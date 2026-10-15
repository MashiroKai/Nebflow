package nebflow.neblink

import cats.effect.{Deferred, IO}
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.shared.PathUtil

import java.net.{InetAddress, ServerSocket, Socket, URI}
import java.net.http.{HttpClient, WebSocket}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.{CompletableFuture, ConcurrentLinkedQueue, TimeUnit}
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
 * pump-selfabort-fix（2026-10-15 残余批）新增段 P1–P4：上批 D1 的四条全走假 socket，
 * 而本批根因恰恰藏在**假 socket 够不着的 JDK 真行为**里——Temurin 23
 * `WebSocketImpl.sendText` 对「前一发未完成」**从不同步抛** IllegalStateException
 * （javadoc 契约说抛，实现却返回**已完成的失败 future**，源码
 * `pendingTextOrBinary.compareAndSet` 失败分支 `failedFuture(new
 * IllegalStateException("Send pending"))`）⇒ D1 的「入队成功 ⇒ 异步失败 = 真死」
 * 分支在 bulk 洪峰下成了新误杀面（A4-R2 残余族：`ping write failed async —
 * aborting broken connection: Send pending…` ×20 / 3min 窗，5 次 2–5s 自愈重连）。
 * 本段以真 RFC 6455 夹具钉死：
 *   P1 真 socket 契约钉（根因证据）：bulk 在飞时 ping `sendText` 零同步异常、
 *      future 携 `IllegalStateException("Send pending")` 失败、`isOutputClosed()==false`；
 *   P2 真 socket 修复行为钉：同一形态走 `sendHeartbeatPing` ⇒ 跳拍不撕、
 *      `lastPingSentAt` 回滚（D3 的「问出去过」前提不被伪造）；
 *   P3 分流边界（假 socket）：异步 ISE 但出向已关 ⇒ 真死仍 abort；
 *   P4 真死仍 abort（假 socket，生产第二行逐字形态 `IOException("Output closed")`）。
 *
 * 真 socket 夹具只绑 127.0.0.1 临时端口；不起任何实例、不触固定端口。
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

  /**
   * 真 RFC 6455 夹具（只绑 127.0.0.1 临时端口）：完成真 101 握手后**永不读**对端
   * socket ⇒ 客户端大帧写满两端 TCP 缓冲后必然 stall 在「发送中」——JDK 的
   * `pendingTextOrBinary` 单发位恒被占用，正是 bulk 洪峰下心跳撞车的确定性形态。
   *
   * 与 `NeblinkPresenceHeartbeatLifecycleSpec` 的 `DeafWsFixture`（聋但**排空**读）
   * 的关键差异：聋夹具把对端字节读掉，缓冲永不填满，大帧写得完 ⇒ 造不出饱和；
   * 本夹具偏偏**一个字节都不读**（连 close 帧都不收）⇒ TCP 反压 ⇒ 在飞发送永不
   * 完成 ⇒ 单发位被钉死占用。同款写面纪律：各文件私有、不跨文件复用。
   */
  private final class NeverReadWsFixture extends AutoCloseable:
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
              val h = new Thread(() => serve(s), "pump-selfabort-never-read")
              h.setDaemon(true)
              h.start()
            catch case _: Throwable => stopped = true,
        "pump-selfabort-accept"
      )
      t.setDaemon(true)
      t.start()
      t

    private def serve(s: Socket): Unit =
      try
        val request = readHeaders(s.getInputStream)
        s.getOutputStream.write(handshakeResponse(request).getBytes(StandardCharsets.ISO_8859_1))
        s.getOutputStream.flush()
        // 关键：握手后一个字节都不读 —— 客户端的 bulk 大帧填满 TCP 缓冲后，
        // 其 JDK transport 停在「写不动」的在飞态，pendingTextOrBinary 恒真。
        while !stopped do Thread.sleep(200)
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

  end NeverReadWsFixture

  /** 真 JDK WebSocket 客户端连到本夹具（与生产侧同栈 java.net.http）。 */
  private def realWsTo(fix: NeverReadWsFixture): WebSocket =
    val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
    client
      .newWebSocketBuilder()
      .buildAsync(URI.create(s"ws://127.0.0.1:${fix.port}/qa-contract-pin"), new WebSocket.Listener {})
      .get(10, TimeUnit.SECONDS)

  /** 饱和前提：16MiB 大帧对「永不读」的对端必然写不完（loopback 缓冲 ≪ 16MiB）。 */
  private val BulkFloodPayload = "b" * (16 * 1024 * 1024)

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

  // ===== pump-selfabort-fix（2026-10-15 残余批）：异步失败面的真含义分流 =====

  test("P1 真 socket 契约钉（根因证据）：bulk 在飞时 ping sendText 零同步异常、future 携 ISE(\"Send pending\") 失败、出向完好") {
    IO.blocking {
      val fix = new NeverReadWsFixture()
      try
        val ws = realWsTo(fix)
        try
          // 复刻生产撞车形态：bulk 大帧占住 JDK 单发位（RelayFrameSender 持锁跨
          // future.get(15s)，慢 WAN 上每帧 0.4–13s —— 本夹具用「对端不读」把这
          // 0.4–13s 拉长成确定性的「永不完成」）。
          val bulkFut = ws.sendText(BulkFloodPayload, true)
          Thread.sleep(600) // 让 transport 真正进入「写不动」的在飞态
          assert(!bulkFut.isDone, "夹具前提失效：bulk 大帧竟已写完（对端没顶住 TCP 反压），饱和形态不成立")
          // ↓ 修前 D1 的假设在这里就错了：本 JDK 对「Send pending」**不同步抛**。
          //   若此行真的抛出 ISE，测试即红 —— 那等于证伪本批根因。
          val pingFut = ws.sendText("""{"type":"ping"}""", true)
          assert(pingFut.isCompletedExceptionally, "「Send pending」拒绝必须落在返回的 future 里（本 JDK 不同步抛）")
          val err =
            try
              pingFut.get(1, TimeUnit.SECONDS)
              None
            catch case e: java.util.concurrent.ExecutionException => Option(e.getCause)
          err match
            case Some(t: IllegalStateException) =>
              assertEquals(
                t.getMessage,
                "Send pending",
                "失败消息必须是 JDK 单发位的饱和拒绝（Send pending），而非真写失败"
              )
            case Some(other) =>
              fail(s"future 携带非 ISE 异常 = 真失败面，本批根因判定将不成立：${other.getClass.getName}: $other")
            case None => fail("ping future 竟然成功完成 —— 单发位没被 bulk 占住，夹具失效")
          assertEquals(
            ws.isOutputClosed,
            false,
            "出向必须完好 —— 连接没死；D1 正是把这一拍读成真死才 abort（残余族根因本体）"
          )
          assert(!bulkFut.isDone, "bulk 大帧仍在飞（饱和持续，与生产洪峰同形态）")
        finally ws.abort()
      finally fix.close()
    }
  }

  test("P2 修复行为钉（真 socket 端到端）：饱和跳拍不撕 —— 不 abort、lastPingSentAt 回滚（D3 前提不被伪造）") {
    withTunnel { (tunnel, closed) =>
      IO.blocking {
        val fix = new NeverReadWsFixture()
        try
          val ws = realWsTo(fix)
          try
            val bulkFut = ws.sendText(BulkFloodPayload, true)
            Thread.sleep(600)
            assert(!bulkFut.isDone, "夹具前提失效：bulk 大帧竟已写完，饱和形态不成立")
            val prevAskedAt = tunnel.lastPingSentAt.get()
            // 修前（D1 异步分支原样）：此拍必 abort —— 即 A4-R2 残余族的产生点。
            tunnel.sendHeartbeatPing(ws, closed)
            // 给「假如错误 abort」一条显化窗口：abort 立即置 outputClosed=true 并
            // 令在飞 bulk future 异常完成 —— 300ms 足够任何异步路径暴露。
            Thread.sleep(300)
            assertEquals(ws.isOutputClosed, false, "饱和跳拍必须不撕连接（修前此拍即 abort = 残余族根因）")
            assert(!bulkFut.isDone, "bulk 在飞发送必须毫发无损（连接未撕的直接见证）")
            assertEquals(
              tunnel.lastPingSentAt.get(),
              prevAskedAt,
              "跳拍必须回滚 lastPingSentAt —— 这一拍没上线，不得伪造 D3 的「问出去过」前提"
            )
          finally ws.abort()
        finally fix.close()
      }
    }
  }

  test("P3 分流边界（假 socket）：异步 ISE 但出向已关 ⇒ 真死，仍 abort") {
    withTunnel { (tunnel, closed) =>
      val fake = new FakeWebSocket(asyncFail = Some(new IllegalStateException("Send pending")), outputClosed = true)
      IO(tunnel.sendHeartbeatPing(fake, closed)).map { _ =>
        assertEquals(
          fake.aborted,
          true,
          "饱和判据以「出向开」为前提：出向已关时即便 ISE 也必须当真死 abort（不得把死连接当饱和养着）"
        )
      }
    }
  }

  test("P4 真死仍 abort（假 socket，生产第二行逐字形态：IOException \"Output closed\"）") {
    withTunnel { (tunnel, closed) =>
      val fake = new FakeWebSocket(asyncFail = Some(new java.io.IOException("Output closed")))
      IO(tunnel.sendHeartbeatPing(fake, closed)).map { _ =>
        assertEquals(
          fake.aborted,
          true,
          "IOException 族 = 真写失败面，必须 abort（残余族日志第二行 01:27:15.097「Output clos…」的判别）"
        )
      }
    }
  }

end NeblinkRelayTunnelHeartbeatSendSpec
