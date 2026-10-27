package nebflow.gateway

import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.Queue
import cats.syntax.all.*
import fs2.{Pipe, Stream}
import org.http4s.websocket.WebSocketFrame
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * console /api/ws 面僵尸会话回收批（台账 #960 · 2026-10-26）——#954 presence 同款
 * 病同款修：装配核收敛至 [[WsZombieGuard]]（本 spec 驱动**同一**接线形状 = 路由侧
 * `guardedSend(bounded 队列流, overflowSig)(onDead)` + `guardedReceive(acquire,
 * release)(base)`），两态回归：
 *
 *   1. 关闭帧（收流终结）后槽位立即释放 —— acquire/release 同会话 fs2 scope 的
 *      配对断言（含 R-1b 升级期幽灵计数洞的回归面：send 单边运行不得占槽）；
 *   2. 心跳超时回收 —— half-open 僵尸（对端消失、入站永久沉默）在判死拍带内
 *      判死：`deadSig` 双侧直达（receive 中断 + send 终结）⇒ 槽位回减必达，
 *      per-ip 槽位随回收立即复纳。
 *
 * console 面与 presence 面的口径差（[[WsZombieGuard.ConsoleLiveness]]）：浏览器
 * 客户端自带 30s 应用层心跳（ws.js HB_INTERVAL_MS，服务端 pong 应答在
 * WsSystemHandlers.handlePing）⇒ 服务端不补发心跳帧（无应答面）、deadAfter=90s
 * = 3 × 客户端拍（容忍两拍丢失）。测试用短节拍（50ms/150ms）驱动同一判死机制。
 */
