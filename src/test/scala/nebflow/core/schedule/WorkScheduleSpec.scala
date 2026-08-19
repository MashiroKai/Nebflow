package nebflow.core.schedule

import io.circe.parser.parse
import io.circe.syntax.*
import WorkSchedule.given
import munit.FunSuite

/** freeze-schedule spec §6 B8/B9：WorkSchedule 纯函数判定 + fail-safe + 校验 + merge。 */
class WorkScheduleSpec extends FunSuite:

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

  private val twoSegment =
    WorkScheduleConfig(
      enabled = true,
      segments = List(
        WorkScheduleSegment("09:00", "12:00"),
        WorkScheduleSegment("14:00", "18:00")
      )
    )

  // ── parseHHmm ─────────────────────────────────────────────

  test("parseHHmm accepts valid HH:mm") {
    assertEquals(WorkSchedule.parseHHmm("09:00"), Some(540))
    assertEquals(WorkSchedule.parseHHmm("00:00"), Some(0))
    assertEquals(WorkSchedule.parseHHmm("23:59"), Some(1439))
    assertEquals(WorkSchedule.parseHHmm("12:30"), Some(750))
  }

  test("parseHHmm rejects invalid formats") {
    assertEquals(WorkSchedule.parseHHmm("24:00"), None) // hour out of range
    assertEquals(WorkSchedule.parseHHmm("9:00"), None) // no leading zero
    assertEquals(WorkSchedule.parseHHmm("09:6"), None) // minute needs two digits
    assertEquals(WorkSchedule.parseHHmm("0960"), None) // missing colon
    assertEquals(WorkSchedule.parseHHmm(""), None)
    assertEquals(WorkSchedule.parseHHmm("abc"), None)
    assertEquals(WorkSchedule.parseHHmm("09:60"), None) // minute out of range
    assertEquals(WorkSchedule.parseHHmm(null), None)
  }

  // ── eval: open/closed ─────────────────────────────────────

  test("eval: inside segment is open") {
    assert(WorkSchedule.eval(twoSegment, at(10, 30)).open)
    assert(WorkSchedule.eval(twoSegment, at(15, 0)).open)
  }

  test("eval: outside all segments is closed") {
    assert(!WorkSchedule.eval(twoSegment, at(8, 59)).open)
    assert(!WorkSchedule.eval(twoSegment, at(13, 0)).open)
    assert(!WorkSchedule.eval(twoSegment, at(23, 0)).open)
  }

  test("eval: boundary start inclusive (09:00 open, 14:00 open)") {
    assert(WorkSchedule.eval(twoSegment, at(9, 0)).open)
    assert(WorkSchedule.eval(twoSegment, at(14, 0)).open)
  }

  test("eval: boundary end exclusive (12:00 closed, 18:00 closed)") {
    assert(!WorkSchedule.eval(twoSegment, at(12, 0)).open)
    assert(!WorkSchedule.eval(twoSegment, at(18, 0)).open)
  }

  test("eval: multi-segment union") {
    assert(WorkSchedule.eval(twoSegment, at(11, 59)).open)
    assert(!WorkSchedule.eval(twoSegment, at(12, 0)).open)
    assert(WorkSchedule.eval(twoSegment, at(14, 0)).open)
  }

  test("eval: disabled is always open with no nextChangeAt") {
    val w1 = WorkSchedule.eval(WorkScheduleConfig(enabled = false, segments = twoSegment.segments), at(3, 0))
    assert(w1.open)
    assertEquals(w1.nextChangeAt, None)
    val w2 = WorkSchedule.eval(WorkScheduleConfig(), at(23, 59))
    assert(w2.open)
    assertEquals(w2.nextChangeAt, None)
  }

  test("eval: overlapping segments take the union") {
    val overlap = WorkScheduleConfig(
      enabled = true,
      segments = List(WorkScheduleSegment("09:00", "13:00"), WorkScheduleSegment("12:00", "15:00"))
    )
    assert(WorkSchedule.eval(overlap, at(12, 30)).open)
  }

  // ── eval: nextChangeAt ────────────────────────────────────

  test("nextChangeAt: open mid-morning → today's first upcoming boundary (12:00)") {
    assertEquals(WorkSchedule.eval(twoSegment, at(10, 30)).nextChangeAt, Some(at(12, 0)))
  }

  test("nextChangeAt: closed midday gap → next segment start (14:00)") {
    assertEquals(WorkSchedule.eval(twoSegment, at(13, 0)).nextChangeAt, Some(at(14, 0)))
  }

  test("nextChangeAt: closed before first segment → today's first start (09:00)") {
    assertEquals(WorkSchedule.eval(twoSegment, at(6, 0)).nextChangeAt, Some(at(9, 0)))
  }

  test("nextChangeAt: cross-day — 23:50 → tomorrow 09:00") {
    assertEquals(WorkSchedule.eval(twoSegment, at(23, 50)).nextChangeAt, Some(atTomorrow(9, 0)))
  }

  test("nextChangeAt: after last segment end → tomorrow's first boundary") {
    assertEquals(WorkSchedule.eval(twoSegment, at(19, 0)).nextChangeAt, Some(atTomorrow(9, 0)))
  }

  // ── load: fail-safe (B8) ──────────────────────────────────

  test("load: valid json preserves config") {
    val json = parse("""{"enabled":true,"segments":[{"start":"09:00","end":"12:00"}]}""").toOption
    val cfg = WorkSchedule.load(json)
    assert(cfg.enabled)
    assertEquals(cfg.segments, List(WorkScheduleSegment("09:00", "12:00")))
  }

  test("load: None / garbage json → disabled") {
    assertEquals(WorkSchedule.load(None), WorkScheduleConfig())
    val garbage = parse("""{"enabled":"yes!!!","segments":"not-a-list"}""").toOption
    assertEquals(WorkSchedule.load(garbage), WorkScheduleConfig())
  }

  test("load: enabled with empty segments → fail-safe disabled") {
    val json = parse("""{"enabled":true,"segments":[]}""").toOption
    assertEquals(WorkSchedule.load(json), WorkScheduleConfig(enabled = false))
  }

  test("load: enabled with invalid segment → fail-safe disabled") {
    val json = parse("""{"enabled":true,"segments":[{"start":"9:00","end":"12:00"}]}""").toOption
    assertEquals(WorkSchedule.load(json), WorkScheduleConfig(enabled = false))
    val json2 = parse("""{"enabled":true,"segments":[{"start":"12:00","end":"09:00"}]}""").toOption
    assertEquals(WorkSchedule.load(json2), WorkScheduleConfig(enabled = false))
  }

  test("load: disabled with valid segments keeps segments (UI round-trip)") {
    val json = parse("""{"enabled":false,"segments":[{"start":"09:00","end":"12:00"}]}""").toOption
    val cfg = WorkSchedule.load(json)
    assert(!cfg.enabled)
    assertEquals(cfg.segments, List(WorkScheduleSegment("09:00", "12:00")))
  }

  // ── validate: setWorkSchedule 入口 (B9) ───────────────────

  test("validate: valid payload → Right") {
    val json = parse("""{"enabled":true,"segments":[{"start":"09:00","end":"12:00"}]}""").toOption.get
    assert(WorkSchedule.validate(json).isRight)
  }

  test("validate: start >= end rejected") {
    val json = parse("""{"enabled":true,"segments":[{"start":"12:00","end":"09:00"}]}""").toOption.get
    assert(WorkSchedule.validate(json).isLeft)
  }

  test("validate: enabled with empty segments rejected") {
    val json = parse("""{"enabled":true,"segments":[]}""").toOption.get
    assert(WorkSchedule.validate(json).isLeft)
  }

  test("validate: bad HH:mm format rejected") {
    val json = parse("""{"enabled":true,"segments":[{"start":"0900","end":"12:00"}]}""").toOption.get
    assert(WorkSchedule.validate(json).isLeft)
  }

  test("validate: disabled with empty segments accepted (turning off)") {
    val json = parse("""{"enabled":false,"segments":[]}""").toOption.get
    assert(WorkSchedule.validate(json).isRight)
  }

  // ── mergeIntoConfig: targeted write 语义 (B9) ─────────────

  test("mergeIntoConfig adds workSchedule and preserves other top-level keys") {
    val existing = parse("""{"llm":{"providers":{"glm":{"apiKey":"sk-x"}}},"thinkingConfig":{"enabled":true}}""").toOption.get
    val cfg = WorkScheduleConfig(enabled = true, segments = List(WorkScheduleSegment("09:00", "12:00")))
    val merged = WorkSchedule.mergeIntoConfig(existing, cfg)
    // 既有顶层节原样保留
    assertEquals(merged.hcursor.downField("llm").downField("providers").downField("glm").downField("apiKey").as[String], Right("sk-x"))
    assertEquals(merged.hcursor.downField("thinkingConfig").downField("enabled").as[Boolean], Right(true))
    // workSchedule 写入
    assertEquals(merged.hcursor.downField("workSchedule").as[WorkScheduleConfig], Right(cfg))
  }

  test("mergeIntoConfig overwrites existing workSchedule node") {
    val existing = parse("""{"workSchedule":{"enabled":true,"segments":[{"start":"08:00","end":"09:00"}]},"x":1}""").toOption.get
    val cfg = WorkScheduleConfig(enabled = false, segments = Nil)
    val merged = WorkSchedule.mergeIntoConfig(existing, cfg)
    assertEquals(merged.hcursor.downField("workSchedule").as[WorkScheduleConfig], Right(cfg))
    assertEquals(merged.hcursor.downField("x").as[Int], Right(1))
  }

  // ── codec round-trip ──────────────────────────────────────

  test("codec round-trip") {
    val cfg = WorkScheduleConfig(enabled = true, segments = List(WorkScheduleSegment("09:00", "12:00"), WorkScheduleSegment("14:00", "18:00")))
    assertEquals(cfg.asJson.as[WorkScheduleConfig], Right(cfg))
  }

end WorkScheduleSpec
