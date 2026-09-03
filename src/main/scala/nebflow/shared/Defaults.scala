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
   *
   * Flow-node supervision P3 (2026-08-26): 60 → 120s. Phase-2 stalls on thinking
   * models without SSE keep-alive can legally exceed 60s (production incident:
   * kimi k3 mid-stream stall at 60s with sibling requests healthy). Aligned with
   * the firstToken 90s thinking-margin logic; phase-1 and the 600s whole-stream
   * guard are unchanged. Override via llm.streamTimeouts.inactivitySec.
   */
  val LlmStreamInactivitySec: Int = 120

  /**
   * Whole-stream no-progress watchdog (issue #31, 2026-08-20): bounds the blind
   * window BEFORE the per-provider inactivityTimeout arms. The per-provider
   * watchdog (LlmFirstTokenTimeoutSec / LlmStreamInactivitySec) only covers a
   * provider stream once it starts producing — the intake→first-chunk
   * evaluation chain (candidates resolve / health check / adapter
   * fetch / HTTP setup / consumer-side processing) has NO coverage: a fiber
   * parked there hangs forever (incident: intake logged at 22:48:41, then 40min
   * of zero traces; Stop and hard-cancel both ineffective because the fiber was
   * suspended on a non-cancellable wait). This outer guard fails the whole
   * sendStream when NO chunk appears within this window (and likewise between
   * chunks — legal fallback silences are bounded well below it: overload
   * backoff 60s + first-token 90s per hop). Aligned with LlmTimeoutMs
   * (the non-streaming per-request timeout) as the "10min total" mental model.
   */
  val LlmStreamNoProgressTimeoutSec: Int = 600

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

  // ---- Bash 卡死防护（#26，2026-08-30 用户裁定：恢复前台直跑语义）----
  // 08-25 #391「5 分钟自动转后台」已推翻：前台命令不再自动转后台、不设命令级
  // 超时——前台一直跑到结束（或显式 timeout / 停滞检测杀）。卡死兜底 =
  // TaskStuckWatcher（agent turn 10min 零活动 → restart 杀进程树）+ 前台
  // no-progress ceiling（shell.scala，10min 零输出零 CPU 停滞杀）。下方
  // hardTimeout/stuckWindow/healthCheck 只服务显式 run_in_background 后台任务
  // （显式后台不占 turn 活动，TaskStuckWatcher 兜不到，需自身兜底——评估建议
  // #26 保留机制 B）。

  /**
   * 后台命令硬超时（ms）：运行超过此阈值（默认 30min）后进入停滞观察期——
   * 输出零增长且 CPU 增量 < 10ms/采样窗口 连续 ≥ BashStuckWindowSec 才杀
   * （双条件裁定：输出零增长且 CPU 零消耗）。CPU 忙的合法任务不杀。
   */
  val BashBackgroundHardTimeoutMs: Long = 30 * 60 * 1000L

  /** 硬超时后的停滞观察窗口（s）：停滞连续满此值 → killProcessTree + TimeoutException。 */
  val BashStuckWindowSec: Int = 120

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

  // ---- Mail queue delivery dedup (P0 投递层指纹去重) ----

  /**
   * Fingerprint dedup window for queue-delivery: the same sender + recipient
   * + content hash delivers at most once per window. Covers the restart-replay
   * root cause (MailTool restart recovery re-fires MailQueued for the disk
   * head). Content-identical mails outside the window deliver normally —
   * legitimate re-sends are never eaten. Window long enough to span a restart
   * cycle, short enough to never eat a deliberate re-send minutes later.
   */
  val MailDedupWindowMs: Long = 30 * 60 * 1000L

  /**
   * V13 (2026-09-03): replay-scoping window for the mail dedup. The dedup
   * ledger consult now happens ONLY for deliveries inside this window after
   * the recipient session's activation (AgentRecord.startedAt) — the only
   * moment the restart-recovery re-fire (MailTool activateAgent → re-fire
   * disk head) can inject a true duplicate. Outside the window every delivery
   * injects even if the fingerprint is fresh — a legitimate same-content
   * re-send (10min polling text, re-pasted instruction) is never eaten.
   * Long enough to cover any activation→re-fire→drain scheduling delay,
   * short enough that the false-suppress exposure shrinks 30min → 60s.
   */
  val MailDedupReplayWindowMs: Long = 60 * 1000L

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

  // ---- STT（语音输入，可配置转录服务，#295）----

  /** STT 默认模型（OpenAI 兼容音频转录 API 的公共模型名，非用户配置）。 */
  val SttDefaultModel: String = "glm-asr-2512"

  /** STT 默认端点（智谱开放平台音频转录；配置文件可覆盖）。 */
  val SttDefaultEndpoint: String =
    "https://open.bigmodel.cn/api/paas/v4/audio/transcriptions"

  /**
   * FreezeScheduler 扫描间隔（冻结恢复延迟上限，freeze-schedule spec ⑤）。
   * frozen agent 靠本扫描驱动重评估时间表——出冻结段后最多延迟本值恢复 dispatch。
   * setWorkSchedule 配置热更走即时 scan，不受此间隔约束。
   */
  val FreezeCheckIntervalSec: Int = 30

  /**
   * v2 冻结式错误恢复升级链（§5.1-5.2）：进入升级链后等待父决策的窗口。
   * 每级窗口相同（从升级链启动算起逐级顺延）；用户级不设超时（最终仲裁，
   * 卡片持久化等待）。与 TaskStuckWatcher 卡死阈值同量级（用户有合理决策窗口）。
   */
  val ErrorEscalateAfterMs: Long = 10 * 60 * 1000L
end Defaults

/** Bash 卡死防护配置（#26：前台直跑语义；hardTimeout/stuckWindow/healthCheck
 * 只服务显式 run_in_background 后台任务。nebflow.json 顶层键可覆盖。 */
case class BashResilienceConfig(
  hardTimeoutMs: Long = Defaults.BashBackgroundHardTimeoutMs,
  stuckWindowSec: Int = Defaults.BashStuckWindowSec,
  healthCheckIntervalSec: Int = Defaults.BgHealthCheckIntervalSec
)
