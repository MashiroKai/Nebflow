package nebflow.core

import cats.effect.IO
import cats.syntax.all.*
import nebflow.core.NebflowLogger
import nebflow.shared.{Message, MessageRole}

import java.time.ZonedDateTime
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

  /**
   * Current time with timezone offset, JVM default zone, minute granularity.
   * e.g. "2026-08-11 17:00 +08:00"
   */
  private def currentTime(): SystemReminder =
    val now = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm XXX"))
    SystemReminder("time", s"Current time: $now")

  private val logger = NebflowLogger.forName("nebflow.reminders")

  /**
   * Collect per-turn system reminders.
   *
   * The time reminder is PERSISTED as a real User message in the session
   * history (injected into state.messages by AgentCore.pipeLlmCall) so it
   * survives restarts and compaction. Other reminder types (save-memory,
   * compact prompts, branch-change) stay non-persistent — injected per-turn,
   * never saved to profile.
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

  // ------------------------------------------------------------------
  // Persisted time-reminder retention (anti-bloat)
  // ------------------------------------------------------------------

  /** Max persisted time-reminder messages kept in session history. */
  val MaxPersistedTimeReminders: Int = 20

  /** True when a User message is a persisted time reminder. */
  def isTimeReminderMessage(msg: Message): Boolean =
    msg.role == MessageRole.User && (msg.content match
      case Left(text) => text.startsWith("<system-reminder>\nCurrent time:")
      case Right(_) => false
    )

  /**
   * Prune older time-reminder messages, keeping only the most recent
   * [[MaxPersistedTimeReminders]]. Called at injection time (not in the
   * compaction path) so history growth from time messages is bounded.
   */
  def pruneTimeReminders(messages: List[Message]): List[Message] =
    val timeIndices = messages.zipWithIndex.collect { case (m, i) if isTimeReminderMessage(m) => i }
    if timeIndices.size <= MaxPersistedTimeReminders then messages
    else
      val dropSet = timeIndices.take(timeIndices.size - MaxPersistedTimeReminders).toSet
      messages.zipWithIndex.collect { case (m, i) if !dropSet.contains(i) => m }

end SystemReminders
