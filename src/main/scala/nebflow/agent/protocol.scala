package nebflow.agent

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.{AskItem, SystemReminder, ToolExecResult}
import nebflow.shared.*

// ============================================================
// Agent Actor Messages
// ============================================================

sealed trait AgentCommand

object AgentCommand:

  // User input
  case class UserInput(
    text: String,
    replyTo: Option[org.apache.pekko.actor.typed.ActorRef[AgentEvent]] = None,
    clientMessageId: Option[String] = None,
    blocks: Option[List[ContentBlock]] = None
  ) extends AgentCommand
  case class Interrupt() extends AgentCommand

  // Ask user / Permission (from tool execution)
  case class AskUser(
    requestId: String,
    items: List[AskItem],
    replyTo: Option[org.apache.pekko.actor.typed.ActorRef[List[String]]] = None
  ) extends AgentCommand

  case class AskPermission(
    requestId: String,
    toolName: String,
    summary: String,
    replyTo: org.apache.pekko.actor.typed.ActorRef[Boolean]
  ) extends AgentCommand

  // Internal — fiber reference for in-flight LLM stream (sent immediately after io.start)
  case class StreamFiberStarted(fiber: cats.effect.Fiber[IO, Throwable, Unit]) extends AgentCommand

  // Internal — piped IO results (used by AgentActor)
  case class LlmComplete(
    result: ConsumeResult,
    replyTo: Option[org.apache.pekko.actor.typed.ActorRef[AgentEvent]],
    turnId: Long
  ) extends AgentCommand

  case class LlmFailed(
    error: Throwable,
    replyTo: Option[org.apache.pekko.actor.typed.ActorRef[AgentEvent]],
    turnId: Long
  ) extends AgentCommand

  // Internal — IO thread notifies actor that a permission deferred needs to be tracked in state
  case class SetPermissionDeferred(deferred: cats.effect.Deferred[IO, Boolean]) extends AgentCommand

  case class ToolsComplete(
    results: List[(ToolCall, ToolExecResult)],
    originalText: String,
    replyTo: Option[org.apache.pekko.actor.typed.ActorRef[AgentEvent]],
    compactedMessages: Option[List[Message]] = None,
    thinking: Option[String] = None,
    thinkingSignature: Option[String] = None,
    updatedWriteTracker: Map[String, WriteTrackerEntry] = Map.empty
  ) extends AgentCommand

  // Internal — direct compaction result from CompactService IO fiber
  case class CompactionComplete(
    result: Either[String, List[Message]]
  ) extends AgentCommand

  // Internal — manual compaction trigger (from /compact command or model switch)
  case class TriggerCompaction(
    mode: String,
    replyDeferred: Option[cats.effect.Deferred[IO, Either[String, CompactionResult]]] = None
  ) extends AgentCommand

  // /ask — inline Q&A using agent's cached system prompt + tools
  case class AskQuestion(
    question: String,
    sessionId: String
  ) extends AgentCommand

  // Skill activation — runs skill.md content + user input as a normal agent turn
  case class SkillActivate(
    skillName: String,
    input: String,
    sessionId: String,
    skillContent: String,
    skillBaseDir: String
  ) extends AgentCommand

  // Session model switched — update contextWindow for compaction threshold
  case class UpdateContextWindow(window: Int) extends AgentCommand

  // Internal — ReplaceToolResults from RemoveUnnecessaryTool
  case class ReplaceToolResults(
    rounds: Int,
    summary: String,
    replyTo: cats.effect.Deferred[IO, Either[String, Int]]
  ) extends AgentCommand

  // User interaction responses
  case class UserAnswered(answers: List[String]) extends AgentCommand
  case class PermissionAnswered(approved: Boolean) extends AgentCommand

  // Lifecycle
  case class Stop(reason: String) extends AgentCommand
  case object ClearReadTracker extends AgentCommand

  /** Full session reset — triggered by /clear to reset messages, usage, compaction state, etc. */
  case object ResetSession extends AgentCommand

  /** Update per-session highest context-pressure level (isolated from other sessions). */
  case class UpdateHighestPressureLevel(level: Int) extends AgentCommand

  // Peer communication (root agent to root agent via ActorRef)
  case class MessageToAgent(
    targetRef: org.apache.pekko.actor.typed.ActorRef[AgentCommand],
    payload: String,
    replyTo: Option[org.apache.pekko.actor.typed.ActorRef[AgentCommand]] = None
  ) extends AgentCommand

  /**
   * Background task completed — inject result into message history and resume.
   *  Retained as a convenience during the Phase 1 coexistence period; internally
   *  converted to [[ExternalEvent]] before processing.
   */
  case class BackgroundTaskNotification(
    taskId: String,
    description: String,
    status: String, // "completed" | "failed" | "killed" | "stuck"
    output: String,
    exitCode: Option[Int] = None
  ) extends AgentCommand:

    /** Convert to the new ExternalEvent format. */
    def toExternalEvent: ExternalEvent = ExternalEvent(
      source = "background-task",
      eventType = status,
      payload = status match
        case "completed" =>
          val exitInfo = exitCode.filter(_ != 0).map(c => s" (exit code $c)").getOrElse("")
          s"[Background task completed] \"$description\"$exitInfo:\n$output"
        case "failed" =>
          s"[Background task failed] \"$description\":\n$output"
        case _ =>
          s"[Background task stopped] \"$description\"",
      metadata = JsonObject(
        "taskId" -> taskId.asJson,
        "description" -> description.asJson,
        "status" -> status.asJson,
        "output" -> output.asJson,
        "exitCode" -> exitCode.asJson
      ),
      correlationId = Option(taskId).filter(_.nonEmpty)
    )
  end BackgroundTaskNotification

  /** Universal agent wake-up message. Any external system can send this to activate the agent. */
  case class ExternalEvent(
    source: String, // Event source identifier (e.g. "background-task", "webhook", "im")
    eventType: String, // Event type within the source
    payload: String, // Human-readable content — injected as User Message
    metadata: JsonObject = JsonObject.empty, // Structured data for programmatic use
    correlationId: Option[String] = None // For tracking request/response chains
  ) extends AgentCommand
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

  case ToolEnd(
    label: String,
    summary: String,
    content: String,
    isError: Boolean,
    input: Option[JsonObject] = None,
    truncated: Boolean = false
  )
  case AgentStart(agentName: String, agentType: String)
  case AgentEnd(agentName: String)
  case Thinking
  case ToolCallDetected(name: String)
  case RetryStatus(message: String)
  case Done(model: Option[String] = None, contextWindow: Option[Int] = None, inputTokens: Option[Int] = None)

  /** Emitted after each LlmComplete round to keep frontend context bar up to date in real time. */
  case UsageUpdate(inputTokens: Int, contextWindow: Int)
  case CompactStart(mode: String, inputTokens: Option[Int], threshold: Option[Int])
  case CompactComplete(before: Int, after: Int, reportPath: Option[String] = None)
  case CompactFailed(reason: String, attempt: Int, maxAttempts: Int)
  case BackgroundTaskUpdate(taskId: String, description: String, status: String)

  case ExternalEventReceived(
    source: String,
    eventType: String,
    correlationId: Option[String]
  )

  def toJson(agentId: String, isSubagent: Boolean = true, sessionId: Option[String] = None): Json = this match
    case TextDelta(text) =>
      if isSubagent then
        Json.obj("type" -> "agentTextDelta".asJson, "agentId" -> agentId.asJson, "delta" -> text.asJson)
      else Json.obj("type" -> "textDelta".asJson, "sessionId" -> sessionId.asJson, "delta" -> text.asJson)
    case ToolStart(label) =>
      if isSubagent then
        Json.obj("type" -> "agentToolStart".asJson, "agentId" -> agentId.asJson, "label" -> label.asJson)
      else Json.obj("type" -> "toolStart".asJson, "sessionId" -> sessionId.asJson, "label" -> label.asJson)
    case ToolEnd(label, summary, content, isError, input, truncated) =>
      val base =
        if isSubagent then
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
      val withInput = input.fold(base)(i => base.deepMerge(Json.obj("input" -> Json.fromJsonObject(i))))
      if truncated then withInput.deepMerge(Json.obj("truncated" -> true.asJson)) else withInput
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
      if isSubagent then Json.obj("type" -> "agentThinking".asJson, "agentId" -> agentId.asJson)
      else Json.obj("type" -> "thinking".asJson, "sessionId" -> sessionId.asJson)
    case ToolCallDetected(name) =>
      if isSubagent then
        Json.obj("type" -> "agentToolCallDetected".asJson, "agentId" -> agentId.asJson, "name" -> name.asJson)
      else Json.obj("type" -> "toolCallDetected".asJson, "sessionId" -> sessionId.asJson, "name" -> name.asJson)
    case RetryStatus(message) =>
      if isSubagent then
        Json.obj("type" -> "agentRetryStatus".asJson, "agentId" -> agentId.asJson, "message" -> message.asJson)
      else Json.obj("type" -> "retryStatus".asJson, "sessionId" -> sessionId.asJson, "message" -> message.asJson)
    case Done(model, contextWindow, inputTokens) =>
      val base =
        if isSubagent then Json.obj("type" -> "agentDone".asJson, "agentId" -> agentId.asJson)
        else Json.obj("type" -> "done".asJson, "sessionId" -> sessionId.asJson)
      val withModel = model.fold(base)(m => base.deepMerge(Json.obj("model" -> m.asJson)))
      val withCw = contextWindow.fold(withModel)(cw => withModel.deepMerge(Json.obj("contextWindow" -> cw.asJson)))
      inputTokens.fold(withCw)(it => withCw.deepMerge(Json.obj("inputTokens" -> it.asJson)))
    case UsageUpdate(inputTokens, contextWindow) =>
      Json.obj(
        "type" -> "usageUpdate".asJson,
        "sessionId" -> sessionId.asJson,
        "inputTokens" -> inputTokens.asJson,
        "contextWindow" -> contextWindow.asJson
      )
    case CompactStart(mode, inputTokens, threshold) =>
      if isSubagent then
        Json.obj(
          "type" -> "agentCompactStart".asJson,
          "agentId" -> agentId.asJson,
          "mode" -> mode.asJson,
          "inputTokens" -> inputTokens.asJson,
          "threshold" -> threshold.asJson
        )
      else
        Json.obj(
          "type" -> "compactStart".asJson,
          "sessionId" -> sessionId.asJson,
          "mode" -> mode.asJson,
          "inputTokens" -> inputTokens.asJson,
          "threshold" -> threshold.asJson
        )
    case CompactComplete(before, after, reportPath) =>
      if isSubagent then
        val base = Json.obj(
          "type" -> "agentCompactComplete".asJson,
          "agentId" -> agentId.asJson,
          "before" -> before.asJson,
          "after" -> after.asJson
        )
        reportPath.fold(base)(p => base.deepMerge(Json.obj("reportPath" -> p.asJson)))
      else
        val base = Json.obj(
          "type" -> "compactComplete".asJson,
          "sessionId" -> sessionId.asJson,
          "before" -> before.asJson,
          "after" -> after.asJson
        )
        reportPath.fold(base)(p => base.deepMerge(Json.obj("reportPath" -> p.asJson)))
    case CompactFailed(reason, attempt, maxAttempts) =>
      if isSubagent then
        Json.obj(
          "type" -> "agentCompactFailed".asJson,
          "agentId" -> agentId.asJson,
          "reason" -> reason.asJson,
          "attempt" -> attempt.asJson,
          "maxAttempts" -> maxAttempts.asJson
        )
      else
        Json.obj(
          "type" -> "compactFailed".asJson,
          "sessionId" -> sessionId.asJson,
          "reason" -> reason.asJson,
          "attempt" -> attempt.asJson,
          "maxAttempts" -> maxAttempts.asJson
        )
    case BackgroundTaskUpdate(taskId, description, status) =>
      Json.obj(
        "type" -> "backgroundTaskUpdate".asJson,
        "taskId" -> taskId.asJson,
        "description" -> description.asJson,
        "status" -> status.asJson,
        "sessionId" -> sessionId.asJson
      )
    case ExternalEventReceived(source, eventType, correlationId) =>
      val base = Json.obj(
        "type" -> "externalEventReceived".asJson,
        "source" -> source.asJson,
        "eventType" -> eventType.asJson
      )
      val withSession =
        if isSubagent then base.deepMerge(Json.obj("agentId" -> agentId.asJson))
        else base.deepMerge(Json.obj("sessionId" -> sessionId.asJson))
      correlationId.fold(withSession)(id => withSession.deepMerge(Json.obj("correlationId" -> id.asJson)))

