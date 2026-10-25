package nebflow.neblink

import cats.effect.{FiberIO, IO, Outcome}
import cats.syntax.all.*
import munit.CatsEffectSuite

import java.util.concurrent.CompletableFuture
import java.net.http.WebSocket
import scala.concurrent.duration.*

/**
 * 稳定性批 2026-10-26 —— 修复前后**同件对照探针**（修复前后各跑一次，红→绿）：
 * bulk 在飞 + 两个 bulk 排队时，一个控制帧（生产=隧道活性 pong / 浏览器 WS pong）
 * 必须在队列排空前完成（等待上界 = 一个在飞帧的写完）。修前形态：monitored
 * 单车道，控制帧排在 B2/B3 之后 ⇒ 本探针 10s 超时红（= 饥饿缺陷本体）。
 */
class PrefixQueueOrderProbe extends CatsEffectSuite:

  private final class GateFakeWebSocket extends WebSocket:
    val sent = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    private val bulkFutures = new java.util.concurrent.ConcurrentLinkedQueue[CompletableFuture[WebSocket]]()
    private val controlFutures = new java.util.concurrent.ConcurrentLinkedQueue[CompletableFuture[WebSocket]]()
    def offerBulk(f: CompletableFuture[WebSocket]): Unit = bulkFutures.add(f)
    def offerControl(f: CompletableFuture[WebSocket]): Unit = controlFutures.add(f)
    override def sendText(data: CharSequence, last: Boolean): CompletableFuture[WebSocket] =
      sent.add(data.toString)
      // 按**车道**派发（稳定性批 2026-10-26 修后控制帧越过 bulk 排队，调用序
      // 不再等于车道序）：控制帧吃 control 队列，bulk 帧吃 bulk 队列。
      val q = if data.toString == Control then controlFutures else bulkFutures
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
    override def abort(): Unit = ()

  private def awaitFiber(fib: FiberIO[Unit]): IO[Unit] =
    fib.join.flatMap {
      case Outcome.Succeeded(_) => IO.unit
      case Outcome.Errored(e)   => IO.raiseError(e)
      case Outcome.Canceled()   => IO.canceled
    }

  private val Bulk = "b" * (400 * 1024)
  private val Control = """{"type":"pong"}"""

  test("对照探针：bulk 在飞+排队 ⇒ 控制帧先于队列排空完成（修前红/修后绿）"):
    val fake = new GateFakeWebSocket
    val sender = new RelayFrameSender(fake, sendTimeoutSec = 30, pendingRetryMillis = 5)
    val f1 = new CompletableFuture[WebSocket]()
    val f2 = new CompletableFuture[WebSocket]()
    val f3 = new CompletableFuture[WebSocket]()
    val fC = new CompletableFuture[WebSocket]()
    fake.offerBulk(f1); fake.offerBulk(f2); fake.offerBulk(f3)
    fake.offerControl(fC)
    val program =
      for
        fibB1 <- sender.send(Bulk).start
        _ <- IO.sleep(120.millis)
        fibB2 <- sender.send(Bulk).start
        fibB3 <- sender.send(Bulk).start
        _ <- IO.sleep(120.millis)
        fibC <- sender.send(Control).start
        _ <- IO.sleep(120.millis)
        // 控制帧自己的写完成闸提前给足（提前完成的 future 在 sendText 取到时
        // 即时返回）——本探针量的是「控制帧何时上得了发送位」，非写时长。
        _ <- IO(fC.complete(fake))
        _ <- IO(f1.complete(fake))
        _ <- awaitFiber(fibC) // 修前 = 等 B2/B3 排空（f2/f3 未完成 ⇒ 超时红）
        _ <- IO(assert(!f2.isDone && !f3.isDone, "控制完成后排队 bulk 不应已被放行"))
        _ <- IO(f2.complete(fake)) *> awaitFiber(fibB2)
        _ <- IO(f3.complete(fake)) *> awaitFiber(fibB3)
        _ <- awaitFiber(fibB1)
      yield ()
    program.timeout(10.seconds)

end PrefixQueueOrderProbe
