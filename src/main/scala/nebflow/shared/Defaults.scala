package nebflow.shared

/** Default configuration values used across the codebase.
  * Centralized here to avoid magic numbers scattered in multiple files.
  */
object Defaults:
  val ContextWindow = 128000
  val MaxTokens = 16384
  val MaxTokensCompact = 4096
end Defaults
