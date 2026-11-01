package nebflow.social

import cats.effect.IO
import cats.effect.Ref
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.bridge.BridgeContext
import nebflow.shared.SessionMeta

/**
 * Pipeline-level spec for the output-block channel wiring (output 块纪律渠道
 * 接线批, 2026-11-01; author's order: 「微信端和飞书端收到的消息也要做 output
 * 块剥离，只显示 output 块内部的内容」).
 *
 * 🔴 NO NETWORK, NO CREDENTIAL: both live bridges are driven through their
 * real `onAgentEvent` event pipeline (textDelta / toolStart / done frames —
 * the production shapes) with constructor-injected RECORDING send seams, and
 * the assertions read the WIRE PAYLOADS. This is the proof the author's order
 * asks for: the outbound payload carries ONLY the block-inner join — never the
 * narration, never a full-text fallback, never a half-streamed block.
 *
 * Each assertion is red-by-construction: removing the [[OutputBlock.extract]]
 * call at either channel's segment/turn-end seam re-exposes the narration (or
 * the whole raw reply) in the recorded payloads and turns these red.
 */
class OutputBlockChannelWireSpec extends CatsEffectSuite:

  // ───────────────────────────── fixtures ─────────────────────────────

  /** Minimal recording BridgeContext (routes rebuild reads listSessions). */
  private final class Ctx(sessions: List[SessionMeta]) extends BridgeContext:
    def injectMessage(
        sessionId: String,
        content: String,
        senderId: Option[String],
        origin: Option[nebflow.bridge.BridgeOrigin] = None
    ): IO[Unit] = IO.unit
    override def injectMessageWithMedia(
        sessionId: String,
        content: String,
        senderId: Option[String],
        origin: Option[nebflow.bridge.BridgeOrigin] = None,
        attachments: List[nebflow.bridge.BridgeAttachment] = Nil
    ): IO[Unit] = IO.unit
    def interruptAgent(sessionId: String): IO[Unit] = IO.unit
    def sessionMeta(sessionId: String): IO[Option[SessionMeta]] = IO.none
    def listSessions: IO[List[SessionMeta]] = IO.pure(sessions)
    def updateBridgeConfig(sessionId: String, platform: String, config: Option[Json]): IO[Unit] = IO.unit

  private def wxMeta(id: String, senderId: String): SessionMeta =
    SessionMeta(
      id = id,
      name = id,
      createdAt = 0L,
      updatedAt = 0L,
      hasUnread = false,
      bridges = Map(
        WeixinIlinkBridgePlugin.Name -> Json.obj("ilink_user_id" -> Json.fromString(senderId))
      )
    )

  private def fsMeta(id: String, chatId: String): SessionMeta =
    SessionMeta(
      id = id,
      name = id,
      createdAt = 0L,
      updatedAt = 0L,
      hasUnread = false,
      bridges = Map(FeishuBridgePlugin.Name -> Json.obj("chat_id" -> Json.fromString(chatId)))
    )

  /** Recording weixin text send seam — captures every (to, payload). */
  private def wxSeam(): (os.Path => WeixinIlinkBridgePlugin.Send, Ref[IO, List[(String, String)]]) =
    val calls = Ref.unsafe[IO, List[(String, String)]](Nil)
    val factory: os.Path => WeixinIlinkBridgePlugin.Send = _ => (to, text) =>
      calls.update(_ :+ ((to, text))).as(WeixinOutboundAdapter.SendResult(true, true, Some("m1"), 200, "delivered"))
    (factory, calls)

  /** Recording feishu text send seam — captures every (receiveId, payload). */
  private def fsSeam(): (FeishuBridgePlugin.Send, Ref[IO, List[(String, String)]]) =
    val calls = Ref.unsafe[IO, List[(String, String)]](Nil)
    val fn: FeishuBridgePlugin.Send = (_appId, _appSecret, _region, receiveId, text) =>
      calls
        .update(_ :+ ((receiveId, text)))
        .as(FeishuChannel.SendResult(ok = true, Some("m1"), 200, "ok", "now", "chat_id"))
    (fn, calls)

  private def delta(sessionId: String, text: String): Json =
    Json.obj("type" -> "textDelta".asJson, "sessionId" -> sessionId.asJson, "delta" -> text.asJson)

  private val toolStart = Json.obj("type" -> "toolStart".asJson)
  private def doneEv(sessionId: String): Json = Json.obj("type" -> "done".asJson, "sessionId" -> sessionId.asJson)

  // ─────────────── weixin iLink: the turn pipeline strips to the join ───────────────

  test("WX-OB1 multi-block turn ⇒ outbound payload is ONLY the blank-line block join (narration gone)") {
    val (factory, calls) = wxSeam()
    val ctx = new Ctx(List(wxMeta("s1", "wx_alice")))
    val p = new WeixinIlinkBridgePlugin(os.temp.dir(prefix = "nb-ob-wx-"), pinnedAllowedIlinkUserIds = Some(Nil), send = factory)
    for
      _ <- p.start(ctx)
      _ <- p.onAgentEvent("s1", delta("s1", "先看结论前的思考叙述 "))
      _ <- p.onAgentEvent("s1", delta("s1", "<output>A</output>\n中间的叙述不出站\n"))
      _ <- p.onAgentEvent("s1", delta("s1", "<output>  B  </output>"))
      _ <- p.onAgentEvent("s1", doneEv("s1"))
      got <- calls.get
    yield assertEquals(got, List(("wx_alice", "A\n\nB")), "the wire carries only the block-inner join")
  }

  test("WX-OB2 a segment flushed mid-block is HELD, not sent, not dropped — it ships once the block closes") {
    val (factory, calls) = wxSeam()
    val ctx = new Ctx(List(wxMeta("s1", "wx_alice")))
    val p = new WeixinIlinkBridgePlugin(os.temp.dir(prefix = "nb-ob-wx-"), pinnedAllowedIlinkUserIds = Some(Nil), send = factory)
    for
      _ <- p.start(ctx)
      _ <- p.onAgentEvent("s1", delta("s1", "<output>未完"))
      _ <- p.onAgentEvent("s1", toolStart) // segment boundary lands INSIDE the open block
      held <- calls.get
      bufAfterHold <- p.pendingRef.get
      _ <- p.onAgentEvent("s1", delta("s1", "段</output>"))
      _ <- p.onAgentEvent("s1", doneEv("s1"))
      got <- calls.get
      bufAtEnd <- p.pendingRef.get
    yield
      assertEquals(held, List.empty, "an unbalanced buffer is never sent mid-stream")
      assertEquals(bufAfterHold.get("s1"), Some("<output>未完"), "hold = the buffer stays for later deltas")
      assertEquals(got, List(("wx_alice", "未完段")), "the completed block ships its inner content once")
      assertEquals(bufAtEnd.get("s1"), None, "the turn buffer is consumed at done")
  }

  test("WX-OB3 narration-only segments deliver NOTHING; the block content still ships (strip, not swallow)") {
    val (factory, calls) = wxSeam()
    val ctx = new Ctx(List(wxMeta("s1", "wx_alice")))
    val p = new WeixinIlinkBridgePlugin(os.temp.dir(prefix = "nb-ob-wx-"), pinnedAllowedIlinkUserIds = Some(Nil), send = factory)
    for
      _ <- p.start(ctx)
      _ <- p.onAgentEvent("s1", delta("s1", "这一段纯工具过程叙述，块外。"))
      _ <- p.onAgentEvent("s1", toolStart)
      _ <- p.onAgentEvent("s1", delta("s1", "<output>可见结论</output>"))
      _ <- p.onAgentEvent("s1", doneEv("s1"))
      got <- calls.get
    yield assertEquals(got, List(("wx_alice", "可见结论")), "narration never reaches the wire; the block does")
  }

  test("WX-OB4 a turn with NO output block delivers NO text (never a full-text fallback)") {
    val (factory, calls) = wxSeam()
    val ctx = new Ctx(List(wxMeta("s1", "wx_alice")))
    val p = new WeixinIlinkBridgePlugin(os.temp.dir(prefix = "nb-ob-wx-"), pinnedAllowedIlinkUserIds = Some(Nil), send = factory)
    for
      _ <- p.start(ctx)
      _ <- p.onAgentEvent("s1", delta("s1", "旧式无块回复全文。"))
      _ <- p.onAgentEvent("s1", doneEv("s1"))
      got <- calls.get
    yield assertEquals(got, List.empty, "作者令：不要回退成发全文")
  }

  // ─────────────── feishu: the same single source, the same semantics ───────────────

  test("FS-OB1 a feishu turn ships ONLY the block-inner join (console-aligned, narration stripped)") {
    val (fn, calls) = fsSeam()
    val ctx = new Ctx(List(fsMeta("s1", "oc_bound")))
    val p = new FeishuBridgePlugin(
      os.temp.dir(prefix = "nb-ob-fs-"),
      send = fn,
      pinnedCreds = Some(FeishuCredentials.Credential("test-app", "test-secret", "spec"))
    )
    for
      _ <- p.rebuildRoutes(ctx)
      _ <- p.onAgentEvent("s1", delta("s1", "思考叙述 <output>飞书可见内容</output> 尾部叙述"))
      _ <- p.onAgentEvent("s1", doneEv("s1"))
      got <- calls.get
    yield assertEquals(got, List(("oc_bound", "飞书可见内容")), "the feishu wire payload is the block join alone")
  }

  test("FS-OB2 the mid-block segment flush is HELD on feishu too (same hold semantics, same source)") {
    val (fn, calls) = fsSeam()
    val ctx = new Ctx(List(fsMeta("s1", "oc_bound")))
    val p = new FeishuBridgePlugin(
      os.temp.dir(prefix = "nb-ob-fs-"),
      send = fn,
      pinnedCreds = Some(FeishuCredentials.Credential("test-app", "test-secret", "spec"))
    )
    for
      _ <- p.rebuildRoutes(ctx)
      _ <- p.onAgentEvent("s1", delta("s1", "<output>第一"))
      _ <- p.onAgentEvent("s1", toolStart)
      held <- calls.get
      _ <- p.onAgentEvent("s1", delta("s1", "半</output>"))
      _ <- p.onAgentEvent("s1", doneEv("s1"))
      got <- calls.get
    yield
      assertEquals(held, List.empty, "an unbalanced buffer is never sent mid-stream")
      assertEquals(got, List(("oc_bound", "第一半")), "the completed block ships once, inner content only")
  }

  test("FS-OB3 a feishu turn with NO output block delivers NO text (never a full-text fallback)") {
    val (fn, calls) = fsSeam()
    val ctx = new Ctx(List(fsMeta("s1", "oc_bound")))
    val p = new FeishuBridgePlugin(
      os.temp.dir(prefix = "nb-ob-fs-"),
      send = fn,
      pinnedCreds = Some(FeishuCredentials.Credential("test-app", "test-secret", "spec"))
    )
    for
      _ <- p.rebuildRoutes(ctx)
      _ <- p.onAgentEvent("s1", delta("s1", "纯叙述回复。"))
      _ <- p.onAgentEvent("s1", doneEv("s1"))
      got <- calls.get
    yield assertEquals(got, List.empty, "作者令：不要回退成发全文")
  }

end OutputBlockChannelWireSpec
