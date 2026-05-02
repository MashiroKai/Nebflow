package nebflow.agent

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.tools.ToolError
import nebflow.core.{AskItem, ToolExecResult}
import nebflow.gateway.SessionMeta
import nebflow.shared.*

// ============================================================
// Guardian Actor Messages
// ============================================================

case class SessionRef(actor: org.apache.pekko.actor.typed.ActorRef[SessionCommand])
case object Ack

sealed trait GuardianCommand
object GuardianCommand:
  case class CreateSession(wsConnId: String, replyTo: org.apache.pekko.actor.typed.ActorRef[SessionRef], wsSend: io.circe.Json => cats.effect.IO[Unit])
      extends GuardianCommand
  case class DestroySession(wsConnId: String) extends GuardianCommand
  case class Shutdown(replyTo: org.apache.pekko.actor.typed.ActorRef[Ack.type]) extends GuardianCommand

// Guardian internal
case class SessionTerminated(wsConnId: String) extends GuardianCommand

// ============================================================
// Session Actor Messages
// ============================================================

sealed trait SessionCommand
object SessionCommand:
  case class UserMessage(text: String, blocks: List[nebflow.shared.ContentBlock]) extends SessionCommand
  case class Interrupt(sessionId: String) extends SessionCommand
  case class SwitchSession(sessionId: String, replyTo: org.apache.pekko.actor.typed.ActorRef[SwitchResult])
      extends SessionCommand
  case class CreateSessionCmd(name: String, replyTo: org.apache.pekko.actor.typed.ActorRef[SessionRef]) extends SessionCommand
  case class DeleteSession(sessionId: String, replyTo: org.apache.pekko.actor.typed.ActorRef[DeleteResult])
      extends SessionCommand
  case class RenameSession(sessionId: String, newName: String, replyTo: org.apache.pekko.actor.typed.ActorRef[Boolean])
      extends SessionCommand
  case class ListSessions(replyTo: org.apache.pekko.actor.typed.ActorRef[SessionList]) extends SessionCommand
  case class StreamToClient(event: AgentStreamEvent) extends SessionCommand
  case class AgentTerminated(agentId: String) extends SessionCommand
  case class AskUserResponse(requestId: String, answers: List[String]) extends SessionCommand
  case class PermissionResponse(requestId: String, approved: Boolean) extends SessionCommand
  case class SetThinking(enabled: Boolean) extends SessionCommand
  case class SetPolicy(policy: String) extends SessionCommand
  case class ClearChat() extends SessionCommand
  case class SendSessionList() extends SessionCommand

  // Agent management commands
  case class ListAgents(replyTo: org.apache.pekko.actor.typed.ActorRef[AgentListResp]) extends SessionCommand
  case class GetAgentConfig(name: String, replyTo: org.apache.pekko.actor.typed.ActorRef[AgentConfigResp]) extends SessionCommand
  case class CreateAgent(name: String, yaml: String, systemMd: String, replyTo: org.apache.pekko.actor.typed.ActorRef[AgentCreatedResp]) extends SessionCommand
  case class UpdateAgent(name: String, yaml: String, systemMd: String, replyTo: org.apache.pekko.actor.typed.ActorRef[AgentUpdatedResp]) extends SessionCommand
  case class CreateAgentSession(agentName: String, replyTo: org.apache.pekko.actor.typed.ActorRef[SessionRef]) extends SessionCommand

  // Config management commands
  case class GetConfig(replyTo: org.apache.pekko.actor.typed.ActorRef[ConfigDataResp]) extends SessionCommand
  case class UpdateConfig(config: String, replyTo: org.apache.pekko.actor.typed.ActorRef[ConfigUpdatedResp]) extends SessionCommand

  // Internal — REPL fiber started (sent from dispatcher back to self)
  case class ReplFiberStarted(
    sessionId: String,
    fiber: cats.effect.Fiber[IO, Throwable, Unit],
    replUi: SessionReplUi
  ) extends SessionCommand

  // Internal — REPL fiber completed (sent from dispatcher back to self)
  case class ReplFiberDone(sessionId: String) extends SessionCommand

case class SwitchResult(success: Boolean, messages: Option[List[Message]] = None, error: Option[String] = None)
case class DeleteResult(success: Boolean, error: Option[String] = None)
case class SessionList(sessions: List[SessionMeta], activeId: String)

// Agent / Config response types
case class AgentInfo(name: String, description: String, tools: List[String], subagents: List[String])
case class AgentListResp(agents: List[AgentInfo])
case class AgentConfigResp(name: String, yaml: String, systemMd: String)
case class AgentCreatedResp(name: String)
case class AgentUpdatedResp(name: String)
case class ConfigDataResp(config: String)
case class ConfigUpdatedResp(success: Boolean)

// ============================================================
// Agent Actor Messages
// ============================================================

