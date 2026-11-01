package nebflow.social.outbound

/**
 * `<output>` 用户可见块提取 — 渠道出站腿的**单一实现源**（output 块纪律渠道
 * 接线批，2026-11-01；作者令「微信端和飞书端收到的消息也要做 output 块剥离，
 * 只显示 output 块内部的内容」）。
 *
 * 所有把 agent 回复文本投给用户的社交渠道出站渲染点（微信 iLink 腿、飞书腿
 * —— 仓内仅有的两个 [[nebflow.bridge.BridgePlugin]] 实现；未来的渠道复用本
 * 对象，**不做第二套解析**）都经 [[extract]] 收口。语义与控制台面逐字对齐
 * （web/js/utils.js 的 `_outputTagsBalanced` + `extractOutputInner`，同一批
 * 升级为围栏安全）：
 *
 *   - 只投递各 `<output>…</output>` 块内部内容的拼接：每块 body trim 后按
 *     出现序以空行连接（多块一条消息，与控制台气泡面同构）；
 *   - 块外文本一律不出站（叙述、工具过程叙述不投）；
 *   - 整条回复没有 output 块 ⇒ 不投递文本内容（**不回退成发全文** —— 作者令
 *     「与『只有用这个块包裹的内容用户才可见』一致」）；
 *   - **代码围栏安全**（作者令原文：块形态当初就是为了避开 md 语法冲突，
 *     提取器不能反被围栏骗了）：``` 围栏内的字面 `<output>` 是内容不是结构
 *     —— 围栏感知的掩码扫描（`[[maskFences]]`）把围栏区从标签扫描中剔除，
 *     控制台提取器同批升级同一条规则，两侧逐字一致。
 *
 * 纯函数、零 IO、零 SDK —— 离线可判（[[OutputBlockSpec]] 钉死五张判定表，
 * 两条渠道各有一条管线级测试证明出站 payload 只含块内内容）。
 *
 * ==特性总闸（output-block-gate 批，作者令「把 output 块相关的内容先关闭，
 * 因为我现在没有体会出来有什么区别」）==
 *
 * nebflow.json 顶层 `outputBlock.enabled`（**缺席 = 关**，作者令默认 false）是
 * 本特性两面的同一开关：
 *
 *   - 渠道剥离面（本对象）：`[[extractGated]]` 是四个出站 seam（微信/飞书 ×
 *     段/轮尾）的收口点——闸开 ⇒ 与 [[extract]] 逐字同判；闸关 ⇒ 原文以
 *     `Extracted` 直通（提取、HOLD、不回退语义整体旁路，渠道回全量原样）。
 *   - 前端渲染面：web/js/featureFlags.js `outputBlockEnabled()` 读同一键
 *     （WS configData 透传），关 = 掩码/徽标/展开交互全旁路、全文直出。
 *
 * 热读：`[[enabledFromConfig]]` 每次 flush 执行期求值（MsgTokenRegistry
 * .enabledFromConfig 同款形态）——配置改动下个 flush 即生效，无重启门槛。
 */
object OutputBlock:

  /**
   * 热读 `outputBlock.enabled`（GlobalSafety.defaultMode / MsgTokenRegistry
   * .enabledFromConfig 同款形态）：缺席 / 解析失败 / 类型不符 / 读盘异常 = 关
   * （默认 OFF = 作者令；闸是**恢复今日行为**的入口，不是缺省面）。
   *
   * prompt-face 补批 2026-11-01 起读实现收拢到
   * `nebflow.shared.OutputBlockGate.enabled()`（提示词组装面同用此读数——一个
   * 键，一份读实现；本方法保留为渠道出站 seam 的缺省构造缝别名）。
   */
  def enabledFromConfig(): Boolean = nebflow.shared.OutputBlockGate.enabled()

  /**
   * The one GATE-AWARE extraction entry — the four channel outbound seams'
   * single choke point. `gateOn = true` ⇒ identical to [[extract]]; `false`
   * ⇒ the raw text passes through as `Extracted(text)` (the pre-feature
   * behavior: full raw text ships, segment-by-segment — the strip, the HOLD
   * and the no-full-text-fallback semantics are all bypassed together).
   */
  def extractGated(text: String, gateOn: Boolean): Verdict =
    if gateOn then extract(text) else Extracted(text)

  private val OpenTag = "<output>"
  private val TagRe   = "</?output>".r

  /** The fence-mask char: NUL — never occurs in engine text, never matches the
    * tag regex; the mask preserves offsets 1:1 so pair bodies slice off the
    * ORIGINAL text. */
  private val MaskChar = '\u0000'

  /**
   * The extraction verdict for one piece of agent turn text.
   *
   *   - [[Extracted]] — ≥1 balanced pair OUTSIDE fences; `inner` is the
   *     blank-line join of every pair's trimmed body, in order. An EMPTY pair
   *     (`<output></output>`) extracts to `""` — the console face renders it
   *     as nothing and the channel sends nothing, but the text IS consumed
   *     (the block is complete and balanced).
   *   - [[NoBlocks]] — no `<output>` tag outside fences at all (narration /
   *     legacy text / tags that live only inside a fence). Delivers NOTHING —
   *     never a full-text fallback.
   *   - [[Unbalanced]] — tags outside fences exist but do not pair up:
   *     a streamed `<output>` whose close has not arrived yet (the streaming
   *     intermediate state), a stray close, or nesting. A segment flush reads
   *     this as HOLD (never pop, never send — the block may complete on later
   *     deltas; the console streaming discipline 「不炸不吞」); a turn-end
   *     flush reads it as nothing deliverable (there is no later flush, and an
   *     unterminated block wraps no user-visible content).
   */
  sealed trait Verdict
  final case class Extracted(inner: String) extends Verdict
  case object NoBlocks      extends Verdict
  case object Unbalanced    extends Verdict

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
      var saw  = false
      var ok   = true
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
        var from  = -1
        val it2 = tags.iterator
        while it2.hasNext do
          val m = it2.next()
          if m.matched == OpenTag then from = m.end
          else
            parts += text.substring(from, m.start).trim
            from = -1
        Extracted(parts.result().mkString("\n\n"))

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
    if !text.contains("```") then text
    else
      val sb      = new StringBuilder(text.length)
      var inFence = false
      val lines   = text.split("\n", -1)
      val n       = lines.length
      var i       = 0
      while i < n do
        val line = lines(i)
        val t    = line.trim
        if inFence then
          appendMask(sb, line)
          if t.length >= 3 && t.forall(_ == '`') then inFence = false
        else if t.startsWith("```") then
          appendMask(sb, line)
          inFence = true
        else sb.append(line)
        if i < n - 1 then sb.append('\n')
        i += 1
      sb.toString

  /** Append `line` char-for-char masked (length preserved). */
  private def appendMask(sb: StringBuilder, line: String): Unit =
    var j = 0
    while j < line.length do
      sb.append(MaskChar)
      j += 1

end OutputBlock
