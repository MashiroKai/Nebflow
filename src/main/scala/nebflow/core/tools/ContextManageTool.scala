package nebflow.core.tools

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.core.compact.{CompactConfig, FullCompact, MicroCompact}
import nebflow.shared.*

object ContextManageTool extends Tool:
  private val logger = nebflow.core.NebflowLogger.forName("nebflow.context")

  val name = "ContextManage"

  val description =
    """Manage your context window. Triggers a sub-agent that reads your conversation history and compresses it.

Modes:
- "full" — Compress entire history into one summary. Best when context is very large.
- "micro" — Selectively compress completed parts while keeping recent context intact. Best for targeted cleanup."""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> io.circe.Json.obj(
        "mode" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "enum" -> io.circe.Json.arr("full".asJson, "micro".asJson),
          "description" -> """Compression mode.
|- "full" — Compress entire history into one summary. Best when context is very large.
|- "micro" — Selectively compress completed parts while keeping recent context intact. Best for targeted cleanup.""".asJson
        )
      ),
      "required" -> io.circe.Json.arr("mode".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val mode = input("mode").flatMap(_.asString).getOrElse("full")
    s"ContextManage($mode)"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 120 then result.take(117) + "..." else result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    (ctx.messagesRef, ctx.llm) match
      case (Some(ref), Some(llm)) =>
        val mode = input("mode").flatMap(_.asString).getOrElse("full")
        for
          messages <- ref.get
          config = CompactConfig.forContextWindow(ctx.contextWindow)
          result <- mode match
            case "micro" => MicroCompact.compact(messages, llm, config, ctx.projectRoot)
            case _ => FullCompact.compact(messages, llm, config, ctx.projectRoot)
        yield result match
          case Left(err) => Left(ToolError(s"Compact failed: $err"))
          case Right(compacted) =>
            ref.set(compacted)
            Right(s"OK: Compacted ${messages.size} -> ${compacted.size} messages ($mode)")

      case (None, _) => IO.pure(Left(ToolError("No message ref available")))
      case (_, None) => IO.pure(Left(ToolError("No LLM handle available")))

end ContextManageTool
