package nebflow.agent

import io.circe.*
import io.circe.derivation.{Configuration, ConfiguredCodec}
import io.circe.generic.semiauto.*

/** Frontend asset configuration for an agent. */
case class FrontendConfig(
  scripts: List[String] = Nil,
  styles: List[String] = Nil
)

object FrontendConfig:
  given Codec[FrontendConfig] = deriveCodec

/**
 * Agent definition from agent.json.
 *  contextWindow / maxTokens are NOT stored here — resolved at runtime from nebflow.json.
 */
case class AgentDef(
  name: String,
  description: String,
  tools: List[String] = Nil,
  /** IDs of globally-registered MCP servers this agent enables. */
  mcpServers: List[String] = Nil,
  systemPrompt: String = "",
  configPath: String = "",
  frontend: Option[FrontendConfig] = None,
  avatar: Option[String] = None,
  displayName: Option[String] = None
)

object AgentDef:
  private given Configuration = Configuration.default.withDefaults

  given Codec[AgentDef] = ConfiguredCodec.derived

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
