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
      // Search
      "Glob" -> GlobTool,
      "Grep" -> GrepTool,
      // Shell
      "Bash" -> BashTool,
      // Web
      "WebSearch" -> WebSearchTool,
      "WebFetch" -> WebFetchTool,
      "Curl" -> CurlTool,
      // Card rendering
      "Card" -> CardTool,
      // User interaction
      "AskUserQuestion" -> AskUserQuestionTool,
      // Task management
      "TaskCreate" -> TaskCreateTool,
      "TaskList" -> TaskListTool,
      "TaskUpdate" -> TaskUpdateTool,
      // Agent lifecycle — always available, no tool whitelist filtering
      "RemoveUnnecessary" -> RemoveUnnecessaryTool,
      // Memory
      "WriteMemory" -> WriteMemoryTool,
      "ClearStaging" -> ClearStagingTool
    )
    tools.putAll(builtins.asJava)
  }

  def TOOL_MAP: Map[String, Tool] = tools.asScala.toMap

  def ALL_TOOLS: List[ToolDefinition] = tools.asScala.values.map { t =>
    ToolDefinition(t.name, t.description, t.inputSchema)
  }.toList

  /** Builtin tool names (non-MCP), used by frontend to build configurable tool list. */
  def builtinToolNames: List[String] =
    tools.asScala.keys.filterNot(_.startsWith("mcp__")).toList.sorted

  def registerTool(tool: Tool): Unit =
    tools.put(tool.name, tool)

  def registerTools(newTools: List[Tool]): Unit =
    newTools.foreach(t => tools.put(t.name, t))

  /** Unregister all tools whose name starts with the given prefix (e.g. "mcp__zai__"). */
  def unregisterToolsByPrefix(prefix: String): Unit =
    tools.keySet.removeIf(_.startsWith(prefix))
end ToolRegistry
