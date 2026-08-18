package nebflow.llm

import munit.FunSuite
import nebflow.shared.*

/**
 * Error classification for the all-Down recovery gate.
 *
 * AllProvidersDownTimeout means "waited one probe cycle, nothing recovered
 * yet" — the canonical transient state at the FALLBACK layer: the chain
 * keeps trying the next provider. Token incident (2026-08-18) policy: the
 * agent-level llm-fail-retry only fires for overload-class (429/529) errors
 * — for anything else the messages are unchanged (96% cache hit), so
 * re-dispatching the full context would amplify spend without healing.
 * A plain TimeoutException (stream first token) stays Permanent by design
 * (0bf832c0): switching providers beats retrying the same dead stream. The
 * two must never regress into each other.
 */
class AllProvidersDownTimeoutSpec extends FunSuite:

  test("AllProvidersDownTimeout is classified Transient so the fallback chain keeps going"):
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

  test("AgentActor llm-fail-retry does NOT fire for AllProvidersDownTimeout"):
    // Mirrors AgentActor's retryable rule (token incident): only overload-class
    // (429/529) errors get the ≥5s backoff retry; AllProvidersDownTimeout means
    // the fallback chain already waited a full probe cycle — re-sending the
    // same full context would just amplify spend, so it fails fast.
    val cls = Fallback.classifyError(new AllProvidersDownTimeout(120000L))
    val retryable = cls.permanence == ErrorPermanence.Transient && (
      cls.reason == FailoverReason.Overloaded || cls.reason == FailoverReason.RateLimit
    )
    assert(!retryable)
end AllProvidersDownTimeoutSpec
