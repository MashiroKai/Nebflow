package nebflow.core

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import nebflow.core.tools.*
import nebflow.shared.*

case class ToolExecResult(content: String, isError: Boolean = false, truncated: Boolean = false)

// NOTE: executeTool has been consolidated into AgentCore.executeTool.
// This file retains ToolExecResult, summarizeToolCall, and summarizeToolResult
// which are used across the codebase.

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
