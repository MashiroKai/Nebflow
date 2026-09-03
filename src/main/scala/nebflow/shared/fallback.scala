package nebflow.shared

/** Stream-inactivity timeout (phase-2 watchdog: chunks received, then stalled).
  * Dedicated type so classifyError can distinguish it from a dead-provider
  * firstToken timeout / whole-stream no-progress hang — an upstream stall is
  * usually transient jitter (same-window sibling requests succeed), so it is
  * classified Transient while firstToken/no-progress remain Permanent.
  * Flow-node LLM supervision (2026-08-26 §P1): carrying lastChunkAgeMs lets
  * the agent layer retry the turn from a clean checkpoint (full re-send, no
  * seam stitching) within the existing MaxTurnLlmCalls budget. */
final case class StreamInactivityTimeout(lastChunkAgeMs: Long, message: String)
    extends RuntimeException(message)

enum FailoverReason:

  case Auth, RateLimit, Overloaded, ServerError, ModelNotFound, ProviderError, Format, ConnectionReset, Timeout,
    EmptyStream, CapabilityMismatch, Unknown

enum ErrorPermanence:
  case Transient, Permanent, Fatal

case class ErrorClassification(
  reason: FailoverReason,
  permanence: ErrorPermanence,
  statusCode: Option[Int] = None,
  message: Option[String] = None,
  /** 审计 20260903 子项②：该类失败是否参与 provider 驱逐（markDown /
    * onProviderExhausted）。false = 请求形状类失败（400 Format/重放回传类）：
    * provider 秒回 400 证明它活着（解析并拒绝了我们的请求），失败根源是
    * 客户端重放形状 vs 该 provider API 契约——只跳过本次请求换下一 provider，
    * 不驱逐全链路（消除 deepseek 7min 35 次 DOWN/UP flap 风暴：探测空历史
    * 永远成功 → 秒回 UP → 下一个 fallback 再 400）。Auth/404/配额等确证
    * 死亡保持 evict=true。默认 true（除 Format 外全部维持现行为）。
    */
  evict: Boolean = true
)

case class FallbackAttempt(
  providerId: String,
  model: String,
  reason: Option[FailoverReason],
  permanence: Option[ErrorPermanence],
  durationMs: Long,
  retriesUsed: Int,
  timestamp: String,
  message: Option[String] = None
)
