package nebflow.core.wiki

import cats.effect.unsafe.implicits.global
import munit.FunSuite

/**
 * TermIndex（B5-2 文档增强腿）的行为规范。
 *
 * 这套断言钉的是**增量**这个承诺，而不只是「能查到」：
 *   · 冷启动建表一次；
 *   · 稳态（无改动）**零重解析** —— 这是把「30s TTL + 整库重扫」换掉的全部意义；
 *   · 单文件改动只重解析那一个文件；
 *   · 删除文件的条目被剔除；
 *   · 子 vault 根（`<sub>/docs`）不吸收进父 vault 的词条表（与 graph 隔离同口径）；
 *   · 非 markdown 不索引（`.html` 无块锚段落语义）；
 *   · 消解裁定与 `selectTerm` 完全一致（歧义永不猜）。
 */
class TermIndexSpec extends FunSuite:

  /** 在临时目录里铺一个 vault；`rel` 用 `/` 分隔。 */
  private def makeVault(files: (String, String)*): os.Path =
    val dir = os.temp.dir(prefix = "term-index-spec")
    files.foreach { case (rel, text) =>
      val p = dir / os.RelPath(rel)
      os.makeDir.all(p / os.up)
      os.write.over(p, text)
    }
    dir

  private val Glossary = "GCR — 图形捕获区域。 ^gcr\n\nDCL — DOM 解析完成。 ^dcl\n"
  private val NoteDup = "DUP — 第一个声明。 ^term-dup\n"

  private def run(index: TermIndex, vault: os.Path, name: String): (WikiIndex.TermSelection, Int) =
    index.lookup("spec|docs", vault, Set.empty, name).unsafeRunSync()

  test("TI-1 冷启动：glossary 块锚段落成为词条，消解带块锚") {
    val v = makeVault("glossary.md" -> Glossary)
    val idx = new TermIndex
    val (sel, reparsed) = run(idx, v, "gcr")
    sel match
      case WikiIndex.TermSelection.Resolved(t) =>
        assertEquals(t.name, "GCR")
        assertEquals(t.anchor, "#^gcr")
        assertEquals(t.source, "glossary")
        assert(t.definition.contains("图形捕获区域"))
      case other => fail(s"expected Resolved(GCR), got $other")
    assertEquals(reparsed, 1, "首次建表只解析一个文件")
  }

  test("TI-2 稳态：第二次查询零重解析（这就是换掉整库重扫的收益）") {
    val v = makeVault("glossary.md" -> Glossary)
    val idx = new TermIndex
    val first = run(idx, v, "gcr")
    val second = run(idx, v, "dcl")
    assert(first._2 >= 1)
    assertEquals(second._2, 0, "指纹未变 ⇒ 不得重解析")
    second._1 match
      case WikiIndex.TermSelection.Resolved(t) => assertEquals(t.name, "DCL")
      case other => fail(s"expected Resolved(DCL), got $other")
  }

  test("TI-3 单文件增量：只重解析变化的那个文件，新词条立即可查") {
    val v = makeVault("glossary.md" -> Glossary)
    val idx = new TermIndex
    run(idx, v, "gcr")
    os.write.over(v / "note.md", NoteDup)
    val (sel, reparsed) = run(idx, v, "dup")
    sel match
      case WikiIndex.TermSelection.Resolved(t) => assertEquals(t.name, "DUP")
      case other => fail(s"expected Resolved(DUP), got $other")
    assertEquals(reparsed, 1, "稳态之后再改一个文件 ⇒ 只重解析 1 个")
    assertEquals(idx.stats("spec|docs")._1, 2, "文件数 = 2")
  }

  test("TI-4 改动同一文件：新内容生效、旧词条消失") {
    val v = makeVault("glossary.md" -> Glossary)
    val idx = new TermIndex
    assert(run(idx, v, "gcr")._1.isInstanceOf[WikiIndex.TermSelection.Resolved])
    os.write.over(v / "glossary.md", "XYZ — 换了内容。 ^xyz\n")
    assertEquals(run(idx, v, "gcr")._1, WikiIndex.TermSelection.NotFound)
    run(idx, v, "xyz")._1 match
      case WikiIndex.TermSelection.Resolved(t) => assertEquals(t.name, "XYZ")
      case other => fail(s"expected Resolved(XYZ), got $other")
  }

  test("TI-5 删除文件：条目被剔除，不再命中") {
    val v = makeVault("glossary.md" -> Glossary, "note.md" -> NoteDup)
    val idx = new TermIndex
    assert(run(idx, v, "dup")._1.isInstanceOf[WikiIndex.TermSelection.Resolved])
    os.remove(v / "note.md")
    assertEquals(run(idx, v, "dup")._1, WikiIndex.TermSelection.NotFound)
    assertEquals(idx.stats("spec|docs")._1, 1)
  }

  test("TI-6 子 vault 剪枝：<sub>/docs 下的词条不进父 vault 表") {
    val v = makeVault(
      "glossary.md" -> Glossary,
      "sub/docs/glossary.md" -> "SUBTERM — 子 vault 词条。 ^sub\n"
    )
    val idx = new TermIndex
    assertEquals(run(idx, v, "subterm")._1, WikiIndex.TermSelection.NotFound)
    assertEquals(idx.stats("spec|docs")._1, 1, "子 vault 根下不计数")
  }

  test("TI-7 非 markdown 不索引（.html 无块锚段落语义）") {
    val v = makeVault(
      "glossary.md" -> Glossary,
      "page.html" -> "<h1>X</h1><p>HTMLTERM — 定义。 ^html</p>\n"
    )
    val idx = new TermIndex
    run(idx, v, "gcr")
    assertEquals(run(idx, v, "htmlterm")._1, WikiIndex.TermSelection.NotFound)
    assertEquals(idx.stats("spec|docs")._1, 1)
  }

  test("TI-8 歧义：两份文档声明同一键 ⇒ Ambiguous（永不猜）") {
    val v = makeVault("a.md" -> NoteDup, "b.md" -> NoteDup)
    val idx = new TermIndex
    run(idx, v, "a")
    run(idx, v, "dup")._1 match
      case WikiIndex.TermSelection.Ambiguous(candidates) => assertEquals(candidates.size, 2)
      case other => fail(s"expected Ambiguous, got $other")
  }

  test("TI-9 别名键：`名（全称）` 的派生键可查") {
    val v = makeVault("glossary.md" -> "DCL（DOMContentLoaded） — 事件。 ^dcl\n")
    val idx = new TermIndex
    run(idx, v, "dcl")
    run(idx, v, "domcontentloaded")._1 match
      case WikiIndex.TermSelection.Resolved(t) => assertEquals(t.name, "DCL（DOMContentLoaded）")
      case other => fail(s"expected Resolved via alias, got $other")
  }

  test("TI-10 vault 不存在 ⇒ 空表、不炸") {
    val idx = new TermIndex
    val missing = os.temp.dir(prefix = "term-index-missing") / "nope"
    val (sel, reparsed) = idx.lookup("spec|docs", missing, Set.empty, "anything").unsafeRunSync()
    assertEquals(sel, WikiIndex.TermSelection.NotFound)
    assertEquals(reparsed, 0)
  }
