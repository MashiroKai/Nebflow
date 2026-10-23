package nebflow.core.wiki

import io.circe.Json
import io.circe.syntax.*

import scala.collection.mutable.ListBuffer

/**
 * Pure parsing layer for the project wiki graph (Obsidian-compatible vaults).
 *
 * A vault is a `docs/` folder inside a project workspace. Documents are plain
 * markdown files; attachments live in `docs/attachments/` (Obsidian's default
 * attachment layout). Links follow Obsidian conventions:
 *   - `[[target]]` / `[[target|alias]]` — wikilinks
 *   - `[[target#Heading]]`              — heading anchor
 *   - `[[target#^blockid]]`             — block reference
 *   - `![[target]]`                     — embed
 *   - `[text](rel.md)` / `[text](rel.md#L42)` — standard markdown links
 *     (with a line-anchor extension `L<n>` for precise jumps)
 *
 * Everything here is synchronous and side-effect free: the callers hand in raw
 * document text, this object returns nodes/edges. Keeping the parser pure lets
 * the same code path serve local vaults (direct disk reads) and remote vaults
 * (bytes pulled over the NebLink peer channel) with zero drift.
 */
object WikiIndex:

  val VaultDirName = "docs"
  val AttachmentsDirName = "attachments"

  /** Scan budgets — a runaway vault must not stall a graph request. */
  val MaxFilesPerVault = 2000
  val MaxScanDepth = 8
  val MaxDocBytes = 4L * 1024 * 1024

  /** A document in the graph. */
  final case class WikiDocNode(
    id: String,        // "<project>:<rel-path-with-forward-slashes>"
    project: String,
    device: String,    // "self" or the peer deviceId
    rel: String,       // vault-relative path, forward slashes
    title: String,     // first H1, else file basename
    headings: List[WikiHeading],
    blocks: List[WikiBlockAnchor],
    stub: Boolean,     // true = project-level placeholder / unresolved phantom
    kind: String = "doc",         // "doc" | "attachment"
    docType: Option[String] = None, // YAML frontmatter `type:` (scenario pack)
    status: Option[String] = None,  // YAML frontmatter `status:` (open/solved/…)
    summary: Option[String] = None,  // graph-preview summary (summaryOf — wiki-graph redesign)
    external: Boolean = false,      // true = cross-vault placeholder (#744-B): the target
                                   // lives in ANOTHER vault of the same project — counted
                                   // in stats, never drawn, never a broken-link entry
    paragraphs: List[WikiParagraph] = Nil // paragraph index (段落级引用批 — docs only)
  )

  /** A sub-vault discovered inside a vault (#744-B hierarchy): `rel` is the
    * vault-relative path of the sub vault root (e.g. "sub/docs"), `name` the
    * sub-project label, `docCount` a listing-level file count (no parsing). */
  final case class ChildVault(name: String, rel: String, docCount: Int)

  final case class WikiHeading(text: String, line: Int)

  /** A `^blockid` found in a document (line is 1-based, source coordinates). */
  final case class WikiBlockAnchor(id: String, line: Int)

  /** One indexable paragraph of a document (wiki-graph 段落级引用批, 台账
    * #744-A; design doc `docs/design/wiki-graph-paragraph-ref-design.zh-CN.md`
    * §3 — the Python exporter `check_wiki_docs.py` mirrors this field by
    * field). `anchor` is the EDGE-anchor form (`"#^id"` / `"#Heading"`), so a
    * client resolves `edge.anchor` against `node.paragraphs` with a plain
    * equality lookup — no transformation. */
  final case class WikiParagraph(
    anchor: String,   // edge-anchor form: "#^blk" | "#Heading"
    kind: String,     // "block" | "heading"
    title: String,    // term part (block) or heading text
    line: Int,        // 1-based FULL-FILE coordinates (frontmatter included)
    endLine: Int,
    text: String      // inline-markup-stripped excerpt, capped at [[ParagraphMaxChars]]
  )

  final case class WikiEdge(
    from: String,
    to: String,        // resolved node id (may be an unresolved-phantom node)
    kind: String,      // "link" | "embed"
    anchor: Option[String], // raw anchor as authored: "^blk" | "#Heading" | "L42"
    line: Int          // source line of the link in `from`
  )

  final case class VaultScan(nodes: List[WikiDocNode], edges: List[WikiEdge], warnings: List[String])

  // ── File classification (docs vs attachments) ───────────────────

  /** Document extensions indexed as first-class graph nodes. `.md` is the
    * primary face; `.html` covers visual-report HTML deliverables so they get
    * the same node treatment the author mandated. */
  val DocExtensions: Set[String] = Set(".md", ".markdown", ".html")

  /** Attachment extensions indexed as `kind=attachment` nodes — exactly the
    * files a canvas viewer can open, so a literature PDF cited from a document
    * is one click away. Unknown extensions are skipped entirely. */
  val AttachmentExtensions: Set[String] = Set(
    ".pdf", ".docx", ".pptx", ".xlsx", ".epub",
    ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg",
    ".mp4", ".webm", ".mov", ".mp3", ".wav", ".csv"
  )

  def extOf(rel: String): String =
    val lower = rel.toLowerCase
    val dot = lower.lastIndexOf('.')
    if dot < 0 then "" else lower.substring(dot)

  // ── Frontmatter (scenario packs: type / status / tags) ───────────

  /** A leading `--- … ---` block's key/value pairs (flat scalars only — the
    * vault convention keeps frontmatter tiny), the text AFTER it, and the
    * number of lines it occupied (body line numbers stay in FULL-FILE
    * coordinates so anchors agree with the authoring view). */
  def frontmatterOf(text: String): (Option[Map[String, String]], String, Int) =
    val lines = text.linesIterator.toVector
    if lines.isEmpty || lines.head.trim != "---" then (None, text, 0)
    else
      var endIdx = -1
      var i = 1
      while i < lines.length && endIdx < 0 do
        if lines(i).trim == "---" then endIdx = i
        i += 1
      if endIdx < 0 then (None, text, 0)
      else
        val fields = lines.slice(1, endIdx).flatMap { l =>
          val idx = l.indexOf(':')
          if idx <= 0 then None
          else Some(l.substring(0, idx).trim -> l.substring(idx + 1).trim.stripPrefix("\"").stripSuffix("\""))
        }.toMap
        val body = lines.drop(endIdx + 1).mkString("\n")
        (Some(fields), body, endIdx + 1)


  // ── Link extraction ──────────────────────────────────────────────

  private val WikilinkRe = raw"(!?)\[\[([^\[\]]+?)\]\]".r
  private val MarkdownLinkRe = raw"\[([^\]]*)\]\(([^)\s]+\.md(?:#[^\)\s]*)?)\)".r
  private val HeadingRe = raw"^(#{1,6})\s+(.+?)\s*#*\s*$$".r
  private val BlockIdRe = raw"^(.*\S)\s+\^([A-Za-z0-9][A-Za-z0-9-]*)\s*$$".r
  private val FenceRe = raw"^\s*(```+|~~~+)".r

  /** Split a document into lines, remembering which lines are inside a fenced
    * code block — links in code samples are not graph edges. */
  private def fencedMask(lines: Vector[String]): Vector[Boolean] =
    val inFence = Array.fill(lines.length)(false)
    var fenceOpen = false
    var fenceMarker = ""
    var i = 0
    while i < lines.length do
      FenceRe.findFirstMatchIn(lines(i)) match
        case Some(m) if !fenceOpen =>
          fenceOpen = true
          fenceMarker = m.group(1).take(3)
        case Some(m) if fenceOpen && m.group(1).startsWith(fenceMarker) =>
          fenceOpen = false
          fenceMarker = ""
        case _ => ()
      inFence(i) = fenceOpen || (FenceRe.findFirstMatchIn(lines(i)).isDefined && !fenceOpen)
      i += 1
    Vector.from(inFence)

  /** Strip the alias segment and split `target#anchor`. Returns (target, anchor). */
  private def splitWikilink(inner: String): (String, Option[String]) =
    val noAlias = inner.split('|').headOption.getOrElse(inner)
    val idx = noAlias.indexOf('#')
    if idx < 0 then (noAlias.trim, None)
    else (noAlias.substring(0, idx).trim, Some(noAlias.substring(idx + 1).trim))

  /**
   * Percent-decode a link destination WITHOUT the `+`→space rewrite.
   * `java.net.URLDecoder` is form-encoding (a literal `+` in a URL path is a
   * plus sign, not a space) — the same trap `nfTicket.decodePathParam`
   * documents for the query-string face. Authoring tools emit spaces as `%20`
   * and pluses as `%2B`, so a strict `%XX` pass is always correct here.
   */
  private def percentDecode(s: String): String =
    if !s.contains('%') then s
    else
      // Segment-wise: raw substrings pass through verbatim; each %XX run is
      // decoded to bytes and UTF-8-decoded as a group (multi-byte sequences
      // must be decoded together, not per byte).
      val sb = new StringBuilder(s.length)
      val bytes = new scala.collection.mutable.ArrayBuffer[Byte](8)
      def flushBytes(): Unit =
        if bytes.nonEmpty then
          sb.append(new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8))
          bytes.clear()
      var i = 0
      while i < s.length do
        if s.charAt(i) == '%' && i + 2 < s.length then
          try
            bytes += Integer.parseInt(s.substring(i + 1, i + 3), 16).toByte
            i += 3
          catch case _: Exception =>
            flushBytes()
            sb.append(s.charAt(i))
            i += 1
        else
          flushBytes()
          sb.append(s.charAt(i))
          i += 1
      flushBytes()
      sb.toString

  /** Split a markdown-link destination into (path, anchor). */
  private def splitMdDest(dest: String): (String, Option[String]) =
    val decoded = percentDecode(dest)
    val idx = decoded.indexOf('#')
    if idx < 0 then (decoded.trim, None)
    else (decoded.substring(0, idx).trim, Some(decoded.substring(idx + 1).trim))

  /** One parsed document: headings, block anchors, outgoing links and the
    * graph summary. */
  final case class ParsedDoc(
    title: String,
    headings: List[WikiHeading],
    blocks: List[WikiBlockAnchor],
    links: List[(String, Option[String], String, Int)], // (target, anchor, kind, line)
    summary: Option[String],
    paragraphs: List[WikiParagraph] = Nil
  )

  /** Parse raw markdown text into headings / block anchors / outgoing links /
    * summary. A leading frontmatter block is metadata, not content — its lines
    * are excluded from heading/block/link/summary extraction. */
  def parseDoc(rel: String, text: String): ParsedDoc =
    val (_, body, fmOffset) = frontmatterOf(text)
    val lines = body.linesIterator.toVector
    val mask = fencedMask(lines)
    val headings = ListBuffer.empty[WikiHeading]
    val blocks = ListBuffer.empty[WikiBlockAnchor]
    val links = ListBuffer.empty[(String, Option[String], String, Int)]
    var title = basename(rel).stripSuffix(".md")
    var afterFirstH1 = -1
    var i = 0
    while i < lines.length do
      val line = lines(i)
      val lineNo = i + 1 + fmOffset // full-file coordinates (frontmatter included)
      if !mask(i) then
        // Headings take precedence over block-id detection (a `^id` on a
        // heading line addresses the heading itself, not a separate block).
        HeadingRe.findFirstMatchIn(line) match
          case Some(m) =>
            val headingText = m.group(2).trim
            if headingText.nonEmpty then
              headings += WikiHeading(headingText, lineNo)
              if line.startsWith("# ") then
                title = headingText // (unchanged legacy behavior: last H1 wins)
                if afterFirstH1 < 0 then afterFirstH1 = i + 1
          case None =>
            BlockIdRe.findFirstMatchIn(line).foreach { m =>
              blocks += WikiBlockAnchor(m.group(2), lineNo)
            }
        WikilinkRe.findAllMatchIn(line).foreach { m =>
          val (target, anchor) = splitWikilink(m.group(2))
          if target.nonEmpty then
            val kind = if m.group(1).nonEmpty then "embed" else "link"
            links += ((target, anchor, kind, lineNo))
        }
        MarkdownLinkRe.findAllMatchIn(line).foreach { m =>
          val (target, anchor) = splitMdDest(m.group(2))
          if target.nonEmpty then links += ((target, anchor, "link", lineNo))
        }
      i += 1
    ParsedDoc(title, headings.toList, blocks.toList, links.toList, summaryOf(lines, mask, afterFirstH1),
      paragraphsOf(lines, mask, headings.toList, fmOffset))

  // ── Graph summary (wiki-graph redesign §5) ──────────────────────

  /** Summary cap — the graph surfaces exactly one preview line per document. */
  val SummaryMaxChars = 140

  /** A line the summary scan may not start from or continue through: blank,
    * any heading (the first paragraph AFTER the H1 — later headings bound the
    * paragraph), or a fence marker line. Fenced CONTENT is masked separately
    * by `mask`. */
  private def summaryBreak(line: String): Boolean =
    val t = line.trim
    t.isEmpty || t.startsWith("#") || FenceRe.findFirstMatchIn(t).isDefined

  /** First non-empty non-heading paragraph after the first H1, inline markup
    * stripped, capped at [[SummaryMaxChars]]. No H1 ⇒ no summary (the graph
    * shows the title only). Multi-line paragraphs join with a space; per-line
    * blockquote/list prefixes are stripped before the join so a quoted first
    * paragraph reads like prose. */
  def summaryOf(lines: Vector[String], mask: Vector[Boolean], fromIdx: Int): Option[String] =
    if fromIdx < 0 || fromIdx >= lines.length then None
    else
      var i = fromIdx
      val buf = ListBuffer.empty[String]
      while i < lines.length && (mask(i) || summaryBreak(lines(i))) do i += 1
      while i < lines.length && !mask(i) && !summaryBreak(lines(i)) do
        buf += lines(i).replaceAll(raw"^\s*>\s?", "").replaceAll(raw"^\s*(?:[-*+]|\d+[.)])\s+", "")
        i += 1
      val cleaned = cleanSummaryText(buf.mkString(" "))
      if cleaned.isEmpty then None else Some(truncateSummary(cleaned, SummaryMaxChars))

  /** Strip inline markdown from a summary candidate: images → alt, links and
    * wikilinks → their text/alias, emphasis/strikethrough markers and inline
    * backticks removed, whitespace collapsed. Plain prose passes verbatim. */
  def cleanSummaryText(s: String): String =
    s.replaceAll(raw"!\[([^\]]*)\]\([^)]*\)", "$1") // image → alt
      .replaceAll(raw"\[([^\]]*)\]\([^)]*\)", "$1") // md link → text
      .replaceAll(raw"!?\[\[[^\]\|]+\|([^\]]+)\]\]", "$1") // aliased wikilink → alias
      .replaceAll(raw"!?\[\[([^\]]+)\]\]", "$1")    // plain wikilink/embed → target
      .replaceAll(raw"\*\*([^*]+)\*\*", "$1")
      .replaceAll(raw"__([^_]+)__", "$1")
      .replaceAll(raw"\*([^*]+)\*", "$1")
      .replaceAll(raw"~~([^~]+)~~", "$1")
      .replaceAll("`", "")
      .replaceAll(raw"\s+", " ")
      .trim

  private def truncateSummary(s: String, max: Int): String =
    if s.length <= max then s
    else s.substring(0, s.offsetByCodePoints(0, max - 1)) + "…" // never split a code point

  private def basename(rel: String): String =
    val norm = rel.replace('\\', '/')
    norm.substring(norm.lastIndexOf('/') + 1)

  // ── Paragraph index (wiki-graph 段落级引用批; design §3) ──────────
  // Mirrored field-by-field by the Python exporter check_wiki_docs.py —
  // the two producers MUST stay isomorphic (anchor form, coordinates,
  // caps). A fence line or a heading always breaks a paragraph run.

  val ParagraphMaxChars = 280
  val ParagraphTitleMaxChars = 60
  val ParagraphMaxPerDoc = 64

  private def truncateAt(s: String, max: Int): String =
    if s.length <= max then s
    else s.substring(0, s.offsetByCodePoints(0, max - 1)) + "…" // never split a code point

  /** Term-part title of a block paragraph: the text before the first
    * ` —`/`：` separator (glossary-entry convention), else the cleaned
    * first-line prefix; falls back to the anchor id when the line is empty. */
  private def blockTitle(rawFirstLine: String, id: String): String =
    val base = rawFirstLine.replaceAll(raw"(?s)^(.*\S)\s+\^[A-Za-z0-9][A-Za-z0-9-]*\s*$$", "$1").trim
    val term = cleanSummaryText(base.split(raw"\s*[—–]\s*|\s*[:：]\s*", 2).head)
      .replaceAll(raw"^[#*-]+\s*", "").trim
    val fallback = cleanSummaryText(base)
    val candidate =
      if term.nonEmpty && term.length <= ParagraphTitleMaxChars then term
      else if fallback.nonEmpty then truncateAt(fallback, ParagraphTitleMaxChars)
      else id
    candidate

  /** Content runs of the body (fenced lines masked out, blanks/headings break
    * runs) as (bodyIdx, text) groups in document order. A NON-CONTENT line
    * always flushes the open run — an intervening blank/fence/heading ends the
    * paragraph even if the next content line is index-adjacent (the Python
    * exporter breaks the same way; keep the two isomorphic). */
  private def contentRuns(lines: Vector[String], mask: Vector[Boolean]): List[List[(Int, String)]] =
    val runs = ListBuffer.empty[List[(Int, String)]]
    var cur = ListBuffer.empty[(Int, String)]
    var prevIdx = -2
    def flush(): Unit =
      if cur.nonEmpty then
        runs += cur.toList
        cur = ListBuffer.empty[(Int, String)]
    for i <- lines.indices do
      val line = lines(i)
      val content = !mask(i) && line.trim.nonEmpty && HeadingRe.findFirstMatchIn(line).isEmpty
      if content then
        if cur.nonEmpty && i == prevIdx + 1 then cur += ((i, line))
        else
          flush()
          cur = ListBuffer((i, line))
      else flush() // blank / fence / heading — run boundary (parity with check_wiki_docs.py)
      prevIdx = i
    flush()
    runs.toList

  /** Block paragraphs: content runs whose LAST line carries a `^blockid`. */
  private def blockParagraphs(runs: List[List[(Int, String)]], fmOffset: Int): List[WikiParagraph] =
    runs.flatMap { group =>
      val (lastIdx, lastLine) = group.last
      BlockIdRe.findFirstMatchIn(lastLine).map { m =>
        val id = m.group(2)
        val body = group.map { case (idx, t) =>
          if idx == lastIdx then t.replaceAll(raw"(?s)^(.*\S)\s+\^[A-Za-z0-9][A-Za-z0-9-]*\s*$$", "$1") else t
        }.mkString(" ")
        WikiParagraph(
          anchor = "#^" + id,
          kind = "block",
          title = blockTitle(group.head._2, id),
          line = group.head._1 + 1 + fmOffset,
          endLine = lastIdx + 1 + fmOffset,
          text = truncateAt(cleanSummaryText(body), ParagraphMaxChars)
        )
      }
    }

  /** Heading sections: heading line through the line before the next
    * heading (any level) or end of body. */
  private def headingParagraphs(lines: Vector[String], mask: Vector[Boolean],
                                headings: List[WikiHeading], fmOffset: Int): List[WikiParagraph] =
    headings.zipWithIndex.map { case (h, i) =>
      val startIdx = h.line - 1 - fmOffset
      val endIdx = headings.lift(i + 1) match
        case Some(nh) => math.min(nh.line - 1 - fmOffset - 1, lines.length - 1)
        case None     => lines.length - 1
      val text =
        if startIdx + 1 > endIdx then ""
        else (startIdx + 1 to endIdx).filter(j => !mask(j) && lines(j).trim.nonEmpty &&
          HeadingRe.findFirstMatchIn(lines(j)).isEmpty).map(lines).mkString(" ")
      WikiParagraph(
        anchor = "#" + h.text,
        kind = "heading",
        title = h.text,
        line = h.line,
        endLine = endIdx + 1 + fmOffset,
        text = truncateAt(cleanSummaryText(text), ParagraphMaxChars)
      )
    }

  /** Full paragraph index of one document: block paragraphs + heading
    * sections, line-ordered, capped at [[ParagraphMaxPerDoc]]. */
  def paragraphsOf(lines: Vector[String], mask: Vector[Boolean],
                   headings: List[WikiHeading], fmOffset: Int): List[WikiParagraph] =
    if lines.isEmpty then Nil
    else
      val runs = contentRuns(lines, mask)
      (blockParagraphs(runs, fmOffset) ++ headingParagraphs(lines, mask, headings, fmOffset))
        .sortBy(_.line)
        .take(ParagraphMaxPerDoc)

  /** Raw-anchor (`"^blk"` | `"#Heading"` | `"L42"`) → edge-anchor form. */
  def edgeAnchorForm(anchor: String): String =
    if anchor.startsWith("#") || anchor.startsWith("^") then "#" + anchor.stripPrefix("#")
    else "#" + anchor

  // ── Graph assembly ───────────────────────────────────────────────

  /** Normalize a link target to a vault-relative doc path (forward slashes,
    * leading "./" stripped, `.md` added when the target has no extension). */
  def normalizeTarget(target: String): String =
    var t = target.replace('\\', '/')
    while t.startsWith("./") do t = t.substring(2)
    if !t.toLowerCase.endsWith(".md") && !t.contains('.') then t + ".md"
    else t

  /**
   * Resolve one document's links against the vault's file list and build edges.
   * Resolution order (Obsidian-like): exact vault-relative path, then unique
   * basename match. Unresolved targets become phantom nodes (`stub=true`) so
   * the graph still shows the reference — Obsidian draws them the same way.
   */
  def buildEdges(project: String, device: String, fromRel: String, parsed: ParsedDoc, knownRels: Set[String]): List[WikiEdge] =
    val byBasename = knownRels.groupBy(r => basename(r).stripSuffix(".md").toLowerCase)
    parsed.links.flatMap { case (rawTarget, anchor, kind, line) =>
      val target = normalizeTarget(rawTarget)
      val targetKey = target.toLowerCase
      val resolved =
        if knownRels.contains(target) || knownRels.contains(targetKey) then Some(target)
        else if knownRels.exists(_.toLowerCase == targetKey) then
          knownRels.find(_.toLowerCase == targetKey)
        else
          byBasename.get(basename(target).stripSuffix(".md").toLowerCase) match
            case Some(matches) if matches.sizeIs == 1 => Some(matches.head)
            case _ => None
      val toId = resolved match
        case Some(rel) => nodeId(project, rel)
        case None => nodeId(project, target)
      Some(WikiEdge(nodeId(project, fromRel), toId, kind, anchor.map(a => "#" + a), line))
    }

  def nodeId(project: String, rel: String): String = s"$project:${rel.replace('\\', '/')}"

  /** Node id for a phantom (unresolved) target — same namespace, flagged stub. */
  def phantomNode(project: String, device: String, rel: String): WikiDocNode =
    WikiDocNode(nodeId(project, rel), project, device, rel, basename(rel).stripSuffix(".md"), Nil, Nil, stub = true)

  /** Cross-vault placeholder (#744-B): same shape as a phantom, but flagged
    * `external` so consumers count it as a cross-vault reference instead of a
    * broken link. `rel` is the target AS AUTHORED (vault-relative to the source
    * vault, e.g. "sub/docs/x.md" or a sibling vault's "glossary.md"). */
  def externalNode(project: String, device: String, rel: String): WikiDocNode =
    phantomNode(project, device, rel).copy(external = true)

  /**
   * Classify a single-vault scan's phantom targets into cross-vault references
   * vs true broken links (#744-B). `foreignRels` = every rel reachable in OTHER
   * vaults of the same project, in BOTH forms: vault-relative ("glossary.md")
   * and vault-qualified ("sub/docs/x.md"). Phantom nodes whose authored rel
   * matches either form flip to `external=true`; the rest stay broken.
   */
  def classifyExternals(scan: VaultScan, foreignRels: Set[String]): VaultScan =
    if foreignRels.isEmpty then scan
    else
      val nodes = scan.nodes.map(n =>
        if n.stub && !n.external && foreignRels.contains(n.rel) then n.copy(external = true) else n
      )
      scan.copy(nodes = nodes)

  /** Build a full vault scan from (rel -> raw text) document pairs plus the
    * vault's attachment files (rel paths; content is never read). Attachments
    * join the resolution namespace so `[[paper.pdf]]` cites resolve like any
    * Obsidian link. */
  def buildVaultScan(project: String, device: String, docs: List[(String, String)], attachments: List[String] = Nil): VaultScan =
    val knownRels = docs.map(_._1).toSet ++ attachments.toSet
    val nodes = ListBuffer.empty[WikiDocNode]
    val edges = ListBuffer.empty[WikiEdge]
    val phantom = scala.collection.mutable.LinkedHashMap.empty[String, WikiDocNode]
    docs.foreach { (rel, text) =>
      val (frontmatter, _, _) = frontmatterOf(text)
      val parsed = parseDoc(rel, text)
      nodes += WikiDocNode(
        id = nodeId(project, rel),
        project = project,
        device = device,
        rel = rel,
        title = parsed.title,
        headings = parsed.headings,
        blocks = parsed.blocks,
        stub = false,
        kind = "doc",
        docType = frontmatter.flatMap(_.get("type")).filter(_.nonEmpty),
        status = frontmatter.flatMap(_.get("status")).filter(_.nonEmpty),
        summary = parsed.summary,
        paragraphs = parsed.paragraphs
      )
      edges ++= buildEdges(project, device, rel, parsed, knownRels)
    }
    attachments.foreach { rel =>
      nodes += WikiDocNode(
        id = nodeId(project, rel),
        project = project,
        device = device,
        rel = rel,
        title = basename(rel),
        headings = Nil,
        blocks = Nil,
        stub = false,
        kind = "attachment"
      )
    }
    edges.foreach { e =>
      if !nodes.exists(_.id == e.to) && !phantom.contains(e.to) then
        phantom(e.to) = phantomNode(project, device, e.to.drop(project.length + 1))
    }
    nodes ++= phantom.values
    VaultScan(nodes.toList, edges.toList, Nil)

  // ── JSON encoding ────────────────────────────────────────────────

  def paragraphJson(p: WikiParagraph): Json =
    Json.obj(
      "anchor" -> p.anchor.asJson,
      "kind" -> p.kind.asJson,
      "title" -> p.title.asJson,
      "line" -> p.line.asJson,
      "endLine" -> p.endLine.asJson,
      "text" -> p.text.asJson
    )

  def nodeJson(n: WikiDocNode): Json =
    Json.obj(
      "id" -> n.id.asJson,
      "project" -> n.project.asJson,
      "device" -> n.device.asJson,
      "rel" -> n.rel.asJson,
      "title" -> n.title.asJson,
      "headings" -> n.headings.map(h => Json.obj("t" -> h.text.asJson, "line" -> h.line.asJson)).asJson,
      "blocks" -> n.blocks.map(b => Json.obj("id" -> b.id.asJson, "line" -> b.line.asJson)).asJson,
      "stub" -> n.stub.asJson,
      "kind" -> n.kind.asJson,
      "type" -> n.docType.map(_.asJson).getOrElse(Json.Null),
      "status" -> n.status.map(_.asJson).getOrElse(Json.Null),
      "summary" -> n.summary.map(_.asJson).getOrElse(Json.Null),
      "external" -> n.external.asJson,
      "paragraphs" -> n.paragraphs.map(paragraphJson).asJson
    )

  def edgeJson(e: WikiEdge): Json =
    Json.obj(
      "from" -> e.from.asJson,
      "to" -> e.to.asJson,
      "kind" -> e.kind.asJson,
      "anchor" -> e.anchor.map(_.asJson).getOrElse(Json.Null),
      "line" -> e.line.asJson
    )
end WikiIndex
