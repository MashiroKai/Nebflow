package nebflow.core.pdf

import cats.effect.{Deferred, IO, Ref}
import nebflow.core.pdf.PdfParser.PdfFailure
import nebflow.shared.NebflowLogger

import java.util.UUID

/**
 * The parsed-document cache: ONE source path per instance data root.
 *
 * ## Layout (a contract, not a convention)
 *
 * {{{
 * <dataRoot>/pdf-cache/<sha256[0:2]>/<sha256>/meta.json          the manifest
 * <dataRoot>/pdf-cache/<sha256[0:2]>/<sha256>/pages/p0001.md     one file per page
 * <dataRoot>/pdf-cache/<sha256[0:2]>/<sha256>/media/p0001-0.png  extracted media
 * <dataRoot>/pdf-cache/_parser/<hash8>/pdf_parse.py              the parser script
 * }}}
 *
 * The data root is the single configuration input (`--home` / `STAND_HOME` /
 * `~/.stand`, resolved by `PathUtil`), so an isolated instance with its own root
 * gets its own cache with no further configuration and can never read or pollute
 * another instance's cache. Nothing else in this feature writes under the data
 * root; there is no second cache path to keep in sync.
 *
 * The two-level digest prefix keeps one directory from holding every document
 * (a flat directory of tens of thousands of entries is slow to list and to
 * clean); the entry itself is named by the full digest, so an entry's name is
 * also its verification.
 *
 * ## Laziness
 *
 * NOTHING here runs on open, on preview, on a note write or on a page read. The
 * only entry point that starts a parse is [[ensure]], and the only caller of
 * [[ensure]] is the ask path (an ask message). `lookup` is a pure read: it never
 * spawns a parser and never repairs an entry.
 *
 * ## Deduplication and reuse
 *
 * Two layers, deliberately:
 *   · On disk — `lookup` hits a complete entry and returns immediately; that is
 *     what makes the SECOND ask about the same document free.
 *   · In memory — concurrent asks for one digest share one [[Deferred]]: the
 *     first caller installs the promise and parses, the others await it. The
 *     promise is kept after completion, so a later ask re-uses the in-memory
 *     manifest without touching the disk. It is dropped on failure, so a failed
 *     parse is retried by the next ask instead of being cached as a verdict.
 *
 * ## Publication is atomic, and failures leave nothing behind
 *
 * The parser writes into a sibling staging directory; only a complete entry is
 * renamed into place (rename of a directory is atomic on one filesystem). A
 * failed parse removes the staging directory and leaves the entry absent, so a
 * failure can never be mistaken for "empty document" and the next ask retries.
 * If another process published the same digest first, the staging directory is
 * dropped and the winner is reused — the cache converges on one entry.
 */
