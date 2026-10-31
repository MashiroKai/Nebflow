package nebflow.core.tools

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.actor.*
import nebflow.core.EventSink
import nebflow.shared.{Defaults, NebflowLogger}

import java.util.concurrent.atomic.AtomicReference

/**
 * BgTaskReaper —— death-watch 收割器（后台任务生命周 P2 · 设计稿 20261030 §4.2
 * 「属主失联 ⇒ 代行终态记账 + 沿 bg-wait 链注入终态通知」+ 10-30 新病灶
 * 「进程已死 + 行残留 ⇒ 合并闸/会话闸永久不放行」的统一执行面）。
 *
 * == 病灶收口（实证：delegate-nebflow-e0478867 合并队列卡 3 小时）==
 *
 * delegate 回合尾 bg-wait 闸（BackoffSupervisor.delegateBgWaitGate）与节点完成闸
 * （NodeStarter 桥）都只认「registry 等待集非空 ⇒ hold」。任务进程已死而行残留
 * （会话收殓杀了进程树、完成回调随 fiber 取消绝路）时，等待集**永不清空**——
 * 合并轮的 Completed 被 hold，机械验收永不到账，队列干等 2h cap。本件给两处闸
 * 一个**活性复检**调用点：hold 前探活，Dead 行即收割（注销 + 代行终态记账 +
 * 注入终态通知），复检等待集再决 hold/放行。 correctness 只读内核进程事实
 * （proc.isAlive / 会话在册 / fiber 句柄图），**零墙钟**；调度 riding 既有事件
 * （Completed 复检）与既有 reconciler 拍频，零新定时器。
 *
 * == 收割动作（Dead 行；全部复用既有腿，零新机制）==
 *
 *  1. **取走行**（`BgTaskRegistry.unregisterReturning`——原子判空，收割与
 *     真完成/取消竞速时只有赢家落终态，first-terminal-wins）；
 *  2. **代行终态记账**：`BgTaskLedger.recordCompletion`（eventType=failed，
 *     载荷注明 owner-lost 原因 + 输出尾段）——补投腿经 payloadHead 判重，挂起
 *     会话唤醒时「等待对象已终止」必达（设计稿 §4.2-2）；
 *  3. **输出留存区翻转** cancelled + owner-lost 注记（详情卡可回看收割时刻
 *     为止的输出；幂等）；
 *  4. **面板帧**：backgroundTaskUpdate(cancelled) 经安装的全站广播口（
 *     [[installSink]]，GatewayMain boot 装配；未装配 = 跳过帧，不阻断收割）；
 *  5. **沿等待链注入**：对行属主会话（sessionId）与其根会话（rootSessionId，
 *     去重）经 `BgNotifyDelivery.deliverAgentEvent` 投「等待对象已终止」
 *     ExternalEvent——活会话直投唤醒（其回合尾复检 ⇒ 等待集已清 ⇒ 放行终判）；
 *     死会话走激活/持久补投既有三腿。注入的正确性语义 = 只把「死」送达，
 *     放行/终判仍由各闸口既有判定承担（设计稿 §4.2-3）。
 *
 * == 调用点 ==
 *
 *  - `BgLifecycleReconciler.scan`（每拍，全表巡检——设计稿 death-watch 本家）；
 *  - `BackoffSupervisor.delegateBgWaitGate`（hold 前复检——新病灶直接收口）；
 *  - `NodeStarter` 桥 Completed 分支（hold 前复检——节点闸同病灶同收口）。
 *
 * 全部 best-effort：单腿失败只 WARN，绝不拖垮主流程（簿记面纪律）。
 */
