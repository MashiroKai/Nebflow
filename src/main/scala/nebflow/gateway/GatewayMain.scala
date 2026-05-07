package nebflow.gateway

import cats.effect.{IO, IOApp, Ref}
import cats.syntax.all.*
import io.circe.syntax.*
import nebflow.agent.*
import nebflow.core.*
import nebflow.core.mcp.*
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.ToolRegistry
import nebflow.llm.*
import nebflow.service.*
import nebflow.shared.*
import nebflow.skill.*
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
    disabledServers: Set[String]
  ): IO[Unit] =
    val fromConfig = config.mcpServers.getOrElse(Map.empty)
    for
      manifests <- nebflow.plugin.PluginLoader.scan()
      fromPlugins = nebflow.plugin.PluginLoader.extractMcpConfigs(manifests)
      merged = fromConfig ++ fromPlugins
      _ <- logger.info("Initializing MCP servers...")
      _ <- manager.startAll(merged, disabledServers)
      _ <- logger.info(s"MCP servers initialized (${manifests.size} plugin(s) loaded)")
    yield ()

  def run: IO[Unit] =
    GatewayConfig.load.flatMap { cfg =>
      IO.blocking(Config.loadServiceConfig()).flatMap { config =>
        Auth.loadOrCreateToken.flatMap { token =>
          // Global session state shared across all connections
          val sessionStore = new SessionStore(os.home / ".nebflow" / "sessions")
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
                  val (providerId, modelId) = Config.parseModelRef(config.llm.model.primary)
                  val provider = config.llm.providers
                    .getOrElse(providerId, throw new RuntimeException(s"Unknown provider: $providerId"))
                  provider.models.find(_.id == modelId).map(_.contextWindow).getOrElse(Defaults.ContextWindow)
                val baseUrl = s"http://localhost:${cfg.port}"
                val url = s"$baseUrl?token=$token"

                logger.info(s"nebflow v${nebflow.Version.string}") *>
                  RuntimePreferencesService.create.flatMap { runtimePrefs =>
                    RateLimiter.create().flatMap { rateLimiter =>
                      FileChangeTracker.create(System.getProperty("user.dir")).flatMap { fileTracker =>
                        Ref.of[IO, ReminderState](ReminderState()).flatMap { reminderStateRef =>
                          // Create Dispatcher for the multi-agent runtime, then start server
                          cats.effect.std.Dispatcher.parallel[IO].use { dispatcher =>
                            val agentLibrary = new AgentLibrary(AgentLibrary.defaultDir, Some(config))
                            cats.effect.std.Semaphore[IO](1).flatMap { askSemaphore =>
                              nebflow.core.tools.FileLockManager.create.flatMap { fileLockMgr =>
                                val sharedResources = SharedResources(
                                  llm = handle,
                                  dispatcher = dispatcher,
                                  sessionStore = sessionStore,
                                  projectRoot = os.pwd,
                                  runtimePrefs = runtimePrefs,
                                  rateLimiter = rateLimiter,
                                  fileChangeTracker = fileTracker,
                                  reminderStateRef = reminderStateRef,
                                  contextWindow = contextWindow,
                                  skillDiscovery = None, // initialized asynchronously
                                  agentLibrary = agentLibrary,
                                  askSemaphore = askSemaphore,
                                  taskStore = FileTaskStore,
                                  historyArchiver = nebflow.core.compact.HistoryArchiver.fileSystem(os.pwd),
                                  fileLockManager = fileLockMgr,
                                  sessionModelOverrides = sessionModelOverrides,
                                  providerRegistry = registry
                                )
                                val actorSystem = ActorSystem[Nothing](Behaviors.empty, "nebflow-guardian")
                                val sessionService = new SessionService(sessionStore)
                                val agentService = new AgentService(agentLibrary)
                                val configService = ConfigService

                                val wsHub = new WsHub()

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
                                      reminderStateRef,
                                      sessionStore,
                                      wsHub,
                                      actorSystem,
                                      contextWindow,
                                      None, // skillDiscovery: initialized asynchronously
                                      sharedResources,
                                      Nil, // pluginManifests: initialized asynchronously
                                      mcpManager
                                    )
                                    Router(
                                      "/api" -> chatRoutes.routes,
                                      "/" -> wsRoutes.routes
                                    ).orNotFound
                                  }
                                  .build
                                  .use { _ =>
                                    for
                                      _ <- logger.info(s"gateway listening on ${cfg.host}:${cfg.port}")
                                      _ <- logger.info(s"access URL: $baseUrl (token in ~/.nebflow/.token)")
                                      _ <- openBrowser(url)
                                      // --- Background init: MCP servers + skill discovery ---
                                      bgInit = runtimePrefs.getDisabledMcpServers
                                        .flatMap { disabled =>
                                          startMcpServers(config, mcpManager, disabled)
                                        }
                                        .flatMap { _ =>
                                          initSkillDiscovery(config, handle)
                                        }
                                        .handleErrorWith { e =>
                                          logger.warn(s"Background init failed: ${e.getMessage}")
                                        }
                                      _ <- bgInit.background.use { _ =>
                                        logger.info(
                                          "Type 'quit', 'exit', or 'q' (or press Ctrl+C) to stop"
                                        ) *> waitForQuit
                                      }
                                    yield ()
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
                              } // end fileLockMgr
                            } // end askSemaphore
                          } // end dispatcher.use
                        } // end reminderStateRef
                      } // end fileTracker
                    } // end rateLimiter
                  } // end runtimePrefs
              } // end mcpManager
            }
          }
        } // end config flatMap
      }
    }

  private def initSkillDiscovery(config: NebflowServiceConfig, llm: LlmHandle[IO]): IO[Unit] =
    config.vectorInjection match
      case Some(viConfig) if viConfig.enable =>
        val skillsDir = Config.NebflowHome / "skills"
        val embedding = new EmbeddingService(viConfig.embedding)
        val qdrant = new QdrantClient(viConfig.qdrantUrl)
        val indexer = new SkillIndexer(qdrant, embedding, viConfig)

        // Estimate vector size from model name
        val vectorSize = viConfig.embedding.model match
          case m if m.contains("3-small") => 1536
          case m if m.contains("3-large") => 3072
          case m if m.contains("embedding") => 1536
          case _ => 1024

        logger.info("Initializing skill discovery...") *>
          (for
            _ <- qdrant.ensureCollection(viConfig.collection, vectorSize)
            _ <- indexer.indexIncremental(skillsDir, llm)
            _ <- logger.info("Skill discovery initialized")
          yield ()).handleErrorWith { e =>
            logger.warn(s"Skill system init failed, continuing without: ${e.getMessage}")
          }
      case _ =>
        IO.unit
end GatewayMain
