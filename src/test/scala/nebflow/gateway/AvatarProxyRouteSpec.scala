package nebflow.gateway

import cats.effect.IO
import cats.effect.std.Dispatcher
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.agent.SharedResources
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.llm.{ModelCandidate, NebflowServiceConfig, ServiceLlmConfig, ThinkingConfig}
import nebflow.neblink.NeblinkService
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.`Content-Type`

import java.net.InetSocketAddress
import java.nio.file.Files

/** 2026-09-07 设置页头像加载态修复的路由契约钉：GET /api/neblink/avatar
  * 同源代理（头像源站无 CORS 头 → 浏览器跨域 fetch 恒失败 → localStorage
  * 缓存永远建不起来 → 每次进设置页都走慢速远端 <img> 直拉 = 「经常加载态」
  * 根因）。钉死：
  *  1. auth 门禁（无 token → 403）
  *  2. NebLink 未配置 → 404 "NebLink not enabled"
  *  3. 身份无 avatarUrl → 404 "no avatar"
  *  4. 上游 200 → 字节与 Content-Type 原样透传
  *  5. 上游非 200 → 502（客户端缓存层按失败退避处理，不污染既有缓存）
  *
  * 时序注意（沿用 FriendApiRoutesSpec 教训）：mock server 的 stop 挂在 IO 的
  * guarantee 上，不能写同步 finally。 */
class AvatarProxyRouteSpec extends CatsEffectSuite:

  private val TestToken = "avatar-spec-token"
  private val AvatarBytes = Array[Byte](1, 2, 3, 4, 5)

  private var tmpDir: java.nio.file.Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("nb-avatar-spec")
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(os.Path(tmpDir, os.pwd))
    super.afterEach(context)

  // ── mock avatar origin ──────────────────────────────────

  private def startMockOrigin: (HttpServer, String) =
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    def respond(ex: HttpExchange, status: Int, bytes: Array[Byte], contentType: String): Unit =
      ex.getRequestBody.transferTo(java.io.OutputStream.nullOutputStream())
      ex.getRequestBody.close()
      ex.getResponseHeaders.add("Content-Type", contentType)
      ex.sendResponseHeaders(status, bytes.length.toLong)
      val os = ex.getResponseBody
      os.write(bytes)
      os.close()
    server.createContext("/a.jpg", ex => respond(ex, 200, AvatarBytes, "image/jpeg"))
    server.createContext("/broken.jpg", ex => respond(ex, 500, "boom".getBytes, "text/plain"))
    server.start()
    (server, s"http://127.0.0.1:${server.getAddress.getPort}")

  // ── routes under test ───────────────────────────────────

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
      historyArchiver = HistoryArchiver.fileSystem(os.temp.dir() / "avatar-spec-archives"),
      fileLockManager = FileLockManager.create.unsafeRunSync(),
      sessionModelOverrides = cats.effect.Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty),
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = null,
      voiceMutedRef = cats.effect.Ref.unsafe[IO, Boolean](false),
      friendService = null
    )

  private def mkRoutes(ms: Option[NeblinkService]): RestApiRoutes =
    new RestApiRoutes(
      token = TestToken,
      configRef = cats.effect.Ref.unsafe[IO, NebflowServiceConfig](
        NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))
      ),
      sharedResources = mkResources,
      sessionStore = null,
      wsRoutes = null,
      neblinkService = ms
    )

  private def avatarReq: Request[IO] =
    Request[IO](Method.GET, Uri.unsafeFromString("/neblink/avatar"))
      .withHeaders(Headers("Authorization" -> s"Bearer $TestToken"))

  /** NeblinkService bound to the temp dataRoot; optional avatarUrl applied. */
  private def withService[A](avatarUrl: Option[String])(use: RestApiRoutes => IO[A]): IO[A] =
    Dispatcher.parallel[IO].use { dispatcher =>
      NeblinkService.create(8099, dispatcher).flatMap { ms =>
        avatarUrl.fold(IO.unit)(u => ms.updateDeviceInfo(avatarUrl = Some(u))) *>
          use(mkRoutes(Some(ms)))
      }
    }

  // ── tests ───────────────────────────────────────────────

  test("auth gate: no token -> 403") {
    mkRoutes(None)
      .routes(Request[IO](Method.GET, Uri.unsafeFromString("/neblink/avatar")))
      .value
      .map(resp => assertEquals(resp.map(_.status), Some(Status.Forbidden)))
  }

  test("neblinkService absent -> 404 NebLink not enabled") {
    mkRoutes(None).routes(avatarReq).value.flatMap { opt =>
      val resp = opt.getOrElse(fail("route fell through"))
      assertEquals(resp.status, Status.NotFound)
      resp.as[Json].map(body =>
        assert(body.hcursor.downField("error").as[String].exists(_ == "NebLink not enabled"))
      )
    }
  }

  test("identity without avatarUrl -> 404 no avatar") {
    withService(None) { routes =>
      routes.routes(avatarReq).value.flatMap { opt =>
        val resp = opt.getOrElse(fail("route fell through"))
        assertEquals(resp.status, Status.NotFound)
        resp.as[Json].map(body =>
          assert(body.hcursor.downField("error").as[String].exists(_ == "no avatar"))
        )
      }
    }
  }

  test("upstream 200 -> bytes and Content-Type pass through") {
    val (origin, base) = startMockOrigin
    withService(Some(s"$base/a.jpg")) { routes =>
      routes.routes(avatarReq).value.flatMap { opt =>
        val resp = opt.getOrElse(fail("route fell through"))
        assertEquals(resp.status, Status.Ok)
        assertEquals(resp.headers.get[`Content-Type`].map(ct => s"${ct.mediaType.mainType}/${ct.mediaType.subType}"), Some("image/jpeg"))
        resp.body.compile.to(Array).map(bytes => assertEquals(bytes.toSeq, AvatarBytes.toSeq))
      }
    }.guarantee(IO.blocking(origin.stop(0)))
  }

  test("upstream non-200 -> 502") {
    val (origin, base) = startMockOrigin
    withService(Some(s"$base/broken.jpg")) { routes =>
      routes.routes(avatarReq).value.flatMap { opt =>
        val resp = opt.getOrElse(fail("route fell through"))
        assertEquals(resp.status, Status.BadGateway)
        resp.as[Json].map(body =>
          assert(body.hcursor.downField("error").as[String].exists(_.contains("upstream HTTP 500")))
        )
      }
    }.guarantee(IO.blocking(origin.stop(0)))
  }

  override def munitIOTimeout: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.DurationInt(60).seconds

end AvatarProxyRouteSpec
