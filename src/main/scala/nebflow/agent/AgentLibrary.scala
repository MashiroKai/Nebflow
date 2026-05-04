package nebflow.agent

import cats.effect.IO
import nebflow.core.NebflowLogger
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
class AgentLibrary(agentsDir: os.Path):
  private val logger = NebflowLogger.forName("nebflow.agent.library")

  // Built-in agents that don't require filesystem definitions
  private val builtins: Map[String, AgentDef] = Map(
    "context-manage" -> AgentDef(
      name = "context-manage",
      description = "Compress conversation history to free up context window space",
      modelRoute = "default",
      contextWindow = 60000,
      maxTokens = 8192,
      tools = List(),
      subagents = Nil,
      systemPrompt = nebflow.core.compact.CompactPrompts.full
    ),
    "default" -> AgentDef(
      name = "default",
      description = "Default chat agent for general-purpose conversations",
      modelRoute = "default",
      contextWindow = Defaults.ContextWindow,
      maxTokens = Defaults.MaxTokens,
      tools = List("*"),
      subagents = Nil,
      systemPrompt = ""
    )
  )

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
              Some(defn.name -> defn)
            else None
          }
          .toMap ++ builtins
    }.flatTap { defs =>
      IO(logger.info(s"Loaded ${defs.size} agent definitions from $agentsDir: ${defs.keys.mkString(", ")}"))
    }

  def get(name: String): IO[Option[AgentDef]] =
    loadAll().map(_.get(name).orElse(builtins.get(name)))

end AgentLibrary

object AgentLibrary:
  def defaultDir: os.Path = os.home / ".nebflow" / "agents"
