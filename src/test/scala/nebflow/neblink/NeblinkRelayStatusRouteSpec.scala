package nebflow.neblink

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import io.circe.Json
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
      Dispatcher.parallel[IO].use { dispatcher =>
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
          tunnel = new NeblinkRelayTunnel(ms, fix.url, () => IO(client.currentSessionToken))(dispatcher)
          _ = ms.setRelayTunnel(tunnel)
          ps = new NeblinkPresenceService(ms, 0)(dispatcher)
          discovery = new NeblinkDiscovery(ms, 0, ps, Some(client))
          _ <- ms.updateConfig(_.copy(enabled = true))
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
                resp <- mkRoutes(ms, discovery).routes(statusRequest).value.map(_.getOrElse(fail("route fell through")))
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
      }.guarantee(IO.blocking(fix.close()))
    }
  }

end NeblinkRelayStatusRouteSpec
