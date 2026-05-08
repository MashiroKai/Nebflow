package nebflow.core.compact

case class CompactConfig(
  bufferTokens: Int = 13000,
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
)
