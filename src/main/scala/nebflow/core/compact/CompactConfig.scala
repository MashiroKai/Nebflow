package nebflow.core.compact

case class CompactConfig(
  circuitBreakerMax: Int = 3,
  // Minimum delay between compaction retries (exponential backoff base)
  compactionRetryDelayMs: Int = 30000,
  // Emergency truncation: max messages to keep when compaction fails repeatedly
  emergencyKeepMessages: Int = 20,
  // Emergency: wire up emergencyClean as circuit breaker fallback
  emergencyAutoFallback: Boolean = true,
  // Post-compact file restoration
  postCompactMaxFiles: Int = 5,
  postCompactMaxCharsPerFile: Int = 5000,
  postCompactTokenBudget: Int = 50000,
  // ---- Tail-fidelity preservation (2026-09-07 压缩摘要尾部保真) ----
  // Number of tail conversation rounds kept VERBATIM after the summary
  // (real messages, not summary text). The last user instruction rides in
  // these rounds — preservedRounds=0 was the 09-07 incident root cause
  // (182-message compaction dropped the author's final task; the summary
  // wasn't required to carry it either). Actual preserved count can be
  // lower than this: guardrails below shrink it, and FullCompact labels
  // the true value.
  preservedRounds: Int = 2,
  // Hard char budget for the preserved tail. Over budget ⇒ preserve fewer
  // rounds; a single round over budget ⇒ preserve none (death-loop guard:
  // the old hardcoded preservedRounds=0 existed because an unshrinkable
  // tail made compaction a no-op and looped. Layer B already replaces
  // oversized ToolResults with placeholders, so this is a second line).
  preservedRoundsMaxChars: Int = 60_000,
  // Per-ToolResult content cap inside the preserved tail (defense in depth
  // on top of Layer B's placeholder pass; user text is NEVER truncated).
  preservedToolResultMaxChars: Int = 8_000
):

  /**
   * Exponential backoff: delay = compactionRetryDelayMs * 2^(failures - 1)
   * Returns 0 for failures=0 (no backoff needed).
   */
  def backoffMs(failures: Int): Long =
    if failures <= 0 then 0L
    else compactionRetryDelayMs.toLong * math.pow(2, failures - 1).toLong

  /** Check whether the elapsed time (ms) satisfies the backoff for the given failure count. */
  def isBackoffSatisfied(failures: Int, elapsedMs: Long): Boolean =
    failures <= 0 || elapsedMs >= backoffMs(failures)
end CompactConfig

object CompactConfig:
  /**
   * Return hardcoded defaults — no longer reads nebflow.json.
   * The `compact` section in nebflow.json is silently ignored.
   */
  def apply(): CompactConfig = new CompactConfig()
end CompactConfig
