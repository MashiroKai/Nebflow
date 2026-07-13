package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.agent.{AgentCommand, PlanAgent, PlanResult}

/**
 * PlanTool — lets the main agent enter plan mode by spawning a read-only
 * plan sub-agent. The plan agent's output is displayed on a canvas for
 * user approval. The tool call blocks until the user approves or cancels.
 *
 * This is the "self-triggered" entry point. The other entry point is the
 * `/plan` command, which goes through WebSocketRoutes → StartPlan → planWaiting.
 */
object PlanTool extends Tool:
  private val logger = nebflow.core.NebflowLogger(getClass)

  val name = "Plan"

  val description =
    """Enter plan mode: spawn a read-only planning agent that analyzes the codebase and produces a structured implementation plan.

The plan is displayed on a canvas for user review. The user can:
- Approve the plan → the plan text is returned as the tool result
- Send feedback → the plan agent revises the plan
- Cancel → the tool returns an error

The tool call BLOCKS until the user approves or cancels.

Use this tool when:
- A complex task requires careful analysis before implementation
- You want to present a structured plan for user approval before making changes
- The user explicitly asks for a plan

Do NOT use this tool for:
- Simple tasks you can handle directly
- Tasks where the user has already given clear, specific instructions""";

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "prompt" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "The task to plan. Should be a clear description of what needs to be done.".asJson
        )
      ),
      "required" -> Json.arr("prompt".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val prompt = input("prompt").flatMap(_.asString).getOrElse("")
    s"Plan(${prompt.take(60)})"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 200 then result.take(197) + "..." else result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val prompt = input("prompt").flatMap(_.asString).getOrElse("")

    if prompt.trim.isEmpty then IO.pure(Left(ToolError("Missing required parameter: prompt")))
    else
      (ctx.actorSystem, ctx.sharedResources, ctx.agentActorRef, ctx.agentLibrary) match
        case (Some(system), Some(resources), Some(mainAgentRef), Some(_)) =>
          for
            deferred <- cats.effect.Deferred[IO, PlanResult]
            planAgentRef <- PlanAgent.spawn(
              task = prompt,
              mainAgentRef = mainAgentRef,
              system = system,
              resources = resources,
              parentDepth = ctx.depth,
              wsSend = ctx.wsSend.getOrElse((_: Json) => IO.unit),
              projectRoot = ctx.projectRoot,
              parentSessionId = ctx.sessionId
            )
            // Register plan state on main agent so it can handle plan events
            _ <- mainAgentRef ! AgentCommand.SetPlanState(planAgentRef, Some(deferred))
            // Block until user approves or cancels
            result <- deferred.get
          yield result match
            case PlanResult.Approved(planText) =>
              Right(planText)
            case PlanResult.Cancelled =>
              Left(ToolError("Plan cancelled by user"))

        case _ =>
          IO.pure(Left(ToolError("Plan requires ActorSystem, SharedResources, agentActorRef, and agentLibrary")))
  end call

end PlanTool
