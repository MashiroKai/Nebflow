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
 * 踢旧批（2026-09-14，作者 17:07 裁定 C+B·客户端一刀）—— **案 B 客户端腿**回归钉。
 *
 * 缺口（取证报告 `20260914_170224_kickold-forensics__chain-n-07d47ec4.md` §4「缺口 1/2」）：
 *   - 缺口 1：被踢端收到服务端 `disconnect` 帧后**只打一行 debug**，用户零感知；
 *   - 缺口 2：`NeblinkClient.reloginGate` / `HealCooldownMs` 的屏障只盖**单实例内**，
 *     跨实例（同账号双实例）无屏障 ⇒ 互踢乒乓（最坏 2 次登录/分钟）。
 *
 * 本钉的三条判据：
 *   ① 被动提示发射 —— `disconnect` 帧到达 ⇒ `statusJson` 报
 *      `signedOutElsewhere: true`（本地网关↔浏览器侧加法字段，**零 wire 新增**），
 *      并有一行 WARN 可溯源；
 *   ② 停摆 —— `parkedAfterKick == true`，且停摆期内**零连接尝试、零重登录**
 *      （读数 = 真 socket fixture 的升级尝试计数 / 登录计数不增长）；
 *   ③ 唯一解除口 = 显式用户登录（`resumeAfterUserLogin`）——自动路径不得解除。
 *
 * Harness 沿用 `RelayAuthFixtureServer`（127.0.0.1 ephemeral 端口，真 RFC 6455 升级，
 * 真文本帧推送）：帧由 fixture 写进真 socket，被测算走生产 listener 的
 * `onText` 分派路径，不是直调内部方法。
 */
