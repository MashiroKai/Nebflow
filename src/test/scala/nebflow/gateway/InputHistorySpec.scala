package nebflow.gateway

import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite

import java.time.LocalDateTime

/**
 * input_history.jsonl entry contract (eco #9-P1): entries carry sessionId /
 * session / agent when available; blank context is omitted so old entries
 * without the fields stay naturally compatible.
 */
class InputHistorySpec extends FunSuite:
  private val now = LocalDateTime.of(2026, 8, 15, 13, 0)

  test("plain input: text/ts/type present, context fields omitted when blank"):
    val e = InputHistory.buildEntry("hello", Nil, "", "", "", now).asJson
    assertEquals(e.hcursor.downField("text").as[String], Right("hello"))
    assertEquals(e.hcursor.downField("ts").as[String], Right("2026-08-15 13:00"))
    assertEquals(e.hcursor.downField("type").as[String], Right("input"))
    assertEquals(e.hcursor.downField("sessionId").as[Option[String]], Right(None))
    assertEquals(e.hcursor.downField("session").as[Option[String]], Right(None))
    assertEquals(e.hcursor.downField("agent").as[Option[String]], Right(None))

  test("sessionId, session and agent are recorded when available"):
    val e = InputHistory.buildEntry("hello", Nil, "sess-42", "Release chat", "Backend", now).asJson
    assertEquals(e.hcursor.downField("sessionId").as[String], Right("sess-42"))
    assertEquals(e.hcursor.downField("session").as[String], Right("Release chat"))
    assertEquals(e.hcursor.downField("agent").as[String], Right("Backend"))

  test("unknown session placeholder '-' is not recorded"):
    val e = InputHistory.buildEntry("hello", Nil, "sess-42", "-", "Backend", now).asJson
    assertEquals(e.hcursor.downField("session").as[Option[String]], Right(None))
    assertEquals(e.hcursor.downField("sessionId").as[String], Right("sess-42"))

  test("classification unchanged: paste over 200 chars, file with attachments"):
    val paste = InputHistory.buildEntry("x" * 250, Nil, "s", "n", "a", now).asJson
    assertEquals(paste.hcursor.downField("type").as[String], Right("paste"))
    val att = Json.obj("name" -> "shot.png".asJson, "data" -> "xyz".asJson)
    val file = InputHistory.buildEntry("see file", List(att), "s", "n", "a", now).asJson
    assertEquals(file.hcursor.downField("type").as[String], Right("file"))
    assertEquals(
      file.hcursor.downField("files").as[List[String]],
      Right(List("shot.png"))
    )

  test("text is truncated to 2000 chars"):
    val e = InputHistory.buildEntry("y" * 3000, Nil, "", "", "", now).asJson
    assertEquals(e.hcursor.downField("text").as[String].map(_.length), Right(2000))
end InputHistorySpec
