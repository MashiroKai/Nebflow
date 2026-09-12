package nebflow.core.tools

import cats.effect.IO
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import nebflow.core.project.BlockedFeedback
import nebflow.core.project.NodeReportRegistry
import nebflow.core.project.BlockedReader
import nebflow.core.project.NodeRoles

/**
 * node_report — Flow Map 节点专属终态语义申报工具（20260909 blocked-signal
 * 设计 spec 方案 A 改造点 #2；同日作者裁定泛化：NodeReport 统一三语义迁移）。
 *
 * 作者裁定原话：「这个工具可以加，但是不能是 report_blocked，而是 NodeReport，
 * 因为我们不只 Blocked，还有 Pass 和 Failed 也是在使用类似的语义判断。如果
 * Blocked 存在问题，那么其他的肯定也存在，要迁移就应该一起迁移。」
 *
 * 节点以工具调用申报终态语义（协议级结构化信号，非文本推断），单 category
 * 参数 = 值域**按节点角色分化**（nrloop 一期 2026-09-12；旧「三语义状态 + blocked
 * 六类细分」的 9 值扁平白名单已被设计 §3.3 #1 取代）：
 *  - `role=task`（执行节点，缺省）：`finish`（可选显式完成）+ blocked 泛值/六类细分；
 *  - `role=verifier`（校验节点）：`pass`/`fail`（verdict）+ blocked 泛值/六类细分。
 * 三条纪律（设计 §3.1，工具 description 与 ProtocolFootnote 双面同文）：
 *  ① **执行失败没有申报通道**（引擎判 `failed`，不由 agent 申报）；
 *  ② `blocked` 保留全部既有语义（可见终态 + FeedbackRouter 重入 + blockCount）；
 *  ③ **verifier 的 `fail` 恒伴随节点 `completed`**（verdict ≠ 节点状态）。
 * detail 必填 / suggestion 可选。execute 只做两件事：
 *  1. 身份双保险之工具内侧：ctx.flowNodeId 空 → 拒绝（挂载面过滤是另一侧，
 *     AgentCore.buildAllowedToolSet 仅 flowNodeSession 注入——TaskBoard 同款双保险）
 *     + **角色值域校验**（ctx.flowNodeRole，非法组合拒并给可行动错误）；
 *  2. NodeReportRegistry.register(sessionId, feedback)（last-write-wins）。
 *
 * 申报后节点照常写收尾报告、正常结束 turn——引擎在会话完成时点 drain 登记表，
 * 按类别分流到既有语义链（零新链）：blocked → blockedNode 终态化（FeedbackRouter
 * 重入协议原样接管）；finish → 既有 completed 语义链；pass → 记 `lastVerdict=pass`
 * + completed 链 + 沿 pass 边投递；fail → 记 `lastVerdict=fail` + **completed 链**
 * （不再是 `failNode`！）+ 沿 `(fail)<目标>:loop` 控制边选通。verify 相位的既有
 * VerdictReader 打回链（旧 loop）与文本锚定降级面（首行裸 BLOCKED / VERDICT:
 * PASS / FAIL）全部保留：未调工具时行为零变化、零放宽。
 */
