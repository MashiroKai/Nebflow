package nebflow.core.pdf

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.shared.NebflowLogger

/**
 * The ask leg: from a question to a SMALL set of cited pages.
 *
 * ## The contract this object exists to keep
 *
 *   · Discovery first: the ask reads the manifest (`page_count`, page names) so it
 *     knows what exists without opening the PDF and without listing the cache.
 *   · On demand: only the pages it decides it needs are read from disk. The whole
 *     document is never loaded, never concatenated and never handed to an answer —
 *     `MaxPagesPerAsk` pages, `MaxCharsPerPage` characters each, hard caps
 *     (`budget` will report what was left out rather than quietly truncating to a
 *     misleading partial page).
 *   · Citations are page numbers. Every excerpt carries the 1-based page number it
 *     came from, so an answer can say "page 3" and the reader can open page 3 and
 *     see the same text. A citation is never a file path or a byte offset.
 *   · Page-level notes are part of the retrieval evidence: a note pinned to page N
 *     makes page N interesting for a question the note's text matches, and the
 *     note text rides along with the excerpt (the user's own annotation is
 *     context, not an instruction).
 *
 * ## Selection (deterministic, inspectable)
 *
 * No model is involved: terms are the question's alphanumeric tokens (lowercased,
 * length ≥ 2, stopwords dropped), a page scores by the number of distinct terms it
 * contains (with repeats capped so a page cannot win by repeating one word), plus
 * a bonus for a page whose note matches, plus a tie-break on the lower page number.
 * The score and the matched terms are RETURNED, so an answer can be audited
 * ("why page 7?") instead of guessing. This is a deliberate non-goal: no embedding
 * search, no reranker — the honest description of what ships.
 */
