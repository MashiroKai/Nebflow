package nebflow.social.outbound

/**
 * `<report>` 用户可见块提取 — 渠道出站腿的**单一实现源**（report 块可见性批，
 * 作者令 2026-11-20：单机制迁移，旧 `<report>` 字样按普通文本处理）。
 *
 * 所有把 agent 回复文本投给用户的社交渠道出站渲染点（微信 iLink 腿、飞书腿
 * —— 仓内仅有的两个 [[nebflow.bridge.BridgePlugin]] 实现；未来的渠道复用本
 * 对象，**不做第二套解析**）都经 [[extract]] 收口。语义与控制台面逐字对齐
 * （web/js/utils.js 的 `_outputTagsBalanced` + `extractOutputInner`，同一批
 * 升级为围栏安全）：
 *
 *   - 只投递各 `<report>…</report>` 块内部内容的拼接：每块 body trim 后按
 *     出现序以空行连接（多块一条消息，与控制台气泡面同构）；
 *   - 块外文本一律不出站（叙述、工具过程叙述不投）；
 *   - 整条回复没有 report 块 ⇒ 不投递文本内容（**不回退成发全文** —— 作者令
 *     「与『只有用这个块包裹的内容用户才可见』一致」）；
 *   - **代码围栏安全**（作者令原文：块形态当初就是为了避开 md 语法冲突，
 *     提取器不能反被围栏骗了）：``` 围栏内的字面 `<report>` 是内容不是结构
 *     —— 围栏感知的掩码扫描（`[[maskFences]]`）把围栏区从标签扫描中剔除，
 *     控制台提取器同批升级同一条规则，两侧逐字一致。
 *
 * ==范围（作者裁定 2026-11-20：源头打标，不按窗口/渠道归属推断）==
 *
 * 可见性规则只作用于主会话：`[[extractGated]]` 的 `rootSession` 由**发射会话
 * 自身身份**打标的帧上标志给出（`AgentActor.stampSourceIdentity` 用
 * `RootAgentIdentity.isRootAgent`）。root ⇒ 块内拼接；非 root ⇒ 原文直通（既有
 * 行为一字不改）。旧的 `outputBlock.enabled` 总闸已退役，不再有「关 = 全文泄漏」
 * 的回退面。
 *
 * 纯函数、零 IO、零 SDK —— 离线可判（[[OutputBlockSpec]] 钉死判定表，
 * 两条渠道各有一条管线级测试证明出站 payload）。
 */
