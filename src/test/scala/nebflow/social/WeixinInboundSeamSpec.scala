package nebflow.social

import cats.effect.IO
import cats.effect.Ref
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.bridge.{BridgeAttachment, BridgeContext, BridgeOrigin}
import nebflow.shared.SessionMeta

/**
 * 微信入站静默死亡批（2026-11-07）— the INBOUND SEAM contract, driven with the
 * side-car's REAL wire frames.
 *
 * Why this spec exists: the author's report was 「我微信发消息你怎么收不到了」.
 * The field evidence put the loss on the side-car's poll loop (see the batch
 * report), but the排障 order also demanded proof that the ENGINE seam still
 * carries a plain-text frame — the 2026-10-20 media batch renamed the injection
 * seam (`injectMessage` → `injectMessageWithMedia`) and a mis-wired rename is
 * the canonical way a pure-text leg dies while the media leg looks fine (the
 * historical `{atts}` / `attachments` destructuring mismatch accident). This
 * spec IS that proof, executable and offline: it feeds the exact JSON bodies
 * `tools/weixin-sidecar/msgloop.mjs` `toInboundBody` emits, through the real
 * `parseInbound` → `deliver` → `intake` chain, into a recording BridgeContext.
 *
 * Pinned:
 *   WS-1 纯文本入站帧 → 必须到达 agent（`injectMessage` 收到原文 + 来源标记；
 *        绝不返回 no-text-body，绝不静默丢弃）。
 *   WS-2 带图入站帧 → 必须走媒体缝（`injectMessageWithMedia` + 本地指针三元组），
 *        而不是降级成纯文本，也绝不被静默丢弃。
 *   WS-3 每条入站帧都有显式裁决（Injected / Dropped(reason)）——没有任何一条
 *        路径能「既没注入也没理由」地消失。
 *   WS-4 旧引擎/旧 sidecar 字段兼容：`from_user_id`/`message_id` 旧拼写与
 *        `senderId`/`messageId` 新拼写解析等价（跨版本桥帧不得因改名掉消息）。
 */
