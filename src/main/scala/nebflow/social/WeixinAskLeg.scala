package nebflow.social

import nebflow.social.outbound.{AskPromptCodec, PromptAction, PromptView}

/**
 * The weixin numbered-reply ask face (askuser-remote P1, 卡 B / 拍板 B-1…B-4).
 *
 * iLink 2.4.9 carries passive TEXT only — there is no interactive card on this
 * channel — so a question travels as a NUMBERED text card and the answer comes
 * back as a numbered text reply. Everything here is a pure function over plain
 * values: the renderer, the reply parser and the long-text degradation
 * (B-4 案甲, pull-style). The stateful legs that call these (the per-sender ask
 * ledger, the intake splitter, the pull slot) live in
 * [[WeixinIlinkBridgePlugin]]; this object holds none.
 *
 * Copy discipline (§16): the numbered-line / question-head shapes reuse the
 * FROZEN 2026-10-01 copy pieces ([[FeishuAskCard.questionCopy]],
 * [[FeishuAskCard.degradeOptionLine]], [[FeishuAskCard.DegradeFoot]]) — same
 * strings, one source, zero drift. The genuinely NEW user-visible strings of
 * this file (the footer line, the two refusal lines, the pull hint, the
 * segment head, the truncation mark, the 「全文」 keyword) are listed verbatim
 * in the batch report §16 for the author's review.
 *
 * Parsing rules (拍板 B-1/B-2, exactly):
 *   · a bare number (or comma list) addresses the FIRST question of the
 *     current active set — the numbers belong to the ACTIVE SET, and a new
 *     ask renumbers everything (the old ask's numbers die with it);
 *   · `问.编` explicitly addresses question `问`, option `编` (both 1-based);
 *   · free text enters ONLY behind the explicit `0 ` / `他` prefix — bare text
 *     is indistinguishable from "the user is chatting" and is never
 *     intercepted;
 *   · out-of-range indices are REFUSED with a readable line, never clamped
 *     and never silently dropped (the AskPromptCodec house rule).
 */
