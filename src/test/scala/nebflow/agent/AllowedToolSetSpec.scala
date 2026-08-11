package nebflow.agent

import munit.FunSuite
import nebflow.core.tools.ToolRegistry

/**
 * Verifies that each agent's dedicated `tools` list actually restricts the
 * tools available to it (#9 — per-agent tools interface).
 *
 * buildAllowedToolSet is the single source of truth: it filters both the
 * LLM-request schema and runtime tool-call execution. The contract:
 *   - a concrete tools list → exactly those tools + Mail
 *   - "*" → all registered tools
 *   - Mail is always present
 *   - non-Nebula agents never get Nebula-exclusive tools (Schedule)
 */
class AllowedToolSetSpec extends FunSuite:

  // AgentCore.buildAllowedToolSet is protected; expose it via a minimal stub.
  private object CoreProbe extends AgentCore:

    def allowed(defn: AgentDef, depth: Int = 0): Set[String] =
      buildAllowedToolSet(defn, depth)

  private def mkDef(name: String, tools: List[String], mcpServers: List[String] = Nil): AgentDef =
    AgentDef(name = name, description = "", tools = tools, systemPrompt = "", mcpServers = mcpServers)

  test("concrete tools list yields exactly those tools + Mail + Issue"):
    val defn = mkDef("researcher", List("Read", "Glob", "Grep"))
    val allowed = CoreProbe.allowed(defn)
    assertEquals(allowed, Set("Read", "Glob", "Grep", "Mail", "Issue"))

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
    // Delegate is available to all agents (no depth restriction)
    assert(allowed.contains("Delegate"))
    // Schedule is stripped for non-Nebula agents
    assert(!allowed.contains("Schedule"))

  test("Mail and Issue are always available even when not listed"):
    val defn = mkDef("minimal", List("Read"))
    val allowed = CoreProbe.allowed(defn)
    assert(allowed.contains("Mail"))
    assert(allowed.contains("Issue"))

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
    assert(allowed.contains("Mail"))

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

  test("Delegate is available at all depths (no depth restriction)"):
    val defn = mkDef("anyone", List("Read", "Delegate"))
    assert(CoreProbe.allowed(defn, depth = 0).contains("Delegate"), "depth 0")
    assert(CoreProbe.allowed(defn, depth = 1).contains("Delegate"), "depth 1")
    assert(CoreProbe.allowed(defn, depth = 2).contains("Delegate"), "depth 2")
    assert(CoreProbe.allowed(defn, depth = 4).contains("Delegate"), "depth 4")
end AllowedToolSetSpec
