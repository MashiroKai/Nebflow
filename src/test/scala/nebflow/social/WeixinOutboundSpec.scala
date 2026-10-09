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
import scala.concurrent.duration.DurationInt

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
  private final class RecordingSend(
    result: WeixinOutboundAdapter.SendResult,
    /** pop multi-group batch: a SHARED ordered wire log across both seams. */
    wire: Option[Ref[IO, List[String]]] = None
  ):
    val calls: Ref[IO, List[(String, String)]] = Ref.unsafe[IO, List[(String, String)]](Nil)
    val sendFn: WeixinOutboundAdapter.Send = (to, text) =>
      calls
        .update(_ :+ ((to, text)))
        .flatMap(_ => wire.fold(IO.unit)(w => w.update(_ :+ s"text:$text")))
        .as(result)
    val factory: os.Path => WeixinIlinkBridgePlugin.Send = _ => sendFn

  /**
   * The recording MEDIA seam (weixin-media batch): captures (to, media) pairs.
   * `failOn` lets one test fail a SLICE of the batch (pop batch-media fix)
   * without touching the other items. `raiseOn` (pop multi-group batch) makes
   * the seam RAISE for a slice — the unexpected-fault face the per-item raise
   * containment guards.
   */
  private final class RecordingMedia(
    result: WeixinOutboundAdapter.SendResult,
    failOn: WeixinOutboundAdapter.WeixinMedia => Boolean = _ => false,
    raiseOn: WeixinOutboundAdapter.WeixinMedia => Boolean = _ => false,
    wire: Option[Ref[IO, List[String]]] = None
  ):

    private val failResult =
      WeixinOutboundAdapter.SendResult(false, false, None, 502, "delivery_failed: cdn http_500")

    val calls: Ref[IO, List[(String, WeixinOutboundAdapter.WeixinMedia)]] =
      Ref.unsafe[IO, List[(String, WeixinOutboundAdapter.WeixinMedia)]](Nil)

    private def note(m: WeixinOutboundAdapter.WeixinMedia): IO[Unit] =
      wire.fold(IO.unit)(w =>
        w.update(_ :+ s"media:${m.imagePath.orElse(m.filePath).getOrElse("?")}")
      )

    val sendFn: WeixinIlinkBridgePlugin.SendMedia = (to, media) =>
      if raiseOn(media) then
        note(media).flatMap(_ => IO.raiseError[WeixinOutboundAdapter.SendResult](new java.io.IOException("simulated media fault")))
      else
        calls
          .update(_ :+ ((to, media)))
          .flatMap(_ => note(media))
          .as(if failOn(media) then failResult else result)
    val factory: os.Path => WeixinIlinkBridgePlugin.SendMedia = _ => sendFn

  private def okResult =
    WeixinOutboundAdapter.SendResult(true, true, Some("421001"), 200, "delivered")

  private def plugin(
    root: os.Path,
    send: os.Path => WeixinIlinkBridgePlugin.Send,
    sendMedia: os.Path => WeixinIlinkBridgePlugin.SendMedia = _ =>
      (_, _) => IO.pure(WeixinOutboundAdapter.SendResult(true, true, None, 200, "delivered")),
    webshot: nebflow.social.outbound.WebPageShot.Renderer = nebflow.social.outbound.WebPageShot.Unavailable
  ): WeixinIlinkBridgePlugin =
    // webshot defaults to Unavailable: no test ever starts node (offline discipline);
    // the production default is WebPageShot.NodePlaywright.
    new WeixinIlinkBridgePlugin(
      root,
      pinnedAllowedIlinkUserIds = None,
      send = send,
      sendMedia = sendMedia,
      webshot = webshot
    )

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
    def injectMessage(
      sessionId: String,
      content: String,
      senderId: Option[String],
      origin: Option[nebflow.bridge.BridgeOrigin] = None
    ): IO[Unit] = IO.unit
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
    end for
  }

  test("WO-K2 a channel-less root gains NO token file from sendLive (the leg stays closed, spelled out)") {
    val root = tmpRoot()
    for r <- WeixinOutboundAdapter.sendLive(root)("wx_alice", "hello")
    yield
      assertEquals(r.ok, false)
      assert(r.reason.startsWith("shim_token_unconfigured"), r.reason)
      assert(
        !Files.exists(WeixinOutboundAdapter.tokenPath(root).toNIO),
        "the send face never mints on its own authority — minting happens only in the plugin start path"
      )
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
      _ <- p.onAgentEvent(
        "s1",
        Json.obj("type" -> "textDelta".asJson, "delta" -> "**bold** and a [link](https://example.com)".asJson)
      )
      _ <- p.onAgentEvent("s1", Json.obj("type" -> "done".asJson))
      calls <- rec.calls.get
    yield
      val sent = calls.headOption.map(_._2).getOrElse(fail("one send expected"))
      assert(
        !sent.contains("**") && !sent.contains("](https://"),
        s"markdown syntax must not reach the wire, got: $sent"
      )
      assert(
        sent.contains("bold") && sent.contains("https://example.com"),
        s"the content itself must survive the de-markup, got: $sent"
      )
    end for
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
    val failing =
      RecordingSend(WeixinOutboundAdapter.SendResult(false, false, None, 502, "delivery_failed: sendmessage ret=1"))
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

  test("WO-F10 a toolEnd alone never sends: a non-product payload leaves no memory either (text seam stays silent)") {
    val rec = RecordingSend(okResult)
    val media = RecordingMedia(okResult)
    val p = plugin(tmpRoot(), rec.factory, media.factory)
    for
      _ <- p.start(recordingCtx(List(meta("s1", Some("wx_alice")))))
      // "___POP_JSON___{}" parses but carries NO items — not a rich product at
      // all (weixin-media batch: the parser is the channel-agnostic
      // [[nebflow.social.outbound.RichProduct]]; a video-only payload reads the same).
      _ <- p.onAgentEvent(
        "s1",
        Json.obj("type" -> "toolEnd".asJson, "content" -> "___POP_JSON___{}".asJson, "isError" -> false.asJson)
      )
      _ <- p.onAgentEvent("s1", Json.obj("type" -> "done".asJson))
      calls <- rec.calls.get
      mediaCalls <- media.calls.get
      rich <- p.richRef.get
    yield
      assertEquals(calls, Nil, "the TEXT seam never fires from a tool result")
      assertEquals(mediaCalls, Nil, "no media seam call without a carriable product")
      assertEquals(rich.get("s1"), None, "a non-product payload leaves no memory behind")
    end for
  }

  // ─────────────── 逐段即时推送批（2026-10-10）：出站段级断言 ───────────────
  //
  // 作者需求（2026-10-09 原话）：一条消息就对应一条渠道消息，一条一条即时推
  // 送，而不是等一起了才推送。分段提交源 = 桌面端一条气泡的落点（SessionRecorder
  // 在 `toolStart` | `roundComplete` | `done` 帧 flush textBuf）——桥接腿接同一源。

  private def wxDelta(sessionId: String, text: String): Json =
    Json.obj("type" -> "textDelta".asJson, "sessionId" -> sessionId.asJson, "delta" -> text.asJson)

  private val wxToolStart = Json.obj("type" -> "toolStart".asJson)
  private val wxRoundComplete = Json.obj("type" -> "roundComplete".asJson)

  test("WO-SG1 three committed segments → THREE in-order immediate sends; done resends nothing") {
    val rec = RecordingSend(okResult)
    val p = plugin(tmpRoot(), rec.factory)
    for
      _ <- p.start(recordingCtx(List(meta("s1", Some("wx_alice")))))
      _ <- p.onAgentEvent("s1", wxDelta("s1", "第一段"))
      _ <- p.onAgentEvent("s1", wxDelta("s1", "①"))
      _ <- p.onAgentEvent("s1", wxToolStart) // 分段提交边界 1：第一段即推
      _ <- p.onAgentEvent("s1", wxDelta("s1", "第二段"))
      _ <- p.onAgentEvent("s1", wxRoundComplete) // 分段提交边界 2：第二段即推
      _ <- p.onAgentEvent("s1", wxDelta("s1", "第三段"))
      _ <- p.onAgentEvent("s1", Json.obj("type" -> "done".asJson)) // 只补发尾段
      calls <- rec.calls.get
      after <- p.pendingRef.get
    yield
      assertEquals(
        calls,
        List(("wx_alice", "第一段①"), ("wx_alice", "第二段"), ("wx_alice", "第三段")),
        "each committed segment is ONE immediate message, in order; the old whole-turn aggregate is gone"
      )
      assertEquals(after.get("s1"), None, "done drains only the tail segment — no whole-turn resend")
    end for
  }

  test("WO-SG2 the throttle paces adjacent segments: send N starts ≥1s after send N-1 started") {
    val stamps = Ref.unsafe[IO, List[Long]](Nil)
    val p = plugin(tmpRoot(), _ =>
      (_, _) => IO.monotonic.flatMap(d => stamps.update(_ :+ d.toMillis)).as(okResult))
    for
      _ <- p.start(recordingCtx(List(meta("s1", Some("wx_alice")))))
      _ <- p.onAgentEvent("s1", wxDelta("s1", "seg one"))
      _ <- p.onAgentEvent("s1", wxToolStart)
      _ <- p.onAgentEvent("s1", wxDelta("s1", "seg two"))
      _ <- p.onAgentEvent("s1", Json.obj("type" -> "done".asJson))
      ss <- stamps.get
    yield
      assertEquals(ss.size, 2, "two segments → two paced sends")
      assert(
        ss(1) - ss(0) >= 950L,
        s"the second send must start ≥1s (start-to-start) after the first, got ${ss(1) - ss(0)}ms"
      )
    end for
  }

  test("WO-SG3 an empty boundary commits nothing: a pure-tool stretch pushes no message") {
    val rec = RecordingSend(okResult)
    val p = plugin(tmpRoot(), rec.factory)
    for
      _ <- p.start(recordingCtx(List(meta("s1", Some("wx_alice")))))
      _ <- p.onAgentEvent("s1", wxDelta("s1", "第一段"))
      _ <- p.onAgentEvent("s1", wxToolStart)
      _ <- p.onAgentEvent("s1", wxToolStart) // 空缓冲：纯工具过程不推
      _ <- p.onAgentEvent("s1", wxRoundComplete) // 仍空：不推
      _ <- p.onAgentEvent("s1", wxDelta("s1", "尾段"))
      _ <- p.onAgentEvent("s1", Json.obj("type" -> "done".asJson))
      calls <- rec.calls.get
    yield
      assertEquals(
        calls,
        List(("wx_alice", "第一段"), ("wx_alice", "尾段")),
        "empty boundaries are silent; only committed text and the tail reach the wire"
      )
    end for
  }

  test("WO-SG4 interrupted after a committed boundary: the pushed segment stays, the torn tail never flushes") {
    val rec = RecordingSend(okResult)
    val p = plugin(tmpRoot(), rec.factory)
    for
      _ <- p.start(recordingCtx(List(meta("s1", Some("wx_alice")))))
      _ <- p.onAgentEvent("s1", wxDelta("s1", "已推段"))
      _ <- p.onAgentEvent("s1", wxToolStart)
      _ <- p.onAgentEvent("s1", wxDelta("s1", "torn tail"))
      _ <- p.onAgentEvent("s1", Json.obj("type" -> "interrupted".asJson))
      _ <- p.onAgentEvent("s1", Json.obj("type" -> "done".asJson))
      // 段推送骑 .start 后台 fiber（awaitCalls 的文档即此险）：轮终 await 盖不
      // 住它，直读有负载竞态——等它落账再断言（pop multi-group 批顺手收紧）。
      calls <- awaitCalls(rec.calls, 1).timeout(10.seconds)
    yield
      assertEquals(
        calls,
        List(("wx_alice", "已推段")),
        "a committed segment is already delivered (cannot be unsent); the torn tail flushes nothing"
      )
    end for
  }

  /** Bounded wait for the recorder to observe `count` calls — the failure note
    * rides a background fiber (`.start`), which done's await cannot cover. */
  private def awaitCalls(
    ref: Ref[IO, List[(String, String)]],
    count: Int
  ): IO[List[(String, String)]] =
    IO.sleep(50.millis) *> ref.get.flatMap { l =>
      if l.size >= count then IO.pure(l) else awaitCalls(ref, count)
    }

  test("WO-SG5 a failed segment is noted, exactly once per failure run; the no-retry boundary stays intact") {
    val failing =
      RecordingSend(WeixinOutboundAdapter.SendResult(false, false, None, 502, "delivery_failed: sendmessage ret=-2"))
    val p = plugin(tmpRoot(), failing.factory)
    for
      _ <- p.start(recordingCtx(List(meta("s1", Some("wx_alice")))))
      _ <- p.onAgentEvent("s1", wxDelta("s1", "段一"))
      _ <- p.onAgentEvent("s1", wxToolStart) // 段一失败 → 注明 #1
      _ <- p.onAgentEvent("s1", wxDelta("s1", "段二"))
      _ <- p.onAgentEvent("s1", wxRoundComplete) // 段二失败 → 连续失败只注明一次
      _ <- p.onAgentEvent("s1", wxDelta("s1", "尾"))
      _ <- p.onAgentEvent("s1", Json.obj("type" -> "done".asJson)) // 尾失败：轮终腿保持既有仅 WARN
      calls <- awaitCalls(failing.calls, 4).timeout(10.seconds)
    yield
      // 注明恰好一条；失败段本身各恰好一次（不补发——重发边界不变）。注明从
      // 失败段的后台 runner 里注册，相对其后注册段的插队位置不保证（失败检测
      // 是异步的；生产段间隔秒级，实际总在下一段之前）——但它必在其主题（段一）
      // 已尝试之后。
      assertEquals(calls.size, 4, "two failed segments + tail + exactly ONE note")
      assertEquals(calls.count(_._2.contains("上一条推送失败")), 1, "contiguous failures note ONCE")
      assertEquals(calls.count(_._2 == "段一"), 1, "the failed segment is never re-sent")
      assertEquals(calls.count(_._2 == "段二"), 1, "the failed segment is never re-sent")
      assertEquals(calls.count(_._2 == "尾"), 1, "the failed segment is never re-sent")
      assert(
        calls.indexWhere(_._2.contains("上一条推送失败")) > calls.indexWhere(_._2 == "段一"),
        s"the note must follow its subject's attempt: $calls"
      )
    end for
  }

  // ───────────────────────── wire shape (pure) ─────────────────────────

  test("WO-W1 the /send request body, default form: exactly to+text, NO account key (the side-car owns selection)") {
    val j = WeixinOutboundAdapter.buildSendJson("wx_alice", "hello 世界")
    assertEquals(
      j.asObject.map(_.keys.toList.sorted),
      Some(List("text", "to")),
      "no hint ⇒ the account key is ABSENT, never \"account\": null"
    )
    assertEquals(j.hcursor.downField("to").as[String].toOption, Some("wx_alice"))
    assertEquals(j.hcursor.downField("text").as[String].toOption, Some("hello 世界"))
  }

  test("WO-W2 parseSendResponse maps the side-car verdicts onto the vocabulary") {
    val ok200 = WeixinOutboundAdapter.parseSendResponse(
      200,
      """{"ok":true,"delivered":true,"message_id":"421001","client_id":"nebflow-sidecar:1-aa","attempts":1,"context_threaded":false}"""
    )
    assertEquals(ok200, WeixinOutboundAdapter.SendResult(true, true, Some("421001"), 200, "delivered"))

    val bad200 = WeixinOutboundAdapter.parseSendResponse(200, """{"ok":true,"delivered":false}""")
    assertEquals(bad200.ok, false, "a 200 without delivered=true is NOT a success reading")

    val unauthorized = WeixinOutboundAdapter.parseSendResponse(401, """{"error":"unauthorized"}""")
    assertEquals(unauthorized.reason, "shim_auth_rejected")

    val unconfigured = WeixinOutboundAdapter.parseSendResponse(503, """{"error":"shim_token_unconfigured"}""")
    assertEquals(unconfigured.reason, "shim_token_unconfigured")

    val refused =
      WeixinOutboundAdapter.parseSendResponse(502, """{"error":"delivery_failed: sendmessage ret=1 errmsg=bad"}""")
    assertEquals(refused.ok, false)
    assert(refused.reason.startsWith("delivery_failed:"), refused.reason)

    val unparseable = WeixinOutboundAdapter.parseSendResponse(502, "not json at all")
    assert(unparseable.reason.startsWith("delivery_failed:"), unparseable.reason)
  }

  test("WO-W3 an account hint rides along VERBATIM: keys are exactly account+text+to, the raw bot id untouched") {
    val j = WeixinOutboundAdapter.buildSendJson("wx_alice", "hello 世界", Some("58dcfa8119b9@im.bot"))
    assertEquals(j.asObject.map(_.keys.toList.sorted), Some(List("account", "text", "to")))
    assertEquals(
      j.hcursor.downField("account").as[String].toOption,
      Some("58dcfa8119b9@im.bot"),
      "the raw @im.bot form passes through untouched — normalization is the side-car's job"
    )
  }

  test("WO-W4 accountHint reads the card's ilink_bot_id: no card ⇒ None, a card ⇒ Some(raw), a blank value ⇒ None") {
    val root = tmpRoot()
    assertEquals(WeixinOutboundAdapter.accountHint(root), None, "no channel card ⇒ no hint")
    def saveFields(fieldsJson: String): Unit =
      SocialChannels.save(
        root,
        WeixinIlinkBridgePlugin.Name,
        io.circe.parser.parse(s"""{"fields":$fieldsJson}""").toOption.get
      ) match
        case Right(_) => ()
        case Left(err) => fail(s"save refused: $err")
    saveFields("""{"ilink_bot_id":"58dcfa8119b9@im.bot"}""")
    assertEquals(
      WeixinOutboundAdapter.accountHint(root),
      Some("58dcfa8119b9@im.bot"),
      "the stored raw bot id is surfaced verbatim (same cold-read face as sidecarBase)"
    )
    saveFields("""{"ilink_bot_id":"   "}""")
    assertEquals(WeixinOutboundAdapter.accountHint(root), None, "a blank hint is no hint — the key stays absent")
  }

  // ───────────── weixin-media batch: the media seam + rich flush ─────────────

  /** A Pop payload with the given items (PopTool.itemJson field names). */
  private def popPayload(items: Json*): String =
    nebflow.social.outbound.RichProduct.PopSentinel +
      Json.obj("items" -> Json.arr(items*)).noSpaces

  private def popItem(kind: String, p: os.Path): Json =
    Json.obj("kind" -> kind.asJson, "name" -> "artifact".asJson, "path" -> p.toString.asJson)

  private def toolEnd(content: String): Json =
    Json.obj(
      "type" -> "toolEnd".asJson,
      "content" -> content.asJson,
      "isError" -> false.asJson,
      "label" -> "Pop".asJson
    )

  private def doneEv(sessionId: String): Json = Json.obj("type" -> "done".asJson, "sessionId" -> sessionId.asJson)

  test(
    "WO-M1 buildMediaSendJson image: keys exactly to+image_path (+account under the same verbatim rule), no text key"
  ) {
    val j = WeixinOutboundAdapter.buildMediaSendJson("wx_alice", WeixinOutboundAdapter.WeixinMedia.image("/tmp/a.png"))
    assertEquals(j.asObject.map(_.keys.toList.sorted), Some(List("image_path", "to")))
    assertEquals(j.hcursor.downField("image_path").as[String].toOption, Some("/tmp/a.png"))
    assertEquals(j.hcursor.downField("text").as[String].toOption, None, "the media body carries NO text key")
    val withHint = WeixinOutboundAdapter.buildMediaSendJson(
      "wx_alice",
      WeixinOutboundAdapter.WeixinMedia.image("/tmp/a.png"),
      Some("58dcfa8119b9@im.bot")
    )
    assertEquals(withHint.asObject.map(_.keys.toList.sorted), Some(List("account", "image_path", "to")))
    assertEquals(withHint.hcursor.downField("account").as[String].toOption, Some("58dcfa8119b9@im.bot"))
  }

  test("WO-M2 buildMediaSendJson file: the file_path form under the same rules") {
    val j = WeixinOutboundAdapter.buildMediaSendJson("wx_bob", WeixinOutboundAdapter.WeixinMedia.file("/tmp/r.pdf"))
    assertEquals(j.asObject.map(_.keys.toList.sorted), Some(List("file_path", "to")))
    assertEquals(j.hcursor.downField("file_path").as[String].toOption, Some("/tmp/r.pdf"))
    assertEquals(j.hcursor.downField("image_path").as[String].toOption, None)
  }

  test("WO-M3 sendMediaLive with no token ⇒ the media leg stays closed (same vocabulary as the text seam)") {
    val root = tmpRoot()
    for r <- WeixinOutboundAdapter.sendMediaLive(root)(
        "wx_alice",
        WeixinOutboundAdapter.WeixinMedia.image("/tmp/a.png")
      )
    yield
      assertEquals(r.ok, false)
      assert(r.reason.startsWith("shim_token_unconfigured"), r.reason)
      assertEquals(r.code, -1)
  }

  test(
    "WO-R1 a Pop IMAGE product remembered on toolEnd flushes ONE media send on done (image_path); the text reply is intact"
  ) {
    val img = tmpRoot() / "pop.png"
    os.write(img, "png-bytes", createFolders = true)
    val rec = RecordingSend(okResult)
    val media = RecordingMedia(okResult)
    val p = plugin(tmpRoot(), rec.factory, media.factory)
    for
      _ <- p.start(recordingCtx(List(meta("s1", Some("wx_alice")))))
      _ <- p.onAgentEvent("s1", toolEnd(popPayload(popItem("image", img))))
      _ <- p.onAgentEvent("s1", Json.obj("type" -> "textDelta".asJson, "delta" -> "图好了".asJson))
      _ <- p.onAgentEvent("s1", doneEv("s1"))
      mediaCalls <- media.calls.get
      calls <- rec.calls.get
      rich <- p.richRef.get
    yield
      assertEquals(
        mediaCalls,
        List(("wx_alice", WeixinOutboundAdapter.WeixinMedia.image(img.toString))),
        "ONE media send, to the bound sender, the payload's resolved path"
      )
      assertEquals(calls, List(("wx_alice", "图好了")), "the text reply is never suppressed (additive rich leg)")
      assertEquals(rich.get("s1"), None, "the memory is drained by the flush")
    end for
  }

  test(
    "WO-R2 a Pop FILE item flushes the file_path form (pop batch-media fix: every item, payload order — no kind beats another)"
  ) {
    val doc = tmpRoot() / "report.pdf"
    os.write(doc, "pdf-bytes", createFolders = true)
    val media = RecordingMedia(okResult)
    val p = plugin(tmpRoot(), RecordingSend(okResult).factory, media.factory)
    for
      _ <- p.start(recordingCtx(List(meta("s1", Some("wx_alice")))))
      _ <- p.onAgentEvent("s1", toolEnd(popPayload(popItem("file", doc))))
      _ <- p.onAgentEvent("s1", doneEv("s1"))
      mediaCalls <- media.calls.get
    yield assertEquals(mediaCalls, List(("wx_alice", WeixinOutboundAdapter.WeixinMedia.file(doc.toString))))
  }

  test("WO-R3 a FAILED media send degrades to a text notice (保底) BEFORE the turn text; nothing raises") {
    val img = tmpRoot() / "pop.png"
    os.write(img, "png-bytes", createFolders = true)
    val rec = RecordingSend(okResult)
    val media =
      RecordingMedia(WeixinOutboundAdapter.SendResult(false, false, None, 502, "delivery_failed: cdn http_500"))
    val p = plugin(tmpRoot(), rec.factory, media.factory)
    for
      _ <- p.start(recordingCtx(List(meta("s1", Some("wx_alice")))))
      _ <- p.onAgentEvent("s1", toolEnd(popPayload(popItem("image", img))))
      _ <- p.onAgentEvent("s1", Json.obj("type" -> "textDelta".asJson, "delta" -> "正文".asJson))
      _ <- p.onAgentEvent("s1", doneEv("s1"))
      mediaCalls <- media.calls.get
      calls <- rec.calls.get
    yield
      assertEquals(mediaCalls.size, 1, "the media attempt WAS made")
      assertEquals(calls.size, 2, "the degrade notice AND the turn text both go out")
      assert(calls.head._2.startsWith("[图片发送失败 code=502]"), s"the notice names the failure: ${calls.head._2}")
      assertEquals(calls(1)._2, "正文", "the turn text is never suppressed by a media failure")
  }

  test("WO-R4 an interrupted turn drops the remembered product (a torn turn is never a media reply)") {
    val img = tmpRoot() / "pop.png"
    os.write(img, "png-bytes", createFolders = true)
    val media = RecordingMedia(okResult)
    val p = plugin(tmpRoot(), RecordingSend(okResult).factory, media.factory)
    for
      _ <- p.start(recordingCtx(List(meta("s1", Some("wx_alice")))))
      _ <- p.onAgentEvent("s1", toolEnd(popPayload(popItem("image", img))))
      _ <- p.onAgentEvent("s1", Json.obj("type" -> "interrupted".asJson))
      _ <- p.onAgentEvent("s1", doneEv("s1"))
      mediaCalls <- media.calls.get
      rich <- p.richRef.get
    yield
      assertEquals(mediaCalls, Nil)
      assertEquals(rich.get("s1"), None)
  }

  test("WO-R5 an errored tool result is never a rich product (isError gate)") {
    val img = tmpRoot() / "pop.png"
    os.write(img, "png-bytes", createFolders = true)
    val media = RecordingMedia(okResult)
    val p = plugin(tmpRoot(), RecordingSend(okResult).factory, media.factory)
    for
      _ <- p.start(recordingCtx(List(meta("s1", Some("wx_alice")))))
      _ <- p.onAgentEvent(
        "s1",
        Json.obj(
          "type" -> "toolEnd".asJson,
          "content" -> popPayload(popItem("image", img)).asJson,
          "isError" -> true.asJson
        )
      )
      _ <- p.onAgentEvent("s1", doneEv("s1"))
      mediaCalls <- media.calls.get
      rich <- p.richRef.get
    yield
      assertEquals(mediaCalls, Nil, "an errored tool's content is an error string, never an artifact")
      assertEquals(rich.get("s1"), None)
    end for
  }

  test("WO-R6 a CARD product is the stated v1 boundary: no media send, the text leg carries the turn") {
    val rec = RecordingSend(okResult)
    val media = RecordingMedia(okResult)
    val p = plugin(tmpRoot(), rec.factory, media.factory)
    val card = nebflow.social.outbound.RichKind.CardSentinel + """{"html":"<h1>card</h1>"}"""
    for
      _ <- p.start(recordingCtx(List(meta("s1", Some("wx_alice")))))
      _ <- p.onAgentEvent("s1", toolEnd(card))
      _ <- p.onAgentEvent("s1", Json.obj("type" -> "textDelta".asJson, "delta" -> "卡片说明".asJson))
      _ <- p.onAgentEvent("s1", doneEv("s1"))
      mediaCalls <- media.calls.get
      calls <- rec.calls.get
    yield
      assertEquals(mediaCalls, Nil, "a card payload is not transcodable to the weixin media items")
      assertEquals(calls, List(("wx_alice", "卡片说明")))
  }

  test("WO-R7 a NON-EXISTENT image path classifies Unknown: no media send (fail-closed, no garbage upload)") {
    val gone = tmpRoot() / "gone.png"
    val media = RecordingMedia(okResult)
    val p = plugin(tmpRoot(), RecordingSend(okResult).factory, media.factory)
    for
      _ <- p.start(recordingCtx(List(meta("s1", Some("wx_alice")))))
      _ <- p.onAgentEvent("s1", toolEnd(popPayload(popItem("image", gone))))
      _ <- p.onAgentEvent("s1", doneEv("s1"))
      mediaCalls <- media.calls.get
    yield assertEquals(mediaCalls, Nil, "RichKind.of(exists=false) ⇒ Unknown ⇒ the v1 boundary log, no send")
  }

  // ───── social-webshot batch: html 出站（2026-10-09 裁定：文件直发、截图兜底）─────

  /**
   * A recording shot seam: never starts node; counts invocations and pins the
   * input, so a test can prove the file leg succeeds WITHOUT consulting it.
   */
  private final class RecordingShot(result: Either[String, os.Path])
      extends nebflow.social.outbound.WebPageShot.Renderer:
    val calls: Ref[IO, Int] = Ref.unsafe[IO, Int](0)
    val lastInput: Ref[IO, String] = Ref.unsafe[IO, String]("")
    def available: Boolean = true
    def shot(htmlFile: os.Path, outPng: os.Path): IO[Either[String, os.Path]] =
      calls.update(_ + 1) *> lastInput.set(htmlFile.toString).as(result)

  test("WO-W1 an html product rides the file_path leg AS THE ORIGINAL; the shot seam is never consulted") {
    val html = tmpRoot() / "diagram.html"
    os.write(html, "<html><body>diagram</body></html>", createFolders = true)
    val media = RecordingMedia(okResult)
    val shot = RecordingShot(Right(tmpRoot() / "never.png"))
    val p = plugin(tmpRoot(), RecordingSend(okResult).factory, media.factory, webshot = shot)
    for
      _ <- p.start(recordingCtx(List(meta("s1", Some("wx_alice")))))
      _ <- p.onAgentEvent("s1", toolEnd(popPayload(popItem("file", html))))
      _ <- p.onAgentEvent("s1", doneEv("s1"))
      mediaCalls <- media.calls.get
      shotCalls <- shot.calls.get
    yield
      assertEquals(
        mediaCalls.map(_._2),
        List(WeixinOutboundAdapter.WeixinMedia.file(html.toString)),
        "the ORIGINAL html rides file_path directly (2026-10-09 ruling) — no auto screenshot"
      )
      assertEquals(shotCalls, 0, "文件腿成功 ⇒ 零截图调用（截图只是兜底）")
  }

  test("WO-W2 a FAILED file send (channel refuses) falls back to the full-page shot on image_path") {
    val html = tmpRoot() / "diagram.html"
    os.write(html, "<html><body>diagram</body></html>", createFolders = true)
    val png = tmpRoot() / "page-shot.png"
    os.write(png, "png-bytes", createFolders = true)
    // The file send is refused (failOn = anything carrying file_path); the image
    // send after the shot succeeds.
    val media = RecordingMedia(okResult, failOn = _.filePath.nonEmpty)
    val shot = RecordingShot(Right(png))
    val p = plugin(tmpRoot(), RecordingSend(okResult).factory, media.factory, webshot = shot)
    for
      _ <- p.start(recordingCtx(List(meta("s1", Some("wx_alice")))))
      _ <- p.onAgentEvent("s1", toolEnd(popPayload(popItem("file", html))))
      _ <- p.onAgentEvent("s1", doneEv("s1"))
      mediaCalls <- media.calls.get
      shotCalls <- shot.calls.get
    yield
      assertEquals(shotCalls, 1, "the shot is consulted only after the file leg failed")
      assertEquals(
        mediaCalls.map(_._2),
        List(
          WeixinOutboundAdapter.WeixinMedia.file(html.toString),
          WeixinOutboundAdapter.WeixinMedia.image(png.toString)
        ),
        "file first (attempted), then the shot product on image_path — in that order"
      )
  }

  test("WO-W2b a failed file send AND an unavailable shot keep the v1 boundary: no image send, the text reply carries the turn") {
    val html = tmpRoot() / "diagram.html"
    os.write(html, "<html><body>diagram</body></html>", createFolders = true)
    val rec = RecordingSend(okResult)
    val media = RecordingMedia(okResult, failOn = _.filePath.nonEmpty)
    val p = plugin(
      tmpRoot(),
      rec.factory,
      media.factory,
      webshot = nebflow.social.outbound.WebPageShot.Unavailable
    )
    for
      _ <- p.start(recordingCtx(List(meta("s1", Some("wx_alice")))))
      _ <- p.onAgentEvent("s1", toolEnd(popPayload(popItem("file", html))))
      _ <- p.onAgentEvent("s1", Json.obj("type" -> "textDelta".asJson, "delta" -> "框图在这".asJson))
      _ <- p.onAgentEvent("s1", doneEv("s1"))
      mediaCalls <- media.calls.get
      calls <- rec.calls.get
    yield
      assertEquals(
        mediaCalls.map(_._2),
        List(WeixinOutboundAdapter.WeixinMedia.file(html.toString)),
        "the file leg was attempted; no image send without a shot product"
      )
      assertEquals(calls, List(("wx_alice", "框图在这")), "b 层在微信侧 = 回合正文承担，绝不吞回合")
  }

  // ───── pop batch-media fix: ONE Pop of N artifacts is N media sends ─────

  test("WO-B1 an 11-image Pop carries ALL of them, in payload order (the old single-pick read dropped ten)") {
    val dir = tmpRoot()
    val imgs = (1 to 11)
      .map(i =>
        val p = dir / s"p$i.png"; os.write(p, s"png-$i", createFolders = true); p
      )
      .toList
    val rec = RecordingSend(okResult)
    val media = RecordingMedia(okResult)
    val p = plugin(tmpRoot(), rec.factory, media.factory)
    for
      _ <- p.start(recordingCtx(List(meta("s1", Some("wx_alice")))))
      _ <- p.onAgentEvent("s1", toolEnd(popPayload(imgs.map(i => popItem("image", i))*)))
      _ <- p.onAgentEvent("s1", Json.obj("type" -> "textDelta".asJson, "delta" -> "十一张都好了".asJson))
      _ <- p.onAgentEvent("s1", doneEv("s1"))
      mediaCalls <- media.calls.get
      calls <- rec.calls.get
    yield
      assertEquals(mediaCalls.map(_._1), List.fill(11)("wx_alice"), "every item goes to the bound sender")
      assertEquals(
        mediaCalls.map(_._2),
        imgs.map(i => WeixinOutboundAdapter.WeixinMedia.image(i.toString)),
        "ALL 11 items, in payload order, each its own image_path send"
      )
      assertEquals(calls, List(("wx_alice", "十一张都好了")), "the text reply follows the batch, never suppressed")
    end for
  }

  test("WO-B2 a MIXED image+file Pop keeps the payload order across kinds (no image-first regrouping)") {
    val dir = tmpRoot()
    def img(n: String) =
      val p = dir / n; os.write(p, "png", createFolders = true); p
    def doc(n: String) =
      val p = dir / n; os.write(p, "pdf", createFolders = true); p
    val a = img("a.png"); val b = doc("b.pdf"); val c = img("c.png"); val d = doc("d.pdf")
    val media = RecordingMedia(okResult)
    val p = plugin(tmpRoot(), RecordingSend(okResult).factory, media.factory)
    for
      _ <- p.start(recordingCtx(List(meta("s1", Some("wx_alice")))))
      _ <- p.onAgentEvent(
        "s1",
        toolEnd(popPayload(popItem("image", a), popItem("file", b), popItem("image", c), popItem("file", d)))
      )
      _ <- p.onAgentEvent("s1", doneEv("s1"))
      mediaCalls <- media.calls.get
    yield assertEquals(
      mediaCalls.map(_._2),
      List(
        WeixinOutboundAdapter.WeixinMedia.image(a.toString),
        WeixinOutboundAdapter.WeixinMedia.file(b.toString),
        WeixinOutboundAdapter.WeixinMedia.image(c.toString),
        WeixinOutboundAdapter.WeixinMedia.file(d.toString)
      ),
      "interleaved image/file items deliver exactly as the payload listed them"
    )
    end for
  }

  test("WO-B3 a single failed send degrades on its own and does NOT swallow the batch") {
    val dir = tmpRoot()
    def img(n: String) =
      val p = dir / n; os.write(p, "png", createFolders = true); p
    val i1 = img("i1.png"); val i2 = img("i2.png"); val i3 = img("i3.png")
    val rec = RecordingSend(okResult)
    val media = RecordingMedia(okResult, failOn = _.imagePath.contains(i2.toString))
    val p = plugin(tmpRoot(), rec.factory, media.factory)
    for
      _ <- p.start(recordingCtx(List(meta("s1", Some("wx_alice")))))
      _ <- p.onAgentEvent("s1", toolEnd(popPayload(popItem("image", i1), popItem("image", i2), popItem("image", i3))))
      _ <- p.onAgentEvent("s1", Json.obj("type" -> "textDelta".asJson, "delta" -> "正文".asJson))
      _ <- p.onAgentEvent("s1", doneEv("s1"))
      mediaCalls <- media.calls.get
      calls <- rec.calls.get
    yield
      assertEquals(
        mediaCalls.map(_._2),
        List(
          WeixinOutboundAdapter.WeixinMedia.image(i1.toString),
          WeixinOutboundAdapter.WeixinMedia.image(i2.toString),
          WeixinOutboundAdapter.WeixinMedia.image(i3.toString)
        ),
        "the loop attempts EVERY item — the failure does not stop it"
      )
      assertEquals(calls.size, 2, "exactly ONE degrade notice (for i2) plus the turn text")
      assert(calls.head._2.startsWith("[图片发送失败 code=502]"), s"the notice names the failure: ${calls.head._2}")
      assertEquals(calls(1)._2, "正文", "the turn text still closes the turn after the batch")
    end for
  }

  // ───────────── pop multi-group fix (2026-10-09): 每张组卡都送达 ─────────────
  //
  // Incident 2026-10-09 22:2x: one turn popped a 4-image group and then a
  // single image; this leg attempted exactly ONE media send (the LAST group's)
  // — the old single-slot richRef let a later Pop silently replace an earlier
  // group. The batch pins: every 组卡 of the turn is delivered, group order =
  // toolEnd order, items in payload order; media before the turn text; one
  // item's fault never swallows the rest of the turn.

  test("WO-B4 TWO Pop 组卡 in one turn → BOTH delivered, in toolEnd order (the old overwrite lost the first group)") {
    val dir = tmpRoot()
    def img(n: String) =
      val p = dir / n; os.write(p, "png", createFolders = true); p
    val a = img("a.png"); val b = img("b.png"); val c = img("c.png")
    val rec = RecordingSend(okResult)
    val media = RecordingMedia(okResult)
    val p = plugin(tmpRoot(), rec.factory, media.factory)
    for
      _ <- p.start(recordingCtx(List(meta("s1", Some("wx_alice")))))
      // Group one: the multi-image stacked card; group two: a later single Pop.
      _ <- p.onAgentEvent("s1", toolEnd(popPayload(popItem("image", a), popItem("image", b))))
      _ <- p.onAgentEvent("s1", toolEnd(popPayload(popItem("image", c))))
      _ <- p.onAgentEvent("s1", doneEv("s1"))
      mediaCalls <- media.calls.get
      rich <- p.richRef.get
    yield
      assertEquals(
        mediaCalls.map(_._2),
        List(
          WeixinOutboundAdapter.WeixinMedia.image(a.toString),
          WeixinOutboundAdapter.WeixinMedia.image(b.toString),
          WeixinOutboundAdapter.WeixinMedia.image(c.toString)
        ),
        "both groups' items sent, group order = toolEnd order, items in payload order"
      )
      assertEquals(rich.get("s1"), None, "the memory is drained by the flush")
    end for
  }

  test("WO-B5 mixed text + 组卡: media first, the turn text last (one pacer slot, 媒体先行、正文殿后)") {
    val dir = tmpRoot()
    def img(n: String) =
      val p = dir / n; os.write(p, "png", createFolders = true); p
    val a = img("a.png"); val b = img("b.png")
    val wire = Ref.unsafe[IO, List[String]](Nil)
    val rec = RecordingSend(okResult, Some(wire))
    val media = RecordingMedia(okResult, wire = Some(wire))
    val p = plugin(tmpRoot(), rec.factory, media.factory)
    for
      _ <- p.start(recordingCtx(List(meta("s1", Some("wx_alice")))))
      _ <- p.onAgentEvent("s1", Json.obj("type" -> "textDelta".asJson, "delta" -> "两张图如下".asJson))
      _ <- p.onAgentEvent("s1", toolEnd(popPayload(popItem("image", a), popItem("image", b))))
      _ <- p.onAgentEvent("s1", doneEv("s1"))
      order <- wire.get
    yield
      assertEquals(
        order,
        List(
          s"media:${a.toString}",
          s"media:${b.toString}",
          "text:两张图如下"
        ),
        "each image is its own media message, then the turn text — the wire order IS the delivery order"
      )
    end for
  }

  test("WO-B6 one item's unexpected RAISE degrades to its own [未送达] line; the remaining items and the text tail survive") {
    val dir = tmpRoot()
    def img(n: String) =
      val p = dir / n; os.write(p, "png", createFolders = true); p
    val a = img("a.png"); val bad = img("bad.png"); val c = img("c.png")
    val rec = RecordingSend(okResult)
    val media = RecordingMedia(okResult, raiseOn = _.imagePath.contains(bad.toString))
    val p = plugin(tmpRoot(), rec.factory, media.factory)
    for
      _ <- p.start(recordingCtx(List(meta("s1", Some("wx_alice")))))
      _ <- p.onAgentEvent("s1", Json.obj("type" -> "textDelta".asJson, "delta" -> "正文照发".asJson))
      _ <- p.onAgentEvent(
        "s1",
        toolEnd(popPayload(popItem("image", a), popItem("image", bad), popItem("image", c)))
      )
      _ <- p.onAgentEvent("s1", doneEv("s1"))
      mediaCalls <- media.calls.get
      calls <- rec.calls.get
    yield
      assertEquals(
        mediaCalls.map(_._2),
        List(
          WeixinOutboundAdapter.WeixinMedia.image(a.toString),
          WeixinOutboundAdapter.WeixinMedia.image(c.toString)
        ),
        "the raised item never reaches the media seam; its neighbours do"
      )
      assertEquals(calls.size, 2, "the [未送达] line AND the turn text both go out")
      assert(
        calls(0)._2.contains("[未送达] bad.png"),
        s"the faulted item is named visibly, BEFORE the turn text: ${calls(0)._2}"
      )
      assertEquals(calls(1)._2, "正文照发", "the turn text is never suppressed by an item fault")
    end for
  }

end WeixinOutboundSpec
