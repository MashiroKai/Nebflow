package nebflow.core.compact

import munit.FunSuite
import nebflow.shared.{Message, MessageRole}

/**
 * CompactionProfile.fromDepth lead discrimination (B5): team members and the
 * team Manager both sit at depth 1 — depth alone mapped members onto the
 * Manager profile, so they never received the Worker skill-sedimentation
 * prompts. isLead splits them.
 */
class CompactionProfileSpec extends FunSuite:

  test("depth 0 is always Root regardless of isLead"):
    assertEquals(CompactionProfile.fromDepth(0, isLead = false), CompactionProfile.Root)
    assertEquals(CompactionProfile.fromDepth(0, isLead = true), CompactionProfile.Root)

  test("depth 1 lead gets Manager"):
    assertEquals(CompactionProfile.fromDepth(1, isLead = true), CompactionProfile.Manager)

  test("depth 1 non-lead (team member) gets Worker"):
    assertEquals(CompactionProfile.fromDepth(1, isLead = false), CompactionProfile.Worker)

  test("depth 2+ is always Worker regardless of isLead"):
    assertEquals(CompactionProfile.fromDepth(2, isLead = false), CompactionProfile.Worker)
    assertEquals(CompactionProfile.fromDepth(2, isLead = true), CompactionProfile.Worker)

  test("isLead defaults to false (backward-compatible call sites)"):
    assertEquals(CompactionProfile.fromDepth(1), CompactionProfile.Worker)

  private def text(m: Message): String = m.content.swap.getOrElse("")

  test("buildCompactReminder depth 1 member uses the Worker prompt"):
    val workerMsg = text(CompactService.buildCompactReminder(1, isLead = false))
    assert(workerMsg.contains("FLOW WORKER"), s"worker prompt expected: ${workerMsg.take(80)}")
    val managerMsg = text(CompactService.buildCompactReminder(1, isLead = true))
    assert(managerMsg.contains("FLOW MANAGER"), s"manager prompt expected: ${managerMsg.take(80)}")

  test("single-stage: worker compact reminder demands full unfinished-task state"):
    // 2026-08-31 redesign — the save turn is gone; the compact summary is the
    // worker's ONLY recovery carrier, so it must explicitly forbid deferring
    // state to a memory file.
    val workerMsg = text(CompactService.buildCompactReminder(1, isLead = false))
    assert(workerMsg.contains("NO persistent memory fallback"), s"worker prompt expected: ${workerMsg.take(80)}")
    val managerMsg = text(CompactService.buildCompactReminder(1, isLead = true))
    assert(managerMsg.contains("NO persistent memory fallback"), s"manager prompt expected: ${managerMsg.take(80)}")

  // ── 2026-09-03 per-level compaction prompts (spec §4.1) ──

  test("depth 0 is Root regardless of sessionId/isLead"):
    assertEquals(CompactionProfile.fromDepth(0, isLead = false, Some("dispatcher-ab12cd34")), CompactionProfile.Root)
    assertEquals(CompactionProfile.fromDepth(0, isLead = true, Some("node-ab12cd34")), CompactionProfile.Root)
    assertEquals(CompactionProfile.fromDepth(0, isLead = false, None), CompactionProfile.Root)

  test("depth 1 + dispatcher- prefix → Dispatcher (even if name would be a legacy lead)"):
    assertEquals(CompactionProfile.fromDepth(1, isLead = true, Some("dispatcher-ab12cd34")), CompactionProfile.Dispatcher)

  test("depth 1 + node- prefix → ProjectNode"):
    assertEquals(CompactionProfile.fromDepth(1, isLead = false, Some("node-ab12cd34")), CompactionProfile.ProjectNode)

  test("legacy prefixes keep old routing"):
    assertEquals(CompactionProfile.fromDepth(1, isLead = false, Some("dag-flow1-n2-123456")), CompactionProfile.Worker)
    assertEquals(CompactionProfile.fromDepth(1, isLead = false, Some("delegate-x")), CompactionProfile.Worker)
    assertEquals(CompactionProfile.fromDepth(1, isLead = false, Some("subtask-y")), CompactionProfile.Worker)
    assertEquals(CompactionProfile.fromDepth(1, isLead = true, None), CompactionProfile.Manager)

  test("depth 2+ is always Worker regardless of sessionId prefix"):
    assertEquals(CompactionProfile.fromDepth(2, isLead = false, Some("dispatcher-ab12cd34")), CompactionProfile.Worker)
    assertEquals(CompactionProfile.fromDepth(2, isLead = true, Some("node-ab12cd34")), CompactionProfile.Worker)
    assertEquals(CompactionProfile.fromDepth(3, isLead = false, None), CompactionProfile.Worker)

  test("buildCompactReminder routes by sessionId"):
    assert(text(CompactService.buildCompactReminder(0, sessionId = Some("s1"))).contains("NEBULA"))
    assert(text(CompactService.buildCompactReminder(1, sessionId = Some("dispatcher-x"))).contains("PROJECT DISPATCHER"))
    assert(text(CompactService.buildCompactReminder(1, sessionId = Some("node-x"))).contains("NODE WORKER"))
    assert(text(CompactService.buildCompactReminder(1, isLead = true)).contains("FLOW MANAGER")) // 回归：旧 marker 保留
    assert(text(CompactService.buildCompactReminder(1)).contains("FLOW WORKER"))

  test("Nebula prompt sections"):
    val msg = text(CompactService.buildCompactReminder(0))
    for section <- Seq("Global Mission Board", "In-Flight Dispatches", "Facts, Decisions and Rulings", "Pending / Blocked Items and their Gates") do
      assert(msg.contains(section), s"nebula prompt missing section: $section")

  test("Dispatcher prompt sections"):
    val msg = text(CompactService.buildCompactReminder(1, sessionId = Some("dispatcher-x")))
    for section <- Seq("Trigger Task(s)", "Topology Changes Made This Session", "Unfinished Work", "AUTHORITATIVE") do
      assert(msg.contains(section), s"dispatcher prompt missing section: $section")

  test("Node prompt sections"):
    val msg = text(CompactService.buildCompactReminder(1, sessionId = Some("node-x")))
    for section <- Seq("Task Goal (IMMUTABLE", "Commits:", "Verification:", "Blockers and Lessons", "Remaining Steps", "Result Statement So Far") do
      assert(msg.contains(section), s"node prompt missing section: $section")

  test("all prompts keep no-memory-fallback declaration"):
    val dispatcherMsg = text(CompactService.buildCompactReminder(1, sessionId = Some("dispatcher-x")))
    assert(
      dispatcherMsg.contains("NO memory fallback") || dispatcherMsg.contains("NO persistent memory"),
      s"dispatcher prompt expected: ${dispatcherMsg.take(80)}"
    )
    val nodeMsg = text(CompactService.buildCompactReminder(1, sessionId = Some("node-x")))
    assert(
      nodeMsg.contains("NO memory fallback") || nodeMsg.contains("NO persistent memory"),
      s"node prompt expected: ${nodeMsg.take(80)}"
    )
end CompactionProfileSpec
