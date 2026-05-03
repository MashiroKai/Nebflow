package nebflow.agent

import cats.effect.IO
import cats.effect.unsafe.implicits.global
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

  def apply(wsConnId: String, resources: SharedResources): Behavior[SessionCommand] =
    Behaviors
      .supervise(
        Behaviors.setup[SessionCommand] { context =>
          val replyAdapter = context.messageAdapter[AgentEvent] {
            case AgentEvent.Completed(sessionId, finalMessages) =>
              SessionCommand.AgentTurnCompleted(sessionId, finalMessages)
            case AgentEvent.Failed(sessionId, error) =>
              SessionCommand.AgentTurnFailed(sessionId, error)
          }
          active(resources, wsConnId, SessionData(), context, replyAdapter)
        }
      )
      .onFailure[Exception](
        org.apache.pekko.actor.typed.SupervisorStrategy.restart.withLimit(3, java.time.Duration.ofMinutes(1))
      )

  private def active(
    resources: SharedResources,
    wsConnId: String,
    data: SessionData,
    ctx: org.apache.pekko.actor.typed.scaladsl.ActorContext[SessionCommand],
    replyAdapter: ActorRef[AgentEvent]
  ): Behavior[SessionCommand] =
    Behaviors.receiveMessage:

      // --- Core: delegate to AgentActor ---
      case SessionCommand.UserMessage(text, blocks) =>
        val userMessage =
          if blocks.length == 1 && text.nonEmpty then Message(MessageRole.User, Left(text))
          else Message(MessageRole.User, Right(blocks))

        val agentIo = resources.sessionStore.getActiveId.flatMap { sessionId =>
          if data.agentStates.contains(sessionId) then
            resources
              .wsSend(
                io.circe.Json.obj(
                  "type" -> "error".asJson,
                  "message" -> "A response is already being generated for this session".asJson,
                  "sessionId" -> sessionId.asJson
                )
              ) *>
              resources
                .wsSend(
                  io.circe.Json.obj(
                    "type" -> "sessionBusy".asJson,
                    "sessionId" -> sessionId.asJson,
                    "busy" -> true.asJson
                  )
                )
                .void
          else
            for
              _ <- IO(
                logger.info(s"[${sessionTag(sessionId)}] starting AgentActor: ${text.take(60)}")
              )
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
            depth = 0,
            parentRef = None,
            sessionId = Some(sessionId),
            initialMessages = initialMessages
          ),
          s"agent-$sessionId-${System.currentTimeMillis()}"
        )
        ctx.watchWith(agentRef, SessionCommand.AgentTerminated(sessionId))
        agentRef ! AgentCommand.UserInput(text, Some(adapter))
        active(
          resources,
          wsConnId,
          data.copy(agentStates = data.agentStates + (sessionId -> AgentSessionState(agentRef))),
          ctx,
          replyAdapter
        )

      case SessionCommand.AgentTurnCompleted(sessionId, messages) =>
        resources.dispatcher.unsafeRunAndForget(
          for
            _ <- resources.sessionStore.saveMessagesForSession(sessionId, messages)
            _ <- resources.sessionStore.flushIndex
            _ <- sendSessionList(resources)
          yield ()
        )
        resources.dispatcher.unsafeRunAndForget(
          resources.wsSend(
            io.circe.Json.obj(
              "type" -> "sessionBusy".asJson,
              "sessionId" -> sessionId.asJson,
              "busy" -> false.asJson
            )
          )
        )
        active(resources, wsConnId, data.copy(agentStates = data.agentStates - sessionId), ctx, replyAdapter)

      case SessionCommand.AgentTurnFailed(sessionId, error) =>
        resources.dispatcher.unsafeRunAndForget(
          resources.wsSend(
            io.circe.Json.obj(
              "type" -> "error".asJson,
              "message" -> error.message.asJson,
              "sessionId" -> sessionId.asJson
            )
          ) *> resources.wsSend(
            io.circe.Json.obj(
              "type" -> "sessionBusy".asJson,
              "sessionId" -> sessionId.asJson,
              "busy" -> false.asJson
            )
          )
        )
        active(resources, wsConnId, data.copy(agentStates = data.agentStates - sessionId), ctx, replyAdapter)

      case SessionCommand.Terminate() =>
        data.agentStates.values.foreach(_.agentRef ! AgentCommand.Stop("session closing"))
        Behaviors.stopped

      case SessionCommand.Interrupt(sessionId) =>
        data.agentStates.get(sessionId) match
          case Some(agentState) =>
            agentState.agentRef ! AgentCommand.Interrupt()
            resources.dispatcher.unsafeRunAndForget(
              resources.wsSend(
                io.circe.Json.obj("type" -> "interrupted".asJson, "sessionId" -> sessionId.asJson)
              ) *> resources.wsSend(
                io.circe.Json.obj(
                  "type" -> "sessionBusy".asJson,
                  "sessionId" -> sessionId.asJson,
                  "busy" -> false.asJson
                )
              )
            )
            active(resources, wsConnId, data.copy(agentStates = data.agentStates - sessionId), ctx, replyAdapter)
          case None => Behaviors.same

      case SessionCommand.AgentTerminated(sessionId) =>
        if data.agentStates.contains(sessionId) then
          resources.dispatcher.unsafeRunAndForget(
            resources.wsSend(
              io.circe.Json.obj(
                "type" -> "sessionBusy".asJson,
                "sessionId" -> sessionId.asJson,
                "busy" -> false.asJson
              )
            )
          )
          active(resources, wsConnId, data.copy(agentStates = data.agentStates - sessionId), ctx, replyAdapter)
        else Behaviors.same

      // --- AskUser / Permission forwarded to active agent ---
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

      // --- Session management (no REPL cancellation!) ---

      case SessionCommand.SwitchSession(sessionId, replyTo) =>
        resources.dispatcher.unsafeRunAndForget(
          resources.sessionStore.switchSession(sessionId).attempt.flatMap {
            case Right(_) =>
              sendSessionList(resources) *>
                IO(replyTo ! SwitchResult(true))
            case Left(e) =>
              IO(replyTo ! SwitchResult(false, error = Some(e.getMessage)))
          }
        )
        Behaviors.same

      case SessionCommand.SendSessionList() =>
        resources.dispatcher.unsafeRunAndForget(sendSessionList(resources))
        Behaviors.same

      case SessionCommand.CreateSessionCmd(name, replyTo) =>
        resources.dispatcher.unsafeRunAndForget(
          resources.sessionStore.createSession(name).flatMap { meta =>
            resources.sessionStore.switchSession(meta.id) *>
              sendSessionList(resources) *>
              IO(replyTo ! SessionRef(ctx.self))
          }
        )
        Behaviors.same

      case SessionCommand.DeleteSession(sessionId, replyTo) =>
        resources.dispatcher.unsafeRunAndForget(
          resources.sessionStore.deleteSession(sessionId).attempt.flatMap {
            case Right(_) => sendSessionList(resources) *> IO(replyTo ! DeleteResult(true))
            case Left(e) => IO(replyTo ! DeleteResult(false, Some(e.getMessage)))
          }
        )
        Behaviors.same

      case SessionCommand.RenameSession(sessionId, newName, replyTo) =>
        resources.dispatcher.unsafeRunAndForget(
          resources.sessionStore.renameSession(sessionId, newName) *>
            sendSessionList(resources) *>
            IO(replyTo ! true)
        )
        Behaviors.same

      case SessionCommand.ListSessions(replyTo) =>
        resources.dispatcher.unsafeRunAndForget(
          for
            sessions <- resources.sessionStore.listSessions
            activeId <- resources.sessionStore.getActiveId
          yield replyTo ! SessionList(sessions, activeId)
        )
        Behaviors.same

      case SessionCommand.SetThinking(enabled) =>
        resources.dispatcher.unsafeRunAndForget(
          resources.thinkingModeRef.set(
            if enabled then Some(io.circe.Json.obj("type" -> "enabled".asJson, "budget_tokens" -> 16000.asJson))
            else None
          )
        )
        Behaviors.same

      case SessionCommand.SetPolicy(policy) =>
        resources.dispatcher.unsafeRunAndForget(
          resources.permState.setPolicy(PermissionPolicy.fromString(policy))
        )
        Behaviors.same

      case SessionCommand.ClearChat() =>
        resources.dispatcher.unsafeRunAndForget(
          resources.sessionStore.setActiveMessages(Nil) *>
            resources.reminderStateRef.update(_.copy(sessionStarted = false, highestPressureLevel = 0))
        )
        Behaviors.same

      // --- Agent management ---

      case SessionCommand.ListAgents(replyTo) =>
        resources.dispatcher.unsafeRunAndForget(
          resources.agentLibrary.loadAll().map { defs =>
            val agents = defs.values
              .map { d =>
                AgentInfo(d.name, d.description, d.tools, d.subagents.map(_.name))
              }
              .toList
              .sortBy(_.name)
            replyTo ! AgentListResp(agents)
          }
        )
        Behaviors.same

      case SessionCommand.GetAgentConfig(name, replyTo) =>
        resources.dispatcher.unsafeRunAndForget(
          resources.agentLibrary.get(name).map {
            case Some(defn) =>
              val configJson =
                if defn.configPath.nonEmpty && os.exists(os.Path(defn.configPath) / "agent.json")
                then os.read(os.Path(defn.configPath) / "agent.json")
                else ""
              replyTo ! AgentConfigResp(name, configJson, defn.systemPrompt)
            case None =>
              replyTo ! AgentConfigResp(name, "", "")
          }
        )
        Behaviors.same

      case SessionCommand.CreateAgent(name, configJson, systemMd, replyTo) =>
        if !isValidAgentName(name) then
          replyTo ! AgentCreatedResp("")
          resources.dispatcher.unsafeRunAndForget(
            resources
              .wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> s"Invalid agent name: $name".asJson))
              .void
          )
        else
          resources.dispatcher.unsafeRunAndForget(
            IO.blocking {
              val dir = AgentLibrary.defaultDir / name
              os.makeDir.all(dir)
              os.write.over(dir / "agent.json", configJson)
              os.write.over(dir / "system.md", systemMd)
            }.attempt
              .flatMap {
                case Right(_) => IO(replyTo ! AgentCreatedResp(name))
                case Left(e) =>
                  resources
                    .wsSend(
                      io.circe.Json
                        .obj("type" -> "error".asJson, "message" -> s"Create agent failed: ${e.getMessage}".asJson)
                    )
                    .void *> IO(replyTo ! AgentCreatedResp(""))
              }
          )
        end if
        Behaviors.same

      case SessionCommand.UpdateAgent(name, configJson, systemMd, replyTo) =>
        if !isValidAgentName(name) then
          replyTo ! AgentUpdatedResp("")
          resources.dispatcher.unsafeRunAndForget(
            resources
              .wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> s"Invalid agent name: $name".asJson))
              .void
          )
        else
          resources.dispatcher.unsafeRunAndForget(
            IO.blocking {
              val dir = AgentLibrary.defaultDir / name
              if os.exists(dir) then
                os.write.over(dir / "agent.json", configJson)
                os.write.over(dir / "system.md", systemMd)
            }.attempt
              .flatMap {
                case Right(_) => IO(replyTo ! AgentUpdatedResp(name))
                case Left(e) =>
                  resources
                    .wsSend(
                      io.circe.Json
                        .obj("type" -> "error".asJson, "message" -> s"Update agent failed: ${e.getMessage}".asJson)
                    )
                    .void *> IO(replyTo ! AgentUpdatedResp(""))
              }
          )
        end if
        Behaviors.same

      case SessionCommand.CreateAgentSession(agentName, replyTo) =>
        resources.dispatcher.unsafeRunAndForget(
          for
            defnOpt <- resources.agentLibrary.get(agentName)
            defn <- IO.fromOption(defnOpt)(new RuntimeException(s"Agent not found: $agentName"))
            meta <- resources.sessionStore.createSession(s"Agent: ${defn.name}", agentName = Some(agentName))
            _ <- resources.sessionStore.switchSession(meta.id)
            _ <- sendSessionList(resources)
            _ <- IO(replyTo ! SessionRef(ctx.self))
          yield ()
        )
        Behaviors.same

      case SessionCommand.GetConfig(replyTo) =>
        resources.dispatcher.unsafeRunAndForget(
          IO.blocking {
            val configPath = os.home / ".nebflow" / "nebflow.json"
            val content = if os.exists(configPath) then os.read(configPath) else "{}"
            // Redact sensitive fields before sending to client
            val redacted =
              content.replaceAll("(?i)\"(api[_-]?key|secret|token|password)\"\\s*:\\s*\"[^\"]*\"", "\"$1\":\"***\"")
            replyTo ! ConfigDataResp(redacted)
          }
        )
        Behaviors.same

      case SessionCommand.UpdateConfig(config, replyTo) =>
        resources.dispatcher.unsafeRunAndForget(
          IO.blocking {
            val configPath = os.home / ".nebflow" / "nebflow.json"
            // Read existing config, merge with new values (preserving redacted secrets)
            val existing = if os.exists(configPath) then os.read(configPath) else "{}"
            val merged = mergeConfig(existing, config)
            os.write.over(configPath, merged, createFolders = true)
            replyTo ! ConfigUpdatedResp(true)
          }.handleErrorWith { e =>
            resources
              .wsSend(
                io.circe.Json
                  .obj("type" -> "error".asJson, "message" -> s"Config update failed: ${e.getMessage}".asJson)
              )
              .void *> IO(replyTo ! ConfigUpdatedResp(false))
          }
        )
        Behaviors.same

  // --- Helpers ---

  private val AgentNameRegex = "[a-zA-Z0-9_-]+".r

  private def isValidAgentName(name: String): Boolean =
    name.nonEmpty && AgentNameRegex.matches(name)

  /**
   * Merge new config into existing, preserving secret values that were redacted as "***".
   * For each leaf string value: if the new value is "***", keep the existing value.
   */
  private def mergeConfig(existing: String, incoming: String): String =
    import io.circe.parser.parse
    import io.circe.{Json, JsonObject}
    def merge(existing: Json, incoming: Json): Json =
      (existing.asObject, incoming.asObject) match
        case (Some(eObj), Some(iObj)) =>
          val merged = iObj.toMap.map { case (key, iVal) =>
            val eVal = eObj(key).getOrElse(Json.Null)
            key -> merge(eVal, iVal)
          }
          // Preserve keys from existing that are not in incoming
          val withExisting = eObj.toMap.filterNot { case (k, _) => merged.contains(k) }
          Json.fromFields(merged ++ withExisting)
        case _ =>
          // Leaf value: if incoming is "***", keep existing
          incoming.asString match
            case Some(s) if s == "***" => existing
            case _ => incoming
    (parse(existing), parse(incoming)) match
      case (Right(eJson), Right(iJson)) => merge(eJson, iJson).spaces2
      case _ => incoming // fallback: write incoming as-is if parse fails

  /** Session identifier for log prefix (avoids blocking IO in actor context) */
  private def sessionTag(sessionId: String): String = sessionId.take(8)

  private def sendSessionList(resources: SharedResources): IO[Unit] =
    for
      sessions <- resources.sessionStore.listSessions
      activeId <- resources.sessionStore.getActiveId
      _ <- resources.wsSend(
        io.circe.Json.obj(
          "type" -> "sessionList".asJson,
          "sessions" -> sessions.asJson,
          "activeId" -> activeId.asJson
        )
      )
    yield ()

end SessionActor
