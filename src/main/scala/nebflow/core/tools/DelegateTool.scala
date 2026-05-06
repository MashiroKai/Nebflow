package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.agent.AgentCommand
import nebflow.core.NebflowLogger
import nebflow.shared.ToolCall

object DelegateTool extends Tool:
  private val logger = NebflowLogger.forName("nebflow.tools.delegate")

  val name = "delegate"

  val description =
    """Delegate a task to a sub-agent. The sub-agent will execute independently and report results back.

Provide the agent name and a clear task description. Available sub-agents are defined in your agent configuration.
If the agent name is not recognized, the call will fail with a list of available agents."""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> io.circe.Json.obj(
        "agent" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Name of the sub-agent to delegate to".asJson
        ),
        "task" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "The task description to delegate".asJson
        )
      ),
      "required" -> io.circe.Json.arr("agent".asJson, "task".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val agent = input("agent").flatMap(_.asString).getOrElse("?")
    val task = input("task").flatMap(_.asString).getOrElse("")
    val short = if task.length > 30 then task.take(27) + "..." else task
    s"delegate($agent: $short)"

  def summarizeResult(input: JsonObject, result: String): String = result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val agentName = input("agent").flatMap(_.asString).getOrElse("")
    val task = input("task").flatMap(_.asString).getOrElse("")

    def maxDepthError(maxDepth: Int) =
      Left(ToolError(s"Cannot delegate: maximum depth ($maxDepth) exceeded"))

    (ctx.agentDef, ctx.agentActorRef) match
      case (None, _) | (_, None) =>
        IO.pure(Left(ToolError("Delegate requires agent definition and actor reference")))
      case (Some(agentDef), Some(selfRef)) =>
        if ctx.depth > 0 then IO.pure(Left(ToolError("Sub-agents cannot delegate to further sub-agents")))
        else
          agentDef.subagents.find(_.name == agentName) match
            case None =>
              val available = agentDef.subagents.map(_.name).mkString(", ")
              IO.pure(
                Left(
                  ToolError(
                    s"Unknown subagent: '$agentName'. Available: $available"
                  )
                )
              )
            case Some(slot) =>
              ctx.agentLibrary match
                case None =>
                  IO.pure(Left(ToolError("Delegate requires agent library access")))
                case Some(library) =>
                  library
                    .get(slot.agent)
                    .flatMap { defnOpt =>
                      IO(
                        selfRef ! AgentCommand.SubagentDefLoaded(
                          ToolCall(java.util.UUID.randomUUID().toString, "delegate", input),
                          agentName,
                          task,
                          defnOpt,
                          ctx.depth + 1
                        )
                      )
                    }
                    .as(Right(s"Delegated to $agentName: ${task.take(100)}"))
    end match
  end call
end DelegateTool
