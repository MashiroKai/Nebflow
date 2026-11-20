package nebflow.bridge

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.parser.decode
import io.circe.syntax.*
import munit.FunSuite
import nebflow.actor.AgentCommand
import nebflow.shared.ContentBlock
import nebflow.shared.SessionMeta
// ExternalEvent 的持久化编解码 given 唯一落点（CompactionQueueStore 手写编解码）。
import nebflow.agent.CompactionQueueStore.given

import java.nio.file.Files
import java.util.Base64

/**
 * 社交通道入站媒体统一适配批（weixin-inbound-media-unify, 2026-10-20）——统一
 * 登记纯面的离线 spec。四个面钉死：
 *
 *   UM-1 分类判据 = 控制台 uploads 管线逐字（mime `image/` 前缀 ⇒ image；
 *        通配嗅探形态按 file 收口）；
 *   UM-2 attJson = 控制台落盘行同形 `[{name,type,path}]`；
 *   UM-3 标签线 = 控制台逐字同款（[用户附加图片/文件: <path>]）；
 *   UM-4 vision 构块 = ImageInject.prepareImage 同管线（小图原样成块；超帽/
 *        缺盘/通配 mime ⇒ 零块、永不 raise）+ ExternalEvent.blocks 编解码
 *        新旧快照兼容 + injectMessageWithMedia 缺省降级（飞书腿接口就位的证明）。
 */
