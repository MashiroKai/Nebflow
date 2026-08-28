package nebflow.neblink

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite

/**
  * PKCE login single-flight state machine + DeviceCredential backward
  * compatibility (stage 2, 2026-08-28).
  */
class PkceLoginSessionSpec extends FunSuite:

  private def statusName(s: PkceLoginSession.Status): String = s.name

  test("start → pending; take with the wrong state → None + error status") {
    val program = for
      session <- PkceLoginSession.make
      _ <- session.start("ver-1", "st-1")
      p <- session.current.map(statusName)
      stolen <- session.take("wrong")
      st <- session.statusJson
    yield (p, stolen, st)
    val (pending, stolen, json) = program.unsafeRunSync()
    assertEquals(pending, "pending")
    assertEquals(stolen, None)
    assertEquals(json, Json.obj("status" -> "error".asJson, "error" -> "Login callback state mismatch".asJson))
  }

  test("take with the right state → verifier; then succeed → sticky success") {
    val program = for
      session <- PkceLoginSession.make
      _ <- session.start("ver-2", "st-2")
      taken <- session.take("st-2")
      // Consumed: a second take (browser replay) finds nothing and must NOT
      // clobber the flow while not pending.
      replay <- session.take("st-2")
      _ <- session.succeed
      st <- session.statusJson
    yield (taken, replay, st)
    val (taken, replay, json) = program.unsafeRunSync()
    assertEquals(taken, Some("ver-2"))
    assertEquals(replay, None)
    assertEquals(json, Json.obj("status" -> "success".asJson))
  }

  test("fail stores the message; next start resets to pending") {
    val program = for
      session <- PkceLoginSession.make
      _ <- session.start("ver-3", "st-3")
      _ <- session.take("st-3")
      _ <- session.fail("token exchange failed: invalid_grant")
      err <- session.statusJson
      _ <- session.start("ver-4", "st-4")
      pending <- session.statusJson
    yield (err, pending)
    val (err, pending) = program.unsafeRunSync()
    assertEquals(err, Json.obj("status" -> "error".asJson, "error" -> "token exchange failed: invalid_grant".asJson))
    assertEquals(pending, Json.obj("status" -> "pending".asJson))
  }

  test("expired attempt reads as state mismatch (clean error, no verifier)") {
    val program = for
      session <- PkceLoginSession.make
      // Created 16 minutes ago — past the 15 min window.
      _ <- session.startAt("ver-old", "st-old", System.currentTimeMillis() - (16 * 60 * 1000L))
      taken <- session.take("st-old")
      st <- session.statusJson
    yield (taken, st)
    val (taken, json) = program.unsafeRunSync()
    assertEquals(taken, None)
    assertEquals(json.hcursor.downField("status").as[String], Right("error"))
  }

  test("stray callback with no attempt: idle stays idle (no phantom error), error only while pending") {
    val program = for
      session <- PkceLoginSession.make
      stray <- session.take("anything")
      idle <- session.statusJson
    yield (stray, idle)
    val (stray, json) = program.unsafeRunSync()
    assertEquals(stray, None)
    assertEquals(json, Json.obj("status" -> "idle".asJson))
  }

  // ── DeviceCredential schema evolution (backward compatible) ────────────

  private val legacyJson =
    """{"serverUrl":"https://neblink.example","networkId":"n-1","deviceId":"d-1","deviceToken":"tok"}"""

  test("legacy device.json (no logto block) decodes with logto=None") {
    import io.circe.parser.decode
    val decoded = decode[DeviceCredential](legacyJson)
    assertEquals(
      decoded,
      Right(DeviceCredential("https://neblink.example", "n-1", "d-1", "tok", None))
    )
  }

  test("round-trip with a logto block keeps the refresh token") {
    import io.circe.syntax.*
    val cred = DeviceCredential(
      "https://neblink.example",
      "n-1",
      "d-1",
      "tok",
      Some(LogtoRefresh("rt-9", 1700000000000L))
    )
    val encoded = cred.asJson.spaces2
    assert(encoded.contains("\"refreshToken\""), "refresh token must serialize")
    assert(!encoded.contains("\"logto\":null"), "absent logto must be omitted, not null")
    val back = io.circe.parser.decode[DeviceCredential](encoded)
    assertEquals(back, Right(cred))
  }

  test("legacy-shape encoding (logto=None) omits the block entirely") {
    import io.circe.syntax.*
    val encoded =
      DeviceCredential("https://neblink.example", "n-1", "d-1", "tok", None).asJson.spaces2
    assertEquals(encoded, io.circe.parser.parse(legacyJson).toOption.get.spaces2)
  }

end PkceLoginSessionSpec
