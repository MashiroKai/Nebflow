package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Codec, Decoder, Encoder}
import io.circe.parser.decode
import nebflow.core.NebflowLogger
import nebflow.core.PathUtil

/**
 * Persisted metadata for a sub-agent task (Delegate / SubTask).
 *
 * Written at spawn time, updated on completion/failure, used for:
 *  - Crash recovery: if the process restarts, tasks in `Running` status
 *    can be resumed or their parent agent notified.
 *  - Observability: the frontend can show sub-agent task status.
 *  - Auto-retry: the parent agent can look up the original prompt to
 *    re-delegate after a retryable failure.
 */
case class SubAgentTask(
  taskId: String, // = subagentId (delegate-xxx / subtask-xxx)
  parentSessionId: String,
  agentName: String,
  prompt: String,
  description: String,
  status: String, // "running" | "completed" | "failed" | "restarting"
  retryCount: Int, // how many times restarted by supervisor
  spawnedAt: Long, // epoch millis
  completedAt: Option[Long], // epoch millis
  lastError: Option[String], // last error message if failed
  source: String // "delegate" | "subtask"
)

object SubAgentTask:
  given Encoder[SubAgentTask] = Encoder.derived
  given Decoder[SubAgentTask] = Decoder.derived

/**
 * File-based store for SubAgentTask records.
 *
 * Layout: `~/.nebflow/subagent-tasks/<parentSessionId>.json` — one file per
 * parent session, containing a list of tasks. Completed/failed tasks are
 * retained for a short period for audit, then pruned.
 */
class SubAgentTaskStore(baseDir: os.Path):

  private val logger = NebflowLogger.forName("nebflow.subagent-task.store")

  private def sessionFile(parentSessionId: String): os.Path =
    // Guard: a blank parentSessionId (caller bug — e.g. an unsupervised
    // context with no session) must not silently write to ".json" where it
    // pollutes the directory and is never read back. Callers already wrap
    // store operations in handleErrorWith, so this degrades to a warn.
    if parentSessionId.isBlank then
      throw new IllegalArgumentException(
        s"subAgentTaskStore: blank parentSessionId refused (would write ${baseDir}/.json)"
      )
    baseDir / s"$parentSessionId.json"

  private def ensureBaseDir: IO[Unit] = IO.blocking {
    if !os.exists(baseDir) then os.makeDir.all(baseDir)
  }

  def loadTasks(parentSessionId: String): IO[List[SubAgentTask]] = IO.blocking {
    val f = sessionFile(parentSessionId)
    if !os.exists(f) then Nil
    else
      decode[List[SubAgentTask]](os.read(f)) match
        case Right(list) => list
        case Left(err) =>
          logger.warnSync(s"Failed to parse subagent tasks for $parentSessionId: ${err.getMessage}")
          Nil
  }

  def saveTasks(parentSessionId: String, tasks: List[SubAgentTask]): IO[Unit] =
    ensureBaseDir *> IO.blocking {
      os.write.over(sessionFile(parentSessionId), tasks.asJson.noSpaces)
    }

  /** Record a new task at spawn time. */
  def recordTask(task: SubAgentTask): IO[Unit] =
    loadTasks(task.parentSessionId).flatMap { existing =>
      saveTasks(task.parentSessionId, existing :+ task)
    }

  /** Update a task's status. */
  def updateStatus(
    parentSessionId: String,
    taskId: String,
    status: String,
    retryCount: Option[Int] = None,
    lastError: Option[String] = None,
    completedAt: Option[Long] = None
  ): IO[Unit] =
    loadTasks(parentSessionId).flatMap { existing =>
      val updated = existing.map { t =>
        if t.taskId == taskId then
          t.copy(
            status = status,
            retryCount = retryCount.getOrElse(t.retryCount),
            lastError = lastError.orElse(t.lastError),
            completedAt = completedAt.orElse(t.completedAt)
          )
        else t
      }
      saveTasks(parentSessionId, updated)
    }

  /** Remove a task (called after successful completion + grace period). */
  def removeTask(parentSessionId: String, taskId: String): IO[Unit] =
    loadTasks(parentSessionId).flatMap { existing =>
      saveTasks(parentSessionId, existing.filterNot(_.taskId == taskId))
    }

  /** Find all tasks in "running" status (for startup recovery). */
  def findRunningTasks: IO[List[SubAgentTask]] = IO
    .blocking {
      if !os.exists(baseDir) then Nil
      else
        os.list(baseDir)
          .flatMap { f =>
            if f.toString.endsWith(".json") then
              decode[List[SubAgentTask]](os.read(f)) match
                case Right(list) => list.filter(_.status == "running")
                case Left(_) => Nil
            else Nil
          }
          .toList
    }
    .handleErrorWith(e => logger.warn(s"findRunningTasks failed: ${e.getMessage}").as(Nil))

end SubAgentTaskStore
