package nebflow.core.pdf

import io.circe.syntax.*
import io.circe.{Json, JsonObject}

/**
 * PDF note/ask — the parsed-document manifest.
 *
 * One manifest describes one cache entry: the whole result of parsing one PDF
 * once. It is the ONLY discovery surface an ask answer needs — "which pages
 * exist, which page file holds which page, what else was extracted" — so an ask
 * can decide which pages to read without opening the PDF and without walking
 * the cache directory.
 *
 * Fields:
 *   - `sourceSha256`     content identity of the PDF bytes. Every cache address,
 *                        every note and every citation is keyed by this, never by
 *                        a path: moving or renaming the file keeps notes and the
 *                        cache entry; editing the bytes is a DIFFERENT document
 *                        (a new sha) and therefore never inherits old notes or
 *                        stale text.
 *   - `sourceName`       display name recorded at parse time (informational only;
 *                        never part of the cache key).
 *   - `pageCount`        1-based page count; the authoritative bound for both
 *                        page reads and note page numbers.
 *   - `pageNames`        the per-page Markdown file names (`p0001.md`, …) in page
 *                        order. `pageNames(n - 1)` is the file for page `n`.
 *   - `parser`           parser identity + version (invalidation input).
 *   - `markdownVersion`  the Markdown shape version; a cache entry whose value is
 *                        not the current one is a MISS (text is never served in a
 *                        shape the reader does not understand).
 *   - `generatedAtMs`    when the entry was produced (evidence only).
 *   - `media`            extracted media paths, entry-relative.
 *   - `tableCount` / `imageCount`  extraction counters (evidence + tests).
 */
final case class PdfManifest(
  sourceSha256: String,
  sourceName: String,
  pageCount: Int,
  pageNames: List[String],
  parser: PdfManifest.ParserId,
  markdownVersion: Int,
  generatedAtMs: Long,
  media: List[String],
  tableCount: Int,
  imageCount: Int
):

  /** Entry-relative path of the Markdown file for a 1-based page, if in range. */
  def pageName(page: Int): Option[String] =
    if page >= 1 && page <= pageNames.length then Some(pageNames(page - 1)) else None

  def toJson: Json = Json.obj(
    "schema" -> PdfManifest.Schema.asJson,
    "source_sha256" -> sourceSha256.asJson,
    "source_name" -> sourceName.asJson,
    "page_count" -> pageCount.asJson,
    "page_names" -> pageNames.asJson,
    "parser" -> parser.toJson,
    "markdown_version" -> markdownVersion.asJson,
    "generated_at_ms" -> generatedAtMs.asJson,
    "media" -> media.asJson,
    "table_count" -> tableCount.asJson,
    "image_count" -> imageCount.asJson
  )

  /** The compact shape the PDF "open" endpoint returns (identity + size only). */
  def summaryJson: Json = Json.obj(
    "source_sha256" -> sourceSha256.asJson,
    "source_name" -> sourceName.asJson,
    "page_count" -> pageCount.asJson,
    "parser" -> parser.toJson,
    "markdown_version" -> markdownVersion.asJson,
    "generated_at_ms" -> generatedAtMs.asJson,
    "table_count" -> tableCount.asJson,
    "image_count" -> imageCount.asJson
  )

end PdfManifest

object PdfManifest:

  /** Manifest schema version (the envelope), independent of the Markdown shape. */
  val Schema: Int = 1

  /** Parser identity as recorded by the parser leg. */
  final case class ParserId(name: String, version: String):
    def toJson: Json = Json.obj("name" -> name.asJson, "version" -> version.asJson)

  private def str(o: JsonObject, k: String): Option[String] = o(k).flatMap(_.asString)

  private def int(o: JsonObject, k: String): Option[Int] = o(k).flatMap(_.asNumber).flatMap(_.toInt)

  private def long(o: JsonObject, k: String): Option[Long] = o(k).flatMap(_.asNumber).flatMap(_.toLong)

  /**
   * Read a manifest from meta.json text.
   *
   * `Left` is a REASON, not an exception: a cache entry written by an older or
   * foreign schema is reported as a miss with a readable cause (the caller then
   * reparses rather than serving text it cannot describe) — never as a crash and
   * never silently as an empty document.
   */
  def decode(text: String): Either[String, PdfManifest] =
    io.circe.parser.parse(text) match
      case Left(err) => Left(s"meta.json is not valid JSON: ${err.message}")
      case Right(json) =>
        json.asObject match
          case None => Left("meta.json is not a JSON object")
          case Some(o) =>
            val declaredSchema = int(o, "schema")
            val fields = for
              schema <- int(o, "schema")
              sha <- str(o, "source_sha256")
              name <- str(o, "source_name")
              pages <- int(o, "page_count")
              names <- o("page_names").flatMap(_.asArray).map(_.toList.flatMap(_.asString))
              parserObj <- o("parser").flatMap(_.asObject)
              pname <- str(parserObj, "name")
              pver <- str(parserObj, "version")
              mv <- int(o, "markdown_version")
              gen <- long(o, "generated_at_ms")
              media <- o("media").flatMap(_.asArray).map(_.toList.flatMap(_.asString))
              tables <- int(o, "table_count")
              images <- int(o, "image_count")
            yield PdfManifest(
              sourceSha256 = sha,
              sourceName = name,
              pageCount = pages,
              pageNames = names,
              parser = ParserId(pname, pver),
              markdownVersion = mv,
              generatedAtMs = gen,
              media = media,
              tableCount = tables,
              imageCount = images
            )
            (declaredSchema, fields) match
              case (Some(s), _) if s != Schema =>
                Left(s"meta.json schema $s is not the supported schema $Schema")
              case (_, None) => Left("meta.json is missing required fields")
              case (_, Some(m)) if m.pageCount != m.pageNames.length =>
                Left(s"meta.json is inconsistent: page_count=${m.pageCount} but ${m.pageNames.length} page names")
              case (_, Some(m)) => Right(m)
end PdfManifest
