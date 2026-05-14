package nebflow.gateway

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, IOApp, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.agent.*
import nebflow.bridge.*
import nebflow.bridge.feishu.*
import nebflow.core.*
import nebflow.core.hooks.*
import nebflow.core.mcp.*
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.ToolRegistry
import nebflow.llm.*
import nebflow.service.*
import nebflow.shared.*
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router

import scala.concurrent.duration.*

object GatewayMain extends IOApp.Simple:
  private val logger = NebflowLogger.forName("nebflow.gateway")

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
   */
  private def waitForQuit: IO[Unit] =
    IO.blocking(Option(scala.io.StdIn.readLine())).flatMap {
      case None => IO.never // stdin closed — keep running until cancelled
      case Some(line) =>
        if QuitCommands.contains(line.trim.toLowerCase) then IO.unit
        else waitForQuit
    }

  /** Load MCP configs and start all servers on an existing McpManager. */
  private def startMcpServers(
    config: NebflowServiceConfig,
    manager: McpManager,
    agentLibrary: AgentLibrary,
    disabledServers: Set[String]
  ): IO[Unit] =
    val fromConfig = config.mcpServers.getOrElse(Map.empty)
    for
      _ <- agentLibrary.loadAll()
      _ <- logger.info("Initializing global MCP servers...")
      _ <- manager.startAll(fromConfig, disabledServers)
      _ <- logger.info("MCP servers initialized")
    yield ()

  def run: IO[Unit] =
    GatewayConfig.load.flatMap { cfg =>
      IO.blocking(Config.loadServiceConfig()).flatMap { config =>
        Auth.loadOrCreateToken.flatMap { token =>
          // Global session state shared across all connections
          val sessionStore = new SessionStore(os.home / ".nebflow" / "sessions", os.home / ".nebflow" / "tasks")
          val sessionModelOverrides: Ref[IO, Map[String, ModelCandidate]] = Ref.unsafe(Map.empty)
          sessionStore.load.flatMap { _ =>
            LlmInterface.createLlm(sessionModelOverrides).flatMap { case (handle, registry, releaseBackend) =>
              // Load persisted session model overrides
              sessionStore.listSessions.flatMap { sessions =>
                val persisted = sessions.flatMap { s =>
                  s.modelRef.flatMap(ref => registry.getCandidateForRef(ref).map(s.id -> _))
                }.toMap
                sessionModelOverrides.set(persisted)
              } *> McpManager.create().flatMap { mcpManager =>
                // --- Fast path: only essential init before server start ---
                val chatRoutes = new ChatRoutes(handle, token)
                val contextWindow =
                  val (providerId, modelId) = Config.parseModelRef(config.llm.model.default)
                  val provider = config.llm.providers
                    .getOrElse(providerId, throw new RuntimeException(s"Unknown provider: $providerId"))
                  provider.models.find(_.id == modelId).map(_.contextWindow).getOrElse(Defaults.ContextWindow)
                val baseUrl = s"http://localhost:${cfg.port}"
                val url = s"$baseUrl?token=$token"

                logger.info(s"nebflow v${nebflow.Version.string}") *>
                  logger.info(s"Context window: $contextWindow tokens (from ${config.llm.model.default})") *>
                  RuntimePreferencesService.create.flatMap { runtimePrefs =>
                    RateLimiter.create().flatMap { rateLimiter =>
                      FileChangeTracker.create(System.getProperty("user.dir")).flatMap { fileTracker =>
                        // Create Dispatcher for the multi-agent runtime, then start server
                        cats.effect.std.Dispatcher.parallel[IO].use { dispatcher =>
                          val agentLibrary = new AgentLibrary(AgentLibrary.defaultDir, Some(config))
                          cats.effect.std.Semaphore[IO](1).flatMap { askSemaphore =>
                            nebflow.core.tools.FileLockManager.create.flatMap { fileLockMgr =>
                              val hooksConfig = HooksConfigLoader.load(os.pwd)
                              val hookEngine = HookEngine(hooksConfig)
                              val sharedResources = SharedResources(
                                llm = handle,
                                dispatcher = dispatcher,
                                sessionStore = sessionStore,
                                projectRoot = os.pwd,
                                runtimePrefs = runtimePrefs,
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
                              val actorSystem = ActorSystem[Nothing](Behaviors.empty, "nebflow-guardian")
                              val sessionService = new SessionService(sessionStore)
                              val agentService = new AgentService(agentLibrary)
                              val configService = ConfigService

                              val wsHub = new WsHub()

                              // --- Bridge Manager (plugins: feishu, telegram, etc.) ---
                              val bridgeInjectRef: Ref[IO, Option[(String, String, Option[String]) => IO[Unit]]] =
                                Ref.unsafe(None)

                              val bridgeCtx = new BridgeContext:
                                def injectMessage(sessionId: String, content: String, senderId: Option[String])
                                  : IO[Unit] =
                                  bridgeInjectRef.get.flatMap(_.fold(IO.unit)(_(sessionId, content, senderId)))
                                def sessionMeta(sessionId: String): IO[Option[SessionMeta]] =
                                  sessionStore.getSessionMeta(sessionId)
                                def listSessions: IO[List[SessionMeta]] =
                                  sessionStore.listSessions
                                def updateBridgeConfig(sessionId: String, platform: String, config: Option[Json])
                                  : IO[Unit] =
                                  sessionStore.updateSessionBridge(sessionId, platform, config)

                              val bridgeSetup: IO[BridgeManager] =
                                BridgeManager.create(bridgeCtx).flatMap { mgr =>
                                  // Load Feishu plugin if config exists
                                  FeishuGlobalConfig.load.flatMap {
                                    case Some(cfg) =>
                                      mgr.register(new FeishuPlugin(cfg)).as(mgr)
                                    case None => IO.pure(mgr)
                                  }
                                }

                              // Holder for wsRoutes so we can wire bridge refs after safe construction
                              var wsRoutesHolder: Option[WebSocketRoutes] = None

                              bridgeSetup.flatMap { bridgeManager =>
                                val sharedResourcesWithBridge =
                                  sharedResources.copy(bridgeManager = Some(bridgeManager))
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
                                      runtimePrefs,
                                      rateLimiter,
                                      token,
                                      fileTracker,
                                      sessionStore,
                                      wsHub,
                                      actorSystem,
                                      contextWindow,
                                      sharedResourcesWithBridge,
                                      mcpManager
                                    )
                                    wsRoutesHolder = Some(wsRoutes)

                                    Router(
                                      "/api" -> chatRoutes.routes,
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
                                      // Register bridge as WsHub listener for agent events
                                      _ <- wsHub.register(json =>
                                        val sessionId = json.hcursor.downField("sessionId").as[String].getOrElse("")
                                        if sessionId.nonEmpty then bridgeManager.dispatchAgentEvent(sessionId, json)
                                        else IO.unit
                                      )
                                      _ <- bridgeManager.startAll.start // start in background
                                      _ <- openBrowser(url)
                                      // --- Background init: MCP servers ---
                                      bgInit = runtimePrefs.getDisabledMcpServers
                                        .flatMap { disabled =>
                                          startMcpServers(config, mcpManager, agentLibrary, disabled)
                                        }
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
                                      _ <- bgInit.background.use { _ =>
                                        logger.info(
                                          "Type 'quit', 'exit', or 'q' (or press Ctrl+C) to stop"
                                        ) *> waitForQuit
                                      }
                                    yield ())
                                  }
                                  .guarantee(
                                    logger.info("shutting down...") *>
                                      mcpManager.stopAll() *>
                                      releaseBackend *>
                                      IO.fromFuture(IO {
                                        actorSystem.terminate()
                                        actorSystem.whenTerminated
                                      }).void
                                  )
                              }
                            } // end fileLockMgr
                          } // end askSemaphore
                        } // end dispatcher.use
                      } // end fileTracker
                    } // end rateLimiter
                  } // end runtimePrefs
              } // end mcpManager
            }
          }
        } // end config flatMap
      }
    }

end GatewayMain
