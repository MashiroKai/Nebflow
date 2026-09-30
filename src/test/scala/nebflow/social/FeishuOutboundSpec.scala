package nebflow.social

import cats.effect.IO
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.bridge.BridgeContext
import nebflow.gateway.NfFilePolicy
import nebflow.social.outbound.*
import nebflow.shared.AttachContract
import nebflow.shared.SessionMeta

/**
 * Offline spec for the outbound rich-content definition layer + the Feishu text
 * leg (socoutbound batch, 2026-09-30).
 *
 * Same discipline as the rest of `src/test/scala/nebflow/social`: NO network,
 * NO credentials, NO browser. Everything is judged through the two seams the
 * design card names -- the stat probe and the upload/render function types.
 *
 * These tests are the red check: each one is red-by-construction against the
 * silent-drop implementation it forbids. `OB-R3`/`OB-R5` in particular fail the
 * moment an `Unknown` or render-failure arm collapses to `IO.unit`.
 */
class FeishuOutboundSpec extends CatsEffectSuite:

  private val table = FeishuOutboundAdapter.defaultTable

  private def artifact(p: os.Path, bytes: Long = 12L): ContentClass.StatProbe =
    _ => Some(ContentClass.Artifact(p, bytes, regularFile = true))

  private def missing: ContentClass.StatProbe = _ => None

  private def path(name: String): os.Path = os.Path(s"/tmp/nebflow-socoutbound/$name")

  /** Recording renderer: counts calls so "the image branch must not render" is
    * an assertion rather than a comment. */
  private final class RecordingRenderer(val available: Boolean) extends OutboundRenderer:
    val calls = new java.util.concurrent.atomic.AtomicInteger(0)
    def render(p: OutboundPayload): IO[OutboundPayload] = IO { calls.incrementAndGet(); p }

  private final class RecordingUploads(fail: Boolean = false):
    val images = new java.util.concurrent.atomic.AtomicInteger(0)
    val files = new java.util.concurrent.atomic.AtomicInteger(0)
    val imageFn: FeishuOutboundAdapter.UploadImage = p =>
      IO { images.incrementAndGet(); if fail then FeishuOutboundAdapter.UploadResult.failed(230002, "rejected") else FeishuOutboundAdapter.UploadResult.ok("img_key_1") }
    val fileFn: FeishuOutboundAdapter.UploadFile = (p, n) =>
      IO { files.incrementAndGet(); if fail then FeishuOutboundAdapter.UploadResult.failed(230002, "rejected") else FeishuOutboundAdapter.UploadResult.ok("file_key_1") }

  private def adapter(
      renderer: OutboundRenderer = OutboundRenderer.unavailable,
      uploads: RecordingUploads = RecordingUploads()
  ): FeishuOutboundAdapter =
    new FeishuOutboundAdapter(
      sendText = (_, _, _, _, _) => IO.pure(
        FeishuChannel.SendResult(true, Some("om_1"), 0, "ok", "2026-09-30T00:00:00Z", "chat_id")),
      uploadImage = uploads.imageFn,
      uploadFile = uploads.fileFn,
      renderer = renderer,
      table = table,
      creds = Some(FeishuCredentials.Credential("test-app", "test-secret", "spec"))
    )

  // ─────────────────── §1 mechanical split criteria ───────────────────

  test("OB-R1 classify short-circuits in the card -> image -> document -> text -> unknown order") {
    val png = path("shot.png")
    val pdf = path("report.pdf")
    val odd = path("thing.zzz")
    assertEquals(ContentClass.classify(cardMarker = false, List(png), artifact(png), table), ContentClass.Image)
    assertEquals(ContentClass.classify(cardMarker = false, List(pdf), artifact(pdf), table), ContentClass.Document)
    assertEquals(ContentClass.classify(cardMarker = false, Nil, missing, table), ContentClass.Text)
    // the card source marker outranks the extension face (priority short-circuit)
    assertEquals(ContentClass.classify(cardMarker = true, List(png), artifact(png), table), ContentClass.Card)
    ContentClass.classify(cardMarker = false, List(odd), artifact(odd), table) match
      case ContentClass.Unknown(reason) => assert(reason.nonEmpty, "an unknown class always carries a non-empty reason")
      case other                        => fail(s"expected Unknown, got $other")
  }

  test("OB-R2 the extension table is the repo's single authority, and no second table is invented") {
    assertEquals(table, NfFilePolicy.NfFileAllowedExt)
    // `png` sits in the allowed table too: the image check must win, which is
    // the ordering guard that keeps an image from being classed as a document.
    assert(table.contains("png"), "png is in the allowed table")
    assertEquals(ContentClass.classifyPath(path("x.png"), artifact(path("x.png")), table), ContentClass.Image)
    // An extension outside both tables is fail-closed, never silently a document.
    assert(!table.contains("zzz"))
  }

  test("OB-R3 an unknown class yields EXACTLY one degraded text carrying the [未知类型] note (no silent drop)") {
    val odd = path("mystery.zzz")
    val plan = OutboundPlanner.unknown("extension not in any known table: /tmp/mystery.zzz", odd, Some("body text"))
    assertEquals(plan.calls, List.empty, "an unknown class mints no channel call")
    assertEquals(plan.degradedText.map(_.linesIterator.count(_.contains("[未知类型]"))), Some(1),
      "exactly one [未知类型] note — an IO.unit arm would produce zero and go red here")
    assert(plan.degradedText.exists(_.contains("mystery.zzz")), "the original path survives into the degradation")
    assert(plan.degradedText.exists(_.contains("body text")), "the original text survives into the degradation")
    assert(plan.noSilentDrop)
  }

  test("OB-R4 the image branch renders NOTHING (transparent passthrough) and mints exactly one upload") {
    val renderer = RecordingRenderer(available = true)
    val uploads = RecordingUploads()
    val png = path("direct.png")
    val plan = OutboundPlanner.image(png, "image/png", 2048L)
    assertEquals(plan.calls, List(ChannelCall.UploadImage(png, thenSendImage = true)))
    assertEquals(renderer.calls.get(), 0, "an already-pixel artifact must never go through the renderer")
    assertEquals(uploads.images.get(), 0, "planning is pure — dispatch performs the upload")
    assert(plan.noSilentDrop)
    assertEquals(adapter(renderer, uploads).channelId, "feishu")
  }

  test("OB-R5 a failed card render yields ZERO uploads, ZERO sends and one [未渲染] text") {
    val uploads = RecordingUploads()
    val html = path("card.html")
    val plan = OutboundPlanner.cardRenderFailed(Some(html), 4096L, OutboundPlanner.Reason.RenderUnavailable)
    assertEquals(plan.calls, List.empty, "no upload and no send may be minted for a failed render")
    assertEquals(plan.degradedText.map(_.linesIterator.count(_.contains("[未渲染]"))), Some(1),
      "exactly one [未渲染] placeholder — a silent arm would produce zero and go red here")
    assert(plan.degradedText.exists(_.contains("card.html")))
    assert(plan.degradedText.exists(_.contains(OutboundPlanner.Reason.RenderUnavailable)))
    assertEquals(uploads.images.get(), 0)
    assert(plan.noSilentDrop)
  }

  test("OB-R6 the size gate rejects BEFORE any upload, carrying code + actual + limit") {
    val over = AttachContract.MaxFileBytes + 1L
    val big = path("huge.pdf")
    val plan = OutboundPlanner.document(big, "huge.pdf", over)
    assertEquals(plan.calls, List.empty, "an over-limit file mints zero upload calls")
    val err = plan.rejection.getOrElse(fail("the gate refusal must be an explicit reading"))
    assertEquals(err.code, AttachContract.Codes.AttachTooLarge)
    assertEquals(err.actual, Some(over))
    assertEquals(err.limit, Some(AttachContract.MaxFileBytes))
    assert(plan.degradedText.exists(_.contains("[尺寸闸拒绝]")))
    // at the limit is allowed: the gate is `>`, not `>=`
    assertEquals(OutboundPlanner.document(big, "huge.pdf", AttachContract.MaxFileBytes).calls.size, 1)
    assert(plan.noSilentDrop)
  }

  test("OB-R7 the text leg carries no bare markdown markers and keeps links alive") {
    val md = """# Title
**bold** and _em_ and `code` and ~~struck~~
- item one
> quoted
See [docs](https://example.com/a) and https://bare.example/b
---
```scala
val x = 1
```"""
    val plain = OutboundText.toPlain(md)
    assertEquals(OutboundText.bareMarkers(plain), List.empty,
      s"bare markdown markers survived: ${OutboundText.bareMarkers(plain)}")
    assert(!plain.contains("**"), "no emphasis delimiters")
    assert(!plain.contains("~~"), "no strikethrough delimiters")
    val urls = OutboundText.links(plain)
    assert(urls.contains("https://example.com/a"), s"the labelled link died: $urls")
    assert(urls.contains("https://bare.example/b"), s"the bare link died: $urls")
    assert(plain.contains("docs"), "the link label survives as words")
    assert(plain.contains("val x = 1"), "code content is preserved, only the fence is dropped")
  }

  test("OB-R8 every plan satisfies the no-silent-drop invariants") {
    val png = path("a.png")
    val pdf = path("b.pdf")
    val plans = List(
      OutboundPlanner.text("hello"),
      OutboundPlanner.image(png, "image/png", 10L),
      OutboundPlanner.document(pdf, "b.pdf", 10L),
      OutboundPlanner.cardRendered(path("c.png"), 10L),
      OutboundPlanner.cardRenderFailed(Some(pdf), 10L, OutboundPlanner.Reason.RenderUnavailable),
      OutboundPlanner.unknown("no extension", pdf, None),
      OutboundPlanner.document(pdf, "b.pdf", AttachContract.MaxFileBytes + 1L)
    )
    plans.foreach(p => assert(p.noSilentDrop, s"silent drop in plan for ${p.cls}"))
    val failed = plans.filter(p => p.degradedText.isDefined)
    assert(failed.forall(_.calls.isEmpty), "a degraded plan never also mints calls")
    val clean = plans.filter(_.degradedText.isEmpty)
    assert(clean.forall(_.calls.nonEmpty), "an undegraded plan always carries at least one call")
  }

  test("OB-R9 the degraded text never carries a secret value") {
    val uploads = RecordingUploads(fail = true)
    val a = adapter(OutboundRenderer.unavailable, uploads)
    val html = path("card.html")
    val d = a.degrade(OutboundPayload.CardHtml("<html/>", Some("t"), Some(html)),
      OutboundPlanner.Reason.RenderUnavailable)
    val rendered = s"${d.body} ${d.reason} ${d.originalPath.map(_.toString).getOrElse("")}"
    assert(!rendered.contains("test-secret"), "the credential value must never reach a degraded body")
    assert(!rendered.toLowerCase.contains("appsecret"), "no credential key either")
    assert(d.reason.trim.nonEmpty, "a degradation reason is never empty")
    assert(d.body.contains("[未渲染]"))
  }

  test("OB-R10 dispatch conserves call count and reports failures as readings, never silently") {
    val uploads = RecordingUploads(fail = true)
    val a = adapter(OutboundRenderer.unavailable, uploads)
    val calls = List(
      ChannelCall.SendText("hi"),
      ChannelCall.UploadImage(path("x.png"), thenSendImage = true),
      ChannelCall.UploadFile(path("y.pdf"), "y.pdf", thenSendFile = true)
    )
    for
      results <- a.dispatch(calls)
    yield
      assertEquals(results.size, calls.size, "N calls in => N results out (count conservation)")
      assertEquals(results.map(_.call), calls, "results line up with the calls, in order")
      val failures = results.filter(!_.ok)
      assert(failures.nonEmpty, "a rejected upload is a failure reading, not a swallowed call")
      assert(failures.forall(_.detail.trim.nonEmpty), "every failure reading self-describes")
  }

  test("OB-R11 the Feishu adapter delegates classification to the definition layer") {
    val a = adapter()
    val cardEvent = Json.obj("type" -> Json.fromString("done"), "cardHtml" -> Json.fromString("<html/>"))
    assertEquals(a.classify(cardEvent), ContentClass.Card)
    val textEvent = Json.obj("type" -> Json.fromString("done"), "text" -> Json.fromString("plain"))
    assertEquals(a.classify(textEvent), ContentClass.Text)
    val unknownEvent = Json.obj("type" -> Json.fromString("done"),
      "path" -> Json.fromString("/tmp/nebflow-socoutbound/nope.zzz"))
    a.classify(unknownEvent) match
      case ContentClass.Unknown(reason) => assert(reason.nonEmpty)
      case other                        => fail(s"expected Unknown for a missing path, got $other")
  }

  test("OB-R12 the text leg reaches the wire de-marked-up while the in-session original is untouched") {
    val sent = new java.util.concurrent.atomic.AtomicReference[List[String]](Nil)
    val a = new FeishuOutboundAdapter(
      sendText = (_, _, _, _, text) => IO { sent.updateAndGet(_ :+ text); FeishuChannel.SendResult(true, Some("om_1"), 0, "ok", "t", "chat_id") },
      uploadImage = FeishuOutboundAdapter.noUpload,
      uploadFile = FeishuOutboundAdapter.noUploadFile,
      creds = Some(FeishuCredentials.Credential("test-app", "test-secret", "spec"))
    )
    for
      results <- a.dispatch(List(ChannelCall.SendText(OutboundText.toPlain("**hi** [d](https://e.com/x)"))))
    yield
      assert(results.forall(_.ok))
      val out = sent.get().headOption.getOrElse(fail("nothing reached the send seam"))
      assertEquals(OutboundText.bareMarkers(out), List.empty)
      assert(out.contains("https://e.com/x"), s"the link must stay alive: $out")
  }

  // ───────── the WIRED bridge leg (the only production file this batch edits) ─────────

  /** Recording BridgeContext: the bridge spec's shape, reused here so the wired
    * leg is judged through the real event entry rather than a private method. */
  private final class BridgeCtx(sessions: List[SessionMeta]) extends BridgeContext:
    def injectMessage(sessionId: String, content: String, senderId: Option[String]): IO[Unit] = IO.unit
    def interruptAgent(sessionId: String): IO[Unit] = IO.unit
    def sessionMeta(sessionId: String): IO[Option[SessionMeta]] = IO.none
    def listSessions: IO[List[SessionMeta]] = IO.pure(sessions)
    def updateBridgeConfig(sessionId: String, platform: String, config: Option[Json]): IO[Unit] = IO.unit

  private def boundMeta(id: String, chatId: String): SessionMeta =
    SessionMeta(
      id = id, name = id, createdAt = 0L, updatedAt = 0L, hasUnread = false,
      bridges = Map("feishu" -> Json.obj("chat_id" -> Json.fromString(chatId)))
    )

  test("OB-R13 the WIRED bridge text leg ships a de-marked-up copy, and the in-session original is untouched") {
    val sent = new java.util.concurrent.atomic.AtomicReference[List[String]](Nil)
    val p = new FeishuBridgePlugin(
      os.temp.dir(prefix = "nb-socoutbound-bridge-"),
      send = (_, _, _, receiveId, text) =>
        IO { sent.updateAndGet(_ :+ s"$receiveId|$text"); FeishuChannel.SendResult(true, Some("om_1"), 0, "ok", "t", "chat_id") },
      pinnedAllowedOpenIds = None,
      pinnedCreds = Some(FeishuCredentials.Credential("test-app", "test-secret", "spec")))
    // The model's own writing: raw markdown, exactly as the session shows it.
    val raw = "**bold** and `code` — see [docs](https://e.com/x) and # heading"

    def delta(s: String): Json =
      Json.obj("type" -> Json.fromString("textDelta"), "sessionId" -> Json.fromString(s),
        "delta" -> Json.fromString(s))
    def done(s: String): Json =
      Json.obj("type" -> Json.fromString("done"), "sessionId" -> Json.fromString(s))

    for
      _ <- p.rebuildRoutes(BridgeCtx(List(boundMeta("s1", "oc_bound"))))
      _ <- p.onAgentEvent("s1", delta(raw))
      _ <- p.onAgentEvent("s1", done("s1"))
    yield
      val wire = sent.get().headOption.getOrElse(fail("nothing reached the send seam"))
      // Split on the first separator by index: the deprecated Char overload of
      // String.split is fatal under -Xfatal-warnings, and a regex separator would
      // make `|` an alternation.
      val sep = wire.indexOf('|')
      val body =
        if sep >= 0 then wire.substring(sep + 1)
        else fail(s"malformed record: $wire")
      // The red-by-construction core: without the reply() conversion this is raw
      // markdown and bareMarkers is non-empty.
      assertEquals(OutboundText.bareMarkers(body), List.empty,
        s"bare markdown reached the wire: ${OutboundText.bareMarkers(body)}")
      assert(!body.contains("**"), s"emphasis delimiter survived: $body")
      assert(!body.contains("`"), s"inline-code delimiter survived: $body")
      // Links stay alive as bare URLs, labels stay as words.
      assertEquals(OutboundText.links(body), List("https://e.com/x"),
        s"the link must survive the wired leg: $body")
      assert(body.contains("docs"), s"the link label survives as words: $body")
      // The conversion really happened (a no-op passthrough would satisfy the
      // marker assertion above only if the input had no markers -- guarded below).
      assert(body != raw, s"the wire body is still the raw markdown: $body")
      // The conversion is a PURE function applied to the outbound argument: the
      // session-side copy is never rewritten, so the original still carries its
      // own markup. Asserted directly so the red above cannot be vacuous.
      assert(OutboundText.bareMarkers(raw).nonEmpty,
        "the original really does carry bare markers (else the red above is vacuous)")
      assertEquals(OutboundText.toPlain(raw), body,
        "the wire body is exactly the one conversion applied to the original")
  }
