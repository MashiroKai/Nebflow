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
import nebflow.core.flow.TeamSessionRegistry
import nebflow.core.tools.AskUserQuestionTool
import nebflow.core.tools.{BgTaskRegistry, ShellSession}
import nebflow.llm.{AllProvidersDownTimeout, Fallback, FallbackExhaustedError}
import nebflow.shared.*
import nebflow.shared.given

import scala.concurrent.duration.*

/**
 * Dispatch 发起方分类（freeze-schedule spec D1）：决定 pipeLlmCall gate 的行为。
 * Gated（默认）= 系统续跑（ToolsComplete 续轮 / finishTurnCont / ExternalEvent /
 * CompactionComplete 恢复 / Retry 等）——受冻结时间表约束（#337 黑名单语义）；UserWake = 用户显式
 * 发起（idle UserInput[clientMessageId.isDefined] / AskQuestion / SkillActivate）
 * ——冻结时段也放行（用户输入即唤醒，需求硬指标）。
 */
sealed trait DispatchCause

object DispatchCause:
  /** 系统续跑：受冻结调度约束（冻结窗口内进入 frozen behavior）。 */
  case object Gated extends DispatchCause

  /** 用户显式发起：gate 直接放行（唤醒语义）。 */
  case object UserWake extends DispatchCause

/**
 * Pure turn-boundary drain decisions shared by ToolsComplete / CompactionComplete.
 * Top-level and side-effect-free so specs can verify the compaction guard
 * directly (queued-inputs-during-compaction bug, 2026-08-14).
 */
object TurnBoundaryDrains:

  /**
   * Drain at most the head of a queue at a turn boundary (serial processing,
   * remainder stays queued). While a compaction job is pending the queue must
   * NOT be consumed: anything injected into the save/compact turn's history is
   * discarded when the summary replaces it — consumed from the queue yet lost.
   * CompactionComplete drains the queues after the summary is in place.
   */
  def drainHead[A](queue: List[A], compactionPending: Boolean): (Option[A], List[A]) =
    if compactionPending then (None, queue)
    else (queue.headOption, if queue.isEmpty then queue else queue.tail)

  /** True for ExternalEvents carrying a Delegate/SubTask result. */
  def isSubagentResult(e: AgentCommand.ExternalEvent): Boolean =
    e.source == "subtask" || e.source == "delegate"

  /**
   * Count how many sub-agent barrier slots a batch of tool results should add.
   * Only successful ephemeral Delegates and all SubTasks count — persistent
   * Delegates send their completion with source=address (not "delegate"), so
   * isSubagentResult never matches and the counter would never decrement.
   * (qa-backend 2026-08-19: phantom barrier fix)
   */
  def countBarrierIncrements(results: List[(nebflow.shared.ToolCall, nebflow.core.ToolExecResult)]): Int =
    results.count { (call, r) =>
      !r.isError && (
        call.name == "SubTask" ||
        (call.name == "Delegate" && call.input("lifecycle").flatMap(_.asString).forall(_ != "persistent"))
      )
    }

  /**
   * Barrier-aware drain (2026-08-18, worker blocking semantics): while a
   * parallel sub-agent batch is outstanding (outstanding > 0), subtask/delegate
   * result events are HELD in the queue — the agent is not interrupted one
   * result at a time. The first non-subagent event (if any) may still pass,
   * so mail/background-task keep their existing serial drain. When the batch
   * is complete (outstanding == 0), ALL held subtask/delegate results are
   * drained together for one batched injection; other event types keep the
   * existing one-at-a-time drain. Compaction guard applies as in [[drainHead]].
   */
  def drainBarrier(
    queue: List[AgentCommand.ExternalEvent],
    compactionPending: Boolean,
    outstanding: Int
  ): (List[AgentCommand.ExternalEvent], List[AgentCommand.ExternalEvent]) =
    if compactionPending then (Nil, queue)
    else if outstanding > 0 then
      queue.indexWhere(e => !isSubagentResult(e)) match
        case -1 => (Nil, queue) // everything held — wait for the batch
        case i => (List(queue(i)), queue.patch(i, Nil, 1))
    else
      val (subtasks, others) = queue.partition(isSubagentResult)
      (subtasks ::: others.take(1), others.drop(1))

end TurnBoundaryDrains

