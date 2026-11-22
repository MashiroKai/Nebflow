package nebflow.gateway.ws

import nebflow.gateway.*

import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite
import nebflow.core.tools.ImageInject
import nebflow.shared.{ContentBlock, UiMessage}

import java.util.Base64

/**
 * canvas ask 附图批（2026-11-22）· 附图提问的入站解析 / 落盘降级 / 回放投影。
 *
 * 钉三件事（改前红 / 改后绿）：
 *   ① parse：件数闸（= AttachContract.MaxAttachmentsPerMessage）、仅具体图片 mime、
 *      base64 合法、压缩管线产出 vision 字节；非法形态整条拒（Left）；
 *   ② save（无 ownerAccess）：不落盘、行件 path=null、模型侧只有 vision 块（无标签线）；
 *   ③ 回放投影 encodeTranscript：user 行携带 attachments（{name,type,path}）；
 *      「图+无正文」行保留（text 可空，行不被丢）；无附件行形状逐字不变。
 */
class AskAttachmentSpec extends FunSuite:

  /** 1×1 红色 PNG（与 ImageAttachSpec 同源 fixture）。 */
  private val pngB64: String =
    Base64.getEncoder.encodeToString(
      Array[Byte](
        0x89.toByte, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x02, 0x00, 0x00, 0x00, 0x90.toByte, 0x77, 0x53,
        0xde.toByte, 0x00, 0x00, 0x00, 0x0c, 0x49, 0x44, 0x41, 0x54, 0x08, 0xd7.toByte, 0x63, 0xf8.toByte, 0xcf.toByte,
        0xc0.toByte, 0x00, 0x00, 0x03, 0x01, 0x01, 0x00, 0x18, 0xdd.toByte, 0x8d.toByte, 0xb0.toByte, 0x00, 0x00,
        0x00, 0x00, 0x49, 0x45, 0x4e, 0x44, 0xae.toByte, 0x42, 0x60, 0x82.toByte
      )
    )

  private def img(name: String, mime: String = "image/png", data: String = pngB64): Json =
    Json.obj("name" -> name.asJson, "mimeType" -> mime.asJson, "data" -> data.asJson, "size" -> 70.asJson)

  test("parse: a valid PNG passes the intake and yields vision bytes") {
    val r = AskAttachmentIntake.parse(List(img("shot.png")))
    assert(r.isRight, clues(r))
    val items = r.toOption.get
    assertEquals(items.map(_.name), List("shot.png"))
    assert(items.head.bytes.nonEmpty)
  }

  test("parse: more than the per-message cap is refused whole") {
    val many = List.fill(AskAttachmentIntake.MaxFiles + 1)(img("a.png"))
    assert(AskAttachmentIntake.parse(many).isLeft)
  }

  test("parse: non-image and wildcard mime are refused (ask intake is vision-only)") {
    assert(AskAttachmentIntake.parse(List(img("doc.pdf", mime = "application/pdf"))).isLeft)
    assert(AskAttachmentIntake.parse(List(img("x", mime = "image/*"))).isLeft)
  }

  test("parse: missing data and invalid base64 are refused") {
    assert(AskAttachmentIntake.parse(List(img("e.png", data = ""))).isLeft)
    assert(AskAttachmentIntake.parse(List(img("bad.png", data = "!!!not-base64***"))).isLeft)
  }

  test("save without an owner: nothing persisted, path null, model gets the vision block only") {
    val items = AskAttachmentIntake.parse(List(img("shot.png"))).toOption.get
    val saved = AskAttachmentIntake.save("ask-1a2b3c4d", items, owner = None).unsafeRunSync()
    assertEquals(saved.size, 1)
    val row = saved.head
    assertEquals(row.uiJson.hcursor.downField("path").focus, Some(Json.Null))
    assertEquals(row.uiJson.hcursor.downField("type").as[String], Right("image"))
    assertEquals(row.blocks.size, 1)
    assert(row.blocks.head.isInstanceOf[ContentBlock.Image])
  }

  test("save: vision blocks are capped at ImageInject.MAX_ATTACHMENTS; extra files stay in the UI row without a block") {
    val cap = ImageInject.MAX_ATTACHMENTS
    val items = AskAttachmentIntake.parse(List.fill(cap + 2)(img("shot.png"))).toOption.get
    val saved = AskAttachmentIntake.save("ask-1a2b3c4d", items, owner = None).unsafeRunSync()
    assertEquals(saved.size, cap + 2)
    assertEquals(saved.count(_.blocks.exists(_.isInstanceOf[ContentBlock.Image])), cap)
    assert(saved.drop(cap).forall(_.blocks.isEmpty))
    assertEquals(saved.map(_.uiJson.hcursor.downField("name").as[String]).distinct, List(Right("shot.png")))
  }

  test("replay: a user row keeps its attachments (name/type/path) and a text-less image row survives") {
    val att = Json.obj("name" -> "shot.png".asJson, "type" -> "image".asJson, "path" -> "/x/uploads/ask-1a2b3c4d/1-shot.png".asJson)
    val rows = WsAskHandlers.encodeTranscript(
      List(UiMessage.User(text = "", attachments = List(att), timestamp = 1L))
    )
    assertEquals(rows.size, 1, clues(rows))
    val row = rows.head
    assertEquals(row.hcursor.downField("role").as[String], Right("user"))
    assertEquals(row.hcursor.downField("text").as[String], Right(""))
    assertEquals(row.hcursor.downField("attachments").as[List[Json]], Right(List(att)))
  }

  test("replay: a plain text user row keeps the legacy shape (no attachments key)") {
    val rows = WsAskHandlers.encodeTranscript(List(UiMessage.User(text = "hi", timestamp = 1L)))
    assertEquals(rows.size, 1)
    assert(rows.head.hcursor.downField("attachments").focus.isEmpty)
    assertEquals(rows.head.hcursor.downField("text").as[String], Right("hi"))
  }

  test("replay: an attachment-only row with a null path still decodes (path-less rows are filtered client-side)") {
    val att = Json.obj("name" -> "x.png".asJson, "type" -> "image".asJson, "path" -> Json.Null)
    val rows = WsAskHandlers.encodeTranscript(List(UiMessage.User(text = "", attachments = List(att), timestamp = 1L)))
    assertEquals(rows.size, 1)
    assertEquals(rows.head.hcursor.downField("attachments").as[List[Json]], Right(List(att)))
  }
end AskAttachmentSpec
