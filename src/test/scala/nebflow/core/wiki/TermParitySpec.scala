package nebflow.core.wiki

import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite

/**
 * **跨语言一致性断言**的输出腿（term-system v2 · ② 的硬要求）。
 *
 * 引擎侧（Scala `WikiIndex.termsOf`）与抽取出口（`check_wiki_docs.py
 * --dump-terms`）读同一个 fixture vault，必须给出**逐条相同**的
 * `(name, rel, anchor, tags)` 集合。本 spec 只负责把 Scala 侧算出来的那一半
 * 落成 `target/term-parity-scala.json`（比对由 shell 步 `scripts/term-parity-check.sh`
 * 完成：它跑 python dump → `target/term-parity-python.json`，再规范化比对）。
 *
 * 为什么拆成「spec 出文件 + shell 比对」而不是在 spec 里调 python：sbt 不该
 * 依赖 python3 与插件脚本路径（AI 节点/CI 环境未必有）；比对程序单独跑则两边
 * 的输出都能贴、都能复算。
 *
 * fixture = `src/test/resources/wiki-term-parity/`：
 *   · `glossary.md`——glossary 源（**每块都算词条**，不看 `^term-` 前缀）；
 *     三条带标签声明（含一个多标签：`#topic/graph` + `#topic/term-index` +
 *     `#ctx/engine`）、一条无标签的旧声明；两条是列表符号写法（真实 vault 的
 *     主流写法，见 PAR-3）；
 *   · `index.md`——普通文档（**只认 `^term-` 块**）里的一条 doc-intro 词条，
 *     以及 `[[词条索引库]]` / `[[term index db]]` / `[[检索]]` 三条短形引用和
 *     一条**悬空引用** `[[不存在的词条]]`。
 */
class TermParitySpec extends FunSuite:

  private val FixtureDir = os.pwd / "src" / "test" / "resources" / "wiki-term-parity"
  private val ScalaOut = os.pwd / "target" / "term-parity-scala.json"

  /** vault 下的 markdown 文件（vault 相对路径，`/` 分隔，字典序）。 */
  private def mdRels(dir: os.Path): List[String] =
    if !os.isDir(dir) then Nil
    else
      os.walk(dir)
        .filter(os.isFile)
        .filter(p => p.last.toLowerCase.endsWith(".md"))
        .map(_.relativeTo(dir).toString.replace('\\', '/'))
        .toList
        .sorted

  private def scalaTerms: List[WikiIndex.WikiTerm] =
    mdRels(FixtureDir).flatMap { rel =>
      WikiIndex.termsOf(rel, WikiIndex.parseDoc(rel, os.read(FixtureDir / os.RelPath(rel))))
    }

  private def termJson(t: WikiIndex.WikiTerm): Json = Json.obj(
    "name" -> t.name.asJson,
    "rel" -> t.rel.asJson,
    "anchor" -> t.anchor.asJson,
    "tags" -> WikiIndex.termTagsJson(t.tags)
  )

  test("PAR-1 fixture 覆盖齐全（glossary + 普通文档 + 多标签 + 别名 + ^term- 块 + 悬空引用）") {
    assert(os.isDir(FixtureDir), s"fixture vault 必须提交在仓库里：$FixtureDir")
    val terms = scalaTerms
    assertEquals(terms.map(_.rel).distinct.sorted, List("glossary.md", "index.md"))
    assertEquals(terms.map(_.source).distinct.sorted, List("doc", "glossary"), "两条腿都要覆盖")
    assert(terms.headOption.exists(_.anchor.startsWith("#^")), "块锚形态")
    assert(terms.exists(t => t.source == "doc" && t.anchor.startsWith("#^term-")), "doc 腿只认 ^term- 块")
    val multi = terms.filter(_.tags.count(_.facet == "topic") > 1)
    assert(multi.nonEmpty, "至少要有一条多主题标签的词条（排序/去重的断言对象）")
    assert(terms.exists(_.tags.exists(_.facet == "extra")), "至少一条 extra 标签")
    assert(terms.exists(_.tags.isEmpty), "至少一条无标签的旧声明（空桶形状要进比对）")
    assert(terms.exists(_.aliases.size >= 2), "至少一条带别名的词条")
    val indexText = os.read(FixtureDir / "index.md")
    assert(indexText.contains("[[不存在的词条]]"), "悬空引用必须在 fixture 里（refs.resolved=false 的那条）")
  }

  test("PAR-2 落盘 Scala 侧结果 → target/term-parity-scala.json（shell 比对腿的输入）") {
    val terms = scalaTerms
    val payload = Json.obj(
      "root" -> FixtureDir.toString.asJson,
      "generator" -> "WikiIndex.termsOf".asJson,
      "terms" -> terms.sortBy(t => (t.rel, t.line, t.name)).map(termJson).asJson
    )
    os.makeDir.all(ScalaOut / os.up)
    os.write.over(ScalaOut, payload.spaces2)
    assert(os.exists(ScalaOut), s"比对腿要靠这个文件：$ScalaOut")
    assertEquals(terms.size, 5, "fixture 当前应有 5 条词条（改 fixture 要同步这条）")
  }

  test("PAR-3 已知跨语言差异（钉住，不当回归）：列表符号 `- ` Scala 剥掉、Python 保留") {
    // 实证（本次批的 python dump）：`- 词条索引库（term index db） — … ^term-index-db`
    //   · Scala（本 spec）：name = "词条索引库（term index db）"（`^[#*-]+` 前缀剥除）
    //   · Python（check_wiki_docs.py:terms_of）：name = "- 词条索引库（term index db）"
    //     （只 `.strip()`，不剥列表符号；别名同带前缀）
    // 后果：库/图谱的显示名在列表写法上会带 `- `（查找键不受影响——CLI 的
    // clean_name 会剥）。修法在上游抽取出口一行正则，本节点无权改（check_wiki_docs.py
    // 归 syntax 节点）。此处只钉住「Scala 侧一定是干净名」，防有人照 Python 改回去。
    val bulleted = scalaTerms.filter(_.anchor == "#^term-index-db")
    assertEquals(bulleted.map(_.name), List("词条索引库（term index db）"))
    assertEquals(bulleted.head.aliases, List("词条索引库", "term index db"), "别名 = 尾部括注拆出的两组，保声明序")
    assert(!bulleted.head.name.startsWith("- "), "Scala 侧名字不带列表符号")
  }
