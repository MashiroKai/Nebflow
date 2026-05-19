package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}

import java.nio.file.{Files, Path, Paths}
import java.util.Base64

/**
 * Card tool — renders arbitrary HTML in a sandboxed iframe in the chat stream.
 *
 * Agents emit HTML directly. The frontend renders it in a sandboxed <iframe>
 * with theme variables injected, so dark mode works automatically.
 *
 * Local file references (src="...") pointing to image/video/audio/font/PDF files
 * on disk are automatically inlined as base64 data URIs (up to 200 MB each).
 * Only whitelisted media extensions are processed — arbitrary files are skipped.
 */
object CardTool extends Tool:

  private val logger = nebflow.core.NebflowLogger.forName("nebflow.tools.card")

  /** Max file size for data URI embedding (200 MB). */
  private val MaxFileSize = 200 * 1024 * 1024

  /** Allowed media extensions — prevents reading arbitrary non-media files via src=. */
  private val AllowedExtensions = Set(
    // images
    "png",
    "jpg",
    "jpeg",
    "gif",
    "svg",
    "webp",
    "ico",
    "bmp",
    "avif",
    "tiff",
    "tif",
    // video
    "mp4",
    "webm",
    "ogg",
    "ogv",
    "mov",
    "avi",
    // audio
    "mp3",
    "wav",
    "oga",
    "flac",
    "aac",
    "m4a",
    // fonts
    "woff",
    "woff2",
    "ttf",
    "otf",
    "eot",
    // documents
    "pdf"
  )

  private val ExtensionToMime = Map(
    // images
    "png" -> "image/png",
    "jpg" -> "image/jpeg",
    "jpeg" -> "image/jpeg",
    "gif" -> "image/gif",
    "svg" -> "image/svg+xml",
    "webp" -> "image/webp",
    "ico" -> "image/x-icon",
    "bmp" -> "image/bmp",
    "avif" -> "image/avif",
    "tiff" -> "image/tiff",
    "tif" -> "image/tiff",
    // video
    "mp4" -> "video/mp4",
    "webm" -> "video/webm",
    "ogg" -> "video/ogg",
    "ogv" -> "video/ogg",
    "mov" -> "video/quicktime",
    "avi" -> "video/x-msvideo",
    // audio
    "mp3" -> "audio/mpeg",
    "wav" -> "audio/wav",
    "oga" -> "audio/ogg",
    "flac" -> "audio/flac",
    "aac" -> "audio/aac",
    "m4a" -> "audio/mp4",
    // fonts
    "woff" -> "font/woff",
    "woff2" -> "font/woff2",
    "ttf" -> "font/ttf",
    "otf" -> "font/otf",
    "eot" -> "application/vnd.ms-fontobject",
    // documents
    "pdf" -> "application/pdf"
  )

  /** SVG placeholder shown when a file is too large to embed. */
  private def tooLargePlaceholder(fileName: String): String =
    val escaped = fileName.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    s"""data:image/svg+xml,${java.net.URLEncoder.encode(
        s"""<svg xmlns="http://www.w3.org/2000/svg" width="200" height="40">""" +
          s"""<rect width="200" height="40" fill="#f5f5f5" rx="4"/>""" +
          s"""<text x="100" y="25" text-anchor="middle" fill="#999" font-size="12" font-family="sans-serif">""" +
          s"""File too large: $escaped</text></svg>""",
        "UTF-8"
      )}"""

  /** Regex matching src= attributes (only src, not href — only images make sense in sandboxed iframes). */
  private val SrcAttrRegex = """(?i)src\s*=\s*"([^"]+)"""".r

  private def fileExtension(path: String): String =
    path.lastIndexOf('.') match
      case -1 => ""
      case i => path.substring(i + 1).toLowerCase

  private def isAllowedImage(path: String): Boolean =
    AllowedExtensions.contains(fileExtension(path))

  private def isLocalFilePath(s: String): Boolean =
    !s.startsWith("data:") &&
      !s.startsWith("http://") &&
      !s.startsWith("https://") &&
      !s.startsWith("//") &&
      !s.startsWith("#") &&
      !s.startsWith("javascript:") &&
      s.nonEmpty

  /** Resolve a path string (supports ~ expansion) to a normalized java.nio.file.Path. */
  private def resolvePath(s: String): Option[Path] =
    try
      val expanded = if s.startsWith("~") then sys.props("user.home") + s.substring(1) else s
      val p = Paths.get(expanded).normalize()
      if p.toString.nonEmpty then Some(p) else None
    catch case _: Exception => None

  /** Result of attempting to embed a local file. */
  private enum EmbedResult:
    case DataUri(value: String)
    case TooLarge(fileName: String)
    case Skip // not a local path, not an image, file not found, etc.

  /**
   * Try to convert a local file to a data URI.
   * Security: only image extensions are allowed; non-image files are silently skipped.
   */
  private def tryEmbed(value: String): EmbedResult =
    if !isLocalFilePath(value) || !isAllowedImage(value) then EmbedResult.Skip
    else
      resolvePath(value) match
        case None => EmbedResult.Skip
        case Some(path) =>
          try
            if !Files.exists(path) || !Files.isRegularFile(path) then EmbedResult.Skip
            else if Files.size(path) > MaxFileSize then EmbedResult.TooLarge(path.getFileName.toString)
            else
              val bytes = Files.readAllBytes(path)
              val mime = ExtensionToMime.getOrElse(fileExtension(path.toString), "application/octet-stream")
              val b64 = Base64.getEncoder.encodeToString(bytes)
              EmbedResult.DataUri(s"data:$mime;base64,$b64")
          catch case _: Exception => EmbedResult.Skip

  /**
   * Scan HTML for src= attributes pointing to local image files,
   * and replace paths with base64 data URIs.
   * - Non-image files are silently skipped (left as-is).
   * - Files exceeding the size limit get an SVG placeholder.
   */
  private def embedLocalFiles(html: String): String =
    val replacements = SrcAttrRegex
      .findAllMatchIn(html)
      .flatMap { m =>
        val value = m.group(1)
        tryEmbed(value) match
          case EmbedResult.DataUri(uri) => Some((m.start(1), m.end(1), uri))
          case EmbedResult.TooLarge(name) => Some((m.start(1), m.end(1), tooLargePlaceholder(name)))
          case EmbedResult.Skip => None
      }
      .toSeq

    if replacements.isEmpty then html
    else
      val sb = new StringBuilder(html.length + replacements.size * 256)
      var lastEnd = 0
      for (start, end, replacement) <- replacements do
        sb.append(html, lastEnd, start)
        sb.append(replacement)
        lastEnd = end
      sb.append(html, lastEnd, html.length)
      val result = sb.toString
      if result != html then logger.debug(s"Embedded ${replacements.size} local file(s) as data URIs")
      result

  val name = "Card"

  val description = """Renders HTML content as a visual card in the chat.

Use this tool when you want to show something visual — previews, dashboards, diagrams, tables, styled content, etc.
Just pass raw HTML (with inline CSS). The frontend renders it in a sandboxed iframe.

Use this when plain text or Markdown falls short. HTML enables layouts, colors, and visual precision that Markdown cannot express.

Typical scenarios:
- Multi-column layouts — side-by-side comparisons, dashboards with panels, grid-based designs
- Color-dependent content — color palettes, heatmaps, status indicators with precise colors
- Visual UI previews — layout mockups with exact spacing, border radii, shadows, gradients
- Interactive-looking elements — hover states, clickable cards, toggle switches (visual only, no JS execution)
- Diagrams with precise positioning — architecture diagrams, flowcharts, timelines, annotated images
- Progress & status visuals — progress bars, step indicators, circular gauges, completion badges
- Concept explanations — math/physics formulas with annotated SVG diagrams, step-by-step derivations with visual alignment
- Before/after comparisons — split views, slider-like overlays, A/B layout diffs
- Complex data grids — sortable-style tables with styled headers, conditional formatting, multi-level grouping
- Pixel-perfect mockups — anything where exact padding, alignment, or visual hierarchy matters


Parameters:
- html (string, required): The HTML content to render. Include all styles inline or in a <style> tag.
  Use CSS custom properties from the host theme: var(--color-primary), var(--color-text), var(--color-bg), etc.
- title (string, optional): A short title shown above the card (e.g. "Color Palette", "Page Preview").

Guidelines:
- Keep HTML self-contained — all styles inline or in <style> tags within the HTML.
- For dark mode support, use `@media (prefers-color-scheme: dark)` or the injected CSS variables.
- JavaScript in the HTML will NOT execute (sandboxed for security).
- Keep the payload reasonable in size — very large HTML wastes LLM context tokens.
- **Font size**: default is 14px. Use smaller sizes (12–14px) for dense content like tables or dashboards. Avoid 16px+ body text — it wastes space in the chat stream.
- **Horizontal layout**: the card width is limited (~85% of chat width). For multi-column layouts, use `flex-wrap: wrap`, `min-width` on columns, or ensure content can reflow. Do not rely on wide fixed widths.
- **Images**: use `max-width: 100%` on images to prevent overflow.

Example:
{
  "html": "<div style=\"font-family:sans-serif;padding:12px;font-size:13px\"><h3 style=\"margin:0 0 8px\">Status</h3><p style=\"color:var(--color-success)\">All systems green</p></div>",
  "title": "Status"
}"""

  val inputSchema: JsonObject = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "html" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "HTML content to render in the card".asJson
        ),
        "title" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Optional title shown above the card".asJson
        )
      ),
      "required" -> Json.arr("html".asJson)
    )
  )

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    input("html").flatMap(_.asString) match
      case Some(rawHtml) =>
        val title = input("title").flatMap(_.asString).getOrElse("")
        IO.blocking {
          val html = embedLocalFiles(rawHtml)
          val payload = Json
            .obj(
              "html" -> html.asJson,
              "title" -> title.asJson
            )
            .noSpaces
          Right(s"___CARD_HTML___$payload")
        }

      case None =>
        IO.pure(Left(ToolError("Missing required parameter: html")))

  def summarize(input: JsonObject): String =
    val title = input("title").flatMap(_.asString).getOrElse("Card")
    s"Card\n  ($title)"

  def summarizeResult(input: JsonObject, result: String): String =
    val title = input("title").flatMap(_.asString).getOrElse("Card")
    s"$title rendered"

end CardTool
