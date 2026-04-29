package nebflow.core

import cats.effect.Ref
import cats.effect.IO
import nebflow.shared.TokenUsage
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

case class SystemReminder(category: String, content: String):
  def render: String = s"<system-reminder>\n$content\n</system-reminder>"

case class ReminderState(
  lastActivityMs: Long = 0L,
  sessionStarted: Boolean = false,
  highestPressureLevel: Int = 0,
  sandboxReminderPending: Boolean = true,
  policyReminderPending: Boolean = false,
  policyPendingName: Option[String] = None
)

object SystemReminders:
  private val IdleThresholdMs: Long = 10 * 60 * 1000L // 10 minutes

  private val PressureLevels = List(20, 40, 60, 80)

  def contextPressure(usage: TokenUsage, contextWindow: Int, highestLevel: Int): (Option[SystemReminder], Int) =
    val ratio = usage.inputTokens.toDouble / contextWindow.toDouble
    val pct = (ratio * 100).toInt
    val newHighest = PressureLevels.filter(l => pct >= l).maxOption.getOrElse(0)
    if newHighest > highestLevel then
      val suggestion = if newHighest >= 80 then
        s"\nContext is approaching the limit. Consider using the ContextManage tool to summarize or delete older messages to free up space."
      else ""
      (Some(SystemReminder(
        "contextPressure",
        s"Token usage: ${usage.inputTokens} / $contextWindow (${pct}% of context window).$suggestion"
      )), newHighest)
    else (None, highestLevel)

  def sessionInfo(): SystemReminder =
    val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    SystemReminder("sessionInfo", s"Current date and time: $now")

  def sandboxStatus(): SystemReminder =
    SystemReminder("sandbox", "Path sandbox is active: Write and Edit operations are restricted to the project root directory.")

  def policyChange(policyName: String): SystemReminder =
    SystemReminder("policyChange", s"Permission policy has been changed to: $policyName")

  def fileChanges(files: List[String]): SystemReminder =
    val fileList = files.take(20).mkString("\n  - ", "\n  - ", if files.length > 20 then s"\n  ... and ${files.length - 20} more" else "")
    SystemReminder("fileChanges", s"The following files were modified externally since the last message:$fileList")

  def collectAll(
    stateRef: Ref[IO, ReminderState],
    usage: Option[TokenUsage],
    contextWindow: Int,
    fileChangesOpt: Option[SystemReminder]
  ): IO[List[SystemReminder]] =
    for
      state <- stateRef.get
      now = System.currentTimeMillis()
      reminders = scala.collection.mutable.ListBuffer.empty[SystemReminder]

      // Session info: first message, after /clear, or after idle
      shouldInjectSession = !state.sessionStarted ||
        (state.lastActivityMs > 0 && now - state.lastActivityMs > IdleThresholdMs)
      _ = if shouldInjectSession then reminders += sessionInfo()

      // Token pressure
      newHighest = state.highestPressureLevel
      (pressureReminder, updatedHighest) = usage match
        case Some(u) => contextPressure(u, contextWindow, state.highestPressureLevel)
        case None => (None, state.highestPressureLevel)
      _ = pressureReminder.foreach(reminders += _)

      // Sandbox status (one-time)
      _ = if state.sandboxReminderPending then reminders += sandboxStatus()

      // Policy change (one-time after /trust)
      _ = if state.policyReminderPending then
        state.policyPendingName.foreach(name => reminders += policyChange(name))

      // External file changes
      _ = fileChangesOpt.foreach(reminders += _)

      // Update state
      _ <- stateRef.set(state.copy(
        lastActivityMs = now,
        sessionStarted = true,
        highestPressureLevel = updatedHighest,
        sandboxReminderPending = false,
        policyReminderPending = false,
        policyPendingName = None
      ))
    yield reminders.toList
end SystemReminders
