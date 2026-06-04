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

  /** Card LLM content is always a compact summary — exempt from guard. */
  override val maxResultSizeChars: Int = Int.MaxValue

  private val logger = nebflow.core.NebflowLogger.forName("nebflow.tools.card")

  /** Max file size for HTTP-served files (200 MB). */
  private val MaxFileSize = 200 * 1024 * 1024

  /** Allowed extensions for /api/nf-file proxy — prevents reading arbitrary files via src=.
   *  Includes media, web assets, data files, and 3D model formats for visualization. */
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
    "pdf",
    // web assets (scripts, styles, data)
    "js", "mjs", "css", "json", "wasm",
    // 3D models
    "obj", "stl", "gltf", "glb"
  )

  /** Regex matching src= attributes with both single and double quotes. */
  private val SrcAttrRegex = """(?i)src\s*=\s*["']([^"']+)["']""".r

  /** Regex matching href= attributes — for <link> stylesheets and other references. */
  private val HrefAttrRegex = """(?i)href\s*=\s*["']([^"']+)["']""".r

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
   * Scan HTML for src= and href= attributes pointing to local files,
   * and replace paths with /api/nf-file?path=... URLs.
   * Non-whitelisted files are silently skipped (left as-is).
   */
  private def embedLocalFiles(html: String): String =
    val srcReplacements = SrcAttrRegex
      .findAllMatchIn(html)
      .flatMap { m =>
        val value = m.group(1)
        tryEmbed(value).map(url => (m.start(1), m.end(1), url))
      }
    val hrefReplacements = HrefAttrRegex
      .findAllMatchIn(html)
      .flatMap { m =>
        val value = m.group(1)
        tryEmbed(value).map(url => (m.start(1), m.end(1), url))
      }
    val replacements = (srcReplacements ++ hrefReplacements).toSeq

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
      if result != html then logger.debug(s"Embedded ${replacements.size} local file(s) via /api/nf-file")
      result
    end if
  end embedLocalFiles

  /**
   * Fix width:100% on img/svg inside fit-content card.
   * fit-content + width:100% creates a deadlock (element says "fill parent",
   * parent says "shrink to content" → collapses to zero).
   * For img: just strip width:100% (natural size + max-width:100% handles overflow).
   * For svg: replace width:100% with viewBox width if available, otherwise strip.
   */
  private def fixFitContent(html: String): String =
    var changed = false
    val result = new StringBuilder(html.length)

    // Process img tags: strip width:100%
    val imgRegex = """(?i)(<img\b[^>]*\bstyle=["'][^"']*)width:\s*100%\s*;?([^"']*["'])""".r
    val fixed1 = imgRegex.replaceAllIn(html, m =>
      changed = true
      s"${m.group(1)}${m.group(2)}"
    )

    // Process svg tags: replace width:100% with viewBox width
    // viewBox format: "minX minY width height" — we need the 3rd number
    val svgWithVbRegex = """(?i)(<svg\b[^>]*viewBox=["']\S+\s+\S+\s+(\d+(?:\.\d+)?)\s+\S+["'][^>]*\bstyle=["'][^"']*)width:\s*100%\s*;?([^"']*["'])""".r
    val fixed2 = svgWithVbRegex.replaceAllIn(fixed1, m =>
      changed = true
      val vbWidth = m.group(2)
      s"${m.group(1)}width:${vbWidth}px;${m.group(3)}"
    )

    // Process svg tags without viewBox: strip width:100% (will use default 300px)
    val svgNoVbRegex = """(?i)(<svg\b(?![^>]*viewBox=)[^>]*\bstyle=["'][^"']*)width:\s*100%\s*;?([^"']*["'])""".r
    val fixed3 = svgNoVbRegex.replaceAllIn(fixed2, m =>
      changed = true
      s"${m.group(1)}${m.group(2)}"
    )

    if changed then logger.debug("Fixed width:100% deadlock in card HTML")
    fixed3
  end fixFitContent

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
    """Renders HTML in a sandboxed iframe. For diagrams, animations, spatial layouts — not text.
If Markdown suffices, do NOT use this tool.

Accuracy and correctness come first. If a visualization involves data, numbers, or precise relationships, use a professional tool (matplotlib, gnuplot, ROOT, etc.) to generate it — then embed the result as an image. Hand-drawn SVG is for layout and simple diagrams where precision is not critical.

To embed generated images: save to a local path (e.g. /tmp/xxx.png), then use `<img src="/tmp/xxx.png">`.

## Parameters

- html (string, required): HTML with CSS and JS. Dark mode via var(--color-*).
- title (string, optional): title above card.

Example (embedded image):
{"html":"<div style=\"padding:16px\"><img src=\"/tmp/plot.png\" style=\"width:100%;height:auto;display:block;margin:0 auto\"></div>","title":"My Plot"}

Example (SVG diagram):
{"html":"<div style=\"font-family:sans-serif;padding:16px\"><svg viewBox=\"0 0 600 200\" style=\"width:100%\"><rect x=\"10\" y=\"60\" width=\"120\" height=\"60\" rx=\"8\" fill=\"var(--color-primary)\"/><text x=\"70\" y=\"96\" text-anchor=\"middle\" fill=\"white\" font-size=\"16\">Client</text><rect x=\"180\" y=\"60\" width=\"120\" height=\"60\" rx=\"8\" fill=\"var(--color-primary)\"/><text x=\"240\" y=\"96\" text-anchor=\"middle\" fill=\"white\" font-size=\"16\">Server</text></svg></div>","title":"TCP"}

Example (interactive 3D with Three.js):
{"html":"<div style=\"padding:0\"><script src=\"https://cdn.jsdelivr.net/npm/three@latest/build/three.min.js\"></script><canvas id=\"c\" style=\"width:100%;height:400px;display:block\"></canvas><script>const s=new THREE.Scene();const c=document.getElementById('c');const r=new THREE.WebGLRenderer({canvas:c,antialias:true});r.setSize(c.clientWidth,400);const cam=new THREE.PerspectiveCamera(75,c.clientWidth/400,0.1,1000);cam.position.z=3;s.add(new THREE.Mesh(new THREE.SphereGeometry(1,32,32),new THREE.MeshNormalMaterial()));function f(){requestAnimationFrame(f);r.render(s,cam)}f()</script></div>","title":"3D Sphere"}"""

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
          val html = fixFitContent(embedLocalFiles(rawHtml))
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
