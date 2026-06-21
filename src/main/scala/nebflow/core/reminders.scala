package nebflow.core

import cats.effect.IO
import cats.syntax.all.*
import nebflow.core.NebflowLogger

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

case class SystemReminder(category: String, content: String):
  def render: String = s"<system-reminder>\n$content\n</system-reminder>"

object SystemReminder:

  def renderAll(reminders: List[SystemReminder]): String =
    if reminders.isEmpty then ""
    else if reminders.length == 1 then reminders.head.render
    else
      val body = reminders.map(_.content).mkString("\n\n")
      s"<system-reminder>\n$body\n</system-reminder>"

object SystemReminders:

  private def currentTime(): SystemReminder =
    val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    SystemReminder("time", s"Current time: $now")

  private val logger = NebflowLogger.forName("nebflow.reminders")

  /**
   * Collect per-turn system reminders.
   * All are non-persistent — injected per-turn, never saved to profile.
   */
  def collectAll(isUserTurn: Boolean = true): List[SystemReminder] =
    val reminders = scala.collection.mutable.ListBuffer.empty[SystemReminder]

    // Current time — only on user input turns
    if isUserTurn then reminders += currentTime()

    reminders.toList
  end collectAll

  /** Log reminders and return them wrapped in IO. */
  def logAndReturn(reminders: List[SystemReminder]): IO[List[SystemReminder]] =
    reminders
      .traverse_ { r =>
        logger.info(s"[${r.category}] ${r.content.take(100)}")
      }
      .as(reminders)
end SystemReminders
