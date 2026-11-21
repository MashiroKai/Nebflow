package nebflow.agent

import cats.effect.IO
import cats.effect.Ref
import cats.syntax.all.*
import nebflow.shared.NebflowLogger

/**
 * DelegateTtlSweep —— 死 delegate 的 **TTL 清理面**（终态 7 天 TTL 批 2026-11-06 ·
 * 作者令 22:39「我发现了非常久远的 delegate 任务也在里面。说明我们没有一个死
 * delegate 的一个清理机制。设置跟 work tree 一个 ttl，一周。」）。
 *
 * 判读单源 = [[nebflow.core.delegate.DelegateTerminalTtl]]（终态锚解析 + 三态 +
 * 7 天边界全在那边）；本对象只做**动作编排**（每条 registry 记录）：
 *
 *  1. **在飞守卫（红线）**：活体 actor 在册（agentRegistry）⇒ 零触碰（在飞会话
 *     绝不清理；开放 workspace 的属主座位照常保留）；
 *  2. **到期判读**：`DelegateTerminalTtl.verdict` —— 保存态（standby/frozen）以
 *     停靠时刻为终态锚（作者 20:36 新规：保存态待手动恢复同属终态计时，7 天后
 *     一并清理，保存态不能无限堆积）；任务行终态以 completedAt 为锚；owner-gone
 *     （无任务行）以取消墓碑/重启旗/登记时间为兜底；
 *  3. **清理动作（到期才做，语义=清理）**：
 *      a. 任务行剪行 —— `SubAgentTaskStore.removeTask`（重启丢失行自此离开复活
 *         视野 = 通知源永久沉默的根除腿）；
 *      b. 登记条目移除 —— `DelegateRegistry.remove`（记录文件删除；台账历史由
 *         ChainStore 行与子代理记账留存承载——brief「语义=清理，不丢台账历史
 *         可接受归档」的清理腿）。工作区/分支一律不动（引擎不回收座位——需要
 *         隔离检出时 delegate 自建自管）。
 *
 * 接线（GatewayMain boot）：跑在 subagentCrashSweep **之前**——先把名单外的
 * 远古尸体清走，随后的收殓/标记/对账腿对已清条目天然零视野（已移除条目不进
 * markInterruptedRecords 的**状态派生收口守卫**；任务行已剪行 = 复活视野
 * 永远看不到它们）。
 * 失败软化：单条失败只 WARN 入 kept，绝不阻断 boot。
 */
