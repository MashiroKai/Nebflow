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

/** Plugin-surface change payload (plugins-live 批 2026-09-12): the rendered
  * `# Plugin Catalog` section as it was last visible to the session (`previous`,
  * = the spawn-time first-message snapshot / the last announced value) versus the
  * current render (`current`). Produced by AgentCore's change detection and
  * consumed by [[SystemReminders.pluginSurfaceReminder]]. */
case class PluginSurfaceChange(previous: String, current: String)

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
   *  - tasks: team sessions only, real-user turns only (task redesign
   *    2026-08-30 — moved from Nebula to team members); the caller supplies
   *    pre-rendered full/delta/unchanged text (change detection lives in
   *    AgentCore, gated on TeamSessionRegistry.teamOfSession).
   *  - schedule: Nebula (root) only.
   *  - sessions & environment reminder types REMOVED (user ruling: sessions
   *    outdated; environment change notifications cut — only stable fields
   *    remain in systemStable, jittery chatWidth dropped from the template).
   *  - devices: caller supplies a pre-computed delta (+/- lines).
   *  - projects: Nebula only (progressive disclosure 2026-09-07) — caller
   *    supplies a pre-computed +/- delta of mounted projects; empty when no
   *    change. systemStable carries the full list at lifecycle nodes, mid-session
   *    mount/unmount/create is reported here instead of invalidating the cache.
   *  - plugin-surface: dispatcher sessions only (plugins-live 批 2026-09-12) —
   *    caller supplies the previous/current rendered Plugin Catalog section; the
   *    reminder is emitted on a real change and is PERSISTED (see
   *    SystemReminders.isPluginSurfaceReminderMessage) so a closed plugin's
   *    capability stays retracted for every later turn of the same session.
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
    injectTime: Boolean = true,
    mountedProjectsDelta: String = "",
    pluginSurfaceChange: Option[PluginSurfaceChange] = None
  ): IO[List[SystemReminder]] =
    if !isUserTurn then IO.pure(Nil)
    else
      for
        pending <- sessionId.fold(IO.pure(Nil: List[ScheduledTask]))(taskStore.loadTasks)
        reminders = (if injectTime then collectAll(isUserTurn = true) else Nil) ++
          (if isRootAgent then pendingScheduleReminder(pending) else None) ++
          devicesReminder(deviceDelta) ++
          // Task redesign (2026-08-30): task reminder targets team sessions, not
          // Nebula. The caller (AgentCore) only renders taskListText when the
          // session is team-registered, so the gate lives upstream — include the
          // reminder whenever text is non-empty (schedule stays Nebula-only).
          tasksReminder(taskListText) ++
          languageReminder(language) ++
          projectsReminder(mountedProjectsDelta) ++
          // Plugins-live (2026-09-12): dispatcher sessions only — the caller
          // supplies the change pair and never calls here without one.
          pluginSurfaceReminder(pluginSurfaceChange)
      yield reminders

  /** Devices changed since systemStable was built (cache v2; delta lines 2026-08-20). */
  private def devicesReminder(deviceDelta: String): Option[SystemReminder] =
    if deviceDelta.isEmpty then None
    else Some(SystemReminder("devices", s"Devices changed:\n$deviceDelta"))

  /** Current task list — team-session user-turn reminder, never part of systemStable.
    * The caller (AgentCore) supplies full / delta / unchanged text. */
  private def tasksReminder(taskListText: String): Option[SystemReminder] =
    if taskListText.isEmpty then None
    else Some(SystemReminder("tasks", taskListText))

  /** Language setting changed since systemStable was built (cache v2). */
  private def languageReminder(language: Option[String]): Option[SystemReminder] =
    language.map(lang => SystemReminder("language", s"Language changed: respond in $lang"))

  /** Mounted projects changed since systemStable was built (cache v2,
    * progressive disclosure 2026-09-07). Nebula sees incremental +/-
    * entries when a project is mounted/unmounted/created — the root-aware
    * gate lives upstream (AgentCore only renders delta for the root agent). */
  private def projectsReminder(mountedProjectsDelta: String): Option[SystemReminder] =
    if mountedProjectsDelta.isEmpty then None
    else Some(SystemReminder("projects", s"Mounted projects changed:\n$mountedProjectsDelta"))

  // ------------------------------------------------------------------
  // Plugin surface (plugins-live 批 2026-09-12)
  // ------------------------------------------------------------------

  /** Reminder category for the plugin-surface delta (AgentCore splits it out of
    * `collectAllIO`'s result and PERSISTS it — see [[isPluginSurfaceReminderMessage]]). */
  val PluginSurfaceCategory: String = "plugin-surface"

  /** First line of every plugin-surface reminder body — the persisted-message
    * marker ([[isPluginSurfaceReminderMessage]]) and the human/grep anchor. */
  val PluginSurfaceMarker: String = "Plugin surface changed"

  /**
   * Plugin trust/`plugins.enabled` changed for a session that carries a Plugin
   * Catalog in context (dispatcher sessions — `ProjectActor.pluginCatalogText`
   * renders it into the FIRST message only). Without this channel an already-open
   * dispatcher session keeps seeing the spawn-time catalog forever: revoking a
   * plugin does not refresh it, so the session still plans nodes against a
   * capability that is no longer allocatable (author report 2026-09-12 12:52).
   *
   * Semantics (same shape as the devices/projects delta channels): the caller
   * (AgentCore) compares the catalog **as last announced to this session** with
   * the current render and calls here only on a real change — no change ⇒ `None`
   * ⇒ zero injection.
   *
   * Content rules:
   *  - closed plugins are named ONLY (no description) under an explicit VOID
   *    clause — their capability description must not be re-advertised;
   *  - the current catalog is carried verbatim as the authoritative value, with an
   *    explicit supersede clause covering the first message's catalog section
   *    (the original context is never rewritten);
   *  - re-opened plugins get their catalog line back.
   */
  def pluginSurfaceReminder(change: Option[PluginSurfaceChange]): Option[SystemReminder] =
    change.filter(c => c.previous != c.current).map(c => SystemReminder(PluginSurfaceCategory, renderPluginSurfaceChange(c)))

  /** Render the authoritative plugin-surface reminder body (pure — unit-testable). */
  private[core] def renderPluginSurfaceChange(c: PluginSurfaceChange): String =
    val previous = catalogEntries(c.previous)
    val current = catalogEntries(c.current)
    val removed = (previous.keySet -- current.keySet).toList.sorted
    val added = (current.keySet -- previous.keySet).toList.sorted
    val sb = scala.collection.mutable.ListBuffer.empty[String]
    sb += s"""$PluginSurfaceMarker — authoritative。本提醒是本会话**当前插件面**的权威值："""
    sb += "取代本会话首条消息里的 `# Plugin Catalog` 段以及更早的 Plugin surface 提醒；前文目录段中已关闭插件的能力条目一律作废。"
    if removed.nonEmpty then
      sb += ""
      sb += "已关闭 / 不再可用（其能力描述作废——不得据此选配 plugins，也不得建依赖该能力的节点）："
      removed.foreach(n => sb += s"- $n")
    if added.nonEmpty then
      sb += ""
      sb += "已批准 / 重新可用（可正常选配）："
      added.foreach(n => sb += s"- ${current.getOrElse(n, n)}")
    sb += ""
    sb += "当前插件面（权威目录）："
    val body = c.current.trim
    if body.isEmpty then sb += "- （当前无可分配插件）" else sb += body
    sb.mkString("\n")

  /** `- name: rest` line map of a rendered catalog section (header and the
    * absence note are not `- ` lines and are skipped). Values are the line body
    * WITHOUT the list prefix (callers add `- ` themselves). */
  private def catalogEntries(catalog: String): Map[String, String] =
    catalog.linesIterator
      .filter(_.startsWith("- "))
      .map(_.drop(2))
      .flatMap { entry =>
        val i = entry.indexOf(':')
        val name = if i > 0 then entry.take(i).trim else entry.trim
        Option.when(name.nonEmpty)(name -> entry)
      }
      .toMap

  /** True when a persisted User message is a plugin-surface reminder. */
  def isPluginSurfaceReminderMessage(msg: Message): Boolean =
    msg.role == MessageRole.User && (msg.content match
      case Left(text) => text.contains(PluginSurfaceMarker)
      case Right(_) => false)

  /** Keep only the newest [[MaxPersistedPluginSurfaceReminders]] plugin-surface
    * reminders in session history. Each reminder is authoritative and carries the
    * full current catalog, so an older one is fully subsumed by the newer one —
    * dropping it loses nothing and bounds history growth (mirrors
    * [[pruneTimeReminders]]). Called at injection time. */
  def prunePluginSurfaceReminders(messages: List[Message]): List[Message] =
    val idx = messages.zipWithIndex.collect { case (m, i) if isPluginSurfaceReminderMessage(m) => i }
    if idx.size <= MaxPersistedPluginSurfaceReminders then messages
    else
      val dropSet = idx.take(idx.size - MaxPersistedPluginSurfaceReminders).toSet
      messages.zipWithIndex.collect { case (m, i) if !dropSet.contains(i) => m }

  /** Max persisted plugin-surface reminders kept in session history (the newest
    * one already supersedes every older one). */
  val MaxPersistedPluginSurfaceReminders: Int = 1

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
          // id + name（2026-09-06 升级）：给 Nebula 直接的 cancel/upsert 句柄——
          // 重启后无需先 list 就能辨认既有例行任务（防 re-arm 双份的事故盲区）。
          val nm = t.name.fold("")(n => s" name: $n")
          s"- [$when] ${t.content.take(60)}$repeat [id: ${t.id}$nm]"
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
