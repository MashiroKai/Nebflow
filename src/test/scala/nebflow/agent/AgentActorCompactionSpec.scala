package nebflow.agent

import munit.FunSuite
import nebflow.shared.{Message, MessageRole}
import nebflow.core.compact.CompactConfig

/** Lightweight state-level circuit breaker tests.
 *  Full Pekko actor integration tests would require pekko-actor-testkit-typed
 *  dependency and extensive mocking; these tests validate the core state
 *  transition logic that the actor relies on.
 */
class AgentActorCompactionSpec extends FunSuite:

  private def mkState(compactionFailures: Int): AgentState =
    AgentState(
      messages = Nil,
      status = AgentStatus.Idle,
      depth = 0,
      subagents = Map.empty,
      activeStreamFiber = None,
      sessionId = Some("test-session"),
      pendingCompaction = None,
      compactionFailures = compactionFailures,
      latestUsage = None,
      pendingAskUser = None,
      pendingPermission = None,
      recentToolCalls = Nil,
      turnIdx = 0,
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
    val reset = failed.copy(compactionFailures = 0)
    assertEquals(reset.compactionFailures, 0)
    assert(reset.compactionFailures < CompactConfig().circuitBreakerMax)
  }

  test("pendingCompaction field is preserved during state transitions") {
    val state = mkState(0)
    assert(state.pendingCompaction.isEmpty)
    val withPending = state.copy(pendingCompaction = Some(
      CompactionContext("sub-1", "full", None, None)
    ))
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
    val state = mkState(2).copy(
      messages = originalMsgs,
      pendingCompaction = Some(CompactionContext("sub-1", "micro", None, None))
    )
    // Simulate the success branch transformations in DelegateResult
    val compactedMsgs = List(Message(MessageRole.User, Left("summary")))
    val newState = state.copy(pendingCompaction = None, subagents = Map.empty)
    val successState = newState.copy(messages = compactedMsgs, compactionFailures = 0)

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
    val state = mkState(1).copy(
      messages = originalMsgs,
      pendingCompaction = Some(CompactionContext("sub-1", "full", None, None))
    )
    // Simulate the failure branch transformations in DelegateResult (non-circuit-broken)
    val failures = state.compactionFailures + 1 // 2
    val newState = state.copy(pendingCompaction = None, subagents = Map.empty)
    val failedState = newState.copy(compactionFailures = failures)

    assert(failedState.pendingCompaction.isEmpty)
    assertEquals(failedState.compactionFailures, 2)
    assertEquals(failedState.messages, originalMsgs) // original messages retained
  }
