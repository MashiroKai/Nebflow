package nebflow.core.tools

import nebflow.shared.ToolDefinition

import java.util.concurrent.ConcurrentHashMap

import scala.jdk.CollectionConverters.*

object ToolRegistry:
  private val tools = new ConcurrentHashMap[String, Tool]()

  locally {
    val builtins: Map[String, Tool] = Map(
      // File operations
      "Read" -> ReadTool,
      "Write" -> WriteTool,
      "Edit" -> EditTool,
      // MultiEdit 已从注册表移除（阶段 2c §C.1/裁定 5：通用 agent 8 件之外；
      // 能力由 Edit 的 replace_all 覆盖）。MultiEditTool 类保留（非物理删除），
      // spec 与未来 plugin 扩展（§B.6 白名单扩展）仍可引用。
      // Search
      "Glob" -> GlobTool,
      "Grep" -> GrepTool,
      // Shell
      "Bash" -> BashTool,
      // Web
      "WebSearch" -> WebSearchTool,
      "WebFetch" -> WebFetchTool,
      "Curl" -> CurlTool,
      // Canvas file display
      "Pop" -> PopTool,
      // Chat-stream HTML card (sandboxed iframe) — 解封恢复（2026-09-05 08:40
      // 作者裁定，NebulaOrchestrationTools +Card）：commit 793f62c1（2026-08-11）
      // 曾整体删除，本批恢复后端工具与注册；前端 iframe 消费面另批恢复。
      "Card" -> CardTool,
      // User interaction
      "AskUserQuestion" -> AskUserQuestionTool,
      // Team task tools (任务工具重做 2026-08-30: 任务=进展展示，工具只配
      // team——category=team 机制层注入全体成员；session 域 TaskCreate/Update/
      // Query 已退役，TTL 清理见 TaskStore)
      "TeamTaskCreate" -> TeamTaskCreateTool,
      "TeamTaskUpdate" -> TeamTaskUpdateTool,
      "TeamTaskList" -> TeamTaskListTool,
      // Scheduled tasks
      "Schedule" -> ScheduleTool,
      // Unified agent communication (message + ask modes)
      "Mail" -> MailTool,
      // Sub-agent delegation → 内置极简内核（2026-09-11 恢复批）：Nebula 专属
      // 执行件，目标恒为 `kernel` def（无 agent 目标参数）；persistent 模式已退役。
      "Delegate" -> DelegateTool,
      // Background agent inspection & control (list/status/cancel/restart) — Nebula 专用
      "AgentControl" -> AgentControlTool,
      // FlowTrigger / FlowExecute / FlowReport retired 2026-09-06（工具面裁撤
      // 批，作者裁定提前执行阶段 2d 子集）：agent 侧 flow 触发/动态编排/verdict
      // 报告三工具整体退役。Mail(→flow-name) 引擎触发链与 FlowDagRunner 引擎
      // 本体不受影响（红线：引擎零触碰）；FlowReportStore（FlowDagExecutor 消费
      // 的 verdict 数据面）抽出为独立文件保留至阶段 3。
      // Team-member task delegation (self-clone + ephemeral worker, no Mail identity)
      "SubTask" -> SubTaskTool,
      // Cross-device file transfer
      "TransferFile" -> TransferFileTool,
      // A2A 一期: agent sends a message to one of the user's NebLink friends
      // (#290). Authorization (阶段 2d, D.1-11): mechanism-fixed for Nebula
      // only (NebulaOrchestrationTools, 2c 起) — agent.json declaration
      // channel removed (buildAllowedToolSet strips the name from base).
      "SendMessage" -> FriendMessageTool,
      // Load Team/Flow from disk (validate + mount)
      "Load" -> LoadTool,
      // FlowReport 注册已随 2026-09-06 工具面裁撤批移除（见上注）。
      // #28 阶段 0：Project + Node 模型工具集（分发器白名单声明；全局注册使
      // agent.json tools 可解析）。NodeEdit/NodeList/NodeCancel = 分发器用；
      // ProjectCreate = Nebula 用（建项目 + 工作区脚手架；workspace 缺省/不可用
      // 时弹 AskUser 式路径面板复用 pending 机制）；Task = Nebula 侧
      // 项目任务触发（阶段 2 迁移第一步，与 Mail(→project) 同内核、入口不同）。
      "NodeEdit" -> NodeEditTool,
      "NodeList" -> NodeListTool,
      "NodeCancel" -> NodeCancelTool,
      // NodeMessage（20260905 机制批，作者裁定）：分发器向已分发节点注入补充
      // 消息（running=turn 边界注入 / 未启动=任务追加 / 终态拒绝）。仅分发器
      // 工具面（AgentCore.DispatcherFixedTools 第八件），Nebula/general 不加。
      "NodeMessage" -> NodeMessageTool,
      "ProjectCreate" -> ProjectCreateTool,
      "Task" -> TaskTool,
      // 阶段 2c（§C.2）：记忆维护工具——target 白名单硬编码 User.md +
      // agents/Nebula/memory.md（H-1①：工具内建路径校验，非沙箱对象）。
      // 授能面：Nebula 固定携带；dream 经 2026-09-05 作者签准备入
      // （AgentCore.DreamAdmittedTools + exclusiveToolsFor 单点剥离豁免），
      // 动作面限修订动作（remove/update/replace_section），append 由
      // MemoryEditTool 拒绝（DREAM_APPEND_DENIED——dream 禁写新记忆铁律）。
      "MemoryEdit" -> MemoryEditTool,
      // TaskList（2026-09-06 作者 00:07 提议 + 00:11 首期无前端拍板）：Nebula
      // 专属编排件——持久任务清单（~/.nebflow/tasks.json 运行时数据层）。
      // 授能面 = NebulaOrchestrationTools 单一来源（恰十四件，TaskList 批 +1）；
      // NebulaExclusiveTools 防声明逃逸（dispatcher/general/"*" 一律剥离）。
      "TaskList" -> TaskListTool,
      // TaskBoard（20260908 任务板批 2，规格 §1b/§1c）：项目域共享工作项看板
      // ——Flow Map 管节点，TaskBoard 管任务（TaskList 同族四态 + blocks 闸 +
      // close 幂等）。授能面 = 会话身份机制挂载（分发器 DispatcherFixedTools
      // 第九件 + project 节点/分发器会话 buildAllowedToolSet 末段按身份追加），
      // plugins 声明不授能（NebulaExclusiveTools 同享防逃逸通道）；权限矩阵在
      // 工具内按引擎侧身份判定（TaskBoardTool.dispatchSync）。
      "TaskBoard" -> TaskBoardToolDef,
      // node_report（blocked 结构化信号批 20260909，设计 spec 方案 A 改造点
      // #2/#3；同日作者裁定泛化更名：ReportBlockedTool → NodeReport——Blocked/
      // Pass/Failed 同为「类似的语义判断」，迁移一起迁移）：Flow Map 节点专属
      // 终态语义申报工具（协议级结构化信号，替代文本锚定推断）。授能面 =
      // buildAllowedToolSet 末段按 flowNodeSession 会话身份注入（TaskBoard 同款
      // 挂载模式，编排层专属工具族）+ 工具内 ctx.flowNodeId 身份拒绝双保险；
      // plugins 声明不授能（不在 BuiltinTool 白名单）。信号经 NodeReportRegistry
      // 登记表由 NodeEngine 完成时点 drain 按类别分流到既有链（blocked →
      // blockedNode/FeedbackRouter；pass → completed 链；fail → failed 链）。
      "node_report" -> NodeReportToolDef
    )
    tools.putAll(builtins.asJava)
  }

  def TOOL_MAP: Map[String, Tool] = tools.asScala.toMap

  /** 动态注册名快照（阶段 2b：plugin MCP allowedSet 追加源；轻量——不经
    * augmentSchema，纯键名）。 */
  def registeredToolNames: List[String] = tools.keys.asScala.toList

  def ALL_TOOLS: List[ToolDefinition] = tools.asScala.values.map { t =>
    val schema = RemoteExecutor.augmentSchema(t.name, t.inputSchema)
    ToolDefinition(t.name, t.description, schema)
  }.toList

  /** Builtin tool names (non-MCP), used by frontend to build configurable tool list. */
  def builtinToolNames: List[String] =
    tools.asScala.keys.filterNot(_.startsWith("mcp__")).toList.sorted

  def registerTool(tool: Tool): Unit =
    tools.put(tool.name, tool)

  def registerTools(newTools: List[Tool]): Unit =
    newTools.foreach(t => tools.put(t.name, t))

  /** Unregister a single tool by name. */
  def unregisterTool(name: String): Unit =
    tools.remove(name)

  /** Unregister all tools whose name starts with the given prefix (e.g. "mcp__zai__"). */
  def unregisterToolsByPrefix(prefix: String): Unit =
    tools.keySet.removeIf(_.startsWith(prefix))
end ToolRegistry
