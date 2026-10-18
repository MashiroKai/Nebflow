package nebflow.neblink

import cats.effect.{FiberIO, IO, Outcome}
import cats.syntax.all.*
import munit.CatsEffectSuite

import java.io.IOException
import java.net.{InetAddress, ServerSocket, Socket, URI}
import java.net.http.{HttpClient, WebSocket}
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.{CompletableFuture, ConcurrentLinkedQueue, TimeoutException, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * portal-cycle 批（2026-10-15，board #148·三裁②）——RelayFrameSender 单发位
 * 发送纪律回归钉（复测 W1 两个邻接失败面的修复件）。
 *
 * 根因（复测报告 .nebflow/reports/20261015_090700_pump-selfabort-retest W1 读数
 * ＋根修报告 20261015_082550 §机制链：PortalTunnelBridge.scala RelayFrameSender
 * 持 per-socket 锁跨 future.get(15s)，慢 WAN 每帧 0.4–13s 占位）：修前 `send`
 * = 「锁跨 `future.get(15s)`」单一语义，两个失败面都出自它的盲区：
 *
 *  - **写停摆超时**（W1 `TimeoutException` ×5）：慢 WAN/路径停摆下 256KiB 帧
 *    的 wire 写在 15s 内完不成 ⇒ 周期失败。且 `get` 超时**不取消** future——
 *    帧仍在写、JDK 单发位（`pendingTextOrBinary`）仍被占；修前丢掉该事实，
 *    下一发撞位拿到已失败 `IllegalStateException("Send pending")` future ⇒
 *    第二个健康周期被一个误导性错误连坐（毒化面）。
 *  - **单发位瞬态碰撞**（W1 `ISE: Send pending` ×4）：心跳 ping/pong、ack、
 *    bind_confirm、relay_response 等**绕锁直发方**（NeblinkRelayTunnel :832
 *    :910 :980 :1042 :1237 :1700 直接 `ws.sendText`——控制面不得被 bulk 顶头
 *    阻塞，绕锁是有意的）恰在本方入队瞬间占位 ⇒ 修前一发拒接 = 整周期失败。
 *
 * 修后被钉语义：
 *   1. 排空在飞前驱（有界）再入队——顺序由构造保证，停摆恢复则下一周期存活；
 *   2. 瞬态拒接（失败 future 携 ISE 且出向开；含同步抛变体的防御面）小额停等
 *      重试，有界——拒接是背压不是死亡；
 *   3. 超时三件诚实：可读 TimeoutException、**不 abort socket**（撕会话不属于
 *      sender 的权限）、在飞帧保持记账（含排空超时/中断后的记账保持）；
 *   4. 真死立即如实传播：IOException 族、ISE 但出向已关。
 *
 * 面 → 用例：假 socket（闸门式，完成时刻全控）钉 T1–T9 分类与预算语义；
 * 真 RFC 6455 夹具（对端读延迟可控）钉 R1（W1 ×5 形态端到端：诚实超时＋连接
 * 完好＋恢复后下一发存活）与 R2（真 JDK CAS 撞位重试——W1 ×4 形态）。
 * 「Send pending 走已失败 future 而非同步抛」的本 JDK 契约本体已由
 * pump-selfabort P1 真 socket 钉在库（NeblinkRelayTunnelHeartbeatSendSpec），
 * 本 spec 不重复；同步抛变体（异构 JDK）由 T3 防御面覆盖。
 *
 * 与件1（pump-selfabort-fix，4ffb11fe）的交互：件1 修的是**心跳面**的同一
 * 撞位（跳拍不撕），本批修的是 **bulk 面本方**的拒接吸收——合并态下 ping 让
 * 位（件1）＋bulk 重试吸收残余碰撞窗（本批）两面互补；本 spec 的 T2/R2 即
 * bulk 面独立钉，合并态全量回归由专项 scratch 验证跑承担（见批次报告）。
 *
 * 真 socket 夹具只绑 127.0.0.1 临时端口；不起任何实例、不触固定端口。
 */
class NeblinkRelayFrameSenderSendCycleSpec extends CatsEffectSuite:

  // ───────────────────── 假 socket（闸门式，完成时刻全控）─────────────────────

  /** 可编程假 socket：`behavior` 逐发可换（正常完成 / 失败 future / 同步抛 /
    * 手工完成的不完整 future），调用时刻逐发留 nanos 供顺序断言。 */
  private final class GateFakeWebSocket(outputClosed0: Boolean = false) extends WebSocket:
    val sent = new ConcurrentLinkedQueue[String]()
    val callNanos = new ConcurrentLinkedQueue[java.lang.Long]()
    @volatile var aborted: Boolean = false
    @volatile var outputClosed: Boolean = outputClosed0
    @volatile var behavior: () => CompletableFuture[WebSocket] =
      () => CompletableFuture.completedFuture(this)

    def callCount: Int = callNanos.size()

    override def sendText(data: CharSequence, last: Boolean): CompletableFuture[WebSocket] =
      callNanos.add(System.nanoTime())
      sent.add(data.toString)
      behavior()
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
    override def isOutputClosed: Boolean = outputClosed
    override def abort(): Unit = aborted = true

  private def failWith[A](e: Throwable, expect: String): IO[A] =
    IO.raiseError(new AssertionError(s"期望 $expect，实得：${e.getClass.getName}: ${Option(e.getMessage).getOrElse("")}", e))

  /** join 并传播纤维结局（本仓 CE 版本无 joinWithDefault）。 */
  private def awaitFiber(fib: FiberIO[Unit]): IO[Unit] =
    fib.join.flatMap {
      case Outcome.Succeeded(_) => IO.unit
      case Outcome.Errored(e)   => IO.raiseError(e)
      case Outcome.Canceled()   => IO.canceled
    }

  // T1 正常发送：一次入队、一次成功、零 abort。
  test("T1 正常发送：入队成功 ⇒ 帧交给 socket，恰好一次调用，不 abort") {
    val fake = new GateFakeWebSocket()
    val sender = new RelayFrameSender(fake)
    sender.send("""{"type":"relay_response"}""").map { _ =>
      assertEquals(fake.callCount, 1, "正常拍恰好一次 sendText")
      assertEquals(fake.sent.peek(), """{"type":"relay_response"}""")
      assertEquals(fake.aborted, false)
    }
  }

  // T2 W1 ×4 面（异步拒接）：失败 future 携 ISE 且出向开 ⇒ 停等重试后成功。
  test("T2 瞬态拒接（异步 ISE \"Send pending\"＋出向开）⇒ 停等重试后成功——拒接是背压不是周期失败") {
    val fake = new GateFakeWebSocket()
    val sender = new RelayFrameSender(fake, sendTimeoutSec = 2, pendingRetryMillis = 5)
    val calls = new AtomicInteger(0)
    fake.behavior = () =>
      if calls.incrementAndGet() == 1 then
        CompletableFuture.failedFuture(new IllegalStateException("Send pending"))
      else CompletableFuture.completedFuture(fake)
    sender.send("frame").map { _ =>
      assertEquals(fake.callCount, 2, "第一发被拒、重试一发成功")
      assertEquals(fake.aborted, false, "饱和重试面绝不撕会话")
    }
  }

  // T3 同步抛变体（异构 JDK 防御面，javadoc 契约形态）：同判据重试。
  test("T3 同步抛 IllegalStateException＋出向开（防御面）⇒ 同判据：重试后成功") {
    val fake = new GateFakeWebSocket()
    val sender = new RelayFrameSender(fake, sendTimeoutSec = 2, pendingRetryMillis = 5)
    val calls = new AtomicInteger(0)
    fake.behavior = () =>
      if calls.incrementAndGet() == 1 then throw new IllegalStateException("previous send not completed")
      else CompletableFuture.completedFuture(fake)
    sender.send("frame").map { _ =>
      assertEquals(fake.callCount, 2)
      assertEquals(fake.aborted, false)
    }
  }

  // T4 分流边界：ISE 但出向已关 = 真死 ⇒ 立即如实失败，零重试。
  test("T4 异步 ISE＋出向已关 ⇒ 立即如实失败（真死不养），恰好一次调用不重试") {
    val fake = new GateFakeWebSocket(outputClosed0 = true)
    val sender = new RelayFrameSender(fake, sendTimeoutSec = 2, pendingRetryMillis = 5)
    fake.behavior = () => CompletableFuture.failedFuture(new IllegalStateException("Send pending"))
    sender.send("frame").attempt.flatMap {
      case Left(e: IllegalStateException) =>
        IO {
          assertEquals(e.getMessage, "Send pending", "真死面必须原样如实传播")
          assertEquals(fake.callCount, 1, "出向已关的 ISE 不得进重试循环")
          assertEquals(fake.aborted, false, "sender 自身无撕会话权限（abort 属隧道存活机制）")
        }
      case Left(other) => failWith(other, "IllegalStateException")
      case Right(_)    => IO.raiseError(new AssertionError("出向已关的拒接竟被吞掉——分流边界失守"))
    }
  }

  // T5 真写失败（生产逐字形态 IOException "Output closed"）⇒ 立即如实失败。
  test("T5 异步 IOException（真写失败）⇒ 立即如实传播，零重试零吞") {
    val fake = new GateFakeWebSocket()
    val sender = new RelayFrameSender(fake, sendTimeoutSec = 2, pendingRetryMillis = 5)
    fake.behavior = () => CompletableFuture.failedFuture(new IOException("Output closed"))
    sender.send("frame").attempt.flatMap {
      case Left(e: IOException) =>
        IO {
          assertEquals(e.getMessage, "Output closed")
          assertEquals(fake.callCount, 1)
          assertEquals(fake.aborted, false)
        }
      case Left(other) => failWith(other, "IOException")
      case Right(_)    => IO.raiseError(new AssertionError("真写失败竟被吞掉"))
    }
  }

  // T6 排空前驱（W1 毒化面核心）：前驱在飞时后发阻塞等待而非撞位反弹，
  // 前驱完成后按序入队——两个周期都存活（修前：后发立即吃 ISE 死亡）。
  test("T6 排空在飞前驱：前驱完成前不撞位，完成后按序入队——两周期均存活") {
    val fake = new GateFakeWebSocket()
    val sender = new RelayFrameSender(fake, sendTimeoutSec = 5, pendingRetryMillis = 5)
    val f1 = new CompletableFuture[WebSocket]()
    val calls = new AtomicInteger(0)
    fake.behavior = () =>
      if calls.incrementAndGet() == 1 then f1
      else CompletableFuture.completedFuture(fake)
    for
      fibA <- sender.send("A").start
      _ <- IO.sleep(120.millis)
      _ <- IO(assertEquals(fake.callCount, 1, "A 在飞中（夹具前提）"))
      fibB <- sender.send("B").start
      _ <- IO.sleep(200.millis)
      _ <- IO(
        assertEquals(
          fake.callCount,
          1,
          "B 必须阻塞在排空前驱上——修前此形态 B 已撞位吃 ISE 整周期失败（W1 毒化面本体）"
        )
      )
      _ <- IO(f1.complete(fake))
      _ <- awaitFiber(fibA)
      _ <- awaitFiber(fibB)
      _ <- IO(assertEquals(fake.callCount, 2, "B 在前驱完成后才入队（顺序由构造保证）"))
      _ <- IO(assertEquals(fake.aborted, false))
    yield ()
  }

  // T7 排空超时＋记账保持：A 写停摆超时（×5 形态）→ B 排空超时给诚实错误 →
  // A 恢复完成后 C 存活，且 C 的入队发生在 A 完成之后（inFlight 记账未丢）。
  test("T7 停摆→恢复全链：A 诚实超时、B 排空超时、前驱记账保持、C 恢复后存活且不撞位") {
    val fake = new GateFakeWebSocket()
    val sender = new RelayFrameSender(fake, sendTimeoutSec = 1, pendingRetryMillis = 5)
    val f1 = new CompletableFuture[WebSocket]()
    val calls = new AtomicInteger(0)
    fake.behavior = () =>
      if calls.incrementAndGet() == 1 then f1
      else CompletableFuture.completedFuture(fake)
    for
      // A：写预算 1s 内完不成 ⇒ 诚实 TimeoutException（W1 ×5 形态），且 future
      // 仍在飞（get 超时不取消）。
      a <- sender.send("A").attempt
      _ <- a match
        case Left(e: TimeoutException) =>
          IO(assert(e.getMessage.contains("relay frame write did not complete within 1s"), s"A 消息须可读：${e.getMessage}"))
        case Left(other) => failWith(other, "TimeoutException（×5 形态）")
        case Right(_)    => IO.raiseError(new AssertionError("16 字节假写竟 1s 未完？夹具失效"))
      // B：前驱仍在飞 ⇒ 排空预算耗尽 ⇒ 诚实「前驱仍在飞」错误（不得用误导性
      // ISE 连坐）。
      b <- sender.send("B").attempt
      _ <- b match
        case Left(e: TimeoutException) =>
          IO(assert(e.getMessage.contains("previous relay frame still in flight"), s"B 消息须指认前驱：${e.getMessage}"))
        case Left(other) => failWith(other, "TimeoutException（排空超时）")
        case Right(_)    => IO.raiseError(new AssertionError("前驱在飞时 B 竟直接成功——排空语义失守"))
      // 前驱恢复：A 的 wire 写完成。
      completedAt <- IO { val t = System.nanoTime(); f1.complete(fake); t }
      // C：必须先排空已恢复的前驱再入队——C 的 sendText 时刻 ≥ 前驱完成时刻
      // （inFlight 记账保持的判读；若记账被丢，C 会在完成时刻之前先撞位一次）。
      c <- sender.send("C").attempt
      _ <- c match
        case Right(_) =>
          IO {
            val nanos = fake.callNanos.toArray(new Array[java.lang.Long](0))
            assert(nanos(1).longValue() >= completedAt, "C 必须在前驱完成后才入队（记账保持钉）")
          }
        case Left(other) => failWith(other, "成功（恢复后存活）")
      _ <- IO(assertEquals(fake.callCount, 2, "A 一发 + C 一发；B 零 sendText（耗在排空上）"))
      _ <- IO(assertEquals(fake.aborted, false, "全链零撕会话"))
    yield ()
  }

  // T8 拒接持续（占位不释放）⇒ 有界重试后给可读 positionTimeout，连接完好。
  test("T8 拒接持续到预算耗尽 ⇒ 可读 TimeoutException（含最后拒因），有界、零 abort") {
    val fake = new GateFakeWebSocket()
    val sender = new RelayFrameSender(fake, sendTimeoutSec = 1, pendingRetryMillis = 10)
    fake.behavior = () => CompletableFuture.failedFuture(new IllegalStateException("Send pending"))
    sender.send("X").attempt.flatMap {
      case Left(e: TimeoutException) =>
        IO {
          assert(e.getMessage.contains("position not acquired within 1s"), s"消息须可读：${e.getMessage}")
          assert(e.getMessage.contains("Send pending"), "最后拒因须入消息")
          assert(fake.callCount >= 2, "必须有界重试过")
          assertEquals(fake.aborted, false, "预算耗尽也不撕会话")
        }
      case Left(other) => failWith(other, "TimeoutException")
      case Right(_)    => IO.raiseError(new AssertionError("持续拒接竟报成功"))
    }
  }

  // T9 双窗预算：入队（占位）与写完（wire）分窗——入队发生在位置预算尾，
  // 写用全新写预算完成成功。
  test("T9 入队与写完分窗：位置预算尾入队后，写享有全新预算——跨位置 deadline 完成仍成功") {
    val fake = new GateFakeWebSocket()
    val sender = new RelayFrameSender(fake, sendTimeoutSec = 1, pendingRetryMillis = 5)
    val f1 = new CompletableFuture[WebSocket]()
    val f2 = new CompletableFuture[WebSocket]()
    val calls = new AtomicInteger(0)
    fake.behavior = () =>
      val c = calls.incrementAndGet()
      if c == 1 then f1 else f2
    for
      fibA <- sender.send("A").start
      _ <- IO.sleep(100.millis)
      fibB <- sender.send("B").start
      _ <- IO.sleep(500.millis) // t≈0.6s：A 完成（位置预算 1s 的尾段）
      _ <- IO(f1.complete(fake))
      _ <- awaitFiber(fibA)
      _ <- IO.sleep(700.millis) // t≈1.3s：B 的 wire 写完成——已跨位置 deadline（1s）
      _ <- IO(f2.complete(fake))
      _ <- awaitFiber(fibB)
      _ <- IO(assertEquals(fake.aborted, false))
    yield ()
  }

  // ───────────────────── 真 RFC 6455 夹具（对端读延迟可控）─────────────────────

  /**
   * 真 RFC 6455 夹具（只绑 127.0.0.1 临时端口）：真 101 握手后先沉默
   * `readDelayMs`（一个字节不读 ⇒ 客户端大帧写满两端 TCP 缓冲 = 确定性在飞
   * 停摆，W1 ×5 形态），到期后开始持续排空读 ⇒ 停摆恢复（wire 写完成）。
   * NeverReadWsFixture（pump-selfabort P1）的「永不读」与本夹具的「延迟读」
   * 差别仅在恢复轴：本批要钉「停摆→恢复→下一发存活」全链，必须能恢复。
   * 写面纪律：各文件私有、不跨文件复用。
   */
  private final class DelayedReadWsFixture(readDelayMs: Long) extends AutoCloseable:
    private val serverSocket = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    private val clients = new java.util.concurrent.CopyOnWriteArrayList[Socket]()
    @volatile private var stopped = false

    def port: Int = serverSocket.getLocalPort

    private val acceptor: Thread =
      val t = new Thread(
        () =>
          while !stopped do
            try
              val s = serverSocket.accept()
              clients.add(s)
              val h = new Thread(() => serve(s), "portal-cycle-delayed-read")
              h.setDaemon(true)
              h.start()
            catch case _: Throwable => stopped = true,
        "portal-cycle-accept"
      )
      t.setDaemon(true)
      t.start()
      t

    private def serve(s: Socket): Unit =
      try
        val request = readHeaders(s.getInputStream)
        s.getOutputStream.write(handshakeResponse(request).getBytes(StandardCharsets.ISO_8859_1))
        s.getOutputStream.flush()
        // 关键一：先沉默——客户端大帧顶死 TCP 反压，单发位钉死占用（×5 形态）。
        Thread.sleep(readDelayMs)
        // 关键二：到期持续排空读 ⇒ 缓冲释放 ⇒ 客户端在飞写完成（恢复轴）。
        val buf = new Array[Byte](64 * 1024)
        while !stopped do
          val n = s.getInputStream.read(buf)
          if n < 0 then return
      catch case _: Throwable => ()
      finally try s.close() catch case _: Throwable => ()

    private def readHeaders(in: java.io.InputStream): String =
      val sb = new StringBuilder
      var state = 0
      var b = in.read()
      while b >= 0 && state < 4 do
        val c = b.toChar
        sb.append(c)
        state = (state, c) match
          case (0, '\r') => 1
          case (1, '\n') => 2
          case (2, '\r') => 3
          case (3, '\n') => 4
          case _         => 0
        if state < 4 then b = in.read()
      sb.toString

    end readHeaders

    private def handshakeResponse(request: String): String =
      val key = request.linesIterator
        .map(_.trim)
        .find(_.toLowerCase.startsWith("sec-websocket-key:"))
        .map(_.substring(18).trim)
        .getOrElse("")
      val accept = Base64.getEncoder.encodeToString(
        MessageDigest
          .getInstance("SHA-1")
          .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.UTF_8))
      )
      s"HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n"

    def close(): Unit =
      stopped = true
      try serverSocket.close()
      catch case _: Throwable => ()
      clients.forEach(s => try s.close() catch case _: Throwable => ())

  end DelayedReadWsFixture

  /** 真 JDK WebSocket 客户端连到本夹具（与生产侧同栈 java.net.http）。 */
  private def realWsTo(fix: DelayedReadWsFixture): WebSocket =
    val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
    client
      .newWebSocketBuilder()
      .buildAsync(URI.create(s"ws://127.0.0.1:${fix.port}/portal-cycle-pin"), new WebSocket.Listener {})
      .get(10, TimeUnit.SECONDS)

  /** 饱和前提：16MiB 大帧对「沉默期」对端必然写不完（loopback 缓冲 ≪ 16MiB）。 */
  private val Bulk16MiB = "b" * (16 * 1024 * 1024)

  // R1 W1 ×5 形态端到端：写停摆 ⇒ 可读 TimeoutException（不是误导性 ISE）、
  // 连接不撕、恢复后下一发排空前驱并存活。
  test("R1 真 socket 停摆超时钉（W1 ×5 形态）：诚实超时、连接完好、恢复后下一发存活") {
    IO {
      val fix = new DelayedReadWsFixture(readDelayMs = 1200)
      val ws = realWsTo(fix)
      (fix, ws)
    }.flatMap { (fix, ws) =>
      val sender = new RelayFrameSender(ws, sendTimeoutSec = 1, pendingRetryMillis = 25)
      val program =
        for
          first <- sender.send(Bulk16MiB).attempt
          _ <- first match
            case Left(e: TimeoutException) =>
              IO(
                assert(
                  e.getMessage.contains("relay frame write did not complete within 1s"),
                  s"超时消息须可读（W1 ×5 的诚实化）：${e.getMessage}"
                )
              )
            case Left(other) => failWith(other, "TimeoutException（×5 形态）")
            case Right(_)    => IO.raiseError(new AssertionError("16MiB 对 1.2s 沉默对端竟 1s 内写完——夹具失效"))
          _ <- IO(assert(!ws.isOutputClosed, "停摆超时不得撕会话（不撕会话钉，真 socket 面）"))
          second <- sender.send("""{"type":"pong"}""").attempt
          _ <- second match
            case Right(_) => IO.unit
            case Left(other) =>
              failWith(other, "成功（沉默期 1.2s 结束后前驱排空，小帧应存活）")
          _ <- IO(assert(!ws.isOutputClosed, "全链连接完好"))
        yield ()
      program.guarantee(IO { try ws.abort() finally fix.close() })
    }
  }

  // R2 W1 ×4 形态真 JDK 钉：绕锁直发方占位（此处用 16MiB 大帧把占位窗拉长为
  // 确定性沉默期，与心跳 ping 同一位、同一 CAS 面）⇒ 停等重试吸收，整周期存活。
  test("R2 真 socket 撞位重试钉（W1 ×4 形态）：占位期间拒接被停等重试吸收，周期存活") {
    IO {
      val fix = new DelayedReadWsFixture(readDelayMs = 800)
      val ws = realWsTo(fix)
      (fix, ws)
    }.flatMap { (fix, ws) =>
      val sender = new RelayFrameSender(ws, sendTimeoutSec = 5, pendingRetryMillis = 25)
      val program =
        for
          // 绕锁直发方占位（生产 = ping/relay_response 等控制帧；16MiB 使占位
          // 确定性持续到对端 800ms 开读）。
          hold <- IO(ws.sendText(Bulk16MiB, true))
          _ <- IO.sleep(150.millis)
          _ <- IO(assert(!hold.isDone, "夹具前提失效：占位帧竟已完成，撞位形态不成立"))
          res <- sender.send("""{"type":"relay_response"}""").attempt
          _ <- res match
            case Right(_) => IO.unit
            case Left(other) =>
              failWith(other, "成功（拒接必须被重试吸收——修前此发即整周期失败 = W1 ×4 本体）")
          _ <- IO(assert(!ws.isOutputClosed, "撞位重试面零撕会话"))
        yield ()
      program.guarantee(IO { try ws.abort() finally fix.close() })
    }
  }

end NeblinkRelayFrameSenderSendCycleSpec
