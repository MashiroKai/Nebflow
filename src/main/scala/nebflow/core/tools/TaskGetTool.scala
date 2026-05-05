package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.task.*

object TaskGetTool extends Tool:
  val name = "TaskGet"

  val description =
    """Get a task by ID from the task list.

## When to Use

- Before starting work, to get full description and dependencies
- To understand what a task blocks and what blocks it

## Task System Overview

- Status progresses: `pending` -> `in_progress` -> `completed`
- Valid transitions only: pending -> in_progress, in_progress -> completed
- Use TaskUpdate to change status and manage dependencies (addBlocks, addBlockedBy, removeBlocks, removeBlockedBy)
- Use TaskDelete to permanently remove a task (irreversible)"""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "taskId" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "The ID of the task to retrieve".asJson
        )
      ),
      "required" -> Json.arr("taskId".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val id = input("taskId").flatMap(_.asString).getOrElse("?")
    s"TaskGet(#$id)"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 150 then result.take(147) + "..." else result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    (ctx.taskStore, ctx.sessionId) match
      case (Some(store), Some(sessionId)) =>
        val taskId = input("taskId").flatMap(_.asString).getOrElse("")
        store.get(sessionId, taskId).map {
          case Some(task) =>
            val status = task.status match
              case TaskStatus.Pending => "pending"
              case TaskStatus.InProgress => "in_progress"
              case TaskStatus.Completed => "completed"
              case TaskStatus.Failed => "failed"
            val lines = List(
              s"Task #${task.id} [$status]",
              s"Subject: ${task.subject}",
              s"Description: ${task.description}",
              task.activeForm.map(a => s"Active form: $a").getOrElse(""),
              if task.blocks.nonEmpty then s"Blocks: #${task.blocks.mkString(", #")}" else "",
              if task.blockedBy.nonEmpty then s"Blocked by: #${task.blockedBy.mkString(", #")}" else ""
            ).filter(_.nonEmpty)
            Right(lines.mkString("\n"))
          case None => Left(ToolError(s"Task #$taskId not found"))
        }
      case (None, _) => IO.pure(Left(ToolError("No task store available")))
      case (_, None) => IO.pure(Left(ToolError("No session ID available")))

end TaskGetTool
