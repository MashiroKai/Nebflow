package nebflow.core.compact

case class CompactConfig(
  bufferTokens: Int = 13000,
  circuitBreakerMax: Int = 3
)
