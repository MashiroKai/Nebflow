package nebflow.gateway

import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json}
import nebflow.shared.*
import nebflow.shared.given

object GatewayCodecs:
  // ----- enums -----
  given Encoder[FailoverReason] = Encoder.encodeString.contramap(_.toString)
  given Encoder[ErrorPermanence] = Encoder.encodeString.contramap(_.toString)

  // ----- protocol case classes -----
  given Encoder[ToolDefinition] = deriveEncoder
  given Decoder[ToolDefinition] = deriveDecoder

  given Encoder[ToolCall] = deriveEncoder
  given Decoder[ToolCall] = deriveDecoder

  given Encoder[FallbackStep] = deriveEncoder
  given Decoder[FallbackStep] = deriveDecoder

  given Encoder[TokenUsage] = deriveEncoder
  given Decoder[TokenUsage] = deriveDecoder

  given Encoder[LlmMeta] = deriveEncoder
  given Decoder[LlmMeta] = deriveDecoder

  given Encoder[LlmRequest] = deriveEncoder
  given Decoder[LlmRequest] = deriveDecoder

  given Encoder[LlmResponse] = deriveEncoder

  given Encoder[FallbackAttempt] = deriveEncoder

  // ----- stream chunk (server-only) -----
  given Encoder[StreamChunk] = Encoder.instance {
    case StreamChunk.TextDelta(delta) =>
      Json.obj(
        "type" -> "textDelta".asJson,
        "delta" -> delta.asJson
      )
    case StreamChunk.ThinkingDelta(delta) =>
      Json.obj(
        "type" -> "thinkingDelta".asJson,
        "delta" -> delta.asJson
      )
    case StreamChunk.ToolCallStart(name) =>
      Json.obj(
        "type" -> "toolCallStart".asJson,
        "name" -> name.asJson
      )
    case StreamChunk.ToolCallChunk(toolCall) =>
      Json.obj(
        "type" -> "toolCall".asJson,
        "toolCall" -> toolCall.asJson
      )
    case StreamChunk.Done(stopReason, usage, meta, _) =>
      Json.obj(
        "type" -> "done".asJson,
        "stopReason" -> stopReason.asJson,
        "usage" -> usage.asJson,
        "meta" -> meta.asJson
      )
  }
end GatewayCodecs
