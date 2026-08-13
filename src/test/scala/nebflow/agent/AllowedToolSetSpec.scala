package nebflow.agent

import munit.FunSuite
import nebflow.core.tools.ToolRegistry

/**
 * Verifies that each agent's dedicated `tools` list actually restricts the
 * tools available to it (#9 — per-agent tools interface).
 *
 * buildAllowedToolSet is the single source of truth: it filters both the
 * LLM-request schema and runtime tool-call execution. The contract:
 *   - a concrete tools list → those tools + fixed tools (base + category-specific)
 *   - "*" → all registered tools
 *   - Mail is team-only (auto-injected for team agents, never for flow/standalone)
 *   - FlowReport is flow-only
 *   - non-Nebula agents never get Nebula-exclusive tools (Schedule, Delegate)
 *   - SubTask workers (isSubTaskWorker=true) are leaf agents: no Mail /
 *     SubTask / Delegate regardless of their tools list
 */
class AllowedToolSetSpec extends FunSuite:

  // AgentCore.buildAllowedToolSet is protected; expose it via a minimal stub.
  private object CoreProbe extends AgentCore:

    def allowed(defn: AgentDef, depth: Int = 0, isSubTaskWorker: Boolean = false): Set[String] =
      buildAllowedToolSet(defn, depth, isSubTaskWorker)

  private def mkDef(name: String, tools: List[String], mcpServers: List[String] = Nil): AgentDef =
    AgentDef(name = name, description = "", tools = tools, systemPrompt = "", mcpServers = mcpServers)

  test("team agent concrete tools list yields those tools + base + Mail"):
    val defn = mkDef("researcher", List("Read", "Glob", "Grep")).copy(category = "team")
    val allowed = CoreProbe.allowed(defn)
    assertEquals(
      allowed,
      Set("Read", "Glob", "Grep", "Write", "Edit", "Bash", "Issue", "RemoveUnnecessary", "Mail")
    )

  test("standalone agent gets base fixed tools but NOT Mail"):
    val defn = mkDef("solo", List("Read", "Pop"))
    val allowed = CoreProbe.allowed(defn)
    assert(allowed.contains("Read"))
    assert(allowed.contains("Pop"))
    assert(allowed.contains("Issue"))
    assert(allowed.contains("Write"))
    assert(!allowed.contains("Mail"), "standalone agent does not get Mail")

  test("'*' expands to all registered tools, minus Nebula-exclusive for non-Nebula"):
    val defn = mkDef("omni", List("*"))
    val allowed = CoreProbe.allowed(defn)
    // Mail is a registered tool, so "*" already includes it. Non-Nebula agents
    // drop Nebula-exclusive tools, EntityManagement tools.
    assert(allowed.contains("Mail"))
    assert(allowed.contains("Read"))
    assert(allowed.contains("Bash"))
    // Nebula-exclusive tools are stripped
    assert(!allowed.contains("Schedule"))
    assert(!allowed.contains("Dispatch"))
    // AskUserQuestion is no longer Nebula-exclusive — any agent that requests it gets it
    assert(allowed.contains("AskUserQuestion"))
    // Delegate is Nebula-exclusive — stripped for non-Nebula agents
    assert(!allowed.contains("Delegate"))
    // Schedule is stripped for non-Nebula agents
    assert(!allowed.contains("Schedule"))

  test("base fixed tools always available; Mail only for team category"):
    val standaloneDefn = mkDef("minimal", List("Read"))
    val standaloneAllowed = CoreProbe.allowed(standaloneDefn)
    assert(!standaloneAllowed.contains("Mail"), "standalone does not get Mail")
    assert(standaloneAllowed.contains("Issue"), "Issue is a base tool")
    assert(standaloneAllowed.contains("Write"), "Write is a base tool")
    assert(standaloneAllowed.contains("RemoveUnnecessary"), "RemoveUnnecessary is a base tool")

    val teamDefn = mkDef("teammate", List("Read")).copy(category = "team")
    val teamAllowed = CoreProbe.allowed(teamDefn)
    assert(teamAllowed.contains("Mail"), "team agent gets Mail")
    assert(teamAllowed.contains("Issue"), "team agent also gets base tools")

  test("FlowReport is available only to flow-category agents"):
    val flowDefn = mkDef("reviewer", List("Read")).copy(category = "flow")
    val teamDefn = mkDef("backend", List("Read")).copy(category = "team")
    val stdDefn = mkDef("explorer", List("Read")).copy(category = "standalone")
    assert(CoreProbe.allowed(flowDefn).contains("FlowReport"), "flow agent keeps FlowReport")
    assert(!CoreProbe.allowed(teamDefn).contains("FlowReport"), "team agent does not get FlowReport")
    assert(!CoreProbe.allowed(stdDefn).contains("FlowReport"), "standalone agent does not get FlowReport")

  test("non-Nebula agents can use AskUserQuestion if listed"):
    // AskUserQuestion is no longer Nebula-exclusive — any agent that requests it gets it.
    val defn = mkDef("leaky", List("Read", "Pop", "AskUserQuestion"))
    val allowed = CoreProbe.allowed(defn)
    assert(allowed.contains("Read"), "Read allowed")
    assert(allowed.contains("Pop"), "Pop is no longer Nebula-exclusive — must be kept")
    assert(allowed.contains("AskUserQuestion"), "AskUserQuestion is no longer Nebula-exclusive")

  test("Nebula keeps Nebula-exclusive tools"):
    val defn = mkDef("Nebula", List("Read", "Pop", "AskUserQuestion"))
    val allowed = CoreProbe.allowed(defn)
    assert(allowed.contains("Pop"), "Nebula keeps Pop")
    assert(allowed.contains("AskUserQuestion"), "Nebula keeps AskUserQuestion")

  test("non-Nebula agents are stripped of Schedule even with wildcard"):
    val defn = mkDef("someone", List("*"))
    val allowed = CoreProbe.allowed(defn)
    assert(!allowed.contains("Schedule"), "Schedule is Nebula-exclusive — stripped for non-Nebula")
    assert(allowed.contains("AskUserQuestion"), "AskUserQuestion is NOT Nebula-exclusive — kept for non-Nebula")

  test("agents without mcpServers grant are stripped of all MCP tools"):
    val defn = mkDef("no-mcp", List("Read", "mcp__git__status", "mcp__zai__chat"))
    val allowed = CoreProbe.allowed(defn)
    assert(allowed.contains("Read"))
    assert(!allowed.contains("mcp__git__status"), "mcp tool dropped without grant")
    assert(!allowed.contains("mcp__zai__chat"), "mcp tool dropped without grant")

  test("mcpServers grant allows only tools from the granted servers"):
    val defn = mkDef("granted", List("Read", "mcp__git__status", "mcp__zai__chat"), mcpServers = List("git"))
    val allowed = CoreProbe.allowed(defn)
    assert(allowed.contains("Read"))
    assert(allowed.contains("mcp__git__status"), "granted server tool kept")
    assert(!allowed.contains("mcp__zai__chat"), "ungranted server tool dropped")

  test("agent's own dedicated MCP tools are auto-allowed without mcpServers grant"):
    // tools/mcp/ servers register as mcp__agent-<agentName>-<serverName>__<tool>
    val defn = mkDef("mydoc", List("Read", "mcp__agent-mydoc-docs__search", "mcp__git__status"))
    val allowed = CoreProbe.allowed(defn)
    assert(allowed.contains("Read"))
    assert(allowed.contains("mcp__agent-mydoc-docs__search"), "own dedicated server tool auto-allowed")
    assert(!allowed.contains("mcp__git__status"), "other server tool still denied without grant")

  test("own dedicated MCP tools are allowed alongside explicit grants"):
    val defn =
      mkDef("mydoc", List("Read", "mcp__agent-mydoc-docs__search", "mcp__git__status"), mcpServers = List("git"))
    val allowed = CoreProbe.allowed(defn)
    assert(allowed.contains("mcp__agent-mydoc-docs__search"), "own dedicated tool kept with grants")
    assert(allowed.contains("mcp__git__status"), "granted server tool kept")
    assert(!allowed.contains("mcp__zai__chat"), "ungranted server tool still denied")

  test("dedicated MCP tools belong only to the owning agent"):
    val defn = mkDef("other", List("Read", "mcp__agent-mydoc-docs__search"))
    val allowed = CoreProbe.allowed(defn)
    assert(!allowed.contains("mcp__agent-mydoc-docs__search"), "another agent's dedicated tool is denied")

  test("Delegate is Nebula-exclusive; SubTask is the team-member delegation tool"):
    val nebula = mkDef("Nebula", List("Read", "Delegate"))
    val teamAgent = mkDef("backend", List("Read", "SubTask"))
    assert(CoreProbe.allowed(nebula).contains("Delegate"), "Nebula keeps Delegate")
    assert(!CoreProbe.allowed(nebula).contains("SubTask"), "Nebula does not need SubTask (unless listed)")
    assert(!CoreProbe.allowed(teamAgent).contains("Delegate"), "non-Nebula never gets Delegate")
    assert(CoreProbe.allowed(teamAgent).contains("SubTask"), "team agent with SubTask listed keeps it")
    // Delegate stripped for non-Nebula at all depths
    val anyone = mkDef("anyone", List("Read", "Delegate"))
    assert(!CoreProbe.allowed(anyone, depth = 0).contains("Delegate"), "depth 0")
    assert(!CoreProbe.allowed(anyone, depth = 1).contains("Delegate"), "depth 1")
    assert(!CoreProbe.allowed(anyone, depth = 2).contains("Delegate"), "depth 2")

  test("SubTask workers are leaf agents — Mail/SubTask/Delegate stripped even when listed"):
    val worker = mkDef("backend", List("Read", "Mail", "SubTask", "Delegate", "Issue"))
    val allowed = CoreProbe.allowed(worker, isSubTaskWorker = true)
    assert(allowed.contains("Read"), "domain tools kept")
    assert(allowed.contains("Issue"), "Issue kept — system problem reporting, not team communication")
    assert(!allowed.contains("Mail"), "Mail stripped for workers")
    assert(!allowed.contains("SubTask"), "SubTask stripped for workers (no further delegation)")
    assert(!allowed.contains("Delegate"), "Delegate stripped for workers")
    // Same for wildcard tool lists
    val wildcardWorker = mkDef("omni-worker", List("*"))
    val wildcardAllowed = CoreProbe.allowed(wildcardWorker, isSubTaskWorker = true)
    assert(!wildcardAllowed.contains("Mail"), "wildcard worker: Mail stripped")
    assert(!wildcardAllowed.contains("SubTask"), "wildcard worker: SubTask stripped")
    assert(!wildcardAllowed.contains("Delegate"), "wildcard worker: Delegate stripped")

  test("team agent with SubTask listed keeps Mail and SubTask"):
    val defn = mkDef("backend", List("Read", "SubTask")).copy(category = "team")
    val allowed = CoreProbe.allowed(defn)
    assert(allowed.contains("Mail"), "team agent keeps Mail")
    assert(allowed.contains("SubTask"), "team agent keeps SubTask")
end AllowedToolSetSpec
