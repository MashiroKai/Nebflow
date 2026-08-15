package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.task.{Task, TaskArchive}

import java.time.{Instant, LocalDate, ZoneId}

/**
 * C2 TaskQuery — agent-side retrieval across the task archive.
 *
 * scope:
 *  - session (default): current session's tasks (all statuses) straight from
 *    the store — keyword matches subject AND description
 *  - recent: across ALL sessions via the archive index, filtered by `since`
 *    (matches completedAt OR createdAt), newest first — keyword matches
 *    subject (description is not carried in the index to keep it small)
 *  - project: all tasks of one project folder via the index (match folder
 *    name or folderId)
 *
 * Graceful degradation (sub-agents, missing index): recent/project fall
 * back to a session-scoped result with a notice line — never an error.
 */
object TaskQueryTool extends Tool:
  val name = "TaskQuery"

  val description =
    """Search the task archive: past and current tasks, their statuses, completion times and notes.

## When to Use
- Recall what was done before (scope=recent, since=7d) — e.g. before re-planning work
- Find tasks of one project (scope=project, project=<folder name>)
- Review this session's own tasks incl. completed (scope=session; the system
  prompt list only shows active tasks — this shows everything)

## Parameters
- scope: "session" (default) | "recent" | "project"
- since: only for recent/project — "today" | "yesterday" | "7d" | "30d" | ISO date (2026-08-15)
- status: filter by exact status: completed | failed | in_progress | pending | dismissed
- keyword: case-insensitive substring match against subject (+ description in session scope)
- project: folder name (or folderId) — required for scope=project
- limit: max results, default 30

## Examples
Recent week's finished work:  {"scope": "recent", "since": "7d", "status": "completed"}
One project's tasks:          {"scope": "project", "project": "nebflow"}
This session, all:            {"scope": "session"}
Find by keyword:              {"keyword": "registry", "scope": "session"}"""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "scope" -> Json.obj(
          "type" -> "string".asJson,
          "enum" -> Json.arr("session".asJson, "recent".asJson, "project".asJson),
          "description" -> "session (default) | recent (all sessions, needs since) | project (one folder)".asJson
        ),
        "since" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> """Time lower bound for recent/project: "today" | "yesterday" | "7d" | "30d" | ISO date.""".asJson
        ),
        "status" -> Json.obj(
          "type" -> "string".asJson,
          "enum" -> Json.arr("pending".asJson, "in_progress".asJson, "completed".asJson, "failed".asJson, "dismissed".asJson),
          "description" -> "Exact status filter".asJson
        ),
        "keyword" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Substring match on subject (+ description in session scope)".asJson
        ),
        "project" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Project folder name or folderId (scope=project)".asJson
        ),
        "limit" -> Json.obj(
          "type" -> "integer".asJson,
          "description" -> "Max results (default 30)".asJson
        )
      )
    )
  )

  def summarize(input: JsonObject): String =
    val scope = input("scope").flatMap(_.asString).getOrElse("session")
    s"TaskQuery($scope)"

  def summarizeResult(input: JsonObject, result: String): String =
    result.linesIterator.take(3).mkString(" / ").take(120)

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    (ctx.taskStore, ctx.sessionId) match
      case (Some(store), Some(sessionId)) =>
        val scope = input("scope").flatMap(_.asString).getOrElse("session")
        val statusFilter = input("status").flatMap(_.asString)
        val keyword = input("keyword").flatMap(_.asString).map(_.toLowerCase).filter(_.nonEmpty)
        val projectOpt = input("project").flatMap(_.asString).filter(_.nonEmpty)
        val limit = input("limit").flatMap(_.as[Int].toOption).getOrElse(30).max(1).min(200)
        val sinceOpt = input("since").flatMap(_.asString).flatMap(parseSince)

        scope match
          case "session" =>
            store.list(sessionId).map { tasks =>
              val filtered = tasks
                .filter(t => statusFilter.forall(_ == t.status.toString.toLowerCase))
                .filter(t => keyword.forall(k =>
                  t.subject.toLowerCase.contains(k) || t.description.toLowerCase.contains(k)))
              Right(render(toEntries(filtered, sessionId), limit, s"session $sessionId"))
            }

          case "recent" =>
            TaskArchive.loadIndex().flatMap {
              case Nil =>
                degradedSessionView(store, sessionId, statusFilter, keyword, limit)
              case entries =>
                val filtered = entries
                  .filter(e => statusFilter.forall(_ == e.status))
                  .filter(e => keyword.forall(k => e.subject.toLowerCase.contains(k)))
                  .filter(e => sinceCutoff(e, sinceOpt))
                  .sortBy(e => e.completedAt.orElse(e.updatedAt).getOrElse(""))(Ordering[String].reverse)
                IO.pure(Right(render(filtered, limit, "recent" + sinceOpt.map(ms => s" since ${Instant.ofEpochMilli(ms)}").getOrElse(""))))
            }

          case "project" =>
            projectOpt match
              case None =>
                IO.pure(Right("Missing required parameter for scope=project: project"))
              case Some(project) =>
                TaskArchive.loadIndex().flatMap {
                  case Nil =>
                    degradedSessionView(store, sessionId, statusFilter, keyword, limit)
                  case entries =>
                    val filtered = entries
                      .filter(e => e.folderName.contains(project) || e.folderId.contains(project))
                      .filter(e => statusFilter.forall(_ == e.status))
                      .filter(e => keyword.forall(k => e.subject.toLowerCase.contains(k)))
                      .filter(e => sinceCutoff(e, sinceOpt))
                      .sortBy(e => e.completedAt.orElse(e.updatedAt).getOrElse(""))(Ordering[String].reverse)
                    IO.pure(Right(render(filtered, limit, s"project '$project'")))
                }

          case other =>
            IO.pure(Right(s"Unknown scope: $other (use session | recent | project)"))
      case (None, _) => IO.pure(Left(ToolError("No task store available")))
      case (_, None) => IO.pure(Left(ToolError("No session ID available")))

  private def degradedSessionView(store: nebflow.core.task.TaskStore, sessionId: String,
                                  statusFilter: Option[String], keyword: Option[String], limit: Int): IO[Either[ToolError, String]] =
    store.list(sessionId).map { tasks =>
      val filtered = tasks
        .filter(t => statusFilter.forall(_ == t.status.toString.toLowerCase))
        .filter(t => keyword.forall(k =>
          t.subject.toLowerCase.contains(k) || t.description.toLowerCase.contains(k)))
      Right("[archive index unavailable — showing current session only]\n" +
        render(toEntries(filtered, sessionId), limit, s"session $sessionId"))
    }

  private def toEntries(tasks: List[Task], sessionId: String): List[TaskArchive.IndexEntry] =
    tasks.map(t => TaskArchive.IndexEntry(
      sessionId = sessionId,
      folderId = None,
      folderName = None,
      sessionName = None,
      taskId = t.id,
      subject = t.subject,
      status = t.status.toString.toLowerCase,
      createdAt = t.createdAt,
      updatedAt = t.updatedAt,
      completedAt = t.completedAt,
      noteCount = t.notes.size,
      hasLinks = t.notes.exists(_.links.nonEmpty)
    ))

  /** since matches completedAt OR createdAt (whichever exists). */
  private def sinceCutoff(e: TaskArchive.IndexEntry, since: Option[Long]): Boolean =
    since.forall { cutoff =>
      val anchor = e.completedAt.orElse(e.createdAt).getOrElse("")
      scala.util.Try(Instant.parse(anchor).toEpochMilli).toOption.exists(_ >= cutoff)
    }

  private def render(entries: List[TaskArchive.IndexEntry], limit: Int, header: String): String =
    val shown = entries.take(limit)
    if shown.isEmpty then s"No tasks found ($header)."
    else
      val lines = shown.map { e =>
        val when = e.completedAt.getOrElse(e.updatedAt.getOrElse(e.createdAt.getOrElse("")))
        val day = if when.length >= 10 then when.take(10) else when
        val notes = if e.noteCount > 0 then s" [${e.noteCount} note${if e.noteCount > 1 then "s" else ""}]" else ""
        val proj = e.folderName.fold("")(f => s" ($f)")
        s"#${e.taskId} [${e.status}] ${e.subject}$notes — $day$proj"
      }
      s"Tasks ($header, ${shown.size} shown):\n" + lines.mkString("\n")

  /** Parse since lexemes → epoch millis cutoff. */
  def parseSince(s: String): Option[Long] =
    val zone = ZoneId.systemDefault()
    val now = System.currentTimeMillis()
    val lower = s.trim.toLowerCase
    if lower == "today" then
      Some(LocalDate.now(zone).atStartOfDay(zone).toInstant.toEpochMilli)
    else if lower == "yesterday" then
      Some(LocalDate.now(zone).minusDays(1).atStartOfDay(zone).toInstant.toEpochMilli)
    else if lower.endsWith("d") then
      lower.stripSuffix("d").toIntOption.map(n => now - n.toLong * 86400000L)
    else
      scala.util.Try(LocalDate.parse(s).atStartOfDay(zone).toInstant.toEpochMilli)
        .orElse(scala.util.Try(Instant.parse(s).toEpochMilli))
        .toOption

end TaskQueryTool
