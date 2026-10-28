package nebflow.core.delegate

import cats.effect.IO
import cats.syntax.all.*
import nebflow.core.GitWorktrees
import nebflow.core.project.TaskLedgerStore
import nebflow.shared.NebflowLogger

/**
 * DelegateLedgerReconcile —— **台账 ↔ delegate 面板两层状态打通**（监督链自动恢复
 * 批增补，2026-10-22 作者补充需求；设计
 * `docs/design/delegate-supervision-revival-design.zh-CN.md` 增补节）。
 *
 * == 关闭的两个脱节 ==
 *  - **台账→面板**：Task 工具把台账条目 complete/close 掉，面板 delegate 行不收敛
 *    （两层零联动）。且 sweep 收殓腿**绕过 `DelegateTerminal.onTerminalCleanup`**——
 *    既有 AutoArchive（终态自动归档）不生效，被标「重启打断·已中断」的收殓行永驻
 *    现役窗（读面判据「旗 ∧ 未归档」，无收敛出口）。
 *  - **面板→台账**：delegate 终态交付后台账零痕迹（反向脱节）。
 *
 * == 三条触发腿（分工红线）==
 *  - 归档写点唯一：`DelegateRegistry.archive`（与既有 AutoArchive 同方法）。
 *  - 旗写点唯一：`markRestartInterrupted`（语义不变）；腿 3 是它的下游对账。
 *  - **复活行（running/未归档/无旗）三条腿都不碰**。
 *
 * 腿 1 [[onLedgerTerminal]]：TaskTool complete/close 成功后——关联记录（taskId
 * 命中）且任务行终态（completed/failed/cancelled；行缺失 = 完成宽限已清，同视终态）
 * → 归档折叠；在飞（running/restarting/ready-to-merge）→ 不替它收尸。
 *
 * 腿 2（在 `DelegateTerminal.onTerminalCleanup` 序列内，verdictLine 邻位）：
 * 终态交付自动台账记账（`ledgerSettlementNote`）——至少 note 级推进，复活注记随行
 * （revivalCount>0 → 「经 N 次重启复活续作」）。
 *
 * 腿 3 [[reconcileInterruptedRecords]]：boot sweep 收尾（markInterruptedRecords 之后）
 * 对「假中断候选」（旗在 ∧ 未归档）逐条裁定——台账条目终态 → 归档（AutoArchive 窗口
 * 延伸到 sweep 收殓行）；台账 open 但分支已并入基分支 → 归档 + 台账 note（合并事实
 * 佐证，父零打扰）；台账 open 且无证据 / 无台账关联 → **旗保留**（真中断，保守）。
 */
