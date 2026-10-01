package nebflow.social

import cats.effect.IO
import cats.effect.Ref
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.bridge.BridgeContext
import nebflow.shared.SessionMeta

/** Offline regression spec for the Feishu bridge adapter (feishubridge batch,
  * 2026-09-25).
  *
  * Deliberately NO network and NO credential (same discipline as
  * [[FeishuChannelSpec]]): the outbound send is a constructor-injected recording
  * function, the allowlist is pinned through the constructor seam, and the SDK
  * receive thread is never started. What is pinned is every routing / gating /
  * accumulation decision the adapter makes; each guard below is
  * red-by-construction if someone removes it (the drop arms of [[FeishuBridgePlugin.intake]],
  * the buffer clearing on interrupted turns, the bound-session filter on the
  * outbound path).
  */
class FeishuBridgePluginSpec extends CatsEffectSuite:

  private def tmpRoot(): os.Path = os.temp.dir(prefix = "nb-feishu-bridge-")

  private def inbound(
      chatId: String,
      text: Option[String],
      sender: Option[String] = None,
      messageType: String = "text",
      contentRaw: String = "",
      mentions: List[FeishuMessage.InboundMention] = Nil,
      parentId: Option[String] = None,
      rootId: Option[String] = None
  ): FeishuMessage.Inbound =
    val raw = if contentRaw.nonEmpty then contentRaw
      else text.map(t => Json.obj("text" -> Json.fromString(t)).noSpaces).getOrElse("")
    FeishuMessage.inboundFrom(
      eventId = Some("evt_1"),
      messageId = Some("om_1"),
      chatId = Some(chatId),
      messageType = Some(messageType),
      contentRaw = Some(raw),
      createTime = None,
      senderId = sender,
      parentId = parentId,
      rootId = rootId,
      mentions = mentions
    ).toOption.getOrElse(throw new AssertionError(s"spec fixture could not build an Inbound for $chatId"))

  private def meta(id: String, chatId: Option[String]): SessionMeta =
    SessionMeta(
      id = id, name = id, createdAt = 0L, updatedAt = 0L, hasUnread = false,
      bridges = chatId.map(c => Map("feishu" -> Json.obj("chat_id" -> Json.fromString(c)))).getOrElse(Map.empty)
    )

  /** Recording BridgeContext: captures injections, serves a fixed session list,
    * and records updateBridgeConfig calls (the auto-bind persistence leg). */
  private final class RecordingCtx(sessions: Ref[IO, List[SessionMeta]]) extends BridgeContext:
    val injected: Ref[IO, List[(String, String, Option[String], Option[nebflow.bridge.BridgeOrigin])]] =
      Ref.unsafe[IO, List[(String, String, Option[String], Option[nebflow.bridge.BridgeOrigin])]](Nil)
    val bound: Ref[IO, List[(String, String, Option[Json])]] =
      Ref.unsafe[IO, List[(String, String, Option[Json])]](Nil)
    def injectMessage(sessionId: String, content: String, senderId: Option[String],
        origin: Option[nebflow.bridge.BridgeOrigin] = None): IO[Unit] =
      injected.update(_ :+ ((sessionId, content, senderId, origin)))
    def interruptAgent(sessionId: String): IO[Unit] = IO.unit
    def sessionMeta(sessionId: String): IO[Option[SessionMeta]] = IO.none
    def listSessions: IO[List[SessionMeta]] = sessions.get
    def updateBridgeConfig(sessionId: String, platform: String, config: Option[Json]): IO[Unit] =
      bound.update(_ :+ ((sessionId, platform, config)))

  private def newCtx(sessions: List[SessionMeta]): (RecordingCtx, Ref[IO, List[(String, String, Option[String], Option[nebflow.bridge.BridgeOrigin])]]) =
    val ctx = RecordingCtx(Ref.unsafe[IO, List[SessionMeta]](sessions))
    (ctx, ctx.injected)

  /** Recording outbound seam — the spec never touches the network. */
  private final class RecordingSend(ok: Boolean = true):
    val calls: Ref[IO, List[(String, String)]] = Ref.unsafe[IO, List[(String, String)]](Nil)
    val fn: FeishuBridgePlugin.Send = (appId, appSecret, region, receiveId, text) =>
      calls.update(_ :+ ((receiveId, text))).map(_ =>
        FeishuChannel.SendResult(ok, Some("om_out"), if ok then 0 else 230001,
          if ok then "ok" else "rejected", "2026-09-25T00:00:00Z", "chat_id"))

  private def plugin(send: FeishuBridgePlugin.Send,
      allowed: Option[List[String]] = None): FeishuBridgePlugin =
    new FeishuBridgePlugin(
      tmpRoot(), send = send, pinnedAllowedOpenIds = allowed,
      pinnedCreds = Some(FeishuCredentials.Credential("test-app", "test-secret", "spec")))

  private def delta(sessionId: String, text: String): Json =
    Json.obj("type" -> Json.fromString("textDelta"), "sessionId" -> Json.fromString(sessionId),
      "delta" -> Json.fromString(text))

  private def done(sessionId: String): Json =
    Json.obj("type" -> Json.fromString("done"), "sessionId" -> Json.fromString(sessionId))

  /** The (session, text, sender) triple of a recorded injection — the shape the
    * pre-source-marker assertions were written against. The fourth element
    * (the origin descriptor) is asserted separately where it matters. */
  private type Injection = (String, String, Option[String], Option[nebflow.bridge.BridgeOrigin])
  private def triples(rs: List[Injection]): List[(String, String, Option[String])] =
    rs.map { case (s, t, snd, _) => (s, t, snd) }

  /** The channel-agnostic origin this bridge announces for a given chat. */
  private def originFor(chatId: String): Option[nebflow.bridge.BridgeOrigin] =
    Some(nebflow.bridge.BridgeOrigin("feishu", FeishuBridgePlugin.ChannelDisplay, Some(chatId)))

  // ───────────────────────────── inbound routing ─────────────────────────────

  test("FB-R1 inbound text from a bound chat injects into the bound session") {
    val (ctx, injected) = newCtx(List(meta("s1", Some("oc_bound"))))
    val p = plugin(RecordingSend().fn)
    for
      _ <- p.rebuildRoutes(ctx)
      _ <- p.intake(ctx, inbound("oc_bound", Some("hello")))
      got <- injected.get
    yield assertEquals(triples(got), List(("s1", "hello", None)))
  }

  test("FB-R2 a chat with no bound session is dropped (no injection, no error)") {
    val (ctx, injected) = newCtx(List(meta("s1", Some("oc_other"))))
    val p = plugin(RecordingSend().fn)
    for
      _ <- p.rebuildRoutes(ctx)
      _ <- p.intake(ctx, inbound("oc_unknown", Some("hello")))
      got <- injected.get
    yield assertEquals(got, List.empty)
  }

  // 🔴 FB-R3 was REWRITTEN by the inbound-parse batch (2026-10-01), not merely
  // renumbered. Its old form asserted that an `image` message injects NOTHING —
  // which was exactly the defect that batch removes (design card §6.1: the
  // "非 text ⇒ 会话零注入" drop). Under the new contract a non-text message
  // injects the explicit unrecognised-type placeholder: still never a fabricated
  // text body, but no longer a silent drop. The rewrite reason is filed in the
  // batch report (I-5b).
  test("FB-R3 a non-text message is never fabricated into a text body — it injects the explicit placeholder") {
    val (ctx, injected) = newCtx(List(meta("s1", Some("oc_bound"))))
    val p = plugin(RecordingSend().fn)
    val image = inbound("oc_bound", None, messageType = "image", contentRaw = """{"image_key":"img_v2_abc"}""")
    for
      _ <- p.rebuildRoutes(ctx)
      _ <- p.intake(ctx, image)
      got <- injected.get
    yield
      assertEquals(got.map(_._1), List("s1"), "the message reaches the session instead of being dropped")
      val body = got.head._2
      assert(body.contains("[未识别的消息类型：image]"),
        s"the placeholder NAMES the type:\n$body")
      assert(body.contains("message_id=om_1"), s"the placeholder is traceable:\n$body")
      assert(body.contains("""{"image_key":"img_v2_abc"}"""), s"the raw content is preserved:\n$body")
      assert(body.contains("[/未识别的消息类型]"), s"the placeholder is closed:\n$body")
  }

  test("FB-R3c a quoted reply produces the quoted block AHEAD of the body, and announces its origin") {
    val (ctx, injected) = newCtx(List(meta("s1", Some("oc_bound"))))
    val p = plugin(RecordingSend().fn)
    val reply = inbound("oc_bound", Some("同意"), messageType = "text", parentId = Some("om_q"))
    for
      _ <- p.rebuildRoutes(ctx)
      _ <- p.intake(ctx, reply)
      got <- injected.get
    yield
      val body = got.head._2
      // The bridge composes the quoted段 + body; the source marker belongs to
      // the unified injection point (which is where the channel identity is
      // applied) and is therefore asserted there, not here.
      assert(body.startsWith("[引用的消息]"), s"the quoted block leads:\n$body")
      assert(body.contains("原文：未取得（message_id=om_q）"),
        s"an unfetched original NAMES the id it could not read (branch B):\n$body")
      val iQuote = body.indexOf("[/引用的消息]")
      val iBody = body.indexOf("同意")
      assert(iQuote < iBody, s"order must be quoted block → body:\n$body")
      // the origin the bridge announces is the channel-agnostic descriptor
      assertEquals(got.head._4, originFor("oc_bound"))
  }

  test("FB-R3c2 a non-reply message carries no quoted block at all") {
    val (ctx, injected) = newCtx(List(meta("s1", Some("oc_bound"))))
    val p = plugin(RecordingSend().fn)
    for
      _ <- p.rebuildRoutes(ctx)
      _ <- p.intake(ctx, inbound("oc_bound", Some("plain")))
      got <- injected.get
    yield
      assertEquals(got.head._2, "plain", "no reply ⇒ no quote block, and the body is untouched")
  }

  test("FB-R3d a post message injects the degraded rich text verbatim as the body") {
    val (ctx, injected) = newCtx(List(meta("s1", Some("oc_bound"))))
    val p = plugin(RecordingSend().fn)
    val postRaw = """{"title":"发布说明","content":[[{"tag":"text","text":"版本 "},{"tag":"a","text":"v2","href":"https://x/y"}],[{"tag":"at","user_id":"ou_1","user_name":"张三"},{"tag":"text","text":" 请看 "},{"tag":"img","image_key":"img_v2_k"}]]}"""
    val post = inbound("oc_bound", None, messageType = "post", contentRaw = postRaw)
    for
      _ <- p.rebuildRoutes(ctx)
      _ <- p.intake(ctx, post)
      got <- injected.get
    yield
      val body = got.head._2
      assert(body.endsWith("发布说明\n版本 v2(https://x/y)\n@张三 请看 [图片 image_key=img_v2_k]"),
        s"the body段 must be the design card's verbatim fixture result:\n$body")
  }

  test("FB-R3e a text message with mentions replaces the placeholders and keeps no raw @_user_N") {
    val (ctx, injected) = newCtx(List(meta("s1", Some("oc_bound"))))
    val p = plugin(RecordingSend().fn)
    val msg = inbound("oc_bound", None, messageType = "text",
      contentRaw = """{"text":"@_user_1 你好"}""",
      mentions = List(FeishuMessage.InboundMention("_user_1", Some("张三"), Some("ou_1"))))
    for
      _ <- p.rebuildRoutes(ctx)
      _ <- p.intake(ctx, msg)
      got <- injected.get
    yield
      val body = got.head._2
      assert(body.contains("@张三 你好"), s"the placeholder resolves to the name:\n$body")
      assert(!body.contains("@_user_1"), s"no raw placeholder survives:\n$body")
  }

  // ───────────────────────────── member allowlist ─────────────────────────────

  test("FB-R4 empty allowlist (shipped default) passes a senderless message") {
    // Feishu is a tenant model: with the slot unfilled every tenant member can
    // reach the gateway, and an event without a usable sender id is not a crime.
    val (ctx, injected) = newCtx(List(meta("s1", Some("oc_bound"))))
    val p = plugin(RecordingSend().fn, allowed = Some(Nil))
    for
      _ <- p.rebuildRoutes(ctx)
      _ <- p.intake(ctx, inbound("oc_bound", Some("hi"), sender = None))
      got <- injected.get
    yield assertEquals(triples(got), List(("s1", "hi", None)))
  }

  test("FB-R5 a non-empty allowlist is fail-closed: member passes, stranger and senderless drop") {
    val (ctxOk, injOk) = newCtx(List(meta("s1", Some("oc_bound"))))
    val (ctxStranger, injStranger) = newCtx(List(meta("s1", Some("oc_bound"))))
    val (ctxBlank, injBlank) = newCtx(List(meta("s1", Some("oc_bound"))))
    val p = plugin(RecordingSend().fn, allowed = Some(List("ou_member")))
    for
      _ <- p.rebuildRoutes(ctxOk) *> p.rebuildRoutes(ctxStranger) *> p.rebuildRoutes(ctxBlank)
      _ <- p.intake(ctxOk, inbound("oc_bound", Some("hi"), sender = Some("ou_member")))
      _ <- p.intake(ctxStranger, inbound("oc_bound", Some("hi"), sender = Some("ou_stranger")))
      _ <- p.intake(ctxBlank, inbound("oc_bound", Some("hi"), sender = None))
      ok <- injOk.get
      stranger <- injStranger.get
      blank <- injBlank.get
    yield
      assertEquals(triples(ok), List(("s1", "hi", Some("ou_member"))), "listed member passes with its identity")
      assertEquals(stranger, List.empty, "an unlisted sender is dropped")
      assertEquals(blank, List.empty, "an absent sender id is dropped when the gate is armed")
  }

  // ───────────────────────────── outbound reply ─────────────────────────────

  test("FB-R6 deltas accumulate and flush exactly once on done, to the bound chat") {
    val send = RecordingSend()
    val (ctx, _) = newCtx(List(meta("s1", Some("oc_bound"))))
    val p = plugin(send.fn)
    for
      _ <- p.rebuildRoutes(ctx)
      _ <- p.onAgentEvent("s1", delta("s1", "he"))
      _ <- p.onAgentEvent("s1", delta("s1", "llo"))
      _ <- p.onAgentEvent("s1", done("s1"))
      _ <- p.onAgentEvent("s1", done("s1")) // a second done must not re-send
      got <- send.calls.get
    yield assertEquals(got, List(("oc_bound", "hello")))
  }

  test("FB-R7 an interrupted turn clears the buffer: no torn reply is ever sent") {
    val send = RecordingSend()
    val (ctx, _) = newCtx(List(meta("s1", Some("oc_bound"))))
    val p = plugin(send.fn)
    val interrupted = Json.obj("type" -> Json.fromString("interrupted"), "sessionId" -> Json.fromString("s1"))
    for
      _ <- p.rebuildRoutes(ctx)
      _ <- p.onAgentEvent("s1", delta("s1", "partial "))
      _ <- p.onAgentEvent("s1", interrupted)
      _ <- p.onAgentEvent("s1", done("s1"))
      got <- send.calls.get
    yield assertEquals(got, List.empty)
  }

  test("FB-R8 a session that is not feishu-bound never triggers a send") {
    val send = RecordingSend()
    val (ctx, _) = newCtx(List(meta("s1", Some("oc_bound"))))
    val p = plugin(send.fn)
    for
      _ <- p.rebuildRoutes(ctx)
      _ <- p.onAgentEvent("s_other", delta("s_other", "text"))
      _ <- p.onAgentEvent("s_other", done("s_other"))
      got <- send.calls.get
    yield assertEquals(got, List.empty)
  }

  test("FB-R9 a rejected send is contained: no raise, the call is still recorded") {
    val send = RecordingSend(ok = false)
    val (ctx, _) = newCtx(List(meta("s1", Some("oc_bound"))))
    val p = plugin(send.fn)
    for
      _ <- p.rebuildRoutes(ctx)
      _ <- p.onAgentEvent("s1", delta("s1", "hello"))
      _ <- p.onAgentEvent("s1", done("s1"))
      got <- send.calls.get
    yield assertEquals(got, List(("oc_bound", "hello")))
  }

  // ───────────────────────────── routing table rebuild ─────────────────────────────

  test("FB-R10 refresh rebuilds chat→session routes from SessionMeta.bridges; duplicate chat binds last-wins") {
    val (ctx, _) = newCtx(List(
      meta("s1", Some("oc_1")),
      meta("s2", Some("oc_2")),
      meta("s3", Some("oc_1")), // duplicate binding: the last one wins
      meta("s4", None)          // no feishu bridge entry: not routed
    ))
    val p = plugin(RecordingSend().fn)
    for
      _ <- p.rebuildRoutes(ctx)
      routes <- p.routesRef.get
    yield
      assertEquals(routes.get("oc_1"), Some("s3"))
      assertEquals(routes.get("oc_2"), Some("s2"))
      assertEquals(routes.size, 2)
  }

  test("FB-R10b a blank chat_id in a bridge binding is not routable") {
    val (ctx, _) = newCtx(List(
      SessionMeta(id = "s1", name = "s1", createdAt = 0L, updatedAt = 0L, hasUnread = false,
        bridges = Map("feishu" -> Json.obj("chat_id" -> Json.fromString("  "))))
    ))
    val p = plugin(RecordingSend().fn)
    for
      _ <- p.rebuildRoutes(ctx)
      routes <- p.routesRef.get
    yield assertEquals(routes, Map.empty)
  }

  // ───────────────── auto-bind (feishu-bind batch, 2026-09-27, P1) ─────────────────

  test("FB-A1 the first message from an unbound chat auto-binds to the default session and injects there") {
    val (ctx, injected) = newCtx(List(meta("s1", Some("oc_other"))))
    val p = new FeishuBridgePlugin(tmpRoot(), send = RecordingSend().fn,
      pinnedCreds = Some(FeishuCredentials.Credential("test-app", "test-secret", "spec")),
      readDefaultSession = _ => Some("s_default"))
    for
      _ <- p.rebuildRoutes(ctx)
      _ <- p.intake(ctx, inbound("oc_new", Some("hello")))
      got <- injected.get
      routes <- p.routesRef.get
      binds <- ctx.bound.get
    yield
      assertEquals(triples(got), List(("s_default", "hello", None)), "the message lands in the default session")
      assertEquals(routes.get("oc_new"), Some("s_default"), "the routing table knows the new chat at once")
      assertEquals(binds.map { case (sid, plat, _) => (sid, plat) }, List(("s_default", "feishu")))
      assert(binds.headOption.flatMap(_._3).exists(_.hcursor.downField("chat_id").as[String].toOption == Some("oc_new")))
      assert(binds.headOption.flatMap(_._3).exists(_.hcursor.downField("source").as[String].toOption == Some("auto")),
        "the persisted binding carries source=auto")
      assert(binds.headOption.flatMap(_._3).exists(_.hcursor.downField("boundAt").as[Long].isRight),
        "the persisted binding carries a boundAt timestamp")
  }

  test("FB-A2 no default session configured keeps the pre-existing drop (the branch this batch must not break)") {
    val (ctx, injected) = newCtx(List(meta("s1", Some("oc_other"))))
    val p = new FeishuBridgePlugin(tmpRoot(), send = RecordingSend().fn,
      pinnedCreds = Some(FeishuCredentials.Credential("test-app", "test-secret", "spec")),
      readDefaultSession = _ => None)
    for
      _ <- p.rebuildRoutes(ctx)
      _ <- p.intake(ctx, inbound("oc_new", Some("hello")))
      got <- injected.get
      routes <- p.routesRef.get
      binds <- ctx.bound.get
    yield
      assertEquals(got, List.empty, "no default session ⇒ drop, exactly as before this batch")
      assertEquals(routes.get("oc_new"), None, "no binding minted")
      assertEquals(binds, List.empty, "nothing persisted")
  }

  test("FB-A3 allowlist armed (C6): a listed member CAN mint an auto-binding, a stranger CANNOT") {
    val p = new FeishuBridgePlugin(tmpRoot(), send = RecordingSend().fn,
      pinnedAllowedOpenIds = Some(List("ou_member")),
      pinnedCreds = Some(FeishuCredentials.Credential("test-app", "test-secret", "spec")),
      readDefaultSession = _ => Some("s_default"))
    val (ctxMember, injMember) = newCtx(Nil)
    val (ctxStranger, injStranger) = newCtx(Nil)
    for
      _ <- p.rebuildRoutes(ctxMember) *> p.rebuildRoutes(ctxStranger)
      _ <- p.intake(ctxStranger, inbound("oc_stranger", Some("hi"), sender = Some("ou_stranger")))
      _ <- p.intake(ctxMember, inbound("oc_member", Some("hi"), sender = Some("ou_member")))
      gotStranger <- injStranger.get
      bindsStranger <- ctxStranger.bound.get
      gotMember <- injMember.get
      bindsMember <- ctxMember.bound.get
    yield
      assertEquals(gotStranger, List.empty, "an unlisted sender cannot trigger auto-bind")
      assertEquals(bindsStranger, List.empty, "an unlisted sender cannot mint a binding")
      assertEquals(triples(gotMember), List(("s_default", "hi", Some("ou_member"))))
      assertEquals(bindsMember.map(_._1), List("s_default"), "a listed member's first message binds the chat")
  }

  test("FB-A4 an auto-bound image message injects the explicit placeholder (never a fabricated body)") {
    val (ctx, injected) = newCtx(Nil)
    val p = new FeishuBridgePlugin(tmpRoot(), send = RecordingSend().fn,
      pinnedCreds = Some(FeishuCredentials.Credential("test-app", "test-secret", "spec")),
      readDefaultSession = _ => Some("s_default"))
    for
      _ <- p.rebuildRoutes(ctx)
      _ <- p.intake(ctx, inbound("oc_new", None, messageType = "image", contentRaw = """{"image_key":"k"}"""))
      got <- injected.get
      binds <- ctx.bound.get
    yield
      assertEquals(got.map(_._1), List("s_default"), "the message lands in the default session")
      assert(got.head._2.contains("[未识别的消息类型：image]"),
        s"a non-text body degrades to the named placeholder, not silence:\n${got.head._2}")
      assertEquals(binds.map(_._1), List("s_default"), "the chat itself is bound for the next text message")
  }

  test("FB-R12 a message with genuinely no body is still dropped (the residual None arm)") {
    // The `None` arm of `intake` is not dead code: an empty text payload really
    // has nothing to show, and inventing a body for it would be a fabrication.
    val (ctx, injected) = newCtx(List(meta("s1", Some("oc_bound"))))
    val p = plugin(RecordingSend().fn)
    val empty = inbound("oc_bound", Some(""), messageType = "text", contentRaw = """{"text":""}""")
    for
      _ <- p.rebuildRoutes(ctx)
      _ <- p.intake(ctx, empty)
      got <- injected.get
    yield assertEquals(got, List.empty, "an empty body injects nothing rather than an empty bubble")
  }

  // ─────────── fingerprint guard + connection face (feishu-bind batch, P0-2, C1/C2) ───────────

  test("FB-F1 fingerprintVerdict: match / mismatch / unknown (pure)") {
    assertEquals(FeishuBridgePlugin.fingerprintVerdict(Some("cli_storedapp0000001"), Some("cli_storedapp0000001")), "match")
    assertEquals(FeishuBridgePlugin.fingerprintVerdict(Some("cli_storedapp0000001"), Some("cli_otherapp0000001")), "mismatch")
    assertEquals(FeishuBridgePlugin.fingerprintVerdict(Some("cli_storedapp0000001"), None), "unknown")
    assertEquals(FeishuBridgePlugin.fingerprintVerdict(None, Some("cli_storedapp0000001")), "unknown")
    assertEquals(FeishuBridgePlugin.fingerprintVerdict(None, None), "unknown")
    // blank values are as absent as None
    assertEquals(FeishuBridgePlugin.fingerprintVerdict(Some("  "), Some("cli_storedapp0000001")), "unknown")
    assertEquals(FeishuBridgePlugin.fingerprintVerdict(Some("cli_storedapp0000001"), Some("")), "unknown")
  }

  test("FB-F2 connection face: the stored-A-connected-B world reads mismatch, never green (P0-2)") {
    // The 2026-09-27 incident as a unit: the schema stores app A while the
    // bridge actually resolved app B (pinned credential; the plugin is never
    // started — no socket, fully offline).
    val root = tmpRoot()
    os.write(root / "nebflow.json",
      """{"socialChannels":{"channels":{"feishu":{"enabled":true,"fields":{"app_id":"cli_storedapp0000001","region":"feishu"}}}}}""")
    val p = new FeishuBridgePlugin(root,
      pinnedCreds = Some(FeishuCredentials.Credential("cli_liveapp0000001", "test-secret", "spec")))
    val json = FeishuBridgePlugin.connectionJson(root, Some(p))
    assertEquals(json.hcursor.downField("fingerprintMatch").as[String].toOption, Some("mismatch"),
      "a stored-A-connected-B world must read mismatch (C2)")
    assertEquals(json.hcursor.downField("liveAppId").as[String].toOption, Some("cli_liveapp0000001"))
    assertEquals(json.hcursor.downField("storedAppId").as[String].toOption, Some("cli_storedapp0000001"))
    assertEquals(json.hcursor.downField("enabled").as[Boolean].toOption, Some(true))
    assertEquals(json.hcursor.downField("connected").as[Boolean].toOption, Some(false),
      "the plugin was never started — the reading is honest about it")
    assertEquals(json.hcursor.downField("adapterRegistered").as[Boolean].toOption, Some(true))
    assert(!json.noSpaces.contains("test-secret"), "the connection face must never carry a secret")
  }

  test("FB-F3 connection face: matching worlds read match; no adapter reads unknown") {
    val root = tmpRoot()
    os.write(root / "nebflow.json",
      """{"socialChannels":{"channels":{"feishu":{"enabled":true,"fields":{"app_id":"cli_sameapp00000001","region":"feishu"}}}}}""")
    val p = new FeishuBridgePlugin(root,
      pinnedCreds = Some(FeishuCredentials.Credential("cli_sameapp00000001", "test-secret", "spec")))
    val matchJson = FeishuBridgePlugin.connectionJson(root, Some(p))
    assertEquals(matchJson.hcursor.downField("fingerprintMatch").as[String].toOption, Some("match"))
    val noneJson = FeishuBridgePlugin.connectionJson(root, None)
    assertEquals(noneJson.hcursor.downField("adapterRegistered").as[Boolean].toOption, Some(false))
    assertEquals(noneJson.hcursor.downField("fingerprintMatch").as[String].toOption, Some("unknown"))
    assertEquals(noneJson.hcursor.downField("liveAppId").focus, Some(io.circe.Json.Null))
    assertEquals(noneJson.hcursor.downField("enabled").as[Boolean].toOption, Some(true),
      "the enabled state comes from the stored config, not from the adapter")
  }

  // ─────────── bindings face (feishu-bind batch, C3) ───────────

  test("FB-B1 bindings face: auto bindings carry source=auto+boundAt; everything else reads manual (C3)") {
    val metas = List(
      meta("s1", Some("oc_legacy")), // meta() writes only chat_id — the pre-auto-bind shape
      SessionMeta(id = "s2", name = "s2", createdAt = 0L, updatedAt = 0L, hasUnread = false,
        bridges = Map("feishu" -> Json.obj("chat_id" -> Json.fromString("oc_manual"),
          "source" -> Json.fromString("manual"), "boundAt" -> Json.fromLong(1700000000000L)))),
      SessionMeta(id = "s3", name = "s3", createdAt = 0L, updatedAt = 0L, hasUnread = false,
        bridges = Map("feishu" -> Json.obj("chat_id" -> Json.fromString("oc_a"),
          "source" -> Json.fromString("auto"), "boundAt" -> Json.fromLong(1700000001000L)))),
      meta("s4", None) // no feishu entry: not listed
    )
    val json = FeishuBridgePlugin.bindingsJson(metas)
    val bindings = json.hcursor.downField("bindings").as[List[Json]].getOrElse(Nil)
    assertEquals(bindings.size, 3, "one entry per feishu-bound session")
    val bySession: Map[String, Json] = bindings.flatMap { j =>
      j.hcursor.downField("sessionId").as[String].toOption.map(_ -> j)
    }.toMap
    assertEquals(bySession("s1").hcursor.downField("source").as[String].toOption, Some("manual"),
      "a binding without a source key (pre-auto-bind) reads manual")
    assertEquals(bySession("s1").hcursor.downField("boundAt").focus, Some(Json.Null))
    assertEquals(bySession("s1").hcursor.downField("chatId").as[String].toOption, Some("oc_legacy"))
    assertEquals(bySession("s2").hcursor.downField("source").as[String].toOption, Some("manual"))
    assertEquals(bySession("s2").hcursor.downField("boundAt").as[Long].toOption, Some(1700000000000L))
    assertEquals(bySession("s3").hcursor.downField("source").as[String].toOption, Some("auto"))
    assertEquals(bySession("s3").hcursor.downField("boundAt").as[Long].toOption, Some(1700000001000L))
    assertEquals(bySession("s3").hcursor.downField("chatId").as[String].toOption, Some("oc_a"))
    assertEquals(bySession("s3").hcursor.downField("sessionName").as[String].toOption, Some("s3"))
    assert(!bySession.contains("s4"), "a session with no feishu entry is not listed")
  }

  // ───────────────────────────── lifecycle containment ─────────────────────────────

  test("FB-R11 start without any credential fails soft; stop is idempotent and safe to call twice") {
    val (ctx, _) = newCtx(Nil)
    val p = plugin(RecordingSend().fn)
    for
      _ <- p.start(ctx) // no credentials under tmpRoot: warn + registered-but-unconnected, never a throw
      _ <- p.stop
      _ <- p.stop
    yield ()
  }
