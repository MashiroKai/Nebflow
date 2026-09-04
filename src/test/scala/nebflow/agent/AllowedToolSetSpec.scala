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
 *   - BaseTools (Read/Write/Edit/Glob/Grep/Bash) are mechanism-fixed for ALL
 *     agents including Nebula (2026-08-28 00:55 用户裁定, reverses #438);
 *     agent.json declarations coexist idempotently
 *   - Issue is Nebula-only (user ruling 2026-08-25: system feedback
 *     collection is orchestrator-only; stripped from non-Nebula even when
 *     explicitly listed or via wildcard)
 *   - Nebula's 7 orchestration tools are mechanism-fixed (no declaration
 *     needed): AgentControl/Delegate/Pop/AskUserQuestion/Mail/Schedule/
 *     TransferFile (+ Issue + FlowExecute). Task tools retired (任务工具重做
 *     2026-08-30 — team-only).
 *   - Mail is team-only (auto-injected for team agents, never for flow/standalone)
 *   - SubTask is team-only (user ruling 2026-08-24: auto-injected at the
 *     mechanism layer — manual agent.json declarations are error-prone)
 *   - FlowReport is flow-only
 *   - non-Nebula agents never get Nebula-exclusive tools (Schedule, Delegate, Issue)
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
        isFlowNode: Boolean = false,
        isTeamLead: Boolean = false,
        userFacingNode: Boolean = false,
        guardrailsOn: Boolean = false
    ): Set[String] =
      buildAllowedToolSet(defn, depth, isSubTaskWorker, isFlowNode, isTeamLead, userFacingNode, guardrailsOn)

  private def mkDef(name: String, tools: List[String], mcpServers: List[String] = Nil): AgentDef =
    AgentDef(name = name, description = "", tools = tools, systemPrompt = "", mcpServers = mcpServers)

  test("team agent concrete tools list yields those tools + base + Mail + SubTask + FlowExecute + TeamTask*"):
    val defn = mkDef("researcher", List("Read", "Glob", "Grep")).copy(category = "team")
    val allowed = CoreProbe.allowed(defn)
    assertEquals(
      allowed,
      Set("Read", "Glob", "Grep", "Write", "Edit", "Bash", "Mail", "SubTask", "FlowExecute",
        "TeamTaskCreate", "TeamTaskUpdate", "TeamTaskList")
    )

  test("standalone agent gets base fixed tools but NOT Mail"):
    val defn = mkDef("solo", List("Read", "Pop"))
    val allowed = CoreProbe.allowed(defn)
    assert(allowed.contains("Read"))
    assert(allowed.contains("Pop"))
    assert(!allowed.contains("Issue"), "Issue is Nebula-only (2026-08-25 ruling)")
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
    assert(!standaloneAllowed.contains("Issue"), "Issue is Nebula-only — not a base tool anymore")
    assert(standaloneAllowed.contains("Write"), "Write is a base tool")

    val teamDefn = mkDef("teammate", List("Read")).copy(category = "team")
    val teamAllowed = CoreProbe.allowed(teamDefn)
    assert(teamAllowed.contains("Mail"), "team agent gets Mail")
    assert(!teamAllowed.contains("Issue"), "team agent also loses Issue (orchestrator-only)")

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
    assert(!allowed.contains("Issue"), "Issue stripped — orchestrator-only (2026-08-25 ruling), even when listed")
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
    // 阶段 2c（§C.1 双轨期过渡组）：FlowTrigger 对 Nebula 机制固定——不再依赖
    // flows 声明（旧 agent.json flows:["*"] 退役为 no-op）。
    val nebulaNoFlows = mkDef("Nebula", Nil)
    assert(CoreProbe.allowed(nebulaNoFlows).contains("FlowTrigger"), "Nebula gets FlowTrigger without flows declaration (阶段 2c 固定)")

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

  // ===== 任务工具重做 (2026-08-30): TeamTask* team-member injection + Nebula/leaf exclusion =====

  test("team lead AND member both get the full TeamTask set (progress display — everyone)"):
    val lead = mkDef("Manager", List("Read", "Grep", "Bash", "Mail")).copy(category = "team")
    val member = mkDef("backend", List("Read", "Grep", "Bash", "Mail")).copy(category = "team")
    for (who, allowed) <- Seq("lead" -> CoreProbe.allowed(lead, isTeamLead = true), "member" -> CoreProbe.allowed(member)) do
      assert(allowed.contains("TeamTaskCreate"), s"$who: TeamTaskCreate")
      assert(allowed.contains("TeamTaskUpdate"), s"$who: TeamTaskUpdate")
      assert(allowed.contains("TeamTaskList"), s"$who: TeamTaskList")

  test("Nebula gets NO TeamTask tools (任务工具重做: tasks are team-only)"):
    val nebula = mkDef("Nebula", List("Read", "Delegate"))
    val allowed = CoreProbe.allowed(nebula)
    assert(!allowed.contains("TeamTaskCreate"), "Nebula: no TeamTaskCreate")
    assert(!allowed.contains("TeamTaskUpdate"), "Nebula: no TeamTaskUpdate")
    assert(!allowed.contains("TeamTaskList"), "Nebula: no TeamTaskList")
    assert(!allowed.contains("TaskCreate"), "Nebula: no session TaskCreate (retired)")
    assert(!allowed.contains("TaskUpdate"), "Nebula: no session TaskUpdate (retired)")
    assert(!allowed.contains("TaskQuery"), "Nebula: no session TaskQuery (retired)")

  test("standalone / flow agents never get TeamTask tools — even when declared"):
    val solo = mkDef("explorer", List("Read", "Grep", "TeamTaskList"))
    val flow = mkDef("scanner", List("Read", "TeamTaskCreate")).copy(category = "flow")
    assert(!CoreProbe.allowed(solo).contains("TeamTaskList"), "standalone: explicit declaration is inert")
    assert(!CoreProbe.allowed(flow).contains("TeamTaskCreate"), "flow agent: explicit declaration is inert")

  // ===== Block 1 (supervision trio §C2): AgentControl mechanism-layer grant =====

  test("Block 1: team lead gets AgentControl via mechanism grant (no declaration needed)"):
    val lead = mkDef("Manager", List("Read", "Grep", "Bash", "Mail")).copy(category = "team")
    val allowed = CoreProbe.allowed(lead, isTeamLead = true)
    assert(allowed.contains("AgentControl"), "team lead gets AgentControl (subtree scope enforced by the tool guard)")

  test("Block 1: declaring AgentControl in agent.json grants nothing (non-lead member)"):
    val member = mkDef("backend", List("Read", "AgentControl", "Mail")).copy(category = "team")
    val allowed = CoreProbe.allowed(member) // isTeamLead=false
    assert(!allowed.contains("AgentControl"), "member: AgentControl stripped despite explicit declaration")

  test("Block 1: Nebula keeps AgentControl; SubTask self-clone of a lead never gets it"):
    val nebula = mkDef("Nebula", List("Read"))
    assert(CoreProbe.allowed(nebula).contains("AgentControl"), "Nebula keeps global AgentControl")
    // a worker self-cloned from a Manager: new session id → isTeamLeadStatus false,
    // and the mechanism grant is the only source — "*" must not resurrect it
    val clone = mkDef("Manager", List("*")).copy(category = "team")
    val workerAllowed = CoreProbe.allowed(clone, isSubTaskWorker = true)
    assert(!workerAllowed.contains("AgentControl"), "worker clone: AgentControl stripped")
    val deepAllowed = CoreProbe.allowed(clone, depth = 2)
    assert(!deepAllowed.contains("AgentControl"), "depth-2 agent: AgentControl stripped")

  test("SubTask worker strips TeamTask* even when the parent is a team lead"):
    // A worker self-cloned from a Manager (category=team + isTeamLead grant)
    // must lose the team task board entirely — leaf isolation.
    val leadWorker = mkDef("Manager", List("Read", "Bash")).copy(category = "team")
    val allowed = CoreProbe.allowed(leadWorker, isSubTaskWorker = true, isTeamLead = true)
    assert(!allowed.contains("TeamTaskCreate"), "worker: TeamTaskCreate stripped despite isTeamLead")
    assert(!allowed.contains("TeamTaskUpdate"), "worker: TeamTaskUpdate stripped")
    assert(!allowed.contains("TeamTaskList"), "worker: TeamTaskList stripped")

  test("flow node strips TeamTask* (leaf isolation)"):
    val teamNode = mkDef("backend", List("Read")).copy(category = "team")
    val allowed = CoreProbe.allowed(teamNode, isFlowNode = true, isTeamLead = true)
    assert(!allowed.contains("TeamTaskCreate"), "flow node: no TeamTaskCreate")
    assert(!allowed.contains("TeamTaskUpdate"), "flow node: no TeamTaskUpdate")
    assert(!allowed.contains("TeamTaskList"), "flow node: no TeamTaskList")

  test("depth>=2 '*' agent strips TeamTask* (leaf treatment)"):
    val deep = mkDef("deep-worker", List("*")).copy(category = "team")
    val allowed = CoreProbe.allowed(deep, depth = 2, isTeamLead = true)
    assert(!allowed.contains("TeamTaskCreate"), "depth≥2 '*': no TeamTaskCreate")
    assert(!allowed.contains("TeamTaskUpdate"), "depth≥2 '*': no TeamTaskUpdate")
    assert(!allowed.contains("TeamTaskList"), "depth≥2 '*': no TeamTaskList")
    val leadShallow = CoreProbe.allowed(deep, depth = 0, isTeamLead = true)
    assert(leadShallow.contains("TeamTaskCreate"), "depth 0 lead keeps TeamTask*")

  // ===== #404 工具体系精简 (user ruling 2026-08-25 17:49) =====

  test("Issue is Nebula-only — stripped from non-Nebula even when explicitly listed"):
    val soloExplicit = mkDef("solo", List("Read", "Issue"))
    assert(!CoreProbe.allowed(soloExplicit).contains("Issue"), "standalone listing Issue gets nothing")
    val teamExplicit = mkDef("backend", List("Read", "Issue")).copy(category = "team")
    assert(!CoreProbe.allowed(teamExplicit).contains("Issue"), "team member listing Issue gets nothing")
    val flowExplicit = mkDef("node", List("Read", "Issue")).copy(category = "flow")
    assert(!CoreProbe.allowed(flowExplicit).contains("Issue"), "flow node listing Issue gets nothing")

  test("Issue stripped from non-Nebula via wildcard too"):
    val omni = mkDef("omni", List("*"))
    assert(!CoreProbe.allowed(omni).contains("Issue"), "wildcard does not resurrect Issue for non-Nebula")

  test("Nebula keeps Issue"):
    val nebula = mkDef("Nebula", List("Read"))
    assert(CoreProbe.allowed(nebula).contains("Issue"), "Issue survives only for the orchestrator")

  test("Nebula gets the §C.1 fixed toolset mechanism-fixed — no declaration needed (阶段 2c)"):
    // 阶段 2c agent 收敛（§C.1 角色-工具静态矩阵）：Nebula 工具面 = 固定十四件
    // （编排触发/通信/双轨期过渡/用户面/平台/记忆），机制注入不可配置。裸定义
    // （空 tools）必须携带完整矩阵——面板编辑/定义失误无法解除调度器武装。
    val orchestration = Set(
      "Task", "ProjectCreate", "NodeList", "AgentControl", // 编排触发（NodeList=2c 新增观测面）
      "Mail", "SendFriendMessage",                         // 通信（SendFriendMessage 2c 起机制固定）
      "Delegate", "FlowTrigger", "FlowExecute",            // 双轨期过渡（保留至阶段 3）
      "Pop", "AskUserQuestion",                            // 用户面
      "Schedule", "TransferFile",                          // 平台
      "MemoryEdit"                                         // 记忆（§C.2 新工具）
    )
    val bare = mkDef("Nebula", Nil)
    val allowed = CoreProbe.allowed(bare)
    orchestration.foreach(t =>
      assert(allowed.contains(t), s"mechanism-fixed orchestration tool missing: $t")
    )
    assert(allowed.contains("Issue"), "Nebula keeps Issue (feedback collector — 现状保留，未在 §C.1 矩阵处置)")

  test("Nebula 显式移除六件文件工具（§C.1 裁定 2/3）——声明也无效"):
    // 阶段 2c：Nebula 不读不写不跑命令。8684acd 文件声明与机制注入一并退役；
    // 收敛三定义的 tools 声明整体失效（ConvergedAgentNames base=∅）——即使
    // agent.json 显式列出 Read/Write 也不给。
    val legacyDeclared = mkDef("Nebula", List("Read", "Write", "Edit", "Glob", "Grep", "Bash"))
    val allowed = CoreProbe.allowed(legacyDeclared)
    val six = Set("Read", "Write", "Edit", "Glob", "Grep", "Bash")
    six.foreach(t => assert(!allowed.contains(t), s"Nebula must NOT have file tool: $t"))
    assert(!allowed.contains("MultiEdit"), "MultiEdit removed from ToolRegistry (阶段 2c)")
    // Web 系同样不在 §C.1 矩阵
    val webDeclared = mkDef("Nebula", List("WebSearch", "WebFetch", "Curl"))
    val webAllowed = CoreProbe.allowed(webDeclared)
    assert(!webAllowed.contains("WebSearch") && !webAllowed.contains("WebFetch") && !webAllowed.contains("Curl"),
      "Nebula: Web 系声明无效")

  test("project-dispatcher 固定工具集（§C.1）：Node 三件 + 读四件，声明无效"):
    val declared = mkDef("project-dispatcher", List("Write", "Edit", "AskUserQuestion"))
    val allowed = CoreProbe.allowed(declared, isFlowNode = true) // 分发器会话 spawn 即 isFlowNode=true
    Set("NodeList", "NodeEdit", "NodeCancel", "Read", "Glob", "Grep", "Bash").foreach { t =>
      assert(allowed.contains(t), s"dispatcher fixed tool missing: $t")
    }
    assert(!allowed.contains("Write"), "dispatcher 不给 Write（只分解不产内容，§C.1）")
    assert(!allowed.contains("Edit"), "dispatcher 不给 Edit")
    assert(!allowed.contains("AskUserQuestion"), "dispatcher 不给 AskUserQuestion（单次会话不阻塞等用户，§C.3）")
    assert(!allowed.contains("Mail"), "dispatcher 无 Mail")

  test("general 固定 8 件（§C.4/§C.5 裁定 5）——BaseTools + AskUserQuestion/Pop"):
    val bare = mkDef("general", Nil)
    val allowed = CoreProbe.allowed(bare, isFlowNode = true) // general 节点会话 isFlowNode=true
    val eight = Set("Read", "Glob", "Edit", "Write", "Grep", "Bash", "AskUserQuestion", "Pop")
    eight.foreach(t => assert(allowed.contains(t), s"general fixed tool missing: $t"))
    assert(!allowed.contains("Mail"), "general 无 Mail")
    assert(!allowed.contains("MultiEdit"), "general 无 MultiEdit（已从 ToolRegistry 删除）")
    // 声明无效（机制固定零配置）
    val sneaky = mkDef("general", List("WebSearch", "Delegate"))
    val sneakyAllowed = CoreProbe.allowed(sneaky)
    assert(!sneakyAllowed.contains("WebSearch") && !sneakyAllowed.contains("Delegate"),
      "general: tools 声明整体失效")

  test("the six remain mechanism-fixed for non-converged agents — cannot be configured away"):
    // 阶段 2c 只收敛 Nebula/dispatcher/general 三定义；team/flow/普通 standalone
    // 的 BaseTools 机制注入不变（双轨回归安全）。
    val six = Set("Read", "Write", "Edit", "Glob", "Grep", "Bash")
    val solo = mkDef("someone", Nil)
    val soloAllowed = CoreProbe.allowed(solo)
    six.foreach(t => assert(soloAllowed.contains(t), s"mechanism-fixed six missing for standalone: $t"))
    val team = mkDef("backend", Nil).copy(category = "team")
    val teamAllowed = CoreProbe.allowed(team)
    six.foreach(t => assert(teamAllowed.contains(t), s"mechanism-fixed six missing for team: $t"))
    val flow = mkDef("node", Nil).copy(category = "flow")
    val flowAllowed = CoreProbe.allowed(flow)
    six.foreach(t => assert(flowAllowed.contains(t), s"mechanism-fixed six missing for flow: $t"))

  test("the 9 orchestration tools are NOT granted to ordinary standalone agents"):
    // Mechanism-fixed for Nebula ≠ auto-granted to everyone: a standalone agent
    // with an empty tools list gets base tools only.
    val solo = mkDef("someone", Nil)
    val allowed = CoreProbe.allowed(solo)
    assert(!allowed.contains("AgentControl"), "no AgentControl for standalone")
    assert(!allowed.contains("Delegate"), "no Delegate for standalone")
    assert(!allowed.contains("Schedule"), "no Schedule for standalone")
    assert(allowed.contains("Read"), "base tools intact")

  // ===== 轨道二 #5: T1 flow-worker display-tool guardrails (dedicatedAgents) =====

  test("guardrails ON: flow node loses Pop/AskUserQuestion even when explicitly declared"):
    val defn = mkDef("qa-worker", List("Read", "Grep", "Bash", "Pop", "AskUserQuestion"))
    val allowed = CoreProbe.allowed(defn, isFlowNode = true, guardrailsOn = true)
    assert(!allowed.contains("Pop"), "Pop stripped at engine level (deck-v6 lesson)")
    assert(!allowed.contains("AskUserQuestion"), "AskUserQuestion stripped (nodes have nobody to ask)")
    assert(allowed.contains("Read"), "domain tools kept")

  test("guardrails OFF (default): declared Pop stays available to a flow node — zero regression"):
    val defn = mkDef("qa-worker", List("Read", "Grep", "Bash", "Pop", "AskUserQuestion"))
    val allowed = CoreProbe.allowed(defn, isFlowNode = true, guardrailsOn = false)
    assert(allowed.contains("Pop"), "flag off = pre-guardrail behavior byte-compatible")
    assert(allowed.contains("AskUserQuestion"))

  test("guardrails ON + userFacing:true whitelist keeps display tools"):
    val defn = mkDef("reviewer", List("Read", "Grep", "Pop"))
    val allowed = CoreProbe.allowed(defn, isFlowNode = true, userFacingNode = true, guardrailsOn = true)
    assert(allowed.contains("Pop"), "userFacing node is the whitelist escape hatch")
    val leafStripped = CoreProbe.allowed(defn.copy(tools = List("Read", "Grep")), isFlowNode = true, userFacingNode = true, guardrailsOn = true)
    assert(!leafStripped.contains("Pop"), "whitelist restores nothing extra — declaration remains the source")

  test("guardrails ON does not touch non-flow agents"):
    val solo = mkDef("explorer", List("Read", "Pop", "AskUserQuestion"))
    val allowed = CoreProbe.allowed(solo, guardrailsOn = true)
    assert(allowed.contains("Pop"), "T0 standalone untouched")
    assert(allowed.contains("AskUserQuestion"), "T0 standalone untouched")
    val worker = mkDef("backend", List("Read")).copy(category = "team")
    val member = CoreProbe.allowed(worker, guardrailsOn = true)
    assert(member.contains("Mail") && !member.contains("Pop"), "T2 team member unchanged this batch (fixed set never granted Pop)")

  test("SubTask workers keep their own strip list regardless of guardrails flag"):
    val worker = mkDef("backend", List("Read")).copy(category = "team")
    val on = CoreProbe.allowed(worker, isSubTaskWorker = true, guardrailsOn = true)
    val off = CoreProbe.allowed(worker, isSubTaskWorker = true, guardrailsOn = false)
    assertEquals(on, off, "flag must not alter the SubTask-worker path in any way")
end AllowedToolSetSpec
