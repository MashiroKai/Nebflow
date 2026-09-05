package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}

import java.nio.file.{Files, Path, Paths}

/**
 * Pop tool — opens a file or URL in the Canvas panel as a new tab.
 *
 * Sends a WebSocket message to the frontend, which dispatches a
 * `workspace-open-item` event that canvas.js picks up to render the file
 * using the existing file viewer registry (Monaco editor for code, markdown
 * renderer, image viewer, PDF viewer, etc.).
 *
 * For binary files (images, PDFs, Office docs), only metadata is sent — the
 * frontend fetches the content via /api/nf-file, same as the file explorer.
 *
 * For HTTP/HTTPS URLs, the URL is sent directly — the frontend renders it
 * in an embedded iframe.
 */
object PopTool extends Tool:

  private val logger = nebflow.core.NebflowLogger.forName("nebflow.tools.pop")

  /** Max text file size to send via WS (2 MB). Larger files are read by the frontend via /api/nf-file. */
  private val MaxTextSize = 2 * 1024 * 1024

  /** Max single image size to embed as base64 data URI (5 MB). Larger images keep their original src. */
  private val MaxEmbedImageSize = 5 * 1024 * 1024

  /** Image extensions that can be embedded as data URIs in HTML. */
  private val EmbeddableImageExtensions = Set("png", "jpg", "jpeg", "gif", "webp", "svg", "bmp")

  /** Matches the src attribute value of an <img> tag (single or double quoted). */
  private val ImgSrcPattern = """(?i)<img\b[^>]*?\bsrc\s*=\s*["']([^"']+)["']""".r

  private def fileExtension(path: String): String =
    path.lastIndexOf('.') match
      case -1 => ""
      case i => path.substring(i + 1).toLowerCase

  // Binary-extension + itemType mapping now lives in a single source of
  // truth (F3): core/workspace/FileTypeRegistry — shared with the WS
  // readFile / pop.readFile routes. The local BinaryExtensions set and
  // detectItemType match were removed as duplicate #1/#2.

  /** Resolve a path string (supports ~ expansion) to a normalized Path. */
  private def resolvePath(s: String): Option[Path] =
    try
      val expanded = if s.startsWith("~") then sys.props("user.home") + s.substring(1) else s
      Some(Paths.get(expanded).normalize())
    catch case _: Exception => None

  /** Map image file extension to MIME type. */
  private def mimeFromExt(ext: String): String = ext.toLowerCase match
    case "png"         => "image/png"
    case "jpg" | "jpeg" => "image/jpeg"
    case "gif"         => "image/gif"
    case "webp"        => "image/webp"
    case "svg"         => "image/svg+xml"
    case "bmp"         => "image/bmp"
    case _             => "application/octet-stream"

  /** Check if a src value is a remote/special URL that should not be embedded. */
  private def isRemoteOrSpecialUrl(src: String): Boolean =
    val lower = src.toLowerCase
    lower.startsWith("http://") || lower.startsWith("https://") ||
      lower.startsWith("data:") || lower.startsWith("blob:") ||
      lower.startsWith("#") || lower.startsWith("javascript:") ||
      lower.startsWith("mailto:") || lower.startsWith("tel:")

  /** Resolve an img src (file://, /absolute, ~/, or relative) to a local file Path. */
  private def resolveImgSrc(src: String, htmlDir: Path): Option[Path] =
    val cleaned =
      if src.startsWith("file:///") then src.stripPrefix("file://")          // file:///path → /path
      else if src.startsWith("file://localhost/") then "/" + src.stripPrefix("file://localhost")
      else if src.startsWith("file://") then "/" + src.stripPrefix("file://") // file://path → /path
      else if src.startsWith("file:") then src.stripPrefix("file:")
      else src
    resolvePath(cleaned).map(p =>
      if p.isAbsolute then p
      else htmlDir.resolve(p).normalize()
    )

  /**
   * Embed local images referenced by <img src="..."> as base64 data URIs so
   * they render inside the Canvas iframe without /api/nf-file or auth tokens.
   * Remote URLs and unreadable files are left unchanged (silent skip).
   */
  private def embedLocalImages(html: String, htmlDir: Path): String =
    ImgSrcPattern.replaceAllIn(html, { m =>
      val src = m.group(1)
      if isRemoteOrSpecialUrl(src) then m.group(0)
      else
        val replaced: Option[String] = resolveImgSrc(src, htmlDir).flatMap { imgPath =>
          try
            val ext = fileExtension(imgPath.toString)
            if !Files.exists(imgPath) || !Files.isRegularFile(imgPath) ||
               !EmbeddableImageExtensions.contains(ext)
            then None
            else if Files.size(imgPath) > MaxEmbedImageSize then None
            else
              val bytes = Files.readAllBytes(imgPath)
              val b64 = java.util.Base64.getEncoder.encodeToString(bytes)
              val dataUri = s"data:${mimeFromExt(ext)};base64,$b64"
              // Replace only the src value: the regex guarantees group(1) is
              // immediately before the closing quote at the end of the match.
              val full = m.group(0)
              Some(full.dropRight(src.length + 1) + dataUri + full.takeRight(1))
          catch case _: Exception => None
        }
        replaced.getOrElse(m.group(0))
    })

  /** Extract hostname from a URL string. */
  private def extractHostname(url: String): String =
    try java.net.URI.create(url).getHost
    catch case _: Exception => url

  /** Check if a string is an HTTP/HTTPS URL. */
  private def isHttpUrl(s: String): Boolean =
    s.startsWith("http://") || s.startsWith("https://")

  val name = "Pop"

  val description: String =
    """Opens a file or URL in the Canvas panel as a new tab. Files are displayed using the appropriate viewer (Monaco editor for code, markdown renderer, image viewer, PDF viewer, etc.). URLs are displayed in an embedded iframe.

## Visual reporting workflow
When you complete a significant task, present results visually with Pop — humans process visual information far more efficiently than long paragraphs. Use a professional tool via Bash to generate the output as a file (SVG preferred — scales perfectly and adapts to dark mode), then Pop it: charts/plots → matplotlib/gnuplot/plotly; flowcharts & architecture diagrams → graphviz; UML → plantuml/mermaid; timing → wavedrom. Anti-pattern: never hand-draw diagrams with ASCII art or raw SVG coordinates. Pop the result to Canvas immediately after generating it; for complex reports write a self-contained .html file with embedded charts and Pop that.

## When to use

- You just created or modified a file and want to show it to the user.
- The user asks to "open" or "show" a file.
- You generated a plot, diagram, or document and want to present it.
- After completing work, generate a visual report (diagram, chart, HTML page) and Pop it to present results to the user.
- You want to show a web page (e.g. a deployed site, documentation) to the user.

The Canvas tab supports the same file types as the file explorer. The tab title defaults to the filename (or hostname for URLs); provide `title` to customize it.

## Parameters

- filePath (string, required): Absolute path to the file (supports `~` expansion), or an HTTP/HTTPS URL.
- title (string, optional): Custom tab title. Defaults to the filename or URL hostname.

Example: {"filePath": "/tmp/output.svg"}
Example: {"filePath": "~/projects/README.md", "title": "README"}
Example: {"filePath": "https://example.com"}"""

  val inputSchema: JsonObject = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "filePath" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Absolute path to the file to display (supports ~ expansion), or an HTTP/HTTPS URL".asJson
        ),
        "title" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Custom tab title (defaults to filename)".asJson
        )
      ),
      "required" -> Json.arr("filePath".asJson)
    )
  )

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val filePathStr = input("filePath").flatMap(_.asString).getOrElse("")
    val customTitle = input("title").flatMap(_.asString).getOrElse("")

    if filePathStr.isBlank then IO.pure(Left(ToolError("Pop tool requires a `filePath` parameter.")))
    else if isHttpUrl(filePathStr) then
      val hostname = extractHostname(filePathStr)
      val tabTitle = if customTitle.nonEmpty then customTitle else hostname
      val msg = Json.obj(
        "type" -> "popFile".asJson,
        "item" -> Json.obj(
          "id" -> s"url:$filePathStr".asJson,
          "itemType" -> "url".asJson,
          "title" -> tabTitle.asJson,
          "url" -> filePathStr.asJson,
          "pinned" -> true.asJson
        )
      )
      val sendIO = ctx.wsSend.getOrElse((_: Json) => IO.unit)
      sendIO(msg) >> IO.pure(Right(s"Opened $tabTitle in Canvas."))
    else
      resolvePath(filePathStr) match
        case None =>
          IO.pure(Left(ToolError(s"Invalid path: $filePathStr")))

        case Some(path) =>
          IO.blocking {
            if !Files.exists(path) then Left(ToolError(s"File not found: $path"))
            else if !Files.isRegularFile(path) then Left(ToolError(s"Not a regular file: $path"))
            else
              val ext = fileExtension(path.toString)
              val entry = nebflow.core.workspace.FileTypeRegistry.detect(ext)
              val itemType = entry.itemType
              val fileName = path.getFileName.toString
              val tabTitle = if customTitle.nonEmpty then customTitle else fileName
              val size = Files.size(path)
              val isBinary = entry.binary

              // For text files under MaxTextSize: read content and send via WS.
              // For binary files or large text: send metadata only, frontend fetches via /api/nf-file.
              val rawContent =
                if isBinary || size > MaxTextSize then ""
                else new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8)

              // Embed local images as base64 data URIs so they render in the
              // Canvas iframe. Only for HTML files with non-empty content.
              val htmlDir = Option(path.getParent).getOrElse(Paths.get("."))
              val content =
                if itemType == "html" && rawContent.nonEmpty then embedLocalImages(rawContent, htmlDir)
                else rawContent

              // Build the WS message — dispatched as 'popFile' type.
              val msg = Json.obj(
                "type" -> "popFile".asJson,
                "item" -> Json.obj(
                  "id" -> s"file:${path.toString}".asJson,
                  "itemType" -> itemType.asJson,
                  "title" -> tabTitle.asJson,
                  "content" -> content.asJson,
                  "absPath" -> path.toString.asJson,
                  "size" -> size.asJson,
                  "pinned" -> true.asJson
                )
              )

              Right((msg, tabTitle))
          }.flatMap {
            case Left(err) => IO.pure(Left(err))
            case Right((msg, tabTitle)) =>
              val sendIO = ctx.wsSend.getOrElse((_: Json) => IO.unit)
              sendIO(msg) >> IO.pure(Right(s"Opened $tabTitle in Canvas."))
          }

    end if

  end call

  def summarize(input: JsonObject): String =
    val filePath = input("filePath").flatMap(_.asString).getOrElse("?")
    val title = input("title").flatMap(_.asString).getOrElse("")
    val label =
      if title.nonEmpty then title
      else if isHttpUrl(filePath) then extractHostname(filePath)
      else filePath.split('/').lastOption.getOrElse(filePath)
    s"Pop\n  ($label)"

  def summarizeResult(input: JsonObject, result: String): String =
    val filePath = input("filePath").flatMap(_.asString).getOrElse("?")
    val fileName =
      if isHttpUrl(filePath) then extractHostname(filePath)
      else filePath.split('/').lastOption.getOrElse(filePath)
    s"Opened $fileName in Canvas"

end PopTool
