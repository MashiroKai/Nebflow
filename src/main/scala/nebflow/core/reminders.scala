package nebflow.core

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import nebflow.agent.WriteTrackerEntry
import nebflow.core.NebflowLogger
import nebflow.shared.TokenUsage

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

/** Global reminder state — only context-pressure tracking (per-session write tracking lives in AgentState). */
case class ReminderState(
  highestPressureLevel: Int = 0
)

object SystemReminders:
  private val PressureLevels = List(20, 40, 60, 80)

  /** Max times a verification reminder is shown for the same file before decaying (stops reminding). */
  private val MaxVerificationReminds = 3

  def contextPressure(usage: TokenUsage, contextWindow: Int, highestLevel: Int): (Option[SystemReminder], Int) =
    if contextWindow <= 0 then (None, highestLevel)
    else
      val ratio = usage.inputTokens.toDouble / contextWindow.toDouble
      val pct = (ratio * 100).toInt
      val newHighest = PressureLevels.filter(l => pct >= l).maxOption.getOrElse(0)
      if newHighest > highestLevel then
        val suggestion =
          if newHighest >= 80 then "\nContext is approaching the limit. Compression will be triggered automatically."
          else ""
        (
          Some(
            SystemReminder(
              "contextPressure",
              s"Token usage: ${usage.inputTokens} / $contextWindow (${pct}% of context window).$suggestion"
            )
          ),
          newHighest
        )
      else (None, highestLevel)

  private def currentTime(): SystemReminder =
    val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    SystemReminder("time", s"Current time: $now")

  def fileChanges(files: List[String]): SystemReminder =
    val fileList = files
      .take(20)
      .mkString("\n  - ", "\n  - ", if files.length > 20 then s"\n  ... and ${files.length - 20} more" else "")
    SystemReminder("fileChanges", s"The following files were modified externally since the last message:$fileList")

  private def verificationReminder(staleFiles: Map[String, Int]): SystemReminder =
    val details = staleFiles.take(10).map { case (f, c) => s"$f ($c edits)" }.mkString(", ")
    val suffix = if staleFiles.size > 10 then s" and ${staleFiles.size - 10} more" else ""
    SystemReminder(
      "verification",
      s"The following files have been edited many times since last read — consider re-reading to verify: $details$suffix"
    )

  /**
   * Compute verification reminder from per-agent write tracker, with decay.
   * Returns (optional reminder, updated write tracker with incremented remindCount).
   * Files that have been reminded MaxVerificationReminds times are excluded (decayed out).
   */
  def computeVerificationReminder(
    writesSinceLastRead: Map[String, WriteTrackerEntry]
  ): (Option[SystemReminder], Map[String, WriteTrackerEntry]) =
    // Only include files that have enough writes AND haven't exceeded max reminders
    val staleFiles = writesSinceLastRead.collect {
      case (f, entry) if entry.writeCount >= 3 && entry.remindCount < MaxVerificationReminds => f -> entry.writeCount
    }
    if staleFiles.isEmpty then (None, writesSinceLastRead)
    else
      // Increment remindCount for files that were included in the reminder
      val updated = writesSinceLastRead.map { case (f, entry) =>
        if staleFiles.contains(f) then f -> entry.copy(remindCount = entry.remindCount + 1)
        else f -> entry
      }
      (Some(verificationReminder(staleFiles)), updated)

  /**
   * Update write tracker based on tool results (pure function).
   * - Reads reset the file's entry.
   * - Writes increment the write count.
   */
  def updateWriteTracker(
    tracker: Map[String, WriteTrackerEntry],
    readFiles: Set[String],
    writtenFiles: List[String]
  ): Map[String, WriteTrackerEntry] =
    val cleared = tracker -- readFiles
    writtenFiles.foldLeft(cleared) { (m, f) =>
      val entry = m.getOrElse(f, WriteTrackerEntry(0, 0))
      m.updated(f, entry.copy(writeCount = entry.writeCount + 1))
    }

  private val logger = NebflowLogger.forName("nebflow.reminders")

  /**
   * Compute system reminders for a turn.
   *
   * @param highestPressureLevel  Current session's highest pressure level.
   * @param usage                 Optional token usage from the last LLM call.
   * @param contextWindow         Context window size for pressure calculation.
   * @param fileChangesOpt        Optional reminder about external file changes.
   * @param isUserTurn            Whether this is a user-initiated turn (affects time reminder).
   * @return                      (reminders to inject, updated highestPressureLevel)
   */
  def collectAll(
    highestPressureLevel: Int,
    usage: Option[TokenUsage],
    contextWindow: Int,
    fileChangesOpt: Option[SystemReminder],
    isUserTurn: Boolean = true
  ): (List[SystemReminder], Int) =
    val reminders = scala.collection.mutable.ListBuffer.empty[SystemReminder]

    // Current time — only on user input turns
    if isUserTurn then reminders += currentTime()

    // Token pressure
    val (pressureReminder, updatedHighest) = usage match
      case Some(u) => contextPressure(u, contextWindow, highestPressureLevel)
      case None => (None, highestPressureLevel)
    pressureReminder.foreach(reminders += _)

    // External file changes
    fileChangesOpt.foreach(reminders += _)

    (reminders.toList, updatedHighest)
  end collectAll

  /** Log reminders and return them wrapped in IO. */
  def logAndReturn(reminders: List[SystemReminder]): IO[List[SystemReminder]] =
    reminders
      .traverse_ { r =>
        logger.info(s"[${r.category}] ${r.content.take(100)}")
      }
      .as(reminders)
end SystemReminders
