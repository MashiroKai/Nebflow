package nebflow.agent
import nebflow.core.PathUtil

import cats.effect.IO
import cats.syntax.all.*
import nebflow.core.NebflowLogger
import nebflow.llm.{Config, NebflowServiceConfig}
import nebflow.shared.{Defaults, MtimeCache}

/**
 * Manages agent definitions stored under ~/.nebflow/agents/<name>/.
 *
 * All agents live on disk as user-editable files. Builtin agents (e.g. Nebula)
 * are seeded on first run; after that they are treated the same as user-created ones.
 *
 * contextWindow / maxTokens are NOT in agent.json — resolved at runtime from nebflow.json.
 */
class AgentLibrary(
  agentsDir: os.Path,
  serviceConfig: Option[NebflowServiceConfig] = None
):
  private val logger = NebflowLogger.forName("nebflow.agent.library")

  /** Resolve context window from nebflow.json default model config. */
  def globalContextWindow: Int =
    serviceConfig match
      case None => Defaults.ContextWindow
      case Some(cfg) =>
        val (providerId, modelId) = Config.parseModelRef(cfg.llm.model.default)
        val provider = cfg.llm.providers
          .getOrElse(providerId, throw new RuntimeException(s"Unknown provider: $providerId"))
        provider.models.find(_.id == modelId).map(_.contextWindow).getOrElse(Defaults.ContextWindow)

  /** Resolve maxTokens from nebflow.json default model config. */
  def globalMaxTokens: Int =
    serviceConfig match
      case None => Defaults.MaxTokens
      case Some(cfg) =>
        val (providerId, modelId) = Config.parseModelRef(cfg.llm.model.default)
        val provider = cfg.llm.providers
          .getOrElse(providerId, throw new RuntimeException(s"Unknown provider: $providerId"))
        provider.models.find(_.id == modelId).map(_.maxTokens).getOrElse(Defaults.MaxTokens)

  // ============================================================
  // Filesystem cache (all agents live on disk)
  // ============================================================

  private val cache = MtimeCache.directory[String, AgentDef](
    () =>
      if !os.exists(agentsDir) then Nil
      else
        os.list(agentsDir).filter(os.isDir).toList.flatMap { dir =>
          if os.exists(dir / "agent.json") then Some(dir.last -> dir)
          else None
        }
    ,
    (name, dir) => AgentDef.parseJson(os.read(dir / "agent.json"), dir)
  )

  // ============================================================
  // Public API
  // ============================================================

  /** Seed builtin agents to filesystem if they don't exist yet. */
  def seedDefaults(): IO[Unit] = IO.blocking {
    defaults.foreach { case DefaultAgent(name, agentJson, systemMd) =>
      val dir = agentsDir / name
      os.makeDir.all(dir)
      if !os.exists(dir / "agent.json") then
        os.write.over(dir / "agent.json", agentJson)
        logger.info(s"Seeded default agent: $name")
      // Always ensure system.md exists if the default provides one.
      // This handles existing installations where agent.json exists but system.md doesn't.
      if systemMd.nonEmpty && !os.exists(dir / "system.md") then os.write.over(dir / "system.md", systemMd)
    }
  }

  private case class DefaultAgent(name: String, agentJson: String, systemMd: String)

  private val defaults = List(
    DefaultAgent(
      "Nebula",
      """{"name":"Nebula","displayName":"Nebula","description":"AI coding assistant with full tool access","tools":["*"],"mcpServers":["*"]}""",
      """You are Nebula, an AI coding assistant running inside Nebflow.

## Session Management

- If the user asks for help, direct them to `/help`.
- The Companion (Pickle) is a separate system. When the user addresses Pickle, stay out of the way — respond in one line or less for any part meant for you. Do not explain that you're not Pickle."""
    ),
    DefaultAgent(
      "MemoryAgent",
      """{"name":"MemoryAgent","description":"Internal memory management agent","tools":["Read","Write","Edit","Glob","Grep"]}""",
      MemoryAgentPrompts.systemPrompt
    )
  )

  /** Load all agent definitions from filesystem. */
  def loadAll(): IO[Map[String, AgentDef]] = cache.loadAll

  /** Get a single agent by name. */
  def get(name: String): IO[Option[AgentDef]] =
    loadAll().map(_.get(name))

  /** Clear cache — next loadAll() will re-read agents from disk. */
  def refresh(): IO[Unit] = cache.invalidate

end AgentLibrary

object AgentLibrary:
  def defaultDir: os.Path = PathUtil.dataRoot / "agents"
end AgentLibrary
