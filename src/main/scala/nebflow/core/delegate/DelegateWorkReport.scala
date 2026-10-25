package nebflow.core.delegate

import nebflow.core.GitWorktrees

import scala.util.Try

/**
 * DelegateWorkReport —— 合并轮 finish 交付的工作结果附页（delegate-finish-report
 * 修复批 2026-10-25 · 作者令逐字：「delegate report merge 这次是比较详细的汇报，
 * 而这次汇报的内容不会回传，而是汇报 delegate report finish……导致工作结果丢失」）.
 *
 * 根因链（三条，全在本批闭合）：
 *   1. 工作轮终稿（judge ready-to-merge 的 resultText）停靠即弃——不落盘、不投父
 *      （[[DelegateTerminal.holdForMerge]] 只入队）。→ 修复：停靠点
 *      `DelegateRegistry.updateWorkResult` 保真（first-write-wins）。
 *   2. `delegate_report` 状态槽是单槽——merge 轮的详细工作汇报被 finish 轮的
 *      「合并完成」短报**覆写**。→ 修复：`updateReport` preserve-before-overwrite
 *      （旧 detail 留底进 workReport）。
 *   3. 合并轮终态投父的 payload 只由合并轮终文组装——父会话只见「合并完成」。
 *      → 修复：合并轮 Release 投递时拼接本附页（`appendixFor`）。
 *
 * 附页三段（全 fail-soft，任一段不可得即整段缺席；三段全空 = 返回 ""——老
 * delegate 逐字退化现状文本，向后兼容）：
 *   - merge status 行：finish 申报 detail 首行（合并信息降为一行状态）；
 *   - work result 段：workReport 槽保真的工作轮终稿（截断边界 [[WorkBound]]）；
 *   - report files 段：合并提交的第一亲 diff 里 basename=REPORT.md 的文件——
 *     路径 + 首行摘要（[[MaxReportFiles]] 个；PostMergeCleanup 的 WorktreeRescue
 *     已保证 reports 落主工作区，此处读基工作区视角）。
 *
 * 消费点：内部腿 [[DelegateTerminal.onCompleted]]（mergeRound Release 尾注）＋
 * 外部腿 ExternalSessionRunner.settle（mergeRoundNow 投父前置）——同一构造器，
 * 两腿同一附页契约。
 */
object DelegateWorkReport:

  /** 附页行标（🔴 模型/用户可见文案，§16 待过目——随批呈报件）。 */
  val Marker: String = "[delegate-finish]"

  /** 截断边界（防附页淹没父会话上下文——摘要不是全文，全文在 REPORT.md）。 */
  object Bound:
    val WorkChars: Int = 2000
    val DigestLines: Int = 40
    val DigestChars: Int = 2400
    val StatusChars: Int = 200

  /** 附页携带的 REPORT.md 文件数上限（多报告形态取 diff 序前 N 个）。 */
  val MaxReportFiles: Int = 3

  /**
   * 附页构造（fail-soft 全包：record 缺失 / git 不可读 / 文件读失败 → 对应段缺席
   * 或整体 ""——绝不抛、绝不阻断终态投递）。
   */
  def appendixFor(record: Option[DelegateRegistry.Record]): String =
    Try {
      record match
        case None => ""
        case Some(rec) =>
          val mergeStatus = rec.reportDetail
            .map(_.trim)
            .filter(_.nonEmpty)
            .map(d => s"$Marker merge status: ${d.take(Bound.StatusChars)}")
          val workResult = rec.workReport
            .map(_.trim)
            .filter(_.nonEmpty)
            .map(w => s"$Marker work result (preserved from the work phase, before the merge round):\n${w.take(Bound.WorkChars)}")
          val reportFiles = reportFilesSection(rec)
          compose(mergeStatus, workResult, reportFiles)
    }.getOrElse("")

  /**
   * 纯组装（spec 判红锚）：三段全空 = ""（老 delegate 逐字退化现状——不产空附页）；
   * 任一段在场 ⇒ 附页以 "\n\n" 前缀接在判词尾注之后。
   */
  private[delegate] def compose(
    mergeStatus: Option[String],
    workResult: Option[String],
    reportFiles: Option[String]
  ): String =
    val parts = List(mergeStatus, workResult, reportFiles).flatten
    if parts.isEmpty then "" else "\n\n" + parts.mkString("\n")

  /**
   * report files 段：合并提交（基分支 tip，双亲 merge）的第一亲 diff 中
   * basename == REPORT.md 的文件（相对基工作区路径 + 内容首段）。判据 fail-soft：
   * 基 tip 非双亲 merge（验收拒绝的注记轮）/ diff 不可读 / 文件缺失 ⇒ None。
   */
  private def reportFilesSection(rec: DelegateRegistry.Record): Option[String] =
    val ws = Try(os.Path(rec.projectWorkspace.getOrElse(rec.cwd))).toOption
    val base = rec.baseBranch
    (ws, base) match
      case (Some(repo), Some(b)) if GitWorktrees.isGitRepo(repo) =>
        val changed = changedReportFiles(repo, b)
        if changed.isEmpty then None
        else
          val entries = changed.take(MaxReportFiles).map { rel =>
            val digest = Try(os.read(os.Path(rel, repo))).getOrElse("")
            s"- $rel\n${digestOf(digest)}"
          }
          Some(s"$Marker report files (auto-attached):\n${entries.mkString("\n")}")
      case _ => None

  /**
   * 合并提交第一亲 diff 中的 REPORT.md 文件（相对路径）：基分支 tip 必须是**双亲
   * merge 提交**（与 MergeAcceptance 同判据——未真并 = Nil，绝不把他人落在基分支
   * 上的提交误归本 delegate），`git diff --name-only <merge^1> <merge>` = 被并分支
   * 带来的全部文件，过滤 basename == REPORT.md（大小写不敏感——报告惯例面）。
   * git 任一步不可读 ⇒ Nil（fail-soft）。
   */
  private[delegate] def changedReportFiles(ws: os.Path, baseBranch: String): List[String] =
    val tip = GitWorktrees.resolveRef(ws, baseBranch)
    val info = GitWorktrees.commitInfo(ws, baseBranch)
    (tip, info) match
      case (Some(mergeSha), Some(ci)) if ci.parents.lengthCompare(2) >= 0 =>
        val p1 = ci.parents.head
        if mergeSha == p1 then Nil
        else
          Try {
            val res = os
              .proc("git", "-C", ws.toString, "diff", "--name-only", s"$p1..$mergeSha")
              .call(check = false, mergeErrIntoOut = true)
            if res.exitCode != 0 then Nil
            else
              res.out.trim().linesIterator.filter(_.trim.nonEmpty).filter { rel =>
                val name = rel.trim.split('/').lastOption.getOrElse("")
                name.equalsIgnoreCase("REPORT.md")
              }.map(_.trim).toList
          }.getOrElse(Nil)
      case _ => Nil

  /** 摘要截断：首 [[Bound.DigestLines]] 行、至多 [[Bound.DigestChars]] 字符。 */
  private[delegate] def digestOf(content: String): String =
    val lines = content.trim.linesIterator.take(Bound.DigestLines).toList
    val head = lines.mkString("\n")
    val cut = head.take(Bound.DigestChars)
    val more =
      if head.length > Bound.DigestChars || content.trim.linesIterator.length > Bound.DigestLines then
        s"\n… (truncated — full report at the path above)"
      else ""
    cut + more

end DelegateWorkReport
