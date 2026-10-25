/* 从 AgentActor 迁出(行为保持重构,2026-09-25)。 */
package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.actor.*
import nebflow.core.*
import nebflow.core.ask.AskService
import nebflow.core.compact.*
import nebflow.shared.{NebflowLogger, *}

/**
 * idle 域:原 object AgentActor 内 idle 行为的实现整体迁入本件(方法体逐字
 * 未动;idle 内部的自递归——返回下一 idle behavior——保持原逻辑,解析到本
 * object 的 idle)。AgentActor 侧保留同名委托 def(签名原样),全部调用点
 * (apply 入口 / processing / AgentFrozen 等)零改动。idle 唯一消费的注入判源
 * 助手 inferInjectionSource 随迁本件;touchBarrierSnapshot /
 * visibleExternalEventSource / buildEventReminder / emitInjectedUserEvent /
 * emitSessionBusy / pipeLlmCall 等与 processing/frozen 共用的助手留守
 * AgentActor,经 import AgentActor.* 引用(本 object 只含 def,无顶层状态)。
 */
private[agent] object AgentIdle:
  import nebflow.agent.AgentActor.*

  /**
   * Infer the injection source (任务 P) for a tool-originated UserInput that
   * did not carry an explicit source. Discriminator: clientMessageId is empty
   * (user WS inputs always carry one). The source is derived from the agent's
   * own session id prefix, falling back to the fork-adapter replyTo for
   * Mail ask/fork spawns, and finally the generic "tool".
   *
   *   delegate-… → "delegate"   DelegateTool 内核会话（一次性执行件）
   *   subtask-…  → "subtask"    SubTaskTool worker
   *   dag-…      → "flow"       FlowDagExecutor node
   *   otherwise  → "tool"
   */
  private def inferInjectionSource(
    sessionId: Option[String],
    replyTo: Option[ActorRef[AgentEvent]]
  ): Option[String] =
    val sid = sessionId.getOrElse("")
    if sid.startsWith("delegate-") then Some("delegate")
    else if sid.startsWith("subtask-") then Some("subtask")
    else if sid.startsWith("dag-") then Some("flow")
    else Some("tool")

  /**
   * ExternalEvent 唤醒轮的 replyTo 判据（纯函数，spec 判红锚）。两路合法：
   *   - node- 项目节点（既有，2026-09-05 完成闸批）；
   *   - delegate 会话 × background-task 完成事件（诊断修复批 2026-10-24）——
   *     BackoffSupervisor 等待屏障依赖该唤醒轮的 Completed 复检投递集。
   * 其余（mail/schedule 事件源、非 delegate 会话、subtask——subtask 轨无等待
   * 屏障，Completed 语义零变化）恒 false。
   */
  private[agent] def wakeContinuationApplies(
    isFlowNode: Boolean,
    sessionId: Option[String],
    eventSource: String,
    isDelegateSession: Boolean
  ): Boolean =
    val nodeLeg =
      sessionId.exists(sid => isFlowNode && sid.startsWith(nebflow.core.project.NodeEngine.SessionPrefix))
    val delegateLeg = isDelegateSession && eventSource == "background-task"
    nodeLeg || delegateLeg

  /**
   * 诊断修复批 2026-10-24：delegate 终态事件注入父会话回合 ⇒ 交付回执确认
   * （fire-and-forget，绝不拖注入链）。判据/幂等语义见
   * [[nebflow.core.delegate.DelegateDeliveryReceipts.ackEvent]]。
   */
  private def ackDelegateDelivery(event: AgentCommand.ExternalEvent): IO[Unit] =
    nebflow.core.delegate.DelegateDeliveryReceipts
      .ackEvent(event)
      .handleErrorWith(e =>
        NebflowLogger.forName("nebflow.agent.idle").warn(s"delegate delivery ack failed: ${e.getMessage}")
      )

  // ============================================================
  // Idle state
  // ============================================================

  private[agent] def idle(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState
  )(using ctx: ActorContext[AgentCommand]): Behavior[AgentCommand] =
    Behaviors.receiveMessage:

      case AgentCommand.UserInput(
            text,
            replyTo,
            clientMessageId,
            blocks,
            chatWidth,
            source,
            sender,
            senderTeam,
            delivery,
            eventType,
            intake,
            fromUser,
            project
          ) =>
        val (isDuplicate, dedupedState) = checkDuplicate(clientMessageId, state)
        if isDuplicate then
          logger.info(s"Dropping duplicate message with clientMessageId=${clientMessageId.getOrElse("")}")
          // resend-dedup 批：丢弃即回执 —— 客户端凭回执收掉同 id 重发乐观置位的
          // 幻影 busy（无回执则看门走满阶梯，凭空呈现「仍在处理」）。best-effort。
          emitDuplicateReceipt(state.wsSend, state.sessionId, clientMessageId) *>
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
          // Injection source (任务 P): user WS inputs always carry clientMessageId.
          // Tool-injected inputs (Mail ask/fork, Delegate, SubTask, ImmediateInput
          // converted from Mail delivery) have clientMessageId=None → emit a user
          // event so the frontend renders the task prompt as a visible bubble.
          // ② (2026-09-11): `fromUser` is the second, explicit discriminator —
          // a real human text that travelled the ImmediateInput leg arrives with
          // clientMessageId=None and used to be stamped source="tool" (blue TOOL
          // card + isRealUserTurn=false). 真人 ⇒ no source, never.
          val injectionSource: Option[String] =
            if clientMessageId.isDefined then None
            else injectionSourceFor(fromUser, source.orElse(inferInjectionSource(state.sessionId, replyTo)))
          val enrichedBlocks: Option[List[ContentBlock]] = blocks.filter(_.nonEmpty)
          val stateWithFlush = stateWithWidth
          val userMsg = (enrichedBlocks match
            case Some(bl) => Message(MessageRole.User, Right(bl))
            case None => Message(MessageRole.User, Left(text))
          ).copy(source = injectionSource)
          // ── Held-receipt 先补注入（平台修复批 fix 1 第三腿，2026-10-21）────────
          // 有被子代理批屏障扣留的已产出回执（pendingEvents 非空）⇒ 本回合**先**
          // 注入回执（reminder 合批一件、前置于本输入）、再处理本输入——「父会话
          // 任何后续事件先补注入未读回执再处理事件」。空队列 ⇒ None，本腿逐字现状。
          // 屏障随补注归还（outstanding 清零）：回执全部产出后剩余槽位只能是永不
          // 投递的幻影（issue #31）；仍在飞的真实成员此后经 idle 单件腿照常注入。
          val heldFlush = heldFlushPlan(stateWithFlush)
          val flushBase = heldFlush.map(_.state).getOrElse(stateWithFlush)
          val newMessages =
            flushBase.messages ++ heldFlush.map(_.messages).getOrElse(Nil) :+ userMsg
          val heldFlushIO = heldFlush.fold(IO.unit)(p => flushHeldSideEffects(agentDef, resources, depth, p))
          val sessionBusyIO =
            if depth == 0 then
              flushBase.sessionId.fold(IO.unit)(sid => emitSessionBusy(flushBase.wsSend, sid, busy = true))
            else IO.unit
          val injectedEventIO = injectionSource match
            case Some(src) =>
              emitInjectedUserEvent(
                resources,
                stateWithWidth.wsSend,
                stateWithWidth.sessionId,
                text,
                src,
                eventType,
                sender = sender,
                senderTeam = senderTeam,
                delivery = delivery,
                // idle 直投腿（mailbadge 批）：收件判别字段随件同源落地。
                intake = intake,
                // 气泡四段式统一批（2026-09-15）：PROJECT 段链首级/② 级。
                project = project,
                sessionProject = stateWithWidth.projectName
              )
            case None => IO.unit
          for
            _ <- heldFlushIO
            _ <- sessionBusyIO
            _ <- injectedEventIO
            result <- pipeLlmCall(
              agentDef,
              resources,
              depth,
              parentRef,
              flushBase
                .withMessages(newMessages)
                .withEmptyResponseRetries(0)
                .withMailUsedThisTurn(false)
                // Block 3：新用户/系统 turn 起点——loop 纪元 +1
                .withNextLoopTurn,
              replyTo,
              // D2：真实用户 WS 输入恒带 clientMessageId → UserWake（冻结时段也
              // 放行，用户输入即唤醒）；系统注入（Mail/continue 等 None）→ Gated。
              if clientMessageId.isDefined then DispatchCause.UserWake else DispatchCause.Gated
            )
          yield result
          end for
        end if

      case AgentCommand.AskQuestion(question, askSessionId) =>
        logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "ask-start", s"q=${question.take(60)}")
        val askReminder = AskService.buildAskReminder(question)
        val askState = state
          .withMessages(state.messages :+ askReminder)
          .withAskMode(Some(question))
          .withStatus(AgentStatus.Processing)
          .withNextLoopTurn
        pipeLlmCall(agentDef, resources, depth, parentRef, askState, None, DispatchCause.UserWake)

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
          .withMessages(
            state.messages :+ Message(MessageRole.User, Left(combinedText), source = Some("skill"))
          )
          .withStatus(AgentStatus.Processing)
          .withNextLoopTurn
        val sessionBusyIO2 =
          if depth == 0 then state.sessionId.fold(IO.unit)(sid => emitSessionBusy(state.wsSend, sid, busy = true))
          else IO.unit
        for
          _ <- sessionBusyIO2
          _ <- emitInjectedUserEvent(
            resources,
            state.wsSend,
            state.sessionId,
            input,
            "skill",
            None,
            sessionProject = state.projectName
          )
          result <- pipeLlmCall(agentDef, resources, depth, parentRef, processingState, None, DispatchCause.UserWake)
        yield result

      case AgentCommand.Interrupt() =>
        IO.pure(idle(agentDef, resources, depth, parentRef, state))

      case AgentCommand.Stop(_) =>
        for
          _ <- ctx.cancelCurrentTurn()

          _ <- killSessionShellProcesses(state)
          _ <- fireLifecycleStopHooks(resources, state)
        yield Behaviors.stopped

      case AgentCommand.ClearReadTracker =>
        clearReadTrackerStay(state)(IO.pure(idle(agentDef, resources, depth, parentRef, state)))

      case AgentCommand.ResetSession =>
        for
          _ <- ctx.cancelCurrentTurn()

          _ <- state.readTracker.fold(IO.unit)(t => t.clear())
          // §E-10：/clear 作废影子——清 state.json + 清全部乒乓位（消息已 Nil，C₀
          // 作废；在飞影子的迟到 SwapReady 由 shadowPending 校验丢弃）。
          _ <- state.sessionId.fold(IO.unit)(sid => PingpongStateFile.clear(sid))
        yield
          val resetState = state
            .withMessages(Nil)
            .withLatestUsage(None)
            .withPendingCompaction(None)
            .withCompactionFailures(0)
            .withLastCompactionFailureAt(0L)
            .withRecentMessageIds(Nil)
            .invalidateSystemStableCache
            .withPingpongCleared
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
        // ctxthresh 批：换模型/换窗口后的立即压缩判定同取**生效门限**——
        // 本会话有阈值覆盖时覆盖同样适用于新窗口（无覆盖时逐字 = 现值函数）。
        val threshold = newState.compactThresholdTokens
        if newState.messages.nonEmpty && estimatedTokens > threshold then
          logAgentEvent(
            agentDef,
            depth,
            state.sessionId,
            state.sessionName,
            "model-switch-compact",
            s"newWindow=$window estimated=$estimatedTokens threshold=$threshold msgs=${newState.messages.size}"
          )
          // ── 乒乓门（§A3 判定点之二 / §G1）───────────────────────────────
          // 谓词假 / 开关关 ⇒ AgentPingpong.forkOrLegacyModelSwitch 的 legacy 腿 =
          // 下行原 handleTriggerCompaction 调用形态逐字；影子在飞 ⇒ 布尔排队位
          // （§A4，不堆叠）后回 idle；三连败 ⇒ 回落老路径一拍（§E-1）；退避窗 ⇒
          // 本点不压，回 idle（阈值判定自然再触发）。
          val pp = newState.pingpong
          val ppCfg = CompactConfig()
          if pp.shadowPending.isDefined then
            AgentPingpong.logQueued(
              agentDef,
              depth,
              newState,
              s"model-switch estimated=$estimatedTokens threshold=$threshold"
            )
            IO.pure(idle(agentDef, resources, depth, parentRef, newState.withForkQueued(true)))
          else if pp.ppFailures >= ppCfg.circuitBreakerMax then
            handleTriggerCompaction(
              agentDef,
              resources,
              depth,
              parentRef,
              newState.withPpFailures(0, 0L),
              "full",
              None,
              resumeAfterCompact = false
            )
          else if pp.ppFailures > 0 && !ppCfg.isBackoffSatisfied(
              pp.ppFailures,
              System.currentTimeMillis() - pp.lastPpFailureAt
            )
          then IO.pure(idle(agentDef, resources, depth, parentRef, newState))
          else AgentPingpong.forkOrLegacyModelSwitch(agentDef, resources, depth, parentRef, newState)
          end if
        else IO.pure(idle(agentDef, resources, depth, parentRef, newState))
        end if

      case AgentCommand.SetCompactThresholdRatio(ratio) =>
        // ctxthresh 批：只改存储值（口径④「有会话覆盖用覆盖，无覆盖走现值函数」）。
        // 🔴 刻意不做任何压缩触发——镜像 UpdateContextWindow 的存储语义但去掉其
        // 「顺手压缩」副作用（设计 §7 逐字禁止复用该命令的原因）。
        // 生效时点 = 下一回合边界（判定点 AgentCore 每轮读 state，非启动快照）。
        logAgentEvent(
          agentDef,
          depth,
          state.sessionId,
          state.sessionName,
          "compact-threshold-set",
          s"ratio=${ratio.map(r => f"$r%.4f").getOrElse("default")} " +
            s"effective=${CompactThresholdOverride.effectiveThreshold(state.contextWindow, ratio)}"
        )
        IO.pure(idle(agentDef, resources, depth, parentRef, state.withCompactThresholdRatio(ratio)))

      case n: AgentCommand.BackgroundTaskNotification =>
        forwardBackgroundTaskNotification(n)(IO.pure(idle(agentDef, resources, depth, parentRef, state)))

      case AgentCommand.ExternalEvent(source, eventType, payload, metadata, correlationId, _) =>
        logAgentEvent(
          agentDef,
          depth,
          state.sessionId,
          state.sessionName,
          "external-event",
          s"source=$source type=$eventType"
        )
        val event = AgentCommand.ExternalEvent(source, eventType, payload, metadata, correlationId)
        val sessionBusyIO =
          if depth == 0 then state.sessionId.fold(IO.unit)(sid => emitSessionBusy(state.wsSend, sid, busy = true))
          else IO.unit
        // 任务 Q: high-value result notifications (sub-agent/flow/background/api
        // completion) render as a visible injected-user bubble so the user can
        // see "what result arrived" in the chat stream, not just the LLM history.
        val visSource = visibleExternalEventSource(source, eventType)
        // P1.4: When a retryable sub-agent failure arrives, add a structured
        // system-reminder guiding the LLM to re-delegate — this is more
        // effective than relying on the LLM noticing the failure text alone.
        val retryableHint = eventType == "failed" &&
          metadata("retryable").exists(_.asBoolean.getOrElse(false))
        val reminderText =
          if retryableHint then
            val failureType = metadata("failureType").flatMap(_.asString).getOrElse("unknown")
            val failedSession = metadata("failedSessionId").flatMap(_.asString).getOrElse("")
            val agentName = metadata("agentName").flatMap(_.asString).getOrElse("")
            s"\n<system-reminder>\nA sub-agent task${if agentName.nonEmpty then s" ($agentName)" else ""}" +
              s"${if failedSession.nonEmpty then s" [session=$failedSession]" else ""} failed" +
              s" (failure type: $failureType). This is a retryable error — consider re-delegating" +
              s" the same task with the Delegate or SubTask tool.\n</system-reminder>"
          else ""
        val injectionText = payload + reminderText
        // Receive-time visibility (stream event + bubble) — mirrors the
        // processing-state path so the UI shows each result arriving even when
        // the LLM injection is held back by the batch barrier.
        def receiveVisibility(waiting: Boolean): IO[Unit] =
          emitStream(
            state.wsSend,
            AgentStreamEvent.ExternalEventReceived(source, eventType, correlationId),
            isSubagent = depth > 0,
            state.sessionId
          ) *> (visSource match
            case Some(s) =>
              val agentName = metadata("agentName").flatMap(_.asString)
              emitInjectedUserEvent(
                resources,
                state.wsSend,
                state.sessionId,
                payload,
                s,
                Some(eventType),
                agentName,
                // file-diff → 委托结果行（bug #58 修）：idle 直投腿同源透传
                // delegate 终态 metadata 的 diff 载荷（缺席 = 旧行为逐字）。
                fileChanges = metadata("fileChanges"),
                sessionProject = state.projectName,
                waitingForBatch = waiting
              )
            case None => IO.unit)
        // ── Sub-agent result barrier (worker blocking semantics) ──────────
        // Delegate/SubTask results arriving while more of the same parallel
        // batch is still outstanding are HELD in pendingEvents instead of
        // interrupting the agent one result at a time. When the batch completes
        // (outstanding hits 0), ALL held results are injected together in a
        // single turn. Non-subagent events keep the existing immediate path.
        val isSubagentResult = TurnBoundaryDrains.isSubagentResult(event)
        val outstanding = state.execution.outstandingSubagentResults
        val newOutstanding = if isSubagentResult then math.max(0, outstanding - 1) else outstanding
        val held = state.execution.pendingEvents
        if isSubagentResult && newOutstanding > 0 then
          // #418: an idle parent must NEVER hold a sub-agent result back —
          // there is no in-flight turn to batch into, and holding it here
          // parks the parent asleep until user input (delegate COMPLETED
          // never wakes the parent — the reported bug). Inject THIS result
          // immediately to wake the parent; the rest of the batch stays HELD
          // in pendingEvents (injected together by the batch-complete branch
          // when they arrive), and the counter keeps the decremented
          // newOutstanding so the #25 nested-debt semantics hold.
          for
            _ <- sessionBusyIO
            _ <- receiveVisibility(waiting = false)
            _ <- touchBarrierSnapshot(resources, state.sessionId, newOutstanding, held.size)
            _ <- ackDelegateDelivery(event)
            result <- pipeLlmCall(
              agentDef,
              resources,
              depth,
              parentRef,
              state
                .withMessages(
                  // 媒体统一适配批（2026-10-20）：组装单点 immediateEventMessage——
                  // 带块事件（桥媒体）⇒ Right(Text+blocks)，无块 ⇒ Left 逐字现状。
                  state.messages :+ immediateEventMessage(
                    injectionText,
                    event,
                    visSource.orElse(Some("external"))
                  )
                )
                .withOutstandingSubagentResults(newOutstanding)
                .withNextLoopTurn, // Block 3：外部事件唤醒 = 新 turn
              None
            )
          yield result
          end for
        else if isSubagentResult && held.nonEmpty then
          // Batch complete: inject ALL held results (plus this one) together.
          val batchMessage = buildEventReminder(held :+ event)
          // F2 补漏批（2026-10-22）：汇聚腿清空队列后必须重写磁盘快照——入队面
          // 全量快照过 held 回执（AgentProcessing:1239），此处不持久 ⇒ 已消费回执
          // 残留 injection-queues.json ⇒ respawn 重放为幽灵回执（10-22 10:36/17:13
          // 两次重启恢复 events=5/11 的构成面之一）。
          val cleanedExec =
            state.execution.copy(
              outstandingSubagentResults = 0,
              pendingEvents = Nil
            )
          for
            _ <- sessionBusyIO
            _ <- receiveVisibility(waiting = false)
            _ <- touchBarrierSnapshot(resources, state.sessionId, 0, 0)
            _ <- persistQueues(state.sessionId, cleanedExec)
            // 诊断修复批 2026-10-24：批次腿同认 delegate 终态交付（任一腿注入即确认）。
            _ <- ackDelegateDelivery(event)
            // 台账对账（平台修复批 fix 1）：held 全量投递 ⇒ 台账清空 + 徽标帧归零。
            _ <- syncHeldReceipts(resources, state.sessionId, Nil).flatMap((h, u) =>
              emitHeldReceiptsBadge(state, h, u)
            )
            result <- pipeLlmCall(
              agentDef,
              resources,
              depth,
              parentRef,
              state
                .copy(execution = cleanedExec)
                .withMessages(state.messages :+ batchMessage)
                .withNextLoopTurn, // Block 3：批次汇聚唤醒 = 新 turn
              None
            )
          yield result
          end for
        else
          // Single immediate result (no batch in flight) or non-subagent event.
          // ═══ 节点完成闸（bgtask-completion-gate 批，作者 2026-09-05 18:29 裁定）═══
          // ExternalEvent 唤醒轮此前 replyTo=None → 续跑轮 AgentEvent.Completed
          // 无人接收——节点观察桥（完成闸单咽喉）在最后一个后台任务完成通知后
          // 永远等不到复检事件，节点悬挂到兜底上限。修正：node- 前缀（Project
          // 节点，kind=Flow）会话以 AgentRecord.supervisorRef（=NodeEngine 观察
          // 桥，注册即粘性完成目标）作为本唤醒轮的 replyTo。
          // ═══ 诊断修复批 2026-10-24（delegate-nebflow-52308ee4 投递丢失）═══
          // 原「仅 node- 启用」排除 delegate 的理由（「Completed 会让 BackoffSupervisor
          // 停 actor」）在等待屏障（sleep-poll 禁令批 A 段，2026-10-20）落地后已
          // 反转：屏障**依赖**唤醒轮的 Completed 复检投递集（等待集空 ⇒ 释放终判），
          // 缺席 = 监督者永远停在 hold 私有态——任务行卡 running、父会话永不收到
          // 结果（实案：barrier 读数 0/0 全清但无任何终态投递，静默 70+ 分钟）。
          // 修正：delegate 会话 × background-task 完成事件同享 supervisorRef
          // replyTo。其余事件源（mail/schedule 等）与非 delegate 会话恒 None——
          // Completed 语义各归既有，零并线。
          val continuationReplyTo: IO[Option[ActorRef[AgentEvent]]] =
            state.sessionId
              .filter(sid =>
                wakeContinuationApplies(
                  isFlowNode = state.isFlowNode,
                  sessionId = Some(sid),
                  eventSource = source,
                  isDelegateSession = nebflow.core.delegate.DelegateSessions.isDelegateSession(Some(sid))
                )
              )
              .fold(IO.pure(Option.empty[ActorRef[AgentEvent]]))(sid =>
                resources.agentRegistry.get.map(_.get(sid).flatMap(_.supervisorRef))
              )
          continuationReplyTo.flatMap { contReplyTo =>
            // Held-receipt 先补注入（fix 1 第三腿）：非子代理事件（background-task /
            // Schedule / Mail 询问等）到达且有被扣留的已产出回执 ⇒ 先随本 turn 补注
            // 回执再注入本事件；屏障随补注归还。空队列 ⇒ flushBase = state，本腿逐字现状。
            val heldFlush = heldFlushPlan(state)
            val flushBase = heldFlush.map(_.state).getOrElse(state)
            val heldMessages = heldFlush.map(_.messages).getOrElse(Nil)
            val flushIO = heldFlush.fold(IO.unit)(p => flushHeldSideEffects(agentDef, resources, depth, p))
            for
              _ <- flushIO
              _ <- sessionBusyIO
              _ <- receiveVisibility(waiting = false)
              _ <-
                if heldFlush.isDefined then IO.unit
                else touchBarrierSnapshot(resources, state.sessionId, newOutstanding, 0)
              // 诊断修复批 2026-10-24：直投腿同认 delegate 终态交付。
              _ <- ackDelegateDelivery(event)
              result <- pipeLlmCall(
                agentDef,
                resources,
                depth,
                parentRef,
                flushBase
                  .withMessages(
                    // Reminder refactor (2026-08-20): always carry a source marker —
                    // external-event turns are system events, not real user input
                    // (fromUser = Message.source.isEmpty).
                    // 媒体统一适配批（2026-10-20）：组装单点 immediateEventMessage——
                    // 带块事件（桥媒体）⇒ Right(Text+blocks)，无块 ⇒ Left 逐字现状。
                    flushBase.messages ++ heldMessages :+ immediateEventMessage(
                      injectionText,
                      event,
                      visSource.orElse(Some("external"))
                    )
                  )
                  // #25: apply the barrier decrement. Pre-fix this branch
                  // computed newOutstanding and dropped it — a single-result
                  // batch (spawn 1, receive 1, held empty) left the counter
                  // stuck at 1 forever. Invisible while Completed fired
                  // unconditionally at turn end; once the completion debt is
                  // parked (#25) a stuck counter means the debt is NEVER paid
                  // and the waiting supervisor/bridge hangs.
                  // flush 腿（回执补注）已把 outstanding 归零（幻影槽位归还），
                  // 不再叠加本回合事件自身的减量（减无可减）。
                  .withOutstandingSubagentResults(if heldFlush.isDefined then 0 else newOutstanding)
                  .withNextLoopTurn, // Block 3：外部事件唤醒 = 新 turn
                contReplyTo
              )
            yield result
            end for
          }
        end if

      case AgentCommand.UpdateGitBranch(branch) =>
        updateGitBranchStay(state, branch)(s => IO.pure(idle(agentDef, resources, depth, parentRef, s)))

      case AgentCommand.CompactionComplete(result) =>
        logAgentEvent(
          agentDef,
          depth,
          state.sessionId,
          state.sessionName,
          "stale-compaction-discarded",
          result.fold(err => s"err=${err.take(60)}", msgs => s"ok=${msgs.size}msgs")
        )
        // Compaction finished (even if stale): messages shrank, the cached
        // systemStable is rebuilt on the next turn.
        val staleState = state.invalidateSystemStableCache
        state.pendingCompaction.flatMap(_.replyDeferred) match
          case Some(d) =>
            ctx.forkTurn(
              d.complete(Left("Compaction result arrived after agent returned to idle"))
                .void
                .handleErrorWith(_ => IO.unit)
            ) *> IO.pure(idle(agentDef, resources, depth, parentRef, staleState))
          case None => IO.pure(idle(agentDef, resources, depth, parentRef, staleState))

      // ── 乒乓压缩（主会话无感上下文压缩一期，2026-10-09）──────────────────
      // §D2-1：S 就绪且主腿 idle ⇒ 立即执行三步原子换入（AgentPingpong.executeSwap；
      // 换入先于下一件输入的 dispatch——本 case 原子完成后才回 idle）。僵尸件
      // （shadowPending 不匹配）在校验处丢弃。
      case cmd: AgentCommand.PingpongSwapReady =>
        AgentPingpong
          .handleSwapReadyIdle(agentDef, resources, depth, state, cmd)
          .map(st => idle(agentDef, resources, depth, parentRef, st))

      // §E-1：影子失败置态（idle 版）——计数 + 清位；主腿本就闲，全程无感。
      case cmd: AgentCommand.PingpongShadowFailed =>
        AgentPingpong
          .handleShadowFailed(agentDef, depth, state, cmd)
          .map(st => idle(agentDef, resources, depth, parentRef, st))

      // §E-7/8/9：重启恢复（spawn 自投；非根会话经 RootAgentIdentity 谓词闸恒 no-op）。
      case AgentCommand.PingpongRecover =>
        AgentPingpong
          .recoverAtIdle(agentDef, resources, depth, state)
          .map(st => idle(agentDef, resources, depth, parentRef, st))

      // 2026-09-13（permshield S1）：`AgentCommand.SetSafetyMode` 已退役（档位 =
      // 应用级持久值，WS/REST 写入口直接落盘 + 热读，无需通知活 agent 改副本）；
      // 由下方 catch-all 兜底为状态不变。三处（idle/processing/frozen）同。

      case _: AgentCommand.LlmComplete | _: AgentCommand.LlmFailed | _: AgentCommand.ToolsComplete |
          _: AgentCommand.SetPermissionDeferred =>
        IO.pure(idle(agentDef, resources, depth, parentRef, state))

      // F2 (2026-08-30): replay persisted injection queues after a crash.
      // Idempotent: merges disk entries ahead of the in-memory queues (which
      // are empty at spawn); the disk file is deleted by the drain paths once
      // the queues are flushed, so a second replay is a no-op.
      case AgentCommand.RecoverPersistedQueues =>
        val sid = state.sessionId.getOrElse("")
        CompactionQueueStore.load(sid).flatMap {
          case Some(q) if q.imms.nonEmpty || q.events.nonEmpty =>
            logAgentEvent(
              agentDef,
              depth,
              state.sessionId,
              state.sessionName,
              "queues-recovered",
              s"imm=${q.imms.size} events=${q.events.size}"
            )
            val exec = state.execution.copy(
              pendingImmediateInputs = q.imms ++ state.execution.pendingImmediateInputs,
              pendingEvents = q.events ++ state.execution.pendingEvents
            )
            // 台账对账 + 徽标帧（fix 1 补漏批，2026-10-22）：恢复的回执必须落账——
            // 此前恢复面不同步台账 ⇒ 无 producedAt ⇒ watchdog 对重启恢复回执致盲
            // （nudge 文本整天数「1 条」而实际队列 5/11 条），只能等下一次输入触
            // 发 fix-1 腿才投递（实证 2026-10-22 10:36/17:13 两次重启恢复）。
            syncHeldReceipts(resources, state.sessionId, exec.pendingEvents).flatMap((h, u) =>
              emitHeldReceiptsBadge(state, h, u)
            ) *>
              IO.pure(idle(agentDef, resources, depth, parentRef, state.copy(execution = exec)))
          case _ => IO.pure(idle(agentDef, resources, depth, parentRef, state))
        }

      // ── HeldDeliveryWatchdog 催注（平台修复批 fix 2 动作面，2026-10-21）────────
      // idle 态收到 = 投递可靠性自检判定「已产出回执 N 分钟未注入」成立 → 强制补投
      // 全部 held 回执（buildEventReminder 合批一 turn，语义与批完成汇聚腿同形），
      // 并归还幻影屏障槽位（outstanding 清零，issue #31）+ 补投注记气泡（父会话
      // 可见的投递事实）+ 徽标帧清零。队列已空（正常 drain 抢先投递）⇒ 幂等 no-op，
      // 仅把 pendingEventCount 快照对齐防漂移。processing/frozen 态另有各自 no-op
      // 分支（turn 边界 drain / 解冻后 drain 兜住）。
      case AgentCommand.DrainHeldReceipts =>
        val held = state.execution.pendingEvents
        if held.isEmpty then
          touchBarrierSnapshot(resources, state.sessionId, state.execution.outstandingSubagentResults, 0) *>
            IO.pure(idle(agentDef, resources, depth, parentRef, state))
        else
          val batchMessage = buildEventReminder(held)
          val cleaned = state.copy(execution =
            state.execution.copy(pendingEvents = Nil, outstandingSubagentResults = 0)
          )
          for
            _ <- IO(
              logAgentEvent(
                agentDef,
                depth,
                state.sessionId,
                state.sessionName,
                "held-receipts-force-drain",
                s"held=${held.size} returned-outstanding=${state.execution.outstandingSubagentResults}"
              )
            )
            _ <-
              if depth == 0 then
                state.sessionId.fold(IO.unit)(sid => emitSessionBusy(state.wsSend, sid, busy = true))
              else IO.unit
            _ <- emitForceDrainVisibility(resources, state, held)
            _ <- touchBarrierSnapshot(resources, state.sessionId, 0, 0)
            _ <- persistQueues(state.sessionId, cleaned.execution)
            _ <- syncHeldReceipts(resources, state.sessionId, Nil).flatMap((h, u) =>
              emitHeldReceiptsBadge(state, h, u)
            )
            result <- pipeLlmCall(
              agentDef,
              resources,
              depth,
              parentRef,
              cleaned
                .withMessages(cleaned.messages :+ batchMessage)
                .withNextLoopTurn, // Block 3：催注补投 = 新 turn
              None
            )
          yield result
          end for
        end if

      // Immediate input arriving in idle (turn already finished) — treat as normal UserInput
      // ② (2026-09-11): `fromUser` is carried across the conversion — dropping it
      // here is exactly what made a real human text land in the
      // `clientMessageId=None ⇒ source="tool"` fallback (diagnosis §1.4 idle row).
      // notifypack 解 b 批（2026-09-23 · r4 rework）：idle 到达是本链的**生产常态**
      // （节点终态通知在上一个 turn 结束之后才到）⇒ 本分支不再是「逐字忽略第 10 槽」，
      // 而是**同一个判据的第二处接线点**：带窗标记的 carrier 在此走 [[expandRootNotify]]
      // 展开为 N 件，于**同一 turn** 内逐件注入（N 气泡 / 1 次唤醒）；无标记件（`None`
      // 与空载荷 `Some(Nil)` —— 与 `expandWindowFlush` 的恒等分支同口径）走下方**逐字
      // 未变**的旧路径（`ctx.self ! UserInput` ⇒ 一次唤醒一件）。
      //
      // 🔴 展开器仍是唯一判据源（本分支不自造谓词）：载体识别 = 元素自带
      // `ImmediateInput.windowItems`，与其余各接线点共用 [[expandRootNotify]]。
      // 🔴 唤醒数：N 件载荷共享**同一次** `pipeLlmCall`（一次 sendStream）——把 N 件各自
      // 转成 `UserInput` 再回投 self 会开 N 个 turn（正是本批要消灭的形态）。
      case imm @ AgentCommand.ImmediateInput(
            text,
            blocks,
            source,
            eventType,
            sender,
            senderTeam,
            delivery,
            fromUser,
            project,
            windowItems
          ) =>
        windowItems match
          case Some(items) if items.nonEmpty =>
            val expanded = expandRootNotify(imm)
            // Held-receipt 先补注入（fix 1 第三腿，与 idle UserInput 腿同判据）：
            // 节点通报（Schedule/调度触发类）同样先带全部 held 回执再展开载荷。
            val heldFlush = heldFlushPlan(state)
            val flushBase = heldFlush.map(_.state).getOrElse(state)
            val heldMessages = heldFlush.map(_.messages).getOrElse(Nil)
            // Per-item context messages (each carries its OWN body — never the merged digest).
            val carrierMessages = expanded.map(item =>
              (item.blocks match
                case Some(b) if b.nonEmpty => Message(MessageRole.User, Right(b))
                case _ => Message(MessageRole.User, Left(item.text))
              ).copy(source = injectionSourceFor(item.fromUser, item.source))
            )
            for
              _ <- IO(
                logAgentEvent(
                  agentDef,
                  depth,
                  state.sessionId,
                  state.sessionName,
                  "window-carrier-expanded-in-idle",
                  s"batch=${expanded.size} heldFlush=${heldMessages.size} texts=${expanded.map(_.text.take(40)).mkString(" | ").take(200)}"
                )
              )
              _ <- heldFlush.fold(IO.unit)(p => flushHeldSideEffects(agentDef, resources, depth, p))
              _ <-
                if depth == 0 then flushBase.sessionId.fold(IO.unit)(sid => emitSessionBusy(flushBase.wsSend, sid, busy = true))
                else IO.unit
              // One injected frame per payload item — each carries its own text / sender /
              // status, so the frontend renders N bubbles with zero frontend change
              // (same single emission point as every other injected leg).
              _ <- expanded.traverse_ { item =>
                injectionSourceFor(item.fromUser, item.source) match
                  case Some(src) =>
                    emitInjectedUserEvent(
                      resources,
                      state.wsSend,
                      state.sessionId,
                      item.text,
                      src,
                      item.eventType,
                      item.sender,
                      item.senderTeam,
                      item.delivery,
                      project = item.project,
                      sessionProject = state.projectName
                    )
                  case None => IO.unit
              }
              // ONE wake for the whole payload (same state preparation as the idle
              // UserInput leg / the turn-boundary immediate-input drain).
              result <- pipeLlmCall(
                agentDef,
                resources,
                depth,
                parentRef,
                flushBase
                  .withMessages(flushBase.messages ++ heldMessages ++ carrierMessages)
                  .withEmptyResponseRetries(0)
                  .withMailUsedThisTurn(false)
                  .withNextLoopTurn,
                None,
                // Same cause as the legacy forward below: an ImmediateInput carries no
                // clientMessageId ⇒ system injection ⇒ Gated (freeze window still applies).
                DispatchCause.Gated
              )
            yield result
            end for
          case _ =>
            for _ <- ctx.self ! AgentCommand.UserInput(
                text,
                None,
                None,
                blocks,
                0,
                source,
                sender,
                senderTeam,
                delivery,
                eventType,
                None,
                fromUser,
                project
              )
            yield idle(agentDef, resources, depth, parentRef, state)

      // Queued mail arriving in idle — drain immediately as a new turn.
      // Idempotent guard (#22): activation sends a head-trigger MailQueued AND
      // the delivering path sends its own for the same item — without the
      // head-check the duplicate injected the same task as TWO turns (double
      // LLM calls, double tool work). Only drain when this item is still the
      // disk head; stale/duplicate triggers are no-ops.
      case AgentCommand.MailQueued(item, _) =>
        val sid = state.sessionId.getOrElse("")
        for
          items <- nebflow.core.flow.MailQueueStore.load(sid)
          headOpt = items.headOption
          // #407 idle gate（08-28 统一裁定）：目标 agent 自身+子树完全空闲才
          // 投递；不空闲则延迟（不消费队列，pendingMailQueueCount=1 保持「有货
          // 未投递」状态），由子 agent 完成事件驱动的 turn 结束重检
          // （finishTurnCont drain 分支的 gate）投递。
          idleOk <- fullyIdle(sid, state, resources)
          result <-
            if !idleOk then
              IO(
                logAgentEvent(
                  agentDef,
                  depth,
                  state.sessionId,
                  state.sessionName,
                  "mail-queued-deferred",
                  s"head=${headOpt.map(_.id).getOrElse("-")} sender=${headOpt.map(_.fromSession).getOrElse("-")} subtree-busy, deferred"
                )
              ) *>
                IO.pure(
                  idle(
                    agentDef,
                    resources,
                    depth,
                    parentRef,
                    // #10 (2026-08-27 mail-queue wedge): accumulate, don't clamp —
                    // multiple mails arriving while idle+subtree-busy each take
                    // this deferral path; `= 1` would lose the earlier count and
                    // the turn-end drain would deliver only the last one.
                    state.copy(execution =
                      state.execution.copy(pendingMailQueueCount = state.execution.pendingMailQueueCount + 1)
                    )
                  )
                )
            else
              headOpt match
                case Some(head) if head.id == item.id =>
                  // Delivery-layer fingerprint dedup (P0): suppresses the same
                  // sender+recipient+content within the 30min window — covers the
                  // restart-replay root cause (MailTool restart recovery re-fires
                  // MailQueued for the disk head). The persisted fingerprint file
                  // survives the restart boundary.
                  // V13 (2026-09-03): the consult is scoped to the replay window
                  // after this session's activation — outside it a same-content
                  // re-send is legitimate and delivers unconditionally.
                  mailDedupInReplayWindow(sid, resources)
                    .flatMap { inReplayWindow =>
                      nebflow.core.flow.MailDeliveryDedup
                        .tryDeliver(
                          sid,
                          nebflow.core.flow.MailDeliveryDedup.fingerprint(item.from, sid, item.message),
                          System.currentTimeMillis(),
                          inReplayWindow
                        )
                        .flatMap {
                          case false =>
                            // Duplicate within window: consume the item (queue + WS)
                            // but inject nothing — sender semantics untouched.
                            nebflow.core.flow.MailQueueStore.removeHead(sid).void *>
                              emitDequeuedWs(state.wsSend, sid, item.id)
                          case true =>
                            for
                              _ <- nebflow.core.flow.MailQueueStore.removeHead(sid).void
                              // G3: re-read attachment paths at drain time (D6 — queue
                              // persists paths, not base64). Lost files degrade to
                              // placeholder text.
                              attBlocks <- nebflow.core.tools.ImageInject.drainImagePaths(item.imagePaths)
                              blocks = nebflow.core.tools.ImageInject.messageBlocks(item.message, attBlocks)
                              _ <- ctx.self ! AgentCommand.UserInput(
                                item.message,
                                None,
                                None,
                                blocks,
                                0,
                                source = Some("mail-queue"),
                                sender = Some(item.from),
                                delivery = Some("queue")
                              )
                              // Emit WS so frontend removes the pending item
                              _ <- emitDequeuedWs(state.wsSend, sid, item.id)
                            yield ()
                        }
                    }
                    .map(_ => idle(agentDef, resources, depth, parentRef, state))
                case _ => IO.pure(idle(agentDef, resources, depth, parentRef, state))
        yield result
        end for

      // Supervisor restart in idle state
      case AgentCommand.RestartAgent(level) =>
        logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "restart-idle", s"level=${level.toString}")
        for
          restartState <- restartStateFor(level, state, resources)
          _ <- state
            .wsSend(
              Json.obj(
                "type" -> "agentRestarted".asJson,
                "sessionId" -> state.sessionId.asJson,
                "level" -> level.toString.asJson
              )
            )
            .handleErrorWith(_ => IO.unit)
          result <- pipeLlmCall(agentDef, resources, depth, parentRef, restartState.withNextLoopTurn, None)
        yield result

      case _ =>
        IO.pure(idle(agentDef, resources, depth, parentRef, state))
  end idle

end AgentIdle
