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
      // Sub-agent delegation (background + persistent modes) — Nebula/调度器专用
      "Delegate" -> DelegateTool,
      // Background agent inspection & control (list/status/cancel/restart) — Nebula 专用
      "AgentControl" -> AgentControlTool,
      // Flow DAG pipeline triggering — whitelist-driven via agent.json flows
      // (injected by buildAllowedToolSet for agents declaring flows)
      "FlowTrigger" -> FlowTriggerTool,
      // One-shot dynamic flow execution (#406) — inline DAG, no persistence.
      // Mechanism-layer injected for Team members + Nebula (fixedToolsFor).
      "FlowExecute" -> FlowExecuteTool,
      // Team-member task delegation (self-clone + ephemeral worker, no Mail identity)
      "SubTask" -> SubTaskTool,
      // Cross-device file transfer
      "TransferFile" -> TransferFileTool,
      // A2A 一期: agent sends a message to one of the user's NebLink friends
      // (#290). Authorization (阶段 2d, D.1-11): mechanism-fixed for Nebula
      // only (NebulaOrchestrationTools, 2c 起) — agent.json declaration
      // channel removed (buildAllowedToolSet strips the name from base).
      "SendFriendMessage" -> FriendMessageTool,
      // Load Team/Flow from disk (validate + mount)
      "Load" -> LoadTool,
      // Flow agent result reporting (verdict + output for DAG switch routing)
      "FlowReport" -> FlowReportTool,
      // #28 阶段 0：Project + Node 模型工具集（分发器白名单声明；全局注册使
      // agent.json tools 可解析）。NodeEdit/NodeList/NodeCancel = 分发器用；
      // ProjectCreate = Nebula 用（建项目 + 工作区脚手架；workspace 缺省/不可用
      // 时弹 AskUser 式路径面板复用 pending 机制）；Task = Nebula 侧
      // 项目任务触发（阶段 2 迁移第一步，与 Mail(→project) 同内核、入口不同）。
      "NodeEdit" -> NodeEditTool,
      "NodeList" -> NodeListTool,
      "NodeCancel" -> NodeCancelTool,
      "ProjectCreate" -> ProjectCreateTool,
      "Task" -> TaskTool,
      // 阶段 2c（§C.2）：Nebula 专用记忆维护工具——target 白名单硬编码
      // User.md + agents/Nebula/memory.md（H-1①：工具内建路径校验，非沙箱
      // 对象）。Nebula-only 注入见 AgentCore.NebulaExclusiveTools。
      "MemoryEdit" -> MemoryEditTool
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
