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
 *      a. worktree 联动回收 —— `PostMergeCleanup.run(expiredTerminal=true)`：
 *         树按 git 本体纪律移除（无 --force，脏树被拒=现场保留），分支删除仍守
 *         零领先（未落地提交绝不因 TTL 消失），reports/evidence 先抢救；
 *      b. 任务行剪行 —— `SubAgentTaskStore.removeTask`（重启丢失行自此离开复活
 *         视野 = 通知源永久沉默的根除腿）；
 *      c. 登记条目移除 —— `DelegateRegistry.remove`（记录文件删除；台账历史由
 *         ChainStore `worktree-cleanup` 行与子代理记账留存承载——brief「语义=
 *         清理，不丢台账历史可接受归档」的清理腿）。
 *
 * 接线（GatewayMain boot）：跑在 subagentCrashSweep **之前**——先把名单外的
 * 远古尸体清走，随后的收殓/标记/对账腿对已清条目天然零视野（已移除条目不进
 * markInterruptedRecords 的 `archivedAt.isEmpty` 守卫；任务行已剪行 = 复活视野
 * 永远看不到它们）。
 * 失败软化：单条失败只 WARN 入 kept，绝不阻断 boot。
 */
object DelegateTtlSweep:

  private val logger = NebflowLogger.forName("nebflow.agent.delegate-ttl-sweep")

  final case class Report(
    /** 到期条目（进了清理面的 id，按处理序）。 */
    expired: List[String] = Nil,
    /** worktree 已回收的 delegate id。 */
    worktreesRemoved: List[String] = Nil,
    /** worktree 保留的 delegate id（git 拒绝 = 脏树/未领先提交在场）。 */
    worktreesKept: List[String] = Nil,
    /** 任务行已剪行的 delegate id。 */
    taskRowsPruned: List[String] = Nil,
    /** 登记条目已移除的 delegate id。 */
    recordsRemoved: List[String] = Nil,
    /** 在飞守卫放行的 delegate id（零触碰）。 */
    inFlightKept: List[String] = Nil,
    /** 到期但清理链部分失败保留现场的 delegate id（fail-soft 留痕）。 */
    keptOnError: List[(String, String)] = Nil
  ):

    def hasWork: Boolean =
      expired.nonEmpty || inFlightKept.nonEmpty || keptOnError.nonEmpty

    def summary: String =
      s"expired=${expired.size} worktreesRemoved=${worktreesRemoved.size} worktreesKept=${worktreesKept.size} " +
        s"taskRowsPruned=${taskRowsPruned.size} recordsRemoved=${recordsRemoved.size} " +
        s"inFlightKept=${inFlightKept.size} keptOnError=${keptOnError.size}"

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
      ref <- Ref.of[IO, Report](Report())
      _ <- records.traverse_ { rec =>
        // ① 在飞守卫（红线）：活体 actor ⇒ 零触碰。
        if live.contains(rec.id) then ref.update(r => r.copy(inFlightKept = r.inFlightKept :+ rec.id))
        else
          // ② 到期判读（单源）。
          nebflow.core.delegate.DelegateTerminalTtl.verdict(rec, rowsById.get(rec.id), now) match
            case nebflow.core.delegate.DelegateTerminalTtl.InFlight => IO.unit
            case _: nebflow.core.delegate.DelegateTerminalTtl.WithinTtl => IO.unit
            case expired: nebflow.core.delegate.DelegateTerminalTtl.Expired =>
              // ③ 清理动作（worktree 回收 → 任务行剪行 → 登记移除；fail-soft）。
              for
                wtRemoved <-
                  nebflow.core.delegate.PostMergeCleanup
                    .run(Some(rec))
                    .handleError(_ => false)
                _ <-
                  rowsById.get(rec.id).traverse_ { t =>
                    resources.subAgentTaskStore.removeTask(t.parentSessionId, t.taskId)
                  }
                rm <- IO(nebflow.core.delegate.DelegateRegistry.remove(rec.id)).attempt
                _ = logger.info(
                  s"[delegate-ttl] '${rec.id}' expired the " +
                    s"${nebflow.core.delegate.DelegateTerminalTtl.TtlMs / 86400000L}-day terminal TTL " +
                    s"(anchor=${expired.anchorName} age=${(now - expired.anchorMs) / 86400000L}d) — " +
                    s"worktree=${if wtRemoved then "recycled" else "kept"} " +
                    s"taskRow=${if rowsById.contains(rec.id) then "pruned" else "none"} " +
                    s"record=${rm.fold(err => s"kept($err)", _ => "removed")}"
                )
                _ <- ref.update { r =>
                  r.copy(
                    expired = r.expired :+ rec.id,
                    worktreesRemoved = if wtRemoved then r.worktreesRemoved :+ rec.id else r.worktreesRemoved,
                    worktreesKept = if !wtRemoved then r.worktreesKept :+ rec.id else r.worktreesKept,
                    taskRowsPruned = if rowsById.contains(rec.id) then r.taskRowsPruned :+ rec.id else r.taskRowsPruned,
                    recordsRemoved = if rm.isRight then r.recordsRemoved :+ rec.id else r.recordsRemoved,
                    keptOnError = rm.fold(err => r.keptOnError :+ (rec.id -> err.getMessage), _ => r.keptOnError)
                  )
                }
              yield ()
      }
      report <- ref.get
      _ <-
        if report.hasWork then logger.info(s"[delegate-ttl] ${report.summary}")
        else IO.unit
    yield report
end DelegateTtlSweep
