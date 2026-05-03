package nebflow.shared

import scala.concurrent.duration.*

/**
 * Default configuration values used across the codebase.
 * Centralized here to avoid magic numbers scattered in multiple files.
 */
object Defaults:
  val ContextWindow = 128000
  val MaxTokens = 16384
  val MaxTokensCompact = 4096

  /** Overall REPL stream timeout (covers LLM generation + all tool executions). */
  val StreamTimeoutSec: Int = 900

  /** Per-provider LLM request timeout. */
  val LlmTimeoutMs: Long = 300_000L
end Defaults
