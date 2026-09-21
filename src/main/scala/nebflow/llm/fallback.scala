package nebflow.llm

import cats.effect.{IO, Temporal}
import cats.syntax.all.*
import nebflow.shared.*

import scala.concurrent.duration.*
import scala.util.Random

class FallbackExhaustedError(val attempts: List[FallbackAttempt]) extends Exception:

  override def getMessage: String =
    val summary = attempts
      .map { a =>
        s"  ${a.providerId}/${a.model}: ${a.reason.map(_.toString).getOrElse("unknown")}"
      }
      .mkString("\n")
    s"All providers failed:\n$summary"

/**
 * Raised when the all-Down gate ([[ProviderHealthMonitor.waitForAnyUp]]) timed
 * out waiting for any provider to recover. Deliberately NOT a
 * `java.util.concurrent.TimeoutException`: that type is classified Permanent
 * (stream-first-token timeouts skip retries by design, 0bf832c0), which would
 * kill the agent's llm-fail retry loop exactly when providers are recovering.
 * This error means "waited, none recovered yet" — the canonical transient
 * case, so [[Fallback.classifyError]] maps it to Transient and the 3-attempt
 * llm-fail-retry backstop fires.
 */
class AllProvidersDownTimeout(val waitedMs: Long) extends RuntimeException(
  s"all providers down: none recovered within ${waitedMs}ms"
)

/**
 * Raised when a single turn has already made [[Fallback.MaxTurnLlmCalls]]
 * failed-retry re-dispatches (plan C: normal tool-loop calls do NOT count).
 * Token incident (2026-08-18): retry amplification re-sends the full
 * ~250k-token context on every retry, so an unbounded retry storm can burn
 * hundreds of millions of tokens in minutes. This error is classified
 * Permanent — the agent's llm-fail retry loop must NOT fire again (that would
 * defeat the budget). The turn fails fast with an explicit reason instead of
 * silently looping.
 */
class TurnBudgetExceeded(val turnId: Long, val calls: Int) extends RuntimeException(
  s"turn LLM budget exceeded: $calls retry calls in turn $turnId (max ${Fallback.MaxTurnLlmCalls}) — failing fast to stop retry amplification"
)

case class FallbackResult[T](
  data: T,
  attempts: List[FallbackAttempt],
  usedCandidate: ModelCandidate
)

