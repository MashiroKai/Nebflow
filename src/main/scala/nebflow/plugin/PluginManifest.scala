package nebflow.plugin

import io.circe.{Decoder, Json}
import io.circe.generic.semiauto.deriveDecoder

/** Frontend asset configuration for a plugin. */
case class FrontendConfig(
  scripts: List[String] = Nil,
  styles: List[String] = Nil
)

object FrontendConfig:
  given Decoder[FrontendConfig] = deriveDecoder[FrontendConfig]

/** MCP server configuration embedded in a plugin manifest. */
case class PluginMcpConfig(
  command: Option[String] = None,
  args: Option[List[String]] = None,
  env: Option[Map[String, String]] = None,
  url: Option[String] = None,
  headers: Option[Map[String, String]] = None
)

object PluginMcpConfig:
  given Decoder[PluginMcpConfig] = deriveDecoder[PluginMcpConfig]

  def toMcpServerConfig(cfg: PluginMcpConfig): nebflow.llm.McpServerConfig =
    nebflow.llm.McpServerConfig(
      command = cfg.command,
      args = cfg.args,
      env = cfg.env,
      url = cfg.url,
      headers = cfg.headers
    )

/** Parsed plugin.yaml manifest. */
case class PluginManifest(
  name: String,
  version: String = "1.0.0",
  description: String = "",
  mcp: Option[PluginMcpConfig] = None,
  frontend: Option[FrontendConfig] = None
)

object PluginManifest:
  given Decoder[PluginManifest] = deriveDecoder[PluginManifest]
