package nebflow.social

import cats.effect.IO
import cats.effect.Ref
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.bridge.{AskAnswerReceipt, BridgeContext}
import nebflow.shared.SessionMeta
import nebflow.social.outbound.{AskPromptCodec, PromptView}

/**
 * Offline spec for the weixin numbered-reply ask legs (askuser-remote P1,
 * 2026-10-07, 卡 B / 拍板 B-1…B-4; askuser-media-split batch additions pinned
 * at the bottom). Pins:
 *
 *   - the numbered text card render (frozen-copy reuse + the NEW footer line;
 *     no 「其他…」 option line — free text is the 0/他 PREFIX leg);
 *   - the reply parser (B-1 bare-number/问.编/comma-multi + B-2 prefix-only
 *     Other + the media-split exact-option-label arm with case/whitespace
 *     tolerance + out-of-range refusals);
 *   - the intake splitter: the ask legs sit BETWEEN the allowlist gate and
 *     the injection arms; non-ask text falls through byte-identically;
 *   - B-3: a new ask retires the previous OPEN one by precise CloseRequest
 *     and takes the slot; askUserClosed clears it; an answered tombstone
 *     refuses a repeat read of the numbers (never a second injection);
 *   - B-4 案甲: a >4000-char reply degrades to 摘要+拉取指令 with the full
 *     text on a 「全文」 pull, segmented within the first tier;
 *   - media split (askuser-media-split batch): existing attachments ride the
 *     MEDIA seam BEFORE the text card (image-table ext → image_path, else
 *     file_path); delivered attachments never repeat as 附件路径 lines — only
 *     undelivered ones (missing file / failed send) keep theirs.
 *
 * Mutation safety: every gate below is red-by-construction if the matching
 * decision is removed — the gate-first ordering (an ask leg before the
 * allowlist gate would answer a non-member), the fall-through (a chat text
 * that got eaten would fail WI-C1), the tombstone refusal (a second injection
 * would double-fire), and the pull-slot clear.
 */
