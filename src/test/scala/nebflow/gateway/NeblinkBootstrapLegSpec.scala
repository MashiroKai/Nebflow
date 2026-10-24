package nebflow.gateway

import cats.effect.IO
import cats.effect.std.Dispatcher
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.agent.SharedResources
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.llm.ModelCandidate
import nebflow.neblink.{LogtoConfig, NeblinkService}
import nebflow.shared.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*

import java.nio.file.Files

/**
 * 引导腿（bootstrap-leg）路由门测试（2026-10-22 新设备引导修复批·作者令）。
 *
 * 缺陷（作者 Windows 机实跑 + 实截实证）：`POST /api/neblink/auth/start` 与
 * `GET /api/neblink/status` 原在 `gate`（session/Bearer 判据）后面，而新设备
 * 引导恰是「零会话 cookie、零令牌」时的场景 ⇒ 解锁命令 403、控制台头部恒挂
 * 「状态读取失败（HTTP 403）」、点登录无效——登录永远无法开始（鸡生蛋缺陷，
 * 主流程一号缺陷）。修复：start / state / status 三面改走
 * [[RestApiCtx.bootstrapGate]]（回调 /auth/callback 本就匿名）——有凭据判据
 * 逐字不变；判 None 时仅在**环回腿**（LoopbackLeg.isLoopback，机械 socket
 * 对端地址）放行。
 *
 * 本 spec 钉四条腿判据（远端构造用 [[SpecRequests]]，同 rest-api 单元面，
 * 无 `/api` 前缀）：
 *  ① 匿名 + 环回（v4 / v4-mapped 双形态）⇒ start 200 带 authorizeUrl、
 *     state 200 读到单飞槽状态、status 200 读到 loggedIn——引导腿通；
 *  ② 匿名 + LAN（192.168.x）⇒ 403 逐字体不变——引导腿不出环回；
 *  ③ 匿名 + 隧道形态（无连接信息，remoteAddr=None）⇒ 403——保守判非，
 *     与 localTicketOnLeg 同口径；
 *  ④ 有凭据（Bearer 文件令牌）从 LAN / 隧道形态 ⇒ 照常 200——凭据调用
 *     零回归（bootstrapGate 只在判 None 时开引导腿）。
 */
