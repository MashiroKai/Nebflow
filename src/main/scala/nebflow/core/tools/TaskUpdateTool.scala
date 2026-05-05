package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.task.*

object TaskUpdateTool extends Tool:
  val name = "TaskUpdate"

  val description =
    """Update a task in the task list.

## When to Use

**Mark tasks as resolved:**
- When you have completed the work described in a task
- IMPORTANT: Always mark tasks as completed when you finish them
- After resolving, call TaskList to find your next task

- ONLY mark a task as completed when you have FULLY accomplished it
- If you encounter errors, blockers, or cannot finish, mark as failed
- Never mark completed if:
  - Tests are failing
  - Implementation is partial
  - You encountered unresolved errors

**Mark tasks as failed:**
- When you cannot complete a task due to errors, blockers, or external issues
- When a task is no longer feasible
- `failed` is a terminal state — it cannot transition back

**Update task details:**
- When establishing dependencies between tasks
- When correcting dependency relationships (use removeBlocks/removeBlockedBy)

## Status Workflow

Status progresses: `pending` -> `in_progress` -> `completed` or `failed`

Valid transitions:
- `pending` -> `in_progress`
- `pending` -> `completed` (for trivial tasks)
- `pending` -> `failed`
- `in_progress` -> `completed`
- `in_progress` -> `failed`
- `completed` cannot be changed to any other state
- `failed` cannot be changed to any other state

## Dependency Management

- `addBlockedBy`: Add task IDs that must complete before this one can start
- `addBlocks`: Add task IDs that this task blocks
- `removeBlockedBy`: Remove blocking dependencies
- `removeBlocks`: Remove blocked tasks
- Circular dependencies are detected and rejected automatically

## Examples

Mark as in progress: {"taskId": "1", "status": "in_progress"}
Mark as completed:  {"taskId": "1", "status": "completed"}
Mark as failed:     {"taskId": "1", "status": "failed"}
Set dependency:     {"taskId": "2", "addBlockedBy": ["1"]}
Remove dependency:  {"taskId": "2", "removeBlockedBy": ["1"]}"""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "taskId" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "The ID of the task to update".asJson
        ),
        "subject" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "New subject".asJson
        ),
        "description" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "New description".asJson
        ),
        "activeForm" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Present continuous form".asJson
        ),
        "status" -> Json.obj(
          "type" -> "string".asJson,
          "enum" -> Json.arr("pending".asJson, "in_progress".asJson, "completed".asJson, "failed".asJson),
          "description" -> "New status".asJson
        ),
        "addBlocks" -> Json.obj(
          "type" -> "array".asJson,
          "items" -> Json.obj("type" -> "string".asJson),
          "description" -> "Task IDs this task blocks".asJson
        ),
        "addBlockedBy" -> Json.obj(
          "type" -> "array".asJson,
          "items" -> Json.obj("type" -> "string".asJson),
          "description" -> "Task IDs that must complete first".asJson
        ),
        "removeBlocks" -> Json.obj(
          "type" -> "array".asJson,
          "items" -> Json.obj("type" -> "string".asJson),
          "description" -> "Task IDs to remove from blocks".asJson
        ),
        "removeBlockedBy" -> Json.obj(
          "type" -> "array".asJson,
          "items" -> Json.obj("type" -> "string".asJson),
          "description" -> "Task IDs to remove from blockedBy".asJson
        )
      ),
      "required" -> Json.arr("taskId".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val id = input("taskId").flatMap(_.asString).getOrElse("?")
    val status = input("status").flatMap(_.asString).getOrElse("")
    s"TaskUpdate(#$id${if status.nonEmpty then s", $status" else ""})"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 120 then result.take(117) + "..." else result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    (ctx.taskStore, ctx.sessionId) match
      case (Some(store), Some(sessionId)) =>
        val taskId = input("taskId").flatMap(_.asString).getOrElse("")
        val updates = TaskUpdateInput(
          subject = input("subject").flatMap(_.asString),
          description = input("description").flatMap(_.asString),
          activeForm = input("activeForm").flatMap(_.asString),
          status = input("status").flatMap(_.asString).flatMap {
            case "pending" => Some(TaskStatus.Pending)
            case "in_progress" => Some(TaskStatus.InProgress)
            case "completed" => Some(TaskStatus.Completed)
            case "failed" => Some(TaskStatus.Failed)
            case _ => None
          },
          addBlocks = input("addBlocks").flatMap(_.as[List[String]].toOption),
          addBlockedBy = input("addBlockedBy").flatMap(_.as[List[String]].toOption),
          removeBlocks = input("removeBlocks").flatMap(_.as[List[String]].toOption),
          removeBlockedBy = input("removeBlockedBy").flatMap(_.as[List[String]].toOption)
        )
        store
          .update(sessionId, taskId, updates)
          .flatMap {
            case Some(updated) =>
              TaskToolHelper
                .emitTaskListUpdate(store, sessionId, ctx)
                .as(Right(s"Task #${updated.id} updated: ${updated.status}"))
            case None => IO.pure(Left(ToolError(s"Task #$taskId not found")))
          }
          .handleErrorWith {
            case e: IllegalStateException => IO.pure(Left(ToolError(e.getMessage)))
            case e => IO.raiseError(e)
          }
      case (None, _) => IO.pure(Left(ToolError("No task store available")))
      case (_, None) => IO.pure(Left(ToolError("No session ID available")))

end TaskUpdateTool
