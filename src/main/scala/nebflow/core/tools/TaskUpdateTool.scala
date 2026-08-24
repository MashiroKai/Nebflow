package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.task.*

object TaskUpdateTool extends Tool:
  val name = "TaskUpdate"

  val description =
    """Update a task in the task list.

## When to Use

**Finishing a task (IMPORTANT — completion needs user confirmation):**
- When you FULLY finish a task, set status to `needs_confirmation` (NOT `completed`) and attach a `note` summarizing the outcome
- `completed` is RESERVED for the user: they confirm via the circle in the todos panel — you may NOT set it directly (the backend rejects agent → completed)
- After confirming, check your task list in the system prompt for the next task
- If you encounter errors, blockers, or cannot finish, mark as failed
- Never mark needs_confirmation if tests are failing, implementation is partial, or you encountered unresolved errors
- While a task is `needs_confirmation` (awaiting user ruling), the user either confirms it (panel circle → completed) or returns it. If the user revises it via DIALOGUE feedback (no panel click), take it back yourself: set status back to `in_progress` AND attach a `note` quoting/describing the user feedback — the backend REQUIRES the note on this transition (anti-abuse). Then revise per the feedback and re-mark `needs_confirmation` when done.
- A [打回任务] block in your input means the user returned it via the PANEL — it is already back in `in_progress`; revise per the block and re-mark needs_confirmation

**Update task details or dependencies:**
- Set status to `in_progress` when starting work on a task
- Use `addBlockedBy`/`removeBlockedBy` to manage dependencies

**Record outcomes (notes):**
- When marking `needs_confirmation` (or `failed`), attach a `note` summarizing what was
  done, key results and where artifacts landed (commit hash / file paths).
  `noteLinks` lists clickable references (file paths, URLs, task IDs).
- Notes are append-only and preserved in the task archive for later retrieval.

## Batch Update

Pass comma-separated task IDs to apply the same update to multiple tasks at once:
{"taskId": "1,2,3", "status": "needs_confirmation"} — marks tasks #1, #2, #3 in one call.
Single-task calls {"taskId": "1", "status": "needs_confirmation"} work exactly as before.

## Status Workflow

`pending` -> `in_progress` -> `needs_confirmation` (done, awaiting user confirmation) or `failed`

- `needs_confirmation` -> `in_progress` is legal when the user returns the task with feedback
  (panel click, or dialogue feedback you take back yourself with a note)

- The user confirms needs_confirmation tasks themselves (todos panel circle) — that moves them to `completed`
- The user may return a needs_confirmation task to you with feedback — panel return goes straight back to `in_progress` (with a [打回任务] block); dialogue feedback you take back yourself via `in_progress` + a note describing the feedback
- Terminal states: `completed` and `failed` cannot transition to any other state.

## Dependency Management

- `addBlockedBy`: Task IDs that must complete before this one can start
- `addBlocks`: Task IDs that this task blocks
- `removeBlockedBy`/`removeBlocks`: Remove dependencies
- Circular dependencies are detected and rejected automatically

## Examples

Mark as in progress:   {"taskId": "1", "status": "in_progress"}
Finished, awaiting user: {"taskId": "1", "status": "needs_confirmation", "note": "Implemented X, tests green, commit abc1234", "noteLinks": ["/tmp/report.md"]}
Mark as failed:        {"taskId": "1", "status": "failed", "note": "blocked by upstream API outage"}
Batch finish:          {"taskId": "1,2,3", "status": "needs_confirmation"}
Set dependency:        {"taskId": "2", "addBlockedBy": ["1"]}
Remove dependency:     {"taskId": "2", "removeBlockedBy": ["1"]}"""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "taskId" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "The ID of the task to update. Comma-separated IDs for batch update (e.g. \"1,2,3\").".asJson
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
          "enum" -> Json.arr(
            "pending".asJson,
            "in_progress".asJson,
            "needs_confirmation".asJson,
            "completed".asJson,
            "failed".asJson
          ),
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
        ),
        "note" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Append a durable outcome note to this task (what was done, results, artifact locations). Recommended whenever marking completed/failed.".asJson
        ),
        "noteLinks" -> Json.obj(
          "type" -> "array".asJson,
          "items" -> Json.obj("type" -> "string".asJson),
          "description" -> "Clickable references attached to the note (file paths, URLs, task IDs)".asJson
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
        val rawTaskId = input("taskId").flatMap(_.asString).getOrElse("")
        val taskIds = rawTaskId.split(",").map(_.trim).filter(_.nonEmpty).toList
        val statusOpt = input("status").flatMap(_.asString).flatMap {
          case "pending" => Some(TaskStatus.Pending)
          case "in_progress" => Some(TaskStatus.InProgress)
          case "needs_confirmation" => Some(TaskStatus.NeedsConfirmation)
          case "completed" => Some(TaskStatus.Completed)
          case "failed" => Some(TaskStatus.Failed)
          case _ => None
        }
        val updates = TaskUpdateInput(
          subject = input("subject").flatMap(_.asString),
          description = input("description").flatMap(_.asString),
          activeForm = input("activeForm").flatMap(_.asString),
          status = statusOpt,
          addBlocks = input("addBlocks").flatMap(_.as[List[String]].toOption),
          addBlockedBy = input("addBlockedBy").flatMap(_.as[List[String]].toOption),
          removeBlocks = input("removeBlocks").flatMap(_.as[List[String]].toOption),
          removeBlockedBy = input("removeBlockedBy").flatMap(_.as[List[String]].toOption),
          note = input("note").flatMap(_.asString),
          noteLinks = input("noteLinks").flatMap(_.as[List[String]].toOption)
        )

        taskIds match
          case Nil =>
            IO.pure(Left(ToolError("Missing required parameter: taskId")))
          case single :: Nil =>
            // Single task — same path as before
            store
              .update(sessionId, single, updates)
              .flatMap {
                case Some(updated) =>
                  TaskToolHelper
                    .emitTaskListUpdate(store, sessionId, ctx)
                    .as(Right(s"${updated.subject} → ${TaskStatus.wireName(updated.status)}"))
                case None => IO.pure(Left(ToolError(s"Task #$single not found")))
              }
              .handleErrorWith {
                case e: IllegalStateException => IO.pure(Left(ToolError(e.getMessage)))
                case e => IO.raiseError(e)
              }
          case multiple =>
            // Batch update — apply same updates to each task
            multiple
              .traverse(id => store.update(sessionId, id, updates).attempt.map(id -> _))
              .flatMap { results =>
                val successCount = results.count { case (_, Right(Some(_))) => true; case _ => false }
                val notFoundIds = results.collect { case (id, Right(None)) => id }
                val errorIds = results.collect {
                  case (id, Left(e: IllegalStateException)) => s"$id (${e.getMessage})"
                  case (id, Left(e)) => s"$id (${e.getClass.getSimpleName})"
                }
                TaskToolHelper.emitTaskListUpdate(store, sessionId, ctx).as {
                  val statusStr = statusOpt.map(s => s" → ${TaskStatus.wireName(s)}").getOrElse("")
                  (successCount, notFoundIds, errorIds) match
                    case (0, _, errs) if errs.nonEmpty =>
                      Left(ToolError(s"All ${multiple.size} tasks failed. Errors: ${errs.mkString("; ")}"))
                    case (0, nf, Nil) =>
                      Left(ToolError(s"Tasks not found: ${nf.mkString(", ")}"))
                    case (succ, nf, errs) =>
                      val parts = List(
                        Some(s"Updated $succ task${if succ > 1 then "s" else ""}$statusStr"),
                        if nf.nonEmpty then Some(s"not found: ${nf.mkString(", ")}") else None,
                        if errs.nonEmpty then Some(s"errors: ${errs.mkString("; ")}") else None
                      ).flatten
                      Right(parts.mkString(". "))
                }
              }
        end match
      case (None, _) => IO.pure(Left(ToolError("No task store available")))
      case (_, None) => IO.pure(Left(ToolError("No session ID available")))

end TaskUpdateTool
