package nebflow.core.processor

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import nebflow.actor.{AgentRecord, AgentStatus}
import nebflow.core.AgentRuntimePort
import nebflow.core.delegate.DelegateNotify
import nebflow.core.project.{FlowMapEventLog, ProjectRuntime, ProjectRuntimeRegistry}
import nebflow.shared.{Defaults, NebflowLogger}

/**
 * **node-stuck dispatcher notification**（2026-09-11 批）——卡死/挂等节点的**向上通报**。
 *
 * 动机（现场缺口）：[[TaskStuckWatcher]] 的既有动作面全是「对卡死会话本身做什么」
 * （广播 `taskStuck` / L1 halt / L2 transport abort / L3 resume / Stop 重启）。**没有人
 * 告诉派发方**：一个 flow-map 节点挂了 20 分钟，它的 dispatcher（上游派发会话）只能
 * 靠轮询 `NodeList` 自己发现，或者用户盯着画布看。`WaitingForUser` 更彻底——它被
 * 卡死扫描**显式豁免**（人不是进程，等的合法态），于是「节点问了问题、人没回」这类
 * 挂等**零通报、零事件**，派发方与用户都看不见。
 *
 * 本模块只做两件事（**通报**，不改既有恢复语义）：
 *   1. 向**派发方**注入一条可执行通报（state / duration / activity / actionable options）；
 *   2. 往 flow-map 审计流写一条 [[FlowMapEventLog]] 行（可 grep、可对拍）。
 *
 * ── 判据（保守；两条轴，均为「可配置阈值」）──────────────────────────────
 *   - **挂等轴** `waiting-for-user`：`status == WaitingForUser` ∧
 *     `now - lastActivityMs > Defaults.NodeStuckNotifyWaitingMs`（默认 15min）。
 *     保守性来源 = 阈值本身就是「人的响应延迟窗」——低于它的挂等是**正常human-in-the-loop**，
 *     不通报（这正是既有扫描豁免 WaitingForUser 的理由，本模块只把「超窗挂等」重新
 *     可见化，不把它重新判死）。
 *   - **无进展轴** `no-progress` / `tool-timeout`：复用卡死扫描**同一判据**
 *     `assessDetailed`（agent 侧事件停滞 ∪ 工具相位超时，阈值默认 = 扫描阈值
 *     `thresholdMs`），且必须同时满足**保守闸门**：
 *       - `inflight > 0`（有在飞 LLM）⇒ 类④ provider-hang ⇒ **不通报**（LLM 层自管）；
 *       - 有新鲜正信号（`classify` ⇒ 类② false-positive）⇒ **不通报**——这是
 *         「productive long task 不得误报」的承重项：一个跑了 30min 但一直在产出
 *         stdout 的工具**不是**卡死；
 *       - 只有类① true-stuck / 类③ env-lost 才通报。
 *
 * ── 投递面（dispatcher → root 兜底）──────────────────────────────────────
 * 目标 = `rec.parentSessionId`（节点注册即由 `NodeStarter` 写成 dispatcher，缺席时落
 * `rootSessionId`）；**dispatcher 缺席/已死**（空串，或注册表里查无此行）⇒ 回退
 * `rec.rootSessionId`；两者都不可达 ⇒ 只写审计事件、不投递（`delivered=false`，通报
 * 绝不静默丢失：事件行仍在）。投递通道复用 `DelegateNotify.inject`（既有到活 actor 的
 * durable intake 通道，零新机制）。
 *
 * ── 只通报真节点 ────────────────────────────────────────────────────────
 * 候选必须先命中阈值，**再**做一次 flow-map 定位（[[probeNode]]）——查无节点 ⇒ 不通报、
 * 不写事件。取数面窄化与既有 `probeEnvLost` 同款（只对已超阈值的会话探一次）。
 *
 * ── 幂等（防通报风暴）────────────────────────────────────────────────────
 * 每会话记 `(cause, atMs)`；同一 cause 在冷却窗（= max(两条轴阈值)）内不重发；cause
 * 变化（如 no-progress → waiting）立即重发。冷却账本是**内存态**（宿主重启即丢），
 * 与 `stopCounts` / `ledger` 同族纪律——不得当持久状态消费。
 */