object BgTaskReaper:

  private val logger = NebflowLogger.forName("nebflow.bg-reaper")

  // ── 修法③：引擎 shutdown/restart 抑制旗 ─────────────────────────────────
  // 根因 docs/20261031_owner-lost.md §2-②（实证 639c9d15）：重启关机窗内 reaper
  // 探针命中「会话正被关机流程杀死」的任务 ⇒ `owner session gone` 误报。旗起期间
  // BgTaskReaper **不判死只告警**（整体跳过本轮扫描）——任务死于重启本身，不
  // 该记 owner-lost 假证。置位点 = GatewayMain 关停 guarantee 链入口（quit /
  // Ctrl+C / 热重启 handover / SIGTERM 全收敛到该链），进程级一次性旗（JVM 退出
  // 即弃，无需清除语义；spec 用 clearEngineShutdownForSpec 还原现场）。
  @volatile private var engineShuttingDown = false

  /** 引擎关停抑制旗置位（GatewayMain 关停 guarantee 链入口调用）。 */
  def markEngineShutdown(): Unit = engineShuttingDown = true

  /** spec 还原缝：清除抑制旗（生产无调用方——旗进程级一次性）。 */
  private[core] def clearEngineShutdownForSpec(): Unit = engineShuttingDown = false

  /** 收割报告（spec 断言面 / 日志）。 */
  final case class ReapReport(reaped: List[(String, String)], remaining: Int):
    def reapedJobIds: List[String] = reaped.map(_._1)

  /**
   * 全站广播面（port 模式——先例 BgInboxPort/BgSessionActivator）：reap 的
   * backgroundTaskUpdate(cancelled) 帧出口。GatewayMain boot 装配 wsHub；
   * spec 直调可不装（帧腿跳过，收割语义不受影响）。
   */
  private val sinkRef = new AtomicReference[Option[EventSink]](None)

  def installSink(s: EventSink): Unit = sinkRef.set(Some(s))

  def clearSink(): Unit = sinkRef.set(None)

  private[core] def broadcast(frame: Json): IO[Unit] =
    sinkRef.get match
      case None    => IO.unit
      case Some(s) => s.broadcast(frame).handleErrorWith(e => logger.warn(s"bg-reaper broadcast failed: ${e.getMessage}"))

  /**
   * 默认收割面：探 [[BgTaskLiveness.probe]] 全等待集，Dead 即收。
   * 返回报告（reaped = (jobId, reason) 列表）。
   *
   * 生产探针在 [[BgTaskLiveness.probe]] 之外再套**修法⑤ 输增长二次确认**：
   * `not owned by session` / `process dead` / `handle never landed` verdict
   * 落地收割前，复查该 job 输出缓冲近
   * [[Defaults.BgDeathWatchOutputGrowthConfirmMs]]（默认 5s）是否仍有 append——
   * 有增长 ⇒ 判 Alive 跳过一拍（真活着的任务不被误埋，实证 887c8244 当时正在
   * 输出编译日志；下一拍复查，输出停增长后照常收割，零永久免疫）。注桩面
   * [[reapWith]] 不套本确认（spec 判定表全隔离）。
   */
  def reapDead(
    system: Option[ActorSystem],
    registry: Option[Ref[IO, Map[String, AgentRecord]]],
    nowMs: Long = System.currentTimeMillis()
  ): IO[ReapReport] =
    reapWith(system, registry, nowMs) { row =>
      BgTaskLiveness.probe(row, nowMs).flatMap {
        case d @ BgTaskLiveness.Dead(reason) if OutputGrowthConfirmReasons(reason) =>
          BgTaskOutputStore.lastOutputAt(row.jobId).flatMap {
            case Some(atMs) if atMs > 0L && nowMs - atMs < Defaults.BgDeathWatchOutputGrowthConfirmMs =>
              logger
                .info(
                  s"[bg-reaper] second-confirmation: ${row.jobId} output still growing " +
                    s"(last line ${nowMs - atMs}ms ago) — skip reaping this beat (verdict was '$reason')"
                )
                .as(BgTaskLiveness.Alive: BgTaskLiveness.Verdict)
            case _ => IO.pure(d)
          }
        case other => IO.pure(other)
      }
    }

  /** 修法⑤：需输增长二次确认的判死分支（结构性死证 `owner session gone` 不适用）。 */
  private val OutputGrowthConfirmReasons: Set[String] =
    Set("not owned by session", "process dead", "handle never landed")

  /**
   * 探针注桩收割面（spec 直测锚）：探针函数可注入 ⇒ 判定表全覆盖无需真进程。
   * 生产恒走 [[reapDead]]（默认探针）。
   */
  private[core] def reapWith(
    system: Option[ActorSystem],
    registry: Option[Ref[IO, Map[String, AgentRecord]]],
    nowMs: Long
  )(probe: BgTaskRegistry.ActiveTask => IO[BgTaskLiveness.Verdict]): IO[ReapReport] =
    BgTaskRegistry.waitingTasks.flatMap { rows =>
      // 修法③：引擎关停抑制——旗起期间不判死只告警，整轮跳过（行留给进程退出
      // 的自然收殓；JVM 即弃，不存在「旗忘清」形态）。
      if engineShuttingDown then
        logger
          .warn(s"[bg-reaper] engine shutting down — death-watch scan suppressed (${rows.size} row(s) not probed)")
          .as(ReapReport(Nil, rows.size))
      else
        rows
          .traverse(row => probe(row).map(v => (row, v)))
          .flatMap { probed =>
            val dead = probed.collect { case (row, BgTaskLiveness.Dead(reason)) => (row, reason) }
            dead.traverse_ { case (row, reason) => reapOne(row, reason, system, registry, nowMs) }
              .as(ReapReport(dead.map { case (row, reason) => (row.jobId, reason) }, probed.size - dead.size))
          }
          .handleErrorWith(e => logger.warn(s"bg-reaper scan failed (best-effort): ${e.getMessage}").as(ReapReport(Nil, 0)))
    }

  /** 单行收割（取走式——行不在册 ⇒ no-op，竞速输家零副作用）。 */
  private def reapOne(
    row: BgTaskRegistry.ActiveTask,
    reason: String,
    system: Option[ActorSystem],
    registry: Option[Ref[IO, Map[String, AgentRecord]]],
    nowMs: Long
  ): IO[Unit] =
    BgTaskRegistry.unregisterReturning(row.jobId).flatMap {
      case None => IO.unit // 竞速：真完成/取消已取走 —— first-terminal-wins
      case Some(taken) =>
        val payload =
          s"[Background task terminated] '${taken.description}' (jobId=${taken.jobId}) — owner lost: $reason. " +
            "Treated as terminal (death-watch); the wait barrier over it releases."
        (for
          tail <- BgTaskOutputStore
            .read(taken.jobId, 0L)
            .map(out => out.map(o => o.output.takeRight(2000)).filter(_.trim.nonEmpty))
            .handleErrorWith(_ => IO.pure(Option.empty[String]))
          _ <- BgTaskLedger.recordCompletion(taken.sessionId, taken.jobId, taken.description, "failed", payload)
            .handleErrorWith(e => logger.warn(s"bg-reaper ledger record failed for ${taken.jobId}: ${e.getMessage}"))
          _ <- BgTaskOutputStore
            .finalizeTask(taken.jobId, "cancelled", None, Some(s"owner lost: $reason"))
            .handleErrorWith(_ => IO.unit)
          _ <- broadcast(
            Json.obj(
              "type"           -> "backgroundTaskUpdate".asJson,
              "sessionId"      -> taken.sessionId.asJson,
              "rootSessionId"  -> taken.rootSessionId.asJson,
              "taskId"         -> taken.jobId.asJson,
              "description"    -> taken.description.asJson,
              "status"         -> "cancelled".asJson,
              "finishedAt"     -> nowMs.asJson
            )
          )
          _ <- injectAlongChain(taken, payload, system, registry)
        yield ()).handleErrorWith(e => logger.warn(s"bg-reaper reap of ${taken.jobId} partially failed: ${e.getMessage}"))
    }

  /**
   * 沿 bg-wait 链注入「等待对象已终止」：属主会话 ∪ 根会话（去重）——
   * deliverAgentEvent 三腿（直投/激活/持久补投）全覆盖，活会话被唤醒后经
   * 回合尾复检自然放行；死会话的补投件由其复活轮消费。
   */
  private def injectAlongChain(
    row: BgTaskRegistry.ActiveTask,
    payload: String,
    system: Option[ActorSystem],
    registry: Option[Ref[IO, Map[String, AgentRecord]]]
  ): IO[Unit] =
    val targets = (List(row.sessionId) ++ Option(row.rootSessionId).toList).filter(_.trim.nonEmpty).distinct
    targets.traverse_ { sid =>
      val refIO: IO[Option[ActorRef[AgentCommand]]] =
        registry match
          case None      => IO.pure(None)
          case Some(reg) => reg.get.map(_.get(sid).map(_.ref))
      refIO.flatMap { refOpt =>
        BgNotifyDelivery.deliverAgentEvent(
          targetRef = refOpt,
          targetSessionId = Some(sid),
          system = system,
          registry = registry,
          projectName = None,
          flowNodeId = None,
          jobId = row.jobId,
          source = "background-task",
          eventType = "failed",
          payload = payload,
          metadata = io.circe.JsonObject("jobId" -> row.jobId.asJson, "ownerLost" -> true.asJson)
        )
      }
    }

end BgTaskReaper
