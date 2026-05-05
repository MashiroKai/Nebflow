package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.agent.AgentCommand

object ContextManageTool extends Tool:
  val name = "ContextManage"

  val description =
    """Trigger context compaction to free up context window space. Use proactively before starting a new unrelated sub-task.

Supports two modes:
- full (default): Full compaction with comprehensive summarization
- micro: Lightweight compaction that only trims recent messages

Compaction runs asynchronously — the current turn completes normally while compaction happens in the background."""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> io.circe.Json.obj(
        "mode" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "enum" -> io.circe.Json.arr("full".asJson, "micro".asJson),
          "description" -> "Compaction mode: full (default) or micro".asJson
        )
      ),
      "required" -> io.circe.Json.arr()
    )
  )

  def summarize(input: JsonObject): String =
    val mode = input("mode").flatMap(_.asString).getOrElse("full")
    s"ContextManage($mode)"

  def summarizeResult(input: JsonObject, result: String): String = result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val mode = input("mode").flatMap(_.asString).getOrElse("full")
    ctx.agentActorRef match
      case Some(ref) =>
        ref ! AgentCommand.TriggerCompaction(mode)
        IO.pure(Right(s"[ContextManage] Triggered $mode compaction"))
      case None =>
        IO.pure(Left(ToolError("ContextManage requires an agent actor reference")))
end ContextManageTool
