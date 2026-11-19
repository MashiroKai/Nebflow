package nebflow.core.tools

import nebflow.actor.AgentDef
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
          // modelcfg batch (28a246724) retired the tool-level preset override
          // leg: AgentDef no longer carries `modelOverride`, so the resolved
          // config lands on the `model` field only. (W3 owns this file's
          // final keep-deleted disposition.)
          agentDef.copy(model = Some(cfg), preset = Some(name))
        }

end PresetResolver
