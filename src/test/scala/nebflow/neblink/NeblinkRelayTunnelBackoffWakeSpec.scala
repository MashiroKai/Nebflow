package nebflow.neblink

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.core.PathUtil

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * clientconn 批（「零成本客户端组」item 1）—— 常驻长连接 / 断线重连 的三个**真实缺口**
 * 的钉子（现场重取基线 = 本分支构建点 `main @ 064b0922`，现场行号见交付载荷）：
 *
 *  1. **退避上限被 Int 移位打穿**（`connectLoop` 修前写法 `math.min(30, 1 << (attempt-1))`）：
 *     `attempt = 32` 时 `1 << 31` = `Int.MinValue` ⇒ `IO.sleep(负数)` 立即返回
 *     （断网中段凭空一次 0 延迟重连），此后梯子又从 1s 重新爬 —— 长断网（>16min）
 *     里退避周期性失守。纯断言 + 溢出形态自证。
 *  2. **退避复位不看连接是否稳定**（修前无条件 `connectLoop(0)`）：服务端 101 升级后
 *     立刻踢（或半开 NIC flap）⇒ 0 延迟重连 = 线速升级风暴。现在只有**稳定 ≥
 *     `StableConnectionMs`** 才复位。fixture 场景实测次数上界。
 *  3. **空转等待不可唤醒**：`ensure()`（enrollment 的「确保在跑」信号）在 `running==true`
 *     时修前是纯 no-op ⇒ 入网落地时若循环正睡在「无 URL」的 300s 上限里，relay 要等
 *     最多 5 分钟才可见。现在 `ensure()` 掐断进行中的等待（`nap`/`signalWake`）。
 *
 * 也用纯函数钉住抖动带（±20%）与梯子上限，避免读数字面量散落。
 *
 * fixture = `RelayAuthFixtureServer`（127.0.0.1 临时端口、真 RFC 6455 101 upgrade）；
 * 不触碰生产 VPS、不起任何网关实例、不碰 8080。
 */
class NeblinkRelayTunnelBackoffWakeSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 2.minutes

  private val Net = "qa-net"
  private val Device = "qa-device"

  private var tmpDir: java.nio.file.Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("nb-tunnel-backoff-wake-spec")
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

  // ── 纯面：梯子 / 复位判据 / 抖动带 ──────────────────────

  test("(1) 退避梯：单调爬到 30s 上限，attempt=32 的 Int 溢出不再把延迟打成 0") {
    val ladder = (0 to 8).map(NeblinkRelayTunnel.backoffSeconds).toList
    assertEquals(ladder, List(0L, 1L, 2L, 4L, 8L, 16L, 30L, 30L, 30L), "梯子形态 0,1,2,4,8,16,30…")
    // 修前形态自证：Int 移位在 attempt=32 处溢出为负数（IO.sleep(负) 立即返回）。
    assert((1 << 31) < 0, "修前写法的溢出前提（Int 移位第 32 位为符号位）")
    assertEquals(NeblinkRelayTunnel.backoffSeconds(32), 30L, "溢出点必须仍在上限")
    assertEquals(NeblinkRelayTunnel.backoffSeconds(33), 30L, "溢出后不得掉回 1s 重爬")
    val long = (1 to 200).map(NeblinkRelayTunnel.backoffSeconds).toList
    assert(long.forall(_ <= 30L), s"任何 attempt 都不得超过上限：${long.filter(_ > 30L)}")
    assert(
      long.sliding(2).forall { case List(a, b) => a <= b; case _ => true },
      "梯子必须单调不减（修前每 32 拍掉回 1s）"
    )
  }

  test("(2) 退避复位：稳定连接/首次短命断开立即重连，连续短命连接（抖动风暴）才进梯子；抖动带 ±20%") {
    // 稳定连接 ⇒ 复位（修前语义保留）
    assertEquals(NeblinkRelayTunnel.nextAttemptAfterDrop(0, 30_000L, 3), 0, "稳定连接 ⇒ 立即重连")
    assertEquals(NeblinkRelayTunnel.nextAttemptAfterDrop(7, 3_600_000L, 2), 0, "长连接后掉线仍即时重连")
    // 首次短命断开 ⇒ 仍立即重连（瞬时抖动按修前速度恢复，不无谓退避）
    assertEquals(NeblinkRelayTunnel.nextAttemptAfterDrop(0, 29_999L, 0), 0, "首次短命断开沿用立即重连")
    assertEquals(NeblinkRelayTunnel.nextAttemptAfterDrop(0, 0L, 0), 0)
    // 连续短命（≥2 次）⇒ 进梯子（修前是 0 延迟 = 线速风暴）
    assertEquals(NeblinkRelayTunnel.nextAttemptAfterDrop(0, 500L, 1), 1, "第二次短命断开必须递增（禁 0 延迟）")
    assertEquals(NeblinkRelayTunnel.nextAttemptAfterDrop(2, 500L, 5), 3, "抖动风暴按梯子递增")
    // 抖动带
    assertEquals(NeblinkRelayTunnel.jittered(1000.millis, 0.5), 1000.millis, "中位 = 无抖动")
    assertEquals(NeblinkRelayTunnel.jittered(1000.millis, 0.0), 800.millis, "下界 −20%")
    assertEquals(NeblinkRelayTunnel.jittered(1000.millis, 1.0), 1200.millis, "上界 +20%")
    assertEquals(NeblinkRelayTunnel.jittered(0.millis, 0.0), 0.millis)
  }

  // ── 行为面：唤醒 + 风暴上界 ─────────────────────────────

  /** 只装 tunnel 的最小栈：URL 已配置但**永远没有 token** ⇒ 循环必然进
    * 「no session token」分支并按梯子空转。tokenGetter 计数 = 空转节拍的可观测面。 */
  private def withIdleTunnel[A](url: String)(body: (NeblinkRelayTunnel, AtomicInteger) => IO[A]): IO[A] =
    Dispatcher.parallel[IO].use { dispatcher =>
      for
        ms <- NeblinkService.create(0, dispatcher)
        _ <- ms.updateConfig(
          _.copy(
            enabled = true,
            neblinkServer = Some(NeblinkServerConfig(url = url, networkId = Net, secret = "qa-secret"))
          )
        )
        calls = new AtomicInteger(0)
        tunnel = new NeblinkRelayTunnel(ms, () => IO(calls.incrementAndGet()).as(Option.empty[String]))(dispatcher)
        _ <- tunnel.connect()
        out <- body(tunnel, calls).guarantee(tunnel.stop())
      yield out
    }

  test("(3) 空转等待可被 ensure() 掐断：入网信号不必等满退避窗") {
    // 关掉「无 URL」分支的 300s 上限场景不可压缩，但**机制同一**：用有 URL/无 token
    // 分支（梯子 1,2,4…）验证掐断语义——第 3 拍后循环睡在 ~4s 的等待里。
    withIdleTunnel("http://127.0.0.1:1") { (tunnel, calls) =>
      for
        third <- waitUntil(20.seconds)(IO(calls.get() >= 3))
        _ <- IO(assert(third, s"循环必须按梯子空转（tokenGetter 调用数=${calls.get()}）"))
        c0 <- IO(calls.get())
        _ <- IO(assert(tunnel.napArmed, "第 3 拍后必须正睡在退避等待里（否则本用例没有判别力）"))
        _ <- IO.sleep(700.millis)
        c1 <- IO(calls.get())
        _ <- IO(assertEquals(c1, c0, "无唤醒信号时不得提前醒来（负控：等待是真的在睡）"))
        _ <- tunnel.ensure()
        cut <- waitUntil(1.5.seconds)(IO(calls.get() > c0))
        _ <- IO(
          assert(
            cut,
            s"ensure() 必须掐断进行中的等待（修前 = 纯 no-op，要睡满这一觉）：calls=${calls.get()}"
          )
        )
      yield ()
    }
  }

  /** fixture 生命周期必须挂在 IO 上（`try/finally` 会在 IO **构造期**就 close —— 那
    * 会把 fixture 在测试真正跑之前关掉，症状是 login 直接 `ConnectException`）。 */
  private def withFixture[A](body: RelayAuthFixtureServer => IO[A]): IO[A] =
    IO.blocking(new RelayAuthFixtureServer()).flatMap(f => body(f).guarantee(IO.blocking(f.close())))

  test("(4) 升级后立刻被断：重连必须走退避梯（6s 窗口的升级次数有上界，禁线速风暴）") {
    withFixture { fix =>
      fix.relayMode = RelayAuthFixtureServer.RelayMode.AcceptIfLive
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
          tunnel = new NeblinkRelayTunnel(ms, () => discovery.currentClient.map(_.flatMap(_.currentSessionToken)))(dispatcher)
          _ = ms.setRelayTunnel(tunnel)
          _ <- ms.setRelayTunnelStarter(tunnel.ensure())
          _ <- ms.updateConfig(
            _.copy(
              enabled = true,
              neblinkServer = Some(NeblinkServerConfig(url = fix.url, networkId = Net, secret = "qa-secret"))
            )
          )
          loginRes <- client.login(Device, "qa-host", "macos", Nil)
          _ <- IO(assert(loginRes.isRight, s"基线登录必须成功：$loginRes"))
          tok <- IO(client.currentSessionToken)
          _ <- IO(assert(tok.isDefined, "登录后必须持有 session token（隧道 tokenGetter 的唯一来源）"))
          _ <- tunnel.connect()
          first <- waitUntil(10.seconds)(IO(fix.attemptCount(101) >= 1))
          _ <- IO(assert(first, s"基线连接失败：${fix.relayAttempts}"))
          // 先证「服务端断开 ⇒ 会重连」这条腿本身是通的（否则下面的上界没有意义）。
          base <- IO(fix.attemptCount(101))
          _ <- IO(fix.closeAllRelaySockets())
          relinked <- waitUntil(6.seconds)(IO(fix.attemptCount(101) > base))
          _ <- IO(assert(relinked, s"连接被断后必须重连：${fix.relayAttempts}"))
          // 抖动风暴：6s 内每 100ms 断一次 —— 修前每次断开都 connectLoop(0)
          // ⇒ 0 延迟重连（线速），现在必须落在梯子上（首次立即、其后 1s,2s,4s…）。
          before <- IO(fix.attemptCount(101))
          _ <- (IO(fix.closeAllRelaySockets()) *> IO.sleep(100.millis)).replicateA_(60)
          during <- IO(fix.attemptCount(101) - before)
          _ <- IO(
            assert(
              during <= 10,
              s"升级后立刻被断必须走退避（修前 0 延迟 ⇒ 同窗口数百次）：6s 内 $during 次"
            )
          )
          _ <- IO(assert(during >= 1, s"抖动期必须仍尝试重连（上界的下界）：$during 次"))
          _ <- IO(tunnel.stop())
        yield ()
      }
    }
  }

end NeblinkRelayTunnelBackoffWakeSpec
