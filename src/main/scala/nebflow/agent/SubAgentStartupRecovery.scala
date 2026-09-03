package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.core.NebflowLogger
import nebflow.gateway.SessionStore
import nebflow.shared.Message
import nebflow.shared.MessageRole

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
 */
object SubAgentStartupRecovery:
  private val logger = NebflowLogger.forName("nebflow.agent.startup-recovery")

  /** Run the orphan sweep. Returns the terminalized task ids (for boot log). */
  def recoverOrphans(store: SubAgentTaskStore, sessionStore: SessionStore): IO[List[String]] =
    store.findRunningTasks.flatMap {
      case Nil => IO.pure(Nil)
      case orphans =>
        logger.warn(
          s"[startup-recovery] ${orphans.size} in-flight delegate task(s) survived a process restart — terminalizing + notifying parent session(s)"
        ) *> orphans.traverse(t => recoverOne(store, sessionStore, t).handleErrorWith { e =>
          logger.warn(s"[startup-recovery] recovery failed for task ${t.taskId}: ${e.getMessage}").as(t.taskId)
        })
    }

  private def recoverOne(store: SubAgentTaskStore, sessionStore: SessionStore, task: SubAgentTask): IO[String] =
    for
      now <- IO(System.currentTimeMillis())
      // 1. Salvage: partial result from the child's own persisted transcript.
      partial <- sessionStore
        .loadMessagesForSession(task.taskId)
        .map(extractLastAssistant)
        .handleErrorWith(_ => IO.pure(None))
      parentExists <- sessionStore.listSessions.map(_.exists(_.id == task.parentSessionId))
      payload =
        val head =
          s"[task lost: crash] Delegate \"${task.description}\" (agent=${task.agentName}, session=${task.taskId}) " +
            s"was still in flight when the process restarted."
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
          "lostToCrash" -> true.asJson,
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
            case Some(_) => "process restart during in-flight task — partial result salvaged and delivered to parent (startup recovery V2)"
            case None    => "process restart during in-flight task — result lost, parent notified (startup recovery V2)"
        ),
        completedAt = Some(now)
      )
      _ <- logger.info(
        s"[startup-recovery] orphan ${task.taskId} (${task.agentName}) terminalized; " +
          s"parent=${task.parentSessionId.take(16)} exists=$parentExists partialResult=${partial.isDefined}"
      )
    yield task.taskId

  /** Append the event to the parent's persisted injection queue, unless an
    * event with the same correlationId (taskId) is already queued — the
    * crash-between-steps guard that keeps notification at-most-once. */
  private def notifyOnce(parentSessionId: String, event: AgentCommand.ExternalEvent): IO[Unit] =
    CompactionQueueStore.load(parentSessionId).flatMap { existing =>
      val already = existing.exists(_.events.exists(_.correlationId.contains(event.correlationId.getOrElse(""))))
      if already then IO.unit
      else
        val q = existing.getOrElse(CompactionQueueStore.PersistedQueues())
        CompactionQueueStore.save(parentSessionId, q.copy(events = q.events :+ event))
    }

  /** Last non-empty assistant text from the child transcript (same extraction
    * rule the Delegate adapters use for completion payloads). */
  private def extractLastAssistant(messages: List[Message]): Option[String] =
    messages.reverse
      .collectFirst {
        case m if m.role == MessageRole.Assistant && m.textContent.trim.nonEmpty => m.textContent
      }
end SubAgentStartupRecovery
