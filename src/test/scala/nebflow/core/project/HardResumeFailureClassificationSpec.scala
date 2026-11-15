package nebflow.core.project

import munit.FunSuite
import nebflow.core.delegate.{FreezeNotice, ProviderFault}

/** Classification and notification-guard coverage only; exactly-once delivery needs the engine integration tests. */
class HardResumeFailureClassificationSpec extends FunSuite:

  /** Keep an independent golden copy of the dispatcher-visible text. */
  private val Golden: String =
    "L3 hard-recovery resume failed (session=s-1) — the engine could not revive this node from " +
      "the on-disk transcript breakpoint (no readable transcript / resume CAS rejected); " +
      "node re-judged as failed by the L3 hard-recovery chain. Downstream keeps waiting (D5 zero-settlement): " +
      "reactivate the upstream (NodeEdit) or rewire the graph to recover."

  private def failedNode(result: String): NodeDef =
    NodeDef(
      id = "n1",
      name = "worker",
      agent = "Nebula",
      status = NodeLifecycle.Failed,
      result = Some(result),
      createdAt = 1L
    )

  test("the recovery failure text keeps its exact wording") {
    assertEquals(NodeRecovery.hardResumeFailureText("s-1"), Golden)
    assert(Golden.startsWith(ProviderFault.EngineRecoveryFailureLead), "the lead constant is the start of the text")
    List("", "node-sess-l3", "会话-503").foreach { sessionId =>
      assertEquals(NodeRecovery.hardResumeFailureText(sessionId), Golden.replace("session=s-1", s"session=$sessionId"))
    }
  }

  test("the text that mentions the upstream node is a real failure, not a provider fault") {
    val text = NodeRecovery.hardResumeFailureText("s-1")
    // Without the engine lead, the unchanged upstream guidance matches the recoverable row.
    assert(text.toLowerCase.contains("upstream"), "the text really contains the marker word that collided")
    assert(ProviderFault.isRealFailure(text), s"the engine's recovery failure must be a real failure: $text")
    assertEquals(ProviderFault.familyOf(text), Some(ProviderFault.Family.DeterministicDefect))
    assertEquals(ProviderFault.verdictOfDetail(text), Some(ProviderFault.Verdict.RealFailure))
    assertEquals(
      ProviderFault.matchRows(text).map(_.family),
      List(ProviderFault.Family.DeterministicDefect, ProviderFault.Family.Upstream5xx)
    )
    assert(!ProviderFault.isRecoverable(text))
    assert(!ProviderFault.silent(text), "a failed recovery is never silent")
    assert(!FreezeNotice.infraFault(text), "the single ruler agrees: not an infrastructure fault")
    assert(
      !DispatchNotify.suppressFailedNotifyForInfraFault(failedNode(text), silenceOn = true),
      "the failed notice of an L3 recovery failure must not be suppressed"
    )
  }

  test("controls: provider faults, including ones that say upstream, stay silent") {
    List(
      "upstream request failed: HTTP 503 service unavailable",
      "provider cmdcode/deepseek: 503 all candidates exhausted",
      "reactivate the upstream (NodeEdit) or rewire the graph to recover.",
      "insufficient_quota: monthly budget exhausted",
      "HTTP 403 invalid api key",
      "ECONNRESET",
      "HTTP 400 context_length_exceeded",
      "HTTP 429 rate limit"
    ).foreach { detail =>
      assert(ProviderFault.isRecoverable(detail), s"provider fault must remain recoverable: $detail")
      assert(!ProviderFault.isRealFailure(detail))
      assert(ProviderFault.silent(detail), s"a provider-shaped text must stay silent: $detail")
      assert(FreezeNotice.infraFault(detail))
      assert(
        DispatchNotify.suppressFailedNotifyForInfraFault(failedNode(detail), silenceOn = true),
        s"its failed notice stays suppressed: $detail"
      )
    }
  }

  test("the kill switch still restores the old behaviour for every text") {
    assert(
      !DispatchNotify.suppressFailedNotifyForInfraFault(
        failedNode("provider 503 all candidates exhausted"),
        silenceOn = false
      )
    )
    List(Golden, "HTTP 503", "insufficient_quota", "unclassified task failure").foreach { detail =>
      assert(!DispatchNotify.suppressFailedNotifyForInfraFault(failedNode(detail), silenceOn = false))
    }
  }

  test("removing only the engine lead reproduces the upstream collision") {
    val withoutLead = NodeRecovery.hardResumeFailureText("s-1").stripPrefix(ProviderFault.EngineRecoveryFailureLead)
    assertEquals(ProviderFault.familyOf(withoutLead), Some(ProviderFault.Family.Upstream5xx))
    assert(!ProviderFault.isRealFailure(withoutLead))
    assert(ProviderFault.silent(withoutLead))
    assert(FreezeNotice.infraFault(withoutLead))
    assert(DispatchNotify.suppressFailedNotifyForInfraFault(failedNode(withoutLead), silenceOn = true))
  }

  test("the engine lead wins even when the session id contains another family's marker") {
    List("provider-HTTP 503", "quota", "HTTP 403", "timeout", "HTTP 400", "HTTP 429", "model_not_found").foreach {
      sessionId =>
        val text = NodeRecovery.hardResumeFailureText(sessionId)
        assertEquals(ProviderFault.familyOf(text), Some(ProviderFault.Family.DeterministicDefect))
        assertEquals(ProviderFault.verdictOfDetail(text), Some(ProviderFault.Verdict.RealFailure))
        assert(!FreezeNotice.infraFault(text))
        assert(!DispatchNotify.suppressFailedNotifyForInfraFault(failedNode(text), silenceOn = true))
    }
  }

  test("unknown failures and invalid model configuration are not silenced") {
    List("", "unclassified task failure").foreach { detail =>
      assertEquals(ProviderFault.familyOf(detail), None)
      assert(!ProviderFault.silent(detail))
      assert(!DispatchNotify.suppressFailedNotifyForInfraFault(failedNode(detail), silenceOn = true))
    }
    assert(
      !DispatchNotify.suppressFailedNotifyForInfraFault(failedNode("").copy(result = None), silenceOn = true)
    )
    val invalidModel = "upstream HTTP 404 model_not_found"
    assertEquals(ProviderFault.familyOf(invalidModel), Some(ProviderFault.Family.ModelUnavailable))
    assertEquals(ProviderFault.verdictOfDetail(invalidModel), Some(ProviderFault.Verdict.ConfigInvalid))
    assert(!DispatchNotify.suppressFailedNotifyForInfraFault(failedNode(invalidModel), silenceOn = true))
  }

  test("the infrastructure suppression guard applies only to failed nodes") {
    List(NodeLifecycle.Pending, NodeLifecycle.Running, NodeLifecycle.Completed, NodeLifecycle.Cancelled).foreach {
      status =>
        val node = failedNode("upstream HTTP 503").copy(status = status)
        assert(!DispatchNotify.suppressFailedNotifyForInfraFault(node, silenceOn = true))
    }
  }

end HardResumeFailureClassificationSpec
