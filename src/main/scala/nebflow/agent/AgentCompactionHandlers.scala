/* 从 AgentActor 迁出(行为保持重构,2026-09-25)。 */
package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.actor.*
import nebflow.core.compact.*
import nebflow.shared.*

/**
 * 压缩/ask 收尾 handler 族:原 object AgentActor 内 handleCompactResponse /
 * handleCompactFailure / emitAbandonedCompaction / dropCompactionScratch /
 * handleAskComplete / isAskReminder / handleTriggerCompaction / handleEmptyResponse
 * 的实现整体迁入本件(方法体逐字未动)。AgentActor 侧保留同名委托 def(签名与
 * 默认参数原样),全部调用点零改动;idle / processing / pipeLlmCall / finishTurn /
 * startDirectCompaction / logAgentEvent 等留驻原处,经 import AgentActor.* 引用。
 */
private[agent] object AgentCompactionHandlers:
  import nebflow.agent.AgentActor.*

  // ============================================================
  // Compact response handlers
  // ============================================================

  private[agent] def handleCompactResponse(
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
    val hookIO = CompactService.runPreCompactHook(
      state.messages,
      resources.hookEngine,
      resources.projectRoot,
      sessionId
    )
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
                FullCompact.parseResponseDetailed(responseText, state.messages, effectiveRoot, readPaths) match
                  case Left(err) =>
                    ctx.self ! AgentCommand.CompactionComplete(Left(err))
                  case Right(outcome) =>
                    val postHookIO = CompactService
                      .runPostCompactHook(
                        state.messages.size,
                        outcome.messages.size,
                        resources.hookEngine,
                        resources.projectRoot,
                        sessionId
                      )
                      .handleErrorWith(_ => IO.unit)
                    // 记忆轨已退役（memory-family-retirement 批，2026-09-29）：压缩双轨
                    // 第二轨随队列族一并删除 ⇒ 本 handler 只跑前置/后置 compaction hook，
                    // 随即装机。行为面收窄为「原轨恒 no-op 形态」：旧轨在 depth != 0 /
                    // 空队列 / 暂停态下本就 IO.unit ⇒ 该三段零行为漂移；有 pending 条目
                    // 时的消耗动作随队列机制停用而消失（如实登记：队列文件不再被消费，
                    // 见 retire 报告 §两特殊件处置）。
                    for
                      _ <- ctx.forkTurn(postHookIO)
                      _ <- ctx.self ! AgentCommand.CompactionComplete(Right(outcome.messages))
                    yield ()
          }
          .handleErrorWith(e => ctx.self ! AgentCommand.CompactionComplete(Left(e.getMessage)))
      )
    yield processing(agentDef, resources, depth, parentRef, state)

  end handleCompactResponse

  private[agent] def handleCompactFailure(
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
  // Compaction abandon face (compactui 批 · 2026-09-15 作者实证)
  // ============================================================

  /**
   * 压缩作业被**放弃**（中断 / turn 提前结束）时的终局帧发射（决策见
   * [[CompactionAbandon.event]]，无作业 = no-op）。
   */
  private[agent] def emitAbandonedCompaction(state: AgentState, depth: Int)(using
    ctx: ActorContext[AgentCommand]
  ): IO[Unit] =
    CompactionAbandon.event(state) match
      case None => IO.unit
      case Some(ev) =>
        emitStreamIO(state.wsSend, ev, isSubagent = depth > 0, state.sessionId)
          .handleErrorWith(_ => IO.unit)

  /**
   * 放弃压缩作业时摘掉压缩轮的**临时输入**（摘要指令 reminder）。
   * 判据与理由见 [[CompactionAbandon.dropScratch]]。
   */
  private[agent] def dropCompactionScratch(state: AgentState): AgentState =
    CompactionAbandon.dropScratch(state)

  // ============================================================
  // Ask complete
  // ============================================================

  private[agent] def handleAskComplete(
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
          // R1（footer 统一 · 作者裁定「带时间」，2026-09-14）：落盘真实时刻 ——
          // 原先不落 timestamp ⇒ 历史行的 footer 时间在刷新后消失（live 传 Date.now）。
          // 与 `askDone` 帧同一次落盘、同一 tick，故取当前时刻即可。
          .appendUiMessages(
            sessionId,
            List(UiMessage.Ask(question, answerText, Some(0L), model, System.currentTimeMillis()))
          )
          .handleErrorWith(e => logger.warn(s"Failed to persist ask UiMessage: ${e.getMessage}"))
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

  private[agent] def isAskReminder(msg: Message): Boolean =
    msg.role == MessageRole.User && (msg.content match
      case Left(text) => text.contains("<system-reminder>") && text.contains("ephemeral follow-up question")
      case _ => false)

  // ============================================================
  // Trigger compaction
  // ============================================================

  private[agent] def handleTriggerCompaction(
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

  private[agent] def handleEmptyResponse(
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
end AgentCompactionHandlers
