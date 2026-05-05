package nebflow.agent

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.{AskItem, ToolExecResult}
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
    clientMessageId: Option[String] = None
  ) extends AgentCommand
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
    thinking: Option[String] = None,
    nextStagnationCount: Option[Int] = None,
    nextStage: Option[AdaptiveStage] = None,
    nextProgressStreak: Option[Int] = None
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

  // User interaction responses
  case class UserAnswered(answers: List[String]) extends AgentCommand
  case class PermissionAnswered(approved: Boolean) extends AgentCommand

  // Lifecycle
  case class Stop(reason: String) extends AgentCommand

  // Peer communication (root agent to root agent via ActorRef)
  case class MessageToAgent(
    targetRef: org.apache.pekko.actor.typed.ActorRef[AgentCommand],
    payload: String,
    replyTo: Option[org.apache.pekko.actor.typed.ActorRef[AgentCommand]] = None
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
  case ToolEnd(label: String, summary: String, content: String, isError: Boolean, input: Option[JsonObject] = None)
  case AgentStart(agentName: String, agentType: String)
  case AgentEnd(agentName: String)
  case Thinking
  case RetryStatus(message: String)
  case Done
  case ProgressUpdate(turnIdx: Int, stagnationCount: Int, stage: String)
  case Paused(summary: String)
  case CompactStart(mode: String, inputTokens: Option[Int], threshold: Option[Int])
  case CompactComplete(before: Int, after: Int, snapshotPath: Option[String], comparisonPath: Option[String] = None)
  case CompactFailed(reason: String, attempt: Int, maxAttempts: Int)

  def toJson(agentId: String, isSubagent: Boolean = true, sessionId: Option[String] = None): Json = this match
    case TextDelta(text) =>
      if isSubagent then
        Json.obj("type" -> "agentTextDelta".asJson, "agentId" -> agentId.asJson, "delta" -> text.asJson)
      else Json.obj("type" -> "textDelta".asJson, "sessionId" -> sessionId.asJson, "delta" -> text.asJson)
    case ToolStart(label) =>
      if isSubagent then
        Json.obj("type" -> "agentToolStart".asJson, "agentId" -> agentId.asJson, "label" -> label.asJson)
      else Json.obj("type" -> "toolStart".asJson, "sessionId" -> sessionId.asJson, "label" -> label.asJson)
    case ToolEnd(label, summary, content, isError, input) =>
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
      if isSubagent then Json.obj("type" -> "agentThinking".asJson, "agentId" -> agentId.asJson)
      else Json.obj("type" -> "thinking".asJson, "sessionId" -> sessionId.asJson)
    case RetryStatus(message) =>
      if isSubagent then
        Json.obj("type" -> "agentRetryStatus".asJson, "agentId" -> agentId.asJson, "message" -> message.asJson)
      else Json.obj("type" -> "retryStatus".asJson, "sessionId" -> sessionId.asJson, "message" -> message.asJson)
    case Done =>
      if isSubagent then Json.obj("type" -> "agentDone".asJson, "agentId" -> agentId.asJson)
      else Json.obj("type" -> "done".asJson, "sessionId" -> sessionId.asJson)
    case ProgressUpdate(turnIdx, stagnationCount, stage) =>
      if isSubagent then
        Json.obj(
          "type" -> "agentProgressUpdate".asJson,
          "agentId" -> agentId.asJson,
          "turnIdx" -> turnIdx.asJson,
          "stagnationCount" -> stagnationCount.asJson,
          "stage" -> stage.asJson
        )
      else
        Json.obj(
          "type" -> "progressUpdate".asJson,
          "sessionId" -> sessionId.asJson,
          "turnIdx" -> turnIdx.asJson,
          "stagnationCount" -> stagnationCount.asJson,
          "stage" -> stage.asJson
        )
    case Paused(summary) =>
      if isSubagent then
        Json.obj(
          "type" -> "agentPaused".asJson,
          "agentId" -> agentId.asJson,
          "summary" -> summary.asJson
        )
      else
        Json.obj(
          "type" -> "paused".asJson,
          "sessionId" -> sessionId.asJson,
          "summary" -> summary.asJson
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
    case CompactComplete(before, after, snapshotPath, comparisonPath) =>
      if isSubagent then
        val base = Json.obj(
          "type" -> "agentCompactComplete".asJson,
          "agentId" -> agentId.asJson,
          "before" -> before.asJson,
          "after" -> after.asJson
        )
        val withSnapshot = snapshotPath.fold(base)(p => base.deepMerge(Json.obj("snapshotPath" -> p.asJson)))
        comparisonPath.fold(withSnapshot)(p => withSnapshot.deepMerge(Json.obj("comparisonPath" -> p.asJson)))
      else
        val base = Json.obj(
          "type" -> "compactComplete".asJson,
          "sessionId" -> sessionId.asJson,
          "before" -> before.asJson,
          "after" -> after.asJson
        )
        val withSnapshot = snapshotPath.fold(base)(p => base.deepMerge(Json.obj("snapshotPath" -> p.asJson)))
        comparisonPath.fold(withSnapshot)(p => withSnapshot.deepMerge(Json.obj("comparisonPath" -> p.asJson)))
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

end AgentStreamEvent

// ============================================================
// Shared response types
// ============================================================

case class AgentInfo(name: String, description: String, tools: List[String], subagents: List[String])

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

enum AdaptiveStage:
  case Normal, Cautious, Conservative, Paused

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

object ToolCallRecord:

  /** Recursively sort JSON keys for deterministic hashing. */
  def canonicalHash(input: JsonObject): String =
    canonicalJsonObject(input).noSpaces

  private def canonicalJson(json: Json): Json = json match
    case j if j.isObject => j.asObject.fold(json)(canonicalJsonObject)
    case j if j.isArray => Json.fromValues(j.asArray.fold(Seq.empty[Json])(_.map(canonicalJson)))
    case other => other

  private def canonicalJsonObject(obj: JsonObject): Json =
    Json.fromFields(obj.toList.sortBy(_._1).map((k, v) => k -> canonicalJson(v)))
end ToolCallRecord

/** A pending or in-progress compaction sub-agent task. */
case class CompactionJob(
  subagentId: String,
  mode: String,
  replyDeferred: Option[cats.effect.Deferred[IO, Either[String, CompactionResult]]] = None,
  replyTo: Option[org.apache.pekko.actor.typed.ActorRef[AgentEvent]] = None
)

// ============================================================
// AgentState sub-structures — grouped by business domain
// ============================================================

/** Session-level persistent state — survives across turns. */
case class SessionContext(
  sessionId: Option[String] = None,
  recentMessageIds: List[String] = Nil,
  wsSend: Json => IO[Unit] = _ => IO.unit,
  depth: Int = 0,
  readTracker: Option[nebflow.core.tools.ReadTracker] = None
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
  subagents: Map[String, org.apache.pekko.actor.typed.ActorRef[AgentCommand]] = Map.empty,
  activeStreamFiber: Option[cats.effect.Fiber[IO, Throwable, Unit]] = None,
  interaction: Option[InteractionState] = None
)

object ExecutionContext:

  /** Factory for idle state — centralizes all per-turn reset logic. */
  def idle(messages: List[Message], turnIdx: Int = 0): ExecutionContext =
    ExecutionContext(
      messages = messages,
      status = AgentStatus.Idle,
      turnIdx = turnIdx,
      subagents = Map.empty,
      activeStreamFiber = None,
      interaction = None
    )
end ExecutionContext

/** Anti-loop and adaptive stage state. */
case class SafetyContext(
  recentToolCalls: List[ToolCallRecord] = Nil,
  recentFilesRead: Set[String] = Set.empty,
  hasInjectedAntiLoop: Boolean = false,
  stagnationCount: Int = 0,
  stage: AdaptiveStage = AdaptiveStage.Normal,
  progressStreak: Int = 0
)

/** Context compression state. */
case class CompactionState(
  pendingJob: Option[CompactionJob] = None,
  compactionFailures: Int = 0,
  latestUsage: Option[TokenUsage] = None
)

/** Top-level agent state — composed of domain-specific sub-structures. */
case class AgentState(
  session: SessionContext,
  execution: ExecutionContext,
  safety: SafetyContext,
  compaction: CompactionState
)

object AgentState:

  /** Convenience constructor for common case. */
  def apply(
    messages: List[Message] = Nil,
    status: AgentStatus = AgentStatus.Idle,
    depth: Int = 0,
    subagents: Map[String, org.apache.pekko.actor.typed.ActorRef[AgentCommand]] = Map.empty,
    activeStreamFiber: Option[cats.effect.Fiber[IO, Throwable, Unit]] = None,
    sessionId: Option[String] = None,
    pendingCompaction: Option[CompactionJob] = None,
    compactionFailures: Int = 0,
    latestUsage: Option[TokenUsage] = None,
    pendingAskUser: Option[cats.effect.Deferred[IO, List[String]]] = None,
    pendingPermission: Option[cats.effect.Deferred[IO, Boolean]] = None,
    recentToolCalls: List[ToolCallRecord] = Nil,
    turnIdx: Int = 0,
    wsSend: Json => IO[Unit] = _ => IO.unit,
    hasInjectedAntiLoop: Boolean = false,
    recentFilesRead: Set[String] = Set.empty,
    stagnationCount: Int = 0,
    stage: AdaptiveStage = AdaptiveStage.Normal,
    progressStreak: Int = 0,
    readTracker: Option[nebflow.core.tools.ReadTracker] = None,
    recentMessageIds: List[String] = Nil
  ): AgentState =
    val interaction = (pendingAskUser, pendingPermission) match
      case (None, None) => None
      case _ => Some(InteractionState(pendingAskUser, pendingPermission))
    new AgentState(
      SessionContext(sessionId, recentMessageIds, wsSend, depth, readTracker),
      ExecutionContext(messages, status, turnIdx, subagents, activeStreamFiber, interaction),
      SafetyContext(recentToolCalls, recentFilesRead, hasInjectedAntiLoop, stagnationCount, stage, progressStreak),
      CompactionState(pendingCompaction, compactionFailures, latestUsage)
    )
  end apply
end AgentState

extension (s: AgentState)
  // Read accessors — allow existing code to read fields without sub-structure paths
  def messages: List[Message] = s.execution.messages
  def status: AgentStatus = s.execution.status
  def sessionId: Option[String] = s.session.sessionId
  def wsSend: Json => IO[Unit] = s.session.wsSend
  def depth: Int = s.session.depth
  def turnIdx: Int = s.execution.turnIdx
  def subagents: Map[String, org.apache.pekko.actor.typed.ActorRef[AgentCommand]] = s.execution.subagents
  def activeStreamFiber: Option[cats.effect.Fiber[IO, Throwable, Unit]] = s.execution.activeStreamFiber
  def recentMessageIds: List[String] = s.session.recentMessageIds
  def pendingCompaction: Option[CompactionJob] = s.compaction.pendingJob
  def compactionFailures: Int = s.compaction.compactionFailures
  def latestUsage: Option[TokenUsage] = s.compaction.latestUsage
  def pendingAskUser: Option[cats.effect.Deferred[IO, List[String]]] = s.execution.interaction.flatMap(_.pendingAskUser)

  def pendingPermission: Option[cats.effect.Deferred[IO, Boolean]] =
    s.execution.interaction.flatMap(_.pendingPermission)
  def recentToolCalls: List[ToolCallRecord] = s.safety.recentToolCalls
  def recentFilesRead: Set[String] = s.safety.recentFilesRead
  def hasInjectedAntiLoop: Boolean = s.safety.hasInjectedAntiLoop
  def stagnationCount: Int = s.safety.stagnationCount
  def stage: AdaptiveStage = s.safety.stage
  def progressStreak: Int = s.safety.progressStreak
  def readTracker: Option[nebflow.core.tools.ReadTracker] = s.session.readTracker

  // Mutation helpers — return new AgentState with updated sub-structure
  def withSession(session: SessionContext): AgentState = s.copy(session = session)
  def withExecution(execution: ExecutionContext): AgentState = s.copy(execution = execution)
  def withSafety(safety: SafetyContext): AgentState = s.copy(safety = safety)
  def withCompaction(compaction: CompactionState): AgentState = s.copy(compaction = compaction)

  def withMessages(msgs: List[Message]): AgentState = s.copy(execution = s.execution.copy(messages = msgs))
  def withStatus(st: AgentStatus): AgentState = s.copy(execution = s.execution.copy(status = st))
  def withTurnIdx(idx: Int): AgentState = s.copy(execution = s.execution.copy(turnIdx = idx))

  def withSubagents(subs: Map[String, org.apache.pekko.actor.typed.ActorRef[AgentCommand]]): AgentState =
    s.copy(execution = s.execution.copy(subagents = subs))

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

  def withPendingCompaction(job: Option[CompactionJob]): AgentState =
    s.copy(compaction = s.compaction.copy(pendingJob = job))

  def withCompactionFailures(failures: Int): AgentState =
    s.copy(compaction = s.compaction.copy(compactionFailures = failures))

  def withLatestUsage(usage: Option[TokenUsage]): AgentState =
    s.copy(compaction = s.compaction.copy(latestUsage = usage))

  def withRecentToolCalls(calls: List[ToolCallRecord]): AgentState =
    s.copy(safety = s.safety.copy(recentToolCalls = calls))
  def withRecentFilesRead(files: Set[String]): AgentState = s.copy(safety = s.safety.copy(recentFilesRead = files))
  def withStagnationCount(count: Int): AgentState = s.copy(safety = s.safety.copy(stagnationCount = count))
  def withStage(st: AdaptiveStage): AgentState = s.copy(safety = s.safety.copy(stage = st))
  def withProgressStreak(streak: Int): AgentState = s.copy(safety = s.safety.copy(progressStreak = streak))

  /** Reset execution state to idle — used by finishTurn, Interrupt, LlmFailed. */
  def resetToIdle(messages: List[Message], turnIdx: Int = s.execution.turnIdx): AgentState =
    s.copy(execution = ExecutionContext.idle(messages, turnIdx))

  /** Reset execution state for interrupt — clears everything including compaction pending. */
  def resetForInterrupt: AgentState = s.copy(
    execution = ExecutionContext.idle(s.execution.messages, s.execution.turnIdx),
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
  thinking: Option[String] = None
)
