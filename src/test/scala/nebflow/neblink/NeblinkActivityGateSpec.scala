package nebflow.neblink

import cats.effect.IO
import cats.effect.std.Dispatcher
import munit.CatsEffectSuite
import nebflow.shared.PathUtil // W1 shim: main had nebflow.core.PathUtil; PR moved it to shared

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.duration.*

/** 腿 A（chain `neblink-lifecycle-fix`）·「`enabled=false` 不得跨重启」活动面 gate。
  *
  * 判词（作者 2026-09-22 二择裁定 A 腿）：`enabled` 从未接线成控制 ⇒ 登出后重启，
  * 服务照样拨号、照样把本机灌回服务端连接表。修法方向**由作者给定**：不得改成
  * 「启动时不建 service」（登录面挂在 `neblinkService` 上，不建 ⇒ 用户再也无法
  * 重新登录）；正解 = **服务照建，活动面 gate 在 `enabled` 上**。
  *
  * 本 spec 逐腿钉住「空转」（零外发）与「运行期可翻转」（无需重启）两件事：
  *  - ① sync 拍（`NeblinkService.syncLoop` → `runSyncCycle` 钩子）：`enabled=false`
  *    时**一拍都不跑**；`true` 时照跑（负控 —— 否则「零」可能只是循环没起来）；
  *  - ② discovery 腿（`NeblinkDiscovery.discoverCycle`）+ ③ heartbeat 腿
  *    （`heartbeatCycle`）—— presence 拨号的**唯一驱动源**（`presenceService.syncPeers`
  *    只被这两条腿调用，见 `NeblinkDiscovery.scala:97` / `:123`）⇒ 两条腿零外发
  *    ⇔ 零 presence 拨号；
  *  - ④ relay 隧道腿（`NeblinkRelayTunnel.connectLoop`）：`enabled=false` 时
  *    **零升级尝试**（tokenGetter 一次都不被读 ⇒ 连「准备拨号」都不发生）；
  *  - ⑤ 运行期翻转：`updateConfig(enabled=true)`（登录成功路径的等价物）后
  *    **一拍内**恢复外发 —— 证明判据是**逐拍现读**、不是启动期快照（禁重启依赖）。
  *
  * 🔴 本 spec 全程只打**桩**（stub）与**环回夹具**，零真实外发、零生产端点。
  */
