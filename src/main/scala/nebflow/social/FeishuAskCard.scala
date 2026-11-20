package nebflow.social

import io.circe.Json

import nebflow.social.outbound.{PromptAction, PromptView, PromptOption, AskPromptCodec}

/**
 * Feishu card face for AskUser prompts (askuser batch, 2026-10-01).
 *
 * Everything here is a TOTAL FUNCTION over plain values — no network, no SDK
 * object, no IO. This is the channel ADAPTER half: channel vocabulary is
 * correct here (a card IS a channel concept), while the channel-neutral model
 * and the answer-slot semantics live in
 * [[nebflow.social.outbound.AskPromptCodec]].
 *
 * 🔴 Scope note (updated by askuser-remote P1, 2026-10-07): the pure functions
 * below AND the wiring around them are both live — the interactive leg IS
 * wired (`FeishuBridgePlugin.sendAskCards` → `FeishuChannel.sendInteractive`,
 * the callback handler registered on the long-connection dispatcher), so a
 * card produced here reaches real chats and real clicks. (The pre-P1 "A9
 * UNWIRED" wording is retired; the A9 ruling lives on as the send seam's
 * name.)
 *
 * 🔴 Copy discipline (§16): every user-visible string in this file is one of the
 * twelve reviewed copy sites (S-1…S-9 of the design card §10), which the author
 * read word for word. Do not reword them here.
 *
 * Button payload design (design card §3.2): the payload carries a namespace tag,
 * the request id, and the two indices — and 🔴 NOTHING ELSE. In particular it
 * never carries a session id: the answering side must resolve the conversation
 * from its own ledger, never from a value the click itself supplies.
 */
