package nebflow.gateway

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import org.http4s.websocket.WebSocketFrame
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * presence WS 僵尸会话回收批（台账 #950 旁支 · 2026-10-26）——两态回归：
 *
 *   1. 关闭帧（收流终结）后槽位立即释放 —— acquire/release 同 fs2 scope 的配对断言；
 *   2. 心跳超时回收 —— half-open 僵尸（对端消失、入站永久沉默）在 N 个既有心跳
 *      拍内判死：`deadSig` 直达中断收流管道 ⇒ 槽位回减 + removePeer 必达，
 *      per-ip 槽位随回收立即复纳。
 *
 * 🔴 装配方向注记（fs2 3.11 实测钉死，探针遗痕）：`concurrently` 的后台流终结
 * **不**中断前台 —— 生产装配因此让判死信号（deadSig）**双侧直达**（send 靠
 * None-terminated 双分支终结、receive 靠自身 interruptWhen），不依赖 ember 的
 * readSide.concurrently(sendSide) 对 send 终结的处置方向。本 spec 的组合同样
 * 只依赖「前台终结取消后台」这一保证（fs2 scope 语义），不依赖反向中断。
 *
 * 对照腿：pong 持续触碰活跃钟的活跃会话不判死（400ms 跨度 > deadAfter
 * 150ms —— 判死若漏了「触碰」此处必红）；装配本身不占槽（会话未起跑 =
 * 升级期幽灵计数洞，R-1b 注记的第三洞）。
 *
 * 测试用短节拍（50ms）驱动**同一**判死机制；生产节拍仍是既有 10s（零新增
 * wall-clock 轮询，红线）。断言全部取「无竞争源」的确定性形态：
 *   · 单独收流腿终结 ⇒ 释放（A1）：无 send 腿 ⇒ 零竞争；
 *   · pong 回复腿（A2）：tick=10s ⇒ 测试窗内无第二产出源 ⇒ take(1) 恒为 pong；
 *   · 首发ping（D）：无收流腿 ⇒ take(1) 恒为首拍 ping；
 *   · 判死（B）：前台收流被自身 interruptWhen 收口，后台 send 终结与否不影响断言。
 */
