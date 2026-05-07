package nebflow.agent

import io.circe.*
import io.circe.generic.semiauto.*
import nebflow.shared.Defaults

/** MCP server configuration embedded in an agent definition. */
case class AgentMcpConfig(
  command: Option[String] = None,
  args: Option[List[String]] = None,
  env: Option[Map[String, String]] = None,
  url: Option[String] = None,
  headers: Option[Map[String, String]] = None
)

object AgentMcpConfig:
  given Codec[AgentMcpConfig] = deriveCodec

  def toMcpServerConfig(cfg: AgentMcpConfig): nebflow.llm.McpServerConfig =
    nebflow.llm.McpServerConfig(
      command = cfg.command,
      args = cfg.args,
      env = cfg.env,
      url = cfg.url,
      headers = cfg.headers
    )

/** Frontend asset configuration for an agent. */
case class FrontendConfig(
  scripts: List[String] = Nil,
  styles: List[String] = Nil
)

object FrontendConfig:
  given Codec[FrontendConfig] = deriveCodec

/** Static definition of an agent, loaded from ~/.nebflow/agents/<name>/agent.json */
case class AgentDef(
  name: String,
  description: String,
  modelRoute: String = "default",
  contextWindow: Int = Defaults.ContextWindow,
  maxTokens: Int = Defaults.MaxTokens,
  tools: List[String] = Nil,
  subagents: List[SubagentSlot] = Nil,
  keepAlive: Boolean = false,
  systemPrompt: String = "",
  configPath: String = "",
  mcp: Option[AgentMcpConfig] = None,
  frontend: Option[FrontendConfig] = None,
  avatar: Option[String] = None,
  displayName: Option[String] = None
)

case class SubagentSlot(
  name: String,
  agent: String
)

object AgentDef:
  given Codec[AgentDef] = deriveCodec
  given Codec[SubagentSlot] = deriveCodec

  def parseJson(jsonStr: String, basePath: os.Path): AgentDef =
    import io.circe.parser.decode
    decode[AgentDef](jsonStr)
      .getOrElse(AgentDef(name = basePath.last, description = ""))
      .copy(
        systemPrompt =
          val p = basePath / "system.md"
          if os.exists(p) then os.read(p) else ""
        ,
        configPath = basePath.toString
      )

end AgentDef
