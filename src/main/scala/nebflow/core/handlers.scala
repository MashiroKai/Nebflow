package nebflow.core

import cats.effect.IO
import cats.effect.Ref
import cats.syntax.all.*
import nebflow.core.tools.*
import nebflow.shared.{LlmHandle, Message, ToolCall}

case class ToolExecResult(content: String, isError: Boolean = false)

/** Check if a file-editing tool targets a path outside the project root. */
private def isOutsideProject(call: ToolCall, projectRoot: String): Boolean =
  if call.name == "Write" || call.name == "Edit" then
    val filePathStr = call.input("file_path").flatMap(_.asString).getOrElse("")
    val resolved =
      if filePathStr.startsWith("/") then filePathStr
      else s"$projectRoot/$filePathStr"
    !PathSandbox.isAllowed(resolved, projectRoot)
  else false

def executeTool(
  call: ToolCall,
  projectRoot: String,
  llm: Option[LlmHandle[IO]] = None,
  replUi: Option[nebflow.core.ReplUi] = None,
  permState: Option[PermissionState] = None,
  messagesRef: Option[Ref[IO, List[Message]]] = None,
  fileChangeTracker: Option[FileChangeTracker] = None
): IO[ToolExecResult] =
  val logger = NebflowLogger.forName("nebflow.handlers")

  ToolRegistry.TOOL_MAP.get(call.name) match
    case Some(tool) =>
      val summary = tool.summarize(call.input)
      val risk = ToolRisk.classify(call.name)

      // Permission check: Write/Edit inside project → auto-approve
      val approvalIO: IO[ApprovalDecision] = (risk, permState, replUi) match
        case (ToolRisk.Safe, _, _) => IO.pure(ApprovalDecision.Approved)
        case (ToolRisk.NeedsApproval, _, _) if (call.name == "Write" || call.name == "Edit") && !isOutsideProject(call, projectRoot) =>
          IO.pure(ApprovalDecision.Approved)
        case (ToolRisk.NeedsApproval, Some(ps), Some(ui)) =>
          ps.shouldApprove(call.name).flatMap {
            case ApprovalDecision.Approved => IO.pure(ApprovalDecision.Approved)
            case ApprovalDecision.Blocked(reason) => IO.pure(ApprovalDecision.Blocked(reason))
            case ApprovalDecision.NeedsUserApproval =>
              val inputJson = io.circe.Json.fromJsonObject(call.input).noSpaces
              ui.askPermission(call.name, summary, inputJson).flatMap {
                case true => ps.recordApproval(call.name) *> logger.info(s"User approved: $summary") *> IO.pure(ApprovalDecision.Approved)
                case false => logger.info(s"User denied: $summary") *> IO.pure(ApprovalDecision.Blocked("User denied permission"))
              }
          }
        case _ => IO.pure(ApprovalDecision.Approved) // no permission system = auto-approve

      approvalIO.flatMap {
        case ApprovalDecision.Blocked(reason) =>
          val msg = NebflowError.toUserMessage(NebflowError.ToolDenied(call.name, reason))
          IO.pure(ToolExecResult(msg, isError = true))
        case ApprovalDecision.Approved =>
          val ctx = ToolContext(projectRoot, llm, replUi, messagesRef)
          IO.delay(System.nanoTime()).flatMap { start =>
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
                else
                  logger.info(s"Tool $summary OK (${elapsed}ms)") *>
                  // Record file modifications by agent tools
                  (if call.name == "Write" || call.name == "Edit" then
                    call.input("file_path").flatMap(_.asString) match
                      case Some(path) => fileChangeTracker.traverse_(_.recordAgentModification(path))
                      case None => IO.unit
                  else IO.unit)
              }
          }
          }
        case ApprovalDecision.NeedsUserApproval =>
          // Should not reach here — handled above with ui.askPermission
          IO.pure(ToolExecResult(s"Unexpected approval state for ${call.name}", isError = true))
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
