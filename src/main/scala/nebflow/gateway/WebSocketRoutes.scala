package nebflow.gateway

import cats.effect.std.{Dispatcher, Queue}
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.{Pipe, Stream}
import io.circe.parser.parse
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.agent.*
import nebflow.core.*
import nebflow.core.mcp.McpManager
import nebflow.core.tools.{ToolContext, ToolRegistry}
import nebflow.llm.{Config, NebflowServiceConfig, ThinkingConfig}
import nebflow.service.*
import nebflow.shared.*
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import org.http4s.{HttpRoutes, StaticFile}

import scala.concurrent.duration.*

class WebSocketRoutes(
  wsb: WebSocketBuilder2[IO],
  sessionService: SessionService,
  agentService: AgentService,
  configService: ConfigService.type,
  configRef: Ref[IO, NebflowServiceConfig],
  rateLimiter: RateLimiter,
  token: String,
  fileChangeTracker: FileChangeTracker,
  sessionStore: SessionStore,
  wsHub: WsHub,
  actorSystem: ActorSystem[Nothing],
  contextWindow: Int = Defaults.ContextWindow,
  sharedResources: SharedResources,
  mcpManager: McpManager
):
  private val logger = NebflowLogger.forName("nebflow.ws")

  /** Map of sessionId -> root AgentActor ref. Concurrent-safe via Ref. */
  private val rootAgents: Ref[IO, Map[String, ActorRef[AgentCommand]]] =
    Ref.unsafe(Map.empty)

  /** Get or create a root AgentActor for the given session. Idempotent. */
  private def ensureRootAgent(sessionId: String): IO[ActorRef[AgentCommand]] =
    rootAgents.get.flatMap { agents =>
      agents.get(sessionId) match
        case Some(ref) => IO.pure(ref)
        case None =>
          val broadcastWsSend = (json: io.circe.Json) => wsHub.broadcast(json)
          val recordingWsSend = makeRecordingWsSend(sessionId, broadcastWsSend)
          val agentIo = for
            history <- sharedResources.sessionStore.loadMessagesForSession(sessionId)
            metaOpt <- sharedResources.sessionStore.getSessionMeta(sessionId)
            agentDef <- metaOpt.flatMap(_.agentName) match
              case Some(agentName) =>
                sharedResources.agentLibrary.get(agentName).flatMap {
                  case Some(defn) => IO.pure(defn)
                  case None =>
                    sharedResources.agentLibrary.get("Nebula").flatMap {
                      case Some(d) => IO.pure(d)
                      case None =>
                        IO.raiseError(new RuntimeException(s"Agent not found: $agentName, and no default agent"))
                    }
                }
              case None =>
                sharedResources.agentLibrary.get("Nebula").flatMap {
                  case Some(d) => IO.pure(d)
                  case None => IO.raiseError(new RuntimeException("No default agent available"))
                }
            readTracker <- nebflow.core.tools.ReadTracker.create
            fileHistory <- nebflow.core.tools.FileHistory.create()
            modelOverrides <- sharedResources.sessionModelOverrides.get
            contextWindow = modelOverrides.get(sessionId).map(_.contextWindow).getOrElse(sharedResources.contextWindow)
          yield actorSystem.systemActorOf(
            AgentActor(
              agentDef,
              sharedResources,
              recordingWsSend,
              depth = 0,
              parentRef = None,
              sessionId = Some(sessionId),
              sessionName = metaOpt.map(_.name),
              initialMessages = history,
              readTracker = Some(readTracker),
              fileHistory = Some(fileHistory),
              contextWindow = contextWindow
            ),
            s"agent-$sessionId"
          )
          agentIo.flatMap { ref =>
            rootAgents.update(_ + (sessionId -> ref)).as(ref)
          }
    }

  /** Stop and remove the root AgentActor for a session. */
  private def removeRootAgent(sessionId: String): IO[Unit] =
    rootAgents.modify { agents =>
      agents.get(sessionId) match
        case Some(ref) =>
          ref ! AgentCommand.Stop(s"session $sessionId deleted")
          (agents - sessionId, IO.unit)
        case None => (agents, IO.unit)
    }.flatten

  /** Route a message to the root agent of a specific session. Discards if sessionId is empty. */
  private def routeToAgent(sessionId: String)(f: ActorRef[AgentCommand] => IO[Unit]): IO[Unit] =
    if sessionId.nonEmpty then
      ensureRootAgent(sessionId).flatMap(f).handleErrorWith { e =>
        logger.warn(s"Failed to route message to agent for session $sessionId: ${e.getMessage}")
      }
    else logger.warn("Dropping message: no sessionId provided") *> IO.unit

  /**
   * Public API for external sources (e.g. Feishu bridge) to inject a user message
   * into a session's agent. Reuses the same logic as WebSocket user messages:
   * rate limiting, UiMessage recording, agent routing.
   */
  def handleBridgeMessage(sessionId: String, content: String, senderId: Option[String] = None): IO[Unit] =
    if content.isEmpty || sessionId.isEmpty then IO.unit
    else
      rateLimiter.check("bridge").flatMap { allowed =>
        if !allowed then logger.warn("Bridge rate limit exceeded")
        else
          val source = senderId.map(id => s" [via bridge:$id]").getOrElse(" [via bridge]")
          logger.info(s"Bridge message for session $sessionId: ${content.take(60)}...$source") *>
            // Record as UiMessage
            sharedResources.sessionStore
              .appendUiMessages(sessionId, List(UiMessage.User(content, Nil)))
              .handleErrorWith(e => IO(logger.warn(s"Failed to record bridge UiMessage: ${e.getMessage}"))) *>
            // Push to frontend in real-time so it shows without switching sessions
            wsHub.broadcast(
              io.circe.Json.obj(
                "type" -> "bridgeUser".asJson,
                "sessionId" -> sessionId.asJson,
                "text" -> content.asJson
              )
            ) *>
            routeToAgent(sessionId)(ref =>
              IO(ref ! AgentCommand.UserInput(content, None, None, Some(List(ContentBlock.Text(content)))))
            )
      }

  /**
   * Public API for bridge card-action callbacks to send specific AgentCommands
   * (e.g. PermissionAnswered, UserAnswered) to a session's agent.
   */
  def handleBridgeAgentCommand(sessionId: String, command: AgentCommand): IO[Unit] =
    if sessionId.isEmpty then IO.unit
    else routeToAgent(sessionId)(ref => IO(ref ! command))

  def routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "ws" =>
      val cookieToken = req.cookies.find(_.name == "nebflow_token").map(_.content).getOrElse("")
      val provided = if cookieToken.nonEmpty then cookieToken else req.params.get("token").getOrElse("")
      if Auth.validateToken(provided, token) then
        for
          outbound <- Queue.unbounded[IO, WebSocketFrame]

          perConnWsSend = (json: io.circe.Json) => outbound.offer(WebSocketFrame.Text(json.noSpaces))
          hubConnId <- wsHub.register(perConnWsSend)

          receivePipe: Pipe[IO, WebSocketFrame, Unit] = _.evalMap {
            case WebSocketFrame.Text(text, _) =>
              handleMessage(text, perConnWsSend)
            case _ => IO.unit
          }.onFinalize(
            wsHub.unregister(hubConnId)
          )

          sendStream = Stream.fromQueueUnterminated(outbound)
          _ <- logger.info("WebSocket client connected")
          thinkingCfg <- sharedResources.thinkingConfigRef.get
          toolsList = ToolRegistry.userConfigurableTools.map(t =>
            io.circe.Json.obj("name" -> t.name.asJson, "description" -> t.description.asJson)
          )
          mcpServers <- mcpManager.listServers.map(_.map { case (id, enabled) =>
            io.circe.Json.obj("id" -> id.asJson, "enabled" -> enabled.asJson)
          })
          _ <- outbound.offer(
            WebSocketFrame.Text(
              io.circe.Json
                .obj(
                  "type" -> "serverConfig".asJson,
                  "streamTimeoutMs" -> (Defaults.StreamTimeoutSec.toLong * 1000).asJson,
                  "version" -> nebflow.Version.string.asJson,
                  "thinking" -> thinkingCfg.asJson,
                  "tools" -> toolsList.asJson,
                  "mcpServers" -> mcpServers.asJson
                )
                .noSpaces
            )
          )
          activeMeta <- sessionStore.getActiveMeta
          agentName = activeMeta.flatMap(_.agentName).getOrElse("Nebula")
          _ <- sessionService.sendSessionList(perConnWsSend, agentName)
          ws <- wsb.build(sendStream, receivePipe)
        yield ws
      else
        logger.warn(
          s"WebSocket auth failed from ${req.remoteAddr.getOrElse("unknown")}"
        ) *>
          Forbidden("Invalid token")
      end if

    // --- Callback endpoint (外→内) ---
    // POST /api/callbacks/inject — agent-centric, authenticated via gateway token
    case req @ POST -> Root / "api" / "callbacks" / "inject" =>
      val provided = extractToken(req)
      if !Auth.validateToken(provided, token) then Forbidden("Invalid token")
      else handleInject(req)

    case req @ GET -> Root =>
      StaticFile.fromResource("web/index.html", Some(req)).getOrElseF(NotFound())

    case req @ GET -> Root / "css" / file =>
      StaticFile.fromResource(s"web/css/$file", Some(req)).getOrElseF(NotFound())

    case req @ GET -> Root / "js" / "locales" / file =>
      StaticFile.fromResource(s"web/js/locales/$file", Some(req)).getOrElseF(NotFound())

    case req @ GET -> Root / "js" / file =>
      StaticFile.fromResource(s"web/js/$file", Some(req)).getOrElseF(NotFound())

    case req @ GET -> Root / fileName =>
      val allowed = Set("style.css", "app.js")
      if allowed.contains(fileName) then StaticFile.fromResource(s"web/$fileName", Some(req)).getOrElseF(NotFound())
      else NotFound()

    case req @ GET -> Root / "agents" / "manifest.json" =>
      sharedResources.agentLibrary.loadAll().flatMap { agents =>
        val json = io.circe.Json.obj(
          "agents" -> agents.values.toList.map { a =>
            io.circe.Json.obj(
              "name" -> a.name.asJson,
              "description" -> a.description.asJson,
              "displayName" -> a.displayName.getOrElse(a.name).asJson,
              "avatar" -> a.avatar.asJson
            )
          }.asJson
        )
        Ok(json.noSpaces, org.http4s.headers.`Content-Type`(org.http4s.MediaType.application.json))
      }

    case req @ GET -> _ if req.uri.path.renderString.startsWith("/agents/") =>
      // Serve static files under agent directory (supports nested paths)
      // Uses manual path parsing because http4s DSL only matches single path segments
      val segs = req.uri.path.segments.map(_.encoded).toList
      if segs.sizeIs < 3 then NotFound()
      else
        val agentName = segs(1)
        // Block path traversal: agentName must be a simple name (no .., /, \)
        if agentName.contains("..") || agentName.contains("/") || agentName.contains("\\") then NotFound()
        else
          val agentDir = AgentLibrary.defaultDir / agentName
          val relParts = segs.drop(2)
          // Block path traversal in relative parts
          val safeRel = relParts.filter(s => s != ".." && !s.contains("\\"))
          if safeRel.length != relParts.length then NotFound()
          else
            val filePath = agentDir / os.RelPath(safeRel.mkString("/"))
            // Final defense: resolve and verify the path stays under agentDir
            if filePath.startsWith(agentDir) && os.exists(filePath) && os.isFile(filePath) then
              StaticFile.fromPath(fs2.io.file.Path(filePath.toString), Some(req)).getOrElseF(NotFound())
            else NotFound()
  }

  private val inputHistoryPath = os.home / ".nebflow" / "input_history.jsonl"

  private def logInputHistory(content: String, attachments: List[io.circe.Json]): IO[Unit] =
    val filtered = content.trim.toLowerCase
    if (filtered == "quit" || filtered == "exit") && attachments.isEmpty then IO.unit
    else if content.trim.isEmpty && attachments.isEmpty then IO.unit
    else
      IO.blocking {
        val inputType =
          if attachments.nonEmpty then "file"
          else if content.length > 200 then "paste"
          else "input"
        val files = attachments.flatMap(_.hcursor.downField("name").as[String].toOption)
        val entry = io.circe.Json.obj(
          "text" -> io.circe.Json.fromString(content.take(2000)),
          "ts" -> io.circe.Json.fromString(
            java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
          ),
          "type" -> io.circe.Json.fromString(inputType)
        )
        val withFiles =
          if files.nonEmpty then
            entry.mapObject(_.add("files", io.circe.Json.fromValues(files.map(io.circe.Json.fromString))))
          else entry
        os.write.append(inputHistoryPath, withFiles.noSpaces + "\n", createFolders = true)
      }

  private def broadcastServerConfig: IO[Unit] =
    val toolsList = ToolRegistry.userConfigurableTools.map(t =>
      io.circe.Json.obj("name" -> t.name.asJson, "description" -> t.description.asJson)
    )
    for
      thinkingCfg <- sharedResources.thinkingConfigRef.get
      mcpServers <- mcpManager.listServers.map(_.map { case (id, enabled) =>
        io.circe.Json.obj("id" -> id.asJson, "enabled" -> enabled.asJson)
      })
      _ <- wsHub.broadcast(
        io.circe.Json.obj(
          "type" -> "serverConfig".asJson,
          "streamTimeoutMs" -> (Defaults.StreamTimeoutSec.toLong * 1000).asJson,
          "version" -> nebflow.Version.string.asJson,
          "thinking" -> thinkingCfg.asJson,
          "tools" -> toolsList.asJson,
          "mcpServers" -> mcpServers.asJson
        )
      )
    yield ()

  /** Persist thinking config to nebflow.json — targeted field update. */
  private def persistThinkingConfig(enabled: Boolean): IO[Unit] =
    IO.blocking {
      val path = nebflow.llm.Config.DefaultConfigPath
      val existing = if os.exists(path) then os.read(path) else "{}"
      parse(existing).foreach { json =>
        val updated = json.mapObject { obj =>
          obj.add("thinkingConfig", ThinkingConfig(enabled).asJson)
        }
        os.write.over(path, updated.spaces2, createFolders = true)
      }
    }.handleErrorWith { e =>
      logger.warn(s"Failed to persist thinking config: ${e.getMessage}")
    }

  /** Send agent-filtered session list by looking up the session's agent name. */
  private def sendAgentSessionList(wsSend: io.circe.Json => IO[Unit], sessionId: String): IO[Unit] =
    sessionStore.getSessionMeta(sessionId).flatMap { metaOpt =>
      val agentName = metaOpt.flatMap(_.agentName).getOrElse("Nebula")
      sendAgentSessionListByName(wsSend, agentName)
    }

  /** Send agent-filtered session list for a known agent name. */
  private def sendAgentSessionListByName(wsSend: io.circe.Json => IO[Unit], agentName: String): IO[Unit] =
    (sessionStore.listSessionsByAgent(agentName), sessionStore.listFolders(agentName)).flatMapN { (sessions, folders) =>
      wsSend(
        io.circe.Json.obj(
          "type" -> "agentSessionList".asJson,
          "agentName" -> agentName.asJson,
          "sessions" -> sessions.asJson,
          "folders" -> folders.asJson
        )
      )
    }

  /** Push memory status for the current session to the frontend. */
  private def sendMemoryStatus(wsSend: io.circe.Json => IO[Unit], sessionId: String): IO[Unit] =
    sessionStore.getSessionMeta(sessionId).flatMap { metaOpt =>
      val agentName = metaOpt.flatMap(_.agentName).getOrElse("Nebula")
      wsSend(
        io.circe.Json.obj(
          "type" -> "memoryStatus".asJson,
          "user" -> io.circe.Json.obj(
            "exists" -> MemoryStore.userExists.asJson,
            "preview" -> MemoryStore.userPreview.asJson
          ),
          "agent" -> io.circe.Json.obj(
            "exists" -> MemoryStore.agentExists(agentName).asJson,
            "preview" -> MemoryStore.agentPreview(agentName).asJson
          ),
          "session" -> io.circe.Json.obj(
            "exists" -> MemoryStore.sessionExists(sessionId).asJson,
            "preview" -> MemoryStore.sessionPreview(sessionId).asJson
          )
        )
      )
    }

  private val MaxMessageSize = 10 * 1024 * 1024 // 10MB (base64 images can be large)

  private def handleMessage(
    text: String,
    wsSend: io.circe.Json => IO[Unit]
  ): IO[Unit] =
    if text.length > MaxMessageSize then logger.warn(s"Dropping oversized WebSocket message (${text.length} bytes)")
    else
      parse(text).toOption.getOrElse(io.circe.Json.Null).hcursor.downField("type").as[String].getOrElse("") match
        case "askUserAnswer" =>
          parse(text).flatMap(_.hcursor.downField("answers").as[List[String]]).toOption match
            case Some(answers) =>
              val askSessionId = parse(text).flatMap(_.hcursor.downField("sessionId").as[String]).toOption.getOrElse("")
              routeToAgent(askSessionId)(ref => IO(ref ! AgentCommand.UserAnswered(answers)))
            case None => IO.unit

        case "permissionAnswer" =>
          val approved = parse(text).flatMap(_.hcursor.downField("approved").as[Boolean]).getOrElse(false)
          val permSessionId = parse(text).flatMap(_.hcursor.downField("sessionId").as[String]).toOption.getOrElse("")
          logger.info(s"Permission answer: ${if approved then "approved" else "denied"}") *>
            routeToAgent(permSessionId)(ref => IO(ref ! AgentCommand.PermissionAnswered(approved)))

        case "interrupt" =>
          val intSessionId = parse(text).flatMap(_.hcursor.downField("sessionId").as[String]).toOption.getOrElse("")
          logger.info("User interrupted") *> routeToAgent(intSessionId)(ref => IO(ref ! AgentCommand.Interrupt()))

        case "command" =>
          val command = parse(text).flatMap(_.hcursor.downField("command").as[String]).getOrElse("")
          command match
            case "clear" =>
              val clearSessionId =
                parse(text).flatMap(_.hcursor.downField("sessionId").as[String]).toOption.getOrElse("")
              logger.info("Session cleared") *>
                sessionStore.saveMessagesForSession(clearSessionId, Nil) *>
                sessionStore.appendUiMessages(
                  clearSessionId,
                  List(UiMessage.System("Context cleared. LLM memory reset."))
                ) *>
                sharedResources.taskStore.deleteAll(clearSessionId) *>
                wsSend(
                  io.circe.Json.obj(
                    "type" -> "taskListUpdate".asJson,
                    "tasks" -> io.circe.Json.arr(),
                    "sessionId" -> clearSessionId.asJson
                  )
                ) *>
                routeToAgent(clearSessionId)(ref => IO(ref ! AgentCommand.ResetSession))
            case "compact" =>
              val compactSessionId =
                parse(text).flatMap(_.hcursor.downField("sessionId").as[String]).toOption.getOrElse("")
              logger.info("Manual compaction triggered") *>
                routeToAgent(compactSessionId)(ref => IO(ref ! AgentCommand.TriggerCompaction("full")))
            case _ => IO.unit
          end match

        case "setThinking" =>
          val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
          val hc = json.hcursor
          val thinkingOpt = hc.downField("thinking").as[Option[io.circe.Json]].toOption.flatten
          // null/absent means toggled off; {enabled: false} also means off; otherwise default true
          val enabled = thinkingOpt match
            case None | Some(io.circe.Json.Null) => false
            case Some(v) => v.hcursor.downField("enabled").as[Boolean].getOrElse(true)
          logger.info(s"Thinking mode set to: enabled=$enabled") *>
            sharedResources.thinkingConfigRef.set(ThinkingConfig(enabled)) *>
            persistThinkingConfig(enabled) *>
            broadcastServerConfig

        case "getModelOptions" =>
          val sessionId = parse(text).flatMap(_.hcursor.downField("sessionId").as[String]).getOrElse("")
          sharedResources.providerRegistry.getAllModels().flatMap { models =>
            sharedResources.sessionModelOverrides.get.flatMap { overrides =>
              val currentOpt = overrides.get(sessionId).map(c => s"${c.providerId}/${c.model}")
              wsSend(
                io.circe.Json.obj(
                  "type" -> "modelOptions".asJson,
                  "models" -> models.map { case (ref, label) =>
                    io.circe.Json.obj("ref" -> ref.asJson, "label" -> label.asJson)
                  }.asJson,
                  "current" -> currentOpt.asJson
                )
              )
            }
          }

        case "setSessionModel" =>
          val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
          val sessionId = json.hcursor.downField("sessionId").as[String].getOrElse("")
          val modelRef = json.hcursor.downField("modelRef").as[Option[String]].getOrElse(None)
          if sessionId.nonEmpty then
            (modelRef match
              case Some(ref) =>
                sharedResources.providerRegistry.getCandidateForRef(ref).flatMap {
                  case Some(candidate) =>
                    sharedResources.sessionModelOverrides.update(_ + (sessionId -> candidate)) *>
                      sessionStore
                        .updateSessionModel(sessionId, Some(ref))
                        .as(Right(s"${candidate.providerId}/${candidate.model}"))
                  case None =>
                    IO.pure(Left(s"Unknown model: $ref"))
                }
              case None =>
                sharedResources.sessionModelOverrides.update(_ - sessionId) *>
                  sessionStore.updateSessionModel(sessionId, None).as(Right("default"))
            ).flatMap {
              case Right(ref) =>
                // Notify agent actor of context window change for compaction threshold
                val notifyAgent = sharedResources.sessionModelOverrides.get.flatMap { overrides =>
                  overrides.get(sessionId) match
                    case Some(candidate) =>
                      routeToAgent(sessionId)(ref =>
                        IO(ref ! AgentCommand.UpdateContextWindow(candidate.contextWindow))
                      )
                    case None => IO.unit
                }
                notifyAgent *> wsSend(io.circe.Json.obj("type" -> "sessionModelSet".asJson, "modelRef" -> ref.asJson))
              case Left(err) =>
                wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> err.asJson))
            }
          else IO.unit
          end if

        case "switchSession" =>
          val sessionId = parse(text).flatMap(_.hcursor.downField("sessionId").as[String]).getOrElse("")
          if sessionId.nonEmpty then
            sessionService
              .switchSession(sessionId)
              .flatMap { _ =>
                sendAgentSessionList(wsSend, sessionId) *>
                  sendMemoryStatus(wsSend, sessionId)
              }
              .handleErrorWith { e =>
                wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
              }
          else IO.unit

        case "createSession" =>
          val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
          val name = json.hcursor.downField("name").as[String].getOrElse("New Session")
          val agentName = json.hcursor.downField("agentName").as[Option[String]].getOrElse(None)
          val folderId = json.hcursor.downField("folderId").as[Option[String]].getOrElse(None)
          sessionService
            .createSession(name, agentName = agentName, folderId = folderId)
            .flatMap { _ =>
              // Send filtered session list for the agent tab
              agentName match
                case Some(an) =>
                  (sessionStore.listSessionsByAgent(an), sessionStore.listFolders(an)).flatMapN { (sessions, folders) =>
                    wsSend(
                      io.circe.Json.obj(
                        "type" -> "agentSessionList".asJson,
                        "agentName" -> an.asJson,
                        "sessions" -> sessions.asJson,
                        "folders" -> folders.asJson
                      )
                    )
                  }
                case None =>
                  sessionService.sendSessionList(wsSend, "Nebula")
            }
            .handleErrorWith { e =>
              wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
            }

        case "deleteSession" =>
          val sessionId = parse(text).flatMap(_.hcursor.downField("sessionId").as[String]).getOrElse("")
          if sessionId.nonEmpty then
            // Get agent name before deleting so we can send filtered list
            sessionStore
              .getSessionMeta(sessionId)
              .flatMap { metaOpt =>
                val agentName = metaOpt.flatMap(_.agentName).getOrElse("Nebula")
                // Clean up text buffers for deleted session to prevent memory leak
                sessionTextBuffers.update(_ - sessionId) *>
                  sessionTurnStarts.update(_ - sessionId) *>
                  removeRootAgent(sessionId) *> sessionService
                    .deleteSession(sessionId)
                    .flatMap { _ =>
                      sendAgentSessionListByName(wsSend, agentName)
                    }
              }
              .handleErrorWith { e =>
                wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
              }
          else IO.unit

        case "batchDeleteSessions" =>
          val sessionIds = parse(text).flatMap(_.hcursor.downField("sessionIds").as[List[String]]).getOrElse(Nil)
          if sessionIds.nonEmpty then
            sessionStore
              .getSessionMeta(sessionIds.head)
              .flatMap { metaOpt =>
                val agentName = metaOpt.flatMap(_.agentName).getOrElse("Nebula")
                sessionIds
                  .traverse_ { sid =>
                    sessionTextBuffers.update(_ - sid) *>
                      sessionTurnStarts.update(_ - sid) *>
                      removeRootAgent(sid) *>
                      sessionService.deleteSession(sid)
                  }
                  .flatMap { _ =>
                    sendAgentSessionListByName(wsSend, agentName)
                  }
              }
              .handleErrorWith { e =>
                wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
              }
          else IO.unit

        case "renameSession" =>
          val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
          val sessionId = json.hcursor.downField("sessionId").as[String].getOrElse("")
          val newName = json.hcursor.downField("name").as[String].getOrElse("")
          if sessionId.nonEmpty && newName.nonEmpty then
            sessionService
              .renameSession(sessionId, newName)
              .flatMap { _ =>
                sendAgentSessionList(wsSend, sessionId)
              }
              .handleErrorWith { e =>
                wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
              }
          else IO.unit

        case "updateSessionFeishu" =>
          val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
          val hc = json.hcursor
          val sessionId = hc.downField("sessionId").as[String].getOrElse("")
          val chatId = hc.downField("chatId").as[String].getOrElse("")
          if sessionId.isEmpty then IO.unit
          else if chatId.isEmpty then
            // Clear feishu config
            sessionStore.updateSessionFeishu(sessionId, None).flatMap { _ =>
              sharedResources.bridgeManager.fold(IO.unit)(_.refreshRoutes) *>
                sendAgentSessionList(wsSend, sessionId)
            }
          else
            val enabled = hc.downField("enabled").as[Option[Boolean]].toOption.flatten.getOrElse(true)
            val chatType = hc.downField("chatType").as[Option[String]].toOption.flatten.getOrElse("p2p")
            val notifyEvents = hc
              .downField("notifyEvents")
              .as[Option[List[String]]]
              .toOption
              .flatten
              .getOrElse(List("aiResponse", "askUser", "permissionRequest"))
            val syncMessages = hc.downField("syncMessages").as[Option[Boolean]].toOption.flatten.getOrElse(true)
            val appId = hc.downField("appId").as[Option[String]].toOption.flatten.getOrElse("")
            val appSecret = hc.downField("appSecret").as[Option[String]].toOption.flatten.getOrElse("")
            val cfgJson = Json.obj(
              "enabled" -> enabled.asJson,
              "chatId" -> chatId.asJson,
              "chatType" -> chatType.asJson,
              "notifyEvents" -> notifyEvents.asJson,
              "syncMessages" -> syncMessages.asJson
            )
            val withCreds = if appId.nonEmpty then cfgJson.deepMerge(Json.obj("appId" -> appId.asJson)) else cfgJson
            val finalCfg =
              if appSecret.nonEmpty then withCreds.deepMerge(Json.obj("appSecret" -> appSecret.asJson)) else withCreds
            sessionStore.updateSessionFeishu(sessionId, Some(finalCfg)).flatMap { _ =>
              // Refresh bridge routing tables so the new binding takes effect immediately
              sharedResources.bridgeManager.fold(IO.unit)(_.refreshRoutes) *>
                sendAgentSessionList(wsSend, sessionId)
            }
          end if

        case "getFeishuGlobalConfig" =>
          import nebflow.bridge.FeishuGlobalConfig
          FeishuGlobalConfig.load.flatMap {
            case Some(cfg) =>
              wsSend(
                io.circe.Json.obj(
                  "type" -> "feishuGlobalConfig".asJson,
                  "configured" -> true.asJson,
                  "appId" -> cfg.appId.asJson,
                  "hasAppSecret" -> cfg.appSecret.nonEmpty.asJson,
                  "appSecret" -> cfg.appSecret.asJson
                )
              )
            case None =>
              wsSend(
                io.circe.Json.obj(
                  "type" -> "feishuGlobalConfig".asJson,
                  "configured" -> false.asJson
                )
              )
          }

        case "updateFeishuGlobalConfig" =>
          val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
          val hc = json.hcursor
          val appId = hc.downField("appId").as[String].getOrElse("")
          val appSecret = hc.downField("appSecret").as[String].getOrElse("")
          if appId.nonEmpty then
            import nebflow.bridge.FeishuGlobalConfig
            // Load existing config to preserve other fields, then merge
            FeishuGlobalConfig.load.flatMap { existing =>
              val base = existing.getOrElse(FeishuGlobalConfig(appId = "", appSecret = ""))
              val updated = base.copy(
                appId = appId,
                appSecret = if appSecret.nonEmpty then appSecret else base.appSecret
              )
              FeishuGlobalConfig.save(updated) *>
                wsSend(io.circe.Json.obj("type" -> "feishuGlobalConfigSaved".asJson, "ok" -> true.asJson))
            }
          else IO.unit

        case "ask" =>
          val askJson = parse(text).toOption.getOrElse(io.circe.Json.Null)
          val question = askJson.hcursor.downField("question").as[String].getOrElse("")
          val askSessionId = askJson.hcursor.downField("sessionId").as[String].getOrElse("")
          if question.nonEmpty && askSessionId.nonEmpty then
            executeAsk(askSessionId, question, wsSend).handleErrorWith { e =>
              logger.warn(s"Ask failed for session $askSessionId: ${e.getMessage}")
              wsSend(
                io.circe.Json.obj(
                  "type" -> "askError".asJson,
                  "sessionId" -> askSessionId.asJson,
                  "message" -> s"Ask failed: ${e.getMessage.take(200)}".asJson
                )
              )
            }
          else IO.unit

        case "searchHistory" =>
          val query = parse(text).flatMap(_.hcursor.downField("query").as[String]).getOrElse("")
          if query.trim.nonEmpty then
            sessionStore.searchHistory(query).flatMap { hits =>
              wsSend(
                io.circe.Json.obj(
                  "type" -> "searchResults".asJson,
                  "query" -> query.asJson,
                  "results" -> hits.asJson
                )
              )
            }
          else
            wsSend(
              io.circe.Json.obj(
                "type" -> "searchResults".asJson,
                "query" -> "".asJson,
                "results" -> List.empty[SearchHit].asJson
              )
            )

        case "ping" => IO.unit

        case "cancelBackgroundJob" =>
          val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
          val cancelSessionId = json.hcursor.downField("sessionId").as[String].getOrElse("")
          val jobId = json.hcursor.downField("jobId").as[String].getOrElse("")
          if cancelSessionId.nonEmpty && jobId.nonEmpty then
            nebflow.core.tools.ShellSession
              .forSession(cancelSessionId)
              .flatMap { shell =>
                shell.cancelBackgroundJob(jobId).flatMap { cancelled =>
                  wsSend(
                    io.circe.Json.obj(
                      "type" -> "backgroundTaskUpdate".asJson,
                      "sessionId" -> cancelSessionId.asJson,
                      "taskId" -> jobId.asJson,
                      "description" -> "".asJson,
                      "status" -> (if cancelled then "completed" else "running").asJson
                    )
                  )
                }
              }
              .handleErrorWith { e =>
                logger.warn(s"cancelBackgroundJob failed: ${e.getMessage}")
                IO.unit
              }
          else IO.unit

        case "getHistory" =>
          val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
          val sessionId = json.hcursor.downField("sessionId").as[String].getOrElse("")
          val limit = json.hcursor.downField("limit").as[Int].getOrElse(50)
          val beforeIndex = json.hcursor.downField("beforeIndex").as[Option[Int]].getOrElse(None)
          if sessionId.nonEmpty then
            (sharedResources.sessionStore
              .getUiMessages(sessionId, 0, 0)
              .map(_._2)
              .attempt
              .flatMap {
                case Right(total) =>
                  beforeIndex match
                    case None =>
                      val offset = Math.max(0, total - limit)
                      sharedResources.sessionStore.getUiMessages(sessionId, offset, limit).flatMap { case (msgs, _) =>
                        wsSend(
                          io.circe.Json.obj(
                            "type" -> "historyPage".asJson,
                            "sessionId" -> sessionId.asJson,
                            "messages" -> msgs.asJson,
                            "total" -> total.asJson,
                            "offset" -> offset.asJson,
                            "hasMore" -> (total > limit).asJson
                          )
                        )
                      }
                    case Some(before) =>
                      val offset = Math.max(0, before - limit)
                      val actualLimit = Math.min(limit, before)
                      sharedResources.sessionStore.getUiMessages(sessionId, offset, actualLimit).flatMap {
                        case (msgs, _) =>
                          wsSend(
                            io.circe.Json.obj(
                              "type" -> "historyPage".asJson,
                              "sessionId" -> sessionId.asJson,
                              "messages" -> msgs.asJson,
                              "total" -> total.asJson,
                              "offset" -> offset.asJson,
                              "hasMore" -> (before > limit).asJson
                            )
                          )
                      }
                case Left(_) =>
                  // Send empty historyPage so the frontend doesn't get stuck
                  wsSend(
                    io.circe.Json.obj(
                      "type" -> "historyPage".asJson,
                      "sessionId" -> sessionId.asJson,
                      "messages" -> List.empty[io.circe.Json].asJson,
                      "total" -> 0.asJson,
                      "offset" -> 0.asJson,
                      "hasMore" -> false.asJson
                    )
                  )
              })
              .handleErrorWith { e =>
                wsSend(
                  io.circe.Json.obj("type" -> "error".asJson, "message" -> s"getHistory failed: ${e.getMessage}".asJson)
                )
              }
          else IO.unit
          end if

        case "listAgents" =>
          agentService.listAgents.flatMap { agents =>
            // Configurable builtin tools — exclude MCP tools (controlled via mcpServers) and auto-injected tools
            val isMcpTool = (name: String) => name.startsWith("mcp__")
            val autoInjected =
              ToolRegistry.AlwaysAvailableNonCompact
            val configurableTools = ToolRegistry.TOOL_MAP.keys
              .filterNot(n => isMcpTool(n) || autoInjected.contains(n))
              .toList
              .sorted
            val autoTools = autoInjected.toList.sorted
            val agentsJson = agents.map { a =>
              io.circe.Json.obj(
                "name" -> a.name.asJson,
                "description" -> a.description.asJson,
                "displayName" -> a.displayName.getOrElse(a.name).asJson,
                "avatar" -> a.avatar.asJson,
                "tools" -> a.tools.asJson,
                "mcpServers" -> a.mcpServers.asJson
              )
            }
            wsSend(
              io.circe.Json.obj(
                "type" -> "agentList".asJson,
                "agents" -> agentsJson.asJson,
                "availableTools" -> configurableTools.asJson,
                "autoTools" -> autoTools.asJson
              )
            )
          }

        case "listAgentSessions" =>
          val agentName = parse(text).flatMap(_.hcursor.downField("name").as[String]).getOrElse("")
          if agentName.nonEmpty then
            (sessionStore.listSessionsByAgent(agentName), sessionStore.listFolders(agentName)).flatMapN {
              (sessions, folders) =>
                wsSend(
                  io.circe.Json.obj(
                    "type" -> "agentSessionList".asJson,
                    "agentName" -> agentName.asJson,
                    "sessions" -> sessions.asJson,
                    "folders" -> folders.asJson
                  )
                )
            }
          else IO.unit

        // ===== Folder Management =====

        case "createFolder" =>
          val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
          val name = json.hcursor.downField("name").as[String].getOrElse("New Folder")
          val parentId = json.hcursor.downField("parentId").as[Option[String]].getOrElse(None)
          val agentNameFromMsg = json.hcursor.downField("agentName").as[String].getOrElse("")
          if name.nonEmpty then
            val agentNameIO =
              if agentNameFromMsg.nonEmpty then IO.pure(agentNameFromMsg)
              else sessionStore.getActiveMeta.map(_.flatMap(_.agentName).getOrElse("Nebula"))
            agentNameIO
              .flatMap { agentName =>
                sessionService.createFolder(name, parentId, agentName).flatMap { _ =>
                  sendAgentSessionListByName(wsSend, agentName)
                }
              }
              .handleErrorWith { e =>
                wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
              }
          else IO.unit

        case "renameFolder" =>
          val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
          val folderId = json.hcursor.downField("folderId").as[String].getOrElse("")
          val newName = json.hcursor.downField("name").as[String].getOrElse("")
          if folderId.nonEmpty && newName.nonEmpty then
            sessionService
              .renameFolder(folderId, newName)
              .flatMap { _ =>
                sessionStore.getActiveMeta.flatMap { metaOpt =>
                  val agentName = metaOpt.flatMap(_.agentName).getOrElse("Nebula")
                  sendAgentSessionListByName(wsSend, agentName)
                }
              }
              .handleErrorWith { e =>
                wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
              }
          else IO.unit

        case "deleteFolder" =>
          val folderId = parse(text).flatMap(_.hcursor.downField("folderId").as[String]).getOrElse("")
          if folderId.nonEmpty then
            sessionService
              .deleteFolder(folderId)
              .flatMap { _ =>
                sessionStore.getActiveMeta.flatMap { metaOpt =>
                  val agentName = metaOpt.flatMap(_.agentName).getOrElse("Nebula")
                  sendAgentSessionListByName(wsSend, agentName)
                }
              }
              .handleErrorWith { e =>
                wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
              }
          else IO.unit

        case "moveSessionToFolder" =>
          val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
          val sessionId = json.hcursor.downField("sessionId").as[String].getOrElse("")
          val folderId = json.hcursor.downField("folderId").as[Option[String]].getOrElse(None)
          if sessionId.nonEmpty then
            sessionService
              .moveSessionToFolder(sessionId, folderId)
              .flatMap { _ =>
                sendAgentSessionList(wsSend, sessionId)
              }
              .handleErrorWith { e =>
                wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
              }
          else IO.unit

        case "moveFolder" =>
          val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
          val folderId = json.hcursor.downField("folderId").as[String].getOrElse("")
          val parentId = json.hcursor.downField("parentId").as[Option[String]].getOrElse(None)
          if folderId.nonEmpty then
            sessionService
              .moveFolder(folderId, parentId)
              .flatMap { _ =>
                sessionStore.getActiveMeta.flatMap { metaOpt =>
                  val agentName = metaOpt.flatMap(_.agentName).getOrElse("Nebula")
                  sendAgentSessionListByName(wsSend, agentName)
                }
              }
              .handleErrorWith { e =>
                wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
              }
          else IO.unit

        case "getAgentConfig" =>
          val agentName = parse(text).flatMap(_.hcursor.downField("name").as[String]).getOrElse("")
          if agentName.nonEmpty then
            agentService.getAgentConfig(agentName).flatMap {
              case Some(cfg) =>
                wsSend(
                  io.circe.Json.obj(
                    "type" -> "agentConfig".asJson,
                    "name" -> cfg.name.asJson,
                    "configJson" -> cfg.configJson.asJson,
                    "systemMd" -> cfg.systemMd.asJson
                  )
                )
              case None => IO.unit
            }
          else IO.unit

        case "createAgent" =>
          val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
          val agentName = json.hcursor.downField("name").as[String].getOrElse("")
          val configJson = json.hcursor.downField("configJson").as[String].getOrElse("")
          val systemMd = json.hcursor.downField("systemMd").as[String].getOrElse("")
          if agentName.nonEmpty then
            agentService.createAgent(agentName, configJson, systemMd).flatMap {
              case Left(err) =>
                wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> err.asJson))
              case Right(_) =>
                wsSend(io.circe.Json.obj("type" -> "agentCreated".asJson, "name" -> agentName.asJson))
            }
          else IO.unit

        case "updateAgent" =>
          val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
          val agentName = json.hcursor.downField("name").as[String].getOrElse("")
          val configJson = json.hcursor.downField("configJson").as[String].getOrElse("")
          val systemMd = json.hcursor.downField("systemMd").as[String].getOrElse("")
          if agentName.nonEmpty then
            agentService.updateAgent(agentName, configJson, systemMd).flatMap {
              case Left(err) =>
                wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> err.asJson))
              case Right(_) =>
                wsSend(io.circe.Json.obj("type" -> "agentUpdated".asJson, "name" -> agentName.asJson))
            }
          else IO.unit

        case "createAgentSession" =>
          val agentName = parse(text).flatMap(_.hcursor.downField("name").as[String]).getOrElse("")
          if agentName.nonEmpty then
            (for
              defnOpt <- sharedResources.agentLibrary.get(agentName)
              defn <- IO.fromOption(defnOpt)(new RuntimeException(s"Agent not found: $agentName"))
              meta <- sessionService.createSession(
                s"Agent: ${defn.displayName.getOrElse(defn.name)}",
                agentName = Some(agentName)
              )
              _ <- sessionService.switchSession(meta.id)
              _ <- sessionService.sendSessionList(wsSend, agentName)
            yield ()).handleErrorWith { e =>
              wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
            }
          else IO.unit

        case "getMemory" =>
          val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
          val scope = json.hcursor.downField("scope").as[String].getOrElse("session")
          (sessionStore.getActiveMeta
            .flatMap { metaOpt =>
              val agentName = metaOpt.flatMap(_.agentName).getOrElse("Nebula")
              val sessionId = metaOpt.map(_.id).getOrElse("")
              val content = scope match
                case "user" => MemoryStore.loadUserMemory.getOrElse("")
                case "agent" => MemoryStore.loadAgentMemory(agentName).getOrElse("")
                case "session" =>
                  if sessionId.nonEmpty then MemoryStore.loadSessionMemory(sessionId).getOrElse("") else ""
                case _ => ""
              wsSend(
                io.circe.Json.obj(
                  "type" -> "memoryData".asJson,
                  "scope" -> scope.asJson,
                  "content" -> content.asJson
                )
              )
            })
            .handleErrorWith { e =>
              logger.warn(s"getMemory error: ${e.getMessage}")
              wsSend(
                io.circe.Json.obj("type" -> "error".asJson, "message" -> s"getMemory failed: ${e.getMessage}".asJson)
              )
            }

        case "saveMemory" =>
          val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
          val scope = json.hcursor.downField("scope").as[String].getOrElse("session")
          val content = json.hcursor.downField("content").as[String].getOrElse("")
          (sessionStore.getActiveMeta
            .flatMap { metaOpt =>
              val agentName = metaOpt.flatMap(_.agentName).getOrElse("Nebula")
              val sessionId = metaOpt.map(_.id).getOrElse("")
              val save = scope match
                case "user" => MemoryStore.saveUserMemory(content)
                case "agent" => MemoryStore.saveAgentMemory(agentName, content)
                case "session" =>
                  if sessionId.nonEmpty then MemoryStore.saveSessionMemory(sessionId, content) else IO.unit
                case _ => IO.unit
              save *> wsSend(io.circe.Json.obj("type" -> "memorySaved".asJson, "scope" -> scope.asJson))
            })
            .handleErrorWith { e =>
              logger.warn(s"saveMemory error: ${e.getMessage}")
              wsSend(
                io.circe.Json.obj("type" -> "error".asJson, "message" -> s"saveMemory failed: ${e.getMessage}".asJson)
              )
            }

        case "memoryStatus" =>
          sessionStore.getActiveMeta.flatMap { metaOpt =>
            val agentName = metaOpt.flatMap(_.agentName).getOrElse("Nebula")
            val sessionId = metaOpt.map(_.id).getOrElse("")
            wsSend(
              io.circe.Json.obj(
                "type" -> "memoryStatus".asJson,
                "user" -> io.circe.Json.obj(
                  "exists" -> MemoryStore.userExists.asJson,
                  "preview" -> MemoryStore.userPreview.asJson
                ),
                "agent" -> io.circe.Json.obj(
                  "exists" -> MemoryStore.agentExists(agentName).asJson,
                  "preview" -> MemoryStore.agentPreview(agentName).asJson
                ),
                "session" -> io.circe.Json.obj(
                  "exists" -> (sessionId.nonEmpty && MemoryStore.sessionExists(sessionId)).asJson,
                  "preview" -> (if sessionId.nonEmpty then MemoryStore.sessionPreview(sessionId) else None).asJson
                )
              )
            )
          }

        case "getConfig" =>
          configService.isConfigured.flatMap { configured =>
            configService.getConfig.flatMap { cfg =>
              wsSend(
                io.circe.Json.obj(
                  "type" -> "configData".asJson,
                  "config" -> cfg.asJson,
                  "configured" -> configured.asJson
                )
              )
            }
          }

        case "updateConfig" =>
          val cfg = parse(text).flatMap(_.hcursor.downField("config").as[String]).getOrElse("")
          if cfg.nonEmpty then
            configService.updateConfig(cfg).flatMap {
              case Left(err) =>
                wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> err.asJson))
              case Right(_) =>
                // Hot-reload: update in-memory config and clear adapter cache
                sharedResources.providerRegistry.reloadConfig().attempt.flatMap {
                  case Right(_) =>
                    logger.info("Config hot-reloaded successfully")
                  case Left(e) =>
                    logger.warn(s"Config hot-reload failed: ${e.getMessage}")
                } *> wsSend(io.circe.Json.obj("type" -> "configUpdated".asJson, "success" -> true.asJson))
            }
          else IO.unit

        case _ =>
          val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
          val content = json.hcursor.downField("content").as[String].getOrElse("")
          val attachments = json.hcursor.downField("attachments").as[List[io.circe.Json]].getOrElse(Nil)
          val clientMessageId = json.hcursor.downField("clientMessageId").as[Option[String]].getOrElse(None)
          val msgSessionId = json.hcursor.downField("sessionId").as[String].getOrElse("")

          if content.nonEmpty || attachments.nonEmpty then
            rateLimiter.check("ws").flatMap { allowed =>
              if !allowed then
                logger.warn("Rate limit exceeded") *>
                  wsSend(
                    io.circe.Json.obj(
                      "type" -> "error".asJson,
                      "message" -> NebflowError.toUserMessage(NebflowError.RateLimited("websocket")).asJson
                    )
                  )
              else
                val blocks = scala.collection.mutable.ListBuffer.empty[ContentBlock]
                if content.nonEmpty then blocks += ContentBlock.Text(content)

                attachments.foreach { att =>
                  val mimeType = att.hcursor.downField("mimeType").as[String].getOrElse("")
                  val data = att.hcursor.downField("data").as[String].getOrElse("")
                  val name = att.hcursor.downField("name").as[String].getOrElse("")
                  if mimeType.startsWith("image/") && data.nonEmpty then blocks += ContentBlock.Image(data, mimeType)
                  else if data.nonEmpty then
                    val maxChars = 30000
                    if data.length <= maxChars then blocks += ContentBlock.Text(s"[file: $name]\n$data")
                    else
                      blocks += ContentBlock.Text(s"[file: $name] (文件过大，已截断前 ${maxChars} 字符)\n${data.take(maxChars)}")
                }

                sessionStore.getSessionMeta(msgSessionId).flatMap { metaOpt =>
                  val sessionName = metaOpt.map(_.name).getOrElse("-")
                  logger.info(s"${logger.hl(sessionName)} User message: ${content
                      .take(60)}${if content.length > 60 then "..." else ""}") *>
                    logInputHistory(content, attachments) *>
                    // Record user message as UiMessage for history
                    (if msgSessionId.nonEmpty then
                       val attJson = attachments.map { att =>
                         val name = att.hcursor.downField("name").as[String].getOrElse("")
                         val mimeType = att.hcursor.downField("mimeType").as[String].getOrElse("")
                         io.circe.Json.obj(
                           "name" -> name.asJson,
                           "type" -> (if mimeType.startsWith("image/") then "image" else "file").asJson
                         )
                       }
                       val injected = json.hcursor.downField("injected").as[Boolean].getOrElse(false)
                       sharedResources.sessionStore
                         .appendUiMessages(msgSessionId, List(UiMessage.User(content, attJson, injected)))
                         .handleErrorWith(e => IO(logger.warn(s"Failed to record user UiMessage: ${e.getMessage}")))
                     else IO.unit) *> {
                      val blocksList = blocks.toList
                      routeToAgent(msgSessionId)(ref =>
                        IO(
                          ref ! AgentCommand
                            .UserInput(content, None, clientMessageId, Some(blocksList).filter(_.nonEmpty))
                        )
                      )
                    }
                }
            }
          else IO.unit
          end if
    end if
  end handleMessage

  // ============================================================
  // UI Message recording — wraps wsSend to persist frontend-renderable history
  // ============================================================

  /** Per-session accumulator for text deltas (emitted one-by-one, saved on textDone). */
  private val sessionTextBuffers: Ref[IO, Map[String, String]] =
    Ref.unsafe[IO, Map[String, String]](Map.empty)

  /** Per-session turn start time — set on first streaming event, consumed on done. */
  private val sessionTurnStarts: Ref[IO, Map[String, Long]] =
    Ref.unsafe[IO, Map[String, Long]](Map.empty)

  private def makeRecordingWsSend(
    sessionId: String,
    underlying: io.circe.Json => IO[Unit]
  ): io.circe.Json => IO[Unit] = json =>
    val hc = json.hcursor
    val eventType = hc.downField("type").as[String].getOrElse("")
    val record = eventType match
      case "textDelta" =>
        // Accumulate text for this session
        val delta = hc.downField("delta").as[String].getOrElse("")
        if delta.nonEmpty then
          sessionTurnStarts
            .update(m => if m.contains(sessionId) then m else m.updated(sessionId, System.currentTimeMillis())) *>
            sessionTextBuffers.update(m => m.updatedWith(sessionId)(_.map(_ + delta).orElse(Some(delta))))
        else IO.unit

      case "thinking" =>
        sessionTurnStarts
          .update(m => if m.contains(sessionId) then m else m.updated(sessionId, System.currentTimeMillis()))

      case "toolStart" =>
        sessionTurnStarts
          .update(m => if m.contains(sessionId) then m else m.updated(sessionId, System.currentTimeMillis()))
        // Flush accumulated text before tool execution, matching frontend finishAi() behavior.
        // Without this, text output before a tool call stays in the in-memory buffer and is lost
        // when the user switches sessions before the final "done" event.
          *> sessionTextBuffers
            .modify { m =>
              val text = m.getOrElse(sessionId, "")
              (m - sessionId, text)
            }
            .flatMap { text =>
              if text.nonEmpty then
                sharedResources.sessionStore.appendUiMessages(sessionId, List(UiMessage.Ai(text, None, None)))
              else IO.unit
            }

      case "done" =>
        val model = hc.downField("model").as[Option[String]].getOrElse(None)
        sessionTurnStarts
          .modify { m =>
            val start = m.getOrElse(sessionId, 0L)
            (m - sessionId, start)
          }
          .flatMap { startTime =>
            val durationMs = if startTime > 0 then Some(System.currentTimeMillis() - startTime) else None
            sessionTextBuffers
              .modify { m =>
                val text = m.getOrElse(sessionId, "")
                (m - sessionId, text)
              }
              .flatMap { text =>
                if text.nonEmpty then
                  sharedResources.sessionStore.appendUiMessages(sessionId, List(UiMessage.Ai(text, durationMs, model)))
                else IO.unit
              }
          }

      case "toolEnd" =>
        val label = hc.downField("label").as[String].getOrElse("")
        val summary = hc.downField("summary").as[String].getOrElse("")
        val content = hc.downField("content").as[String].getOrElse("")
        val isError = hc.downField("isError").as[Boolean].getOrElse(false)
        val input = hc.downField("input").as[io.circe.Json].getOrElse(io.circe.Json.Null).noSpaces
        val truncated = hc.downField("truncated").as[Boolean].getOrElse(false)
        sharedResources.sessionStore.appendUiMessages(
          sessionId,
          List(UiMessage.Tool(label, summary, content, isError, input, truncated))
        )

      case "agentEnd" =>
        val agentId = hc.downField("agentId").as[String].getOrElse("")
        // Sub-agent text is streamed as agentTextDelta — we need to capture it.
        // For now, agentEnd without accumulated text is a no-op.
        // Agent text is typically short and embedded in the main AI bubble on the frontend.
        // We'll record a minimal agent entry for history if needed.
        IO.unit

      case "askUser" =>
        val items = hc.downField("items").as[List[io.circe.Json]].getOrElse(Nil)
        sharedResources.sessionStore.appendUiMessages(sessionId, List(UiMessage.AskUser(items)))

      case "askPermission" =>
        val toolName = hc.downField("toolName").as[String].getOrElse("")
        val summary = hc.downField("summary").as[String].getOrElse("")
        val permInput = hc.downField("input").as[io.circe.Json].getOrElse(io.circe.Json.Null).noSpaces
        if toolName.nonEmpty then
          sharedResources.sessionStore.appendUiMessages(
            sessionId,
            List(UiMessage.AskPermission(toolName, summary, permInput))
          )
        else IO.unit

      case "system" =>
        val content = hc.downField("content").as[String].getOrElse("")
        if content.nonEmpty then
          sharedResources.sessionStore.appendUiMessages(sessionId, List(UiMessage.System(content)))
        else IO.unit

      case "compactStart" =>
        val mode = hc.downField("mode").as[String].getOrElse("full")
        sharedResources.sessionStore.appendUiMessages(
          sessionId,
          List(UiMessage.System(s"Compacting context ($mode)..."))
        )

      case "compactComplete" =>
        val before = hc.downField("before").as[Int].getOrElse(0)
        val after = hc.downField("after").as[Int].getOrElse(0)
        val reportPath = hc.downField("reportPath").as[String].toOption
        val detail = reportPath.map(p => s" (report: ${p.split('/').last})").getOrElse("")
        sharedResources.sessionStore.appendUiMessages(
          sessionId,
          List(UiMessage.System(s"Context compacted: $before → $after messages$detail"))
        )

      case "compactFailed" =>
        val attempt = hc.downField("attempt").as[Int].getOrElse(0)
        val maxAttempts = hc.downField("maxAttempts").as[Int].getOrElse(0)
        sharedResources.sessionStore.appendUiMessages(
          sessionId,
          List(UiMessage.System(s"Context compaction failed (attempt $attempt/$maxAttempts)"))
        )

      case _ => IO.unit

    record.handleErrorWith(e =>
      IO(logger.warn(s"Failed to record UI message for session $sessionId: ${e.getMessage}"))
    ) *> underlying(json).handleErrorWith(e =>
      IO(logger.warn(s"Failed to broadcast message for session $sessionId: ${e.getMessage}"))
    )

  // ============================================================
  // /ask — isolated LLM Q&A (does not affect agent context)
  // ============================================================

  private def executeAsk(
    sessionId: String,
    question: String,
    wsSend: io.circe.Json => IO[Unit]
  ): IO[Unit] =
    // Route /ask to the agent actor — it handles inline via pipeLlmCall with askMode
    routeToAgent(sessionId)(ref => IO(ref ! AgentCommand.AskQuestion(question, sessionId)))

  // ============================================================
  // Callback helpers
  // ============================================================

  /** Extract token from cookie, Authorization header, or query param. */
  private def extractToken(req: org.http4s.Request[IO]): String =
    val fromCookie = req.cookies.find(_.name == "nebflow_token").map(_.content).getOrElse("")
    if fromCookie.nonEmpty then fromCookie
    else
      val fromHeader = req.headers
        .get[org.http4s.headers.Authorization]
        .collectFirst { case org.http4s.headers.Authorization(org.http4s.Credentials.Token(_, t)) =>
          t
        }
        .getOrElse("")
      if fromHeader.nonEmpty then fromHeader
      else req.params.get("token").getOrElse("")

  /**
   * POST /api/callbacks/inject
   *
   * Body: { "agent": "Nebula", "session?": "abc123", "message": "text" }
   *
   * - agent is required
   * - session is optional; if missing, create a new session for the agent
   * - message is required
   */
  private def handleInject(req: org.http4s.Request[IO]): IO[org.http4s.Response[IO]] =
    req.bodyText.compile.string
      .flatMap { text =>
        parse(text).toOption match
          case None => BadRequest("Invalid JSON body")
          case Some(json) =>
            val cursor = json.hcursor
            val agentName = cursor.downField("agent").as[String].getOrElse("")
            val sessionIdOpt = cursor.downField("session").as[Option[String]].getOrElse(None)
            val message = cursor.downField("message").as[String].getOrElse("")
            val source = cursor.downField("source").as[String].getOrElse("callback")
            val metadata = cursor.downField("metadata").as[JsonObject].getOrElse(JsonObject.empty)
            val correlationId = cursor.downField("correlationId").as[Option[String]].getOrElse(None)

            if agentName.isEmpty then BadRequest(Json.obj("error" -> "Missing 'agent' field".asJson))
            else if message.isEmpty then BadRequest(Json.obj("error" -> "Missing 'message' field".asJson))
            else
              resolveSession(agentName, sessionIdOpt).flatMap { sessionId =>
                val event = AgentCommand.ExternalEvent(source, "inject", message, metadata, correlationId)
                handleBridgeAgentCommand(sessionId, event) *>
                  Ok(
                    Json.obj(
                      "status" -> "ok".asJson,
                      "sessionId" -> sessionId.asJson,
                      "agent" -> agentName.asJson
                    )
                  )
              }
      }
      .handleErrorWith {
        case e: RuntimeException => NotFound(Json.obj("error" -> e.getMessage.asJson))
        case e => InternalServerError(Json.obj("error" -> e.getMessage.asJson))
      }

  /** Resolve a session: use provided ID, or create a new one for the agent. */
  private def resolveSession(agentName: String, sessionIdOpt: Option[String]): IO[String] =
    sessionIdOpt match
      case Some(sid) =>
        sessionStore.getSessionMeta(sid).flatMap {
          case Some(_) => IO.pure(sid)
          case None => IO.raiseError(new RuntimeException(s"Session not found: $sid"))
        }
      case None =>
        sessionService
          .createSession(
            name = s"$agentName-callback",
            agentName = Some(agentName)
          )
          .map(_.id)

end WebSocketRoutes
