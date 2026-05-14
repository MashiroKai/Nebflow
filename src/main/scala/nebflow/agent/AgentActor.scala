package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.agent.AgentCommand.*
import nebflow.core.*
import nebflow.core.compact.*
import nebflow.core.tools.AskUserQuestionTool
import nebflow.llm.FallbackExhaustedError
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
  private val MaxEmptyResponseRetries = 2
  private val logger = NebflowLogger.forName("nebflow.agent")

  def apply(
    agentDef: AgentDef,
    resources: SharedResources,
    wsSend: io.circe.Json => IO[Unit],
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]] = None,
    sessionId: Option[String] = None,
    sessionName: Option[String] = None,
    initialMessages: List[Message] = Nil,
    readTracker: Option[nebflow.core.tools.ReadTracker] = None,
    fileHistory: Option[nebflow.core.tools.FileHistory] = None,
    contextWindow: Int = Defaults.ContextWindow
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
              sessionName,
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
                sessionName = sessionName,
                pendingCompaction = None,
                latestUsage = None,
                pendingAskUser = None,
                pendingPermission = None,
                wsSend = wsSend,
                readTracker = readTracker,
                fileHistory = fileHistory,
                contextWindow = contextWindow
              ),
              stash,
              context
            )
          }
        }
      )
      .onFailure[Exception](SupervisorStrategy.restart.withLimit(2, java.time.Duration.ofSeconds(30)))

  /** Refresh agent definition from library so on-disk config changes (e.g. mcpServers) take effect. */
  private def refreshAgentDef(current: AgentDef, resources: SharedResources): AgentDef =
    import cats.effect.unsafe.implicits.global
    resources.agentLibrary.get(current.name).unsafeRunSync().getOrElse(current)

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
      case AgentCommand.UserInput(text, replyTo, clientMessageId, blocks) =>
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
            state.sessionName,
            "start",
            s"msgs=${state.messages.size}"
          )
          // Auto-detect language on first user message (root agent only, one-time)
          if depth == 0 && parentRef.isEmpty && dedupedState.messages.isEmpty then
            LanguageDetector.detect(text).foreach { lang =>
              resources.dispatcher.unsafeRunAndForget(
                resources.runtimePrefs.getLanguage.flatMap {
                  case None =>
                    resources.runtimePrefs.setLanguage(Some(lang)) *>
                      NebflowLogger.forName("nebflow.agent").info(s"Auto-detected language: $lang") *>
                      state
                        .wsSend(io.circe.Json.obj("type" -> "serverConfig".asJson, "language" -> lang.asJson))
                        .handleErrorWith(_ => IO.unit)
                  case Some(_) => IO.unit
                }
              )
            }
          end if

          val userMsg = blocks.filter(_.nonEmpty) match
            case Some(bl) => Message(MessageRole.User, Right(bl))
            case None => Message(MessageRole.User, Left(text))
          val newMessages = dedupedState.messages :+ userMsg
          // Refresh memory if not yet loaded (first message of session)
          val stateWithMemory =
            if dedupedState.memoryBlock.isEmpty then
              dedupedState.withMemoryBlock(buildMemoryBlock(agentDef, dedupedState.sessionId))
            else dedupedState
          pipeLlmCall(
            agentDef,
            resources,
            depth,
            parentRef,
            stateWithMemory.withMessages(newMessages),
            stash,
            ctx,
            replyTo
          )
        end if

      case AgentCommand.Interrupt() =>
        // Idle state — nothing to interrupt, ignore
        Behaviors.same

      case AgentCommand.Stop(_) =>
        logAgentEvent(ctx, agentDef, depth, state.sessionId, state.sessionName, "stop", "reason=user")
        state.activeStreamFiber.foreach(f =>
          resources.dispatcher.unsafeRunAndForget(f.cancel.handleErrorWith(_ => IO.unit))
        )
        state.subagents.values.foreach(ctx.stop)
        Behaviors.stopped

      case AgentCommand.ClearReadTracker =>
        state.readTracker.foreach(t => resources.dispatcher.unsafeRunAndForget(t.clear()))
        Behaviors.same

      case AgentCommand.ResetSession =>
        logAgentEvent(ctx, agentDef, depth, state.sessionId, state.sessionName, "reset-session", "")
        state.readTracker.foreach(t => resources.dispatcher.unsafeRunAndForget(t.clear()))
        val resetState = state
          .withMessages(Nil)
          .withLatestUsage(None)
          .withPendingCompaction(None)
          .withCompactionFailures(0)
          .withLastCompactionFailureAt(0L)
          .withMemoryBlock("") // Will be re-loaded on next UserInput
          .withWritesSinceLastRead(Map.empty)
          .withRecentMessageIds(Nil)
          .withCompaction(state.compaction.copy(highestPressureLevel = 0))
        val refreshedDef = refreshAgentDef(agentDef, resources)
        idle(refreshedDef, resources, depth, parentRef, resetState, stash, ctx)

      case AgentCommand.TriggerCompaction(mode, replyDeferred) =>
        // User-triggered /compact: compress then return to idle (no LLM resume)
        handleTriggerCompaction(
          agentDef,
          resources,
          depth,
          parentRef,
          state,
          stash,
          ctx,
          mode,
          replyDeferred,
          resumeAfterCompact = false
        )

      case AgentCommand.UpdateContextWindow(window) =>
        idle(agentDef, resources, depth, parentRef, state.withContextWindow(window), stash, ctx)

      // --- Background task completed (Phase 1 compat): convert to ExternalEvent ---
      case n: AgentCommand.BackgroundTaskNotification =>
        ctx.self ! n.toExternalEvent
        Behaviors.same

      // --- External event: universal wake-up ---
      case AgentCommand.ExternalEvent(source, eventType, payload, metadata, correlationId) =>
        logAgentEvent(
          ctx,
          agentDef,
          depth,
          state.sessionId,
          state.sessionName,
          "external-event",
          s"source=$source type=$eventType"
        )
        emitStream(
          resources.dispatcher,
          state.wsSend,
          ctx,
          AgentStreamEvent.ExternalEventReceived(source, eventType, correlationId),
          isSubagent = depth > 0,
          state.sessionId
        )
        val userMsg = Message(MessageRole.User, Left(payload))
        val newMessages = state.messages :+ userMsg
        pipeLlmCall(
          agentDef,
          resources,
          depth,
          parentRef,
          state.withMessages(newMessages),
          stash,
          ctx,
          None
        )

      // --- User answered: route to blocking replyTo or forward to sub-agents ---
      case AgentCommand.UserAnswered(answers) =>
        state.execution.interaction.flatMap(_.pendingAskUserReplyTo) match
          case Some(replyTo) =>
            // Complete blocking AskUserQuestion call
            replyTo ! answers
            val clearedState = state.withInteraction(None)
            idle(agentDef, resources, depth, parentRef, clearedState, stash, ctx)
          case None =>
            // Not for us — forward to sub-agents (a sub-agent may have a pending AskUser)
            state.subagents.values.foreach(_ ! AgentCommand.UserAnswered(answers))
            Behaviors.same

      // --- Parent answered (async ask_parent response): inject and activate ---
      case AgentCommand.ParentAnswer(answer) =>
        logAgentEvent(
          ctx,
          agentDef,
          depth,
          state.sessionId,
          state.sessionName,
          "parent-answer-injected",
          s"answerLen=${answer.length}"
        )
        val userMsg = Message(MessageRole.User, Left(s"[Parent response]\n$answer"))
        val newMessages = state.messages :+ userMsg
        pipeLlmCall(
          agentDef,
          resources,
          depth,
          parentRef,
          state.withMessages(newMessages),
          stash,
          ctx,
          None
        )

      // --- Stale CompactionComplete: discard (agent was interrupted/reset) ---
      case AgentCommand.CompactionComplete(result) =>
        logAgentEvent(
          ctx,
          agentDef,
          depth,
          state.sessionId,
          state.sessionName,
          "stale-compaction-discarded",
          result.fold(err => s"err=${err.take(60)}", msgs => s"ok=${msgs.size}msgs")
        )
        state.pendingCompaction.foreach(_.replyDeferred.foreach { d =>
          resources.dispatcher.unsafeRunAndForget(
            d.complete(Left("Compaction result arrived after agent returned to idle")).handleErrorWith(_ => IO.unit)
          )
        })
        Behaviors.same

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

      // --- Stream fiber registered (for Interrupt cancellation) ---
      case AgentCommand.StreamFiberStarted(fiber) =>
        processing(agentDef, resources, depth, parentRef, state.withActiveStreamFiber(Some(fiber)), stash, ctx)

      // --- Per-session highest pressure level update (from IO fiber) ---
      case AgentCommand.UpdateHighestPressureLevel(level) =>
        processing(
          agentDef,
          resources,
          depth,
          parentRef,
          state.withCompaction(state.compaction.copy(highestPressureLevel = level)),
          stash,
          ctx
        )

      // --- LLM completed ---
      case LlmComplete(result, replyTo, turnId) =>
        // Discard stale results from a previous (interrupted) turn
        if turnId != state.currentTurnId then
          logAgentEvent(
            ctx,
            agentDef,
            depth,
            state.sessionId,
            state.sessionName,
            "stale-llm-complete-discarded",
            s"turnId=$turnId current=${state.currentTurnId}"
          )
          Behaviors.same
        else
          val updatedState = state
            .withLatestUsage(result.usage.orElse(state.latestUsage))
            .updateContextWindowIfNeeded(result.contextWindow)
          if result.toolCalls.nonEmpty then
            pipeToolExecutions(agentDef, resources, depth, parentRef, updatedState, stash, ctx, result, replyTo)
          // Has text or thinking content — normal completion
          else if result.text.nonEmpty || result.thinking.nonEmpty then
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
              textAlreadyStreamed = true,
              result.model
            )
          // Empty response — something went wrong, check stopReason
          else
            val stopReason = result.stopReason.getOrElse("")
            val isContextError = stopReason.toLowerCase.contains("context") ||
              stopReason.toLowerCase.contains("exceeded") ||
              stopReason.toLowerCase.contains("limit") ||
              stopReason.toLowerCase.contains("too long") ||
              stopReason.toLowerCase.contains("too_long")
            val isMaxTokens = stopReason.equalsIgnoreCase("max_tokens") ||
              stopReason.equalsIgnoreCase("length")

            if isContextError then
              // Context window exceeded
              logAgentEvent(
                ctx,
                agentDef,
                depth,
                state.sessionId,
                state.sessionName,
                "context-exceeded",
                s"stopReason=$stopReason"
              )
              // Only root agent triggers compaction; sub-agents (including context-manage itself)
              // must propagate the error upward to avoid infinite sub-agent recursion.
              if depth > 0 then
                val errMsg = s"Context window exceeded (stopReason: $stopReason)"
                parentRef match
                  case Some(p) =>
                    p ! AgentCommand.DelegateResult(
                      ctx.self.path.name,
                      Left(
                        AgentError(ctx.self.path.name, agentDef.name, depth, AgentErrorType.LlmFailed, errMsg)
                      )
                    )
                    Behaviors.stopped
                  case None =>
                    // Should not happen (depth > 0 implies parentRef.isDefined), but handle gracefully
                    finishTurn(
                      agentDef,
                      resources,
                      depth,
                      parentRef,
                      updatedState,
                      stash,
                      ctx,
                      errMsg,
                      replyTo,
                      None,
                      textAlreadyStreamed = false,
                      result.model
                    )
                end match
              else if state.pendingCompaction.isDefined || state.compactionFailures >= CompactConfig().circuitBreakerMax
              then
                // Compaction already in progress or circuit breaker open — report error
                val msg = "Context window exceeded and compaction is unavailable. " +
                  "Please start a new session or use /clear to reset."
                finishTurn(
                  agentDef,
                  resources,
                  depth,
                  parentRef,
                  updatedState,
                  stash,
                  ctx,
                  msg,
                  replyTo,
                  None,
                  textAlreadyStreamed = false,
                  result.model
                )
              else
                // Trigger direct compaction via CompactService, then retry
                val jobId = s"compact-${java.util.UUID.randomUUID().toString.take(8)}"
                val pending = CompactionJob(jobId, "full", None, replyTo)
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
                      "full",
                      result.usage.map(_.inputTokens),
                      Some(state.contextWindow - CompactConfig().bufferForWindow(state.contextWindow))
                    ),
                    isSubagent = depth > 0,
                    state.sessionId
                  ).handleErrorWith(_ => IO.unit)
                )
                processing(
                  agentDef,
                  resources,
                  depth,
                  parentRef,
                  updatedState.withPendingCompaction(Some(pending)),
                  stash,
                  ctx
                )
              end if
            else if isMaxTokens then
              // Max tokens reached — notify frontend and finish turn
              logAgentEvent(
                ctx,
                agentDef,
                depth,
                state.sessionId,
                state.sessionName,
                "max-tokens",
                s"stopReason=$stopReason"
              )
              resources.dispatcher.unsafeRunAndForget(
                state
                  .wsSend(
                    io.circe.Json.obj(
                      "type" -> "maxTokens".asJson,
                      "sessionId" -> state.sessionId.asJson
                    )
                  )
                  .handleErrorWith(_ => IO.unit)
              )
              finishTurn(
                agentDef,
                resources,
                depth,
                parentRef,
                updatedState,
                stash,
                ctx,
                "[Response truncated: max output tokens reached]",
                replyTo,
                None,
                textAlreadyStreamed = false,
                result.model
              )
            else
              // Unknown empty response — retry up to MaxEmptyResponseRetries times, then treat as error
              val retryCount = updatedState.emptyResponseRetries
              logAgentEvent(
                ctx,
                agentDef,
                depth,
                state.sessionId,
                state.sessionName,
                "empty-response",
                s"stopReason=$stopReason retry=$retryCount/$MaxEmptyResponseRetries usage=${result.usage.map(u => s"in=${u.inputTokens} out=${u.outputTokens}").getOrElse("none")}"
              )
              if retryCount < MaxEmptyResponseRetries then
                // Retry: increment counter and re-issue the LLM call
                val stateForRetry = updatedState.withEmptyResponseRetries(retryCount + 1)
                logger.info(
                  s"Empty response, retrying (${retryCount + 1}/$MaxEmptyResponseRetries) stopReason=$stopReason"
                )
                resources.dispatcher.unsafeRunAndForget(
                  emitStreamIO(
                    state.wsSend,
                    ctx,
                    AgentStreamEvent.RetryStatus(
                      s"Empty response from LLM, retrying (${retryCount + 1}/$MaxEmptyResponseRetries)..."
                    ),
                    isSubagent = depth > 0,
                    state.sessionId
                  ).handleErrorWith(_ => IO.unit)
                )
                pipeLlmCall(agentDef, resources, depth, parentRef, stateForRetry, stash, ctx, replyTo)
              else
                // Retries exhausted — report error
                val errMsg =
                  if stopReason.nonEmpty then
                    s"LLM returned empty response after $MaxEmptyResponseRetries retries (stopReason: $stopReason)"
                  else s"LLM returned empty response with no content after $MaxEmptyResponseRetries retries"
                parentRef match
                  case Some(p) =>
                    p ! AgentCommand.DelegateResult(
                      ctx.self.path.name,
                      Left(
                        AgentError(ctx.self.path.name, agentDef.name, depth, AgentErrorType.LlmFailed, errMsg)
                      )
                    )
                    Behaviors.stopped
                  case None =>
                    state.sessionId.foreach { sid =>
                      resources.dispatcher.unsafeRunAndForget(
                        (state
                          .wsSend(
                            io.circe.Json.obj(
                              "type" -> "error".asJson,
                              "sessionId" -> state.sessionId.asJson,
                              "message" -> errMsg.asJson
                            )
                          )
                          .handleErrorWith(_ => IO.unit)) *>
                          emitSessionBusy(state.wsSend, sid, busy = false)
                      )
                    }
                    emitStream(
                      resources.dispatcher,
                      state.wsSend,
                      ctx,
                      AgentStreamEvent.Done(result.model),
                      isSubagent = false,
                      state.sessionId
                    )
                    stash.unstashAll(
                      idle(agentDef, resources, depth, parentRef, updatedState, stash, ctx)
                    )
                end match
              end if
            end if
          end if
        end if

      case LlmFailed(error, replyTo, turnId) =>
        // Discard stale errors from a previous (interrupted) turn
        if turnId != state.currentTurnId then
          logAgentEvent(
            ctx,
            agentDef,
            depth,
            state.sessionId,
            state.sessionName,
            "stale-llm-failed-discarded",
            s"turnId=$turnId current=${state.currentTurnId}"
          )
          Behaviors.same
        else
          // Cleanup: cancel active stream fiber and stop sub-agents to prevent resource leaks
          state.activeStreamFiber.foreach(f =>
            resources.dispatcher.unsafeRunAndForget(f.cancel.handleErrorWith(_ => IO.unit))
          )
          state.subagents.values.foreach(ctx.stop)
          val cleanedState = state
            .withSubagents(Map.empty)
            .withActiveStreamFiber(None)

          logAgentEvent(
            ctx,
            agentDef,
            depth,
            state.sessionId,
            state.sessionName,
            "llm-fail",
            s"err=${error.getMessage.take(80)}"
          )
          val agentError =
            AgentError(ctx.self.path.name, agentDef.name, depth, AgentErrorType.LlmFailed, error.getMessage)
          parentRef match
            case Some(p) => p ! AgentCommand.DelegateResult(ctx.self.path.name, Left(agentError))
            case None =>
              // Emit error to frontend so the user knows what happened, then Done to clear the spinner
              val errMsg = error match
                case e: FallbackExhaustedError =>
                  val attempts = e.attempts
                    .map { a =>
                      val detail = a.message.getOrElse(a.reason.map(_.toString).getOrElse("unknown"))
                      s"${a.providerId}/${a.model}: $detail"
                    }
                    .mkString("; ")
                  s"All providers failed: $attempts"
                case _ =>
                  Option(error.getMessage)
                    .filter(_.nonEmpty)
                    .map(m => s"LLM request failed: ${m.take(200)}")
                    .getOrElse(s"LLM request failed: ${error.getClass.getSimpleName}")
              cleanedState.sessionId.foreach { sid =>
                resources.dispatcher.unsafeRunAndForget(
                  (cleanedState
                    .wsSend(
                      io.circe.Json.obj(
                        "type" -> "error".asJson,
                        "sessionId" -> cleanedState.sessionId.asJson,
                        "message" -> errMsg.asJson
                      )
                    )
                    .handleErrorWith(_ => IO.unit)) *>
                    emitSessionBusy(cleanedState.wsSend, sid, busy = false)
                )
              }
              emitStream(
                resources.dispatcher,
                cleanedState.wsSend,
                ctx,
                AgentStreamEvent.Done(None),
                isSubagent = false,
                cleanedState.sessionId
              )
          end match
          replyTo.foreach(_ ! AgentEvent.Failed(cleanedState.sessionId.getOrElse(""), agentError))
          stash.unstashAll(
            idle(
              agentDef,
              resources,
              depth,
              parentRef,
              cleanedState.withStatus(AgentStatus.Error(error.getMessage)),
              stash,
              ctx
            )
          )
        end if

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

        val updatedState = state
          .copy(
            execution = state.execution.copy(messages = newMessages, interaction = None)
          )
          .withWritesSinceLastRead(tc.updatedWriteTracker)
        resources.dispatcher.unsafeRunAndForget(
          persistIfSession(resources, updatedState)
            .handleErrorWith(e =>
              IO(NebflowLogger.forName("nebflow.agent").warn(s"Persist session failed: ${e.getMessage}"))
            )
        )

        if didContextManage then
          // ContextManage triggered: directly invoke compaction (not via TriggerCompaction message,
          // which would get stashed in processing state and cause timing issues).
          // After compaction completes, CompactionComplete handler will call pipeLlmCall to resume.
          handleTriggerCompaction(agentDef, resources, depth, parentRef, updatedState, stash, ctx, "full", None)
        else pipeLlmCall(agentDef, resources, depth, parentRef, updatedState, stash, ctx, tc.replyTo)
        end if

      // --- Subagent definition loaded, spawn and start ---
      case AgentCommand.SubagentDefLoaded(call, agentName, task, defnOpt, subDepth) =>
        defnOpt match
          case None =>
            logAgentEvent(
              ctx,
              agentDef,
              depth,
              state.sessionId,
              state.sessionName,
              "subagent-def-not-found",
              s"agent=$agentName"
            )
            // Inject error as tool result so the LLM sees the failure
            val errMsg = s"[Tool error: Unknown agent '$agentName']"
            val toolResultMsg = Message(
              MessageRole.User,
              Right(List(ContentBlock.ToolResult(call.id, errMsg, Some(true))))
            )
            val assistantMsg = Message(
              MessageRole.Assistant,
              Right(List(ContentBlock.ToolUse(call.id, call.name, call.input)))
            )
            val updatedMessages = state.messages :+ assistantMsg :+ toolResultMsg
            pipeLlmCall(
              agentDef,
              resources,
              depth,
              parentRef,
              state.withMessages(updatedMessages),
              stash,
              ctx,
              None
            )
          case Some(subDef) =>
            val subId = s"sub-${java.util.UUID.randomUUID().toString.take(8)}"
            logAgentEvent(
              ctx,
              agentDef,
              depth,
              state.sessionId,
              state.sessionName,
              "subagent-spawn",
              s"subId=$subId agent=$agentName depth=$subDepth"
            )
            // Sub-agent wsSend: forward events directly (sub-agent already uses correct format)
            val subWsSend: Json => IO[Unit] = state.wsSend

            val subBehavior = AgentActor(
              subDef,
              resources,
              subWsSend,
              depth = subDepth,
              parentRef = Some(ctx.self),
              sessionId = state.sessionId,
              sessionName = state.sessionName
            )
            val subRef = ctx.spawn(subBehavior, subId)
            ctx.watch(subRef)
            // Inject assistant tool_use + user tool_result into parent messages
            val assistantMsg = Message(
              MessageRole.Assistant,
              Right(List(ContentBlock.ToolUse(call.id, call.name, call.input)))
            )
            val toolResultMsg = Message(
              MessageRole.User,
              Right(
                List(
                  ContentBlock.ToolResult(
                    call.id,
                    s"Delegated to $agentName: ${task.take(100)}",
                    Some(false)
                  )
                )
              )
            )
            val updatedMessages = state.messages :+ assistantMsg :+ toolResultMsg
            // Send the task to the sub-agent
            subRef ! AgentCommand.UserInput(task, None)
            emitStream(
              resources.dispatcher,
              state.wsSend,
              ctx,
              AgentStreamEvent.AgentStart(subId, agentName),
              isSubagent = depth > 0,
              state.sessionId
            )
            processing(
              agentDef,
              resources,
              depth,
              parentRef,
              state
                .withSubagents(state.subagents + (subId -> subRef))
                .withMessages(updatedMessages),
              stash,
              ctx
            )
        end match

      // --- Subagent stream forwarding ---
      case AgentCommand.SubagentStreamEvent(subId, event) =>
        emitStream(resources.dispatcher, state.wsSend, ctx, event)
        Behaviors.same

      // --- Subagent returned result ---
      case AgentCommand.DelegateResult(subId, result) =>
        state.subagents.get(subId).foreach(ctx.unwatch)
        emitStream(resources.dispatcher, state.wsSend, ctx, AgentStreamEvent.AgentEnd(subId))
        val resultText = result match
          case Right(text) => s"[Subagent $subId completed]: $text"
          case Left(err) => s"[Subagent $subId failed]: ${err.message}"
        result match
          case Right(_) =>
            logAgentEvent(
              ctx,
              agentDef,
              depth,
              state.sessionId,
              state.sessionName,
              "subagent-complete",
              s"subId=$subId"
            )
          case Left(err) =>
            logAgentEvent(
              ctx,
              agentDef,
              depth,
              state.sessionId,
              state.sessionName,
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

      // --- Interrupt: cancel current LLM stream, discard pending compaction ---
      case AgentCommand.Interrupt() =>
        logAgentEvent(ctx, agentDef, depth, state.sessionId, state.sessionName, "interrupt", "reason=user")
        state.activeStreamFiber.foreach(f =>
          resources.dispatcher.unsafeRunAndForget(f.cancel.handleErrorWith(_ => IO.unit))
        )
        val interruptedState = state.resetForInterrupt
          .withPendingCompaction(None)
        // Complete any pending replyDeferred
        state.pendingCompaction.foreach(_.replyDeferred.foreach { d =>
          resources.dispatcher.unsafeRunAndForget(d.complete(Left("Interrupted by user")).handleErrorWith(_ => IO.unit))
        })
        stash.unstashAll(idle(agentDef, resources, depth, parentRef, interruptedState, stash, ctx))

      // --- Stop ---
      case AgentCommand.Stop(_) =>
        logAgentEvent(ctx, agentDef, depth, state.sessionId, state.sessionName, "stop", "reason=user")
        state.activeStreamFiber.foreach(f =>
          resources.dispatcher.unsafeRunAndForget(f.cancel.handleErrorWith(_ => IO.unit))
        )
        state.subagents.values.foreach(ctx.stop)
        Behaviors.stopped

      case AgentCommand.ClearReadTracker =>
        state.readTracker.foreach(t => resources.dispatcher.unsafeRunAndForget(t.clear()))
        Behaviors.same

      case AgentCommand.ResetSession =>
        // In processing state — cancel current work, then reset
        state.activeStreamFiber.foreach(f =>
          resources.dispatcher.unsafeRunAndForget(f.cancel.handleErrorWith(_ => IO.unit))
        )
        state.readTracker.foreach(t => resources.dispatcher.unsafeRunAndForget(t.clear()))
        val resetState = state
          .withMessages(Nil)
          .withLatestUsage(None)
          .withPendingCompaction(None)
          .withCompactionFailures(0)
          .withLastCompactionFailureAt(0L)
          .withMemoryBlock("")
          .withWritesSinceLastRead(Map.empty)
          .withRecentMessageIds(Nil)
          .withCompaction(state.compaction.copy(highestPressureLevel = 0))
          .resetToIdle(Nil)
        val refreshedDef = refreshAgentDef(agentDef, resources)
        stash.unstashAll(idle(refreshedDef, resources, depth, parentRef, resetState, stash, ctx))

      // --- ReplaceToolResults: agent-driven context management ---
      case AgentCommand.ReplaceToolResults(rounds, summary, replyTo) =>
        val result = replaceToolResults(state.messages, rounds, summary)
        result match
          case Right((updatedMessages, count)) =>
            resources.dispatcher.unsafeRunAndForget(
              replyTo.complete(Right(count)).handleErrorWith(_ => IO.unit)
            )
            processing(
              agentDef,
              resources,
              depth,
              parentRef,
              state.withMessages(updatedMessages),
              stash,
              ctx
            )
          case Left(err) =>
            resources.dispatcher.unsafeRunAndForget(
              replyTo.complete(Left(err)).handleErrorWith(_ => IO.unit)
            )
            Behaviors.same

      // --- Compaction completed: apply compacted messages and retry ---
      case AgentCommand.CompactionComplete(result) =>
        // Guard: discard stale CompactionComplete (e.g. after ResetSession)
        if state.pendingCompaction.isEmpty then
          ctx.log.warn("[AgentActor] discarding stale CompactionComplete (no pending compaction)")
          Behaviors.same
        else
          val pending = state.pendingCompaction
          result match
            case Right(compactedMessages) =>
              logAgentEvent(
                ctx,
                agentDef,
                depth,
                state.sessionId,
                state.sessionName,
                "compaction-complete",
                s"before=${state.messages.size} after=${compactedMessages.size}"
              )
              // Archive compaction for debugging (fire-and-forget, non-blocking)
              val archiveIO = state.sessionId match
                case Some(sid) =>
                  resources.historyArchiver.archiveCompaction(
                    sessionId = sid,
                    sessionName = state.sessionName,
                    agentName = agentDef.name,
                    before = state.messages,
                    after = compactedMessages,
                    mode = pending.map(_.mode).getOrElse("full"),
                    extra = Map("preservedRounds" -> FullCompact.PreserveRecentRounds.toString)
                  )
                case None => IO.pure(Left("no sessionId"))
              resources.dispatcher.unsafeRunAndForget(
                archiveIO
                  .flatMap {
                    case Right(archive) =>
                      emitStreamIO(
                        state.wsSend,
                        ctx,
                        AgentStreamEvent.CompactComplete(
                          state.messages.size,
                          compactedMessages.size,
                          Some(archive.reportPath)
                        ),
                        isSubagent = depth > 0,
                        state.sessionId
                      )
                    case Left(err) =>
                      NebflowLogger.forName("nebflow.agent").warn(s"Compaction archive failed: $err")
                      emitStreamIO(
                        state.wsSend,
                        ctx,
                        AgentStreamEvent.CompactComplete(state.messages.size, compactedMessages.size, None),
                        isSubagent = depth > 0,
                        state.sessionId
                      )
                  }
                  .handleErrorWith(_ => IO.unit)
              )
              val compactedState = state
                .withMessages(compactedMessages)
                .withPendingCompaction(None)
                .withCompactionFailures(0)
                .withEmptyResponseRetries(0)
                .withLatestUsage(None) // Clear stale usage to prevent maybeAutoCompact re-trigger
                // Refresh memory after compaction — agent may have written memory during the compacted turns
                .withMemoryBlock(buildMemoryBlock(agentDef, state.sessionId))
              val refreshedDef = refreshAgentDef(agentDef, resources)

              if pending.exists(_.resumeAfterCompact) then
                // Auto/LLM-triggered: resume LLM call with compacted messages
                pipeLlmCall(
                  refreshedDef,
                  resources,
                  depth,
                  parentRef,
                  compactedState,
                  stash,
                  ctx,
                  pending.flatMap(_.replyTo)
                )
              else
                // User-triggered /compact: apply compacted messages and return to idle
                resources.dispatcher.unsafeRunAndForget(
                  persistIfSession(resources, compactedState)
                    .handleErrorWith(e =>
                      IO(NebflowLogger.forName("nebflow.agent").warn(s"Persist after compact failed: ${e.getMessage}"))
                    )
                )
                stash.unstashAll(idle(refreshedDef, resources, depth, parentRef, compactedState, stash, ctx))
              end if
            case Left(err) =>
              logAgentEvent(
                ctx,
                agentDef,
                depth,
                state.sessionId,
                state.sessionName,
                "compaction-failed",
                s"err=$err"
              )
              resources.dispatcher.unsafeRunAndForget(
                emitStreamIO(
                  state.wsSend,
                  ctx,
                  AgentStreamEvent.CompactFailed(err, state.compactionFailures + 1, CompactConfig().circuitBreakerMax),
                  isSubagent = depth > 0,
                  state.sessionId
                ).handleErrorWith(_ => IO.unit)
              )
              val now = System.currentTimeMillis()
              val failedState = state
                .withPendingCompaction(None)
                .withCompactionFailures(state.compactionFailures + 1)
                .withLastCompactionFailureAt(now)
              // Complete any pending replyDeferred
              pending.foreach(_.replyDeferred.foreach { d =>
                resources.dispatcher.unsafeRunAndForget(d.complete(Left(err)).handleErrorWith(_ => IO.unit))
              })
              // If we have a replyTo (root agent context-exceeded path), finish turn with error
              pending.flatMap(_.replyTo) match
                case Some(replyTo) =>
                  val errMsg = s"Context compaction failed: $err. Please start a new session or use /clear to reset."
                  finishTurn(
                    agentDef,
                    resources,
                    depth,
                    parentRef,
                    failedState,
                    stash,
                    ctx,
                    errMsg,
                    Some(replyTo),
                    None,
                    textAlreadyStreamed = false,
                    None
                  )
                case None =>
                  if pending.exists(!_.resumeAfterCompact) then
                    // User-triggered /compact: return to idle on failure too
                    stash.unstashAll(idle(agentDef, resources, depth, parentRef, failedState, stash, ctx))
                  else processing(agentDef, resources, depth, parentRef, failedState, stash, ctx)
          end match
        end if

      // --- Background task completed while processing (Phase 1 compat): convert and queue ---
      case n: AgentCommand.BackgroundTaskNotification =>
        ctx.self ! n.toExternalEvent
        Behaviors.same

      // --- External event while processing: queue for injection after this turn ---
      case AgentCommand.ExternalEvent(source, eventType, payload, metadata, correlationId) =>
        logAgentEvent(
          ctx,
          agentDef,
          depth,
          state.sessionId,
          state.sessionName,
          "external-event-queued",
          s"source=$source type=$eventType"
        )
        emitStream(
          resources.dispatcher,
          state.wsSend,
          ctx,
          AgentStreamEvent.ExternalEventReceived(source, eventType, correlationId),
          isSubagent = depth > 0,
          state.sessionId
        )
        val event = AgentCommand.ExternalEvent(source, eventType, payload, metadata, correlationId)
        val updatedExec = state.execution.copy(
          pendingEvents = state.execution.pendingEvents :+ event
        )
        processing(agentDef, resources, depth, parentRef, state.copy(execution = updatedExec), stash, ctx)

      // --- AskUser from tool (blocking AskUserQuestion) ---
      case AgentCommand.AskUser(requestId, items, replyToOpt) =>
        val askJson = io.circe.Json.obj(
          "type" -> "askUser".asJson,
          "sessionId" -> state.sessionId.asJson,
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
        // Store replyTo so UserAnswered can complete it (blocking mode)
        val updatedInteraction = Some(
          InteractionState(
            pendingAskUser = None,
            pendingPermission = state.pendingPermission,
            pendingAskUserReplyTo = replyToOpt
          )
        )
        val updatedState = state.copy(
          execution = state.execution.copy(interaction = updatedInteraction)
        )
        resources.dispatcher.unsafeRunAndForget(
          state.wsSend(askJson).handleErrorWith { e =>
            replyToOpt.foreach(replyTo => IO(replyTo ! Nil))
            IO.unit
          }
        )
        processing(agentDef, resources, depth, parentRef, updatedState, stash, ctx)

      // --- Sub-agent asks a question (from ask_parent tool): auto-respond ---
      case AgentCommand.SubagentQuestion(subId, question, replyToOpt, subagentRefOpt) =>
        logAgentEvent(
          ctx,
          agentDef,
          depth,
          state.sessionId,
          state.sessionName,
          "subagent-question",
          s"subId=$subId question=${question.take(40)}"
        )
        val answer = "I don't have additional information beyond the task description I provided. " +
          "Please use your best judgment to proceed."
        // Send answer back to sub-agent
        replyToOpt.foreach(_ ! ParentAnswer(answer))
        subagentRefOpt.foreach(_ ! ParentAnswer(answer))
        processing(agentDef, resources, depth, parentRef, state, stash, ctx)

      // --- Parent answered while processing: queue for injection after this turn ---
      case AgentCommand.ParentAnswer(answer) =>
        logAgentEvent(
          ctx,
          agentDef,
          depth,
          state.sessionId,
          state.sessionName,
          "parent-answer-queued",
          s"answerLen=${answer.length}"
        )
        val responseText = s"[Parent response]\n$answer"
        val updatedExec = state.execution.copy(
          pendingResponses = state.execution.pendingResponses :+ responseText
        )
        processing(agentDef, resources, depth, parentRef, state.copy(execution = updatedExec), stash, ctx)

      // --- User answered while processing: complete blocking AskUserQuestion ---
      case AgentCommand.UserAnswered(answers) =>
        state.execution.interaction.flatMap(_.pendingAskUserReplyTo) match
          case Some(replyTo) =>
            logAgentEvent(
              ctx,
              agentDef,
              depth,
              state.sessionId,
              state.sessionName,
              "user-answered-in-processing",
              s"answers=${answers.size}"
            )
            replyTo ! answers
            val clearedState = state.withInteraction(None)
            processing(agentDef, resources, depth, parentRef, clearedState, stash, ctx)
          case None =>
            // Not for us — forward to sub-agents (a sub-agent may have a pending AskUser)
            state.subagents.values.foreach(_ ! AgentCommand.UserAnswered(answers))
            Behaviors.same

      // --- Permission answered while processing: complete blocking permission deferred ---
      case AgentCommand.PermissionAnswered(approved) =>
        state.pendingPermission match
          case Some(deferred) =>
            logAgentEvent(
              ctx,
              agentDef,
              depth,
              state.sessionId,
              state.sessionName,
              "permission-answered-in-processing",
              s"approved=$approved"
            )
            resources.dispatcher.unsafeRunAndForget(
              deferred.complete(approved).handleErrorWith(_ => IO.unit)
            )
            val clearedState = state.withPendingPermission(None)
            processing(agentDef, resources, depth, parentRef, clearedState, stash, ctx)
          case None =>
            Behaviors.same

      // --- Set permission deferred while processing: store in state ---
      case AgentCommand.SetPermissionDeferred(deferred) =>
        val updatedState = state.withPendingPermission(Some(deferred))
        processing(agentDef, resources, depth, parentRef, updatedState, stash, ctx)

      // --- Session model switched: update contextWindow for compaction threshold ---
      case AgentCommand.UpdateContextWindow(window) =>
        processing(agentDef, resources, depth, parentRef, state.withContextWindow(window), stash, ctx)

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
    textAlreadyStreamed: Boolean = false,
    model: Option[String] = None
  ): Behavior[AgentCommand] =
    val isSubagent = parentRef.isDefined
    if !textAlreadyStreamed && text.nonEmpty then
      emitStream(
        resources.dispatcher,
        state.wsSend,
        ctx,
        AgentStreamEvent.TextDelta(text),
        isSubagent = isSubagent,
        state.sessionId
      )
    // Build Done event with model, contextWindow, and inputTokens
    val doneEvent = AgentStreamEvent.Done(
      model,
      contextWindow = if !isSubagent then Some(state.contextWindow) else None,
      inputTokens = if !isSubagent then state.latestUsage.map(_.inputTokens) else None
    )
    // For root agent: await Done event delivery to guarantee frontend receives it
    // For sub-agent: fire-and-forget (parent handles the done signal)
    if isSubagent then
      emitStream(
        resources.dispatcher,
        state.wsSend,
        ctx,
        doneEvent,
        isSubagent = true,
        state.sessionId
      )
    else
      val doneJson = doneEvent.toJson(ctx.self.path.name, false, state.sessionId)
      NebflowLogger
        .forName("nebflow.agent")
        .info(
          s"finishTurn: sending Done sessionId=${state.sessionId.getOrElse("-")} jsonLen=${doneJson.noSpaces.length}"
        )
      resources.dispatcher.unsafeRunAndForget(
        state
          .wsSend(doneJson)
          .handleErrorWith(e =>
            IO(NebflowLogger.forName("nebflow.agent").warn(s"finishTurn: Done event delivery failed: ${e.getMessage}"))
          ) *> IO {
          NebflowLogger
            .forName("nebflow.agent")
            .info(s"finishTurn: Done event delivered sessionId=${state.sessionId.getOrElse("-")}")
        }
      )
    end if
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
          state.sessionName,
          "turn-complete",
          s"msgs=${state.messages.size} textLen=${text.length} textStreamed=$textAlreadyStreamed thinking=${thinking.map(_.length).getOrElse(0)} model=${model.getOrElse("-")}"
        )
        val assistantContent = (thinking, text) match
          case (None, _) => Left(text)
          case (Some(t), "") => Right(List(ContentBlock.Thinking(t)))
          case (Some(t), txt) => Right(List(ContentBlock.Thinking(t), ContentBlock.Text(txt)))
        val newMessages = state.messages :+ Message(MessageRole.Assistant, assistantContent)

        // Check for queued external events and async responses
        val queuedEvents = state.execution.pendingEvents
        val queuedResponses = state.execution.pendingResponses
        val hasPending = queuedEvents.nonEmpty || queuedResponses.nonEmpty
        if hasPending && depth == 0 then
          // Inject events as user messages
          val eventMessages = queuedEvents.map { e =>
            Message(MessageRole.User, Left(e.payload))
          }
          // Inject async responses (from ask_parent)
          val responseMessages = queuedResponses.map(text => Message(MessageRole.User, Left(text)))
          val allPendingMessages = eventMessages ++ responseMessages
          val messagesWithPending = newMessages ++ allPendingMessages
          logAgentEvent(
            ctx,
            agentDef,
            depth,
            state.sessionId,
            state.sessionName,
            "pending-messages-injected",
            s"events=${queuedEvents.size} responses=${queuedResponses.size}"
          )
          val updatedState = state.copy(
            execution = ExecutionContext.idle(messagesWithPending, state.execution.turnIdx)
          )
          state.sessionId.foreach { sid =>
            resources.dispatcher.unsafeRunAndForget(
              (resources.sessionStore.saveMessagesForSession(sid, messagesWithPending) *>
                resources.sessionStore.flushIndex *>
                emitSessionBusy(state.wsSend, sid, busy = true))
                .handleErrorWith(e =>
                  IO(NebflowLogger.forName("nebflow.agent").warn(s"Save/flush session failed: ${e.getMessage}"))
                )
            )
          }
          replyTo.foreach(_ ! AgentEvent.Completed(state.sessionId.getOrElse(""), messagesWithPending))
          pipeLlmCall(
            agentDef,
            resources,
            depth,
            parentRef,
            updatedState,
            stash,
            ctx,
            None
          )
        else
          val updatedState = state.copy(
            execution = ExecutionContext.idle(newMessages, state.execution.turnIdx)
          )
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
        end if
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
      finishTurn = (ad, r, d, p, s, st, c, t, rep, th, streamed, model) =>
        finishTurn(ad, r, d, p, s, st, c, t, rep, th, streamed, model)
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
      finishTurnFn = (ad, r, d, p, s, st, c, t, rep, th, streamed, model) =>
        finishTurn(ad, r, d, p, s, st, c, t, rep, th, streamed, model)
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
    replyDeferred: Option[cats.effect.Deferred[IO, Either[String, CompactionResult]]],
    resumeAfterCompact: Boolean = true
  ): Behavior[AgentCommand] =
    val config = CompactConfig()
    if state.pendingCompaction.isDefined then
      val reason = "Compaction already in progress"
      replyDeferred.foreach { d =>
        resources.dispatcher.unsafeRunAndForget(
          d.complete(Left(reason)).handleErrorWith(_ => IO.unit)
        )
      }
      resources.dispatcher.unsafeRunAndForget(
        emitStreamIO(
          state.wsSend,
          ctx,
          AgentStreamEvent.CompactFailed(reason, state.compactionFailures, config.circuitBreakerMax),
          isSubagent = depth > 0,
          state.sessionId
        ).handleErrorWith(_ => IO.unit)
      )
      Behaviors.same
    else
      // Check retry backoff before circuit breaker
      val backoffOk =
        if state.compactionFailures == 0 then true
        else
          val backoff = config.compactionRetryDelayMs * math.pow(2, state.compactionFailures - 1).toLong
          System.currentTimeMillis() - state.lastCompactionFailureAt >= backoff

      if !backoffOk then
        val err = s"Compaction retry backed off (${state.compactionFailures} failures)"
        replyDeferred.foreach { d =>
          resources.dispatcher.unsafeRunAndForget(d.complete(Left(err)).handleErrorWith(_ => IO.unit))
        }
        Behaviors.same
      else if state.compactionFailures >= config.circuitBreakerMax then
        val err = s"Compaction circuit breaker open after ${state.compactionFailures} attempts"
        replyDeferred.foreach { d =>
          resources.dispatcher.unsafeRunAndForget(d.complete(Left(err)).handleErrorWith(_ => IO.unit))
        }
        resources.dispatcher.unsafeRunAndForget(
          emitStreamIO(
            state.wsSend,
            ctx,
            AgentStreamEvent.CompactFailed(err, state.compactionFailures, config.circuitBreakerMax),
            isSubagent = depth > 0,
            state.sessionId
          ).handleErrorWith(_ => IO.unit)
        )
        Behaviors.same
      else
        val pending = CompactionJob(
          s"compact-${java.util.UUID.randomUUID().toString.take(8)}",
          mode,
          replyDeferred,
          resumeAfterCompact = resumeAfterCompact
        )
        // Fire-and-forget direct compaction via CompactService
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
              Some(state.contextWindow - config.bufferForWindow(state.contextWindow))
            ),
            isSubagent = depth > 0,
            state.sessionId
          ).handleErrorWith(_ => IO.unit)
        )
        processing(agentDef, resources, depth, parentRef, state.withPendingCompaction(Some(pending)), stash, ctx)
      end if
    end if
  end handleTriggerCompaction

  /**
   * Replace tool_result content for the last N rounds of tool calls with a summary.
   * A "round" = one tool_use (in assistant msg) + its corresponding tool_result (in user msg).
   * Rounds are counted from the END of the conversation (1 = most recent).
   *
   * @return Right((updatedMessages, countReplaced)) or Left(errorMessage)
   */
  private def replaceToolResults(
    messages: List[Message],
    rounds: Int,
    summary: String
  ): Either[String, (List[Message], Int)] =
    // Collect all tool_use IDs in reverse order (most recent first)
    val allToolUseIds = messages.flatMap {
      case Message(MessageRole.Assistant, Right(blocks), _) =>
        blocks.collect { case ContentBlock.ToolUse(id, _, _) => id }
      case _ => Nil
    }.reverse

    if allToolUseIds.isEmpty then Left("No tool call results found in conversation")
    else
      val selectedIds = allToolUseIds.take(rounds).toSet
      if selectedIds.isEmpty then Left("Selected rounds exceed available tool calls")
      else
        val (updated, count) = messages.foldLeft((Vector.empty[Message], 0)) {
          case ((acc, c), msg @ Message(MessageRole.User, Right(blocks), _)) =>
            val (newBlocks, nc) = blocks.foldLeft((Vector.empty[ContentBlock], c)) {
              case ((ba, bc), tr: ContentBlock.ToolResult) if selectedIds.contains(tr.toolUseId) =>
                (ba :+ tr.copy(content = s"[Replaced — see RemoveUnnecessary result above]"), bc + 1)
              case ((ba, bc), other) => (ba :+ other, bc)
            }
            (acc :+ msg.copy(content = Right(newBlocks.toList)), nc)
          case ((acc, c), other) => (acc :+ other, c)
        }
        if count == 0 then Left("No matching tool results found")
        else Right((updated.toList, count))
  end replaceToolResults

end AgentActor
