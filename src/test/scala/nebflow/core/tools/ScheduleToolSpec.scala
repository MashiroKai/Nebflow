package nebflow.core.tools

import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite

import java.time.{Instant, ZoneId}

class ScheduleToolSpec extends FunSuite:

  // Fixed reference: 2026-08-16 12:00:00 +08:00 (= 2026-08-16T04:00:00Z)
  private val zone = ZoneId.of("Asia/Shanghai")
  private val now = 1786852800000L
  private def zdt(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone)

  test("epoch-ms integer passes through unchanged") {
    assertEquals(ScheduleTool.parseTriggerAt(Some(Json.fromLong(1786939200000L)), now, zone), Right(1786939200000L))
  }

  test("epoch-ms string (13 digits) parses to the same value") {
    assertEquals(ScheduleTool.parseTriggerAt(Some("1786939200000".asJson), now, zone), Right(1786939200000L))
  }

  test("ISO 8601 with +08:00 offset") {
    // 2026-08-16T21:00:00+08:00
    val res = ScheduleTool.parseTriggerAt(Some("2026-08-16T21:00:00+08:00".asJson), now, zone)
    assertEquals(res, Right(1786885200000L))
  }

  test("ISO 8601 with Z") {
    val res = ScheduleTool.parseTriggerAt(Some("2026-08-16T21:00:00Z".asJson), now, zone)
    assertEquals(res, Right(Instant.parse("2026-08-16T21:00:00Z").toEpochMilli))
  }

  test("ISO local datetime without zone uses system-zone argument") {
    // 2026-08-16T21:00 in +08:00
    val res = ScheduleTool.parseTriggerAt(Some("2026-08-16T21:00".asJson), now, zone)
    assertEquals(res, Right(1786885200000L))
  }

  test("space-separated datetime works like ISO local") {
    val res = ScheduleTool.parseTriggerAt(Some("2026-08-16 21:00".asJson), now, zone)
    assertEquals(res, Right(1786885200000L))
  }

  test("bare date means midnight local") {
    val res = ScheduleTool.parseTriggerAt(Some("2026-08-17".asJson), now, zone)
    val expected = zdt(res.toOption.get).toString
    assertEquals(zdt(res.toOption.get).toLocalDate.toString, "2026-08-17")
    assertEquals(zdt(res.toOption.get).toLocalTime.toString, "00:00")
    assert(clue(expected).nonEmpty)
  }

  test("time-of-day in the future maps to today") {
    // now is 12:00 local; 21:30 is still ahead today
    val res = ScheduleTool.parseTriggerAt(Some("21:30".asJson), now, zone)
    assertEquals(res.map(t => zdt(t).toString), Right("2026-08-16T21:30+08:00[Asia/Shanghai]"))
  }

  test("time-of-day already past rolls to tomorrow") {
    // now is 12:00 local; 09:00 already passed → tomorrow 09:00
    val res = ScheduleTool.parseTriggerAt(Some("09:00".asJson), now, zone)
    assertEquals(res.map(t => zdt(t).toString), Right("2026-08-17T09:00+08:00[Asia/Shanghai]"))
  }

  test("\"at HH:mm\" prefix is accepted") {
    val res = ScheduleTool.parseTriggerAt(Some("at 08:00".asJson), now, zone)
    assertEquals(res.map(t => zdt(t).toString), Right("2026-08-17T08:00+08:00[Asia/Shanghai]"))
  }

  test("bare \"tomorrow\" defaults to 09:00") {
    val res = ScheduleTool.parseTriggerAt(Some("tomorrow".asJson), now, zone)
    assertEquals(res.map(t => zdt(t).toString), Right("2026-08-17T09:00+08:00[Asia/Shanghai]"))
  }

  test("\"tomorrow at 21:00\"") {
    val res = ScheduleTool.parseTriggerAt(Some("tomorrow at 21:00".asJson), now, zone)
    assertEquals(res.map(t => zdt(t).toString), Right("2026-08-17T21:00+08:00[Asia/Shanghai]"))
  }

  test("\"in 30 minutes\" is relative to now") {
    assertEquals(ScheduleTool.parseTriggerAt(Some("in 30 minutes".asJson), now, zone), Right(now + 30 * 60_000L))
  }

  test("\"in 2 hours\" and \"in 1 day\" and unit abbreviations") {
    assertEquals(ScheduleTool.parseTriggerAt(Some("in 2 hours".asJson), now, zone), Right(now + 2 * 3_600_000L))
    assertEquals(ScheduleTool.parseTriggerAt(Some("in 1 day".asJson), now, zone), Right(now + 86_400_000L))
    assertEquals(ScheduleTool.parseTriggerAt(Some("in 90m".asJson), now, zone), Right(now + 90 * 60_000L))
    assertEquals(ScheduleTool.parseTriggerAt(Some("IN 2 HRS FROM NOW".asJson), now, zone), Right(now + 2 * 3_600_000L))
  }

  test("unparseable string yields a self-healing error with current time") {
    val res = ScheduleTool.parseTriggerAt(Some("sometime next week maybe".asJson), now, zone)
    res match
      case Left(msg) =>
        assert(msg.contains("Cannot parse"), msg)
        assert(msg.contains("ISO 8601"), msg)
        // epoch is embedded zone-independently so the LLM can recalibrate
        assert(msg.contains(s"$now ms"), s"should embed current epoch: $msg")
      case Right(_) => fail("must be Left")
  }

  test("empty and whitespace strings are rejected") {
    assert(ScheduleTool.parseTriggerAt(Some("".asJson), now, zone).isLeft)
    assert(ScheduleTool.parseTriggerAt(Some("   ".asJson), now, zone).isLeft)
  }

  test("missing triggerAt is rejected") {
    assert(ScheduleTool.parseTriggerAt(None, now, zone).isLeft)
  }
end ScheduleToolSpec
