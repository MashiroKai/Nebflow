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
      "TaskGet" -> TaskGetTool,
      "TaskUpdate" -> TaskUpdateTool,
      "TaskDelete" -> TaskDeleteTool,
      // Agent lifecycle — always available, no tool whitelist filtering
      "RemoveUnnecessary" -> RemoveUnnecessaryTool
    )
    tools.putAll(builtins.asJava)
  }

  def TOOL_MAP: Map[String, Tool] = tools.asScala.toMap

  def ALL_TOOLS: List[ToolDefinition] = tools.asScala.values.map { t =>
    ToolDefinition(t.name, t.description, t.inputSchema)
  }.toList

  /** Tools that are always included regardless of agent tool whitelist. */
  val AlwaysAvailable: Set[String] = Set.empty[String]

  /** Tools always available except on the compaction agent itself (to prevent recursion). */
  val AlwaysAvailableNonCompact: Set[String] = Set("RemoveUnnecessary")

  /** Tools that users can select in agent configuration UI. */
  val UserConfigurable: Set[String] = AlwaysAvailableNonCompact

  /** Tool definitions for the user-configurable set (sent to frontend). */
  def userConfigurableTools: List[ToolDefinition] =
    TOOL_MAP.values
      .filterNot(t => UserConfigurable.contains(t.name))
      .map(t => ToolDefinition(t.name, t.description, t.inputSchema))
      .toList

  def registerTool(tool: Tool): Unit =
    tools.put(tool.name, tool)

  def registerTools(newTools: List[Tool]): Unit =
    newTools.foreach(t => tools.put(t.name, t))

  /** Unregister all tools whose name starts with the given prefix (e.g. "mcp__zai__"). */
  def unregisterToolsByPrefix(prefix: String): Unit =
    tools.keySet.removeIf(_.startsWith(prefix))
end ToolRegistry
