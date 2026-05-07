package nebflow.agent

import cats.effect.IO
import nebflow.core.NebflowLogger
import nebflow.llm.{Config, NebflowServiceConfig}
import nebflow.shared.Defaults

/**
 * Loads agent definitions from ~/.nebflow/agents/ directory.
 * Directory structure:
 *   ~/.nebflow/agents/
 *     arch/
 *       agent.json
 *       system.md
 *     tina/
 *       agent.json
 *       system.md
 */
class AgentLibrary(agentsDir: os.Path, serviceConfig: Option[NebflowServiceConfig] = None):
  private val logger = NebflowLogger.forName("nebflow.agent.library")

  private def resolveContextWindow(current: Int): Int =
    if current != Defaults.ContextWindow then current
    else
      serviceConfig match
        case None => Defaults.ContextWindow
        case Some(cfg) =>
          val (providerId, modelId) = Config.parseModelRef(cfg.llm.model.primary)
          val provider = cfg.llm.providers
            .getOrElse(providerId, throw new RuntimeException(s"Unknown provider: $providerId"))
          provider.models.find(_.id == modelId).map(_.contextWindow).getOrElse(Defaults.ContextWindow)

  // Built-in agents that don't require filesystem definitions
  private val builtins: Map[String, AgentDef] =
    val ctxManage = AgentDef(
      name = "context-manage",
      description = "Compress conversation history to free up context window space",
      modelRoute = "default",
      contextWindow = 60000,
      maxTokens = 8192,
      tools = List(),
      subagents = Nil,
      systemPrompt = nebflow.core.compact.CompactPrompts.full
    )
    val defaultAgent = AgentDef(
      name = "Nebula",
      description = "Default chat agent for general-purpose conversations",
      modelRoute = "default",
      contextWindow = Defaults.ContextWindow,
      maxTokens = Defaults.MaxTokens,
      tools = List("*"),
      subagents = Nil,
      systemPrompt = ""
    )
    val askAgent = AgentDef(
      name = "Ask",
      description = "Quick follow-up Q&A agent with read and web search tools",
      modelRoute = "default",
      contextWindow = Defaults.ContextWindow,
      maxTokens = 4096,
      tools = List("Read", "Glob", "Grep", "WebSearch", "WebFetch", "Curl"),
      subagents = Nil,
      systemPrompt =
        """You are a helpful assistant answering a quick follow-up question about an ongoing conversation.
          |You have access to file reading, code search, and web search tools to help answer accurately.
          |
          |Rules:
          |- Answer the user's question directly and concisely.
          |- Use tools when needed to find accurate information.
          |- Your response will NOT be saved to the conversation history.
          |- This is a single exchange: answer the question, then stop.""".stripMargin
    )
    Map(
      "context-manage" -> ctxManage,
      "Nebula" -> defaultAgent.copy(contextWindow = resolveContextWindow(defaultAgent.contextWindow)),
      "Ask" -> askAgent.copy(contextWindow = resolveContextWindow(askAgent.contextWindow))
    )

  end builtins

  def loadAll(): IO[Map[String, AgentDef]] =
    IO.blocking {
      if !os.exists(agentsDir) then Map.empty
      else
        os.list(agentsDir)
          .filter(os.isDir)
          .flatMap { dir =>
            val jsonPath = dir / "agent.json"
            if os.exists(jsonPath) then
              val json = os.read(jsonPath)
              val defn = AgentDef.parseJson(json, dir)
              val resolved = defn.copy(contextWindow = resolveContextWindow(defn.contextWindow))
              Some(resolved.name -> resolved)
            else None
          }
          .toMap ++ builtins
    }.flatTap { defs =>
      IO(logger.info(s"Loaded ${defs.size} agent definitions from $agentsDir: ${defs.keys.mkString(", ")}"))
    }

  def get(name: String): IO[Option[AgentDef]] =
    loadAll().map(_.get(name).orElse(builtins.get(name)))

  /** Resolved context window from nebflow.json primary model config. */
  def resolvedContextWindow: Int = resolveContextWindow(Defaults.ContextWindow)

end AgentLibrary

object AgentLibrary:
  def defaultDir: os.Path = os.home / ".nebflow" / "agents"
end AgentLibrary
