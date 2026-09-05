package nebflow.agent

import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite
import nebflow.core.tools.ToolRegistry

/**
 * 阶段 2d 工具体系改造 spec（设计文档 §D.1 #1/#9/#11 + §D.2，20260902 设计 §G.4）。
 *
 * - D.1-1：fixedToolsFor 收口——三角色直接返回静态集常量（工具面逐件不变，
 *   与 AgentConvergenceSpec 互为表里）；legacy 分支（team/flow/catch-all）
 *   原样保留（双轨期），三角色 name 分支已从 legacy 路径删除。
 * - D.1-11：SendFriendMessage 声明式注入通道删除——显式声明与 "*" 均不再
 *   授能，Nebula 静态集照常携带。
 * - D.1-9：converged 三角色 agent.json mcpServers 声明退役（机制层与 tools
 *   声明同批失效）；plugin MCP 前缀追加语义补 spec 钉（§B.4 既有命名
 *   mcp__plugin_<p>_<s>__<t>，链路 E2E 见 NodePluginChainSpec）。
 * - §D.2：条件段下迁后 description 自包含——AskUserQuestion/Read/Pop/
 *   TeamTask 三件各含下迁文本关键句；PromptSections 不再含旧段（PromptSectionsSpec
 *   反向断言）。
 */
