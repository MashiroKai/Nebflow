package nebflow.neblink

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.core.PathUtil

import java.nio.file.Files
import scala.concurrent.duration.*

/**
 * ① 实时性 · 真通路钉子（2026-09-12 波3，方案 §2.1 / §5.1 P1）。
 *
 * 被测的两条腿都是**真 socket + 真 RFC 6455 101 upgrade**（`RelayAuthFixtureServer`
 * 的 raw-socket fixture），不是 mock 的状态机：
 *
 *  ①opt-A1 通道自愈 —— 僵尸连接（对端不回 pong）时：
 *    · `heartbeat` 在 `LivenessTimeoutMs`(30s) 到点判僵尸 → `abort()`；
 *    · `abort()` **不回调 Listener**（JDK 探针 AbortProbe.java 复现）⇒
 *      `closed` 闩永不完成 ⇒ 修前 `connectLoop` 永久停在 `closed.get`；
 *    · 修后 `armZombieLatchWatchdog` 在 `ZombieLatchGrace`(5s) 后补
 *      `complete(())`（幂等）⇒ 连接链前进 ⇒ **第二个 101 必须在 ≤60s 内出现**。
 *
 *  ①-2 `isAlive` 语义诚实化 —— 僵尸窗口内 `alive.get()` 仍为真（闩没返回），
 *    修前 status 端点因此恒定报「可用」（线上假阳性）。现语义 = 连线上册
 *    **且** 最近 pong 在 30s 窗内 ⇒ 僵尸窗口内必须观测到 `isAlive == false`
 *    且此时**尚未**重连（101 计数仍为 1）。
 *
 * 健康路径反证（防「闩提前完成」把抖动掩盖成通过）：fixture `pongReplies=true`
 * 时（= 真服务端 relay.rs 的 Pong 分支）心跳必须一路存活 —— 45s 观察窗内
 * **只有一次 101**、`isAlive` 恒真（无 flap、无误杀）。
 *
 * fixture 全程 127.0.0.1 临时端口；不触碰生产 VPS、不起任何网关实例、不碰 8080。
 */
class NeblinkRelayTunnelZombieHealSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 3.minutes

  private val Net = "qa-net"
  private val Device = "qa-device"

  private var tmpDir: java.nio.file.Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("nb-tunnel-zombie-heal-spec")
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(os.Path(tmpDir, os.pwd))
    super.afterEach(context)

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

  /** 与 GatewayMain 等价的最小栈（同 NeblinkRelayTunnelLiveUrlSpec.withStack）。 */
  private def withStack[A](fix: RelayAuthFixtureServer)(
    body: (NeblinkService, NeblinkClient, NeblinkRelayTunnel) => IO[A]
  ): IO[A] =
    Dispatcher.parallel[IO].use { dispatcher =>
      for
        ms <- NeblinkService.create(0, dispatcher)
        client = new NeblinkClient(
          NeblinkServerConfig(url = fix.url, networkId = Net, secret = "qa-secret"),
          0,
          identity = Some(IO.pure(DeviceIdentity(Device, "qa-host", "macos")))
        )
        _ = ms.setRelayClient(Some(client))
        ps = new NeblinkPresenceService(ms, 0)(dispatcher)
        discovery = new NeblinkDiscovery(ms, 0, ps, Some(client))
        tunnel = new NeblinkRelayTunnel(
          ms,
          () => discovery.currentClient.map(_.flatMap(_.currentSessionToken))
        )(dispatcher)
        _ = ms.setRelayTunnel(tunnel)
        _ <- ms.setRelayTunnelStarter(tunnel.ensure())
        _ <- ms.updateConfig(_.copy(
          enabled = true,
          neblinkServer = Some(NeblinkServerConfig(url = fix.url, networkId = Net, secret = "qa-secret"))
        ))
        out <- body(ms, client, tunnel).guarantee(tunnel.stop())
      yield out
    }

  /** 每 250ms 采一次 (isAlive, 101 计数)，直到出现第二个 101 或预算耗尽。 */
  private def sampleUntilReconnect(
    fix: RelayAuthFixtureServer,
    tunnel: NeblinkRelayTunnel,
    budget: FiniteDuration
  ): IO[Vector[(Boolean, Int)]] =
    IO.monotonic.flatMap { start =>
      def loop(acc: Vector[(Boolean, Int)]): IO[Vector[(Boolean, Int)]] =
        for
          now <- IO.monotonic
          n = fix.attemptCount(101)
          a = tunnel.isAlive
          acc2 = acc :+ ((a, n))
          out <-
            if n >= 2 || now - start > budget then IO.pure(acc2)
            else IO.sleep(250.millis) *> loop(acc2)
        yield out
      loop(Vector.empty)
    }

  test("①opt-A1 僵尸连接自愈：abort 后闩被看门狗补完，≤60s 内出现第二个 101") {
    withFixture { fix =>
      fix.relayMode = RelayAuthFixtureServer.RelayMode.AcceptIfLive
      fix.pongReplies = false // 僵尸形态：服务端从不回 pong
      withStack(fix) { (_, client, tunnel) =>
        for
          _ <- client.login(Device, "qa-host", "macos", Nil)
          _ <- tunnel.connect()
          first <- waitUntil(20.seconds)(IO(fix.attemptCount(101) >= 1))
          _ <- IO(assert(first, s"基线连接失败：${fix.relayAttempts}"))
          aliveOnConnect <- IO(tunnel.isAlive)
          t0 <- IO.monotonic
          samples <- sampleUntilReconnect(fix, tunnel, 70.seconds)
          t1 <- IO.monotonic
          elapsed = (t1 - t0).toSeconds
          reconnected = samples.exists(_._2 >= 2)
          // 僵尸窗口：仍停在第一条连接（101 = 1）却被诚实判死
          deadWhileFirstConn = samples.exists { case (alive, n) => !alive && n == 1 }
          // 反例基线（修前形态）：永远不会出现「未重连但已判死」的采样
        yield
          assertEquals(aliveOnConnect, true, "刚连上（pong 窗内）时 isAlive 必须为真")
          assert(reconnected, s"僵尸态必须在 ≤60s 内重连（实测 ${elapsed}s，101=${fix.attemptCount(101)}）")
          assert(elapsed <= 60, s"重连时延必须 ≤60s，实测 ${elapsed}s")
          assert(
            deadWhileFirstConn,
            "①-2：僵尸窗口内必须出现 isAlive=false 且尚未重连的采样（修前 alive 恒真 ⇒ 线上 4h 假阳性；" +
              s"samples=${samples.take(6)}…${samples.takeRight(3)}）"
          )
          // 观察窗内的重连次数有界（无 flap 风暴）：50s 预算内 101 计数不应超过 3
          assert(fix.attemptCount(101) <= 3, s"重连次数有界（实测 ${fix.attemptCount(101)}）")
      }
    }
  }

  test("①opt-A1 反证 · 健康路径（服务端回 pong）：45s 内只有一次 101 且 isAlive 恒真（无 flap）") {
    withFixture { fix =>
      fix.relayMode = RelayAuthFixtureServer.RelayMode.AcceptIfLive
      fix.pongReplies = true // 健康形态：服务端回 {"type":"pong"}（relay.rs 同形）
      withStack(fix) { (_, client, tunnel) =>
        for
          _ <- client.login(Device, "qa-host", "macos", Nil)
          _ <- tunnel.connect()
          first <- waitUntil(20.seconds)(IO(fix.attemptCount(101) >= 1))
          _ <- IO(assert(first, s"基线连接失败：${fix.relayAttempts}"))
          // 45s > LivenessTimeoutMs(30s) + 心跳周期(10s)：若 pong 窗口误杀或看门狗
          // 提前完成闩，这段时间内必然出现第二个 101 或 isAlive 落假。
          samples <- IO.sleep(45.seconds) *> IO(fix.attemptCount(101) -> tunnel.isAlive)
          (n101, aliveAtEnd) = samples
        yield
          assertEquals(n101, 1, s"健康路径不得重连（flap）：101=${n101}")
          assertEquals(aliveAtEnd, true, "健康路径 isAlive 必须恒真（pong 窗口判据不误杀常连）")
          assert(fix.pongsSent.get() >= 3, s"45s 内应至少应答 4 拍心跳中的 3 拍，实测 ${fix.pongsSent.get()}")
      }
    }
  }

end NeblinkRelayTunnelZombieHealSpec
