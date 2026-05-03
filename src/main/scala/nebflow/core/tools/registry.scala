package nebflow.core.tools

import nebflow.shared.ToolDefinition

import java.util.concurrent.ConcurrentHashMap

import scala.jdk.CollectionConverters.*

object ToolRegistry:
  private val tools = new ConcurrentHashMap[String, Tool]()

  locally {
    val builtins: Map[String, Tool] = Map(
      "Read" -> ReadTool,
      "Write" -> WriteTool,
      "Edit" -> EditTool,
      "Bash" -> BashTool,
      "Glob" -> GlobTool,
      "Grep" -> GrepTool,
      "WebSearch" -> WebSearchTool,
      "WebFetch" -> WebFetchTool,
      "Curl" -> CurlTool,
      "AskUserQuestion" -> AskUserQuestionTool,
      "ContextManage" -> ContextManageTool,
      "NewSession" -> NewSessionTool,
      "TaskCreate" -> TaskCreateTool,
      "TaskList" -> TaskListTool,
      "TaskGet" -> TaskGetTool,
      "TaskUpdate" -> TaskUpdateTool,
      "TaskDelete" -> TaskDeleteTool
    )
    tools.putAll(builtins.asJava)
  }

  def TOOL_MAP: Map[String, Tool] = tools.asScala.toMap

  def ALL_TOOLS: List[ToolDefinition] = tools.asScala.values.map { t =>
    ToolDefinition(t.name, t.description, t.inputSchema)
  }.toList

  def registerTool(tool: Tool): Unit =
    tools.put(tool.name, tool)

  def registerTools(newTools: List[Tool]): Unit =
    newTools.foreach(t => tools.put(t.name, t))
end ToolRegistry
