package nebflow.llm.providers

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
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
 * thinking-config 实施批（2026-10-28/29）——wire 翻译表逐族钉（设计 §4.2）。
 *
 * AnthropicAdapter.wireThinkingFragment 是唯一翻译点：语义载体
 * （params.thinking：enabled → budget JSON，off → Json.Null）× 能力类 → 本请求
 * 要 deepMerge 的 JSON 片段。逐族钉「发什么/不发什么/省略键」：
 *
 *   W1  Budget（未知/claude）enabled → thinking:{type:enabled,budget_tokens:N}
 *       （既有形态逐字保留）
 *   W2  Budget off → 空 fragment = **省略 thinking 键**（旧 "thinking":null 消灭）
 *   W3  Toggle（glm / kimi-for-coding）enabled → thinking:{type:enabled}
 *       （**不发 budget_tokens**；glm 的 budget 处置未核实、kimi-for-coding
 *       官方对非 enabled 形态 400）
 *   W4  Toggle off → 兜底 enabled（强制思考模型 off 是错误路径，引擎不发 disabled）
 *   W5  Toggle + 显式 clearThinking=false → thinking:{type:enabled,clear_thinking:false}
 *   W6  EffortTopLevel（k3.1）enabled/off → 空 fragment（K3 官方明示移除
 *       thinking 配置；恒推理无「关」可表达）
 *   W7  EffortAnthropic（deepseek 族）enabled → thinking:{type:enabled}，
 *       不发 budget_tokens（官方字段支持表：budget_tokens is ignored）；
 *       effort 门未开（无活 anthropic 面）⇒ 不发 output_config
 *   W8  passback 剥离 → 空 fragment（20260903 语义不变）
 *
 * 第二段点亮（探测定性后，gate=true 的族）：
 *   W9  k3.1 + effort high → 顶层 reasoning_effort:"high"（无 thinking 键）
 *   W10 k3.1 / glm + effort medium → reasoning_effort:"high"（三档面客户端映射
 *       单点 = EffortWire.threeTier：DeepSeek/GLM 官方映射 medium→high；Kimi
 *       medium→high 为作者裁定取）
 *   W11 kimi-for-coding + effort 配置 → 不发 reasoning_effort（该族无 effort 面）
 *   W12 deepseek 族 + effort 配置 → 不发 output_config（门关：cmdcode 探测无
 *       可服务 anthropic 面，官方端点无凭据不可探测）
 *
 * E2E（真实 LlmInterface → registry → adapter 链，capture mock 记录出站字节）：
 *   E1  kimi-for-coding 候选 → thinking 恰为 {type:enabled}（无 budget_tokens）
 *   E2  k3.1 候选 → 出站 body 完全无 thinking 键
 *   E3  全局 off（thinking=Some(Json.Null)）→ 出站 body 完全无 thinking 键
 *       （旧形态 "thinking":null 的回归钉）
 *   E4  model 层 effort=high → 全链贯通到顶层 reasoning_effort:"high"
 *
 * 变异验红：把 wireThinkingFragment 还原为直通 deepMerge thinking → W2/E2/E3 红
 * （thinking 键回归 null/形态错）；把 onWire Toggle 还原为带 budget → W3 红。
 */
