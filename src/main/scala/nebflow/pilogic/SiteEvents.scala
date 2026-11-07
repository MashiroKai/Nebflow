package nebflow.pilogic

import io.circe.syntax.*
import io.circe.{Encoder, Json}

/** 接线期四件 ①：29 采纳位点的**强类型输入面**（D-2 收敛）。
  *
  * 范围裁定（诚实收敛）：位点入参从「LoopRuntime 手拼 Json」升级为逐位点 case class +
  * Encoder——schema 单源、引擎侧与接线侧共用，拼写/字段名错误编译期可见。
  * `Interceptor/Observer` 接口保持 Json 载体：hook 边界是 wire 协议（外源输出无法
  * 编译期类型化），守卫（decode 失败 ⇒ 弃改写 + HookErrored）语义不变——「Json+守卫」
  * 在 hook 侧是必要的运行期校验，强类型化落在**生产者（引擎构造）与消费者（接线解码）**
  * 两侧，此即 D-2 在本轮的收敛范围。
  *
  * wire 形逐字节对齐：每个 Encoder 的输出与 LoopRuntime 既有手拼 Json（及
  * SiteAdaptationSpec 的 33 项断言）完全一致——编码迁移零行为漂移，72 测零回归即证。 */

private[pilogic] object SiteEvents:

  import SitePayloads.given

  // ── run 入口族 ───────────────────────────────────────────────────────────

  /** run.Gate（#42）。 */
  final case class RunGateInput(messageCount: Int, model: String)
  given runGateEncoder: Encoder[RunGateInput] = i => Json.obj(
    "messageCount" -> i.messageCount.asJson,
    "model" -> i.model.asJson,
  )

  /** preRun.Start（#19）：system 现值（Null=无）。 */
  final case class PreRunStartInput(system: Json)
  given preRunStartEncoder: Encoder[PreRunStartInput] = i => i.system

  /** preRun.Inject（#41）。 */
  final case class PreRunInjectInput(messageCount: Int)
  given preRunInjectEncoder: Encoder[PreRunInjectInput] = i =>
    Json.obj("messageCount" -> i.messageCount.asJson)

  // ── preRequest 族 ────────────────────────────────────────────────────────

  /** preRequest.Context（#13/#44/#53）。 */
  final case class PreRequestContextInput(messages: Vector[LoopMessage])
  given preRequestContextEncoder: Encoder[PreRequestContextInput] = i =>
    Json.obj("messages" -> i.messages.asJson)

  /** preRequest.System（#14/#52）：system 现值（Null=无）。 */
  final case class PreRequestSystemInput(system: Json)
  given preRequestSystemEncoder: Encoder[PreRequestSystemInput] = i => i.system

  /** preRequest.Options（#45）：options 基线/现值。 */
  final case class PreRequestOptionsInput(options: Json)
  given preRequestOptionsEncoder: Encoder[PreRequestOptionsInput] = i => i.options

  // ── providerPort 族 ──────────────────────────────────────────────────────

  /** providerPort.Payload（#15/#46）：整块请求。 */
  final case class ProviderPayloadInput(request: ProviderRequest)
  given providerPayloadEncoder: Encoder[ProviderPayloadInput] = i => i.request.asJson

  /** providerPort.Headers（#16 改造位）。 */
  final case class ProviderHeadersInput(headers: Json)
  given providerHeadersEncoder: Encoder[ProviderHeadersInput] = i => i.headers

  /** providerPort.Responded（#17/#54）：终响应（message/stopReason/toolCalls）。 */
  final case class ProviderRespondedInput(response: ProviderResponse)
  given providerRespondedEncoder: Encoder[ProviderRespondedInput] = i =>
    SitePayloads.responseJson(i.response)

  /** providerPort.Chunked（#18，L-11 Dropping）：单流块增量。 */
  final case class ProviderChunkedInput(delta: Json)
  given providerChunkedEncoder: Encoder[ProviderChunkedInput] = i => i.delta

  // ── message 族 ───────────────────────────────────────────────────────────

  /** message.Started（#28）。 */
  final case class MessageStartedInput(seq: MessageSeq)
  given messageStartedEncoder: Encoder[MessageStartedInput] = i =>
    Json.obj("seq" -> i.seq.value.asJson)

  /** message.Delta（#29）。 */
  final case class MessageDeltaInput(seq: MessageSeq, chunk: Json)
  given messageDeltaEncoder: Encoder[MessageDeltaInput] = i =>
    Json.obj("seq" -> i.seq.value.asJson, "chunk" -> i.chunk)

  /** message.Ended（#30/#47）：定稿消息。 */
  final case class MessageEndedInput(message: LoopMessage)
  given messageEndedEncoder: Encoder[MessageEndedInput] = i => i.message.asJson

  // ── tool 族 ──────────────────────────────────────────────────────────────

  /** tool.Pre（#36/#48/#56/#68 合并位）。 */
  final case class ToolPreInput(call: ToolCall)
  given toolPreEncoder: Encoder[ToolPreInput] = i => ToolCall.toJson(i.call)

  /** tool.Post（#37/#49/#57/#69 合并位）：单调用结果。 */
  final case class ToolOutcome(call: ToolCall, result: ToolExecutionResult)
  given toolOutcomeEncoder: Encoder[ToolOutcome] = o =>
    ToolExecutionResult.toJson(o.call, o.result)

  /** tool.BatchSettled（#58）：一批结果。 */
  final case class ToolBatchSettledInput(results: Vector[ToolOutcome])
  given toolBatchSettledEncoder: Encoder[ToolBatchSettledInput] = i =>
    Json.obj("results" -> Json.arr(i.results.map(o => ToolExecutionResult.toJson(o.call, o.result))*))

  /** toolExecution.Started（#31）。 */
  final case class ToolExecutionStartedInput(callId: String, name: String)
  given toolExecutionStartedEncoder: Encoder[ToolExecutionStartedInput] = i =>
    Json.obj("callId" -> i.callId.asJson, "name" -> i.name.asJson)

  /** toolExecution.Delta（#32）。 */
  final case class ToolExecutionDeltaInput(callId: String, partial: Json)
  given toolExecutionDeltaEncoder: Encoder[ToolExecutionDeltaInput] = i =>
    Json.obj("callId" -> i.callId.asJson, "partial" -> i.partial)

  /** toolExecution.Ended（#33）。 */
  final case class ToolExecutionEndedInput(callId: String, output: Json, isError: Boolean)
  given toolExecutionEndedEncoder: Encoder[ToolExecutionEndedInput] = i =>
    Json.obj("callId" -> i.callId.asJson, "output" -> i.output, "isError" -> i.isError.asJson)

  // ── turn 族 ──────────────────────────────────────────────────────────────

  /** turn.Started（#26）。 */
  final case class TurnStartedInput(turn: TurnNumber)
  given turnStartedEncoder: Encoder[TurnStartedInput] = i =>
    Json.obj("turn" -> i.turn.value.asJson)

  /** turn.Ended（#27）：决策载荷（decision=上游 finishTurn 决策）。 */
  final case class TurnEndedInput(
    turn: TurnNumber,
    message: Option[LoopMessage],
    toolResults: Vector[Json],
    decision: TurnDecision,
  )
  given turnEndedEncoder: Encoder[TurnEndedInput] = i => Json.obj(
    "turn" -> i.turn.value.asJson,
    "message" -> i.message.asJson,
    "toolResults" -> i.toolResults.asJson,
    "decision" -> (i.decision match
      case TurnDecision.End           => "end"
      case TurnDecision.Continue(why) => s"continue:$why").asJson,
  )

  // ── postRun 族 ───────────────────────────────────────────────────────────

  /** postRun.Settle（#22/#55）。 */
  final case class PostRunSettleInput(newMessageCount: Int, turn: TurnNumber)
  given postRunSettleEncoder: Encoder[PostRunSettleInput] = i => Json.obj(
    "newMessageCount" -> i.newMessageCount.asJson,
    "turn" -> i.turn.value.asJson,
  )

  /** postRun.Settled（#23）：终局通知（outcome=收尾态简单名）。 */
  final case class PostRunSettledInput(outcome: RunOutcome)
  given postRunSettledEncoder: Encoder[PostRunSettledInput] = i =>
    Json.obj("outcome" -> i.outcome.getClass.getSimpleName.asJson)

  /** postRun.FollowUp（#43）。 */
  final case class PostRunFollowUpInput(messageCount: Int)
  given postRunFollowUpEncoder: Encoder[PostRunFollowUpInput] = i =>
    Json.obj("messageCount" -> i.messageCount.asJson)

  // ── session/model/compact 族 ─────────────────────────────────────────────

  /** session.Started（#3；D-3：空上下文=新会话等价 ⇒ fresh=true）。 */
  final case class SessionStartedInput(fresh: Boolean)
  given sessionStartedEncoder: Encoder[SessionStartedInput] = i =>
    Json.obj("fresh" -> i.fresh.asJson)

  /** session.Ending（#10；SessionStages 接线层触发）。 */
  final case class SessionEndingInput(stage: String = "session.ending")
  given sessionEndingEncoder: Encoder[SessionEndingInput] = i =>
    Json.obj("stage" -> i.stage.asJson)

  /** model.Selected（#34）。 */
  final case class ModelSelectedInput(from: String, to: String)
  given modelSelectedEncoder: Encoder[ModelSelectedInput] = i =>
    Json.obj("from" -> i.from.asJson, "to" -> i.to.asJson)

  /** thinkingLevel.Selected（#35）。 */
  final case class ThinkingLevelSelectedInput(from: Json, to: Json)
  given thinkingLevelSelectedEncoder: Encoder[ThinkingLevelSelectedInput] = i =>
    Json.obj("from" -> i.from.asJson, "to" -> i.to.asJson)

  /** compact.Ended（#8 观察级；SessionStages 接线层触发）。 */
  final case class CompactEndedInput(stage: String = "compact.ended")
  given compactEndedEncoder: Encoder[CompactEndedInput] = i =>
    Json.obj("stage" -> i.stage.asJson)

end SiteEvents
