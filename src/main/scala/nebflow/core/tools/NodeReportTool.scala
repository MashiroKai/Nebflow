package nebflow.core.tools

import cats.effect.IO
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import nebflow.core.project.BlockedFeedback
import nebflow.core.project.NodeReportRegistry
import nebflow.core.project.BlockedReader

/**
 * node_report — Flow Map 节点专属终态语义申报工具（20260909 blocked-signal
 * 设计 spec 方案 A 改造点 #2；同日作者裁定泛化：NodeReport 统一三语义迁移）。
 *
 * 作者裁定原话：「这个工具可以加，但是不能是 report_blocked，而是 NodeReport，
 * 因为我们不只 Blocked，还有 Pass 和 Failed 也是在使用类似的语义判断。如果
 * Blocked 存在问题，那么其他的肯定也存在，要迁移就应该一起迁移。」
 *
 * 节点以工具调用申报终态语义（协议级结构化信号，非文本推断），单 category
 * 参数 = 三语义状态 + blocked 细分原因的扁平白名单（9 值）：
 *  - blocked 细分六类（BlockedReader.Categories 全集）+ 泛值 blocked；
 *  - pass / fail 两语义状态。
 * detail 必填 / suggestion 可选。execute 只做两件事：
 *  1. 身份双保险之工具内侧：ctx.flowNodeId 空 → 拒绝（挂载面过滤是另一侧，
 *     AgentCore.buildAllowedToolSet 仅 flowNodeSession 注入——TaskBoard 同款双保险）；
 *  2. NodeReportRegistry.register(sessionId, feedback)（last-write-wins）。
 *
 * 申报后节点照常写收尾报告、正常结束 turn——引擎在会话完成时点 drain 登记表，
 * 按类别分流到既有语义链（零新链）：blocked → blockedNode 终态化（FeedbackRouter
 * 重入协议原样接管）；pass → 既有 completed 语义链；fail → 既有 failed 语义链
 * （verify 相位 = VerdictReader 同款打回链）。三语义文本锚定（首行裸 BLOCKED /
 * VERDICT: PASS / VERDICT: FAIL）全部保留为降级面：未调工具时行为零变化、零放宽。
 */