object PdfAsk:

  private val logger = NebflowLogger.forName("nebflow.pdf.ask")

  /** Hard cap: how many pages one ask may read. */
  val MaxPagesPerAsk: Int = 4

  /** Hard cap: characters taken from one page. */
  val MaxCharsPerPage: Int = 6000

  /** Hard cap: characters of assembled context across all selected pages. */
  val MaxCharsPerAsk: Int = 24000

  /** A term shorter than this is ignored (`a`, `of`, …). */
  private val MinTermLength: Int = 2

  private val Stopwords: Set[String] = Set(
    "the",
    "and",
    "for",
    "with",
    "that",
    "this",
    "from",
    "what",
    "which",
    "where",
    "when",
    "how",
    "why",
    "are",
    "was",
    "were",
    "does",
    "did",
    "is",
    "in",
    "on",
    "at",
    "to",
    "of",
    "it",
    "its",
    "as",
    "by",
    "be",
    "or",
    "an",
    "a",
    "page",
    "pdf",
    "please",
    "tell",
    "about",
    "into",
    "than"
  )

  /** Question -> terms (deterministic, language-agnostic beyond the stopword list). */
  def terms(question: String): List[String] =
    question.toLowerCase
      .split("[^\\p{L}\\p{N}]+")
      .iterator
      .map(_.trim)
      .filter(t => t.length >= MinTermLength && !Stopwords.contains(t))
      .toList
      .distinct

  /** One scored page candidate. */
  final case class Candidate(page: Int, score: Double, matched: List[String], noteHit: Boolean)

  /** One page actually read, with its excerpt. */
  final case class Excerpt(page: Int, markdown: String, truncated: Boolean, score: Double, matched: List[String])

  /**
   * Rank pages by the indexed terms they share with the question.
   *
   * `matched` comes from [[PdfIndex]] (term → posting list), NOT from a page body:
   * this is what lets the ask know which pages are worth opening without opening
   * anything. Ordering is (score desc, page asc) — deterministic and re-derivable.
   */
  def rankIndexed(
    index: PdfIndex,
    notes: Map[Int, String],
    question: String,
    budget: Int = MaxPagesPerAsk
  ): List[Candidate] =
    val ts = terms(question)
    index
      .matchedPages(ts)
      .map { case (n, matched) =>
        val note = notes.getOrElse(n, "")
        val noteHit = note.nonEmpty && ts.exists(t => matched.contains(t) || note.toLowerCase.contains(t))
        Candidate(n, matched.length.toDouble + (if noteHit then 1.0 else 0.0), matched, noteHit)
      }
      // Zero-score pages are not relevant: with no term overlap there is nothing
      // to justify a citation, so they are dropped rather than padded in.
      .filter(c => c.score > 0.0)
      .sortBy(c => (-c.score, c.page))
      .take(budget)

  end rankIndexed

  /**
   * Rank pages by term overlap in page bodies already in hand. Retained for the
   * legacy/in-memory case (a caller that has the text anyway); the ask path uses
   * [[rankIndexed]] so that it never needs it.
   */
  def rankAll(
    pageTexts: List[(Int, String)],
    notes: Map[Int, String],
    question: String,
    budget: Int = MaxPagesPerAsk
  ): List[Candidate] =
    val ts = terms(question)
    pageTexts
      .map { case (n, text) =>
        val lower = text.toLowerCase
        val matched = ts.filter(t => lower.contains(t))
        val note = notes.getOrElse(n, "")
        val noteHit = note.nonEmpty && ts.exists(t => lower.contains(t) || note.toLowerCase.contains(t))
        Candidate(n, matched.length.toDouble + (if noteHit then 1.0 else 0.0), matched, noteHit)
      }
      // Zero-score pages are not relevant: with no term overlap there is nothing
      // to justify a citation, so they are dropped rather than padded in.
      .filter(c => c.score > 0.0)
      .sortBy(c => (-c.score, c.page))
      .take(budget)

  end rankAll

  /** Trim a page's Markdown to the per-page character cap. */
  private def excerptOf(page: Int, markdown: String): (String, Boolean) =
    if markdown.length <= MaxCharsPerPage then (markdown, false)
    else (markdown.take(MaxCharsPerPage), true)

  /** The context block handed to an answer: cited excerpts, nothing else. */
  def render(
    question: String,
    excerpts: List[Excerpt],
    manifest: PdfManifest,
    leftOut: Int,
    notes: Map[Int, String]
  ): String =
    val sb = new StringBuilder
    sb.append(s"PDF: ${manifest.sourceName} (sha256 ${manifest.sourceSha256.take(12)}…, ${manifest.pageCount} pages)\n")
    sb.append(s"Question: $question\n")
    if excerpts.isEmpty then sb.append("No page of this document matched the question.\n")
    else
      sb.append(s"Relevant pages (cite these numbers):\n")
      var used = 0
      for e <- excerpts if used < MaxCharsPerAsk do
        val body =
          if e.markdown.length > MaxCharsPerAsk - used then e.markdown.take(MaxCharsPerAsk - used) else e.markdown
        used += body.length
        sb.append(s"\n--- page ${e.page} ---\n")
        sb.append(body)
        if !body.endsWith("\n") then sb.append("\n")
        if e.truncated then sb.append(s"[page ${e.page} was truncated at $MaxCharsPerPage characters]\n")
        if notes.contains(e.page) then sb.append(s"[the reader left a note on page ${e.page}]\n")
    if leftOut > 0 then
      sb.append(s"\n[$leftOut further page(s) matched the question but were not read (budget $MaxPagesPerAsk pages)]\n")
    sb.append("[This is an excerpt set, not the whole document. Cite page numbers.]\n")
    sb.toString

  end render

  /** JSON shape of one excerpt (page number first — the citation). */
  def excerptJson(e: Excerpt): Json = Json.obj(
    "page" -> e.page.asJson,
    "citation" -> s"p.${e.page}".asJson,
    "score" -> e.score.asJson,
    "matched_terms" -> e.matched.asJson,
    "truncated" -> e.truncated.asJson,
    "chars" -> e.markdown.length.asJson,
    "markdown" -> e.markdown.asJson
  )

  /**
   * The ask: ensure a cache entry (this is the ONE place a parse can start), read
   * the manifest and the page INDEX, then read at most `budget` pages on demand.
   *
   * ## Discovery before reading (the on-demand contract)
   *
   * The ask resolves the question against `index.json` (term → pages) and only
   * then opens the pages it decided to cite. Page bodies are opened EXACTLY
   * `min(matched, budget)` times — the count is returned in the answer
   * (`page_reads`) and counted process-wide (`PdfCache.pageReadsFor`), so "only
   * the matching pages were read" is an observable fact. A document with 500
   * pages costs the same for a question as a document with 3: one index read plus
   * one page read per cited page.
   *
   * `notes` come from [[PdfNotes]] and are used both as ranking evidence and as a
   * flagged part of the result — a note is user context and is never presented as
   * document text.
   */
  def ask(
    cache: PdfCache,
    dataRoot: os.Path,
    pdf: os.Path,
    displayName: String,
    question: String,
    budget: Int = MaxPagesPerAsk
  ): IO[Either[PdfParser.PdfFailure, Json]] =
    cache.ensure(pdf, displayName).flatMap {
      case Left(f) =>
        // A readable failure is part of the answer: the ask says what happened
        // ("this PDF is encrypted") instead of pretending there is no document.
        logger.warnSync(s"pdf-ask no context sha=? code=${f.code}")
        IO.pure(Left(f))
      case Right(manifest) =>
        val sha = manifest.sourceSha256
        val pages = manifest.pageCount
        for
          notePages <- PdfNotes.pages(dataRoot, sha)
          noteMs <- IO.traverse(notePages) { n =>
            PdfNotes.get(dataRoot, sha, n).map { j =>
              val text = j.flatMap(_.hcursor.downField("text").as[String].toOption).getOrElse("")
              val snippet = j.flatMap(_.hcursor.downField("snippet").as[String].toOption).getOrElse("")
              (n, (text + " " + snippet).trim)
            }
          }
          noteMap = noteMs.filter(_._2.nonEmpty).toMap
          // Discovery: the manifest (page count / page names) plus the index.
          // NO page body is read to decide which pages matter.
          index <- cache.readIndex(sha)
          ranked = index.map(idx => rankIndexed(idx, noteMap, question, budget + 1)).getOrElse(Nil)
          selected = ranked.take(budget)
          // Only now are page bodies opened — exactly one per cited page.
          excerpts <- IO.traverse(selected) { c =>
            cache.readPage(sha, c.page).map {
              case Right(md) =>
                val (body, truncated) = excerptOf(c.page, md)
                Excerpt(c.page, body, truncated, c.score, c.matched)
              case Left(_) => Excerpt(c.page, "", false, c.score, c.matched)
            }
          }
          leftOut = math.max(0, ranked.length - selected.length)
          result = Json.obj(
            "state" -> "ready".asJson,
            "manifest" -> manifest.summaryJson,
            "question" -> question.asJson,
            "terms" -> terms(question).asJson,
            "budget" -> Json.obj(
              "max_pages" -> budget.asJson,
              "max_chars_per_page" -> MaxCharsPerPage.asJson,
              "max_chars_total" -> MaxCharsPerAsk.asJson,
              "pages_read" -> excerpts.length.asJson,
              "pages_left_out" -> leftOut.asJson
            ),
            // The on-demand ledger: how many pages matched, how many were
            // actually opened, and how many the document has.
            "page_reads" -> Json.obj(
              "matched_pages" -> ranked.length.asJson,
              "pages_opened" -> excerpts.length.asJson,
              "total_pages" -> pages.asJson,
              "index_used" -> index.isDefined.asJson,
              "whole_document_read" -> false.asJson
            ),
            "citations" -> excerpts.map(e => s"p.${e.page}".asJson).asJson,
            "excerpts" -> excerpts.map(excerptJson).asJson,
            "note_pages" -> noteMap.keys.toList.sorted.asJson,
            "context" -> render(question, excerpts, manifest, leftOut, noteMap).asJson
          )
        yield Right(result)
        end for
    }
end PdfAsk
