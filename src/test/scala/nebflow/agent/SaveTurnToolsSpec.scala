package nebflow.agent

import munit.FunSuite

/**
 * Save-phase compaction tool whitelist (compaction burn-down, 2026-08-18).
 *
 * Evidence: on the default model (107/glm-5.2-107) a save turn with the FULL
 * toolset explored indefinitely (5min+ without compactComplete); qa-mini with
 * NO tools finished cleanly in 110s. The save turn's purpose is the memory
 * maintenance cycle (Write/Edit on memory files + Read for the VERIFY step) —
 * so restrict it to those essentials and nothing that can branch outward
 * (Bash/Grep/Glob/WebSearch/WebFetch/Delegate/Mail).
 */
class SaveTurnToolsSpec extends FunSuite:

  private object CoreProbe extends AgentCore:
    def save(defn: AgentDef, depth: Int = 0, isSubTaskWorker: Boolean = false): Option[List[String]] =
      saveTurnTools(defn, depth, isSubTaskWorker).map(_.map(_.name))

  private val Whitelist = Set("Write", "Edit", "Read")

  private def mkDef(name: String, tools: List[String]): AgentDef =
    AgentDef(name = name, description = "test", tools = tools)

  test("save turn restricts the full toolset to the memory-maintenance whitelist"):
    val names = CoreProbe.save(mkDef("Nebula", List("*"))).getOrElse(fail("save turn must keep tools"))
    assert(names.nonEmpty, "write capability must survive")
    assert(names.forall(Whitelist.contains), s"unexpected tool in save turn: $names")
    assert(names.contains("Write"), "Write is the core memory-maintenance tool")

  test("save turn never includes exploration / branching tools"):
    val names = CoreProbe.save(mkDef("Nebula", List("*"))).getOrElse(Nil)
    val banned = Set("Bash", "Grep", "Glob", "WebSearch", "WebFetch", "Delegate", "Mail", "SubTask", "FlowTrigger")
    assert(!names.exists(banned.contains), s"exploration tools leaked into save turn: $names")

  test("save turn filters a concrete team-agent tool list to the whitelist"):
    val names = CoreProbe
      .save(mkDef("Manager", List("Write", "Edit", "Read", "Grep", "Bash", "Mail")))
      .getOrElse(fail("save turn must keep tools"))
    assertEquals(names.toSet, Whitelist)

  test("save turn drops non-whitelist tools even when the base set injects them"):
    // Write/Edit/Read are injected as fixed base tools for every agent, so the
    // whitelist survives; the point is that nothing ELSE (Grep/Bash from the
    // def's own tools) leaks through.
    val names = CoreProbe.save(mkDef("explorer", List("Grep", "Bash"))).getOrElse(fail("save turn must keep tools"))
    assert(names.forall(Whitelist.contains), s"non-whitelist tool leaked into save turn: $names")
