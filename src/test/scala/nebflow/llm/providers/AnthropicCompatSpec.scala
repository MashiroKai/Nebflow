package nebflow.llm.providers

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.llm.*
import nebflow.shared.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.{GenericRequest, Response, StreamBackend}

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * protocol-compat 批（2026-11-01）——deepseek `Format` 事故的端到端钉。
 *
 * 事故链（真端点复现，见 docs/20261101_protocol-compat-batch.md §1）：
 *   历史缺思考块 → `effectiveThinking` 剥离 → 旧实现**省略 thinking 键**
 *   → DeepSeek 缺键 = 默认进思考模式 → 400 "content[].thinking ... must be
 *   passed back" → classifyError 400=Format/Permanent → 候选判死 → 整批全灭。
 *
 * 本 spec 用可编程内存后端（记录每个请求的 body 与 headers）钉三组：
 *   A 组（根因修复）：剥离腿发 `thinking:{type:"disabled"}`，不再发省略键。
 *   B 组（降级重试）：撞 passback 400 就地重试一次，且**重试前零块已发出**。
 *   C 组（解析鲁棒）：thinking-first / 未知块 / 未知 delta 不崩、不丢正文。
 *
 * 变异验红：stripWire 改回 `Json.obj()` → A1/A2 红；删 handleErrorWith 重试
 * → B1 红；content_block_delta 未知分支改回 Nil → C2 红。
 */
