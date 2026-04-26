package nebscala.core

import nebscala.shared.{ToolCall, LlmHandle}
import nebscala.core.tools.{Tool, ToolContext, ToolError, ToolRegistry}
import cats.effect.IO
import cats.syntax.all.*

case class ToolExecResult(content: String, isError: Boolean = false)

def executeTool(call: ToolCall, projectRoot: String, llm: Option[LlmHandle[IO]] = None): IO[ToolExecResult] =
  ToolRegistry.TOOL_MAP.get(call.name) match
    case Some(tool) =>
      val ctx = ToolContext(projectRoot, llm)
      tool.call(call.input, ctx).map {
        case Left(err) => ToolExecResult(err.message, isError = true)
        case Right(result) =>
          if result.startsWith("Error:") then ToolExecResult(result, isError = true)
          else ToolExecResult(result)
      }.handleError {
        case _: UserAbort => throw new UserAbort()
        case e => ToolExecResult(s"Tool execution error: ${e.getMessage}", isError = true)
      }
    case None =>
      IO.pure(ToolExecResult(s"No such tool available: ${call.name}", isError = true))

def summarizeToolCall(call: ToolCall): String =
  ToolRegistry.TOOL_MAP.get(call.name) match
    case Some(tool) => tool.summarize(call.input)
    case None =>
      if call.name.startsWith("mcp__") then
        val parts = call.name.split("__")
        val toolPart = if parts.length >= 3 then parts.slice(2, parts.length).mkString("__") else call.name
        s"[MCP] $toolPart"
      else call.name

def summarizeToolResult(call: ToolCall, result: String): String =
  ToolRegistry.TOOL_MAP.get(call.name) match
    case Some(tool) => tool.summarizeResult(call.input, result)
    case None =>
      val firstLine = result.split("\n").headOption.getOrElse("")
      if firstLine.length > 80 then firstLine.take(80) + "..." else firstLine
