package nebflow.neblink

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.core.PathUtil
import org.slf4j.LoggerFactory

import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*

/**
 * Relay-tunnel auth self-heal + status-code observability
 * (2026-09-10 隧道鉴权自愈批 — report `20260910_134229_device-channel-attribution.md`
 * §2 R1, §3 U1, §4 F2/F3, §6).
 *
 * Failure being nailed (B state of the incident): the server's
 * one-live-session-per-device policy kicks the relay tunnel's session the
 * moment a fresh login happens on this device (UI re-login / enrollment
 * hot-swap / another process reusing the credential). `NeblinkRelayTunnel`
 * then reconnects forever with the DEAD token:
 *   - it never re-logins, never refreshes the token, never reports upstream;
 *   - the failure path logged `e.getMessage`, which is `null` for
 *     `WebSocketHandshakeException` (the class does not override getMessage),
 *     and `ExecutionException`'s message only carries the class NAME — hence
 *     702 `...WebSocketHandshakeException` + 22 `Relay tunnel error: null`
 *     lines in the 2026-09-10 host log with ZERO evidence of the HTTP status
 *     (U1, the report's only hard gap).
 *
 * Harness shape: `RelayAuthFixtureServer` (raw-socket 127.0.0.1 fixture with a
 * real RFC 6455 upgrade + one-live-session semantics) + a real
 * `NeblinkService`/`NeblinkClient`/`NeblinkRelayTunnel` stack wired like
 * GatewayMain. No production endpoint, no real credential, no process restart.
 *
 * Assertions are wire-level (fixture attempt counters) rather than relying on
 * `tunnel.isAlive`, which only flips false once the socket close propagates —
 * a stale `true` would silently green-light the very failure this spec guards.
 *
 *   R1/R4 — auth rejection (403/401) is REPORTED with its status code and
 *           triggers exactly one re-login that restores the tunnel;
 *   R2    — the narrow gate: 5xx must never re-login (report only);
 *   R3    — single-flight: 8 concurrent API 403s + a relay 403 = ONE re-login
 *           (a second, independent gate would kick our own session in a loop).
 */