object WeixinAskLeg:

  /** B-4 案甲 first tier (拍板): an outbound text at or under this many chars
    * goes out as-is; longer text degrades to 摘要 + 拉取指令, with the full
    * text delivered on demand in segments of this size. A conservative LOCAL
    * assumption — the iLink protocol's true single-message limit has no public
    * documentation (research card B §B2), so nothing here claims it. */
  val FullTextThreshold = 4000

  /** The pull keyword (B-4 案甲). A reply of exactly this word (trimmed) on a
    * sender with a stashed full text pushes the segments. */
  val FullTextKeyword = "全文"

  /** The ask card's closing instruction line (NEW §16 string, this batch). */
  val NumberFooter = "回复编号选择，多选用逗号（如 1,3）；回复 0 + 内容 = 其他。"

  /** The out-of-range refusal line (NEW §16 string). One line for every range
    * failure — option index, question index, bare `0` — so the user always
    * gets the SAME readable correction instead of guessing. */
  val RangeErrorLine = "编号超出范围，请按题面编号回复（多选用逗号；多问用 问.编 寻址，如 2.1）。"

  /** The single-select violation line (NEW §16 string): a comma list on a
    * question that is not `multiple`. */
  val SinglePickOnlyLine = "本题是单选：请只回复一个编号。"

  /** The 案甲 pull instruction (NEW §16 string template). */
  def fullTextHint(totalChars: Int, segments: Int): String =
    s"内容较长（共 $totalChars 字，将分 $segments 段发送）：回复「$FullTextKeyword」获取完整内容。"

  /** The segment order marker (NEW §16 string template): 案甲 numbers its
    * segments 1/3, 2/3 … so a multi-push stays ordered on the wire. */
  def segmentHead(i: Int, n: Int): String = s"（$i/$n）"

  /** The truncation mark appended to a shortened question text (NEW §16). */
  val TruncatedMark = "……"

  // ───────────────────────── outbound rendering ─────────────────────────

  /** Render the ask as one numbered text card. Question head, then its
    * numbered options (NO 「其他…」 option line — free text here is the `0`/`他`
    * PREFIX leg, not an option), then the attachment path lines (A6), then the
    * frozen closing instruction and the channel footer. Returns the full text
    * plus whether it fits the 案甲 first tier. */
  def renderAskText(views: List[PromptView], attachments: List[String]): (String, Boolean) =
    renderAskTextBudgeted(views, attachments, questionBudget = None)

  /** The 案甲 degraded form for an oversized ask: the question TEXT is cut to
    * `questionBudget` chars (marked with [[TruncatedMark]]), everything the
    * user needs to REPLY (the numbering, the footer) stays intact — the full
    * text rides the pull slot instead. */
  def renderAskTextTruncated(views: List[PromptView], attachments: List[String], questionBudget: Int): (String, Boolean) =
    renderAskTextBudgeted(views, attachments, questionBudget = Some(math.max(questionBudget, 0)))

  private def renderAskTextBudgeted(
    views: List[PromptView],
    attachments: List[String],
    questionBudget: Option[Int]
  ): (String, Boolean) =
    var truncated = false
    val body = views
      .map { v =>
        val questionText = questionBudget match
          case Some(budget) if v.question.length > budget =>
            truncated = true
            v.question.take(budget) + TruncatedMark
          case _ => v.question
        val head = FeishuAskCard.questionCopy(v.idx, views.size) + " " + questionText
        val opts = v.options.zipWithIndex.map { (o, i) => FeishuAskCard.degradeOptionLine(i, o.label) }
        (head :: opts).mkString("\n")
      }
      .mkString("\n\n")
    val attach = AskPromptCodec.attachmentLines(attachments)
    val text =
      (if attach.isEmpty then body else body + "\n" + attach) + "\n" +
        FeishuAskCard.DegradeFoot + "\n" + NumberFooter
    (text, truncated)

  /** Split a long text into 案甲 segments: each at most `chunk` chars, every
    * segment BUT THE FIRST headed with its order marker (the first needs no
    * order — it arrives in sequence). Pure. */
  def segments(text: String, chunk: Int = FullTextThreshold): List[String] =
    if text.isEmpty then Nil
    else if text.length <= chunk then List(text)
    else
      val parts = text.grouped(chunk).toList
      parts.zipWithIndex.map {
        case (s, 0) => s
        case (s, i) => s"${segmentHead(i + 1, parts.length)}\n$s"
      }

  // ───────────────────────── inbound reply parsing ─────────────────────────

  /** Why a numeric-shaped reply was refused. Each refusal carries its OWN
    * reviewed line — the user is told what to do, not just "no". */
  sealed trait ParseReject:
    def line: String
  case object RangeReject extends ParseReject:
    val line: String = RangeErrorLine
  case object SinglePickReject extends ParseReject:
    val line: String = SinglePickOnlyLine

  private val BareNumberList = "^\\d+(\\s*,\\s*\\d+)*$".r
  private val Addressed = "^(\\d+)\\.(\\d+)$".r
  private val OtherPrefix = "^(?:0\\s+(.+)|他\\s*(.+))$".r

  /** Parse one inbound text against the CURRENT active question set.
    *
    *   · `Right(None)` — the text is NOT an ask reply under 拍板 B-2 (no
    *     explicit prefix, not number-shaped): the caller must let it fall
    *     through to the normal chat injection, byte-for-byte untouched.
    *   · `Right(Some(actions))` — a validated answer; feed it to
    *     [[AskPromptCodec.answers]] over the SAME (renumbered, flattened)
    *     `views` and expand with the entry's original indices.
    *   · `Left(reject)` — number-shaped but unusable: the caller answers the
    *     ONE refusal line and does NOT inject (「超界拒不钳制」).
    */
  def parseReply(text: String, views: List[PromptView]): Either[ParseReject, Option[List[PromptAction]]] =
    val t = text.trim
    t match
      case OtherPrefix(zero, ta) =>
        val content = Option(zero).orElse(Option(ta)).getOrElse("").trim
        firstView(views) match
          case Some(v) if content.nonEmpty => Right(Some(List(PromptAction.Other(v.idx, content))))
          case _ => Left(RangeReject) // bare "0" with no content: nothing answerable — the refusal line
      case Addressed(q, o) =>
        val qi = q.toInt - 1
        val oi = o.toInt - 1
        views.lift(qi) match
          case Some(v) if oi >= 0 && oi < v.options.size => Right(Some(List(PromptAction.Pick(v.idx, oi))))
          case _ => Left(RangeReject)
      case _ if BareNumberList.matches(t) =>
        firstView(views) match
          case None => Left(RangeReject)
          case Some(v) =>
            val picks = t.split(",").toList.map(_.trim.toInt).map(_ - 1)
            if picks.exists(oi => oi < 0 || oi >= v.options.size) then Left(RangeReject)
            else if !v.multiple && picks.sizeIs > 1 then Left(SinglePickReject)
            else Right(Some(picks.map(PromptAction.Pick(v.idx, _))))
      case _ => Right(None)

  /** B-1: a bare number addresses the FIRST question of the active set. The
    * views arrive already flattened and renumbered, so "first" is `idx` 0 —
    * but the lookup goes through the list head, not the index, so an
    * (impossible) empty set cannot index-crash. */
  private def firstView(views: List[PromptView]): Option[PromptView] =
    views.headOption

end WeixinAskLeg
