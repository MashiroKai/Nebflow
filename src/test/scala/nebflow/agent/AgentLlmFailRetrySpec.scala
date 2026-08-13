package nebflow.agent

import munit.FunSuite
import nebflow.llm.{Fallback, FallbackExhaustedError}
import nebflow.shared.*
import nebflow.shared.FailoverReason.*
import nebflow.shared.ErrorPermanence.*

/**
 * Verifies the graceful-failure state machinery (83d7de45):
 *  - llmFailRetries counter: default, set, reset-on-success semantics
 *  - retry decision matches the Actor's rule: FallbackExhaustedError is
 *    retryable only when EVERY attempt was Transient/unknown
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

  test("retry decision: all-transient FallbackExhaustedError is retryable"):
    val attempts = List(
      FallbackAttempt("USTC", "deepseek-v4-pro", Some(ConnectionReset), Some(Transient), 100, 0, "t"),
      FallbackAttempt("USTC", "deepseek-v4-flash", Some(Timeout), Some(Transient), 200, 1, "t")
    )
    val err = new FallbackExhaustedError(attempts)
    assert(isRetryable(err), "all-transient exhaustion must be retryable")

  test("retry decision: any Permanent attempt makes exhaustion non-retryable"):
    val attempts = List(
      FallbackAttempt("USTC", "deepseek-v4-pro", Some(Auth), Some(Permanent), 100, 0, "t"),
      FallbackAttempt("kimi", "k3", Some(Timeout), Some(Transient), 200, 1, "t")
    )
    val err = new FallbackExhaustedError(attempts)
    assert(!isRetryable(err), "auth (permanent) must not be retried")

  test("retry decision: unknown-permanence attempts are treated as retryable"):
    val attempts = List(
      FallbackAttempt("kimi", "k3", Some(Unknown), None, 100, 0, "t")
    )
    val err = new FallbackExhaustedError(attempts)
    assert(isRetryable(err), "unknown permanence must be treated as transient")

  test("retry decision: transient exception is retryable, permanent is not"):
    // Timeout is classified Permanent by design (0bf832c0: "skip retries,
    // go straight to next provider") — NOT retryable.
    assert(!isRetryable(new java.util.concurrent.TimeoutException("boom")))
    assert(!isRetryable(new RuntimeException("unauthorized")))
    // Connection-reset style transient failures ARE retryable.
    assert(isRetryable(new RuntimeException("connection reset")))

  /** Mirrors the Actor's inline rule (AgentActor LlmFailed branch). */
  private def isRetryable(error: Throwable): Boolean = error match
    case e: FallbackExhaustedError =>
      e.attempts.forall(a => a.permanence.forall(_ == ErrorPermanence.Transient))
    case _: ToolPipelineError => false
    case _ => Fallback.classifyError(error).permanence == ErrorPermanence.Transient
end AgentLlmFailRetrySpec
