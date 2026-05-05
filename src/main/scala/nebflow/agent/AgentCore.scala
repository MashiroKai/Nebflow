package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.agent.AgentCommand.*
import nebflow.core.*
import nebflow.core.compact.*
import nebflow.core.tools.{ToolContext, ToolRegistry}
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
  protected val MaxTurns = 200
  protected val MaxRecentToolCalls = 20
  protected val DuplicateLookbackTurns = 5
  // Direct SLF4J logger — avoids the IO-returning NebflowLogger methods that were
  // being silently discarded when called from Unit-returning actor methods.
  private val lifecycleLog = org.slf4j.LoggerFactory.getLogger("nebflow.agent.lifecycle")

  protected def stageConstraints(stage: AdaptiveStage): Set[String] = stage match
    case AdaptiveStage.Normal => Set.empty
    case AdaptiveStage.Cautious => Set.empty
    case AdaptiveStage.Conservative => Set("Write", "Edit", "Bash")
    case AdaptiveStage.Paused => Set("*")

  protected def logAgentEvent(
    ctx: ActorContext[?],
    agentDef: AgentDef,
    depth: Int,
    sessionId: Option[String],
    event: String,
    detail: String = ""
  ): Unit =
    val sid = sessionId.getOrElse("-")
    val pid = ctx.self.path.parent.name
    val msg = s"agent=${ctx.self.path.name} name=${agentDef.name} depth=$depth session=$sid parent=$pid event=$event"
    lifecycleLog.info(if detail.nonEmpty then s"$msg detail=$detail" else msg)
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

  protected def evaluateProgress(
    freshCalls: List[ToolCall],
    state: AgentState
  ): Boolean =
    val declaredWait = freshCalls.exists(_.name == "declareWait")
    if declaredWait then true
    else freshCalls.nonEmpty

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
      Boolean
    ) => Behavior[AgentCommand]
  ): Behavior[AgentCommand] =
    if state.turnIdx >= MaxTurns then
      val warningMsg = s"[Turn limit reached] Agent has exceeded the maximum of $MaxTurns turns. " +
        "Stopping now. Please synthesize a final answer based on the information gathered so far."
      logAgentEvent(ctx, agentDef, depth, state.sessionId, "turn-limit", s"turnIdx=${state.turnIdx}")
      finishTurn(
        agentDef,
        resources,
        depth,
        parentRef,
        state.withMessages(state.messages :+ Message(MessageRole.User, Left(warningMsg))),
        stash,
        ctx,
        "I apologize, but I've reached the maximum number of turns for this session. " +
          "Here's what I was able to determine from the work done so far: [synthesize findings]",
        replyTo,
        None,
        false
      )
    else
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

          // Cache-optimal layout for Anthropic prompt caching (prefix order: system → tools → messages):
          //   system[0]: stable system prompt  ← cache breakpoint (rarely changes)
          //   tools:     tool definitions       ← cache breakpoint (stable, no dynamic content between)
          //   messages:  [dynamic context] + actual conversation (dynamic context not persisted)
          val systemStable =
            if agentDef.systemPrompt.nonEmpty then agentDef.systemPrompt
            else Repl.loadSystemPrompt()
          val tools = buildToolList(agentDef, depth, parentRef.isDefined)

          val isSubagent = depth > 0
          val sessionIdOpt = state.sessionId

          val onAttemptCb: FallbackAttempt => IO[Unit] = attempt =>
            val msg = attempt.message.getOrElse(s"${attempt.providerId}/${attempt.model} failed, retrying...")
            emitStreamIO(state.wsSend, ctx, AgentStreamEvent.RetryStatus(msg), isSubagent, sessionIdOpt)

          val io = for
            // --- reminders (previously sync, now async) ---
            fileChangesOpt <- resources.fileChangeTracker.checkChanges()
            currentPolicy <- resources.runtimePrefs.getPolicy
            userInput = state.messages.reverseIterator
              .collectFirst {
                case m if m.role == MessageRole.User => m.textContent
              }
              .getOrElse("")
            skillMatchOpt <- resources.skillDiscovery match
              case Some(sd) => sd.findRelevantSkill(userInput)
              case None => IO.pure(None)
            reminders <- SystemReminders.collectAll(
              resources.reminderStateRef,
              state.latestUsage,
              agentDef.contextWindow,
              fileChangesOpt,
              currentPolicy,
              skillMatchOpt
            )
            remindersText = SystemReminder.renderAll(reminders)
            // Build dynamic context: env info + per-turn reminders (appended to messages, not prepended)
            envInfo =
              if agentDef.systemPrompt.nonEmpty then ""
              else Repl.buildEnvInfo(resources.projectRoot.toString)
            dynamicParts = List(envInfo, remindersText).filter(_.nonEmpty)
            // Append dynamic context as the last user message — preserves messages prefix for caching.
            // Not stored in state.messages, only in the API request.
            dynamicMsg =
              if dynamicParts.nonEmpty then List(Message(MessageRole.User, Left(dynamicParts.mkString("\n\n"))))
              else Nil
            // --- LLM call ---
            thinkingOpt <- resources.runtimePrefs.getThinking
            request = LlmRequest(
              messages = state.messages ++ dynamicMsg,
              sessionId = state.sessionId.getOrElse(ctx.self.path.name),
              agentId = agentDef.name,
              tools = tools,
              maxTokens = Some(agentDef.maxTokens),
              thinking = thinkingOpt.map(nebflow.service.ThinkingConfig.toLlmJson),
              systemStable = Some(systemStable)
            )
            result <- resources.llm
              .sendStream(request, onAttempt = Some(onAttemptCb))
              .through(streamEmitter(state.wsSend, ctx, isSubagent, sessionIdOpt))
              .compile
              .toList
              .map(aggregateChunks)
              .attempt
            _ <- result match
              case Right(r) => IO(ctx.self ! LlmComplete(r, replyTo))
              case Left(e) => IO(ctx.self ! LlmFailed(e, replyTo))
          yield ()

          resources.dispatcher.unsafeRunAndForget(
            io.handleErrorWith { e =>
              IO(NebflowLogger.forName("nebflow.agent").warn(s"pipeLlmCall failed: ${e.getMessage}")) *>
                IO(ctx.self ! LlmFailed(e, replyTo))
            }
          )
          processing(agentDef, resources, depth, parentRef, state, stash, ctx)
      end match
    end if
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
      Boolean
    ) => Behavior[AgentCommand]
  ): Behavior[AgentCommand] =
    // Build the full allowed tool set: whitelist + always-available + context-dependent
    val allowedTools = buildAllowedToolSet(agentDef, depth, parentRef.isDefined)
    val filteredCalls = result.toolCalls.filter(tc => allowedTools.contains(tc.name))

    // Stage-based tool blocking
    val blockedTools = stageConstraints(state.stage)
    val (stageBlocked, stageAllowed) = filteredCalls.partition { c =>
      blockedTools.contains("*") || blockedTools.contains(c.name)
    }
    val stageBlockedResults = stageBlocked.map { call =>
      val warning = state.stage match
        case AdaptiveStage.Conservative =>
          s"[Stage: Conservative] ${call.name} is disabled. Synthesize your findings without further modifications."
        case AdaptiveStage.Paused =>
          "[Stage: Paused] All tools are disabled. Synthesize what you know and respond with your current best answer."
        case _ => s"[Stage: ${state.stage}] ${call.name} is temporarily disabled."
      (call, ToolExecResult(warning, isError = false))
    }

    // Deduplication: detect repetitive tool calls within recent turns
    val nextTurnIdx = state.turnIdx + 1
    val (freshCalls, duplicateResults) = stageAllowed.foldLeft(
      (List.empty[ToolCall], List.empty[(ToolCall, ToolExecResult)])
    ) { case ((fresh, dups), call) =>
      val inputHash = ToolCallRecord.canonicalHash(call.input)
      val dupRecord = state.recentToolCalls.find { r =>
        r.name == call.name && r.inputHash == inputHash && (nextTurnIdx - r.turnIdx) <= DuplicateLookbackTurns
      }
      val isDup = dupRecord.isDefined
      val readSignature =
        if call.name == "Read" then
          for
            path <- call.input("file_path").flatMap(_.asString)
            offset = call.input("offset").flatMap(_.asNumber).flatMap(_.toInt).getOrElse(0)
            limit = call.input("limit").flatMap(_.asNumber).flatMap(_.toInt)
          yield limit match
            case Some(l) => s"$path@$offset:$l"
            case None => s"$path@full"
        else None
      val isSameFileReRead = readSignature.exists(state.recentFilesRead.contains)
      if isDup || isSameFileReRead then
        val lastTurn = dupRecord.map(_.turnIdx).getOrElse(0)
        val (warningText, reasonDetail) = if isSameFileReRead then
          val sig = readSignature.getOrElse("")
          (
            s"[Loop detection] You already read this file at the same position earlier. Do not read it again unless the user explicitly asks. Synthesize what you know.",
            s"reason=same-file sig=$sig"
          )
        else
          (
            s"[Loop detection] You already called ${call.name} with the same parameters at turn $lastTurn and received the result. " +
              "Do not repeat the same call. Synthesize your findings instead.",
            s"reason=duplicate tool=${call.name} lastTurn=$lastTurn"
          )
        logAgentEvent(ctx, agentDef, depth, state.sessionId, "tool-dedup", s"$reasonDetail turn=$nextTurnIdx")
        val warning = ToolExecResult(warningText, isError = false)
        (fresh, dups :+ (call, warning))
      else (fresh :+ call, dups)
    }

    // Track deferreds created for Permission so they can be saved to state
    // (Using Ref instead of var to avoid race condition in concurrent traverse)
    val permissionDeferredRef = cats.effect.Ref.unsafe[IO, Option[cats.effect.Deferred[IO, Boolean]]](None)

    val isSubagent = depth > 0
    val sessionIdOpt = state.sessionId

    val (limitedFreshCalls, cappedCalls) = state.stage match
      case AdaptiveStage.Cautious if freshCalls.size > 3 =>
        logAgentEvent(
          ctx,
          agentDef,
          depth,
          state.sessionId,
          "tool-limit",
          s"capped parallel calls from ${freshCalls.size} to 3"
        )
        (freshCalls.take(3), freshCalls.drop(3))
      case _ => (freshCalls, List.empty[ToolCall])

    val cappedResults = cappedCalls.map { call =>
      (
        call,
        ToolExecResult(
          s"[Stage: Cautious] Parallel call limit reached (max 3). ${call.name} was not executed. Prioritize your most important calls.",
          isError = false
        )
      )
    }

    // Separate ContextManage from real tool calls — it triggers TriggerCompaction
    val (contextManageCalls, realToolCalls) = limitedFreshCalls.partition(_.name == "ContextManage")

    // Progress tracking
    val hadProgress =
      if limitedFreshCalls.isEmpty && duplicateResults.isEmpty && stageBlocked.isEmpty then true
      else evaluateProgress(limitedFreshCalls, state)
    val newStagnationCount = if hadProgress then 0 else state.stagnationCount + 1
    val newProgressStreak = if hadProgress then state.progressStreak + 1 else 0
    val targetStage = newStagnationCount match
      case n if n >= 9 => AdaptiveStage.Paused
      case n if n >= 6 => AdaptiveStage.Conservative
      case n if n >= 3 => AdaptiveStage.Cautious
      case _ => AdaptiveStage.Normal
    val newStage =
      if targetStage.ordinal > state.stage.ordinal then targetStage
      else if newProgressStreak >= 2 && targetStage.ordinal < state.stage.ordinal then
        AdaptiveStage.fromOrdinal(state.stage.ordinal - 1)
      else state.stage

    // Build ToolContext with full agent-scoped context
    val toolCtx = ToolContext(
      projectRoot = resources.projectRoot.toString,
      llm = Some(resources.llm),
      sessionStore = Some(resources.sessionStore),
      agentActorRef = Some(ctx.self),
      contextWindow = agentDef.contextWindow,
      sessionId = state.sessionId,
      taskStore = Some(resources.taskStore),
      wsSend = Some(state.wsSend),
      readTracker = state.readTracker,
      parentRef = parentRef,
      depth = depth,
      agentDef = Some(agentDef),
      agentLibrary = Some(resources.agentLibrary),
      askSemaphore = Some(resources.askSemaphore),
      pekkoScheduler = Some(ctx.system.scheduler),
      fileLockManager = Some(resources.fileLockManager)
    )

    val io = realToolCalls
      .traverse { call =>
        emitStreamIO(
          state.wsSend,
          ctx,
          AgentStreamEvent.ToolStart(nebflow.core.summarizeToolCall(call)),
          isSubagent,
          sessionIdOpt
        ) *>
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
              val summary = summarizeToolResult(call, r.content)
              emitStreamIO(
                state.wsSend,
                ctx,
                AgentStreamEvent.ToolEnd(
                  nebflow.core.summarizeToolCall(call),
                  summary,
                  r.content,
                  r.isError,
                  input = Some(call.input)
                ),
                isSubagent,
                sessionIdOpt
              )
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
        val allResults =
          freshResults ++ contextManageResults ++ stageBlockedResults ++ duplicateResults ++ cappedResults

        val hasRead = freshResults.exists { case (call, result) => call.name == "Read" && !result.isError }
        val writeCount = freshResults.count { case (call, result) =>
          Set("Write", "Edit").contains(call.name) && !result.isError
        }
        if hasRead || writeCount > 0 then
          resources.dispatcher.unsafeRunAndForget(
            resources.reminderStateRef
              .update { rs =>
                if hasRead then rs.copy(writesWithoutRead = 0)
                else rs.copy(writesWithoutRead = rs.writesWithoutRead + writeCount)
              }
              .handleErrorWith(_ => IO.unit)
          )

        if newStage != state.stage || newStagnationCount != state.stagnationCount then
          emitStream(
            resources.dispatcher,
            state.wsSend,
            ctx,
            AgentStreamEvent.ProgressUpdate(nextTurnIdx, newStagnationCount, newStage.toString),
            isSubagent = depth > 0,
            state.sessionId
          )
        if newStage == AdaptiveStage.Paused && state.stage != AdaptiveStage.Paused then
          emitStream(
            resources.dispatcher,
            state.wsSend,
            ctx,
            AgentStreamEvent.Paused("Agent has paused after detecting stagnation. Providing best-effort summary..."),
            isSubagent = depth > 0,
            state.sessionId
          )

        IO(
          ctx.self ! ToolsComplete(
            allResults,
            result.text,
            replyTo,
            None,
            result.thinking,
            Some(newStagnationCount),
            Some(newStage),
            Some(newProgressStreak)
          )
        )
      }

    resources.dispatcher.unsafeRunAndForget(
      io.handleErrorWith { e =>
        IO(NebflowLogger.forName("nebflow.agent").warn(s"pipeToolExecutions failed: ${e.getMessage}")) *>
          IO(ctx.self ! LlmFailed(new RuntimeException(s"Tool execution pipeline failed: ${e.getMessage}"), replyTo))
      }
    )

    val newRecords = filteredCalls.map { call =>
      ToolCallRecord(call.name, ToolCallRecord.canonicalHash(call.input), nextTurnIdx)
    }
    val prunedRecent = (state.recentToolCalls ++ newRecords).sortBy(-_.turnIdx).take(MaxRecentToolCalls)

    val newRecentFilesRead = freshCalls
      .filter(_.name == "Read")
      .flatMap { call =>
        for
          path <- call.input("file_path").flatMap(_.asString)
          offset = call.input("offset").flatMap(_.asNumber).flatMap(_.toInt).getOrElse(0)
          limit = call.input("limit").flatMap(_.asNumber).flatMap(_.toInt)
        yield limit match
          case Some(l) => s"$path@$offset:$l"
          case None => s"$path@full"
      }
      .toSet ++ state.recentFilesRead

    val permDeferredOpt = resources.dispatcher.unsafeRunSync(permissionDeferredRef.get)
    val updatedInteraction = permDeferredOpt.orElse(state.pendingPermission) match
      case None => None
      case d => Some(InteractionState(None, d))

    val updatedState = state.copy(
      execution = state.execution.copy(turnIdx = nextTurnIdx, interaction = updatedInteraction),
      safety = SafetyContext(
        recentToolCalls = prunedRecent,
        recentFilesRead = newRecentFilesRead,
        hasInjectedAntiLoop = state.safety.hasInjectedAntiLoop,
        stagnationCount = newStagnationCount,
        stage = newStage,
        progressStreak = newProgressStreak
      )
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
        IO.delay(System.nanoTime()).flatMap { start =>
          logger.debug(s"Executing tool: $summary") *>
            tool
              .call(call.input, ctx)
              .map {
                case Left(err) => ToolExecResult(err.message, isError = true)
                case Right(result) => ToolExecResult(result)
              }
              .handleErrorWith {
                case _: UserAbort => IO.raiseError(new UserAbort())
                case e => IO.pure(ToolExecResult(s"Tool execution error: ${e.getMessage}", isError = true))
              }
              .flatTap { result =>
                val elapsed = (System.nanoTime() - start) / 1_000_000
                if result.isError then logger.warn(s"Tool $summary failed (${elapsed}ms): ${result.content.take(100)}")
                else logger.info(s"Tool $summary OK (${elapsed}ms)")
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
    dispatcher.unsafeRunAndForget(
      wsSend(event.toJson(ctx.self.path.name, isSubagent, sessionId))
        .handleErrorWith(_ => IO.unit)
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
        case StreamChunk.ToolCallChunk(tc) =>
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
    ConsumeResult(text, toolCalls, Nil, stopReason, usage, Option.when(thinking.nonEmpty)(thinking))

  // ============================================================
  // Prompt / tool helpers
  // ============================================================

  protected def buildSystemPrompt(agentDef: AgentDef, resources: SharedResources): String =
    if agentDef.systemPrompt.nonEmpty then agentDef.systemPrompt
    else Repl.loadSystemPrompt() + "\n\n" + Repl.buildEnvInfo(resources.projectRoot.toString)

  protected def summarizeToolResult(call: ToolCall, result: String): String =
    nebflow.core.summarizeToolResult(call, result)

end AgentCore
