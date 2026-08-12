package nebflow.agent

import nebflow.shared.AgentModelConfig

/**
 * Runtime agent definition.
 *
 * All fields are resolved from code ([[AgentLibrary.defaults]]), not from disk.
 * The system prompt may be overridden by a user-edited system.md file.
 * contextWindow / maxTokens are resolved at runtime from nebflow.json.
 */
case class AgentDef(
  name: String,
  description: String,
  tools: List[String] = Nil,
  systemPrompt: String = "",
  avatar: Option[String] = None,
  displayName: Option[String] = None,
  voiceEnabled: Boolean = true,
  model: Option[AgentModelConfig] = None,
  preset: Option[String] = None, // references a named preset in model-presets.json
  category: String = "standalone",
  mcpServers: List[String] = Nil,
  skills: List[String] = Nil,   // skill names this agent can see
  flows: List[String] = Nil     // flow names this agent can trigger
)
