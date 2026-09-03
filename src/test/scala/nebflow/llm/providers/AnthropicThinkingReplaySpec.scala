package nebflow.llm.providers

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.llm.*
import nebflow.shared.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.StreamBackend

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*

/**
 * 审计 20260903 子项④——跨 provider 重放 thinking 块适配（flap 风暴底层缺陷）。
 *
 * 会话历史由不产出 thinking 块的 provider 生成（所有 OpenAI 协议 adapter 的
 * history 序列化都丢弃 thinking）→ fallback 到 requireThinkingPassback 的
 * deepseek → thinking 模式下 assistant 历史缺块 → 400 invalid_request
 * （"The content[].thinking in the thinking mode must be passed back"）→
 * 25 次 DOWN/UP flap。
 *
 * 取舍（转换优先、不可转换则剥离）：
 *  - 历史有 thinking 块 → 原样回传（2026-08-15 既有契约不动，含签名行为
 *    由 AnthropicThinkingPassbackSpec 锁定）；
 *  - 历史缺 thinking 块 → 转换不可能（不可伪造模型没产生的推理）→ 剥离 =
 *    本请求不进 thinking 模式（去掉 thinking 参数）。
 *
 * 变异验红：effectiveThinking 还原为直通 params.thinking → T1/T5 红
 * （出站 body 仍带 thinking 字段 = 生产 400 形态）。
 */
