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

### Color: always use CSS variables

**Never hardcode hex colors.** Always use `var(--color-text)` for body text, `var(--color-bg)`/`var(--color-surface)` for backgrounds. These guarantee maximum contrast in both light and dark mode. Use `var(--color-primary/success/error/warning)` only for status indicators. `var(--color-text-muted)` is for captions only — too low contrast for body text.

### Generated images: two SVG embedding strategies

**Never embed raster images (PNG/JPG) via `<img>`** — they have hardcoded colors that cannot adapt to dark mode. Always generate SVG output instead.

There are two ways to embed SVG, each with trade-offs:

#### Strategy A: `<img src>` — simpler, more reliable (default choice)

```
dot -Tsvg -o /tmp/output.svg input.dot
```
```html
<img src="/tmp/output.svg" style="width:100%;height:auto;display:block" alt="Diagram"/>
```

- Browser handles SVG scaling natively — dimensions always correct.
- SVG is self-contained: graphviz/matplotlib already set correct text colors per node (dark bg = white text, light bg = dark text).
- Works reliably in sandboxed iframe / shadow DOM environments.
- Does NOT support dark-mode CSS overrides (SVG is rendered as an image, CSS variables don't penetrate). If dark mode is important, use Strategy B.

#### Strategy B: inline SVG — enables dark-mode CSS overrides

1. Generate output as **SVG format**
2. Read the SVG file, strip `<?xml?>` and `<!DOCTYPE>` declarations
3. Replace `width="..."` attribute with `width="100%"`, remove `height` attribute (let viewBox control aspect ratio)
4. **Paste the SVG directly into the HTML** — do NOT wrap in an outer `<svg>`
5. Add a `<style>` block with CSS overrides

**Warning:** Inline SVG may render at incorrect sizes in some sandboxed environments. If the card appears too small, switch to Strategy A.

**CSS override template — graphviz diagrams:**

```html
<div class="gv-diagram">
  <svg width="100%" viewBox="...">...</svg>
</div>
<style>
.gv-diagram svg { width: 100%; height: auto; display: block; }
/* Hide graphviz white background */
.gv-diagram svg > g > polygon { fill: transparent !important; }
/* Edge labels follow theme — do NOT override .node text, graphviz sets correct contrast per node */
.gv-diagram .edge text { fill: var(--color-text-muted) !important; }
/* Edges: no fill, muted stroke */
.gv-diagram .edge path { fill: none !important; stroke: var(--color-text-muted) !important; }
/* Arrowheads */
.gv-diagram .edge polygon { fill: var(--color-text-muted) !important; stroke: none !important; }
</style>
```

**CSS override template — matplotlib charts:**

```html
<div class="mpl-chart">
  <svg>...</svg>
</div>
<style>
.mpl-chart svg { width: 100%; height: auto; }
/* All text follows theme */
.mpl-chart text { fill: var(--color-text) !important; }
/* Override black axis lines, ticks, spines — data lines keep original colors */
.mpl-chart path[style*="#000000"] { stroke: var(--color-text-muted) !important; }
.mpl-chart use[style*="#000000"] { stroke: var(--color-text-muted) !important; }
/* Legend/patch borders */
.mpl-chart path[style*="fill: #ffffff"], .mpl-chart path[style*="fill: white"] {
  fill: var(--color-surface) !important;
}
</style>
```

**Key principle:** CSS `!important` in a `<style>` block overrides both SVG presentation attributes and inline `style="..."` on SVG elements — so the theme variables always win.

**When raster (PNG/JPG) is unavoidable** (photos, screenshots, complex renders): wrap in a container with `var(--color-bg)` background and add `border-radius`. Do NOT apply CSS filters (invert etc.) — they distort colors unpredictably.

### Visual defaults

- **No emoji.** Use typography and spacing for visual interest.
- **Rounded corners:** cards 16px, buttons 10px, inputs 8px, badges 9999px.
- **Font:** `-apple-system, BlinkMacSystemFont, "SF Pro Display", "SF Pro Text", "Helvetica Neue", sans-serif`
- Body 13-14px / 400, headings 16-20px / 600, tight letter-spacing on headings.
- Animations only if they aid understanding (200-400ms, ease-out for entrance). Respect prefers-reduced-motion.

### Embedding external content

HTML must be self-contained (all styles/tags inline, no external CSS/JS). Local file paths in src/href are automatically served by the backend. **You MUST use absolute paths** (e.g. `/Users/you/project/plot.png` or `~/project/plot.png`). Relative paths will NOT be resolved."""

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

## Frontend / Design Change Protocol — MANDATORY

When a task involves **frontend or visual design changes** (CSS, UI layout, styling, color schemes, component design, icon changes), you MUST use Card to render a visual preview of the proposed result BEFORE writing the actual code changes. The workflow is:

1. Understand the design change requested
2. Use Card to create a visual mockup or preview showing the intended result
3. Wait for the user to confirm the design direction
4. Only then proceed with implementation

This applies to ALL UI changes, no matter how small (color tweaks, spacing adjustments, new buttons, layout changes, etc.). Never write frontend code without first showing the user what it will look like via Card.

## Why use cards

Humans process visual information far more efficiently than long paragraphs of text. A well-chosen diagram conveys in a glance what would take paragraphs to explain. Use cards to present relationships, structure, and data visually — when a visual makes your answer clearer than words alone.

Card is for **presenting** results, not for drawing them. Always generate images with professional tools first, then embed with Card.

## Use cases and counter-examples

### Use Card when:
- **Spatial structure**: architecture diagrams, flowcharts, org charts, network topologies
- **Data visualization**: charts, plots, heatmaps, spectra, histograms
- **Interactive elements**: clickable prototypes, parameter sliders, step-through animations
- **Side-by-side comparison**: image galleries, before/after grids, annotated screenshots
- **Embedding generated media**: plots from matplotlib, diagrams from graphviz, 3D models

### Common mistakes — the right way vs the wrong way:
- **Charts, curves, spectra**: do NOT draw with ASCII art or hand-write SVG. Use matplotlib/gnuplot → output SVG → inline with CSS override.
- **Flowcharts, architecture diagrams**: do NOT approximate with text boxes and arrows. Use graphviz/mermaid → output SVG → inline with CSS override.
- **Circuit schematics, timing diagrams**: do NOT hand-draw with SVG `<path>`. Use schemdraw/wavedrom → output SVG → inline with CSS override.
- **Any content involving data, proportions, or precise shapes**: do NOT guess coordinates in SVG. Use a professional tool — always.

**Important:** Never embed diagrams/charts as PNG/JPG via `<img>` — they have hardcoded colors that break dark mode. Generate SVG output instead. You can embed SVG two ways: `<img src="/tmp/output.svg">` (simpler, more reliable sizing, no dark-mode CSS) or inline the SVG content (enables dark-mode CSS overrides). See the design guidelines below for details and CSS templates.

## Professional tool correspondence table

| Scenario | Recommended tool |
|----------|-----------------|
| **Charts & plots** (line, bar, scatter, heatmap) | matplotlib, gnuplot, ROOT, plotly |
| **Scientific plots** (contour, vector field, polar, 3D surface) | matplotlib, plotly |
| **Flowcharts & block diagrams** | graphviz (dot), mermaid-cli |
| **Architecture diagrams & network topologies** | graphviz, networkx + matplotlib |
| **UML (class / sequence / state)** | plantuml, mermaid |
| **Timing diagrams** | wavedrom |
| **Circuit schematics** | schemdraw (Python) |
| **PCB layouts & cross-sections** | KiCad/Eagle export, matplotlib patches |
| **Frequency spectra / Bode plots / eye diagrams** | matplotlib + scipy |
| **Smith charts** | matplotlib (smithplot) |
| **Molecular structures** | rdkit, OpenBabel |
| **Crystal structures** | pymatgen, ASE |
| **Maps & spatial distributions** | folium, cartopy + matplotlib |
| **3D models** | OpenSCAD CLI, matplotlib 3D |
| **Gantt charts / timelines** | matplotlib, plotly, mermaid |
| **Sankey diagrams / treemaps / radar charts** | plotly, squarify |

## Workflow

1. Use Bash to run a tool (matplotlib, graphviz, etc.) → **output as SVG format**
2. Embed the SVG in Card — two options:
   - **Simple:** `<img src="/tmp/output.svg" style="width:100%;height:auto">` (recommended default)
   - **Dark-mode CSS:** Read the SVG file, strip `<?xml?>`/`<!DOCTYPE>`, replace `width` with `width="100%"`, inline the SVG content, add `<style>` CSS overrides (see templates below)

## Parameters

- html (string, required): HTML with CSS and JS. Dark mode via var(--color-*).
- title (string, optional): title above card.

Note: When referencing local files (images, videos, etc.) in `src` or `href` attributes, you MUST use absolute paths (e.g. `/Users/you/project/plot.png` or `~/project/plot.png`). The backend will automatically proxy these files. Relative paths will NOT work.

Example (graphviz SVG via img — recommended default):
{"html":"<img src=\"/tmp/output.svg\" style=\"width:100%;height:auto;display:block\" alt=\"Architecture\"/>","title":"Architecture"}

Example (graphviz SVG inline with dark mode CSS):
{"html":"<div class=\"gv-diagram\"><svg width=\"100%\" viewBox=\"0 0 200 100\">...</svg></div><style>.gv-diagram svg{width:100%;height:auto;display:block}.gv-diagram .edge text{fill:var(--color-text-muted)!important}.gv-diagram .edge path{fill:none!important;stroke:var(--color-text-muted)!important}.gv-diagram .edge polygon{fill:var(--color-text-muted)!important;stroke:none!important}</style>","title":"Architecture"}

Example (matplotlib SVG inline):
{"html":"<div class=\"mpl-chart\"><svg viewBox=\"0 0 432 216\">...</svg></div><style>.mpl-chart svg{width:100%;height:auto}.mpl-chart text{fill:var(--color-text)!important}.mpl-chart path[style*=\"#000000\"]{stroke:var(--color-text-muted)!important}</style>","title":"My Plot"}

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
