package nebflow.llm

import munit.FunSuite
import nebflow.shared.*
import nebflow.shared.FailoverReason.*
import nebflow.shared.ErrorPermanence.*

/**
 * Token incident (2026-08-18) retry-policy tests:
 *  - per-turn LLM budget error (TurnBudgetExceeded) is Permanent — the
 *    llm-fail retry loop must never fire again (it would defeat the budget)
 *  - overload-class (429/529) retry delay is always ≥5s; other transients
 *    keep the normal exponential ramp
 *  - the delay decision is pure (retryDelayMs) so it is directly testable
 *    without sleeping real 5s in the suite
 */
class FallbackRetryPolicySpec extends FunSuite:

  test("TurnBudgetExceeded is classified Permanent (llm-fail retry must not fire)"):
    val c = Fallback.classifyError(new TurnBudgetExceeded(42, 4))
    assertEquals(c.permanence, ErrorPermanence.Permanent)
    assert(c.message.exists(_.contains("turn LLM budget exceeded")), s"message: $c")

  test("overload-class errors are classified Transient (retryable at fallback layer)"):
    assertEquals(Fallback.classifyError(new RuntimeException("overloaded")).permanence, ErrorPermanence.Transient)
    assertEquals(Fallback.classifyError(new RuntimeException("rate limit")).permanence, ErrorPermanence.Transient)

  test("overload retry delay is always ≥60s (rate window), regardless of backoff ramp"):
    // gate-wedge 止损 (2026-08-20): floor raised 5s → 60s to match the standard
    // rate-limit refill window — edge-of-window retries just burn another 429.
    // Even the initial 1s backoff must be stretched to the 60s floor.
    assertEquals(Fallback.retryDelayMs(1000L, Overloaded, 0L), 60_000L)
    assertEquals(Fallback.retryDelayMs(1000L, RateLimit, 0L), 60_000L)
    // Mid-ramp backoff (2s/4s) with jitter is also floored at 60s.
    assert(Fallback.retryDelayMs(4000L, Overloaded, 1999L) >= 60_000L)
    // MaxBackoffMs (10s) < floor (60s): every overload delay is exactly the floor.
    assertEquals(Fallback.retryDelayMs(8000L, Overloaded, 0L), 60_000L)

  test("non-overload transients keep the normal exponential ramp"):
    val d = Fallback.retryDelayMs(1000L, ConnectionReset, 0L)
    assert(d < 5000L, s"connection-reset delay must stay on the ramp, got $d")
    assertEquals(d, 1000L)

  test("retry delay is capped at MaxBackoffMs for overload class when floor < MaxBackoffMs"):
    // Document the cap-then-floor order: min(backoff, MaxBackoffMs) is applied
    // first, then the overload floor dominates. With the 60s floor > 10s cap,
    // overload delays are always exactly the floor.
    assertEquals(Fallback.retryDelayMs(100000L, Overloaded, 0L), 60_000L)

  test("gate-wedge: StuckAbort is classified Fatal — no provider fallback, no markDown"):
    val e = new StuckAbort("session-x")
    val c = Fallback.classifyError(e)
    assertEquals(c.permanence, ErrorPermanence.Fatal)
    assert(c.message.exists(_.contains("session-x")), s"message: $c")

end FallbackRetryPolicySpec
