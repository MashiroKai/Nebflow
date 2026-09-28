package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.task.*

/**
 * Team task tool — update. 任务工具重做 (2026-08-30): injected into every team
 * member (progress-display).
 * §2.3). Four-state team matrix (pending → in_progress → completed / failed;
 * terminal states are no-op only) — the Manager is the owner and sets
 * `completed` directly (no user-confirmation lane; C15 constrains only the
 * session domain). Block dependencies reuse the session-domain semantics
 * (blocks/blockedBy + DFS cycle detection, same-team set). No batch update —
 * one task per call (the team matrix is small and the Manager is the only
 * caller).
 */
object TeamTaskUpdateTool extends Tool:
  val name = "TeamTaskUpdate"

  val description =
    """Update a team task — status, dependencies, details, or notes (任务工具重做 2026-08-30: any team member).
## When to Use
- Advance team work: mark `in_progress` when a member starts, `completed` when done (attach a `note` summarizing the outcome + artifact locations), `failed` when blocked/aborted (attach a `note` with the reason).
- Manage dependencies: `addBlockedBy` = task IDs that must finish before this one; `addBlocks` = task IDs this one blocks; `removeBlockedBy`/`removeBlocks` undo them. Circular dependencies are detected and rejected.
- Revise subject/description/activeForm.

## Status Workflow (four-state team matrix)
`pending` -> `in_progress` -> `completed` or `failed` (terminal)
- `pending` may go directly to `completed` or `failed`.
- Terminal states (`completed`/`failed`) cannot transition to any other state; re-open a failed task by creating a new one.
- No needs_confirmation / dismissed / cancelled in the team domain.
- Work through your tasks in order; when a task is fully done mark it `completed` (attach a note with the outcome), if it cannot be finished mark it `failed` (attach a note explaining why). Tasks marked [waiting-user] are human todos — never part of your own work loop.
- Tasks auto-expire: completed/failed clear after 6h, pending/in_progress after 2d — keep the list current.

## Parameters
- **taskId**: The team task ID to update.
- **status**: "pending" | "in_progress" | "completed" | "failed"
- **addBlocks / addBlockedBy / removeBlocks / removeBlockedBy**: dependency changes (arrays of team task IDs)
- **subject / description / activeForm**: detail edits
- **assignee**: Reassign the responsible member (the panel's team→member→task group key). A member name sets it; an empty string clears it.
- **note**: Append a durable outcome note (what was done, key results, commit/file paths). Recommended whenever marking completed/failed.
- **noteLinks**: Clickable references attached to the note (file paths, URLs, task IDs).

## Examples
Start work:      {"taskId": "7", "status": "in_progress"}
Done:            {"taskId": "7", "status": "completed", "note": "Fix merged, commit abc1234", "noteLinks": ["/tmp/report.md"]}
Blocked:         {"taskId": "6", "status": "failed", "note": "blocked by upstream API outage"}
Set dependency:  {"taskId": "6", "addBlockedBy": ["7"]}"""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "taskId" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "The ID of the team task to update".asJson
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
            "completed".asJson,
            "failed".asJson
          ),
          "description" -> "New status (team four-state matrix)".asJson
        ),
        "addBlocks" -> Json.obj(
          "type" -> "array".asJson,
          "items" -> Json.obj("type" -> "string".asJson),
          "description" -> "Team task IDs this task blocks".asJson
        ),
        "addBlockedBy" -> Json.obj(
          "type" -> "array".asJson,
          "items" -> Json.obj("type" -> "string".asJson),
          "description" -> "Team task IDs that must complete first".asJson
        ),
        "removeBlocks" -> Json.obj(
          "type" -> "array".asJson,
          "items" -> Json.obj("type" -> "string".asJson),
          "description" -> "Team task IDs to remove from blocks".asJson
        ),
        "removeBlockedBy" -> Json.obj(
          "type" -> "array".asJson,
          "items" -> Json.obj("type" -> "string".asJson),
          "description" -> "Team task IDs to remove from blockedBy".asJson
        ),
        "note" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Append a durable outcome note (recommended when marking completed/failed)".asJson
        ),
        "noteLinks" -> Json.obj(
          "type" -> "array".asJson,
          "items" -> Json.obj("type" -> "string".asJson),
          "description" -> "Clickable references attached to the note (file paths, URLs, task IDs)".asJson
        ),
        "assignee" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Reassign the responsible member (team→member→task group key); empty string clears it".asJson
        )
      ),
      "required" -> Json.arr("taskId".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val id = input("taskId").flatMap(_.asString).getOrElse("?")
    val status = input("status").flatMap(_.asString).getOrElse("")
    s"TeamTaskUpdate(#$id${if status.nonEmpty then s", $status" else ""})"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 120 then result.take(117) + "..." else result

  private val TeamStatuses = Map(
    "pending" -> TaskStatus.Pending,
    "in_progress" -> TaskStatus.InProgress,
    "completed" -> TaskStatus.Completed,
    "failed" -> TaskStatus.Failed
  )

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    ctx.teamName match
      case None =>
        IO.pure(Left(ToolError("Team task tools are only available to team agents")))
      case Some(teamName) if !TaskStore.isSafeTeamName(teamName) =>
        IO.pure(Left(ToolError(s"Invalid team name: $teamName")))
      case Some(teamName) =>
        ctx.taskStore match
          case None => IO.pure(Left(ToolError("No task store available")))
          case Some(store) =>
            val scopeKey = TaskStore.teamScopeKey(teamName)
            val rawTaskId = input("taskId").flatMap(_.asString).getOrElse("").trim
            if rawTaskId.isEmpty then
              IO.pure(Left(ToolError("Missing required parameter: taskId")))
            else
              // Strict status parsing: the team matrix has exactly four states —
              // an unknown string is an error, NOT a silent no-op (TaskUpdate's
              // lenient parse would silently ignore a typo'd status).
              val statusOpt: Either[String, Option[TaskStatus]] = input("status").flatMap(_.asString) match
                case None => Right(None)
                case Some(s) => TeamStatuses.get(s).map(Some(_)).toRight(s"Invalid status for team task: '$s' (use pending | in_progress | completed | failed)")
              statusOpt match
                case Left(err) => IO.pure(Left(ToolError(err)))
                case Right(maybeStatus) =>
                  val updates = TaskUpdateInput(
                    subject = input("subject").flatMap(_.asString),
                    description = input("description").flatMap(_.asString),
                    activeForm = input("activeForm").flatMap(_.asString),
                    status = maybeStatus,
                    addBlocks = input("addBlocks").flatMap(_.as[List[String]].toOption),
                    addBlockedBy = input("addBlockedBy").flatMap(_.as[List[String]].toOption),
                    removeBlocks = input("removeBlocks").flatMap(_.as[List[String]].toOption),
                    removeBlockedBy = input("removeBlockedBy").flatMap(_.as[List[String]].toOption),
                    note = input("note").flatMap(_.asString),
                    noteLinks = input("noteLinks").flatMap(_.as[List[String]].toOption),
                    assignee = input("assignee").flatMap(_.asString)
                  )
                  store
                    .update(scopeKey, rawTaskId, updates)
                    .flatMap {
                      case Some(updated) =>
                        TaskToolHelper
                          .emitTeamTaskListUpdate(store, teamName, ctx)
                          .as(Right(s"${updated.subject} → ${TaskStatus.wireName(updated.status)}"))
                      case None => IO.pure(Left(ToolError(s"Task #$rawTaskId not found")))
                    }
                    .handleErrorWith {
                      case e: IllegalStateException => IO.pure(Left(ToolError(e.getMessage)))
                      case e => IO.raiseError(e)
                    }

end TeamTaskUpdateTool
