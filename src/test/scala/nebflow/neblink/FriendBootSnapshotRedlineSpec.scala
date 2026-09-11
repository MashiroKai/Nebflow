package nebflow.neblink

import cats.effect.{IO, Ref}
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import io.circe.Json
import io.circe.parser.parse
import munit.CatsEffectSuite
import nebflow.agent.SharedResources
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.RestApiRoutes
import nebflow.llm.{ModelCandidate, NebflowServiceConfig, ServiceLlmConfig, ThinkingConfig}
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.concurrent.duration.*

/**
 * 好友域 boot 快照缺陷（2026-09-11）—— 行为级离线红线 spec。
 *
 * 缺陷（判决书 `.nebflow/evidence/frienddiag/20260911_closeout-verdict.md` 定论 ③）：
 * `GatewayMain` 把「好友域是否存在」编码成 boot 期快照
 * （`neblinkClient: Option[…]`，全新 home ⇒ `None`）⇒ 16 个 `/api/friends*`
 * 站点恒走 `case None => 404 "NebLink not enabled"`；UI 登录只 hot-swap
 * client（`NeblinkEnrollment.persist`），**不重建好友域** ⇒ 除重启进程外不自愈。
 * 前端把 404 折叠成「暂无好友请求」空态（定论 ④），掩盖了故障。
 *
 * 本 spec 是**行为级红线**（不是编译红、不是既有 :217 不变量）：
 *  - 全部离线：`NeblinkService.createForTest` + 手写字面量 enroll 响应
 *    （先例 `NeblinkEnrollmentPersistSpec:42-52`）+ 本地 127.0.0.1 mock 上游；
 *    **零生产 enrollment、零外部网络**（不对 VPS 做任何登录/重登）。
 *  - boot 等价：`NeblinkDiscovery(…, initialClient = None)` + 生产装配缝
 *    `NeblinkWiring.sharedResourcesSlot(bootClient = None, …)`
 *    （= `GatewayMain:668-688` 的 boot 快照落在 None）。
 *  - 断言即**修复后应然语义**，修前必须真红、修后自然翻绿，断言逐字不改：
 *      1) 热切换后不重启 ⇒ `GET /api/friends` = 200 且 incoming 非空；
 *      2) 已配置但未登录 ⇒ 401 + `code=neblink_not_logged_in`（修前 502）；
 *      3) 未配置 ⇒ 仍 404 `NebLink not enabled`（`neblinkOff` 必须保持可表达，
 *         即 A 案的前置依赖 R3(a) 的回归闸）。
 *  - 残留缺口（如实标注）：本 spec 不经过 `GatewayMain` 的字面绑定 —— 装配缝
 *    `NeblinkWiring` 承接了那条判据，`GatewayMain` 端到端仍属静态数据流论证 +
 *    8097 运行态读数（见 impl 报告「残留缺口」节）。
 */
