package nebflow.core.delegate

import cats.effect.IO
import nebflow.core.GitWorktrees
import nebflow.shared.NebflowLogger

import scala.util.Try

/**
 * MergeEnqueue —— 合并队列**入队强保证**面（merge-fifo 批 2026-11-03 · 作者报障
 * 「两个会话都已经报了 merge，为什么还没收到通知」）。
 *
 * == 病灶（只读侦察已确认的代码坐标）==
 *
 * 修复前入队**只发生在回合终态腿**（[[DelegateTerminal.holdForMerge]] /
 * [[nebflow.core.executor.ExternalSessionRunner]] 的 ready-to-merge 腿 →
 * `MergeQueue.enqueueIO`）；`delegate_report(category="merge")` 本身只写一个状态槽
 * （[[nebflow.core.tools.DelegateReportTool]]）。于是任何让终态腿没跑成的路径
 * （重启打断 / 判定降级 / 提前收尾）都会**零台账行 + 零通知**地漏掉——队列侧既不会
 * 排它，也没有任何一条消息告诉任何人「这条分支永远不会被排到」。
 *
 * == 本件是什么 ==
 *
 * 申报点的**预记账**（pending membership）：`delegate_report(merge)` 落槽成功后
 * 立刻把成员记进账本（[[MergeQueue.enqueuePendingIO]]——只记成员、不 pump，此刻
 * 回合尚未收尾），这样最坏情况退化成「成员停在 waiting 等下一拍」，而不是「什么都不存在」。
 * 回合收尾的重入队（[[MergeQueue.enqueue]]，状态仍 waiting ⇒ 落 pump）与扫描拍
 * （[[MergeQueueCare]] → careTick 每项目补一拍 pump）把这条预记账推进到放行。
 *
 * **零猜**：成员资格要求 `branch ∧ baseBranch` 两锚（与 [[DelegateTerminal.holdForMerge]]
 * 的「缺锚不猜 main」同纪律）。缺锚 = None/`false`，调用方如实回执——绝不把合并
 * 目标猜成 main。
 *
 * 分工（同族三件）：
 *   - [[MergeEnqueueWatch]]：**活性守卫**——申报/终态腿双失时，从「idle + 有未并
 *     交付 + 任务行 running/restarting + 无开放成员」的形态里把漏掉的成员补进队；
 *   - [[MergeQueue.enqueueVerifiedIO]]：终态腿的**核实入队**（入队后核账本，没落地
 *     就发 `enqueue-failed`）；
 *   - 本件：申报点的预记账。
 */
object MergeEnqueue:

  private val logger = NebflowLogger.forName("nebflow.core.delegate.merge-enqueue")

  /**
   * 成员资格构造（纯函数——spec 判红锚）：registry 记录 → 账本成员。
   * `readyAt` = 入队锚（申报点 = 申报时刻；活性守卫腿 = 会话最后活动时刻）。
   * 两锚（`branch` ∧ `baseBranch`）缺一 = None（**绝不猜 main**，同
   * [[DelegateTerminal.holdForMerge]] 的缺锚降级纪律）。
   */
  def membershipOf(rec: DelegateRegistry.Record, readyAt: Long): Option[MergeQueue.Member] =
    for
      branch <- rec.branch
      baseBranch <- rec.baseBranch
    yield
      val baseWs = rec.projectWorkspace.getOrElse(rec.cwd)
      MergeQueue.Member(
        delegateId = rec.id,
        branch = branch,
        baseWs = baseWs,
        baseBranch = baseBranch,
        worktree = rec.worktree.isDefined,
        projectKey = Try(os.Path(baseWs)).map(GitWorktrees.projectKeyOf).getOrElse(baseWs),
        taskId = rec.taskId,
        parentSessionId = rec.parentSessionId,
        description = rec.title,
        readyAt = readyAt,
        channelKind = "internal"
      )

  /**
   * 申报点预记账（`delegate_report(category="merge")` 的成功腿调用；fail-soft：
   * 任何故障 = false + WARN，绝不拖垮申报回执）。
   *
   * 返回 = 「账本此刻确有本成员的开放条目」（新记成功 / 早已在队 —— 两者都是 true）。
   * false 的两形：registry 查无记录；记录缺 `branch`/`baseBranch` 锚（合并目标未知，
   * 引擎不猜）。回执照实显示（[[nebflow.core.tools.DelegateReportTool]] 确认行）。
   */
  def ensurePending(delegateId: String, nowMs: Long): IO[Boolean] =
    IO.blocking(DelegateRegistry.find(delegateId))
      .handleErrorWith(e =>
        logger
          .warn(s"[merge-enqueue] registry read failed for '$delegateId': ${Option(e.getMessage).getOrElse(e.toString).take(200)}")
          .as(Option.empty[DelegateRegistry.Record])
      )
      .flatMap {
        case None =>
          logger
            .warn(s"[merge-enqueue] no registry record for '$delegateId' — merge membership not pre-booked")
            .as(false)
        case Some(rec) =>
          membershipOf(rec, nowMs) match
            case None =>
              logger
                .warn(
                  s"[merge-enqueue] '$delegateId' has no branch/baseBranch anchor (branch=${rec.branch.getOrElse("-")}, " +
                    s"baseBranch=${rec.baseBranch.getOrElse("-")}) — merge target unknown, membership NOT pre-booked (the engine never guesses 'main')"
                )
                .as(false)
            case Some(entry) =>
              MergeQueue
                .enqueuePendingIO(entry)
                .attempt
                .map {
                  case Right(_) =>
                    // 核实（与 enqueueVerifiedIO 同读面）：账本确有该成员的开放条目。
                    val open = Try(MergeQueue.find(delegateId).exists(_.open)).getOrElse(false)
                    if open then
                      logger.info(
                        s"[merge-enqueue] pending membership pre-booked for '$delegateId' (branch '${entry.branch}' → '${entry.baseBranch}', readyAt=${entry.readyAt})"
                      )
                    open
                  case Left(e) =>
                    logger.warn(
                      s"[merge-enqueue] pre-booking failed for '$delegateId': ${Option(e.getMessage).getOrElse(e.toString).take(200)}"
                    )
                    false
                }
      }
  end ensurePending

end MergeEnqueue
