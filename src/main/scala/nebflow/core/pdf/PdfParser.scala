package nebflow.core.pdf

import cats.effect.IO
import nebflow.shared.NebflowLogger

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * The parser leg: run the open-source PDF parser as a subprocess and read its
 * verdict.
 *
 * ## Why a subprocess, and why the script is a resource
 *
 * The parser is Python (PyMuPDF) — see `src/main/resources/pdf/pdf_parse.py` for
 * the extraction contract and `module-doc.md` in this package for the license and
 * dependency statement. Keeping it a subprocess means the JVM gains no new
 * library and the parser can be updated, versioned and tested on its own. The
 * script travels as a classpath resource so a packaged install carries it (a
 * checkout-relative path would silently break in a distribution); it is extracted
 * LAZILY, on the first real parse, into
 * `<dataRoot>/pdf-cache/_parser/<sha8-of-script>/pdf_parse.py` — content-addressed,
 * so editing the script re-extracts and no stale copy can ever run.
 *
 * ## Interpreter selection (single knob)
 *
 * `STAND_PDF_PYTHON` (default `python3`). Nothing else in this feature picks an
 * interpreter, so "which python" has exactly one answer to read.
 *
 * ## Failure contract
 *
 * The parser exits 0 + writes `meta.json` on success, or exits 3 with one line of
 * JSON `{"ok":false,"error":"<CODE>","message":"…"}` on failure; exit 2 means a
 * usage error (a bug on our side, reported as `PARSER_USAGE`). Every path returns
 * `Left(PdfFailure)` with a machine code and a human sentence — a spawn failure,
 * a timeout-less hang or a corrupt PDF must be distinguishable by the caller,
 * never an exception that dies inside a background fiber.
 */
