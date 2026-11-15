package nebflow.gateway.ws

import nebflow.gateway.*

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import io.circe.Json
import munit.FunSuite
import nebflow.shared.PathUtil

/**
 * Frame contract of the Note protocol (`askNoteGet` / `askNotePut`), driven through the real
 * `WsDispatch.handlers` entries against an isolated data root: the fields a client relies on to
 * match receipts to writes (`writeId`), to compare versions (`version`, `cleared`), to tell a read
 * error from an empty note, and the deterministic refusal of a page that names no base version.
 * The Note handlers do not touch the context or the explorer session, so those are null.
 */
class WsAskNoteFramesSpec extends FunSuite:

  private val Sid = "ask-1a2b3c4d"
  private val tempRoot: os.Path = os.pwd / "target" / "test-ws-ask-note-frames"
  private val originalRoot = PathUtil.dataRoot

  override def beforeEach(context: BeforeEach): Unit =
    if os.exists(tempRoot) then os.remove.all(tempRoot)
    os.makeDir.all(tempRoot)
    PathUtil.setDataRoot(tempRoot)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    if os.exists(tempRoot) then os.remove.all(tempRoot)

  /** Send one frame through the registered handler and return what it sent back. */
  private def exchange(msgType: String, frame: String): List[Json] =
    (for
      sent <- Ref.of[IO, List[Json]](Nil)
      _ <- WsDispatch.handlers(msgType)(null, frame, (j: Json) => sent.update(_ :+ j), null)
      out <- sent.get
    yield out).unsafeRunSync()

  private def get(): Json = exchange("askNoteGet", s"""{"type":"askNoteGet","sessionId":"$Sid"}""").head

  private def put(text: String, base: Option[Long], writeId: Option[Int] = None): Json =
    val fields = List(
      "type" -> Json.fromString("askNotePut"),
      "sessionId" -> Json.fromString(Sid),
      "text" -> Json.fromString(text)
    ) ++ base.toList.map(b => "baseVersion" -> Json.fromLong(b)) ++
      writeId.toList.map(w => "writeId" -> Json.fromInt(w))
    exchange("askNotePut", Json.obj(fields*).noSpaces).head

  private def str(j: Json, k: String): Option[String] = j.hcursor.get[String](k).toOption
  private def lng(j: Json, k: String): Option[Long] = j.hcursor.get[Long](k).toOption
  private def bool(j: Json, k: String): Option[Boolean] = j.hcursor.get[Boolean](k).toOption

  test("FDC-SCN-004 a receipt carries the writeId of the write it answers and the new version") {
    val ack = put("one", Some(0L), Some(7))
    assertEquals(str(ack, "type"), Some("askNoteAck"))
    assertEquals(str(ack, "sessionId"), Some(Sid))
    assertEquals(bool(ack, "ok"), Some(true))
    assertEquals(lng(ack, "writeId"), Some(7L))
    val version = lng(ack, "version").getOrElse(-1L)
    assert(version > 0L, clue(ack))
    assertEquals(lng(ack, "ts"), Some(version), "ts stays equal to the version for older readers")
    assertEquals(bool(ack, "overwritten"), Some(false))
    val state = get()
    assertEquals((str(state, "text"), lng(state, "version"), bool(state, "exists"), bool(state, "cleared")), (Some("one"), Some(version), Some(true), Some(false)))
    assertEquals(lng(state, "ts"), Some(version))
  }

  test("FDC-SCN-004 a stale write is answered with a conflict that carries the stored text, version and cleared flag") {
    val first = lng(put("one", Some(0L)), "version").get
    val second = lng(put("two", Some(first)), "version").get
    val ack = put("stale", Some(first), Some(9))
    assertEquals(bool(ack, "ok"), Some(false))
    assertEquals(str(ack, "error"), Some("conflict"))
    assertEquals(lng(ack, "writeId"), Some(9L))
    assertEquals((str(ack, "text"), lng(ack, "version"), bool(ack, "exists"), bool(ack, "cleared")), (Some("two"), Some(second), Some(true), Some(false)))
    assertEquals(str(get(), "text"), Some("two"))
  }

  test("FDC-SCN-005 a write that names no version is refused with version-required and writes nothing") {
    val missing = put("blind", None, Some(3))
    assertEquals((bool(missing, "ok"), str(missing, "error"), lng(missing, "writeId")), (Some(false), Some("version-required"), Some(3L)))
    assertEquals(bool(get(), "exists"), Some(false))
    val version = lng(put("kept", Some(0L)), "version").get
    // Older pages sent `baseTs` only, and a non-numeric or negative base counts as none.
    for raw <- List(
        s"""{"type":"askNotePut","sessionId":"$Sid","text":"overwrite","baseTs":$version}""",
        s"""{"type":"askNotePut","sessionId":"$Sid","text":"overwrite","baseVersion":null}""",
        s"""{"type":"askNotePut","sessionId":"$Sid","text":"overwrite","baseVersion":"x"}""",
        s"""{"type":"askNotePut","sessionId":"$Sid","text":"overwrite","baseVersion":-1}"""
      )
    do
      val ack = exchange("askNotePut", raw).head
      assertEquals(str(ack, "error"), Some("version-required"), clue(raw))
      assertEquals(str(ack, "text"), Some("kept"), clue(raw))
    assertEquals(str(get(), "text"), Some("kept"))
  }

  test("FDC-SCN-005 a read of a cleared note says cleared with the clear's version") {
    val v1 = lng(put("text", Some(0L)), "version").get
    val v2 = lng(put("", Some(v1)), "version").get
    val state = get()
    assertEquals((str(state, "text"), bool(state, "exists"), bool(state, "cleared"), lng(state, "version"), lng(state, "ts")), (Some(""), Some(false), Some(true), Some(v2), Some(v2)))
    assert(v2 > v1)
    assertEquals(str(put("old edit", Some(v1)), "error"), Some("conflict"))
  }

  test("FDC-SCN-012 an unreadable note is a read-failed error frame without text, for a read and for a write") {
    val dir = tempRoot / "ask" / "notes"
    os.makeDir.all(dir)
    os.write(dir / s"$Sid.json", "{not json")
    val state = get()
    assertEquals(str(state, "type"), Some("askNoteState"))
    assertEquals(str(state, "error"), Some("read-failed"))
    assert(state.hcursor.downField("text").focus.isEmpty && state.hcursor.downField("exists").focus.isEmpty, clue(state))
    val ack = put("overwrite", Some(0L), Some(5))
    assertEquals((bool(ack, "ok"), str(ack, "error"), lng(ack, "writeId")), (Some(false), Some("read-failed"), Some(5L)))
    assertEquals(os.read(dir / s"$Sid.json"), "{not json")
  }

  test("FDC-SCN-029 an oversized text and a bad session id are refused with their own errors") {
    val big = put("a" * (256 * 1024 + 1), Some(0L))
    assertEquals((bool(big, "ok"), str(big, "error"), lng(big, "bytes"), lng(big, "limit")), (Some(false), Some("too-large"), Some(256L * 1024 + 1), Some(256L * 1024)))
    val bad = exchange("askNotePut", """{"type":"askNotePut","sessionId":"x","text":"t","baseVersion":0}""").head
    assertEquals(str(bad, "type"), Some("askError"))
  }
