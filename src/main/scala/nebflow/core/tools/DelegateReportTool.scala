package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.delegate.{DelegateInflightGate, DelegateRegistry, DelegateSessions}

/**
 * delegate_report —— delegate 终态语义申报工具（S7/S8；worktree-judge-fifo 批
 * #1224 增补 §3.4）.
 *
 * **对齐 node_report 挂载先例**（NodeReportToolDef / AgentCore 角色门控双保险）：
 *   - 挂载面：`buildAllowedToolSet` 末段按**引擎侧会话身份**（delegate 会话——
 *     `DelegateSessions.isDelegateSession`，`agent` 侧透传）注入；
 *   - 工具内侧：`ctx.sessionId` 同判据拒绝（挂载面过滤是另一侧；兜防御
 *     allowedSet 配置漂移——TaskBoard / node_report 同款双保险）。
 *
 * 值域 `finish | merge`＋detail（#566 修复批 2026-10-19 · 作者令：「delegate 是
 * 不应该有 blocked 型状态的，直接上报就行了」——blocked 类别退役：报不了的腿照常
 * report finish，阻塞事实写进 detail 与终稿文本，判官/面板零 blocked 状态）。
 * （node_report task 角色值域不动：blocked 只归 agentflow 节点。merge 态：working →
 * （分支上工作完成且需合并）report merge（进入等待合并态）→（合并轮完成）report
 * finish（终态汇报）；纯读/无文件变更腿 working → finish 直达。）
 * **仅更新 DelegateRegistry 状态，不携汇报**——汇报仍以终态最后一条消息直传
 * （既有 `source="delegate"` 系统消息面不变）：本工具写的是结构化状态槽
 * （reportStatus / reportDetail），消费点 = 终态判定机（DelegateJudge 的
 * selfReport 输入 + 判定机失败时的回落语义）与任务列表状态面。状态槽是单槽
 * （新申报覆写旧申报）——覆写发生时旧 detail 由 DelegateRegistry.updateReport
 * 留底进 workReport 槽（delegate-finish-report 修复批 2026-10-25：merge 轮的
 * 工作详报不随 finish 轮短报覆写而丢失，合并轮 Release 投父时随附页回传）。
 *
 * 提醒机制（防忘记调用）：delegate 初始 brief 携一行申报纪律（DelegateTool
 * 注入，§16 待过目）＋ 未申报不阻断终态（与 node_report「未申报不终态」同族，
 * delegate 腿的判词由判定机兜底——未申报只是少一个先验，不产生悬挂）。
 */
