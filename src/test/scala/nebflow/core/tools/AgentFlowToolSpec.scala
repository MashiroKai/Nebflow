package nebflow.core.tools

import io.circe.JsonObject
import io.circe.syntax.*
import munit.FunSuite

/**
 * builtin-merge 批（2026-10-03）纯核验收 ＋ N1 复名批（v2 ①，Workflow→AgentFlow）
 * 案 A guard 负例组。本文件钉：形态解析、判环（纯核）与 N-1..N-5 复名负例
 * （v2 §3.3 口径——guard 触发/不触发的形态判据 G1 边界）。执行面（登记/放行）
 * 由 ChainWriteToolSpec 的整链往返覆盖（薄壳 = ChainWrite.registerChain 单点）。
 */
class AgentFlowToolSpec extends FunSuite:

  private def stepsJson(items: (String, String, List[String])*) =
    JsonObject(
      "steps" -> io.circe.Json.arr(
        items.map { case (id, task, deps) =>
          io.circe.Json.obj(
            "id" -> id.asJson,
            "task" -> task.asJson,
            "deps" -> deps.map(_.asJson).asJson
          )
        }*
      )
    )

  test("parseSteps: happy path keeps order and deps") {
    val res = AgentFlowTool.parseSteps(stepsJson(("a", "do a", Nil), ("b", "do b", List("a"))))
    assertEquals(res.map(_.map(_.id)), Right(List("a", "b")))
    assertEquals(res.map(_.map(_.deps)), Right[nebflow.core.tools.ToolError, List[List[String]]](List(Nil, List("a"))))
  }

  test("parseSteps: empty / missing / duplicate / unknown-dep / cycle are explicit refusals") {
    assertEquals(AgentFlowTool.parseSteps(JsonObject.empty).isLeft, true)
    assertEquals(AgentFlowTool.parseSteps(stepsJson()).isLeft, true)
    val dup = AgentFlowTool.parseSteps(stepsJson(("a", "x", Nil), ("a", "y", Nil)))
    assert(dup.left.exists(_.message.contains("Duplicate")), dup.toString)
    val unknown = AgentFlowTool.parseSteps(stepsJson(("a", "x", List("ghost"))))
    assert(unknown.left.exists(_.message.contains("unknown step id")), unknown.toString)
    val cyclic = AgentFlowTool.parseSteps(stepsJson(("a", "x", List("b")), ("b", "y", List("a"))))
    assert(cyclic.left.exists(_.message.contains("Cycle")), cyclic.toString)
  }

  test("parseSteps: over the cap is refused (single source = ChainWriteTool.MaxNodes)") {
    val many = (1 to ChainWriteTool.MaxNodes + 1).map(i => (s"s$i", "t", List.empty[String])).toSeq
    val res = AgentFlowTool.parseSteps(stepsJson(many*))
    assert(res.left.exists(_.message.contains("cap")), res.toString)
  }

  test("hasCycle: diamond DAG is acyclic, self-loop is a cycle") {
    val diamond = List(
      AgentFlowTool.Step("a", "t", Nil),
      AgentFlowTool.Step("b", "t", List("a")),
      AgentFlowTool.Step("c", "t", List("a")),
      AgentFlowTool.Step("d", "t", List("b", "c"))
    )
    assertEquals(AgentFlowTool.hasCycle(diamond), false)
    assertEquals(AgentFlowTool.hasCycle(List(AgentFlowTool.Step("a", "t", List("a")))), true)
  }

  // ── N1 复名负例（v2 §3.3 N-1..N-5；判据 G1 = steps 键缺席 ∧ (task ∨ project) 在场）──

  test("N-1: AgentFlow(steps=[...]) takes the new path — the legacy guard does NOT trigger") {
    val input = stepsJson(("a", "do a", Nil))
    assertEquals(AgentFlowTool.legacyGuardHit(input), false, "steps present ⇒ new path")
    // 新路径首错 = 结构校验（这里结构合法 ⇒ 错误只会来自作用域闸，不会是 legacy 文案）
    assert(AgentFlowTool.parseSteps(input).isRight)
  }

  test("N-2: AgentFlow(task=..., project=...) with no steps hits the guard — the error names Delegate") {
    val input = JsonObject("task" -> "调查X".asJson, "project" -> "neb".asJson)
    assert(AgentFlowTool.legacyGuardHit(input), "steps missing + task/project present ⇒ guard fires")
    val err = AgentFlowTool.legacyGuardError
    assert(err.message.contains("Delegate"), err.message)
    assert(err.message.contains(AgentFlowTool.LegacyShapeCode), err.message)
  }

  test("N-3: AgentFlow(description=\"lbl\") alone does NOT trigger the guard — it falls to the normal missing-steps error") {
    val input = JsonObject("description" -> "lbl".asJson)
    assertEquals(AgentFlowTool.legacyGuardHit(input), false, "no task/project key ⇒ not a legacy dispatch shape")
    // 正常缺参错（文案原样——形态判据的边界即此）
    val res = AgentFlowTool.parseSteps(input)
    assert(res.left.exists(_.message.contains("Missing required parameter: steps")), res.toString)
  }

  test("N-4: AgentFlow(steps=[], task=\"x\") → CHAIN_EMPTY family error, the guard does NOT misfire") {
    val input = JsonObject.fromIterable(stepsJson().toMap + ("task" -> "x".asJson))
    assertEquals(AgentFlowTool.legacyGuardHit(input), false, "steps key PRESENT (even empty) ⇒ guard never fires")
    val res = AgentFlowTool.parseSteps(input)
    assert(res.left.exists(_.message.contains(ChainWriteTool.Codes.Empty)), res.toString)
  }

  test("N-5: a replayed legacy dispatch shape from an old session hits the guard (self-corrects to Delegate)") {
    val input = JsonObject("task" -> "old dispatch replay".asJson)
    assert(AgentFlowTool.legacyGuardHit(input), "old session replay = same G1 predicate")
    assert(AgentFlowTool.legacyGuardError.message.contains("Delegate"))
  }

end AgentFlowToolSpec
