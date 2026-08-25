package nebflow.core.flow

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import nebflow.actor.*
import nebflow.agent.*
import nebflow.core.NebflowLogger
import nebflow.core.entity.{FlowDagDef, FlowDagExecutor}

/**
 * One-shot actor that runs a Flow DAG and delivers the result to the caller.
 *
 *  Spawned by FlowTriggerTool when an agent triggers a flow via the FlowTrigger tool.
 *  Executes the DAG (synchronous, node-by-node) and delivers the result back to
 *  the calling agent via ImmediateInput. Stops itself after completion.
 */
object FlowDagRunner:
  private val logger = NebflowLogger.forName("nebflow.flow.dag-runner")

  case class RunFlow(
    flowDef: FlowDagDef,
    taskInput: String,
    replyTo: ActorRef[AgentCommand],
    /**
     * P2: permission-policy bucket of the agent that triggered the flow —
     * flow nodes inherit the caller's root session so their permission/AskUser
     * requests render in the same Nebula window and share its policy.
     */
    rootSessionId: String = "",
    // Structured trigger parameters (validated against flow.params schema by
    // the caller); node inputs reference them via $params.<name>.
    params: Map[String, Json] = Map.empty,
    // #406: true for one-shot FlowExecute flows (inline DAG, no flows/ dir).
    // Instance id uses the "inline-" prefix (frontend distinguishes dynamic
    // runs) and node agents resolve from the global library only.
    dynamic: Boolean = false
  )

  def apply(resources: SharedResources, wsSend: Option[Json => IO[Unit]]): Behavior[RunFlow] =
    Behaviors.receiveMessage:
      case RunFlow(flowDef, taskInput, replyTo, rootSessionId, params, dynamic) =>
        val instanceId =
          if dynamic then s"inline-${java.util.UUID.randomUUID().toString.take(8)}"
          else s"flow-${flowDef.name.take(15)}-${java.util.UUID.randomUUID().toString.take(8)}"
        val parentAgentRef = Some(replyTo) // replyTo is the AgentRef of the agent that triggered the flow
        for
          _ <- logger.info(s"Starting DAG execution for flow '${flowDef.name}' (instance: $instanceId${if dynamic then ", dynamic" else ""})")
          result <- FlowDagExecutor
            .execute(
              flowDef,
              taskInput,
              resources,
              resources.actorSystem,
              wsSend,
              instanceId,
              parentAgentRef,
              rootSessionId,
              params,
              dynamic
            )
            .handleErrorWith(e => logger.warn(s"FlowDagExecutor failed: ${e.getMessage}").as(Left(e.getMessage)))
          _ <- result match
            case Right(output) =>
              (replyTo ! AgentCommand.ImmediateInput(
                s"[Flow '${flowDef.name}' completed]\n$output",
                source = Some("flow"),
                // sender/eventType feed the injected-bubble source label:
                // 'Flow · <flow name> · Completed' (373 — flow name was invisible).
                eventType = Some("completed"),
                sender = Some(flowDef.name)
              )).void
            case Left(err) =>
              (replyTo ! AgentCommand.ImmediateInput(
                s"[Flow '${flowDef.name}' failed]\n$err",
                source = Some("flow"),
                eventType = Some("failed"),
                sender = Some(flowDef.name)
              )).void
        yield Behaviors.stopped
        end for

end FlowDagRunner
