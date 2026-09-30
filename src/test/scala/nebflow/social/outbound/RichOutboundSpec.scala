package nebflow.social.outbound

import cats.effect.IO
import cats.effect.Ref
import munit.CatsEffectSuite
import nebflow.shared.AttachContract

import java.awt.image.BufferedImage
import java.nio.file.{Files, StandardOpenOption}
import javax.imageio.ImageIO

/**
 * Offline spec for the rich-content outbound leg (richcontent batch, 2026-09-30).
 *
 * 🔴 NO NETWORK, NO CREDENTIAL, NO BROWSER. Every outbound call the adapter makes
 * — image upload, file upload, image/file message send, text send — is a
 * constructor-injected recording seam, and the renderer is injected too. So this
 * spec proves every DECISION without a socket ever opening, which is exactly what
 * the acceptance criteria demand ("可注入缝做离线断言；禁真联网上传").
 *
 * Each assertion is red-by-construction: it names the guard it pins, and removing
 * that guard makes the test fail rather than silently change shape. The one thing
 * this spec deliberately does NOT prove is that Chrome can render on this host —
 * that is a real-process reading and lives in the live probe (`RichRenderProbe`),
 * because a spec that asserted it would have to start a browser and would stop
 * being offline.
 */
class RichOutboundSpec extends CatsEffectSuite:

  // ───────────────────────────── fixtures ─────────────────────────────

  private val DocumentExts: Set[String] =
    Set("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv", "md", "txt", "json", "zip")

  private def tmpDir(): os.Path = os.temp.dir(prefix = "nb-rich-outbound-")

  /** A REAL PNG of the requested size, written through the JDK's own encoder —
    *  noise, not a solid colour, so it clears the distinct-pixel floor the way a
    *  browser screenshot does. Hand-writing bytes would risk a file that is not
    *  actually decodable, which would make the judgement vacuous. */
  private def writeNoisePng(path: os.Path, w: Int, h: Int): os.Path =
    val img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val rnd = new java.util.Random(42L)
    var y = 0
    while y < h do
      var x = 0
      while x < w do
        img.setRGB(x, y, rnd.nextInt(0xffffff))
        x += 1
      y += 1
    Files.createDirectories(path.toNIO.getParent)
    ImageIO.write(img, "png", path.toNIO.toFile)
    path

  /** Structurally valid but a single flat colour — the shape an empty/blank
    *  render produces, and the thing HC-3 must reject. */
  private def writeSolidPng(path: os.Path, w: Int, h: Int, rgb: Int): os.Path =
    val img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    var y = 0
    while y < h do
      var x = 0
      while x < w do
        img.setRGB(x, y, rgb)
        x += 1
      y += 1
    Files.createDirectories(path.toNIO.getParent)
    ImageIO.write(img, "png", path.toNIO.toFile)
    path

  /** A sparse file of the requested length — exercises the REAL gate without
    *  writing a gigabyte. */
  private def sparseFile(path: os.Path, bytes: Long): os.Path =
    Files.createDirectories(path.toNIO.getParent)
    val ch = Files.newByteChannel(path.toNIO, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    try
      ch.position(bytes - 1)
      ch.write(java.nio.ByteBuffer.wrap(Array[Byte](0)))
    finally ch.close()
    path

  /** Recording renderer: never starts a process, counts its invocations (so a
    *  test can prove the image/document legs do NOT render), and captures the HTML
    *  it was handed (so `htmlOf`'s extraction is pinned). */
  private final class RecordingRenderer(
      result: RichRenderer.RenderResult,
      produce: Option[(os.Path, Int, Int) => Unit] = None
  ) extends RichRenderer.Renderer:
    val calls: Ref[IO, Int] = Ref.unsafe[IO, Int](0)
    val lastHtml: Ref[IO, String] = Ref.unsafe[IO, String]("")
    def available: Boolean = true
    def render(html: String, outPng: os.Path, width: Int, height: Int): IO[RichRenderer.RenderResult] =
      calls.update(_ + 1) *> lastHtml.set(html) *> IO.blocking {
        if result.ok then produce.foreach(f => f(outPng, width, height))
        result
      }

  /** Records every outbound call. Defaults are all-success; the flags let a test
    *  drive the failure branch of one leg without touching the others. */
  private final class Recorder(imageOk: Boolean = true, fileOk: Boolean = true, sendOk: Boolean = true):
    val images: Ref[IO, List[os.Path]] = Ref.unsafe[IO, List[os.Path]](Nil)
    val files: Ref[IO, List[(os.Path, String)]] = Ref.unsafe[IO, List[(os.Path, String)]](Nil)
    val msgs: Ref[IO, List[(String, String)]] = Ref.unsafe[IO, List[(String, String)]](Nil)
    val texts: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)

    def uploadImage: RichFeishuAdapter.UploadImage = p =>
      images.update(_ :+ p).map(_ =>
        if imageOk then RichFeishuAdapter.UploadResult(true, "img_key_1", 0, "ok")
        else RichFeishuAdapter.UploadResult(false, "", 230020, "upload refused"))

    def uploadFile: RichFeishuAdapter.UploadFile = (p, n) =>
      files.update(_ :+ ((p, n))).map(_ =>
        if fileOk then RichFeishuAdapter.UploadResult(true, "file_key_1", 0, "ok")
        else RichFeishuAdapter.UploadResult(false, "", 230021, "upload refused"))

    def sendMsg: RichFeishuAdapter.SendMsg = (t, c) =>
      msgs.update(_ :+ ((t, c))).map(_ =>
        if sendOk then RichFeishuAdapter.SendResult(true, Some("om_msg_1"), 0, "ok")
        else RichFeishuAdapter.SendResult(false, None, 230030, "send refused"))

    def sendText: RichFeishuAdapter.SendText = t =>
      texts.update(_ :+ t).map(_ =>
        if sendOk then RichFeishuAdapter.SendResult(true, Some("om_text_1"), 0, "ok")
        else RichFeishuAdapter.SendResult(false, None, 230031, "text send refused"))

  private def adapter(
      rec: Recorder,
      renderer: RichRenderer.Renderer = RichRenderer.Unavailable
  ): RichFeishuAdapter =
    // The render-output face is injected as a temp directory: the production
    // default reads the process-global data root, which other suites mutate, so a
    // spec that used it would be coupled to their state.
    new RichFeishuAdapter(rec.uploadImage, rec.uploadFile, rec.sendMsg, rec.sendText, renderer,
      () => tmpDir())

  private val CardHtml = """<!doctype html><html><body><h1>hello card</h1></body></html>"""
  private def cardPayload(html: String = CardHtml): String =
    RichKind.CardSentinel + io.circe.Json.obj("html" -> io.circe.Json.fromString(html)).noSpaces

  // ─────────────────── R-1 classification (pure, no IO) ───────────────────

  test("R-1 classification: card marker → Card; image ext → Image; doc ext → Document; rest → Unknown") {
    val img = tmpDir() / "shot.png"
    Files.write(img.toNIO, Array[Byte](1, 2, 3))
    val doc = tmpDir() / "report.pdf"
    Files.write(doc.toNIO, Array[Byte](1, 2, 3))

    assertEquals(RichKind.of(cardPayload(), None, true, DocumentExts), RichKind.Card)
    assertEquals(RichKind.of("", Some(img.toString), true, DocumentExts), RichKind.Image)
    assertEquals(RichKind.of("", Some(doc.toString), true, DocumentExts), RichKind.Document)
    assert(RichKind.of("", Some((tmpDir() / "a.xyz").toString), false, DocumentExts).isInstanceOf[RichKind.Unknown],
      "an unknown extension must classify as Unknown, never be guessed into a rich kind")
  }

  test("R-1b classification is fail-closed: a missing path is Unknown with a non-empty reason") {
    RichKind.of("", Some("/nonexistent/nope.png"), exists = false, DocumentExts) match
      case RichKind.Unknown(r) => assert(r.trim.nonEmpty, "an Unknown must always explain itself")
      case other               => fail(s"expected Unknown for a non-existent path, got $other")
  }

  test("R-1c image detection reuses the existing authority table (ImageInject), not a second list") {
    // Every extension the existing tool layer treats as an image must classify as
    // Image here too — a second table would show up as a failure on one of these
    // rows the moment the two drifted.
    assert(nebflow.core.tools.ImageInject.imageExtensions.nonEmpty, "the authority table must be non-empty")
    nebflow.core.tools.ImageInject.imageExtensions.keys.foreach { ext =>
      val p = tmpDir() / s"x.$ext"
      Files.write(p.toNIO, Array[Byte](1))
      assertEquals(RichKind.of("", Some(p.toString), true, DocumentExts), RichKind.Image, s"extension .$ext")
    }
  }

  // ─────────────────── R-2 no silent drop, ever ───────────────────

  test("R-2 Unknown produces exactly one text call and is NEVER silently dropped") {
    val rec = Recorder()
    adapter(rec).deliver(RichKind.Unknown("extension is in neither authority table: xyz"), None).flatMap { results =>
      for
        texts <- rec.texts.get
        imgs  <- rec.images.get
        files <- rec.files.get
        msgs  <- rec.msgs.get
      yield
        assertEquals(results.size, 1, "one intent must yield exactly one reading (count conservation)")
        assertEquals(imgs.size, 0, "an unknown kind must never upload an image")
        assertEquals(files.size, 0, "an unknown kind must never upload a file")
        assertEquals(msgs.size, 0, "an unknown kind must never send an image/file message")
        assertEquals(texts.size, 1, "an unknown kind must still say something")
        assert(texts.head.contains("[未知类型]"), s"the placeholder must be machine-matchable, got: ${texts.head}")
    }
  }

  test("R-2b a rich kind with no path still degrades to one text (fail-closed one step earlier)") {
    val rec = Recorder()
    adapter(rec).deliver(RichKind.Image, None).flatMap { results =>
      for texts <- rec.texts.get
      yield
        assertEquals(results.size, 1)
        assertEquals(texts.size, 1)
        assert(texts.head.contains("[未知类型]"), texts.head)
    }
  }

  test("R-2c count conservation holds on the total path: every intent yields exactly one reading") {
    val rec = Recorder()
    val p = writeNoisePng(tmpDir() / "a.png", 8, 8)
    adapter(rec).deliver(RichKind.Image, Some(p)).flatMap { results =>
      IO {
        assertEquals(results.size, 1)
        results.foreach(r => if !r.ok then assert(r.detail.trim.nonEmpty, s"failure ${r.call} has an empty detail"))
      }
    }
  }

  // ─────────────────── R-3 image leg: real bytes, no render ───────────────────

  test("R-3 an image product is sent with its REAL bytes and the renderer is NOT invoked") {
    val renderer = new RecordingRenderer(RichRenderer.RenderResult.engineUnavailable("must not be called"))
    val rec = Recorder()
    val p = writeNoisePng(tmpDir() / "real.png", 16, 16)
    val before = Files.size(p.toNIO)

    adapter(rec, renderer).deliver(RichKind.Image, Some(p)).flatMap { results =>
      for
        calls <- renderer.calls.get
        imgs  <- rec.images.get
        msgs  <- rec.msgs.get
      yield
        assertEquals(calls, 0, "route D: an already-raster product must never be re-rendered")
        assertEquals(imgs, List(p), "the uploaded artefact must be the original file, byte-for-byte unchanged")
        assertEquals(Files.size(p.toNIO), before, "the product must not be rewritten")
        assertEquals(msgs.size, 1, "exactly one image message")
        assertEquals(msgs.head._1, "image", "the image leg must send msg_type=image")
        assert(msgs.head._2.contains("img_key_1"), s"content must carry the returned image_key: ${msgs.head._2}")
        assert(results.head.ok, s"expected a successful send, got ${results.head.detail}")
    }
  }

  // ─────────────────── R-4 card leg: render + MANDATORY note ───────────────────

  test("R-4 a rendered card uploads the render product once, sends msg_type=image, then the verbatim note") {
    val produced = tmpDir() / "card.png"
    val renderer = new RecordingRenderer(
      RichRenderer.RenderResult(true, Some(produced), 4096L, 600, 320, "", "PNG 600x320"),
      Some((p, w, h) => { writeNoisePng(p, w, h); () })
    )
    val rec = Recorder()

    adapter(rec, renderer).deliver(RichKind.Card, None, cardPayload()).flatMap { results =>
      for
        calls <- renderer.calls.get
        imgs  <- rec.images.get
        msgs  <- rec.msgs.get
        texts <- rec.texts.get
      yield
        assertEquals(calls, 1, "a card must be rendered exactly once")
        assertEquals(imgs.size, 1, "the render product must be uploaded exactly once")
        assertEquals(imgs.head, produced, "the uploaded artefact must be the render product")
        assertEquals(msgs.map(_._1), List("image"), "the card is carried as an image message")
        assertEquals(texts, List(RichPlanner.InteractivePanelNote),
          "the interactive-panel note must be sent, verbatim and exactly once")
        assertEquals(texts.head, "交互版见本地面板", "the note is a fixed, author-approved string")
        assertEquals(results.size, 2, "card = image send + note send, two readings")
    }
  }

  test("R-4b the renderer receives the card's HTML, not the raw JSON payload") {
    // Pins `htmlOf`: handing the JSON envelope to a browser would screenshot a
    // wall of JSON and still produce a passable PNG, so this cannot be caught by
    // the PNG judgement — it has to be asserted on the renderer input.
    val produced = tmpDir() / "card.png"
    val renderer = new RecordingRenderer(
      RichRenderer.RenderResult(true, Some(produced), 4096L, 600, 320, "", "PNG 600x320"),
      Some((p, w, h) => { writeNoisePng(p, w, h); () })
    )
    adapter(Recorder(), renderer).deliver(RichKind.Card, None, cardPayload()).flatMap { _ =>
      renderer.lastHtml.get.map { html =>
        assertEquals(html, CardHtml, "the renderer must get the html field's value")
        assert(!html.contains("___CARD_HTML___"), "the sentinel must not reach the browser")
        assert(!html.contains("\"html\":"), "the JSON envelope must not reach the browser")
      }
    }
  }

  // ─────────────────── R-5 render failure: placeholder + note ───────────────────

  test("R-5 a failed render sends [未渲染] PLUS the note, and uploads nothing") {
    val renderer = new RecordingRenderer(RichRenderer.RenderResult.engineUnavailable("no Chrome binary detected on this host"))
    val rec = Recorder()

    adapter(rec, renderer).deliver(RichKind.Card, None, cardPayload()).flatMap { _ =>
      for
        imgs  <- rec.images.get
        files <- rec.files.get
        msgs  <- rec.msgs.get
        texts <- rec.texts.get
      yield
        assertEquals(imgs.size, 0, "a failed render must not upload anything")
        assertEquals(files.size, 0, "a failed render must not upload anything")
        assertEquals(msgs.size, 0, "a failed render must not send an image message")
        assertEquals(texts.size, 1, "the failure must be reported as text — never silently dropped (§7.3 #3)")
        assert(texts.head.contains("[未渲染]"), s"§7.1 placeholder missing: ${texts.head}")
        assert(texts.head.contains("交互版见本地面板"), s"the note is mandatory even on the degrade path: ${texts.head}")
    }
  }

  test("R-5b the render timeout branch is a reading, not an exception") {
    val renderer = new RecordingRenderer(
      RichRenderer.RenderResult.timedOut("no stable render product within 30000ms"))
    val rec = Recorder()
    adapter(rec, renderer).deliver(RichKind.Card, None, cardPayload()).flatMap { results =>
      rec.texts.get.map { texts =>
        assertEquals(results.size, 1)
        assertEquals(texts.size, 1)
        assert(texts.head.contains("[未渲染]"), texts.head)
      }
    }
  }

  test("R-5c a rejected upload still reports the channel's code AND texts the recipient (no silence)") {
    val produced = tmpDir() / "card.png"
    val renderer = new RecordingRenderer(
      RichRenderer.RenderResult(true, Some(produced), 4096L, 600, 320, "", "PNG 600x320"),
      Some((p, w, h) => { writeNoisePng(p, w, h); () })
    )
    val rec = Recorder(imageOk = false)

    adapter(rec, renderer).deliver(RichKind.Card, None, cardPayload()).flatMap { results =>
      rec.texts.get.map { texts =>
        assertEquals(results.size, 1, "an upload refusal yields exactly one reading")
        assertEquals(texts.size, 1, "the refusal must also be reported to the recipient")
        assert(texts.head.contains("渠道拒绝(code=230020)"), s"the channel code must be echoed: ${texts.head}")
        assert(texts.head.contains("交互版见本地面板"), "the note survives the refusal path too")
      }
    }
  }

  // ─────────────────── R-6 size gate: reuse + refuse before upload ───────────────────

  test("R-6 the size gate reuses the EXISTING constant and refuses BEFORE any upload") {
    // The gate's authority, asserted directly: no second constant was created for
    // this leg (design card §6.1).
    assertEquals(AttachContract.MaxFileBytes, 1_073_741_824L)
    assertEquals(AttachContract.checkFileSize(AttachContract.MaxFileBytes).isRight, true,
      "exactly at the limit must pass — the gate is `>` not `>=`")

    val rec = Recorder()
    val big = sparseFile(tmpDir() / "huge.pdf", AttachContract.MaxFileBytes + 1)
    adapter(rec).deliver(RichKind.Document, Some(big)).flatMap { results =>
      for
        imgs  <- rec.images.get
        files <- rec.files.get
        msgs  <- rec.msgs.get
        texts <- rec.texts.get
      yield
        assertEquals(imgs.size + files.size + msgs.size, 0,
          "an oversize product must produce ZERO uploads and ZERO sends (the gate runs before the upload leg)")
        assertEquals(texts.size, 1, "the refusal must be reported")
        assert(texts.head.contains("尺寸闸拒绝"), s"§7.1 reason missing: ${texts.head}")
        assert(texts.head.contains(AttachContract.Codes.AttachTooLarge),
          s"the existing error code must be echoed: ${texts.head}")
        assert(texts.head.contains((AttachContract.MaxFileBytes + 1).toString),
          s"§6.2 requires echoing the ACTUAL size: ${texts.head}")
        assertEquals(results.size, 1, "count conservation holds on the refusal path too")
    }
  }

  test("R-6b the gate's own error carries code + actual + limit (the machine-readable half)") {
    AttachContract.checkFileSize(AttachContract.MaxFileBytes + 1) match
      case Left(err) =>
        assertEquals(err.code, AttachContract.Codes.AttachTooLarge)
        assertEquals(err.actual, Some(AttachContract.MaxFileBytes + 1))
        assertEquals(err.limit, Some(AttachContract.MaxFileBytes))
      case Right(_) => fail("the gate must reject a file above the limit")
  }

  // ─────────────────── R-7 document leg: file upload + msg_type ───────────────────

  test("R-7 a document product uploads the ORIGINAL file once and sends msg_type=file") {
    val rec = Recorder()
    val p = tmpDir() / "report.pdf"
    Files.write(p.toNIO, Array[Byte](1, 2, 3, 4, 5))
    adapter(rec).deliver(RichKind.Document, Some(p)).flatMap { results =>
      for
        files <- rec.files.get
        imgs  <- rec.images.get
        msgs  <- rec.msgs.get
        texts <- rec.texts.get
      yield
        assertEquals(imgs.size, 0, "a document must not go down the image leg")
        assertEquals(texts.size, 0, "a successful document send needs no text fallback")
        assertEquals(files.map(_._1), List(p), "the uploaded artefact must be the original file")
        assertEquals(files.map(_._2), List("report.pdf"), "the file name must be the original name")
        assertEquals(msgs.map(_._1), List("file"), "the document leg must send msg_type=file")
        assert(msgs.head._2.contains("file_key_1"), s"content must carry the returned file_key: ${msgs.head._2}")
        assertEquals(results.size, 1)
        assert(results.head.ok, results.head.detail)
    }
  }

  test("R-7b fileType maps through the SDK's own enum (PDF named; unknown → STREAM)") {
    assertEquals(RichFeishuAdapter.fileTypeOf("report.pdf"), "pdf")
    assertEquals(RichFeishuAdapter.fileTypeOf("book.docx"), "doc")
    assertEquals(RichFeishuAdapter.fileTypeOf("sheet.xlsx"), "xls")
    assertEquals(RichFeishuAdapter.fileTypeOf("deck.pptx"), "ppt")
    assertEquals(RichFeishuAdapter.fileTypeOf("archive.bin"), "stream",
      "an unmapped extension must fall back to STREAM, not be refused")
  }

  // ─────────────────── R-8 the HC-3 judgement itself ───────────────────

  test("R-8 HC-3 accepts a real noisy PNG of the declared size") {
    val p = writeNoisePng(tmpDir() / "good.png", 32, 24)
    RichRenderer.inspect(p, 32, 24) match
      case Right(r) =>
        assert(r.ok)
        assertEquals((r.width, r.height), (32, 24))
        assert(r.bytes > 0L)
      case Left(err) => fail(s"a real PNG must pass: ${err.detail}")
  }

  test("R-8b HC-3 rejects a solid-colour PNG (the empty-render shape)") {
    val p = writeSolidPng(tmpDir() / "blank.png", 32, 24, 0xffffff)
    // The reason this row exists at all: a uniform PNG still contains MANY
    // distinct file bytes (row filters and entropy coding produce them), so a
    // file-level byte count accepts it. Measured here: bytes are large, decoded
    // colours are exactly 1. HC-3 must judge the pixels.
    val distinctBytes = Files.readAllBytes(p.toNIO).distinct.length
    assert(distinctBytes > RichRenderer.MinDistinctPixels,
      s"fixture sanity: a solid PNG is expected to have many distinct BYTES ($distinctBytes), " +
        "which is exactly why byte statistics cannot be the criterion")
    RichRenderer.inspect(p, 32, 24) match
      case Right(_)  => fail("a solid-colour image must NOT be accepted as a successful render")
      case Left(err) => assert(err.detail.contains("empty/solid"), err.detail)
  }

  test("R-8c HC-3 rejects a non-PNG carrying a .png name (the renamed-SVG trick)") {
    val p = tmpDir() / "fake.png"
    Files.write(p.toNIO, """<svg xmlns="http://www.w3.org/2000/svg"/>""".getBytes("UTF-8"))
    RichRenderer.inspect(p, 10, 10) match
      case Right(_)  => fail("a renamed SVG must never be accepted as a render product")
      case Left(err) => assert(err.detail.contains("not a PNG"), err.detail)
  }

  test("R-8d HC-3 rejects a geometry mismatch") {
    val p = writeNoisePng(tmpDir() / "wrong.png", 20, 20)
    RichRenderer.inspect(p, 600, 320) match
      case Right(_)  => fail("a render whose size differs from the declared geometry must be rejected")
      case Left(err) => assert(err.detail.contains("does not match"), err.detail)
  }

  test("R-8e HC-3 rejects a missing product") {
    RichRenderer.inspect(tmpDir() / "absent.png", 10, 10) match
      case Right(_)  => fail("a missing product must be a failure")
      case Left(err) => assert(err.detail.contains("no render product"), err.detail)
  }

  // ─────────────────── R-9 zero-secret discipline ───────────────────

  test("R-9 no credential value appears in any produced text or reading") {
    val rec = Recorder()
    val img = writeNoisePng(tmpDir() / "a.png", 8, 8)
    val doc = tmpDir() / "b.pdf"
    Files.write(doc.toNIO, Array[Byte](9))
    val failing = new RecordingRenderer(RichRenderer.RenderResult.engineUnavailable("no Chrome binary detected on this host"))

    val legs = for
      _ <- adapter(rec).deliver(RichKind.Image, Some(img))
      _ <- adapter(rec).deliver(RichKind.Document, Some(doc))
      _ <- adapter(rec, failing).deliver(RichKind.Card, None, cardPayload())
      _ <- adapter(rec).deliver(RichKind.Unknown("nope"), None)
    yield ()

    legs.flatMap { _ =>
      for
        texts <- rec.texts.get
        imgs  <- rec.images.get
        files <- rec.files.get
        msgs  <- rec.msgs.get
      yield
        val all = (texts ++ imgs.map(_.toString) ++ files.map(_._1.toString) ++ msgs.map(_._2)).mkString("\n")
        assert(!all.contains("appSecret"), s"a credential marker leaked into the produced output: $all")
        assert(!all.contains("test-app"), "an app id must not leak into user-visible text either")
    }
  }

  test("R-9b the ADAPTER holds no credential field at all (structural, not just observed)") {
    // The live credential values are closed over by the live seams; the adapter
    // instance itself never carries them. That is what makes the zero-secret
    // discipline structural rather than a matter of remembering not to log.
    val fields = classOf[RichFeishuAdapter].getDeclaredFields.map(_.getName).toList
    assert(!fields.exists(_.toLowerCase.contains("secret")), s"adapter must not store a secret: $fields")
    assert(!fields.exists(_.toLowerCase.contains("appid")), s"adapter must not store an app id: $fields")
    assert(fields.exists(_.contains("sendText")), s"the seams must be fields: $fields")
  }

  // ─────────────────── R-10 the planner is pure and order-correct ───────────────────

  test("R-10 the card plan is image-then-note, in that order") {
    val p = tmpDir() / "card.png"
    RichPlanner.planCard(p) match
      case List(RichCall.UploadImage(x, true), RichCall.SendText(note)) =>
        assertEquals(x, p)
        assertEquals(note, "交互版见本地面板")
      case other => fail(s"unexpected plan: $other")
  }

  test("R-10b the degrade vocabulary is closed and the placeholders are exactly §7.1/§7.2") {
    val p = tmpDir() / "x.pdf"
    assert(RichDegrade.renderFailed(RichDegrade.Reason.RenderTimeout, p, 12L).startsWith("[未渲染] 渲染超时"))
    assert(RichDegrade.unknown("why", Some(p)).startsWith("[未知类型] why"))
    assertEquals(RichDegrade.unknown("", Some(p)).startsWith("[未知类型] unspecified"), true,
      "an empty reason must be normalized, never passed through")
    assert(RichDegrade.renderFailed("r", p, 1L).contains("原件："))
  }

  test("R-10c RichLedger never lets an empty detail through on a failure") {
    val r = RichLedger.of(RichCall.SendText("t"), false, 7, None, "")
    assert(r.detail.trim.nonEmpty, "a failed reading must always carry a detail (§7.3 #2)")
    assertEquals(r.code, 7)
  }

  // ─────────────────── R-11 no silent-drop idiom in the package ───────────────────

  test("R-11 the outbound package contains none of the swallow idioms (design card §7.3 #4)") {
    val rel = os.RelPath("src/main/scala/nebflow/social/outbound")
    val candidates = List(os.pwd / rel, os.Path(System.getProperty("user.dir"), os.pwd) / rel)
    val dir = candidates.find(os.exists).getOrElse(fail(
      s"could not locate the outbound package; tried:\n${candidates.mkString("\n")}"))
    val sources = os.list(dir).filter(p => p.last.endsWith(".scala"))
    assert(sources.nonEmpty, s"expected source files under $dir")
    sources.foreach { f =>
      // Scan CODE, not prose. These idioms are named in the files' own doc
      // comments (that is how the contract is documented), so a raw grep would
      // flag the documentation instead of the code. Block comments are removed
      // and trailing `//` comments are stripped per line before matching.
      val code = stripComments(os.read(f))
      assert(!code.contains("case _ => IO.unit"), s"${f.last}: silent-drop idiom `case _ => IO.unit`")
      assert(!code.contains("case _ => ()"), s"${f.last}: silent-drop idiom `case _ => ()`")
    }
  }

  /** Remove Scala block comments and per-line `//` trailing comments. Good enough
    *  for an idiom scan: string literals containing `//` would only make the scan
    *  *weaker* on that line, never produce a false positive. */
  private def stripComments(src: String): String =
    val noBlocks = src.replaceAll("(?s)/\\*.*?\\*/", "")
    noBlocks.linesIterator.map { line =>
      val i = line.indexOf("//")
      if i >= 0 then line.substring(0, i) else line
    }.mkString("\n")
