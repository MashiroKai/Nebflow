package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.agent.{AgentCommand, CompactionResult}

object ContextManageTool extends Tool:
  val name = "ContextManage"

  val description =
    """Trigger context compaction to free up context window space.

The system will automatically compact context when remaining tokens fall below
bufferTokens (default 13000). Use this tool proactively if you want to compact
before hitting that threshold, or if you want to use micro mode for targeted cleanup.

## Modes

- "full" — Compress entire history into one summary (default). Best for a clean slate.
- "micro" — Selectively compress completed parts while keeping recent context intact.

## When to use

- After finishing a multi-file research phase, before starting implementation
- After a long debugging session that resolved an issue
- Before starting a new sub-task unrelated to the previous one
- When context feels crowded but you still need recent messages verbatim (use micro)"""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "mode" -> Json.obj(
          "type" -> "string".asJson,
          "enum" -> Json.arr("full".asJson, "micro".asJson),
          "description" -> "Compaction mode: full (default) or micro".asJson
        )
      ),
      "required" -> Json.arr()
    )
  )

  def summarize(input: JsonObject): String =
    val mode = input("mode").flatMap(_.asString).getOrElse("full")
    s"ContextManage($mode)"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 120 then result.take(117) + "..." else result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val mode = input("mode").flatMap(_.asString).getOrElse("full")
    ctx.agentActorRef match
      case Some(agentRef) =>
        val deferred = cats.effect.Deferred.unsafe[IO, Either[String, CompactionResult]]
        IO(agentRef ! AgentCommand.TriggerCompaction(mode, Some(deferred))) *>
          deferred.get.map {
            case Right(cr) =>
              Right(s"Context compaction completed ($mode mode): ${cr.before} messages -> ${cr.after} messages")
            case Left(err) =>
              Left(ToolError(s"Context compaction failed: $err"))
          }
      case None =>
        IO.pure(Left(ToolError("No agent actor available to trigger compaction")))

end ContextManageTool
