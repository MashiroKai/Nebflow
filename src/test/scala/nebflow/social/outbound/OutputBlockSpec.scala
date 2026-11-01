package nebflow.social.outbound

import munit.FunSuite

/**
 * Offline spec for the `<output>` user-visible block extraction — the CHANNEL
 * outbound face's single implementation source (output 块纪律渠道接线批,
 * 2026-11-01; author's order: the weixin/feishu outbound legs strip to the
 * block-inner join, semantics aligned with the console face verbatim).
 *
 * Five decision tables the author's order names, plus the unbalanced family
 * the streaming hold reads:
 *
 *   1. 无块   — narration only ⇒ NoBlocks (deliver NOTHING, never a full-text
 *      fallback);
 *   2. 单块   — one pair ⇒ Extracted, body trimmed;
 *   3. 多块   — several pairs ⇒ blank-line join, in order;
 *   4. 围栏含标签 — literal `<output>` inside a ``` fence is CONTENT, never
 *      structure (author's order: the block shape exists to dodge md syntax
 *      clashes — the extractor must not be fooled by fences);
 *   5. 空块   — `<output></output>` ⇒ Extracted("") — consumed, nothing
 *      sendable.
 *
 * Each assertion is red-by-construction: the fence mask removal, the balance
 * walk, or the blank-line join each turns a named case red.
 */
class OutputBlockSpec extends FunSuite:

  // ── 1. 无块 ────────────────────────────────────────────────────────────────

  test("OB-1 no tags at all ⇒ NoBlocks (the no-full-text-fallback rule)") {
    assertEquals(OutputBlock.extract(" plain narration, no tags "), OutputBlock.NoBlocks)
    assertEquals(OutputBlock.extract(""), OutputBlock.NoBlocks)
  }

  // ── 2. 单块 ────────────────────────────────────────────────────────────────

  test("OB-2 single block ⇒ the trimmed body, nothing else") {
    assertEquals(OutputBlock.extract("前叙述 <output> 你好 </output> 后叙述"), OutputBlock.Extracted("你好"))
    assertEquals(OutputBlock.extract("<output>line1\nline2</output>"), OutputBlock.Extracted("line1\nline2"))
  }

  // ── 3. 多块 ────────────────────────────────────────────────────────────────

  test("OB-3 multiple blocks ⇒ blank-line join, in order, bodies trimmed") {
    assertEquals(
      OutputBlock.extract("<output>A</output>\n中间叙述\n<output>  B  </output>"),
      OutputBlock.Extracted("A\n\nB")
    )
    // Multi-line bodies keep their internal shape; only the edges are trimmed.
    assertEquals(
      OutputBlock.extract("<output>a1\na2</output><output>b1</output>"),
      OutputBlock.Extracted("a1\na2\n\nb1")
    )
  }

  // ── 4. 围栏含标签（作者令：提取器不能反被围栏骗了） ────────────────────────

  test("OB-4a literal tags inside a ``` fence are content, never structure") {
    val fenced = "看这段：\n```html\n<output>hi</output>\n```\n完毕"
    // The only tags live inside the fence: no valid block OUTSIDE ⇒ NoBlocks.
    assertEquals(OutputBlock.extract(fenced), OutputBlock.NoBlocks)
  }

  test("OB-4b fenced literal tags never get consumed by outside blocks") {
    // An outside block coexisting with fenced literal tags: only the outside
    // bodies extract; the fence body stays exactly as written.
    assertEquals(
      OutputBlock.extract("<output>A</output>\n```html\n<output>hi</output>\n```\n<output>B</output>"),
      OutputBlock.Extracted("A\n\nB")
    )
  }

  test("OB-4c an unterminated fence hides everything to the end of the text") {
    assertEquals(OutputBlock.extract("```scala\nval x = \"<output>y</output>\""), OutputBlock.NoBlocks)
  }

  test("OB-4d the close fence must be backticks only (no info string)") {
    // ```with-info is NOT a close: the fence stays open and hides the rest of
    // the text — no valid block exists anywhere ⇒ NoBlocks.
    val notClosed = "```html\n<output>x</output>\n```ignored\n<output>y</output>"
    assertEquals(OutputBlock.extract(notClosed), OutputBlock.NoBlocks)
    // A bare ``` line closes it: the pair after is structure again.
    val closed = "```html\n<output>x</output>\n```\n<output>y</output>"
    assertEquals(OutputBlock.extract(closed), OutputBlock.Extracted("y"))
  }

  // ── 5. 空块 ────────────────────────────────────────────────────────────────

  test("OB-5 empty block ⇒ Extracted(\"\") — consumed, nothing sendable") {
    assertEquals(OutputBlock.extract("<output></output>"), OutputBlock.Extracted(""))
    assertEquals(OutputBlock.extract("<output>   </output>"), OutputBlock.Extracted(""))
    // Mixed with a real block: the empty one still occupies its blank-line slot
    // — the console join is verbatim-identical (["","real"].mkString("\n\n")).
    assertEquals(OutputBlock.extract("<output></output><output>real</output>"), OutputBlock.Extracted("\n\nreal"))
  }

  // ── the unbalanced family (streaming HOLD reads) ───────────────────────────

  test("OB-6 open without close (streaming intermediate) ⇒ Unbalanced") {
    assertEquals(OutputBlock.extract("前叙述 <output>未完的"), OutputBlock.Unbalanced)
  }

  test("OB-7 stray close ⇒ Unbalanced") {
    assertEquals(OutputBlock.extract("</output> 孤立闭标签"), OutputBlock.Unbalanced)
  }

  test("OB-8 nesting ⇒ Unbalanced (not the agreed shape)") {
    assertEquals(OutputBlock.extract("<output>a<output>b</output>c</output>"), OutputBlock.Unbalanced)
  }

  test("OB-9 the balance walk is fence-aware: a stray tag inside a fence does not poison outside blocks") {
    // The fence's literal stray close is content; the outside pair is fine.
    assertEquals(
      OutputBlock.extract("<output>ok</output>\n```\n</output>\n```"),
      OutputBlock.Extracted("ok")
    )
    // And the fence's literal pair does not rescue an outside unbalanced tag.
    assertEquals(OutputBlock.extract("<output>ok\n```\n<output>x</output>\n```"), OutputBlock.Unbalanced)
  }

end OutputBlockSpec
