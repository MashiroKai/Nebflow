package nebflow.llm.decision

import io.circe.syntax.*
import munit.FunSuite
import nebflow.core.delegate.DelegateJudge
import nebflow.core.delegate.DelegateJudge.{TerminalInput, Verdict}

/**
 * 判定机执行体（⑦ Jev 优先＋SmallJudge 兜底）的**纯函数面**判读——网络腿
 * （真 JeV / 真 LLM adapter）不在 spec 范围（依赖外部服务），本件钉死：
 *
 *  - SmallJudge 回文解析：裸 JSON / ```json 围栏 / 混排散文皆容；缺键/非法判词拒；
 *  - 三态判词值域（completed / blocked(+reason) / ready-to-merge）解析往返；
 *  - Jev Choice 判词读取（verdictFromChoice）：合法 label → 判词；未知 label → None
 *    （= 兜底腿接手，非误判）。
 */
class DelegateJudgeMachineSpec extends FunSuite:

  private def input(mergeRound: Boolean = false): TerminalInput =
    TerminalInput(
      delegateId = "delegate-nebflow-x",
      taskBrief = "brief",
      resultText = "result",
      gitDigest = "branch: delegate/delegate-nebflow-x",
      selfReport = None,
      mergeRound = mergeRound
    )

  // ── SmallJudge JSON 解析 ─────────────────────────────────────────────

  test("parseVerdict: bare, fenced and prose-wrapped JSON all parse; verdict+reason land") {
    assertEquals(
      DelegateJudgeMachine.parseVerdict("""{"verdict":"ready-to-merge","reason":"commits on branch"}"""),
      Some(Verdict.ReadyToMerge)
    )
    assertEquals(
      DelegateJudgeMachine.parseVerdict("```json\n{\"verdict\": \"completed\", \"reason\": \"\"}\n```"),
      Some(Verdict.Completed)
    )
    assertEquals(
      DelegateJudgeMachine.parseVerdict(
        "The judge says: {\"verdict\":\"blocked\",\"reason\":\"merge conflict in a.scala\"} (end)"
      ),
      Some(Verdict.Blocked("merge conflict in a.scala"))
    )
    assertEquals(
      DelegateJudgeMachine.parseVerdict("{\"verdict\":\"blocked\"}"),
      Some(Verdict.Blocked("small-judge: blocked")),
      "missing reason gets the default reason, not None"
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

  test("parseWorktree: {\"worktree\": bool} parses; garbage rejects") {
    assertEquals(DelegateJudgeMachine.parseWorktree("{\"worktree\":true}"), Some(true))
    assertEquals(DelegateJudgeMachine.parseWorktree("```json\n{\"worktree\": false}\n```"), Some(false))
    assertEquals(DelegateJudgeMachine.parseWorktree("{\"worktree\":\"yes\"}"), None)
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

  // ── Jev Choice 判词读取 ──────────────────────────────────────────────

  private def choiceResponse(label: String): DecisionResponse =
    DecisionResponse(
      model = Some("jev-test"),
      answers = Map(
        "verdict" -> DecisionAnswer.Choice(choice = label, confidence = None, probabilities = Map(label -> 0.9))
      ),
      usage = None
    )

  test("verdictFromChoice: the three legal labels map to the three states; blocked carries the mergeRound marker") {
    assertEquals(DelegateJudgeMachine.verdictFromChoice(choiceResponse("completed"), input()), Some(Verdict.Completed))
    assertEquals(
      DelegateJudgeMachine.verdictFromChoice(choiceResponse("ready-to-merge"), input()),
      Some(Verdict.ReadyToMerge)
    )
    val blocked = DelegateJudgeMachine.verdictFromChoice(choiceResponse("blocked"), input(mergeRound = true))
    assertEquals(blocked.isDefined, true)
    assert(blocked.get.toString.contains("blocked"), "blocked verdict carries its reason channel")
    assertEquals(
      DelegateJudgeMachine.verdictFromChoice(choiceResponse("failed"), input()),
      None,
      "unknown label → None (fallback leg), never a guess"
    )
  }

  // ── VerdictWire 往返（core 侧承重键） ────────────────────────────────

  test("VerdictWire encode/decode round trip; payload keys carry verdict+reason") {
    assertEquals(DelegateJudge.VerdictWire.fromWire("READY-TO-MERGE"), Some("ready-to-merge"))
    assertEquals(DelegateJudge.VerdictWire.fromWire("nope"), None)
    val payload = DelegateJudge.verdictPayload("d-1", Some("9"), Verdict.Blocked("conflict"), source = "judge")
    assertEquals(payload.hcursor.get[String]("verdict").toOption, Some("blocked"))
    assertEquals(payload.hcursor.get[String]("reason").toOption, Some("conflict"))
    assertEquals(payload.hcursor.get[String]("taskId").toOption, Some("9"))
  }

end DelegateJudgeMachineSpec
