package nebflow.neblink

import cats.effect.IO
import cats.effect.std.Dispatcher
import munit.CatsEffectSuite
import nebflow.shared.PathUtil
import org.slf4j.LoggerFactory

import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue

import scala.concurrent.duration.*

/**
 * relay-honest 批（2026-10-20，link-server 诊断「登出→重登录后 relay 隧道无重启
 * 触发器」）回归钉。
 *
 * 根因链（诊断 2026-10-20）：
 *  ① `performLocalLogout` 第 2 步 `stop()` 静默停隧道 —— 引擎日志零 disconnect 行，
 *     状态面残留「连着」假象；
 *  ② 重登录走**凭据腿**（`POST /api/device/session` 成功）只恢复 device session，
 *     隧道 register 只由 enrollment / silent-relogin 腿经
 *     `NeblinkEnrollment.persistCredential → ensureRelayTunnel` 发 —— 凭据腿没有任何
 *     重启触发器 ⇒ 门户永久判 relay 不可达。
 *
 * 修法与判据：
 *  - A（收口）：`NeblinkClient.onSessionMinted` 钩 —— 每次**成功的设备登录/会话铸造**
 *    之后无条件发一次「确保隧道在跑」，生产装配点接 `NeblinkService.ensureRelayTunnel`
 *    （三条腿统一收口，幂等 = 信号量串行 + 隧道 CAS 单飞）。判据 = 本 spec 用例 ①：
 *    stop() 之后**只做凭据腿登录**（零 enroll/persist）隧道必须复活并真升级（101）。
 *  - B（诚实化）：`stop()` 必须留 disconnect 行（用例 ① 断言日志）；
 *    `connectOnce` 的 **stop-during-upgrade 诚实闸**（用例 ②：stop() 落在 101 在飞
 *    窗口里 ⇒ 零装配，状态面不得残留「连着」）。既有可见性行不动：驱逐
 *    （`disconnect` 帧）的 WARN 与僵尸 abort 的 INFO 由 `NeblinkRelayTunnelKickParkSpec`
 *    / `NeblinkRelayTunnelZombieHealSpec` 钉住；inbox full 的逐环 WARN 在
 *    `PortalTunnelBridge`（既有）。
 *
 * Harness 沿用 `RelayAuthFixtureServer`（127.0.0.1 ephemeral 端口，真 RFC 6455
 * 升级）——「重注册」判据 = fixture 上**第二次真 101 升级**，不是 mock 计数。
 */
