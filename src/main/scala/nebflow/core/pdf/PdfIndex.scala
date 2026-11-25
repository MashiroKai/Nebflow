package nebflow.core.pdf

import io.circe.syntax.*
import io.circe.{Json, JsonObject}

/**
 * The page index — an INVERTED index (term → the pages carrying it) built once,
 * at parse time, next to the pages it describes.
 *
 * ## Why it exists
 *
 * Ranking pages needs to know which pages mention the question's terms. The
 * honest alternatives are:
 *
 *   · read every page file and grep it — that is "read the whole document on
 *     every ask", the exact cost this module promises not to pay, and it makes
 *     the answer's page-read count grow with the document rather than with the
 *     question;
 *   · ask a model / an embedding store — a new moving part, a new dependency and
 *     a non-deterministic answer;
 *   · build the lookup once, at the only moment the document is being read
 *     anyway, and store it as terms + posting lists.
 *
 * This is the third option. The index holds TERMS, never page bodies: an ask
 * reads `index.json` (small, bounded by the document's vocabulary) plus the page
 * files it actually cites.
 *
 * ## Shape
 *
 * `{"schema":1,"markdown_version":2,"page_count":3,"terms":{"r2c2":[2],…}}`
 *
 * `terms` maps one lowercase alphanumeric term (length ≥ 2, the same floor the
 * ask applies) to the ascending list of 1-based page numbers containing it.
 * Page numbers, never file names: the page number is the citation, so the index
 * is already expressed in the vocabulary an answer must use.
 */
final case class PdfIndex(
  pageCount: Int,
  terms: Map[String, List[Int]],
  markdownVersion: Int
):

  /**
   * Pages matching at least one of `query`'s terms, with the terms each matched.
   *
   * Deterministic and total: the result is sorted by page number, and a page
   * with no term overlap is simply absent (there is nothing to justify citing
   * it). This is the ONLY source of candidate pages — no page file is opened to
   * discover candidates.
   */
  def matchedPages(query: List[String]): List[(Int, List[String])] =
    query
      .flatMap(t => terms.getOrElse(t, Nil).map(page => (page, t)))
      .groupBy(_._1)
      .toList
      .map { case (page, hits) => (page, hits.map(_._2).distinct.sorted) }
      .sortBy(_._1)

  def toJson: Json = Json.obj(
    "schema" -> PdfIndex.Schema.asJson,
    "markdown_version" -> markdownVersion.asJson,
    "page_count" -> pageCount.asJson,
    "terms" -> Json.fromJsonObject(JsonObject.fromIterable(terms.toList.sortBy(_._1).map { case (k, v) =>
      k -> v.asJson
    }))
  )

end PdfIndex

object PdfIndex:

  /** Index envelope version (independent of the Markdown shape). */
  val Schema: Int = 1

  /**
   * Build an index from per-page term sets.
   *
   * Terms are lowercased alphanumeric runs of length ≥ 2, deduplicated per page
   * — the same tokenisation the ask applies to a question, so a query and a page
   * are compared in one vocabulary.
   */
  def build(pageCount: Int, pageTerms: List[(Int, Set[String])], markdownVersion: Int): PdfIndex =
    val inverted = scala.collection.mutable.Map.empty[String, List[Int]]
    for
      (page, ts) <- pageTerms.sortBy(_._1)
      t <- ts.toList.sorted
    do inverted.update(t, inverted.getOrElse(t, Nil) :+ page)
    PdfIndex(pageCount, inverted.toMap, markdownVersion)

  /** Tokens of a text as the index stores them (shared vocabulary with the ask). */
  def tokenize(text: String): Set[String] =
    text.toLowerCase
      .split("[^\\p{L}\\p{N}]+")
      .iterator
      .map(_.trim)
      .filter(_.length >= 2)
      .toSet

  /** Read an index from `index.json` text. `Left` is a reason, never an exception. */
  def decode(text: String): Either[String, PdfIndex] =
    io.circe.parser.parse(text) match
      case Left(err) => Left(s"index.json is not valid JSON: ${err.message}")
      case Right(json) =>
        json.asObject match
          case None => Left("index.json is not a JSON object")
          case Some(o) =>
            val declaredSchema = o("schema").flatMap(_.asNumber).flatMap(_.toInt)
            val fields = for
              _ <- o("schema").flatMap(_.asNumber).flatMap(_.toInt)
              pages <- o("page_count").flatMap(_.asNumber).flatMap(_.toInt)
              mv <- o("markdown_version").flatMap(_.asNumber).flatMap(_.toInt)
              terms <- o("terms").flatMap(_.asObject).map { to =>
                to.toList.flatMap { case (k, v) =>
                  v.asArray.map(a => k -> a.toList.flatMap(_.asNumber).flatMap(_.toInt))
                }.toMap
              }
            yield PdfIndex(pages, terms, mv)
            fields match
              case Some(idx) if idx.pageCount < 0 => Left("index.json declares a negative page_count")
              case Some(idx) => Right(idx)
              case None => Left("index.json is missing required fields")
end PdfIndex