class PresenceWsLifecycleSpec extends CatsEffectSuite:

  private val logger = nebflow.shared.NebflowLogger.forName("test.presence-ws-lifecycle")

  private val peerIp = "100.118.229.97"
  private val deviceId = "device-under-test"
  private val pongFrame = WebSocketFrame.Text("""{"type":"pong"}""")

  /** 装配一条会话：guard + 收发流 + removePeer/僵尸回收审计钩子。 */
  private def fixture(
    cap: Int = 8,
    tick: FiniteDuration = 50.millis,
    deadAfter: FiniteDuration = 150.millis
  ): IO[(ConnGuard, PresenceRoutes.PresenceWsStreams, Ref[IO, List[String]], Ref[IO, Option[Long]])] =
    for
      guard <- ConnGuard.forConfig(ConnGuardConfig(wsPerIpCap = cap, wsTotalCap = 64), logger)
      removed <- Ref.of[IO, List[String]](List.empty)
      zombie <- Ref.of[IO, Option[Long]](None)
      streams <- PresenceRoutes.presenceWsStreams(
        connGuard = guard,
        ip = peerIp,
        deviceId = deviceId,
        removePeer = id => removed.update(id :: _),
        handleData = _ => IO.unit,
        onZombieRecycle = silentMs => zombie.set(Some(silentMs)),
        liveness = PresenceRoutes.PresenceWsLiveness(tick = tick, deadAfter = deadAfter)
      )
    yield (guard, streams, removed, zombie)

  // ── 态①a：连接关闭帧（收流终结）后槽位立即释放 ─────────────────────────

  test("state A1 close-frame: slot is released immediately, peer removed, no zombie verdict"):
    for
      (guard, streams, removed, zombie) <- fixture(tick = 1.hour, deadAfter = 2.hours) // 测试窗内无判死干扰
      _ <- streams.receive(Stream(pongFrame)).compile.drain.timeout(5.seconds) // 客户端回一声 pong 后关闭
      snap <- guard.snapshot
      removedNow <- removed.get
      zombieNow <- zombie.get
      readmit <- guard.checkWs(peerIp)
    yield
      assertEquals(snap.acceptedTotal, 1L) // 恰好一次入账
      assertEquals(snap.wsTotal, 0) // 关闭即回零 —— 槽位立即释放
      assertEquals(removedNow, List(deviceId))
      assertEquals(zombieNow, None) // 正常关闭 ≠ 僵尸回收
      assertEquals(readmit, None) // 槽位已复纳（per-ip-cap 不牵连后续连接）

  // ── 态①b：对端 ping 的 pong 回复必须上发送线（帧语义回归）────────────────

  test("state A2 wire: peer ping is answered with pong on the send line"):
    for
      // 线路协议：对端发 {"type":"ping"} ⇒ 服务端回 {"type":"pong"}（服务端自身
      // 心跳 ping 的应答 pong 无回复、只触碰活跃钟）。tick=10s ⇒ 测试窗内唯一
      // 产出源 = 对 ping 的回复 ⇒ take(1) 恒为 pong（零竞争）。
      (guard, streams, _, _) <- fixture(tick = 10.seconds, deadAfter = 30.seconds)
      pingIn = WebSocketFrame.Text("""{"type":"ping"}""")
      collected <- streams.send
        .take(1)
        .concurrently(streams.receive(Stream(pingIn) ++ Stream.never)) // 客户端保持连接（前台终结即取消）
        .compile
        .toList
        .timeout(5.seconds)
      snap <- guard.snapshot
    yield
      assertEquals(collected, List(pongFrame), "对端 ping 未获 pong 回复")
      assertEquals(snap.acceptedTotal, 1L)

  // ── 态②：half-open 僵尸 —— 心跳超时判死回收 ────────────────────────────

  test("state B heartbeat-timeout: silent half-open zombie is recycled, slot re-admits"):
    for
      (guard, streams, removed, zombie) <- fixture() // tick=50ms, deadAfter=150ms
      // Stream.never = 半开僵尸：收流永不终结、入站帧为零。后台 send 驱动既有拍；
      // 判死 ⇒ deadSig 直达中断前台收流（生产装配同一机制）⇒ 组合收口。
      _ <- streams
        .receive(Stream.never)
        .concurrently(streams.send) // 后台：既有拍上的判死检查
        .compile
        .drain
        .timeout(5.seconds) // 不判死 ⇒ 挂死，此处必红
      snap <- guard.snapshot
      removedNow <- removed.get
      zombieMs <- zombie.get
      readmit <- guard.checkWs(peerIp)
    yield
      assertEquals(snap.wsTotal, 0, "僵尸槽位未被回收")
      assertEquals(removedNow, List(deviceId), "判死回收未触达 removePeer")
      assert(zombieMs.exists(_ >= 150L), s"判死读数应 ≥ deadAfter(150ms)：$zombieMs")
      assert(zombieMs.exists(_ < 5000L), s"判死应在节拍面内发生，而非超时兜底：$zombieMs")
      assertEquals(readmit, None, "回收后 per-ip 槽位必须复纳")

  // ── 对照腿：活跃会话不误杀 ──────────────────────────────────────────────

  test("healthy peer answering pings is never recycled; its later close still releases"):
    for
      (guard, streams, removed, zombie) <- fixture() // 10 声 pong 跨 ~400ms > deadAfter 150ms
      frames = Stream.awakeEvery[IO](40.millis).as(pongFrame).take(10)
      _ <- streams.receive(frames).compile.drain.timeout(5.seconds)
      snap <- guard.snapshot
      zombieNow <- zombie.get
      removedNow <- removed.get
    yield
      assertEquals(zombieNow, None, "活跃会话（pong 持续触碰活跃钟）被判死 = 误杀")
      assertEquals(snap.wsTotal, 0) // 关闭后照常释放
      assertEquals(removedNow, List(deviceId))

  // ── 既有心跳拍照发（send 侧行为回归）────────────────────────────────────

  test("heartbeat ping still flows on the send line (existing cadence unchanged)"):
    for
      (guard, streams, _, _) <- fixture() // tick=50ms ⇒ 首拍 ping
      first <- streams.send.take(1).compile.lastOrError.timeout(5.seconds) // 无收流腿 = 无幽灵入账（升级期洞）
      snap <- guard.snapshot
    yield
      assertEquals(first, WebSocketFrame.Text("""{"type":"ping"}"""))
      assertEquals(snap.wsTotal, 0, "acquire 必须在会话作用域内 —— 仅 send 单边运行不得占槽")
      assertEquals(snap.acceptedTotal, 0L)

end PresenceWsLifecycleSpec
