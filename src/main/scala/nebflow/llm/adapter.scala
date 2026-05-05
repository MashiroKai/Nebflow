package nebflow.llm

import nebflow.shared.*

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
  agentId: Option[String] = None
)

case class AdapterResponse(
  reply: String,
  toolCalls: List[ToolCall],
  usage: Option[TokenUsage] = None
)

trait ProviderAdapter[F[_]]:
  def sendMessage(params: SendMessageParams): F[AdapterResponse]
  def sendMessageStream(params: SendMessageParams): fs2.Stream[F, StreamChunk]
