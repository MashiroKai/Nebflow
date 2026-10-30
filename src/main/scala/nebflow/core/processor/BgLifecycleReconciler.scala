package nebflow.core.processor

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.actor.AgentCommand
import nebflow.core.{AgentRuntimePort, EventSink}
import nebflow.core.tools.{BgHealth, BgNotifyDelivery, BgTaskLedger, BgTaskOutputStore, BgTaskReaper, BgTaskRegistry}
import nebflow.shared.{Defaults, NebflowLogger}

import scala.concurrent.duration.FiniteDuration

/**
 * BgLifecycleReconciler —— 后台任务健康轨 reconcile 控制器（后台任务生命周期批
 * P1，设计稿 20261030_background-task-lifecycle §3，台账 #1120）。
 *
 * 姊妹 HeldDeliveryWatchdog / FreezeScheduler / TaskStuckWatcher 三件套成四件套，
 * 工程约束逐条继承：错误自愈（scan 异常吞掉防 fiber 崩）、`>>`（by-name）递归防
 * 栈溢出、决策/执行分离（[[scanCore]] 纯函数 = spec 判红锚）。
 *
 * 职责（全部**观测/提醒/记账**，零杀伤——零超时红线编译层守门：[[ScanAction]]
 * 封闭类型无 Kill case）：
 *   ① 每拍扫 BgTaskRegistry 全表 + 输出行数（既有观测面，零新传感器），维护
 *      每任务自基线（头 K 分钟活跃间隔中位，冻结后不再变）与催注记账（进程内存，
 *      suspect 级重启丢可接受——§8-R2）。
 *   ② suspect（空闲 > max(基线×N, 10min) 档）：MarkSuspect（黄徽）+ Nudge 退避
 *      提醒（mailbox 级 ExternalEvent 提醒属主会话自查；10/20/40min 指数退避，
 *      HeldDeliveryFirstRetryMs 哲学）。429 风暴期抑制 Nudge、徽标照刷（§5）。
 *   ③ escalated（催注 k 次退避无进展 或 空闲 > 基线×M）：Escalate = 红徽
 *      （幂等升级戳，updateHealth escalated 保留首次 escalatedAtMs）+ 编排者通知
 *      （BgNotifyDelivery.deliverToRootOrInbox，root 活投/持久收件箱补投）+
 *      升级审计落盘（BgTaskLedger kind="escalation"，§8-R2，重启后首拍经
 *      [[BgTaskLedger.allEscalations]] 重建红徽视图）。
 *   ④ 进展信号（输出行增 / 行内活动戳增——远端心跳喂缝）⇒ 即时降回 healthy
 *      （重排不判死：停滞计数与升级戳清零，设计稿 §3.1）。
 *   ⑤ BadgeSync：每拍 syncHealthBadge（heldReceipts 同款：计数变化才广播
 *      bgHealth 帧；unread 格 = 升级通知未送达记账，P0 桩本批激活）。
 *
 * 观测信号来源（设计稿 §3.3，既有面）：输出行数 = BgTaskOutputStore.lineCounts；
 * 活动戳 = 行内 lastActivityMs（本地由本控制器经 RecordActivity 落缝，远端由
 * RemoteExecutor 心跳落缝）。CPU 增量腿 P1 未接入（需 jobId→Process 活体读面，
 * 登记为开放项）——10min 地板 + 误报代价=一次徽标零杀伤（R5）兜住。
 */
