package nebflow.shared

import io.circe.*
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

  case class Thinking(thinking: String, signature: Option[String] = None) extends ContentBlock:
    val `type` = "thinking"

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
  maxTokens: Option[Int] = None,
  thinking: Option[io.circe.Json] = None,
  /** Stable system prompt (e.g. system.md) — can be cached by the provider. */
  systemStable: Option[String] = None,
  /** Dynamic system content (env info, reminders) — changes frequently, placed after cache breakpoint. */
  systemDynamic: Option[String] = None
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
  case class ThinkingDelta(delta: String) extends StreamChunk
  case class ToolCallChunk(toolCall: ToolCall) extends StreamChunk

  case class Done(stopReason: Option[String], usage: Option[TokenUsage], meta: Option[LlmMeta] = None)
      extends StreamChunk

// ===== LLM Handle =====

trait LlmHandle[F[_]]:
  def send(req: LlmRequest): F[LlmResponse]

  def sendStream(
    req: LlmRequest,
    onAttempt: Option[FallbackAttempt => cats.effect.IO[Unit]] = None
  ): fs2.Stream[F, StreamChunk]

// ===== Circe Codecs =====

given Encoder[MessageRole] = Encoder.encodeString.contramap(_.name)
given Decoder[MessageRole] = Decoder.decodeString.emap(s => MessageRole.fromString(s).toRight(s"Unknown role: $s"))

given Encoder[ContentBlock] = Encoder.instance {
  case ContentBlock.Text(text) => Json.obj("type" -> "text".asJson, "text" -> text.asJson)
  case ContentBlock.Image(data, mediaType) =>
    Json.obj("type" -> "image".asJson, "data" -> data.asJson, "mediaType" -> mediaType.asJson)
  case ContentBlock.ToolUse(id, name, input) =>
    Json.obj(
      "type" -> "tool_use".asJson,
      "id" -> id.asJson,
      "name" -> name.asJson,
      "input" -> Json.fromJsonObject(input)
    )
  case ContentBlock.ToolResult(toolUseId, content, isError) =>
    val base = Json.obj("type" -> "tool_result".asJson, "toolUseId" -> toolUseId.asJson, "content" -> content.asJson)
    isError.fold(base)(e => base.deepMerge(Json.obj("isError" -> e.asJson)))
  case ContentBlock.Thinking(thinking, signature) =>
    val base = Json.obj("type" -> "thinking".asJson, "thinking" -> thinking.asJson)
    signature.fold(base)(s => base.deepMerge(Json.obj("signature" -> s.asJson)))
}

given Decoder[ContentBlock] = Decoder.instance { cursor =>
  cursor.downField("type").as[String].flatMap {
    case "text" => cursor.downField("text").as[String].map(ContentBlock.Text(_))
    case "image" =>
      for data <- cursor.downField("data").as[String]; mt <- cursor.downField("mediaType").as[String]
      yield ContentBlock.Image(data, mt)
    case "tool_use" =>
      for
        id <- cursor.downField("id").as[String]; name <- cursor.downField("name").as[String];
        input <- cursor.downField("input").as[JsonObject]
      yield ContentBlock.ToolUse(id, name, input)
    case "tool_result" =>
      for
        id <- cursor.downField("toolUseId").as[String]; content <- cursor.downField("content").as[String];
        isError <- cursor.downField("isError").as[Option[Boolean]]
      yield ContentBlock.ToolResult(id, content, isError)
    case "thinking" =>
      for
        thinking <- cursor.downField("thinking").as[String]
        signature <- cursor.downField("signature").as[Option[String]]
      yield ContentBlock.Thinking(thinking, signature)
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
    role <- cursor
      .downField("role")
      .as[String]
      .flatMap(s => MessageRole.fromString(s).toRight(DecodingFailure(s"Unknown role: $s", cursor.history)))
    isBlocks <- cursor.downField("blocks").as[Option[Boolean]]
    content <- isBlocks match
      case Some(true) => cursor.downField("content").as[List[ContentBlock]].map(Right(_))
      case _ => cursor.downField("content").as[String].map(Left(_))
  yield Message(role, content)
}

// ===== UI Messages (for frontend rendering) =====

sealed trait UiMessage:
  def typeName: String

object UiMessage:

  case class User(text: String, attachments: List[Json] = Nil) extends UiMessage:
    val typeName = "user"

  case class Ai(text: String, durationMs: Option[Long] = None, model: Option[String] = None) extends UiMessage:
    val typeName = "ai"

  case class Tool(
    label: String,
    summary: String,
    content: String = "",
    isError: Boolean = false,
    input: String = ""
  ) extends UiMessage:
    val typeName = "tool"

  case class Agent(agentId: String, text: String) extends UiMessage:
    val typeName = "agent"

  case class AskUser(items: List[Json]) extends UiMessage:
    val typeName = "askUser"

  case class System(content: String) extends UiMessage:
    val typeName = "system"

  given Encoder[UiMessage] = Encoder.instance {
    case m: User => Json.obj("type" -> "user".asJson, "text" -> m.text.asJson, "attachments" -> m.attachments.asJson)
    case m: Ai =>
      val base = Json.obj("type" -> "ai".asJson, "text" -> m.text.asJson)
      val withDur = m.durationMs.fold(base)(d => base.deepMerge(Json.obj("durationMs" -> d.asJson)))
      m.model.fold(withDur)(mod => withDur.deepMerge(Json.obj("model" -> mod.asJson)))
    case m: Tool =>
      Json.obj(
        "type" -> "tool".asJson,
        "label" -> m.label.asJson,
        "summary" -> m.summary.asJson,
        "content" -> m.content.asJson,
        "isError" -> m.isError.asJson,
        "input" -> m.input.asJson
      )
    case m: Agent => Json.obj("type" -> "agent".asJson, "agentId" -> m.agentId.asJson, "text" -> m.text.asJson)
    case m: AskUser => Json.obj("type" -> "askUser".asJson, "items" -> m.items.asJson)
    case m: System => Json.obj("type" -> "system".asJson, "content" -> m.content.asJson)
  }

  given Decoder[UiMessage] = Decoder.instance { cursor =>
    cursor.downField("type").as[String].flatMap {
      case "user" =>
        for
          text <- cursor.downField("text").as[String]
          atts <- cursor.downField("attachments").as[Option[List[Json]]]
        yield User(text, atts.getOrElse(Nil))
      case "ai" =>
        for
          text <- cursor.downField("text").as[String]
          durationMs <- cursor.downField("durationMs").as[Option[Long]]
          model <- cursor.downField("model").as[Option[String]]
        yield Ai(text, durationMs, model)
      case "tool" =>
        for
          label <- cursor.downField("label").as[String]
          summary <- cursor.downField("summary").as[String]
          content <- cursor.downField("content").as[Option[String]]
          isError <- cursor.downField("isError").as[Option[Boolean]]
          input <- cursor.downField("input").as[Option[String]]
        yield Tool(label, summary, content.getOrElse(""), isError.getOrElse(false), input.getOrElse(""))
      case "agent" =>
        for
          agentId <- cursor.downField("agentId").as[String]
          text <- cursor.downField("text").as[String]
        yield Agent(agentId, text)
      case "askUser" =>
        cursor.downField("items").as[List[Json]].map(AskUser(_))
      case "system" =>
        cursor.downField("content").as[String].map(System(_))
      case other => Left(DecodingFailure(s"Unknown UiMessage type: $other", cursor.history))
    }
  }
end UiMessage
