package nebflow.gateway

import cats.effect.unsafe.implicits.global
import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import io.circe.parser.parse
import munit.FunSuite

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.Executors
import scala.jdk.CollectionConverters.*

/** SttService 双协议自动识别（2026-08-21 MiMo 支持）：endpoint 以
  * /chat/completions 结尾 → chat 协议（input_audio data-URL + asr_options，
  * 转写文本在 choices[0].message.content）；否则保留 multipart transcriptions
  * （OpenAI Whisper 风格）。本 spec 用本地 HttpServer 录制真实 HTTP 请求形状：
  * Content-Type、JSON 字段、data URL 前缀、语言映射（zh-CN→zh / en-US→en /
  * 其余→auto）、响应解析、非 200 错误回传 + WARN 实发（warnSync 钉子——
  * IO.blocking 语句位裸 logger.warn 是死日志，曾被静默吞）。 */
class SttServiceProtocolSpec extends FunSuite:

  private final case class Captured(contentType: String, body: Array[Byte])

  /** 录制型 handler：drain+捕获请求 body，回固定响应（非空 body，含错误态）。 */
  private final class RecordingHandler(status: Int, responseBody: String) extends HttpHandler:
    private val buf = scala.collection.mutable.ListBuffer.empty[Captured]
    def captured: List[Captured] = buf.synchronized(buf.toList)
    def handle(exchange: HttpExchange): Unit =
      val body = exchange.getRequestBody.readAllBytes() // drain（JDK handler 铁律）
      buf.synchronized {
        buf += Captured(
          Option(exchange.getRequestHeaders.getFirst("Content-type")).getOrElse(""),
          body
        )
      }
      val bytes = responseBody.getBytes(StandardCharsets.UTF_8)
      exchange.sendResponseHeaders(status, bytes.length)
      exchange.getResponseBody.write(bytes)
      exchange.close()
  end RecordingHandler

  private def withServer[A](status: Int, responseBody: String)(
      f: (HttpServer, RecordingHandler) => A
  ): A =
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.setExecutor(Executors.newCachedThreadPool())
    val handler = new RecordingHandler(status, responseBody)
    server.createContext("/", handler)
    server.start()
    try f(server, handler)
    finally server.stop(0)

  /** 含非 ASCII 字节的伪 WAV——验证 base64 编码往返无损。 */
  private val wavBytes: Array[Byte] = Array[Byte](0x52, 0x49, 0x46, 0x46).map(_.toByte) ++
    Array.tabulate(64)(i => (i * 7 + 0x80).toByte) // 0x80.. 高位字节，UTF-8 不保真

  private val chatOkBody =
    """{"id":"chatcmpl-1","choices":[{"index":0,"message":{"role":"assistant","content":"你好，世界。"},"finish_reason":"stop"}]}"""

  // ── chat 协议：请求形状 + 响应解析 ────────────────────────

  test("chat 协议：endpoint /chat/completions 后缀 → application/json + input_audio data-URL + choices 解析") {
    withServer(200, chatOkBody) { (server, handler) =>
      val svc = new SttService(
        "sk-mimo-test",
        "mimo-v2.5-asr",
        s"http://127.0.0.1:${server.getAddress.getPort}/v1/chat/completions"
      )
      assertEquals(
        svc.transcribe(wavBytes, Some("zh-CN")).unsafeRunSync(),
        Right("你好，世界。")
      )
      val cap = handler.captured.head
      assertEquals(cap.contentType, "application/json")
      val json = parse(new String(cap.body, StandardCharsets.UTF_8)).toOption.get
      assertEquals(json.hcursor.downField("model").as[String], Right("mimo-v2.5-asr"))
      val audio = json.hcursor
        .downField("messages").downArray
        .downField("content").downArray
        .downField("input_audio")
      assertEquals(audio.downField("format").as[String], Right("wav"))
      audio.downField("data").as[String] match
        case Right(dataUrl) =>
          val expected = "data:audio/wav;base64," + Base64.getEncoder.encodeToString(wavBytes)
          assertEquals(dataUrl, expected, "data URL must be exact data:audio/wav;base64,<b64>")
          // base64 往返无损（含高位字节）——Array 断言必须 toSeq（munit 引用相等）
          assertEquals(
            Base64.getDecoder.decode(dataUrl.stripPrefix("data:audio/wav;base64,")).toSeq,
            wavBytes.toSeq
          )
        case Left(e) => fail(s"missing input_audio.data: $e")
      assertEquals(
        json.hcursor.downField("asr_options").downField("language").as[String],
        Right("zh")
      )
      assertEquals(json.hcursor.downField("messages").downArray.downField("role").as[String], Right("user"))
    }
  }

  test("chat 协议：语言映射 en-US→en、fr-FR→auto、None→auto（endpoint 尾空白也识别）") {
    withServer(200, chatOkBody) { (server, handler) =>
      // 尾随空格覆盖 endpoint.trim 判定路径
      val svc = new SttService(
        "sk-mimo-test",
        "mimo-v2.5-asr",
        s"http://127.0.0.1:${server.getAddress.getPort}/v1/chat/completions "
      )
      svc.transcribe(wavBytes, Some("en-US")).unsafeRunSync()
      svc.transcribe(wavBytes, Some("fr-FR")).unsafeRunSync()
      svc.transcribe(wavBytes, None).unsafeRunSync()
      val langs = handler.captured.map { c =>
        parse(new String(c.body, StandardCharsets.UTF_8)).toOption.get
          .hcursor.downField("asr_options").downField("language").as[String].toOption.get
      }
      assertEquals(langs, List("en", "auto", "auto"))
    }
  }

  test("chat 协议：非 200 → Left + WARN 实发（IO.blocking 语句位 warnSync 钉子）") {
    withServer(500, """{"error":"boom"}""") { (server, handler) =>
      val lbLogger =
        org.slf4j.LoggerFactory.getLogger("nebflow.stt").asInstanceOf[ch.qos.logback.classic.Logger]
      val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
      try
        appender.start()
        lbLogger.addAppender(appender)
        val svc = new SttService(
          "sk-mimo-test",
          "mimo-v2.5-asr",
          s"http://127.0.0.1:${server.getAddress.getPort}/v1/chat/completions"
        )
        assertEquals(
          svc.transcribe(wavBytes, Some("zh-CN")).unsafeRunSync(),
          Left("STT API error: 500")
        )
        val warns = appender.list.asScala.toList
          .filter(_.getLevel == ch.qos.logback.classic.Level.WARN)
          .map(_.getFormattedMessage)
        assert(
          warns.exists(m => m.contains("STT API error 500") && m.contains("boom")),
          s"expected recovery-path WARN to actually fire, got $warns"
        )
      finally lbLogger.detachAppender(appender)
    }
  }

  test("chat 协议：200 但无 choices → Left(unexpected format)") {
    withServer(200, """{"unexpected":"shape"}""") { (server, _) =>
      val svc = new SttService(
        "sk-mimo-test",
        "mimo-v2.5-asr",
        s"http://127.0.0.1:${server.getAddress.getPort}/v1/chat/completions"
      )
      assertEquals(
        svc.transcribe(wavBytes, None).unsafeRunSync(),
        Left("STT API returned unexpected format")
      )
    }
  }

  // ── multipart 协议：原路径保留（OpenAI Whisper 风格回归）────

  test("multipart 协议：非 chat 后缀 → multipart/form-data + 原始字节 + text 解析") {
    withServer(200, """{"text":"hello whisper"}""") { (server, handler) =>
      val svc = new SttService(
        "sk-oai-test",
        "whisper-1",
        s"http://127.0.0.1:${server.getAddress.getPort}/v1/audio/transcriptions"
      )
      assertEquals(
        svc.transcribe(wavBytes, Some("zh")).unsafeRunSync(),
        Right("hello whisper")
      )
      val cap = handler.captured.head
      assert(cap.contentType.startsWith("multipart/form-data"), cap.contentType)
      val bodyText = new String(cap.body, StandardCharsets.ISO_8859_1) // 字节保真
      assert(bodyText.contains("""name="file"; filename="audio.wav""""))
      assert(bodyText.contains("""name="model""""))
      assert(bodyText.contains("""name="language""""))
      // 音频以原始字节（非 base64）内嵌
      assert(bodyText.contains(new String(wavBytes, StandardCharsets.ISO_8859_1)))
    }
  }

  test("multipart 协议：非 200 → Left（错误回传回归）") {
    withServer(503, "overloaded") { (server, _) =>
      val svc = new SttService(
        "sk-oai-test",
        "whisper-1",
        s"http://127.0.0.1:${server.getAddress.getPort}/v1/audio/transcriptions"
      )
      assertEquals(
        svc.transcribe(wavBytes, None).unsafeRunSync(),
        Left("STT API error: 503")
      )
    }
  }

  test("空音频：两协议均 Left 且零请求") {
    withServer(200, chatOkBody) { (server, handler) =>
      val chat = new SttService("k", "m", s"http://127.0.0.1:${server.getAddress.getPort}/v1/chat/completions")
      val mp = new SttService("k", "m", s"http://127.0.0.1:${server.getAddress.getPort}/v1/audio/transcriptions")
      assertEquals(chat.transcribe(Array.emptyByteArray, None).unsafeRunSync(), Left("Empty audio data"))
      assertEquals(mp.transcribe(Array.emptyByteArray, None).unsafeRunSync(), Left("Empty audio data"))
      assertEquals(handler.captured, Nil)
    }
  }

end SttServiceProtocolSpec