class NeblinkActivityGateSpec extends CatsEffectSuite:

  private var tmpDir: java.nio.file.Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  /** 🔴 必须换数据根：宿主真实 home 的 `neblink/config.json` 现在是 `enabled=true`
    * （现读 2026-09-22），不隔离 ⇒「默认 false」类断言会被宿主配置污染。 */
  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("nb-activity-gate-spec")
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(os.Path(tmpDir, os.pwd))
    super.afterEach(context)

  /** 外发计数器桩：`discover` / `heartbeat` 各计一次 —— 两腿的**唯一**外发入口。
    * 覆写后不触网（`NeblinkServerConfig.url` 指向丢弃端口也无妨：永不发出）。 */
  private final class CountingClient extends NeblinkClient(
        NeblinkServerConfig(url = "http://127.0.0.1:9", networkId = "n1", secret = "s"),
        0
      ):
    val discoverCalls = new AtomicInteger(0)
    val heartbeatCalls = new AtomicInteger(0)

    override def discover(
      deviceId: String,
      deviceName: String,
      platform: String,
      endpoints: List[NeblinkEndpoint]
    ): IO[Either[String, List[NeblinkPeerInfo]]] =
      IO(discoverCalls.incrementAndGet()).as(Right(Nil))

    override def heartbeat: IO[Either[String, List[NeblinkPeerInfo]]] =
      IO(heartbeatCalls.incrementAndGet()).as(Right(Nil))

  private def withStack[A](
    body: (NeblinkService, NeblinkPresenceService, NeblinkDiscovery, CountingClient) => IO[A]
  ): IO[A] =
    Dispatcher.parallel[IO].use { dispatcher =>
      for
        ms <- NeblinkService.createForTest(0, dispatcher, 300.millis)
        client = new CountingClient
        ps = new NeblinkPresenceService(ms, 0)(dispatcher)
        discovery = new NeblinkDiscovery(ms, 0, ps, Some(client))
        out <- body(ms, ps, discovery, client)
      yield out
    }

  // ===== ① sync 拍 =====

  test("① enabled=false ⇒ sync 拍不跑任何 cycle（零外发）；enabled=true ⇒ 照跑（负控）") {
    withStack { (ms, _, _, _) =>
      val cycles = new AtomicInteger(0)
      for
        // 默认配置 = NeblinkConfig.default（enabled=false，见 NeblinkModelSpec 同判据）
        cfg0 <- ms.neblinkConfig
        _ <- IO(assertEquals(cfg0.enabled, false, "本 spec 的起点必须是 enabled=false"))
        _ <- ms.setDiscoveryHook(IO(cycles.incrementAndGet()).void)
        fiber <- ms.startSyncLoop.start
        _ <- IO.sleep(400.millis)
        cyclesWhileDisabled <- IO(cycles.get())
        // 运行期翻转（= 登录成功路径 `NeblinkEnrollment.persistImpl` 的等价物）
        _ <- ms.updateConfig(_.copy(enabled = true))
        // 掐断当前这一觉（生产里由 sendSync / ensure 承担；见 §5 注释）
        _ <- ms.sendSync(SyncCommand.PeerDiscovered)
        _ <- IO.sleep(600.millis)
        cyclesAfterEnable <- IO(cycles.get())
        _ <- fiber.cancel
      yield
        assertEquals(cyclesWhileDisabled, 0, "enabled=false 时 sync 拍必须零 cycle（否则登出后重启仍会拨号）")
        assert(cyclesAfterEnable >= 1, s"enabled=true 后必须照跑（负控：证明零不是循环没起来）：$cyclesAfterEnable")
    }
  }

  // ===== ②③ discovery / heartbeat 腿 =====

  test("②③ enabled=false ⇒ 发现/心跳两腿零外发（= 零 presence 拨号）；enabled=true ⇒ 照发（负控）") {
    withStack { (ms, _, discovery, client) =>
      for
        _ <- discovery.discoverCycle
        _ <- discovery.heartbeatCycle
        offDiscover <- IO(client.discoverCalls.get())
        offHeartbeat <- IO(client.heartbeatCalls.get())
        // 负控：同一对象、同一入口，只把闸张开
        _ <- ms.updateConfig(_.copy(enabled = true))
        _ <- discovery.discoverCycle
        _ <- discovery.heartbeatCycle
        onDiscover <- IO(client.discoverCalls.get())
        onHeartbeat <- IO(client.heartbeatCalls.get())
      yield
        assertEquals(offDiscover, 0, "enabled=false：discoverCycle 不得外发")
        assertEquals(offHeartbeat, 0, "enabled=false：heartbeatCycle 不得外发")
        assertEquals(onDiscover, 1, "enabled=true：discoverCycle 必须照发（负控）")
        assertEquals(onHeartbeat, 1, "enabled=true：heartbeatCycle 必须照发（负控）")
    }
  }

  test("②③ 反向臂：enabled=true 时两腿行为与改前一致（同一桩同帧读数）") {
    withStack { (ms, _, discovery, client) =>
      for
        _ <- ms.updateConfig(_.copy(enabled = true))
        _ <- discovery.discoverCycle
        _ <- discovery.heartbeatCycle
        d <- IO(client.discoverCalls.get())
        h <- IO(client.heartbeatCalls.get())
      yield
        // 改前：两腿无条件直通 client ⇒ 各恰一次。本断言对「改后」同帧同值。
        assertEquals((d, h), (1, 1), "enabled=true 下两腿行为不得回归")
    }
  }

  // ===== ④ relay 隧道腿 =====

  test("④ enabled=false ⇒ relay 隧道零升级尝试（tokenGetter 一次都不读）") {
    Dispatcher.parallel[IO].use { dispatcher =>
      for
        ms <- NeblinkService.createForTest(0, dispatcher, 300.millis)
        _ <- ms.updateConfig(_.copy(
          enabled = false,
          neblinkServer = Some(NeblinkServerConfig(url = "http://127.0.0.1:9", networkId = "n1", secret = "s"))
        ))
        tokenReads = new AtomicInteger(0)
        tunnel = new NeblinkRelayTunnel(
          ms,
          () => IO(tokenReads.incrementAndGet()).as(Option.empty[String])
        )(dispatcher)
        _ = ms.setRelayTunnel(tunnel)
        _ <- tunnel.connect()
        _ <- IO.sleep(700.millis)
        off <- IO(tokenReads.get())
        runningWhileDisabled <- IO(tunnel.isRunning)
        // 运行期翻转：登录成功后闸张开 ⇒ 空转腿恢复为既有梯子（并开始读 token）
        _ <- ms.updateConfig(_.copy(enabled = true))
        _ <- tunnel.ensure() // 掐断空转那一觉（= GatewayMain/enrollment 的既有信号）
        _ <- IO.sleep(500.millis)
        on <- IO(tokenReads.get())
        _ <- tunnel.stop()
      yield
        assertEquals(off, 0, "enabled=false：隧道不得读 token ⇒ 零升级尝试（登出后重启零拨号）")
        assertEquals(runningWhileDisabled, true, "空转期间 loop 必须仍在跑（可被重登即时点亮）")
        assert(on >= 1, s"enabled=true 后必须回到既有梯子：tokenReads=$on")
    }
  }

end NeblinkActivityGateSpec
