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
 * Parsing rules (拍板 B-1/B-2, plus the askuser-media-split batch ruling C2 and
 * the answer-landing batch's short-number arm, 2026-10-21 — mapping-table
 * priority 4★):
 *   · a bare number (or comma list) addresses the FIRST question of the
 *     current active set — the numbers belong to the ACTIVE SET, and a new
 *     ask renumbers everything (the old ask's numbers die with it);
 *   · `问.编` explicitly addresses question `问`, option `编` (both 1-based);
 *   · `N <自由文本>` is question N's free-text answer (the `0 `-prefix leg's
 *     numbered variant; N ∈ active set, out-of-range/blank refused);
 *   · a reply that IS an option label (case-insensitive, whitespace-
 *     insensitive exact match) picks that option — the media-split batch's
 *     return-matching ruling; the FIRST question carrying the label wins
 *     (the B-1 "bare forms address from the first question" rule);
 *   · other free text enters ONLY behind the explicit `0 ` / `他` prefix —
 *     bare text that matches NO option label is indistinguishable from
 *     "the user is chatting" and is never intercepted;
 *   · out-of-range indices are REFUSED with a readable line, never clamped
 *     and never silently dropped (the AskPromptCodec house rule).
 */
object WeixinAskLeg:

  /**
   * B-4 案甲 first tier (拍板): an outbound text at or under this many chars
   * goes out as-is; longer text degrades to 摘要 + 拉取指令, with the full
   * text delivered on demand in segments of this size. A conservative LOCAL
   * assumption — the iLink protocol's true single-message limit has no public
   * documentation (research card B §B2), so nothing here claims it.
   */
  val FullTextThreshold = 4000

  /**
   * The pull keyword (B-4 案甲). A reply of exactly this word (trimmed) on a
   * sender with a stashed full text pushes the segments.
   */
  val FullTextKeyword = "全文"

  // ───────────── askucancel 批（2026-10-08）：取消关键字 ─────────────
  // 渠道侧用户发起取消的唯一关键字（飞书文本 intake 与微信 ask 臂**共用同一
  // 常量**——一处定义、两渠道引用，禁第二份字面量）。判据 = **精确匹配**（trim
  // 后整词相等），沿用 [[FullTextKeyword]] 的既有形态：不精确匹配会把「取消问题
  // 之后我再问你一件事」这类聊天误判成取消。零 pending 问题时的取消关键字按
  // B-2 既有裁定走聊天透传（不拦截、不吞）。

  /** 取消关键字（精确匹配）。 */
  val CancelKeyword = "取消问题"

  /** 取消成功回执（NEW §16 string）。 */
  val CancelledLine = "已取消该问题：不会再等待作答。"

  /**
   * 多问歧义拒绝线（NEW §16 string，飞书文本腿专用——微信按 B-3 恒单问在册，
   * 不可能歧义）。`%d` = 在册问题数。
   */
  def cancelAmbiguousLine(n: Int): String =
    s"当前有 $n 个待答问题，无法确定取消哪一个——请在网页端对要取消的问题卡点「取消」。"

  /**
   * 多问歧义拒绝线（answer 臂专用，NEW §16 string，飞书文本臂——微信按 B-3 恒单问
   * 在册，不可能歧义）。`%d` = 在册问题数。与 [[cancelAmbiguousLine]] 同族不同句：
   * 取消线指回网页端「取消」按钮，本线指回卡片按钮作答（飞书多问并存是卡片
   * 的合法形态，按钮按 rid 消歧；文本无法消歧时绝不猜）。
   */
  def answerAmbiguousLine(n: Int): String =
    s"当前有 $n 个待答问题，编号回复无法确定对应哪一张——请点击对应卡片上的按钮作答。"

  /** The ask card's closing instruction line (NEW §16 string, this batch). */
  val NumberFooter = "回复编号选择，多选用逗号（如 1,3）；回复 0 + 内容 = 其他。"

  /**
   * The out-of-range refusal line (NEW §16 string). One line for every range
   * failure — option index, question index, bare `0` — so the user always
   * gets the SAME readable correction instead of guessing.
   */
  val RangeErrorLine = "编号超出范围，请按题面编号回复（多选用逗号；多问用 问.编 寻址，如 2.1）。"

  /**
   * secret 题的渠道拒绝线（askuser-secret 批 · 新 §16 string，单一来源 =
   * [[nebflow.social.outbound.AskPromptCodec.SecretRefuseLine]]——两渠道禁第二份
   * 字面量）。
   */
  val SecretRefuseLine: String = nebflow.social.outbound.AskPromptCodec.SecretRefuseLine

  /**
   * The single-select violation line (NEW §16 string): a comma list on a
   * question that is not `multiple`.
   */
  val SinglePickOnlyLine = "本题是单选：请只回复一个编号。"

  /** The 案甲 pull instruction (NEW §16 string template). */
  def fullTextHint(totalChars: Int, segments: Int): String =
    s"内容较长（共 $totalChars 字，将分 $segments 段发送）：回复「$FullTextKeyword」获取完整内容。"

  /**
   * The segment order marker (NEW §16 string template): 案甲 numbers its
   * segments 1/3, 2/3 … so a multi-push stays ordered on the wire.
   */
  def segmentHead(i: Int, n: Int): String = s"（$i/$n）"

  /** The truncation mark appended to a shortened question text (NEW §16). */
  val TruncatedMark = "……"

  // ───────────────────────── outbound rendering ─────────────────────────

  /**
   * Render the ask as one numbered text card. Question head, then its
   * numbered options (NO 「其他…」 option line — free text here is the `0`/`他`
   * PREFIX leg, not an option), then the attachment path lines (A6), then the
   * frozen closing instruction and the channel footer. Returns the full text
   * plus whether it fits the 案甲 first tier.
   *
   * askuser-secret 批（规格 §3）：secret 题不渲染选项/入口，渲染桌面引导文案
   * （secretGuideLine）。`requestId` 仅用于该文案的括注；缺省 `""` 时引导行
   * 省略括注（`renderAskText` 的两个调用点都在 rid 在手的作用域内，恒显式传）。
   * 无 secret 题的集合 ⇒ 输出与改前逐字节一致。
   * answer-landing 实施批（2026-10-21，映射规则推荐表 表1 ★）：`batch` 非空 ⇒
   * 卡头前置一行批名标注（`AskPromptCodec.batchHeaderLine` 单一来源模板）——
   * 作者多批并发时辨识「答的是谁」；**标签不是寻址键**，解析臂永不读它。
   * 批名行计入首段、不参与截断判据（表1 长文降级行）。缺省 `""` ⇒ 卡头与改前
   * 逐字节一致（无归因提问零变化）。
   */
  def renderAskText(
    views: List[PromptView],
    attachments: List[String],
    requestId: String = "",
    batch: String = ""
  ): (String, Boolean) =
    renderAskTextBudgeted(views, attachments, requestId = requestId, questionBudget = None, batch = batch)

  /**
   * The 案甲 degraded form for an oversized ask: the question TEXT is cut to
   * `questionBudget` chars (marked with [[TruncatedMark]]), everything the
   * user needs to REPLY (the numbering, the footer) stays intact — the full
   * text rides the pull slot instead.
   */
  def renderAskTextTruncated(
    views: List[PromptView],
    attachments: List[String],
    questionBudget: Int,
    requestId: String = "",
    batch: String = ""
  ): (String, Boolean) =
    renderAskTextBudgeted(
      views,
      attachments,
      requestId = requestId,
      questionBudget = Some(math.max(questionBudget, 0)),
      batch = batch
    )

  private def renderAskTextBudgeted(
    views: List[PromptView],
    attachments: List[String],
    requestId: String,
    questionBudget: Option[Int],
    batch: String = ""
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
        // askuser-secret 批：secret 题 = 头行 + 桌面引导文案，零选项零入口
        // （渠道只答非 secret 题；值不走渠道线）。
        if v.secret then
          val guide =
            if requestId.isEmpty then "（需安全输入，请在桌面 Nebula 面板完成）"
            else nebflow.social.outbound.AskPromptCodec.secretGuideLine(v.idx, views.size, requestId)
          head + "\n" + guide
        else
          val opts = v.options.zipWithIndex.map { (o, i) => FeishuAskCard.degradeOptionLine(i, o.label) }
          (head :: opts).mkString("\n")
      }
      .mkString("\n\n")
    val attach = AskPromptCodec.attachmentLines(attachments)
    // 表1 ★：批名行卡头前置（AskPromptCodec 单一来源模板；空批名不注空段 ⇒ 逐字节兼容）。
    val header = AskPromptCodec.batchHeaderLine(batch)
    val headed = if header.isEmpty then body else header + "\n" + body
    val text =
      (if attach.isEmpty then headed else headed + "\n" + attach) + "\n" +
        FeishuAskCard.DegradeFoot + "\n" + NumberFooter
    (text, truncated)

  end renderAskTextBudgeted

  /**
   * Split a long text into 案甲 segments: each at most `chunk` chars, every
   * segment BUT THE FIRST headed with its order marker (the first needs no
   * order — it arrives in sequence). Pure.
   */
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

  /**
   * Why a numeric-shaped reply was refused. Each refusal carries its OWN
   * reviewed line — the user is told what to do, not just "no".
   */
  sealed trait ParseReject:
    def line: String

  case object RangeReject extends ParseReject:
    val line: String = RangeErrorLine

  case object SinglePickReject extends ParseReject:
    val line: String = SinglePickOnlyLine

  /**
   * askuser-secret 批：寻址到 secret 题的作答——渠道 fail-closed（规格 §3），
   * 回线指路桌面面板（值永远不进渠道线）。
   */
  case object SecretReject extends ParseReject:
    val line: String = SecretRefuseLine

  private val BareNumberList = "^\\d+(\\s*,\\s*\\d+)*$".r
  private val Addressed = "^(\\d+)\\.(\\d+)$".r
  private val OtherPrefix = "^(?:0\\s+(.+)|他\\s*(.+))$".r

  // answer-landing 实施批（2026-10-21，映射规则推荐表 表2 优先级 4★）：**短号自由
  // 文本臂**——`N <文本>` = 第 N 题的自由文本作答（现行 `0 内容` 的带题号变体）。
  // 插序 = 裸编号之后、选项标签臂之前（「数字开头优先按编号解析」的既有序不变：
  // 编号形永不进标签臂的白名单判据因此继续成立）。B-3 恒单问下 N 恒为 1（等价
  // `0 <文本>` 的便捷形），多问在册的渠道（飞书多卡并存 + 文本腿）用它对题寻址。
  private val ShortNumberFree = "^(\\d{1,3})\\s+(\\S.*)$".r

  /**
   * Parse one inbound text against the CURRENT active question set.
   *
   *   · `Right(None)` — the text is NOT an ask reply under 拍板 B-2 (no
   *     explicit prefix, not number-shaped, matches NO option label): the
   *     caller must let it fall through to the normal chat injection,
   *     byte-for-byte untouched.
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
          case Some(v) if v.secret => Left(SecretReject) // secret 题不收自由输入（规格 §3）
          case Some(v) if content.nonEmpty => Right(Some(List(PromptAction.Other(v.idx, content))))
          case _ => Left(RangeReject) // bare "0" with no content: nothing answerable — the refusal line
      case Addressed(q, o) =>
        val qi = q.toInt - 1
        val oi = o.toInt - 1
        views.lift(qi) match
          case Some(v) if v.secret => Left(SecretReject) // secret 题不收编号作答
          case Some(v) if oi >= 0 && oi < v.options.size => Right(Some(List(PromptAction.Pick(v.idx, oi))))
          case _ => Left(RangeReject)
      case _ if BareNumberList.matches(t) =>
        firstView(views) match
          case None => Left(RangeReject)
          case Some(v) if v.secret => Left(SecretReject) // secret 题不收编号作答
          case Some(v) =>
            val picks = t.split(",").toList.map(_.trim.toInt).map(_ - 1)
            if picks.exists(oi => oi < 0 || oi >= v.options.size) then Left(RangeReject)
            else if !v.multiple && picks.sizeIs > 1 then Left(SinglePickReject)
            else Right(Some(picks.map(PromptAction.Pick(v.idx, _))))
      case ShortNumberFree(qn, rest) =>
        // 表2 优先级 4★：第 N 题的自由文本作答。判据 = N∈活动集（超界拒不钳制）
        // ∧ 内容非空白（空白 = 无可作答之实，同一拒绝线）；secret 题不收（同构
        // 既有三臂的 fail-closed 口径）。`{1,3}` 位宽 + toIntOption 双保险——
        // 超宽数字串/溢出一律落 RangeErrorLine，绝不抛。
        val qi = qn.toIntOption.map(_ - 1)
        val content = rest.trim
        qi.flatMap(views.lift) match
          case Some(v) if v.secret => Left(SecretReject) // secret 题不收自由输入（规格 §3）
          case Some(v) if content.nonEmpty => Right(Some(List(PromptAction.Other(v.idx, content))))
          case _ => Left(RangeReject) // N 超界 / 纯空白内容：同一拒绝线（超界拒不钳制）
      case _ =>
        // C2 (askuser-media-split batch): a reply that IS an option label picks
        // it — number/addr/prefix shapes never reach this arm (the earlier cases
        // already ruled), so a label that LOOKS like a number stays a number.
        labelPick(t, views) match
          case Some(pick) => Right(Some(List(pick)))
          case None => Right(None)

    end match

  end parseReply

  /**
   * B-1: a bare number addresses the FIRST question of the active set. The
   * views arrive already flattened and renumbered, so "first" is `idx` 0 —
   * but the lookup goes through the list head, not the index, so an
   * (impossible) empty set cannot index-crash.
   */
  private def firstView(views: List[PromptView]): Option[PromptView] =
    views.headOption

  /**
   * The exact-label arm (C2): normalization = lower-case + ALL whitespace
   * stripped (ASCII spaces/tabs/newlines AND the full-width space) — the
   * tolerance the return-matching requirement pins. Views are scanned in
   * order and the FIRST matching option wins (B-1's "from the first
   * question" rule); a label repeated across questions therefore answers the
   * earliest question carrying it — deterministic, never guessed further.
   */
  private def labelPick(text: String, views: List[PromptView]): Option[PromptAction] =
    val needle = normalizeLabel(text)
    if needle.isEmpty then None
    else
      views.iterator
        .flatMap { v =>
          v.options.iterator.zipWithIndex
            .collect { case (o, oi) if normalizeLabel(o.label) == needle => PromptAction.Pick(v.idx, oi) }
        }
        .nextOption()

  /** label 归一化：小写化 + 去除全部空白（含全角空格）。 */
  private[social] def normalizeLabel(s: String): String =
    s.trim.toLowerCase.replaceAll("[\\s\\u3000]+", "")

end WeixinAskLeg
