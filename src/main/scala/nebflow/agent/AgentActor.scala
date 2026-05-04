package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.agent.AgentCommand.*
import nebflow.core.*
import nebflow.core.compact.*
import nebflow.core.tools.ToolRegistry
import nebflow.shared.*
import nebflow.shared.given
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}
import org.apache.pekko.actor.typed.{ActorRef, Behavior, SupervisorStrategy}

/**
 * Core agent actor — the heart of the multi-agent system.
 *
 * Message flow:
 *   UserInput -> startLlm -> LlmComplete -> [executeTools -> ToolsComplete -> startLlm -> ...] -> idle
 */
object AgentActor:

  private val MaxStashCapacity = 100
  private val MaxDepth = 5
  private val MaxRecentToolCalls = 20
  private val DuplicateLookbackTurns = 5
  private val MaxTurns = 200
  private val SyntheticTools = Set(
    "finish", "declareWait", "delegate", "report", "ask_parent",
    "ContextManage", "AskUserQuestion"
  )
  private val lifecycleLogger = NebflowLogger.forName("nebflow.agent.lifecycle")

  private def stageConstraints(stage: AdaptiveStage): Set[String] = stage match
    case AdaptiveStage.Normal       => Set.empty
    case AdaptiveStage.Cautious     => Set.empty
    case AdaptiveStage.Conservative => Set("Write", "Edit", "Bash")
    case AdaptiveStage.Paused       => Set("*")

  private def logAgentEvent(
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
    lifecycleLogger.info(if detail.nonEmpty then s"$msg detail=$detail" else msg)
  end logAgentEvent

  def apply(
    agentDef: AgentDef,
    resources: SharedResources,
    wsSend: io.circe.Json => IO[Unit],
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]] = None,
    sessionId: Option[String] = None,
    initialMessages: List[Message] = Nil,
    readTracker: Option[nebflow.core.tools.ReadTracker] = None
  ): Behavior[AgentCommand] =
    Behaviors
      .supervise(
        Behaviors.withStash[AgentCommand](MaxStashCapacity) { stash =>
          Behaviors.setup[AgentCommand] { context =>
            logAgentEvent(
              context,
              agentDef,
              depth,
              sessionId,
              "spawn",
              s"parent=${parentRef.map(_.path.name).getOrElse("-")} msgs=${initialMessages.size}"
            )
            idle(
              agentDef,
              resources,
              depth,
              parentRef,
              AgentState(
                messages = initialMessages,
                status = AgentStatus.Idle,
                depth = depth,
                subagents = Map.empty,
                activeStreamFiber = None,
                sessionId = sessionId,
                pendingCompaction = None,
                latestUsage = None,
                pendingAskUser = None,
                pendingPermission = None,
                wsSend = wsSend,
                readTracker = readTracker
              ),
              stash,
              context
            )
          }
        }
      )
      .onFailure[Exception](SupervisorStrategy.restart.withLimit(2, java.time.Duration.ofSeconds(30)))

  // ============================================================
  // Idle state
  // ============================================================

  private def idle(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    stash: StashBuffer[AgentCommand],
    ctx: ActorContext[AgentCommand]
  ): Behavior[AgentCommand] =
    Behaviors.receiveMessage:
      case AgentCommand.UserInput(text, replyTo) =>
        logAgentEvent(
          ctx,
          agentDef,
          depth,
          state.sessionId,
          "start",
          s"text=${text.take(40)} msgs=${state.messages.size}"
        )
        val userMsg = Message(MessageRole.User, Left(text))
        val newMessages = state.messages :+ userMsg
        pipeLlmCall(agentDef, resources, depth, parentRef, state.copy(messages = newMessages), stash, ctx, replyTo)

      case AgentCommand.Stop(_) =>
        logAgentEvent(ctx, agentDef, depth, state.sessionId, "stop", "reason=user")
        state.activeStreamFiber.foreach(f => resources.dispatcher.unsafeRunAndForget(f.cancel))
        state.subagents.values.foreach(ctx.stop)
        Behaviors.stopped

      case msg =>
        stash.stash(msg)
        Behaviors.same

  // ============================================================
  // Processing state — LLM or tools in flight
  // ============================================================

  private def processing(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    stash: StashBuffer[AgentCommand],
    ctx: ActorContext[AgentCommand]
  ): Behavior[AgentCommand] =
    Behaviors.receiveMessage:

      // --- LLM completed ---
      case LlmComplete(result, replyTo) =>
        val updatedState = state.copy(latestUsage = result.usage.orElse(state.latestUsage))
        if result.toolCalls.isEmpty then
          finishTurn(
            agentDef,
            resources,
            depth,
            parentRef,
            updatedState,
            stash,
            ctx,
            result.text,
            replyTo,
            result.thinking
          )
        else pipeToolExecutions(agentDef, resources, depth, parentRef, updatedState, stash, ctx, result, replyTo)

      case LlmFailed(error, replyTo) =>
        logAgentEvent(ctx, agentDef, depth, state.sessionId, "llm-fail", s"err=${error.getMessage.take(60)}")
        val agentError =
          AgentError(ctx.self.path.name, agentDef.name, depth, AgentErrorType.LlmFailed, error.getMessage)
        parentRef match
          case Some(p) => p ! AgentCommand.DelegateResult(ctx.self.path.name, Left(agentError))
          case None => emitStream(resources.dispatcher, state.wsSend, ctx, AgentStreamEvent.Done, isSubagent = false, state.sessionId)
        replyTo.foreach(_ ! AgentEvent.Failed(state.sessionId.getOrElse(""), agentError))
        stash.unstashAll(
          idle(
            agentDef,
            resources,
            depth,
            parentRef,
            state.copy(status = AgentStatus.Error(error.getMessage)),
            stash,
            ctx
          )
        )

      // --- Tools completed ---
      case tc: ToolsComplete =>
        val toolCalls = tc.results.map((call, _) => call)
        val assistantBlocks = scala.collection.mutable.ListBuffer.empty[ContentBlock]
        tc.thinking.foreach(t => assistantBlocks += ContentBlock.Thinking(t))
        if tc.originalText.nonEmpty then assistantBlocks += ContentBlock.Text(tc.originalText)
        toolCalls.foreach(c => assistantBlocks += ContentBlock.ToolUse(c.id, c.name, c.input))
        val assistantMsg = Message(MessageRole.Assistant, Right(assistantBlocks.toList))
        val resultBlocks = tc.results.map { (call, r) =>
          ContentBlock.ToolResult(call.id, r.content, Some(r.isError))
        }
        val resultMsg = Message(MessageRole.User, Right(resultBlocks))
        val baseMessages = tc.compactedMessages.getOrElse(state.messages)
        val newMessages = baseMessages ++ List(assistantMsg, resultMsg)

        // Persist intermediate state if sessionId is present
        val updatedState = state.copy(
          messages = newMessages,
          pendingAskUser = None,
          pendingPermission = None,
          stagnationCount = tc.nextStagnationCount.getOrElse(state.stagnationCount),
          stage = tc.nextStage.getOrElse(state.stage),
          progressStreak = tc.nextProgressStreak.getOrElse(state.progressStreak)
        )
        resources.dispatcher.unsafeRunAndForget(persistIfSession(resources, updatedState))

        val didFinish = tc.results.exists { case (call, _) => call.name == "finish" }
        if didFinish then
          val finishAnswer = tc.results.collectFirst {
            case (call, r) if call.name == "finish" => r.content.stripPrefix("[finish] ")
          }.getOrElse("")
          finishTurn(agentDef, resources, depth, parentRef, updatedState, stash, ctx, finishAnswer, tc.replyTo, tc.thinking)
        else
          // Loop: start next LLM call with updated messages
          pipeLlmCall(agentDef, resources, depth, parentRef, updatedState, stash, ctx, tc.replyTo)

      // --- Subagent stream forwarding ---
      case AgentCommand.SubagentStreamEvent(subId, event) =>
        emitStream(resources.dispatcher, state.wsSend, ctx, event)
        Behaviors.same

      // --- Subagent returned result ---
      case AgentCommand.DelegateResult(subId, result) =>
        state.pendingCompaction match
          case Some(pending) if pending.subagentId == subId =>
            // This is a compaction result from context-manage agent
            val compactedMessages = result match
              case Right(text) =>
                pending.mode match
                  case "micro" => MicroCompact.parseResponse(text, state.messages)
                  case _ => FullCompact.parseResponse(text, state.messages, resources.projectRoot.toString)
              case Left(err) =>
                NebflowLogger.forName("nebflow.agent").warn(s"Compaction agent failed: ${err.message}")
                Left(err.message)

            val newState = state.copy(
              pendingCompaction = None,
              subagents = state.subagents - subId
            )
            // Archive original messages before applying compaction (non-blocking).
            // 即使 parseResponse 失败(Left),原始历史也应被快照,因为这是最需要回滚的高风险场景。
            val snapshotIO = state.sessionId match
              case Some(sid) =>
                resources.historyArchiver.archive(sid, state.messages).map {
                  case Right(path) => Some(path)
                  case Left(_) => None
                }.handleError(_ => None)
              case None => IO.pure(None)
            val snapshotPath = resources.dispatcher.unsafeRunSync(snapshotIO)

            compactedMessages match
              case Right(compacted) =>
                NebflowLogger
                  .forName("nebflow.agent")
                  .info(s"Auto-compact: ${state.messages.size} -> ${compacted.size} messages")
                logAgentEvent(
                  ctx,
                  agentDef,
                  depth,
                  state.sessionId,
                  "compaction-complete",
                  s"subId=$subId mode=${pending.mode} before=${state.messages.size} after=${compacted.size} snapshot=${snapshotPath.getOrElse("-")}"
                )
                resources.dispatcher.unsafeRunAndForget(
                  emitStreamIO(
                    state.wsSend,
                    ctx,
                    AgentStreamEvent.CompactComplete(state.messages.size, compacted.size, snapshotPath),
                    isSubagent = depth > 0,
                    state.sessionId
                  )
                )

                pending.replyDeferred.foreach { d =>
                  resources.dispatcher.unsafeRunAndForget(
                    d.complete(Right(CompactionResult(state.messages.size, compacted.size)))
                  )
                }
                val successState = newState.copy(
                  messages = compacted,
                  compactionFailures = 0
                )
                if pending.replyTo.isDefined then
                  // Auto-compaction: resume LLM call with compacted messages
                  pipeLlmCall(
                    agentDef,
                    resources,
                    depth,
                    parentRef,
                    successState,
                    stash,
                    ctx,
                    pending.replyTo
                  )
                else
                  processing(
                    agentDef,
                    resources,
                    depth,
                    parentRef,
                    successState,
                    stash,
                    ctx
                  )
              case Left(err) =>
                val failures = state.compactionFailures + 1
                val maxFailures = CompactConfig().circuitBreakerMax
                NebflowLogger.forName("nebflow.agent").warn(s"Auto-compact failed: $err (attempt $failures/$maxFailures)")
                logAgentEvent(
                  ctx,
                  agentDef,
                  depth,
                  state.sessionId,
                  "compaction-fail",
                  s"subId=$subId mode=${pending.mode} err=${err.take(60)} failures=$failures"
                )
                resources.dispatcher.unsafeRunAndForget(
                  emitStreamIO(
                    state.wsSend,
                    ctx,
                    AgentStreamEvent.CompactFailed(err, failures, maxFailures),
                    isSubagent = depth > 0,
                    state.sessionId
                  )
                )

                pending.replyDeferred.foreach { d =>
                  resources.dispatcher.unsafeRunAndForget(
                    d.complete(Left(err))
                  )
                }
                val failedState = newState.copy(compactionFailures = failures)
                if failures >= maxFailures then
                  // Circuit breaker open: stop and report failure
                  val agentError = AgentError(
                    ctx.self.path.name,
                    agentDef.name,
                    depth,
                    AgentErrorType.ToolFailed,
                    s"Compaction circuit breaker open after $failures attempts"
                  )
                  parentRef match
                    case Some(p) => p ! AgentCommand.DelegateResult(ctx.self.path.name, Left(agentError))
                    case None =>
                      emitStream(resources.dispatcher, state.wsSend, ctx, AgentStreamEvent.Done, isSubagent = false, state.sessionId)
                  pending.replyTo.foreach(_ ! AgentEvent.Failed(state.sessionId.getOrElse(""), agentError))
                  stash.unstashAll(
                    idle(
                      agentDef,
                      resources,
                      depth,
                      parentRef,
                      failedState.copy(status = AgentStatus.Error(s"Compaction circuit breaker open after $failures attempts")),
                      stash,
                      ctx
                    )
                  )
                else if pending.replyTo.isDefined then
                  // Auto-compaction failed: try LLM with original messages anyway
                  pipeLlmCall(
                    agentDef,
                    resources,
                    depth,
                    parentRef,
                    failedState,
                    stash,
                    ctx,
                    pending.replyTo
                  )
                else processing(agentDef, resources, depth, parentRef, failedState, stash, ctx)
            end match
          case _ =>
            // Regular subagent result
            state.subagents.get(subId).foreach(ctx.unwatch)
            emitStream(resources.dispatcher, state.wsSend, ctx, AgentStreamEvent.AgentEnd(subId))
            val resultText = result match
              case Right(text) => s"[Subagent $subId completed]: $text"
              case Left(err) => s"[Subagent $subId failed]: ${err.message}"
            result match
              case Right(_) =>
                logAgentEvent(ctx, agentDef, depth, state.sessionId, "subagent-complete", s"subId=$subId")
              case Left(err) =>
                logAgentEvent(
                  ctx,
                  agentDef,
                  depth,
                  state.sessionId,
                  "subagent-fail",
                  s"subId=$subId err=${err.message.take(60)}"
                )
            processing(
              agentDef,
              resources,
              depth,
              parentRef,
              state.copy(
                subagents = state.subagents - subId,
                messages = state.messages :+ Message(MessageRole.User, Left(resultText))
              ),
              stash,
              ctx
            )

      // --- User interaction responses ---
      case AgentCommand.UserAnswered(answers) =>
        state.pendingAskUser match
          case Some(deferred) =>
            resources.dispatcher.unsafeRunAndForget(deferred.complete(answers))
            Behaviors.same
          case None => Behaviors.same

      case AgentCommand.PermissionAnswered(approved) =>
        state.pendingPermission match
          case Some(deferred) =>
            resources.dispatcher.unsafeRunAndForget(deferred.complete(approved))
            Behaviors.same
          case None => Behaviors.same

      // --- Interrupt ---
      case AgentCommand.Interrupt() =>
        state.activeStreamFiber.foreach(f => resources.dispatcher.unsafeRunAndForget(f.cancel))
        state.subagents.values.foreach(_ ! AgentCommand.Interrupt())
        state.pendingAskUser.foreach(d => resources.dispatcher.unsafeRunAndForget(d.complete(Nil)))
        state.pendingPermission.foreach(d => resources.dispatcher.unsafeRunAndForget(d.complete(false)))
        emitStream(resources.dispatcher, state.wsSend, ctx, AgentStreamEvent.Done)
        stash.unstashAll(
          idle(
            agentDef,
            resources,
            depth,
            parentRef,
            state.copy(
              status = AgentStatus.Idle,
              activeStreamFiber = None,
              subagents = Map.empty,
              pendingCompaction = None,
              pendingAskUser = None,
              pendingPermission = None
            ),
            stash,
            ctx
          )
        )

      // --- Compaction agent definition loaded (piped from IO) — spawn on actor thread ---
      case AgentCommand.CompactionDefLoaded(defnOpt) =>
        state.pendingCompaction match
          case Some(pending) =>
            defnOpt match
              case Some(subDefBase) =>
                val prompt = pending.mode match
                  case "micro" => CompactPrompts.micro
                  case _ => CompactPrompts.full
                val subDef = subDefBase.copy(systemPrompt = prompt)
                val subId = pending.subagentId
                val cleaned = nebflow.core.compact.CompactUtils.stripImages(state.messages)
                // Cap at 500 messages to avoid OOM when serializing huge histories
                val capped = if cleaned.size > 500 then cleaned.takeRight(500) else cleaned
                val messagesJson = capped.asJson.noSpaces
                logAgentEvent(
                  ctx,
                  agentDef,
                  depth,
                  state.sessionId,
                  "subagent-spawn",
                  s"subId=$subId subName=${subDef.name} mode=${pending.mode} msgs=${state.messages.size}"
                )
                val subActor = ctx.spawn(
                  AgentActor(subDef, resources, state.wsSend, depth + 1, Some(ctx.self), readTracker = state.readTracker),
                  subId
                )
                ctx.watchWith(
                  subActor,
                  AgentCommand.DelegateResult(
                    subId,
                    Left(
                      AgentError(
                        subId,
                        subDef.name,
                        depth + 1,
                        AgentErrorType.Interrupted,
                        "Actor terminated unexpectedly"
                      )
                    )
                  )
                )
                subActor ! AgentCommand.UserInput(messagesJson, None)
                emitStream(resources.dispatcher, state.wsSend, ctx, AgentStreamEvent.AgentStart(subDef.name, subDef.description))
                val newState = state.copy(subagents = state.subagents + (subId -> subActor))
                processing(agentDef, resources, depth, parentRef, newState, stash, ctx)
              case None =>
                NebflowLogger.forName("nebflow.agent").warn("context-manage agent not found, skipping compaction")
                pending.replyDeferred.foreach { d =>
                  resources.dispatcher.unsafeRunAndForget(
                    d.complete(Left("context-manage agent not found"))
                  )
                }
                val cleared = state.copy(pendingCompaction = None)
                processing(agentDef, resources, depth, parentRef, cleared, stash, ctx)
          case None =>
            // No pending compaction, ignore
            Behaviors.same

      // --- Manual compaction trigger from ContextManageTool ---
      case AgentCommand.TriggerCompaction(mode, replyDeferred) =>
        if state.pendingCompaction.isDefined then
          replyDeferred.foreach { d =>
            resources.dispatcher.unsafeRunAndForget(d.complete(Left("Compaction already in progress")))
          }
          Behaviors.same
        else if state.compactionFailures >= CompactConfig().circuitBreakerMax then
          val err = s"Compaction circuit breaker open after ${state.compactionFailures} attempts"
          replyDeferred.foreach { d =>
            resources.dispatcher.unsafeRunAndForget(d.complete(Left(err)))
          }
          resources.dispatcher.unsafeRunAndForget(
            emitStreamIO(
              state.wsSend,
              ctx,
              AgentStreamEvent.CompactFailed(err, state.compactionFailures, CompactConfig().circuitBreakerMax),
              isSubagent = depth > 0,
              state.sessionId
            )
          )
          Behaviors.same
        else
          val subId = s"context-manage-${java.util.UUID.randomUUID().toString.take(8)}"
          val io = resources.agentLibrary.get("context-manage").flatMap { defnOpt =>
            IO(ctx.self ! AgentCommand.CompactionDefLoaded(defnOpt))
          }
          resources.dispatcher.unsafeRunAndForget(io)
          val pending = CompactionContext(subId, mode, replyDeferred)
          resources.dispatcher.unsafeRunAndForget(
            emitStreamIO(
              state.wsSend,
              ctx,
              AgentStreamEvent.CompactStart(mode, None, None),
              isSubagent = depth > 0,
              state.sessionId
            )
          )
          processing(
            agentDef,
            resources,
            depth,
            parentRef,
            state.copy(pendingCompaction = Some(pending)),
            stash,
            ctx
          )

      // --- Subagent definition loaded (piped from IO) — spawn on actor thread ---
      case AgentCommand.SubagentDefLoaded(call, agentName, task, defnOpt, subDepth) =>
        defnOpt match
          case Some(subDef) =>
            val subId = s"${agentName}-${java.util.UUID.randomUUID().toString.take(8)}"
            logAgentEvent(
              ctx,
              agentDef,
              depth,
              state.sessionId,
              "subagent-spawn",
              s"subId=$subId subName=${subDef.name} task=${task.take(40)}"
            )
            val subActor = ctx.spawn(
              AgentActor(subDef, resources, state.wsSend, subDepth, Some(ctx.self), readTracker = state.readTracker),
              subId
            )
            ctx.watchWith(
              subActor,
              AgentCommand.DelegateResult(
                subId,
                Left(
                  AgentError(subId, subDef.name, subDepth, AgentErrorType.Interrupted, "Actor terminated unexpectedly")
                )
              )
            )
            subActor ! AgentCommand.UserInput(task, None)
            emitStream(resources.dispatcher, state.wsSend, ctx, AgentStreamEvent.AgentStart(subDef.name, subDef.description))
            val newState = state.copy(subagents = state.subagents + (subId -> subActor))
            processing(agentDef, resources, depth, parentRef, newState, stash, ctx)
          case None =>
            emitStream(
              resources.dispatcher,
              state.wsSend,
              ctx,
              AgentStreamEvent.ToolEnd(
                s"Delegate($agentName)",
                s"Subagent definition not found: $agentName",
                s"Subagent definition not found: $agentName",
                isError = true,
                input = Some(call.input)
              )
            )
            Behaviors.same
        end match

      case AgentCommand.Stop(_) =>
        logAgentEvent(ctx, agentDef, depth, state.sessionId, "stop", "reason=user")
        state.activeStreamFiber.foreach(f => resources.dispatcher.unsafeRunAndForget(f.cancel))
        state.subagents.values.foreach(ctx.stop)
        Behaviors.stopped

      case msg =>
        stash.stash(msg)
        Behaviors.same

  // ============================================================
  // Persist state to SessionStore if sessionId is present
  // ============================================================

  private def persistIfSession(
    resources: SharedResources,
    state: AgentState
  ): IO[Unit] =
    state.sessionId match
      case Some(sid) => resources.sessionStore.saveMessagesForSession(sid, state.messages)
      case None => IO.unit

  private def evaluateProgress(
    freshCalls: List[ToolCall],
    state: AgentState
  ): Boolean =
    val declaredWait = freshCalls.exists(_.name == "declareWait")
    if declaredWait then return true

    val hasNewRead = freshCalls.exists { c =>
      c.name == "Read" && c.input("file_path").flatMap(_.asString)
        .exists(!state.recentFilesRead.contains(_))
    }
    val hasSideEffect = freshCalls.exists { c =>
      Set("Write", "Edit", "Bash").contains(c.name)
    }
    hasNewRead || hasSideEffect

  // ============================================================
  // Start LLM call -> pipe result back to self
  // ============================================================

  /** Check if auto-compaction is needed and trigger it. Returns Some(behavior) if compacting. */
  private def maybeAutoCompact(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    stash: StashBuffer[AgentCommand],
    ctx: ActorContext[AgentCommand],
    replyTo: Option[ActorRef[AgentEvent]]
  ): Option[Behavior[AgentCommand]] =
    if agentDef.name == "context-manage" then None // prevent self-recursive compaction
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
      // inputTokens 优先用上一轮 LLM 的真实 usage(latestUsage.inputTokens),
      // 若缺失(首次 turn 或 usage 未上报)则用 TokenEstimator 的字符近似值。
      // 注意:前者是"上一轮实际消耗",后者是"当前 messages 的近似";两者不在同一基线上,
      // 但均用于触发 heuristics,不用于精确计费。
      val inputTokens = state.latestUsage.map(_.inputTokens).getOrElse(TokenEstimator.estimate(state.messages))
      val threshold = agentDef.contextWindow - CompactConfig().bufferTokens
      if inputTokens > threshold then
        logAgentEvent(
          ctx,
          agentDef,
          depth,
          state.sessionId,
          "auto-compact-trigger",
          s"inputTokens=$inputTokens threshold=$threshold usage=${state.latestUsage.isDefined}"
        )
        val subId = s"context-manage-${java.util.UUID.randomUUID().toString.take(8)}"
        val io = resources.agentLibrary.get("context-manage").flatMap { defnOpt =>
          IO(ctx.self ! AgentCommand.CompactionDefLoaded(defnOpt))
        }
        resources.dispatcher.unsafeRunAndForget(io)
        val pending = CompactionContext(subId, "full", None, replyTo)
        resources.dispatcher.unsafeRunAndForget(
          emitStreamIO(
            state.wsSend,
            ctx,
            AgentStreamEvent.CompactStart("full", Some(inputTokens), Some(threshold)),
            isSubagent = depth > 0,
            state.sessionId
          )
        )
        Some(
          processing(
            agentDef,
            resources,
            depth,
            parentRef,
            state.copy(pendingCompaction = Some(pending)),
            stash,
            ctx
          )
        )
      else None
      end if
  end maybeAutoCompact

  private def pipeLlmCall(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    stash: StashBuffer[AgentCommand],
    ctx: ActorContext[AgentCommand],
    replyTo: Option[ActorRef[AgentEvent]]
  ): Behavior[AgentCommand] =
    // Hard turn limit: if we've exceeded MaxTurns, force completion
    if state.turnIdx >= MaxTurns then
      val warningMsg = s"[Turn limit reached] Agent has exceeded the maximum of $MaxTurns turns. " +
        "Stopping now. Please synthesize a final answer based on the information gathered so far."
      logAgentEvent(ctx, agentDef, depth, state.sessionId, "turn-limit", s"turnIdx=${state.turnIdx}")
      return finishTurn(
        agentDef, resources, depth, parentRef,
        state.copy(messages = state.messages :+ Message(MessageRole.User, Left(warningMsg))),
        stash, ctx,
        "I apologize, but I've reached the maximum number of turns for this session. " +
          "Here's what I was able to determine from the work done so far: [synthesize findings]",
        replyTo
      )

    // Inter-turn auto-compaction: check threshold before calling LLM
    maybeAutoCompact(agentDef, resources, depth, parentRef, state, stash, ctx, replyTo) match
      case Some(behavior) => behavior
      case None =>
        emitStream(resources.dispatcher, state.wsSend, ctx, AgentStreamEvent.AgentStart(agentDef.name, agentDef.description))

        val systemPrompt = buildSystemPrompt(agentDef, resources)
        val tools = buildToolList(agentDef, depth, parentRef.isDefined)
        val messagesWithSystem = Message(MessageRole.System, Left(systemPrompt)) :: state.messages
        val request = LlmRequest(
          messages = messagesWithSystem,
          sessionId = state.sessionId.getOrElse(ctx.self.path.name),
          agentId = agentDef.name,
          tools = tools,
          maxTokens = Some(agentDef.maxTokens)
        )

        val isSubagent = depth > 0
        val sessionIdOpt = state.sessionId

        val onAttemptCb: nebflow.shared.FallbackAttempt => IO[Unit] = attempt =>
          val msg = attempt.message.getOrElse(
            s"${attempt.providerId}/${attempt.model} failed, retrying..."
          )
          emitStreamIO(state.wsSend, ctx, AgentStreamEvent.RetryStatus(msg), isSubagent, sessionIdOpt)

        val io = resources.llm
          .sendStream(request, onAttempt = Some(onAttemptCb))
          .through(streamEmitter(state.wsSend, ctx, isSubagent, sessionIdOpt))
          .compile
          .toList
          .map(aggregateChunks)
          .attempt
          .flatMap {
            case Right(result) => IO(ctx.self ! LlmComplete(result, replyTo))
            case Left(e) => IO(ctx.self ! LlmFailed(e, replyTo))
          }

        resources.dispatcher.unsafeRunAndForget(io)
        processing(agentDef, resources, depth, parentRef, state, stash, ctx)
  end pipeLlmCall

  // ============================================================
  // Execute tools -> pipe results back to self
  // ============================================================

  private def pipeToolExecutions(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    stash: StashBuffer[AgentCommand],
    ctx: ActorContext[AgentCommand],
    result: ConsumeResult,
    replyTo: Option[ActorRef[AgentEvent]]
  ): Behavior[AgentCommand] =
    val allowedTools = agentDef.tools match
      case Nil => Set.empty[String]
      case List("*") => ToolRegistry.ALL_TOOLS.map(_.name).toSet
      case names => names.toSet
    val filteredCalls =
      if allowedTools.isEmpty then result.toolCalls
      else result.toolCalls.filter(tc => allowedTools.contains(tc.name) || SyntheticTools.contains(tc.name))

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
          "[Stage: Paused] All tools are disabled. Please call finish() with your current best answer."
        case _ => s"[Stage: ${state.stage}] ${call.name} is temporarily disabled."
      (call, ToolExecResult(warning, isError = false))
    }

    // Deduplication: detect repetitive tool calls within recent turns
    val nextTurnIdx = state.turnIdx + 1
    val (freshCalls, duplicateResults) = stageAllowed.foldLeft(
      (List.empty[ToolCall], List.empty[(ToolCall, ToolExecResult)])
    ) { case ((fresh, dups), call) =>
      val inputHash = ToolCallRecord.canonicalHash(call.input)
      val isDup = state.recentToolCalls.exists { r =>
        r.name == call.name && r.inputHash == inputHash && (nextTurnIdx - r.turnIdx) <= DuplicateLookbackTurns
      }
      val isSameFileReRead = call.name == "Read" &&
        call.input("file_path").flatMap(_.asString).exists(state.recentFilesRead.contains)
      if isDup || isSameFileReRead then
        val lastTurn = state.recentToolCalls
          .find(r => r.name == call.name && r.inputHash == inputHash)
          .map(_.turnIdx)
          .getOrElse(0)
        val warningText = if isSameFileReRead then
          s"[Loop detection] You already read this file earlier. Do not read it again unless the user explicitly asks. Synthesize what you know."
        else
          s"[Loop detection] You already called ${call.name} with the same parameters at turn $lastTurn and received the result. " +
            "Do not repeat the same call. Synthesize your findings instead."
        val warning = ToolExecResult(warningText, isError = false)
        (fresh, dups :+ (call, warning))
      else
        (fresh :+ call, dups)
    }

    // Log loop detection if any duplicates were found
    if duplicateResults.nonEmpty then
      logAgentEvent(
        ctx, agentDef, depth, state.sessionId, "tool-dedup",
        s"skipped=${duplicateResults.map(_._1.name).mkString(",")} turn=$nextTurnIdx"
      )

    // Track deferreds created for AskUser / Permission so they can be saved to state
    var askUserDeferred: Option[cats.effect.Deferred[IO, List[String]]] = None
    var permissionDeferred: Option[cats.effect.Deferred[IO, Boolean]] = None

    val isSubagent = depth > 0
    val sessionIdOpt = state.sessionId

    val limitedFreshCalls = state.stage match
      case AdaptiveStage.Cautious if freshCalls.size > 3 =>
        logAgentEvent(ctx, agentDef, depth, state.sessionId, "tool-limit",
          s"capped parallel calls from ${freshCalls.size} to 3")
        freshCalls.take(3)
      case _ => freshCalls

    // Progress tracking — computed on actor thread before IO so updatedState is consistent
    val hadProgress = if limitedFreshCalls.isEmpty && duplicateResults.isEmpty && stageBlocked.isEmpty then
      true // No tool calls at all — agent is synthesizing or responding
    else
      evaluateProgress(limitedFreshCalls, state)
    val newStagnationCount = if hadProgress then 0 else state.stagnationCount + 1
    val newProgressStreak = if hadProgress then state.progressStreak + 1 else 0
    val targetStage = newStagnationCount match
      case n if n >= 4 => AdaptiveStage.Paused
      case n if n >= 3 => AdaptiveStage.Conservative
      case n if n >= 2 => AdaptiveStage.Cautious
      case _ => AdaptiveStage.Normal
    val newStage =
      if targetStage.ordinal > state.stage.ordinal then targetStage
      else if newProgressStreak >= 2 && targetStage.ordinal < state.stage.ordinal then
        AdaptiveStage.fromOrdinal(state.stage.ordinal - 1)
      else state.stage

    val io = limitedFreshCalls
      .traverse { call =>
        emitStreamIO(state.wsSend, ctx, AgentStreamEvent.ToolStart(nebflow.core.summarizeToolCall(call)), isSubagent, sessionIdOpt) *>
          (call.name match
            case "delegate" =>
              handleDelegate(call, agentDef, resources, depth, parentRef, state, ctx)
            case "report" =>
              handleReport(call, agentDef, parentRef, ctx)
            case "ask_parent" =>
              handleAskParent(call, parentRef, ctx)
            case "AskUserQuestion" =>
              if askUserDeferred.isDefined then
                IO.pure(ToolExecResult("Another AskUser request is already pending", isError = true))
              else
                val questionsJson = call.input("questions").flatMap(_.asArray).getOrElse(Nil)
                val items = questionsJson.flatMap { q =>
                  val question = q.hcursor.downField("question").as[String].getOrElse("")
                  val options = q.hcursor.downField("options").as[List[Json]].getOrElse(Nil)
                  val opts = options.flatMap { o =>
                    val label = o.hcursor.downField("label").as[String].getOrElse("")
                    val desc = o.hcursor.downField("description").as[String].toOption
                    Some(AskOption(label, desc))
                  }
                  Some(AskItem(question, opts))
                }.toList
                if items.isEmpty then IO.pure(ToolExecResult("No valid questions provided", isError = true))
                else
                  val deferred = cats.effect.Deferred.unsafe[IO, List[String]]
                  askUserDeferred = Some(deferred)
                  val askJson = Json.obj(
                    "type" -> "askUser".asJson,
                    "items" -> Json.fromValues(items.map { item =>
                      Json.obj(
                        "question" -> item.question.asJson,
                        "options" -> Json.fromValues(item.options.map { opt =>
                          val fields = scala.collection.mutable.ListBuffer(
                            "label" -> opt.label.asJson
                          )
                          opt.description.foreach(d => fields += "description" -> d.asJson)
                          Json.obj(fields.toList*)
                        }),
                        "allowOther" -> item.allowOther.asJson
                      )
                    })
                  )
                  for
                    _ <- state.wsSend(askJson)
                    _ <- resources.askSemaphore.acquire
                    answers <- deferred.get
                    _ <- resources.askSemaphore.release
                  yield ToolExecResult(answers.mkString(", "))
                end if
            case "finish" =>
              val answer = call.input("answer").flatMap(_.asString).getOrElse("")
              IO.pure(ToolExecResult(s"[finish] $answer", isError = false))
            case "declareWait" =>
              val reason = call.input("reason").flatMap(_.asString).getOrElse("")
              IO.pure(ToolExecResult(s"[Wait declared] $reason", isError = false))
            case _ =>
              ToolRisk.classify(call.name) match
                case ToolRisk.Safe =>
                  executeTool(
                    call,
                    resources.projectRoot.toString,
                    Some(resources.llm),
                    None,
                    None,
                    None,
                    None,
                    Some(ctx.self),
                    agentDef.contextWindow,
                    sessionId = state.sessionId,
                    taskStore = Some(resources.taskStore),
                    wsSend = Some(state.wsSend),
                    readTracker = state.readTracker
                  )
                case ToolRisk.NeedsApproval =>
                  resources.permState.shouldApprove(call.name).flatMap {
                    case ApprovalDecision.Approved =>
                      executeTool(
                        call,
                        resources.projectRoot.toString,
                        Some(resources.llm),
                        None,
                        None,
                        None,
                        None,
                        Some(ctx.self),
                        agentDef.contextWindow,
                        sessionId = state.sessionId,
                        taskStore = Some(resources.taskStore),
                        wsSend = Some(state.wsSend),
                        readTracker = state.readTracker
                      )
                    case ApprovalDecision.Blocked(reason) =>
                      IO.pure(
                        ToolExecResult(
                          NebflowError.toUserMessage(NebflowError.ToolDenied(call.name, reason)),
                          isError = true
                        )
                      )
                    case ApprovalDecision.NeedsUserApproval =>
                      if permissionDeferred.isDefined then
                        IO.pure(
                          ToolExecResult(
                            "Another permission request is already pending",
                            isError = true
                          )
                        )
                      else
                        val deferred = cats.effect.Deferred.unsafe[IO, Boolean]
                        permissionDeferred = Some(deferred)
                        val summary = nebflow.core.summarizeToolCall(call)
                        val permJson = Json.obj(
                          "type" -> "askPermission".asJson,
                          "toolName" -> call.name.asJson,
                          "summary" -> summary.asJson,
                          "input" -> call.input.asJson
                        )
                        for
                          _ <- state.wsSend(permJson)
                          approved <- deferred.get
                          result <-
                            if approved then
                              executeTool(
                                call,
                                resources.projectRoot.toString,
                                Some(resources.llm),
                                None,
                                None,
                                None,
                                None,
                                Some(ctx.self),
                                agentDef.contextWindow,
                                sessionId = state.sessionId,
                                taskStore = Some(resources.taskStore),
                                wsSend = Some(state.wsSend),
                                readTracker = state.readTracker
                              )
                            else IO.pure(ToolExecResult("Permission denied by user", isError = true))
                        yield result
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
        val allResults = freshResults ++ stageBlockedResults ++ duplicateResults

        if newStage != state.stage || newStagnationCount != state.stagnationCount then
          emitStream(
            resources.dispatcher, state.wsSend, ctx,
            AgentStreamEvent.ProgressUpdate(nextTurnIdx, newStagnationCount, newStage.toString),
            isSubagent = depth > 0, state.sessionId
          )
        if newStage == AdaptiveStage.Paused && state.stage != AdaptiveStage.Paused then
          emitStream(
            resources.dispatcher, state.wsSend, ctx,
            AgentStreamEvent.Paused("Agent has paused after detecting stagnation. Providing best-effort summary..."),
            isSubagent = depth > 0, state.sessionId
          )

        IO(ctx.self ! ToolsComplete(allResults, result.text, replyTo, None, result.thinking,
          Some(newStagnationCount), Some(newStage), Some(newProgressStreak)))
      }

    resources.dispatcher.unsafeRunAndForget(io)

    // Update recentToolCalls with all calls from this turn (fresh + blocked + duplicates)
    val newRecords = filteredCalls.map { call =>
      ToolCallRecord(call.name, ToolCallRecord.canonicalHash(call.input), nextTurnIdx)
    }
    val prunedRecent = (state.recentToolCalls ++ newRecords)
      .sortBy(-_.turnIdx)
      .take(MaxRecentToolCalls)

    val newRecentFilesRead = freshCalls.filter(_.name == "Read")
      .flatMap(_.input("file_path").flatMap(_.asString))
      .toSet ++ state.recentFilesRead

    val updatedState = state.copy(
      pendingAskUser = askUserDeferred.orElse(state.pendingAskUser),
      pendingPermission = permissionDeferred.orElse(state.pendingPermission),
      recentToolCalls = prunedRecent,
      turnIdx = nextTurnIdx,
      recentFilesRead = newRecentFilesRead,
      stagnationCount = newStagnationCount,
      stage = newStage,
      progressStreak = newProgressStreak
    )
    processing(agentDef, resources, depth, parentRef, updatedState, stash, ctx)
  end pipeToolExecutions

  // ============================================================
  // Synthetic tool handlers
  // ============================================================

  private def handleDelegate(
    call: ToolCall,
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    ctx: ActorContext[AgentCommand]
  ): IO[ToolExecResult] =
    if depth >= MaxDepth then
      IO.pure(ToolExecResult(s"Cannot delegate: maximum depth ($MaxDepth) exceeded", isError = true))
    else
      val agentName = call.input("agent").flatMap(_.asString).getOrElse("")
      val task = call.input("task").flatMap(_.asString).getOrElse("")
      agentDef.subagents.find(_.name == agentName) match
        case None =>
          IO.pure(
            ToolExecResult(
              s"Unknown subagent: '$agentName'. Available: ${agentDef.subagents.map(_.name).mkString(", ")}",
              isError = true
            )
          )
        case Some(slot) =>
          // Load definition via IO, pipe result back to self so ctx.spawn runs on actor thread
          resources.agentLibrary
            .get(slot.agent)
            .flatMap { defnOpt =>
              IO(ctx.self ! AgentCommand.SubagentDefLoaded(call, agentName, task, defnOpt, depth + 1))
            }
            .as(ToolExecResult(s"Delegated to $agentName: ${task.take(100)}"))

  private def handleReport(
    call: ToolCall,
    agentDef: AgentDef,
    parentRef: Option[ActorRef[AgentCommand]],
    ctx: ActorContext[AgentCommand]
  ): IO[ToolExecResult] =
    parentRef match
      case None =>
        IO.pure(ToolExecResult("Cannot report: no parent agent", isError = true))
      case Some(parent) =>
        val message = call.input("message").flatMap(_.asString).getOrElse("")
        parent ! AgentCommand.DelegateResult(ctx.self.path.name, Right(message))
        IO.pure(ToolExecResult("Reported to parent"))

  private def handleAskParent(
    call: ToolCall,
    parentRef: Option[ActorRef[AgentCommand]],
    ctx: ActorContext[AgentCommand]
  ): IO[ToolExecResult] =
    parentRef match
      case None =>
        IO.pure(ToolExecResult("Cannot ask_parent: no parent agent", isError = true))
      case Some(parent) =>
        val question = call.input("question").flatMap(_.asString).getOrElse("")
        // Use ask pattern — block until parent answers
        import scala.concurrent.ExecutionContext.Implicits.global
        implicit val scheduler: org.apache.pekko.actor.typed.Scheduler = ctx.system.scheduler
        implicit val askTimeout: org.apache.pekko.util.Timeout =
          org.apache.pekko.util.Timeout(scala.concurrent.duration.Duration(60, "seconds"))
        IO.fromFuture(IO {
          parent.ask[ParentAnswer](replyTo => AgentCommand.SubagentQuestion(ctx.self.path.name, question, replyTo))
        }).map(answer => ToolExecResult(answer.answer))
          .handleError(e => ToolExecResult(s"Ask parent failed: ${e.getMessage}", isError = true))

  // ============================================================
  // Finish turn — emit done, return to idle
  // ============================================================

  private def finishTurn(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    stash: StashBuffer[AgentCommand],
    ctx: ActorContext[AgentCommand],
    text: String,
    replyTo: Option[ActorRef[AgentEvent]],
    thinking: Option[String] = None
  ): Behavior[AgentCommand] =
    emitStream(resources.dispatcher, state.wsSend, ctx, AgentStreamEvent.Done, isSubagent = parentRef.isDefined, state.sessionId)
    parentRef match
      case Some(parent) =>
        parent ! AgentCommand.DelegateResult(ctx.self.path.name, Right(text))
        Behaviors.stopped
      case None =>
        logAgentEvent(
          ctx,
          agentDef,
          depth,
          state.sessionId,
          "turn-complete",
          s"msgs=${state.messages.size} text=${text.take(40)}"
        )
        val assistantContent = (thinking, text) match
          case (None, _) => Left(text)
          case (Some(t), "") => Right(List(ContentBlock.Thinking(t)))
          case (Some(t), txt) => Right(List(ContentBlock.Thinking(t), ContentBlock.Text(txt)))
        val newMessages = state.messages :+ Message(MessageRole.Assistant, assistantContent)
        val updatedState = state.copy(
          messages = newMessages,
          status = AgentStatus.Idle,
          pendingAskUser = None,
          pendingPermission = None
        )

        resources.dispatcher.unsafeRunAndForget(persistIfSession(resources, updatedState))

        replyTo.foreach(_ ! AgentEvent.Completed(state.sessionId.getOrElse(""), newMessages))
        stash.unstashAll(
          idle(
            agentDef,
            resources,
            depth,
            parentRef,
            updatedState,
            stash,
            ctx
          )
        )
    end match
  end finishTurn

  // ============================================================
  // Stream helpers
  // ============================================================

  private def emitStream(
    dispatcher: cats.effect.std.Dispatcher[IO],
    wsSend: io.circe.Json => IO[Unit],
    ctx: ActorContext[?],
    event: AgentStreamEvent,
    isSubagent: Boolean = true,
    sessionId: Option[String] = None
  ): Unit =
    dispatcher.unsafeRunAndForget(
      wsSend(event.toJson(ctx.self.path.name, isSubagent, sessionId))
    )

  private def emitStreamIO(
    wsSend: io.circe.Json => IO[Unit],
    ctx: ActorContext[?],
    event: AgentStreamEvent,
    isSubagent: Boolean = true,
    sessionId: Option[String] = None
  ): IO[Unit] =
    wsSend(event.toJson(ctx.self.path.name, isSubagent, sessionId))

  /** Emit streaming events as chunks arrive. */
  private def streamEmitter(
    wsSend: io.circe.Json => IO[Unit],
    ctx: ActorContext[AgentCommand],
    isSubagent: Boolean = true,
    sessionId: Option[String] = None
  ): fs2.Pipe[IO, StreamChunk, StreamChunk] =
    stream =>
      stream.evalTap {
        case StreamChunk.TextDelta(delta) if delta.nonEmpty =>
          val json = if isSubagent then
            AgentStreamEvent.TextDelta(delta).toJson(ctx.self.path.name, true, None)
          else
            Json.obj("type" -> "textDelta".asJson, "sessionId" -> sessionId.asJson, "delta" -> delta.asJson)
          wsSend(json)
        case StreamChunk.ThinkingDelta(delta) if delta.nonEmpty =>
          val json = if isSubagent then
            AgentStreamEvent.Thinking.toJson(ctx.self.path.name, true, None)
          else
            Json.obj("type" -> "thinking".asJson, "sessionId" -> sessionId.asJson)
          wsSend(json)
        case StreamChunk.ToolCallChunk(tc) =>
          val json = if isSubagent then
            AgentStreamEvent.ToolStart(nebflow.core.summarizeToolCall(tc)).toJson(ctx.self.path.name, true, None)
          else
            Json.obj("type" -> "toolStart".asJson, "sessionId" -> sessionId.asJson, "label" -> nebflow.core.summarizeToolCall(tc).asJson)
          wsSend(json)
        case _ => IO.unit
      }

  private def aggregateChunks(chunks: List[StreamChunk]): ConsumeResult =
    val text = chunks.collect { case StreamChunk.TextDelta(d) => d }.mkString
    val thinking = chunks.collect { case StreamChunk.ThinkingDelta(d) => d }.mkString
    val toolCalls = chunks.collect { case StreamChunk.ToolCallChunk(tc) => tc }
    val usage = chunks.collectFirst { case StreamChunk.Done(_, u, _) => u }.flatten
    val stopReason = chunks.collectFirst { case StreamChunk.Done(sr, _, _) => sr }.flatten
    ConsumeResult(text, toolCalls, Nil, stopReason, usage, Option.when(thinking.nonEmpty)(thinking))

  // Token estimation moved to compact.TokenEstimator

  // ============================================================
  // Prompt / tool helpers
  // ============================================================

  private def buildSystemPrompt(agentDef: AgentDef, resources: SharedResources): String =
    if agentDef.systemPrompt.nonEmpty then agentDef.systemPrompt
    else Repl.loadSystemPrompt() + "\n\n" + Repl.buildEnvInfo(resources.projectRoot.toString)

  private def buildToolList(agentDef: AgentDef, depth: Int, hasParent: Boolean): Option[List[ToolDefinition]] =
    val base = agentDef.tools match
      case Nil => Nil
      case List("*") => ToolRegistry.ALL_TOOLS
      case names => ToolRegistry.ALL_TOOLS.filter(t => names.contains(t.name))
    val synthetic = List.newBuilder[ToolDefinition]
    // delegate — only if this agent has subagent slots
    if agentDef.subagents.nonEmpty then
      synthetic += ToolDefinition(
        name = "delegate",
        description =
          s"Delegate a task to a sub-agent. Available agents: ${agentDef.subagents.map(s => s"${s.name} (${s.agent})").mkString(", ")}",
        inputSchema = JsonObject.fromIterable(
          List(
            "type" -> Json.fromString("object"),
            "properties" -> Json.fromFields(
              List(
                "agent" -> Json.fromFields(
                  List(
                    "type" -> Json.fromString("string"),
                    "description" -> Json.fromString("Name of the sub-agent to delegate to")
                  )
                ),
                "task" -> Json.fromFields(
                  List(
                    "type" -> Json.fromString("string"),
                    "description" -> Json.fromString("The task description to delegate")
                  )
                )
              )
            ),
            "required" -> Json.fromValues(List(Json.fromString("agent"), Json.fromString("task")))
          )
        )
      )
    end if
    // report — only for subagents (hasParent)
    if hasParent then
      synthetic += ToolDefinition(
        name = "report",
        description =
          "Report a result or message back to the parent agent that delegated to you. Use this when you have completed your assigned task.",
        inputSchema = JsonObject.fromIterable(
          List(
            "type" -> Json.fromString("object"),
            "properties" -> Json.fromFields(
              List(
                "message" -> Json.fromFields(
                  List(
                    "type" -> Json.fromString("string"),
                    "description" -> Json.fromString("The result or message to report")
                  )
                )
              )
            ),
            "required" -> Json.fromValues(List(Json.fromString("message")))
          )
        )
      )
      // ask_parent — only for subagents
      synthetic += ToolDefinition(
        name = "ask_parent",
        description =
          "Ask the parent agent a question and wait for a response. Use this when you need clarification or information from the parent.",
        inputSchema = JsonObject.fromIterable(
          List(
            "type" -> Json.fromString("object"),
            "properties" -> Json.fromFields(
              List(
                "question" -> Json.fromFields(
                  List(
                    "type" -> Json.fromString("string"),
                    "description" -> Json.fromString("The question to ask the parent")
                  )
                )
              )
            ),
            "required" -> Json.fromValues(List(Json.fromString("question")))
          )
        )
      )
    end if
    synthetic += ToolDefinition(
      name = "finish",
      description = "Signal that the task is complete and provide the final answer.",
      inputSchema = JsonObject.fromIterable(List(
        "type" -> Json.fromString("object"),
        "properties" -> Json.fromFields(List(
          "answer" -> Json.fromFields(List(
            "type" -> Json.fromString("string"),
            "description" -> Json.fromString("The final answer or summary of findings")
          ))
        )),
        "required" -> Json.fromValues(List(Json.fromString("answer")))
      ))
    )
    synthetic += ToolDefinition(
      name = "declareWait",
      description = "Declare that this turn is intentionally waiting for an external condition and should not count as stagnation.",
      inputSchema = JsonObject.fromIterable(List(
        "type" -> Json.fromString("object"),
        "properties" -> Json.fromFields(List(
          "reason" -> Json.fromFields(List(
            "type" -> Json.fromString("string"),
            "description" -> Json.fromString("Explanation of what is being waited for")
          ))
        )),
        "required" -> Json.fromValues(List(Json.fromString("reason")))
      ))
    )
    Some(base ++ synthetic.result())

  end buildToolList

  private def summarizeToolResult(call: ToolCall, result: String): String =
    nebflow.core.summarizeToolResult(call, result)

end AgentActor
