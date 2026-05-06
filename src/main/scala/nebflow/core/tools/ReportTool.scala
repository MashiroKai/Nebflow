package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.agent.AgentCommand

object ReportTool extends Tool:
  val name = "report"

  val description =
    """Report a result or message back to the parent agent that delegated to you.

Use this when you have completed your assigned task and want to send findings to the parent.
Only available when this agent was spawned as a sub-agent (has a parent)."""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> io.circe.Json.obj(
        "message" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "The result or message to report".asJson
        )
      ),
      "required" -> io.circe.Json.arr("message".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val msg = input("message").flatMap(_.asString).getOrElse("")
    val short = if msg.length > 40 then msg.take(37) + "..." else msg
    s"report($short)"

  def summarizeResult(input: JsonObject, result: String): String = result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val message = input("message").flatMap(_.asString).getOrElse("")
    (ctx.parentRef, ctx.agentActorRef) match
      case (Some(parent), Some(selfRef)) =>
        parent ! AgentCommand.DelegateResult(selfRef.path.name, Right(message))
        IO.pure(Right("Reported to parent"))
      case (None, _) =>
        IO.pure(Left(ToolError("Cannot report: no parent agent")))
      case _ =>
        IO.pure(Left(ToolError("Report requires agent actor reference")))
end ReportTool
