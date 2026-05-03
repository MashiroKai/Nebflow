package nebflow.agent

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
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

  case class CreateSession(
    wsConnId: String,
    replyTo: org.apache.pekko.actor.typed.ActorRef[SessionRef],
    wsSend: io.circe.Json => cats.effect.IO[Unit]
  ) extends GuardianCommand
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

  case class CreateSessionCmd(name: String, replyTo: org.apache.pekko.actor.typed.ActorRef[SessionRef])
      extends SessionCommand

  case class DeleteSession(sessionId: String, replyTo: org.apache.pekko.actor.typed.ActorRef[DeleteResult])
      extends SessionCommand

  case class RenameSession(sessionId: String, newName: String, replyTo: org.apache.pekko.actor.typed.ActorRef[Boolean])
      extends SessionCommand
  case class ListSessions(replyTo: org.apache.pekko.actor.typed.ActorRef[SessionList]) extends SessionCommand
  case class AgentTerminated(agentId: String) extends SessionCommand
  case class AskUserResponse(requestId: String, answers: List[String]) extends SessionCommand
  case class PermissionResponse(requestId: String, approved: Boolean) extends SessionCommand
  case class SetThinking(enabled: Boolean) extends SessionCommand
  case class SetPolicy(policy: String) extends SessionCommand
  case class ClearChat() extends SessionCommand
  case class SendSessionList() extends SessionCommand
  case class Terminate() extends SessionCommand

  // Internal — agent data loaded, spawn on actor thread
  case class SpawnAgent(
    sessionId: String,
    agentDef: AgentDef,
    initialMessages: List[Message],
    text: String,
    replyAdapter: org.apache.pekko.actor.typed.ActorRef[AgentEvent]
  ) extends SessionCommand

  // Agent management commands
  case class ListAgents(replyTo: org.apache.pekko.actor.typed.ActorRef[AgentListResp]) extends SessionCommand

  case class GetAgentConfig(name: String, replyTo: org.apache.pekko.actor.typed.ActorRef[AgentConfigResp])
      extends SessionCommand

  case class CreateAgent(
    name: String,
    configJson: String,
    systemMd: String,
    replyTo: org.apache.pekko.actor.typed.ActorRef[AgentCreatedResp]
  ) extends SessionCommand

  case class UpdateAgent(
    name: String,
    configJson: String,
    systemMd: String,
    replyTo: org.apache.pekko.actor.typed.ActorRef[AgentUpdatedResp]
  ) extends SessionCommand

  case class CreateAgentSession(agentName: String, replyTo: org.apache.pekko.actor.typed.ActorRef[SessionRef])
      extends SessionCommand

  // Config management commands
  case class GetConfig(replyTo: org.apache.pekko.actor.typed.ActorRef[ConfigDataResp]) extends SessionCommand

  case class UpdateConfig(config: String, replyTo: org.apache.pekko.actor.typed.ActorRef[ConfigUpdatedResp])
      extends SessionCommand

  // AgentActor integration
  case class AgentTurnCompleted(sessionId: String, messages: List[Message]) extends SessionCommand
  case class AgentTurnFailed(sessionId: String, error: AgentError) extends SessionCommand
end SessionCommand

case class SwitchResult(success: Boolean, messages: Option[List[Message]] = None, error: Option[String] = None)
case class DeleteResult(success: Boolean, error: Option[String] = None)
case class SessionList(sessions: List[SessionMeta], activeId: String)

// Agent / Config response types
case class AgentInfo(name: String, description: String, tools: List[String], subagents: List[String])
case class AgentListResp(agents: List[AgentInfo])
case class AgentConfigResp(name: String, configJson: String, systemMd: String)
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
    replyTo: Option[org.apache.pekko.actor.typed.ActorRef[AgentEvent]],
    compactedMessages: Option[List[Message]] = None,
    thinking: Option[String] = None
  ) extends AgentCommand

  // Internal — compaction agent definition loaded, ready to spawn on actor thread
  case class CompactionDefLoaded(
    defn: Option[AgentDef]
  ) extends AgentCommand

  // Internal — manual compaction trigger from ContextManageTool
  case class TriggerCompaction(
    mode: String,
    replyDeferred: Option[cats.effect.Deferred[IO, Either[String, CompactionResult]]] = None
  ) extends AgentCommand

  // Internal — subagent definition loaded, ready to spawn on actor thread
  case class SubagentDefLoaded(
    call: ToolCall,
    agentName: String,
    task: String,
    defn: Option[AgentDef],
    depth: Int
  ) extends AgentCommand

  // User interaction responses (forwarded from SessionActor)
  case class UserAnswered(answers: List[String]) extends AgentCommand
  case class PermissionAnswered(approved: Boolean) extends AgentCommand

  // Lifecycle
  case class Stop(reason: String) extends AgentCommand
