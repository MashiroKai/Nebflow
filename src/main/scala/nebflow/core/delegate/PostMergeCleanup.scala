package nebflow.core.delegate

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.GitWorktrees
import nebflow.shared.NebflowLogger

import scala.util.Try

/**
 * PostMergeCleanup —— 机械验收通过的合并 Release 后的现场回收（引擎加固批
 * 2026-10-09，上游审计 #31 结论 P2-4）.
 *
 * 动作（全 guarded，绝不 force）：
 *   - `git worktree remove <path>`（无 --force——脏/锁定工作树被 git 本体拒绝，
 *     失败仅 WARN 保留现场）；
 *   - `git branch -d <branch>`（小写 -d——git 拒绝未合并分支；零领先守卫的二道闸）；
 *   - 删除事实写 ChainStore `worktree-cleanup` 行（removed / kept + 原因）。
 *
 * 守卫（全部满足才动手）：
 *   - delegate 有 worktree 座位 ＋ branch ＋ baseBranch（缺任一 = 零动作——
 *     就地腿/非 git delegate 无现场可收）；
 *   - **零领先守卫**：`commitsAhead(base..branch) == 0` 且
 *     `isAncestor(branch→base) == true`——领先 > 0 = 还有未落地工作，绝不删。
 *
 * 消费点：内部腿 [[DelegateTerminal.onCompleted]]（合并轮 completed ＋验收通过
 * 的 Release 前回收；**非合并完成绝不清理**——防丢工作）；boot sweep
 * （nebflow.agent.MergeQueueBootSweep）的中断成员 reconciliation（已并分支的
 * 残留现场——同守卫同判据）。delegate 的 actor/cwd 尚在 worktree 内时移除目录
 * 是安全的（进程持陈旧 cwd 句柄，会话/图盘面锚在基工作区不受影响）。
 */
object PostMergeCleanup:

  private val logger = NebflowLogger.forName("nebflow.core.delegate.merge-cleanup")

  /** ChainStore 行种类（新行种类零迁移——ChainStore 通用行形态）。 */
  val EntryKind: String = "worktree-cleanup"

  /**
   * 守卫 + 回收（fail-soft 全包：任何一步失败只 WARN + 账本行，绝不抛、绝不
   * force）。返回是否执行了移除（审计/测试面）。
   */
  def run(rec: Option[DelegateRegistry.Record]): IO[Boolean] = IO.blocking {
    rec match
      case None => false
      case Some(r) =>
        (r.worktree, r.branch, r.baseBranch) match
          case (Some(wt), Some(branch), Some(baseBranch)) =>
            Try(os.Path(r.projectWorkspace.getOrElse(r.cwd))).toOption match
              case None =>
                keep(r, wt, branch, "base workspace path unresolvable")
              case Some(ws) =>
                val ahead    = GitWorktrees.commitsAhead(ws, baseBranch, branch)
                val ancestor = GitWorktrees.isAncestor(ws, branch, baseBranch)
                if ahead > 0 || !ancestor.contains(true) then
                  keep(
                    r,
                    wt,
                    branch,
                    s"zero-ahead guard: commitsAhead=$ahead isAncestor=${ancestor.fold("unreadable")(_.toString)} — work kept"
                  )
                else
                  val rm      = GitWorktrees.removeWorktree(ws, wt)
                  val bd      = if rm.isRight then GitWorktrees.deleteBranch(ws, branch) else Right("")
                  val removed = rm.isRight && bd.isRight
                  row(
                    r,
                    if removed then "removed" else "kept",
                    s"worktree=$wt branch=$branch base=$baseBranch " +
                      s"worktreeRemove=${rm.fold(e => e.take(160), _ => "ok")} " +
                      s"branchDelete=${bd.fold(e => e.take(160), _ => "ok")}"
                  )
                  removed
          case _ =>
            // 无 worktree / 无分支锚 = 非合并现场（就地腿 / 非 git）——零动作
            false
  }

  private def keep(r: DelegateRegistry.Record, wt: String, branch: String, reason: String): Boolean =
    logger.warn(s"post-merge cleanup KEPT the scene for ${r.id}: $reason")
    row(r, "kept", s"worktree=$wt branch=$branch — $reason")
    false

  private def row(r: DelegateRegistry.Record, action: String, detail: String): Unit =
    try
      ChainStore.appendSync(
        ChainStore.keyFor(r.taskId, r.id),
        EntryKind,
        Json.obj(
          "delegateId" -> r.id.asJson,
          "action" -> action.asJson,
          "detail" -> detail.asJson
        )
      )
    catch case e: Throwable => logger.warn(s"worktree-cleanup ChainStore line failed: ${e.getMessage}")

end PostMergeCleanup
