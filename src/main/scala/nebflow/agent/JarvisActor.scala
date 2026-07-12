package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.*
import nebflow.agent.AgentCommand.*
import nebflow.core.*
import nebflow.core.ask.AskService
import nebflow.core.compact.CompactConfig
import nebflow.llm.FallbackExhaustedError
import nebflow.service.MemoryStore
import nebflow.shared.*
import nebflow.shared.given

/**
 * Jarvis — pure orchestrator actor with memory consolidation.
 *
 * Unlike AgentActor (a general-purpose worker), Jarvis never executes tools
 * directly. It only delegates to sub-agents, reviews results, and consolidates
 * memories after each delegation cycle.
 *
 * States:  idle → processing → (finishTurn) → memoryConsolidating → idle
 *
 * Memory consolidation triggers automatically when a turn involved delegation.
 * Jarvis makes a separate LLM call (not streamed to user) to extract long-term
 * memories from the conversation, then writes them via MemoryStore.
 */
object JarvisActor extends AgentCore with AgentSession:

  private val logger = NebflowLogger.forName("nebflow.agent.jarvis")
  private val MaxEmptyRetries = 3

  /** Jarvis skips auto-compaction — conversations are short (delegation only). */
  override protected def maybeAutoCompact(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    replyTo: Option[ActorRef[AgentEvent]],
    processing: ProcessingFn
  )(using ctx: ActorContext[AgentCommand]): Option[IO[Behavior[AgentCommand]]] = None

  // ============================================================
  // Factory
  // ============================================================

  def apply(
    agentDef: AgentDef,
    resources: SharedResources,
    wsSend: Json => IO[Unit],
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
    Behaviors.setup { ctx =>
      logAgentEvent(
        agentDef,
        depth,
        sessionId,
        sessionName,
        "spawn",
        s"Jarvis actor, parent=${parentRef.map(_.path.name).getOrElse("-")} msgs=${initialMessages.size}"
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
            wsSend = wsSend,
            readTracker = readTracker,
            fileHistory = fileHistory,
            contextWindow = contextWindow,
            projectRoot = projectRoot,
            rulesMd = rulesMd,
            folderId = folderId
          )
        )(using ctx)
      )
    }

  // ============================================================
  // Lifecycle hooks
  // ============================================================

  private def fireLifecycleStopHooks(resources: SharedResources, state: AgentState)(using
    ctx: ActorContext[AgentCommand]
  ): IO[Unit] =
    if state.depth == 0 then
      val hookCtx = nebflow.core.hooks.HookContext(
        sessionId = state.sessionId,
        projectRoot = state.projectRoot.getOrElse(""),
        cwd = state.projectRoot.getOrElse("")
      )
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

      case UserInput(text, replyTo, clientMessageId, blocks, chatWidth) =>
        val (isDuplicate, dedupedState) = checkDuplicate(clientMessageId, state)
        if isDuplicate then IO.pure(idle(agentDef, resources, depth, parentRef, state))
        else
          val stateWithLang =
            if depth == 0 && parentRef.isEmpty && dedupedState.messages.isEmpty then
              LanguageDetector.detect(text).map(l => dedupedState.withLanguage(Some(l))).getOrElse(dedupedState)
            else dedupedState
          val stateWithWidth =
            if chatWidth > 0 then stateWithLang.copy(session = stateWithLang.session.copy(chatWidth = chatWidth))
            else stateWithLang
          val userMsg = blocks.filter(_.nonEmpty) match
            case Some(bl) => Message(MessageRole.User, Right(bl))
            case None => Message(MessageRole.User, Left(text))
          val newMessages = stateWithWidth.messages :+ userMsg
          val busyIO =
            if depth == 0 then
              stateWithWidth.sessionId.fold(IO.unit)(sid =>
                emitSessionBusy(stateWithWidth.wsSend, sid, busy = true)
              )
            else IO.unit
          for
            _ <- busyIO
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

      case AskQuestion(question, _) =>
        val askReminder = AskService.buildAskReminder(question)
        val askState = state
          .withMessages(state.messages :+ askReminder)
          .withAskMode(Some(question))
          .withStatus(AgentStatus.Processing)
        pipeLlmCall(agentDef, resources, depth, parentRef, askState, None)

      case SkillActivate(skillName, input, _, skillContent, _) =>
        val combinedText = s"<skill name=\"$skillName\">\n$skillContent\n</skill>\n\n$input"
        val processingState = state
          .withMessages(state.messages :+ Message(MessageRole.User, Left(combinedText)))
          .withStatus(AgentStatus.Processing)
        val busyIO =
          if depth == 0 then
            state.sessionId.fold(IO.unit)(sid => emitSessionBusy(state.wsSend, sid, busy = true))
          else IO.unit
        for
          _ <- busyIO
          result <- pipeLlmCall(agentDef, resources, depth, parentRef, processingState, None)
        yield result

      case ExternalEvent(source, eventType, payload, _, correlationId) =>
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

      case Interrupt() =>
        IO.pure(idle(agentDef, resources, depth, parentRef, state))

      case Stop(_) =>
        for
          _ <- ctx.cancelCurrentTurn()

          _ <- fireLifecycleStopHooks(resources, state)
        yield Behaviors.stopped

      case ResetSession =>
        for _ <- ctx.cancelCurrentTurn()
        yield
          val resetState = state
            .withMessages(Nil)
            .withLatestUsage(None)
            .withRecentMessageIds(Nil)
            .withLifecycleCleared
          idle(agentDef, resources, depth, parentRef, resetState)

      case UpdateContextWindow(window) =>
        IO.pure(idle(agentDef, resources, depth, parentRef, state.withContextWindow(window).withLifecycleCleared))

      case n: BackgroundTaskNotification =>
        (ctx.self ! n.toExternalEvent) *> IO.pure(idle(agentDef, resources, depth, parentRef, state))

      case UserAnswered(answers) =>
        state.execution.interaction.flatMap(_.pendingAskUserReplyTo) match
          case Some(replyTo) =>
            (replyTo ! answers) *> IO.pure(idle(agentDef, resources, depth, parentRef, state.withInteraction(None)))
          case None => IO.pure(idle(agentDef, resources, depth, parentRef, state))

      case PermissionAnswered(approved) =>
        state.pendingPermission match
          case Some(deferred) =>
            deferred.complete(approved).void.handleErrorWith(_ => IO.unit) *>
              IO.pure(idle(agentDef, resources, depth, parentRef, state))
          case None => IO.pure(idle(agentDef, resources, depth, parentRef, state))

      case AgentCommand.ForwardPermission(deferred, permJson) =>
        if state.pendingPermission.isDefined then
          deferred.complete(false).void.handleErrorWith(_ => IO.unit) *>
            IO.pure(idle(agentDef, resources, depth, parentRef, state))
        else
          state.wsSend(permJson).handleErrorWith(_ => IO.unit) *>
            IO.pure(idle(agentDef, resources, depth, parentRef, state.withPendingPermission(Some(deferred))))

      case AgentCommand.SetBypass(bypass) =>
        IO.pure(idle(agentDef, resources, depth, parentRef, state.withBypass(bypass)))

      case _ =>
        IO.pure(idle(agentDef, resources, depth, parentRef, state))
  end idle

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

      case UpdateLifecycle(lc) =>
        IO.pure(processing(agentDef, resources, depth, parentRef, state.withLifecycle(lc), pending))

      case UpdateGitBranch(branch) =>
        IO.pure(processing(agentDef, resources, depth, parentRef, state.withGitBranch(branch), pending))

      case LlmComplete(result, replyTo, turnId) =>
        if turnId != state.currentTurnId then IO.pure(processing(agentDef, resources, depth, parentRef, state, pending))
        else
          val updatedState = state
            .withLatestUsage(result.usage.orElse(state.latestUsage))
            .withLastModel(result.model.orElse(state.lastModel))
            .updateContextWindowIfNeeded(result.contextWindow)
          // Emit usage update for depth=0
          val usageIO = updatedState.latestUsage.fold(IO.unit)(usage =>
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
          usageIO *> handleLlmCompleteBranch(
            agentDef,
            resources,
            depth,
            parentRef,
            updatedState,
            replyTo,
            result,
            pending
          )

      case LlmFailed(error, replyTo, turnId) =>
        if turnId != state.currentTurnId then IO.pure(processing(agentDef, resources, depth, parentRef, state, pending))
        else
          val cleanedState = state
          val errMsg = error match
            case e: FallbackExhaustedError =>
              e.attempts.map(a => s"${a.providerId}/${a.model}").mkString("; ")
            case _ =>
              Option(error.getMessage)
                .filter(_.nonEmpty)
                .map(m => s"LLM request failed: ${m.take(200)}")
                .getOrElse(s"LLM request failed: ${error.getClass.getSimpleName}")
          for
  
            _ <- cleanedState.sessionId.fold(IO.unit) { sid =>
              ctx.forkTurn(
                cleanedState
                  .wsSend(Json.obj("type" -> "error".asJson, "sessionId" -> sid.asJson, "message" -> errMsg.asJson))
                  .handleErrorWith(_ => IO.unit) *>
                  emitSessionBusy(cleanedState.wsSend, sid, busy = false)
              )
            }
          yield idle(
            agentDef,
            resources,
            depth,
            parentRef,
            cleanedState.withStatus(AgentStatus.Error(error.getMessage))
          )
          end for

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
        val newMessages = baseMessages ++ List(assistantMsg, resultMsg)
        val updatedState = state.copy(execution = state.execution.copy(messages = newMessages, interaction = None))
        for
          _ <- ctx.forkTurn(
            persistIfSession(resources, updatedState)
              .handleErrorWith(e => IO(logger.warn(s"Persist failed: ${e.getMessage}")))
          )
          result <- pipeLlmCall(agentDef, resources, depth, parentRef, updatedState, tc.replyTo)
        yield result

      case Interrupt() =>
        for
          _ <- ctx.cancelCurrentTurn()

          _ <- emitStream(state.wsSend, AgentStreamEvent.Interrupted, isSubagent = depth > 0, state.sessionId)
        yield idle(agentDef, resources, depth, parentRef, state.resetForInterrupt)

      case Stop(_) =>
        for
          _ <- ctx.cancelCurrentTurn()

          _ <- fireLifecycleStopHooks(resources, state)
        yield Behaviors.stopped

      case ResetSession =>
        for

          _ <- emitStream(state.wsSend, AgentStreamEvent.Interrupted, isSubagent = depth > 0, state.sessionId)
        yield
          val resetState = state
            .withMessages(Nil)
            .withLatestUsage(None)
            .withRecentMessageIds(Nil)
            .withLifecycleCleared
            .resetToIdle(Nil)
          idle(agentDef, resources, depth, parentRef, resetState)

      case n: BackgroundTaskNotification =>
        (ctx.self ! n.toExternalEvent) *> IO.pure(processing(agentDef, resources, depth, parentRef, state, pending))

      case ExternalEvent(source, eventType, payload, _, correlationId) =>
        val updatedExec = state.execution.copy(
          pendingEvents = state.execution.pendingEvents :+
            AgentCommand.ExternalEvent(source, eventType, payload, JsonObject.empty, correlationId)
        )
        emitStream(
          state.wsSend,
          AgentStreamEvent.ExternalEventReceived(source, eventType, correlationId),
          isSubagent = depth > 0,
          state.sessionId
        ) *>
          IO.pure(processing(agentDef, resources, depth, parentRef, state.copy(execution = updatedExec), pending))

      case AgentCommand.AskUser(_, items, replyToOpt) =>
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
        val updatedState = state.copy(execution =
          state.execution.copy(interaction =
            Some(InteractionState(pendingPermission = state.pendingPermission, pendingAskUserReplyTo = replyToOpt))
          )
        )
        state.wsSend(askJson).handleErrorWith { _ =>
          (replyToOpt.foreach(r => r ! Nil)); IO.unit
        } *> IO.pure(processing(agentDef, resources, depth, parentRef, updatedState, pending))

      case UserAnswered(answers) =>
        state.execution.interaction.flatMap(_.pendingAskUserReplyTo) match
          case Some(replyTo) =>
            (replyTo ! answers) *>
              IO.pure(processing(agentDef, resources, depth, parentRef, state.withInteraction(None), pending))
          case None => IO.pure(processing(agentDef, resources, depth, parentRef, state, pending))

      case PermissionAnswered(approved) =>
        state.pendingPermission match
          case Some(deferred) =>
            deferred.complete(approved).void.handleErrorWith(_ => IO.unit) *>
              IO.pure(processing(agentDef, resources, depth, parentRef, state.withPendingPermission(None), pending))
          case None => IO.pure(processing(agentDef, resources, depth, parentRef, state, pending))

      case SetPermissionDeferred(deferred) =>
        IO.pure(processing(agentDef, resources, depth, parentRef, state.withPendingPermission(Some(deferred)), pending))

      case AgentCommand.ForwardPermission(deferred, permJson) =>
        if state.pendingPermission.isDefined then
          deferred.complete(false).void.handleErrorWith(_ => IO.unit) *>
            IO.pure(processing(agentDef, resources, depth, parentRef, state, pending))
        else
          state.wsSend(permJson).handleErrorWith(_ => IO.unit) *>
            IO.pure(
              processing(agentDef, resources, depth, parentRef, state.withPendingPermission(Some(deferred)), pending)
            )

      case AgentCommand.SetBypass(bypass) =>
        IO.pure(processing(agentDef, resources, depth, parentRef, state.withBypass(bypass), pending))

      case UpdateContextWindow(window) =>
        IO.pure(
          processing(
            agentDef,
            resources,
            depth,
            parentRef,
            state.withContextWindow(window).withLifecycleCleared,
            pending
          )
        )

      case msg: UserInput =>
        IO.pure(processing(agentDef, resources, depth, parentRef, state, pending :+ msg))
      case msg: SkillActivate =>
        IO.pure(processing(agentDef, resources, depth, parentRef, state, pending :+ msg))
      case msg: AskQuestion =>
        IO.pure(processing(agentDef, resources, depth, parentRef, state, pending :+ msg))

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

    new Behavior[AgentCommand]:
      def receive(c: ActorContext[AgentCommand], msg: AgentCommand): IO[Behavior[AgentCommand]] =
        base.receive(c, msg)
      override def onError(c: ActorContext[AgentCommand], err: Throwable): IO[Behavior[AgentCommand]] =
        c.log.error(s"Jarvis error in processing, returning to idle: ${err.getMessage}")
          .as(idle(agentDef, resources, depth, parentRef,
            state.withStatus(AgentStatus.Idle).withInteraction(None)))
  end processing

  // ============================================================
  // Memory consolidation state
  // ============================================================

  private def memoryConsolidating(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    pending: List[AgentCommand] = Nil
  )(using ctx: ActorContext[AgentCommand]): Behavior[AgentCommand] =
    val base = Behaviors.receiveMessage[AgentCommand]:

      case LlmComplete(result, _, turnId) =>
        if turnId != state.currentTurnId then
          IO.pure(memoryConsolidating(agentDef, resources, depth, parentRef, state, pending))
        else
          for
            _ <- applyConsolidationResult(result, agentDef)
            _ = logAgentEvent(
              agentDef,
              depth,
              state.sessionId,
              state.sessionName,
              "memory-consolidation-done",
              s"textLen=${result.text.length}"
            )
          yield
            pending.headOption.foreach(msg => ctx.self ! msg)
            idle(agentDef, resources, depth, parentRef, state)

      case LlmFailed(error, _, turnId) =>
        if turnId != state.currentTurnId then
          IO.pure(memoryConsolidating(agentDef, resources, depth, parentRef, state, pending))
        else
          logger.warn(s"Memory consolidation failed: ${error.getMessage}")
          pending.headOption.foreach(msg => ctx.self ! msg)
          IO.pure(idle(agentDef, resources, depth, parentRef, state))

      case Interrupt() =>
        for
          _ <- ctx.cancelCurrentTurn()

        yield
          pending.headOption.foreach(msg => ctx.self ! msg)
          idle(agentDef, resources, depth, parentRef, state)

      case Stop(_) =>
        for
          _ <- ctx.cancelCurrentTurn()

          _ <- fireLifecycleStopHooks(resources, state)
        yield Behaviors.stopped

      case msg: UserInput =>
        IO.pure(memoryConsolidating(agentDef, resources, depth, parentRef, state, pending :+ msg))
      case msg: SkillActivate =>
        IO.pure(memoryConsolidating(agentDef, resources, depth, parentRef, state, pending :+ msg))
      case msg: AskQuestion =>
        IO.pure(memoryConsolidating(agentDef, resources, depth, parentRef, state, pending :+ msg))

      case _ =>
        IO.pure(memoryConsolidating(agentDef, resources, depth, parentRef, state, pending))

    new Behavior[AgentCommand]:
      def receive(c: ActorContext[AgentCommand], msg: AgentCommand): IO[Behavior[AgentCommand]] =
        base.receive(c, msg)
      override def onError(c: ActorContext[AgentCommand], err: Throwable): IO[Behavior[AgentCommand]] =
        c.log.error(s"Jarvis error in memoryConsolidating, returning to idle: ${err.getMessage}")
          .as(idle(agentDef, resources, depth, parentRef,
            state.withStatus(AgentStatus.Idle).withInteraction(None)))
  end memoryConsolidating

  // ============================================================
  // LLM complete branch selector
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
    if state.askMode.isDefined && result.toolCalls.isEmpty then
      finishAskMode(agentDef, resources, depth, parentRef, state, result.text, result.model)
    else if result.toolCalls.nonEmpty then
      pipeToolExecutions(agentDef, resources, depth, parentRef, state.withEmptyResponseRetries(0), result, replyTo)
    else if result.text.nonEmpty || result.thinking.nonEmpty then
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
  // Finish turn — emit Done, persist, check delegation
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
    val assistantContent = (thinking, text) match
      case (None, _) => Left(text)
      case (Some(t), "") => Right(List(ContentBlock.Thinking(t, thinkingSignature)))
      case (Some(t), txt) => Right(List(ContentBlock.Thinking(t, thinkingSignature), ContentBlock.Text(txt)))
    val newMessages = state.messages :+ Message(MessageRole.Assistant, assistantContent)

    val sendTextIO =
      if !textAlreadyStreamed && text.nonEmpty then
        emitStream(state.wsSend, AgentStreamEvent.TextDelta(text), isSubagent = false, state.sessionId)
      else IO.unit

    for
      _ <- sendTextIO
      _ <- state.sessionId.fold(IO.unit) { sid =>
        val doneEvent = AgentStreamEvent.Done(
          model.orElse(state.lastModel),
          contextWindow = Some(state.contextWindow),
          inputTokens = state.latestUsage.map(_.inputTokens),
          compactThreshold = Some(CompactConfig().compactionTriggerRatio(state.contextWindow))
        )
        ctx.forkTurn(
          state
            .wsSend(doneEvent.toJson(ctx.self.path.name, false, state.sessionId))
            .handleErrorWith(_ => IO.unit) *>
            emitSessionBusy(state.wsSend, sid, busy = false)
        )
      }
      _ <- state.sessionId.fold(IO.unit) { sid =>
        ctx.forkTurn(
          (resources.sessionStore.saveMessagesForSession(sid, newMessages) *>
            resources.sessionStore.flushIndex)
            .handleErrorWith(e => IO(logger.warn(s"Save/flush session failed: ${e.getMessage}")))
        )
      }
      targetState = state.copy(execution = ExecutionContext.idle(newMessages, state.execution.turnIdx))
      result <-
        if hasDelegation(newMessages) then
          logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "memory-consolidation-start", "")
          startMemoryConsolidation(agentDef, resources, depth, parentRef, targetState)
        else IO.pure(idle(agentDef, resources, depth, parentRef, targetState))
    yield result
    end for
  end finishTurn

  // ============================================================
  // Memory consolidation
  // ============================================================

  private def startMemoryConsolidation(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState
  )(using ctx: ActorContext[AgentCommand]): IO[Behavior[AgentCommand]] =
    val turnId = state.currentTurnId + 1
    val existingMemory = MemoryStore.loadAgentMemory(agentDef.name).getOrElse("(no existing memories)")
    val conversationSummary = buildConversationSummary(state.messages)
    val consolidationInput = buildConsolidationInput(agentDef.name, existingMemory, conversationSummary)

    val llmIo = for
      result <- resources.llm
        .sendStream(
          LlmRequest(
            messages = List(Message(MessageRole.User, Left(consolidationInput))),
            sessionId = state.sessionId.getOrElse(ctx.self.path.name),
            agentId = agentDef.name,
            tools = None,
            maxTokens = Some(resources.agentLibrary.globalMaxTokens),
            thinking = None,
            systemStable = Some(ConsolidationSystemPrompt)
          )
        )
        .compile
        .toList
        .map(aggregateChunks)
        .attempt
      _ <- result match
        case Right(r) => ctx.self ! LlmComplete(r, None, turnId)
        case Left(e) => ctx.self ! LlmFailed(e, None, turnId)
    yield ()

    for _ <- ctx.forkTurn(llmIo.handleErrorWith { e =>
        IO(logger.warn(s"Consolidation LLM call failed: ${e.getMessage}")) *>
          (ctx.self ! LlmFailed(e, None, turnId))
      })
    yield memoryConsolidating(agentDef, resources, depth, parentRef, state.withCurrentTurnId(turnId))

  end startMemoryConsolidation

  private def applyConsolidationResult(result: ConsumeResult, agentDef: AgentDef): IO[Unit] =
    val content = result.text.trim
    if content.nonEmpty then
      IO(logger.info(s"Consolidation: writing ${content.length} chars to ${agentDef.name} memory")) *>
        MemoryStore.saveAgentMemory(agentDef.name, content)
    else IO(logger.warn("Consolidation: empty response, skipping"))

  // ============================================================
  // Ask mode complete
  // ============================================================

  private def finishAskMode(
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
    for _ <- ctx.forkTurn(
        state
          .wsSend(
            Json.obj(
              "type" -> "askDone".asJson,
              "sessionId" -> sessionId.asJson,
              "durationMs" -> 0L.asJson,
              "model" -> model.getOrElse("").asJson
            )
          )
          .handleErrorWith(_ => IO.unit) *>
          emitSessionBusy(state.wsSend, sessionId, busy = false)
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

  end finishAskMode

  private def isAskReminder(msg: Message): Boolean =
    msg.role == MessageRole.User && (msg.content match
      case Left(text) => text.contains("<system-reminder>") && text.contains("ephemeral follow-up question")
      case _ => false)

  // ============================================================
  // Empty response handler (simplified)
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
    val retryCount = state.emptyResponseRetries
    if retryCount < MaxEmptyRetries then
      logger.info(s"Empty response, retrying (${retryCount + 1}/$MaxEmptyRetries)")
      pipeLlmCall(agentDef, resources, depth, parentRef, state.withEmptyResponseRetries(retryCount + 1), replyTo)
    else
      logger.warn(s"Empty response after $MaxEmptyRetries retries")
      finishTurn(
        agentDef,
        resources,
        depth,
        parentRef,
        state,
        replyTo,
        s"[No response after $MaxEmptyRetries retries]",
        None,
        None,
        false,
        result.model
      )
    end if
  end handleEmptyResponse

  // ============================================================
  // Helpers
  // ============================================================

  private def hasDelegation(messages: List[Message]): Boolean =
    messages.exists(_.content match
      case Right(blocks) =>
        blocks.exists {
          case ContentBlock.ToolUse(_, "Delegate", _) => true
          case _ => false
        }
      case _ => false)

  private def buildConversationSummary(messages: List[Message]): String =
    val summary = messages
      .map { msg =>
        val role = msg.role match
          case MessageRole.User => "User"
          case MessageRole.Assistant => "Jarvis"
          case MessageRole.System => "System"
        msg.content match
          case Left(text) => s"$role: $text"
          case Right(blocks) =>
            val parts = blocks.flatMap {
              case ContentBlock.Text(t) => Some(t)
              case ContentBlock.ToolUse(name, _, _) => Some(s"[Called tool: $name]")
              case ContentBlock.ToolResult(_, content, _) => Some(s"[Tool result: ${content.take(500)}]")
              case _ => None
            }
            if parts.nonEmpty then s"$role: ${parts.mkString("\n")}" else ""
      }
      .filter(_.nonEmpty)
      .mkString("\n\n---\n\n")
    if summary.length > 20000 then summary.take(20000) + "\n\n[... truncated ...]" else summary

  end buildConversationSummary

  private def buildConsolidationInput(agentName: String, existingMemory: String, conversation: String): String =
    s"""## Existing Agent Memory ($agentName)
       |
       |$existingMemory
       |
       |## Recent Conversation
       |
       |$conversation
       |
       |## Task
       |
       |Review the conversation above. Output the COMPLETE updated memory file.
       |Incorporate any new long-term memories following the rules in the system prompt.
       |Output ONLY the memory file content — no explanations, no markdown code fences.""".stripMargin

  private val ConsolidationSystemPrompt =
    """You are a memory consolidation system. Review a recent conversation and update the agent's long-term memory.
      |
      |## Memory Format
      |Each entry is a single line: [tag] description
      |
      |Tags:
      |- [fact]: What happened, what was decided, who is involved
      |- [experience]: What worked well, what didn't, lessons learned
      |- [workflow]: Reusable step-by-step processes
      |- [skill]: Techniques or approaches learned
      |- [preference]: User preferences or behavioral patterns
      |- [decision]: Architectural or design decisions
      |- [gotcha]: Pitfalls, edge cases, non-obvious behavior
      |- [correction]: Corrections to previous mistakes
      |
      |## Rules
      |1. Extract ONLY long-term information worth remembering across sessions
      |2. Do NOT include short-term details: specific file changes, command outputs, code snippets
      |3. Keep entries concise — one line each
      |4. If existing entries are outdated, update them in place
      |5. If existing entries conflict with new information, keep the newer version
      |6. Preserve existing entries that are still accurate
      |7. Use → prefix for cross-references between entries (e.g. →1efabd0558e8)
      |
      |## Output
      |Output ONLY the complete updated memory file content. No explanations. No code fences.""".stripMargin

  // ============================================================
  // Pipe wrappers — thread processing callback into AgentCore
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

end JarvisActor
