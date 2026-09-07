package nebflow.llm

import cats.effect.IO
import nebflow.shared.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.StreamBackend

case class SendMessageParams(
  messages: List[Message],
  model: String,
  tools: Option[List[ToolDefinition]] = None,
  maxTokens: Option[Int] = None,
  thinking: Option[io.circe.Json] = None,
  /** Stable system prompt — cached by providers that support it. */
  systemStable: Option[String] = None,
  /** Dynamic system content (env info, reminders) — changes frequently. */
  systemDynamic: Option[String] = None,
  /** Session identifier for LLM provider metadata. */
  sessionId: Option[String] = None,
  /** Agent identifier for LLM provider metadata. */
  agentId: Option[String] = None,
  /** WebSearch P0: provider-native search injection kind for this request
    * (resolved per-candidate in interface.scala — fallback switches provider
    * mid-request and must not carry the previous provider's injection).
    * Consumed by OpenAiAdapter; Anthropic-protocol candidates never set it. */
  providerSearch: Option[ProviderSearchKind] = None,
  /** Hard-recovery P1 (2026-09-07, 设计 D-1 方案 A): per-attempt backend for
    * STREAMING requests — a dedicated HttpClient whose shutdownNow() aborts
    * exactly this request (the only primitive proven to unblock a parked body
    * read). None = use the adapter's shared backend (legacy behavior; also the
    * non-streaming sendMessage path). */
  attemptBackend: Option[StreamBackend[IO, Fs2Streams[IO]]] = None
)

case class AdapterResponse(
  reply: String,
  toolCalls: List[ToolCall],
  usage: Option[TokenUsage] = None,
  /** WebSearch P0: structured provider search results (zhipu `web_search` /
    * qwen `search_info` response field), when the provider returned them. */
  searchInfo: Option[io.circe.Json] = None
)

trait ProviderAdapter[F[_]]:
  def sendMessage(params: SendMessageParams): F[AdapterResponse]
  def sendMessageStream(params: SendMessageParams): fs2.Stream[F, StreamChunk]
