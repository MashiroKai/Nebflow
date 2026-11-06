package nebflow.shared

import java.time.format.{DateTimeFormatter, TextStyle}
import java.time.{DayOfWeek, Instant, ZoneId, ZonedDateTime}
import java.util.Locale

/**
 * TimeAnchor —— **「现在」的全仓唯一生成源**（时间感知修复批，作者指令
 * 2026-11-05 18:48「修复你的时间感知问题」）。
 *
 * WHY：模型没有可信的「现在」就会靠猜，一猜则相对时间（今天/昨天/刚才/N 分钟前/
 * 上次）系统性出错（实录：把 10 天前的两次提交说成「两年」）。本对象把
 * 「格式」与「取值」一并收成一处：
 *
 *   - **格式单源**：全仓只有本文件定义模型可见的时间戳格式。其它面
 *     （`SystemReminders` 的每回合锚、`ScheduleTool` 的 `Current time:` 与任务行）
 *     一律调用本对象，**不再各自 `ofPattern(...)`**。
 *   - **取值现取**：[[now]] 每次调用都重新读系统时钟，**任何返回串都不缓存**。
 *     长生命周期对象（systemStable / PromptContext / 各类 memo）里**禁止**存
 *     本对象的产物或「now」本身——冻结的「现在」比没有更坏（它看起来权威）。
 *
 * 格式（一条时间锚，无歧义：本地时间 + UTC 偏移 + 时区名 + 星期 + epoch 毫秒）：
 * {{{
 * 2026-11-05 18:48:12 +08:00 | Asia/Shanghai | Thursday 周四 | epoch_ms 1762345692000
 * }}}
 *
 * 四个字段各自的职责：
 *   - `2026-11-05 18:48:12 +08:00` — 本地时间带偏移，人类与模型都能直读，也能被
 *     `OffsetDateTime.parse` 精确回读（spec 钉了往返一致性）；
 *   - `Asia/Shanghai` — 时区**名**（偏移不足以区分跨夏令时的同偏移区）；
 *   - `Thursday 周四` — 星期几明写，杜绝「周几要靠推算」；
 *   - `epoch_ms ...` — 精确算术底座（提交/文件/报告的毫秒时间戳与它直接相减即得
 *     真实间隔，不必经过日期字符串）。
 *
 * 语言选择说明：星期走 **Locale.ENGLISH 全名 + 中文简写**双写。英文名避开 JVM 默认
 * Locale 抖动（同一毫秒在不同机器上渲染出不同字节 = 缓存与断言双重不稳），中文简写
 * 让以中文思考的会话不必把「周四」再翻一次（相对时间表述本来就是中文高频面）。
 */
object TimeAnchor:

  /** 时间戳主体（本地时间 + UTC 偏移）。`XXX` = `+08:00` / `Z`；Locale.ROOT 防抖动。 */
  private val StampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX", Locale.ROOT)

  /** 紧凑日期时间（任务清单 / 预告时间等行内引用）；与锚同源，仅粒度不同。 */
  private val CompactFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

  /** 中文星期简写（不走 Locale，避免 JVM 默认 Locale 影响字节）。 */
  private val ZhWeekday: Map[DayOfWeek, String] = Map(
    DayOfWeek.MONDAY -> "周一",
    DayOfWeek.TUESDAY -> "周二",
    DayOfWeek.WEDNESDAY -> "周三",
    DayOfWeek.THURSDAY -> "周四",
    DayOfWeek.FRIDAY -> "周五",
    DayOfWeek.SATURDAY -> "周六",
    DayOfWeek.SUNDAY -> "周日"
  )

  /** 星期字段：`Thursday 周四`。 */
  def weekday(at: ZonedDateTime): String =
    val dow = at.getDayOfWeek
    s"${dow.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${ZhWeekday.getOrElse(dow, "")}"

  /** 现在是几点——**每次现取**，永不缓存。 */
  def now(): ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault())

  /** 把一个 epoch 毫秒读数换成本地时区的 `ZonedDateTime`（同一时钟源，仅换形态）。 */
  def at(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime =
    ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zone)

  /**
   * 完整时间锚串（无前缀）：`<本地时间 +偏移> | <时区名> | <英文星期 中文星期> | epoch_ms <毫秒>`。
   *
   * 这是全仓**唯一**的时间锚渲染：任何面要展示「一个时刻」的权威形态都走这里。
   */
  def stamp(at: ZonedDateTime): String =
    s"${StampFormatter.format(at)} | ${at.getZone.getId} | ${weekday(at)} | epoch_ms ${at.toInstant.toEpochMilli}"

  /** 行内紧凑形态（`yyyy-MM-dd HH:mm`，本地时区）。用于任务清单等窄面，不带偏移。 */
  def compact(at: ZonedDateTime): String = CompactFormatter.format(at)

  /** 紧凑形态的 epoch 入参版本（`ScheduleTool` 等以毫秒记账的调用点用）。 */
  def compact(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    compact(at(epochMs, zone))

  /**
   * 每回合时间锚（**持久化**进会话历史的形态）。`Current time:` 前缀是既有契约：
   * `SystemReminders.isTimeReminderMessage` / `pruneTimeReminders` 靠它识别，
   * 勿改前缀。
   */
  def line(at: ZonedDateTime = now()): String = s"Current time: ${stamp(at)}"

  /**
   * 每请求时间锚（**请求级、不落盘**；进 `LlmRequest.systemDynamic`）。
   *
   * 适配器把本段放在历史末尾、缓存断点之后，不写入顶层 system 或会话历史。
   * 每次工具往返都重新读到「现在」，同时保留此前历史的完整缓存前缀。
   */
  def block(at: ZonedDateTime = now()): String =
    "── Clock (authoritative; re-read on every request) ──\n" +
      s"Now: ${stamp(at)}\n" +
      "Derive every relative date (today / yesterday / N minutes ago / last time) and every absolute " +
      "date from this line by arithmetic — never from memory, feeling or a training prior."
