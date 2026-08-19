package nebflow.core.tools

import nebflow.agent.AgentDef
import nebflow.core.presets.PresetStore

/**
 * Tool-level preset override for sub-agent spawning (Delegate/SubTask `preset`
 * param).
 *
 * Resolution: an explicit `preset` parameter on the spawn tool wins over the
 * target agent's own preset/model — the resulting AgentDef carries the
 * explicit preset's chain as its resolved model, so the child's LLM requests
 * run on the requested provider chain (e.g. a 'LowCost' preset resolving to a
 * cheaper provider chain).
 *
 * Missing/empty presets return Left with the available list so the tool can
 * surface a self-describing error the LLM can self-heal from (issue #291,
 * 2026-08-18).
 */
object PresetResolver:

  /**
   * Apply an explicit preset to an agent def.
   *
   * @return Right(def with model overridden by the preset chain) when the
   *         preset resolves; Left(message with available list) when missing or
   *         empty; Right(unchanged def) when no preset param is given.
   */
  def applyPreset(
    store: PresetStore,
    agentDef: AgentDef,
    presetName: Option[String]
  ): Either[String, AgentDef] =
    presetName.filter(_.nonEmpty) match
      case None => Right(agentDef)
      case Some(name) =>
        store.resolveExplicit(name).map { cfg =>
          // modelOverride carries the override across the per-turn def refresh
          // (ContextRefresher.loadCurrentDef reloads from disk and would
          // otherwise discard it).
          agentDef.copy(model = Some(cfg), preset = Some(name), modelOverride = Some(cfg))
        }

end PresetResolver
