package nebflow.core.schedule

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json}
import nebflow.core.AtomicJson

/**
 * 冻结调度（freeze-schedule，spec v1.2）：冻结时段模型 + 纯函数判定。
 *
 * **语义（#337，2026-08-19 23:16 用户澄清）**：配置的 segments 是**冻结时间
 * （非工作时间）——黑名单语义**。时段内 = 冻结（agent 不发下一轮 LLM），
 * 时段外 = 正常工作。此前 v1.1 的白名单语义（segments=工作时间）已废弃。
 *
 * nebflow.json 顶层 workSchedule 节（JSON 键名保留 "workSchedule"——前端契约，
 * 语义为冻结时段）：
 * {{{
 * "workSchedule": {
 *   "enabled": true,
 *   "segments": [ {"start": "23:00", "end": "08:00"} ]
 * }
 * }}}
 *
 * 段规则（D6 v2）：
 *  - 每日相同、HH:mm 段、start 含 end 不含（[start, end)）
 *  - start < end 常规段；**start > end 跨午夜段**（23:00-08:00 = [23:00,24:00)∪[00:00,08:00)）
 *  - start == end 非法（零长度按 [start,end) 覆盖 0 分钟、无意义；也**不**定义为
 *    24h 全冻结——误配即全停过于危险，validate 拒绝、load fail-safe 视为关闭）
 *  - 段重叠允许（判定取并集）
 *  - enabled 且 segments 为空视为非法（validate 拒绝、load fail-safe 视为关闭）
 *  - 默认 disabled——不开启时行为与现在 100% 一致
 */
case class FreezeSegment(start: String, end: String)

case class FreezeScheduleConfig(
  enabled: Boolean = false,
  segments: List[FreezeSegment] = Nil
)

/** eval 结果：frozen=当前是否在冻结时段内；nextChangeAt=下一次冻结/解冻的真实
  * 翻转点 epoch millis（严格晚于 now，全天冻结时 None）。 */
case class FreezeWindow(frozen: Boolean, nextChangeAt: Option[Long])

