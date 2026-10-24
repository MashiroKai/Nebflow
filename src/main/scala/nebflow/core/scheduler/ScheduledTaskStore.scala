package nebflow.core.scheduler

import cats.effect.IO
import cats.syntax.all.*
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.shared.NebflowLogger

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

  /** Get all due (untriggered, past triggerAt) tasks across all sessions.
    * 双面化（2026-10-24）：待办（kind=todo）不走定时触发路径——排除之，
    * 由 getAllDueTodos / ScheduledTaskService.surfaceTodos 的周期浮出面接管。
    */
  def getAllDueTasks: IO[List[ScheduledTask]] = IO.blocking {
    if !os.exists(baseDir) then Nil
    else
      val now = System.currentTimeMillis()
      os.list(baseDir)
        .filter(_.last.endsWith(".json"))
        .toList
        .flatMap { f =>
          decode[List[ScheduledTask]](os.read(f)) match
            case Right(list) =>
              list.filter(t => !t.triggered && t.triggerAt <= now && t.enabled && !t.isTodo)
            case Left(_) => Nil
        }
        // FIFO: fire in scheduled-time order so simultaneously-due tasks are
        // processed one by one in a deterministic sequence.
        .sortBy(_.triggerAt)
    end if
  }

  /** Get all pending (untriggered) tasks across all sessions — used to compute next fire time.
    * 含待办（待办也是 pending 条目；调用方按 kind 分面呈现——工具 list 分节、
    * next-fire 计算把待办的下次浮出时间视同一次未来注入，无害）。
    */
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

  /** Get ALL unfinished todos across all sessions — digest 的内容源（含尚未到
    * 浮出时间的，汇总语义：一次把未完成待办说全）。 */
  def getAllTodos: IO[List[ScheduledTask]] = IO.blocking {
    if !os.exists(baseDir) then Nil
    else
      os.list(baseDir)
        .filter(_.last.endsWith(".json"))
        .toList
        .flatMap { f =>
          decode[List[ScheduledTask]](os.read(f)) match
            case Right(list) => list.filter(t => t.isTodo && !t.triggered && t.enabled)
            case Left(_) => Nil
        }
        .sortBy(t => (t.triggerAt, t.createdAt))
    end if
  }

  /** Get due todos (untriggered, enabled, past surface time) across all sessions. */
  def getAllDueTodos: IO[List[ScheduledTask]] = IO.blocking {
    if !os.exists(baseDir) then Nil
    else
      val now = System.currentTimeMillis()
      os.list(baseDir)
        .filter(_.last.endsWith(".json"))
        .toList
        .flatMap { f =>
          decode[List[ScheduledTask]](os.read(f)) match
            case Right(list) => list.filter(t => t.isTodo && !t.triggered && t.enabled && t.triggerAt <= now)
            case Left(_) => Nil
        }
        .sortBy(t => (t.triggerAt, t.createdAt))
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

  /**
   * Find a task by id across ALL session files（2026-09-06 升级）：list 是跨
   * 会话的，cancel 按 id 也必须跨会话——任务可能住在旧会话文件里（重启后
   * re-arm 去重的目标正是它们）。
   */
  def findTaskById(taskId: String): IO[Option[ScheduledTask]] = IO.blocking {
    if !os.exists(baseDir) then None
    else
      os.list(baseDir)
        .filter(_.last.endsWith(".json"))
        .toList
        .flatMap(f => decode[List[ScheduledTask]](os.read(f)).getOrElse(Nil))
        .find(_.id == taskId)
  }

  /**
   * Remove every task whose name == given name across ALL session files.
   * Returns the removed tasks. Same-name tasks are global-unique（upsert 语义
   * 的清理半步）；读不了的任务文件原样跳过（与 loadTasks 容错同口径）。
   * 双面化（2026-10-24）：kind 圈定清理范围——待办与定时任务可同名互不覆盖
   * （kind 不匹配者原样保留）；默认 None = 定时任务面，对存量调用方零变化。
   */
  def removeByName(name: String, kind: Option[String] = ScheduledTask.KindTask.some): IO[List[ScheduledTask]] =
    IO.blocking {
      if !os.exists(baseDir) then Nil
      else
        val removed = List.newBuilder[ScheduledTask]
        val matchKind = kind.getOrElse(ScheduledTask.KindTask).some
        os.list(baseDir)
          .filter(_.last.endsWith(".json"))
          .toList
          .foreach { f =>
            decode[List[ScheduledTask]](os.read(f)) match
              case Right(list) =>
                val (drop, keep) = list.partition(t => t.name.contains(name) && ScheduledTask.sameKind(t.kind, matchKind))
                if drop.nonEmpty then
                  removed ++= drop
                  os.write.over(f, keep.asJson.noSpaces)
              case Left(_) => () // unreadable: leave untouched
          }
        removed.result()
    }

  /**
   * Upsert（2026-09-06 事故根因修复）：带 name 的任务替换全库所有同 name 任务
   * （重启后 re-arm 例行任务同名自动去重，收敛单份）；无 name 任务按旧语义
   * 直接追加、不参与去重。返回被替换掉的旧任务列表。双面化（2026-10-24）：
   * 清理半步按 task 自身 kind 圈定——todo upsert 不吃同名定时任务，反之亦然。
   */
  def upsertTaskByName(task: ScheduledTask): IO[List[ScheduledTask]] =
    task.name match
      case None => addTask(task).as(Nil)
      case Some(n) => removeByName(n, task.kind).flatMap(removed => addTask(task).as(removed))

end ScheduledTaskStore
