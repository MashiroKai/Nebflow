package nebflow.agent

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import nebflow.core.NebflowLogger
import nebflow.llm.{Config, NebflowServiceConfig}
import nebflow.shared.{Defaults, MtimeCache}

/**
 * Loads agent definitions from two sources:
 *
 *  1. Builtin agents — classpath:/agents/<name>/  (src/main/resources/agents/)
 *  2. External agents — ~/.nebflow/agents/<name>/  (user-created)
 *
 * External agents with the same name are ignored (builtins take priority).
 * contextWindow / maxTokens are NOT in agent.json — resolved at runtime from nebflow.json.
 *
 * Uses mtime-based caching: external agent directories are cached and only re-read
 * when their modification time changes. Builtin agents are loaded once and never re-read.
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
  // Builtin agents from classpath (immutable, loaded once)
  // ============================================================

  private val builtinAgents: Map[String, AgentDef] =
    val builtinNames = List("Nebula")
    builtinNames.flatMap { name =>
      Option(getClass.getResourceAsStream(s"/agents/$name/agent.json")).map { jsonStream =>
        val json =
          try scala.io.Source.fromInputStream(jsonStream).mkString
          finally jsonStream.close()
        val defn = io.circe.parser
          .decode[AgentDef](json)
          .getOrElse(AgentDef(name = name, description = ""))
          .copy(systemPrompt = loadClasspathSystemMd(name))
        defn.name -> defn
      }
    }.toMap

  /** Load system.md for a builtin agent from classpath. */
  private def loadClasspathSystemMd(name: String): String =
    Option(getClass.getResourceAsStream(s"/agents/$name/system.md")) match
      case Some(is) =>
        try scala.io.Source.fromInputStream(is).mkString
        finally is.close()
      case None => ""

  // ============================================================
  // External agents from filesystem (mtime-cached)
  // ============================================================

  private val externalCache = MtimeCache.directory[String, AgentDef](
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

  /** Load all agent definitions (builtins + mtime-cached externals). */
  def loadAll(): IO[Map[String, AgentDef]] =
    externalCache.loadAll.map { externals =>
      // Warn and skip external agents that shadow builtins
      val (conflicts, valid) = externals.partition { (name, _) => builtinAgents.contains(name) }
      conflicts.foreach { (name, _) =>
        logger.warn(
          s"External agent '$name' conflicts with builtin — ignored. Remove it from ${agentsDir}/$name/ to use the builtin."
        )
      }
      builtinAgents ++ valid
    }

  /** Get a single agent by name. */
  def get(name: String): IO[Option[AgentDef]] =
    loadAll().map(_.get(name))

  /** Clear cache — next loadAll() will re-read external agents from disk. */
  def refresh(): IO[Unit] = externalCache.invalidate

end AgentLibrary

object AgentLibrary:
  def defaultDir: os.Path = os.home / ".nebflow" / "agents"
end AgentLibrary
