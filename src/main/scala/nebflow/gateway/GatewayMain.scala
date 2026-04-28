package nebflow.gateway

import cats.effect.{IO, IOApp, Ref}
import cats.syntax.all.*
import io.circe.syntax.*
import nebflow.core.mcp.*
import nebflow.core.tools.ToolRegistry
import nebflow.core.{NebflowLogger, PermissionState}
import nebflow.llm.{Config, LlmInterface, NebflowServiceConfig}
import nebflow.shared.{Message, given}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router

import scala.concurrent.duration.*

object GatewayMain extends IOApp.Simple:
  private val logger = NebflowLogger.forName("nebflow.gateway")

  private val webSessionPath = os.home / ".nebflow" / "sessions" / "web.json"

  private def loadWebSession(): IO[List[Message]] = IO.blocking {
    if os.exists(webSessionPath) then
      io.circe.parser.decode[List[Message]](os.read(webSessionPath)) match
        case Right(msgs) => msgs
        case Left(_) => Nil
    else Nil
  }

  private def saveWebSession(messages: List[Message]): IO[Unit] = IO.blocking {
    os.write.over(webSessionPath, messages.asJson.spaces2, createFolders = true)
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

  private def initMcpServers(config: NebflowServiceConfig): IO[List[Unit]] =
    config.mcpServers match
      case Some(servers) =>
        servers.toList
          .traverse { case (serverId, serverConfig) =>
            val transport = (serverConfig.command, serverConfig.url) match
              case (Some(cmd), _) =>
                new StdioTransport(cmd, serverConfig.args.getOrElse(Nil), serverConfig.env.getOrElse(Map.empty))
              case (_, Some(url)) =>
                new HttpTransport(url, serverConfig.headers.getOrElse(Map.empty))
              case _ =>
                throw new RuntimeException(s"MCP server $serverId must have either command or url")

            val client = new McpClient(transport)
            client.initialize() *>
              client.listTools().flatMap { tools =>
                val wrapped = tools.map(t => createMcpToolWrapper(serverId, t, client))
                IO.delay(ToolRegistry.registerTools(wrapped))
              }
          }
          .handleErrorWith { e =>
            IO.println(s"[MCP] Warning: ${e.getMessage}") *> IO.pure(Nil)
          }
      case None => IO.pure(Nil)

  def run: IO[Unit] =
    GatewayConfig.load.flatMap { cfg =>
      val config = Config.loadServiceConfig()

      Auth.loadOrCreateToken.flatMap { token =>
        // Global session state shared across all connections
        loadWebSession().flatMap { initialHistory =>
          Ref.of[IO, List[Message]](initialHistory).flatMap { globalMessagesRef =>
            LlmInterface.createLlm().flatMap { case (handle, releaseBackend) =>
              initMcpServers(config).flatMap { _ =>
                val chatRoutes = new ChatRoutes(handle, token)
                val baseUrl = s"http://localhost:${cfg.port}"
                val url = s"$baseUrl?token=$token"

                logger.info(s"nebflow v${nebflow.Version.string}") *>
                  logger.info(s"gateway listening on ${cfg.host}:${cfg.port}") *>
                  logger.info(s"access URL: $url") *>
                  Ref.of[IO, Option[io.circe.Json]](None).flatMap { thinkingModeRef =>
                    PermissionState.create.flatMap { permState =>
                      RateLimiter.create().flatMap { rateLimiter =>
                        EmberServerBuilder
                          .default[IO]
                          .withHost(cfg.host)
                          .withPort(cfg.port)
                          .withHttpWebSocketApp { wsb =>
                            val wsRoutes =
                              new WebSocketRoutes(
                                wsb,
                                handle,
                                globalMessagesRef,
                                saveWebSession,
                                thinkingModeRef,
                                permState,
                                rateLimiter,
                                token
                              )
                            Router(
                              "/api" -> chatRoutes.routes,
                              "/" -> wsRoutes.routes
                            ).orNotFound
                          }
                          .build
                          .use { _ =>
                            logger.info(s"opening $url") *>
                              openBrowser(url) *>
                              IO.sleep(1.second).foreverM
                          }
                          .guarantee(releaseBackend)
                      }
                    }
                  }
              }
            }
          }
        }
      }
    }
end GatewayMain
