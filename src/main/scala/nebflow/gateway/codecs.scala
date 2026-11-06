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

  // ----- 候选跳过原因（缺陷批 2026-11-06）-----
  // `LlmRequest.pinSkips`（派发钉链剔除账本，PinnedChain.preExcluded 随请求下发）
  // 进了 LlmRequest 字段面 ⇒ deriveEncoder/deriveDecoder 需要全字段实例。手写
  // `{"type": wire, …参数}` 形（不用 derive 派生 enum——参数化 case 与无参 case
  // 混合的 coproduct 形状不稳定）；编解码逐字往返。实际下发只在引擎内构造
  // （AgentSessionExecution 单点）；外部入站请求缺省 Nil。
  given Encoder[CandidateSkipCause] = Encoder.instance {
    case CandidateSkipCause.HealthDown(d)      => Json.obj("type" -> "health-down".asJson, "detail" -> d.asJson)
    case CandidateSkipCause.SoftAvoid(ms, l)   =>
      Json.obj("type" -> "soft-avoid".asJson, "remainingMs" -> ms.asJson, "label" -> l.asJson)
    case CandidateSkipCause.ProviderFull(u, c) =>
      Json.obj("type" -> "provider-full".asJson, "inUse" -> u.asJson, "cap" -> c.asJson)
    case CandidateSkipCause.ScheduleWindow(w)  => Json.obj("type" -> "schedule-window".asJson, "window" -> w.asJson)
    case CandidateSkipCause.NotConfigured(d)   => Json.obj("type" -> "not-configured".asJson, "detail" -> d.asJson)
    case CandidateSkipCause.SilentExclusion    => Json.obj("type" -> "silent-exclusion".asJson)
  }

  given Decoder[CandidateSkipCause] = Decoder.instance { c =>
    c.downField("type").as[String].flatMap {
      case "health-down"      => c.downField("detail").as[String].map(CandidateSkipCause.HealthDown.apply)
      case "soft-avoid"       =>
        for
          ms <- c.downField("remainingMs").as[Long]
          l  <- c.downField("label").as[String]
        yield CandidateSkipCause.SoftAvoid(ms, l)
      case "provider-full"    =>
        for
          u   <- c.downField("inUse").as[Int]
          cap <- c.downField("cap").as[Int]
        yield CandidateSkipCause.ProviderFull(u, cap)
      case "schedule-window"  => c.downField("window").as[String].map(CandidateSkipCause.ScheduleWindow.apply)
      case "not-configured"   => c.downField("detail").as[String].map(CandidateSkipCause.NotConfigured.apply)
      case "silent-exclusion" => Right(CandidateSkipCause.SilentExclusion)
      case other => Left(io.circe.DecodingFailure(s"unknown CandidateSkipCause type: $other", c.history))
    }
  }

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
    case StreamChunk.ThinkingSignature(signature) =>
      Json.obj(
        "type" -> "thinkingSignature".asJson,
        "signature" -> signature.asJson
      )
    case StreamChunk.ToolCallStart(name) =>
      Json.obj(
        "type" -> "toolCallStart".asJson,
        "name" -> name.asJson
      )
    case StreamChunk.ToolArgDelta(toolName, delta) =>
      Json.obj(
        "type" -> "toolArgDelta".asJson,
        "toolName" -> toolName.asJson,
        "delta" -> delta.asJson
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
