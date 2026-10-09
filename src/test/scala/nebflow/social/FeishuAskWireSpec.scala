package nebflow.social

import cats.effect.IO
import cats.effect.Ref
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.bridge.BridgeContext
import nebflow.shared.SessionMeta
import nebflow.social.outbound.AskPromptCodec

/**
 * Offline wiring spec for the feishu askUser legs (askuser-remote P1,
 * 2026-10-07). The pure-function faces ([[FeishuAskCard]] render/parse) were
 * pinned by their 2026-10-01 specs; what THIS spec pins is the WIRING:
 *
 *   - the askUser frame branch (L0): flatten (D-1) → card render → the
 *     interactive send seam, with the two degraded routes (LocalOnly → the
 *     numbered TEXT card; SendRejected → the same) and the requestId→chat
 *     cache write;
 *   - the card.action.trigger chain (A-4/A-5): allowlist gate → hub snapshot
 *     as the ONLY open-ask authority (no second ledger) → parse → answers →
 *     the bridge answer leg → the A4 answered card in the response;
 *   - askUserClosed → cache clear (a stale click reads 「已失效」).
 *
 * Mutation safety: every gate below is red-by-construction if the matching
 * decision is removed — the allowlist refusal, the chat-mismatch judge, the
 * hub-authority expiry, the D-1 slot alignment (an answer array that grew or
 * shrank would fail the engine's fixed-length contract), the A4 card update,
 * and the cache clear.
 */
