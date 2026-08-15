package nebflow.llm

import munit.FunSuite
import nebflow.shared.*

/**
 * Error classification for the all-Down recovery gate.
 *
 * AllProvidersDownTimeout means "waited one probe cycle, nothing recovered
 * yet" — the canonical transient state, so the agent-level llm-fail retry
 * (3 attempts, backoff) must fire. A plain TimeoutException (stream first
 * token) stays Permanent by design (0bf832c0): switching providers beats
 * retrying the same dead stream. The two must never regress into each other.
 */
class AllProvidersDownTimeoutSpec extends FunSuite:

  test("AllProvidersDownTimeout is classified Transient so llm-fail-retry fires"):
    val c = Fallback.classifyError(new AllProvidersDownTimeout(120000L))
    assertEquals(c.permanence, ErrorPermanence.Transient)
    assert(c.message.exists(_.contains("all providers down")), s"message: $c")

  test("plain TimeoutException stays Permanent (stream timeout skips retry by design)"):
    val c = Fallback.classifyError(new java.util.concurrent.TimeoutException("120 seconds"))
    assertEquals(c.permanence, ErrorPermanence.Permanent)

  test("AllProvidersDownTimeout does not match the TimeoutException branch even by message"):
    // Guards the match ordering: type-based case must win before any
    // message-sniffing fallback that would see "recovered" wording.
    val e = new AllProvidersDownTimeout(5000L)
    val c = Fallback.classifyError(e)
    assertEquals(c.permanence, ErrorPermanence.Transient)

  test("retryable check used by AgentActor accepts AllProvidersDownTimeout"):
    // Mirrors AgentActor's retryable logic for non-FallbackExhausted errors.
    val retryable = Fallback.classifyError(new AllProvidersDownTimeout(120000L)).permanence ==
      ErrorPermanence.Transient
    assert(retryable)
end AllProvidersDownTimeoutSpec
