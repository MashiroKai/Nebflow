package nebflow.social

import munit.FunSuite

/**
 * Offline spec for the channel-agnostic inbound parser (inbound-parse batch,
 * 2026-10-01). Pure functions only — no network, no credential, no SDK.
 *
 * Every expectation below is copied VERBATIM from the design card's fixtures
 * (§4.3 / §5.4 / §6.3 / §8.1), so a failure here means the parser drifted from
 * the reviewed contract, not that the fixture is wrong.
 */
class InboundParseSpec extends FunSuite:

  private def mention(key: String, name: Option[String] = None, id: Option[String] = None): InboundParse.Mention =
    InboundParse.Mention(key, name, id)

  // ───────────────────── post rich text (IP-1…IP-3) ─────────────────────

  test("IP-1 post degrades to readable lines, verbatim") {
    val raw =
      """{"title":"发布说明","content":[[{"tag":"text","text":"版本 "},{"tag":"a","text":"v2","href":"https://x/y"}],[{"tag":"at","user_id":"ou_1","user_name":"张三"},{"tag":"text","text":" 请看 "},{"tag":"img","image_key":"img_v2_k"}]]}"""
    assertEquals(InboundParse.renderPost(raw),
      Some("发布说明\n版本 v2(https://x/y)\n@张三 请看 [图片 image_key=img_v2_k]"))
  }

  test("IP-2 an unknown post tag leaves a visible trace (never silently dropped)") {
    assertEquals(InboundParse.renderPost("""{"content":[[{"tag":"text","text":"a"},{"tag":"nope","x":"y"}]]}"""),
      Some("a[nope]"))
  }

  test("IP-3 empty content / null title degrade without inventing lines") {
    assertEquals(InboundParse.renderPost("""{"title":"T","content":[]}"""), Some("T"))
    assertEquals(InboundParse.renderPost("""{"content":[[{"tag":"text","text":"空"}]],"title":null}"""), Some("空"))
    // A payload that is not the documented shape answers None — the caller then
    // echoes the raw content rather than guessing a structure that is not there.
    assertEquals(InboundParse.renderPost("not-json"), None)
  }

  test("IP-2b an element with no readable strings is the ONLY thing allowed to vanish") {
    assertEquals(InboundParse.renderElement(io.circe.Json.obj("tag" -> io.circe.Json.fromString("hr"))), "")
    assertEquals(InboundParse.renderElement(io.circe.Json.obj("tag" -> io.circe.Json.fromString("emotion"))), "")
    // …but one carrying a value keeps its tag visible.
    assertEquals(InboundParse.renderElement(
      io.circe.Json.obj("tag" -> io.circe.Json.fromString("media"), "file_key" -> io.circe.Json.fromString("fk"))),
      "[视频 file_key=fk]")
  }

  // ───────────────────── @mentions (IP-4…IP-7) ─────────────────────

  test("IP-4 placeholders resolve to the mention's display name / id") {
    val text = "@_user_1 你好 @_user_2"
    val ms = List(mention("_user_1", Some("张三"), Some("ou_1")), mention("_user_2", None, Some("ou_2")))
    assertEquals(InboundParse.renderMentions(text, ms), "@张三 你好 @ou_2")
  }

  test("IP-5 an unmatched placeholder is KEPT as-is (never swallowed)") {
    assertEquals(InboundParse.renderMentions("@_user_9 hi", Nil), "@_user_9 hi")
  }

  test("IP-6 a mention with no placeholder in the body adds nothing") {
    assertEquals(InboundParse.renderMentions("hi", List(mention("_user_1", Some("张三"), Some("ou_1")))), "hi")
  }

  test("IP-7 a mention with neither name nor id falls back to the fixed word") {
    assertEquals(InboundParse.renderMentions("@_user_1 hi", List(mention("_user_1"))), "@某人 hi")
  }

  // ───────────────────── quoted reply (IP-8) ─────────────────────

  test("IP-8 branch A: a fetched original renders sender + text") {
    val s = InboundParse.quoteSection(Some("om_q"), Some("张三"), Some("原文内容")).get
    assert(s.contains("[引用的消息]"), s)
    assert(s.contains("发送方：张三"), s)
    assert(s.contains("原文：\n原文内容"), s)
    assert(s.contains("[/引用的消息]"), s)
  }

  test("IP-8 branch B: an unfetched original NAMES the id it could not read") {
    val s = InboundParse.quoteSection(Some("om_q"), Some("张三"), None).get
    assert(s.contains("原文：未取得（message_id=om_q）"), s)
  }

  test("IP-8 branch B default: an unknown sender renders the fixed placeholder") {
    val s = InboundParse.quoteSection(Some("om_q"), None, None).get
    assert(s.contains(s"发送方：${InboundParse.UnknownSender}"), s)
  }

  test("IP-8 branch C: no parent/root id means NOT a reply — no block at all") {
    assertEquals(InboundParse.quoteSection(None, Some("张三"), Some("原文")), None)
    // an empty-string id normalises to "no id" too
    assertEquals(InboundParse.quoteSection(Some("  "), None, None), None)
  }

  // ───────────────────── unrecognised type (IP-9) ─────────────────────

  test("IP-9 an unrecognised type yields the verbatim placeholder with its three mandatory fields") {
    val expected =
      """[未识别的消息类型：image]
        |message_id=om_9
        |原始内容：
        |{"image_key":"img_v2_abc"}
        |[/未识别的消息类型]""".stripMargin
    assertEquals(InboundParse.unrecognized("image", "om_9", """{"image_key":"img_v2_abc"}"""), expected)
  }

  test("IP-9b every non-text, non-post type lands in the placeholder") {
    assertEquals(InboundParse.bodyOf("file", "om_10", """{"file_key":"file_v2_x"}"""),
      Option(InboundParse.unrecognized("file", "om_10", """{"file_key":"file_v2_x"}""")))
    assert(InboundParse.bodyOf("sticker", "om_11", "{}").exists(_.contains("[未识别的消息类型：sticker]")))
  }

  // ───────────────────── body cap (IP-10) ─────────────────────

  test("IP-10 an over-long body is cut to the cap and the cut is stated") {
    val long = "x" * 3000
    val out = InboundParse.bodyOf("text", "om_1", io.circe.Json.obj("text" -> io.circe.Json.fromString(long)).noSpaces)
    val body = out.get
    assertEquals(body.length, InboundParse.BodyCap + "…（已截断，原文共 3000 字）".length)
    assert(body.endsWith("…（已截断，原文共 3000 字）"), body)
    // The cap applies to the unrecognised placeholder's raw echo as well.
    assert(InboundParse.unrecognized("image", "om_1", long).contains("…（已截断，原文共 3000 字）"))
  }

  test("IP-10b a body at or under the cap is untouched") {
    val exact = "y" * 2000
    val out = InboundParse.bodyOf("text", "om_1", io.circe.Json.obj("text" -> io.circe.Json.fromString(exact)).noSpaces)
    assertEquals(out, Some(exact))
  }

  // ───────────────────── body selection + composition ─────────────────────

  test("bodyOf: text is mention-replaced, an empty text body answers None") {
    val raw = """{"text":"@_user_1 hi"}"""
    assertEquals(InboundParse.bodyOf("text", "om_1", raw, List(mention("_user_1", Some("张三")))), Some("@张三 hi"))
    assertEquals(InboundParse.bodyOf("text", "om_1", """{"text":""}"""), None)
  }

  test("bodyOf: a text payload that is not JSON echoes the raw content instead of vanishing") {
    assertEquals(InboundParse.bodyOf("text", "om_1", "plain words"), Some("plain words"))
  }

  test("compose: quote段 then body, one blank line apart; empty sets answer None") {
    val q = InboundParse.quoteSection(Some("om_q"), None, None)
    assertEquals(InboundParse.compose(q, Some("正文")), Some(q.get + "\n\n正文"))
    assertEquals(InboundParse.compose(None, Some("正文")), Some("正文"))
    assertEquals(InboundParse.compose(q, None), Some(q.get))
    assertEquals(InboundParse.compose(None, None), None)
  }
