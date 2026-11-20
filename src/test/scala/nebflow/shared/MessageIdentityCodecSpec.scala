package nebflow.shared

import io.circe.Json
import io.circe.parser.decode
import io.circe.syntax.*
import munit.FunSuite
import nebflow.shared.given

class MessageIdentityCodecSpec extends FunSuite:

  test("legacy text messages decode without identity and retain their encoded shape") {
    val raw = """{"role":"user","content":"hello"}"""
    val expected = Message(MessageRole.User, Left("hello"), timestamp = 0L)
    assertEquals(decode[Message](raw), Right(expected))
    assertEquals(expected.asJson.noSpaces, raw)
  }

  test("legacy block messages retain timestamps and source without adding identity keys") {
    val raw =
      """{"role":"user","content":[{"type":"text","text":"hello"}],"blocks":true,"timestamp":42,"source":"delegate"}"""
    val decoded = decode[Message](raw).toOption.get
    assertEquals(decoded.messageId, None)
    assertEquals(decoded.runId, None)
    assertEquals(decoded.timestamp, 42L)
    assertEquals(decoded.source, Some("delegate"))
    assertEquals(decoded.asJson, decode[Json](raw).toOption.get)
  }

  test("text and block messages round trip all combinations of optional identity fields") {
    val contents: List[Either[String, List[ContentBlock]]] = List(
      Left("result"),
      Right(List(ContentBlock.ToolResult("tool-1", "result", Some(false))))
    )
    for
      content <- contents
      messageId <- List(None, Some("message-1"))
      runId <- List(None, Some("run-1"))
    do
      val msg = Message(MessageRole.User, content, 42L, Some("subtask"), messageId, runId)
      assertEquals(decode[Message](msg.asJson.noSpaces), Right(msg))
      assertEquals(msg.asJson.hcursor.downField("messageId").focus.isDefined, messageId.isDefined)
      assertEquals(msg.asJson.hcursor.downField("runId").focus.isDefined, runId.isDefined)
  }

  test("null identities decode as absent and malformed identities are rejected") {
    val raw = """{"role":"user","content":"hello","messageId":null,"runId":null}"""
    val decoded = decode[Message](raw).toOption.get
    assertEquals(decoded.messageId, None)
    assertEquals(decoded.runId, None)
    assert(decode[Message]("""{"role":"user","content":"hello","messageId":{}}""").isLeft)
    assert(decode[Message]("""{"role":"user","content":"hello","runId":[]}""").isLeft)
  }

end MessageIdentityCodecSpec
