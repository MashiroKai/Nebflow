package nebflow.agent

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.ActorRef
import nebflow.core.{AskItem, SystemReminder, ToolExecResult}
import nebflow.shared.*

sealed trait AgentCommand

object AgentCommand:

  case class UserInput(
    text: String,
    replyTo: Option[ActorRef[AgentEvent]] = None,
    clientMessageId: Option[String] = None,
    blocks: Option[List[ContentBlock]] = None,
    chatWidth: Int = 0
  ) extends AgentCommand

  case class ImmediateInput(
    text: String,
    blocks: Option[List[ContentBlock]] = None
  ) extends AgentCommand
  case class Interrupt() extends AgentCommand

  case class AskUser(
    requestId: String,
    items: List[AskItem],
    replyTo: Option[ActorRef[List[String]]] = None
  ) extends AgentCommand

  case class AskPermission(
    requestId: String,
    toolName: String,
    summary: String,
    replyTo: ActorRef[Boolean]
  ) extends AgentCommand

  case class LlmComplete(
    result: ConsumeResult,
    replyTo: Option[ActorRef[AgentEvent]],
    turnId: Long
  ) extends AgentCommand

  case class LlmFailed(
    error: Throwable,
    replyTo: Option[ActorRef[AgentEvent]],
    turnId: Long
  ) extends AgentCommand

  case class SetPermissionDeferred(deferred: cats.effect.Deferred[IO, Boolean]) extends AgentCommand

  case class ToolsComplete(
    results: List[(ToolCall, ToolExecResult)],
    originalText: String,
    replyTo: Option[ActorRef[AgentEvent]],
    compactedMessages: Option[List[Message]] = None,
    thinking: Option[String] = None,
    thinkingSignature: Option[String] = None
  ) extends AgentCommand

  case class CompactionComplete(result: Either[String, List[Message]]) extends AgentCommand

  case class TriggerCompaction(
    mode: String,
    replyDeferred: Option[cats.effect.Deferred[IO, Either[String, CompactionResult]]] = None
  ) extends AgentCommand

  case class Retry(reason: String) extends AgentCommand

  case class AskQuestion(question: String, sessionId: String) extends AgentCommand

  case class SkillActivate(
    skillName: String,
    input: String,
    sessionId: String,
    skillContent: String,
    skillBaseDir: String
  ) extends AgentCommand

  case class UpdateContextWindow(window: Int) extends AgentCommand

  case class ReplaceToolResults(
    rounds: Int,
    summary: String,
    replyTo: cats.effect.Deferred[IO, Either[String, Int]]
  ) extends AgentCommand

  case class UserAnswered(answers: List[String]) extends AgentCommand
  case class PermissionAnswered(approved: Boolean) extends AgentCommand

  /**
   * Sub-agent → parent: forward a permission request so the parent can track
   * the Deferred and route the frontend answer back to the sub-agent.
   */
  case class ForwardPermission(deferred: cats.effect.Deferred[IO, Boolean], permJson: Json) extends AgentCommand

  /** Frontend → agent: update bypass (auto-approve) status for this session. */
  case class SetBypass(bypass: Boolean) extends AgentCommand

  // ============================================================
  // Plan mode commands
  // ============================================================

  /** WebSocketRoutes → agent: start plan mode with the given task. */
  case class StartPlan(task: String) extends AgentCommand

  /** Plan adapter → agent: plan agent completed a turn, carrying the plan text. */
  case class PlanTurnComplete(planText: String) extends AgentCommand

  /** Plan adapter → agent: plan agent failed or terminated. */
  case class PlanFailed(error: String) extends AgentCommand

  /** Frontend → agent: user approved the plan. */
  case object PlanApproved extends AgentCommand

  /** Frontend → agent: user sent feedback to adjust the plan. */
  case class PlanFeedback(text: String) extends AgentCommand

  /** Frontend → agent: user cancelled plan mode. */
  case object PlanCancelled extends AgentCommand

  /** Plan tool → agent: register plan agent ref and optional deferred. */
  case class SetPlanState(
    planAgentRef: ActorRef[AgentCommand],
    deferred: Option[cats.effect.Deferred[IO, PlanResult]] = None
  ) extends AgentCommand

  case class Stop(reason: String) extends AgentCommand
  case object ClearReadTracker extends AgentCommand
  case object ResetSession extends AgentCommand

  case class UpdateLifecycle(lc: LifecycleContext) extends AgentCommand
  case class UpdateGitBranch(branch: Option[String]) extends AgentCommand

  case class BackgroundTaskNotification(
    taskId: String,
    description: String,
    status: String,
    output: String,
    exitCode: Option[Int] = None
  ) extends AgentCommand:

    def toExternalEvent: ExternalEvent = ExternalEvent(
      source = "background-task",
      eventType = status,
      payload = status match
        case "completed" =>
          val exitInfo = exitCode.filter(_ != 0).map(c => s" (exit code $c)").getOrElse("")
          s"[Background task completed] \"$description\"$exitInfo:\n$output"
        case "failed" => s"[Background task failed] \"$description\":\n$output"
        case _ => s"[Background task stopped] \"$description\"",
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

  case class ExternalEvent(
    source: String,
    eventType: String,
    payload: String,
    metadata: JsonObject = JsonObject.empty,
    correlationId: Option[String] = None
  ) extends AgentCommand

  case class SessionStarted(
    address: String,
    agentName: String,
    taskDescription: String
  ) extends AgentCommand

  case class SessionClosed(address: String) extends AgentCommand
  case class SessionUpdate(address: String, status: String) extends AgentCommand
end AgentCommand

/**
 * Tool/compaction pipeline error — distinct from LLM failures.
 * Carried via LlmFailed but pattern-matched to show correct message to user.
 */
case class ToolPipelineError(message: String) extends RuntimeException(message)

sealed trait AgentEvent

object AgentEvent:
  case class Completed(sessionId: String, messages: List[Message] = Nil) extends AgentEvent
  case class Failed(sessionId: String, error: AgentError) extends AgentEvent

enum AgentStreamEvent:
  case TextDelta(text: String)
  case ToolStart(label: String)
  case ToolEnd(label: String, summary: String, content: String, isError: Boolean, input: Option[JsonObject] = None)
  case AgentStart(agentName: String, agentType: String, taskDescription: Option[String] = None)
  case AgentEnd(agentName: String)
  case Thinking
  case ToolCallDetected(name: String)
  case RetryStatus(message: String)

  case Done(
    model: Option[String] = None,
    contextWindow: Option[Int] = None,
    inputTokens: Option[Int] = None,
    compactThreshold: Option[Double] = None
  )
  case UsageUpdate(inputTokens: Int, contextWindow: Int, compactThreshold: Double)
  case CompactStart(mode: String, inputTokens: Option[Int], threshold: Option[Int])
  case CompactComplete(before: Int, after: Int, reportPath: Option[String] = None)
  case CompactFailed(reason: String, attempt: Int, maxAttempts: Int)
  case BackgroundTaskUpdate(taskId: String, description: String, status: String)
  case ExternalEventReceived(source: String, eventType: String, correlationId: Option[String])
  case Interrupted

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
    case AgentStart(name, agentType, taskDescription) =>
      val base = Json.obj(
        "type" -> "agentStart".asJson,
        "agentId" -> agentId.asJson,
        "name" -> name.asJson,
        "agentType" -> agentType.asJson
      )
      taskDescription.fold(base)(desc => base.deepMerge(Json.obj("taskDescription" -> desc.asJson)))
    case AgentEnd(name) => Json.obj("type" -> "agentEnd".asJson, "agentId" -> agentId.asJson, "name" -> name.asJson)
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
    case Done(model, contextWindow, inputTokens, compactThreshold) =>
      val base =
        if isSubagent then Json.obj("type" -> "agentDone".asJson, "agentId" -> agentId.asJson)
        else Json.obj("type" -> "done".asJson, "sessionId" -> sessionId.asJson)
      val withModel = model.fold(base)(m => base.deepMerge(Json.obj("model" -> m.asJson)))
      val withCw = contextWindow.fold(withModel)(cw => withModel.deepMerge(Json.obj("contextWindow" -> cw.asJson)))
      val withIt = inputTokens.fold(withCw)(it => withCw.deepMerge(Json.obj("inputTokens" -> it.asJson)))
      compactThreshold.fold(withIt)(ct => withIt.deepMerge(Json.obj("compactThreshold" -> ct.asJson)))
    case UsageUpdate(inputTokens, contextWindow, compactThreshold) =>
      Json.obj(
        "type" -> "usageUpdate".asJson,
        "sessionId" -> sessionId.asJson,
        "inputTokens" -> inputTokens.asJson,
        "contextWindow" -> contextWindow.asJson,
        "compactThreshold" -> compactThreshold.asJson
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
      val base =
        if isSubagent then
          Json.obj(
            "type" -> "agentCompactComplete".asJson,
            "agentId" -> agentId.asJson,
            "before" -> before.asJson,
            "after" -> after.asJson
          )
        else
          Json.obj(
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
      val base =
        Json.obj("type" -> "externalEventReceived".asJson, "source" -> source.asJson, "eventType" -> eventType.asJson)
      val withSession =
        if isSubagent then base.deepMerge(Json.obj("agentId" -> agentId.asJson))
        else base.deepMerge(Json.obj("sessionId" -> sessionId.asJson))
      correlationId.fold(withSession)(id => withSession.deepMerge(Json.obj("correlationId" -> id.asJson)))
    case Interrupted =>
      val base = Json.obj("type" -> "interrupted".asJson)
      if isSubagent then base.deepMerge(Json.obj("agentId" -> agentId.asJson))
      else base.deepMerge(Json.obj("sessionId" -> sessionId.asJson))
end AgentStreamEvent

case class AgentInfo(
  name: String,
  description: String,
  tools: List[String],
  displayName: Option[String] = None,
  avatar: Option[String] = None
)

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

enum AgentStatus:
  case Idle
  case Processing
  case WaitingForUser
  case Error(msg: String)

case class CompactionResult(before: Int, after: Int)

case class CompactionJob(
  subagentId: String,
  mode: String,
  replyDeferred: Option[cats.effect.Deferred[IO, Either[String, CompactionResult]]] = None,
  replyTo: Option[ActorRef[AgentEvent]] = None,
  resumeAfterCompact: Boolean = true
)

case class TurnContext(
  agentDef: AgentDef,
  systemPrefix: String,
  projectRoot: Option[String],
  rulesMd: Option[String],
  thinkingConfig: nebflow.llm.ThinkingConfig,
  branchChange: Option[SystemReminder] = None,
  currentBranch: Option[String] = None
)

case class LifecycleContext(
  systemPrefix: String,
  agentDef: AgentDef,
  rulesMd: Option[String],
  projectRoot: Option[String]
)

case class SessionContext(
  sessionId: Option[String] = None,
  sessionName: Option[String] = None,
  recentMessageIds: List[String] = Nil,
  wsSend: Json => IO[Unit] = _ => IO.unit,
  depth: Int = 0,
  readTracker: Option[nebflow.core.tools.ReadTracker] = None,
  fileHistory: Option[nebflow.core.tools.FileHistory] = None,
  liveFileTracker: Option[nebflow.core.tools.LiveFileTracker] = None,
  contextWindow: Int = nebflow.shared.Defaults.ContextWindow,
  askMode: Option[String] = None,
  language: Option[String] = None,
  projectRoot: Option[String] = None,
  rulesMd: Option[String] = None,
  folderId: Option[String] = None,
  lifecycle: Option[LifecycleContext] = None,
  chatWidth: Int = 0,
  gitBranch: Option[String] = None,
  bypass: Boolean = false
)

case class InteractionState(
  pendingAskUser: Option[cats.effect.Deferred[IO, List[String]]] = None,
  pendingPermission: Option[cats.effect.Deferred[IO, Boolean]] = None,
  pendingAskUserReplyTo: Option[ActorRef[List[String]]] = None
)

/** Tracks what was last dispatched (LLM call or tool execution) for Retry. */
case class LastDispatch(
  isToolExecution: Boolean,
  llmResult: Option[ConsumeResult] = None
)

case class ExecutionContext(
  messages: List[Message] = Nil,
  status: AgentStatus = AgentStatus.Idle,
  turnIdx: Int = 0,
  currentTurnId: Long = 0L,
  interaction: Option[InteractionState] = None,
  pendingEvents: List[AgentCommand.ExternalEvent] = Nil,
  pendingImmediateInputs: List[AgentCommand.ImmediateInput] = Nil,
  emptyResponseRetries: Int = 0,
  lastDispatch: Option[LastDispatch] = None
)

object ExecutionContext:

  def idle(messages: List[Message], turnIdx: Int = 0, currentTurnId: Long = 0L): ExecutionContext =
    ExecutionContext(
      messages = messages,
      status = AgentStatus.Idle,
      turnIdx = turnIdx,
      currentTurnId = currentTurnId,
      interaction = None,
      pendingEvents = Nil,
      pendingImmediateInputs = Nil,
      emptyResponseRetries = 0
    )
end ExecutionContext

case class CompactionState(
  pendingJob: Option[CompactionJob] = None,
  compactionFailures: Int = 0,
  lastCompactionFailureAt: Long = 0L,
  latestUsage: Option[TokenUsage] = None,
  lastModel: Option[String] = None
)

case class AgentSessionInfo(
  address: String,
  agentName: String,
  taskDescription: String,
  status: String = "running",
  createdAt: Long = System.currentTimeMillis()
)

// ============================================================
// Plan mode
// ============================================================

/** Result of a plan session — either approved with plan text, or cancelled. */
sealed trait PlanResult
object PlanResult:
  case class Approved(planText: String) extends PlanResult
  case object Cancelled extends PlanResult

/**
 * Tracks active plan mode state on the main agent.
 *
 * @param planAgentRef   ref to the plan sub-agent (for forwarding feedback)
 * @param currentPlanText  latest plan text from the plan agent's last turn
 * @param deferred       Some when triggered via Plan tool (main agent in processing,
 *                       blocked on deferred.get); None when triggered via /plan
 *                         (main agent in planWaiting state)
 * @param taskDescription  the original user task, for context injection on approve
 */
case class PlanModeState(
  planAgentRef: ActorRef[AgentCommand],
  currentPlanText: String = "",
  deferred: Option[cats.effect.Deferred[IO, PlanResult]] = None,
  taskDescription: String = ""
)

case class AgentState(
  session: SessionContext,
  execution: ExecutionContext,
  compaction: CompactionState,
  agentSessions: List[AgentSessionInfo],
  planMode: Option[PlanModeState]
)

object AgentState:

  def apply(
    messages: List[Message] = Nil,
    status: AgentStatus = AgentStatus.Idle,
    depth: Int = 0,
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
    liveFileTracker: Option[nebflow.core.tools.LiveFileTracker] = None,
    recentMessageIds: List[String] = Nil,
    contextWindow: Int = nebflow.shared.Defaults.ContextWindow,
    projectRoot: Option[String] = None,
    rulesMd: Option[String] = None,
    folderId: Option[String] = None,
    bypass: Boolean = false
  ): AgentState =
    val interaction = (pendingAskUser, pendingPermission) match
      case (None, None) => None
      case _ => Some(InteractionState(pendingAskUser, pendingPermission))
    new AgentState(
      SessionContext(
        sessionId = sessionId,
        sessionName = sessionName,
        recentMessageIds = recentMessageIds,
        wsSend = wsSend,
        depth = depth,
        readTracker = readTracker,
        fileHistory = fileHistory,
        liveFileTracker = liveFileTracker,
        contextWindow = contextWindow,
        folderId = folderId,
        projectRoot = projectRoot,
        rulesMd = rulesMd,
        bypass = bypass
      ),
      ExecutionContext(messages, status, turnIdx, 0L, interaction),
      CompactionState(pendingCompaction, compactionFailures, 0L, latestUsage),
      Nil,
      None
    )
  end apply
end AgentState

extension (s: AgentState)
  def messages: List[Message] = s.execution.messages
  def status: AgentStatus = s.execution.status
  def sessionId: Option[String] = s.session.sessionId
  def sessionName: Option[String] = s.session.sessionName
  def wsSend: Json => IO[Unit] = s.session.wsSend
  def depth: Int = s.session.depth
  def turnIdx: Int = s.execution.turnIdx
  def currentTurnId: Long = s.execution.currentTurnId
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
  def liveFileTracker: Option[nebflow.core.tools.LiveFileTracker] = s.session.liveFileTracker
  def contextWindow: Int = s.session.contextWindow
  def askMode: Option[String] = s.session.askMode
  def language: Option[String] = s.session.language
  def projectRoot: Option[String] = s.session.projectRoot
  def rulesMd: Option[String] = s.session.rulesMd
  def folderId: Option[String] = s.session.folderId
  def lifecycle: Option[LifecycleContext] = s.session.lifecycle
  def gitBranch: Option[String] = s.session.gitBranch
  def bypass: Boolean = s.session.bypass

  def withSession(session: SessionContext): AgentState = s.copy(session = session)
  def withExecution(execution: ExecutionContext): AgentState = s.copy(execution = execution)
  def withCompaction(compaction: CompactionState): AgentState = s.copy(compaction = compaction)
  def withAgentSessions(sessions: List[AgentSessionInfo]): AgentState = s.copy(agentSessions = sessions)
  def withMessages(msgs: List[Message]): AgentState = s.copy(execution = s.execution.copy(messages = msgs))
  def withStatus(st: AgentStatus): AgentState = s.copy(execution = s.execution.copy(status = st))
  def withTurnIdx(idx: Int): AgentState = s.copy(execution = s.execution.copy(turnIdx = idx))
  def withCurrentTurnId(id: Long): AgentState = s.copy(execution = s.execution.copy(currentTurnId = id))

  def withInteraction(interaction: Option[InteractionState]): AgentState =
    s.copy(execution = s.execution.copy(interaction = interaction))

  def withLastDispatch(d: Option[LastDispatch]): AgentState =
    s.copy(execution = s.execution.copy(lastDispatch = d))

  def lastDispatch: Option[LastDispatch] = s.execution.lastDispatch

  def withPendingAskUser(d: Option[cats.effect.Deferred[IO, List[String]]]): AgentState =
    s.copy(execution =
      s.execution.copy(interaction =
        Some(s.execution.interaction.getOrElse(InteractionState()).copy(pendingAskUser = d))
      )
    )

  def withPendingPermission(d: Option[cats.effect.Deferred[IO, Boolean]]): AgentState =
    s.copy(execution =
      s.execution.copy(interaction =
        Some(s.execution.interaction.getOrElse(InteractionState()).copy(pendingPermission = d))
      )
    )
  def withRecentMessageIds(ids: List[String]): AgentState = s.copy(session = s.session.copy(recentMessageIds = ids))
  def withContextWindow(window: Int): AgentState = s.copy(session = s.session.copy(contextWindow = window))
  def withAskMode(mode: Option[String]): AgentState = s.copy(session = s.session.copy(askMode = mode))
  def withLanguage(lang: Option[String]): AgentState = s.copy(session = s.session.copy(language = lang))

  def withPendingCompaction(job: Option[CompactionJob]): AgentState =
    s.copy(compaction = s.compaction.copy(pendingJob = job))

  def withCompactionFailures(failures: Int): AgentState =
    s.copy(compaction = s.compaction.copy(compactionFailures = failures))

  def withLastCompactionFailureAt(ts: Long): AgentState =
    s.copy(compaction = s.compaction.copy(lastCompactionFailureAt = ts))

  def withEmptyResponseRetries(count: Int): AgentState =
    s.copy(execution = s.execution.copy(emptyResponseRetries = count))
  def withLifecycle(lc: LifecycleContext): AgentState = s.copy(session = s.session.copy(lifecycle = Some(lc)))
  def withLifecycleCleared: AgentState = s.copy(session = s.session.copy(lifecycle = None))
  def withGitBranch(branch: Option[String]): AgentState = s.copy(session = s.session.copy(gitBranch = branch))
  def withBypass(b: Boolean): AgentState = s.copy(session = s.session.copy(bypass = b))

  def withPlanMode(pm: Option[PlanModeState]): AgentState = s.copy(planMode = pm)

  def withLatestUsage(usage: Option[TokenUsage]): AgentState =
    s.copy(compaction = s.compaction.copy(latestUsage = usage))
  def withLastModel(model: Option[String]): AgentState = s.copy(compaction = s.compaction.copy(lastModel = model))

  def updateContextWindowIfNeeded(reported: Option[Int]): AgentState = reported match
    case Some(cw) if cw != s.session.contextWindow => s.copy(session = s.session.copy(contextWindow = cw))
    case _ => s

  def resetToIdle(messages: List[Message], turnIdx: Int = s.execution.turnIdx): AgentState =
    s.copy(execution = ExecutionContext.idle(messages, turnIdx, s.execution.currentTurnId))

  def resetForInterrupt: AgentState = s.copy(
    execution = ExecutionContext.idle(s.execution.messages, s.execution.turnIdx, s.execution.currentTurnId),
    compaction = s.compaction.copy(pendingJob = None)
  )
end extension

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
