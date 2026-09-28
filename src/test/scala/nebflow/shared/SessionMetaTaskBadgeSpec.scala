package nebflow.shared

import io.circe.Json
import io.circe.parser.decode
import io.circe.syntax.*
import munit.FunSuite

/** Task-badge payload contract (taskbadge batch, 2026-09-27): the session-list
  * wire face of the sub-agents panel task attribution.
  *
  * Three faces pinned here (the batch's spec requirements (a)(b)(c)):
  *
  *  - (a) payload field shape: attribution present → `taskId`/`taskTitle` keys in
  *    the encoded session JSON with the ledger title; no attribution → keys
  *    absent (empty-value form is stable, never null/empty-string noise);
  *  - (b) SessionMeta backward compatibility: old-form bytes (no new keys) decode
  *    zero-migration, and the two new fields default to None so every existing
  *    constructor call site compiles unchanged;
  *  - (c) multi-task coexistence: [[SessionMeta.withTaskAttribution]] merges each
  *    session's OWN task keys side by side (two tasks in one list never bleed
  *    into each other), and unattributed sessions stay byte-identical.
  *
  * Wire-only discipline: the enrichment helper is the same face as
  * `withEffectiveSafetyModes` (exit overlay); `SessionStore.saveIndex` must never
  * gain these keys — the disk-bytes-unchanged side is enforced by construction
  * (the case class fields default to None and no saveIndex path sets them).
  */
class SessionMetaTaskBadgeSpec extends FunSuite:

  private def meta(
    id: String,
    name: String = "session",
    taskId: Option[String] = None,
    taskTitle: Option[String] = None
  ): SessionMeta =
    SessionMeta(
      id = id,
      name = name,
      createdAt = 100L,
      updatedAt = 200L,
      hasUnread = false,
      taskId = taskId,
      taskTitle = taskTitle
    )

  // ── (a) payload field shape ──────────────────────────────────────────

  test("(a) attributed session encodes taskId + taskTitle keys with the ledger title") {
    val json = meta("dispatcher-ab12cd34", taskId = Some("35"), taskTitle = Some("Sub-agent panel task badge")).asJson
    assertEquals(json.hcursor.get[String]("taskId"), Right("35"))
    assertEquals(json.hcursor.get[String]("taskTitle"), Right("Sub-agent panel task badge"))
    // base fields untouched
    assertEquals(json.hcursor.get[String]("id"), Right("dispatcher-ab12cd34"))
  }

  test("(a) unattributed session encodes NEITHER key (keys absent, not empty strings)") {
    val obj = meta("uuid-main-session").asJson.asObject.get
    assertEquals(obj.contains("taskId"), false)
    assertEquals(obj.contains("taskTitle"), false)
  }

  test("(a) taskId without a resolvable title encodes taskId alone (degraded badge, stable form)") {
    val json = meta("node-ab12cd34", taskId = Some("12")).asJson
    assertEquals(json.hcursor.get[String]("taskId"), Right("12"))
    assertEquals(json.asObject.get.contains("taskTitle"), false)
  }

  // ── (b) SessionMeta backward compatibility (old bytes, zero migration) ──

  test("(b) old-form index bytes (no new keys) decode with both fields None") {
    // the exact key set sessions/_index.json has carried for an ordinary session
    val old = Json.obj(
      "id" -> "uuid-1".asJson,
      "name" -> "Main".asJson,
      "createdAt" -> 0L.asJson,
      "updatedAt" -> 1727400000000L.asJson,
      "hasUnread" -> false.asJson,
      "safetyMode" -> "auto-all".asJson
    )
    decode[SessionMeta](old.noSpaces) match
      case Left(e)  => fail(s"old-form bytes must decode zero-migration: $e")
      case Right(m) =>
        assertEquals(m.taskId, None)
        assertEquals(m.taskTitle, None)
        assertEquals(m.id, "uuid-1")
    end match
  }

  test("(b) pre-ctxthresh-era bytes (missing several later keys) still decode") {
    val older = """{"id":"uuid-2","name":"Legacy","updatedAt":1,"hasUnread":true,"bypass":true}"""
    decode[SessionMeta](older) match
      case Left(e)  => fail(s"legacy bytes must decode: $e")
      case Right(m) =>
        assertEquals(m.taskId, None)
        assertEquals(m.safetyMode, "auto-all") // legacy bypass migration intact
    end match
  }

  test("(b) None-valued new fields round-trip without the keys (old consumer shape out)") {
    val out = meta("uuid-3").asJson
    val back = decode[SessionMeta](out.noSpaces)
    assertEquals(back, Right(meta("uuid-3")))
  }

  test("(b) attributed round-trip preserves both values") {
    val m = meta("uuid-4", taskId = Some("35"), taskTitle = Some("多任务架构展示补全"))
    assertEquals(decode[SessionMeta](m.asJson.noSpaces), Right(m))
  }

  // ── (c) multi-task coexistence (exit overlay enrichment) ──────────────

  private val titles = Map("12" -> "task twelve", "35" -> "task thirty-five")

  test("(c) two attributed sessions in one list keep their OWN task keys (no bleed)") {
    val base = SessionMeta.withEffectiveSafetyModes(
      List(
        meta("dispatcher-aaa", taskId = Some("12")),
        meta("dispatcher-bbb", taskId = Some("35"))
      ),
      nebflow.core.SafetyMode.ConfirmEdits
    )
    val enriched = SessionMeta.withTaskAttribution(base, Map("dispatcher-aaa" -> "12", "dispatcher-bbb" -> "35"), titles)
    val arr = enriched.asArray.get
    val first = arr(0)
    val second = arr(1)
    assertEquals(first.hcursor.get[String]("taskId"), Right("12"))
    assertEquals(first.hcursor.get[String]("taskTitle"), Right("task twelve"))
    assertEquals(second.hcursor.get[String]("taskId"), Right("35"))
    assertEquals(second.hcursor.get[String]("taskTitle"), Right("task thirty-five"))
  }

  test("(c) unattributed session in a mixed list stays byte-identical (empty state)") {
    val base = SessionMeta.withEffectiveSafetyModes(
      List(meta("dispatcher-aaa", taskId = Some("12")), meta("uuid-main")),
      nebflow.core.SafetyMode.ConfirmEdits
    )
    val enriched = SessionMeta.withTaskAttribution(base, Map("dispatcher-aaa" -> "12"), titles)
    val arr = enriched.asArray.get
    val plain = base.asArray.get(1) // the overlay-encoded form, pre-enrichment
    assertEquals(arr(1), plain)
    assertEquals(arr(1).asObject.get.contains("taskId"), false)
  }

  test("(c) title-map miss degrades to taskId alone (ledger read failure never fails the exit)") {
    val base = SessionMeta.withEffectiveSafetyModes(List(meta("dispatcher-aaa")), nebflow.core.SafetyMode.ConfirmEdits)
    val enriched = SessionMeta.withTaskAttribution(base, Map("dispatcher-aaa" -> "99"), Map.empty)
    val s = enriched.asArray.get.head
    assertEquals(s.hcursor.get[String]("taskId"), Right("99"))
    assertEquals(s.asObject.get.contains("taskTitle"), false)
  }

  test("(c) empty attribution map = identity (no keys anywhere, zero waste)") {
    val base = SessionMeta.withEffectiveSafetyModes(List(meta("a"), meta("b")), nebflow.core.SafetyMode.ConfirmEdits)
    val enriched = SessionMeta.withTaskAttribution(base, Map.empty, Map.empty)
    assertEquals(enriched, base)
  }

end SessionMetaTaskBadgeSpec
