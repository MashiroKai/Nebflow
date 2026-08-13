package nebflow.core.flow

import cats.effect.{Deferred, IO}
import io.circe.Json
import nebflow.actor.*
import nebflow.agent.*
import nebflow.shared.{ContentBlock, Message, MessageRole}

import java.util.UUID

/**
 * Runs a standalone agent definition ephemerally.
 *
 * Lifecycle: spawn AgentActor → send UserInput → wait for AgentEvent (event-driven, no timeout) →
 * extract output → reply to caller → stop agent actor → delete session → self-stop.
 *
 * Used by MailTool when mailing a standalone agent that isn't currently mounted.
 */
object EphemeralAgentRunner:

  case class RunAgent(
    agentDef: AgentDef,
    taskInput: String,
    replyTo: ActorRef[AgentCommand],
    depth: Int,
    projectRoot: String
  )

  def apply(resources: SharedResources, wsSend: Option[Json => IO[Unit]]): Behavior[RunAgent] =
    Behaviors.receiveMessage: msg =>
      val agentDef = msg.agentDef
      val replyTo = msg.replyTo
      val sessionId = s"ephemeral-${agentDef.name.take(10)}-${UUID.randomUUID().toString.take(8)}"
      val rawWsSend = wsSend.getOrElse((_: Json) => IO.unit)

      for
        resultDeferred <- Deferred[IO, Either[String, List[Message]]]
        // Bridge actor: receives AgentEvent, completes the Deferred, self-stops
        bridgeRef <- resources.actorSystem.spawn(
          Behaviors.receive[AgentEvent] { (ctx, event) =>
            event match
              case AgentEvent.Completed(_, messages) =>
                ctx.forkTurn(resultDeferred.complete(Right(messages)).void).as(Behaviors.stopped)
              case AgentEvent.Failed(_, err) =>
                ctx.forkTurn(resultDeferred.complete(Left(err.message)).void).as(Behaviors.stopped)
          },
          s"bridge-ephemeral-${agentDef.name.take(10)}"
        )
        // Spawn AgentActor
        ref <- resources.actorSystem.spawn(
          AgentActor(
            agentDef = agentDef,
            resources = resources,
            wsSend = rawWsSend,
            depth = msg.depth,
            parentRef = None,
            sessionId = Some(sessionId),
            sessionName = Some(s"ephemeral/${agentDef.name}"),
            initialMessages = Nil,
            readTracker = None,
            fileHistory = None,
            contextWindow = resources.contextWindow,
            expectsMail = false,
            // P2: standalone agent buckets its own permission policy (same as
            // its AgentRecord below).
            rootSessionId = sessionId
          ),
          s"ephemeral-agent-${agentDef.name.take(10)}"
        )
        // Register in agentRegistry so WS events (askUser, permission) route correctly
        _ <- resources.agentRegistry.update(
          _ + (sessionId -> AgentRecord(sessionId, ref, AgentKind.Ephemeral, sessionId))
        )
        // Send input with bridge actor as replyTo
        _ <- (ref ! AgentCommand.UserInput(
          text = msg.taskInput,
          replyTo = Some(bridgeRef)
        )).void
        // Wait for completion — no timeout, event-driven
        eventResult <- resultDeferred.get
        // Stop agent actor + cleanup
        _ <- resources.agentRegistry.update(_ - sessionId)
        _ <- resources.actorSystem.stop(ref).handleErrorWith(_ => IO.unit)
        _ <- resources.actorSystem.stop(bridgeRef).handleErrorWith(_ => IO.unit)
        _ <- resources.sessionStore.deleteSession(sessionId).handleErrorWith(_ => IO.unit)
        // Extract output and deliver to caller
        output = eventResult match
          case Right(messages) =>
            extractLastAssistant(messages)
          case Left(errMsg) =>
            s"[Agent '${agentDef.name}' failed: $errMsg]"
        _ <- (replyTo ! AgentCommand.ImmediateInput(
          s"[Agent '${agentDef.name}' completed]\n$output"
        )).void
      yield Behaviors.stopped

      end for

  /** Extract text from the last assistant message. */
  private def extractLastAssistant(messages: List[Message]): String =
    messages.reverse.find(_.role == MessageRole.Assistant) match
      case Some(msg) =>
        msg.content match
          case Left(text) => text
          case Right(blocks) => blocks.collect { case ContentBlock.Text(t) => t }.mkString("\n")
      case None => "(no output)"

end EphemeralAgentRunner
