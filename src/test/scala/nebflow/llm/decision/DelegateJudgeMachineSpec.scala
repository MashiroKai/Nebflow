package nebflow.llm.decision

import io.circe.syntax.*
import munit.FunSuite
import nebflow.core.delegate.DelegateJudge
import nebflow.core.delegate.DelegateJudge.Verdict

/** Pure parsing and wire-contract checks for terminal judgment. */
class DelegateJudgeMachineSpec extends FunSuite:

  // ── SmallJudge JSON 解析 ─────────────────────────────────────────────

  test("parseVerdict: bare, fenced and prose-wrapped JSON all parse; verdict+reason land") {
    assertEquals(
      DelegateJudgeMachine.parseVerdict(
        """{"verdict":"completed","reason":"work on branch, integration is a merger request"}"""
      ),
      Some(Verdict.Completed)
    )
    assertEquals(
      DelegateJudgeMachine.parseVerdict("```json\n{\"verdict\": \"completed\", \"reason\": \"\"}\n```"),
      Some(Verdict.Completed)
    )
    // #566：模型回文 "blocked" → 注记形 completed（reason 进注记，不再是状态）。
    assertEquals(
      DelegateJudgeMachine.parseVerdict(
        "The judge says: {\"verdict\":\"blocked\",\"reason\":\"merge conflict in a.scala\"} (end)"
      ),
      Some(Verdict.CompletedNote("judge: merge conflict in a.scala"))
    )
    assertEquals(
      DelegateJudgeMachine.parseVerdict("{\"verdict\":\"blocked\"}"),
      Some(Verdict.CompletedNote("judge: could not complete — substance in the terminal text")),
      "missing reason gets the default note, not None"
    )
  }

  test(
    "parseVerdict: unknown verdict values and non-JSON text are rejected (None = fallback leg, never a fabricated verdict)"
  ) {
    assertEquals(
      DelegateJudgeMachine.parseVerdict("{\"verdict\":\"failed\",\"reason\":\"x\"}"),
      None,
      "'failed' is engine-side and cannot be declared (taskbook wording)"
    )
    assertEquals(DelegateJudgeMachine.parseVerdict("no json here"), None)
    assertEquals(DelegateJudgeMachine.parseVerdict(""), None)
  }

  test("extractJsonObject: first {...} to last } wins (tolerates leading/trailing prose)") {
    val j = DelegateJudgeMachine.extractJsonObject("""intro {"a":{"b":1}} outro""")
    assertEquals(
      j.flatMap(_.hcursor.get[Int]("a").toOption),
      None,
      "nested object is NOT flattened (outer object is the parse root)"
    )
    assertEquals(j.flatMap(o => o.hcursor.downField("a").get[Int]("b").toOption), Some(1))
    assertEquals(DelegateJudgeMachine.extractJsonObject("{}").isDefined, true)
    assertEquals(DelegateJudgeMachine.extractJsonObject("nope"), None)
  }

  // ── VerdictWire 往返（core 侧承重键） ────────────────────────────────

  test(
    "VerdictWire: the noted completion encodes 'completed' (no blocked wire word); engine payload writes true terminal values"
  ) {
    assertEquals(DelegateJudge.VerdictWire.fromWire("READY-TO-MERGE"), Some("ready-to-merge"))
    assertEquals(DelegateJudge.VerdictWire.fromWire("nope"), None)
    // #566：注记形 wire 仍编 completed；注记走 reason 通道。
    val payload = DelegateJudge.verdictPayload("d-1", Some("9"), Verdict.CompletedNote("conflict"), source = "judge")
    assertEquals(payload.hcursor.get[String]("verdict").toOption, Some("completed"))
    assertEquals(payload.hcursor.get[String]("reason").toOption, Some("conflict"))
    assertEquals(payload.hcursor.get[String]("taskId").toOption, Some("9"))
    // 引擎终态真值行（failed/cancelled——不再冒充 blocked）。
    val engine = DelegateJudge.engineTerminalPayload("d-1", Some("9"), "failed", "engine terminal: failed")
    assertEquals(engine.hcursor.get[String]("verdict").toOption, Some("failed"))
    assertEquals(engine.hcursor.get[String]("source").toOption, Some("engine"))
    assertEquals(DelegateJudge.VerdictWire.fromWire("failed"), Some("failed"))
  }

end DelegateJudgeMachineSpec
