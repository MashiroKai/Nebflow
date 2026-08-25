package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import nebflow.core.task.{TaskArchive, TaskStore}

/** Shared helper for task-related tools to emit taskListUpdate events. */
object TaskToolHelper:

  def emitTaskListUpdate(store: TaskStore, sessionId: String, ctx: ToolContext): IO[Unit] =
    ctx.wsSend match
      case Some(send) =>
        store.list(sessionId).flatMap { tasks =>
          val json = io.circe.Json.obj(
            "type" -> "taskListUpdate".asJson,
            "sessionId" -> sessionId.asJson,
            "tasks" -> tasks.asJson
          )
          send(json)
        } *> refreshArchiveIndex(sessionId, ctx)
      case None => refreshArchiveIndex(sessionId, ctx)

  /** Team Manager task panel event (spec §2.5, team-manager-task-tool):
    * `{type: "teamTaskListUpdate", team, tasks}` — NO sessionId; the frontend
    * routes it to the global teams panel (ws.js entry, flowCanvas
    * onTeamTaskListUpdate mutates the in-memory team state and re-renders).
    * Deliberately does NOT refresh the session archive index: team tasks live
    * in their own directory and must never leak into TaskQuery's
    * session/recent/project results (stage-3 may add a team archive). */
  def emitTeamTaskListUpdate(store: TaskStore, teamName: String, ctx: ToolContext): IO[Unit] =
    ctx.wsSend match
      case Some(send) =>
        store.list(TaskStore.teamScopeKey(teamName)).flatMap { tasks =>
          val json = io.circe.Json.obj(
            "type" -> "teamTaskListUpdate".asJson,
            "team" -> teamName.asJson,
            "tasks" -> tasks.asJson
          )
          send(json)
        }
      case None => IO.unit

  /**
    * C2: incremental archive-index refresh after any task mutation, joined
    * with session folder info when a SessionStore is reachable (folderName
    * via the existing synchronous getFolderName lookup, same pattern as the
    * rest of SessionStore). Best-effort — failures are logged inside
    * TaskArchive and never propagate to the tool call.
    */
  private def refreshArchiveIndex(sessionId: String, ctx: ToolContext): IO[Unit] =
    val joinIO: IO[String => TaskArchive.SessionJoin] = ctx.sessionStore match
      case Some(store) =>
        store
          .getSessionMeta(sessionId)
          .map {
            case Some(meta) =>
              val j = TaskArchive.SessionJoin(
                folderId = meta.folderId,
                folderName = meta.folderId.flatMap(store.getFolderName),
                sessionName = Some(meta.name)
              )
              (_: String) => j
            case None => (_: String) => TaskArchive.Unclassified
          }
          .handleErrorWith(_ => IO.pure((_: String) => TaskArchive.Unclassified))
      case None =>
        IO.pure((_: String) => TaskArchive.Unclassified)

    joinIO.flatMap(join => TaskArchive.refreshSession(sessionId, join))

end TaskToolHelper
