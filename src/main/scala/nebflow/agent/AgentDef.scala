package nebflow.agent

import io.circe.*
import io.circe.generic.semiauto.*
import nebflow.shared.Defaults

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
  configPath: String = ""
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
