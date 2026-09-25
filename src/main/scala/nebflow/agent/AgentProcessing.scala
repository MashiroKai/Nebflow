/* 从 AgentActor 迁出(行为保持重构,2026-09-25)。 */
package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.*
import nebflow.agent.AgentCommand.*
import nebflow.core.*
import nebflow.core.compact.*
import nebflow.llm.*
import nebflow.shared.{NebflowLogger, *}

import scala.concurrent.duration.*

/**
 * processing 域:原 object AgentActor 内 processing 行为、mail 检查助手
 * (hasUsedMail / handleMissingMail)、supervisor 重启助手(restartStateFor /
 * rollbackLastToolCall)与两枚 dispatch 管道(pipeLlmCall / pipeToolExecutions)
 * 的实现整体迁入本件(方法体逐字未动)。AgentActor 侧保留同名委托 def(签名与
 * 默认参数原样),全部调用点(含 AgentIdle / AgentFrozen / AgentFinishTurn /
 * AgentCompactionHandlers 与测试)零改动。pipeLlmCall / pipeToolExecutions 原
 * 体内的 super.pipeLlmCall / super.pipeToolExecutions 仅在继承 AgentCore 的
 * 对象内合法,迁入后改为经 AgentActor.corePipeLlmCall / corePipeToolExecutions
 * 薄桥转发(AgentProcessing 不继承 AgentCore,AgentCore 的每实例可变状态仍单
 * 实例)。hasUsedMail / rollbackLastToolCall 为私有单消费助手,随实现迁入、不
 * 留委托。emitInjectedUserEvent / buildEventReminder / persistQueues /
 * logAgentEvent / persistIfSession 等共用助手留驻 AgentActor / AgentCore /
 * AgentSession,经 import AgentActor.* 引用。
 */
