package nebflow.neblink

import cats.effect.{FiberIO, IO, Outcome}
import cats.syntax.all.*
import munit.CatsEffectSuite

import java.util.concurrent.{CompletableFuture, TimeUnit}
import java.net.http.WebSocket
import scala.concurrent.duration.*

/**
 * 稳定性批 2026-10-26（作者 01:09「大文件传输掉登录 + 长连接 403 泛滥」）——
 * RelayFrameSender **双车道让位**回归钉。
 *
 * 归因（详见 reports/20261026_*_session-stability/REPORT.md）：修前 `send` 只有
 * 非公平监视器一个车道，5MB+ 传输的 bulk 泵逐帧释放后可立即 barging 重夺锁，
 * 控制帧（隧道活性 pong / 浏览器控制台 WS 的 ws_frame pong / http_response 头）
 * 在发送位上的排队时长无上界——隧道被 relay 2-miss 判死、前端 3-miss 自我逐出、
 * ingress 15s resp_header 超窗回 504 被误读成凭据死，三族后果同源。
 *
 * 修后被钉语义：
 *   P1 控制车道越过 bulk 等待队列——bulk 在飞 + bulk 排队时，控制帧的完成不等
 *      队列排空（等待上界 = 一个在飞帧的写完）；
 *   P2 无控制等待者时 bulk 直通（零回归面：正常 bulk 流不因车道机制变慢）；
 *   P3 让位信用上限——连续 ControlGrantCap 个控制放行后，下一个 bulk 即使仍有
 *      控制等待者也放行（控制洪泛不饿死 bulk 的理论防线）；
 *   P4 车道判定单点：pong/小帧 = 控制车道；body_frame 尺寸（256KiB→base64
 *      ≈349K 字符）与 ws 大帧 = bulk 车道。
 *
 * 用例全部走假 socket（闸门式，完成时刻全控）——确定性，无真实时序依赖；真
 * socket 的时延对照（修复前后）由 RelayFrameSenderTimingHarness 落盘承担
 * （NF_TIMING_HARNESS=1 手动跑，证据件不进 CI）。
 */