object BgLifecycleReconciler:

  private val logger = NebflowLogger.forName("nebflow.core.processor.bg-lifecycle")

  // ── ScanAction（封闭类型：编译层守零超时红线——**无 Kill case**，spec 经
  //    Mirror.SumOf 断言 case 集）──────────────────────────────────────────

  sealed trait ScanAction

  /** healthy→suspect 迁移（执行面 updateHealth(suspect)：行内 suspicionLevel 置位，面板黄徽）。 */
  final case class MarkSuspect(jobId: String, atMs: Long) extends ScanAction

  /** 进展 ⇒ 降回 healthy（重排不判死：停滞计数/升级戳清零；执行面同时消解 unread 记账）。 */
  final case class ProgressReset(jobId: String, atMs: Long) extends ScanAction

  /** 进展/心跳活动戳落行（快照帧 lastActivityMs 数据源；仅在比行内更新鲜时抬升）。 */
  final case class RecordActivity(jobId: String, atMs: Long) extends ScanAction

  /** suspect 退避催注（执行面 ExternalEvent 直投属主会话转录；attempt 从 1 计）。 */
  final case class Nudge(jobId: String, sessionId: String, description: String, attempt: Int, atMs: Long)
      extends ScanAction

  /** 升级（红徽幂等戳 + 编排者通知 + 审计落盘；仍不杀——杀是人工/编排者决策，§9-①）。 */
  final case class Escalate(jobId: String, sessionId: String, description: String, idleMs: Long, atMs: Long)
      extends ScanAction

  /** 徽标对账（执行面 syncHealthBadge：计数变化才广播 bgHealth 帧）。 */
  case object BadgeSync extends ScanAction

  // ── 决策输入/输出模型（纯面，零 IO）──────────────────────────────────────

  /** 单任务观测视图（执行面每拍自 registry + 输出行数构造）。 */
  final case class TaskView(
    jobId: String,
    sessionId: String,
    description: String,
    startedAtMs: Long,
    lineCount: Long,
    rowLastActivityMs: Long,
    rowHealth: String
  )

  /**
   * 每任务观测记账（reconciler 进程内存态——与 registry 本体同生命周期，重启即
   * 清；suspect 级重启丢可接受 §8-R2，escalated 级经账本重建）。
   */
  final case class Observation(
    /** 上次拍观测到的输出行数（行增 = 进展信号）。 */
    lastLines: Long = 0L,
    /** 权威最近活动戳（进展信号更新；初始化取 max(行内戳, 起始戳)）。 */
    lastActivityMs: Long = 0L,
    /** 基线窗内采样的活跃间隔（拍粒度近似——HeldDelivery 同款扫描器哲学）。 */
    gapsMs: Vector[Long] = Vector.empty,
    /** 基线是否已冻结（到龄即冻，之后不再采样）。 */
    baselineFrozen: Boolean = false,
    /** 冻结基线（ms）= gapsMs 中位；零采样冻结为 0 ⇒ 判据恒走地板档。 */
    baselineMs: Long = 0L,
    /** suspect 起始时刻（0 = 当前不在 suspect 轨；进展/降级清零）。 */
    suspectSinceMs: Long = 0L,
    /** 已发催注次数。 */
    nudges: Int = 0,
    /** 末次催注时刻。 */
    lastNudgeAtMs: Long = 0L
  )

  /** 判据参数集（稿 §9-③ 默认档；[[Params.fromDefaults]] 现读 prop，spec 可注入）。 */
  final case class Params(
    baselineWindowMs: Long,
    suspectBaselineFactor: Long,
    suspectMinIdleMs: Long,
    escalateBaselineFactor: Long,
    maxNudges: Int,
    nudgeFirstMs: Long,
    nudgeMaxBackoffMs: Long
  )

  object Params:
    def fromDefaults: Params =
      Params(
        baselineWindowMs = Defaults.BgLifecycleBaselineWindowMs,
        suspectBaselineFactor = Defaults.BgLifecycleSuspectBaselineFactor,
        suspectMinIdleMs = Defaults.BgLifecycleSuspectMinIdleMs,
        escalateBaselineFactor = Defaults.BgLifecycleEscalateBaselineFactor,
        maxNudges = Defaults.BgLifecycleMaxNudges,
        nudgeFirstMs = Defaults.BgLifecycleNudgeFirstMs,
        nudgeMaxBackoffMs = Defaults.BgLifecycleNudgeMaxBackoffMs
      )
  end Params

  /** 中位（偶数取下中位——拍粒度近似，无需插值复杂度）。 */
  def median(values: Vector[Long]): Long =
    if values.isEmpty then 0L
    else
      val sorted = values.sorted
      sorted(sorted.size / 2)

  /** 第 attempt 次催注的退避间隔：首催 ×2 递增，封顶（稿默认档 10/20/40min）。 */
  def nudgeDelayMs(attempt: Int, p: Params): Long =
    val raw = BigDecimal(p.nudgeFirstMs) * BigDecimal(2).pow(math.max(0, attempt - 1))
    math.min(raw.toLong, p.nudgeMaxBackoffMs)

  /** 第 attempt 次催注到点的累计偏移（自 suspectSince 起 = 各次退避之和）。 */
  def nudgeDueOffsetMs(attempt: Int, p: Params): Long =
    (1 to math.max(0, attempt)).map(nudgeDelayMs(_, p)).sum

  /**
   * 决策面（纯函数，spec 判红锚）：全表任务 × 观测记账 × 判据参数 →
   * (动作序列, 新观测记账)。逐任务决策表：
   *
   *   进展（行增 ∨ 行内活动戳增）⇒ RecordActivity 恒发；行非 healthy ⇒
   *     ProgressReset；停滞计数清零（重排不判死）。
   *   已 escalated ⇒ 零动作（幂等：升级由行状态挡重复）。
   *   空闲 > 基线×M（基线>0）⇒ Escalate（×10 腿，直达）。
   *   空闲 > max(基线×N, 地板) ⇒ 首次 MarkSuspect；其后按退避表催注
   *     （风暴抑制：到点不发、计数不进位，徽标照刷）；催注达 k 次且末催后
   *     再过一个封顶窗仍无进展 ⇒ Escalate（催注穷尽腿，HeldDelivery
   *     shouldExhaust 同构）。
   *   其余 ⇒ suspect 行回降 healthy（ProgressReset）。
   *
   * 返回的观测记账只含本拍在册任务（注销任务记账随表自动清）。
   */
  def scanCore(
    tasks: List[TaskView],
    obs: Map[String, Observation],
    now: Long,
    p: Params,
    stormActive: Boolean
  ): (List[ScanAction], Map[String, Observation]) =
    val actions = List.newBuilder[ScanAction]
    val nextObs = Map.newBuilder[String, Observation]

    tasks.foreach { t =>
      val o0 = obs.getOrElse(
        t.jobId,
        Observation(
          lastLines = t.lineCount,
          lastActivityMs = math.max(t.rowLastActivityMs, t.startedAtMs)
        )
      )
      val ageMs = now - t.startedAtMs
      val lineProgress = t.lineCount > o0.lastLines
      // 行内活动戳增长（远端心跳喂缝）也算进展——但排除自己上拍落缝造成的回读。
      val stampProgress = t.rowLastActivityMs > o0.lastActivityMs && t.rowLastActivityMs > 0
      val progressed = lineProgress || stampProgress

      // 基线冻结：到龄即冻（中位采样窗关闭），与是否进展无关。
      val preFrozen =
        if !o0.baselineFrozen && ageMs > p.baselineWindowMs then
          o0.copy(baselineFrozen = true, baselineMs = median(o0.gapsMs))
        else o0

      val (oNext, acts) =
        if progressed then
          val gap = now - o0.lastActivityMs
          val gaps1 =
            if !preFrozen.baselineFrozen && ageMs <= p.baselineWindowMs then preFrozen.gapsMs :+ gap
            else preFrozen.gapsMs
          val o1 = preFrozen.copy(
            lastLines = t.lineCount,
            lastActivityMs = now,
            gapsMs = gaps1,
            suspectSinceMs = 0L,
            nudges = 0,
            lastNudgeAtMs = 0L
          )
          val acts = List.newBuilder[ScanAction]
          if t.rowHealth != BgHealth.Healthy then acts += ProgressReset(t.jobId, now)
          acts += RecordActivity(t.jobId, now)
          (o1, acts.result())
        else if t.rowHealth == BgHealth.Escalated then
          // 已升级：保持终态轨到进展/注销（幂等——不再重复升级动作）。
          (preFrozen, Nil)
        else
          val idleMs = now - preFrozen.lastActivityMs
          val suspectIdleMs = math.max(preFrozen.baselineMs * p.suspectBaselineFactor, p.suspectMinIdleMs)
          val escalateIdleMs =
            if preFrozen.baselineMs > 0 then preFrozen.baselineMs * p.escalateBaselineFactor
            else Long.MaxValue
          if idleMs > escalateIdleMs then
            (preFrozen.copy(suspectSinceMs = 0L, nudges = 0, lastNudgeAtMs = 0L),
              List(Escalate(t.jobId, t.sessionId, t.description, idleMs, now)))
          else if idleMs > suspectIdleMs then
            if preFrozen.suspectSinceMs == 0L then
              (preFrozen.copy(suspectSinceMs = now), List(MarkSuspect(t.jobId, now)))
            else
              val attempt = preFrozen.nudges + 1
              val nudgeDue =
                preFrozen.nudges < p.maxNudges &&
                  now >= preFrozen.suspectSinceMs + nudgeDueOffsetMs(attempt, p)
              val escalateByNudges =
                preFrozen.nudges >= p.maxNudges &&
                  now >= preFrozen.lastNudgeAtMs + nudgeDelayMs(p.maxNudges, p)
              if escalateByNudges then
                (preFrozen.copy(suspectSinceMs = 0L, nudges = 0, lastNudgeAtMs = 0L),
                  List(Escalate(t.jobId, t.sessionId, t.description, idleMs, now)))
              else if nudgeDue && !stormActive then
                (preFrozen.copy(nudges = attempt, lastNudgeAtMs = now),
                  List(Nudge(t.jobId, t.sessionId, t.description, attempt, now)))
              else
                // 未到期，或 429 风暴抑制（徽标照刷：BadgeSync 每拍恒发）。
                (preFrozen, Nil)
          else
            val acts =
              if t.rowHealth == BgHealth.Suspect then List(ProgressReset(t.jobId, now))
              else Nil
            (preFrozen.copy(suspectSinceMs = 0L, nudges = 0, lastNudgeAtMs = 0L), acts)

      acts.foreach(actions += _)
      nextObs += (t.jobId -> oNext)
    }

    actions += BadgeSync
    (actions.result(), nextObs.result())
  end scanCore

  // ── 催注/升级载荷（纯文案面，spec 断言锚）───────────────────────────────

  /** suspect 催注提醒（进属主会话转录；零杀伤——只提醒自查，动作由会话/人决定）。 */
  def nudgePayload(jobId: String, description: String, attempt: Int): String =
    s"""<system-reminder>
       |[Background-task health] Your background task '$description' ($jobId) has shown no observable progress for a while (suspect, reminder #$attempt). The task is STILL RUNNING — nothing has been killed (zero-timeout rule). ACTION: check its output and confirm it is actually progressing; if it is stuck, cancel/restart it yourself or keep waiting if it is legitimately long-running. Repeated reminders escalate with exponential backoff and eventually reach the orchestrator.
       |</system-reminder>""".stripMargin

  /** escalated 升级通知（编排者可见；审计落盘同款文案单点）。 */
  def escalationPayload(jobId: String, sessionId: String, description: String, idleMs: Long): String =
    val minutes = idleMs / 60000L
    s"""[bg-lifecycle] ESCALATION: background task '$description' ($jobId) of session '$sessionId' has been idle for ~${minutes}m with no observable progress after repeated reminders. The task is STILL RUNNING — nothing was killed (zero-timeout red line; killing is a human/orchestrator decision). MANUAL ACTION: check the task output from the panel; cancel it if stuck, or let it keep running if legitimate."""

  // ── 执行面 ───────────────────────────────────────────────────────────────

  /** 每任务观测记账（进程内存态，重启即清）+ 首拍重建旗。 */
  private val observations: Ref[IO, Map[String, Observation]] = Ref.unsafe(Map.empty)
  private val bootstrapped: Ref[IO, Boolean] = Ref.unsafe(false)

  /** 周期扫描循环：scan → sleep(interval) → 递归。由 GatewayMain 以 fiber 启动。 */
  def run(
    resources: AgentRuntimePort,
    wsHub: EventSink,
    interval: FiniteDuration,
    stormActive: Option[IO[Boolean]] = None
  ): IO[Unit] =
    def loop: IO[Unit] =
      scan(resources, wsHub, stormActive).handleErrorWith(e =>
        logger.warn(s"BgLifecycleReconciler scan failed (will retry next cycle): ${e.getMessage}")
      ) *> IO.sleep(interval) >> loop
    loop

  /** 单轮扫描（执行面）：death-watch 活性收割 → 读账本快照 → scanCore 纯决策 → 逐动作执行 → 徽标对账。 */
  def scan(
    resources: AgentRuntimePort,
    wsHub: EventSink,
    stormActive: Option[IO[Boolean]] = None,
    now: Long = System.currentTimeMillis()
  ): IO[Unit] =
    if !Defaults.BgLifecycleEnabled then IO.unit
    else
      for
        _ <- bootstrapEscalationsIfNeeded
        // P2 death-watch（设计稿 §4.2 + 10-30 合并闸卡死病灶）：先收割活性死行——
        // 被收割的挂起会话由注入的终态通知唤醒，下拍起其行已出表（health 轨不
        // 再为死任务浪费判据）。零墙钟：活性 = 内核进程事实。
        reap <- BgTaskReaper.reapDead(Some(resources.actorSystem), Some(resources.agentRegistry), now)
        _ <- IO.whenA(reap.reaped.nonEmpty)(
          logger.warn(
            s"[bg-lifecycle] death-watch reaped ${reap.reaped.size} owner-lost task(s): " +
              reap.reaped.map { case (id, reason) => s"$id($reason)" }.mkString(", ")
          )
        )
        tasks <- BgTaskRegistry.allActiveTasks
        lineCounts <- BgTaskOutputStore.lineCounts
        storm <- stormActive.getOrElse(IO.pure(false))
        registry <- resources.agentRegistry.get
        p = Params.fromDefaults
        // 等待型任务才入健康轨：persistent=true（服务型 server）与 waitingFor 同
        // 过滤——它的完成本就不通知任何等待方，健康轨的「等它的人可见」语义不适用。
        views = tasks.filterNot(_.persistent).map(t =>
          TaskView(
            t.jobId,
            t.sessionId,
            t.description,
            t.startedAtMs,
            lineCounts.getOrElse(t.jobId, 0L),
            t.lastActivityMs,
            BgHealth.of(t)
          )
        )
        snap <- observations.get
        (actions, next) = scanCore(views, snap, now, p, storm)
        _ <- observations.set(next)
        _ <- execute(resources, wsHub, registry, actions)
      yield ()
  end scan

  /**
   * 重启重建（§8-R2「重启后一拍内由 reconciler 重建视图」）：首拍遍历会话账本的
   * escalated 审计行，对在册任务回放 updateHealth(escalated)（幂等戳保留落盘
   * 时刻）+ 恢复 unread 记账。一次性（bootstrapped 旗）；失败只 WARN。
   */
  private def bootstrapEscalationsIfNeeded: IO[Unit] =
    bootstrapped.get.flatMap { done =>
      if done then IO.unit
      else
        bootstrapped.set(true) *>
          BgTaskLedger.allEscalations.flatMap { rows =>
            rows.traverse_ { case (sessionId, e) =>
              BgTaskRegistry.updateHealth(e.jobId, BgHealth.Escalated, e.recordedAtMs).flatMap {
                case true =>
                  BgTaskRegistry.markEscalationsUnread(Set(e.jobId)) *>
                    logger.warn(
                      s"[bg-lifecycle-escalated] rebuilt after restart: job=${e.jobId} " +
                        s"session=$sessionId (escalatedAt=${e.recordedAtMs})"
                    )
                case false => IO.unit
              }
            }
          }.handleErrorWith(e => logger.warn(s"BgLifecycleReconciler escalation rebuild failed: ${e.getMessage}"))
    }

  /** 决策执行：行写缝 / 催注直投 / 升级三腿 / 徽标对账。全部 best-effort（失败只 WARN）。 */
  private def execute(
    resources: AgentRuntimePort,
    wsHub: EventSink,
    registry: Map[String, nebflow.actor.AgentRecord],
    actions: List[ScanAction]
  ): IO[Unit] =
    actions.traverse_ {
      case BadgeSync =>
        BgTaskRegistry.syncHealthBadge(wsHub).void

      case RecordActivity(jobId, atMs) =>
        BgTaskRegistry.recordActivity(jobId, atMs).void

      case MarkSuspect(jobId, atMs) =>
        logger.info(s"[bg-lifecycle] job=$jobId: idle past suspect threshold (max(baseline×N, floor)) — yellow badge") *>
          BgTaskRegistry.updateHealth(jobId, BgHealth.Suspect, atMs).void

      case ProgressReset(jobId, atMs) =>
        logger.info(s"[bg-lifecycle] job=$jobId: progress detected — back to healthy (重排不判死：停滞计数清零)") *>
          BgTaskRegistry.updateHealth(jobId, BgHealth.Healthy, atMs) *>
          BgTaskRegistry.markEscalationsRead(Set(jobId)).void

      case Nudge(jobId, sessionId, description, attempt, atMs) =>
        registry.get(sessionId) match
          case Some(rec) =>
            logger.info(
              s"[bg-lifecycle] nudge #$attempt to owner session $sessionId for job=$jobId " +
                s"('$description') — suspect past baseline, owner asked to check"
            ) *>
              (rec.ref ! AgentCommand.ExternalEvent(
                source = "bg-lifecycle",
                eventType = "nudge",
                payload = nudgePayload(jobId, description, attempt),
                correlationId = Some(s"bg-lifecycle-nudge-$jobId-$attempt")
              )).handleErrorWith(e => logger.warn(s"BgLifecycleReconciler: nudge to $sessionId failed: ${e.getMessage}"))
          case None =>
            // 属主会话已不在册（收殓/重启窗）：提醒无从投递——徽标仍可见，WARN 留痕。
            logger.warn(
              s"[bg-lifecycle] owner session $sessionId not live for job=$jobId — nudge skipped (badge still visible)"
            )

      case Escalate(jobId, sessionId, description, idleMs, atMs) =>
        escalate(resources, jobId, sessionId, description, idleMs, atMs)
    }

  /**
   * 升级三腿（执行面）：① 红徽幂等戳（updateHealth(escalated)，保留首次
   * escalatedAtMs——重复升级零漂移）② 编排者通知（root 活投 / 持久收件箱补投，
   * MergeQueue owner-gone 同款路由；投递路由决定 unread 记账）③ 审计落盘
   * （BgTaskLedger kind="escalation"，§8-R2）+ 响亮 WARN。仍不杀。
   */
  private def escalate(
    resources: AgentRuntimePort,
    jobId: String,
    sessionId: String,
    description: String,
    idleMs: Long,
    atMs: Long
  ): IO[Unit] =
    val payload = escalationPayload(jobId, sessionId, description, idleMs)
    logger.warn(
      s"[bg-lifecycle-escalated] job=$jobId session=$sessionId idleMs=$idleMs — red badge + " +
        "orchestrator notify + ledger audit (zero-timeout: nothing killed)"
    ) *>
      BgTaskRegistry.updateHealth(jobId, BgHealth.Escalated, atMs) *>
      BgTaskLedger.recordEscalation(sessionId, jobId, description, payload) *>
      BgNotifyDelivery
        .resolveRootSessionId(Some(resources.agentRegistry), sessionId, sessionId)
        .flatMap { rootId =>
          if rootId.trim.isEmpty then
            BgTaskRegistry.markEscalationsUnread(Set(jobId)) *>
              logger.warn(s"[bg-lifecycle-escalated] no responsible session for job=$jobId — notice dropped with this line")
          else
            val event = AgentCommand.ExternalEvent(
              source = "bg-lifecycle",
              eventType = "escalated",
              payload = payload,
              metadata = JsonObject(
                "jobId" -> jobId.asJson,
                "sessionId" -> sessionId.asJson,
                "description" -> description.asJson,
                "idleMs" -> idleMs.asJson,
                "escalatedBy" -> "bg-lifecycle".asJson
              ),
              correlationId = Some(s"bg-lifecycle-escalated-$jobId")
            )
            BgNotifyDelivery
              .deliverToRootOrInbox(Some(resources.actorSystem), Some(resources.agentRegistry), rootId, event)
              .flatMap {
                case BgNotifyDelivery.Route.Delivered(_) => BgTaskRegistry.markEscalationsRead(Set(jobId))
                case _ => BgTaskRegistry.markEscalationsUnread(Set(jobId))
              }
              .handleErrorWith(e =>
                BgTaskRegistry.markEscalationsUnread(Set(jobId)) *>
                  logger.warn(s"[bg-lifecycle-escalated] orchestrator notify failed for job=$jobId: ${e.getMessage}")
              )
        }
  end escalate

end BgLifecycleReconciler
