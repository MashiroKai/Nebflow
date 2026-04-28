package nebflow.core

import cats.effect.IO
import cats.syntax.all.*
import nebflow.core.tools.*
import nebflow.shared.{LlmHandle, ToolCall}

case class ToolExecResult(content: String, isError: Boolean = false)

def executeTool(
  call: ToolCall,
  projectRoot: String,
  llm: Option[LlmHandle[IO]] = None,
  replUi: Option[nebflow.core.ReplUi] = None,
  permState: Option[PermissionState] = None
): IO[ToolExecResult] =
  val logger = NebflowLogger.forName("nebflow.handlers")
  val start = System.nanoTime()

  ToolRegistry.TOOL_MAP.get(call.name) match
    case Some(tool) =>
      val summary = tool.summarize(call.input)
      val risk = ToolRisk.classify(call.name)

      // Permission check
      val approvalIO: IO[Boolean] = (risk, permState, replUi) match
        case (ToolRisk.Safe, _, _) => IO.pure(true)
        case (ToolRisk.Blocked, _, _) => IO.pure(false)
        case (ToolRisk.NeedsApproval, Some(ps), Some(ui)) =>
          ps.shouldApprove(call.name).flatMap {
            case true => IO.pure(true)
            case false =>
              val inputJson = io.circe.Json.fromJsonObject(call.input).noSpaces
              ui.askPermission(call.name, summary, inputJson).flatTap {
                case true => ps.recordApproval(call.name) *> logger.info(s"User approved: $summary")
                case false => logger.info(s"User denied: $summary")
              }
          }
        case _ => IO.pure(true) // no permission system = auto-approve

      approvalIO.flatMap {
        case false =>
          val msg = NebflowError.toUserMessage(NebflowError.ToolDenied(call.name, "User denied permission"))
          IO.pure(ToolExecResult(msg, isError = true))
        case true =>
          val ctx = ToolContext(projectRoot, llm, replUi)
          logger.debug(s"Executing tool: $summary") *> {
            tool
              .call(call.input, ctx)
              .map {
                case Left(err) => ToolExecResult(err.message, isError = true)
                case Right(result) => ToolExecResult(result)
              }
              .handleError {
                case _: UserAbort => throw new UserAbort()
                case e => ToolExecResult(s"Tool execution error: ${e.getMessage}", isError = true)
              }
              .flatTap { result =>
                val elapsed = (System.nanoTime() - start) / 1_000_000
                if result.isError then logger.warn(s"Tool $summary failed (${elapsed}ms): ${result.content.take(100)}")
                else logger.info(s"Tool $summary OK (${elapsed}ms)")
              }
          }
      }
    case None =>
      logger.warn(s"Unknown tool requested: ${call.name}") *>
        IO.pure(ToolExecResult(s"No such tool available: ${call.name}", isError = true))

  end match

end executeTool

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
