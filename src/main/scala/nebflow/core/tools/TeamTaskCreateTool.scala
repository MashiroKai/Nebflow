package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.task.*

/**
 * Team task tool — create. 任务工具重做 (2026-08-30): injected into EVERY team
 * member (progress-display semantics); the team name is
 * taken from ToolContext.teamName (team-agent spawn injection) — there is NO
 * teamName parameter, so a tool call can never write to another team (no
 * cross-team write surface, spec §2.2). Non-team contexts are hard-rejected.
 *
 * Created tasks start `pending`; blockedBy declared at creation is applied via
 * a follow-up dependency update (cycle-checked by the store, same as TaskUpdate).
 */
object TeamTaskCreateTool extends Tool:
  val name = "TeamTaskCreate"

  val description =
    """Create a team task — the team's work list, owned and controlled by the Manager.
## When to Use
- The Manager plans work for the team: create one task per piece of work (subject in imperative form, description with what/how).
- Track multi-step team work — the team panel (frontend) shows these tasks live to the user.

## Parameters
- **subject**: Brief, actionable title in imperative form (e.g. "Fix focus chain memory leak")
- **description**: What needs to be done (e.g. "Identify the retain cycle in ReminderListView and add a regression test")
- **activeForm** (optional): Present continuous form shown in the panel (e.g. "Fixing focus chain memory leak")
- **assignee** (optional): The member responsible for this task — group key for the panel's team→member→task layout. Defaults to YOU (the caller) when omitted; a Manager assigns work to a member by passing their name.
- **blockedBy** (optional): IDs of team tasks that must complete BEFORE this one can start (declared dependency at creation; cycle-detected)

## Semantics
- The task starts with status `pending`. Use TeamTaskUpdate to advance it.
- Team name is inferred from your context (you belong to exactly one team) — no cross-team writes possible.
- Returns the new task ID, e.g. `Task created: Fix focus chain memory leak (ID: 7)`.
- Changes are broadcast to the team panel via `teamTaskListUpdate`.
- Tasks auto-expire: completed/failed clear after 6h, pending/in_progress after 2d — create only tasks that will be worked within that horizon."""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "subject" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Brief, actionable title in imperative form".asJson
        ),
        "description" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "What needs to be done".asJson
        ),
        "activeForm" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Present continuous form shown in the panel".asJson
        ),
        "assignee" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Member responsible for this task (team→member→task group key). Defaults to you (the caller) when omitted".asJson
        ),
        "blockedBy" -> Json.obj(
          "type" -> "array".asJson,
          "items" -> Json.obj("type" -> "string".asJson),
          "description" -> "Team task IDs that must complete before this one can start".asJson
        )
      ),
      "required" -> Json.arr("subject".asJson, "description".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val subject = input("subject").flatMap(_.asString).getOrElse("")
    s"TeamTaskCreate($subject)"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 120 then result.take(117) + "..." else result

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
            // Member attribution (作者规格④ team→成员→任务): explicit
            // `assignee` wins; default = the caller itself (progress-display
            // semantics — a member creates and owns its own task; a Manager
            // assigns by passing the target member's name).
            val assignee = input("assignee")
              .flatMap(_.asString)
              .map(_.trim)
              .filter(_.nonEmpty)
              .orElse(ctx.agentDef.map(_.name).filter(_.nonEmpty))
            val createInput = TaskCreateInput(
              subject = input("subject").flatMap(_.asString).getOrElse(""),
              description = input("description").flatMap(_.asString).getOrElse(""),
              activeForm = input("activeForm").flatMap(_.asString),
              assignee = assignee
            )
            val blockedBy = input("blockedBy").flatMap(_.as[List[String]].toOption).getOrElse(Nil)
            for
              id <- store.create(scopeKey, createInput)
              _ <-
                if blockedBy.nonEmpty then
                  store
                    .update(scopeKey, id, TaskUpdateInput(addBlockedBy = Some(blockedBy)))
                    .handleErrorWith {
                      case e: IllegalStateException =>
                        IO.raiseError(new IllegalStateException(
                          s"Task #$id created, but dependency update failed: ${e.getMessage} (use TeamTaskUpdate to retry the dependencies)"
                        ))
                      case e => IO.raiseError(e)
                    }
                else IO.unit
              _ <- TaskToolHelper.emitTeamTaskListUpdate(store, teamName, ctx)
            yield Right(s"Task created: ${createInput.subject} (ID: $id)")

end TeamTaskCreateTool
