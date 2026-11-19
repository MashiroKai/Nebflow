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
    case CandidateSkipCause.HealthDown(d) => Json.obj("type" -> "health-down".asJson, "detail" -> d.asJson)
    case CandidateSkipCause.SoftAvoid(ms, l) =>
      Json.obj("type" -> "soft-avoid".asJson, "remainingMs" -> ms.asJson, "label" -> l.asJson)
    case CandidateSkipCause.ProviderFull(u, c) =>
      Json.obj("type" -> "provider-full".asJson, "inUse" -> u.asJson, "cap" -> c.asJson)
    case CandidateSkipCause.ScheduleWindow(w) => Json.obj("type" -> "schedule-window".asJson, "window" -> w.asJson)
    case CandidateSkipCause.NotConfigured(d) => Json.obj("type" -> "not-configured".asJson, "detail" -> d.asJson)
    case CandidateSkipCause.SilentExclusion => Json.obj("type" -> "silent-exclusion".asJson)
  }

  given Decoder[CandidateSkipCause] = Decoder.instance { c =>
    c.downField("type").as[String].flatMap {
      case "health-down" => c.downField("detail").as[String].map(CandidateSkipCause.HealthDown.apply)
      case "soft-avoid" =>
        for
          ms <- c.downField("remainingMs").as[Long]
          l <- c.downField("label").as[String]
        yield CandidateSkipCause.SoftAvoid(ms, l)
      case "provider-full" =>
        for
          u <- c.downField("inUse").as[Int]
          cap <- c.downField("cap").as[Int]
        yield CandidateSkipCause.ProviderFull(u, cap)
      case "schedule-window" => c.downField("window").as[String].map(CandidateSkipCause.ScheduleWindow.apply)
      case "not-configured" => c.downField("detail").as[String].map(CandidateSkipCause.NotConfigured.apply)
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

  // The in-tree decode face treats every defaulted LlmRequest field as OPTIONAL
  // on the wire (searchAllowed -> true, pinSkips -> Nil, requestOnlyContext ->
  // Nil, requestId -> None). The semiauto derivation ignores Scala defaults, so
  // each later-added defaulted field silently became wire-required and pre-existing
  // minimal inbound bodies (no pinSkips) failed the /v1/chat decode with a 500.
  // messages / sessionId / agentId stay required; the engine-internal wire form
  // keeps the full-field deriveEncoder above.
  given Decoder[LlmRequest] = Decoder.instance { c =>
    for
      messages <- c.downField("messages").as[List[Message]]
      sessionId <- c.downField("sessionId").as[String]
      agentId <- c.downField("agentId").as[String]
      tools <- c.downField("tools").as[Option[List[ToolDefinition]]]
      thinking <- c.downField("thinking").as[Option[Json]]
      systemStable <- c.downField("systemStable").as[Option[String]]
      systemDynamic <- c.downField("systemDynamic").as[Option[String]]
      agentModel <- c.downField("agentModel").as[Option[AgentModelConfig]]
      searchAllowed <- c.downField("searchAllowed").as[Option[Boolean]]
      pinSkips <- c.downField("pinSkips").as[Option[List[(String, CandidateSkipCause)]]]
      requestOnlyContext <- c.downField("requestOnlyContext").as[Option[List[Message]]]
      requestId <- c.downField("requestId").as[Option[String]]
    yield LlmRequest(
      messages = messages,
      sessionId = sessionId,
      agentId = agentId,
      tools = tools,
      thinking = thinking,
      systemStable = systemStable,
      systemDynamic = systemDynamic,
      agentModel = agentModel,
      searchAllowed = searchAllowed.getOrElse(true),
      pinSkips = pinSkips.getOrElse(Nil),
      requestOnlyContext = requestOnlyContext.getOrElse(Nil),
      requestId = requestId
    )
  }

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