object OutputBlock:

  /**
   * The one SCOPE-AWARE extraction entry — the four channel outbound seams'
   * single choke point. `rootSession = true` (the emitting session is the root
   * orchestrator, stamped by the actor) ⇒ identical to [[extract]];
   * `false` ⇒ the raw text passes through as `Extracted(text)` — every other
   * session keeps its previous behavior verbatim (no hold, no swallow).
   */
  def extractGated(text: String, rootSession: Boolean): Verdict =
    if rootSession then extract(text) else Extracted(text)

  private val OpenTag = "<report>"
  private val TagRe = "</?report>".r

  /**
   * The fence-mask char: NUL — never occurs in engine text, never matches the
   * tag regex; the mask preserves offsets 1:1 so pair bodies slice off the
   * ORIGINAL text.
   */
  private val MaskChar = '\u0000'

  /**
   * The extraction verdict for one piece of agent turn text.
   *
   *   - [[Extracted]] — ≥1 balanced pair OUTSIDE fences; `inner` is the
   *     blank-line join of every pair's trimmed body, in order. An EMPTY pair
   *     (`<report></report>`) extracts to `""` — the console face renders it
   *     as nothing and the channel sends nothing, but the text IS consumed
   *     (the block is complete and balanced).
   *   - [[NoBlocks]] — no `<report>` tag outside fences at all (narration /
   *     legacy text / tags that live only inside a fence). Delivers NOTHING —
   *     never a full-text fallback.
   *   - [[Unbalanced]] — tags outside fences exist but do not pair up:
   *     a streamed `<report>` whose close has not arrived yet (the streaming
   *     intermediate state), a stray close, or nesting. A segment flush reads
   *     this as HOLD (never pop, never send — the block may complete on later
   *     deltas; the console streaming discipline 「不炸不吞」); a turn-end
   *     flush reads it as nothing deliverable (there is no later flush, and an
   *     unterminated block wraps no user-visible content).
   */
  sealed trait Verdict
  final case class Extracted(inner: String) extends Verdict
  case object NoBlocks extends Verdict
  case object Unbalanced extends Verdict

  /**
   * The one extraction entry. The balance walk is the console rule verbatim:
   * tags in order, an open while already open fails (nesting), a close while
   * closed fails (stray), ≥1 pair required, ends closed. Pair bodies always
   * slice the ORIGINAL text (the fence mask is length-preserving), so fenced
   * code can never leak into — nor be consumed by — a block body.
   */
  def extract(text: String): Verdict =
    val masked = maskFences(text)
    val tags = TagRe.findAllMatchIn(masked).toList
    if tags.isEmpty then NoBlocks
    else
      // console _outputTagsBalanced walk, on the masked text.
      var open = false
      var saw = false
      var ok = true
      val it = tags.iterator
      while ok && it.hasNext do
        val m = it.next()
        if m.matched == OpenTag then
          if open then ok = false // nesting = not the agreed shape
          else
            open = true
            saw = true
        else if !open then ok = false // stray close
        else open = false
      if !ok || !saw || open then Unbalanced
      else
        // Balanced: pair up and slice the original text. Every close here has
        // a matching open (the walk just proved it).
        val parts = List.newBuilder[String]
        var from = -1
        val it2 = tags.iterator
        while it2.hasNext do
          val m = it2.next()
          if m.matched == OpenTag then from = m.end
          else
            parts += text.substring(from, m.start).trim
            from = -1
        Extracted(parts.result().mkString("\n\n"))

    end if

  end extract

  /**
   * Same-length copy of `text` with every ``` fenced region replaced by NUL
   * chars (newlines preserved, so match offsets still index the original).
   * Fence shape (the same reading the console pipeline and
   * [[OutboundText.toPlain]] already apply): a fence opens at a line whose
   * trimmed form starts with ``` and closes at a line whose trimmed form is
   * backticks only (3+, no info string — CommonMark); an unterminated fence
   * runs to the end of the text. Fast path: text without ``` is returned
   * as-is.
   */
  private def maskFences(text: String): String =
    val sb = new StringBuilder(text.length)
    var fence = 0
    val lines = text.split("\n", -1)
    for (line, index) <- lines.zipWithIndex do
      val t = line.trim
      val ticks = t.takeWhile(_ == '`').length
      if fence > 0 then
        appendMask(sb, line)
        if ticks >= fence && t.drop(ticks).trim.isEmpty then fence = 0
      else if t.startsWith(">") then appendMask(sb, line)
      else if ticks >= 3 then
        appendMask(sb, line)
        fence = ticks
      else
        var i = 0
        while i < line.length do
          if line(i) == '`' then
            val start = i
            while i < line.length && line(i) == '`' do i += 1
            val delimiter = line.substring(start, i)
            val end = line.indexOf(delimiter, i)
            if end >= 0 then
              appendMask(sb, line.substring(start, end + delimiter.length))
              i = end + delimiter.length
            else sb.append(delimiter)
          else
            sb.append(line(i))
            i += 1
      end if
      if index < lines.length - 1 then sb.append('\n')
    end for
    sb.toString

  end maskFences

  /** Append `line` char-for-char masked (length preserved). */
  private def appendMask(sb: StringBuilder, line: String): Unit =
    var j = 0
    while j < line.length do
      sb.append(MaskChar)
      j += 1

end OutputBlock
