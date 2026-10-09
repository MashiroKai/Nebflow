package nebflow.social

import cats.effect.IO
import cats.effect.Ref
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.bridge.BridgeContext
import nebflow.core.tools.PopTool
import nebflow.shared.SessionMeta
import nebflow.social.outbound.{RichFeishuAdapter, RichKind, RichPlanner, RichRenderer}

import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO

/**
 * Main-link spec for the rich-content outbound leg (richcontent main-link batch,
 * soc483).
 *
 * What this pins, and why it is a different object from [[RichOutboundSpec]]:
 * that spec proves the rich ADAPTER (classify → plan → render → deliver →
 * degrade). This one proves the WIRING — that a `done` turn carrying rich
 * content actually leaves the bridge through the rich leg, that a turn without
 * one leaves through the EXISTING text leg byte-for-byte, and that every
 * failure degrades into visible text instead of silence.
 *
 * 🔴 NO NETWORK, NO CREDENTIAL, NO BROWSER. Every outbound seam of the adapter
 * the factory builds is a recorder (R-2), the credential is a pinned fake, and
 * the renderer is injected. The bridge's own text `send` seam is a recorder too,
 * so "the text leg is unchanged" is an assertion on the recorded wire, not a
 * code read.
 *
 * Each assertion is red-by-construction: removing the `toolEnd` arm, the rich
 * memory, or the rich dispatch in `onAgentEvent` makes one of these fail.
 */
