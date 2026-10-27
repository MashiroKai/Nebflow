package nebflow.neblink

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.neblink.PortalWireCodec.*

import java.io.BufferedInputStream
import java.net.{InetAddress, ServerSocket, Socket}
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.{Arrays, Base64}
import java.util.concurrent.{CompletableFuture, ConcurrentLinkedQueue}

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * v1.3 WS 分片批 B0 段（契约 2.14；台账 #957）单端 spec —— 下行重组槽先行：
 *
 *  1. `WsReasmBuffer` 纯函数组：顺序重组；seq 违规 / 同消息 enc 混值 / kind
 *     混值 / 超 cap 各自丢环（契约 2.14 流级纪律）；跨消息 seq 连续不重置；
 *     enc=gzip 到齐 = 压缩批落地前显式拒绝（fail-closed）。
 *  2. 桥面组（真 loopback socket）：ws_data_frame 进重组槽 → more=false 完成
 *     → 本地 face 收到逐字重组件；seq 违规 = 整 cycle 丢弃（无部分投递）。
 *     （上行分片 encodeWsUplink / 协商 / 两端口 round-trip = B1 段同文件追加。）
 */
class PortalWsShardSpec extends CatsEffectSuite:

  private val Rid = "0a1b2c3d-4e5f-4678-9abc-def012345678"

  // ────────────────────────── 2. WsReasmBuffer（纯函数） ──────────────────────────

  private def shard(seq: Int, more: Boolean, kind: String = "text", enc: String = "none", data: Array[Byte] = Array.emptyByteArray) =
    WsDataFrame(Rid, seq, more, kind, enc, data)

  /** 断言一次「消息完成」事件（kind + 字节级还原；Array 不进 ==）。 */
  private def assertCompleted(
    result: Either[String, Option[(String, Array[Byte])]],
    kind: String,
    expected: Array[Byte]
  ): Unit =
    result match
      case Right(Some((k, bytes))) =>
        assertEquals(k, kind)
        assert(Arrays.equals(bytes, expected), s"重组字节漂移：${bytes.mkString(",")} vs ${expected.mkString(",")}")
      case other => fail(s"expected completion, got: $other")

  test("WsReasmBuffer: ordered feed completes with the exact concatenation; seq keeps rising across messages"):
    val buf = new PortalTunnelBridge.WsReasmBuffer(1024)
    assertEquals(buf.feed(shard(0, more = true, data = "hel".getBytes(UTF_8))), Right(None))
    assertCompleted(buf.feed(shard(1, more = false, data = "lo".getBytes(UTF_8))), "text", "hello".getBytes(UTF_8))
    // 下一条消息：seq 从 2 继续（契约 2.14：more=false 是消息边界而非 seq 重置点）。
    assertCompleted(buf.feed(shard(2, more = false, data = "!".getBytes(UTF_8))), "text", "!".getBytes(UTF_8))

  test("WsReasmBuffer: seq regression/skip is a violation"):
    val buf = new PortalTunnelBridge.WsReasmBuffer(1024)
    assert(buf.feed(shard(0, more = true)).isRight)
    assert(buf.feed(shard(2, more = false)).isLeft, "跳片 = violation")
    val buf2 = new PortalTunnelBridge.WsReasmBuffer(1024)
    assert(buf2.feed(shard(1, more = false)).isLeft, "首片必须 seq=0")
    val buf3 = new PortalTunnelBridge.WsReasmBuffer(1024)
    assert(buf3.feed(shard(-1, more = false)).isLeft, "负 seq 在 codec 已拒；槽内再拒一道")

  test("WsReasmBuffer: same-message enc/kind mismatch is a violation"):
    val buf = new PortalTunnelBridge.WsReasmBuffer(1024)
    assert(buf.feed(shard(0, more = true, enc = "none")).isRight)
    assert(buf.feed(shard(1, more = false, enc = "gzip")).isLeft, "同消息 enc 混值 = violation（契约 2.14）")
    val buf2 = new PortalTunnelBridge.WsReasmBuffer(1024)
    assert(buf2.feed(shard(0, more = true, kind = "text")).isRight)
    assert(buf2.feed(shard(1, more = false, kind = "binary")).isLeft, "同消息 kind 混值 = violation")

  test("WsReasmBuffer: accumulation over cap is a violation (cap = loopback message cap, #955 pairing)"):
    val buf = new PortalTunnelBridge.WsReasmBuffer(10)
    assert(buf.feed(shard(0, more = true, data = Array.fill[Byte](6)(1))).isRight)
    assert(buf.feed(shard(1, more = true, data = Array.fill[Byte](6)(2))).isLeft, "6+6 > 10 ⇒ 超 cap 丢环")
    // 恰好等于 cap 可完成。
    val buf2 = new PortalTunnelBridge.WsReasmBuffer(10)
    assertEquals(buf2.feed(shard(0, more = true, data = Array.fill[Byte](10)(1))), Right(None))
    assertCompleted(buf2.feed(shard(1, more = false)), "text", Array.fill[Byte](10)(1))

  test("WsReasmBuffer: a completed enc=gzip message is rejected until the compression batch lands"):
    val buf = new PortalTunnelBridge.WsReasmBuffer(1024)
    assert(buf.feed(shard(0, more = true, enc = "gzip", data = Array.fill[Byte](3)(9))).isRight)
    assert(
      buf.feed(shard(1, more = false, enc = "gzip")).isLeft,
      "fail-closed：未接解压器绝不把 gzip 原字节投给本地 face"
    )

  // ────────────────────────── 3. 桥面组（真 loopback socket） ──────────────────────────

  /** 既有 FakeWebSocket 同款（记录上行帧文本）。 */
  private final class FakeWebSocket extends java.net.http.WebSocket:
    val sent      = new ConcurrentLinkedQueue[String]()
    @volatile var aborted = false
    override def sendText(data: CharSequence, last: Boolean) =
      sent.add(data.toString); CompletableFuture.completedFuture(this)
    override def sendBinary(data: java.nio.ByteBuffer, last: Boolean) = CompletableFuture.completedFuture(this)
    override def sendClose(code: Int, reason: String)                 = CompletableFuture.completedFuture(this)
    override def request(n: Long): Unit                               = ()
    override def sendPing(data: java.nio.ByteBuffer)                  = CompletableFuture.completedFuture(this)
    override def sendPong(data: java.nio.ByteBuffer)                  = CompletableFuture.completedFuture(this)
    override def getSubprotocol: String                               = ""
    override def isOutputClosed: Boolean                              = false
    override def isInputClosed: Boolean                               = false
    override def abort(): Unit                                        = aborted = true

  private def sentTexts(fake: FakeWebSocket): List[String] =
    val out = scala.collection.mutable.ListBuffer.empty[String]
    fake.sent.forEach(out += _)
    out.toList

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

  private def mkDeps(faces: PortalHostFaces): PortalTunnelDeps =
    new PortalTunnelDeps(
      faces,
      ConsoleTicketStore.unsafeCreate(),
      PortalBindingState.unsafeCreate(),
      IO.pure(DeviceIdentity("qa-device", "qa-host", "macos")),
      IO.pure(0)
    )

  /**
   * 真 loopback WS server（带读回能力）：升级后先写下发载荷（可分 2 个 RFC 帧），
   * 然后逐条记录引擎回送的**重组后整消息**文本（掩码读 + RFC 分帧重组）。
   */
  private final class LoopbackCapture(message: Option[Array[Byte]]) extends AutoCloseable:
    val received     = new ConcurrentLinkedQueue[String]()
    /** 诊断读数：下发载荷写完成/写异常/读循环帧数。 */
    @volatile var payloadWritten = false
    @volatile var payloadWriteError: String = null
    @volatile var framesRead = 0
    private val server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val sockets = new ConcurrentLinkedQueue[Socket]()
    @volatile private var stopped = false
    private val acceptor = new Thread(() =>
      while !stopped do
        try
          val sock = server.accept()
          sockets.add(sock)
          new Thread(() => serve(sock)).start()
        catch case _: Exception => ()
    )
    acceptor.setDaemon(true)
    acceptor.start()

    def port: Int = server.getLocalPort

    /** 分叉试验用：握手完成后补推下发载荷（存住 serve 的 out）。 */
    @volatile private var liveOut: java.io.OutputStream = null
    def pushPayload(bytes: Array[Byte]): Unit =
      Option(liveOut).foreach { out =>
        try
          writeServerFrame(out, fin = true, opcode = 0x1, bytes)
          payloadWritten = true
        catch case e: Exception => payloadWriteError = s"push: ${e.getClass.getSimpleName}: ${e.getMessage}"
      }

    private def serve(sock: Socket): Unit =
      try
        liveOut = sock.getOutputStream
        val reader = new BufferedInputStream(sock.getInputStream)
        val head   = scala.collection.mutable.ListBuffer.empty[String]
        var line: String = readLine(reader)
        while line != null && line.nonEmpty do
          head += line
          line = readLine(reader)
        val key = head
          .collectFirst { case l if l.toLowerCase(java.util.Locale.ROOT).startsWith("sec-websocket-key:") =>
            l.dropWhile(_ != ':').drop(1).trim
          }
          .getOrElse("")
        val accept = Base64.getEncoder.encodeToString(
          MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(UTF_8))
        )
        val resp =
          "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            s"Sec-WebSocket-Accept: $accept\r\n\r\n"
        val out = sock.getOutputStream
        out.write(resp.getBytes(UTF_8))
        out.flush()
        message.foreach { bytes =>
          // 单帧大消息（引擎侧 JDK listener 会先缓冲到 request(n) 之后再投递）。
          try
            writeServerFrame(out, fin = true, opcode = 0x1, bytes)
            payloadWritten = true
          catch case e: Exception => payloadWriteError = s"${e.getClass.getSimpleName}: ${e.getMessage}"
        }
        // 记录引擎回送的重组后消息：掩码读 + 分帧重组到 fin。重组纪律同
        // PairingRelay：opcode 只在非 continuation 帧（低 4 位非零）上钉定。
        var acc = new StringBuilder
        var msgOpcode = 0
        var done = false
        while !stopped && !done do
          readFrame(reader) match
            case None => done = true
            case Some((op, payload)) =>
              framesRead += 1
              if (op & 0x0f) != 0 then msgOpcode = op & 0x0f
              acc.append(new String(payload, UTF_8))
              if (op & 0x80) != 0 then
                if msgOpcode == 0x1 then received.add(acc.toString)
                acc = new StringBuilder
                msgOpcode = 0
      catch case e: Exception =>
        if payloadWriteError == null then payloadWriteError = s"serve: ${e.getClass.getSimpleName}: ${e.getMessage}"

    private def readLine(in: BufferedInputStream): String =
      val sb = new StringBuilder
      var prev = -1
      var b = in.read()
      while b >= 0 do
        if prev == 13 && b == 10 then return sb.dropRight(1).toString
        sb.append(b.toChar)
        prev = b
        b = in.read()
      if sb.isEmpty then null else sb.toString

    /** RFC 6455 帧读（客户端掩码 + 7/16/64 位长度），返回 (首字节, 已解掩码载荷)。 */
    private def readFrame(in: BufferedInputStream): Option[(Int, Array[Byte])] =
      val h = in.readNBytes(2)
      if h.length < 2 then None
      else
        val first  = h(0) & 0xff
        val masked = (h(1) & 0x80) != 0
        var len    = (h(1) & 0x7f).toLong
        if len == 126L then
          val e = in.readNBytes(2)
          if e.length < 2 then return None
          len = ((e(0) & 0xff) << 8 | (e(1) & 0xff)).toLong
        else if len == 127L then
          val e = in.readNBytes(8)
          if e.length < 8 then return None
          len = (0 until 8).foldLeft(0L)((acc, i) => (acc << 8) | (e(i) & 0xff).toLong)
        val mask = if masked then in.readNBytes(4) else Array.emptyByteArray
        val payload = if len > 0 then in.readNBytes(len.toInt) else Array.emptyByteArray
        if payload.length < len then None
        else
          if masked && mask.length == 4 then
            var i = 0
            while i < payload.length do
              payload(i) = (payload(i) ^ mask(i % 4)).toByte
              i += 1
          Some((first, payload))

    private def writeServerFrame(out: java.io.OutputStream, fin: Boolean, opcode: Int, data: Array[Byte]): Unit =
      out.write(((if fin then 0x80 else 0) | (opcode & 0x0f)).toByte)
      val len = data.length
      if len <= 125 then out.write(len.toByte)
      else if len <= 0xffff then
        out.write(126.toByte)
        out.write((len >>> 8) & 0xff)
        out.write(len & 0xff)
      else
        out.write(127.toByte)
        var i = 7
        while i >= 0 do
          out.write(((len.toLong >>> (8 * i)) & 0xffL).toByte)
          i -= 1
      out.write(data)
      out.flush()

    def close(): Unit =
      stopped = true
      try server.close()
      catch case _: Exception => ()
      sockets.forEach(s => try s.close() catch case _: Exception => ())
  end LoopbackCapture

  /** 上行 bridge 装配：capture 作 loopback 面，FakeWebSocket 收上行帧。 */
  test("bridge downlink reasm: ws_data_frame shards reassemble and the local face receives the exact message"):
    // 下发面为空（本测只驱动下行）：引擎 connect 后 idle 挂着，我们直接把
    // ws_data_frame 帧序 enqueue 进桥（mailbox 单消费 → 重组槽 → toLocal →
    // forwardToLocal），capture 读回**重组后整消息**。
    val message = "reasm-target-配置😀-tail".getBytes(UTF_8)
    val capture = new LoopbackCapture(None)
    Dispatcher.parallel[IO]
      .use { _ =>
        val faces = new PortalHostFaces(gatewayPort = capture.port)
        val deps  = mkDeps(faces)
        val fake  = new FakeWebSocket
        val bridge = new PortalTunnelBridge(deps, new RelayFrameSender(fake))
        for
          fiber <- bridge.run.start
          _ = bridge.enqueueFrame(WsOpen(Rid, "/ws", None, None))
          // 等 cycle 建好（ws_open_result 上行即注册完成）。
          opened <- waitUntil(10.seconds)(
                      IO(sentTexts(fake).exists(_.contains("ws_open_result")))
                    )
          _ = assert(opened, "loopback 升级必须成功")
          // 三片下行：2+2 终结；再一条单帧消息（seq 连续到 3）。
          _ = bridge.enqueueFrame(WsDataFrame(Rid, 0, more = true, "text", "none", "he".getBytes(UTF_8)))
          _ = bridge.enqueueFrame(WsDataFrame(Rid, 1, more = false, "text", "none", "llo".getBytes(UTF_8)))
          _ = bridge.enqueueFrame(WsDataFrame(Rid, 2, more = false, "text", "none", "!!".getBytes(UTF_8)))
          got <- waitUntil(10.seconds)(
                   IO(capture.received.stream().anyMatch(m => m == "hello" || m == "hello!!"))
                 )
          _ = assert(got, s"本地 face 必须收到重组消息；got=${capture.received.stream().toList()}")
          both <- waitUntil(10.seconds)(IO {
                    val l = capture.received.stream().toList()
                    l.contains("hello") && l.contains("!!")
                  })
          _ = assert(both, s"两条消息都必须重组投递（seq 连续）；got=${capture.received.stream().toList()}")
          _ <- bridge.teardown() *> fiber.cancel
        yield ()
      }
      .guarantee(IO(capture.close()))

  test("bridge downlink violation: a skipped seq drops the WHOLE cycle (no partial delivery)"):
    val capture = new LoopbackCapture(None)
    Dispatcher.parallel[IO]
      .use { _ =>
        val faces = new PortalHostFaces(gatewayPort = capture.port)
        val deps  = mkDeps(faces)
        val fake  = new FakeWebSocket
        val bridge = new PortalTunnelBridge(deps, new RelayFrameSender(fake))
        for
          fiber <- bridge.run.start
          _ = bridge.enqueueFrame(WsOpen(Rid, "/ws", None, None))
          opened <- waitUntil(10.seconds)(IO(sentTexts(fake).exists(_.contains("ws_open_result"))))
          _ = assert(opened)
          // seq 跳 0→2：整 cycle 丢弃（契约 2.14 流级纪律），后续帧也无 cycle 可进。
          _ = bridge.enqueueFrame(WsDataFrame(Rid, 2, more = false, "text", "none", "xx".getBytes(UTF_8)))
          _ <- IO.sleep(500.millis)
          _ = assert(capture.received.isEmpty, s"violation 不得投递任何部分消息；got=${capture.received.stream().toList()}")
          // cycle 已出注册表：再来的同 requestId 帧走 no-open-cycle 丢弃（不炸桥）。
          _ = bridge.enqueueFrame(WsDataFrame(Rid, 3, more = false, "text", "none", "yy".getBytes(UTF_8)))
          _ <- IO.sleep(300.millis)
          mint = ConsoleTicketMint("1f2e3d4c-5b6a-4978-8877-6a5b4c3d2e1f", "1f2e3d4c-5b6a-4978-8877-6a5b4c3d2e1f", "logto|u", "portal")
          _ = bridge.enqueueFrame(mint)
          alive <- waitUntil(10.seconds)(IO(sentTexts(fake).exists(_.contains("ConsoleTicketResult"))))
          _ = assert(alive, "violation 丢环后 mailbox 必须保持存活")
          _ <- bridge.teardown() *> fiber.cancel
        yield ()
      }
      .guarantee(IO(capture.close()))

end PortalWsShardSpec