private[agent] object AgentProcessing:
  import nebflow.agent.AgentActor.*

  // ============================================================
  // Processing state
  // ============================================================

  private[agent] def processing(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    pending: List[AgentCommand] = Nil
  )(using ctx: ActorContext[AgentCommand]): Behavior[AgentCommand] =
    val base = Behaviors.receiveMessage[AgentCommand]:

      case AgentCommand.UpdateGitBranch(branch) =>
        updateGitBranchStay(state, branch)(s => IO.pure(processing(agentDef, resources, depth, parentRef, s, pending)))

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
            .withLlmFailRetries(0)
            .updateContextWindowIfNeeded(result.contextWindow)
          val isSubagent = depth > 0
          // Emit usageUpdate: use provider-reported tokens if available, otherwise
          // estimate from message history so the UI context bar always updates.
          val effectiveTokens = updatedState.latestUsage
            .map(_.inputTokens)
            .filter(_ > 0)
            .getOrElse(nebflow.core.compact.TokenEstimator.estimate(updatedState.messages))
          val usageEvent: IO[Unit] =
            emitStream(
              state.wsSend,
              AgentStreamEvent.UsageUpdate(
                effectiveTokens,
                updatedState.contextWindow,
                updatedState.effectiveCompactThresholdRatio,
                updatedState.latestUsage.flatMap(u => Option.when(u.outputTokens > 0)(u.outputTokens)),
                // #308: per-round actual model (lastModel was just refreshed from
                // this round's result at withLastModel above) — lets the frontend
                // live-refresh the popup/tile model badge every LLM round.
                model = updatedState.lastModel
              ),
              isSubagent = isSubagent,
              state.sessionId
            )
          // Structured usage telemetry (token dashboard): record provider/model/
          // agent/session + token buckets for every successful LLM call. model
          // arrives as "providerId/modelId" from aggregateChunks.
          val usageRecordIO: IO[Unit] =
            (result.usage, result.model) match
              case (Some(u), Some(modelRef)) =>
                val idx = modelRef.indexOf('/')
                val (provider, model) =
                  if idx > 0 then (modelRef.take(idx), modelRef.drop(idx + 1)) else ("unknown", modelRef)
                resources.usageRecordStore
                  .record(
                    nebflow.core.LlmUsageRecord(
                      timestamp = System.currentTimeMillis(),
                      provider = provider,
                      model = model,
                      agent = agentDef.name,
                      sessionId = state.sessionId,
                      inputTokens = u.inputTokens,
                      outputTokens = u.outputTokens,
                      cacheReadTokens = u.cacheReadTokens.getOrElse(0),
                      cacheWriteTokens = u.cacheWriteTokens.getOrElse(0)
                    )
                  )
                  .handleErrorWith(e =>
                    // 2026-09-10 死日志修复：warn 已返回 IO[Unit]，再包 IO(...) 得 IO[IO[Unit]]（静默）。
                    NebflowLogger
                      .forName("nebflow.agent")
                      .warn(s"usage record failed: ${e.getMessage}")
                  )
              case _ => IO.unit
          usageEvent *> usageRecordIO *> handleLlmCompleteBranch(
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

      case LlmFailed(error, replyTo, turnId, msg) =>
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
          val cleanedState = msg match
            case Some(cnt) => state.withLoopCounters(cnt) // Block 3：L1 终止的计数器写回
            case None => state
          // P4 (flow-node supervision): provider/attempt/stall-age observability
          // on llm-fail — the fields needed to tell "upstream jitter" from
          // "provider dead" in post-mortems without re-reading router logs.
          val failExtras = error match
            case e: StreamInactivityTimeout =>
              s" class=stream-inactivity lastChunkAgeMs=${e.lastChunkAgeMs}"
            case e: FallbackExhaustedError =>
              val last = e.attempts.lastOption
              s" class=exhausted attempts=${e.attempts.size}" +
                last.map(a => s" last=${a.providerId}/${a.model}:${a.reason.getOrElse("unknown")}").getOrElse("")
            case _ => ""
          logAgentEvent(
            agentDef,
            depth,
            state.sessionId,
            state.sessionName,
            "llm-fail",
            s"err=${error.getMessage.take(80)}$failExtras"
          )
          // Auto-retry overload-class LLM failures (429/529) once with a ≥5s
          // backoff: the provider is saturated and may recover. Token incident
          // (2026-08-18): every retry re-dispatches the FULL message history
          // (~250k tokens, 96% cache read), so anything else — connection
          // resets, timeouts, unknown transients — fails fast: the messages
          // haven't changed, retrying mostly burns tokens without healing.
          // The per-turn budget (Fallback.MaxTurnLlmCalls) is the final
          // backstop: even legitimate retries are capped per turn.
          // Flow-node supervision P1 (2026-08-26): stream-inactivity stalls
          // (phase-2 watchdog, upstream jitter) join the retryable set — a
          // clean-checkpoint re-send within the SAME OverloadRetryMax budget,
          // no new quota. Not a stream-level resume: the full turn re-sends,
          // so the seam guard (no provider switch after partial content) is
          // never violated. Single source: AgentActor.llmFailureRetryable.
          val isStreamInactivity = AgentActor.llmFailureInactivityClass(error)
          val retryable = AgentActor.llmFailureRetryable(error)
          // Hard-recovery P4 (设计 §2.5「用户意图优先」): a transport-aborted turn
          // with QUEUED user input must NOT blindly retry — the abort happened
          // precisely BECAUSE a user message could not get in (wedged turn).
          // Failing the turn routes to the turn-boundary drain, which batch-
          // injects the queued inputs (缺陷⑥ 合批) as the next turn. Empty queue
          // (VPN mid-stream flap) keeps the bounded auto-retry (增量#1 验收).
          val recoverableAbortYieldsToQueued = error.isInstanceOf[RecoverableAbort] &&
            state.execution.pendingImmediateInputs.nonEmpty
          if retryable && !recoverableAbortYieldsToQueued && state.llmFailRetries < OverloadRetryMax then
            // 方案 C（2026-08-18 误杀修复）：预算只在重试路径递增——llmCallsThisTurn
            // 与 llmFailRetries 的区别：后者成功即重置（只限连续重试），前者 turn 内
            // 单调累计（限全 turn 重试总量，正常工具循环的成功调用不计入）。
            val retryState = state
              .withLlmFailRetries(state.llmFailRetries + 1)
              .withLlmCallsThisTurn(state.llmCallsThisTurn + 1)
            // Inactivity retries back off ≥30s (upstream stall needs a recovery
            // window — the incident stall was 60s+). Overload retries keep the
            // existing [OverloadBackoffMinMs, LlmFailBackoffMaxMs] pipeline.
            val backoffMs =
              if isStreamInactivity then
                math.max(
                  LlmFailBackoffBaseMs * (1L << (retryState.llmFailRetries - 1)),
                  effectiveInactivityBackoffMinMs
                )
              else
                math.min(
                  math.max(LlmFailBackoffBaseMs * (1L << (retryState.llmFailRetries - 1)), OverloadBackoffMinMs),
                  LlmFailBackoffMaxMs
                )
            val jitter = java.util.concurrent.ThreadLocalRandom.current().nextLong(0, 500)
            val delayMs = backoffMs + jitter
            logAgentEvent(
              agentDef,
              depth,
              state.sessionId,
              state.sessionName,
              "llm-fail-retry",
              s"err=${error.getMessage.take(80)} retry=${retryState.llmFailRetries}/$OverloadRetryMax backoff=${delayMs}ms"
            )
            // The failed LLM call produced no content — re-dispatch with the
            // same messages from the last checkpoint, after a backoff delay.
            // The sleep must be INLINE: ctx.forkTurn is fire-and-forget
            // (ActorContext.forkTurn .start), so wrapping the sleep there made
            // `*>` re-dispatch IMMEDIATELY — the backoff was fake (both the
            // 08-18 overload retry and the P1 inactivity retry re-sent into a
            // still-stalled upstream). Inline sleep is consistent with how
            // pipeLlmCall itself runs (whole turns block the actor loop).
            IO.sleep(delayMs.millis) *>
              pipeLlmCall(agentDef, resources, depth, parentRef, retryState, replyTo)
          else if recoverableAbortYieldsToQueued then
            // ── Hard-recovery P4「用户意图优先」（2026-09-15 ub 缺陷批改形态）──
            // transport abort 的目的就是让排队的用户消息进来：失败回合不带内容
            // （seam guard 弃置部分流），取队首**一件**排队输入开一个**独立** turn
            // （roundComplete 一次 / 蓝气泡一件 / 单次 LLM 往返）；其余留队，由后续
            // turn 边界逐条消费。原「整批合并为一个新 turn」（缺陷⑥）已按 root 裁定
            // 删除——那正是「一报错，队列里的消息一次全发」的 burst 面之一。
            // 不得落入下方 freeze-or-fail——RecoverableAbort 分类为 Fatal
            // （stream 层禁 provider 拼接），fatal 会连队列一起丢且 UI 报错，
            // 「恢复」退化成「失败」（round-5 隔离冒烟实证：kick 后零恢复请求、
            // agent 直接 idle、队列滞留）。
            val (immHeadAfterAbort, remainingImmAfterAbort) = TurnBoundaryDrains.drainHead(
              state.execution.pendingImmediateInputs,
              compactionPending = false
            )
            val immInputs = immHeadAfterAbort.toList
            val immMessages = immInputs.map(imm =>
              (imm.blocks match
                case Some(blocks) if blocks.nonEmpty => Message(MessageRole.User, Right(blocks))
                case _ => Message(MessageRole.User, Left(imm.text))
              ).copy(source = injectionSourceFor(imm.fromUser, imm.source))
            )
            logAgentEvent(
              agentDef,
              depth,
              state.sessionId,
              state.sessionName,
              "immediate-input-injected-after-recoverable-abort",
              s"batch=${immInputs.size} texts=${immInputs.map(_.text.take(40)).mkString(" | ").take(200)} remaining=${remainingImmAfterAbort.size}"
            )
            val updatedState = state
              .copy(execution =
                ExecutionContext
                  .idle(state.execution.messages ++ immMessages, state.execution.turnIdx, state.execution.currentTurnId)
                  .copy(
                    pendingImmediateInputs = remainingImmAfterAbort,
                    pendingMailQueueCount = state.execution.pendingMailQueueCount,
                    pendingUserInputs = state.execution.pendingUserInputs,
                    pendingEvents = state.execution.pendingEvents,
                    outstandingSubagentResults = state.execution.outstandingSubagentResults,
                    owedCompletion = state.execution.owedCompletion
                  )
              )
              .withNextLoopTurn
            for
              // F2: disk snapshot in sync after the drain (queue emptied).
              _ <- persistQueues(state.sessionId, updatedState.execution)
              _ <- state.sessionId.fold(IO.unit)(sid => emitSessionBusy(state.wsSend, sid, busy = true))
              // One blue injected bubble per sourced input — same (new) turn group.
              // ② (2026-09-11): 真人输入不产注入气泡（fromUser 优先于 source）。
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
                    imm.senderTeam,
                    project = imm.project,
                    sessionProject = state.projectName
                  )
                )
              }.sequence_
              _ <- state.sessionId.fold(IO.unit)(sid =>
                ctx.forkTurn(
                  (resources.sessionStore.saveMessagesForSession(sid, updatedState.execution.messages) *>
                    resources.sessionStore.flushIndex)
                    .handleErrorWith(e =>
                      NebflowLogger.forName("nebflow.agent").warn(s"Save/flush session failed: ${e.getMessage}")
                    )
                )
              )
              _ <- state.sessionId.fold(IO.unit)(sid =>
                ctx.forkTurn(
                  state
                    .wsSend(Json.obj("type" -> "roundComplete".asJson, "sessionId" -> sid.asJson))
                    .handleErrorWith(e =>
                      NebflowLogger.forName("nebflow.agent").warn(s"roundComplete delivery failed: ${e.getMessage}")
                    )
                )
              )
              b <- pipeLlmCall(agentDef, resources, depth, parentRef, updatedState, replyTo)
            yield b
            end for
          else
            // ── v2 冻结式错误恢复（§3.3/§6.1 步骤 3）────────────────────────
            // transient 类错误不再 fail-fast（原「turn 死亡」语义）→ 进入
            // ErrorFrozen：退避到期自动续跑（重新过 gate，条件未消除再入冻结，
            // 天然闭环）；同 reason 连续 ≥3 次 → 升级父干预（不直接 fatal）。
            // 保留 Permanent/Fatal（Timeout/EmptyStream/Auth/Format/ModelNotFound/
            // TurnBudgetExceeded/StuckAbort/context overflow）→ 下方 fatal 路径。
            val errCls = Fallback.classifyError(error)
            // Flow-node supervision P2: flow nodes are exempt from ErrorFrozen —
            // a DAG is deterministic execution, there is no "wait for the
            // condition to clear" semantics. A retryable-exhausted stall fails
            // the turn (AgentError.retryable=true) so the flow executor's
            // Restart path checkpoint-resumes the node. Root/team agents keep
            // the v2 freeze-then-resume semantics for the same error class.
            val shouldErrorFreeze = !state.isFlowNode && (error match
              // retryable 预算耗尽（attempts 全 overload/inactivity）：饱和或上游
              // 抖动可能恢复 → 冻结等待（与 llmFailureRetryable 同判定，单点来源）
              case _: FallbackExhaustedError => AgentActor.llmFailureRetryable(error)
              // 工具管道错误：工具链路问题，不是 provider 条件性错误 → 不冻结（原语义）
              case _: ToolPipelineError => false
              // Block 3 循环检测器 L1：turn 级终止，非条件性错误 → 不冻结
              // （L2 冻结走 LoopFreezeDetected 独立路径，不经 LlmFailed）
              case _: LoopDetectedError => false
              case _ => errCls.permanence == ErrorPermanence.Transient)
            if shouldErrorFreeze then
              val errReason = error match
                case _: AllProvidersDownTimeout => FreezeReason.ProviderDown
                case _ =>
                  errCls.reason match
                    case FailoverReason.ConnectionReset => FreezeReason.Network
                    case _ => FreezeReason.LlmTransient
              val jitter = java.util.concurrent.ThreadLocalRandom.current().nextLong(0, 500)
              val resumeInMs = errReason match
                case FreezeReason.Network =>
                  NetworkErrorFreezeBackoffMs + java.util.concurrent.ThreadLocalRandom.current().nextLong(0, 20_000L)
                case FreezeReason.ProviderDown => ProviderDownFreezeBackoffMs + jitter
                case _ =>
                  // LlmTransient：沿用 overload 退避（预算耗尽时 llmFailRetries ≥ Max → ≥60s）
                  math.min(
                    math
                      .max(LlmFailBackoffBaseMs * (1L << math.max(state.llmFailRetries, 1) - 1), OverloadBackoffMinMs),
                    LlmFailBackoffMaxMs
                  ) + jitter
              enterErrorFrozen(agentDef, resources, depth, parentRef, state, replyTo, errReason, resumeInMs, error)
            else
              val agentError =
                AgentError(
                  ctx.self.path.name,
                  agentDef.name,
                  depth,
                  AgentErrorType.LlmFailed,
                  error.getMessage,
                  retryable = Some(AgentActor.llmFailureRetryable(error))
                )
              val errMsg = error match
                case e: FallbackExhaustedError =>
                  val attemptSummaries =
                    e.attempts.map(a => s"${a.providerId}/${a.model}: ${a.reason.map(_.toString).getOrElse("unknown")}")
                  NebflowError.toUserMessage(NebflowError.LlmFailed(e.getMessage, attemptSummaries))
                case e: ToolPipelineError =>
                  e.message
                case e: LoopDetectedError =>
                  e.getMessage
                case _ =>
                  NebflowError.toUserMessage(
                    NebflowError.Internal(Option(error.getMessage).getOrElse("internalError"))
                  )
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
                _ <- cleanedState.sessionId.fold(IO.unit) { sid =>
                  // Persist the conversation before failing so the parent can
                  // resume from saved history (re-dispatch / fork on this session).
                  ctx.forkTurn(
                    (resources.sessionStore.saveMessagesForSession(sid, cleanedState.messages) *>
                      resources.sessionStore.flushIndex)
                      .handleErrorWith(e =>
                        NebflowLogger.forName("nebflow.agent").warn(s"Save failed session: ${e.getMessage}")
                      )
                  )
                }
                // #25: a fatal failure must discharge the completion debt with
                // Failed — a parked requester (supervisor/bridge waiting for the
                // post-batch answer) would otherwise hang forever, and the clone
                // would sit registered as running. Targets = this turn's replyTo
                // plus any parked debt (deduped).
                failTargets = (replyTo.toList ++ state.execution.owedCompletion).distinct
                _ <- failTargets.traverse_(_ ! AgentEvent.Failed(cleanedState.sessionId.getOrElse(""), agentError))
                // Mail team members have no replyTo (their turns are driven by
                // UserInput(replyTo=None)), so the notification above is a no-op
                // for them and a fatal LLM failure evaporated silently — the
                // Manager kept waiting for a [RESULT] that would never come
                // (2026-08-15: 53-minute mutual-wait deadlock). Route the same
                // "failed" external event to the parent (team lead) instead,
                // mirroring BackoffSupervisor's notifyParentAndStop metadata
                // contract (failedSessionId / retryable / failureType) so the
                // parent's re-delegate system-reminder kicks in.
                // #25: only when NO requester at all (no replyTo AND no parked
                // debt) — a debt discharge above already reaches the parent via
                // the supervisor's source="delegate/subtask" failed event (which
                // also releases the parent's barrier; source="team" would not).
                _ <- (failTargets, parentRef) match
                  case (Nil, Some(parent)) =>
                    val sid = cleanedState.sessionId.getOrElse("")
                    val sessionInfo = if sid.nonEmpty then s" [session=$sid]" else ""
                    // ActorRef.! returns IO[Unit] — return it directly.
                    parent ! AgentCommand.ExternalEvent(
                      source = "team",
                      eventType = "failed",
                      payload = s""""${agentDef.name}" (team member) hit a fatal LLM failure: """ +
                        s"${Option(error.getMessage).getOrElse("unknown").take(200)}$sessionInfo",
                      metadata = JsonObject(
                        "failedSessionId" -> sid.asJson,
                        "retryable" -> true.asJson,
                        "failureType" -> agentError.errorType.toString.asJson,
                        "agentName" -> agentDef.name.asJson
                      ),
                      correlationId = Some(sid).filter(_.nonEmpty)
                    )
                  case _ => IO.unit
                // A pending CompactionJob whose LLM call died fatally must not
                // survive into idle (zombie): the next normal reply would be
                // misrouted as the compact summary and replace all messages.
                // Complete the deferred waiter and record the failure — mirrors
                // finishTurn's zombie guard and the CompactionComplete(Left) path.
                _ <- state.pendingCompaction
                  .flatMap(_.replyDeferred)
                  .traverse_(d =>
                    d.complete(Left("Compaction abandoned: LLM failed during compaction"))
                      .void
                      .handleErrorWith(_ => IO.unit)
                  )
                // F1 (2026-08-29, loop-detected report §4.1/§6): the fatal path
                // never wrote the registry back — the last entry stayed
                // status=Processing (written by the crashed round's loop-counter
                // touch), so TaskStuckWatcher flagged a zombie every 30s until a
                // human restarted the actor. Every other terminal path
                // (turn-done AgentFinishTurn.finishTurnCont / Interrupt
                // processing·frozen 的 Interrupt 分支 / Stop frozen 的 Stop 分支 /
                // ResetSession AgentActor.resetSessionHandler / ErrorFrozen
                // AgentFrozen.enterFrozen) writes the registry; this was the
                // last one missing. Idle = "no active turn" (the behavior state
                // keeps the Error detail; the watcher only flags Processing).
                // （2026-09-25 行号引用修正：原单文件行号已随行为域拆分漂移,
                //   改指文件+成员名。）
                _ <- touchRegistryActivity(resources, cleanedState.sessionId, AgentStatus.Idle)
              yield
                val compactionWasPending = state.pendingCompaction.isDefined
                val fatalState = cleanedState
                  .withStatus(AgentStatus.Error(error.getMessage))
                  .withPendingCompaction(None)
                  // #25: the debt was discharged with Failed above — never
                  // carry it into the idle state.
                  .withOwedCompletion(Nil)
                val finalState =
                  if compactionWasPending then
                    logAgentEvent(
                      agentDef,
                      depth,
                      state.sessionId,
                      state.sessionName,
                      "compaction-abandoned",
                      s"reason=llm-fatal job=${state.pendingCompaction.map(_.subagentId).getOrElse("")}"
                    )
                    fatalState
                      .withCompactionFailures(state.compactionFailures + 1)
                      .withLastCompactionFailureAt(System.currentTimeMillis())
                  else fatalState
                idle(agentDef, resources, depth, parentRef, finalState)
              end for
            end if
          end if
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
        // Collect image blocks from tool results and inject as a separate user message.
        // For Anthropic: mergeConsecutive merges this with resultMsg → single user message
        //   with tool_result + image blocks (valid Anthropic format).
        // For OpenAI: resultMsg → {role: "tool"} messages, imageMsg → {role: "user"} message
        //   with image_url content (correct OpenAI format for tool results + images).
        val imageBlocks = tc.results.flatMap { (call, r) =>
          r.imageBlocks.getOrElse(List.empty[ContentBlock.Image])
        }
        val imageMsgs =
          if imageBlocks.nonEmpty then List(Message(MessageRole.User, Right(imageBlocks)))
          else Nil
        val baseMessages = tc.compactedMessages.getOrElse(state.messages)
        // Drain queued external events alongside tool results (serial processing,
        // same as pendingImmediateInputs). While a parallel sub-agent batch is
        // still outstanding, subtask/delegate results are HELD (barrier, worker
        // blocking semantics) — they are injected ALL together when the batch
        // completes; other event types keep the existing one-at-a-time drain.
        // Guarded against a pending compaction job: the save/compact turn's
        // history is replaced by the summary, so an event drained here would be
        // consumed from the queue yet discarded with the pre-compaction
        // messages. CompactionComplete re-drains afterwards.
        val (drainedEvents, remainingEvents) =
          TurnBoundaryDrains.drainBarrier(
            state.execution.pendingEvents,
            state.pendingCompaction.isDefined,
            state.execution.outstandingSubagentResults
          )
        val eventMessages = drainedEvents match
          case Nil => Nil
          case events =>
            logAgentEvent(
              agentDef,
              depth,
              state.sessionId,
              state.sessionName,
              "pending-events-injected-at-tools-complete",
              s"events=${events.size} remaining=${remainingEvents.size}"
            )
            List(buildEventReminder(events))
        // 排队消息逐条注入（2026-09-15 ub 缺陷批）：本边界只消费队首**一件**
        // immediate input（原缺陷⑥ 合批 = 整队塞进同一次续轮，已按 root 裁定删除）。
        // While compaction is in progress, keep inputs queued — injecting mid-compaction
        // risks the input being lost in the summary. CompactionComplete drains them.
        val (immHeadInput, remainingImmInputs) =
          TurnBoundaryDrains.drainHead(state.execution.pendingImmediateInputs, state.pendingCompaction.isDefined)
        val immInputs = immHeadInput.toList
        val immediateMessages = immInputs match
          case Nil => Nil
          case inputs =>
            logAgentEvent(
              agentDef,
              depth,
              state.sessionId,
              state.sessionName,
              "immediate-input-injected-at-tools-complete",
              s"batch=${inputs.size} texts=${inputs.map(_.text.take(40)).mkString(" | ").take(200)} remaining=${remainingImmInputs.size}"
            )
            inputs.map(imm =>
              (imm.blocks match
                case Some(blocks) if blocks.nonEmpty => Message(MessageRole.User, Right(blocks))
                case _ => Message(MessageRole.User, Left(imm.text))
              ).copy(source = injectionSourceFor(imm.fromUser, imm.source))
            )
        // ② (2026-09-11): 真人输入不产注入气泡（fromUser 优先于 source）。
        val immEventIO = immInputs.flatMap { imm =>
          injectionSourceFor(imm.fromUser, imm.source).map(src =>
            emitInjectedUserEvent(
              resources,
              state.wsSend,
              state.sessionId,
              imm.text,
              src,
              imm.eventType,
              imm.sender,
              imm.senderTeam,
              imm.delivery,
              project = imm.project,
              sessionProject = state.projectName
            )
          )
        }.sequence_
        // Q1-A2′ 的「本边界整队合批注入 queued UserInput」已于 2026-09-15 ub 缺陷批
        // 退役（root 裁定：排队消息按序逐条注入、每条独立成 turn、禁合并语义）：
        // `pendingUserInputs` 在本边界**一律不消费**，全部留在全元数据队列，由 turn
        // 末完成路径逐条重投给 idle 处理器（head-forward，见 finishTurnCont）——那
        // 条路径本就是逐条 + 独立 turn + 带全元数据（replyTo 完成结算不搁浅），
        // 也正是本缺陷的「正常路径同规」形态。
        // Block 3 循环检测器 L0（supervision trio §D3）：LoopGuard Warn 提醒以
        // user system-reminder 追加在工具结果之后（同系统 reminder 形态）
        // ——零成本给模型自纠机会；不阻断轮次。
        val loopReminderMsgs = tc.loopReminder match
          case Some(rem) => List(Message(MessageRole.User, Left(rem)))
          case None => Nil
        val newMessages =
          baseMessages ++ List(
            assistantMsg,
            resultMsg
          ) ++ imageMsgs ++ eventMessages ++ immediateMessages ++ loopReminderMsgs
        // Increment delegate count for Delegate/SubTask calls
        val delegateIncrement = toolCalls.count(c => c.name == "Delegate" || c.name == "SubTask")
        val newDelegateCount = state.delegateCount + delegateIncrement
        // Sub-agent barrier: each SUCCESSFULLY spawned ephemeral Delegate or
        // any SubTask in this turn adds one outstanding result slot. Failed
        // spawns (depth limit, invalid agent, missing ActorSystem) return a
        // ToolError and never produce an ExternalEvent — counting them would
        // stall the barrier forever, blocking every later result delivery.
        //
        // Persistent Delegates are EXCLUDED (qa-backend 2026-08-19): their
        // completion event carries source=address (the actor path, via
        // persistentAdapter in DelegateTool:618-626), NOT "delegate" — so
        // isSubagentResult (:39-40) never matches it, and the counter would
        // never decrement. Counting them creates a phantom slot that permanently
        // holds every later ephemeral batch's results.
        val spawnedIncrement = TurnBoundaryDrains.countBarrierIncrements(tc.results)
        val newOutstanding = state.execution.outstandingSubagentResults + spawnedIncrement
        val updatedState0 =
          state.copy(execution =
            state.execution
              .copy(
                messages = newMessages,
                // D3: keep a pending permission across the turn boundary — the
                // root holds the sub-agent's Deferred while waiting for the user
                // answer; clearing it here would strand the sub-agent until the
                // 5-minute timeout. Only pendingPermission survives (bounded by
                // PermissionTimeout), pendingAskUserReplyTo still resets.
                interaction = state.execution.interaction.filter(_.pendingPermission.isDefined),
                pendingEvents = remainingEvents,
                pendingImmediateInputs = remainingImmInputs,
                // ub 缺陷批：本边界不再消费排队 UserInput（原 Q1-A2′ 内联合批已退役），
                // 队列原样带过 turn 边界，由 turn 末 head-forward 逐条处理。
                pendingUserInputs = state.execution.pendingUserInputs,
                delegateCount = newDelegateCount,
                outstandingSubagentResults = newOutstanding,
                mailUsedThisTurn = state.mailUsedThisTurn ||
                  tc.results.exists((call, r) => call.name == "Mail" && !r.isError)
              )
          )
        // Block 3：LoopGuard 计数器快照写回（pipeToolExecutions 异步 IO 内计算、
        // 经消息携带——S1/S2 跨轮 + S3 跨 turn 持久化的唯一载体）。
        val updatedState = tc.loopCounters match
          case Some(cnt) => updatedState0.withLoopCounters(cnt)
          case None => updatedState0
        tc.freezeAfter match
          case Some(detail) =>
            // Block 3 L2（supervision trio §D3）：消息组装/持久化/计数器写回后
            // 不续轮——WS loopDetected 广播 + 父 ExternalEvent("loop-detected") +
            // enterFrozen(Loop) 待人工（用户唤醒 / Manager·Nebula AgentControl
            // restart / cancel 终态；resumeAt=1 年，语义「永不自动续跑」）。
            for
              _ <- persistIfSession(resources, updatedState)
                .handleErrorWith(e =>
                  NebflowLogger.forName("nebflow.agent").warn(s"Persist session failed: ${e.getMessage}")
                )
              // F2@freeze：冻结分支同样在 drain 后落队列快照（崩溃重放禁重复注入已消费队首）
              _ <- persistQueues(state.sessionId, updatedState.execution)
              _ <- IO {
                logAgentEvent(
                  agentDef,
                  depth,
                  state.sessionId,
                  state.sessionName,
                  "loop-freeze",
                  s"detail=${detail.take(120)}"
                )
              }
              _ <- state.sessionId.fold(IO.unit) { sid =>
                state
                  .wsSend(
                    Json.obj(
                      "type" -> "loopDetected".asJson,
                      "sessionId" -> sid.asJson,
                      "detail" -> detail.take(300).asJson
                    )
                  )
                  .handleErrorWith(_ => IO.unit)
              }
              _ <- parentRef.fold(IO.unit) { parent =>
                val sid = state.sessionId.getOrElse("")
                parent ! AgentCommand.ExternalEvent(
                  source = "team",
                  eventType = "loop-detected",
                  payload = s""""${agentDef.name}" loop detected and frozen: ${detail.take(200)}""" +
                    (if sid.nonEmpty then s" [session=$sid]" else ""),
                  metadata = JsonObject(
                    "failedSessionId" -> sid.asJson,
                    "retryable" -> false.asJson,
                    "failureType" -> "loop".asJson,
                    "agentName" -> agentDef.name.asJson
                  ),
                  correlationId = Some(sid).filter(_.nonEmpty)
                )
              }
              result <- enterErrorFrozen(
                agentDef,
                resources,
                depth,
                parentRef,
                updatedState,
                tc.replyTo,
                FreezeReason.Loop,
                LoopFreezeResumeMs,
                LoopDetectedError(detail)
              )
            yield result
          case None =>
            for
              _ <- ctx.forkTurn(
                persistIfSession(resources, updatedState)
                  .handleErrorWith(e =>
                    NebflowLogger.forName("nebflow.agent").warn(s"Persist session failed: ${e.getMessage}")
                  )
              )
              _ <- persistQueues(
                state.sessionId,
                updatedState.execution
              ) // F2@tools-complete：drain 后快照队列（崩溃重放禁重复注入已消费队首）
              _ <- immEventIO
              _ <- touchBarrierSnapshot(resources, state.sessionId, newOutstanding, remainingEvents.size)
              result <- pipeLlmCall(agentDef, resources, depth, parentRef, updatedState, tc.replyTo)
            yield result
        end match

      // --- Interrupt ---
      case AgentCommand.Interrupt() =>
        logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "interrupt", "reason=user")
        for
          _ <- ctx.cancelCurrentTurn()

          _ <- emitStream(state.wsSend, AgentStreamEvent.Interrupted, isSubagent = depth > 0, state.sessionId)

          // compactui 批（2026-09-15 事故，作者 16:45–16:48 实证）：压缩作业被中断
          // 必须补发**终局帧**——否则客户端 pill 永挂、落盘孤儿 chat.compacting
          // 每次历史重放复活。详见 emitAbandonedCompaction 文档。
          _ <- emitAbandonedCompaction(state, depth)

          _ <- state.pendingCompaction
            .flatMap(_.replyDeferred)
            .traverse_(d => d.complete(Left("Interrupted by user")).void.handleErrorWith(_ => IO.unit))
          // Back to idle without finishing the turn — clear the busy mark so a
          // interrupted team agent isn't stuck "running" in the Teams panel.
          _ <- markTeamIdle(agentDef, state.sessionId)
          // R2 closure (wait-timeout-fix): user cancel is the guaranteed exit
          // from WaitingForUser (AskUser/permission parks the turn fiber on a
          // deferred — no finishTurnCont runs). Without this touch the registry
          // would keep the waiting status forever. Idle — NOT Processing — so
          // the watcher sees a consistent idle row (also fixes the pre-existing
          // stale-Processing-after-interrupt gap).
          _ <- touchRegistryActivity(resources, state.sessionId, AgentStatus.Idle)
          // #250 第②项（2026-09-13 作者裁定「6 项全补」）：turn 被中断 ⇒ 回收本会话
          // 仍挂在 InteractionHub 的 pending 槽（中断前那一问已无人等待）。
          _ <- closePendingInteractionsForInterruptedTurn(resources, state.sessionId.getOrElse(""))
        yield
          // Hard-recovery P3: cancelCurrentTurn is now fire-and-forget — the
          // abandoned turn fiber may still complete and send a late
          // LlmComplete/LlmFailed. Bump currentTurnId so the stale-turnId
          // guard discards them (turnId is monotonic; the next dispatch takes
          // +1 from here with no collision — AgentCore.pipeLlmCall 的
          // `currentTurnId + 1` 取号处；2026-09-25 行号引用修正)。
          // compactui 批（2026-09-15 事故 ②）：dropCompactionScratch 必须在
          // withPendingCompaction(None) **之前**应用（判据依赖作业仍在）。
          val interruptedState = dropCompactionScratch(state).resetForInterrupt
            .withCurrentTurnId(state.execution.currentTurnId + 1)
            .withPendingCompaction(None)
          idle(agentDef, resources, depth, parentRef, interruptedState)
        end for

      // --- Retry: cancel current work, re-dispatch from last checkpoint ---
      // F (2026-09-25 命令消重): 与 frozen 态逐字同形,收敛至
      // AgentActor.retryFromCheckpoint（实现体原样迁入,注释随行）。
      case AgentCommand.Retry(reason) =>
        retryFromCheckpoint(agentDef, resources, depth, parentRef, state, reason)

      // --- Supervisor restart ---
      case AgentCommand.RestartAgent(level) =>
        logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "restart", s"level=${level.toString}")
        for
          _ <- ctx.cancelCurrentTurn()
          _ <- state.pendingCompaction
            .flatMap(_.replyDeferred)
            .traverse_(d => d.complete(Left("Restarted by supervisor")).void.handleErrorWith(_ => IO.unit))
          _ <- emitStream(state.wsSend, AgentStreamEvent.Interrupted, isSubagent = depth > 0, state.sessionId)
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
        end for

      // --- Stop ---
      case AgentCommand.Stop(_) =>
        logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "stop", "reason=user")
        for
          _ <- ctx.cancelCurrentTurn()

          _ <- killSessionShellProcesses(state)
          _ <- fireLifecycleStopHooks(resources, state)
        yield Behaviors.stopped

      case AgentCommand.ClearReadTracker =>
        clearReadTrackerStay(state)(IO.pure(processing(agentDef, resources, depth, parentRef, state, pending)))

      // F (2026-09-25 命令消重): 与 frozen 态逐字同形,收敛至
      // AgentActor.resetSessionHandler（实现体原样迁入,R2 注释随行）。
      case AgentCommand.ResetSession =>
        resetSessionHandler(agentDef, resources, depth, parentRef, state)

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
                    // 尾部保真 (2026-09-07): truthful count read back from the
                    // summary label written by FullCompact.parseResponseDetailed.
                    extra = Map("preservedRounds" -> FullCompact.preservedRoundsOf(compactedMessages).toString)
                  )
                case None => IO.pure(Left("no sessionId"))
              val compactEmitIO = archiveIO
                .flatMap {
                  // 2026-09-15 作者令：压缩不再落 report ⇒ 不再有 reportPath 可播报，
                  // 与失败分支一致播报 None（该字段承载的「report: xxx.md」详情已随
                  // 生成链删除，见 HistoryArchiver 头注）。
                  case Right(_) =>
                    emitStreamIO(
                      state.wsSend,
                      AgentStreamEvent
                        .CompactComplete(state.messages.size, compactedMessages.size),
                      isSubagent = depth > 0,
                      state.sessionId
                    )
                  case Left(err) =>
                    NebflowLogger.forName("nebflow.agent").warn(s"Compaction archive failed: $err")
                    emitStreamIO(
                      state.wsSend,
                      AgentStreamEvent.CompactComplete(state.messages.size, compactedMessages.size),
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
                // Cache v2: compaction shrinks history; the cached systemStable
                // is rebuilt on the next turn (lifecycle node).
                .invalidateSystemStableCache
              // F1 (2026-08-30, compact-injection-shield G1): BOTH branches now
              // drain the held-back queues through ONE shared helper. Previously
              // only the no-resume path drained (and only one injection per
              // round); the resume=true auto-compaction continuation injected
              // NOTHING, deferring queued Mail/delegate results to the next turn
              // boundary — G1, the loss-window this batch closes.
              val drain = drainQueuesAfterCompaction(compactedState)
              // F2: persist the post-drain queue state (drained entries removed;
              // deferred/barrier-held entries survive) before continuing.
              val persistDrainIO = persistQueues(state.sessionId, drain.exec)
              if compactionPending.exists(_.resumeAfterCompact) then
                val stateWithInstruction = compactionPending.flatMap(_.postCompactInstruction) match
                  case Some(instruction) =>
                    compactedState.withMessages(compactedState.messages :+ Message(MessageRole.User, Left(instruction)))
                  case None => compactedState
                val drainedState = stateWithInstruction
                  .copy(execution =
                    drain.exec.copy(
                      messages = stateWithInstruction.messages ++ drain.appended
                    )
                  )
                  .withNextLoopTurn // Block 3：压缩后注入 = 新 turn
                for
                  _ <- ctx.forkTurn(compactEmitIO)
                  _ <- persistDrainIO
                  _ <- logPostCompactInjection(agentDef, depth, state, drain)
                  _ <- emitInjectedBubbles(resources, state, drain)
                  result <- pipeLlmCall(
                    agentDef,
                    resources,
                    depth,
                    parentRef,
                    drainedState,
                    compactionPending.flatMap(_.replyTo)
                  )
                yield result
              else
                for
                  _ <- ctx.forkTurn(compactEmitIO)
                  _ <- ctx.forkTurn(
                    persistIfSession(resources, compactedState)
                      .handleErrorWith(e =>
                        // 2026-09-10 死日志修复：去掉外层 IO(...)（内层 IO 永不执行）。
                        NebflowLogger.forName("nebflow.agent").warn(s"Persist after compact failed: ${e.getMessage}")
                      )
                  )
                  _ <- persistDrainIO
                  result <-
                    if drain.appended.nonEmpty then
                      for
                        _ <- logPostCompactInjection(agentDef, depth, state, drain)
                        _ <- emitInjectedBubbles(resources, state, drain)
                        res <- pipeLlmCall(
                          agentDef,
                          resources,
                          depth,
                          parentRef,
                          compactedState
                            .copy(execution = drain.exec.copy(messages = compactedState.messages ++ drain.appended))
                            .withNextLoopTurn, // Block 3：压缩后注入 = 新 turn
                          None
                        )
                      yield res
                    else
                      // Nothing injected inline — but deferred commands (replyTo-bearing
                      // UserInputs and non-UserInput commands such as SkillActivate /
                      // AskQuestion, held for full-metadata forwarding) may
                      // still be parked in pendingUserInputs. Forward the head to self
                      // so the idle handler processes it with full metadata — the
                      // replyTo completion target must NOT be stranded — preserving the
                      // tail for the next turn boundary drain (mirrors finishTurnCont).
                      drain.exec.pendingUserInputs.headOption match
                        case Some(userCmd) =>
                          (ctx.self ! userCmd) *>
                            IO.pure(
                              idle(
                                agentDef,
                                resources,
                                depth,
                                parentRef,
                                compactedState.copy(execution =
                                  drain.exec.copy(pendingUserInputs = drain.exec.pendingUserInputs.tail)
                                )
                              )
                            )
                        case None =>
                          // Truly nothing queued during the window — the continuation
                          // state (messages compacted, pendingCompaction cleared) is final.
                          IO.pure(idle(agentDef, resources, depth, parentRef, compactedState))
                yield result
                end for
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
        forwardBackgroundTaskNotification(n)(IO.pure(processing(agentDef, resources, depth, parentRef, state, pending)))

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
        // Sub-agent barrier: a Delegate/SubTask result (completed or failed)
        // satisfies one outstanding slot of the parallel batch. The drain at the
        // turn boundary only releases subtask/delegate events once the counter
        // hits 0, so a batch completing mid-turn is still injected together.
        val updatedOutstanding =
          if TurnBoundaryDrains.isSubagentResult(event) then math.max(0, state.execution.outstandingSubagentResults - 1)
          else state.execution.outstandingSubagentResults
        val updatedExec = state.execution.copy(
          pendingEvents = state.execution.pendingEvents :+ event,
          outstandingSubagentResults = updatedOutstanding
        )
        // 任务 Q: emit the visible bubble at receive time (the combined
        // <system-reminder> injected later at finishTurn is for the LLM).
        val visSource = visibleExternalEventSource(source, eventType)
        val bubbleIO = visSource match
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
              sessionProject = state.projectName
            )
          case None => IO.unit
        bubbleIO *>
          emitStream(
            state.wsSend,
            AgentStreamEvent.ExternalEventReceived(source, eventType, correlationId),
            isSubagent = depth > 0,
            state.sessionId
          ) *> persistQueues(state.sessionId, updatedExec) *> IO.pure(
            processing(agentDef, resources, depth, parentRef, state.copy(execution = updatedExec), pending)
          )

      // --- AskUser from tool ---
      case AgentCommand.AskUser(requestId, items, replyToOpt, askMode) =>
        // P2: every agent (root or sub-agent) sends the question straight to
        // the InteractionHub — no ForwardAskUser relay chain. The hub holds
        // replyTo, renders the question in the Nebula window (sessionId =
        // rootSessionId + source attribution) and routes the answers back.
        val srcAgent = agentDef.name
        val srcSession = state.sessionId.getOrElse("")
        val rootSid =
          Option(state.session.rootSessionId).filter(_.nonEmpty).getOrElse(state.sessionId.getOrElse(""))
        // D6 批 F1（G9 来源标注）：项目上下文随 payload 携带——项目节点=flowNodeName
        // （spawn 置位路径 a），分发器提问标注 "dispatcher"；非项目会话（Nebula
        // 自问/REPL）无此两字段，前端 badge 回落 agentName（不回归）。
        val askProject = state.projectName
        val askNodeName =
          if state.isDispatcher then Some("dispatcher") else state.flowNodeName
        val payload0 = buildAskUserJson(
          Some(rootSid),
          srcAgent,
          items,
          Some(srcAgent),
          Some(srcSession),
          askProject,
          askNodeName
        )
        // U3（2026-09-11 作者裁定）：内核会话的来源标注覆盖为 `subagent · <任务摘要>`
        // （纯后端注入——落点仍是来源标注的回落分支：payload.agentName 即前端 badge
        // 回落来源；`project`/`nodeName` 对内核恒空）。非内核会话 payload 逐字节不变
        // （AskUserBuildJsonSpec V11b 的字节一致性防线不受影响）。
        val payload =
          if isKernelSession(srcSession) then
            payload0.deepMerge(Json.obj("agentName" -> subagentAskLabel(state.sessionName, srcSession).asJson))
          else payload0
        // D6 批 F1（方案 A 监督补齐件，spec §3.2/§3.3）：节点提问留痕 node-ask
        // 事件——分发器监督从实时把关变审计可见（FlowMapEventLog 既有基座，
        // NodeEngine start-aborted 等写入点先例）。仅项目节点会话留痕（分发器
        // 提问本就在监督链内）；best-effort——留痕失败绝不阻断提问链。
        val nodeAskEventIO = (state.flowNodeId, askProject) match
          case (Some(nodeId), Some(proj)) =>
            val q0 = items.headOption.map(_.question).getOrElse("")
            val short = if q0.length > 40 then q0.take(37) + "..." else q0
            val multi = if items.length > 1 then s" (+${items.length - 1} more)" else ""
            val summary =
              s"node=${askNodeName.getOrElse(nodeId)} requestId=$requestId ask: $short$multi"
            state.sandboxRoot.orElse(state.projectRoot) match
              case Some(workspace) =>
                nebflow.core.project.FlowMapEventLog
                  .append(workspace, proj, nodeId, "node-ask", summary)
                  .handleErrorWith(e => logger.warn(s"node-ask event append failed (node=$nodeId): ${e.getMessage}"))
              case None =>
                logger.warn(s"node-ask event skipped: no workspace path (node=$nodeId)")
          case _ => IO.unit
        val sendIO = resources.interactionHubRef.get.flatMap {
          case Some(hub) =>
            // R2 (wait-timeout-fix, 2026-09-03): the turn parks on a
            // human-in-the-loop wait — mark WaitingForUser so TaskStuckWatcher
            // skips the session. Was: status stayed Processing + lastActivityMs
            // frozen at dispatch → 116 taskStuck false positives/day (audit
            // 20260903) and, for sub-agents, the destructive Stop→hard-cancel
            // chain killing a pending question. Paired un-marks (state machine
            // must never strand WaitingForUser):
            //   answer → AskUserQuestionTool restore (Processing, fresh stamp)
            //   user cancel → Interrupt/ResetSession handler touch (Idle)
            //   turn end → finishTurnCont (Idle) — pre-existing backstop.
            // True-hang coverage is intact: every exit above re-enters scanned
            // statuses, and WaitingForUser itself is never a terminal state.
            // 工具面按角色分化批 B4（2026-09-13）：**只有阻塞模式**才是
            // human-in-the-loop 等待 —— 非阻塞发起即返回、答复稍后以注入用户输入
            // 到达（D5），**从未等待** ⇒ 不得标 WaitingForUser，也不得 pause 预算
            // （无配对物；误标即造出「永不解除的等待」，误 pause 即把没暂停的预算
            // 重复 resume）。`AskMode.parksTurn` 是单点判据（可独立单测）。
            // 判据由 `AskUserQuestionTool` 侧的运行期闸（B3）保证：非 root 会话
            // 根本发不出 NonBlocking（硬造 ⇒ 显式 ToolError，先于本分支）。
            val waitMarks: IO[Unit] =
              if AskMode.parksTurn(askMode) then
                touchRegistryActivity(resources, state.sessionId, AgentStatus.WaitingForUser) *>
                  // R11 第 4 层 / U1=C-a + U8=(ii)：ask **发起单点**发暂停信号——内核
                  // 的 3600s wall-clock 预算在等待期暂停（等待无界，R1）。非 Delegate
                  // 会话无预算通道 ⇒ 无害 no-op。配对恢复点 =
                  // AskUserQuestionTool.restoreRegistryAfterAnswer。
                  DelegateBudget.pause(srcSession)
              else IO.unit
            waitMarks *>
              (hub ! InteractionHubCommand.Request(
                InteractionRequest(
                  requestId = requestId,
                  kind = InteractionKind.AskUser,
                  payload = payload,
                  reply = InteractionReply.AskUserReply(replyToOpt),
                  rootSessionId = rootSid,
                  sourceAgent = srcAgent,
                  sourceSession = srcSession
                )
              )).void *> nodeAskEventIO
          case None =>
            // Hub not spawned (early boot / tests): cancel the ask so the
            // caller's AskUserQuestionTool `.?` does not hang forever.
            logger.warn(
              s"AskUser dropped: InteractionHub not spawned (requestId=$requestId sourceAgent=$srcAgent)"
            ) *>
              replyToOpt.fold(IO.unit)(replyTo => (replyTo ! Nil))
        }
        sendIO *> IO.pure(processing(agentDef, resources, depth, parentRef, state, pending))

      // --- Set permission deferred while processing (P1 fallback, hub absent) ---
      case AgentCommand.SetPermissionDeferred(deferred) =>
        setPermissionDeferredStay(state, deferred)(s =>
          IO.pure(processing(agentDef, resources, depth, parentRef, s, pending))
        )

      // --- Bypass toggled while processing（permshield S1：命令已退役，catch-all 兜底）---

      // --- Session model switched ---
      case AgentCommand.UpdateContextWindow(window) =>
        updateContextWindowStay(state, window)(s =>
          IO.pure(processing(agentDef, resources, depth, parentRef, s, pending))
        )

      // ctxthresh 批：processing 态收到阈值热更 ⇒ 轻量存储（无压缩副作用）。
      // 在飞 turn 用旧值、下一回合边界起用新值（口径 §8.3-E7 的时序语义）。
      case AgentCommand.SetCompactThresholdRatio(ratio) =>
        setCompactThresholdRatioStay(state, ratio)(s =>
          IO.pure(processing(agentDef, resources, depth, parentRef, s, pending))
        )

      // --- Buffer user-initiated messages during processing ---
      // Stored in ExecutionContext.pendingUserInputs (not the dead-end `pending`
      // parameter) so they are drained at the next turn boundary — the head is
      // re-sent to self and processed by the idle handler with full metadata.
      case msg: AgentCommand.UserInput =>
        logAgentEvent(
          agentDef,
          depth,
          state.sessionId,
          state.sessionName,
          "user-input-queued",
          s"textLen=${msg.text.length} pending=${state.execution.pendingUserInputs.size + 1}"
        )
        val updatedExec = state.execution.copy(
          pendingUserInputs = state.execution.pendingUserInputs :+ msg
        )
        IO.pure(processing(agentDef, resources, depth, parentRef, state.copy(execution = updatedExec), pending))
      case msg: AgentCommand.SkillActivate =>
        val updatedExec = state.execution.copy(
          pendingUserInputs = state.execution.pendingUserInputs :+ msg
        )
        IO.pure(processing(agentDef, resources, depth, parentRef, state.copy(execution = updatedExec), pending))
      case msg: AgentCommand.AskQuestion =>
        val updatedExec = state.execution.copy(
          pendingUserInputs = state.execution.pendingUserInputs :+ msg
        )
        IO.pure(processing(agentDef, resources, depth, parentRef, state.copy(execution = updatedExec), pending))
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
        persistQueues(state.sessionId, updatedExec) *> IO.pure(
          processing(agentDef, resources, depth, parentRef, state.copy(execution = updatedExec), pending)
        )

      // Queued mail arriving while busy — just count; actual content is on disk
      case AgentCommand.MailQueued(item, _) =>
        logAgentEvent(
          agentDef,
          depth,
          state.sessionId,
          state.sessionName,
          "mail-queued",
          s"from=${item.from} pendingCount=${state.execution.pendingMailQueueCount + 1}"
        )
        val updatedExec = state.execution.copy(
          pendingMailQueueCount = state.execution.pendingMailQueueCount + 1
        )
        IO.pure(processing(agentDef, resources, depth, parentRef, state.copy(execution = updatedExec), pending))

      // --- Session management (persistent sub-agents) ---
      case AgentCommand.SessionStarted(address, agentName, taskDescription) =>
        val session = AgentSessionInfo(address, agentName, taskDescription, "running")
        agentSessionsStay(state, state.agentSessions :+ session)(s =>
          IO.pure(processing(agentDef, resources, depth, parentRef, s, pending))
        )

      case AgentCommand.SessionUpdate(address, status) =>
        val updated = state.agentSessions.map(s => if s.address == address then s.copy(status = status) else s)
        agentSessionsStay(state, updated)(s => IO.pure(processing(agentDef, resources, depth, parentRef, s, pending)))

      case AgentCommand.SessionClosed(address) =>
        val updated = state.agentSessions.filterNot(_.address == address)
        agentSessionsStay(state, updated)(s => IO.pure(processing(agentDef, resources, depth, parentRef, s, pending)))

      case _ =>
        IO.pure(processing(agentDef, resources, depth, parentRef, state, pending))

    // Supervision: on unhandled error, return to idle with safe state
    new Behavior[AgentCommand]:
      def receive(c: ActorContext[AgentCommand], msg: AgentCommand): IO[Behavior[AgentCommand]] =
        base.receive(c, msg)
      override def onError(c: ActorContext[AgentCommand], err: Throwable): IO[Behavior[AgentCommand]] =
        for
          _ <- c.log.error(s"Agent error in processing, returning to idle: ${err.getMessage}")
          _ <- emitStream(state.wsSend, AgentStreamEvent.Interrupted, isSubagent = depth > 0, state.sessionId)
          _ <- markTeamIdle(agentDef, state.sessionId)
        yield idle(agentDef, resources, depth, parentRef, state.withStatus(AgentStatus.Idle).withInteraction(None))
  end processing

  // ============================================================
  // Mail check — flow agents must call Mail before finishing
  // ============================================================

  /** Check if the conversation contains any SUCCESSFUL Mail tool call. */
  private def hasUsedMail(messages: List[Message]): Boolean =
    val mailToolUseIds = messages
      .flatMap(_.content match
        case Right(blocks) =>
          blocks.collect {
            case ContentBlock.ToolUse(id, name, _) if name == "Mail" => id
          }
        case _ => Nil)
      .toSet
    if mailToolUseIds.isEmpty then false
    else
      // At least one Mail result must NOT be an error
      messages.exists(_.content match
        case Right(blocks) =>
          blocks.exists {
            case tr: ContentBlock.ToolResult if mailToolUseIds.contains(tr.toolUseId) =>
              !tr.isError.getOrElse(false)
            case _ => false
          }
        case _ => false)

  end hasUsedMail

  /** Inject a system reminder telling the agent to use Mail, then trigger a new turn. */
  private[agent] def handleMissingMail(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    replyTo: Option[ActorRef[AgentEvent]],
    result: ConsumeResult
  )(using ctx: ActorContext[AgentCommand]): IO[Behavior[AgentCommand]] =
    logAgentEvent(
      agentDef,
      depth,
      state.sessionId,
      state.sessionName,
      "mail-reminder",
      s"agent finished without Mail (reminder ${state.mailReminders + 1}/$MaxMailReminders)"
    )
    val assistantContent = (result.thinking, result.text) match
      case (None, _) => Left(result.text)
      case (Some(t), "") => Right(List(ContentBlock.Thinking(t, result.thinkingSignature)))
      case (Some(t), txt) =>
        Right(List(ContentBlock.Thinking(t, result.thinkingSignature), ContentBlock.Text(txt)))
    val assistantMsg = Message(MessageRole.Assistant, assistantContent)
    val reminderMsg = Message(
      MessageRole.User,
      Left(
        "<system-reminder>\nYou must use the Mail tool to report your result before finishing. Call Mail now with your findings.\n</system-reminder>"
      )
    )
    val newMessages = state.messages ++ List(assistantMsg, reminderMsg)
    val updatedState =
      state.copy(execution =
        state.execution.copy(
          messages = newMessages,
          status = AgentStatus.Processing,
          mailReminders = state.mailReminders + 1
        )
      )
    pipeLlmCall(agentDef, resources, depth, parentRef, updatedState, replyTo)
  end handleMissingMail

  // ============================================================
  // Supervisor restart helpers
  // ============================================================

  /**
   * Rollback the last tool call: truncate messages from the last ToolUse
   *  Assistant message onwards, inject a supervisor error message.
   *  If no tool call is found, returns state unchanged.
   */
  /**
   * Restart state by level (#13). Soft cancels current work keeping
   * messages; Rollback additionally truncates the last tool pair; Full
   * rebuilds message state from persisted history (documented "(future)
   * reset to empty, reload from persisted history" — implemented per issue
   * #13; it was silently aliased to Soft). Prune stays a Soft alias until
   * context-prune exists. Disk-load failure degrades to Soft (fail-safe).
   */
  private[agent] def restartStateFor(
    level: RestartLevel,
    state: AgentState,
    resources: SharedResources
  ): IO[AgentState] =
    def base: AgentState = state.resetForInterrupt.withPendingCompaction(None)
    level match
      case RestartLevel.Rollback => IO.pure(rollbackLastToolCall(base))
      case RestartLevel.Full =>
        val sid = state.sessionId.getOrElse("")
        if sid.isEmpty then IO.pure(base)
        else
          resources.sessionStore
            .loadMessagesForSession(sid)
            .map(msgs => base.copy(execution = base.execution.copy(messages = msgs)))
            .handleErrorWith { e =>
              logger.warn(
                s"Full restart: history reload failed for $sid (${e.getMessage}) — degrading to Soft"
              ) *> IO.pure(base)
            }
      case _ => IO.pure(base)
    end match
  end restartStateFor

  private def rollbackLastToolCall(state: AgentState): AgentState =
    val messages = state.messages
    // Find the index of the last Assistant message containing ToolUse blocks
    val lastToolUseIdx = messages.lastIndexWhere { msg =>
      msg.role == MessageRole.Assistant && {
        msg.content match
          case Right(blocks) => blocks.exists(_.isInstanceOf[ContentBlock.ToolUse])
          case _ => false
      }
    }
    if lastToolUseIdx < 0 then state // No tool call found — nothing to rollback
    else
      // Extract the tool name for the error message
      val toolName = messages(lastToolUseIdx).content match
        case Right(blocks) =>
          blocks.collectFirst { case ContentBlock.ToolUse(_, name, _) => name }.getOrElse("unknown")
        case _ => "unknown"
      // Truncate from the tool call onwards
      val truncated = messages.take(lastToolUseIdx)
      // Inject supervisor message
      val supervisorMsg = Message(
        MessageRole.User,
        Left(
          s"[SUPERVISOR] Your last action ($toolName) was rolled back because it caused a problem. " +
            "Do not repeat the same approach. Try a different strategy."
        )
      )
      state.withMessages(truncated :+ supervisorMsg)
    end if
  end rollbackLastToolCall

  // ============================================================
  // Pipe wrappers
  // ============================================================

  private[agent] def pipeLlmCall(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    replyTo: Option[ActorRef[AgentEvent]],
    cause: DispatchCause = DispatchCause.Gated
  )(using ctx: ActorContext[AgentCommand]): IO[Behavior[AgentCommand]] =
    // Turn start: mark team agent busy. Idempotent (Set), so the recursive
    // multi-tool-call turns that re-enter pipeLlmCall are harmless.
    // MUST run unconditionally BEFORE the freeze gate (spec 6a / B12): a frozen
    // team agent must still read busy in the Teams panel — putting this in the
    // dispatch branch would leave frozen team agents marked idle.
    markTeamBusy(agentDef, state.sessionId) *>
      // ── Freeze gate (freeze-schedule spec ⑥, F2 single choke point) ──
      // 所有 AgentActor 层 dispatch 都经过本 shadow；AgentCore 内部递归
      // （maybeAutoCompact 等）静态解析不经此处，但只会在 gate 放行后执行。
      // 冻结期间零 LLM 调用（零 token 硬指标）——拦截后转入 frozen behavior，
      // 持有完整 state（工具结果已在冻结前持久化，F1）。
      // #337：黑名单语义——segments 是冻结时间，段内 window.frozen=true。
      resources.freezeScheduleRef.get.flatMap { cfg =>
        resources.freezeSkipUntilRef.get.flatMap { skipUntil =>
          // #337 黑名单语义：segments = 冻结时段（非工作时间），段内 frozen=true。
          // 2026-08-25 裁定：用户消息全局跳过——skipUntil 未到期（用户消息作废了
          // 本次冻结窗口）→ evalWithSkip 返回 frozen=false，所有 agent 恢复工作。
          val now = System.currentTimeMillis()
          val window = nebflow.core.schedule.FreezeSchedule.evalWithSkip(cfg, skipUntil, now)
          // D11 交互豁免：ask 轮（用户在场等回答）与 freezeExempt 会话
          // （交互场景）不冻结——冻结它们省的 token 远低于浪费的用户等待。
          val interactive = state.askMode.isDefined || state.session.freezeExempt
          if window.frozen && cause == DispatchCause.Gated && !interactive then
            logAgentEvent(
              agentDef,
              depth,
              state.sessionId,
              state.sessionName,
              "freeze-enter",
              s"resumeAt=${window.nextChangeAt.map(_.toString).getOrElse("none")}"
            )
            enterFrozen(agentDef, resources, depth, parentRef, state, replyTo, window.nextChangeAt)
          else
            AgentActor.corePipeLlmCall(
              agentDef,
              resources,
              depth,
              parentRef,
              state,
              replyTo,
              (ad, r, d, p, s) => processing(ad, r, d, p, s)
            )
          end if
        }
      }

  private[agent] def pipeToolExecutions(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    result: ConsumeResult,
    replyTo: Option[ActorRef[AgentEvent]]
  )(using ctx: ActorContext[AgentCommand]): IO[Behavior[AgentCommand]] =
    AgentActor.corePipeToolExecutions(
      agentDef,
      resources,
      depth,
      parentRef,
      state,
      result,
      replyTo,
      (ad, r, d, p, s) => processing(ad, r, d, p, s)
    )

end AgentProcessing