class FriendBootSnapshotRedlineSpec extends CatsEffectSuite:

  private val TestToken = "test-token-redline"
  private val GatewayPort = 8099

  private var tmpDir: java.nio.file.Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("nb-friend-redline-spec")
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(os.Path(tmpDir, os.pwd))
    super.afterEach(context)

  // ── 本地 mock 上游（127.0.0.1 临时端口；不是生产 VPS） ──────────────────

  /** 上游 friend list：判决书 §1 的形态（outgoing 有、incoming 有一条 pending）。 */
  private val friendsJson =
    """{"friends":[{"userId":"u1","username":"lin","display_name":"林小满","avatar":null,"blocked":false}],
       "incoming":[{"requestId":"rq-9","userId":"u-new","username":"newbie42","display_name":"新同学","avatar":null,"note":"你好","createdAt":1234560000}],
       "outgoing":[]}""".stripMargin.replaceAll("\\n\\s*", "")

  private def startUpstream: (HttpServer, String) =
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    def respond(ex: HttpExchange, status: Int, body: String): Unit =
      // JDK HttpServer keep-alive: the request body must be drained.
      ex.getRequestBody.transferTo(java.io.OutputStream.nullOutputStream())
      ex.getRequestBody.close()
      val bytes = body.getBytes(StandardCharsets.UTF_8)
      ex.getResponseHeaders.add("Content-Type", "application/json")
      ex.sendResponseHeaders(status, bytes.length.toLong)
      ex.getResponseBody.write(bytes)
      ex.getResponseBody.close()
    // persist 造出的 fresh client 带 deviceToken ⇒ 走 /api/device/session；
    // 无 credential 的启动客户端走 /api/device/login。两条都答同一形态。
    val loginBody = """{"token":"tok-1","networkId":"n1","deviceId":"d1","peers":[]}"""
    server.createContext("/api/device/session", ex => respond(ex, 200, loginBody))
    server.createContext("/api/device/login", ex => respond(ex, 200, loginBody))
    server.createContext(
      "/api/friends",
      ex => if ex.getRequestURI.getPath == "/api/friends" then respond(ex, 200, friendsJson)
            else respond(ex, 404, """{"error":"not found"}""")
    )
    server.start()
    (server, s"http://127.0.0.1:${server.getAddress.getPort}")

  private def withUpstream[A](body: String => IO[A]): IO[A] =
    IO.delay(startUpstream).flatMap { (server, url) =>
      body(url).guarantee(IO.blocking(server.stop(0)))
    }

  // ── 被测装配（生产缝）等价于 GatewayMain 的接线 ─────────────────────────

  private def mkResources(slot: Option[FriendService]): SharedResources =
    SharedResources(
      llm = null,
      dispatcher = null,
      sessionStore = null,
      projectRoot = os.pwd,
      thinkingConfigRef = Ref.unsafe[IO, ThinkingConfig](ThinkingConfig()),
      rateLimiter = null,
      fileChangeTracker = null,
      contextWindow = 100_000,
      agentLibrary = null,
      taskStore = FileTaskStore,
      historyArchiver = HistoryArchiver.fileSystem(os.Path(tmpDir, os.pwd) / "archives"),
      fileLockManager = FileLockManager.create.unsafeRunSync(),
      sessionModelOverrides = Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty),
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = null,
      voiceMutedRef = Ref.unsafe[IO, Boolean](false),
      friendService = slot
    )

  private def mkRoutes(
    slot: Option[FriendService],
    ms: NeblinkService,
    discovery: NeblinkDiscovery
  ): RestApiRoutes =
    new RestApiRoutes(
      token = TestToken,
      configRef = Ref.unsafe[IO, NebflowServiceConfig](
        NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))
      ),
      sharedResources = mkResources(slot),
      sessionStore = null,
      wsRoutes = null,
      neblinkService = Some(ms),
      neblinkDiscovery = Some(discovery)
    )

  private def get(routes: RestApiRoutes, path: String): IO[Response[IO]] =
    routes.routes(
      Request[IO](Method.GET, Uri.unsafeFromString(path))
        .withHeaders(Headers("Authorization" -> s"Bearer $TestToken"))
    ).value.map(_.getOrElse(fail(s"route fell through: $path")))

  private def enrollJson(token: String): Json =
    parse(s"""{"deviceToken":"$token","networkId":"n1","avatarUrl":null,"githubUsername":null}""")
      .toOption
      .get

  override def munitIOTimeout: FiniteDuration = 60.seconds

  // ── 红线 1：热切换后不重启必须可达 ─────────────────────────────────────

  test("RED LINE 1: UI login without a restart makes the friend domain reachable (200 + incoming)") {
    Dispatcher.parallel[IO].use { dispatcher =>
      withUpstream { url =>
        for
          ms <- NeblinkService.createForTest(GatewayPort, dispatcher, 15.seconds)
          presence = new NeblinkPresenceService(ms, GatewayPort)(dispatcher)
          // boot 等价：全新 home（无 neblink/config.json）⇒ GatewayMain 的 boot 客户端快照 = None
          bootClient: Option[NeblinkClient] = None
          discovery = new NeblinkDiscovery(ms, GatewayPort, presence, bootClient)
          // 生产装配缝：FriendService 本体 per-call live（F1），存在性判据在 slot
          friendService = NeblinkWiring.friendService(discovery.currentClient, AgentMessagingConfig())
          slot = NeblinkWiring.sharedResourcesSlot(bootClient, friendService)
          routes = mkRoutes(slot, ms, discovery)
          // 离线 enrollment 驱动（手写字面量响应；零网络、零生产 enrollment）
          _ <- NeblinkEnrollment.persist(ms, url, enrollJson("tok-boot"), None, Some(discovery), GatewayPort, None)
          // 断言 A：状态面已声称登录 —— 事故指纹的前半（loggedIn=true 而好友域 404）
          statusResp <- get(routes, "/neblink/status")
          statusBody <- statusResp.as[Json]
          // 热切换后的 live client 建会话（只打本地 mock）
          fresh <- discovery.currentClient
          _ <- IO(assert(fresh.isDefined, "persist 必须把 fresh client 热切换进 discovery"))
          _ <- fresh.get.login("d1", "dev", "macos", Nil).void
          // 断言 B：不重启即可达
          resp <- get(routes, "/friends")
          body <- resp.as[Json]
        yield
          assertEquals(statusResp.status, Status.Ok)
          assertEquals(
            statusBody.hcursor.downField("loggedIn").as[Boolean].toOption,
            Some(true),
            "/neblink/status 在 persist 之后必须声称已登录（事故指纹：状态面乐观、好友域 404）"
          )
          assertEquals(
            resp.status,
            Status.Ok,
            s"persist 热切换后不重启必须可达；实际 ${resp.status.code} body=${body.noSpaces.take(300)}"
          )
          assert(
            body.hcursor.downField("incoming").as[Vector[Json]].exists(_.nonEmpty),
            s"incoming 必须非空（判决书 §1 的 pending 行形态）；实际 body=${body.noSpaces.take(300)}"
          )
      }
    }
  }

  // ── 红线 2：已配置 + 未登录 ⇒ 401（修前 502 retryable） ──────────────────

  test("RED LINE 2: configured home without a session answers 401 neblink_not_logged_in") {
    Dispatcher.parallel[IO].use { dispatcher =>
      withUpstream { url =>
        for
          ms <- NeblinkService.createForTest(GatewayPort, dispatcher, 15.seconds)
          presence = new NeblinkPresenceService(ms, GatewayPort)(dispatcher)
          discovery = new NeblinkDiscovery(ms, GatewayPort, presence, None)
          // 已配置（persist 把 server 址写进 live config）+ 随后登出（client 清空）
          _ <- NeblinkEnrollment.persist(ms, url, enrollJson("tok-2"), None, Some(discovery), GatewayPort, None)
          _ <- discovery.setClient(None)
          friendService = NeblinkWiring.friendService(discovery.currentClient, AgentMessagingConfig())
          routes = mkRoutes(Some(friendService), ms, discovery)
          resp <- get(routes, "/friends")
          body <- resp.as[Json]
        yield
          assertEquals(
            resp.status,
            Status.Unauthorized,
            s"已配置但无会话必须是 401（「未登录」与「上游错」不能再同一 502 面）；实际 ${resp.status.code} body=${body.noSpaces.take(300)}"
          )
          assertEquals(
            body.hcursor.downField("code").as[String].toOption,
            Some("neblink_not_logged_in"),
            "401 必须带可消歧的 code（同端点族 withAuth 用 403，这是有意的契约分化）"
          )
      }
    }
  }

  // ── 红线 3：未配置 ⇒ 仍 404（neblinkOff 必须保持可表达） ────────────────

  test("RED LINE 3: unconfigured home still answers 404 NebLink not enabled") {
    Dispatcher.parallel[IO].use { dispatcher =>
      withUpstream { _ =>
        for
          ms <- NeblinkService.createForTest(GatewayPort, dispatcher, 15.seconds)
          presence = new NeblinkPresenceService(ms, GatewayPort)(dispatcher)
          // 全新 home：不驱动 persist ⇒ neblinkServer 未定义、无 credential
          discovery = new NeblinkDiscovery(ms, GatewayPort, presence, None)
          friendService = NeblinkWiring.friendService(discovery.currentClient, AgentMessagingConfig())
          routes = mkRoutes(Some(friendService), ms, discovery)
          resp <- get(routes, "/friends")
          body <- resp.as[Json]
        yield
          assertEquals(
            resp.status,
            Status.NotFound,
            s"未配置 home 必须保持 404 —— 否则前端 errKind='neblinkOff' 不再可表达、retry 恒无效（净回归）；实际 ${resp.status.code} body=${body.noSpaces.take(300)}"
          )
          assertEquals(
            body.hcursor.downField("error").as[String].toOption,
            Some("NebLink not enabled"),
            "404 body 必须维持既有字面（前端/CLI 契约）"
          )
      }
    }
  }

end FriendBootSnapshotRedlineSpec
