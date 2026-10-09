package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.delegate.{DelegateRegistry, DelegateSessions}

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
 * 值域 `finish | merge | blocked`＋detail（node_report task 角色值域＋merge 态：
 * working →（分支上工作完成且需合并）report merge（进入等待合并态）→（合并轮完成）
 * report finish（终态汇报）；纯读/无文件变更腿 working → finish 直达）。
 * **仅更新 DelegateRegistry 状态，不携汇报**——汇报仍以终态最后一条消息直传
 * （既有 `source="delegate"` 系统消息面不变）：本工具写的是结构化状态槽
 * （reportStatus / reportDetail），消费点 = 终态判定机（DelegateJudge 的
 * selfReport 输入 + 判定机失败时的回落语义）与任务列表状态面。
 *
 * 提醒机制（防忘记调用）：delegate 初始 brief 携一行申报纪律（DelegateTool
 * 注入，§16 待过目）＋ 未申报不阻断终态（与 node_report「未申报不终态」同族，
 * delegate 腿的判词由判定机兜底——未申报只是少一个先验，不产生悬挂）。
 */
object DelegateReportTool extends Tool:

  val Name: String = "delegate_report"

  /** 值域（node_report task 角色值域＋delegate 三态工作机：finish | merge | blocked）。 */
  val FinishValue: String = "finish"
  val MergeValue: String = "merge"
  val BlockedValue: String = "blocked"
  val StatusValues: Set[String] = Set(FinishValue, MergeValue, BlockedValue)

  override def name: String = Name

  /** 🔴 模型可见文案——§16 待过目标注：本批最小成稿，终稿归 N3 提示词批。 */
  override def description: String =
    """Report your state-machine position as a structured signal — delegate sessions only. `category` = "merge" (your work on the branch is complete and needs to land: you enter the waiting-merge state — the engine queues you in the MergeQueue FIFO and sends you a merge reminder when it is your turn; do NOT merge on your own initiative), "finish" (terminal report: pure read / no file changes with nothing to merge, or your merge round has landed — this ends your session), or "blocked" (it cannot be completed), plus a `detail` saying what exactly is the case (actionable). The report updates your session's status record only — your final assistant text remains the deliverable that is handed back; write your own completion report there (what you did, artifacts, open items), self-contained. Unreported does not hang: the engine's judge decides your terminal state from your result either way — the report just makes your own semantics explicit."""

  override def inputSchema: JsonObject = JsonObject(
    "type" -> "object".asJson,
    "properties" -> Json.obj(
      "category" -> Json.obj(
        "type" -> "string".asJson,
        "enum" -> Json.arr(FinishValue.asJson, MergeValue.asJson, BlockedValue.asJson),
        "description" -> "State-machine position: merge = branch work complete, needs to land (enters the waiting-merge FIFO; a merge reminder follows); finish = terminal report (pure read / no file changes, or the merge has landed); blocked = it cannot be completed.".asJson
      ),
      "detail" -> Json.obj(
        "type" -> "string".asJson,
        "description" -> "What exactly — concrete, actionable (merge: what sits unmerged on the branch; blocked: the reason it cannot proceed). Required.".asJson
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
            s"delegate_report: invalid category '$category' — must be one of " +
              s"${StatusValues.toList.sorted.mkString(" | ")} (DELEGATE_REPORT_CATEGORY). Fix and call again."
          )
        )
      )
    else if detail.isEmpty then
      IO.pure(
        Left(
          ToolError(
            s"delegate_report: 'detail' must be non-empty — say WHAT is the case (merge: what sits unmerged on the branch; blocked: the reason it cannot proceed). (DELEGATE_REPORT_DETAIL)"
          )
        )
      )
    else
      val id = ctx.sessionId.getOrElse("")
      // 仅状态单写（§3.4）：DelegateRegistry 状态槽；不携汇报、不动终态消息面。
      val write = IO.blocking(DelegateRegistry.updateReport(id, category, detail, suggestion))
        .handleErrorWith(e => IO.pure(Left(s"delegate_report: status write failed: ${e.getMessage}")))
      write.flatTap {
        // task-rag 批（#1227 / §Batch 7）：delegate 终态增量索引——申报写成功后
        // fire-and-forget upkeep（reportStatus/reportDetail 变化 ⇒ 索引文本 hash
        // 变化 ⇒ 下轮 upkeep 重嵌；检索前另有惰性 upkeep 自愈）。在飞合流 + 异常
        // 内吞，绝不影响本工具结果。🔴 接缝纪律：DelegateRegistry 本体零触碰。
        case Right(_) => nebflow.core.index.TaskSearchIndex.upkeepFireAndForget()
        case Left(_)  => IO.unit
      }.map {
        case Left(err) => Left(ToolError(err))
        case Right(_) =>
          val confirm =
            if category == MergeValue then
              "[OK] merge recorded — you are now waiting-merge (MergeQueue FIFO). Do NOT merge on your own initiative: keep working elsewhere or go idle; the engine sends you a merge reminder when it is your turn. Your final report comes AFTER the merge round (report finish then)."
            else if category == FinishValue then
              "[OK] finish recorded — now write your final report as normal text output; it is the deliverable handed back to the dispatcher."
            else
              "[OK] blocked recorded — write your final report stating the blocker; do not fabricate a result."
          Right(confirm)
      }

end DelegateReportTool
