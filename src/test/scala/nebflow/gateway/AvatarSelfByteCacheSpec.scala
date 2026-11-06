package nebflow.gateway

import cats.effect.IO
import cats.effect.std.Dispatcher
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.agent.SharedResources
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.llm.ModelCandidate
import nebflow.neblink.core.NeblinkService
import nebflow.shared.{NebflowServiceConfig, PathUtil, ServiceLlmConfig, ThinkingConfig}
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString

import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * 自己的头像路由（`GET /api/neblink/avatar`）的**网关字节缓存**回归闸
 * （2026-11-04 两小件批 · 件二）。
 *
 * 对端头像路由（`/api/neblink/avatars/:userId`）在 a6b3ad30 批已经接上
 * `AvatarByteCache`（TTL 10 min / 64 条 / 16MB / 单条 2MB + 最旧淘汰 + 失败不入
 * 缓存 + `X-Avatar-Cache: hit|miss` 自证头），自己的头像这条腿当时**明写未做**，
 * 于是设置页/列表每次打开都要回源一次远源头像。
 *
 * 判据（可判读、非「感觉快了」）：
 *  1. 第二次同源请求 ⇒ `X-Avatar-Cache: hit`；
 *  2. 上游命中次数**不增**（mock origin 计数，1 → 1）；
 *  3. 字节 / Content-Type 与第一次逐字节一致（命中不许吐错内容）；
 *  4. 上游失败**不入缓存**（第二次仍然回源、仍然 502）。
 */
class AvatarSelfByteCacheSpec extends CatsEffectSuite:

  private val TestToken = "avatar-self-cache-token"
  private val AvatarBytes = Array[Byte](9, 8, 7, 6, 5)

  private var tmpDir: java.nio.file.Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("nb-avatar-self-cache")
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(os.Path(tmpDir, os.pwd))
    super.afterEach(context)

  /** mock 头像源：`/self.jpg` 计数 200，`/broken-self.jpg` 计数 500。 */
  private def startMockOrigin: (HttpServer, String, AtomicInteger, AtomicInteger) =
    val okHits = new AtomicInteger(0)
    val brokenHits = new AtomicInteger(0)
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    def respond(ex: HttpExchange, status: Int, bytes: Array[Byte], contentType: String): Unit =
      ex.getRequestBody.transferTo(java.io.OutputStream.nullOutputStream())
      ex.getRequestBody.close()
      ex.getResponseHeaders.add("Content-Type", contentType)
      ex.sendResponseHeaders(status, bytes.length.toLong)
      val os = ex.getResponseBody
      os.write(bytes)
      os.close()
    server.createContext(
      "/self.jpg",
      ex =>
        okHits.incrementAndGet()
        respond(ex, 200, AvatarBytes, "image/jpeg")
    )
    server.createContext(
      "/broken-self.jpg",
      ex =>
        brokenHits.incrementAndGet()
        respond(ex, 500, "boom".getBytes, "text/plain")
    )
    server.start()
    (server, s"http://127.0.0.1:${server.getAddress.getPort}", okHits, brokenHits)

  private def mkResources: SharedResources =
    SharedResources(
      llm = null,
      dispatcher = null,
      sessionStore = null,
      projectRoot = os.pwd,
      thinkingConfigRef = cats.effect.Ref.unsafe[IO, ThinkingConfig](ThinkingConfig()),
      rateLimiter = null,
      fileChangeTracker = null,
      contextWindow = 100_000,
      agentLibrary = null,
      taskStore = FileTaskStore,
      historyArchiver = HistoryArchiver.fileSystem(os.temp.dir() / "avatar-self-cache-archives"),
      fileLockManager = FileLockManager.create.unsafeRunSync(),
      sessionModelOverrides = cats.effect.Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty),
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = null,
      voiceMutedRef = cats.effect.Ref.unsafe[IO, Boolean](false),
      friendService = null
    )

  private def mkRoutes(ms: NeblinkService): RestApiRoutes =
    new RestApiRoutes(
      tokenRef = cats.effect.Ref.unsafe[IO, String](TestToken),
      configRef = cats.effect.Ref.unsafe[IO, NebflowServiceConfig](
        NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))
      ),
      sharedResources = mkResources,
      sessionStore = null,
      wsRoutes = null,
      neblinkService = Some(ms)
    )

  private def avatarReq: Request[IO] =
    Request[IO](Method.GET, Uri.unsafeFromString("/neblink/avatar"))
      .withHeaders(Headers("Authorization" -> s"Bearer $TestToken"))

  private def withService[A](avatarUrl: String)(use: RestApiRoutes => IO[A]): IO[A] =
    Dispatcher.parallel[IO].use { dispatcher =>
      NeblinkService.create(8098, dispatcher).flatMap { ms =>
        ms.updateDeviceInfo(avatarUrl = Some(avatarUrl)) *> use(mkRoutes(ms))
      }
    }

  private def cacheHeader(resp: Response[IO]): Option[String] =
    resp.headers.get(CIString("X-Avatar-Cache")).map(_.head.value.trim)

  private def oneCall(routes: RestApiRoutes): IO[Response[IO]] =
    routes.routes(avatarReq).value.map(_.getOrElse(fail("route fell through")))

  test("second request to the own avatar route is served from cache (hit, zero upstream)") {
    val (origin, base, okHits, _) = startMockOrigin
    withService(s"$base/self.jpg") { routes =>
      for
        first <- oneCall(routes)
        firstBytes <- first.body.compile.to(Array)
        firstState = cacheHeader(first)
        second <- oneCall(routes)
        secondBytes <- second.body.compile.to(Array)
        secondState = cacheHeader(second)
        hits <- IO(okHits.get())
      yield
        assertEquals(first.status, Status.Ok)
        assertEquals(firstBytes.toSeq, AvatarBytes.toSeq)
        assertEquals(second.status, Status.Ok)
        assertEquals(secondBytes.toSeq, AvatarBytes.toSeq, "命中不许吐错字节")
        assertEquals(
          second.headers.get[`Content-Type`].map(ct => s"${ct.mediaType.mainType}/${ct.mediaType.subType}"),
          Some("image/jpeg"),
          "命中也要保留 Content-Type"
        )
        assertEquals(firstState, Some("miss"), s"第一次必须是 miss（实读 $firstState）")
        assertEquals(secondState, Some("hit"), s"第二次必须是 hit（实读 $secondState）")
        assertEquals(hits, 1, s"上游命中次数必须停在 1（实读 $hits）")
    }.guarantee(IO.blocking(origin.stop(0)))
  }

  test("upstream failure is never cached: the second call still hits upstream and still 502s") {
    val (origin, base, _, brokenHits) = startMockOrigin
    withService(s"$base/broken-self.jpg") { routes =>
      for
        first <- oneCall(routes)
        second <- oneCall(routes)
        hits <- IO(brokenHits.get())
      yield
        assertEquals(first.status, Status.BadGateway)
        assertEquals(second.status, Status.BadGateway)
        assertEquals(hits, 2, s"失败不入缓存 ⇒ 第二次仍回源（实读 $hits）")
    }.guarantee(IO.blocking(origin.stop(0)))
  }

  override def munitIOTimeout: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.DurationInt(60).seconds

end AvatarSelfByteCacheSpec
