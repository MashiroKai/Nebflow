package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import nebflow.shared.{NebflowLogger, SubAgentTask}

/**
 * 手动恢复入口（重启手动恢复批，作者令 2026-11-06 20:36 逐字「由于重启/软件崩溃
 * 导致的错误，不用向上通知，不自动恢复，保存状态后，由用户手动恢复，粒度可以是
 * 逐个点击，也可以是子agent面板一键全部恢复」）——把既有 boot 自动复活腿
 * （`SubAgentStartupRecovery.sweep` 的复活分派）翻成**用户触发**模式。
 *
 * == 资格与入口 ==
 *   - **可恢复行**（`isRestorableRow`）：`pending-recovery`（手动模式 boot sweep 的
 *     落点态）/ `frozen`（模型失效静默等待行）/ `failed` ∧ [[SubAgentStartupRecovery.RestartLossFingerprint]]
 *     前缀（旧自动模式留下的重启丢失行）。自身失败（非重启指纹）的 failed 行**不在**
 *     恢复视野——那是诚实终态，不是重启致损。
 *   - **逐个恢复**（`restoreOne`）：面板行点击 → 本件 → [[DelegateRevival.revive]]
 *     （同 id 续原转录；资格闸/墓碑/预算/座位照守——杀过的任务不会被面板键复活）。
 *   - **一键全部恢复**（`restoreAll`）：子 agent 面板按钮 → 本件 → 全量扫描 +
 *     `DelegateRevival.eligibility` 逐条过滤 → [[DelegateRevival.reviveAll]]
 *     （并发信号量护栏 + 单条失败落 salvage 链留行待重试，绝不静默悬挂）。
 *
 * 零通知纪律：恢复成功/失败都**不发任何父会话通知**——呈现面只有子 agent 面板
 * （行状态翻 running 即可见）；`DelegateRevival.revive` 自身的 wsSend 状态帧照走
 * （那是子会话帧，不是向上通知）。
 */
object ManualRevival:

  private val logger = NebflowLogger.forName("nebflow.agent.manual-revival")

  /** 可恢复状态集（`restoreAll` 的扫描窗）。 */
  val RestorableStatuses: Set[String] =
    Set(SubAgentStartupRecovery.PendingRecoveryStatus, SubAgentStartupRecovery.FrozenStatus)

  /** 一行是否可手动恢复（状态面判据；资格闸在 [[DelegateRevival.eligibility]]）。 */
  def isRestorableRow(t: SubAgentTask): Boolean =
    RestorableStatuses.contains(t.status) || SubAgentStartupRecovery.isRestartLostRow(t)

  /** 全量扫描可恢复行（面板「一键全部恢复」前的预告读数同源）。 */
  def restorableRows(store: SubAgentTaskStore): IO[List[SubAgentTask]] =
    store.findAllTasks().map(_.filter(isRestorableRow))

  /**
   * 逐个恢复：找到行 → 资格闸 → 复活。返回 `Right(())` = 已复活（行翻 running）；
   * `Left(reason)` = 找不到行 / 不可恢复态 / 资格不满足 / spawn 失败（文案可直显面板）。
   */
  def restoreOne(deps: DelegateRevival.Deps, store: SubAgentTaskStore, taskId: String): IO[Either[String, Unit]] =
    store.findByTaskId(taskId).flatMap {
      case None => IO.pure(Left(s"no task row for $taskId"))
      case Some(t) if !isRestorableRow(t) =>
        IO.pure(
          Left(s"task $taskId is not restorable (status=${t.status} — only pending-recovery / frozen / restart-lost rows)")
        )
      case Some(t) =>
        val sessionStore = deps.resources.sessionStore
        DelegateRevival.eligibility(sessionStore, t).flatMap {
          case Some(reason) => IO.pure(Left(s"task $taskId not eligible: $reason"))
          case None =>
            logger.info(s"[manual-revival] restoring $taskId (status=${t.status})").as(()) *>
              DelegateRevival
                .revive(deps, t)
                .flatTap {
                  case Right(_) => resumeSavedScope(t.taskId)
                  case Left(_) => IO.unit
                }
                .handleErrorWith(e =>
                  IO.pure(Left(s"task $taskId revive errored: ${Option(e.getMessage).getOrElse("error")}"))
                )
        }
    }

  /**
   * 一键全部恢复：扫描可恢复行 → 逐条资格过滤 → 并发护栏下批量复活。
   * 返回 (revived, skipped with reasons)。
   */
  def restoreAll(
    deps: DelegateRevival.Deps,
    store: SubAgentTaskStore
  ): IO[(List[String], List[(String, String)])] =
    // deps 只在真有可恢复行时解引用：空恢复视野是**真空操作**（面板无货时点
    // 「一键全部恢复」不得因装配面缺席而抛——单点空跑，判据见 ManualRevivalSpec MR4）。
    restorableRows(store).flatMap { rows =>
      if rows.isEmpty then IO.pure((Nil, Nil))
      else
        val sessionStore = deps.resources.sessionStore
        logger
          .info(
            s"[manual-revival] restore-all: ${rows.size} restorable row(s) — ${rows.map(r => s"${r.taskId}(${r.status})").mkString(", ")}"
          )
          .as(()) *>
          rows
            .traverse { t =>
              DelegateRevival.eligibility(sessionStore, t).map(reason => (t, reason))
            }
            .flatMap { judged =>
              val eligible = judged.collect { case (t, None) => t }.sortBy(-_.spawnedAt)
              val skipped = judged.collect { case (t, Some(r)) => (t.taskId, r) }
              if eligible.isEmpty then IO.pure((Nil, skipped))
              else
                DelegateRevival
                  .reviveAll(
                    deps,
                    eligible,
                    // 复活失败的行不静默：落 WARN（台账行由 revive 链自身落），面板
                    // 行保持原状态，用户可逐个重试。
                    salvage = t =>
                      logger
                        .warn(s"[manual-revival] revive failed for ${t.taskId} — row left as-is for retry")
                        .as(())
                  )
                  .flatMap(ref => ref.get.flatTap(_.traverse_(resumeSavedScope)).map(rev => (rev, skipped)))
            }
    }

  private def resumeSavedScope(id: String): IO[Unit] =
    nebflow.core.project.DelegateFlowHosts.get(id).flatMap {
      case Some(host) => host.store.snapshot.flatMap(s => host.engine.resumeSavedNodes(s.manualRecoveryNodes)).void
      case None => IO.unit
    }

end ManualRevival
