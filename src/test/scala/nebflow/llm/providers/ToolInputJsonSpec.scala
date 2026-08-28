package nebflow.llm.providers

import io.circe.parser.parse
import io.circe.syntax.*
import io.circe.JsonObject
import munit.FunSuite

import java.time.{Instant, ZoneId}

/**
 * Issue #18 (2026-08-17): GLM-5.3 emitted Schedule's triggerAt as an unquoted
 * ISO-8601 literal — invalid JSON. The adapters' `.getOrElse(JsonObject.empty)`
 * silently coerced it to {}, the tool then reported "triggerAt is required",
 * and the LLM retried the same malformed call six times. These specs pin the
 * repair (quote rescue) and the no-silent-swallow contract (marker).
 */
class ToolInputJsonSpec extends FunSuite:

  private val zone = ZoneId.of("Asia/Shanghai")

  test("rescues unquoted ISO-8601 with offset — exact issue #18 shape") {
    // Shape captured from the live replay of the failed request (raw SSE).
    val raw = """{"content":"今晚实施批次派发","triggerAt":2026-08-18T00:00:00+08:00}"""
    val obj = ToolInputJson.parseToolInput("Schedule", raw)
    assertEquals(obj("triggerAt").flatMap(_.asString), Some("2026-08-18T00:00:00+08:00"))
    assertEquals(obj("content").flatMap(_.asString), Some("今晚实施批次派发"))
    assert(obj(ToolInputJson.RawArgsKey).isEmpty, "rescued input must not carry the marker")
  }

  test("rescued triggerAt is directly consumable by ScheduleTool") {
    val raw = """{"content":"早安汇总","triggerAt":2026-08-18T00:00:00+08:00}"""
    val obj = ToolInputJson.parseToolInput("Schedule", raw)
    val res = nebflow.core.tools.ScheduleTool.parseTriggerAt(obj("triggerAt"), 0L, zone)
    assertEquals(res, Right(Instant.parse("2026-08-17T16:00:00Z").toEpochMilli))
  }

  test("rescues date-only literal") {
    val obj = ToolInputJson.parseToolInput("Schedule", """{"triggerAt":2026-08-18}""")
    assertEquals(obj("triggerAt").flatMap(_.asString), Some("2026-08-18"))
  }

  test("rescues space-separated datetime literal") {
    val obj = ToolInputJson.parseToolInput("Schedule", """{"triggerAt": 2026-08-18 09:30}""")
    assertEquals(obj("triggerAt").flatMap(_.asString), Some("2026-08-18 09:30"))
  }

  test("rescues Z-suffix datetime literal") {
    val obj = ToolInputJson.parseToolInput("Schedule", """{"triggerAt":2026-08-18T01:02:03Z}""")
    assertEquals(obj("triggerAt").flatMap(_.asString), Some("2026-08-18T01:02:03Z"))
  }

  test("rescues compact +hhmm offset literal") {
    val obj = ToolInputJson.parseToolInput("Schedule", """{"triggerAt":2026-08-18T09:00:00+0800}""")
    assertEquals(obj("triggerAt").flatMap(_.asString), Some("2026-08-18T09:00:00+0800"))
  }

  test("valid JSON is returned untouched — numeric triggerAt stays a number") {
    val raw = """{"content":"x","triggerAt":1755298800000}"""
    val obj = ToolInputJson.parseToolInput("Schedule", raw)
    assertEquals(obj("triggerAt").flatMap(_.asNumber.flatMap(_.toLong)), Some(1755298800000L))
  }

  test("valid JSON with quoted ISO is returned untouched") {
    val raw = """{"content":"x","triggerAt":"2026-08-18T00:00:00+08:00"}"""
    val obj = ToolInputJson.parseToolInput("Schedule", raw)
    assertEquals(obj("triggerAt").flatMap(_.asString), Some("2026-08-18T00:00:00+08:00"))
  }

  test("blank arguments mean zero-arg call — empty object, no marker") {
    val obj = ToolInputJson.parseToolInput("Read", "   ")
    assert(obj.isEmpty)
    assert(obj(ToolInputJson.RawArgsKey).isEmpty)
  }

  test("unrepairable arguments produce the marker, not a silent {}") {
    val raw = """{"content":"x","triggerAt":not-a-time-at-all}"""
    val obj = ToolInputJson.parseToolInput("Schedule", raw)
    val rawCarried = obj(ToolInputJson.RawArgsKey).flatMap(_.asString)
    assert(rawCarried.isDefined, "must carry the raw args in the marker")
    assert(rawCarried.get.contains("not-a-time-at-all"))

    val details = ToolInputJson.malformedDetails(obj, "Schedule")
    assert(details.isDefined)
    assert(details.get.contains("could not be parsed"), details.get)
    assert(details.get.contains("not-a-time-at-all"), "error must show the raw args to the LLM")
    assert(details.get.contains("Re-issue"), "error must tell the LLM how to recover")
  }

  test("healthy input never produces malformedDetails") {
    assert(ToolInputJson.malformedDetails(JsonObject("a" -> "b".asJson), "Read").isEmpty)
  }

  test("marker raw text is capped for context safety") {
    val raw = "{\"data\":\"" + "x" * 10_000 + "\",\"triggerAt\":@@@}"
    val obj = ToolInputJson.parseToolInput("Write", raw)
    val carried = obj(ToolInputJson.RawArgsKey).flatMap(_.asString).get
    assert(clue(carried.length) <= 4000 + 50, s"carried ${carried.length} chars")
    assert(carried.contains("truncated"))
  }

  test("valid non-object JSON (bare scalar) is marked, not silently dropped") {
    val obj = ToolInputJson.parseToolInput("Read", "42")
    assert(obj(ToolInputJson.RawArgsKey).isDefined)
  }

end ToolInputJsonSpec
