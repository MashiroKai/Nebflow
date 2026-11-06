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
 *     `isAncestor(branch→base) == true`——领先 > 0 = 还有未落地工作，绝不删；
 *   - **在飞守卫**（引擎会话治理专项 2026-11-03，作者令 14:38 · 缺陷一）：
 *     `allowLiveOwner = false` 的**队列侧**调用（grant 账实核对 / care-tick
 *     stale-holder 放窗 / boot reconcile / owner-gone 核对）必须先查会话注册表活
 *     状态——属主会话**仍在场**（[[WorktreeCandidates.LiveSessions]] 端口）或记录带
 *     **停靠/冻结**标记（`standbySince` / `frozenSince`）⇒ 一律保留现场（`kept` 行
 *     具名 `in-flight guard`）。实证（chain 1460 账本行 14:04:52）：合并机曾把
 *     dispatcher 会话 `delegate-nebflow-bb1a8bbb`（standby、12 节点在飞）的
 *     worktree ＋ 分支引用 ＋ worktree 注册一并删除，整条串行链的座位瞬间消失。
 *   - **属主终态腿例外**（`allowLiveOwner = true`）：属主**自己的**终态序列
 *     （[[DelegateTerminal]] 合并轮 verdict 链 / [[MergeQueue.onTerminal]] 的
 *     merge-round 关账 / [[nebflow.core.executor.ExternalSessionRunner]] settle）里
 *     「分支已合并」与「会话正在收尾」是同一件事，此时会话仍在 agentRegistry 是常态
 *     ——该腿行为与本批前**逐字节相同**（否则每个正常收尾的 delegate 都留下永不回收
 *     的现场）。
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

  /** 在飞守卫的行内具名锚（审计 / spec greppable）。 */
  val InFlightGuardReason: String = "in-flight guard"

  /**
   * 在飞读数（三层，任一命中即「该会话还在飞，现场绝不可清」）：
   *   ① 会话注册表活状态（[[WorktreeCandidates.LiveSessions]] 端口——生产实现 =
   *      `agentRegistry` 在场判据）；
   *   ② 记录带待命停靠标记 `standbySince`（重启后端口必空——盘面标记是唯一活口，
   *      正是 bb1a8bbb 形态）；
   *   ③ 记录带错误冻结标记 `frozenSince`（冻结 = 故意的停，等唤醒续做）。
   */
  private[delegate] def inFlightReason(rec: DelegateRegistry.Record): IO[Option[String]] =
    WorktreeCandidates.LiveSessions.liveAmong(List(rec.id)).map { live =>
      if live.contains(rec.id) then
        Some(
          s"$InFlightGuardReason: the owner session '${rec.id}' is LIVE in the session registry — the merge flow never " +
            "cleans an in-flight session's scene (worktree + branch + registration kept)"
        )
      else if rec.standbySince.isDefined then
        Some(
          s"$InFlightGuardReason: the registry record is PARKED in standby (standbySince=${rec.standbySince.get}) — a parked " +
            "session is still in flight (restart-survivable marker; the session registry reads empty after a restart)"
        )
      else if rec.frozenSince.isDefined then
        Some(
          s"$InFlightGuardReason: the registry record is FROZEN (frozenSince=${rec.frozenSince.get}) — a frozen session keeps " +
            "its scene for the wake-up"
        )
      else None
    }

  /**
   * 守卫 + 回收（fail-soft 全包：任何一步失败只 WARN + 账本行，绝不抛、绝不
   * force）。返回是否执行了移除（审计/测试面）。
   *
   * @param allowLiveOwner true = 属主**自己的**终态序列在调（会话正在收尾，活状态
   *                       不是「在飞」）——跳过在飞守卫，行为与本批前逐字节相同；
   *                       false（缺省）= 队列侧/扫描侧调用 → 先查在飞（见类注）。
   */
  def run(
    rec: Option[DelegateRegistry.Record],
    allowLiveOwner: Boolean = false,
    expiredTerminal: Boolean = false
  ): IO[Boolean] =
    rec match
      case None => IO.pure(false)
      case Some(r) =>
        (r.worktree, r.branch, r.baseBranch) match
          case (Some(wt), Some(branch), Some(baseBranch)) =>
            // TTL 清理腿可以消费超期 standby/frozen 记录（它们是用户新规指定的
            // 有限期保存态，不再无限保留）；但即便如此，活体 actor 仍绝对不可清。
            val guard: IO[Option[String]] =
              if allowLiveOwner then IO.pure(None)
              else if expiredTerminal then
                WorktreeCandidates.LiveSessions.liveAmong(List(r.id)).map { live =>
                  Option.when(live.contains(r.id))(
                    s"$InFlightGuardReason: the owner session '${r.id}' is LIVE in the session registry"
                  )
                }
              else inFlightReason(r)
            guard.flatMap {
              case Some(reason) => IO.blocking(keep(r, wt, branch, reason))
              case None         => IO.blocking(reclaim(r, wt, branch, baseBranch, expiredTerminal))
            }
          case _ =>
            // 无 worktree / 无分支锚 = 非合并现场（就地腿 / 非 git）——零动作
            IO.pure(false)

  /** 零领先守卫 + 移除腿（在飞守卫通过后的既有本体，逐字保留）。 */
  private def reclaim(
    r: DelegateRegistry.Record,
    wt: String,
    branch: String,
    baseBranch: String,
    expiredTerminal: Boolean = false
  ): Boolean =
    Try(os.Path(r.projectWorkspace.getOrElse(r.cwd))).toOption match
      case None =>
        keep(r, wt, branch, "base workspace path unresolvable")
      case Some(ws) =>
        val ahead    = GitWorktrees.commitsAhead(ws, baseBranch, branch)
        val ancestor = GitWorktrees.isAncestor(ws, branch, baseBranch)
        val zeroAhead = ahead == 0 && ancestor.contains(true)
        // TTL 回收腿（expiredTerminal=true，终态 7 天 TTL 批 2026-11-06）：树按 git
        // 本体纪律移除（无 --force——脏/锁定树被 git 拒绝 = 现场保留）；**分支删除
        // 仍守零领先**——未落地提交绝不因 TTL 消失（树清走、分支留档）。既有合并腿
        // （expiredTerminal=false）行为逐字节不变（整场守卫在前）。
        if !expiredTerminal && (ahead > 0 || !ancestor.contains(true)) then
          keep(
            r,
            wt,
            branch,
            s"zero-ahead guard: commitsAhead=$ahead isAncestor=${ancestor.fold("unreadable")(_.toString)} — work kept"
          )
        else
          // 抢救守卫（作者令 2026-10-24「worktree 清理抢救 reports/
          // evidence」，[[WorktreeRescue]]）：删除前把主工作区同相对
          // 路径缺失的 reports/evidence 复制回主工作区（已存在的跳过；
          // fail-soft，绝不阻断清理）——只挂在移除分支，kept 现场不经此。
          val rescue = WorktreeRescue.rescue(ws, wt)
          val rm      = GitWorktrees.removeWorktree(ws, wt)
          val bd      = if rm.isRight && (zeroAhead || !expiredTerminal) then GitWorktrees.deleteBranch(ws, branch) else Right("")
          // 既有合并腿语义逐字节保留：removed = 树与分支都清走才 true；TTL 回收腿
          // 的分支删除可以合法跳过（未领先守卫拦下）⇒ 树清走即 removed。
          val removed = if expiredTerminal then rm.isRight else rm.isRight && bd.isRight
          row(
            r,
            if removed then "removed" else "kept",
            s"worktree=$wt branch=$branch base=$baseBranch " +
              (if expiredTerminal then s"leg=terminal-ttl-expired zeroAhead=$zeroAhead " else "") +
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