object AgentActor extends AgentCore with AgentSession:

  private val MaxEmptyResponseRetries = 5

  /** Overload-class reasons: provider saturated, backoff can heal it. */
  def isOverloadReason(r: FailoverReason): Boolean =
    r == FailoverReason.Overloaded || r == FailoverReason.RateLimit

  /** Flow-node supervision P1 (2026-08-26): agent-turn-level retryability of an
    * LLM failure. Retryable = overload-class (saturation may clear during
    * backoff) OR stream-inactivity stall (phase-2 watchdog — upstream jitter;
    * a clean-checkpoint re-send within the SAME OverloadRetryMax/MaxTurnLlmCalls
    * budget, no new quota; NOT a stream-level resume, so the seam guard is never
    * violated). Everything else fails fast (2026-08-18 token-incident ruling).
    * Single source: the llm-fail retry branch AND AgentError.retryable (P2). */
  def llmFailureRetryable(error: Throwable): Boolean = error match
    case e: FallbackExhaustedError =>
      e.attempts.forall(a =>
        a.reason.exists(isOverloadReason)
          || (a.reason.contains(FailoverReason.Timeout) && a.permanence.contains(ErrorPermanence.Transient))
      )
    case _: ToolPipelineError => false
    case _: StreamInactivityTimeout => true
    case _ =>
      val cls = Fallback.classifyError(error)
      cls.permanence == ErrorPermanence.Transient && isOverloadReason(cls.reason)

  /** Inactivity-class failure (drives the ≥30s retry backoff): a typed stall,
    * or every attempt in an exhausted chain was a Transient timeout. */
  def llmFailureInactivityClass(error: Throwable): Boolean = error match
    case _: StreamInactivityTimeout => true
    case e: FallbackExhaustedError =>
      e.attempts.nonEmpty && e.attempts.forall(a =>
        a.reason.contains(FailoverReason.Timeout) && a.permanence.contains(ErrorPermanence.Transient)
      )
    case _ => false

  /** Max "you must call Mail" reminder injections before giving up (initial + retries). */
  private val MaxMailReminders = 2

  /**
   * Max llm-fail retries for overload-class failures (429 rate-limit / 529
   * overloaded): the provider is saturated — a ≥5s backoff gives it a chance
   * to recover. Token incident (2026-08-18): every retry re-sends the full
   * ~250k-token context, so ALL other transient errors now fail fast — the
   * messages are unchanged between retries (96% cache hit), so retrying them
   * mostly amplifies spend without healing anything.
   */
  private val OverloadRetryMax = 1

  /** Flow-node supervision P1: stream-inactivity retries back off at least
    * this long — the upstream stall lasted 60s+ (watchdog window), an
    * immediate re-send would hit the same stall. */
  private val InactivityRetryBackoffMinMs = 30_000L

  /** Test hook: override the inactivity retry backoff floor (specs inject
    * ~100ms so checkpoint-restart integration tests don't wait 30s per retry).
    * Public for cross-package specs (core.entity supervision spec). Global
    * var — specs MUST reset to None in a finally. */
  var testInactivityBackoffMs: Option[Long] = None

  private def effectiveInactivityBackoffMinMs: Long =
    testInactivityBackoffMs.getOrElse(InactivityRetryBackoffMinMs)

  /** Exponential backoff base for LLM fail retries (ms). */
  private val LlmFailBackoffBaseMs = 2000L

  /** Cap for LLM fail backoff (ms). */
  private val LlmFailBackoffMaxMs = 10000L

  /** Overload-class failures always back off ≥5s before retrying. */
  private val OverloadBackoffMinMs = 5000L

  /** Cancel-batch debounce window (user ruling 2026-08-26 08:33): task
    * cancellations arriving while the agent is processing merge into ONE
    * packaged notice, flushed when the window (measured from the FIRST
    * buffered cancellation) expires. 45s = mid of the approved 30-60s range.
    * The timer rides forkTurn — a turn boundary naturally cancels it and the
    * buffer degrades to the idle-cache semantics (delivered, never lost). */

  // ── v2 冻结式错误恢复（20260824_frozen-error-recovery-plan §3.3/§5.1）─────
  /** 同 reason 连续 ErrorFrozen ≥ 本值 → 进入升级链（请求父干预，不再直接 fatal）。 */
  private val ErrorFreezeEscalationThreshold = 3

  /** Block 3 循环检测器 L2（supervision trio §D3）：Loop 冻结的 resumeAt 偏移
    * ——语义「永不自动续跑」（1 年），恢复只走人工三路：用户输入唤醒 /
    * Manager·Nebula AgentControl restart / cancel 终态。 */
  private val LoopFreezeResumeMs = 365L * 24 * 3600 * 1000

  /** Network 类错误的 ErrorFrozen 退避窗口：10-30s（含 jitter），短退避后自动重试 1-2 次。 */
  private val NetworkErrorFreezeBackoffMs = 10_000L

  /** ProviderDown 的 ErrorFrozen 退避：≈ 探测周期（120s+jitter），P0 用 R1 轮询兜底。 */
  private val ProviderDownFreezeBackoffMs = 120_000L

  /** FreezeReason → wire 字符串（与 protocol.toJson 序列化一致，单源映射）。 */
  private def reasonStr(r: FreezeReason): String = r match
    case FreezeReason.Schedule        => "schedule"
    case FreezeReason.LlmTransient    => "llm-transient"
    case FreezeReason.Network         => "network"
    case FreezeReason.ProviderDown    => "provider-down"
    case FreezeReason.RestartRecovery => "restart-recovery"
    case FreezeReason.Loop            => "loop"

  /** Overload-class reasons: provider saturated, backoff can heal it. */
  private def isOverloadClass(r: FailoverReason): Boolean = AgentActor.isOverloadReason(r)

  private val logger = NebflowLogger.forName("nebflow.agent")

  /**
   * Infer the injection source (任务 P) for a tool-originated UserInput that
   * did not carry an explicit source. Discriminator: clientMessageId is empty
   * (user WS inputs always carry one). The source is derived from the agent's
   * own session id prefix, falling back to the fork-adapter replyTo for
   * Mail ask/fork spawns, and finally the generic "tool".
   *
   *   delegate-… → "delegate"   SubTaskTool worker
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

  /** issue #31 Fix D (2026-08-20)：把自身 barrier 状态快照进 agentRegistry，
    * 供 AgentControl list/status 展示——phantom slot（成员 hang / 停止失败时
    * barrier 永不归还）从日志考古变成一条命令可见。诊断语义：idle 期
    * outstanding > 0 且无在飞任务 = phantom；outstanding > 0 且 pending > 0 =
    * 有结果被 HOLD 扣留等批。幂等，registry 无该 session 时 no-op。
    * 刷新点：spawn 计数（tools-complete）、ExternalEvent 三分支。 */
  private def touchBarrierSnapshot(
    resources: SharedResources,
    sessionId: Option[String],
    outstanding: Int,
    pending: Int
  ): IO[Unit] =
    sessionId.fold(IO.unit) { sid =>
      resources.agentRegistry.modify { m =>
        m.get(sid) match
          case Some(rec) =>
            (m.updated(sid, rec.copy(outstandingSubagents = outstanding, pendingEventCount = pending)), ())
          case None => (m, ())
      }
    }

  /**
   * ExternalEvent sources whose result-notification payload should render as a
   * visible injected-user bubble (任务 Q, report §3 🔴). Low-value internal
   * sources (mail-ask, schedule, filewatch, bridge-already-visible, etc.) stay
   * hidden — only the high-value result notifications get a bubble.
   *
   *   delegate / subtask  → A6/A9 sub-agent completion
   *   background-task      → B1 background command completion
   *   eventType=="inject"  → B4 API inject (source is user-controlled)
   */
  private def visibleExternalEventSource(source: String, eventType: String): Option[String] =
    if eventType == "inject" then Some(source)
    else if source == "delegate" then Some("delegate")
    else if source == "subtask" then Some("subtask")
    else if source == "background-task" then Some("background")
    else None

  /**
   * Build the system-reminder message for one or more external events (sub-agent
   * results). Single events produce the existing one-payload reminder; a
   * completed sub-agent batch produces ONE message with all payloads numbered,
   * so the LLM sees the whole batch in a single turn instead of N interruptions.
   * Retryable-failure guidance is appended per event, mirroring the single-event
   * idle path.
   */
  private def buildEventReminder(events: List[AgentCommand.ExternalEvent]): Message =
    val parts = events.zipWithIndex.map { (e, i) =>
      val hint =
        if e.eventType == "failed" && e.metadata("retryable").exists(_.asBoolean.getOrElse(false)) then
          val failureType = e.metadata("failureType").flatMap(_.asString).getOrElse("unknown")
          val failedSession = e.metadata("failedSessionId").flatMap(_.asString).getOrElse("")
          val agentName = e.metadata("agentName").flatMap(_.asString).getOrElse("")
          s"\n<system-reminder>\nA sub-agent task${if agentName.nonEmpty then s" ($agentName)" else ""}" +
            s"${if failedSession.nonEmpty then s" [session=$failedSession]" else ""} failed" +
            s" (failure type: $failureType). This is a retryable error — consider re-delegating" +
            s" the same task with the Delegate or SubTask tool.\n</system-reminder>"
        else ""
      s"${i + 1}. ${e.payload}$hint"
    }
    // Reminder refactor (2026-08-20): source marker is the fromUser signal —
    // external-event injections must NOT look like user-typed messages.
    Message(MessageRole.User, Left(s"<system-reminder>\n${parts.mkString("\n\n")}\n</system-reminder>"), source = Some("external"))

  // ============================================================
  // F1 (2026-08-30, compact-injection-shield G1): unified post-compaction
  // queue drain. Both CompactionComplete(Right) branches (resume=true
  // auto-compaction continuation and resume=false return-to-idle) share ONE
  // drain so queued injections accumulated during the compaction window are
  // NEVER lost: immediate inputs + user input head + barrier-drained events
  // all land in the very next turn.
  // ============================================================

  /** Result of [[drainQueuesAfterCompaction]]. */
  private[agent] case class PostCompactDrain(
    /** Messages to append to the continuation round (immediate inputs + user inputs + events). */
    appended: List[Message],
    /** Messages appended from immediate inputs (for per-input WS bubble emission). */
    immMessages: List[Message],
    /** Original immediate inputs (source/eventType/sender for emitInjectedUserEvent). */
    injectedImms: List[AgentCommand.ImmediateInput],
    /** Queued UserInput commands converted to messages (injected inline). */
    userMessages: List[Message],
    /** Original UserInput commands (source/eventType/sender for emitInjectedUserEvent). */
    injectedUsers: List[AgentCommand.UserInput],
    /** Queued external events injected as ONE batched reminder (None if none drained). */
    eventMessage: Option[Message],
    /** Number of events folded into eventMessage. */
    eventCount: Int,
    /** Updated execution with queues drained. */
    exec: ExecutionContext
  )

  /** ImmediateInput → User message (blocks preferred, text fallback). */
  private def immInputToMessage(imm: AgentCommand.ImmediateInput): Message =
    (imm.blocks match
      case Some(blocks) if blocks.nonEmpty => Message(MessageRole.User, Right(blocks))
      case _ => Message(MessageRole.User, Left(imm.text))
    ).copy(source = imm.source)

  /** UserInput (AgentCommand) → User message for inline continuation injection. */
  private def userCmdToMessage(ui: AgentCommand.UserInput): Message =
    (ui.blocks match
      case Some(blocks) if blocks.nonEmpty => Message(MessageRole.User, Right(blocks))
      case _ => Message(MessageRole.User, Left(ui.text))
    ).copy(source = ui.source)

  /**
   * F1 (2026-08-30): drain every queue that was held back during the
   * compaction window (ToolsComplete guard keeps pendingImmediateInputs and
   * pendingEvents untouched while a job is pending; the processing handler
   * buffers UserInputs into pendingUserInputs). Inject ALL of it into the
   * continuation round — immediate inputs each as their own User message
   * (with WS bubble emission), UserInput commands inline as User messages
   * (only replyTo-free ones — a UserInput carrying a replyTo must keep the
   * full-metadata path so its completion target is not stranded), and ALL
   * events batched via buildEventReminder (sub-agent barrier: Delegate/
   * SubTask results stay held while the batch is outstanding — #25/#31).
   * Non-UserInput commands (SkillActivate/AskQuestion) keep the
   * full-metadata queue: they stay in pendingUserInputs and are forwarded
   * by the next turn boundary drain (finishTurnCont) — never lost, only
   * deferred.
   */
  /**
   * F2 (2026-08-30): snapshot the injection queues to disk. Called on every
   * enqueue and every drain so a crash mid-compaction degrades to "queued
   * injections survive the restart" instead of "silently lost". Failures are
   * logged inside the store and never propagate — enqueue must not block.
   */
  private def persistQueues(sessionId: Option[String], exec: ExecutionContext): IO[Unit] =
    sessionId match
      case Some(sid) =>
        CompactionQueueStore.save(
          sid,
          CompactionQueueStore.PersistedQueues(exec.pendingImmediateInputs, exec.pendingEvents)
        )
      case None => IO.unit

  /**
   * V13 (2026-09-03): is this mail delivery inside the REPLAY window — i.e.
   * could it be the MailTool restart-recovery re-fire of the disk head? That
   * re-fire is the only proven true-duplicate source and it happens within
   * seconds of the recipient session's activation (AgentRecord.startedAt).
   * Inside the window → dedup ledger consult applies (suppress restart
   * replays); outside → a same-content re-send is legitimate and must deliver
   * (V13: the 30min triple fingerprint used to eat it). Missing registry
   * record / legacy 0 stamp → conservative fallback: dedup active
   * (pre-V13 behavior).
   */
  private def mailDedupInReplayWindow(sid: String, resources: SharedResources): IO[Boolean] =
    resources.agentRegistry.get.map { reg =>
      reg.get(sid) match
        case Some(rec) if rec.startedAt > 0 =>
          System.currentTimeMillis() - rec.startedAt <= nebflow.shared.Defaults.MailDedupReplayWindowMs
        case _ => true
    }

  private[agent] def drainQueuesAfterCompaction(compactedState: AgentState): PostCompactDrain =
    val exec = compactedState.execution
    val imms = exec.pendingImmediateInputs
    val immMessages = imms.map(immInputToMessage)
    // replyTo-free UserInput commands can be safely inlined into the
    // continuation round; UserInputs carrying a replyTo (and non-UserInput
    // commands) stay queued for full-metadata forwarding.
    val (userMessages, userTail) = exec.pendingUserInputs.partitionMap {
      case ui: AgentCommand.UserInput if ui.replyTo.isEmpty => Left((ui, userCmdToMessage(ui)))
      case other => Right(other)
    }
    val (injectedUsers, userMsgs) = userMessages.unzip
    // Full flush (2026-08-30, G1): EVERY event held during the compaction
    // window is injected together in the continuation round — the window is a
    // one-time flush, not the steady-state one-event-per-turn-boundary drain
    // (drainBarrier's serial pacing). The only exception is the sub-agent
    // barrier (#25/#31): while a parallel Delegate/SubTask batch is still
    // outstanding, its result events stay HELD — injecting them one at a time
    // would break the batch-injection contract. Non-subagent events
    // (background/mail notifications) flush regardless, exactly as the
    // steady-state drain lets them pass during an outstanding batch.
    val (drainedEvents, remainingEvents) =
      if exec.outstandingSubagentResults > 0 then
        val (subtaskHeld, others) = exec.pendingEvents.partition(TurnBoundaryDrains.isSubagentResult)
        (others, subtaskHeld)
      else (exec.pendingEvents, Nil)
    val eventMessage =
      if drainedEvents.nonEmpty then Some(buildEventReminder(drainedEvents)) else None
    val appended = immMessages ++ userMsgs ++ eventMessage.toList
    val updatedExec = exec.copy(
      pendingImmediateInputs = Nil,
      pendingUserInputs = userTail,
      pendingEvents = remainingEvents
    )
    PostCompactDrain(
      appended,
      immMessages,
      imms,
      userMsgs,
      injectedUsers,
      eventMessage,
      drainedEvents.size,
      updatedExec
    )

  /** WS bubble emission for every injected immediate/user input that carries a source. */
  private def emitInjectedBubbles(
    state: AgentState,
    drain: PostCompactDrain
  )(using ctx: ActorContext[AgentCommand]): IO[Unit] =
    val immBubbles = drain.injectedImms.traverse_ { imm =>
      imm.source match
        case Some(src) =>
          emitInjectedUserEvent(
            state.wsSend, state.sessionId, imm.text, src, imm.eventType, imm.sender, imm.senderTeam, imm.delivery
          )
        case None => IO.unit
    }
    val userBubbles = drain.injectedUsers.traverse_ { ui =>
      ui.source match
        case Some(src) =>
          emitInjectedUserEvent(
            state.wsSend, state.sessionId, ui.text, src, ui.eventType, ui.sender, ui.senderTeam, ui.delivery
          )
        case None => IO.unit
    }
    immBubbles *> userBubbles

  /** F1/F3: audit log — how many queued injections landed in the continuation round. */
  private def logPostCompactInjection(
    agentDef: AgentDef,
    depth: Int,
    state: AgentState,
    drain: PostCompactDrain
  )(using ctx: ActorContext[AgentCommand]): IO[Unit] =
    IO(logAgentEvent(
      agentDef,
      depth,
      state.sessionId,
      state.sessionName,
      "queues-injected-after-compaction",
      s"imm=${drain.immMessages.size} user=${drain.userMessages.size} events=${drain.eventCount} " +
        s"remainingImm=${drain.exec.pendingImmediateInputs.size} " +
        s"remainingUser=${drain.exec.pendingUserInputs.size} " +
        s"remainingEvents=${drain.exec.pendingEvents.size}"
    ))

  /**
   * Emit a `user` WS event so the frontend renders an injected-task bubble
   * (浅蓝, source-labeled) instead of leaving the task prompt invisible.
   * Mirrors the `user` event shape the frontend already handles for normal
   * user input (SessionRecorder records it as UiMessage.User with injected=true).
   */
  private def emitInjectedUserEvent(
    wsSend: Json => IO[Unit],
    sessionId: Option[String],
    text: String,
    source: String,
    eventType: Option[String] = None,
    sender: Option[String] = None,
    senderTeam: Option[String] = None,
    delivery: Option[String] = None,
    waitingForBatch: Boolean = false
  )(using ctx: ActorContext[AgentCommand]): IO[Unit] =
    sessionId.fold(IO.unit) { sid =>
      val base = Json.obj(
        "type" -> "user".asJson,
        "text" -> text.asJson,
        "injected" -> true.asJson,
        "source" -> source.asJson,
        "sessionId" -> sid.asJson
      )
      val withEt = eventType.fold(base)(et => base.deepMerge(Json.obj("eventType" -> et.asJson)))
      val withSender = sender.fold(withEt)(s => withEt.deepMerge(Json.obj("sender" -> s.asJson)))
      val withTeam = senderTeam.fold(withSender)(t => withSender.deepMerge(Json.obj("senderTeam" -> t.asJson)))
      val withDelivery = delivery.fold(withTeam)(d => withTeam.deepMerge(Json.obj("delivery" -> d.asJson)))
      // issue #31 Fix C (2026-08-20): HOLD 分支的完成气泡标注「等待同批任务」——
      // 把「COMPLETED 但父不动」从 bug 观感变成可理解的等待状态（前端展示
      // 待 Frontend 消费此字段）。顺修既有 bug：此处原发 withTeam，
      // withDelivery 被算出后丢弃（delivery 字段从未到达前端）。
      val withWaiting =
        if waitingForBatch then withDelivery.deepMerge(Json.obj("waitingForBatch" -> true.asJson))
        else withDelivery
      ctx.forkTurn(
        wsSend(withWaiting).handleErrorWith(e => logger.warn(s"injected user event failed: ${e.getMessage}"))
      )
    }

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
    agentsMd: Option[String] = None,
    folderId: Option[String] = None,
    safetyMode: String = "confirm-edits",
    gitBranch: Option[String] = None,
    expectsMail: Boolean = false,
    /**
     * P2: permission-policy bucket (inheritance anchor). Explicitly passed at
     * every spawn site (Root=itself, Team=parent root session, Flow/Ephemeral=
     * itself, Delegate=resolved caller root). Falls back to sessionId so legacy
     * spawn sites still get a sane bucket (P1 best-effort behavior).
     */
    rootSessionId: String = "",
    isSubTaskWorker: Boolean = false,
    /** D11 交互豁免：freezeExempt 会话不参与冻结。 */
    freezeExempt: Boolean = false,
    /** #406: one-shot FlowExecute node — leaf tools stripped (see SessionContext.isFlowNode). */
    isFlowNode: Boolean = false,
    /** 轨道二 #5: 节点 userFacing 白名单声明（详见 SessionContext.userFacingNode）。 */
    userFacingNode: Boolean = false,
    /** 阶段 2a 沙箱（§A.6）：project 节点/分发器 spawn 置 true——AgentCore 据此
      * 从 projectRoot 派生 ToolContext.sandbox。默认 false=旧行为（双轨豁免面）。 */
    sandboxEnabled: Boolean = false
  ): Behavior[AgentCommand] =
    Behaviors.setup { ctx =>
      val effectiveRootSessionId =
        if rootSessionId.nonEmpty then rootSessionId else sessionId.getOrElse("")
      logAgentEvent(
        agentDef,
        depth,
        sessionId,
        sessionName,
        "spawn",
        s"parent=${parentRef.map(_.path.name).getOrElse("-")} msgs=${initialMessages.size}"
      )(using ctx)
      // F2 (2026-08-30): replay persisted injection queues. Sent to self
      // BEFORE any external delivery can enqueue — Pekko mailbox ordering
      // guarantees this message is processed first, so a recovered entry is
      // never re-ordered after a fresh enqueue.
      // NOTE: `ctx.self !` returns IO[Unit] — a bare statement would be
      // built-not-run (2026-08-30 probe: setup-time self-send silently
      // dropped), so it MUST be sequenced into the factory's IO.
      (ctx.self ! AgentCommand.RecoverPersistedQueues) *> IO.pure(
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
            contextWindow = contextWindow,
            projectRoot = projectRoot,
            rulesMd = rulesMd,
            agentsMd = agentsMd,
            folderId = folderId,
            safetyMode = safetyMode,
            gitBranch = gitBranch,
            expectsMail = expectsMail,
            rootSessionId = effectiveRootSessionId,
            isSubTaskWorker = isSubTaskWorker,
            freezeExempt = freezeExempt,
            isFlowNode = isFlowNode,
            userFacingNode = userFacingNode,
            sandboxEnabled = sandboxEnabled
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

  /**
   * #391 机制 E：restart/Stop 联动——杀该 session 全部 shell 进程树（前台 +
   * 后台 runProcess 注册的 OS 进程）+ 注销 BgTaskRegistry + WS cancelled 通知。
   *
   * B9 残留根因链修复：AgentControl restart → Stop → cancelCurrentTurn 只取消
   * turn fiber（cats-effect Fiber），shell.scala 全程 IO.blocking 取消不中断线程，
   * bracket release 的 killProcessTree 永不执行 → bash/Chrome/helpers 进程树残留
   * 需手动 pkill。killSessionProcesses 直接杀注册的进程树，断掉这条链。
   * 不碰：其他 session 的进程、JVM 自身（ProcessTree 只操作注册的 ProcessHandle）。
   */
  private def killSessionShellProcesses(state: AgentState): IO[Unit] =
    ShellSession.killSessionProcesses(state.sessionId) *>
      BgTaskRegistry.unregisterSession(state.sessionId).flatMap { removed =>
        removed.traverse_ { t =>
          state.wsSend(
            io.circe.Json.obj(
              "type" -> "backgroundTaskUpdate".asJson,
              "sessionId" -> state.sessionId.asJson,
              // 权威分键（2026-09-05 计数/列表分叉修复）：与 BashTool/RemoteExecutor
              // 发射点一致携带 rootSessionId，restart/Stop 清场帧按根会话分桶直达
              // 归属视图（前端已删 bgTaskRootFor 启发式逆向分键）。
              "rootSessionId" -> state.rootSessionId.asJson,
              "taskId" -> t.jobId.asJson,
              "description" -> t.description.asJson,
              "status" -> "cancelled".asJson
            )
          ).handleErrorWith(_ => IO.unit)
        }
      }

  /** Build the "askUser" WS payload for the frontend. When the question comes
    * from a sub-agent (ForwardAskUser), agentName is set to the source agent
    * for attribution and sourceAgent/sourceSession carry the origin info.
    * Pure — `private[agent]` so the #380 passthrough contract (canvas/preview
    * emitted only when present, byte-identical otherwise) is unit-covered.
    */
  private[agent] def buildAskUserJson(
    sessionId: Option[String],
    agentName: String,
    items: List[AskItem],
    sourceAgent: Option[String] = None,
    sourceSession: Option[String] = None
  ): Json =
    val fields = scala.collection.mutable.ListBuffer(
      "type" -> "askUser".asJson,
      "sessionId" -> sessionId.asJson,
      "agentName" -> agentName.asJson
    )
    sourceAgent.foreach(sa => fields += "sourceAgent" -> sa.asJson)
    sourceSession.foreach(ss => fields += "sourceSession" -> ss.asJson)
    fields += "items" -> Json.fromValues(items.map { item =>
      val base = scala.collection.mutable.ListBuffer(
        "question" -> item.question.asJson,
        "options" -> Json.fromValues(item.options.map { opt =>
          val optFields = scala.collection.mutable.ListBuffer("label" -> opt.label.asJson)
          opt.description.foreach(d => optFields += "description" -> d.asJson)
          // preview emitted only when present — pre-#380 option payloads stay byte-identical
          opt.preview.foreach { pv =>
            val pvFields = scala.collection.mutable.ListBuffer("type" -> pv.`type`.asJson)
            pv.colors.foreach(cs => pvFields += "colors" -> cs.asJson)
            pv.src.foreach(s => pvFields += "src" -> s.asJson)
            optFields += "preview" -> Json.obj(pvFields.toList*)
          }
          Json.obj(optFields.toList*)
        }),
        "allowOther" -> item.allowOther.asJson
      )
      item.id.foreach(id => base += "id" -> id.asJson)
      item.dependsOn.foreach { dep =>
        base += "dependsOn" -> Json.obj("ref" -> dep.ref.asJson, "equals" -> dep.equals.asJson)
      }
      // multiple is emitted only when true — every pre-multiple payload stays byte-identical
      if item.multiple then base += "multiple" -> true.asJson
      // canvas emitted only when present — pre-#380 question payloads stay byte-identical
      item.canvas.foreach(c => base += "canvas" -> c.asJson)
      // dirPicker emitted only when true — pre-workspace-picker payloads stay byte-identical
      // (2026-09-05 作者裁定：工作区选择卡；前端据此渲染系统目录选择大目标)
      if item.dirPicker then base += "dirPicker" -> true.asJson
      Json.obj(base.toList*)
    })
    Json.obj(fields.toList*)
  end buildAskUserJson

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

      case AgentCommand.UserInput(text, replyTo, clientMessageId, blocks, chatWidth, source, sender, senderTeam, delivery, eventType) =>
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
          // Injection source (任务 P): user WS inputs always carry clientMessageId.
          // Tool-injected inputs (Mail ask/fork, Delegate, SubTask, ImmediateInput
          // converted from Mail delivery) have clientMessageId=None → emit a user
          // event so the frontend renders the task prompt as a visible bubble.
          val injectionSource: Option[String] =
            if clientMessageId.isDefined then None
            else source.orElse(inferInjectionSource(state.sessionId, replyTo))
          val enrichedBlocks: Option[List[ContentBlock]] = blocks.filter(_.nonEmpty)
          val stateWithFlush = stateWithWidth
          val userMsg = (enrichedBlocks match
            case Some(bl) => Message(MessageRole.User, Right(bl))
            case None => Message(MessageRole.User, Left(text))
          ).copy(source = injectionSource)
          val newMessages = stateWithFlush.messages :+ userMsg
          val sessionBusyIO =
            if depth == 0 then
              stateWithWidth.sessionId.fold(IO.unit)(sid => emitSessionBusy(stateWithWidth.wsSend, sid, busy = true))
            else IO.unit
          val injectedEventIO = injectionSource match
            case Some(src) =>
              emitInjectedUserEvent(
                stateWithWidth.wsSend,
                stateWithWidth.sessionId,
                text,
                src,
                eventType,
                sender = sender,
                senderTeam = senderTeam,
                delivery = delivery
              )
            case None => IO.unit
          for
            _ <- sessionBusyIO
            _ <- injectedEventIO
            result <- pipeLlmCall(
              agentDef,
              resources,
              depth,
              parentRef,
              stateWithFlush.withMessages(newMessages).withEmptyResponseRetries(0).withMailUsedThisTurn(false)
                // Block 3：新用户/系统 turn 起点——loop 纪元 +1
                .withNextLoopTurn,
              replyTo,
              // D2：真实用户 WS 输入恒带 clientMessageId → UserWake（冻结时段也
              // 放行，用户输入即唤醒）；系统注入（Mail/continue 等 None）→ Gated。
              if clientMessageId.isDefined then DispatchCause.UserWake else DispatchCause.Gated
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
          _ <- emitInjectedUserEvent(state.wsSend, state.sessionId, input, "skill", None)
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
            .invalidateSystemStableCache
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
        val threshold = CompactThreshold.threshold(window)
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
                state.wsSend,
                state.sessionId,
                payload,
                s,
                Some(eventType),
                agentName,
                waitingForBatch = waiting
              )
            case None => IO.unit
          )
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
            result <- pipeLlmCall(
              agentDef,
              resources,
              depth,
              parentRef,
              state
                .withMessages(
                  state.messages :+ Message(MessageRole.User, Left(injectionText), source = visSource.orElse(Some("external")))
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
          for
            _ <- sessionBusyIO
            _ <- receiveVisibility(waiting = false)
            _ <- touchBarrierSnapshot(resources, state.sessionId, 0, 0)
            result <- pipeLlmCall(
              agentDef,
              resources,
              depth,
              parentRef,
              state.copy(execution =
                state.execution.copy(
                  outstandingSubagentResults = 0,
                  pendingEvents = Nil
                )
              ).withMessages(state.messages :+ batchMessage)
                .withNextLoopTurn, // Block 3：批次汇聚唤醒 = 新 turn
              None
            )
          yield result
          end for
        else
          // Single immediate result (no batch in flight) or non-subagent event.
          for
            _ <- sessionBusyIO
            _ <- receiveVisibility(waiting = false)
            _ <- touchBarrierSnapshot(resources, state.sessionId, newOutstanding, 0)
            result <- pipeLlmCall(
              agentDef,
              resources,
              depth,
              parentRef,
              state
                .withMessages(
                  // Reminder refactor (2026-08-20): always carry a source marker —
                  // external-event turns are system events, not real user input
                  // (fromUser = Message.source.isEmpty).
                  state.messages :+ Message(MessageRole.User, Left(injectionText), source = visSource.orElse(Some("external")))
                )
                // #25: apply the barrier decrement. Pre-fix this branch
                // computed newOutstanding and dropped it — a single-result
                // batch (spawn 1, receive 1, held empty) left the counter
                // stuck at 1 forever. Invisible while Completed fired
                // unconditionally at turn end; once the completion debt is
                // parked (#25) a stuck counter means the debt is NEVER paid
                // and the waiting supervisor/bridge hangs.
                .withOutstandingSubagentResults(newOutstanding)
                .withNextLoopTurn, // Block 3：外部事件唤醒 = 新 turn
              None
            )
          yield result
          end for
        end if

      case AgentCommand.UpdateGitBranch(branch) =>
        IO.pure(idle(agentDef, resources, depth, parentRef, state.withGitBranch(branch)))

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

      case AgentCommand.SetSafetyMode(mode) =>
        // P2: write the root session's permission-policy bucket (dynamic
        // inheritance). The per-agent safetyMode copy is kept in sync for P3
        // cleanup; the decision point reads the policy, not this field.
        val policy = PermissionPolicy(safetyMode = mode)
        resources.permissionPolicies
          .update(_ + (state.session.rootSessionId -> policy)) *>
          IO.pure(
            idle(
              agentDef,
              resources,
              depth,
              parentRef,
              state.withSafetyMode(nebflow.core.SafetyMode.toString(mode))
            )
          )

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
            IO.pure(idle(agentDef, resources, depth, parentRef, state.copy(execution = exec)))
          case _ => IO.pure(idle(agentDef, resources, depth, parentRef, state))
        }

      // Immediate input arriving in idle (turn already finished) — treat as normal UserInput
      case AgentCommand.ImmediateInput(text, blocks, source, eventType, sender, senderTeam, delivery) =>
        for _ <- ctx.self ! AgentCommand.UserInput(text, None, None, blocks, 0, source, sender, senderTeam, delivery, eventType)
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
                  }.map(_ => idle(agentDef, resources, depth, parentRef, state))
                case _ => IO.pure(idle(agentDef, resources, depth, parentRef, state))
        yield result

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
                CompactThreshold.thresholdRatio(updatedState.contextWindow),
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
                    IO(
                      NebflowLogger
                        .forName("nebflow.agent")
                        .warn(s"usage record failed: ${e.getMessage}")
                    )
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
            case None      => state
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
          if retryable && state.llmFailRetries < OverloadRetryMax then
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
              if isStreamInactivity then math.max(LlmFailBackoffBaseMs * (1L << (retryState.llmFailRetries - 1)), effectiveInactivityBackoffMinMs)
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
              case _ => errCls.permanence == ErrorPermanence.Transient
            )
            if shouldErrorFreeze then
              val errReason = error match
                case _: AllProvidersDownTimeout => FreezeReason.ProviderDown
                case _ =>
                  errCls.reason match
                    case FailoverReason.ConnectionReset => FreezeReason.Network
                    case _                              => FreezeReason.LlmTransient
              val jitter = java.util.concurrent.ThreadLocalRandom.current().nextLong(0, 500)
              val resumeInMs = errReason match
                case FreezeReason.Network =>
                  NetworkErrorFreezeBackoffMs + java.util.concurrent.ThreadLocalRandom.current().nextLong(0, 20_000L)
                case FreezeReason.ProviderDown => ProviderDownFreezeBackoffMs + jitter
                case _ =>
                  // LlmTransient：沿用 overload 退避（预算耗尽时 llmFailRetries ≥ Max → ≥60s）
                  math.min(
                    math.max(LlmFailBackoffBaseMs * (1L << math.max(state.llmFailRetries, 1) - 1), OverloadBackoffMinMs),
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
                      payload =
                        s""""${agentDef.name}" (team member) hit a fatal LLM failure: """ +
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
                    d.complete(Left("Compaction abandoned: LLM failed during compaction")).void
                      .handleErrorWith(_ => IO.unit)
                  )
                // F1 (2026-08-29, loop-detected report §4.1/§6): the fatal path
                // never wrote the registry back — the last entry stayed
                // status=Processing (written by the crashed round's loop-counter
                // touch), so TaskStuckWatcher flagged a zombie every 30s until a
                // human restarted the actor. Every other terminal path
                // (turn-done :2859 / Interrupt :3638 / Stop :3650 / ResetSession
                // :3927 / ErrorFrozen :3474) writes the registry; this was the
                // last one missing. Idle = "no active turn" (the behavior state
                // keeps the Error detail; the watcher only flags Processing).
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
        // Inject ONE queued immediate input alongside tool results (serial processing).
        // While compaction is in progress, keep inputs queued — injecting mid-compaction
        // risks the input being lost in the summary. CompactionComplete drains them.
        val (immInputOpt, remainingImmInputs) =
          TurnBoundaryDrains.drainHead(state.execution.pendingImmediateInputs, state.pendingCompaction.isDefined)
        val immediateMessages = immInputOpt match
          case Some(imm) =>
            logAgentEvent(
              agentDef,
              depth,
              state.sessionId,
              state.sessionName,
              "immediate-input-injected-at-tools-complete",
              s"text=${imm.text.take(60)} remaining=${remainingImmInputs.size}"
            )
            List((imm.blocks match
              case Some(blocks) if blocks.nonEmpty => Message(MessageRole.User, Right(blocks))
              case _ => Message(MessageRole.User, Left(imm.text))
            ).copy(source = imm.source))
          case None => Nil
        val immEventIO = immInputOpt match
          case Some(imm) if imm.source.isDefined =>
            emitInjectedUserEvent(
              state.wsSend,
              state.sessionId,
              imm.text,
              imm.source.get,
              imm.eventType,
              imm.sender,
              imm.senderTeam,
              imm.delivery
            )
          case _ => IO.unit
        // Block 3 循环检测器 L0（supervision trio §D3）：LoopGuard Warn 提醒以
        // user system-reminder 追加在工具结果之后（同系统 reminder 形态）
        // ——零成本给模型自纠机会；不阻断轮次。
        val loopReminderMsgs = tc.loopReminder match
          case Some(rem) => List(Message(MessageRole.User, Left(rem)))
          case None      => Nil
        val newMessages =
          baseMessages ++ List(assistantMsg, resultMsg) ++ imageMsgs ++ eventMessages ++ immediateMessages ++ loopReminderMsgs
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
          case None      => updatedState0
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
                  payload =
                    s""""${agentDef.name}" loop detected and frozen: ${detail.take(200)}""" +
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
              _ <- immEventIO
              _ <- touchBarrierSnapshot(resources, state.sessionId, newOutstanding, remainingEvents.size)
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
        yield
          val interruptedState = state.resetForInterrupt.withPendingCompaction(None)
          idle(agentDef, resources, depth, parentRef, interruptedState)

      // --- Retry: cancel current work, re-dispatch from last checkpoint ---
      case AgentCommand.Retry(reason) =>
        logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "retry", s"reason=$reason")
        ctx.cancelCurrentTurn() *> (state.lastDispatch match
          case Some(LastDispatch(false, _)) =>
            // Re-dispatch LLM call with same messages. 6-arg call goes through
            // the AgentActor shadow (freeze gate) — retry must not bypass the
            // work schedule (spec §5.4: 冻结时段不重试，出冻结段后恢复即重试).
            pipeLlmCall(agentDef, resources, depth, parentRef, state, None)
          case Some(LastDispatch(true, Some(cr))) =>
            // Re-dispatch tool execution with same LLM result
            pipeToolExecutions(agentDef, resources, depth, parentRef, state, cr, None)
          case _ =>
            // No checkpoint — go to idle
            markTeamIdle(agentDef, state.sessionId) *>
              IO.pure(idle(agentDef, resources, depth, parentRef, state.resetForInterrupt)))

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
        state.readTracker.fold(IO.unit)(t => t.clear()) *>
          IO.pure(processing(agentDef, resources, depth, parentRef, state, pending))

      case AgentCommand.ResetSession =>
        for

          _ <- state.readTracker.fold(IO.unit)(t => t.clear())
          _ <- emitStream(state.wsSend, AgentStreamEvent.Interrupted, isSubagent = depth > 0, state.sessionId)
          // R2 closure (wait-timeout-fix): ResetSession discards a turn that
          // may be parked on a pending AskUser/permission wait — un-mark
          // WaitingForUser (mirror of the Interrupt handler), the registry row
          // must never keep a waiting status for a turn that no longer exists.
          _ <- touchRegistryActivity(resources, state.sessionId, AgentStatus.Idle)
        yield
          val resetState = state
            .withMessages(Nil)
            .withLatestUsage(None)
            .withPendingCompaction(None)
            .withCompactionFailures(0)
            .withLastCompactionFailureAt(0L)
            .withRecentMessageIds(Nil)
            .invalidateSystemStableCache
            .resetToIdle(Nil)
          idle(agentDef, resources, depth, parentRef, resetState)

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
                val drainedState = stateWithInstruction.copy(execution =
                  drain.exec.copy(
                    messages = stateWithInstruction.messages ++ drain.appended
                  )
                ).withNextLoopTurn // Block 3：压缩后注入 = 新 turn
                for
                  _ <- ctx.forkTurn(compactEmitIO)
                  _ <- persistDrainIO
                  _ <- logPostCompactInjection(agentDef, depth, state, drain)
                  _ <- emitInjectedBubbles(state, drain)
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
                        IO(
                          NebflowLogger.forName("nebflow.agent").warn(s"Persist after compact failed: ${e.getMessage}")
                        )
                      )
                  )
                  _ <- persistDrainIO
                  result <- if drain.appended.nonEmpty then
                    for
                      _ <- logPostCompactInjection(agentDef, depth, state, drain)
                      _ <- emitInjectedBubbles(state, drain)
                      res <- pipeLlmCall(
                        agentDef,
                        resources,
                        depth,
                        parentRef,
                        compactedState.copy(execution =
                          drain.exec.copy(messages = compactedState.messages ++ drain.appended)
                        ).withNextLoopTurn, // Block 3：压缩后注入 = 新 turn
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
        // Sub-agent barrier: a Delegate/SubTask result (completed or failed)
        // satisfies one outstanding slot of the parallel batch. The drain at the
        // turn boundary only releases subtask/delegate events once the counter
        // hits 0, so a batch completing mid-turn is still injected together.
        val updatedOutstanding =
          if TurnBoundaryDrains.isSubagentResult(event) then
            math.max(0, state.execution.outstandingSubagentResults - 1)
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
            emitInjectedUserEvent(state.wsSend, state.sessionId, payload, s, Some(eventType), agentName)
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
      case AgentCommand.AskUser(requestId, items, replyToOpt) =>
        // P2: every agent (root or sub-agent) sends the question straight to
        // the InteractionHub — no ForwardAskUser relay chain. The hub holds
        // replyTo, renders the question in the Nebula window (sessionId =
        // rootSessionId + source attribution) and routes the answers back.
        val srcAgent = agentDef.name
        val srcSession = state.sessionId.getOrElse("")
        val rootSid =
          Option(state.session.rootSessionId).filter(_.nonEmpty).getOrElse(state.sessionId.getOrElse(""))
        val payload = buildAskUserJson(Some(rootSid), srcAgent, items, Some(srcAgent), Some(srcSession))
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
            touchRegistryActivity(resources, state.sessionId, AgentStatus.WaitingForUser) *>
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
              )).void
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
        IO.pure(processing(agentDef, resources, depth, parentRef, state.withPendingPermission(Some(deferred)), pending))

      // --- Bypass toggled while processing ---
      case AgentCommand.SetSafetyMode(mode) =>
        val policy = PermissionPolicy(safetyMode = mode)
        resources.permissionPolicies
          .update(_ + (state.session.rootSessionId -> policy)) *>
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
  private def handleMissingMail(
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
  /** Restart state by level (#13). Soft cancels current work keeping
    * messages; Rollback additionally truncates the last tool pair; Full
    * rebuilds message state from persisted history (documented "(future)
    * reset to empty, reload from persisted history" — implemented per issue
    * #13; it was silently aliased to Soft). Prune stays a Soft alias until
    * context-prune exists. Disk-load failure degrades to Soft (fail-safe). */
  private def restartStateFor(
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
          resources.sessionStore.loadMessagesForSession(sid)
            .map(msgs => base.copy(execution = base.execution.copy(messages = msgs)))
            .handleErrorWith { e =>
              logger.warn(
                s"Full restart: history reload failed for $sid (${e.getMessage}) — degrading to Soft"
              ) *> IO.pure(base)
            }
      case _ => IO.pure(base)
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
    // Single-stage compaction (2026-08-31 redesign): the former Save stage is
    // gone — compaction only compresses. Every pending compaction is a
    // Compact turn: tools disabled (pipeLlmCall sets tools=Some(Nil)),
    // text-only summary required.
    if state.pendingCompaction.isDefined && result.toolCalls.isEmpty && result.text.nonEmpty then
      handleCompactResponse(agentDef, resources, depth, parentRef, state, result.text)
    else if state.pendingCompaction.exists(_.phase == CompactionPhase.Compact) && result.toolCalls.isEmpty && result.text.isEmpty then
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
          state
            .withPendingCompaction(None)
            .withCompactionFailures(state.compactionFailures + 1)
            .withLastCompactionFailureAt(now),
          job.replyDeferred
            .fold(IO.unit)(d =>
              d.complete(Left("Compaction abandoned: turn ended before the compact phase")).void
                .handleErrorWith(_ => IO.unit)
            )
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
        compactThreshold = Some(CompactThreshold.thresholdRatio(state.contextWindow)),
        outputTokens = state.latestUsage.flatMap(u => Option.when(u.outputTokens > 0)(u.outputTokens))
      )
      val emitDoneIO =
        if isSubagent then
          // Synchronous emit to prevent markIdle/markBusy race condition
          emitStreamIO(state.wsSend, doneEvent, isSubagent = true, state.sessionId)
            .handleErrorWith(e =>
              IO(
                NebflowLogger.forName("nebflow.agent").warn(s"finishTurn: emitDone (sync) failed: ${e.getMessage}")
              ).void
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
      val updatedState = state.copy(execution =
        ExecutionContext
          .idle(messagesWithPending, state.execution.turnIdx)
          .copy(pendingEvents = remainingEvents, pendingImmediateInputs = state.execution.pendingImmediateInputs,
                pendingMailQueueCount = state.execution.pendingMailQueueCount,
                pendingUserInputs = state.execution.pendingUserInputs,
                outstandingSubagentResults = state.execution.outstandingSubagentResults,
                // #25: hold/replay the completion debt across this injection.
                owedCompletion = owedAfter)
      ).withNextLoopTurn // Block 3：turn 末事件注入 = 新 turn
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
      // Inject ONE queued immediate input (serial processing).
      val immInput = state.execution.pendingImmediateInputs.head
      val remainingInputs = state.execution.pendingImmediateInputs.tail
      logAgentEvent(
        agentDef,
        depth,
        state.sessionId,
        state.sessionName,
        "immediate-input-injected-at-turn-end",
        s"text=${immInput.text.take(60)} remaining=${remainingInputs.size}"
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
      val immMessage = (immInput.blocks match
        case Some(blocks) if blocks.nonEmpty => Message(MessageRole.User, Right(blocks))
        case _ => Message(MessageRole.User, Left(immInput.text))
      ).copy(source = immInput.source)
      val messagesWithImmediate = newMessages ++ List(immMessage)
      val updatedState = state
        .copy(execution =
          ExecutionContext
            .idle(messagesWithImmediate, state.execution.turnIdx)
            .copy(pendingImmediateInputs = remainingInputs,
                  pendingMailQueueCount = state.execution.pendingMailQueueCount,
                  pendingUserInputs = state.execution.pendingUserInputs,
                  // Sub-agent barrier: held subtask/delegate results and the
                  // outstanding count survive the turn boundary.
                  pendingEvents = state.execution.pendingEvents,
                  outstandingSubagentResults = state.execution.outstandingSubagentResults,
                  // #25: hold/replay the completion debt across this injection.
                  owedCompletion = owedAfter)
        )
        .withNextLoopTurn // Block 3：turn 末注入 = 新 turn
      for
        _ <- roundCompleteIO
        // F2: keep the disk snapshot in sync after the drain (injected input gone).
        _ <- persistQueues(state.sessionId, updatedState.execution)
        _ <-
          if !isSubagent then state.sessionId.fold(IO.unit)(sid => emitSessionBusy(state.wsSend, sid, busy = true))
          else IO.unit
        _ <- immInput.source match
          case Some(src) =>
            emitInjectedUserEvent(
              state.wsSend,
              state.sessionId,
              immInput.text,
              src,
              immInput.eventType,
              immInput.sender,
              immInput.senderTeam
            )
          case None => IO.unit
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
          else completionTargets.traverse_(_ ! AgentEvent.Completed(state.sessionId.getOrElse(""), messagesWithImmediate))
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
                    updatedState = state.copy(execution =
                      ExecutionContext
                        .idle(messagesWithQueue, state.execution.turnIdx)
                        .copy(pendingMailQueueCount = state.execution.pendingMailQueueCount - 1,
                              pendingUserInputs = state.execution.pendingUserInputs,
                              // Sub-agent barrier: held results survive the turn boundary.
                              pendingEvents = state.execution.pendingEvents,
                              outstandingSubagentResults = state.execution.outstandingSubagentResults,
                              // #25: hold/replay the completion debt across this injection.
                              owedCompletion = owedAfter)
                    ).withNextLoopTurn // Block 3：Mail 队列投递 = 新 turn
                    _ <- nebflow.core.flow.MailQueueStore.removeHead(sid)
                    _ <-
                      if !isSubagent then emitSessionBusy(state.wsSend, sid, busy = true)
                      else IO.unit
                    _ <- emitInjectedUserEvent(
                      state.wsSend, state.sessionId, item.message,
                      "mail-queue", Some("queue"), Some(item.from), None, Some("queue")
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
                      else completionTargets.traverse_(_ ! AgentEvent.Completed(state.sessionId.getOrElse(""), messagesWithQueue))
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
                          agentDef, resources, depth, parentRef,
                          state.copy(execution = state.execution.copy(
                            pendingMailQueueCount = state.execution.pendingMailQueueCount - 1)),
                          replyTo, newMessages, text, model, thinking, thinkingSignature, textStreamed, isSubagent
                        )
                    case true => deliver
                  }
                }
              case None =>
                // Count > 0 but disk queue empty (items cancelled while busy) — reset
                // count and re-enter finishTurnCont which will hit the idle branch.
                finishTurnCont(
                  agentDef, resources, depth, parentRef,
                  state.copy(execution = state.execution.copy(pendingMailQueueCount = 0)),
                  replyTo, newMessages, text, model, thinking, thinkingSignature, textStreamed, isSubagent
                )
      yield result
    else returnToIdle
    end if
  end finishTurnCont

  // ============================================================
  // Pipe wrappers
  // ============================================================

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
  private def markTeamBusy(agentDef: AgentDef, sid: Option[String]): IO[Unit] =
    if agentDef.category == "team" then
      sid.fold(IO.unit)(s => TeamSessionRegistry.markBusy(s).handleErrorWith(_ => IO.unit))
    else IO.unit

  private def markTeamIdle(agentDef: AgentDef, sid: Option[String]): IO[Unit] =
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
  private def fullyIdle(
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
  private def emitDequeuedWs(wsSend: Json => IO[Unit], sessionId: String, itemId: String): IO[Unit] =
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

  private def pipeLlmCall(
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
            super.pipeLlmCall(
              agentDef,
              resources,
              depth,
              parentRef,
              state,
              replyTo,
              (ad, r, d, p, s) => processing(ad, r, d, p, s)
            )
        }
      }

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
  // Frozen state (freeze-schedule spec 6c / D3)
  // ============================================================

  /** v2 升级链通知（§5.1）：父是 agent → ExternalEvent("error-escalated") 注入
    * 父上下文（排队不唤醒，T_escalate 兜底）；父是用户（无 parentRef）→ WS
    * errorEscalated 事件 + UI 升级卡片。通知不消耗本 agent LLM token（D2）。
    * 幂等：由 enterFrozen 的 escalation.isEmpty 守卫保证每轮升级链只通知一次。 */
  private def notifyEscalation(
    agentDef: AgentDef,
    depth: Int,
    state: AgentState,
    parentRef: Option[ActorRef[AgentCommand]],
    reason: FreezeReason,
    count: Int,
    esc: EscalationInfo
  ): IO[Unit] =
    val sid = state.sessionId.getOrElse("")
    parentRef match
      case Some(parent) =>
        parent ! AgentCommand.ExternalEvent(
          source = "team",
          eventType = "error-escalated",
          payload = s""""${agentDef.name}" auto-recovery failed $count times (reason=${reasonStr(reason)}); awaiting parent decision (restart/cancel/wait)""",
          metadata = JsonObject(
            "reason" -> reasonStr(reason).asJson,
            "retryCount" -> count.asJson,
            "level" -> esc.level.asJson,
            "escalateAfterMs" -> nebflow.shared.Defaults.ErrorEscalateAfterMs.asJson,
            "failedSessionId" -> sid.asJson,
            "agentName" -> agentDef.name.asJson
          ),
          correlationId = Some(sid).filter(_.nonEmpty)
        )
      case None =>
        // 无父（root/standalone）→ 用户级：WS errorEscalated 事件（前端升级卡片）
        state
          .wsSend(
            Json.obj(
              "type" -> "errorEscalated".asJson,
              "sessionId" -> sid.asJson,
              "reason" -> reasonStr(reason).asJson,
              "retryCount" -> count.asJson,
              "level" -> esc.level.asJson,
              "escalateAt" -> esc.escalateAt.asJson
            )
          )
          .handleErrorWith(_ => IO.unit)

  /** 反查 ActorRef → registry 中对应 sessionId（升级链「父存活」判定）。
    * registry 小（几十条），O(n) 可接受。 */
  private def sessionIdOfRef(
    resources: SharedResources,
    ref: Option[ActorRef[AgentCommand]]
  ): IO[Option[String]] =
    ref match
      case None => IO.pure(None)
      case Some(r) =>
        resources.agentRegistry.get.map(_.collectFirst { case (sid, rec) if rec.ref == r => sid })

  /** registry 的 escalation + frozenReason 快照（FreezeScheduler.scan / WS
    * parentRestart 只读侧；actor 层为权威）。 */
  private def updateRegistryEscalation(
    resources: SharedResources,
    sessionId: Option[String],
    esc: Option[EscalationInfo]
  ): IO[Unit] =
    sessionId.fold(IO.unit) { sid =>
      resources.agentRegistry.modify { m =>
        m.get(sid) match
          case Some(rec) =>
            (m.updated(sid, rec.copy(escalation = esc)), ())
          case None => (m, ())
      }
    }

  /** registry 的 frozenReason 快照（WS parentRestart 区分时间表/错误族冻结）。
    * 冻结进入时写 Some(reasonStr)，恢复/唤醒/中断时清 None。 */
  private def updateRegistryFrozenReason(
    resources: SharedResources,
    sessionId: Option[String],
    reason: Option[String]
  ): IO[Unit] =
    sessionId.fold(IO.unit) { sid =>
      resources.agentRegistry.modify { m =>
        m.get(sid) match
          case Some(rec) => (m.updated(sid, rec.copy(frozenReason = reason)), ())
          case None => (m, ())
      }
    }

  /** 当前冻结窗口态的下一翻转点（现象 2 契约补全 2026-08-30）：恢复/唤醒路径
    * 发 Resumed 事件时附带——工作态 = 下一冻结开始时刻（前端展示「下一段 HH:mm
    * 再冻结」），配置关闭/全天无翻转 = None。错误族恢复（与时间表无关）也带当前
    * 窗口态，让前端输入栏状态机始终有权威依据。 */
  private def currentNextChange(resources: SharedResources): IO[Option[Long]] =
    for
      cfg <- resources.freezeScheduleRef.get
      skipUntil <- resources.freezeSkipUntilRef.get
    yield nebflow.core.schedule.FreezeSchedule
      .evalWithSkip(cfg, skipUntil, System.currentTimeMillis())
      .nextChangeAt

  /** v2 错误冻结转入辅助（§3.3/§6.1 步骤 3）：transient 类 LLM 失败 → 立即
    * persist（复用 fatal 路径的 save 模式）→ enterErrorFrozen——冻结期间零 LLM
    * 调用（D2），退避到期 CheckFreezeGate 续跑（重新过 gate，天然闭环）。 */
  private def enterErrorFrozen(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    replyTo: Option[ActorRef[AgentEvent]],
    reason: FreezeReason,
    resumeInMs: Long,
    error: Throwable
  )(using ctx: ActorContext[AgentCommand]): IO[Behavior[AgentCommand]] =
    for
      _ <- state.sessionId.fold(IO.unit) { sid =>
        ctx.forkTurn(
          (resources.sessionStore.saveMessagesForSession(sid, state.messages) *>
            resources.sessionStore.flushIndex)
            .handleErrorWith(e =>
              NebflowLogger.forName("nebflow.agent").warn(s"Save failed session: ${e.getMessage}")
            )
        )
      }
      _ <- IO {
        logAgentEvent(
          agentDef,
          depth,
          state.sessionId,
          state.sessionName,
          "error-freeze",
          s"reason=${reasonStr(reason)} err=${Option(error.getMessage).getOrElse("unknown").take(80)} resumeIn=${resumeInMs}ms"
        )
      }
      result <- enterFrozen(
        agentDef,
        resources,
        depth,
        parentRef,
        state,
        replyTo,
        Some(System.currentTimeMillis() + resumeInMs),
        reason,
        Some(Option(error.getMessage).getOrElse("unknown").take(200))
      )
    yield result

  /** gate 拦截后的转入辅助：Frozen 事件 + registry 标记 + 进入 frozen behavior。
    * v2（冻结式错误恢复）：reason 泛化——Schedule=时间表（默认，旧调用点零改动）；
    * 错误族（LlmTransient/Network/ProviderDown/RestartRecovery）附加连续计数与
    * 升级链判定（同 reason 连续 ≥3 → 进入升级链，通知父/用户，不再直接 fatal）。 */
  private def enterFrozen(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    replyTo: Option[ActorRef[AgentEvent]],
    resumeAt: Option[Long],
    reason: FreezeReason = FreezeReason.Schedule,
    detail: Option[String] = None
  )(using ctx: ActorContext[AgentCommand]): IO[Behavior[AgentCommand]] =
    val isErrorFamily = reason != FreezeReason.Schedule
    // 连续计数：Schedule 不参与；错误族 reason 相同则 +1（跨恢复累计），变化重置为 1
    val count =
      if isErrorFamily then
        if state.lastErrorFreezeReason.contains(reason) then state.errorFreezeCount + 1 else 1
      else 0
    // 升级链进入判定（§5.1）：连续 ≥3 且尚未在升级链中（幂等——不重复通知）
    val (escalation, escalateNotify): (Option[EscalationInfo], IO[Unit]) =
      if isErrorFamily && count >= ErrorFreezeEscalationThreshold && state.escalation.isEmpty then
        val now = System.currentTimeMillis()
        val esc = EscalationInfo(
          level = 1,
          escalateAt = now + nebflow.shared.Defaults.ErrorEscalateAfterMs,
          awaitedParentSessionId = ""
        )
        (Some(esc), notifyEscalation(agentDef, depth, state, parentRef, reason, count, esc))
      else (state.escalation, IO.unit)
    escalateNotify *>
      emitStream(
        state.wsSend,
        AgentStreamEvent.Frozen(resumeAt, reason, detail, count, escalation),
        isSubagent = depth > 0,
        state.sessionId
      ) *>
      touchRegistryActivity(resources, state.sessionId, AgentStatus.Frozen) *>
      updateRegistryEscalation(resources, state.sessionId, escalation) *>
      updateRegistryFrozenReason(resources, state.sessionId, Some(reasonStr(reason))) *>
      // 计数/escalation 写回 state（frozen 闭包参数不持久——LlmFailed 从 state
      // 读 errorFreezeCount 判定「同 reason 连续」；不写回则每轮都从 0 计数，
      // 升级链永不触发）。Schedule 冻结不参与计数（原样保留 state）。
      IO.pure {
        val frozenState =
          if isErrorFamily then state.withErrorFreezeCount(count, reason).withEscalation(escalation)
          else state
        frozen(agentDef, resources, depth, parentRef, frozenState, replyTo, resumeAt, reason, count, escalation)
      }

  /**
   * frozen behavior：冻结中的 agent——持有完整 state（工具结果已组装并持久化，
   * F1）+ replyTo，等待出冻结段自动恢复或用户唤醒。
   *
   * 恢复驱动只有两个：CheckFreezeGate（FreezeScheduler 30s 轮询 / 配置热更即时
   * scan）重评估时间表；UserInput(clientMessageId.isDefined) 用户唤醒。系统注入
   * 一律排队不唤醒（零 token 铁律，D2）——BackoffSupervisor 崩溃重启注入的
   * "continue" 在冻结时段同样不唤醒，出冻结段后续跑（spec §5.3）。
   *
   * 独立 behavior 而非 processing 加 flag（D3）：天然隔离 processing 的 20+ 交互
   * case（重复 ToolsComplete 双 dispatch、stale LlmComplete 等）；catch-all 留在
   * frozen 态——stale 消息丢弃，但冻结前已持久化的状态不丢。
   */
  private def frozen(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    replyTo: Option[ActorRef[AgentEvent]],
    resumeAt: Option[Long],
    reason: FreezeReason = FreezeReason.Schedule,
    retryCount: Int = 0,
    escalation: Option[EscalationInfo] = None
  )(using ctx: ActorContext[AgentCommand]): Behavior[AgentCommand] =
    Behaviors.receiveMessage:

      case AgentCommand.CheckFreezeGate =>
        // 唯一自动恢复驱动：重评估（支持运行中改配置/时钟漂移）。仍冻结 →
        // 原地留任（静默更新 resumeAt——不重发 Frozen 事件，避免 30s 轮询刷屏）；
        // 冻结时段已过（段外=工作时段）→ Resumed + 恢复挂起的 dispatch
        // （cause=Gated，但已不在冻结段 → 通过）。
        val now = System.currentTimeMillis()
        reason match
          case FreezeReason.Schedule =>
            // 时间表冻结：现有 eval 语义（#337 黑名单——段外=工作时段）+ 2026-08-25
            // 用户消息全局跳过（skipUntil 未到期 → 视为段外，立即恢复）。
            for
              cfg <- resources.freezeScheduleRef.get
              skipUntil <- resources.freezeSkipUntilRef.get
              window = nebflow.core.schedule.FreezeSchedule.evalWithSkip(cfg, skipUntil, now)
              result <-
                if !window.frozen then
                  logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "freeze-resume", "reason=outside-freeze-segment")
                  emitStream(state.wsSend, AgentStreamEvent.Resumed(window.nextChangeAt), isSubagent = depth > 0, state.sessionId) *> updateRegistryFrozenReason(resources, state.sessionId, None) *>
                    pipeLlmCall(agentDef, resources, depth, parentRef, state, replyTo)
                else
                  IO.pure(frozen(agentDef, resources, depth, parentRef, state, replyTo, window.nextChangeAt, reason, retryCount, escalation))
            yield result
          case _ =>
            // 错误族（LlmTransient/Network/ProviderDown/RestartRecovery）：退避到期
            // （resumeAt = 进入时计算的退避时刻）→ 续跑——重新过 gate（pipeLlmCall），
            // 条件未消除再入冻结，天然闭环；ProviderDown 的候选 health 检查 P0 用
            // 退避到期 + 续跑重试兜底（R1 轮询），P1 接 HealthMonitor markUp 事件。
            val shouldResume = resumeAt.forall(now >= _)
            if shouldResume then
              logAgentEvent(
                agentDef,
                depth,
                state.sessionId,
                state.sessionName,
                "freeze-resume",
                s"reason=${reasonStr(reason)} backoff-elapsed"
              )
              currentNextChange(resources).flatMap { nextChange =>
                emitStream(state.wsSend, AgentStreamEvent.Resumed(nextChange), isSubagent = depth > 0, state.sessionId) *> updateRegistryFrozenReason(resources, state.sessionId, None) *>
                  pipeLlmCall(agentDef, resources, depth, parentRef, state, replyTo)
              }
            else
              IO.pure(frozen(agentDef, resources, depth, parentRef, state, replyTo, resumeAt, reason, retryCount, escalation))

      case AgentCommand.UserInput(text, replyTo2, clientMessageId, blocks, chatWidth, source, sender, senderTeam, delivery, eventType) =>
        if clientMessageId.isDefined then
          // ★ 用户唤醒（B5）：注入用户消息到冻结中的上下文，立即 dispatch——
          // 冻结前组装好的工具结果 + 用户新指令同轮喂给 LLM。dedup 防止 WS
          // 重发导致同一条用户消息注入两次（v1.1）。
          val (isDuplicate, dedupedState) = checkDuplicate(clientMessageId, state)
          if isDuplicate then
            logger.info(s"[frozen] Dropping duplicate wake message clientMessageId=${clientMessageId.getOrElse("")}")
            IO.pure(frozen(agentDef, resources, depth, parentRef, state, replyTo, resumeAt, reason, retryCount, escalation))
          else
            val userMsg = blocks.filter(_.nonEmpty) match
              case Some(bl) => Message(MessageRole.User, Right(bl))
              case None => Message(MessageRole.User, Left(text))
            val stateWithWidth =
              if chatWidth > 0 then dedupedState.copy(session = dedupedState.session.copy(chatWidth = chatWidth))
              else dedupedState
            val wakeState = stateWithWidth
              .withMessages(stateWithWidth.messages :+ userMsg)
              .withEmptyResponseRetries(0)
              .withMailUsedThisTurn(false)
              .withNextLoopTurn // Block 3：冻结唤醒 = 新 turn
            logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "freeze-wake", s"text=${text.take(60)}")
            currentNextChange(resources).flatMap { nextChange =>
              emitStream(state.wsSend, AgentStreamEvent.Resumed(nextChange), isSubagent = depth > 0, state.sessionId) *> updateRegistryFrozenReason(resources, state.sessionId, None) *>
                pipeLlmCall(agentDef, resources, depth, parentRef, wakeState, replyTo2, DispatchCause.UserWake)
            }
        else
          // 系统注入（Mail/Delegate/BackoffSupervisor continue）：排队不唤醒——
          // 恢复后的 turn 结束时由 finishTurnCont drain（head 重发，idle 全量处理）。
          logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "freeze-queue-input", s"textLen=${text.length}")
          val queued = state.copy(execution =
            state.execution.copy(
              pendingUserInputs = state.execution.pendingUserInputs :+ AgentCommand.UserInput(
                text, replyTo2, clientMessageId, blocks, chatWidth, source, sender, senderTeam, delivery, eventType
              )
            )
          )
          IO.pure(frozen(agentDef, resources, depth, parentRef, queued, replyTo, resumeAt, reason, retryCount, escalation))

      case AgentCommand.AskQuestion(question, _) =>
        // 用户动作（D2）：唤醒——ask 轮本身也是 gate 豁免路径（askMode.isDefined）。
        logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "freeze-wake", s"ask=${question.take(60)}")
        val askReminder = AskService.buildAskReminder(question)
        val askState = state
          .withMessages(state.messages :+ askReminder)
          .withAskMode(Some(question))
          .withStatus(AgentStatus.Processing)
          .withNextLoopTurn // Block 3：冻结唤醒 = 新 turn
        currentNextChange(resources).flatMap { nextChange =>
          emitStream(state.wsSend, AgentStreamEvent.Resumed(nextChange), isSubagent = depth > 0, state.sessionId) *> updateRegistryFrozenReason(resources, state.sessionId, None) *>
            pipeLlmCall(agentDef, resources, depth, parentRef, askState, None, DispatchCause.UserWake)
        }

      case AgentCommand.SkillActivate(skillName, input, _, skillContent, _) =>
        // 用户动作（D2）：唤醒（镜像 idle 的 SkillActivate handler）。
        logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "freeze-wake", s"skill=$skillName")
        val combinedText = s"<skill name=\"$skillName\">\n$skillContent\n</skill>\n\n$input"
        val processingState = state
          .withMessages(
            state.messages :+ Message(MessageRole.User, Left(combinedText), source = Some("skill"))
          )
          .withStatus(AgentStatus.Processing)
          .withNextLoopTurn // Block 3：冻结唤醒 = 新 turn
        for
          _ <- currentNextChange(resources).flatMap { nextChange =>
            emitStream(state.wsSend, AgentStreamEvent.Resumed(nextChange), isSubagent = depth > 0, state.sessionId) *> updateRegistryFrozenReason(resources, state.sessionId, None)
          }
          _ <- emitInjectedUserEvent(state.wsSend, state.sessionId, input, "skill", None)
          result <- pipeLlmCall(agentDef, resources, depth, parentRef, processingState, None, DispatchCause.UserWake)
        yield result

      case AgentCommand.Interrupt() =>
        // D10 放弃续跑：与 processing 的 Interrupt 同语义（cancelCurrentTurn +
        // Interrupted 事件 + idle，历史含工具结果保留）。
        logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "interrupt", "reason=user-during-frozen")
        for
          _ <- ctx.cancelCurrentTurn()

          _ <- emitStream(state.wsSend, AgentStreamEvent.Interrupted, isSubagent = depth > 0, state.sessionId)

          _ <- state.pendingCompaction
            .flatMap(_.replyDeferred)
            .traverse_(d => d.complete(Left("Interrupted by user")).void.handleErrorWith(_ => IO.unit))
          // Back to idle without resuming — clear the team busy mark so a
          // frozen-then-interrupted team agent isn't stuck "running".
          _ <- markTeamIdle(agentDef, state.sessionId)
          // frozen 专属：registry 退回 Idle——否则 FreezeScheduler 会持续 ping
          // 一个已回 idle 的 agent（无害但浪费，且面板显示错误）。
          _ <- touchRegistryActivity(resources, state.sessionId, AgentStatus.Idle)
          _ <- updateRegistryFrozenReason(resources, state.sessionId, None)
        yield
          val interruptedState = state.resetForInterrupt.withPendingCompaction(None)
          idle(agentDef, resources, depth, parentRef, interruptedState)

      case AgentCommand.Stop(_) =>
        logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "stop", "reason=user-during-frozen")
        for
          _ <- ctx.cancelCurrentTurn()

          _ <- killSessionShellProcesses(state)
          _ <- touchRegistryActivity(resources, state.sessionId, AgentStatus.Idle)
          _ <- updateRegistryFrozenReason(resources, state.sessionId, None)
          _ <- fireLifecycleStopHooks(resources, state)
        yield Behaviors.stopped

      case AgentCommand.RestartAgent(level) =>
        // 镜像 processing 的 RestartAgent；末尾 dispatch 是系统动作（Gated）——
        // 冻结时段再次进入 frozen（正确语义：supervisor 重启不唤醒）。
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
          result <- pipeLlmCall(agentDef, resources, depth, parentRef, restartState, None)
        yield result
        end for

      case AgentCommand.Retry(reason) =>
        // 镜像 processing 的 Retry：从 checkpoint 重派——Gated，冻结时段再次冻结
        // （不烧 token，spec §5.4）。
        logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "retry", s"reason=$reason")
        ctx.cancelCurrentTurn() *> (state.lastDispatch match
          case Some(LastDispatch(false, _)) =>
            pipeLlmCall(agentDef, resources, depth, parentRef, state, None)
          case Some(LastDispatch(true, Some(cr))) =>
            pipeToolExecutions(agentDef, resources, depth, parentRef, state, cr, None)
          case _ =>
            markTeamIdle(agentDef, state.sessionId) *>
              IO.pure(idle(agentDef, resources, depth, parentRef, state.resetForInterrupt)))

      case AgentCommand.Escalate =>
        // v2 升级链到期（§5.2）：FreezeScheduler.scan 发现 escalation.escalateAt
        // 超时 → level+1，通知上一级；父缺失 → 跳级（P0=到达用户终态）；无父 →
        // 用户终态（WS errorEscalated，不设超时——用户是最终仲裁）。
        val current = escalation.getOrElse(EscalationInfo(level = 1, escalateAt = 0L, awaitedParentSessionId = ""))
        val nextLevel = current.level + 1
        val now = System.currentTimeMillis()
        for
          parentSid <- sessionIdOfRef(resources, parentRef)
          target = Escalation.nextTarget(nextLevel, parentRef.isDefined, parentSid.isDefined)
          newEsc =
            if target == Escalation.Target.User then current.copy(level = nextLevel, escalateAt = 0L)
            else current.copy(level = nextLevel, escalateAt = now + nebflow.shared.Defaults.ErrorEscalateAfterMs)
          _ <- IO {
            logAgentEvent(
              agentDef,
              depth,
              state.sessionId,
              state.sessionName,
              "error-escalate",
              s"level=$nextLevel target=$target retryCount=$retryCount"
            )
          }
          _ <- target match
            case Escalation.Target.Parent =>
              parentRef.traverse_ { p =>
                p ! AgentCommand.ExternalEvent(
                  source = "team",
                  eventType = "error-escalated",
                  payload = s""""${agentDef.name}" auto-recovery escalation level $nextLevel (reason=${reasonStr(reason)})""",
                  metadata = JsonObject(
                    "reason" -> reasonStr(reason).asJson,
                    "retryCount" -> retryCount.asJson,
                    "level" -> nextLevel.asJson,
                    "escalateAfterMs" -> nebflow.shared.Defaults.ErrorEscalateAfterMs.asJson,
                    "failedSessionId" -> state.sessionId.getOrElse("").asJson,
                    "agentName" -> agentDef.name.asJson
                  ),
                  correlationId = state.sessionId.filter(_.nonEmpty)
                )
              }
            case Escalation.Target.Grandparent =>
              // 父缺失跳级（P0）：视同到达用户终态（无再上级信息），WS 通知 + 不设超时
              state
                .wsSend(
                  Json.obj(
                    "type" -> "errorEscalated".asJson,
                    "sessionId" -> state.sessionId.getOrElse("").asJson,
                    "reason" -> reasonStr(reason).asJson,
                    "retryCount" -> retryCount.asJson,
                    "level" -> nextLevel.asJson,
                    "escalateAt" -> 0L.asJson,
                    "detail" -> "parent-unavailable-skipped".asJson
                  )
                )
                .handleErrorWith(_ => IO.unit)
            case Escalation.Target.User =>
              state
                .wsSend(
                  Json.obj(
                    "type" -> "errorEscalated".asJson,
                    "sessionId" -> state.sessionId.getOrElse("").asJson,
                    "reason" -> reasonStr(reason).asJson,
                    "retryCount" -> retryCount.asJson,
                    "level" -> nextLevel.asJson,
                    "escalateAt" -> 0L.asJson
                  )
                )
                .handleErrorWith(_ => IO.unit)
          _ <- updateRegistryEscalation(resources, state.sessionId, Some(newEsc))
        yield
          val escalatedState = state.withEscalation(Some(newEsc))
          frozen(agentDef, resources, depth, parentRef, escalatedState, replyTo, resumeAt, reason, retryCount, Some(newEsc))

      case AgentCommand.ExternalEvent(source, eventType, payload, metadata, correlationId) =>
        // 排队 pendingEvents（不唤醒）：出冻结段/唤醒后的下一个 turn 边界
        // （ToolsComplete → drainBarrier）统一注入。barrier 计数语义镜像 idle
        // ExternalEvent——subagent result 到达即递减 outstanding，否则批次在
        // 冻结期间全部完成时计数永不清零，恢复后 drainBarrier 永久 hold。
        logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "freeze-queue-event", s"source=$source type=$eventType")
        val event = AgentCommand.ExternalEvent(source, eventType, payload, metadata, correlationId)
        val isSubagentResult = TurnBoundaryDrains.isSubagentResult(event)
        val outstanding = state.execution.outstandingSubagentResults
        val newOutstanding = if isSubagentResult then math.max(0, outstanding - 1) else outstanding
        val queued = state.copy(execution =
          state.execution.copy(
            outstandingSubagentResults = newOutstanding,
            pendingEvents = state.execution.pendingEvents :+ event
          )
        )
        IO.pure(frozen(agentDef, resources, depth, parentRef, queued, replyTo, resumeAt, reason, retryCount, escalation))

      case msg: AgentCommand.ImmediateInput =>
        // 排队 pendingImmediateInputs（不唤醒）——恢复后的 turn 边界 drain。
        logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "freeze-queue-immediate", s"textLen=${msg.text.length}")
        val queued = state.copy(execution =
          state.execution.copy(pendingImmediateInputs = state.execution.pendingImmediateInputs :+ msg)
        )
        IO.pure(frozen(agentDef, resources, depth, parentRef, queued, replyTo, resumeAt, reason, retryCount, escalation))

      case AgentCommand.MailQueued(item, _) =>
        // 镜像 processing：计数 +1，实际内容在磁盘（MailQueueStore）。
        logAgentEvent(
          agentDef,
          depth,
          state.sessionId,
          state.sessionName,
          "mail-queued",
          s"from=${item.from} pendingCount=${state.execution.pendingMailQueueCount + 1}"
        )
        val queued = state.copy(execution =
          state.execution.copy(pendingMailQueueCount = state.execution.pendingMailQueueCount + 1)
        )
        IO.pure(frozen(agentDef, resources, depth, parentRef, queued, replyTo, resumeAt, reason, retryCount, escalation))

      case AgentCommand.SetSafetyMode(mode) =>
        // 镜像 processing 的轻量状态更新（保持一致性）。
        val policy = PermissionPolicy(safetyMode = mode)
        resources.permissionPolicies
          .update(_ + (state.session.rootSessionId -> policy)) *>
          IO.pure(
            frozen(
              agentDef,
              resources,
              depth,
              parentRef,
              state.withSafetyMode(nebflow.core.SafetyMode.toString(mode)),
              replyTo,
              resumeAt
            )
          )

      case AgentCommand.UpdateContextWindow(window) =>
        // 轻量存储：恢复后下一次 dispatch 的 autoCompact 自会按新窗口评估溢出。
        IO.pure(frozen(agentDef, resources, depth, parentRef, state.withContextWindow(window), replyTo, resumeAt, reason, retryCount, escalation))

      case AgentCommand.UpdateGitBranch(branch) =>
        IO.pure(frozen(agentDef, resources, depth, parentRef, state.withGitBranch(branch), replyTo, resumeAt, reason, retryCount, escalation))

      case n: AgentCommand.BackgroundTaskNotification =>
        // 转成 ExternalEvent 走上面的排队分支（同 processing 的处理方式）。
        (ctx.self ! n.toExternalEvent) *>
          IO.pure(frozen(agentDef, resources, depth, parentRef, state, replyTo, resumeAt, reason, retryCount, escalation))

      case AgentCommand.SetPermissionDeferred(deferred) =>
        // 镜像 processing：持有 deferred，防子 agent 权限应答悬空。
        IO.pure(frozen(agentDef, resources, depth, parentRef, state.withPendingPermission(Some(deferred)), replyTo, resumeAt, reason, retryCount, escalation))

      case AgentCommand.SessionStarted(address, agentName, taskDescription) =>
        val session = AgentSessionInfo(address, agentName, taskDescription, "running")
        IO.pure(
          frozen(agentDef, resources, depth, parentRef, state.withAgentSessions(state.agentSessions :+ session), replyTo, resumeAt, reason, retryCount, escalation)
        )

      case AgentCommand.SessionUpdate(address, status) =>
        val updated = state.agentSessions.map(s => if s.address == address then s.copy(status = status) else s)
        IO.pure(frozen(agentDef, resources, depth, parentRef, state.withAgentSessions(updated), replyTo, resumeAt, reason, retryCount, escalation))

      case AgentCommand.SessionClosed(address) =>
        val updated = state.agentSessions.filterNot(_.address == address)
        IO.pure(frozen(agentDef, resources, depth, parentRef, state.withAgentSessions(updated), replyTo, resumeAt, reason, retryCount, escalation))

      case AgentCommand.CompactionComplete(result) =>
        // stale 压缩结果：frozen 不可能由压缩轮直接进入（压缩 dispatch 在
        // super 内部静态解析，进入 frozen 前已完成）。镜像 idle 的兜底——完成
        // deferred 防等待方悬空，结果本身丢弃。
        logAgentEvent(
          agentDef,
          depth,
          state.sessionId,
          state.sessionName,
          "stale-compaction-discarded",
          result.fold(err => s"err=${err.take(60)}", msgs => s"ok=${msgs.size}msgs")
        )
        val staleState = state.invalidateSystemStableCache
        state.pendingCompaction.flatMap(_.replyDeferred) match
          case Some(d) =>
            ctx.forkTurn(
              d.complete(Left("Compaction result arrived while agent was frozen"))
                .void
                .handleErrorWith(_ => IO.unit)
            ) *> IO.pure(
              frozen(agentDef, resources, depth, parentRef, staleState.withPendingCompaction(None), replyTo, resumeAt, reason, retryCount, escalation)
            )
          case None =>
            IO.pure(frozen(agentDef, resources, depth, parentRef, staleState, replyTo, resumeAt, reason, retryCount, escalation))

      case AgentCommand.ClearReadTracker =>
        state.readTracker.fold(IO.unit)(t => t.clear()) *>
          IO.pure(frozen(agentDef, resources, depth, parentRef, state, replyTo, resumeAt, reason, retryCount, escalation))

      case AgentCommand.ResetSession =>
        for

          _ <- state.readTracker.fold(IO.unit)(t => t.clear())
          _ <- emitStream(state.wsSend, AgentStreamEvent.Interrupted, isSubagent = depth > 0, state.sessionId)
          _ <- touchRegistryActivity(resources, state.sessionId, AgentStatus.Idle)
        yield
          val resetState = state
            .withMessages(Nil)
            .withLatestUsage(None)
            .withPendingCompaction(None)
            .withCompactionFailures(0)
            .withLastCompactionFailureAt(0L)
            .withRecentMessageIds(Nil)
            .invalidateSystemStableCache
            .resetToIdle(Nil)
          idle(agentDef, resources, depth, parentRef, resetState)

      case _ =>
        // stale LlmComplete/LlmFailed/ToolsComplete/TriggerCompaction 等 → 丢弃
        // 留在 frozen（这些消息属于被 gate 拦下的 turn，没有 fiber 在等待它们）。
        IO.pure(frozen(agentDef, resources, depth, parentRef, state, replyTo, resumeAt, reason, retryCount, escalation))

  end frozen

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

end AgentActor