end AgentStreamEvent

// ============================================================
// Shared response types
// ============================================================

case class AgentInfo(
  name: String,
  description: String,
  tools: List[String],
  displayName: Option[String] = None,
  avatar: Option[String] = None,
  mcpServers: List[String] = Nil
)

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
  case WaitingForUser
  case Error(msg: String)

// ============================================================
// Agent Runtime State (behavior closure parameter, not Ref[IO])
// ============================================================

case class CompactionResult(before: Int, after: Int)

/** A pending or in-progress compaction job. */
case class CompactionJob(
  subagentId: String,
  mode: String,
  replyDeferred: Option[cats.effect.Deferred[IO, Either[String, CompactionResult]]] = None,
  replyTo: Option[org.apache.pekko.actor.typed.ActorRef[AgentEvent]] = None,
  /** When true, resume LLM call after compaction (auto/LLM-triggered). When false, return to idle (user-triggered /compact). */
  resumeAfterCompact: Boolean = true
)

// ============================================================
// AgentState sub-structures — grouped by business domain
// ============================================================

/**
 * Per-turn resolved context — all values are freshly resolved from disk/config
 * at the start of each pipeLlmCall via ContextRefresher.
 *
 * All resources are EveryTurn — mtime-cached so unchanged reads cost only stat() syscalls.
 */
