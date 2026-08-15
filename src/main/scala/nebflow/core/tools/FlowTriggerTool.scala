package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.*

/**
 * FlowTriggerTool — triggers a flow DAG pipeline.
 *
 * Split out of DelegateTool (R1, 2026-08-15): "delegate an agent" and
 * "trigger a pipeline" are two verbs with different runtime mechanics
 * (AgentActor + BackoffSupervisor vs one-shot FlowDagRunner) and different
 * permission dimensions. Delegate is now a pure sub-agent spawner.
 *
 * Availability is whitelist-driven, NOT Nebula-exclusive: buildAllowedToolSet
 * injects this tool into any agent whose agent.json declares a non-empty
 * `flows` array, and strips it from everyone else. Each call is gated by the
 * same whitelist (the agent must declare the flow it triggers). SubTask
 * workers are leaf agents and never get it.
 *
 * The flow runs in the background; the result is delivered to the caller as
 * an ImmediateInput message ("[Flow '...' completed] ...") when it finishes.
 */
object FlowTriggerTool extends Tool:
  val name = "FlowTrigger"

  val description =
    """Trigger a flow DAG pipeline (e.g. "code-review", "release-stable"). The pipeline drives a sequence of agents defined in its flow.json — you do not pick the agents, the flow routes itself.

**Parameters:**
- flow (required): flow name. Must be declared in your agent's flows whitelist — calling with an undeclared flow is rejected.
- prompt (required): self-contained task input for the flow's entry node.

**Behavior:**
- Returns immediately after the flow starts; the pipeline runs in the background
- The result is delivered to you as a system message when the flow completes ("[Flow '<name>' completed]")
- The prompt goes to the entry node; downstream nodes receive upstream outputs automatically

**Rules:**
- Prompt must be self-contained (the flow's agents start with clean contexts)
- State what "done" looks like (e.g. "Report findings — do not modify files")
- Do NOT duplicate the pipeline's work while it runs
- To spawn a sub-agent instead (a single autonomous worker), use Delegate."""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "flow" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Flow name to trigger (e.g. \"code-review\", \"release-beta\"). Must be declared in your agent's flows whitelist.".asJson
        ),
        "prompt" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Self-contained task input for the flow's entry node. Must include all context the pipeline needs.".asJson
        )
      ),
      "required" -> Json.arr("flow".asJson, "prompt".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val flow = input("flow").flatMap(_.asString).getOrElse("?")
    s"FlowTrigger(→ flow:$flow)"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 200 then result.take(197) + "..." else result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val flowName = input("flow").flatMap(_.asString).filter(_.nonEmpty)
    val prompt = input("prompt").flatMap(_.asString).getOrElse("")

    if flowName.isEmpty then IO.pure(Left(ToolError("Missing required parameter: flow")))
    else if prompt.trim.isEmpty then IO.pure(Left(ToolError("Missing required parameter: prompt")))
    else
      // Whitelist: agent must declare this flow in its flows list.
      // "*" is a wildcard matching any named flow — mirrors the skills layer
      // semantics (skills:["*"] = full catalog injection).
      val whitelistError: Option[ToolError] = ctx.agentDef match
        case Some(ad)
            if !(ad.flows.contains(flowName.get) || ad.flows.contains("*")) =>
          val allowed = if ad.flows.isEmpty then "(none — no flows declared)" else ad.flows.mkString(", ")
          Some(ToolError(s"Flow '${flowName.get}' not allowed for agent '${ad.name}'. Allowed: $allowed"))
        case _ => None
      whitelistError match
        case Some(err) => IO.pure(Left(err))
        case None =>
          // Trigger flow via FlowDagRunner
          (ctx.sharedResources, ctx.actorSystem, ctx.agentActorRef) match
            case (Some(resources), Some(sys), Some(callerRef)) =>
              for
                // Resolve the caller's root session so flow nodes inherit the
                // same permission policy and render interactions in the caller's
                // window.
                callerRoot <- ctx.sessionId match
                  case Some(sid) =>
                    resources.agentRegistry.get.map(_.get(sid).map(_.rootSessionId).filter(_.nonEmpty).getOrElse(sid))
                  case None => IO.pure("")
                flowOpt <- nebflow.core.entity.EntityLoader.loadFlow(flowName.get)
                r <- flowOpt match
                  case Some(flowDef) =>
                    for
                      runnerRef <- sys.spawn(
                        nebflow.core.flow.FlowDagRunner(resources, ctx.wsSend),
                        s"dag-runner-${flowName.get.take(10)}-${System.currentTimeMillis().toString.takeRight(6)}"
                      )
                      _ <- (runnerRef ! nebflow.core.flow.FlowDagRunner.RunFlow(
                        flowDef,
                        prompt,
                        callerRef,
                        callerRoot
                      )).void
                    yield Right(s"Flow '${flowName.get}' started. Result will be delivered when complete.")
                  case None =>
                    IO.pure(Left(ToolError(s"Flow '${flowName.get}' not found")))
              yield r
            case _ =>
              IO.pure(Left(ToolError("Cannot start flow: missing resources")))
    end if
  end call

end FlowTriggerTool