class FeishuRichMainLinkSpec extends CatsEffectSuite:

  // ───────────────────────────── fixtures ─────────────────────────────

  private val DocumentExts: Set[String] = FeishuBridgePlugin.defaultDocumentExtensions

  private def tmpDir(): os.Path = os.temp.dir(prefix = "nb-rich-mainlink-")

  /** A REAL, non-uniform PNG — the shape a browser screenshot has. */
  private def writeNoisePng(path: os.Path, w: Int, h: Int): os.Path =
    val img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val rnd = new java.util.Random(7L)
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

  private def writeDoc(path: os.Path): os.Path =
    Files.createDirectories(path.toNIO.getParent)
    Files.write(path.toNIO, Array[Byte](1, 2, 3, 4))
    path

  /** Recording renderer — never starts a process; can be told to fail. */
  private final class RecordingRenderer(result: RichRenderer.RenderResult,
      produce: Option[(os.Path, Int, Int) => Unit] = None) extends RichRenderer.Renderer:
    val calls: Ref[IO, Int] = Ref.unsafe[IO, Int](0)
    def available: Boolean = true
    def render(html: String, outPng: os.Path, width: Int, height: Int): IO[RichRenderer.RenderResult] =
      calls.update(_ + 1) *> IO.blocking {
        if result.ok then produce.foreach(f => f(outPng, width, height))
        result
      }

  /** Records every rich-leg outbound call. Flags drive one leg's failure branch
    * so the degrade path can be exercised without touching the others. */
  private final class OutboundRecorder(imageOk: Boolean = true):
    val images: Ref[IO, List[os.Path]] = Ref.unsafe[IO, List[os.Path]](Nil)
    val files: Ref[IO, List[(os.Path, String)]] = Ref.unsafe[IO, List[(os.Path, String)]](Nil)
    val msgs: Ref[IO, List[(String, String)]] = Ref.unsafe[IO, List[(String, String)]](Nil)
    val texts: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)

    def uploadImage: RichFeishuAdapter.UploadImage = p =>
      images.update(_ :+ p).as(
        if imageOk then RichFeishuAdapter.UploadResult(true, "img_key_1", 0, "ok")
        else RichFeishuAdapter.UploadResult(false, "", 230020, "upload refused"))
    def uploadFile: RichFeishuAdapter.UploadFile = (p, n) =>
      files.update(_ :+ ((p, n))).as(RichFeishuAdapter.UploadResult(true, "file_key_1", 0, "ok"))
    def sendMsg: RichFeishuAdapter.SendMsg = (t, c) =>
      msgs.update(_ :+ ((t, c))).as(RichFeishuAdapter.SendResult(true, Some("om_msg_1"), 0, "ok"))
    def sendText: RichFeishuAdapter.SendText = t =>
      texts.update(_ :+ t).as(RichFeishuAdapter.SendResult(true, Some("om_text_1"), 0, "ok"))

  /** Recording BridgeContext: a fixed session list, nothing else. */
  private final class RecordingCtx(sessions: List[SessionMeta]) extends BridgeContext:
    val injected: Ref[IO, List[(String, String, Option[String])]] =
      Ref.unsafe[IO, List[(String, String, Option[String])]](Nil)
    def injectMessage(sessionId: String, content: String, senderId: Option[String],
        origin: Option[nebflow.bridge.BridgeOrigin] = None): IO[Unit] =
      injected.update(_ :+ ((sessionId, content, senderId)))
    def interruptAgent(sessionId: String): IO[Unit] = IO.unit
    def sessionMeta(sessionId: String): IO[Option[SessionMeta]] = IO.none
    def listSessions: IO[List[SessionMeta]] = IO.pure(sessions)
    def updateBridgeConfig(sessionId: String, platform: String, config: Option[Json]): IO[Unit] = IO.unit

  private def boundMeta(id: String, chatId: String): SessionMeta =
    SessionMeta(id = id, name = id, createdAt = 0L, updatedAt = 0L, hasUnread = false,
      bridges = Map(FeishuBridgePlugin.Name -> Json.obj("chat_id" -> Json.fromString(chatId))))

  /** The bridge's own text send seam — the recorded wire of the EXISTING leg. */
  private final class SendRecorder:
    val calls: Ref[IO, List[(String, String)]] = Ref.unsafe[IO, List[(String, String)]](Nil)
    val fn: FeishuBridgePlugin.Send = (_, _, _, receiveId, text) =>
      calls.update(_ :+ ((receiveId, text)))
        .as(FeishuChannel.SendResult(true, Some("om_text"), 0, "ok", "t", "chat_id"))

  /** A bridge whose rich factory builds an adapter over the given recorder. */
  private def bridge(rec: OutboundRecorder, renderer: RichRenderer.Renderer, send: SendRecorder): FeishuBridgePlugin =
    new FeishuBridgePlugin(
      tmpDir(),
      send = send.fn,
      pinnedAllowedOpenIds = None,
      pinnedCreds = Some(FeishuCredentials.Credential("test-app", "test-secret", "spec")),
      buildRichAdapter = (_, _, _) =>
        new RichFeishuAdapter(rec.uploadImage, rec.uploadFile, rec.sendMsg, rec.sendText, renderer, () => tmpDir()))

  private def textDelta(sessionId: String, text: String): Json =
    Json.obj("type" -> "textDelta".asJson, "sessionId" -> sessionId.asJson, "delta" -> text.asJson)

  private def toolEnd(sessionId: String, content: String, input: Option[Json] = None, isError: Boolean = false): Json =
    input.fold(
      Json.obj("type" -> "toolEnd".asJson, "sessionId" -> sessionId.asJson, "label" -> "Pop".asJson,
        "summary" -> "shown".asJson, "content" -> content.asJson, "isError" -> isError.asJson)
    )(i =>
      Json.obj("type" -> "toolEnd".asJson, "sessionId" -> sessionId.asJson, "label" -> "Pop".asJson,
        "summary" -> "shown".asJson, "content" -> content.asJson, "isError" -> isError.asJson,
        "input" -> i))

  private def done(sessionId: String): Json =
    Json.obj("type" -> "done".asJson, "sessionId" -> sessionId.asJson)

  private def cardPayload(html: String = "<html><body><h1>card</h1></body></html>"): String =
    RichKind.CardSentinel + Json.obj("html" -> Json.fromString(html)).noSpaces

  private def popPayload(items: Json*): String =
    PopTool.Sentinel + Json.obj("items" -> Json.arr(items*)).noSpaces

  private def item(kind: String, name: String, p: os.Path): Json =
    Json.obj("kind" -> kind.asJson, "name" -> name.asJson, "path" -> p.toString.asJson)

  // ─────────────── mirror guards (the pinned literals vs their authorities) ───────────────

  test("ML-0 the pinned pop sentinel mirrors PopTool.Sentinel; the card marker is the frontend's") {
    assertEquals(FeishuBridgePlugin.RichProduct.PopSentinel, PopTool.Sentinel,
      "the pop sentinel must mirror the tool that writes it")
    // The card marker family the frontend actually splits on — read from the
    // served tree, not restated: `^___<NAME>_HTML___` (cardRegistry.js).
    val rel = os.RelPath("src/main/resources/web/js/cardRegistry.js")
    val candidates = List(os.pwd / rel, os.Path(System.getProperty("user.dir"), os.pwd) / rel)
    val js = os.read(candidates.find(os.exists).getOrElse(fail(s"cardRegistry.js not found; tried $candidates")))
    assert(js.contains("""/^___\w+_HTML___/"""),
      "the frontend must still split on the card-marker family; the pinned literal's authority moved")
    assert(RichKind.CardSentinel.matches("""^___\w+_HTML___"""),
      "the pinned card sentinel must be inside the frontend's marker family")
  }

  // ─────────────── the rich leg fires (image / document / card ≥ 1 each) ───────────────

  test("ML-1 a CARD product in the turn makes the done turn deliver a rich card AND keeps the text reply") {
    val rec = new OutboundRecorder
    val send = new SendRecorder
    val produced = tmpDir() / "card.png"
    val renderer = new RecordingRenderer(
      RichRenderer.RenderResult(true, Some(produced), 4096L, 600, 320, "", "PNG 600x320"),
      Some((p, w, h) => { writeNoisePng(p, w, h); () }))
    val p = bridge(rec, renderer, send)
    val ctx = new RecordingCtx(List(boundMeta("s1", "oc_bound")))

    for
      _ <- p.rebuildRoutes(ctx)
      _ <- p.onAgentEvent("s1", textDelta("s1", "here is the card"))
      _ <- p.onAgentEvent("s1", toolEnd("s1", cardPayload()))
      _ <- p.onAgentEvent("s1", done("s1"))
      imgs <- rec.images.get
      msgs <- rec.msgs.get
      texts <- rec.texts.get
      sent <- send.calls.get
    yield
      assertEquals(imgs.size, 1, "the card screenshot must be uploaded exactly once")
      assertEquals(msgs.map(_._1), List("image"), "a rendered card rides an image message")
      assertEquals(texts, List(RichPlanner.InteractivePanelNote),
        "the interactive-panel note must be sent verbatim by the rich leg")
      assertEquals(sent.map(_._2), List("here is the card"),
        "the accumulated text reply must still go out through the EXISTING text leg")
  }

  test("ML-2 an IMAGE product (pop item) makes the done turn take the rich image leg") {
    val rec = new OutboundRecorder
    val send = new SendRecorder
    val img = writeNoisePng(tmpDir() / "shot.png", 12, 12)
    val p = bridge(rec, RichRenderer.Unavailable, send)
    val ctx = new RecordingCtx(List(boundMeta("s1", "oc_bound")))

    for
      _ <- p.rebuildRoutes(ctx)
      _ <- p.onAgentEvent("s1", toolEnd("s1", popPayload(item("image", "shot.png", img))))
      _ <- p.onAgentEvent("s1", done("s1"))
      imgs <- rec.images.get
      msgs <- rec.msgs.get
      texts <- rec.texts.get
    yield
      assertEquals(imgs, List(img), "route D: the image is sent with its real bytes, no render")
      assertEquals(msgs.map(_._1), List("image"))
      assertEquals(texts, Nil, "a successful image leg needs no text fallback")
  }

  test("ML-3 a DOCUMENT product (pop file item) makes the done turn take the rich file leg") {
    val rec = new OutboundRecorder
    val send = new SendRecorder
    val doc = writeDoc(tmpDir() / "report.pdf")
    val p = bridge(rec, RichRenderer.Unavailable, send)
    val ctx = new RecordingCtx(List(boundMeta("s1", "oc_bound")))

    for
      _ <- p.rebuildRoutes(ctx)
      _ <- p.onAgentEvent("s1", toolEnd("s1", popPayload(item("file", "report.pdf", doc))))
      _ <- p.onAgentEvent("s1", done("s1"))
      files <- rec.files.get
      msgs <- rec.msgs.get
    yield
      assertEquals(files.map(_._1), List(doc), "route E: the original file is uploaded, never rasterised")
      assertEquals(msgs.map(_._1), List("file"), "the document leg sends msg_type=file")
  }

  // ─────────────── degrade: never a silent drop ───────────────

  test("ML-4 a card whose render FAILS degrades to visible [未渲染] text plus the note, and uploads nothing") {
    val rec = new OutboundRecorder
    val send = new SendRecorder
    val renderer = new RecordingRenderer(RichRenderer.RenderResult.engineUnavailable("no Chrome on this host"))
    val p = bridge(rec, renderer, send)
    val ctx = new RecordingCtx(List(boundMeta("s1", "oc_bound")))

    for
      _ <- p.rebuildRoutes(ctx)
      _ <- p.onAgentEvent("s1", toolEnd("s1", cardPayload()))
      _ <- p.onAgentEvent("s1", done("s1"))
      imgs <- rec.images.get
      files <- rec.files.get
      msgs <- rec.msgs.get
      texts <- rec.texts.get
    yield
      assertEquals(imgs.size + files.size + msgs.size, 0, "a failed render must not upload or send anything")
      assertEquals(texts.size, 1, "the failure must be visible as text, never silent")
      assert(texts.head.contains("[未渲染]"), s"the §7.1 placeholder is missing: ${texts.head}")
      assert(texts.head.contains(RichPlanner.InteractivePanelNote), s"the note survives the degrade: ${texts.head}")
  }

  test("ML-5 a rich-classified product whose path is gone still produces a visible line (no silence)") {
    val rec = new OutboundRecorder
    val send = new SendRecorder
    // A pop payload naming a path that does not exist. The product IS remembered
    // (its extension is rich), and at `done` the classification is `Unknown`
    // ("path does not exist") — which the adapter must turn into a visible
    // `[未知类型] …` line rather than dropping the turn.
    val missing = tmpDir() / "gone.png"
    val p = bridge(rec, RichRenderer.Unavailable, send)
    val ctx = new RecordingCtx(List(boundMeta("s1", "oc_bound")))

    for
      _ <- p.rebuildRoutes(ctx)
      _ <- p.onAgentEvent("s1", toolEnd("s1", popPayload(item("image", "gone.png", missing))))
      _ <- p.onAgentEvent("s1", done("s1"))
      imgs <- rec.images.get
      texts <- rec.texts.get
    yield
      assertEquals(imgs.size, 0, "a missing product must never reach the upload leg")
      assertEquals(texts.size, 1, "a missing product must still produce exactly one visible line")
      assert(texts.head.contains("[未知类型]"), s"expected a [未知类型] line, got: ${texts.head}")
      assert(texts.head.contains(missing.toString), s"the line must name the product: ${texts.head}")
  }

  test("ML-5b an oversize product degrades to the size-gate line BEFORE any upload") {
    val rec = new OutboundRecorder
    val send = new SendRecorder
    val big = tmpDir() / "huge.pdf"
    Files.createDirectories(big.toNIO.getParent)
    val ch = Files.newByteChannel(big.toNIO,
      java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)
    try
      ch.position(nebflow.shared.AttachContract.MaxFileBytes) // one byte over
      ch.write(java.nio.ByteBuffer.wrap(Array[Byte](0)))
    finally ch.close()

    val p = bridge(rec, RichRenderer.Unavailable, send)
    val ctx = new RecordingCtx(List(boundMeta("s1", "oc_bound")))

    for
      _ <- p.rebuildRoutes(ctx)
      _ <- p.onAgentEvent("s1", toolEnd("s1", popPayload(item("file", "huge.pdf", big))))
      _ <- p.onAgentEvent("s1", done("s1"))
      files <- rec.files.get
      texts <- rec.texts.get
    yield
      assertEquals(files.size, 0, "an oversize product must produce ZERO uploads")
      assertEquals(texts.size, 1)
      assert(texts.head.contains("尺寸闸拒绝"), s"the size-gate reason must be echoed: ${texts.head}")
      assert(texts.head.contains(nebflow.shared.AttachContract.Codes.AttachTooLarge),
        s"the existing error code must be echoed: ${texts.head}")
  }

  test("ML-5c an upload the channel refuses still tells the recipient (code echoed, no silence)") {
    val rec = new OutboundRecorder(imageOk = false)
    val send = new SendRecorder
    val img = writeNoisePng(tmpDir() / "shot.png", 10, 10)
    val p = bridge(rec, RichRenderer.Unavailable, send)
    val ctx = new RecordingCtx(List(boundMeta("s1", "oc_bound")))

    for
      _ <- p.rebuildRoutes(ctx)
      _ <- p.onAgentEvent("s1", toolEnd("s1", popPayload(item("image", "shot.png", img))))
      _ <- p.onAgentEvent("s1", done("s1"))
      imgs <- rec.images.get
      texts <- rec.texts.get
    yield
      assertEquals(imgs, List(img), "the upload was attempted")
      assertEquals(texts.size, 1, "the refusal must also be reported to the recipient")
      assert(texts.head.contains("渠道拒绝(code=230020)"), s"the channel code must be echoed: ${texts.head}")
  }

  test("ML-6 an ERRORED toolEnd contributes no rich product (its content is an error string)") {
    val rec = new OutboundRecorder
    val send = new SendRecorder
    val p = bridge(rec, RichRenderer.Unavailable, send)
    val ctx = new RecordingCtx(List(boundMeta("s1", "oc_bound")))

    for
      _ <- p.rebuildRoutes(ctx)
      _ <- p.onAgentEvent("s1", textDelta("s1", "failed"))
      _ <- p.onAgentEvent("s1", toolEnd("s1", cardPayload(), isError = true))
      _ <- p.onAgentEvent("s1", done("s1"))
      imgs <- rec.images.get
      texts <- rec.texts.get
      sent <- send.calls.get
    yield
      assertEquals(imgs.size + texts.size, 0, "an errored tool result must not reach the rich leg")
      assertEquals(sent.map(_._2), List("failed"), "the plain text reply is the whole turn")
  }

  // ─────────────── the plain-text path is byte-identical ───────────────

  test("ML-7 a turn with NO rich product is byte-identical to the pre-batch text path") {
    val rec = new OutboundRecorder
    val send = new SendRecorder
    val renderer = new RecordingRenderer(RichRenderer.RenderResult.engineUnavailable("must not be called"))
    val p = bridge(rec, renderer, send)
    val ctx = new RecordingCtx(List(boundMeta("s1", "oc_bound")))
    val raw = "**bold** and `code` and _em_ — see [docs](https://e.com/x)"

    for
      _ <- p.rebuildRoutes(ctx)
      _ <- p.onAgentEvent("s1", textDelta("s1", raw))
      _ <- p.onAgentEvent("s1", done("s1"))
      calls <- renderer.calls.get
      imgs <- rec.images.get
      files <- rec.files.get
      texts <- rec.texts.get
      sent <- send.calls.get
    yield
      assertEquals(sent.map(_._2), List(nebflow.social.outbound.OutboundText.toPlain(raw)),
        "the wire text is the SAME de-marked-up copy the pre-batch path produced")
      assertEquals(calls, 0, "no rich product ⇒ the renderer is never consulted")
      assertEquals(imgs.size + files.size + texts.size, 0, "no rich product ⇒ no rich-leg call of any kind")
  }

  test("ML-8 a plain (non-rich) file argument never turns a turn rich") {
    val rec = new OutboundRecorder
    val send = new SendRecorder
    val p = bridge(rec, RichRenderer.Unavailable, send)
    val ctx = new RecordingCtx(List(boundMeta("s1", "oc_bound")))
    val odd = writeDoc(tmpDir() / "notes.xyz")

    for
      _ <- p.rebuildRoutes(ctx)
      _ <- p.onAgentEvent("s1", textDelta("s1", "done"))
      _ <- p.onAgentEvent("s1", toolEnd("s1", s"file at $odd", Some(Json.obj("path" -> odd.toString.asJson))))
      _ <- p.onAgentEvent("s1", done("s1"))
      imgs <- rec.images.get
      files <- rec.files.get
      texts <- rec.texts.get
      sent <- send.calls.get
    yield
      assertEquals(imgs.size + files.size + texts.size, 0,
        "an extension in neither rich table must not enter the rich leg")
      assertEquals(sent.map(_._2), List("done"), "the text leg carries the whole turn")
  }

  // ─────────────── a torn turn is never a rich reply ───────────────

  test("ML-9 an interrupted turn drops the remembered rich product: no card is sent afterwards") {
    val rec = new OutboundRecorder
    val send = new SendRecorder
    val produced = tmpDir() / "card.png"
    val renderer = new RecordingRenderer(
      RichRenderer.RenderResult(true, Some(produced), 4096L, 600, 320, "", "PNG 600x320"),
      Some((p, w, h) => { writeNoisePng(p, w, h); () }))
    val p = bridge(rec, renderer, send)
    val ctx = new RecordingCtx(List(boundMeta("s1", "oc_bound")))
    val interrupted = Json.obj("type" -> "interrupted".asJson, "sessionId" -> "s1".asJson)

    for
      _ <- p.rebuildRoutes(ctx)
      _ <- p.onAgentEvent("s1", toolEnd("s1", cardPayload()))
      _ <- p.onAgentEvent("s1", interrupted)
      _ <- p.onAgentEvent("s1", done("s1"))
      imgs <- rec.images.get
      texts <- rec.texts.get
    yield
      assertEquals(imgs.size + texts.size, 0, "a torn turn must not flush the rich product it had observed")
  }

  test("ML-10 a session that is not feishu-bound never reaches the rich leg") {
    val rec = new OutboundRecorder
    val send = new SendRecorder
    val p = bridge(rec, RichRenderer.Unavailable, send)
    val ctx = new RecordingCtx(Nil) // no binding at all

    for
      _ <- p.rebuildRoutes(ctx)
      _ <- p.onAgentEvent("s1", toolEnd("s1", cardPayload()))
      _ <- p.onAgentEvent("s1", done("s1"))
      imgs <- rec.images.get
      texts <- rec.texts.get
      sent <- send.calls.get
    yield
      assertEquals(imgs.size + texts.size + sent.size, 0, "an unbound session reaches no outbound seam")
  }
