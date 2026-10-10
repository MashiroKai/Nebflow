package nebflow.neblink

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.shared.PathUtil

import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue

import scala.concurrent.duration.*

/**
 * Phase 3a front-check spec (design §3.1): the P0 proto gate and the P1
 * contract-frame dispatch, exercised over the REAL relay socket fixture
 * (`RelayAuthFixtureServer`) — the same e2e shape as the FragReasm spec, so
 * the checks run through the production listener path (`onText` →
 * reassembler → `handleFrame`), not direct method calls.
 *
 * Pins:
 *  - P0: a frame whose proto exceeds the supported version is DROPPED for
 *    every frame family (a `ping` with proto 99 must not pong) — the exact
 *    pass-through regression this branch once had;
 *  - P0 inertness: proto 1 frames keep their existing behavior (ping pongs;
 *    relay_request with proto executes — forward compatibility for the
 *    deployment batch that rolls proto onto the legacy frames);
 *  - P1: a contract frame failing codec validation drops with the
 *    structured rejection diagnostic (no partial processing, no W10
 *    unknown-type misattribution).
 */
class PortalProtoFrontCheckSpec extends CatsEffectSuite:

  private val Net = "qa-net"
  private val Device = "qa-device"
  private val Rid = "0a1b2c3d-4e5f-4678-9abc-def012345678"

  private var tmpDir: java.nio.file.Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("nb-portal-front-spec")
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(os.Path(tmpDir, os.pwd))
    super.afterEach(context)

  // ---- harness（与 NeblinkRelayTunnelFragReasmSpec 同款；零改写其件）----

  private final class RelayLogAppender
      extends ch.qos.logback.core.AppenderBase[ch.qos.logback.classic.spi.ILoggingEvent]:
    val lines = new ConcurrentLinkedQueue[String]()

    override def append(event: ch.qos.logback.classic.spi.ILoggingEvent): Unit =
      lines.add(event.getFormattedMessage)

  private def captureRelayLog[A](body: ConcurrentLinkedQueue[String] => IO[A]): IO[A] =
    IO {
      org.slf4j.LoggerFactory.getLogger("nebflow.neblink.relay") match
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

  private def relayLines(raw: ConcurrentLinkedQueue[String]): List[String] =
    val out = scala.collection.mutable.ListBuffer.empty[String]
    raw.forEach(l => out += l)
    out.toList

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

  private def mkClient(fix: RelayAuthFixtureServer): NeblinkClient =
    new NeblinkClient(
      NeblinkServerConfig(url = fix.url, networkId = Net, secret = "qa-secret"),
      0,
      identity = Some(IO.pure(DeviceIdentity(Device, "qa-host", "macos")))
    )

  private def withTunnel[A](fix: RelayAuthFixtureServer)(body: RelayAuthFixtureServer => IO[A]): IO[A] =
    Dispatcher.parallel[IO].use { dispatcher =>
      for
        ms <- NeblinkService.create(0, dispatcher)
        client = mkClient(fix)
        _ = ms.setRelayClient(Some(client))
        _ <- client.login(Device, "qa-host", "macos", Nil)
        _ <- ms.updateConfig(
          _.copy(
            enabled = true,
            neblinkServer = Some(NeblinkServerConfig(url = fix.url, networkId = Net, secret = "qa-secret"))
          )
        )
        tunnel = new NeblinkRelayTunnel(ms, () => IO(client.currentSessionToken))(dispatcher)
        _ = ms.setRelayTunnel(tunnel)
        fiber <- tunnel.connect().start
        out <- body(fix).guarantee(fiber.cancel *> tunnel.stop())
      yield out
    }

  private def withFixture[A](body: RelayAuthFixtureServer => IO[A]): IO[A] =
    IO.blocking(new RelayAuthFixtureServer()).flatMap(f => body(f).guarantee(IO.blocking(f.close())))

  private def up(fix: RelayAuthFixtureServer): IO[Boolean] =
    waitUntil(5.seconds)(IO(fix.attemptCount(101) >= 1))

  // ── P0：proto 超版本 ⇒ 所有帧族拒收（丢帧，绝不穿透到分派）──────────────

  test("P0: ping with proto 99 is dropped — no pong, structured warn, family has NO exception"):
    withFixture { fix =>
      fix.relayMode = RelayAuthFixtureServer.RelayMode.AcceptIfLive
      fix.recordClientFrames = true
      captureRelayLog { raw =>
        withTunnel(fix) { _ =>
          def pongs: Int = fix.clientFrames.count(_ == """{"type":"pong"}""")
          for
            isUp <- up(fix)
            _ <- IO(assert(isUp, s"baseline: the relay tunnel must be up (${fix.relayAttempts})"))
            baseline <- IO(pongs)
            sent <- IO(fix.sendTextToRelayBig("""{"type":"ping","proto":99}"""))
            _ <- IO(assert(sent >= 1, "the fixture must have an open relay socket"))
            warned <- waitUntil(5.seconds)(IO(relayLines(raw).exists(_.contains("branch=P0"))))
            _ <- IO(
              assert(warned, s"the P0 diagnostic must be logged; log=${relayLines(raw).mkString(" | ")}")
            )
            _ <- IO.sleep(400.millis)
            _ <- IO(
              assertEquals(
                pongs - baseline,
                0,
                s"a proto-99 ping must NOT be dispatched (no pong); frames=${fix.clientFrames}"
              )
            )
            _ <- IO(
              assert(
                relayLines(raw).exists(l => l.contains("branch=P0") && l.contains("proto_unsupported")),
                s"the drop reason must be machine-readable; log=${relayLines(raw).mkString(" | ")}"
              )
            )
          yield ()
          end for
        }
      }
    }

  test("P0 inert for supported proto: plain ping and proto:1 ping both still pong"):
    withFixture { fix =>
      fix.relayMode = RelayAuthFixtureServer.RelayMode.AcceptIfLive
      fix.recordClientFrames = true
      withTunnel(fix) { _ =>
        def pongs: Int = fix.clientFrames.count(_ == """{"type":"pong"}""")
        for
          isUp <- up(fix)
          _ <- IO(assert(isUp))
          _ <- IO(fix.sendTextToRelayBig("""{"type":"ping"}"""))
          plain <- waitUntil(5.seconds)(IO(pongs >= 1))
          _ <- IO(fix.sendTextToRelayBig("""{"type":"ping","proto":1}"""))
          tagged <- waitUntil(5.seconds)(IO(pongs >= 2))
          _ <- IO(assert(plain && tagged, s"both ping shapes must pong; frames=${fix.clientFrames}"))
        yield ()
        end for
      }
    }

  test("forward compat for the deployment batch: relay_request carrying proto:1 executes normally"):
    withFixture { fix =>
      fix.relayMode = RelayAuthFixtureServer.RelayMode.AcceptIfLive
      fix.recordClientFrames = true
      withTunnel(fix) { _ =>
        def responses: List[String] =
          fix.clientFrames.filter(_.contains("\"relay_response\""))
        for
          isUp <- up(fix)
          _ <- IO(assert(isUp))
          _ <- IO(
            fix.sendTextToRelayBig(
              s"""{"type":"relay_request","proto":1,"requestId":"$Rid","action":"NoSuchTool","params":{}}"""
            )
          )
          ok <- waitUntil(5.seconds)(IO(responses.exists(_.contains(Rid))))
          _ <- IO(
            assert(
              ok && responses.exists(r => r.contains(Rid) && r.contains("Unknown tool: NoSuchTool")),
              s"a proto-tagged relay_request must execute and answer; frames=${fix.clientFrames}"
            )
          )
        yield ()
        end for
      }
    }

  // ── Terminal probe leg（判定表 row 1/2/4：终态不停拨、101 清终态）────────

  test("terminal binding does NOT stop dialing: the low-frequency probe reaches a real upgrade and row 4 clears the state"):
    withFixture { fix =>
      fix.relayMode = RelayAuthFixtureServer.RelayMode.AcceptIfLive
      Dispatcher.parallel[IO].use { dispatcher =>
        for
          ms <- NeblinkService.create(0, dispatcher)
          client = mkClient(fix)
          _ = ms.setRelayClient(Some(client))
          _ <- client.login(Device, "qa-host", "macos", Nil)
          _ <- ms.updateConfig(
            _.copy(
              enabled = true,
              neblinkServer = Some(NeblinkServerConfig(url = fix.url, networkId = Net, secret = "qa-secret"))
            )
          )
          // Pre-write the PERSISTED terminal state: this is a restarted host
          // whose binding was revoked before the restart (the kickParked
          // lesson — the state must survive, but it must never stop dialing).
          _ <- IO {
            os.write.over(
              PathUtil.dataRoot / "neblink" / "binding-state.json",
              """{"status":"unbound","updatedAt":1}""",
              createFolders = true
            )
          }
          faces = new PortalHostFaces(gatewayPort = 1)
          tunnel = new NeblinkRelayTunnel(
            ms,
            () => IO(client.currentSessionToken),
            portalHost = Some(faces)
          )(dispatcher)
          // Test seam: shrink the 10-minute probe cadence to 300ms.
          _ = tunnel.terminalProbeNap = 300.millis
          _ = ms.setRelayTunnel(tunnel)
          fiber <- tunnel.connect().start
          dialed <- waitUntil(10.seconds)(IO(fix.attemptCount(101) >= 1))
          _ = assert(
            dialed,
            "a terminal-binding host must still DIAL the relay (probe = nap + real upgrade attempt)"
          )
          cleared <- waitUntil(10.seconds)(
            tunnel.portalDeps.fold(IO.pure(false))(_.bindingState.isUnbound.map(!_))
          )
          _ = assert(
            cleared,
            "judgment-table row 4: the successful upgrade must clear the terminal binding state"
          )
          _ <- fiber.cancel *> tunnel.stop()
        yield ()
      }
    }

  // ── P1：契约帧校验拒斥 ⇒ 丢帧 + 结构化诊断（不落 W10）──────────────────

  test("P1: contract frame failing codec validation drops with the rejection diagnostic"):
    withFixture { fix =>
      fix.relayMode = RelayAuthFixtureServer.RelayMode.AcceptIfLive
      captureRelayLog { raw =>
        withTunnel(fix) { _ =>
          for
            isUp <- up(fix)
            _ <- IO(assert(isUp))
            // http_request without requestId: missing_required_field.
            _ <- IO(
              fix.sendTextToRelayBig(
                """{"type":"http_request","proto":1,"method":"GET","path":"/api/x","headers":{}}"""
              )
            )
            warned <- waitUntil(5.seconds)(
              IO(
                relayLines(raw).exists(l =>
                  l.contains("branch=P1") && l.contains("missing_required_field") && l.contains("requestId")
                )
              )
            )
            _ <- IO(
              assert(
                warned,
                s"the P1 rejection must be logged with code + field; log=${relayLines(raw).mkString(" | ")}"
              )
            )
            _ <- IO(
              assert(
                !relayLines(raw).exists(l => l.contains("branch=W10") && l.contains("http_request")),
                "a rejected CONTRACT frame must not be misattributed as unknown_type (W10)"
              )
            )
          yield ()
          end for
        }
      }
    }

  test("P1: MigrationChunk decodes then drops as not-implemented (never unknown_type)"):
    withFixture { fix =>
      fix.relayMode = RelayAuthFixtureServer.RelayMode.AcceptIfLive
      captureRelayLog { raw =>
        withTunnel(fix) { _ =>
          for
            isUp <- up(fix)
            _ <- IO(assert(isUp))
            sent <- IO(
              fix.sendTextToRelayBig(
                """{"type":"MigrationChunk","proto":1,"migrationId":"2a3b4c5d-6e7f-4890-abcd-ef0123456789",""" +
                  """"manifestPath":".nebflow/migration/manifest.json","chunkIndex":0,""" +
                  """"sha256":"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855","data":"AAECAwQFBgcICQ=="}"""
              )
            )
            _ <- IO(assert(sent >= 1, "the fixture must have an open relay socket to push into"))
            warned <- waitUntil(5.seconds)(
              IO(relayLines(raw).exists(l => l.contains("MigrationChunk") && l.contains("not implemented")))
            )
            _ <- IO(
              assert(
                warned,
                s"MigrationChunk must drop as not-implemented; log=${relayLines(raw).mkString(" | ")}"
              )
            )
            _ <- IO(
              assert(
                !relayLines(raw).exists(l => l.contains("branch=W10") && l.contains("MigrationChunk")),
                "MigrationChunk is a KNOWN contract frame — W10 would misreport contract drift"
              )
            )
          yield ()
          end for
        }
      }
    }

end PortalProtoFrontCheckSpec
