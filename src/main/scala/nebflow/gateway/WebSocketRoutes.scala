package nebflow.gateway

import nebflow.core.PathUtil

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
import nebflow.core.skill.SkillService
import nebflow.core.telemetry.{TaskInferencer, TelemetryReporter}
import nebflow.core.tools.{ToolContext, ToolRegistry}
import nebflow.llm.{Config, NebflowServiceConfig, ThinkingConfig}
import nebflow.service.*
import nebflow.shared.*
import org.apache.pekko.actor.typed.*
import org.apache.pekko.actor.typed.scaladsl.Behaviors
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

  /** Reflect rootAgents for death watcher: ActorRef -> sessionId lookup. */
  private val refToSession: Ref[IO, Map[ActorRef[AgentCommand], String]] =
    Ref.unsafe(Map.empty)

  /**
   * Death-watcher actor: watches root AgentActor refs and cleans up
   * rootAgents/refToSession when an actor stops permanently (exceeds
   * supervision restart limit). Without this, dead refs stay in the map
   * and all future UserInput messages go to DeadLetters silently.
   */
  private val deathWatcher: ActorRef[DeathWatcher.Command] =
    actorSystem.systemActorOf(
      DeathWatcher(rootAgents, refToSession, sharedResources.dispatcher, wsHub),
      "agent-death-watcher"
    )

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
            // Resolve folder-level projectRoot and inherited rules
            folderId = metaOpt.flatMap(_.folderId)
            resolvedProjectRoot <- sharedResources.sessionStore.resolveProjectRoot(folderId)
            agentName = metaOpt.flatMap(_.agentName).getOrElse("Nebula")
            // Compute effective projectRoot: folder setting → agent workspace default
            effectiveProjectRoot <- resolvedProjectRoot match
              case Some(pr) => IO.pure(Some(pr))
              case None =>
                folderId match
                  case Some(fid) =>
                    // Find folder name for default workspace directory
                    val folderName = sharedResources.sessionStore
                      .getFolderName(fid)
                      .getOrElse(fid.take(8))
                    val defaultPath = PathUtil.dataRoot / "agents" / agentName / "projects" / folderName
                    IO.blocking {
                      if !os.exists(defaultPath) then os.makeDir.all(defaultPath)
                    }.as(Some(defaultPath.toString))
                  case None => IO.pure(None)
            // Resolve inherited rules from folder chain
            resolvedRules = folderId.map { fid =>
              nebflow.service.RulesStore.resolveInheritedRules(
                fid,
                id => sharedResources.sessionStore.getFolderParentId(id)
              )
            }.flatten
          yield
            val ref = actorSystem.systemActorOf(
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
                contextWindow = contextWindow,
                projectRoot = effectiveProjectRoot,
                rulesMd = resolvedRules,
                folderId = folderId
              ),
              s"agent-$sessionId"
            )
            (ref, effectiveProjectRoot.getOrElse(""))
          agentIo.flatMap { case (ref, pr) =>
            val hookCtx = nebflow.core.hooks.HookContext(
              sessionId = Some(sessionId),
              projectRoot = pr,
              cwd = pr
            )
            sharedResources.hookEngine
              .onSessionStart(hookCtx)
              .handleErrorWith { e =>
                nebflow.core.NebflowLogger
                  .forName("nebflow.hooks")
                  .warn(s"SessionStart hook failed: ${e.getMessage}")
                  .as(nebflow.core.hooks.HookResult.allow)
              }
              .void *>
              refToSession.update(_ + (ref -> sessionId)) *>
              IO(deathWatcher ! DeathWatcher.Watch(sessionId, ref)) *>
              rootAgents.update(_ + (sessionId -> ref)).as(ref)
          }
    }

  /** Stop and remove the root AgentActor for a session. */
  private def removeRootAgent(sessionId: String): IO[Unit] =
    rootAgents.modify { agents =>
      agents.get(sessionId) match
        case Some(ref) =>
          ref ! AgentCommand.Stop(s"session $sessionId deleted")
          (
            agents - sessionId,
            IO(deathWatcher ! DeathWatcher.Unwatch(sessionId)) *>
              refToSession.update(_ - ref)
          )
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
   * Public API for external sources (e.g. bridge plugins) to inject a user message
   * into a session's agent. Reuses the same logic as WebSocket user messages:
   * rate limiting, UiMessage recording, agent routing.
   */
  def handleBridgeMessage(sessionId: String, content: String, senderId: Option[String] = None): IO[Unit] =
    if content.isEmpty || sessionId.isEmpty then IO.unit
    else
      rateLimiter.check("bridge").flatMap { allowed =>
        if !allowed then logger.warn("Bridge rate limit exceeded")
        else
          val source = senderId.map(id => s"[via bridge:$id]").getOrElse("[via bridge]")
          logger.info(s"Bridge message for session $sessionId: ${content.take(60)}... $source") *>
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
            // Use ExternalEvent instead of UserInput so the message is queued in
            // pendingEvents and can be injected at the next ToolsComplete gap —
            // avoids being stashed until the entire turn finishes.
            routeToAgent(sessionId)(ref =>
              IO(
                ref ! AgentCommand.ExternalEvent(
                  source = "bridge",
                  eventType = "user-message",
                  payload = content,
                  metadata = io.circe.JsonObject("senderId" -> senderId.asJson),
                  correlationId = None
                )
              )
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
          toolsList = ToolRegistry.ALL_TOOLS.map(t =>
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

    // --- Local file serving for card iframes ---
    // GET /api/nf-file?path=xxx&token=xxx — serves whitelisted media files from disk.
    // Used by card iframes to display local images/videos/audio without base64 embedding.
    // Supports Range requests for video seeking.
    case req @ GET -> Root / "api" / "nf-file" =>
      val provided = extractToken(req)
      if !Auth.validateToken(provided, token) then Forbidden("Invalid token")
      else
        val rawPath = req.params.get("path").getOrElse("")
        if rawPath.isEmpty then BadRequest("Missing 'path' parameter")
        else
          // Resolve and validate path
          val expanded = if rawPath.startsWith("~") then sys.props("user.home") + rawPath.substring(1) else rawPath
          val path = java.nio.file.Paths.get(expanded).normalize()
          val ext = path.toString.lastIndexOf('.') match
            case -1 => ""
            case i => path.toString.substring(i + 1).toLowerCase
          // Only allow whitelisted media extensions
          val allowedExt = Set(
            "png",
            "jpg",
            "jpeg",
            "gif",
            "svg",
            "webp",
            "ico",
            "bmp",
            "avif",
            "tiff",
            "tif",
            "mp4",
            "webm",
            "ogg",
            "ogv",
            "mov",
            "mp3",
            "wav",
            "oga",
            "flac",
            "aac",
            "m4a",
            "woff",
            "woff2",
            "ttf",
            "otf",
            "pdf"
          )
          if !allowedExt.contains(ext) then BadRequest("File type not allowed")
          else if !java.nio.file.Files.exists(path) || !java.nio.file.Files.isRegularFile(path) then NotFound()
          else StaticFile.fromPath(fs2.io.file.Path(path.toString), Some(req)).getOrElseF(NotFound())
        end if
      end if

    case req @ GET -> Root =>
      StaticFile.fromResource("web/index.html", Some(req)).getOrElseF(NotFound())

    case req @ GET -> Root / "css" / file =>
      StaticFile.fromResource(s"web/css/$file", Some(req)).getOrElseF(NotFound())

    case req @ GET -> Root / "js" / "locales" / file =>
      StaticFile.fromResource(s"web/js/locales/$file", Some(req)).getOrElseF(NotFound())

    case req @ GET -> Root / "js" / file =>
      StaticFile.fromResource(s"web/js/$file", Some(req)).getOrElseF(NotFound())

    case req @ GET -> Root / "vendor" / file =>
      StaticFile.fromResource(s"web/vendor/$file", Some(req)).getOrElseF(NotFound())

    case req @ GET -> Root / "vendor" / "fonts" / file =>
      StaticFile.fromResource(s"web/vendor/fonts/$file", Some(req)).getOrElseF(NotFound())

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
      end if
  }

  private val inputHistoryPath = PathUtil.dataRoot / "input_history.jsonl"

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

    end if

  end logInputHistory

  private def broadcastServerConfig: IO[Unit] =
    val toolsList =
      ToolRegistry.ALL_TOOLS.map(t => io.circe.Json.obj("name" -> t.name.asJson, "description" -> t.description.asJson))
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

    end for

  end broadcastServerConfig

  /** Persist thinking config to nebflow.json — targeted field update. */
  private def persistThinkingConfig(tc: ThinkingConfig): IO[Unit] =
    IO.blocking {
      val path = nebflow.llm.Config.DefaultConfigPath
      val existing = if os.exists(path) then os.read(path) else "{}"
      parse(existing).foreach { json =>
        val updated = json.mapObject { obj =>
          obj.add("thinkingConfig", tc.asJson)
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
      val rulesFolderIds = folders.filter(f => nebflow.service.RulesStore.exists(f.id)).map(_.id)
      wsSend(
        io.circe.Json.obj(
          "type" -> "agentSessionList".asJson,
          "agentName" -> agentName.asJson,
          "sessions" -> sessions.asJson,
          "folders" -> folders.asJson,
          "foldersWithRules" -> rulesFolderIds.asJson
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
          )
        )
      )
    }

  private val MaxMessageSize = 10 * 1024 * 1024 // 10MB (base64 images can be large)

  /** Public facade for REST API to call into the same message handler. */
  def handleMessagePublic(text: String, wsSend: io.circe.Json => IO[Unit]): IO[Unit] =
    handleMessage(text, wsSend)

  private def handleMessage(
    text: String,
    wsSend: io.circe.Json => IO[Unit]
  ): IO[Unit] =
    if text.length > MaxMessageSize then logger.warn(s"Dropping oversized WebSocket message (${text.length} bytes)")
    else
      parse(text).toOption.getOrElse(io.circe.Json.Null).hcursor.downField("type").as[String].getOrElse("") match
        case "askUserAnswer" =>
          val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
          val hc = json.hcursor
          (hc.downField("answers").as[List[String]], hc.downField("sessionId").as[String]) match
            case (Right(answers), Right(askSessionId)) =>
              val answerText = answers.mkString("\n")
              sessionStore.appendUiMessages(askSessionId, List(UiMessage.User(answerText))) *>
                routeToAgent(askSessionId)(ref => IO(ref ! AgentCommand.UserAnswered(answers)))
            case _ => IO.unit

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
                  List(UiMessage.System("Context cleared. LLM memory reset.", Some("slash.clearDone")))
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
          val budgetTokens = thinkingOpt match
            case None | Some(io.circe.Json.Null) => 32000
            case Some(v) => v.hcursor.downField("budgetTokens").as[Int].getOrElse(32000)
          val tc = ThinkingConfig(enabled, budgetTokens)
          logger.info(s"Thinking mode set to: enabled=$enabled budgetTokens=$budgetTokens") *>
            sharedResources.thinkingConfigRef.set(tc) *>
            persistThinkingConfig(tc) *>
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
            // Emit session_end for the session being left
            sessionStore.getActiveId.flatMap { oldId =>
              if oldId.nonEmpty && oldId != sessionId then emitSessionEnd(oldId)
              else IO.unit
            } *>
              // Cloud pull: fetch latest session data before switching (best-effort)
              (nebflow.core.tools.MeshTool.currentIncrementalSyncEngine match
                case Some(engine) =>
                  engine.pullSessionIncremental(sessionId).handleErrorWith(_ => IO.unit)
                case None => IO.unit) *> sessionService
                .switchSession(sessionId)
                .flatMap { _ =>
                  val telStart = sharedResources.telemetry.fold(IO.unit)(
                    _.record("session_start", io.circe.JsonObject("session_id" -> sessionId.asJson))
                  )
                  sendAgentSessionList(wsSend, sessionId) *>
                    sendMemoryStatus(wsSend, sessionId) *> telStart
                }
                .handleErrorWith { e =>
                  wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
                }
          else IO.unit
          end if

        case "createSession" =>
          val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
          val name = json.hcursor.downField("name").as[String].getOrElse("New Session")
          val agentName = json.hcursor.downField("agentName").as[Option[String]].getOrElse(None)
          val folderId = json.hcursor.downField("folderId").as[Option[String]].getOrElse(None)
          sessionService
            .createSession(name, agentName = agentName, folderId = folderId)
            .flatMap { meta =>
              val tel = sharedResources.telemetry
              val telStart =
                tel.fold(IO.unit)(_.record("session_start", io.circe.JsonObject("session_id" -> meta.id.asJson)))
              // Send filtered session list for the agent tab
              val sendList = agentName match
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
              sendList *> telStart
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
                // Emit session_end telemetry before cleanup
                emitSessionEnd(sessionId).handleErrorWith(_ => IO.unit) *>
                  // Clean up text buffers for deleted session to prevent memory leak
                  sessionTextBuffers.update(_ - sessionId) *>
                  sessionThinkingBuffers.update(_ - sessionId) *>
                  sessionTurnStarts.update(_ - sessionId) *>
                  removeRootAgent(sessionId) *> sessionService
                    .deleteSession(sessionId)
                    .flatMap { _ =>
                      // Cloud sync: delete from cloud (fire-and-forget)
                      nebflow.core.tools.MeshTool.currentCloudSessionSync.foreach { css =>
                        sharedResources.dispatcher.unsafeRunAndForget(
                          css.deleteFromCloud(sessionId).handleErrorWith(_ => IO.unit)
                        )
                      }
                      sendAgentSessionListByName(wsSend, agentName)
                    }
              }
              .handleErrorWith { e =>
                wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
              }
          else IO.unit
          end if

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
                      sessionThinkingBuffers.update(_ - sid) *>
                      sessionTurnStarts.update(_ - sid) *>
                      removeRootAgent(sid) *>
                      sessionService.deleteSession(sid) *>
                      // Cloud sync: delete from cloud (fire-and-forget)
                      IO {
                        nebflow.core.tools.MeshTool.currentCloudSessionSync.foreach { css =>
                          sharedResources.dispatcher.unsafeRunAndForget(
                            css.deleteFromCloud(sid).handleErrorWith(_ => IO.unit)
                          )
                        }
                      }
                  }
                  .flatMap { _ =>
                    sendAgentSessionListByName(wsSend, agentName)
                  }
              }
              .handleErrorWith { e =>
                wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
              }
          else IO.unit
          end if

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

        case "getSkills" =>
          SkillService.listSkills().flatMap { skills =>
            wsSend(
              io.circe.Json.obj(
                "type" -> "skillList".asJson,
                "skills" -> skills.asJson
              )
            )
          }

        case "skill" =>
          val skillJson = parse(text).toOption.getOrElse(io.circe.Json.Null)
          val skillName = skillJson.hcursor.downField("skillName").as[String].getOrElse("")
          val skillInput = skillJson.hcursor.downField("input").as[String].getOrElse("")
          val skillSessionId = skillJson.hcursor.downField("sessionId").as[String].getOrElse("")
          if skillName.nonEmpty && skillSessionId.nonEmpty then
            // Persist user message and skill activation system bubble to session history,
            // so they survive session switching (frontend rebuilds DOM from backend history).
            sharedResources.sessionStore.appendUiMessages(
              skillSessionId,
              List(
                UiMessage.User(skillInput),
                UiMessage.System(
                  s"Using skill: $skillName",
                  Some("slash.skillActivated"),
                  Some(io.circe.Json.obj("skillName" -> skillName.asJson))
                )
              )
            ) *>
              executeSkill(skillName, skillInput, skillSessionId, wsSend).handleErrorWith { e =>
                logger.warn(s"Skill '$skillName' failed for session $skillSessionId: ${e.getMessage}")
                wsSend(
                  io.circe.Json.obj(
                    "type" -> "skillError".asJson,
                    "sessionId" -> skillSessionId.asJson,
                    "message" -> s"Skill failed: ${e.getMessage.take(200)}".asJson
                  )
                )
              }
          else IO.unit
          end if

        // ===== Scheduled Task Management =====

        case "createScheduledTask" =>
          val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
          val hc = json.hcursor
          val crSessionId = hc.downField("sessionId").as[String].getOrElse("")
          val crContent = hc.downField("content").as[String].getOrElse("")
          val crTriggerAt = hc.downField("triggerAt").as[Long].getOrElse(0L)
          val crRefPath = hc.downField("referencePath").as[Option[String]].getOrElse(None)
          if crSessionId.nonEmpty && crContent.nonEmpty && crTriggerAt > System.currentTimeMillis() then
            val task = nebflow.core.scheduler.ScheduledTask.create(crSessionId, crContent, crTriggerAt, crRefPath)
            sharedResources.scheduledTaskStore.addTask(task).flatMap { _ =>
              sharedResources.scheduledTaskActorRef.foreach(
                _ ! nebflow.core.scheduler.ScheduledTaskCommand.TaskCreated(task)
              )
              wsSend(
                io.circe.Json.obj(
                  "type" -> "scheduledTaskCreated".asJson,
                  "task" -> io.circe.Json.obj(
                    "id" -> task.id.asJson,
                    "content" -> task.content.asJson,
                    "triggerAt" -> task.triggerAt.asJson,
                    "createdAt" -> task.createdAt.asJson,
                    "referencePath" -> task.referencePath.asJson
                  )
                )
              )
            }
          else
            val reason =
              if crSessionId.isEmpty then "missing sessionId"
              else if crContent.isEmpty then "missing content"
              else if crTriggerAt <= System.currentTimeMillis() then "triggerAt must be in the future"
              else "unknown"
            wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> s"Invalid scheduled task: $reason".asJson))
          end if

        case "listScheduledTasks" =>
          val lrSessionId = parse(text).flatMap(_.hcursor.downField("sessionId").as[String]).getOrElse("")
          if lrSessionId.nonEmpty then
            sharedResources.scheduledTaskStore.loadTasks(lrSessionId).flatMap { tasks =>
              val taskJsons = tasks.map { t =>
                io.circe.Json.obj(
                  "id" -> t.id.asJson,
                  "content" -> t.content.asJson,
                  "triggerAt" -> t.triggerAt.asJson,
                  "createdAt" -> t.createdAt.asJson,
                  "triggered" -> t.triggered.asJson,
                  "triggeredAt" -> t.triggeredAt.asJson,
                  "referencePath" -> t.referencePath.asJson
                )
              }
              wsSend(
                io.circe.Json.obj(
                  "type" -> "scheduledTaskList".asJson,
                  "tasks" -> taskJsons.asJson,
                  "sessionId" -> lrSessionId.asJson
                )
              )
            }
          else IO.unit
          end if

        case "deleteScheduledTask" =>
          val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
          val drSessionId = json.hcursor.downField("sessionId").as[String].getOrElse("")
          val drId = json.hcursor.downField("id").as[String].getOrElse("")
          if drSessionId.nonEmpty && drId.nonEmpty then
            sharedResources.scheduledTaskStore.deleteTask(drSessionId, drId).flatMap { _ =>
              sharedResources.scheduledTaskActorRef.foreach(
                _ ! nebflow.core.scheduler.ScheduledTaskCommand.TaskDeleted(drSessionId, drId)
              )
              wsSend(
                io.circe.Json.obj(
                  "type" -> "scheduledTaskDeleted".asJson,
                  "id" -> drId.asJson,
                  "sessionId" -> drSessionId.asJson
                )
              )
            }
          else IO.unit

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
                  val logMsg =
                    if cancelled then s"Cancelled background job $jobId"
                    else s"Background job $jobId not found or already completed"
                  logger.info(logMsg, "sessionId" -> cancelSessionId, "jobId" -> jobId) *>
                    // Notify agent so it can process cancellation
                    routeToAgent(cancelSessionId) { ref =>
                      IO(
                        ref ! AgentCommand.ExternalEvent(
                          source = "background-task",
                          eventType = "cancelled",
                          payload = s"[Background task cancelled] Job ID: $jobId",
                          metadata = io.circe.JsonObject(
                            "jobId" -> jobId.asJson
                          ),
                          correlationId = Some(jobId)
                        )
                      )
                    } *>
                    // Send completion update to frontend so the task is removed from the dropdown
                    wsSend(
                      io.circe.Json.obj(
                        "type" -> "backgroundTaskUpdate".asJson,
                        "sessionId" -> cancelSessionId.asJson,
                        "taskId" -> jobId.asJson,
                        "description" -> "".asJson,
                        "status" -> "completed".asJson
                      )
                    )
                }
              }
              .handleErrorWith { e =>
                logger.warn(s"cancelBackgroundJob failed for session=$cancelSessionId job=$jobId: ${e.getMessage}")
                // Send a completion update even on error, so the frontend removes the task
                wsSend(
                  io.circe.Json.obj(
                    "type" -> "backgroundTaskUpdate".asJson,
                    "sessionId" -> cancelSessionId.asJson,
                    "taskId" -> jobId.asJson,
                    "description" -> "".asJson,
                    "status" -> "failed".asJson
                  )
                ).handleErrorWith(_ => IO.unit)
              }
          else IO.unit
          end if

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
            // All builtin tools are configurable — MCP tools controlled via mcpServers
            val configurableTools = ToolRegistry.builtinToolNames
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
                "availableTools" -> configurableTools.asJson
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

        case "setFolderProjectRoot" =>
          val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
          val folderId = json.hcursor.downField("folderId").as[String].getOrElse("")
          val projectRoot = json.hcursor.downField("projectRoot").as[Option[String]].getOrElse(None)
          if folderId.nonEmpty then
            sessionService
              .setFolderProjectRoot(folderId, projectRoot)
              .flatMap {
                case Right(_) =>
                  // Use the folder's own agent name, not the active session's,
                  // to ensure the frontend receives the update regardless of which agent tab is active.
                  sessionStore.getFolderAgentName(folderId).flatMap { agentOpt =>
                    val agentName = agentOpt.getOrElse("Nebula")
                    sendAgentSessionListByName(wsSend, agentName)
                  }
                case Left(err) =>
                  wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> err.asJson))
              }
              .handleErrorWith { e =>
                wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
              }
          else IO.unit
          end if

        // Directory browser for project root selection
        case "browsePath" =>
          val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
          val path = json.hcursor.downField("path").as[String].getOrElse("~")
          val expanded = if path.startsWith("~") then System.getProperty("user.home") + path.drop(1) else path
          IO.blocking {
            val dir = os.Path(expanded, os.pwd)
            if os.isDir(dir) then
              val entries = os.list(dir).filter(os.isDir).sortBy(_.last)
              val result = entries.take(200).map { p =>
                io.circe.Json.obj("name" -> p.last.asJson, "path" -> p.toString.asJson)
              }
              io.circe.Json.obj(
                "type" -> "browseResult".asJson,
                "path" -> dir.toString.asJson,
                "entries" -> result.asJson
              )
            else
              io.circe.Json.obj(
                "type" -> "browseResult".asJson,
                "path" -> path.asJson,
                "entries" -> io.circe.Json.arr()
              )
            end if
          }.flatMap(wsSend)
            .handleErrorWith { e =>
              wsSend(
                io.circe.Json.obj(
                  "type" -> "browseResult".asJson,
                  "path" -> path.asJson,
                  "entries" -> io.circe.Json.arr(),
                  "error" -> e.getMessage.asJson
                )
              )
            }

        // ===== Folder Rules Management =====

        case "getRules" =>
          val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
          val folderId = json.hcursor.downField("folderId").as[String].getOrElse("")
          if folderId.nonEmpty then
            val content = RulesStore.loadFolderRules(folderId).getOrElse("")
            wsSend(
              io.circe.Json.obj(
                "type" -> "rulesData".asJson,
                "folderId" -> folderId.asJson,
                "content" -> content.asJson
              )
            )
          else IO.unit

        case "saveRules" =>
          val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
          val folderId = json.hcursor.downField("folderId").as[String].getOrElse("")
          val content = json.hcursor.downField("content").as[String].getOrElse("")
          if folderId.nonEmpty then
            RulesStore.saveFolderRules(folderId, content) *>
              wsSend(io.circe.Json.obj("type" -> "rulesSaved".asJson, "folderId" -> folderId.asJson))
          else IO.unit

        case "deleteRules" =>
          val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
          val folderId = json.hcursor.downField("folderId").as[String].getOrElse("")
          if folderId.nonEmpty then
            RulesStore.deleteFolderRules(folderId) *>
              wsSend(io.circe.Json.obj("type" -> "rulesDeleted".asJson, "folderId" -> folderId.asJson))
          else IO.unit

        case "rulesStatus" =>
          val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
          val folderId = json.hcursor.downField("folderId").as[String].getOrElse("")
          if folderId.nonEmpty then
            wsSend(
              io.circe.Json.obj(
                "type" -> "rulesStatus".asJson,
                "folderId" -> folderId.asJson,
                "exists" -> RulesStore.exists(folderId).asJson,
                "preview" -> RulesStore.preview(folderId).asJson
              )
            )
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
              val folderId = metaOpt.flatMap(_.folderId).getOrElse("")
              val content = scope match
                case "user" => MemoryStore.loadUserMemory.getOrElse("")
                case "agent" => MemoryStore.loadAgentMemory(agentName).getOrElse("")
                case "folder" =>
                  if folderId.nonEmpty then MemoryStore.loadFolderMemory(folderId).getOrElse("") else ""
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
              val folderId = metaOpt.flatMap(_.folderId).getOrElse("")
              val save = scope match
                case "user" => MemoryStore.saveUserMemory(content)
                case "agent" => MemoryStore.saveAgentMemory(agentName, content)
                case "folder" =>
                  if folderId.nonEmpty then MemoryStore.saveFolderMemory(folderId, content) else IO.unit
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
            val folderId = metaOpt.flatMap(_.folderId).getOrElse("")
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
                "folder" -> io.circe.Json.obj(
                  "exists" -> (folderId.nonEmpty && MemoryStore.folderExists(folderId)).asJson,
                  "preview" -> (if folderId.nonEmpty then MemoryStore.folderPreview(folderId) else None).asJson
                )
              )
            )
          }

        case "getCardDesign" =>
          val path = java.nio.file.Paths.get(sys.props("user.home"), ".nebflow", "card-design-prompt.md")
          IO.blocking {
            if java.nio.file.Files.exists(path) then
              new String(java.nio.file.Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8)
            else ""
          }.flatMap { content =>
            wsSend(
              io.circe.Json.obj(
                "type" -> "cardDesignData".asJson,
                "content" -> content.asJson
              )
            )
          }

        case "saveCardDesign" =>
          val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
          val content = json.hcursor.downField("content").as[String].getOrElse("")
          val path = java.nio.file.Paths.get(sys.props("user.home"), ".nebflow", "card-design-prompt.md")
          IO.blocking {
            java.nio.file.Files.write(path, content.getBytes(java.nio.charset.StandardCharsets.UTF_8))
          }.flatMap { _ =>
            wsSend(io.circe.Json.obj("type" -> "cardDesignSaved".asJson, "ok" -> true.asJson))
          }.handleErrorWith { e =>
            wsSend(
              io.circe.Json
                .obj("type" -> "error".asJson, "message" -> s"Failed to save card design: ${e.getMessage}".asJson)
            )
          }

        case "checkUpdate" =>
          val currentVer = nebflow.Version.string
          val result = IO
            .blocking {
              try
                val url = "https://api.github.com/repos/MashiroKai/Nebflow/releases/latest"
                val extract = (j: io.circe.Json) =>
                  for
                    tag <- j.hcursor.downField("tag_name").as[String].toOption
                    name <- j.hcursor.downField("name").as[String].toOption
                  yield (tag, name)
                val conn = new java.net.URL(url).openConnection()
                conn.setConnectTimeout(5000)
                conn.setReadTimeout(5000)
                val raw = new String(conn.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                io.circe.parser.parse(raw).toOption.flatMap(extract)
              catch case _: Exception => None
            }
            .flatMap {
              case Some((tag, releaseName)) =>
                val latestVer = tag.stripPrefix("v")
                val hasUpdate = latestVer != currentVer
                wsSend(
                  io.circe.Json.obj(
                    "type" -> "updateCheckResult".asJson,
                    "currentVersion" -> currentVer.asJson,
                    "latestVersion" -> latestVer.asJson,
                    "hasUpdate" -> hasUpdate.asJson,
                    "releaseName" -> releaseName.asJson
                  )
                )
              case None =>
                wsSend(
                  io.circe.Json.obj(
                    "type" -> "updateCheckResult".asJson,
                    "currentVersion" -> currentVer.asJson,
                    "error" -> "Failed to check for updates".asJson
                  )
                )
            }
          result

        case "doUpdate" =>
          wsSend(io.circe.Json.obj("type" -> "updateStarted".asJson)) *>
            IO.blocking {
              import sys.process.*
              val isWindows = System.getProperty("os.name").toLowerCase.contains("win")
              val script =
                if isWindows then """powershell -Command "& { iwr https://nebflow.space/install.ps1 | iex }" """
                else "curl -fsSL https://nebflow.space/install.sh | sh"
              val exitCode = script.!
              if exitCode == 0 then
                wsSend(io.circe.Json.obj("type" -> "updateCompleted".asJson, "success" -> true.asJson))
              else
                wsSend(
                  io.circe.Json
                    .obj(
                      "type" -> "updateCompleted".asJson,
                      "success" -> false.asJson,
                      "error" -> s"Exit code: $exitCode".asJson
                    )
                )
            }.flatten
              .handleErrorWith { e =>
                wsSend(
                  io.circe.Json
                    .obj("type" -> "updateCompleted".asJson, "success" -> false.asJson, "error" -> e.getMessage.asJson)
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
          val chatWidth = json.hcursor.downField("chatWidth").as[Int].getOrElse(0)

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
                // Resolve projectRoot for local file search before processing attachments
                (for
                  metaOpt <- sessionStore.getSessionMeta(msgSessionId)
                  folderId = metaOpt.flatMap(_.folderId)
                  projectRoot <- sessionStore.resolveProjectRoot(folderId)
                yield (metaOpt, projectRoot)).flatMap { (metaOpt, projectRoot) =>
                  val blocks = scala.collection.mutable.ListBuffer.empty[ContentBlock]
                  if content.nonEmpty then blocks += ContentBlock.Text(content)

                  val savedPaths = scala.collection.mutable.ListBuffer.empty[String]
                  attachments.foreach { att =>
                    val mimeType = att.hcursor.downField("mimeType").as[String].getOrElse("")
                    val data = att.hcursor.downField("data").as[String].getOrElse("")
                    val name = att.hcursor.downField("name").as[String].getOrElse("")
                    val hash = att.hcursor.downField("hash").as[String].getOrElse("")
                    val fileSize = att.hcursor.downField("size").as[Long].getOrElse(0L)
                    if mimeType.startsWith("image/") && data.nonEmpty then blocks += ContentBlock.Image(data, mimeType)
                    else if data.nonEmpty then
                      // Try to find the file locally by name + size + hash
                      // Search priority: project root → common user dirs → full home
                      val home = os.home.toString
                      val commonDirs = List("Downloads", "Desktop", "Documents")
                        .map(d => s"$home/$d")
                        .filter(d => java.nio.file.Files.isDirectory(java.nio.file.Path.of(d)))
                      val searchPaths = projectRoot.toList ::: commonDirs ::: List(home)
                      val localPath =
                        if hash.nonEmpty && fileSize > 0 then findLocalFile(name, hash, fileSize, searchPaths) else None
                      localPath match
                        case Some(path) =>
                          savedPaths += path
                          blocks += ContentBlock.Text(s"[用户附加文件: $path]")
                          logger.info(s"Attachment '$name' resolved to local file: $path")
                        case None =>
                          // Fallback: save file to disk, send path reference to LLM
                          val uploadDir = Config.NebflowHome / "uploads" / msgSessionId
                          try
                            os.makeDir.all(uploadDir)
                            val safeName = name.replaceAll("[/\\\\]", "_").replace("..", "_")
                            val fileName = s"${System.nanoTime()}_$safeName"
                            val filePath = uploadDir / fileName
                            os.write.over(filePath, java.util.Base64.getDecoder.decode(data))
                            val absPath = filePath.toString
                            val decodedSize = java.util.Base64.getDecoder.decode(data).length
                            if absPath.startsWith(uploadDir.toString) then
                              savedPaths += absPath
                              blocks += ContentBlock.Text(s"[用户附加文件: $absPath]")
                              logger.info(s"Saved attachment '$name' to $absPath ($decodedSize bytes)")
                            else
                              logger.warn(s"Attachment '$name' resolved outside upload dir, skipping")
                              blocks += ContentBlock.Text(s"[file: $name (path unsafe)]")
                          catch
                            case e: Exception =>
                              logger.warn(s"Failed to save attachment '$name': ${e.getMessage}")
                              blocks += ContentBlock.Text(s"[file: $name (保存失败)]")
                          end try
                      end match
                    end if
                  }

                  val sessionName = metaOpt.map(_.name).getOrElse("-")
                  logger.info(s"${logger.hl(sessionName)} User message: ${content
                      .take(60)}${if content.length > 60 then "..." else ""}") *>
                    logInputHistory(content, attachments) *>
                    // Record user message as UiMessage for history
                    (if msgSessionId.nonEmpty then
                       val attJson = attachments.zipWithIndex.map { case (att, idx) =>
                         val name = att.hcursor.downField("name").as[String].getOrElse("")
                         val mimeType = att.hcursor.downField("mimeType").as[String].getOrElse("")
                         val savedPath =
                           if !mimeType.startsWith("image/") && idx < savedPaths.length then savedPaths(idx) else ""
                         io.circe.Json.obj(
                           "name" -> name.asJson,
                           "type" -> (if mimeType.startsWith("image/") then "image" else "file").asJson,
                           "path" -> (if savedPath.nonEmpty then savedPath.asJson else Json.Null)
                         )
                       }
                       val injected = json.hcursor.downField("injected").as[Boolean].getOrElse(false)
                       sharedResources.sessionStore
                         .appendUiMessages(msgSessionId, List(UiMessage.User(content, attJson, injected)))
                         .handleErrorWith(e => IO(logger.warn(s"Failed to record user UiMessage: ${e.getMessage}")))
                     else IO.unit) *> {
                      // Track turn count + session start time for telemetry
                      sessionTurnCounts
                        .update(m => m.updated(msgSessionId, m.getOrElse(msgSessionId, 0) + 1))
                        .handleErrorWith(_ => IO.unit) *>
                        sessionStartTimes
                          .update { m =>
                            if m.contains(msgSessionId) then m else m.updated(msgSessionId, System.currentTimeMillis())
                          }
                          .handleErrorWith(_ => IO.unit) *> {
                          // Telemetry: message_sent (structural features only, no content)
                          sharedResources.telemetry.fold(IO.unit)(
                            _.record(
                              "message_sent",
                              JsonObject.fromIterable(
                                List(
                                  "session_id" -> msgSessionId.asJson,
                                  "prompt_length" -> content.length.asJson,
                                  "language_hint" -> (if content.exists(_ > 0x4e00) then "chinese"
                                                      else "english").asJson,
                                  "has_code_block" -> content.contains("```").asJson
                                )
                              )
                            ).handleErrorWith(_ => IO.unit)
                          ) *> {
                            val blocksList = blocks.toList
                            // Fire-and-forget: acquire cloud busy lock to signal other devices
                            nebflow.core.tools.MeshTool.currentCloudSessionSync.foreach { css =>
                              sharedResources.dispatcher.unsafeRunAndForget(
                                css.tryAcquireBusy(msgSessionId).handleErrorWith(_ => IO.unit)
                              )
                            }
                            routeToAgent(msgSessionId)(ref =>
                              IO(
                                ref ! AgentCommand
                                  .UserInput(
                                    content,
                                    None,
                                    clientMessageId,
                                    Some(blocksList).filter(_.nonEmpty),
                                    chatWidth
                                  )
                              )
                            )
                          }
                        }
                    }
                }
            }
          else IO.unit
          end if
    end if
  end handleMessage

  // ============================================================
  // Local file search for smart attachment resolution
  // ============================================================

  /** Directories to skip during filesystem search — build artifacts, caches, system dirs. */
  private val skipDirs = Set(
    "node_modules",
    ".git",
    "build",
    "target",
    "dist",
    ".cache",
    "__pycache__",
    ".gradle",
    ".idea",
    ".vscode",
    ".nebflow",
    "Library",
    "Applications",
    "Trash",
    ".Trash",
    ".npm",
    ".yarn",
    ".pnpm-store",
    ".cargo",
    ".rustup",
    ".conda",
    ".venv",
    "venv",
    "DerivedData",
    ".m2",
    ".ivy2",
    ".sbt",
    ".coursier",
    "Pods",
    "vendor",
    "bower_components",
    "Movies"
  )

  /**
   * Search for a file by name and verify its SHA-256 hash matches.
   * Searches directories in priority order. Does NOT follow symlinks.
   * First matches by filename + size (cheap), then verifies by SHA-256 (expensive).
   *
   * @param name filename to search for
   * @param expectedHash SHA-256 hex hash of the uploaded file
   * @param expectedSize file size in bytes (used for fast pre-filtering)
   * @param searchPaths directories to search in priority order
   * @return absolute path of a matching local file, or None
   */
  private def findLocalFile(
    name: String,
    expectedHash: String,
    expectedSize: Long,
    searchPaths: List[String]
  ): Option[String] =
    import java.nio.file.{FileVisitResult, Files, Path, SimpleFileVisitor}
    import java.security.MessageDigest

    val targetName = name.replaceAll("[/\\\\]", "_").replace("..", "_")
    if targetName.isEmpty then None
    else
      val candidates = scala.collection.mutable.ListBuffer.empty[(Path, Long, Long)] // (path, mtime, size)

      for searchRoot <- searchPaths if candidates.isEmpty do
        val rootPath = Path.of(searchRoot)
        if Files.isDirectory(rootPath) then
          try
            // No FOLLOW_LINKS — avoids symlink loops on macOS bundles (.fcpcache etc.)
            Files.walkFileTree(
              rootPath,
              java.util.EnumSet.noneOf(classOf[java.nio.file.FileVisitOption]),
              Integer.MAX_VALUE,
              new SimpleFileVisitor[Path]:
                override def preVisitDirectory(
                  dir: Path,
                  attrs: java.nio.file.attribute.BasicFileAttributes
                ): FileVisitResult =
                  if skipDirs.contains(dir.getFileName.toString) then FileVisitResult.SKIP_SUBTREE
                  else FileVisitResult.CONTINUE

                override def visitFile(
                  file: Path,
                  attrs: java.nio.file.attribute.BasicFileAttributes
                ): FileVisitResult =
                  // Fast filter: match filename + size before expensive hash computation
                  if file.getFileName.toString == targetName && attrs.isRegularFile && attrs.size == expectedSize then
                    candidates += ((file, attrs.lastModifiedTime.toMillis, attrs.size))
                  FileVisitResult.CONTINUE

                override def visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult =
                  FileVisitResult.CONTINUE
            )
          catch
            case _: Exception => // skip inaccessible directories
        end if
      end for
      if candidates.isEmpty then None
      else
        // Verify SHA-256 for each candidate (already pre-filtered by name + size)
        val hashMatches = candidates.toList.filter { (path, _, _) =>
          try
            val bytes = Files.readAllBytes(path)
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val hex = digest.map(b => String.format("%02x", b)).mkString
            hex == expectedHash
          catch case _: Exception => false
        }

        hashMatches match
          case Nil => None
          case (p, _, _) :: Nil => Some(p.toString)
          case multiple =>
            // Multiple identical copies: pick the most recently modified with shortest path
            val sorted = multiple.sortBy { (path, mtime, _) =>
              (-mtime, path.toString.length)
            }
            val chosen = sorted.head._1.toString
            logger.info(s"Attachment '$name': ${multiple.size} local copies with same hash, chose: $chosen")
            Some(chosen)
      end if
    end if
  end findLocalFile

  // ============================================================
  // UI Message recording — wraps wsSend to persist frontend-renderable history
  // ============================================================

  /** Per-session accumulator for text deltas (emitted one-by-one, saved on textDone). */
  private val sessionTextBuffers: Ref[IO, Map[String, String]] =
    Ref.unsafe[IO, Map[String, String]](Map.empty)

  /** Per-session accumulator for thinking deltas. */
  private val sessionThinkingBuffers: Ref[IO, Map[String, String]] =
    Ref.unsafe[IO, Map[String, String]](Map.empty)

  /** Per-session turn start time — set on first streaming event, consumed on done. */
  private val sessionTurnStarts: Ref[IO, Map[String, Long]] =
    Ref.unsafe[IO, Map[String, Long]](Map.empty)

  // ---- Per-session telemetry tracking ----

  /** Per-session tool call counts: sessionId -> (toolName -> count). */
  private val sessionToolProfile: Ref[IO, Map[String, Map[String, Int]]] =
    Ref.unsafe[IO, Map[String, Map[String, Int]]](Map.empty)

  /** Per-session turn count (user messages). */
  private val sessionTurnCounts: Ref[IO, Map[String, Int]] =
    Ref.unsafe[IO, Map[String, Int]](Map.empty)

  /** Per-session start time (first activity). */
  private val sessionStartTimes: Ref[IO, Map[String, Long]] =
    Ref.unsafe[IO, Map[String, Long]](Map.empty)

  /** Per-session model used (last model). */
  private val sessionModels: Ref[IO, Map[String, String]] =
    Ref.unsafe[IO, Map[String, String]](Map.empty)

  /** Per-session all models used (accumulated). */
  private val sessionAllModels: Ref[IO, Map[String, Set[String]]] =
    Ref.unsafe[IO, Map[String, Set[String]]](Map.empty)

  /** Per-session response times in milliseconds (per turn). */
  private val sessionResponseTimes: Ref[IO, Map[String, List[Long]]] =
    Ref.unsafe[IO, Map[String, List[Long]]](Map.empty)

  /** Record a tool call for a session's telemetry profile. */
  private def recordToolForTelemetry(sessionId: String, toolName: String): IO[Unit] =
    sessionToolProfile.update { m =>
      val profile = m.getOrElse(sessionId, Map.empty)
      m.updated(sessionId, profile.updated(toolName, profile.getOrElse(toolName, 0) + 1))
    } *> sessionStartTimes.update { m =>
      if m.contains(sessionId) then m else m.updated(sessionId, System.currentTimeMillis())
    }

  /** Emit a session_end event and clean up telemetry state for a session. */
  private def emitSessionEnd(sessionId: String): IO[Unit] =
    sharedResources.telemetry match
      case None => cleanupSessionTelemetry(sessionId)
      case Some(reporter) =>
        for
          profile <- sessionToolProfile.get.map(_.getOrElse(sessionId, Map.empty))
          turns <- sessionTurnCounts.get.map(_.getOrElse(sessionId, 0))
          startTime <- sessionStartTimes.get.map(_.get(sessionId))
          model <- sessionModels.get.map(_.get(sessionId).getOrElse(""))
          allModels <- sessionAllModels.get.map(_.getOrElse(sessionId, Set.empty))
          responseTimes <- sessionResponseTimes.get.map(_.getOrElse(sessionId, Nil))
          _ <- cleanupSessionTelemetry(sessionId)
          durationSec = startTime.map(s => (System.currentTimeMillis() - s) / 1000).getOrElse(0L)
          inferredTask = TaskInferencer.infer(profile, turns)
          avgResponseMs = if responseTimes.nonEmpty then responseTimes.sum / responseTimes.size else 0L
          maxResponseMs = responseTimes.maxOption.getOrElse(0L)
          props = JsonObject.fromIterable(
            List(
              "session_id" -> sessionId.asJson,
              "duration_sec" -> durationSec.asJson,
              "turn_count" -> turns.asJson,
              "tool_profile" -> TaskInferencer.toolProfileJson(profile).asJson,
              "inferred_task" -> inferredTask.asJson,
              "model_used" -> model.asJson,
              "models_used" -> allModels.toList.asJson,
              "avg_response_time_ms" -> avgResponseMs.asJson,
              "max_response_time_ms" -> maxResponseMs.asJson,
              "response_times" -> responseTimes.asJson
            )
          )
          _ <- reporter.record("session_end", props)
          _ <- reporter.flush
        yield ()

  /** Clean up per-session telemetry tracking state. */
  private def cleanupSessionTelemetry(sessionId: String): IO[Unit] =
    sessionToolProfile.update(_ - sessionId) *>
      sessionTurnCounts.update(_ - sessionId) *>
      sessionStartTimes.update(_ - sessionId) *>
      sessionModels.update(_ - sessionId) *>
      sessionAllModels.update(_ - sessionId) *>
      sessionResponseTimes.update(_ - sessionId)

  private def makeRecordingWsSend(
    sessionId: String,
    underlying: io.circe.Json => IO[Unit]
  ): io.circe.Json => IO[Unit] = json =>
    val hc = json.hcursor
    val eventType = hc.downField("type").as[String].getOrElse("")
    val record = eventType match
      case "thinkingDelta" =>
        val delta = hc.downField("delta").as[String].getOrElse("")
        if delta.nonEmpty then
          sessionTurnStarts
            .update(m => if m.contains(sessionId) then m else m.updated(sessionId, System.currentTimeMillis())) *>
            sessionThinkingBuffers.update(m => m.updatedWith(sessionId)(_.map(_ + delta).orElse(Some(delta))))
        else IO.unit

      case "thinking" =>
        sessionTurnStarts
          .update(m => if m.contains(sessionId) then m else m.updated(sessionId, System.currentTimeMillis()))

      case "textDelta" =>
        // Accumulate text for this session
        val delta = hc.downField("delta").as[String].getOrElse("")
        if delta.nonEmpty then
          sessionTurnStarts
            .update(m => if m.contains(sessionId) then m else m.updated(sessionId, System.currentTimeMillis())) *>
            sessionTextBuffers.update(m => m.updatedWith(sessionId)(_.map(_ + delta).orElse(Some(delta))))
        else IO.unit

      case "toolStart" =>
        val label = hc.downField("label").as[String].getOrElse("")
        // Track tool usage for telemetry
        val toolName = label.takeWhile(_ != '(').trim
        val trackTool =
          if toolName.nonEmpty then recordToolForTelemetry(sessionId, toolName).handleErrorWith(_ => IO.unit)
          else IO.unit
        trackTool *> sessionTurnStarts
          .update(m => if m.contains(sessionId) then m else m.updated(sessionId, System.currentTimeMillis()))
        // Flush accumulated text + thinking before tool execution, matching frontend finishAi() behavior.
        // Without this, text output before a tool call stays in the in-memory buffer and is lost
        // when the user switches sessions before the final "done" event.
          *> sessionTextBuffers
            .modify { m =>
              val text = m.getOrElse(sessionId, "")
              (m - sessionId, text)
            }
            .flatMap { text =>
              if text.nonEmpty then
                sessionThinkingBuffers
                  .modify { m =>
                    val thinking = m.getOrElse(sessionId, "")
                    (m - sessionId, thinking)
                  }
                  .flatMap { thinking =>
                    sharedResources.sessionStore.appendUiMessages(
                      sessionId,
                      List(UiMessage.Ai(text, None, None, Option.when(thinking.nonEmpty)(thinking)))
                    )
                  }
              else
                sessionThinkingBuffers.update(_ - sessionId)
                IO.unit
            }

      case "roundComplete" =>
        // Flush accumulated text + thinking for the current round (same as toolStart).
        // The backend is about to start a new LLM round via pipeLlmCall.
        sessionTextBuffers
          .modify { m =>
            val text = m.getOrElse(sessionId, "")
            (m - sessionId, text)
          }
          .flatMap { text =>
            if text.nonEmpty then
              sessionThinkingBuffers
                .modify { m =>
                  val thinking = m.getOrElse(sessionId, "")
                  (m - sessionId, thinking)
                }
                .flatMap { thinking =>
                  sharedResources.sessionStore.appendUiMessages(
                    sessionId,
                    List(UiMessage.Ai(text, None, None, Option.when(thinking.nonEmpty)(thinking)))
                  )
                }
            else
              sessionThinkingBuffers.update(_ - sessionId)
              IO.unit
          }

      case "done" =>
        val model = hc.downField("model").as[Option[String]].getOrElse(None)
        // Fire-and-forget: release cloud busy lock — AI response complete
        nebflow.core.tools.MeshTool.currentCloudSessionSync.foreach { css =>
          sharedResources.dispatcher.unsafeRunAndForget(
            css.releaseBusy(sessionId).handleErrorWith(_ => IO.unit)
          )
        }
        // Track model for telemetry
        val trackModel = model match
          case Some(m) =>
            sessionModels.update(_.updated(sessionId, m)) *>
              sessionAllModels.update { map =>
                val set = map.getOrElse(sessionId, Set.empty)
                map.updated(sessionId, set + m)
              }
          case None => IO.unit
        trackModel *> sessionTurnStarts
          .modify { m =>
            val start = m.getOrElse(sessionId, 0L)
            (m - sessionId, start)
          }
          .flatMap { startTime =>
            val durationMs = if startTime > 0 then Some(System.currentTimeMillis() - startTime) else None
            // Track response time for telemetry
            val trackResponseTime = durationMs match
              case Some(ms) =>
                sessionResponseTimes.update { map =>
                  val times = map.getOrElse(sessionId, Nil)
                  map.updated(sessionId, times :+ ms)
                }
              case None => IO.unit
            trackResponseTime *>
              sessionTextBuffers
                .modify { m =>
                  val text = m.getOrElse(sessionId, "")
                  (m - sessionId, text)
                }
                .flatMap { text =>
                  sessionThinkingBuffers
                    .modify { m =>
                      val thinking = m.getOrElse(sessionId, "")
                      (m - sessionId, thinking)
                    }
                    .flatMap { thinking =>
                      val thinkingOpt = Option.when(thinking.nonEmpty)(thinking)
                      if text.nonEmpty || thinkingOpt.isDefined then
                        sharedResources.sessionStore.appendUiMessages(
                          sessionId,
                          List(UiMessage.Ai(text, durationMs, model, thinkingOpt))
                        )
                      else IO.unit
                    }
                }
          }

      case "toolEnd" =>
        val label = hc.downField("label").as[String].getOrElse("")
        val summary = hc.downField("summary").as[String].getOrElse("")
        val content = hc.downField("content").as[String].getOrElse("")
        val isError = hc.downField("isError").as[Boolean].getOrElse(false)
        val input = hc.downField("input").as[io.circe.Json].getOrElse(io.circe.Json.Null).noSpaces
        sharedResources.sessionStore.appendUiMessages(
          sessionId,
          List(UiMessage.Tool(label, summary, content, isError, input))
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
          List(UiMessage.System(s"Compacting context ($mode)...", Some("chat.compacting")))
        )

      case "compactComplete" =>
        val before = hc.downField("before").as[Int].getOrElse(0)
        val after = hc.downField("after").as[Int].getOrElse(0)
        val reportPath = hc.downField("reportPath").as[String].toOption
        val detail = reportPath.map(p => s" (report: ${p.split('/').last})").getOrElse("")
        sharedResources.sessionStore.appendUiMessages(
          sessionId,
          List(
            UiMessage.System(
              s"Context compacted: $before → $after messages$detail",
              Some("chat.compacted"),
              Some(io.circe.Json.obj("before" -> before.asJson, "after" -> after.asJson, "detail" -> detail.asJson))
            )
          )
        )

      case "compactFailed" =>
        val attempt = hc.downField("attempt").as[Int].getOrElse(0)
        val maxAttempts = hc.downField("maxAttempts").as[Int].getOrElse(0)
        sharedResources.sessionStore.appendUiMessages(
          sessionId,
          List(
            UiMessage.System(
              s"Context compaction failed (attempt $attempt/$maxAttempts)",
              Some("chat.compactFailed"),
              Some(io.circe.Json.obj("attempt" -> attempt.asJson, "maxAttempts" -> maxAttempts.asJson))
            )
          )
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

  private def executeSkill(
    skillName: String,
    input: String,
    sessionId: String,
    wsSend: io.circe.Json => IO[Unit]
  ): IO[Unit] =
    // Scan skills to find the matching skill file, then load its content
    SkillService.listSkills().flatMap { skills =>
      skills.find(_.name == skillName) match
        case Some(skillInfo) =>
          SkillService.loadSkill(skillInfo.filePath).flatMap {
            case Some(content) =>
              routeToAgent(sessionId) { ref =>
                IO(
                  ref ! AgentCommand.SkillActivate(
                    skillName,
                    input,
                    sessionId,
                    content.content,
                    content.baseDir
                  )
                )
              }
            case None =>
              wsSend(
                io.circe.Json.obj(
                  "type" -> "skillError".asJson,
                  "sessionId" -> sessionId.asJson,
                  "message" -> s"Skill '$skillName' content not found".asJson
                )
              )
          }
        case None =>
          wsSend(
            io.circe.Json.obj(
              "type" -> "skillError".asJson,
              "sessionId" -> sessionId.asJson,
              "message" -> s"Skill '$skillName' not found".asJson
            )
          )
    }

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

/**
 * Death-watcher actor: monitors root AgentActor refs via Pekko's death-watch
 * mechanism. When a root agent stops permanently (exceeds supervision restart
 * limit), its ref is removed from the rootAgents map so future messages
 * trigger creation of a fresh actor rather than going to DeadLetters.
 */
private object DeathWatcher:
  sealed trait Command
  case class Watch(sessionId: String, ref: ActorRef[AgentCommand]) extends Command
  case class Unwatch(sessionId: String) extends Command

  def apply(
    rootAgents: Ref[IO, Map[String, ActorRef[AgentCommand]]],
    refToSession: Ref[IO, Map[ActorRef[AgentCommand], String]],
    dispatcher: cats.effect.std.Dispatcher[IO],
    wsHub: WsHub
  ): Behavior[Command] = Behaviors.setup { ctx =>
    def behavior(refMap: Map[ActorRef[AgentCommand], String]): Behavior[Command] =
      Behaviors
        .receiveMessage[Command] {
          case Watch(sessionId, ref) =>
            ctx.watch(ref)
            behavior(refMap + (ref -> sessionId))
          case Unwatch(sessionId) =>
            refMap.find(_._2 == sessionId) match
              case Some((ref, _)) => behavior(refMap - ref)
              case None => Behaviors.same
        }
        .receiveSignal { case (_, Terminated(deadRef)) =>
          // Terminated.ref is ActorRef[Nothing] — cast is safe because
          // ActorRef identity is by path, not type parameter
          val agentRef: ActorRef[AgentCommand] = deadRef.asInstanceOf[ActorRef[AgentCommand]]
          refMap.get(agentRef) match
            case Some(sid) =>
              dispatcher.unsafeRunAndForget(
                rootAgents.update(_ - sid) *>
                  refToSession.update(_ - agentRef) *>
                  // Broadcast sessionBusy(false) to all frontend clients so the
                  // session's blocked state is cleared immediately — without this,
                  // the frontend would wait up to 10 minutes for the safety timeout
                  // before the user can send a new message (which triggers creation
                  // of a fresh actor via ensureRootAgent).
                  wsHub.broadcast(
                    io.circe.Json.obj(
                      "type" -> "sessionBusy".asJson,
                      "sessionId" -> sid.asJson,
                      "busy" -> false.asJson
                    )
                  )
              )
              behavior(refMap - agentRef)
            case None => Behaviors.same
          end match
        }
    behavior(Map.empty)
  }
end DeathWatcher
