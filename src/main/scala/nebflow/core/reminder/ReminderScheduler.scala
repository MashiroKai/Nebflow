package nebflow.core.reminder

import cats.effect.{Fiber, IO}
import cats.syntax.all.*
import fs2.Stream
import io.circe.syntax.*
import nebflow.agent.AgentCommand
import nebflow.core.NebflowLogger
import nebflow.gateway.{SessionStore, WsHub}

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneId}

import scala.concurrent.duration.*

object ReminderScheduler:

  private val logger = NebflowLogger.forName("nebflow.reminder.scheduler")

  def start(
    reminderStore: ReminderStore,
    routeToAgent: (String, AgentCommand.ExternalEvent) => IO[Unit],
    wsHub: WsHub,
    sessionStore: SessionStore
  ): IO[Fiber[IO, Throwable, Unit]] =
    val stream = Stream
      .awakeEvery[IO](30.seconds)
      .evalMap(_ => tick(reminderStore, routeToAgent, wsHub, sessionStore))
      .handleErrorWith(e =>
        Stream.eval(logger.warn(s"Reminder scheduler tick failed: ${e.getMessage}")) *> Stream.empty
      )
    stream.compile.drain.start

  private def tick(
    reminderStore: ReminderStore,
    routeToAgent: (String, AgentCommand.ExternalEvent) => IO[Unit],
    wsHub: WsHub,
    sessionStore: SessionStore
  ): IO[Unit] =
    reminderStore.getAllDueReminders.flatMap { due =>
      if due.isEmpty then IO.unit
      else
        due.traverse_ { reminder =>
          val formattedTime = formatTime(reminder.triggerAt)
          // Build payload
          val refNote = reminder.referencePath match
            case Some(path) => s"\n（参考文档: $path）"
            case None => ""
          val payload = s"[定时提醒触发] ${reminder.content}$refNote\n（预定于 $formattedTime 触发）"

          val event = AgentCommand.ExternalEvent(
            source = "reminder",
            eventType = "trigger",
            payload = payload,
            metadata = io.circe.JsonObject(
              "reminderId" -> reminder.id.asJson,
              "triggerAt" -> reminder.triggerAt.asJson,
              "referencePath" -> reminder.referencePath.asJson
            ),
            correlationId = Some(reminder.id)
          )

          for
            _ <- logger.info(
              s"Triggering reminder ${reminder.id} for session ${reminder.sessionId}: ${reminder.content.take(60)}"
            )
            _ <- reminderStore.markTriggered(reminder.sessionId, reminder.id)
            _ <- routeToAgent(reminder.sessionId, event).handleErrorWith { e =>
              logger.warn(s"Failed to route reminder to agent: ${e.getMessage}")
            }
            _ <- wsHub.broadcast(
              io.circe.Json.obj(
                "type" -> "reminderTriggered".asJson,
                "sessionId" -> reminder.sessionId.asJson,
                "reminder" -> io.circe.Json.obj(
                  "id" -> reminder.id.asJson,
                  "content" -> reminder.content.asJson,
                  "triggerAt" -> reminder.triggerAt.asJson,
                  "referencePath" -> reminder.referencePath.asJson,
                  "formattedTime" -> formattedTime.asJson
                )
              )
            )
          yield ()
          end for
        }
    }

  private def formatTime(epochMs: Long): String =
    val instant = Instant.ofEpochMilli(epochMs)
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    formatter.format(instant)

end ReminderScheduler