object NodeStuckNotify:

  private val logger = NebflowLogger.forName("nebflow.core.processor.node-stuck-notify")

  /** 注入源标记（`ImmediateInput.source` / `ProducerInput` 归因锚）。 */
  val Source: String = "node-stuck-notify"

  /** flow-map 审计事件类型（追加式注册先例见 [[FlowMapEventLog]] 头注）。 */
  val EventType: String = FlowMapEventLog.NodeStuckNotifiedType

  /** 通报原因（事件行 `cause=` 与注入文本同源取值域）。 */
  val CauseWaiting: String = "waiting-for-user"
  val CauseNoProgress: String = "no-progress"
  val CauseToolTimeout: String = "tool-timeout"

  /** 挂等轴的判据分支身份（与 [[TaskStuckWatcher.BranchMerged]] 同族的机器可读串）。 */
  val BranchWaiting: String = "waiting-for-user"

  /** 命中通报的节点身份（flow-map 面取数，写审计行/定位工作区用）。 */
  final case class NodeRef(workspace: String, project: String, nodeId: String, chainId: Option[String])

  /** 投递目标：`viaRoot=true` 表示 dispatcher 缺席、走 root 兜底。 */
  final case class Target(sessionId: String, viaRoot: Boolean)

  /** 幂等账本行（内存态）。 */
  final case class Sent(atMs: Long, cause: String)

  /** 一次通报的结果（测试与日志面消费；`delivered=false` 仅当无可达目标）。 */
  final case class Outcome(
    sessionId: String,
    cause: String,
    ageMs: Long,
    target: Option[Target],
    delivered: Boolean,
    eventSummary: String
  )

  /** 两条轴的可配置阈值（每次调用现读，kill-switch 先例）。 */
  def waitingThresholdMs: Long = Defaults.NodeStuckNotifyWaitingMs

  // ── 判据（纯函数，可独立单测）─────────────────────────────────────────────

  /**
   * 挂等轴判据：`WaitingForUser` ∧ 挂等时长 > 阈值。`lastActivityMs == 0`（旧注册点
   * 未设）⇒ 不判（与 [[TaskStuckWatcher.assessDetailed]] 的 `agentIdleMs` 同款防误判）。
   */
  def assessWaiting(
    rec: AgentRecord,
    now: Long,
    waitingThresholdMs: Long = waitingThresholdMs
  ): Option[TaskStuckWatcher.StuckAssessment] =
    if rec.status != AgentStatus.WaitingForUser then None
    else if rec.lastActivityMs <= 0L then None
    else
      val ageMs = now - rec.lastActivityMs
      if ageMs <= math.max(0L, waitingThresholdMs) then None
      else
        Some(
          TaskStuckWatcher.StuckAssessment(
            secs = ageMs / 1000,
            reason = s"waiting for user input ${ageMs / 1000}s > threshold ${waitingThresholdMs / 1000}s",
            branch = BranchWaiting,
            agentIdleMs = ageMs,
            toolPhaseMs = 0L,
            toolName = rec.currentToolName
          )
        )

  /**
   * 原因分流：挂等轴单列；无进展轴**只在保守闸门放行时**产出（类① true-stuck /
   * 类③ env-lost），类② false-positive / 类④ provider-hang ⇒ `None`（不通报）。
   */
  def causeOf(a: TaskStuckWatcher.StuckAssessment, cls: TaskStuckWatcher.StuckClassification): Option[String] =
    if a.branch == BranchWaiting then Some(CauseWaiting)
    else if cls.cls == TaskStuckWatcher.ClassTrueStuck || cls.cls == TaskStuckWatcher.ClassEnvLost then
      if a.branch == TaskStuckWatcher.BranchToolOverdue then Some(CauseToolTimeout) else Some(CauseNoProgress)
    else None

  /** 目标解析：dispatcher（活的）→ root（活的）→ None。 */
  def resolveTarget(rec: AgentRecord, registry: Map[String, AgentRecord]): Option[Target] =
    def live(sid: String): Boolean =
      sid.nonEmpty && sid != rec.sessionId && registry.contains(sid)
    val dispatcher = Option(rec.parentSessionId).map(_.trim).filter(_.nonEmpty)
    dispatcher
      .filter(live)
      .map(Target(_, viaRoot = false))
      .orElse(
        Option(rec.rootSessionId).map(_.trim).filter(_.nonEmpty).filter(live).map(Target(_, viaRoot = true))
      )

  /** 人类可读时长（`1h2m` / `12m30s` / `45s`）。 */
  def humanDuration(ms: Long): String =
    val totalSecs = math.max(0L, ms) / 1000L
    val h = totalSecs / 3600L
    val m = (totalSecs % 3600L) / 60L
    val s = totalSecs % 60L
    if h > 0 then s"${h}h${m}m" else if m > 0 then s"${m}m${s}s" else s"${s}s"

  /** 注入文本里的状态行（state 事实，非判据）。 */
  def stateLine(rec: AgentRecord, cause: String): String =
    if cause == CauseWaiting then s"WaitingForUser (parked on a human answer — AskUser / permission card)"
    else
      val tool = rec.currentToolName.map(t => s"tool '$t' in flight").getOrElse("no tool in flight")
      s"Processing ($tool)"

  /**
   * 通报文本：**state / duration / activity / actionable options** 四段齐备（本模块的
   * 产品契约，缺一段即视为未交付）。
   */
  def renderNotice(
    rec: AgentRecord,
    cause: String,
    ageMs: Long,
    assessment: Option[TaskStuckWatcher.StuckAssessment],
    nodeRef: NodeRef,
    target: Option[Target],
    cooldownMs: Long,
    thresholdMs: Long
  ): String =
    val activity =
      assessment match
        case Some(a) if a.branch != BranchWaiting =>
          val progress =
            val ago =
              if rec.lastProgressSignalAt > 0 then (System.currentTimeMillis() - rec.lastProgressSignalAt) / 1000
              else -1L
            if ago < 0 then "no progress signal (never seen)" else s"no fresh progress signal (last ${ago}s ago)"
          s"agent-side event ${a.agentIdleMs / 1000}s ago; ${progress}; tool phase ${a.toolPhaseMs / 1000}s " +
            s"(authorised ${TaskStuckJudgementLabel.effectiveToolPhaseSecs(rec)}s); branch=${a.branch}"
        case _ =>
          s"parked ${ageMs / 1000}s without agent-side events (last event ${ageMs / 1000}s ago); branch=$BranchWaiting"
    val targetLine =
      target match
        case Some(t) if t.viaRoot => s"${t.sessionId} (root fallback: no live dispatcher for this node)"
        case Some(t) => t.sessionId
        case None => "(none — no live dispatcher and no live root; audit event written, delivery skipped)"
    s"""[node-stuck] cause=$cause node=${nodeRef.nodeId} project=${nodeRef.project} session=${rec.sessionId} — no progress for ${humanDuration(
        ageMs
      )}.
       |state: ${stateLine(rec, cause)}
       |duration: ${humanDuration(ageMs)} (> threshold ${humanDuration(thresholdMs)})
       |activity: $activity
       |notified: ${targetLine}
       |options: (1) steer the node — reply to it with guidance (it resumes the moment it gets your message);
       |         (2) intervene — stop the node (AgentControl stop) and re-dispatch it from the flow map;
       |         (3) accept the wait — no action; this notice repeats at most every ${humanDuration(
        cooldownMs
      )}.""".stripMargin

  end renderNotice

  /** 审计行 summary：机器可读（gate 按 `cause=` / `target=` / `delivered=` 记账）。 */
  def renderEventSummary(
    rec: AgentRecord,
    cause: String,
    ageMs: Long,
    nodeRef: NodeRef,
    target: Option[Target],
    delivered: Boolean
  ): String =
    val tgt =
      target.map(t => s"${t.sessionId}${if t.viaRoot then ":root-fallback" else ":dispatcher"}").getOrElse("none")
    s"cause=$cause state=${rec.status} duration=${ageMs / 1000}s target=$tgt delivered=$delivered " +
      s"session=${rec.sessionId} node=${nodeRef.nodeId}"

  // ── 生产探针 ────────────────────────────────────────────────────────────

  /**
   * 会话 → 它所属项目 runtime 的 flow-map 节点（只读；查无 ⇒ `None`）。
   * 定位键 = 会话在哪个 store 里（`NodeEngine.findNodeForSession`，与
   * `runtimeOwning` 同款唯一键，不用 rootSessionId 反查）。fail-soft：探测链故障 ⇒
   * `None`（宁可不通报，绝不因探针故障报错或误通报）。
   */
  def probeNode(sessionId: String): IO[Option[NodeRef]] =
    ProjectRuntimeRegistry.all
      .flatMap { rts =>
        def go(rest: List[ProjectRuntime]): IO[Option[NodeRef]] =
          rest match
            case Nil => IO.pure(None)
            case rt :: tail =>
              rt.engine.ownsSession(sessionId).flatMap { owns =>
                if owns then
                  rt.store.snapshot.map { snap =>
                    nebflow.core.project.NodeEngine
                      .findNodeForSession(snap, sessionId)
                      .map(n => NodeRef(rt.store.baseWorkspace, rt.project.name, n.id, n.chainId))
                  }
                else go(tail)
              }
        go(rts)
      }
      .handleErrorWith(e =>
        logger
          .warn(s"node-stuck-notify: node probe for $sessionId failed: ${Option(e.getMessage).getOrElse(e.toString)}")
          .as(None)
      )

  // ── 扫描面 ──────────────────────────────────────────────────────────────

  /** 一次扫描轮的通报结果（供测试与日志消费）。 */
  def sweep(
    resources: AgentRuntimePort,
    registry: Map[String, AgentRecord],
    now: Long,
    /**
     * 无进展轴的判据阈值 = 卡死扫描阈值（与恢复链同源单点；默认
     * [[Defaults.StuckThresholdMs]]）。挂等轴另用 [[waitingThresholdMs]]。
     */
    noProgressThresholdMs: Long = Defaults.StuckThresholdMs,
    waitingThresholdMs: Long = waitingThresholdMs,
    sent: Ref[IO, Map[String, Sent]] = Ref.unsafe(Map.empty),
    nodeProbe: String => IO[Option[NodeRef]] = probeNode,
    eventAppend: (String, String, String, String, String, Option[String]) => IO[Unit] = FlowMapEventLog.append,
    inflight: String => IO[Int] = nebflow.core.LlmRuntimePort.inflightFor
  ): IO[List[Outcome]] =
    val cooldownMs = math.max(waitingThresholdMs, noProgressThresholdMs)
    // 顺序稳定（registry 是 Map，遍历序不定 ⇒ 通报/事件落盘序不定）——按 sessionId 定序。
    val candidates = registry.values.toList
      .filter(rec => rec.status == AgentStatus.WaitingForUser || rec.status == AgentStatus.Processing)
      .sortBy(_.sessionId)

    def decision(rec: AgentRecord): IO[Option[(String, Long, Option[TaskStuckWatcher.StuckAssessment])]] =
      if rec.status == AgentStatus.WaitingForUser then
        // 挂等态**只**走挂等轴：WaitingForUser 的推进者是人不是进程，agent 侧静默是
        // 合法态（R2 裁定的豁免在通报面同样成立——否则「挂在人工回答上 20min」会被
        // 无进展轴读成 no-progress 误报，本批 S4 用例实测捕获过这一形态）。
        IO.pure(assessWaiting(rec, now, waitingThresholdMs).map(a => (CauseWaiting, a.agentIdleMs, Some(a))))
      else
        TaskStuckWatcher.assessDetailed(rec, now, noProgressThresholdMs) match
          case None => IO.pure(None)
          case Some(a) =>
            inflight(rec.sessionId).map { inFlight =>
              val cls = TaskStuckWatcher.classify(rec, a, now, inFlight)
              causeOf(a, cls).map(cause => (cause, math.max(a.agentIdleMs, a.toolPhaseMs), Some(a)))
            }

    def notice(
      rec: AgentRecord,
      cause: String,
      ageMs: Long,
      assessment: Option[TaskStuckWatcher.StuckAssessment],
      nodeRef: NodeRef
    ): IO[Option[Outcome]] =
      val target = resolveTarget(rec, registry)
      val axisThresholdMs = if cause == CauseWaiting then waitingThresholdMs else noProgressThresholdMs
      for
        delivered <- target match
          case None => IO.pure(false)
          case Some(t) =>
            DelegateNotify.inject(
              resources.agentRegistry,
              t.sessionId,
              renderNotice(rec, cause, ageMs, assessment, nodeRef, target, cooldownMs, axisThresholdMs),
              Some(Source)
            )
        // **每次通报恰好一行审计事件**（gate「notification + event accounting」契约 =
        // 通报与事件一一对上；`delivered` 字段如实回填，投递失败也留行）。
        accounted = renderEventSummary(rec, cause, ageMs, nodeRef, target, delivered)
        _ <- eventAppend(nodeRef.workspace, nodeRef.project, nodeRef.nodeId, EventType, accounted, nodeRef.chainId)
        _ <- if delivered then sent.update(_ + (rec.sessionId -> Sent(now, cause))) else IO.unit
        _ <-
          if delivered then
            logger.warn(
              s"node-stuck-notify: node=${nodeRef.nodeId} session=${rec.sessionId} cause=$cause " +
                s"age=${ageMs / 1000}s → notified ${target
                    .map(t => t.sessionId + (if t.viaRoot then " (root fallback)" else ""))
                    .getOrElse("?")}"
            )
          else
            logger.warn(
              s"node-stuck-notify: node=${nodeRef.nodeId} session=${rec.sessionId} cause=$cause " +
                s"age=${ageMs / 1000}s → no live dispatcher/root; audit event written, delivery skipped"
            )
      yield Some(Outcome(rec.sessionId, cause, ageMs, target, delivered, accounted))
      end for
    end notice

    candidates
      .flatTraverse { rec =>
        decision(rec).flatMap {
          case None => IO.pure(Nil)
          case Some((cause, ageMs, assessment)) =>
            sent.get.map(_.get(rec.sessionId)).flatMap { prev =>
              val cooling = prev.exists(p => p.cause == cause && now - p.atMs < cooldownMs)
              if cooling then IO.pure(Nil)
              else
                nodeProbe(rec.sessionId).flatMap {
                  case None => IO.pure(Nil) // 非 flow-map 节点 ⇒ 不通报（保守）
                  case Some(nodeRef) => notice(rec, cause, ageMs, assessment, nodeRef).map(_.toList)
                }
            }
        }
      }
      .handleErrorWith(e =>
        logger
          .warn(s"node-stuck-notify: sweep failed (non-fatal): ${Option(e.getMessage).getOrElse(e.toString)}")
          .as(Nil)
      )
  end sweep

  /**
   * 通报文本里「已授权工具相位」的读数包装（复用 [[ToolStuckJudgment.effectiveToolPhaseMs]]
   * 单点，避免文案与判据两套口径）。
   */
  private object TaskStuckJudgementLabel:

    def effectiveToolPhaseSecs(rec: AgentRecord): Long =
      ToolStuckJudgment.effectiveToolPhaseMs(
        Defaults.ToolPhaseStuckMs,
        rec.currentToolDeadlineMs,
        Defaults.ToolDeadlineSlackMs
      ) / 1000L

end NodeStuckNotify