object Fallback:
  private val JitterMinMs = 1000
  private val JitterMaxMs = 3000
  private val DefaultTimeoutMs = Defaults.LlmTimeoutMs
  val MaxRetries: Int = 1
  val InitialBackoffMs: Long = 1000L
  val MaxBackoffMs: Long = 10000L

  /**
   * Per-turn LLM RETRY budget (2026-08-18 token incident, plan C): a single
   * turn may make at most this many failed-retry re-dispatches (incremented
   * only in the AgentActor LlmFailed retry branch). Normal tool-loop calls do
   * NOT count — tool-intensive agents (read → edit → compile → ...) are free
   * to call the LLM as often as their loop needs. Exceeding it raises
   * [[TurnBudgetExceeded]] (Permanent → no further retry): same-turn failures
   * still fail fast after ≤4 retry re-dispatches, so retry-amplification
   * protection (full ~250k-context re-sends) is preserved.
   */
  val MaxTurnLlmCalls: Int = 4

  /** Overload-class failures (429 rate-limit / 529 overloaded) need a long
   *  backoff before retry — they mean the provider is saturated, not dead.
   *  gate-wedge 止损 (2026-08-20): raised 5s → 60s to match the standard
   *  rate-limit window (litellm-class resolvers refill per 60s). Retrying at
   *  the window edge just burns another 429 and re-sends the full context. */
  val OverloadBackoffMinMs: Long = 60_000L

  /**
   * 配额类上游错误码白名单（现读真源 `~/.nebflow/logs/nebflow.log`，2026-09-21）：
   *
   *   - `"1308"` = zhipu「已达到 5 小时的使用上限」（HTTP 429，17:54:58，当日最后
   *     一条 zhipu 事件；此后无 `recovered (UP)`）——**额度**语义，计划性。
   *
   * 同族码 `1302`（HTTP 429 ×16，16:58–17:54，「您的账户已达到速率限制，请您控制
   * 请求频率」/ `rate_limit_error`）经现读判读为**频率类**（60s 窗口自愈）⇒
   * **不并入**本白名单：频率类的正确动作是退避等待（`OverloadBackoffMinMs`），
   * 不是换链。判据级别 = 只增「确证是额度语义」的码，禁凭形状编造。
   *
   * 403 面不靠码识别（LLM 面唯一实证形态 = kimi 5h 额度闸，错误体
   * `permission_error` + `5-hour usage limit`），见 [[isQuotaError]]。
   */
  val QuotaUpstreamCodes: Set[String] = Set("1308")

  /** 错误体是否携带某上游码：JSON 引号形（`"code":"1308"`）/ 上游 message 前缀形
    * （`[1308]`）。**不认裸数字文本**——request_id 等十六进制串可能偶然含该数字，
    * 裸匹配会凭空制造配额判定。
    */
  private def carriesUpstreamCode(body: String, code: String): Boolean =
    body.contains(s""""code":"$code"""") || body.contains(s"[$code]")

  /**
   * 配额类判据（计划性额度耗尽）：
   *   - HTTP **403**：LLM 面唯一实证形态 = 5h 额度闸（kimi 2026-09-21 16:47）；
   *   - HTTP **429** 且错误体带 [[QuotaUpstreamCodes]] 的码（zhipu 17:54 code 1308）；
   *   - 字符串路径（无结构化状态码）：只认码，不认裸数字。
   *
   * 判据来源 = 作者令 2026-09-21 19:16 腿 b（「配额类 403 / 429 且上游 code 1308」）
   * + providerwipe-arch 定谳 §2.1 的原始错误体。码集合的边界见 [[QuotaUpstreamCodes]]。
   */
  def isQuotaError(error: Throwable, body: String): Boolean =
    error match
      case e: sttp.client4.HttpError[?] =>
        val c = e.statusCode.code
        c == 403 || (c == 429 && QuotaUpstreamCodes.exists(code => carriesUpstreamCode(body, code)))
      case _ => QuotaUpstreamCodes.exists(code => carriesUpstreamCode(body, code))

  def classifyError(error: Throwable): ErrorClassification =
    val body = Option(error.getMessage).getOrElse("")
    val classified = classifyBase(error)
    // 配额类分层（令 2026-09-21 19:16 腿 b）：计划性额度耗尽 ⇒ **不可自愈**
    // （Permanent ⇒ 不做同 provider 的退避重试）+ 打配额标志（interface 侧据此走
    // 配额软回避窗，而非 markDown / 阻塞等待）。Fatal（上下文溢出 / StuckAbort /
    // RecoverableAbort / turn 预算）永不被改写——那些是多 provider 共性或有意中止。
    if classified.permanence != ErrorPermanence.Fatal && isQuotaError(error, body) then
      classified.copy(permanence = ErrorPermanence.Permanent, quota = true)
    else classified

  private def classifyBase(error: Throwable): ErrorClassification =
    // Check for structured sttp4 HttpError first
    error match
      case e: sttp.client4.HttpError[?] =>
        val c = e.statusCode.code
        val reason = c match
          case 401 | 403 => FailoverReason.Auth
          case 404 => FailoverReason.ModelNotFound
          case 429 => FailoverReason.RateLimit
          case 500 | 502 | 503 => FailoverReason.ServerError
          case 529 => FailoverReason.Overloaded
          case 400 => FailoverReason.Format
          case _ => FailoverReason.ProviderError
        // Context overflow affects all providers — abort immediately.
        // Other 400 errors are permanent for this provider but may not affect others.
        // 审计 20260903 子项②：400 Format 类 = 请求形状 vs 契约问题，provider
        // 本身健康（秒回 400 = 活着）——不参与驱逐（evict=false），只跳本次请求。
        // 上下文溢出仍 Fatal（影响所有 provider，保持现行为）。
        val msgLower = Option(e.getMessage).map(_.toLowerCase).getOrElse("")
        val isContextOverflow = msgLower.contains("context_length_exceeded")
          || msgLower.contains("maximum context length")
          || msgLower.contains("reduce the length of the messages")
        val permanence = c match
          case 400 if isContextOverflow => ErrorPermanence.Fatal
          case 401 | 403 | 404 | 400 => ErrorPermanence.Permanent
          case _ => ErrorPermanence.Transient
        val evict = !(c == 400 && !isContextOverflow)
        ErrorClassification(reason, permanence, Some(c), Some(error.getMessage), evict)
      case e: AllProvidersDownTimeout =>
        ErrorClassification(FailoverReason.Timeout, ErrorPermanence.Transient, message = Some(e.getMessage))
      case e: TurnBudgetExceeded =>
        // Turn LLM budget exhausted — the llm-fail retry loop must NOT fire
        // again (it would re-send the same full context and defeat the budget).
        ErrorClassification(FailoverReason.Unknown, ErrorPermanence.Permanent, message = Some(e.getMessage))
      case e: StuckAbort =>
        // gate-wedge P1-1: the WATCHER killed this request on purpose (agent
        // unresponsive to Stop). The provider is innocent — no markDown, no
        // same-provider retry, no next-provider fallback (that would re-send
        // the full context the watcher just tried to stop burning). Abort the
        // whole stream; the agent's bounded turn-retry loop takes over.
        ErrorClassification(FailoverReason.Unknown, ErrorPermanence.Fatal, message = Some(e.getMessage))
      case e: RecoverableAbort =>
        // Hard-recovery P6 (2026-09-07): the TRANSPORT of this request was
        // force-aborted (SessionKick / watcher L2) to unwedge a parked body
        // read. Stream-level semantics identical to StuckAbort — Fatal, no
        // provider fallback (partial content must never be stitched across
        // providers; the provider is innocent, no markDown/evict). The
        // DIFFERENCE lives at the agent layer: AgentActor.llmFailureRetryable
        // treats RecoverableAbort as retryable (re-send the whole turn within
        // the existing OverloadRetryMax/MaxTurnLlmCalls budget) or yields to
        // queued user input at the turn boundary — see 设计 §9 细化 1.
        ErrorClassification(
          FailoverReason.Unknown,
          ErrorPermanence.Fatal,
          message = Some(e.getMessage),
          evict = false
        )
      case e: StreamInactivityTimeout =>
        // Flow-node supervision P1 (2026-08-26): a phase-2 mid-stream stall is
        // upstream jitter, not a dead provider — Transient so the agent layer
        // can retry the turn from a clean checkpoint (within MaxTurnLlmCalls).
        // firstToken timeouts and the 600s whole-stream no-progress guard stay
        // Permanent (typed TimeoutException below) — those mean dead provider /
        // system-level hang, the strict fail-fast path is correct for them.
        ErrorClassification(FailoverReason.Timeout, ErrorPermanence.Transient, message = Some(e.getMessage))
      case _: java.util.concurrent.TimeoutException =>
        ErrorClassification(FailoverReason.Timeout, ErrorPermanence.Permanent, message = Some("timeout"))
      case _ =>
        val msg = Option(error.getMessage).map(_.toLowerCase).getOrElse("")
        if msg.contains("connection reset") || msg.contains("econnreset") || msg.contains("econnrefused") || msg
            .contains(
              "epipe"
            ) || msg.contains("broken pipe") || msg.contains("chunked") || msg.contains("invalid chunk") || msg
            .contains("transfer encoding") || msg.contains("reading_length")
        then
          ErrorClassification(
            FailoverReason.ConnectionReset,
            ErrorPermanence.Transient,
            message = Some(error.getMessage)
          )
        else if msg.contains("timeout") || msg.contains("timed out") then
          ErrorClassification(FailoverReason.Timeout, ErrorPermanence.Permanent, message = Some(error.getMessage))
        else if msg.contains("auth") || msg.contains("unauthorized") || msg.contains("403") || msg.contains("401") then
          ErrorClassification(FailoverReason.Auth, ErrorPermanence.Permanent, message = Some(error.getMessage))
        else if msg.contains("rate limit") || msg.contains("429") then
          ErrorClassification(FailoverReason.RateLimit, ErrorPermanence.Transient, message = Some(error.getMessage))
        else if msg.contains("overloaded") || msg.contains("529") then
          ErrorClassification(FailoverReason.Overloaded, ErrorPermanence.Transient, message = Some(error.getMessage))
        else if msg.contains("server error") || msg.contains("500") || msg.contains("502") || msg.contains("503") then
          ErrorClassification(FailoverReason.ServerError, ErrorPermanence.Transient, message = Some(error.getMessage))
        else if msg.contains("model not found") || msg.contains("404") then
          ErrorClassification(FailoverReason.ModelNotFound, ErrorPermanence.Permanent, message = Some(error.getMessage))
        else if msg.contains("invalid request") || msg.contains("bad request") || msg.contains("400") then
          // 同上（子项②）：stringly 400 形状同样不驱逐（与非流式 adapter 结构化
          // HttpError 之外的残余路径保持一致语义）。
          ErrorClassification(
            FailoverReason.Format,
            ErrorPermanence.Permanent,
            message = Some(error.getMessage),
            evict = false
          )
        else if msg.contains("empty response") || msg.contains("no content") then
          ErrorClassification(FailoverReason.EmptyStream, ErrorPermanence.Permanent, message = Some(error.getMessage))
        else ErrorClassification(FailoverReason.Unknown, ErrorPermanence.Transient, message = Some(error.getMessage))
        end if

  end classifyBase

  private def withTimeoutIO[A](ioa: IO[A], ms: Long): IO[A] =
    ioa.timeout(ms.millis)

  def sleepWithJitter(minMs: Int, maxMs: Int): IO[Unit] =
    val delay = minMs + java.util.concurrent.ThreadLocalRandom.current().nextInt(maxMs - minMs)
    IO.sleep(delay.millis)

  /**
   * Pure retry-delay decision (extracted for testability, 2026-08-18 token
   * incident): overload-class failures (429/529) always wait ≥
   * [[OverloadBackoffMinMs]] — the provider is saturated, a long backoff gives
   * it a chance to recover instead of hammering it. Other transients keep the
   * exponential ramp. `jitterMs` is caller-provided randomness (0..<2000).
   */
  def retryDelayMs(backoffMs: Long, reason: FailoverReason, jitterMs: Long): Long =
    val delay = math.min(backoffMs + jitterMs, MaxBackoffMs)
    val isOverload = reason == FailoverReason.Overloaded || reason == FailoverReason.RateLimit
    if isOverload then math.max(delay, OverloadBackoffMinMs) else delay

  def tryProviderWithFallback[T](
    candidates: List[ModelCandidate],
    action: ModelCandidate => IO[T],
    maxRetries: Int = MaxRetries,
    onAttempt: Option[FallbackAttempt => IO[Unit]] = None,
    onProviderExhausted: Option[ModelCandidate => IO[Unit]] = None
  ): IO[FallbackResult[T]] =

    def tryWithRetry(
      candidate: ModelCandidate,
      retriesLeft: Int,
      backoffMs: Long,
      priorFailures: List[FallbackAttempt]
    )(fallback: List[FallbackAttempt] => IO[FallbackResult[T]]): IO[FallbackResult[T]] =
      val start = System.currentTimeMillis()
      withTimeoutIO(action(candidate), DefaultTimeoutMs).attempt.flatMap {
        case Right(result) =>
          val attempt = FallbackAttempt(
            candidate.providerId,
            candidate.model,
            None,
            None,
            System.currentTimeMillis() - start,
            maxRetries - retriesLeft,
            java.time.Instant.now().toString
          )
          val successNotify =
            if priorFailures.nonEmpty then
              onAttempt.traverse_(
                _.apply(
                  attempt.copy(
                    reason = Some(FailoverReason.Unknown),
                    message = Some(s"Switched to ${candidate.providerId}/${candidate.model} successfully")
                  )
                )
              )
            else IO.unit
          successNotify *> IO.pure(FallbackResult(result, priorFailures :+ attempt, candidate))
        case Left(error) =>
          val classification = classifyError(error)
          val durationMs = System.currentTimeMillis() - start
          val failAttempt = FallbackAttempt(
            candidate.providerId,
            candidate.model,
            Some(classification.reason),
            Some(classification.permanence),
            durationMs,
            maxRetries - retriesLeft,
            java.time.Instant.now().toString,
            classification.message.orElse(Option(error.getMessage))
          )
          onAttempt.traverse_(_.apply(failAttempt))
          val allFailures = priorFailures :+ failAttempt

          val notifyExhausted = onProviderExhausted.traverse_(_.apply(candidate))

          classification.permanence match
            case ErrorPermanence.Fatal =>
              // Error affects all providers (e.g. context overflow) — abort immediately
              notifyExhausted *> IO.raiseError(new FallbackExhaustedError(allFailures))
            case ErrorPermanence.Permanent =>
              notifyExhausted *> fallback(allFailures)
            case ErrorPermanence.Transient =>
              if retriesLeft > 0 then
                val jitter = java.util.concurrent.ThreadLocalRandom.current().nextLong(0, 2000)
                // Overload-class (429/529): provider is saturated — back off
                // ≥5s before retrying, never hammer it (token incident lesson).
                val effectiveDelay = retryDelayMs(backoffMs, classification.reason, jitter)
                IO.sleep(effectiveDelay.millis) *>
                  tryWithRetry(candidate, retriesLeft - 1, backoffMs * 2, allFailures)(fallback)
              else notifyExhausted *> fallback(allFailures)
      }
    end tryWithRetry

    def loop(remaining: List[ModelCandidate], attempts: List[FallbackAttempt]): IO[FallbackResult[T]] =
      remaining match
        case Nil =>
          IO.raiseError(new FallbackExhaustedError(attempts))
        case candidate :: rest =>
          tryWithRetry(candidate, maxRetries, InitialBackoffMs, attempts)(failures => loop(rest, failures))

    loop(candidates, Nil)
  end tryProviderWithFallback
end Fallback
