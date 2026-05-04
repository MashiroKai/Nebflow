package nebflow.core

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import nebflow.core.tools.*
import nebflow.shared.*

case class ToolExecResult(content: String, isError: Boolean = false)

def executeTool(
  call: ToolCall,
  projectRoot: String,
  llm: Option[LlmHandle[IO]] = None,
  permState: Option[PermissionState] = None,
  fileChangeTracker: Option[FileChangeTracker] = None,
  sessionStore: Option[nebflow.gateway.SessionStore] = None,
  sessionTag: Option[String] = None,
  agentActorRef: Option[org.apache.pekko.actor.typed.ActorRef[nebflow.agent.AgentCommand]] = None,
  contextWindow: Int = Defaults.ContextWindow,
  sessionId: Option[String] = None,
  taskStore: Option[nebflow.core.task.TaskStore] = None,
  wsSend: Option[io.circe.Json => IO[Unit]] = None,
  readTracker: Option[nebflow.core.tools.ReadTracker] = None
): IO[ToolExecResult] =
  val logger = NebflowLogger.forName("nebflow.handlers")

  ToolRegistry.TOOL_MAP.get(call.name) match
    case Some(tool) =>
      val summary = tool.summarize(call.input)
      val risk = ToolRisk.classify(call.name)

      // Permission check: blocked by policy, otherwise auto-approve
      val approvalIO: IO[ApprovalDecision] = (risk, permState) match
        case (ToolRisk.Safe, _) => IO.pure(ApprovalDecision.Approved)
        case (ToolRisk.NeedsApproval, Some(ps)) =>
          ps.shouldApprove(call.name).map {
            case ApprovalDecision.Blocked(reason) => ApprovalDecision.Blocked(reason)
            case _ => ApprovalDecision.Approved
          }
        case _ => IO.pure(ApprovalDecision.Approved)

      approvalIO.flatMap {
        case ApprovalDecision.Blocked(reason) =>
          val msg = NebflowError.toUserMessage(NebflowError.ToolDenied(call.name, reason))
          IO.pure(ToolExecResult(msg, isError = true))
        case ApprovalDecision.Approved =>
          val ctx = ToolContext(
            projectRoot,
            llm,
            sessionStore,
            agentActorRef,
            contextWindow,
            sessionId,
            taskStore,
            wsSend,
            readTracker
          )
          val tag = sessionTag.map(t => s"[$t] ").getOrElse("")
          IO.delay(System.nanoTime()).flatMap { start =>
            logger.debug(s"${tag}Executing tool: $summary") *> {
              tool
                .call(call.input, ctx)
                .map {
                  case Left(err) => ToolExecResult(err.message, isError = true)
                  case Right(result) => ToolExecResult(result)
                }
                .handleErrorWith {
                  case _: UserAbort => IO.raiseError(new UserAbort())
                  case e => IO.pure(ToolExecResult(s"Tool execution error: ${e.getMessage}", isError = true))
                }
                .flatTap { result =>
                  val elapsed = (System.nanoTime() - start) / 1_000_000
                  if result.isError then
                    logger.warn(s"${tag}Tool $summary failed (${elapsed}ms): ${result.content.take(100)}")
                  else
                    logger.info(s"${tag}Tool $summary OK (${elapsed}ms)") *>
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
          // Should not reach here — all NeedsUserApproval are converted to Approved above
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