final class PdfCache(
  val root: os.Path,
  scriptDir: os.Path,
  python: String = PdfParser.pythonBin()
):

  private val logger = NebflowLogger.forName("nebflow.pdf.cache")

  /** In-flight/completed parses by digest (single-flight). */
  private val flights: Ref[IO, Map[String, Deferred[IO, Either[PdfFailure, PdfManifest]]]] =
    Ref.unsafe(Map.empty)

  /** Digest prefix directory (`<sha[0:2]>`) — the fan-out level. */
  def bucketDir(sha: String): os.Path = root / sha.take(2)

  /** The entry directory for a digest: `<root>/<sha[0:2]>/<sha>`. */
  def entryDir(sha: String): os.Path = bucketDir(sha) / sha

  def metaPath(sha: String): os.Path = entryDir(sha) / "meta.json"

  /** Entry-relative page file for a 1-based page (`pages/p0001.md`). */
  def pagePath(sha: String, page: Int): os.Path = entryDir(sha) / "pages" / f"p$page%04d.md"

  /** Entry-relative media path for a manifest media entry (`media/…`). */
  def mediaPath(sha: String, rel: String): os.Path = entryDir(sha) / os.RelPath(rel)

  /**
   * Read a COMPLETE entry, or `None`. Never parses, never repairs.
   *
   * A manifest whose `markdown_version` is not the current one is a miss (an
   * entry produced by an older shape must be rebuilt, never half-served).
   */
  def lookup(sha: String): IO[Option[PdfManifest]] =
    IO.blocking {
      val meta = metaPath(sha)
      if !os.exists(meta) then None
      else
        PdfManifest.decode(os.read(meta)) match
          case Right(m) if m.markdownVersion == PdfParser.MarkdownVersion => Some(m)
          case Right(m) =>
            logger.infoSync(
              s"pdf-cache stale sha=${sha.take(12)} markdown_version=${m.markdownVersion} != ${PdfParser.MarkdownVersion} — treating as a miss"
            )
            None
          case Left(why) =>
            logger.warnSync(s"pdf-cache unreadable entry sha=${sha.take(12)}: $why — treating as a miss")
            None
    }.handleErrorWith { e =>
      logger.warnSync(s"pdf-cache lookup failed sha=${sha.take(12)}: ${e.getMessage}")
      IO.pure(None)
    }

  /**
   * Guarantee an entry for `pdf` exists and return its manifest.
   *
   * Call this ONLY from the ask path. It is safe to call concurrently for the
   * same document: exactly one parse runs, every caller gets that parse's result.
   */
  def ensure(pdf: os.Path, displayName: String): IO[Either[PdfFailure, PdfManifest]] =
    PdfParser
      .sha256OfFile(pdf)
      .map(sha => Right(sha): Either[PdfFailure, String])
      .handleErrorWith { e =>
        IO.pure(Left(PdfFailure("PDF_NOT_FOUND", s"cannot read $pdf to compute its digest: ${e.getMessage}")))
      }
      .flatMap {
        case Left(f) => IO.pure(Left(f))
        case Right(sha) =>
          lookup(sha).flatMap {
            case Some(m) => IO.pure(Right(m))
            case None    => ensureParsed(sha, pdf, displayName)
          }
      }

  private def ensureParsed(sha: String, pdf: os.Path, displayName: String): IO[Either[PdfFailure, PdfManifest]] =
    Deferred[IO, Either[PdfFailure, PdfManifest]].flatMap { fresh =>
      flights
        .modify { m =>
          m.get(sha) match
            case Some(existing) => (m, Left(existing))
            case None           => (m.updated(sha, fresh), Right(fresh))
        }
        .flatMap {
          case Left(existing) =>
            // Deduplicated: someone else is already parsing (or has just finished
            // and the promise is still in the map). Await their verdict.
            IO.delay(
              logger.infoSync(s"pdf-cache join sha=${sha.take(12)} (a parse for this document is already in flight)")
            ) *> existing.get
          case Right(owner) =>
            parseInto(sha, pdf, displayName).flatTap { verdict =>
              // Success is kept (a later ask reuses the in-memory manifest);
              // failure is dropped so the next ask retries instead of inheriting
              // a stale negative verdict.
              owner.complete(verdict) *> flushOnFailure(sha, verdict)
            }
        }
    }

  private def flushOnFailure(sha: String, verdict: Either[PdfFailure, PdfManifest]): IO[Unit] =
    verdict match
      case Right(_) => IO.unit
      case Left(_)  => flights.update(_ - sha)

  private def parseInto(sha: String, pdf: os.Path, displayName: String): IO[Either[PdfFailure, PdfManifest]] =
    // A missing/unextractable parser script is a readable failure of its own
    // (PARSER_MISSING), not a generic crash: the caller must be able to tell
    // "this install cannot parse" from "this PDF cannot be parsed".
    PdfParser.materializeScript(scriptDir) match
      case Left(missing) => IO.pure(Left(missing))
      case Right(script) => parseWithScript(sha, pdf, displayName, script)

  private def parseWithScript(
    sha: String,
    pdf: os.Path,
    displayName: String,
    script: os.Path
  ): IO[Either[PdfFailure, PdfManifest]] =
    val staging = bucketDir(sha) / s"$sha.tmp-${UUID.randomUUID().toString.take(8)}"
    val run = for
      _ <- IO.blocking(os.makeDir.all(staging))
      _ <- logger.info(
        s"pdf-cache parse start sha=${sha.take(12)} name=$displayName entry=${entryDir(sha)}"
      )
      verdict <- PdfParser.parse(script, pdf, staging, displayName, python)
      finalVerdict <- verdict match
        case Right(m) =>
          IO.blocking {
            if os.exists(entryDir(sha)) then
              // Another process published while we parsed: keep theirs.
              os.remove.all(staging)
              logger.infoSync(s"pdf-cache race lost sha=${sha.take(12)} — reusing the existing entry")
              lookupSync(sha).toRight(PdfFailure(PdfParser.CodeParseFailed, "the existing entry could not be read"))
            else
              os.makeDir.all(bucketDir(sha))
              os.move(staging, entryDir(sha))
              logger.infoSync(
                s"pdf-cache published sha=${sha.take(12)} pages=${m.pageCount} entry=${entryDir(sha)}"
              )
              Right(m)
          }
        case Left(f) =>
          IO.blocking(if os.exists(staging) then os.remove.all(staging))
            .handleErrorWith(_ => IO.unit)
            .as(Left(f))
    yield finalVerdict
    run.handleErrorWith { e =>
      val msg = s"pdf-cache parse crashed: ${e.getClass.getSimpleName}: ${e.getMessage}"
      logger.errorSync(msg)
      IO.blocking(if os.exists(staging) then os.remove.all(staging))
        .handleError(_ => ())
        .as(Left(PdfFailure(PdfParser.CodeParseFailed, msg)))
    }

  /** Synchronous corner of [[lookup]] used inside the publication critical section. */
  private def lookupSync(sha: String): Option[PdfManifest] =
    val meta = metaPath(sha)
    if !os.exists(meta) then None
    else PdfManifest.decode(os.read(meta)).toOption.filter(_.markdownVersion == PdfParser.MarkdownVersion)

  /** Read one page's Markdown from a COMPLETE entry (an on-demand page read). */
  def readPage(sha: String, page: Int): IO[Either[String, String]] =
    IO.blocking {
      val p = pagePath(sha, page)
      if !os.exists(p) then Left(s"page $page is not in the cache entry for ${sha.take(12)}")
      else Right(os.read(p))
    }.handleErrorWith(e => IO.pure(Left(s"cannot read page $page: ${e.getMessage}")))

  /** Number of entries currently on disk (evidence + cleanup accounting). */
  def entryCount: IO[Int] =
    IO.blocking {
      if !os.exists(root) then 0
      else
        os.list(root)
          .filter(os.isDir)
          .filterNot(_.last.startsWith("_"))
          .map(b => os.list(b).count(os.isDir))
          .sum
    }.handleErrorWith(_ => IO.pure(0))

object PdfCache:

  /** The cache root under a data root (single source path). */
  def rootFor(dataRoot: os.Path): os.Path = dataRoot / "pdf-cache"

  /** Where the lazily extracted parser script lives. */
  def scriptDirFor(dataRoot: os.Path): os.Path = dataRoot / "pdf-cache" / "_parser"

  /** Cache for one instance data root. */
  def forDataRoot(dataRoot: os.Path): PdfCache =
    new PdfCache(rootFor(dataRoot), scriptDirFor(dataRoot))
