package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.task.*

/**
 * Team Manager task tool — list (spec 20260825_team-manager-task-tool-spec.md
 * §2.1/§2.3). READ-ONLY by hard constraint: no mutation parameters exist on
 * this tool at all. Available to the team's Manager (own team by default) and
 * to Nebula (read-only oversight, spec §3 — passes `team` explicitly since it
 * has no team context). All statuses are listed (pending/in_progress/
 * completed/failed) with dependencies and note counts.
 */
object TeamTaskListTool extends Tool:
  val name = "TeamTaskList"

  val description =
    """List team tasks — read-only view of a team's work list (no mutation parameters).
## When to Use
- Review the team's progress display: what is pending, in progress, completed, failed.
- Check dependencies (blockedBy/blocks) before dispatching new work.

## Parameters
- **team** (optional): Team name to list. Omit to list YOUR OWN team (your team is inferred from your team context).
- **status** (optional): Filter by exact status: "pending" | "in_progress" | "completed" | "failed". Omit to list all.

## Output
One line per task: `#<id> [<status>] <subject> — <updated day> · dep <blockedBy ids> · blocks <ids> · <n> note(s)`.
This tool is strictly read-only — team task changes happen via TeamTaskCreate/TeamTaskUpdate."""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "team" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Team name to list (omit to list your own team)".asJson
        ),
        "status" -> Json.obj(
          "type" -> "string".asJson,
          "enum" -> Json.arr("pending".asJson, "in_progress".asJson, "completed".asJson, "failed".asJson),
          "description" -> "Exact status filter (omit to list all)".asJson
        )
      )
    )
  )

  def summarize(input: JsonObject): String =
    val team = input("team").flatMap(_.asString).getOrElse("<own>")
    val status = input("status").flatMap(_.asString).getOrElse("all")
    s"TeamTaskList($team, $status)"

  def summarizeResult(input: JsonObject, result: String): String =
    result.linesIterator.take(3).mkString(" / ").take(120)

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    // Team resolution: explicit `team` param wins; falls back to the caller's
    // own team context (Manager). Neither → hard error (Nebula must name a team).
    val explicitTeam = input("team").flatMap(_.asString).filter(_.nonEmpty)
    val teamName = explicitTeam.orElse(ctx.teamName)
    teamName match
      case None =>
        IO.pure(Left(ToolError("TeamTaskList requires a team name: pass `team` (or call it from within a team)")))
      case Some(name) if !TaskStore.isSafeTeamName(name) =>
        IO.pure(Left(ToolError(s"Invalid team name: $name")))
      case Some(name) =>
        ctx.taskStore match
          case None => IO.pure(Left(ToolError("No task store available")))
          case Some(store) =>
            val statusFilter = input("status").flatMap(_.asString)
            val scopeKey = TaskStore.teamScopeKey(name)
            store.list(scopeKey).map { tasks =>
              val filtered = statusFilter match
                case Some(s) => tasks.filter(t => TaskStatus.wireName(t.status) == s)
                case None    => tasks
              Right(render(name, filtered))
            }

  private def render(teamName: String, tasks: List[Task]): String =
    if tasks.isEmpty then s"No team tasks for '$teamName'."
    else
      val lines = tasks.map { t =>
        val day = t.updatedAt.filter(_.length >= 10).map(_.take(10)).getOrElse("")
        val deps =
          val b = if t.blockedBy.nonEmpty then s" · dep ${t.blockedBy.map(id => s"#$id").mkString(", ")}" else ""
          val k = if t.blocks.nonEmpty then s" · blocks ${t.blocks.map(id => s"#$id").mkString(", ")}" else ""
          b + k
        val notes = if t.notes.nonEmpty then s" · ${t.notes.size} note${if t.notes.size > 1 then "s" else ""}" else ""
        s"#${t.id} [${TaskStatus.wireName(t.status)}] ${t.subject} — $day$deps$notes"
      }
      s"Team tasks for '$teamName' (${tasks.size}):\n" + lines.mkString("\n")

end TeamTaskListTool
