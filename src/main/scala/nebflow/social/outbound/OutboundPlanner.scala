package nebflow.social.outbound

import nebflow.shared.AttachContract

/**
 * The pure planner: class + payload + gate readings -> call intents.
 *
 * Zero IO, zero SDK, zero logger. That is what makes the fallback rules
 * offline-judgeable (design card section 5): the spec hands in a class and a
 * payload and asserts the exact call list, with no browser, no network and no
 * credentials anywhere near it.
 *
 * The four "no silent drop" invariants (design card section 7.3) are structural
 * here, not aspirational:
 *   1. every call in `OutboundPlan.calls` has a matching [[ChannelCallResult]]
 *      (the dispatcher owes one per call -- count conservation);
 *   2. a non-degraded plan always carries at least one call;
 *   3. a degraded plan always carries [[OutboundPlan.degradedText]] with the
 *      `[未渲染]` / `[未知类型]` / `[尺寸闸拒绝]` prefix;
 *   4. there is no arm that returns an empty plan without a degradation and no
 *      `case _ => ()` anywhere in this package.
 */
object OutboundPlanner:

  /**
   * Outcome of planning one payload for one channel.
   *
   * @param calls        call intents, in order (empty ONLY when `degradedText` is set)
   * @param degradedText the placeholder body that must reach the wire when set
   * @param rejection    the explicit gate refusal (code + actual + limit), never swallowed
   * @param reasons      closed-vocabulary reasons, for the warn log and the evidence
   */
  final case class OutboundPlan(
      cls: ContentClass,
      calls: List[ChannelCall],
      degradedText: Option[String],
      rejection: Option[AttachContract.AttachError],
      reasons: List[String]
  ):
    /** A plan is compliant only if nothing was silently dropped. */
    def noSilentDrop: Boolean =
      (calls.nonEmpty || degradedText.nonEmpty) &&
        (degradedText.isEmpty || degradedText.exists(_.nonEmpty))

  object OutboundPlan:
    def text(cls: ContentClass, body: String): OutboundPlan =
      OutboundPlan(cls, List(ChannelCall.SendText(body)), None, None, Nil)

  /** The closed reason vocabulary (design card section 7.1). A free-form reason
    * would make the reading unjudgeable. */
  object Reason:
    val RenderUnavailable = "渲染件不可用"
    val RenderTimeout = "渲染超时"
    val RenderInvalidOutput = "渲染输出非法(PNG 校验失败)"
    val SizeGateRejected = "尺寸闸拒绝"
    def channelRejected(code: Int): String = s"渠道拒绝(code=$code)"

  /**
   * Plan a TEXT-class payload. The channel adapter is expected to have already
   * run [[OutboundText.toPlain]] on the body; the planner keeps the body opaque
   * so this function stays a pure data transform.
   */
  def text(body: String): OutboundPlan =
    OutboundPlan.text(ContentClass.Text, body)

  /**
   * Plan an image payload. The image branch deliberately does NOT touch the
   * renderer: an artifact that is already a PNG/JPG is passed through
   * unchanged -- re-encoding it is pure loss and would be the "fake image"
   * failure mode in reverse (design card section 3.5, route D).
   */
  def image(path: os.Path, mimeType: String, bytes: Long): OutboundPlan =
    gate(bytes) match
      case Some(err) =>
        OutboundPlan(
          ContentClass.Image,
          calls = Nil,
          degradedText = Some(OutboundText.sizeRejected(bytes, AttachContract.MaxFileBytes)),
          rejection = Some(err),
          reasons = List(Reason.SizeGateRejected)
        )
      case None =>
        OutboundPlan(
          ContentClass.Image,
          calls = List(ChannelCall.UploadImage(path, thenSendImage = true)),
          degradedText = None,
          rejection = None,
          reasons = Nil
        )

  /**
   * Plan a document payload. The size gate runs BEFORE the upload call is ever
   * minted -- an over-limit file produces zero upload calls plus one explicit
   * refusal reading (design card section 6.2, criterion R-7).
   */
  def document(path: os.Path, fileName: String, bytes: Long): OutboundPlan =
    gate(bytes) match
      case Some(err) =>
        OutboundPlan(
          ContentClass.Document,
          calls = Nil,
          degradedText = Some(OutboundText.sizeRejected(bytes, AttachContract.MaxFileBytes)),
          rejection = Some(err),
          reasons = List(Reason.SizeGateRejected)
        )
      case None =>
        OutboundPlan(
          ContentClass.Document,
          calls = List(ChannelCall.UploadFile(path, fileName, thenSendFile = true)),
          degradedText = None,
          rejection = None,
          reasons = Nil
        )

  /**
   * Plan a card payload from an ALREADY-RENDERED pixel artifact. The render
   * call itself is the adapter's job (it owns the browser); what the planner
   * decides is that a rendered card is an image upload, never a re-render.
   */
  def cardRendered(rendered: os.Path, bytes: Long): OutboundPlan =
    gate(bytes) match
      case Some(err) =>
        OutboundPlan(
          ContentClass.Card,
          calls = Nil,
          degradedText = Some(OutboundText.sizeRejected(bytes, AttachContract.MaxFileBytes)),
          rejection = Some(err),
          reasons = List(Reason.SizeGateRejected)
        )
      case None =>
        OutboundPlan(
          ContentClass.Card,
          calls = List(ChannelCall.UploadImage(rendered, thenSendImage = true)),
          degradedText = None,
          rejection = None,
          reasons = Nil
        )

  /**
   * Plan a card payload whose render FAILED, or whose renderer is unavailable.
   *
   * This is the arm that must never collapse into "send nothing": zero upload
   * calls, zero send calls, and exactly one degraded text carrying the reason
   * and the original path.
   */
  def cardRenderFailed(
      originalPath: Option[os.Path],
      bytes: Long,
      reason: String
  ): OutboundPlan =
    val body = originalPath match
      case Some(p) => OutboundText.unrendered(reason, p, bytes)
      case None    => s"[未渲染] $reason"
    OutboundPlan(
      ContentClass.Card,
      calls = Nil,
      degradedText = Some(body),
      rejection = None,
      reasons = List(reason)
    )

  /**
   * Plan an UNKNOWN-class payload (design card section 7.2).
   *
   * Fail-closed: the unknown class is spelled out, never dropped. This is the
   * arm that a silent-`IO.unit` implementation would erase, so it is the one
   * the red check attacks.
   */
  def unknown(reason: String, path: os.Path, originalText: Option[String]): OutboundPlan =
    val head = originalText.map(_.trim).filter(_.nonEmpty)
    val note = OutboundText.unknownType(reason, path)
    val body = head.map(t => s"$t\n$note").getOrElse(note)
    OutboundPlan(
      ContentClass.Unknown(reason),
      calls = Nil,
      degradedText = Some(body),
      rejection = None,
      reasons = List(reason)
    )

  /**
   * Plan from a classification result. This is the single entry the channel
   * adapter uses so that the unknown/text arms cannot drift apart.
   */
  def plan(
      cls: ContentClass,
      path: Option[os.Path],
      text: Option[String],
      fileName: Option[String],
      bytes: Long
  ): OutboundPlan =
    cls match
      case ContentClass.Text =>
        OutboundPlan.text(ContentClass.Text, text.getOrElse(""))
      case ContentClass.Image =>
        path match
          case Some(p) =>
            val mime = ContentClass.imageMime(p.last).getOrElse("application/octet-stream")
            image(p, mime, bytes)
          case None =>
            OutboundPlan(
              ContentClass.Image,
              calls = Nil,
              degradedText = Some(s"[未知类型] image class without a path — 原件：(none)"),
              rejection = None,
              reasons = List("image class carried no path")
            )
      case ContentClass.Document =>
        path match
          case Some(p) => document(p, fileName.getOrElse(p.last), bytes)
          case None =>
            OutboundPlan(
              ContentClass.Document,
              calls = Nil,
              degradedText = Some("[未知类型] document class without a path — 原件：(none)"),
              rejection = None,
              reasons = List("document class carried no path")
            )
      case ContentClass.Card =>
        // A card reaches the planner un-rendered; the adapter renders first.
        // Reaching this arm means the adapter had no render output, which is a
        // render failure by construction, never a silent pass-through.
        cardRenderFailed(path, bytes, Reason.RenderUnavailable)
      case ContentClass.Unknown(reason) =>
        path match
          case Some(p) => unknown(reason, p, text)
          case None =>
            OutboundPlan(
              ContentClass.Unknown(reason),
              calls = Nil,
              degradedText = Some(s"[未知类型] $reason"),
              rejection = None,
              reasons = List(reason)
            )

  /** The size gate -- the repo's existing constant and function, never a second copy. */
  private def gate(bytes: Long): Option[AttachContract.AttachError] =
    AttachContract.checkFileSize(bytes) match
      case Left(err) => Some(err)
      case Right(_)  => None

  /** The attachment-count gate, for a multi-part outbound (N calls at once). */
  def checkCount(n: Int): Option[AttachContract.AttachError] =
    AttachContract.checkAttachmentCount(n) match
      case Left(err) => Some(err)
      case Right(_)  => None
