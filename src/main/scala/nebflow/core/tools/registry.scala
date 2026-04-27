package nebflow.core.tools

import nebflow.shared.ToolDefinition

object ToolRegistry:
  @volatile private var _tools: Map[String, Tool] = Map(
    "Read" -> ReadTool,
    "Write" -> WriteTool,
    "Edit" -> EditTool,
    "Bash" -> BashTool,
    "Glob" -> GlobTool,
    "Grep" -> GrepTool,
    "WebSearch" -> WebSearchTool,
    "WebFetch" -> WebFetchTool,
    "Curl" -> CurlTool,
    "WordDocx" -> WordDocxTool,
    "Pdf" -> PdfTool,
    "ExcelXlsx" -> ExcelXlsxTool,
    "AskUserQuestion" -> AskUserQuestionTool,
  )

  def TOOL_MAP: Map[String, Tool] = _tools

  def ALL_TOOLS: List[ToolDefinition] = _tools.values.map { t =>
    ToolDefinition(t.name, t.description, t.inputSchema)
  }.toList

  def registerTool(tool: Tool): Unit = synchronized {
    _tools = _tools + (tool.name -> tool)
  }

  def registerTools(tools: List[Tool]): Unit = synchronized {
    _tools = _tools ++ tools.map(t => t.name -> t)
  }
