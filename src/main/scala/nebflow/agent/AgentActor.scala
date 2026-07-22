package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.*
import nebflow.agent.AgentCommand.*
import nebflow.core.*
import nebflow.core.ask.AskService
import nebflow.core.compact.*
import nebflow.core.tools.AskUserQuestionTool
import nebflow.llm.FallbackExhaustedError
import nebflow.service.StrengthStore
import nebflow.shared.*
import nebflow.shared.given

import scala.concurrent.duration.*

object AgentActor extends AgentCore with AgentSession:

  private val MaxEmptyResponseRetries = 5
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
    liveFileTracker: Option[nebflow.core.tools.LiveFileTracker] = None,
    contextWindow: Int = Defaults.ContextWindow,
    projectRoot: Option[String] = None,
    rulesMd: Option[String] = None,
    folderId: Option[String] = None,
    safetyMode: String = "confirm-edits",
    gitBranch: Option[String] = None,
    expectsMail: Boolean = false
  ): Behavior[AgentCommand] =
    Behaviors.setup { ctx =>
      logAgentEvent(
        agentDef,
        depth,
        sessionId,
        sessionName,
        "spawn",
        s"parent=${parentRef.map(_.path.name).getOrElse("-")} msgs=${initialMessages.size}"
      )(using ctx)
      IO.pure(
        idle(
          agentDef,
          resources,
          depth,
          parentRef,
          AgentState(
            messages = initialMessages,
            status = AgentStatus.Idle,
            depth = depth,
            sessionId = sessionId,
            sessionName = sessionName,
            pendingCompaction = None,
            latestUsage = None,
            pendingAskUser = None,
            pendingPermission = None,
            wsSend = wsSend,
            readTracker = readTracker,
            fileHistory = fileHistory,
            liveFileTracker = liveFileTracker,
            contextWindow = contextWindow,
            projectRoot = projectRoot,
            rulesMd = rulesMd,
            folderId = folderId,
            safetyMode = safetyMode,
            gitBranch = gitBranch,
            expectsMail = expectsMail
          )
        )(using ctx)
      )
    }

  private def buildHookContext(state: AgentState): nebflow.core.hooks.HookContext =
    nebflow.core.hooks.HookContext(
      sessionId = state.sessionId,
      projectRoot = state.projectRoot.getOrElse(""),
      cwd = state.projectRoot.getOrElse("")
    )

  private def fireLifecycleStopHooks(resources: SharedResources, state: AgentState)(using
    ctx: ActorContext[AgentCommand]
  ): IO[Unit] =
    if state.depth == 0 then
      val hookCtx = buildHookContext(state)
      ctx.forkTurn(resources.hookEngine.onStop(hookCtx) *> resources.hookEngine.onSessionEnd(hookCtx))
    else IO.unit

  // ============================================================
  // Idle state
  // ============================================================

  private def idle(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState
  )(using ctx: ActorContext[AgentCommand]): Behavior[AgentCommand] =
    Behaviors.receiveMessage:

      case AgentCommand.UserInput(text, replyTo, clientMessageId, blocks, chatWidth) =>
        val (isDuplicate, dedupedState) = checkDuplicate(clientMessageId, state)
        if isDuplicate then
          logger.info(s"Dropping duplicate message with clientMessageId=${clientMessageId.getOrElse("")}")
          IO.pure(idle(agentDef, resources, depth, parentRef, state))
        else
          logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "start", s"msgs=${state.messages.size}")
          val stateWithLang =
            if depth == 0 && parentRef.isEmpty && dedupedState.messages.isEmpty then
              LanguageDetector.detect(text) match
                case Some(lang) =>
                  NebflowLogger.forName("nebflow.agent").info(s"Auto-detected language: $lang")
                  dedupedState.withLanguage(Some(lang))
                case None => dedupedState
            else dedupedState
          val stateWithWidth =
            if chatWidth > 0 then stateWithLang.copy(session = stateWithLang.session.copy(chatWidth = chatWidth))
            else stateWithLang
          val userMsg = blocks.filter(_.nonEmpty) match
            case Some(bl) => Message(MessageRole.User, Right(bl))
            case None => Message(MessageRole.User, Left(text))
          val newMessages = stateWithWidth.messages :+ userMsg
          val sessionBusyIO =
            if depth == 0 then
              stateWithWidth.sessionId.fold(IO.unit)(sid => emitSessionBusy(stateWithWidth.wsSend, sid, busy = true))
            else IO.unit
          for
            _ <- sessionBusyIO
            result <- pipeLlmCall(
              agentDef,
              resources,
              depth,
              parentRef,
              stateWithWidth.withMessages(newMessages).withEmptyResponseRetries(0),
              replyTo
            )
          yield result
        end if

      case AgentCommand.AskQuestion(question, askSessionId) =>
        logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "ask-start", s"q=${question.take(60)}")
        val askReminder = AskService.buildAskReminder(question)
        val askState = state
          .withMessages(state.messages :+ askReminder)
          .withAskMode(Some(question))
          .withStatus(AgentStatus.Processing)
        pipeLlmCall(agentDef, resources, depth, parentRef, askState, None)

      case AgentCommand.SkillActivate(skillName, input, skillSessionId, skillContent, skillBaseDir) =>
        logAgentEvent(
          agentDef,
          depth,
          state.sessionId,
          state.sessionName,
          "skill-start",
          s"skill=$skillName input=${input.take(60)}"
        )
        val combinedText = s"<skill name=\"$skillName\">\n$skillContent\n</skill>\n\n$input"
        val processingState = state
          .withMessages(state.messages :+ Message(MessageRole.User, Left(combinedText)))
          .withStatus(AgentStatus.Processing)
        val sessionBusyIO2 =
          if depth == 0 then state.sessionId.fold(IO.unit)(sid => emitSessionBusy(state.wsSend, sid, busy = true))
          else IO.unit
        for
          _ <- sessionBusyIO2
          result <- pipeLlmCall(agentDef, resources, depth, parentRef, processingState, None)
        yield result

      case AgentCommand.Interrupt() =>
        IO.pure(idle(agentDef, resources, depth, parentRef, state))

      case AgentCommand.Stop(_) =>
        for
          _ <- ctx.cancelCurrentTurn()

          _ <- fireLifecycleStopHooks(resources, state)
        yield Behaviors.stopped

      case AgentCommand.ClearReadTracker =>
        state.readTracker.fold(IO.unit)(t => t.clear()) *>
          IO.pure(idle(agentDef, resources, depth, parentRef, state))

      case AgentCommand.ResetSession =>
        for
          _ <- ctx.cancelCurrentTurn()

          _ <- state.readTracker.fold(IO.unit)(t => t.clear())
        yield
          val resetState = state
            .withMessages(Nil)
            .withLatestUsage(None)
            .withPendingCompaction(None)
            .withCompactionFailures(0)
            .withLastCompactionFailureAt(0L)
            .withRecentMessageIds(Nil)
          idle(agentDef, resources, depth, parentRef, resetState)

      case AgentCommand.TriggerCompaction(mode, replyDeferred, postCompactInstruction) =>
        handleTriggerCompaction(
          agentDef,
          resources,
          depth,
          parentRef,
          state,
          mode,
          replyDeferred,
          resumeAfterCompact = postCompactInstruction.isDefined,
          postCompactInstruction = postCompactInstruction
        )

      case AgentCommand.UpdateContextWindow(window) =>
        val newState = state.withContextWindow(window)
        val estimatedTokens = TokenEstimator.estimate(newState.messages)
        val config = CompactConfig()
        val threshold = window - config.bufferForWindow(window)
        if newState.messages.nonEmpty && estimatedTokens > threshold then
          logAgentEvent(
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
            "full",
            None,
            resumeAfterCompact = false
          )
        else IO.pure(idle(agentDef, resources, depth, parentRef, newState))
        end if

      case n: AgentCommand.BackgroundTaskNotification =>
        (ctx.self ! n.toExternalEvent) *> IO.pure(idle(agentDef, resources, depth, parentRef, state))

      case AgentCommand.ExternalEvent(source, eventType, payload, metadata, correlationId) =>
        logAgentEvent(
          agentDef,
          depth,
          state.sessionId,
          state.sessionName,
          "external-event",
          s"source=$source type=$eventType"
        )
        for
          _ <- emitStream(
            state.wsSend,
            AgentStreamEvent.ExternalEventReceived(source, eventType, correlationId),
            isSubagent = depth > 0,
            state.sessionId
          )
          result <- pipeLlmCall(
            agentDef,
            resources,
            depth,
            parentRef,
            state.withMessages(state.messages :+ Message(MessageRole.User, Left(payload))),
            None
          )
        yield result
        end for

      case AgentCommand.UserAnswered(answers) =>
        state.execution.interaction.flatMap(_.pendingAskUserReplyTo) match
          case Some(replyTo) =>
            (replyTo ! answers) *> IO.pure(idle(agentDef, resources, depth, parentRef, state.withInteraction(None)))
          case None => IO.pure(idle(agentDef, resources, depth, parentRef, state))

      case AgentCommand.CompactionComplete(result) =>
        logAgentEvent(
          agentDef,
          depth,
          state.sessionId,
          state.sessionName,
          "stale-compaction-discarded",
          result.fold(err => s"err=${err.take(60)}", msgs => s"ok=${msgs.size}msgs")
        )
        state.pendingCompaction.flatMap(_.replyDeferred) match
          case Some(d) =>
            ctx.forkTurn(
              d.complete(Left("Compaction result arrived after agent returned to idle"))
                .void
                .handleErrorWith(_ => IO.unit)
            ) *> IO.pure(idle(agentDef, resources, depth, parentRef, state))
          case None => IO.pure(idle(agentDef, resources, depth, parentRef, state))

      case AgentCommand.PermissionAnswered(approved) =>
        state.pendingPermission match
          case Some(deferred) =>
            deferred.complete(approved).void.handleErrorWith(_ => IO.unit) *>
              IO.pure(idle(agentDef, resources, depth, parentRef, state))
          case None => IO.pure(idle(agentDef, resources, depth, parentRef, state))

      case AgentCommand.ForwardPermission(deferred, permJson) =>
        // Sub-agent forwarded a permission request — store deferred and ask frontend
        if state.pendingPermission.isDefined then
          deferred.complete(false).void.handleErrorWith(_ => IO.unit) *>
            IO.pure(idle(agentDef, resources, depth, parentRef, state))
        else
          state.wsSend(permJson).handleErrorWith(_ => IO.unit) *>
            IO.pure(idle(agentDef, resources, depth, parentRef, state.withPendingPermission(Some(deferred))))

      case AgentCommand.SetSafetyMode(mode) =>
        IO.pure(
          idle(agentDef, resources, depth, parentRef, state.withSafetyMode(nebflow.core.SafetyMode.toString(mode)))
        )

      case AgentCommand.StartPlan(task) =>
        logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "plan-start", s"task=${task.take(60)}")
        val projectRootStr = state.projectRoot.getOrElse(resources.projectRoot.toString)
        // Plan mode is Nebula's own capability — uses Explorer (read-only) for safe planning.
        resources.agentLibrary.get("Explorer").flatMap {
          case Some(explorerDef) =>
            val planningDef = explorerDef.copy(
              systemPrompt = PlanAgent.PlanningPrompt + "\n\n" + explorerDef.systemPrompt
            )
            PlanAgent
              .spawn(
                agentDef = planningDef,
                task = task,
                mainAgentRef = ctx.self,
                system = ctx.system,
                resources = resources,
                parentDepth = depth,
                wsSend = state.wsSend,
                projectRoot = projectRootStr,
                parentSessionId = state.sessionId
              )
              .map { planAgentRef =>
                val planState = PlanModeState(planAgentRef = planAgentRef, taskDescription = task)
                planWaiting(agentDef, resources, depth, parentRef, state.withPlanMode(Some(planState)))
              }
          case None =>
            ctx
              .forkTurn(
                state
                  .wsSend(
                    Json.obj(
                      "type" -> "error".asJson,
                      "sessionId" -> state.sessionId.asJson,
                      "message" -> "Explorer agent not found — cannot start plan mode".asJson
                    )
                  )
                  .handleErrorWith(_ => IO.unit)
              )
              .as(idle(agentDef, resources, depth, parentRef, state))
        }

      case _: AgentCommand.LlmComplete | _: AgentCommand.LlmFailed | _: AgentCommand.ToolsComplete |
          _: AgentCommand.SetPermissionDeferred | _: AgentCommand.ReplaceToolResults |
          _: AgentCommand.UpdateGitBranch =>
        IO.pure(idle(agentDef, resources, depth, parentRef, state))

      // Immediate input arriving in idle (turn already finished) — treat as normal UserInput
      case AgentCommand.ImmediateInput(text, blocks) =>
        ctx.self ! AgentCommand.UserInput(text, None, None, blocks, 0)
        IO.pure(idle(agentDef, resources, depth, parentRef, state))

      case _ =>
        IO.pure(idle(agentDef, resources, depth, parentRef, state))
  end idle
  // ============================================================
  // Plan waiting state — main agent blocked while plan agent works
  // ============================================================

  private def planWaiting(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState
  )(using ctx: ActorContext[AgentCommand]): Behavior[AgentCommand] =
    Behaviors.receiveMessage:

      case AgentCommand.PlanTurnComplete(planText) =>
        val updatedPlanState = state.planMode.map(_.copy(currentPlanText = planText))
        for _ <- ctx.forkTurn(
            state
              .wsSend(
                Json.obj(
                  "type" -> "planReady".asJson,
                  "sessionId" -> state.sessionId.asJson
                )
              )
              .handleErrorWith(_ => IO.unit)
          )
        yield planWaiting(agentDef, resources, depth, parentRef, state.withPlanMode(updatedPlanState))

      case AgentCommand.PlanFeedback(text) =>
        state.planMode match
          case Some(pm) =>
            logAgentEvent(
              agentDef,
              depth,
              state.sessionId,
              state.sessionName,
              "plan-feedback",
              s"text=${text.take(60)}"
            )
            (pm.planAgentRef ! AgentCommand.UserInput(text)) *>
              IO.pure(planWaiting(agentDef, resources, depth, parentRef, state))
          case None =>
            IO.pure(planWaiting(agentDef, resources, depth, parentRef, state))

      case AgentCommand.PlanApproved =>
        state.planMode match
          case Some(pm) =>
            logAgentEvent(
              agentDef,
              depth,
              state.sessionId,
              state.sessionName,
              "plan-approved",
              s"textLen=${pm.currentPlanText.length}"
            )
            for
              _ <- ctx.system.stop(pm.planAgentRef).handleErrorWith(_ => IO.unit)
              _ <- ctx.forkTurn(
                state
                  .wsSend(
                    Json.obj(
                      "type" -> "planEnd".asJson,
                      "sessionId" -> state.sessionId.asJson,
                      "reason" -> "approved".asJson
                    )
                  )
                  .handleErrorWith(_ => IO.unit)
              )
              // Inject approved plan as user message so the main agent executes it
              planMsg = Message(
                MessageRole.User,
                Left(
                  s"User requested planning for: ${pm.taskDescription}\n\n" +
                    s"The plan below has been approved by the user. Execute it now.\n\n" +
                    s"## Approved Plan\n\n${pm.currentPlanText}\n\n" +
                    s"Start by creating tasks with TaskCreate to track progress, then execute each step."
                )
              )
              newMessages = state.messages :+ planMsg
              sessionBusyIO = state.sessionId.fold(IO.unit)(sid => emitSessionBusy(state.wsSend, sid, busy = true))
              result <- sessionBusyIO *> pipeLlmCall(
                agentDef,
                resources,
                depth,
                parentRef,
                state.withMessages(newMessages).withPlanMode(None),
                None
              )
            yield result
            end for
          case None =>
            IO.pure(idle(agentDef, resources, depth, parentRef, state))

      case AgentCommand.PlanCancelled =>
        state.planMode match
          case Some(pm) =>
            logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "plan-cancelled", "")
            for
              _ <- ctx.system.stop(pm.planAgentRef).handleErrorWith(_ => IO.unit)
              _ <- ctx.forkTurn(
                state
                  .wsSend(
                    Json.obj(
                      "type" -> "planEnd".asJson,
                      "sessionId" -> state.sessionId.asJson,
                      "reason" -> "cancelled".asJson
                    )
                  )
                  .handleErrorWith(_ => IO.unit)
              )
            yield idle(agentDef, resources, depth, parentRef, state.withPlanMode(None))
          case None =>
            IO.pure(idle(agentDef, resources, depth, parentRef, state))

      case AgentCommand.PlanFailed(error) =>
        logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "plan-failed", s"err=${error.take(80)}")
        state.planMode.foreach(pm => ctx.system.stop(pm.planAgentRef).handleErrorWith(_ => IO.unit))
        for
          _ <- ctx.forkTurn(
            state
              .wsSend(
                Json.obj(
                  "type" -> "planEnd".asJson,
                  "sessionId" -> state.sessionId.asJson,
                  "reason" -> "failed".asJson,
                  "error" -> error.asJson
                )
              )
              .handleErrorWith(_ => IO.unit)
          )
          _ <- ctx.forkTurn(
            state.sessionId.fold(IO.unit)(sid =>
              state
                .wsSend(
                  Json.obj(
                    "type" -> "error".asJson,
                    "sessionId" -> sid.asJson,
                    "message" -> s"Plan agent failed: $error".asJson
                  )
                )
                .handleErrorWith(_ => IO.unit)
            )
          )
        yield idle(agentDef, resources, depth, parentRef, state.withPlanMode(None))
        end for

      case AgentCommand.Interrupt() =>
        state.planMode.foreach(pm => ctx.system.stop(pm.planAgentRef).handleErrorWith(_ => IO.unit))
        for _ <- ctx.forkTurn(
            state
              .wsSend(
                Json.obj(
                  "type" -> "planEnd".asJson,
                  "sessionId" -> state.sessionId.asJson,
                  "reason" -> "cancelled".asJson
                )
              )
              .handleErrorWith(_ => IO.unit)
          )
        yield idle(agentDef, resources, depth, parentRef, state.withPlanMode(None))

      case AgentCommand.Stop(_) =>
        state.planMode.foreach(pm => ctx.system.stop(pm.planAgentRef).handleErrorWith(_ => IO.unit))
        for _ <- fireLifecycleStopHooks(resources, state)
        yield Behaviors.stopped

      case AgentCommand.SetSafetyMode(mode) =>
        IO.pure(
          planWaiting(
            agentDef,
            resources,
            depth,
            parentRef,
            state.withSafetyMode(nebflow.core.SafetyMode.toString(mode))
          )
        )

      // Buffer user messages while planning
      case msg: AgentCommand.UserInput =>
        IO.pure(planWaiting(agentDef, resources, depth, parentRef, state))

      case _ =>
        IO.pure(planWaiting(agentDef, resources, depth, parentRef, state))

  end planWaiting
  // ============================================================
  // Processing state
  // ============================================================

  private def processing(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    pending: List[AgentCommand] = Nil
  )(using ctx: ActorContext[AgentCommand]): Behavior[AgentCommand] =
    val base = Behaviors.receiveMessage[AgentCommand]:

      case AgentCommand.UpdateGitBranch(branch) =>
        IO.pure(processing(agentDef, resources, depth, parentRef, state.withGitBranch(branch), pending))

      // --- LLM completed ---
      case LlmComplete(result, replyTo, turnId) =>
        if turnId != state.currentTurnId then
          logAgentEvent(
            agentDef,
            depth,
            state.sessionId,
            state.sessionName,
            "stale-llm-complete-discarded",
            s"turnId=$turnId current=${state.currentTurnId}"
          )
          IO.pure(processing(agentDef, resources, depth, parentRef, state, pending))
        else
          val updatedState = state
            .withLatestUsage(result.usage.orElse(state.latestUsage))
            .withLastModel(result.model.orElse(state.lastModel))
            .updateContextWindowIfNeeded(result.contextWindow)
          val isSubagent = depth > 0
          val usageEvent: IO[Unit] =
            if !isSubagent then
              updatedState.latestUsage.fold(IO.unit)(usage =>
                emitStream(
                  state.wsSend,
                  AgentStreamEvent.UsageUpdate(
                    usage.inputTokens,
                    updatedState.contextWindow,
                    CompactConfig().compactionTriggerRatio(updatedState.contextWindow)
                  ),
                  isSubagent = false,
                  state.sessionId
                )
              )
            else IO.unit
          usageEvent *> handleLlmCompleteBranch(
            agentDef,
            resources,
            depth,
            parentRef,
            updatedState,
            replyTo,
            result,
            pending
          )
        end if

      case LlmFailed(error, replyTo, turnId) =>
        if turnId != state.currentTurnId then
          logAgentEvent(
            agentDef,
            depth,
            state.sessionId,
            state.sessionName,
            "stale-llm-failed-discarded",
            s"turnId=$turnId current=${state.currentTurnId}"
          )
          IO.pure(processing(agentDef, resources, depth, parentRef, state, pending))
        else
          val cleanedState = state
          logAgentEvent(
            agentDef,
            depth,
            state.sessionId,
            state.sessionName,
            "llm-fail",
            s"err=${error.getMessage.take(80)}"
          )
          val agentError =
            AgentError(ctx.self.path.name, agentDef.name, depth, AgentErrorType.LlmFailed, error.getMessage)
          val errMsg = error match
            case e: FallbackExhaustedError =>
              val attempts = e.attempts
                .map { a =>
                  val detail = a.message.getOrElse(a.reason.map(_.toString).getOrElse("unknown"))
                  s"${a.providerId}/${a.model}: $detail"
                }
                .mkString("; ")
              s"All providers failed: $attempts"
            case e: ToolPipelineError =>
              e.message
            case _ =>
              Option(error.getMessage)
                .filter(_.nonEmpty)
                .map(m => s"LLM request failed: ${m.take(200)}")
                .getOrElse(s"LLM request failed: ${error.getClass.getSimpleName}")
          for

            _ <- cleanedState.sessionId.fold(IO.unit) { sid =>
              val doneEvent = AgentStreamEvent.Done(None)
              val doneJson = doneEvent.toJson(ctx.self.path.name, false, cleanedState.sessionId)
              ctx.forkTurn(
                (cleanedState
                  .wsSend(
                    Json.obj(
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
            _ <- replyTo.traverse_(_ ! AgentEvent.Failed(cleanedState.sessionId.getOrElse(""), agentError))
          yield idle(
            agentDef,
            resources,
            depth,
            parentRef,
            cleanedState.withStatus(AgentStatus.Error(error.getMessage))
          )
          end for
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
        val pendingEvents = state.execution.pendingEvents
        val eventMessages = if pendingEvents.nonEmpty then
          logAgentEvent(
            agentDef,
            depth,
            state.sessionId,
            state.sessionName,
            "pending-events-injected-at-tools-complete",
            s"events=${pendingEvents.size}"
          )
          val remindersText = pendingEvents.map(e => e.payload).mkString("\n\n")
          List(Message(MessageRole.User, Left(s"<system-reminder>\n$remindersText\n</system-reminder>")))
        else Nil
        // Inject queued immediate user inputs alongside tool results
        val immInputs = state.execution.pendingImmediateInputs
        val immediateMessages = if immInputs.nonEmpty then
          logAgentEvent(
            agentDef,
            depth,
            state.sessionId,
            state.sessionName,
            "immediate-inputs-injected-at-tools-complete",
            s"count=${immInputs.size}"
          )
          immInputs.map(imm =>
            imm.blocks match
              case Some(blocks) if blocks.nonEmpty => Message(MessageRole.User, Right(blocks))
              case _ => Message(MessageRole.User, Left(imm.text))
          )
        else Nil
        val newMessages = baseMessages ++ List(assistantMsg, resultMsg) ++ eventMessages ++ immediateMessages
        // Increment delegate count for Delegate/ExecuteFlow calls
        val delegateIncrement = toolCalls.count(c => c.name == "Delegate" || c.name == "ExecuteFlow")
        val newDelegateCount = state.delegateCount + delegateIncrement
        // Record strength reads for SKILL.md / memory detail file reads
        toolCalls.foreach { call =>
          if call.name == "Read" then
            call.input("file_path").flatMap(_.asString).foreach { path =>
              StrengthStore.recordReadIfTracked(path, newDelegateCount)
            }
        }
        val updatedState =
          state.copy(execution =
            state.execution
              .copy(
                messages = newMessages,
                interaction = None,
                pendingEvents = Nil,
                pendingImmediateInputs = Nil,
                delegateCount = newDelegateCount
              )
          )
        for
          _ <- ctx.forkTurn(
            persistIfSession(resources, updatedState)
              .handleErrorWith(e =>
                IO(NebflowLogger.forName("nebflow.agent").warn(s"Persist session failed: ${e.getMessage}"))
              )
          )
          result <- pipeLlmCall(agentDef, resources, depth, parentRef, updatedState, tc.replyTo)
        yield result

      // --- Interrupt ---
      case AgentCommand.Interrupt() =>
        logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "interrupt", "reason=user")
        for
          _ <- ctx.cancelCurrentTurn()

          _ <- emitStream(state.wsSend, AgentStreamEvent.Interrupted, isSubagent = depth > 0, state.sessionId)

          _ <- state.pendingCompaction
            .flatMap(_.replyDeferred)
            .traverse_(d => d.complete(Left("Interrupted by user")).void.handleErrorWith(_ => IO.unit))
        yield
          val interruptedState = state.resetForInterrupt.withPendingCompaction(None)
          idle(agentDef, resources, depth, parentRef, interruptedState)

      // --- Retry: cancel current work, re-dispatch from last checkpoint ---
      case AgentCommand.Retry(reason) =>
        logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "retry", s"reason=$reason")
        ctx.cancelCurrentTurn() *> (state.lastDispatch match
          case Some(LastDispatch(false, _)) =>
            // Re-dispatch LLM call with same messages
            pipeLlmCall(
              agentDef,
              resources,
              depth,
              parentRef,
              state,
              None,
              (ad, r, d, p, s) => processing(ad, r, d, p, s)
            )
          case Some(LastDispatch(true, Some(cr))) =>
            // Re-dispatch tool execution with same LLM result
            pipeToolExecutions(
              agentDef,
              resources,
              depth,
              parentRef,
              state,
              cr,
              None,
              (ad, r, d, p, s) => processing(ad, r, d, p, s)
            )
          case _ =>
            // No checkpoint — go to idle
            IO.pure(idle(agentDef, resources, depth, parentRef, state.resetForInterrupt)))

      // --- Stop ---
      case AgentCommand.Stop(_) =>
        logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "stop", "reason=user")
        for
          _ <- ctx.cancelCurrentTurn()

          _ <- fireLifecycleStopHooks(resources, state)
        yield Behaviors.stopped

      case AgentCommand.ClearReadTracker =>
        state.readTracker.fold(IO.unit)(t => t.clear()) *>
          IO.pure(processing(agentDef, resources, depth, parentRef, state, pending))

      case AgentCommand.ResetSession =>
        for

          _ <- state.readTracker.fold(IO.unit)(t => t.clear())
          _ <- emitStream(state.wsSend, AgentStreamEvent.Interrupted, isSubagent = depth > 0, state.sessionId)
        yield
          val resetState = state
            .withMessages(Nil)
            .withLatestUsage(None)
            .withPendingCompaction(None)
            .withCompactionFailures(0)
            .withLastCompactionFailureAt(0L)
            .withRecentMessageIds(Nil)
            .resetToIdle(Nil)
          idle(agentDef, resources, depth, parentRef, resetState)

      // --- ReplaceToolResults ---
      case AgentCommand.ReplaceToolResults(rounds, summary, replyTo) =>
        replaceToolResults(state.messages, rounds, summary) match
          case Right((updatedMessages, count)) =>
            replyTo.complete(Right(count)).void.handleErrorWith(_ => IO.unit) *>
              IO.pure(processing(agentDef, resources, depth, parentRef, state.withMessages(updatedMessages), pending))
          case Left(err) =>
            replyTo.complete(Left(err)).void.handleErrorWith(_ => IO.unit) *>
              IO.pure(processing(agentDef, resources, depth, parentRef, state, pending))

      // --- Compaction completed ---
      case AgentCommand.CompactionComplete(result) =>
        if state.pendingCompaction.isEmpty then
          IO.pure(processing(agentDef, resources, depth, parentRef, state, pending))
        else
          val compactionPending = state.pendingCompaction
          result match
            case Right(compactedMessages) =>
              logAgentEvent(
                agentDef,
                depth,
                state.sessionId,
                state.sessionName,
                "compaction-complete",
                s"before=${state.messages.size} after=${compactedMessages.size}"
              )
              val archiveIO = state.sessionId match
                case Some(sid) =>
                  resources.historyArchiver.archiveCompaction(
                    sessionId = sid,
                    sessionName = state.sessionName,
                    agentName = agentDef.name,
                    before = state.messages,
                    after = compactedMessages,
                    mode = compactionPending.map(_.mode).getOrElse("full"),
                    extra = Map("preservedRounds" -> "0")
                  )
                case None => IO.pure(Left("no sessionId"))
              val compactEmitIO = archiveIO
                .flatMap {
                  case Right(archive) =>
                    emitStreamIO(
                      state.wsSend,
                      AgentStreamEvent
                        .CompactComplete(state.messages.size, compactedMessages.size, Some(archive.reportPath)),
                      isSubagent = depth > 0,
                      state.sessionId
                    )
                  case Left(err) =>
                    NebflowLogger.forName("nebflow.agent").warn(s"Compaction archive failed: $err")
                    emitStreamIO(
                      state.wsSend,
                      AgentStreamEvent.CompactComplete(state.messages.size, compactedMessages.size, None),
                      isSubagent = depth > 0,
                      state.sessionId
                    )
                }
                .handleErrorWith(_ => IO.unit)
              val compactedState = state
                .withMessages(compactedMessages)
                .withPendingCompaction(None)
                .withCompactionFailures(0)
                .withEmptyResponseRetries(0)
                .withLatestUsage(None)
              if compactionPending.exists(_.resumeAfterCompact) then
                val stateWithInstruction = compactionPending.flatMap(_.postCompactInstruction) match
                  case Some(instruction) =>
                    compactedState.withMessages(compactedState.messages :+ Message(MessageRole.User, Left(instruction)))
                  case None => compactedState
                ctx.forkTurn(compactEmitIO) *>
                  pipeLlmCall(
                    agentDef,
                    resources,
                    depth,
                    parentRef,
                    stateWithInstruction,
                    compactionPending.flatMap(_.replyTo)
                  )
              else
                for
                  _ <- ctx.forkTurn(compactEmitIO)
                  _ <- ctx.forkTurn(
                    persistIfSession(resources, compactedState)
                      .handleErrorWith(e =>
                        IO(
                          NebflowLogger.forName("nebflow.agent").warn(s"Persist after compact failed: ${e.getMessage}")
                        )
                      )
                  )
                yield idle(agentDef, resources, depth, parentRef, compactedState)
              end if
            case Left(err) =>
              logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "compaction-failed", s"err=$err")
              val now = System.currentTimeMillis()
              val failedState = state
                .withPendingCompaction(None)
                .withCompactionFailures(state.compactionFailures + 1)
                .withLastCompactionFailureAt(now)
              val baseIO: IO[Unit] = for
                _ <- ctx.forkTurn(
                  emitStreamIO(
                    state.wsSend,
                    AgentStreamEvent.CompactFailed(
                      err,
                      state.compactionFailures + 1,
                      CompactConfig().circuitBreakerMax
                    ),
                    isSubagent = depth > 0,
                    state.sessionId
                  ).handleErrorWith(_ => IO.unit)
                )
                _ <- compactionPending
                  .flatMap(_.replyDeferred)
                  .fold(IO.unit)(d => d.complete(Left(err)).void.handleErrorWith(_ => IO.unit))
              yield ()
              compactionPending.flatMap(_.replyTo) match
                case Some(replyTo) =>
                  baseIO *> finishTurn(
                    agentDef,
                    resources,
                    depth,
                    parentRef,
                    failedState,
                    Some(replyTo),
                    s"Context compaction failed: $err. Please start a new session or use /clear to reset.",
                    None,
                    None,
                    textAlreadyStreamed = false,
                    None
                  )
                case None =>
                  if compactionPending.exists(!_.resumeAfterCompact) then
                    baseIO *> IO.pure(idle(agentDef, resources, depth, parentRef, failedState))
                  else baseIO *> IO.pure(processing(agentDef, resources, depth, parentRef, failedState, pending))
              end match
          end match
        end if

      // --- Background task completed while processing ---
      case n: AgentCommand.BackgroundTaskNotification =>
        (ctx.self ! n.toExternalEvent) *> IO.pure(processing(agentDef, resources, depth, parentRef, state, pending))

      // --- External event while processing ---
      case AgentCommand.ExternalEvent(source, eventType, payload, metadata, correlationId) =>
        logAgentEvent(
          agentDef,
          depth,
          state.sessionId,
          state.sessionName,
          "external-event-queued",
          s"source=$source type=$eventType"
        )
        val event = AgentCommand.ExternalEvent(source, eventType, payload, metadata, correlationId)
        val updatedExec = state.execution.copy(pendingEvents = state.execution.pendingEvents :+ event)
        emitStream(
          state.wsSend,
          AgentStreamEvent.ExternalEventReceived(source, eventType, correlationId),
          isSubagent = depth > 0,
          state.sessionId
        ) *> IO.pure(processing(agentDef, resources, depth, parentRef, state.copy(execution = updatedExec), pending))

      // --- AskUser from tool ---
      case AgentCommand.AskUser(requestId, items, replyToOpt) =>
        val roundCompleteJson = state.sessionId.map { sid =>
          Json.obj("type" -> "roundComplete".asJson, "sessionId" -> sid.asJson)
        }
        val askJson = Json.obj(
          "type" -> "askUser".asJson,
          "sessionId" -> state.sessionId.asJson,
          "items" -> Json.fromValues(items.map { item =>
            val base = scala.collection.mutable.ListBuffer(
              "question" -> item.question.asJson,
              "options" -> Json.fromValues(item.options.map { opt =>
                val fields = scala.collection.mutable.ListBuffer("label" -> opt.label.asJson)
                opt.description.foreach(d => fields += "description" -> d.asJson)
                Json.obj(fields.toList*)
              }),
              "allowOther" -> item.allowOther.asJson
            )
            item.id.foreach(id => base += "id" -> id.asJson)
            item.dependsOn.foreach { dep =>
              base += "dependsOn" -> Json.obj("ref" -> dep.ref.asJson, "equals" -> dep.equals.asJson)
            }
            Json.obj(base.toList*)
          })
        )
        val updatedInteraction = Some(
          InteractionState(
            pendingAskUser = None,
            pendingPermission = state.pendingPermission,
            pendingAskUserReplyTo = replyToOpt
          )
        )
        val updatedState = state.copy(execution = state.execution.copy(interaction = updatedInteraction))
        ctx.forkTurn(
          (roundCompleteJson.map(state.wsSend).getOrElse(IO.unit)).handleErrorWith(_ => IO.unit) *>
            state.wsSend(askJson).handleErrorWith { e =>
              replyToOpt.foreach(replyTo => (replyTo ! Nil))
              IO.unit
            }
        ) *> IO.pure(processing(agentDef, resources, depth, parentRef, updatedState, pending))

      // --- User answered while processing ---
      case AgentCommand.UserAnswered(answers) =>
        state.execution.interaction.flatMap(_.pendingAskUserReplyTo) match
          case Some(replyTo) =>
            logAgentEvent(
              agentDef,
              depth,
              state.sessionId,
              state.sessionName,
              "user-answered-in-processing",
              s"answers=${answers.size}"
            )
            (replyTo ! answers) *> IO.pure(
              processing(agentDef, resources, depth, parentRef, state.withInteraction(None), pending)
            )
          case None => IO.pure(processing(agentDef, resources, depth, parentRef, state, pending))

      // --- Permission answered while processing ---
      case AgentCommand.PermissionAnswered(approved) =>
        state.pendingPermission match
          case Some(deferred) =>
            logAgentEvent(
              agentDef,
              depth,
              state.sessionId,
              state.sessionName,
              "permission-answered-in-processing",
              s"approved=$approved"
            )
            deferred.complete(approved).void.handleErrorWith(_ => IO.unit) *>
              IO.pure(processing(agentDef, resources, depth, parentRef, state.withPendingPermission(None), pending))
          case None => IO.pure(processing(agentDef, resources, depth, parentRef, state, pending))

      // --- Set permission deferred while processing ---
      case AgentCommand.SetPermissionDeferred(deferred) =>
        IO.pure(processing(agentDef, resources, depth, parentRef, state.withPendingPermission(Some(deferred)), pending))

      // --- Sub-agent forwarded a permission request while processing ---
      case AgentCommand.ForwardPermission(deferred, permJson) =>
        if state.pendingPermission.isDefined then
          deferred.complete(false).void.handleErrorWith(_ => IO.unit) *>
            IO.pure(processing(agentDef, resources, depth, parentRef, state, pending))
        else
          state.wsSend(permJson).handleErrorWith(_ => IO.unit) *>
            IO.pure(
              processing(agentDef, resources, depth, parentRef, state.withPendingPermission(Some(deferred)), pending)
            )

      // --- Bypass toggled while processing ---
      case AgentCommand.SetSafetyMode(mode) =>
        IO.pure(
          processing(
            agentDef,
            resources,
            depth,
            parentRef,
            state.withSafetyMode(nebflow.core.SafetyMode.toString(mode)),
            pending
          )
        )

      // --- Session model switched ---
      case AgentCommand.UpdateContextWindow(window) =>
        IO.pure(
          processing(
            agentDef,
            resources,
            depth,
            parentRef,
            state.withContextWindow(window),
            pending
          )
        )

      // --- Buffer user-initiated messages during processing ---
      case msg: AgentCommand.UserInput =>
        IO.pure(processing(agentDef, resources, depth, parentRef, state, pending :+ msg))
      case msg: AgentCommand.SkillActivate =>
        IO.pure(processing(agentDef, resources, depth, parentRef, state, pending :+ msg))
      case msg: AgentCommand.AskQuestion =>
        IO.pure(processing(agentDef, resources, depth, parentRef, state, pending :+ msg))
      case msg: AgentCommand.ImmediateInput =>
        logAgentEvent(
          agentDef,
          depth,
          state.sessionId,
          state.sessionName,
          "immediate-input-queued",
          s"textLen=${msg.text.length}"
        )
        val updatedExec = state.execution.copy(
          pendingImmediateInputs = state.execution.pendingImmediateInputs :+ msg
        )
        IO.pure(processing(agentDef, resources, depth, parentRef, state.copy(execution = updatedExec), pending))

      // --- Session management (persistent sub-agents) ---
      case AgentCommand.SessionStarted(address, agentName, taskDescription) =>
        val session = AgentSessionInfo(address, agentName, taskDescription, "running")
        IO.pure(
          processing(
            agentDef,
            resources,
            depth,
            parentRef,
            state.withAgentSessions(state.agentSessions :+ session),
            pending
          )
        )

      case AgentCommand.SessionUpdate(address, status) =>
        val updated = state.agentSessions.map(s => if s.address == address then s.copy(status = status) else s)
        IO.pure(processing(agentDef, resources, depth, parentRef, state.withAgentSessions(updated), pending))

      case AgentCommand.SessionClosed(address) =>
        val updated = state.agentSessions.filterNot(_.address == address)
        IO.pure(processing(agentDef, resources, depth, parentRef, state.withAgentSessions(updated), pending))

      case _ =>
        IO.pure(processing(agentDef, resources, depth, parentRef, state, pending))

    // Supervision: on unhandled error, return to idle with safe state
    new Behavior[AgentCommand]:
      def receive(c: ActorContext[AgentCommand], msg: AgentCommand): IO[Behavior[AgentCommand]] =
        base.receive(c, msg)
      override def onError(c: ActorContext[AgentCommand], err: Throwable): IO[Behavior[AgentCommand]] =
        c.log
          .error(s"Agent error in processing, returning to idle: ${err.getMessage}")
          .as(idle(agentDef, resources, depth, parentRef, state.withStatus(AgentStatus.Idle).withInteraction(None)))
  end processing
  // ============================================================
  // Mail check — flow agents must call Mail before finishing
  // ============================================================

  /** Check if the conversation contains any Mail tool call. */
  private def hasUsedMail(messages: List[Message]): Boolean =
    messages.exists(_.content match
      case Right(blocks) => blocks.exists {
        case ContentBlock.ToolUse(_, name, _) => name == "Mail"
        case _ => false
      }
      case _ => false
    )

  /** Inject a system reminder telling the agent to use Mail, then trigger a new turn. */
  private def handleMissingMail(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    replyTo: Option[ActorRef[AgentEvent]],
    result: ConsumeResult
  )(using ctx: ActorContext[AgentCommand]): IO[Behavior[AgentCommand]] =
    logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "mail-reminder", "agent finished without Mail")
    val assistantContent = (result.thinking, result.text) match
      case (None, _) => Left(result.text)
      case (Some(t), "") => Right(List(ContentBlock.Thinking(t, result.thinkingSignature)))
      case (Some(t), txt) =>
        Right(List(ContentBlock.Thinking(t, result.thinkingSignature), ContentBlock.Text(txt)))
    val assistantMsg = Message(MessageRole.Assistant, assistantContent)
    val reminderMsg = Message(MessageRole.User, Left(
      "<system-reminder>\nYou must use the Mail tool to report your result before finishing. Call Mail now with your findings.\n</system-reminder>"
    ))
    val newMessages = state.messages ++ List(assistantMsg, reminderMsg)
    val updatedState = state.copy(execution = state.execution.copy(messages = newMessages, status = AgentStatus.Processing))
    pipeLlmCall(agentDef, resources, depth, parentRef, updatedState, replyTo)

  // ============================================================
  // LlmComplete branch selector
  // ============================================================

  private def handleLlmCompleteBranch(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    replyTo: Option[ActorRef[AgentEvent]],
    result: ConsumeResult,
    pending: List[AgentCommand]
  )(using ctx: ActorContext[AgentCommand]): IO[Behavior[AgentCommand]] =
    if state.pendingCompaction.isDefined && result.toolCalls.isEmpty && result.text.nonEmpty then
      handleCompactResponse(agentDef, resources, depth, parentRef, state, result.text)
    else if state.pendingCompaction.isDefined && result.toolCalls.nonEmpty then
      handleCompactFailure(agentDef, resources, depth, parentRef, state, "Compact model unexpectedly called tools")
    else if state.askMode.isDefined && result.toolCalls.isEmpty then
      handleAskComplete(agentDef, resources, depth, parentRef, state, result.text, result.model)
    else if result.toolCalls.nonEmpty then
      pipeToolExecutions(agentDef, resources, depth, parentRef, state.withEmptyResponseRetries(0), result, replyTo)
    else if result.text.nonEmpty || result.thinking.nonEmpty then
      // Mail check: flow agents must call Mail before finishing
      if state.expectsMail && !hasUsedMail(state.messages) then
        handleMissingMail(agentDef, resources, depth, parentRef, state, replyTo, result)
      else
        finishTurn(
          agentDef,
          resources,
          depth,
          parentRef,
          state.withEmptyResponseRetries(0),
          replyTo,
          result.text,
          result.thinking,
          result.thinkingSignature,
          textAlreadyStreamed = true,
          result.model
        )
    else handleEmptyResponse(agentDef, resources, depth, parentRef, state, replyTo, result)

  // ============================================================
  // Finish turn
  // ============================================================

  private def finishTurn(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    replyTo: Option[ActorRef[AgentEvent]],
    text: String,
    thinking: Option[String] = None,
    thinkingSignature: Option[String] = None,
    textAlreadyStreamed: Boolean = false,
    model: Option[String] = None
  )(using ctx: ActorContext[AgentCommand]): IO[Behavior[AgentCommand]] =
    val isSubagent = parentRef.isDefined
    val sendText = !textAlreadyStreamed && text.nonEmpty
    val assistantContent = (thinking, text) match
      case (None, _) => Left(text)
      case (Some(t), "") => Right(List(ContentBlock.Thinking(t, thinkingSignature)))
      case (Some(t), txt) =>
        Right(List(ContentBlock.Thinking(t, thinkingSignature), ContentBlock.Text(txt)))
    val newMessages = state.messages :+ Message(MessageRole.Assistant, assistantContent)
    val sendTextIO =
      if sendText then
        emitStream(state.wsSend, AgentStreamEvent.TextDelta(text), isSubagent = isSubagent, state.sessionId)
      else IO.unit
    for
      _ <- sendTextIO
      result <- finishTurnCont(
        agentDef,
        resources,
        depth,
        parentRef,
        state,
        replyTo,
        newMessages,
        text,
        model,
        thinking,
        thinkingSignature,
        sendText,
        isSubagent
      )
    yield result

    end for

  end finishTurn

  private def finishTurnCont(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    replyTo: Option[ActorRef[AgentEvent]],
    newMessages: List[Message],
    text: String,
    model: Option[String],
    thinking: Option[String],
    thinkingSignature: Option[String],
    textAlreadyStreamed: Boolean,
    isSubagent: Boolean
  )(using ctx: ActorContext[AgentCommand]): IO[Behavior[AgentCommand]] =
    logAgentEvent(
      agentDef,
      depth,
      state.sessionId,
      state.sessionName,
      "turn-complete",
      s"msgs=${state.messages.size} textLen=${text.length} textStreamed=$textAlreadyStreamed " +
        s"thinking=${thinking.map(_.length).getOrElse(0)} model=${model.getOrElse("-")}"
    )
    val queuedEvents = state.execution.pendingEvents
    if queuedEvents.nonEmpty then
      val roundCompleteIO: IO[Unit] =
        if !isSubagent then
          state.sessionId.fold(IO.unit)(sid =>
            ctx.forkTurn(
              state
                .wsSend(Json.obj("type" -> "roundComplete".asJson, "sessionId" -> sid.asJson))
                .handleErrorWith(e =>
                  IO(NebflowLogger.forName("nebflow.agent").warn(s"roundComplete delivery failed: ${e.getMessage}"))
                )
            )
          )
        else IO.unit
      val remindersText = queuedEvents.map(_.payload).mkString("\n\n")
      val eventMessages = List(
        Message(MessageRole.User, Left(s"<system-reminder>\n$remindersText\n</system-reminder>"))
      )
      val messagesWithPending = newMessages ++ eventMessages
      logAgentEvent(
        agentDef,
        depth,
        state.sessionId,
        state.sessionName,
        "pending-messages-injected",
        s"events=${queuedEvents.size}"
      )
      val updatedState = state.copy(execution =
        ExecutionContext
          .idle(messagesWithPending, state.execution.turnIdx)
          .copy(pendingImmediateInputs = state.execution.pendingImmediateInputs)
      )
      for
        _ <- roundCompleteIO
        _ <- state.sessionId.fold(IO.unit)(sid =>
          ctx.forkTurn(
            (resources.sessionStore.saveMessagesForSession(sid, messagesWithPending) *>
              resources.sessionStore.flushIndex)
              .handleErrorWith(e =>
                IO(NebflowLogger.forName("nebflow.agent").warn(s"Save/flush session failed: ${e.getMessage}"))
              )
          )
        )
        _ <- replyTo.traverse_(_ ! AgentEvent.Completed(state.sessionId.getOrElse(""), messagesWithPending))
        result <- pipeLlmCall(agentDef, resources, depth, parentRef, updatedState, None)
      yield result
    else if state.execution.pendingImmediateInputs.nonEmpty then
      // Immediate user inputs arrived during the final LLM call (no tool gap).
      // Inject them and continue the turn so the LLM sees them right away.
      val immInputs = state.execution.pendingImmediateInputs
      logAgentEvent(
        agentDef,
        depth,
        state.sessionId,
        state.sessionName,
        "immediate-inputs-injected-at-turn-end",
        s"count=${immInputs.size}"
      )
      val roundCompleteIO: IO[Unit] =
        if !isSubagent then
          state.sessionId.fold(IO.unit)(sid =>
            ctx.forkTurn(
              state
                .wsSend(Json.obj("type" -> "roundComplete".asJson, "sessionId" -> sid.asJson))
                .handleErrorWith(e =>
                  IO(NebflowLogger.forName("nebflow.agent").warn(s"roundComplete delivery failed: ${e.getMessage}"))
                )
            )
          )
        else IO.unit
      val immMessages = immInputs.map(imm =>
        imm.blocks match
          case Some(blocks) if blocks.nonEmpty => Message(MessageRole.User, Right(blocks))
          case _ => Message(MessageRole.User, Left(imm.text))
      )
      val messagesWithImmediate = newMessages ++ immMessages
      val updatedState = state.copy(execution = ExecutionContext.idle(messagesWithImmediate, state.execution.turnIdx))
      for
        _ <- roundCompleteIO
        _ <- state.sessionId.fold(IO.unit)(sid =>
          ctx.forkTurn(
            (resources.sessionStore.saveMessagesForSession(sid, messagesWithImmediate) *>
              resources.sessionStore.flushIndex)
              .handleErrorWith(e =>
                IO(NebflowLogger.forName("nebflow.agent").warn(s"Save/flush session failed: ${e.getMessage}"))
              )
          )
        )
        _ <- replyTo.traverse_(_ ! AgentEvent.Completed(state.sessionId.getOrElse(""), messagesWithImmediate))
        result <- pipeLlmCall(agentDef, resources, depth, parentRef, updatedState, None)
      yield result
    else
      val doneCompactThreshold =
        if !isSubagent then Some(CompactConfig().compactionTriggerRatio(state.contextWindow))
        else None
      val doneEvent = AgentStreamEvent.Done(
        model.orElse(state.lastModel),
        contextWindow = if !isSubagent then Some(state.contextWindow) else None,
        inputTokens = if !isSubagent then state.latestUsage.map(_.inputTokens) else None,
        compactThreshold = doneCompactThreshold
      )
      val emitDoneIO =
        if isSubagent then emitStream(state.wsSend, doneEvent, isSubagent = true, state.sessionId)
        else
          state.sessionId.fold(IO.unit) { sid =>
            val doneJson = doneEvent.toJson(ctx.self.path.name, false, state.sessionId)
            NebflowLogger
              .forName("nebflow.agent")
              .info(s"finishTurn: sending Done sessionId=$sid jsonLen=${doneJson.noSpaces.length}")
            ctx.forkTurn(
              (state
                .wsSend(doneJson)
                .handleErrorWith(e =>
                  IO(
                    NebflowLogger
                      .forName("nebflow.agent")
                      .warn(s"finishTurn: Done event delivery failed: ${e.getMessage}")
                  )
                ) *>
                emitSessionBusy(state.wsSend, sid, busy = false))
                .handleErrorWith(e =>
                  IO(
                    NebflowLogger
                      .forName("nebflow.agent")
                      .warn(s"finishTurn: Done+sessionBusy chain failed: ${e.getMessage}")
                  )
                )
            )
          }
      for
        _ <- emitDoneIO
        _ <- state.sessionId.fold(IO.unit)(sid =>
          ctx.forkTurn(
            (resources.sessionStore.saveMessagesForSession(sid, newMessages) *>
              resources.sessionStore.flushIndex)
              .handleErrorWith(e =>
                IO(NebflowLogger.forName("nebflow.agent").warn(s"Save/flush session failed: ${e.getMessage}"))
              )
          )
        )
        _ <- replyTo.traverse_(_ ! AgentEvent.Completed(state.sessionId.getOrElse(""), newMessages))
      yield
        val updatedState = state.copy(execution = ExecutionContext.idle(newMessages, state.execution.turnIdx))
        idle(agentDef, resources, depth, parentRef, updatedState)
      end for
    end if
  end finishTurnCont

  // ============================================================
  // Pipe wrappers
  // ============================================================

  private def pipeLlmCall(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    replyTo: Option[ActorRef[AgentEvent]]
  )(using ctx: ActorContext[AgentCommand]): IO[Behavior[AgentCommand]] =
    super.pipeLlmCall(
      agentDef,
      resources,
      depth,
      parentRef,
      state,
      replyTo,
      (ad, r, d, p, s) => processing(ad, r, d, p, s)
    )

  private def pipeToolExecutions(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    result: ConsumeResult,
    replyTo: Option[ActorRef[AgentEvent]]
  )(using ctx: ActorContext[AgentCommand]): IO[Behavior[AgentCommand]] =
    super.pipeToolExecutions(
      agentDef,
      resources,
      depth,
      parentRef,
      state,
      result,
      replyTo,
      (ad, r, d, p, s) => processing(ad, r, d, p, s)
    )

  // ============================================================
  // Compact response handlers
  // ============================================================

  private def handleCompactResponse(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    responseText: String
  )(using ctx: ActorContext[AgentCommand]): IO[Behavior[AgentCommand]] =
    val sessionId = state.sessionId.getOrElse(ctx.self.path.name)
    val readPathsIO = state.readTracker
      .map(_.recentFiles(CompactConfig().postCompactMaxFiles).map(_.map(_.toString)))
      .getOrElse(IO.pure(Nil))
    val hookIO = CompactService.runPreCompactHook(state.messages, resources, sessionId)
    val rootIO: IO[String] = state.folderId match
      case Some(fid) =>
        resources.sessionStore.resolveProjectRoot(Some(fid)).map(_.getOrElse(resources.projectRoot.toString))
      case None => IO.pure(resources.projectRoot.toString)
    for _ <- ctx.forkTurn(
        (readPathsIO, hookIO, rootIO)
          .mapN { (readPaths, hookResult, effectiveRoot) =>
            (readPaths, hookResult, effectiveRoot)
          }
          .flatMap { (readPaths, hookResult, effectiveRoot) =>
            hookResult match
              case Left(reason) =>
                ctx.self ! AgentCommand.CompactionComplete(Left(reason))
              case Right(_) =>
                FullCompact.parseResponse(responseText, state.messages, effectiveRoot, readPaths) match
                  case Left(err) =>
                    ctx.self ! AgentCommand.CompactionComplete(Left(err))
                  case Right(compactedMessages) =>
                    val postHookIO = CompactService
                      .runPostCompactHook(state.messages.size, compactedMessages.size, resources, sessionId)
                      .handleErrorWith(_ => IO.unit)
                    ctx
                      .forkTurn(postHookIO)
                      .flatMap(_ => ctx.self ! AgentCommand.CompactionComplete(Right(compactedMessages)))
          }
          .handleErrorWith(e => ctx.self ! AgentCommand.CompactionComplete(Left(e.getMessage)))
      )
    yield processing(agentDef, resources, depth, parentRef, state)

  end handleCompactResponse

  private def handleCompactFailure(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    err: String
  )(using ctx: ActorContext[AgentCommand]): IO[Behavior[AgentCommand]] =
    val pending = state.pendingCompaction
    val now = System.currentTimeMillis()
    logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "compaction-failed", s"err=$err")
    val failedState = state
      .withPendingCompaction(None)
      .withCompactionFailures(state.compactionFailures + 1)
      .withLastCompactionFailureAt(now)
    for
      _ <- ctx.forkTurn(
        emitStreamIO(
          state.wsSend,
          AgentStreamEvent.CompactFailed(err, state.compactionFailures + 1, CompactConfig().circuitBreakerMax),
          isSubagent = depth > 0,
          state.sessionId
        ).handleErrorWith(_ => IO.unit)
      )
      _ <- pending
        .flatMap(_.replyDeferred)
        .fold(IO.unit)(d => d.complete(Left(err)).void.handleErrorWith(_ => IO.unit))
      result <- pending.flatMap(_.replyTo) match
        case Some(replyTo) =>
          finishTurn(
            agentDef,
            resources,
            depth,
            parentRef,
            failedState,
            Some(replyTo),
            s"Context compaction failed: $err.",
            None,
            None,
            textAlreadyStreamed = false,
            None
          )
        case None =>
          if pending.exists(!_.resumeAfterCompact) then
            IO.pure(idle(agentDef, resources, depth, parentRef, failedState))
          else IO.pure(processing(agentDef, resources, depth, parentRef, failedState))
    yield result
    end for
  end handleCompactFailure

  // ============================================================
  // Ask complete
  // ============================================================

  private def handleAskComplete(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    answerText: String,
    model: Option[String]
  )(using ctx: ActorContext[AgentCommand]): IO[Behavior[AgentCommand]] =
    val question = state.askMode.getOrElse("")
    val sessionId = state.sessionId.getOrElse(ctx.self.path.name)
    logAgentEvent(
      agentDef,
      depth,
      state.sessionId,
      state.sessionName,
      "ask-complete",
      s"q=${question.take(40)} a=${answerText.take(40)}"
    )
    for
      _ <- ctx.forkTurn(
        (state
          .wsSend(
            Json.obj(
              "type" -> "askDone".asJson,
              "sessionId" -> sessionId.asJson,
              "durationMs" -> 0L.asJson,
              "model" -> model.getOrElse("").asJson
            )
          )
          .handleErrorWith(_ => IO.unit)) *>
          emitSessionBusy(state.wsSend, sessionId, busy = false)
      )
      _ <- ctx.forkTurn(
        resources.sessionStore
          .appendUiMessages(sessionId, List(UiMessage.Ask(question, answerText, Some(0L), model)))
          .handleErrorWith(e => IO(logger.warn(s"Failed to persist ask UiMessage: ${e.getMessage}")))
      )
    yield
      val originalMessages = state.messages.takeWhile(m => !isAskReminder(m))
      val restoredMessages =
        if originalMessages.size == state.messages.size then state.messages.dropRight(1)
        else originalMessages
      idle(
        agentDef,
        resources,
        depth,
        parentRef,
        state.withAskMode(None).withMessages(restoredMessages).resetToIdle(restoredMessages)
      )

    end for

  end handleAskComplete

  private def isAskReminder(msg: Message): Boolean =
    msg.role == MessageRole.User && (msg.content match
      case Left(text) => text.contains("<system-reminder>") && text.contains("ephemeral follow-up question")
      case _ => false)

  // ============================================================
  // Trigger compaction
  // ============================================================

  private def handleTriggerCompaction(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    mode: String,
    replyDeferred: Option[cats.effect.Deferred[IO, Either[String, CompactionResult]]],
    resumeAfterCompact: Boolean = true,
    postCompactInstruction: Option[String] = None
  )(using ctx: ActorContext[AgentCommand]): IO[Behavior[AgentCommand]] =
    val config = CompactConfig()
    if state.pendingCompaction.isDefined then
      val reason = "Compaction already in progress"
      for
        _ <- replyDeferred.fold(IO.unit)(d => d.complete(Left(reason)).void.handleErrorWith(_ => IO.unit))
        _ <- ctx.forkTurn(
          emitStreamIO(
            state.wsSend,
            AgentStreamEvent.CompactFailed(reason, state.compactionFailures, config.circuitBreakerMax),
            isSubagent = depth > 0,
            state.sessionId
          ).handleErrorWith(_ => IO.unit)
        )
      yield idle(agentDef, resources, depth, parentRef, state)
    else
      val backoffOk =
        if state.compactionFailures == 0 then true
        else
          val elapsed = System.currentTimeMillis() - state.lastCompactionFailureAt
          config.isBackoffSatisfied(state.compactionFailures, elapsed)
      if !backoffOk then
        val err = s"Compaction retry backed off (${state.compactionFailures} failures)"
        replyDeferred.fold(IO.unit)(d => d.complete(Left(err)).void.handleErrorWith(_ => IO.unit)) *>
          IO.pure(idle(agentDef, resources, depth, parentRef, state))
      else if state.compactionFailures >= config.circuitBreakerMax then
        val err = s"Compaction circuit breaker open after ${state.compactionFailures} attempts"
        for
          _ <- replyDeferred.fold(IO.unit)(d => d.complete(Left(err)).void.handleErrorWith(_ => IO.unit))
          _ <- ctx.forkTurn(
            emitStreamIO(
              state.wsSend,
              AgentStreamEvent.CompactFailed(err, state.compactionFailures, config.circuitBreakerMax),
              isSubagent = depth > 0,
              state.sessionId
            ).handleErrorWith(_ => IO.unit)
          )
        yield idle(agentDef, resources, depth, parentRef, state)
      else
        startDirectCompaction(
          agentDef,
          resources,
          depth,
          parentRef,
          state,
          None,
          (ad, r, d, p, s) => processing(ad, r, d, p, s),
          mode,
          resumeAfterCompact,
          postCompactInstruction
        )
      end if
    end if
  end handleTriggerCompaction

  // ============================================================
  // Empty response handler
  // ============================================================

  private def handleEmptyResponse(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    replyTo: Option[ActorRef[AgentEvent]],
    result: ConsumeResult
  )(using ctx: ActorContext[AgentCommand]): IO[Behavior[AgentCommand]] =
    val stopReason = result.stopReason.getOrElse("")
    val isContextError = stopReason.toLowerCase.contains("context") ||
      stopReason.toLowerCase.contains("exceeded") || stopReason.toLowerCase.contains("limit") ||
      stopReason.toLowerCase.contains("too long") || stopReason.toLowerCase.contains("too_long")
    val isMaxTokens = stopReason.equalsIgnoreCase("max_tokens") ||
      stopReason.equalsIgnoreCase("length")
    if isContextError then
      logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "context-exceeded", s"stopReason=$stopReason")
      if state.pendingCompaction.isDefined || state.compactionFailures >= CompactConfig().circuitBreakerMax then
        finishTurn(
          agentDef,
          resources,
          depth,
          parentRef,
          state,
          replyTo,
          "Context window exceeded and compaction is unavailable. " +
            "Please start a new session or use /clear to reset.",
          None,
          None,
          textAlreadyStreamed = false,
          result.model
        )
      else
        startDirectCompaction(
          agentDef,
          resources,
          depth,
          parentRef,
          state,
          replyTo,
          (ad, r, d, p, s) => processing(ad, r, d, p, s),
          "full"
        )
      end if
    else if isMaxTokens then
      logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "max-tokens", s"stopReason=$stopReason")
      for
        _ <- ctx.forkTurn(
          state
            .wsSend(Json.obj("type" -> "maxTokens".asJson, "sessionId" -> state.sessionId.asJson))
            .handleErrorWith(_ => IO.unit)
        )
        result <- finishTurn(
          agentDef,
          resources,
          depth,
          parentRef,
          state,
          replyTo,
          "[Response truncated: max output tokens reached]",
          None,
          None,
          textAlreadyStreamed = false,
          result.model
        )
      yield result
      end for
    else
      val retryCount = state.emptyResponseRetries
      logAgentEvent(
        agentDef,
        depth,
        state.sessionId,
        state.sessionName,
        "empty-response",
        s"stopReason=$stopReason retry=$retryCount/$MaxEmptyResponseRetries " +
          s"usage=${result.usage.map(u => s"in=${u.inputTokens} out=${u.outputTokens}").getOrElse("none")}"
      )
      if retryCount < MaxEmptyResponseRetries then
        val stateForRetry = state.withEmptyResponseRetries(retryCount + 1)
        logger.info(s"Empty response, retrying (${retryCount + 1}/$MaxEmptyResponseRetries) stopReason=$stopReason")
        for
          _ <- ctx.forkTurn(
            emitStreamIO(
              state.wsSend,
              AgentStreamEvent.RetryStatus(
                s"Empty response from LLM, retrying (${retryCount + 1}/$MaxEmptyResponseRetries)..."
              ),
              isSubagent = depth > 0,
              state.sessionId
            ).handleErrorWith(_ => IO.unit)
          )
          result <- pipeLlmCall(agentDef, resources, depth, parentRef, stateForRetry, replyTo)
        yield result
      else
        val errMsg =
          if stopReason.nonEmpty then
            s"LLM returned empty response after $MaxEmptyResponseRetries retries (stopReason: $stopReason)"
          else s"LLM returned empty response with no content after $MaxEmptyResponseRetries retries"
        for _ <- state.sessionId.fold(IO.unit) { sid =>
            val doneEvent = AgentStreamEvent.Done(result.model)
            val doneJson = doneEvent.toJson(ctx.self.path.name, false, state.sessionId)
            ctx.forkTurn(
              (state
                .wsSend(
                  Json.obj("type" -> "error".asJson, "sessionId" -> state.sessionId.asJson, "message" -> errMsg.asJson)
                )
                .handleErrorWith(_ => IO.unit)) *>
                state.wsSend(doneJson).handleErrorWith(_ => IO.unit) *>
                emitSessionBusy(state.wsSend, sid, busy = false)
            )
          }
        yield idle(agentDef, resources, depth, parentRef, state)
      end if
    end if
  end handleEmptyResponse

  // ============================================================
  // Tool result replacement
  // ============================================================

  private def replaceToolResults(
    messages: List[Message],
    rounds: Int,
    summary: String
  ): Either[String, (List[Message], Int)] =
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