class RelayFrameSenderPrioritySpec extends CatsEffectSuite:

  /** 闸门式假 socket：完成时刻按**车道**派发（bulk 队列 / 控制队列各自 FIFO）——
    * 修后控制帧越过 bulk 排队，「按调用序派发」不再成立，必须按帧内容分流。 */
  private final class GateFakeWebSocket(bulkFutures: java.util.Queue[CompletableFuture[WebSocket]],
                                        controlFutures: java.util.Queue[CompletableFuture[WebSocket]],
                                        controlText: String) extends WebSocket:
    val sent = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    @volatile var aborted: Boolean = false

    override def sendText(data: CharSequence, last: Boolean): CompletableFuture[WebSocket] =
      sent.add(data.toString)
      val q = if data.toString == controlText then controlFutures else bulkFutures
      val f = q.poll()
      if f == null then CompletableFuture.completedFuture(this) else f
    override def sendBinary(data: java.nio.ByteBuffer, last: Boolean): CompletableFuture[WebSocket] =
      CompletableFuture.completedFuture(this)
    override def sendClose(code: Int, reason: String): CompletableFuture[WebSocket] =
      CompletableFuture.completedFuture(this)
    override def request(n: Long): Unit = ()
    override def sendPing(data: java.nio.ByteBuffer): CompletableFuture[WebSocket] =
      CompletableFuture.completedFuture(this)
    override def sendPong(data: java.nio.ByteBuffer): CompletableFuture[WebSocket] =
      CompletableFuture.completedFuture(this)
    override def getSubprotocol: String = ""
    override def isInputClosed: Boolean = false
    override def isOutputClosed: Boolean = false
    override def abort(): Unit = aborted = true

  private def awaitFiber(fib: FiberIO[Unit]): IO[Unit] =
    fib.join.flatMap {
      case Outcome.Succeeded(_) => IO.unit
      case Outcome.Errored(e)   => IO.raiseError(e)
      case Outcome.Canceled()   => IO.canceled
    }

  private val BulkSized = "b" * (400 * 1024) // > ControlLaneMaxChars ⇒ bulk 车道
  private val ControlSized = """{"type":"pong"}"""

  // P4 车道判定单点：阈值两端。
  test("P4 车道判定：pong/小帧=控制车道；256KiB body_frame base64 尺寸=bulk 车道") {
    IO {
      assert(RelayFrameSender.isControlSized("""{"type":"pong"}"""), "pong 应走控制车道")
      assert(RelayFrameSender.isControlSized("x" * 1024), "KB 级小帧（响应头/ws 小消息）应走控制车道")
      assert(RelayFrameSender.isControlSized("x" * RelayFrameSender.ControlLaneMaxChars), "阈值边界含")
      assert(!RelayFrameSender.isControlSized("x" * (RelayFrameSender.ControlLaneMaxChars + 1)), "阈值 +1 应落 bulk")
      // 生产 body_frame 的真实下限尺寸：256KiB chunk 的 base64 + envelope。
      val bodyFrameChars = 349525
      assert(!RelayFrameSender.isControlSized("x" * bodyFrameChars), "body_frame 尺寸必须落 bulk 车道")
      assert(!RelayFrameSender.isControlSized("x" * (4 * 1024 * 1024)), "ws 大帧必须落 bulk 车道")
    }
  }

  // P1 核心钉：bulk 在飞 + bulk 排队 ⇒ 控制帧完成不等队列排空。
  test("P1 控制车道越过 bulk 等待队列：bulk 在飞+排队时控制帧先完成") {
    val f1 = new CompletableFuture[WebSocket]() // B1（在飞 bulk）的写完成闸
    val f2 = new CompletableFuture[WebSocket]() // B2（排队 bulk）
    val f3 = new CompletableFuture[WebSocket]() // B3（排队 bulk）
    val fC = new CompletableFuture[WebSocket]() // C（控制）
    val bulkQ = new java.util.concurrent.ConcurrentLinkedQueue[CompletableFuture[WebSocket]]()
    val ctlQ = new java.util.concurrent.ConcurrentLinkedQueue[CompletableFuture[WebSocket]]()
    bulkQ.add(f1); bulkQ.add(f2); bulkQ.add(f3)
    ctlQ.add(fC)
    val fake = new GateFakeWebSocket(bulkQ, ctlQ, ControlSized)
    val sender = new RelayFrameSender(fake, sendTimeoutSec = 10, pendingRetryMillis = 5)

    val program =
      for
        fibB1 <- sender.send(BulkSized).start
        _ <- IO.sleep(100.millis) // B1 已持锁（在飞，f1 未完成）
        fibB2 <- sender.send(BulkSized).start
        fibB3 <- sender.send(BulkSized).start
        _ <- IO.sleep(100.millis) // B2/B3 已入等待（公平锁 FIFO：B2 → B3 → C）
        fibC <- sender.send(ControlSized).start
        _ <- IO.sleep(150.millis) // controlWaiters 已自增（拿锁前）
        // C 自身的写完成闸提前给足（提前完成的 future 在 sendText 取到时即时
        // 返回）——本钉断言的是「C 何时**上得了发送位**」，不是它的写时长。
        _ <- IO(fC.complete(fake))
        _ <- IO(f1.complete(fake)) // B1 写完 → B2/B3 依 FIFO 取锁、见控制等待者让位 → C 上位
        _ <- awaitFiber(fibC)      // 修前 = C 等 B2/B3 排空才轮到（这里 f2/f3 未完成 ⇒ 永等 → 超时红）
        _ <- IO(assert(!f2.isDone, "控制完成后排队 bulk 不应已被放行（C 越过了队列）"))
        _ <- IO(assert(!f3.isDone, "同上（第二排队 bulk）"))
        _ <- IO(f2.complete(fake)) *> awaitFiber(fibB2)
        _ <- IO(f3.complete(fake)) *> awaitFiber(fibB3)
        _ <- awaitFiber(fibB1)
      yield ()
    program.timeout(15.seconds)
  }

  // P2 零回归：无控制等待者时 bulk 直通（车道机制不给正常 bulk 流加等待）。
  test("P2 无控制等待者：bulk 直通（车道机制零开销面）") {
    val fake = new GateFakeWebSocket(
      new java.util.concurrent.ConcurrentLinkedQueue[CompletableFuture[WebSocket]](),
      new java.util.concurrent.ConcurrentLinkedQueue[CompletableFuture[WebSocket]](),
      ControlSized
    )
    val sender = new RelayFrameSender(fake, sendTimeoutSec = 5, pendingRetryMillis = 5)
    for
      _ <- sender.send(BulkSized)
      _ <- sender.send(BulkSized)
      _ <- sender.send(ControlSized)
      _ <- IO(assertEquals(fake.sent.size(), 3, "三帧全部直接放行"))
    yield ()
  }

  // P3 让位信用上限：连续 cap 个控制放行后，bulk 强制放行（不等更多控制）。
  test("P3 让位信用上限：连续 ControlGrantCap 个控制后 bulk 强制放行") {
    val cap = 2
    val f1 = new CompletableFuture[WebSocket]()
    val fC1 = new CompletableFuture[WebSocket]()
    val fC2 = new CompletableFuture[WebSocket]()
    val fC3 = new CompletableFuture[WebSocket]()
    val fB2 = new CompletableFuture[WebSocket]()
    val bulkQ = new java.util.concurrent.ConcurrentLinkedQueue[CompletableFuture[WebSocket]]()
    val ctlQ = new java.util.concurrent.ConcurrentLinkedQueue[CompletableFuture[WebSocket]]()
    bulkQ.add(f1); bulkQ.add(fB2)
    ctlQ.add(fC1); ctlQ.add(fC2); ctlQ.add(fC3)
    val fake = new GateFakeWebSocket(bulkQ, ctlQ, ControlSized)
    val sender = new RelayFrameSender(fake, sendTimeoutSec = 10, pendingRetryMillis = 5, controlGrantCap = cap)

    val program =
      for
        fibB1 <- sender.send(BulkSized).start
        _ <- IO.sleep(100.millis)
        fibC1 <- sender.send(ControlSized).start
        fibC2 <- sender.send(ControlSized).start
        _ <- IO.sleep(100.millis)
        fibB2 <- sender.send(BulkSized).start
        _ <- IO.sleep(100.millis)
        fibC3 <- sender.send(ControlSized).start
        _ <- IO.sleep(100.millis)
        // 公平锁 FIFO 泊位序：C1 → C2 → B2 → C3（C3 晚于 B2 到达，排在队尾）。
        // C1/C2/B2 的写完成闸提前给足（断言的是上位于序，不是写时长）；fC3 保持
        // 手动——「B2 放行时 fC3 未完成」的断言依赖它未被提前完成。
        _ <- IO(fC1.complete(fake))
        _ <- IO(fC2.complete(fake))
        _ <- IO(fB2.complete(fake))
        _ <- IO(f1.complete(fake)) // B1 写完
        _ <- awaitFiber(fibC1) // 控制#1（信用 1）
        _ <- awaitFiber(fibC2) // 控制#2（信用 2 = cap）
        _ <- awaitFiber(fibB2) // B2 放行：信用已达 cap，即使 C3 仍在等
        _ <- IO(assert(!fC3.isDone, "B2 放行时未轮到的控制帧应仍在等待（信用上限定向）"))
        _ <- IO(fC3.complete(fake)) *> awaitFiber(fibC3)
        _ <- awaitFiber(fibB1)
      yield ()
    program.timeout(20.seconds)
  }

end RelayFrameSenderPrioritySpec
