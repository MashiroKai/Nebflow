package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.service.MemoryStore

/**
 * Tool for MemoryAgent to clear the staging file after processing all entries.
 * Only useful during Dream cycles — regular agents never need this.
 */
object ClearStagingTool extends Tool:
  val name = "ClearStaging"

  val description =
    "Delete the memory staging file after all entries have been processed. " +
      "Call this once you have finished writing all staging entries to memory files."

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "confirm" -> Json.obj(
          "type" -> "boolean".asJson,
          "description" -> "Set to true to confirm deletion.".asJson
        )
      ),
      "required" -> Json.arr("confirm".asJson)
    )
  )

  def summarize(input: JsonObject): String = "ClearStaging()"

  def summarizeResult(input: JsonObject, result: String): String = result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val confirmed = input("confirm").flatMap(_.asBoolean).getOrElse(false)
    if !confirmed then IO.pure(Left(ToolError("Set confirm=true to delete the staging file")))
    else MemoryStore.clearStaging.as(Right("Staging file deleted."))
end ClearStagingTool