object DelegateReportTool extends Tool:

  val Name: String = "delegate_report"

  /** 值域（#566：finish | merge 两态——blocked 退役，非法值拒并给去处）。 */
  val FinishValue: String = "finish"
  val MergeValue: String = "merge"

  /**
   * 待命申报（案A 批 2026-10-31 · 作者 11:40 拍板「显式待命态为主干」；状态矩阵批
   * 2026-11-01 收编为矩阵组合 complete×standby）：回合收尾挂起待命——不终判、
   * 不投父、任务行保持 running、会话停靠 Waiting 显「待命」，Mail 到达唤醒续跑。
   */
  val StandbyValue: String = "standby"

  /** 退役类别（旧 brief/旧在飞会话仍可能上报——拒绝并引导 finish，不静默改写）。 */
  val RetiredBlockedValue: String = "blocked"
  val StatusValues: Set[String] = Set(FinishValue, StandbyValue)

  override def name: String = Name

  /** 🔴 模型可见文案——§16 待过目标注：本批最小成稿，终稿归 N3 提示词批。 */
  override def description: String =
    "Declare a development round: finish after committing and verifying the work, or standby while waiting. The workspace and owner remain open for Mail. " +
      "Unmerged commits do not create a merge obligation. Integration and cleanup require a separately authorized project merger request."

  override def inputSchema: JsonObject = JsonObject(
    "type" -> "object".asJson,
    "properties" -> Json.obj(
      "category" -> Json.obj(
        "type" -> "string".asJson,
        "enum" -> Json.arr(FinishValue.asJson, StandbyValue.asJson),
        "description" -> "finish = work complete, committed and verified; nonterminal nodes must finish or be cancelled first. standby = waiting for instructions or downstream work without a completion delivery. Both retain the owner and workspace; merging is a separate authorized merger task.".asJson
      ),
      "detail" -> Json.obj(
        "type" -> "string".asJson,
        "description" -> "What exactly — concrete, actionable (merge: what sits unmerged on the branch; unfinished: the reason it cannot proceed). Required.".asJson
      ),
      "suggestion" -> Json.obj(
        "type" -> "string".asJson,
        "description" -> "What would unblock / fix the task. Optional.".asJson
      )
    ),
    "required" -> Json.arr("category".asJson, "detail".asJson)
  )

  override def summarize(input: JsonObject): String =
    val c = input("category").flatMap(_.asString).getOrElse("?")
    val d = input("detail").flatMap(_.asString).getOrElse("").take(60)
    s"delegate_report($c: $d)"

  override def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 200 then result.take(197) + "..." else result

  override def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val category = input("category").flatMap(_.asString).map(_.trim).getOrElse("")
    val detail = input("detail").flatMap(_.asString).map(_.trim).getOrElse("")
    val suggestion = input("suggestion").flatMap(_.asString).map(_.trim).getOrElse("")

    // 身份双保险之工具内侧（node_report ctx.flowNodeId 拒绝同款模式）：引擎侧
    // 身份判据（DelegateSessions 单点），不信客户端参数。
    if !DelegateSessions.isDelegateSession(ctx.sessionId) then
      IO.pure(
        Left(
          ToolError(
            s"delegate_report: permission denied — this tool is exclusive to delegate sessions (your session carries no delegate identity). " +
              s"Finish your report as normal text output instead. (DELEGATE_REPORT_FORBIDDEN)"
          )
        )
      )
    else if !StatusValues.contains(category) then
      IO.pure(
        Left(
          ToolError(
            (if category == MergeValue then
               "delegate_report: merge is retired. Commit and report finish, or standby. Only the project merger handles explicitly authorized integration requests. "
             else if category == RetiredBlockedValue then
               s"delegate_report: category 'blocked' is retired — there is NO blocked state for delegates. Report finish instead and state the blocker plainly in your detail and final text. "
             else "") +
              s"delegate_report: invalid category '$category' — must be one of " +
              s"${StatusValues.toList.sorted.mkString(" | ")} (DELEGATE_REPORT_CATEGORY). Fix and call again."
          )
        )
      )
    else if detail.isEmpty then
      IO.pure(
        Left(
          ToolError(
            s"delegate_report: 'detail' must be non-empty — say WHAT is the case (merge: what sits unmerged on the branch; unfinished: the reason it cannot proceed). (DELEGATE_REPORT_DETAIL)"
          )
        )
      )
    else
      val id = ctx.sessionId.getOrElse("")
      // 在飞闸（P3 批 2026-11-02 · 作者裁定逐字「finish 要是任务完成了 而不是汇报轮次
      // 完成 有在飞节点是不能接受完成的 需要用 system reminder 提醒 要么取消 要么
      // stand by 等节点完成」）：finish 只在**任务确定完成**时才受理。作用域图里还有
      // 非终态节点 ⇒ **硬拒绝**（工具返回 Left = 调用失败，零写状态槽）。Left 文案
      // 全文进模型上下文（ToolExecResult.isError 渲染），这就是 system reminder 的
      // 送达通道；文案里带结构化错误码 DELEGATE_REPORT_INFLIGHT + 逐个节点读数 +
      // 两条真可执行的出路（NodeCancel / NodeEdit(abandon) 或 standby）。
      // merge / standby 不经本闸：前者是入队等合并、后者是显式未完成。
      val inflightGate: IO[Option[String]] =
        if category == FinishValue then DelegateInflightGate.check(id)
        else IO.pure(None)
      inflightGate.flatMap {
        case Some(refusal) => IO.pure(Left(ToolError(refusal)))
        case None =>
          IO.blocking {
            val ledger = nebflow.core.delegate.WorkspaceLedger.default
            if ledger.owned(id).exists(_.lifecycle != "open") then
              Some("Workspace is reserved or closed; development reports cannot change its lifecycle")
            else if category == FinishValue then
              DelegateRegistry.find(id).flatMap(_.worktree).flatMap { path =>
                nebflow.core.GitWorktrees.dirtyPaths(os.Path(path)) match
                  case Some(Nil) => None
                  case Some(_) =>
                    Some("Commit your workspace changes before reporting finish. Do not merge or remove the worktree.")
                  case None => Some("Workspace status could not be verified; inspect it before reporting finish.")
              }
            else None
          }.flatMap {
            case Some(error) => IO.pure(Left(ToolError(error)))
            case None => writeReport(id, category, detail, suggestion)
          }
      }
    end if
  end call

  /**
   * 申报落槽（原 `call()` 主体，2026-11-02 在飞闸批提为私有方法只为让 `call()` 的
   * `if/else` 链保持表达式式、不嵌套块）：仅状态单写（§3.4）——DelegateRegistry
   * 状态槽；不携汇报、不动终态消息面。
   */
  private def writeReport(
    id: String,
    category: String,
    detail: String,
    suggestion: String
  ): IO[Either[ToolError, String]] =
    IO.blocking(DelegateRegistry.updateReport(id, category, detail, suggestion))
      .flatMap {
        case Left(error) => IO.pure(Left(ToolError(error)))
        case Right(_) =>
          nebflow.core.index.TaskSearchIndex
            .upkeepFireAndForget()
            .as(
              Right(
                if category == StandbyValue then
                  "[OK] standby recorded. This round parks without a completion report. Your workspace and identity remain available for Mail; unmerged commits do not create a merge obligation."
                else
                  "[OK] finish recorded. Write the work result and verification evidence in your final text. Commit workspace changes before ending. The round is reported to the parent and the workspace owner remains available for Mail. Integration requires a separately authorized project merger request; do not merge, push or clean up the worktree yourself."
              )
            )
      }
      .handleError(e => Left(ToolError(s"Report write failed: ${e.getMessage}")))

end DelegateReportTool
