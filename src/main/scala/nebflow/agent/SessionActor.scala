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

  private case class ReplState(
    fiber: cats.effect.Fiber[IO, Throwable, Unit],
    replUi: SessionReplUi
  )

  private case class SessionData(
    replStates: Map[String, ReplState] = Map.empty
  )

  def apply(wsConnId: String, resources: SharedResources): Behavior[SessionCommand] =
    Behaviors.supervise(
      Behaviors.setup[SessionCommand] { context =>
        active(resources, wsConnId, SessionData(), context)
      }
    ).onFailure[Exception](
      org.apache.pekko.actor.typed.SupervisorStrategy.restart.withLimit(3, java.time.Duration.ofMinutes(1))
    )

  private def active(
    resources: SharedResources,
    wsConnId: String,
    data: SessionData,
    ctx: org.apache.pekko.actor.typed.scaladsl.ActorContext[SessionCommand]
  ): Behavior[SessionCommand] =
    Behaviors.receiveMessage:

      // --- Core: run REPL for this session ---
      case SessionCommand.UserMessage(text, blocks) =>
        val userMessage =
          if blocks.length == 1 && text.nonEmpty then Message(MessageRole.User, Left(text))
          else Message(MessageRole.User, Right(blocks))

        // Capture sessionId BEFORE starting REPL so events are tagged correctly
        val replIo = resources.sessionStore.getActiveId.flatMap { sessionId =>
          // Same-session duplicate rejection
          if data.replStates.contains(sessionId) then
            resources.wsSend(io.circe.Json.obj(
              "type" -> "error".asJson,
              "message" -> "A response is already being generated for this session".asJson,
              "sessionId" -> sessionId.asJson
            )).void
          else
            val replUi = new SessionReplUi(resources.wsSend, sessionId, resources.askSemaphore)

            val io = for
              _ <- IO(logger.info(s"[${sessionTag(resources.sessionStore, sessionId)}] starting REPL: ${text.take(60)}"))
              history <- resources.sessionStore.loadMessagesForSession(sessionId)
              thinking <- resources.thinkingModeRef.get
              // Load agent config if session is associated with an agent
              metaOpt <- resources.sessionStore.getSessionMeta(sessionId)
              agentCfg <- metaOpt.flatMap(_.agentName) match
                case Some(agentName) => resources.agentLibrary.get(agentName).map(_.map(d => (d.systemPrompt, d.tools)))
                case None => IO.pure(None)
              updated <- Repl.runRepl(
                userMessage = userMessage,
                llm = resources.llm,
                projectRoot = resources.projectRoot.toString,
                initialMessages = history,
                store = replUi,
                onToolRound = Some((msgs: List[Message]) =>
                  resources.sessionStore.saveMessagesForSession(sessionId, msgs)
                ),
                silent = true,
                thinkingMode = thinking,
                permState = Some(resources.permState),
                contextWindow = resources.contextWindow,
                reminderStateRef = Some(resources.reminderStateRef),
                fileChangeTracker = Some(resources.fileChangeTracker),
                skillDiscovery = resources.skillDiscovery,
                userText = Some(text),
                sessionStore = Some(resources.sessionStore),
                systemPromptOverride = agentCfg.map(_._1),
                toolFilter = agentCfg.map(_._2).filter(_.nonEmpty),
                sessionTag = Some(sessionTag(resources.sessionStore, sessionId)),
                sessionActorRef = Some(ctx.self)
              )
              _ <- resources.sessionStore.saveMessagesForSession(sessionId, updated)
              _ <- resources.sessionStore.flushIndex
              _ <- replUi.emitDone()
              _ <- sendSessionList(resources)
              _ <- IO(logger.info(s"[${sessionTag(resources.sessionStore, sessionId)}] REPL completed"))
            yield ()

            val safeIo = io.handleErrorWith { e =>
              val userMsg = e match
                case fe: nebflow.llm.FallbackExhaustedError =>
                  val attemptSummaries = fe.attempts.map(a =>
                    s"${a.providerId}/${a.model}: ${a.reason.map(_.toString).getOrElse("unknown")}"
                  )
                  NebflowError.toUserMessage(NebflowError.LlmFailed(fe.getMessage, attemptSummaries))
                case _ =>
                  NebflowError.toUserMessage(NebflowError.Internal(Option(e.getMessage).getOrElse("Unknown error")))
              logger.error(s"[${sessionTag(resources.sessionStore, sessionId)}] REPL error: ${e.getMessage}", e) *>
                replUi.sendError(userMsg) *>
                replUi.emitDone()
            }.guarantee(
              // Always notify actor that REPL is done, even on cancel
              IO(ctx.self ! SessionCommand.ReplFiberDone(sessionId))
            )

            safeIo.start.map { fiber =>
              ctx.self ! SessionCommand.ReplFiberStarted(sessionId, fiber, replUi)
            }
        }

        resources.dispatcher.unsafeRunAndForget(replIo)
        Behaviors.same

      case SessionCommand.ReplFiberStarted(sessionId, fiber, replUi) =>
        active(resources, wsConnId, data.copy(
          replStates = data.replStates + (sessionId -> ReplState(fiber, replUi))
        ), ctx)

      case SessionCommand.ReplFiberDone(sessionId) =>
        active(resources, wsConnId, data.copy(
          replStates = data.replStates - sessionId
        ), ctx)

      case SessionCommand.Interrupt(sessionId) =>
        data.replStates.get(sessionId) match
          case Some(replState) =>
            resources.dispatcher.unsafeRunAndForget(
              replState.fiber.cancel *> replState.replUi.emitInterrupted()
            )
            active(resources, wsConnId, data.copy(
              replStates = data.replStates - sessionId
            ), ctx)
          case None =>
            Behaviors.same

      case SessionCommand.StreamToClient(event) =>
        resources.dispatcher.unsafeRunAndForget(
          resources.wsSend(event.toJson("root"))
        )
        Behaviors.same

      case SessionCommand.AgentTerminated(agentId) =>
        Behaviors.same

      case SessionCommand.AskUserResponse(requestId, answers) =>
        // Forward to all running REPLs — only the one with matching pending Deferred will process
        data.replStates.values.foreach { replState =>
          resources.dispatcher.unsafeRunAndForget(replState.replUi.answerAskUser(answers))
        }
        Behaviors.same

      case SessionCommand.PermissionResponse(requestId, approved) =>
        data.replStates.values.foreach { replState =>
          resources.dispatcher.unsafeRunAndForget(replState.replUi.answerPermission(approved))
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
          resources.thinkingModeRef.set(if enabled then Some(io.circe.Json.obj("type" -> "enabled".asJson)) else None)
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
            val agents = defs.values.map { d =>
              AgentInfo(d.name, d.description, d.tools, d.subagents.map(_.name))
            }.toList.sortBy(_.name)
            replyTo ! AgentListResp(agents)
          }
        )
        Behaviors.same

      case SessionCommand.GetAgentConfig(name, replyTo) =>
        resources.dispatcher.unsafeRunAndForget(
          resources.agentLibrary.get(name).map {
            case Some(defn) =>
              val yaml = if defn.yamlPath.nonEmpty && os.exists(os.Path(defn.yamlPath) / "agent.yaml")
                then os.read(os.Path(defn.yamlPath) / "agent.yaml") else ""
              replyTo ! AgentConfigResp(name, yaml, defn.systemPrompt)
            case None =>
              replyTo ! AgentConfigResp(name, "", "")
          }
        )
        Behaviors.same

      case SessionCommand.CreateAgent(name, yaml, systemMd, replyTo) =>
        if !isValidAgentName(name) then
          replyTo ! AgentCreatedResp("")
          resources.dispatcher.unsafeRunAndForget(
            resources.wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> s"Invalid agent name: $name".asJson)).void
          )
        else
          resources.dispatcher.unsafeRunAndForget(
            IO.blocking {
              val dir = AgentLibrary.defaultDir / name
              os.makeDir.all(dir)
              os.write.over(dir / "agent.yaml", yaml)
              os.write.over(dir / "system.md", systemMd)
            }.attempt.flatMap {
              case Right(_) => IO(replyTo ! AgentCreatedResp(name))
              case Left(e) =>
                resources.wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> s"Create agent failed: ${e.getMessage}".asJson))
                  .void *> IO(replyTo ! AgentCreatedResp(""))
            }
          )
        Behaviors.same

      case SessionCommand.UpdateAgent(name, yaml, systemMd, replyTo) =>
        if !isValidAgentName(name) then
          replyTo ! AgentUpdatedResp("")
          resources.dispatcher.unsafeRunAndForget(
            resources.wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> s"Invalid agent name: $name".asJson)).void
          )
        else
          resources.dispatcher.unsafeRunAndForget(
            IO.blocking {
              val dir = AgentLibrary.defaultDir / name
              if os.exists(dir) then
                os.write.over(dir / "agent.yaml", yaml)
                os.write.over(dir / "system.md", systemMd)
            }.attempt.flatMap {
              case Right(_) => IO(replyTo ! AgentUpdatedResp(name))
              case Left(e) =>
                resources.wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> s"Update agent failed: ${e.getMessage}".asJson))
                  .void *> IO(replyTo ! AgentUpdatedResp(""))
            }
          )
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
            val redacted = content.replaceAll("(?i)\"(api[_-]?key|secret|token|password)\"\\s*:\\s*\"[^\"]*\"", "\"$1\":\"***\"")
            replyTo ! ConfigDataResp(redacted)
          }
        )
        Behaviors.same

      case SessionCommand.UpdateConfig(config, replyTo) =>
        resources.dispatcher.unsafeRunAndForget(
          IO.blocking {
            val configPath = os.home / ".nebflow" / "nebflow.json"
            os.write.over(configPath, config, createFolders = true)
            replyTo ! ConfigUpdatedResp(true)
          }.handleErrorWith { e =>
            resources.wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> s"Config update failed: ${e.getMessage}".asJson))
              .void *> IO(replyTo ! ConfigUpdatedResp(false))
          }
        )
        Behaviors.same

  // --- Helpers ---

  private val AgentNameRegex = "[a-zA-Z0-9_-]+".r

  private def isValidAgentName(name: String): Boolean =
    name.nonEmpty && AgentNameRegex.matches(name)

  /** Look up session name for log prefix */
  private def sessionTag(store: nebflow.gateway.SessionStore, sessionId: String): String =
    store.listSessions.attempt.unsafeRunSync() match
      case Right(sessions) => sessions.find(_.id == sessionId).map(_.name).getOrElse(sessionId.take(8))
      case Left(_) => sessionId.take(8)

  private def sendSessionList(resources: SharedResources): IO[Unit] =
    for
      sessions <- resources.sessionStore.listSessions
      activeId <- resources.sessionStore.getActiveId
      _ <- resources.wsSend(io.circe.Json.obj(
        "type" -> "sessionList".asJson,
        "sessions" -> sessions.asJson,
        "activeId" -> activeId.asJson
      ))
    yield ()

end SessionActor
