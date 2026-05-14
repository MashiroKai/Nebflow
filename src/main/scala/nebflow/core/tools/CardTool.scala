package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}

/**
 * Card tool — renders arbitrary HTML in a sandboxed iframe in the chat stream.
 *
 * Agents emit HTML directly. The frontend renders it in a sandboxed <iframe>
 * with theme variables injected, so dark mode works automatically.
 *
 * This replaces the previous JSON+renderer pipeline. No per-agent frontend JS needed.
 */
object CardTool extends Tool:

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
      case Some(_) =>
        val title = input("title").flatMap(_.asString).getOrElse("")
        IO.pure(Right(s"${if (title.nonEmpty) title else "Card"} rendered"))

      case None =>
        IO.pure(Left(ToolError("Missing required parameter: html")))

  def summarize(input: JsonObject): String =
    val title = input("title").flatMap(_.asString).getOrElse("Card")
    s"Card\n  ($title)"

  def summarizeResult(input: JsonObject, result: String): String =
    val title = input("title").flatMap(_.asString).getOrElse("Card")
    s"$title rendered"

end CardTool
