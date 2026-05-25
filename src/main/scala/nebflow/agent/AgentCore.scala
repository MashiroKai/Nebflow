package nebflow.agent

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.agent.AgentCommand.*
import nebflow.core.*
import nebflow.core.compact.*
import nebflow.core.hooks.*
import nebflow.core.tools.{ToolContext, ToolRegistry, ToolResultGuard}
import nebflow.service.MemoryStore
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
            AgentStreamEvent.AgentStart(agentDef.name, agentDef.description),
            isSubagent = true,
            state.sessionId
          )

        // In compact mode, disable tools — model must respond with summary text only
        val tools = if isCompactTurn then Some(Nil) else buildToolList(agentDef)

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

        // NOTE: context-budget-exceeded removed — maybeAutoCompact already covers both
        // API-reported inputTokens AND estimate-based fallback with the same threshold.
        // Compute verification reminder from per-agent write tracker (pure, no IO needed)
        val (verificationOpt, updatedWriteTracker) =
          SystemReminders.computeVerificationReminder(stateForTurn.writesSinceLastRead)
        val stateForLlm = stateForTurn.withWritesSinceLastRead(updatedWriteTracker)

        val io = for
          // --- Unified EveryTurn refresh: agentDef, projectRoot, rulesMd, memory, thinking, fileChanges ---
          turnCtx <- ContextRefresher.refreshTurn(stateForTurn, resources, agentDef)
          freshDef = turnCtx.agentDef
          baseSystemStable = buildSystemPrompt(freshDef, resources, turnCtx.projectRoot, turnCtx.rulesMd)
          // --- reminders (async) ---
          // isUserTurn: last message is a plain-text user message (not tool results wrapped as User)
          isUserTurn = stateForLlm.messages.lastOption.exists(m => m.role == MessageRole.User && m.content.isLeft)
          collectAllResult = SystemReminders.collectAll(
            stateForLlm.compaction.highestPressureLevel,
            stateForLlm.latestUsage,
            state.contextWindow,
            turnCtx.fileChanges,
            isUserTurn
          )
          reminders = collectAllResult._1
          newHighest = collectAllResult._2
          _ <- IO(ctx.self ! AgentCommand.UpdateHighestPressureLevel(newHighest))
          loggedReminders <- SystemReminders.logAndReturn(reminders)
          // --- Active task reminder ---
          // Only when pendingTaskCheck is set (LLM had text+tools or used TaskUpdate).
          // Load active tasks and inject a reminder so LLM updates remaining tasks.
          taskReminder <- (
            if isCompactTurn || isAskTurn || !stateForLlm.execution.pendingTaskCheck then IO.pure("")
            else
              resources.taskStore
                .listActive(stateForLlm.sessionId.getOrElse(""))
                .map { activeTasks =>
                  if activeTasks.isEmpty then ""
                  else
                    val lines = activeTasks.map { t =>
                      val st = t.status match
                        case nebflow.core.task.TaskStatus.InProgress => "in_progress"
                        case _ => "pending"
                      s"  #${t.id} [$st] ${t.subject}"
                    }
                    s"""\n<system-reminder>
                       |You still have ${activeTasks.size} active task(s):
                       |${lines.mkString("\n")}
                       |
                       |Please call TaskUpdate to mark tasks as completed or failed when done.
                       |</system-reminder>""".stripMargin
                }
                .handleError(_ => "")
          )
          // Clear the flag after reading
          _ <- IO(ctx.self ! AgentCommand.ClearTaskCheck)
          allReminders = verificationOpt.toList ++ loggedReminders
          remindersText = SystemReminder.renderAll(allReminders) + taskReminder
          // Per-turn dynamic context: only reminders (env info is now in system prompt)
          // Skip for compact/ask turns — the reminder is already in messages
          dynamicMsg =
            if isCompactTurn || isAskTurn then Nil
            else if remindersText.nonEmpty then List(Message(MessageRole.User, Left(remindersText)))
            else Nil
          // --- LLM call ---
          // Use fresh agentDef from TurnContext for tools and system prompt
          freshTools = if isCompactTurn then Some(Nil) else buildToolList(freshDef)
          systemStable = baseSystemStable +
            (if turnCtx.memoryBlock.nonEmpty then s"\n\n${turnCtx.memoryBlock}" else "") +
            stateForLlm.language
              .map(l =>
                s"\n\n# Language\n- Respond in $l.\n- When creating tasks (TaskCreate), the `subject` and `activeForm` fields MUST be in $l.\n- When writing to memory files (Agent/Session/User memory), all content MUST be in $l.\n- All user-visible text must be in $l."
              )
              .getOrElse("") +
            // NOTE: active task reminder — if you created tasks, clean them up when done
            "\n\n# Task Management\n- After creating a task with TaskCreate, use TaskUpdate to update its status when you finish or abandon it.\n- Mark tasks as `completed` when done, `failed` if blocked or abandoned.\n- If a task is no longer relevant (e.g. user changed their mind), mark it as `failed`.\n- Never leave tasks in `in_progress` status at the end of your work."
          request = LlmRequest(
            messages = stateForLlm.messages ++ dynamicMsg,
            sessionId = stateForLlm.sessionId.getOrElse(ctx.self.path.name),
            agentId = freshDef.name,
            tools = freshTools,
            maxTokens = Some(resources.agentLibrary.globalMaxTokens),
            thinking = Some(nebflow.llm.ThinkingConfig.toLlmJson(turnCtx.thinkingConfig)),
            systemStable = Some(systemStable)
          )
          result <- resources.llm
            .sendStream(request, onAttempt = Some(onAttemptCb))
            .through(withInactivityTimeout(Defaults.LlmStreamInactivitySec.seconds))
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
    val allowedTools = buildAllowedToolSet(agentDef)
    val (filteredCalls, droppedCalls) = result.toolCalls.partition(tc => allowedTools.contains(tc.name))
    if droppedCalls.nonEmpty then
      val dropped = droppedCalls.map(_.name).distinct.mkString(", ")
      NebflowLogger.forName("nebflow.agent").warn(s"Tool calls filtered (not in allowed set): $dropped")

    val nextTurnIdx = state.turnIdx + 1

    // Track deferreds created for Permission so they can be saved to state
    val permissionDeferredRef = cats.effect.Ref.unsafe[IO, Option[cats.effect.Deferred[IO, Boolean]]](None)

    val isSubagent = depth > 0
    val sessionIdOpt = state.sessionId

    // Build ToolContext inside the IO block so projectRoot is hot-reloadable.
    val io = for
      (freshProjectRoot, _) <- ContextRefresher.refreshFolderContext(state, resources, agentDef.name)
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
        inputTokens = state.latestUsage.map(_.inputTokens),
        hookEngine = resources.hookEngine,
        hookContext = HookContext(
          sessionId = state.sessionId,
          projectRoot = effectiveProjectRoot,
          cwd = effectiveProjectRoot
        ),
        memoryAgentManager = resources.memoryAgentManager,
        folderId = state.folderId,
        postOffice = resources.postOffice,
        mailboxAddress = state.session.mailbox.map(_.address)
      )
      freshResults <- filteredCalls
        .traverse { call =>
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
            (if isSessionMemoryFile(call, state, agentDef) then
               // Session memory files: auto-approve (agent can edit freely)
               executeTool(call, toolCtx)
             else if ToolReversibility.isReversible(call.name, call.input) then
                // Reversible operations: auto-approve
                executeTool(call, toolCtx)
              else
                // Irreversible operations: ask user for confirmation
                askUserPermission(call, state, permissionDeferredRef, ctx, toolCtx)
            ) .map(r => (call, r)).attempt
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
                    input = Some(call.input),
                    truncated = r.truncated
                  ),
                  isSubagent,
                  sessionIdOpt
                )
              else IO.unit
            }
        }
      // Include filtered-out tool calls as errors so the assistant message stays consistent
      droppedResults = droppedCalls.map { call =>
        (call, ToolExecResult(s"Tool not available: ${call.name}", isError = true))
      }
      // Track per-file write count since last read (per-agent, pure computation)
      readFiles = freshResults
        .collect {
          case (call, result) if call.name == "Read" && !result.isError =>
            call.input("file_path").flatMap(_.asString).getOrElse("")
        }
        .filter(_.nonEmpty)
        .toSet
      writtenFiles = freshResults
        .collect {
          case (call, result) if Set("Write", "Edit").contains(call.name) && !result.isError =>
            call.input("file_path").flatMap(_.asString).getOrElse("")
        }
        .filter(_.nonEmpty)
      updatedWriteTracker = SystemReminders.updateWriteTracker(
        state.writesSinceLastRead,
        readFiles,
        writtenFiles
      )
      _ <- IO(
        ctx.self ! ToolsComplete(
          freshResults ++ droppedResults,
          result.text,
          replyTo,
          None,
          result.thinking,
          result.thinkingSignature,
          updatedWriteTracker
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
    permissionDeferredRef.get.flatMap {
      case Some(_) =>
        IO.pure(ToolExecResult("Another permission request is already pending", isError = true))
      case None =>
        val deferred = cats.effect.Deferred.unsafe[IO, Boolean]
        permissionDeferredRef.set(Some(deferred)) *>
          IO(ctx.self ! AgentCommand.SetPermissionDeferred(deferred)) *>
          IO {
            val summary = nebflow.core.summarizeToolCall(call)
            Json.obj(
              "type" -> "askPermission".asJson,
              "sessionId" -> state.sessionId.asJson,
              "toolName" -> call.name.asJson,
              "summary" -> summary.asJson,
              "input" -> call.input.asJson
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
    }

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

                // --- Execute tool ---
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
                      // ToolResultGuard truncates LLM-facing content to protect context window.
                      // Raw result is always preserved in frontendContent for correct frontend rendering.
                      val guarded = ToolResultGuard.guard(result, call.name, ctx) match
                        case ToolResultGuard.Ok(c) => c
                      val wasTruncated = guarded.length < result.length
                      if wasTruncated then
                        logger.warn(
                          s"$logCtx Tool $summary result truncated: ${result.length} → ${guarded.length} chars"
                        )
                      val isCard = call.name == "Card"
                      val isFileEdit = call.name == "Edit" || call.name == "Write"
                      // --- PostToolUse hook operates on LLM-visible content ---
                      hookEngine
                        .afterTool(call.name, finalInput, guarded, true, hookCtx)
                        .map { postResult =>
                          val hookSuffix = postResult.additionalContext.getOrElse("")
                          if isCard then
                            // Card tool: LLM sees a compact summary (not the guarded HTML)
                            val title = call.input("title").flatMap(_.asString).getOrElse("")
                            val llmSummary = s"Card${if title.nonEmpty then s" ($title)" else ""} rendered"
                            ToolExecResult(
                              llmSummary + (if hookSuffix.nonEmpty then s"\n\n$hookSuffix" else ""),
                              frontendContent = Some(result + (if hookSuffix.nonEmpty then s"\n\n$hookSuffix" else "")),
                              truncated = wasTruncated
                            )
                          else if isFileEdit then
                            // Edit/Write: LLM already knows what it wrote — no need to echo the diff.
                            // Full diff is preserved in frontendContent for the user to review.
                            val llmSummary = nebflow.core.summarizeToolResult(call, result)
                            ToolExecResult(
                              llmSummary + (if hookSuffix.nonEmpty then s"\n\n$hookSuffix" else ""),
                              frontendContent = Some(result + (if hookSuffix.nonEmpty then s"\n\n$hookSuffix" else "")),
                              truncated = false
                            )
                          else
                            ToolExecResult(
                              guarded + (if hookSuffix.nonEmpty then s"\n\n$hookSuffix" else ""),
                              frontendContent = Some(result + (if hookSuffix.nonEmpty then s"\n\n$hookSuffix" else "")),
                              truncated = wasTruncated
                            )
                          end if
                        }
                  }
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
                  s"$logCtx Tool $summary OK (${elapsed}ms)" + (if result.truncated then " [truncated]" else "")
                )
            }
        }
      case None =>
        IO.pure(ToolExecResult(s"No such tool available: ${call.name}", isError = true))

  // ============================================================
  // Tool list builder — uses ToolRegistry, no inline schema construction
  // ============================================================

  /** Build the set of allowed tool names for filtering tool call results. */
  protected def buildAllowedToolSet(agentDef: AgentDef): Set[String] =
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
    base ++ mcpTools

  end buildAllowedToolSet

  protected def buildToolList(agentDef: AgentDef): Option[List[ToolDefinition]] =
    val allowedSet = buildAllowedToolSet(agentDef)
    val tools = ToolRegistry.ALL_TOOLS.filter(t => allowedSet.contains(t.name))
    Some(tools)
  end buildToolList

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

  /**
   * Stream pipe: raises TimeoutException if no element passes through within `d`.
   * Resets the timer on each element. Detects hung LLM connections (e.g. after Mac sleep/wake).
   *
   * Uses System.currentTimeMillis() (not nanoTime) because nanoTime freezes during
   * Mac sleep/wake, which would prevent timeout detection after wake.
   * The concurrent watchdog runs alongside the main stream and is cancelled when the
   * main stream completes normally. Watchdog errors propagate via Concurrent semantics.
   */
  private def withInactivityTimeout[O](d: FiniteDuration): fs2.Pipe[IO, O, O] =
    val timeoutEx = new java.util.concurrent.TimeoutException(
      s"LLM stream inactive for ${d.toSeconds}s"
    )
    in =>
      fs2.Stream.eval(IO.ref(System.currentTimeMillis())).flatMap { lastActivity =>
        val main = in.evalTap(_ => lastActivity.set(System.currentTimeMillis()))
        // Check at half the timeout interval for timely detection (min 5s, max 30s)
        val checkInterval = math.max(math.min(d.toMillis / 5, 30000L), 5000L).millis
        val watchdog = fs2.Stream
          .awakeEvery[IO](checkInterval)
          .evalMap { _ =>
            IO(System.currentTimeMillis()).flatMap { now =>
              lastActivity.get.flatMap { last =>
                if now - last > d.toMillis then IO.raiseError(timeoutEx)
                else IO.unit
              }
            }
          }
          .drain
        main.concurrently(watchdog)
      }
  end withInactivityTimeout

  // ============================================================
  // Prompt / tool helpers
  // ============================================================

  protected def buildSystemPrompt(
    agentDef: AgentDef,
    resources: SharedResources,
    sessionProjectRoot: Option[String] = None,
    sessionRulesMd: Option[String] = None
  ): String =
    val prefix = loadPlatformPrefix()
    val agentPrompt =
      if agentDef.systemPrompt.nonEmpty then agentDef.systemPrompt
      else Repl.loadSystemPrompt()
    val effectiveRoot = sessionProjectRoot.getOrElse(resources.projectRoot.toString)
    val envInfo = Repl.buildEnvInfo(effectiveRoot)
    val rulesBlock = sessionRulesMd.map(r => s"\n## Project Rules\n\n$r").getOrElse("")
    s"$prefix$agentPrompt\n\n$envInfo$rulesBlock"

  /** Platform-level system prompt. Checks filesystem first (live-editable), falls back to JAR resource. */
  private def loadPlatformPrefix(): String =
    val fsPath = os.home / ".nebflow" / "system-prefix.md"
    val content =
      if os.exists(fsPath) then os.read(fsPath)
      else
        val is = getClass.getResourceAsStream("/system-prefix.md")
        if is != null then
          val s = scala.io.Source.fromInputStream(is)(scala.io.Codec.UTF8).mkString
          is.close()
          s
        else ""
    val trimmed = content.trim
    if trimmed.nonEmpty then trimmed + "\n\n" else ""

  protected def summarizeToolResult(call: ToolCall, result: String): String =
    nebflow.core.summarizeToolResult(call, result)

  /** Check if an Edit/Write call targets the current session's memory file. */
  private def isSessionMemoryFile(call: ToolCall, state: AgentState, agentDef: AgentDef): Boolean =
    val pathOpt = call.input("file_path").flatMap(_.asString)
    (call.name == "Edit" || call.name == "Write") && pathOpt.exists { path =>
      path.endsWith(".memory.md") && state.sessionId.exists { sid =>
        path == MemoryStore.sessionMemoryPath(sid).toString
      }
    }

end AgentCore
