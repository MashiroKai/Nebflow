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
   * First-token timeout — if no chunk arrives from the LLM provider within this
   * time after the request is sent, the stream is considered hung (connection
   * dead, provider down). Shorter than inactivity timeout because a responsive
   * provider should always send the first token within seconds. 90s leaves room
   * for thinking models (GLM-5.2 etc.) whose reasoning phase delays the first
   * content token well past 30s.
   */
  val LlmFirstTokenTimeoutSec: Int = 90

  /**
   * Backend LLM stream inactivity timeout — if no chunk is received from the LLM provider
   * within this time, the stream is considered hung and cancelled. This is the primary
   * recovery mechanism for hung connections (e.g. after Mac sleep/wake).
   * Applies AFTER the first token. Shorter than StreamTimeoutSec because LLM providers
   * should always produce chunks within a few seconds, even during extended thinking.
   */
  val LlmStreamInactivitySec: Int = 60

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

  /**
   * Background job idle timeout — if a background command produces no output
   * for this many seconds, it is automatically cancelled and the agent is
   * notified with a timeout error. This prevents delegate/subtask agents from
   * being permanently blocked by a stuck background command (e.g. a grep that
   * hangs on a FUSE mount, an SSH prompt waiting for input, etc.).
   *
   * 5 minutes is long enough for legitimate slow commands (npm install, sbt
   * compile) that produce no output for a while, but short enough to recover
   * a stuck agent within a reasonable timeframe.
   */
  val BgIdleTimeoutSec: Int = 300

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

  // ---- Concurrency gate (P0 API 并发管理) ----

  /**
   * Default per-provider LLM concurrency limit (requests in flight at once).
   * Mainstream API free/common tiers allow >= 3 concurrent requests; 3 is a
   * safe floor that keeps 7-parallel-Delegate bursts from slamming the API.
   * `maxConcurrency: 0` in config means unlimited.
   */
  val LlmMaxConcurrencyDefault: Int = 3

  /**
   * Queue timeout for a concurrency-gated LLM request. Currently unused —
   * gate.acquire waits indefinitely (user #296 追加, 2026-08-19): 排队等待
   * 正常，不 fallback。Provider 真故障时 LLM 请求本身的超时（首 token 90s /
   * 空闲 60s）会触发 fallback，排队层不需要超时兜底。保留值供未来可选启用。
   */
  val LlmQueueTimeoutMs: Long = 120_000L

  /** RPM sliding-window width (seconds) for the per-provider rate limiter. */
  val LlmRpmWindowSec: Int = 60

  /** Persist queued LLM requests to disk so they survive a restart. */
  val LlmQueuePersistDefault: Boolean = true

  // ---- Task stuck detection (P0 阶段 3) ----

  /**
   * Default stuck threshold: an agent in Processing with no turn activity for
   * this long is considered stuck. 10min is far above the llm-fail retry chain
   * upper bound (8s×3 + provider probe 120s) — every retry action touches the
   * activity stamp, so a healthy agent in the retry chain is never misjudged.
   */
  val StuckThresholdMs: Long = 10 * 60 * 1000L

  /** TaskStuckWatcher scan interval. */
  val StuckWatcherIntervalSec: Int = 30
end Defaults
