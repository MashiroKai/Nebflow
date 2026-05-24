package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.agent.AgentCommand.*
import nebflow.core.*
import nebflow.core.ask.AskService
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
    contextWindow: Int = Defaults.ContextWindow,
    projectRoot: Option[String] = None,
    rulesMd: Option[String] = None,
    folderId: Option[String] = None
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
                contextWindow = contextWindow,
                projectRoot = projectRoot,
                rulesMd = rulesMd,
                folderId = folderId
              ),
              stash,
              context
            )
          }
        }
      )
      .onFailure[Exception](SupervisorStrategy.restart.withLimit(2, java.time.Duration.ofSeconds(30)))

  private def buildHookContext(state: AgentState): nebflow.core.hooks.HookContext =
    nebflow.core.hooks.HookContext(
      sessionId = state.sessionId,
      projectRoot = state.projectRoot.getOrElse(""),
      cwd = state.projectRoot.getOrElse("")
    )

  /** Fire Stop + SessionEnd lifecycle hooks (root agent only). Fire-and-forget. */
  private def fireLifecycleStopHooks(resources: SharedResources, state: AgentState): Unit =
    if state.depth == 0 then
      val hookCtx = buildHookContext(state)
      resources.dispatcher.unsafeRunAndForget(
        resources.hookEngine.onStop(hookCtx) *> resources.hookEngine.onSessionEnd(hookCtx)
      )

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
          val stateWithLang =
            if depth == 0 && parentRef.isEmpty && dedupedState.messages.isEmpty then
              LanguageDetector.detect(text) match
                case Some(lang) =>
                  NebflowLogger.forName("nebflow.agent").info(s"Auto-detected language: $lang")
                  dedupedState.withLanguage(Some(lang))
                case None => dedupedState
            else dedupedState

          val userMsg = blocks.filter(_.nonEmpty) match
            case Some(bl) => Message(MessageRole.User, Right(bl))
            case None => Message(MessageRole.User, Left(text))
          val newMessages = stateWithLang.messages :+ userMsg
          // Memory is loaded EveryTurn via TurnContext — no need to preload here
          pipeLlmCall(
            agentDef,
            resources,
            depth,
            parentRef,
            stateWithLang.withMessages(newMessages),
            stash,
            ctx,
            replyTo
          )
        end if

      case AgentCommand.AskQuestion(question, askSessionId) =>
        // Inline /ask: inject ask-reminder into current messages, reuse agent's cached LLM context
        val askReminder = AskService.buildAskReminder(question)
        val askMessages = state.messages :+ askReminder
        // Set askMode so pipeLlmCall knows to skip reminders/micro-compact
        // and LlmComplete handler knows to treat the response as ask-answer
        val askState = state
          .withMessages(askMessages)
          .withAskMode(Some(question))
          .withStatus(AgentStatus.Processing)
        logAgentEvent(ctx, agentDef, depth, state.sessionId, state.sessionName, "ask-start", s"q=${question.take(60)}")
        pipeLlmCall(agentDef, resources, depth, parentRef, askState, stash, ctx, None)

      case AgentCommand.SkillActivate(skillName, input, skillSessionId, skillContent, skillBaseDir) =>
        // Skill activation: prepend skill content + user input as a new user message
        val combinedText = s"<skill name=\"$skillName\">\n$skillContent\n</skill>\n\n$input"
        val userMsg = Message(MessageRole.User, Left(combinedText))
        val newMessages = state.messages :+ userMsg
        // Memory is loaded EveryTurn via TurnContext — no need to preload here
        val processingState = state
          .withMessages(newMessages)
          .withStatus(AgentStatus.Processing)
        // Persist a system bubble showing skill activation
        val sysMsg = nebflow.shared.UiMessage.System(
          s"Using skill: $skillName",
          Some("slash.skillActivated"),
          Some(io.circe.Json.obj("skillName" -> skillName.asJson))
        )
        resources.dispatcher.unsafeRunAndForget(
          resources.sessionStore.appendUiMessages(state.sessionId.getOrElse(""), List(sysMsg))
        )
        logAgentEvent(
          ctx,
          agentDef,
          depth,
          state.sessionId,
          state.sessionName,
          "skill-start",
          s"skill=$skillName input=${input.take(60)}"
        )
        pipeLlmCall(
          agentDef,
          resources,
          depth,
          parentRef,
          processingState,
          stash,
          ctx,
          None
        )

      case AgentCommand.Interrupt() =>
        // Idle state — nothing to interrupt, ignore
        Behaviors.same

      case AgentCommand.Stop(_) =>
        logAgentEvent(ctx, agentDef, depth, state.sessionId, state.sessionName, "stop", "reason=user")
        state.activeStreamFiber.foreach(f =>
          resources.dispatcher.unsafeRunAndForget(f.cancel.handleErrorWith(_ => IO.unit))
        )
        fireLifecycleStopHooks(resources, state)
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
          .withWritesSinceLastRead(Map.empty)
          .withRecentMessageIds(Nil)
          .withCompaction(state.compaction.copy(highestPressureLevel = 0))
        // agentDef is refreshed EveryTurn via TurnContext — no lifecycle refresh needed
        stash.unstashAll(idle(agentDef, resources, depth, parentRef, resetState, stash, ctx))

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
        val newState = state.withContextWindow(window)
        // Model switched: check if current messages exceed the new context window
        val estimatedTokens = TokenEstimator.estimate(newState.messages)
        val config = CompactConfig()
        val threshold = window - config.bufferForWindow(window)
        if newState.messages.nonEmpty && estimatedTokens > threshold then
          logAgentEvent(
            ctx,
            agentDef,
            depth,
            state.sessionId,
            state.sessionName,
            "model-switch-compact",
            s"newWindow=$window estimated=$estimatedTokens threshold=$threshold msgs=${newState.messages.size}"
          )
          handleTriggerCompaction(
            agentDef,
            resources,
            depth,
            parentRef,
            newState,
            stash,
            ctx,
            "full",
            None,
            resumeAfterCompact = false
          )
        else idle(agentDef, resources, depth, parentRef, newState, stash, ctx)
        end if

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

      // --- User answered: route to blocking replyTo ---
      case AgentCommand.UserAnswered(answers) =>
        state.execution.interaction.flatMap(_.pendingAskUserReplyTo) match
          case Some(replyTo) =>
            // Complete blocking AskUserQuestion call
            replyTo ! answers
            val clearedState = state.withInteraction(None)
            idle(agentDef, resources, depth, parentRef, clearedState, stash, ctx)
          case None =>
            Behaviors.same

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
            .withLastModel(result.model.orElse(state.lastModel))
            .updateContextWindowIfNeeded(result.contextWindow)

          // Emit real-time usage update after each LLM round
          val isSubagent = depth > 0
          updatedState.latestUsage.foreach { usage =>
            if !isSubagent then
              emitStream(
                resources.dispatcher,
                state.wsSend,
                ctx,
                AgentStreamEvent.UsageUpdate(usage.inputTokens, updatedState.contextWindow),
                isSubagent = false,
                state.sessionId
              )
          }

          // --- Inline compact mode: pendingCompaction is set ---
          // The LLM has responded to the compact reminder — parse the summary
          if state.pendingCompaction.isDefined && result.toolCalls.isEmpty && result.text.nonEmpty then
            handleCompactResponse(agentDef, resources, depth, parentRef, updatedState, stash, ctx, result.text)
          else if state.pendingCompaction.isDefined && result.toolCalls.nonEmpty then
            // Model tried to call tools despite being told not to — treat as failure
            NebflowLogger
              .forName("nebflow.agent")
              .warn(
                s"Compact model returned tool calls: ${result.toolCalls.map(_.name).mkString(", ")}"
              )
            handleCompactFailure(
              agentDef,
              resources,
              depth,
              parentRef,
              updatedState,
              stash,
              ctx,
              "Compact model unexpectedly called tools"
            )
          // --- Inline ask mode: askMode is set ---
          // The LLM has answered the ask question — capture response, restore state
          else if state.askMode.isDefined && result.toolCalls.isEmpty then
            handleAskComplete(
              agentDef,
              resources,
              depth,
              parentRef,
              updatedState,
              stash,
              ctx,
              result.text,
              result.model
            )
          // Tool calls (normal or ask mode) — execute tools
          else if result.toolCalls.nonEmpty then
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
              result.thinkingSignature,
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
              // Context window exceeded — trigger inline compaction if possible
              if state.pendingCompaction.isDefined || state.compactionFailures >= CompactConfig().circuitBreakerMax
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
                  None,
                  textAlreadyStreamed = false,
                  result.model
                )
              else
                // Trigger inline compaction via pipeLlmCall with compact reminder
                startDirectCompaction(
                  agentDef,
                  resources,
                  depth,
                  parentRef,
                  updatedState,
                  stash,
                  ctx,
                  replyTo,
                  (ad, r, d, p, s, st, c) => processing(ad, r, d, p, s, st, c),
                  "full"
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
                state.sessionId.foreach { sid =>
                  val doneEvent = AgentStreamEvent.Done(result.model)
                  val doneJson = doneEvent.toJson(ctx.self.path.name, false, state.sessionId)
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
                      state.wsSend(doneJson).handleErrorWith(_ => IO.unit) *>
                      emitSessionBusy(state.wsSend, sid, busy = false)
                  )
                }
                stash.unstashAll(
                  idle(agentDef, resources, depth, parentRef, updatedState, stash, ctx)
                )
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
          // Cleanup: cancel active stream fiber to prevent resource leaks
          state.activeStreamFiber.foreach(f =>
            resources.dispatcher.unsafeRunAndForget(f.cancel.handleErrorWith(_ => IO.unit))
          )
          val cleanedState = state
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
            val doneEvent = AgentStreamEvent.Done(None)
            val doneJson = doneEvent.toJson(ctx.self.path.name, false, cleanedState.sessionId)
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
                cleanedState.wsSend(doneJson).handleErrorWith(_ => IO.unit) *>
                emitSessionBusy(cleanedState.wsSend, sid, busy = false)
            )
          }
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
        val toolCalls = tc.results.map((call, _) => call)
        val assistantBlocks = scala.collection.mutable.ListBuffer.empty[ContentBlock]
        tc.thinking.foreach(t => assistantBlocks += ContentBlock.Thinking(t, tc.thinkingSignature))
        if tc.originalText.nonEmpty then assistantBlocks += ContentBlock.Text(tc.originalText)
        toolCalls.foreach(c => assistantBlocks += ContentBlock.ToolUse(c.id, c.name, c.input))
        val assistantMsg = Message(MessageRole.Assistant, Right(assistantBlocks.toList))
        val resultBlocks = tc.results.map { (call, r) =>
          ContentBlock.ToolResult(call.id, r.content, Some(r.isError))
        }
        val resultMsg = Message(MessageRole.User, Right(resultBlocks))
        val baseMessages = tc.compactedMessages.getOrElse(state.messages)

        // Inject pending external events (background tasks, webhooks, etc.) as
        // system-reminders so the LLM sees them in the *current* turn — no extra
        // round-trip needed.  This is the primary injection point; finishTurn is
        // the fallback for events arriving after the last tool round.
        val pendingEvents = state.execution.pendingEvents
        val eventMessages = if pendingEvents.nonEmpty then
          logAgentEvent(
            ctx,
            agentDef,
            depth,
            state.sessionId,
            state.sessionName,
            "pending-events-injected-at-tools-complete",
            s"events=${pendingEvents.size}"
          )
          val remindersText = pendingEvents.map(e => e.payload).mkString("\n\n")
          List(
            Message(
              MessageRole.User,
              Left(
                s"<system-reminder>\n$remindersText\n</system-reminder>"
              )
            )
          )
        else Nil
        val newMessages = baseMessages ++ List(assistantMsg, resultMsg) ++ eventMessages

        val updatedState = state
          .copy(
            execution = state.execution.copy(
              messages = newMessages,
              interaction = None,
              pendingEvents = Nil // consumed
            )
          )
          .withWritesSinceLastRead(tc.updatedWriteTracker)
        resources.dispatcher.unsafeRunAndForget(
          persistIfSession(resources, updatedState)
            .handleErrorWith(e =>
              IO(NebflowLogger.forName("nebflow.agent").warn(s"Persist session failed: ${e.getMessage}"))
            )
        )

        pipeLlmCall(agentDef, resources, depth, parentRef, updatedState, stash, ctx, tc.replyTo)

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
        fireLifecycleStopHooks(resources, state)
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
          .withWritesSinceLastRead(Map.empty)
          .withRecentMessageIds(Nil)
          .withCompaction(state.compaction.copy(highestPressureLevel = 0))
          .resetToIdle(Nil)
        // agentDef is refreshed EveryTurn via TurnContext — no lifecycle refresh needed
        stash.unstashAll(idle(agentDef, resources, depth, parentRef, resetState, stash, ctx))

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
        end match

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
                    extra = Map("preservedRounds" -> "0")
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
              // Memory is loaded EveryTurn via TurnContext — no need to refresh here
              // agentDef is refreshed EveryTurn via TurnContext — no lifecycle refresh needed
              if pending.exists(_.resumeAfterCompact) then
                // Auto/LLM-triggered: resume LLM call with compacted messages
                pipeLlmCall(
                  agentDef,
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
                      IO(
                        NebflowLogger
                          .forName("nebflow.agent")
                          .warn(s"Persist after compact failed: ${e.getMessage}")
                      )
                    )
                )
                stash.unstashAll(idle(agentDef, resources, depth, parentRef, compactedState, stash, ctx))
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
    thinkingSignature: Option[String] = None,
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
      case (Some(t), "") => Right(List(ContentBlock.Thinking(t, thinkingSignature)))
      case (Some(t), txt) => Right(List(ContentBlock.Thinking(t, thinkingSignature), ContentBlock.Text(txt)))
    val newMessages = state.messages :+ Message(MessageRole.Assistant, assistantContent)

    // Check for queued external events BEFORE sending done.
    // If pending events exist, a new LLM round will start — sending done now
    // would cause the frontend to clean up state, then the new round's
    // thinkingDelta would create a stray thinking bubble.
    val queuedEvents = state.execution.pendingEvents
    if queuedEvents.nonEmpty then
      // Send roundComplete to finalize the current round's text bubble
      // WITHOUT ending the turn (no done/sessionBusy(false)).
      // The subsequent pipeLlmCall will produce its own done when complete.
      if !isSubagent then
        state.sessionId.foreach { sid =>
          resources.dispatcher.unsafeRunAndForget(
            state
              .wsSend(io.circe.Json.obj("type" -> "roundComplete".asJson, "sessionId" -> sid.asJson))
              .handleErrorWith(e =>
                IO(NebflowLogger.forName("nebflow.agent").warn(s"roundComplete delivery failed: ${e.getMessage}"))
              )
          )
        }
      // Wrap as system-reminder for consistency with ToolsComplete injection
      val remindersText = queuedEvents.map(_.payload).mkString("\n\n")
      val eventMessages = List(
        Message(
          MessageRole.User,
          Left(
            s"<system-reminder>\n$remindersText\n</system-reminder>"
          )
        )
      )
      val messagesWithPending = newMessages ++ eventMessages
      logAgentEvent(
        ctx,
        agentDef,
        depth,
        state.sessionId,
        state.sessionName,
        "pending-messages-injected",
        s"events=${queuedEvents.size}"
      )
      val updatedState = state.copy(
        execution = ExecutionContext.idle(messagesWithPending, state.execution.turnIdx)
      )
      state.sessionId.foreach { sid =>
        resources.dispatcher.unsafeRunAndForget(
          (resources.sessionStore.saveMessagesForSession(sid, messagesWithPending) *>
            resources.sessionStore.flushIndex)
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
      // No pending events — send done + sessionBusy(false) to end the turn.
      // Build Done event with model, contextWindow, and inputTokens
      val doneEvent = AgentStreamEvent.Done(
        model.orElse(state.lastModel),
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
        val sid = state.sessionId
        val doneJson = doneEvent.toJson(ctx.self.path.name, false, sid)
        NebflowLogger
          .forName("nebflow.agent")
          .info(
            s"finishTurn: sending Done sessionId=${sid.getOrElse("-")} jsonLen=${doneJson.noSpaces.length}"
          )
        // Send Done + sessionBusy(false) in a single IO chain so Done always
        // arrives before sessionBusy.  Otherwise a parallel dispatcher can
        // deliver sessionBusy first, causing the frontend to finalise the AI
        // bubble *without* the model name (finishAi(null) in the sessionBusy
        // handler).
        sid.foreach { sessionId =>
          resources.dispatcher.unsafeRunAndForget(
            (state
              .wsSend(doneJson)
              .handleErrorWith(e =>
                IO(
                  NebflowLogger
                    .forName("nebflow.agent")
                    .warn(s"finishTurn: Done event delivery failed: ${e.getMessage}")
                )
              ) *>
              emitSessionBusy(state.wsSend, sessionId, busy = false))
              .handleErrorWith(e =>
                IO(
                  NebflowLogger
                    .forName("nebflow.agent")
                    .warn(s"finishTurn: Done+sessionBusy chain failed: ${e.getMessage}")
                )
              )
          )
        }
      end if

      val updatedState = state.copy(
        execution = ExecutionContext.idle(newMessages, state.execution.turnIdx)
      )
      state.sessionId.foreach { sid =>
        resources.dispatcher.unsafeRunAndForget(
          (resources.sessionStore.saveMessagesForSession(sid, newMessages) *>
            resources.sessionStore.flushIndex)
            .handleErrorWith(e =>
              IO(NebflowLogger.forName("nebflow.agent").warn(s"Save/flush session failed: ${e.getMessage}"))
            )
        )
      }
      replyTo.foreach(_ ! AgentEvent.Completed(state.sessionId.getOrElse(""), newMessages))
      stash.unstashAll(idle(agentDef, resources, depth, parentRef, updatedState, stash, ctx))
    end if
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
      finishTurn = (ad, r, d, p, s, st, c, t, rep, th, thSig, streamed, model) =>
        finishTurn(ad, r, d, p, s, st, c, t, rep, th, thSig, streamed, model)
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
      finishTurnFn = (ad, r, d, p, s, st, c, t, rep, th, thSig, streamed, model) =>
        finishTurn(ad, r, d, p, s, st, c, t, rep, th, thSig, streamed, model)
    )

  /** Handle successful compact response — parse summary, replace messages, optionally resume. */
  private def handleCompactResponse(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    stash: StashBuffer[AgentCommand],
    ctx: ActorContext[AgentCommand],
    responseText: String
  ): Behavior[AgentCommand] =
    val pending = state.pendingCompaction
    val sessionId = state.sessionId.getOrElse(ctx.self.path.name)

    // Fetch recently-read file paths for file restoration
    val readPathsIO = state.readTracker
      .map(_.recentFiles(CompactConfig().postCompactMaxFiles).map(_.map(_.toString)))
      .getOrElse(IO.pure(Nil))

    val hookIO = CompactService.runPreCompactHook(state.messages, resources, sessionId)

    // Re-resolve projectRoot so folder settings changes take effect immediately
    val rootIO: IO[String] = state.folderId match
      case Some(fid) =>
        resources.sessionStore.resolveProjectRoot(Some(fid)).map { rr =>
          rr.getOrElse(resources.projectRoot.toString)
        }
      case None => IO.pure(resources.projectRoot.toString)

    resources.dispatcher.unsafeRunAndForget(
      (readPathsIO, hookIO, rootIO)
        .mapN { (readPaths, hookResult, effectiveRoot) =>
          (readPaths, hookResult, effectiveRoot)
        }
        .flatMap { (readPaths, hookResult, effectiveRoot) =>
          hookResult match
            case Left(reason) =>
              // Hook blocked compaction
              IO(ctx.self ! AgentCommand.CompactionComplete(Left(reason)))
            case Right(_) =>
              val result = FullCompact.parseResponse(
                responseText,
                state.messages,
                effectiveRoot,
                readPaths
              )
              result match
                case Left(err) =>
                  IO(ctx.self ! AgentCommand.CompactionComplete(Left(err)))
                case Right(compactedMessages) =>
                  // Run post-compact hook (fire-and-forget)
                  resources.dispatcher.unsafeRunAndForget(
                    CompactService
                      .runPostCompactHook(
                        state.messages.size,
                        compactedMessages.size,
                        resources,
                        sessionId
                      )
                      .handleErrorWith(_ => IO.unit)
                  )

                  IO(ctx.self ! AgentCommand.CompactionComplete(Right(compactedMessages)))
              end match
        }
        .handleError { e =>
          IO(ctx.self ! AgentCommand.CompactionComplete(Left(e.getMessage)))
        }
        .void
    )
    // Stay in processing state until CompactionComplete arrives
    processing(agentDef, resources, depth, parentRef, state, stash, ctx)
  end handleCompactResponse

  /** Handle compact failure — increment failure counter, emit event, report error or retry. */
  private def handleCompactFailure(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    stash: StashBuffer[AgentCommand],
    ctx: ActorContext[AgentCommand],
    err: String
  ): Behavior[AgentCommand] =
    val pending = state.pendingCompaction
    val now = System.currentTimeMillis()

    logAgentEvent(ctx, agentDef, depth, state.sessionId, state.sessionName, "compaction-failed", s"err=$err")

    resources.dispatcher.unsafeRunAndForget(
      emitStreamIO(
        state.wsSend,
        ctx,
        AgentStreamEvent.CompactFailed(err, state.compactionFailures + 1, CompactConfig().circuitBreakerMax),
        isSubagent = depth > 0,
        state.sessionId
      ).handleErrorWith(_ => IO.unit)
    )

    // Complete any pending replyDeferred
    pending.foreach(_.replyDeferred.foreach { d =>
      resources.dispatcher.unsafeRunAndForget(d.complete(Left(err)).handleErrorWith(_ => IO.unit))
    })

    val failedState = state
      .withPendingCompaction(None)
      .withCompactionFailures(state.compactionFailures + 1)
      .withLastCompactionFailureAt(now)

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
          None,
          textAlreadyStreamed = false,
          None
        )
      case None =>
        processing(agentDef, resources, depth, parentRef, failedState, stash, ctx)
    end match
  end handleCompactFailure

  /**
   * Handle /ask completion — send answer to frontend, persist UiMessage,
   * restore original messages, return to idle.
   */
  private def handleAskComplete(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    stash: StashBuffer[AgentCommand],
    ctx: ActorContext[AgentCommand],
    answerText: String,
    model: Option[String]
  ): Behavior[AgentCommand] =
    val question = state.askMode.getOrElse("")
    val sessionId = state.sessionId.getOrElse(ctx.self.path.name)
    val durationMs = 0L // TODO: track start time if needed

    logAgentEvent(
      ctx,
      agentDef,
      depth,
      state.sessionId,
      state.sessionName,
      "ask-complete",
      s"q=${question.take(40)} a=${answerText.take(40)}"
    )

    // Send askDone + sessionBusy(false). Do NOT send a 'done' event — it would
    // cause the frontend done handler + makeRecordingWsSend to save ask-mode
    // thinking as a separate AI message (stray thinking fragment). askDone alone
    // signals completion to the frontend.
    resources.dispatcher.unsafeRunAndForget(
      (state
        .wsSend(
          io.circe.Json.obj(
            "type" -> "askDone".asJson,
            "sessionId" -> sessionId.asJson,
            "durationMs" -> durationMs.asJson,
            "model" -> model.getOrElse("").asJson
          )
        )
        .handleErrorWith(_ => IO.unit)
        *> emitSessionBusy(state.wsSend, sessionId, busy = false))
    )

    // Persist UiMessage.Ask
    resources.dispatcher.unsafeRunAndForget(
      resources.sessionStore
        .appendUiMessages(sessionId, List(UiMessage.Ask(question, answerText, Some(durationMs), model)))
        .handleErrorWith(e => IO(logger.warn(s"Failed to persist ask UiMessage: ${e.getMessage}")))
    )

    // Restore original messages: strip the ask-reminder and any tool exchanges that happened
    // during the ask turn. The ask messages were: original + ask-reminder + [tool exchanges].
    // We keep only the original messages.
    val originalMessages = state.messages.takeWhile { m =>
      // Drop everything from (and including) the ask-reminder onward
      !isAskReminder(m)
    }
    // If we couldn't find the ask-reminder (shouldn't happen), keep all messages minus the last few
    val restoredMessages =
      if originalMessages.size == state.messages.size then
        // Fallback: just keep messages before ask. The ask-reminder was appended by AskQuestion handler.
        state.messages.dropRight(1)
      else originalMessages

    // Clear askMode and return to idle with original messages restored
    idle(
      agentDef,
      resources,
      depth,
      parentRef,
      state
        .withAskMode(None)
        .withMessages(restoredMessages)
        .resetToIdle(restoredMessages),
      stash,
      ctx
    )
  end handleAskComplete

  /** Check if a message is the ask-reminder injected by AskQuestion handler. */
  private def isAskReminder(msg: Message): Boolean =
    msg.role == MessageRole.User && (msg.content match
      case Left(text) => text.contains("<system-reminder>") && text.contains("ephemeral follow-up question")
      case _ => false)

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
          val elapsed = System.currentTimeMillis() - state.lastCompactionFailureAt
          config.isBackoffSatisfied(state.compactionFailures, elapsed)

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
        // Trigger inline compaction via pipeLlmCall with compact reminder
        startDirectCompaction(
          agentDef,
          resources,
          depth,
          parentRef,
          state,
          stash,
          ctx,
          None, // replyTo not used for manual /compact
          (ad, r, d, p, s, st, c) => processing(ad, r, d, p, s, st, c),
          mode
        )
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
    end if
  end replaceToolResults

end AgentActor
