package nebflow.core.project

import munit.FunSuite

/**
 * BlockedReader 单测（blocked 反馈重入设计 §1.3 文法）：
 * - 合法 BLOCKED + JSON 体 → 结构化 BlockedFeedback
 * - JSON 缺失/畸形 → other + 其余全文截断降级
 * - 正文含 BLOCKED 不误判（只查开头锚定）
 * - category 不在枚举 → other
 * - 非 BLOCKED 开头 → None（completed 原路径无损降级）
 */
class BlockedReaderSpec extends FunSuite:

  test("parse: BLOCKED anchored + valid JSON body → structured feedback") {
    val text =
      """BLOCKED: 上游未就绪
        |{"category":"upstream-incomplete","detail":"节点 n-abc 结果为 null 字面串","suggestion":"需上游 X 先完成"}""".stripMargin
    val f = BlockedReader.parse(text).getOrElse(fail("expected blocked feedback"))
    assertEquals(f.category, "upstream-incomplete")
    assertEquals(f.detail, "节点 n-abc 结果为 null 字面串")
    assertEquals(f.suggestion, "需上游 X 先完成")
  }

  test("parse: BLOCKED with JSON body embedded after prose line") {
    val text = "BLOCKED\n我无法继续：任务缺少必要的外部条件。\n{\"category\":\"external-dependency\",\"detail\":\"等待 API key\",\"suggestion\":\"请提供凭据后重派\"}"
    val f = BlockedReader.parse(text).getOrElse(fail("expected blocked feedback"))
    assertEquals(f.category, "external-dependency")
    assertEquals(f.detail, "等待 API key")
    assertEquals(f.suggestion, "请提供凭据后重派")
  }

  test("parse: bare BLOCKED token alone → other with empty detail") {
    val f = BlockedReader.parse("BLOCKED").getOrElse(fail("expected blocked feedback"))
    assertEquals(f.category, "other")
    assertEquals(f.detail, "")
    assertEquals(f.suggestion, "")
  }

  test("parse: JSON missing → other + remainder text truncated as detail") {
    val text = "BLOCKED: 任务定义不完整，无法继续\n需要明确交付物格式与验收标准。"
    val f = BlockedReader.parse(text).getOrElse(fail("expected blocked feedback"))
    assertEquals(f.category, "other")
    assert(f.detail.contains("需要明确交付物格式与验收标准"), s"detail must carry remainder, got: ${f.detail}")
    assert(!f.detail.startsWith("BLOCKED"), "marker line must be stripped from fallback detail")
    assertEquals(f.suggestion, "")
  }

  test("parse: malformed JSON body → other + remainder fallback") {
    val text = "BLOCKED\n{category: upstream-incomplete, detail: 不是合法 JSON}"
    val f = BlockedReader.parse(text).getOrElse(fail("expected blocked feedback"))
    assertEquals(f.category, "other")
    assert(f.detail.nonEmpty, "fallback detail must carry the remaining text")
  }

  test("parse: category outside enum → normalized to other") {
    val text = """BLOCKED
                 |{"category":"totally-unknown-kind","detail":"d","suggestion":"s"}""".stripMargin
    val f = BlockedReader.parse(text).getOrElse(fail("expected blocked feedback"))
    assertEquals(f.category, "other")
    assertEquals(f.detail, "d")
    assertEquals(f.suggestion, "s")
  }

  test("parse: all six enum categories accepted verbatim") {
    val cats = List("upstream-incomplete", "task-underspecified", "agent-mismatch", "external-dependency", "needs-split", "other")
    cats.foreach { c =>
      val text = s"""BLOCKED\n{"category":"$c","detail":"d","suggestion":"s"}"""
      assertEquals(BlockedReader.parse(text).map(_.category), Some(c), s"category $c must be accepted")
    }
  }

  test("parse: BLOCKED inside body must NOT trigger (anchored check only)") {
    assertEquals(BlockedReader.parse("The task is BLOCKED because upstream failed"), None)
    assertEquals(BlockedReader.parse("输出里提到 BLOCKED 但任务完成了"), None)
    assertEquals(BlockedReader.parse(" result is BLOCKED-like"), None)
  }

  test("parse: BLOCKEDxx (no boundary) does not trigger") {
    assertEquals(BlockedReader.parse("BLOCKEDS: not a marker"), None)
  }

  test("parse: non-BLOCKED output → None (completed path, 无损降级)") {
    assertEquals(BlockedReader.parse("normal completed result"), None)
    assertEquals(BlockedReader.parse(""), None)
    assertEquals(BlockedReader.parse("   \n  "), None)
    // completed 结果的 JSON 体不误判
    assertEquals(BlockedReader.parse("""任务完成。{"summary":"全部搞定"}"""), None)
  }

  test("parse: lowercase 'blocked' does not trigger (文法规定大写锚定)") {
    assertEquals(BlockedReader.parse("blocked: upstream not ready"), None)
  }

  test("render: 落库渲染串格式 [blocked:<category>] <detail> — 建议: <suggestion>") {
    assertEquals(
      BlockedReader.render(BlockedFeedback("needs-split", "任务过大", "拆为 A+B 两个节点")),
      "[blocked:needs-split] 任务过大 — 建议: 拆为 A+B 两个节点")
  }

end BlockedReaderSpec
