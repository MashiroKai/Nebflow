package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.task.*

object TaskDeleteTool extends Tool:
  val name = "TaskDelete"

  val description =
    """Permanently delete a task from the task list.

## When to Use

- When a task is no longer relevant or was created in error
- When a task has been superseded by another task
- When cleaning up completed tasks that are no longer needed

## When NOT to Use

- To mark a task as finished (use TaskUpdate with status: completed instead)
- When a task is partially done but abandoned (keep it and mark in_progress)

Deletion is irreversible. The task will be removed from the file system,
and any references to it in other tasks' blocks/blockedBy lists will be
automatically cleaned up.

## Task System Overview

- Status progresses: `pending` -> `in_progress` -> `completed` or `failed`
- Valid transitions: pending -> in_progress, pending -> completed, pending -> failed, in_progress -> completed, in_progress -> failed
- Use TaskUpdate to change status and manage dependencies
- Use TaskDelete only when a task is truly no longer needed"""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "taskId" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "The ID of the task to delete".asJson
        )
      ),
      "required" -> Json.arr("taskId".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val id = input("taskId").flatMap(_.asString).getOrElse("?")
    s"TaskDelete(#$id)"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 120 then result.take(117) + "..." else result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    (ctx.taskStore, ctx.sessionId) match
      case (Some(store), Some(sessionId)) =>
        val taskId = input("taskId").flatMap(_.asString).getOrElse("")
        store.delete(sessionId, taskId).flatMap { deleted =>
          if deleted then TaskToolHelper.emitTaskListUpdate(store, sessionId, ctx).as(Right(s"Task #$taskId deleted"))
          else IO.pure(Left(ToolError(s"Task #$taskId not found")))
        }
      case (None, _) => IO.pure(Left(ToolError("No task store available")))
      case (_, None) => IO.pure(Left(ToolError("No session ID available")))

end TaskDeleteTool
