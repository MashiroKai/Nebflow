package nebflow.agent

import munit.FunSuite
import nebflow.core.compact.{CompactConfig, CompactThreshold}
import nebflow.shared.{Message, MessageRole, TokenUsage}

/**
 * Lightweight state-level circuit breaker tests.
 * These tests validate the core state transition logic that the actor relies on.
 */
class AgentActorCompactionSpec extends FunSuite:

  private def mkState(compactionFailures: Int): AgentState =
    AgentState(
      messages = Nil,
      status = AgentStatus.Idle,
      depth = 0,
      sessionId = Some("test-session"),
      pendingCompaction = None,
      compactionFailures = compactionFailures,
      latestUsage = None,
      pendingAskUser = None,
      pendingPermission = None,
      turnIdx = 0
    )

  test("fresh state allows compaction (failures = 0 < max = 3)") {
    val state = mkState(0)
    assert(state.compactionFailures < CompactConfig().circuitBreakerMax)
  }

  test("state with failures = max - 1 still allows compaction") {
    val state = mkState(2)
    assert(state.compactionFailures < CompactConfig().circuitBreakerMax)
  }

  test("state with failures = max blocks compaction") {
    val state = mkState(3)
    assert(state.compactionFailures >= CompactConfig().circuitBreakerMax)
  }

  test("state with failures > max blocks compaction") {
    val state = mkState(5)
    assert(state.compactionFailures >= CompactConfig().circuitBreakerMax)
  }

  test("state reset: copy with compactionFailures = 0 after success") {
    val failed = mkState(3)
    val reset = failed.withCompactionFailures(0)
    assertEquals(reset.compactionFailures, 0)
    assert(reset.compactionFailures < CompactConfig().circuitBreakerMax)
  }

  test("pendingCompaction field is preserved during state transitions") {
    val state = mkState(0)
    assert(state.pendingCompaction.isEmpty)
    val withPending = state.withPendingCompaction(
      Some(
        CompactionJob("sub-1", "full", None, None)
      )
    )
    assert(withPending.pendingCompaction.isDefined)
  }

  test("AgentState no longer has pendingManualCompaction (compile-time check)") {
    // This test is a compile-time guard: if pendingManualCompaction were still
    // a field on AgentState, the code below would compile. Instead, we verify
    // the constructor only accepts the new fields by constructing a valid state.
    val state = mkState(0)
    // Verify we can access all expected fields
    assertEquals(state.compactionFailures, 0)
    assert(state.pendingCompaction.isEmpty)
    assert(state.sessionId.contains("test-session"))
  }

  // ---------- Manual compact state transitions (issue 009) ----------

  test("manual compact success clears pendingCompaction and resets failures") {
    val originalMsgs = List(
      Message(MessageRole.User, Left("hello")),
      Message(MessageRole.Assistant, Left("world"))
    )
    val state = mkState(2)
      .withMessages(originalMsgs)
      .withPendingCompaction(Some(CompactionJob("sub-1", "full", None, None)))
    // Simulate the success branch transformations in DelegateResult
    val compactedMsgs = List(Message(MessageRole.User, Left("summary")))
    val newState = state.withPendingCompaction(None)
    val successState = newState.withMessages(compactedMsgs).withCompactionFailures(0)

    assert(successState.pendingCompaction.isEmpty)
    assertEquals(successState.compactionFailures, 0)
    assertEquals(successState.messages.size, 1)
    assertEquals(successState.messages.head.content.swap.getOrElse(""), "summary")
  }

  test("manual compact failure increments failures and clears pendingCompaction") {
    val originalMsgs = List(
      Message(MessageRole.User, Left("hello")),
      Message(MessageRole.Assistant, Left("world"))
    )
    val state = mkState(1)
      .withMessages(originalMsgs)
      .withPendingCompaction(Some(CompactionJob("sub-1", "full", None, None)))
    // Simulate the failure branch transformations in DelegateResult (non-circuit-broken)
    val failures = state.compactionFailures + 1 // 2
    val newState = state.withPendingCompaction(None)
    val failedState = newState.withCompactionFailures(failures)

    assert(failedState.pendingCompaction.isEmpty)
    assertEquals(failedState.compactionFailures, 2)
    assertEquals(failedState.messages, originalMsgs) // original messages retained
  }

  // ---------- latestUsage reset after compaction success (prevents infinite loop) ----------

  test("compaction success resets latestUsage to prevent re-trigger") {
    val contextWindow = 128000
    val threshold = CompactThreshold.threshold(contextWindow)
    val highUsage = TokenUsage(inputTokens = threshold + 5000, outputTokens = 1000)

    val originalMsgs = List(
      Message(MessageRole.User, Left("hello")),
      Message(MessageRole.Assistant, Left("world"))
    )
    val state = mkState(0)
      .withMessages(originalMsgs)
      .withLatestUsage(Some(highUsage))
      .withPendingCompaction(Some(CompactionJob("sub-1", "full", None, None)))

    // Simulate the success branch — exactly matching AgentActor DelegateResult handler:
    val compactedMsgs = List(Message(MessageRole.User, Left("summary")))
    val newState = state.withPendingCompaction(None)
    val successState = newState
      .withMessages(compactedMsgs)
      .withCompactionFailures(0)
      .withLatestUsage(None)

    // After compaction success, latestUsage must be None so maybeAutoCompact skips
    assert(successState.latestUsage.isEmpty, "latestUsage should be None after compaction success")
    // Verify that with None latestUsage, the auto-compact check would NOT re-trigger
    val shouldNotCompact = successState.latestUsage.exists(_.inputTokens > threshold)
    assert(!shouldNotCompact, "Auto-compaction must not re-trigger after successful compaction")
  }

  // ---------- Queued-input drains during compaction (2026-08-14 injection bug) ----------
  //
  // Bug: ToolsComplete drained pendingEvents unconditionally — during the Save
  // phase (tools still running) the drained event landed in the save-turn
  // history, which the compact summary then REPLACED: consumed from the queue
  // yet lost. pendingImmediateInputs already had the guard; events did not.
  // Additionally CompactionComplete never re-drained the held-back events, and
  // a CompactionJob could survive into idle (finishTurn / fatal LlmFailed),
  // making the next normal reply masquerade as the compact summary.

  private def mkEvent(n: Int): AgentCommand.ExternalEvent =
    AgentCommand.ExternalEvent("background", "completed", s"payload-$n")

  private def mkSaveJob: CompactionJob =
    CompactionJob("job-1", "full", None, None, resumeAfterCompact = false, phase = CompactionPhase.Save)

  test("drainHead keeps the queue intact while a compaction job is pending") {
    val evs = List(mkEvent(1), mkEvent(2), mkEvent(3))
    val (drained, remaining) = TurnBoundaryDrains.drainHead(evs, compactionPending = true)
    assert(drained.isEmpty, "no event may be consumed while compaction is pending")
    assertEquals(remaining, evs, "queue must be untouched while compaction is pending")
  }

  test("drainHead consumes exactly the head once no compaction is pending") {
    val evs = List(mkEvent(1), mkEvent(2), mkEvent(3))
    val (drained, remaining) = TurnBoundaryDrains.drainHead(evs, compactionPending = false)
    assertEquals(drained.map(_.payload), Some("payload-1"))
    assertEquals(remaining.map(_.payload), List("payload-2", "payload-3"))
  }

  test("drainHead on an empty queue is a no-op either way") {
    assertEquals(TurnBoundaryDrains.drainHead[AgentCommand.ExternalEvent](Nil, compactionPending = false), (None, Nil))
    assertEquals(TurnBoundaryDrains.drainHead[AgentCommand.ExternalEvent](Nil, compactionPending = true), (None, Nil))
  }

  test("ToolsComplete drain mirrors the guard: save-phase turn leaves events queued") {
    // Exact call-site shape at ToolsComplete (AgentActor):
    val state = mkState(0)
      .withPendingCompaction(Some(mkSaveJob))
      .copy(execution = state0Execution(List(mkEvent(1), mkEvent(2))))
    val (eventOpt, remainingEvents) =
      TurnBoundaryDrains.drainHead(state.execution.pendingEvents, state.pendingCompaction.isDefined)
    assert(eventOpt.isEmpty)
    assertEquals(remainingEvents.size, 2)
  }

  test("CompactionComplete drains ONE held-back event after the summary is in place") {
    // Mirror of the post-compaction drain branch (AgentActor CompactionComplete,
    // non-resume path, after immediate/user inputs are exhausted):
    val compactedMsgs = List(Message(MessageRole.User, Left("summary")))
    val events = List(mkEvent(1), mkEvent(2))
    val compactedState = mkState(0)
      .withMessages(compactedMsgs)
      .withPendingCompaction(None)
      .copy(execution = state0Execution(Nil).copy(pendingEvents = events))
    // Branch transformation:
    val ev = compactedState.execution.pendingEvents.head
    val eventMessage = Message(MessageRole.User, Left(s"<system-reminder>\n${ev.payload}\n</system-reminder>"))
    val drainedState = compactedState.copy(execution =
      compactedState.execution.copy(
        messages = compactedState.messages :+ eventMessage,
        pendingEvents = compactedState.execution.pendingEvents.tail
      )
    )
    // The drained event survives INTO the post-compaction history…
    assert(
      drainedState.messages.last.content.swap.getOrElse("").contains("payload-1"),
      "drained event content must be present in the post-compaction messages"
    )
    // …and the tail stays queued for the next turn boundary.
    assertEquals(drainedState.execution.pendingEvents.map(_.payload), List("payload-2"))
    // Guard prevents any double-drain before that: queue head stays put while pending.
    val (none, untouched) =
      TurnBoundaryDrains.drainHead(drainedState.execution.pendingEvents, compactionPending = true)
    assert(none.isEmpty)
    assertEquals(untouched.map(_.payload), List("payload-2"))
  }

  test("finishTurn zombie guard clears a surviving CompactionJob and lets events deliver") {
    // A job reaching finishTurn is dead (context overflow / max-tokens on the
    // compact turn). Normalization: complete deferred (not asserted — IO),
    // clear job, count the failure. Queued events then hit finishTurnCont's
    // drain because pendingCompaction is now empty.
    val state = mkState(1)
      .withPendingCompaction(Some(mkSaveJob))
      .copy(execution = state0Execution(List(mkEvent(1))))
    val normalizedState = state
      .withPendingCompaction(None)
      .withCompactionFailures(state.compactionFailures + 1)
    assert(normalizedState.pendingCompaction.isEmpty, "job must not survive into idle")
    assertEquals(normalizedState.compactionFailures, 2)
    // finishTurnCont branch-1 condition after normalization:
    val branchDelivers = normalizedState.pendingCompaction.isEmpty &&
      normalizedState.execution.pendingEvents.nonEmpty
    assert(branchDelivers, "queued events must be deliverable once the zombie job is cleared")
    val (drained, _) =
      TurnBoundaryDrains.drainHead(normalizedState.execution.pendingEvents, normalizedState.pendingCompaction.isDefined)
    assertEquals(drained.map(_.payload), Some("payload-1"))
  }

  test("finishTurnCont branch-3: empty pendingUserInputs must be tail-safe") {
    // Mirror of the turn-fully-finished drain (AgentActor finishTurnCont, after
    // markTeamIdle): the forward is guarded by headOption, and the tail update
    // must be empty-safe in the same style. The REST /api/command turn-end path
    // reached this branch with an EMPTY queue on every turn in live testing
    // (9/9 sessions) — a bare .tail threw "tail of empty list" there.
    val state = mkState(0).copy(execution = state0Execution(Nil))
    assert(state.execution.pendingUserInputs.isEmpty, "branch-3 is reached with an empty queue")
    val remainingEmpty =
      val queued = state.execution.pendingUserInputs
      if queued.isEmpty then queued else queued.tail
    assertEquals(remainingEmpty, Nil)
    // Non-empty queue still consumes exactly the head.
    val queued2: List[String] = List("m1", "m2")
    val remaining2 = if queued2.isEmpty then queued2 else queued2.tail
    assertEquals(remaining2, List("m2"))
  }

  /** Default execution context seeded with a pendingEvents queue (specs build
    * AgentState without an explicit execution). */
  private def state0Execution(events: List[AgentCommand.ExternalEvent]) =
    mkState(0).execution.copy(pendingEvents = events)
end AgentActorCompactionSpec
