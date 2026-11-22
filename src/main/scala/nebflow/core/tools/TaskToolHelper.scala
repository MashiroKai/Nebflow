package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import nebflow.core.task.TaskStore

/**
 * Shared helper for task-related tools to emit list-update events.
 *
 * 任务工具重做（2026-08-30）：TaskArchive/归档索引退役——事件推送保留，
 * 增量索引刷新删除。列表一律走 listVisible（TTL 过滤后的可见集）。
 */
object TaskToolHelper:

  def emitTaskListUpdate(store: TaskStore, sessionId: String, ctx: ToolContext): IO[Unit] =
    ctx.wsSend match
      case Some(send) =>
        store.listVisible(sessionId).flatMap { tasks =>
          val json = io.circe.Json.obj(
            "type" -> "taskListUpdate".asJson,
            "sessionId" -> sessionId.asJson,
            "tasks" -> tasks.asJson
          )
          send(json)
        }
      case None => IO.unit

end TaskToolHelper
