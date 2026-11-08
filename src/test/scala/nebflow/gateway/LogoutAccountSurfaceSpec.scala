package nebflow.gateway

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import munit.CatsEffectSuite
import nebflow.agent.SharedResources
import nebflow.core.compact.HistoryArchiver
import nebflow.core.root.{AccountRoot, ActiveAccount, LoginGuard}
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.llm.ModelCandidate
import nebflow.neblink.*
import nebflow.neblink.core.*
import nebflow.neblink.identity.*
import nebflow.neblink.relay.*
import nebflow.shared.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*

import java.net.InetSocketAddress
import java.nio.file.Files

/**
 * 登出收尾批 · 账号面拆除（T3 + T5 + 上行排队点）—— **route 级** spec：走真
 * `POST /neblink/logout`（八步 + 本批新增的账号面三步），与
 * `NeblinkLogoutRoutesSpec` 同一夹具族（mock neblink-server + 真
 * NeblinkService/Discovery/RestApiRoutes），但**多钉两层**：
 *
 *  - **机器层指针**（`active-account`）与**账号根目录** —— T3 判据：登出后指针
 *    必须清空、该账号控制台会话必须按账号吊销、账号目录一律保留（登出 ≠ 删数据）；
 *  - **登录门**（`LoginGuard.checkLoginAllowed`）—— T5 判据：登出后 login 不得
 *    静默回原账号。本地腿的静默续用有三根柱子：残留指针（让登录门误判「已在登 A」
 *    → 换号登录被误拒 / 同号登录免验短路）、活控制台会话（登出后控制台照常可用）、
 *    本地凭据（既有第 4 步已删，此处钉住不退化）。本件断言三根柱子在登出后全部消失。
 *
 * == 红绿态登记（测试纪律）==
 * T3/T5 的红态 = **行为红**：本件只引用既有符号即可编译，对着现状跑
 * （performLocalLogout 不清指针、不吊销会话）⇒ 断言失败；接线后转绿。
 * 上行排队点的**结构红**（`LogoutNotifyOutbox` 符号尚不存在）在
 * `nebflow.neblink.LogoutNotifyOutboxSpec` —— 本件对排队点只做**盘面读数**
 * （机器层 `<machineRoot>/logout-notify-pending/` 下 `*.json` 条目，字面 =
 * `LogoutNotifyOutbox.PendingDirName` 单源，由该 spec 交叉钉住），零符号引用。
 *
 * == 隔离纪律 ==
 * 机器层根/有效根 = 临时目录（绝不用 `~/.stand`），用毕恢复原值并删净；
 * mock server / 假活实例端口用后即关；不起真实例、零外联。
 * `GatewayConfig.setPort` 进程级覆盖无复位 API ⇒ 本件残留一次临时口覆盖 ——
 * 测试 JVM 内零影响（全仓测试面零处调 `GatewayConfig.load`，b1-guards 批已核实）。
 */
