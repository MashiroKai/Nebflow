package nebflow.gateway

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, IOApp, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.agent.*
import nebflow.bridge.*
import nebflow.core.*
import nebflow.core.daemon.{DaemonService, DaemonStore}
import nebflow.core.hooks.*
import nebflow.core.mcp.*
import nebflow.core.scheduler.{ScheduledTaskService, ScheduledTaskStore}
import nebflow.core.skill.SkillService
import nebflow.core.task.FileTaskStore
import nebflow.core.telemetry.TelemetryReporter
import nebflow.core.tools.{RemoteExecutor, ToolLoader, ToolRegistry}
import nebflow.llm.*
import nebflow.neblink.*
import nebflow.service.{ConfigSnapshot, *}
import nebflow.shared.*
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
      _ <- startAgentMcpServers(manager)
      _ <- logger.info("MCP servers initialized")
      _ <- loadExternalTools()
    yield ()

  end startMcpServers

  /**
   * Start agent-scoped MCP servers (agent directory `tools/mcp/`, one `*.json`
   * per server) at startup. Each server is started independently; a failing
   * server is logged and skipped so one bad config can't block the rest of the
   * boot.
   */
  private def startAgentMcpServers(mcpManager: McpManager): IO[Unit] =
    for
      servers <- AgentMcpLoader.scanAll()
      _ <- servers.traverse_ { case (_, serverId, cfg) =>
        if cfg.isEnabled then
          mcpManager
            .startServer(serverId, cfg)
            .timeout(5.seconds)
            .handleErrorWith(e => logger.warn(s"Agent MCP server '$serverId' failed: ${e.getMessage}"))
        else logger.info(s"Agent MCP server '$serverId' is disabled, skipping")
      }
      _ <-
        if servers.nonEmpty then logger.info(s"Started ${servers.size} agent MCP server(s)")
        else IO.unit
    yield ()

  private def loadExternalTools(): IO[Unit] =
    for
      _ <- ToolLoader.reload()
      _ <- ToolLoader.startFileWatcher().start // background fiber — hot reload on file changes
    yield ()

  /**
   * Background heartbeat loop for NebLink server mode.
   * Every 30 seconds: send heartbeat, update peer list + trusted IPs.
   * Uses the discovery's current client (hot-swappable).
   * Returns the fiber so the caller can cancel/restart it.
   */
  private def startHeartbeatLoop(
    discovery: nebflow.neblink.NeblinkDiscovery
  ): IO[cats.effect.Fiber[IO, Throwable, Unit]] =
    def loop: IO[Unit] =
      for
        delay <- discovery.currentDelay
        _ <- IO.sleep(delay)
        _ <- discovery.heartbeatCycle
        _ <- IO.defer(loop)
      yield ()
    loop.start

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
            sessionStore.load
              .flatMap { _ =>
                // Single-session architecture: guarantee the primary agent Nebula
                // has exactly one session and that it is the active session shown in
                // the Main window, before any WS client connects. Adopts legacy
                // agentless sessions (preserving history) and activates as needed.
                sessionStore.ensureActiveAgentSession("Nebula").void
              }
              .flatMap { _ =>
                LlmInterface.createLlm(sessionModelOverrides, configRef = Some(configRef)).flatMap {
                  case (handle, registry, healthMonitor, releaseBackend) =>
                    // Clear per-session model overrides on restart so all sessions
                    // follow the global fallback order from config.
                    sessionStore.clearAllSessionModels() *> McpManager.create.flatMap { mcpManager =>
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
                         else
                           logger.info(s"Context window: $contextWindow tokens (from ${config.llm.model.default})")) *>
                        RateLimiter.create().flatMap { rateLimiter =>
                          FileChangeTracker.create(System.getProperty("user.dir")).flatMap { fileTracker =>
                            // Create Dispatcher for the multi-agent runtime, then start server
                            cats.effect.std.Dispatcher.parallel[IO].use { dispatcher =>
                              val agentLibrary = new AgentLibrary(AgentLibrary.defaultDir, Some(config))
                              nebflow.core.tools.FileLockManager.create.flatMap { fileLockMgr =>
                                val hooksConfig = HooksConfigLoader.load(os.pwd)
                                val hookEngine = HookEngine(hooksConfig)
                                val actorSystem = nebflow.actor.ActorSystem("local")
                                val voiceMutedRef: Ref[IO, Boolean] = Ref.unsafe(false)
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
                                  taskStore = FileTaskStore,
                                  historyArchiver = nebflow.core.compact.HistoryArchiver.fileSystem(os.pwd),
                                  fileLockManager = fileLockMgr,
                                  sessionModelOverrides = sessionModelOverrides,
                                  providerRegistry = registry,
                                  healthMonitor = healthMonitor,
                                  actorSystem = actorSystem,
                                  hookEngine = hookEngine,
                                  voiceMutedRef = voiceMutedRef
                                )
                                // Initialize telemetry (opt-out aware, fire-and-forget on failure)
                                val telemetryIO = TelemetryReporter.create().handleErrorWith { e =>
                                  logger.warn(s"Telemetry init failed: ${e.getMessage}").as(None)
                                }
                                // P2: spawn the global InteractionHub and publish its ref.
                                // Every agent's permission/AskUser requests and every frontend
                                // interaction answer route through this single actor.
                                val hubSetup: IO[Unit] =
                                  actorSystem.spawn(nebflow.agent.InteractionHub(), "interaction-hub").flatMap {
                                    hubRef =>
                                      sharedResources.interactionHubRef.set(Some(hubRef))
                                  }
                                hubSetup *> telemetryIO.flatMap { telemetry =>
                                  val sharedResourcesWithTelemetry = sharedResources.copy(telemetry = telemetry)
                                  val sessionService = new SessionService(sessionStore)
                                  val agentService = new AgentService(agentLibrary)
                                  val configService = ConfigService

                                  val wsHub = new WsHub()

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

                                  // Create neblink service (device discovery only, no file sync)
                                  val neblinkServiceF: IO[NeblinkService] =
                                    NeblinkService.create(cfg.port.value, dispatcher)

                                  bridgeSetup.flatMap { bridgeManager =>
                                    neblinkServiceF.flatMap { neblinkService =>
                                      // Presence WS service — maintains real-time online/offline via persistent WebSocket connections
                                      val presenceService =
                                        new nebflow.neblink.NeblinkPresenceService(neblinkService, cfg.port.value)(
                                          dispatcher
                                        )
                                      // Check if NebLink Server is configured; if so, create client for NebLink-based discovery
                                      val neblinkClient: Option[nebflow.neblink.NeblinkClient] =
                                        neblinkService.neblinkConfig.unsafeRunSync() match
                                          case nc if nc.neblinkServer.isDefined =>
                                            Some(
                                              new nebflow.neblink.NeblinkClient(nc.neblinkServer.get, cfg.port.value)
                                            )
                                          case _ => None
                                      // Wire relay client + presence service into NeblinkService so
                                      // TransferFileTool / DropboxService / status endpoint can use them.
                                      neblinkService.setRelayClient(neblinkClient)
                                      neblinkService.setPresenceService(presenceService)
                                      // Register remote executor for cross-device tool dispatch (P2P + relay fallback)
                                      RemoteExecutor.initialize(neblinkService, dispatcher, neblinkClient)
                                      // Start relay tunnel if NebLink Server is configured — maintains a
                                      // persistent WS to the server so cross-network relay-exec requests
                                      // can reach this device.
                                      neblinkClient.foreach { client =>
                                        val serverUrl =
                                          neblinkService.neblinkConfig.unsafeRunSync().neblinkServer.get.url
                                        val relayTunnel = new nebflow.neblink.NeblinkRelayTunnel(
                                          neblinkService,
                                          serverUrl,
                                          () => client.currentSessionToken
                                        )(dispatcher)
                                        neblinkService.setRelayTunnel(relayTunnel)
                                        dispatcher.unsafeRunAndForget(relayTunnel.connect())
                                      }
                                      // Discovery service — uses NebLink Server for discovery
                                      val tsDiscovery = new nebflow.neblink.NeblinkDiscovery(
                                        neblinkService,
                                        cfg.port.value,
                                        presenceService,
                                        neblinkClient
                                      )
                                      neblinkService.setDiscoveryHook(
                                        tsDiscovery.discoverCycle
                                          .handleErrorWith(e =>
                                            logger.debug(s"Discovery error: ${e.getMessage}").void
                                          )
                                      ) *> neblinkService.setDiagnostic(tsDiscovery.diagnosticScan) *>
                                        neblinkService.addPeerChangeCallback(
                                          wsHub.broadcast(io.circe.Json.obj("type" -> "peerListChanged".asJson))
                                        ) *>
                                        // Wire the WS data channel send function so other services
                                        // (e.g. DropboxService) can send P2P messages via presenceService.
                                        neblinkService.setSendDataFn((deviceId, channel, payload) =>
                                          presenceService.sendData(deviceId, channel, payload)
                                        ) *>
                                        // Trigger an immediate discovery cycle now that the hook is wired.
                                        neblinkService.sendSync(nebflow.neblink.SyncCommand.PeerDiscovered) *>
                                        // Start NebLink Server heartbeat loop (if configured) — maintains
                                        // session liveness and updates peer list every 30 seconds.
                                        // The fiber is stored so it can be cancelled/restarted on hot-swap.
                                        startHeartbeatLoop(tsDiscovery).void *>
                                        // Create Dropbox service (cross-device messaging & file transfer)
                                        nebflow.dropbox.DropboxService.create(neblinkService, wsHub).flatMap {
                                          dropboxService =>
                                            val sharedResourcesWithBridge =
                                              sharedResourcesWithTelemetry.copy(
                                                bridgeManager = Some(bridgeManager),
                                                neblinkService = Some(neblinkService),
                                                dropboxService = Some(dropboxService)
                                              )

                                            // --- Create Scheduled Task Service before wsRoutes ---
                                            // routeToAgent needs wsRoutesHolder, which is set below (same pattern as bridge)
                                            val scheduledTaskService = new ScheduledTaskService(
                                              sharedResourcesWithBridge.dispatcher,
                                              sharedResourcesWithBridge.scheduledTaskStore,
                                              (sid, event) =>
                                                wsRoutesHolder match
                                                  case Some(routes) => routes.handleBridgeAgentCommand(sid, event)
                                                  case None => IO.unit,
                                              wsHub.broadcast,
                                              sessionStore
                                            )
                                            dispatcher.unsafeRunAndForget(scheduledTaskService.start())
                                            val sharedResourcesFinal = sharedResourcesWithBridge.copy(
                                              scheduledTaskService = Some(scheduledTaskService)
                                            )

                                            // --- Daemon Service (external process lifecycle) ---
                                            val daemonService = new DaemonService(dispatcher)
                                            val sharedResourcesWithDaemon = sharedResourcesFinal.copy(
                                              daemonService = Some(daemonService)
                                            )
                                            // JVM shutdown hook: daemons must die with Nebflow even when the JVM
                                            // is killed by SIGINT/SIGTERM (Ctrl+C) — cats-effect `.guarantee`
                                            // finalizers are not guaranteed to run on abrupt termination. The
                                            // shutdown hook always runs on JVM exit. Idempotent: stopAll on an
                                            // already-stopped set is a no-op, so it is safe alongside the
                                            // graceful path in `.guarantee` below.
                                            Runtime.getRuntime.addShutdownHook(
                                              new Thread(() =>
                                                try daemonService.stopAll().unsafeRunSync()
                                                catch case _: Throwable => ()
                                              )
                                            )
                                            // Auto-start daemons configured with autoStart=true
                                            dispatcher.unsafeRunAndForget(
                                              IO.sleep(2.seconds) *> daemonService
                                                .autoStart(DaemonStore())
                                                .handleErrorWith(e =>
                                                  logger.warn(s"Daemon auto-start failed: ${e.getMessage}")
                                                )
                                            )

                                            TtsService.create().flatMap { ttsService =>
                                              SttService.create().flatMap { sttService =>
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
                                                      contextWindow,
                                                      sharedResourcesWithDaemon,
                                                      mcpManager,
                                                      sttService = sttService
                                                    )
                                                    wsRoutesHolder = Some(wsRoutes)

                                                    // REST API routes for CLI consumption
                                                    val restApiRoutes = new RestApiRoutes(
                                                      token,
                                                      configRef,
                                                      sharedResourcesWithDaemon,
                                                      sessionStore,
                                                      wsRoutes,
                                                      neblinkService = Some(neblinkService),
                                                      ttsService = ttsService,
                                                      neblinkDiscovery = Some(tsDiscovery),
                                                      gatewayPort = cfg.port.value,
                                                      wsHub = wsHub
                                                    )

                                                    Router(
                                                      "/api" -> (chatRoutes.routes <+> restApiRoutes.routes <+> restApiRoutes
                                                        .presenceWsRoutes(wsb)),
                                                      // Static tree only — gzip must never wrap the
                                                      // "/api" tree (SSE stream, presence WS).
                                                      "/" -> GzipMiddleware(wsRoutes.routes)
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
                                                      _ <- logger.info(
                                                        s"gateway listening on ${cfg.host}:${cfg.port}"
                                                      )
                                                      _ <- logger.info(
                                                        s"access URL: $baseUrl (token in ~/.nebflow/auth.json)"
                                                      )
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
                                                      // --- Background: LLM provider health monitoring ---
                                                      _ <- healthMonitor.start().void.start
                                                      // --- Background: TaskStuckWatcher (P0 阶段 3) ---
                                                      // 卡死识别与恢复：周期扫 agentRegistry，Processing 态且 turn 活动
                                                      // 超阈值（默认 10min，可配 stuckThresholdMs）→ 子 agent 发 Stop 走
                                                      // BackoffSupervisor 退避重启；根 agent 广播 taskStuck 事件由用户决定。
                                                      _ <- nebflow.core.processor.TaskStuckWatcher
                                                        .run(
                                                          sharedResourcesWithDaemon,
                                                          wsHub,
                                                          interval = nebflow.shared.Defaults.StuckWatcherIntervalSec.seconds,
                                                          thresholdMs =
                                                            config.stuckThresholdMs.getOrElse(nebflow.shared.Defaults.StuckThresholdMs)
                                                        )
                                                        .start
                                                      _ <-
                                                        if GatewayConfig.noBrowser ||
                                                          nebflow.core.HeadlessMode.enabled
                                                        then IO.unit
                                                        else openBrowser(url)
                                                      // --- Background init: skills dir, MCP servers ---
                                                      _ <- SkillService
                                                        .ensureDefaults()
                                                        .handleErrorWith(e =>
                                                          logger.warn(s"Skills init failed: ${e.getMessage}")
                                                        )
                                                        .start
                                                      // --- Background init: MCP servers ---
                                                      _ <- startMcpServers(config, mcpManager, agentLibrary)
                                                        .flatMap { _ =>
                                                          // Broadcast updated MCP server list to all connected clients
                                                          mcpManager.listServers
                                                            .map(_.map { case (id, enabled) =>
                                                              io.circe.Json
                                                                .obj("id" -> id.asJson, "enabled" -> enabled.asJson)
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
                                                      // --- NebLink: sync is event-driven (actor), no background loops needed ---
                                                      _ <- logger.info(
                                                        "Type 'quit', 'exit', or 'q' (or press Ctrl+C) to stop"
                                                      ) *> waitForQuit
                                                    yield ())
                                                  }
                                                  .guarantee(
                                                    logger.info("shutting down...") *>
                                                      daemonService.stopAll() *>
                                                      // P0 (2026-08-19): abort in-flight LLM requests BEFORE the
                                                      // sttp backend/dispatcher close — Ctrl+C previously let
                                                      // FS2 streams keep burning tokens during JVM drain.
                                                      nebflow.llm.LlmInterface.cancelAllInflight() *>
                                                      neblinkClient.traverse_(_.logout) *>
                                                      telemetry.fold(IO.unit)(_.shutdown) *>
                                                      mcpManager.stopAll() *>
                                                      releaseBackend
                                                  )
                                              } // end sttService
                                            } // end ttsService
                                        } // end neblinkService setup block
                                    } // end neblinkService
                                  } // end bridgeManager
                                } // end telemetry.flatMap
                              } // end fileLockMgr
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
