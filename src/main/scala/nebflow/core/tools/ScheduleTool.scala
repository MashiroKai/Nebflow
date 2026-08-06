package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.scheduler.ScheduledTask

object ScheduleTool extends Tool:
  val name = "Schedule"

  val description =
    """Schedule a task to fire at a specified time, optionally recurring.

## When to Use

- Schedule a reminder or follow-up for a future time
- Set up a recurring task (hourly, daily, weekly checks)
- Automate periodic actions without manual prompting

## Fields

- **content** (required): Task content/description — what should happen when the task fires.
- **triggerAt** (required): Epoch milliseconds (UTC) when the task should fire. Must be in the future.
- **repeat** (optional): Recurrence pattern. One of "hourly", "daily", "weekly". Omit for a one-shot task.

## Notes

- The task fires as an ExternalEvent in the current session.
- For recurring tasks, the next fire time is computed from the previous triggerAt (not wall clock), maintaining consistent intervals."""

  private val validRepeat = Set("hourly", "daily", "weekly")

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "content" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Task content — what should happen when the task fires".asJson
        ),
        "triggerAt" -> Json.obj(
          "type" -> "integer".asJson,
          "description" -> "Epoch milliseconds (UTC) when the task should fire. Must be in the future.".asJson
        ),
        "repeat" -> Json.obj(
          "type" -> "string".asJson,
          "enum" -> Json.arr("hourly".asJson, "daily".asJson, "weekly".asJson),
          "description" -> "Recurrence pattern. Omit for one-shot.".asJson
        )
      ),
      "required" -> Json.arr("content".asJson, "triggerAt".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val content = input("content").flatMap(_.asString).getOrElse("")
    val repeat = input("repeat").flatMap(_.asString).getOrElse("once")
    val triggerAt = input("triggerAt").flatMap(_.asNumber.flatMap(_.toLong)).getOrElse(0L)
    s"Schedule($repeat @ $triggerAt: $content)"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.startsWith("Scheduled task") then "scheduled"
    else "failed"

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    ctx.sharedResources match
      case None => IO.pure(Left(ToolError("SharedResources not available")))
      case Some(sr) =>
        ctx.sessionId match
          case None => IO.pure(Left(ToolError("No session ID available")))
          case Some(sessionId) =>
            val content = input("content").flatMap(_.asString).getOrElse("")
            val triggerAt = input("triggerAt").flatMap(_.asNumber.flatMap(_.toLong)).getOrElse(0L)
            val repeat = input("repeat").flatMap(_.asString).filter(validRepeat.contains)
            val now = System.currentTimeMillis()

            if content.isBlank then IO.pure(Left(ToolError("content is required and must not be blank")))
            else if triggerAt <= now then
              IO.pure(Left(ToolError(s"triggerAt must be in the future (given $triggerAt, current $now)")))
            else
              val task = ScheduledTask.create(sessionId, content, triggerAt, None, repeat)
              for
                _ <- sr.scheduledTaskStore.addTask(task)
                _ <- sr.scheduledTaskService match
                  case Some(svc) => svc.notifyTaskChange()
                  case None => IO.unit
                // Broadcast to frontend so the reminder panel updates in real-time
                _ <- ctx.wsSend match
                  case Some(send) =>
                    send(
                      io.circe.Json.obj(
                        "type" -> "scheduledTaskCreated".asJson,
                        "sessionId" -> sessionId.asJson,
                        "task" -> io.circe.Json.obj(
                          "id" -> task.id.asJson,
                          "content" -> task.content.asJson,
                          "triggerAt" -> task.triggerAt.asJson,
                          "createdAt" -> task.createdAt.asJson,
                          "referencePath" -> io.circe.Json.Null,
                          "repeat" -> task.repeat.asJson
                        )
                      )
                    )
                  case None => IO.unit
              yield Right(
                s"Scheduled task ${task.id} for ${formatTime(triggerAt)}" +
                  repeat.map(r => s" (recurring: $r)").getOrElse("") +
                  s": ${content.take(80)}"
              )

              end for

            end if

  private def formatTime(epochMs: Long): String =
    val instant = java.time.Instant.ofEpochMilli(epochMs)
    val formatter = java.time.format.DateTimeFormatter
      .ofPattern("yyyy-MM-dd HH:mm")
      .withZone(java.time.ZoneId.systemDefault())
    formatter.format(instant)

end ScheduleTool
