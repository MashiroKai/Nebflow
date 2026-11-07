package nebflow.gateway.static

import nebflow.gateway.*

import nebflow.neblink.core.*
import nebflow.neblink.misc.*
import nebflow.neblink.social.*

import cats.effect.IO
import cats.effect.std.Dispatcher
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.agent.SharedResources
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.llm.ModelCandidate
import nebflow.neblink.core.AgentMessagingConfig
import nebflow.neblink.misc.AvatarProxy
import nebflow.neblink.social.FriendService
import nebflow.neblink.core.NeblinkClient
import nebflow.neblink.core.NeblinkServerConfig
import nebflow.neblink.core.NeblinkService
import nebflow.shared.{NebflowServiceConfig, PathUtil, ServiceLlmConfig, ThinkingConfig}
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * `GET /api/avatars/{userId}` —— 对端头像同源只读路由的契约钉（sessperf Phase B
 * 前置项，方案卡 §3 / §4③；作者 2026-09-20 18:55 令 B「出生即包 apiguard 鉴权闸」）。
 *
 * 钉死五组：
 *  1. **出生即包鉴权**：无令牌 ⇒ 403（先于任何分派，零上游往返）；NebLink 未配置
 *     ⇒ 404；上游 `Not logged in` ⇒ 401 + `code=neblink_not_logged_in`（与网关 403
 *     的**有意分化**，见 `friendErr` 注释）。
 *  2. **不是开放代理**：客户端只给 `userId`，URL 由网关从本账号好友档案解出 ⇒
 *     非好友 / 好友无头像 ⇒ 404 `no avatar`；请求方无法指定任意 URL（SSRF 面为零）。
 *  3. **内容指纹面**：`X-Avatar-Sha256` == sha256(bytes) == 强 `ETag` 的内容；
 *     `Cache-Control: private` 在场（客户端「hash 未变 ⇒ 复用旧 blob」的判据面）。
 *  4. **协商**：`If-None-Match` 命中 ⇒ **304 且零重传**；不命中 ⇒ 200 全量。
 *  5. 上游非 200 ⇒ 502（客户端按失败退避，不污染既有缓存）。
 *
 * 时序注意（沿用 FriendApiRoutesSpec / AvatarProxyRouteSpec 教训）：mock server 的
 * stop 必须挂在 IO 的 guarantee 上，不能写同步 finally。
 */
