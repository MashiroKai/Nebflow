package nebflow.neblink

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.agent.SharedResources
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.RestApiRoutes
import nebflow.llm.ModelCandidate
import nebflow.shared.{NebflowServiceConfig, PathUtil, ServiceLlmConfig, ThinkingConfig}
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*

import java.nio.file.Files
import scala.concurrent.duration.*

/**
 * F7 route-level nail (2026-09-10 隧道鉴权自愈批, report §4 F7).
 *
 * `/neblink/status` reported relay health as `relayAvailable = tunnel.isAlive`
 * only — "tunnel dead" hid WHY it was dead, so a session the server had kicked
 * (401/403 → self-healable, client-side) looked identical to a relay/gateway
 * outage (5xx → server-side, the report §6 cross-project discriminator).
 *
 * This drives the REAL route against a fixture that always rejects the upgrade,
 * and asserts the new independent `relay.authRejected` state is what the
 * endpoint actually returns (the JSON rendering itself is unit-tested in
 * RelayTunnelDiagnosticsSpec — here the wiring is what is under test).
 */
class NeblinkRelayStatusRouteSpec extends CatsEffectSuite:

  private val TestToken = "test-token-relay-status"
  private val Net = "qa-net"
  private val Device = "qa-device"

  private var tmpDir: java.nio.file.Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("nb-relay-status-spec")
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(os.Path(tmpDir, os.pwd))
    super.afterEach(context)

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

  private def mkRoutes(ms: NeblinkService, discovery: NeblinkDiscovery): RestApiRoutes =
    new RestApiRoutes(
      token = TestToken,
      configRef = cats.effect.Ref.unsafe[IO, NebflowServiceConfig](
        NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))
      ),
      sharedResources = mkResources,
      sessionStore = null,
      wsRoutes = null,
      neblinkService = Some(ms),
      neblinkDiscovery = Some(discovery)
    )

  private def statusRequest: Request[IO] =
    Request[IO](Method.GET, Uri.unsafeFromString("/neblink/status"))
      .withHeaders(Headers("Authorization" -> s"Bearer $TestToken"))

  private def waitUntil(timeout: FiniteDuration)(cond: IO[Boolean]): IO[Boolean] =
    IO.monotonic.flatMap { start =>
      def loop: IO[Boolean] =
        cond.flatMap { ok =>
          if ok then IO.pure(true)
          else
            IO.monotonic.flatMap { now =>
              if now - start > timeout then IO.pure(false) else IO.sleep(50.millis) *> loop
            }
        }
      loop
    }

  test("/neblink/status exposes relay.authRejected with the rejection status code") {
    IO.blocking(new RelayAuthFixtureServer()).flatMap { fix =>
      Dispatcher
        .parallel[IO]
        .use { dispatcher =>
          for
            ms <- NeblinkService.create(0, dispatcher)
            client = new NeblinkClient(
              NeblinkServerConfig(url = fix.url, networkId = Net, secret = "qa-secret"),
              0,
              identity = Some(IO.pure(DeviceIdentity(Device, "qa-host", "macos")))
            )
            _ = ms.setRelayClient(Some(client))
            _ <- client.login(Device, "qa-host", "macos", Nil)
            // The fixture (Auth403 mode) accepts nothing: once the session is
            // kicked the upgrade is rejected forever — the incident's shape.
            _ <- IO { fix.kickSessionOf(Device, Net); () }
            // 2026-09-11：隧道 URL 改为连接期 live 解析（构造参已移除）⇒
            // 测试改为把 server 址写进 config ref（= 生产里 updateConfig/enrollment 的等价物）。
            tunnel = new NeblinkRelayTunnel(ms, () => IO(client.currentSessionToken))(dispatcher)
            _ = ms.setRelayTunnel(tunnel)
            ps = new NeblinkPresenceService(ms, 0)(dispatcher)
            discovery = new NeblinkDiscovery(ms, 0, ps, Some(client))
            _ <- ms.updateConfig(
              _.copy(
                enabled = true,
                neblinkServer = Some(NeblinkServerConfig(url = fix.url, networkId = Net, secret = "qa-secret"))
              )
            )
            _ <- DeviceCredential.save(DeviceCredential(fix.url, Net, Device, "dev-tok"))
            // Keep the session permanently dead: the fixture rejects the upgrade
            // (403) AND refuses further logins, so the tunnel stays parked on the
            // auth rejection — the state F7 exists to surface.
            _ <- IO { fix.failLogins = true; () }
            fiber <- tunnel.connect().start
            out <-
              (
                for
                  rejected <- waitUntil(10.seconds)(IO(tunnel.authStatus.exists(_.statusCode == 403)))
                  _ <- IO(assert(rejected, s"the tunnel must record the 403 rejection; attempts=${fix.relayAttempts}"))
                  resp <- mkRoutes(ms, discovery)
                    .routes(statusRequest)
                    .value
                    .map(_.getOrElse(fail("route fell through")))
                  body <- resp.as[Json]
                yield (resp.status, body.hcursor.downField("relay"))
              ).guarantee(fiber.cancel *> tunnel.stop())
          yield
            val (status, relay) = out
            assertEquals(status, Status.Ok)
            assertEquals(relay.downField("available").as[Boolean].toOption, Some(false), "tunnel is not up")
            assertEquals(
              relay.downField("authRejected").as[Boolean].toOption,
              Some(true),
              "the independent F7 state: down BECAUSE our session was rejected"
            )
            assertEquals(relay.downField("lastRejectedStatusCode").as[Int].toOption, Some(403))
            assert(relay.downField("lastRejectedAt").as[Long].toOption.exists(_ > 0L), "timestamp recorded")
            assert(
              relay
                .downField("selfHeal")
                .as[String]
                .toOption
                .exists(s => s == "ok" || s == "failed" || s == "not-attempted"),
              s"self-heal outcome must be reported: ${relay.downField("selfHeal").focus}"
            )
        }
        .guarantee(IO.blocking(fix.close()))
    }
  }

  // ── 缺陷 A（2026-09-18）：坏凭据不得把状态面打成 500（判据 G4②③）──────────
  //
  // 修前形态两半：① 读抛（权限/占用）⇒ 本端点 500；② 解码坏 ⇒ 静默 `None`
  // （连日志都没有）⇒ 用户看到 `loggedIn=false` 但**没有任何原因**。本钉同时覆盖：
  // 200 + `loggedIn=false` + 加法读数 `credentialIssue`（三段式、零路径）+ 盘上一次性备份件。
  test("G4②③ 坏凭据 ⇒ /neblink/status 200 + loggedIn=false + credentialIssue 带分类码 + 备份件落地") {
    Dispatcher.parallel[IO].use { dispatcher =>
      for
        ms <- NeblinkService.create(0, dispatcher)
        client = new NeblinkClient(NeblinkServerConfig(url = "http://127.0.0.1:9", networkId = Net, secret = "s"), 0)
        ps = new NeblinkPresenceService(ms, 0)(dispatcher)
        discovery = new NeblinkDiscovery(ms, 0, ps, Some(client))
        _ = ms.setRelayClient(Some(client))
        _ = ms.setPresenceService(ps)
        // enabled=true ⇒ 若凭据面正常，loggedIn 会是 true：本钉的 false 只可能来自坏凭据。
        _ <- ms.updateConfig(_.copy(enabled = true))
        dir = os.Path(tmpDir, os.pwd) / "neblink"
        _ <- IO.blocking {
          os.makeDir.all(dir)
          os.write.over(dir / "device.json", "{ broken")
        }
        _ <- IO(DeviceCredential.resetSelfHealForTest())
        resp <- mkRoutes(ms, discovery).routes(statusRequest).value.map(_.getOrElse(fail("route fell through")))
        body <- resp.as[Json]
        backups <- IO.blocking(os.list(dir).toList.map(_.last).filter(_.contains(".corrupt-")))
      yield
        assertEquals(resp.status, Status.Ok, "坏凭据不得把状态面打成 500（判据 G4②）")
        assertEquals(body.hcursor.downField("loggedIn").as[Boolean], Right(false))
        val issue = body.hcursor.downField("credentialIssue")
        assertEquals(issue.downField("code").as[String], Right("credential-undecodable"))
        val message = issue.downField("error").as[String].toOption.getOrElse("")
        assert(message.contains("诊断码：credential-undecodable"), s"状态面错误串必须三段式: $message")
        // round 1 / 判词 D4：负控不止判据正则 —— 追加「不含 data-root 路径」「不含 device.json」
        // 二重断言（正则的 `[A-Za-z]:\\` 覆盖不到 POSIX 绝对路径，单靠它会漏掉 `/var/folders/…` 形态）。
        val dataRoot = os.Path(tmpDir, os.pwd)
        List(
          "G4②.credentialIssue.error" -> message,
          "G4②.credentialIssue.reason" -> issue.downField("reason").as[String].toOption.getOrElse(""),
          "G4②.credentialIssue.action" -> issue.downField("action").as[String].toOption.getOrElse("")
        ).foreach { case (tag, s) =>
          assertEquals(LogdevTestSupport.violations(s, dataRoot), Nil, s"$tag 三条判据必须全过: $s")
        }
        assertEquals(backups.length, 1, "盘上必须出现一次性备份件（判据 G4③）")
    }
  }

end NeblinkRelayStatusRouteSpec
