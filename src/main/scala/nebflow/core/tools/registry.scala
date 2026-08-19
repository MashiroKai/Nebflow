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
      "MultiEdit" -> MultiEditTool,
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
      // Task management
      "TaskCreate" -> TaskCreateTool,
      "TaskUpdate" -> TaskUpdateTool,
      "TaskQuery" -> TaskQueryTool,
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
      // Team-member task delegation (self-clone + ephemeral worker, no Mail identity)
      "SubTask" -> SubTaskTool,
      // Cross-device file transfer
      "TransferFile" -> TransferFileTool,
      // Load Team/Flow from disk (validate + mount)
      "Load" -> LoadTool,
      // Flow agent result reporting (verdict + output for DAG switch routing)
      "FlowReport" -> FlowReportTool
    )
    tools.putAll(builtins.asJava)
  }

  def TOOL_MAP: Map[String, Tool] = tools.asScala.toMap

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
