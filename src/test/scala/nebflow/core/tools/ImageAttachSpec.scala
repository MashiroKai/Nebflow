package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.parser.decode
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.PathUtil
import nebflow.core.flow.MailQueueStore
import nebflow.shared.{ContentBlock, Message, MessageRole}

import java.nio.file.{Files, Paths}
import java.util.Base64

/**
 * G3: Mail/Delegate/SubTask structured image attachment channel.
 * Covers ImageInject (parse / resolve / drain / messageBlocks), the
 * MailQueueItem persistence round-trip (D6 restart recovery), MailTool
 * fail-fast ordering, and dual-channel degradation under image stripping
 * (non-vision recipients keep the path text).
 */
class ImageAttachSpec extends CatsEffectSuite:

  private val testDir = Files.createTempDirectory("g3-image-attach")

  // Minimal valid 1x1 red PNG (same fixture as ReadImageSpec)
  private val pngBytes: Array[Byte] = Array[Byte](
    0x89.toByte, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
    0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52,
    0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
    0x08, 0x02, 0x00, 0x00, 0x00, 0x90.toByte, 0x77, 0x53, 0xde.toByte,
    0x00, 0x00, 0x00, 0x0c, 0x49, 0x44, 0x41, 0x54,
    0x08, 0xd7.toByte, 0x63, 0xf8.toByte, 0xcf.toByte, 0xc0.toByte, 0x00, 0x00,
    0x00, 0x03, 0x00, 0x01, 0x50, 0x74, 0x1c.toByte, 0xae.toByte,
    0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4e, 0x44,
    0xae.toByte, 0x42, 0x60, 0x82.toByte
  )

  private def writePng(name: String): String =
    val p = testDir.resolve(name)
    Files.write(p, pngBytes)
    p.toString

  private def mailInput(fields: (String, io.circe.Json)*): io.circe.JsonObject =
    io.circe.JsonObject.fromIterable(fields)

  // ============================================================
  // parseImagesParam
  // ============================================================

  test("parseImagesParam: absent → Nil, strings pass, >5 rejected, non-string rejected"):
    assertEquals(ImageInject.parseImagesParam(io.circe.JsonObject()), Right(Nil))
    assertEquals(
      ImageInject.parseImagesParam(mailInput("images" -> io.circe.Json.arr("/a.png".asJson))),
      Right(List("/a.png"))
    )
    // blank entries dropped
    assertEquals(
      ImageInject.parseImagesParam(mailInput("images" -> io.circe.Json.arr("".asJson))),
      Right(Nil)
    )
    // >5
    val six = io.circe.Json.arr((1 to 6).map(i => s"/tmp/x$i.png".asJson)*)
    ImageInject.parseImagesParam(mailInput("images" -> six)) match
      case Left(err) => assert(err.message.contains("Too many image attachments: 6"))
      case Right(_)  => fail("expected max-attachments error")
    // non-string entry
    ImageInject.parseImagesParam(mailInput("images" -> io.circe.Json.arr(42.asJson))) match
      case Left(err) => assert(err.message.contains("array of file path strings"))
      case Right(_)  => fail("expected type error")
    // images not an array
    ImageInject.parseImagesParam(mailInput("images" -> "/a.png".asJson)) match
      case Left(err) => assert(err.message.contains("array of file path strings"))
      case Right(_)  => fail("expected type error")

  // ============================================================
  // resolveImages — success (dual-channel blocks)
  // ============================================================

  test("resolveImages: valid PNG → [label Text, Image] with matching base64"):
    val path = writePng("ok.png")
    ImageInject.resolveImages(List(path)).map {
      case Right(blocks) =>
        assertEquals(blocks.length, 2)
        blocks.head match
          case ContentBlock.Text(t) => assertEquals(t, s"[Mail 附件图片: $path]")
          case other                => fail(s"expected label Text first, got $other")
        blocks(1) match
          case ContentBlock.Image(data, mime) =>
            assertEquals(mime, "image/png")
            assertEquals(data, Base64.getEncoder.encodeToString(pngBytes))
          case other => fail(s"expected Image second, got $other")
      case Left(err) => fail(s"unexpected error: ${err.message}")
    }

  test("resolveImages: multiple images flatten in order"):
    val p1 = writePng("a.png")
    val p2 = writePng("b.png")
    ImageInject.resolveImages(List(p1, p2)).map {
      case Right(blocks) =>
        assertEquals(blocks.length, 4)
        val texts = blocks.collect { case ContentBlock.Text(t) => t }
        assertEquals(texts, List(s"[Mail 附件图片: $p1]", s"[Mail 附件图片: $p2]"))
      case Left(err) => fail(s"unexpected error: ${err.message}")
    }

  // ============================================================
  // resolveImages — four error classes (fail fast, descriptive)
  // ============================================================

  test("resolveImages: relative path rejected"):
    ImageInject.resolveImages(List("relative/shot.png")).map {
      case Left(err) =>
        assert(err.message.contains("must be absolute"))
        assert(!err.message.contains("TransferFile"))
      case Right(_) => fail("expected absolute-path error")
    }

  test("resolveImages: remote device path (Windows drive) rejected with TransferFile hint"):
    ImageInject.resolveImages(List("""C:\Users\Kai\shot.png""")).map {
      case Left(err) =>
        // PathUtil.isAbsolute accepts drive letters, so the error surfaces at
        // the existence check — enriched with D2's remote guidance.
        assert(err.message.contains("does not exist"))
        assert(err.message.contains("remote device paths are not supported"))
        assert(err.message.contains("TransferFile"))
      case Right(_) => fail("expected remote-path error")
    }

  test("resolveImages: nonexistent file rejected"):
    ImageInject.resolveImages(List("/nonexistent/g3/shot.png")).map {
      case Left(err)  => assert(err.message.contains("does not exist"))
      case Right(_)   => fail("expected nonexistent error")
    }

  test("resolveImages: directory rejected"):
    ImageInject.resolveImages(List(testDir.toString)).map {
      case Left(err)  => assert(err.message.contains("directory"))
      case Right(_)   => fail("expected directory error")
    }

  test("resolveImages: non-image extension rejected with path-text guidance"):
    val txt = testDir.resolve("notes.txt")
    Files.write(txt, "hello".getBytes)
    ImageInject.resolveImages(List(txt.toString)).map {
      case Left(err) =>
        assert(err.message.contains("images only"))
        assert(err.message.contains("reference the path in your message text"))
      case Right(_) => fail("expected unsupported-type error")
    }

  test("resolveImages: oversized image rejected (10MB cap)"):
    val big = testDir.resolve("big.png")
    Files.write(big, new Array[Byte](10 * 1024 * 1024 + 1))
    ImageInject.resolveImages(List(big.toString)).map {
      case Left(err)  => assert(err.message.contains("too large"))
      case Right(_)   => fail("expected too-large error")
    }

  test("resolveImages: empty list → Right(Nil)"):
    ImageInject.resolveImages(Nil).map(r => assertEquals(r, Right(Nil)))

  // ============================================================
  // drainImagePaths — D6 queue-drain semantics
  // ============================================================

  test("drainImagePaths: existing file loads like resolveImages"):
    val path = writePng("drain.png")
    ImageInject.drainImagePaths(List(path)).map { blocks =>
      assertEquals(blocks.length, 2)
      blocks.head match
        case ContentBlock.Text(t) => assertEquals(t, s"[Mail 附件图片: $path]")
        case other                => fail(s"expected label, got $other")
    }

  test("drainImagePaths: lost file degrades to [attachment lost: path], never fails"):
    ImageInject.drainImagePaths(List("/nonexistent/g3/gone.png")).map { blocks =>
      assertEquals(blocks, List(ContentBlock.Text("[attachment lost: /nonexistent/g3/gone.png]")))
    }

  // ============================================================
  // messageBlocks — the "text dropped when blocks present" trap
  // ============================================================

  test("messageBlocks: no attachments → None (plain string message, zero change)"):
    assertEquals(ImageInject.messageBlocks("hello", Nil), None)

  test("messageBlocks: message text is the FIRST Text block"):
    val att = List(ContentBlock.Text("label"), ContentBlock.Image("b64", "image/png"))
    ImageInject.messageBlocks("hello", att) match
      case Some(ContentBlock.Text("hello") :: rest) => assertEquals(rest, att)
      case other => fail(s"expected message Text head, got $other")

  // ============================================================
  // MailTool — fail-fast ordering + regression (no images param)
  // ============================================================

  private def ctx = ToolContext(projectRoot = testDir.toString)

  test("MailTool regression: no images param, missing address → unchanged error"):
    MailTool.call(mailInput("message" -> "hi".asJson), ctx).map {
      case Left(err)  => assertEquals(err.message, "Missing required parameter: address")
      case Right(r)   => fail(s"expected error, got $r")
    }

  test("MailTool regression: no images param, no actor system → unchanged error"):
    val input = mailInput("address" -> "backend".asJson, "message" -> "hi".asJson)
    MailTool.call(input, ctx).map {
      case Left(err)  => assertEquals(err.message, "No actor system available")
      case Right(r)   => fail(s"expected error, got $r")
    }

  test("MailTool: >5 images rejected before any resolution"):
    val six = io.circe.Json.arr((1 to 6).map(i => s"/tmp/s$i.png".asJson)*)
    val input = mailInput("address" -> "backend".asJson, "message" -> "hi".asJson, "images" -> six)
    MailTool.call(input, ctx).map {
      case Left(err)  => assert(err.message.contains("Too many image attachments"))
      case Right(r)   => fail(s"expected error, got $r")
    }

  test("MailTool: invalid attachment fails fast even without actor system"):
    val input = mailInput(
      "address" -> "backend".asJson,
      "message" -> "hi".asJson,
      "images" -> io.circe.Json.arr("relative.png".asJson)
    )
    MailTool.call(input, ctx).map {
      // attachment error surfaces BEFORE the "no actor system" check
      case Left(err)  => assert(err.message.contains("must be absolute"))
      case Right(r)   => fail(s"expected error, got $r")
    }

  test("MailTool: valid attachment resolves, then fails on missing actor system"):
    val path = writePng("mail.png")
    val input = mailInput(
      "address" -> "backend".asJson,
      "message" -> "see attachment".asJson,
      "images" -> io.circe.Json.arr(path.asJson)
    )
    MailTool.call(input, ctx).map {
      case Left(err)  => assertEquals(err.message, "No actor system available")
      case Right(r)   => fail(s"expected error, got $r")
    }

  // ============================================================
  // MailQueueStore — D6 persistence round-trip (restart recovery)
  // ============================================================

  override def beforeAll(): Unit =
    // Redirect dataRoot so queue writes stay in the temp dir
    PathUtil.setDataRoot(os.Path(testDir.toString))

  override def afterAll(): Unit =
    // Restore default (~/.nebflow) for subsequent specs sharing the JVM
    PathUtil.setDataRoot(os.home / ".nebflow")
    try
      Files.walk(testDir).sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))
    catch case _: Exception => ()

  test("MailQueueItem: codec round-trip preserves imagePaths"):
    val item = MailQueueStore.MailQueueItem(
      id = "mail-q-abc",
      from = "Manager",
      fromSession = "s1",
      message = "check screenshot",
      `type` = "INFO",
      timestamp = 12345L,
      imagePaths = List("/tmp/a.png", "/tmp/b.jpg")
    )
    decode[MailQueueStore.MailQueueItem](item.asJson.noSpaces) match
      case Right(back) => assertEquals(back.imagePaths, List("/tmp/a.png", "/tmp/b.jpg"))
      case Left(err)   => fail(s"round-trip failed: $err")

  test("MailQueueItem: old-format JSON (no imagePaths) decodes to Nil"):
    val old =
      """[{"id":"mail-q-x","from":"Manager","fromSession":"s1","message":"m","type":"INFO","timestamp":1}]"""
    decode[List[MailQueueStore.MailQueueItem]](old) match
      case Right(items) =>
        assertEquals(items.length, 1)
        assertEquals(items.head.imagePaths, Nil)
        assertEquals(items.head.message, "m")
      case Left(err) => fail(s"old-format decode failed: $err")

  test("MailQueueStore: append + load round-trip persists imagePaths to disk"):
    val sid = "g3-queue-test"
    val item = MailQueueStore.MailQueueItem(
      id = "mail-q-disk",
      from = "Manager",
      fromSession = "s1",
      message = "queued with image",
      `type` = "RESULT",
      timestamp = 1L,
      imagePaths = List("/tmp/queued.png")
    )
    for
      _ <- MailQueueStore.append(sid, item)
      loaded <- MailQueueStore.load(sid)
      _ <- IO {
        assertEquals(loaded.map(_.imagePaths), List(List("/tmp/queued.png")))
        assertEquals(loaded.head.message, "queued with image")
      }
      _ <- MailQueueStore.removeHead(sid)
      after <- MailQueueStore.load(sid)
      _ <- IO(assertEquals(after.length, 0))
    yield ()

  // ============================================================
  // Dual-channel degradation — non-vision recipients keep the path
  // ============================================================

  test("stripImages self-consistency: Image → placeholder, path Text survives"):
    val path = writePng("strip.png")
    val blocks = ImageInject.messageBlocks("look at this", ImageInject.resolveImages(List(path)).unsafeRunSync() match
      case Right(att) => att
      case Left(err)  => fail(s"resolve failed: ${err.message}")
    ).get
    val msg = Message(MessageRole.User, Right(blocks))
    val stripped = nebflow.core.compact.CompactUtils.stripImages(List(msg)).head
    stripped.content match
      case Right(bl) =>
        val texts = bl.collect { case ContentBlock.Text(t) => t }
        // message text AND path label survive; Image became a placeholder
        assert(texts.exists(_ == "look at this"))
        assert(texts.exists(_ == s"[Mail 附件图片: $path]"))
        assert(texts.exists(_.contains("[image:"))) // CompactUtils placeholder: [image: <mime>]
        assert(!bl.exists(_.isInstanceOf[ContentBlock.Image]))
      case Left(t) => fail(s"unexpected plain content: $t")

end ImageAttachSpec
