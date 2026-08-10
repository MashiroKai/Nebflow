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
  postCompactTokenBudget: Int = 50000
):

  /** Exponential backoff: delay = compactionRetryDelayMs * 2^(failures - 1)
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
  /** Return hardcoded defaults — no longer reads nebflow.json.
   * The `compact` section in nebflow.json is silently ignored.
   */
  def apply(): CompactConfig = new CompactConfig()
end CompactConfig
