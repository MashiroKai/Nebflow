package nebflow.neblink

import io.circe.parser.parse
import munit.FunSuite
import nebflow.neblink.FriendCodecs.given

/**
 * 4b 腿 A-1（客户端镜像面）的**本支专属**断言：新增类型的语义与容错方向。
 *
 * 前红后绿的探针在 `FriendAttachMirrorProbeSpec`（那份只用基线 API，基线可跑且必红）；
 * 本件补充「本批新增面」的行为钉：容错折叠、`downloadable` 判据、`state` 枚举映射。
 */
class FriendAttachMirrorSpec extends FunSuite:

  // ④ 越界/缺失 state ⇒ 降级为可判读态，**绝不把条目丢掉**（禁静默丢弃）
  test("A-1: a malformed attachment entry is kept (never silently dropped), state folds to Unknown") {
    val malformed = """{"id":11,"senderId":"u1","kind":"text","body":"x","createdAt":1,
                      | "attachments":[{"id":"att-x","name":"n"},"not-an-object"]}""".stripMargin
    val decoded = parse(malformed).flatMap(_.as[MessageSummary]).fold(e => fail(s"decode failed: $e"), identity)
    val atts = decoded.attachments.getOrElse(fail("attachments dropped"))
    assertEquals(atts.size, 2, "every entry must survive (no silent drop)")
    assert(atts.forall(_.stateKind.isInstanceOf[AttachmentState.Unknown]))
    assert(!atts.exists(_.downloadable), "unreadable entries must not be downloadable")
  }

  test("A-1: a non-array attachments value is surfaced as one unreadable entry (not dropped)") {
    val weird = """{"id":12,"senderId":"u1","kind":"text","body":"x","createdAt":1,"attachments":"oops"}"""
    val decoded = parse(weird).flatMap(_.as[MessageSummary]).fold(e => fail(s"decode failed: $e"), identity)
    assertEquals(decoded.attachments.map(_.size), Some(1))
    assert(!decoded.attachments.exists(_.exists(_.downloadable)))
  }

  test("A-1: `attachments: null` decodes to None (same as key absence, §B.3)") {
    val withNull = """{"id":13,"senderId":"u1","kind":"text","body":"x","createdAt":1,"attachments":null}"""
    val decoded = parse(withNull).flatMap(_.as[MessageSummary]).fold(e => fail(s"decode failed: $e"), identity)
    assertEquals(decoded.attachments, None)
  }

  test("A-1: state mapping is the §B.7 online enum (uploading/ready/expired) with an Unknown fallback") {
    assertEquals(AttachmentState.of("uploading"), AttachmentState.Uploading)
    assertEquals(AttachmentState.of("ready"), AttachmentState.Ready)
    assertEquals(AttachmentState.of("expired"), AttachmentState.Expired)
    assertEquals(AttachmentState.of("pending"), AttachmentState.Unknown("pending"))
    assertEquals(AttachmentState.of(""), AttachmentState.Unknown(""))
  }

  test("A-1: downloadable is true only for ready-with-id (never a clickable-but-failing button)") {
    val ready = AttachmentSummary("att", "n", 1L, "h", "ready")
    assert(ready.downloadable)
    assert(!ready.copy(id = "").downloadable)
    assert(!ready.copy(state = "expired").downloadable)
    assert(!ready.copy(state = "uploading").downloadable)
  }

  test("A-1: `mime` is omitted from the wire when absent (skip_serializing_if parity)") {
    val a = AttachmentSummary("att", "n", 3L, "h", "ready")
    val js = io.circe.syntax.EncoderOps(a).asJson
    assert(!js.hcursor.downField("mime").succeeded, js.noSpaces)
    assert(js.hcursor.downField("state").succeeded)
  }
end FriendAttachMirrorSpec
