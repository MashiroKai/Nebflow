package nebflow.core.flow

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import nebflow.actor.*
import nebflow.agent.*
import nebflow.core.NebflowLogger
import nebflow.core.entity.{FlowDagDef, FlowDagExecutor}

/** One-shot actor that runs a Flow DAG and delivers the result to the caller.
 *
 *  Spawned by MailTool when a Mail targets a flow name that has a flow.json DAG.
 *  Executes the DAG (synchronous, node-by-node) and Mails the result back to
 *  the calling agent via ImmediateInput. Stops itself after completion.
 */
object FlowDagRunner:
  private val logger = NebflowLogger.forName("nebflow.flow.dag-runner")

  case class RunFlow(
    flowDef: FlowDagDef,
    taskInput: String,
    replyTo: ActorRef[AgentCommand]
  )

  def apply(resources: SharedResources, wsSend: Option[Json => IO[Unit]]): Behavior[RunFlow] =
    Behaviors.receiveMessage:
      case RunFlow(flowDef, taskInput, replyTo) =>
        val instanceId = s"flow-${flowDef.name.take(15)}-${java.util.UUID.randomUUID().toString.take(8)}"
        for
          _ <- logger.info(s"Starting DAG execution for flow '${flowDef.name}' (instance: $instanceId)")
          result <- FlowDagExecutor.execute(flowDef, taskInput, resources, resources.actorSystem, wsSend, instanceId)
            .handleErrorWith(e =>
              IO(logger.warn(s"FlowDagExecutor failed: ${e.getMessage}")).as(Left(e.getMessage))
            )
          _ <- result match
            case Right(output) =>
              (replyTo ! AgentCommand.ImmediateInput(
                s"[Flow '${flowDef.name}' completed]\n$output"
              )).void
            case Left(err) =>
              (replyTo ! AgentCommand.ImmediateInput(
                s"[Flow '${flowDef.name}' failed]\n$err"
              )).void
        yield Behaviors.stopped

end FlowDagRunner
