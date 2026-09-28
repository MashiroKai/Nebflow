package nebflow.core.schedule

import io.circe.parser.parse
import io.circe.syntax.*
import cats.effect.unsafe.implicits.global
import FreezeSchedule.given
import munit.FunSuite

/** freeze-schedule spec §6 B8/B9（#337 v1.2 黑名单语义）：FreezeSchedule 纯函数
  * 判定 + fail-safe + 校验 + merge。segments = 冻结时段（非工作时间），段内
  * frozen=true；支持跨午夜段（start > end）；nextChangeAt = 下一次真实翻转点。 */
class FreezeScheduleSpec extends FunSuite:

  // 固定日期（2026-08-19 周三）注入 now——eval 用系统时区，测试同侧构造，口径一致。
  private def at(h: Int, m: Int): Long =
    java.time.LocalDate.of(2026, 8, 19)
      .atTime(h, m)
      .atZone(java.time.ZoneId.systemDefault())
      .toInstant
      .toEpochMilli

  private def atTomorrow(h: Int, m: Int): Long =
    java.time.LocalDate.of(2026, 8, 20)
      .atTime(h, m)
      .atZone(java.time.ZoneId.systemDefault())
      .toInstant
      .toEpochMilli

  /** 白天双冻结段（用户场景：午休 + 晚间；黑名单语义=这两段内冻结）。 */
  private val twoSegment =
    FreezeScheduleConfig(
      enabled = true,
      segments = List(
        FreezeSegment("09:00", "12:00"),
        FreezeSegment("14:00", "18:00")
      )
    )

  /** 跨午夜段：23:00-08:00（用户核心场景，#337 P2）。 */
  private val overnight =
    FreezeScheduleConfig(enabled = true, segments = List(FreezeSegment("23:00", "08:00")))

  // ── parseHHmm ─────────────────────────────────────────────

  test("parseHHmm accepts valid HH:mm") {
    assertEquals(FreezeSchedule.parseHHmm("09:00"), Some(540))
    assertEquals(FreezeSchedule.parseHHmm("00:00"), Some(0))
    assertEquals(FreezeSchedule.parseHHmm("23:59"), Some(1439))
    assertEquals(FreezeSchedule.parseHHmm("12:30"), Some(750))
  }

  test("parseHHmm rejects invalid formats") {
    assertEquals(FreezeSchedule.parseHHmm("24:00"), None) // hour out of range
    assertEquals(FreezeSchedule.parseHHmm("9:00"), None) // no leading zero
    assertEquals(FreezeSchedule.parseHHmm("09:6"), None) // minute needs two digits
    assertEquals(FreezeSchedule.parseHHmm("0960"), None) // missing colon
    assertEquals(FreezeSchedule.parseHHmm(""), None)
    assertEquals(FreezeSchedule.parseHHmm("abc"), None)
    assertEquals(FreezeSchedule.parseHHmm("09:60"), None) // minute out of range
    assertEquals(FreezeSchedule.parseHHmm(null), None)
  }

  // ── eval: 黑名单语义（段内冻结，段外工作）────────────────

  test("eval: inside segment is frozen (#337 blacklist)") {
    assert(FreezeSchedule.eval(twoSegment, at(10, 30)).frozen)
    assert(FreezeSchedule.eval(twoSegment, at(15, 0)).frozen)
  }

  test("eval: outside all segments is NOT frozen (work time)") {
    assert(!FreezeSchedule.eval(twoSegment, at(8, 59)).frozen)
    assert(!FreezeSchedule.eval(twoSegment, at(13, 0)).frozen)
    assert(!FreezeSchedule.eval(twoSegment, at(23, 0)).frozen)
  }

  test("eval: boundary start inclusive (09:00 frozen, 14:00 frozen)") {
    assert(FreezeSchedule.eval(twoSegment, at(9, 0)).frozen)
    assert(FreezeSchedule.eval(twoSegment, at(14, 0)).frozen)
  }

  test("eval: boundary end exclusive (12:00 not frozen, 18:00 not frozen)") {
    assert(!FreezeSchedule.eval(twoSegment, at(12, 0)).frozen)
    assert(!FreezeSchedule.eval(twoSegment, at(18, 0)).frozen)
  }

  test("eval: multi-segment union") {
    assert(FreezeSchedule.eval(twoSegment, at(11, 59)).frozen)
    assert(!FreezeSchedule.eval(twoSegment, at(12, 0)).frozen)
    assert(FreezeSchedule.eval(twoSegment, at(14, 0)).frozen)
  }

  // ── eval: 跨午夜段（#337 P2）─────────────────────────────

  test("eval overnight segment: evening part frozen (23:00-08:00)") {
    assert(FreezeSchedule.eval(overnight, at(23, 0)).frozen) // start boundary
    assert(FreezeSchedule.eval(overnight, at(23, 30)).frozen)
    assert(FreezeSchedule.eval(overnight, at(23, 59)).frozen)
  }

  test("eval overnight segment: early-morning part frozen") {
    assert(FreezeSchedule.eval(overnight, at(0, 0)).frozen)
    assert(FreezeSchedule.eval(overnight, at(2, 30)).frozen)
    assert(FreezeSchedule.eval(overnight, at(7, 59)).frozen)
  }

  test("eval overnight segment: daytime NOT frozen") {
    assert(!FreezeSchedule.eval(overnight, at(8, 0)).frozen) // end boundary
    assert(!FreezeSchedule.eval(overnight, at(12, 0)).frozen)
    assert(!FreezeSchedule.eval(overnight, at(22, 59)).frozen)
  }

  // ── eval: disabled / fail-safe ────────────────────────────

  test("eval: disabled is never frozen with no nextChangeAt") {
    val w1 = FreezeSchedule.eval(FreezeScheduleConfig(enabled = false, segments = twoSegment.segments), at(3, 0))
    assert(!w1.frozen)
    assertEquals(w1.nextChangeAt, None)
    val w2 = FreezeSchedule.eval(FreezeScheduleConfig(), at(23, 59))
    assert(!w2.frozen)
    assertEquals(w2.nextChangeAt, None)
  }

  test("eval: overlapping segments take the union") {
    val overlap = FreezeScheduleConfig(
      enabled = true,
      segments = List(FreezeSegment("09:00", "13:00"), FreezeSegment("12:00", "15:00"))
    )
    assert(FreezeSchedule.eval(overlap, at(12, 30)).frozen)
  }

  test("eval: all-day coverage (two segments spanning 24h) frozen everywhere, nextChangeAt None") {
    val allDay = FreezeScheduleConfig(
      enabled = true,
      segments = List(FreezeSegment("00:00", "12:00"), FreezeSegment("12:00", "00:00"))
    )
    assert(FreezeSchedule.eval(allDay, at(3, 0)).frozen)
    assert(FreezeSchedule.eval(allDay, at(15, 0)).frozen)
    assertEquals(FreezeSchedule.eval(allDay, at(3, 0)).nextChangeAt, None)
  }

  // ── eval: nextChangeAt = 下一次真实翻转点（#337 P3）──────

  test("nextChangeAt: frozen mid-morning → resumes at segment end (12:00)") {
    assertEquals(FreezeSchedule.eval(twoSegment, at(10, 30)).nextChangeAt, Some(at(12, 0)))
  }

  test("nextChangeAt: open midday gap → next freeze start (14:00)") {
    assertEquals(FreezeSchedule.eval(twoSegment, at(13, 0)).nextChangeAt, Some(at(14, 0)))
  }

  test("nextChangeAt: open before first segment → today's first start (09:00)") {
    assertEquals(FreezeSchedule.eval(twoSegment, at(6, 0)).nextChangeAt, Some(at(9, 0)))
  }

  test("nextChangeAt: open after last end → tomorrow's first boundary (09:00)") {
    assertEquals(FreezeSchedule.eval(twoSegment, at(19, 0)).nextChangeAt, Some(atTomorrow(9, 0)))
  }

  test("nextChangeAt: frozen late evening in overnight segment → tomorrow end (08:00)") {
    assertEquals(FreezeSchedule.eval(overnight, at(23, 30)).nextChangeAt, Some(atTomorrow(8, 0)))
  }

  test("nextChangeAt: frozen early morning in overnight segment → today end (08:00)") {
    assertEquals(FreezeSchedule.eval(overnight, at(2, 0)).nextChangeAt, Some(at(8, 0)))
  }

  test("nextChangeAt: open daytime in overnight segment → today freeze start (23:00)") {
    assertEquals(FreezeSchedule.eval(overnight, at(12, 0)).nextChangeAt, Some(at(23, 0)))
  }

  test("nextChangeAt: exact flip — overlapping segments skip inner boundary (resume 15:00, not 13:00)") {
    val overlap = FreezeScheduleConfig(
      enabled = true,
      segments = List(FreezeSegment("09:00", "13:00"), FreezeSegment("12:00", "15:00"))
    )
    // 12:30 处于并集 [09:00,15:00) 内：13:00 不是翻转点（12:00-15:00 仍冻结）
    assertEquals(FreezeSchedule.eval(overlap, at(12, 30)).nextChangeAt, Some(at(15, 0)))
  }

  test("nextChangeAt invariant: strictly in the future for every sampled minute (#337 P3)") {
    val cfgs = List(twoSegment, overnight)
    // 每 37 分钟采样 26 小时（跨日），断言翻转点恒严格晚于 now
    val base = at(0, 0)
    val samples = (0 until 26 * 60 by 37).map(min => base + min * 60_000L)
    for cfg <- cfgs; now <- samples do
      val w = FreezeSchedule.eval(cfg, now)
      w.nextChangeAt.foreach { t =>
        assert(t > now, s"nextChangeAt must be strictly future: cfg=$cfg now=$now flip=$t")
      }
  }

  // ── load: fail-safe (B8) ──────────────────────────────────

  test("load: valid json preserves config (incl. overnight segment)") {
    val json = parse("""{"enabled":true,"segments":[{"start":"23:00","end":"08:00"}]}""").toOption
    val cfg = FreezeSchedule.load(json)
    assert(cfg.enabled)
    assertEquals(cfg.segments, List(FreezeSegment("23:00", "08:00")))
  }

  test("load: None / garbage json → disabled") {
    assertEquals(FreezeSchedule.load(None), FreezeScheduleConfig())
    val garbage = parse("""{"enabled":"yes!!!","segments":"not-a-list"}""").toOption
    assertEquals(FreezeSchedule.load(garbage), FreezeScheduleConfig())
  }

  test("load: enabled with empty segments → fail-safe disabled") {
    val json = parse("""{"enabled":true,"segments":[]}""").toOption
    assertEquals(FreezeSchedule.load(json), FreezeScheduleConfig(enabled = false))
  }

  test("load: enabled with invalid segment → fail-safe disabled (bad format / start==end)") {
    val json = parse("""{"enabled":true,"segments":[{"start":"9:00","end":"12:00"}]}""").toOption
    assertEquals(FreezeSchedule.load(json), FreezeScheduleConfig(enabled = false))
    // #337 P2：start==end 仍非法（零长度段无意义，也不定义为 24h 全冻结——防误配全停）
    val json2 = parse("""{"enabled":true,"segments":[{"start":"09:00","end":"09:00"}]}""").toOption
    assertEquals(FreezeSchedule.load(json2), FreezeScheduleConfig(enabled = false))
  }

  test("load: disabled with valid segments keeps segments (UI round-trip)") {
    val json = parse("""{"enabled":false,"segments":[{"start":"23:00","end":"08:00"}]}""").toOption
    val cfg = FreezeSchedule.load(json)
    assert(!cfg.enabled)
    assertEquals(cfg.segments, List(FreezeSegment("23:00", "08:00")))
  }

  // ── validate: setWorkSchedule 入口 (B9) ───────────────────

  test("validate: valid payload → Right (incl. cross-midnight)") {
    val json = parse("""{"enabled":true,"segments":[{"start":"09:00","end":"12:00"}]}""").toOption.get
    assert(FreezeSchedule.validate(json).isRight)
    val overnightJson = parse("""{"enabled":true,"segments":[{"start":"23:00","end":"08:00"}]}""").toOption.get
    assert(FreezeSchedule.validate(overnightJson).isRight)
  }

  test("validate: start == end rejected (zero-length)") {
    val json = parse("""{"enabled":true,"segments":[{"start":"12:00","end":"12:00"}]}""").toOption.get
    assert(FreezeSchedule.validate(json).isLeft)
  }

  test("validate: start > end accepted (overnight, #337 P2)") {
    val json = parse("""{"enabled":true,"segments":[{"start":"12:00","end":"09:00"}]}""").toOption.get
    assert(FreezeSchedule.validate(json).isRight)
  }

  test("validate: enabled with empty segments rejected") {
    val json = parse("""{"enabled":true,"segments":[]}""").toOption.get
    assert(FreezeSchedule.validate(json).isLeft)
  }

  test("validate: bad HH:mm format rejected") {
    val json = parse("""{"enabled":true,"segments":[{"start":"0900","end":"12:00"}]}""").toOption.get
    assert(FreezeSchedule.validate(json).isLeft)
  }

  test("validate: disabled with empty segments accepted (turning off)") {
    val json = parse("""{"enabled":false,"segments":[]}""").toOption.get
    assert(FreezeSchedule.validate(json).isRight)
  }

  // ── mergeIntoConfig: targeted write 语义 (B9，P4 落盘复证) ─

  test("mergeIntoConfig adds workSchedule node and preserves other top-level keys") {
    val existing = parse("""{"llm":{"providers":{"glm":{"apiKey":"sk-x"}}},"thinkingConfig":{"enabled":true}}""").toOption.get
    val cfg = FreezeScheduleConfig(enabled = true, segments = List(FreezeSegment("23:00", "08:00")))
    val merged = FreezeSchedule.mergeIntoConfig(existing, cfg)
    // 既有顶层节原样保留
    assertEquals(merged.hcursor.downField("llm").downField("providers").downField("glm").downField("apiKey").as[String], Right("sk-x"))
    assertEquals(merged.hcursor.downField("thinkingConfig").downField("enabled").as[Boolean], Right(true))
    // workSchedule 写入（JSON 键名保留）且 round-trip 一致
    assertEquals(merged.hcursor.downField("workSchedule").as[FreezeScheduleConfig], Right(cfg))
  }

  test("mergeIntoConfig overwrites existing workSchedule node") {
    val existing = parse("""{"workSchedule":{"enabled":true,"segments":[{"start":"08:00","end":"09:00"}]},"x":1}""").toOption.get
    val cfg = FreezeScheduleConfig(enabled = false, segments = Nil)
    val merged = FreezeSchedule.mergeIntoConfig(existing, cfg)
    assertEquals(merged.hcursor.downField("workSchedule").as[FreezeScheduleConfig], Right(cfg))
    assertEquals(merged.hcursor.downField("x").as[Int], Right(1))
  }

  // ── 2026-08-25 用户消息全局跳过：applySkip / evalWithSkip / skipUntilFor ─

  test("applySkip: frozen + unexpired skipUntil → NOT frozen (window voided)") {
    val window = FreezeSchedule.eval(twoSegment, at(10, 30)) // frozen, nextChangeAt 12:00
    val skipped = FreezeSchedule.applySkip(window, Some(at(12, 0)), at(10, 30))
    assert(!skipped.frozen)
  }

  test("applySkip: frozen + expired skipUntil → still frozen (next segment normal)") {
    val now = at(10, 30)
    // skip 来自昨天 12:00（上一窗口结束）——已过期 → 本次窗口照常冻结
    val window = FreezeSchedule.eval(twoSegment, now)
    val skipped = FreezeSchedule.applySkip(window, Some(at(12, 0) - 24 * 3600_000L), now)
    assert(skipped.frozen)
  }

  test("applySkip: not frozen → unchanged (nextChangeAt preserved)") {
    val window = FreezeSchedule.eval(twoSegment, at(13, 0)) // 13:00 段外
    val skipped = FreezeSchedule.applySkip(window, Some(at(18, 0)), at(13, 0))
    assert(!skipped.frozen)
    assertEquals(skipped.nextChangeAt, Some(at(14, 0)))
  }

  test("evalWithSkip: skip applies through the combined entry") {
    val w = FreezeSchedule.evalWithSkip(twoSegment, Some(at(12, 0)), at(10, 30))
    assert(!w.frozen)
    assertEquals(w.nextChangeAt, Some(at(12, 0)))
  }

  test("evalWithSkip: skip expired → frozen again (skip is not permanent)") {
    val w = FreezeSchedule.evalWithSkip(twoSegment, Some(at(12, 0)), at(12, 1))
    assert(!w.frozen) // 12:01 本就段外（[09:00,12:00) 含头不含尾）
    // 下一冻结段 14:00 起照常冻结
    val w2 = FreezeSchedule.evalWithSkip(twoSegment, Some(at(12, 0)), at(14, 0))
    assert(w2.frozen)
  }

  test("skipUntilFor: returns the window end (nextChangeAt)") {
    val window = FreezeSchedule.eval(twoSegment, at(10, 30))
    assertEquals(FreezeSchedule.skipUntilFor(window, at(10, 30)), Some(at(12, 0)))
    val overnightWindow = FreezeSchedule.eval(overnight, at(23, 30))
    assertEquals(FreezeSchedule.skipUntilFor(overnightWindow, at(23, 30)), Some(atTomorrow(8, 0)))
  }

  test("skipUntilFor: all-day coverage (no flip) falls back to next midnight") {
    val allDay = FreezeScheduleConfig(
      enabled = true,
      segments = List(FreezeSegment("00:00", "12:00"), FreezeSegment("12:00", "00:00"))
    )
    val window = FreezeSchedule.eval(allDay, at(3, 0))
    assertEquals(window.nextChangeAt, None)
    val until = FreezeSchedule.skipUntilFor(window, at(3, 0))
    assertEquals(until, Some(atTomorrow(0, 0)))
  }

  // ── 2026-08-25 设置关闭保留配置：mergeConfig / mergeValidate ─

  test("mergeValidate: toggle-off (enabled only) keeps existing segments") {
    val existing = FreezeScheduleConfig(enabled = true, segments = List(FreezeSegment("14:00", "18:00")))
    val patch = parse("""{"enabled":false}""").toOption.get
    val merged = FreezeSchedule.mergeValidate(existing, patch)
    assertEquals(merged, Right(FreezeScheduleConfig(enabled = false, segments = List(FreezeSegment("14:00", "18:00")))))
  }

  test("mergeValidate: re-enable (enabled only) keeps existing segments") {
    val existing = FreezeScheduleConfig(enabled = false, segments = List(FreezeSegment("14:00", "18:00")))
    val patch = parse("""{"enabled":true}""").toOption.get
    val merged = FreezeSchedule.mergeValidate(existing, patch)
    assertEquals(merged, Right(FreezeScheduleConfig(enabled = true, segments = List(FreezeSegment("14:00", "18:00")))))
  }

  test("mergeValidate: segments-only patch keeps existing enabled") {
    val existing = FreezeScheduleConfig(enabled = true, segments = List(FreezeSegment("09:00", "12:00")))
    val patch = parse("""{"segments":[{"start":"23:00","end":"08:00"}]}""").toOption.get
    val merged = FreezeSchedule.mergeValidate(existing, patch)
    assertEquals(merged, Right(FreezeScheduleConfig(enabled = true, segments = List(FreezeSegment("23:00", "08:00")))))
  }

  test("mergeValidate: explicit segments=[] still clears (user intent)") {
    val existing = FreezeScheduleConfig(enabled = false, segments = List(FreezeSegment("14:00", "18:00")))
    val patch = parse("""{"enabled":false,"segments":[]}""").toOption.get
    val merged = FreezeSchedule.mergeValidate(existing, patch)
    assertEquals(merged, Right(FreezeScheduleConfig(enabled = false, segments = Nil)))
  }

  test("mergeValidate: full payload replaces both (no inheritance needed)") {
    val existing = FreezeScheduleConfig(enabled = false, segments = List(FreezeSegment("14:00", "18:00")))
    val patch = parse("""{"enabled":true,"segments":[{"start":"23:00","end":"08:00"}]}""").toOption.get
    val merged = FreezeSchedule.mergeValidate(existing, patch)
    assertEquals(merged, Right(FreezeScheduleConfig(enabled = true, segments = List(FreezeSegment("23:00", "08:00")))))
  }

  test("mergeValidate: re-enable with no segments → rejected (needs at least one)") {
    val existing = FreezeScheduleConfig(enabled = false, segments = Nil)
    val patch = parse("""{"enabled":true}""").toOption.get
    assert(FreezeSchedule.mergeValidate(existing, patch).isLeft)
  }

  test("mergeValidate: malformed payload rejected (bad enabled type / bad segments)") {
    val existing = FreezeScheduleConfig(enabled = true, segments = List(FreezeSegment("14:00", "18:00")))
    assert(FreezeSchedule.mergeValidate(existing, parse("""{"enabled":"yes"}""").toOption.get).isLeft)
    assert(FreezeSchedule.mergeValidate(existing, parse("""{"segments":"nope"}""").toOption.get).isLeft)
    assert(FreezeSchedule.mergeValidate(existing, parse("""not-json""").toOption.getOrElse(io.circe.Json.Null)).isLeft)
  }

  // ── freezeStateNode（现象 2 契约 2026-08-30）────────────────

  private def node(cfg: FreezeScheduleConfig, skipUntil: Option[Long], now: Long): io.circe.Json =
    FreezeSchedule.freezeStateNode(cfg, skipUntil, now)

  private def nodeField(j: io.circe.Json, f: String): Option[io.circe.Json] =
    j.hcursor.downField(f).focus

  test("freezeStateNode: enabled + in-window → frozen=true, nextChangeAt=窗口结束, segments 携带") {
    val j = node(twoSegment, None, at(10, 0))
    assertEquals(nodeField(j, "enabled").flatMap(_.asBoolean), Some(true))
    assertEquals(nodeField(j, "frozen").flatMap(_.asBoolean), Some(true))
    assertEquals(nodeField(j, "skipped").flatMap(_.asBoolean), Some(false))
    assertEquals(nodeField(j, "nextChangeAt").flatMap(_.asNumber).flatMap(_.toLong), Some(at(12, 0)))
    // 段信息：与 workSchedule 一致（前端「下一段 HH:mm」展示直接读节点，无需另解析）
    assertEquals(nodeField(j, "segments").flatMap(_.asArray).map(_.size), Some(2))
  }

  test("freezeStateNode: enabled + outside window → frozen=false, nextChangeAt=下一冻结开始") {
    val j = node(twoSegment, None, at(13, 0))
    assertEquals(nodeField(j, "enabled").flatMap(_.asBoolean), Some(true))
    assertEquals(nodeField(j, "frozen").flatMap(_.asBoolean), Some(false))
    assertEquals(nodeField(j, "skipped").flatMap(_.asBoolean), Some(false))
    assertEquals(nodeField(j, "nextChangeAt").flatMap(_.asNumber).flatMap(_.toLong), Some(at(14, 0)))
  }

  test("freezeStateNode: skipped window → frozen=false, skipped=true, nextChangeAt 保持窗口结束") {
    // skipUntil 落在窗口内 → applySkip 把 frozen 翻 false；nextChangeAt 保持 raw 窗口结束
    val j = node(twoSegment, Some(at(11, 0)), at(10, 0))
    assertEquals(nodeField(j, "enabled").flatMap(_.asBoolean), Some(true))
    assertEquals(nodeField(j, "frozen").flatMap(_.asBoolean), Some(false))
    assertEquals(nodeField(j, "skipped").flatMap(_.asBoolean), Some(true))
    assertEquals(nodeField(j, "nextChangeAt").flatMap(_.asNumber).flatMap(_.toLong), Some(at(12, 0)))
  }

  test("freezeStateNode: disabled → frozen=false, skipped=false, nextChangeAt=null") {
    val j = node(FreezeScheduleConfig(enabled = false, segments = List(FreezeSegment("09:00", "12:00"))), None, at(10, 0))
    assertEquals(nodeField(j, "enabled").flatMap(_.asBoolean), Some(false))
    assertEquals(nodeField(j, "frozen").flatMap(_.asBoolean), Some(false))
    assertEquals(nodeField(j, "skipped").flatMap(_.asBoolean), Some(false))
    assertEquals(nodeField(j, "nextChangeAt").map(_.isNull), Some(true))
  }

  test("freezeStateNode: 跨午夜段 in-window → frozen=true（黑名单语义）") {
    val j = node(overnight, None, at(2, 0))
    assertEquals(nodeField(j, "frozen").flatMap(_.asBoolean), Some(true))
    assertEquals(nodeField(j, "skipped").flatMap(_.asBoolean), Some(false))
  }

  // ── skip 持久化（P0 2026-08-30：skip 状态非持久化——「点跳过→刷新/重启→
  // 输入框重新冻结」；修=独立文件 freeze-skip.json，启动恢复、到期清理）────

  test("skip persist round-trip: write → load 原值; None → 删除残留") {
    val root = os.temp.dir(prefix = "nb-skip-persist-")
    try
      val t = at(11, 0)
      FreezeSchedule.persistSkip(root, Some(t)).unsafeRunSync()
      assertEquals(FreezeSchedule.loadSkipUntil(root).unsafeRunSync(), Some(t))
      assertEquals(os.exists(FreezeSchedule.skipPersistPath(root)), true)
      FreezeSchedule.persistSkip(root, None).unsafeRunSync()
      assertEquals(FreezeSchedule.loadSkipUntil(root).unsafeRunSync(), None)
      assertEquals(os.exists(FreezeSchedule.skipPersistPath(root)), false)
    finally os.remove.all(root)
  }

  test("skip persist: 损坏/缺失文件 → None 不炸（fail-safe 加载）") {
    val root = os.temp.dir(prefix = "nb-skip-persist-")
    try
      os.write(FreezeSchedule.skipPersistPath(root), "not-json{{{")
      assertEquals(FreezeSchedule.loadSkipUntil(root).unsafeRunSync(), None)
    finally os.remove.all(root)
  }

  test("skip persist: load 不过滤过期——>now 判断与清理由调用方（GatewayMain）负责") {
    // 语义钉：loadSkipUntil 返回原始值；「过期 → 丢弃+删盘」是启动加载方
    // （t > now 判断）的职责，持久化层不隐式改变「跳过非永久」语义。
    val root = os.temp.dir(prefix = "nb-skip-persist-")
    try
      val expired = at(9, 0) // 相对 at(10, 0) 已过期
      FreezeSchedule.persistSkip(root, Some(expired)).unsafeRunSync()
      assertEquals(FreezeSchedule.loadSkipUntil(root).unsafeRunSync(), Some(expired))
    finally os.remove.all(root)
  }

  test("skip persist: persistSkip 写盘文件内容为 {skipUntil: epoch}") {
    val root = os.temp.dir(prefix = "nb-skip-persist-")
    try
      val t = at(11, 0)
      FreezeSchedule.persistSkip(root, Some(t)).unsafeRunSync()
      val parsed = parse(os.read(FreezeSchedule.skipPersistPath(root))).toOption
      assertEquals(
        parsed.flatMap(_.hcursor.downField("skipUntil").as[Long].toOption),
        Some(t)
      )
    finally os.remove.all(root)
  }

  // ── codec round-trip ──────────────────────────────────────

  test("codec round-trip (incl. overnight segment)") {
    val cfg = FreezeScheduleConfig(
      enabled = true,
      segments = List(FreezeSegment("23:00", "08:00"), FreezeSegment("14:00", "18:00"))
    )
    assertEquals(cfg.asJson.as[FreezeScheduleConfig], Right(cfg))
  }

end FreezeScheduleSpec
