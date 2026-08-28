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
import nebflow.core.tools.{FriendMessageTool, RemoteExecutor, ToolLoader, ToolRegistry}
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
      providers = Map.empty
      // #339：占位 llm.model default 已删（字段退役）——未配置时 registry 走
      // "首 provider 首模型"兜底
    )
  )

  def run: IO[Unit] =
    // Team #11 ④: log which Windows toolchain pieces (Git Bash / rg)
    // resolved at boot — missing pieces must be loud up front, not discovered
    // later inside a failing tool call. No-op off Windows.
    nebflow.core.WindowsDepProbe.warnIfMissing *>
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
          // #311 (2026-08-19): enforce the preset invariant at boot — a usable
          // default preset must always exist once any model is configured.
          // Creates/seeds model-presets.json when absent (from llm.model, i.e.
          // the user's first provider model) and repairs a dangling/chain-less
          // default. Idempotent; healthy files are untouched.
          try nebflow.core.presets.PresetStore().ensureDefaultPreset()
          catch case e: Exception => logger.warn(s"Default preset seeding failed: ${e.getMessage}")
          // #339 D-b：llm.model 一次性迁移（先播种验证后剥离，原子写；失败
          // 幂等重试）。种子源此时仍优先读 llm.model（迁移优先），剥离后新
          // 安装的种子源走 providers 推导。
          try nebflow.core.presets.PresetStore.migrateGlobalModelChain()
          catch case e: Exception => logger.warn(s"llm.model migration failed: ${e.getMessage}")
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
                // Flow-node supervision P3: llm.streamTimeouts watchdog overrides
                // (boot-time; config changes take effect on restart).
                configRef.get.flatMap { bootCfg =>
                  val st = bootCfg.llm.streamTimeouts.getOrElse(nebflow.llm.StreamTimeoutsConfig())
                  IO(LlmInterface.applyStreamTimeouts(st.firstTokenSec, st.inactivitySec, st.noProgressSec))
                } *> LlmInterface.createLlm(sessionModelOverrides, configRef = Some(configRef)).flatMap {
                  case (handle, registry, healthMonitor, releaseBackend) =>
                    // Clear per-session model overrides on restart so all sessions
                    // follow the global fallback order from config.
                    sessionStore.clearAllSessionModels() *> McpManager.create.flatMap { mcpManager =>
                      // --- Fast path: only essential init before server start ---
                      val chatRoutes = new ChatRoutes(handle, token)
                      val isConfigured = config.llm.providers.nonEmpty
                      // #339 D-c：contextWindow 改读默认 preset 链首个能解析到
                      // provider 模型表的 ref（preferred 优先逐个试 fallbacks；
                      // 全失败回 Defaults.ContextWindow + warn）。横幅同时暴露
                      // preset 名 + 实际 ref，与设置页 preset 卡片逐字可对上。
                      val (contextWindow, presetLabel): (Int, Option[(String, String)]) =
                        if !isConfigured then (Defaults.ContextWindow, None)
                        else
                          try
                            val pFile = nebflow.core.presets.PresetStore().load()
                            val dp = pFile.presets
                              .getOrElse(pFile.defaultPreset, nebflow.core.presets.ModelPreset(pFile.defaultPreset))
                            val chain = dp.preferred.toList ++ dp.fallbacks
                            val resolved = chain.flatMap { ref =>
                              try
                                val (providerId, modelId) = Config.parseModelRef(ref)
                                config.llm.providers
                                  .get(providerId)
                                  .flatMap(_.models.find(_.id == modelId))
                                  .map(m => (m.contextWindow, ref))
                              catch case _: Exception => None
                            }
                            resolved.headOption match
                              case Some((cw, ref)) => (cw, Some((pFile.defaultPreset, ref)))
                              case None =>
                                logger.warn(
                                  s"Default preset '${pFile.defaultPreset}' resolves to no provider model; " +
                                    s"contextWindow falls back to ${Defaults.ContextWindow}"
                                )
                                (Defaults.ContextWindow, Some((pFile.defaultPreset, dp.preferred.getOrElse(""))))
                          catch case _: Exception => (Defaults.ContextWindow, None)
                      val baseUrl = s"http://localhost:${cfg.port}"
                      val url = s"$baseUrl?token=$token"
                      sys.props.update("nebflow.url", baseUrl)

                          // Initialize thinking config from nebflow.json (default enabled=true)
                          val initialThinking = config.thinkingConfig.getOrElse(nebflow.llm.ThinkingConfig())
                          val thinkingConfigRef: Ref[IO, nebflow.llm.ThinkingConfig] = Ref.unsafe(initialThinking)
                          // 冻结调度（freeze-schedule，#337 黑名单语义）：从 nebflow.json
                          // workSchedule 节（JSON 键名保留，语义=冻结时段）fail-safe 加载
                          // （非法配置视为关闭——恒不冻结，功能旁路）。
                          val initialFreezeSchedule =
                            nebflow.core.schedule.FreezeSchedule.load(config.workSchedule)
                          val freezeScheduleRef: Ref[IO, nebflow.core.schedule.FreezeScheduleConfig] =
                            Ref.unsafe(initialFreezeSchedule)
                          // 工具结果 TTL 清理（#341）：fail-safe 加载（非法配置
                          // 视为关闭——默认关，request-only 清理）。Ref 化（镜像
                          // freezeScheduleRef）——setToolResultTtl WS 热更。
                          val toolResultTtlCfg =
                            nebflow.core.compact.ToolResultTtlConfig.load(config.toolResultTtl)
                          val toolResultTtlRef: Ref[IO, nebflow.core.compact.ToolResultTtlConfig] =
                            Ref.unsafe(toolResultTtlCfg)
                      logger.info(s"nebflow v${nebflow.Version.string}") *>
                        (if !isConfigured then logger.info("No LLM provider configured — open the web UI to set up")
                         else presetLabel match
                           case Some((name, ref)) =>
                             logger.info(s"Context window: $contextWindow tokens (default preset \"$name\": $ref)")
                           case None =>
                             logger.info(s"Context window: $contextWindow tokens")) *>
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
                                  voiceMutedRef = voiceMutedRef,
                                  freezeScheduleRef = freezeScheduleRef,
                                  toolResultTtlRef = toolResultTtlRef,
                                  bashResilience = nebflow.shared.BashResilienceConfig(
                                    autoBackgroundMs = config.bashAutoBackgroundMs
                                      .getOrElse(nebflow.shared.Defaults.BashAutoBackgroundMs),
                                    hardTimeoutMs = config.bashBackgroundHardTimeoutMs
                                      .getOrElse(nebflow.shared.Defaults.BashBackgroundHardTimeoutMs),
                                    stuckWindowSec =
                                      config.bashStuckWindowSec.getOrElse(nebflow.shared.Defaults.BashStuckWindowSec),
                                    healthCheckIntervalSec = config.bashHealthCheckIntervalSec
                                      .getOrElse(nebflow.shared.Defaults.BgHealthCheckIntervalSec)
                                  )
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
                                      // Late-bound discovery holder for the silent re-login hook: the
                                      // startup client is created BEFORE NeblinkDiscovery (below) —
                                      // the hook resolves the hot-swap target at call time.
                                      val neblinkDiscoveryHolder =
                                        new java.util.concurrent.atomic.AtomicReference[Option[nebflow.neblink.NeblinkDiscovery]](None)
                                      // Check if NebLink Server is configured; if so, create client for NebLink-based discovery
                                      val neblinkClient: Option[nebflow.neblink.NeblinkClient] =
                                        neblinkService.neblinkConfig.unsafeRunSync() match
                                          case nc if nc.neblinkServer.isDefined =>
                                            Some(
                                              new nebflow.neblink.NeblinkClient(
                                                nc.neblinkServer.get,
                                                cfg.port.value,
                                                onDeviceTokenRejected = Some(
                                                  nebflow.neblink.LogtoSilentRelogin.make(
                                                    neblinkService,
                                                    IO(neblinkDiscoveryHolder.get),
                                                    cfg.port.value,
                                                    IO.pure(neblinkService.neblinkConfig.unsafeRunSync().neblinkServer.map(_.url).getOrElse(Branding.serverUrl))
                                                  )
                                                )
                                              )
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
                                      // A2A 一期：FriendService 基于 NeblinkClient（好友/消息 REST +
                                      // 事件去重/限速/未读 cursor）。WS 事件回调广播 friendEvent 给前端
                                      // （messages.js 监听）；agentMessaging 配置来自 neblinkConfig。
                                      val clientWithFriends: Option[(nebflow.neblink.NeblinkClient, nebflow.neblink.FriendService)] =
                                        neblinkClient.map { client =>
                                          val amConfig = neblinkService.neblinkConfig.unsafeRunSync().agentMessaging
                                          val friendService = new nebflow.neblink.FriendService(
                                            client,
                                            amConfig,
                                            onFriendEvent = Some { ev =>
                                              // Frontend contract (messages.js onMessage('friend_event')):
                                              // frame type is "friend_event"; event type in msg.event;
                                              // payload fields (conversationId/messageId/body/...) flattened
                                              // onto the frame. Strip the payload's inner "type" so it
                                              // cannot clobber the frame envelope.
                                              val payloadFields =
                                                ev.payload.asObject.getOrElse(io.circe.JsonObject.empty).remove("type")
                                              val frame = io.circe.Json
                                                .obj("type" -> "friend_event".asJson, "event" -> ev.eventType.asJson)
                                                .deepMerge(io.circe.Json.fromJsonObject(payloadFields))
                                              wsHub.broadcast(frame)
                                            }
                                          )
                                          // A2A 一期（#290 域 A）：SendFriendMessage 工具接线——
                                          // 授权仅 Nebula agent.json 声明（作者特批 2026-08-28），
                                          // 服务依赖走 RemoteExecutor.initialize 同款单例模式。
                                          FriendMessageTool.initialize(friendService)
                                          (client, friendService)
                                        }
                                      clientWithFriends.foreach { (client, friendService) =>
                                        val serverUrl =
                                          neblinkService.neblinkConfig.unsafeRunSync().neblinkServer.get.url
                                        val relayTunnel = new nebflow.neblink.NeblinkRelayTunnel(
                                          neblinkService,
                                          serverUrl,
                                          () => client.currentSessionToken,
                                          friendService = Some(friendService)
                                        )(dispatcher)
                                        neblinkService.setRelayTunnel(relayTunnel)
                                        dispatcher.unsafeRunAndForget(relayTunnel.connect())
                                        // Baseline refresh (spec §5.2 startup entry): conversations +
                                        // per-conversation keyset pull + friends. Silently no-ops when
                                        // not logged in yet (FriendService catches upstream errors);
                                        // later friend_event pushes keep state fresh.
                                        dispatcher.unsafeRunAndForget(friendService.refreshAll())
                                      }
                                      // Discovery service — uses NebLink Server for discovery
                                      val tsDiscovery = new nebflow.neblink.NeblinkDiscovery(
                                        neblinkService,
                                        cfg.port.value,
                                        presenceService,
                                        neblinkClient
                                      )
                                      neblinkDiscoveryHolder.set(Some(tsDiscovery))
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
                                                friendService = clientWithFriends.map(_._2),
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
                                            //
                                            // releaseBackend runs FIRST: on Ctrl+C the graceful path never
                                            // executes, so without this the in-flight LLM HTTP requests (FS2/
                                            // sttp via the JDK HttpClient) would keep running and the provider
                                            // would keep generating (and billing) their responses. shutdownNow
                                            // aborts them at the TCP level; close + dispatcher release tear down
                                            // the pool. Release is idempotent (once-guarded in createLlm), so
                                            // hook + graceful `.guarantee` cannot double-release.
                                            Runtime.getRuntime.addShutdownHook(
                                              new Thread(() =>
                                                try releaseBackend.unsafeRunSync()
                                                catch case _: Throwable => ()
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
                                                      // Logto AC+PKCE loopback callback (RFC 8252) —
                                                      // root-level, outside /api: the provider's browser
                                                      // redirect carries no gateway token.
                                                      "/auth" -> restApiRoutes.authCallbackRoutes,
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
                                                      // --- Background: FreezeScheduler (freeze-schedule) ---
                                                      // 冻结恢复扫描：周期向 Frozen 态 agent 发 CheckFreezeGate，
                                                      // 出冻结段则恢复挂起的 dispatch（仿 TaskStuckWatcher 模式：
                                                      // 错误自愈 + `>>` 递归栈安全）。Frozen 态 TaskStuckWatcher
                                                      // 天然豁免（只扫 Processing）。
                                                      _ <- nebflow.core.processor.FreezeScheduler
                                                        .run(
                                                          sharedResourcesWithDaemon,
                                                          interval = nebflow.shared.Defaults.FreezeCheckIntervalSec.seconds
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
