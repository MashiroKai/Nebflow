package nebflow.shared

import java.time.ZoneId

/**
 * 模型不可用时段判定（model-schedule-unavailable 批，2026-10-29 作者令 17:44
 * 「下午2点到6点，我不想用zhipu和kimi的模型，因为会加倍收费。应该设置一个，
 * 模型不可用时间规则就行了」）——**单点谓词**。
 *
 * 纯时间数学，无 cats / 无 IO：注入 now 便于测试，时区恒 = 本机时区
 * （`ZoneId.systemDefault()`，与冻结调度 FreezeSchedule 同侧同口径——两者的
 * 段形态同构：HH:mm、[start, end)、start > end = 跨午夜，故判据逐条对齐其
 * D6 规则；shared 不依赖 core，`parseHHmm` 为同形复制而非引用，注释即绑定）。
 *
 * 语义（设计件 §3，docs/design/20261029_1815_model-schedule-unavailable-spec…）：
 *   - 段 = [from, to)：14:00 整即禁、18:00 整即解；
 *   - from < to 常规段；from > to 跨午夜段（23:00-07:00 = [23:00,24:00)∪[00:00,07:00)）；
 *   - from == to 非法：fail-safe 永不命中（不定义为 24h 全禁——误配即全停
 *     过于危险，FreezeSchedule D6 同款裁定）；
 *   - 任一端解析失败：该条 fail-safe 永不命中（非法配置 = 关闭该条）；
 *   - 多条规则取并集；provider 级 ∪ 模型级（并集在调用方完成，本对象只收
 *     已合并的窗口列表）；
 *   - `days` = ISO 星期 1..7，缺席 = 每天；跨午夜段的清晨腿归**昨天**的星期。
 */
object ModelAvailability:

  /** 解析 "HH:mm" → 当日分钟数。非法 → None。（FreezeSchedule.parseHHmm 同形。） */
  def parseHHmm(s: String): Option[Int] =
    s match
      case str if str != null =>
        """^([01]\d|2[0-3]):([0-5]\d)$""".r.findFirstMatchIn(str) match
          case Some(m) => Some(m.group(1).toInt * 60 + m.group(2).toInt)
          case None => None
      case null => None

  /** 该条规则两端合法且 from != to（零长度段非法——见对象注释）。 */
  def isValidWindow(w: UnavailableWindow): Boolean =
    (parseHHmm(w.from), parseHHmm(w.to)) match
      case (Some(a), Some(b)) => a != b
      case _ => false

  /** 单条规则是否覆盖给定时刻（分钟粒度判定 + 星期归属；非法规则恒 false）。 */
  def covers(w: UnavailableWindow, now: Long, zone: ZoneId): Boolean =
    (parseHHmm(w.from), parseHHmm(w.to)) match
      case (Some(from), Some(to)) if from != to =>
        val zdt = java.time.Instant.ofEpochMilli(now).atZone(zone)
        val minuteOfDay = zdt.getHour * 60 + zdt.getMinute
        val today = zdt.getDayOfWeek.getValue
        val yesterday = zdt.minusDays(1).getDayOfWeek.getValue
        def dayMatches(d: Int): Boolean = w.days.forall(_.contains(d))
        if from < to then dayMatches(today) && minuteOfDay >= from && minuteOfDay < to
        else // 跨午夜：晚腿归今天、清晨腿归昨天（星期各随所属日）
          (dayMatches(today) && minuteOfDay >= from) || (dayMatches(yesterday) && minuteOfDay < to)
      case _ => false // 非法 / 零长段：fail-safe 永不命中

  /** 合并窗口列表是否覆盖给定时刻（多条取并集；空列表 = 无限制）。 */
  def isUnavailable(windows: List[UnavailableWindow], now: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean =
    windows.exists(covers(_, now, zone))

  /** 当前时刻命中的窗口描述串（"14:00-18:00"；多条取首条命中的即可读性）。 */
  def activeWindowDesc(windows: List[UnavailableWindow], now: Long, zone: ZoneId = ZoneId.systemDefault()): Option[String] =
    windows.find(covers(_, now, zone)).map(describeWindow)

  /** 单条窗口的可读形态（"14:00-18:00"；days 非全周时附 "(周6,周7)"）。 */
  def describeWindow(w: UnavailableWindow): String =
    val dayNote = w.days match
      case Some(ds) if ds.nonEmpty && ds.sorted != List(1, 2, 3, 4, 5, 6, 7) =>
        ds.sorted.map(d => s"周$d").mkString("(", ",", ")")
      case _ => ""
    s"${w.from}-${w.to}$dayNote"

  /** 多条窗口的可读形态（逗号连接；空 = ""）。 */
  def describeWindows(windows: List[UnavailableWindow]): String =
    windows.filter(isValidWindow).map(describeWindow).mkString(", ")

end ModelAvailability

/**
 * 全受限终局（model-schedule-unavailable 批）：本次请求的候选链上**每一个**
 * 模型都处于其不可用时段内（含储备层），没有任何未受限模型可承接。
 *
 * 语义（作者令倾向，采纳）：仍按链序尝试未受限模型（= 过滤后照常走链）；
 * 全受限才显式报错，**不静默**、也不进 120s 全灭闸等待——时间闸等不来（同
 * 配额类「等不如换」，换无可换才报）。消息自带原因与窗口（「当前时段受限」
 * 可读面）。分类走 classifyBase 兜底 = Unknown/Transient ⇒ agent 层既有
 * llm-fail 重试预算内每轮重过闸：18:00:01 的重试自然成功（时间闸的自愈形态）。
 */
final class ModelScheduleRestrictedError(
  val restricted: List[String],
  val windowsDesc: String
) extends RuntimeException(
    s"当前时段（$windowsDesc）全部候选模型受限：${restricted.mkString("、")} — " +
      "所有候选都在其不可用时段内，已按规则跳过（不静默降级）。可等待窗口结束后自动恢复，" +
      "或在 设置→服务商 中调整 unavailableWindows。"
  )