object NodeReportToolDef extends Tool:

  val Name: String = "node_report"

  /** 通用 blocked 泛值（六类细分之外的兜底；两个角色值域共有）。 */
  val BlockedValue: String = "blocked"

  /** **值域按角色分化**（nrloop 一期 2026-09-12，设计 §3.3 #1 / 附 C2-R1·R2）：两个正交
    * 维度不再压进一个 `category`——
    *   ①「本节点执行成没成」：`finish`（完成，可选显式化）| `blocked`（做不成）；
    *   ②「被判定对象合格不合格」：`pass` | `fail`（**仅 verifier**，verdict 面）。
    * 执行节点拿不到 `pass`/`fail`（工具侧拒），校验节点拿不到 `finish`——分化面在
    * `enumFor(role)` 单点，引擎分流判据（`isFinish`/`isPass`/`isFail`）与之同源。 */
  val FinishValue: String = "finish"
  val PassValue: String = "pass"
  val FailValue: String = "fail"

  /** blocked 泛值之外既有的六类细分（BlockedReader 现状枚举，降级面同源零漂移）。
    * **必须定义在两个值域常量之前**（Scala object 初始化顺序 = 文本顺序；后置会让
    * `BlockedDomain` 读到 null）。 */
  val BlockedCategories: Set[String] = BlockedReader.Categories

  /** **blocked 面完整值域**（泛值 `blocked` + 六类细分）——两个角色共有（设计 §3.1
    * 纪律②：blocked 保留全部既有语义）。单点定义以避免「泛值漏进角色值域」这类
    * 静默收窄（旧 `EnumWhitelist` 是 9 值 = {blocked,pass,fail} ∪ 六类 ⇒ 本批必须
    * 显式带上泛值才是等价面）。 */
  val BlockedDomain: Set[String] = BlockedCategories + BlockedValue

  /** 执行节点（`role=task`）值域：`finish` + blocked 完整面（8 值）。 */
  val TaskCategories: Set[String] = BlockedDomain + FinishValue

  /** 校验节点（`role=verifier`）值域：`pass`/`fail`（verdict）+ blocked 完整面（9 值）。 */
  val VerifierCategories: Set[String] = BlockedDomain ++ Set(PassValue, FailValue)

  /** 全值域（schema enum 白名单所载的 **10 值** = 两角色值域并集；**schema 拿不到会话
    * 身份**——`Tool.inputSchema` 是无参 def（`tools/types.scala`），故 schema 只能写
    * 并集 + 一句话说明「值域取决于节点角色」，真正的角色校验在 `call()` 里按
    * `ctx.flowNodeRole` 单点判）。 */
  val StatusValues: Set[String] = TaskCategories ++ VerifierCategories

  /** 兼容名（既有消费方/测试的读面）：与 [[StatusValues]] 同值。 */
  val EnumWhitelist: Set[String] = StatusValues

  /** 角色 → 合法值域（**单点**）：`verifier` ⇒ verdict 面值域；其余（含 None/未知）⇒
    * 执行节点值域（缺省 = `task`，与 `NodeRoles.normalize` 同口径）。 */
  def enumFor(role: Option[String]): Set[String] =
    if NodeRoles.normalize(role.getOrElse("")) == NodeRoles.Verifier then VerifierCategories
    else TaskCategories

  /** 值域并集内的合法值（用于区分「非法值」与「角色不匹配」两类错误）。 */
  def isValidCategory(category: String): Boolean = StatusValues.contains(category)

  /** drain 分流判据：申报是否走既有 blocked 链（细分六类或泛值 blocked）。 */
  def isBlockedSemantics(category: String): Boolean = BlockedDomain.contains(category)

  /** `finish` = 执行节点显式申报完成（R10：可选显式化——不申报也照常走完成链）。 */
  def isFinish(category: String): Boolean = category == FinishValue

  def isPass(category: String): Boolean  = category == PassValue
  def isFail(category: String): Boolean  = category == FailValue


  /** fail 申报落 failNode 的 result 渲染串（BlockedReader.render 同款风格：
    * 头部标记 + detail + 可选建议——观测面单点格式）。 */
  def renderFail(f: BlockedFeedback): String =
    val sugg = if f.suggestion.trim.isEmpty then "" else s" — 建议: ${f.suggestion}"
    s"[node-report:fail] ${f.detail}$sugg"

  override def name: String = Name

  override def description: String =
    """Report your node's terminal semantics as a structured signal — Flow Map node sessions only. THE VALID CATEGORIES DEPEND ON YOUR NODE ROLE (task vs verifier; your session carries it — an invalid value is rejected with the exact legal list for your role):
(1) role=task (execution node) — (a) FINISH: declare completion explicitly (OPTIONAL: completing is the default behaviour, so a normal finish needs no report; use it when you want the completion to be explicit); (b) BLOCKED: your task CANNOT be completed (upstream deliverables not ready, underspecified task, capability mismatch, missing external condition) — pass a specific blocked category when you know it, or the generic "blocked"; the engine marks the node blocked and re-engages the dispatcher. A task node CANNOT report pass/fail: an execution failure is decided by the engine, not declared by you.
(2) role=verifier (verification node) — (a) PASS: you formally declare the target verified; (b) FAIL: you formally declare the target REJECTED — this is a VERDICT about the object you judged, NOT node failure: the engine keeps THIS node completed and routes the fail verdict along the "(fail)<target>:loop" edge (the target re-runs). (c) BLOCKED works exactly as above.
Either role: BLOCKED semantics (visible terminal state + dispatcher re-entry + blockCount) are unchanged. Never report "failed" — engine-side execution failure has no declaration channel.
Pass category (the verdict + why), detail (what exactly, actionable), suggestion (what would unblock / fix). After calling, finish your report normally — the engine routes the declaration to the matching terminal chain."""

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
          "finish".asJson,
          "pass".asJson,
          "fail".asJson),
        "description" -> ("Terminal semantics — THE LEGAL SET DEPENDS ON YOUR NODE ROLE (the tool rejects a mismatch with the legal list for your role). " +
          "role=task: finish (explicit completion; optional — completing is the default) | upstream-incomplete | task-underspecified | agent-mismatch | external-dependency | needs-split | other | blocked (blocked, reason unclassified). " +
          "role=verifier: pass (the judged target is verified) | fail (the judged target is REJECTED — a verdict about the target, never node failure: this node stays completed and the fail edge routes the re-run) | plus the blocked categories above. " +
          "blocked = your task cannot be completed (the dispatcher reads it verbatim and re-engages); \"failed\" is engine-side and cannot be declared.").asJson
      ),
      "detail" -> Json.obj(
        "type" -> "string".asJson,
        "description" -> "What exactly — concrete, actionable (blocked: the dispatcher reads this verbatim in the reentry brief; fail: what the judged target must fix). Required.".asJson
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
        case Some(_) if category.isEmpty || !isValidCategory(category) =>
          // schema enum 白名单在工具层强制（入参层拒绝，比 BlockedReader 事后归一
          // 更强——spec §4-A ①）：非法值给可修正错误，节点可重调（spec §9.3）。
          IO.pure(Left(ToolError(
            s"node_report: invalid category '$category' — must be one of " +
              s"${StatusValues.toList.sorted.mkString(" | ")} " +
              s"(NODE_REPORT_CATEGORY). Fix and call again.")))
        // 角色值域门（nrloop 一期 2026-09-12，设计 §3.3 #4 / 附 C2-R1·R2）：值域合法
        // 但**不属于本节点角色**（task 报 pass/fail，或 verifier 报 finish）⇒ 拒，且
        // 错误里给出**本节点 role + 本角色合法值清单**（可行动：agent 据此自纠，
        // 不误杀、不降级——设计 §2 R3 表「未标」行的观测面措施）。
        case Some(_) if !enumFor(ctx.flowNodeRole).contains(category) =>
          val role = NodeRoles.normalize(ctx.flowNodeRole.getOrElse(""))
          val legal = enumFor(ctx.flowNodeRole).toList.sorted.mkString(" | ")
          val hint =
            if role == NodeRoles.Verifier then
              "Your role is verifier: declare the VERDICT about the object you judged (pass/fail), or blocked when you cannot verify it. " +
                "'finish' is the execution node's completion marker — a verifier reports pass/fail instead."
            else
              "Your role is task: declare completion with 'finish' (optional — completing is the default) or blocked when the task cannot be completed. " +
                "'pass'/'fail' are the verifier's verdict gate — a task node cannot route them; engine-side execution failure has no declaration channel."
          IO.pure(Left(ToolError(
            s"node_report: category '$category' is not allowed for this node — node role=${role}, legal categories for your role: $legal " +
              s"(NODE_REPORT_CATEGORY_ROLE). $hint")))
        case Some(_) if detail.isEmpty =>
          IO.pure(Left(ToolError(
            s"node_report: 'detail' must be non-empty — say WHAT is the verdict and why " +
              s"(blocked: the dispatcher reads it verbatim). (NODE_REPORT_DETAIL)")))
        case Some(nodeId) =>
          val fb = BlockedFeedback(
            category = category,
            detail = detail,
            suggestion = suggestion)
          // 确认文本：blocked 链沿用 v1 原文（测试/冒烟断言锚零漂移）；**verdict 面按
          // 角色/类别分支**（nrloop 一期 §3.3 #5「回执不误导」）——verifier 的 fail
          // 必须说清「节点本身照常 completed、结论沿 fail 边选通」，否则 agent 会把
          // verdict 读成「我失败了」。判断顺序：blocked ⇒ verdict ⇒ finish。
          val confirm =
            if isBlockedSemantics(category) then
              "[OK] blocked declaration recorded — finish your report normally. " +
                "The engine will mark the node blocked and re-engage the dispatcher; do not fabricate a result."
            else if isPass(category) then
              "[OK] verdict recorded (pass) — finish your report normally. The engine completes this node and delivers " +
                "along the pass edge(s); nothing else is required of you."
            else if isFail(category) then
              "[OK] verdict recorded (fail) — finish your report normally. This is your VERDICT about the judged target: " +
                "THIS node still completes normally (verdict ≠ node status). The fail edge routes the re-run of the target " +
                "node; the engine owns the round cap and will terminalize the loop here if the budget is exhausted."
            else
              "[OK] node report (finish) recorded — finish your report normally. The engine completes this node along the " +
                "default completion chain."
          NodeReportRegistry.register(ctx.sessionId.getOrElse(nodeIdSessionKey(nodeId)), fb).as(
            Right(confirm))
    }

  /** 会话 id 缺失（理论不可达：flow 节点会话恒有 sessionId）时的确定性兜底键——
   * 保持 register 总有键可登记，避免 None 分支吞掉申报。 */
  private def nodeIdSessionKey(nodeId: String): String = s"node-identity:$nodeId"
end NodeReportToolDef