end AgentCommand

// ============================================================
// Agent Events (output)
// ============================================================

sealed trait AgentEvent

object AgentEvent:
  case class Completed(sessionId: String, messages: List[Message] = Nil) extends AgentEvent
  case class Failed(sessionId: String, error: AgentError) extends AgentEvent

// ============================================================
// Agent Stream Events (for WebSocket)
// ============================================================

enum AgentStreamEvent:
  case TextDelta(text: String)
  case ToolStart(label: String)
  case ToolEnd(label: String, summary: String, content: String, isError: Boolean, input: Option[JsonObject] = None)
  case AgentStart(agentName: String, agentType: String)
  case AgentEnd(agentName: String)
  case Thinking
  case RetryStatus(message: String)
  case Done

  def toJson(agentId: String, isSubagent: Boolean = true, sessionId: Option[String] = None): Json = this match
    case TextDelta(text) =>
      if isSubagent then
        Json.obj("type" -> "agentTextDelta".asJson, "agentId" -> agentId.asJson, "delta" -> text.asJson)
      else
        Json.obj("type" -> "textDelta".asJson, "sessionId" -> sessionId.asJson, "delta" -> text.asJson)
    case ToolStart(label) =>
      if isSubagent then
        Json.obj("type" -> "agentToolStart".asJson, "agentId" -> agentId.asJson, "label" -> label.asJson)
      else
        Json.obj("type" -> "toolStart".asJson, "sessionId" -> sessionId.asJson, "label" -> label.asJson)
    case ToolEnd(label, summary, content, isError, input) =>
      val base = if isSubagent then
        Json.obj(
          "type" -> "agentToolEnd".asJson,
          "agentId" -> agentId.asJson,
          "label" -> label.asJson,
          "summary" -> summary.asJson,
          "content" -> content.asJson,
          "isError" -> isError.asJson
        )
      else
        Json.obj(
          "type" -> "toolEnd".asJson,
          "sessionId" -> sessionId.asJson,
          "label" -> label.asJson,
          "summary" -> summary.asJson,
          "content" -> content.asJson,
          "isError" -> isError.asJson
        )
      input.fold(base)(i => base.deepMerge(Json.obj("input" -> Json.fromJsonObject(i))))
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
      if isSubagent then
        Json.obj("type" -> "agentThinking".asJson, "agentId" -> agentId.asJson)
      else
        Json.obj("type" -> "thinking".asJson, "sessionId" -> sessionId.asJson)
    case RetryStatus(message) =>
      if isSubagent then
        Json.obj("type" -> "agentRetryStatus".asJson, "agentId" -> agentId.asJson, "message" -> message.asJson)
      else
        Json.obj("type" -> "retryStatus".asJson, "sessionId" -> sessionId.asJson, "message" -> message.asJson)
    case Done =>
      if isSubagent then
        Json.obj("type" -> "agentDone".asJson, "agentId" -> agentId.asJson)
      else
        Json.obj("type" -> "done".asJson, "sessionId" -> sessionId.asJson)

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

case class CompactionResult(before: Int, after: Int)

// Record of a tool call for loop detection
case class ToolCallRecord(
  name: String,
  inputHash: String,
  turnIdx: Int
)

case class CompactionContext(
  subagentId: String,
  mode: String,
  replyDeferred: Option[cats.effect.Deferred[IO, Either[String, CompactionResult]]] = None,
  replyTo: Option[org.apache.pekko.actor.typed.ActorRef[AgentEvent]] = None
)

case class AgentState(
  messages: List[Message],
  status: AgentStatus,
  depth: Int,
  subagents: Map[String, org.apache.pekko.actor.typed.ActorRef[AgentCommand]],
  activeStreamFiber: Option[cats.effect.Fiber[IO, Throwable, Unit]],
  sessionId: Option[String] = None,
  pendingCompaction: Option[CompactionContext] = None,
  pendingManualCompaction: Option[String] = None,
  latestUsage: Option[TokenUsage] = None,
  pendingAskUser: Option[cats.effect.Deferred[IO, List[String]]] = None,
  pendingPermission: Option[cats.effect.Deferred[IO, Boolean]] = None,
  recentToolCalls: List[ToolCallRecord] = Nil,
  turnIdx: Int = 0
)

// ============================================================
// Consume Result (extracted from Repl for actor use)
// ============================================================

case class ConsumeResult(
  text: String,
  toolCalls: List[ToolCall],
  results: List[(ToolCall, ToolExecResult)],
  stopReason: Option[String],
  usage: Option[TokenUsage] = None,
  thinking: Option[String] = None
)
