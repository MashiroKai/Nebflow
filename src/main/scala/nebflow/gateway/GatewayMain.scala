package nebflow.gateway

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, IOApp, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.agent.*
import nebflow.bridge.*
import nebflow.core.*
import nebflow.core.hooks.*
import nebflow.core.mcp.*
import nebflow.core.scheduler.{ScheduledTaskActor, ScheduledTaskStore}
import nebflow.core.skill.SkillService
import nebflow.core.task.FileTaskStore
import nebflow.core.telemetry.TelemetryReporter
import nebflow.core.tools.{MeshTool, ToolRegistry}
import nebflow.llm.*
import nebflow.mesh.*
import nebflow.service.{ConfigSnapshot, *}
import nebflow.shared.*
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router

import scala.concurrent.duration.*

object GatewayMain extends IOApp.Simple:
  private val logger = NebflowLogger.forName("nebflow.gateway")

  private def pidFilePath = java.nio.file.Paths.get(PathUtil.dataRoot.toString, "nebflow.pid")

  /**
   * Kill stale Nebflow processes before starting.
   * Port-based detection (like OpenClaw's cleanStaleGatewayProcessesSync):
   * uses lsof to find all processes listening on the gateway port and kills them.
   * Falls back to PID file on Windows.
   */
  private def ensureSingleInstance(port: Int): IO[Unit] =
    IO.blocking {
      val os = sys.props.getOrElse("os.name", "").toLowerCase
      if os.contains("mac") || os.contains("linux") then
        // Port-based detection: find all PIDs listening on our port
        try
          val pb = new ProcessBuilder("lsof", "-i", s":$port", "-t", "-sTCP:LISTEN")
          val output = pb.redirectErrorStream(true).start()
          val result = new String(output.getInputStream.readAllBytes(), "UTF-8").trim
          output.waitFor()
          if result.nonEmpty then
            val pids = result.split("\\s+").filter(_.matches("\\d+"))
            if pids.nonEmpty then
              val currentPid = ProcessHandle.current.pid
              val stalePids = pids.filter(_.toLong != currentPid)
              if stalePids.nonEmpty then
                logger
                  .warn(s"[startup] killing stale processes on port $port: ${stalePids.mkString(", ")}")
                  .unsafeRunSync()
                for pid <- stalePids do
                  try ProcessHandle.of(pid.toLong).ifPresent(_.destroyForcibly())
                  catch case _: Exception => ()
                Thread.sleep(1000)
          end if
        catch case _: Exception => ()
      end if
      // Write current PID file
      val pf = pidFilePath
      java.nio.file.Files.createDirectories(pf.getParent)
      java.nio.file.Files.write(
        pf,
        ProcessHandle.current.pid.toString.getBytes("UTF-8"),
        java.nio.file.StandardOpenOption.CREATE,
        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
      )
    }

  private def openBrowser(url: String): IO[Unit] = IO.blocking {
    val os = sys.props.getOrElse("os.name", "").toLowerCase
    val cmd =
      if os.contains("mac") then Seq("open", url)
      else if os.contains("win") then Seq("rundll32", "url.dll,FileProtocolHandler", url)
      else Seq("xdg-open", url)
    try Runtime.getRuntime.exec(cmd.toArray)
    catch case _: Exception => ()
  }

  private val QuitCommands: Set[String] = Set("quit", "exit", "q")

  /**
   * Block until user types a quit command on stdin.
   * If stdin is closed (EOF — common under sbt run / non-interactive shells), wait
   * forever instead of exiting, so the server is only killed by SIGINT/SIGTERM.
   *
   * On Windows, StdIn.readLine() may not be interrupted by Thread.interrupt() from
   * Ctrl+C. Use a polling approach with a short sleep to allow cancellation.
   */
  private def waitForQuit: IO[Unit] =
    val isWindows = sys.props.getOrElse("os.name", "").toLowerCase.contains("win")
    if isWindows then pollStdinLoop
    else
      IO.interruptible(Option(scala.io.StdIn.readLine())).flatMap {
        case None => IO.never // stdin closed — keep running until cancelled
        case Some(line) =>
          if QuitCommands.contains(line.trim.toLowerCase) then IO.unit
          else waitForQuit
      }

  /**
   * Windows-compatible stdin polling: non-blocking check + short sleep.
   * On Windows CI (background process with no stdin), System.in.available()
   * throws IOException — we catch it and fall back to a sleep loop so the
   * server keeps running until cancelled externally.
   */
  private def pollStdinLoop: IO[Unit] =
    IO.blocking {
      if System.in.available() > 0 then Option(scala.io.StdIn.readLine())
      else null
    }.handleErrorWith { _ =>
      // stdin unavailable (e.g. Windows background process) — sleep forever
      IO.never
    }.flatMap {
      case Some(line) =>
        if QuitCommands.contains(line.trim.toLowerCase) then IO.unit
        else pollStdinLoop
      case _ =>
        IO.sleep(300.millis) *> pollStdinLoop
    }

  /** Load MCP configs and start all servers on an existing McpManager. */
  private def startMcpServers(
    config: NebflowServiceConfig,
    manager: McpManager,
    agentLibrary: AgentLibrary
  ): IO[Unit] =
    val fromConfig = config.mcpServers.getOrElse(Map.empty)
    for
      _ <- agentLibrary.seedDefaults()
      _ <- agentLibrary.loadAll()
      _ <- logger.info("Initializing global MCP servers...")
      _ <- manager.startAll(fromConfig)
      _ <- logger.info("MCP servers initialized")
    yield ()

  private lazy val defaultConfig: NebflowServiceConfig = NebflowServiceConfig(
    llm = ServiceLlmConfig(
      providers = Map.empty,
      model = ModelChainConfig(default = "anthropic/claude-sonnet-4-6")
    )
  )

  def run: IO[Unit] =
    // Read port from config first, then kill stale processes on that port
    GatewayConfig.load.flatMap { cfg =>
      ensureSingleInstance(cfg.port.value) *> GatewayConfig.load.flatMap { cfg =>
        // Expose resolved gateway port and PID to agent via system properties
        System.setProperty("nebflow.gateway.port", cfg.port.value.toString)
        System.setProperty("nebflow.gateway.pid", ProcessHandle.current.pid.toString)
        // Safe config load — never crash on bad config; auto-restore from snapshot on corruption
        val configRef: Ref[IO, NebflowServiceConfig] = Ref.unsafe {
          try
            val cfg = Config.loadServiceConfig()
            // Save snapshot on successful load (fire-and-forget)
            ConfigSnapshot.save().unsafeRunSync()
            cfg
          catch
            case e: Exception =>
              logger.warn(s"Config load failed: ${e.getMessage}")
              // Try restoring from latest snapshot
              ConfigSnapshot.restoreLatest().unsafeRunSync() match
                case true =>
                  logger.info("Restored config from latest snapshot")
                  try Config.loadServiceConfig()
                  catch
                    case _: Exception =>
                      logger.warn("Restored snapshot also invalid — starting with defaults")
                      defaultConfig
                case false =>
                  logger.warn("No snapshot available — starting with defaults")
                  defaultConfig
        }
        configRef.get.flatMap { config =>
          Auth.loadOrCreateToken.flatMap { token =>
            // Global session state shared across all connections
            val sessionStore = new SessionStore(PathUtil.dataRoot / "sessions", PathUtil.dataRoot / "tasks")
            val sessionModelOverrides: Ref[IO, Map[String, ModelCandidate]] = Ref.unsafe(Map.empty)
            sessionStore.load.flatMap { _ =>
              LlmInterface.createLlm(sessionModelOverrides, configRef = Some(configRef)).flatMap {
                case (handle, registry, releaseBackend) =>
                  // Load persisted session model overrides
                  sessionStore.listSessions.flatMap { sessions =>
                    val persisted = sessions.flatMap { s =>
                      s.modelRef match
                        case Some(ref) => registry.getCandidateForRef(ref).map(_.map(s.id -> _)).unsafeRunSync()
                        case None => None
                    }.toMap
                    sessionModelOverrides.set(persisted)
                  } *> McpManager.create.flatMap { mcpManager =>
                    // --- Fast path: only essential init before server start ---
                    val chatRoutes = new ChatRoutes(handle, token)
                    val isConfigured = config.llm.providers.nonEmpty
                    val contextWindow =
                      if !isConfigured then Defaults.ContextWindow
                      else
                        try
                          val (providerId, modelId) = Config.parseModelRef(config.llm.model.default)
                          config.llm.providers
                            .get(providerId)
                            .flatMap(_.models.find(_.id == modelId))
                            .map(_.contextWindow)
                            .getOrElse(Defaults.ContextWindow)
                        catch case _: Exception => Defaults.ContextWindow
                    val baseUrl = s"http://localhost:${cfg.port}"
                    val url = s"$baseUrl?token=$token"
                    sys.props.update("nebflow.url", baseUrl)

                    // Initialize thinking config from nebflow.json (default enabled=true)
                    val initialThinking = config.thinkingConfig.getOrElse(nebflow.llm.ThinkingConfig())
                    val thinkingConfigRef: Ref[IO, nebflow.llm.ThinkingConfig] = Ref.unsafe(initialThinking)
                    logger.info(s"nebflow v${nebflow.Version.string}") *>
                      (if !isConfigured then logger.info("No LLM provider configured — open the web UI to set up")
                       else logger.info(s"Context window: $contextWindow tokens (from ${config.llm.model.default})")) *>
                      RateLimiter.create().flatMap { rateLimiter =>
                        FileChangeTracker.create(System.getProperty("user.dir")).flatMap { fileTracker =>
                          // Create Dispatcher for the multi-agent runtime, then start server
                          cats.effect.std.Dispatcher.parallel[IO].use { dispatcher =>
                            val agentLibrary = new AgentLibrary(AgentLibrary.defaultDir, Some(config))
                            cats.effect.std.Semaphore[IO](1).flatMap { askSemaphore =>
                              nebflow.core.tools.FileLockManager.create.flatMap { fileLockMgr =>
                                val hooksConfig = HooksConfigLoader.load(os.pwd)
                                val hookEngine = HookEngine(hooksConfig)
                                // Actor system must be created before SharedResources
                                // because MemoryAgentManager needs it
                                val actorSystem = ActorSystem[Nothing](Behaviors.empty, "nebflow-guardian")
                                val sharedResources = SharedResources(
                                  llm = handle,
                                  dispatcher = dispatcher,
                                  sessionStore = sessionStore,
                                  projectRoot = os.pwd,
                                  thinkingConfigRef = thinkingConfigRef,
                                  rateLimiter = rateLimiter,
                                  fileChangeTracker = fileTracker,
                                  contextWindow = contextWindow,
                                  agentLibrary = agentLibrary,
                                  askSemaphore = askSemaphore,
                                  taskStore = FileTaskStore,
                                  historyArchiver = nebflow.core.compact.HistoryArchiver.fileSystem(os.pwd),
                                  fileLockManager = fileLockMgr,
                                  sessionModelOverrides = sessionModelOverrides,
                                  providerRegistry = registry,
                                  hookEngine = hookEngine
                                )
                                // Initialize telemetry (opt-out aware, fire-and-forget on failure)
                                val telemetryIO = TelemetryReporter.create().handleErrorWith { e =>
                                  logger.warn(s"Telemetry init failed: ${e.getMessage}").as(None)
                                }
                                telemetryIO.flatMap { telemetry =>
                                  val sharedResourcesWithTelemetry = sharedResources.copy(telemetry = telemetry)
                                  val sessionService = new SessionService(sessionStore)
                                  val agentService = new AgentService(agentLibrary)
                                  val configService = ConfigService

                                  val wsHub = new WsHub()

                                  // Dream scheduler: event-driven memory consolidation + pattern extraction
                                  val memoryAgentManager = new MemoryAgentManager(
                                    actorSystem,
                                    dispatcher,
                                    sessionStore
                                  )
                                  // Wire dreamSchedulerRef into SharedResources (created after SharedResources init)
                                  val sharedResourcesWithDream = sharedResourcesWithTelemetry.copy(
                                    dreamSchedulerRef = Some(memoryAgentManager.dreamSchedulerRef)
                                  )
                                  memoryAgentManager.setSharedResources(sharedResourcesWithDream)
                                  memoryAgentManager.setWsHub(wsHub)

                                  // --- Bridge Manager (plugins: telegram, etc.) ---
                                  val bridgeInjectRef: Ref[IO, Option[(String, String, Option[String]) => IO[Unit]]] =
                                    Ref.unsafe(None)

                                  // Holder for wsRoutes so we can wire bridge refs after safe construction
                                  var wsRoutesHolder: Option[WebSocketRoutes] = None

                                  val bridgeCtx = new BridgeContext:
                                    def injectMessage(sessionId: String, content: String, senderId: Option[String])
                                      : IO[Unit] =
                                      bridgeInjectRef.get.flatMap(_.fold(IO.unit)(_(sessionId, content, senderId)))
                                    def interruptAgent(sessionId: String): IO[Unit] =
                                      wsRoutesHolder match
                                        case Some(routes) =>
                                          routes.handleBridgeAgentCommand(sessionId, AgentCommand.Interrupt())
                                        case None => IO.unit
                                    def sessionMeta(sessionId: String): IO[Option[SessionMeta]] =
                                      sessionStore.getSessionMeta(sessionId)
                                    def listSessions: IO[List[SessionMeta]] =
                                      sessionStore.listSessions
                                    def updateBridgeConfig(sessionId: String, platform: String, config: Option[Json])
                                      : IO[Unit] =
                                      sessionStore.updateSessionBridge(sessionId, platform, config)

                                  val bridgeSetup: IO[BridgeManager] =
                                    BridgeManager.create(bridgeCtx)

                                  // Create mesh service (event-driven sync actor, no UDP)
                                  val meshServiceF: IO[MeshService] =
                                    MeshSyncStore
                                      .load()
                                      .flatMap(store =>
                                        MeshService.create(store, cfg.port.value, actorSystem, dispatcher)
                                      )

                                  bridgeSetup.flatMap { bridgeManager =>
                                    meshServiceF.flatMap { meshService =>
                                      // Register mesh tool for agent cross-device operations
                                      MeshTool.register(meshService)

                                      // Create sync services
                                      val cloudSessionSync = nebflow.mesh.CloudSessionSync(meshService, sessionStore)
                                      val incrementalSync = nebflow.mesh.IncrementalSyncEngine(meshService, sessionStore)
                                      // Hook: incremental blob push on message change
                                      sessionStore.setSessionChangedHook(sid => incrementalSync.pushSessionIncremental(sid))
                                      // Full sync cycle: state + files + all sessions
                                      meshService.setPostSyncHook(incrementalSync.fullSyncCycle)
                                      // MeshTool: cloud session sync for busy lock + relay
                                      MeshTool.setCloudSessionSync(cloudSessionSync)
                                      MeshTool.setIncrementalSyncEngine(incrementalSync)
                                      // Background pollers: fast state sync (5s) + relay (10s)
                                      cloudSessionSync.startBackgroundPollers(
                                        sharedResourcesWithDream.dispatcher,
                                        incrementalSync.fastSyncCycle
                                      )

                                      val sharedResourcesWithBridge =
                                        sharedResourcesWithDream.copy(
                                          bridgeManager = Some(bridgeManager),
                                          meshService = Some(meshService)
                                        )

                                      // --- Create Scheduled Task Actor before wsRoutes ---
                                      // routeToAgent needs wsRoutesHolder, which is set below (same pattern as bridge)
                                      var scheduledTaskActorRefHolder: Option[org.apache.pekko.actor.typed.ActorRef[
                                        nebflow.core.scheduler.ScheduledTaskCommand
                                      ]] = None
                                      val scheduledTaskActor = new ScheduledTaskActor(
                                        actorSystem,
                                        sharedResourcesWithBridge.dispatcher,
                                        sharedResourcesWithBridge.scheduledTaskStore,
                                        (sid, event) =>
                                          wsRoutesHolder match
                                            case Some(routes) => routes.handleBridgeAgentCommand(sid, event)
                                            case None => IO.unit,
                                        wsHub.broadcast
                                      )
                                      scheduledTaskActorRefHolder = Some(scheduledTaskActor.ref)
                                      val sharedResourcesFinal = sharedResourcesWithBridge.copy(
                                        scheduledTaskActorRef = scheduledTaskActorRefHolder
                                      )

                                      EmberServerBuilder
                                        .default[IO]
                                        .withHost(cfg.host)
                                        .withPort(cfg.port)
                                        .withIdleTimeout(1.hour)
                                        .withHttpWebSocketApp { wsb =>
                                          val wsRoutes = new WebSocketRoutes(
                                            wsb,
                                            sessionService,
                                            agentService,
                                            configService,
                                            configRef,
                                            rateLimiter,
                                            token,
                                            fileTracker,
                                            sessionStore,
                                            wsHub,
                                            actorSystem,
                                            contextWindow,
                                            sharedResourcesFinal,
                                            mcpManager
                                          )
                                          wsRoutesHolder = Some(wsRoutes)

                                          // REST API routes for CLI consumption
                                          val restApiRoutes = new RestApiRoutes(
                                            token,
                                            configRef,
                                            sharedResourcesFinal,
                                            sessionStore,
                                            wsRoutes,
                                            meshService = Some(meshService)
                                          )

                                          Router(
                                            "/api" -> (chatRoutes.routes <+> restApiRoutes.routes),
                                            "/" -> wsRoutes.routes
                                          ).orNotFound
                                        }
                                        .build
                                        .use { _ =>
                                          // Wire bridge inject ref
                                          val wireBridge = wsRoutesHolder match
                                            case Some(wsRoutes) =>
                                              bridgeInjectRef.set(Some(wsRoutes.handleBridgeMessage))
                                            case None => IO.unit
                                          wireBridge *> (for
                                            _ <- logger.info(s"gateway listening on ${cfg.host}:${cfg.port}")
                                            _ <- logger.info(s"access URL: $baseUrl (token in ~/.nebflow/.token)")
                                            // Telemetry: app_start
                                            _ <- telemetry.fold(IO.unit)(
                                              _.record("app_start", io.circe.JsonObject.empty)
                                            )
                                            // Register bridge as WsHub listener for agent events
                                            _ <- wsHub.register(json =>
                                              val sessionId =
                                                json.hcursor.downField("sessionId").as[String].getOrElse("")
                                              if sessionId.nonEmpty then
                                                bridgeManager.dispatchAgentEvent(sessionId, json)
                                              else IO.unit
                                            )
                                            _ <- bridgeManager.startAll.start // start in background
                                            _ <- openBrowser(url)
                                            // --- Background init: skills dir, MCP servers ---
                                            _ <- SkillService
                                              .ensureDefaults()
                                              .handleErrorWith(e => logger.warn(s"Skills init failed: ${e.getMessage}"))
                                              .start
                                            // --- Background init: MCP servers ---
                                            _ <- startMcpServers(config, mcpManager, agentLibrary)
                                              .flatMap { _ =>
                                                // Broadcast updated MCP server list to all connected clients
                                                mcpManager.listServers
                                                  .map(_.map { case (id, enabled) =>
                                                    io.circe.Json.obj("id" -> id.asJson, "enabled" -> enabled.asJson)
                                                  })
                                                  .flatMap { mcpJson =>
                                                    wsHub.broadcast(
                                                      io.circe.Json.obj(
                                                        "type" -> "mcpServersUpdate".asJson,
                                                        "mcpServers" -> mcpJson.asJson
                                                      )
                                                    )
                                                  }
                                              }
                                              .handleErrorWith { e =>
                                                logger.warn(s"Background init failed: ${e.getMessage}")
                                              }
                                              .start
                                            // --- Mesh: sync is event-driven (actor), no background loops needed ---
                                            _ <- logger.info(
                                              "Type 'quit', 'exit', or 'q' (or press Ctrl+C) to stop"
                                            ) *> waitForQuit
                                          yield ())
                                        }
                                        .guarantee(
                                          logger.info("shutting down...") *>
                                            telemetry.fold(IO.unit)(_.shutdown) *>
                                            mcpManager.stopAll() *>
                                            releaseBackend *>
                                            IO.fromFuture(IO {
                                              actorSystem.terminate()
                                              actorSystem.whenTerminated
                                            }).void
                                        )
                                    } // end meshService
                                  } // end bridgeManager
                                } // end telemetry.flatMap
                              } // end fileLockMgr
                            } // end askSemaphore
                          } // end dispatcher.use
                        } // end fileTracker
                      } // end rateLimiter
                  } // end mcpManager
              }
            }
          } // end config flatMap
        }
      }
    }

end GatewayMain