case class TurnContext(
  agentDef: AgentDef,
  projectRoot: Option[String],
  rulesMd: Option[String],
  memoryBlock: String,
  thinkingConfig: nebflow.llm.ThinkingConfig,
  fileChanges: Option[SystemReminder]
)

/** Per-file write tracking entry for verification reminders. */
case class WriteTrackerEntry(writeCount: Int, remindCount: Int)

/** Session-level persistent state — survives across turns. */
case class SessionContext(
  sessionId: Option[String] = None,
  sessionName: Option[String] = None,
  recentMessageIds: List[String] = Nil,
  wsSend: Json => IO[Unit] = _ => IO.unit,
  depth: Int = 0,
  readTracker: Option[nebflow.core.tools.ReadTracker] = None,
  fileHistory: Option[nebflow.core.tools.FileHistory] = None,
  /** Per-file write tracking for verification reminders — scoped to this agent/session. */
  writesSinceLastRead: Map[String, WriteTrackerEntry] = Map.empty,
  /** Context window for this session's current model — updated when user switches models. */
  contextWindow: Int = nebflow.shared.Defaults.ContextWindow,
  /** When Some(question), this turn is an inline /ask — single Q&A, no history write-back. */
  askMode: Option[String] = None,
  /** Auto-detected language for system prompt injection. Per-session, set on first user message. */
  language: Option[String] = None,
  /** Resolved project root from session's folder. None → use agent workspace default. */
  projectRoot: Option[String] = None,
  /** Resolved inherited rules.md content from folder chain. None → no rules. */
  rulesMd: Option[String] = None,
  /** This session's folder ID (for rules re-resolution). */
  folderId: Option[String] = None
)

