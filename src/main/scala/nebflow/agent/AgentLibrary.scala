package nebflow.agent

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import nebflow.core.NebflowLogger
import nebflow.llm.{Config, NebflowServiceConfig}
import nebflow.shared.Defaults

/**
 * Loads agent definitions from two sources:
 *
 *  1. Builtin agents — classpath:/agents/<name>/  (src/main/resources/agents/)
 *  2. External agents — ~/.nebflow/agents/<name>/  (user-created)
 *
 * External agents with the same name override builtins.
 * contextWindow / maxTokens are NOT in agent.json — resolved at runtime from nebflow.json.
 */
class AgentLibrary(
  agentsDir: os.Path,
  serviceConfig: Option[NebflowServiceConfig] = None
):
  private val logger = NebflowLogger.forName("nebflow.agent.library")

  /** In-memory cache — populated on first loadAll(), cleared by refresh(). */
  private val cache: Ref[IO, Map[String, AgentDef]] = Ref.unsafe(Map.empty)

  /** Resolve context window from nebflow.json primary model config. */
  def globalContextWindow: Int =
    serviceConfig match
      case None => Defaults.ContextWindow
      case Some(cfg) =>
        val (providerId, modelId) = Config.parseModelRef(cfg.llm.model.primary)
        val provider = cfg.llm.providers
          .getOrElse(providerId, throw new RuntimeException(s"Unknown provider: $providerId"))
        provider.models.find(_.id == modelId).map(_.contextWindow).getOrElse(Defaults.ContextWindow)

  /** Resolve maxTokens from nebflow.json primary model config. */
  def globalMaxTokens: Int =
    serviceConfig match
      case None => Defaults.MaxTokens
      case Some(cfg) =>
        val (providerId, modelId) = Config.parseModelRef(cfg.llm.model.primary)
        val provider = cfg.llm.providers
          .getOrElse(providerId, throw new RuntimeException(s"Unknown provider: $providerId"))
        provider.models.find(_.id == modelId).map(_.maxTokens).getOrElse(Defaults.MaxTokens)

  // ============================================================
  // Builtin agents from classpath
  // ============================================================

  /** List agent directories on the classpath under /agents/. */
  private def listClasspathAgents(): List[(String, String)] =
    val builtins = List("Nebula", "context-manage", "Ask")
    builtins.flatMap { name =>
      Option(getClass.getResourceAsStream(s"/agents/$name/agent.json")).map { jsonStream =>
        val json =
          try scala.io.Source.fromInputStream(jsonStream).mkString
          finally jsonStream.close()
        name -> json
      }
    }

  /** Load system.md for a builtin agent from classpath. */
  private def loadClasspathSystemMd(name: String): String =
    Option(getClass.getResourceAsStream(s"/agents/$name/system.md")) match
      case Some(is) =>
        try scala.io.Source.fromInputStream(is).mkString
        finally is.close()
      case None => ""

  // ============================================================
  // External agents from filesystem
  // ============================================================

  private def loadExternalAgents(): Map[String, AgentDef] =
    if !os.exists(agentsDir) then Map.empty
    else
      os.list(agentsDir)
        .filter(os.isDir)
        .flatMap { dir =>
          val jsonPath = dir / "agent.json"
          if os.exists(jsonPath) then
            val defn = AgentDef.parseJson(os.read(jsonPath), dir)
            Some(defn.name -> defn)
          else None
        }
        .toMap

  // ============================================================
  // Loading with cache
  // ============================================================

  /** Load all agent definitions. Uses cache if available. */
  def loadAll(): IO[Map[String, AgentDef]] =
    cache.get.flatMap { cached =>
      if cached.nonEmpty then IO.pure(cached)
      else forceReload()
    }

  /** Force rescan from classpath + filesystem and update cache. */
  def forceReload(): IO[Map[String, AgentDef]] =
    IO.blocking {
      val builtins = listClasspathAgents().map { case (name, json) =>
        val defn = io.circe.parser
          .decode[AgentDef](json)
          .getOrElse(AgentDef(name = name, description = ""))
          .copy(systemPrompt = loadClasspathSystemMd(name))
        defn.name -> defn
      }.toMap

      val externals = loadExternalAgents()

      // Warn and skip external agents that shadow builtins
      val builtinNames = builtins.keySet
      val (conflicts, valid) = externals.partition { (name, _) => builtinNames.contains(name) }
      conflicts.foreach { (name, _) =>
        logger.warn(
          s"External agent '$name' conflicts with builtin — ignored. Remove it from ${agentsDir}/$name/ to use the builtin."
        )
      }

      builtins ++ valid
    }.flatTap { defs =>
      cache.set(defs) *> IO(logger.info(s"Loaded ${defs.size} agents: ${defs.keys.mkString(", ")}"))
    }

  /** Get a single agent by name. Uses cache. */
  def get(name: String): IO[Option[AgentDef]] =
    loadAll().map(_.get(name))

  /** Clear cache — next loadAll() will rescan. Call after create/update operations. */
  def refresh(): IO[Unit] = cache.set(Map.empty)

  /** Returns frontend configs from agents that declare them. */
  def frontendConfigs(): IO[Map[String, FrontendConfig]] =
    loadAll().map(
      _.values.flatMap { defn => defn.frontend.map(defn.name -> _) }.toMap
    )

end AgentLibrary

object AgentLibrary:
  def defaultDir: os.Path = os.home / ".nebflow" / "agents"
end AgentLibrary
