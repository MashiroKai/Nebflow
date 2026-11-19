package nebflow.gateway

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.agent.SharedResources
import nebflow.core.pdf.{PdfCache, PdfParser}
import nebflow.llm.ModelCandidate
import nebflow.shared.{NebflowServiceConfig, PathUtil, ServiceLlmConfig}
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.typelevel.ci.CIString

/**
 * The HTTP ask leg under concurrency (P0).
 *
 * The defect this pins: the PDF routes built a NEW `PdfCache` per request, so
 * the in-memory single-flight registry (and the manifest it already read, and
 * the publication lock) was thrown away on every request. Four concurrent asks
 * for one unseen document therefore ran four parser subprocesses for the same
 * bytes — the dedup only ever covered callers that happened to share an
 * instance. The fixture here is a real PDF; the fact under test is the number of
 * parses the instance started for this data root, counted at the single point a
 * parser subprocess is about to run (`PdfCache.parseStartsFor`).
 *
 * The requests go through the REAL route table (`RestApiRoutes.routes`, the same
 * value the gateway serves) with a Bearer token — in-process `Request[IO]`, the
 * established repo idiom for route-level HTTP evidence.
 */
class PdfAskHttpConcurrencySpec extends CatsEffectSuite:

  private val previous = PathUtil.dataRoot
  private var temp: os.Path = os.pwd

  private val python = PdfParser.pythonBin()
  private val fixtureScript = os.pwd / "scripts" / "tests" / "pdf_fixture.py"

  private def parserReady: Boolean =
    os.exists(fixtureScript) &&
      scala.util.Try(os.proc(python, "-c", "import pdfplumber, pypdf").call(check = false).exitCode == 0).getOrElse(false)

  override def beforeEach(context: BeforeEach): Unit =
    temp = os.temp.dir(prefix = "pdf-http-")
    PathUtil.setDataRoot(temp / "data")

  override def afterEach(context: AfterEach): Unit =
    PdfCache.forgetShared(temp / "data")
    PathUtil.setDataRoot(previous)
    os.remove.all(temp)

  private def resources = SharedResources(
    llm = null,
    dispatcher = null,
    sessionStore = null,
    projectRoot = temp,
    thinkingConfigRef = null,
    rateLimiter = null,
    fileChangeTracker = null,
    contextWindow = 100000,
    agentLibrary = null,
    taskStore = null,
    historyArchiver = null,
    fileLockManager = null,
    sessionModelOverrides = Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty),
    providerRegistry = null,
    healthMonitor = null,
    actorSystem = null,
    voiceMutedRef = Ref.unsafe[IO, Boolean](false)
  )

  private def routes = new RestApiRoutes(
    tokenRef = Ref.unsafe[IO, String]("pdf-http-test"),
    configRef =
      Ref.unsafe[IO, NebflowServiceConfig](NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))),
    sharedResources = resources,
    sessionStore = null,
    wsRoutes = null
  ).routes

  /** The same mounting the gateway serves: the API table under `/api`. */
  private def app: org.http4s.HttpApp[IO] = org.http4s.server.Router("/api" -> routes).orNotFound

  private def askRequest(path: os.Path, question: String): Request[IO] =
    Request[IO](Method.POST, Uri.unsafeFromString("/api/pdf/ask"))
      .withEntity(Json.obj("path" -> path.toString.asJson, "question" -> question.asJson))
      .putHeaders(Header.Raw(CIString("Authorization"), "Bearer pdf-http-test"))

  test("four concurrent HTTP asks for one document run the parser exactly once") {
    assume(parserReady, "python3 + pdfplumber/pypdf are required for the PDF fixture")
    val fx = temp / "fixtures"
    os.makeDir.all(fx)
    os.proc(python, fixtureScript.toString, fx.toString).call(check = false, stdout = os.Pipe, stderr = os.Pipe)
    val pdf = fx / "simple.pdf"
    assert(os.exists(pdf), s"the fixture must have been written: $pdf")
    val cacheRoot = PdfCache.rootFor(PathUtil.dataRoot)
    val served = app
    val req = askRequest(pdf, "r2c2")
    for
      before <- IO.delay(PdfCache.parseStartsFor(cacheRoot))
      responses <- List.fill(4)(served(req)).parSequence
      after <- IO.delay(PdfCache.parseStartsFor(cacheRoot))
      parses = after - before
      codes = responses.map(_.status.code).sorted
      entry <- PdfCache.forDataRoot(PathUtil.dataRoot).entryCount
      _ = assertEquals(responses.length, 4, s"every concurrent ask must be answered: $codes")
      _ = assert(codes.forall(c => c == 200 || c == 202), s"unexpected statuses: $codes")
      // RED (per-request cache instances): 4 parses for the same bytes.
      _ = assertEquals(parses, 1L, s"the parser must run exactly once for concurrent HTTP asks; it ran $parses time(s)")
      _ = assertEquals(entry, 1, "one document ⇒ one cache entry, however many asks arrived together")
    yield ()
  }