class ConsoleWsLifecycleSpec extends CatsEffectSuite:

  private val logger = nebflow.shared.NebflowLogger.forName("test.console-ws-lifecycle")

  private val clientIp = "100.118.229.97"
  private val pongFrame = WebSocketFrame.Text("""{"type":"pong"}""")

  /** 装配一条 console 形会话：共享核 + bounded 出站队列 + 审计钩子。 */
  private def fixture(
    cap: Int = 8,
    tick: FiniteDuration = 50.millis,
    deadAfter: FiniteDuration = 150.millis
  ): IO[(ConnGuard, WsZombieGuard.Session, Queue[IO, WebSocketFrame], Deferred[IO, Unit], Ref[IO, Option[Long]])] =
    for
      guard <- ConnGuard.forConfig(ConnGuardConfig(wsPerIpCap = cap, wsTotalCap = 64), logger)
      session <- WsZombieGuard.open(WsZombieGuard.Liveness(tick = tick, deadAfter = deadAfter))
      outbound <- Queue.bounded[IO, WebSocketFrame](64)
      overflow <- Deferred[IO, Unit]
      zombie <- Ref.of[IO, Option[Long]](None)
    yield (guard, session, outbound, overflow, zombie)
  end fixture

  /** 路由侧同款发送侧装配（bounded 队列流 + overflow 中断 + 判死腿）。 */
  private def sendSide(
    session: WsZombieGuard.Session,
    outbound: Queue[IO, WebSocketFrame],
    overflow: Deferred[IO, Unit],
    zombie: Ref[IO, Option[Long]]
  ): Stream[IO, WebSocketFrame] =
    session.guardedSend(
      Stream.fromQueueUnterminated(outbound),
      overflow
    )(silentMs => zombie.set(Some(silentMs)))

  /** 路由侧同款收流管装配（会话 scope acquire/release + 入站帧触碰活跃钟）。 */
  private def receiveSide(
    guard: ConnGuard,
    session: WsZombieGuard.Session
  ): Pipe[IO, WebSocketFrame, Unit] =
    session.guardedReceive(
      guard.acquireWs(clientIp),
      handle => guard.releaseWs(handle)
    )(_.evalMap(_ => IO.unit)) // 协议分发面由路由承载；本面只验装配核

  // ── 态①a：连接关闭帧（收流终结）后槽位立即释放 ─────────────────────────

  test("state A1 close-frame: slot is released immediately, no zombie verdict, re-admission open"):
    for
      (guard, session, _, _, zombie) <- fixture(tick = 1.hour, deadAfter = 2.hours) // 测试窗内无判死干扰
      _ <- receiveSide(guard, session)(Stream(pongFrame)).compile.drain.timeout(5.seconds) // 客户端回一声 pong 后关闭
      snap <- guard.snapshot
      zombieNow <- zombie.get
      readmit <- guard.checkWs(clientIp)
    yield
      assertEquals(snap.acceptedTotal, 1L) // 恰好一次入账（收流管起跑时点）
      assertEquals(snap.wsTotal, 0) // 关闭即回零 —— 槽位立即释放
      assertEquals(zombieNow, None) // 正常关闭 ≠ 僵尸回收
      assertEquals(readmit, None) // 槽位已复纳（per-ip-cap 不牵连后续连接）

  // ── 态②：half-open 僵尸 —— 心跳超时判死回收（deadSig 双侧直达）──────────

  test("state B heartbeat-timeout: silent half-open zombie is recycled, slot re-admits"):
    for
      (guard, session, outbound, overflow, zombie) <- fixture() // tick=50ms, deadAfter=150ms
      // Stream.never = 半开僵尸：收流永不终结、入站帧为零。后台 send 驱动判死拍；
      // 判死 ⇒ deadSig 直达中断前台收流 + 终结 send（ember 写 close 帧同款）⇒ 组合收口。
      _ <- receiveSide(guard, session)(Stream.never[IO])
        .concurrently(sendSide(session, outbound, overflow, zombie))
        .compile
        .drain
        .timeout(5.seconds) // 不判死 ⇒ 挂死，此处必红
      snap <- guard.snapshot
      zombieMs <- zombie.get
      readmit <- guard.checkWs(clientIp)
    yield
      assertEquals(snap.wsTotal, 0, "僵尸槽位未被回收")
      assert(zombieMs.exists(_ >= 150L), s"判死读数应 ≥ deadAfter(150ms)：$zombieMs")
      assert(zombieMs.exists(_ < 5000L), s"判死应在节拍带内发生，而非超时兜底：$zombieMs")
      assertEquals(readmit, None, "回收后 per-ip 槽位必须复纳")

  // ── 态②b：判死 send 侧直达 —— 队列流凭 deadSig 自行收口（close 帧同款）──

  test("state B2 dead verdict ends the send stream on its own (ember close-frame analog)"):
    for
      (guard, session, outbound, overflow, zombie) <- fixture()
      // 无收流腿：判死拍纯靠 send 侧自身；判死 ⇒ deadSig 终结队列成员 + 心跳腿
      // 自行终结 ⇒ 整条 send 收口（compile drain 自然返回，无需外部超时）。
      _ <- sendSide(session, outbound, overflow, zombie).compile.drain.timeout(5.seconds)
      snap <- guard.snapshot
      zombieMs <- zombie.get
    yield
      assertEquals(snap.wsTotal, 0, "send 单边运行不占槽（升级期幽灵计数洞）")
      assertEquals(snap.acceptedTotal, 0L)
      assert(zombieMs.isDefined, "判死腿未打点")

  // ── 对照腿：活跃会话（客户端 30s 自心跳的短拍驱动）不误杀 ────────────────

  test("healthy client cadence is never recycled; its later close still releases"):
    for
      (guard, session, _, _, zombie) <- fixture() // 10 声 pong 跨 ~400ms > deadAfter 150ms
      frames = Stream.awakeEvery[IO](40.millis).as(pongFrame).take(10)
      _ <- receiveSide(guard, session)(frames).compile.drain.timeout(5.seconds)
      snap <- guard.snapshot
      zombieNow <- zombie.get
    yield
      assertEquals(zombieNow, None, "活跃会话（入站帧持续触碰活跃钟）被判死 = 误杀")
      assertEquals(snap.wsTotal, 0) // 关闭后照常释放

  // ── console 口径锁：90s = 3 × 客户端自 ping 间隔（防旋钮漂移）────────────

  test("console liveness contract: tick 10s, deadAfter 90s (3 × client HB_INTERVAL_MS)"):
    assertEquals(WsZombieGuard.ConsoleLiveness.tick, 10.seconds)
    assertEquals(WsZombieGuard.ConsoleLiveness.deadAfter, 90.seconds)

end ConsoleWsLifecycleSpec
