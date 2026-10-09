package nebflow.core

import nebflow.shared.AgentModelConfig
import nebflow.shared.PathUtil

/**
 * Name-aware model-chain resolution policy (single source of truth for "which
 * model chain does agent X run on").
 *
 * Storage: each agent's chain lives in its own `agent.json` under the `model`
 * key as `{preferred, fallbacks}` ([[AgentModelConfig]]). Resolution:
 *
 *   1. **Own chain** (non-empty `model` key) — settable agents only.
 *   2. **Nebula primary chain** — every other agent (the executor agent and all
 *      custom, team and flow agents)
 *      follows Nebula's chain, re-read fresh on every resolution (per turn
 *      via the agent-def reload path, so /model edits take effect on running
 *      actors without any propagation step).
 *   3. **Seed chain** — when Nebula itself carries no chain: the first
 *      configured provider's first model (a legacy `llm.model` chain in
 *      nebflow.json still wins while it exists, so pre-migration installs
 *      keep their chain).
 *
 * Nebula never follows anyone (its chain IS the primary chain); an absent or
 * unreadable chain degrades to the seed chain — resolution never throws.
 * Non-role agents keep any stored `preset`/`model` references on disk for
 * audit, but the engine does not read them: they follow Nebula uniformly.
 *
 * The settable gate ([[SettableAgents]]) guards every write path: only these
 * roles accept a model-chain write (REST `PUT /api/agents/:name/model`).
 */
object SchemePolicy:

  val NebulaName = "Nebula"
  val DispatcherName = "project-dispatcher"

  /** The executor role name (single point = [[nebflow.core.entity.BuiltinAgents.ExecutorName]]).
    *
    * P1-2 downstream-role-face convergence batch (2026-10-03): `kernel` and `general`
    * were merged into the single executor agent by the builtin-merge batch
    * (`f646eeeaa`) and left `BuiltinAgents.Names`, so they are no longer ROLES — the
    * set below names the current executor instead of the two retired names. */
  val ExecutorName: String = nebflow.core.entity.BuiltinAgents.ExecutorName

  /** Roles whose model chain is user-settable (the /model write gate). */
  val SettableAgents: Set[String] = Set(NebulaName, DispatcherName, ExecutorName)

  /** Settability, with the retired names resolved through the single rename table
    * ([[nebflow.core.entity.BuiltinAgents.resolveRetired]] — the same read-side
    * fallback `EntityLoader.loadAgent` uses).
    *
    * Old-data compatibility (P0-1/P1-2, 2026-10-03): a stored `kernel` / `general`
    * sidecar keeps being honoured and a stale client that still writes the retired
    * name keeps acting on the same role — the pre-merge behaviour of an existing
    * home, so the convergence does NOT silently move those chains onto the Nebula
    * primary chain. */
  def isSettable(name: String): Boolean =
    SettableAgents.contains(name) || nebflow.core.entity.BuiltinAgents.resolveRetired(name).exists(SettableAgents.contains)

  /** `resolvedFrom` vocabulary (reported by GET /api/agents/:name/model). */
  final val OwnChainSource = "own-chain"
  final val NebulaChainSource = "nebula-chain"
  final val SeedSource = "seed"

  /** A chain is usable when at least one slot carries a ref. */
  def hasChain(c: AgentModelConfig): Boolean = c.preferred.isDefined || c.fallbacks.nonEmpty

  /** Read the raw `model` chain from an agent's agent.json, fresh per call.
    * Missing file / unparseable JSON / absent key => None (fail-safe). */
  def ownChainOf(agent: String): Option[AgentModelConfig] =
    val jsonPath = PathUtil.dataRoot / "agents" / agent / "agent.json"
    if !os.exists(jsonPath) then None
    else
      io.circe.parser.parse(os.read(jsonPath)).toOption.flatMap { json =>
        json.hcursor.downField("model").as[AgentModelConfig].toOption
      }

  /** Nebula's primary chain — the global default every follower inherits. */
  def nebulaChain(): Option[AgentModelConfig] = ownChainOf(NebulaName)

  /**
   * Seed chain: legacy `llm.model` chain first (installs that predate the
   * field's removal keep their configured chain), otherwise the first
   * provider's first model in nebflow.json field order (single-element chain —
   * fallbacks are an explicit user decision, never auto-populated).
   */
  def readSeedChain(): List[String] =
    val fromLlmModel = readGlobalChainDefault()
    if fromLlmModel.nonEmpty then fromLlmModel else readFromProviders()

  /**
   * Resolve the effective model chain for `name` given its own stored chain.
   * Returns `(chain, resolvedFrom)`. Never throws: a missing root, an empty
   * chain or an unconfigured install all degrade to the seed chain.
   */
  def resolveModel(name: String, ownModel: Option[AgentModelConfig]): (AgentModelConfig, String) =
    val own = ownModel.filter(hasChain)
    if own.isDefined && isSettable(name) then (own.get, OwnChainSource)
    else if name == NebulaName then seedChain() // Nebula has no one to follow
    else
      nebulaChain().filter(hasChain) match
        case Some(primary) => (primary, NebulaChainSource)
        case None          => seedChain()

  private def seedChain(): (AgentModelConfig, String) =
    val chain = readSeedChain()
    (AgentModelConfig(chain.headOption, chain.drop(1)), SeedSource)

  /** Global model chain from nebflow.json (`llm.model.default` + `fallbacks`). */
  private def readGlobalChainDefault(): List[String] =
    val nebflowJson = PathUtil.configJsonReadPath(PathUtil.dataRoot)
    if !os.exists(nebflowJson) then Nil
    else
      io.circe.parser
        .parse(os.read(nebflowJson))
        .toOption
        .flatMap(_.hcursor.downField("llm").downField("model").focus)
        .map { modelJson =>
          val default = modelJson.hcursor.downField("default").as[Option[String]].toOption.flatten.getOrElse("")
          val fallbacks =
            modelJson.hcursor.downField("fallbacks").as[Option[List[String]]].toOption.flatten.getOrElse(Nil)
          (default :: fallbacks).filter(_.nonEmpty)
        }
        .getOrElse(Nil)

  /** Providers-derived seed: first provider (JSON field order) with models,
    * its first model. JsonObject preserves insertion order, so "first" is the
    * first provider in the user's config file. */
  private def readFromProviders(): List[String] =
    val nebflowJson = PathUtil.configJsonReadPath(PathUtil.dataRoot)
    if !os.exists(nebflowJson) then Nil
    else
      val firstRef: Option[String] =
        io.circe.parser
          .parse(os.read(nebflowJson))
          .toOption
          .flatMap(_.hcursor.downField("llm").downField("providers").focus)
          .flatMap(_.asObject)
          .flatMap { providers =>
            providers.toIterable.view
              .flatMap { (name, pj) =>
                pj.hcursor.downField("models").focus
                  .flatMap(_.asArray)
                  .flatMap(_.headOption)
                  .flatMap(m => m.hcursor.downField("id").as[String].toOption)
                  .map(id => s"$name/$id")
              }
              .take(1)
              .toList
              .headOption
          }
      firstRef.toList

end SchemePolicy
