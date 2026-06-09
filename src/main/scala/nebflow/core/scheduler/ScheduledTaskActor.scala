package nebflow.core.scheduler

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import io.circe.syntax.*
import nebflow.agent.AgentCommand
import nebflow.core.NebflowLogger
import nebflow.gateway.WsHub
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneId}
import scala.compiletime.uninitialized
import scala.concurrent.duration.*

// ============================================================
// Command protocol — public so GatewayMain / WebSocketRoutes can send commands
// ============================================================

sealed trait ScheduledTaskCommand

object ScheduledTaskCommand:
  /** A new task was created — reschedule the next fire timer. */
  case class TaskCreated(task: ScheduledTask) extends ScheduledTaskCommand

  /** A task was deleted — reschedule the next fire timer. */
  case class TaskDeleted(sessionId: String, taskId: String) extends ScheduledTaskCommand

  /** Fire timer — check and trigger all due tasks. */
  private[scheduler] case object FireTick extends ScheduledTaskCommand

  /** Shutdown the actor. */
  private[scheduler] case object Shutdown extends ScheduledTaskCommand
end ScheduledTaskCommand

// ============================================================
// ScheduledTaskActor — event-driven, no polling
//
// Design:
//   - Uses startSingleTimer to fire at the exact time of the next due task.
//   - When a task is created/deleted, reschedule the timer to the new nearest deadline.
//   - Zero CPU when idle — no periodic scanning.
//   - Mac sleep: Pekko timers pause, no missed or duplicated fires.
// ============================================================

class ScheduledTaskActor(
  actorSystem: ActorSystem[?],
  dispatcher: Dispatcher[IO],
  taskStore: ScheduledTaskStore,
  routeToAgent: (String, AgentCommand.ExternalEvent) => IO[Unit],
  wsHub: WsHub
):

  private val logger = NebflowLogger.forName("nebflow.scheduled-task.actor")

  @volatile private var _ref: ActorRef[ScheduledTaskCommand] = uninitialized

  // Spawn actor on construction
  locally {
    _ref = actorSystem.systemActorOf(behavior(), "scheduled-task-actor")
    // Schedule initial fire based on existing tasks
    dispatcher.unsafeRunAndForget(rescheduleFromStore())
  }

  /** ActorRef for external senders. */
  def ref: ActorRef[ScheduledTaskCommand] = _ref

  private def rescheduleFromStore(): IO[Unit] =
    taskStore.getAllPendingTasks.map { tasks =>
      // FireTick handler will: (1) trigger any due tasks, (2) reschedule timer for next pending
      if tasks.nonEmpty then _ref ! ScheduledTaskCommand.FireTick
    }

  // ============================================================
  // Actor behavior
  // ============================================================

  private def behavior(): Behavior[ScheduledTaskCommand] =
    Behaviors.withTimers { timers =>
      idle(timers)
    }

  private def idle(
    timers: org.apache.pekko.actor.typed.scaladsl.TimerScheduler[ScheduledTaskCommand]
  ): Behavior[ScheduledTaskCommand] =
    Behaviors.receiveMessage {
      case ScheduledTaskCommand.TaskCreated(task) =>
        reschedule(timers)
        Behaviors.same

      case ScheduledTaskCommand.TaskDeleted(_, _) =>
        reschedule(timers)
        Behaviors.same

      case ScheduledTaskCommand.FireTick =>
        fireDueTasks(timers)
        Behaviors.same

      case ScheduledTaskCommand.Shutdown =>
        Behaviors.stopped
    }

  private def reschedule(
    timers: org.apache.pekko.actor.typed.scaladsl.TimerScheduler[ScheduledTaskCommand]
  ): Unit =
    val pending = dispatcher.unsafeRunSync(taskStore.getAllPendingTasks)
    timers.cancel(ScheduledTaskCommand.FireTick)
    if pending.nonEmpty then
      val nearest = pending.map(_.triggerAt).min
      val delay = Math.max(1, nearest - System.currentTimeMillis())
      timers.startSingleTimer(ScheduledTaskCommand.FireTick, delay.millis)

  private def fireDueTasks(
    timers: org.apache.pekko.actor.typed.scaladsl.TimerScheduler[ScheduledTaskCommand]
  ): Unit =
    val due = dispatcher.unsafeRunSync(taskStore.getAllDueTasks)
    for task <- due do
      try
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

        dispatcher.unsafeRunAndForget(
          for
            _ <- logger.info(s"Triggering scheduled task ${task.id} for session ${task.sessionId}: ${task.content.take(60)}")
            _ <- taskStore.markTriggered(task.sessionId, task.id)
            _ <- routeToAgent(task.sessionId, event).handleErrorWith { e =>
              logger.warn(s"Failed to route scheduled task to agent: ${e.getMessage}")
            }
            _ <- wsHub.broadcast(
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
        )
      catch
        case e: Exception =>
          logger.warnSync(s"Failed to trigger scheduled task ${task.id}: ${e.getMessage}")

    // After firing, reschedule to the next pending task
    reschedule(timers)
  end fireDueTasks

  private def formatTime(epochMs: Long): String =
    val instant = Instant.ofEpochMilli(epochMs)
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    formatter.format(instant)

end ScheduledTaskActor
