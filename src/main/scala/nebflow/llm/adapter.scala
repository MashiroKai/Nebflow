package nebflow.llm

import nebflow.shared.{Message, ToolDefinition, ToolCall, StreamChunk, TokenUsage}

case class SendMessageParams(
  messages: List[Message],
  model: String,
  tools: Option[List[ToolDefinition]] = None,
  maxTokens: Option[Int] = None
)

case class AdapterResponse(
  reply: String,
  toolCalls: List[ToolCall],
  usage: Option[TokenUsage] = None
)

trait ProviderAdapter[F[_]]:
  def sendMessage(params: SendMessageParams): F[AdapterResponse]
  def sendMessageStream(params: SendMessageParams): fs2.Stream[F, StreamChunk]