class AnthropicThinkingWireSpec extends CatsEffectSuite:

  override val munitIOTimeout = 30.seconds

  private def adapter(passback: Boolean = false): AnthropicAdapter =
    AnthropicAdapter(
      "http://localhost:0",
      "k",
      null.asInstanceOf[StreamBackend[IO, Fs2Streams[IO]]],
      passback
    )

  private val budgetOn: Option[Json] =
    Some(Json.obj("type" -> "enabled".asJson, "budget_tokens" -> 32000.asJson))
  private val off: Option[Json] = Some(Json.Null)

  private def params(
    model: String,
    thinking: Option[Json],
    resolved: Option[ResolvedThinking] = None,
    messages: List[Message] = List(Message(MessageRole.User, Left("hi")))
  ): SendMessageParams =
    SendMessageParams(messages = messages, model = model, thinking = thinking, thinkingResolved = resolved)

  private def thinkingKeyOf(fragment: Json): Option[Json] =
    fragment.hcursor.downField("thinking").focus

  // ── W1/W2：Budget 族（未知模型兜底）─────────────────────────────────────

  test("W1: budget kind + enabled → legacy {type:enabled,budget_tokens} verbatim") {
    val f = adapter().wireThinkingFragment(params("claude-sonnet-x", budgetOn))
    assertEquals(thinkingKeyOf(f), Some(Json.obj("type" -> "enabled".asJson, "budget_tokens" -> 32000.asJson)))
  }

  test("W2: budget kind + off → EMPTY fragment (thinking key omitted, never null)") {
    val f = adapter().wireThinkingFragment(params("claude-sonnet-x", off))
    assertEquals(f, Json.obj(), "off must omit the thinking key entirely")
  }

  // ── W3/W4/W5：Toggle 族（glm / kimi-for-coding）─────────────────────────

  test("W3: toggle (glm) + enabled → {type:enabled} with NO budget_tokens") {
    val f = adapter().wireThinkingFragment(params("glm-5.3-flash", budgetOn))
    assertEquals(thinkingKeyOf(f), Some(Json.obj("type" -> "enabled".asJson)))
  }

  test("W3b: toggle (kimi-for-coding) + enabled → {type:enabled} with NO budget_tokens") {
    val f = adapter().wireThinkingFragment(params("kimi-for-coding", budgetOn))
    assertEquals(thinkingKeyOf(f), Some(Json.obj("type" -> "enabled".asJson)))
  }

  test("W4: toggle + off → falls back to enabled (forced-thinking off is an error path)") {
    assertEquals(thinkingKeyOf(adapter().wireThinkingFragment(params("glm-5.3-flash", off))),
      Some(Json.obj("type" -> "enabled".asJson)))
    assertEquals(thinkingKeyOf(adapter().wireThinkingFragment(params("kimi-for-coding", off))),
      Some(Json.obj("type" -> "enabled".asJson)))
  }

  test("W5: toggle + explicit clearThinking=false → {type:enabled,clear_thinking:false}") {
    val f = adapter().wireThinkingFragment(
      params("glm-5.3-flash", budgetOn, Some(ResolvedThinking(ThinkingKind.Toggle, enabled = true, None, Some(false))))
    )
    assertEquals(
      thinkingKeyOf(f),
      Some(Json.obj("type" -> "enabled".asJson, "clear_thinking" -> false.asJson))
    )
  }

  // ── W6：EffortTopLevel 族（kimi k3.1）───────────────────────────────────

  test("W6: k3.1 + enabled → NO thinking key at all (official: remove thinking config)") {
    assertEquals(adapter().wireThinkingFragment(params("k3.1-pro-highspeed", budgetOn)), Json.obj())
  }

  test("W6b: k3.1 + off → NO thinking key") {
    assertEquals(adapter().wireThinkingFragment(params("k3.1-pro-highspeed", off)), Json.obj())
  }

  // ── W7/W12：EffortAnthropic 族（deepseek）────────────────────────────────

  test("W7: deepseek + enabled → {type:enabled}, NO budget_tokens, NO output_config (gate closed)") {
    val f = adapter().wireThinkingFragment(params("deepseek-v4.1-flash", budgetOn))
    assertEquals(thinkingKeyOf(f), Some(Json.obj("type" -> "enabled".asJson)))
    assertEquals(f.hcursor.downField("output_config").focus, None, "effort gate closed → no output_config")
  }

  // ── W8：passback 剥离 → 空 fragment ─────────────────────────────────────

  test("W8: passback strip (history without thinking blocks) → empty fragment") {
    val history = List(
      Message(MessageRole.User, Left("hi")),
      Message(MessageRole.Assistant, Right(List(ContentBlock.Text("answer")))),
      Message(MessageRole.User, Left("continue"))
    )
    val f = adapter(passback = true).wireThinkingFragment(params("deepseek-v4.1-flash", budgetOn, messages = history))
    assertEquals(f, Json.obj(), "replay strip leaves thinking mode silently (audit 20260903 #4)")
  }

  // ── W9/W10/W11/W12：第二段点亮（effort 档位）────────────────────────────

  test("W9: k3.1 + effort high → top-level reasoning_effort:high, still NO thinking key") {
    val f = adapter().wireThinkingFragment(
      params("k3.1-pro-highspeed", budgetOn, Some(ResolvedThinking(ThinkingKind.EffortTopLevel, enabled = true, Some(ThinkingEffort.High))))
    )
    assertEquals(f.hcursor.downField("reasoning_effort").as[String].toOption, Some("high"))
    assertEquals(thinkingKeyOf(f), None)
  }

  test("W10: medium maps to high on three-tier faces (k3.1 and glm)") {
    val k3 = adapter().wireThinkingFragment(
      params("k3.1-pro-highspeed", budgetOn, Some(ResolvedThinking(ThinkingKind.EffortTopLevel, enabled = true, Some(ThinkingEffort.Medium))))
    )
    assertEquals(k3.hcursor.downField("reasoning_effort").as[String].toOption, Some("high"))
    val glm = adapter().wireThinkingFragment(
      params("glm-5.3-flash", budgetOn, Some(ResolvedThinking(ThinkingKind.Toggle, enabled = true, Some(ThinkingEffort.Medium))))
    )
    assertEquals(glm.hcursor.downField("reasoning_effort").as[String].toOption, Some("high"))
    assertEquals(thinkingKeyOf(glm), Some(Json.obj("type" -> "enabled".asJson)))
  }

  test("W11: kimi-for-coding has NO effort face — effort config never reaches the wire") {
    val f = adapter().wireThinkingFragment(
      params("kimi-for-coding", budgetOn, Some(ResolvedThinking(ThinkingKind.Toggle, enabled = true, Some(ThinkingEffort.Max))))
    )
    assertEquals(f.hcursor.downField("reasoning_effort").focus, None)
    assertEquals(thinkingKeyOf(f), Some(Json.obj("type" -> "enabled".asJson)))
  }

  test("W12: deepseek family effort gate closed → no output_config even with effort configured") {
    val f = adapter().wireThinkingFragment(
      params("deepseek-v4.1-flash", budgetOn, Some(ResolvedThinking(ThinkingKind.EffortAnthropic, enabled = true, Some(ThinkingEffort.High))))
    )
    assertEquals(f.hcursor.downField("output_config").focus, None)
  }

  test("W13: family heuristic defaults match the capability table") {
    assertEquals(ThinkingFamily.forModel("glm-5.3-flash"), ThinkingKind.Toggle)
    assertEquals(ThinkingFamily.forModel("kimi-for-coding"), ThinkingKind.Toggle)
    assertEquals(ThinkingFamily.forModel("kimi-for-coding-highspeed"), ThinkingKind.Toggle)
    assertEquals(ThinkingFamily.forModel("k3.1-pro-highspeed"), ThinkingKind.EffortTopLevel)
    assertEquals(ThinkingFamily.forModel("deepseek/deepseek-v4.1-flash"), ThinkingKind.EffortAnthropic)
    assertEquals(ThinkingFamily.forModel("claude-sonnet-x"), ThinkingKind.Budget)
  }

  test("W14: explicit thinkingKind always wins over the family heuristic") {
    val f = adapter().wireThinkingFragment(
      params("glm-5.3-flash", budgetOn, Some(ResolvedThinking(ThinkingKind.Budget)))
    )
    assertEquals(
      thinkingKeyOf(f),
      Some(Json.obj("type" -> "enabled".asJson, "budget_tokens" -> 32000.asJson)),
      "explicit kind (Budget) overrides the glm toggle heuristic"
    )
  }

  // ── E2E：真实链路 capture mock（interface 解析 → registry → adapter）────

  private val anthropicSse =
    Seq(
      "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_mock\",\"usage\":{\"input_tokens\":10,\"output_tokens\":0}}}\n\n",
      "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"OK\"}}\n\n",
      "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":2}}\n\n",
      "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n",
      "data: [DONE]\n\n"
    ).mkString

  private def startCaptureMock(
    captured: ConcurrentLinkedQueue[String]
  ): IO[HttpServer] =
    IO.blocking {
      val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
      server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool())
      server.createContext(
        "/v1/messages",
        (exchange: HttpExchange) =>
          val body = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
          captured.add(body)
          val resp = anthropicSse.getBytes(StandardCharsets.UTF_8)
          exchange.getResponseHeaders.add("Content-Type", "text/event-stream")
          exchange.sendResponseHeaders(200, 0)
          exchange.getResponseBody.write(resp)
          exchange.close()
      )
      server.start()
      server
    }

  private def sendTurn(
    providerId: String,
    modelId: String,
    thinking: Option[Json],
    server: HttpServer,
    modelLayer: Option[ThinkingLayerConfig] = None
  ): Either[Throwable, List[StreamChunk]] =
    (for
      configRef <- Ref.of[IO, NebflowServiceConfig](
        NebflowServiceConfig(
          llm = ServiceLlmConfig(
            providers = Map(
              providerId -> ProviderConfig(
                baseUrl = s"http://127.0.0.1:${server.getAddress.getPort}",
                apiKey = "test",
                protocol = LlmProtocol.Anthropic,
                models = List(
                  ModelConfig(modelId, contextWindow = 1000000, thinking = modelLayer)
                )
              )
            )
          )
        )
      )
      sessionOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      triple <- LlmInterface.createLlm(sessionOverrides, None, Some(configRef))
      (handle, _, _, release) = triple
      req = LlmRequest(
        messages = List(Message(MessageRole.User, Left("hi"))),
        sessionId = "thinking-wire-spec",
        agentId = "thinking-wire-spec",
        thinking = thinking,
        agentModel = Some(AgentModelConfig(preferred = Some(s"$providerId/$modelId"), fallbacks = Nil))
      )
      chunks <- handle.sendStream(req).compile.toList.attempt
      _ <- release
    yield chunks).unsafeRunSync()

  private def assertOkAndCapture(result: Either[Throwable, List[StreamChunk]], captured: ConcurrentLinkedQueue[String]): Json =
    result match
      case Right(chunks) =>
        val text = chunks.collect { case StreamChunk.TextDelta(d) => d }.mkString
        assertEquals(text, "OK")
      case Left(e) => fail(s"request must succeed, got $e")
    parse(captured.peek()).toOption.getOrElse(fail("captured body must be JSON"))

  test("E1: kimi-for-coding candidate → wire thinking is exactly {type:enabled} (no budget)") {
    val captured = ConcurrentLinkedQueue[String]()
    var server: HttpServer = null
    try
      server = startCaptureMock(captured).unsafeRunSync()
      val body = assertOkAndCapture(sendTurn("kimi", "kimi-for-coding", budgetOn, server), captured)
      assertEquals(body.hcursor.downField("thinking").focus,
        Some(Json.obj("type" -> "enabled".asJson)), "budget_tokens must NOT reach the kimi-for-coding wire")
    finally if server != null then server.stop(0)
  }

  test("E2: k3.1 candidate → wire carries NO thinking key at all") {
    val captured = ConcurrentLinkedQueue[String]()
    var server: HttpServer = null
    try
      server = startCaptureMock(captured).unsafeRunSync()
      val body = assertOkAndCapture(sendTurn("kimi", "k3.1-pro-highspeed", budgetOn, server), captured)
      assertEquals(body.hcursor.downField("thinking").focus, None,
        "K3 official migration: thinking config removed from the wire")
    finally if server != null then server.stop(0)
  }

  test("E3: global off (Some(Json.Null)) → wire carries NO thinking key (null-shape regression pin)") {
    val captured = ConcurrentLinkedQueue[String]()
    var server: HttpServer = null
    try
      server = startCaptureMock(captured).unsafeRunSync()
      val body = assertOkAndCapture(sendTurn("p", "m1", off, server), captured)
      assertEquals(body.hcursor.downField("thinking").focus, None,
        "the old wire shape \"thinking\":null must stay dead")
    finally if server != null then server.stop(0)
  }

  test("E4: model layer effort=high flows through the full chain to reasoning_effort") {
    val captured = ConcurrentLinkedQueue[String]()
    var server: HttpServer = null
    try
      server = startCaptureMock(captured).unsafeRunSync()
      val body = assertOkAndCapture(
        sendTurn("kimi", "k3.1-pro-highspeed", budgetOn, server,
          modelLayer = Some(ThinkingLayerConfig(effort = Some(ThinkingEffort.High)))),
        captured
      )
      assertEquals(body.hcursor.downField("reasoning_effort").as[String].toOption, Some("high"))
      assertEquals(body.hcursor.downField("thinking").focus, None)
    finally if server != null then server.stop(0)
  }
end AnthropicThinkingWireSpec
