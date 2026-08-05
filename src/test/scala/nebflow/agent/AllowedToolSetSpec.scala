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
 *   - non-Nebula agents never get Nebula-exclusive tools (Pop, AskUserQuestion, Schedule)
 */
class AllowedToolSetSpec extends FunSuite:

  // AgentCore.buildAllowedToolSet is protected; expose it via a minimal stub.
  private object CoreProbe extends AgentCore:
    def allowed(defn: AgentDef, depth: Int = 0): Set[String] =
      buildAllowedToolSet(defn, depth)

  private def mkDef(name: String, tools: List[String], mcpServers: List[String] = Nil): AgentDef =
    AgentDef(name = name, description = "", tools = tools, systemPrompt = "", mcpServers = mcpServers)

  test("concrete tools list yields exactly those tools + Mail"):
    val defn = mkDef("researcher", List("Read", "Glob", "Grep"))
    val allowed = CoreProbe.allowed(defn)
    assertEquals(allowed, Set("Read", "Glob", "Grep", "Mail"))

  test("'*' expands to all registered tools, minus Nebula-exclusive for non-Nebula"):
    val defn = mkDef("omni", List("*"))
    val allowed = CoreProbe.allowed(defn)
    // Mail is a registered tool, so "*" already includes it. Non-Nebula agents
    // drop Nebula-exclusive tools, EntityManagement tools, and Delegate.
    assert(allowed.contains("Mail"))
    assert(allowed.contains("Read"))
    assert(allowed.contains("Bash"))
    // Nebula-exclusive tools are stripped
    assert(!allowed.contains("Pop"))
    assert(!allowed.contains("AskUserQuestion"))
    assert(!allowed.contains("Schedule"))
    assert(!allowed.contains("Dispatch"))
    // Delegate only for depth >= 2
    assert(!allowed.contains("Delegate"))
    // Fewer than total registered tools
    assert(allowed.size < ToolRegistry.ALL_TOOLS.map(_.name).toSet.size)

  test("Mail is always available even when not listed"):
    val defn = mkDef("minimal", List("Read"))
    val allowed = CoreProbe.allowed(defn)
    assert(allowed.contains("Mail"))

  test("non-Nebula agents are stripped of Nebula-exclusive tools even if listed"):
    // If a flow agent erroneously requests Pop, it must be dropped.
    val defn = mkDef("leaky", List("Read", "Pop"))
    val allowed = CoreProbe.allowed(defn)
    assert(allowed.contains("Read"), "Read allowed")
    assert(!allowed.contains("Pop"), "Pop is Nebula-exclusive — must be dropped")
    assert(allowed.contains("Mail"))

  test("Nebula keeps Nebula-exclusive tools"):
    val defn = mkDef("Nebula", List("Read", "Pop"))
    val allowed = CoreProbe.allowed(defn)
    assert(allowed.contains("Pop"), "Nebula keeps Pop")

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
