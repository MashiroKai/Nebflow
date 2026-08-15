package nebflow.agent

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.actor.*
import nebflow.agent.AgentCommand.*
import nebflow.agent.PromptSections.*
import nebflow.core.*
import nebflow.core.compact.*
import nebflow.core.hooks.*
import nebflow.core.tools.*
import nebflow.shared.*
import nebflow.shared.given

import scala.concurrent.duration.*

private[agent] trait AgentCore:

  protected val MaxDepth = 5

  /**
   * Timeout for permission confirmation. Prevents indefinite session lockup
   * when the user doesn't respond (popup missed, WS issue, away from keyboard).
   */
  private val PermissionTimeout = 5.minutes

  /**
   * Nebula-exclusive tools: only available when agentName == "Nebula".
   * - Schedule: session-scoped scheduled tasks
   * - Delegate: 调度器/根 agent 专用——指派 standalone agent。
   *   Team 成员委派走 SubTaskTool（self-clone + ephemeral）。
   *   Flow 触发不在此列——FlowTrigger 由 agent.json flows 白名单驱动注入。
   */
  private val NebulaExclusiveTools = AgentCore.NebulaExclusiveTools

  /** Tools available to Nebula and Team Leads, but NOT workers. */
  private val LeadLevelTools = AgentCore.LeadLevelTools

  private val lifecycleLog = NebflowLogger.forName("nebflow.agent.lifecycle")

  private[agent] type ProcessingFn =
    (AgentDef, SharedResources, Int, Option[ActorRef[AgentCommand]], AgentState) => Behavior[AgentCommand]

  private def shortUuid(id: String): String =
    if id.startsWith("agent-") then id.drop(6).take(8)
    else if id == "-" || id == "system" then id
    else id.take(8)

  protected def logAgentEvent(
    agentDef: AgentDef,
    depth: Int,
    sessionId: Option[String],
    sessionName: Option[String],
    event: String,
    detail: String = ""
  )(using ctx: ActorContext[AgentCommand]): Unit =
    val sid = shortUuid(sessionId.getOrElse("-"))
    val sname = sessionName.getOrElse("-")
    val who = if depth == 0 then s"${agentDef.name}-${shortUuid(ctx.self.path.name)}" else s"subagent-${agentDef.name}"
    val logCtx = lifecycleLog.ctxPrefix(who, s"$sname/$sid")
    lifecycleLog.infoSync(if detail.nonEmpty then s"$logCtx event=$event detail=$detail" else s"$logCtx event=$event")

  protected def persistIfSession(resources: SharedResources, state: AgentState): IO[Unit] =
    state.sessionId match
      case Some(sid) => resources.sessionStore.saveMessagesForSession(sid, state.messages)
      case None => IO.unit

  protected def maybeAutoCompact(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    replyTo: Option[ActorRef[AgentEvent]],
    processing: ProcessingFn
  )(using ctx: ActorContext[AgentCommand]): Option[IO[Behavior[AgentCommand]]] =
    if state.pendingCompaction.isDefined then None
    else
      val config = CompactConfig()
      val backoffOk =
        if state.compactionFailures == 0 then true
        else
          val elapsed = System.currentTimeMillis() - state.lastCompactionFailureAt
          val ok = config.isBackoffSatisfied(state.compactionFailures, elapsed)
          if !ok then
            logAgentEvent(
              agentDef,
              depth,
              state.sessionId,
              state.sessionName,
              "auto-compact-skipped",
              s"backoff=${config.backoffMs(state.compactionFailures)}ms elapsed=${elapsed}ms failures=${state.compactionFailures}"
            )
          ok
      if !backoffOk then None
      else if state.compactionFailures >= config.circuitBreakerMax then
        if !config.emergencyAutoFallback then
          logAgentEvent(
            agentDef,
            depth,
            state.sessionId,
            state.sessionName,
            "auto-compact-skipped",
            s"circuitBreakerOpen failures=${state.compactionFailures} max=${config.circuitBreakerMax}"
          )
          None
        else
          // Emergency fallback: non-LLM rule-based compaction.
          // Strips tool results, removes old tool-result pairs, truncates to last N.
          logAgentEvent(
            agentDef,
            depth,
            state.sessionId,
            state.sessionName,
            "emergency-compact-trigger",
            s"circuitBreakerOpen failures=${state.compactionFailures} messages=${state.messages.size}"
          )
          val (cleaned, desc) = CompactUtils.emergencyClean(state.messages, config.emergencyKeepMessages)
          val emergencyState = state
            .withMessages(cleaned)
            .withCompactionFailures(0)
            .withLatestUsage(None)
          Some(for
            _ <- ctx.forkTurn(
              emitStreamIO(
                state.wsSend,
                AgentStreamEvent.CompactComplete(state.messages.size, cleaned.size, None),
                isSubagent = depth > 0,
                state.sessionId
              ).handleErrorWith(_ => IO.unit)
            )
            _ <- state.sessionId.fold(IO.unit)(sid =>
              ctx.forkTurn(
                resources.historyArchiver
                  .archiveCompaction(
                    sessionId = sid,
                    sessionName = state.sessionName,
                    agentName = agentDef.name,
                    before = state.messages,
                    after = cleaned,
                    mode = "emergency",
                    extra = Map("description" -> desc)
                  )
                  .void
                  .handleErrorWith(_ => IO.unit)
              )
            )
            result <- pipeLlmCall(agentDef, resources, depth, parentRef, emergencyState, replyTo, processing)
          yield result)
      else
        val inputTokensOpt = state.latestUsage.map(_.inputTokens)
        // Unified threshold: hardcoded, role-independent (CompactThreshold).
        val threshold = CompactThreshold.threshold(state.contextWindow)
        val shouldCompact = inputTokensOpt match
          case Some(inputTokens) if inputTokens > 0 && inputTokens > threshold =>
            Some(s"inputTokens=$inputTokens threshold=$threshold")
          case _ =>
            val estimated = TokenEstimator.estimate(state.messages)
            if estimated > threshold then
              Some(s"estimated=$estimated threshold=$threshold (provider did not report inputTokens)")
            else None
        shouldCompact match
          case Some(detail) =>
            logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "auto-compact-trigger", detail)
            Some(startDirectCompaction(agentDef, resources, depth, parentRef, state, replyTo, processing, "full"))
          case None => None

      end if

  /**
   * Whether this agent gets a save-memory turn before compaction.
   * Precisely mirrors ContextRefresher's memory-injection condition:
   * Nebula (depth 0) and team agents (Manager/Worker) get memory; standalone
   * and flow agents don't — so no save turn for them (behavior unchanged).
   */
  protected def shouldInjectSaveReminder(agentDef: AgentDef, state: AgentState): IO[Boolean] =
    if agentDef.name == "Nebula" then IO.pure(true)
    else
      state.sessionId match
        case Some(sid) => nebflow.core.flow.TeamSessionRegistry.teamOfSession(sid).map(_.isDefined)
        case None => IO.pure(false)

  protected def startDirectCompaction(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    replyTo: Option[ActorRef[AgentEvent]],
    processing: ProcessingFn,
    mode: String,
    resumeAfterCompact: Boolean = true,
    postCompactInstruction: Option[String] = None
  )(using ctx: ActorContext[AgentCommand]): IO[Behavior[AgentCommand]] =
    val profile = CompactionProfile.fromDepth(depth)
    val hook = PreCompactionHooks.forProfile(profile)

    // ── 1. Role-based pre-compaction extraction (fire-and-forget, non-blocking) ──
    val preHookIO: IO[Unit] = hook
      .run(state.messages, agentDef.name, state.sessionId, None, resources)
      .handleErrorWith(e =>
        IO(lifecycleLog.warn(s"Pre-compaction hook failed for ${agentDef.name}: ${e.getMessage}")).void
      )

    // ── 2. Two-stage compaction ──
    //    Stage 1 (Save): memory-bearing agents (Nebula + team) get a save turn
    //    with tools available to write memory/skills; ending the turn transitions
    //    to stage 2. Other agents go straight to stage 2 (compact) — unchanged.
    val jobId = s"compact-${java.util.UUID.randomUUID().toString.take(8)}"
    for
      saveTurn <- shouldInjectSaveReminder(agentDef, state)
      phase = if saveTurn then CompactionPhase.Save else CompactionPhase.Compact
      reminder =
        if saveTurn then CompactService.buildSaveMemoryReminder(depth)
        else CompactService.buildCompactReminder(depth)
      pending = CompactionJob(jobId, mode, None, replyTo, resumeAfterCompact, postCompactInstruction, phase)
      firstState = state
        .withPendingCompaction(Some(pending))
        .withMessages(state.messages :+ reminder)
      _ <- ctx.forkTurn(preHookIO)
      _ <- ctx.forkTurn(
        emitStreamIO(
          state.wsSend,
          AgentStreamEvent.CompactStart(
            mode,
            state.latestUsage.map(_.inputTokens),
            Some(CompactThreshold.threshold(state.contextWindow))
          ),
          isSubagent = depth > 0,
          state.sessionId
        ).handleErrorWith(_ => IO.unit)
      )
      result <- pipeLlmCall(agentDef, resources, depth, parentRef, firstState, replyTo, processing)
    yield result

    end for

  end startDirectCompaction

  protected def pipeLlmCall(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    replyTo: Option[ActorRef[AgentEvent]],
    processing: ProcessingFn
  )(using ctx: ActorContext[AgentCommand]): IO[Behavior[AgentCommand]] =
    maybeAutoCompact(agentDef, resources, depth, parentRef, state, replyTo, processing) match
      case Some(ioBehavior) => ioBehavior
      case None =>
        // Phase-aware compaction: Save turn keeps tools available (memory write),
        // Compact turn disables tools (existing behavior).
        val isCompactTurn = state.pendingCompaction.exists(_.phase == CompactionPhase.Compact)
        val isSaveTurn = state.pendingCompaction.exists(_.phase == CompactionPhase.Save)
        val isAskTurn = state.askMode.isDefined
        val tools = if isCompactTurn then Some(Nil) else buildToolList(agentDef, depth, state.isSubTaskWorker)
        val isSubagent = depth > 0
        val sessionIdOpt = state.sessionId
        // Track the first model that failed (for modelChanged notification)
        val firstFailedModel: cats.effect.Ref[IO, Option[String]] = cats.effect.Ref.unsafe(None)
        val onAttemptCb: FallbackAttempt => IO[Unit] = attempt =>
          // Record the first failed model for modelChanged comparison
          firstFailedModel.get.flatMap {
            case None => firstFailedModel.set(Some(s"${attempt.providerId}/${attempt.model}"))
            case _ => IO.unit
          } *> {
            val msg = attempt.message.getOrElse(s"${attempt.providerId}/${attempt.model} failed, retrying...")
            state.wsSend(AgentStreamEvent.RetryStatus(msg).toJson(ctx.self.path.name, isSubagent, sessionIdOpt))
          }
        val turnId = state.currentTurnId + 1
        // Save turn also skips FastMicroCompact: the agent needs the full history
        // (including old tool results) to extract durable memory entries.
        val microResult = if isCompactTurn || isSaveTurn || isAskTurn then None else FastMicroCompact(state.messages)
        val stateForLlm = microResult match
          case Some(compacted) =>
            logAgentEvent(
              agentDef,
              depth,
              state.sessionId,
              state.sessionName,
              "fast-micro-compact",
              s"before=${state.messages.size} after=${compacted.size}"
            )
            state.copy(execution = state.execution.copy(messages = compacted)).withCurrentTurnId(turnId)
          case None => state.withCurrentTurnId(turnId)

        // Synchronous context preparation (fast: file reads with mtime cache)
        val contextIo = for
          turnCtx <- ContextRefresher.refreshTurn(stateForLlm, resources, agentDef)
          freshDef = turnCtx.agentDef
          voiceMuted <- resources.voiceMutedRef.get
          voiceEnabled = freshDef.voiceEnabled && !voiceMuted
          allowedTools = buildAllowedToolSet(freshDef, depth, stateForLlm.isSubTaskWorker)
          devInfo = deviceInfoBlock
          sessionsText = formatAgentSessions(stateForLlm.agentSessions)
          // Cache v2: the task list is fetched every turn but NO LONGER injected
          // into systemStable — it travels as a user-turn reminder instead
          // (keeps the system prompt stable across task create/update/dismiss).
          taskListText <- stateForLlm.sessionId match
            case Some(sid) => resources.taskStore.renderForPrompt(sid)
            case None => IO.pure("")
          // Env section text (rendered from data.sh + prompt.md) — participates
          // in change detection. chatWidth is the only ctx input the env block
          // uses (version/PID/port come from sys props).
          envInfo = PromptSections.envInfoSection(
            PromptContext(chatWidth = stateForLlm.session.chatWidth)
          )
          // Snapshot of the dynamic values at systemStable build time.
          currentSnapshot = SystemStableSnapshot(devInfo, sessionsText, stateForLlm.language, envInfo)
          // Lifecycle nodes (new session / compaction / restart): rebuild the
          // whole systemStable and refresh the snapshot — mid-session changes
          // are reported via reminders instead of invalidating the cache.
          isLifecycleRebuild = isCompactTurn || stateForLlm.cachedSystemStable.isEmpty
          promptCtx = PromptContext(
            availableTools = allowedTools,
            depth = depth,
            voiceEnabled = voiceEnabled,
            hasDevices = devInfo.nonEmpty,
            deviceInfo = devInfo,
            hasActiveSessions = sessionsText.nonEmpty,
            agentSessionsText = sessionsText,
            language = stateForLlm.language,
            chatWidth = state.session.chatWidth,
            agentCategory = freshDef.category,
            agentName = freshDef.name,
            skillCatalog = turnCtx.skillCatalog,
            flowCatalog = turnCtx.flowCatalog,
            teamCatalog = turnCtx.teamCatalog,
            memoryBlock = turnCtx.memoryBlock,
            rulesMd = turnCtx.rulesMd,
            isSubTaskWorker = stateForLlm.isSubTaskWorker
          )
          // systemStable: rebuilt only at lifecycle nodes; otherwise reuse the
          // cached string byte-for-byte (provider prefix cache stays hit).
          // Memory block (turnCtx.memoryBlock) is therefore consumed only at
          // rebuild — memory edits take effect at the next lifecycle node.
          (systemStable, changeDevices, changeSessions, changeEnv, changeLanguage) =
            if isLifecycleRebuild then
              (buildSystemPrompt(freshDef, turnCtx.systemPrefix, promptCtx), "", "", "", Option.empty[String])
            else
              val cached = stateForLlm.cachedSystemStable.getOrElse(
                buildSystemPrompt(freshDef, turnCtx.systemPrefix, promptCtx)
              )
              val snap = stateForLlm.stableSnapshot.getOrElse(currentSnapshot)
              (
                cached,
                if snap.devices != devInfo then devInfo else "",
                if snap.sessions != sessionsText then sessionsText else "",
                if snap.envInfo != envInfo then envInfo else "",
                if snap.language != stateForLlm.language then stateForLlm.language else None
              )
          // User turn = the last message is a User message that is NOT a tool
          // result. Tool-result messages are User role with Right(blocks)
          // containing ContentBlock.ToolResult — those belong to the tool loop,
          // not a new user turn. Attachments (Right(blocks) with Image/Text but
          // no ToolResult) and tool-injected inputs (Left text from
          // Mail/Delegate/SubTask) DO count as user turns.
          isUserTurn = stateForLlm.messages.lastOption.exists { m =>
            m.role == MessageRole.User && !m.content.toOption.exists(_.exists(_.isInstanceOf[ContentBlock.ToolResult]))
          }
          reminders <- SystemReminders.collectAllIO(
            isUserTurn,
            resources.scheduledTaskStore,
            stateForLlm.sessionId,
            deviceInfo = changeDevices,
            sessionsText = changeSessions,
            taskListText = taskListText,
            language = changeLanguage,
            envInfo = changeEnv
          )
          // Branch change: persist synchronously (no async message needed)
          _ <- turnCtx.branchChange match
            case Some(_) =>
              stateForLlm.sessionId.traverse_(sid =>
                resources.sessionStore
                  .updateGitBranch(sid, turnCtx.currentBranch)
                  .handleErrorWith(e =>
                    IO(NebflowLogger.forName("nebflow.agent").warn(s"Failed to persist gitBranch: ${e.getMessage}"))
                  )
              )
            case None => IO.unit
          loggedReminders <- SystemReminders.logAndReturn(reminders)
          // The time reminder is PERSISTED as a real User message in the
          // session history: folded into the working state so it flows into
          // the persisted history (persistIfSession saves state.messages) and
          // into the LLM request. Older time reminders are pruned to bound
          // history growth. Dynamic context (peak/idle windows, pending
          // schedules) stays request-only — injected per-turn, never persisted.
          timeReminders = loggedReminders.filter(_.category == "time")
          contextReminders = loggedReminders.filterNot(_.category == "time")
          timeMsg =
            if isCompactTurn || isAskTurn || timeReminders.isEmpty then Nil
            else List(Message(MessageRole.User, Left(SystemReminder.renderAll(timeReminders))))
          contextMsg =
            if isCompactTurn || isAskTurn || contextReminders.isEmpty then Nil
            else List(Message(MessageRole.User, Left(SystemReminder.renderAll(contextReminders))))
          branchMsg =
            if isCompactTurn || isAskTurn then Nil
            else turnCtx.branchChange.toList.map(r => Message(MessageRole.User, Left(r.render)))
          stateWithReminder =
            if timeMsg.isEmpty then stateForLlm
            else stateForLlm.withMessages(SystemReminders.pruneTimeReminders(stateForLlm.messages ++ timeMsg))
          // Cache v2: persist the rebuilt systemStable + snapshot in state at
          // lifecycle nodes (next turns reuse it). Non-lifecycle turns keep the
          // existing cache untouched.
          stateWithCache =
            if isLifecycleRebuild then stateWithReminder.withSystemStableCache(systemStable, currentSnapshot)
            else stateWithReminder
          // Maintenance check: every N delegate/flow calls
          maintenanceMsg =
            if MaintenanceService.shouldTrigger(stateWithReminder, depth, isCompactTurn, isAskTurn) then
              List(MaintenanceService.buildReminder(stateWithReminder.delegateCount))
            else Nil
          freshTools =
            if isCompactTurn then Some(Nil) else buildToolList(freshDef, depth, stateForLlm.isSubTaskWorker)
          request = LlmRequest(
            messages = stateWithReminder.messages ++ contextMsg ++ branchMsg ++ maintenanceMsg,
            sessionId = stateForLlm.sessionId.getOrElse(ctx.self.path.name),
            agentId = freshDef.name,
            tools = freshTools,
            maxTokens = Some(resources.agentLibrary.globalMaxTokens),
            thinking = Some(nebflow.llm.ThinkingConfig.toLlmJson(turnCtx.thinkingConfig)),
            systemStable = Some(systemStable),
            agentModel = freshDef.model
          )
        yield (turnCtx, request, stateWithCache)

        val agentStartIO =
          if depth > 0 && !isCompactTurn && !isAskTurn then
            emitStream(
              state.wsSend,
              AgentStreamEvent.AgentStart(agentDef.name, agentDef.description, state.sessionName),
              isSubagent = true,
              state.sessionId
            )
          else IO.unit
        for
          _ <- agentStartIO
          (turnCtx, request, stateWithReminder) <- contextIo
          // Synchronously update gitBranch in state — no async message
          stateWithBranch = stateWithReminder.withGitBranch(turnCtx.currentBranch)
          _ <- ctx.forkTurn(
            resources.llm
              .sendStream(request, onAttempt = Some(onAttemptCb))
              .through(streamEmitter(stateForLlm.wsSend, isSubagent, sessionIdOpt, isAskTurn, isCompactTurn))
              .compile
              .toList
              .flatMap { chunks =>
                val cr = aggregateChunks(chunks)
                // Track runtime model for this session
                val trackModel = sessionIdOpt match
                  case Some(sid) if cr.model.isDefined =>
                    resources.runtimeModels.update(_ + (sid -> cr.model.get))
                  case _ => IO.unit
                // Broadcast modelChanged if fallback occurred
                val notifyModelChanged = firstFailedModel.get.flatMap {
                  case Some(oldModel) if cr.model.isDefined && cr.model.get != oldModel =>
                    state.wsSend(
                      io.circe.Json.obj(
                        "type" -> "modelChanged".asJson,
                        "sessionId" -> sessionIdOpt.asJson,
                        "oldModel" -> oldModel.asJson,
                        "newModel" -> cr.model.get.asJson
                      )
                    )
                  case _ => IO.unit
                }
                trackModel *> notifyModelChanged *>
                  LlmLogWriter.log(
                    request,
                    chunks,
                    cr.text,
                    cr.toolCalls,
                    cr.thinking,
                    cr.stopReason,
                    cr.usage,
                    cr.model,
                    isSubagent,
                    isCompactTurn
                  ) *> IO.pure(cr)
              }
              .attempt
              .flatMap {
                case Right(r) => ctx.self ! LlmComplete(r, replyTo, turnId)
                case Left(e) => ctx.self ! LlmFailed(e, replyTo, turnId)
              }
              .handleErrorWith { e =>
                NebflowLogger
                  .forName("nebflow.agent")
                  .warn(s"pipeLlmCall failed: ${e.getMessage}")
                  .flatMap(_ => ctx.self ! LlmFailed(e, replyTo, turnId))
              }
          )
        yield processing(
          agentDef,
          resources,
          depth,
          parentRef,
          // Update lastMaintenanceDelegateCount if maintenance was triggered this turn
          (if MaintenanceService.shouldTrigger(stateWithReminder, depth, isCompactTurn, isAskTurn) then
             stateWithBranch.withLastMaintenanceDelegateCount(stateWithReminder.delegateCount)
           else stateWithBranch)
            .withLastDispatch(Some(LastDispatch(isToolExecution = false)))
        )

        end for

  protected def pipeToolExecutions(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    result: ConsumeResult,
    replyTo: Option[ActorRef[AgentEvent]],
    processing: ProcessingFn
  )(using ctx: ActorContext[AgentCommand]): IO[Behavior[AgentCommand]] =
    val allowedTools = buildAllowedToolSet(agentDef, depth, state.isSubTaskWorker)
    val (filteredCalls, droppedCalls) = result.toolCalls.partition(tc => allowedTools.contains(tc.name))
    if droppedCalls.nonEmpty then
      NebflowLogger
        .forName("nebflow.agent")
        .warn(s"Tool calls filtered (not in allowed set): ${droppedCalls.map(_.name).distinct.mkString(", ")}")
    val nextTurnIdx = state.turnIdx + 1
    val permissionDeferredRef = cats.effect.Ref.unsafe[IO, Option[cats.effect.Deferred[IO, Boolean]]](None)
    val isSubagent = depth > 0
    val sessionIdOpt = state.sessionId

    val io = for
      freshProjectRoot <- ContextRefresher.resolveProjectRootForTool(state, resources, agentDef)
      effectiveProjectRoot = freshProjectRoot.getOrElse(resources.projectRoot.toString)
      toolCtx = ToolContext(
        projectRoot = effectiveProjectRoot,
        llm = Some(resources.llm),
        sessionStore = Some(resources.sessionStore),
        agentActorRef = Some(ctx.self),
        contextWindow = state.contextWindow,
        sessionId = state.sessionId,
        sessionName = state.sessionName,
        taskStore = Some(resources.taskStore),
        wsSend = Some(state.wsSend),
        readTracker = state.readTracker,
        fileHistory = state.fileHistory,
        parentRef = parentRef,
        depth = depth,
        agentDef = Some(agentDef),
        agentLibrary = Some(resources.agentLibrary),
        askSemaphore = Some(resources.askSemaphore),
        fileLockManager = Some(resources.fileLockManager),
        fileChangeTracker = Some(resources.fileChangeTracker),
        hookEngine = resources.hookEngine,
        hookContext =
          HookContext(sessionId = state.sessionId, projectRoot = effectiveProjectRoot, cwd = effectiveProjectRoot),
        folderId = state.folderId,
        mailboxAddress = state.session.sessionId,
        sharedResources = Some(resources),
        actorSystem = Some(ctx.system),
        messages = state.messages
      )
      freshResults <- filteredCalls.parTraverse { call =>
        val skipStreaming = call.name == "AskUserQuestion"
        val callCtx = toolCtx.copy(toolCallId = call.id)
        (if !skipStreaming then
           emitStreamIO(
             state.wsSend,
             AgentStreamEvent.ToolStart(nebflow.core.summarizeToolCall(call)),
             isSubagent,
             sessionIdOpt
           )
         else IO.unit) *>
          permissionDecision(resources, state, call)
            .flatMap {
              case PermissionDecision.Allow => executeTool(call, callCtx)
              case PermissionDecision.Deny =>
                IO.pure(
                  ToolExecResult(s"Tool ${call.name} is denied by the session permission policy", isError = true)
                )
              case PermissionDecision.Ask => askUserPermission(call, state, permissionDeferredRef, callCtx)
            }
            .map(r => (call, r))
            .attempt
            .map {
              case Right(pair) => pair
              case Left(e) => (call, ToolExecResult(s"Tool error: ${e.getMessage}", isError = true))
            }
            .flatTap { (call, r) =>
              if call.name != "AskUserQuestion" then
                val summary = summarizeToolResult(call, r.content)
                val frontendContent = r.frontendContent.getOrElse(r.content)
                emitStreamIO(
                  state.wsSend,
                  AgentStreamEvent.ToolEnd(
                    nebflow.core.summarizeToolCall(call),
                    summary,
                    frontendContent,
                    r.isError,
                    input = Some(call.input)
                  ),
                  isSubagent,
                  sessionIdOpt
                )
              else IO.unit
            }
            .flatMap { (call, r) =>
              ToolResultGuard.guardResult(call, r, state.sessionId.getOrElse("default")).map(r => (call, r))
            }
      }
      guardedBatch <- ToolResultGuard.guardBatch(freshResults, state.sessionId.getOrElse("default"))
      droppedResults = droppedCalls.map(call =>
        (call, ToolExecResult(s"Tool not available: ${call.name}", isError = true))
      )
      _ <- ctx.self ! ToolsComplete(
        guardedBatch ++ droppedResults,
        result.text,
        replyTo,
        None,
        result.thinking,
        result.thinkingSignature
      )
    yield ()

    for _ <- ctx.forkTurn(io.handleErrorWith { e =>
        val msg = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
        NebflowLogger.forName("nebflow.agent").warn(s"pipeToolExecutions failed (this should be rare): $msg")
        ctx.self ! LlmFailed(
          ToolPipelineError(s"Tool execution pipeline failed: $msg"),
          replyTo,
          state.currentTurnId
        )
      })
    yield
      val updatedState = state
        .copy(execution = state.execution.copy(turnIdx = nextTurnIdx))
        .withLastDispatch(Some(LastDispatch(isToolExecution = true, Some(result))))
      processing(agentDef, resources, depth, parentRef, updatedState)

  end pipeToolExecutions

  /**
   * P2: permission decision reads the root session's PermissionPolicy bucket
   * (permissionPolicies[rootSessionId]) — dynamic inheritance, NOT the
   * per-agent state.safetyMode copy (D5). Decision order: deny → reject
   * (no card); allow → auto-approve; otherwise reversible-by-safetyMode.
   */
  private enum PermissionDecision:
    case Allow, Deny, Ask

  private def permissionDecision(
    resources: SharedResources,
    state: AgentState,
    call: ToolCall
  ): IO[PermissionDecision] =
    resources.permissionPolicies.get.map { policies =>
      val rootSid = Option(state.session.rootSessionId).filter(_.nonEmpty).getOrElse(state.sessionId.getOrElse(""))
      val policy = policies.getOrElse(rootSid, PermissionPolicy.default)
      if policy.deny.contains(call.name) then PermissionDecision.Deny
      else if policy.allow.contains(call.name) then PermissionDecision.Allow
      else if ToolReversibility.isReversible(call.name, call.input, policy.safetyMode) then PermissionDecision.Allow
      else PermissionDecision.Ask
    }

  private def askUserPermission(
    call: ToolCall,
    state: AgentState,
    permissionDeferredRef: Ref[IO, Option[cats.effect.Deferred[IO, Boolean]]],
    toolCtx: ToolContext
  )(using ctx: ActorContext[AgentCommand]): IO[ToolExecResult] =
    permissionDeferredRef.modify {
      case existing @ Some(_) =>
        (existing, IO.pure(ToolExecResult("Another permission request is already pending", isError = true)))
      case None =>
        val deferred = cats.effect.Deferred.unsafe[IO, Boolean]
        (
          Some(deferred),
          IO {
            val summary = nebflow.core.summarizeToolCall(call)
            val dangerLevel =
              if call.name == "Bash" then
                call.input("command").flatMap(_.asString).map(nebflow.core.tools.BashTool.dangerLevel).getOrElse(0)
              else if call.name == "Curl" then
                call.input("method").flatMap(_.asString).map(_.toUpperCase) match
                  case Some(m) if !Set("GET", "HEAD", "OPTIONS").contains(m) => 2
                  case _ => 0
              else 1
            Json.obj(
              "type" -> "askPermission".asJson,
              "toolName" -> call.name.asJson,
              "summary" -> summary.asJson,
              "input" -> call.input.asJson,
              "dangerLevel" -> dangerLevel.asJson
            )
          }.flatMap { permJson =>
            // P2: every agent (root or sub-agent) sends the request straight to
            // the InteractionHub — no depth/parentRef relay chain. The hub holds
            // the Deferred, renders the card in the Nebula window and routes the
            // answer back by requestId. The 5-minute timeout stays on the
            // requesting side (hub only routes).
            val sourceAgent = toolCtx.agentDef.map(_.name).getOrElse("unknown")
            val sourceSession = state.sessionId.getOrElse("")
            val rootSessionId =
              Option(state.session.rootSessionId).filter(_.nonEmpty).getOrElse(state.sessionId.getOrElse(""))
            for
              _ <- sendPermissionRequest(toolCtx, state, permJson, deferred, sourceAgent, sourceSession, rootSessionId)
              approvedOpt <- deferred.get
                .map(Some(_))
                .timeoutTo(PermissionTimeout, IO.pure(None))
              _ <- permissionDeferredRef.set(None)
              result <- approvedOpt match
                case Some(approved) =>
                  if approved then executeTool(call, toolCtx)
                  else IO.pure(ToolExecResult("Permission denied by user", isError = true))
                case None =>
                  // Timeout — dismiss the permission popup on the frontend
                  // (the card is rendered at sessionId = rootSessionId).
                  state
                    .wsSend(
                      Json.obj(
                        "type" -> "permissionExpired".asJson,
                        "sessionId" -> rootSessionId.asJson
                      )
                    )
                    .handleErrorWith(_ => IO.unit) *>
                    IO.pure(
                      ToolExecResult(
                        s"Permission timed out — no response within ${PermissionTimeout.toMinutes} min, auto-denied. Re-issue the command if needed.",
                        isError = true
                      )
                    )
            yield result
            end for
          }
        )
    }.flatten

  /** Send a permission request to the InteractionHub (P2), or fall back to P1 local render. */
  private def sendPermissionRequest(
    toolCtx: ToolContext,
    state: AgentState,
    permJson: Json,
    deferred: cats.effect.Deferred[IO, Boolean],
    sourceAgent: String,
    sourceSession: String,
    rootSessionId: String
  )(using ctx: ActorContext[AgentCommand]): IO[Unit] =
    // Origin attribution for the permission card: team name (authoritative via
    // TeamSessionRegistry) or flow name (parsed from sessionName "flowName/nodeId"
    // set by FlowDagExecutor, confirmed via agentRegistry kind == Flow so a
    // standalone agent whose sessionName happens to contain '/' is not misread).
    val resourcesOpt = toolCtx.sharedResources
    for
      teamOpt <- nebflow.core.flow.TeamSessionRegistry.teamOfSession(sourceSession)
      recordOpt <- resourcesOpt
        .fold(IO.pure(Option.empty[AgentRecord]))(_.agentRegistry.get.map(_.get(sourceSession)))
      flowOpt = (teamOpt, recordOpt, state.sessionName) match
        case (None, Some(rec), Some(sn)) if rec.kind == AgentKind.Flow && sn.contains("/") =>
          Some(sn.takeWhile(_ != '/'))
        case _ => None
      enrichment =
        val teamFld = teamOpt.map(t => Json.obj("sourceTeam" -> t.asJson)).getOrElse(Json.obj())
        val flowFld = flowOpt.map(f => Json.obj("sourceFlow" -> f.asJson)).getOrElse(Json.obj())
        teamFld.deepMerge(flowFld)
      enriched = permJson.deepMerge(enrichment)
      _ <- resourcesOpt.traverse(_.interactionHubRef.get).map(_.flatten).flatMap {
        case Some(hub) =>
          (hub ! InteractionHubCommand.Request(
            InteractionRequest(
              requestId = java.util.UUID.randomUUID().toString.take(8),
              kind = InteractionKind.Permission,
              payload = enriched,
              reply = InteractionReply.PermissionReply(deferred),
              rootSessionId = rootSessionId,
              sourceAgent = sourceAgent,
              sourceSession = sourceSession
            )
          )).void
        case None =>
          // Hub not spawned (early boot / tests): P1 fallback — the requesting
          // agent holds the Deferred itself and renders locally.
          (ctx.self ! AgentCommand.SetPermissionDeferred(deferred)) *>
            state.wsSend(
              enriched.deepMerge(
                Json.obj(
                  "sessionId" -> state.sessionId.asJson,
                  "sourceAgent" -> sourceAgent.asJson,
                  "sourceSession" -> sourceSession.asJson
                )
              )
            )
      }
    yield ()

    end for

  end sendPermissionRequest

  protected def executeTool(call: ToolCall, ctx: ToolContext): IO[ToolExecResult] =
    ToolRegistry.TOOL_MAP.get(call.name) match
      case Some(tool) =>
        val summary = tool.summarize(call.input)
        val logger = NebflowLogger.forName("nebflow.handlers")
        val agentName = ctx.agentDef.map(_.name).getOrElse("-")
        val sessionName = ctx.sessionName.getOrElse("-")
        val logCtx = logger.ctxPrefix(agentName, sessionName)
        val hookEngine = ctx.hookEngine
        val hookCtx = ctx.hookContext
        IO.delay(System.nanoTime()).flatMap { start =>
          hookEngine
            .beforeTool(call.name, call.input, hookCtx)
            .flatMap { preResult =>
              if preResult.decision == HookDecision.Block then
                val blockMsg = preResult.reason.getOrElse(s"Tool ${call.name} blocked by hook")
                logger.info(s"$logCtx Hook blocked $summary: $blockMsg")
                IO.pure(ToolExecResult(blockMsg, isError = true))
              else
                val finalInput = preResult.updatedInput.getOrElse(call.input)
                val deviceOpt = finalInput("device").flatMap(_.asString).filter(_.nonEmpty).filter(_ != "local")
                val execIO: IO[ToolExecResult] = deviceOpt match
                  case Some(deviceName) if RemoteExecutor.current.isDefined =>
                    val remoteInput = finalInput.remove("device")
                    logger.info(s"$logCtx Remote tool: [${deviceName}] ${tool.summarize(remoteInput)}")
                    RemoteExecutor.current.get.execute(deviceName, call.name, remoteInput, Some(ctx)).flatMap {
                      case Right(result) =>
                        hookEngine.afterTool(call.name, finalInput, result, true, hookCtx).map { postResult =>
                          val hookSuffix = postResult.additionalContext.getOrElse("")
                          ToolExecResult(
                            result + (if hookSuffix.nonEmpty then s"\n\n$hookSuffix" else ""),
                            frontendContent = Some(result)
                          )
                        }
                      case Left(err) =>
                        hookEngine.afterToolFailure(call.name, finalInput, err.message, hookCtx).map { postResult =>
                          val appended = postResult.additionalContext match
                            case Some(r) => s"${err.message}\n\n$r"
                            case None => err.message
                          ToolExecResult(appended, isError = true)
                        }
                    }
                  case _ =>
                    tool.call(finalInput, ctx).flatMap {
                      case Left(err) =>
                        hookEngine.afterToolFailure(call.name, finalInput, err.message, hookCtx).map { postResult =>
                          val appended = postResult.additionalContext match
                            case Some(r) => s"${err.message}\n\n$r"
                            case None => err.message
                          ToolExecResult(appended, isError = true)
                        }
                      case Right(result) =>
                        val isFileEdit = call.name == "Edit" || call.name == "Write"
                        val imageBlocks = tool.extractImages(finalInput, result)
                        hookEngine.afterTool(call.name, finalInput, result, true, hookCtx).map { postResult =>
                          val hookSuffix = postResult.additionalContext.getOrElse("")
                          val llmContent = if false then
                            val title = call.input("title").flatMap(_.asString).getOrElse("")
                            s"Card${if title.nonEmpty then s" ($title)" else ""} rendered"
                          else if isFileEdit then nebflow.core.summarizeToolResult(call, result)
                          else result
                          ToolExecResult(
                            llmContent + (if hookSuffix.nonEmpty then s"\n\n$hookSuffix" else ""),
                            frontendContent = Some(result + (if hookSuffix.nonEmpty then s"\n\n$hookSuffix" else "")),
                            imageBlocks = imageBlocks
                          )
                        }
                    }
                execIO.handleErrorWith {
                  case _: UserAbort => IO.raiseError(new UserAbort())
                  case e => IO.pure(ToolExecResult(s"Tool execution error: ${e.getMessage}", isError = true))
                }
            }
            .flatTap { result =>
              val elapsed = (System.nanoTime() - start) / 1_000_000
              if result.isError then
                logger.warn(s"$logCtx Tool $summary failed (${elapsed}ms): ${result.content.take(100)}")
              else logger.info(s"$logCtx Tool $summary OK (${elapsed}ms)")
            }
        }
      case None => IO.pure(ToolExecResult(s"No such tool available: ${call.name}", isError = true))

  protected def buildAllowedToolSet(agentDef: AgentDef, depth: Int = 0, isSubTaskWorker: Boolean = false): Set[String] =
    val base = agentDef.tools match
      case Nil => Set.empty[String]
      case List("*") => ToolRegistry.ALL_TOOLS.map(_.name).toSet
      case names => names.toSet
    // Fixed tools are auto-injected based on agent category — they don't
    // need to be listed in agent.json. Mail is team-only; FlowReport is
    // flow-only; all agents get base tools (file ops, search, shell, feedback).
    val withBuiltin = base ++ AgentCore.fixedToolsFor(agentDef)
    // FlowTrigger is whitelist-driven (R1 split, NOT Nebula-exclusive): any
    // agent declaring flows in agent.json gets the tool; everyone else is
    // stripped of it (even via "*" or explicit listing — every call would
    // fail the whitelist check anyway).
    val withFlowTrigger =
      if agentDef.flows.nonEmpty then withBuiltin + "FlowTrigger" else withBuiltin - "FlowTrigger"
    val isNebula = agentDef.name == "Nebula"
    val nebulaFiltered = if isNebula then withFlowTrigger else withFlowTrigger -- NebulaExclusiveTools
    // Task tools: available to Nebula and Team Lead, NOT workers
    val taskFiltered = agentDef.tools match
      case List("*") =>
        if depth >= 2 then nebulaFiltered -- LeadLevelTools
        else nebulaFiltered
      case _ =>
        nebulaFiltered
    // MCP tools: agents may use MCP tools from explicitly granted servers
    // (mcpServers) plus their own dedicated agent-scoped servers, which are
    // always auto-allowed. Tool names are mcp__<serverId>__<tool>; dedicated
    // servers use serverId "agent-<agentName>-<serverName>".
    val agentOwnPrefix = s"mcp__agent-${agentDef.name}-"
    val mcpFiltered =
      if agentDef.mcpServers.isEmpty then
        taskFiltered.filter(t => !t.startsWith("mcp__") || t.startsWith(agentOwnPrefix))
      else
        val prefixes = agentDef.mcpServers.map(sid => s"mcp__${sid}__")
        taskFiltered.filter(t =>
          !t.startsWith("mcp__") || t.startsWith(agentOwnPrefix) || prefixes.exists(t.startsWith)
        )
    // SubTask workers are leaf agents: no Mail / no further delegation, and
    // no FlowTrigger (workers don't trigger pipelines). Delegate is
    // Nebula-exclusive (filtered above for everyone else); these strips also
    // defend against a worker whose agent.json explicitly lists the tools.
    if isSubTaskWorker then mcpFiltered -- Set("Mail", "SubTask", "Delegate", "FlowTrigger")
    // Flow agents have no Mail — flow nodes report via FlowReport, not Mail.
    // Structurally defends against the 08-14 P0 root cause: a flow agent
    // whose agent.json lists Mail (or uses "*") could block forever on a
    // Mail ask (flow callers cannot receive background notifications).
    else if agentDef.category == "flow" then mcpFiltered - "Mail"
    else mcpFiltered

  end buildAllowedToolSet

  protected def buildToolList(
    agentDef: AgentDef,
    depth: Int = 0,
    isSubTaskWorker: Boolean = false
  ): Option[List[ToolDefinition]] =
    val allowedSet = buildAllowedToolSet(agentDef, depth, isSubTaskWorker)
    Some(ToolRegistry.ALL_TOOLS.filter(t => allowedSet.contains(t.name)))

  protected def emitStream(
    wsSend: io.circe.Json => IO[Unit],
    event: AgentStreamEvent,
    isSubagent: Boolean = true,
    sessionId: Option[String] = None
  )(using ctx: ActorContext[AgentCommand]): IO[Unit] =
    val eventName = event.getClass.getSimpleName
    val json = event.toJson(ctx.self.path.name, isSubagent, sessionId)
    ctx.forkTurn(
      wsSend(json).handleErrorWith(e =>
        IO(NebflowLogger.forName("nebflow.agent").warn(s"emitStream($eventName) failed: ${e.getMessage}"))
      )
    )

  /**
   * Direct wsSend for use inside Fiber context (pipeLlmCall, pipeToolExecutions).
   * Do NOT call from receive handlers — use emitStream instead (non-blocking forkTurn wrapper).
   */
  protected def emitStreamIO(
    wsSend: io.circe.Json => IO[Unit],
    event: AgentStreamEvent,
    isSubagent: Boolean = true,
    sessionId: Option[String] = None
  )(using ctx: ActorContext[AgentCommand]): IO[Unit] =
    wsSend(event.toJson(ctx.self.path.name, isSubagent, sessionId))

  protected def streamEmitter(
    wsSend: io.circe.Json => IO[Unit],
    isSubagent: Boolean = true,
    sessionId: Option[String] = None,
    isAskMode: Boolean = false,
    isCompactTurn: Boolean = false
  )(using ctx: ActorContext[AgentCommand]): fs2.Pipe[IO, StreamChunk, StreamChunk] =
    stream =>
      // ── WS event batching for TextDelta ──────────────────────────────
      // Accumulate text deltas and flush in batches (max 50 deltas or 50ms
      // window). A 1000-token LLM response produces 500-1000 TextDelta chunks;
      // batching reduces WS JSON construction + send from ~800 to ~16 calls.
      // Non-TextDelta events (ToolCall, Thinking, Done) trigger an immediate
      // flush first so event ordering is preserved on the frontend.
      //
      // Thinking deltas are batched the same way (P0-1/P0-2):
      // - sub-agent `agentThinking` carries no payload — all markers in a
      //   window collapse into a single event (pure noise reduction; thinking
      //   phase used to emit 200-1000 WS frames/s/agent)
      // - main-session `thinkingDelta` carries text — deltas in a window are
      //   concatenated into one frame; the frontend rAF-throttles rendering,
      //   so the coarser frames are imperceptible
      //
      // Mutable state is local to this pipe invocation — each agent's stream
      // creates its own accumulators, and fs2 processes elements sequentially.
      val textBuf = new StringBuilder(512)
      var textCount = 0
      val thinkingBuf = new StringBuilder(256)
      var thinkingCount = 0
      var lastFlushMs = System.currentTimeMillis()
      val MaxBatch = 50
      val FlushWindowMs = 50L

      def flushText(): IO[Unit] =
        if textBuf.isEmpty then IO.unit
        else
          val delta = textBuf.toString
          textBuf.setLength(0)
          textCount = 0
          lastFlushMs = System.currentTimeMillis()
          val json =
            if isAskMode then
              Json.obj("type" -> "askTextDelta".asJson, "sessionId" -> sessionId.asJson, "delta" -> delta.asJson)
            else if isSubagent then AgentStreamEvent.TextDelta(delta).toJson(ctx.self.path.name, true, None)
            else Json.obj("type" -> "textDelta".asJson, "sessionId" -> sessionId.asJson, "delta" -> delta.asJson)
          wsSend(json)

      def flushThinking(): IO[Unit] =
        if thinkingCount == 0 then IO.unit
        else
          val json =
            if isSubagent then AgentStreamEvent.Thinking.toJson(ctx.self.path.name, true, None)
            else
              val delta = thinkingBuf.toString
              Json.obj("type" -> "thinkingDelta".asJson, "sessionId" -> sessionId.asJson, "delta" -> delta.asJson)
          thinkingBuf.setLength(0)
          thinkingCount = 0
          lastFlushMs = System.currentTimeMillis()
          wsSend(json)

      // Thinking chunks precede text chunks in an LLM stream; when a window
      // straddles the phase boundary the thinking buffer holds earlier content,
      // so flush thinking before text to preserve event order.
      def flushAll(): IO[Unit] = flushThinking() *> flushText()

      stream.evalTap {
        case StreamChunk.TextDelta(delta) if delta.nonEmpty && !isCompactTurn =>
          textBuf.append(delta)
          textCount += 1
          if textCount >= MaxBatch || System.currentTimeMillis() - lastFlushMs >= FlushWindowMs then flushAll()
          else IO.unit

        // Batch thinking instead of one frame per chunk.
        case StreamChunk.ThinkingDelta(delta) if delta.nonEmpty && !isCompactTurn =>
          if !isSubagent then thinkingBuf.append(delta)
          thinkingCount += 1
          if thinkingCount >= MaxBatch || System.currentTimeMillis() - lastFlushMs >= FlushWindowMs then flushAll()
          else IO.unit

        case StreamChunk.ToolCallStart(name) if name != "AskUserQuestion" && !isCompactTurn =>
          val json =
            if isSubagent then AgentStreamEvent.ToolCallDetected(name).toJson(ctx.self.path.name, true, None)
            else Json.obj("type" -> "toolCallDetected".asJson, "sessionId" -> sessionId.asJson, "name" -> name.asJson)
          flushAll() *> wsSend(json)

        case StreamChunk.ToolCallChunk(tc) if tc.name != "AskUserQuestion" && !isCompactTurn =>
          val json =
            if isSubagent then
              AgentStreamEvent.ToolStart(nebflow.core.summarizeToolCall(tc)).toJson(ctx.self.path.name, true, None)
            else
              Json.obj(
                "type" -> "toolStart".asJson,
                "sessionId" -> sessionId.asJson,
                "label" -> nebflow.core.summarizeToolCall(tc).asJson
              )
          flushAll() *> wsSend(json)

        case StreamChunk.ToolArgDelta(toolName, delta) if delta.nonEmpty && !isCompactTurn && !isSubagent =>
          val json = Json.obj(
            "type" -> "toolArgDelta".asJson,
            "sessionId" -> sessionId.asJson,
            "toolName" -> toolName.asJson,
            "delta" -> delta.asJson
          )
          flushAll() *> wsSend(json)

        // Stream end: flush any remaining buffered text/thinking so no deltas are lost.
        case StreamChunk.Done(_, _, _, _) => flushAll()

        case _ => IO.unit
      }

  protected def aggregateChunks(chunks: List[StreamChunk]): ConsumeResult =
    val text = chunks.collect { case StreamChunk.TextDelta(d) => d }.mkString
    val thinking = chunks.collect { case StreamChunk.ThinkingDelta(d) => d }.mkString
    val thinkingSignature = chunks.collectFirst { case StreamChunk.ThinkingSignature(s) => s }
    val toolCalls = chunks.collect { case StreamChunk.ToolCallChunk(tc) => tc }
    val usage = chunks
      .collectFirst { case StreamChunk.Done(_, Some(u), _, _) if u.inputTokens > 0 => u }
      .orElse(chunks.collectFirst { case StreamChunk.Done(_, u, _, _) => u }.flatten)
    val stopReason = chunks.collectFirst { case StreamChunk.Done(sr, _, _, _) => sr }.flatten
    val model = chunks.collectFirst { case StreamChunk.Done(_, _, Some(meta), _) =>
      s"${meta.providerId}/${meta.model}"
    }
    val contextWindow = chunks.collectFirst { case StreamChunk.Done(_, _, _, cw) => cw }.flatten
    ConsumeResult(
      text,
      toolCalls,
      Nil,
      stopReason,
      usage,
      Option.when(thinking.nonEmpty)(thinking),
      thinkingSignature,
      model,
      contextWindow
    )

  end aggregateChunks

  protected def buildSystemPrompt(
    agentDef: AgentDef,
    systemPrefix: String,
    ctx: PromptContext
  ): String =
    val rawPrompt = if agentDef.systemPrompt.nonEmpty then agentDef.systemPrompt else Repl.loadSystemPrompt()
    // SubTask workers inherit the parent's system.md for domain knowledge but
    // team interaction content is stripped (no team / no Mail / no reporting).
    val base = if ctx.isSubTaskWorker then SubTaskPrompt.stripTeamContent(rawPrompt) else rawPrompt
    val cleanedPrompt = PromptSections.stripAllMigrated(base)
    val conditionalBlocks = PromptSections.buildConditionalBlocks(ctx)
    val separator = if conditionalBlocks.nonEmpty then "\n\n" else ""
    s"$systemPrefix$cleanedPrompt$separator$conditionalBlocks"

  end buildSystemPrompt

  /** Format active persistent sub-agent sessions for system prompt injection. */
  protected def formatAgentSessions(sessions: List[AgentSessionInfo]): String =
    if sessions.isEmpty then ""
    else
      val lines = sessions.map: s =>
        s"${s.address} — ${s.agentName}: ${s.taskDescription} (${s.status})"
      "# Active Sessions\n\n" + lines.mkString("\n") +
        "\n\nUse Mail to send follow-up instructions to any session above."

  @volatile private var deviceInfoCache: (Long, String) = (0L, "")

  private def deviceInfoBlock: String =
    val now = System.currentTimeMillis()
    val (lastUpdate, cached) = deviceInfoCache
    if now - lastUpdate < 30000 && cached.nonEmpty then cached
    else
      val refreshed = RemoteExecutor.current
        .flatMap(_.neblinkServiceOpt)
        .flatMap { ms =>
          try
            import cats.effect.unsafe.implicits.global
            val id = ms.identity.unsafeRunSync()
            val peersList = ms.peers.unsafeRunSync()
            val localStr =
              s"local (${id.deviceName})" +
                (if id.userDescription.nonEmpty then s" -${id.userDescription}" else "")
            val peerStrs =
              peersList.map { p =>
                p.deviceName + (if p.userDescription.nonEmpty then s" -${p.userDescription}" else "")
              }
            val allDevices = (localStr :: peerStrs).mkString("; ")
            val deviceHint =
              if peersList.nonEmpty then
                "\nEach tool accepts a `device` parameter. Select the appropriate device for each task."
              else ""
            Some(s"$allDevices$deviceHint")
          catch case _: Exception => None
        }
        .getOrElse("")
      deviceInfoCache = (now, refreshed)
      refreshed

    end if

  end deviceInfoBlock

  protected def summarizeToolResult(call: ToolCall, result: String): String =
    nebflow.core.summarizeToolResult(call, result)

