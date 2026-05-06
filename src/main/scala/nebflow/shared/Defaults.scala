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

  /**
   * Stream inactivity timeout — resets on every stream event (text/tool/compaction).
   *  Long tasks with ongoing activity will not be killed; only truly stuck streams time out.
   */
  val StreamTimeoutSec: Int = 600

  /** Per-provider LLM request timeout. */
  val LlmTimeoutMs: Long = 300_000L
end Defaults
