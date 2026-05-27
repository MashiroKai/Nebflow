package nebflow.core.reminder

import cats.effect.IO
import cats.syntax.all.*
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.core.NebflowLogger

class ReminderStore(baseDir: os.Path):

  private val logger = NebflowLogger.forName("nebflow.reminder.store")

  private def sessionFile(sessionId: String): os.Path =
    baseDir / s"$sessionId.json"

  private def ensureBaseDir: IO[Unit] = IO.blocking {
    if !os.exists(baseDir) then os.makeDir.all(baseDir)
  }

  def loadReminders(sessionId: String): IO[List[Reminder]] = IO.blocking {
    val f = sessionFile(sessionId)
    if !os.exists(f) then Nil
    else
      decode[List[Reminder]](os.read(f)) match
        case Right(list) => list
        case Left(err) =>
          logger.warnSync(s"Failed to parse reminders for $sessionId: ${err.getMessage}")
          Nil
  }

  def saveReminders(sessionId: String, reminders: List[Reminder]): IO[Unit] =
    ensureBaseDir *> IO.blocking {
      os.write.over(sessionFile(sessionId), reminders.asJson.noSpaces)
    }

  def addReminder(reminder: Reminder): IO[Unit] =
    loadReminders(reminder.sessionId).flatMap { existing =>
      saveReminders(reminder.sessionId, existing :+ reminder)
    }

  def deleteReminder(sessionId: String, reminderId: String): IO[Unit] =
    loadReminders(sessionId).flatMap { existing =>
      saveReminders(sessionId, existing.filterNot(_.id == reminderId))
    }

  def markTriggered(sessionId: String, reminderId: String): IO[Unit] =
    loadReminders(sessionId).flatMap { existing =>
      val updated = existing.map { r =>
        if r.id == reminderId then r.copy(triggered = true, triggeredAt = Some(System.currentTimeMillis()))
        else r
      }
      saveReminders(sessionId, updated)
    }

  /** Get all due (untriggered, past triggerAt) reminders across all sessions. */
  def getAllDueReminders: IO[List[Reminder]] = IO.blocking {
    if !os.exists(baseDir) then Nil
    else
      val now = System.currentTimeMillis()
      os.list(baseDir)
        .filter(_.last.endsWith(".json"))
        .toList
        .flatMap { f =>
          decode[List[Reminder]](os.read(f)) match
            case Right(list) => list.filter(r => !r.triggered && r.triggerAt <= now)
            case Left(_) => Nil
        }
    end if
  }

  /** Get pending (untriggered) reminder count for a session. */
  def getPendingCount(sessionId: String): IO[Int] =
    loadReminders(sessionId).map(_.count(!_.triggered))

end ReminderStore