/** Pending user interaction deferreds. */
case class InteractionState(
  pendingAskUser: Option[cats.effect.Deferred[IO, List[String]]] = None,
  pendingPermission: Option[cats.effect.Deferred[IO, Boolean]] = None,
  pendingAskUserReplyTo: Option[org.apache.pekko.actor.typed.ActorRef[List[String]]] = None
)

/** Per-turn execution state — reset when returning to idle. */
case class ExecutionContext(
  messages: List[Message] = Nil,
  status: AgentStatus = AgentStatus.Idle,
  turnIdx: Int = 0,
  currentTurnId: Long = 0L,
  activeStreamFiber: Option[cats.effect.Fiber[IO, Throwable, Unit]] = None,
  interaction: Option[InteractionState] = None,
  /** Queued external events to inject after turn. */
  pendingEvents: List[AgentCommand.ExternalEvent] = Nil,
  /** Retry count for LLM empty responses within a single turn. */
  emptyResponseRetries: Int = 0
)

object ExecutionContext:

  /** Factory for idle state — centralizes all per-turn reset logic. */
  def idle(messages: List[Message], turnIdx: Int = 0, currentTurnId: Long = 0L): ExecutionContext =
    ExecutionContext(
      messages = messages,
      status = AgentStatus.Idle,
      turnIdx = turnIdx,
      currentTurnId = currentTurnId,
      activeStreamFiber = None,
      interaction = None,
      pendingEvents = Nil,
      emptyResponseRetries = 0
    )
end ExecutionContext

/** Context compression state. */
case class CompactionState(
  pendingJob: Option[CompactionJob] = None,
  compactionFailures: Int = 0,
  /** Timestamp of the last compaction failure (epoch ms) — used for retry backoff. */
  lastCompactionFailureAt: Long = 0L,
  latestUsage: Option[TokenUsage] = None,
  lastModel: Option[String] = None,
  /** Highest context-pressure reminder level shown for this session (0–100). Per-session isolation. */
  highestPressureLevel: Int = 0
)

