package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.agent.AgentCommand

/**
 * ContextManage tool definition.
 *
 * Registration: Provides name, description, inputSchema for LLM tool definitions.
 *
 * Execution path: NOT via the normal executeTool pipeline. Instead,
 * pipeToolExecutions separates ContextManage calls and generates synthetic results.
 * The actual compaction is triggered directly in the ToolsComplete handler via
 * handleTriggerCompaction — this avoids stash timing issues that would occur if
 * TriggerCompaction were sent as a message during processing state.
 *
 * The `call` method exists as a safety fallback but is never invoked in practice.
 */
object ContextManageTool extends Tool:
  val name = "ContextManage"

  val description =
    """Trigger full context compaction to free up context window space. Use proactively before starting a new unrelated sub-task.

Compaction runs asynchronously — the current turn completes normally while compaction happens in the background."""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> io.circe.Json.obj(),
      "required" -> io.circe.Json.arr()
    )
  )

  def summarize(input: JsonObject): String = "ContextManage(full)"

  def summarizeResult(input: JsonObject, result: String): String = result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    ctx.agentActorRef match
      case Some(ref) =>
        ref ! AgentCommand.TriggerCompaction("full")
        IO.pure(Right("[ContextManage] Triggered full compaction"))
      case None =>
        IO.pure(Left(ToolError("ContextManage requires an agent actor reference")))
end ContextManageTool