class NeblinkRelayTunnelAuthSpec extends CatsEffectSuite:

  private val Net = "qa-net"
  private val Device = "qa-device"

  private var tmpDir: java.nio.file.Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("nb-relay-auth-spec")
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(os.Path(tmpDir, os.pwd))
    super.afterEach(context)

  // ── log capture (the U1 evidence channel) ────────────────

  private final class RelayLogAppender
      extends ch.qos.logback.core.AppenderBase[ch.qos.logback.classic.spi.ILoggingEvent]:
    val lines = new ConcurrentLinkedQueue[String]()
    override def append(event: ch.qos.logback.classic.spi.ILoggingEvent): Unit =
      lines.add(event.getFormattedMessage)

  /** Capture everything the relay logger emits while `body` runs. */
  private def captureRelayLog[A](body: ConcurrentLinkedQueue[String] => IO[A]): IO[A] =
    IO {
      LoggerFactory.getLogger("nebflow.neblink.relay") match
        case lb: ch.qos.logback.classic.Logger =>
          val appender = new RelayLogAppender
          appender.setContext(lb.getLoggerContext)
          appender.start()
          lb.addAppender(appender)
          (lb, appender)
        case other => fail(s"expected a logback logger for the relay channel, got $other")
    }.flatMap { (lb, appender) =>
      body(appender.lines).guarantee(IO(lb.detachAppender(appender)))
    }

  private def logText(lines: ConcurrentLinkedQueue[String]): String =
    lines.toArray.toList.mkString(" | ")

  // ── stack wiring (mirrors GatewayMain:661-745) ───────────

  private def mkClient(fix: RelayAuthFixtureServer): NeblinkClient =
    new NeblinkClient(
      NeblinkServerConfig(url = fix.url, networkId = Net, secret = "qa-secret"),
      0,
      identity = Some(IO.pure(DeviceIdentity(Device, "qa-host", "macos")))
    )

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

  /** Fixture + service + client + tunnel, wired like GatewayMain: the client is
    * registered as the live relay client (the pointer every relay consumer
    * reads) and the tunnel's token getter reads it live on each reconnect. */
  private def withStack[A](
    fix: RelayAuthFixtureServer,
    mode: RelayAuthFixtureServer.RelayMode = RelayAuthFixtureServer.RelayMode.Auth403
  )(body: (NeblinkService, NeblinkClient, NeblinkRelayTunnel) => IO[A]): IO[A] =
    fix.relayMode = mode
    Dispatcher.parallel[IO].use { dispatcher =>
      for
        ms <- NeblinkService.create(0, dispatcher)
        client = mkClient(fix)
        _ = ms.setRelayClient(Some(client))
        _ <- client.login(Device, "qa-host", "macos", Nil)
        tunnel = new NeblinkRelayTunnel(ms, fix.url, () => IO(client.currentSessionToken))(dispatcher)
        _ = ms.setRelayTunnel(tunnel)
        fiber <- tunnel.connect().start
        out <- body(ms, client, tunnel).guarantee(fiber.cancel *> tunnel.stop())
      yield out
    }

  private def withFixture[A](body: RelayAuthFixtureServer => IO[A]): IO[A] =
    IO.blocking(new RelayAuthFixtureServer()).flatMap(f => body(f).guarantee(IO.blocking(f.close())))

  // ── R1: the incident scenario ────────────────────────────

  test("R1: kicked session -> relay upgrade 403 is reported with its status code AND self-healed") {
    withFixture { fix =>
      captureRelayLog { lines =>
        withStack(fix) { (_, _, tunnel) =>
          for
            baseline <- waitUntil(5.seconds)(IO(fix.attemptCount(101) >= 1))
            _ <- IO(assert(baseline, s"baseline: the live token must be accepted (${fix.relayAttempts})"))
            baseline101 = fix.attemptCount(101)
            // The incident trigger: a fresh login on this device (UI re-login /
            // enrollment hot-swap) kicks our session server-side. The open WS
            // survives, so the tunnel only notices on the next reconnect.
            _ <- IO { fix.kickSessionOf(Device, Net); () }
            _ <- IO { fix.closeAllRelaySockets(); () }
            rejected <- waitUntil(10.seconds)(IO(fix.attemptCount(403) >= 1))
            _ <- IO(
              assert(
                rejected,
                s"the kicked token must be rejected by the relay upgrade; attempts=${fix.relayAttempts}"
              )
            )
            recovered <- waitUntil(15.seconds)(IO(fix.attemptCount(101) > baseline101))
            _ <- IO(
              assert(
                recovered,
                s"tunnel must re-login and reconnect after the 403; attempts=${fix.relayAttempts}, log=${logText(lines)}"
              )
            )
            // U1 (the hard gap of the incident): the status code must be visible.
            _ <- IO(
              assert(
                logText(lines).contains("HTTP 403"),
                s"the rejected upgrade must log its HTTP status code; log=${logText(lines)}"
              )
            )
            _ <- IO(
              assertEquals(
                fix.logins.get(),
                3,
                s"startup + impersonator kick + exactly ONE self-heal re-login; calls=${fix.loginCalls.toArray.toList}"
              )
            )
          yield ()
        }
      }
    }
  }

  // ── R2: the narrow gate — 5xx must NEVER re-login ────────

  test("R2: 5xx relay rejection is reported with its status code and must NOT trigger a re-login") {
    withFixture { fix =>
      captureRelayLog { lines =>
        withStack(fix, RelayAuthFixtureServer.RelayMode.Server500) { (_, _, tunnel) =>
          for
            attempted <- waitUntil(12.seconds)(IO(fix.attemptCount(500) >= 2))
            _ <- IO(assert(attempted, s"fixture must serve repeated 500 upgrades: ${fix.relayAttempts}"))
            _ <- IO(
              assert(
                logText(lines).contains("HTTP 500"),
                s"a 5xx upgrade rejection must log its status code; log=${logText(lines)}"
              )
            )
            _ <- IO(
              assertEquals(
                fix.logins.get(),
                1,
                "5xx is a server problem — a re-login is meaningless and would amplify the storm, so logins must stay at the startup one"
              )
            )
            _ <- IO(assert(!tunnel.isAlive, "a perpetually 500-ing relay must not report itself as alive"))
          yield ()
        }
      }
    }
  }

  // ── R3: shared single-flight gate ────────────────────────

  test("R3: 8 concurrent API 403s + one relay 403 produce exactly ONE re-login (shared gate)") {
    withFixture { fix =>
      withStack(fix, RelayAuthFixtureServer.RelayMode.AcceptIfLive) { (_, client, tunnel) =>
        val probe = () => client.searchUser("ghost-probe")
        for
          baseline <- waitUntil(5.seconds)(IO(fix.attemptCount(101) >= 1))
          _ <- IO(assert(baseline, s"baseline connect failed: ${fix.relayAttempts}"))
          baseline101 = fix.attemptCount(101)
          // Hold the heal gate open long enough for every concurrent caller to
          // join the winner (determinism knob, not product behaviour).
          _ <- IO { fix.loginDelayMs = 600; () }
          _ <- IO { fix.kickSessionOf(Device, Net); () }
          searches <- IO.parSequenceN(8)(List.fill(8)(probe())).start
          _ <- IO.sleep(150.millis)
          // Rush the tunnel into its own 403 while the gate is held.
          _ <- IO { fix.closeAllRelaySockets(); () }
          outs <- searches.joinWithNever
          _ <- IO(assert(outs.forall(_.isRight), s"every API call must recover through the shared heal: $outs"))
          relay403 <- waitUntil(5.seconds)(IO(fix.attemptCount(403) >= 1))
          _ <- IO(assert(relay403, s"the tunnel must have hit its own 403: ${fix.relayAttempts}"))
          recovered <- waitUntil(10.seconds)(IO(fix.attemptCount(101) > baseline101))
          _ <- IO(
            assert(
              recovered,
              s"the tunnel must reconnect off the same single heal; attempts=${fix.relayAttempts}"
            )
          )
          _ <- IO(
            assertEquals(
              fix.logins.get(),
              3,
              "startup + impersonator + exactly ONE heal shared by the 8 API paths and the tunnel — two independent gates would re-login twice and kick each other"
            )
          )
          _ <- IO(assert(tunnel.isAlive, "the tunnel must be up at the end of the shared-heal sequence"))
        yield ()
      }
    }
  }

  // ── R4: 401 is its own status (distinguishable from 403) ─

  test("R4: 401 relay rejection is reported as 401 (distinct from 403) and self-heals") {
    withFixture { fix =>
      captureRelayLog { lines =>
        withStack(fix, RelayAuthFixtureServer.RelayMode.Auth401) { (_, _, tunnel) =>
          for
            baseline <- waitUntil(5.seconds)(IO(fix.attemptCount(101) >= 1))
            _ <- IO(assert(baseline, s"baseline connect failed: ${fix.relayAttempts}"))
            baseline101 = fix.attemptCount(101)
            _ <- IO { fix.kickSessionOf(Device, Net); () }
            _ <- IO { fix.closeAllRelaySockets(); () }
            rejected <- waitUntil(10.seconds)(IO(fix.attemptCount(401) >= 1))
            _ <- IO(assert(rejected, s"the kicked token must be rejected with 401: ${fix.relayAttempts}"))
            recovered <- waitUntil(15.seconds)(IO(fix.attemptCount(101) > baseline101))
            _ <- IO(assert(recovered, s"401 must be treated as an auth rejection and healed; log=${logText(lines)}"))
            _ <- IO(
              assert(
                logText(lines).contains("HTTP 401") && !logText(lines).contains("HTTP 403"),
                s"the status code must be reported verbatim (401 vs 403 are the cross-project discriminator); log=${logText(lines)}"
              )
            )
            _ <- IO(assertEquals(fix.logins.get(), 3, "startup + impersonator + one heal"))
            _ <- IO(assert(tunnel.isAlive, "the tunnel must be up after the heal"))
          yield ()
        }
      }
    }
  }

end NeblinkRelayTunnelAuthSpec
