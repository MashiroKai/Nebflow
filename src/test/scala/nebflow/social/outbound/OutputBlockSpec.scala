package nebflow.social.outbound

import munit.FunSuite

/**
 * Offline spec for the `<report>` user-visible block extraction — the CHANNEL
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
 *   4. 围栏含标签 — literal `<report>` inside a ``` fence is CONTENT, never
 *      structure (author's order: the block shape exists to dodge md syntax
 *      clashes — the extractor must not be fooled by fences);
 *   5. 空块   — `<report></report>` ⇒ Extracted("") — consumed, nothing
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
    assertEquals(OutputBlock.extract("前叙述 <report> 你好 </report> 后叙述"), OutputBlock.Extracted("你好"))
    assertEquals(OutputBlock.extract("<report>line1\nline2</report>"), OutputBlock.Extracted("line1\nline2"))
  }

  // ── 3. 多块 ────────────────────────────────────────────────────────────────

  test("OB-3 multiple blocks ⇒ blank-line join, in order, bodies trimmed") {
    assertEquals(
      OutputBlock.extract("<report>A</report>\n中间叙述\n<report>  B  </report>"),
      OutputBlock.Extracted("A\n\nB")
    )
    // Multi-line bodies keep their internal shape; only the edges are trimmed.
    assertEquals(
      OutputBlock.extract("<report>a1\na2</report><report>b1</report>"),
      OutputBlock.Extracted("a1\na2\n\nb1")
    )
  }

  // ── 4. 围栏含标签（作者令：提取器不能反被围栏骗了） ────────────────────────

  test("OB-4a literal tags inside a ``` fence are content, never structure") {
    val fenced = "看这段：\n```html\n<report>hi</report>\n```\n完毕"
    // The only tags live inside the fence: no valid block OUTSIDE ⇒ NoBlocks.
    assertEquals(OutputBlock.extract(fenced), OutputBlock.NoBlocks)
  }

  test("OB-4b fenced literal tags never get consumed by outside blocks") {
    // An outside block coexisting with fenced literal tags: only the outside
    // bodies extract; the fence body stays exactly as written.
    assertEquals(
      OutputBlock.extract("<report>A</report>\n```html\n<report>hi</report>\n```\n<report>B</report>"),
      OutputBlock.Extracted("A\n\nB")
    )
  }

  test("OB-4c an unterminated fence hides everything to the end of the text") {
    assertEquals(OutputBlock.extract("```scala\nval x = \"<report>y</report>\""), OutputBlock.NoBlocks)
  }

  test("OB-4d the close fence must be backticks only (no info string)") {
    // ```with-info is NOT a close: the fence stays open and hides the rest of
    // the text — no valid block exists anywhere ⇒ NoBlocks.
    val notClosed = "```html\n<report>x</report>\n```ignored\n<report>y</report>"
    assertEquals(OutputBlock.extract(notClosed), OutputBlock.NoBlocks)
    // A bare ``` line closes it: the pair after is structure again.
    val closed = "```html\n<report>x</report>\n```\n<report>y</report>"
    assertEquals(OutputBlock.extract(closed), OutputBlock.Extracted("y"))
  }

  // ── 5. 空块 ────────────────────────────────────────────────────────────────

  test("OB-5 empty block ⇒ Extracted(\"\") — consumed, nothing sendable") {
    assertEquals(OutputBlock.extract("<report></report>"), OutputBlock.Extracted(""))
    assertEquals(OutputBlock.extract("<report>   </report>"), OutputBlock.Extracted(""))
    // Mixed with a real block: the empty one still occupies its blank-line slot
    // — the console join is verbatim-identical (["","real"].mkString("\n\n")).
    assertEquals(OutputBlock.extract("<report></report><report>real</report>"), OutputBlock.Extracted("\n\nreal"))
  }

  // ── the unbalanced family (streaming HOLD reads) ───────────────────────────

  test("OB-6 open without close (streaming intermediate) ⇒ Unbalanced") {
    assertEquals(OutputBlock.extract("前叙述 <report>未完的"), OutputBlock.Unbalanced)
  }

  test("OB-7 stray close ⇒ Unbalanced") {
    assertEquals(OutputBlock.extract("</report> 孤立闭标签"), OutputBlock.Unbalanced)
  }

  test("OB-8 nesting ⇒ Unbalanced (not the agreed shape)") {
    assertEquals(OutputBlock.extract("<report>a<report>b</report>c</report>"), OutputBlock.Unbalanced)
  }

  test("OB-9 the balance walk is fence-aware: a stray tag inside a fence does not poison outside blocks") {
    // The fence's literal stray close is content; the outside pair is fine.
    assertEquals(
      OutputBlock.extract("<report>ok</report>\n```\n</report>\n```"),
      OutputBlock.Extracted("ok")
    )
    // And the fence's literal pair does not rescue an outside unbalanced tag.
    assertEquals(OutputBlock.extract("<report>ok\n```\n<report>x</report>\n```"), OutputBlock.Unbalanced)
  }

  // ── scope: the retired kill switch must have NO effect on this face ────────
  //    (report 块可见性批 2026-11-20: `outputBlock.enabled` 整体退役, and with it
  //    `OutputBlock.enabledFromConfig` — the channel face is scope-driven only)

  test("OB-G1 extractGated(false) bypasses the whole face — raw text passes through as Extracted") {
    // A balanced block ships RAW (tags verbatim), not the join.
    assertEquals(
      OutputBlock.extractGated("前叙述 <report>你好</report> 后叙述", rootSession = false),
      OutputBlock.Extracted("前叙述 <report>你好</report> 后叙述")
    )
    // An unbalanced (would-HOLD) shape passes through too — the hold is bypassed.
    assertEquals(
      OutputBlock.extractGated("<report>未完", rootSession = false),
      OutputBlock.Extracted("<report>未完")
    )
    // Empty buffer: Extracted("") = consumed, nothing sendable (same as today).
    assertEquals(OutputBlock.extractGated("", rootSession = false), OutputBlock.Extracted(""))
  }

  test("OB-G2 extractGated(true) is verbatim extract") {
    assertEquals(
      OutputBlock.extractGated("前叙述 <report>你好</report> 后叙述", rootSession = true),
      OutputBlock.extract("前叙述 <report>你好</report> 后叙述")
    )
    assertEquals(OutputBlock.extractGated("plain", rootSession = true), OutputBlock.NoBlocks)
  }

  test("OB-G3 the retired `outputBlock.enabled` key is NOT read by this face any more") {
    val home = os.temp.dir(prefix = "nb-ob-retired-gate-")
    val prev = nebflow.shared.PathUtil.dataRoot
    nebflow.shared.PathUtil.setDataRoot(home)
    try
      val cfg = nebflow.shared.PathUtil.configJsonReadPath(home)
      // The compatibility reader is gone from the object surface — the verdict is
      // a pure function of (text, rootSession), so no config file can change it.
      os.write.over(cfg, """{"outputBlock":{"enabled":false}}""", createFolders = true)
      val offVerdict = (
        OutputBlock.extractGated("前 <report>块内</report> 后", rootSession = true),
        OutputBlock.extractGated("前 <report>块内</report> 后", rootSession = false)
      )
      os.write.over(cfg, """{"outputBlock":{"enabled":true}}""")
      val onVerdict = (
        OutputBlock.extractGated("前 <report>块内</report> 后", rootSession = true),
        OutputBlock.extractGated("前 <report>块内</report> 后", rootSession = false)
      )
      assertEquals(onVerdict, offVerdict, "config flips must not move the channel verdict (no full-text-leak fallback)")
      assertEquals(offVerdict._1, OutputBlock.Extracted("块内"), "root still ships the block body")
      assertEquals(
        offVerdict._2,
        OutputBlock.Extracted("前 <report>块内</report> 后"),
        "non-root still ships the raw text verbatim"
      )
      // 退役的读法已在编译面消失（引用即编译失败），本 spec 不再断言它存在。
    finally
      os.remove.all(home)
      nebflow.shared.PathUtil.setDataRoot(prev)
    end try
  }

end OutputBlockSpec
