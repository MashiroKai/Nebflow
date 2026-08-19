package nebflow.core.schedule

import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json}

/**
 * 冻结调度（freeze-schedule，2026-08-19 spec v1.1）：工作时间表模型 + 纯函数判定。
 *
 * nebflow.json 顶层 workSchedule 节：
 * {{{
 * "workSchedule": {
 *   "enabled": true,
 *   "segments": [ {"start": "09:00", "end": "12:00"}, {"start": "14:00", "end": "18:00"} ]
 * }
 * }}}
 *
 * 语义（D6）：每日相同、HH:mm 段、start < end（不支持跨天）；段重叠允许
 * （判定取并集）；enabled 且 segments 为空视为非法（validate 拒绝、load fail-safe
 * 视为关闭）。默认 disabled——不开启时行为与现在 100% 一致。
 */
case class WorkScheduleSegment(start: String, end: String)

case class WorkScheduleConfig(
  enabled: Boolean = false,
  segments: List[WorkScheduleSegment] = Nil
)

/** eval 结果：open=当前是否在工作时间内；nextChangeAt=下一次开/关边界 epoch millis。 */
case class WorkWindow(open: Boolean, nextChangeAt: Option[Long])

object WorkSchedule:

  given Encoder[WorkScheduleSegment] = Encoder.derived
  given Decoder[WorkScheduleSegment] = Decoder.instance { c =>
    for
      start <- c.downField("start").as[String]
      end <- c.downField("end").as[String]
    yield WorkScheduleSegment(start, end)
  }

  given Encoder[WorkScheduleConfig] = Encoder.derived
  given Decoder[WorkScheduleConfig] = Decoder.instance { c =>
    for
      enabled <- c.downField("enabled").as[Option[Boolean]].map(_.getOrElse(false))
      segments <- c.downField("segments").as[Option[List[WorkScheduleSegment]]].map(_.getOrElse(Nil))
    yield WorkScheduleConfig(enabled, segments)
  }

  private val MinutePerDay = 24 * 60

  /** 解析 "HH:mm" → 当日分钟数。非法 → None。 */
  def parseHHmm(s: String): Option[Int] =
    s match
      case str if str != null =>
        """^([01]\d|2[0-3]):([0-5]\d)$""".r.findFirstMatchIn(str) match
          case Some(m) => Some(m.group(1).toInt * 60 + m.group(2).toInt)
          case None => None
      case null => None

  private def validSegment(s: WorkScheduleSegment): Boolean =
    (parseHHmm(s.start), parseHHmm(s.end)) match
      case (Some(a), Some(b)) => a < b
      case _ => false

  /**
   * fail-safe 加载（B8）：decode 失败 / enabled 且 segments 空 / 任一段非法
   * → disabled（恒开窗，功能关闭语义）。disabled + 合法段 → 保留段（UI 回显
   * round-trip：用户关掉开关不应丢掉已配时段）。
   */
  def load(json: Option[Json]): WorkScheduleConfig =
    json
      .flatMap(j => j.as[WorkScheduleConfig].toOption)
      .map { cfg =>
        val allValid = cfg.segments.forall(validSegment)
        if !allValid then WorkScheduleConfig(enabled = false)
        else if cfg.enabled && cfg.segments.isEmpty then WorkScheduleConfig(enabled = false)
        else cfg
      }
      .getOrElse(WorkScheduleConfig())

  /**
   * setWorkSchedule WS 命令的校验入口（B9）：非法 payload → Left(错误消息)，
   * 调用方拒绝保存并回 configUpdateFailed。规则：每段 HH:mm 格式且 start < end；
   * enabled 时至少一段。
   */
  def validate(json: Json): Either[String, WorkScheduleConfig] =
    json.as[WorkScheduleConfig].left.map(_ => "工作时间段配置格式错误：需要 {enabled, segments:[{start,end}]}").flatMap {
      cfg =>
        if cfg.segments.isEmpty then
          if cfg.enabled then Left("开启工作时间需要至少一个时间段") else Right(cfg)
        else
          cfg.segments
            .collectFirst {
              case s if !validSegment(s) =>
                s"时间段无效：${s.start} - ${s.end}（需 HH:mm 格式且开始早于结束）"
            }
            .toLeft(cfg)
    }

  /**
   * 核心判定（纯函数，注入 now 便于测试）。
   *
   * 规则：now 的当日分钟数 ∈ 任一 [start, end) → open（start 含、end 不含）。
   * nextChangeAt：所有段边界的并集中「严格晚于 now 的最小者」；今日无更晚边界
   * → 次日最早边界（跨日，如 23:50 → 次日 09:00）。注意 nextChangeAt 供前端
   * 展示与诊断，实际恢复以 FreezeScheduler 30s 重评估为准（D4）——重叠段的
   * 边界不一定是语义翻转点，但一定是下一个候选评估点。
   *
   * fail-safe：disabled / 空段 / 含非法段 → 恒 open、nextChangeAt=None。
   */
  def eval(cfg: WorkScheduleConfig, now: Long): WorkWindow =
    val parsed: List[(Int, Int)] = cfg.segments.flatMap { s =>
      (parseHHmm(s.start), parseHHmm(s.end)) match
        case (Some(a), Some(b)) if a < b => List((a, b))
        case _ => Nil
    }
    if !cfg.enabled || parsed.isEmpty then WorkWindow(open = true, nextChangeAt = None)
    else
      val zone = java.time.ZoneId.systemDefault()
      val zdt = java.time.Instant.ofEpochMilli(now).atZone(zone)
      val minuteOfDay = zdt.getHour * 60 + zdt.getMinute
      val open = parsed.exists((a, b) => minuteOfDay >= a && minuteOfDay < b)
      val boundaries = parsed.flatMap((a, b) => List(a, b)).distinct.sorted
      val nextMinute = boundaries.find(_ > minuteOfDay).getOrElse(boundaries.min + MinutePerDay)
      val midnightMillis = zdt.toLocalDate.atStartOfDay(zone).toInstant.toEpochMilli
      WorkWindow(open = open, nextChangeAt = Some(midnightMillis + nextMinute * 60_000L))

  /**
   * targeted write 的纯函数部分（照抄 persistThinkingConfig 的 read-merge-write
   * 语义，抽出便于 B9 测试）：在既有顶层配置上写入/覆写 workSchedule 节，其余
   * 顶层键原样保留。
   */
  def mergeIntoConfig(existing: Json, cfg: WorkScheduleConfig): Json =
    existing.asObject match
      case Some(obj) => Json.fromJsonObject(obj.add("workSchedule", cfg.asJson))
      case None => Json.obj("workSchedule" -> cfg.asJson)

end WorkSchedule
