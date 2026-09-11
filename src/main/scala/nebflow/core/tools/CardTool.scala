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

  /** Card LLM content is always a compact summary — exempt from guard.
    * 注意（2026-09-05 恢复批核对）：2026-09-01 #38 起 ToolResultGuard 对
    * ∞ 声明不再豁免——math.min(declaredMax, Defaults.DefaultMaxResultSizeChars)
    * 将本声明 clamp 到系统级 50K 上限。本行保留仅表达"Card 结果设计上紧凑"
    * 的意图，实际由 guard 统一兜底（大 card 持久化+preview，frontendContent
    * 全文保留）。 */
  override val maxResultSizeChars: Int = Int.MaxValue

  private val logger = nebflow.core.NebflowLogger.forName("nebflow.tools.card")

  /** The shared local-reference policy (failure enum, file probe, app-route
    *  exemption, warning/counter JSON shapes) lives in `FileRefs` — the same
    *  one Pop uses, so the two tools cannot drift apart. Card contributes only
    *  the resolution policy below (a Card has no containing directory). */
  import FileRefs.*

  /** Regex matching src= attributes with both single and double quotes. */
  private val SrcAttrRegex = """(?i)src\s*=\s*["']([^"']+)["']""".r

  /** Regex matching href= attributes — for <link> stylesheets and other references. */
  private val HrefAttrRegex = """(?i)href\s*=\s*["']([^"']+)["']""".r

  /** `srcset=` attribute value.
    *
    *  2026-09-11 (toolfail batch): `srcset` was outside the scan face, so a
    *  responsive `<img srcset="…">` whose candidates all failed stayed silent.
    *  The lookbehind keeps `data-srcset=` out — the two legacy regexes above
    *  carry no such boundary (pre-existing over-match, registered as a
    *  follow-up rather than widened here). */
  private val SrcsetAttrRegex = """(?i)(?<![-\w])srcset\s*=\s*["']([^"']+)["']""".r

  /** A CSS `url(...)` token, quoted or bare. One pattern covers `<style>`
    *  blocks, inline `style` attributes, `@import url(…)`,
    *  `image-set(url(…))` and `@font-face src:` — all reduce to this token. */
  private val CssUrlRegex = """(?i)url\(\s*(['"]?)([^'")]+)\1\s*\)""".r

  /** A bare `@import "…"` — the `url(…)` form is already covered above. */
  private val ImportBareRegex = """(?i)@import\s+(['"])([^'"]+)\1""".r

  /** One card's local-file pass: rewritten HTML plus every rejected reference
    *  and the count of suppressed app-route exemptions. */
  private[tools] case class EmbedOutcome(html: String, proxied: Int, rejects: List[RejectedRef], exempt: Int)

  /**
   * Classify one reference value (an `src`/`href` attribute, a `srcset`
   * candidate URL, a CSS `url(...)` token or a bare `@import`): proxy it,
   * ignore it, exempt it, or reject it with a reason.
   *
   * Only `~`/`/` anchored references are ever proxied. Relative references stay
   * unproxied and WARN instead of silently doing nothing — that also keeps
   * `?path=images/logo.png` (resolved by the endpoint against the *server's*
   * working directory) out of the URL space, which the docs always promised.
   * A failing value that is simultaneously a gateway route (`/js/…`) is
   * exempted instead of reported — see `FileRefs.applyAppRouteExemption`.
   *
   * The file-level probe itself is shared with Pop (`FileRefs.probeFile`);
   * only the resolution policy in this wrapper is Card-specific.
   */
  private def decideRef(rawValue: String): RefDecision =
    val value = rawValue.trim
    if !isLocalFilePath(value) || !looksLikeFilePath(value) then RefDecision.Ignore
    else
      val verdict =
        if !value.startsWith("~") && !value.startsWith("/") then
          unresolvable(
            value,
            "relative references are never resolved — use an absolute path (`/Users/you/…`) or `~/…`"
          )
        else if hasTemplatePlaceholder(value) then
          unresolvable(
            value,
            "the reference contains a template placeholder — resolve it to a real path before emitting the card"
          )
        else
          resolvePath(value) match
            case None    => unresolvable(value, "the reference is not a usable filesystem path")
            case Some(p) => probeFile(value, p)
      val decision = applyAppRouteExemption(value, verdict)
      decision match
        case RefDecision.Exempt(route) => logger.debug(s"Card: exempted app-route reference '$value' (route $route)")
        case _                         => ()
      decision

  /**
   * Split a `srcset` value into its image candidates and locate each URL token
   * inside the original string, so descriptors (`2x`, `640w`) survive the
   * rewrite. Grammar: `URL [descriptor]` separated by commas (HTML spec).
   *
   * Registered boundary: an unescaped comma is legal inside a `data:` URL — the
   * one place a comma split is ambiguous — so a `srcset` value containing
   * `data:` is skipped whole (never rewritten, never warned) instead of
   * guessed at.
   */
  private def srcsetCandidates(m: scala.util.matching.Regex.Match): List[(Int, Int, RefDecision)] =
    val raw = m.group(1)
    if raw.toLowerCase.contains("data:") then Nil
    else
      val base = m.start(1)
      val out = scala.collection.mutable.ListBuffer.empty[(Int, Int, RefDecision)]
      var cursor = 0
      raw.split(",", -1).foreach { candidate =>
        val lead = candidate.indexWhere(ch => !ch.isWhitespace)
        if lead >= 0 then
          val rest = candidate.substring(lead)
          val urlLen = rest.indexWhere(ch => ch.isWhitespace) match
            case -1 => rest.length
            case i  => i
          if urlLen > 0 then
            val start = base + cursor + lead
            out += ((start, start + urlLen, decideRef(rest.substring(0, urlLen))))
        cursor += candidate.length + 1
      }
      out.toList

  /**
   * Drop (never merge) any span that an earlier accepted span already covers.
   *
   * Spans arrive in document order. Two scan patterns can genuinely overlap (a
   * `url(...)` nested inside an `src=` value that is itself proxyable), and
   * splicing overlapping ranges would either duplicate text or cut it in half —
   * so the later span is discarded and the earlier one wins.
   */
  private[tools] def nonOverlapping[A](spans: List[(Int, Int, A)]): List[(Int, Int, A)] =
    val out = scala.collection.mutable.ListBuffer.empty[(Int, Int, A)]
    var lastEnd = -1
    spans.foreach { span =>
      val (start, end, _) = span
      if start >= lastEnd then
        out += span
        lastEnd = end
    }
    out.toList

  /**
   * Scan HTML for local file references and replace the ones that can be
   * proxied with /api/nf-file?path=... URLs.
   *
   * Scan face — `src=`, `href=`, every `srcset` candidate URL, every CSS
   * `url(...)` token and a bare `@import "…"` (2026-09-11 toolfail batch
   * widened it from the first two; the forms that are deliberately still out
   * of face are registered in the batch report with their reasons).
   *
   * Rejected references keep their raw value (a broken reference must not take
   * the whole card down) but are returned in `EmbedOutcome.rejects` so the
   * caller can report them. Matches from all five patterns are merged in
   * document order — the original src-then-href concatenation spliced a
   * later-listed href before an earlier src and threw on such cards — and an
   * overlap guard drops (never merges) a range that an earlier accepted
   * replacement already covers, because splicing overlapping ranges would
   * corrupt the document.
   */
  private def embedLocalFiles(html: String): EmbedOutcome =
    val matches: List[(Int, Int, RefDecision)] =
      (SrcAttrRegex.findAllMatchIn(html).map(m => (m.start(1), m.end(1), decideRef(m.group(1)))) ++
        HrefAttrRegex.findAllMatchIn(html).map(m => (m.start(1), m.end(1), decideRef(m.group(1)))) ++
        SrcsetAttrRegex.findAllMatchIn(html).flatMap(srcsetCandidates) ++
        CssUrlRegex.findAllMatchIn(html).map(m => (m.start(2), m.end(2), decideRef(m.group(2)))) ++
        ImportBareRegex.findAllMatchIn(html).map(m => (m.start(2), m.end(2), decideRef(m.group(2))))).toList
        .sortBy(_._1)

    val rejects = matches.collect { case (_, _, RefDecision.Reject(rejected)) => rejected }
    val exempts = matches.count { case (_, _, RefDecision.Exempt(_)) => true; case _ => false }

    val replacements = nonOverlapping(
      matches.collect { case (start, end, RefDecision.Proxy(url)) => (start, end, url) }
    )

    val rewritten =
      if replacements.isEmpty then html
      else
        val sb = new StringBuilder(html.length + replacements.size * 128)
        var cursor = 0
        for (start, end, replacement) <- replacements do
          sb.append(html.substring(cursor, start))
          sb.append(replacement)
          cursor = end
        sb.append(html.substring(cursor, html.length))
        sb.toString

    if rewritten != html then logger.debug(s"Embedded ${replacements.size} local file(s) via /api/nf-file")
    EmbedOutcome(rewritten, replacements.size, rejects, exempts)
  end embedLocalFiles

  /** The sentinel prefix the frontend splits the JSON payload on (cardRegistry.js
    *  `^___\w+_HTML___`); nothing may be appended after the JSON. */
  private val CardSentinel = "___CARD_HTML___"

  /** One integer counter out of an already-built card result's `fileRefs`. */
  private def fileRefCount(result: String, field: String): Int =
    if !result.startsWith(CardSentinel) then 0
    else
      io.circe.parser
        .parse(result.substring(CardSentinel.length))
        .toOption
        .flatMap(_.asObject)
        .flatMap(_.apply("fileRefs"))
        .flatMap(_.asObject)
        .flatMap(_.apply(field))
        .flatMap(_.asNumber)
        .flatMap(_.toInt)
        .getOrElse(0)

  private def unresolvedFileRefs(result: String): Int = fileRefCount(result, "failed")

  /** App-route references the exemption suppressed — surfaced in the chat
    *  header whenever it is non-zero, so a suppressed reference is never
    *  invisible (see `FileRefs.applyAppRouteExemption`). */
  private def exemptFileRefs(result: String): Int = fileRefCount(result, "exempt")

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

