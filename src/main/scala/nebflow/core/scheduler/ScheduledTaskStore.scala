package nebflow.core.scheduler

import cats.effect.IO
import cats.syntax.all.*
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.core.NebflowLogger

class ScheduledTaskStore(baseDir: os.Path):

  private val logger = NebflowLogger.forName("nebflow.scheduled-task.store")

  private def sessionFile(sessionId: String): os.Path =
    baseDir / s"$sessionId.json"

  private def ensureBaseDir: IO[Unit] = IO.blocking {
    if !os.exists(baseDir) then os.makeDir.all(baseDir)
  }

  def loadTasks(sessionId: String): IO[List[ScheduledTask]] = IO.blocking {
    val f = sessionFile(sessionId)
    if !os.exists(f) then Nil
    else
      decode[List[ScheduledTask]](os.read(f)) match
        case Right(list) => list
        case Left(err) =>
          logger.warnSync(s"Failed to parse scheduled tasks for $sessionId: ${err.getMessage}")
          Nil
  }

  def saveTasks(sessionId: String, tasks: List[ScheduledTask]): IO[Unit] =
    ensureBaseDir *> IO.blocking {
      os.write.over(sessionFile(sessionId), tasks.asJson.noSpaces)
    }

  def addTask(task: ScheduledTask): IO[Unit] =
    loadTasks(task.sessionId).flatMap { existing =>
      saveTasks(task.sessionId, existing :+ task)
    }

  def deleteTask(sessionId: String, taskId: String): IO[Unit] =
    loadTasks(sessionId).flatMap { existing =>
      saveTasks(sessionId, existing.filterNot(_.id == taskId))
    }

  def markTriggered(sessionId: String, taskId: String): IO[Unit] =
    loadTasks(sessionId).flatMap { existing =>
      val updated = existing.map { t =>
        if t.id == taskId then t.copy(triggered = true, triggeredAt = Some(System.currentTimeMillis()))
        else t
      }
      saveTasks(sessionId, updated)
    }

  /** Reschedule a recurring task — bump triggerAt forward, keep triggered=false. */
  def rescheduleTask(sessionId: String, taskId: String, nextTriggerAt: Long): IO[Unit] =
    loadTasks(sessionId).flatMap { existing =>
      val updated = existing.map { t =>
        if t.id == taskId then t.copy(triggered = false, triggeredAt = None, triggerAt = nextTriggerAt)
        else t
      }
      saveTasks(sessionId, updated)
    }

  /** Get all due (untriggered, past triggerAt) tasks across all sessions. */
  def getAllDueTasks: IO[List[ScheduledTask]] = IO.blocking {
    if !os.exists(baseDir) then Nil
    else
      val now = System.currentTimeMillis()
      os.list(baseDir)
        .filter(_.last.endsWith(".json"))
        .toList
        .flatMap { f =>
          decode[List[ScheduledTask]](os.read(f)) match
            case Right(list) => list.filter(t => !t.triggered && t.triggerAt <= now && t.enabled)
            case Left(_) => Nil
        }
        // FIFO: fire in scheduled-time order so simultaneously-due tasks are
        // processed one by one in a deterministic sequence.
        .sortBy(_.triggerAt)
    end if
  }

  /** Get all pending (untriggered) tasks across all sessions — used to compute next fire time. */
  def getAllPendingTasks: IO[List[ScheduledTask]] = IO.blocking {
    if !os.exists(baseDir) then Nil
    else
      os.list(baseDir)
        .filter(_.last.endsWith(".json"))
        .toList
        .flatMap { f =>
          decode[List[ScheduledTask]](os.read(f)) match
            case Right(list) => list.filter(!_.triggered)
            case Left(_) => Nil
        }
    end if
  }

  /** Get pending (untriggered) task count for a session. */
  def getPendingCount(sessionId: String): IO[Int] =
    loadTasks(sessionId).map(_.count(!_.triggered))

  /** Toggle the enabled state of a task. Returns the updated enabled value. */
  def toggleTask(sessionId: String, taskId: String): IO[Boolean] =
    loadTasks(sessionId).flatMap { existing =>
      val updated = existing.map { t =>
        if t.id == taskId then t.copy(enabled = !t.enabled)
        else t
      }
      saveTasks(sessionId, updated) *>
        IO.pure(updated.find(_.id == taskId).map(_.enabled).getOrElse(true))
    }

  /** Find a task by id across ALL session files（2026-09-06 升级）：list 是跨
    * 会话的，cancel 按 id 也必须跨会话——任务可能住在旧会话文件里（重启后
    * re-arm 去重的目标正是它们）。 */
  def findTaskById(taskId: String): IO[Option[ScheduledTask]] = IO.blocking {
    if !os.exists(baseDir) then None
    else
      os.list(baseDir)
        .filter(_.last.endsWith(".json"))
        .toList
        .flatMap(f => decode[List[ScheduledTask]](os.read(f)).getOrElse(Nil))
        .find(_.id == taskId)
  }

  /** Remove every task whose name == given name across ALL session files.
    * Returns the removed tasks. Same-name tasks are global-unique（upsert 语义
    * 的清理半步）；读不了的任务文件原样跳过（与 loadTasks 容错同口径）。 */
  def removeByName(name: String): IO[List[ScheduledTask]] = IO.blocking {
    if !os.exists(baseDir) then Nil
    else
      val removed = List.newBuilder[ScheduledTask]
      os.list(baseDir)
        .filter(_.last.endsWith(".json"))
        .toList
        .foreach { f =>
          decode[List[ScheduledTask]](os.read(f)) match
            case Right(list) =>
              val (drop, keep) = list.partition(_.name.contains(name))
              if drop.nonEmpty then
                removed ++= drop
                os.write.over(f, keep.asJson.noSpaces)
            case Left(_) => () // unreadable: leave untouched
        }
      removed.result()
    }

  /** Upsert（2026-09-06 事故根因修复）：带 name 的任务替换全库所有同 name 任务
    * （重启后 re-arm 例行任务同名自动去重，收敛单份）；无 name 任务按旧语义
    * 直接追加、不参与去重。返回被替换掉的旧任务列表。 */
  def upsertTaskByName(task: ScheduledTask): IO[List[ScheduledTask]] =
    task.name match
      case None       => addTask(task).as(Nil)
      case Some(n)    => removeByName(n).flatMap(removed => addTask(task).as(removed))

end ScheduledTaskStore
