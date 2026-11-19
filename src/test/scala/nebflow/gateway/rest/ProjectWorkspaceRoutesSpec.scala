package nebflow.gateway

import cats.effect.{IO, Ref}
import nebflow.llm.ModelCandidate
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.agent.SharedResources
import nebflow.core.project.ProjectStore
import nebflow.shared.{NebflowServiceConfig, PathUtil, ServiceLlmConfig}
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*

/**
 * Compat-read spec for the project workspace REST face: the managed-workspace
 * mechanism is retired, so the endpoint only renders what the legacy ledger
 * recorded — and every mutating face (cleanup, merge requests, baseline) is
 * gone from the route table.
 */
class ProjectWorkspaceRoutesSpec extends CatsEffectSuite:
  private val previous = PathUtil.dataRoot
  private var root: os.Path = os.pwd
  private var currentResources: SharedResources = null

  override def beforeEach(context: BeforeEach): Unit =
    root = os.temp.dir(prefix = "project-workspace-routes-")
    PathUtil.setDataRoot(root / "data")
    currentResources = makeResources

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(previous)
    os.remove.all(root)

  private def resources = currentResources

  private def makeResources = SharedResources(
    llm = null,
    dispatcher = null,
    sessionStore = null,
    projectRoot = root,
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
    tokenRef = Ref.unsafe[IO, String]("workspace-test"),
    configRef =
      Ref.unsafe[IO, NebflowServiceConfig](NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))),
    sharedResources = resources,
    sessionStore = null,
    wsRoutes = null
  ).routes

  private def mountedRoutes = org.http4s.server.Router("/api" -> routes)

  private def requestOpt(
    method: Method,
    path: String,
    body: Json = Json.obj(),
    auth: Boolean = true
  ): IO[Option[Response[IO]]] =
    val req = Request[IO](method, Uri.unsafeFromString(path)).withEntity(body)
    val authenticated =
      if auth then req.putHeaders(Header.Raw(org.typelevel.ci.CIString("Authorization"), "Bearer workspace-test"))
      else req
    mountedRoutes(authenticated).value

  test("the workspace read route is mounted and requires authentication") {
    for
      anonymous <- requestOpt(Method.GET, "/api/projects/example/workspaces", auth = false)
      missing <- requestOpt(Method.GET, "/api/projects/example/workspaces")
    yield
      assertEquals(anonymous.map(_.status), Some(Status.Forbidden))
      assertEquals(missing.map(_.status), Some(Status.NotFound))
  }

  test("the read-only workspace view remains available for a registered project") {
    val ws = root / "workspace"
    for
      _ <- IO {
        os.makeDir.all(ws)
        os.proc("git", "init", "-q").call(cwd = ws)
      }
      _ <- ProjectStore.create("example", ws.toString, None, "# example\n")
      response <- requestOpt(Method.GET, "/api/projects/example/workspaces")
      body <- response.getOrElse(fail("workspace route must match")).as[Json]
    yield
      assertEquals(response.map(_.status), Some(Status.Ok))
      assert(body.hcursor.downField("workspaces").focus.exists(_.isArray))
  }

  test("the managed-workspace mutation faces are gone from the route table") {
    for
      cleanup <- requestOpt(Method.GET, "/api/projects/example/workspaces/task/cleanup")
      submit <- requestOpt(Method.POST, "/api/projects/example/workspaces/merge-requests")
      cancel <- requestOpt(Method.POST, "/api/projects/example/workspaces/merge-requests/req-1/cancel")
      baseline <- requestOpt(Method.PUT, "/api/projects/example/workspaces/baseline")
    yield
      assertEquals(cleanup, None)
      assertEquals(submit, None)
      assertEquals(cancel, None)
      assertEquals(baseline, None)
  }

end ProjectWorkspaceRoutesSpec
