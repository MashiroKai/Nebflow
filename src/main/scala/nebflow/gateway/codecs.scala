package nebflow.gateway

import nebflow.shared.{
  ToolDefinition, ToolCall, FallbackStep, TokenUsage, LlmMeta,
  LlmRequest, LlmResponse, StreamChunk,
}
import nebflow.shared.given
import nebflow.llm.{FallbackAttempt, FailoverReason, ErrorPermanence}

import io.circe.{Encoder, Decoder, Json}
import io.circe.generic.semiauto.{deriveEncoder, deriveDecoder}
import io.circe.syntax.*

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
        "type"  -> "textDelta".asJson,
        "delta" -> delta.asJson,
      )
    case StreamChunk.ToolCallChunk(toolCall) =>
      Json.obj(
        "type"     -> "toolCall".asJson,
        "toolCall" -> toolCall.asJson,
      )
    case StreamChunk.Done(stopReason, usage, meta) =>
      Json.obj(
        "type"       -> "done".asJson,
        "stopReason" -> stopReason.asJson,
        "usage"      -> usage.asJson,
        "meta"       -> meta.asJson,
      )
  }