/** Top-level agent state — composed of domain-specific sub-structures. */
case class AgentState(
  session: SessionContext,
  execution: ExecutionContext,
  compaction: CompactionState
)

object AgentState:

  /** Convenience constructor for common case. */
  def apply(
    messages: List[Message] = Nil,
    status: AgentStatus = AgentStatus.Idle,
    depth: Int = 0,
    activeStreamFiber: Option[cats.effect.Fiber[IO, Throwable, Unit]] = None,
    sessionId: Option[String] = None,
    sessionName: Option[String] = None,
    pendingCompaction: Option[CompactionJob] = None,
    compactionFailures: Int = 0,
    latestUsage: Option[TokenUsage] = None,
    pendingAskUser: Option[cats.effect.Deferred[IO, List[String]]] = None,
    pendingPermission: Option[cats.effect.Deferred[IO, Boolean]] = None,
    turnIdx: Int = 0,
    wsSend: Json => IO[Unit] = _ => IO.unit,
    readTracker: Option[nebflow.core.tools.ReadTracker] = None,
    fileHistory: Option[nebflow.core.tools.FileHistory] = None,
    recentMessageIds: List[String] = Nil,
    contextWindow: Int = nebflow.shared.Defaults.ContextWindow,
    projectRoot: Option[String] = None,
    rulesMd: Option[String] = None,
    folderId: Option[String] = None
  ): AgentState =
    val interaction = (pendingAskUser, pendingPermission) match
      case (None, None) => None
      case _ => Some(InteractionState(pendingAskUser, pendingPermission))
    new AgentState(
      SessionContext(
        sessionId,
        sessionName,
        recentMessageIds,
        wsSend,
        depth,
        readTracker,
        fileHistory,
        Map.empty,
        contextWindow,
        folderId = folderId,
        projectRoot = projectRoot,
        rulesMd = rulesMd
      ),
      ExecutionContext(messages, status, turnIdx, 0L, activeStreamFiber, interaction),
      CompactionState(pendingCompaction, compactionFailures, 0L, latestUsage)
    )
  end apply
end AgentState