class UnifiedMediaSpec extends FunSuite:

  /** 1×1 PNG（<2MB、长边≤1920 ⇒ prepareImage 原样通过，无重编码）。 */
  private val TinyPng: Array[Byte] =
    Base64.getDecoder.decode(
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
    )

  private def tempFile(name: String, bytes: Array[Byte]): String =
    val p = Files.createTempFile("nb-unify-", name)
    Files.write(p, bytes)
    p.toString

  // ── UM-1 classification ──

  test("UM-1a mime image/ prefix ⇒ image; everything else ⇒ file (console gate verbatim)") {
    assertEquals(UnifiedMedia.attachmentTypeOf(Some("image/png")), "image")
    assertEquals(UnifiedMedia.attachmentTypeOf(Some("image/jpeg")), "image")
    assertEquals(UnifiedMedia.attachmentTypeOf(Some("video/mp4")), "file")
    assertEquals(UnifiedMedia.attachmentTypeOf(Some("application/pdf")), "file")
    assertEquals(UnifiedMedia.attachmentTypeOf(None), "file")
    assertEquals(UnifiedMedia.attachmentTypeOf(Some("")), "file")
    assertEquals(UnifiedMedia.attachmentTypeOf(Some("  image/png ")), "image", "trimmed like the console face")
  }

  test("UM-1b the wildcard sniff form is a FILE, never a provider-bound image block") {
    assertEquals(UnifiedMedia.attachmentTypeOf(Some(UnifiedMedia.WildcardImageMime)), "file")
  }

  // ── UM-2 attJson ──

  test("UM-2 attJson rows are console-shaped {name,type,path}; blank pointers drop; name falls back to basename") {
    val atts = List(
      BridgeAttachment("/root/acc/m1-pic.png", Some("pic.png"), Some("image/png")),
      BridgeAttachment("/root/acc/m2-clip.mp4", None, Some("video/mp4")),
      BridgeAttachment("   ", Some("gone"), Some("image/png"))
    )
    val rows = UnifiedMedia.attJsonOf(atts)
    assertEquals(rows.size, 2, "the blank pointer never registers")
    assertEquals(
      rows(0),
      io.circe.Json.obj(
        "name" -> io.circe.Json.fromString("pic.png"),
        "type" -> io.circe.Json.fromString("image"),
        "path" -> io.circe.Json.fromString("/root/acc/m1-pic.png")
      )
    )
    assertEquals(
      rows(1),
      io.circe.Json.obj(
        "name" -> io.circe.Json.fromString("m2-clip.mp4"),
        "type" -> io.circe.Json.fromString("file"),
        "path" -> io.circe.Json.fromString("/root/acc/m2-clip.mp4")
      ),
      "absent name ⇒ path basename; console attJson types are image|file only"
    )
  }

  // ── UM-3 label lines ──

  test("UM-3 label lines are the console wording verbatim ([用户附加图片/文件: <path>])") {
    assertEquals(
      UnifiedMedia.labelLineOf(BridgeAttachment("/r/m1.png", None, Some("image/png"))),
      "[用户附加图片: /r/m1.png]"
    )
    assertEquals(
      UnifiedMedia.labelLineOf(BridgeAttachment("/r/m2.pdf", None, Some("application/pdf"))),
      "[用户附加文件: /r/m2.pdf]"
    )
    assertEquals(
      UnifiedMedia.labelLinesOf(
        List(
          BridgeAttachment("/r/m1.png", None, Some("image/png")),
          BridgeAttachment("/r/m2.pdf", None, Some("application/pdf"))
        )
      ),
      "[用户附加图片: /r/m1.png]\n[用户附加文件: /r/m2.pdf]"
    )
    assertEquals(UnifiedMedia.labelLinesOf(Nil), "")
  }

  // ── UM-4 vision blocks ──

  test("UM-4a a small PNG builds one ContentBlock.Image through the prepareImage pipeline") {
    val p = tempFile("pic.png", TinyPng)
    val blocks = UnifiedMedia.imageBlocksOf(BridgeAttachment(p, Some("pic.png"), Some("image/png"))).unsafeRunSync()
    assertEquals(blocks.map(_.mediaType), List("image/png"))
    assertEquals(
      blocks.map(_.data),
      List(Base64.getEncoder.encodeToString(TinyPng)),
      "under both trigger caps the ORIGINAL bytes ride (the pipeline's pass-through arm)"
    )
  }

  test("UM-4b files build NO block (the path label is the read surface)") {
    val p = tempFile("doc.pdf", "pdf".getBytes)
    val blocks = UnifiedMedia.imageBlocksOf(BridgeAttachment(p, None, Some("application/pdf"))).unsafeRunSync()
    assertEquals(blocks, Nil)
  }

  test("UM-4c fail-closed: missing file / wildcard mime / over-cap ⇒ Nil, never a raise") {
    assertEquals(
      UnifiedMedia.imageBlocksOf(BridgeAttachment("/nope/never-landed.png", None, Some("image/png"))).unsafeRunSync(),
      Nil
    )
    val wild = tempFile("weird.png", TinyPng)
    assertEquals(
      UnifiedMedia.imageBlocksOf(BridgeAttachment(wild, None, Some(UnifiedMedia.WildcardImageMime))).unsafeRunSync(),
      Nil,
      "the wildcard sniff can never ride a provider block"
    )
    val big = tempFile("big.png", new Array[Byte](10 * 1024 * 1024 + 1))
    assertEquals(
      UnifiedMedia.imageBlocksOf(BridgeAttachment(big, None, Some("image/png"))).unsafeRunSync(),
      Nil,
      "over the 10MB pre-compress cap ⇒ text-only delivery"
    )
  }

  // ── codec compat ──

  test("UM-5 ExternalEvent blocks codec: round-trip rides; legacy snapshots (no key) decode as None") {
    val withBlocks = AgentCommand.ExternalEvent(
      "bridge",
      "user-message",
      "payload",
      correlationId = None,
      blocks = Some(List(ContentBlock.Image("abcd", "image/png")))
    )
    val encoded = withBlocks.asJson.noSpaces
    val decoded = decode[AgentCommand.ExternalEvent](encoded).toOption.get
    assertEquals(
      decoded.blocks.map(_.collect { case i: ContentBlock.Image => i.data }),
      Some(List("abcd"))
    )

    val legacy =
      """{"source":"bridge","eventType":"user-message","payload":"p","metadata":{},"correlationId":null}"""
    assertEquals(decode[AgentCommand.ExternalEvent](legacy).toOption.get.blocks, None, "old snapshot ⇒ None")

    val noBlocks = AgentCommand.ExternalEvent("bridge", "user-message", "p")
    assert(!noBlocks.asJson.noSpaces.contains("blocks"), "block-less events never grow the persisted key")
  }

  // ── the Feishu-leg interface: the default degrade ──

  test(
    "UM-6 injectMessageWithMedia default degrades to injectMessage (interface-only legs keep compiling and behaving)"
  ) {
    val recorded = new scala.collection.mutable.ListBuffer[(String, String)]()
    val ctx = new BridgeContext:
      def injectMessage(
        sessionId: String,
        content: String,
        senderId: Option[String],
        origin: Option[BridgeOrigin]
      ): IO[Unit] =
        IO { recorded += ((sessionId, content)) }.void
      def interruptAgent(sessionId: String): IO[Unit] = IO.unit
      def sessionMeta(sessionId: String): IO[Option[SessionMeta]] = IO.none
      def listSessions: IO[List[SessionMeta]] = IO.pure(Nil)
      def updateBridgeConfig(sessionId: String, platform: String, config: Option[io.circe.Json]): IO[Unit] = IO.unit
    ctx
      .injectMessageWithMedia(
        "s1",
        "[图片] 已存至 /r/x.png，可用 Read 查看",
        None,
        None,
        List(BridgeAttachment("/r/x.png", None, Some("image/png")))
      )
      .unsafeRunSync()
    assertEquals(
      recorded.toList,
      List(("s1", "[图片] 已存至 /r/x.png，可用 Read 查看")),
      "the default = the text-only legacy injection, byte-for-byte (Feishu leg untouched)"
    )
  }

end UnifiedMediaSpec