object FreezeSchedule:

  given Encoder[FreezeSegment] = Encoder.derived
  given Decoder[FreezeSegment] = Decoder.instance { c =>
    for
      start <- c.downField("start").as[String]
      end <- c.downField("end").as[String]
    yield FreezeSegment(start, end)
  }

  given Encoder[FreezeScheduleConfig] = Encoder.derived
  given Decoder[FreezeScheduleConfig] = Decoder.instance { c =>
    for
      enabled <- c.downField("enabled").as[Option[Boolean]].map(_.getOrElse(false))
      segments <- c.downField("segments").as[Option[List[FreezeSegment]]].map(_.getOrElse(Nil))
    yield FreezeScheduleConfig(enabled, segments)
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

  /** 段合法 = 两端 HH:mm 且 start != end（start > end = 跨午夜段，合法）。 */
  private def validSegment(s: FreezeSegment): Boolean =
    (parseHHmm(s.start), parseHHmm(s.end)) match
      case (Some(a), Some(b)) => a != b
      case _ => false

  /**
   * fail-safe 加载（B8）：decode 失败 / enabled 且 segments 空 / 任一段非法
   * → disabled（恒不冻结，功能关闭语义）。disabled + 合法段 → 保留段（UI 回显
   * round-trip：用户关掉开关不应丢掉已配时段）。
   */
  def load(json: Option[Json]): FreezeScheduleConfig =
    json
      .flatMap(j => j.as[FreezeScheduleConfig].toOption)
      .map { cfg =>
        val allValid = cfg.segments.forall(validSegment)
        if !allValid then FreezeScheduleConfig(enabled = false)
        else if cfg.enabled && cfg.segments.isEmpty then FreezeScheduleConfig(enabled = false)
        else cfg
      }
      .getOrElse(FreezeScheduleConfig())

  /**
   * setWorkSchedule WS 命令的校验入口（B9）：非法 payload → Left(错误消息)，
   * 调用方拒绝保存并回 configUpdateFailed。规则：每段 HH:mm 格式且 start != end
   * （可跨午夜）；enabled 时至少一段。
   */
  def validate(json: Json): Either[String, FreezeScheduleConfig] =
    json.as[FreezeScheduleConfig].left.map(_ => "冻结时间配置格式错误：需要 {enabled, segments:[{start,end}]}").flatMap {
      cfg =>
        if cfg.segments.isEmpty then
          if cfg.enabled then Left("开启冻结调度需要至少一个时间段") else Right(cfg)
        else
          cfg.segments
            .collectFirst {
              case s if !validSegment(s) =>
                s"时间段无效：${s.start} - ${s.end}（需 HH:mm 格式且开始≠结束；跨午夜用开始>结束，如 23:00-08:00）"
            }
            .toLeft(cfg)
    }

  /** 段是否覆盖当日第 m 分钟（start 含、end 不含）。a > b = 跨午夜环绕段。 */
  private def covers(a: Int, b: Int, m: Int): Boolean =
    if a < b then m >= a && m < b
    else m >= a || m < b

  /**
   * 核心判定（纯函数，注入 now 便于测试）。
   *
   * 规则（黑名单语义）：now 的当日分钟数 ∈ 任一段覆盖范围 → frozen；
   * 段外 → 不冻结（正常工作）。
   *
   * nextChangeAt = **下一次真实语义翻转点**（严格晚于 now）：按时间序扫描未来
   * 段边界（今日更晚者，其次明日全部），第一个使 frozen 态与当前不同的边界。
   * 重叠段并集的中间边界不是翻转点——精确扫描保证 resumeAt 恒指向真实恢复
   * （或下一次冻结）时刻、永指未来（#337 P3；实际恢复仍以 FreezeScheduler
   * 30s 重评估为准，D4）。全时段覆盖（24h 全冻结）无翻转点 → None。
   *
   * fail-safe：disabled / 空段 / 含非法段 → 恒不冻结、nextChangeAt=None。
   */
  def eval(cfg: FreezeScheduleConfig, now: Long): FreezeWindow =
    val parsed: List[(Int, Int)] = cfg.segments.flatMap { s =>
      (parseHHmm(s.start), parseHHmm(s.end)) match
        case (Some(a), Some(b)) if a != b => List((a, b))
        case _ => Nil
    }
    if !cfg.enabled || parsed.isEmpty then FreezeWindow(frozen = false, nextChangeAt = None)
    else
      val zone = java.time.ZoneId.systemDefault()
      val zdt = java.time.Instant.ofEpochMilli(now).atZone(zone)
      val minuteOfDay = zdt.getHour * 60 + zdt.getMinute
      val frozenNow = parsed.exists((a, b) => covers(a, b, minuteOfDay))
      val boundaries = parsed.flatMap((a, b) => List(a, b)).distinct.sorted
      val candidates = boundaries.filter(_ > minuteOfDay) ++ boundaries.map(_ + MinutePerDay)
      def frozenAt(m: Int): Boolean = parsed.exists((a, b) => covers(a, b, m % MinutePerDay))
      val flip = candidates.find(c => frozenAt(c) != frozenNow)
      val midnightMillis = zdt.toLocalDate.atStartOfDay(zone).toInstant.toEpochMilli
      FreezeWindow(
        frozen = frozenNow,
        nextChangeAt = flip.map(c => midnightMillis + c * 60_000L)
      )

  /**
   * targeted write 的纯函数部分（照抄 persistThinkingConfig 的 read-merge-write
   * 语义，抽出便于 B9 测试）：在既有顶层配置上写入/覆写 workSchedule 节（JSON
   * 键名保留前端契约），其余顶层键原样保留。
   */
  def mergeIntoConfig(existing: Json, cfg: FreezeScheduleConfig): Json =
    existing.asObject match
      case Some(obj) => Json.fromJsonObject(obj.add("workSchedule", cfg.asJson))
      case None => Json.obj("workSchedule" -> cfg.asJson)

  /**
   * 用户消息全局跳过当前冻结窗口（2026-08-25 用户裁定）：窗口内任何用户消息
   * 到达 → 本次冻结整体作废（所有 agent 恢复正常工作），**不存在部分唤醒**；
   * 跳过非永久——skipUntil 到期（= 当前冻结窗口结束时刻）后下一冻结段照常冻结。
   * 纯函数：window.frozen 且 skipUntil 未到期 → 视为不冻结（其余字段原样）。
   */
  def applySkip(window: FreezeWindow, skipUntil: Option[Long], now: Long): FreezeWindow =
    if window.frozen && skipUntil.exists(_ > now) then window.copy(frozen = false) else window

  /** eval + applySkip 的组合入口（gate / CheckFreezeGate / 用户消息钩子共用）。 */
  def evalWithSkip(cfg: FreezeScheduleConfig, skipUntil: Option[Long], now: Long): FreezeWindow =
    applySkip(eval(cfg, now), skipUntil, now)

  /** 全局冻结状态节点（现象 2 契约补全 2026-08-30）：serverConfig 广播携带，
    * 前端输入栏禁用/冻结提示的状态机直接读它（WS 连接初始态、配置热更、skipFreeze
    * 后均即时刷新）。字段：
    *   enabled      — 配置是否开启
    *   frozen       — 当前是否处于冻结窗口（含 skip 语义：被跳过的窗口 = false）
    *   skipped      — 当前窗口是否被 skipFreeze 跳过（配置开启且窗口本应冻结）
    *   nextChangeAt — 下一翻转点 epoch ms（工作态=下一冻结开始；None=无翻转）
    *   segments     — 冻结段列表（供前端展示「下一段 HH:mm」，与 workSchedule 一致）
    */
  def freezeStateNode(cfg: FreezeScheduleConfig, skipUntil: Option[Long], now: Long): io.circe.Json =
    val raw = eval(cfg, now)
    val window = applySkip(raw, skipUntil, now)
    io.circe.Json.obj(
      "enabled" -> cfg.enabled.asJson,
      "frozen" -> window.frozen.asJson,
      "skipped" -> (cfg.enabled && raw.frozen && !window.frozen).asJson,
      "nextChangeAt" -> window.nextChangeAt.asJson,
      "segments" -> cfg.segments.asJson
    )

  /** 用户消息到达时计算 skipUntil：当前冻结窗口的结束时刻（eval 的 nextChangeAt，
    * 即下一次真实翻转点）；全天冻结无翻转点（None）时兜底为下一个午夜——跳过
    * 今天剩余时段，明天照常冻结。 */
  def skipUntilFor(window: FreezeWindow, now: Long): Option[Long] =
    window.nextChangeAt.orElse {
      val zone = java.time.ZoneId.systemDefault()
      Some(
        java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate.plusDays(1).atStartOfDay(zone).toInstant.toEpochMilli
      )
    }

  // ============================================================
  // skip 持久化（2026-08-30 P0：skip 状态非持久化——「点跳过 → 刷新/重启 →
  // 输入框重新冻结」）。skipUntil 虽是运行时态（时间戳），但必须跨进程重启存活。
  // 独立文件 <dataRoot>/freeze-skip.json，不与 nebflow.json 配置面交互——
  // toggle off/on 走 setWorkSchedule（改 cfg.enabled），与 skip 天然独立不冲突。
  // 持久化不改变「跳过非永久」语义：到期由 applySkip 的 >now 判断自然失效；
  // 启动加载时过期值由调用方清理。内容 {"skipUntil": <epoch millis>}。
  // ============================================================

  def skipPersistPath(root: os.Path): os.Path = root / "freeze-skip.json"

  /** 写盘（Some → 原子写）或删盘（None → 移除残留）。调用方 best-effort。 */
  def persistSkip(root: os.Path, skipUntil: Option[Long]): IO[Unit] =
    skipUntil match
      case Some(t) => AtomicJson.write(skipPersistPath(root), Json.obj("skipUntil" -> t.asJson).noSpaces)
      case None =>
        IO.blocking {
          if os.exists(skipPersistPath(root)) then os.remove(skipPersistPath(root))
        }

  /** 启动加载：读盘原始值（不过滤过期——调用方按 >now 判断）。缺失/损坏 → None。 */
  def loadSkipUntil(root: os.Path): IO[Option[Long]] =
    IO.blocking {
      val p = skipPersistPath(root)
      if os.exists(p) then
        io.circe.parser.parse(os.read(p)).toOption
          .flatMap(_.hcursor.downField("skipUntil").as[Long].toOption)
      else None
    }

  /**
   * setWorkSchedule 部分更新合并（2026-08-25 裁定：设置关闭保留配置）：payload
   * 缺 enabled / segments 键时从现有配置继承——toggle off 只置 disabled、segments
   * 不清空不重置；显式传 segments=[] 仍可清空。解析错误 → Left（拒绝保存，与
   * 全量 payload 的错误语义一致）。
   */
  def mergeConfig(existing: FreezeScheduleConfig, patch: Json): Either[String, Json] =
    patch.asObject match
      case Some(obj) =>
        for
          enabled <- obj("enabled") match
            case None => Right(existing.enabled)
            case Some(e) => e.asBoolean.toRight("enabled 必须是布尔值")
          segments <- obj("segments") match
            case None => Right(existing.segments)
            case Some(s) =>
              s.as[List[FreezeSegment]].toOption.toRight("segments 格式错误：[{start,end}]")
        yield FreezeScheduleConfig(enabled, segments).asJson
      case None => Left("冻结时间配置格式错误：需要 {enabled, segments:[{start,end}]}")

  /** mergeConfig + validate 的组合入口（setWorkSchedule WS 命令用）。 */
  def mergeValidate(existing: FreezeScheduleConfig, patch: Json): Either[String, FreezeScheduleConfig] =
    mergeConfig(existing, patch).flatMap(validate)

end FreezeSchedule
