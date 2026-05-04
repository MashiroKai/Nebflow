package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import nebflow.core.*
import nebflow.shared.*
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

object SessionActor:

  private val logger = NebflowLogger.forName("nebflow.session")

  private case class AgentSessionState(
    agentRef: ActorRef[AgentCommand]
  )

  private case class SessionData(
    agentStates: Map[String, AgentSessionState] = Map.empty
  )

  def apply(
    wsConnId: String,
    resources: SharedResources,
    wsSend: io.circe.Json => IO[Unit],
    readTracker: nebflow.core.tools.ReadTracker
  ): Behavior[SessionCommand] =
    Behaviors
      .supervise(
        Behaviors.setup[SessionCommand] { context =>
          val replyAdapter = context.messageAdapter[AgentEvent] {
            case AgentEvent.Completed(sessionId, finalMessages) =>
              SessionCommand.AgentTurnCompleted(sessionId, finalMessages)
            case AgentEvent.Failed(sessionId, error) =>
              SessionCommand.AgentTurnFailed(sessionId, error)
          }
          active(resources, wsSend, wsConnId, SessionData(), context, replyAdapter, readTracker)
        }
      )
      .onFailure[Exception](
        org.apache.pekko.actor.typed.SupervisorStrategy.restart.withLimit(3, java.time.Duration.ofMinutes(1))
      )

  private def active(
    resources: SharedResources,
    wsSend: io.circe.Json => IO[Unit],
    wsConnId: String,
    data: SessionData,
    ctx: org.apache.pekko.actor.typed.scaladsl.ActorContext[SessionCommand],
    replyAdapter: ActorRef[AgentEvent],
    readTracker: nebflow.core.tools.ReadTracker
  ): Behavior[SessionCommand] =
    Behaviors.receiveMessage:

      case SessionCommand.UserMessage(text, blocks) =>
        val userMessage =
          if blocks.length == 1 && text.nonEmpty then Message(MessageRole.User, Left(text))
          else Message(MessageRole.User, Right(blocks))

        val agentIo = resources.sessionStore.getActiveId.flatMap { sessionId =>
          if data.agentStates.contains(sessionId) then
            wsSend(
              io.circe.Json.obj(
                "type" -> "error".asJson,
                "message" -> "A response is already being generated for this session".asJson,
                "sessionId" -> sessionId.asJson
              )
            ) *>
              wsSend(
                io.circe.Json.obj(
                  "type" -> "sessionBusy".asJson,
                  "sessionId" -> sessionId.asJson,
                  "busy" -> true.asJson
                )
              ).void
          else
            for
              _ <- IO(logger.info(s"[${sessionTag(sessionId)}] starting AgentActor: ${text.take(60)}"))
              history <- resources.sessionStore.loadMessagesForSession(sessionId)
              metaOpt <- resources.sessionStore.getSessionMeta(sessionId)
              agentDef <- metaOpt.flatMap(_.agentName) match
                case Some(agentName) =>
                  resources.agentLibrary.get(agentName).flatMap {
                    case Some(defn) => IO.pure(defn)
                    case None =>
                      resources.agentLibrary.get("default").flatMap {
                        case Some(d) => IO.pure(d)
                        case None =>
                          IO.raiseError(new RuntimeException(s"Agent not found: $agentName, and no default agent"))
                      }
                  }
                case None =>
                  resources.agentLibrary.get("default").flatMap {
                    case Some(d) => IO.pure(d)
                    case None => IO.raiseError(new RuntimeException("No default agent available"))
                  }
              initialMessages = history :+ userMessage
            yield ctx.self ! SessionCommand.SpawnAgent(sessionId, agentDef, initialMessages, text, replyAdapter)
        }

        resources.dispatcher.unsafeRunAndForget(agentIo)
        Behaviors.same

      case SessionCommand.SpawnAgent(sessionId, agentDef, initialMessages, text, adapter) =>
        val agentRef = ctx.spawn(
          AgentActor(
            agentDef,
            resources,
            wsSend,
            depth = 0,
            parentRef = None,
            sessionId = Some(sessionId),
            initialMessages = initialMessages,
            readTracker = Some(readTracker)
          ),
          s"agent-$sessionId-${System.currentTimeMillis()}"
        )
        ctx.watchWith(agentRef, SessionCommand.AgentTerminated(sessionId))
        agentRef ! AgentCommand.UserInput(text, Some(adapter))
        active(
          resources,
          wsSend,
          wsConnId,
          data.copy(agentStates = data.agentStates + (sessionId -> AgentSessionState(agentRef))),
          ctx,
          replyAdapter,
          readTracker
        )

      case SessionCommand.AgentTurnCompleted(sessionId, messages) =>
        resources.dispatcher.unsafeRunAndForget(
          for
            _ <- resources.sessionStore.saveMessagesForSession(sessionId, messages)
            _ <- resources.sessionStore.flushIndex
          yield ()
        )
        resources.dispatcher.unsafeRunAndForget(
          wsSend(
            io.circe.Json.obj(
              "type" -> "sessionBusy".asJson,
              "sessionId" -> sessionId.asJson,
              "busy" -> false.asJson
            )
          )
        )
        active(resources, wsSend, wsConnId, data.copy(agentStates = data.agentStates - sessionId), ctx, replyAdapter, readTracker)

      case SessionCommand.AgentTurnFailed(sessionId, error) =>
        resources.dispatcher.unsafeRunAndForget(
          wsSend(
            io.circe.Json.obj(
              "type" -> "error".asJson,
              "message" -> error.message.asJson,
              "sessionId" -> sessionId.asJson
            )
          ) *> wsSend(
            io.circe.Json.obj(
              "type" -> "sessionBusy".asJson,
              "sessionId" -> sessionId.asJson,
              "busy" -> false.asJson
            )
          )
        )
        active(resources, wsSend, wsConnId, data.copy(agentStates = data.agentStates - sessionId), ctx, replyAdapter, readTracker)

      case SessionCommand.Terminate() =>
        data.agentStates.values.foreach(_.agentRef ! AgentCommand.Stop("session closing"))
        Behaviors.stopped

      case SessionCommand.Interrupt(sessionId) =>
        data.agentStates.get(sessionId) match
          case Some(agentState) =>
            agentState.agentRef ! AgentCommand.Interrupt()
            resources.dispatcher.unsafeRunAndForget(
              wsSend(
                io.circe.Json.obj("type" -> "interrupted".asJson, "sessionId" -> sessionId.asJson)
              ) *> wsSend(
                io.circe.Json.obj(
                  "type" -> "sessionBusy".asJson,
                  "sessionId" -> sessionId.asJson,
                  "busy" -> false.asJson
                )
              )
            )
            active(resources, wsSend, wsConnId, data.copy(agentStates = data.agentStates - sessionId), ctx, replyAdapter, readTracker)
          case None => Behaviors.same

      case SessionCommand.AgentTerminated(sessionId) =>
        if data.agentStates.contains(sessionId) then
          resources.dispatcher.unsafeRunAndForget(
            wsSend(
              io.circe.Json.obj(
                "type" -> "sessionBusy".asJson,
                "sessionId" -> sessionId.asJson,
                "busy" -> false.asJson
              )
            )
          )
          active(resources, wsSend, wsConnId, data.copy(agentStates = data.agentStates - sessionId), ctx, replyAdapter, readTracker)
        else Behaviors.same

      case SessionCommand.AskUserResponse(_, answers) =>
        data.agentStates.headOption.foreach { case (_, agentState) =>
          agentState.agentRef ! AgentCommand.UserAnswered(answers)
        }
        Behaviors.same

      case SessionCommand.PermissionResponse(_, approved) =>
        data.agentStates.headOption.foreach { case (_, agentState) =>
          agentState.agentRef ! AgentCommand.PermissionAnswered(approved)
        }
        Behaviors.same

  private def sessionTag(sessionId: String): String = sessionId.take(8)

end SessionActor