class WeixinInboundSeamSpec extends CatsEffectSuite:

  private def tmpRoot(): os.Path = os.temp.dir(prefix = "nb-weixin-seam-")

  private def meta(id: String, sender: String): SessionMeta =
    SessionMeta(
      id = id,
      name = id,
      createdAt = 0L,
      updatedAt = 0L,
      hasUnread = false,
      bridges = Map(WeixinIlinkBridgePlugin.Name -> Json.obj("ilink_user_id" -> Json.fromString(sender)))
    )

  /**
   * Recording context: the two injection seams are SEPARATE recorders, so a
   * "landed on the wrong seam" defect is red by construction.
   */
  private final class SeamCtx(sessions: List[SessionMeta]) extends BridgeContext:
    val textSeam: Ref[IO, List[(String, String, Option[String], Option[BridgeOrigin])]] =
      Ref.unsafe(Nil)
    val mediaSeam: Ref[IO, List[(String, String, List[BridgeAttachment])]] = Ref.unsafe(Nil)

    def injectMessage(
      sessionId: String,
      content: String,
      senderId: Option[String],
      origin: Option[BridgeOrigin] = None
    ): IO[Unit] = textSeam.update(_ :+ ((sessionId, content, senderId, origin)))

    override def injectMessageWithMedia(
      sessionId: String,
      content: String,
      senderId: Option[String],
      origin: Option[BridgeOrigin] = None,
      attachments: List[BridgeAttachment] = Nil
    ): IO[Unit] = mediaSeam.update(_ :+ ((sessionId, content, attachments)))

    def interruptAgent(sessionId: String): IO[Unit] = IO.unit
    def sessionMeta(sessionId: String): IO[Option[SessionMeta]] = IO.none
    def listSessions: IO[List[SessionMeta]] = IO.pure(sessions)
    def updateBridgeConfig(sessionId: String, platform: String, config: Option[Json]): IO[Unit] = IO.unit

  private def plugin(): WeixinIlinkBridgePlugin =
    new WeixinIlinkBridgePlugin(tmpRoot(), pinnedAllowedIlinkUserIds = Some(Nil), rootSessionGate = Some(_ => true))

  /** The route's parse step, lifted into IO (a parse failure IS a test failure). */
  private def parse(body: Json): IO[WeixinIlinkBridgePlugin.Inbound] =
    IO.fromEither(WeixinIlinkBridgePlugin.parseInbound(body).left.map(new RuntimeException(_)))

  /** The side-car's wire body for a plain text message (msgloop toInboundBody). */
  private def textFrame(sender: String, text: String, id: String): Json =
    Json.obj("senderId" -> sender.asJson, "text" -> text.asJson, "messageId" -> id.asJson)

  /** The side-car's wire body for a landed image (the four media fields). */
  private def imageFrame(sender: String, path: String, name: String, id: String): Json =
    Json.obj(
      "senderId" -> sender.asJson,
      "text" -> "[图片]".asJson,
      "messageId" -> id.asJson,
      "mediaPath" -> path.asJson,
      "mediaKind" -> "image".asJson,
      "mediaName" -> name.asJson,
      "mediaMime" -> "image/jpeg".asJson
    )

  // ───────────────────────── WS-1 纯文本帧必达 ─────────────────────────

  test("WS-1 a plain-text inbound frame reaches the agent (and never as a silent drop)") {
    val c = new SeamCtx(List(meta("s1", "wx_alice")))
    val p = plugin()
    for
      _ <- p.start(c)
      in <- parse(textFrame("wx_alice", "在吗", "m1"))
      v <- p.deliver(in)
      text <- c.textSeam.get
      media <- c.mediaSeam.get
    yield
      assertEquals(v, WeixinIlinkBridgePlugin.Verdict.Injected("s1"), "a text frame is injected, never dropped")
      assertEquals(text.length, 1, "exactly one text injection")
      val (session, content, sender, origin) = text.head
      assertEquals(session, "s1")
      assertEquals(sender, Some("wx_alice"))
      // 两个面由网关唯一注入点分离（BridgeOrigin.faces）：渠道只交原文 + 来源描述，
      // 「<system-reminder> 以下消息来自…」标记是网关注入点按 origin 合成的——所以
      // 这里断言原文逐字到达，且来源描述确实在场（标记本身在网关侧，spec 不重复造）。
      assertEquals(origin.map(_.channelId), Some(WeixinIlinkBridgePlugin.Name), "the source is announced to the gateway")
      assertEquals(content, "在吗", s"the body must reach the agent verbatim, got: $content")
      assertEquals(media, Nil, "a text frame must NOT touch the media seam")
  }

  // ───────────────────────── WS-2 带图帧走媒体缝 ─────────────────────────

  test("WS-2 a landed-image inbound frame rides the media seam with its local pointer") {
    val root = tmpRoot()
    val img = root / "m2.jpg"
    os.write.over(img, "not-a-real-jpeg-but-a-real-file")
    val c = new SeamCtx(List(meta("s1", "wx_alice")))
    val p = plugin()
    for
      _ <- p.start(c)
      in <- parse(imageFrame("wx_alice", img.toString, "IMG_1.jpg", "m2"))
      v <- p.deliver(in)
      text <- c.textSeam.get
      media <- c.mediaSeam.get
    yield
      assertEquals(v, WeixinIlinkBridgePlugin.Verdict.Injected("s1"), "a media frame is injected, never dropped")
      assertEquals(text, Nil, "a landed image must not degrade to the text seam")
      assertEquals(media.length, 1, "exactly one media injection")
      val (session, content, attachments) = media.head
      assertEquals(session, "s1")
      assertEquals(attachments.length, 1)
      assertEquals(attachments.head.path, img.toString, "the pointer is the side-car's local path")
      assertEquals(attachments.head.name, Some("IMG_1.jpg"))
      assertEquals(attachments.head.mime, Some("image/jpeg"))
      assertEquals(content, "", "the side-car placeholder label is filtered — the caption slot stays empty")
  }

  // ───────────────────────── WS-3 没有静默丢弃 ─────────────────────────

  test("WS-3 every inbound frame yields an explicit verdict (no path vanishes without a reason)") {
    val root = tmpRoot()
    val img = root / "m3.png"
    os.write.over(img, "x")
    val frames = List(
      "text" -> textFrame("wx_alice", "hello", "m3"),
      "image" -> imageFrame("wx_alice", img.toString, "a.png", "m4"),
      // a media pointer whose file died between landing and injection: MUST still
      // be delivered (placeholder degrade), never a silent loss
      "image-missing" -> imageFrame("wx_alice", (root / "gone.jpg").toString, "gone.jpg", "m5"),
      // an old side-car frame with no text at all
      "no-text" -> Json.obj("senderId" -> "wx_alice".asJson, "messageId" -> "m6".asJson)
    )
    val c = new SeamCtx(List(meta("s1", "wx_alice")))
    val p = plugin()
    for
      _ <- p.start(c)
      results <- frames.traverse[IO, (String, WeixinIlinkBridgePlugin.Verdict)] { case (label, body) =>
        parse(body).flatMap(p.deliver).map(label -> _)
      }
      text <- c.textSeam.get
      media <- c.mediaSeam.get
    yield
      results.foreach { case (label, v) =>
        v match
          case WeixinIlinkBridgePlugin.Verdict.Injected(_) => ()
          case WeixinIlinkBridgePlugin.Verdict.Dropped(r) =>
            assert(r.nonEmpty, s"$label: a drop must carry a reason")
            // Only the deliberately text-less "no-text" frame may answer with
            // no-text-body: a frame that DID carry text must never be dropped
            // under that reason (the canonical "改缝后纯文本也被判废" regression).
            if label != "no-text" then
              assertNotEquals(r, WeixinIlinkBridgePlugin.ReasonNoText, s"$label: a text-bearing frame must not be dropped as $r")
          case other => fail(s"$label: unexpected verdict $other")
      }
      assertEquals(
        text.length + media.length,
        results.count(_._2.isInstanceOf[WeixinIlinkBridgePlugin.Verdict.Injected]),
        "nothing was swallowed: every injected frame landed on exactly one seam"
      )
      val byLabel = results.toMap
      assert(byLabel("text").isInstanceOf[WeixinIlinkBridgePlugin.Verdict.Injected], "text frame injected")
      assert(byLabel("image").isInstanceOf[WeixinIlinkBridgePlugin.Verdict.Injected], "image frame injected")
      assert(
        byLabel("image-missing").isInstanceOf[WeixinIlinkBridgePlugin.Verdict.Injected],
        "a vanished media pointer degrades to the placeholder and is STILL delivered"
      )
      assertEquals(byLabel("no-text"), WeixinIlinkBridgePlugin.Verdict.Dropped(WeixinIlinkBridgePlugin.ReasonNoText))
  }

  // ───────────────────────── WS-4 跨版本字段兼容 ─────────────────────────

  test("WS-4 legacy field spellings (from_user_id / message_id) parse identically") {
    val legacy = Json.obj(
      "from_user_id" -> "wx_alice".asJson,
      "text" -> "老版本帧".asJson,
      "message_id" -> "m7".asJson
    )
    val modern = Json.obj("senderId" -> "wx_alice".asJson, "text" -> "老版本帧".asJson, "messageId" -> "m7".asJson)
    for
      a <- parse(legacy)
      b <- parse(modern)
    yield
      assertEquals(a.senderId, b.senderId)
      assertEquals(a.messageId, b.messageId)
      assertEquals(a.text, b.text)
      assertEquals(a.routeKey, "wx_alice")
  }
