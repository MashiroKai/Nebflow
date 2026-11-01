package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.actor.*
import nebflow.core.mail.SessionMailbox
import nebflow.shared.{Message, MessageRole, NebflowLogger}

/**
 * MailboxDrain —— 会话邮箱的**唯一消费口**（终态通知 actor 邮箱化批 P1/P2；设计
 * `docs/design/20261101_090600_delegate-terminal-mailbox-design__delegate-nebflow-4a58b3db.zh-CN.md`
 * §3.4「五唤醒点 drain」）。
 *
 * == 唤醒点接线（P2 落地面）==
 *
 *   - W1 spawn/恢复：`AgentIdle.RecoverPersistedQueues` 尾部——taken 残差重注 +
 *     pending 全取，空箱零动作（逐字现状）。
 *   - W2 回合边界：`AgentProcessing` ToolsComplete / `AgentFinishTurn` drainBarrier
 *     面——[[barrierPlan]] 批门禁（在飞成员>0 ⇒ 子代理件留箱；成员齐 ⇒ 全取并轮）。
 *   - W3 idle 收口：`AgentFinishTurn.returnToIdle` 尾部——全取注入（#418 idle 面
 *     语义：永不扣留已产出结果）。
 *   - W4 解冻边界：`AgentFrozen` 两条 CheckFreezeGate 恢复腿 + UserInput 唤醒腿
 *     ——重注+全取**并入恢复轮本身**（解冻首拍必注入，判红锚）。
 *   - W5 门铃：`AgentCommand.MailboxArrived`——idle 态即扫即注；processing/frozen
 *     态 no-op（条目留箱等 W2/W3/W4）。
 *
 * == 取走-注入-清除的事务邻域（与旧机器同窗）==
 *
 * take（原子 rename 进 taken/）先于注入（条目从此以 taken 为持久载体——取走后
 * 注入前崩 = 恢复点 [[SessionMailbox.scanTaken]] 重注，≤1 重复零丢失）；unlink
 * 在 pipeLlmCall 装配点（旧机器 markDelivered 同邻域）——「已投递」= 条目删除的
 * 物理事实。注入回合内容超过该点的崩溃耐久性 = 会话转录既有事务域（旧链事件
 * 同规，非本批新增窗口）。
 *
 * == 批聚合（HOLD 本职保留、实现换轴，设计 §2.7/§4.4）==
 *
 * 批未关成员条目**留箱**（唯一拷贝在盘上、计入徽标深度），批关一回合注入——
 * [[barrierPlan]] 的在飞估算 = outstanding − 箱内子代理件数（ arrivals 不再过
 * pendingEvents，计数语义在 drain 拍重建；idempotent：减法只发生在**取走拍**，
 * 留箱件零会计）。
 *
 * 呈现纪律：单件 = 既有直投形（payload 原文 + retryable 提示，无 reminder 包裹）；
 * 多件 = [[buildEventReminder]] 合批形（编号 + system-reminder 包裹）——「消费呈现
 * 逐字保留」。气泡/流事件同点发射。
 */
