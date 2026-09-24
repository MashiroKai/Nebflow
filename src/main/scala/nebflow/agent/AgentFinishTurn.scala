/* 从 AgentActor 迁出(行为保持重构,2026-09-25)。 */
package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.actor.*
import nebflow.core.*
import nebflow.core.flow.TeamSessionRegistry
import nebflow.shared.*

/**
 * turn 收尾族:原 object AgentActor 内 handleLlmCompleteBranch / finishTurn /
 * finishTurnCont / markTeamBusy / markTeamIdle / fullyIdle / emitDequeuedWs
 * 的实现整体迁入本件(方法体逐字未动)。AgentActor 侧保留同名委托 def(签名与
 * 默认参数原样),全部调用点零改动;pipeLlmCall / pipeToolExecutions 留守
 * AgentActor,经 import AgentActor.* 引用。
 */
private[agent] object AgentFinishTurn:
  import nebflow.agent.AgentActor.*

  // ============================================================
  // LlmComplete branch selector
  // ============================================================

  private[agent] def handleLlmCompleteBranch(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    replyTo: Option[ActorRef[AgentEvent]],
    result: ConsumeResult,
    pending: List[AgentCommand]
  )(using ctx: ActorContext[AgentCommand]): IO[Behavior[AgentCommand]] =
    // Single-stage compaction (2026-08-31 redesign): the former Save stage is
    // gone — compaction only compresses. Every pending compaction is a
    // Compact turn: tools disabled (pipeLlmCall sets tools=Some(Nil)),
    // text-only summary required.
    if state.pendingCompaction.isDefined && result.toolCalls.isEmpty && result.text.nonEmpty then
      handleCompactResponse(agentDef, resources, depth, parentRef, state, result.text)
    else if state.pendingCompaction.exists(
        _.phase == CompactionPhase.Compact
      ) && result.toolCalls.isEmpty && result.text.isEmpty
    then
      // F0 (2026-08-30, compact-injection-shield Q1): a Compact-phase response
      // with EMPTY text (thinking-only) must be treated as a compaction
      // FAILURE, never allowed to fall through to the mail-check rung. Tools
      // are disabled during compaction (pipeLlmCall sets tools=Some(Nil)), so
      // the "you must call Mail" reminder loop is a deterministic dead loop —
      // production deaths 03:29/03:41/08:45 each burned ~219K input ×2 retries
      // before compaction-abandoned silently. handleCompactFailure increments
      // the circuit-breaker counter, emits CompactFailed, completes the
      // deferred waiter, and (auto-compaction) ends the turn with an honest
      // failure instead of laundering it through the mail-reminder mechanism.
      handleCompactFailure(
        agentDef,
        resources,
        depth,
        parentRef,
        state,
        "Compact phase returned no text (thinking-only response) — the summary must be written as text, not reasoning"
      )
    else if state.pendingCompaction.isDefined && result.toolCalls.nonEmpty then
      handleCompactFailure(agentDef, resources, depth, parentRef, state, "Compact model unexpectedly called tools")
    else if state.askMode.isDefined && result.toolCalls.isEmpty then
      handleAskComplete(agentDef, resources, depth, parentRef, state, result.text, result.model)
    else if result.toolCalls.nonEmpty then
      pipeToolExecutions(agentDef, resources, depth, parentRef, state.withEmptyResponseRetries(0), result, replyTo)
    else if result.text.nonEmpty || result.thinking.nonEmpty then
      // Mail check: team members must call Mail before finishing.
      // After MaxMailReminders retries, give up and finishTurn (avoid infinite loop).
      if state.expectsMail && !state.mailUsedThisTurn && state.mailReminders < MaxMailReminders then
        handleMissingMail(agentDef, resources, depth, parentRef, state, replyTo, result)
      else
        if state.expectsMail && !state.mailUsedThisTurn then
          logAgentEvent(
            agentDef,
            depth,
            state.sessionId,
            state.sessionName,
            "mail-give-up",
            s"agent finished without Mail after $MaxMailReminders reminders"
          )
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

  private[agent] def finishTurn(
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
    // ── Zombie-compaction guard ──
    // A pending CompactionJob must never survive into idle: the next normal
    // LLM reply would be misrouted by handleLlmCompleteBranch's pendingCompaction
    // ladder as the compact summary and REPLACE all messages (data loss).
    // Legitimate compaction flows never reach finishTurn — the ladder
    // intercepts every LlmComplete while a job is pending — so arriving here
    // with a job means the attempt died mid-turn (context overflow on the
    // compact call, max-tokens truncation, …). Complete the deferred waiter,
    // record the failure. Mirrors CompactionComplete(Left) / handleCompactFailure /
    // Interrupt. Events queued during the dead compaction are then delivered
    // normally by finishTurnCont's pendingEvents drain (job now cleared).
    val (normalizedState, compactionCleanup) = state.pendingCompaction match
      case Some(job) =>
        logAgentEvent(
          agentDef,
          depth,
          state.sessionId,
          state.sessionName,
          "compaction-abandoned",
          s"reason=turn-ended-before-compact job=${job.subagentId} phase=${job.phase.toString}"
        )
        val now = System.currentTimeMillis()
        (
          // compactui 批（2026-09-15 事故 ②）：同中断面，先把压缩轮临时输入摘掉。
          dropCompactionScratch(state)
            .withPendingCompaction(None)
            .withCompactionFailures(state.compactionFailures + 1)
            .withLastCompactionFailureAt(now),
          job.replyDeferred
            .fold(IO.unit)(d =>
              d.complete(Left("Compaction abandoned: turn ended before the compact phase"))
                .void
                .handleErrorWith(_ => IO.unit)
            ) *>
            // compactui 批（2026-09-15 事故 ①）：本路径原先只记 lifecycle 事件，
            // 前端收不到终局帧 ⇒ pill 永挂（与中断面同一缺陷类，一并补齐）。
            emitAbandonedCompaction(state, depth)
        )
      case None => (state, IO.unit)
    // #22 (2026-08-19): 空轮必须留痕——thinking-only 响应（text 空、无工具）
    // 结束 turn 时零输出零事件，从外部（含派工 Manager）看与「turn 静默挂死」
    // 无法区分（14:40/14:47 Manager 两案实为此形态）。留痕后法证可分。
    if text.isEmpty then
      logAgentEvent(
        agentDef,
        depth,
        state.sessionId,
        state.sessionName,
        "turn-ended-empty",
        s"model=${model.getOrElse("-")} thinking=${thinking.map(_.length).getOrElse(0)} msgs=${state.messages.size}"
      )
    val isSubagent = parentRef.isDefined
    val sendText = !textAlreadyStreamed && text.nonEmpty
    val assistantContent = (thinking, text) match
      case (None, _) => Left(text)
      case (Some(t), "") => Right(List(ContentBlock.Thinking(t, thinkingSignature)))
      case (Some(t), txt) =>
        Right(List(ContentBlock.Thinking(t, thinkingSignature), ContentBlock.Text(txt)))
    val newMessages = normalizedState.messages :+ Message(MessageRole.Assistant, assistantContent)
    val sendTextIO =
      if sendText then
        emitStream(state.wsSend, AgentStreamEvent.TextDelta(text), isSubagent = isSubagent, state.sessionId)
      else IO.unit
    for
      _ <- compactionCleanup
      _ <- sendTextIO
      result <- finishTurnCont(
        agentDef,
        resources,
        depth,
        parentRef,
        normalizedState,
        replyTo,
        newMessages,
        text,
        model,
        thinking,
        thinkingSignature,
        textAlreadyStreamed,
        isSubagent
      )
    yield result

    end for

  end finishTurn

  private[agent] def finishTurnCont(
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
    // True when the text was streamed incrementally to the client during the
    // turn (normal path); false when it is being delivered as a single final
    // burst at finish (error paths: compaction failed / context exceeded /
    // max tokens). Logged verbatim as textStreamed — do NOT invert.
    textStreamed: Boolean,
    isSubagent: Boolean
  )(using ctx: ActorContext[AgentCommand]): IO[Behavior[AgentCommand]] =
    logAgentEvent(
      agentDef,
      depth,
      state.sessionId,
      state.sessionName,
      "turn-complete",
      s"msgs=${state.messages.size} textLen=${text.length} textStreamed=$textStreamed " +
        s"thinking=${thinking.map(_.length).getOrElse(0)} model=${model.getOrElse("-")}"
    )
    // #25 (nested delegation dead-letter): "turn ended" is NOT "task completed"
    // while spawned sub-agents are still in flight. The completion notification
    // (supervisor adapter / ask fork / flow bridge) is PARKED in
    // execution.owedCompletion instead of being sent — BackoffSupervisor would
    // otherwise stop this actor on Completed and the grandchildren's results
    // would dead-letter. When a later turn ends with the barrier at 0, every
    // parked target receives the Completed event carrying the FINAL synthesized
    // text and the debt clears. Root agents (replyTo=None, no debt) unaffected.
    val completionTargets: List[ActorRef[AgentEvent]] =
      (replyTo.toList ++ state.execution.owedCompletion).distinct
    val subagentsInFlight = state.execution.outstandingSubagentResults > 0
    val owedAfter: List[ActorRef[AgentEvent]] = if subagentsInFlight then completionTargets else Nil

    // #407: turn 收尾回 idle（原 else 分支提取）。queue drain 分支的 idle-gate
    // 拦截也复用此收尾——queue 延迟投递但本 turn 正常结束（Done/持久化/debt
    // 照常；pendingMailQueueCount 保留，子 agent 完成事件驱动的下一次
    // finishTurnCont 会重检 gate 再 drain）。
    def returnToIdle: IO[Behavior[AgentCommand]] =
      val effectiveInputTokens = state.latestUsage
        .map(_.inputTokens)
        .filter(_ > 0)
        .getOrElse(nebflow.core.compact.TokenEstimator.estimate(state.messages))
      val doneEvent = AgentStreamEvent.Done(
        model.orElse(state.lastModel),
        contextWindow = Some(state.contextWindow),
        inputTokens = Some(effectiveInputTokens),
        compactThreshold = Some(state.effectiveCompactThresholdRatio),
        outputTokens = state.latestUsage.flatMap(u => Option.when(u.outputTokens > 0)(u.outputTokens))
      )
      val emitDoneIO =
        if isSubagent then
          // Synchronous emit to prevent markIdle/markBusy race condition
          emitStreamIO(state.wsSend, doneEvent, isSubagent = true, state.sessionId)
            .handleErrorWith(e =>
              // 2026-09-10 死日志修复：去掉外层 IO(...)（内层日志永不执行）。
              NebflowLogger.forName("nebflow.agent").warn(s"finishTurn: emitDone (sync) failed: ${e.getMessage}").void
            )
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
                  // 2026-09-10 死日志修复：去掉外层 IO(...)。
                  NebflowLogger
                    .forName("nebflow.agent")
                    .warn(s"finishTurn: Done event delivery failed: ${e.getMessage}")
                ) *>
                emitSessionBusy(state.wsSend, sid, busy = false))
                .handleErrorWith(e =>
                  // 2026-09-10 死日志修复：去掉外层 IO(...)。
                  NebflowLogger
                    .forName("nebflow.agent")
                    .warn(s"finishTurn: Done+sessionBusy chain failed: ${e.getMessage}")
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
                NebflowLogger.forName("nebflow.agent").warn(s"Save/flush session failed: ${e.getMessage}")
              )
          )
        )
        _ <-
          // #25 (THE fix): the terminal Completed must NOT fire while spawned
          // sub-agents are in flight — BackoffSupervisor.notifyParentAndStop
          // would stop this actor and the grandchildren's results would
          // dead-letter (the root would only ever see the relay text). Park
          // the debt instead; the batch-completion injection turn re-enters
          // finishTurnCont with the barrier at 0 and pays it off there.
          if subagentsInFlight then IO.unit
          else completionTargets.traverse_(_ ! AgentEvent.Completed(state.sessionId.getOrElse(""), newMessages))
        // Turn fully finished (no pending events / immediate inputs) — back to
        // idle, so clear the team busy mark. The two earlier branches re-enter
        // pipeLlmCall which re-marks busy, so they must NOT clear here.
        _ <- markTeamIdle(agentDef, state.sessionId)
        // P0 阶段 3：turn 完成回 idle——registry 状态快照置 Idle 并 touch。
        // Idle 是合法状态（run_in_background 后台命令等待期），TaskStuckWatcher
        // 只判 Processing——此标记是防误杀铁律的落地。
        _ <- touchRegistryActivity(resources, state.sessionId, AgentStatus.Idle)
        // Drain pending user inputs: forward head to self (agent is now idle,
        // so it will be processed with full metadata by the idle handler). The
        // tail is preserved for the next turn boundary drain.
        _ <- state.execution.pendingUserInputs.headOption.traverse_(msg => ctx.self ! msg)
      yield
        // Empty-queue safe tail (same guard style as TurnBoundaryDrains.drainHead):
        // the REST /api/command turn-end path reaches this branch with an empty
        // queue on every turn — a bare .tail threw "tail of empty list" there.
        val remainingUserInputs =
          val queued = state.execution.pendingUserInputs
          if queued.isEmpty then queued else queued.tail
        val keptInteraction = state.execution.interaction.filter(_.pendingPermission.isDefined)
        val updatedState = state
          .copy(execution =
            ExecutionContext
              .idle(newMessages, state.execution.turnIdx)
              .copy(
                interaction = keptInteraction,
                // Preserve queue when compaction is in progress — CompactionComplete drains it.
                pendingImmediateInputs = state.execution.pendingImmediateInputs,
                pendingUserInputs = remainingUserInputs,
                // Sub-agent barrier: held subtask/delegate results and the
                // outstanding count survive the turn boundary (the batch may
                // still be running — they are injected when it completes).
                pendingEvents = state.execution.pendingEvents,
                outstandingSubagentResults = state.execution.outstandingSubagentResults,
                // #25: parked completion debt survives the turn boundary —
                // paid off when a later turn ends with the barrier at 0.
                owedCompletion = owedAfter,
                // #10 (2026-08-27 mail-queue wedge): ExecutionContext.idle
                // rebuilds pendingMailQueueCount to 0. This returnToIdle tail
                // is ALSO reached by the queue drain branch's idle-gate
                // deferral path (pendingMailQueueCount>0 but subtree busy) —
                // without carrying the counter, the NEXT turn's drain branch
                // (`pendingMailQueueCount > 0`) is permanently false and the
                // disk queue wedges until another MailQueued arrives. The two
                // earlier injection branches already carry it; this tail must too.
                pendingMailQueueCount = state.execution.pendingMailQueueCount
              )
          )
          .withMailTurnCount(state.mailTurnCount + 1)
        idle(agentDef, resources, depth, parentRef, updatedState)
      end for
    end returnToIdle
    if subagentsInFlight && completionTargets.nonEmpty then
      logAgentEvent(
        agentDef,
        depth,
        state.sessionId,
        state.sessionName,
        "completion-parked",
        s"outstanding=${state.execution.outstandingSubagentResults} parked=${completionTargets.size}"
      )
    // Barrier-aware drain: while a parallel sub-agent batch is outstanding,
    // subtask/delegate results stay held (worker blocking semantics) — they are
    // injected ALL together when the batch completes. Other event types keep
    // the existing serial one-at-a-time drain.
    val (drainedEvents, remainingEvents) =
      TurnBoundaryDrains.drainBarrier(
        state.execution.pendingEvents,
        compactionPending = false,
        state.execution.outstandingSubagentResults
      )
    if drainedEvents.nonEmpty then
      val roundCompleteIO: IO[Unit] =
        if !isSubagent then
          state.sessionId.fold(IO.unit)(sid =>
            ctx.forkTurn(
              state
                .wsSend(Json.obj("type" -> "roundComplete".asJson, "sessionId" -> sid.asJson))
                .handleErrorWith(e =>
                  NebflowLogger.forName("nebflow.agent").warn(s"roundComplete delivery failed: ${e.getMessage}")
                )
            )
          )
        else IO.unit
      val eventMessages = List(buildEventReminder(drainedEvents))
      val messagesWithPending = newMessages ++ eventMessages
      logAgentEvent(
        agentDef,
        depth,
        state.sessionId,
        state.sessionName,
        "pending-messages-injected",
        s"events=${drainedEvents.size} remaining=${remainingEvents.size}"
      )
      val updatedState = state
        .copy(execution =
          ExecutionContext
            .idle(messagesWithPending, state.execution.turnIdx)
            .copy(
              pendingEvents = remainingEvents,
              pendingImmediateInputs = state.execution.pendingImmediateInputs,
              pendingMailQueueCount = state.execution.pendingMailQueueCount,
              pendingUserInputs = state.execution.pendingUserInputs,
              outstandingSubagentResults = state.execution.outstandingSubagentResults,
              // #25: hold/replay the completion debt across this injection.
              owedCompletion = owedAfter
            )
        )
        .withNextLoopTurn // Block 3：turn 末事件注入 = 新 turn
      for
        _ <- roundCompleteIO
        // F2: keep the disk snapshot in sync after the drain (drained events gone).
        _ <- persistQueues(state.sessionId, updatedState.execution)
        _ <-
          if !isSubagent then state.sessionId.fold(IO.unit)(sid => emitSessionBusy(state.wsSend, sid, busy = true))
          else IO.unit
        _ <- state.sessionId.fold(IO.unit)(sid =>
          ctx.forkTurn(
            (resources.sessionStore.saveMessagesForSession(sid, messagesWithPending) *>
              resources.sessionStore.flushIndex)
              .handleErrorWith(e =>
                NebflowLogger.forName("nebflow.agent").warn(s"Save/flush session failed: ${e.getMessage}")
              )
          )
        )
        _ <-
          // #25: never signal turn-terminal Completed while sub-agents are in
          // flight — park the debt (owedAfter) and keep the requester waiting.
          if subagentsInFlight then IO.unit
          else completionTargets.traverse_(_ ! AgentEvent.Completed(state.sessionId.getOrElse(""), messagesWithPending))
        result <- pipeLlmCall(agentDef, resources, depth, parentRef, updatedState, None)
      yield result
      end for
    else if state.pendingCompaction.isEmpty && state.execution.pendingImmediateInputs.nonEmpty then
      // 排队消息逐条注入（2026-09-15 ub 缺陷批，禁合并语义）：turn 末只取队首**一件**
      // immediate input 开一个独立 turn；其余留队，由后续 turn 边界逐条消费（到达顺序
      // 不变，每件各自若干 turn ⇒ 每件各自 roundComplete / 一次 save / 一次 pipeLlmCall）。
      // 原缺陷⑥「整批塞进一个新 turn」已删除。
      val (immHead, remainingInputs) = TurnBoundaryDrains.drainHead(
        state.execution.pendingImmediateInputs,
        compactionPending = false
      )
      val immInputs = immHead.toList
      logAgentEvent(
        agentDef,
        depth,
        state.sessionId,
        state.sessionName,
        "immediate-input-injected-at-turn-end",
        s"batch=${immInputs.size} texts=${immInputs.map(_.text.take(40)).mkString(" | ").take(200)}"
      )
      val roundCompleteIO: IO[Unit] =
        if !isSubagent then
          state.sessionId.fold(IO.unit)(sid =>
            ctx.forkTurn(
              state
                .wsSend(Json.obj("type" -> "roundComplete".asJson, "sessionId" -> sid.asJson))
                .handleErrorWith(e =>
                  NebflowLogger.forName("nebflow.agent").warn(s"roundComplete delivery failed: ${e.getMessage}")
                )
            )
          )
        else IO.unit
      val immMessages = immInputs.map(imm =>
        (imm.blocks match
          case Some(blocks) if blocks.nonEmpty => Message(MessageRole.User, Right(blocks))
          case _ => Message(MessageRole.User, Left(imm.text))
        ).copy(source = injectionSourceFor(imm.fromUser, imm.source))
      )
      val messagesWithImmediate = newMessages ++ immMessages
      val updatedState = state
        .copy(execution =
          ExecutionContext
            .idle(messagesWithImmediate, state.execution.turnIdx)
            .copy(
              pendingImmediateInputs = remainingInputs,
              pendingMailQueueCount = state.execution.pendingMailQueueCount,
              pendingUserInputs = state.execution.pendingUserInputs,
              // Sub-agent barrier: held subtask/delegate results and the
              // outstanding count survive the turn boundary.
              pendingEvents = state.execution.pendingEvents,
              outstandingSubagentResults = state.execution.outstandingSubagentResults,
              // #25: hold/replay the completion debt across this injection.
              owedCompletion = owedAfter
            )
        )
        .withNextLoopTurn // Block 3：turn 末注入 = 新 turn（整批共享同一 turn）
      for
        _ <- roundCompleteIO
        // F2: keep the disk snapshot in sync after the drain (injected input gone).
        _ <- persistQueues(state.sessionId, updatedState.execution)
        _ <-
          if !isSubagent then state.sessionId.fold(IO.unit)(sid => emitSessionBusy(state.wsSend, sid, busy = true))
          else IO.unit
        // One blue injected bubble per sourced input — same group, back to back.
        // ② (2026-09-11): 真人输入没有来源标签 ⇒ 也不产注入气泡（fromUser 优先
        // 于 source，与 Message 侧的 injectionSourceFor 同一判据）。
        _ <- immInputs.flatMap { imm =>
          injectionSourceFor(imm.fromUser, imm.source).map(src =>
            emitInjectedUserEvent(
              resources,
              state.wsSend,
              state.sessionId,
              imm.text,
              src,
              imm.eventType,
              imm.sender,
              imm.senderTeam
            )
          )
        }.sequence_
        _ <- state.sessionId.fold(IO.unit)(sid =>
          ctx.forkTurn(
            (resources.sessionStore.saveMessagesForSession(sid, messagesWithImmediate) *>
              resources.sessionStore.flushIndex)
              .handleErrorWith(e =>
                NebflowLogger.forName("nebflow.agent").warn(s"Save/flush session failed: ${e.getMessage}")
              )
          )
        )
        _ <-
          // #25: park the completion debt while sub-agents are in flight.
          if subagentsInFlight then IO.unit
          else
            completionTargets.traverse_(_ ! AgentEvent.Completed(state.sessionId.getOrElse(""), messagesWithImmediate))
        result <- pipeLlmCall(agentDef, resources, depth, parentRef, updatedState, None)
      yield result
      end for
    else if state.pendingCompaction.isEmpty && state.execution.pendingMailQueueCount > 0 then
      // Queue drain: next queued mail (less urgent than immediate inputs).
      // Load head from disk, remove it, inject as a new user turn.
      // #407 idle gate: 目标 agent 连同子树（或 root 发送者→全 team）完全空闲才
      // 投递；不空闲则跳过 drain（保留 count），由子 agent 完成事件驱动的下一次
      // finishTurnCont 重检（不消费队列、不动 head，FIFO 顺序保持）。
      val sid = state.sessionId.getOrElse("")
      for
        items <- nebflow.core.flow.MailQueueStore.load(sid)
        headOpt = items.headOption
        // skipSelfStatus=true：turn 已结束（finishTurnCont 执行中），registry
        // status 还是 Processing 残留——自身不算忙，只查子树与 flow
        idleOk <- fullyIdle(sid, state, resources, skipSelfStatus = true)
        result <-
          if !idleOk then
            IO(
              logAgentEvent(
                agentDef,
                depth,
                state.sessionId,
                state.sessionName,
                "mail-queued-deferred",
                s"head=${headOpt.map(_.id).getOrElse("-")} sender=${headOpt.map(_.fromSession).getOrElse("-")} subtree-busy, queue retained"
              )
            ) *> returnToIdle
          else
            headOpt match
              case Some(item) =>
                val deliver =
                  for
                    // G3: re-read attachment paths at drain time (D6). Lost files
                    // degrade to placeholder text instead of failing the turn.
                    attBlocks <- nebflow.core.tools.ImageInject.drainImagePaths(item.imagePaths)
                    queueMessage = (attBlocks match
                      case Nil => Message(MessageRole.User, Left(item.message))
                      case blocks =>
                        // Message text MUST be the first Text block — blocks replace
                        // the string content entirely.
                        Message(MessageRole.User, Right(ContentBlock.Text(item.message) :: blocks))
                    ).copy(source = Some("mail-queue"))
                    messagesWithQueue = newMessages ++ List(queueMessage)
                    updatedState = state
                      .copy(execution =
                        ExecutionContext
                          .idle(messagesWithQueue, state.execution.turnIdx)
                          .copy(
                            pendingMailQueueCount = state.execution.pendingMailQueueCount - 1,
                            pendingUserInputs = state.execution.pendingUserInputs,
                            // Sub-agent barrier: held results survive the turn boundary.
                            pendingEvents = state.execution.pendingEvents,
                            outstandingSubagentResults = state.execution.outstandingSubagentResults,
                            // #25: hold/replay the completion debt across this injection.
                            owedCompletion = owedAfter
                          )
                      )
                      .withNextLoopTurn // Block 3：Mail 队列投递 = 新 turn
                    _ <- nebflow.core.flow.MailQueueStore.removeHead(sid)
                    _ <-
                      if !isSubagent then emitSessionBusy(state.wsSend, sid, busy = true)
                      else IO.unit
                    _ <- emitInjectedUserEvent(
                      resources,
                      state.wsSend,
                      state.sessionId,
                      item.message,
                      "mail-queue",
                      Some("queue"),
                      Some(item.from),
                      None,
                      Some("queue"),
                      // 气泡四段式统一批（2026-09-15）：legacy 排空腿的 source =
                      // `"mail-queue"` 不在 KIND 词表内 ⇒ 本腿恒不产 header（旧呈现
                      // 逐字保持）。PROJECT 段落回级别照传，未来若纳入词表即生效。
                      sessionProject = state.projectName
                    )
                    _ <- ctx.forkTurn(
                      (resources.sessionStore.saveMessagesForSession(sid, messagesWithQueue) *>
                        resources.sessionStore.flushIndex)
                        .handleErrorWith(e =>
                          NebflowLogger.forName("nebflow.agent").warn(s"Save/flush session failed: ${e.getMessage}")
                        )
                    )
                    _ <-
                      // #25: park the completion debt while sub-agents are in flight.
                      if subagentsInFlight then IO.unit
                      else
                        completionTargets.traverse_(
                          _ ! AgentEvent.Completed(state.sessionId.getOrElse(""), messagesWithQueue)
                        )
                    r <- pipeLlmCall(agentDef, resources, depth, parentRef, updatedState, None)
                  yield r
                // Delivery-layer fingerprint dedup (P0): same sender+recipient+content
                // within the 30min window is consumed without injection — the
                // restart-replay backstop (fingerprints persist across restarts).
                // V13 (2026-09-03): consult scoped to the replay window after this
                // session's activation — outside it a same-content re-send is
                // legitimate and delivers unconditionally.
                mailDedupInReplayWindow(sid, resources).flatMap { inReplayWindow =>
                  nebflow.core.flow.MailDeliveryDedup
                    .tryDeliver(
                      sid,
                      nebflow.core.flow.MailDeliveryDedup.fingerprint(item.from, sid, item.message),
                      System.currentTimeMillis(),
                      inReplayWindow
                    )
                    .flatMap {
                      case false =>
                        // Duplicate within window: consume the head, keep draining —
                        // recurse with count-1 so the next item is tried (or the
                        // queue settles to the done branch when count hits 0).
                        nebflow.core.flow.MailQueueStore.removeHead(sid).void *>
                          finishTurnCont(
                            agentDef,
                            resources,
                            depth,
                            parentRef,
                            state.copy(execution =
                              state.execution.copy(pendingMailQueueCount = state.execution.pendingMailQueueCount - 1)
                            ),
                            replyTo,
                            newMessages,
                            text,
                            model,
                            thinking,
                            thinkingSignature,
                            textStreamed,
                            isSubagent
                          )
                      case true => deliver
                    }
                }
              case None =>
                // Count > 0 but disk queue empty (items cancelled while busy) — reset
                // count and re-enter finishTurnCont which will hit the idle branch.
                finishTurnCont(
                  agentDef,
                  resources,
                  depth,
                  parentRef,
                  state.copy(execution = state.execution.copy(pendingMailQueueCount = 0)),
                  replyTo,
                  newMessages,
                  text,
                  model,
                  thinking,
                  thinkingSignature,
                  textStreamed,
                  isSubagent
                )
      yield result
      end for
    else returnToIdle
    end if
  end finishTurnCont

  /**
   * Team busy signal for the /api/teams/mounted status fallback (busyMap).
   * The frontend prefers realtime agentStart/agentDone events, but on page
   * load / event loss it falls back to the mounted status field — which stays
   * idle forever unless something writes the busyMap. Only team agents
   * (category inferred from `teams/` path by loadAgentFromDir) are shown as
   * team tiles, so only they need marking.
   *
   * Errors are swallowed: a failed status record must never break the turn.
   */
  private[agent] def markTeamBusy(agentDef: AgentDef, sid: Option[String]): IO[Unit] =
    if agentDef.category == "team" then
      sid.fold(IO.unit)(s => TeamSessionRegistry.markBusy(s).handleErrorWith(_ => IO.unit))
    else IO.unit

  private[agent] def markTeamIdle(agentDef: AgentDef, sid: Option[String]): IO[Unit] =
    if agentDef.category == "team" then
      sid.fold(IO.unit)(s => TeamSessionRegistry.markIdle(s).handleErrorWith(_ => IO.unit))
    else IO.unit

  /**
   * #407: queue Mail 空闲 gate —— 目标 agent 连同子树（root 发送者→整个 team）
   * 完全空闲才投递（用户裁定 2026-08-25 19:53/19:57）。判定数据：
   *  - registry 快照（status / outstandingSubagents / 任务型子记录 parentRef）
   *  - RunningFlowRegistry（Q3：RunningFlow.sessionId 关联触发者）
   *  - agent 内部权威 barrier（state.execution.outstandingSubagentResults）
   *
   * 2026-08-28 01:00 统一裁定：投递判定 = 目标 agent 自身+子树空闲，**与发送者
   * 无关**——废除 08-25 按发送者区分（root→全 team 停）的语义：无关成员的忙碌
   * 不再无限期扣住投递（旧 senderIsRoot 分支连同 isTeamTreeIdle 一并退役）。
   */
  private[agent] def fullyIdle(
    sid: String,
    state: AgentState,
    resources: SharedResources,
    skipSelfStatus: Boolean = false
  ): IO[Boolean] =
    for
      registry <- resources.agentRegistry.get
      flows <- nebflow.core.flow.RunningFlowRegistry.list
      // agent 内部权威 barrier（registry 快照之外的一层防御）
      internalIdle = state.execution.outstandingSubagentResults == 0
      selfOk = nebflow.core.flow.MailIdleGate.isAgentTreeIdle(sid, registry, flows, checkStatus = !skipSelfStatus)
    yield selfOk && internalIdle

  /** Emit a WS event so the frontend removes a pending mail-queue item. */
  private[agent] def emitDequeuedWs(wsSend: Json => IO[Unit], sessionId: String, itemId: String): IO[Unit] =
    if sessionId.nonEmpty then
      nebflow.core.flow.MailQueueStore.size(sessionId).flatMap { pendingCount =>
        wsSend(
          Json.obj(
            "type" -> "mailDequeued".asJson,
            "sessionId" -> sessionId.asJson,
            "itemId" -> itemId.asJson,
            "pendingCount" -> pendingCount.asJson
          )
        ).handleErrorWith(_ => IO.unit)
      }
    else IO.unit
