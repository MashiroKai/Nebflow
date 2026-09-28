package nebflow.llm.providers

import io.circe.parser.parse
import io.circe.syntax.*
import io.circe.{JsonObject}

import nebflow.core.NebflowLogger

/**
 * Shared parser for LLM tool-call arguments (accumulated streaming fragments or
 * non-streaming `arguments` strings).
 *
 * Root cause this guards against (issue #18, 2026-08-17): GLM-5.3 emitted the
 * Schedule tool's triggerAt as an UNQUOTED ISO-8601 literal —
 * `{"content":"...","triggerAt":2026-08-18T00:00:00+08:00}` — which is not
 * valid JSON. The adapters' old `parse(...).getOrElse(JsonObject.empty)`
 * silently coerced every parse failure to an empty object, so the tool saw
 * `triggerAt is required` while the frontend (which renders the raw stream
 * fragments) showed the "complete" arguments. Six identical failures in a row
 * because the error message pointed away from the real problem.
 *
 * Contract:
 *  1. Blank input → empty object (legitimate zero-arg call).
 *  2. Valid JSON object → returned untouched (rescue never mutates healthy JSON).
 *  3. Invalid JSON → one repair attempt: quote bare ISO-8601 date literals.
 *  4. Still invalid → marker object carrying the raw text, so
 *     `AgentCore.executeTool` can return a self-describing error to the LLM
 *     instead of a misleading "parameter is required".
 */
object ToolInputJson:

  private val logger = NebflowLogger.forName("nebflow.llm.toolargs")

  /** Marker key injected when arguments cannot be parsed. Reserved — never a real tool parameter. */
  val RawArgsKey = "__nebflow_raw_args__"

  /** Cap for the raw text carried in the marker (context safety for huge Write/Edit args). */
  private val MaxRawChars = 4000

  /**
   * Bare (unquoted) ISO-8601 date / date-time used as a JSON object value:
   * after `:`, before `,` `}` `]`. Date-only, `T`- or space-separated time,
   * optional fractional seconds, `Z` or `±hh:mm` / `±hhmm` offset.
   * Only applied to input whose direct parse already failed.
   */
  private val BareIsoLiteral =
    """(?<=:)\s*(\d{4}-\d{2}-\d{2}(?:[T ]\d{2}:\d{2}(?::\d{2}(?:[.,]\d{1,9})?)?)?(?:[Zz]|[+-]\d{2}:?\d{2})?)(?=\s*[,}\]])""".r

  /** Parse tool arguments; never fails silently. See object doc for the contract. */
  def parseToolInput(toolName: String, raw: String): JsonObject =
    if raw.isBlank then JsonObject.empty
    else
      parse(raw).flatMap(_.as[JsonObject]) match
        case Right(obj) => obj
        case Left(directErr) =>
          val rescued = BareIsoLiteral.replaceAllIn(raw, m => "\"" + m.group(1).trim + "\"")
          parse(rescued).flatMap(_.as[JsonObject]) match
            case Right(obj) =>
              logger.infoSync(
                s"tool '$toolName': rescued unquoted ISO-8601 literal in arguments (direct parse error: ${directErr.getMessage})"
              )
              obj
            case Left(rescueErr) =>
              logger.warnSync(
                s"tool '$toolName': arguments are not valid JSON and could not be repaired " +
                  s"(${rescueErr.getMessage}); raw=${raw.take(200)}"
              )
              malformedInput(raw)

  /** Marker object carrying the raw (unparseable) arguments. */
  def malformedInput(raw: String): JsonObject =
    JsonObject(RawArgsKey -> capRaw(raw).asJson)

  /**
   * If `input` carries the malformed-args marker, return the error message the
   * LLM should see instead of executing the tool with no parameters.
   */
  def malformedDetails(input: JsonObject, toolName: String): Option[String] =
    input(RawArgsKey).flatMap(_.asString).map { raw =>
      s"Tool call to $toolName received NO parameters — the arguments JSON could not be parsed, so every field was dropped. " +
        s"Your raw arguments were:\n$raw\n" +
        "Likely cause: a string value missing its quotes, e.g. \"triggerAt\": 2026-08-18T00:00:00+08:00 " +
        "is invalid JSON and must be \"2026-08-18T00:00:00+08:00\" (quoted). " +
        "Re-issue the tool call with strictly valid JSON."
    }

  private def capRaw(raw: String): String =
    if raw.length <= MaxRawChars then raw
    else raw.take(MaxRawChars) + s"…(truncated, ${raw.length} chars total)"

end ToolInputJson
