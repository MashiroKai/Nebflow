package nebflow.agent

import munit.FunSuite

/**
 * #12 劝停 reminder: AgentCore.denialMessage — the n-th user denial of a tool
 * within one turn. First denial is minimal; from the second on the message
 * carries a system-reminder (retryableHint pattern) telling the LLM to change
 * approach instead of re-asking. Timeout never increments the counter (user
 * inaction ≠ denial), which is asserted at the call site by construction.
 */
class PermissionDenialMessageSpec extends FunSuite:

  test("first denial stays minimal — no reminder, no count") {
    val m = AgentCore.denialMessage("Edit", 1)
    assertEquals(m, "Permission denied by user")
    assert(!m.contains("<system-reminder>"))
  }

  test("second denial injects the nudge reminder with the count") {
    val m = AgentCore.denialMessage("Edit", 2)
    assert(m.startsWith("Permission denied by user ('Edit' denied 2 times this turn)."))
    assert(m.contains("<system-reminder>"))
    assert(m.contains("denied this tool 2 times in this turn"))
    assert(m.contains("Change the approach"))
    assert(m.contains("AskUserQuestion"))
    assert(m.contains("report the blocker"))
    assert(m.endsWith("</system-reminder>"))
  }

  test("count keeps counting (3rd, 5th denial)") {
    assert(AgentCore.denialMessage("Bash", 3).contains("denied 3 times this turn"))
    assert(AgentCore.denialMessage("Bash", 5).contains("denied this tool 5 times in this turn"))
  }

  test("message is deterministic per (tool, n) — same denial replays identically") {
    assertEquals(AgentCore.denialMessage("Write", 2), AgentCore.denialMessage("Write", 2))
  }

  test("tool names with special characters are embedded verbatim once") {
    val m = AgentCore.denialMessage("tool'quote", 2)
    // exactly one occurrence of the tool name — no escaping surprises
    assertEquals(m.split("tool'quote").length - 1, 1)
  }

end PermissionDenialMessageSpec
