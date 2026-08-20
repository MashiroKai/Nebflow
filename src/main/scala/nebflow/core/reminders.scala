package nebflow.core

import cats.effect.IO
import cats.syntax.all.*
import nebflow.core.NebflowLogger
import nebflow.core.scheduler.{ScheduledTask, ScheduledTaskStore}
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

  /**
   * Collect per-turn reminders including a summary of pending scheduled
   * tasks for this session. IO because it queries the scheduled-task store.
   *
   * Cache-optimization v2 (2026-08-11): dynamic values that were moved OUT of
   * the per-turn system prompt are injected here instead — request-level
   * reminders (never persisted). The time reminder keeps its persisted
   * semantics (任务 O).
   *
   * Reminder refactor (2026-08-20, user ruling — audit
   * docs/Nebflow/20260820_system-reminder-audit.md, 44K token/半天 waste):
   *  - time: injected on real-user turns always; on system-event turns
   *    (mail/external/skill injections) at most once per hour — the caller
   *    passes `injectTime` after checking the gap.
   *  - tasks: Nebula (root) only, real-user turns only; the caller supplies
   *    pre-rendered full/delta/unchanged text (change detection lives in
   *    AgentCore). Manager/team agents query via the TaskList tool instead.
   *  - schedule: Nebula (root) only.
   *  - sessions & environment reminder types REMOVED (user ruling: sessions
   *    outdated; environment change notifications cut — only stable fields
   *    remain in systemStable, jittery chatWidth dropped from the template).
   *  - devices: caller supplies a pre-computed delta (+/- lines).
   *
   * Reminder audit (2026-08-19): time-context (peak/off-peak + next idle
   * window) removed entirely — user flagged "Off-peak hours." as noise
   * (00:00 实测). `tasks` is gated to root agents: subagent workers receive
   * their work from the parent's Delegate/SubTask instructions, not the task
   * store.
   */
  def collectAllIO(
    isUserTurn: Boolean,
    taskStore: ScheduledTaskStore,
    sessionId: Option[String],
    deviceDelta: String = "",
    taskListText: String = "",
    language: Option[String] = None,
    isRootAgent: Boolean = false,
    injectTime: Boolean = true
  ): IO[List[SystemReminder]] =
    if !isUserTurn then IO.pure(Nil)
    else
      for
        pending <- sessionId.fold(IO.pure(Nil: List[ScheduledTask]))(taskStore.loadTasks)
        reminders = (if injectTime then collectAll(isUserTurn = true) else Nil) ++
          (if isRootAgent then pendingScheduleReminder(pending) else None) ++
          devicesReminder(deviceDelta) ++
          (if isRootAgent then tasksReminder(taskListText) else None) ++
          languageReminder(language)
      yield reminders

  /** Devices changed since systemStable was built (cache v2; delta lines 2026-08-20). */
  private def devicesReminder(deviceDelta: String): Option[SystemReminder] =
    if deviceDelta.isEmpty then None
    else Some(SystemReminder("devices", s"Devices changed:\n$deviceDelta"))

  /** Current task list — Nebula-only user-turn reminder, never part of systemStable.
    * The caller (AgentCore) supplies full / delta / unchanged text. */
  private def tasksReminder(taskListText: String): Option[SystemReminder] =
    if taskListText.isEmpty then None
    else Some(SystemReminder("tasks", taskListText))

  /** Language setting changed since systemStable was built (cache v2). */
  private def languageReminder(language: Option[String]): Option[SystemReminder] =
    language.map(lang => SystemReminder("language", s"Language changed: respond in $lang"))

  /** Summary of this session's pending (untriggered) scheduled tasks. */
  private def pendingScheduleReminder(pending: List[ScheduledTask]): Option[SystemReminder] =
    if pending.isEmpty then None
    else
      val now = System.currentTimeMillis()
      val effective = pending.filter(t => t.enabled && !t.triggered && t.triggerAt > now)
      val lines = effective
        .sortBy(_.triggerAt)
        .take(5)
        .map { t =>
          val when = formatScheduleTime(t.triggerAt, now)
          val repeat = t.repeat.fold("")(r => s" ($r)")
          s"- [$when] ${t.content.take(60)}$repeat"
        }
      if lines.isEmpty then None
      else Some(SystemReminder("schedule", s"Pending schedules (${effective.size}):\n${lines.mkString("\n")}"))

  private def formatScheduleTime(epochMs: Long, nowMs: Long): String =
    val zdt = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMs), java.time.ZoneId.systemDefault())
    val nowZdt = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMs), java.time.ZoneId.systemDefault())
    val fmt = DateTimeFormatter.ofPattern("HH:mm")
    val sameDay = zdt.toLocalDate == nowZdt.toLocalDate
    val tomorrow = zdt.toLocalDate == nowZdt.toLocalDate.plusDays(1)
    val day =
      if sameDay then "today"
      else if tomorrow then "tomorrow"
      else zdt.format(DateTimeFormatter.ofPattern("MM-dd"))
    s"${zdt.format(fmt)} $day"

  /** Log reminders and return them wrapped in IO.
    *
    * Reminder refactor (2026-08-20): the `time` category fires on nearly every
    * user turn (~2.3K log lines/half-day) — downsampled to at most one log
    * line per [[TimeLogSampleMs]]. The injection itself is unaffected. */
  def logAndReturn(reminders: List[SystemReminder]): IO[List[SystemReminder]] =
    reminders
      .traverse_ { r =>
        val now = System.currentTimeMillis()
        val shouldLog = r.category != "time" || now - lastTimeLogMs >= TimeLogSampleMs
        if !shouldLog then IO.unit
        else
          if r.category == "time" then lastTimeLogMs = now
          logger.info(s"[${r.category}] ${r.content.take(100)}")
      }
      .as(reminders)

  /** Min interval between two logged `time` reminders (log-noise downsample). */
  private val TimeLogSampleMs = 10 * 60 * 1000L

  @volatile private var lastTimeLogMs: Long = 0L

  // ------------------------------------------------------------------
  // Persisted time-reminder retention (anti-bloat)
  // ------------------------------------------------------------------

  /** Max persisted time-reminder messages kept in session history. */
  val MaxPersistedTimeReminders: Int = 20

  /** True when a User message is a persisted time reminder. */
  def isTimeReminderMessage(msg: Message): Boolean =
    msg.role == MessageRole.User && (msg.content match
      case Left(text) => text.startsWith("<system-reminder>\nCurrent time:")
      case Right(_) => false)

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