class NeblinkBootstrapLegSpec extends CatsEffectSuite:

  private val TestToken = "test-token-bootstrap"

  private var tmpDir: java.nio.file.Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("nb-bootstrap-leg-spec")
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(os.Path(tmpDir, os.pwd))
    super.afterEach(context)

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
      historyArchiver = HistoryArchiver.fileSystem(os.Path(tmpDir, os.pwd) / "archives"),
      fileLockManager = FileLockManager.create.unsafeRunSync(),
      sessionModelOverrides = cats.effect.Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty),
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = null,
      voiceMutedRef = cats.effect.Ref.unsafe[IO, Boolean](false),
      friendService = None
    )

  /**
   * start 只读 ms.neblinkConfig（effectiveLogto 单点），presence/tunnel/discovery
   * 都不参与 ⇒ stack 到 ms 为止（同 DeviceFaceHardeningRoutesSpec 的最小装配）。
   */
  private def mkStack(dispatcher: Dispatcher[IO]): IO[(NeblinkService, RestApiRoutes)] =
    NeblinkService.create(0, dispatcher).map { ms =>
      val routes = new RestApiRoutes(
        tokenRef = cats.effect.Ref.unsafe[IO, String](TestToken),
        configRef = cats.effect.Ref.unsafe[IO, NebflowServiceConfig](
          NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))
        ),
        sharedResources = mkResources,
        sessionStore = null,
        wsRoutes = null,
        neblinkService = Some(ms)
      )
      (ms, routes)
    }

  /**
   * Explicit logto block with a PKCE client: authorizeUrl is built LOCALLY
   * (no network). The embedded default would also resolve, but the explicit
   * block keeps the spec independent of the embedded product config
   * (same shape the NeblinkLogoutRoutesSpec start tests pin).
   */
  private def configureLogto(ms: NeblinkService): IO[Unit] =
    ms.updateConfig(
      _.copy(logto =
        Some(LogtoConfig(endpoint = "https://auth.example", clientId = "legacy", pkceClientId = Some("pkce-app")))
      )
    )

  // ── request builders ────────────────────────────────────

  private def startReq: Request[IO] = Request[IO](Method.POST, Uri.unsafeFromString("/neblink/auth/start"))
  private def stateReq: Request[IO] = Request[IO](Method.GET, Uri.unsafeFromString("/neblink/auth/state"))
  private def statusReqGet: Request[IO] = Request[IO](Method.GET, Uri.unsafeFromString("/neblink/status"))

  private def loopbackStart: Request[IO] = SpecRequests.withRemote(startReq, Some(SpecRequests.LoopbackV4))
  private def loopbackState: Request[IO] = SpecRequests.withRemote(stateReq, Some(SpecRequests.LoopbackV4))
  private def lanStart: Request[IO] = SpecRequests.withRemote(startReq, Some(SpecRequests.Lan))
  private def lanState: Request[IO] = SpecRequests.withRemote(stateReq, Some(SpecRequests.Lan))
  // remote=None = the tunnel bridge's in-process HTTP shape.
  private def tunnelStart: Request[IO] = SpecRequests.withRemote(startReq, None)
  private def tunnelState: Request[IO] = SpecRequests.withRemote(stateReq, None)

  private def authed(r: Request[IO]): Request[IO] =
    r.withHeaders(Headers("Authorization" -> s"Bearer $TestToken"))

  private def serve(routes: RestApiRoutes, req: Request[IO]): IO[Response[IO]] =
    routes.routes(req).value.map(_.getOrElse(fail("route fell through")))

  // ── ① anonymous + loopback ⇒ bootstrap leg opens ─────────

  test("anonymous start on the loopback leg returns an authorizeUrl (bootstrap leg)") {
    Dispatcher.parallel[IO].use { dispatcher =>
      mkStack(dispatcher).flatMap { case (ms, routes) =>
        for
          _ <- configureLogto(ms)
          resp <- serve(routes, loopbackStart)
          body <- resp.as[Json]
        yield
          assertEquals(resp.status, Status.Ok)
          val url = body.hcursor.downField("authorizeUrl").as[String].toOption.getOrElse(fail("authorizeUrl missing"))
          assert(url.contains("response_type=code"), s"PKCE authorize URL: $url")
          assert(url.contains("state="), s"one-time state nonce present: $url")
          assert(url.contains("code_challenge="), s"PKCE challenge present: $url")
      }
    }
  }

  test("anonymous start on the v4-mapped loopback form also opens (dual-stack socket reality)") {
    Dispatcher.parallel[IO].use { dispatcher =>
      mkStack(dispatcher).flatMap { case (ms, routes) =>
        for
          _ <- configureLogto(ms)
          req = SpecRequests.withRemote(startReq, Some(SpecRequests.LoopbackMapped))
          resp <- serve(routes, req)
        yield assertEquals(resp.status, Status.Ok, "v4-mapped loopback collapses onto the loopback leg")
      }
    }
  }

  test("anonymous state on the loopback leg reads the single-flight slot (pending after start)") {
    Dispatcher.parallel[IO].use { dispatcher =>
      mkStack(dispatcher).flatMap { case (ms, routes) =>
        for
          _ <- configureLogto(ms)
          _ <- serve(routes, loopbackStart) // arm the single-flight slot anonymously
          resp <- serve(routes, loopbackState)
          body <- resp.as[Json]
        yield
          assertEquals(resp.status, Status.Ok)
          assertEquals(body.hcursor.downField("status").as[String], Right("pending"))
      }
    }
  }

  // ── ②③ anonymous off the loopback leg ⇒ 403, body unchanged ──

  test("anonymous start from a LAN address still answers 403 Unauthorized") {
    Dispatcher.parallel[IO].use { dispatcher =>
      mkStack(dispatcher).flatMap { case (ms, routes) =>
        for
          _ <- configureLogto(ms)
          resp <- serve(routes, lanStart)
          body <- resp.as[Json]
        yield
          assertEquals(resp.status, Status.Forbidden)
          assertEquals(body, Json.obj("error" -> "Unauthorized".asJson))
      }
    }
  }

  test("anonymous state from a LAN address still answers 403") {
    Dispatcher.parallel[IO].use { dispatcher =>
      mkStack(dispatcher).flatMap { case (ms, routes) =>
        for
          _ <- configureLogto(ms)
          resp <- serve(routes, lanState)
        yield assertEquals(resp.status, Status.Forbidden)
      }
    }
  }

  test("anonymous start/state on the tunnel shape (remote=None) still answers 403 (conservative leg)") {
    Dispatcher.parallel[IO].use { dispatcher =>
      mkStack(dispatcher).flatMap { case (ms, routes) =>
        for
          _ <- configureLogto(ms)
          sResp <- serve(routes, tunnelStart)
          stResp <- serve(routes, tunnelState)
        yield
          assertEquals(sResp.status, Status.Forbidden, "tunnel bridge leg is not a loopback leg")
          assertEquals(stResp.status, Status.Forbidden, "state on the tunnel leg stays gated")
      }
    }
  }

  // ── ④ credentialed callers: zero regression ──────────────

  test("credentialed start from a LAN address still works (bearer rail untouched by the bootstrap leg)") {
    Dispatcher.parallel[IO].use { dispatcher =>
      mkStack(dispatcher).flatMap { case (ms, routes) =>
        for
          _ <- configureLogto(ms)
          resp <- serve(routes, authed(lanStart))
          body <- resp.as[Json]
        yield
          assertEquals(resp.status, Status.Ok)
          assert(
            body.hcursor.downField("authorizeUrl").as[String].toOption.exists(_.contains("response_type=code")),
            s"credentialed login unchanged: $body"
          )
      }
    }
  }

  test("credentialed state on the tunnel shape (bearer, remote=None) still works") {
    Dispatcher.parallel[IO].use { dispatcher =>
      mkStack(dispatcher).flatMap { case (ms, routes) =>
        for
          _ <- configureLogto(ms)
          _ <- serve(routes, authed(lanStart))
          resp <- serve(routes, authed(tunnelState))
          body <- resp.as[Json]
        yield
          assertEquals(resp.status, Status.Ok)
          assertEquals(body.hcursor.downField("status").as[String], Right("pending"))
      }
    }
  }

  // ── 状态读取面（引导链第三条腿）：GET /neblink/status ──────────────────
  //
  // 作者实截（Windows 零凭据设备）：控制台头部恒挂「状态读取失败（HTTP 403）」
  // = 本面在 gate 后面。开腿后：匿名环回读得到 loggedIn（登录成功后控制台的
  // 头像/账号面才收得了口）；LAN/隧道形态照旧 403；凭据调用零回归。

  private def loopbackStatus: Request[IO] = SpecRequests.withRemote(statusReqGet, Some(SpecRequests.LoopbackV4))
  private def lanStatus: Request[IO] = SpecRequests.withRemote(statusReqGet, Some(SpecRequests.Lan))
  private def tunnelStatus: Request[IO] = SpecRequests.withRemote(statusReqGet, None)

  test("anonymous status on the loopback leg answers 200 with loggedIn (状态读取引导面)") {
    Dispatcher.parallel[IO].use { dispatcher =>
      mkStack(dispatcher).flatMap { case (ms, routes) =>
        for
          _ <- configureLogto(ms)
          resp <- serve(routes, loopbackStatus)
          body <- resp.as[Json]
        yield
          assertEquals(resp.status, Status.Ok)
          // Fresh spec env: no device credential ⇒ loggedIn=false — the point is
          // the READ lands (200 + the field the console renders), not the value.
          assert(
            body.hcursor.downField("loggedIn").as[Boolean].toOption.isDefined,
            s"status body must carry loggedIn for the console header: $body"
          )
          assert(
            body.hcursor.downField("device").downField("id").as[String].toOption.exists(_.nonEmpty),
            s"device identity readable: $body"
          )
      }
    }
  }

  test("anonymous status from a LAN address still answers 403") {
    Dispatcher.parallel[IO].use { dispatcher =>
      mkStack(dispatcher).flatMap { case (ms, routes) =>
        for
          _ <- configureLogto(ms)
          resp <- serve(routes, lanStatus)
          body <- resp.as[Json]
        yield
          assertEquals(resp.status, Status.Forbidden)
          assertEquals(body, Json.obj("error" -> "Unauthorized".asJson))
      }
    }
  }

  test("anonymous status on the tunnel shape (remote=None) still answers 403") {
    Dispatcher.parallel[IO].use { dispatcher =>
      mkStack(dispatcher).flatMap { case (ms, routes) =>
        for
          _ <- configureLogto(ms)
          resp <- serve(routes, tunnelStatus)
        yield assertEquals(resp.status, Status.Forbidden, "tunnel bridge leg is not a loopback leg")
      }
    }
  }

  test("credentialed status from a LAN address still works (bearer rail untouched)") {
    Dispatcher.parallel[IO].use { dispatcher =>
      mkStack(dispatcher).flatMap { case (ms, routes) =>
        for
          _ <- configureLogto(ms)
          resp <- serve(routes, authed(lanStatus))
          body <- resp.as[Json]
        yield
          assertEquals(resp.status, Status.Ok)
          assert(
            body.hcursor.downField("loggedIn").as[Boolean].toOption.isDefined,
            s"credentialed read unchanged: $body"
          )
      }
    }
  }

end NeblinkBootstrapLegSpec
