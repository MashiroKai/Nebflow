package nebflow.core.tools

import cats.effect.IO
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * ReportBlockedToolDef 单测（blocked 结构化信号批 20260909，spec §5.2 #2/#10、§9.3）：
 * - 身份双保险之工具内侧：ctx.flowNodeId 空 → REPORT_BLOCKED_FORBIDDEN 拒绝
 * - schema enum 白名单在工具层强制：非法 category → REPORT_BLOCKED_CATEGORY（可修正重调）
 * - detail 必填非空 → REPORT_BLOCKED_DETAIL
 * - 合法申报 → register 登记表（sessionId 键控）+ [OK] 确认文本
 * - 合法申报不带 sessionId → 确定性兜底键（不吞申报）
 */
class ReportBlockedToolSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 30.seconds

  private def ctx(flowNodeId: Option[String], sessionId: Option[String] = Some("node-testsid")): ToolContext =
    ToolContext(projectRoot = "/tmp", sessionId = sessionId, flowNodeId = flowNodeId)

  private def input(category: Json, detail: Json, suggestion: Json*): JsonObject =
    JsonObject.fromIterable(
      List("category" -> category, "detail" -> detail) ++
        suggestion.toList.map(s => "suggestion" -> s))

  private def call(json: JsonObject, c: ToolContext): IO[Either[ToolError, String]] =
    ReportBlockedToolDef.call(json, c)

  test("identity guard: non-flow session (flowNodeId empty) is rejected with REPORT_BLOCKED_FORBIDDEN") {
    for
      r <- call(input("upstream-incomplete".asJson, "上游未完成".asJson), ctx(flowNodeId = None))
    yield
      assert(r.isLeft, "non-flow session must be rejected")
      assert(r.left.exists(_.message.contains("REPORT_BLOCKED_FORBIDDEN")), s"error must carry the code, got: $r")
      assert(r.left.exists(_.message.contains("Flow Map node sessions")), s"error must say who may call, got: $r")
  }

  test("enum whitelist: invalid category rejected with REPORT_BLOCKED_CATEGORY (fixable error, spec §9.3)") {
    for
      r <- call(input("not-a-category".asJson, "细节".asJson), ctx(flowNodeId = Some("n-x")))
    yield
      assert(r.isLeft, "invalid category must be rejected at the tool layer")
      assert(r.left.exists(_.message.contains("REPORT_BLOCKED_CATEGORY")), s"error must carry the code, got: $r")
      assert(r.left.exists(_.message.contains("upstream-incomplete")), s"error must list the whitelist, got: $r")
  }

  test("detail required: empty detail rejected with REPORT_BLOCKED_DETAIL") {
    for
      r <- call(input("other".asJson, "  ".asJson), ctx(flowNodeId = Some("n-x")))
    yield
      assert(r.isLeft, "empty detail must be rejected")
      assert(r.left.exists(_.message.contains("REPORT_BLOCKED_DETAIL")), s"error must carry the code, got: $r")
  }

  test("happy path: valid declaration registers under sessionId and returns [OK] confirmation") {
    for
      r <- call(
        input("external-dependency".asJson, "等待 API key".asJson, "提供凭据后重派".asJson),
        ctx(flowNodeId = Some("n-x"), sessionId = Some("node-abc123")))
      drained <- nebflow.core.project.BlockedSignalRegistry.drain("node-abc123")
    yield
      assert(r.isRight, s"valid declaration must succeed, got: $r")
      assert(r.exists(_.contains("[OK] blocked declaration recorded")), s"confirmation text, got: $r")
      assertEquals(drained, Some(nebflow.core.project.BlockedFeedback("external-dependency", "等待 API key", "提供凭据后重派")),
        "registry must hold the declaration under the session id")
  }

  test("no sessionId: declaration still lands on a deterministic fallback key (never swallowed)") {
    for
      r <- call(input("needs-split".asJson, "任务应拆分".asJson), ctx(flowNodeId = Some("n-y"), sessionId = None))
      drained <- nebflow.core.project.BlockedSignalRegistry.drain("node-identity:n-y")
    yield
      assert(r.isRight, s"declaration must not be swallowed, got: $r")
      assert(drained.isDefined, "fallback-key entry must be drainable")
      assertEquals(drained.map(_.category), Some("needs-split"))
  }

  test("schema shape: category enum carries the six-value whitelist; required = category+detail") {
    val schema = ReportBlockedToolDef.inputSchema
    val enumVals = schema("properties").flatMap(_.asObject).flatMap(_("category"))
      .flatMap(_.asObject).flatMap(_("enum")).flatMap(_.asArray).map(_.flatMap(_.asString)).getOrElse(Nil)
    val required = schema("required").flatMap(_.asArray).map(_.flatMap(_.asString)).getOrElse(Nil)
    assertEquals(enumVals.sorted, List(
      "agent-mismatch", "external-dependency", "needs-split", "other",
      "task-underspecified", "upstream-incomplete"), "enum whitelist must match BlockedReader.Categories")
    assertEquals(required, List("category", "detail"))
  }

  // 挂载面断言（spec §9.3 第三条：非 flow 会话工具面不含 report_blocked）在
  // nebflow.agent.AllowedToolSetSpec——AgentCore 是 private[agent]，stub 须同包。
end ReportBlockedToolSpec
