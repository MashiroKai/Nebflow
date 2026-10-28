package nebflow.social.outbound

import cats.effect.IO
import cats.effect.Ref
import io.circe.Json
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

  /**
   * A REAL PNG of the requested size, written through the JDK's own encoder —
   *  noise, not a solid colour, so it clears the distinct-pixel floor the way a
   *  browser screenshot does. Hand-writing bytes would risk a file that is not
   *  actually decodable, which would make the judgement vacuous.
   */
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

  /**
   * Structurally valid but a single flat colour — the shape an empty/blank
   *  render produces, and the thing HC-3 must reject.
   */
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

  /**
   * A sparse file of the requested length — exercises the REAL gate without
   *  writing a gigabyte.
   */
  private def sparseFile(path: os.Path, bytes: Long): os.Path =
    Files.createDirectories(path.toNIO.getParent)
    val ch = Files.newByteChannel(path.toNIO, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    try
      ch.position(bytes - 1)
      ch.write(java.nio.ByteBuffer.wrap(Array[Byte](0)))
    finally ch.close()
    path

  /**
   * Recording renderer: never starts a process, counts its invocations (so a
   *  test can prove the image/document legs do NOT render), and captures the HTML
   *  it was handed (so `htmlOf`'s extraction is pinned).
   */
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

  /**
   * Records every outbound call. Defaults are all-success; the flags let a test
   *  drive the failure branch of one leg without touching the others.
   */
  private final class Recorder(imageOk: Boolean = true, fileOk: Boolean = true, sendOk: Boolean = true):
    val images: Ref[IO, List[os.Path]] = Ref.unsafe[IO, List[os.Path]](Nil)
    val files: Ref[IO, List[(os.Path, String)]] = Ref.unsafe[IO, List[(os.Path, String)]](Nil)
    val msgs: Ref[IO, List[(String, String)]] = Ref.unsafe[IO, List[(String, String)]](Nil)
    val texts: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)

    def uploadImage: RichFeishuAdapter.UploadImage = p =>
      images
        .update(_ :+ p)
        .map(_ =>
          if imageOk then RichFeishuAdapter.UploadResult(true, "img_key_1", 0, "ok")
          else RichFeishuAdapter.UploadResult(false, "", 230020, "upload refused")
        )

    def uploadFile: RichFeishuAdapter.UploadFile = (p, n) =>
      files
        .update(_ :+ ((p, n)))
        .map(_ =>
          if fileOk then RichFeishuAdapter.UploadResult(true, "file_key_1", 0, "ok")
          else RichFeishuAdapter.UploadResult(false, "", 230021, "upload refused")
        )

    def sendMsg: RichFeishuAdapter.SendMsg = (t, c) =>
      msgs
        .update(_ :+ ((t, c)))
        .map(_ =>
          if sendOk then RichFeishuAdapter.SendResult(true, Some("om_msg_1"), 0, "ok")
          else RichFeishuAdapter.SendResult(false, None, 230030, "send refused")
        )

    def sendText: RichFeishuAdapter.SendText = t =>
      texts
        .update(_ :+ t)
        .map(_ =>
          if sendOk then RichFeishuAdapter.SendResult(true, Some("om_text_1"), 0, "ok")
          else RichFeishuAdapter.SendResult(false, None, 230031, "text send refused")
        )

  end Recorder

  private def adapter(
    rec: Recorder,
    renderer: RichRenderer.Renderer = RichRenderer.Unavailable,
    longshot: FileLongShot.Renderer = FileLongShot.Unavailable
  ): RichFeishuAdapter =
    // The render-output face is injected as a temp directory: the production
    // default reads the process-global data root, which other suites mutate, so a
    // spec that used it would be coupled to their state. The long-shot seam
    // defaults to Unavailable so no test ever starts node (the offline discipline).
    new RichFeishuAdapter(
      rec.uploadImage,
      rec.uploadFile,
      rec.sendMsg,
      rec.sendText,
      renderer,
      () => tmpDir(),
      longshot
    )

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
    assert(
      RichKind.of("", Some((tmpDir() / "a.xyz").toString), false, DocumentExts).isInstanceOf[RichKind.Unknown],
      "an unknown extension must classify as Unknown, never be guessed into a rich kind"
    )
  }

  test("R-1b classification is fail-closed: a missing path is Unknown with a non-empty reason") {
    RichKind.of("", Some("/nonexistent/nope.png"), exists = false, DocumentExts) match
      case RichKind.Unknown(r) => assert(r.trim.nonEmpty, "an Unknown must always explain itself")
      case other => fail(s"expected Unknown for a non-existent path, got $other")
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
    // social-webshot batch: the pinned line is now the FRIENDLY one-liner — the
    // mechanism wording (`extension is in neither authority table: …`) went to
    // the adapter's WARN log, never to the recipient. The product is a real
    // on-disk file of an off-table extension: the exact shape of the 2026-10-09
    // incident (`diagram.html` 透出英文机制报错).
    val rec = Recorder()
    val odd = tmpDir() / "diagram.xyz"
    Files.write(odd.toNIO, Array[Byte](1, 2, 3))
    adapter(rec)
      .deliver(RichKind.Unknown("extension is in neither authority table: xyz"), Some(odd))
      .flatMap { results =>
        for
          texts <- rec.texts.get
          imgs <- rec.images.get
          files <- rec.files.get
          msgs <- rec.msgs.get
        yield
          assertEquals(results.size, 1, "one intent must yield exactly one reading (count conservation)")
          assertEquals(imgs.size, 0, "an unknown kind must never upload an image")
          assertEquals(files.size, 0, "an unknown kind must never upload a file")
          assertEquals(msgs.size, 0, "an unknown kind must never send an image/file message")
          assertEquals(texts.size, 1, "an unknown kind must still say something")
          val line = texts.head
          assert(line.contains(RichDegrade.UnsupportedPrefix), s"the placeholder must be machine-matchable, got: $line")
          assert(line.contains("原件在桌面端"), s"the line must point at the desktop original: $line")
          assert(line.contains(odd.toString), s"the line must carry the absolute original path: $line")
          assert(line.contains("diagram.xyz"), s"the line must name the file: $line")
          assert(
            !line.contains("authority") && !line.contains("neither") && !line.contains("extension"),
            s"mechanism vocabulary must not leak to the recipient: $line"
          )
      }
  }

  test("R-2b a rich kind with no path still degrades to one text (fail-closed one step earlier)") {
    val rec = Recorder()
    adapter(rec).deliver(RichKind.Image, None).flatMap { results =>
      for texts <- rec.texts.get
      yield
        assertEquals(results.size, 1)
        assertEquals(texts.size, 1)
        assert(texts.head.contains(RichDegrade.UnreachablePrefix), texts.head)
        assert(texts.head.contains("没有可发送的文件"), texts.head)
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
        imgs <- rec.images.get
        msgs <- rec.msgs.get
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
      Some((p, w, h) =>
        writeNoisePng(p, w, h); ()
      )
    )
    val rec = Recorder()

    adapter(rec, renderer).deliver(RichKind.Card, None, cardPayload()).flatMap { results =>
      for
        calls <- renderer.calls.get
        imgs <- rec.images.get
        msgs <- rec.msgs.get
        texts <- rec.texts.get
      yield
        assertEquals(calls, 1, "a card must be rendered exactly once")
        assertEquals(imgs.size, 1, "the render product must be uploaded exactly once")
        assertEquals(imgs.head, produced, "the uploaded artefact must be the render product")
        assertEquals(msgs.map(_._1), List("image"), "the card is carried as an image message")
        assertEquals(
          texts,
          List(RichPlanner.InteractivePanelNote),
          "the interactive-panel note must be sent, verbatim and exactly once"
        )
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
      Some((p, w, h) =>
        writeNoisePng(p, w, h); ()
      )
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
    val renderer =
      new RecordingRenderer(RichRenderer.RenderResult.engineUnavailable("no Chrome binary detected on this host"))
    val rec = Recorder()

    adapter(rec, renderer).deliver(RichKind.Card, None, cardPayload()).flatMap { _ =>
      for
        imgs <- rec.images.get
        files <- rec.files.get
        msgs <- rec.msgs.get
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
    val renderer = new RecordingRenderer(RichRenderer.RenderResult.timedOut("no stable render product within 30000ms"))
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
      Some((p, w, h) =>
        writeNoisePng(p, w, h); ()
      )
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
    assertEquals(
      AttachContract.checkFileSize(AttachContract.MaxFileBytes).isRight,
      true,
      "exactly at the limit must pass — the gate is `>` not `>=`"
    )

    val rec = Recorder()
    val big = sparseFile(tmpDir() / "huge.pdf", AttachContract.MaxFileBytes + 1)
    adapter(rec).deliver(RichKind.Document, Some(big)).flatMap { results =>
      for
        imgs <- rec.images.get
        files <- rec.files.get
        msgs <- rec.msgs.get
        texts <- rec.texts.get
      yield
        assertEquals(
          imgs.size + files.size + msgs.size,
          0,
          "an oversize product must produce ZERO uploads and ZERO sends (the gate runs before the upload leg)"
        )
        assertEquals(texts.size, 1, "the refusal must be reported")
        assert(texts.head.contains("尺寸闸拒绝"), s"§7.1 reason missing: ${texts.head}")
        assert(
          texts.head.contains(AttachContract.Codes.AttachTooLarge),
          s"the existing error code must be echoed: ${texts.head}"
        )
        assert(
          texts.head.contains((AttachContract.MaxFileBytes + 1).toString),
          s"§6.2 requires echoing the ACTUAL size: ${texts.head}"
        )
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
        imgs <- rec.images.get
        msgs <- rec.msgs.get
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
    assertEquals(
      RichFeishuAdapter.fileTypeOf("archive.bin"),
      "stream",
      "an unmapped extension must fall back to STREAM, not be refused"
    )
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
    assert(
      distinctBytes > RichRenderer.MinDistinctPixels,
      s"fixture sanity: a solid PNG is expected to have many distinct BYTES ($distinctBytes), " +
        "which is exactly why byte statistics cannot be the criterion"
    )
    RichRenderer.inspect(p, 32, 24) match
      case Right(_) => fail("a solid-colour image must NOT be accepted as a successful render")
      case Left(err) => assert(err.detail.contains("empty/solid"), err.detail)
  }

  test("R-8c HC-3 rejects a non-PNG carrying a .png name (the renamed-SVG trick)") {
    val p = tmpDir() / "fake.png"
    Files.write(p.toNIO, """<svg xmlns="http://www.w3.org/2000/svg"/>""".getBytes("UTF-8"))
    RichRenderer.inspect(p, 10, 10) match
      case Right(_) => fail("a renamed SVG must never be accepted as a render product")
      case Left(err) => assert(err.detail.contains("not a PNG"), err.detail)
  }

  test("R-8d HC-3 rejects a geometry mismatch") {
    val p = writeNoisePng(tmpDir() / "wrong.png", 20, 20)
    RichRenderer.inspect(p, 600, 320) match
      case Right(_) => fail("a render whose size differs from the declared geometry must be rejected")
      case Left(err) => assert(err.detail.contains("does not match"), err.detail)
  }

  test("R-8e HC-3 rejects a missing product") {
    RichRenderer.inspect(tmpDir() / "absent.png", 10, 10) match
      case Right(_) => fail("a missing product must be a failure")
      case Left(err) => assert(err.detail.contains("no render product"), err.detail)
  }

  // ─────────────────── R-9 zero-secret discipline ───────────────────

  test("R-9 no credential value appears in any produced text or reading") {
    val rec = Recorder()
    val img = writeNoisePng(tmpDir() / "a.png", 8, 8)
    val doc = tmpDir() / "b.pdf"
    Files.write(doc.toNIO, Array[Byte](9))
    val failing =
      new RecordingRenderer(RichRenderer.RenderResult.engineUnavailable("no Chrome binary detected on this host"))

    val legs = for
      _ <- adapter(rec).deliver(RichKind.Image, Some(img))
      _ <- adapter(rec).deliver(RichKind.Document, Some(doc))
      _ <- adapter(rec, failing).deliver(RichKind.Card, None, cardPayload())
      _ <- adapter(rec).deliver(RichKind.Unknown("nope"), None)
    yield ()

    legs.flatMap { _ =>
      for
        texts <- rec.texts.get
        imgs <- rec.images.get
        files <- rec.files.get
        msgs <- rec.msgs.get
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

  test("R-10b the degrade vocabulary is closed and the placeholders are the friendly Chinese face") {
    val p = tmpDir() / "x.pdf"
    assert(RichDegrade.renderFailed(RichDegrade.Reason.RenderTimeout, p, 12L).startsWith("[未渲染] 渲染超时"))
    // b-layer, on-disk off-table type → [暂不支持] + 原件在桌面端 + path; the
    // mechanism wording of the classification reason never renders (social-webshot).
    val line = RichDegrade.unknown(Some(tmpDir() / "diagram.html"))
    assert(line.startsWith(s"${RichDegrade.UnsupportedPrefix} 这个格式在聊天里看不了（diagram.html）"), line)
    assert(line.contains("原件在桌面端"), line)
    assert(!line.contains("authority") && !line.contains("neither"), s"mechanism words must not render: $line")
    // missing-face: the original is gone (or the event carried no path) → [未送达].
    assert(
      RichDegrade
        .unknown(Some(p), missing = true)
        .startsWith(s"${RichDegrade.UnreachablePrefix} 原件没找到")
    )
    assertEquals(RichDegrade.unknown(None, missing = true), "[未送达] 这条消息没有可发送的文件")
    // a-layer note: the original path rides the text leg, fixed builder output.
    assertEquals(RichDegrade.originalNote(p), s"原件（桌面端可打开）：$p")
    assert(RichDegrade.renderFailed("r", p, 1L).contains("原件："))
    // 文件类文本卡（pop-social-files batch）：文件名 + 一句话摘要 + 本地路径，
    // 机器可匹配前缀 [未转图]；summary 为 None 时省略摘要行。
    val card = RichDegrade.unconverted(p, Some("季度审计的结论行"))
    assert(card.startsWith(s"${RichDegrade.UnconvertedPrefix} x.pdf"), card)
    assert(card.contains("内容摘要：季度审计的结论行") && card.contains(s"原件在桌面端：$p"), card)
    val bare = RichDegrade.unconverted(p, None)
    assert(bare.startsWith(s"${RichDegrade.UnconvertedPrefix} x.pdf") && bare.contains(s"原件在桌面端：$p"), bare)
    assert(!bare.contains("内容摘要"), "no summary ⇒ no summary line")
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
    val dir = candidates
      .find(os.exists)
      .getOrElse(fail(s"could not locate the outbound package; tried:\n${candidates.mkString("\n")}"))
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

  // ─────────────────── R-12 the planner→renderer→adapter sequence (main link) ───────────────────
  //
  // richcontent main-link batch (soc483): the two manual probes
  // (`RichRenderProbe` / `RichDegradeProbe`) were the only place the full
  // classify → plan → render → deliver → degrade sequence was exercised end to
  // end. Their BEHAVIOUR is converted into the formal assertions below, so the
  // sequence is pinned in `sbt test`; the probes keep their manual `main` and
  // stay out of the discovery face (a spec that started a browser would stop
  // being offline).

  test("R-12a the three rich kinds each drive their own documented call sequence") {
    // One assertion per kind, over ONE recorder, so the sequence — not just the
    // call count — is what is read back.
    val rec = Recorder()
    val png = writeNoisePng(tmpDir() / "shot.png", 8, 8)
    val doc = tmpDir() / "report.pdf"
    Files.write(doc.toNIO, Array[Byte](1, 2, 3))
    val produced = tmpDir() / "card.png"
    val renderer = new RecordingRenderer(
      RichRenderer.RenderResult(true, Some(produced), 4096L, 600, 320, "", "PNG 600x320"),
      Some((p, w, h) =>
        writeNoisePng(p, w, h); ()
      )
    )
    val a = adapter(rec, renderer)

    for
      _ <- a.deliver(RichKind.Image, Some(png))
      _ <- a.deliver(RichKind.Document, Some(doc))
      _ <- a.deliver(RichKind.Card, None, cardPayload())
      imgs <- rec.images.get
      files <- rec.files.get
      msgs <- rec.msgs.get
      texts <- rec.texts.get
    yield
      // image: upload the real bytes → msg_type=image. No render, no text.
      // document: upload the original → msg_type=file. No render, no text.
      // card: render → upload the render product → msg_type=image → the note.
      assertEquals(imgs, List(png, produced), "image leg then the card's render product, in that order")
      assertEquals(files.map(_._1), List(doc), "the document leg uploads the original file")
      assertEquals(msgs.map(_._1), List("image", "file", "image"), "three sends: image, file, then the card screenshot")
      assertEquals(texts, List(RichPlanner.InteractivePanelNote), "only the card contributes a text call")
    end for
  }

  test("R-12b RichDegradeProbe's failure cases hold as assertions on the same code path") {
    // Mirror of the probe's A/B/C/D cases: (A) a card whose renderer cannot run,
    // (B) an oversize document, (C) an off-table on-disk product (the incident
    // shape — friendly line, no mechanism words), (D) a missing product. All
    // four must land on exactly one visible text with a machine-matchable
    // Chinese placeholder.
    val failing =
      new RecordingRenderer(RichRenderer.RenderResult.engineUnavailable("no Chrome binary detected on this host"))
    val recA = Recorder()
    val recB = Recorder()
    val recC = Recorder()
    val recD = Recorder()
    val big = sparseFile(tmpDir() / "huge.pdf", AttachContract.MaxFileBytes + 1)
    val offTable = tmpDir() / "diagram.xyz"
    Files.write(offTable.toNIO, Array[Byte](1, 2, 3))
    val gone = tmpDir() / "gone.png"

    for
      _ <- adapter(recA, failing).deliver(RichKind.Card, None, cardPayload())
      _ <- adapter(recB).deliver(RichKind.Document, Some(big))
      _ <- adapter(recC).deliver(RichKind.Unknown("extension is in neither authority table: xyz"), Some(offTable))
      _ <- adapter(recD).deliver(RichKind.Unknown("path does not exist or is not a regular file: gone"), Some(gone))
      textsA <- recA.texts.get
      textsB <- recB.texts.get
      textsC <- recC.texts.get
      textsD <- recD.texts.get
      imgsA <- recA.images.get
      filesB <- recB.files.get
    yield
      assertEquals(imgsA.size, 0, "A: a failed render uploads nothing")
      assert(
        textsA.size == 1 && textsA.head.contains("[未渲染]") && textsA.head.contains("交互版见本地面板"),
        s"A: expected the placeholder + the note, got $textsA"
      )
      assertEquals(filesB.size, 0, "B: an oversize product never reaches the upload leg")
      assert(
        textsB.size == 1 && textsB.head.contains("尺寸闸拒绝") && textsB.head.contains("ATTACH_TOO_LARGE"),
        s"B: expected the size-gate line with the existing code, got $textsB"
      )
      assert(
        textsC.size == 1 && textsC.head.contains(RichDegrade.UnsupportedPrefix)
          && textsC.head.contains("原件在桌面端") && textsC.head.contains("diagram.xyz"),
        s"C: expected the friendly unsupported line, got $textsC"
      )
      assert(
        textsD.size == 1 && textsD.head.contains(RichDegrade.UnreachablePrefix)
          && textsD.head.contains(gone.toString),
        s"D: expected the unreachable line naming the product, got $textsD"
      )
    end for
  }

  // ───────── pop batch-media fix: the parser carries ALL items, in order ─────────

  /** A Pop payload string with the given items (PopTool.itemJson field names). */
  private def popPayload(items: Json*): String =
    RichProduct.PopSentinel + Json.obj("items" -> Json.arr(items*)).noSpaces

  private def popItem(kind: String, name: String, p: String): Json =
    Json.obj("kind" -> Json.fromString(kind), "name" -> Json.fromString(name), "path" -> Json.fromString(p))

  private def parse(payload: String): Option[RichProduct] =
    RichProduct.of(payload, input = None, label = "Pop", documentExtensions = DocumentExts)

  test("R-P1 an 11-image Pop parses to 11 media items in payload order (the old read kept only the first)") {
    val paths = (1 to 11).map(i => s"/tmp/nb-popfix/p$i.png").toList
    val payload = popPayload(paths.map(p => popItem("image", p.split('/').last, p))*)
    val rich = parse(payload).getOrElse(fail("an 11-image payload must parse to a product"))
    assertEquals(rich.media, paths.map(os.Path(_)), "ALL items carried, order preserved")
    assertEquals(rich.path, Some(os.Path(paths.head)), "path is the derived head, nothing more")
    assertEquals(rich.label, "Pop")
  }

  test("R-P2 a MIXED image+file+video payload keeps payload order and skips only the video") {
    val payload = popPayload(
      popItem("image", "a.png", "/tmp/nb-popfix/a.png"),
      popItem("video", "v.mp4", "/tmp/nb-popfix/v.mp4"), // rides the shape, not a rich product — skipped
      popItem("file", "b.pdf", "/tmp/nb-popfix/b.pdf"),
      popItem("image", "c.png", "/tmp/nb-popfix/c.png")
    )
    val rich = parse(payload).getOrElse(fail("a mixed payload must parse to a product"))
    assertEquals(
      rich.media,
      List(os.Path("/tmp/nb-popfix/a.png"), os.Path("/tmp/nb-popfix/b.pdf"), os.Path("/tmp/nb-popfix/c.png")),
      "carriable items in payload order; the video skipped, never mis-delivered"
    )
  }

  test("R-P3 a video-only payload is NOT a rich product; an item without a path is skipped, not fatal") {
    assertEquals(
      parse(popPayload(popItem("video", "v.mp4", "/tmp/nb-popfix/v.mp4"))),
      None,
      "the old single-channel contract stands: video-only keeps the turn's text reply"
    )
    val partial = parse(
      popPayload(
        popItem("image", "gone", ""), // empty path — the tool's reject face, not a path
        popItem("image", "a.png", "/tmp/nb-popfix/a.png")
      )
    )
    assertEquals(
      partial.map(_.media),
      Some(List(os.Path("/tmp/nb-popfix/a.png"))),
      "the usable item survives a neighbour's empty path"
    )
    assertEquals(parse(RichProduct.PopSentinel + "not json at all"), None, "an unparseable payload is no product")
    assertEquals(parse(popPayload()), None, "an items-less payload is no product")
  }

  // ───────── pop-social-files batch: 文件类（md/html）长图腿（2026-10-20 作者令）─────────

  /**
   * Recording long-shot seam: never starts node; counts invocations, captures the
   * ORIGINAL input, and can produce a REAL noise PNG at the out path (so the
   * upload leg is exercised with decodable bytes).
   */
  private final class RecordingLongShot(
    result: Either[String, os.Path],
    produce: Option[os.Path => Unit] = None
  ) extends FileLongShot.Renderer:
    val calls: Ref[IO, Int] = Ref.unsafe[IO, Int](0)
    val lastInput: Ref[IO, String] = Ref.unsafe[IO, String]("")
    def available: Boolean = true

    def shot(file: os.Path, outPng: os.Path): IO[Either[String, os.Path]] =
      calls.update(_ + 1) *> lastInput.set(file.toString) *> IO.blocking {
        result match
          case Right(_) => produce.foreach(f => f(outPng)); result
          case left => left
      }

  private def writeMd(dir: os.Path, content: String = "# 报告\n\n第一段正文。\n"): os.Path =
    val p = dir / "report.md"
    Files.write(p.toNIO, content.getBytes("UTF-8"))
    p

  private def writeHtmlFile(dir: os.Path): os.Path =
    val p = dir / "diagram.html"
    Files.write(p.toNIO, "<html><body><h1>diagram</h1></body></html>".getBytes("UTF-8"))
    p

  test("W-1 an on-disk md product converts to ONE long-shot PNG and rides the image leg + the original-path note") {
    FileLongShot.Dedup.reset()
    val rec = Recorder()
    val md = writeMd(tmpDir())
    val produced = tmpDir() / "report.png"
    val longshot = new RecordingLongShot(Right(produced), Some(p => writeNoisePng(p, 40, 30)))
    adapter(rec, longshot = longshot)
      .deliver(RichKind.Unknown("extension is in neither authority table: md"), Some(md))
      .flatMap { results =>
        for
          calls <- longshot.calls.get
          input <- longshot.lastInput.get
          files <- rec.files.get
          imgs <- rec.images.get
          msgs <- rec.msgs.get
          texts <- rec.texts.get
        yield
          assertEquals(calls, 1, "the conversion seam is consulted exactly once")
          assertEquals(input, md.toString, "the seam receives the ORIGINAL md file")
          assertEquals(files.size, 0, "the original NEVER rides the file leg (2026-10-20 ruling)")
          assertEquals(imgs, List(produced), "the converted PNG is uploaded once")
          assertEquals(msgs.map(_._1), List("image"), "msg_type=image — the long-shot, not a file message")
          assertEquals(texts, List(RichDegrade.originalNote(md)), "the original-path note rides the text leg")
          assertEquals(results.size, 2, "long-shot send + note = two readings")
          assert(results.forall(_.ok), s"all readings ok, got ${results.map(_.detail)}")
          assert(FileLongShot.Dedup.size >= 1, "a successful push records the dedup key")
      }
  }

  test("W-1b an on-disk html product takes the SAME long-shot leg (the file-first face is retired)") {
    FileLongShot.Dedup.reset()
    val rec = Recorder()
    val html = writeHtmlFile(tmpDir())
    val produced = tmpDir() / "diagram.png"
    val longshot = new RecordingLongShot(Right(produced), Some(p => writeNoisePng(p, 40, 30)))
    adapter(rec, longshot = longshot)
      .deliver(RichKind.Unknown("extension is in neither authority table: html"), Some(html))
      .flatMap { results =>
        for
          input <- longshot.lastInput.get
          files <- rec.files.get
          imgs <- rec.images.get
          texts <- rec.texts.get
        yield
          assertEquals(input, html.toString, "the seam receives the ORIGINAL html file")
          assertEquals(files.size, 0, "no file upload — the file-first leg is gone")
          assertEquals(imgs, List(produced), "the converted PNG rides the image leg")
          assertEquals(texts, List(RichDegrade.originalNote(html)), "the note carries the original path")
          assertEquals(results.size, 2, "count conservation: shot send + note")
      }
  }

  test("W-2 the SAME bytes pushed twice are suppressed once (dedup): the second deliver sends NOTHING") {
    FileLongShot.Dedup.reset()
    val rec = Recorder()
    val md = writeMd(tmpDir())
    val produced = tmpDir() / "report.png"
    val longshot = new RecordingLongShot(Right(produced), Some(p => writeNoisePng(p, 40, 30)))
    val a = adapter(rec, longshot = longshot)
    for
      first <- a.deliver(RichKind.Unknown("extension is in neither authority table: md"), Some(md))
      second <- a.deliver(RichKind.Unknown("extension is in neither authority table: md"), Some(md))
      imgs <- rec.images.get
      texts <- rec.texts.get
    yield
      assertEquals(first.size, 2, "the first push delivers shot + note")
      assertEquals(second, Nil, "the duplicate push is suppressed to zero sends (防轰炸)")
      assertEquals(imgs.size, 1, "exactly ONE image upload across both delivers")
      assertEquals(texts.size, 1, "exactly ONE note across both delivers")
  }

  test("W-2b a REGENERATED file (new bytes ⇒ new dedup key) pushes again") {
    FileLongShot.Dedup.reset()
    val rec = Recorder()
    val md = writeMd(tmpDir())
    val produced = tmpDir() / "report.png"
    val longshot = new RecordingLongShot(Right(produced), Some(p => writeNoisePng(p, 40, 30)))
    val a = adapter(rec, longshot = longshot)
    val kind = RichKind.Unknown("extension is in neither authority table: md")
    for
      _ <- a.deliver(kind, Some(md))
      _ <- IO(Files.write(md.toNIO, "# 报告 v2\n\n改过的内容。\n".getBytes("UTF-8")))
      second <- a.deliver(kind, Some(md))
      imgs <- rec.images.get
    yield
      assertEquals(second.size, 2, "new bytes ⇒ new key ⇒ the push goes out again")
      assertEquals(imgs.size, 2, "both pushes uploaded")
  }

  test("W-3 a FAILED conversion degrades to exactly one 「文件名+路径+摘要」text card; nothing uploads") {
    FileLongShot.Dedup.reset()
    val rec = Recorder()
    val md = writeMd(tmpDir(), "# 存储审计\n\n结论先行的报告正文。")
    val failing = new RecordingLongShot(Left(RichDegrade.Reason.EngineUnavailable))
    adapter(rec, longshot = failing)
      .deliver(RichKind.Unknown("extension is in neither authority table: md"), Some(md))
      .flatMap { results =>
        for
          imgs <- rec.images.get
          msgs <- rec.msgs.get
          texts <- rec.texts.get
        yield
          assertEquals(imgs.size + msgs.size, 0, "a failed conversion uploads and sends nothing rich")
          assertEquals(texts.size, 1, "the degrade is exactly one visible text card")
          val line = texts.head
          assert(line.startsWith(RichDegrade.UnconvertedPrefix), s"machine-matchable prefix: $line")
          assert(line.contains("report.md"), s"the card names the file: $line")
          assert(line.contains(md.toString), s"the card carries the local path: $line")
          assert(line.contains("存储审计"), s"the card carries the one-line summary: $line")
          assert(results.size == 1 && results.head.ok, "the card SEND is the one reading")
      }
  }

  test("W-3b a long-shot UPLOAD refusal degrades to the [未渲染] line + the original-path note") {
    FileLongShot.Dedup.reset()
    val rec = Recorder(imageOk = false)
    val md = writeMd(tmpDir())
    val produced = tmpDir() / "report.png"
    val longshot = new RecordingLongShot(Right(produced), Some(p => writeNoisePng(p, 40, 30)))
    adapter(rec, longshot = longshot)
      .deliver(RichKind.Unknown("extension is in neither authority table: md"), Some(md))
      .flatMap { results =>
        for
          imgs <- rec.images.get
          texts <- rec.texts.get
        yield
          assertEquals(imgs.size, 1, "the upload WAS attempted")
          assertEquals(texts.size, 1, "the refusal lands as one text reading")
          assert(texts.head.contains("[未渲染]"), s"the closed placeholder: ${texts.head}")
          assert(texts.head.contains(md.toString), s"the note survives the degrade: ${texts.head}")
          assertEquals(
            results.size,
            1,
            "the degrade card is the one reading (the refused upload leaves no upload reading)"
          )
          assert(results.head.ok, "the CARD was delivered — the refusal lives in the card text, not a failed send")
      }
  }

  test("W-4 an off-table NON-convertible product never consults the long-shot seam (straight to b)") {
    FileLongShot.Dedup.reset()
    val rec = Recorder()
    val odd = tmpDir() / "notes.xyz"
    Files.write(odd.toNIO, Array[Byte](1))
    val longshot = new RecordingLongShot(Right(tmpDir() / "never.png"))
    adapter(rec, longshot = longshot)
      .deliver(RichKind.Unknown("extension is in neither authority table: xyz"), Some(odd))
      .flatMap { _ =>
        for
          calls <- longshot.calls.get
          texts <- rec.texts.get
        yield
          assertEquals(calls, 0, "only md/markdown/html/htm may reach the conversion seam")
          assertEquals(texts.size, 1)
          assert(texts.head.contains(RichDegrade.UnsupportedPrefix) && texts.head.contains(odd.toString), texts.head)
      }
  }

  test("W-5 the convertibility table and the offline detection face") {
    assert(FileLongShot.isConvertible(os.Path("/tmp/a/diagram.html", os.pwd)))
    assert(FileLongShot.isConvertible(os.Path("/tmp/a/x.htm", os.pwd)))
    assert(FileLongShot.isConvertible(os.Path("/tmp/a/report.md", os.pwd)), "md is the incident shape")
    assert(FileLongShot.isConvertible(os.Path("/tmp/a/REPORT.MD", os.pwd)), "case-insensitive")
    assert(FileLongShot.isConvertible(os.Path("/tmp/a/notes.markdown", os.pwd)))
    assert(!FileLongShot.isConvertible(os.Path("/tmp/a/x.htmlx", os.pwd)), "no suffix leakage across extensions")
    assert(!FileLongShot.isConvertible(os.Path("/tmp/a/noext", os.pwd)))
    // `available` is the pure discovery read (both repo scripts found), never a
    // node start — the definition is pinned so it cannot quietly narrow.
    assertEquals(
      FileLongShot.NodeShot().available,
      WebPageShot.NodePlaywright.detectScriptNamed("social-html-shot.mjs").isDefined &&
        WebPageShot.NodePlaywright.detectScriptNamed("social-md-shot.mjs").isDefined,
      "available ⇔ both conversion scripts are discovered"
    )
    // Unavailable never lies: Left with the closed-vocab engine reason.
    FileLongShot.Unavailable.shot(os.Path("/tmp/a.md", os.pwd), os.Path("/tmp/out.png", os.pwd)).map {
      case Left(reason) => assertEquals(reason, RichDegrade.Reason.EngineUnavailable)
      case Right(_) => fail("Unavailable must never fake a shot")
    }
  }

  /**
   * Remove Scala block comments and per-line `//` trailing comments. Good enough
   *  for an idiom scan: string literals containing `//` would only make the scan
   *  *weaker* on that line, never produce a false positive.
   */
  private def stripComments(src: String): String =
    val noBlocks = src.replaceAll("(?s)/\\*.*?\\*/", "")
    noBlocks.linesIterator
      .map { line =>
        val i = line.indexOf("//")
        if i >= 0 then line.substring(0, i) else line
      }
      .mkString("\n")
end RichOutboundSpec