object NodeReportToolDef extends Tool:

  val Name: String = "node_report"

  /** 三语义状态值（作者裁定：Blocked/Pass/Failed 同语义判断面，统一迁移）。 */
  val StatusValues: Set[String] = Set("blocked", "pass", "fail")

  /** blocked 泛值之外既有的六类细分（BlockedReader 现状枚举，降级面同源零漂移）。 */
  val BlockedCategories: Set[String] = BlockedReader.Categories

  /** schema enum 白名单（9 值）：三语义状态 + 六类 blocked 细分。 */
  val EnumWhitelist: Set[String] = StatusValues ++ BlockedCategories

  /** drain 分流判据：申报是否走既有 blocked 链（细分六类或泛值 blocked）。 */
  def isBlockedSemantics(category: String): Boolean =
    category == "blocked" || BlockedCategories.contains(category)

  def isPass(category: String): Boolean  = category == "pass"
  def isFail(category: String): Boolean  = category == "fail"

  /** fail 申报落 failNode 的 result 渲染串（BlockedReader.render 同款风格：
    * 头部标记 + detail + 可选建议——观测面单点格式）。 */
  def renderFail(f: BlockedFeedback): String =
    val sugg = if f.suggestion.trim.isEmpty then "" else s" — 建议: ${f.suggestion}"
    s"[node-report:fail] ${f.detail}$sugg"

  override def name: String = Name

  override def description: String =
    """Report your node's terminal verdict as a structured signal — Flow Map node sessions only. Three semantics, one tool: (1) BLOCKED — your task CANNOT be completed (upstream deliverables not ready, underspecified task, capability mismatch, missing external condition): pass a specific blocked category when you know it, or the generic "blocked"; the engine marks the node blocked and re-engages the dispatcher — never fabricate a result. (2) PASS — you formally declare the task/round completed and verified. (3) FAIL — you formally declare the task failed with the concrete reason. Pass category (the verdict + why), detail (what exactly, actionable), suggestion (what would unblock / fix). After calling, finish your report normally — the engine routes the declaration to the matching terminal chain."""

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
          "other".asJson,
          "blocked".asJson,
          "pass".asJson,
          "fail".asJson),
        "description" -> "Terminal verdict: upstream-incomplete (an upstream node's deliverable is missing/invalid) | task-underspecified (acceptance criteria/definition incomplete) | agent-mismatch (required capability absent) | external-dependency (waiting on a resource outside the flow) | needs-split (work must be split into a subgraph) | other (blocked, reason unclassified) | blocked (blocked, reason not further classified) | pass (declare completion) | fail (declare failure)".asJson
      ),
      "detail" -> Json.obj(
        "type" -> "string".asJson,
        "description" -> "What exactly — concrete, actionable (blocked: the dispatcher reads this verbatim in the reentry brief; fail: the failure reason). Required.".asJson
      ),
      "suggestion" -> Json.obj(
        "type" -> "string".asJson,
        "description" -> "What would unblock / fix the task (topology change, missing input, retry condition). Optional.".asJson
      )
    ),
    "required" -> Json.arr("category".asJson, "detail".asJson)
  )

  override def summarize(input: JsonObject): String =
    val c = input("category").flatMap(_.asString).getOrElse("?")
    val d = input("detail").flatMap(_.asString).getOrElse("").take(60)
    s"node_report($c: $d)"

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
      // （非 flow 节点会话无此工具），此处兜底防御 allowedSet 配置漂移。
      ctx.flowNodeId match
        case None =>
          IO.pure(Left(ToolError(
            s"node_report: permission denied — this tool is exclusive to Flow Map node sessions (your session carries no node identity). " +
              s"Finish your report as normal text output instead. (NODE_REPORT_FORBIDDEN)")))
        case Some(_) if category.isEmpty || !EnumWhitelist.contains(category) =>
          // schema enum 白名单在工具层强制（入参层拒绝，比 BlockedReader 事后归一
          // 更强——spec §4-A ①）：非法值给可修正错误，节点可重调（spec §9.3）。
          IO.pure(Left(ToolError(
            s"node_report: invalid category '$category' — must be one of " +
              s"${EnumWhitelist.toList.sorted.mkString(" | ")} " +
              s"(NODE_REPORT_CATEGORY). Fix and call again.")))
        case Some(_) if detail.isEmpty =>
          IO.pure(Left(ToolError(
            s"node_report: 'detail' must be non-empty — say WHAT is the verdict and why " +
              s"(blocked: the dispatcher reads it verbatim). (NODE_REPORT_DETAIL)")))
        case Some(nodeId) =>
          val fb = BlockedFeedback(
            category = category,
            detail = detail,
            suggestion = suggestion)
          // 确认文本：blocked 链沿用 v1 原文（测试/冒烟断言锚零漂移）；pass/fail
          // 用泛化形态。两分支同走 register，分流在引擎 drain 处。
          val confirm =
            if isBlockedSemantics(category) then
              "[OK] blocked declaration recorded — finish your report normally. " +
                "The engine will mark the node blocked and re-engage the dispatcher; do not fabricate a result."
            else
              s"[OK] node report ($category) recorded — finish your report normally."
          NodeReportRegistry.register(ctx.sessionId.getOrElse(nodeIdSessionKey(nodeId)), fb).as(
            Right(confirm))
    }

  /** 会话 id 缺失（理论不可达：flow 节点会话恒有 sessionId）时的确定性兜底键——
   * 保持 register 总有键可登记，避免 None 分支吞掉申报。 */
  private def nodeIdSessionKey(nodeId: String): String = s"node-identity:$nodeId"
end NodeReportToolDef
