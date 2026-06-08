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

  /**
   * Allowed extensions for /api/nf-file proxy — prevents reading arbitrary files via src=.
   *  Includes media, web assets, data files, and 3D model formats for visualization.
   */
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
    "pdf",
    // web assets (scripts, styles, data)
    "js",
    "mjs",
    "css",
    "json",
    "wasm",
    // 3D models
    "obj",
    "stl",
    "gltf",
    "glb"
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

  val name = "Card"

  /** Path to user-editable card design prompt. */
  private val designPromptPath = java.nio.file.Paths.get(sys.props("user.home"), ".nebflow", "card-design-prompt.md")

  /** Cached design prompt (reloaded on each access via mtime check). */
  @volatile private var designPromptCache: (Long, String) = (0L, "")

  /** Default design guidelines — written to disk on first access if file doesn't exist. */
  private val defaultDesignPrompt: String =
    """## Card Visual Design Guidelines

Follow these strictly. They override any conflicting defaults.

### Purpose

Visual-first, text-minimal. Cards are for diagrams, animations, transitions, spatial layouts — not paragraphs. If the content works as Markdown, don't use Card.

### Color: always use CSS variables

**Never hardcode hex colors.** Always use `var(--color-text)` for body text, `var(--color-bg)`/`var(--color-surface)` for backgrounds. These guarantee maximum contrast in both light and dark mode. Use `var(--color-primary/success/error/warning)` only for status indicators. `var(--color-text-muted)` is for captions only — too low contrast for body text.

### Accuracy

Accuracy and correctness come first. If a visualization involves data, numbers, or precise relationships, use a professional tool (matplotlib, gnuplot, ROOT, etc.) to generate it — then embed the result as an image. Hand-drawn SVG is for layout and simple diagrams where precision is not critical.

### Visual defaults

- **No emoji.** Use typography and spacing for visual interest.
- **Rounded corners:** cards 16px, buttons 10px, inputs 8px, badges 9999px.
- **Font:** `-apple-system, BlinkMacSystemFont, "SF Pro Display", "SF Pro Text", "Helvetica Neue", sans-serif`
- Body 13-14px / 400, headings 16-20px / 600, tight letter-spacing on headings.
- Animations only if they aid understanding (200-400ms, ease-out for entrance). Respect prefers-reduced-motion.

### Embedding external content

HTML must be self-contained (all styles/tags inline, no external CSS/JS). Local file paths in src/href are automatically served by the backend."""

  /**
   * Load user design prompt from disk (cached by mtime).
   *  Auto-creates with defaults on first access if the file doesn't exist.
   */
  private def loadDesignPrompt(): String =
    try
      if !java.nio.file.Files.exists(designPromptPath) then
        java.nio.file.Files.createDirectories(designPromptPath.getParent)
        java.nio.file.Files
          .write(designPromptPath, defaultDesignPrompt.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        designPromptCache = (java.nio.file.Files.getLastModifiedTime(designPromptPath).toMillis, defaultDesignPrompt)
        defaultDesignPrompt
      else
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
    """Renders an interactive HTML card embedded in the chat.

Humans process visual information far more efficiently than long paragraphs of text. Use this tool to optimize your output — present relationships, structure, and data visually when doing so makes your answer easier to understand.

Accuracy and correctness come first. **Always generate images with professional tools first, then embed them with Card.** Card is for presenting results — not for drawing. Hand-written SVG should only be used for simple conceptual diagrams where precision does not matter. For any data, numbers, or precise structures, always use tools.

## Workflow: generate with tools, embed with Card

1. Use Bash to run a tool (matplotlib, schemdraw, graphviz, etc.) → outputs an image file
2. Use Card to embed that file via `<img src="/absolute/path/to/file.png">`

## Recommended tools by scenario

### Data visualization
- **Plots, charts, heatmaps**: matplotlib, gnuplot, ROOT, plotly → save PNG/SVG
- **Sankey diagrams** (energy/material flow): plotly → save PNG
- **Gantt charts / timelines**: matplotlib, plotly, mermaid → save PNG
- **Radar / spider charts**: matplotlib, plotly → save PNG
- **Treemaps**: plotly, squarify (Python) → save PNG

### Electronics & signals
- **Circuit schematics**: schemdraw (Python) → save PNG/SVG
- **PCB layouts**: KiCad/Eagle export, or matplotlib patches → embed
- **PCB cross-sections / layer stack-ups**: matplotlib patches → save PNG
- **Timing diagrams**: wavedrom (Python/CLI) → save SVG
- **Frequency spectra / Bode plots**: matplotlib + scipy → save PNG
- **Eye diagrams**: matplotlib → save PNG
- **Smith charts**: matplotlib (smithplot) → save PNG

### Software & system architecture
- **Block diagrams / signal flow**: graphviz (dot), mermaid-cli → save PNG/SVG
- **Flowcharts, org charts**: graphviz, mermaid → save PNG/SVG
- **UML class / sequence / state diagrams**: plantuml, mermaid → save PNG/SVG
- **ER diagrams** (database schemas): plantuml, erd (CLI), graphviz → save PNG
- **Network topologies**: graphviz, networkx + matplotlib → save PNG

### Science & engineering
- **Detector / material cross-sections**: matplotlib patches → save PNG
- **3D structures / assemblies**: OpenSCAD CLI, matplotlib 3D → save PNG/STL
- **Vector fields**: matplotlib (quiver), plotly → save PNG
- **3D surfaces / contour plots**: matplotlib, plotly → save PNG
- **Polar / antenna radiation patterns**: matplotlib (polar) → save PNG
- **Mathematical functions / geometry**: matplotlib, manim CLI → save PNG/MP4

### Chemistry & materials
- **Molecular structures**: rdkit (Python), OpenBabel CLI → save PNG/SVG/3D
- **Crystal structures / unit cells**: pymatgen, ASE (Python) → save PNG/3D

### Geography & spatial
- **Maps / spatial distributions**: folium (→ save HTML → embed), cartopy + matplotlib → save PNG

## What it can do

- **Diagrams & charts**: flowcharts, architecture diagrams, org charts, bar/pie/line charts, heatmaps, network topologies
- **Animations & simulations**: physics simulations, algorithm step-throughs, state machine transitions, progress indicators
- **Spatial layouts**: UI mockups, floor plans, image galleries, comparison grids, annotated screenshots
- **Interactive widgets**: clickable prototypes, drag-and-drop exercises, parameter sliders, input forms, quizzes
- **Embedded media**: local images, generated plots, videos, 3D models (OBJ/STL/GLTF)
- **Engineering drawings**: circuit schematics, PCB layouts, PCB layer stack-ups, block diagrams, signal flow graphs, detector geometries, 2D/3D structural cross-sections, mechanical assemblies

## Parameters

- html (string, required): HTML with CSS and JS. Dark mode via var(--color-*).
- title (string, optional): title above card.

Example (embed tool-generated image):
{"html":"<div style=\"padding:16px\"><img src=\"/tmp/plot.png\" style=\"width:100%;height:auto;display:block;margin:0 auto\"></div>","title":"My Plot"}

Example (SVG diagram):
{"html":"<div style=\"font-family:sans-serif;padding:16px\"><svg viewBox=\"0 0 600 200\" style=\"width:100%\"><rect x=\"10\" y=\"60\" width=\"120\" height=\"60\" rx=\"8\" fill=\"var(--color-primary)\"/><text x=\"70\" y=\"96\" text-anchor=\"middle\" fill=\"white\" font-size=\"16\">Client</text><rect x=\"180\" y=\"60\" width=\"120\" height=\"60\" rx=\"8\" fill=\"var(--color-primary)\"/><text x=\"240\" y=\"96\" text-anchor=\"middle\" fill=\"white\" font-size=\"16\">Server</text></svg></div>","title":"TCP"}

Example (interactive 3D with Three.js):
{"html":"<div style=\"padding:0\"><script src=\"https://cdn.jsdelivr.net/npm/three@latest/build/three.min.js\"></script><canvas id=\"c\" style=\"width:100%;height:400px;display:block\"></canvas><script>const s=new THREE.Scene();const c=document.getElementById('c');const r=new THREE.WebGLRenderer({canvas:c,antialias:true});r.setSize(c.clientWidth,400);const cam=new THREE.PerspectiveCamera(75,c.clientWidth/400,0.1,1000);cam.position.z=3;s.add(new THREE.Mesh(new THREE.SphereGeometry(1,32,32),new THREE.MeshNormalMaterial()));function f(){requestAnimationFrame(f);r.render(s,cam)}f()</script></div>","title":"3D Sphere"}"""

  /** Dynamic description: base tool description + user design prompt (always present after auto-init). */
  def description: String =
    s"$baseDescription\n\n${loadDesignPrompt()}"

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
    extractHtml(input) match
      case Some(rawHtml) =>
        val title = extractTitle(input)
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
        val debug = input.toMap
          .map { (k, v) =>
            val preview = v.asString.getOrElse(v.noSpaces).take(80)
            s"$k: $preview"
          }
          .mkString(", ")
        IO.pure(
          Left(
            ToolError(
              s"Card tool requires non-empty `html` parameter. Your input: {$debug}. Example: {\"html\": \"<div>content</div>\", \"title\": \"optional\"}"
            )
          )
        )

  /**
   * Try to extract valid HTML from the input with multi-level fallback:
   *   1. html field is a non-empty string → use directly
   *   2. html field is a non-string type → stringify and check if it looks like HTML
   *   3. html is empty/missing, but title contains HTML → use title as html
   */
  private def extractHtml(input: JsonObject): Option[String] =
    // Level 1: html field is a non-empty string
    input("html")
      .flatMap(_.asString)
      .filter(_.nonEmpty)
      // Level 2: html is non-string — try to stringify
      .orElse(
        input("html").filterNot(_.isString).map(_.noSpaces).filter(isHtmlLike)
      )
      // Level 3: html missing/empty but title looks like HTML
      .orElse(
        input("title").flatMap(_.asString).filter(isHtmlLike)
      )

  /** Extract title, accounting for the case where title was repurposed as html. */
  private def extractTitle(input: JsonObject): String =
    val htmlDirect = input("html").flatMap(_.asString).filter(_.nonEmpty)
    val titleValue = input("title").flatMap(_.asString).getOrElse("")
    // If html was empty/missing and title was repurposed as html, clear title
    if htmlDirect.isEmpty && isHtmlLike(titleValue) then ""
    else titleValue

  /** Rough check: does the string look like HTML (starts with < and contains >)? */
  private def isHtmlLike(s: String): Boolean =
    val t = s.trim
    t.startsWith("<") && t.contains(">")

  def summarize(input: JsonObject): String =
    val title = input("title").flatMap(_.asString).getOrElse("Card")
    s"Card\n  ($title)"

  def summarizeResult(input: JsonObject, result: String): String =
    val title = input("title").flatMap(_.asString).getOrElse("Card")
    s"$title rendered"

end CardTool
