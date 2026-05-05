package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import nebflow.core.task.TaskStore

/** Shared helper for task-related tools to emit taskListUpdate events. */
private[tools] object TaskToolHelper:

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
        }
      case None => IO.unit

end TaskToolHelper
