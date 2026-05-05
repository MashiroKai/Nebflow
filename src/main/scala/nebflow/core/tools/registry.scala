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
      // User interaction
      "AskUserQuestion" -> AskUserQuestionTool,
      // Task management
      "TaskCreate" -> TaskCreateTool,
      "TaskList" -> TaskListTool,
      "TaskGet" -> TaskGetTool,
      "TaskUpdate" -> TaskUpdateTool,
      "TaskDelete" -> TaskDeleteTool,
      // Multi-agent
      "NewSession" -> NewSessionTool,
      // Agent lifecycle — always available, no tool whitelist filtering
      "finish" -> FinishTool,
      "declareWait" -> DeclareWaitTool,
      "ContextManage" -> ContextManageTool,
      // Agent communication — injected conditionally by buildToolList
      "delegate" -> DelegateTool,
      "report" -> ReportTool,
      "ask_parent" -> AskParentTool
    )
    tools.putAll(builtins.asJava)
  }

  def TOOL_MAP: Map[String, Tool] = tools.asScala.toMap

  def ALL_TOOLS: List[ToolDefinition] = tools.asScala.values.map { t =>
    ToolDefinition(t.name, t.description, t.inputSchema)
  }.toList

  /** Tools that are always included regardless of agent tool whitelist. */
  val AlwaysAvailable: Set[String] = Set("finish", "declareWait", "ContextManage")

  /** Tools only available when agent has subagents. */
  val SubagentTools: Set[String] = Set("delegate")

  /** Tools only available when agent has a parent (depth > 0). */
  val ParentTools: Set[String] = Set("report", "ask_parent")

  def registerTool(tool: Tool): Unit =
    tools.put(tool.name, tool)

  def registerTools(newTools: List[Tool]): Unit =
    newTools.foreach(t => tools.put(t.name, t))
end ToolRegistry
