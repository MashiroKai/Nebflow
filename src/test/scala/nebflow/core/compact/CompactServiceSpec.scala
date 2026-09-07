package nebflow.core.compact

import munit.FunSuite
import nebflow.shared.{Message, MessageRole}

/**
 * 2026-09-07 尾部保真 — template hard requirements.
 *
 * Incident: the compaction summary prompt never required summarizing the task
 * in flight at the tail — after a 182-message compaction (preservedRounds=0)
 * the author's LAST instruction before compaction was missing from the
 * summary and silently dropped after restore. Every profile's compact
 * reminder must now carry the TAIL FIDELITY RULE: quote the last user message
 * verbatim + its processing status, at the head of the current-work section.
 * (Engine-side backstop: FullCompact preserves the tail rounds themselves —
 * see FullCompactSpec.)
 */
class CompactServiceSpec extends FunSuite:

  private def promptOf(
    depth: Int,
    isLead: Boolean = false,
    sessionId: Option[String] = None
  ): String =
    CompactService.buildCompactReminder(depth, isLead, sessionId).content.left.getOrElse("")

  // Preamble rule shared by all five profiles.
  private def assertTailFidelityRule(prompt: String, profile: String): Unit =
    assert(prompt.contains("TAIL FIDELITY RULE"), s"$profile: preamble rule missing")
    assert(
      prompt.contains("verbatim or near-verbatim quote"),
      s"$profile: verbatim-quote requirement missing"
    )
    assert(prompt.contains("processing status"), s"$profile: processing-status requirement missing")
    assert(
      prompt.contains("Never omit or dilute the tail"),
      s"$profile: no-omission clause missing"
    )

  test("Root (Nebula) reminder carries the tail-fidelity rule + Current Work anchor") {
    val p = promptOf(depth = 0)
    assertTailFidelityRule(p, "Root")
    assert(
      p.contains("OPEN this") && p.contains("VERBATIM quote of the LAST user instruction"),
      "Root: §7 Current Work must open with the verbatim last instruction"
    )
  }

  test("Manager reminder carries the tail-fidelity rule + Current Work anchor") {
    val p = promptOf(depth = 1, isLead = true)
    assertTailFidelityRule(p, "Manager")
    assert(
      p.contains("LAST instruction/Mail you received"),
      "Manager: §6 Current Work must open with the verbatim last instruction"
    )
  }

  test("Worker reminder carries the tail-fidelity rule + Current Task anchor") {
    val p = promptOf(depth = 1, isLead = false)
    assertTailFidelityRule(p, "Worker")
    assert(
      p.contains("QUOTE IT VERBATIM"),
      "Worker: §1 Current Task must demand a verbatim quote of the most recent Mail"
    )
  }

  test("Dispatcher reminder carries the tail-fidelity rule (preamble level)") {
    val p = promptOf(depth = 1, sessionId = Some(s"${nebflow.core.project.ProjectActor.DispatcherSessionPrefix}abc12345"))
    assertTailFidelityRule(p, "Dispatcher")
    // §1 Trigger Task(s) already demanded verbatim quoting pre-fix; keep it pinned.
    assert(p.contains("quote verbatim"), "Dispatcher: §1 verbatim-quote of trigger task must stay")
  }

  test("ProjectNode reminder carries the tail-fidelity rule (preamble level)") {
    val p = promptOf(depth = 1, sessionId = Some(s"${nebflow.core.project.NodeEngine.SessionPrefix}abc12345"))
    assertTailFidelityRule(p, "ProjectNode")
    // §1 Task Goal already demanded verbatim quoting pre-fix; keep it pinned.
    assert(p.contains("quote verbatim"), "ProjectNode: §1 verbatim-quote of task goal must stay")
  }

  test("isCompactReminder recognizes every profile reminder and rejects ordinary text") {
    val reminderMsgs = List(
      CompactService.buildCompactReminder(depth = 0),
      CompactService.buildCompactReminder(depth = 1, isLead = true),
      CompactService.buildCompactReminder(depth = 1, isLead = false),
      CompactService.buildCompactReminder(depth = 1, sessionId = Some(s"${nebflow.core.project.NodeEngine.SessionPrefix}abc12345"))
    )
    reminderMsgs.zipWithIndex.foreach { (m, i) =>
      assert(CompactService.isCompactReminder(m), s"reminder $i must be recognized")
    }
    val ordinary = Message(MessageRole.User, Left("普通用户消息 <system-reminder> 别的提醒"))
    assert(!CompactService.isCompactReminder(ordinary), "ordinary user text must not match")
    val blocksMsg = Message(
      MessageRole.Assistant,
      Right(List(nebflow.shared.ContentBlock.Text("Context compaction required — 但不是 reminder 开头")))
    )
    assert(!CompactService.isCompactReminder(blocksMsg), "non-matching block text must not match")
  }

end CompactServiceSpec
