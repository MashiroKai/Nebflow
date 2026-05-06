package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.agent.AgentCommand
import nebflow.agent.AgentCommand.ParentAnswer
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*

object AskParentTool extends Tool:
  val name = "ask_parent"

  val description =
    """Ask the parent agent a question and wait for a response.

Use this when you need clarification or information from the parent agent that delegated to you.
This blocks until the parent responds. Only available when this agent was spawned as a sub-agent."""

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
    (ctx.parentRef, ctx.agentActorRef, ctx.pekkoScheduler) match
      case (Some(parent), Some(selfRef), Some(scheduler)) =>
        import scala.concurrent.ExecutionContext.Implicits.global
        implicit val sched: org.apache.pekko.actor.typed.Scheduler = scheduler
        implicit val askTimeout: org.apache.pekko.util.Timeout =
          org.apache.pekko.util.Timeout(scala.concurrent.duration.Duration(60, "seconds"))
        IO.fromFuture(IO {
          parent.ask[ParentAnswer](replyTo => AgentCommand.SubagentQuestion(selfRef.path.name, question, replyTo))
        }).map(answer => Right(answer.answer))
          .handleError(e => Left(ToolError(s"Ask parent failed: ${e.getMessage}")))
      case (None, _, _) =>
        IO.pure(Left(ToolError("Cannot ask_parent: no parent agent")))
      case (_, _, None) =>
        IO.pure(Left(ToolError("ask_parent requires a Pekko scheduler")))
      case _ =>
        IO.pure(Left(ToolError("ask_parent requires agent actor reference")))
end AskParentTool