object FeishuAskCard:

  // ───────────────────────── reviewed copy (§16 · S-1…S-9) ─────────────────────────

  /** S-1: the card header title. */
  val TitleCopy = "来自 Nebula 的问题"

  /** S-2: the per-question section label; `{i}` is 1-based, `{n}` the total. */
  def questionCopy(i: Int, n: Int): String = s"第 ${i + 1}/$n 问"

  /** S-3: the free-text entry button. */
  val OtherCopy = "其他…"

  /** S-4: the form submit button. */
  val SubmitCopy = "提交"

  /**
   * S-5: the degraded-text header. `{reason}` names the fact that forced the
   * fallback — never a generic "failed".
   */
  def degradeHead(reason: String): String = s"⚠ 本条问题无法使用交互卡片（原因：$reason）。"

  /** S-6: the degraded-text option line and its closing instruction. */
  def degradeOptionLine(i: Int, label: String): String = s"  ${i + 1}) $label"

  val DegradeFoot = "请回复编号（多个问题用换行分隔；多选可用逗号）。"

  /** S-7 / S-8 / S-9: the three callback toasts. */
  val ToastExpired = "这个问题已失效"
  val ToastAlreadyAnswered = "这个问题已经作答过了"
  val ToastCannotHandle = "这个操作无法处理"

  /**
   * The answered-state line prefix (askuser-remote P1, NEW §16 string — the
   * A4 ruling: the callback response carries the updated card, where each
   * answered question's buttons are replaced by one line 「已选：X」).
   */
  val AnsweredPrefix = "已选："

  // ───────────────────────── local limits (A2) ─────────────────────────

  /**
   * Local caps, deliberately conservative and NOT a claim about any remote
   * limit (the design card could not obtain one, so none is asserted). Past
   * them the prompt degrades to sequential cards — the overflow route, not a
   * failure.
   */
  val MaxQuestions = 5
  val MaxOptionsPerQuestion = 5
  val MaxButtons = 20

  /** Would this prompt fit one card under the local caps? */
  def fitsOneCard(views: List[PromptView]): Boolean =
    views.size <= MaxQuestions &&
      views.forall(_.options.size <= MaxOptionsPerQuestion) &&
      views.map(v => v.options.size + (if v.allowOther then 1 else 0)).sum <= MaxButtons

  // ───────────────────────── outbound rendering ─────────────────────────

  /**
   * Render the prompt as one card, or as a sequence of cards when the local
   * caps are exceeded. One card per question in the sequential form, each
   * carrying its own question index so an answer can still be placed in the
   * right slot.
   */
  def render(views: List[PromptView], requestId: String): List[String] =
    if views.isEmpty then Nil
    else if fitsOneCard(views) then List(oneCard(views, requestId))
    else views.map(v => oneCard(List(v), requestId))

  /**
   * askuser-remote P1 (A6): the send face WITH attachment path lines — the
   * same cards [[render]] builds, plus one attachment block per card. A
   * separate entry (not a new parameter on the frozen [[render]]): the
   * pre-P1 callers and their byte-contract stay untouched.
   */
  def renderFull(views: List[PromptView], requestId: String, attachments: List[String]): List[String] =
    if views.isEmpty then Nil
    else if fitsOneCard(views) then List(oneCard(views, requestId, attachments = attachments))
    else views.map(v => oneCard(List(v), requestId, attachments = attachments))

  /**
   * askuser-remote P1 (A4): the card with every answered question's buttons
   * replaced by one 「已选：X」 line — the body the card callback RESPONSE
   * returns, so the clicker's card flips to the answered state inside the
   * 3-second callback window (方式一：立即更新), zero extra API calls.
   * `answered` maps question index → the DISPLAY string (the chosen label,
   * or the typed/fallback text for an Other answer).
   */
  def answeredCard(
    views: List[PromptView],
    requestId: String,
    answered: Map[Int, String],
    attachments: List[String] = Nil
  ): String =
    oneCard(views, requestId, attachments = attachments, answered = answered)

  private def oneCard(
    views: List[PromptView],
    requestId: String,
    attachments: List[String] = Nil,
    answered: Map[Int, String] = Map.empty
  ): String =
    val elements = views.flatMap { v =>
      val head = if views.sizeIs > 1 then questionCopy(v.idx, views.size) + "\n" + v.question else v.question
      val section = Json.obj(
        "tag" -> Json.fromString("div"),
        "text" -> Json.obj("tag" -> Json.fromString("lark_md"), "content" -> Json.fromString(head))
      )
      // askuser-secret 批（规格 §3）：secret 题 = 引导文案 div，**零按钮零输入面**
      // （渠道默认禁止 secret 题，fail-closed；值永远不进渠道线）。
      if v.secret then List(section, secretGuideSection(v.idx, views.size, requestId))
      else
        answered.get(v.idx) match
          case Some(display) =>
            // A4: this question is done — its answer replaces the buttons.
            List(section, answeredLine(display))
          case None =>
            val buttons = v.options.zipWithIndex.map { (o, oi) => button(o.label, payload(requestId, v.idx, oi)) }
            val other =
              if v.allowOther then List(button(OtherCopy, payload(requestId, v.idx, AskPromptCodec.OtherOptionIndex)))
              else Nil
            val action = Json.obj(
              "tag" -> Json.fromString("action"),
              "actions" -> Json.arr((buttons ++ other)*)
            )
            List(section, action)
      end if
    }
    val attachBlock =
      if attachments.isEmpty then Nil
      else
        List(
          Json.obj(
            "tag" -> Json.fromString("div"),
            "text" -> Json.obj(
              "tag" -> Json.fromString("lark_md"),
              "content" -> Json.fromString(AskPromptCodec.attachmentLines(attachments))
            )
          )
        )
    Json
      .obj(
        "config" -> Json.obj("wide_screen_mode" -> Json.fromBoolean(true)),
        "header" -> Json.obj(
          "template" -> Json.fromString("blue"),
          "title" -> Json.obj("tag" -> Json.fromString("plain_text"), "content" -> Json.fromString(TitleCopy))
        ),
        "elements" -> Json.arr((elements ++ attachBlock)*)
      )
      .noSpaces

  end oneCard

  /**
   * secret 题的引导文案 section（askuser-secret 批 · 新 §16 string 经
   * `AskPromptCodec.secretGuideLine` 单一来源）。
   */
  private def secretGuideSection(i: Int, n: Int, requestId: String): Json =
    Json.obj(
      "tag" -> Json.fromString("div"),
      "text" -> Json.obj(
        "tag" -> Json.fromString("lark_md"),
        "content" -> Json.fromString("→ " + AskPromptCodec.secretGuideLine(i, n, requestId))
      )
    )

  /** The one-line answered-state element (A4). */
  private def answeredLine(display: String): Json =
    Json.obj(
      "tag" -> Json.fromString("div"),
      "text" -> Json.obj("tag" -> Json.fromString("lark_md"), "content" -> Json.fromString(AnsweredPrefix + display))
    )

  private def button(label: String, value: Json): Json =
    Json.obj(
      "tag" -> Json.fromString("button"),
      "text" -> Json.obj("tag" -> Json.fromString("plain_text"), "content" -> Json.fromString(label)),
      "type" -> Json.fromString("default"),
      "value" -> value
    )

  /**
   * The button payload. `nf` makes a click from another feature refuse-able
   * instead of silently misread; the indices are the stable keys (labels are
   * not — they can be long, repeated or reworded).
   */
  private def payload(requestId: String, qi: Int, oi: Int): Json =
    Json.obj(
      PayloadNamespace -> Json.fromString(NamespaceAsk),
      PayloadRequestId -> Json.fromString(requestId),
      PayloadQuestionIndex -> Json.fromInt(qi),
      PayloadOptionIndex -> Json.fromInt(oi)
    )

  val PayloadNamespace = "nf"
  val PayloadRequestId = "rid"
  val PayloadQuestionIndex = "qi"
  val PayloadOptionIndex = "oi"
  val NamespaceAsk = "ask"

  /**
   * The degraded text leg (§3.4 of the design card). Question text first, then
   * its numbered options, then the attachment path lines (askuser-media-split
   * batch: ONLY the attachments the media leg did not deliver ride here —
   * the A6 lines are the visible degrade, default `Nil` keeps every pre-P1
   * call byte-identical), then the closing instruction, then the reason line —
   * the answer still returns through the same slot machinery.
   */
  def renderText(views: List[PromptView], reason: String, attachments: List[String] = Nil): String =
    val body = views
      .map { v =>
        // askuser-secret 批：secret 题 = 头行 + 引导短形（文本降级腿无 rid 在手，
        // 用无括注短形；卡片腿 oneCard 用带 rid 全形）。
        if v.secret then
          List(
            questionCopy(v.idx, views.size) + " " + v.question,
            "→ 需安全输入，请在桌面 Nebula 面板完成"
          )
        else
          val opts = v.options.zipWithIndex.map { (o, i) => degradeOptionLine(i, o.label) }
          val other = if v.allowOther then List(degradeOptionLine(v.options.size, OtherCopy)) else Nil
          (questionCopy(v.idx, views.size) + " " + v.question) :: (opts ++ other)
      }
      .map(_.mkString("\n"))
      .mkString("\n\n")
    val attach = AskPromptCodec.attachmentLines(attachments)
    s"$body${if attach.isEmpty then "" else "\n" + attach}\n$DegradeFoot\n\n${degradeHead(reason)}"
  end renderText

  // ───────────────────────── fallback decision (A3) ─────────────────────────

  /**
   * The pure third trigger of the fallback chain: a question that needs a
   * local-panel affordance cannot be carried by a remote card. The other two
   * triggers are readings taken at call time (a measured capability result and
   * a transport `ok = false`), not configuration — deliberately, so the
   * decision follows a fact the machine always has rather than a switch
   * someone may forget to flip.
   */
  def needsTextFallback(views: List[PromptView]): Boolean =
    AskPromptCodec.requiresLocalElements(views)

  // ───────────────────────── inbound parsing ─────────────────────────

  /**
   * Why a callback could not be turned into an answer. Each reason is a
   * distinct, named fact so the refusal can be specific — a generic failure is
   * indistinguishable from a silent drop, which is the defect family this
   * whole path exists to avoid.
   */
  final case class Reject(reason: String, detail: String)

  object Reject:

    def unknownNamespace(got: String): Reject =
      Reject("unknown-namespace", s"payload namespace is not '$NamespaceAsk' (got '$got')")

    def unknownRequestId(rid: String): Reject =
      Reject("unknown-request-id", s"no open prompt for request id '$rid'")

    def indexOutOfRange(field: String, value: Int): Reject =
      Reject("index-out-of-range", s"$field=$value is outside the prompt")

    def chatMismatch(expected: String, got: String): Reject =
      Reject("chat-mismatch", s"callback came from chat '$got', prompt was sent to '$expected'")

    def duplicate(qi: Int): Reject =
      Reject("duplicate", s"question index $qi was already answered")

  end Reject

  /**
   * Turn one callback payload into the user's action.
   *
   * Pure and total: every branch answers either a well-formed action or a NAMED
   * refusal. Nothing is clamped, nothing defaults to question 0, and an
   * unrecognised namespace is never treated as if it were ours.
   *
   * @param value          the callback's `value` object
   * @param knownRequestIds request ids the caller currently has open
   * @param expectedChatId the chat the prompt was sent to, if known
   * @param callbackChatId the chat the callback arrived from, if known
   * @param alreadyAnswered question indices already answered for this request
   */
  def parse(
    value: Json,
    knownRequestIds: Set[String] = Set.empty,
    expectedChatId: Option[String] = None,
    callbackChatId: Option[String] = None,
    alreadyAnswered: Set[Int] = Set.empty
  ): Either[Reject, PromptAction] =
    val c = value.hcursor
    val ns = c.downField(PayloadNamespace).as[String].toOption.map(_.trim).getOrElse("")
    if ns != NamespaceAsk then Left(Reject.unknownNamespace(ns))
    else
      val rid = c.downField(PayloadRequestId).as[String].toOption.map(_.trim).getOrElse("")
      if rid.isEmpty || !knownRequestIds.contains(rid) then Left(Reject.unknownRequestId(rid))
      else
        (expectedChatId.map(_.trim).filter(_.nonEmpty), callbackChatId.map(_.trim).filter(_.nonEmpty)) match
          case (Some(exp), Some(got)) if exp != got => Left(Reject.chatMismatch(exp, got))
          case _ =>
            val qi = c.downField(PayloadQuestionIndex).as[Int].toOption
            val oi = c.downField(PayloadOptionIndex).as[Int].toOption
            (qi, oi) match
              case (Some(q), Some(o)) if q < 0 => Left(Reject.indexOutOfRange("qi", q))
              case (Some(q), Some(o)) if alreadyAnswered.contains(q) => Left(Reject.duplicate(q))
              case (Some(q), Some(o)) if o < AskPromptCodec.OtherOptionIndex => Left(Reject.indexOutOfRange("oi", o))
              case (Some(q), Some(AskPromptCodec.OtherOptionIndex)) =>
                Right(PromptAction.Other(q, c.downField("input_value").as[String].toOption.getOrElse("").trim))
              case (Some(q), Some(o)) => Right(PromptAction.Pick(q, o))
              case _ => Left(Reject.indexOutOfRange("qi/oi", -1))

      end if

    end if

  end parse

  /**
   * The toast a refusal answers with (§16 · S-7 / S-8 / S-9). Total: every
   * reason maps to one of the three reviewed strings, so a user who clicks
   * something unusable always sees WHY, never nothing.
   */
  def toastFor(reject: Reject): String = reject.reason match
    case "duplicate" => ToastAlreadyAnswered
    case "unknown-request-id" => ToastExpired
    case _ => ToastCannotHandle

  /**
   * The reason phrase that goes into [[degradeHead]] for each fallback trigger
   * (design card §3.4).
   */
  def fallbackReason(fallback: nebflow.social.outbound.PromptFallback): String = fallback match
    case nebflow.social.outbound.PromptFallback.NoCallback(reason) => s"卡片回调不可达（$reason）"
    case nebflow.social.outbound.PromptFallback.SendRejected(code, d) => s"发送被拒 code=$code（$d）"
    case nebflow.social.outbound.PromptFallback.LocalOnly(reason) => s"含本地面板元素（$reason）"

end FeishuAskCard
