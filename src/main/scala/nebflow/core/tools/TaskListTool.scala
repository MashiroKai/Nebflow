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

Returns each task's id, subject, status, and dependency info (blockedBy, blocks)."""

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
              val deps = List(
                if t.blockedBy.nonEmpty then s"blockedBy: #${t.blockedBy.mkString(", #")}" else "",
                if t.blocks.nonEmpty then s"blocks: #${t.blocks.mkString(", #")}" else ""
              ).filter(_.nonEmpty).mkString(", ")
              val depStr = if deps.nonEmpty then s" ($deps)" else ""
              s"#${t.id} [$status] ${t.subject}$depStr"
            }
            Right(lines.mkString("\n"))
        }
      case (None, _) => IO.pure(Left(ToolError("No task store available")))
      case (_, None) => IO.pure(Left(ToolError("No session ID available")))

end TaskListTool
