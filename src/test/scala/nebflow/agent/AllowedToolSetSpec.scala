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
 *     agents EXCEPT Nebula (2026-09-05 23:34 作者裁定：Nebula 回归纯编排——
 *     Bash/Write/Edit 从 Nebula 集移除，Nebula 只携读三件 Read/Glob/Grep；
     *     六件全体默认对 general/legacy 侧不变)；agent.json declarations coexist
 *     idempotently
 *   - Issue is retired (2026-09-04 终裁: Issue/CheckIssues 退役, issue reporting
 *     via gh cli by nodes) — unregistered everywhere: no fixed set carries it,
 *     no schema is offered, calls fail with "No such tool available"
 *   - FlowTrigger / FlowExecute / FlowReport retired 2026-09-06（工具面裁撤批，
 *     作者裁定提前执行阶段 2d 子集）——unregistered everywhere: no fixed set
 *     carries them, no schema is offered, calls fail with "No such tool
 *     available"。flows/skills 声明解析保留（决策 A①，legacy 授能活到阶段 3），
 *     但不再驱动任何工具注入。
 *   - Nebula's orchestration tools are mechanism-fixed (no declaration
 *     needed) — §C.1 静态矩阵恰十四件（2026-09-06 00:48 作者裁定：NodeList
 *     摘除——节点结果沿 out 边自动投递，主动查图与裁定职责重叠；dispatcher
 *     自身面不受影响。2026-09-06 TaskList 批：+TaskList——Nebula 专属持久
 *     任务清单，快变状态出记忆）. Task tools retired (任务工具重做
 *     2026-08-30 — team-only).
 *   - Mail is team-only (auto-injected for team agents, never for flow/standalone)
 *   - SubTask is team-only (user ruling 2026-08-24: auto-injected at the
 *     mechanism layer — manual agent.json declarations are error-prone)
 *   - non-Nebula agents never get Nebula-exclusive tools (Schedule, Delegate,
 *     MemoryEdit, TaskList) — exception: dream is admitted for MemoryEdit (2026-09-05
 *     author ruling, AgentCore.DreamAdmittedTools; append still denied at the
 *     tool's action layer, DREAM_APPEND_DENIED)
 *   - SubTask workers (isSubTaskWorker=true) are leaf agents: no Mail /
 *     SubTask / Delegate regardless of their tools list
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

    /** Probe the actual LLM tool face (allowedSet ∩ registered schemas). */
    def face(defn: AgentDef): List[String] =
      buildToolList(defn).getOrElse(Nil).map(_.name)

  private def mkDef(name: String, tools: List[String], mcpServers: List[String] = Nil): AgentDef =
    AgentDef(name = name, description = "", tools = tools, systemPrompt = "", mcpServers = mcpServers)

  // ===== 2026-09-06 工具面裁撤批：flow 三工具退役（钉死断言①）=====

  val RetiredFlowTools = Set("FlowTrigger", "FlowExecute", "FlowReport")

  test("retired flow tools are unregistered — no schema anywhere (2026-09-06)"):
    RetiredFlowTools.foreach { t =>
      assert(!ToolRegistry.TOOL_MAP.contains(t), s"$t unregistered (retired = 注册层不挂)")
      assert(!ToolRegistry.ALL_TOOLS.exists(_.name == t), s"$t absent from ALL_TOOLS")
    }

  test("retired flow tools are on NO agent's LLM tool face — team/flow/wildcard/Nebula (2026-09-06)"):
    // 工具面 = buildToolList（allowedSet ∩ ALL_TOOLS）。任何类别、任何声明
    // 形态（显式/通配/flows 白名单）都不得再见到三工具 schema。
    val faces = List(
      "team" -> CoreProbe.face(mkDef("backend", List("*")).copy(category = "team")),
      "flow" -> CoreProbe.face(mkDef("scanner", List("*")).copy(category = "flow")),
      "flow+declared" -> CoreProbe.face(mkDef("scanner", List("Read", "FlowReport", "FlowTrigger", "FlowExecute")).copy(category = "flow")),
      "flows-whitelist" -> CoreProbe.face(mkDef("scheduler", List("*")).copy(flows = List("code-review"))),
      "standalone" -> CoreProbe.face(mkDef("solo", List("Read", "FlowExecute"))),
      "nebula" -> CoreProbe.face(mkDef("Nebula", List("*")))
    )
    faces.foreach { (who, tools) =>
      RetiredFlowTools.foreach { t =>
        assert(!tools.contains(t), s"$who must not see retired tool $t (got: $tools)")
      }
    }

  test("team agent concrete tools list yields those tools + base + Mail + SubTask + TeamTask*"):
    val defn = mkDef("researcher", List("Read", "Glob", "Grep")).copy(category = "team")
    val allowed = CoreProbe.allowed(defn)
    assertEquals(
      allowed,
      Set("Read", "Glob", "Grep", "Write", "Edit", "Bash", "Mail", "SubTask",
        "TeamTaskCreate", "TeamTaskUpdate", "TeamTaskList")
    )

  test("standalone agent gets base fixed tools but NOT Mail"):
    val defn = mkDef("solo", List("Read", "Pop"))
    val allowed = CoreProbe.allowed(defn)
    assert(allowed.contains("Read"))
    assert(allowed.contains("Pop"))
    assert(!allowed.contains("Issue"), "Issue retired (2026-09-04) — standalone never has it")
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
    assert(!standaloneAllowed.contains("Issue"), "Issue retired (2026-09-04) — not a base tool")
    assert(standaloneAllowed.contains("Write"), "Write is a base tool")

    val teamDefn = mkDef("teammate", List("Read")).copy(category = "team")
    val teamAllowed = CoreProbe.allowed(teamDefn)
    assert(teamAllowed.contains("Mail"), "team agent gets Mail")
    assert(!teamAllowed.contains("Issue"), "team agent has no Issue (retired 2026-09-04)")

  test("retired FlowReport is on no category's fixed face — flow included (2026-09-06)"):
    val flowDefn = mkDef("reviewer", List("Read")).copy(category = "flow")
    val teamDefn = mkDef("backend", List("Read")).copy(category = "team")
    val stdDefn = mkDef("explorer", List("Read")).copy(category = "standalone")
    assert(!CoreProbe.allowed(flowDefn).contains("FlowReport"), "flow agent: FlowReport retired from fixed face")
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
    // 2026-09-05 08:40 作者裁定：旧体系对 Nebula 完全退役——Delegate 声明
    // （converged 面声明本就失效）与机制固定携带一并移除。
    assert(!CoreProbe.allowed(nebula).contains("Delegate"), "Nebula no longer carries Delegate (2026-09-05 旧体系退役)")
    assert(!CoreProbe.allowed(nebula).contains("SubTask"), "Nebula does not need SubTask (unless listed)")
    assert(!CoreProbe.allowed(teamAgent).contains("Delegate"), "non-Nebula never gets Delegate")
    assert(CoreProbe.allowed(teamAgent).contains("SubTask"), "team agent with SubTask listed keeps it")
    // Delegate stripped for non-Nebula at all depths
    val anyone = mkDef("anyone", List("Read", "Delegate"))
    assert(!CoreProbe.allowed(anyone, depth = 0).contains("Delegate"), "depth 0")
    assert(!CoreProbe.allowed(anyone, depth = 1).contains("Delegate"), "depth 1")
    assert(!CoreProbe.allowed(anyone, depth = 2).contains("Delegate"), "depth 2")

  test("SubTask workers are leaf agents — Mail/SubTask/Delegate stripped even when listed"):
    val worker = mkDef("backend", List("Read", "Mail", "SubTask", "Delegate"))
    val allowed = CoreProbe.allowed(worker, isSubTaskWorker = true)
    assert(allowed.contains("Read"), "domain tools kept")
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
    assert(!allowed.contains("SubTask"), "Nebula (standalone category) unchanged — no SubTask")
    // 2026-09-05 08:40 作者裁定：Delegate 已从 Nebula 固定面移除。
    assert(!allowed.contains("Delegate"), "Nebula no longer keeps Delegate (2026-09-05 旧体系退役)")

  // ===== Flow agents: Mail structurally disabled (08-14 P0 root cause) =====

  test("flow agent with '*' wildcard never gets Mail (P0 penetration case)"):
    val flowWildcard = mkDef("scanner", List("*")).copy(category = "flow")
    val allowed = CoreProbe.allowed(flowWildcard)
    assert(!allowed.contains("Mail"), "flow agent with '*' must not get Mail — a flow agent calling Mail(ask) blocks forever (08-14 P0)")
    assert(!allowed.contains("FlowReport"), "flow agent: FlowReport retired (2026-09-06)")
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
    // block is flow-only (flow nodes report plain-text output to the executor
    // since the FlowReport verdict channel retired 2026-09-06).
    val solo = mkDef("solo", List("Read", "Mail"))
    assert(CoreProbe.allowed(solo).contains("Mail"), "standalone agent explicitly listing Mail keeps it")

  // ===== FlowTrigger/FlowExecute retirement (2026-09-06 工具面裁撤批) =====
  // 决策 A①：flows 声明解析保留（legacy 授能字段活到阶段 3），但不再驱动任何
  // 工具注入；三工具注册摘除后名字至多是惰性字符串（无 schema、无执行路径）。

  test("flows whitelist no longer injects FlowTrigger — declared or not (2026-09-06)"):
    val withFlows = mkDef("scheduler", List("Read")).copy(flows = List("code-review"))
    val without = mkDef("plain", List("Read"))
    assert(!CoreProbe.allowed(withFlows).contains("FlowTrigger"), "flows declared → no tool (retired)")
    assert(!CoreProbe.allowed(without).contains("FlowTrigger"), "no flows → no tool")
    val wildcardWithFlows = mkDef("omni-flow", List("*")).copy(flows = List("release-beta"))
    assert(!CoreProbe.allowed(wildcardWithFlows).contains("FlowTrigger"), "wildcard WITH flows → still no tool (retired)")

  test("FlowTrigger absent from every fixed face — Nebula included (2026-09-06)"):
    val teamMember = mkDef("backend", List("Read")).copy(category = "team", flows = List("code-review"))
    assert(!CoreProbe.allowed(teamMember).contains("FlowTrigger"), "team member: retired tool absent")
    val nebula = mkDef("Nebula", List("Read")).copy(flows = List("code-review"))
    assert(!CoreProbe.allowed(nebula).contains("FlowTrigger"), "Nebula no longer gets FlowTrigger even with flows (2026-09-05 旧体系退役)")
    val nebulaNoFlows = mkDef("Nebula", Nil)
    assert(!CoreProbe.allowed(nebulaNoFlows).contains("FlowTrigger"), "Nebula fixed set has no FlowTrigger (2026-09-05 旧体系退役)")

  test("SubTask workers and the same def as a normal agent both lack FlowTrigger (retired)"):
    val worker = mkDef("backend", List("*")).copy(flows = List("code-review"))
    assert(
      !CoreProbe.allowed(worker, isSubTaskWorker = true).contains("FlowTrigger"),
      "workers are leaf agents — no pipeline triggering"
    )
    assert(
      !CoreProbe.allowed(worker, isSubTaskWorker = false).contains("FlowTrigger"),
      "retired tool is absent for non-workers too (2026-09-06)"
    )

  // ===== FlowExecute retirement (2026-09-06 工具面裁撤批) =====

  test("team member WITHOUT explicit FlowExecute does not get it (retired from fixed face)"):
    val defn = mkDef("backend", List("Read", "Grep", "Bash")).copy(category = "team")
    val allowed = CoreProbe.allowed(defn)
    assert(!allowed.contains("FlowExecute"), "team member: FlowExecute retired (2026-09-06)")
    assert(allowed.contains("SubTask"), "team member keeps SubTask")
    assert(allowed.contains("Mail"), "team member keeps Mail")

  test("team member with '*' wildcard does not get FlowExecute (retired)"):
    val defn = mkDef("omni-team", List("*")).copy(category = "team")
    val allowed = CoreProbe.allowed(defn)
    assert(!allowed.contains("FlowExecute"), "wildcard team member: FlowExecute retired (2026-09-06)")

  test("Nebula does not get FlowExecute (2026-09-05 旧体系退役)"):
    // 原口径「Nebula (root) gets FlowExecute without declaration」随
    // 2026-09-05 08:40 作者裁定废止：旧 Team/Flow 体系对 Nebula 完全退役，
    // FlowExecute/Delegate 均不在 Nebula 固定面。
    val nebula = mkDef("Nebula", List("Read", "Delegate"))
    val allowed = CoreProbe.allowed(nebula)
    assert(!allowed.contains("FlowExecute"), "Nebula no longer carries FlowExecute (2026-09-05 旧体系退役)")
    assert(!allowed.contains("Delegate"), "Nebula no longer carries Delegate (2026-09-05 旧体系退役)")

  test("standalone agents do NOT get FlowExecute"):
    val solo = mkDef("solo", List("Read", "Grep"))
    assert(!CoreProbe.allowed(solo).contains("FlowExecute"), "standalone agent has no FlowExecute by default")

  test("flow-category agents do NOT get FlowExecute (predefined flow nodes are leaves too)"):
    val flow = mkDef("scanner", List("Read", "Grep")).copy(category = "flow")
    val allowed = CoreProbe.allowed(flow)
    assert(!allowed.contains("FlowExecute"), "predefined flow node is a leaf — no nested flow execution")

  test("isFlowNode leaf rule keeps stripping SubTask/Delegate; retired flow tools absent regardless"):
    val teamNode = mkDef("backend", List("Read", "Grep")).copy(category = "team")
    val allowed = CoreProbe.allowed(teamNode, isFlowNode = true)
    assert(!allowed.contains("FlowExecute"), "flow node: FlowExecute retired")
    assert(!allowed.contains("FlowTrigger"), "flow node: FlowTrigger retired")
    assert(!allowed.contains("SubTask"), "flow node: SubTask stripped")
    assert(!allowed.contains("Delegate"), "flow node: Delegate stripped")
    assert(allowed.contains("Mail"), "flow node: team-category keeps Mail (may Mail the caller's team)")
    assert(allowed.contains("Read"), "flow node: domain tools kept")

  test("explicitly listed retired flow tools on a flow node are inert names (no schema, no execution)"):
    // 决策 A①：声明解析保留（名字留在 allowedSet 惰性字符串层），但注册已摘
    // ——buildToolList ∩ ALL_TOOLS 保证三工具不出现在 LLM 工具面（上面
    // "retired flow tools are on NO agent's LLM tool face" 锁定交付面）。
    val node = mkDef("worker", List("Read", "Grep", "FlowReport"))
    val allowed = CoreProbe.allowed(node, isFlowNode = true)
    assert(!allowed.contains("FlowExecute"), "flow node: no nested flow")

  test("isFlowNode leaf rule still strips explicitly listed SubTask"):
    val sneaky = mkDef("sneaky", List("Read", "SubTask")).copy(category = "team")
    val allowed = CoreProbe.allowed(sneaky, isFlowNode = true)
    assert(!allowed.contains("SubTask"), "explicitly listed SubTask stripped for flow nodes")

  test("SubTask workers also lack FlowExecute (retired from the team fixed face they cloned)"):
    // A SubTask worker self-clones a team-member def; since 2026-09-06 the
    // cloned fixed face no longer carries FlowExecute.
    val worker = mkDef("backend", List("Read", "Grep")).copy(category = "team")
    val allowed = CoreProbe.allowed(worker, isSubTaskWorker = true)
    assert(!allowed.contains("FlowExecute"), "worker: FlowExecute retired")
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

  test("Issue 退役（2026-09-04 终裁）——注册面零 Issue，Nebula 声明亦无效"):
    // 终裁：Issue/CheckIssues 退役，报 issue 走 gh cli 由节点代劳；定义层已归档。
    // 机制层收尾：NebulaExclusiveTools 不再含 Issue（剥离职责随工具消亡——未注册
    // 名无 schema、无执行路径，非 Nebula 声明仅是惰性字符串）；converged 三角色
    // 声明整体失效照旧。
    assert(!ToolRegistry.TOOL_MAP.contains("Issue"), "Issue 未注册（退役=注册层不挂）")
    assert(!ToolRegistry.ALL_TOOLS.exists(_.name == "Issue"), "ALL_TOOLS 零 Issue")
    val nebulaExplicit = mkDef("Nebula", List("Read", "Issue"))
    val nebulaAllowed = CoreProbe.allowed(nebulaExplicit)
    assert(!nebulaAllowed.contains("Issue"), "Nebula：Issue 声明无效（已退役，不再 parity carry）")
    assert(nebulaAllowed.contains("Read"), "Nebula：Read 机制固定携带（2026-09-05 基础四件解禁；声明对 converged 面本就无效）")

  test("Issue stripped from non-Nebula via wildcard too"):
    val omni = mkDef("omni", List("*"))
    assert(!CoreProbe.allowed(omni).contains("Issue"), "wildcard cannot resurrect a retired, unregistered tool")

  test("Issue 退役——Nebula 也不再持有（2026-09-04 终裁）"):
    val nebula = mkDef("Nebula", List("Read"))
    assert(!CoreProbe.allowed(nebula).contains("Issue"), "Nebula fixedTools 零 Issue（parity carry 已删）")

  test("NodeList 退役——Nebula 面不再携带、声明无效；dispatcher 自身面不受影响（2026-09-06 00:48 裁定）"):
    // 节点结果沿 out 边自动投递 Nebula，主动查图与「全量派发 + pending 节点、
    // 不维护状态清单」的裁定职责重叠——Nebula 面摘除。边界：本批只动 agent
    // 工具面，dispatcher 固定集（DispatcherFixedTools）照旧携带；NodeList
    // 服务端 API / 前端 Flow Map / 工具本体零改动。
    val nebula = mkDef("Nebula", List("NodeList"))
    assert(!AgentCore.fixedToolsFor(nebula).contains("NodeList"), "Nebula 机制集零 NodeList（00:48 裁定）")
    assert(!CoreProbe.allowed(nebula).contains("NodeList"), "Nebula 交付面零 NodeList（声明亦无效——机制固定集不含即不授能）")
    assert(AgentCore.DispatcherFixedTools.contains("NodeList"), "dispatcher 固定集保留 NodeList（边界：仅摘 agent 工具面）")

  test("Nebula gets the §C.1 fixed toolset mechanism-fixed — no declaration needed (阶段 2c)"):
    // 阶段 2c agent 收敛（§C.1 角色-工具静态矩阵）：Nebula 工具面 = 固定集
    // （2026-09-05 23:34 作者裁定：Nebula 回归纯编排——Bash/Write/Edit 移除；
    // 2026-09-06 00:48 作者裁定：NodeList 摘除——out 边自动投递取代主动查图；
    // 2026-09-06 TaskList 批：+TaskList——恰十四件：编排触发/任务编排/通信/
    // 读三件/可视化/用户面/平台/记忆），机制注入不可配置。裸定义（空 tools）
    // 必须携带完整矩阵——面板编辑/定义失误无法解除调度器武装。
    val orchestration = Set(
      "Task", "ProjectCreate", "AgentControl",             // 编排触发（NodeList 00:48 裁定摘除）
      "TaskList",                                          // 任务编排（TaskList 批：快变状态出记忆）
      "SendFriendMessage",                                 // 通信（好友功能非旧体系，保留）
      "Read", "Glob", "Grep",                              // 读三件（08:40 解禁四件；23:34 收走写手）
      "Card",                                              // 可视化（2026-09-05 解封恢复）
      "Pop", "AskUserQuestion",                            // 用户面
      "Schedule", "TransferFile",                          // 平台
      "MemoryEdit"                                         // 记忆（§C.2 新工具）
    )
    val bare = mkDef("Nebula", Nil)
    val allowed = CoreProbe.allowed(bare)
    orchestration.foreach(t =>
      assert(allowed.contains(t), s"mechanism-fixed orchestration tool missing: $t")
    )
    assert(!allowed.contains("Issue"), "恰十四件、零 Issue（2026-09-04 终裁：Issue/CheckIssues 退役）")
    assert(!allowed.contains("NodeList"), "恰十四件、零 NodeList（2026-09-06 00:48 裁定摘除）")
    Set("Mail", "Delegate", "FlowTrigger", "FlowExecute").foreach { t =>
      assert(!allowed.contains(t), s"旧体系四件已从 Nebula 固定面退役（2026-09-05 08:40 作者裁定）: $t")
    }
    // 钉死断言（2026-09-05 23:34 作者裁定）：Nebula 机制集不含 Bash、不含
    // Write、不含 Edit——变异验红锚
    assert(!allowed.contains("Bash"), "Nebula 无写手：Bash 已移除（23:34 裁定）")
    assert(!allowed.contains("Write"), "Nebula 无写手：Write 已移除（23:34 裁定）")
    assert(!allowed.contains("Edit"), "Nebula 无写手：Edit 已移除（23:34 裁定）")

  // ===== TaskList 工具面隔离（2026-09-06 TaskList 批，硬约束）=====
  // Nebula 专属编排件：仅 NebulaOrchestrationTools 携带（+1，恰十四件）；
  // dispatcher（DispatcherFixedTools）/ general（BaseTools+Pop）与一切非
  // Nebula 身份（含 "*" 声明、dream、SubTask worker、flow 节点）零出现。

  test("TaskList 仅 Nebula（工具面总数=原数目+1 仅此一件）：dispatcher/general 固定面均不含"):
    // Nebula 面 +1
    val nebulaFixed = AgentCore.fixedToolsFor(mkDef("Nebula", Nil))
    assert(nebulaFixed.contains("TaskList"), "Nebula 机制集含 TaskList")
    // dispatcher 固定集不含（分发器只分解不维护 Nebula 私有任务清单）
    assert(!AgentCore.DispatcherFixedTools.contains("TaskList"), "dispatcher 固定集零 TaskList")
    assert(!CoreProbe.allowed(mkDef("project-dispatcher", Nil), isFlowNode = true).contains("TaskList"),
      "dispatcher 交付面零 TaskList")
    // general 固定集不含
    assert(!AgentCore.GeneralFixedTools.contains("TaskList"), "general 固定集零 TaskList")
    assert(!CoreProbe.allowed(mkDef("general", Nil), isFlowNode = true).contains("TaskList"),
      "general 交付面零 TaskList")

  test("TaskList 防声明逃逸：非 Nebula 显式声明与 '*' 通配均剥离（NebulaExclusiveTools）"):
    assert(AgentCore.NebulaExclusiveTools.contains("TaskList"), "TaskList 进 NebulaExclusiveTools（剥离语义单点）")
    val declared = CoreProbe.allowed(mkDef("sneaky", List("Read", "TaskList")))
    assert(!declared.contains("TaskList"), "standalone 显式声明无效")
    val wildcard = CoreProbe.allowed(mkDef("omni", List("*")))
    assert(!wildcard.contains("TaskList"), "wildcard 剥离")
    val team = CoreProbe.allowed(mkDef("member", List("*")).copy(category = "team"))
    assert(!team.contains("TaskList"), "team 成员剥离")
    val worker = CoreProbe.allowed(mkDef("w", List("*")).copy(category = "team"), isSubTaskWorker = true)
    assert(!worker.contains("TaskList"), "SubTask worker 剥离")
    val flow = CoreProbe.allowed(mkDef("f", List("*")).copy(category = "flow"))
    assert(!flow.contains("TaskList"), "flow 节点剥离")
    // dream 无豁免（豁免面恰为 MemoryEdit 一件，TaskList 对 dream 照剥）
    val dream = CoreProbe.allowed(mkDef("dream", List("TaskList")))
    assert(!dream.contains("TaskList"), "dream 声明 TaskList 无效（非 DreamAdmittedTools）")

  test("Nebula 文件工具面（2026-09-05 23:34 裁定）：读三件机制固定、写手声明依然无效"):
    // 23:34 作者裁定：Nebula 回归纯编排——Bash/Write/Edit 移出机制集，文件
    // 面只余读三件 Read/Glob/Grep；general/BaseTools 六件默认注入不变（Nebula
    // 唯一例外）。converged 定义 tools 声明整体失效（base=∅）不变——声明
    // Write/Edit/Bash 依旧 no-op（既不因声明授能，机制集也不再携带）。
    val legacyDeclared = mkDef("Nebula", List("Read", "Write", "Edit", "Glob", "Grep", "Bash"))
    val allowed = CoreProbe.allowed(legacyDeclared)
    Set("Read", "Glob", "Grep").foreach(t => assert(allowed.contains(t), s"Nebula must have read tool: $t"))
    Set("Bash", "Write", "Edit").foreach { t =>
      assert(!allowed.contains(t), s"Nebula: writer tool not granted (23:34 裁定): $t")
    }
    assert(!allowed.contains("MultiEdit"), "MultiEdit removed from ToolRegistry (阶段 2c)")
    // Web 系同样不在 §C.1 矩阵
    val webDeclared = mkDef("Nebula", List("WebSearch", "WebFetch", "Curl"))
    val webAllowed = CoreProbe.allowed(webDeclared)
    assert(!webAllowed.contains("WebSearch") && !webAllowed.contains("WebFetch") && !webAllowed.contains("Curl"),
      "Nebula: Web 系声明无效")

  test("Card 仅授 Nebula（2026-09-05 解封）——legacy team/flow/catch-all 均不授"):
    // Card 恢复自 793f62c1 删除（2026-09-05 08:40 作者裁定）。授能面 =
    // NebulaOrchestrationTools 单一来源；legacy 路径（双轨期保留）不携带。
    assert(AgentCore.legacyFixedTools(mkDef("member", Nil).copy(category = "team")).contains("Mail"),
      "legacy team 成员照旧携带 Mail（双轨期保留面，本断言证明该路径活跃）")
    assert(!AgentCore.legacyFixedTools(mkDef("member", Nil).copy(category = "team")).contains("Card"), "legacy team 成员不授 Card")
    assert(!AgentCore.legacyFixedTools(mkDef("leaf", Nil).copy(category = "flow")).contains("Card"), "legacy flow 节点不授 Card")
    assert(!AgentCore.legacyFixedTools(mkDef("standalone-x", Nil)).contains("Card"), "legacy catch-all（BaseTools）不授 Card")

  test("project-dispatcher 固定工具集（§C.1 + NodeMessage 20260905 机制批）：Node 四件 + 读四件，声明无效"):
    val declared = mkDef("project-dispatcher", List("Write", "Edit", "AskUserQuestion"))
    val allowed = CoreProbe.allowed(declared, isFlowNode = true) // 分发器会话 spawn 即 isFlowNode=true
    Set("NodeList", "NodeEdit", "NodeCancel", "NodeMessage", "Read", "Glob", "Grep", "Bash").foreach { t =>
      assert(allowed.contains(t), s"dispatcher fixed tool missing: $t")
    }
    assert(!allowed.contains("Write"), "dispatcher 不给 Write（只分解不产内容，§C.1）")
    assert(!allowed.contains("Edit"), "dispatcher 不给 Edit")
    assert(!allowed.contains("AskUserQuestion"), "dispatcher 不给 AskUserQuestion（单次会话不阻塞等用户，§C.3）")
    assert(!allowed.contains("Mail"), "dispatcher 无 Mail")

  test("general 固定 7 件（§C.4/§C.5 裁定 5 − 2026-09-06 节点面摘除 AskUser）——BaseTools + Pop"):
    val bare = mkDef("general", Nil)
    val allowed = CoreProbe.allowed(bare, isFlowNode = true) // general 节点会话 isFlowNode=true
    val seven = Set("Read", "Glob", "Edit", "Write", "Grep", "Bash", "Pop")
    seven.foreach(t => assert(allowed.contains(t), s"general fixed tool missing: $t"))
    // 钉死断言（2026-09-06 作者提议 + Nebula 背书裁定）：general 默认面不含
    // AskUserQuestion——交互出口统一（作者触点 = Flow Map pending 节点 +
    // Nebula 汇报；节点确认点 = 建 pending 节点/BLOCKED 回投）——变异验红锚
    assert(!allowed.contains("AskUserQuestion"), "general 默认面零 AskUserQuestion（2026-09-06 节点面摘除）")
    assert(!allowed.contains("Mail"), "general 无 Mail")
    assert(!allowed.contains("MultiEdit"), "general 无 MultiEdit（已从 ToolRegistry 删除）")
    // 声明无效（机制固定零配置）
    val sneaky = mkDef("general", List("WebSearch", "Delegate"))
    val sneakyAllowed = CoreProbe.allowed(sneaky)
    assert(!sneakyAllowed.contains("WebSearch") && !sneakyAllowed.contains("Delegate"),
      "general: tools 声明整体失效")
    // Nebula / dispatcher 面不受本裁定影响（对照钉死）
    assert(AgentCore.fixedToolsFor(mkDef("Nebula", Nil)).contains("AskUserQuestion"),
      "Nebula 面保留 AskUserQuestion（本裁定只动 general 节点面）")
    assert(!AgentCore.fixedToolsFor(mkDef("project-dispatcher", Nil)).contains("AskUserQuestion"),
      "dispatcher 面照旧无 AskUserQuestion（§C.3，不受本裁定影响）")

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
