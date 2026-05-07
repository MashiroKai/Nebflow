package nebflow.core.compact

case class CompactConfig(
  bufferTokens: Int = 13000,
  circuitBreakerMax: Int = 3,
  // Emergency truncation: max messages to keep when compaction fails repeatedly
  emergencyKeepMessages: Int = 20
)