class AnthropicCompatSpec extends CatsEffectSuite:

  override val munitIOTimeout = 60.seconds

  /** 可编程后端：按入队顺序应答，记录请求体与请求头。 */
  private final class ScriptedBackend(streams: Boolean) extends StreamBackend[IO, Fs2Streams[IO]]:
    val bodies = new ConcurrentLinkedQueue[String]()
    val headers = new ConcurrentLinkedQueue[List[(String, String)]]()
    private val queue = new ConcurrentLinkedQueue[Either[(Int, String), (Int, fs2.Stream[IO, Byte])]]()

    def enqueueJson(code: Int, body: String): Unit = queue.add(Left((code, body)))
    def enqueueStream(code: Int, body: fs2.Stream[IO, Byte]): Unit = queue.add(Right((code, body)))

    def send[T](request: GenericRequest[T, Fs2Streams[IO] & sttp.capabilities.Effect[IO]]): IO[Response[T]] =
      IO.delay {
        request.body match
          case sttp.client4.StringBody(s, _, _) => bodies.add(s)
          case other => bodies.add(other.toString.take(64))
        headers.add(request.headers.map(h => h.name -> h.value).toList)
        Option(queue.poll()) match
          case Some(Left((code, b))) =>
            // 4xx 走 Left（sttp 语义：非 2xx 的 body 在错误侧），2xx 走 Right
            if code >= 400 then Response(Left(b).asInstanceOf[T], sttp.model.StatusCode(code), "err", Nil)
            else Response(Right(b).asInstanceOf[T], sttp.model.StatusCode(code), "ok", Nil)
          case Some(Right((code, s))) =>
            Response(
              Right(s.asInstanceOf[Any]).asInstanceOf[T],
              sttp.model.StatusCode(code),
              "err",
              Nil
            )
          case None => Response(Right("{}").asInstanceOf[T], sttp.model.StatusCode.Ok, "ok", Nil)
      }

    def monad: sttp.monad.MonadError[IO] =
      new sttp.client4.impl.cats.CatsMonadError[IO](using IO.asyncForIO)
    def close(): IO[Unit] = IO.unit
  end ScriptedBackend

  private def nonStream(): ScriptedBackend = new ScriptedBackend(streams = false)
  private def streaming(): ScriptedBackend = new ScriptedBackend(streams = true)

  private def mk(be: ScriptedBackend, passback: Boolean = true): AnthropicAdapter =
    AnthropicAdapter(
      "https://api.example.com/anthropic/",
      "k",
      be,
      requireThinkingPassback = passback,
      providerId = "deepseek"
    )

  /** 历史里有助手消息**不带思考块** ⇒ 触发剥离腿。 */
  private val historyWithoutThinking = List(
    Message(MessageRole.User, Left("hi")),
    Message(MessageRole.Assistant, Right(List(ContentBlock.Text("answer")))),
    Message(MessageRole.User, Left("continue"))
  )

  private def params(history: List[Message]) = SendMessageParams(
    messages = history,
    model = "deepseek-flash",
    thinking = Some(Json.obj("type" -> "enabled".asJson, "budget_tokens" -> 32000.asJson)),
    systemStable = Some("stable system"),
    systemDynamic = Some("dynamic"),
    sessionId = Some("s1"),
    agentId = Some("a1")
  )

  private def bodyOf(be: ScriptedBackend, i: Int = 0): Json =
    io.circe.parser.parse(be.bodies.asScala.toList(i)).fold(e => throw e, identity)

  private def sse(events: (String, String)*): fs2.Stream[IO, Byte] =
    fs2.Stream
      .emits(events.flatMap { case (t, d) => List(s"event: $t", s"data: $d", "") }.map(_ + "\n"))
      .through(fs2.text.utf8.encode)

  private val okJson = """{"content":[{"type":"text","text":"ok"}],"usage":{"input_tokens":1,"output_tokens":1}}"""

  // ── A 组：根因修复（strip 形态）─────────────────────────────────────

  test("A1: strip leg sends thinking:{type:disabled} — the ONLY shape DeepSeek accepts as 'off'") {
    val be = nonStream()
    be.enqueueJson(200, okJson)
    val r = mk(be).sendMessage(params(historyWithoutThinking)).unsafeRunSync()
    assertEquals(r.reply, "ok")
    assertEquals(
      bodyOf(be).hcursor.downField("thinking").downField("type").as[String].toOption,
      Some("disabled"),
      s"strip must express 'off' explicitly; body=${bodyOf(be).noSpaces}"
    )
  }

  test("A2: strip leg no longer OMITS the thinking key for the deepseek family") {
    val be = nonStream()
    be.enqueueJson(200, okJson)
    mk(be).sendMessage(params(historyWithoutThinking)).unsafeRunSync()
    assert(
      bodyOf(be).hcursor.downField("thinking").focus.isDefined,
      s"thinking key must be present: ${bodyOf(be).noSpaces}"
    )
  }

  test("A3: history WITH thinking blocks keeps thinking:enabled and replays the block") {
    val be = nonStream()
    be.enqueueJson(200, okJson)
    val hist = List(
      Message(MessageRole.User, Left("hi")),
      Message(MessageRole.Assistant, Right(List(ContentBlock.Thinking("reasoned", None), ContentBlock.Text("answer")))),
      Message(MessageRole.User, Left("continue"))
    )
    mk(be).sendMessage(params(hist)).unsafeRunSync()
    val body = bodyOf(be)
    assertEquals(body.hcursor.downField("thinking").downField("type").as[String].toOption, Some("enabled"))
    assert(body.noSpaces.contains("\"reasoned\""), s"thinking block must replay: ${body.noSpaces}")
  }

  test("A4: the route banner reaches the wire (provider/model readable by the session)") {
    val be = nonStream()
    be.enqueueJson(200, okJson)
    mk(be).sendMessage(params(historyWithoutThinking)).unsafeRunSync()
    val sent = be.bodies.asScala.toList.head
    assert(sent.contains("provider=deepseek"), sent)
    assert(sent.contains("model=deepseek-flash"), sent)
  }

  test("A5: every request carries a User-Agent (Cloudflare-1010 root cause)") {
    val be = nonStream()
    be.enqueueJson(200, okJson)
    mk(be).sendMessage(params(historyWithoutThinking)).unsafeRunSync()
    val hs = be.headers.asScala.toList.head
    val ua = hs.collectFirst { case (k, v) if k.equalsIgnoreCase("user-agent") => v }
    assert(ua.exists(_.trim.nonEmpty), s"user-agent missing on the wire: $hs")
  }

  // ── B 组：降级重试（单 provider 失败不许打死整批）────────────────────

  private val passback400 =
    """{"type":"error","error":{"type":"invalid_request_error","code":"invalid_request_error","message":"The `content[].thinking` in the thinking mode must be passed back to the API. (request_id: t)"}}"""

  test("B1: a passback 400 is repaired in place — one retry with thinking disabled, then success") {
    val be = nonStream()
    be.enqueueJson(400, passback400)
    be.enqueueJson(200, """{"content":[{"type":"text","text":"recovered"}]}""")
    val r = mk(be).sendMessage(params(historyWithoutThinking)).unsafeRunSync()
    assertEquals(r.reply, "recovered")
    assertEquals(be.bodies.size, 2, "exactly one degraded retry, not a retry storm")
    assertEquals(
      bodyOf(be, 1).hcursor.downField("thinking").downField("type").as[String].toOption,
      Some("disabled")
    )
  }

  test("B2: a NON-passback 400 is NOT retried (no blind retry on shape errors)") {
    val be = nonStream()
    be.enqueueJson(400, """{"error":{"type":"invalid_request_error","message":"Model \"x\" is not supported"}}""")
    val ex = intercept[sttp.client4.HttpError[?]](mk(be).sendMessage(params(historyWithoutThinking)).unsafeRunSync())
    assertEquals(be.bodies.size, 1, "single attempt — nothing to repair")
    assert(ex.getMessage.contains("400"), ex.getMessage)
  }

  test("B3: streaming retry emits ZERO chunks before the repair (seam-safe, no stitched output)") {
    val be = streaming()
    be.enqueueJson(400, passback400)
    be.enqueueStream(
      200,
      sse(
        "message_start" -> """{"type":"message_start","message":{"usage":{"input_tokens":5}}}""",
        "content_block_start" -> """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
        "content_block_delta" -> """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi"}}""",
        "content_block_stop" -> """{"type":"content_block_stop","index":0}""",
        "message_delta" -> """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}"""
      )
    )
    val chunks = mk(be).sendMessageStream(params(historyWithoutThinking)).compile.toList.unsafeRunSync()
    assertEquals(chunks.collect { case StreamChunk.TextDelta(t) => t }.mkString(""), "hi")
    assertEquals(be.bodies.size, 2)
  }

  test("B4: the 400 diagnostic names provider, model, request shape and response types") {
    val be = nonStream()
    be.enqueueJson(
      400,
      """{"error":{"type":"invalid_request_error","code":"invalid_request_error","message":"Model \"x\" is not supported on this endpoint"}}"""
    )
    val err =
      try
        mk(be).sendMessage(params(historyWithoutThinking)).unsafeRunSync()
        fail("expected a 400")
      catch case e: sttp.client4.HttpError[?] => e.getMessage
    assert(err.contains("provider=deepseek"), err)
    assert(err.contains("model=deepseek-flash"), err)
    assert(err.contains("request=u:t,a:t,u:t"), err)
    assert(err.contains("response=error/invalid_request_error"), err)
    assert(err.contains("Model \"x\" is not supported"), err)
  }

  test("B5: an edge/WAF 403 diagnostic says header problem, not credential problem") {
    val be = nonStream()
    be.enqueueJson(403, "error code: 1010")
    val err =
      try
        mk(be).sendMessage(params(historyWithoutThinking)).unsafeRunSync()
        fail("expected a 403")
      catch case e: sttp.client4.HttpError[?] => e.getMessage
    assert(err.contains("EDGE-BLOCK"), err)
    assert(err.contains("REQUEST-HEADER problem"), err)
  }

  // ── C 组：解析鲁棒（thinking-first / 未知块 / 未知 delta）─────────────

  test("C1: a non-streaming response starting with a thinking block parses (no Format, no crash)") {
    val be = nonStream()
    be.enqueueJson(
      200,
      """{"content":[{"type":"thinking","thinking":"let me think"},{"type":"text","text":"final"}],"usage":{"input_tokens":3,"output_tokens":4}}"""
    )
    val r = mk(be).sendMessage(params(historyWithoutThinking)).unsafeRunSync()
    assertEquals(r.reply, "final")
  }

  test("C2: an UNKNOWN delta type carrying text still yields TextDelta (text never dropped)") {
    val be = streaming()
    be.enqueueStream(
      200,
      sse(
        "message_start" -> """{"type":"message_start","message":{"usage":{"input_tokens":5}}}""",
        "content_block_delta" -> """{"type":"content_block_delta","index":0,"delta":{"type":"output_text_delta","text":"survived"}}""",
        "message_delta" -> """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}"""
      )
    )
    val chunks = mk(be).sendMessageStream(params(historyWithoutThinking)).compile.toList.unsafeRunSync()
    assertEquals(chunks.collect { case StreamChunk.TextDelta(t) => t }.mkString(""), "survived")
  }

  test("C3: an UNKNOWN content block carrying reasoning becomes ThinkingDelta") {
    val be = streaming()
    be.enqueueStream(
      200,
      sse(
        "message_start" -> """{"type":"message_start","message":{"usage":{"input_tokens":5}}}""",
        "content_block_start" -> """{"type":"content_block_start","index":0,"content_block":{"type":"vendor_thought","thinking":"hmm"}}""",
        "message_delta" -> """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}"""
      )
    )
    val chunks = mk(be).sendMessageStream(params(historyWithoutThinking)).compile.toList.unsafeRunSync()
    assertEquals(chunks.collect { case StreamChunk.ThinkingDelta(t) => t }.mkString(""), "hmm")
  }

  test("C4: thinking-first interleaving with an unknown event does not break the stream") {
    val be = streaming()
    be.enqueueStream(
      200,
      sse(
        "message_start" -> """{"type":"message_start","message":{"usage":{"input_tokens":5}}}""",
        "ping" -> """{"type":"ping"}""",
        "content_block_start" -> """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
        "content_block_delta" -> """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"pondering"}}""",
        "content_block_delta" -> """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"answer"}}""",
        "message_delta" -> """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}"""
      )
    )
    val chunks = mk(be).sendMessageStream(params(historyWithoutThinking)).compile.toList.unsafeRunSync()
    assertEquals(chunks.collect { case StreamChunk.ThinkingDelta(t) => t }.mkString(""), "pondering")
    assertEquals(chunks.collect { case StreamChunk.TextDelta(t) => t }.mkString(""), "answer")
  }
end AnthropicCompatSpec
