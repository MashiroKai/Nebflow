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
   * Collect per-turn reminders including time-of-day context (peak/off-peak +
   * next idle window from usage history) and a summary of pending scheduled
   * tasks for this session. IO because it queries the scheduled-task store and
   * the persisted usage pattern.
   *
   * Cache-optimization v2 (2026-08-11): dynamic values that were moved OUT of
   * the per-turn system prompt are injected here instead — request-level
   * reminders (never persisted). Callers pass the *current* values; only
   * changed values produce a reminder (task list is always reported on user
   * turns). The time reminder keeps its persisted semantics (任务 O).
   */
  def collectAllIO(
    isUserTurn: Boolean,
    taskStore: ScheduledTaskStore,
    sessionId: Option[String],
    deviceInfo: String = "",
    sessionsText: String = "",
    taskListText: String = "",
    language: Option[String] = None,
    envInfo: String = ""
  ): IO[List[SystemReminder]] =
    if !isUserTurn then IO.pure(Nil)
    else
      for
        pattern <- UsageTracker.loadPattern()
        pending <- sessionId.fold(IO.pure(Nil: List[ScheduledTask]))(taskStore.loadTasks)
        reminders = collectAll(isUserTurn = true) ++
          timeContextReminder(pattern) ++
          pendingScheduleReminder(pending) ++
          devicesReminder(deviceInfo) ++
          sessionsReminder(sessionsText) ++
          envReminder(envInfo) ++
          tasksReminder(taskListText) ++
          languageReminder(language)
      yield reminders

  /** Devices changed since systemStable was built (cache v2). */
  private def devicesReminder(deviceInfo: String): Option[SystemReminder] =
    if deviceInfo.isEmpty then None
    else Some(SystemReminder("devices", s"Devices changed:\n$deviceInfo"))

  /** Active sessions changed since systemStable was built (cache v2). */
  private def sessionsReminder(sessionsText: String): Option[SystemReminder] =
    if sessionsText.isEmpty then None
    else Some(SystemReminder("sessions", s"Active sessions changed:\n$sessionsText"))

  /** Environment section (chat width / PID / platform) changed (cache v2). */
  private def envReminder(envInfo: String): Option[SystemReminder] =
    if envInfo.isEmpty then None
    else Some(SystemReminder("environment", s"Environment changed:\n$envInfo"))

  /** Current task list — user-turn only, never part of systemStable (cache v2). */
  private def tasksReminder(taskListText: String): Option[SystemReminder] =
    if taskListText.isEmpty then None
    else Some(SystemReminder("tasks", s"Current tasks:\n$taskListText"))

  /** Language setting changed since systemStable was built (cache v2). */
  private def languageReminder(language: Option[String]): Option[SystemReminder] =
    language.map(lang => SystemReminder("language", s"Language changed: respond in $lang"))

  /** Peak (14-18 on weekdays) / off-peak + next idle window from usage history. */
  private def timeContextReminder(pattern: UsagePattern): Option[SystemReminder] =
    val now = ZonedDateTime.now()
    val isPeak = now.getDayOfWeek.getValue <= 5 && now.getHour >= 14 && now.getHour < 18
    val peakPart = if isPeak then "Peak hours active (14:00-18:00, 3x pricing)" else "Off-peak hours"
    val idlePart = nextIdleWindow(pattern, now) match
      case Some(w) => s"Next idle window: ${w.startHour}:00 (${w.durationHours}h)"
      case None => ""
    val content = s"$peakPart. $idlePart".trim
    if content.isEmpty then None else Some(SystemReminder("time-context", content))

  /** Find the next idle window strictly after now (today or upcoming days). */
  private def nextIdleWindow(pattern: UsagePattern, now: ZonedDateTime): Option[TimeWindow] =
    if pattern.idleWindows.isEmpty then None
    else
      val todayDow = now.getDayOfWeek.getValue % 7
      val nowHour = now.getHour
      // Candidates today (later hours) and the next 7 days
      (0 to 7).iterator
        .flatMap { dayOffset =>
          val dow = (todayDow + dayOffset) % 7
          pattern.idleWindows.filter(_.dayOfWeek == dow).map(w => (dayOffset, w))
        }
        .collectFirst {
          case (0, w) if w.startHour > nowHour => w
          case (dayOffset, w) if dayOffset > 0 => w
        }

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
