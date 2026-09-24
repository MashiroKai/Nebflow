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
import nebflow.core.project.NotificationHeader
import nebflow.core.tools.{AskUserQuestionTool, BgTaskRegistry}
import nebflow.llm.*
import nebflow.shared.*
import nebflow.shared.given

import scala.concurrent.duration.*

object AgentActor extends AgentCore with AgentSession:

  private[agent] val MaxEmptyResponseRetries = 5

  /** Overload-class reasons: provider saturated, backoff can heal it. */
  def isOverloadReason(r: FailoverReason): Boolean =
    r == FailoverReason.Overloaded || r == FailoverReason.RateLimit

  /**
   * Flow-node supervision P1 (2026-08-26): agent-turn-level retryability of an
   * LLM failure. Retryable = overload-class (saturation may clear during
   * backoff) OR stream-inactivity stall (phase-2 watchdog — upstream jitter;
   * a clean-checkpoint re-send within the SAME OverloadRetryMax/MaxTurnLlmCalls
   * budget, no new quota; NOT a stream-level resume, so the seam guard is never
   * violated). Everything else fails fast (2026-08-18 token-incident ruling).
   * Single source: the llm-fail retry branch AND AgentError.retryable (P2).
   */
  def llmFailureRetryable(error: Throwable): Boolean = error match
    case e: FallbackExhaustedError =>
      e.attempts.forall(a =>
        a.reason.exists(isOverloadReason)
          || (a.reason.contains(FailoverReason.Timeout) && a.permanence.contains(ErrorPermanence.Transient))
      )
    case _: ToolPipelineError => false
    case _: StreamInactivityTimeout => true
    // Hard-recovery P6 (2026-09-07): a transport-aborted turn (SessionKick on
    // a wedged read / watcher L2) is recoverable — re-send the whole turn
    // within the SAME OverloadRetryMax/MaxTurnLlmCalls budget (no new quota,
    // 设计 D-4). Distinct from StuckAbort (watcher kill semantics: not
    // retryable — the Stop waiting in the mailbox is the recovery).
    case _: RecoverableAbort => true
    case _ =>
      val cls = Fallback.classifyError(error)
      cls.permanence == ErrorPermanence.Transient && isOverloadReason(cls.reason)

  /**
   * Inactivity-class failure (drives the ≥30s retry backoff): a typed stall,
   * or every attempt in an exhausted chain was a Transient timeout.
   */
  def llmFailureInactivityClass(error: Throwable): Boolean = error match
    case _: StreamInactivityTimeout => true
    case e: FallbackExhaustedError =>
      e.attempts.nonEmpty && e.attempts
        .forall(a => a.reason.contains(FailoverReason.Timeout) && a.permanence.contains(ErrorPermanence.Transient))
    case _ => false

  /** Max "you must call Mail" reminder injections before giving up (initial + retries). */
  private[agent] val MaxMailReminders = 2

  /**
   * Max llm-fail retries for overload-class failures (429 rate-limit / 529
   * overloaded): the provider is saturated — a ≥5s backoff gives it a chance
   * to recover. Token incident (2026-08-18): every retry re-sends the full
   * ~250k-token context, so ALL other transient errors now fail fast — the
   * messages are unchanged between retries (96% cache hit), so retrying them
   * mostly amplifies spend without healing anything.
   */
  private val OverloadRetryMax = 1

  /**
   * Flow-node supervision P1: stream-inactivity retries back off at least
   * this long — the upstream stall lasted 60s+ (watchdog window), an
   * immediate re-send would hit the same stall.
   */
  private val InactivityRetryBackoffMinMs = 30_000L

  /**
   * Test hook: override the inactivity retry backoff floor (specs inject
   * ~100ms so checkpoint-restart integration tests don't wait 30s per retry).
   * Public for cross-package specs (core.entity supervision spec). Global
   * var — specs MUST reset to None in a finally.
   */
  var testInactivityBackoffMs: Option[Long] = None

  private def effectiveInactivityBackoffMinMs: Long =
    testInactivityBackoffMs.getOrElse(InactivityRetryBackoffMinMs)

  /** Exponential backoff base for LLM fail retries (ms). */
  private val LlmFailBackoffBaseMs = 2000L

  /** Cap for LLM fail backoff (ms). */
  private val LlmFailBackoffMaxMs = 10000L

  /** Overload-class failures always back off ≥5s before retrying. */
  private val OverloadBackoffMinMs = 5000L

  /**
   * Cancel-batch debounce window (user ruling 2026-08-26 08:33): task
   * cancellations arriving while the agent is processing merge into ONE
   * packaged notice, flushed when the window (measured from the FIRST
   * buffered cancellation) expires. 45s = mid of the approved 30-60s range.
   * The timer rides forkTurn — a turn boundary naturally cancels it and the
   * buffer degrades to the idle-cache semantics (delivered, never lost).
   */

  // ── v2 冻结式错误恢复（20260824_frozen-error-recovery-plan §3.3/§5.1）─────
  /** 同 reason 连续 ErrorFrozen ≥ 本值 → 进入升级链（请求父干预，不再直接 fatal）。 */
  private[agent] val ErrorFreezeEscalationThreshold = 3

  /**
   * Block 3 循环检测器 L2（supervision trio §D3）：Loop 冻结的 resumeAt 偏移
   * ——语义「永不自动续跑」（1 年），恢复只走人工三路：用户输入唤醒 /
   * Manager·Nebula AgentControl restart / cancel 终态。
   */
  private val LoopFreezeResumeMs = 365L * 24 * 3600 * 1000

  /** Network 类错误的 ErrorFrozen 退避窗口：10-30s（含 jitter），短退避后自动重试 1-2 次。 */
  private val NetworkErrorFreezeBackoffMs = 10_000L

  /** ProviderDown 的 ErrorFrozen 退避：≈ 探测周期（120s+jitter），P0 用 R1 轮询兜底。 */
  private val ProviderDownFreezeBackoffMs = 120_000L

  /** FreezeReason → wire 字符串（与 protocol.toJson 序列化一致，单源映射）。 */
  private[agent] def reasonStr(r: FreezeReason): String = r match
    case FreezeReason.Schedule => "schedule"
    case FreezeReason.LlmTransient => "llm-transient"
    case FreezeReason.Network => "network"
    case FreezeReason.ProviderDown => "provider-down"
    case FreezeReason.RestartRecovery => "restart-recovery"
    case FreezeReason.Loop => "loop"

  /** Overload-class reasons: provider saturated, backoff can heal it. */
  private def isOverloadClass(r: FailoverReason): Boolean = AgentActor.isOverloadReason(r)

  private[agent] val logger = NebflowLogger.forName("nebflow.agent")

  /**
   * ② (2026-09-11, queue-direct-pass diagnosis §2 根因): a REAL human input must
   * never carry an injection source. Single definition of the rule so it holds
   * identically at every ImmediateInput/UserInput → Message conversion point
   * (idle judgement, compaction drain, turn-end batch drain) — a caller that
   * sets both `source=Some(...)` and `fromUser=true` still renders as a plain
   * user turn, because 真人性 wins over the label.
   */
  private[agent] def injectionSourceFor(fromUser: Boolean, source: Option[String]): Option[String] =
    if fromUser then None else source

  /**
   * issue #31 Fix D (2026-08-20)：把自身 barrier 状态快照进 agentRegistry，
   * 供 AgentControl list/status 展示——phantom slot（成员 hang / 停止失败时
   * barrier 永不归还）从日志考古变成一条命令可见。诊断语义：idle 期
   * outstanding > 0 且无在飞任务 = phantom；outstanding > 0 且 pending > 0 =
   * 有结果被 HOLD 扣留等批。幂等，registry 无该 session 时 no-op。
   * 刷新点：spawn 计数（tools-complete）、ExternalEvent 三分支。
   */
  private[agent] def touchBarrierSnapshot(
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
  private[agent] def visibleExternalEventSource(source: String, eventType: String): Option[String] =
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
  private[agent] def buildEventReminder(events: List[AgentCommand.ExternalEvent]): Message =
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
    Message(
      MessageRole.User,
      Left(s"<system-reminder>\n${parts.mkString("\n\n")}\n</system-reminder>"),
      source = Some("external")
    )
  end buildEventReminder

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
    ).copy(source = injectionSourceFor(imm.fromUser, imm.source))

  /** UserInput (AgentCommand) → User message for inline continuation injection. */
  private def userCmdToMessage(ui: AgentCommand.UserInput): Message =
    (ui.blocks match
      case Some(blocks) if blocks.nonEmpty => Message(MessageRole.User, Right(blocks))
      case _ => Message(MessageRole.User, Left(ui.text))
    ).copy(source = injectionSourceFor(ui.fromUser, ui.source))

  /**
   * F1 (2026-08-30): drain the queues that were held back during the
   * compaction window (ToolsComplete guard keeps pendingImmediateInputs and
   * pendingEvents untouched while a job is pending; the processing handler
   * buffers UserInputs into pendingUserInputs). 2026-09-15 ub 缺陷批（root 裁定
   * 禁合并语义）把「Inject ALL of it」收敛为**逐条**：continuation round 最多携带
   * **一件**排队消息（immediate input 队首优先，否则队首 replyTo-free 的 UserInput），
   * 其余留在队列由后续 turn 边界逐条消费；events 仍按 buildEventReminder 合批
   * （sub-agent barrier：Delegate/SubTask 结果在批未完成前保持 HELD — #25/#31）。
   * replyTo-bearing UserInput 与非 UserInput 命令（SkillActivate/AskQuestion）
   * 只走全元数据队列：它们留在 pendingUserInputs，由下一个 turn 边界的 head-forward
   * （finishTurnCont）逐条处理 — never lost, only deferred.
   */
  /**
   * F2 (2026-08-30): snapshot the injection queues to disk. Called on every
   * enqueue and every drain so a crash mid-compaction degrades to "queued
   * injections survive the restart" instead of "silently lost". Failures are
   * logged inside the store and never propagate — enqueue must not block.
   */
  private[agent] def persistQueues(sessionId: Option[String], exec: ExecutionContext): IO[Unit] =
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
  private[agent] def mailDedupInReplayWindow(sid: String, resources: SharedResources): IO[Boolean] =
    resources.agentRegistry.get.map { reg =>
      reg.get(sid) match
        case Some(rec) if rec.startedAt > 0 =>
          System.currentTimeMillis() - rec.startedAt <= nebflow.shared.Defaults.MailDedupReplayWindowMs
        case _ => true
    }

  private[agent] def drainQueuesAfterCompaction(compactedState: AgentState): PostCompactDrain =
    val exec = compactedState.execution
    // 排队消息逐条注入（2026-09-15 ub 缺陷批，禁合并语义）：压缩窗口之后的续轮请求
    // 只携带**一件**排队消息 —— immediate input 队首优先（与旧 appended 的
    // imm → user 相对顺序同源），否则取队首 replyTo-free 的 UserInput；其余保持队列，
    // 由后续 turn 边界逐条消费（到达顺序不变）。原「窗口内所有 imm + 所有可内联
    // UserInput 一起注入」（缺陷⑥ 合批）已按 root 裁定删除。
    // replyTo-bearing UserInput / 非 UserInput 命令（SkillActivate / AskQuestion）
    // 依旧只走全元数据路径（turn 末 head-forward），完成目标不搁浅。
    val (imms, immTail) = exec.pendingImmediateInputs match
      case head :: tail => (List(head), tail)
      case Nil => (Nil, Nil)
    val (injectedUsers, userTail): (List[AgentCommand.UserInput], List[AgentCommand]) =
      if imms.nonEmpty then (Nil, exec.pendingUserInputs)
      else
        exec.pendingUserInputs match
          case (ui: AgentCommand.UserInput) :: tail if ui.replyTo.isEmpty => (List(ui), tail)
          case _ => (Nil, exec.pendingUserInputs)
    val immMessages = imms.map(immInputToMessage)
    val userMsgs = injectedUsers.map(userCmdToMessage)
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
      pendingImmediateInputs = immTail,
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

  end drainQueuesAfterCompaction

  /**
   * WS bubble emission for every injected immediate/user input that carries a source.
   * ② (2026-09-11): fromUser 优先于 source —— 真人输入不产注入气泡。
   */
  private def emitInjectedBubbles(
    resources: SharedResources,
    state: AgentState,
    drain: PostCompactDrain
  )(using ctx: ActorContext[AgentCommand]): IO[Unit] =
    val immBubbles = drain.injectedImms.traverse_ { imm =>
      injectionSourceFor(imm.fromUser, imm.source) match
        case Some(src) =>
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
            // 气泡四段式统一批（2026-09-15）：PROJECT 段链首级随件转发（发送方所属
            // 项目），② 级 = 本会话所属项目。
            project = imm.project,
            sessionProject = state.projectName
          )
        case None => IO.unit
    }
    val userBubbles = drain.injectedUsers.traverse_ { ui =>
      injectionSourceFor(ui.fromUser, ui.source) match
        case Some(src) =>
          emitInjectedUserEvent(
            resources,
            state.wsSend,
            state.sessionId,
            ui.text,
            src,
            ui.eventType,
            ui.sender,
            ui.senderTeam,
            ui.delivery,
            // 收件判别字段随 UserInput 同源转发（mailbadge 批 2026-09-13）。
            intake = ui.intake,
            // 气泡四段式统一批（2026-09-15）：PROJECT 段链首级/② 级（同上）。
            project = ui.project,
            sessionProject = state.projectName
          )
        case None => IO.unit
    }
    immBubbles *> userBubbles

  end emitInjectedBubbles

  /** F1/F3: audit log — how many queued injections landed in the continuation round. */
  private def logPostCompactInjection(
    agentDef: AgentDef,
    depth: Int,
    state: AgentState,
    drain: PostCompactDrain
  )(using ctx: ActorContext[AgentCommand]): IO[Unit] =
    IO(
      logAgentEvent(
        agentDef,
        depth,
        state.sessionId,
        state.sessionName,
        "queues-injected-after-compaction",
        s"imm=${drain.immMessages.size} user=${drain.userMessages.size} events=${drain.eventCount} " +
          s"remainingImm=${drain.exec.pendingImmediateInputs.size} " +
          s"remainingUser=${drain.exec.pendingUserInputs.size} " +
          s"remainingEvents=${drain.exec.pendingEvents.size}"
      )
    )

  /**
   * Emit a `user` WS event so the frontend renders an injected-task bubble
   * (浅蓝, source-labeled) instead of leaving the task prompt invisible.
   * Mirrors the `user` event shape the frontend already handles for normal
   * user input (SessionRecorder records it as UiMessage.User with injected=true).
   */
  private[agent] def emitInjectedUserEvent(
    resources: SharedResources,
    wsSend: Json => IO[Unit],
    sessionId: Option[String],
    text: String,
    source: String,
    eventType: Option[String] = None,
    sender: Option[String] = None,
    senderTeam: Option[String] = None,
    delivery: Option[String] = None,
    /**
     * 收件通道判别（mailbadge 批 2026-09-13，选项 C）：与 `senderTeam` /
     * `delivery` **同款可选帧字段**（缺席即不 merge，帧逐字节不变 ⇒ 向后兼容）。
     * 只做**呈现判别**：前端标签优先取它、缺席回落 `source` 表；`source` 的
     * 会计语义（`"task"` = 桥的消费计数口径）不受任何影响。
     */
    intake: Option[String] = None,
    /**
     * **发送方所属项目**（「气泡四段式统一」批 2026-09-15）：四段式 header 第 2 段的
     * 链首级（构造点置位）。`None` ⇒ 走 `sessionProject`（本项目）⇒
     * [[NotificationHeader.RootProject]]（跨 root 直投 / 根域）。
     */
    project: Option[String] = None,
    /**
     * 接收会话所属项目（= `AgentState.projectName`）：PROJECT 段落回链第 ② 级。
     * 由调用点逐处传入（本方法不读 `state`——沿用既有「state 字段显式传参」纪律）。
     */
    sessionProject: Option[String] = None,
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
      // mailbadge 批（2026-09-13，选项 C）：收件通道判别字段。`source` 段逐字节不动
      // （D-5 口径：值不改名），本段只加一枚**可选**展示判别键。
      val withIntake = intake.fold(withDelivery)(i => withDelivery.deepMerge(Json.obj("intake" -> i.asJson)))
      // 气泡四段式统一批（2026-09-15，作者 12:33 令）：**唯一格式化调用点**——
      // `NotificationHeader.header` 是全仓唯一的 header 组装实现（引擎单一来源），
      // 覆盖 `BackendNamedSources` 全部源（含 CHAIN/NODE/MAIL）。产出的 header 同时
      // 进 WS 帧（前端逐字渲染，禁二次拼接）与落盘（.ui.json 历史行同源 ⇒ live 与
      // 历史恢复逐字节一致）。
      //   PROJECT 段落回链：① `project`（构造点 = 发送方所属项目）→ ②
      //   `sessionProject`（接收会话所属项目）→ ③ `NotificationHeader.RootProject`
      //   （跨 root 直投 / 根域）。三级都取不到的场合不存在（③ 恒有值）。
      //   词表外的 source（在飞批新增源，如 device-mail 批的 deviceMail）⇒ None ⇒
      //   **帧不带 header 键**，前端回落既有 `injectedSourceLabel`（逐字节不变）。
      val headerOpt =
        NotificationHeader.header(
          source,
          intake,
          project.orElse(sessionProject).orElse(Some(NotificationHeader.RootProject)),
          sender,
          senderTeam,
          eventType
        )
      val withHeader =
        headerOpt.fold(withIntake)(h => withIntake.deepMerge(Json.obj("header" -> h.asJson)))
      // issue #31 Fix C (2026-08-20): HOLD 分支的完成气泡标注「等待同批任务」——
      // 把「COMPLETED 但父不动」从 bug 观感变成可理解的等待状态（前端展示
      // 待 Frontend 消费此字段）。顺修既有 bug：此处原发 withTeam，
      // withDelivery 被算出后丢弃（delivery 字段从未到达前端）。
      val withWaiting =
        if waitingForBatch then withHeader.deepMerge(Json.obj("waitingForBatch" -> true.asJson))
        else withHeader
      // bluebubble 批（2026-09-12）：注入行的落盘**在唯一发射点收口**。
      // 本方法是全仓唯一的 injected user 帧发射点（grep `"injected" -> true` 单命中），
      // 故它也是这条 .ui.json 记录的唯一写者——此前落盘依赖 WS 录制层
      // （`WebSocketRoutes#makeRecordingWsSend` 的 `user` 分支按帧内 nodeSessionId 嗅探），
      // 而**启动挂载**的项目 engine.wsSendFn = 裸 `wsHub.broadcast`（非录制 send，
      // GatewayMain.startupMount）⇒ 分发器/节点会话的注入气泡只在 live 广播里存在、
      // 永不落盘 ⇒ 会话抽屉/行内视图「看不到蓝气泡」（作者 2026-09-12 19:42 现象）。
      // 此处 `sid` 恒 = 发射者自身会话（= 帧内 `sessionId`，与既有 WS 路由目标
      // 一致：routeWsSend/routeSubagentWsSend 只在缺省时改写 sessionId，而本帧
      // 必带自身 sid；二者重合使落盘目标与前端 live 路由目标严格同源）。
      // 落盘判据与旧录制层逐字保持（`text.nonEmpty && injected` ⇒ 空文本注入
      // 只广播不落盘），写入顺序也保持「先落盘、后广播」单链（同一条 forkTurn
      // 内串行，避免两条 fiber 竞争导致 .ui.json 行序与旧读法不一致）。
      // bluebubble 批的这条 UI 行现在**一次构造、两处使用**（本会话落盘 + 子代理收件
      // 镜像）：两处必须是**同一实例**——「同一事件在 root 与子代理窗口逐字节同形」
      // 是子代理收件批的判据（禁二次构造，防两处字段漂移）。
      val injectedUiRow = UiMessage.User(
        text,
        Nil,
        injected = true,
        timestamp = System.currentTimeMillis(),
        source = Some(source),
        eventType = eventType,
        sender = sender,
        senderTeam = senderTeam,
        delivery = delivery,
        // 与帧同源（同一批名字）：历史恢复路径靠这条落盘字段重建标签。
        intake = intake,
        // 气泡四段式统一批（2026-09-15）：**已渲染 header 随行落盘**——
        // 历史恢复路径逐字渲染同一串（引擎单一来源；前端不再二次拼接）。
        // 词表外 source ⇒ None ⇒ 不落键（旧读法逐字不变，前端走旧回落）。
        header = headerOpt
      )
      val persist =
        if text.nonEmpty then
          resources.sessionStore
            .appendUiMessages(sid, List(injectedUiRow))
            .handleErrorWith(e => logger.warn(s"injected user event persist failed: ${e.getMessage}"))
        else IO.unit
      // 子代理收件（卡08 裁点 2，作者 2026-09-20 07:33 批「建」）：**投递侧增量**。
      // 本点是全仓唯一的 injected user 行写者（上方 bluebubble 批注释）⇒ 11 处
      // `emitInjectedUserEvent` 调用点的镜像扩展**在此单点收口**（逐处复制 11 份
      // 同款镜像腿会造出 11 份会漂移的副本，违反本仓「单一来源」纪律）：11 处调用点
      // 的全部差异只在**传入字段**，镜像判据只吃 `sid`/`source`/行本身，故单点等价覆盖。
      // 路由与边界（白名单族 / parent 链 / 前缀族 / 开关 / 只落流不投 agent / 零 WS 帧）
      // 全在 [[InjectedInboxMirror]] 内（唯一来源）。空文本与落盘腿同判据（只广播不落盘
      // ⇒ 零镜像行）。位置刻意在 `wsSend` **之后**：live 帧时序与改前逐字一致。
      val inboxMirror =
        if text.nonEmpty then
          resources.agentRegistry.get
            .flatMap(reg =>
              InjectedInboxMirror.mirror(
                reg.iterator.map((s, rec) => (s, rec.rootSessionId)).toList,
                sid,
                source,
                injectedUiRow,
                (target, row) => resources.sessionStore.appendUiMessages(target, List(row))
              )
            )
            .handleErrorWith(e => logger.warn(s"injected inbox mirror failed: ${e.getMessage}"))
            .void
        else IO.unit
      ctx.forkTurn(
        persist *> wsSend(withWaiting)
          .handleErrorWith(e => logger.warn(s"injected user event failed: ${e.getMessage}")) *> inboxMirror
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
    /**
     * Project 任务板身份（TaskBoard 批 2，详见 SessionContext.flowNodeId/isDispatcher/
     * projectName）：NodeEngine 置 flowNodeId+projectName，ProjectActor 置
     * isDispatcher+projectName；经 AgentCore 透传 ToolContext。默认空=非项目会话。
     */
    flowNodeId: Option[String] = None,
    isDispatcher: Boolean = false,
    /**
     * 节点角色（nrloop 一期 2026-09-12；设计 §3.2 + B1 透传链）：NodeDef.role 随
     * spawn 注入（NodeEngine 节点/loop 会话），经 AgentCore 透传
     * ToolContext.flowNodeRole——node_report 值域分化 + ProtocolFootnote 角色分支
     * 的来源。默认 None = 非项目会话/旧路径（判据回落 NodeRoles.Task）。
     */
    flowNodeRole: Option[String] = None,
    projectName: Option[String] = None,
    /**
     * D6 批 F1（G9 路径 a）：节点人类可读名随 spawn 注入——AskUser payload
     * nodeName 字段来源。详见 SessionContext.flowNodeName。
     */
    flowNodeName: Option[String] = None,
    /**
     * 链级抽象 P2（20260910 spec §9.2 项 2）：节点所属链 id spawn 时刻快照
     * （NodeEngine 注入，None = 无链/非项目会话）。详见 SessionContext.flowChainId。
     */
    flowChainId: Option[String] = None,
    /**
     * 阶段 2a 沙箱（§A.6）：project 节点/分发器 spawn 置 true——AgentCore 据此
     * 从 projectRoot 派生 ToolContext.sandbox。默认 false=旧行为（双轨豁免面）。
     */
    sandboxEnabled: Boolean = false,
    /**
     * 显式沙箱根（2026-09-05 21:05 作者裁定——worktree 节点继承项目沙箱）：
     * NodeEngine 传项目工作区根，沙箱 root 不再收窄到 worktree 目录自身。None =
     * 沿用 projectRoot 推导（旧行为）。
     */
    sandboxRoot: Option[String] = None,
    /**
     * **会话初始 cwd**（B5 缺口② · 作者 2026-09-17 M-1 裁定，选项①）：NodeEngine
     * 两个 spawn 点传座椅路径（worktree 节点 ⇒ `<ws>/.nebflow/worktrees/<name>`）。
     * 围栏面（sandboxRoot）不动；座椅缺失 ⇒ 该会话 Bash 显式失败（fail-closed）。
     * 非项目轨不传 ⇒ None ⇒ 旧行为逐字节不变。详见 SessionContext.sessionCwd。
     */
    sessionCwd: Option[String] = None,
    /**
     * 项目会话信号（沙箱拆围栏批 S1/R8 解耦）：project 节点 / 分发器 spawn 置
     * true（NodeEngine ×2 + ProjectActor ×1），AGENTS.md 注入判据据此置位——
     * 不再挂在沙箱总闸上。默认 false = WS 根会话/双轨面不注入（旧行为不变）。
     */
    projectSession: Boolean = false,
    /**
     * ctxthresh 批（2026-09-15 方案 A）：会话级压缩阈值比例覆盖（root 会话专属）。
     * 🔴 唯一注入点 = `WebSocketRoutes.doSpawnRootAgent`（depth=0）；非 root spawn
     * 一律不传 ⇒ None ⇒ 走现值函数（口径③ / §5.3 静态泄漏判据）。
     */
    compactThresholdRatio: Option[Double] = None
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
            flowNodeId = flowNodeId,
            isDispatcher = isDispatcher,
            flowNodeRole = flowNodeRole,
            projectName = projectName,
            flowNodeName = flowNodeName,
            flowChainId = flowChainId,
            sandboxEnabled = sandboxEnabled,
            sandboxRoot = sandboxRoot,
            sessionCwd = sessionCwd,
            projectSession = projectSession,
            compactThresholdRatio = compactThresholdRatio
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

  private[agent] def fireLifecycleStopHooks(resources: SharedResources, state: AgentState)(using
    ctx: ActorContext[AgentCommand]
  ): IO[Unit] =
    // P0-2（spec §2.5）：会话终态清空 MCP 审批卡的 scope=session 放行记忆 ——
    // 「不落盘 + 会话终态失效 ⇒ 零跨会话残留」。**放在 depth 判据之外**：子代理会话
    // （depth>0）的放行记忆同样必须随其终态释放（否则 map 只增不减）。
    // 只清本会话键（`SessionApprovals.clear(sessionId)` 幂等）。
    val clearMcpSessionApprovals =
      IO.delay(nebflow.core.SessionApprovals.clear(state.sessionId.getOrElse("")))
        .handleErrorWith(_ => IO.unit)
    if state.depth == 0 then
      val hookCtx = buildHookContext(state)
      clearMcpSessionApprovals *>
        ctx.forkTurn(resources.hookEngine.onStop(hookCtx) *> resources.hookEngine.onSessionEnd(hookCtx))
    else clearMcpSessionApprovals

  end fireLifecycleStopHooks

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
  private[agent] def killSessionShellProcesses(state: AgentState): IO[Unit] =
    // 抽公共收殓函数（孤儿后台任务收割 D1）：杀进程树 + 注销 BgTaskRegistry +
    // WS cancelled 帧三件事合一，与 NodeEngine 终态出口共用（去重）。
    BgTaskRegistry.reclaimSession(state.sessionId, state.wsSend, state.rootSessionId)

  /**
   * Build the "askUser" WS payload for the frontend. When the question comes
   * from a sub-agent (ForwardAskUser), agentName is set to the source agent
   * for attribution and sourceAgent/sourceSession carry the origin info.
   * Pure — `private[agent]` so the #380 passthrough contract (canvas/preview
   * emitted only when present, byte-identical otherwise) is unit-covered.
   * D6 批 F1（G9）：project/nodeName 来源标注字段——仅在项目上下文（项目节点
   * 或分发器会话）时携带，缺省 payload 与恢复前字节一致（AskUserBuildJsonSpec
   * V11b 防线同款纪律）。
   */
  /**
   * 内核会话判定（单一判据）：会话 id 前缀 `delegate-kernel-`（与 R10 审计行的
   * 事后过滤口径、`SessionStore.DelegateId` 命名同源）。
   */
  private[agent] def isKernelSession(sessionId: String): Boolean =
    sessionId.startsWith("delegate-kernel-")

  /**
   * U3（作者裁定 2026-09-11：自定义答「显示 subagent-任务」，否决裸 `kernel`）：
   * 内核 ask 的来源标注 = `subagent · <任务摘要>`——一眼看出「这是子代理在问」，
   * 并带上它正在做的那个任务，使多张卡可区分。摘要来源 = 调用方传入的
   * `description`（= 会话语义名 `state.sessionName`；缺省回落到会话 id 尾段），
   * 上限 24 字符（不把整段任务文本灌进标签）。
   *
   * 落点仍是**来源标注的回落分支**：payload 的 `agentName` 字段即前端 badge 的
   * 回落来源（`askPending.js:38-41` / `chat.js:2033`）⇒ 纯后端注入标签文本，
   * **零 web/ 改动**（读 `askPending.js:35-41` + `main.js:1297-1333` 确认：
   * `project` 缺省时标签 = `msg.agentName` 原样渲染）。
   */
  private[agent] def subagentAskLabel(sessionName: Option[String], sessionId: String): String =
    val raw = sessionName.map(_.trim).filter(_.nonEmpty).getOrElse {
      // 无 description：退回会话 id 尾段（可辨识，且不编造任务语义）
      sessionId.split('-').lastOption.filter(_.nonEmpty).getOrElse("task")
    }
    val summary = if raw.length > 24 then raw.take(23) + "…" else raw
    s"subagent · $summary"

  private[agent] def buildAskUserJson(
    sessionId: Option[String],
    agentName: String,
    items: List[AskItem],
    sourceAgent: Option[String] = None,
    sourceSession: Option[String] = None,
    project: Option[String] = None,
    nodeName: Option[String] = None
  ): Json =
    val fields = scala.collection.mutable.ListBuffer(
      "type" -> "askUser".asJson,
      "sessionId" -> sessionId.asJson,
      "agentName" -> agentName.asJson
    )
    sourceAgent.foreach(sa => fields += "sourceAgent" -> sa.asJson)
    sourceSession.foreach(ss => fields += "sourceSession" -> ss.asJson)
    project.foreach(p => fields += "project" -> p.asJson)
    nodeName.foreach(nn => fields += "nodeName" -> nn.asJson)
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
      // (2026-09-05 作者裁定：工作区选择卡；前端据此渲染「选择工作区」应用内目录浏览器大目标)
      if item.dirPicker then base += "dirPicker" -> true.asJson
      // freeInput emitted only when false — payloads that omit it (or carry the default
      // true) stay byte-identical (2026-09-17 作者裁定 ②-7：dirPicker 卡显式置 false，
      // 前端据此不渲染自由输入 textarea / 不恢复草稿)
      if !item.freeInput then base += "freeInput" -> false.asJson
      Json.obj(base.toList*)
    })
    Json.obj(fields.toList*)
  end buildAskUserJson

  /**
   * 多 AskUser 并发批（#250 第②项，2026-09-13 作者裁定「6 项全补」）：
   * turn 被用户 Interrupt 后，回收本会话仍挂在 InteractionHub 的 pending 槽。
   *
   * 改前现象（代码判据）：`AgentCommand.Interrupt` 的 processing 分支只做
   * `cancelCurrentTurn` + `emitStream(Interrupted)` + registry 回 `Idle`
   * （本文件 Interrupt 分支），**不触发** `CleanupForSession`；而全仓
   * `CleanupForSession` 只有 2 个调用点（`NodeEngine` 节点 cancel/abandon/死会话回收、
   * `BackoffSupervisor` 子代理终态）——**root 会话没有清理入口**。后果：turn 没了、
   * 等待方（工具里的 `.?`）已死，但 hub 槽位与前端卡片仍然挂着；用户点它 =
   * 对一个没有听众的动作作答（`InteractionHub.handleAnswered` 里 `replyTo` 早已
   * 无人接收），且 R1 起等待无超时 ⇒ 卡片是永久僵尸。
   *
   * 语义边界：中断 = 该 turn 的**全部**人类等待一起作废 —— AskUser 卡与权限卡
   * 共用同一个槽容器（`InteractionHub` 的 `pending` Map，两种 kind），故按
   * `sourceSession` 批量回收正是既有 `CleanupForSession` 语义；`reason =
   * "turn-interrupted"` 让前端把卡片文案从「来源已关闭」改成中断文案。
   * （相邻但**不同**的 Exit —— `ResetSession` / `Retry` / `Stop` —— 本批按
   * 「禁扩面」只登记不修，见报告「邻接问题登记」。）
   *
   * 无静默路径核证：hub 未装配（早期 boot / 测试）时**无槽可清**——请求在
   * `AskUser` 分支（本文件）与权限分支（`AgentCore.sendPermissionRequest`）就已
   * WARN 丢弃；此处仍打一行 info 供归因，绝不静默吞掉一次中断清理意图。
   */
  private def closePendingInteractionsForInterruptedTurn(
    resources: SharedResources,
    sessionId: String
  ): IO[Unit] =
    if sessionId.isEmpty then IO.unit
    else
      resources.interactionHubRef.get.flatMap {
        case Some(hub) =>
          logger.info(s"Interrupt: closing pending interaction slots for session=$sessionId")
          (hub ! InteractionHubCommand.CleanupForSession(sessionId, reason = "turn-interrupted")).void
        case None =>
          logger.info(
            s"Interrupt: no InteractionHub spawned — nothing to clean for session=$sessionId " +
              "(no hub slot can exist without the hub)"
          )
      }

  // ============================================================
  // Idle state
  // ============================================================

  // idle 态已整体迁至 agent/AgentIdle.scala(行为保持重构,2026-09-25):idle
  // 行为及其唯一消费的注入判源助手 inferInjectionSource 的实现都在那边
  // (方法体逐字未动);此处保留同名委托 def(签名原样),调用点零改动。
  private[agent] def idle(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState
  )(using ctx: ActorContext[AgentCommand]): Behavior[AgentCommand] =
    AgentIdle.idle(agentDef, resources, depth, parentRef, state)
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
          // +1 from here with no collision — AgentCore.scala:449).
          // compactui 批（2026-09-15 事故 ②）：dropCompactionScratch 必须在
          // withPendingCompaction(None) **之前**应用（判据依赖作业仍在）。
          val interruptedState = dropCompactionScratch(state).resetForInterrupt
            .withCurrentTurnId(state.execution.currentTurnId + 1)
            .withPendingCompaction(None)
          idle(agentDef, resources, depth, parentRef, interruptedState)
        end for

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
        IO.pure(processing(agentDef, resources, depth, parentRef, state.withPendingPermission(Some(deferred)), pending))

      // --- Bypass toggled while processing（permshield S1：命令已退役，catch-all 兜底）---

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

      // ctxthresh 批：processing 态收到阈值热更 ⇒ 轻量存储（无压缩副作用）。
      // 在飞 turn 用旧值、下一回合边界起用新值（口径 §8.3-E7 的时序语义）。
      case AgentCommand.SetCompactThresholdRatio(ratio) =>
        IO.pure(
          processing(
            agentDef,
            resources,
            depth,
            parentRef,
            state.withCompactThresholdRatio(ratio),
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

  // turn 收尾族已整体迁至 agent/AgentFinishTurn.scala(行为保持重构,2026-09-25):
  // handleLlmCompleteBranch / finishTurn / finishTurnCont / markTeamBusy /
  // markTeamIdle / fullyIdle / emitDequeuedWs 的实现都在那边(方法体逐字未动);
  // 此处保留同名委托 def(签名与默认参数原样),调用点零改动。pipeLlmCall /
  // pipeToolExecutions 留守本文件。
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
    AgentFinishTurn.handleLlmCompleteBranch(agentDef, resources, depth, parentRef, state, replyTo, result, pending)

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
    AgentFinishTurn.finishTurn(
      agentDef,
      resources,
      depth,
      parentRef,
      state,
      replyTo,
      text,
      thinking,
      thinkingSignature,
      textAlreadyStreamed,
      model
    )

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
    textStreamed: Boolean,
    isSubagent: Boolean
  )(using ctx: ActorContext[AgentCommand]): IO[Behavior[AgentCommand]] =
    AgentFinishTurn.finishTurnCont(
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
      textStreamed,
      isSubagent
    )

  private[agent] def markTeamBusy(agentDef: AgentDef, sid: Option[String]): IO[Unit] =
    AgentFinishTurn.markTeamBusy(agentDef, sid)

  private[agent] def markTeamIdle(agentDef: AgentDef, sid: Option[String]): IO[Unit] =
    AgentFinishTurn.markTeamIdle(agentDef, sid)

  private[agent] def fullyIdle(
    sid: String,
    state: AgentState,
    resources: SharedResources,
    skipSelfStatus: Boolean = false
  ): IO[Boolean] = AgentFinishTurn.fullyIdle(sid, state, resources, skipSelfStatus)

  private[agent] def emitDequeuedWs(wsSend: Json => IO[Unit], sessionId: String, itemId: String): IO[Unit] =
    AgentFinishTurn.emitDequeuedWs(wsSend, sessionId, itemId)

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
            super.pipeLlmCall(
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

  /**
   * v2 升级链通知（§5.1）：父是 agent → ExternalEvent("error-escalated") 注入
   * 父上下文（排队不唤醒，T_escalate 兜底）；父是用户（无 parentRef）→ WS
   * errorEscalated 事件 + UI 升级卡片。通知不消耗本 agent LLM token（D2）。
   * 幂等：由 enterFrozen 的 escalation.isEmpty 守卫保证每轮升级链只通知一次。
   */
  private[agent] def notifyEscalation(
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
          payload = s""""${agentDef.name}" auto-recovery failed $count times (reason=${reasonStr(
              reason
            )}); awaiting parent decision (restart/cancel/wait)""",
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

    end match

  end notifyEscalation

  /**
   * 反查 ActorRef → registry 中对应 sessionId（升级链「父存活」判定）。
   * registry 小（几十条），O(n) 可接受。
   */
  private[agent] def sessionIdOfRef(
    resources: SharedResources,
    ref: Option[ActorRef[AgentCommand]]
  ): IO[Option[String]] =
    ref match
      case None => IO.pure(None)
      case Some(r) =>
        resources.agentRegistry.get.map(_.collectFirst { case (sid, rec) if rec.ref == r => sid })

  /**
   * registry 的 escalation + frozenReason 快照（FreezeScheduler.scan / WS
   * parentRestart 只读侧；actor 层为权威）。
   */
  private[agent] def updateRegistryEscalation(
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

  /**
   * registry 的 frozenReason 快照（WS parentRestart 区分时间表/错误族冻结）。
   * 冻结进入时写 Some(reasonStr)，恢复/唤醒/中断时清 None。
   */
  private[agent] def updateRegistryFrozenReason(
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

  /**
   * 当前冻结窗口态的下一翻转点（现象 2 契约补全 2026-08-30）：恢复/唤醒路径
   * 发 Resumed 事件时附带——工作态 = 下一冻结开始时刻（前端展示「下一段 HH:mm
   * 再冻结」），配置关闭/全天无翻转 = None。错误族恢复（与时间表无关）也带当前
   * 窗口态，让前端输入栏状态机始终有权威依据。
   */
  private[agent] def currentNextChange(resources: SharedResources): IO[Option[Long]] =
    for
      cfg <- resources.freezeScheduleRef.get
      skipUntil <- resources.freezeSkipUntilRef.get
    yield nebflow.core.schedule.FreezeSchedule
      .evalWithSkip(cfg, skipUntil, System.currentTimeMillis())
      .nextChangeAt

  // 冻结域(enterErrorFrozen / enterFrozen / frozen)已整体迁至 agent/AgentFrozen.scala
  // (行为保持重构,2026-09-25):方法体逐字未动,frozen 行为内部的 actor 变换与
  // 自递归(返回下一 frozen behavior)保持原逻辑;notifyEscalation / sessionIdOfRef /
  // updateRegistryEscalation / updateRegistryFrozenReason / currentNextChange /
  // reasonStr / ErrorFreezeEscalationThreshold 等冻结域 helper 与常量留守此处;
  // 此处保留同名委托 def(签名与默认参数原样),调用点零改动。
  private[agent] def enterErrorFrozen(
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
    AgentFrozen.enterErrorFrozen(agentDef, resources, depth, parentRef, state, replyTo, reason, resumeInMs, error)

  private[agent] def enterFrozen(
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
    AgentFrozen.enterFrozen(agentDef, resources, depth, parentRef, state, replyTo, resumeAt, reason, detail)

  private[agent] def frozen(
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
    AgentFrozen.frozen(agentDef, resources, depth, parentRef, state, replyTo, resumeAt, reason, retryCount, escalation)

  // 压缩/ask 收尾 handler 族已整体迁至 agent/AgentCompactionHandlers.scala(行为保持
  // 重构,2026-09-25):handleCompactResponse / handleCompactFailure /
  // emitAbandonedCompaction / dropCompactionScratch / handleAskComplete / isAskReminder /
  // handleTriggerCompaction / handleEmptyResponse 的实现都在那边(方法体逐字未动);
  // 此处保留同名委托 def(签名与默认参数原样),调用点零改动。
  private[agent] def handleCompactResponse(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    responseText: String
  )(using ctx: ActorContext[AgentCommand]): IO[Behavior[AgentCommand]] =
    AgentCompactionHandlers.handleCompactResponse(
      agentDef,
      resources,
      depth,
      parentRef,
      state,
      responseText
    )

  private[agent] def handleCompactFailure(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    err: String
  )(using ctx: ActorContext[AgentCommand]): IO[Behavior[AgentCommand]] =
    AgentCompactionHandlers.handleCompactFailure(agentDef, resources, depth, parentRef, state, err)

  private[agent] def emitAbandonedCompaction(state: AgentState, depth: Int)(using
    ctx: ActorContext[AgentCommand]
  ): IO[Unit] = AgentCompactionHandlers.emitAbandonedCompaction(state, depth)

  private[agent] def dropCompactionScratch(state: AgentState): AgentState =
    AgentCompactionHandlers.dropCompactionScratch(state)

  private[agent] def handleAskComplete(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    answerText: String,
    model: Option[String]
  )(using ctx: ActorContext[AgentCommand]): IO[Behavior[AgentCommand]] =
    AgentCompactionHandlers.handleAskComplete(
      agentDef,
      resources,
      depth,
      parentRef,
      state,
      answerText,
      model
    )

  private[agent] def isAskReminder(msg: Message): Boolean =
    AgentCompactionHandlers.isAskReminder(msg)

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
    AgentCompactionHandlers.handleTriggerCompaction(
      agentDef,
      resources,
      depth,
      parentRef,
      state,
      mode,
      replyDeferred,
      resumeAfterCompact,
      postCompactInstruction
    )

  private[agent] def handleEmptyResponse(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    replyTo: Option[ActorRef[AgentEvent]],
    result: ConsumeResult
  )(using ctx: ActorContext[AgentCommand]): IO[Behavior[AgentCommand]] =
    AgentCompactionHandlers.handleEmptyResponse(
      agentDef,
      resources,
      depth,
      parentRef,
      state,
      replyTo,
      result
    )

end AgentActor
