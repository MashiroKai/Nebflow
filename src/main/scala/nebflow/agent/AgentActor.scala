package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.agent.AgentCommand.*
import nebflow.core.*
import nebflow.core.compact.*
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
          pipeLlmCall(
            agentDef,
            resources,
            depth,
            parentRef,
            dedupedState.withMessages(newMessages),
            stash,
            ctx,
            replyTo
          )
        end if

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

      case AgentCommand.TriggerCompaction(mode, replyDeferred) =>
        handleTriggerCompaction(agentDef, resources, depth, parentRef, state, stash, ctx, mode, replyDeferred)

      // --- Background task notification while idle: queue for next turn ---
      case AgentCommand.BackgroundTaskNotification(taskId, description, status, output, exitCode) =>
        logAgentEvent(
          ctx,
          agentDef,
          depth,
          state.sessionId,
          state.sessionName,
          "background-task-notification",
          s"taskId=$taskId status=$status"
        )
        // Emit WebSocket update so UI can update task pill
        emitStream(
          resources.dispatcher,
          state.wsSend,
          ctx,
          AgentStreamEvent.BackgroundTaskUpdate(taskId, description, status),
          isSubagent = depth > 0,
          state.sessionId
        )
        // No pending LLM call — inject notification as user message and start a new turn
        val notificationText = status match
          case "completed" =>
            val exitInfo = exitCode.map(c => s" (exit code $c)").getOrElse("")
            s"[Background task completed] \"$description\" (id: $taskId)$exitInfo:\n$output"
          case "failed" =>
            s"[Background task failed] \"$description\" (id: $taskId):\n$output"
          case _ =>
            s"[Background task stopped] \"$description\" (id: $taskId)"
        val userMsg = Message(MessageRole.User, Left(notificationText))
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
          val updatedState = state.withLatestUsage(result.usage.orElse(state.latestUsage))
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
                    p ! AgentCommand.DelegateResult(ctx.self.path.name, Left(
                      AgentError(ctx.self.path.name, agentDef.name, depth, AgentErrorType.LlmFailed, errMsg)
                    ))
                    Behaviors.stopped
                  case None =>
                    // Should not happen (depth > 0 implies parentRef.isDefined), but handle gracefully
                    finishTurn(
                      agentDef, resources, depth, parentRef, updatedState, stash, ctx,
                      errMsg, replyTo, None, textAlreadyStreamed = false, result.model
                    )
              else if state.pendingCompaction.isDefined || state.compactionFailures >= CompactConfig().circuitBreakerMax then
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
                // Trigger compaction, then retry
                val subId = s"context-manage-${java.util.UUID.randomUUID().toString.take(8)}"
                val io = resources.agentLibrary.get("context-manage").flatMap { defnOpt =>
                  IO(ctx.self ! AgentCommand.CompactionDefLoaded(defnOpt))
                }
                resources.dispatcher.unsafeRunAndForget(
                  io.handleErrorWith { e =>
                    IO(
                      NebflowLogger.forName("nebflow.agent").warn(s"CompactionDefLoaded lookup failed: ${e.getMessage}")
                    ) *>
                      IO(ctx.self ! AgentCommand.CompactionDefLoaded(None))
                  }
                )
                val pending = CompactionJob(subId, "full", None, replyTo)
                resources.dispatcher.unsafeRunAndForget(
                  emitStreamIO(
                    state.wsSend,
                    ctx,
                    AgentStreamEvent.CompactStart(
                      "full",
                      result.usage.map(_.inputTokens),
                      Some(agentDef.contextWindow - CompactConfig().bufferTokens)
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
              // Unknown empty response — treat as error
              logAgentEvent(
                ctx,
                agentDef,
                depth,
                state.sessionId,
                state.sessionName,
                "empty-response",
                s"stopReason=$stopReason usage=${result.usage.map(u => s"in=${u.inputTokens} out=${u.outputTokens}").getOrElse("none")}"
              )
              val errMsg =
                if stopReason.nonEmpty then s"LLM returned empty response (stopReason: $stopReason)"
                else "LLM returned empty response with no content"
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
                  resources.dispatcher.unsafeRunAndForget(
                    state
                      .wsSend(
                        io.circe.Json.obj(
                          "type" -> "error".asJson,
                          "sessionId" -> state.sessionId.asJson,
                          "message" -> errMsg.asJson
                        )
                      )
                      .handleErrorWith(_ => IO.unit)
                  )
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
              resources.dispatcher.unsafeRunAndForget(
                state
                  .wsSend(
                    io.circe.Json.obj(
                      "type" -> "error".asJson,
                      "sessionId" -> state.sessionId.asJson,
                      "message" -> errMsg.asJson
                    )
                  )
                  .handleErrorWith(_ => IO.unit)
              )
              emitStream(
                resources.dispatcher,
                state.wsSend,
                ctx,
                AgentStreamEvent.Done(None),
                isSubagent = false,
                state.sessionId
              )
          end match
          replyTo.foreach(_ ! AgentEvent.Failed(state.sessionId.getOrElse(""), agentError))
          stash.unstashAll(
            idle(
              agentDef,
              resources,
              depth,
              parentRef,
              state.withStatus(AgentStatus.Error(error.getMessage)),
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

        val updatedState = state.copy(
          execution = state.execution.copy(messages = newMessages, interaction = None)
        )
        resources.dispatcher.unsafeRunAndForget(
          persistIfSession(resources, updatedState)
            .handleErrorWith(e =>
              IO(NebflowLogger.forName("nebflow.agent").warn(s"Persist session failed: ${e.getMessage}"))
            )
        )

        if didContextManage then
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
            state.sessionName,
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
                  state.sessionName,
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
                // Reset pressure level — compaction freed tokens, allow re-notification at lower levels
                resources.dispatcher.unsafeRunAndForget(
                  resources.reminderStateRef.update(_.copy(highestPressureLevel = 0))
                )
                // Reset latestUsage so maybeAutoCompact won't re-trigger on stale token counts.
                // Fresh usage data will be populated by the next LLM call.
                pipeLlmCall(
                  agentDef,
                  resources,
                  depth,
                  parentRef,
                  newState.withMessages(compacted).withCompactionFailures(0).withLatestUsage(None),
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
                  state.sessionName,
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
                  // Emergency cleanup: progressively strip tool results, remove old tool messages, then truncate
                  val keep = CompactConfig().emergencyKeepMessages
                  val (cleanedMessages, cleanDesc) = CompactUtils.emergencyClean(state.messages, keep)
                  NebflowLogger
                    .forName("nebflow.agent")
                    .warn(s"Emergency cleanup after compaction failed $failures times: $cleanDesc")

                  logAgentEvent(
                    ctx,
                    agentDef,
                    depth,
                    state.sessionId,
                    state.sessionName,
                    "emergency-cleanup",
                    s"before=${state.messages.size} after=${cleanedMessages.size} detail=$cleanDesc"
                  )

                  // Reset compaction failures so future compaction can be attempted
                  val recoveredState = failedState
                    .copy(
                      execution = state.execution.copy(messages = cleanedMessages),
                      compaction = state.compaction.copy(pendingJob = None, compactionFailures = 0)
                    )

                  parentRef match
                    case Some(p) =>
                      p ! AgentCommand.DelegateResult(ctx.self.path.name, Left(
                        AgentError(ctx.self.path.name, agentDef.name, depth, AgentErrorType.ToolFailed,
                          s"Compaction failed $failures times. $cleanDesc")
                      ))
                      Behaviors.stopped
                    case None =>
                      resources.dispatcher.unsafeRunAndForget(
                        persistIfSession(resources, recoveredState).handleErrorWith(e =>
                          IO(NebflowLogger.forName("nebflow.agent").warn(s"Persist after emergency cleanup failed: ${e.getMessage}"))
                        )
                      )
                      // Notify frontend
                      resources.dispatcher.unsafeRunAndForget(
                        state.wsSend(io.circe.Json.obj(
                          "type" -> "error".asJson,
                          "sessionId" -> state.sessionId.asJson,
                          "message" -> s"Context pressure relief: $cleanDesc".asJson
                        )).handleErrorWith(_ => IO.unit)
                      )
                      emitStream(
                        resources.dispatcher,
                        state.wsSend,
                        ctx,
                        AgentStreamEvent.Done(None),
                        isSubagent = false,
                        state.sessionId
                      )
                      stash.unstashAll(
                        idle(agentDef, resources, depth, parentRef, recoveredState, stash, ctx)
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

      // --- AskUser from tool (Pekko Ask pattern) ---
      case AgentCommand.AskUser(requestId, items, replyTo) =>
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
          case None =>
            // Not for us — forward to sub-agents (a sub-agent may have a pending AskUser)
            state.subagents.values.foreach(_ ! AgentCommand.UserAnswered(answers))
            Behaviors.same

      case AgentCommand.PermissionAnswered(approved) =>
        state.pendingPermission match
          case Some(deferred) =>
            resources.dispatcher.unsafeRunAndForget(deferred.complete(approved).handleErrorWith(_ => IO.unit))
            Behaviors.same
          case None =>
            // Not for us — forward to sub-agents
            state.subagents.values.foreach(_ ! AgentCommand.PermissionAnswered(approved))
            Behaviors.same

      // --- Busy signal for root agents ---
      case AgentCommand.UserInput(_, _, _, _) if depth == 0 && parentRef.isEmpty =>
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
        emitStream(resources.dispatcher, state.wsSend, ctx, AgentStreamEvent.Done(None))
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
                  state.sessionName,
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
                val reason = "context-manage agent not found"
                NebflowLogger.forName("nebflow.agent").warn(s"$reason, skipping compaction")
                pending.replyDeferred.foreach { d =>
                  resources.dispatcher.unsafeRunAndForget(
                    d.complete(Left(reason)).handleErrorWith(_ => IO.unit)
                  )
                }
                resources.dispatcher.unsafeRunAndForget(
                  emitStreamIO(
                    state.wsSend,
                    ctx,
                    AgentStreamEvent.CompactFailed(reason, state.compactionFailures, CompactConfig().circuitBreakerMax),
                    isSubagent = depth > 0,
                    state.sessionId
                  )
                    .handleErrorWith(_ => IO.unit)
                )
                // No sub-agent spawned — pipe next LLM call to avoid deadlock
                pipeLlmCall(
                  agentDef,
                  resources,
                  depth,
                  parentRef,
                  state.withPendingCompaction(None),
                  stash,
                  ctx,
                  pending.replyTo
                )
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
              state.sessionName,
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
        logAgentEvent(ctx, agentDef, depth, state.sessionId, state.sessionName, "stop", "reason=user")
        state.activeStreamFiber.foreach(f =>
          resources.dispatcher.unsafeRunAndForget(f.cancel.handleErrorWith(_ => IO.unit))
        )
        state.subagents.values.foreach(ctx.stop)
        Behaviors.stopped

      case AgentCommand.ClearReadTracker =>
        state.readTracker.foreach(t => resources.dispatcher.unsafeRunAndForget(t.clear()))
        Behaviors.same

      // --- Background task notification while processing: queue for injection after current turn ---
      case AgentCommand.BackgroundTaskNotification(taskId, description, status, output, exitCode) =>
        logAgentEvent(
          ctx,
          agentDef,
          depth,
          state.sessionId,
          state.sessionName,
          "background-task-notification-queued",
          s"taskId=$taskId status=$status"
        )
        // Emit WebSocket update so UI can update task pill immediately
        emitStream(
          resources.dispatcher,
          state.wsSend,
          ctx,
          AgentStreamEvent.BackgroundTaskUpdate(taskId, description, status),
          isSubagent = depth > 0,
          state.sessionId
        )
        val notification = AgentCommand.BackgroundTaskNotification(taskId, description, status, output, exitCode)
        val updatedExec = state.execution.copy(
          pendingNotifications = state.execution.pendingNotifications :+ notification
        )
        processing(agentDef, resources, depth, parentRef, state.copy(execution = updatedExec), stash, ctx)

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
    // For root agent: await Done event delivery to guarantee frontend receives it
    // For sub-agent: fire-and-forget (parent handles the done signal)
    if isSubagent then
      emitStream(
        resources.dispatcher,
        state.wsSend,
        ctx,
        AgentStreamEvent.Done(model),
        isSubagent = true,
        state.sessionId
      )
    else
      val doneJson = AgentStreamEvent.Done(model).toJson(ctx.self.path.name, false, state.sessionId)
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

        // If there are queued background task notifications, inject them as user messages
        // and immediately start a new LLM turn instead of going idle.
        val queuedNotifications = state.execution.pendingNotifications
        if queuedNotifications.nonEmpty && depth == 0 then
          val notificationMessages = queuedNotifications.map { n =>
            val notificationText = n.status match
              case "completed" =>
                val exitInfo = n.exitCode.map(c => s" (exit code $c)").getOrElse("")
                s"[Background task completed] \"${n.description}\" (id: ${n.taskId})$exitInfo:\n${n.output}"
              case "failed" =>
                s"[Background task failed] \"${n.description}\" (id: ${n.taskId}):\n${n.output}"
              case _ =>
                s"[Background task stopped] \"${n.description}\" (id: ${n.taskId})"
            Message(MessageRole.User, Left(notificationText))
          }
          val messagesWithNotifications = newMessages ++ notificationMessages
          logAgentEvent(
            ctx,
            agentDef,
            depth,
            state.sessionId,
            state.sessionName,
            "background-notifications-injected",
            s"count=${queuedNotifications.size}"
          )
          val updatedState = state.copy(
            execution = ExecutionContext.idle(messagesWithNotifications, state.execution.turnIdx)
          )
          // Persist and start new LLM call with injected notifications
          state.sessionId.foreach { sid =>
            resources.dispatcher.unsafeRunAndForget(
              (resources.sessionStore.saveMessagesForSession(sid, messagesWithNotifications) *>
                resources.sessionStore.flushIndex *>
                emitSessionBusy(state.wsSend, sid, busy = true))
                .handleErrorWith(e =>
                  IO(NebflowLogger.forName("nebflow.agent").warn(s"Save/flush session failed: ${e.getMessage}"))
                )
            )
          }
          replyTo.foreach(_ ! AgentEvent.Completed(state.sessionId.getOrElse(""), messagesWithNotifications))
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
    replyDeferred: Option[cats.effect.Deferred[IO, Either[String, CompactionResult]]]
  ): Behavior[AgentCommand] =
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
          AgentStreamEvent.CompactFailed(reason, state.compactionFailures, CompactConfig().circuitBreakerMax),
          isSubagent = depth > 0,
          state.sessionId
        )
          .handleErrorWith(_ => IO.unit)
      )
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
