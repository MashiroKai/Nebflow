package nebflow.social

import cats.effect.IO
import cats.effect.Ref
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.shared.SessionMeta

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * Offline regression spec for the weixin OUTBOUND leg (weixin-outbound batch,
 * 2026-10-07). Deliberately NO network and NO credential: the send seam is a
 * recorder ([[WeixinIlinkBridgePlugin]]'s injected `send` factory), the token
 * lives in a temp data root, and the response mapping is exercised through the
 * pure [[WeixinOutboundAdapter.parseSendResponse]]. What is pinned:
 *
 *   - the token mint (owner-only from the first byte, idempotent, never
 *     rotated on a second call);
 *   - the reply-flush shape (textDelta accumulate → ONE send on `done`, plain
 *     text through OutboundText.toPlain, torn turns never flush);
 *   - the reverse routing rule (only an explicitly bound sender is a target);
 *   - containment (a failing send never raises, a flush never duplicates);
 *   - the wire request/response mapping (pure functions).
 */
class WeixinOutboundSpec extends CatsEffectSuite:

  private def tmpRoot(): os.Path = os.temp.dir(prefix = "nb-weixin-outbound-")

  /** The recording send seam: captures (to, text) pairs, answers `result`. */
  private final class RecordingSend(result: WeixinOutboundAdapter.SendResult):
    val calls: Ref[IO, List[(String, String)]] = Ref.unsafe[IO, List[(String, String)]](Nil)
    val sendFn: WeixinOutboundAdapter.Send = (to, text) => calls.update(_ :+ ((to, text))).as(result)
    val factory: os.Path => WeixinIlinkBridgePlugin.Send = _ => sendFn

  private def okResult =
    WeixinOutboundAdapter.SendResult(true, true, Some("421001"), 200, "delivered")

  private def plugin(root: os.Path, send: os.Path => WeixinIlinkBridgePlugin.Send): WeixinIlinkBridgePlugin =
    new WeixinIlinkBridgePlugin(root, pinnedAllowedIlinkUserIds = None, send = send)

  private def meta(id: String, senderId: Option[String]): SessionMeta =
    SessionMeta(
      id = id,
      name = id,
      createdAt = 0L,
      updatedAt = 0L,
      hasUnread = false,
      bridges = senderId
        .map(s => Map(WeixinIlinkBridgePlugin.Name -> Json.obj("ilink_user_id" -> Json.fromString(s))))
        .getOrElse(Map.empty)
    )

  private def recordingCtx(sessions: List[SessionMeta]) = new nebflow.bridge.BridgeContext:
    def injectMessage(sessionId: String, content: String, senderId: Option[String],
        origin: Option[nebflow.bridge.BridgeOrigin] = None): IO[Unit] = IO.unit
    def interruptAgent(sessionId: String): IO[Unit] = IO.unit
    def sessionMeta(sessionId: String): IO[Option[SessionMeta]] = IO.none
    def listSessions: IO[List[SessionMeta]] = IO.pure(sessions)
    def updateBridgeConfig(sessionId: String, platform: String, config: Option[Json]): IO[Unit] = IO.unit

  // ───────────────────────── shared token face ─────────────────────────

  test("WO-K1 ensureToken mints an owner-only token; the second call returns the SAME value (never rotated)") {
    val root = tmpRoot()
    for
      first <- WeixinOutboundAdapter.ensureToken(root)
      second <- WeixinOutboundAdapter.ensureToken(root)
      read <- WeixinOutboundAdapter.readToken(root)
    yield
      val t1 = first.getOrElse(fail(s"the first mint must succeed, got: $first"))
      val t2 = second.getOrElse(fail(s"the second mint must succeed, got: $second"))
      assertEquals(t2, t1, "an existing token is read, never rotated (rotation would desync a running side-car)")
      assertEquals(read, Some(t1))
      assert(t1.length >= 40, s"a 32-byte base64url token is at least 40 chars, got ${t1.length}")
      val path = WeixinOutboundAdapter.tokenPath(root).toNIO
      assertEquals(
        PosixFilePermissions.toString(Files.getPosixFilePermissions(path)),
        "rw-------",
        "the token file is owner-only from its first byte (the F1 discipline)"
      )
  }

  test("WO-K2 a channel-less root gains NO token file from sendLive (the leg stays closed, spelled out)") {
    val root = tmpRoot()
    for r <- WeixinOutboundAdapter.sendLive(root)("wx_alice", "hello")
    yield
      assertEquals(r.ok, false)
      assert(r.reason.startsWith("shim_token_unconfigured"), r.reason)
      assert(!Files.exists(WeixinOutboundAdapter.tokenPath(root).toNIO),
        "the send face never mints on its own authority — minting happens only in the plugin start path")
  }

  test("WO-K3 sidecarBase reads the stored card, absent ⇒ the scan-bind default") {
    val root = tmpRoot()
    assertEquals(WeixinOutboundAdapter.sidecarBase(root), WeixinIlinkScanBind.SidecarDefaultBase)
    SocialChannels.save(
      root,
      WeixinIlinkBridgePlugin.Name,
      io.circe.parser.parse("""{"fields":{"sidecar_url":"http://127.0.0.1:8788"}}""").toOption.get
    ) match
      case Right(_) => ()
      case Left(err) => fail(s"save refused: $err")
    assertEquals(WeixinOutboundAdapter.sidecarBase(root), "http://127.0.0.1:8788")
  }

  // ───────────────────────── reply flush shape ─────────────────────────

  test("WO-F1 textDelta accumulates and `done` flushes ONE plain-text send to the bound sender") {
    val rec = RecordingSend(okResult)
    val p = plugin(tmpRoot(), rec.factory)
    for
      _ <- p.start(recordingCtx(List(meta("s1", Some("wx_alice")))))
      _ <- p.onAgentEvent("s1", Json.obj("type" -> "textDelta".asJson, "delta" -> "你好".asJson))
      _ <- p.onAgentEvent("s1", Json.obj("type" -> "textDelta".asJson, "delta" -> "，世界".asJson))
      _ <- p.onAgentEvent("s1", Json.obj("type" -> "done".asJson))
      calls <- rec.calls.get
      after <- p.pendingRef.get
    yield
      assertEquals(calls, List(("wx_alice", "你好，世界")), "ONE send, to the bound sender, whole turn text")
      assertEquals(after.get("s1"), None, "the buffer is drained by the flush")
  }

  test("WO-F2 the outbound copy is de-marked-up (toPlain) — the in-session original is never rewritten") {
    val rec = RecordingSend(okResult)
    val p = plugin(tmpRoot(), rec.factory)
    for
      _ <- p.start(recordingCtx(List(meta("s1", Some("wx_alice")))))
      _ <- p.onAgentEvent("s1", Json.obj("type" -> "textDelta".asJson,
        "delta" -> "**bold** and a [link](https://example.com)".asJson))
      _ <- p.onAgentEvent("s1", Json.obj("type" -> "done".asJson))
      calls <- rec.calls.get
    yield
      val sent = calls.headOption.map(_._2).getOrElse(fail("one send expected"))
      assert(!sent.contains("**") && !sent.contains("](https://"),
        s"markdown syntax must not reach the wire, got: $sent")
      assert(sent.contains("bold") && sent.contains("https://example.com"),
        s"the content itself must survive the de-markup, got: $sent")
  }

  test("WO-F3 an interrupted turn never flushes (a torn turn is not a reply)") {
    val rec = RecordingSend(okResult)
    val p = plugin(tmpRoot(), rec.factory)
    for
      _ <- p.start(recordingCtx(List(meta("s1", Some("wx_alice")))))
      _ <- p.onAgentEvent("s1", Json.obj("type" -> "textDelta".asJson, "delta" -> "partial".asJson))
      _ <- p.onAgentEvent("s1", Json.obj("type" -> "interrupted".asJson))
      _ <- p.onAgentEvent("s1", Json.obj("type" -> "done".asJson))
      calls <- rec.calls.get
    yield assertEquals(calls, Nil, "interrupted clears the buffer; the later done has nothing to flush")
  }

  test("WO-F4 a retried turn (retryStatus) never flushes its stale partial either") {
    val rec = RecordingSend(okResult)
    val p = plugin(tmpRoot(), rec.factory)
    for
      _ <- p.start(recordingCtx(List(meta("s1", Some("wx_alice")))))
      _ <- p.onAgentEvent("s1", Json.obj("type" -> "textDelta".asJson, "delta" -> "stale".asJson))
      _ <- p.onAgentEvent("s1", Json.obj("type" -> "retryStatus".asJson))
      _ <- p.onAgentEvent("s1", Json.obj("type" -> "done".asJson))
      calls <- rec.calls.get
    yield assertEquals(calls, Nil)
  }

  test("WO-F5 done with an empty buffer sends nothing (no fabricated empty message)") {
    val rec = RecordingSend(okResult)
    val p = plugin(tmpRoot(), rec.factory)
    for
      _ <- p.start(recordingCtx(List(meta("s1", Some("wx_alice")))))
      _ <- p.onAgentEvent("s1", Json.obj("type" -> "done".asJson))
      calls <- rec.calls.get
    yield assertEquals(calls, Nil)
  }

  test("WO-F6 only the turn's OWN session flushes (per-session buffers, not one global buffer)") {
    val rec = RecordingSend(okResult)
    val p = plugin(tmpRoot(), rec.factory)
    for
      _ <- p.start(recordingCtx(List(meta("s1", Some("wx_alice")), meta("s2", Some("wx_bob")))))
      _ <- p.onAgentEvent("s1", Json.obj("type" -> "textDelta".asJson, "delta" -> "for alice".asJson))
      _ <- p.onAgentEvent("s1", Json.obj("type" -> "done".asJson))
      calls <- rec.calls.get
    yield assertEquals(calls, List(("wx_alice", "for alice")), "s2's buffer is untouched by s1's flush")
  }

  test("WO-F7 a session with NO bound sender has no return address: dropped, nothing sent") {
    val rec = RecordingSend(okResult)
    val p = plugin(tmpRoot(), rec.factory)
    for
      _ <- p.start(recordingCtx(List(meta("s9", None)))) // default-session-style session, no binding
      _ <- p.onAgentEvent("s9", Json.obj("type" -> "textDelta".asJson, "delta" -> "hi".asJson))
      _ <- p.onAgentEvent("s9", Json.obj("type" -> "done".asJson))
      calls <- rec.calls.get
    yield assertEquals(calls, Nil, "no binding ⇒ no iLink target; the WARN names no-return-address")
  }

  test("WO-F8 a failing send is contained: the flush completes, no exception escapes") {
    val failing = RecordingSend(
      WeixinOutboundAdapter.SendResult(false, false, None, 502, "delivery_failed: sendmessage ret=1"))
    val p = plugin(tmpRoot(), failing.factory)
    for
      _ <- p.start(recordingCtx(List(meta("s1", Some("wx_alice")))))
      _ <- p.onAgentEvent("s1", Json.obj("type" -> "textDelta".asJson, "delta" -> "hi".asJson))
      _ <- p.onAgentEvent("s1", Json.obj("type" -> "done".asJson))
      calls <- failing.calls.get
    yield
      assertEquals(calls.size, 1, "the attempt WAS made (fail-closed, not silently skipped)")
      // no raise: the for-comprehension above completing IS the assertion
  }

  test("WO-F9 stop clears the pending buffer (a torn turn is never a reply, also across restarts)") {
    val rec = RecordingSend(okResult)
    val p = plugin(tmpRoot(), rec.factory)
    for
      _ <- p.start(recordingCtx(List(meta("s1", Some("wx_alice")))))
      _ <- p.onAgentEvent("s1", Json.obj("type" -> "textDelta".asJson, "delta" -> "orphan".asJson))
      _ <- p.stop
      _ <- p.onAgentEvent("s1", Json.obj("type" -> "done".asJson))
      calls <- rec.calls.get
    yield assertEquals(calls, Nil, "stop drains the buffer; the post-stop done flushes nothing")
  }

  test("WO-F10 toolEnd is a stated no-op arm (text-only v1): a tool result never triggers a send") {
    val rec = RecordingSend(okResult)
    val p = plugin(tmpRoot(), rec.factory)
    for
      _ <- p.start(recordingCtx(List(meta("s1", Some("wx_alice")))))
      _ <- p.onAgentEvent("s1", Json.obj("type" -> "toolEnd".asJson, "content" -> "___POP_JSON___{}".asJson,
        "isError" -> false.asJson))
      _ <- p.onAgentEvent("s1", Json.obj("type" -> "done".asJson))
      calls <- rec.calls.get
    yield assertEquals(calls, Nil)
  }

  // ───────────────────────── wire shape (pure) ─────────────────────────

  test("WO-W1 the /send request body carries exactly to+text (the side-car owns the rest)") {
    val j = WeixinOutboundAdapter.buildSendJson("wx_alice", "hello 世界")
    assertEquals(j.asObject.map(_.keys.toList.sorted), Some(List("text", "to")))
    assertEquals(j.hcursor.downField("to").as[String].toOption, Some("wx_alice"))
    assertEquals(j.hcursor.downField("text").as[String].toOption, Some("hello 世界"))
  }

  test("WO-W2 parseSendResponse maps the side-car verdicts onto the vocabulary") {
    val ok200 = WeixinOutboundAdapter.parseSendResponse(200,
      """{"ok":true,"delivered":true,"message_id":"421001","client_id":"nebflow-sidecar:1-aa","attempts":1,"context_threaded":false}""")
    assertEquals(ok200, WeixinOutboundAdapter.SendResult(true, true, Some("421001"), 200, "delivered"))

    val bad200 = WeixinOutboundAdapter.parseSendResponse(200, """{"ok":true,"delivered":false}""")
    assertEquals(bad200.ok, false, "a 200 without delivered=true is NOT a success reading")

    val unauthorized = WeixinOutboundAdapter.parseSendResponse(401, """{"error":"unauthorized"}""")
    assertEquals(unauthorized.reason, "shim_auth_rejected")

    val unconfigured = WeixinOutboundAdapter.parseSendResponse(503, """{"error":"shim_token_unconfigured"}""")
    assertEquals(unconfigured.reason, "shim_token_unconfigured")

    val refused = WeixinOutboundAdapter.parseSendResponse(502,
      """{"error":"delivery_failed: sendmessage ret=1 errmsg=bad"}""")
    assertEquals(refused.ok, false)
    assert(refused.reason.startsWith("delivery_failed:"), refused.reason)

    val unparseable = WeixinOutboundAdapter.parseSendResponse(502, "not json at all")
    assert(unparseable.reason.startsWith("delivery_failed:"), unparseable.reason)
  }

end WeixinOutboundSpec
