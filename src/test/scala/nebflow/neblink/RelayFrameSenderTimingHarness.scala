package nebflow.neblink

import cats.effect.IO
import munit.CatsEffectSuite

import java.net.{InetAddress, ServerSocket, Socket, URI}
import java.net.http.{HttpClient, WebSocket}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 稳定性批 2026-10-26（作者 01:09「大文件传输掉登录」）——**证据 harness，不是
 * CI 断言**：真 socket 上以生产同构的帧尺寸（256KiB body_frame 的 base64 体）
 * 灌 5MB+ 传输洪峰，同时以固定节奏发控制帧（生产 = 隧道活性 pong / 浏览器控制台
 * WS 的 ws_frame pong），逐帧落盘 enqueue→写完成的时延，供修复前后对照。
 *
 * 🔴 默认跳过（CI 无此噪声面）：仅 `NF_TIMING_HARNESS=1` 时运行。
 * 输出：`$NF_TIMING_OUT/sender-timing-<tag>.jsonl`（缺省 target/session-stability/），
 * 每行 `{"kind":"bulk|control","index":n,"totalMs":..,"bytes":..}`，
 * totalMs = 从 send 调用到 sendText future 完成（排队 + 线上写完）。
 *
 * 运行：
 *   NF_TIMING_HARNESS=1 NF_TIMING_TAG=prefix \
 *     sbt "Test/testOnly nebflow.neblink.RelayFrameSenderTimingHarness"
 *
 * 洪峰口径（对齐作者症状「5MB+ 图片」）：BodyChunkBytes=256KiB 的 JSON+base64
 * envelope（≈349,525 字符/帧）× 16 帧 ≈ 5.6MB 字节面；控制帧 = 生产 pong 字面量。
 * 节流对端：每读 32KiB 睡 40ms（≈0.8MB/s 慢上行），单帧写完 ≈ 320ms，16 帧排空
 * ≈ 5s+ —— 压缩时钟下复刻「传输在途、控制帧排队」的窗口（生产拍：30s 心跳 /
 * 分钟级传输，同一形状）。
 */
class RelayFrameSenderTimingHarness extends CatsEffectSuite:

  // 生产同构帧尺寸：256KiB chunk 的 base64 + JSON envelope ≈ 349,525 chars。
  private val BulkFrame: String =
    val raw = new Array[Byte](256 * 1024)
    java.util.Arrays.fill(raw, 0x61.toByte)
    s"""{"type":"body_frame","requestId":"harness","seq":0,"more":true,"data":"${Base64.getEncoder.encodeToString(raw)}"}"""

  private val ControlFrame = """{"type":"pong"}"""
  private val BulkFrames = 16 // ×349,525 chars ≈ 5.6MB 字节面（≥5MB 门槛）
  private val ControlEveryMs = 300L
  private val ControlTotal = 24

  test("sender-timing harness：5MB+ bulk 洪峰下控制帧时延逐帧落盘（NF_TIMING_HARNESS=1 才跑）"):
    val enabled = Option(System.getenv("NF_TIMING_HARNESS")).exists(_.trim.nonEmpty)
    if !enabled then
      println("sender-timing harness skipped (set NF_TIMING_HARNESS=1 to run)")
      IO.unit
    else
      IO.blocking {
        val outDir = Option(System.getenv("NF_TIMING_OUT")).map(Paths.get(_))
          .getOrElse(Paths.get("target", "session-stability"))
        Files.createDirectories(outDir)
        val tag = Option(System.getenv("NF_TIMING_TAG")).getOrElse("run")
        val out = outDir.resolve(s"sender-timing-$tag.jsonl")

        val fix = new ThrottledReadWsFixture(readSleepMs = 40L)
        try
          val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
          val ws = client
            .newWebSocketBuilder()
            .buildAsync(URI.create(s"ws://127.0.0.1:${fix.port}/sender-timing"), new WebSocket.Listener {})
            .get(10, TimeUnit.SECONDS)
          try
            val sender = new RelayFrameSender(ws)
            val stop = new AtomicBoolean(false)
            val sink = Files.newBufferedWriter(out)
            val t0 = System.nanoTime()

            // 控制面：固定节奏发 pong，逐帧记（排队+写完）时延 —— 独立线程，
            // 与 bulk 泵（本线程）在 sender 的发送位上竞争（生产形状）。
            val controlThread = new Thread(() =>
              try
                var i = 0
                while !stop.get() && i < ControlTotal do
                  val f0 = System.nanoTime()
                  sender.send(ControlFrame).unsafeRunSync()
                  val totalMs = (System.nanoTime() - f0) / 1000000
                  synchronized {
                    sink.write(s"""{"kind":"control","index":$i,"totalMs":$totalMs,"bytes":${ControlFrame.length}}\n""")
                    sink.flush()
                  }
                  i += 1
                  Thread.sleep(ControlEveryMs)
              catch case _: InterruptedException => ()
              finally stop.set(true),
              "sender-timing-control"
            )
            controlThread.start()

            // bulk 面：洪峰逐帧顺序发（生产 = 单 fiber streamResponseBody）。
            var bulkSent = 0
            try
              while bulkSent < BulkFrames && !stop.get() do
                val f0 = System.nanoTime()
                sender.send(BulkFrame).unsafeRunSync()
                val totalMs = (System.nanoTime() - f0) / 1000000
                synchronized {
                  sink.write(s"""{"kind":"bulk","index":$bulkSent,"totalMs":$totalMs,"bytes":${BulkFrame.length}}\n""")
                  sink.flush()
                }
                bulkSent += 1
            finally
              stop.set(true)
              controlThread.join(5000)
              sink.close()

            val elapsedMs = (System.nanoTime() - t0) / 1000000
            println(f"[sender-timing] tag=$tag elapsed=${elapsedMs}ms bulkSent=$bulkSent out=$out")
            assertEquals(bulkSent, BulkFrames, "洪峰应全部写完（夹具有效性）")
          finally try ws.abort() finally ()
        finally fix.close()
      }

  /** 慢读对端：排空速率受控（每 32KiB 睡 readSleepMs），制造确定性回压。 */
  private final class ThrottledReadWsFixture(readSleepMs: Long) extends AutoCloseable:
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
              val h = new Thread(() => serve(s), "sender-timing-throttled")
              h.setDaemon(true)
              h.start()
            catch case _: Throwable => stopped = true,
        "sender-timing-accept"
      )
      t.setDaemon(true)
      t.start()
      t

    private def serve(s: Socket): Unit =
      try
        val request = readHeaders(s.getInputStream)
        s.getOutputStream.write(handshakeResponse(request).getBytes(StandardCharsets.ISO_8859_1))
        s.getOutputStream.flush()
        val buf = new Array[Byte](32 * 1024)
        while !stopped do
          val n = s.getInputStream.read(buf)
          if n < 0 then return
          if n > 0 then Thread.sleep(readSleepMs) // 受限排空 = 慢上行
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

  end ThrottledReadWsFixture

end RelayFrameSenderTimingHarness
