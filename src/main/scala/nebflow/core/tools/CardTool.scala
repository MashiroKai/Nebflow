package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}

/**
 * Card tool — safely emits structured card payloads for frontend rendering.
 *
 * This tool replaces the error-prone Bash-echo pattern for sending card JSON.
 * Bash echo parses shell metacharacters (parentheses, quotes, angle brackets)
 * which corrupts HTML/CSS-heavy payloads. CardTool uses native JSON serialization,
 * completely bypassing the shell.
 *
 * Output format: ___<AGENT>_JSON___{"ok":true,"data":{"_card":"type",...}}
 *
 * Frontend renderers should register on tool name "Card" (or match the marker).
 */
object CardTool extends Tool:

  val name = "Card"

  val description = """Emits a structured card payload for frontend rendering.

Use this tool instead of Bash echo to send card data. It avoids shell escaping issues with HTML, quotes, parentheses, and other special characters that corrupt JSON when passed through bash.

Parameters:
- cardType (string, required): The card type identifier. Maps to the `_card` field in the payload (e.g. "page_review", "color_palette", "design_plan").
- data (object, required): Arbitrary card-specific JSON fields. This object is merged into the payload under the `data` key.
- ok (boolean, default true): Whether the card represents a successful state.

Example:
{
  "cardType": "page_review",
  "data": {
    "num": 1,
    "html": "<section style='background:#f5f5f7'><div style='background:linear-gradient(rgba(7,193,96,0.03) 1px,transparent 1px)'></div></section>"
  }
}

The output is automatically prefixed with the agent's marker (e.g. ___NOVA_JSON___) so existing frontend renderers can match it."""

  val inputSchema: JsonObject = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "cardType" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Card type identifier, e.g. page_review, color_palette".asJson
        ),
        "data" -> Json.obj(
          "type" -> "object".asJson,
          "description" -> "Card-specific JSON data fields".asJson
        ),
        "ok" -> Json.obj(
          "type" -> "boolean".asJson,
          "description" -> "Whether the card represents success (default true)".asJson
        )
      ),
      "required" -> Json.arr("cardType".asJson, "data".asJson)
    )
  )

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val cardTypeOpt = input("cardType").flatMap(_.asString)
    val dataOpt = input("data")
    val ok = input("ok").flatMap(_.asBoolean).getOrElse(true)

    (cardTypeOpt, dataOpt) match
      case (Some(cardType), Some(data)) =>
        val payload = Json.obj(
          "ok" -> ok.asJson,
          "data" -> data.deepMerge(Json.obj("_card" -> cardType.asJson))
        )
        val agentName = ctx.agentDef.map(_.name).getOrElse("NEBFLOW")
        val marker = s"___${agentName.toUpperCase}_JSON___"
        IO.pure(Right(marker + payload.noSpaces))

      case (None, _) =>
        IO.pure(Left(ToolError("Missing required parameter: cardType")))

      case (_, None) =>
        IO.pure(Left(ToolError("Missing required parameter: data")))
  end call

  def summarize(input: JsonObject): String =
    val cardType = input("cardType").flatMap(_.asString).getOrElse("?")
    s"Card\n  ($cardType)"

  def summarizeResult(input: JsonObject, result: String): String =
    "Card emitted"

end CardTool
