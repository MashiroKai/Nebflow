package nebflow.llm

import cats.effect.IO
import nebflow.shared.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.StreamBackend

case class SendMessageParams(
  messages: List[Message],
  model: String,
  tools: Option[List[ToolDefinition]] = None,
  thinking: Option[io.circe.Json] = None,
  /** Stable system prompt — cached by providers that support it. */
  systemStable: Option[String] = None,
  /** Dynamic system content (env info, reminders) — changes frequently. */
  systemDynamic: Option[String] = None,
  /** Session identifier for LLM provider metadata. */
  sessionId: Option[String] = None,
  /** Agent identifier for LLM provider metadata. */
  agentId: Option[String] = None,
  /**
   * WebSearch P0: provider-native search injection kind for this request
   * (resolved per-candidate in interface.scala — fallback switches provider
   * mid-request and must not carry the previous provider's injection).
   * Consumed by OpenAiAdapter; Anthropic-protocol candidates never set it.
   */
  providerSearch: Option[ProviderSearchKind] = None,
  /**
   * Output-cap clamp batch (2026-10-21): the candidate's effective context
   * window (`ModelCandidate.contextWindow` — already min(configured,
   * modelMaxContext) at the registry). AnthropicAdapter subtracts the
   * estimated input volume from it before sending `max_tokens`, so a 128000
   * output allowance is never handed to an endpoint whose window must also
   * hold the input. None = clamp dormant (sends `Defaults.MaxTokens`
   * verbatim); auxiliary faces that pin `maxTokensOverride` need not set it.
   */
  contextWindow: Option[Int] = None,
  /**
   * Output-cap clamp batch (2026-10-21): engine-internal override for
   * auxiliary faces — health probe / small-judge / allocator pin
   * `Defaults.ProbeMaxTokens` (16384, the pre-raise serving value) so their
   * single-token / tiny-JSON calls keep the years-proven small request shape.
   * Sent verbatim, NO clamp leg (already known-safe; keeps probes identical to
   * pre-batch behaviour on tiny-window configs). None = the serving-face
   * derivation (`Defaults.MaxTokens` + thinking raise + per-model clamp).
   */
  maxTokensOverride: Option[Int] = None,
  /**
   * Hard-recovery P1 (2026-09-07, 设计 D-1 方案 A): per-attempt backend for
   * STREAMING requests — a dedicated HttpClient whose shutdownNow() aborts
   * exactly this request (the only primitive proven to unblock a parked body
   * read). None = use the adapter's shared backend (legacy behavior; also the
   * non-streaming sendMessage path).
   */
  attemptBackend: Option[StreamBackend[IO, Fs2Streams[IO]]] = None,
  /**
   * thinking-config 批（2026-10-28，设计 §4.2）：按候选解析好的思考语义
   * （能力类 + 档位 + GLM 保留式思考位）。None（辅助面：probe / judge /
   * allocator 等）= adapter 按模型名家族启发取能力类（`ThinkingFamily
   * .forModel`）——启发式只影响缺省。`thinking` 字段仍是语义载体
   * （enabled → budget JSON；off → `Json.Null`）；wire 形态翻译单点 =
   * `AnthropicAdapter.wireThinkingFragment`。
   */
  thinkingResolved: Option[ResolvedThinking] = None
)

/**
 * 按候选解析后的思考语义（interface.scala 每候选单点解析：model 层 >
 * provider 层 > 全局，`ThinkingMode.resolveEffective`；能力类 = model
 * `thinkingKind` > provider `thinkingKind` > 家族启发）。
 *
 * @param enabled
 *   三层解析后的开关位。只能降不能升：全局 master off（语义载体 Null）不可被
 *   layer 复活（`AnthropicAdapter.wireThinkingOn` 保守语义）——本位只表达
 *   「layer 显式关」这条腿。
 */
case class ResolvedThinking(
  kind: ThinkingKind,
  enabled: Boolean = true,
  effort: Option[ThinkingEffort] = None,
  clearThinking: Option[Boolean] = None
)

case class AdapterResponse(
  reply: String,
  toolCalls: List[ToolCall],
  usage: Option[TokenUsage] = None,
  /**
   * WebSearch P0: structured provider search results (zhipu `web_search` /
   * qwen `search_info` response field), when the provider returned them.
   */
  searchInfo: Option[io.circe.Json] = None
)

trait ProviderAdapter[F[_]]:
  def sendMessage(params: SendMessageParams): F[AdapterResponse]
  def sendMessageStream(params: SendMessageParams): fs2.Stream[F, StreamChunk]
