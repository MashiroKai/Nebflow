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
end WikiIndexSpec
