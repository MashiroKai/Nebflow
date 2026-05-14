package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.agent.AgentCommand

/**
 * Let the LLM proactively manage context by summarizing recent tool call results.
 *
 * Selection format: `rounds: N` means summarize the last N rounds of tool calls
 * (counting from the end of the conversation). Maximum 10 rounds per call.
 *
 * The LLM writes a summary for each selected round, and the tool replaces the
 * original tool_result content with the summary in the conversation history.
 *
 * Recommended use cases:
 * - Search results that were irrelevant or fully processed
 * - Completed investigation phases where only conclusions matter
 * - Large file reads where only portions were useful
 */
object RemoveUnnecessaryTool extends Tool:
  val name = "RemoveUnnecessary"

  val description =
    """Summarize and replace recent tool call results to free up context window space. Use proactively when:
- Search results were irrelevant or already fully processed
- A phase of investigation is complete, only conclusions matter
- Large file reads where only small portions were useful

Select recent tool call rounds to summarize. Your summary replaces the original content.

Example: rounds=3 means summarize the last 3 rounds of tool call results."""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> io.circe.Json.obj(
        "rounds" -> io.circe.Json.obj(
          "type" -> "integer".asJson,
          "description" -> "Number of recent tool call rounds to summarize (1-10). 1=most recent round only.".asJson,
          "minimum" -> 1.asJson,
          "maximum" -> 10.asJson
        ),
        "summary" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Summary to replace the selected tool result content. Be specific: include file paths with backticks, line numbers, key findings.".asJson
        )
      ),
      "required" -> io.circe.Json.arr("rounds".asJson, "summary".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val rounds = input("rounds").flatMap(_.as[Int].toOption).getOrElse(1)
    s"RemoveUnnecessary(rounds=$rounds)"

  def summarizeResult(input: JsonObject, result: String): String = result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val rounds = input("rounds").flatMap(_.as[Int].toOption).getOrElse(1)
    val summary = input("summary").flatMap(_.asString).getOrElse("")

    if summary.isEmpty then IO.pure(Left(ToolError("summary is required")))
    else if rounds < 1 || rounds > 10 then IO.pure(Left(ToolError("rounds must be between 1 and 10")))
    else
      ctx.agentActorRef match
        case Some(ref) =>
          // Use a Deferred to get the result back from the actor
          for
            deferred <- cats.effect.Deferred[IO, Either[String, Int]]
            _ <- IO(ref ! AgentCommand.ReplaceToolResults(rounds, summary, deferred))
            result <- deferred.get
          yield result match
            case Right(count) => Right(s"Replaced $count tool result(s). Summary: $summary")
            case Left(err) => Left(ToolError(err))
        case None =>
          IO.pure(Left(ToolError("RemoveUnnecessary requires an agent actor reference")))
end RemoveUnnecessaryTool
