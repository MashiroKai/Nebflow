package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.agent.AgentCommand
import nebflow.agent.AgentCommand.ParentAnswer

object AskParentTool extends Tool:
  val name = "ask_parent"

  val description =
    """Ask the parent agent a question and receive the response asynchronously.

Use this when you need clarification or information from the parent agent that delegated to you.
Only available when this agent was spawned as a sub-agent.

Non-blocking behavior:
- When you call this tool, the question is sent to the parent agent immediately.
- You do NOT need to wait for the answer. You can continue with other tasks in this turn, or finish your turn.
- When the parent responds, you will be automatically notified with their answer. You do not need to poll or re-ask.
- This means calling ask_parent is a valid way to complete a step — you have not abandoned the task."""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> io.circe.Json.obj(
        "question" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "The question to ask the parent".asJson
        )
      ),
      "required" -> io.circe.Json.arr("question".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val q = input("question").flatMap(_.asString).getOrElse("")
    val short = if q.length > 40 then q.take(37) + "..." else q
    s"ask_parent($short)"

  def summarizeResult(input: JsonObject, result: String): String = result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val question = input("question").flatMap(_.asString).getOrElse("")
    (ctx.parentRef, ctx.agentActorRef) match
      case (Some(parent), Some(selfRef)) =>
        // Fire-and-forget: send SubagentQuestion without replyTo
        val subagentId = selfRef.path.name
        IO(
          parent ! AgentCommand.SubagentQuestion(
            subagentId,
            question,
            replyTo = None,
            subagentRef = Some(selfRef)
          )
        ) *>
          IO.pure(
            Right(
              "[Question sent to parent] You will be automatically notified when they respond — continue with other work or finish your turn."
            )
          )
      case (None, _) =>
        IO.pure(Left(ToolError("Cannot ask_parent: no parent agent")))
      case _ =>
        IO.pure(Left(ToolError("ask_parent requires agent actor reference")))
end AskParentTool
