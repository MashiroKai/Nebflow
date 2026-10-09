package nebflow.social

import cats.effect.IO
import cats.effect.Ref
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.bridge.BridgeContext
import nebflow.shared.SessionMeta

import scala.concurrent.duration.*

/**
 * askucancel 批（2026-10-08）—— 渠道侧取消关键字腿接线（单测级，无网络/无凭据/
 * 无宿主进程）：两渠道文本 intake 对 `取消问题` 的裁决。
 *
 *  微信（weixin-ilink）：在册 open 问（B-3 恒单问不变量）⇒ Verdict.Cancelled +
 *  精确取消（cancel 面被调）+ 回执行落 seam；无问 ⇒ 聊天透传（B-2，注入照旧）。
 *  飞书：恰一问 ⇒ 精确取消 + 回执行；多问 ⇒ 歧义拒绝线（绝不猜）；零问 ⇒ 透传。
 */
class AskCancelChannelSpec extends CatsEffectSuite:

  override val munitIOTimeout = 30.seconds

  private def tmpRoot(): os.Path = os.temp.dir(prefix = "nb-askcancel-")

  // ─────────────────────── 共用 fake：hub 快照 + cancel 记录 ───────────────────────

  private final class HubCtx(frames: List[Json], sessions: List[SessionMeta]) extends BridgeContext:
    val cancelled: Ref[IO, List[(String, String, String)]] = Ref.unsafe[IO, List[(String, String, String)]](Nil)
    val injected: Ref[IO, List[(String, String)]] = Ref.unsafe[IO, List[(String, String)]](Nil)
    override def listPendingAsks: IO[List[Json]] = IO.pure(frames)
    override def cancelInteractionRequest(requestId: String, reason: String, by: String): IO[Unit] =
      cancelled.update(_ :+ ((requestId, reason, by)))
    def injectMessage(sessionId: String, content: String, senderId: Option[String],
        origin: Option[nebflow.bridge.BridgeOrigin] = None): IO[Unit] =
      injected.update(_ :+ ((sessionId, content)))
    def interruptAgent(sessionId: String): IO[Unit] = IO.unit
    def sessionMeta(sessionId: String): IO[Option[SessionMeta]] = IO.none
    def listSessions: IO[List[SessionMeta]] = IO.pure(sessions)
    def updateBridgeConfig(sessionId: String, platform: String, config: Option[Json]): IO[Unit] = IO.unit
  end HubCtx

  /** hub 快照里的一张 askUser 卡（frames 的 sessionId = rootSessionId）。 */
  private def askFrame(rid: String, root: String): Json =
    Json.obj(
      "type" -> "askUser".asJson,
      "sessionId" -> root.asJson,
      "requestId" -> rid.asJson,
      "items" -> Json.arr(Json.obj("question" -> "继续？".asJson))
    )

  // ───────────────────────────── 微信腿 ─────────────────────────────

  private def wxMeta(id: String, senderId: Option[String]): SessionMeta =
    SessionMeta(
      id = id, name = id, createdAt = 0L, updatedAt = 0L, hasUnread = false,
      bridges = senderId
        .map(s => Map(WeixinIlinkBridgePlugin.Name -> Json.obj("ilink_user_id" -> Json.fromString(s))))
        .getOrElse(Map.empty)
    )

  private final class WxSend(ok: Boolean = true):
    val texts: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)
    val fn: (String, String) => IO[WeixinOutboundAdapter.SendResult] = (_, text) =>
      texts.update(_ :+ text).as(WeixinOutboundAdapter.SendResult(ok, delivered = ok, None, 0, "ok"))

  private def wxPlugin(seam: WxSend, allowed: Option[List[String]] = None): WeixinIlinkBridgePlugin =
    new WeixinIlinkBridgePlugin(tmpRoot(), pinnedAllowedIlinkUserIds = allowed, send = _ => seam.fn)

  test("WX-C1 在册 open 问 + 「取消问题」 ⇒ Verdict.Cancelled + cancel 面被调 + 回执行落 seam + 不注入会话") {
    val ctx = new HubCtx(Nil, List(wxMeta("s1", Some("wx_alice"))))
    val seam = new WxSend()
    val p = wxPlugin(seam)
    for
      _ <- p.start(ctx)
      _ <- p.onAgentEvent("s1", Json.obj(
        "type" -> "askUser".asJson,
        "sessionId" -> "s1".asJson,
        "requestId" -> "asknb-wx1".asJson,
        "items" -> Json.arr(Json.obj("question" -> "继续？".asJson, "options" -> Json.arr(Json.obj("label" -> "是".asJson))))
      ))
      v <- p.intake(ctx, WeixinIlinkBridgePlugin.Inbound(Some("wx_alice"), Some("取消问题"), Some("m1")))
      cancels <- ctx.cancelled.get
      receipt <- seam.texts.get
      injected <- ctx.injected.get
    yield
      assertEquals(v, WeixinIlinkBridgePlugin.Verdict.Cancelled("asknb-wx1"))
      assertEquals(cancels.map(_._1), List("asknb-wx1"), "取消未按 requestId 精确发出")
      assert(receipt.exists(_.contains("已取消")), s"回执行未送达：$receipt")
      assertEquals(injected, Nil, "取消关键字被当成聊天注入了会话")
    end for
  }

  test("WX-C2 在册 open 问 + 编号答复照常作答（取消臂不拦截合法答复）") {
    val ctx = new HubCtx(Nil, List(wxMeta("s1", Some("wx_alice"))))
    val seam = new WxSend()
    val p = wxPlugin(seam)
    for
      _ <- p.start(ctx)
      _ <- p.onAgentEvent("s1", Json.obj(
        "type" -> "askUser".asJson,
        "sessionId" -> "s1".asJson,
        "requestId" -> "asknb-wx2".asJson,
        "items" -> Json.arr(Json.obj("question" -> "继续？".asJson, "options" -> Json.arr(Json.obj("label" -> "是".asJson))))
      ))
      v <- p.intake(ctx, WeixinIlinkBridgePlugin.Inbound(Some("wx_alice"), Some("1"), Some("m2")))
      cancels <- ctx.cancelled.get
    yield
      assertEquals(v, WeixinIlinkBridgePlugin.Verdict.Answered("asknb-wx2"))
      assertEquals(cancels, Nil, "编号答复被取消臂误吞")
    end for
  }

  test("WX-C3 无在册问 + 「取消问题」 ⇒ 聊天透传（B-2：关键字不拦截不吞）") {
    val ctx = new HubCtx(Nil, List(wxMeta("s1", Some("wx_alice"))))
    val seam = new WxSend()
    val p = wxPlugin(seam)
    for
      _ <- p.start(ctx)
      v <- p.intake(ctx, WeixinIlinkBridgePlugin.Inbound(Some("wx_alice"), Some("取消问题"), Some("m3")))
      cancels <- ctx.cancelled.get
      injected <- ctx.injected.get
    yield
      assertEquals(v, WeixinIlinkBridgePlugin.Verdict.Injected("s1"))
      assertEquals(cancels, Nil)
      assertEquals(injected.map(_._2), List("取消问题"))
    end for
  }

  // ───────────────────────────── 飞书腿 ─────────────────────────────

  private def fsMeta(id: String, chatId: Option[String]): SessionMeta =
    SessionMeta(
      id = id, name = id, createdAt = 0L, updatedAt = 0L, hasUnread = false,
      bridges = chatId.map(c => Map("feishu" -> Json.obj("chat_id" -> Json.fromString(c)))).getOrElse(Map.empty)
    )

  private final class FsSeams(ok: Boolean = true):
    val texts: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)
    val okSend: FeishuBridgePlugin.Send = (_, _, _, _, text) =>
      texts.update(_ :+ text).as(FeishuChannel.SendResult(ok, Some("om"), 0, "ok", "t", "chat_id"))

  private def fsPlugin(seams: FsSeams): FeishuBridgePlugin =
    new FeishuBridgePlugin(
      tmpRoot(),
      send = seams.okSend,
      pinnedCreds = Some(FeishuCredentials.Credential("cli_app", "sec", "spec")))

  test("FS-C1 恰一张在册问卡 + 「取消问题」 ⇒ 精确取消 + 回执行 + 不注入") {
    val frames = List(askFrame("asknb-fs1", "root-1"))
    val ctx = new HubCtx(frames, List(fsMeta("root-1", Some("oc_a"))))
    val seams = new FsSeams()
    val p = fsPlugin(seams)
    for
      _ <- p.rebuildRoutes(ctx)
      _ <- p.intake(ctx, feishuInbound("oc_a", "取消问题"))
      cancels <- ctx.cancelled.get
      sent <- seams.texts.get
      injected <- ctx.injected.get
    yield
      assertEquals(cancels.map(_._1), List("asknb-fs1"))
      assertEquals(cancels.map(_._2), List("channel-cancel-keyword"))
      assert(sent.exists(_.contains("已取消")), s"回执行未送达：$sent")
      assertEquals(injected, Nil)
    end for
  }

  test("FS-C2 两张在册问卡 ⇒ 歧义拒绝线（绝不猜），零取消") {
    val frames = List(askFrame("asknb-fs2", "root-1"), askFrame("asknb-fs3", "root-1"))
    val ctx = new HubCtx(frames, List(fsMeta("root-1", Some("oc_a"))))
    val seams = new FsSeams()
    val p = fsPlugin(seams)
    for
      _ <- p.rebuildRoutes(ctx)
      _ <- p.intake(ctx, feishuInbound("oc_a", "取消问题"))
      cancels <- ctx.cancelled.get
      sent <- seams.texts.get
      injected <- ctx.injected.get
    yield
      assertEquals(cancels, Nil, "歧义形态仍发生了取消（猜了）")
      assert(sent.exists(_.contains("2 个待答问题")), s"拒绝线未送达：$sent")
      assertEquals(injected, Nil)
    end for
  }

  test("FS-C3 零在册问卡 + 「取消问题」 ⇒ 聊天透传（B-2），mcp 卡不计入") {
    val frames = List(
      Json.obj("type" -> "mcpPermission".asJson, "sessionId" -> "root-1".asJson, "requestId" -> "perm-x".asJson)
    )
    val ctx = new HubCtx(frames, List(fsMeta("root-1", Some("oc_a"))))
    val seams = new FsSeams()
    val p = fsPlugin(seams)
    for
      _ <- p.rebuildRoutes(ctx)
      _ <- p.intake(ctx, feishuInbound("oc_a", "取消问题"))
      cancels <- ctx.cancelled.get
      injected <- ctx.injected.get
    yield
      assertEquals(cancels, Nil, "mcp 卡被算成了可取消问卡")
      assertEquals(injected.map(_._2), List("取消问题"), "零问时未按聊天透传")
    end for
  }

  private def feishuInbound(chatId: String, text: String): FeishuMessage.Inbound =
    FeishuMessage.inboundFrom(
      eventId = Some("evt-1"),
      messageId = Some("om-1"),
      chatId = Some(chatId),
      messageType = Some("text"),
      contentRaw = Some(Json.obj("text" -> text.asJson).noSpaces),
      createTime = None,
      senderId = Some("ou_author"),
      parentId = None,
      rootId = None,
      mentions = Nil
    ).toOption.get

end AskCancelChannelSpec
