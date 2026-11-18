package nebflow.core.pdf

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.AtomicJson
import nebflow.shared.NebflowLogger

import java.util.concurrent.ConcurrentHashMap

/**
 * Page-anchored PDF notes — the "note" half of the feature.
 *
 * ## Semantics (this is the part that must be readable, not just implemented)
 *
 * A note is a short text about ONE PAGE of ONE DOCUMENT:
 *
 *   identity = (document content digest, 1-based page number)
 *
 * Consequences, stated because each one is a decision:
 *
 *   · **The document is its bytes, not its path.** Notes live under
 *     `<dataRoot>/pdf-notes/<sha256>/p<NNNN>.json`. Moving or renaming the PDF
 *     keeps the note; editing the PDF produces a different digest and therefore a
 *     different note slot. A note can never be silently applied to changed
 *     content.
 *   · **A note never parses.** Writing or reading a note touches no PDF and no
 *     parser. When the document's manifest is NOT cached, the page number is
 *     accepted and recorded as `page_verified: false` (the note is never refused
 *     just because nobody has parsed the file); the ask path later repairs it —
 *     a note whose page is beyond the real page count is reported as
 *     `out_of_range` when read WITH a manifest in hand. This is what keeps the
 *     laziness promise intact in both directions: opening (and annotating) never
 *     pays the parse, and the page bound only becomes authoritative once a parse
 *     has actually happened.
 *   · **A note may quote a snippet.** Optional verbatim text (what the user
 *     selected on that page) is stored alongside the note. It is descriptive: it
 *     survives as evidence of what the note was about and is offered to the ask
 *     path as a keyword source. It is NOT part of the identity, so two notes can
 *     never collide merely because they quote the same sentence.
 *   · **One note per (document, page).** A write replaces the previous text and
 *     bumps a monotonically increasing version; the version is what makes a
 *     concurrent write detectable (`expected_version`). Notes are never deleted
 *     automatically — clearing the text is the only way the text goes away, and
 *     today the only writer is the user.
 *
 * ## Record
 *
 * `<dataRoot>/pdf-notes/<sha256>/p<NNNN>.json`, written with
 * `AtomicJson.writeSync` (temp file + atomic rename):
 *
 * {{{
 * {"v":1,"version":<ms>,"sha256":"…","page":<n>,"text":"…","snippet":"…","updated_ms":<ms>}
 * }}}
 *
 * Reads and writes of one note are serialized by one lock per note file, so a
 * conditional write is a single read-compare-write step.
 */
