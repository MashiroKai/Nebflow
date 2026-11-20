package nebflow.llm.providers

import io.circe.JsonObject
import munit.FunSuite
import nebflow.llm.SendMessageParams
import nebflow.shared.*

class PrefixFingerprintSpec extends FunSuite:
  private val adapter = new AnthropicAdapter("http://localhost:0", "k", null)
  private def params(turn: Int) = SendMessageParams(
    messages = List(Message(MessageRole.User, Left(s"user turn $turn"))),
    model = "model", tools = Some(List(ToolDefinition("Read", "read", JsonObject.empty))),
    systemStable = Some("system injection: fixed"),
    requestOnlyContext = List(Message(MessageRole.User, Left(s"dynamic reminder $turn")))
  )
  private def core(p: SendMessageParams) = PrefixFingerprint.anthropicCore(adapter.bodyOf(p, false))

  test("five turns preserve fixed wire core"):
    val baseline = PrefixFingerprint.fingerprint(core(params(0)))
    (1 to 5).foreach(i => assertEquals(PrefixFingerprint.fingerprint(core(params(i))), baseline))

  test("changed byte reports segment, UTF-8 offset, layer and context"):
    val old = core(params(0))
    val changed = core(params(0).copy(systemStable = Some("system injection: fixeX")))
    val diff = PrefixFingerprint.firstDifference(old, changed).get
    assertEquals(diff.segment, "system")
    assertEquals(diff.layer, "assembled system injection")
    assertEquals(diff.byteOffset, 22)
    assert(diff.before.contains("fixed") && diff.after.contains("fixeX"))

  test("separate adapter instances reproduce the fixed wire core"):
    val second = new AnthropicAdapter("http://localhost:0", "k", null)
    val other = PrefixFingerprint.anthropicCore(second.bodyOf(params(0), false))
    assertEquals(PrefixFingerprint.fingerprint(core(params(0))), PrefixFingerprint.fingerprint(other))
end PrefixFingerprintSpec
