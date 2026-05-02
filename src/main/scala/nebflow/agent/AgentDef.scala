package nebflow.agent

import io.circe.*
import io.circe.generic.semiauto.*
import nebflow.shared.Defaults

/** Static definition of an agent, loaded from ~/.nebflow/agents/<name>/agent.yaml
  */
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
  yamlPath: String = ""
)

case class SubagentSlot(
  name: String,
  agent: String
)

object AgentDef:
  given Codec[AgentDef] = deriveCodec
  given Codec[SubagentSlot] = deriveCodec

  /** Parse a simple YAML-like agent definition.
    * Uses the same simple YAML parser as SkillFile.
    */
  def parseYaml(yaml: String, basePath: os.Path): AgentDef =
    val fields = parseSimpleYaml(yaml)
    val name = fields.getOrElse("name", basePath.last)
    val description = fields.getOrElse("description", "")
    val modelRoute = fields.getOrElse("modelRoute", "default")
    val contextWindow = fields.getOrElse("contextWindow", "128000").toIntOption.getOrElse(Defaults.ContextWindow)
    val maxTokens = fields.getOrElse("maxTokens", "16384").toIntOption.getOrElse(Defaults.MaxTokens)
    val keepAlive = fields.getOrElse("keepAlive", "false").toBooleanOption.getOrElse(false)
    val toolsStr = fields.getOrElse("tools", "")
    val tools = if toolsStr.isBlank then Nil
      else toolsStr.split(",").map(_.trim.stripPrefix("- ").trim).filter(_.nonEmpty).toList

    // Parse subagents (simple format: "researcher: researcher, coder: coder")
    val subagentsStr = fields.getOrElse("subagents", "")
    val subagents = if subagentsStr.isBlank then Nil
      else
        subagentsStr.split(",").map(_.trim).filter(_.nonEmpty).flatMap { entry =>
          val parts = entry.split(":", 2).map(_.trim)
          if parts.length == 2 then Some(SubagentSlot(parts(0), parts(1)))
          else None
        }.toList

    // Load system prompt from system.md in the same directory
    val systemMdPath = basePath / "system.md"
    val systemPrompt = if os.exists(systemMdPath) then os.read(systemMdPath) else ""

    AgentDef(
      name = name,
      description = description,
      modelRoute = modelRoute,
      contextWindow = contextWindow,
      maxTokens = maxTokens,
      tools = tools,
      subagents = subagents,
      keepAlive = keepAlive,
      systemPrompt = systemPrompt,
      yamlPath = basePath.toString
    )

  private def parseSimpleYaml(text: String): Map[String, String] =
    text.linesIterator
      .map { line =>
        val trimmed = line.trim
        if trimmed.startsWith("#") then None
        else
          val idx = trimmed.indexOf(':')
          if idx >= 0 then
            val key = trimmed.substring(0, idx).trim
            var value = trimmed.substring(idx + 1).trim
            if !value.startsWith("\"") && !value.startsWith("'") then
              val commentIdx = value.indexOf('#')
              if commentIdx >= 0 then value = value.substring(0, commentIdx).trim
            value = value.stripPrefix("\"").stripSuffix("\"").stripPrefix("'").stripSuffix("'")
            if key.nonEmpty then Some(key -> value) else None
          else None
      }
      .collect { case Some(k -> v) => k -> v }
      .toMap

end AgentDef
