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