sealed trait AgentCommand
object AgentCommand:
  // User input
  case class UserInput(text: String, replyTo: Option[org.apache.pekko.actor.typed.ActorRef[AgentEvent]] = None)
      extends AgentCommand
  case class Interrupt() extends AgentCommand

  // LLM streaming result callback
  case class StreamComplete(result: ConsumeResult, replyTo: Option[org.apache.pekko.actor.typed.ActorRef[AgentEvent]])
      extends AgentCommand
  case class StreamFailed(error: Throwable, replyTo: Option[org.apache.pekko.actor.typed.ActorRef[AgentEvent]])
      extends AgentCommand

  // Tool execution result callback
  case class ToolComplete(callId: String, result: Either[ToolError, String]) extends AgentCommand

  // Sub-agent communication
  case class DelegateResult(subagentId: String, result: Either[AgentError, String]) extends AgentCommand
  case class SubagentQuestion(
    subagentId: String,
    question: String,
    replyTo: org.apache.pekko.actor.typed.ActorRef[ParentAnswer]
  ) extends AgentCommand
  case class ParentAnswer(answer: String) extends AgentCommand

  // Streaming output forwarding
  case class SubagentStreamEvent(subagentId: String, event: AgentStreamEvent) extends AgentCommand

  // Ask user / Permission (from tool execution)
  case class AskUser(
    requestId: String,
    items: List[AskItem],
    replyTo: org.apache.pekko.actor.typed.ActorRef[List[String]]
  ) extends AgentCommand
  case class AskPermission(
    requestId: String,
    toolName: String,
    summary: String,
    replyTo: org.apache.pekko.actor.typed.ActorRef[Boolean]
  ) extends AgentCommand

  // Internal — piped IO results (used by AgentActor)
  case class LlmComplete(result: ConsumeResult, replyTo: Option[org.apache.pekko.actor.typed.ActorRef[AgentEvent]])
      extends AgentCommand
  case class LlmFailed(error: Throwable, replyTo: Option[org.apache.pekko.actor.typed.ActorRef[AgentEvent]])
      extends AgentCommand
  case class ToolsComplete(
    results: List[(ToolCall, ToolExecResult)],
    originalText: String,
    replyTo: Option[org.apache.pekko.actor.typed.ActorRef[AgentEvent]]
  ) extends AgentCommand

  // Internal — auto-compact completed
  case class CompactionDone(
    compactedMessages: List[Message],
    originalResult: ConsumeResult,
    replyTo: Option[org.apache.pekko.actor.typed.ActorRef[AgentEvent]]
  ) extends AgentCommand

  // Internal — subagent definition loaded, ready to spawn on actor thread
  case class SubagentDefLoaded(
    call: ToolCall, agentName: String, task: String,
    defn: Option[AgentDef], depth: Int
  ) extends AgentCommand

  // Lifecycle
  case class Stop(reason: String) extends AgentCommand

// ============================================================
// Agent Events (output)
// ============================================================

sealed trait AgentEvent
object AgentEvent:
  case class Completed(text: String) extends AgentEvent
  case class Failed(error: AgentError) extends AgentEvent

// ============================================================
// Agent Stream Events (for WebSocket)
// ============================================================

enum AgentStreamEvent:
  case TextDelta(text: String)
  case ToolStart(label: String)
  case ToolEnd(label: String, summary: String, isError: Boolean)
  case AgentStart(agentName: String, agentType: String)
  case AgentEnd(agentName: String)
  case Thinking
  case Done

  def toJson(agentId: String): Json = this match
    case TextDelta(text) =>
      Json.obj("type" -> "agentTextDelta".asJson, "agentId" -> agentId.asJson, "delta" -> text.asJson)
    case ToolStart(label) =>
      Json.obj("type" -> "agentToolStart".asJson, "agentId" -> agentId.asJson, "label" -> label.asJson)
    case ToolEnd(label, summary, isError) =>
      Json.obj(
        "type" -> "agentToolEnd".asJson,
        "agentId" -> agentId.asJson,
        "label" -> label.asJson,
        "summary" -> summary.asJson,
        "isError" -> isError.asJson
      )
    case AgentStart(name, agentType) =>
      Json.obj(
        "type" -> "agentStart".asJson,
        "agentId" -> agentId.asJson,
        "name" -> name.asJson,
        "agentType" -> agentType.asJson
      )
    case AgentEnd(name) =>
      Json.obj("type" -> "agentEnd".asJson, "agentId" -> agentId.asJson, "name" -> name.asJson)
    case Thinking =>
      Json.obj("type" -> "agentThinking".asJson, "agentId" -> agentId.asJson)
    case Done =>
      Json.obj("type" -> "agentDone".asJson, "agentId" -> agentId.asJson)

end AgentStreamEvent

// ============================================================
// Agent Error
// ============================================================

enum AgentErrorType:
  case LlmFailed, ToolFailed, Timeout, Interrupted, DepthExceeded, Unknown

case class AgentError(
  agentId: String,
  agentName: String,
  depth: Int,
  errorType: AgentErrorType,
  message: String,
  cause: Option[AgentError] = None
)

// ============================================================
// Agent Status
// ============================================================

enum AgentStatus:
  case Idle
  case Processing
  case Delegating(subagentId: String)
  case WaitingForUser
  case Error(msg: String)

// ============================================================
// Agent Runtime State (behavior closure parameter, not Ref[IO])
// ============================================================

case class AgentState(
  messages: List[Message],
  status: AgentStatus,
  depth: Int,
  subagents: Map[String, org.apache.pekko.actor.typed.ActorRef[AgentCommand]],
  activeStreamFiber: Option[cats.effect.Fiber[IO, Throwable, Unit]]
)

// ============================================================
// Consume Result (extracted from Repl for actor use)
// ============================================================

case class ConsumeResult(
  text: String,
  toolCalls: List[ToolCall],
  results: List[(ToolCall, ToolExecResult)],
  stopReason: Option[String],
  usage: Option[TokenUsage] = None
)