class PeerAvatarRouteSpec extends CatsEffectSuite:

  private val TestToken = "peer-avatar-spec-token"
  private val AvatarBytes = Array[Byte](1, 2, 3, 4, 5)

  /** 指纹面（sha256 已知向量在下方单测里另钉）。 */
  private val AvatarSha = AvatarProxy.sha256Hex(AvatarBytes)

  private var tmpDir: java.nio.file.Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("nb-peer-avatar-spec")
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(os.Path(tmpDir, os.pwd))
    super.afterEach(context)

  // ── mock 上游（好友列表 / 头像源站）──────────────────────────

  /** mock 头像源站句柄：base + 每路径命中计数（「不再回源」的可判据面）。 */
  private final class MockOrigin(val server: HttpServer, val base: String):

    private val counts =
      new java.util.concurrent.ConcurrentHashMap[String, java.util.concurrent.atomic.AtomicInteger]()

    def hit(path: String): Unit =
      counts.computeIfAbsent(path, _ => new java.util.concurrent.atomic.AtomicInteger(0)).incrementAndGet()
    def hits(path: String): Int = Option(counts.get(path)).fold(0)(_.get)

  /** 一个只服务 `/a.jpg` 的**第三方**源站（白名单外跳转目标夹具）。 */
  private def startThirdPartyOrigin: IO[(HttpServer, String)] =
    IO.blocking {
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
      server.start()
      (server, s"http://127.0.0.1:${server.getAddress.getPort}")
    }

  /**
   * mock 头像源站：
   *  · `/a.jpg` = 200 5 字节 image/jpeg；`/broken.jpg` = 500；
   *  · `/moved.jpg` = **301 → /a.jpg**（同 origin 跳转）—— 线上
   *    「好友头像 URL 仍指旧域、旧域今天 301 跳走」的等价形状，本批缺陷的复现夹具；
   *  · `/loop.jpg` = 301 → 自身（跳数上限夹具）；
   *  · `/offsite.jpg` = 302 → `offsiteTarget`（白名单外跳转夹具，防开放代理）。
   */
  private def startMockOrigin(offsiteTarget: String): IO[MockOrigin] =
    IO.blocking {
      val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
      def respond(ex: HttpExchange, status: Int, bytes: Array[Byte], contentType: String): Unit =
        ex.getRequestBody.transferTo(java.io.OutputStream.nullOutputStream())
        ex.getRequestBody.close()
        ex.getResponseHeaders.add("Content-Type", contentType)
        ex.sendResponseHeaders(status, bytes.length.toLong)
        val os = ex.getResponseBody
        os.write(bytes)
        os.close()
      def redirect(ex: HttpExchange, status: Int, location: String): Unit =
        ex.getRequestBody.transferTo(java.io.OutputStream.nullOutputStream())
        ex.getRequestBody.close()
        ex.getResponseHeaders.add("Location", location)
        ex.sendResponseHeaders(status, -1L)
        ex.close()
      val origin = new MockOrigin(server, s"http://127.0.0.1:${server.getAddress.getPort}")
      server.createContext(
        "/a.jpg",
        ex =>
          origin.hit("/a.jpg")
          respond(ex, 200, AvatarBytes, "image/jpeg")
      )
      server.createContext(
        "/broken.jpg",
        ex =>
          origin.hit("/broken.jpg")
          respond(ex, 500, "boom".getBytes, "text/plain")
      )
      server.createContext(
        "/moved.jpg",
        ex =>
          origin.hit("/moved.jpg")
          redirect(ex, 301, "/a.jpg")
      )
      server.createContext(
        "/loop.jpg",
        ex =>
          origin.hit("/loop.jpg")
          redirect(ex, 301, "/loop.jpg")
      )
      server.createContext(
        "/offsite.jpg",
        ex =>
          origin.hit("/offsite.jpg")
          redirect(ex, 302, offsiteTarget + "/a.jpg")
      )
      server.start()
      origin
    }

  /**
   * mock 好友服务：`u1.avatar = <originBase>/a.jpg`（或调用方给的路径）、
   *  `u2.avatar = null`（好友但无头像）。
   */
  private def startMockFriends(avatarUrl: String): IO[(HttpServer, String)] =
    IO.blocking {
      val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
      def respond(ex: HttpExchange, status: Int, body: String): Unit =
        ex.getRequestBody.transferTo(java.io.OutputStream.nullOutputStream())
        ex.getRequestBody.close()
        val bytes = body.getBytes(StandardCharsets.UTF_8)
        ex.getResponseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, bytes.length.toLong)
        val os = ex.getResponseBody
        os.write(bytes)
        os.close()
      val friendsJson =
        s"""{"friends":[{"userId":"u1","username":"lin","display_name":"林小满","avatar":"$avatarUrl"},""" +
          """{"userId":"u2","username":"bob","display_name":"Bob","avatar":null}],"incoming":[],"outgoing":[]}"""
      server.createContext(
        "/api/device/login",
        ex => respond(ex, 200, """{"token":"tok-1","networkId":"n1","deviceId":"d1","peers":[]}""")
      )
      server.createContext("/api/friends", ex => respond(ex, 200, friendsJson))
      server.start()
      (server, s"http://127.0.0.1:${server.getAddress.getPort}")
    }

  // ── routes under test ───────────────────────────────────

  private def mkClient(url: String): NeblinkClient =
    new NeblinkClient(NeblinkServerConfig(url = url, networkId = "n1", secret = "s"), 8080)

  private def mkResources(fs: Option[FriendService]): SharedResources =
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
      historyArchiver = HistoryArchiver.fileSystem(os.temp.dir() / "peer-avatar-spec-archives"),
      fileLockManager = FileLockManager.create.unsafeRunSync(),
      sessionModelOverrides = cats.effect.Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty),
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = null,
      voiceMutedRef = cats.effect.Ref.unsafe[IO, Boolean](false),
      friendService = fs
    )

  private def mkRoutes(fs: Option[FriendService], ms: Option[NeblinkService] = None): RestApiRoutes =
    new RestApiRoutes(
      tokenRef = cats.effect.Ref.unsafe[IO, String](TestToken),
      configRef = cats.effect.Ref.unsafe[IO, NebflowServiceConfig](
        NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))
      ),
      sharedResources = mkResources(fs),
      sessionStore = null,
      wsRoutes = null,
      neblinkService = ms
    )

  private def authed(path: String, extra: List[Header.Raw] = Nil): Request[IO] =
    Request[IO](Method.GET, Uri.unsafeFromString(path))
      .withHeaders(Headers(Header.Raw(CIString("Authorization"), s"Bearer $TestToken") :: extra))

  private def runWith(fs: Option[FriendService], ms: Option[NeblinkService] = None)(
    req: Request[IO]
  ): IO[Response[IO]] =
    mkRoutes(fs, ms).routes(req).value.map(_.getOrElse(fail("route fell through")))

  private def header(resp: Response[IO], name: String): Option[String] =
    resp.headers.headers.find(_.name.toString.equalsIgnoreCase(name)).map(_.value)

  /**
   * 起 mock 好友服务 + **已登录**的 FriendService（`sessionToken` 必须在位：
   * `NeblinkClient.withSessionPlain` 对 None 直接 `Left("Not logged in")`，
   * 见 FriendApiRoutesSpec 的同款前置），并在收尾收割 mock server。
   */
  private def withFriendService[A](avatarUrl: String)(use: Option[FriendService] => IO[A]): IO[A] =
    startMockFriends(avatarUrl).flatMap { case (server, base) =>
      val client = mkClient(base)
      val fs = Some(new FriendService(IO.pure(Some(client)), AgentMessagingConfig()))
      client.login("d1", "dev", "macos", Nil) *> use(fs)
        .guarantee(IO.blocking(server.stop(0)))
    }

  /** 起 mock 头像源站 + mock 好友服务（后者指向给定的源站路径）。 */
  private def withBoth[A](avatarPath: String)(use: Option[FriendService] => IO[A]): IO[A] =
    withOrigin(avatarPath)((_, fs) => use(fs))

  /** 同上，但把源站句柄一并交给用例（命中计数 = 「有没有回源」的判据）。 */
  private def withOrigin[A](avatarPath: String)(
    use: (MockOrigin, Option[FriendService]) => IO[A]
  ): IO[A] =
    startThirdPartyOrigin.flatMap { case (thirdParty, thirdPartyBase) =>
      startMockOrigin(thirdPartyBase).flatMap { origin =>
        withFriendService(origin.base + avatarPath)(fs => use(origin, fs))
          .guarantee(IO.blocking(origin.server.stop(0)))
          .guarantee(IO.blocking(thirdParty.stop(0)))
      }
    }

  /**
   * 一个「已配置但无会话」的 NeblinkService（401 分态判据需要它：`friendErr` 的
   * 「已配置 + Not logged in ⇒ 401」分支）。隔离 dataRoot，绝不读写真实 ~/.nebflow。
   */
  private def withConfiguredService[A](use: NeblinkService => IO[A]): IO[A] =
    Dispatcher.parallel[IO].use { dispatcher =>
      NeblinkService.create(0, dispatcher).flatMap { ms =>
        ms.updateConfig(
          _.copy(
            enabled = true,
            neblinkServer = Some(NeblinkServerConfig(url = "http://127.0.0.1:9", networkId = "n1", secret = "s"))
          )
        ) *> use(ms)
      }
    }

  // ── tests ───────────────────────────────────────────────

  test("sha256Hex: 已知向量 + 纯函数性") {
    assertEquals(
      AvatarProxy.sha256Hex(Array.emptyByteArray),
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    )
    assertEquals(AvatarProxy.sha256Hex(AvatarBytes).length, 64)
    assertEquals(AvatarSha, AvatarProxy.sha256Hex(AvatarBytes))
  }

  test("auth gate: no token -> 403（出生即闸；先于任何分派）") {
    // friendService 缺席也必须是 403 而不是 404 —— 鉴权在分派**之前**。
    runWith(None)(Request[IO](Method.GET, Uri.unsafeFromString("/avatars/u1")))
      .map(resp => assertEquals(resp.status, Status.Forbidden))
  }

  test("friendService absent -> 404 NebLink not enabled（fail-closed）") {
    runWith(None)(authed("/avatars/u1")).flatMap { resp =>
      assertEquals(resp.status, Status.NotFound)
      resp.as[Json].map(body => assert(body.hcursor.downField("error").as[String].exists(_ == "NebLink not enabled")))
    }
  }

  test("friend with avatar -> 200 + 字节 + Content-Type + sha256 头 + ETag + Cache-Control") {
    withBoth("/a.jpg") { fs =>
      runWith(fs)(authed("/avatars/u1")).flatMap { resp =>
        assertEquals(resp.status, Status.Ok)
        assertEquals(
          resp.headers.get[`Content-Type`].map(ct => s"${ct.mediaType.mainType}/${ct.mediaType.subType}"),
          Some("image/jpeg")
        )
        assertEquals(header(resp, "X-Avatar-Sha256"), Some(AvatarSha))
        assertEquals(header(resp, "ETag"), Some("\"" + AvatarSha + "\""))
        assert(header(resp, "Cache-Control").exists(_.startsWith("private")))
        resp.body.compile.to(Array).map(bytes => assertEquals(bytes.toSeq, AvatarBytes.toSeq))
      }
    }
  }

  test("If-None-Match 命中 -> 304 零重传；不命中 -> 200 全量") {
    withBoth("/a.jpg") { fs =>
      val hit = authed("/avatars/u1", List(Header.Raw(CIString("If-None-Match"), "\"" + AvatarSha + "\"")))
      runWith(fs)(hit).flatMap { r1 =>
        assertEquals(r1.status, Status.NotModified)
        assertEquals(header(r1, "ETag"), Some("\"" + AvatarSha + "\""))
        r1.body.compile.to(Array).flatMap { body1 =>
          assertEquals(body1.length, 0) // 304 不带体
          val miss = authed("/avatars/u1", List(Header.Raw(CIString("If-None-Match"), "\"deadbeef\"")))
          runWith(fs)(miss).flatMap { r2 =>
            assertEquals(r2.status, Status.Ok)
            r2.body.compile.to(Array).map(body2 => assertEquals(body2.toSeq, AvatarBytes.toSeq))
          }
        }
      }
    }
  }

  test("非好友 / 好友无头像 -> 404 no avatar（不是开放代理）") {
    withBoth("/a.jpg") { fs =>
      for
        r1 <- runWith(fs)(authed("/avatars/u2")) // 好友但 avatar: null
        _ = assertEquals(r1.status, Status.NotFound)
        b1 <- r1.as[Json]
        _ = assert(b1.hcursor.downField("error").as[String].exists(_ == "no avatar"))
        r2 <- runWith(fs)(authed("/avatars/u-does-not-exist"))
        b2 <- r2.as[Json]
      yield
        assertEquals(r2.status, Status.NotFound)
        assert(b2.hcursor.downField("error").as[String].exists(_ == "no avatar"))
    }
  }

  test("上游头像非 200 -> 502（客户端按失败退避，不污染既有缓存）") {
    withBoth("/broken.jpg") { fs =>
      runWith(fs)(authed("/avatars/u1")).flatMap { resp =>
        assertEquals(resp.status, Status.BadGateway)
        resp
          .as[Json]
          .map(body => assert(body.hcursor.downField("error").as[String].exists(_.contains("upstream HTTP 500"))))
      }
    }
  }

  // ── 2026-11-04 旧域 301 批：跳转跟随 + 网关字节缓存 ─────────

  /*
   * 缺陷（作者 2026-11-04 手机端头像批顺带查出）：好友头像 URL 仍指旧域
   * `mashiro.staging.nebflow.space`，域名迁移后该域 **301 跳走**；而代理腿
   * （`AvatarProxy.jdkFetch`，JDK HttpClient 默认 `Redirect.NEVER`）把 301 当
   * 「上游非 200」⇒ 本路由 **502 `upstream HTTP 301`** ⇒ 字节缓存永远建不起来。
   *
   * 下面四组钉住修复后的行为（缺一条即回归）：
   *  ① 同源 301 ⇒ **跟随**并返回终点字节（红：修复前恒 502）；
   *  ② 跳数上限：自指 301 不得无限跟（⇒ 502 且错误文本点名跳数）；
   *  ③ 白名单外跳转（另一 origin 的第三方源站）⇒ 拒跟 + 502，**且第三方站点零命中**
   *     —— 防「代返代理被跳转改造成开放代理」；
   *  ④ 网关字节缓存：同一 URL 第二次请求（含 `If-None-Match` 命中）**零上游往返**。
   */

  test("同源 301 ⇒ 跟随跳转返回终点字节（修复前：502 upstream HTTP 301）") {
    withOrigin("/moved.jpg") { (origin, fs) =>
      runWith(fs)(authed("/avatars/u1")).flatMap { resp =>
        assertEquals(resp.status, Status.Ok)
        assertEquals(header(resp, "X-Avatar-Sha256"), Some(AvatarSha))
        assertEquals(header(resp, "ETag"), Some("\"" + AvatarSha + "\""))
        resp.body.compile.to(Array).map { bytes =>
          assertEquals(bytes.toSeq, AvatarBytes.toSeq)
          // 跳转确实被走了：起点命中 1 次、终点命中 1 次。
          assertEquals(origin.hits("/moved.jpg"), 1)
          assertEquals(origin.hits("/a.jpg"), 1)
          // 验收读数（报告引用；日志里即可直接抄）
          println(
            s"[EVIDENCE] 301-follow status=${resp.status.code} bytes=${bytes.length} " +
              s"sha256=${header(resp, "X-Avatar-Sha256").getOrElse("")} " +
              s"cache=${header(resp, "X-Avatar-Cache").getOrElse("")} " +
              s"hits(start)=${origin.hits("/moved.jpg")} hits(final)=${origin.hits("/a.jpg")}"
          )
        }
      }
    }
  }

  test("自指 301 循环 ⇒ 跳数上限拒绝（502，不无限跟随）") {
    withOrigin("/loop.jpg") { (origin, fs) =>
      runWith(fs)(authed("/avatars/u1")).flatMap { resp =>
        assertEquals(resp.status, Status.BadGateway)
        resp.as[Json].map { body =>
          val err = body.hcursor.downField("error").as[String].toOption.getOrElse("")
          assert(err.contains("redirect"), s"error should name the redirect failure, got: $err")
          // 上限 = 1 + MaxRedirects 次单跳（起点 + 3 跳）
          assert(origin.hits("/loop.jpg") <= 4, s"hop cap must stop the chain, hits=${origin.hits("/loop.jpg")}")
        }
      }
    }
  }

  test("白名单外跳转 ⇒ 拒跟 + 502，且第三方源站零命中（不是开放代理）") {
    withOrigin("/offsite.jpg") { (origin, fs) =>
      runWith(fs)(authed("/avatars/u1")).flatMap { resp =>
        assertEquals(resp.status, Status.BadGateway)
        resp.as[Json].map { body =>
          val err = body.hcursor.downField("error").as[String].toOption.getOrElse("")
          assert(err.contains("redirect"), s"error should name the redirect refusal, got: $err")
          assertEquals(origin.hits("/a.jpg"), 0)
        }
      }
    }
  }

  test("网关字节缓存：同一 URL 第二次请求零回源（含 If-None-Match ⇒ 304）") {
    withOrigin("/a.jpg") { (origin, fs) =>
      for
        r1 <- runWith(fs)(authed("/avatars/u1"))
        b1 <- r1.body.compile.to(Array)
        _ = assertEquals(r1.status, Status.Ok)
        _ = assertEquals(b1.toSeq, AvatarBytes.toSeq)
        _ = assertEquals(header(r1, "X-Avatar-Cache"), Some("miss"))
        _ = assertEquals(origin.hits("/a.jpg"), 1)
        r2 <- runWith(fs)(authed("/avatars/u1"))
        b2 <- r2.body.compile.to(Array)
        _ = assertEquals(r2.status, Status.Ok)
        _ = assertEquals(b2.toSeq, AvatarBytes.toSeq)
        _ = assertEquals(header(r2, "X-Avatar-Cache"), Some("hit"))
        _ = assertEquals(origin.hits("/a.jpg"), 1, "second request must not go back to origin")
        r3 <- runWith(fs)(
          authed("/avatars/u1", List(Header.Raw(CIString("If-None-Match"), "\"" + AvatarSha + "\"")))
        )
        _ = assertEquals(r3.status, Status.NotModified)
        _ = assertEquals(header(r3, "X-Avatar-Cache"), Some("hit"))
        _ = assertEquals(origin.hits("/a.jpg"), 1, "304 path must be served from cache too")
        _ = println(
          s"[EVIDENCE] avatar-cache #1 status=${r1.status.code} bytes=${b1.length} " +
            s"sha256=${header(r1, "X-Avatar-Sha256").getOrElse("")} cache=${header(r1, "X-Avatar-Cache").getOrElse("")} upstreamHits=${origin.hits("/a.jpg")}"
        )
        _ = println(
          s"[EVIDENCE] avatar-cache #2 status=${r2.status.code} bytes=${b2.length} " +
            s"sha256=${header(r2, "X-Avatar-Sha256").getOrElse("")} cache=${header(r2, "X-Avatar-Cache").getOrElse("")} upstreamHits=${origin.hits("/a.jpg")}"
        )
        _ = println(
          s"[EVIDENCE] avatar-cache #3(If-None-Match) status=${r3.status.code} bytes=0 " +
            s"cache=${header(r3, "X-Avatar-Cache").getOrElse("")} upstreamHits=${origin.hits("/a.jpg")}"
        )
      yield ()
    }
  }

  test("未登录（已配置 + 无会话）-> 401 code=neblink_not_logged_in") {
    // 已配置（server 址在 live config）但无会话 ⇒ `friendErr` 的 401 分态 —— 与
    // 网关自身 403 的**有意分化**（同一端点族两种「未认证」，靠 code 消歧）。
    withConfiguredService { ms =>
      val fs = Some(new FriendService(IO.pure(None), AgentMessagingConfig()))
      runWith(fs, Some(ms))(authed("/avatars/u1")).flatMap { resp =>
        assertEquals(resp.status, Status.Unauthorized)
        resp.as[Json].map { body =>
          assertEquals(body.hcursor.downField("error").as[String].toOption, Some("Not logged in"))
          assertEquals(body.hcursor.downField("code").as[String].toOption, Some("neblink_not_logged_in"))
        }
      }
    }
  }

  override def munitIOTimeout: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.DurationInt(60).seconds

end PeerAvatarRouteSpec