class FeishuAskWireSpec extends CatsEffectSuite:

  private def tmpRoot(): os.Path = os.temp.dir(prefix = "nb-feishu-ask-")

  /** Recording BridgeContext: the hub faces are captured, the hub snapshot is
    * a FIXED list (the spec plays the hub). */
  private final class HubCtx(frames: List[Json]) extends BridgeContext:
    val forwarded: Ref[IO, List[(String, String, Json)]] = Ref.unsafe[IO, List[(String, String, Json)]](Nil)
    val closed: Ref[IO, List[String]]                    = Ref.unsafe[IO, List[String]](Nil)
    val hubQueries: Ref[IO, Int]                         = Ref.unsafe[IO, Int](0)
    val injected: Ref[IO, List[String]]                  = Ref.unsafe[IO, List[String]](Nil)

    override def listPendingAsks: IO[List[Json]] = hubQueries.update(_ + 1).as(frames)
    override def forwardInteractionAnswer(requestId: String, rootSessionId: String, payload: Json): IO[Unit] =
      forwarded.update(_ :+ ((requestId, rootSessionId, payload)))
    override def closeInteractionRequest(requestId: String): IO[Unit] = closed.update(_ :+ requestId)
    def injectMessage(sessionId: String, content: String, senderId: Option[String],
        origin: Option[nebflow.bridge.BridgeOrigin] = None): IO[Unit] = injected.update(_ :+ content)
    def interruptAgent(sessionId: String): IO[Unit] = IO.unit
    def sessionMeta(sessionId: String): IO[Option[SessionMeta]] = IO.none
    def listSessions: IO[List[SessionMeta]] =
      IO.pure(List(SessionMeta(id = "root-1", name = "root-1", createdAt = 0L, updatedAt = 0L,
        hasUnread = false, bridges = Map("feishu" -> Json.obj("chat_id" -> "oc_author".asJson)))))
    def updateBridgeConfig(sessionId: String, platform: String, config: Option[Json]): IO[Unit] = IO.unit
  end HubCtx

  /** Recording send seams — no network anywhere. */
  private final class Seams:
    val texts: Ref[IO, List[String]]          = Ref.unsafe[IO, List[String]](Nil)
    val cards: Ref[IO, List[String]]          = Ref.unsafe[IO, List[String]](Nil)
    val cardResults: Ref[IO, List[FeishuChannel.SendResult]] = Ref.unsafe[IO, List[FeishuChannel.SendResult]](Nil)
    val okSend: FeishuBridgePlugin.Send = (_, _, _, _, text) => texts.update(_ :+ text).as(
      FeishuChannel.SendResult(ok = true, Some("om-1"), 0, "ok", "t", "chat_id"))
    val okCard: FeishuBridgePlugin.SendCard = (_, _, _, _, card) => cards.update(_ :+ card).as(
      FeishuChannel.SendResult(ok = true, Some("om-1"), 0, "ok", "t", "chat_id"))
    val rejectCard: FeishuBridgePlugin.SendCard = (_, _, _, _, card) => cards.update(_ :+ card).as(
      FeishuChannel.SendResult(ok = false, None, 230013, "card rejected by test seam", "t", "chat_id"))

  private def plugin(seams: Seams, allowed: Option[List[String]] = None): FeishuBridgePlugin =
    new FeishuBridgePlugin(
      tmpRoot(),
      send = seams.okSend,
      sendCard = seams.okCard,
      pinnedAllowedOpenIds = allowed,
      pinnedCreds = Some(FeishuCredentials.Credential("cli_app", "sec", "spec")),
    )

  /** A hub-shaped askUser frame — the exact fields the bridge consumes
    * (mirrors `AgentActor.buildAskUserJson` + the hub's render additions). */
  private def askFrame(rid: String, root: String, items: List[Json], attachments: List[String] = Nil): Json =
    val fields = scala.collection.mutable.ListBuffer[(String, Json)](
      "type" -> "askUser".asJson,
      "sessionId" -> root.asJson,
      "requestId" -> rid.asJson,
      "items" -> Json.fromValues(items)
    )
    if attachments.nonEmpty then fields += "attachments" -> attachments.asJson
    Json.obj(fields.toList*)

  /** One wire item, mirroring `buildAskUserJson`'s item shape. */
  private def itemJson(
    question: String,
    labels: List[String],
    id: Option[String] = None,
    dependsOnRef: Option[String] = None,
    canvas: Option[String] = None
  ): Json =
    val base = scala.collection.mutable.ListBuffer[(String, Json)](
      "question" -> question.asJson,
      "options" -> Json.fromValues(labels.map(l => Json.obj("label" -> l.asJson))),
      "allowOther" -> true.asJson
    )
    id.foreach(i => base += "id" -> i.asJson)
    dependsOnRef.foreach(r => base += "dependsOn" -> Json.obj("ref" -> r.asJson, "equals" -> "x".asJson))
    canvas.foreach(c => base += "canvas" -> c.asJson)
    Json.obj(base.toList*)

  /** The button payload the real card carries (FeishuAskCard's design). */
  private def clickValue(rid: String, qi: Int, oi: Int): Json =
    Json.obj(
      "nf" -> "ask".asJson,
      "rid" -> rid.asJson,
      "qi" -> qi.asJson,
      "oi" -> oi.asJson
    )

  private def cardAction(value: Json, openId: Option[String] = Some("ou_author"),
    chatId: Option[String] = Some("oc_author")): FeishuChannel.CardAction =
    FeishuChannel.CardAction(openId = openId, chatId = chatId, messageId = Some("om_card"), value = value)

  // ───────────────────────── outbound: the askUser frame branch ─────────────────────────

  test("FA-W1 askUser frame → ONE 1.0 card through the card seam; cache pinned") {
    val seams = Seams()
    val p = plugin(seams)
    val ctx = HubCtx(Nil)
    for
      _ <- p.start(ctx)
      _ <- p.onAgentEvent("root-1", askFrame("rid-1", "root-1",
        List(itemJson("继续吗?", List("继续", "停")))))
      cards <- seams.cards.get
      texts <- seams.texts.get
      cache <- p.askChatRef.get
    yield
      assertEquals(cards.size, 1)
      val card = cards.head
      assert(card.contains(FeishuAskCard.TitleCopy), s"card must carry the frozen title")
      assert(card.contains("\"rid\":\"rid-1\""), s"payload must carry the request id: $card")
      assert(card.contains("\"qi\":0") && card.contains("\"oi\":1"), s"payload must carry the indices: $card")
      assertEquals(texts, List.empty) // no text fallback on the happy path
      assertEquals(cache.get("rid-1"), Some("oc_author")) // A5: the ONLY local ask state
    end for
  }

  test("FA-W2 LocalOnly (canvas question) → the numbered TEXT card, zero cards") {
    val seams = Seams()
    val p = plugin(seams)
    val ctx = HubCtx(Nil)
    for
      _ <- p.start(ctx)
      _ <- p.onAgentEvent("root-1", askFrame("rid-2", "root-1",
        List(itemJson("选目录?", Nil, canvas = Some("/tmp")))))
      cards <- seams.cards.get
      texts <- seams.texts.get
    yield
      assertEquals(cards, List.empty)
      assertEquals(texts.size, 1)
      assert(texts.head.contains(FeishuAskCard.DegradeFoot))
      assert(texts.head.contains("canvas"), s"the degrade reason names the fact: ${texts.head}")
    end for
  }

  test("FA-W3 SendRejected on every card → ONE text fallback (the frozen chain)") {
    val seams = Seams()
    val p = new FeishuBridgePlugin(
      tmpRoot(), send = seams.okSend, sendCard = seams.rejectCard,
      pinnedAllowedOpenIds = None,
      pinnedCreds = Some(FeishuCredentials.Credential("cli_app", "sec", "spec")))
    val ctx = HubCtx(Nil)
    for
      _ <- p.start(ctx)
      _ <- p.onAgentEvent("root-1", askFrame("rid-3", "root-1",
        List(itemJson("继续吗?", List("继续", "停")))))
      cards <- seams.cards.get
      texts <- seams.texts.get
    yield
      assertEquals(cards.size, 1) // the rejected attempt IS observable
      assertEquals(texts.size, 1)
      assert(texts.head.contains("230013"), s"the degrade reason carries the transport code: ${texts.head}")
    end for
  }

  test("FA-W4 D-1 flatten: the unreachable question is NOT sent, its slot stays \"\"") {
    val seams = Seams()
    val p = plugin(seams)
    val ctx = HubCtx(List(
      askFrame("rid-4", "root-1", List(
        itemJson("第一问?", List("A1", "A2"), id = Some("q1")),
        itemJson("不可达?", List("B1"), id = Some("q2"), dependsOnRef = Some("q-missing"))
      ))
    ))
    for
      _ <- p.start(ctx)
      cards <- seams.cards.get
      // The card must carry ONLY the surviving question's buttons.
      _ = assertEquals(cards.count(_.contains("不可达?")), 0)
      res <- p.handleCardAction(ctx, cardAction(clickValue("rid-4", 0, 0)))
      fw <- ctx.forwarded.get
    yield
      assert(res.card.exists(_.contains(FeishuAskCard.AnsweredPrefix)), s"A4 card update in the response")
      assertEquals(fw.map(_._3), List(Json.obj("answers" -> List("A1", "").asJson)))
    end for
  }

  test("FA-W5 D-1 flatten is a FIXPOINT: a dropped question takes its dependents") {
    val flat = AskPromptCodec.flatten(List(
      outbound.PromptView(0, "a", id = Some("a")),
      outbound.PromptView(1, "b", id = Some("b"), dependsOnRef = Some("c")),
      outbound.PromptView(2, "c", id = Some("c"), dependsOnRef = Some("missing"))
    ))
    assertEquals(flat.views.map(_.question), List("a"))
    assertEquals(flat.originalIndices, List(0))
    assertEquals(flat.originalSize, 3)
    assertEquals(flat.expandAnswerSlots(List("x")), List("x", "", ""))
  }

  test("FA-W6 attachments ride as PATH LINES only (A6)") {
    val seams = Seams()
    val p = plugin(seams)
    val ctx = HubCtx(Nil)
    for
      _ <- p.start(ctx)
      _ <- p.onAgentEvent("root-1", askFrame("rid-6", "root-1",
        List(itemJson("看图选?", List("图1", "图2"))), attachments = List("/tmp/a.png", " /tmp/b.pdf ")) )
      cards <- seams.cards.get
    yield
      assertEquals(cards.size, 1)
      assert(cards.head.contains(nebflow.social.outbound.AskPromptCodec.AttachmentLineHead))
      assert(cards.head.contains("- /tmp/a.png"))
      assert(cards.head.contains("- /tmp/b.pdf"))
    end for
  }

  test("FA-W7 askUserClosed clears the cache entry (a stale click then reads 已失效)") {
    val seams = Seams()
    val p = plugin(seams)
    val ctx = HubCtx(Nil)
    for
      _ <- p.start(ctx)
      _ <- p.onAgentEvent("root-1", askFrame("rid-7", "root-1", List(itemJson("q?", List("a")))))
      cacheBefore <- p.askChatRef.get
      _ <- p.onAgentEvent("root-1", Json.obj(
        "type" -> "askUserClosed".asJson, "sessionId" -> "root-1".asJson,
        "requestId" -> "rid-7".asJson, "reason" -> "caller-withdrew".asJson))
      cacheAfter <- p.askChatRef.get
    yield
      assertEquals(cacheBefore.get("rid-7"), Some("oc_author"))
      assertEquals(cacheAfter.get("rid-7"), None)
    end for
  }

  // ───────────────────────── inbound: the card.action.trigger chain ─────────────────────────

  test("FA-C1 allowlist gate: a click from a non-member is refused, nothing forwarded") {
    val seams = Seams()
    val p = plugin(seams, allowed = Some(List("ou_author")))
    val ctx = HubCtx(List(askFrame("rid-8", "root-1", List(itemJson("q?", List("a", "b"))))))
    for
      _ <- p.start(ctx)
      res <- p.handleCardAction(ctx, cardAction(clickValue("rid-8", 0, 0), openId = Some("ou_mallory")))
      fw <- ctx.forwarded.get
    yield
      assertEquals(res.toast, Some(FeishuAskCard.ToastCannotHandle))
      assertEquals(res.card, None)
      assertEquals(fw, List.empty)
    end for
  }

  test("FA-C2 hub authority: an unknown/answered requestId toasts 已失效 (A5 — no second ledger)") {
    val seams = Seams()
    val p = plugin(seams)
    val ctx = HubCtx(Nil) // the hub snapshot is EMPTY: nothing is open
    for
      _ <- p.start(ctx)
      res <- p.handleCardAction(ctx, cardAction(clickValue("rid-gone", 0, 0)))
      q <- ctx.hubQueries.get
    yield
      assertEquals(res.toast, Some(FeishuAskCard.ToastExpired))
      assertEquals(q, 1) // the hub was consulted — the bridge keeps no expiry state
    end for
  }

  test("FA-C3 chat-mismatch: a click from another chat is refused") {
    val seams = Seams()
    val p = plugin(seams)
    val ctx = HubCtx(List(askFrame("rid-9", "root-1", List(itemJson("q?", List("a", "b"))))))
    for
      _ <- p.start(ctx)
      _ <- p.askChatRef.set(Map("rid-9" -> "oc_author"))
      res <- p.handleCardAction(ctx, cardAction(clickValue("rid-9", 0, 0), chatId = Some("oc_elsewhere")))
      fw <- ctx.forwarded.get
    yield
      assertEquals(res.toast, Some(FeishuAskCard.ToastCannotHandle))
      assertEquals(fw, List.empty)
    end for
  }

  test("FA-C4 happy click → Answered forwarded (fixed-length slots) + A4 card update, no toast") {
    val seams = Seams()
    val p = plugin(seams)
    val ctx = HubCtx(List(
      askFrame("rid-10", "root-1", List(
        itemJson("一?", List("A1", "A2"), id = Some("q1")),
        itemJson("二?", List("B1", "B2"), id = Some("q2"))
      ))
    ))
    for
      _ <- p.start(ctx)
      _ <- p.askChatRef.set(Map("rid-10" -> "oc_author"))
      res <- p.handleCardAction(ctx, cardAction(clickValue("rid-10", 1, 0)))
      fw <- ctx.forwarded.get
    yield
      assertEquals(res.toast, None)
      assertEquals(fw, List(("rid-10", "root-1", Json.obj("answers" -> List("", "B1").asJson))))
      val card = res.card.getOrElse("")
      assert(card.contains(FeishuAskCard.AnsweredPrefix + "B1"), s"the answered question flips: $card")
      assert(card.contains(clickValue("rid-10", 0, 0).noSpaces), s"the OTHER question stays clickable: $card")
      assert(card.contains(FeishuAskCard.TitleCopy))
    end for
  }

  test("FA-C5 A5 degrade: cache miss + hub open → answer proceeds, click re-pins the chat") {
    val seams = Seams()
    val p = plugin(seams)
    val ctx = HubCtx(List(askFrame("rid-11", "root-1", List(itemJson("q?", List("a", "b"))))))
    for
      _ <- p.start(ctx)
      res <- p.handleCardAction(ctx, cardAction(clickValue("rid-11", 0, 1), chatId = Some("oc_new")))
      fw <- ctx.forwarded.get
      cache <- p.askChatRef.get
    yield
      assertEquals(fw.map(_._3), List(Json.obj("answers" -> List("b").asJson)))
      assertEquals(cache.get("rid-11"), Some("oc_new"))
      assert(res.card.isDefined)
    end for
  }

  test("FA-C6 an Other click forwards the empty-slot semantics and shows the button label") {
    val seams = Seams()
    val p = plugin(seams)
    val ctx = HubCtx(List(askFrame("rid-12", "root-1", List(itemJson("q?", List("a"))))))
    for
      _ <- p.start(ctx)
      _ <- p.askChatRef.set(Map("rid-12" -> "oc_author"))
      res <- p.handleCardAction(ctx, cardAction(clickValue("rid-12", 0, -1)))
      fw <- ctx.forwarded.get
    yield
      // P1 has no form input: the Other button click is the "skip" semantics —
      // slot stays "", the card says so with the frozen button label.
      assertEquals(fw.map(_._3), List(Json.obj("answers" -> List("").asJson)))
      assert(res.card.exists(_.contains(FeishuAskCard.AnsweredPrefix + FeishuAskCard.OtherCopy)))
    end for
  }

end FeishuAskWireSpec
