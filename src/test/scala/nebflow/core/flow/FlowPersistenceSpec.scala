package nebflow.core.flow

import cats.effect.IO
import io.circe.syntax.*
import io.circe.parser.decode
import munit.CatsEffectSuite
import nebflow.core.PathUtil
import os.Path

class FlowPersistenceSpec extends CatsEffectSuite:

  // ============================================================
  // FlowMembership
  // ============================================================

  test("FlowMembership: join and canCommunicate within same flow") {
    for
      _ <- FlowMembership.clear
      _ <- FlowMembership.join("agent-A", "flow-1")
      _ <- FlowMembership.join("agent-B", "flow-1")
      canTalk <- FlowMembership.canCommunicate("agent-A", "agent-B")
    yield assert(canTalk, "Agents in same flow should communicate")
  }

  test("FlowMembership: cannot communicate across different flows") {
    for
      _ <- FlowMembership.clear
      _ <- FlowMembership.join("agent-A", "flow-1")
      _ <- FlowMembership.join("agent-B", "flow-2")
      canTalk <- FlowMembership.canCommunicate("agent-A", "agent-B")
    yield assert(!canTalk, "Agents in different flows should NOT communicate")
  }

  test("FlowMembership: non-flow agents are unrestricted") {
    for
      _ <- FlowMembership.clear
      _ <- FlowMembership.join("agent-A", "flow-1")
      // agent-B is not in any flow
      canTalk1 <- FlowMembership.canCommunicate("agent-A", "agent-B")
      canTalk2 <- FlowMembership.canCommunicate("agent-B", "agent-A")
      canTalk3 <- FlowMembership.canCommunicate("agent-B", "agent-C")
    yield
      assert(canTalk1, "Flow agent → non-flow agent: allowed")
      assert(canTalk2, "Non-flow agent → flow agent: allowed")
      assert(canTalk3, "Non-flow → non-flow: allowed")
  }

  test("FlowMembership: agent in multiple flows can reach agents in any of them") {
    for
      _ <- FlowMembership.clear
      _ <- FlowMembership.join("main-agent", "flow-1")
      _ <- FlowMembership.join("main-agent", "flow-2")
      _ <- FlowMembership.join("agent-A", "flow-1")
      _ <- FlowMembership.join("agent-B", "flow-2")
      canReachA <- FlowMembership.canCommunicate("main-agent", "agent-A")
      canReachB <- FlowMembership.canCommunicate("main-agent", "agent-B")
      aToB <- FlowMembership.canCommunicate("agent-A", "agent-B")
    yield
      assert(canReachA, "Main agent in flow-1 can reach agent-A")
      assert(canReachB, "Main agent in flow-2 can reach agent-B")
      assert(!aToB, "agent-A and agent-B in different flows cannot communicate")
  }

  test("FlowMembership: leave removes from specific flow") {
    for
      _ <- FlowMembership.clear
      _ <- FlowMembership.join("agent-A", "flow-1")
      _ <- FlowMembership.join("agent-A", "flow-2")
      _ <- FlowMembership.leave("agent-A", "flow-1")
      flows <- FlowMembership.flowsOf("agent-A")
    yield assertEquals(flows, Set("flow-2"))
  }

  test("FlowMembership: leaveAll removes from all flows") {
    for
      _ <- FlowMembership.clear
      _ <- FlowMembership.join("agent-A", "flow-1")
      _ <- FlowMembership.join("agent-A", "flow-2")
      _ <- FlowMembership.leaveAll("agent-A")
      flows <- FlowMembership.flowsOf("agent-A")
    yield assert(flows.isEmpty, "Agent should have no flow memberships")
  }

  test("FlowMembership: membersOf lists all members") {
    for
      _ <- FlowMembership.clear
      _ <- FlowMembership.join("agent-A", "flow-1")
      _ <- FlowMembership.join("agent-B", "flow-1")
      _ <- FlowMembership.join("agent-C", "flow-2")
      members <- FlowMembership.membersOf("flow-1")
    yield assertEquals(members.toSet, Set("agent-A", "agent-B"))
  }

  // ============================================================
  // FlowSnapshot serialization
  // ============================================================

  test("FlowSnapshot: serialization round-trip") {
    val flowDef = FlowDef(
      name = "test-flow",
      description = "A test",
      steps = List(
        FlowStep("a", "Explorer", "do A"),
        FlowStep("b", "Nebula", "do B", dependsOn = Set("a"))
      ),
      verify = VerifyStep(agent = "Explorer", prompt = "check"),
      loop = Some(LoopDef(FlowStep("fix", "Nebula", "fix it"), 3)),
      maxConcurrency = 3
    )
    val snapshot = FlowSnapshot(
      flowDef = flowDef,
      flowId = "flow-test-123",
      phase = "Working",
      stepStatus = Map("a" -> "Done", "b" -> "Running"),
      results = Map("a" -> "output-A"),
      failedReasons = Map("b" -> "timeout"),
      retryLeft = Map("b" -> 1),
      verifyResult = None,
      iteration = 0
    )

    val json = snapshot.asJson.noSpaces
    val decoded = decode[FlowSnapshot](json)

    decoded match
      case Right(s) =>
        assertEquals(s.flowId, "flow-test-123")
        assertEquals(s.phase, "Working")
        assertEquals(s.stepStatus("a"), "Done")
        assertEquals(s.results("a"), "output-A")
        assertEquals(s.failedReasons("b"), "timeout")
        assertEquals(s.flowDef.name, "test-flow")
        assertEquals(s.flowDef.steps.length, 2)
        assertEquals(s.flowDef.loop.map(_.maxIterations), Some(3))
      case Left(err) => fail(s"Decode failed: $err")
  }

  test("FlowSnapshot: handles empty maps and None") {
    val flowDef = FlowDef(
      name = "simple",
      description = "d",
      steps = List(FlowStep("s1", "Explorer", "do")),
      verify = VerifyStep(prompt = "check")
    )
    val snapshot = FlowSnapshot(
      flowDef = flowDef,
      flowId = "flow-simple",
      phase = "Working",
      stepStatus = Map("s1" -> "Pending"),
      results = Map.empty,
      failedReasons = Map.empty,
      retryLeft = Map.empty,
      verifyResult = None,
      iteration = 0
    )

    val json = snapshot.asJson.noSpaces
    val decoded = decode[FlowSnapshot](json)

    decoded match
      case Right(s) =>
        assert(s.results.isEmpty)
        assert(s.failedReasons.isEmpty)
        assert(s.verifyResult.isEmpty)
      case Left(err) => fail(s"Decode failed: $err")
  }

  // ============================================================
  // FlowStore (with temp directory)
  // ============================================================

  // Override dataRoot to use temp directory for isolation
  private val tempRoot: Path = os.pwd / "target" / "test-flow-store"
  private val testSessionId = "test-session-001"

  // Setup: run synchronously before tests
  PathUtil.setDataRoot(tempRoot)
  if os.exists(tempRoot) then os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  test("FlowStore: save and load round-trip") {
    val flowDef = FlowDef(
      name = "persist-test",
      description = "Testing persistence",
      steps = List(FlowStep("s1", "Explorer", "explore")),
      verify = VerifyStep(prompt = "verify")
    )
    val snapshot = FlowSnapshot(
      flowDef = flowDef,
      flowId = "flow-persist-1",
      phase = "Working",
      stepStatus = Map("s1" -> "Done"),
      results = Map("s1" -> "found 3 files"),
      failedReasons = Map.empty,
      retryLeft = Map.empty,
      verifyResult = None,
      iteration = 0
    )

    for
      _ <- FlowStore.save(testSessionId, "flow-persist-1", snapshot)
      loaded <- FlowStore.load(testSessionId, "flow-persist-1")
    yield loaded match
      case Some(s) =>
        assertEquals(s.flowId, "flow-persist-1")
        assertEquals(s.phase, "Working")
        assertEquals(s.stepStatus("s1"), "Done")
        assertEquals(s.results("s1"), "found 3 files")
      case None => fail("Should have loaded the snapshot")
  }

  test("FlowStore: load returns None for non-existent flow") {
    for loaded <- FlowStore.load(testSessionId, "non-existent")
    yield assert(loaded.isEmpty, "Should return None for non-existent flow")
  }

  test("FlowStore: listFlows returns all flow IDs") {
    val snap = FlowSnapshot(
      flowDef = FlowDef("f", "d", List(FlowStep("s", "Explorer", "p")), VerifyStep(prompt = "v")),
      flowId = "dummy",
      phase = "Working",
      stepStatus = Map.empty,
      results = Map.empty,
      failedReasons = Map.empty,
      retryLeft = Map.empty,
      verifyResult = None,
      iteration = 0
    )

    for
      _ <- FlowStore.save(testSessionId, "flow-list-1", snap.copy(flowId = "flow-list-1"))
      _ <- FlowStore.save(testSessionId, "flow-list-2", snap.copy(flowId = "flow-list-2"))
      flows <- FlowStore.listFlows(testSessionId)
    yield assert(flows.contains("flow-list-1") && flows.contains("flow-list-2"), s"Should list both flows, got: $flows")
  }

  test("FlowStore: listRestorable excludes terminal flows") {
    val activeSnap = FlowSnapshot(
      flowDef = FlowDef("a", "d", List(FlowStep("s", "Explorer", "p")), VerifyStep(prompt = "v")),
      flowId = "flow-active",
      phase = "Working",
      stepStatus = Map.empty,
      results = Map.empty,
      failedReasons = Map.empty,
      retryLeft = Map.empty,
      verifyResult = None,
      iteration = 0
    )
    val completedSnap = activeSnap.copy(flowId = "flow-done", phase = "Completed")
    val failedSnap = activeSnap.copy(flowId = "flow-failed", phase = "Failed")

    for
      _ <- FlowStore.save(testSessionId, "flow-active", activeSnap)
      _ <- FlowStore.save(testSessionId, "flow-done", completedSnap)
      _ <- FlowStore.save(testSessionId, "flow-failed", failedSnap)
      restorable <- FlowStore.listRestorable(testSessionId)
    yield
      assert(restorable.contains("flow-active"), "Active flow should be restorable")
      assert(!restorable.contains("flow-done"), "Completed flow should NOT be restorable")
      assert(!restorable.contains("flow-failed"), "Failed flow should NOT be restorable")
  }

  test("FlowStore: delete removes flow") {
    val snap = FlowSnapshot(
      flowDef = FlowDef("d", "d", List(FlowStep("s", "Explorer", "p")), VerifyStep(prompt = "v")),
      flowId = "flow-del",
      phase = "Working",
      stepStatus = Map.empty,
      results = Map.empty,
      failedReasons = Map.empty,
      retryLeft = Map.empty,
      verifyResult = None,
      iteration = 0
    )

    for
      _ <- FlowStore.save(testSessionId, "flow-del", snap)
      loaded1 <- FlowStore.load(testSessionId, "flow-del")
      _ <- FlowStore.delete(testSessionId, "flow-del")
      loaded2 <- FlowStore.load(testSessionId, "flow-del")
    yield
      assert(loaded1.isDefined, "Should exist before delete")
      assert(loaded2.isEmpty, "Should be gone after delete")
  }

end FlowPersistenceSpec
