package nebflow.agent

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import nebflow.actor.AgentCommand
import nebflow.core.SessionStore
import nebflow.core.delegate.{ChainStore, DelegateRegistry, DelegateTerminalTtl}
import nebflow.shared.*
import nebflow.shared.given

import scala.concurrent.duration.*

/**
 * V2 (2026-09-03, 结果投递链丢失向量修复): startup recovery for orphan
 * in-flight delegate tasks.
 *
 * Root cause this closes: a process crash (SIGKILL / power loss / crash loop
 * exhaustion) while a Delegate/SubTask child is in-flight permanently loses
 * the child's result. `SubAgentTaskStore.findRunningTasks` documented
 * "for startup recovery" but NOTHING called it at startup — the only caller
 * (AgentControl list) joins task rows onto live registry entries, and the
 * registry is empty after a restart, so the orphan was invisible everywhere.
 * The parent session's LLM context promises "You will be notified when it
 * completes" — a promise that could never be kept.
 *
 * Recovery (runs once at Gateway startup, before any session actor spawns —
 * single-writer on the queue files, no concurrent enqueue possible):
 *  1. Salvage: if the child session transcript has a partial result on disk
 *     (sessions/<taskId>.json — the child persisted every turn), the last
 *     assistant text is recovered and attached to the notification.
 *  2. Notify: an ExternalEvent (eventType=failed, source=delegate — the same
 *     shape a real child failure produces, so the existing retryable-hint and
 *     event-reminder machinery treats it uniformly) is appended to the parent
 *     session's F2 injection-queue file (CompactionQueueStore). When the
 *     parent actor spawns, RecoverPersistedQueues replays it into
 *     pendingEvents and the next turn boundary injects it — the exact
 *     durability chain F2 built for compaction-window events.
 *  3. Terminalize: the task record is flipped running → failed with the loss
 * note, so the sweep is idempotent (next boot finds no running tasks) and
 *     AgentControl shows a settled record instead of a phantom running row.
 *
 * Idempotency: BOTH steps are crash-safe without ordering tricks.
 *  - Terminalize (step 3) makes the sweep skip the task on the next boot.
 *  - The notify append is guarded by correlationId: an event whose
 *    correlationId == taskId already present in the queue file is never
 *    appended twice. So a crash between step 2 and step 3 → next boot
 *    re-runs the sweep, sees the event already queued, appends nothing, and
 *    only terminalizes. No duplicate notifications, no double rescue.
 *  - A parent session that no longer exists (deleted while the child ran) is
 *    skipped for notification — injecting into a deleted session's queue file
 *    would only create unread garbage. The task is still terminalized.
 *
 * == 重启打断标记腿（任务列表行重启对账批 2026-10-22）==
 *
 * 孤儿收殓只终态化任务记录（running → failed），delegate 行（DelegateRegistry
 * 记录）本身不带任何「死于重启」痕迹——行投影回落 running/failed，任务列表出现
 * 「死会话尸体系」（冻结简报 + 错误活态/勾态）。本批在孤儿收殓之后追加**回溯标记
 * 腿**：凡任务记录带 [[RestartLossFingerprint]]（本 sweep 终态化时的 lastError
 * 前缀——含历史 boot 落下的）且记录行未交付报告（reportStatus 缺席）、在册未归档、
 * 尚未标记 ⇒ [[DelegateRegistry.markRestartInterrupted]] 落旗（幂等零重写）。旗是
 * 显示面对账的字段基座：「重启打断」徽标 /「已中断」灰态 / 接续折叠（continuedBy
 * 推导在 ChainsReadFace 读面）都从它读起。在飞资格同步收宽：`restarting`（supervisor
 * 退避态）与 `running` 同判——进程死亡时退避中的任务同样是孤儿（旧 sweep 只认
 * running，退避态记录死后永远躺在 restart-tasks 盘面上，行投影永远回落 running）。
 *
 * ── #692 修批（2026-10-21 作者令）两件新增（合并融合 2026-10-22）──────────
 *
 * ① findRunningTasks 语义加宽（running ∪ restarting）：10-21 实测 7.5h 僵尸的
 *    直接成因——respawn 半途（"restarting" 已写、新 child 未起）引擎重启，该行
 *    不在旧「只扫 running」的启动清扫视野里，永远无人终态化。restarting 行同样
 *    在飞，本清扫照单全收（ Salvage/Notify/Terminalize 链对状态值无感知）。
 *    [合并注] 与标记腿的私有 inFlightTasks 收宽语义同向重叠——boot sweep 走
 *    私有 inFlightTasks（InFlightStatuses 过滤），findRunningTasks 的加宽服务
 *    ② 的运行期视野与热重启在飞判定；两调用点并存，无重复执行。
 *
 * ② 运行期 watchdog（watchdogSweep / run）：重启屏障僵死的兜底闸。判据 = 任务行
 *    在飞（running/restarting）∧ registry 无活体行（actor 与 supervisor 都不在）
 *    ∧ 该状态已持续 ≥ stallMs（默认 10min ≈ 10× respawn 最大退避 60s）⇒ 强制
 *    诚实终态化（failed + partial-salvage）+ F2 队列通知父（幂等，同 correlationId
 *    守卫）。「强推重新 respawn」由父会话的 re-delegate 机制承接（failed 事件自带
 *    retryable 语义）——引擎侧裸 respawn 需要父上下文（barrier/worktree 记账），
 *    直驾会绕过既有治理面。周期循环 run 由 GatewayMain 以 fiber 启动（错误自愈 +
 *    `>>` 递归，FreezeScheduler/TaskStuckWatcher 同款纪律）。
 */
