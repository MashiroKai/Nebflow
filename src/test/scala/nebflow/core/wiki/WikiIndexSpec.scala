package nebflow.core.wiki

import munit.FunSuite

/**
 * Offline spec for the wiki link parser and vault graph assembly. Pure
 * functions only — no disk, no network. The fixtures pin the Obsidian link
 * faces the wiki graph and the markdown viewer both depend on.
 */
class WikiIndexSpec extends FunSuite:

  // ───────────────────── parseDoc ─────────────────────

  test("WI-1 wikilinks: plain, aliased, heading-anchored, block-anchored, embed") {
    val doc = WikiIndex.parseDoc(
      "a.md",
      """See [[target]] and [[target|the alias]].
        |Cite [[glossary#Retry storm]] and [[glossary#^retry-storm]].
        |Embed ![[pic.png]] inline.
        |""".stripMargin
    )
    val links = doc.links.map(l => (l._1, l._2, l._3)) // (target, anchor, kind)
    assertEquals(links.map(_._1), List("target", "target", "glossary", "glossary", "pic.png"))
    assertEquals(links.map(_._2), List(None, None, Some("Retry storm"), Some("^retry-storm"), None))
    assertEquals(links(4)._3, "embed")
    assertEquals(links(0)._3, "link")
  }

  test("WI-2 links inside fenced code blocks are not edges") {
    val doc = WikiIndex.parseDoc(
      "a.md",
      """Real link: [[real]].
        |```scala
        |val s = "[[in-code]]"
        |```
        |~~~text
        |[[in-tilde]]
        |~~~
        |After: [[after]]""".stripMargin
    )
    assertEquals(doc.links.map(_._1), List("real", "after"))
  }

  test("WI-3 headings and the H1 title") {
    val doc = WikiIndex.parseDoc(
      "auth-review.md",
      """# Auth review
        |
        |## Token lifetime
        |body
        |### Deep heading""".stripMargin
    )
    assertEquals(doc.title, "Auth review")
    assertEquals(
      doc.headings.map(h => (h.text, h.line)),
      List(("Auth review", 1), ("Token lifetime", 3), ("Deep heading", 5))
    )
  }

  test("WI-4 block ids attach to their source line (1-based)") {
    val doc = WikiIndex.parseDoc(
      "g.md",
      """# G
        |
        |Retry storm — a feedback loop. ^retry-storm
        |next line""".stripMargin
    )
    assertEquals(doc.blocks.map(b => (b.id, b.line)), List(("retry-storm", 3)))
  }

  test("WI-5 markdown links with percent-encoded destinations decode without folding +") {
    val doc = WikiIndex.parseDoc(
      "a.md",
      """[a](b%20name.md) and [c](d%2Be.md#L42) and [x](f.md)""".stripMargin
    )
    assertEquals(doc.links.map(_._1), List("b name.md", "d+e.md", "f.md"))
    assertEquals(doc.links(1)._2, Some("L42"))
  }

  // ───────────────────── buildVaultScan ─────────────────────

  test("WI-6 edges resolve by exact rel path, then unique basename; unresolved become phantom nodes") {
    val scan = WikiIndex.buildVaultScan(
      "proj",
      "self",
      List(
        "notes/a.md" -> "links: [[notes/b.md]] and [[b]] and [[missing]]",
        "notes/b.md" -> "back to [[a]]"
      )
    )
    val fromA = scan.edges.filter(_.from == "proj:notes/a.md")
    assertEquals(fromA.map(_.to), List("proj:notes/b.md", "proj:notes/b.md", "proj:missing.md"))
    // Phantom node exists for the unresolved target, flagged stub.
    assert(scan.nodes.exists(n => n.id == "proj:missing.md" && n.stub))
    // Real nodes are not stubs.
    assert(scan.nodes.filter(_.rel.endsWith("a.md")).forall(n => !n.stub))
  }

  test("WI-7 ambiguous basename (two matches) stays unresolved — never a silent guess") {
    val scan = WikiIndex.buildVaultScan(
      "proj",
      "self",
      List(
        "x/a.md" -> "see [[a]]",
        "x/one.md" -> "x",
        "y/two.md" -> "y"
      )
    )
    // Two candidate basenames? "a" matches exactly one doc (x/a.md) → resolved.
    // Renamed fixture: a link whose basename matches TWO documents resolves to none.
    val scan2 = WikiIndex.buildVaultScan(
      "proj",
      "self",
      List(
        "x/a.md" -> "see [[dup]]",
        "p/dup.md" -> "1",
        "q/dup.md" -> "2"
      )
    )
    val edge = scan2.edges.find(_.from == "proj:x/a.md").get
    assertEquals(edge.to, "proj:dup.md")
    assert(scan2.nodes.exists(n => n.id == "proj:dup.md" && n.stub))
    // Sanity for scan: unique basename resolves.
    val edge1 = scan.edges.find(_.from == "proj:x/a.md").get
    assertEquals(edge1.to, "proj:x/a.md") // [[a]] → unique basename x/a.md → itself
  }

  test("WI-8 anchors ride the edge verbatim (prefixed with #)") {
    val scan = WikiIndex.buildVaultScan(
      "proj",
      "self",
      List("a.md" -> "[[b#Heading one]] [[b#^blk-1]] [[b|alias only]]", "b.md" -> "b")
    )
    val anchors = scan.edges.filter(_.from == "proj:a.md").map(_.anchor)
    assertEquals(anchors, List(Some("#Heading one"), Some("#^blk-1"), None))
    assertEquals(scan.edges.head.line, 1)
  }

  test("WI-9 normalizeTarget adds .md to extensionless targets and strips ./") {
    assertEquals(WikiIndex.normalizeTarget("./notes/topic"), "notes/topic.md")
    assertEquals(WikiIndex.normalizeTarget("already.md"), "already.md")
    assertEquals(WikiIndex.normalizeTarget("img.png"), "img.png")
    assertEquals(WikiIndex.normalizeTarget("a\\b.md"), "a/b.md")
  }

  // ───────────── scenario packs (frontmatter + attachments) ─────────────

  test("WI-10 frontmatter feeds docType/status; line numbers stay full-file") {
    val text = """---
type: research-log
status: open
---

# Research log

Day 1: framed the retry budget question. ^frame
"""
    val scan = WikiIndex.buildVaultScan("proj", "self", List("log.md" -> text))
    val node = scan.nodes.head
    assertEquals(node.docType, Some("research-log"))
    assertEquals(node.status, Some("open"))
    // Heading/block line numbers include the frontmatter offset (full-file coords).
    assertEquals(node.headings.map(h => (h.text, h.line)), List(("Research log", 6)))
    assertEquals(node.blocks.map(b => (b.id, b.line)), List(("frame", 8)))
  }

  test("WI-11 attachments become kind=attachment nodes and resolve as link targets") {
    val scan = WikiIndex.buildVaultScan(
      "proj",
      "self",
      List("review.md" -> "Read [[chen-2024-retry.pdf]] and see ![[figure.png]]."),
      attachments = List("attachments/chen-2024-retry.pdf", "attachments/figure.png")
    )
    val att = scan.nodes.filter(_.kind == "attachment")
    assertEquals(att.map(_.rel).sorted, List("attachments/chen-2024-retry.pdf", "attachments/figure.png"))
    assert(att.forall(n => !n.stub))
    // Both links resolved to real nodes — no phantoms for present attachments.
    assert(scan.edges.forall(e => scan.nodes.exists(n => n.id == e.to && !n.stub)))
    assert(scan.edges.exists(_.to == "proj:attachments/chen-2024-retry.pdf"))
  }

  test("WI-12 frontmatter is excluded from heading/link extraction") {
    val doc = WikiIndex.parseDoc(
      "a.md",
      """---
type: glossary
see: [[not-a-link]]
---
body links [[real]]"""
    )
    assertEquals(doc.headings, Nil)
    assertEquals(doc.links.map(_._1), List("real"))
  }

  test("WI-13 file classification: md/html are docs, viewer files are attachments") {
    assertEquals(WikiIndex.DocExtensions.contains(".html"), true)
    assertEquals(WikiIndex.AttachmentExtensions.contains(".pdf"), true)
    assertEquals(WikiIndex.extOf("paper.PDF"), ".pdf")
    assertEquals(WikiIndex.extOf("noext"), "")
  }

  // ───────────── graph summary (wiki-graph redesign §5) ─────────────

  test("WI-14 summary: first paragraph after H1, inline markup stripped, links to text") {
    val doc = WikiIndex.parseDoc(
      "a.md",
      """# Auth review
        |
        |Read **this** note and `code`, see [[glossary|the glossary]] and [md link](x.md) and ![[pic.png]].
        |
        |## Next section
        |Not the first paragraph — the heading above bounded the scan window.
        |""".stripMargin
    )
    assertEquals(
      doc.summary,
      Some("Read this note and code, see the glossary and md link and pic.png.")
    )
  }

  test("WI-15 summary: quoted/list paragraphs read like prose; fenced code never contributes") {
    val doc = WikiIndex.parseDoc(
      "a.md",
      """# T
        |> Quoted first line
        |> second quoted line
        |
        |```scala
        |val insideFence = "[[not-a-summary]]"
        |```
        |after fence paragraph""".stripMargin
    )
    assertEquals(doc.summary, Some("Quoted first line second quoted line"))
  }

  test("WI-16 summary: no H1 ⇒ none; truncated at 140 chars without splitting a code point") {
    val noH1 = WikiIndex.parseDoc("a.md", "plain text, no heading at all")
    assertEquals(noH1.summary, None)
    val long = "# T\n\n" + "字" * 200
    val doc = WikiIndex.parseDoc("a.md", long)
    val s = doc.summary.get
    assertEquals(s.length, 140) // 139 kept chars + ellipsis, all inside the cap
    assert(s.endsWith("…"))
    assertEquals(WikiIndex.SummaryMaxChars, 140)
  }

  test("WI-17 nodeJson carries summary; nodes without one emit null") {
    val scan = WikiIndex.buildVaultScan(
      "proj",
      "self",
      List(
        "a.md" -> "# A\n\nFirst paragraph here with [[b]] reference.",
        "b.md" -> "no heading in b"
      )
    )
    val jsons = scan.nodes.map(WikiIndex.nodeJson)
    val a = jsons.find(_.hcursor.get[String]("rel").toOption.contains("a.md")).get
    assertEquals(a.hcursor.get[String]("summary").toOption, Some("First paragraph here with b reference."))
    val b = jsons.find(_.hcursor.get[String]("rel").toOption.contains("b.md")).get
    assertEquals(b.hcursor.get[Option[String]]("summary").toOption, Some(None))
  }

  // ───────────────────── #744-B multi-vault ─────────────────────

  test("WI-18 classifyExternals flips only cross-vault phantoms; true broken links stay broken") {
    val scan = WikiIndex.buildVaultScan(
      "proj",
      "self",
      List(
        "a.md" -> "# A\n\nSee [[sub/docs/c.md]] and [[ghost.md]].",
        "glossary.md" -> "# G\n\ndefinition. ^x"
      )
    )
    // "sub/docs/c.md" resolves into another vault of the same project (both the
    // vault-qualified form and plain sibling forms ride foreignRels).
    val classified = WikiIndex.classifyExternals(scan, Set("sub/docs/c.md"))
    assertEquals(classified.nodes.filter(_.external).map(_.rel), List("sub/docs/c.md"))
    assertEquals(classified.nodes.filter(n => n.stub && !n.external).map(_.rel), List("ghost.md"))
    // Empty foreign set ⇒ no-op (merged view / remote views keep legacy semantics).
    val untouched = WikiIndex.classifyExternals(scan, Set.empty)
    assertEquals(untouched.nodes.filter(_.external), Nil)
  }

  test("WI-19 nodeJson carries the external flag (default false); externalNode is stub+external") {
    val scan = WikiIndex.buildVaultScan("proj", "self", List("a.md" -> "# A"))
    val doc = scan.nodes.find(_.rel == "a.md").get
    assertEquals(WikiIndex.nodeJson(doc).hcursor.get[Boolean]("external").toOption, Some(false))
    val ext = WikiIndex.externalNode("proj", "self", "sub/docs/x.md")
    assertEquals(WikiIndex.nodeJson(ext).hcursor.get[Boolean]("external").toOption, Some(true))
    assertEquals(WikiIndex.nodeJson(ext).hcursor.get[Boolean]("stub").toOption, Some(true))
  }

  test("WI-23 paragraphs: glossary-style block run → term title, edge-form anchor, ^id stripped from text") {
    val doc = WikiIndex.parseDoc(
      "glossary.md",
      """# 词汇表
        |
        |引用语法一览（围栏内不是段落）：
        |
        |```
        |[[目标#^块锚]] 引用某个概念定义块
        |```
        |
        |文档库（vault） — 项目工作区内 `docs/` 目录整体：Obsidian 兼容，图谱视图（[[glossary#^graph-view]]）只扫这个目录。 ^vault
        |
        |multi line entry
        |second line of the entry ^entry-two
        |""".stripMargin
    )
    val paras = doc.paragraphs
    val vault = paras.find(_.anchor == "#^vault").get
    assertEquals(vault.kind, "block")
    assertEquals(vault.title, "文档库（vault）")
    assertEquals(vault.line, 9)
    assertEquals(vault.endLine, 9)
    assertEquals(vault.text, "文档库（vault） — 项目工作区内 docs/ 目录整体：Obsidian 兼容，图谱视图（glossary#^graph-view）只扫这个目录。")
    // multi-line run: line range covers the whole group; ^id stripped from text only
    val entry = paras.find(_.anchor == "#^entry-two").get
    assertEquals((entry.line, entry.endLine), (11, 12))
    assertEquals(entry.text, "multi line entry second line of the entry")
    // the fenced code lines never contribute
    assert(!paras.exists(_.text.contains("块锚]] 引用")))
  }

  test("WI-24 paragraphs: heading sections span to the next heading; anchor is the edge form") {
    val doc = WikiIndex.parseDoc(
      "a.md",
      """# T
        |
        |intro line one
        |intro line two
        |
        |## Section A
        |body of A
        |
        |## Section B
        |body of B""".stripMargin
    )
    val sa = doc.paragraphs.find(_.anchor == "#Section A").get
    assertEquals(sa.kind, "heading")
    assertEquals(sa.title, "Section A")
    assertEquals((sa.line, sa.endLine), (6, 8))
    assertEquals(sa.text, "body of A")
    val sb = doc.paragraphs.find(_.anchor == "#Section B").get
    assertEquals((sb.line, sb.endLine), (9, 10))
    val t = doc.paragraphs.find(_.anchor == "#T").get
    assertEquals((t.line, t.endLine), (1, 5))
    assertEquals(t.text, "intro line one intro line two")
  }

  test("WI-25 paragraphs: frontmatter offset keeps full-file coordinates; fences break runs") {
    val doc = WikiIndex.parseDoc(
      "a.md",
      """---
        |type: note
        |---
        |# T
        |
        |entry one ^one
        |
        |```text
        |fenced ^not-a-block-anchor
        |```
        |
        |entry two ^two""".stripMargin
    )
    val one = doc.paragraphs.find(_.anchor == "#^one").get
    assertEquals(one.line, 6) // 1-based FULL-FILE coords (frontmatter included)
    val two = doc.paragraphs.find(_.anchor == "#^two").get
    assertEquals(two.line, 12)
    assert(!doc.paragraphs.exists(_.anchor == "#^not-a-block-anchor"), "fenced lines never index")
  }

  test("WI-26 paragraphs: caps and nodeJson wiring; stub/attachment nodes carry none") {
    // title term-split fallback: no separator → cleaned first-line prefix capped at 60
    val doc = WikiIndex.parseDoc("a.md", "plain line without separator ^p1\n")
    val p = doc.paragraphs.find(_.anchor == "#^p1").get
    assertEquals(p.title, "plain line without separator")
    // per-doc cap
    val many = (1 to 70).map(i => s"entry $i text here enough length ^b$i\n\n").mkString
    assertEquals(WikiIndex.parseDoc("a.md", many).paragraphs.size, WikiIndex.ParagraphMaxPerDoc)
    assertEquals(WikiIndex.ParagraphMaxPerDoc, 64)
    assertEquals(WikiIndex.ParagraphMaxChars, 280)
    // nodeJson carries paragraphs; attachment/phantom nodes carry []
    val scan = WikiIndex.buildVaultScan(
      "proj",
      "self",
      List("a.md" -> "# A\n\nglossary term — the definition. ^term"),
      List("fig.png")
    )
    val a = scan.nodes.map(WikiIndex.nodeJson).find(_.hcursor.get[String]("rel").toOption.contains("a.md")).get
    val paras = a.hcursor.downField("paragraphs").as[List[Map[String, io.circe.Json]]].toOption.get
    assertEquals(paras.find(p => p("anchor").asString.contains("#^term")).map(_("kind").asString.get), Some("block"))
    val att = scan.nodes.map(WikiIndex.nodeJson).find(_.hcursor.get[String]("rel").toOption.contains("fig.png")).get
    assertEquals(att.hcursor.downField("paragraphs").as[List[io.circe.Json]].toOption.get, Nil)
  }

  test("WI-27 edgeAnchorForm: raw anchors normalize to the edge form the paragraphs index uses") {
    assertEquals(WikiIndex.edgeAnchorForm("^blk"), "#^blk")
    assertEquals(WikiIndex.edgeAnchorForm("#Heading"), "#Heading")
    assertEquals(WikiIndex.edgeAnchorForm("Heading"), "#Heading")
    assertEquals(WikiIndex.edgeAnchorForm("L42"), "#L42")
    // end-to-end: a block-anchored edge resolves against the target's paragraph index
    val from = WikiIndex.parseDoc("a.md", "see the [[g#^vault]] entry")
    val g = WikiIndex.parseDoc("g.md", "文档库（vault） — 定义内容。 ^vault")
    val edge = WikiIndex.buildEdges("proj", "self", "a.md", from, Set("a.md", "g.md")).head
    assertEquals(edge.anchor, Some("#^vault"))
    assert(g.paragraphs.exists(_.anchor == edge.anchor.get), "edge.anchor 直查 node.paragraphs 命中")
  }

  // ───────────── term registry (词条引用批) ─────────────

  private val glossaryDoc =
    """# 词汇表
      |
      |GCR（银河宇宙线） — 来自银河系的高能粒子流，图谱样例词条。 ^gcr
      |
      |重试风暴 — 失败触发重试、重试放大失败的反馈循环。 ^retry-storm
      |
      |无分隔符的词表段落不算词条 ^not-a-term
      |""".stripMargin

  private val introDoc =
    """# 报告
      |
      |## 词条
      |
      |- DCL（DOMContentLoaded 事件） — 浏览器首次可交互的时间点。 ^term-dcl
      |
      |- 归因结论 — 本批缺陷由配置漂移引入。 ^term-attrib
      |
      |普通锚段落不声明词条 ^plain-anchor
      |""".stripMargin

  test("WI-28 glossary terms: every block-anchored paragraph registers, source=glossary, paren aliases derived") {
    val parsed = WikiIndex.parseDoc("glossary.md", glossaryDoc)
    val terms = WikiIndex.termsOf("glossary.md", parsed)
    assertEquals(terms.map(_.name), List("GCR（银河宇宙线）", "重试风暴"))
    val gcr = terms.head
    assertEquals(gcr.source, "glossary")
    assertEquals(gcr.anchor, "#^gcr")
    assertEquals(gcr.rel, "glossary.md")
    assert(gcr.line > 0 && gcr.endLine >= gcr.line)
    assert(gcr.definition.startsWith("GCR（银河宇宙线） — 来自银河系"))
    // trailing-parenthetical alias keys: base + inner, both lowercase-lookup-able
    assertEquals(gcr.aliases, List("GCR", "银河宇宙线"))
    // the no-separator anchored block is NOT a term (registry stays clean;
    // the check script flags it as term-format instead)
    assert(!terms.exists(_.anchor == "#^not-a-term"))
    // markdown links inside the definition collapse to text (hover payload face)
    val withLink = WikiIndex.parseDoc("glossary.md", "# G\n\n词条 — 定义见 [[其他#^x]] 处。 ^t\n")
    assertEquals(WikiIndex.termsOf("glossary.md", withLink).head.definition, "词条 — 定义见 其他#^x 处。")
  }

  test(
    "WI-29 doc-intro terms: only ^term-* blocks register in ordinary docs; ^term- blocks lacking a separator don't"
  ) {
    val parsed = WikiIndex.parseDoc("report.md", introDoc)
    val terms = WikiIndex.termsOf("report.md", parsed)
    assertEquals(terms.map(_.name), List("DCL（DOMContentLoaded 事件）", "归因结论"))
    assert(terms.forall(_.source == "doc"))
    assertEquals(terms.head.aliases, List("DCL", "DOMContentLoaded 事件"))
    // ordinary anchored paragraphs in a non-glossary doc never register
    assert(!terms.exists(_.anchor == "#^plain-anchor"))
    // a ^term-* id with no ` —`/`：` separator yields no name ⇒ not a term
    val bad = WikiIndex.parseDoc("r.md", "# R\n\nsome words without separator ^term-x\n")
    assertEquals(WikiIndex.termsOf("r.md", bad), Nil)
    // glossary detection mirrors check_wiki_docs.py: stem (last ext stripped) contains "glossary"
    assert(WikiIndex.isGlossaryRel("glossary.md"))
    assert(WikiIndex.isGlossaryRel("sub/docs/Glossary.zh-CN.md"))
    assert(!WikiIndex.isGlossaryRel("report.md"))
  }

  test("WI-30 selectTerm ruling: glossary beats doc-intro, unique hit resolves, same-tier duplicates never guess") {
    // aliases here mirror what termsOf derives (trailing parenthetical groups)
    val gcr = WikiIndex.WikiTerm("GCR", List("GCR", "银河宇宙线"), "glossary.md", "#^gcr", 3, 3, "glossary", "定义 GCR")
    val storm =
      WikiIndex.WikiTerm("重试风暴", List("retry storm"), "glossary.md", "#^retry-storm", 5, 5, "glossary", "定义 重试风暴")
    val dclA = WikiIndex.WikiTerm("DCL", Nil, "a.md", "#^term-dcl", 2, 2, "doc", "A 的 DCL")
    val dclB = WikiIndex.WikiTerm("DCL", Nil, "b.md", "#^term-dcl", 2, 2, "doc", "B 的 DCL")
    val terms = List(gcr, storm, dclA, dclB)
    // glossary-priority: a doc-intro GCR cannot outshine the glossary entry
    val withShadow = terms :+ WikiIndex.WikiTerm("GCR", Nil, "a.md", "#^term-gcr", 2, 2, "doc", "A 的 GCR")
    assertEquals(WikiIndex.selectTerm("gcr", withShadow), WikiIndex.TermSelection.Resolved(gcr))
    // alias keys hit: paren-inner and explicit alias, case-insensitively
    assertEquals(WikiIndex.selectTerm("银河宇宙线", terms), WikiIndex.TermSelection.Resolved(gcr))
    assertEquals(WikiIndex.selectTerm("RETRY STORM", terms), WikiIndex.TermSelection.Resolved(storm))
    // unique doc-intro hit resolves
    assertEquals(WikiIndex.selectTerm("DCL", List(gcr, storm, dclA)), WikiIndex.TermSelection.Resolved(dclA))
    // same-tier duplicates (two docs' intro terms) stay ambiguous — never a guess
    assertEquals(WikiIndex.selectTerm("DCL", terms), WikiIndex.TermSelection.Ambiguous(List(dclA, dclB)))
    // two glossary entries of the same name = ambiguous (R4 duplicate face)
    val dupGloss = List(gcr, gcr.copy(rel = "sub-glossary.md"))
    assertEquals(WikiIndex.selectTerm("gcr", dupGloss), WikiIndex.TermSelection.Ambiguous(dupGloss))
    assertEquals(WikiIndex.selectTerm("nope", terms), WikiIndex.TermSelection.NotFound)
    assertEquals(WikiIndex.selectTerm("  ", terms), WikiIndex.TermSelection.NotFound)
  }

  test(
    "WI-31 edge resolution: term fallback is third priority — doc wins, term fills the gap, ambiguity stays phantom"
  ) {
    // registry: glossary GCR + doc-intro DCL; vault also has a real gcr.md doc
    val gParsed = WikiIndex.parseDoc("glossary.md", glossaryDoc)
    val rParsed = WikiIndex.parseDoc("report.md", introDoc)
    val terms = WikiIndex.termsOf("glossary.md", gParsed) ++ WikiIndex.termsOf("report.md", rParsed)
    val from = WikiIndex.parseDoc(
      "a.md",
      "see [[GCR]] and [[DCL]] and [[银河宇宙线]] and [[GCR#^gcr]] and [[NoTerm#^blk]] and [[ghost]]"
    )
    val knownRels = Set("a.md", "glossary.md", "report.md", "gcr.md")
    val edges = WikiIndex.buildEdges("proj", "self", "a.md", from, knownRels, terms)
    val tos = edges.map(e => (e.to, e.anchor))
    // ① same-name DOCUMENT wins — [[GCR]] hits the case-insensitive doc leg and the edge
    // rides the AUTHORED case ("GCR.md", pre-existing face of the doc legs); no term anchor
    assertEquals(tos(0), ("proj:GCR.md", None))
    // ② term fallback: [[DCL]] → the declaring document + its block anchor
    assertEquals(tos(1), ("proj:report.md", Some("#^term-dcl")))
    // ③ alias-key registry hit (paren-inner name), case handled by selectTerm
    assertEquals(tos(2), ("proj:glossary.md", Some("#^gcr")))
    // ④ existing doc+block edge is untouched by the term layer (authored case rides, as today)
    assertEquals(tos(3), ("proj:GCR.md", Some("#^gcr")))
    // ⑤ anchored targets never term-fall: unresolved doc + explicit anchor stays a phantom edge
    assertEquals(tos(4), ("proj:NoTerm.md", Some("#^blk")))
    // ⑥ unregistered bare name stays an unresolved phantom
    assertEquals(tos(5)._2, None)
    // full scan: term-resolved links create NO phantom nodes; scan.terms carries the registry
    val scan = WikiIndex.buildVaultScan(
      "proj",
      "self",
      List("a.md" -> "see [[DCL]]", "report.md" -> introDoc, "glossary.md" -> glossaryDoc)
    )
    assertEquals(scan.edges.find(_.from == "proj:a.md").get.to, "proj:report.md")
    assert(!scan.nodes.exists(n => n.stub && n.rel.equalsIgnoreCase("dcl.md")), "term hit must not phantom a dcl.md")
    assertEquals(scan.terms.map(_.name).sorted, List("DCL（DOMContentLoaded 事件）", "GCR（银河宇宙线）", "归因结论", "重试风暴"))
    // no registry in this vault ⇒ the same link stays an unresolved phantom — zero behavior change
    val legacy = WikiIndex.buildVaultScan("proj", "self", List("a.md" -> "see [[DCL]]"))
    assert(legacy.nodes.exists(n => n.stub && n.rel.equalsIgnoreCase("dcl.md")))
  }

  test("WI-32 nodeJson terms[] projection + VaultScan registry + decodeTerm round-trip") {
    val scan = WikiIndex.buildVaultScan(
      "proj",
      "self",
      List("report.md" -> introDoc, "glossary.md" -> glossaryDoc),
      List("fig.png")
    )
    val report = scan.nodes.find(_.rel == "report.md").get
    val json = WikiIndex.nodeJson(report)
    val terms = json.hcursor.downField("terms").as[List[io.circe.Json]].toOption.get
    assertEquals(
      terms.map(_.hcursor.downField("name").as[String].toOption.get),
      List("DCL（DOMContentLoaded 事件）", "归因结论")
    )
    val dcl = terms.head
    assertEquals(dcl.hcursor.downField("source").as[String].toOption, Some("doc"))
    assertEquals(dcl.hcursor.downField("rel").as[String].toOption, Some("report.md"))
    assertEquals(dcl.hcursor.downField("anchor").as[String].toOption, Some("#^term-dcl"))
    assertEquals(dcl.hcursor.downField("aliases").as[List[String]].toOption, Some(List("DCL", "DOMContentLoaded 事件")))
    // attachment + phantom nodes carry no terms
    val att = scan.nodes.find(_.kind == "attachment").get
    assertEquals(WikiIndex.nodeJson(att).hcursor.downField("terms").as[List[io.circe.Json]].toOption.get, Nil)
    // service-level ruling reads back through decodeTerm (JSON → model → selection)
    val decoded = terms.flatMap(WikiIndex.decodeTerm)
    assertEquals(
      decoded.flatMap(t =>
        WikiIndex.selectTerm("dcl", List(t)) match
          case WikiIndex.TermSelection.Resolved(t) => Some(t.anchor)
          case _ => None
      ),
      List("#^term-dcl")
    )
  }

  // ───────────────────── term tags v2 (词条标签) ─────────────────────

  test("WI-33 term tags v2: a legal declaration keeps its name/aliases and yields three deduped, sorted buckets") {
    val doc =
      """# 词条
        |
        |- DCL（DOMContentLoaded 事件） — 浏览器首次可交互的时间点。 #proj/stand #topic/term-index #topic/render #ctx/browser #proj/stand ^term-dcl
        |""".stripMargin
    val terms = WikiIndex.termsOf("report.md", WikiIndex.parseDoc("report.md", doc))
    assertEquals(terms.size, 1)
    val dcl = terms.head
    // the declaration face is untouched: name + derived paren aliases
    assertEquals(dcl.name, "DCL（DOMContentLoaded 事件）")
    assertEquals(dcl.aliases, List("DCL", "DOMContentLoaded 事件"))
    assertEquals(dcl.anchor, "#^term-dcl")
    // exactly three buckets, in project → topic → extra order, deduped + sorted
    assertEquals(
      dcl.tags,
      List(
        WikiIndex.WikiTermTag(WikiIndex.TagFacetProject, "stand"),
        WikiIndex.WikiTermTag(WikiIndex.TagFacetTopic, "render"),
        WikiIndex.WikiTermTag(WikiIndex.TagFacetTopic, "term-index"),
        WikiIndex.WikiTermTag(WikiIndex.TagFacetExtra, "ctx/browser")
      )
    )
    // tags are decoration only — they never become lookup keys
    assertEquals(WikiIndex.termKeys(dcl), List("dcl（domcontentloaded 事件）", "dcl", "domcontentloaded 事件"))
    assertEquals(WikiIndex.selectTerm("银河", List(dcl)), WikiIndex.TermSelection.NotFound)
    // two-segment parity with the Python TERM_TAG_RE: `#facet/a/b` yields `facet/a`
    assertEquals(
      WikiIndex.termTagsOf(" #proj/a #ctx/a/b "),
      List(
        WikiIndex.WikiTermTag(WikiIndex.TagFacetProject, "a"),
        WikiIndex.WikiTermTag(WikiIndex.TagFacetExtra, "ctx/a")
      )
    )
  }

  test("WI-34 term tags v2: a missing project or topic leaves that bucket empty (the term-tag-missing shape)") {
    def tagsOf(decl: String) =
      WikiIndex.termsOf("r.md", WikiIndex.parseDoc("r.md", s"# R\n\n$decl\n")).head.tags

    // topic only — the project bucket is empty
    val noProj = tagsOf("- 甲 — 定义。 #topic/a ^term-a")
    assertEquals(noProj, List(WikiIndex.WikiTermTag(WikiIndex.TagFacetTopic, "a")))
    assertEquals(
      WikiIndex
        .termTagsJson(noProj)
        .hcursor
        .downField("project")
        .as[List[String]]
        .toOption,
      Some(Nil)
    )

    // project only — the topic bucket is empty
    val noTopic = tagsOf("- 乙 — 定义。 #proj/stand ^term-b")
    assertEquals(noTopic, List(WikiIndex.WikiTermTag(WikiIndex.TagFacetProject, "stand")))
    assertEquals(
      WikiIndex
        .termTagsJson(noTopic)
        .hcursor
        .downField("topic")
        .as[List[String]]
        .toOption,
      Some(Nil)
    )

    // neither — extra facets never substitute for project/topic
    val neither = tagsOf("- 丙 — 定义。 #ctx/x #level/deep ^term-c")
    assertEquals(
      neither,
      List(
        WikiIndex.WikiTermTag(WikiIndex.TagFacetExtra, "ctx/x"),
        WikiIndex.WikiTermTag(WikiIndex.TagFacetExtra, "level/deep")
      )
    )
    assertEquals(tagsOf("- 丁 — 无标签的定义。 ^term-d"), Nil)
  }

  test("WI-35 term tags v2: a tag AFTER the ^block-id is not seen — the mid-line ^id is not an anchor, so no term") {
    val parsed = WikiIndex.parseDoc("r.md", "# R\n\n- 丙 — 定义。 ^term-c #proj/stand #topic/a\n")
    // BlockIdRe is line-anchored (same as the Python BLOCKID_RE): `^id` followed
    // by more text is NOT a block anchor, so the paragraph never becomes a term
    // (the script's misplaced-tag branch reports term-tag-missing for it).
    assert(!parsed.paragraphs.exists(_.anchor == "#^term-c"))
    assertEquals(WikiIndex.termsOf("r.md", parsed), Nil)

    // window boundary, other side: a tag BEFORE the definition separator is
    // outside the window too (name segment, not definition tail)
    val beforeSep =
      WikiIndex.termsOf("r.md", WikiIndex.parseDoc("r.md", "# R\n\n- 丁 #proj/stand — 定义。 #topic/a ^term-d\n")).head
    assertEquals(beforeSep.tags, List(WikiIndex.WikiTermTag(WikiIndex.TagFacetTopic, "a")))
  }

  test("WI-36 term tags v2: inline-code spans are not tags; a >280-char definition still exposes its tail tags") {
    // backticked examples are documentation, never tags
    val codeWindow = WikiIndex.tagWindow("- 词条 — 写法示例 `#proj/fake` 与 `#topic/fake` 都不算。 #proj/stand #topic/real")
    assertEquals(
      WikiIndex.termTagsOf(codeWindow),
      List(
        WikiIndex.WikiTermTag(WikiIndex.TagFacetProject, "stand"),
        WikiIndex.WikiTermTag(WikiIndex.TagFacetTopic, "real")
      )
    )

    // paragraph text is cleaned + capped at ParagraphMaxChars — the tag window
    // reads the RAW block body, so tail tags survive a long definition
    val longDef = "长定义文字" * 70 // 350 chars, well past the cap
    assert(longDef.length > WikiIndex.ParagraphMaxChars)
    val longParsed = WikiIndex.parseDoc("r.md", s"# R\n\n- 长词条 — $longDef #proj/stand #topic/long ^term-long\n")
    val para = longParsed.paragraphs.find(_.anchor == "#^term-long").get
    assert(para.text.length <= WikiIndex.ParagraphMaxChars && para.text.endsWith("…"))
    assert(!para.text.contains("#proj"))
    val longTerm = WikiIndex.termsOf("r.md", longParsed).head
    assertEquals(
      longTerm.tags,
      List(
        WikiIndex.WikiTermTag(WikiIndex.TagFacetProject, "stand"),
        WikiIndex.WikiTermTag(WikiIndex.TagFacetTopic, "long")
      )
    )

    // multi-line block: the window spans the whole group (lines joined by a
    // space), exactly like the Python tag_window
    val multi = WikiIndex
      .termsOf("r.md", WikiIndex.parseDoc("r.md", "# R\n\n- 戊 — 定义分两行写\n  续行带标签 #proj/stand #topic/multi ^term-e\n"))
      .head
    assertEquals(
      multi.tags,
      List(
        WikiIndex.WikiTermTag(WikiIndex.TagFacetProject, "stand"),
        WikiIndex.WikiTermTag(WikiIndex.TagFacetTopic, "multi")
      )
    )
  }

  test(
    "WI-37 term tags v2 JSON face: three buckets always present, decodeTerm round-trips, legacy payloads carry none"
  ) {
    val doc =
      """# 词条
        |
        |- 甲（alias one） — 定义。 #proj/stand #topic/render #ctx/browser ^term-a
        |
        |- 乙 — 没有标签的词条。 ^term-b
        |""".stripMargin
    val scan = WikiIndex.buildVaultScan("proj", "self", List("report.md" -> doc))
    val node = scan.nodes.find(_.rel == "report.md").get
    val jsonTerms = WikiIndex.nodeJson(node).hcursor.downField("terms").as[List[io.circe.Json]].toOption.get
    assertEquals(jsonTerms.size, 2)

    val tagsA = jsonTerms.head.hcursor.downField("tags").as[io.circe.Json].toOption.get
    assertEquals(tagsA.hcursor.downField("project").as[List[String]].toOption, Some(List("stand")))
    assertEquals(tagsA.hcursor.downField("topic").as[List[String]].toOption, Some(List("render")))
    assertEquals(tagsA.hcursor.downField("extra").as[List[String]].toOption, Some(List("ctx/browser")))

    // an untagged term keeps the same three-key shape (empty arrays, never absent)
    val tagsB = jsonTerms(1).hcursor.downField("tags").as[io.circe.Json].toOption.get
    assertEquals(tagsB.hcursor.downField("project").as[List[String]].toOption, Some(Nil))
    assertEquals(tagsB.hcursor.downField("topic").as[List[String]].toOption, Some(Nil))
    assertEquals(tagsB.hcursor.downField("extra").as[List[String]].toOption, Some(Nil))

    // JSON → model round-trip (the service lookup path) preserves the buckets
    val decoded = jsonTerms.flatMap(WikiIndex.decodeTerm)
    assertEquals(decoded, scan.terms)
    assertEquals(
      decoded.head.tags,
      List(
        WikiIndex.WikiTermTag(WikiIndex.TagFacetProject, "stand"),
        WikiIndex.WikiTermTag(WikiIndex.TagFacetTopic, "render"),
        WikiIndex.WikiTermTag(WikiIndex.TagFacetExtra, "ctx/browser")
      )
    )

    // a graph payload predating the tag face decodes to no tags, not a failure
    val legacy = WikiIndex.termJson(scan.terms.head).mapObject(_.remove("tags"))
    assertEquals(WikiIndex.decodeTerm(legacy).map(_.tags), Some(Nil))

    // resolution is decoration-blind: a tag change cannot move a citation
    assertEquals(WikiIndex.selectTerm("alias one", scan.terms), WikiIndex.TermSelection.Resolved(scan.terms.head))
  }

  // ───────────────────── reader summary（点击说明弹窗的提要读序）─────────────────────

  test("WI-40 reader summary: frontmatter `summary:` is canonical (source=frontmatter)") {
    val doc =
      """---
        |type: research-log
        |status: open
        |summary: 三十七个计数单位的读者提要示例，说明本文回答什么问题、结论与数字。
        |---
        |
        |# Title
        |
        |Body paragraph that the graph preview would otherwise take.
        |""".stripMargin
    val (summary, source) = WikiIndex.readerSummary(doc)
    assertEquals(source, "frontmatter")
    assertEquals(summary, Some("三十七个计数单位的读者提要示例，说明本文回答什么问题、结论与数字。"))
  }

  test("WI-41 reader summary: legacy docs fall back to the COMPLETE paragraph after the marker") {
    val legacy =
      """---
        |type: research-log
        |---
        |
        |# Legacy doc
        |
        |<!-- reader-summary -->
        |
        |旧文档把读者提要写在正文标记之后：这里是一整段。
        |这一段续行必须一起显示，否则读者读到的是半句话。
        |
        |## 过程
        |
        |细节。
        |""".stripMargin
    val (summary, source) = WikiIndex.readerSummary(legacy)
    assertEquals(source, "marker")
    assertEquals(
      summary,
      Some("旧文档把读者提要写在正文标记之后：这里是一整段。 这一段续行必须一起显示，否则读者读到的是半句话。")
    )
  }

  test("WI-42 reader summary: none ⇒ (None, none) — never an empty string; marker without a paragraph/H1 first") {
    assertEquals(WikiIndex.readerSummary("# Only a title\n\nplain body, no marker\n"), (None, "none"))
    assertEquals(WikiIndex.readerSummary("<!-- reader-summary -->\n\n## 过程\n\n细节\n"), (None, "none"))
    // 围栏里的标记是示例代码，不是约定
    assertEquals(
      WikiIndex.readerSummary("# T\n\n```md\n<!-- reader-summary -->\n```\n\n正文\n"),
      (None, "none")
    )
  }

  test("WI-43 splitVaultPath: only `<project>/.stand/docs/<rel>` is a metadata-carrying path") {
    assertEquals(
      WikiIndex.splitVaultPath("/Users/kaiyu/.stand/projects/stand/.stand/docs/x__20261122.md"),
      Some(("stand", "x__20261122.md"))
    )
    assertEquals(WikiIndex.splitVaultPath("stand/.stand/docs/sub/y.md"), Some(("stand", "sub/y.md")))
    assertEquals(WikiIndex.splitVaultPath("/tmp/nope/x.md"), None)
    assertEquals(WikiIndex.splitVaultPath("stand/.stand/docs/../evil.md"), None)
    assertEquals(WikiIndex.splitVaultPath(""), None)
  }

end WikiIndexSpec
