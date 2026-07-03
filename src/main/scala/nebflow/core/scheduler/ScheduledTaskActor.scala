package nebflow.core.scheduler

import cats.effect.std.Dispatcher
import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.agent.AgentCommand
import nebflow.core.NebflowLogger

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneId}

import scala.concurrent.duration.*

// ============================================================
// ScheduledTaskService — event-driven scheduled task firing
//
// Design:
//   - start() launches a background fiber loop.
//   - The loop fires due tasks, then sleeps until the nearest deadline.
//   - notifyTaskChange() signals the loop to wake up early via a Deferred.
//   - Zero CPU when idle — no periodic scanning.
// ============================================================

class ScheduledTaskService(
  dispatcher: Dispatcher[IO],
  taskStore: ScheduledTaskStore,
  routeToAgent: (String, AgentCommand.ExternalEvent) => IO[Unit],
  broadcast: Json => IO[Unit]
):

  private val logger = NebflowLogger.forName("nebflow.scheduled-task")

  // Wakeup signal — completed by notifyTaskChange() to interrupt the sleep.
  // Ref holds the current Deferred; replaced each loop iteration.
  private val wakeupRef: Ref[IO, Option[Deferred[IO, Unit]]] = Ref.unsafe(None)

  /** Launch the background loop. Call once at startup. */
  def start(): IO[Unit] =
    for
      d <- Deferred[IO, Unit]
      _ <- wakeupRef.set(Some(d))
      _ <- loop
    yield ()

  /** Signal that the task set changed — wakes the loop to re-evaluate deadlines. */
  def notifyTaskChange(): IO[Unit] =
    wakeupRef.get.flatMap {
      case Some(d) => d.complete(()).void
      case None => IO.unit
    }

  // ============================================================
  // Timing loop
  // ============================================================

  private def loop: IO[Unit] =
    for
      _ <- fireDueTasks.handleErrorWith(e => logger.warn(s"Scheduled task loop error: ${e.getMessage}").void)
      delay <- nextDelay
      sigOpt <- wakeupRef.get
      _ <- sigOpt match
        case Some(sig) => IO.race(IO.sleep(delay), sig.get).void
        case None => IO.sleep(delay)
      // Create a fresh Deferred for the next round
      newSig <- Deferred[IO, Unit]
      _ <- wakeupRef.set(Some(newSig))
      _ <- loop
    yield ()

  private def nextDelay: IO[FiniteDuration] =
    taskStore.getAllPendingTasks.map { tasks =>
      if tasks.isEmpty then 1.hour
      else
        val nearest = tasks.map(_.triggerAt).min
        val ms = Math.max(1, nearest - System.currentTimeMillis())
        ms.millis
    }

  private def fireDueTasks: IO[Unit] =
    for
      due <- taskStore.getAllDueTasks
      _ <- due.toList.traverse_(triggerTask)
    yield ()

  private def triggerTask(task: ScheduledTask): IO[Unit] =
    val formattedTime = formatTime(task.triggerAt)
    val refNote = task.referencePath match
      case Some(path) => s"\n（参考文档: $path）"
      case None => ""
    val payload = s"[定时任务触发] ${task.content}$refNote\n（预定于 $formattedTime 触发）"

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
      _ <- taskStore.markTriggered(task.sessionId, task.id)
      _ <- routeToAgent(task.sessionId, event).handleErrorWith(e =>
        logger.warn(s"Failed to route scheduled task to agent: ${e.getMessage}")
      )
      _ <- broadcast(
        io.circe.Json.obj(
          "type" -> "scheduledTaskTriggered".asJson,
          "sessionId" -> task.sessionId.asJson,
          "task" -> io.circe.Json.obj(
            "id" -> task.id.asJson,
            "content" -> task.content.asJson,
            "triggerAt" -> task.triggerAt.asJson,
            "referencePath" -> task.referencePath.asJson,
            "formattedTime" -> formattedTime.asJson
          )
        )
      )
    yield ()

    end for

  end triggerTask

  private def formatTime(epochMs: Long): String =
    val instant = Instant.ofEpochMilli(epochMs)
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    formatter.format(instant)

end ScheduledTaskService
