package nebflow.core.scheduler

import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.agent.AgentCommand
import nebflow.core.NebflowLogger
import nebflow.gateway.SessionStore
import nebflow.shared.UiMessage

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneId}

import scala.concurrent.duration.*

// ============================================================
// ScheduledTaskService — robust periodic task firing
//
// Design:
//   - start() launches a background fiber that polls every 10 seconds.
//   - Due tasks are fired, then deleted (one-shot) or rescheduled (recurring).
//   - notifyTaskChange() triggers an immediate check (best-effort, non-blocking).
//   - Simpler and more reliable than the old Deferred-based approach, which had
//     a race condition between consuming the old signal and setting the new one.
// ============================================================

class ScheduledTaskService(
  dispatcher: Dispatcher[IO],
  taskStore: ScheduledTaskStore,
  routeToAgent: (String, AgentCommand.ExternalEvent) => IO[Unit],
  broadcast: Json => IO[Unit],
  sessionStore: SessionStore
):

  private val logger = NebflowLogger.forName("nebflow.scheduled-task")

  /** Flag to request an immediate check (set by notifyTaskChange, cleared by loop). */
  private val checkNowRef: Ref[IO, Boolean] = Ref.unsafe(false)

  /** Launch the background loop. Call once at startup. */
  def start(): IO[Unit] =
    logger.info("Starting scheduled task service (10s poll interval)") *> loop

  /** Signal that the task set changed — requests an immediate poll. */
  def notifyTaskChange(): IO[Unit] = checkNowRef.set(true)

  // ============================================================
  // Polling loop — every 10 seconds, or sooner if notified
  // ============================================================

  private val PollInterval = 10.seconds

  private def loop: IO[Unit] =
    for
      _ <- fireDueTasks.handleErrorWith(e => logger.warn(s"Scheduled task loop error: ${e.getMessage}").void)
      // Check if we were notified during fireDueTasks — if so, skip the sleep
      notified <- checkNowRef.getAndSet(false)
      sleepTime = if notified then 1.second else PollInterval
      _ <- IO.sleep(sleepTime)
      _ <- loop
    yield ()

  private def fireDueTasks: IO[Unit] =
    for
      due <- taskStore.getAllDueTasks
      _ <- due.toList.traverse_(triggerTask)
    yield ()

  private def triggerTask(task: ScheduledTask): IO[Unit] =
    val formattedTime = formatTime(task.triggerAt)
    val actualTime = formatTime(System.currentTimeMillis())
    val refNote = task.referencePath match
      case Some(path) => s"\n（参考文档: $path）"
      case None => ""
    val payload = s"[定时任务触发] ${task.content}$refNote\n（实际触发 $actualTime，预定 $formattedTime）"

    val event = AgentCommand.ExternalEvent(
      source = "scheduled-task",
      eventType = "trigger",
      payload = payload,
      metadata = io.circe.JsonObject(
        "taskId" -> task.id.asJson,
        "triggerAt" -> task.triggerAt.asJson,
        "referencePath" -> task.referencePath.asJson
      ),
      correlationId = Some(task.id)
    )

    for
      _ <- logger.info(
        s"Triggering scheduled task ${task.id} for session ${task.sessionId}: ${task.content.take(60)}"
      )
      // Session fallback: if the original session no longer exists (e.g. after restart),
      // route to the active session instead of dropping the task.
      resolvedSessionId <- sessionStore.getSessionMeta(task.sessionId).flatMap {
        case Some(_) => IO.pure(task.sessionId)
        case None =>
          sessionStore.getActiveId.flatMap { activeId =>
            if activeId.nonEmpty && activeId != task.sessionId then
              logger.info(s"Session ${task.sessionId} not found, falling back to $activeId") *>
                IO.pure(activeId)
            else IO.pure(task.sessionId)
          }
      }
      // Persist the trigger as a user message in session history so it survives
      // page refresh and behaves like a normal user message (shows bubble + busy state).
      _ <- sessionStore.appendUiMessages(
        resolvedSessionId,
        List(UiMessage.User(payload, timestamp = System.currentTimeMillis()))
      )
      // Broadcast the user message so the frontend shows it in real-time
      _ <- broadcast(
        io.circe.Json.obj(
          "type" -> "userMessage".asJson,
          "sessionId" -> resolvedSessionId.asJson,
          "content" -> payload.asJson,
          "timestamp" -> System.currentTimeMillis().asJson
        )
      )
      // Route to agent — ExternalEvent so it queues properly if agent is busy
      _ <- routeToAgent(resolvedSessionId, event).handleErrorWith(e =>
        logger.warn(s"Failed to route scheduled task to agent: ${e.getMessage}")
      )
      _ <- task.repeat match
        case Some(r) if r == "hourly" || r == "daily" || r == "weekly" =>
          val nextTriggerAt = nextTriggerTime(task.triggerAt, r)
          taskStore.rescheduleTask(task.sessionId, task.id, nextTriggerAt) *>
            logger.info(s"Rescheduled recurring task ${task.id} (${r}) for ${formatTime(nextTriggerAt)}")
        case _ =>
          // One-shot task: delete from storage so it doesn't clutter the list.
          taskStore.deleteTask(task.sessionId, task.id)
      _ <- broadcast(
        io.circe.Json.obj(
          "type" -> "scheduledTaskTriggered".asJson,
          "sessionId" -> task.sessionId.asJson,
          "task" -> io.circe.Json.obj(
            "id" -> task.id.asJson,
            "content" -> task.content.asJson,
            "triggerAt" -> task.triggerAt.asJson,
            "referencePath" -> task.referencePath.asJson,
            "repeat" -> task.repeat.asJson,
            "formattedTime" -> formattedTime.asJson
          )
        )
      )
    yield ()

    end for

  end triggerTask

  private def nextTriggerTime(current: Long, repeat: String): Long = repeat match
    case "hourly" => current + 3_600_000L
    case "daily" => current + 86_400_000L
    case "weekly" => current + 604_800_000L
    case other => current // unknown pattern — don't advance (will re-fire immediately)

  private def formatTime(epochMs: Long): String =
    val instant = Instant.ofEpochMilli(epochMs)
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    formatter.format(instant)

end ScheduledTaskService