object DelegateTtlSweep:

  private val logger = NebflowLogger.forName("nebflow.agent.delegate-ttl-sweep")

  final case class Report(
    /** 到期条目（进了清理面的 id，按处理序）。 */
    expired: List[String] = Nil,
    /** 任务行已剪行的 delegate id。 */
    taskRowsPruned: List[String] = Nil,
    /** 登记条目已移除的 delegate id。 */
    recordsRemoved: List[String] = Nil,
    /** 在飞守卫放行的 delegate id（零触碰）。 */
    inFlightKept: List[String] = Nil,
    /** 置顶（阶段③）放行的 delegate id：终态但不许清（七天物理清理零动作）。 */
    pinnedKept: List[String] = Nil,
    /** 到期但清理链部分失败保留现场的 delegate id（fail-soft 留痕）。 */
    keptOnError: List[(String, String)] = Nil,
    /** 孤儿任务行已剪行（死行收口批 2026-11-21：无 registry 记录的到期行）。 */
    orphanRowsPruned: List[String] = Nil
  ):

    def hasWork: Boolean =
      expired.nonEmpty || inFlightKept.nonEmpty || pinnedKept.nonEmpty || keptOnError.nonEmpty ||
        orphanRowsPruned.nonEmpty

    def summary: String =
      s"expired=${expired.size} taskRowsPruned=${taskRowsPruned.size} recordsRemoved=${recordsRemoved.size} " +
        s"orphanRowsPruned=${orphanRowsPruned.size} " +
        s"inFlightKept=${inFlightKept.size} pinnedKept=${pinnedKept.size} keptOnError=${keptOnError.size}"

  end Report

  /** boot 清理入口（GatewayMain 装配链；失败只 WARN 绝不阻塞启动）。 */
  def run(resources: SharedResources): IO[Report] =
    for
      now <- IO(System.currentTimeMillis())
      rows <- resources.subAgentTaskStore.findAllTasks().handleErrorWith { e =>
        logger.warn(s"[delegate-ttl] task-row scan failed: ${e.getMessage}").as(Nil)
      }
      rowsById = rows.map(t => t.taskId -> t).toMap
      live <- resources.agentRegistry.get.map(_.keySet).handleErrorWith { e =>
        logger
          .warn(s"[delegate-ttl] live-session read failed (treating as empty): ${e.getMessage}")
          .as(Set.empty[String])
      }
      records <- IO
        .blocking(nebflow.core.delegate.DelegateRegistry.list())
        .handleErrorWith(e => logger.warn(s"[delegate-ttl] registry read failed: ${e.getMessage}").as(Nil))
      // worktree 现场快照（孤儿行全清 ops 复核批 2026-11-21）：**一次取**，全批共用——
      // 逐条各跑 `git worktree list` 在千条量级是上千次进程叉。快照只对「目录仍在盘上」
      // 的记录取数（纯 os.isDir 预筛）并按仓库根去重，每个根至多一次 git 调用。
      worktrees <- IO
        .blocking(nebflow.core.delegate.DelegateWorktreeRegistry.Snapshot.capture(records))
        .handleErrorWith(e =>
          logger
            .warn(s"[delegate-ttl] worktree snapshot failed (treating as empty): ${e.getMessage}")
            .as(
              nebflow.core.delegate.DelegateWorktreeRegistry.Snapshot.empty
            )
        )
      ref <- Ref.of[IO, Report](Report())
      _ <- records.traverse_ { rec =>
        // ① 在飞守卫（红线）：活体 actor ⇒ 零触碰。
        resources.executionProtection(rec.id).flatMap {
          case nebflow.shared.ExecutionProtection.Protected(_) =>
            ref.update(r => r.copy(keptOnError = r.keptOnError :+ (rec.id -> "protected-execution")))
          case nebflow.shared.ExecutionProtection.Unknown(reason) =>
            ref.update(r => r.copy(keptOnError = r.keptOnError :+ (rec.id -> s"protection-unknown: $reason")))
          case nebflow.shared.ExecutionProtection.Unprotected if live.contains(rec.id) =>
            ref.update(r => r.copy(inFlightKept = r.inFlightKept :+ rec.id))
          case nebflow.shared.ExecutionProtection.Unprotected =>
            // ② 到期判读（单源；worktree 现场快照全批共用）。
            nebflow.core.delegate.DelegateTerminalTtl.verdict(rec, rowsById.get(rec.id), now, worktrees) match
              case nebflow.core.delegate.DelegateTerminalTtl.InFlight => IO.unit
              case nebflow.core.delegate.DelegateTerminalTtl.Pinned =>
                // 阶段③ 置顶（重要记录）：终态也不清——只留痕，零动作。
                ref.update(r => r.copy(pinnedKept = r.pinnedKept :+ rec.id))
              case _: nebflow.core.delegate.DelegateTerminalTtl.WithinTtl => IO.unit
              case expired: nebflow.core.delegate.DelegateTerminalTtl.Expired =>
                // ③ 清理动作（任务行剪行 → 登记移除；fail-soft）。工作区/分支一律
                // 不动（引擎不再回收座位——需要隔离检出时 delegate 自建自管）。
                resources.executionCommitPort
                  .withUnprotectedSession(rec.id) {
                    for
                      _ <- rowsById.get(rec.id).traverse_ { t =>
                        resources.subAgentTaskStore.removeTask(t.parentSessionId, t.taskId)
                      }
                      rm <- IO(nebflow.core.delegate.DelegateRegistry.remove(rec.id)).attempt
                      _ = logger.info(
                        s"[delegate-ttl] '${rec.id}' expired the " +
                          s"${nebflow.core.delegate.DelegateTerminalTtl.TtlMs / 86400000L}-day terminal TTL " +
                          s"(anchor=${expired.anchorName} age=${(now - expired.anchorMs) / 86400000L}d) — " +
                          s"taskRow=${if rowsById.contains(rec.id) then "pruned" else "none"} " +
                          s"record=${rm.fold(err => s"kept($err)", _ => "removed")}"
                      )
                      _ <- ref.update { r =>
                        r.copy(
                          expired = r.expired :+ rec.id,
                          taskRowsPruned =
                            if rowsById.contains(rec.id) then r.taskRowsPruned :+ rec.id else r.taskRowsPruned,
                          recordsRemoved = if rm.isRight then r.recordsRemoved :+ rec.id else r.recordsRemoved,
                          keptOnError = rm.fold(err => r.keptOnError :+ (rec.id -> err.getMessage), _ => r.keptOnError)
                        )
                      }
                    yield ()
                  }
                  .handleErrorWith(e =>
                    ref.update(r =>
                      r.copy(keptOnError =
                        r.keptOnError :+ (rec.id ->
                          s"cleanup-unconfirmed: ${e.getClass.getSimpleName}")
                      )
                    )
                  )
        }
      }
      report <- ref.get
      // ── 孤儿任务行收口（死行收口批 2026-11-21）──────────────────────────────
      // 为什么另立一条腿：上面整条链按 registry 记录编排——**没有登记条目**的任务行
      // 完全不在它的视野里。落盘实测（2026-11-21）：114 条 pending-recovery 行中有
      // 93 条的 rootSessionId 为空、且没有任何 registry 记录 ⇒ 这些行既不会被清理、
      // 也不会进任何收殓视野，只能永久挂在面板上。它们是「行还在、会话早没了」的尸体，
      // 与 registry 记录是否还在无关，因此必须按**行本身**判到期。
      // 判据仍是单源 [[nebflow.core.delegate.DelegateTerminalTtl.rowVerdict]]（终态值域
      // 已含 pending-recovery）＋ 同一把 7 天尺子；保护面（protected/unknown）与活体
      // actor 照旧零触碰。
      orphanReport <- sweepOrphanRows(resources, rowsById, records.map(_.id).toSet, live, now)
      merged = report.copy(orphanRowsPruned = orphanReport.orphanRowsPruned)
      _ <-
        if merged.hasWork then logger.info(s"[delegate-ttl] ${merged.summary}")
        else IO.unit
    yield merged

  /**
   * 孤儿任务行收口：到期且**无 registry 记录**的行剪掉。
   * 三条守卫（任一命中 ⇒ 零触碰）：
   *   ① 该 id 有登记条目 —— 交给上面的主链（它还要管置顶/工作区/合并队列）；
   *   ② 活体 actor 在册 —— 在飞会话绝不清理；
   *   ③ 执行保护未知或为保护态 —— 保守留现场（fail-closed）。
   */
  private def sweepOrphanRows(
    resources: SharedResources,
    rowsById: Map[String, nebflow.shared.SubAgentTask],
    recordIds: Set[String],
    live: Set[String],
    now: Long
  ): IO[Report] =
    val orphans = rowsById.values.toList.filter { t =>
      !recordIds.contains(t.taskId) && !live.contains(t.taskId) &&
      !nebflow.core.delegate.DelegateTerminalTtl
        .rowVerdict(t, now)
        .equals(nebflow.core.delegate.DelegateTerminalTtl.InFlight)
    }
    if orphans.isEmpty then IO.pure(Report())
    else
      Ref.of[IO, Report](Report()).flatMap { ref =>
        orphans.traverse_ { t =>
          resources.executionProtection(t.taskId).flatMap {
            case nebflow.shared.ExecutionProtection.Unprotected =>
              resources.executionCommitPort
                .withUnprotectedSession(t.taskId) {
                  val v = nebflow.core.delegate.DelegateTerminalTtl.rowVerdict(t, now)
                  if !v.isExpired then IO.unit
                  else
                    resources.subAgentTaskStore.removeTask(t.parentSessionId, t.taskId) *>
                      ref.update(r => r.copy(orphanRowsPruned = r.orphanRowsPruned :+ t.taskId)) *>
                      logger
                        .info(
                          s"[delegate-ttl] orphan task row '${t.taskId}' (parent=${t.parentSessionId}, " +
                            s"status=${t.status}) expired the terminal TTL with no registry record — pruned"
                        )
                        .as(())
                }
                .handleErrorWith(e =>
                  logger.warn(s"[delegate-ttl] orphan prune failed for ${t.taskId}: ${e.getMessage}").as(())
                )
            case _ => IO.unit // 保护态/未知：留现场
          }
        } *> ref.get
      }
    end if
  end sweepOrphanRows
end DelegateTtlSweep
