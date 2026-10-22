package nebflow.agent

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.actor.AgentCommand
import nebflow.core.SessionStore
import nebflow.core.delegate.DelegateRegistry
import nebflow.shared.*

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

  /** 在飞孤儿资格：running ＋ restarting（supervisor 退避态——进程死亡时同样在飞）。 */
  private val InFlightStatuses = Set("running", "restarting")

  /** Run the orphan sweep (＋重启打断标记回溯腿). Returns the terminalized task ids (for boot log). */
  def recoverOrphans(store: SubAgentTaskStore, sessionStore: SessionStore): IO[List[String]] =
    inFlightTasks(store).flatMap {
      case Nil => markInterruptedRecords(store).as(Nil)
      case orphans =>
        logger.warn(
          s"[startup-recovery] ${orphans.size} in-flight delegate task(s) survived a process restart — terminalizing + notifying parent session(s)"
        ) *> orphans
          .traverse(t =>
            recoverOne(store, sessionStore, t).handleErrorWith { e =>
              logger.warn(s"[startup-recovery] recovery failed for task ${t.taskId}: ${e.getMessage}").as(t.taskId)
            }
          )
          .flatMap(ids => markInterruptedRecords(store).as(ids))
    }

  /** 在飞任务全量（running/restarting；单遍 findAllTasks 后过滤——boot 一次性）。
    * [合并融合] 标记腿与 #692 收宽同向：本 helper 服务 boot sweep（一次性全量），
    * 运行期视野走 watchdogSweep 的 findRunningTasks（已加宽 running∨restarting）。 */
  private def inFlightTasks(store: SubAgentTaskStore): IO[List[SubAgentTask]] =
    store.findAllTasks()
      .map(_.filter(t => InFlightStatuses.contains(t.status)))
      .handleErrorWith(e => logger.warn(s"[startup-recovery] in-flight scan failed: ${e.getMessage}").as(Nil))

  /**
   * 重启打断标记回溯腿（幂等，boot 恰一次）：任务记录带重启指纹（本 boot 终态化的
   * 或历史 boot 落下的）⇒ 对应 registry 记录落 `restartInterruptedAt` 旗。守卫三闸：
   * 未旗（幂等）/ 在册未归档（归档尸体零触碰）/ 未交付报告（真完成不被旗截断）。
   * 单条失败只 WARN，绝不阻断 boot。
   */
  private def markInterruptedRecords(store: SubAgentTaskStore): IO[Int] =
    for
      tasks <- store.findAllTasks()
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
                  r.restartInterruptedAt.isEmpty && r.archivedAt.isEmpty && r.reportStatus.isEmpty && lostIds.contains(
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
        if marked > 0 then
          logger.info(s"[startup-recovery] restart-interruption marked on $marked delegate record(s)")
        else IO.unit
    yield marked

  /**
   * #692 运行期重启屏障 watchdog 单轮清扫。返回本轮被强制终态化的任务 id。
   * @param stallMs 状态无进展判读阈值（statusAt 距今）；registry 活体行存在 ⇒ 永不判（活着的任务不受阈值约束——有 actor 在，就没有僵尸）。
   */
  def watchdogSweep(
    store: SubAgentTaskStore,
    agentRegistry: Ref[IO, Map[String, nebflow.actor.AgentRecord]],
    sessionStore: SessionStore,
    stallMs: Long
  ): IO[List[String]] =
    for
      inFlight <- store.findRunningTasks
      registry <- agentRegistry.get
      now <- IO(System.currentTimeMillis())
      zombies = inFlight.filter { t =>
        !registry.contains(t.taskId) && now - t.statusAt.getOrElse(t.spawnedAt) >= stallMs
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
        recoverOne(store, sessionStore, t, head, "lostToWatchdog").handleErrorWith { e =>
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
      watchdogSweep(resources.subAgentTaskStore, resources.agentRegistry, resources.sessionStore, stallMs)
        .handleErrorWith(e =>
          logger.warn(s"[restarting-watchdog] sweep failed (will retry next cycle): ${e.getMessage}").as(Nil)
        ) *> IO.sleep(interval) >> loop
    loop

  private def recoverOne(store: SubAgentTaskStore, sessionStore: SessionStore, task: SubAgentTask): IO[String] =
    val head =
      s"[task lost: crash] Delegate \"${task.description}\" (agent=${task.agentName}, session=${task.taskId}) " +
        s"was still in flight when the process restarted."
    recoverOne(store, sessionStore, task, head, "lostToCrash")

  private def recoverOne(
    store: SubAgentTaskStore,
    sessionStore: SessionStore,
    task: SubAgentTask,
    head: String,
    lostMarkerKey: String
  ): IO[String] =
    for
      now <- IO(System.currentTimeMillis())
      // 1. Salvage: partial result from the child's own persisted transcript.
      partial <- sessionStore
        .loadMessagesForSession(task.taskId)
        .map(extractLastAssistant)
        .handleErrorWith(_ => IO.pure(None))
      parentExists <- sessionStore.listSessions.map(_.exists(_.id == task.parentSessionId))
      payload =
        partial match
          case Some(text) =>
            s"$head Partial result recovered from the child's disk transcript:\n$text"
          case None =>
            s"$head No recoverable result on disk — the task is marked failed. " +
              s"Original prompt: ${task.prompt.take(500)}"
      event = AgentCommand.ExternalEvent(
        source = if task.source == "subtask" then "subtask" else "delegate",
        eventType = "failed",
        payload = payload,
        metadata = JsonObject(
          "taskId" -> task.taskId.asJson,
          "agentName" -> task.agentName.asJson,
          "description" -> task.description.asJson,
          lostMarkerKey -> true.asJson,
          "recoveredBy" -> "startup-recovery".asJson
        ),
        correlationId = Some(task.taskId)
      )
      // 2. Notify the parent via its F2 injection-queue file (idempotent on
      // correlationId — see object doc).
      _ <- if parentExists then notifyOnce(task.parentSessionId, event) else IO.unit
      // 3. Terminalize — after the notify append, so a crash anywhere leaves
      // either (task running + event queued) [retry, no duplicate] or
      // (task failed + event queued) [done]. Never a lost notification.
      _ <- store.updateStatus(
        task.parentSessionId,
        task.taskId,
        status = "failed",
        lastError = Some(
          partial match
            case Some(_) =>
              if lostMarkerKey == "lostToWatchdog" then
                "restarting-barrier stall: no live actor for the in-flight task — watchdog force-terminalized, partial result salvaged and delivered to parent"
              else
                "process restart during in-flight task — partial result salvaged and delivered to parent (startup recovery V2)"
            case None =>
              if lostMarkerKey == "lostToWatchdog" then
                "restarting-barrier stall: no live actor for the in-flight task — watchdog force-terminalized, result lost, parent notified"
              else "process restart during in-flight task — result lost, parent notified (startup recovery V2)"
        ),
        completedAt = Some(now)
      )
      _ <- logger.info(
        s"[startup-recovery] orphan ${task.taskId} (${task.agentName}) terminalized; " +
          s"parent=${task.parentSessionId.take(16)} exists=$parentExists partialResult=${partial.isDefined}"
      )
    yield task.taskId

  /**
   * Append the event to the parent's persisted injection queue, unless an
   * event with the same correlationId (taskId) is already queued — the
   * crash-between-steps guard that keeps notification at-most-once.
   */
  private def notifyOnce(parentSessionId: String, event: AgentCommand.ExternalEvent): IO[Unit] =
    CompactionQueueStore.load(parentSessionId).flatMap { existing =>
      val already = existing.exists(_.events.exists(_.correlationId.contains(event.correlationId.getOrElse(""))))
      if already then IO.unit
      else
        val q = existing.getOrElse(CompactionQueueStore.PersistedQueues())
        CompactionQueueStore.save(parentSessionId, q.copy(events = q.events :+ event))
    }

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
