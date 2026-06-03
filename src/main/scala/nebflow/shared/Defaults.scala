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
   * Long tasks with ongoing activity will not be killed; only truly stuck streams time out.
   * Also used as frontend spinner timeout (streamTimeoutMs + 30s buffer).
   */
  val StreamTimeoutSec: Int = 600

  /**
   * Backend LLM stream inactivity timeout — if no chunk is received from the LLM provider
   * within this time, the stream is considered hung and cancelled. This is the primary
   * recovery mechanism for hung connections (e.g. after Mac sleep/wake).
   * Shorter than StreamTimeoutSec because LLM providers should always produce chunks
   * within a few seconds, even during extended thinking.
   */
  val LlmStreamInactivitySec: Int = 180

  /** Per-provider LLM request timeout (covers streaming generation). */
  val LlmTimeoutMs: Long = 600_000L

  /** HTTP readTimeout for LLM provider connections (must be >= LlmTimeoutMs). */
  val LlmReadTimeoutSec: Int = 600

  /** Bash tool max timeout in ms. */
  val BashMaxTimeoutMs: Long = 3_600_000L

  /** Curl tool max timeout in seconds. */
  val CurlMaxTimeoutSec: Int = 120

  /** WebFetch tool timeout in ms. */
  val WebFetchTimeoutMs: Int = 120_000

  /** Background job heartbeat interval in seconds. */
  val BgHeartbeatIntervalSec: Int = 30

  /** Background job health check interval in seconds — polls OS process liveness. */
  val BgHealthCheckIntervalSec: Int = 30

  /** Background job idle threshold (no output) before flagging as stuck, in seconds. */
  val BgStuckThresholdSec: Int = 600

  // ---- Tool Result Guard ----

  /**
   * Global cap on tool result size (chars). Individual tools may declare a lower
   * maxResultSizeChars, but this constant acts as a system-wide cap regardless.
   * When exceeded, the result is saved to disk and the model receives a preview
   * with the file path instead of the full content.
   */
  val DefaultMaxResultSizeChars: Int = 50_000

  /**
   * Maximum aggregate size (chars) for tool_result blocks within a single turn's
   * batch of tool results. When the total exceeds this, the largest results are
   * persisted to disk and replaced with previews until under budget.
   */
  val MaxToolResultsPerMessageChars: Int = 200_000

  /** Preview size in characters for persisted tool results. */
  val ToolResultPreviewSize: Int = 2048
end Defaults
