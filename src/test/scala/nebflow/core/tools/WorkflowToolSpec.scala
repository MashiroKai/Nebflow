package nebflow.core.tools

import io.circe.JsonObject
import io.circe.syntax.*
import munit.FunSuite

/**
 * builtin-merge 批（2026-10-03）：Workflow 步骤调度核的纯函数验收——形态解析、
 * 判环、拓扑轮次。执行面（spawn/阻塞桥）由隔离实例 e2e 覆盖，本文件只钉纯核。
 */
class WorkflowToolSpec extends FunSuite:

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
    val res = WorkflowTool.parseSteps(stepsJson(("a", "do a", Nil), ("b", "do b", List("a"))))
    assertEquals(res.map(_.map(_.id)), Right(List("a", "b")))
    assertEquals(res.map(_.map(_.deps)), Right[nebflow.core.tools.ToolError, List[List[String]]](List(Nil, List("a"))))
  }

  test("parseSteps: empty / missing / duplicate / unknown-dep / cycle are explicit refusals") {
    assertEquals(WorkflowTool.parseSteps(JsonObject.empty).isLeft, true)
    assertEquals(WorkflowTool.parseSteps(stepsJson()).isLeft, true)
    val dup = WorkflowTool.parseSteps(stepsJson(("a", "x", Nil), ("a", "y", Nil)))
    assert(dup.left.exists(_.message.contains("Duplicate")), dup.toString)
    val unknown = WorkflowTool.parseSteps(stepsJson(("a", "x", List("ghost"))))
    assert(unknown.left.exists(_.message.contains("unknown step id")), unknown.toString)
    val cyclic = WorkflowTool.parseSteps(stepsJson(("a", "x", List("b")), ("b", "y", List("a"))))
    assert(cyclic.left.exists(_.message.contains("Cycle")), cyclic.toString)
  }

  test("parseSteps: over the 12-step cap is refused") {
    val many = (1 to 13).map(i => (s"s$i", "t", List.empty[String])).toSeq
    val res = WorkflowTool.parseSteps(stepsJson(many*))
    assert(res.left.exists(_.message.contains("cap")), res.toString)
  }

  test("hasCycle: diamond DAG is acyclic, self-loop is a cycle") {
    val diamond = List(
      WorkflowTool.Step("a", "t", Nil),
      WorkflowTool.Step("b", "t", List("a")),
      WorkflowTool.Step("c", "t", List("a")),
      WorkflowTool.Step("d", "t", List("b", "c"))
    )
    assertEquals(WorkflowTool.hasCycle(diamond), false)
    assertEquals(WorkflowTool.hasCycle(List(WorkflowTool.Step("a", "t", List("a")))), true)
  }

  test("rounds: diamond runs b+c concurrently after a, then d") {
    val diamond = List(
      WorkflowTool.Step("a", "t", Nil),
      WorkflowTool.Step("b", "t", List("a")),
      WorkflowTool.Step("c", "t", List("a")),
      WorkflowTool.Step("d", "t", List("b", "c"))
    )
    val rs = WorkflowTool.rounds(diamond)
    assertEquals(rs.map(_.map(_.id)), List(List("a"), List("b", "c"), List("d")))
  }

  test("rounds: entry steps all run in round 1") {
    val rs = WorkflowTool.rounds(List(
      WorkflowTool.Step("a", "t", Nil),
      WorkflowTool.Step("b", "t", Nil),
      WorkflowTool.Step("c", "t", List("a", "b"))
    ))
    assertEquals(rs.map(_.map(_.id)), List(List("a", "b"), List("c")))
  }