object PdfParser:

  private val logger = NebflowLogger.forName("nebflow.pdf")

  /** Classpath resource holding the parser script (the single source of truth). */
  val ScriptResource: String = "pdf/pdf_parse.py"

  /**
   * Markdown shape version — must equal `MARKDOWN_VERSION` in the parser script
   * (asserted by `scripts/tests/pdf-parse.test.mjs`; the two are the same fact in
   * two languages, and a mismatch would silently serve text in the wrong shape).
   */
  val MarkdownVersion: Int = 1

  /** Environment knob naming the interpreter (single knob; default `python3`). */
  val PythonEnv: String = "STAND_PDF_PYTHON"

  def pythonBin(env: Map[String, String] = sys.env): String =
    env.get(PythonEnv).map(_.trim).filter(_.nonEmpty).getOrElse("python3")

  /** Failure with a stable machine code and a readable sentence. */
  final case class PdfFailure(code: String, message: String):
    def toJson: io.circe.Json =
      io.circe.Json.obj("error" -> io.circe.Json.fromString(code), "message" -> io.circe.Json.fromString(message))

  /** The interpreter could not be started / the script resource is absent. */
  val CodeParserMissing: String = "PARSER_MISSING"
  val CodeParserUsage: String = "PARSER_USAGE"
  val CodeParseFailed: String = "PARSE_FAILED"

  /** Byte-content identity of a file (streamed; never loads the file in memory). */
  def sha256OfFile(path: os.Path): IO[String] =
    IO.blocking {
      val md = MessageDigest.getInstance("SHA-256")
      val in = java.io.FileInputStream(path.toIO)
      try
        val buf = new Array[Byte](1 << 20)
        var n = in.read(buf)
        while n >= 0 do
          if n > 0 then md.update(buf, 0, n)
          n = in.read(buf)
      finally in.close()
      md.digest().map(b => f"${b & 0xff}%02x").mkString
    }

  /** Read a classpath resource as text, or `None` when it is not on the classpath. */
  private def readResource(name: String): Option[String] =
    Option(getClass.getClassLoader.getResourceAsStream(name)).map { s =>
      try new String(s.readAllBytes(), StandardCharsets.UTF_8)
      finally s.close()
    }

  /**
   * Extract the parser script into `dir` under a content-addressed name.
   * `Left` = the resource is missing (a broken install/build); the caller reports
   * it as [[CodeParserMissing]] instead of spawning a nonexistent file.
   */
  def materializeScript(dir: os.Path): Either[PdfFailure, os.Path] =
    readResource(ScriptResource) match
      case None =>
        Left(PdfFailure(CodeParserMissing, s"the parser script resource `$ScriptResource` is not on the classpath"))
      case Some(text) =>
        val bytes = text.getBytes(StandardCharsets.UTF_8)
        val md = MessageDigest.getInstance("SHA-256").digest(bytes)
        val short = md.take(4).map(b => f"${b & 0xff}%02x").mkString
        val target = dir / s"pdf_parse-$short.py"
        try
          if !os.exists(target) then
            os.makeDir.all(dir)
            os.write.over(target, bytes)
          Right(target)
        catch
          case scala.util.control.NonFatal(e) =>
            Left(PdfFailure(CodeParseFailed, s"cannot extract the parser script to $target: ${e.getMessage}"))

  /**
   * Run the parser on one PDF into `outDir` (a staging directory the caller owns)
   * and read the resulting manifest.
   *
   * The subprocess inherits no shell (argv only), records its full command line
   * in the returned failure text, and its stderr is captured so a parser-side
   * traceback is visible in the gateway log instead of being lost.
   */
  def parse(
    script: os.Path,
    pdf: os.Path,
    outDir: os.Path,
    displayName: String,
    python: String = pythonBin(),
    stderrTail: Int = 4000
  ): IO[Either[PdfFailure, PdfManifest]] =
    val argv = List(python, script.toString, "--input", pdf.toString, "--out", outDir.toString, "--name", displayName)
    IO.blocking {
      val spawned =
        try Right(os.proc(argv).call(cwd = outDir / os.up, check = false, stdout = os.Pipe, stderr = os.Pipe))
        catch
          case scala.util.control.NonFatal(e) =>
            Left(PdfFailure(CodeParserMissing, s"cannot start the PDF parser: $python ($argv): ${e.getMessage}"))
      spawned.flatMap(res => verdictOf(res, outDir, argv, stderrTail))
    }.handleErrorWith(e => IO.pure(Left(PdfFailure(CodeParseFailed, s"parser run failed: ${e.getMessage}"))))
      .map {
        case r @ Right(m) =>
          logger.infoSync(
            s"pdf-parse ok sha=${m.sourceSha256.take(12)} pages=${m.pageCount} tables=${m.tableCount} images=${m.imageCount} parser=${m.parser.name}/${m.parser.version}"
          )
          r
        case l @ Left(f) =>
          logger.warnSync(s"pdf-parse failed code=${f.code} pdf=$pdf: ${f.message}")
          l
      }

  /**
   * Turn one finished parser run into a verdict. Exit 0 is only a success when
   * the manifest it wrote is readable — a parser that exits 0 with a broken
   * manifest is a failure, not an empty document.
   */
  private def verdictOf(
    res: os.CommandResult,
    outDir: os.Path,
    argv: List[String],
    stderrTail: Int
  ): Either[PdfFailure, PdfManifest] =
    val out = res.out.text()
    val err = res.err.text()
    val errTail = if err.length > stderrTail then err.takeRight(stderrTail) else err
    if res.exitCode == 0 then
      val metaPath = outDir / "meta.json"
      if !os.exists(metaPath) then Left(PdfFailure(CodeParseFailed, s"parser exited 0 without writing meta.json ($argv)"))
      else
        PdfManifest.decode(os.read(metaPath)) match
          case Right(m)  => Right(m)
          case Left(why) => Left(PdfFailure(CodeParseFailed, s"parser wrote an unreadable manifest: $why"))
    else
      // The parser's own single-line JSON verdict is the best message; a
      // missing/broken verdict falls back to the raw streams.
      val verdict =
        out.linesIterator
          .map(_.trim)
          .filter(_.startsWith("{"))
          .toList
          .flatMap(line => io.circe.parser.parse(line).toOption)
          .lastOption
          .flatMap { j =>
            for
              code <- j.hcursor.downField("error").as[String].toOption
              message <- j.hcursor.downField("message").as[String].toOption
            yield PdfFailure(code, message)
          }
      verdict match
        case Some(f)                    => Left(f)
        case None if res.exitCode == 2  => Left(PdfFailure(CodeParserUsage, s"parser usage error (exit 2): ${errTail.trim}"))
        case None =>
          Left(PdfFailure(CodeParseFailed, s"parser failed (exit ${res.exitCode}) without a readable verdict: ${errTail.trim}"))