class LogoutAccountSurfaceSpec extends CatsEffectSuite:

  private val TestToken = "test-token-logout-fin"
  private val Ts = "2026-11-06T12:00:00Z"
  private val SubA = "logto|user-A"
  private val SubB = "logto|user-B"

  /** 排队点目录名（单源 = `LogoutNotifyOutbox.PendingDirName`；LogoutNotifyOutboxSpec 交叉钉）。 */
  private val OutboxDirName = "logout-notify-pending"

  private var tmpDir: java.nio.file.Path = null
  private var savedData: os.Path = scala.compiletime.uninitialized
  private var savedMachine: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedData = PathUtil.dataRoot
    savedMachine = PathUtil.machineRoot
    tmpDir = Files.createTempDirectory("logout-fin-spec")
    PathUtil.setMachineRoot(os.Path(tmpDir, os.pwd))
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedData)
    PathUtil.setMachineRoot(savedMachine)
    os.remove.all(os.Path(tmpDir, os.pwd))
    super.afterEach(context)

  // ── 夹具（与 NeblinkLogoutRoutesSpec 同族：mock server + 全栈） ──────────

  private def startMockServer: IO[(HttpServer, String)] =
    IO.blocking {
      val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)

      def respond(ex: HttpExchange, status: Int, body: String): Unit =
        // Drain the request body first — JDK HttpServer keep-alive requires it.
        ex.getRequestBody.transferTo(java.io.OutputStream.nullOutputStream())
        ex.getRequestBody.close()
        val bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        ex.getResponseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, bytes.length.toLong)
        val os = ex.getResponseBody
        os.write(bytes)
        os.close()

      server.createContext(
        "/api/device/login",
        ex => respond(ex, 200, """{"token":"tok-1","networkId":"n1","deviceId":"d1","peers":[]}""")
      )
      server.createContext("/api/device/logout", ex => respond(ex, 200, """{"ok":true}"""))
      server.start()
      (server, s"http://127.0.0.1:${server.getAddress.getPort}")
    }

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

  /** 本批会话簿 = 显式传入（断言面直接读它；生产装配 = GatewayMain 同位）。 */
  private def mkRoutes(ms: NeblinkService, discovery: NeblinkDiscovery, sessions: GatewaySessionStore): RestApiRoutes =
    new RestApiRoutes(
      tokenRef = cats.effect.Ref.unsafe[IO, String](TestToken),
      configRef = cats.effect.Ref.unsafe[IO, NebflowServiceConfig](
        NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))
      ),
      sessions = sessions,
      sharedResources = mkResources,
      sessionStore = null,
      wsRoutes = null,
      neblinkService = Some(ms),
      neblinkDiscovery = Some(discovery)
    )

  private case class Stack(
    ms: NeblinkService,
    tunnel: NeblinkRelayTunnel,
    discovery: NeblinkDiscovery,
    client: NeblinkClient,
    routes: RestApiRoutes
  )

  private def mkStack(serverUrl: String, dispatcher: Dispatcher[IO], sessions: GatewaySessionStore): IO[Stack] =
    for
      ms <- NeblinkService.create(0, dispatcher)
      client = new NeblinkClient(NeblinkServerConfig(url = serverUrl, networkId = "n1", secret = "s"), 0)
      tunnel = new NeblinkRelayTunnel(ms, () => IO.pure(client.currentSessionToken))(dispatcher)
      _ = ms.setRelayClient(Some(client))
      _ = ms.setRelayTunnel(tunnel)
      _ <- ms.setRelayTunnelStarter(tunnel.ensure())
      discovery = new NeblinkDiscovery(ms, 0, null, Some(client))
      _ <- ms.updateConfig(
        _.copy(
          enabled = true,
          neblinkServer = Some(NeblinkServerConfig(url = serverUrl, networkId = "n1", secret = "s"))
        )
      )
      _ <- DeviceCredential.save(DeviceCredential(serverUrl, "n1", "d1", "dev-tok"))
    yield Stack(ms, tunnel, discovery, client, mkRoutes(ms, discovery, sessions))

  private def logoutRequest: Request[IO] =
    Request[IO](Method.POST, Uri.unsafeFromString("/neblink/logout"))
      .withHeaders(Headers("Authorization" -> s"Bearer $TestToken"))

  /** 预置「机器已登录 A」：layout 标记 + 账号根 + 指针（登录腿的等价面），返回账号目录。 */
  private def seedSignedInAs(root: os.Path, sub: String, email: String): os.Path =
    AccountRoot.writeLayoutMarker(root)
    val dir = AccountRoot.ensureAccountDir(root, sub)
    AccountRoot.writePointer(root, ActiveAccount(sub, email, Ts))
    dir

  /** 排队点盘面读数：`<machineRoot>/logout-notify-pending/` 下全部条目原文。 */
  private def outboxEntries: List[String] =
    val dir = os.Path(tmpDir, os.pwd) / OutboxDirName
    if !os.exists(dir) then Nil
    else os.list(dir).filter(_.ext == "json").toList.map(os.read(_)).sorted

  /** 拿一个当前空闲的临时端口（用后本用例独占它）。 */
  private def freePort(): Int =
    val s = new java.net.ServerSocket(0)
    try s.getLocalPort
    finally s.close()

  /**
   * 环回假活实例（b1-guards 批同款）：本品牌健康面应答本品牌词 ⇒
   * `LoginGuard` 的探活链把它归为活实例。返回 IO 括号（bracket 保证停机）。
   */
  private def withFakeLiveInstance[A](port: Int)(inner: IO[A]): IO[A] =
    IO.blocking {
      val s = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0)
      s.createContext("/api/health", exchange => {
        val body =
          s"""{"product":"${Branding.lowerName}","status":"ok","version":"logout-fin-spec"}""".getBytes(
            java.nio.charset.StandardCharsets.UTF_8
          )
        exchange.getResponseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, body.length.toLong)
        val out = exchange.getResponseBody
        try out.write(body)
        finally
          out.close()
          exchange.close()
      })
      s.setExecutor(null)
      s.start()
      s
    }.bracket(_ => inner)(s => IO.blocking(s.stop(0)))

  // ── T3：登出后指针清空 + 该账号控制台会话吊销（他账号不受累 + 账号目录保留） ──

  test("T3: logout 后 active-account 指针清空 + 该账号控制台会话按账号吊销（B 会话不受累，账号目录保留）"):
    Dispatcher.parallel[IO].use { dispatcher =>
      startMockServer.flatMap { (server, url) =>
        GatewaySessionStore.create().flatMap { sessions =>
          mkStack(url, dispatcher, sessions).flatMap { st =>
            for
              dirA <- IO(seedSignedInAs(os.Path(tmpDir, os.pwd), SubA, "a@example.com"))
              sessA <- sessions.create(SubA, GatewaySessionStore.OriginRelayMint, "net-1")
              sessB <- sessions.create(SubB, GatewaySessionStore.OriginRelayMint, "net-1")
              _ <- st.client.login("d1", "dev", "macos", Nil)
              resp <- st.routes.routes(logoutRequest).value.map(_.getOrElse(fail("route fell through")))
              pointerAfter <- IO(AccountRoot.readPointer(os.Path(tmpDir, os.pwd)))
              sessAAfter <- sessions.verifyAndTouch(sessA.sessionId)
              sessBAfter <- sessions.verifyAndTouch(sessB.sessionId)
              entries <- IO(outboxEntries)
            yield
              assertEquals(resp.status, Status.Ok)
              assertEquals(pointerAfter, None, "登出后机器层 active-account 指针必须清空（现状残留 = T3 红）")
              assertEquals(sessAAfter, None, "被登出账号的控制台会话必须吊销（现状仍活 = T3 红）")
              assert(sessBAfter.isDefined, "他账号的控制台会话不受累 —— 按账号吊销，不是全清")
              assert(os.isDir(dirA), "登出 ≠ 删数据：账号目录必须保留（设计 §3.5）")
              assertEquals(entries.length, 1, "设备登出确认不等于账号域确认，必须保留账号通知")
              assert(entries.head.contains(SubA), "账号通知必须带已登出账号")
          }.guarantee(IO.blocking(server.stop(0)))
        }
      }
    }

  test("无设备会话时仍保留账号域登出通知"):
    Dispatcher.parallel[IO].use { dispatcher =>
      startMockServer.flatMap { (server, url) =>
        GatewaySessionStore.create().flatMap { sessions =>
          mkStack(url, dispatcher, sessions).flatMap { st =>
            for
              _ <- IO(seedSignedInAs(os.Path(tmpDir, os.pwd), SubA, "a@example.com"))
              resp <- st.routes.routes(logoutRequest).value.map(_.getOrElse(fail("route fell through")))
              entries <- IO(outboxEntries)
            yield
              assertEquals(resp.status, Status.Ok)
              assertEquals(entries.length, 1)
              assert(entries.head.contains(SubA))
          }.guarantee(IO.blocking(server.stop(0)))
        }
      }
    }

  test("持久化失败保留账号指针，重试后撤销不会在重启复活"):
    val root = os.Path(tmpDir, os.pwd)
    val snapshot = root / "sessions.json"
    val temp = root / "sessions.json.tmp"
    for
      _ <- IO(seedSignedInAs(root, SubA, "a@example.com"))
      sessions <- GatewaySessionStore.create(persistenceFile = Some(snapshot))
      rec <- sessions.create(SubA, GatewaySessionStore.OriginRelayMint, "net")
      _ <- IO(os.makeDir(temp))
      failed <- AuthRoutes.clearAccountSurface(sessions, root).attempt
      _ = assert(failed.isLeft)
      _ = assert(AccountRoot.readPointer(root).isDefined)
      _ <- IO(os.remove(temp))
      _ <- AuthRoutes.clearAccountSurface(sessions, root)
      _ = sessions.shutdown()
      restored <- GatewaySessionStore.create(persistenceFile = Some(snapshot))
      hit <- restored.verifyAndTouch(rec.sessionId)
      _ = assertEquals(hit, None)
      _ = restored.shutdown()
    yield ()

  test("损坏指针不能确定账号时撤销全部本地控制台会话"):
    val root = os.Path(tmpDir, os.pwd)
    for
      _ <- IO(seedSignedInAs(root, SubA, "a@example.com"))
      _ <- IO(os.write.over(AccountRoot.pointerPath(root), "invalid"))
      sessions <- GatewaySessionStore.create()
      rec <- sessions.create(SubA, GatewaySessionStore.OriginRelayMint, "net")
      _ <- AuthRoutes.clearAccountSurface(sessions, root)
      hit <- sessions.verifyAndTouch(rec.sessionId)
      _ = assertEquals(hit, None)
    yield ()

  // ── T5：登出后 login 不得静默回原账号（本仓本地腿） ─────────────────────

  test("T5: 登出后 login 不静默回原账号 —— 残留指针/活会话/本地凭据三根静默续用柱子全部消失"):
    Dispatcher.parallel[IO].use { dispatcher =>
      startMockServer.flatMap { (server, url) =>
        GatewaySessionStore.create().flatMap { sessions =>
          mkStack(url, dispatcher, sessions).flatMap { st =>
            val port = freePort()
            GatewayConfig.setPort(port)
            withFakeLiveInstance(port) {
              for
                _ <- IO(seedSignedInAs(os.Path(tmpDir, os.pwd), SubA, "a@example.com"))
                sessA <- sessions.create(SubA, GatewaySessionStore.OriginRelayMint, "net-1")
                _ <- st.client.login("d1", "dev", "macos", Nil)
                resp <- st.routes.routes(logoutRequest).value.map(_.getOrElse(fail("route fell through")))
                gate <- LoginGuard.checkLoginAllowed("b-user@example.com")
                sessAAfter <- sessions.verifyAndTouch(sessA.sessionId)
                pointerAfter <- IO(AccountRoot.readPointer(os.Path(tmpDir, os.pwd)))
                cred <- DeviceCredential.load
              yield
                assertEquals(resp.status, Status.Ok)
                assertEquals(pointerAfter, None, "静默续用柱①（残留指针让登录门误判「已在登 A」）必须消失")
                assertEquals(
                  gate,
                  Right(()),
                  "登出后 B 登录必须放行 —— 现状残留指针 + 活实例 ⇒ 误拒 account-switch-requires-logout（T5 红态）"
                )
                assertEquals(sessAAfter, None, "静默续用柱②（活控制台会话，登出后控制台照常可用）必须消失")
                assertEquals(cred, None, "静默续用柱③（本地凭据）不在 —— 既有第 4 步语义，钉住不退化")
            }.guarantee(IO.blocking(server.stop(0)))
          }
        }
      }
    }

  // ── 上行通知排队点：活通知未达 ⇒ 机器层落一条待发记录（离线也记账） ────────

  test("上行排队点: 活通知未证实送达（服务不可达）⇒ 机器层恰记一条账号域待发记录，登出照常 200"):
    Dispatcher.parallel[IO].use { dispatcher =>
      startMockServer.flatMap { (server, url) =>
        GatewaySessionStore.create().flatMap { sessions =>
          mkStack(url, dispatcher, sessions).flatMap { st =>
            for
              _ <- IO(seedSignedInAs(os.Path(tmpDir, os.pwd), SubA, "a@example.com"))
              _ <- st.client.login("d1", "dev", "macos", Nil)
              _ <- IO.blocking(server.stop(0)) // 服务先走：活通知必不达
              resp <- st.routes.routes(logoutRequest).value.map(_.getOrElse(fail("route fell through")))
              pointerAfter <- IO(AccountRoot.readPointer(os.Path(tmpDir, os.pwd)))
              entries <- IO(outboxEntries)
            yield
              assertEquals(resp.status, Status.Ok, "离线登出必须照常 200（Always completes locally）")
              assertEquals(pointerAfter, None, "离线登出同样清指针（账号面拆除不依赖上行可达）")
              assertEquals(
                entries.length,
                1,
                s"活通知未证实送达 ⇒ 排队点必须恰记一条（现状零记账 = 红）: $entries"
              )
              assert(entries.headOption.exists(_.contains(SubA)), s"待发记录必须携带账号域身份 accountSub: $entries")
          }
        }
      }
    }

end LogoutAccountSurfaceSpec
