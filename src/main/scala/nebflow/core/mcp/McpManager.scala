package nebflow.core.mcp

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.generic.semiauto.deriveDecoder
import io.circe.parser.parse
import io.circe.{Decoder, Json}
import nebflow.core.NebflowLogger
import nebflow.core.tools.ToolRegistry
import nebflow.llm.McpServerConfig

import scala.concurrent.duration.*

/** Manages lifecycle of all MCP servers: config loading, connection, tool registration, shutdown. */
class McpManager private (
  serversRef: Ref[IO, Map[String, (McpClient, List[nebflow.core.tools.Tool])]]
):
  private val logger = NebflowLogger.forName("nebflow.mcp")

  /** Start all configured MCP servers concurrently with per-server timeout and error isolation. */
  def startAll(configs: Map[String, McpServerConfig]): IO[Unit] =
    if configs.isEmpty then IO.unit
    else
      configs.toList.parTraverse { case (id, cfg) =>
        connectServer(id, cfg)
          .timeout(5.seconds)
          .handleErrorWith { e =>
            logger.error(s"MCP server '$id' failed to start: ${e.getMessage}") *> IO.unit
          }
      }.void

  /** Gracefully close all MCP connections with per-server timeout. */
  def stopAll(): IO[Unit] =
    serversRef.get.flatMap { servers =>
      servers.values.toList.parTraverse_ { case (client, _) =>
        client
          .close()
          .timeout(3.seconds)
          .handleErrorWith(_ => IO.unit)
      }
    }

  private def connectServer(id: String, cfg: McpServerConfig): IO[Unit] =
    (cfg.command, cfg.url) match
      case (Some(cmd), _) =>
        val transport = new StdioTransport(cmd, cfg.args.getOrElse(Nil), cfg.env.getOrElse(Map.empty))
        connectWithTransport(id, transport)
      case (_, Some(url)) =>
        val transport = new HttpTransport(url, cfg.headers.getOrElse(Map.empty))
        connectWithTransport(id, transport)
      case _ =>
        IO.raiseError(new RuntimeException(s"MCP server '$id' must have either command or url"))

  private def connectWithTransport(id: String, transport: McpTransport): IO[Unit] =
    val client = new McpClient(id, transport)
    for
      _ <- client.initialize()
      tools <- client.listTools()
      wrapped = tools.map(t => createMcpToolWrapper(id, t, client))
      _ <- IO.delay(ToolRegistry.registerTools(wrapped))
      // Register notification handler for tools/list_changed
      _ <- transport.onNotification { notification =>
        if notification.method == "notifications/tools/list_changed" then refreshServerTools(id, client)
        else IO.unit
      }
      _ <- logger.info(s"MCP server '$id' connected, ${wrapped.size} tools registered")
      _ <- serversRef.update(_ + (id -> (client, wrapped)))
    yield ()

  /** Re-fetch tool list from server and update ToolRegistry. */
  private def refreshServerTools(id: String, client: McpClient): IO[Unit] =
    for
      tools <- client.listTools().handleErrorWith { e =>
        logger.error(s"Failed to refresh tools for '$id': ${e.getMessage}") *> IO.pure(Nil)
      }
      wrapped = tools.map(t => createMcpToolWrapper(id, t, client))
      _ <- IO.delay(ToolRegistry.registerTools(wrapped))
      _ <- serversRef.update { servers =>
        servers.get(id).map { case (_, _) => servers.updated(id, (client, wrapped)) }.getOrElse(servers)
      }
      _ <- logger.info(s"MCP server '$id' tools refreshed, ${wrapped.size} tools registered")
    yield ()

end McpManager

object McpManager:

  def create: IO[McpManager] =
    Ref.of[IO, Map[String, (McpClient, List[nebflow.core.tools.Tool])]](Map.empty).map { ref =>
      new McpManager(ref)
    }

  /**
   * Load MCP server configs from ~/.nebflow/mcp-servers.json if it exists.
   *  Falls back to empty map if file is missing or invalid.
   */
  def loadMcpServersJson: IO[Map[String, McpServerConfig]] = IO
    .blocking {
      val path = nebflow.llm.Config.NebflowHome / "mcp-servers.json"
      if !os.exists(path) then Map.empty[String, McpServerConfig]
      else
        val raw = os.read(path)
        parse(raw).flatMap(_.as[Map[String, McpServerConfig]]) match
          case Right(cfg) => cfg
          case Left(err) =>
            NebflowLogger.forName("nebflow.mcp").warn(s"Failed to parse mcp-servers.json: ${err.getMessage}")
            Map.empty[String, McpServerConfig]
    }
    .handleError { e =>
      NebflowLogger.forName("nebflow.mcp").warn(s"Failed to load mcp-servers.json: ${e.getMessage}")
      Map.empty[String, McpServerConfig]
    }
end McpManager
