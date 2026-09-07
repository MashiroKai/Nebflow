package nebflow.core.tools

import io.circe.Json
import munit.FunSuite
import nebflow.core.project.NodeLifecycle

/**
 * NodeTools 纯校验单测（#28 阶段 0，§2.4）——不依赖 runtime 的部分：
 * - parseOut：单值 string / "Nebula" / null 接受；数组（1 对多）拒绝
 * - NodeLifecycle 状态枚举与 Terminal 集合
 */
class NodeToolsSpec extends FunSuite:

  test("parseOut: single node id accepted") {
    assertEquals(NodeTools.parseOut(Some(Json.fromString("n-abc12345"))), Right(Some("n-abc12345")))
  }

  test("parseOut: Nebula accepted") {
    assertEquals(NodeTools.parseOut(Some(Json.fromString("Nebula"))), Right(Some("Nebula")))
  }

  test("parseOut: null → disconnect (Right(None))") {
    assertEquals(NodeTools.parseOut(Some(Json.Null)), Right(None))
  }

  test("parseOut: string \"null\" → disconnect (Right(None)) — LLM 断开常见写法，历史上被当字面 target id 存盘") {
    assertEquals(NodeTools.parseOut(Some(Json.fromString("null"))), Right(None))
    assertEquals(NodeTools.parseOut(Some(Json.fromString(" null "))), Right(None))
    assertEquals(NodeTools.parseOut(Some(Json.fromString("NULL"))), Right(None))
  }

  test("parseOut: absent → unchanged (Right(None))") {
    assertEquals(NodeTools.parseOut(None), Right(None))
  }

  // ── parseIn 宽容解析（修复次因 B，回归⑦）────────────────

  test("parseIn: native JSON array accepted") {
    assertEquals(
      NodeTools.parseIn(Some(Json.arr(Json.fromString("n-a"), Json.fromString("n-b")))),
      Right(List("n-a", "n-b")))
  }

  test("parseIn: JSON-array-as-string accepted (LLM 把数组整体字符串化的实证形态)") {
    assertEquals(
      NodeTools.parseIn(Some(Json.fromString("[\"n-a\",\"n-b\"]"))),
      Right(List("n-a", "n-b")))
  }

  test("parseIn: comma-separated string accepted") {
    assertEquals(
      NodeTools.parseIn(Some(Json.fromString("n-a, n-b ,n-c"))),
      Right(List("n-a", "n-b", "n-c")))
  }

  test("parseIn: single plain id stays single") {
    assertEquals(NodeTools.parseIn(Some(Json.fromString("n-a"))), Right(List("n-a")))
  }

  test("parseIn: empty segments / empty strings filtered") {
    assertEquals(NodeTools.parseIn(Some(Json.fromString("n-a,, ,n-b,"))), Right(List("n-a", "n-b")))
    assertEquals(
      NodeTools.parseIn(Some(Json.arr(Json.fromString("n-a"), Json.fromString(""), Json.fromString(" ")))),
      Right(List("n-a")))
    assertEquals(NodeTools.parseIn(Some(Json.fromString(""))), Right(Nil))
    assertEquals(NodeTools.parseIn(Some(Json.fromString("   "))), Right(Nil))
  }

  test("parseIn: malformed JSON-array-string → clear error with raw text (不再吞成单字面 id)") {
    val raw = "[n-a, n-b"
    val r = NodeTools.parseIn(Some(Json.fromString(raw)))
    assert(r.isLeft, s"malformed JSON array string must be rejected, got: $r")
    assert(r.left.exists(_.contains(raw)), s"error must carry the raw input for diagnosis, got: $r")
  }

  test("parseIn: absent / JSON null → Nil") {
    assertEquals(NodeTools.parseIn(None), Right(Nil))
    assertEquals(NodeTools.parseIn(Some(Json.Null)), Right(Nil))
  }

  test("parseOut: array rejected (1-to-many not supported)") {
    val arr = Json.arr(Json.fromString("n1"), Json.fromString("n2"))
    assert(NodeTools.parseOut(Some(arr)).isLeft)
  }

  test("parseOut: empty string rejected") {
    assert(NodeTools.parseOut(Some(Json.fromString(""))).isLeft)
  }

  test("NodeLifecycle: Terminal = completed/failed/cancelled/blocked, excludes running/pending/wiring") {
    assertEquals(
      NodeLifecycle.Terminal,
      Set(NodeLifecycle.Completed, NodeLifecycle.Failed, NodeLifecycle.Cancelled, NodeLifecycle.Blocked)
    )
    assert(!NodeLifecycle.Terminal.contains(NodeLifecycle.Running))
    assert(!NodeLifecycle.Terminal.contains(NodeLifecycle.Pending))
    assert(!NodeLifecycle.Terminal.contains(NodeLifecycle.Wiring))
  }

  // ── resolveProject 报错（mountError 纯函数，项目名不匹配附可用项目列表）────
  // 实证锚点：NodeList(Nebflow) 报 "Project 'Nebflow' is not mounted. Use ProjectCreate first."——
  // 不匹配应列出实际已挂载项目名（提示大小写/名称，不裸报 not mounted）。

  test("mountError: not-mounted error lists available projects (case-insensitive hint)") {
    val e = NodeTools.mountError("Nebflow", List("nebflow"))
    assert(e.contains("Nebflow"), s"must name the requested project, got: $e")
    assert(e.contains("Available projects: nebflow"), s"must list available project(s), got: $e")
    assert(e.contains("Use ProjectCreate first"), s"must point to ProjectCreate, got: $e")
  }

  test("mountError: empty available list → dedicated no-projects hint (still actionable)") {
    val e = NodeTools.mountError("nonexistent", Nil)
    assert(e.contains("nonexistent"), s"must name the requested project, got: $e")
    assert(e.contains("No projects are currently mounted"), s"empty registry needs its own hint, got: $e")
    assert(e.contains("Use ProjectCreate first"), s"must point to ProjectCreate, got: $e")
  }

  // ── 四工具 schema：project 参数可选化（缺省=分发器当前项目，不再 required）────

  private def requiredOf(t: Tool): List[String] =
    t.inputSchema("required").flatMap(_.asArray).toList.flatten.flatMap(_.asString)

  test("four node tools: 'project' removed from required (dispatcher defaults to current project)") {
    // NodeEdit: nodename 仍必填
    assert(!requiredOf(NodeEditTool).contains("project") && requiredOf(NodeEditTool).contains("nodename"),
      s"NodeEdit required should drop project, keep nodename; got ${requiredOf(NodeEditTool)}")
    // NodeList: 唯一必填原本就是 project → 现在无必填
    assertEquals(requiredOf(NodeListTool), Nil, "NodeList has no other required param — project optional leaves required empty")
    // NodeCancel: node-id 仍必填
    assert(!requiredOf(NodeCancelTool).contains("project") && requiredOf(NodeCancelTool).contains("node-id"),
      s"NodeCancel required should drop project, keep node-id; got ${requiredOf(NodeCancelTool)}")
    // NodeMessage: nodeId + message 仍必填
    val msgReq = requiredOf(NodeMessageTool)
    assert(!msgReq.contains("project") && msgReq.contains("nodeId") && msgReq.contains("message"),
      s"NodeMessage required should drop project, keep nodeId+message; got $msgReq")
  }

end NodeToolsSpec
