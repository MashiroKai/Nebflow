package nebflow.agent

import nebflow.core.presets.PresetStore
import nebflow.llm.ColdStartConfig
import nebflow.shared.{AgentModelConfig, ContentBlock, Message, MessageRole}

/**
 * Cold-start routing (A, 2026-08-18): an agent idle longer than the configured
 * threshold has its first LLM request routed to a low-cost/free preset
 * (default "LowCost" → 107 free gateway) instead of its default provider.
 *
 * Rationale (cache-miss analysis, docs/Nebflow/20260818_cache-miss-analysis.md):
 * provider prompt caches expire after ~30min, so a stale agent's wake-up
 * request is a full-price cache miss (avg 68.6K input, 97% full-price).
 * Routing the wake-up to the free gateway makes that cost ~0, matching the
 * user preference "107 免费多用".
 *
 * Guards (all must pass to route):
 *  - cold-start enabled (default true; one-key disable via nebflow.json
 *    `llm.coldStart.enabled: false`)
 *  - depth == 0 — sub-agents (Delegate/SubTask workers, flow nodes) are active
 *    work with an explicit model intent, not idle wakes
 *  - category != "flow" — flow-node agents are ephemeral per-run, not stale
 *  - idle >= threshold (default 30min, aligned with measured cache TTL)
 *  - no image blocks in the messages (LowCost models may lack vision)
 *  - the agent's own chain is not already on the free gateway (preferred
 *    starts with "107/")
 *
 * Only the final preset resolution touches the file-backed PresetStore; all
 * guards are pure, so hot agents (the common case) incur zero I/O.
 */
object ColdStartRouter:

  /** Last-resort chain when the configured preset is missing/empty. */
  val BuiltinChain: AgentModelConfig =
    AgentModelConfig(Some("107/deepseek-v4-flash-ascend"), List("107/deepseek-v4-pro", "107/glm-5.2-107"))

  /**
   * Decide the wake-up model for this request.
   *
   * @return Some(chain) when this is a cold-start wake-up, None otherwise
   *         (caller keeps the agent's normal model).
   */
  def evaluate(
    config: Option[ColdStartConfig],
    agentName: String,
    depth: Int,
    category: String,
    now: Long,
    lastActivityMs: Long,
    messages: List[Message],
    currentModel: Option[AgentModelConfig],
    presetStore: PresetStore
  ): Option[AgentModelConfig] =
    val cfg = config.getOrElse(ColdStartConfig())
    if !cfg.enabled.getOrElse(ColdStartConfig.DefaultEnabled) then None
    else if depth != 0 then None
    else if category == "flow" then None
    else
      val idleMs = now - lastActivityMs
      if idleMs < cfg.idleThresholdMs.getOrElse(ColdStartConfig.DefaultIdleThresholdMs) then None
      else if hasImages(messages) then None
      else if alreadyOnFreeGateway(currentModel) then None
      else
        // Cold-start wake-up: resolve the low-cost chain (preset → builtin).
        // Uses the file store directly (self-contained; the preset-param
        // resolveExplicit helper lives on a sibling branch).
        val presetName = cfg.preset.getOrElse(ColdStartConfig.DefaultPreset)
        presetStore.load().presets.get(presetName) match
          case Some(p) if p.preferred.isDefined || p.fallbacks.nonEmpty =>
            Some(AgentModelConfig(p.preferred, p.fallbacks))
          case _ => Some(BuiltinChain)
    end if

  /** The agent's current chain already prefers the free gateway → no routing needed. */
  private def alreadyOnFreeGateway(model: Option[AgentModelConfig]): Boolean =
    model.flatMap(_.preferred).exists(_.startsWith("107/"))

  private def hasImages(messages: List[Message]): Boolean =
    messages.exists {
      case Message(_, Right(blocks), _, _) =>
        blocks.exists {
          case _: ContentBlock.Image => true
          case _ => false
        }
      case _ => false
    }

end ColdStartRouter
