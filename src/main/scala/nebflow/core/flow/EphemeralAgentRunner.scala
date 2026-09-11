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
    projectRoot: String,
    /** Block 0 registration chain (supervision trio §B2): the caller's
      * session (parent) and root (permission bucket). Absent → legacy
      * self-anchored behavior. */
    callerSessionId: Option[String] = None,
    callerRootSessionId: Option[String] = None
  )

  def apply(resources: SharedResources, wsSend: Option[Json => IO[Unit]]): Behavior[RunAgent] =
    Behaviors.receiveMessage: msg =>
      val agentDef = msg.agentDef
      val replyTo = msg.replyTo
      val sessionId = s"ephemeral-${agentDef.name.take(10)}-${UUID.randomUUID().toString.take(8)}"
      val rawWsSend = wsSend.getOrElse((_: Json) => IO.unit)

      for
        resultDeferred <- Deferred[IO, Either[String, List[Message]]]
        // Spawn AgentActor first — the bridge needs its ref for death-watch
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
            // P2 / Block 0: bucket under the CALLER's root when provided
            // (the legacy self-anchor left ephemerals outside every bucket —
            // same-bucket guards then rejected the only callers who could
            // manage them). Self only as fallback.
            rootSessionId = msg.callerRootSessionId.getOrElse(sessionId)
          ),
          s"ephemeral-agent-${agentDef.name.take(10)}"
        )
        // Bridge actor: receives AgentEvent, completes the Deferred, self-stops.
        // AgentControl 前置修复（spec §3.3）：MUST ctx.watch(agentRef)——直接对
        // agent 发 Stop（cancel）时，Terminated 在此完成 deferred(Left)，
        // 否则 resultDeferred.get 永久挂起（调用方 fiber 泄漏）。
        bridgeRef <- resources.actorSystem.spawn(
          Behaviors.setup[AgentEvent] { bctx =>
            bctx.watch(ref) *> IO.pure(
              new Behavior[AgentEvent]:
                def receive(ctx: ActorContext[AgentEvent], event: AgentEvent): IO[Behavior[AgentEvent]] =
                  event match
                    case AgentEvent.Completed(_, messages) =>
                      // 直接 complete（不 forkTurn）：forkTurn 与返回 stopped
                      // 竞态——actor loop 退出时 cancel 当前 turn，forked fiber
                      // 可能没跑，deferred 永远不完成（同 FlowDagExecutor 教训）。
                      resultDeferred.complete(Right(messages)).void.as(Behaviors.stopped)
                    case AgentEvent.Failed(_, err) =>
                      resultDeferred.complete(Left(err.message)).void.as(Behaviors.stopped)
                    case AgentEvent.Cancelled(_, reason) =>
                      resultDeferred.complete(Left(s"cancelled: $reason")).void.as(Behaviors.stopped)

                override def onSignal(ctx: ActorContext[AgentEvent], signal: SystemSignal): IO[Behavior[AgentEvent]] =
                  signal match
                    case SystemSignal.Terminated(_) =>
                      // agent 死了（AgentControl cancel 的 Stop / 任何未通知死亡）
                      // ——以 cancelled 完成 deferred，runner 走正常清理返回。
                      resultDeferred.complete(Left("cancelled")).void
                        .handleErrorWith(_ => IO.unit)
                        .as(Behaviors.stopped)
            )
          },
          s"bridge-ephemeral-${agentDef.name.take(10)}"
        )
        // Register in agentRegistry so WS events (askUser, permission) route correctly
        _ <- resources.agentRegistry.update(
          _ + (sessionId -> AgentRecord(
            sessionId,
            ref,
            AgentKind.Ephemeral,
            msg.callerRootSessionId.getOrElse(sessionId),
            parentSessionId = msg.callerSessionId.getOrElse(""),
            startedAt = System.currentTimeMillis(),
            lastActivityMs = System.currentTimeMillis()
          ))
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
          s"[Agent '${agentDef.name}' completed]\n$output",
          fromUser = false // ② 服务端注入（ephemeral agent 完成回执），不是真人输入
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
