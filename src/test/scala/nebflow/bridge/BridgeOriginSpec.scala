package nebflow.bridge

import munit.FunSuite

/**
 * Offline spec for the channel-agnostic source marker (source-marker batch,
 * 2026-10-01). Pure functions only.
 *
 * The marker exists so a message that arrives through a social interface is
 * labelled as such where the model reads it. The template is owned HERE, on the
 * channel-agnostic side, so a second channel reuses it by supplying its own
 * values instead of copying the text — which is exactly the shape the author's
 * ruling forbids (no per-channel hardcoding).
 */
class BridgeOriginSpec extends FunSuite:

  private val feishu = BridgeOrigin("feishu", "飞书", Some("oc_123"))

  test("the marker names the channel, the sender and the conversation") {
    val m = BridgeOrigin.marker(feishu, Some("ou_sender"))
    assertEquals(m,
      """<system-reminder>
        |以下消息来自飞书（社交接口 · 渠道 feishu）· 发送方：ou_sender · 会话：oc_123
        |</system-reminder>""".stripMargin)
  }

  test("a missing sender renders the fixed placeholder, never an empty slot") {
    val m = BridgeOrigin.marker(feishu, None)
    assert(m.contains(s"发送方：${BridgeOrigin.UnknownSender}"), m)
    val blank = BridgeOrigin.marker(feishu, Some("   "))
    assert(blank.contains(s"发送方：${BridgeOrigin.UnknownSender}"), blank)
  }

  test("a missing conversation reference renders a neutral dash, not a guessed name") {
    assert(BridgeOrigin.marker(feishu.copy(chatRef = None), Some("x")).contains("会话：-"))
    assert(BridgeOrigin.marker(feishu.copy(chatRef = Some("  ")), Some("x")).contains("会话：-"))
  }

  test("compose places the marker block AHEAD of the content, one blank line apart") {
    val out = BridgeOrigin.compose(Some(feishu), Some("ou_sender"), "hello")
    assert(out.startsWith("<system-reminder>\n"), out)
    assert(out.endsWith("</system-reminder>\n\nhello"), out)
    // exactly one marker block, never two
    assertEquals(out.split(BridgeOrigin.MarkerOpen, -1).length, 2, out)
  }

  test("compose without an origin leaves the content byte-identical (the compatibility rule)") {
    assertEquals(BridgeOrigin.compose(None, Some("ou_sender"), "hello"), "hello")
  }

  test("the template is data-driven: a second channel reuses it without a second copy of the text") {
    val other = BridgeOrigin("other-channel", "另一个渠道", None)
    val out = BridgeOrigin.compose(Some(other), None, "hi")
    assert(out.contains("以下消息来自另一个渠道（社交接口 · 渠道 other-channel）"), out)
    assert(out.contains(s"发送方：${BridgeOrigin.UnknownSender}"), out)
  }
