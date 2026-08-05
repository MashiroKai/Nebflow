package nebflow.core.mcp

import cats.effect.IO
import cats.syntax.all.*
import io.circe.parser.decode
import nebflow.core.{NebflowLogger, PathUtil}
import nebflow.llm.McpServerConfig

/**
 * Loads agent-scoped MCP servers from agent directory `tools/mcp/`
 * (each `*.json` file holds one server config).
 *
 * Layout (three-layer agents):
 *   - agents/<name>/tools/mcp/               (global agents)
 *   - teams/<team>/agents/<name>/tools/mcp/  (team agents)
 *   - flows/<flow>/agents/<name>/tools/mcp/  (flow agents)
 *
 * Each JSON file holds an [[McpServerConfig]]; the file name (minus .json) is
 * the server name. The runtime server id is `agent-<agentName>-<serverName>`
 * so it can never collide with global servers. Tools register under
 * `mcp__agent-<agentName>-<serverName>__<tool>` and are auto-allowed for that
 * agent only (see AgentCore.buildAllowedToolSet).
 *
 * Loaded statically at Nebflow startup — not hot-reloaded.
 */
object AgentMcpLoader:
  private val logger = NebflowLogger.forName("nebflow.mcp.agent")

  /** Runtime server id for an agent-scoped MCP server: `agent-<agentName>-<serverName>`. */
  def serverIdFor(agentName: String, serverName: String): String =
    s"agent-$agentName-$serverName"

  /** Scan all three agent layers for `tools/mcp/` (`*.json` configs). Returns (agentName, serverId, config). */
  def scanAll(): IO[List[(String, String, McpServerConfig)]] =
    for
      global <- scanBase(PathUtil.dataRoot / "agents")
      team <- scanBase(PathUtil.dataRoot / "teams")
      flow <- scanBase(PathUtil.dataRoot / "flows")
    yield global ++ team ++ flow

  /**
   * Scan one top-level layer dir for `tools/mcp/` subdirectories and decode
   * every `*.json` config found. The agent name is read from the enclosing
   * agent.json `name` field (falling back to the directory name).
   */
  private def scanBase(baseDir: os.Path): IO[List[(String, String, McpServerConfig)]] =
    IO.blocking {
      if !os.exists(baseDir) then Nil
      else
        os.walk(baseDir)
          .filter(p => os.isDir(p) && p.last == "mcp" && (p / os.up).last == "tools")
          .toList
    }.flatMap { mcpDirs =>
      mcpDirs.traverse { mcpDir =>
        val agentDir = mcpDir / os.up / os.up
        loadServerConfigs(mcpDir, agentNameFor(agentDir))
      }
    }.map(_.flatten)

  /** Decode all `*.json` configs in a single `tools/mcp/` dir. */
  private def loadServerConfigs(mcpDir: os.Path, agentName: String): IO[List[(String, String, McpServerConfig)]] =
    IO.blocking {
      os.list(mcpDir).filter(f => os.isFile(f) && f.last.endsWith(".json")).toList
    }.flatMap { files =>
      files.traverse { f =>
        val serverName = f.baseName
        IO.blocking(decode[McpServerConfig](os.read(f))).flatMap {
          case Right(cfg) =>
            IO.pure(Some((agentName, serverIdFor(agentName, serverName), cfg)))
          case Left(err) =>
            logger.warn(s"Skipping invalid MCP config at $f: ${err.getMessage}").as(None)
        }
      }
    }.map(_.flatten)

  /** Agent name from agent.json `name` field, falling back to the directory name. */
  private def agentNameFor(agentDir: os.Path): String =
    val jsonPath = agentDir / "agent.json"
    if os.exists(jsonPath) then
      decode[io.circe.Json](os.read(jsonPath)).toOption
        .flatMap(_.hcursor.downField("name").as[String].toOption)
        .filter(_.nonEmpty)
        .getOrElse(agentDir.last)
    else agentDir.last
end AgentMcpLoader
