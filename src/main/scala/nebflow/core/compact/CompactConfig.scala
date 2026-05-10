package nebflow.core.compact

case class CompactConfig(
  // Absolute buffer used as fallback for small-context models
  bufferTokens: Int = 13000,
  // Proportional buffer: max of absolute or 10% of context window
  bufferRatio: Double = 0.10,
  circuitBreakerMax: Int = 3,
  // Minimum delay between compaction retries (exponential backoff base)
  compactionRetryDelayMs: Int = 30000,
  // Emergency truncation: max messages to keep when compaction fails repeatedly
  emergencyKeepMessages: Int = 20,
  // FastMicroCompact: cache TTL in minutes — only fire when cache is cold
  microCacheTtlMinutes: Int = 10,
  // FastMicroCompact: how many recent tool results to keep untouched
  microKeepRecent: Int = 5,
  // Post-compact file restoration
  postCompactMaxFiles: Int = 5,
  postCompactMaxCharsPerFile: Int = 5000,
  postCompactTokenBudget: Int = 50000
):

  /** Buffer that scales with context window (10% min, or fixed 13k for small models). */
  def bufferForWindow(contextWindow: Int): Int =
    math.max(bufferTokens, (contextWindow * bufferRatio).toInt)
end CompactConfig
