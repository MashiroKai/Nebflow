package nebflow.core.delegate

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.GitWorktrees
import nebflow.shared.NebflowLogger

import scala.util.Try

/**
 * PostMergeCleanup —— 终态记录到期回收的现场清退腿（终态 7 天 TTL 批的执行半；
 * 唯一调用方 = `nebflow.agent.DelegateTtlSweep`）.
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
 *   - **活体守卫**：`WorktreeCandidates.LiveSessions` 端口判活——属主会话仍在
 *     会话注册表 ⇒ 一律保留现场（`kept` 行具名 `in-flight guard`）。TTL 到期
 *     只证明**终态/保存态**已超期，活体 actor 在场永不清。
 *   - **零领先守卫**：分支删除仍守 `commitsAhead(base..branch) == 0` 且
 *     `isAncestor(branch→base) == true`——领先 > 0 = 还有未落地工作，树清走、
 *     分支留档（未落地提交绝不因 TTL 消失）。worktree 目录按 git 本体纪律移除
 *     （脏/锁定树被拒 = 现场保留）。
 *
 * 删除前的 reports/evidence 抢救守卫（[[WorktreeRescue]]）：主工作区同相对路径
 * 缺失的报告/证据先复制回主工作区（已存在的跳过；fail-soft，绝不阻断清理）——
 * 只挂在移除分支，kept 现场不经此。
 */
object PostMergeCleanup:

  private val logger = NebflowLogger.forName("nebflow.core.delegate.merge-cleanup")

  /** ChainStore 行种类（新行种类零迁移——ChainStore 通用行形态）。 */
  val EntryKind: String = "worktree-cleanup"

  /** 活体守卫的行内具名锚（审计 / spec greppable）。 */
  val InFlightGuardReason: String = "in-flight guard"

  /**
   * 守卫 + 回收（fail-soft 全包：任何一步失败只 WARN + 账本行，绝不抛、绝不
   * force）。返回是否执行了移除（审计/测试面）。
   */
  def run(rec: Option[DelegateRegistry.Record]): IO[Boolean] =
    rec match
      case None => IO.pure(false)
      case Some(r) =>
        (r.worktree, r.branch, r.baseBranch) match
          case (Some(wt), Some(branch), Some(baseBranch)) =>
            // 活体守卫：属主会话仍在会话注册表 = 绝不清（TTL 到期不越过活体红线）。
            WorktreeCandidates.LiveSessions.liveAmong(List(r.id)).flatMap { live =>
              if live.contains(r.id) then
                val reason =
                  s"$InFlightGuardReason: the owner session '${r.id}' is LIVE in the session registry — the " +
                    "expiry reclaim never clears a live session's scene (worktree + branch + registration kept)"
                IO.blocking(keep(r, wt, branch, reason))
              else IO.blocking(reclaim(r, wt, branch, baseBranch))
            }
          case _ =>
            // 无 worktree / 无分支锚 = 非合并现场（就地腿 / 非 git）——零动作
            IO.pure(false)

  /** 零领先守卫 + 移除腿（活体守卫通过后的本体）。 */
  private def reclaim(
    r: DelegateRegistry.Record,
    wt: String,
    branch: String,
    baseBranch: String
  ): Boolean =
    Try(os.Path(r.projectWorkspace.getOrElse(r.cwd))).toOption match
      case None =>
        keep(r, wt, branch, "base workspace path unresolvable")
      case Some(ws) =>
        val ahead = GitWorktrees.commitsAhead(ws, baseBranch, branch)
        val ancestor = GitWorktrees.isAncestor(ws, branch, baseBranch)
        val zeroAhead = ahead == 0 && ancestor.contains(true)
        // 树按 git 本体纪律移除（无 --force——脏/锁定树被 git 拒绝 = 现场保留）；
        // 分支删除仍守零领先——未落地提交绝不因到期回收消失（树清走、分支留档）。
        // 抢救守卫（[[WorktreeRescue]]）：删除前把主工作区同相对路径缺失的
        // reports/evidence 复制回主工作区（已存在的跳过；fail-soft，绝不阻断清理）
        // ——只挂在移除分支，kept 现场不经此。
        val rescue = WorktreeRescue.rescue(ws, wt)
        val rm = GitWorktrees.removeWorktree(ws, wt)
        val bd = if rm.isRight && zeroAhead then GitWorktrees.deleteBranch(ws, branch) else Right("")
        val removed = rm.isRight && bd.isRight
        row(
          r,
          if removed then "removed" else "kept",
          s"worktree=$wt branch=$branch base=$baseBranch " +
            s"leg=terminal-ttl-expired zeroAhead=$zeroAhead " +
            s"worktreeRemove=${rm.fold(e => e.take(160), _ => "ok")} " +
            s"branchDelete=${bd.fold(e => e.take(160), _ => "ok")}" +
            (if rescue.isEmpty then "" else s" ${rescue.summary}")
        )
        removed

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
