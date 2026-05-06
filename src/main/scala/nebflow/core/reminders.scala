package nebflow.core

import cats.effect.{IO, Ref}
import cats.syntax.all.*
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

case class ReminderState(
  lastActivityMs: Long = 0L,
  highestPressureLevel: Int = 0,
  writesSinceLastRead: Map[String, Int] = Map.empty
)

object SystemReminders:
  private val PressureLevels = List(20, 40, 60, 80)

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

  def verificationReminder(staleFiles: Map[String, Int]): SystemReminder =
    val details = staleFiles.take(10).map { case (f, c) => s"$f ($c edits)" }.mkString(", ")
    val suffix = if staleFiles.size > 10 then s" and ${staleFiles.size - 10} more" else ""
    SystemReminder(
      "verification",
      s"The following files have been edited many times since last read — consider re-reading to verify: $details$suffix"
    )

  private val logger = NebflowLogger.forName("nebflow.reminders")

  def collectAll(
    stateRef: Ref[IO, ReminderState],
    usage: Option[TokenUsage],
    contextWindow: Int,
    fileChangesOpt: Option[SystemReminder],
    isUserTurn: Boolean = true
  ): IO[List[SystemReminder]] =
    stateRef
      .modify { state =>
        val reminders = scala.collection.mutable.ListBuffer.empty[SystemReminder]

        // Current time — only on user input turns
        if isUserTurn then reminders += currentTime()

        // Token pressure
        val (pressureReminder, updatedHighest) = usage match
          case Some(u) => contextPressure(u, contextWindow, state.highestPressureLevel)
          case None => (None, state.highestPressureLevel)
        pressureReminder.foreach(reminders += _)

        // External file changes
        fileChangesOpt.foreach(reminders += _)

        // Verification reminder: files with many writes since last read
        val staleFiles = state.writesSinceLastRead.filter((_, count) => count >= 3)
        if staleFiles.nonEmpty then reminders += verificationReminder(staleFiles)

        val newState = state.copy(
          lastActivityMs = System.currentTimeMillis(),
          highestPressureLevel = updatedHighest
        )

        (newState, reminders.toList)
      }
      .flatMap { reminders =>
        reminders
          .traverse_ { r =>
            logger.info(s"[${r.category}] ${r.content.take(100)}")
          }
          .as(reminders)
      }
  end collectAll
end SystemReminders