class WeixinAskLegSpec extends CatsEffectSuite:

  private def tmpRoot(): os.Path = os.temp.dir(prefix = "nb-weixin-ask-")

  /**
   * Recording context: captures injections, the forwarded hub answers and
   * the precise close requests — the three hub faces the ask legs use.
   */
  private final class AskCtx(sessions: List[SessionMeta] = Nil) extends BridgeContext:
    val injected: Ref[IO, List[(String, String)]] = Ref.unsafe[IO, List[(String, String)]](Nil)
    val forwarded: Ref[IO, List[(String, String, Json)]] = Ref.unsafe[IO, List[(String, String, Json)]](Nil)
    val closed: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)

    def injectMessage(
      sessionId: String,
      content: String,
      senderId: Option[String],
      origin: Option[nebflow.bridge.BridgeOrigin] = None
    ): IO[Unit] =
      injected.update(_ :+ ((sessionId, content)))
    override def listPendingAsks: IO[List[Json]] = IO.pure(Nil)

    override def forwardInteractionAnswer(requestId: String, rootSessionId: String, payload: Json): IO[Unit] =
      forwarded.update(_ :+ ((requestId, rootSessionId, payload)))
    override def closeInteractionRequest(requestId: String): IO[Unit] = closed.update(_ :+ requestId)
    def interruptAgent(sessionId: String): IO[Unit] = IO.unit
    def sessionMeta(sessionId: String): IO[Option[SessionMeta]] = IO.none
    def listSessions: IO[List[SessionMeta]] = IO.pure(sessions)
    def updateBridgeConfig(sessionId: String, platform: String, config: Option[Json]): IO[Unit] = IO.unit
  end AskCtx

  /** Recording outbound seam — every text the bridge would hand the side-car. */
  private final class Out:
    val sent: Ref[IO, List[(String, String)]] = Ref.unsafe[IO, List[(String, String)]](Nil)
    val fail: Ref[IO, Boolean] = Ref.unsafe[IO, Boolean](false)

    val seam: WeixinIlinkBridgePlugin.Send = (to, text) =>
      sent.update(_ :+ ((to, text))).flatMap { _ =>
        fail.get.map(f =>
          WeixinOutboundAdapter.SendResult(
            ok = !f,
            delivered = !f,
            Some("m-1"),
            if f then 500 else 200,
            if f then "delivery_failed: test" else "delivered"
          )
        )
      }

  end Out

  /**
   * Recording MEDIA seam (askuser-media-split batch) — every media payload the
   * bridge would hand the side-car, with a flippable ok.
   */
  private final class MediaOut(ok: Boolean = true):

    val sent: Ref[IO, List[(String, WeixinOutboundAdapter.WeixinMedia)]] =
      Ref.unsafe[IO, List[(String, WeixinOutboundAdapter.WeixinMedia)]](Nil)

    val seam: WeixinIlinkBridgePlugin.SendMedia = (to, media) =>
      sent
        .update(_ :+ ((to, media)))
        .as(
          WeixinOutboundAdapter.SendResult(
            ok,
            delivered = ok,
            Some("md-1"),
            if ok then 200 else 500,
            if ok then "delivered" else "delivery_failed: test"
          )
        )

  end MediaOut

  private def meta(senderId: String): SessionMeta =
    SessionMeta(
      id = "s1",
      name = "s1",
      createdAt = 0L,
      updatedAt = 0L,
      hasUnread = false,
      bridges = Map(WeixinIlinkBridgePlugin.Name -> Json.obj("ilink_user_id" -> senderId.asJson))
    )

  private def started(out: Out, sessions: List[SessionMeta]): IO[(WeixinIlinkBridgePlugin, AskCtx)] =
    val ctx = AskCtx(sessions)
    val p = new WeixinIlinkBridgePlugin(tmpRoot(), pinnedAllowedIlinkUserIds = None, send = _ => out.seam)
    p.start(ctx).as((p, ctx))

  /** A hub-shaped askUser frame (two questions, the first multi-select). */
  private def askFrame(rid: String, root: String, items: List[Json], attachments: List[String] = Nil): Json =
    val fields = scala.collection.mutable.ListBuffer[(String, Json)](
      "type" -> "askUser".asJson,
      "sessionId" -> root.asJson,
      "requestId" -> rid.asJson,
      "items" -> Json.fromValues(items)
    )
    if attachments.nonEmpty then fields += "attachments" -> attachments.asJson
    Json.obj(fields.toList*)

  private def itemJson(
    question: String,
    labels: List[String],
    multiple: Boolean = false,
    id: Option[String] = None,
    dependsOnRef: Option[String] = None
  ): Json =
    val base = scala.collection.mutable.ListBuffer[(String, Json)](
      "question" -> question.asJson,
      "options" -> Json.fromValues(labels.map(l => Json.obj("label" -> l.asJson))),
      "allowOther" -> true.asJson
    )
    if multiple then base += "multiple" -> true.asJson
    id.foreach(i => base += "id" -> i.asJson)
    dependsOnRef.foreach(r => base += "dependsOn" -> Json.obj("ref" -> r.asJson, "equals" -> "x".asJson))
    Json.obj(base.toList*)
  end itemJson

  private def simpleItems: List[Json] = List(itemJson("用哪个?", List("甲", "乙", "丙")))

  // ───────────────────────── the render ─────────────────────────

  test("WA-R1 the numbered card: frozen question/option shapes + NEW footer, no 「其他…」 line") {
    val (text, truncated) = WeixinAskLeg.renderAskText(
      List(
        PromptView(
          0,
          "用哪个?",
          nebflow.social.outbound.PromptOption("甲") :: nebflow.social.outbound.PromptOption("乙") :: Nil
        )
      ),
      List("/tmp/a.png")
    )
    assert(text.startsWith("第 1/1 问 用哪个?\n  1) 甲\n  2) 乙"), s"head+numbered options: $text")
    assert(text.contains(nebflow.social.outbound.AskPromptCodec.AttachmentLineHead + "\n- /tmp/a.png"))
    assert(text.contains(FeishuAskCard.DegradeFoot))
    assert(text.endsWith(WeixinAskLeg.NumberFooter))
    assert(!text.contains(FeishuAskCard.OtherCopy), s"no 其他… option line on the weixin card: $text")
    assertEquals(truncated, false)
  }

  test("WA-R2 the truncated form cuts the question TEXT only, keeps numbering+footer") {
    val long = "长" * 5000
    val (text, truncated) = WeixinAskLeg.renderAskTextTruncated(
      List(PromptView(0, long, List(nebflow.social.outbound.PromptOption("甲")))),
      Nil,
      questionBudget = 3200
    )
    assert(truncated)
    assert(text.contains(WeixinAskLeg.TruncatedMark))
    assert(text.length < long.length)
    assert(text.endsWith(WeixinAskLeg.NumberFooter))
    assert(text.contains("  1) 甲"))
  }

  test("WA-R3 segments: each within the first tier, order-marked from the second on") {
    val text = "x" * (WeixinAskLeg.FullTextThreshold * 2 + 100)
    val segs = WeixinAskLeg.segments(text)
    assertEquals(segs.size, 3)
    assert(!segs.head.startsWith("（"), "the first segment carries no order head")
    assert(segs(1).startsWith(WeixinAskLeg.segmentHead(2, 3) + "\n"))
    assert(segs.forall(s => s.length <= WeixinAskLeg.FullTextThreshold + 12))
  }

  // ───────────────────────── the reply parser ─────────────────────────

  private val one = List(
    PromptView(
      0,
      "单选?",
      List(
        nebflow.social.outbound.PromptOption("甲"),
        nebflow.social.outbound.PromptOption("乙"),
        nebflow.social.outbound.PromptOption("丙")
      )
    )
  )

  private val multiOne = List(
    PromptView(
      0,
      "多选?",
      List(
        nebflow.social.outbound.PromptOption("甲"),
        nebflow.social.outbound.PromptOption("乙"),
        nebflow.social.outbound.PromptOption("丙")
      ),
      multiple = true
    )
  )

  private val twoQuestions = List(
    PromptView(0, "一?", List(nebflow.social.outbound.PromptOption("甲"), nebflow.social.outbound.PromptOption("乙"))),
    PromptView(1, "二?", List(nebflow.social.outbound.PromptOption("丙"), nebflow.social.outbound.PromptOption("丁")))
  )

  test("WA-P1 bare number = the FIRST question's option (B-1)") {
    assertEquals(WeixinAskLeg.parseReply("2", one), Right(Some(List(nebflow.social.outbound.PromptAction.Pick(0, 1)))))
  }

  test("WA-P2 comma list on a multiple question → the picks in reply order") {
    assertEquals(
      WeixinAskLeg.parseReply("1,3", multiOne),
      Right(
        Some(List(nebflow.social.outbound.PromptAction.Pick(0, 0), nebflow.social.outbound.PromptAction.Pick(0, 2)))
      )
    )
  }

  test("WA-P3 comma list on a SINGLE question is refused, never clamped") {
    assertEquals(WeixinAskLeg.parseReply("1,3", one), Left(WeixinAskLeg.SinglePickReject))
    assertEquals(WeixinAskLeg.SinglePickReject.line, WeixinAskLeg.SinglePickOnlyLine)
  }

  test("WA-P4 问.编 explicit addressing (B-1 兜底)") {
    assertEquals(
      WeixinAskLeg.parseReply("2.1", twoQuestions),
      Right(Some(List(nebflow.social.outbound.PromptAction.Pick(1, 0))))
    )
    assertEquals(WeixinAskLeg.parseReply("2.9", twoQuestions), Left(WeixinAskLeg.RangeReject))
    assertEquals(WeixinAskLeg.parseReply("3.1", twoQuestions), Left(WeixinAskLeg.RangeReject))
  }

  test("WA-P5 the 0/他 PREFIX is the only free-text leg (B-2); bare text is chat") {
    assertEquals(
      WeixinAskLeg.parseReply("他 自己写一个", one),
      Right(Some(List(nebflow.social.outbound.PromptAction.Other(0, "自己写一个"))))
    )
    assertEquals(
      WeixinAskLeg.parseReply("0 自己写一个", one),
      Right(Some(List(nebflow.social.outbound.PromptAction.Other(0, "自己写一个"))))
    )
    assertEquals(WeixinAskLeg.parseReply("你好", one), Right(None))
    assertEquals(WeixinAskLeg.parseReply("全文", one), Right(None))
    assertEquals(WeixinAskLeg.parseReply("0", one), Left(WeixinAskLeg.RangeReject))
    assertEquals(WeixinAskLeg.RangeReject.line, WeixinAskLeg.RangeErrorLine)
  }

  test("WA-P6 out-of-range numbers are refused with the ONE readable line") {
    assertEquals(WeixinAskLeg.parseReply("9", one), Left(WeixinAskLeg.RangeReject))
  }

  // ───────── media-split batch: the exact-option-label arm (C2) ─────────

  test("WA-P7 exact option label answers: case/whitespace tolerant; not-number-shaped; non-label text stays chat") {
    val labeled = List(
      PromptView(
        0,
        "继续吗?",
        List(nebflow.social.outbound.PromptOption("继续 执行"), nebflow.social.outbound.PromptOption("取消"))
      ),
      PromptView(1, "选谁?", List(nebflow.social.outbound.PromptOption("等我"), nebflow.social.outbound.PromptOption("别人")))
    )
    // exact match
    assertEquals(
      WeixinAskLeg.parseReply("取消", labeled),
      Right(Some(List(nebflow.social.outbound.PromptAction.Pick(0, 1))))
    )
    // surrounding + internal whitespace tolerance (incl. the full-width space)
    assertEquals(
      WeixinAskLeg.parseReply("  继续 执行  ", labeled),
      Right(Some(List(nebflow.social.outbound.PromptAction.Pick(0, 0))))
    )
    assertEquals(
      WeixinAskLeg.parseReply("继续执行", labeled),
      Right(Some(List(nebflow.social.outbound.PromptAction.Pick(0, 0))))
    )
    assertEquals(
      WeixinAskLeg.parseReply("继 续　执 行", labeled),
      Right(Some(List(nebflow.social.outbound.PromptAction.Pick(0, 0))))
    )
    // ASCII case tolerance
    assertEquals(
      WeixinAskLeg
        .parseReply("continue", List(PromptView(0, "q?", List(nebflow.social.outbound.PromptOption("Continue"))))),
      Right(Some(List(nebflow.social.outbound.PromptAction.Pick(0, 0))))
    )
    // a duplicate label resolves to the FIRST question carrying it (B-1 rule)
    val dup = List(
      PromptView(0, "一?", List(nebflow.social.outbound.PromptOption("甲"))),
      PromptView(1, "二?", List(nebflow.social.outbound.PromptOption("甲"), nebflow.social.outbound.PromptOption("乙")))
    )
    assertEquals(WeixinAskLeg.parseReply("甲", dup), Right(Some(List(nebflow.social.outbound.PromptAction.Pick(0, 0)))))
    assertEquals(WeixinAskLeg.parseReply("乙", dup), Right(Some(List(nebflow.social.outbound.PromptAction.Pick(1, 1)))))
    // number-shaped text NEVER reaches the label arm — a label that looks like
    // a number is still interpreted as a number (range refusal here)
    assertEquals(
      WeixinAskLeg.parseReply("9", List(PromptView(0, "q?", List(nebflow.social.outbound.PromptOption("9"))))),
      Left(WeixinAskLeg.RangeReject)
    )
    // non-label free text stays chat (不误吞)
    assertEquals(WeixinAskLeg.parseReply("你好", labeled), Right(None))
    // the 0/他 prefix keeps precedence over the label arm
    assertEquals(
      WeixinAskLeg.parseReply("他 取消也不干", labeled),
      Right(Some(List(nebflow.social.outbound.PromptAction.Other(0, "取消也不干"))))
    )
  }

  // ───────────────────────── the intake splitter ─────────────────────────

  test("WI-A1 open ask + bare number → Answered verdict, fixed-length slots forwarded, tombstoned") {
    val out = Out()
    for
      (p, ctx) <- started(out, List(meta("wx_a")))
      _ <- p.onAgentEvent("s1", askFrame("rid-1", "s1", simpleItems))
      _ <- out.sent.get.map(s => assertEquals(s.size, 1)) // the numbered card went out
      v <- p.intake(ctx, WeixinIlinkBridgePlugin.Inbound(Some("wx_a"), Some("2"), Some("m1")))
      fw <- ctx.forwarded.get
      injected <- ctx.injected.get
    yield
      assertEquals(v, WeixinIlinkBridgePlugin.Verdict.Answered("rid-1"))
      assertEquals(fw, List(("rid-1", "s1", Json.obj("answers" -> List("乙").asJson))))
      assertEquals(injected, List.empty) // the digits never became a chat message
    end for
  }

  test("WI-A2 a repeat read of the numbers AFTER an answer is refused (never re-injected)") {
    val out = Out()
    for
      (p, ctx) <- started(out, List(meta("wx_a")))
      _ <- p.onAgentEvent("s1", askFrame("rid-1", "s1", simpleItems))
      _ <- p.intake(ctx, WeixinIlinkBridgePlugin.Inbound(Some("wx_a"), Some("2"), Some("m1")))
      sentBefore <- out.sent.get
      v2 <- p.intake(ctx, WeixinIlinkBridgePlugin.Inbound(Some("wx_a"), Some("2"), Some("m2")))
      sentAfter <- out.sent.get
    yield
      assertEquals(v2, WeixinIlinkBridgePlugin.Verdict.Dropped(WeixinIlinkBridgePlugin.ReasonAskAnswered))
      assertEquals(sentAfter.size, sentBefore.size + 1)
      assertEquals(sentAfter.last._2, FeishuAskCard.ToastAlreadyAnswered) // the S-8 line, verbatim
    end for
  }

  test("WI-A3 out-of-range on an open ask → ONE refusal line, ask stays, nothing injected") {
    val out = Out()
    for
      (p, ctx) <- started(out, List(meta("wx_a")))
      _ <- p.onAgentEvent("s1", askFrame("rid-1", "s1", simpleItems))
      v <- p.intake(ctx, WeixinIlinkBridgePlugin.Inbound(Some("wx_a"), Some("9"), Some("m1")))
      sent <- out.sent.get
    yield
      assertEquals(v, WeixinIlinkBridgePlugin.Verdict.Dropped(WeixinIlinkBridgePlugin.ReasonAskRange))
      assertEquals(sent.last._2, WeixinAskLeg.RangeErrorLine)
    end for
  }

  test("WI-A4 chat text on an open ask falls through untouched (B-2 拍板)") {
    val out = Out()
    for
      (p, ctx) <- started(out, List(meta("wx_a")))
      _ <- p.onAgentEvent("s1", askFrame("rid-1", "s1", simpleItems))
      v <- p.intake(ctx, WeixinIlinkBridgePlugin.Inbound(Some("wx_a"), Some("今天天气不错"), Some("m1")))
      injected <- ctx.injected.get
    yield
      assertEquals(v, WeixinIlinkBridgePlugin.Verdict.Injected("s1"))
      assertEquals(injected, List(("s1", "今天天气不错")))
    end for
  }

  test("WI-A5 the 他 prefix answers even with an open ask (B-2)") {
    val out = Out()
    for
      (p, ctx) <- started(out, List(meta("wx_a")))
      _ <- p.onAgentEvent("s1", askFrame("rid-1", "s1", simpleItems))
      v <- p.intake(ctx, WeixinIlinkBridgePlugin.Inbound(Some("wx_a"), Some("他 都不行"), Some("m1")))
      fw <- ctx.forwarded.get
    yield
      assertEquals(v, WeixinIlinkBridgePlugin.Verdict.Answered("rid-1"))
      assertEquals(fw.map(_._3), List(Json.obj("answers" -> List("都不行").asJson)))
    end for
  }

  test("WI-A6 no open ask → digits are plain chat (the splitter never fires)") {
    val out = Out()
    for
      (p, ctx) <- started(out, List(meta("wx_a")))
      v <- p.intake(ctx, WeixinIlinkBridgePlugin.Inbound(Some("wx_a"), Some("2"), Some("m1")))
      injected <- ctx.injected.get
    yield
      assertEquals(v, WeixinIlinkBridgePlugin.Verdict.Injected("s1"))
      assertEquals(injected, List(("s1", "2")))
    end for
  }

  test("WI-A7 the gate rules the ask legs: a non-member's digits are dropped BEFORE any ask leg") {
    val out = Out()
    val ctx = AskCtx(List(meta("wx_a")))
    val p = new WeixinIlinkBridgePlugin(tmpRoot(), pinnedAllowedIlinkUserIds = Some(List("wx_a")), send = _ => out.seam)
    for
      _ <- p.start(ctx)
      _ <- p.onAgentEvent("s1", askFrame("rid-1", "s1", simpleItems))
      v <- p.intake(ctx, WeixinIlinkBridgePlugin.Inbound(Some("wx_b"), Some("2"), Some("m1")))
      fw <- ctx.forwarded.get
      sent <- out.sent.get.map(_.filter(_._1 == "wx_b"))
    yield
      assertEquals(v, WeixinIlinkBridgePlugin.Verdict.Dropped(WeixinIlinkBridgePlugin.ReasonNotOnAllowlist))
      assertEquals(fw, List.empty)
      assertEquals(sent, List.empty)
    end for
  }

  test("WI-B1 B-3 top-old: a new ask retires the previous OPEN one by precise CloseRequest") {
    val out = Out()
    for
      (p, ctx) <- started(out, List(meta("wx_a")))
      _ <- p.onAgentEvent("s1", askFrame("rid-1", "s1", simpleItems))
      _ <- p.onAgentEvent("s1", askFrame("rid-2", "s1", simpleItems))
      closed <- ctx.closed.get
      v <- p.intake(ctx, WeixinIlinkBridgePlugin.Inbound(Some("wx_a"), Some("1"), Some("m1")))
      fw <- ctx.forwarded.get
    yield
      assertEquals(closed, List("rid-1")) // precise, never the session-wide sweep
      assertEquals(v, WeixinIlinkBridgePlugin.Verdict.Answered("rid-2")) // the NEW ask answers
      assertEquals(fw.map(_._1), List("rid-2"))
    end for
  }

  test("WI-B2 askUserClosed clears the slot; a later number is chat again") {
    val out = Out()
    for
      (p, ctx) <- started(out, List(meta("wx_a")))
      _ <- p.onAgentEvent("s1", askFrame("rid-1", "s1", simpleItems))
      _ <- p.onAgentEvent(
        "s1",
        Json.obj("type" -> "askUserClosed".asJson, "sessionId" -> "s1".asJson, "requestId" -> "rid-1".asJson)
      )
      v <- p.intake(ctx, WeixinIlinkBridgePlugin.Inbound(Some("wx_a"), Some("2"), Some("m1")))
      injected <- ctx.injected.get
    yield
      assertEquals(v, WeixinIlinkBridgePlugin.Verdict.Injected("s1"))
      assertEquals(injected, List(("s1", "2")))
    end for
  }

  test(
    "WI-B3 media split: existing attachments ride the MEDIA seam FIRST; the text card keeps only undelivered path lines"
  ) {
    val out = Out()
    val mout = MediaOut()
    val root = tmpRoot()
    val png = root / "shot.png"; os.write(png, "png-bytes")
    val doc = root / "notes.md"; os.write(doc, "md-bytes")
    val ctx = AskCtx(List(meta("wx_a")))
    val p = new WeixinIlinkBridgePlugin(
      root,
      pinnedAllowedIlinkUserIds = None,
      send = _ => out.seam,
      sendMedia = _ => mout.seam
    )
    for
      _ <- p.start(ctx)
      _ <- p.onAgentEvent(
        "s1",
        askFrame("rid-1", "s1", simpleItems, attachments = List(png.toString, doc.toString, " /gone/missing.md "))
      )
      media <- mout.sent.get
      texts <- out.sent.get
    yield
      // media FIRST, in list order: image-table ext → image_path, else
      // file_path; the missing path never reaches the seam
      assertEquals(media.map(_._1), List("wx_a", "wx_a"))
      assertEquals(media(0)._2.imagePath.map(_.trim), Some(png.toString))
      assertEquals(media(1)._2.filePath.map(_.trim), Some(doc.toString))
      assertEquals(texts.size, 1, "exactly the ask text follows the media")
      val card = texts.head._2
      assert(card.contains("第 1/1 问 用哪个?"))
      assert(card.contains(WeixinAskLeg.NumberFooter))
      assert(
        !card.contains("shot.png") && !card.contains("notes.md"),
        "delivered attachments must not repeat as path lines"
      )
      assert(
        card.contains("附件路径：") && card.contains("/gone/missing.md"),
        "the undelivered path stays visible (A6 degrade)"
      )
    end for
  }

  test("WI-B3a media split degrade: a FAILED media send keeps its path line in the text card") {
    val out = Out()
    val mout = MediaOut(ok = false)
    val root = tmpRoot()
    val png = root / "shot.png"; os.write(png, "png-bytes")
    val ctx = AskCtx(List(meta("wx_a")))
    val p = new WeixinIlinkBridgePlugin(
      root,
      pinnedAllowedIlinkUserIds = None,
      send = _ => out.seam,
      sendMedia = _ => mout.seam
    )
    for
      _ <- p.start(ctx)
      _ <- p.onAgentEvent("s1", askFrame("rid-1", "s1", simpleItems, attachments = List(png.toString)))
      media <- mout.sent.get
      texts <- out.sent.get
    yield
      assertEquals(media.size, 1, "the send WAS attempted")
      assertEquals(texts.size, 1)
      assert(texts.head._2.contains("附件路径："), "the failed attachment degrades to its path line")
      assert(texts.head._2.contains("shot.png"))
    end for
  }

  test("WI-B3b no attachments ⇒ zero media calls, the card is the pre-split shape") {
    val out = Out()
    val mout = MediaOut()
    val ctx = AskCtx(List(meta("wx_a")))
    val p = new WeixinIlinkBridgePlugin(
      tmpRoot(),
      pinnedAllowedIlinkUserIds = None,
      send = _ => out.seam,
      sendMedia = _ => mout.seam
    )
    for
      _ <- p.start(ctx)
      _ <- p.onAgentEvent("s1", askFrame("rid-1", "s1", simpleItems))
      media <- mout.sent.get
      texts <- out.sent.get
    yield
      assertEquals(media, List.empty)
      assertEquals(texts.size, 1)
      assert(texts.head._2.contains("第 1/1 问 用哪个?"))
      assert(!texts.head._2.contains("附件路径："))
    end for
  }

  test("WI-B4 D-1 on weixin: the unreachable question's slot stays \"\" in the forwarded answer") {
    val out = Out()
    for
      (p, ctx) <- started(out, List(meta("wx_a")))
      _ <- p.onAgentEvent(
        "s1",
        askFrame(
          "rid-1",
          "s1",
          List(
            itemJson("一?", List("甲"), id = Some("q1")),
            itemJson("不可达?", List("乙"), id = Some("q2"), dependsOnRef = Some("nope"))
          )
        )
      )
      v <- p.intake(ctx, WeixinIlinkBridgePlugin.Inbound(Some("wx_a"), Some("1"), Some("m1")))
      fw <- ctx.forwarded.get
    yield
      assertEquals(v, WeixinIlinkBridgePlugin.Verdict.Answered("rid-1"))
      assertEquals(fw.map(_._3), List(Json.obj("answers" -> List("甲", "").asJson)))
    end for
  }

  // ───────────────────────── B-4 案甲: the pull chain ─────────────────────────

  test("WI-P1 a >4000-char reply degrades to 摘要+拉取指令; the full text stays pullable") {
    val out = Out()
    for
      (p, ctx) <- started(out, List(meta("wx_a")))
      long = "好" * (WeixinAskLeg.FullTextThreshold + 500)
      _ <- p.onAgentEvent("s1", Json.obj("type" -> "textDelta".asJson, "delta" -> long.asJson))
      _ <- p.onAgentEvent("s1", Json.obj("type" -> "done".asJson))
      sent <- out.sent.get
      v0 <- p.intake(ctx, WeixinIlinkBridgePlugin.Inbound(Some("wx_a"), Some("随便聊聊"), Some("m0")))
      v <- p.intake(ctx, WeixinIlinkBridgePlugin.Inbound(Some("wx_a"), Some("全文"), Some("m1")))
      sentAfter <- out.sent.get
      v2 <- p.intake(ctx, WeixinIlinkBridgePlugin.Inbound(Some("wx_a"), Some("全文"), Some("m2")))
    yield
      // The flush: preview (4000) + the hint — NOT the whole text.
      assertEquals(sent.size, 1)
      val head = sent.head._2
      assertEquals(head.length <= WeixinAskLeg.FullTextThreshold + 120, true)
      assert(head.contains(WeixinAskLeg.fullTextHint(long.length, 2).take(12)))
      // Other chat still falls through while a pull is stashed.
      assertEquals(v0, WeixinIlinkBridgePlugin.Verdict.Injected("s1"))
      // The pull: 2 segments, then the slot is gone.
      assertEquals(v, WeixinIlinkBridgePlugin.Verdict.Pulled(2))
      val pulled = sentAfter.drop(1).map(_._2)
      assertEquals(pulled.size, 2)
      assert(pulled(0).length >= WeixinAskLeg.FullTextThreshold - 1)
      assert(pulled(1).startsWith(WeixinAskLeg.segmentHead(2, 2)))
      // The slot is consumed: a second 全文 is chat again.
      assertEquals(v2, WeixinIlinkBridgePlugin.Verdict.Injected("s1"))
    end for
  }

  test("WI-P2 a ≤4000-char reply is byte-identical to the pre-P1 flush") {
    val out = Out()
    for
      (p, _) <- started(out, List(meta("wx_a")))
      _ <- p.onAgentEvent("s1", Json.obj("type" -> "textDelta".asJson, "delta" -> "正常回复".asJson))
      _ <- p.onAgentEvent("s1", Json.obj("type" -> "done".asJson))
      sent <- out.sent.get
    yield assertEquals(sent, List(("wx_a", "正常回复")))
    end for
  }

  test("WI-P3 verdict json faces (the two new consumed outcomes)") {
    assertEquals(
      WeixinIlinkBridgePlugin.Verdict.Answered("r1").json,
      Json.obj("verdict" -> "answered".asJson, "requestId" -> "r1".asJson)
    )
    assertEquals(
      WeixinIlinkBridgePlugin.Verdict.Pulled(2).json,
      Json.obj("verdict" -> "full-text".asJson, "segments" -> 2.asJson)
    )
  }

  // ──────── answer-landing 批（2026-10-21）：短号自由文本臂（表2 优先级 4★） ────────

  private val secretOne =
    List(PromptView(0, "密钥?", List(nebflow.social.outbound.PromptOption("甲")), secret = true))

  test("WA-P8 short-number free-text arm: `N <text>` = question N's Other; out-of-range refused; secret fail-closed; the 0-prefix/comma legs unchanged") {
    // B-3 恒单问：`1 开放` ≡ `0 开放` 的带题号便捷形（单问集 N 恒 1）
    assertEquals(
      WeixinAskLeg.parseReply("1 开放", one),
      Right(Some(List(nebflow.social.outbound.PromptAction.Other(0, "开放"))))
    )
    // 多问在册（飞书文本腿形态）：`2 前进` 寻址第 2 题
    assertEquals(
      WeixinAskLeg.parseReply("2 前进", twoQuestions),
      Right(Some(List(nebflow.social.outbound.PromptAction.Other(1, "前进"))))
    )
    // N 超界拒不钳制；3 位宽之外的数字串不是编号形（落标签臂 ⇒ 聊天透传）
    assertEquals(WeixinAskLeg.parseReply("9 开放", one), Left(WeixinAskLeg.RangeReject))
    assertEquals(WeixinAskLeg.parseReply("999 开放", one), Left(WeixinAskLeg.RangeReject))
    assertEquals(WeixinAskLeg.parseReply("1000 开放", one), Right(None))
    // secret 题 fail-closed（同构既有三臂口径）
    assertEquals(WeixinAskLeg.parseReply("1 x", secretOne), Left(WeixinAskLeg.SecretReject))
    // 既有腿零回归：`0 文本` 前缀、逗号多选（含逗号后空格容忍）
    assertEquals(
      WeixinAskLeg.parseReply("0 自己写", one),
      Right(Some(List(nebflow.social.outbound.PromptAction.Other(0, "自己写"))))
    )
    assertEquals(
      WeixinAskLeg.parseReply("1, 3", multiOne),
      Right(
        Some(List(nebflow.social.outbound.PromptAction.Pick(0, 0), nebflow.social.outbound.PromptAction.Pick(0, 2)))
      )
    )
  }

  // ──────── answer-landing 批（2026-10-21）缺陷②：媒体消息永不进 ask 臂 ────────

  test("WI-A8 media message never enters the ask arm — a placeholder label colliding with an option label is NOT an answer") {
    val out = Out()
    val root = tmpRoot()
    val png = root / "shot.png"; os.write(png, "png-bytes")
    for
      (p, ctx) <- started(out, List(meta("wx_a")))
      // 选项标签就是媒体占位标签——无守卫时 label 臂的规范化碰撞会把「发了张图」
      // 消费成选项作答（缺陷本体：入站通知被渲染进问题卡回答区）
      _ <- p.onAgentEvent("s1", askFrame("rid-img", "s1", List(itemJson("选个图?", List("[图片]", "乙")))))
      v <- p.intake(
        ctx,
        WeixinIlinkBridgePlugin
          .Inbound(Some("wx_a"), Some("[图片]"), Some("m1"), mediaPath = Some(png.toString))
      )
      fw <- ctx.forwarded.get
      injected <- ctx.injected.get
      receipt <- AskAnswerReceipt.peek("rid-img")
    yield
      assertEquals(v, WeixinIlinkBridgePlugin.Verdict.Injected("s1"), "媒体消息恒走媒体臂，不进 ask 臂")
      assertEquals(fw, List.empty, "占位标签不得被消费成作答（零混入）")
      assert(injected.exists(_._2.contains(png.toString)), s"媒体臂注入落地路径行：$injected")
      assertEquals(receipt, None, "未作答 ⇒ 无回执登记")
    end for
  }

  // ──────── answer-landing 批（2026-10-21）表1 ★：批名卡头 ────────

  test("WA-R4 batch header line: non-empty batch → leading [批名]; empty batch → byte-identical pre-batch card") {
    val views = List(PromptView(0, "用哪个?", List(nebflow.social.outbound.PromptOption("甲"))))
    val (withBatch, truncB) = WeixinAskLeg.renderAskText(views, Nil, requestId = "rid-1", batch = "proj-x")
    val (without, truncN) = WeixinAskLeg.renderAskText(views, Nil, requestId = "rid-1")
    assert(withBatch.startsWith("[proj-x]\n"), s"批名行卡头前置：${withBatch.take(30)}")
    assert(without.startsWith("第 1/1 问"), s"无批名零头行：${without.take(30)}")
    assertEquals(withBatch.stripPrefix("[proj-x]\n"), without, "批名行只前缀差（其余逐字节一致）")
    assertEquals(truncB, truncN, "批名行不参与截断判据")
  }

  test("WI-A9 the ask card carries the frame's attribution as the batch header (project first, nodeName fallback, absent → none)") {
    val out = Out()
    def frame(rid: String, extra: (String, Json)*): Json =
      Json.obj(
        List[(String, Json)](
          "type" -> "askUser".asJson,
          "sessionId" -> "s1".asJson,
          "requestId" -> rid.asJson,
          "items" -> Json.fromValues(simpleItems)
        ) ++ extra*
      )
    for
      (p, ctx) <- started(out, List(meta("wx_a")))
      _ <- p.onAgentEvent("s1", frame("rid-b1", "project" -> "proj-x".asJson))
      _ <- p.onAgentEvent("s1", frame("rid-b2", "nodeName" -> "node-b".asJson))
      _ <- p.onAgentEvent("s1", frame("rid-b3"))
      sent <- out.sent.get
    yield
      assertEquals(sent.size, 3, "三张卡各走一次外发")
      assert(sent(0)._2.startsWith("[proj-x]\n"), s"project 归因进卡头：${sent(0)._2.take(30)}")
      assert(sent(1)._2.startsWith("[node-b]\n"), s"nodeName 回落：${sent(1)._2.take(30)}")
      assert(!sent(2)._2.startsWith("["), s"无归因零头行：${sent(2)._2.take(30)}")
    end for
  }

  // ──────── answer-landing 批（2026-10-21）缺陷①：回执反向通道接线 ────────

  test("WI-A10 answerAsk registers the receipt channel; cancel/askUserClosed retire it") {
    val out = Out()
    for
      (p, ctx) <- started(out, List(meta("wx_a")))
      // 作答 ⇒ 转发的同一时刻登记回执通道（channel=渠道名, routeKey=发送者）
      _ <- p.onAgentEvent("s1", askFrame("rid-rc1", "s1", simpleItems))
      _ <- p.intake(ctx, WeixinIlinkBridgePlugin.Inbound(Some("wx_a"), Some("2"), Some("m1")))
      rc1 <- AskAnswerReceipt.peek("rid-rc1")
      // 取消关键字 ⇒ 登记随撤
      _ <- p.onAgentEvent("s1", askFrame("rid-rc2", "s1", simpleItems))
      _ <- p.intake(ctx, WeixinIlinkBridgePlugin.Inbound(Some("wx_a"), Some("取消问题"), Some("m2")))
      rc2 <- AskAnswerReceipt.peek("rid-rc2")
      // askUserClosed ⇒ 登记随撤（幂等二次清理）
      _ <- p.onAgentEvent("s1", askFrame("rid-rc3", "s1", simpleItems))
      _ <- p.onAgentEvent(
        "s1",
        Json.obj("type" -> "askUserClosed".asJson, "sessionId" -> "s1".asJson, "requestId" -> "rid-rc3".asJson)
      )
      rc3 <- AskAnswerReceipt.peek("rid-rc3")
    yield
      assertEquals(rc1.map(_.channel), Some("weixin-ilink"))
      assertEquals(rc1.map(_.routeKey), Some("wx_a"))
      assertEquals(rc2, None, "取消随撤登记（僵尸卡收口的载具面）")
      assertEquals(rc3, None, "closed 随撤登记")
    end for
  }

end WeixinAskLegSpec
