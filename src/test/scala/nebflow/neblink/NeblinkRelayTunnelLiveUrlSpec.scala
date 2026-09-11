package nebflow.neblink

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.core.PathUtil

import java.nio.file.Files
import scala.concurrent.duration.*

/**
 * Relay tunnel 生命周期修复（2026-09-11）—— 三个独立缺陷的在线（本地 fixture）钉子：
 *
 *  (a) **serverUrl 去构造期死值**：`neblinkServer` 未配置时 `connect()` 空转不崩
 *      （无升级尝试、无 INFO 噪声）；随后 `updateConfig(Some(url))` + login ⇒
 *      **不重建 tunnel** 即连上（live 解析）。修前 serverUrl 是构造期常量，
 *      「启动后配置」这条路径根本不存在。
 *  (b) **stop() 后可复活**：logout 会 `stop()`，而修前 `running` 无任何复位路径
 *      ⇒ relay 永久缺席到进程重启。`ensure()`/`start()` 必须让隧道再次可达。
 *  (c) **ensure 单飞**：连续两次 `ensure()` 只能拉起一条连接链（不得双隧道）——
 *      用确定性的 loop 派生计数断言，不靠升级尝试次数间接推断。
 *
 * fixture = `RelayAuthFixtureServer`（127.0.0.1 临时端口、真实 RFC 6455 upgrade）；
 * 不触碰生产 VPS、不起任何网关实例。
 */
class NeblinkRelayTunnelLiveUrlSpec extends CatsEffectSuite:

  private val Net = "qa-net"
  private val Device = "qa-device"

  private var tmpDir: java.nio.file.Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("nb-tunnel-live-url-spec")
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(os.Path(tmpDir, os.pwd))
    super.afterEach(context)

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

  private def withFixture[A](body: RelayAuthFixtureServer => IO[A]): IO[A] =
    IO.blocking(new RelayAuthFixtureServer()).flatMap(f => body(f).guarantee(IO.blocking(f.close())))

  /** 与 GatewayMain 等价的最小栈：tunnel + live tokenGetter + starter 登记。
    * `seedUrl = true` 时按「启动即有配置」时序把 server 址写进 config ref。 */
  private def withStack[A](fix: RelayAuthFixtureServer, seedUrl: Boolean)(
    body: (NeblinkService, NeblinkClient, NeblinkRelayTunnel, NeblinkDiscovery) => IO[A]
  ): IO[A] =
    Dispatcher.parallel[IO].use { dispatcher =>
      for
        ms <- NeblinkService.create(0, dispatcher)
        client = mkClient(fix)
        _ = ms.setRelayClient(Some(client))
        ps = new NeblinkPresenceService(ms, 0)(dispatcher)
        discovery = new NeblinkDiscovery(ms, 0, ps, Some(client))
        tunnel = new NeblinkRelayTunnel(
          ms,
          () => discovery.currentClient.map(_.flatMap(_.currentSessionToken))
        )(dispatcher)
        _ = ms.setRelayTunnel(tunnel)
        // 装配 owner（GatewayMain）的等价登记：enrollment 只发 ensure 信号。
        // （setRelayTunnelStarter 返回 IO ⇒ 必须走生成器）
        _ <- ms.setRelayTunnelStarter(tunnel.ensure())
        _ <-
          if seedUrl then
            ms.updateConfig(_.copy(
              enabled = true,
              neblinkServer = Some(NeblinkServerConfig(url = fix.url, networkId = Net, secret = "qa-secret"))
            ))
          else IO.unit
        out <- body(ms, client, tunnel, discovery).guarantee(tunnel.stop())
      yield out
    }

  test("(a) no server URL: connect() idles without upgrades, then picks the URL up live (no rebuild)") {
    withFixture { fix =>
      withStack(fix, seedUrl = false) { (ms, client, tunnel, _) =>
        for
          _ <- tunnel.connect()
          _ <- IO.sleep(700.millis)
          attemptsWhileUnconfigured <- IO(fix.relayAttempts.size)
          running <- IO(tunnel.isRunning)
          // 运行期配置 + 登录（**不重建 tunnel**）
          _ <- ms.updateConfig(_.copy(
            enabled = true,
            neblinkServer = Some(NeblinkServerConfig(url = fix.url, networkId = Net, secret = "qa-secret"))
          ))
          _ <- client.login(Device, "qa-host", "macos", Nil)
          connected <- waitUntil(20.seconds)(IO(fix.attemptCount(101) >= 1))
        yield
          assertEquals(attemptsWhileUnconfigured, 0, "未配置 server URL 时不得发起 relay 升级（空转不崩）")
          assertEquals(running, true, "空转期间 loop 必须仍在跑（可被后续配置点亮）")
          assert(connected, s"updateConfig + login 后必须在不重建 tunnel 的情况下连上：${fix.relayAttempts}")
      }
    }
  }

  test("(b) stop() then ensure() revives the tunnel (logout must not be terminal)") {
    withFixture { fix =>
      withStack(fix, seedUrl = true) { (_, client, tunnel, _) =>
        for
          _ <- client.login(Device, "qa-host", "macos", Nil)
          _ <- tunnel.connect()
          first <- waitUntil(10.seconds)(IO(fix.attemptCount(101) >= 1))
          _ <- IO(assert(first, s"基线连接失败：${fix.relayAttempts}"))
          baseline101 = fix.attemptCount(101)
          // logout 的等价物
          _ <- tunnel.stop()
          stopped <- IO(tunnel.isRunning)
          _ <- tunnel.ensure()
          revived <- IO(tunnel.isRunning)
          reconnected <- waitUntil(15.seconds)(IO(fix.attemptCount(101) > baseline101))
        yield
          assertEquals(stopped, false, "stop() 后必须停")
          assertEquals(revived, true, "ensure() 必须把 running 复位（修前只有进程重启能救）")
          assert(reconnected, s"ensure() 后必须重新建链：${fix.relayAttempts}")
      }
    }
  }

  test("(c) two consecutive ensure() calls spawn exactly ONE loop (no double tunnel)") {
    withFixture { fix =>
      withStack(fix, seedUrl = true) { (_, client, tunnel, _) =>
        for
          _ <- client.login(Device, "qa-host", "macos", Nil)
          _ <- tunnel.connect()
          first <- waitUntil(10.seconds)(IO(fix.attemptCount(101) >= 1))
          _ <- IO(assert(first, s"基线连接失败：${fix.relayAttempts}"))
          _ <- tunnel.stop()
          spawnsBefore <- IO(tunnel.loopSpawnCount)
          _ <- tunnel.ensure()
          _ <- tunnel.ensure()
          _ <- tunnel.ensure()
          spawnsAfter <- IO(tunnel.loopSpawnCount)
          alive <- IO(tunnel.isRunning)
        yield
          assertEquals(spawnsAfter, spawnsBefore + 1, "连续 ensure 只允许派生一条连接链（CAS 单飞）")
          assertEquals(alive, true)
      }
    }
  }

end NeblinkRelayTunnelLiveUrlSpec
