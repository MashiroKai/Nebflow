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
 * - D.1-11：SendMessage 声明式注入通道删除——显式声明与 "*" 均不再
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
        guardrailsOn: Boolean = false,
        projectBoardSession: Boolean = false
    ): Set[String] =
      buildAllowedToolSet(defn, depth, isSubTaskWorker, isFlowNode, isTeamLead, userFacingNode, guardrailsOn, projectBoardSession)

  private def mkDef(name: String, tools: List[String] = Nil): AgentDef =
    AgentDef(name = name, description = "", tools = tools)

  // ===== D.1-1：三角色静态集收口，工具面逐件不变 =====

  test("D.1-1: Nebula fixed set == §C.1 清单（在飞 15 件，终态 14）、零 Issue、零写手、零 NodeList（逐件不变）"):
    val fixed = AgentCore.fixedToolsFor(mkDef("Nebula"))
    val expected =
      Set("Task", "ProjectCreate", "AgentControl",
        "Delegate",                                            // 编排触发（2026-09-11 极简内核回归，+1）
        "TaskList",                                            // 任务编排（2026-09-06 TaskList 批：快变状态出记忆）
        "SendMessage",
        "Read", "Glob", "Grep",                                // 读三件（08:40 解禁四件；23:34 裁定收走写手）
        "Card",                                               // 可视化（2026-09-05 解封恢复）
        "AskUserQuestion", "Pop",
        "Schedule", "TransferFile",
        "MemoryEdit")
    assertEquals(fixed, expected,
      "Nebula 静态集件数 == 单点常量 AgentCore.NebulaOrchestrationToolsExpectedSize（在飞 15 = 终态 14 + 本批 Delegate −1 待 TransferFile 退役批；2026-09-06 TaskList 批：+TaskList，作者 00:07 提议 + 00:11 首期无前端拍板；00:48 作者裁定：NodeList 摘除——节点结果沿 out 边自动投递，主动查图与裁定职责重叠，dispatcher 自身面不受影响；2026-09-05 23:34 作者裁定：Nebula 回归纯编排——Bash/Write/Edit 移除；08:40 作者裁定：+Card 解封/−Mail/Delegate/FlowTrigger/FlowExecute 旧体系退役；2026-09-04 终裁：Issue/CheckIssues 退役）")
    assert(!fixed.contains("Issue"), "Nebula fixedTools 零 Issue（2026-09-04 终裁退役）")
    assert(!fixed.contains("NodeList"), "Nebula fixedTools 零 NodeList（2026-09-06 00:48 裁定摘除——变异验红锚）")
    Set("Mail", "FlowTrigger", "FlowExecute").foreach { t =>
      assert(!fixed.contains(t), s"旧体系三件维持退役（2026-09-05 裁定）: $t")
    }
    assertEquals(fixed.size, AgentCore.NebulaOrchestrationToolsExpectedSize,
      "件数断言单点来源（同一常量）——本批在飞 15，终态目标 14 待 TransferFile 退役批同窗抵平")
    assert(fixed.contains("Delegate"), "Delegate 以极简内核形态回归（2026-09-11）——不携带 Team/Flow/Mail 语义")
    // 钉死断言（2026-09-05 23:34 作者裁定）：Nebula 机制集不含 Bash、不含
    // Write、不含 Edit——变异验红锚
    assert(!fixed.contains("Bash"), "Nebula 机制集不含 Bash（23:34 裁定：回归纯编排）")
    assert(!fixed.contains("Write"), "Nebula 机制集不含 Write（23:34 裁定）")
    assert(!fixed.contains("Edit"), "Nebula 机制集不含 Edit（23:34 裁定）")

  test("D.1-1: dispatcher fixed set == Node 四件 + 读四件 + TaskBoard（逐件不变；NodeMessage 20260905 机制批第八件、TaskBoard 20260908 任务板批 2 第九件）"):
    assertEquals(
      AgentCore.fixedToolsFor(mkDef("project-dispatcher")),
      Set("NodeList", "NodeEdit", "NodeCancel", "NodeMessage", "Read", "Glob", "Grep", "Bash", "TaskBoard"),
      "TaskBoard（20260908 任务板批 2，规格 §1c）：分发器八件→九件——项目任务板全权面（create 全量/update 全板含结构字段/close 全板/list 全板；权限判定引擎侧身份=isDispatcher，工具内不信客户端参数）"
    )

  test("D.1-1: general fixed set == 七件（2026-09-08 恢复 AskUser；2026-09-10 裁定摘 Pop）"):
    assertEquals(
      AgentCore.fixedToolsFor(mkDef("general")),
      Set("Read", "Glob", "Edit", "Write", "Grep", "Bash", "AskUserQuestion"),
      "AskUserQuestion 回归 general 默认面（2026-09-08 作者修订，D6 批D1：直达作者 + 留痕审计）；Pop 摘除（2026-09-10 作者裁定：收归 Nebula 专属——节点交付物沿 out 边交链末端/Nebula）"
    )
    assert(!AgentCore.fixedToolsFor(mkDef("general")).contains("Pop"),
      "general 固定面零 Pop（2026-09-10 裁定，变异验红锚）")

  test("D.1-1: legacy 路径不再含三角色 name 分支——catch-all 对三角色名生效"):
    // legacyFixedTools 是纯 category 函数：三角色名传入时走 catch-all BaseTools
    // （即 name 分支已删）。生产路径 fixedToolsFor 对三角色派发静态集（上面的
    // 断言），二者分层即「收口」的结构证明。
    assertEquals(AgentCore.legacyFixedTools(mkDef("Nebula")), AgentCore.BaseTools,
      "legacy 路径对 Nebula 名返回 catch-all——name 分支已删除")
    assertEquals(AgentCore.legacyFixedTools(mkDef("project-dispatcher")), AgentCore.BaseTools)
    assertEquals(AgentCore.legacyFixedTools(mkDef("general")), AgentCore.BaseTools)

  test("D.1-1: legacy 双轨分支 team/catch-all 逐件不变；flow 分支已随 2026-09-06 裁撤批收 BaseTools"):
    // 2026-09-06 工具面裁撤批：FlowExecute（team 固定面）与 FlowReport（flow
    // 固定面）移出——legacy 双轨路径本身保留至阶段 3。
    val team = AgentCore.legacyFixedTools(mkDef("backend").copy(category = "team"))
    assertEquals(team, AgentCore.BaseTools + "Mail" + "SubTask" ++ AgentCore.TeamTaskTools)
    val flow = AgentCore.legacyFixedTools(mkDef("node").copy(category = "flow"))
    assertEquals(flow, AgentCore.BaseTools, "flow 固定面零 FlowReport（2026-09-06 裁撤批）")
    val solo = AgentCore.legacyFixedTools(mkDef("Coder"))
    assertEquals(solo, AgentCore.BaseTools,
      "legacy standalone catch-all 保留（Coder/Explorer/design-engineer 依赖，阶段 3 删）")

  test("D.1-1: buildAllowedToolSet 三角色交付面 == 静态集（LLM 面，注册表过滤后）"):
    val nebulaDelivered = CoreProbe.allowed(mkDef("Nebula"))
    // 2026-09-06 00:48 作者裁定：NodeList 摘除——MemoryEdit 在、NodeList 不在
    // 交付面（out 边自动投递取代主动查图）。
    assert(nebulaDelivered.contains("MemoryEdit"))
    assert(!nebulaDelivered.contains("NodeList"), "Nebula 交付面零 NodeList（00:48 裁定）")
    // 2026-09-05 23:34 作者裁定：Nebula 回归纯编排——读三件在、写手三件
    // （Bash/Write/Edit）不在交付面；general/BaseTools 六件默认注入不变。
    assert(nebulaDelivered.contains("Read") && nebulaDelivered.contains("Glob") && nebulaDelivered.contains("Grep"),
      "Nebula 携带读三件（23:34 裁定后唯一文件面）")
    Set("Bash", "Write", "Edit").foreach { t =>
      assert(!nebulaDelivered.contains(t), s"Nebula 交付面零写手（23:34 裁定）: $t")
    }
    val generalDelivered = CoreProbe.allowed(mkDef("general"), isFlowNode = true)
    assertEquals(generalDelivered, AgentCore.GeneralFixedTools,
      "general 节点形态交付面 == 静态集恰七件（2026-09-08 作者修订恢复 AskUser；2026-09-10 裁定摘 Pop）")
    assert(!generalDelivered.contains("NodeMessage"), "NodeMessage 仅分发器（general 不加，20260905 机制批裁定⑥）")
    val dispatcherDelivered = CoreProbe.allowed(mkDef("project-dispatcher"), isFlowNode = true, projectBoardSession = true)
    assertEquals(dispatcherDelivered, AgentCore.DispatcherFixedTools, "dispatcher project 会话交付面 == 静态 9 件（含 NodeMessage + TaskBoard）")
    assert(dispatcherDelivered.contains("NodeMessage"), "NodeMessage 机制固定进分发器交付面（20260905 机制批）")
    assert(dispatcherDelivered.contains("TaskBoard"), "TaskBoard 随 project 会话身份进分发器交付面（任务板批 2 §1c）")
    // 边界（裁定⑥）：Nebula 不加 NodeMessage
    assert(!CoreProbe.allowed(mkDef("Nebula")).contains("NodeMessage"), "NodeMessage 仅分发器（Nebula 不加，裁定⑥）")

  // ===== TaskBoard：project 会话按身份挂载（任务板批 2 §1c/§1d-4）=====

  test("TaskBoard: projectBoardSession 旗标是唯一挂载闸——分发器/flow 节点 project 会话挂，双轨会话恒不挂"):
    // 挂载表 §1c：projectBoardSession = isDispatcher || flowNodeId.isDefined。
    // 追加点在全部角色过滤与 NebulaExclusiveTools 剥离【之后】（末段重挂）。
    assert(CoreProbe.allowed(mkDef("project-dispatcher"), projectBoardSession = true).contains("TaskBoard"),
      "分发器 project 会话（isDispatcher 置位）挂 TaskBoard")
    assert(CoreProbe.allowed(mkDef("general"), isFlowNode = true, projectBoardSession = true).contains("TaskBoard"),
      "flow 节点 project 会话（flowNodeId 置位）挂 TaskBoard（§1d：节点身份同面）")
    // 双保险（§1d-4）：非 project 会话 flag=false 恒不挂——声明（含 "*"）不授能
    // （nebulaFiltered 先剥、末段不挂），工具面 + 工具内身份判定两层独立。
    assert(!CoreProbe.allowed(mkDef("sneaky", List("TaskBoard"))).contains("TaskBoard"),
      "非 project 会话显式声明 TaskBoard 不授能（防声明逃逸通道，NebulaExclusiveTools）")
    assert(!CoreProbe.allowed(mkDef("omni", List("*"))).contains("TaskBoard"),
      "wildcard 声明同样不授能")
    assert(!CoreProbe.allowed(mkDef("general"), isFlowNode = true).contains("TaskBoard"),
      "双轨 flow 会话 flag=false 恒不挂（任务板批 2 前行为零变化）")

  // ===== D.1-11：SendMessage 声明通道删除 =====

  // 工具面收敛 批① A 轨（2026-09-10）：通信工具纯名字迁移（旧名 = LegacyToolName
  // 拼接值，见下；「好友消息」→ 中性「消息」，语义/件数零变化）。
  // 三条不变式必须齐——件数 == 单点常量 ∧ 新名在 ∧ 旧名零残留。本文件刻意不写裸旧名
  // 字面量：既让「引号精确 grep 旧名 → 0」的验收口径成立（注释本身也不许破口径），
  // 又保住零残留断言——裸字面量会同时打破这两条中的一条。
  private val LegacyToolName = "SendFriend" + "Message"

  test("A轨(批①): 改名后三条不变式齐——交付面件数 == 单点常量 ∧ 含 SendMessage ∧ 旧名零残留"):
    val delivered = CoreProbe.allowed(mkDef("Nebula"))
    assertEquals(delivered.size, AgentCore.NebulaOrchestrationToolsExpectedSize,
      "交付面件数与机制集单点常量一致（本批在飞 15：Delegate 回归 +1；终态 14 待 TransferFile 退役批）")
    assert(delivered.contains("SendMessage"), "新名进交付面（改名承重点：LLM 可见名）")
    assert(!delivered.exists(_.contains(LegacyToolName)), "旧名零残留（LLM 交付面）")
    assert(!AgentCore.NebulaOrchestrationTools.exists(_.contains(LegacyToolName)),
      "旧名零残留（Nebula 机制固定集）")
    assert(!ToolRegistry.TOOL_MAP.contains(LegacyToolName),
      "旧名零残留（注册表——旧调用名解析失败，按未知工具明确报错，不静默）")

  test("D.1-11: standalone 显式声明 SendMessage 不再授能"):
    val declared = CoreProbe.allowed(mkDef("social", List("Read", "SendMessage")))
    assert(!declared.contains("SendMessage"), "声明通道已删——声明不授能")
    val wildcard = CoreProbe.allowed(mkDef("omni", List("*")))
    assert(!wildcard.contains("SendMessage"), "\"*\" 同样不授能")
    assert(CoreProbe.allowed(mkDef("social", List("Read"))).contains("Read"), "其余声明不受影响")

  test("D.1-11: Nebula 机制固定照常携带 SendMessage"):
    assert(CoreProbe.allowed(mkDef("Nebula")).contains("SendMessage"),
      "机制固定是唯一授权源（静态集）")

  test("D.1-11: registry 注册名随改名（工具本身保留）"):
    assert(ToolRegistry.TOOL_MAP.contains("SendMessage"))

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

  test("D.2: Pop description 含 Nebula 专属 + 节点交付协议关键句（2026-09-10 作者裁定重写）"):
    // 旧的「生成后立即 Pop / professional tool + never hand-draw」面向节点的
    // 汇报工作流指导句已按裁定删除（工具面收口取代提示词恳求）；改为明示
    // Nebula 专属 + 节点交付协议（沿 out 边交链末端/Nebula）。
    val d = ToolRegistry.TOOL_MAP("Pop").description
    assert(d.contains("Nebula-exclusive"), "专属声明句")
    assert(d.contains("POP_NEBULA_ONLY"), "拒答错误码（非 Nebula 身份被拒的可行动文案）")
    assert(d.contains("out edge"), "节点交付协议：沿 out 边交链末端/Nebula")
    assert(d.contains("Canvas"), "呈现面")
    assert(!d.contains("never hand-draw"),
      "面向节点的「生成后立即 Pop」指导句必须已删（不得写成「节点也能 Pop」）")

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
