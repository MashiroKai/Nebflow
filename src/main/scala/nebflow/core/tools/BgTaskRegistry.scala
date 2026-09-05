package nebflow.core.tools

import cats.effect.{IO, Ref}
import io.circe.syntax.*
import nebflow.core.NebflowLogger

/**
 * Global registry of active background tasks (local Bash + remote).
 * Used by the WS reconnect handler to sync frontend state after disconnects.
 *
 * Tasks are registered when they start and removed when they complete.
 * The frontend queries this on WS reconnect to recover missed completion events.
 */
object BgTaskRegistry:
  private val logger = NebflowLogger.forName("nebflow.bg-registry")

  case class ActiveTask(
    jobId: String,
    sessionId: String,
    description: String,
    startedAtMs: Long,
    kind: String, // "local" | "remote"
    /** 顶层根会话 id（2026-09-05 计数/列表分叉修复）：前端 backgroundTaskUpdate
      * 信封与 activeBgTasks 快照共用本键分桶。空 = 无 agent 上下文（REST 直调等）
      * 或旧调用方未传 → activeTasksJson 回退按 sessionId 分组。 */
    rootSessionId: String = ""
  )

  private val tasks: Ref[IO, Map[String, ActiveTask]] = Ref.unsafe(Map.empty)

  def register(
    jobId: String,
    sessionId: String,
    description: String,
    kind: String,
    rootSessionId: String = ""
  ): IO[Unit] =
    tasks.update(_ + (jobId -> ActiveTask(jobId, sessionId, description, System.currentTimeMillis(), kind, rootSessionId)))

  def unregister(jobId: String): IO[Unit] =
    tasks.update(_ - jobId)

  /** #391 机制 E：移除某 session 的全部任务并返回它们——restart/Stop 联动时
    * 调用方用返回值发 WS backgroundTaskUpdate(status="cancelled") 通知前端
    * 指示器消失（agent 已死，正常的 completed 通知不会到来）。 */
  def unregisterSession(sessionId: Option[String]): IO[List[ActiveTask]] =
    sessionId match
      case None => IO.pure(Nil)
      case Some(sid) =>
        tasks.modify { m =>
          val (removed, kept) = m.partition(_._2.sessionId == sid)
          (kept, removed.values.toList)
        }

  /** Returns active tasks grouped by root session id (rootSessionId, falling
    * back to sessionId when absent), as JSON for the frontend. */
  def activeTasksJson: IO[io.circe.Json] =
    tasks.get
      .map { m =>
        m.values
          .groupBy(t => if t.rootSessionId.nonEmpty then t.rootSessionId else t.sessionId)
          .map { case (sid, taskSet) =>
            sid -> taskSet.map { t =>
              io.circe.Json.obj(
                "taskId" -> t.jobId.asJson,
                "description" -> t.description.asJson,
                "status" -> "running".asJson,
                "startedAt" -> t.startedAtMs.asJson
              )
            }.toList
          }
          .toMap
      }
      .map(_.asJson)
end BgTaskRegistry
