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
    assertEquals(doc.headings.map(h => (h.text, h.line)), List(("Auth review", 1), ("Token lifetime", 3), ("Deep heading", 5)))
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
      "proj", "self",
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
      "proj", "self",
      List(
        "x/a.md" -> "see [[a]]",
        "x/one.md" -> "x",
        "y/two.md" -> "y"
      )
    )
    // Two candidate basenames? "a" matches exactly one doc (x/a.md) → resolved.
    // Renamed fixture: a link whose basename matches TWO documents resolves to none.
    val scan2 = WikiIndex.buildVaultScan(
      "proj", "self",
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
      "proj", "self",
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
      "proj", "self",
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
      "proj", "self",
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
      "proj", "self",
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
      "proj", "self",
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

end WikiIndexSpec
