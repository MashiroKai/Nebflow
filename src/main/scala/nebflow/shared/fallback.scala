package nebflow.shared

/**
 * Stream-inactivity timeout (phase-2 watchdog: chunks received, then stalled).
 * Dedicated type so classifyError can distinguish it from a dead-provider
 * firstToken timeout / whole-stream no-progress hang — an upstream stall is
 * usually transient jitter (same-window sibling requests succeed), so it is
 * classified Transient while firstToken/no-progress remain Permanent.
 * Flow-node LLM supervision (2026-08-26 §P1): carrying lastChunkAgeMs lets
 * the agent layer retry the turn from a clean checkpoint (full re-send, no
 * seam stitching) within the existing MaxTurnLlmCalls budget.
 */
final case class StreamInactivityTimeout(lastChunkAgeMs: Long, message: String) extends RuntimeException(message)

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
  /**
   * 审计 20260903 子项②：该类失败是否参与 provider 驱逐（markDown /
   * onProviderExhausted）。false = 请求形状类失败（400 Format/重放回传类）：
   * provider 秒回 400 证明它活着（解析并拒绝了我们的请求），失败根源是
   * 客户端重放形状 vs 该 provider API 契约——只跳过本次请求换下一 provider，
   * 不驱逐全链路（消除 deepseek 7min 35 次 DOWN/UP flap 风暴：探测空历史
   * 永远成功 → 秒回 UP → 下一个 fallback 再 400）。Auth/404/配额等确证
   * 死亡保持 evict=true。默认 true（除 Format 外全部维持现行为）。
   */
  evict: Boolean = true,
  /**
   * 配额类分层（作者令 2026-09-21 19:16 腿 b）：上游错误体证明这是**计划性
   * 额度耗尽**（HTTP 403，或 429 且上游 code ∈ `Fallback.QuotaUpstreamCodes`）。
   *
   * 分界判据 = 「短窗内会不会自愈」：
   *   - `quota = true`（计划性：现读 403×1 = kimi 5h 额度闸 @16:47；429-1308×1 =
   *     zhipu 5h 使用上限 @17:54）⇒ interface 侧走**配额软回避窗**：该 candidate
   *     退出本轮候选、立即换链、不进探测集（不烧必然失败的探测），窗口到期自然
   *     回链。阻塞式等待（同 provider 退避重试 / 全灭闸 `waitForAnyUp` 等满预算）
   *     对它无意义——额度不会在一个退避窗内回来。
   *   - `quota = false`（自愈性：超时 / 连接层 / 频率类 429-1302）⇒ 现形态逐条不变
   *     （瞬时 = 软回避 [[nebflow.shared.Defaults.TimeoutAvoidWindowMs]] + 探测；
   *     频率类 = overload 退避 ≥60s + 同 provider 重试）。
   *
   * 只影响驱逐**机制**，不改 `reason` 面（零新枚举，UI / attempt 面零改）。
   */
  quota: Boolean = false
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

// 严格DAG第⑤步裁定(2026-09-26):错误类型拆至 shared 与 FallbackAttempt 团聚(行为保持;core 的 OnboardingService 引用边随消)
class FallbackExhaustedError(val attempts: List[FallbackAttempt]) extends Exception:

  override def getMessage: String =
    val summary = attempts
      .map { a =>
        s"  ${a.providerId}/${a.model}: ${a.reason.map(_.toString).getOrElse("unknown")}"
      }
      .mkString("\n")
    s"All providers failed:\n$summary"