object PdfNotes:

  private val logger = NebflowLogger.forName("nebflow.pdf.notes")

  /** Maximum note text size in UTF-8 bytes (a note is an annotation, not a document). */
  val MaxTextBytes: Int = 16 * 1024

  /** Maximum snippet size in UTF-8 bytes. */
  val MaxSnippetBytes: Int = 2 * 1024

  /** `<dataRoot>/pdf-notes` — the only place notes are written. */
  def rootFor(dataRoot: os.Path): os.Path = dataRoot / "pdf-notes"

  private val DigestRe = "^[0-9a-f]{64}$".r
  private val locks = new ConcurrentHashMap[String, Object]()

  def validDigest(sha: String): Boolean = sha != null && DigestRe.matches(sha)

  /** True when the file name is a page file of this store (`p0001.json`). */
  private def pageFileName(page: Int): String = f"p$page%04d.json"

  def notePath(dataRoot: os.Path, sha: String, page: Int): os.Path =
    rootFor(dataRoot) / sha / pageFileName(page)

  /** Result of a write. Sealed: a caller must handle every branch, none is an exception. */
  enum WriteResult:
    case Ok(version: Long)
    case TooLarge(limit: Int, field: String)
    case BadDigest(sha: String)
    case BadPage(page: Int)
    case Conflict(current: Long)
    case Failed(message: String)

  /** One read of a note (`None` = no note; `Left` = the record exists but is unreadable). */
  def get(dataRoot: os.Path, sha: String, page: Int): IO[Option[Json]] =
    IO.blocking {
      if !validDigest(sha) || page < 1 then None
      else
        val p = notePath(dataRoot, sha, page)
        if !os.exists(p) then None else Some(io.circe.parser.parse(os.read(p)).fold(_ => Json.Null, identity))
    }.handleErrorWith { e =>
      logger.warnSync(s"pdf-note read failed sha=${sha.take(12)} page=$page: ${e.getMessage}")
      IO.pure(None)
    }

  /** All note page numbers for a document, ascending (never reads the PDFs). */
  def pages(dataRoot: os.Path, sha: String): IO[List[Int]] =
    IO.blocking {
      if !validDigest(sha) then Nil
      else
        val dir = rootFor(dataRoot) / sha
        if !os.exists(dir) then Nil
        else
          os.list(dir)
            .filter(f => f.last.startsWith("p") && f.last.endsWith(".json"))
            .flatMap(f => f.last.drop(1).dropRight(5).toIntOption)
            .sorted
            .toList
    }.handleErrorWith(_ => IO.pure(Nil))

  /**
   * Write (or replace) the note of one page.
   *
   * `expectedVersion` = the version the writer last read; supplying it makes the
   * write conditional (`None` = the writer read no note and expects none).
   * A mismatch is `Conflict(current)` and writes NOTHING — a lost update is
   * reported, never silently applied.
   */
  def put(
    dataRoot: os.Path,
    sha: String,
    page: Int,
    text: String,
    snippet: String = "",
    expectedVersion: Option[Long] = None
  ): IO[WriteResult] =
    IO.blocking {
      if !validDigest(sha) then WriteResult.BadDigest(sha)
      else if page < 1 then WriteResult.BadPage(page)
      else if text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MaxTextBytes then
        WriteResult.TooLarge(MaxTextBytes, "text")
      else if snippet.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MaxSnippetBytes then
        WriteResult.TooLarge(MaxSnippetBytes, "snippet")
      else
        val path = notePath(dataRoot, sha, page)
        lockOf(path) match
          case l =>
            l.synchronized {
              val current = currentVersion(path)
              expectedVersion match
                case Some(exp) if exp != current => WriteResult.Conflict(current)
                case _ =>
                  val now = System.currentTimeMillis()
                  val version = math.max(now, current + 1)
                  val record = Json.obj(
                    "v" -> 1.asJson,
                    "version" -> version.asJson,
                    "sha256" -> sha.asJson,
                    "page" -> page.asJson,
                    "text" -> text.asJson,
                    "snippet" -> snippet.asJson,
                    "updated_ms" -> now.asJson
                  )
                  try
                    AtomicJson.writeSync(path, record.spaces2 + "\n")
                    WriteResult.Ok(version)
                  catch
                    case scala.util.control.NonFatal(e) =>
                      WriteResult.Failed(s"cannot write the note at $path: ${e.getMessage}")
            }
    }.handleErrorWith(e => IO.pure(WriteResult.Failed(s"note write failed: ${e.getMessage}")))

  private def lockOf(path: os.Path): Object = locks.computeIfAbsent(path.toString, _ => new Object)

  /** Current version of a note file (0 = no record). */
  private def currentVersion(path: os.Path): Long =
    if !os.exists(path) then 0L
    else
      io.circe.parser
        .parse(os.read(path))
        .toOption
        .flatMap(_.hcursor.downField("version").as[Long].toOption)
        .getOrElse(0L)

  /** JSON body of a read note, including the page bound verdict when a manifest is known. */
  def json(dataRoot: os.Path, sha: String, page: Int, manifest: Option[PdfManifest]): IO[Json] =
    for
      record <- get(dataRoot, sha, page)
      pageCount = manifest.map(_.pageCount)
      out = Json.obj(
        "sha256" -> sha.asJson,
        "page" -> page.asJson,
        "exists" -> record.isDefined.asJson,
        "note" -> record.getOrElse(Json.Null),
        // `page_verified` = "a parse has happened, so the page bound is real".
        // Without a manifest a page number is accepted but unverified — notes
        // must not force a parse.
        "page_verified" -> pageCount.isDefined.asJson,
        "page_count" -> pageCount.map(_.asJson).getOrElse(Json.Null),
        "out_of_range" -> pageCount.exists(page > _).asJson
      )
    yield out