HTML must be self-contained (all styles/tags inline, no external CSS/JS).

Local file paths in `src`/`href` are proxied by the backend to `/api/nf-file`, so **you MUST use absolute paths** — `/Users/you/project/plot.png`, `/tmp/output.svg`, or `~/.nebflow/projects/<name>/reports/plot.svg`. `~` expands to the user's home directory, and project workspaces live under `~/.nebflow/projects/<name>/` — write that full path, not `~/projects/<name>/…`. Relative paths are never resolved.

Every reference that could not be proxied is reported in this tool's result under `warnings` (`ref` → `resolvedPath` → `reason`: not-found / unresolvable / extension-not-allowed / size-exceeded / not-regular-file, plus `fileRefs` counts) and renders as a visible placeholder in the card instead of a silent blank box. Scanned: `src=`, `href=`, every `srcset` candidate, every CSS `url(...)`, a bare `@import "..."`. The app's own routes (`/js/`, `/css/`, `/assets/`, `/vendor/`, `/uploads/`, `/agents/`, `/voice-models/`, plus `/style.css` `/app.js` `/logo.svg` `/favicon.*`) are exempt — the app serves them, not the disk — and are counted in `fileRefs.exempt` instead of being reported. Read `warnings` and fix the references before finishing."""

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

Note: Local file paths in `src`/`href` are proxied by the backend to `/api/nf-file`, so **you MUST use absolute paths** — `/Users/you/project/plot.png`, `/tmp/output.svg`, or `~/.nebflow/projects/<name>/reports/plot.svg`. `~` expands to the user's home directory, and project workspaces live under `~/.nebflow/projects/<name>/` — write that full path, not `~/projects/<name>/…`. Relative paths are never resolved.

Every reference that could not be proxied is reported in this tool's result under `warnings` (`ref` → `resolvedPath` → `reason`: not-found / unresolvable / extension-not-allowed / size-exceeded / not-regular-file, plus `fileRefs` counts) and renders as a visible placeholder in the card instead of a silent blank box. Read `warnings` and fix the references before finishing.

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
          val outcome = embedLocalFiles(rawHtml)
          val distinct = distinctRejections(outcome.rejects)
          val listed = distinct.take(MaxListedWarnings)
          if distinct.nonEmpty then
            logger.warn(
              s"Card: ${outcome.rejects.size} local file reference(s) NOT proxied (" +
                listed.map { case (rejected, count) => s"${rejected.value} [${rejected.failure.code}]x$count" }.mkString(", ") +
                ")"
            )
          val payload = Json
            .obj(
              // Warnings come FIRST on purpose: ToolResultGuard replaces the
              // LLM-visible content of a result over 50K chars with the first
              // 2048 chars + a persisted-file pointer (ToolResultGuard.scala
              // persistAndReplace). A big card would push a trailing warning
              // section out of that preview — leading with fileRefs/warnings
              // keeps the failure visible to the model in every case.
              // Field order is irrelevant to the frontend (property access on
              // the parsed object), so this stays contract-compatible; the
              // toolfail batch only ADDED `exempt` inside fileRefs.
              "fileRefs" -> fileRefsJson(
                outcome.proxied,
                distinct.size,
                distinct.size - listed.size,
                outcome.exempt
              ),
              "warnings" -> warningsJson(listed),
              "html" -> outcome.html.asJson,
              "title" -> title.asJson
            )
            .noSpaces
          Right(s"$CardSentinel$payload")
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
    val failed = unresolvedFileRefs(result)
    val exempt = exemptFileRefs(result)
    // Visibility (carderr batch): a card whose local references were dropped
    // used to look like a clean `Card rendered` in the chat header as well.
    // toolfail batch: a suppressed app-route exemption is surfaced too, so
    // `fileRefs.exempt` is not a silent counter.
    val note =
      if failed > 0 then s" — $failed file reference(s) NOT proxied"
      else if exempt > 0 then s" — $exempt app-route reference(s) exempt"
      else ""
    s"$title rendered$note"

end CardTool