class AnthropicThinkingReplaySpec extends CatsEffectSuite:

  override val munitIOTimeout = 30.seconds

  private val thinkingOn = Some(Json.obj("type" -> "enabled".asJson, "budget_tokens" -> 1024.asJson))

  private def adapter(passback: Boolean): AnthropicAdapter =
    AnthropicAdapter(
      "http://localhost:0",
      "k",
      null.asInstanceOf[StreamBackend[IO, Fs2Streams[IO]]],
      passback
    )

  /** zhipu/GLM 形态的会话历史：assistant 消息无 thinking 块（OpenAI 协议
    * adapter 序列化时丢弃）。 */
  private def historyWithoutThinking: List[Message] =
    List(
      Message(MessageRole.User, Left("hi")),
      Message(MessageRole.Assistant, Right(List(ContentBlock.Text("the answer")))),
      Message(MessageRole.User, Left("continue"))
    )

  /** deepseek 自产历史：assistant 消息带 unsigned thinking 块。 */
  private def historyWithUnsignedThinking: List[Message] =
    List(
      Message(MessageRole.User, Left("hi")),
      Message(
        MessageRole.Assistant,
        Right(List(ContentBlock.Thinking("let me think", None), ContentBlock.Text("the answer")))
      ),
      Message(MessageRole.User, Left("continue"))
    )

  private def params(messages: List[Message], thinking: Option[Json], passback: Boolean) =
    SendMessageParams(
      messages = messages,
      model = "deepseek-v4-flash",
      thinking = thinking
    )

  // ── 单元：effectiveThinking 决策 ──

  test("T1: passback + thinking requested + history WITHOUT thinking blocks → strip (None)") {
    val out = adapter(passback = true).effectiveThinking(params(historyWithoutThinking, thinkingOn, true))
    assertEquals(out, None, "unconvertible history must strip thinking mode for this request")
  }

  test("T2: passback + thinking requested + history WITH thinking blocks → keep (conversion passes)") {
    val out = adapter(passback = true).effectiveThinking(params(historyWithUnsignedThinking, thinkingOn, true))
    assertEquals(out, thinkingOn, "replayable history keeps thinking mode (2026-08-15 contract)")
  }

  test("T3: non-passback (real Anthropic) is unaffected by history shape") {
    val out = adapter(passback = false).effectiveThinking(params(historyWithoutThinking, thinkingOn, false))
    assertEquals(out, thinkingOn, "default path unchanged — only passback providers adapt")
  }

  test("T4: no thinking param → None regardless (baseline)") {
    val out = adapter(passback = true).effectiveThinking(params(historyWithoutThinking, None, true))
    assertEquals(out, None)
  }

  // ── E2E：mock deepseek 出站 body 断言（适配真实请求形状） ──

  private val Port = 18571

  private val messageStart =
    "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_mock\",\"usage\":{\"input_tokens\":10,\"output_tokens\":0}}}\n\n"
  private val okTail =
    Seq(
      "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"OK\"}}\n\n",
      "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":2}}\n\n",
      "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n",
      "data: [DONE]\n\n"
    ).mkString

  /** mock：捕获出站请求 body（200 应答）。 */
  private def startCaptureMock(
      port: Int,
      captured: ConcurrentLinkedQueue[String]
  ): IO[HttpServer] =
    IO.blocking {
      val server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0)
      server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool())
      server.createContext(
        "/v1/messages",
        (exchange: HttpExchange) =>
          val body = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
          captured.add(body)
          val resp = (messageStart + okTail).getBytes(StandardCharsets.UTF_8)
          exchange.getResponseHeaders.add("Content-Type", "text/event-stream")
          exchange.sendResponseHeaders(200, 0)
          exchange.getResponseBody.write(resp)
          exchange.close()
      )
      server.start()
      server
    }

  private def sendTurn(messages: List[Message], port: Int): Either[Throwable, List[StreamChunk]] =
    (for
      configRef <- Ref.of[IO, NebflowServiceConfig](
        NebflowServiceConfig(
          llm = ServiceLlmConfig(
            providers = Map(
              // providerId = "deepseek" → registry 默认 requireThinkingPassback=true
              "deepseek" -> ProviderConfig(
                baseUrl = s"http://127.0.0.1:$port",
                apiKey = "test",
                protocol = LlmProtocol.Anthropic,
                models = List(ModelConfig("deepseek-v4-flash", maxTokens = 1024, vision = Some(false)))
              )
            )
          )
        )
      )
      sessionOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      triple <- LlmInterface.createLlm(sessionOverrides, None, Some(configRef))
      (handle, _, _, release) = triple
      req = LlmRequest(
        messages = messages,
        sessionId = "thinking-replay-e2e",
        agentId = "thinking-replay-spec",
        thinking = thinkingOn,
        agentModel = Some(AgentModelConfig(preferred = Some("deepseek/deepseek-v4-flash"), fallbacks = Nil))
      )
      chunks <- handle.sendStream(req).compile.toList.attempt
      _ <- release
    yield chunks).unsafeRunSync()

  test("T5: E2E — zhipu-shaped history replayed to deepseek sends NO thinking param (no 400)") {
    val captured = ConcurrentLinkedQueue[String]()
    var server: HttpServer = null
    try
      server = startCaptureMock(Port, captured).unsafeRunSync()
      val result = sendTurn(historyWithoutThinking, Port)
      result match
        case Right(chunks) =>
          val text = chunks.collect { case StreamChunk.TextDelta(d) => d }.mkString
          assertEquals(text, "OK", "request must succeed once thinking mode is stripped")
        case Left(e) => fail(s"stripped thinking must not 400, got $e")
      val body = parse(captured.peek()).toOption.getOrElse(fail("captured body must be JSON"))
      assert(
        body.hcursor.downField("thinking").focus.isEmpty,
        s"outgoing body must NOT carry a thinking field, got: ${body.noSpaces.take(400)}"
      )
    finally if server != null then server.stop(0)
  }

  test("T6: E2E — deepseek-native history (unsigned thinking) keeps thinking param AND replays blocks") {
    val captured = ConcurrentLinkedQueue[String]()
    var server: HttpServer = null
    try
      server = startCaptureMock(Port, captured).unsafeRunSync()
      val result = sendTurn(historyWithUnsignedThinking, Port)
      result match
        case Right(chunks) =>
          val text = chunks.collect { case StreamChunk.TextDelta(d) => d }.mkString
          assertEquals(text, "OK")
        case Left(e) => fail(s"replayable history must succeed, got $e")
      val bodyStr = captured.peek()
      assert(bodyStr != null && bodyStr.contains("\"thinking\""), "thinking param must be present")
      assert(bodyStr.contains("let me think"), "unsigned thinking block must be replayed (2026-08-15 fix)")
    finally if server != null then server.stop(0)
  }
end AnthropicThinkingReplaySpec
