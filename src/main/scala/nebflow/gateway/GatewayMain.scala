package nebflow.gateway

import cats.effect.{IO, IOApp, Ref}
import cats.syntax.all.*
import io.circe.syntax.*
import nebflow.agent.*
import nebflow.core.*
import nebflow.core.mcp.*
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.ToolRegistry
import nebflow.llm.{Config, LlmInterface, NebflowServiceConfig}
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

  private def initMcpServers(config: NebflowServiceConfig): IO[(McpManager, List[nebflow.plugin.PluginManifest])] =
    McpManager.create.flatMap { manager =>
      val fromConfig = IO.pure(config.mcpServers.getOrElse(Map.empty))
      val fromJson = McpManager.loadMcpServersJson
      nebflow.plugin.PluginLoader.scan().flatMap { manifests =>
        val fromPlugins = nebflow.plugin.PluginLoader.extractMcpConfigs(manifests)
        // Merge configs from nebflow.json, mcp-servers.json, and plugin.yaml
        // Priority: plugins override mcp-servers.json overrides nebflow.json
        val merged = (fromConfig, fromJson, IO.pure(fromPlugins)).mapN { (a, b, c) => a ++ b ++ c }
        merged.flatMap(manager.startAll).as((manager, manifests))
      }
    }

  def run: IO[Unit] =
    GatewayConfig.load.flatMap { cfg =>
      IO.blocking(Config.loadServiceConfig()).flatMap { config =>
        Auth.loadOrCreateToken.flatMap { token =>
          // Global session state shared across all connections
          val sessionStore = new SessionStore(os.home / ".nebflow" / "sessions")
          sessionStore.load.flatMap { _ =>
            LlmInterface.createLlm().flatMap { case (handle, releaseBackend) =>
              initMcpServers(config).flatMap { case (mcpManager, pluginManifests) =>
                val chatRoutes = new ChatRoutes(handle, token)
                // Read contextWindow from config for the primary model
                val contextWindow =
                  val (providerId, modelId) = Config.parseModelRef(config.llm.model.primary)
                  val provider = config.llm.providers
                    .getOrElse(providerId, throw new RuntimeException(s"Unknown provider: $providerId"))
                  provider.models.find(_.id == modelId).map(_.contextWindow).getOrElse(Defaults.ContextWindow)
                val baseUrl = s"http://localhost:${cfg.port}"
                val url = s"$baseUrl?token=$token"

                logger.info(s"nebflow v${nebflow.Version.string}") *>
                  logger.info(s"gateway listening on ${cfg.host}:${cfg.port}") *>
                  logger.info(s"access URL: $baseUrl (token in ~/.nebflow/.token)") *>
                  initSkillDiscovery(config, handle).flatMap { skillDiscoveryOpt =>
                    RuntimePreferencesService.create.flatMap { runtimePrefs =>
                      RateLimiter.create().flatMap { rateLimiter =>
                        FileChangeTracker.create(System.getProperty("user.dir")).flatMap { fileTracker =>
                          Ref.of[IO, ReminderState](ReminderState()).flatMap { reminderStateRef =>
                            // Create Dispatcher for the multi-agent runtime, then start server
                            cats.effect.std.Dispatcher.parallel[IO].use { dispatcher =>
                              val agentLibrary = new AgentLibrary(AgentLibrary.defaultDir, Some(config))
                              cats.effect.std.Semaphore[IO](1).flatMap { askSemaphore =>
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
                                  skillDiscovery = skillDiscoveryOpt,
                                  agentLibrary = agentLibrary,
                                  askSemaphore = askSemaphore,
                                  taskStore = FileTaskStore,
                                  historyArchiver = nebflow.core.compact.HistoryArchiver.fileSystem(os.pwd)
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
                                      skillDiscoveryOpt,
                                      sharedResources,
                                      pluginManifests
                                    )
                                    Router(
                                      "/api" -> chatRoutes.routes,
                                      "/" -> wsRoutes.routes
                                    ).orNotFound
                                  }
                                  .build
                                  .use { _ =>
                                    openBrowser(url) *> IO.never[Unit]
                                  }
                                  .guarantee(
                                    mcpManager.stopAll() *>
                                      releaseBackend *>
                                      IO { actorSystem.terminate() }
                                  )
                              } // end askSemaphore
                            } // end dispatcher.use
                          } // end reminderStateRef
                        } // end fileTracker
                      } // end rateLimiter
                    } // end runtimePrefs
                  } // end skillDiscoveryOpt
              }
            }
          }
        }
      } // end config flatMap
    }

  private def initSkillDiscovery(config: NebflowServiceConfig, llm: LlmHandle[IO]): IO[Option[SkillDiscovery]] =
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

        (for
          _ <- qdrant.ensureCollection(viConfig.collection, vectorSize)
          _ <- indexer.indexIncremental(skillsDir, llm)
        yield Some(new SkillDiscovery(qdrant, embedding, viConfig)))
          .handleErrorWith { e =>
            logger.warn(s"Skill system init failed, continuing without: ${e.getMessage}") *> IO.pure(None)
          }
      case _ =>
        IO.pure(None)
end GatewayMain
