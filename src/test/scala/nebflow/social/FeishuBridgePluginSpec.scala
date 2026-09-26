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
      messageType: String = "text"
  ): FeishuMessage.Inbound =
    FeishuMessage.Inbound(
      eventId = Some("evt_1"),
      messageId = "om_1",
      chatId = chatId,
      messageType = messageType,
      contentRaw = text.map(t => Json.obj("text" -> Json.fromString(t)).noSpaces).getOrElse(""),
      text = text,
      createTime = None,
      senderId = sender
    )

  private def meta(id: String, chatId: Option[String]): SessionMeta =
    SessionMeta(
      id = id, name = id, createdAt = 0L, updatedAt = 0L, hasUnread = false,
      bridges = chatId.map(c => Map("feishu" -> Json.obj("chat_id" -> Json.fromString(c)))).getOrElse(Map.empty)
    )

  /** Recording BridgeContext: captures injections, serves a fixed session list. */
  private final class RecordingCtx(sessions: Ref[IO, List[SessionMeta]]) extends BridgeContext:
    val injected: Ref[IO, List[(String, String, Option[String])]] =
      Ref.unsafe[IO, List[(String, String, Option[String])]](Nil)
    def injectMessage(sessionId: String, content: String, senderId: Option[String]): IO[Unit] =
      injected.update(_ :+ ((sessionId, content, senderId)))
    def interruptAgent(sessionId: String): IO[Unit] = IO.unit
    def sessionMeta(sessionId: String): IO[Option[SessionMeta]] = IO.none
    def listSessions: IO[List[SessionMeta]] = sessions.get
    def updateBridgeConfig(sessionId: String, platform: String, config: Option[Json]): IO[Unit] = IO.unit

  private def newCtx(sessions: List[SessionMeta]): (RecordingCtx, Ref[IO, List[(String, String, Option[String])]]) =
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

  // ───────────────────────────── inbound routing ─────────────────────────────

  test("FB-R1 inbound text from a bound chat injects into the bound session") {
    val (ctx, injected) = newCtx(List(meta("s1", Some("oc_bound"))))
    val p = plugin(RecordingSend().fn)
    for
      _ <- p.rebuildRoutes(ctx)
      _ <- p.intake(ctx, inbound("oc_bound", Some("hello")))
      got <- injected.get
    yield assertEquals(got, List(("s1", "hello", None)))
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

  test("FB-R3 a non-text message is never fabricated into a text injection") {
    val (ctx, injected) = newCtx(List(meta("s1", Some("oc_bound"))))
    val p = plugin(RecordingSend().fn)
    val image = inbound("oc_bound", None, messageType = "image")
    for
      _ <- p.rebuildRoutes(ctx)
      _ <- p.intake(ctx, image)
      got <- injected.get
    yield assertEquals(got, List.empty)
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
    yield assertEquals(got, List(("s1", "hi", None)))
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
      assertEquals(ok, List(("s1", "hi", Some("ou_member"))), "listed member passes with its identity")
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