end AgentCore

object AgentCore:

  /**
   * Nebula-exclusive tools: only available when agentName == "Nebula".
   * - Schedule: session-scoped scheduled tasks
   * - Delegate: 调度器/根 agent 专用——指派 standalone agent。
   *   Team 成员委派走 SubTaskTool（self-clone + ephemeral）。
   *   Flow 触发不在此列——FlowTrigger 由 agent.json flows 白名单驱动注入。
   */
  val NebulaExclusiveTools = Set(
    "Schedule",
    "Delegate"
  )

  /** Tools available to Nebula and Team Leads, but NOT workers. */
  val LeadLevelTools = Set("TaskCreate", "TaskUpdate")

  /**
   * Base tools always available to ALL agents regardless of category.
   * These are injected automatically — agent.json does not need to list them.
   */
  val BaseTools = Set(
    "Read",
    "Write",
    "Edit",
    "Glob",
    "Grep",
    "Bash",
    "Issue",
    "RemoveUnnecessary"
  )

  /**
   * Fixed tools for a given agent: base tools plus category-specific tools.
   * These are auto-injected and should NOT be stored in agent.json.
   *
   * - Team agents: BaseTools + Mail (communication primitive)
   * - Flow agents: BaseTools + FlowReport (no Mail)
   * - Standalone agents: BaseTools only (no Mail, no FlowReport)
   */
  def fixedToolsFor(agentDef: AgentDef): Set[String] =
    agentDef.category match
      case "team" => BaseTools + "Mail"
      case "flow" => BaseTools + "FlowReport"
      case _ => BaseTools

end AgentCore
