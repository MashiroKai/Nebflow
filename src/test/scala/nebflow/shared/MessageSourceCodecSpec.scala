package nebflow.shared

import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.shared.given
import munit.FunSuite

/**
 * Verifies the Message.source injection-marker codec (任务 P):
 *   - new messages with source round-trip through JSON
 *   - old persisted messages without source still decode (source=None)
 *   - UiMessage.User carries source for frontend restore rendering
 */
class MessageSourceCodecSpec extends FunSuite:

  test("Message with source round-trips through JSON"):
    val msg = Message(MessageRole.User, Left("task prompt"), source = Some("delegate"))
    val json = msg.asJson.noSpaces
    val decoded = decode[Message](json).toOption
    assert(decoded.isDefined, "decoded")
    assertEquals(decoded.get.role, MessageRole.User)
    assertEquals(decoded.get.source, Some("delegate"))

  test("old persisted Message without source decodes to source=None (backward compatible)"):
    val legacyJson = """{"role":"user","content":"hello","timestamp":1700000000000}"""
    val decoded = decode[Message](legacyJson).toOption
    assert(decoded.isDefined, "legacy decodes")
    assertEquals(decoded.get.source, None)
    assertEquals(decoded.get.textContent, "hello")

  test("UserInput with injected=true and source persists in UiMessage.User JSON"):
    val ui: UiMessage = UiMessage.User("📬 Mail from Manager", injected = true, timestamp = 1700000000000L, source = Some("mail"))
    val json = ui.asJson.noSpaces
    assert(json.contains("\"injected\":true"))
    assert(json.contains("\"source\":\"mail\""))
    // Round-trip via the UiMessage decoder
    val decoded = decode[UiMessage](json).toOption
    assert(decoded.isDefined, "UiMessage decodes")
    decoded.get match
      case u: UiMessage.User =>
        assertEquals(u.source, Some("mail"))
        assert(u.injected)
      case other => fail(s"expected User, got $other")

  test("UiMessage.User without source decodes to source=None (legacy)"):
    val legacyJson = """{"type":"user","text":"hi","attachments":[],"injected":true}"""
    val decoded = decode[UiMessage](legacyJson).toOption
    assert(decoded.isDefined, "legacy UiMessage decodes")
    decoded.get match
      case u: UiMessage.User => assertEquals(u.source, None)
      case other => fail(s"expected User, got $other")

  test("UiMessage.User with sender round-trips through JSON"):
    val ui: UiMessage = UiMessage.User("task from Manager", injected = true, timestamp = 1700000000000L, source = Some("mail"), sender = Some("Manager"))
    val json = ui.asJson.noSpaces
    assert(json.contains("\"sender\":\"Manager\""), s"json contains sender: $json")
    val decoded = decode[UiMessage](json).toOption
    assert(decoded.isDefined, "UiMessage with sender decodes")
    decoded.get match
      case u: UiMessage.User => assertEquals(u.sender, Some("Manager"))
      case other => fail(s"expected User, got $other")

  test("UiMessage.User without sender decodes to sender=None (legacy)"):
    val legacyJson = """{"type":"user","text":"hi","attachments":[],"injected":true,"source":"mail"}"""
    val decoded = decode[UiMessage](legacyJson).toOption
    assert(decoded.isDefined, "legacy UiMessage without sender decodes")
    decoded.get match
      case u: UiMessage.User => assertEquals(u.sender, None)
      case other => fail(s"expected User, got $other")

end MessageSourceCodecSpec
