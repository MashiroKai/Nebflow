package nebflow.core

import cats.effect.IO
import cats.syntax.all.*
import nebflow.core.scheduler.{ScheduledTask, ScheduledTaskStore}
import nebflow.shared.*

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Plugin-surface change payload (plugins-live 批 2026-09-12): the rendered
 * `# Plugin Catalog` section as it was last visible to the session (`previous`,
 * = the spawn-time first-message snapshot / the last announced value) versus the
 * current render (`current`). Produced by AgentCore's change detection and
 * consumed by [[SystemReminders.pluginSurfaceReminder]].
 */
case class PluginSurfaceChange(previous: String, current: String)

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
   * system-reminder audit 设计件, 44K token/半天 waste):
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
   *
   * F-2（presdial 批 2026-09-19，`kaiflap-diag` §2.4 M1/M2/M3）：
   *  - `suppressContextReminders` **抑制位**：compact/ask 轮在注入闸
   *    （AgentCore `contextMsg`）处为空 ⇒ 这些**请求级**提醒根本到不了模型。
   *    「没交付就不记数」：该轮**不产生提醒对象** ⇒ 既不注入也不打计数行
   *    （改前 `logAndReturn` 在闸之前 ⇒ 打了行但没注入 ⇒ 基线不推进 ⇒ 下个真用户轮
   *    再打一遍 = M2）。传 true 时抑制的正是 `contextMsg` 会丢掉的那一族
   *    （devices / tasks / language / projects / schedule）；`time` 与
   *    plugin-surface 的采集与降采样次序**不变**（前者持久化、后者持久化，各有自己的闸）。
   *    🔴 未播报的真变化仍保持 pending（基线不动）⇒ 下个真用户轮照常提示。
   *  - `deviceMemberKey` = device 通道的**计数键**（成员集合）——渲染文本变化
   *    （画像 / ⚠stale）不构成新键 ⇒ 不产生计数增量（M3）。
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
    pluginSurfaceChange: Option[PluginSurfaceChange] = None,
    /** F-2：true = 本轮的请求级提醒注定不被注入（compact / ask）⇒ 不产生对象、不计数。 */
    suppressContextReminders: Boolean = false,
    /** F-2：device 变化的计数键（成员集合，deviceId 面）；空 = 不参与去重（旧行为）。 */
    deviceMemberKey: String = ""
  ): IO[List[SystemReminder]] =
    if !isUserTurn then IO.pure(Nil)
    else
      for
        pending <- sessionId.fold(IO.pure(Nil: List[ScheduledTask]))(taskStore.loadTasks)
        contextReminders =
          // F-2 抑制位：不会注入的轮 ⇒ 零提醒对象（含 devices 计数行）。顺序与改前逐字相同。
          if suppressContextReminders then Nil
          else
            List(
              if isRootAgent then pendingScheduleReminder(pending) else None,
              devicesReminder(deviceDelta, deviceMemberKey),
              // Task redesign (2026-08-30): task reminder targets team sessions, not
              // Nebula. The caller (AgentCore) only renders taskListText when the
              // session is team-registered, so the gate lives upstream — include the
              // reminder whenever text is non-empty (schedule stays Nebula-only).
              tasksReminder(taskListText),
              languageReminder(language),
              projectsReminder(mountedProjectsDelta)
            ).flatten
        reminders = (if injectTime then collectAll(isUserTurn = true) else Nil) ++
          contextReminders ++
          pluginSurfaceReminder(pluginSurfaceChange)
      yield reminders

  /**
   * Devices changed since systemStable was built (cache v2; delta lines 2026-08-20).
   *
   * F-2（presdial 批 2026-09-19）：`memberKey` 是变化的**身份**（成员集合），
   * 作为 [[SystemReminder.countKey]] 交给 [[logAndReturn]] 去重计数；空键 ⇒ `None`
   * （不参与去重 = 旧行为）。注入内容与字面**逐字不变**（`+/-` 条目形态保持原样 ——
   * 条目字面属提示词面，本批零改动）。
   */
  private def devicesReminder(deviceDelta: String, memberKey: String = ""): Option[SystemReminder] =
    if deviceDelta.isEmpty then None
    else
      Some(
        SystemReminder(
          "devices",
          s"Devices changed:\n$deviceDelta",
          countKey = Option(memberKey).map(_.trim).filter(_.nonEmpty)
        )
      )

  /**
   * F-2 计数键：设备的**成员集合**（deviceId 面；排序 + 去重 ⇒ 顺序抖动不换键）。
   *
   * 🔴 不得用渲染文本当计数键：条目文本会随画像轴 / `⚠stale` 标记变化（M3），
   * 用文本当键会把「同一批设备的重渲染」计成新变化。
   */
  def deviceMemberKey(deviceIds: Iterable[String]): String =
    deviceIds.iterator.map(_.trim).filter(_.nonEmpty).toList.distinct.sorted.mkString("\u0001")

  /**
   * F-2 M1 计数账本：device 变化的**跨会话去重**。
   *
   * WHY 进程内账本：`stableSnapshot.devices` 是**每 session 各自一套**基线
   * （`AgentCore` 的进程内 state），所以同一次 roster 变化会被「被真用户轮告知的
   * session 数」各计一遍（跨设备/多设备同源场景下「同一变化在极短间隔内被两条 session 各记一次」的最可能解释）。计数键是
   * **成员集合**（跨会话同源）⇒ 「同一变化只计一次」在进程维度可判。
   * 语义 = 与**上一次**已计键比较（不是永久 seen 集）：设备离开后**再次回来**
   * （成员集合回到旧值、但中间经过了别的集合）仍会各计一次。
   */
  object DeviceChangeCount:

    private val lastCountedKey = new java.util.concurrent.atomic.AtomicReference[String]("")
    private val counted = new java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * true = 该键与上一次已计键不同 ⇒ **计一次**（调用方据此打计数行）；
     * false = 同一变化已计过 ⇒ 零增量（不重复打行，注入不受影响）。
     */
    def claim(key: String): Boolean =
      val previous = lastCountedKey.getAndSet(key)
      if previous == key then false
      else
        counted.incrementAndGet()
        true

    /** 计数读数（诊断 / 测试）。 */
    def total: Long = counted.get()

    /** 复位账本（测试用；生产无调用方）。 */
    private[nebflow] def reset(): Unit =
      lastCountedKey.set("")
      counted.set(0L)

  end DeviceChangeCount

  /**
   * Current task list — team-session user-turn reminder, never part of systemStable.
   * The caller (AgentCore) supplies full / delta / unchanged text.
   */
  private def tasksReminder(taskListText: String): Option[SystemReminder] =
    if taskListText.isEmpty then None
    else Some(SystemReminder("tasks", taskListText))

  /** Language setting changed since systemStable was built (cache v2). */
  private def languageReminder(language: Option[String]): Option[SystemReminder] =
    language.map(lang => SystemReminder("language", s"Language changed: respond in $lang"))

  /**
   * Mounted projects changed since systemStable was built (cache v2,
   * progressive disclosure 2026-09-07). Nebula sees incremental +/-
   * entries when a project is mounted/unmounted/created — the root-aware
   * gate lives upstream (AgentCore only renders delta for the root agent).
   */
  private def projectsReminder(mountedProjectsDelta: String): Option[SystemReminder] =
    if mountedProjectsDelta.isEmpty then None
    else Some(SystemReminder("projects", s"Mounted projects changed:\n$mountedProjectsDelta"))

  // ------------------------------------------------------------------
  // Plugin surface (plugins-live 批 2026-09-12)
  // ------------------------------------------------------------------

  /**
   * Reminder category for the plugin-surface delta (AgentCore splits it out of
   * `collectAllIO`'s result and PERSISTS it — see [[isPluginSurfaceReminderMessage]]).
   */
  val PluginSurfaceCategory: String = "plugin-surface"

  /**
   * First line of every plugin-surface reminder body — the persisted-message
   * marker ([[isPluginSurfaceReminderMessage]]) and the human/grep anchor.
   */
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
    change
      .filter(c => c.previous != c.current)
      .map(c => SystemReminder(PluginSurfaceCategory, renderPluginSurfaceChange(c)))

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

  end renderPluginSurfaceChange

  /**
   * `- name: rest` line map of a rendered catalog section (header and the
   * absence note are not `- ` lines and are skipped). Values are the line body
   * WITHOUT the list prefix (callers add `- ` themselves).
   */
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

  /**
   * Keep only the newest [[MaxPersistedPluginSurfaceReminders]] plugin-surface
   * reminders in session history. Each reminder is authoritative and carries the
   * full current catalog, so an older one is fully subsumed by the newer one —
   * dropping it loses nothing and bounds history growth (mirrors
   * [[pruneTimeReminders]]). Called at injection time.
   */
  def prunePluginSurfaceReminders(messages: List[Message]): List[Message] =
    val idx = messages.zipWithIndex.collect { case (m, i) if isPluginSurfaceReminderMessage(m) => i }
    if idx.size <= MaxPersistedPluginSurfaceReminders then messages
    else
      val dropSet = idx.take(idx.size - MaxPersistedPluginSurfaceReminders).toSet
      messages.zipWithIndex.collect { case (m, i) if !dropSet.contains(i) => m }

  /**
   * Max persisted plugin-surface reminders kept in session history (the newest
   * one already supersedes every older one).
   */
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

  /**
   * Log reminders and return them wrapped in IO.
   *
   * Reminder refactor (2026-08-20): the `time` category fires on nearly every
   * user turn (~2.3K log lines/half-day) — downsampled to at most one log
   * line per [[TimeLogSampleMs]]. The injection itself is unaffected.
   *
   * F-2（presdial 批 2026-09-19）：带 [[SystemReminder.countKey]] 的提醒（device
   * 通道）**按变化身份计数** —— 同一变化（成员集合键相同）已经在别的会话计过 ⇒
   * **零增量**（不打第二行）。计数面 = 这一行日志本身（「同一变化对应多条日志行」的源头即此处）。
   * 🔴 注入不受影响（每会话仍各自收到它自己的差量）；`time` 的降采样次序不变
   * （`countKey = None` ⇒ 无条件过闸）。
   */
  def logAndReturn(reminders: List[SystemReminder]): IO[List[SystemReminder]] =
    reminders
      .traverse_ { r =>
        val now = System.currentTimeMillis()
        val sampled = r.category != "time" || now - lastTimeLogMs >= TimeLogSampleMs
        val counted = r.countKey.forall(DeviceChangeCount.claim)
        if !sampled || !counted then IO.unit
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
