package nebflow.core.mcp

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import nebflow.core.NebflowLogger
import nebflow.core.tools.ToolRegistry
import nebflow.llm.McpServerConfig

import scala.concurrent.duration.*

/** Manages lifecycle of all MCP servers: config loading, connection, tool registration, shutdown. */
class McpManager private (
  serversRef: Ref[IO, Map[String, (McpClient, List[nebflow.core.tools.Tool])]],
  serverEnabledMap: Ref[IO, Map[String, Boolean]]
):
  private val logger = NebflowLogger.forName("nebflow.mcp")

  /** Start all enabled MCP servers concurrently. Disabled servers are tracked but not started. */
  def startAll(configs: Map[String, McpServerConfig]): IO[Unit] =
    if configs.isEmpty then IO.unit
    else
      val enabledMap = configs.map { case (id, cfg) => id -> cfg.isEnabled }
      serverEnabledMap.set(enabledMap) *>
        configs.toList.parTraverse { case (id, cfg) =>
          if cfg.isEnabled then
            connectServer(id, cfg)
              .timeout(5.seconds)
              .handleErrorWith { e =>
                logger.error(s"MCP server '$id' failed to start: ${e.getMessage}") *> IO.unit
              }
          else
            logger.info(s"MCP server '$id' is disabled, skipping")
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

  /** Get all configured server IDs with their enabled status. */
  def listServers: IO[List[(String, Boolean)]] =
    serverEnabledMap.get.map(_.toList.sorted.map { case (id, enabled) => (id, enabled) })

  /** Enable a server: update enabled flag and start it. */
  def enableServer(id: String, cfg: McpServerConfig): IO[Unit] =
    serverEnabledMap.update(_ + (id -> true)) *>
      connectServer(id, cfg)
        .timeout(10.seconds)
        .handleErrorWith { e =>
          logger.error(s"MCP server '$id' failed to start: ${e.getMessage}") *> IO.unit
        }

  /** Disable a server: stop it and update enabled flag. */
  def disableServer(id: String): IO[Unit] =
    serverEnabledMap.update(_ + (id -> false)) *>
      stopServer(id)

  /** Connect a single MCP server and register its tools. Public for AgentLibrary use. */
  def startServer(id: String, cfg: McpServerConfig): IO[Unit] =
    connectServer(id, cfg)

  /** Stop a single MCP server, unregister its tools, and close the connection. */
  def stopServer(id: String): IO[Unit] =
    serversRef.get.flatMap { servers =>
      servers.get(id) match
        case Some((client, _)) =>
          for
            _ <- IO.delay(ToolRegistry.unregisterToolsByPrefix(s"mcp__${id}__"))
            _ <- serversRef.update(_ - id)
            _ <- client.close().timeout(3.seconds).handleErrorWith(_ => IO.unit)
            _ <- logger.info(s"MCP server '$id' stopped")
          yield ()
        case None => IO.unit
    }

  private def connectServer(id: String, cfg: McpServerConfig): IO[Unit] =
    (cfg.command, cfg.url) match
      case (Some(cmd), _) =>
        StdioTransport(cmd, cfg.args.getOrElse(Nil), cfg.env.getOrElse(Map.empty))
          .flatMap(transport => connectWithTransport(id, transport))
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
      _ <- IO.delay(ToolRegistry.registerTools(wrapped)) *>
        logger.info(s"MCP server '$id' connected, ${wrapped.size} tools registered")
      // Register notification handler for tools/list_changed
      _ <- transport.onNotification { notification =>
        if notification.method == "notifications/tools/list_changed" then refreshServerTools(id, client)
        else IO.unit
      }
      _ <- serversRef.update(_ + (id -> (client, wrapped)))
    yield ()

  end connectWithTransport

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
      _ <- logger.info(s"MCP server '$id' tools refreshed, ${wrapped.size} tools discovered")
    yield ()

end McpManager

object McpManager:

  def create: IO[McpManager] =
    for
      serversRef <- Ref.of[IO, Map[String, (McpClient, List[nebflow.core.tools.Tool])]](Map.empty)
      serverEnabledMap <- Ref.of[IO, Map[String, Boolean]](Map.empty)
    yield new McpManager(serversRef, serverEnabledMap)

end McpManager
