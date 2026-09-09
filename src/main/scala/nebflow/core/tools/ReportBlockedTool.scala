package nebflow.core.tools

import cats.effect.IO
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import nebflow.core.project.BlockedFeedback
import nebflow.core.project.BlockedSignalRegistry
import nebflow.core.project.BlockedReader

/**
 * report_blocked — Flow Map 节点专属 blocked 申报工具（20260909 blocked-signal
 * 设计 spec 方案 A，改造点 #2）。
 *
 * 节点判定任务无法完成时调用本工具申报（协议级结构化信号，非文本推断）：
 * 三参与 BlockedFeedback 同构（category enum 六值白名单 / detail 必填 /
 * suggestion 可选）。execute 只做两件事：
 *  1. 身份双保险之工具内侧：ctx.flowNodeId 空 → 拒绝（挂载面过滤是另一侧，
 *     AgentCore.buildAllowedToolSet 仅 isFlowNode 会话注入——TaskBoard 同款双保险）；
 *  2. BlockedSignalRegistry.register(sessionId, feedback)（last-write-wins）。
 *
 * 申报后节点照常写收尾报告、正常结束 turn——引擎在会话完成时点 drain 登记表
 * 终态化 blocked（FeedbackRouter 重入协议原样接管）。文本锚定（首行裸 BLOCKED）
 * 保留为降级面：未调工具时行为零变化。
 */
object ReportBlockedToolDef extends Tool:

  val Name: String = "report_blocked"

  override def name: String = Name

  override def description: String =
    """Declare that your task CANNOT be completed — Flow Map node sessions only. Call this tool INSTEAD of fabricating a result when you are blocked by: upstream deliverables not ready, an underspecified task, a capability mismatch, or a missing external condition. Pass category (why), detail (what exactly is missing), suggestion (what would unblock). After calling, finish your report normally — the engine converts the node to blocked (never completed) and the dispatcher is re-engaged to fix routing/topology. If you CAN complete the task, do NOT call this tool; just deliver your result."""

  override def inputSchema: JsonObject = JsonObject(
    "type" -> "object".asJson,
    "properties" -> Json.obj(
      "category" -> Json.obj(
        "type" -> "string".asJson,
        "enum" -> Json.arr(
          "upstream-incomplete".asJson,
          "task-underspecified".asJson,
          "agent-mismatch".asJson,
          "external-dependency".asJson,
          "needs-split".asJson,
          "other".asJson),
        "description" -> "Why you are blocked: upstream-incomplete (an upstream node's deliverable is missing/invalid) | task-underspecified (acceptance criteria/definition incomplete) | agent-mismatch (required capability absent) | external-dependency (waiting on a resource outside the flow) | needs-split (work must be split into a subgraph) | other".asJson
      ),
      "detail" -> Json.obj(
        "type" -> "string".asJson,
        "description" -> "What exactly is blocked — concrete, actionable (the dispatcher reads this verbatim in the reentry brief). Required.".asJson
      ),
      "suggestion" -> Json.obj(
        "type" -> "string".asJson,
        "description" -> "What would unblock the task (topology change, missing input, retry condition). Optional.".asJson
      )
    ),
    "required" -> Json.arr("category".asJson, "detail".asJson)
  )

  override def summarize(input: JsonObject): String =
    val c = input("category").flatMap(_.asString).getOrElse("?")
    val d = input("detail").flatMap(_.asString).getOrElse("").take(60)
    s"report_blocked($c: $d)"

  override def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 200 then result.take(197) + "..." else result

  override def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    IO.blocking {
      val category = input("category").flatMap(_.asString).map(_.trim).getOrElse("")
      val detail = input("detail").flatMap(_.asString).map(_.trim).getOrElse("")
      val suggestion = input("suggestion").flatMap(_.asString).map(_.trim).getOrElse("")
      (category, detail, suggestion)
    }.flatMap { case (category, detail, suggestion) =>
      // 身份双保险之工具内侧（TaskBoardTool BoardCaller.Other 拒绝同款模式）：
      // 引擎侧身份（ctx.flowNodeId）判据，不信客户端参数。挂载面已过滤
      // （非 flow 会话无此工具），此处兜底防御 allowedSet 配置漂移。
      ctx.flowNodeId match
        case None =>
          IO.pure(Left(ToolError(
            s"report_blocked: permission denied — this tool is exclusive to Flow Map node sessions (your session carries no node identity). " +
              s"Finish your report as normal text output instead. (REPORT_BLOCKED_FORBIDDEN)")))
        case Some(_) if category.isEmpty || !BlockedReader.Categories.contains(category) =>
          // schema enum 白名单在工具层强制（入参层拒绝，比 BlockedReader 事后归一
          // 更强——spec §4-A ①）：非法值给可修正错误，节点可重调（spec §9.3）。
          IO.pure(Left(ToolError(
            s"report_blocked: invalid category '$category' — must be one of " +
              s"${BlockedReader.Categories.toList.sorted.mkString(" | ")} " +
              s"(REPORT_BLOCKED_CATEGORY). Fix and call again.")))
        case Some(_) if detail.isEmpty =>
          IO.pure(Left(ToolError(
            s"report_blocked: 'detail' must be non-empty — say WHAT is blocked and why, " +
              s"the dispatcher reads it verbatim. (REPORT_BLOCKED_DETAIL)")))
        case Some(nodeId) =>
          val fb = BlockedFeedback(
            category = category,
            detail = detail,
            suggestion = suggestion)
          BlockedSignalRegistry.register(ctx.sessionId.getOrElse(nodeIdSessionKey(nodeId)), fb).as(
            Right("[OK] blocked declaration recorded — finish your report normally. " +
              "The engine will mark the node blocked and re-engage the dispatcher; do not fabricate a result."))
    }

  /** 会话 id 缺失（理论不可达：flow 节点会话恒有 sessionId）时的确定性兜底键——
   * 保持 register 总有键可登记，避免 None 分支吞掉申报。 */
  private def nodeIdSessionKey(nodeId: String): String = s"node-identity:$nodeId"
end ReportBlockedToolDef
