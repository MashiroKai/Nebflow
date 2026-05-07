package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.agent.AgentCommand.*
import nebflow.core.*
import nebflow.core.compact.*
import nebflow.core.tools.{ToolContext, ToolRegistry, ToolResultGuard}
import nebflow.shared.*
import nebflow.shared.given
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

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
    if agentDef.name == "context-manage" then None
    else if state.pendingCompaction.isDefined then None
    else if state.compactionFailures >= CompactConfig().circuitBreakerMax then
      logAgentEvent(
        ctx,
        agentDef,
        depth,
        state.sessionId,
        state.sessionName,
        "auto-compact-skipped",
        s"circuitBreakerOpen failures=${state.compactionFailures} max=${CompactConfig().circuitBreakerMax}"
      )
      None
    else
      val inputTokensOpt = state.latestUsage.map(_.inputTokens)
      val threshold = agentDef.contextWindow - CompactConfig().bufferTokens
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
          val subId = s"context-manage-${java.util.UUID.randomUUID().toString.take(8)}"
          val io = resources.agentLibrary.get("context-manage").flatMap { defnOpt =>
            IO(ctx.self ! AgentCommand.CompactionDefLoaded(defnOpt))
          }
          resources.dispatcher.unsafeRunAndForget(
            io.handleErrorWith { e =>
              IO(NebflowLogger.forName("nebflow.agent").warn(s"CompactionDefLoaded lookup failed: ${e.getMessage}")) *>
                IO(ctx.self ! AgentCommand.CompactionDefLoaded(None))
            }
          )
          val pending = CompactionJob(subId, "full", None, replyTo)
          resources.dispatcher.unsafeRunAndForget(
            emitStreamIO(
              state.wsSend,
              ctx,
              AgentStreamEvent.CompactStart("full", Some(inputTokens), Some(threshold)),
              isSubagent = depth > 0,
              state.sessionId
            )
              .handleErrorWith(_ => IO.unit)
          )
          Some(
            processing(agentDef, resources, depth, parentRef, state.withPendingCompaction(Some(pending)), stash, ctx)
          )
        case _ => None
      end match
  end maybeAutoCompact

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
          val baseSystemStable =
            if agentDef.systemPrompt.nonEmpty then agentDef.systemPrompt
            else Repl.loadSystemPrompt() + "\n\n" + Repl.buildEnvInfo(resources.projectRoot.toString)
          val tools = buildToolList(agentDef, depth, parentRef.isDefined)

          val isSubagent = depth > 0
          val sessionIdOpt = state.sessionId

          val onAttemptCb: FallbackAttempt => IO[Unit] = attempt =>
            val msg = attempt.message.getOrElse(s"${attempt.providerId}/${attempt.model} failed, retrying...")
            emitStreamIO(state.wsSend, ctx, AgentStreamEvent.RetryStatus(msg), isSubagent, sessionIdOpt)

          // Assign a monotonically increasing turnId so stale LlmComplete/LlmFailed from
          // a cancelled (interrupted) turn can be detected and discarded.
          val turnId = state.currentTurnId + 1
          val stateForTurn = state.withCurrentTurnId(turnId)

          val io = for
            // --- reminders (async) ---
            fileChangesOpt <- resources.fileChangeTracker.checkChanges()
            // isUserTurn: last message is a plain-text user message (not tool results wrapped as User)
            isUserTurn = stateForTurn.messages.lastOption.exists(m =>
              m.role == MessageRole.User && m.content.isLeft
            )
            reminders <- SystemReminders.collectAll(
              resources.reminderStateRef,
              stateForTurn.latestUsage,
              agentDef.contextWindow,
              fileChangesOpt,
              isUserTurn
            )
            remindersText = SystemReminder.renderAll(reminders)
            // Per-turn dynamic context: only reminders (env info is now in system prompt)
            dynamicMsg =
              if remindersText.nonEmpty then List(Message(MessageRole.User, Left(remindersText)))
              else Nil
            // --- LLM call ---
            thinkingOpt <- resources.runtimePrefs.getThinking
            languageOpt <- resources.runtimePrefs.getLanguage
            systemStable = baseSystemStable + languageOpt
              .map(l => s"\n\n# Language\nAlways respond in $l.")
              .getOrElse("")
            request = LlmRequest(
              messages = stateForTurn.messages ++ dynamicMsg,
              sessionId = stateForTurn.sessionId.getOrElse(ctx.self.path.name),
              agentId = agentDef.name,
              tools = tools,
              maxTokens = Some(agentDef.maxTokens),
              thinking = thinkingOpt.map(nebflow.service.ThinkingConfig.toLlmJson),
              systemStable = Some(systemStable)
            )
            result <- resources.llm
              .sendStream(request, onAttempt = Some(onAttemptCb))
              .through(streamEmitter(stateForTurn.wsSend, ctx, isSubagent, sessionIdOpt))
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
              IO(ctx.self ! LlmFailed(e, replyTo, turnId)) *> IO.never.start
          }
          resources.dispatcher.unsafeRunAndForget(
            startIo.flatMap(fiber => IO(ctx.self ! StreamFiberStarted(fiber)))
          )
          processing(agentDef, resources, depth, parentRef, stateForTurn, stash, ctx)
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
    val allowedTools = buildAllowedToolSet(agentDef, depth, parentRef.isDefined)
    val (filteredCalls, droppedCalls) = result.toolCalls.partition(tc => allowedTools.contains(tc.name))
    if droppedCalls.nonEmpty then
      val dropped = droppedCalls.map(_.name).distinct.mkString(", ")
      NebflowLogger.forName("nebflow.agent").warn(s"Tool calls filtered (not in allowed set): $dropped")

    val nextTurnIdx = state.turnIdx + 1

    // Track deferreds created for Permission so they can be saved to state
    // (Using Ref instead of var to avoid race condition in concurrent traverse)
    val permissionDeferredRef = cats.effect.Ref.unsafe[IO, Option[cats.effect.Deferred[IO, Boolean]]](None)

    val isSubagent = depth > 0
    val sessionIdOpt = state.sessionId

    // Separate ContextManage from real tool calls — it triggers TriggerCompaction
    val (contextManageCalls, realToolCalls) = filteredCalls.partition(_.name == "ContextManage")

    // Build ToolContext with full agent-scoped context
    val toolCtx = ToolContext(
      projectRoot = resources.projectRoot.toString,
      llm = Some(resources.llm),
      sessionStore = Some(resources.sessionStore),
      agentActorRef = Some(ctx.self),
      contextWindow = agentDef.contextWindow,
      sessionId = state.sessionId,
      sessionName = state.sessionName,
      taskStore = Some(resources.taskStore),
      wsSend = Some(state.wsSend),
      readTracker = state.readTracker,
      parentRef = parentRef,
      depth = depth,
      agentDef = Some(agentDef),
      agentLibrary = Some(resources.agentLibrary),
      askSemaphore = Some(resources.askSemaphore),
      pekkoScheduler = Some(ctx.system.scheduler),
      fileLockManager = Some(resources.fileLockManager),
      fileChangeTracker = Some(resources.fileChangeTracker),
      inputTokens = state.latestUsage.map(_.inputTokens)
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
          (ToolRisk.classify(call.name) match
            case ToolRisk.Safe =>
              executeTool(call, toolCtx)
            case ToolRisk.NeedsApproval =>
              resources.runtimePrefs.shouldApprove(call.name).flatMap {
                case ApprovalDecision.Approved =>
                  executeTool(call, toolCtx)
                case ApprovalDecision.Blocked(reason) =>
                  IO.pure(
                    ToolExecResult(
                      NebflowError.toUserMessage(NebflowError.ToolDenied(call.name, reason)),
                      isError = true
                    )
                  )
                case ApprovalDecision.NeedsUserApproval =>
                  permissionDeferredRef.get.flatMap {
                    case Some(_) =>
                      IO.pure(ToolExecResult("Another permission request is already pending", isError = true))
                    case None =>
                      val deferred = cats.effect.Deferred.unsafe[IO, Boolean]
                      permissionDeferredRef.set(Some(deferred)) *>
                        (IO {
                          val summary = nebflow.core.summarizeToolCall(call)
                          Json.obj(
                            "type" -> "askPermission".asJson,
                            "sessionId" -> state.sessionId.asJson,
                            "toolName" -> call.name.asJson,
                            "summary" -> summary.asJson,
                            "input" -> call.input.asJson
                          )
                        }).flatMap { permJson =>
                          for
                            _ <- state.wsSend(permJson)
                            approved <- deferred.get
                            result <-
                              if approved then executeTool(call, toolCtx)
                              else IO.pure(ToolExecResult("Permission denied by user", isError = true))
                          yield result
                        }
                  }
              }
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
                emitStreamIO(
                  state.wsSend,
                  ctx,
                  AgentStreamEvent.ToolEnd(
                    nebflow.core.summarizeToolCall(call),
                    summary,
                    r.content,
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
        // ContextManage: send TriggerCompaction and add synthetic results
        contextManageCalls.foreach { call =>
          val mode = call.input("mode").flatMap(_.asString).getOrElse("full")
          ctx.self ! AgentCommand.TriggerCompaction(mode, None)
        }
        val contextManageResults = contextManageCalls.map { call =>
          val mode = call.input("mode").flatMap(_.asString).getOrElse("full")
          (call, ToolExecResult(s"[ContextManage] Triggered $mode compaction", isError = false))
        }
        val allResults = freshResults ++ contextManageResults
        // Include filtered-out tool calls as errors so the assistant message stays consistent
        val droppedResults = droppedCalls.map { call =>
          (call, ToolExecResult(s"Tool not available: ${call.name}", isError = true))
        }

        // Track per-file write count since last read
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
        if readFiles.nonEmpty || writtenFiles.nonEmpty then
          resources.dispatcher.unsafeRunAndForget(
            resources.reminderStateRef
              .update { rs =>
                // Reset counter for read files, increment for written files
                val cleared = rs.writesSinceLastRead -- readFiles
                val updated = writtenFiles.foldLeft(cleared) { (m, f) =>
                  m.updated(f, m.getOrElse(f, 0) + 1)
                }
                rs.copy(writesSinceLastRead = updated)
              }
              .handleErrorWith(_ => IO.unit)
          )

        IO(
          ctx.self ! ToolsComplete(
            allResults ++ droppedResults,
            result.text,
            replyTo,
            None,
            result.thinking
          )
        )
      }

    resources.dispatcher.unsafeRunAndForget(
      io.handleErrorWith { e =>
        IO(NebflowLogger.forName("nebflow.agent").warn(s"pipeToolExecutions failed: ${e.getMessage}")) *>
          IO(ctx.self ! LlmFailed(new RuntimeException(s"Tool execution pipeline failed: ${e.getMessage}"), replyTo, state.currentTurnId))
      }
    )

    val permDeferredOpt = resources.dispatcher.unsafeRunSync(permissionDeferredRef.get)
    val updatedInteraction = permDeferredOpt.orElse(state.pendingPermission) match
      case None => None
      case d => Some(InteractionState(None, d))

    val updatedState = state.copy(
      execution = state.execution.copy(turnIdx = nextTurnIdx, interaction = updatedInteraction)
    )
    processing(agentDef, resources, depth, parentRef, updatedState, stash, ctx)
  end pipeToolExecutions

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
        IO.delay(System.nanoTime()).flatMap { start =>
          logger.debug(s"$logCtx Executing tool: $summary") *>
            tool
              .call(call.input, ctx)
              .map {
                case Left(err) => ToolExecResult(err.message, isError = true)
                case Right(result) =>
                  ToolResultGuard.guard(result, call.name, ctx) match
                    case ToolResultGuard.Ok(content) => ToolExecResult(content)
                    case ToolResultGuard.Rejected(msg) =>
                      logger.warn(s"$logCtx Tool $summary result rejected: $msg")
                      ToolExecResult(msg, isError = true)
              }
              .handleErrorWith {
                case _: UserAbort => IO.raiseError(new UserAbort())
                case e => IO.pure(ToolExecResult(s"Tool execution error: ${e.getMessage}", isError = true))
              }
              .flatTap { result =>
                val elapsed = (System.nanoTime() - start) / 1_000_000
                if result.isError then logger.warn(s"$logCtx Tool $summary failed (${elapsed}ms): ${result.content.take(100)}")
                else logger.info(s"$logCtx Tool $summary OK (${elapsed}ms)" + (if result.truncated then " [truncated]" else ""))
              }
        }
      case None =>
        IO.pure(ToolExecResult(s"No such tool available: ${call.name}", isError = true))

  // ============================================================
  // Tool list builder — uses ToolRegistry, no inline schema construction
  // ============================================================

  /** Build the set of allowed tool names for filtering tool call results. */
  protected def buildAllowedToolSet(agentDef: AgentDef, depth: Int, hasParent: Boolean): Set[String] =
    val base = agentDef.tools match
      case Nil => Set.empty[String]
      case List("*") => ToolRegistry.ALL_TOOLS.map(_.name).toSet
      case names => names.toSet
    val always = ToolRegistry.AlwaysAvailable
    val subagentTools = if agentDef.subagents.nonEmpty then ToolRegistry.SubagentTools else Set.empty[String]
    val parentTools = if hasParent then ToolRegistry.ParentTools else Set.empty[String]
    base ++ always ++ subagentTools ++ parentTools

  protected def buildToolList(agentDef: AgentDef, depth: Int, hasParent: Boolean): Option[List[ToolDefinition]] =
    val allowedSet = buildAllowedToolSet(agentDef, depth, hasParent)
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
    val usage = chunks.collectFirst { case StreamChunk.Done(_, u, _) => u }.flatten
    val stopReason = chunks.collectFirst { case StreamChunk.Done(sr, _, _) => sr }.flatten
    val model = chunks.collectFirst { case StreamChunk.Done(_, _, Some(meta)) => meta.model }
    ConsumeResult(text, toolCalls, Nil, stopReason, usage, Option.when(thinking.nonEmpty)(thinking), model)

  // ============================================================
  // Prompt / tool helpers
  // ============================================================

  protected def buildSystemPrompt(agentDef: AgentDef, resources: SharedResources): String =
    if agentDef.systemPrompt.nonEmpty then agentDef.systemPrompt
    else Repl.loadSystemPrompt() + "\n\n" + Repl.buildEnvInfo(resources.projectRoot.toString)

  protected def summarizeToolResult(call: ToolCall, result: String): String =
    nebflow.core.summarizeToolResult(call, result)

end AgentCore
