package nebflow.core.tools

import cats.effect.IO
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * NodeReportToolDef 单测（blocked 结构化信号批 20260909，spec §5.2 #2/#10、§9.3；
 * 同日作者裁定泛化：NodeReport 统一三语义——原六例重放全保留 + pass/fail 扩面）：
 * - 身份双保险之工具内侧：ctx.flowNodeId 空 → NODE_REPORT_FORBIDDEN 拒绝
 * - schema enum 白名单在工具层强制：非法 category → NODE_REPORT_CATEGORY（可修正重调）
 * - detail 必填非空 → NODE_REPORT_DETAIL
 * - 合法 blocked 申报 → register 登记表（sessionId 键控）+ [OK] 确认文本
 * - 合法申报不带 sessionId → 确定性兜底键（不吞申报）
 * - 泛化面：enum 白名单 10 值（finish/pass/fail + blocked 泛值 + 六类细分）；
 *   pass/fail 主动申报 → 登记表可 drain（drain 分流由引擎侧集成测试覆盖）
 * - nrloop 一期（2026-09-12）：值域**按节点角色分化**（`enumFor(role)`）——角色不匹配
 *   → NODE_REPORT_CATEGORY_ROLE（可行动错误，含本节点 role 与合法值清单）；
 *   task 值域 = finish+blocked 面；verifier 值域 = pass/fail+blocked 面。
 */
class NodeReportToolSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 30.seconds

  private def ctx(
    flowNodeId: Option[String],
    sessionId: Option[String] = Some("node-testsid"),
    role: Option[String] = None
  ): ToolContext =
    ToolContext(projectRoot = "/tmp", sessionId = sessionId, flowNodeId = flowNodeId, flowNodeRole = role)

  private def input(category: Json, detail: Json, suggestion: Json*): JsonObject =
    JsonObject.fromIterable(
      List("category" -> category, "detail" -> detail) ++
        suggestion.toList.map(s => "suggestion" -> s)
    )

  private def call(json: JsonObject, c: ToolContext): IO[Either[ToolError, String]] =
    NodeReportToolDef.call(json, c)

  test("identity guard: non-flow session (flowNodeId empty) is rejected with NODE_REPORT_FORBIDDEN") {
    for r <- call(input("blocked".asJson, "上游未完成".asJson), ctx(flowNodeId = None))
    yield
      assert(r.isLeft, "non-flow session must be rejected")
      assert(r.left.exists(_.message.contains("NODE_REPORT_FORBIDDEN")), s"error must carry the code, got: $r")
      assert(r.left.exists(_.message.contains("Flow Map node sessions")), s"error must say who may call, got: $r")
  }

  test("retired blocked category is rejected and AskHost is named for uncertainty") {
    for r <- call(input("blocked".asJson, "范围不明".asJson), ctx(flowNodeId = Some("n-x")))
    yield
      assert(r.isLeft, "blocked must not be a reportable terminal category")
      assert(r.left.exists(_.message.contains("NODE_REPORT_CATEGORY")), s"error must carry the code, got: $r")
      assert(NodeReportToolDef.description.contains("AskHost"), "unclear scope must direct agents to AskHost")
  }

  test("detail required: empty detail rejected with NODE_REPORT_DETAIL") {
    for r <- call(input("finish".asJson, "  ".asJson), ctx(flowNodeId = Some("n-x")))
    yield
      assert(r.isLeft, "empty detail must be rejected")
      assert(r.left.exists(_.message.contains("NODE_REPORT_DETAIL")), s"error must carry the code, got: $r")
  }

  test("blocked is absent from the finite report category vocabulary") {
    assert(!NodeReportToolDef.StatusValues.contains("blocked"))
    assertEquals(NodeReportToolDef.TaskCategories, Set("finish"))
    assertEquals(NodeReportToolDef.VerifierCategories, Set("pass", "fail"))
  }

  test("no sessionId: declaration still lands on a deterministic fallback key (never swallowed)") {
    for
      r <- call(input("finish".asJson, "完成".asJson), ctx(flowNodeId = Some("n-y"), sessionId = None))
      drained <- nebflow.core.project.NodeReportRegistry.drain("node-identity:n-y")
    yield
      assert(r.isRight, s"declaration must not be swallowed, got: $r")
      assert(drained.isDefined, "fallback-key entry must be drainable")
      assertEquals(drained.map(_.category), Some("finish"))
  }

  test("schema shape: category enum contains only live role-specific semantics") {
    val schema = NodeReportToolDef.inputSchema
    val enumVals = schema("properties")
      .flatMap(_.asObject)
      .flatMap(_("category"))
      .flatMap(_.asObject)
      .flatMap(_("enum"))
      .flatMap(_.asArray)
      .map(_.flatMap(_.asString))
      .getOrElse(Nil)
    val required = schema("required").flatMap(_.asArray).map(_.flatMap(_.asString)).getOrElse(Nil)
    assertEquals(enumVals.sorted, List("fail", "finish", "pass"))
    assertEquals(enumVals.size, 3)
    assert(!enumVals.contains("blocked"))
    assertEquals(required, List("category", "detail"))
  }

  // ===== NodeReport 泛化批扩面：pass/fail 主动申报（作者裁定三语义统一迁移）=====

  test(
    "pass declaration (role=verifier): registers under sessionId with the [OK] confirmation (drain分流 to completed chain is engine-side)"
  ) {
    for
      r <- call(
        input("pass".asJson, "验收条件逐条核对通过".asJson, "——".asJson),
        ctx(flowNodeId = Some("n-p"), sessionId = Some("node-passsid"), role = Some("verifier"))
      )
      drained <- nebflow.core.project.NodeReportRegistry.drain("node-passsid")
    yield
      assert(r.isRight, s"pass declaration must succeed, got: $r")
      assert(r.exists(_.contains("[OK] verdict recorded (pass)")), s"confirmation text, got: $r")
      assert(r.exists(_.contains("pass edge")), s"pass confirmation must say the pass edges are delivered, got: $r")
      assertEquals(
        drained,
        Some(nebflow.core.project.NodeReportFeedback("pass", "验收条件逐条核对通过", "——")),
        "registry must hold the pass declaration under the session id"
      )
  }

  test("fail declaration (role=verifier): registers under sessionId with the verdict != node status confirmation") {
    for
      r <- call(
        input("fail".asJson, "产物编译红，无法在本会话内修复".asJson, "回滚上游依赖后重派".asJson),
        ctx(flowNodeId = Some("n-f"), sessionId = Some("node-failsid"), role = Some("verifier"))
      )
      drained <- nebflow.core.project.NodeReportRegistry.drain("node-failsid")
    yield
      assert(r.isRight, s"fail declaration must succeed, got: $r")
      assert(r.exists(_.contains("[OK] verdict recorded (fail)")), s"confirmation text, got: $r")
      assert(
        r.exists(_.contains("verdict ≠ node status")),
        s"fail confirmation must spell out verdict != node status, got: $r"
      )
      assertEquals(
        drained.map(_.category),
        Some("fail"),
        "registry must hold the fail declaration under the session id"
      )
  }

  // ===== nrloop 一期：值域按角色分化（NODE_REPORT_CATEGORY_ROLE）=====

  test("role=task (and role omitted) rejects pass/fail with NODE_REPORT_CATEGORY_ROLE + the legal list") {
    for
      rDefault <- call(input("pass".asJson, "完成".asJson), ctx(flowNodeId = Some("n-t")))
      rExplicit <- call(input("fail".asJson, "失败".asJson), ctx(flowNodeId = Some("n-t"), role = Some("task")))
      rFinish <- call(input("finish".asJson, "完成".asJson), ctx(flowNodeId = Some("n-t"), sessionId = Some("node-tfin")))
    yield
      List(rDefault, rExplicit).foreach { r =>
        assert(r.isLeft, s"a task node must not be able to report a verdict, got: $r")
        assert(r.left.exists(_.message.contains("NODE_REPORT_CATEGORY_ROLE")), s"error must carry the code, got: $r")
        assert(r.left.exists(_.message.contains("role=task")), s"error must name this node's role, got: $r")
        assert(r.left.exists(_.message.contains("finish")), s"error must list the legal values for the role, got: $r")
      }
      assert(rFinish.isRight, s"finish is legal for role=task (optional explicit completion), got: $rFinish")
      assert(rFinish.exists(_.contains("[OK] node report (finish) recorded")), s"finish confirmation, got: $rFinish")
  }

  test("role=verifier rejects finish with NODE_REPORT_CATEGORY_ROLE; blocked is rejected") {
    for
      rFinish <- call(input("finish".asJson, "完成".asJson), ctx(flowNodeId = Some("n-v"), role = Some("verifier")))
      rBlocked <- call(input("blocked".asJson, "范围不清".asJson), ctx(flowNodeId = Some("n-v"), role = Some("verifier")))
    yield
      assert(rFinish.isLeft, s"a verifier node must not report finish, got: $rFinish")
      assert(rFinish.left.exists(_.message.contains("NODE_REPORT_CATEGORY_ROLE")), s"code, got: $rFinish")
      assert(rBlocked.isLeft, s"blocked must be rejected, got: $rBlocked")
      assert(rBlocked.left.exists(_.message.contains("NODE_REPORT_CATEGORY")), s"code, got: $rBlocked")
  }

  test("enumFor: the single role→value-domain point (verifier vs task/default)") {
    assertEquals(NodeReportToolDef.enumFor(Some("verifier")), NodeReportToolDef.VerifierCategories)
    assertEquals(
      NodeReportToolDef.enumFor(Some("VERIFIER ")),
      NodeReportToolDef.VerifierCategories,
      "normalize is case/space tolerant"
    )
    assertEquals(NodeReportToolDef.enumFor(Some("task")), NodeReportToolDef.TaskCategories)
    assertEquals(NodeReportToolDef.enumFor(None), NodeReportToolDef.TaskCategories, "no role = task (decode default)")
    assert(
      NodeReportToolDef.VerifierCategories.contains("pass") && NodeReportToolDef.VerifierCategories.contains("fail")
    )
    assert(!NodeReportToolDef.TaskCategories.contains("pass") && !NodeReportToolDef.TaskCategories.contains("fail"))
    assert(!NodeReportToolDef.VerifierCategories.contains("finish"))
    assert(NodeReportToolDef.TaskCategories.contains("finish"))
  }

  test("report vocabulary is exhaustive and has no stopped/waiting terminal category") {
    assertEquals(NodeReportToolDef.StatusValues, Set("finish", "pass", "fail"))
    assertEquals(NodeReportToolDef.StatusValues, NodeReportToolDef.TaskCategories ++ NodeReportToolDef.VerifierCategories)
    assert(!NodeReportToolDef.description.toLowerCase.contains("blocked"))
    assert(NodeReportToolDef.description.contains("AskHost"))
  }

  test("renderFail: fail report renders [node-report:fail] head + detail + optional suggestion") {
    val withSugg = NodeReportToolDef.renderFail(nebflow.core.project.NodeReportFeedback("fail", "编译红", "回滚后重派"))
    val noSugg = NodeReportToolDef.renderFail(nebflow.core.project.NodeReportFeedback("fail", "编译红", ""))
    val blankSugg = NodeReportToolDef.renderFail(nebflow.core.project.NodeReportFeedback("fail", "编译红", "   "))
    assert(withSugg.startsWith("[node-report:fail] 编译红") && withSugg.contains("建议: 回滚后重派"), withSugg)
    assertEquals(noSugg, "[node-report:fail] 编译红", "empty suggestion renders without the 建议 tail")
    assertEquals(blankSugg, noSugg, "blank suggestion treated as empty")
  }

  // 挂载面断言（spec §9.3 第三条：非 flow 会话工具面不含 node_report）在
  // nebflow.agent.AllowedToolSetSpec——AgentCore 是 private[agent]，stub 须同包。
end NodeReportToolSpec