object DelegateLedgerReconcile:

  private val logger = NebflowLogger.forName("nebflow.delegate.ledger-reconcile")

  /** 任务行终态集（ready-to-merge 不算——合并轮在飞，不算收口）。 */
  private val TerminalTaskStatuses = Set("completed", "failed", "cancelled")

  /** 腿 3 汇总（boot 日志行 + 测试断言面）。 */
  final case class Report(
      archivedByLedger: List[String] = Nil,
      archivedByMerge: List[String] = Nil,
      kept: List[String] = Nil
  ):
    def summary: String =
      s"ledger=${archivedByLedger.size} merge=${archivedByMerge.size} kept=${kept.size}"

  // ── 腿 1：台账 → 面板 ─────────────────────────────────────────────────

  /**
   * TaskTool complete/close 成功后的面板联动（fail-soft：任何失败只 WARN，绝不
   * 影响工具结果——台账写已成功，面板收敛是观测面补强）。`store` 经参数注入
   * （测试接缝，TaskTool 从 ToolContext.sharedResources 传入）。
   */
  def onLedgerTerminal(store: nebflow.core.SubAgentTaskStorePort, entryId: String): IO[Unit] =
    IO.blocking(DelegateRegistry.list().filter(_.taskId.contains(entryId)))
      .flatMap { matched =>
        if matched.isEmpty then IO.unit
        else
          logger.info(
            s"[ledger-reconcile] ledger entry '$entryId' reached a terminal state — reconciling ${matched.size} linked delegate row(s)"
          ) *> matched.traverse_ { rec =>
            store.findByTaskId(rec.id).flatMap {
              case Some(t) if !TerminalTaskStatuses.contains(t.status) =>
                // 在飞（running/restarting/ready-to-merge）——delegate 还活着或
                // 合并轮在飞，不替它收尸（面板保持活跃是真值）。
                IO.unit
              case _ =>
                // 终态行 ∨ 行缺失（完成宽限期后 removeTask 清零）⇒ 台账已裁定
                // （complete=实际完成 / close=撤单收口）⇒ 归档折叠。
                archiveLinked(rec.id, reason = s"ledger entry '$entryId' terminal")
            }
          }
      }
      .handleErrorWith(e =>
        logger.warn(s"[ledger-reconcile] onLedgerTerminal('$entryId') failed (panel left as-is): ${e.getMessage}")
      )

  private def archiveLinked(delegateId: String, reason: String): IO[Unit] =
    IO.blocking(DelegateRegistry.archive(delegateId)).attempt.flatMap {
      case Right(_) =>
        logger.info(s"[ledger-reconcile] delegate row '$delegateId' archived ($reason) — folded out of the active window")
      case Left(err) =>
        logger.warn(s"[ledger-reconcile] archive failed for '$delegateId': $err")
    }

  // ── 腿 2：面板 → 台账（onTerminalCleanup 序列内调用）───────────────────

  /**
   * 终态交付的台账记账（`DelegateTerminal.onTerminalCleanup` verdictLine 邻位）：
   * `record.taskId` 定义时往台账 append 一条结算 note（复活注记读任务行
   * revivalCount——>0 即「经 N 次重启复活续作」）。台账条目已删 = notFound 吞 WARN
   * （既有清理序列的 fail-soft 纪律：记账绝不拖垮终态三清）。
   */
  def ledgerSettlementNote(
    store: nebflow.core.SubAgentTaskStorePort,
    record: Option[DelegateRegistry.Record],
    delegateId: String,
    taskStatus: String
  ): IO[Unit] =
    record.flatMap(_.taskId) match
      case None => IO.unit
      case Some(tid) =>
        store.findByTaskId(delegateId).attempt.flatMap {
          case Left(err) =>
            logger.warn(s"[ledger-reconcile] settlement-note task-row read failed for $delegateId: ${err.getMessage}")
          case Right(row) =>
            val revivalNote = row.flatMap(_.revivalCount).filter(_ > 0).map(n => s"，经 $n 次重启复活续作").getOrElse("")
            IO.blocking(
              TaskLedgerStore
                .open()
                .appendNoteSync(
                  tid,
                  s"delegate $delegateId 终态 $taskStatus（${record.map(_.title).getOrElse(delegateId)}）—— 结果已回投派发会话$revivalNote",
                  from = nebflow.core.project.TaskLedgerHistory.Origins.Nebula,
                  actor = nebflow.core.project.TaskLedgerHistory.Actors.Nebula
                )
            ).attempt.flatMap {
              case Right(_) => IO.unit
              case Left(err) =>
                logger.warn(s"[ledger-reconcile] settlement note failed for $delegateId (ledger entry '$tid'): $err")
            }
        }

  // ── 腿 3：boot 对账（假中断候选的逐条裁定）─────────────────────────────

  /** boot sweep 收尾调用（markInterruptedRecords 之后）：旗先落、对账后收敛。 */
  def reconcileInterruptedRecords(store: nebflow.core.SubAgentTaskStorePort): IO[Report] =
    IO.blocking(DelegateRegistry.list().filter(r => r.restartInterruptedAt.isDefined && r.archivedAt.isEmpty))
      .flatMap { candidates =>
        if candidates.isEmpty then IO.pure(Report())
        else
          logger.info(s"[ledger-reconcile] boot reconcile: ${candidates.size} restart-interrupted row(s) to adjudicate") *>
            candidates.traverse(c => adjudicateOne(store, c)).map { reports =>
              val merged = reports.foldLeft(Report())((a, b) =>
                Report(
                  archivedByLedger = a.archivedByLedger ++ b.archivedByLedger,
                  archivedByMerge = a.archivedByMerge ++ b.archivedByMerge,
                  kept = a.kept ++ b.kept
                )
              )
              logger.info(s"[ledger-reconcile] boot reconcile done: ${merged.summary}")
              merged
            }
      }
      .handleErrorWith(e =>
        logger.warn(s"[ledger-reconcile] boot reconcile failed (flags left as-is): ${e.getMessage}").as(Report())
      )

  /** 单条假中断候选的裁定（逐条 fail-soft 不串扰）。 */
  private def adjudicateOne(store: nebflow.core.SubAgentTaskStorePort, rec: DelegateRegistry.Record): IO[Report] =
    (rec.taskId match
      case None => IO.pure(Report(kept = List(rec.id))) // 无台账关联——真中断待再派发
      case Some(tid) =>
        IO.blocking(TaskLedgerStore.open().findSync(tid)).flatMap {
          case Some(entry) if TaskLedgerStore.Status.isTerminal(entry.status) =>
            // 台账已裁定（completed=实际完成 / closed=撤单收口）⇒ 归档折叠——
            // AutoArchive 窗口延伸到 sweep 收殓行（它们没走 onTerminalCleanup）。
            archiveLinked(rec.id, reason = s"ledger entry '$tid' is ${entry.status}").as(
              Report(archivedByLedger = List(rec.id))
            )
          case Some(_) =>
            // 台账 open：合并佐证裁定——分支已并入基分支 = 完成的最硬证据。
            if mergeEvidence(rec) then
              val note =
                s"delegate ${rec.id} 的面板行标「重启打断」，但分支 `${rec.branch.getOrElse("?")}` 已并入基分支——按合并事实归档面板行（台账仍 open，请按需 complete）"
              IO.blocking(
                TaskLedgerStore
                  .open()
                  .appendNoteSync(
                    tid,
                    note,
                    from = nebflow.core.project.TaskLedgerHistory.Origins.Nebula,
                    actor = nebflow.core.project.TaskLedgerHistory.Actors.Nebula
                  )
              ).attempt.flatMap {
                case Left(err) =>
                  logger.warn(s"[ledger-reconcile] merge-evidence note failed for '$tid': $err")
                case Right(_) => IO.unit
              } *> archiveLinked(rec.id, reason = "branch merged into base").as(
                Report(archivedByMerge = List(rec.id))
              )
            else IO.pure(Report(kept = List(rec.id))) // 真中断——旗保留
          case None =>
            // 台账条目已删：无裁定源——保守保留旗（不替作者猜）。
            IO.pure(Report(kept = List(rec.id)))
        }
    ).handleErrorWith(e =>
      logger.warn(s"[ledger-reconcile] adjudicate failed for '${rec.id}': ${e.getMessage}").as(Report(kept = List(rec.id)))
    )

  /** 合并佐证：分支 ∧ 基分支 ∧ 工作区可解析 ∧ 分支已并入基分支（isAncestor）。 */
  private def mergeEvidence(rec: DelegateRegistry.Record): Boolean =
    (rec.branch, rec.baseBranch) match
      case (Some(branch), Some(baseBranch)) =>
        scala.util.Try(os.Path(rec.projectWorkspace.getOrElse(rec.cwd))).toOption.exists { ws =>
          GitWorktrees.isAncestor(ws, branch, baseBranch).contains(true)
        }
      case _ => false

end DelegateLedgerReconcile