extension (s: AgentState)
  // Read accessors — allow existing code to read fields without sub-structure paths
  def messages: List[Message] = s.execution.messages
  def status: AgentStatus = s.execution.status
  def sessionId: Option[String] = s.session.sessionId
  def sessionName: Option[String] = s.session.sessionName
  def wsSend: Json => IO[Unit] = s.session.wsSend
  def depth: Int = s.session.depth
  def turnIdx: Int = s.execution.turnIdx
  def currentTurnId: Long = s.execution.currentTurnId
  def activeStreamFiber: Option[cats.effect.Fiber[IO, Throwable, Unit]] = s.execution.activeStreamFiber
  def recentMessageIds: List[String] = s.session.recentMessageIds
  def pendingCompaction: Option[CompactionJob] = s.compaction.pendingJob
  def compactionFailures: Int = s.compaction.compactionFailures
  def lastCompactionFailureAt: Long = s.compaction.lastCompactionFailureAt
  def latestUsage: Option[TokenUsage] = s.compaction.latestUsage
  def lastModel: Option[String] = s.compaction.lastModel
  def emptyResponseRetries: Int = s.execution.emptyResponseRetries
  def pendingAskUser: Option[cats.effect.Deferred[IO, List[String]]] = s.execution.interaction.flatMap(_.pendingAskUser)

  def pendingPermission: Option[cats.effect.Deferred[IO, Boolean]] =
    s.execution.interaction.flatMap(_.pendingPermission)
  def readTracker: Option[nebflow.core.tools.ReadTracker] = s.session.readTracker
  def fileHistory: Option[nebflow.core.tools.FileHistory] = s.session.fileHistory
  def writesSinceLastRead: Map[String, WriteTrackerEntry] = s.session.writesSinceLastRead
  def contextWindow: Int = s.session.contextWindow
  def askMode: Option[String] = s.session.askMode
  def language: Option[String] = s.session.language
  def projectRoot: Option[String] = s.session.projectRoot
  def rulesMd: Option[String] = s.session.rulesMd
  def folderId: Option[String] = s.session.folderId

  // Mutation helpers — return new AgentState with updated sub-structure
  def withSession(session: SessionContext): AgentState = s.copy(session = session)
  def withExecution(execution: ExecutionContext): AgentState = s.copy(execution = execution)
  def withCompaction(compaction: CompactionState): AgentState = s.copy(compaction = compaction)

  def withMessages(msgs: List[Message]): AgentState = s.copy(execution = s.execution.copy(messages = msgs))
  def withStatus(st: AgentStatus): AgentState = s.copy(execution = s.execution.copy(status = st))
  def withTurnIdx(idx: Int): AgentState = s.copy(execution = s.execution.copy(turnIdx = idx))

  def withCurrentTurnId(id: Long): AgentState = s.copy(execution = s.execution.copy(currentTurnId = id))

  def withActiveStreamFiber(fiber: Option[cats.effect.Fiber[IO, Throwable, Unit]]): AgentState =
    s.copy(execution = s.execution.copy(activeStreamFiber = fiber))

  def withInteraction(interaction: Option[InteractionState]): AgentState =
    s.copy(execution = s.execution.copy(interaction = interaction))

  def withPendingAskUser(d: Option[cats.effect.Deferred[IO, List[String]]]): AgentState =
    s.copy(execution =
      s.execution.copy(interaction =
        Some(
          s.execution.interaction.getOrElse(InteractionState()).copy(pendingAskUser = d)
        )
      )
    )

  def withPendingPermission(d: Option[cats.effect.Deferred[IO, Boolean]]): AgentState =
    s.copy(execution =
      s.execution.copy(interaction =
        Some(
          s.execution.interaction.getOrElse(InteractionState()).copy(pendingPermission = d)
        )
      )
    )
  def withRecentMessageIds(ids: List[String]): AgentState = s.copy(session = s.session.copy(recentMessageIds = ids))

  def withWritesSinceLastRead(tracker: Map[String, WriteTrackerEntry]): AgentState =
    s.copy(session = s.session.copy(writesSinceLastRead = tracker))

  def withContextWindow(window: Int): AgentState =
    s.copy(session = s.session.copy(contextWindow = window))

  def withAskMode(mode: Option[String]): AgentState =
    s.copy(session = s.session.copy(askMode = mode))

  def withLanguage(lang: Option[String]): AgentState =
    s.copy(session = s.session.copy(language = lang))

  def withPendingCompaction(job: Option[CompactionJob]): AgentState =
    s.copy(compaction = s.compaction.copy(pendingJob = job))

  def withCompactionFailures(failures: Int): AgentState =
    s.copy(compaction = s.compaction.copy(compactionFailures = failures))

  def withLastCompactionFailureAt(ts: Long): AgentState =
    s.copy(compaction = s.compaction.copy(lastCompactionFailureAt = ts))

  def withEmptyResponseRetries(count: Int): AgentState =
    s.copy(execution = s.execution.copy(emptyResponseRetries = count))

  def withLatestUsage(usage: Option[TokenUsage]): AgentState =
    s.copy(compaction = s.compaction.copy(latestUsage = usage))

  def withLastModel(model: Option[String]): AgentState =
    s.copy(compaction = s.compaction.copy(lastModel = model))

  /** Update contextWindow only if the LLM reports a different value (e.g. after fallback). */
  def updateContextWindowIfNeeded(reported: Option[Int]): AgentState =
    reported match
      case Some(cw) if cw != s.session.contextWindow =>
        s.copy(session = s.session.copy(contextWindow = cw))
      case _ => s

  /** Reset execution state to idle — used by finishTurn, Interrupt, LlmFailed. */
  def resetToIdle(messages: List[Message], turnIdx: Int = s.execution.turnIdx): AgentState =
    s.copy(execution = ExecutionContext.idle(messages, turnIdx, s.execution.currentTurnId))

  /** Reset execution state for interrupt — clears everything including compaction pending. */
  def resetForInterrupt: AgentState = s.copy(
    execution = ExecutionContext.idle(s.execution.messages, s.execution.turnIdx, s.execution.currentTurnId),
    compaction = s.compaction.copy(pendingJob = None)
  )
end extension

// ============================================================
// Consume Result (extracted from Repl for actor use)
// ============================================================

case class ConsumeResult(
  text: String,
  toolCalls: List[ToolCall],
  results: List[(ToolCall, ToolExecResult)],
  stopReason: Option[String],
  usage: Option[TokenUsage] = None,
  thinking: Option[String] = None,
  thinkingSignature: Option[String] = None,
  model: Option[String] = None,
  contextWindow: Option[Int] = None
)
