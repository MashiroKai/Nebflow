package nebflow.agent

import munit.FunSuite
import nebflow.llm.{Fallback, FallbackExhaustedError, TurnBudgetExceeded}
import nebflow.shared.*
import nebflow.shared.FailoverReason.*
import nebflow.shared.ErrorPermanence.*

/**
 * Verifies the graceful-failure state machinery (83d7de45) plus the token
 * incident (2026-08-18) retry policy:
 *  - llmFailRetries counter: default, set, reset-on-success semantics
 *  - retry decision matches the Actor's rule: ONLY overload-class failures
 *    (429/529) are retryable (≤1 retry with ≥5s backoff); everything else —
 *    including other transient errors — fails fast because the messages are
 *    unchanged (96% cache hit) and retrying amplifies token spend.
 */
class AgentLlmFailRetrySpec extends FunSuite:

  private def mkState(llmFailRetries: Int = 0): AgentState =
    AgentState(
      messages = Nil,
      status = AgentStatus.Idle,
      depth = 1,
      sessionId = Some("test-session"),
      pendingCompaction = None,
      compactionFailures = 0,
      latestUsage = None,
      pendingAskUser = None,
      pendingPermission = None,
      turnIdx = 0
    ).withLlmFailRetries(llmFailRetries)

  test("llmFailRetries defaults to 0 and is settable"):
    val s0 = mkState()
    assertEquals(s0.llmFailRetries, 0)
    val s1 = s0.withLlmFailRetries(2)
    assertEquals(s1.llmFailRetries, 2)

  test("reset-on-success sets llmFailRetries to 0"):
    val s = mkState(2).withLlmFailRetries(0)
    assertEquals(s.llmFailRetries, 0)

  test("retry decision: all-overloaded FallbackExhaustedError is retryable"):
    val attempts = List(
      FallbackAttempt("USTC", "deepseek-v4-pro", Some(Overloaded), Some(Transient), 100, 0, "t"),
      FallbackAttempt("kimi", "k3", Some(RateLimit), Some(Transient), 200, 1, "t")
    )
    val err = new FallbackExhaustedError(attempts)
    assert(isRetryable(err), "all-overload exhaustion must be retryable (backoff may clear saturation)")

  test("retry decision: any non-overload transient attempt makes exhaustion non-retryable"):
    val attempts = List(
      FallbackAttempt("USTC", "deepseek-v4-pro", Some(ConnectionReset), Some(Transient), 100, 0, "t"),
      FallbackAttempt("kimi", "k3", Some(Overloaded), Some(Transient), 200, 1, "t")
    )
    val err = new FallbackExhaustedError(attempts)
    assert(
      !isRetryable(err),
      "mixed exhaustion (connection reset + overload) must fail fast — messages unchanged, retry amplifies spend"
    )

  test("retry decision: any Permanent attempt makes exhaustion non-retryable"):
    val attempts = List(
      FallbackAttempt("USTC", "deepseek-v4-pro", Some(Auth), Some(Permanent), 100, 0, "t"),
      FallbackAttempt("kimi", "k3", Some(Overloaded), Some(Transient), 200, 1, "t")
    )
    val err = new FallbackExhaustedError(attempts)
    assert(!isRetryable(err), "auth (permanent) must not be retried")

  test("retry decision: overload-class exceptions are retryable, all others fail fast"):
    // 429 rate-limit / 529 overloaded: retryable (backoff ≥5s then ≤1 retry).
    assert(isRetryable(new RuntimeException("overloaded")))
    assert(isRetryable(new RuntimeException("529")))
    assert(isRetryable(new RuntimeException("rate limit")))
    assert(isRetryable(new RuntimeException("429")))
    // Timeout is classified Permanent by design (0bf832c0: "skip retries,
    // go straight to next provider") — NOT retryable.
    assert(!isRetryable(new java.util.concurrent.TimeoutException("boom")))
    assert(!isRetryable(new RuntimeException("unauthorized")))
    // Connection-reset style transients are now fail-fast too (token incident):
    // messages are unchanged between retries, so retrying only amplifies spend.
    assert(!isRetryable(new RuntimeException("connection reset")))

  test("retry decision: TurnBudgetExceeded is never retryable"):
    // The budget error is classified Permanent so the llm-fail retry loop
    // cannot fire again — that would defeat the per-turn budget entirely.
    assert(!isRetryable(new TurnBudgetExceeded(42, 4)))

  /** Mirrors the Actor's inline rule (AgentActor LlmFailed branch). */
  private def isRetryable(error: Throwable): Boolean = error match
    case e: FallbackExhaustedError =>
      e.attempts.forall(a => a.reason.exists(r => r == Overloaded || r == RateLimit))
    case _: ToolPipelineError => false
    case _ =>
      val cls = Fallback.classifyError(error)
      cls.permanence == ErrorPermanence.Transient && (cls.reason == Overloaded || cls.reason == RateLimit)
end AgentLlmFailRetrySpec
