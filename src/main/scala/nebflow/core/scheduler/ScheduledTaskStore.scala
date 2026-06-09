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
            case Right(list) => list.filter(t => !t.triggered && t.triggerAt <= now)
            case Left(_) => Nil
        }
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

end ScheduledTaskStore
