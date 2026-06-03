package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}

import java.nio.file.{Files, Path, Paths}

/**
 * Card tool — renders arbitrary HTML in a sandboxed iframe in the chat stream.
 *
 * Agents emit HTML directly. The frontend renders it in a sandboxed <iframe>
 * with theme variables injected, so dark mode works automatically.
 *
 * Local file references (src="...") pointing to image/video/audio/font/PDF files
 * on disk are converted to /api/nf-file?path=... URLs served by the HTTP endpoint.
 * The frontend injects the auth token into these URLs before rendering.
 * Only whitelisted media extensions are processed — arbitrary files are skipped.
 */
object CardTool extends Tool:

  private val logger = nebflow.core.NebflowLogger.forName("nebflow.tools.card")

  /** Max file size for HTTP-served files (200 MB). */
  private val MaxFileSize = 200 * 1024 * 1024

  /** Allowed media extensions — prevents reading arbitrary non-media files via src=. */
  private val AllowedExtensions = Set(
    // images
    "png", "jpg", "jpeg", "gif", "svg", "webp", "ico", "bmp", "avif", "tiff", "tif",
    // video
    "mp4", "webm", "ogg", "ogv", "mov", "avi",
    // audio
    "mp3", "wav", "oga", "flac", "aac", "m4a",
    // fonts
    "woff", "woff2", "ttf", "otf", "eot",
    // documents
    "pdf"
  )

  /** Regex matching src= attributes with both single and double quotes. */
  private val SrcAttrRegex = """(?i)src\s*=\s*["']([^"']+)["']""".r

  private def fileExtension(path: String): String =
    path.lastIndexOf('.') match
      case -1 => ""
      case i => path.substring(i + 1).toLowerCase

  private def isAllowedMedia(path: String): Boolean =
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

  /**
   * Try to convert a local file path to an /api/nf-file URL.
   * Returns Some(url) if the file exists and is a whitelisted media file, None otherwise.
   */
  private def tryEmbed(value: String): Option[String] =
    if !isLocalFilePath(value) || !isAllowedMedia(value) then None
    else
      resolvePath(value) match
        case None => None
        case Some(path) =>
          try
            if !Files.exists(path) || !Files.isRegularFile(path) then None
            else if Files.size(path) > MaxFileSize then None
            else
              val encoded = java.net.URLEncoder.encode(path.toString, "UTF-8")
              Some(s"/api/nf-file?path=$encoded")
          catch case _: Exception => None

  /**
   * Scan HTML for src= attributes pointing to local media files,
   * and replace paths with /api/nf-file?path=... URLs.
   * Non-media files are silently skipped (left as-is).
   */
  private def embedLocalFiles(html: String): String =
    val replacements = SrcAttrRegex
      .findAllMatchIn(html)
      .flatMap { m =>
        val value = m.group(1)
        tryEmbed(value).map(url => (m.start(1), m.end(1), url))
      }
      .toSeq

    if replacements.isEmpty then html
    else
      val sb = new StringBuilder(html.length + replacements.size * 128)
      var lastEnd = 0
      for (start, end, replacement) <- replacements do
        sb.append(html.substring(lastEnd, start))
        sb.append(replacement)
        lastEnd = end
      sb.append(html.substring(lastEnd, html.length))
      val result = sb.toString
      if result != html then
        logger.debug(s"Embedded ${replacements.size} local file(s) via /api/nf-file")
      result
    end if
  end embedLocalFiles

  val name = "Card"

  /** Path to user-editable card design prompt. */
  private val designPromptPath = java.nio.file.Paths.get(sys.props("user.home"), ".nebflow", "card-design-prompt.md")

  /** Cached design prompt (reloaded on each access via mtime check). */
  @volatile private var designPromptCache: (Long, String) = (0L, "")

  /** Load user design prompt from disk (cached by mtime). */
  private def loadDesignPrompt(): String =
    try
      val mtime = java.nio.file.Files.getLastModifiedTime(designPromptPath).toMillis
      if mtime != designPromptCache._1 then
        val content =
          new String(java.nio.file.Files.readAllBytes(designPromptPath), java.nio.charset.StandardCharsets.UTF_8)
        designPromptCache = (mtime, content)
        content
      else designPromptCache._2
    catch case _: Exception => designPromptCache._2

  /** Base description without user design prompt. */
  private val baseDescription =
    """Renders an interactive visual explanation in the chat — use this when a diagram, animation, or spatial layout conveys the idea better than paragraphs of text.

This tool is for "a picture is worth a thousand words" scenarios. If the content works equally well as Markdown text, do NOT use this tool.

Recommended use cases:
- Complex concept visualization — physical processes (e.g. how a heat pump works, TCP handshake), algorithm flows, data transformations, system architectures
- Design demos — UI mockups, layout previews, before/after comparisons, interactive prototypes
- Animated explanations — CSS-animated diagrams showing state changes over time, data flow, or causal chains
- Spatial relationships — anything where the position/size/color of elements carries meaning (hierarchy, dependency, timeline, distribution)
- Side-by-side comparisons — showing two states, versions, or alternatives visually

CRITICAL: This tool must NOT be used for text-heavy content. If the card's primary information carrier is text (paragraphs, lists, explanations), use Markdown instead. Cards should be dominated by visual elements — shapes, lines, colors, spatial arrangement — with text kept to minimal annotations and labels.

## How to generate plots and charts

For data plots, scientific figures, or any quantitative visualization (charts, graphs, histograms, scatter plots, etc.), you MUST use a professional plotting tool (matplotlib, ROOT, gnuplot, etc.) to produce the image, then embed it. Do NOT hand-draw SVG paths for data visualization.

Workflow:
1. Use Bash to run Python/matplotlib (or ROOT/gnuplot) to create the plot
2. Save the output to a local file (e.g. /tmp/plot.png)
3. Use this Card tool with `<img src="/tmp/plot.png">` to embed it

Hand-written SVG is ONLY appropriate for simple diagrams, flowcharts, or schematic illustrations where precision data is not needed.

## Image sizing rules (IMPORTANT — follow these strictly)

Embedded images (plots, photos) are often too small by default. You MUST:
- Wrap every `<img>` in a container with explicit width: `<div style="width:100%;max-width:800px;margin:0 auto">`
- For plots and charts, the img tag should use: `style="width:100%;height:auto;display:block"`
- For scientific figures, add padding around the image: wrap in `<div style="padding:16px;background:var(--color-surface)">`
- NEVER use fixed pixel widths on images (e.g. width="400px") — they break on different screen sizes
- A good default structure for plot embedding:
  `<div style="padding:16px"><img src="/tmp/plot.png" style="width:100%;max-width:800px;height:auto;display:block;margin:0 auto"></div>`

Typography rules (IMPORTANT — follow these strictly):
- Body text: minimum 15px. Use 15-16px for readable content.
- Labels/annotations on diagrams: minimum 14px.
- Headings: 18-22px.
- NEVER use font-size below 14px for any visible text.
- Line height: 1.5 for body text, 1.2 for headings.

Color rules (IMPORTANT — text must be readable in BOTH light and dark mode):
- ALWAYS use CSS variables for text/fill colors: var(--color-text), var(--color-primary), var(--color-surface), etc.
- For SVG <text> on colored backgrounds: use style="fill:var(--color-text)" or fill="white" (if background is dark).
- NEVER hardcode gray colors like #999, #666, #ccc, #aaa — they are invisible in one of the themes.
- NEVER use opacity below 0.85 on text — low-opacity text is unreadable, especially in dark mode.
- For secondary/muted text, use var(--color-text) at full opacity. If you need visual hierarchy, use font-size or font-weight, NOT reduced opacity or gray color.

Parameters:
- html (string, required): HTML with inline CSS. Supports dark mode via var(--color-*) CSS variables. JavaScript does NOT execute (sandboxed iframe), but CSS animations work.
- title (string, optional): Short title above the card.

Example (embedding a plot):
{
  "html": "<div style=\"padding:16px\"><img src=\"/tmp/plot.png\" style=\"width:100%;max-width:800px;height:auto;display:block;margin:0 auto\"></div>",
  "title": "Bethe-Bloch Curve"
}

Example (SVG diagram):
{
  "html": "<div style=\"font-family:sans-serif;padding:16px\"><svg viewBox=\"0 0 400 200\" style=\"width:100%\"><rect x=\"10\" y=\"60\" width=\"80\" height=\"40\" rx=\"6\" fill=\"var(--color-primary)\"/><text x=\"50\" y=\"85\" text-anchor=\"middle\" fill=\"white\" font-size=\"16\">Client</text><line x1=\"90\" y1=\"80\" x2=\"180\" y2=\"80\" stroke=\"var(--color-text)\" stroke-width=\"2\" stroke-dasharray=\"5,3\"/><text x=\"135\" y=\"72\" text-anchor=\"middle\" fill=\"var(--color-text)\" font-size=\"14\">SYN</text><rect x=\"180\" y=\"60\" width=\"80\" height=\"40\" rx=\"6\" fill=\"var(--color-primary)\"/><text x=\"220\" y=\"85\" text-anchor=\"middle\" fill=\"white\" font-size=\"16\">Server</text></svg><p style=\"margin:8px 0 0;font-size:15px;color:var(--color-text);text-align:center\">TCP handshake: Client → SYN → Server</p></div>",
  "title": "TCP 握手示意"
}"""

  /** Dynamic description: base tool description + user design prompt (if present). */
  def description: String =
    val prompt = loadDesignPrompt()
    if prompt.nonEmpty then s"$baseDescription\n\n$prompt"
    else baseDescription

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
