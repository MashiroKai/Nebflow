package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*

object DeclareWaitTool extends Tool:
  val name = "declareWait"

  val description =
    """Declare that this turn is intentionally waiting for an external condition and should not count as stagnation.

Use this when you are waiting for user input, an external process, or any condition outside your control.
This prevents the adaptive stage system from escalating due to perceived inactivity."""

  val inputSchema = JsonObject.fromIterable(List(
    "type" -> "object".asJson,
    "properties" -> io.circe.Json.obj(
      "reason" -> io.circe.Json.obj(
        "type" -> "string".asJson,
        "description" -> "Explanation of what is being waited for".asJson
      )
    ),
    "required" -> io.circe.Json.arr("reason".asJson)
  ))

  def summarize(input: JsonObject): String =
    val reason = input("reason").flatMap(_.asString).getOrElse("")
    val short = if reason.length > 40 then reason.take(37) + "..." else reason
    s"declareWait($short)"

  def summarizeResult(input: JsonObject, result: String): String = result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val reason = input("reason").flatMap(_.asString).getOrElse("")
    IO.pure(Right(s"[Wait declared] $reason"))