class Phase2dToolRefactorSpec extends FunSuite:

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

  private def mkDef(name: String, tools: List[String] = Nil): AgentDef =
    AgentDef(name = name, description = "", tools = tools)

  // ===== D.1-1：三角色静态集收口，工具面逐件不变 =====

  test("D.1-1: Nebula fixed set == §C.1 恰十七件、零 Issue、零旧体系四件（逐件不变）"):
    val fixed = AgentCore.fixedToolsFor(mkDef("Nebula"))
    val expected =
      Set("Task", "ProjectCreate", "NodeList", "AgentControl",
        "SendFriendMessage",
        "Bash", "Read", "Glob", "Grep", "Write", "Edit",       // 基础六件（08:40 解禁四件；13:11 裁定补齐 Write/Edit）
        "Card",                                               // 可视化（2026-09-05 解封恢复）
        "AskUserQuestion", "Pop",
        "Schedule", "TransferFile",
        "MemoryEdit")
    assertEquals(fixed, expected,
      "Nebula 静态集恰十七件（08:40 作者裁定：+基础四件/+Card 解封/−Mail/Delegate/FlowTrigger/FlowExecute 旧体系退役；13:11 作者裁定：+Write/Edit 补齐基础六件；2026-09-04 终裁：Issue/CheckIssues 退役）")
    assert(!fixed.contains("Issue"), "Nebula fixedTools 零 Issue（2026-09-04 终裁退役）")
    Set("Mail", "Delegate", "FlowTrigger", "FlowExecute").foreach { t =>
      assert(!fixed.contains(t), s"旧体系四件已从 Nebula 固定面退役（2026-09-05 裁定）: $t")
    }
    assert(fixed.contains("Write") && fixed.contains("Edit"),
      "基础六件补齐含 Write/Edit（2026-09-05 13:11 裁定：基础六件为全体 agent 统一默认工具集）")

  test("D.1-1: dispatcher fixed set == Node 三件 + 读四件（逐件不变）"):
    assertEquals(
      AgentCore.fixedToolsFor(mkDef("project-dispatcher")),
      Set("NodeList", "NodeEdit", "NodeCancel", "Read", "Glob", "Grep", "Bash")
    )

  test("D.1-1: general fixed set == 裁定 5 八件（逐件不变）"):
    assertEquals(
      AgentCore.fixedToolsFor(mkDef("general")),
      Set("Read", "Glob", "Edit", "Write", "Grep", "Bash", "AskUserQuestion", "Pop")
    )

  test("D.1-1: legacy 路径不再含三角色 name 分支——catch-all 对三角色名生效"):
    // legacyFixedTools 是纯 category 函数：三角色名传入时走 catch-all BaseTools
    // （即 name 分支已删）。生产路径 fixedToolsFor 对三角色派发静态集（上面的
    // 断言），二者分层即「收口」的结构证明。
    assertEquals(AgentCore.legacyFixedTools(mkDef("Nebula")), AgentCore.BaseTools,
      "legacy 路径对 Nebula 名返回 catch-all——name 分支已删除")
    assertEquals(AgentCore.legacyFixedTools(mkDef("project-dispatcher")), AgentCore.BaseTools)
    assertEquals(AgentCore.legacyFixedTools(mkDef("general")), AgentCore.BaseTools)

  test("D.1-1: legacy 双轨期分支原样——team/flow/catch-all 逐件不变"):
    val team = AgentCore.legacyFixedTools(mkDef("backend").copy(category = "team"))
    assertEquals(team, AgentCore.BaseTools + "Mail" + "SubTask" + "FlowExecute" ++ AgentCore.TeamTaskTools)
    val flow = AgentCore.legacyFixedTools(mkDef("node").copy(category = "flow"))
    assertEquals(flow, AgentCore.BaseTools + "FlowReport")
    val solo = AgentCore.legacyFixedTools(mkDef("Coder"))
    assertEquals(solo, AgentCore.BaseTools,
      "legacy standalone catch-all 保留（Coder/Explorer/design-engineer 依赖，阶段 3 删）")

  test("D.1-1: buildAllowedToolSet 三角色交付面 == 静态集（LLM 面，注册表过滤后）"):
    val nebulaDelivered = CoreProbe.allowed(mkDef("Nebula"))
    assert(nebulaDelivered.contains("MemoryEdit") && nebulaDelivered.contains("NodeList"))
    // 2026-09-05 08:40 作者裁定：基础四件解禁（Read/Bash 机制固定携带）；
    // 13:11 作者裁定：+Write/Edit 补齐基础六件（全体 agent 统一默认工具集）。
    assert(nebulaDelivered.contains("Read") && nebulaDelivered.contains("Bash"),
      "Nebula 携带基础四件（2026-09-05 解禁）")
    assert(nebulaDelivered.contains("Write") && nebulaDelivered.contains("Edit"),
      "Nebula 补齐 Write/Edit（2026-09-05 13:11 裁定：基础六件为全体 agent 统一默认）")
    val generalDelivered = CoreProbe.allowed(mkDef("general"), isFlowNode = true)
    assertEquals(generalDelivered, AgentCore.GeneralFixedTools, "general 节点形态交付面 == 静态 8 件")
    val dispatcherDelivered = CoreProbe.allowed(mkDef("project-dispatcher"), isFlowNode = true)
    assertEquals(dispatcherDelivered, AgentCore.DispatcherFixedTools, "dispatcher 交付面 == 静态 7 件")

  // ===== D.1-11：SendFriendMessage 声明通道删除 =====

  test("D.1-11: standalone 显式声明 SendFriendMessage 不再授能"):
    val declared = CoreProbe.allowed(mkDef("social", List("Read", "SendFriendMessage")))
    assert(!declared.contains("SendFriendMessage"), "声明通道已删——声明不授能")
    val wildcard = CoreProbe.allowed(mkDef("omni", List("*")))
    assert(!wildcard.contains("SendFriendMessage"), "\"*\" 同样不授能")
    assert(CoreProbe.allowed(mkDef("social", List("Read"))).contains("Read"), "其余声明不受影响")

  test("D.1-11: Nebula 机制固定照常携带 SendFriendMessage"):
    assert(CoreProbe.allowed(mkDef("Nebula")).contains("SendFriendMessage"),
      "机制固定是唯一授权源（静态集）")

  test("D.1-11: registry 注册名不变（工具本身保留）"):
    assert(ToolRegistry.TOOL_MAP.contains("SendFriendMessage"))

  // ===== D.1-9：converged mcpServers 声明退役 + plugin 前缀语义钉 =====

  test("D.1-9: converged 三角色 mcpServers 声明退役——注册的 mcp 工具不因声明而放行"):
    // 机制语义：mcpServers 是对「已声明 mcp__ 工具」的过滤授权（legacy 路径：
    // tools 声明 + mcpServers 授权 → 放行）。converged 退役 = 声明+授权双双
    // 失效：tools 声明进不了 base（ConvergedAgentNames），mcpServers 进不了
    // 过滤授权（effectiveMcpServers=Nil）。
    val probe = "mcp__opendataloader-pdf__parse"
    ToolRegistry.registerTool(new nebflow.core.tools.ScriptTool(
      nebflow.core.tools.ExternalToolConfig(
        name = probe,
        description = "2d spec probe",
        command = "true",
        inputSchema = io.circe.JsonObject.empty
      ),
      os.pwd
    ))
    try
      val declared = CoreProbe.allowed(mkDef("Nebula", List(probe)).copy(mcpServers = List("opendataloader-pdf")))
      assert(!declared.contains(probe), "converged 定义：tools 声明 + mcpServers 授权均已退役")
      val legacy = CoreProbe.allowed(mkDef("pdfagent", List(probe)).copy(mcpServers = List("opendataloader-pdf")))
      assert(legacy.contains(probe), "legacy agent 的 mcpServers 授权双轨期保留")
    finally ToolRegistry.unregisterTool(probe)

  test("D.1-9: plugin MCP 前缀追加语义（§B.4-③ spec 钉；链路 E2E 见 NodePluginChainSpec）"):
    val probe = "mcp__plugin_p2d_probe__tool"
    ToolRegistry.registerTool(new nebflow.core.tools.ScriptTool(
      nebflow.core.tools.ExternalToolConfig(
        name = probe,
        description = "2d plugin prefix probe",
        command = "true",
        inputSchema = io.circe.JsonObject.empty
      ),
      os.pwd
    ))
    try
      val nodeAgent = mkDef("general").copy(pluginMcpServers = List("plugin_p2d_probe"))
      val allowed = CoreProbe.allowed(nodeAgent, isFlowNode = true)
      assert(allowed.contains(probe), "pluginMcpServers 前缀是 converged 角色唯一的 MCP 授能通道")
      val unallocated = CoreProbe.allowed(mkDef("general"), isFlowNode = true)
      assert(!unallocated.contains(probe), "未分配 plugin 的 general 会话不得见 plugin 工具")
    finally ToolRegistry.unregisterTool(probe)

  // ===== §D.2：description 自包含（下迁文本关键句） =====

  test("D.2: AskUserQuestion description 含提问指南关键句（order 400 下迁）"):
    val d = ToolRegistry.TOOL_MAP("AskUserQuestion").description
    assert(d.contains("never ask clarifying questions in plain text"), "核心规则在 description")
    assert(d.contains("When NOT to use"), "反模式段（§D.2 样式：功能+用法+反模式）")
    assert(d.contains("let the user correct course"), "不提问判据文本")
    assert(d.contains("dependsOn"), "条件分支用法")
    assert(d.contains("Rule of thumb"), "顺序提问→单调用问题树")

  test("D.2: Read description 含 live 语义关键句（order 410 下迁）"):
    val d = ToolRegistry.TOOL_MAP("Read").description
    assert(d.contains("Live results"), "live 语义段")
    assert(d.contains("Never re-read"), "免重读规则")
    assert(d.contains("git diff"), "历史对比手法")
    assert(d.contains("exact-match"), "Edit 安全联动")

  test("D.2: Pop description 含可视化汇报工作流关键句（order 415 下迁）"):
    val d = ToolRegistry.TOOL_MAP("Pop").description
    assert(d.contains("professional tool"), "专业工具→SVG→Pop 句")
    assert(d.contains("SVG"), "格式偏好")
    assert(d.contains("never hand-draw"), "反模式（ASCII 手绘）")
    assert(d.contains("Canvas"), "呈现面")

  test("D.2: TeamTask 三件 description 含任务协议关键句（order 630 下迁，双轨期）"):
    val list = ToolRegistry.TOOL_MAP("TeamTaskList").description
    assert(list.contains("<system-reminder>"), "reminder 到达协议")
    assert(list.contains("[waiting-user]"), "人类 todo 语义")
    assert(list.contains("auto-expire"), "TTL 生命周期")
    assert(list.contains("fold into a count line"), "截断折叠语义")
    val update = ToolRegistry.TOOL_MAP("TeamTaskUpdate").description
    assert(update.contains("auto-expire"), "TTL 生命周期")
    assert(update.contains("completed") && update.contains("failed"), "四态语义")
    val create = ToolRegistry.TOOL_MAP("TeamTaskCreate").description
    assert(create.contains("auto-expire"), "TTL 生命周期")

end Phase2dToolRefactorSpec
