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
 * v1.3 WS 分片批（契约 2.14/2.15；台账 #957/#958）单端 spec —— 三组判据面：
 *
 *  1. `encodeWsUplink` 纯函数组：非 v2 = 单条 v1 ws_frame（fin=true，字节级
 *     原路）；v2 = seq 0..n-1 / 末片 more=false / enc=none / 切片按 raw 字节 /
 *     空消息 = 单条空终结帧 / 恰整除无空尾片；片字符数远超控制车道阈值（bulk
 *     车道，与 body_frame 同档）。
 *  2. `WsReasmBuffer` 纯函数组：顺序重组；seq 违规 / 同消息 enc 混值 / kind
 *     混值 / 超 cap 各自丢环（契约 2.14 流级纪律）；跨消息 seq 连续不重置；
 *     enc=gzip 到齐 = 压缩批落地前显式拒绝（fail-closed）。
 *  3. 桥面组（真 loopback socket）：v2 周期上行 = listener 整消息 → pump 逐片
 *     过单发位（真 socket 上收到的就是分片帧序）；下行 = ws_data_frame 进重组
 *     槽 → more=false 完成 → 本地 face 收到逐字重组件；非 v2 回落整帧。
 */
class PortalWsShardSpec extends CatsEffectSuite:

  private val Rid = "0a1b2c3d-4e5f-4678-9abc-def012345678"

  // ────────────────────────── 1. encodeWsUplink（纯函数） ──────────────────────────

  test("encodeWsUplink non-v2: exactly one v1 ws_frame, fin=true, byte-exact (fallback golden)"):
    val bytes = "hello world".getBytes(UTF_8)
    val texts = PortalTunnelBridge.encodeWsUplink(portalV2 = false, Rid, "text", bytes)
    assertEquals(texts.length, 1)
    PortalWireCodec.decode(texts.head) match
      case Right(WsFrame(rid, kind, data, fin)) =>
        assertEquals(rid, Rid)
        assertEquals(kind, "text")
        assert(Arrays.equals(data, bytes), "fallback must carry the message byte-exactly")
        assert(fin, "the v1 face is a whole message: fin=true")
      case other => fail(s"expected a v1 ws_frame, got: $other")

  test("encodeWsUplink v2 small message: single ws_data_frame seq=0 more=false enc=none"):
    val bytes = "小消息".getBytes(UTF_8)
    val texts = PortalTunnelBridge.encodeWsUplink(portalV2 = true, Rid, "text", bytes)
    assertEquals(texts.length, 1)
    PortalWireCodec.decode(texts.head) match
      case Right(f: WsDataFrame) =>
        assertEquals((f.requestId, f.seq, f.more, f.kind, f.enc), (Rid, 0, false, "text", "none"))
        assert(Arrays.equals(f.data, bytes))
      case other => fail(s"expected ws_data_frame, got: $other")

  test("encodeWsUplink v2 large message: seq 0..n-1, more true*(n-1)+false, concat byte-exact, tail short"):
    val bytes = Array.fill[Byte](PortalTunnelBridge.WsShardBytes * 2 + 1234)(7)
    val texts = PortalTunnelBridge.encodeWsUplink(portalV2 = true, Rid, "binary", bytes)
    assertEquals(texts.length, 3)
    val frames = texts.map(t => PortalWireCodec.decode(t).toOption.get.asInstanceOf[WsDataFrame])
    assertEquals(frames.map(_.seq).toList, List(0, 1, 2))
    assertEquals(frames.map(_.more).toList, List(true, true, false))
    assert(frames.forall(_.enc == "none"), "压缩批之前 enc 恒 none（契约 2.14 枚举，B2 才启用 gzip）")
    assert(frames.forall(_.kind == "binary"))
    val concat = frames.foldLeft(Array.emptyByteArray)(_ ++ _.data)
    assert(Arrays.equals(concat, bytes), "切片拼接必须字节级还原")
    assertEquals(frames(0).data.length, PortalTunnelBridge.WsShardBytes, "首片必须满片")
    assertEquals(frames(2).data.length, 1234, "尾片为余数")
    assertEquals(frames(1).data.length, PortalTunnelBridge.WsShardBytes, "中片必须满片")

  test("encodeWsUplink v2 empty message: one empty more=false terminator (no zero-shard face)"):
    val texts = PortalTunnelBridge.encodeWsUplink(portalV2 = true, Rid, "text", Array.emptyByteArray)
    assertEquals(texts.length, 1)
    PortalWireCodec.decode(texts.head) match
      case Right(f: WsDataFrame) =>
        assertEquals((f.seq, f.more), (0, false))
        assert(f.data.isEmpty, "空消息 = 单条空终结帧（契约 2.14）")
      case other => fail(s"expected terminator, got: $other")

  test("encodeWsUplink v2 exact multiple of the shard size: no empty tail shard"):
    val bytes = Array.fill[Byte](PortalTunnelBridge.WsShardBytes * 2)(3)
    val texts = PortalTunnelBridge.encodeWsUplink(portalV2 = true, Rid, "text", bytes)
    assertEquals(texts.length, 2, "恰整除 ⇒ 2 片、无空尾片")
    val frames = texts.map(t => PortalWireCodec.decode(t).toOption.get.asInstanceOf[WsDataFrame])
    assertEquals(frames.map(_.more).toList, List(true, false))
    assert(frames.forall(_.data.length == PortalTunnelBridge.WsShardBytes))

  test("encodeWsUplink v2 shards sit in the bulk lane (base64 far above the control-lane threshold)"):
    val bytes = Array.fill[Byte](PortalTunnelBridge.WsShardBytes)(5)
    val texts = PortalTunnelBridge.encodeWsUplink(portalV2 = true, Rid, "text", bytes)
    assert(
      texts.forall(_.length > RelayFrameSender.ControlLaneMaxChars),
      "满片 base64 ≈349K 字符必须远离 64KiB 控制车道阈值（bulk 车道，设计 §7）"
    )

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
  private def withUplinkBridge[A](portalV2: Boolean, payload: Array[Byte])(
    body: (PortalTunnelBridge, FakeWebSocket) => IO[A]
  ): IO[A] =
    val capture = new LoopbackCapture(Some(payload))
    Dispatcher.parallel[IO]
      .use { _ =>
        val faces = new PortalHostFaces(gatewayPort = capture.port)
        val deps  = mkDeps(faces)
        val fake  = new FakeWebSocket
        val bridge = new PortalTunnelBridge(deps, new RelayFrameSender(fake), portalV2)
        for
          fiber <- bridge.run.start
          _ = bridge.enqueueFrame(WsOpen(Rid, "/ws", None, None))
          out <- body(bridge, fake).guarantee(bridge.teardown() *> fiber.cancel)
        yield out
      }
      .guarantee(IO(capture.close()))

  private def shardFramesOf(fake: FakeWebSocket): List[WsDataFrame] =
    sentTexts(fake).flatMap(t => PortalWireCodec.decode(t).toOption).collect { case f: WsDataFrame => f }

  test("bridge uplink v2: a large local message leaves the tunnel as ordered ws_data_frame shards (single fin point at the end)"):
    val payload = ("配置😀" * 40000).getBytes(UTF_8) // ≈1.6MB ⇒ 多片
    withUplinkBridge(portalV2 = true, payload) { (_, fake) =>
      for
        ok <- waitUntil(15.seconds)(
                IO(shardFramesOf(fake).lastOption.exists(!_.more))
              )
        _ = assert(ok, s"分片流未终结；sent=${sentTexts(fake).map(_.take(80))}")
        frames = shardFramesOf(fake)
        _ = assert(frames.size >= 2, s"大消息必须切多片；got ${frames.size}")
        _ = assertEquals(frames.map(_.seq).toList, (0 until frames.size).toList, "seq 0..n-1 严格 +1")
        _ = assertEquals(frames.map(_.more).toList, (0 until frames.size).map(_ < frames.size - 1).toList)
        _ = assert(frames.forall(_.enc == "none"))
        _ = assert(frames.forall(_.kind == "text"))
        _ = assert(Arrays.equals(frames.foldLeft(Array.emptyByteArray)(_ ++ _.data), payload), "分片必须字节级还原")
        // 契约 2.14：该 cycle 的应用消息一律 ws_data_frame —— 无 ws_frame 混流。
        _ = assert(
              sentTexts(fake).flatMap(t => PortalWireCodec.decode(t).toOption).forall {
                case _: WsDataFrame => true
                case _: WsOpenResult => true
                case other => fail(s"v2 周期上行出现非分片族帧：$other")
              },
              "v2 周期不得混入 ws_frame 族（同帧族替代）"
            )
      yield ()
    }

  test("bridge uplink non-v2 fallback: the same message leaves as exactly one v1 ws_frame (byte-identical face)"):
    val payload = ("fallback-golden-" * 100).getBytes(UTF_8)
    withUplinkBridge(portalV2 = false, payload) { (_, fake) =>
      for
        ok <- waitUntil(10.seconds)(
                IO(sentTexts(fake).exists(t => PortalWireCodec.decode(t).toOption.exists(_.isInstanceOf[WsFrame])))
              )
        _ = assert(ok, "非 v2 周期必须原路上行 ws_frame")
        frames = sentTexts(fake).flatMap(t => PortalWireCodec.decode(t).toOption).collect { case f: WsFrame => f }
        _ = assertEquals(frames.length, 1, "回落面 = 单条整帧")
        _ = assert(frames.head.fin, "整帧 fin=true")
        _ = assert(Arrays.equals(frames.head.data, payload), "回落载荷字节级不变")
        _ = assert(shardFramesOf(fake).isEmpty, "非 v2 周期零 ws_data_frame")
      yield ()
    }

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
        val bridge = new PortalTunnelBridge(deps, new RelayFrameSender(fake), portalV2 = false)
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
        val bridge = new PortalTunnelBridge(deps, new RelayFrameSender(fake), portalV2 = false)
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

  // ────────────────────────── 4. 两端口对发大帧（真 socket 配对自测） ──────────────────────────

  /**
   * relay 替身（raw socket）：①升级时可 echo subprotocol（协商格）；②把收到的
   * 引擎帧**原样回授**（上行分片 → 下行重组的回路面）；③记录引擎上行帧原文。
   * 掩码读（JDK 客户端帧）+ 非掩码写（服务端帧）。
   */
  private final class PairingRelay(echoSubprotocol: Boolean, echoFrames: Boolean) extends AutoCloseable:
    val clientFrames = new ConcurrentLinkedQueue[String]()
    /** 诊断读数：读循环帧数 + serve 异常（外层吞异常会无声死读循环）。 */
    @volatile var framesRead = 0
    @volatile var serveError: String = null
    val frameTrace = new ConcurrentLinkedQueue[String]()
    private val server  = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
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

    private def serve(sock: Socket): Unit =
      try
        sock.setSoTimeout(0)
        val reader = new BufferedInputStream(sock.getInputStream)
        val head   = scala.collection.mutable.ListBuffer.empty[String]
        var line: String = readLine(reader)
        while line != null && line.nonEmpty do
          head += line
          line = readLine(reader)
        val offered = head
          .collectFirst { case l if l.toLowerCase(java.util.Locale.ROOT).startsWith("sec-websocket-protocol:") =>
            l.dropWhile(_ != ':').drop(1).trim.split(",").map(_.trim).filter(_.nonEmpty).toList
          }
          .getOrElse(Nil)
        val key = head
          .collectFirst { case l if l.toLowerCase(java.util.Locale.ROOT).startsWith("sec-websocket-key:") =>
            l.dropWhile(_ != ':').drop(1).trim
          }
          .getOrElse("")
        val accept = Base64.getEncoder.encodeToString(
          MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(UTF_8))
        )
        val out = sock.getOutputStream
        val selected =
          if echoSubprotocol && offered.nonEmpty then s"Sec-WebSocket-Protocol: ${offered.head}\r\n" else ""
        out.write(
          ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            s"Sec-WebSocket-Accept: $accept\r\n$selected\r\n").getBytes(UTF_8)
        )
        out.flush()
        // 读循环：掩码帧 → 文本记录（+可选原样回授）。重组纪律：opcode 只在
        // 非 continuation 帧（低 4 位非零）上钉定——continuation 帧首字节
        // 0x8_ 会把已钉 opcode 覆写成 0，消息被静默丢掉（本次批的实测坑）。
        var acc = new StringBuilder
        var msgOpcode = 0
        var done = false
        while !stopped && !done do
          readFrame(reader) match
            case None => done = true
            case Some((op, payload)) =>
              framesRead += 1
              frameTrace.add(f"raw#${framesRead} op=0x${op & 0xff}%02x len=${payload.length} fin=${(op & 0x80) != 0}")
              if (op & 0x0f) != 0 then msgOpcode = op & 0x0f
              acc.append(new String(payload, UTF_8))
              if (op & 0x80) != 0 then
                val text = acc.toString
                acc = new StringBuilder
                val opcode = msgOpcode
                msgOpcode = 0
                if opcode == 0x1 then
                  clientFrames.add(text)
                  frameTrace.add(f"msg#added len=${text.length} opcode=0x$opcode%02x")
                  if echoFrames then writeServerFrame(out, fin = true, opcode = 0x1, text.getBytes(UTF_8))
      catch case e: Exception =>
        serveError = s"${e.getClass.getSimpleName}: ${e.getMessage}"

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
  end PairingRelay

  /**
   * 两端口配对（契约 2.14/2.15 全链）：**端口 R** = relay 替身（echo
   * `neblink.portal.v2` + 帧回授），**端口 B** = 引擎 loopback 面（下发大消息）。
   * 链路 = WsOpen → B 下发大消息 → 引擎上行分片过真隧道 socket → R 回授 →
   * 桥（下行重组）→ B 收到逐字重组件。替身回授腿 = 真 relay 读循环的分诊位
   * （decode → enqueueFrame，同 RelayWsListener.handlePortalFrame 形）。
   */
  private def roundTrip(portalV2Negotiated: Boolean, payload: Array[Byte]): IO[(List[WsDataFrame], List[String])] =
    val relay   = new PairingRelay(echoSubprotocol = portalV2Negotiated, echoFrames = true)
    val capture = new LoopbackCapture(Some(payload))
    Dispatcher.parallel[IO]
      .use { _ =>
        val faces = new PortalHostFaces(gatewayPort = capture.port)
        val deps  = mkDeps(faces)
        // 桥在 socket 建立后才能装配；回授帧经由这个 holder 进桥（mailbox
        // 无界缓冲兜住装配竞态窗，同 PortalConnection.dispatch 语义）。
        val bridgeHolder = new java.util.concurrent.atomic.AtomicReference[Option[PortalTunnelBridge]](None)
        for
          // 真 JDK WebSocket 客户端作隧道 socket。测试客户端恒 offer（钉协商
          // 机械本身；env 总闸是运维回退面，另由文档钉，不进本判据面）。
          // listener 带 RFC 分帧重组（大消息按片回调 last=false——生产面
          // RelayWsListener.FrameReassembler 的同款义务，测试替身不得裸 decode）。
          ws <- IO {
            val c = java.net.http.HttpClient
              .newBuilder()
              .version(java.net.http.HttpClient.Version.HTTP_1_1)
              .proxy(java.net.ProxySelector.of(null))
              .build()
            val acc = new java.lang.StringBuilder
            val listener = new java.net.http.WebSocket.Listener:
              override def onOpen(w: java.net.http.WebSocket): Unit = w.request(1)
              override def onText(w: java.net.http.WebSocket, data: CharSequence, last: Boolean) =
                acc.append(data)
                if last then
                  val text = acc.toString
                  acc.setLength(0)
                  PortalWireCodec.decode(text) match
                    case Right(frame) => bridgeHolder.get.foreach(_.enqueueFrame(frame))
                    case Left(_)      => () // 回授路径无非契约帧；拒收即替身 bug，由断言面暴露
                w.request(1)
                null
            c.newWebSocketBuilder()
              .subprotocols(PortalWireCodec.PortalV2Subprotocol)
              .buildAsync(java.net.URI.create(s"ws://127.0.0.1:${relay.port}/api/device/relay-ws"), listener)
              .get(10, java.util.concurrent.TimeUnit.SECONDS)
          }
          negotiated = ws.getSubprotocol == PortalWireCodec.PortalV2Subprotocol
          _ = assertEquals(
                negotiated,
                portalV2Negotiated,
                s"协商结果必须等于替身 echo 面（negotiated=$negotiated）"
              )
          bridge = new PortalTunnelBridge(deps, new RelayFrameSender(ws), portalV2 = negotiated)
          _ = bridgeHolder.set(Some(bridge))
          fiber <- bridge.run.start
          _ = bridge.enqueueFrame(WsOpen(Rid, "/ws", None, None))
          // 等回路面走完：R 收到上行件、回授、桥下行重组、B 收到逐字重组件。
          got <- waitUntil(20.seconds)(IO(capture.received.stream().anyMatch(_ == new String(payload, UTF_8))))
          _ = assert(
                got,
                s"两端口 round-trip 未完成：frames=${relay.clientFrames.size()} " +
                  s"first=${Option(relay.clientFrames.peek()).map(_.take(200))} " +
                  s"capture=${capture.received.size()} captureFramesRead=${capture.framesRead} " +
                  s"payloadWritten=${capture.payloadWritten} writeErr=${capture.payloadWriteError} " +
                  s"relayFramesRead=${relay.framesRead} relayErr=${relay.serveError} " +
                  s"trace=${relay.frameTrace.asScala.toList.mkString(", ")}"
              )
          _ <- bridge.teardown() *> fiber.cancel
          _ <- IO(ws.abort())
          shards <- IO {
            import scala.jdk.CollectionConverters.*
            relay.clientFrames.asScala.toList
              .flatMap(t => PortalWireCodec.decode(t).toOption)
              .collect { case f: WsDataFrame => f }
          }
          captured <- IO {
            import scala.jdk.CollectionConverters.*
            capture.received.asScala.toList
          }
        yield (shards, captured)
      }
      .guarantee(IO { relay.close(); capture.close() })

  test("two-port round-trip: 1MB message shards over a REAL negotiated tunnel and reassembles byte-exact at the loopback face"):
    val payload = ("roundtrip-分片-😀" * 90000).getBytes(UTF_8) // ≈1.3MB ⇒ 5 片
    roundTrip(portalV2Negotiated = true, payload).map { (shards, captured) =>
      assert(shards.size >= 5, s"大消息必须切多片；got ${shards.size}")
      assertEquals(shards.map(_.seq).toList, (0 until shards.size).toList)
      assertEquals(shards.map(_.more).toList, (0 until shards.size).map(_ < shards.size - 1).toList)
      assert(shards.forall(_.enc == "none") && shards.forall(_.kind == "text"))
      assert(Arrays.equals(shards.foldLeft(Array.emptyByteArray)(_ ++ _.data), payload), "wire 上的分片拼接必须字节级还原")
      assert(captured.contains(new String(payload, UTF_8)), "loopback 面收到的必须是重组后整消息")
    }

  test("two-port round-trip non-negotiated: the same message leaves as ONE v1 ws_frame (fallback golden over real socket)"):
    val payload = ("fallback-" * 2000).getBytes(UTF_8) // 16KB ⇒ 单帧
    roundTrip(portalV2Negotiated = false, payload).map { (shards, captured) =>
      assertEquals(shards, Nil, "未协商隧道零 ws_data_frame")
      assert(captured.contains(new String(payload, UTF_8)), "回落面下重组语义照常（v1 整帧 → 本地面整消息）")
    }

end PortalWsShardSpec