class NeblinkReloginTunnelReviveSpec extends CatsEffectSuite:

  private val Net = "qa-net"
  private val Device = "qa-device"

  private var tmpDir: java.nio.file.Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("nb-relogin-revive-spec")
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(os.Path(tmpDir, os.pwd))
    super.afterEach(context)

  // ── 日志捕获（同 NeblinkRelayTunnelKickParkSpec 的 appender 形态）─────────

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

  private def allLines(lines: ConcurrentLinkedQueue[String]): String =
    lines.toArray.toList.mkString(" | ")

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

  // ── 栈：GatewayMain 装配的最小等价物（含 A 修的凭据腿收口钩）────────────

  private def withFixture[A](body: RelayAuthFixtureServer => IO[A]): IO[A] =
    IO.blocking(new RelayAuthFixtureServer()).flatMap(f => body(f).guarantee(IO.blocking(f.close())))

  private def withStack[A](fix: RelayAuthFixtureServer)(
    body: (NeblinkService, NeblinkClient, NeblinkRelayTunnel) => IO[A]
  ): IO[A] =
    fix.relayMode = RelayAuthFixtureServer.RelayMode.AcceptIfLive
    fix.pongReplies = true // 健康隧道：pong 正常回流，isAlive 判据不受僵尸窗干扰
    Dispatcher.parallel[IO].use { dispatcher =>
      for
        ms <- NeblinkService.create(0, dispatcher)
        client = new NeblinkClient(
          NeblinkServerConfig(url = fix.url, networkId = Net, secret = "qa-secret"),
          0,
          identity = Some(IO.pure(DeviceIdentity(Device, "qa-host", "macos"))),
          // 生产装配点同形（GatewayMain boot 客户端 / persistCredential hot-swap
          // 都传 `Some(ms.ensureRelayTunnel)`）：凭据腿 → 单一 ensure 收口。
          onSessionMinted = Some(ms.ensureRelayTunnel)
        )
        _ = ms.setRelayClient(Some(client))
        tunnel = new NeblinkRelayTunnel(ms, () => IO(client.currentSessionToken))(dispatcher)
        _ = ms.setRelayTunnel(tunnel)
        // 装配 owner（GatewayMain）的等价登记：ensure 只发「确保在跑」信号。
        // （setRelayTunnelStarter 返回 IO ⇒ 必须走生成器）
        _ <- ms.setRelayTunnelStarter(tunnel.ensure())
        _ <- ms.updateConfig(
          _.copy(
            enabled = true,
            neblinkServer = Some(NeblinkServerConfig(url = fix.url, networkId = Net, secret = "qa-secret"))
          )
        )
        out <- body(ms, client, tunnel).guarantee(tunnel.stop())
      yield out
    }

  // ── 用例 ①：登出 → 凭据腿重登录 → 隧道自动重注册（A 修全链）──────────────

  test("logout -> credential-leg re-login revives and re-registers the relay tunnel (single ensure funnel)") {
    withFixture { fix =>
      captureRelayLog { lines =>
        withStack(fix) { (_, client, tunnel) =>
          for
            _ <- client.login(Device, "qa-host", "macos", Nil)
            _ <- tunnel.connect()
            first <- waitUntil(10.seconds)(IO(fix.attemptCount(101) >= 1))
            _ <- IO(assert(first, s"baseline: the first upgrade must land (${fix.relayAttempts})"))
            spawnsBaseline <- IO(tunnel.loopSpawnCount)
            aliveBefore <- IO(tunnel.isAlive)
            // ── 登出（performLocalLogout 第 2 步的等价物）──
            _ <- tunnel.stop()
            runningAfterLogout <- IO(tunnel.isRunning)
            aliveAfterLogout <- IO(tunnel.isAlive)
            logAfterLogout = allLines(lines)
            // ── 凭据腿重登录：只做一次会话交换（零 enroll / 零 persist），
            //    A 修的钩子必须把「确保隧道在跑」发到同一处 ensure。──
            _ <- client.login(Device, "qa-host", "macos", Nil)
            runningAfterRelogin <- waitUntil(10.seconds)(IO(tunnel.isRunning))
            spawnsAfterRelogin <- IO(tunnel.loopSpawnCount)
            reconnected <- waitUntil(15.seconds)(IO(fix.attemptCount(101) >= 2))
            aliveAfterRelogin <- IO(tunnel.isAlive)
            // 幂等：再来一次凭据腿登录不得派生第二条链（CAS 单飞）
            _ <- client.login(Device, "qa-host", "macos", Nil)
            _ <- IO.sleep(600.millis)
            spawnsAfterSecondLogin <- IO(tunnel.loopSpawnCount)
          yield
            assertEquals(aliveBefore, true, "baseline: tunnel alive before logout")
            assertEquals(runningAfterLogout, false, "logout must stop the tunnel (existing semantics)")
            assertEquals(aliveAfterLogout, false, "after logout the status face must NOT claim connected")
            assert(
              logAfterLogout.contains("Relay tunnel disconnected: stop()"),
              s"stop() must leave a visible disconnect line; log=$logAfterLogout"
            )
            assertEquals(
              runningAfterRelogin,
              true,
              "a credential-leg re-login (session re-mint) must revive the stopped tunnel " +
                "via the single ensure funnel — no enroll/persist needed"
            )
            assertEquals(
              spawnsAfterRelogin,
              spawnsBaseline + 1,
              "exactly one new connection chain after the revive (single-flight CAS)"
            )
            assert(
              reconnected,
              s"the revived tunnel must re-register on the relay (a fresh 101 upgrade); attempts=${fix.relayAttempts}"
            )
            assertEquals(aliveAfterRelogin, true, "the re-registered tunnel must report alive again")
            assertEquals(
              spawnsAfterSecondLogin,
              spawnsAfterRelogin,
              "a second session mint must stay idempotent (no second chain)"
            )
          end for
        }
      }
    }
  }

  // ── 用例 ②：stop() 落在 101 升级在飞窗口 ⇒ 零装配（不留「连着」假象）─────

  test("stop() landing during an in-flight upgrade installs nothing (no stale connected illusion)") {
    withFixture { fix =>
      // 1500ms 的升级悬挂：stop() 在 ~400ms 落地，101 在 ~1500ms 才回来 ——
      // 确定性地把「stop() 在升级在飞时到达」钉进窗口（夹具旋钮 upgradeDelayMs）。
      fix.upgradeDelayMs = 1500L
      captureRelayLog { lines =>
        withStack(fix) { (_, client, tunnel) =>
          for
            _ <- client.login(Device, "qa-host", "macos", Nil)
            _ <- tunnel.connect()
            _ <- IO.sleep(400.millis) // 升级在飞（fixture 悬着 101）
            _ <- tunnel.stop()
            runningAfterStop <- IO(tunnel.isRunning)
            upgraded <- waitUntil(10.seconds)(IO(fix.attemptCount(101) >= 1))
            _ <- IO(assert(upgraded, "the fixture must eventually complete the held 101"))
            // 修前形态：101 回来后装配块照跑 ⇒ alive=true + 新心跳 ⇒ 状态面残留「连着」。
            aliveAfterUpgrade <- IO(tunnel.isAlive)
            _ <- IO.sleep(1500.millis) // 越过任何迟到装配 / 存活窗边缘
            aliveLater <- IO(tunnel.isAlive)
            spawns <- IO(tunnel.loopSpawnCount)
            logAll = allLines(lines)
          yield
            assertEquals(runningAfterStop, false, "stop() semantics unchanged")
            assertEquals(
              aliveAfterUpgrade,
              false,
              "the post-stop install must be skipped: alive must stay false " +
                "(pre-fix the fresh socket was installed with a heartbeat stop() never saw)"
            )
            assertEquals(aliveLater, false, "the false reading must persist (no late install)")
            assertEquals(spawns, 1, "the loop chain exits via running==false; no respawn")
            assert(
              logAll.contains("nothing installed"),
              s"the honest guard must log the skipped install; log=$logAll"
            )
          end for
        }
      }
    }
  }

end NeblinkReloginTunnelReviveSpec
