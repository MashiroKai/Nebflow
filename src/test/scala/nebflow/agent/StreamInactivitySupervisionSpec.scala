package nebflow.agent

import munit.FunSuite
import nebflow.llm.{Fallback, FallbackExhaustedError}
import nebflow.shared.*
import scala.concurrent.duration.*

/** Flow-node LLM supervision P1 (2026-08-26 §5.P1, acceptance #1):
  * stream-inactivity classification split + agent-turn retryable extension.
  * Red lines asserted here: firstToken/no-progress timeouts stay Permanent;
  * non-overload non-inactivity Permanent errors stay zero-retry. */
class StreamInactivitySupervisionSpec extends FunSuite:

  private def attempt(reason: FailoverReason, perm: ErrorPermanence) =
    FallbackAttempt("p", "m", Some(reason), Some(perm), 0, 0, "t", None)

  // ── classifyError three-way split ──────────────────────────

  test("classify: stream-inactivity stall is Transient(Timeout) — upstream jitter, retryable") {
    val cls = Fallback.classifyError(StreamInactivityTimeout(61_000L, "LLM stream inactive for 120s"))
    assertEquals(cls.reason, FailoverReason.Timeout)
    assertEquals(cls.permanence, ErrorPermanence.Transient)
  }

  test("classify: typed firstToken timeout stays Permanent — dead provider, fail fast") {
    val cls = Fallback.classifyError(new java.util.concurrent.TimeoutException("LLM stream: no response within 90s"))
    assertEquals(cls.reason, FailoverReason.Timeout)
    assertEquals(cls.permanence, ErrorPermanence.Permanent)
  }

  test("classify: plain timeout message still Permanent (600s no-progress guard keeps strict path)") {
    // The whole-stream no-progress guard reuses inactivityTimeout with
    // transientPhase2=false → plain TimeoutException → Permanent.
    val cls = Fallback.classifyError(new java.util.concurrent.TimeoutException("LLM stream inactive for 600s"))
    assertEquals(cls.permanence, ErrorPermanence.Permanent)
  }

  // ── AgentActor.llmFailureRetryable matrix ──────────────────

  test("retryable: typed StreamInactivityTimeout is retryable (clean-checkpoint re-send)") {
    assert(AgentActor.llmFailureRetryable(StreamInactivityTimeout(70_000L, "stall")))
  }

  test("retryable: exhausted chain of all-inactivity attempts is retryable") {
    val e = new FallbackExhaustedError(List(attempt(FailoverReason.Timeout, ErrorPermanence.Transient)))
    assert(AgentActor.llmFailureRetryable(e))
    assert(AgentActor.llmFailureInactivityClass(e))
  }

  test("retryable: exhausted chain mixing inactivity with a Permanent attempt is NOT retryable") {
    // A permanent failure in the chain means something other than jitter happened.
    val e = new FallbackExhaustedError(List(
      attempt(FailoverReason.Timeout, ErrorPermanence.Transient),
      attempt(FailoverReason.Auth, ErrorPermanence.Permanent)
    ))
    assert(!AgentActor.llmFailureRetryable(e))
    assert(!AgentActor.llmFailureInactivityClass(e))
  }

  test("retryable: overload exhausted chain stays retryable (regression, 08-18 semantics)") {
    val e = new FallbackExhaustedError(List(attempt(FailoverReason.Overloaded, ErrorPermanence.Transient)))
    assert(AgentActor.llmFailureRetryable(e))
  }

  test("retryable red line: Auth/Format/context-overflow/firstToken-timeout Permanent errors stay zero-retry") {
    // Auth (401) — typed HttpError is exercised elsewhere; message-classified here.
    assert(!AgentActor.llmFailureRetryable(new RuntimeException("Unauthorized 401 auth failed")))
    assert(!AgentActor.llmFailureRetryable(new RuntimeException("invalid request bad request 400")))
    assert(!AgentActor.llmFailureRetryable(new RuntimeException("maximum context length exceeded")))
    assert(!AgentActor.llmFailureRetryable(new java.util.concurrent.TimeoutException("LLM stream: no response within 90s")))
    // Connection resets stay fail-fast (messages unchanged; 08-18 ruling).
    assert(!AgentActor.llmFailureRetryable(new RuntimeException("Connection reset by peer")))
    // Unknown transient non-overload stays fail-fast.
    assert(!AgentActor.llmFailureRetryable(new RuntimeException("weird upstream error")))
  }

  test("retryable: ToolPipelineError never retries (tool-chain issue, not provider condition)") {
    assert(!AgentActor.llmFailureRetryable(ToolPipelineError("tool boom")))
  }

  // ── P3: stream timeout config wiring ───────────────────────

  test("config: llm.streamTimeouts decodes and boots with Defaults on absence") {
    // Absent streamTimeouts → None (boot keeps Defaults: 90s / 120s / 600s).
    io.circe.parser.decode[nebflow.llm.NebflowServiceConfig]("""{"llm":{"providers":{}}}""") match
      case Right(cfg) => assert(cfg.llm.streamTimeouts.isEmpty)
      case Left(e)    => fail(s"config parse failed: $e")
    // Explicit override decodes each window independently.
    io.circe.parser.decode[nebflow.llm.NebflowServiceConfig](
      """{"llm":{"providers":{},"streamTimeouts":{"inactivitySec":45}}}"""
    ) match
      case Right(cfg) =>
        assertEquals(cfg.llm.streamTimeouts.flatMap(_.inactivitySec), Some(45))
        assert(cfg.llm.streamTimeouts.flatMap(_.firstTokenSec).isEmpty)
      case Left(e) => fail(s"config parse failed: $e")
  }

  test("defaults: inactivity raised 60→120s (P3); firstToken/no-progress unchanged") {
    assertEquals(Defaults.LlmStreamInactivitySec, 120)
    assertEquals(Defaults.LlmFirstTokenTimeoutSec, 90)
    assertEquals(Defaults.LlmStreamNoProgressTimeoutSec, 600)
  }

end StreamInactivitySupervisionSpec