class NeblinkRelayTunnelKickParkSpec extends CatsEffectSuite:

  private val Net = "qa-net"
  private val Device = "qa-device"

  private var tmpDir: java.nio.file.Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("nb-kick-park-spec")
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(os.Path(tmpDir, os.pwd))
    super.afterEach(context)

  private final class RelayLogAppender
      extends ch.qos.logback.core.AppenderBase[ch.qos.logback.classic.spi.ILoggingEvent]:
    val lines = new ConcurrentLinkedQueue[String]()
    override def append(event: ch.qos.logback.classic.spi.ILoggingEvent): Unit =
      lines.add(event.getFormattedMessage)

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

  private def withStack[A](
    fix: RelayAuthFixtureServer
  )(body: (NeblinkService, NeblinkClient, NeblinkRelayTunnel) => IO[A]): IO[A] =
    fix.relayMode = RelayAuthFixtureServer.RelayMode.AcceptIfLive
    Dispatcher.parallel[IO].use { dispatcher =>
      for
        ms <- NeblinkService.create(0, dispatcher)
        client = mkClient(fix)
        _ = ms.setRelayClient(Some(client))
        _ <- client.login(Device, "qa-host", "macos", Nil)
        _ <- ms.updateConfig(_.copy(
          enabled = true,
          neblinkServer = Some(NeblinkServerConfig(url = fix.url, networkId = Net, secret = "qa-secret"))
        ))
        tunnel = new NeblinkRelayTunnel(ms, () => IO(client.currentSessionToken))(dispatcher)
        _ = ms.setRelayTunnel(tunnel)
        fiber <- tunnel.connect().start
        out <- body(ms, client, tunnel).guarantee(fiber.cancel *> tunnel.stop())
      yield out
    }

  private def withFixture[A](body: RelayAuthFixtureServer => IO[A]): IO[A] =
    IO.blocking(new RelayAuthFixtureServer()).flatMap(f => body(f).guarantee(IO.blocking(f.close())))

  // ── ① + ②：帧 ⇒ 提示发射 + 停摆（零自动重连/重注册）──────

  test("kick: a server `disconnect` frame emits the passive notice AND parks reconnects (zero attempts, zero re-login)") {
    withFixture { fix =>
      captureRelayLog { lines =>
        withStack(fix) { (_, _, tunnel) =>
          for
            up <- waitUntil(5.seconds)(IO(fix.attemptCount(101) >= 1))
            _ <- IO(assert(up, s"baseline: the live token must be accepted (${fix.relayAttempts})"))
            // 被踢前的读数基线。**必须在推帧之前取**：推帧 + 关连接之后、等待停摆标志
            // 落下的窗口里，改动前的实现已经会立刻重连 —— 若在窗口之后再取基线，
            // 该断言对改动前同样成立（不具判别力，实测踩过）。
            attemptsBefore <- IO(fix.relayAttempts.size())
            loginsBefore <- IO(fix.logins.get())
            parkedBefore <- IO(tunnel.parkedAfterKick)
            _ <- IO(assert(!parkedBefore, "the tunnel must not start parked"))
            // 真帧 + 真收尾：服务端 `disconnect_device` 的形态是 push_teardown(Disconnect)
            // **随即 break 掉 relay_ws_loop**（relay.rs:415-441）⇒ WS 跟着关闭。
            // 🔴 只推帧、不关 socket 的夹具**不具判别力**：隧道此刻阻塞在 `connectOnce`
            // 的 `closed.get` 上，无论停摆与否都不会发起重连（改动前后同为「零尝试」）。
            // 因此这里必须与生产同形：推帧 → 关连接。
            written <- IO(fix.sendTextToRelay("""{"type":"disconnect"}"""))
            _ <- IO(assert(written >= 1, "the fixture must have an open relay socket to push into"))
            _ <- IO(fix.closeRelaySocketsGracefully())
            parked <- waitUntil(5.seconds)(IO(tunnel.parkedAfterKick))
            _ <- IO(assert(parked, "a server-forced `disconnect` must park the tunnel"))
            // 停摆期观察窗 8s（≫ 一次立即重连的时延）：停摆期必须**零**升级尝试增长。
            _ <- IO.sleep(8.seconds)
            attemptsAfter <- IO(fix.relayAttempts.size())
            loginsAfter <- IO(fix.logins.get())
            _ <- IO(
              assertEquals(
                attemptsAfter,
                attemptsBefore,
                s"parked tunnel must not attempt any reconnect; attempts=${fix.relayAttempts}"
              )
            )
            _ <- IO(
              assertEquals(
                loginsAfter,
                loginsBefore,
                s"parked tunnel must not re-login/re-register; calls=${fix.loginCalls.toArray.toList}"
              )
            )
            // ① 被动提示发射：本地状态面报告「已在别处登录」+ 停摆
            notice = NeblinkRelayTunnel.statusJson(available = false, status = None, kickedAtMs = tunnel.signedOutElsewhereAt)
            _ <- IO(assert(tunnel.signedOutElsewhereAt > 0L, "the kick timestamp must be recorded"))
            _ <- IO(
              assertEquals(notice.hcursor.downField("signedOutElsewhere").as[Boolean].toOption, Some(true))
            )
            _ <- IO(
              assertEquals(notice.hcursor.downField("autoReconnectParked").as[Boolean].toOption, Some(true))
            )
            _ <- IO(
              assert(
                lines.toArray.toList.mkString(" | ").contains("signed in elsewhere"),
                s"the passive notice must be observable in the log; log=${lines.toArray.toList.mkString(" | ")}"
              )
            )
          yield ()
        }
      }
    }
  }

  // ── ③：唯一解除口 = 显式用户登录 ────────────────────────

  test("kick: only an explicit user login lifts the park (the automatic path must not)") {
    withFixture { fix =>
      withStack(fix) { (_, _, tunnel) =>
        for
          up <- waitUntil(5.seconds)(IO(fix.attemptCount(101) >= 1))
          _ <- IO(assert(up, "baseline: tunnel must be up"))
          _ <- IO(fix.sendTextToRelay("""{"type":"disconnect"}"""))
          _ <- IO(fix.closeRelaySocketsGracefully()) // 生产同形：推帧后 WS 关闭（见上一 test 注释）
          parked <- waitUntil(5.seconds)(IO(tunnel.parkedAfterKick))
          _ <- IO(assert(parked, "must be parked after the disconnect frame"))
          // 自动路径的等价信号：enrollment hot-swap / logout-revive 都走 `ensure()`
          // （= `start()`）。它必须**唤醒复查**但**不解除**停摆。
          _ <- IO.sleep(1.second) // 让被唤醒的循环落到停摆分支的睡点上
          attemptsBefore <- IO(fix.relayAttempts.size())
          loginsBefore <- IO(fix.logins.get())
          _ <- tunnel.ensure()
          _ <- IO.sleep(3.seconds)
          stillParked <- IO(tunnel.parkedAfterKick)
          _ <- IO(assert(stillParked, "the automatic ensure()/start() signal must NOT lift the park"))
          _ <- IO(
            assertEquals(fix.relayAttempts.size(), attemptsBefore, "an automatic ensure() must not produce a connect attempt while parked")
          )
          _ <- IO(assertEquals(fix.logins.get(), loginsBefore, "an automatic ensure() must not produce a re-login while parked"))
          // 唯一解除口
          _ <- tunnel.resumeAfterUserLogin()
          lifted <- waitUntil(5.seconds)(IO(!tunnel.parkedAfterKick))
          _ <- IO(assert(lifted, "an explicit user login must lift the park"))
          resumed <- waitUntil(10.seconds)(IO(fix.attemptCount(101) >= 2))
          _ <- IO(assert(resumed, s"the tunnel must reconnect after the park is lifted; attempts=${fix.relayAttempts}"))
          // 幂等：再次调用不产生副作用
          _ <- tunnel.resumeAfterUserLogin()
          _ <- IO(assertEquals(tunnel.parkedAfterKick, false))
        yield ()
      }
    }
  }

  // ── 纯函数面：statusJson 的兼容性（老调用点零字段变化）────

  test("statusJson: the kick fields are additive; the pre-案-B shape is unchanged when not kicked") {
    val before = NeblinkRelayTunnel.statusJson(available = true, status = None)
    assertEquals(before.hcursor.downField("available").as[Boolean].toOption, Some(true))
    assertEquals(before.hcursor.downField("authRejected").as[Boolean].toOption, Some(false))
    // 无 auth 历史时 selfHeal 恒 null（修前同形，本批未动该字段）
    assertEquals(before.hcursor.downField("selfHeal").focus.flatMap(_.asNull).map(_ => "null"), Some("null"))
    assertEquals(before.hcursor.downField("signedOutElsewhere").as[Boolean].toOption, Some(false))
    assertEquals(
      before.hcursor.downField("signedOutElsewhereAt").focus.flatMap(_.asNull).map(_ => "null"),
      Some("null")
    )
  }
end NeblinkRelayTunnelKickParkSpec
