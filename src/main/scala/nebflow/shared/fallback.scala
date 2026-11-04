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
    EmptyStream, Unknown

  /**
   * 网络级不可达（#692 网络不可达退避等待修批，2026-10-21 作者令）：连接被拒 /
   * DNS 解析失败 / 无路由 / "Network is unreachable" 等**本机出网失败**。与
   * ConnectionReset（连接建立后被重置）分立——本类意味着请求根本没出网，换
   * provider 无意义（全链都会同样失败），正确动作 = 退避等待网络恢复，而不是
   * 终态判死。判据 = 类型化 JDK 异常（isNetworkUnreachableThrowable），非关键词。
   */
  case NetworkUnreachable
end FailoverReason

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
class FallbackExhaustedError(
  val attempts: List[FallbackAttempt],
  /**
   * visionfix (甲): did the failed request carry image content? Threaded to
   * the user-facing error so "the images could not be delivered" can be told
   * apart from a generic chain failure. Default false keeps every existing
   * construction site source-compatible. (W1 provisional shim: member
   * ported from main's FallbackExhaustedError, following this class's move
   * into nebflow.shared.)
   */
  val hadImage: Boolean = false
) extends Exception:

  override def getMessage: String =
    // 可诊断（protocol-compat 批，作者令 2026-11-01 17:49 第 3 条）：
    // 旧文案是一句话 `All providers failed:` + 每行 `provider/model: Reason`——
    // 配额耗尽（429 计划额度）与形状/格式失败（400 Format）混成同一个词，
    // 读的人分不出"等一会儿会好"还是"我们的请求形状错了"。现在每行前置一个
    // **失败族标签**，并把上游原话（截断）带出来。
    def familyOf(a: FallbackAttempt): String =
      a.reason match
        // vision 能力不匹配（flysin 收口批 2026-11-04）：机制上是 400（Format），
        // 但语义上「这条腿不收图」——不是我们的形状 bug，重试一百次也不会好。
        // 归到 format 族里，作者只能读到「provider 失败了」这种笼统面；单列
        // 一族并点名模型，才是可理解的错误条目。
        case _ if ImageCapabilityRejection.isRejection(a.message.getOrElse("")) => "image-capability"
        case Some(FailoverReason.RateLimit)       => "quota"    // 额度/频率闸：等得来
        case Some(FailoverReason.Overloaded)      => "overload"
        case Some(FailoverReason.Auth)            => "auth"     // key/权限问题：等不来
        case Some(FailoverReason.ModelNotFound)   => "model"
        case Some(FailoverReason.Format)          => "format"   // 请求/响应形状 vs 契约
        case Some(FailoverReason.Timeout)         => "timeout"
        case Some(FailoverReason.ConnectionReset) => "conn-reset"
        case Some(FailoverReason.NetworkUnreachable) => "network"
        case Some(FailoverReason.ServerError)     => "server"
        case Some(FailoverReason.EmptyStream)     => "empty"
        case Some(FailoverReason.ProviderError)   => "provider"
        case _                                    => "unknown"
    val families = attempts.map(familyOf).distinct
    val summary = attempts
      .map { a =>
        val detail = a.message.map(m => s" — ${m.replaceAll("\\s+", " ").take(300)}").getOrElse("")
        s"  [${familyOf(a)}] ${a.providerId}/${a.model}: ${a.reason.map(_.toString).getOrElse("unknown")}$detail"
      }
      .mkString("\n")
    val verdict =
      if families == List("quota") || families == List("quota", "overload") then
        "\nquota-class failure: upstream quota/rate window exhausted — waits are meaningful, the request shape is not at fault"
      else if families.contains("image-capability") then
        // 能力不匹配的判词必须说清「谁不收图」+「怎么办」——这是作者报障
        // （2026-11-03 22:23/22:36）里读不出来的那两件事。
        val models = ImageCapabilityRejection.rejectingModels(attempts)
        s"\nimage-capability failure: ${models.mkString("、")} does not accept image input " +
          "(plain-text model) — pick a vision-capable model or remove the image; retrying will not help"
      else if families.contains("format") then
        "\nformat-class failure: at least one provider rejected the request/response shape (400) — see the [wire] line for provider, model, request block sequence and response types"
      else ""
    s"All providers failed (${attempts.size} attempt(s); families: ${families.mkString(",")}):\n$summary$verdict"
end FallbackExhaustedError
