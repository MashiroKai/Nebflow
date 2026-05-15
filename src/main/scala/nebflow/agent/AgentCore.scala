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
          val backoff = config.compactionRetryDelayMs * math.pow(2, state.compactionFailures - 1).toLong
          val elapsed = System.currentTimeMillis() - state.lastCompactionFailureAt
          val ok = elapsed >= backoff
          if !ok then
            logAgentEvent(
              ctx,
              agentDef,
              depth,
              state.sessionId,
              state.sessionName,
              "auto-compact-skipped",
              s"backoff=${backoff}ms elapsed=${elapsed}ms failures=${state.compactionFailures}"
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
        inputTokensOpt match
          case Some(inputTokens) if inputTokens > threshold =>
            logAgentEvent(
              ctx,
              agentDef,
              depth,
              state.sessionId,
              state.sessionName,
              "auto-compact-trigger",
              s"inputTokens=$inputTokens threshold=$threshold"
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
          case _ => None
        end match
      end if
  end maybeAutoCompact

  /**
   * Start a direct compaction via CompactService (no sub-agent).
   * Returns the processing behavior with a pending compaction job.
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

    // Fire-and-forget CompactService call
    val io = CompactService
      .compact(
        state.messages,
        resources,
        state.sessionId.getOrElse(ctx.self.path.name),
        state.readTracker
      )
      .map { result =>
        ctx.self ! AgentCommand.CompactionComplete(result)
      }
    resources.dispatcher.unsafeRunAndForget(
      io.handleError { e =>
        NebflowLogger.forName("nebflow.agent").warn(s"CompactService failed: ${e.getMessage}")
        ctx.self ! AgentCommand.CompactionComplete(Left(e.getMessage))
      }
    )

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

    processing(agentDef, resources, depth, parentRef, state.withPendingCompaction(Some(pending)), stash, ctx)
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
      Boolean,
      Option[String]
    ) => Behavior[AgentCommand]
  ): Behavior[AgentCommand] =
    maybeAutoCompact(agentDef, resources, depth, parentRef, state, stash, ctx, replyTo, processing) match
      case Some(behavior) => behavior
      case None =>
        if depth > 0 then
          emitStream(
            resources.dispatcher,
            state.wsSend,
            ctx,
            AgentStreamEvent.AgentStart(agentDef.name, agentDef.description),
            isSubagent = true,
            state.sessionId
          )

        // Cache-optimal layout for Anthropic prompt caching (prefix order: system -> tools -> messages):
        //   system[0]: stable system prompt + static env info  <- cache breakpoint (never changes)
        //   tools:     tool definitions                          <- cache breakpoint (stable)
        //   messages:  [per-turn reminders] + actual conversation (dynamic, not persisted)
        // Env info is baked into system prompt once — git state is omitted; agent uses Bash on demand.
        val baseSystemStable = buildSystemPrompt(agentDef, resources)
        val tools = buildToolList(agentDef)

        val isSubagent = depth > 0
        val sessionIdOpt = state.sessionId

        val onAttemptCb: FallbackAttempt => IO[Unit] = attempt =>
          val msg = attempt.message.getOrElse(s"${attempt.providerId}/${attempt.model} failed, retrying...")
          emitStreamIO(state.wsSend, ctx, AgentStreamEvent.RetryStatus(msg), isSubagent, sessionIdOpt)

        // Assign a monotonically increasing turnId so stale LlmComplete/LlmFailed from
        // a cancelled (interrupted) turn can be detected and discarded.
        val turnId = state.currentTurnId + 1

        // FastMicroCompact: rule-driven tool result cleanup when cache is cold.
        // Must write back to state so subsequent turns see the compacted messages.
        val microResult = FastMicroCompact(state.messages)
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

        // --- Memory: use cached memoryBlock from state (refreshed at lifecycle points) ---
        val memoryBlock = stateForTurn.memoryBlock

        // Compute verification reminder from per-agent write tracker (pure, no IO needed)
        val (verificationOpt, updatedWriteTracker) =
          SystemReminders.computeVerificationReminder(stateForTurn.writesSinceLastRead)
        val stateForLlm = stateForTurn.withWritesSinceLastRead(updatedWriteTracker)

        val io = for
          // --- reminders (async) ---
          fileChangesOpt <- resources.fileChangeTracker.checkChanges()
          // isUserTurn: last message is a plain-text user message (not tool results wrapped as User)
          isUserTurn = stateForLlm.messages.lastOption.exists(m => m.role == MessageRole.User && m.content.isLeft)
          collectAllResult = SystemReminders.collectAll(
            stateForLlm.compaction.highestPressureLevel,
            stateForLlm.latestUsage,
            state.contextWindow,
            fileChangesOpt,
            isUserTurn
          )
          reminders = collectAllResult._1
          newHighest = collectAllResult._2
          _ <- IO(ctx.self ! AgentCommand.UpdateHighestPressureLevel(newHighest))
          loggedReminders <- SystemReminders.logAndReturn(reminders)
          allReminders = verificationOpt.toList ++ loggedReminders
          remindersText = SystemReminder.renderAll(allReminders)
          // Per-turn dynamic context: only reminders (env info is now in system prompt)
          dynamicMsg =
            if remindersText.nonEmpty then List(Message(MessageRole.User, Left(remindersText)))
            else Nil
          // --- LLM call ---
          thinkingOpt <- resources.runtimePrefs.getThinking
          languageOpt <- resources.runtimePrefs.getLanguage
          systemStable = baseSystemStable +
            (if memoryBlock.nonEmpty then s"\n\n$memoryBlock" else "") +
            languageOpt.map(l => s"\n\n# Language\nAlways respond in $l.").getOrElse("")
          request = LlmRequest(
            messages = stateForLlm.messages ++ dynamicMsg,
            sessionId = stateForLlm.sessionId.getOrElse(ctx.self.path.name),
            agentId = agentDef.name,
            tools = tools,
            maxTokens = Some(resources.agentLibrary.globalMaxTokens),
            thinking = thinkingOpt.map(nebflow.service.ThinkingConfig.toLlmJson),
            systemStable = Some(systemStable)
          )
          result <- resources.llm
            .sendStream(request, onAttempt = Some(onAttemptCb))
            .through(streamEmitter(stateForLlm.wsSend, ctx, isSubagent, sessionIdOpt))
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

    // Separate ContextManage from real tool calls — handled directly in ToolsComplete handler
    // (not sent as TriggerCompaction message to avoid stash timing issues)
    val (contextManageCalls, realToolCalls) = filteredCalls.partition(_.name == "ContextManage")

    // Build ToolContext with full agent-scoped context
    val toolCtx = ToolContext(
      projectRoot = resources.projectRoot.toString,
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
        projectRoot = resources.projectRoot.toString,
        cwd = resources.projectRoot.toString
      )
    )

    val io = realToolCalls
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
      .flatMap { freshResults =>
        // ContextManage: generate synthetic results (triggered in ToolsComplete handler, not here)
        val contextManageResults = contextManageCalls.map { call =>
          (call, ToolExecResult(s"[ContextManage] Triggered full compaction", isError = false))
        }
        val allResults = freshResults ++ contextManageResults
        // Include filtered-out tool calls as errors so the assistant message stays consistent
        val droppedResults = droppedCalls.map { call =>
          (call, ToolExecResult(s"Tool not available: ${call.name}", isError = true))
        }

        // Track per-file write count since last read (per-agent, pure computation)
        val readFiles = freshResults
          .collect {
            case (call, result) if call.name == "Read" && !result.isError =>
              call.input("file_path").flatMap(_.asString).getOrElse("")
          }
          .filter(_.nonEmpty)
          .toSet
        val writtenFiles = freshResults
          .collect {
            case (call, result) if Set("Write", "Edit").contains(call.name) && !result.isError =>
              call.input("file_path").flatMap(_.asString).getOrElse("")
          }
          .filter(_.nonEmpty)
        val updatedWriteTracker = SystemReminders.updateWriteTracker(
          state.writesSinceLastRead,
          readFiles,
          writtenFiles
        )

        IO(
          ctx.self ! ToolsComplete(
            allResults ++ droppedResults,
            result.text,
            replyTo,
            None,
            result.thinking,
            updatedWriteTracker
          )
        )
      }

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
                      val isCard = call.name == "Card"
                      val guarded = ToolResultGuard.guard(result, call.name, ctx)
                      guarded match
                        case ToolResultGuard.Ok(content) =>
                          if content.length < result.length then
                            logger.warn(
                              s"$logCtx Tool $summary result truncated: ${result.length} → ${content.length} chars"
                            )
                          // --- PostToolUse hook ---
                          hookEngine
                            .afterTool(call.name, finalInput, content, true, hookCtx)
                            .map { postResult =>
                              val finalContent = postResult.additionalContext match
                                case Some(ctx) => s"$content\n\n$ctx"
                                case None => content
                              if isCard then
                                // Card tool: LLM sees a compact summary, frontend gets the full HTML from input
                                val title = call.input("title").flatMap(_.asString).getOrElse("")
                                val llmSummary = s"Card${if title.nonEmpty then s" ($title)" else ""} rendered"
                                ToolExecResult(llmSummary, frontendContent = Some(finalContent))
                              else ToolExecResult(finalContent)
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
    // Each serverId maps to tools registered as mcp__{serverId}__{toolName}
    // mcpServers: ["*"] means all MCP tools; specific serverIds filter by prefix.
    val mcpToolNames = ToolRegistry.ALL_TOOLS.map(_.name).filter(isMcpTool)
    val mcpTools =
      if agentDef.mcpServers == List("*") then mcpToolNames.toSet
      else
        agentDef.mcpServers.flatMap { serverId =>
          val prefix = s"mcp__${serverId}__"
          mcpToolNames.filter(_.startsWith(prefix))
        }.toSet
    val always =
      if agentDef.name == "context-manage" then ToolRegistry.AlwaysAvailable
      else ToolRegistry.AlwaysAvailableNonCompact
    base ++ mcpTools ++ always

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
    sessionId: Option[String] = None
  ): fs2.Pipe[IO, StreamChunk, StreamChunk] =
    stream =>
      stream.evalTap {
        case StreamChunk.TextDelta(delta) if delta.nonEmpty =>
          val json =
            if isSubagent then AgentStreamEvent.TextDelta(delta).toJson(ctx.self.path.name, true, None)
            else Json.obj("type" -> "textDelta".asJson, "sessionId" -> sessionId.asJson, "delta" -> delta.asJson)
          wsSend(json)
        case StreamChunk.ThinkingDelta(delta) if delta.nonEmpty =>
          val json =
            if isSubagent then AgentStreamEvent.Thinking.toJson(ctx.self.path.name, true, None)
            else Json.obj("type" -> "thinking".asJson, "sessionId" -> sessionId.asJson)
          wsSend(json)
        case StreamChunk.ToolCallStart(name) if name != "AskUserQuestion" =>
          val json =
            if isSubagent then AgentStreamEvent.ToolCallDetected(name).toJson(ctx.self.path.name, true, None)
            else Json.obj("type" -> "toolCallDetected".asJson, "sessionId" -> sessionId.asJson, "name" -> name.asJson)
          wsSend(json)
        case StreamChunk.ToolCallChunk(tc) if tc.name != "AskUserQuestion" =>
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
    val toolCalls = chunks.collect { case StreamChunk.ToolCallChunk(tc) => tc }
    val usage = chunks.collectFirst { case StreamChunk.Done(_, u, _, _) => u }.flatten
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
      model,
      contextWindow
    )

  // ============================================================
  // Prompt / tool helpers
  // ============================================================

  protected def buildSystemPrompt(agentDef: AgentDef, resources: SharedResources): String =
    val prefix = loadPlatformPrefix()
    val agentPrompt =
      if agentDef.systemPrompt.nonEmpty then agentDef.systemPrompt
      else Repl.loadSystemPrompt()
    val envInfo = Repl.buildEnvInfo(resources.projectRoot.toString)
    s"$prefix$agentPrompt\n\n$envInfo"

  /** Platform-level system prompt, mandatory for all agents, shipped inside the JAR. */
  private def loadPlatformPrefix(): String =
    val is = getClass.getResourceAsStream("/system-prefix.md")
    if is != null then
      val content = scala.io.Source.fromInputStream(is)(scala.io.Codec.UTF8).mkString.trim
      is.close()
      if content.nonEmpty then content + "\n\n" else ""
    else ""

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

  /**
   * Build the full memory block: file contents + guide + file paths.
   * Called at lifecycle points (new session, compact resume) — NOT every turn.
   */
  protected def buildMemoryBlock(agentDef: AgentDef, sessionId: Option[String]): String =
    val userMemory = MemoryStore.loadUserMemory
    val agentMemory = MemoryStore.loadAgentMemory(agentDef.name)
    val sessionMemory = sessionId.flatMap(MemoryStore.loadSessionMemory)
    val memorySections = List(
      sessionMemory.map(c => s"# Memory — Session Context\n$c"),
      agentMemory.map(c => s"# Memory — Agent\n$c"),
      userMemory.map(c => s"# Memory — User Preferences\n$c")
    ).flatten
    val memoryGuide =
      """|# Memory Usage Guidelines
         |You have three memory scopes, each a Markdown file you can edit with Edit/Write. Writing memory is HIGH priority — err on the side of writing too much rather than too little.
         |
         |## You MUST write memory when:
         |1. Starting a new task → write goal and key files to Session memory
         |2. Discovering a project convention, architecture pattern, or non-obvious gotcha → write to Agent memory immediately (do NOT wait until "between tasks")
         |3. Completing a task → summarize durable findings from Session into Agent memory
         |4. The user explicitly asks you to remember something → write to User memory
         |5. You encounter and solve a non-trivial problem (wrong API usage, hidden config, surprising behavior) → write to Agent memory so you don't repeat the same discovery
         |
         |## Memory scope guide
         |- **Session** (working scratchpad): Task goals, key file paths, progress notes, open questions. Write at task start, update as you go, summarize key findings at task end.
         |- **Agent** (durable knowledge): Architecture decisions, project conventions, tool configurations, gotchas, debugging patterns, key file locations. Write IMMEDIATELY when you discover something — do not batch or defer.
         |- **User** (preferences): Only when the user explicitly asks, or you've confirmed a strong pattern across multiple sessions.
         |
         |## How to write
         |- Use concise bullet points, not prose.
         |- Prefix each bullet with a tag: `[decision]`, `[fact]`, `[todo]`, `[convention]`, `[gotcha]`.
         |- Example: `[decision] Use JWT RS256 for auth` / `[gotcha] file X must be loaded before Y or init fails`
         |- Do NOT duplicate information already in the agent's system prompt or project config files.
         |- Do NOT log transient state (current line numbers, temporary errors being fixed).
         |
         |## Self-check (ask yourself every few turns)
         |- Did I learn something about this project I didn't know before? → Write to Agent memory
         |- Am I working on a multi-step task? → Ensure Session memory is up to date
         |- Did I just finish a task? → Transfer key learnings from Session to Agent memory""".stripMargin

    val memoryFiles = sessionId match
      case Some(sid) =>
        s"""|# Memory Files
            |- Session context (auto-approved): ${MemoryStore.sessionMemoryPath(sid)}
            |- Agent knowledge (requires approval): ${MemoryStore.agentMemoryPath(agentDef.name)}
            |- User preferences (requires approval): ${MemoryStore.userMemoryPath}""".stripMargin
      case None =>
        s"""|# Memory Files
            |- Agent knowledge (requires approval): ${MemoryStore.agentMemoryPath(agentDef.name)}
            |- User preferences (requires approval): ${MemoryStore.userMemoryPath}""".stripMargin

    (memorySections :+ memoryGuide :+ memoryFiles).mkString("\n\n")
  end buildMemoryBlock

end AgentCore
