package nebscala.shared

import io.circe.{Json, JsonObject, Encoder, Decoder, DecodingFailure}
import io.circe.syntax.*

// ===== Content Blocks =====

sealed trait ContentBlock:
  def `type`: String

object ContentBlock:
  case class Text(text: String) extends ContentBlock:
    val `type` = "text"
  case class Image(data: String, mediaType: String) extends ContentBlock:
    val `type` = "image"
  case class ToolUse(id: String, name: String, input: JsonObject) extends ContentBlock:
    val `type` = "tool_use"
  case class ToolResult(toolUseId: String, content: String, isError: Option[Boolean] = None) extends ContentBlock:
    val `type` = "tool_result"

// ===== Message =====

enum MessageRole:
  case System, User, Assistant

  def name: String = this match
    case System => "system"
    case User => "user"
    case Assistant => "assistant"

object MessageRole:
  def fromString(s: String): Option[MessageRole] = s match
    case "system" => Some(System)
    case "user" => Some(User)
    case "assistant" => Some(Assistant)
    case _ => None

case class Message(role: MessageRole, content: Either[String, List[ContentBlock]]):
  def textContent: String = content match
    case Left(text) => text
    case Right(blocks) =>
      blocks.collect { case ContentBlock.Text(t) => t }.mkString("\n")

// ===== Tool Definition =====

case class ToolDefinition(
  name: String,
  description: String,
  inputSchema: JsonObject
)

// ===== Tool Call =====

case class ToolCall(id: String, name: String, input: JsonObject)

// ===== LLM =====

case class FallbackStep(
  providerId: String,
  model: String,
  reason: Option[String],
  durationMs: Long
)

case class LlmRequest(
  messages: List[Message],
  sessionId: String,
  agentId: String,
  tools: Option[List[ToolDefinition]] = None,
  maxTokens: Option[Int] = None
)

case class TokenUsage(
  inputTokens: Int,
  outputTokens: Int,
  cacheReadTokens: Option[Int] = None,
  cacheWriteTokens: Option[Int] = None
)

case class LlmMeta(
  sessionId: String,
  agentId: String,
  providerId: String,
  model: String,
  durationMs: Long,
  fallbackChain: Option[List[FallbackStep]] = None
)

case class LlmResponse(
  reply: String,
  toolCalls: List[ToolCall],
  usage: Option[TokenUsage],
  meta: LlmMeta
)

case class LlmOptions(configPath: Option[String] = None)

// ===== Stream Chunk =====

sealed trait StreamChunk
object StreamChunk:
  case class TextDelta(delta: String) extends StreamChunk
  case class ToolCallChunk(toolCall: ToolCall) extends StreamChunk
  case class Done(stopReason: Option[String], usage: Option[TokenUsage], meta: Option[LlmMeta] = None) extends StreamChunk

// ===== LLM Handle =====

trait LlmHandle[F[_]]:
  def send(req: LlmRequest): F[LlmResponse]
  def sendStream(req: LlmRequest): fs2.Stream[F, StreamChunk]

// ===== Circe Codecs =====

given Encoder[MessageRole] = Encoder.encodeString.contramap(_.name)
given Decoder[MessageRole] = Decoder.decodeString.emap(s =>
  MessageRole.fromString(s).toRight(s"Unknown role: $s")
)

given Encoder[ContentBlock] = Encoder.instance {
  case ContentBlock.Text(text) => Json.obj("type" -> "text".asJson, "text" -> text.asJson)
  case ContentBlock.Image(data, mediaType) => Json.obj("type" -> "image".asJson, "data" -> data.asJson, "mediaType" -> mediaType.asJson)
  case ContentBlock.ToolUse(id, name, input) => Json.obj("type" -> "tool_use".asJson, "id" -> id.asJson, "name" -> name.asJson, "input" -> Json.fromJsonObject(input))
  case ContentBlock.ToolResult(toolUseId, content, isError) =>
    val base = Json.obj("type" -> "tool_result".asJson, "toolUseId" -> toolUseId.asJson, "content" -> content.asJson)
    isError.fold(base)(e => base.deepMerge(Json.obj("isError" -> e.asJson)))
}

given Decoder[ContentBlock] = Decoder.instance { cursor =>
  cursor.downField("type").as[String].flatMap {
    case "text" => cursor.downField("text").as[String].map(ContentBlock.Text(_))
    case "image" => for { data <- cursor.downField("data").as[String]; mt <- cursor.downField("mediaType").as[String] } yield ContentBlock.Image(data, mt)
    case "tool_use" => for { id <- cursor.downField("id").as[String]; name <- cursor.downField("name").as[String]; input <- cursor.downField("input").as[JsonObject] } yield ContentBlock.ToolUse(id, name, input)
    case "tool_result" => for { id <- cursor.downField("toolUseId").as[String]; content <- cursor.downField("content").as[String]; isError <- cursor.downField("isError").as[Option[Boolean]] } yield ContentBlock.ToolResult(id, content, isError)
    case other => Left(DecodingFailure(s"Unknown content block type: $other", cursor.history))
  }
}

given Encoder[Message] = Encoder.instance { msg =>
  msg.content match
    case Left(text) => Json.obj("role" -> msg.role.name.asJson, "content" -> text.asJson)
    case Right(blocks) => Json.obj("role" -> msg.role.name.asJson, "content" -> blocks.asJson, "blocks" -> true.asJson)
}

given Decoder[Message] = Decoder.instance { cursor =>
  for
    role <- cursor.downField("role").as[String].flatMap(s => MessageRole.fromString(s).toRight(DecodingFailure(s"Unknown role: $s", cursor.history)))
    isBlocks <- cursor.downField("blocks").as[Option[Boolean]]
    content <- isBlocks match
      case Some(true) => cursor.downField("content").as[List[ContentBlock]].map(Right(_))
      case _ => cursor.downField("content").as[String].map(Left(_))
  yield Message(role, content)
}
