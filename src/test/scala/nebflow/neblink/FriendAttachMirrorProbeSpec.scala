package nebflow.neblink

import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import munit.FunSuite
import nebflow.neblink.FriendCodecs.given

/** 4b 腿 A-1 的**前红后绿探针**（信息包 C1/C2/C3 的现读病灶本体）。
  *
  * 🔴 本件刻意**只使用基线就有的 API**（`MessageSummary` 的 decode/encode + 原始 JSON），
  * 不引用任何本批新增类型 ⇒ 同一份源码在**基线 sha** 上可编译，红是**行为红**：
  * 基线 `MessageSummary`（5 字段 + `deriveEncoder`）会把服务端已下发的 `attachments`
  * 键在网关 re-encode 处**静默丢弃**，①组断言失败。
  *
  * 断言面 = REST 出口实际走的那两步（`RestApiRoutes.scala` 的 `r.map(_.asJson)` /
  * `convs.asJson`）：先 `Decoder[MessageSummary]` 解码，再 `Encoder[MessageSummary]` 编码。
  */
class FriendAttachMirrorProbeSpec extends FunSuite:

  private val serverMessage =
    """{"id":42,"senderId":"u-peer","kind":"text","body":"[附件] report.pdf","createdAt":1789000000000,
      | "attachments":[{"id":"att-1","name":"report.pdf","size":10485760,"mime":"application/pdf",
      | "sha256":"ab12","state":"ready"}]}""".stripMargin

  private def roundTrip(json: String): Json =
    val decoded = parse(json).flatMap(_.as[List[MessageSummary]]).fold(e => fail(s"decode failed: $e"), identity)
    decoded.asJson

  test("A-1 (red nail): attachments survive the gateway re-encode (decode -> asJson)") {
    val out = roundTrip(s"[$serverMessage]")
    val att = out.hcursor.downArray.downField("attachments")
    assert(att.succeeded, s"attachments key was dropped by the re-encode: ${out.noSpaces}")
    val entry = att.downN(0)
    assertEquals(entry.get[String]("id").toOption, Some("att-1"))
    assertEquals(entry.get[String]("name").toOption, Some("report.pdf"))
    assertEquals(entry.get[Long]("size").toOption, Some(10485760L))
    assertEquals(entry.get[String]("sha256").toOption, Some("ab12"))
    // 🔴 `state` 必填且**逐字透传**（§B.7 的线上枚举是客户端唯一判读依据）
    assertEquals(entry.get[String]("state").toOption, Some("ready"))
    assertEquals(entry.get[String]("mime").toOption, Some("application/pdf"))
  }

  test("A-1/§B.3: a message without attachments re-encodes byte-identically (no `attachments` key, no null)") {
    val legacy = """{"id":7,"senderId":"u1","kind":"text","body":"hi","createdAt":123}"""
    val out    = roundTrip(s"[$legacy]")
    val m      = out.hcursor.downArray
    assert(!m.downField("attachments").succeeded, s"legacy message must NOT gain an attachments key: ${out.noSpaces}")
    assertEquals(m.focus.map(_.noSpaces), parse(legacy).toOption.map(_.noSpaces))
  }

  test("A-1/§B.3: an explicit empty array is equivalent to 'no attachments' (key omitted)") {
    val withEmpty = """{"id":7,"senderId":"u1","kind":"text","body":"hi","createdAt":123,"attachments":[]}"""
    val out       = roundTrip(s"[$withEmpty]")
    assert(!out.hcursor.downArray.downField("attachments").succeeded, out.noSpaces)
  }

  test("A-1/§B.7: expired attachments keep name+size and the expired state") {
    val expired =
      """{"id":9,"senderId":"u1","kind":"text","body":"[附件] a.bin","createdAt":1,
        | "attachments":[{"id":"att-9","name":"a.bin","size":12,"sha256":"cd","state":"expired"}]}""".stripMargin
    val out = roundTrip(s"[$expired]")
    val e   = out.hcursor.downArray.downField("attachments").downN(0)
    assertEquals(e.get[String]("state").toOption, Some("expired"))
    assertEquals(e.get[String]("name").toOption, Some("a.bin"))
    assertEquals(e.get[Long]("size").toOption, Some(12L))
  }
end FriendAttachMirrorProbeSpec
