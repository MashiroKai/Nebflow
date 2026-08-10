package nebflow.core.compact

// ═══════════════════════════════════════════════
// CompactThreshold — hardcoded, globally unified, non-configurable
// ═══════════════════════════════════════════════

object CompactThreshold:
  /** Fixed threshold for large-context models (256k tokens). */
  private val LargeContextFixed = 256000
  /** Models with contextWindow above this use the fixed threshold. */
  private val LargeContextLine = 300000
  /** Trigger ratio for small-context models (80%). */
  private val SmallContextRatio = 0.8

  /**
   * Unified compaction trigger threshold. Hardcoded, global, role-independent.
   *
   * - contextWindow > 300,000 → fixed 256,000 tokens
   * - otherwise → contextWindow × 80%
   *
   * Design rationale:
   * 1. Large-window models (e.g. 1M tokens) at 80% would compact too late
   *    (800k trigger), degrading perceived response speed. Fixed 256k keeps
   *    the experience consistent.
   * 2. Small-window models (e.g. 32k/128k) at 80% is reasonable, leaving
   *    enough headroom after compaction.
   */
  def threshold(contextWindow: Int): Int =
    if contextWindow > LargeContextLine then LargeContextFixed
    else (contextWindow * SmallContextRatio).toInt

  /** Threshold as a fraction of contextWindow (for UI progress display). */
  def thresholdRatio(contextWindow: Int): Double =
    threshold(contextWindow).toDouble / contextWindow
end CompactThreshold
