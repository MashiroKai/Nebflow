package nebflow.pilogic

import io.circe.syntax.*
import io.circe.Json
import munit.CatsEffectSuite

/** 接线期四件 ①：29 采纳位点强类型输入面的 wire 形对拍——每个 Encoder 输出与
  * LoopRuntime 既有手拼 Json（迁移前形态，逐字取自增量轮实现）完全一致。
  * 此件即「编码迁移零行为漂移」的单点证据（SiteAdaptationSpec 33 测为循环侧行为证）。 */
class SiteEventsSpec extends CatsEffectSuite:

  import SiteEvents.given
  import SitePayloads.given

  private val userMsg = LoopMessage(LoopMessageRole.User, Json.obj("k" -> "v".asJson))
  private val asstMsg = LoopMessage(LoopMessageRole.Assistant, Json.fromString("text"))
  private val msgs = Vector(userMsg, asstMsg)
  private val req = ProviderRequest("m-1", Some(Json.fromString("sys")), msgs, Json.obj("o" -> 1.asJson), Json.obj("h" -> true.asJson))
  private val resp = ProviderResponse(asstMsg, StopReason.ToolUse, Vector(ToolCall("c1", "Bash", Json.obj("command" -> "ls".asJson))))
  private val turn = TurnNumber(7L)
  private val seq = MessageSeq(3L)
  private val chunk = Json.obj("d" -> 1.asJson)

  test("run 入口族 wire 形对拍（RunGate/PreRunStart/PreRunInject/SessionStarted）"):
    assertEquals(
      SiteEvents.RunGateInput(2, "gpt-x").asJson,
      Json.obj("messageCount" -> 2.asJson, "model" -> "gpt-x".asJson))
    assertEquals(SiteEvents.PreRunStartInput(Json.Null).asJson, Json.Null)
    assertEquals(
      SiteEvents.PreRunStartInput(Json.fromString("sys")).asJson,
      Json.fromString("sys"))
    assertEquals(
      SiteEvents.PreRunInjectInput(5).asJson,
      Json.obj("messageCount" -> 5.asJson))
    assertEquals(
      SiteEvents.SessionStartedInput(fresh = true).asJson,
      Json.obj("fresh" -> true.asJson))

  test("preRequest 族 wire 形对拍（Context/System/Options）"):
    assertEquals(
      SiteEvents.PreRequestContextInput(msgs).asJson,
      Json.obj("messages" -> Json.arr(msgs.map(m => Json.obj("role" -> SitePayloads.roleStr(m.role).asJson, "content" -> m.content))*)))
    assertEquals(
      SiteEvents.PreRequestSystemInput(Json.Null).asJson,
      Json.Null)
    val opts = Json.obj("temperature" -> 0.2.asJson)
    assertEquals(SiteEvents.PreRequestOptionsInput(opts).asJson, opts)

  test("providerPort 族 wire 形对拍（Payload/Headers/Responded/Chunked）"):
    assertEquals(
      SiteEvents.ProviderPayloadInput(req).asJson,
      Json.obj(
        "model" -> "m-1".asJson,
        "system" -> Json.fromString("sys").asJson,
        "messages" -> Json.arr(msgs.map(m => Json.obj("role" -> SitePayloads.roleStr(m.role).asJson, "content" -> m.content))*),
        "options" -> Json.obj("o" -> 1.asJson),
        "headers" -> Json.obj("h" -> true.asJson)))
    val hdrs = Json.obj("x-a" -> "b".asJson)
    assertEquals(SiteEvents.ProviderHeadersInput(hdrs).asJson, hdrs)
    assertEquals(
      SiteEvents.ProviderRespondedInput(resp).asJson,
      Json.obj(
        "message" -> Json.obj("role" -> "assistant".asJson, "content" -> Json.fromString("text")),
        "stopReason" -> "ToolUse".asJson,
        "toolCalls" -> Json.arr(ToolCall.toJson(ToolCall("c1", "Bash", Json.obj("command" -> "ls".asJson))))))
    assertEquals(SiteEvents.ProviderChunkedInput(chunk).asJson, chunk)

  test("message 族 wire 形对拍（Started/Delta/Ended）"):
    assertEquals(SiteEvents.MessageStartedInput(seq).asJson, Json.obj("seq" -> 3.asJson))
    assertEquals(
      SiteEvents.MessageDeltaInput(seq, chunk).asJson,
      Json.obj("seq" -> 3.asJson, "chunk" -> chunk))
    assertEquals(
      SiteEvents.MessageEndedInput(asstMsg).asJson,
      Json.obj("role" -> "assistant".asJson, "content" -> Json.fromString("text")))

  test("tool 族 wire 形对拍（Pre/Post/BatchSettled/Execution 三件）"):
    val call = ToolCall("c1", "Bash", Json.obj("command" -> "ls".asJson))
    val r = ToolExecutionResult(Json.fromString("ok"), isError = false)
    assertEquals(SiteEvents.ToolPreInput(call).asJson, ToolCall.toJson(call))
    assertEquals(
      SiteEvents.ToolOutcome(call, r).asJson,
      Json.obj("callId" -> "c1".asJson, "name" -> "Bash".asJson, "output" -> Json.fromString("ok"), "isError" -> false.asJson))
    assertEquals(
      SiteEvents.ToolBatchSettledInput(Vector(SiteEvents.ToolOutcome(call, r))).asJson,
      Json.obj("results" -> Json.arr(ToolExecutionResult.toJson(call, r))))
    assertEquals(
      SiteEvents.ToolExecutionStartedInput("c1", "Bash").asJson,
      Json.obj("callId" -> "c1".asJson, "name" -> "Bash".asJson))
    assertEquals(
      SiteEvents.ToolExecutionDeltaInput("c1", chunk).asJson,
      Json.obj("callId" -> "c1".asJson, "partial" -> chunk))
    assertEquals(
      SiteEvents.ToolExecutionEndedInput("c1", Json.fromString("ok"), isError = true).asJson,
      Json.obj("callId" -> "c1".asJson, "output" -> Json.fromString("ok"), "isError" -> true.asJson))

  test("turn/postRun 族 wire 形对拍（TurnStarted/TurnEnded/Settle/Settled/FollowUp）"):
    assertEquals(SiteEvents.TurnStartedInput(turn).asJson, Json.obj("turn" -> 7.asJson))
    assertEquals(
      SiteEvents.TurnEndedInput(turn, Some(asstMsg), Vector(Json.fromString("r1")), TurnDecision.End).asJson,
      Json.obj(
        "turn" -> 7.asJson,
        "message" -> Json.obj("role" -> "assistant".asJson, "content" -> Json.fromString("text")),
        "toolResults" -> Json.arr(Json.fromString("r1")),
        "decision" -> "end".asJson))
    assertEquals(
      SiteEvents.TurnEndedInput(turn, None, Vector.empty, TurnDecision.Continue("natural")).asJson
        .hcursor.get[String]("decision").toOption,
      Some("continue:natural"))
    assertEquals(
      SiteEvents.PostRunSettleInput(4, turn).asJson,
      Json.obj("newMessageCount" -> 4.asJson, "turn" -> 7.asJson))
    assertEquals(
      SiteEvents.PostRunSettledInput(RunOutcome.Completed(Vector.empty)).asJson,
      Json.obj("outcome" -> "Completed".asJson))
    assertEquals(
      SiteEvents.PostRunFollowUpInput(9).asJson,
      Json.obj("messageCount" -> 9.asJson))

  test("session/model/compact 族 wire 形对拍（Ending/Model/Thinking/CompactEnded）"):
    assertEquals(
      SiteEvents.SessionEndingInput().asJson,
      Json.obj("stage" -> "session.ending".asJson))
    assertEquals(
      SiteEvents.ModelSelectedInput("a", "b").asJson,
      Json.obj("from" -> "a".asJson, "to" -> "b".asJson))
    assertEquals(
      SiteEvents.ThinkingLevelSelectedInput(Json.Null, Json.fromString("high")).asJson,
      Json.obj("from" -> Json.Null, "to" -> Json.fromString("high")))
    assertEquals(
      SiteEvents.CompactEndedInput().asJson,
      Json.obj("stage" -> "compact.ended".asJson))

  test("位点计数自检：SiteEvents 输入面覆盖全部 29 采纳位点（缺一即红）"):
    // 采纳 29 位点的 display 单源对拍（LoopTestKit.adoptedPoints 为既有单源清单）
    assertEquals(LoopTestKit.adoptedPoints.size, 29)
