package nebflow.neblink

import cats.effect.{Deferred, IO}
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.shared.PathUtil

import java.net.http.WebSocket
import java.nio.file.Files
import java.util.concurrent.{CompletableFuture, ConcurrentLinkedQueue}
import scala.concurrent.duration.*

/**
 * hbzombie-src（2026-10-14 客户端腿同案确认批）D1 回归钉：
 * 心跳 ping 的发送失败面必须按**真实原因**分流，不得把一切同步异常都读成
 * 「socket 已死 ⇒ abort」。
 *
 * 背景（A4 真实 WAN 补测崩塌的客户端侧放大器）：JDK `WebSocket.sendText`
 * 在「前一发未完成」时抛 `IllegalStateException`（`RelayFrameSender` 的锁注释
 * 即此契约）。bulk 传输期间 body 帧几乎恒占在飞发送位 ⇒ 心跳拍必撞 ⇒ 修前
 * 每撞一次就 abort 一条出向完好的活连接。且真写失败由 JDK 放进返回的 future
 * （不抛）⇒ 修前的 catch 对真失败是死代码。
 *
 * 本 spec 用假 socket 逐面钉死修后口径：
 *   1. 正常入队 ⇒ 有 ping、不 abort；
 *   2. 同步 IllegalStateException + 出向开（= 另一发送在飞）⇒ 只跳拍，**不 abort**；
 *   3. 同步异常 + 出向已关 ⇒ 真死，立即 abort（修前原意图保留）；
 *   4. 入队成功但 future 异步失败（真写失败的面）⇒ abort（原意图落到真实触发点）。
 *
 * 纯本机假 socket，不起任何实例、不触任何端口。
 */
class NeblinkRelayTunnelHeartbeatSendSpec extends CatsEffectSuite:

  private var tmpDir: java.nio.file.Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("nb-tunnel-hb-send-spec")
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(os.Path(tmpDir, os.pwd))
    super.afterEach(context)

  /** 可编程假 socket：同步抛出 / 异步失败 / 正常完成，出向开关联动。 */
  private final class FakeWebSocket(
    syncThrow: Option[Throwable] = None,
    asyncFail: Option[Throwable] = None,
    outputClosed: Boolean = false
  ) extends WebSocket:
    val sent = new ConcurrentLinkedQueue[String]()
    @volatile var aborted = false
    override def sendText(data: CharSequence, last: Boolean): CompletableFuture[WebSocket] =
      syncThrow.foreach(t => throw t)
      sent.add(data.toString)
      asyncFail match
        case Some(t) => CompletableFuture.failedFuture(t)
        case None    => CompletableFuture.completedFuture(this)
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
    override def isOutputClosed: Boolean = outputClosed
    override def isInputClosed: Boolean = false
    override def abort(): Unit = aborted = true

  private def withTunnel[A](body: (NeblinkRelayTunnel, Deferred[IO, Unit]) => IO[A]): IO[A] =
    Dispatcher.parallel[IO].use { dispatcher =>
      for
        ms <- NeblinkService.create(0, dispatcher)
        tunnel = new NeblinkRelayTunnel(ms, () => IO.pure(Option.empty[String]))(dispatcher)
        closed <- Deferred[IO, Unit]
        out <- body(tunnel, closed).guarantee(tunnel.stop())
      yield out
    }

  test("D1-1 正常拍：ping 入队成功 ⇒ 帧真的交给 socket 且不 abort") {
    withTunnel { (tunnel, closed) =>
      val fake = new FakeWebSocket()
      IO(tunnel.sendHeartbeatPing(fake, closed)).map { _ =>
        assertEquals(fake.sent.size(), 1, "正常拍必须发出一条 ping")
        assert(fake.sent.peek().contains("\"ping\""), s"帧形必须是心跳 ping：${fake.sent.peek()}")
        assertEquals(fake.aborted, false, "正常拍不得 abort")
      }
    }
  }

  test("D1-2 bulk 在飞撞单发契约（IllegalStateException + 出向开）⇒ 只跳拍，绝不 abort 活连接") {
    withTunnel { (tunnel, closed) =>
      val fake = new FakeWebSocket(syncThrow = Some(new IllegalStateException("previous send not completed")))
      // 连打多拍模拟「持续 bulk、每拍都撞」：任何一拍都不许撕连接
      (1 to 5).toList.traverse_(_ => IO(tunnel.sendHeartbeatPing(fake, closed))).map { _ =>
        assertEquals(fake.aborted, false, "出向开时的发送撞车 = 另一发送在飞，不是连接死亡证据")
      }
    }
  }

  test("D1-3 同步异常 + 出向已关 ⇒ 真死：立即 abort（修前原语义保留）") {
    withTunnel { (tunnel, closed) =>
      val fake = new FakeWebSocket(
        syncThrow = Some(new IllegalStateException("output closed")),
        outputClosed = true
      )
      IO(tunnel.sendHeartbeatPing(fake, closed)).map { _ =>
        assertEquals(fake.aborted, true, "出向已关的发送失败 = socket 真死，必须立即 abort")
      }
    }
  }

  test("D1-4 future 异步失败（JDK 真写失败的面）⇒ abort，不再依赖永远抓不到的同步抛错") {
    withTunnel { (tunnel, closed) =>
      val fake = new FakeWebSocket(asyncFail = Some(new java.io.IOException("broken pipe")))
      IO(tunnel.sendHeartbeatPing(fake, closed)).map { _ =>
        assertEquals(fake.aborted, true, "异步写失败 = socket 真死，必须 abort（修前此面零覆盖）")
      }
    }
  }

end NeblinkRelayTunnelHeartbeatSendSpec
