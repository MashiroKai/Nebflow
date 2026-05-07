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
  allServerIds: Ref[IO, Set[String]],
  disabledRef: Ref[IO, Set[String]]
):
  private val logger = NebflowLogger.forName("nebflow.mcp")

  /**
   * Start all configured MCP servers concurrently.
   * All servers are connected, but tools from disabled servers are not registered in ToolRegistry.
   */
  def startAll(configs: Map[String, McpServerConfig], disabledServers: Set[String]): IO[Unit] =
    if configs.isEmpty then IO.unit
    else
      allServerIds.set(configs.keySet) *>
        disabledRef.set(disabledServers) *>
        configs.toList.parTraverse { case (id, cfg) =>
          connectServer(id, cfg, skipRegister = disabledServers.contains(id))
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

  /** Get all configured server IDs with their enabled status. */
  def listServers: IO[List[(String, Boolean)]] =
    for
      ids <- allServerIds.get
      disabled <- disabledRef.get
    yield ids.toList.sorted.map(id => (id, !disabled.contains(id)))

  /** Enable/disable an MCP server at runtime. Affects new sessions only. */
  def setEnabled(serverId: String, enabled: Boolean): IO[Unit] =
    val toolPrefix = s"mcp__${serverId}__"
    if enabled then
      for
        _ <- disabledRef.update(_ - serverId)
        // Re-register tools if server is running
        servers <- serversRef.get
        _ <- servers.get(serverId) match
          case Some((_, tools)) =>
            IO.delay(ToolRegistry.registerTools(tools)) *>
              logger.info(s"MCP server '$serverId' enabled, ${tools.size} tools registered")
          case None => IO.unit
      yield ()
    else
      for
        _ <- disabledRef.update(_ + serverId)
        _ <- IO.delay(ToolRegistry.unregisterToolsByPrefix(toolPrefix))
        _ <- logger.info(s"MCP server '$serverId' disabled, tools unregistered")
      yield ()

  private def connectServer(id: String, cfg: McpServerConfig, skipRegister: Boolean): IO[Unit] =
    (cfg.command, cfg.url) match
      case (Some(cmd), _) =>
        val transport = new StdioTransport(cmd, cfg.args.getOrElse(Nil), cfg.env.getOrElse(Map.empty))
        connectWithTransport(id, transport, skipRegister)
      case (_, Some(url)) =>
        val transport = new HttpTransport(url, cfg.headers.getOrElse(Map.empty))
        connectWithTransport(id, transport, skipRegister)
      case _ =>
        IO.raiseError(new RuntimeException(s"MCP server '$id' must have either command or url"))

  private def connectWithTransport(id: String, transport: McpTransport, skipRegister: Boolean): IO[Unit] =
    val client = new McpClient(id, transport)
    for
      _ <- client.initialize()
      tools <- client.listTools()
      wrapped = tools.map(t => createMcpToolWrapper(id, t, client))
      _ <-
        if skipRegister then
          logger.info(s"MCP server '$id' connected, ${wrapped.size} tools discovered (disabled, not registered)")
        else
          IO.delay(ToolRegistry.registerTools(wrapped)) *>
            logger.info(s"MCP server '$id' connected, ${wrapped.size} tools registered")
      // Register notification handler for tools/list_changed
      _ <- transport.onNotification { notification =>
        if notification.method == "notifications/tools/list_changed" then refreshServerTools(id, client)
        else IO.unit
      }
      _ <- serversRef.update(_ + (id -> (client, wrapped)))
    yield ()

  /** Re-fetch tool list from server and update ToolRegistry (only if server is enabled). */
  private def refreshServerTools(id: String, client: McpClient): IO[Unit] =
    for
      tools <- client.listTools().handleErrorWith { e =>
        logger.error(s"Failed to refresh tools for '$id': ${e.getMessage}") *> IO.pure(Nil)
      }
      wrapped = tools.map(t => createMcpToolWrapper(id, t, client))
      disabled <- disabledRef.get
      _ <- if !disabled.contains(id) then IO.delay(ToolRegistry.registerTools(wrapped)) else IO.unit
      _ <- serversRef.update { servers =>
        servers.get(id).map { case (_, _) => servers.updated(id, (client, wrapped)) }.getOrElse(servers)
      }
      _ <- logger.info(s"MCP server '$id' tools refreshed, ${wrapped.size} tools discovered")
    yield ()

end McpManager

object McpManager:

  def create(disabledServers: Set[String] = Set.empty): IO[McpManager] =
    for
      serversRef <- Ref.of[IO, Map[String, (McpClient, List[nebflow.core.tools.Tool])]](Map.empty)
      allServerIds <- Ref.of[IO, Set[String]](Set.empty)
      disabledRef <- Ref.of[IO, Set[String]](disabledServers)
    yield new McpManager(serversRef, allServerIds, disabledRef)

end McpManager
