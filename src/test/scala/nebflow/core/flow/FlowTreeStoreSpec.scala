package nebflow.core.flow

import cats.effect.IO
import munit.CatsEffectSuite
import nebflow.core.PathUtil
import os.Path

class FlowTreeStoreSpec extends CatsEffectSuite:

  private val tempRoot: Path = os.pwd / "target" / "test-flow-tree-store"
  private val testSessionId = "test-session-tree-001"

  PathUtil.setDataRoot(tempRoot)
  if os.exists(tempRoot) then os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  // --- helpers ---

  private def simpleBranch: BranchState = BranchState(
    name = "watcher",
    address = "nebflow://local/watcher",
    flowName = "watch-flow",
    phase = BranchPhase.Running,
    children = Map("child-a" -> "child-a")
  )

  private def pipelineBranch: BranchState = BranchState(
    name = "build",
    address = "nebflow://local/build",
    flowName = "ci-pipeline",
    phase = BranchPhase.Running,
    stepStatus = Map("s1" -> "Done", "s2" -> "Running"),
    results = Map("s1" -> "found 3 files"),
    failedReasons = Map("s2" -> "timeout"),
    retryLeft = Map("s2" -> 1),
    verdicts = Map("verify" -> true),
    iteration = 0
  )

  // --- tests ---

  test("FlowTreeStore: save and load round-trip") {
    val snapshot = FlowTreeSnapshot(
      sessionId = testSessionId,
      branches = Map(
        "watcher" -> simpleBranch,
        "build" -> pipelineBranch
      )
    )

    for
      _ <- FlowTreeStore.save(snapshot)
      loaded <- FlowTreeStore.load(testSessionId)
    yield loaded match
      case None => fail("Should have loaded the snapshot")
      case Some(s) =>
        assertEquals(s.sessionId, testSessionId)
        assertEquals(s.branches.size, 2)

        // simple branch
        val d = s.branches("watcher")
        assertEquals(d.name, "watcher")
        assertEquals(d.address, "nebflow://local/watcher")
        assertEquals(d.flowName, "watch-flow")
        assertEquals(d.phase, BranchPhase.Running)
        assertEquals(d.children("child-a"), "child-a")

        // pipeline branch
        val p = s.branches("build")
        assertEquals(p.name, "build")
        assertEquals(p.flowName, "ci-pipeline")
        assertEquals(p.stepStatus("s1"), "Done")
        assertEquals(p.stepStatus("s2"), "Running")
        assertEquals(p.results("s1"), "found 3 files")
        assertEquals(p.failedReasons("s2"), "timeout")
        assertEquals(p.retryLeft("s2"), 1)
        assertEquals(p.verdicts("verify"), true)
    end for
  }

  test("FlowTreeStore: load returns None for non-existent session") {
    for loaded <- FlowTreeStore.load("never-saved-session")
    yield assert(loaded.isEmpty, "Should return None for a session that was never saved")
  }

  test("FlowTreeStore: delete removes the file") {
    val snapshot = FlowTreeSnapshot(
      sessionId = testSessionId,
      branches = Map("temp" -> simpleBranch)
    )

    for
      _ <- FlowTreeStore.save(snapshot)
      file = PathUtil.dataRoot / "sessions" / testSessionId / "flow-tree.json"
      _ = assert(os.exists(file), "File should exist after save")
      _ <- FlowTreeStore.delete(testSessionId)
      _ = assert(!os.exists(file), "File should be gone after delete")
      loaded <- FlowTreeStore.load(testSessionId)
    yield assert(loaded.isEmpty, "Load should return None after delete")
  }

  test("FlowTreeStore: save overwrites existing snapshot") {
    val snapshotA = FlowTreeSnapshot(
      sessionId = testSessionId,
      branches = Map("branch-a" -> simpleBranch)
    )
    val snapshotB = FlowTreeSnapshot(
      sessionId = testSessionId,
      branches = Map("branch-b" -> pipelineBranch)
    )

    for
      _ <- FlowTreeStore.save(snapshotA)
      _ <- FlowTreeStore.save(snapshotB)
      loaded <- FlowTreeStore.load(testSessionId)
    yield loaded match
      case None => fail("Should have loaded snapshot B")
      case Some(s) =>
        assert(!s.branches.contains("branch-a"), "branch-a from snapshot A should be gone")
        assert(s.branches.contains("branch-b"), "branch-b from snapshot B should be present")
  }

  test("FlowTreeStore: handles empty branches map") {
    val snapshot = FlowTreeSnapshot(
      sessionId = "empty-session",
      branches = Map.empty
    )

    for
      _ <- FlowTreeStore.save(snapshot)
      loaded <- FlowTreeStore.load("empty-session")
    yield loaded match
      case None => fail("Should have loaded the empty snapshot")
      case Some(s) =>
        assertEquals(s.sessionId, "empty-session")
        assert(s.branches.isEmpty, "Branches map should be empty")
  }

end FlowTreeStoreSpec
