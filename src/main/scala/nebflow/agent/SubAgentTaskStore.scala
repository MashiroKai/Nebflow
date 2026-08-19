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

  /**
   * 并发写锁（2026-08-18 11:08 重放实证）：多个 Delegate 并发 spawn 时
   * recordTask/updateStatus 同时 load→modify→save 同一文件——读-改-写竞态
   * 导致任务记录互相覆盖甚至被半写状态解析失败后写空（[]）。load-modify-save
   * 必须整体持锁串行化。ReentrantLock + IO.blocking：JVM 内进程级互斥，
   * 无创建点改动（SharedResources 默认参数 + 测试共用同一构造签名）。
   */
  private val lock = new java.util.concurrent.locks.ReentrantLock()

  private def withLock[A](body: => A): IO[A] = IO.blocking {
    lock.lock()
    try body
    finally lock.unlock()
  }

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

  private def ensureBaseDirSync(): Unit =
    if !os.exists(baseDir) then os.makeDir.all(baseDir)

  private def readTasksSync(parentSessionId: String): List[SubAgentTask] =
    val f = sessionFile(parentSessionId)
    if !os.exists(f) then Nil
    else
      decode[List[SubAgentTask]](os.read(f)) match
        case Right(list) => list
        case Left(err) =>
          logger.warnSync(s"Failed to parse subagent tasks for $parentSessionId: ${err.getMessage}")
          Nil

  private def writeTasksSync(parentSessionId: String, tasks: List[SubAgentTask]): Unit =
    ensureBaseDirSync()
    os.write.over(sessionFile(parentSessionId), tasks.asJson.noSpaces)

  def loadTasks(parentSessionId: String): IO[List[SubAgentTask]] =
    withLock(readTasksSync(parentSessionId))

  def saveTasks(parentSessionId: String, tasks: List[SubAgentTask]): IO[Unit] =
    withLock(writeTasksSync(parentSessionId, tasks))

  /** Record a new task at spawn time. */
  def recordTask(task: SubAgentTask): IO[Unit] =
    withLock {
      writeTasksSync(task.parentSessionId, readTasksSync(task.parentSessionId) :+ task)
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
    withLock {
      val updated = readTasksSync(parentSessionId).map { t =>
        if t.taskId == taskId then
          t.copy(
            status = status,
            retryCount = retryCount.getOrElse(t.retryCount),
            lastError = lastError.orElse(t.lastError),
            completedAt = completedAt.orElse(t.completedAt)
          )
        else t
      }
      writeTasksSync(parentSessionId, updated)
    }

  /** Remove a task (called after successful completion + grace period). */
  def removeTask(parentSessionId: String, taskId: String): IO[Unit] =
    withLock {
      writeTasksSync(parentSessionId, readTasksSync(parentSessionId).filterNot(_.taskId == taskId))
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

  /**
   * AgentControl（spec §2 status 命令）：按 taskId 全目录反查任务记录——registry
   * 无记录时检测孤儿任务（task status=running 但 actor 已不存在），也用于
   * restart 前区分 ephemeral Delegate（有任务记录）与 persistent（无记录）。
   * 文件数 = 活跃父会话数（量小），线性扫描可接受。
   */
  def findByTaskId(taskId: String): IO[Option[SubAgentTask]] =
    IO
      .blocking {
        if !os.exists(baseDir) then None
        else
          os.list(baseDir)
            .flatMap { f =>
              if f.toString.endsWith(".json") then
                decode[List[SubAgentTask]](os.read(f)) match
                  case Right(list) => list.filter(_.taskId == taskId)
                  case Left(_) => Nil
              else Nil
            }
            .headOption
      }
      .handleErrorWith(e => logger.warn(s"findByTaskId failed: ${e.getMessage}").as(None))

end SubAgentTaskStore
