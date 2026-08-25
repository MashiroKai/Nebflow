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
 *   - SubTask is team-only (user ruling 2026-08-24: auto-injected at the
 *     mechanism layer — manual agent.json declarations are error-prone)
 *   - FlowReport is flow-only
 *   - non-Nebula agents never get Nebula-exclusive tools (Schedule, Delegate)
 *   - FlowTrigger is whitelist-driven: present iff agentDef.flows is non-empty
 *     (not Nebula-exclusive); SubTask workers never get it
 *   - SubTask workers (isSubTaskWorker=true) are leaf agents: no Mail /
 *     SubTask / Delegate / FlowTrigger regardless of their tools list
 */
class AllowedToolSetSpec extends FunSuite:

  // AgentCore.buildAllowedToolSet is protected; expose it via a minimal stub.
  private object CoreProbe extends AgentCore:

    def allowed(
        defn: AgentDef,
        depth: Int = 0,
        isSubTaskWorker: Boolean = false,
        forkContext: Boolean = false,
        isFlowNode: Boolean = false
    ): Set[String] =
      buildAllowedToolSet(defn, depth, isSubTaskWorker, forkContext, isFlowNode)

  private def mkDef(name: String, tools: List[String], mcpServers: List[String] = Nil): AgentDef =
    AgentDef(name = name, description = "", tools = tools, systemPrompt = "", mcpServers = mcpServers)

  test("team agent concrete tools list yields those tools + base + Mail + SubTask + FlowExecute"):
    val defn = mkDef("researcher", List("Read", "Glob", "Grep")).copy(category = "team")
    val allowed = CoreProbe.allowed(defn)
    assertEquals(
      allowed,
      Set("Read", "Glob", "Grep", "Write", "Edit", "Bash", "Issue", "Mail", "SubTask", "FlowExecute")
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

  // ===== SubTask team auto-injection (user ruling 2026-08-24) =====

  test("team member WITHOUT explicit SubTask gets it auto-injected"):
    // The ruling's core case: a team member whose agent.json tools list omits
    // SubTask must still have it (html-deck-studio missed it for all four
    // members; manual declaration is error-prone).
    val defn = mkDef("visual-reviewer", List("Read", "Grep", "Bash")).copy(category = "team")
    val allowed = CoreProbe.allowed(defn)
    assert(allowed.contains("SubTask"), "team member gets SubTask without declaring it")
    assert(allowed.contains("Mail"), "team member keeps Mail")
    assert(allowed.contains("Read"), "declared tools unaffected")

  test("team member with '*' wildcard keeps SubTask"):
    val defn = mkDef("omni-team", List("*")).copy(category = "team")
    val allowed = CoreProbe.allowed(defn)
    assert(allowed.contains("SubTask"), "wildcard team member keeps SubTask")

  test("standalone agents do NOT get SubTask auto-injected"):
    val solo = mkDef("solo", List("Read", "Grep"))
    val allowed = CoreProbe.allowed(solo)
    assert(!allowed.contains("SubTask"), "standalone agent has no SubTask (leaf — no team context)")
    // Explicit listing still works (opt-in), same as Mail for standalone.
    val explicit = mkDef("solo-explicit", List("Read", "SubTask"))
    assert(CoreProbe.allowed(explicit).contains("SubTask"), "standalone explicitly listing SubTask keeps it")

  test("flow agents do NOT get SubTask auto-injected"):
    val flow = mkDef("scanner", List("Read", "Grep")).copy(category = "flow")
    val allowed = CoreProbe.allowed(flow)
    assert(!allowed.contains("SubTask"), "flow node is a leaf — no SubTask")
    assert(!allowed.contains("Mail"), "flow node keeps the structural Mail block")

  test("SubTask worker stripped of SubTask even when auto-injected team member"):
    // A SubTask worker self-clones its team-member def (category=team →
    // SubTask auto-injected) but the isSubTaskWorker leaf rule must still
    // strip it — workers cannot delegate further.
    val defn = mkDef("backend", List("Read", "Grep")).copy(category = "team")
    val allowed = CoreProbe.allowed(defn, isSubTaskWorker = true)
    assert(!allowed.contains("SubTask"), "worker: SubTask stripped despite team category")
    assert(!allowed.contains("Mail"), "worker: Mail stripped")
    assert(allowed.contains("Read"), "worker: domain tools kept")

  test("Nebula is not affected by the team SubTask injection"):
    val nebula = mkDef("Nebula", List("Read", "Delegate"))
    val allowed = CoreProbe.allowed(nebula)
    assert(!allowed.contains("SubTask"), "Nebula (standalone category) unchanged — uses Delegate")
    assert(allowed.contains("Delegate"), "Nebula keeps Delegate")

  // ===== Flow agents: Mail structurally disabled (08-14 P0 root cause) =====

  test("flow agent with '*' wildcard never gets Mail (P0 penetration case)"):
    val flowWildcard = mkDef("scanner", List("*")).copy(category = "flow")
    val allowed = CoreProbe.allowed(flowWildcard)
    assert(!allowed.contains("Mail"), "flow agent with '*' must not get Mail — a flow agent calling Mail(ask) blocks forever (08-14 P0)")
    assert(allowed.contains("FlowReport"), "flow agent keeps FlowReport")
    assert(allowed.contains("Read"), "flow agent keeps normal tools")

  test("flow agent with Mail explicitly listed still loses it"):
    val flowExplicit = mkDef("reviewer", List("Read", "Mail")).copy(category = "flow")
    val allowed = CoreProbe.allowed(flowExplicit)
    assert(!allowed.contains("Mail"), "explicitly listed Mail is stripped for flow agents")
    assert(allowed.contains("Read"), "other tools unaffected")

  test("team and standalone Mail access unaffected by the flow filter"):
    val team = mkDef("backend", List("Read")).copy(category = "team")
    assert(CoreProbe.allowed(team).contains("Mail"), "team agent keeps Mail")
    // Standalone agents that explicitly list Mail keep it — the structural
    // block is flow-only (flow nodes report via FlowReport).
    val solo = mkDef("solo", List("Read", "Mail"))
    assert(CoreProbe.allowed(solo).contains("Mail"), "standalone agent explicitly listing Mail keeps it")

  // ===== FlowTrigger: whitelist-driven (R1 split) =====

  test("FlowTrigger injected iff the agent declares flows"):
    val withFlows = mkDef("scheduler", List("Read")).copy(flows = List("code-review"))
    val without = mkDef("plain", List("Read"))
    assert(CoreProbe.allowed(withFlows).contains("FlowTrigger"), "flows declared → tool injected")
    assert(!CoreProbe.allowed(without).contains("FlowTrigger"), "no flows → no tool")

  test("FlowTrigger stripped from flow-less agents even when listed or via wildcard"):
    val listed = mkDef("sneaky", List("Read", "FlowTrigger"))
    assert(!CoreProbe.allowed(listed).contains("FlowTrigger"), "explicit listing without flows is stripped")
    val wildcard = mkDef("omni", List("*"))
    assert(!CoreProbe.allowed(wildcard).contains("FlowTrigger"), "wildcard without flows is stripped")
    val wildcardWithFlows = mkDef("omni-flow", List("*")).copy(flows = List("release-beta"))
    assert(CoreProbe.allowed(wildcardWithFlows).contains("FlowTrigger"), "wildcard WITH flows keeps the tool")

  test("FlowTrigger is NOT Nebula-exclusive — team member with flows gets it"):
    val teamMember = mkDef("backend", List("Read")).copy(category = "team", flows = List("code-review"))
    assert(
      CoreProbe.allowed(teamMember).contains("FlowTrigger"),
      "FlowTrigger availability follows the flows whitelist, not the Nebula filter"
    )
    val nebula = mkDef("Nebula", List("Read")).copy(flows = List("code-review"))
    assert(CoreProbe.allowed(nebula).contains("FlowTrigger"), "Nebula with flows keeps it")
    val nebulaNoFlows = mkDef("Nebula", List("Read", "FlowTrigger"))
    assert(!CoreProbe.allowed(nebulaNoFlows).contains("FlowTrigger"), "even Nebula needs flows declared")

  test("SubTask workers never get FlowTrigger even with flows declared"):
    val worker = mkDef("backend", List("*")).copy(flows = List("code-review"))
    assert(
      !CoreProbe.allowed(worker, isSubTaskWorker = true).contains("FlowTrigger"),
      "workers are leaf agents — no pipeline triggering"
    )
    assert(
      CoreProbe.allowed(worker, isSubTaskWorker = false).contains("FlowTrigger"),
      "same def as a normal agent would keep it (sanity)"
    )

  // ===== #30: Mail ask/fork contexts strip side-effect tools =====

  test("fork context strips dispatch tools (Mail/SubTask/FlowTrigger/Delegate)"):
    val teamMember = mkDef("backend", List("*")).copy(category = "team", flows = List("code-review"))
    val allowed = CoreProbe.allowed(teamMember, forkContext = true)
    assert(!allowed.contains("Mail"), "fork must not dispatch via Mail")
    assert(!allowed.contains("SubTask"), "fork must not spawn sub-workers")
    assert(!allowed.contains("FlowTrigger"), "fork must not trigger pipelines")
    assert(!allowed.contains("Delegate"), "fork must not delegate")
    assert(!allowed.contains("AgentControl"), "fork must not control agents")
    assert(!allowed.contains("Load"), "fork must not mount entities")
    assert(!allowed.contains("TransferFile"), "fork must not transfer files")

  test("fork context strips write/shell/schedule side effects, keeps retrieval"):
    val member = mkDef("backend", List("*")).copy(category = "team")
    val allowed = CoreProbe.allowed(member, forkContext = true)
    // side-effect tools gone
    assert(!allowed.contains("Write"), "fork must not write files (memory double-write race)")
    assert(!allowed.contains("Edit"), "fork must not edit files")
    assert(!allowed.contains("MultiEdit"), "fork must not multi-edit")
    assert(!allowed.contains("Bash"), "fork must not run shell")
    assert(!allowed.contains("Curl"), "fork must not issue network requests")
    assert(!allowed.contains("SaveTurn"), "fork must not run save-turn memory cycle")
    assert(!allowed.contains("Schedule"), "fork must not schedule tasks")
    assert(!allowed.contains("AskUserQuestion"), "fork must not ask the user")
    assert(!allowed.contains("TaskCreate"), "fork must not create tasks")
    assert(!allowed.contains("TaskUpdate"), "fork must not update tasks")
    // retrieval stays for answering questions
    assert(allowed.contains("Read"), "fork keeps Read")
    assert(allowed.contains("Glob"), "fork keeps Glob")
    assert(allowed.contains("Grep"), "fork keeps Grep")
    assert(allowed.contains("WebSearch"), "fork keeps WebSearch")
    assert(allowed.contains("WebFetch"), "fork keeps WebFetch")
    assert(allowed.contains("Pop"), "fork keeps Pop")
    assert(allowed.contains("TaskQuery"), "fork keeps read-only task query")

  test("fork context is inert for non-fork spawns (default false)"):
    val member = mkDef("backend", List("*")).copy(category = "team")
    val normal = CoreProbe.allowed(member)
    assert(normal.contains("Mail"), "normal team spawn keeps Mail")
    assert(normal.contains("Write"), "normal spawn keeps Write")
    assert(normal.contains("Bash"), "normal spawn keeps Bash")

  test("fork context does not resurrect tools stripped by other rules"):
    // worker + fork: worker leaf rule and fork strip compose
    val worker = mkDef("backend", List("*")).copy(category = "team")
    val w = CoreProbe.allowed(worker, isSubTaskWorker = true, forkContext = true)
    assert(!w.contains("Mail"), "worker leaf rule strips Mail, fork or not")
    assert(!w.contains("Write"), "fork strip applies on top of worker rule")
    assert(!w.contains("Bash"), "fork strip applies on top of worker rule")
    assert(w.contains("Read"), "worker keeps its retrieval tools")
    // flow + fork: flow Mail strip and fork strip compose
    val flow = mkDef("node", List("*")).copy(category = "flow")
    val f = CoreProbe.allowed(flow, forkContext = true)
    assert(!f.contains("Mail"), "flow agents never get Mail, fork or not")
    assert(!f.contains("FlowReport"), "fork strips FlowReport too")
    assert(f.contains("Read"), "flow fork keeps retrieval")

  // ===== #406 FlowExecute mechanism-layer injection + flow-node leaf rule =====

  test("team member WITHOUT explicit FlowExecute gets it auto-injected"):
    val defn = mkDef("backend", List("Read", "Grep", "Bash")).copy(category = "team")
    val allowed = CoreProbe.allowed(defn)
    assert(allowed.contains("FlowExecute"), "team member gets FlowExecute without declaring it")
    assert(allowed.contains("SubTask"), "team member keeps SubTask")
    assert(allowed.contains("Mail"), "team member keeps Mail")

  test("team member with '*' wildcard keeps FlowExecute"):
    val defn = mkDef("omni-team", List("*")).copy(category = "team")
    val allowed = CoreProbe.allowed(defn)
    assert(allowed.contains("FlowExecute"), "wildcard team member keeps FlowExecute")

  test("Nebula (root) gets FlowExecute without declaration"):
    val nebula = mkDef("Nebula", List("Read", "Delegate"))
    val allowed = CoreProbe.allowed(nebula)
    assert(allowed.contains("FlowExecute"), "Nebula gets FlowExecute at the mechanism layer")
    assert(allowed.contains("Delegate"), "Nebula keeps Delegate")

  test("standalone agents do NOT get FlowExecute auto-injected"):
    val solo = mkDef("solo", List("Read", "Grep"))
    assert(!CoreProbe.allowed(solo).contains("FlowExecute"), "standalone agent has no FlowExecute by default")
    // Explicit listing still works (opt-in) — fixedToolsFor only ADDS the
    // mechanism-layer default; an explicit list survives.
    val explicit = mkDef("solo-flow", List("Read", "FlowExecute"))
    assert(CoreProbe.allowed(explicit).contains("FlowExecute"), "standalone explicitly listing FlowExecute keeps it")

  test("flow-category agents do NOT get FlowExecute (predefined flow nodes are leaves too)"):
    val flow = mkDef("scanner", List("Read", "Grep")).copy(category = "flow")
    val allowed = CoreProbe.allowed(flow)
    assert(!allowed.contains("FlowExecute"), "predefined flow node is a leaf — no nested flow execution")

  test("isFlowNode leaf rule strips FlowExecute/FlowTrigger/SubTask/Delegate even when auto-injected"):
    // A dynamic flow node reusing a team-category agent (FlowExecute auto-
    // injected via fixedToolsFor) must still be stripped — flow nodes cannot
    // open nested flows (recursive explosion guard, #406).
    val teamNode = mkDef("backend", List("Read", "Grep")).copy(category = "team")
    val allowed = CoreProbe.allowed(teamNode, isFlowNode = true)
    assert(!allowed.contains("FlowExecute"), "flow node: FlowExecute stripped despite team category")
    assert(!allowed.contains("FlowTrigger"), "flow node: FlowTrigger stripped")
    assert(!allowed.contains("SubTask"), "flow node: SubTask stripped")
    assert(!allowed.contains("Delegate"), "flow node: Delegate stripped")
    assert(allowed.contains("Mail"), "flow node: team-category keeps Mail (may Mail the caller's team)")
    assert(allowed.contains("Read"), "flow node: domain tools kept")

  test("isFlowNode on a standalone-category agent keeps FlowReport when listed (verdict channel)"):
    val node = mkDef("worker", List("Read", "Grep", "FlowReport"))
    val allowed = CoreProbe.allowed(node, isFlowNode = true)
    assert(!allowed.contains("FlowExecute"), "flow node: no nested flow")
    assert(allowed.contains("FlowReport"), "flow node: FlowReport survives the leaf strip (verdict channel)")
    assert(allowed.contains("Read"), "flow node: domain tools kept")

  test("isFlowNode leaf rule also strips explicitly listed FlowExecute"):
    val sneaky = mkDef("sneaky", List("Read", "FlowExecute", "SubTask")).copy(category = "team")
    val allowed = CoreProbe.allowed(sneaky, isFlowNode = true)
    assert(!allowed.contains("FlowExecute"), "explicitly listed FlowExecute stripped for flow nodes")
    assert(!allowed.contains("SubTask"), "explicitly listed SubTask stripped for flow nodes")

  test("SubTask workers also stripped of FlowExecute (leaf symmetry)"):
    // A SubTask worker self-clones a team-member def (category=team →
    // FlowExecute auto-injected); the worker leaf rule must strip it too.
    val worker = mkDef("backend", List("Read", "Grep")).copy(category = "team")
    val allowed = CoreProbe.allowed(worker, isSubTaskWorker = true)
    assert(!allowed.contains("FlowExecute"), "worker: FlowExecute stripped despite team category")
    assert(!allowed.contains("SubTask"), "worker: SubTask stripped")
    assert(!allowed.contains("Mail"), "worker: Mail stripped")
end AllowedToolSetSpec
