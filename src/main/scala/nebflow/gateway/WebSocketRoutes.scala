package nebflow.gateway

import cats.effect.std.{Dispatcher, Queue}
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.{Pipe, Stream}
import io.circe.parser.parse
import io.circe.syntax.*
import nebflow.agent.*
import nebflow.core.*
import nebflow.core.tools.ToolRegistry
import nebflow.service.*
import nebflow.shared.*
import nebflow.skill.SkillDiscovery
import nebflow.core.mcp.McpManager
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
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
  runtimePrefs: RuntimePreferencesService,
  rateLimiter: RateLimiter,
  token: String,
  fileChangeTracker: FileChangeTracker,
  reminderStateRef: Ref[IO, ReminderState],
  sessionStore: SessionStore,
  wsHub: WsHub,
  actorSystem: ActorSystem[Nothing],
  contextWindow: Int = Defaults.ContextWindow,
  skillDiscovery: Option[SkillDiscovery] = None,
  sharedResources: SharedResources,
  pluginManifests: List[nebflow.plugin.PluginManifest] = Nil,
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
              readTracker = Some(readTracker)
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

  /** Route a message to the root agent of the currently active session. */
  private def withActiveAgent(f: ActorRef[AgentCommand] => IO[Unit]): IO[Unit] =
    sessionStore.getActiveId
      .flatMap { sessionId =>
        ensureRootAgent(sessionId).flatMap(f)
      }
      .handleErrorWith { e =>
        logger.warn(s"Failed to route message to active agent: ${e.getMessage}")
      }

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
          prefs <- runtimePrefs.getAll
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
                  "policy" -> PermissionPolicy.toName(prefs.permissionPolicy).asJson,
                  "thinking" -> prefs.thinkingConfig.map(ThinkingConfig.toJson).getOrElse(io.circe.Json.Null),
                  "tools" -> toolsList.asJson,
                  "language" -> prefs.language.asJson,
                  "mcpServers" -> mcpServers.asJson
                )
                .noSpaces
            )
          )
          _ <- sessionService.sendSessionList(perConnWsSend)
          ws <- wsb.build(sendStream, receivePipe)
        yield ws
      else
        logger.debug("WebSocket auth failed: invalid token") *>
          Forbidden("Invalid token")
      end if

    case req @ GET -> Root =>
      StaticFile.fromResource("web/index.html", Some(req)).getOrElseF(NotFound())

    case req @ GET -> Root / "css" / file =>
      StaticFile.fromResource(s"web/css/$file", Some(req)).getOrElseF(NotFound())

    case req @ GET -> Root / "js" / file =>
      StaticFile.fromResource(s"web/js/$file", Some(req)).getOrElseF(NotFound())

    case req @ GET -> Root / fileName =>
      val allowed = Set("style.css", "app.js")
      if allowed.contains(fileName) then StaticFile.fromResource(s"web/$fileName", Some(req)).getOrElseF(NotFound())
      else NotFound()

    case req @ GET -> Root / "plugins" / "manifest.json" =>
      val json = io.circe.Json.obj(
        "plugins" -> pluginManifests.map { pm =>
          io.circe.Json.obj(
            "name" -> pm.name.asJson,
            "version" -> pm.version.asJson,
            "description" -> pm.description.asJson,
            "frontend" -> pm.frontend
              .map { fe =>
                io.circe.Json.obj(
                  "scripts" -> fe.scripts.asJson,
                  "styles" -> fe.styles.asJson
                )
              }
              .getOrElse(io.circe.Json.Null)
          )
        }.asJson
      )
      Ok(json.noSpaces, org.http4s.headers.`Content-Type`(org.http4s.MediaType.application.json))

    case req @ GET -> Root / "plugins" / pluginName / path =>
      val pluginDir = nebflow.plugin.PluginLoader.PluginDir / pluginName
      val filePath = pluginDir / os.RelPath(path)
      if filePath.toString.startsWith(pluginDir.toString) && os.exists(filePath) && os.isFile(filePath) then
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
      prefs <- runtimePrefs.getAll
      mcpServers <- mcpManager.listServers.map(_.map { case (id, enabled) =>
        io.circe.Json.obj("id" -> id.asJson, "enabled" -> enabled.asJson)
      })
      _ <- wsHub.broadcast(
        io.circe.Json.obj(
          "type" -> "serverConfig".asJson,
          "streamTimeoutMs" -> (Defaults.StreamTimeoutSec.toLong * 1000).asJson,
          "version" -> nebflow.Version.string.asJson,
          "policy" -> PermissionPolicy.toName(prefs.permissionPolicy).asJson,
          "thinking" -> prefs.thinkingConfig.map(ThinkingConfig.toJson).getOrElse(io.circe.Json.Null),
          "tools" -> toolsList.asJson,
          "language" -> prefs.language.asJson,
          "mcpServers" -> mcpServers.asJson
        )
      )
    yield ()

  private val MaxMessageSize = 1024 * 1024 // 1MB

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
              // Route to the session that asked the question, not the currently active session
              val askSessionId = parse(text).flatMap(_.hcursor.downField("sessionId").as[String]).toOption
              askSessionId match
                case Some(sid) =>
                  ensureRootAgent(sid)
                    .flatMap(ref => IO(ref ! AgentCommand.UserAnswered(answers)))
                    .handleErrorWith(e =>
                      logger.warn(s"Failed to route askUserAnswer to session $sid: ${e.getMessage}")
                    )
                case None =>
                  withActiveAgent(ref => IO(ref ! AgentCommand.UserAnswered(answers)))
            case None => IO.unit

        case "permissionAnswer" =>
          val approved = parse(text).flatMap(_.hcursor.downField("approved").as[Boolean]).getOrElse(false)
          val permSessionId = parse(text).flatMap(_.hcursor.downField("sessionId").as[String]).toOption
          logger.info(s"Permission answer: ${if approved then "approved" else "denied"}") *>
            (permSessionId match
              case Some(sid) =>
                ensureRootAgent(sid)
                  .flatMap(ref => IO(ref ! AgentCommand.PermissionAnswered(approved)))
                  .handleErrorWith(e =>
                    logger.warn(s"Failed to route permissionAnswer to session $sid: ${e.getMessage}")
                  )
              case None =>
                withActiveAgent(ref => IO(ref ! AgentCommand.PermissionAnswered(approved))))

        case "setPolicy" =>
          val policyStr = parse(text).flatMap(_.hcursor.downField("policy").as[String]).getOrElse("ask")
          val policy = PermissionPolicy.fromString(policyStr)
          logger.info(s"Permission policy changed to: $policyStr") *>
            runtimePrefs.setPolicy(policy) *>
            broadcastServerConfig

        case "interrupt" =>
          logger.info("User interrupted") *> withActiveAgent(ref => IO(ref ! AgentCommand.Interrupt()))

        case "command" =>
          val command = parse(text).flatMap(_.hcursor.downField("command").as[String]).getOrElse("")
          command match
            case "clear" =>
              logger.info("Session cleared") *> sessionStore.setActiveMessages(Nil) *>
                reminderStateRef.update(_.copy(highestPressureLevel = 0, writesSinceLastRead = Map.empty)) *>
                withActiveAgent(ref => IO(ref ! AgentCommand.ClearReadTracker))
            case _ => IO.unit

        case "setThinking" =>
          val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
          json.hcursor.downField("thinking").focus match
            case Some(t) if !t.isNull =>
              ThinkingConfig.fromJson(t) match
                case Right(tc) =>
                  logger.info(s"Thinking mode changed to: enabled=${tc.enabled}, budget=${tc.budgetTokens}") *>
                    runtimePrefs.setThinking(Some(tc)) *>
                    broadcastServerConfig
                case Left(err) =>
                  logger.warn(s"Invalid thinking config: $err") *>
                    wsSend(
                      io.circe.Json.obj("type" -> "error".asJson, "message" -> s"Invalid thinking config: $err".asJson)
                    )
            case _ =>
              logger.info("Thinking mode disabled") *>
                runtimePrefs.setThinking(None) *>
                broadcastServerConfig

        case "setMcpEnabled" =>
          val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
          val serverId = json.hcursor.downField("serverId").as[String].getOrElse("")
          val enabled = json.hcursor.downField("enabled").as[Boolean].getOrElse(true)
          if serverId.nonEmpty then
            logger.info(s"MCP server '$serverId' ${if enabled then "enabled" else "disabled"}") *>
              runtimePrefs.setMcpServerEnabled(serverId, enabled) *>
              mcpManager.setEnabled(serverId, enabled) *>
              broadcastServerConfig
          else IO.unit

        case "getModelOptions" =>
          val sessionId = parse(text).flatMap(_.hcursor.downField("sessionId").as[String]).getOrElse("")
          val models = sharedResources.providerRegistry.getAllModels()
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

        case "setSessionModel" =>
          val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
          val sessionId = json.hcursor.downField("sessionId").as[String].getOrElse("")
          val modelRef = json.hcursor.downField("modelRef").as[Option[String]].getOrElse(None)
          if sessionId.nonEmpty then
            (modelRef match
              case Some(ref) =>
                sharedResources.providerRegistry.getCandidateForRef(ref) match
                  case Some(candidate) =>
                    sharedResources.sessionModelOverrides.update(_ + (sessionId -> candidate)) *>
                      sessionStore
                        .updateSessionModel(sessionId, Some(ref))
                        .as(Right(s"${candidate.providerId}/${candidate.model}"))
                  case None =>
                    IO.pure(Left(s"Unknown model: $ref"))
              case None =>
                sharedResources.sessionModelOverrides.update(_ - sessionId) *>
                  sessionStore.updateSessionModel(sessionId, None).as(Right("default"))
            ).flatMap {
              case Right(ref) =>
                wsSend(io.circe.Json.obj("type" -> "sessionModelSet".asJson, "modelRef" -> ref.asJson))
              case Left(err) =>
                wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> err.asJson))
            }
          else IO.unit

        case "switchSession" =>
          val sessionId = parse(text).flatMap(_.hcursor.downField("sessionId").as[String]).getOrElse("")
          if sessionId.nonEmpty then
            sessionService
              .switchSession(sessionId)
              .flatMap { _ =>
                sessionService.sendSessionList(wsSend)
              }
              .handleErrorWith { e =>
                wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
              }
          else IO.unit

        case "createSession" =>
          val name = parse(text).flatMap(_.hcursor.downField("name").as[String]).getOrElse("New Session")
          sessionService
            .createSession(name)
            .flatMap { _ =>
              sessionService.sendSessionList(wsSend)
            }
            .handleErrorWith { e =>
              wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
            }

        case "deleteSession" =>
          val sessionId = parse(text).flatMap(_.hcursor.downField("sessionId").as[String]).getOrElse("")
          if sessionId.nonEmpty then
            removeRootAgent(sessionId) *> sessionService
              .deleteSession(sessionId)
              .flatMap { _ =>
                sessionService.sendSessionList(wsSend)
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
                sessionService.sendSessionList(wsSend)
              }
              .handleErrorWith { e =>
                wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
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
            val always = ToolRegistry.AlwaysAvailable.toList.sorted
            val agentsJson = agents.map { a =>
              // Resolve actual tools: user-configured + auto-injected
              val subagentTools = if a.subagents.nonEmpty then ToolRegistry.SubagentTools else Set.empty[String]
              // Filter out wildcard "*" — it's a runtime marker, not a real tool name
              val userTools = (a.tools.toSet ++ subagentTools).filterNot(_ == "*").toList.sorted
              // User tools + always-available (always-available sent separately for UI distinction)
              val allTools = (userTools ++ always).sorted
              io.circe.Json.obj(
                "name" -> a.name.asJson,
                "description" -> a.description.asJson,
                "tools" -> allTools.asJson,
                "builtInTools" -> always.asJson,
                "subagents" -> a.subagents.asJson
              )
            }
            wsSend(
              io.circe.Json.obj(
                "type" -> "agentList".asJson,
                "agents" -> agentsJson.asJson
              )
            )
          }

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
              meta <- sessionService.createSession(s"Agent: ${defn.name}", agentName = Some(agentName))
              _ <- sessionService.switchSession(meta.id)
              _ <- sessionService.sendSessionList(wsSend)
            yield ()).handleErrorWith { e =>
              wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
            }
          else IO.unit

        case "getConfig" =>
          configService.getConfig.flatMap { cfg =>
            wsSend(
              io.circe.Json.obj(
                "type" -> "configData".asJson,
                "config" -> cfg.asJson
              )
            )
          }

        case "updateConfig" =>
          val cfg = parse(text).flatMap(_.hcursor.downField("config").as[String]).getOrElse("")
          if cfg.nonEmpty then
            configService.updateConfig(cfg).flatMap {
              case Left(err) =>
                wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> err.asJson))
              case Right(_) =>
                wsSend(io.circe.Json.obj("type" -> "configUpdated".asJson, "success" -> true.asJson))
            }
          else IO.unit

        case _ =>
          val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
          val content = json.hcursor.downField("content").as[String].getOrElse("")
          val attachments = json.hcursor.downField("attachments").as[List[io.circe.Json]].getOrElse(Nil)
          val clientMessageId = json.hcursor.downField("clientMessageId").as[Option[String]].getOrElse(None)

          if content.trim == "__interrupt__" then withActiveAgent(ref => IO(ref ! AgentCommand.Interrupt()))
          else if content.nonEmpty || attachments.nonEmpty then
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

                logger.info(s"User message: ${content.take(60)}${if content.length > 60 then "..." else ""}") *>
                  logInputHistory(content, attachments) *>
                  // Record user message as UiMessage for history
                  sessionStore.getActiveId.flatMap { sid =>
                    if sid.nonEmpty then
                      val attJson = attachments.map { att =>
                        val name = att.hcursor.downField("name").as[String].getOrElse("")
                        val mimeType = att.hcursor.downField("mimeType").as[String].getOrElse("")
                        io.circe.Json.obj(
                          "name" -> name.asJson,
                          "type" -> (if mimeType.startsWith("image/") then "image" else "file").asJson
                        )
                      }
                      sharedResources.sessionStore
                        .appendUiMessages(sid, List(UiMessage.User(content, attJson)))
                        .handleErrorWith(e => IO(logger.warn(s"Failed to record user UiMessage: ${e.getMessage}")))
                    else IO.unit
                  } *> {
                    val blocksList = blocks.toList
                    withActiveAgent(ref => IO(ref ! AgentCommand.UserInput(content, None, clientMessageId, Some(blocksList).filter(_.nonEmpty))))
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
            .update(m => if m.contains(sessionId) then m else m.updated(sessionId, System.currentTimeMillis()))
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

      case _ => IO.unit

    record.handleErrorWith(e =>
      IO(logger.warn(s"Failed to record UI message for session $sessionId: ${e.getMessage}"))
    ) *> underlying(json)

  // ============================================================
  // /ask — isolated LLM Q&A (does not affect agent context)
  // ============================================================

  private def executeAsk(
    sessionId: String,
    question: String,
    wsSend: io.circe.Json => IO[Unit]
  ): IO[Unit] =
    sharedResources.askSemaphore.tryAcquire.flatMap { acquired =>
      if !acquired then
        wsSend(
          io.circe.Json.obj(
            "type" -> "askError".asJson,
            "sessionId" -> sessionId.asJson,
            "message" -> "Another ask is in progress, please wait".asJson
          )
        )
      else
        (for
          messages <- sharedResources.sessionStore.loadMessagesForSession(sessionId)
          history = messages.filter(m => m.role == MessageRole.User || m.role == MessageRole.Assistant)
          systemMsg = Message(
            MessageRole.System,
            Left(
              "You are a helpful assistant. The user is asking a follow-up question about an ongoing conversation. Use the provided conversation context to answer concisely. Your response will NOT be saved to the conversation history."
            )
          )
          askMsg = Message(MessageRole.User, Left(question))
          request = LlmRequest(
            messages = systemMsg :: history ::: List(askMsg),
            sessionId = sessionId,
            agentId = "ask",
            tools = None,
            maxTokens = Some(2048)
          )
          answerRef <- IO.ref("")
          metaRef <- IO.ref(Option.empty[LlmMeta])
          _ <- sharedResources.llm
            .sendStream(request)
            .evalMap {
              case StreamChunk.TextDelta(delta) =>
                answerRef.update(_ + delta) *>
                wsSend(
                  io.circe.Json.obj(
                    "type" -> "askTextDelta".asJson,
                    "sessionId" -> sessionId.asJson,
                    "delta" -> delta.asJson
                  )
                )
              case StreamChunk.Done(_, _, meta) =>
                metaRef.set(meta) *>
                wsSend(
                  io.circe.Json.obj(
                    "type" -> "askDone".asJson,
                    "sessionId" -> sessionId.asJson,
                    "durationMs" -> meta.map(_.durationMs).getOrElse(0L).asJson,
                    "model" -> meta.map(_.model).getOrElse("").asJson
                  )
                )
              case _ => IO.unit
            }
            .compile
            .drain
          answer <- answerRef.get
          meta <- metaRef.get
          _ <- sharedResources.sessionStore
            .appendUiMessages(sessionId, List(UiMessage.Ask(question, answer, meta.map(_.durationMs), meta.map(_.model))))
            .handleErrorWith(e => IO(logger.warn(s"Failed to persist ask UiMessage: ${e.getMessage}")))
        yield ())
          .guarantee(sharedResources.askSemaphore.release)
    }

end WebSocketRoutes
