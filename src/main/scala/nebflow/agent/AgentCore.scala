package nebflow.agent

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.agent.AgentCommand.*
import nebflow.core.*
import nebflow.core.compact.*
import nebflow.core.hooks.*
import nebflow.core.tools.{RemoteExecutor, ToolContext, ToolRegistry, ToolResultGuard}
import nebflow.shared.*
import nebflow.shared.given
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

import scala.concurrent.duration.*

/**
 * Core LLM loop, tool execution, compaction, and stream helpers
 * extracted from AgentActor for maintainability.
 */
private[agent] trait AgentCore:

  protected val MaxDepth = 5

  private val lifecycleLog = NebflowLogger.forName("nebflow.agent.lifecycle")

  /** Shorten UUID to first 8 hex chars. */
  private def shortUuid(id: String): String =
    if id.startsWith("agent-") then id.drop(6).take(8)
    else if id == "-" || id == "system" then id
    else id.take(8)

  protected def logAgentEvent(
    ctx: ActorContext[?],
    agentDef: AgentDef,
    depth: Int,
    sessionId: Option[String],
    sessionName: Option[String],
    event: String,
    detail: String = ""
  ): Unit =
    val sid = shortUuid(sessionId.getOrElse("-"))
    val sname = sessionName.getOrElse("-")
    val who = if depth == 0 then s"${agentDef.name}-${shortUuid(ctx.self.path.name)}" else s"subagent-${agentDef.name}"
    val logCtx = lifecycleLog.ctxPrefix(who, s"$sname/$sid")
    lifecycleLog.infoSync(if detail.nonEmpty then s"$logCtx event=$event detail=$detail" else s"$logCtx event=$event")
  end logAgentEvent

  // ============================================================
  // Persist state to SessionStore if sessionId is present
  // ============================================================

  protected def persistIfSession(
    resources: SharedResources,
    state: AgentState
  ): IO[Unit] =
    state.sessionId match
      case Some(sid) => resources.sessionStore.saveMessagesForSession(sid, state.messages)
      case None => IO.unit

  // ============================================================
  // Auto-compaction check
  // ============================================================

  /** Check if auto-compaction is needed and trigger it. Returns Some(behavior) if compacting. */
  protected def maybeAutoCompact(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    stash: StashBuffer[AgentCommand],
    ctx: ActorContext[AgentCommand],
    replyTo: Option[ActorRef[AgentEvent]],
    processing: (
      AgentDef,
      SharedResources,
      Int,
      Option[ActorRef[AgentCommand]],
      AgentState,
      StashBuffer[AgentCommand],
      ActorContext[AgentCommand]
    ) => Behavior[AgentCommand]
  ): Option[Behavior[AgentCommand]] =
    if state.pendingCompaction.isDefined then None
    else
      val config = CompactConfig()
      // Check retry backoff before anything else
      val backoffOk =
        if state.compactionFailures == 0 then true
        else
          val elapsed = System.currentTimeMillis() - state.lastCompactionFailureAt
          val ok = config.isBackoffSatisfied(state.compactionFailures, elapsed)
          if !ok then
            logAgentEvent(
              ctx,
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
        logAgentEvent(
          ctx,
          agentDef,
          depth,
          state.sessionId,
          state.sessionName,
          "auto-compact-skipped",
          s"circuitBreakerOpen failures=${state.compactionFailures} max=${config.circuitBreakerMax}"
        )
        None
      else
        val inputTokensOpt = state.latestUsage.map(_.inputTokens)
        val threshold = state.contextWindow - config.bufferForWindow(state.contextWindow)

        // Primary check: use reported inputTokens if available and non-zero
        val shouldCompact = inputTokensOpt match
          case Some(inputTokens) if inputTokens > 0 && inputTokens > threshold =>
            Some(s"inputTokens=$inputTokens threshold=$threshold")
          case _ =>
            // Fallback: estimate tokens from message chars when provider doesn't report accurate usage
            val estimated = TokenEstimator.estimate(state.messages)
            if estimated > threshold then
              Some(s"estimated=$estimated threshold=$threshold (provider did not report inputTokens)")
            else None

        shouldCompact match
          case Some(detail) =>
            logAgentEvent(
              ctx,
              agentDef,
              depth,
              state.sessionId,
              state.sessionName,
              "auto-compact-trigger",
              detail
            )
            Some(
              startDirectCompaction(
                agentDef,
                resources,
                depth,
                parentRef,
                state,
                stash,
                ctx,
                replyTo,
                processing,
                "full"
              )
            )
          case None => None
        end match
      end if
  end maybeAutoCompact

  /**
   * Start inline compaction — inject a compact reminder into the message
   * stream and call pipeLlmCall with tools disabled. The LLM response is
   * captured by the LlmComplete handler, which runs FullCompact.parseResponse.
   *
   * This reuses the agent's cached system prompt + tool definitions,
   * avoiding the expensive separate LLM call of the old approach.
   */
  protected def startDirectCompaction(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    stash: StashBuffer[AgentCommand],
    ctx: ActorContext[AgentCommand],
    replyTo: Option[ActorRef[AgentEvent]],
    processing: (
      AgentDef,
      SharedResources,
      Int,
      Option[ActorRef[AgentCommand]],
      AgentState,
      StashBuffer[AgentCommand],
      ActorContext[AgentCommand]
    ) => Behavior[AgentCommand],
    mode: String
  ): Behavior[AgentCommand] =
    val jobId = s"compact-${java.util.UUID.randomUUID().toString.take(8)}"
    val pending = CompactionJob(jobId, mode, None, replyTo)

    // Emit compact-start event to frontend
    resources.dispatcher.unsafeRunAndForget(
      emitStreamIO(
        state.wsSend,
        ctx,
        AgentStreamEvent.CompactStart(
          mode,
          state.latestUsage.map(_.inputTokens),
          Some(state.contextWindow - CompactConfig().bufferForWindow(state.contextWindow))
        ),
        isSubagent = depth > 0,
        state.sessionId
      ).handleErrorWith(_ => IO.unit)
    )

    // Prepare state: set pendingCompaction so pipeLlmCall knows this is a compact turn
    val compactState = state
      .withPendingCompaction(Some(pending))
      .withMessages(state.messages :+ CompactService.buildCompactReminder())

    // Call pipeLlmCall directly — it will detect pendingCompaction and use tools=Nil
    pipeLlmCall(
      agentDef,
      resources,
      depth,
      parentRef,
      compactState,
      stash,
      ctx,
      replyTo,
      processing,
      // finishTurn not used for compact (handled in LlmComplete), but pass it anyway
      (ad, r, d, p, s, st, c, t, rep, th, thSig, streamed, model) => Behaviors.unhandled
    )
  end startDirectCompaction

  // ============================================================
  // Start LLM call -> pipe result back to self
  // ============================================================

  protected def pipeLlmCall(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    stash: StashBuffer[AgentCommand],
    ctx: ActorContext[AgentCommand],
    replyTo: Option[ActorRef[AgentEvent]],
    processing: (
      AgentDef,
      SharedResources,
      Int,
      Option[ActorRef[AgentCommand]],
      AgentState,
      StashBuffer[AgentCommand],
      ActorContext[AgentCommand]
    ) => Behavior[AgentCommand],
    finishTurn: (
      AgentDef,
      SharedResources,
      Int,
      Option[ActorRef[AgentCommand]],
      AgentState,
      StashBuffer[AgentCommand],
      ActorContext[AgentCommand],
      String,
      Option[ActorRef[AgentEvent]],
      Option[String],
      Option[String],
      Boolean,
      Option[String]
    ) => Behavior[AgentCommand]
  ): Behavior[AgentCommand] =
    maybeAutoCompact(agentDef, resources, depth, parentRef, state, stash, ctx, replyTo, processing) match
      case Some(behavior) => behavior
      case None =>
        // Inline compact mode: pendingCompaction is set, tools are disabled
        val isCompactTurn = state.pendingCompaction.isDefined
        // Inline ask mode: single Q&A turn, reuses agent tools, no history write-back
        val isAskTurn = state.askMode.isDefined

        if depth > 0 && !isCompactTurn && !isAskTurn then
          emitStream(
            resources.dispatcher,
            state.wsSend,
            ctx,
            AgentStreamEvent.AgentStart(agentDef.name, agentDef.description, state.sessionName),
            isSubagent = true,
            state.sessionId
          )

        // In compact mode, disable tools — model must respond with summary text only
        val tools = if isCompactTurn then Some(Nil) else buildToolList(agentDef, depth)

        val isSubagent = depth > 0
        val sessionIdOpt = state.sessionId

        val onAttemptCb: FallbackAttempt => IO[Unit] = attempt =>
          val msg = attempt.message.getOrElse(s"${attempt.providerId}/${attempt.model} failed, retrying...")
          emitStreamIO(state.wsSend, ctx, AgentStreamEvent.RetryStatus(msg), isSubagent, sessionIdOpt)

        // Assign a monotonically increasing turnId so stale LlmComplete/LlmFailed from
        // a cancelled (interrupted) turn can be detected and discarded.
        val turnId = state.currentTurnId + 1

        // FastMicroCompact: rule-driven tool result cleanup when cache is cold.
        // Skip during compact/ask turns — messages already have the reminder appended.
        val microResult = if isCompactTurn || isAskTurn then None else FastMicroCompact(state.messages)
        val stateAfterMicro = microResult match
          case Some(compacted) =>
            logAgentEvent(
              ctx,
              agentDef,
              depth,
              state.sessionId,
              state.sessionName,
              "fast-micro-compact",
              s"before=${state.messages.size} after=${compacted.size}"
            )
            state
              .copy(execution = state.execution.copy(messages = compacted))
              .withCurrentTurnId(turnId)
          case None =>
            state.withCurrentTurnId(turnId)
        val stateForTurn = stateAfterMicro
        val stateForLlm = stateForTurn

        val io = for
          // --- Context refresh: Lifecycle sources cached after first turn, EveryTurn sources always fresh ---
          (turnCtx, newLifecycle) <- ContextRefresher.refreshTurn(stateForTurn, resources, agentDef)
          _ <- newLifecycle match
            case Some(lc) => IO(ctx.self ! AgentCommand.UpdateLifecycle(lc))
            case None => IO.unit
          freshDef = turnCtx.agentDef
          baseSystemStable = buildSystemPrompt(
            freshDef,
            resources,
            turnCtx.systemPrefix,
            turnCtx.projectRoot,
            turnCtx.rulesMd,
            state.session.chatWidth
          )
          // --- reminders (async) ---
          // isUserTurn: last message is a plain-text user message (not tool results wrapped as User)
          isUserTurn = stateForLlm.messages.lastOption.exists(m => m.role == MessageRole.User && m.content.isLeft)
          timeReminders = SystemReminders.collectAll(isUserTurn)
          // Add device pool info as per-turn reminder (not persisted in system prompt)
          reminders = timeReminders ++ deviceReminder.toList
          // --- Git branch change detection ---
          _ <- turnCtx.branchChange match
            case Some(_) =>
              // Persist the current branch so we don't re-alert on subsequent turns
              IO(ctx.self ! AgentCommand.UpdateGitBranch(turnCtx.currentBranch))
            case None =>
              // Still update if this is the first detection (None → Some)
              IO.whenA(turnCtx.currentBranch != stateForLlm.gitBranch)(
                IO(ctx.self ! AgentCommand.UpdateGitBranch(turnCtx.currentBranch))
              )
          loggedReminders <- SystemReminders.logAndReturn(reminders)
          allReminders = loggedReminders ++ turnCtx.branchChange.toList
          remindersText = SystemReminder.renderAll(allReminders)
          // Per-turn dynamic context: only reminders (env info is now in system prompt)
          // Skip for compact/ask turns — the reminder is already in messages
          dynamicMsg =
            if isCompactTurn || isAskTurn then Nil
            else if remindersText.nonEmpty then List(Message(MessageRole.User, Left(remindersText)))
            else Nil
          // --- LLM call ---
          // Use fresh agentDef from TurnContext for tools and system prompt
          // Fetch available models to enrich Delegate tool description with model options
          modelDescs <- if isCompactTurn then IO.pure(Nil) else resources.providerRegistry.getAllModelsDetailed()
          freshTools =
            if isCompactTurn then Some(Nil) else enrichDelegateTools(buildToolList(freshDef, depth), modelDescs)
          systemStable = baseSystemStable +
            (if turnCtx.memoryBlock.nonEmpty then s"\n\n${turnCtx.memoryBlock}" else "") +
            stateForLlm.language
              .map(l =>
                s"\n\n# Language\n- Respond in $l.\n- When creating tasks (TaskCreate), the `subject` and `activeForm` fields MUST be in $l.\n- When writing to memory files (Agent/Session/User memory), all content MUST be in $l.\n- All user-visible text must be in $l."
              )
              .getOrElse("")
          request = LlmRequest(
            messages = stateForLlm.messages ++ dynamicMsg,
            sessionId = stateForLlm.sessionId.getOrElse(ctx.self.path.name),
            agentId = freshDef.name,
            tools = freshTools,
            maxTokens = Some(resources.agentLibrary.globalMaxTokens),
            thinking = Some(nebflow.llm.ThinkingConfig.toLlmJson(turnCtx.thinkingConfig)),
            systemStable = Some(systemStable)
          )
          // Inactivity timeout is now per-provider inside LlmInterface.sendStream,
          // so the fallback mechanism can switch providers on timeout.
          result <- resources.llm
            .sendStream(request, onAttempt = Some(onAttemptCb))
            .through(streamEmitter(stateForLlm.wsSend, ctx, isSubagent, sessionIdOpt, isAskTurn, isCompactTurn))
            .compile
            .toList
            .map(aggregateChunks)
            .attempt
          _ <- result match
            case Right(r) => IO(ctx.self ! LlmComplete(r, replyTo, turnId))
            case Left(e) => IO(ctx.self ! LlmFailed(e, replyTo, turnId))
        yield ()

        // Start the IO fiber and immediately send the fiber reference back to the actor
        // so that Interrupt can cancel it. io.start forks immediately; StreamFiberStarted
        // is queued in the actor mailbox before any LlmComplete can arrive.
        val startIo = io.start.handleErrorWith { e =>
          IO(NebflowLogger.forName("nebflow.agent").warn(s"pipeLlmCall failed: ${e.getMessage}")) *>
            IO(ctx.self ! LlmFailed(e, replyTo, turnId)) *> IO.never.void.start
        }
        resources.dispatcher.unsafeRunAndForget(
          startIo.flatMap(fiber => IO(ctx.self ! StreamFiberStarted(fiber)))
        )
        processing(agentDef, resources, depth, parentRef, stateForLlm, stash, ctx)
    end match
  end pipeLlmCall

  // ============================================================
  // Execute tools -> pipe results back to self
  // ============================================================

  protected def pipeToolExecutions(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    stash: StashBuffer[AgentCommand],
    ctx: ActorContext[AgentCommand],
    result: ConsumeResult,
    replyTo: Option[ActorRef[AgentEvent]],
    processing: (
      AgentDef,
      SharedResources,
      Int,
      Option[ActorRef[AgentCommand]],
      AgentState,
      StashBuffer[AgentCommand],
      ActorContext[AgentCommand]
    ) => Behavior[AgentCommand],
    pipeLlmCallFn: (
      AgentDef,
      SharedResources,
      Int,
      Option[ActorRef[AgentCommand]],
      AgentState,
      StashBuffer[AgentCommand],
      ActorContext[AgentCommand],
      Option[ActorRef[AgentEvent]]
    ) => Behavior[AgentCommand],
    finishTurnFn: (
      AgentDef,
      SharedResources,
      Int,
      Option[ActorRef[AgentCommand]],
      AgentState,
      StashBuffer[AgentCommand],
      ActorContext[AgentCommand],
      String,
      Option[ActorRef[AgentEvent]],
      Option[String],
      Option[String],
      Boolean,
      Option[String]
    ) => Behavior[AgentCommand]
  ): Behavior[AgentCommand] =
    // Build the full allowed tool set: whitelist + always-available + context-dependent
    val allowedTools = buildAllowedToolSet(agentDef, depth)
    val (filteredCalls, droppedCalls) = result.toolCalls.partition(tc => allowedTools.contains(tc.name))
    if droppedCalls.nonEmpty then
      val dropped = droppedCalls.map(_.name).distinct.mkString(", ")
      NebflowLogger.forName("nebflow.agent").warn(s"Tool calls filtered (not in allowed set): $dropped")

    val nextTurnIdx = state.turnIdx + 1

    // Track deferreds created for Permission so they can be saved to state
    val permissionDeferredRef = cats.effect.Ref.unsafe[IO, Option[cats.effect.Deferred[IO, Boolean]]](None)

    val isSubagent = depth > 0
    val sessionIdOpt = state.sessionId

    // Build ToolContext — projectRoot from Lifecycle cache if available.
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
        pekkoScheduler = Some(ctx.system.scheduler),
        fileLockManager = Some(resources.fileLockManager),
        fileChangeTracker = Some(resources.fileChangeTracker),
        hookEngine = resources.hookEngine,
        hookContext = HookContext(
          sessionId = state.sessionId,
          projectRoot = effectiveProjectRoot,
          cwd = effectiveProjectRoot
        ),
        folderId = state.folderId,
        mailboxAddress = state.session.sessionId,
        dreamSchedulerRef = resources.dreamSchedulerRef,
        sharedResources = Some(resources),
        actorSystem = Some(ctx.system),
        messages = state.messages
      )
      freshResults <- filteredCalls
        .parTraverse { call =>
          // AskUserQuestion renders its own UI via askUser event — skip generic toolStart/End
          val skipStreaming = call.name == "AskUserQuestion"
          (if !skipStreaming then
             emitStreamIO(
               state.wsSend,
               ctx,
               AgentStreamEvent.ToolStart(nebflow.core.summarizeToolCall(call)),
               isSubagent,
               sessionIdOpt
             )
           else IO.unit) *>
            (if ToolReversibility.isReversible(call.name, call.input) then
               // Reversible operations: auto-approve
               executeTool(call, toolCtx)
             else
               // Irreversible operations: ask user for confirmation
               askUserPermission(call, state, permissionDeferredRef, ctx, toolCtx)
            ).map(r => (call, r))
              .attempt
              .map {
                case Right(pair) => pair
                case Left(e) => (call, ToolExecResult(s"Tool error: ${e.getMessage}", isError = true))
              }
              .flatTap { (call, r) =>
                // AskUserQuestion renders its own UI — skip generic toolEnd
                if call.name != "AskUserQuestion" then
                  val summary = summarizeToolResult(call, r.content)
                  // For Card tools: frontend receives the full payload, LLM sees the summary
                  val frontendContent = r.frontendContent.getOrElse(r.content)
                  emitStreamIO(
                    state.wsSend,
                    ctx,
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
              // Layer 1: per-tool result size guard
              .flatMap { (call, r) =>
                ToolResultGuard
                  .guardResult(call, r, state.sessionId.getOrElse("default"))
                  .map(r => (call, r))
              }
        }
      // Layer 2: per-message aggregate budget guard
      guardedBatch <- ToolResultGuard.guardBatch(freshResults, state.sessionId.getOrElse("default"))
      // Include filtered-out tool calls as errors so the assistant message stays consistent
      droppedResults = droppedCalls.map { call =>
        (call, ToolExecResult(s"Tool not available: ${call.name}", isError = true))
      }
      _ <- IO(
        ctx.self ! ToolsComplete(
          guardedBatch ++ droppedResults,
          result.text,
          replyTo,
          None,
          result.thinking,
          result.thinkingSignature
        )
      )
    yield ()

    resources.dispatcher.unsafeRunAndForget(
      io.handleErrorWith { e =>
        val msg = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
        NebflowLogger
          .forName("nebflow.agent")
          .warn(
            s"pipeToolExecutions failed (this should be rare — individual tool errors are already handled): $msg"
          )
        IO(
          ctx.self ! LlmFailed(
            new RuntimeException(s"Tool execution pipeline failed: $msg"),
            replyTo,
            state.currentTurnId
          )
        )
      }
    )

    // Permission deferred is now managed via SetPermissionDeferred actor message,
    // so we don't need to read permissionDeferredRef here.
    val updatedState = state.copy(
      execution = state.execution.copy(turnIdx = nextTurnIdx)
    )
    processing(agentDef, resources, depth, parentRef, updatedState, stash, ctx)
  end pipeToolExecutions

  // ============================================================
  // Ask user for permission to execute a tool call
  // ============================================================

  private def askUserPermission(
    call: ToolCall,
    state: AgentState,
    permissionDeferredRef: Ref[IO, Option[cats.effect.Deferred[IO, Boolean]]],
    ctx: org.apache.pekko.actor.typed.scaladsl.ActorContext[AgentCommand],
    toolCtx: ToolContext
  ): IO[ToolExecResult] =
    // Use Ref.modify for atomic check-and-set — prevents the race condition where
    // multiple .parTraverse fibers all pass the None check before any set completes,
    // causing orphaned Deferreds that are never completed (deadlock).
    permissionDeferredRef.modify {
      case existing @ Some(_) =>
        // Another fiber already claimed the permission slot
        (existing, IO.pure(ToolExecResult("Another permission request is already pending", isError = true)))
      case None =>
        val deferred = cats.effect.Deferred.unsafe[IO, Boolean]
        (
          Some(deferred),
          IO(ctx.self ! AgentCommand.SetPermissionDeferred(deferred)) *>
            IO {
              val summary = nebflow.core.summarizeToolCall(call)
              // Determine danger level for frontend highlighting
              val dangerLevel =
                if call.name == "Bash" then
                  call.input("command").flatMap(_.asString).map(nebflow.core.tools.BashTool.dangerLevel).getOrElse(0)
                else if call.name == "Curl" then
                  call.input("method").flatMap(_.asString).map(_.toUpperCase) match
                    case Some(m) if !Set("GET", "HEAD", "OPTIONS").contains(m) => 2
                    case _ => 0
                else 1 // Unknown tools are level 1
              Json.obj(
                "type" -> "askPermission".asJson,
                "sessionId" -> state.sessionId.asJson,
                "toolName" -> call.name.asJson,
                "summary" -> summary.asJson,
                "input" -> call.input.asJson,
                "dangerLevel" -> dangerLevel.asJson
              )
            }.flatMap { permJson =>
              for
                _ <- state.wsSend(permJson)
                approved <- deferred.get
                result <-
                  if approved then executeTool(call, toolCtx)
                  else IO.pure(ToolExecResult("Permission denied by user", isError = true))
              yield result
            }
        )
    }.flatten

  // ============================================================
  // Unified tool execution — all tools go through executeTool
  // ============================================================

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
          // --- PreToolUse hook ---
          hookEngine
            .beforeTool(call.name, call.input, hookCtx)
            .flatMap { preResult =>
              if preResult.decision == HookDecision.Block then
                val blockMsg = preResult.reason.getOrElse(s"Tool ${call.name} blocked by hook")
                logger.info(s"$logCtx Hook blocked $summary: $blockMsg")
                IO.pure(ToolExecResult(blockMsg, isError = true))
              else
                // Merge updatedInput if provided
                val finalInput = preResult.updatedInput.getOrElse(call.input)

                // --- Check device parameter for remote execution ---
                val deviceOpt = finalInput("device").flatMap(_.asString).filter(_.nonEmpty).filter(_ != "local")

                val execIO: IO[ToolExecResult] = deviceOpt match
                  case Some(deviceName) if RemoteExecutor.current.isDefined =>
                    // Remote execution: route to target device via P2P or relay
                    val remoteInput = finalInput.remove("device")
                    val remoteSummary = s"[${deviceName}] ${tool.summarize(remoteInput)}"
                    logger.info(s"$logCtx Remote tool: $remoteSummary")
                    // Broadcast status to other devices (fire-and-forget)
                    val sid = ctx.sessionId.getOrElse("")
                    RemoteExecutor.current.get.meshServiceOpt.foreach { ms =>
                      given cats.effect.unsafe.IORuntime = cats.effect.unsafe.IORuntime.global
                      ms.broadcastAgentStatus("executing", sid, deviceName, call.name)
                        .unsafeRunAndForget()
                    }
                    RemoteExecutor.current.get
                      .execute(deviceName, call.name, remoteInput)
                      .flatMap {
                        case Right(result) =>
                          hookEngine.afterTool(call.name, finalInput, result, true, hookCtx)
                            .map { postResult =>
                              val hookSuffix = postResult.additionalContext.getOrElse("")
                              ToolExecResult(
                                result + (if hookSuffix.nonEmpty then s"\n\n$hookSuffix" else ""),
                                frontendContent = Some(result)
                              )
                            }
                        case Left(err) =>
                          hookEngine.afterToolFailure(call.name, finalInput, err.message, hookCtx)
                            .map { postResult =>
                              val appended = postResult.additionalContext match
                                case Some(ctx) => s"${err.message}\n\n$ctx"
                                case None => err.message
                              ToolExecResult(appended, isError = true)
                            }
                      }
                  case _ =>
                    // Local execution (original path)
                    tool
                      .call(finalInput, ctx)
                      .flatMap {
                        case Left(err) =>
                          // --- PostToolUseFailure hook ---
                          hookEngine
                            .afterToolFailure(call.name, finalInput, err.message, hookCtx)
                            .map { postResult =>
                              val appended = postResult.additionalContext match
                                case Some(ctx) => s"${err.message}\n\n$ctx"
                                case None => err.message
                              ToolExecResult(appended, isError = true)
                            }
                        case Right(result) =>
                          val isCard = call.name == "Card"
                          val isFileEdit = call.name == "Edit" || call.name == "Write"
                          hookEngine
                            .afterTool(call.name, finalInput, result, true, hookCtx)
                            .map { postResult =>
                              val hookSuffix = postResult.additionalContext.getOrElse("")
                              if isCard then
                                val title = call.input("title").flatMap(_.asString).getOrElse("")
                                val llmSummary = s"Card${if title.nonEmpty then s" ($title)" else ""} rendered"
                                ToolExecResult(
                                  llmSummary + (if hookSuffix.nonEmpty then s"\n\n$hookSuffix" else ""),
                                  frontendContent = Some(result + (if hookSuffix.nonEmpty then s"\n\n$hookSuffix" else ""))
                                )
                              else if isFileEdit then
                                val llmSummary = nebflow.core.summarizeToolResult(call, result)
                                ToolExecResult(
                                  llmSummary + (if hookSuffix.nonEmpty then s"\n\n$hookSuffix" else ""),
                                  frontendContent = Some(result + (if hookSuffix.nonEmpty then s"\n\n$hookSuffix" else ""))
                                )
                              else
                                ToolExecResult(
                                  result + (if hookSuffix.nonEmpty then s"\n\n$hookSuffix" else ""),
                                  frontendContent = Some(result + (if hookSuffix.nonEmpty then s"\n\n$hookSuffix" else ""))
                                )
                              end if
                            }
                      }

                execIO
                  .handleErrorWith {
                    case _: UserAbort => IO.raiseError(new UserAbort())
                    case e => IO.pure(ToolExecResult(s"Tool execution error: ${e.getMessage}", isError = true))
                  }
              end if
            }
            .flatTap { result =>
              val elapsed = (System.nanoTime() - start) / 1_000_000
              if result.isError then
                logger.warn(s"$logCtx Tool $summary failed (${elapsed}ms): ${result.content.take(100)}")
              else
                logger.info(
                  s"$logCtx Tool $summary OK (${elapsed}ms)"
                )
            }
        }
      case None =>
        IO.pure(ToolExecResult(s"No such tool available: ${call.name}", isError = true))

  // ============================================================
  // Tool list builder — uses ToolRegistry, no inline schema construction
  // ============================================================

  /** Build the set of allowed tool names for filtering tool call results. */
  protected def buildAllowedToolSet(agentDef: AgentDef, depth: Int = 0): Set[String] =
    val isMcpTool = (name: String) => name.startsWith("mcp__")
    val base = agentDef.tools match
      case Nil => Set.empty[String]
      case List("*") => ToolRegistry.ALL_TOOLS.map(_.name).filterNot(isMcpTool).toSet
      case names => names.toSet
    // Include MCP tools enabled by this agent via mcpServers config.
    val mcpToolNames = ToolRegistry.ALL_TOOLS.map(_.name).filter(isMcpTool)
    val mcpTools =
      if agentDef.mcpServers == List("*") then mcpToolNames.toSet
      else
        agentDef.mcpServers.flatMap { serverId =>
          val prefix = s"mcp__${serverId}__"
          mcpToolNames.filter(_.startsWith(prefix))
        }.toSet
    // Remove Delegate tool when at max depth — leaf agents cannot spawn children
    val depthFiltered = if depth >= nebflow.core.tools.DelegateTool.MaxDepth then base - "Delegate" else base
    depthFiltered ++ mcpTools

  end buildAllowedToolSet

  protected def buildToolList(agentDef: AgentDef, depth: Int = 0): Option[List[ToolDefinition]] =
    val allowedSet = buildAllowedToolSet(agentDef, depth)
    val tools = ToolRegistry.ALL_TOOLS.filter(t => allowedSet.contains(t.name))
    Some(tools)
  end buildToolList

  /** Enrich the Delegate tool description with available model options. */
  private def enrichDelegateTools(
    tools: Option[List[ToolDefinition]],
    models: List[(String, String, Option[String])]
  ): Option[List[ToolDefinition]] =
    if models.isEmpty then tools
    else
      tools.map(_.map { td =>
        if td.name == "Delegate" then
          val modelList = models
            .map { case (ref, _, desc) =>
              s"  - $ref" + desc.map(d => s": $d").getOrElse("")
            }
            .mkString("\n")
          td.copy(description =
            td.description + s"\n\nAvailable models (pass as the `model` parameter in \"provider/model\" format):\n$modelList"
          )
        else td
      })
  end enrichDelegateTools

  // ============================================================
  // Stream helpers
  // ============================================================

  protected def emitStream(
    dispatcher: cats.effect.std.Dispatcher[IO],
    wsSend: io.circe.Json => IO[Unit],
    ctx: ActorContext[?],
    event: AgentStreamEvent,
    isSubagent: Boolean = true,
    sessionId: Option[String] = None
  ): Unit =
    val eventName = event.getClass.getSimpleName
    val json = event.toJson(ctx.self.path.name, isSubagent, sessionId)
    dispatcher.unsafeRunAndForget(
      wsSend(json)
        .handleErrorWith(e =>
          IO(NebflowLogger.forName("nebflow.agent").warn(s"emitStream($eventName) failed: ${e.getMessage}"))
        )
    )

  end emitStream

  protected def emitStreamIO(
    wsSend: io.circe.Json => IO[Unit],
    ctx: ActorContext[?],
    event: AgentStreamEvent,
    isSubagent: Boolean = true,
    sessionId: Option[String] = None
  ): IO[Unit] =
    wsSend(event.toJson(ctx.self.path.name, isSubagent, sessionId))

  protected def streamEmitter(
    wsSend: io.circe.Json => IO[Unit],
    ctx: ActorContext[AgentCommand],
    isSubagent: Boolean = true,
    sessionId: Option[String] = None,
    isAskMode: Boolean = false,
    isCompactTurn: Boolean = false
  ): fs2.Pipe[IO, StreamChunk, StreamChunk] =
    stream =>
      stream.evalTap {
        case StreamChunk.TextDelta(delta) if delta.nonEmpty && !isCompactTurn =>
          val json =
            if isAskMode then
              Json.obj("type" -> "askTextDelta".asJson, "sessionId" -> sessionId.asJson, "delta" -> delta.asJson)
            else if isSubagent then AgentStreamEvent.TextDelta(delta).toJson(ctx.self.path.name, true, None)
            else Json.obj("type" -> "textDelta".asJson, "sessionId" -> sessionId.asJson, "delta" -> delta.asJson)
          wsSend(json)
        case StreamChunk.ThinkingDelta(delta) if delta.nonEmpty && !isCompactTurn =>
          val json =
            if isSubagent then AgentStreamEvent.Thinking.toJson(ctx.self.path.name, true, None)
            else Json.obj("type" -> "thinkingDelta".asJson, "sessionId" -> sessionId.asJson, "delta" -> delta.asJson)
          wsSend(json)
        case StreamChunk.ToolCallStart(name) if name != "AskUserQuestion" && !isCompactTurn =>
          val json =
            if isSubagent then AgentStreamEvent.ToolCallDetected(name).toJson(ctx.self.path.name, true, None)
            else Json.obj("type" -> "toolCallDetected".asJson, "sessionId" -> sessionId.asJson, "name" -> name.asJson)
          wsSend(json)
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
          wsSend(json)
        case _ => IO.unit
      }

  protected def aggregateChunks(chunks: List[StreamChunk]): ConsumeResult =
    val text = chunks.collect { case StreamChunk.TextDelta(d) => d }.mkString
    val thinking = chunks.collect { case StreamChunk.ThinkingDelta(d) => d }.mkString
    val thinkingSignature = chunks.collectFirst { case StreamChunk.ThinkingSignature(s) => s }
    val toolCalls = chunks.collect { case StreamChunk.ToolCallChunk(tc) => tc }
    // Prefer Done chunk with non-zero inputTokens (usage-only chunk from stream_options.include_usage
    // arrives AFTER the finish_reason chunk whose usage is null). Fall back to first Done's usage.
    val usage = chunks
      .collectFirst { case StreamChunk.Done(_, Some(u), _, _) if u.inputTokens > 0 => u }
      .orElse(chunks.collectFirst { case StreamChunk.Done(_, u, _, _) => u }.flatten)
    val stopReason = chunks.collectFirst { case StreamChunk.Done(sr, _, _, _) => sr }.flatten
    val model = chunks.collectFirst { case StreamChunk.Done(_, _, Some(meta), _) => meta.model }
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

  // ============================================================
  // Prompt / tool helpers
  // ============================================================

  protected def buildSystemPrompt(
    agentDef: AgentDef,
    resources: SharedResources,
    systemPrefix: String,
    sessionProjectRoot: Option[String] = None,
    sessionRulesMd: Option[String] = None,
    chatWidth: Int = 0
  ): String =
    val agentPrompt =
      if agentDef.systemPrompt.nonEmpty then agentDef.systemPrompt
      else Repl.loadSystemPrompt()
    val effectiveRoot = sessionProjectRoot.getOrElse(resources.projectRoot.toString)
    val envInfo = Repl.buildEnvInfo(effectiveRoot, chatWidth)  // device info moved to per-turn reminder
    val rulesBlock = sessionRulesMd.map(r => s"\n## Project Rules\n\n$r").getOrElse("")
    s"$systemPrefix$agentPrompt\n\n$envInfo$rulesBlock"
  end buildSystemPrompt

  /** Build device info for per-turn system reminder (not persisted in system prompt). */
  private def deviceReminder: Option[SystemReminder] =
    val msOpt = RemoteExecutor.current.flatMap(_.meshServiceOpt)
    msOpt.flatMap { ms =>
      try
        import cats.effect.unsafe.implicits.global
        val id = ms.identity.unsafeRunSync()
        val loggedIn = ms.isLoggedIn.unsafeRunSync()
        val peersList = if loggedIn then ms.peers.unsafeRunSync() else Nil
        val parts = List.newBuilder[String]
        parts += s"local (${id.deviceName}, ${id.platform})"
        val localCaps = id.capabilities.keys.toList.sorted
        if localCaps.nonEmpty then parts += s"[${localCaps.mkString(", ")}]"
        val localStr = parts.result.mkString(" ")
        val peerStrs = if loggedIn then peersList.map { p =>
          val caps = p.capabilities.keys.filterNot(_ == "os").toList.sorted
          val desc = p.userDescription
          val ps = List(s"${p.deviceName} (${p.platform})") ++
            (if caps.nonEmpty then List(s"[${caps.mkString(", ")}]") else Nil) ++
            (if desc.nonEmpty then List(s"-$desc") else Nil)
          ps.mkString(" ")
        } else Nil
        val allDevices = (localStr :: peerStrs).mkString("; ")
        val deviceHint = if loggedIn && peersList.nonEmpty then
          "\nEach tool accepts a `device` parameter. Select the appropriate device for each task."
        else ""
        Some(SystemReminder("devices", s"Devices: $allDevices$deviceHint"))
      catch case _: Exception => None
    }

  protected def summarizeToolResult(call: ToolCall, result: String): String =
    nebflow.core.summarizeToolResult(call, result)

end AgentCore
