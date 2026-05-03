package nebflow.core.compact

import nebflow.shared.Defaults

case class CompactConfig(
  contextWindow: Int = Defaults.ContextWindow,
  autoCompactThreshold: Float = 0.80f,
  bufferTokens: Int = 13000,
  circuitBreakerMax: Int = 3
)

object CompactConfig:

  def forContextWindow(window: Int): CompactConfig =
    CompactConfig(contextWindow = window)
