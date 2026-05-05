package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.task.*

object TaskListTool extends Tool:
  val name = "TaskList"

  val description =
    """List all tasks in the task list.

## When to Use

- To check overall progress
- To find available tasks (status: 'pending', not blocked)
- After completing a task, to find next work
- Prefer working on tasks in ID order when multiple are available

## Output

Returns each task's id, subject, status, and blockedBy (open blockers only).

## Task System Overview

- Status progresses: `pending` -> `in_progress` -> `completed` or `failed`
- Valid transitions: pending -> in_progress, pending -> completed, pending -> failed, in_progress -> completed, in_progress -> failed
- `completed` and `failed` cannot go back to any other state
- Use TaskUpdate to change status and manage dependencies
- Use TaskDelete to permanently remove a task (irreversible)"""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj()
    )
  )

  def summarize(input: JsonObject): String = "TaskList()"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 200 then result.take(197) + "..." else result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    (ctx.taskStore, ctx.sessionId) match
      case (Some(store), Some(sessionId)) =>
        store.list(sessionId).map { tasks =>
          if tasks.isEmpty then Right("No tasks in the list.")
          else
            val lines = tasks.map { t =>
              val status = t.status match
                case TaskStatus.Pending => "pending"
                case TaskStatus.InProgress => "in_progress"
                case TaskStatus.Completed => "completed"
                case TaskStatus.Failed => "failed"
              val blocked = if t.blockedBy.nonEmpty then s" [blocked by #${t.blockedBy.mkString(", #")}]" else ""
              s"#${t.id} [$status] ${t.subject}$blocked"
            }
            Right(lines.mkString("\n"))
        }
      case (None, _) => IO.pure(Left(ToolError("No task store available")))
      case (_, None) => IO.pure(Left(ToolError("No session ID available")))

end TaskListTool
