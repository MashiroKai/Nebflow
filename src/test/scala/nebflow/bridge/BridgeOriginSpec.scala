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

  // ────────── source-marker relocation: the two faces must not be conflated ──────────
  //
  // Defect (author-reported, 2026-10-01, verbatim): 「现在飞书消息的 system reminder
  // 会在会话消息中显示，system reminder 永远不显示在会话中。」 Root cause: ONE
  // composed string was handed to BOTH the model context and the session display
  // face. The three criteria below pin the split; the source-contract gate at the
  // bottom pins the single call site that performs it.

  test("CRITERION 1 (session display face): the recorded/broadcast string never carries the marker") {
    val f = BridgeOrigin.faces(Some(feishu), Some("ou_sender"), "hello")
    assert(!f.sessionDisplay.contains(BridgeOrigin.MarkerOpen),
      s"会话显示面不得含 ${BridgeOrigin.MarkerOpen}，现读到：${f.sessionDisplay}")
    assert(!f.sessionDisplay.contains(BridgeOrigin.MarkerClose),
      s"会话显示面不得含 ${BridgeOrigin.MarkerClose}，现读到：${f.sessionDisplay}")
    // and it is the bare content, not a re-derived variant
    assertEquals(f.sessionDisplay, "hello")
  }

  test("CRITERION 2 (model face): the payload carries the marker block, exactly one") {
    val f = BridgeOrigin.faces(Some(feishu), Some("ou_sender"), "hello")
    assert(f.modelPayload.contains(BridgeOrigin.MarkerOpen), f.modelPayload)
    assert(f.modelPayload.contains(BridgeOrigin.MarkerClose), f.modelPayload)
    assertEquals(f.modelPayload.split(BridgeOrigin.MarkerOpen, -1).length, 2, f.modelPayload)
    assertEquals(f.modelPayload.split(BridgeOrigin.MarkerClose, -1).length, 2, f.modelPayload)
    // the model keeps the source attribution the marker encodes
    assert(f.modelPayload.contains("以下消息来自飞书（社交接口 · 渠道 feishu）"), f.modelPayload)
  }

  test("CRITERION 3 (invariant): origin = None leaves every face byte-identical") {
    val f = BridgeOrigin.faces(None, Some("ou_sender"), "hello")
    assertEquals(f.sessionDisplay, "hello")
    assertEquals(f.modelPayload, "hello")
    // the metadata base is the pre-batch object, byte-for-byte (`senderId` only);
    // an unannounced channel adds no channel keys.
    assertEquals(f.metadata, io.circe.JsonObject("senderId" -> io.circe.Json.fromString("ou_sender")))
    // and a None senderId still encodes as JSON null, as before
    assertEquals(
      BridgeOrigin.faces(None, None, "x").metadata,
      io.circe.JsonObject("senderId" -> io.circe.Json.Null)
    )
  }

  test("the structured origin rides in metadata when a channel announces itself, and only then") {
    val none = BridgeOrigin.faces(Some(feishu.copy(chatRef = None)), Some("s"), "hi").metadata
    assertEquals(none("channelId"), Some(io.circe.Json.fromString("feishu")))
    assertEquals(none("channelDisplay"), Some(io.circe.Json.fromString("飞书")))
    // a blank chatRef adds no key rather than a guessed conversation name
    assertEquals(none("chatRef"), None)
    val some = BridgeOrigin.faces(Some(feishu), Some("s"), "hi").metadata
    assertEquals(some("chatRef"), Some(io.circe.Json.fromString("oc_123")))
    // blank chatRef is normalised away, same as the marker's rendering rule
    assertEquals(BridgeOrigin.faces(Some(feishu.copy(chatRef = Some("  "))), Some("s"), "hi").metadata("chatRef"), None)
  }

  test("SOURCE CONTRACT: the one bridge injection point sends each face to its own consumer") {
    val src = os.read(os.pwd / "src" / "main" / "scala" / "nebflow" / "gateway" / "WebSocketRoutes.scala")
    val iFaces = src.indexOf("nebflow.bridge.BridgeOrigin.faces(origin, senderId, content)")
    assert(iFaces > 0, "handleBridgeMessage no longer splits the message into faces")

    val iRecord = src.indexOf("UiMessage.User(faces.sessionDisplay", iFaces)
    assert(iRecord > iFaces, "the recorded UiMessage row must carry the DISPLAY face (bare content)")

    val iBroadcast = src.indexOf("\"text\" -> faces.sessionDisplay.asJson", iFaces)
    assert(iBroadcast > iFaces, "the bridgeUser broadcast must carry the DISPLAY face (bare content)")

    val iPayload = src.indexOf("payload = faces.modelPayload", iFaces)
    assert(iPayload > iFaces, "the ExternalEvent payload must carry the MODEL face (marker retained)")

    val iMeta = src.indexOf("metadata = faces.metadata", iFaces)
    assert(iMeta > iFaces, "the ExternalEvent metadata must carry the structured origin")

    // and the pre-fix form is gone: no consumer may take the composed string again
    assert(!src.contains("UiMessage.User(labelled"),
      "the recorded row is fed a labelled string again — the marker leaks back into the session display face")
    assert(!src.contains("\"text\" -> labelled"),
      "the broadcast is fed a labelled string again — the marker leaks back into the session display face")
    assert(!src.contains("val labelled = nebflow.bridge.BridgeOrigin.compose("),
      "handleBridgeMessage composes a single string again instead of splitting the faces")
  }

