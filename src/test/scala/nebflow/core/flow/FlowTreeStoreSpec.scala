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

  private def daemonBranch: BranchState = BranchState(
    name = "watcher",
    address = "nebflow://local/daemon-watcher",
    branchType = BranchType.Daemon(
      agent = "Explorer",
      prompt = "Watch for changes",
      persistent = true
    ),
    phase = BranchPhase.Running,
    children = Map("child-a" -> "child-a")
  )

  private def pipelineBranch: BranchState = BranchState(
    name = "build",
    address = "nebflow://local/pipeline-build",
    branchType = BranchType.Pipeline(
      steps = List(
        PipelineStep(id = "s1", agent = Some("Explorer"), prompt = Some("explore")),
        PipelineStep(id = "s2", agent = Some("Nebula"), prompt = Some("build"), dependsOn = Set("s1"))
      ),
      verify = VerifyStep(agent = "Explorer", prompt = "check output"),
      loop = Some(LoopConfig(
        fix = PipelineStep(id = "fix", agent = Some("Nebula"), prompt = Some("fix it")),
        maxIterations = 3
      )),
      maxConcurrency = 4
    ),
    phase = BranchPhase.Running,
    stepStatus = Map("s1" -> "Done", "s2" -> "Running"),
    results = Map("s1" -> "found 3 files"),
    failedReasons = Map("s2" -> "timeout"),
    retryLeft = Map("s2" -> 1),
    verifyResult = None,
    iteration = 0
  )

  // --- tests ---

  test("FlowTreeStore: save and load round-trip") {
    val snapshot = FlowTreeSnapshot(
      sessionId = testSessionId,
      branches = Map(
        "watcher" -> daemonBranch,
        "build"   -> pipelineBranch
      )
    )

    for
      _        <- FlowTreeStore.save(snapshot)
      loaded   <- FlowTreeStore.load(testSessionId)
    yield loaded match
      case None => fail("Should have loaded the snapshot")
      case Some(s) =>
        assertEquals(s.sessionId, testSessionId)
        assertEquals(s.branches.size, 2)

        // daemon branch
        val d = s.branches("watcher")
        assertEquals(d.name, "watcher")
        assertEquals(d.address, "nebflow://local/daemon-watcher")
        assertEquals(d.phase, BranchPhase.Running)
        assertEquals(d.children("child-a"), "child-a")
        d.branchType match
          case BranchType.Daemon(agent, prompt, persistent) =>
            assertEquals(agent, "Explorer")
            assertEquals(prompt, "Watch for changes")
            assertEquals(persistent, true)
          case other => fail(s"Expected Daemon, got $other")

        // pipeline branch
        val p = s.branches("build")
        assertEquals(p.name, "build")
        assertEquals(p.stepStatus("s1"), "Done")
        assertEquals(p.stepStatus("s2"), "Running")
        assertEquals(p.results("s1"), "found 3 files")
        assertEquals(p.failedReasons("s2"), "timeout")
        assertEquals(p.retryLeft("s2"), 1)
        p.branchType match
          case BranchType.Pipeline(steps, verify, loop, maxConcurrency) =>
            assertEquals(steps.length, 2)
            assertEquals(steps(1).dependsOn, Set("s1"))
            assertEquals(verify.agent, "Explorer")
            assertEquals(loop.map(_.maxIterations), Some(3))
            assertEquals(maxConcurrency, 4)
          case other => fail(s"Expected Pipeline, got $other")
  }

  test("FlowTreeStore: load returns None for non-existent session") {
    for loaded <- FlowTreeStore.load("never-saved-session")
    yield assert(loaded.isEmpty, "Should return None for a session that was never saved")
  }

  test("FlowTreeStore: delete removes the file") {
    val snapshot = FlowTreeSnapshot(
      sessionId = testSessionId,
      branches = Map("temp" -> daemonBranch)
    )

    for
      _      <- FlowTreeStore.save(snapshot)
      file    = PathUtil.dataRoot / "sessions" / testSessionId / "flow-tree.json"
      _       = assert(os.exists(file), "File should exist after save")
      _      <- FlowTreeStore.delete(testSessionId)
      _       = assert(!os.exists(file), "File should be gone after delete")
      loaded <- FlowTreeStore.load(testSessionId)
    yield assert(loaded.isEmpty, "Load should return None after delete")
  }

  test("FlowTreeStore: save overwrites existing snapshot") {
    val snapshotA = FlowTreeSnapshot(
      sessionId = testSessionId,
      branches = Map("branch-a" -> daemonBranch)
    )
    val snapshotB = FlowTreeSnapshot(
      sessionId = testSessionId,
      branches = Map("branch-b" -> pipelineBranch)
    )

    for
      _      <- FlowTreeStore.save(snapshotA)
      _      <- FlowTreeStore.save(snapshotB)
      loaded <- FlowTreeStore.load(testSessionId)
    yield loaded match
      case None => fail("Should have loaded snapshot B")
      case Some(s) =>
        assert(!s.branches.contains("branch-a"), "branch-a from snapshot A should be gone")
        assert(s.branches.contains("branch-b"),  "branch-b from snapshot B should be present")
  }

  test("FlowTreeStore: handles empty branches map") {
    val snapshot = FlowTreeSnapshot(
      sessionId = "empty-session",
      branches = Map.empty
    )

    for
      _      <- FlowTreeStore.save(snapshot)
      loaded <- FlowTreeStore.load("empty-session")
    yield loaded match
      case None => fail("Should have loaded the empty snapshot")
      case Some(s) =>
        assertEquals(s.sessionId, "empty-session")
        assert(s.branches.isEmpty, "Branches map should be empty")
  }

end FlowTreeStoreSpec