private[agent] object MailboxDrain:
  import nebflow.agent.AgentActor.*

  private val logger = NebflowLogger.forName("nebflow.agent.mailbox-drain")

  /** 条目事件是否占用子代理批屏障槽（与 pendingEvents 同判据）。 */
  def isSubagentEvent(e: AgentCommand.ExternalEvent): Boolean = TurnBoundaryDrains.isSubagentResult(e)

  /** drain 产出事件的 viaMailbox 标（旧机器判别位：标记/ack 面对邮箱件零动作）。 */
  private def stamped(e: SessionMailbox.Entry): AgentCommand.ExternalEvent =
    e.event.copy(metadata = e.event.metadata.deepMerge(JsonObject("viaMailbox" -> true.asJson)))

  /**
   * W2 批门禁（纯函数，spec 判红锚）。入参必须已按 seq 序。
   * 逐条顺序裁决（谓词是调度不是重排——放行集保持 seq 相对序）：
   *   - 子代理件：`outstanding − 箱内子代理余数 > 0` ⇒ **留箱**（批次在飞，等齐）；
   *     否则取走并扣一个槽（取走拍会计，恰一次）；
   *   - 非子代理件（升级等）：恒取走。
   * 返回 (取走, 留箱)。
   */
  def barrierPlan(
    pending: List[SessionMailbox.Entry],
    outstanding: Int
  ): (List[SessionMailbox.Entry], List[SessionMailbox.Entry]) =
    var open = math.max(0, outstanding)
    var subRemaining = pending.count(e => isSubagentEvent(e.event))
    val take = scala.collection.mutable.ListBuffer[SessionMailbox.Entry]()
    val hold = scala.collection.mutable.ListBuffer[SessionMailbox.Entry]()
    pending.foreach { e =>
      if isSubagentEvent(e.event) then
        if open - subRemaining > 0 then hold += e // 成员仍在飞——留箱等齐（HOLD 本职）
        else
          take += e
          open = math.max(0, open - 1)
          subRemaining = math.max(0, subRemaining - 1)
      else take += e
    }
    (take.toList, hold.toList)

  // ── 门铃（best-effort；活会话即唤醒，死会话蒸发——信已在盘上）──────────

  def doorbell(resources: nebflow.core.AgentRuntimePort, sessionId: String): IO[Unit] =
    if sessionId.trim.isEmpty then IO.unit
    else
      resources.agentRegistry.get.map(_.get(sessionId)).flatMap {
        case None => IO.unit // 不 live：等会话下次 spawn 的 W1 扫箱（BgSessionActivator 接线留 P3）
        case Some(rec) =>
          (rec.ref ! AgentCommand.MailboxArrived)
            .handleErrorWith(e => logger.warn(s"mailbox doorbell to '$sessionId' failed: ${e.getMessage}"))
      }

  // ── drain 面（物理消费原语的编排）────────────────────────────────────

  /** W1/W4 残差重注：taken 全量（条目留在 taken，注入点 unlink 清除）。 */
  def replayTakenEvents(sid: String): IO[List[AgentCommand.ExternalEvent]] =
    SessionMailbox.scanTaken(sid).map(_.map(stamped))

  /** [[replayTakenEvents]] 同步面（行为体 val 位消费）。 */
  def replayTakenEventsSync(sid: String): List[AgentCommand.ExternalEvent] =
    SessionMailbox.scanTakenSync(sid).map(stamped)

  /** W3/W1 idle 面：pending 全取（#418——idle 永不扣留已产出结果）。 */
  def drainIdleFace(sid: String): IO[List[AgentCommand.ExternalEvent]] =
    SessionMailbox.scanPending(sid).flatMap(entries => SessionMailbox.take(sid, entries)).map(_.map(stamped))

  /** [[drainIdleFace]] 同步面（行为体 val 位消费——W3 收口/W1 恢复接线用）。 */
  def drainIdleFaceSync(sid: String): List[AgentCommand.ExternalEvent] =
    SessionMailbox.takeSync(sid, SessionMailbox.scanPendingSync(sid)).map(stamped)

  /**
   * W2 批门禁面：[[barrierPlan]] 裁决 → 取走放行集。返回 (注入事件, 留箱数)。
   * 留箱件**原地不动**（唯一拷贝留盘、计入徽标深度）。
   */
  def drainBarrierFace(sid: String, outstanding: Int): IO[(List[AgentCommand.ExternalEvent], Int)] =
    SessionMailbox.scanPending(sid).flatMap { pending =>
      val (takeE, holdE) = barrierPlan(pending, outstanding)
      SessionMailbox.take(sid, takeE).map(taken => (taken.map(stamped), holdE.size))
    }

  /** [[drainBarrierFace]] 同步面（行为体 val 位消费——W2 回合边界接线用）。 */
  def drainBarrierFaceSync(sid: String, outstanding: Int): (List[AgentCommand.ExternalEvent], Int) =
    val pending = SessionMailbox.scanPendingSync(sid)
    val (takeE, holdE) = barrierPlan(pending, outstanding)
    (SessionMailbox.takeSync(sid, takeE).map(stamped), holdE.size)

  /** 留箱深度（诊断/徽标快照面；纯读数，零消费）。 */
  def mailboxHeldCount(sid: String): Int = SessionMailbox.scanPendingSync(sid).size

  /** 消费完成清除（装配点调用；幂等）。 */
  def unlinkConsumed(sid: String, events: List[AgentCommand.ExternalEvent]): IO[Unit] =
    SessionMailbox.unlinkTaken(sid, events.map(_.correlationId.getOrElse("")).filter(_.nonEmpty))

  // ── 呈现组装（逐字保留既有注入管线形态）──────────────────────────────

  /**
   * 注入消息：单件 = 直投形（payload 原文 + retryable 提示，marker = 可见源或
   * external）；多件 = buildEventReminder 合批形。与 AgentIdle 直投/批次两腿同形。
   */
  def mailboxMessage(events: List[AgentCommand.ExternalEvent]): Message =
    events match
      case Nil => Message(MessageRole.User, Left("")) // 防呆：调用方空集不该到达（W1/W3 空箱走原状态）
      case single :: Nil =>
        val hint =
          if single.eventType == "failed" && single.metadata("retryable").exists(_.asBoolean.getOrElse(false)) then
            val failureType = single.metadata("failureType").flatMap(_.asString).getOrElse("unknown")
            val failedSession = single.metadata("failedSessionId").flatMap(_.asString).getOrElse("")
            val agentName = single.metadata("agentName").flatMap(_.asString).getOrElse("")
            s"\n<system-reminder>\nA sub-agent task${if agentName.nonEmpty then s" ($agentName)" else ""}" +
              s"${if failedSession.nonEmpty then s" [session=$failedSession]" else ""} failed" +
              s" (failure type: $failureType). This is a retryable error — consider re-delegating" +
              s" the same task with the Delegate or SubTask tool.\n</system-reminder>"
          else ""
        immediateEventMessage(single.payload + hint, single, visibleExternalEventSource(single.source, single.eventType).orElse(Some("external")))
      case many => buildEventReminder(many)
  end mailboxMessage

  /** 接收可见性（流事件 + 注入泡）——与 idle 直投腿同点同形。 */
  private def visibility(
    resources: SharedResources,
    state: AgentState,
    e: AgentCommand.ExternalEvent,
    depth: Int,
    waitingForBatch: Boolean
  )(using ctx: ActorContext[AgentCommand]): IO[Unit] =
    emitStream(
      state.wsSend,
      AgentStreamEvent.ExternalEventReceived(e.source, e.eventType, e.correlationId),
      isSubagent = depth > 0,
      state.sessionId
    ) *> (visibleExternalEventSource(e.source, e.eventType) match
      case Some(src) =>
        emitInjectedUserEvent(
          resources,
          state.wsSend,
          state.sessionId,
          e.payload,
          src,
          Some(e.eventType),
          e.metadata("agentName").flatMap(_.asString),
          fileChanges = e.metadata("fileChanges"),
          sessionProject = state.projectName,
          waitingForBatch = waitingForBatch
        )
      case None => IO.unit
    )

  /** 子代理屏障会计：取走集占用的槽数（恰一次——只在取走拍减）。 */
  def takenSubagentSlots(events: List[AgentCommand.ExternalEvent]): Int =
    events.count(isSubagentEvent)

  // ── W1/W3/W5：idle 面注入轮 ─────────────────────────────────────────

  /**
   * 把邮箱条目装配成**独立注入轮**（单件/合批消息 + 气泡 + 屏障会计 + unlink +
   * pipeLlmCall）。空集 = None（调用方逐字现状）。delegate 轨完成事件的续跑
   * replyTo 与 idle 直投腿同判据（屏障依赖唤醒轮的 Completed 复检投递集）。
   */
  def injectIdleTurn(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    baseState: AgentState,
    events: List[AgentCommand.ExternalEvent]
  )(using ctx: ActorContext[AgentCommand]): IO[Option[Behavior[AgentCommand]]] =
    if events.isEmpty then IO.pure(None)
    else
      val sid = baseState.sessionId
      val takenSub = takenSubagentSlots(events)
      val newOutstanding = math.max(0, baseState.execution.outstandingSubagentResults - takenSub)
      val continuationReplyTo: IO[Option[ActorRef[AgentEvent]]] =
        sid
          .filter(s =>
            AgentIdle.wakeContinuationApplies(
              isFlowNode = baseState.isFlowNode,
              sessionId = Some(s),
              eventSource = events.head.source,
              isDelegateSession = nebflow.core.delegate.DelegateSessions.isDelegateSession(Some(s))
            )
          )
          .fold(IO.pure(Option.empty[ActorRef[AgentEvent]]))(s =>
            resources.agentRegistry.get.map(_.get(s).flatMap(_.supervisorRef))
          )
      // logAgentEvent 是同步 Unit 面（AgentRegistryEmit trait）——语句位执行，不进 IO 链。
      logAgentEvent(
        agentDef,
        depth,
        sid,
        baseState.sessionName,
        "mailbox-drained",
        s"events=${events.size} takenSub=$takenSub outstanding=$newOutstanding sources=${events.map(e => s"${e.source}/${e.eventType}").mkString(",").take(200)}"
      )
      continuationReplyTo.flatMap { contReplyTo =>
          for
            _ <- if depth == 0 then sid.fold(IO.unit)(s => emitSessionBusy(baseState.wsSend, s, busy = true)) else IO.unit
            _ <- events.traverse_(e => visibility(resources, baseState, e, depth, waitingForBatch = false))
            _ <- touchBarrierSnapshot(resources, sid, newOutstanding, 0)
            _ <- unlinkConsumed(sid.getOrElse(""), events)
            result <- pipeLlmCall(
              agentDef,
              resources,
              depth,
              parentRef,
              baseState
                .withMessages(baseState.messages :+ mailboxMessage(events))
                .withOutstandingSubagentResults(newOutstanding)
                .withNextLoopTurn, // Block 3：邮箱注入 = 新 turn
              contReplyTo // idle 直投腿同位：屏障依赖唤醒轮的 Completed 复检投递集
            )
          yield Some(result)
        }
  end injectIdleTurn

  /**
   * W1 spawn/恢复扫箱：taken 残差重注 + pending 全取 → 空箱 None（逐字现状）；
   * 有件 = 注入轮。恢复重注件与首批共用一轮（都是恢复面）。
   */
  def recoverSweep(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    baseState: AgentState
  )(using ctx: ActorContext[AgentCommand]): IO[Option[Behavior[AgentCommand]]] =
    baseState.sessionId.fold(IO.pure(Option.empty[Behavior[AgentCommand]])) { sid =>
      for
        replayed <- replayTakenEvents(sid)
        drained <- drainIdleFace(sid)
        result <- injectIdleTurn(agentDef, resources, depth, parentRef, baseState, replayed ++ drained)
      yield result
    }

  /**
   * W4 解冻面：taken 残差重注 + pending 全取（解冻首拍必扫箱——冻结窗吞投递的
   * 机械化终点）。空箱返回原 state 逐字。
   */
  def resumeSweep(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    state: AgentState
  )(using ctx: ActorContext[AgentCommand]): IO[AgentState] =
    state.sessionId.fold(IO.pure(state)) { sid =>
      for
        replayed <- replayTakenEvents(sid)
        drained <- drainIdleFace(sid)
        events = replayed ++ drained
        _ <- IO.whenA(events.nonEmpty)(
          IO(
            logAgentEvent(
              agentDef,
              depth,
              state.sessionId,
              state.sessionName,
              "mailbox-drained-at-unfreeze",
              s"events=${events.size} replayed=${replayed.size}"
            )
          )
        )
        _ <- events.traverse_(e => visibility(resources, state, e, depth, waitingForBatch = false))
        _ <- unlinkConsumed(sid, events)
        takenSub = takenSubagentSlots(events)
      yield
        if events.isEmpty then state
        else
          state
            .withMessages(state.messages :+ mailboxMessage(events))
            .withOutstandingSubagentResults(math.max(0, state.execution.outstandingSubagentResults - takenSub))
    }
end MailboxDrain
