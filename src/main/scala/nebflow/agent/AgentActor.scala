package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.agent.AgentCommand.*
import nebflow.core.*
import nebflow.core.compact.*
import nebflow.shared.*
import nebflow.shared.given
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}
import org.apache.pekko.actor.typed.{ActorRef, Behavior, SupervisorStrategy}

/**
 * Core agent actor — the heart of the multi-agent system.
 *
 * Message flow:
 *   UserInput -> startLlm -> LlmComplete -> [executeTools -> ToolsComplete -> startLlm -> ...] -> idle
 *
 * Root agents (depth=0, parentRef=None) also manage session state:
 * dedup, sessionBusy signaling, and history persistence.
 */
object AgentActor extends AgentCore with AgentSession:

  private val MaxStashCapacity = 100
  private val logger = NebflowLogger.forName("nebflow.agent")

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
      case AgentCommand.UserInput(text, replyTo, clientMessageId) =>
        // Dedup check (root agent session management)
        val (isDuplicate, dedupedState) = checkDuplicate(clientMessageId, state)
        if isDuplicate then
          logger.info(s"Dropping duplicate message with clientMessageId=${clientMessageId.getOrElse("")}")
          Behaviors.same
        else
          logAgentEvent(
            ctx,
            agentDef,
            depth,
            state.sessionId,
            "start",
            s"text=${text.take(40)} msgs=${state.messages.size}"
          )
          val userMsg = Message(MessageRole.User, Left(text))
          val newMessages = dedupedState.messages :+ userMsg
          pipeLlmCall(
            agentDef,
            resources,
            depth,
            parentRef,
            dedupedState.withMessages(newMessages).withRecentFilesRead(Set.empty),
            stash,
            ctx,
            replyTo
          )

      case AgentCommand.Stop(_) =>
        logAgentEvent(ctx, agentDef, depth, state.sessionId, "stop", "reason=user")
        state.activeStreamFiber.foreach(f =>
          resources.dispatcher.unsafeRunAndForget(f.cancel.handleErrorWith(_ => IO.unit))
        )
        state.subagents.values.foreach(ctx.stop)
        Behaviors.stopped

      case AgentCommand.TriggerCompaction(mode, replyDeferred) =>
        handleTriggerCompaction(agentDef, resources, depth, parentRef, state, stash, ctx, mode, replyDeferred)

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
        val updatedState = state.withLatestUsage(result.usage.orElse(state.latestUsage))
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
            result.thinking,
            textAlreadyStreamed = true
          )
        else pipeToolExecutions(agentDef, resources, depth, parentRef, updatedState, stash, ctx, result, replyTo)

      case LlmFailed(error, replyTo) =>
        logAgentEvent(ctx, agentDef, depth, state.sessionId, "llm-fail", s"err=${error.getMessage.take(60)}")
        val agentError =
          AgentError(ctx.self.path.name, agentDef.name, depth, AgentErrorType.LlmFailed, error.getMessage)
        parentRef match
          case Some(p) => p ! AgentCommand.DelegateResult(ctx.self.path.name, Left(agentError))
          case None =>
            emitStream(
              resources.dispatcher,
              state.wsSend,
              ctx,
              AgentStreamEvent.Done,
              isSubagent = false,
              state.sessionId
            )
        replyTo.foreach(_ ! AgentEvent.Failed(state.sessionId.getOrElse(""), agentError))
        stash.unstashAll(
          idle(agentDef, resources, depth, parentRef, state.withStatus(AgentStatus.Error(error.getMessage)), stash, ctx)
        )

      // --- Tools completed ---
      case tc: ToolsComplete =>
        val didContextManage = tc.results.exists { case (call, _) => call.name == "ContextManage" }
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

        val updatedState = state.copy(
          execution = state.execution.copy(messages = newMessages, interaction = None),
          safety = state.safety.copy(
            stagnationCount = tc.nextStagnationCount.getOrElse(state.stagnationCount),
            stage = tc.nextStage.getOrElse(state.stage),
            progressStreak = tc.nextProgressStreak.getOrElse(state.progressStreak)
          )
        )
        resources.dispatcher.unsafeRunAndForget(
          persistIfSession(resources, updatedState)
            .handleErrorWith(e =>
              IO(NebflowLogger.forName("nebflow.agent").warn(s"Persist session failed: ${e.getMessage}"))
            )
        )

        val didFinish = tc.results.exists { case (call, _) => call.name == "finish" }
        if didFinish then
          // When LLM called finish(), prefer the already-streamed text if available.
          // Otherwise, use the finish tool's answer. textAlreadyStreamed controls whether
          // finishTurn emits a TextDelta (false = emit, true = skip since already streamed).
          val (turnText, wasStreamed) =
            if tc.originalText.nonEmpty then (tc.originalText, true)
            else
              val answer = tc.results
                .collectFirst {
                  case (call, r) if call.name == "finish" => r.content.stripPrefix("[finish] ")
                }
                .getOrElse("")
              (answer, false)
          finishTurn(
            agentDef,
            resources,
            depth,
            parentRef,
            updatedState,
            stash,
            ctx,
            turnText,
            tc.replyTo,
            tc.thinking,
            textAlreadyStreamed = wasStreamed
          )
        else if didContextManage then
          // ContextManage triggered: state is updated, results appended, but do NOT pipe
          // next LLM call — DelegateResult from compaction sub-agent will drive the next turn.
          processing(agentDef, resources, depth, parentRef, updatedState, stash, ctx)
        else pipeLlmCall(agentDef, resources, depth, parentRef, updatedState, stash, ctx, tc.replyTo)
        end if

      // --- Subagent stream forwarding ---
      case AgentCommand.SubagentStreamEvent(subId, event) =>
        val isCompaction = state.pendingCompaction.exists(_.subagentId == subId)
        if !isCompaction then emitStream(resources.dispatcher, state.wsSend, ctx, event)
        else
          logAgentEvent(
            ctx,
            agentDef,
            depth,
            state.sessionId,
            "compact-sub-stream",
            s"subId=$subId event=${event.getClass.getSimpleName}"
          )
        Behaviors.same

      // --- Subagent returned result ---
      case AgentCommand.DelegateResult(subId, result) =>
        state.pendingCompaction match
          case Some(pending) if pending.subagentId == subId =>
            val compactedMessages = result match
              case Right(text) =>
                pending.mode match
                  case "micro" => MicroCompact.parseResponse(text, state.messages)
                  case _ => FullCompact.parseResponse(text, state.messages, resources.projectRoot.toString)
              case Left(err) =>
                NebflowLogger.forName("nebflow.agent").warn(s"Compaction agent failed: ${err.message}")
                Left(err.message)

            state.subagents.get(subId).foreach(ctx.unwatch)
            val newState = state.withPendingCompaction(None).withSubagents(state.subagents - subId)

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
                  s"subId=$subId mode=${pending.mode} before=${state.messages.size} after=${compacted.size}"
                )
                resources.dispatcher.unsafeRunAndForget(
                  emitStreamIO(
                    state.wsSend,
                    ctx,
                    AgentStreamEvent.CompactComplete(state.messages.size, compacted.size, None, None),
                    isSubagent = depth > 0,
                    state.sessionId
                  )
                    .handleErrorWith(e =>
                      IO(NebflowLogger.forName("nebflow.agent").warn(s"emit CompactComplete failed: ${e.getMessage}"))
                    )
                )
                // Archive in background — fire-and-forget, does not block state machine
                state.sessionId.foreach { sid =>
                  resources.dispatcher.unsafeRunAndForget(
                    resources.historyArchiver
                      .archiveComparison(sid, pending.mode, state.messages, compacted)
                      .flatMap {
                        case Right(path) => IO(NebflowLogger.forName("nebflow.agent").info(s"Archive complete: $path"))
                        case Left(err) => IO(NebflowLogger.forName("nebflow.agent").warn(s"Archive failed: $err"))
                      }
                      .handleErrorWith(e =>
                        IO(NebflowLogger.forName("nebflow.agent").warn(s"Archive error: ${e.getMessage}"))
                      )
                  )
                }
                pending.replyDeferred.foreach { d =>
                  resources.dispatcher.unsafeRunAndForget(
                    d.complete(Right(CompactionResult(state.messages.size, compacted.size)))
                      .handleErrorWith(_ => IO.unit)
                  )
                }
                pipeLlmCall(
                  agentDef,
                  resources,
                  depth,
                  parentRef,
                  newState.withMessages(compacted).withCompactionFailures(0),
                  stash,
                  ctx,
                  pending.replyTo
                )

              case Left(err) =>
                val failures = state.compactionFailures + 1
                val maxFailures = CompactConfig().circuitBreakerMax
                NebflowLogger
                  .forName("nebflow.agent")
                  .warn(s"Auto-compact failed: $err (attempt $failures/$maxFailures)")
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
                    .handleErrorWith(_ => IO.unit)
                )
                pending.replyDeferred.foreach { d =>
                  resources.dispatcher.unsafeRunAndForget(d.complete(Left(err)).handleErrorWith(_ => IO.unit))
                }
                val failedState = newState.withCompactionFailures(failures)
                if failures >= maxFailures then
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
                      emitStream(
                        resources.dispatcher,
                        state.wsSend,
                        ctx,
                        AgentStreamEvent.Done,
                        isSubagent = false,
                        state.sessionId
                      )
                  pending.replyTo.foreach(_ ! AgentEvent.Failed(state.sessionId.getOrElse(""), agentError))
                  stash.unstashAll(
                    idle(
                      agentDef,
                      resources,
                      depth,
                      parentRef,
                      failedState.withStatus(
                        AgentStatus.Error(s"Compaction circuit breaker open after $failures attempts")
                      ),
                      stash,
                      ctx
                    )
                  )
                else pipeLlmCall(agentDef, resources, depth, parentRef, failedState, stash, ctx, pending.replyTo)
                end if
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
              state
                .withSubagents(state.subagents - subId)
                .withMessages(state.messages :+ Message(MessageRole.User, Left(resultText))),
              stash,
              ctx
            )

      // --- AskUser from tool (Pekko Ask pattern) ---
      case AgentCommand.AskUser(requestId, items, replyTo) =>
        val askJson = io.circe.Json.obj(
          "type" -> "askUser".asJson,
          "items" -> io.circe.Json.fromValues(items.map { item =>
            io.circe.Json.obj(
              "question" -> item.question.asJson,
              "options" -> io.circe.Json.fromValues(item.options.map { opt =>
                val fields = scala.collection.mutable.ListBuffer("label" -> opt.label.asJson)
                opt.description.foreach(d => fields += "description" -> d.asJson)
                io.circe.Json.obj(fields.toList*)
              }),
              "allowOther" -> item.allowOther.asJson
            )
          })
        )
        // Store replyTo so UserAnswered can complete it, forward to WS
        val updatedState = state.copy(
          execution = state.execution.copy(
            interaction = Some(
              InteractionState(
                pendingAskUser = None,
                pendingPermission = state.pendingPermission,
                pendingAskUserReplyTo = Some(replyTo)
              )
            )
          )
        )
        resources.dispatcher.unsafeRunAndForget(
          state.wsSend(askJson).handleErrorWith(e => IO(replyTo ! Nil) *> IO.unit)
        )
        processing(agentDef, resources, depth, parentRef, updatedState, stash, ctx)

      // --- User interaction responses ---
      case AgentCommand.UserAnswered(answers) =>
        // Complete AskUser replyTo if present (from AskUserQuestionTool via Pekko Ask)
        state.execution.interaction.flatMap(_.pendingAskUserReplyTo) match
          case Some(replyTo) =>
            replyTo ! answers
            val clearedState = state.copy(
              execution = state.execution.copy(
                interaction = state.execution.interaction.map(_.copy(pendingAskUserReplyTo = None))
              )
            )
            processing(agentDef, resources, depth, parentRef, clearedState, stash, ctx)
          case None => Behaviors.same

      case AgentCommand.PermissionAnswered(approved) =>
        state.pendingPermission match
          case Some(deferred) =>
            resources.dispatcher.unsafeRunAndForget(deferred.complete(approved).handleErrorWith(_ => IO.unit))
            Behaviors.same
          case None => Behaviors.same

      // --- Busy signal for root agents ---
      case AgentCommand.UserInput(_, _, _) if depth == 0 && parentRef.isEmpty =>
        state.sessionId.foreach { sid =>
          resources.dispatcher.unsafeRunAndForget(
            emitSessionBusy(state.wsSend, sid, busy = true).handleErrorWith(_ => IO.unit)
          )
        }
        Behaviors.same

      // --- Interrupt ---
      case AgentCommand.Interrupt() =>
        state.activeStreamFiber.foreach(f =>
          resources.dispatcher.unsafeRunAndForget(f.cancel.handleErrorWith(_ => IO.unit))
        )
        state.subagents.values.foreach(_ ! AgentCommand.Interrupt())
        state.pendingAskUser.foreach(d =>
          resources.dispatcher.unsafeRunAndForget(d.complete(Nil).handleErrorWith(_ => IO.unit))
        )
        state.execution.interaction.flatMap(_.pendingAskUserReplyTo).foreach(_ ! Nil)
        state.pendingPermission.foreach(d =>
          resources.dispatcher.unsafeRunAndForget(d.complete(false).handleErrorWith(_ => IO.unit))
        )
        emitStream(resources.dispatcher, state.wsSend, ctx, AgentStreamEvent.Done)
        // Root agent: emit sessionBusy=false on interrupt
        if depth == 0 && parentRef.isEmpty then
          state.sessionId.foreach { sid =>
            resources.dispatcher.unsafeRunAndForget(
              emitSessionBusy(state.wsSend, sid, busy = false).handleErrorWith(_ => IO.unit)
            )
          }
        stash.unstashAll(idle(agentDef, resources, depth, parentRef, state.resetForInterrupt, stash, ctx))

      // --- Compaction agent definition loaded ---
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
                val silentWsSend: Json => IO[Unit] = _ => IO.unit
                val subActor = ctx.spawn(
                  AgentActor(
                    subDef,
                    resources,
                    silentWsSend,
                    depth + 1,
                    Some(ctx.self),
                    readTracker = state.readTracker
                  ),
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
                processing(
                  agentDef,
                  resources,
                  depth,
                  parentRef,
                  state.withSubagents(state.subagents + (subId -> subActor)),
                  stash,
                  ctx
                )
              case None =>
                NebflowLogger.forName("nebflow.agent").warn("context-manage agent not found, skipping compaction")
                pending.replyDeferred.foreach { d =>
                  resources.dispatcher.unsafeRunAndForget(
                    d.complete(Left("context-manage agent not found")).handleErrorWith(_ => IO.unit)
                  )
                }
                processing(agentDef, resources, depth, parentRef, state.withPendingCompaction(None), stash, ctx)
          case None => Behaviors.same

      // --- Manual compaction trigger ---
      case AgentCommand.TriggerCompaction(mode, replyDeferred) =>
        handleTriggerCompaction(agentDef, resources, depth, parentRef, state, stash, ctx, mode, replyDeferred)

      // --- Subagent definition loaded ---
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
            emitStream(
              resources.dispatcher,
              state.wsSend,
              ctx,
              AgentStreamEvent.AgentStart(subDef.name, subDef.description)
            )
            processing(
              agentDef,
              resources,
              depth,
              parentRef,
              state.withSubagents(state.subagents + (subId -> subActor)),
              stash,
              ctx
            )
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

      case AgentCommand.Stop(_) =>
        logAgentEvent(ctx, agentDef, depth, state.sessionId, "stop", "reason=user")
        state.activeStreamFiber.foreach(f =>
          resources.dispatcher.unsafeRunAndForget(f.cancel.handleErrorWith(_ => IO.unit))
        )
        state.subagents.values.foreach(ctx.stop)
        Behaviors.stopped

      case msg =>
        stash.stash(msg)
        Behaviors.same

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
    thinking: Option[String] = None,
    textAlreadyStreamed: Boolean = false
  ): Behavior[AgentCommand] =
    if !textAlreadyStreamed && text.nonEmpty then
      emitStream(
        resources.dispatcher,
        state.wsSend,
        ctx,
        AgentStreamEvent.TextDelta(text),
        isSubagent = parentRef.isDefined,
        state.sessionId
      )
    emitStream(
      resources.dispatcher,
      state.wsSend,
      ctx,
      AgentStreamEvent.Done,
      isSubagent = parentRef.isDefined,
      state.sessionId
    )
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
          execution = ExecutionContext.idle(newMessages, state.execution.turnIdx),
          safety = state.safety.copy(recentFilesRead = Set.empty)
        )

        // Root agent: persist to SessionStore and emit sessionBusy=false
        state.sessionId.foreach { sid =>
          resources.dispatcher.unsafeRunAndForget(
            (resources.sessionStore.saveMessagesForSession(sid, newMessages) *>
              resources.sessionStore.flushIndex *>
              emitSessionBusy(state.wsSend, sid, busy = false))
              .handleErrorWith(e =>
                IO(NebflowLogger.forName("nebflow.agent").warn(s"Save/flush session failed: ${e.getMessage}"))
              )
          )
        }

        replyTo.foreach(_ ! AgentEvent.Completed(state.sessionId.getOrElse(""), newMessages))
        stash.unstashAll(idle(agentDef, resources, depth, parentRef, updatedState, stash, ctx))
    end match
  end finishTurn

  // ============================================================
  // Pipe wrappers — bridge AgentCore methods with local behavior refs
  // ============================================================

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
    super.pipeLlmCall(
      agentDef,
      resources,
      depth,
      parentRef,
      state,
      stash,
      ctx,
      replyTo,
      processing = (ad, r, d, p, s, st, c) => processing(ad, r, d, p, s, st, c),
      finishTurn =
        (ad, r, d, p, s, st, c, t, rep, th, streamed) => finishTurn(ad, r, d, p, s, st, c, t, rep, th, streamed)
    )

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
    super.pipeToolExecutions(
      agentDef,
      resources,
      depth,
      parentRef,
      state,
      stash,
      ctx,
      result,
      replyTo,
      processing = (ad, r, d, p, s, st, c) => processing(ad, r, d, p, s, st, c),
      pipeLlmCallFn = (ad, r, d, p, s, st, c, rep) => pipeLlmCall(ad, r, d, p, s, st, c, rep),
      finishTurnFn =
        (ad, r, d, p, s, st, c, t, rep, th, streamed) => finishTurn(ad, r, d, p, s, st, c, t, rep, th, streamed)
    )

  private def handleTriggerCompaction(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    stash: StashBuffer[AgentCommand],
    ctx: ActorContext[AgentCommand],
    mode: String,
    replyDeferred: Option[cats.effect.Deferred[IO, Either[String, CompactionResult]]]
  ): Behavior[AgentCommand] =
    if state.pendingCompaction.isDefined then
      replyDeferred.foreach { d =>
        resources.dispatcher.unsafeRunAndForget(
          d.complete(Left("Compaction already in progress")).handleErrorWith(_ => IO.unit)
        )
      }
      Behaviors.same
    else if state.compactionFailures >= CompactConfig().circuitBreakerMax then
      val err = s"Compaction circuit breaker open after ${state.compactionFailures} attempts"
      replyDeferred.foreach { d =>
        resources.dispatcher.unsafeRunAndForget(d.complete(Left(err)).handleErrorWith(_ => IO.unit))
      }
      resources.dispatcher.unsafeRunAndForget(
        emitStreamIO(
          state.wsSend,
          ctx,
          AgentStreamEvent.CompactFailed(err, state.compactionFailures, CompactConfig().circuitBreakerMax),
          isSubagent = depth > 0,
          state.sessionId
        )
          .handleErrorWith(_ => IO.unit)
      )
      Behaviors.same
    else
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
      val pending = CompactionJob(subId, mode, replyDeferred)
      resources.dispatcher.unsafeRunAndForget(
        emitStreamIO(
          state.wsSend,
          ctx,
          AgentStreamEvent.CompactStart(mode, None, None),
          isSubagent = depth > 0,
          state.sessionId
        )
          .handleErrorWith(_ => IO.unit)
      )
      processing(agentDef, resources, depth, parentRef, state.withPendingCompaction(Some(pending)), stash, ctx)

end AgentActor