object SubAgentStartupRecovery:
  private val logger = NebflowLogger.forName("nebflow.agent.startup-recovery")

  /** 重启打断指纹（[[recoverOne]] 终态化写下的 lastError 前缀；回溯标记腿的判据）。 */
  val RestartLossFingerprint = "process restart during in-flight task"

  /**
   * 失效/待恢复态（重启手动恢复批，作者令 2026-11-06 20:36「保存状态后，由用户手动
   * 恢复」）：手动模式下在飞孤儿 / ready-to-merge 僵尸 / 重启丢失行的落点状态——
   * 不判死（failed）、不复活（running），面板按「失效 · 待恢复」呈现，恢复入口 =
   * [[ManualRevival.restoreOne]] / [[ManualRevival.restoreAll]]。
   */
  val PendingRecoveryStatus = "pending-recovery"

  /** 在飞孤儿资格：running ＋ restarting（supervisor 退避态——进程死亡时同样在飞）。 */
  private val InFlightStatuses = Set("running", "restarting")

  /**
   * 重启丢失行（中断恢复批 2026-11-04 · 任务②）：上一 boot 被收殓判 failed 的孤儿。
   * 判据全部可从任务行机械求值：`status=failed` ∧ `lastError` 带 [[RestartLossFingerprint]]
   * 前缀（= 死于重启收殓，不是自身失败的诚实终态——LLM 网络错等自然失败行**不**进
   * 复活视野，作者原话「只有人工 cancel/close 的才不复活」）。
   *
   * 这类行在本批起**仍有复活机会**：进 sweep 视野走同一资格闸（墓碑/预算/座位/
   * 父会话逐条照守），合格者同 id 复活续跑；不合格者照旧收殓（幂等重写同一终态）。
   * 防无限循环三闸：任务级预算 `DelegateRevivalMaxPerTask`（达限后每次 boot 都只
   * 重走收殓，不再 spawn）、boot cap `DelegateRevivalBootCap`、kill-switch。复活
   * 成功即翻 running + 清指纹（`markRevived`），下一 boot 回到普通在飞判读。
   */
  def isRestartLostRow(t: SubAgentTask): Boolean =
    t.status == "failed" && t.lastError.exists(_.startsWith(RestartLossFingerprint))

  /**
   * 冻结行状态值（错误冻结批 2026-11-01，与 [[nebflow.core.delegate.DelegateFreeze.TaskStatus]]
   * 同串）：冻结行**单独分诊**——不进在飞收殓视野（不能把冻结当在飞孤儿终态化），
   * 有复活腿 ⇒ 判复活（同 id 续原上下文）；无复活腿 ⇒ 原样留着（用户仍可 Mail
   * `delegate:<id>` 唤醒；长期无人唤醒由 DelegateFreeze.dormantSweep 兜底提示/归档）。
   * 手动恢复批（2026-11-06）：改 public——[[ManualRevival]] 可恢复状态集引用它。
   */
  val FrozenStatus = "frozen"

  // ── 监督链自动恢复批（2026-10-22 作者令）：boot 复活腿 ────────────────────
  //
  // 孤儿处置从「一律收殓」升级为「先判复活资格，不合格才收殓」（设计
  // docs/design/delegate-supervision-revival-design.zh-CN.md）：合格孤儿的四件恢复
  // 现场（任务行/registry 记录/会话转录/座位目录）俱在盘上，由 [[DelegateRevival]]
  // 同 id 复活续跑，父会话最终收续作结果而非失败通知。复活是慢段 fork（boot 不
  // 阻塞，ProjectCrashRecovery 慢段同款纪律），spawn 并发信号量护栏；复活失败
  // （spawn 链错误）的孤儿在 fork 内落回收殓链——绝不静默悬挂。

  /**
   * 复活腿依赖（GatewayMain boot 装配点传入；None = 复活腿整体旁路——legacy 形态，
   * 既有测试/无 ActorSystem 载体场的诚实缺省）。
   */
  final case class RevivalDeps(
    resources: SharedResources,
    system: nebflow.actor.ActorSystem,
    wsSend: Option[io.circe.Json => IO[Unit]]
  )

  /** sweep 汇总（boot 日志行 + 测试断言面）。 */
  final case class Report(
    terminalized: List[String] = Nil,
    revived: List[String] = Nil,
    /** (taskId, 不可复活原因)——资格不满足/预算耗尽/boot cap 超额而走收殓的孤儿。 */
    revivalSkipped: List[(String, String)] = Nil,
    /** 手动模式：被标 `pending-recovery` 的行（不判死不复活，待用户手动恢复）。 */
    pended: List[String] = Nil
  ):

    def summary: String =
      s"terminalized=${terminalized.size} revived=${revived.size} (${revived.mkString(",")}) " +
        s"revivalSkipped=${revivalSkipped.size} pendedRecovery=${pended.size}"

  /** Legacy 签名（收殓 id 列表）——零复活依赖形态，既有调用面/测试零改动。 */
  def recoverOrphans(store: SubAgentTaskStore, sessionStore: SessionStore): IO[List[String]] =
    sweep(store, sessionStore, None).map(_.terminalized)

  /**
   * boot sweep（＋复活腿）：先逐孤儿判复活资格（kill-switch/seat/记录/父会话/预算），
   * boot cap 内按 spawnedAt 新者优先取复活集 → 慢段 fork 复活（并发护栏）；其余孤儿
   * 同步走既有收殓链（recoverOne——lastError 指纹、F2 幂等通知、终态化逐字不变），
   * 收尾跑标记回溯腿。无复活依赖（deps=None）= 全员收殓（本批前现状逐字节）。
   * 台账↔面板对账批（增补）：三条路径统一收尾 [[DelegateLedgerReconcile.reconcileInterruptedRecords]]
   * （假中断候选的逐条裁定——旗先落、对账后收敛；fail-soft 绝不阻断 boot）。
   *
   * **手动模式（重启手动恢复批，作者令 2026-11-06 20:36 逐字「由于重启/软件崩溃
   * 导致的错误，不用向上通知，不自动恢复，保存状态后，由用户手动恢复」）**：
   * `manual = true`（生产缺省 [[Defaults.RestartRecoveryManual]]）⇒ 在飞孤儿与
   * ready-to-merge 僵尸**不复活、不收殓、零向上通知**——一律标
   * [[PendingRecoveryStatus]]（失效/待恢复态，子 agent 面板可见），现场四件
   * （任务行/registry 记录/转录/worktree 指针）原样在盘；用户经 `ManualRevival`
   * 逐个或一键全部复活。冻结行维持既有「原样保留」处置；完成未报告收尾腿
   * （finalizeCompletedUnreported）保留——完成交付不是错误通知。
   */
  private[agent] def validTerminalEnvelope(
    task: SubAgentTask,
    run: String,
    event: AgentCommand.ExternalEvent
  ): Boolean =
    task.runId.contains(run) && event.source == task.source && event.correlationId.contains(task.taskId) &&
      event.metadata("parentSessionId").flatMap(_.asString).contains(task.parentSessionId) &&
      event.metadata("taskRunId").flatMap(_.asString).contains(run)

  private def isReconciliationRow(t: SubAgentTask): Boolean =
    Set("subagent", "subtask").contains(t.source) && Set("running", "pending-recovery").contains(t.status)

  def sweep(
    store: SubAgentTaskStore,
    sessionStore: SessionStore,
    revival: Option[RevivalDeps],
    manual: Boolean = false
  ): IO[Report] =
    revival match
      case None =>
        def scan(authority: nebflow.core.ExecutionCommitPort): IO[Report] =
          authority.candidates.flatMap {
            case Right(Nil) => sweepLegacy(store, sessionStore, revival, manual)
            case Right(_) if manual =>
              repairCommittedProjections(store, authority) *> sweepManualUnprotected(store, authority)
            case Right(_) =>
              repairCommittedProjections(store, authority) *>
                logger.warn("[startup-recovery] protected or unknown candidate; legacy sweep deferred").as(Report())
            case Left(reason) => logger.warn(s"[startup-recovery] authority discovery failed: $reason").as(Report())
          }
        sessionStore.installedExecutionAuthority match
          case Some(authority) => scan(authority)
          case None => ExecutionCommitStore.resource(sessionStore.executionAuthorityRoot).use(scan)
      case Some(deps) =>
        deps.resources.executionCommitPort.candidates.flatMap {
          case Left(reason) =>
            logger.warn(s"[startup-recovery] authority discovery failed: $reason; legacy sweep deferred").as(Report())
          case Right(candidates) if candidates.nonEmpty && manual =>
            recoverPendingInternal(store, sessionStore, Some(deps.resources)) *>
              sweepManualUnprotected(store, deps.resources.executionCommitPort, Some(deps.resources))
          case Right(candidates) if candidates.nonEmpty =>
            recoverPendingInternal(store, sessionStore, Some(deps.resources)) *>
              logger
                .warn(
                  s"[startup-recovery] ${candidates.size} protected or unknown session(s) found; legacy sweep deferred"
                )
                .as(Report())
          case Right(_) =>
            store.findAllTasks().flatMap { tasks =>
              tasks.traverse(t => deps.resources.executionProtection(t.taskId)).flatMap { states =>
                if states.forall(_ == nebflow.shared.ExecutionProtection.Unprotected) then
                  sweepLegacy(store, sessionStore, revival, manual)
                else
                  logger.warn("[startup-recovery] protected or unknown execution: legacy sweep deferred").as(Report())
              }
            }
        }

  private def sweepManualUnprotected(
    store: SubAgentTaskStore,
    authority: nebflow.core.ExecutionCommitPort,
    resources: Option[SharedResources] = None
  ): IO[Report] =
    inFlightTasks(store).flatMap { rows =>
      rows
        .filterNot(t => t.status == FrozenStatus || isReconciliationRow(t))
        .traverseFilter { task =>
          resources.fold(authority.protection(task.taskId))(_.executionProtection(task.taskId)).flatMap {
            case nebflow.shared.ExecutionProtection.Unprotected =>
              authority
                .withUnprotectedSession(task.taskId) {
                  store
                    .transitionObserved(
                      task,
                      PendingRecoveryStatus,
                      Some("Execution interrupted; manual recovery required")
                    )
                    .as(Some(task.taskId))
                }
                .handleErrorWith(e =>
                  logger
                    .warn(s"[startup-recovery] guarded pending transition failed for ${task.taskId}: ${e.getMessage}")
                    .as(None)
                )
            case _ => IO.pure(None)
          }
        }
        .map(ids => Report(pended = ids))
    }

  private def sweepLegacy(
    store: SubAgentTaskStore,
    sessionStore: SessionStore,
    revival: Option[RevivalDeps],
    manual: Boolean
  ): IO[Report] =
    def settle(): IO[Unit] =
      // 完成未报告收尾腿（本批 V5/审计 D8）先于标记/对账：干完不报的会话在
      // 「重启打断旗」落旗之前就被收尾，不会被误标成「中断」。
      recoverPendingInternal(store, sessionStore, revival.map(_.resources)) *>
        (if revival.isEmpty then reconcileWithoutResources(store, sessionStore) else IO.unit) *>
        finalizeCompletedUnreported(store, sessionStore, revival.map(_.resources)).void *>
        markInterruptedRecords(store).void *>
        nebflow.core.delegate.DelegateLedgerReconcile.reconcileInterruptedRecords(store).void
    val tasks = recoverPendingInternal(store, sessionStore, revival.map(_.resources)) *> interruptExternalRuns(store) *>
      inFlightTasks(store, includeRestartLost = revival.isDefined && Defaults.DelegateRevivalEnabled)
    tasks
      .map(_.filterNot(t => isReconciliationRow(t) || revival.exists(_.resources.isProtectedExecution(t.taskId))))
      .flatMap {
        case Nil => settle().as(Report())
        case allOrphans =>
          // ── 会话死亡台账结算（2026-10-28 · R1 同族监督持久性面）────────────────
          // ready-to-merge 行（legacy 状态值）单独分诊：终态化/待恢复见 rtmSettled。
          val (rtmRows, rest1) = allOrphans.partition(_.status == "ready-to-merge")
          // 错误冻结批（2026-11-01）：冻结行单独分诊——**绝不进收殓链**（冻结不是
          // 在飞孤儿，忠实终态化 = 把作者要保留的现场判死）。有复活腿 ⇒ 与在飞孤儿
          // 走同一判据（`DelegateRevival.eligibility` 的冻结腿放开预算/年龄闸）；复活
          // 不成的冻结行原样留着（作者仍可 Mail `delegate:<id>` 唤醒；长期无人唤醒由
          // `DelegateFreeze.dormantSweep` 提示/归档兜底）。
          val (frozenRows, orphans) = rest1.partition(_.status == FrozenStatus)
          val frozenNote: IO[Unit] =
            if frozenRows.isEmpty then IO.unit
            else
              logger.info(
                s"[startup-recovery] ${frozenRows.size} FROZEN delegate row(s) survived the restart — kept as-is awaiting a wake " +
                  s"(never terminalized): ${frozenRows.map(_.taskId).mkString(", ")}"
              )
          // 冻结行的复活 = 可选腿（有 deps 才做）：冻结行不参与「在飞孤儿」的收殓分流，
          // 复活失败也只回原状（行仍 frozen、待作者唤醒）。
          def reviveFrozen(): IO[List[String]] =
            if frozenRows.isEmpty || revival.isEmpty || !Defaults.DelegateRevivalEnabled then IO.pure(Nil)
            else
              frozenRows
                .traverse(t =>
                  DelegateRevival
                    .eligibility(sessionStore, t)
                    .flatMap {
                      case Some(_) => IO.pure(None)
                      case None =>
                        DelegateRevival
                          .revive(
                            DelegateRevival.Deps(revival.get.resources, revival.get.system, revival.get.wsSend),
                            t
                          )
                          .map {
                            case Right(()) => Some(t.taskId)
                            case Left(err) =>
                              logger.warn(s"[startup-recovery] frozen wake failed for ${t.taskId}: $err — left frozen")
                              None
                          }
                    }
                    .handleErrorWith(e =>
                      logger.warn(s"[startup-recovery] frozen wake errored for ${t.taskId}: ${e.getMessage}").as(None)
                    )
                )
                .map(_.flatten)
          // ready-to-merge 行（legacy 状态值，现行引擎不再写入）：没有合并队列可再
          // 授予合并轮 ⇒ 全部按「重启丢失」终态化（branch/worktree 原样保留；集成走
          // 项目 merger 的授权请求）。
          val rtmSettled: IO[List[String]] =
            if rtmRows.isEmpty then IO.pure(Nil)
            else
              logger
                .warn(
                  s"[startup-recovery] ${rtmRows.size} ready-to-merge row(s) survived a restart — settling to interrupted: ${rtmRows.map(_.taskId).mkString(", ")}"
                )
                .as(()) *> rtmRows
                .traverse { t =>
                  val head =
                    s"[task lost: crash] Delegate \"${t.description}\" (agent=${t.agentName}, session=${t.taskId}) " +
                      "had reached ready-to-merge under a previous engine version; no merge round can be granted for it anymore — " +
                      "the row is settled to interrupted (branch/worktree kept as-is; commits remain on the branch, landing is the author's decision)."
                  recoverOne(store, sessionStore, t, head, "lostToCrash", revival.map(_.resources))
                    .handleErrorWith { e =>
                      logger
                        .warn(s"[startup-recovery] rtm settle failed for ${t.taskId}: ${e.getMessage}")
                        .as(t.taskId)
                    }
                }
          def salvageAll(tasks: List[SubAgentTask]): IO[List[String]] =
            if tasks.isEmpty then IO.pure(Nil)
            else
              logger
                .warn(
                  s"[startup-recovery] ${tasks.size} in-flight delegate task(s) survived a process restart — terminalizing + notifying parent session(s)"
                )
                .as(()) *> tasks
                .traverse(t =>
                  recoverOne(store, sessionStore, t, revival.map(_.resources)).handleErrorWith { e =>
                    logger
                      .warn(s"[startup-recovery] recovery failed for task ${t.taskId}: ${e.getMessage}")
                      .as(t.taskId)
                  }
                )
          // ── 手动模式腿（重启手动恢复批 2026-11-06 20:36 作者令）：标 pending-recovery，
          // 不复活、不收殓、零向上通知（recoverOne 的通知链本批已整体摘除，本腿根本不进它）。
          def markPending(tasks: List[SubAgentTask]): IO[List[String]] =
            if tasks.isEmpty then IO.pure(Nil)
            else
              logger
                .info(
                  s"[startup-recovery] MANUAL recovery mode: ${tasks.size} row(s) → pending-recovery " +
                    s"(no auto-revive / no terminalize / zero parent notify): ${tasks.map(_.taskId).mkString(", ")}"
                )
                .as(()) *> tasks
                .traverse(t =>
                  store
                    .transitionObserved(
                      t,
                      PendingRecoveryStatus,
                      Some("Execution interrupted; manual recovery required")
                    )
                    .handleErrorWith(e =>
                      logger
                        .warn(s"[startup-recovery] pending-recovery mark failed for ${t.taskId}: ${e.getMessage}")
                        .as(())
                    )
                    .as(t.taskId)
                )
          // 手动模式下的 ready-to-merge 行：不收殓（那是判死+通知）→ 标
          // pending-recovery 待手动恢复。
          def rtmManual(): IO[List[String]] = markPending(rtmRows)
          frozenNote *> (
            if manual then
              rtmManual().flatMap { pendedRtm =>
                markPending(orphans).flatMap { pendedOrphans =>
                  settle().as(Report(pended = pendedRtm ++ pendedOrphans))
                }
              }
            else
              revival match // 手动模式分支包裹（最小 diff：花括号界定既有 case 缩进）
                case None =>
                  salvageAll(orphans)
                    .flatMap(ids => rtmSettled.flatMap(rtm => settle().as(Report(terminalized = rtm ++ ids))))
                case Some(deps) if !Defaults.DelegateRevivalEnabled =>
                  logger
                    .info("[delegate-revival] kill-switch off — all orphans fall through to the salvage chain")
                    .as(())
                    .flatMap(_ =>
                      salvageAll(orphans)
                        .flatMap(ids => rtmSettled.flatMap(rtm => settle().as(Report(terminalized = rtm ++ ids))))
                    )
                case Some(deps) =>
                  for
                    rtm <- rtmSettled
                    // 冻结行复活腿（错误冻结批）：先于在飞孤儿判据——冻结行的 eligibility
                    // 走冻结预算/保留期，与在飞孤儿的年龄预算分道。
                    revivedFrozen <- reviveFrozen()
                    judged <- orphans.traverse { t =>
                      DelegateRevival.eligibility(sessionStore, t).map(reason => (t, reason))
                    }
                    eligible = judged.collect { case (t, None) => t }.sortBy(-_.spawnedAt) // 新者优先（设计 §2.2 #8）
                    cap = math.max(0, Defaults.DelegateRevivalBootCap)
                    reviveSet = eligible.take(cap)
                    skipped =
                      judged.collect { case (t, Some(reason)) => (t.taskId, reason) } ++
                        eligible.drop(cap).map(t => (t.taskId, s"boot revival cap exhausted ($cap)"))
                    _ <-
                      if reviveSet.isEmpty then IO.unit
                      else
                        logger.info(
                          s"[delegate-revival] ${reviveSet.size} eligible orphan(s) queued for revival: " +
                            reviveSet.map(t => s"${t.taskId}(rev=${t.revivalCount.getOrElse(0)})").mkString(", ") +
                            skipped.map((id, r) => s"; skip $id: $r").mkString
                        )
                    // 慢段：fork 复活（并发护栏；spawn 失败的单条 fork 内落回收殓链）。
                    revivedRef <-
                      if reviveSet.isEmpty then Ref.of[IO, List[String]](Nil)
                      else
                        DelegateRevival.reviveAll(
                          DelegateRevival.Deps(deps.resources, deps.system, deps.wsSend),
                          reviveSet,
                          salvage = t => recoverOne(store, sessionStore, t, revival.map(_.resources)).as(())
                        )
                    // 同步段：复活集之外的孤儿收殓（F2 队列单写者纪律——复活 fork 零触碰
                    // 队列文件，收殓全部在本同步段串行完成，无并发写）。
                    reviveIds = reviveSet.map(_.taskId).toSet
                    rest = orphans.filterNot(o => reviveIds.contains(o.taskId))
                    terminalized <- salvageAll(rest)
                    _ <- settle()
                    revivedNow <- revivedRef.get
                  yield Report(
                    terminalized = rtm ++ terminalized,
                    revived = revivedNow ++ revivedFrozen,
                    revivalSkipped = skipped
                  )
          )
      }

  end sweepLegacy

  private def repairCommittedProjections(store: SubAgentTaskStore, port: nebflow.core.ExecutionCommitPort): IO[Unit] =
    port match
      case authority: ExecutionCommitStore =>
        authority.candidates.flatMap {
          case Left(reason) => logger.warn(s"[startup-recovery] candidate scan failed: $reason")
          case Right(candidates) =>
            candidates.traverse_ { candidate =>
              candidate.protection match
                case nebflow.shared.ExecutionProtection.Protected(ids) =>
                  ids.traverse_ { run =>
                    authority.repairTerminalProjection(candidate.sessionId, run, store).flatMap {
                      case Right(_) => IO.unit
                      case Left(reason) if reason == "terminal-uncommitted" => IO.unit
                      case Left(reason) =>
                        logger.warn(
                          s"[startup-recovery] terminal projection requires review: ${candidate.sessionId}/$run: $reason"
                        )
                    }
                  }
                case _ => IO.unit
            }
        }
      case _ => IO.unit

  /** Replay the exact saved terminal envelope; never synthesize a success report. */
  /** Evidence-only reconciliation for boots without shared resources: no marking, no mailbox work. */
  private[agent] def reconcileWithoutResources(store: SubAgentTaskStore, sessionStore: SessionStore): IO[Unit] =
    reconcileWithoutResourcesImpl(store, sessionStore)

  private def reconcileWithoutResourcesImpl(store: SubAgentTaskStore, sessionStore: SessionStore): IO[Unit] =
    store
      .findAllTasks()
      .flatMap(
        _.filter(t => isReconciliationRow(t) && t.runId.nonEmpty && !t.executionStatus.contains("interrupted"))
          .traverse_ { task =>
            val run = task.runId.get
            val debtOnly = nebflow.core.mail.CompletionDebtStore
              .pendingManaged(task.parentSessionId, task.taskId, run, task.source)
              .map(_.map { debt =>
                val text =
                  debt.messages.reverse.find(_.role == MessageRole.Assistant).map(_.textContent.trim).getOrElse("")
                val payload =
                  if text.nonEmpty && text != Defaults.MaxTokensStubText then s"\"${task.description}\":\n$text"
                  else s"\"${task.description}\" (no text output)"
                AgentCommand.ExternalEvent(
                  task.source,
                  "completed",
                  payload,
                  JsonObject(
                    "parentSessionId" -> task.parentSessionId.asJson,
                    "taskRunId" -> run.asJson,
                    "description" -> task.description.asJson,
                    "agentName" -> task.agentName.asJson,
                    "completionMessages" -> debt.messages.asJson,
                    "completionGeneration" -> run.asJson
                  ),
                  Some(task.taskId)
                )
              })
            (sessionStore.loadTerminalEnvelope(task.taskId, run), debtOnly).tupled
              .flatMap {
                case (Some(event), _) if event.eventType == "completed" && validTerminalEnvelope(task, run, event) =>
                  sessionStore
                    .saveTerminalEnvelope(task.taskId, run, event)
                    .flatMap(ref =>
                      store
                        .completeRun(
                          task.parentSessionId,
                          task.taskId,
                          run,
                          "finished",
                          resultRef = Some(ref),
                          notificationId = Some(s"${task.taskId}#$run")
                        )
                        .void
                    )
                case (None, Some(event)) =>
                  sessionStore
                    .saveTerminalEnvelope(task.taskId, run, event)
                    .flatMap(ref =>
                      store
                        .completeRun(
                          task.parentSessionId,
                          task.taskId,
                          run,
                          "finished",
                          resultRef = Some(ref),
                          notificationId = Some(s"${task.taskId}#$run")
                        )
                        .void
                    )
                case _ => IO.unit
              }
              .handleErrorWith(e =>
                logger
                  .warn(s"[startup-recovery] resource-free reconciliation failed for ${task.taskId}: ${e.getMessage}")
              )
          }
      )

  private[agent] def recoverPendingInternal(
    store: SubAgentTaskStore,
    sessionStore: SessionStore,
    resources: Option[SharedResources]
  ): IO[Unit] = resources.fold(IO.unit) { rs =>
    val recoverDeliveredLegacy = store
      .findAllTasks()
      .flatMap(
        _.filter(t =>
          isReconciliationRow(t) && t.source == "subagent" && t.runId.isEmpty && t.toolCallId.exists(_.nonEmpty)
        ).traverse_ { task =>
          sessionStore
            .loadMessagesForSession(task.parentSessionId)
            .flatMap { messages =>
              val delivered = messages.exists {
                case Message(MessageRole.User, Right(blocks), _, Some(source), _, _)
                    if source.startsWith("subagent-tool-returns:") =>
                  val trustedIds = source.stripPrefix("subagent-tool-returns:").split(",").toSet
                  blocks.exists {
                    case ContentBlock.ToolResult(id, _, _) => task.toolCallId.contains(id) && trustedIds.contains(id)
                    case _ => false
                  }
                case _ => false
              }
              if delivered then
                store.completeDeliveredLegacy(task.parentSessionId, task.taskId, task.toolCallId.get).void
              else IO.unit
            }
            .handleErrorWith(e =>
              logger.warn(s"[startup-recovery] legacy delivery check failed for ${task.taskId}: ${e.getMessage}")
            )
        }
      )
    val recoverActiveEnvelopes = store
      .findAllTasks()
      .flatMap(
        _.filter(t =>
          isReconciliationRow(t) && t.executor.contains("nebflow") && t.runId.nonEmpty &&
            !t.executionStatus.contains("interrupted")
        ).traverse_ { task =>
          val run = task.runId.get
          rs.executionProtection(task.taskId)
            .flatMap {
              case nebflow.shared.ExecutionProtection.Unprotected =>
                (
                  sessionStore.loadTerminalEnvelope(task.taskId, run),
                  nebflow.core.mail.CompletionDebtStore
                    .pendingManaged(task.parentSessionId, task.taskId, run, task.source)
                ).tupled.flatMap {
                  case (Some(event), Some(debt))
                      if !validTerminalEnvelope(task, run, event) || event.eventType != "completed" ||
                        !event.metadata("completionMessages").contains(debt.messages.asJson) =>
                    store.markSuspectedLostTerminal(task).void *>
                      logger.warn(
                        s"[startup-recovery] conflicting terminal evidence for ${task.taskId}; manual review required"
                      )
                  case (Some(event), _) if event.eventType == "completed" && validTerminalEnvelope(task, run, event) =>
                    sessionStore.saveTerminalEnvelope(task.taskId, run, event).flatMap { ref =>
                      store
                        .completeRun(
                          task.parentSessionId,
                          task.taskId,
                          run,
                          "finished",
                          resultRef = Some(ref),
                          notificationId = Some(s"${task.taskId}#$run")
                        )
                        .void
                    }
                  case (None, _) =>
                    nebflow.core.mail.CompletionDebtStore
                      .pendingManaged(task.parentSessionId, task.taskId, run, task.source)
                      .flatMap {
                        case Some(debt) =>
                          val text = debt.messages.reverse
                            .find(_.role == MessageRole.Assistant)
                            .map(_.textContent.trim)
                            .getOrElse("")
                          val payload =
                            if text.nonEmpty && text != Defaults.MaxTokensStubText then
                              s"\"${task.description}\":\n$text"
                            else s"\"${task.description}\" (no text output)"
                          val event = AgentCommand.ExternalEvent(
                            task.source,
                            "completed",
                            payload,
                            JsonObject(
                              "parentSessionId" -> task.parentSessionId.asJson,
                              "taskRunId" -> run.asJson,
                              "description" -> task.description.asJson,
                              "agentName" -> task.agentName.asJson,
                              "completionMessages" -> debt.messages.asJson,
                              "completionGeneration" -> run.asJson
                            ),
                            Some(task.taskId)
                          )
                          sessionStore.saveTerminalEnvelope(task.taskId, run, event).flatMap { ref =>
                            store
                              .completeRun(
                                task.parentSessionId,
                                task.taskId,
                                run,
                                "finished",
                                resultRef = Some(ref),
                                notificationId = Some(s"${task.taskId}#$run")
                              )
                              .void
                          }
                        case None => IO.unit
                      }
                  case _ => IO.unit
                }
              case _ => IO.unit
            }
            .handleErrorWith(e =>
              logger.warn(s"[startup-recovery] terminal envelope recovery failed for ${task.taskId}: ${e.getMessage}")
            )
        }
      )
    val markUnresolved = store
      .findAllTasks()
      .flatMap(_.filter(isReconciliationRow).traverse_ { row =>
        (rs.executionProtection(row.taskId), rs.agentRegistry.get).tupled.flatMap {
          case (nebflow.shared.ExecutionProtection.Unprotected, registry) if !registry.contains(row.taskId) =>
            val mayMark =
              if row.executionStatus.contains("interrupted") then
                row.runId.fold(IO.pure(false)) { run =>
                  sessionStore
                    .loadTerminalEnvelope(row.taskId, run)
                    .map(_.exists(event => event.eventType == "completed" && validTerminalEnvelope(row, run, event)))
                }
              else IO.pure(true)
            mayMark.flatMap { yes =>
              if !yes then IO.unit
              else
                store.markSuspectedLostTerminal(row).flatMap { marked =>
                  if marked then
                    logger
                      .warn(s"[suspected-lost-terminal] ${row.parentSessionId}/${row.taskId}: manual review required")
                  else IO.unit
                }
            }
          case _ => IO.unit
        }
      })
    repairCommittedProjections(
      store,
      rs.executionCommitPort
    ) *> recoverDeliveredLegacy *> recoverActiveEnvelopes *> markUnresolved *>
      deliverPendingNotifications(store, sessionStore, rs, onlyPending = false)
  }

  /**
   * 挂账终态通知的消费腿（送达链路修复批 2026-11-23 P0③）——**非 boot 期**周期面。
   *
   * 背景（诊断书 §C）：重启闸强终（zero-notify by author order 2026-11-06）给
   * delegate 行写 `notificationPending=true` 挂账，唯一消费者就是本块；可它原先
   * 只在 boot sweep / 手动恢复里跑（[[recoverPendingInternal]] 尾部）。23c910c6
   * 轮 21 于 19:38 强终、下一次 boot 之后才可能有消费者 ⇒ 挂账行至今无人消费。
   * 本批起 [[run]]（GatewayMain 已装配的 60s 周期 fiber）每拍也跑这条腿 ⇒
   * 挂账行在**同一进程生命周期内**必然被下一次扫描消费并投递。
   *
   * 覆盖边界：本腿只认 executor=nebflow（delegate 轨；`ExternalSessionRunner.
   * recoverPendingNotifications` 的 `executor != DefaultId` 过滤保留给外置执行器
   * 轨，二者互补不重叠——同一身份重复投递由 `SessionMailbox.mailOnce` 幂等键 +
   * 载荷指纹闸兜住）。
   */
  private[agent] def deliverPendingNotifications(
    store: SubAgentTaskStore,
    sessionStore: SessionStore,
    rs: SharedResources,
    onlyPending: Boolean
  ): IO[Unit] =
    store
      .findAllTasks()
      .flatMap(
        _.filter(t =>
          t.executor.contains("nebflow") && t.runId.nonEmpty && t.notificationId.nonEmpty &&
            // 周期腿只消费**真挂账**行（notificationPending=true）；boot 腿保持全量形态。
            (!onlyPending || t.notificationPending.contains(true))
        ).traverse_ {
          task =>
            rs.executionProtection(task.taskId).flatMap {
              case nebflow.shared.ExecutionProtection.Unprotected if task.notificationPending.contains(true) =>
                sessionStore.loadTerminalEnvelope(task.taskId, task.runId.get).flatMap { saved =>
                  val fallback = Option.when(
                    task.executionStatus.exists(Set("failed", "cancelled").contains) &&
                      task.resultRef.isEmpty && task.terminalReason.nonEmpty
                  )(
                    AgentCommand.ExternalEvent(
                      task.source,
                      task.status,
                      task.terminalReason.get,
                      JsonObject("taskId" -> task.taskId.asJson, "runId" -> task.runId.asJson),
                      Some(task.taskId)
                    )
                  )
                  saved
                    .filter(validTerminalEnvelope(task, task.runId.get, _))
                    .orElse(Option.when(saved.isEmpty)(fallback).flatten)
                    .fold(IO.unit) { event =>
                      val identity = task.notificationId.get
                      MailboxProducer
                        .deliver(
                          rs,
                          task.parentSessionId,
                          event,
                          identity,
                          "delegate-terminal",
                          coalesce = false,
                          producer = "task-lifecycle-recovery"
                        )
                        .flatMap {
                          case nebflow.core.mail.SessionMailbox.MailResult.Failed(_) => IO.unit
                          case _ =>
                            store
                              .acknowledgeNotification(task.parentSessionId, task.taskId, task.runId.get, identity)
                              .void
                        }
                    }
                }
              case nebflow.shared.ExecutionProtection.Protected(List(run))
                  if task.runId.contains(run) &&
                    task.executionStatus.contains("finished") && task.notificationId.contains(s"${task.taskId}#$run") =>
                val encoded = java.util.Base64.getUrlEncoder
                  .withoutPadding()
                  .encodeToString(run.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                val expected = sessionStore.executionAuthorityRoot.toAbsolutePath
                  .normalize()
                  .resolve(task.taskId)
                  .resolve(s"terminal-$encoded.json")
                  .toString
                if !task.resultRef.contains(expected) then IO.unit
                else
                  rs.executionCommitPort.committedTerminal(task.taskId, run).flatMap {
                    case Left(_) => IO.unit
                    case Right(confirmed) =>
                      sessionStore.loadTerminalEnvelope(task.taskId, run).flatMap {
                        case Some(event)
                            if validTerminalEnvelope(task, run, event) && Json.obj(
                              "source" -> event.source.asJson,
                              "eventType" -> event.eventType.asJson,
                              "payload" -> event.payload.asJson,
                              "metadata" -> Json.fromJsonObject(event.metadata),
                              "correlationId" -> event.correlationId.asJson,
                              "blocks" -> event.blocks.asJson
                            ) == confirmed =>
                          MailboxProducer
                            .deliver(
                              rs,
                              task.parentSessionId,
                              event,
                              task.notificationId.get,
                              "delegate-terminal",
                              coalesce = false,
                              producer = "task-lifecycle-recovery"
                            )
                            .flatMap {
                              case nebflow.core.mail.SessionMailbox.MailResult.Failed(_) => IO.unit
                              case nebflow.core.mail.SessionMailbox.MailResult.AlreadyPresent =>
                                val identity = task.notificationId.get
                                (
                                  nebflow.core.mail.SessionMailbox.scanPending(task.parentSessionId),
                                  nebflow.core.mail.SessionMailbox.scanTaken(task.parentSessionId),
                                  nebflow.core.mail.SessionMailbox
                                    .deliveredContains(task.parentSessionId, Set(identity))
                                ).tupled.flatMap { case (pending, taken, delivered) =>
                                  val matches = (pending ++ taken).filter(_.identity == identity)
                                  if matches.exists(_.event != event) then
                                    logger.warn(
                                      s"[startup-recovery] conflicting protected terminal mailbox identity for ${task.taskId}"
                                    )
                                  else if matches.nonEmpty || delivered.contains(identity) then
                                    store.acknowledgeNotification(task.parentSessionId, task.taskId, run, identity).void
                                  else
                                    logger.warn(
                                      s"[startup-recovery] protected terminal mailbox receipt unavailable for ${task.taskId}"
                                    )
                                }
                              case _ =>
                                store
                                  .acknowledgeNotification(
                                    task.parentSessionId,
                                    task.taskId,
                                    run,
                                    task.notificationId.get
                                  )
                                  .void
                            }
                        case Some(event) if !validTerminalEnvelope(task, run, event) =>
                          store.markInvalidTerminalEnvelope(task).flatMap { marked =>
                            if marked then
                              logger.warn(
                                s"[startup-recovery] invalid protected terminal identity for ${task.taskId}; manual review required"
                              )
                            else IO.unit
                          }
                        case _ => IO.unit
                      }
                  }
                end if
              case _ => IO.unit
            }
        }
      )

  /** Loss of monitoring is not proof that the external process died or failed its task. */
  private[agent] def interruptExternalRuns(store: SubAgentTaskStore): IO[List[String]] =
    store
      .findAllTasks()
      .flatMap(
        _.filter(t =>
          // Managed rows (executor `nebflow`) never reach this leg — the clause below already
          // requires a foreign executor, and evidence reconciliation owns every `nebflow` row.
          // A subtask/subagent projection written by a FOREIGN executor carries no envelope and
          // no run-bound debt, so monitoring loss is genuinely all we know about it: keep the
          // interrupt leg for it instead of silently leaving it running (the blanket
          // `!isReconciliationRow` exclusion introduced in 5b9245509 suppressed exactly that
          // leg and left such rows unresolved forever).
          t.runId.nonEmpty &&
            t.executor.exists(_ != "nebflow") &&
            t.executionStatus.exists(Set("starting", "running", "waiting-input").contains)
        ).traverseFilter { task =>
          store
            .completeRun(
              task.parentSessionId,
              task.taskId,
              task.runId.get,
              "interrupted",
              reason = Some("Execution monitoring was lost during restart; the original process may still be running"),
              resultRef = task.resultRef,
              incomplete = true,
              diagnostic = Some("Manual recovery required; no process identity or output attachment was verified")
            )
            .map(_.toOption.map(_.taskId))
        }
      )

  /**
   * 在飞任务全量（running/restarting/ready-to-merge/frozen；单遍 findAllTasks 后过滤——boot 一次性）。
   * ready-to-merge = legacy 状态值（现行引擎不再写入）；旧盘残留行收入视野，sweep
   * 内统一终态化或标待恢复（见 sweep 注）。
   * [中断恢复批 2026-11-04] `includeRestartLost`：failed ∧ 重启指纹行进复活视野
   * （任务②）；仅复活腿开启时收（legacy / kill-switch off = 逐字节回本批前现状，
   * failed 行不进任何处置面）。
   */
  private def inFlightTasks(store: SubAgentTaskStore, includeRestartLost: Boolean = false): IO[List[SubAgentTask]] =
    store
      .findAllTasks()
      .map { all =>
        val inFlight =
          all.filter(t =>
            InFlightStatuses.contains(t.status) || t.status == "ready-to-merge" || t.status == FrozenStatus
          )
        // TTL 静默闸（终态 7 天 TTL 批 2026-11-06 · 通知源永久沉默）：超期死亡行
        // （终态时刻 ≥ Defaults.DelegateTerminalTtlMs 前）绝不进复活/收殓视野——
        // 重启收殓不再把远古死会话翻出来报 FAILED；在飞行（非终态）永不受此闸。
        val restartLost =
          if includeRestartLost then
            all.filter(isRestartLostRow).filterNot(t => DelegateTerminalTtl.rowExpired(t, System.currentTimeMillis()))
          else Nil
        inFlight ++ restartLost
      }
      .handleErrorWith(e => logger.warn(s"[startup-recovery] in-flight scan failed: ${e.getMessage}").as(Nil))

  /**
   * 重启打断标记回溯腿（幂等，boot 恰一次）：任务记录带重启指纹（本 boot 终态化的
   * 或历史 boot 落下的）⇒ 对应 registry 记录落 `restartInterruptedAt` 旗。守卫三闸：
   * 未旗（幂等）/ **状态派生未收口**（收口身份零触碰——阶段② 起由
   * [[nebflow.core.delegate.DelegateVisibility]] 现读判据供给，不再吃归档旗）/
   * 未交付报告（真完成不被旗截断）。
   * 单条失败只 WARN，绝不阻断 boot。
   */
  private def markInterruptedRecords(store: SubAgentTaskStore): IO[Int] =
    for
      tasks <- store
        .findAllTasks()
        .map(_.filter(t => t.lastError.exists(_.startsWith(RestartLossFingerprint))))
        .handleErrorWith(e => logger.warn(s"[startup-recovery] fingerprint scan failed: ${e.getMessage}").as(Nil))
      lostIds = tasks.map(_.taskId).collect { case id if id.nonEmpty => id }.toSet
      marked <-
        if lostIds.isEmpty then IO.pure(0)
        else
          IO.blocking(DelegateRegistry.list())
            .handleErrorWith(e => logger.warn(s"[startup-recovery] registry read failed: ${e.getMessage}").as(Nil))
            .flatMap { records =>
              val now = System.currentTimeMillis()
              records
                .filter(r =>
                  r.restartInterruptedAt.isEmpty && !nebflow.core.delegate.DelegateVisibility
                    .settledOf(r) && r.reportStatus.isEmpty && lostIds.contains(
                    r.id
                  )
                )
                .traverse { r =>
                  IO.blocking(DelegateRegistry.markRestartInterrupted(r.id, now)).attempt.flatMap {
                    case Right(_) => IO.pure(true)
                    case Left(err) =>
                      logger
                        .warn(s"[startup-recovery] restart-interrupted mark failed for ${r.id}: $err")
                        .as(false)
                  }
                }
                .map(_.count(identity))
            }
      _ <-
        if marked > 0 then logger.info(s"[startup-recovery] restart-interruption marked on $marked delegate record(s)")
        else IO.unit
    yield marked

  /**
   * ## 完成未报告收尾腿（中断恢复批 2026-11-04 · V5 / 审计 D8 · cb580112 形态）
   *
   * 缺陷：活干完、只差收尾报告的会话（实证 cb580112：11:25:20 合并先落地 → 11:25:56
   * finish 无裁定 → 11:27:41「NO terminal verdict」→ idle）**连 sweep 视野都不进**
   * ——在飞视野只有 running/restarting/ready-to-merge/frozen，completed 行永远轮不到
   * 任何人收敛，父会话静默不知道结果已到。
   *
   * 机械判据（盘上可求值，与验收台架 README §3 同口径）：
   * {{{
   *   任务行 status == "completed" ∧ source != "subtask"
   *   ∧ registry.workReport 非空          （工作轮终稿在盘 = 活真干完了）
   *   ∧ registry.reportStatus 缺席        （从未交付收尾报告 ⇒ 无人收敛）
   * }}}
   * ⇒ **自动收尾**：把 workReport 作为 completed 终态通知投进父会话持久邮箱
   * （`MailboxProducer.deliver`，identity=taskId ⇒ 与主投递腿同一幂等键）→ 成功后把
   * 记录置 `reportStatus=finish`（「已交付」态，从此不再进本腿）。
   *
   * 幂等/不重复：
   *  - reportStatus 落槽 ⇒ 下一 boot 判据不成立（一次性）；
   *  - 邮箱 `mailOnce` identity 去重 + 消费墓碑 ⇒ 不产生第二条同 id 通知
   *    （`MailboxBootSweep` 的账面合成腿同键，二者互不打架）；
   *  - 先投递后落态（与 [[recoverOne]] 同序）：投递成功前崩 = 下一 boot 重投（去重兜住），
   *    落态后崩 = 零动作。
   * 载体闸：本腿是**邮箱轨**通知腿（生产 boot 恒带 SharedResources）；无载体场
   * （legacy 测试面 / 无 deps）整体旁路——绝不向无人读的队列写垃圾。父会话已删
   * ⇒ 不投放、不落态（诚实：没人可通知，残余留在记录与转录里）。
   */
  private def finalizeCompletedUnreported(
    store: SubAgentTaskStore,
    sessionStore: SessionStore,
    resources: Option[SharedResources]
  ): IO[List[String]] =
    resources match
      case None => IO.pure(Nil)
      case Some(rs) =>
        store
          .findAllTasks()
          .map { rows =>
            // TTL 静默闸（终态 7 天 TTL 批 2026-11-06）：超期 completed 行（终态时刻
            // ≥ 7 天前）不再被自动收尾——远古收尾报告绝不重投；7 天内的收尾照旧。
            val now = System.currentTimeMillis()
            rows.filter(t =>
              !rs.isProtectedExecution(t.taskId) && t.status == "completed" && t.source != "subtask" &&
                !DelegateTerminalTtl.rowExpired(t, now)
            )
          }
          .handleErrorWith(e => logger.warn(s"[startup-recovery] completed-row scan failed: ${e.getMessage}").as(Nil))
          .flatMap(_.traverseFilter(t => finalizeOne(sessionStore, rs, t)))
          .flatMap { ids =>
            if ids.isEmpty then IO.pure(ids)
            else
              logger
                .info(
                  s"[startup-recovery] completion-finalize: ${ids.size} finished-but-unreported delegate(s) closed " +
                    s"(work report delivered to the parent mailbox): ${ids.mkString(", ")}"
                )
                .as(ids)
          }

  /** 单行收尾判定（见 [[finalizeCompletedUnreported]] 判据）。返回 Some(taskId) = 已收尾。 */
  private def finalizeOne(
    sessionStore: SessionStore,
    resources: SharedResources,
    t: SubAgentTask
  ): IO[Option[String]] =
    IO.blocking(DelegateRegistry.find(t.taskId))
      .flatMap {
        case Some(rec) if rec.workReport.exists(_.trim.nonEmpty) && rec.reportStatus.isEmpty =>
          val report = rec.workReport.map(_.trim).getOrElse("")
          val head =
            s"[completed · closing report delivered by boot sweep] Delegate \"${t.description}\" " +
              s"(agent=${t.agentName}, session=${t.taskId}) finished its work in an earlier engine lifetime but never " +
              s"delivered a closing report, so nothing ever converged it. The restart sweep is closing it now — the work " +
              s"report recorded on its delegate record follows verbatim."
          val event = AgentCommand.ExternalEvent(
            source = "delegate",
            eventType = "completed",
            payload = s"$head\n$report",
            metadata = JsonObject(
              "taskId" -> t.taskId.asJson,
              "agentName" -> t.agentName.asJson,
              "description" -> t.description.asJson,
              "workReport" -> true.asJson,
              "recoveredBy" -> "completion-finalize".asJson
            ),
            correlationId = Some(t.taskId)
          )
          for
            // 父会话在场判据 = [[SessionLiveness]]（活体 actor ∪ 索引/盘上记录）。改前读
            // `sessionStore.listSessions`（只读索引）⇒ delegate 父会话恒判「已删」，
            // 收殓回执被静默跳过（OWNER-GONE 误判修复批 2026-11-06；2026-11-06 07:38
            // 日志实证 `parent=delegate-nebflow exists=false` 的正是这条腿）。
            parentExists <- SessionLiveness.alive(resources, t.parentSessionId).handleErrorWith(_ => IO.pure(false))
            delivered <-
              if !parentExists then IO.pure(Left("parent-session-gone"))
              else
                MailboxProducer
                  .deliver(
                    resources,
                    t.parentSessionId,
                    event,
                    identity = t.taskId,
                    kind = "delegate-terminal",
                    coalesce = false,
                    producer = "completion-finalize"
                  )
                  .attempt
                  .map {
                    case Right(nebflow.core.mail.SessionMailbox.MailResult.Failed(cause)) => Left(cause)
                    case Right(nebflow.core.mail.SessionMailbox.MailResult.AlreadyPresent) => Right("AlreadyPresent")
                    case Right(other) => Right(other.toString)
                    case Left(e) => Left(Option(e.getMessage).getOrElse("deliver-error"))
                  }
            // 落「已交付」态：报告内容留在 workReport（updateReport 的 preserve-before-
            // overwrite 保证它不被顶掉），reportStatus=finish ⇒ 本腿一次性。
            marked <- delivered match
              case Right(_) =>
                IO.blocking(DelegateRegistry.updateReport(t.taskId, DelegateRegistry.FinishValue, report, "")).attempt
              case Left(_) => IO.pure(Right(()))
            _ <- IO
              .blocking(
                ChainStore.appendSync(
                  ChainStore.keyFor(rec.taskId, t.taskId),
                  "delegate-completion-finalize",
                  io.circe.Json.obj(
                    "action" -> "finalized".asJson,
                    "delegateId" -> t.taskId.asJson,
                    "delivery" -> delivered.fold(c => s"FAILED:$c", ok => s"$ok").asJson,
                    "workReportChars" -> report.length.asJson,
                    "parentSessionId" -> t.parentSessionId.asJson
                  )
                )
              )
              .handleErrorWith(e =>
                logger.warn(s"[startup-recovery] completion-finalize audit row failed for ${t.taskId}: ${e.getMessage}")
              )
            _ <- logger.info(
              s"[startup-recovery] completion-finalize ${t.taskId}: delivery=${delivered.fold(c => s"FAILED($c)", s => s)} " +
                s"reportStatus=${if delivered.isRight then "finish" else "<unchanged>"} workReportChars=${report.length}" +
                (marked.fold(m => s" markError=${m.getMessage}", _ => ""))
            )
          yield if delivered.isRight then Some(t.taskId) else None
          end for
        case _ => IO.pure(None)
      }
      .handleErrorWith(e =>
        logger.warn(s"[startup-recovery] completion-finalize errored for ${t.taskId}: ${e.getMessage}").as(None)
      )

  /**
   * #692 运行期重启屏障 watchdog 单轮清扫。返回本轮被强制终态化的任务 id。
   * @param stallMs 状态无进展判读阈值（statusAt 距今）；registry 活体行存在 ⇒ 永不判（活着的任务不受阈值约束——有 actor 在，就没有僵尸）。
   */
  def watchdogSweep(
    store: SubAgentTaskStore,
    agentRegistry: Ref[IO, Map[String, nebflow.actor.AgentRecord]],
    sessionStore: SessionStore,
    stallMs: Long,
    resources: Option[SharedResources] = None
  ): IO[List[String]] =
    resources match
      case Some(rs) =>
        watchdogSweepWithAuthority(store, agentRegistry, sessionStore, stallMs, resources, rs.executionCommitPort)
      case None =>
        sessionStore.installedExecutionAuthority match
          case Some(authority) =>
            watchdogSweepWithAuthority(store, agentRegistry, sessionStore, stallMs, resources, authority)
          case None =>
            ExecutionCommitStore.resource(sessionStore.executionAuthorityRoot).use { authority =>
              watchdogSweepWithAuthority(store, agentRegistry, sessionStore, stallMs, resources, authority)
            }

  private def watchdogSweepWithAuthority(
    store: SubAgentTaskStore,
    agentRegistry: Ref[IO, Map[String, nebflow.actor.AgentRecord]],
    sessionStore: SessionStore,
    stallMs: Long,
    resources: Option[SharedResources],
    authority: nebflow.core.ExecutionCommitPort
  ): IO[List[String]] =
    for
      inFlight <- store.findRunningTasks
      registry <- agentRegistry.get
      now <- IO(System.currentTimeMillis())
      candidates = inFlight.filter { t =>
        !registry.contains(t.taskId) && now - t.statusAt.getOrElse(t.spawnedAt) >= stallMs
      }
      zombies <- candidates.filterA { task =>
        val classification = resources.fold(authority.protection(task.taskId))(_.executionProtection(task.taskId))
        classification.map(_ == nebflow.shared.ExecutionProtection.Unprotected)
      }
      _ <-
        if zombies.isEmpty then IO.unit
        else
          logger.warn(
            s"[restarting-watchdog] ${zombies.size} orphaned in-flight task row(s) stalled ≥${stallMs}ms with no live actor — force-terminalizing: " +
              zombies.map(t => s"${t.taskId}(${t.status},retry=${t.retryCount})").mkString(", ")
          )
      reaped <- zombies.traverse { t =>
        val head =
          s"[task lost: restarting-watchdog] Delegate \"${t.description}\" (agent=${t.agentName}, session=${t.taskId}) " +
            s"had an in-flight task row (status=${t.status}, retryCount=${t.retryCount}) but NO live actor for it " +
            s"for over ${stallMs / 1000}s — the restart-barrier watchdog is force-terminalizing it (the " +
            s"respawn barrier stalled or the actor was lost without a terminal event). You may re-delegate this task."
        recoverOne(store, sessionStore, t, head, "lostToWatchdog", resources).handleErrorWith { e =>
          logger.warn(s"[restarting-watchdog] reap failed for task ${t.taskId}: ${e.getMessage}").as(t.taskId)
        }
      }
    yield reaped

  /**
   * watchdog 周期循环：sweep → sleep(interval) → 递归（`>>` by-name 防栈溢出，
   * 错误自愈——扫描器必须活着）。由 GatewayMain 以 fiber 启动。
   */
  def run(resources: SharedResources, interval: FiniteDuration, stallMs: Long): IO[Unit] =
    def loop: IO[Unit] =
      watchdogSweep(
        resources.subAgentTaskStore,
        resources.agentRegistry,
        resources.sessionStore,
        stallMs,
        Some(resources)
      )
        .handleErrorWith(e =>
          logger.warn(s"[restarting-watchdog] sweep failed (will retry next cycle): ${e.getMessage}").as(Nil)
        ) *>
        // P0③（送达链路修复批 2026-11-23）：挂账终态通知的非 boot 周期消费腿——
        // 强终（zero-notify）挂账行在本进程生命周期内经下一拍必被消费投递。
        deliverPendingNotifications(
          resources.subAgentTaskStore,
          resources.sessionStore,
          resources,
          onlyPending = true
        )
          .handleErrorWith(e =>
            logger.warn(s"[startup-recovery] pending-notification reconcile failed (will retry next cycle): ${e.getMessage}")
          ) *> IO.sleep(interval) >> loop
    loop

  private def recoverOne(
    store: SubAgentTaskStore,
    sessionStore: SessionStore,
    task: SubAgentTask,
    resources: Option[SharedResources] = None
  ): IO[String] =
    val head =
      s"[task lost: crash] Delegate \"${task.description}\" (agent=${task.agentName}, session=${task.taskId}) " +
        s"was still in flight when the process restarted."
    recoverOne(store, sessionStore, task, head, "lostToCrash", resources)

  private def recoverOne(
    store: SubAgentTaskStore,
    sessionStore: SessionStore,
    task: SubAgentTask,
    head: String,
    lostMarkerKey: String,
    // 无默认值：与 4 参重载并存时 Scala 禁止双默认参（调用歧义）；三处调用点
    // （183/337/364）均显式传参，legacy 测试面走 4 参重载（= F2 回退）。
    resources: Option[SharedResources]
  ): IO[String] =
    for
      now <- IO(System.currentTimeMillis())
      // 1. Salvage: partial result from the child's own persisted transcript.
      partial <- sessionStore
        .loadMessagesForSession(task.taskId)
        .map(extractLastAssistant)
        .handleErrorWith(_ => IO.pure(None))
      // 父会话在场判据 = [[SessionLiveness]]（见上游 parentExists 注释）：有 resources
      // 时用「活体 actor ∪ 记录」双源，legacy 无载体场退回记录面（索引 ∪ 盘上）。
      parentExists <- resources match
        case Some(rs) => SessionLiveness.alive(rs, task.parentSessionId)
        case None => SessionLiveness.recordAlive(sessionStore, task.parentSessionId)
      // （原 payload/event 构造已随零通知腿摘除——事件只服务于父投递，本批起
      // 收殓链不再构造任何父可见事件。）
      // ═══ 零向上通知（重启手动恢复批，作者令 2026-11-06 20:36 逐字「由于重启/软件
      // 崩溃导致的错误，不用向上通知，不自动恢复，保存状态后，由用户手动恢复」）═══
      // 改前这里构造 delegate-terminal 失败事件并经三条腿投递父会话：跨设备待发区
      // （DeviceDelegate.enqueueLostResultNotice）、持久邮箱（MailboxProducer.deliver）、
      // F2 注入队（notifyOnce），另落持久提醒账（RestartLossLedger.record，压缩时向
      // 父上下文重述）。四腿**全部摘除**：子代理的失败呈现面 = 子 agent 面板（任务行
      // failed + lastError 指纹，[[ManualRevival]] 手动复活）；部分结果仍在子会话转录
      // 里（面板/弹窗可读），父会话上下文零注入。完成腿（finalizeOne 的交付）不在本
      // 判据内——完成交付不是错误通知，保持原样。
      rec = nebflow.core.delegate.DelegateRegistry.find(task.taskId)
      hostDeviceId = rec.flatMap(_.delegatedBy)
      // 投递结果面（保留字段形状供 ChainStore 台账行与日志读面）：本批起恒为
      // suppressed——「零通知」是语义裁定，不是投递失败，台账行如实记之。
      delivery: Either[String, String] = Right("suppressed:zero-notify-by-author-order")
      // 2b. 可观测回执（日志行 + 台账行）：台账行 = 审计（落 ChainStore，不是用户
      // 可见通知）。原「持久提醒账」腿（RestartLossLedger.record → 压缩时向父上下文
      // 重述收殓通知）随零通知语义一并摘除（重启手动恢复批 2026-11-06 20:36）。
      _ <- delivery match
        case Right(ok) =>
          logger.info(
            s"[startup-recovery] zero-notify receipt ${task.taskId} → parent ${task.parentSessionId.take(16)}: $ok " +
              "(panel display + manual revival only — no parent mailbox / F2 / device-outbox / ledger notice)"
          )
        case Left(err) =>
          logger.error(
            s"[startup-recovery] notify DELIVERY FAILED ${task.taskId} → parent ${task.parentSessionId.take(16)}: $err " +
              "(the task row will record the failure honestly; the payload remains in the delegate transcript)"
          )
      _ <- IO
        .blocking(
          ChainStore.appendSync(
            ChainStore.keyFor(rec.flatMap(_.taskId), task.taskId),
            "delegate-terminal-delivery",
            io.circe.Json.obj(
              "delegateId" -> task.taskId.asJson,
              "parentSessionId" -> task.parentSessionId.asJson,
              "delivery" -> delivery.fold(c => s"FAILED:$c", ok => ok).asJson,
              "delivered" -> delivery.isRight.asJson,
              "partialResult" -> partial.isDefined.asJson,
              "lostMarker" -> lostMarkerKey.asJson
            )
          )
        )
        .handleErrorWith(e =>
          logger.warn(s"[startup-recovery] delivery receipt row failed for ${task.taskId}: ${e.getMessage}")
        )
      // 3. Terminalize — after the notify append, so a crash anywhere leaves
      // either (task running + event queued) [retry, no duplicate] or
      // (task failed + event queued) [done]. Never a lost notification.
      _ <- store.transitionObserved(
        task,
        status = "failed",
        lastError = Some(
          // 文案与事实同源（D1）：`delivered` 为 Left 时必须写「投递失败」，绝不写
          // 「delivered to parent」——任务行是面板/对账/晨报的读面，撒谎会误导排障。
          // 前缀恒为 [[RestartLossFingerprint]]（failed 行复活腿的判据依赖它）。
          // 零通知语义（重启手动恢复批 2026-11-06 20:36）：文案如实写「未通知父会话
          // （零通知裁定）」——绝不写「delivered to parent」。
          partial match
            case Some(_) =>
              if lostMarkerKey == "lostToWatchdog" then
                "restarting-barrier stall: no live actor for the in-flight task — watchdog force-terminalized, partial result salvaged" +
                  "; parent NOT notified (zero-notify by author order 2026-11-06 — sub-agent panel shows the state; restore via ManualRevival)"
              else
                s"$RestartLossFingerprint — partial result salvaged" +
                  "; parent NOT notified (zero-notify by author order 2026-11-06 — sub-agent panel shows the state; restore via ManualRevival)"
            case None =>
              if lostMarkerKey == "lostToWatchdog" then
                "restarting-barrier stall: no live actor for the in-flight task — watchdog force-terminalized, result lost" +
                  "; parent NOT notified (zero-notify by author order 2026-11-06 — sub-agent panel shows the state; restore via ManualRevival)"
              else
                s"$RestartLossFingerprint — result lost" +
                  "; parent NOT notified (zero-notify by author order 2026-11-06 — sub-agent panel shows the state; restore via ManualRevival)"
        )
      )
      _ <- logger.info(
        s"[startup-recovery] orphan ${task.taskId} (${task.agentName}) terminalized; " +
          s"parent=${task.parentSessionId.take(16)} exists=$parentExists partialResult=${partial.isDefined} " +
          s"delivery=${delivery.fold(c => s"FAILED:$c", ok => ok)}" +
          hostDeviceId.fold("")(h => s" crossDeviceOutbox=$h")
      )
    yield task.taskId

  // （原 notifyOnce——父会话 F2 注入队的幂等追加——已随零通知腿整体摘除：
  //  重启手动恢复批 2026-11-06 20:36 作者令「不用向上通知」。）

  /**
   * Last non-empty assistant text from the child transcript (same extraction
   * rule the Delegate adapters use for completion payloads).
   */
  private def extractLastAssistant(messages: List[Message]): Option[String] =
    messages.reverse
      .collectFirst {
        case m if m.role == MessageRole.Assistant && m.textContent.trim.nonEmpty => m.textContent
      }
end SubAgentStartupRecovery
