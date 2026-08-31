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
end CompactionProfileSpec
