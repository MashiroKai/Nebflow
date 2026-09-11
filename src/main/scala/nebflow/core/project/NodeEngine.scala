package nebflow.core.project

import cats.effect.{Deferred, Fiber, IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.actor.*
import nebflow.agent.*
import nebflow.core.NebflowLogger
import nebflow.core.PathUtil
import nebflow.core.entity.EntityLoader
import nebflow.core.node.NodeRunner
import nebflow.core.plugin.{PluginMcpManager, PluginRegistry, PluginsConfig}
import nebflow.core.presets.PresetStore
import nebflow.core.skill.SkillService
import nebflow.core.tools.{BgTaskRegistry, PresetResolver}
import nebflow.shared.Message

import scala.concurrent.duration.*

/**
 * NodeEngine —— 节点执行内核（#28 阶段 0，方案 §2.7 + §3.1）。
 *
 * 职责：
 * 1. spawn 节点 agent（NodeRunner 共享内核；sessionId = "node-<uuid>"，干净上下文、
 *    leaf、无记忆无持久会话；projectRoot = worktree 或工作区）
 * 2. 结果捕获（bridge actor + Deferred）：节点 agent 完成 → 其最终输出文本即结果
 *    （无 FlowReport/verdict/slots）→ 写节点 result（持久化）+ status=completed +
 *    ttlExpireAt=+24h → 沿 out 投递
 * 3. 投递（§2.7）：out=节点 → 下游 deliveredTo 记录 + barrier 归零启动下游（输入 =
 *    task 上下文 + 各上游 result 带 === Node <name> === 头）；out=Nebula →
 *    ImmediateInput("[Node '<name>' completed]\n<result>", source="node") 投根会话；
 *    out=null → 结果保留（接线后从活动/归档自动投递）；failed →
 *    ImmediateInput("[Node '<name>' failed]\n<err>")
 * 4. NodeCancel：运行中节点 → cancel 信号 → 停 agent + status=cancelled（不投递）
 * 5. blocked 终态（20260902 反馈重入设计 §1/§2）：completeNode 入口经 BlockedReader
 *    解析——最终输出以 BLOCKED 锚定 + JSON 体 → status=blocked（不结算下游）+
 *    FeedbackRouter 重入/升级路由；非 BLOCKED 开头 → completed 原路径无损降级
 *
 * 硬约束：不设运行超时（TaskStuckWatcher 10min 零活动兜底）；节点无 Mail 身份。
 */
class NodeEngine(
  val store: FlowMapStore,
  system: ActorSystem,
  resources: SharedResources,
  val wsSendFn: Json => IO[Unit],
  workspace: String,
  val rootSessionId: String,
  /** 项目名：Node 完成通知蓝气泡 header 第二段（NODE · <项目名> · <节点名> · <状态>）。 */
  val projectName: String,
  /** blocked 反馈档位（设计 §7.1）：auto（默认）| escalate-only。挂载时读 project.json。 */
  val feedbackMode: String = FeedbackRouter.ModeAuto,
  /** WS 事件推送（type → payload 载体）。 */
  emitEvent: (String, String, Json) => IO[Unit],
  /** 产物完整性闸门的 git 执行器（audit 20260905）：默认真实 git 只读命令；
    * spec 注 stub。闸门本体与 kill-switch 见 CompletionGate。 */
  gateRunner: CompletionGate.GitRunner = CompletionGate.defaultRunner,
  /** 节点完成闸等待总上限兜底（bgtask-completion-gate 批）：单个 bg 等待期超过
    * 此值 → 节点 failed+注明（防通知链断裂永久悬挂）。默认 Defaults.BgGateWaitTimeoutMs
    * （system prop nebflow.bgtask.gate.timeoutMs 可调）；spec 注小值验红。 */
  bgWaitCapMs: Long = nebflow.shared.Defaults.BgGateWaitTimeoutMs,
  /** 项目任务板 store（TaskBoard 批 2 §3a）：节点 buildInput 头部板块数据源；
    * None = 该项目无板（节点注入整块省略）。mount 时传入。 */
  val board: Option[TaskBoardStore] = None,
  /** 项目目标行来源（TaskBoard 批 2 §3b：ProjectDef.description；None 整行省略）。 */
  val projectGoal: Option[String] = None,
  /** dispatch-notify 触发通道覆盖（取消静默死锁修复批 R1，测试接缝）：Some 时取代
    * 真实触发链（ProjectRuntimeRegistry → ProjectActor.TriggerDispatcher）——spec
    * 用它捕获**通知文本原文**断言「cancelled 专属处置指引」。生产调用点零传参
    * （默认 None = 真实链，零行为变化）。 */
  notifyTriggerOverride: Option[String => IO[Unit]] = None,
  /** 完成门腿 1 开关（noderpt 批 A 段 2026-09-11）：后台任务存活是否拦节点终态化。
    * None = 现读 `Defaults.BgGateCompletionHold`（生产默认 **false = 封存**：不查
    * `BgTaskRegistry.waitingFor`，hold 代码与 armCap 逐字保留、打开即恢复旧行为）；
    * Some = spec 显式注入（避开全局 prop 的跨 suite 污染，测试矩阵双态各一条）。 */
  bgGateCompletionHold: Option[Boolean] = None,
  /** 完成门腿 2 开关（noderpt 批 A 段）：未申报 `node_report` 是否拦节点终态化。
    * None = 现读 `Defaults.NodeReportCompletionHold`（生产默认 **true**：未申报的
    * `Completed` 不终态化、节点保持 Running，由未申报提醒阶梯兜底）；Some = spec 注入。 */
  reportGateHold: Option[Boolean] = None,
  /** 终态延迟销毁窗口（noderpt 批 B 段 2026-09-11 作者裁定「一律存活 30 分钟再销毁」）：
    * None = 现读 `Defaults.NodeDestroyWindowMs`（生产默认 30min）；Some = spec 注入
    * （压到 0/秒级，避开全局 prop 的跨 suite 污染——`bgGateCompletionHold` 同款接缝）。 */
  destroyWindowMs: Option[Long] = None
):
  private val logger = NebflowLogger.forName("nebflow.node.engine")

  /** 完成门腿 1 生效值（每次判定现读；无在线翻转路由 ⇒ 生产侧改 prop 需重启宿主）。 */
  private def bgGateHoldEnabled: Boolean =
    bgGateCompletionHold.getOrElse(nebflow.shared.Defaults.BgGateCompletionHold)

  /** 完成门腿 2 生效值（同上，现读）。 */
  private def reportGateHoldEnabled: Boolean =
    reportGateHold.getOrElse(nebflow.shared.Defaults.NodeReportCompletionHold)

  /** 终态延迟销毁窗口生效值（现读；spec 走构造入参注入）。 */
  private def destroyWindowDurationMs: Long =
    destroyWindowMs.getOrElse(nebflow.shared.Defaults.NodeDestroyWindowMs)

  /** blocked 反馈路由器（§2.2/§2.3）：档位决策 + 项目级频率保护 + 重入/升级执行。
    * escalate 通道 = 本引擎的 deliverToNebula（eventType="blocked"，前端 label 自动 BLOCKED）；
    * escalateFailed 通道 = 同型 deliverToNebula（eventType="failed"）——P2 RetryCap
    * 升级专用（spec §2.3 G12：failed 终态真实显示，不冒充 BLOCKED）。 */
  private[project] val feedbackRouter: FeedbackRouter = new FeedbackRouter(
    projectName = projectName,
    workspace = workspace,
    feedbackMode = feedbackMode,
    escalate = (text, nodeName) => deliverToNebula(text, nodeName, NodeLifecycle.Blocked),
    escalateFailed = Some((text, nodeName) => deliverToNebula(text, nodeName, NodeLifecycle.Failed))
  )

  /** dispatch-notify 通道（2026-09-05 批接线 completion；2026-09-07 批接线 failed；
    * 取消静默死锁修复批 2026-09-10 接线 cancelled/R1）：
    * 节点终态结果回流分发器的单一通知入口（completion 查 notifyDispatcher flag；
    * failed/cancelled 不查——异常低频事件拓扑主人全知情；防循环+预算独立分账+
    * 窗口熔断+持久去重见 DispatchNotify）。挂接点四处：completedNode 尾部 +
    * deliverFailed 尾部直触发 + **cancelNode 尾部（R1）** + ProjectActor.TtlTick 周期补投。 */
  private[project] val dispatchNotify: DispatchNotify = DispatchNotify.forEngine(
    store, workspace, projectName, rootSessionId,
    // notice 语义（非 blocked）：预算耗尽时节点保持 completed，前端不可标 BLOCKED。
    escalate = (text, nodeName) => deliverToNebula(text, nodeName, DispatchNotify.NoticeEventType),
    emitUpdated = emitUpdated,
    triggerOverride = notifyTriggerOverride
  )

  /** 运行中节点 → cancel 信号（NodeCancel 用）。 */
  private val running: Ref[IO, Map[String, Deferred[IO, Unit]]] = Ref.unsafe[IO, Map[String, Deferred[IO, Unit]]](Map.empty)

  /** NodeMessage 注入链（20260905 机制批，作者裁定②）：running 节点 nodeId →
    * sessionId 活映射。runWithAgent 在飞登记时与 running 表同步置位、清理段
    * 同步移除——sendNodeMessage 据此定位节点会话 actor 发 ImmediateInput
    * （turn 边界 drain 既有基建：pendingImmediateInputs + TurnBoundaryDrains
    * .drainHead + CompactionQueueStore 压缩窗口保全，全部复用零重造）。
    * 无映射 = 会话已终结（裁定②竞态兜底入口：回退记录追加「注入未达」）。 */
  private[project] val nodeSessions: Ref[IO, Map[String, String]] =
    Ref.unsafe[IO, Map[String, String]](Map.empty)

  /** boot 恢复排队集（crash-recovery 批 2026-09-07）：快段已认领（翻 Pending）但慢段
    * 尚未 startNode(resume) 的节点 id——settleRunnableSweep 对其跳过（资格回扫的
    * 新鲜启动会与续跑竞速，CAS 裁决虽不双 spawn 但恢复降级为无 transcript 的全新重
    * 跑）。进程内 boot 生命周期状态：慢段逐节点认领前移除；慢段异常（节点级
    * handleErrorWith）后该节点回归正常 pending 池，settle 回扫以新鲜路径兜底启动
    * （R4 降级语义——恢复优先、收殓兜底）。 */
  private[project] val bootRecoveryQueue: Ref[IO, Set[String]] =
    Ref.unsafe[IO, Set[String]](Set.empty)

  /** 缺口4（2026-09-04 重复投递去重）：同 (identity, status) 60s 窗口内重复
    * Nebula 通知抑制——进程内时间窗 map（固定窗，顺路淘汰过期条目）。与 V8
    * nebulaDeliveredAt 账本**正交**：账本管跨重启 at-least-once（持久化，管
    * 「结果是否到达过 Nebula」），本表管秒级抖动（内存，管「同一通知短窗内
    * 重复轰炸」——09-03 ProjectCreate 合并回报三连投实证）。窗口内重复被抑制
    * 时仍 markNebulaDelivered，账本一致性不破坏。identity = nodeId（无 nodeId
    * 的 fire-and-forget 通道用 nodeName——escalate 各节点独立窗口）。 */
  private[project] val recentNebulaDeliveries: Ref[IO, Map[(String, String), Long]] =
    Ref.unsafe[IO, Map[(String, String), Long]](Map.empty)

  /** 节点是否正在运行（ProjectActor 状态查询用）。 */
  def isRunning(nodeId: String): IO[Boolean] = running.get.map(_.contains(nodeId))

  /** 取消运行中节点（NodeCancel/ProjectActor 转发）：触发 cancel 信号 → 停 agent。 */
  def cancelNodeById(nodeId: String): IO[Unit] =
    running.get.map(_.get(nodeId)).flatMap {
      case Some(sig) => sig.complete(()).void
      case None => IO.unit
    }

  /** 死会话 running 节点收殓（清场 c-③，20260903 03:04 清场误杀事故复盘）：
    * status=running 但无在飞执行 fiber（running 表无此节点——会话死于传输中断 /
    * 实例重启后内存态清空）→ 直接终态化 cancelled（cancelNode 全链；2026-09-07
    * 裁定：无 TTL 强制清，死亡现场保留主图待上层裁决）+ 审计事件，修复
    * NodeCancel「cancel signal sent」但状态不落终态的假成功。
    * 误杀防护（硬约束）：有在飞 fiber = 活会话（取消信号可达）→ Left 拒绝，
    * 本方法绝不触碰活会话节点。幂等：非 running → Left（不重复终态化）。 */
  def reapStaleRunning(nodeId: String): IO[Either[String, String]] =
    store.getNode(nodeId).flatMap {
      case None => IO.pure(Left(s"Node '$nodeId' not found"))
      case Some(n) if n.status != NodeLifecycle.Running =>
        IO.pure(Left(s"Node '${n.name}' is not running (status=${n.status}) — nothing to reap"))
      case Some(n) =>
        isRunning(nodeId).flatMap {
          case true =>
            IO.pure(Left(s"Node '${n.name}' has a live execution fiber — use the normal cancel signal, not reap"))
          case false =>
            logger.warn(s"Node '${n.name}' ($nodeId) reaped: status=running but no live execution fiber (dead session / instance restart)")
            FlowMapEventLog.append(workspace, projectName, nodeId, "reaped",
              "dead running session finalized as cancelled (no live execution fiber; NodeCancel reap)") *>
              // R2/R2-R7：reap 是**引擎发起**的收殓（无人主动取消）→ source=Engine，
              // reason 明确写出死会话判据（此前 cancelNode 无 reason 形参，此处文本
              // 只存在于 reaped 事件里，节点自身零原因）。
              cancelNode(nodeId,
                "dead-session reap: status=running but no live execution fiber (dead session / instance restart)",
                CancelSource.Engine)
                .as(Right(s"Node '${n.name}' reaped — dead running session finalized as cancelled (retained on map, no TTL)"))
        }
    }

  /** bg-wait 标注写点（僵尸收敛批 2026-09-06，作者「首要缺口 = 补显示」）：节点完成
    * 闸（bgtask-completion-gate 批）在持留等待后台任务时把 node `bgWait` 置为在途
    * 等待型任务快照、全部清空/终态化时清 None——NodePayload 条件字段随之带/不带，
    * 前端据此标「等待后台任务」徽标（与真僵尸区分，避免把设计内等待误判成
    * dead-session running）。仅对 running 节点写（fresh 守卫）；状态已变 → 拒写
    * （R2 竞态纪律），flow-map.json 不残留过期 bgWait。值未变 → 不写不 event
    * （幂等：终态化清 None 时若原本就 None，不重复发 nodeUpdated）。 */
  private def setNodeBgWait(nodeId: String, waiting: List[BgTaskRegistry.ActiveTask]): IO[Unit] =
    val desc =
      if waiting.isEmpty then None
      else Some(s"${waiting.size} background task(s): " + waiting.map(t => s"'${t.description}'").mkString(", "))
    store.mutate { st =>
      st.nodes.get(nodeId) match
        case Some(fresh) if fresh.status == NodeLifecycle.Running && fresh.bgWait != desc =>
          st.copy(nodes = st.nodes.updated(nodeId, fresh.copy(bgWait = desc)))
        case _ => st
    }.flatMap { s =>
      s.nodes.get(nodeId).filter(_.bgWait == desc).traverse_(emitUpdated)
    }

  /** 未申报计时起表（noderpt 批 A 段 2026-09-11）：**只置不重**——首次未申报在手时置
    * `reportPendingSince`（+ 计数归零）；已置则原样保留，后续 `Completed`（含提醒轮自身
    * 产生的 Completed）与 `NodeMessage` 重入都不改起点（代裁 4：否则计时可被无限拖延）。
    * 会话身份守卫：只在「本 fiber 的会话仍是该节点的当前会话」时置（`sessionRef` 同值），
    * 避免 resume/reactivate 换会话后旧 fiber 的判定落到新会话上。值真的变化才 emitUpdated
    * （载荷条件字段随之带；未变化不刷帧）。 */
  private def markReportPendingIfAbsent(nodeId: String, sessionId: String): IO[Unit] =
    IO(System.currentTimeMillis()).flatMap { now =>
      store.mutateWithResult { st =>
        st.nodes.get(nodeId) match
          case Some(fresh)
              if fresh.status == NodeLifecycle.Running
                && fresh.sessionRef.forall(_ == sessionId)
                && fresh.reportPendingSince.isEmpty =>
            (st.copy(nodes = st.nodes.updated(nodeId, fresh.copy(
              reportPendingSince = Some(now),
              reportReminderCount = 0))), true)
          case _ => (st, false)
      }.flatMap {
        case (s, true)  => s.nodes.get(nodeId).traverse_(emitUpdated)
        case (_, false) => IO.unit
      }
    }

  /** 未申报计时清表（唯一清表条件 = 该会话任一 `node_report` 申报；终态/挂起出口与新一轮
    * 翻转同点清零，防跨轮累计）。`sessionId = Some` 时只在「本 fiber 的会话仍是该节点当前
    * 会话」时清——resume 换会话后老 fiber 的收尾不得误清新会话的计时。 */
  private def clearReportPending(nodeId: String, sessionId: Option[String]): IO[Unit] =
    store.mutateWithResult { st =>
      st.nodes.get(nodeId) match
        case Some(fresh)
            if fresh.reportPendingSince.isDefined
              && sessionId.forall(sid => fresh.sessionRef.forall(_ == sid)) =>
          (st.copy(nodes = st.nodes.updated(nodeId, fresh.copy(
            reportPendingSince = None,
            reportReminderCount = 0))), true)
        case _ => (st, false)
    }.flatMap {
      case (s, true)  => s.nodes.get(nodeId).traverse_(emitUpdated)
      case (_, false) => IO.unit
    }

  // ── 终态延迟销毁窗口（noderpt 批 B 段 2026-09-11 作者裁定：一律存活 30 分钟再销毁）──
  //
  // 语义（作者裁定形态 (b)，逐条）：
  //   ① 终态时刻**只登记**，不杀进程：`destroyAt = T + destroyWindowMs` 落节点持久字段；
  //   ② 窗口内：进程/任务照跑、输出照写、**允许读取取证**，仅**禁止新 spawn**（新后台
  //      任务 / 新会话——拦截表 = `BgTaskRegistry.finalizedSessions`，落点见该对象头注）；
  //   ③ 到点由 `sweepDestroyWindows`（`ProjectActor.TtlTick` 30s）执行 `reclaimSession`
  //      （杀进程树 + 注销 registry + 逐条 `finalizeTask` + 释放 `ShellSession.sessions`
  //      条目 + WS `backgroundTaskUpdate(status="cancelled")` 帧）+ 清 `destroyAt`；
  //   ④ 挂起腿不登记（中途换会话，进程即时收割不变）；
  //   ⑤ `settleStaleRunningNodes` 的「bg 等待不算死」判据保留不动（代裁 6）；
  //   ⑥ 每条终态出口都留可事后对齐的痕迹：`destroyAt` 字段 + `node-destroy-scheduled`
  //      / `node-destroyed` 事件（failed/cancelled 与 completed/blocked 口径一致）。
  //
  // 与既有「即时收割」的差别只在**时点**：收殓动作集合逐字相同（同一个
  // `BgTaskRegistry.reclaimSession`），从「终态瞬间 fork」移到「窗口到期扫描」。

  /** 节点名下的会话清单（销毁窗口的收殓对象）：普通节点 = `sessionRef`；Loop 节点 =
    * worker（`sessionRef`）+ verify（`sessionRefVerify`）双会话（裁定 B 双会话贯穿）。 */
  private def destroyTargetSessions(n: NodeDef): List[String] =
    (n.sessionRef.toList ++ n.sessionRefVerify.toList).map(_.trim).filter(_.nonEmpty).distinct

  /** 终态销毁窗口登记（终态写点之后调用——**只登记不杀进程**）。
    *
    * 幂等：`destroyAt` 已置 ⇒ 不改字段、不重复发事件（同节点二次终态化/重复调用零副作用）。
    * 安全：节点**非终态**（未真正终态化 / 已被重激活回 Running）⇒ 拒登记——窗口只属于
    * 终态，绝不把 Running 节点的进程放进销毁计划。
    * 副作用：① 落 `destroyAt` 持久字段（跨宿主重启存活 ⇒ 到点仍由扫描腿兜底）；
    * ② 会话登记进 `BgTaskRegistry` 禁 spawn 表；③ `node-destroy-scheduled` 事件 + 日志。 */
  private def scheduleDestroy(nodeId: String, sessions: List[String], cause: String): IO[Unit] =
    IO(System.currentTimeMillis()).flatMap { now =>
      val at = now + destroyWindowDurationMs
      store.mutateWithResult { st =>
        st.nodes.get(nodeId) match
          case Some(fresh) if NodeLifecycle.Terminal.contains(fresh.status) && fresh.destroyAt.isEmpty =>
            (st.copy(nodes = st.nodes.updated(nodeId, fresh.copy(destroyAt = Some(at)))), true)
          case _ => (st, false)
      }.flatMap { case (s, registered) =>
        if !registered then IO.unit
        else
          s.nodes.get(nodeId).traverse_ { n =>
            val sids = sessions.map(_.trim).filter(_.nonEmpty).distinct
            sids.traverse_(sid => BgTaskRegistry.markSessionFinalized(sid, at)) *>
              emitUpdated(n) *>
              FlowMapEventLog.append(workspace, projectName, nodeId, NodeEngine.DestroyScheduledEventType,
                s"terminal destroy scheduled: session(s) ${sids.mkString(",")} kept alive for " +
                  s"${destroyWindowDurationMs / 1000}s (read-only evidence window; new spawns rejected) — " +
                  s"destroyAt=$at; cause: ${cause.take(160)}") *>
              logger.info(
                s"Node '${n.name}' ($nodeId) terminal (${n.status}) — destroy window opened: sessions " +
                  s"${sids.mkString(",")} stay alive until $at (${destroyWindowDurationMs / 1000}s)")
          }
      }
    }

  /** 窗口撤销（异常残留清点）：节点带着 `destroyAt` 却已非终态（窗口内被重激活/重跑）
    * ⇒ 清字段 + 解禁 spawn + `node-destroy-withdrawn` 事件。正常路径在翻转点已清零，
    * 本方法只作扫描腿的防御兜底。 */
  private def withdrawDestroyWindow(n: NodeDef, reason: String): IO[Unit] =
    val sids = destroyTargetSessions(n)
    store.mutateWithResult { st =>
      st.nodes.get(n.id) match
        case Some(fresh) if fresh.destroyAt.isDefined =>
          (st.copy(nodes = st.nodes.updated(n.id, fresh.copy(destroyAt = None))), true)
        case _ => (st, false)
    }.flatMap { case (s, cleared) =>
      if !cleared then IO.unit
      else
        sids.traverse_(BgTaskRegistry.reopenSession) *>
          s.nodes.get(n.id).traverse_(emitUpdated) *>
          FlowMapEventLog.append(workspace, projectName, n.id, NodeEngine.DestroyWithdrawnEventType,
            s"destroy window withdrawn (node no longer terminal): $reason") *>
          logger.warn(s"Node '${n.name}' (${n.id}) destroy window withdrawn: $reason")
    }

  /** 终态销毁窗口扫描腿（`ProjectActor.TtlTick` 30s 驱动，与 `settleStaleRunningNodes` /
    * `remindUnreportedNodes` 同族——复用既有心跳点零新调度器）。
    *
    * 两件事：① **表项自愈**——带 `destroyAt` 的节点逐个重建禁 spawn 表（宿主重启后
    * 内存表为空，本腿一拍内恢复，禁 spawn 面不因重启长期失守）；② **到点销毁**——
    * `destroyAt <= now` 且节点仍为终态 ⇒ `destroyNodeSessions`；节点已非终态 ⇒
    * `withdrawDestroyWindow`（窗口随重激活撤销）。
    *
    * 幂等：字段已清 ⇒ 不再入选（二次调用零副作用）；`reclaimSession` 本体亦幂等
    * （会话无进程/无任务时 no-op、已注销二次无副作用）。best-effort：单节点失败只 WARN，
    * 不影响其它节点与后续 TTL sweep。 */
  def sweepDestroyWindows(): IO[Unit] =
    store.snapshot.flatMap { s =>
      val registered = s.nodes.values.toList.filter(_.destroyAt.isDefined)
      // ① 表项自愈（重启后重建；幂等覆盖）
      registered.traverse_ { n =>
        val at = n.destroyAt.getOrElse(0L)
        destroyTargetSessions(n).traverse_(sid => BgTaskRegistry.markSessionFinalized(sid, at))
      } *>
        // ② 到点处置（按 snapshot 逐个复核最新状态，避免用陈旧快照做终态判断）
        IO(System.currentTimeMillis()).flatMap { now =>
          registered.filter(_.destroyAt.exists(_ <= now)).traverse_ { snap =>
            store.snapshot.flatMap(_.nodes.get(snap.id) match
              case None => IO.unit
              case Some(fresh) if fresh.destroyAt.isEmpty => IO.unit // 已被其它路径处置
              case Some(fresh) if !NodeLifecycle.Terminal.contains(fresh.status) =>
                withdrawDestroyWindow(fresh,
                  s"node status is '${fresh.status}' at window expiry (reactivated/re-run inside the window) — spawn ban lifted")
              case Some(fresh) => destroyNodeSessions(fresh)
            )
          }
        }
    }.handleErrorWith(e => logger.warn(s"destroy-window sweep failed: ${e.getMessage}"))

  /** 到点对称收殓（③ 的执行体）：对节点名下全部会话执行 `reclaimSession`
    * （= 杀进程树 + 注销 BgTaskRegistry + 逐条 `BgTaskOutputStore.finalizeTask("cancelled")`
    * + 释放 `ShellSession.sessions` 条目 + WS `backgroundTaskUpdate(status="cancelled")` 帧，
    * 见 `shell.scala#killSessionProcesses` 与 `BgTaskRegistry#reclaimSession`），
    * 再清 `destroyAt` + 写 `node-destroyed` 事件。
    *
    * 幂等双保险：字段清点用「destroyAt 与本次登记值相同」的 CAS（并发同拍只留一个赢家）；
    * 输家 `reclaimSession` 亦为 no-op。清点后**禁 spawn 表项保留**（死会话不可复活——
    * 合法复活唯一入口 = 节点翻转 Running 时的 `BgTaskRegistry.reopenSession`）。 */
  private def destroyNodeSessions(n: NodeDef): IO[Unit] =
    val at = n.destroyAt.getOrElse(0L)
    val sids = destroyTargetSessions(n)
    for
      _ <- sids.traverse_(sid => BgTaskRegistry.markSessionFinalized(sid, at))
      _ <- sids.traverse_ { sid =>
        BgTaskRegistry.reclaimSession(Some(sid), wsSendFn, rootSessionId)
          .handleErrorWith(e =>
            logger.warn(s"Node '${n.name}' (${n.id}) destroy-window reclaim failed for session '$sid': ${e.getMessage}"))
      }
      (s, cleared) <- store.mutateWithResult { st =>
        st.nodes.get(n.id) match
          case Some(fresh) if fresh.destroyAt.contains(at) =>
            (st.copy(nodes = st.nodes.updated(n.id, fresh.copy(destroyAt = None))), true)
          case _ => (st, false)
      }
      _ <- IO.whenA(cleared) {
        s.nodes.get(n.id).traverse_ { fresh =>
          emitUpdated(fresh) *>
            FlowMapEventLog.append(workspace, projectName, n.id, NodeEngine.DestroyedEventType,
              s"terminal destroy window expired: session(s) ${sids.mkString(",")} reclaimed " +
                s"(processes killed + bg tasks finalized + shell sessions released), destroyAt=$at") *>
            logger.info(
              s"Node '${n.name}' (${n.id}) destroy window expired — reclaimed session(s) ${sids.mkString(",")}")
        }
      }
    yield ()

  /** 终态写点的计时清表纯函数（noderpt 批 A 段 2026-09-11）：**终态无计时语义** ⇒
    * 状态写点与计时字段同事务清零，持久层不残留「终态节点带待申报计时」的误导态。
    * 为什么不能只靠 [[clearReportPending]]（run fiber 收尾的 `cleanupRunTables`）：
    * 存在**不经 run fiber** 的终态写点——boot 期 `reapStaleRunning`（死会话收殓）、
    * `autoFailDeadRunning`、`mergeBlockedByUpstreamFailure`、NodeCancel 收殓等，
    * 它们的节点从没有 fiber 可跑 finalizer ⇒ 计时会随节点进归档（隔离实例实跑读
    * 数：reap 后归档的 cancelled 节点仍带 `reportPendingSince`/`reportReminderCount`）。
    * 值已清 ⇒ 原样返回（零漂移，不发生无谓写）。
    *
    * **public**（noderpt 批 F3，2026-09-11 复核 D3 修复）：第 7 个写点在另一模块
    * （`NodeTools` 的 `NodeEdit abandon`，`:1181`）⇒ 提为公共单点，跨模块复用同一判据，
    * 防第 8 个写点再漏。纯函数（无 IO、不读 store）——调用方在自己的 mutate 事务内联用。 */
  def withoutReportPending(n: NodeDef): NodeDef =
    if n.reportPendingSince.isEmpty && n.reportReminderCount == 0 then n
    else n.copy(reportPendingSince = None, reportReminderCount = 0)

  /** 在飞登记三表的对称清理（僵尸收敛批 2026-09-06 清理硬化，根因报告漏洞①）：
    * running / nodeSessions / agentRegistry 三表在 runWithAgent 内登记后，只能由该
    * fiber 自己在 race 落定后清理——fiber 崩溃/被外部 cancel/悬死在 race 时三表泄漏
    * （制造「假活会话」canary → isRunning 恒 true → 误杀防护被击穿，
    * reapStaleRunning/abandon/NodeCancel 全部拒绝，不可回收僵尸）。本方法作为
    * runWithAgent 整体 `.guarantee` finalizer：任意退出路径（正常/崩溃/异常/cancel）
    * 都对称移除。身份感知（防误删竞发赢家）：只在 running 表条目是**本 fiber 的
    * cancelSig** 时移除（LostRace/Aborted 败方自己的 sig 已被分支移除，此处 no-op；
    * 赢家条目保留）；nodeSessions 只在映射到本 sessionId 时移除（nodeId 是共享键，
    * 盲删会误删赢家条目）；agentRegistry 只移除本 sessionId。与既有清理段
    * （:947-953）同点幂等（先到先清，后到 no-op）。 */
  private def cleanupRunTables(nodeId: String, sessionId: String, cancelSig: Deferred[IO, Unit]): IO[Unit] =
    running.modify { m =>
      if m.get(nodeId).exists(_.eq(cancelSig)) then (m - nodeId, ())
      else (m, ())
    } *>
      nodeSessions.update { m =>
        if m.get(nodeId).contains(sessionId) then (m - nodeId) else m
      } *>
      resources.agentRegistry.update(_ - sessionId) *>
      // blocked 结构化信号批（20260909 spec §5.2 #5④；同日泛化 NodeReportRegistry）：
      // 登记表对称清理——cancelled/failed/异常退出路径未消费的申报在此兜底移除
      // （completed 路径已 drain，此处幂等 no-op）。与 running/nodeSessions 同点清理纪律。
      NodeReportRegistry.remove(sessionId) *>
      // 未申报计时对称清理（noderpt 批 A 段 2026-09-11）：终态/挂起出口清表不累计
      // （挂起恢复后按新会话重新起表；sessionId 守卫防误清 resume 后新会话的计时）。
      clearReportPending(nodeId, Some(sessionId))

  /** 死会话 running 节点的自动收敛（僵尸收敛批 2026-09-06；与 NodeCancel-stale /
    * abandon 的人力收殓区分——本方法走**自动** watchdog 路径）。收敛目标取 **failed**
    * 而非 reapStaleRunning 的 cancelled：cancelled 不投递不通知（cancelNode 不调
    * deliverFailed/settleDeps）且不可重激活——无人知情、无人可修；failed 沿
    * deliverFailed 触发分发器通知（附停等等待者清单，wf1cde E-③）且可 reactivate
    * 修复重跑——D5 零结算下下游停等可见，上游修好后等待者自动续跑。fresh 守卫
    * （R2）：只在 `status==Running` 时收敛——节点已终态/状态已变 → 拒写（并发
    * 完成/取消不被本路径覆盖成 failed）。审计事件独立（dead-session-reaped），
    * 与既有 reaped/abandoned 区分。 */
  private def autoFailDeadRunning(nodeId: String, err: String): IO[Unit] =
    for
      now <- IO(System.currentTimeMillis())
      s <- store.mutate { st =>
        st.nodes.get(nodeId) match
          case Some(fresh) if fresh.status == NodeLifecycle.Running =>
            st.copy(nodes = st.nodes.updated(nodeId, withoutReportPending(fresh.copy(
              status = NodeLifecycle.Failed,
              result = Some(err),
              completedAt = Some(now),
              // 2026-09-07 作者裁定：failed/cancelled 无 TTL 强制清（同 blocked 既
              // 有语义）——死亡现场保留主图待上层裁决取消/重跑，不静默消失。
              ttlExpireAt = None))))
          case _ => st // 已终态/消失/状态已变 → 拒写（R2 竞态纪律）
      }
      _ <- s.nodes.get(nodeId) match
        case Some(failed) if failed.status == NodeLifecycle.Failed =>
          emitWithChain("nodeUpdated", nodeId, NodePayload.buildNodeJson(failed, now)) *>
            logger.warn(s"Node '${failed.name}' auto-finalized failed (dead session): ${err.take(200)}") *>
            FlowMapEventLog.append(workspace, projectName, nodeId, "dead-session-reaped",
              s"dead-session node auto-converged to failed: ${err.take(220)}") *>
            deliverFailed(failed, err) *>
            // R3：与 failNode 同款的终态写点即时 barrier 告警（failed 侧仅此新增）。
            checkBarriersNow(failed.id, cause = "failed")
        case _ => IO.unit
    yield ()

  /** 死会话 running 周期对账（僵尸收敛批 2026-09-06；ProjectActor.TtlTick 30s 驱动
    * ——复用既有心跳点零新调度器，与 settleRunnableSweep 同族）。对每个 status=Running
    * 节点做死会话判定，死者自动收敛 failed（autoFailDeadRunning）。判定口径（作者
    * 2026-09-06 裁定：收敛必须以「可证明无活会话 且 无在途后台任务」触发）：
    *   - 活会话 = nodeSessions(sessionId) 在 agentRegistry 有记录（agent 已 spawn 且
    *     未清理）——有记录即视为活（保守，宁可漏一期不可误杀）；
    *   - nodeSessions 无映射 = 会话未登记/登记已清（重启内存清空 / 翻转即崩溃於登记
    *     前）→ 用 running 表再判：无在飞 fiber（running 表无此节点）才收敛（有 fiber
    *     却无 nodeSessions 是异常带，保守不收敛）；
    *   - 记录缺失但过了 spawn 窗口（StaleSpawnGraceMs）→ 判死（fiber 已死未登记
    *     agent，而非刚启动的 spawn 窗口内）；
    *   - 「无在途后台任务」= BgTaskRegistry.waitingFor(sessionId) 为空——非空 = 设计
    *     内等待后台任务（节点按设计保持 running，**不得收敛/提前结算**——作者红线，
    *     禁把「等待后台任务」当 bug 提前结算）。
    * 不误杀活会话：registry 有活记录 / waitingFor 非空 → 一律跳过。
    *
    * noderpt 批 A 段（2026-09-11 代裁 6）：判据**原样保留**（bg 等待不算死），只补
    * 「在事件里标出该态节点供人监督」——命中该豁免时单发一条
    * [[NodeEngine.DeadSessionBgWaitEventType]] 事件（进程内每节点一条，防 30s 节拍刷屏）。 */
  def settleStaleRunningNodes(): IO[Unit] =
    store.snapshot.flatMap { s =>
      s.nodes.values.filter(_.status == NodeLifecycle.Running).toList
        .filterA { n =>
          val deadSession: IO[Boolean] =
            nodeSessions.get.map(_.get(n.id)).flatMap {
              case None =>
                running.get.map(m => !m.contains(n.id))
              case Some(sid) =>
                resources.agentRegistry.get.map { reg =>
                  val pastSpawnWindow =
                    n.startedAt.exists(st => System.currentTimeMillis() - st > NodeEngine.StaleSpawnGraceMs)
                  !reg.contains(sid) && pastSpawnWindow
                }
            }
          deadSession.flatMap {
            case false => IO.pure(false)
            case true =>
              nodeSessions.get.map(_.get(n.id)).flatMap {
                case Some(sid) =>
                  BgTaskRegistry.waitingFor(sid).flatMap {
                    case waiting if waiting.nonEmpty =>
                      markDeadSessionBgWaitExemption(n, sid, waiting).as(false)
                    case _ => IO.pure(true)
                  }
                case None => IO.pure(true)
              }
          }
        }
        .flatMap { zombies =>
          zombies.traverse_ { z =>
            autoFailDeadRunning(z.id,
              "node session dead (no live session, no in-flight background task) — auto-converged to failed by dead-session watchdog")
          }
        }
    }

  // ── 未申报提醒阶梯（noderpt 批 A 段 2026-09-11 作者裁定）────────────────────
  //
  // 背景：完成门腿 2 默认开（`reportGateHoldEnabled`）⇒ 节点交棒（桥收到 Completed）
  // 而**未**调 node_report 时不终态化——节点保持 Running、会话存活。本腿是那条
  // 「保持 Running」的兜底提醒：**永不判 failed、永不上报失败、永不杀进程或会话**
  // （相对设计稿 §4.2「30min 判 failed」的核心改判），只提醒 + 留痕，等人工处置。
  //
  // 时序（作者裁定逐字）：10min / 30min / 1h / 2h / 4h + 此后每 4h 一拍、单节点上限
  // 8 拍；第 8 拍后**不再注入**（每拍都是一次完整 LLM turn，封顶为省 token），但
  // **事件不停**——改每 `quiescentIntervalMs`（默认 4h）一条 `node-report-missing`
  // （stage=quiescent、reminderCount 不递增），直到终态/挂起出口/申报清表。
  //
  // 计时跨宿主重启存活：起点落**节点持久字段** `reportPendingSince`（落盘），扫描腿在
  // `ProjectActor.TtlTick`（30s 节拍）上跑——禁用 per-node fiber 计时器（重启即丢）。

  /** quiescent 档写事件节流记账（nodeId → 上次写事件的 ts）。内存态：重启后每个节点
    * 至多多写一条 quiescent 事件（幂等冗余，不丢监督面）；阶梯拍数的幂等不依赖本表
    * （那是 `reportReminderCount` 的持久 CAS）。 */
  private val quiescentNotified: Ref[IO, Map[String, Long]] =
    Ref.unsafe[IO, Map[String, Long]](Map.empty)

  /** 死会话 bg-wait 豁免留痕单发记账（nodeId 集合，进程内一次）。 */
  private val bgWaitExemptNotified: Ref[IO, Set[String]] = Ref.unsafe[IO, Set[String]](Set.empty)

  /** 释放唤醒单发记账（nodeId → 已唤醒的 `reportPendingSince` 值，进程内）：同一**未申报
    * 期**只注入一次唤醒轮——防 TtlTick 30s 拍连发 LLM turn。新一轮翻转/新会话重排起表
    * （新起点值）自动重新允许；进程重启后表空 ⇒ 至多再唤醒一次（幂等冗余，不丢监督面）。 */
  private val releaseWakeSent: Ref[IO, Map[String, Long]] = Ref.unsafe[IO, Map[String, Long]](Map.empty)

  /** 代裁 6「在事件里标出该态节点供人监督」：`settleStaleRunningNodes` 命中
    * 「死会话 + 仍在途后台任务」豁免时单发一条留痕（判据本身原样保留——bg 等待不算死）。 */
  private def markDeadSessionBgWaitExemption(
    node: NodeDef,
    sessionId: String,
    waiting: List[BgTaskRegistry.ActiveTask]
  ): IO[Unit] =
    bgWaitExemptNotified.modify(s => if s.contains(node.id) then (s, false) else (s + node.id, true)).flatMap {
      case false => IO.unit
      case true =>
        FlowMapEventLog.append(workspace, projectName, node.id, NodeEngine.DeadSessionBgWaitEventType,
          s"dead-session node kept Running (bg-wait exemption): ${waiting.size} background task(s) still " +
            s"in flight (${waiting.map(t => s"'${t.description}'").mkString(", ")}) — not reaped, needs human supervision") *>
          logger.warn(
            s"Node '${node.name}' (${node.id}) session '$sessionId' looks dead but still owns ${waiting.size} " +
              "in-flight background task(s) — kept Running for human supervision (bg-wait exemption, no auto-convergence)")
    }

  /** 未申报提醒阶梯扫描腿（ProjectActor.TtlTick 30s 驱动，与 `settleStaleRunningNodes` /
    * `settleRunnableSweep` 同族——复用既有心跳点零新调度器）。
    *
    * 逐节点判据（作者裁定口径）：`status == Running ∧ reportPendingSince 已置`；
    *   - 申报槽非空（**非消费读**）⇒ 申报已到但桥可能未观测（`NodeMessage` 重入轮不产
    *     生 `Completed`）⇒ **唤醒桥复检放行**（[[wakeBridgeForRelease]]，不清表不注入提醒）；
    *   - `elapsed ≥ 第 N 档 ∧ N > reportReminderCount` ⇒ 发第 N 拍（注入 + 事件）；
    *   - `dueRung ≥ maxRungs` 且拍数已满 ⇒ quiescent 档（只写事件，间隔
    *     `quiescentIntervalMs`）；
    *   - 与 `cleanupRunTables` 对称：节点非 Running / 会话映射已清 / 会话非活 ⇒ 跳过
    *     （注入需要活会话；僵尸腿由 settleStaleRunningNodes 管）。
    *
    * 幂等：拍数按 `reportReminderCount` 的 CAS 单发（同一拍并发/重复命中 ⇒ 计数已推进
    * ⇒ no-op、不重复写事件）；quiescent 按内存记账节流。**零 failNode 路径**。 */
  def remindUnreportedNodes(): IO[Unit] =
    val ladder = nebflow.shared.Defaults.NodeReportReminderLadderMs
    val maxRungs = math.min(nebflow.shared.Defaults.NodeReportReminderMaxRungs, ladder.length)
    if ladder.isEmpty || maxRungs <= 0 then IO.unit
    else
      store.snapshot.flatMap { s =>
        s.nodes.values
          .filter(n => n.status == NodeLifecycle.Running && n.reportPendingSince.isDefined)
          .toList
          .traverse_(n => remindUnreportedNode(n, ladder, maxRungs))
      }

  private def remindUnreportedNode(node: NodeDef, ladder: List[Long], maxRungs: Int): IO[Unit] =
    val since = node.reportPendingSince.getOrElse(0L)
    IO(System.currentTimeMillis()).flatMap { now =>
      val elapsed = now - since
      val dueRung = ladder.count(_ <= elapsed)
      val ladderStage = math.min(dueRung, maxRungs) > node.reportReminderCount
      val quiescentStage = !ladderStage && dueRung >= maxRungs
      if !ladderStage && !quiescentStage then IO.unit
      else
        nodeSessions.get.map(_.get(node.id)).flatMap { cached =>
          // 会话 id 候选 = 内存缓存 ∪ 持久 sessionRef（injectRunning 同款解析序）。
          val candidates = (cached.toList ++ node.sessionRef.toList).distinct
          if candidates.isEmpty then IO.unit // 三表已清（会话映射无）⇒ 跳过
          else
            // 申报槽检查（非消费读）：任一候选会话有申报 ⇒ 申报已到 ⇒ 清表不提醒。
            candidates
              .traverse(sid => NodeReportRegistry.peek(sid).map(sid -> _))
              .flatMap { probes =>
                probes.collectFirst { case (sid, Some(_)) => sid } match
                  case Some(sid) => wakeBridgeForRelease(node, sid, since)
                  case None =>
                    val target = math.min(dueRung, maxRungs)
                    if ladderStage then
                      resources.agentRegistry.get.flatMap { reg =>
                        candidates.find(reg.contains) match
                          case None => IO.unit // 会话非活 ⇒ 跳过（注入不可达）
                          case Some(sid) =>
                            fireReminderRung(node, sid, since, elapsed, target, maxRungs, ladder(target - 1))
                      }
                    else fireQuiescentEvent(node, since, elapsed, maxRungs, ladder(maxRungs - 1))
              }
        }
    }

  /** 发第 N 拍（阶梯期）：`reportReminderCount` CAS 单发 ⇒ 注入提醒轮 + 写
    * `node-report-missing`（stage=active）。注入复用 `sendNodeMessage` 通道（其内部
    * 会写 node-message 留痕并在会话已终结时回退「注入未达」），投递结果进事件字段。 */
  private def fireReminderRung(
    node: NodeDef,
    sessionId: String,
    since: Long,
    elapsed: Long,
    rung: Int,
    maxRungs: Int,
    rungMs: Long
  ): IO[Unit] =
    val expectedCount = node.reportReminderCount
    store.mutateWithResult { st =>
      st.nodes.get(node.id) match
        case Some(fresh)
            if fresh.status == NodeLifecycle.Running
              && fresh.reportPendingSince.contains(since)
              && fresh.reportReminderCount == expectedCount =>
          (st.copy(nodes = st.nodes.updated(node.id, fresh.copy(reportReminderCount = rung))), true)
        case _ => (st, false)
    }.flatMap {
      case (_, false) => IO.unit // 同拍重复命中 / 状态已变 ⇒ 不再发（幂等）
      case (s, true) =>
        val exhausted = rung >= maxRungs
        injectReminderTurn(sessionId, NodeEngine.reportReminderText(node.name, rung, maxRungs, rungMs, elapsed), rung, maxRungs)
          .flatMap { delivered =>
            s.nodes.get(node.id).traverse_(emitUpdated) *>
              FlowMapEventLog.append(workspace, projectName, node.id, NodeEngine.NodeReportMissingEventType,
                NodeEngine.reportMissingSummary(
                  stage = NodeEngine.NodeReportStageActive, rung = rung, maxRungs = maxRungs,
                  rungMs = rungMs, elapsedMs = elapsed, pendingSince = since, reminderCount = rung,
                  ladderExhausted = exhausted, delivered = Some(delivered))) *>
              logger.warn(
                s"Node '${node.name}' (${node.id}) finished its turn without a node_report declaration — " +
                  s"reminder $rung/$maxRungs injected (delivered=$delivered, waited=${elapsed / 1000}s, session=$sessionId); " +
                  "the node stays Running — never failed, never killed (human supervision expected)")
          }
    }

  /** 释放唤醒（noderpt 批 F2，2026-09-11 复核 D2 修复）：申报**已到**但桥未观测时的放行腿。
    *
    * 病灶（复核 D2 / 探针 P3 实证）：`NodeMessage` 重入轮（`sendNodeMessage → injectRunning
    * → AgentCommand.ImmediateInput`）在 AgentActor 内转成 `UserInput(replyTo = None)` ⇒ 该轮
    * 收尾的 `completionTargets` 不含节点观察桥 ⇒ **无 `AgentEvent.Completed` 到桥**（⑧-1
    * 同一根因）。若该轮里 agent 调了 `node_report`（分发器催办后最常见的那一轮），申报就进了
    * `NodeReportRegistry`，而桥的复检点只在 `Completed` 分支 ⇒ 永不触发。改前本腿走 `peek`
    * 命中分支**只清表**（`clearReportPending`）不放行 ⇒ 计时清零、节点永久 Running、
    * 零提醒零投递（安全网被无声关闭）。
    *
    * 本腿动作：命中申报槽 ⇒ **唤醒桥复检**（**不清表**）。桥在 `Completed` 分支里做的正是
    * 「`clearReportPending *> releaseNow`」原子组合 ⇒ 复检即放行，清表与放行同点；申报信息
    * 由终态点的 `NodeReportRegistry.drain` 单次消费（pass/fail/blocked 三链分流零改动）。
    *
    * **为什么退化为「注入一次唤醒轮」而非直接放行**（按任务书要求登记退化理由 + 代码锚）：
    * `releaseNow` 是桥行为内的**局部闭包**（`NodeEngine.scala:2160`，闭包持有本次 `Completed`
    * 的 `messages` 与 `resultDeferred`），扫描腿（`ProjectActor.TtlTick`）手上只有
    * `AgentRecord`/`AgentRef`——其接收面是 `AgentEvent`，而 `Completed/Failed/Cancelled`
    * 三者都是**终态事件**：合成一个喂给桥 = 伪造终态（`messages` 只能是编造文本），比不释放
    * 更糟。故 **不存在等价的直接释放入口** ⇒ 退化为注入唤醒轮，让桥自己走它既有的复检放行
    * 路径。通道 = 与提醒轮/腿 1 后台完成通知同一条已实证机制：`node-` 前缀 Flow 会话以
    * `AgentRecord.supervisorRef`（= 本桥）作粘性 `replyTo` ⇒ 唤醒轮收尾必回 `Completed`。
    *
    * **为什么不清表**：若在此先清表，桥复检将见空槽 ⇒ 再次 hold，且申报已被吃掉、无法再被
    * `drain` 消费 ⇒ 比现状更糟的静默搁死（节点永久 Running 且无监督痕迹）。清表必须与放行
    * 同点（桥内原子组合）。
    *
    * 单发：同一未申报期只唤醒一次（see [[releaseWakeSent]]）。会话已不在 registry（死会话）
    * 时**不清表**、只留痕：计时保留 ⇒ quiescent 留痕与僵尸腿（`settleStaleRunningNodes`）
    * 两条既有出路都不丢。 */
  private def wakeBridgeForRelease(node: NodeDef, sessionId: String, since: Long): IO[Unit] =
    releaseWakeSent.get.flatMap { sent =>
      if sent.get(node.id).contains(since) then IO.unit
      else
        releaseWakeSent.update(_ + (node.id -> since)) *>
          injectExternalTurn(
            sessionId,
            NodeEngine.NodeReportReleaseWakeSource,
            eventType = "release-wake",
            payload = NodeEngine.reportReleaseWakeText(node.name),
            metadata = io.circe.JsonObject("pendingSince" -> since.asJson)
          ).flatMap { delivered =>
            FlowMapEventLog.append(workspace, projectName, node.id, NodeEngine.NodeReportReleaseWakeEventType,
              NodeEngine.reportReleaseWakeSummary(sessionId, since, delivered)) *>
              (if delivered then
                 logger.info(
                   s"Node '${node.name}' (${node.id}) holds a node_report declaration the completion bridge never " +
                     s"observed (immediate-injection turn produces no Completed) — release wake injected into " +
                     s"session '$sessionId' so the bridge re-checks and releases (clock kept: $since)")
               else
                 logger.warn(
                   s"Node '${node.name}' (${node.id}) holds a node_report declaration but session '$sessionId' is " +
                     "not live — release wake undeliverable; clock kept, node stays Running (dead-session settle / " +
                     "human action expected)"))
          }
    }

  /** 提醒轮注入（**外部事件唤醒通道**）。
    *
    * 为什么不是 `sendNodeMessage`（ImmediateInput）通道：`ImmediateInput` 在 AgentActor
    * 内转成 `UserInput(replyTo = None)`，其轮次收尾的
    * `completionTargets = replyTo.toList ++ owedCompletion`（`finishTurnCont`）**不含**
    * 本节点观察桥 ⇒ 唤醒轮**不产生** `AgentEvent.Completed` ⇒ 桥无法复检、节点无法放行
    * （本批 spec R4 实证：提醒已注入、agent 已按提醒申报，节点仍滞留 Running）。
    * `ExternalEvent` 走的是**节点会话唤醒轮专用腿**（AgentActor：`node-` 前缀且
    * `kind=Flow` 的会话以 `AgentRecord.supervisorRef` 作粘性 replyTo）——唤醒轮结束必回
    * `Completed` 到本桥，与完成闸腿 1 的「后台任务完成通知 → 唤醒轮 → 复检」**同一机制**。
    * 提醒文本原文（含专用前缀头 [[NodeReportReminderPrefix]]）进 agent 上下文/transcript。
    *
    * 返回 true = 已投递给活会话 actor（tell 语义，fire-and-forget）；false = 会话已不在
    * registry（会话非活 ⇒ 事件字段 delivered=false，节点仍保持 Running，等人工/僵尸腿）。 */
  private def injectReminderTurn(sessionId: String, text: String, rung: Int, maxRungs: Int): IO[Boolean] =
    injectExternalTurn(
      sessionId,
      NodeEngine.NodeReportReminderSource,
      eventType = "reminder",
      payload = text,
      metadata = io.circe.JsonObject(
        "rung" -> rung.asJson,
        "maxRungs" -> maxRungs.asJson
      )
    )

  /** 外部事件唤醒轮注入**单点**（提醒轮与释放唤醒共用，见 [[wakeBridgeForRelease]]）：
    * 查 registry 取会话 actor → `AgentCommand.ExternalEvent`（tell 语义，fire-and-forget）。
    * 返回 true = 已投递给活会话 actor；false = 会话已不在 registry（调用方据此写
    * `delivered=false` / 走 WARN 留痕）。 */
  private def injectExternalTurn(
    sessionId: String,
    source: String,
    eventType: String,
    payload: String,
    metadata: io.circe.JsonObject
  ): IO[Boolean] =
    resources.agentRegistry.get.map(_.get(sessionId)).flatMap {
      case None => IO.pure(false)
      case Some(record) =>
        (record.ref ! AgentCommand.ExternalEvent(
          source = source,
          eventType = eventType,
          payload = payload,
          metadata = metadata
        )).void *> IO.pure(true)
    }

  /** quiescent 档（第 maxRungs 拍之后）：**不注入**，每 `quiescentIntervalMs` 写一条
    * `node-report-missing`（stage=quiescent，reminderCount 不递增）。节点保持 Running。 */
  private def fireQuiescentEvent(
    node: NodeDef,
    since: Long,
    elapsed: Long,
    maxRungs: Int,
    lastRungMs: Long
  ): IO[Unit] =
    val intervalMs = nebflow.shared.Defaults.NodeReportReminderQuiescentMs
    val stageEntry = since + lastRungMs
    IO(System.currentTimeMillis()).flatMap { now =>
      quiescentNotified.get.map(_.getOrElse(node.id, stageEntry)).flatMap { last =>
        if intervalMs <= 0 || now - last < intervalMs then IO.unit
        else
          quiescentNotified.update(_ + (node.id -> now)) *>
            FlowMapEventLog.append(workspace, projectName, node.id, NodeEngine.NodeReportMissingEventType,
              NodeEngine.reportMissingSummary(
                stage = NodeEngine.NodeReportStageQuiescent, rung = maxRungs, maxRungs = maxRungs,
                rungMs = lastRungMs, elapsedMs = elapsed, pendingSince = since,
                reminderCount = node.reportReminderCount, ladderExhausted = true, delivered = None)) *>
            logger.warn(
              s"Node '${node.name}' (${node.id}) still has no node_report declaration after the reminder ladder " +
                s"(${maxRungs} rungs, waited ${elapsed / 1000}s) — quiescent stage: no further reminders injected, " +
                "periodic audit event only; node stays Running awaiting human action (never failed, never killed)")
      }
    }

  // ── boot-time 崩溃恢复（crash-recovery 批 2026-09-07，设计 §3.2 快段/慢段）──
  //
  // 快段（同步秒级）：对崩溃残留 status=Running 节点按持久层完整性三分类（§3.3）：
  //   (a) checkpoint 完整 / (b) mid-turn —— 磁盘不可区分，统一同路径：认领 = 翻回
  //       Pending + 清 bgWait + boot-recovery 事件 + 入 bootRecoveryQueue，续跑交慢段；
  //   (c) sessionRef 无值 / transcript 缺失/损坏/空 —— failNode("crash recovery:
  //       session transcript lost/corrupt") 走既有 deliverFailed 链（姊妹批挂接后自动
  //       获分发器 failed 通知）。
  // 认领动作改变节点状态使其即刻脱离 watchdog（settleStaleRunningNodes 只看 Running）
  // 与资格回扫（bootRecoveryQueue 排除）的处置口径——sweep 与 watchdog 的竞速由
  // 挂载顺序结构性关闭（快段先于 projectTtlScanner 启动完成）。
  // 判定材料 = sessionRef（D1，与 startedAt 同事务落库）→ SessionStore transcript
  // 文件。分类读取用 loadMessagesForSession（decode 失败侧自动备份损坏文件——
  // SessionStore 既有语义，非静默）。

  /** 快段单节点：分类 + 认领。返回：
    * - Some(Right(ctx))：已认领（翻 Pending + 清 bgWait + 事件 + 入队），待慢段
    *   startNode(ctx) 续跑；
    * - Some(Left(reason))：(c) 类已 failNode（reason 含 transcript 指针），下游走
    *   deliverFailed 既有链；
    * - None：非候选（非 Running）或认领事务败给并发状态变更（fresh 守卫拒写）。
    * 节点级异常不在此吞——调用方（sweep）逐节点 handleErrorWith，残余 Running 由
    * watchdog 兜底（R4）。 */
  def bootRecoveryClaim(n: NodeDef): IO[Option[Either[String, NodeEngine.ResumeContext]]] =
    if n.status != NodeLifecycle.Running then IO.pure(None)
    else
      def failClaim(reason: String): IO[Option[Either[String, NodeEngine.ResumeContext]]] =
        // (c) 类：仍 Running 才处置（fresh 守卫——与并发终态化互斥）；failNode 自带
        // deliverFailed + WS；boot-recovery 事件留痕（禁止静默自愈）。
        store.getNode(n.id).flatMap {
          case Some(fresh) if fresh.status == NodeLifecycle.Running =>
            failNode(n.id, reason) *>
              FlowMapEventLog.append(workspace, projectName, n.id, NodeEngine.BootRecoveryEventType,
                s"failed (class c): $reason") *>
              logger.warn(s"[boot-recovery] node '${n.name}' (${n.id}) failed: $reason").as(Some(Left(reason)))
          case _ => IO.pure(None)
        }
      def resumeClaim(ctx: NodeEngine.ResumeContext, claimNote: String): IO[Option[Either[String, NodeEngine.ResumeContext]]] =
        // (a)/(b) 类认领：Running → Pending 单事务翻转（CAS：并发终态化/已处置 → 拒写
        // 返回 None）+ 清 bgWait（等待集随进程蒸发 G4，resume prompt 已附死亡告知）。
        store.mutateWithResult { s =>
          s.nodes.get(n.id) match
            case Some(f) if f.status == NodeLifecycle.Running =>
              (s.copy(nodes = s.nodes.updated(n.id, f.copy(
                status = NodeLifecycle.Pending,
                bgWait = None,
                // 未申报计时随认领清零（noderpt 批 A 段）：重挂载后按新会话重新起表，
                // 旧一轮的起点/拍数不继承（flipToRunning 处还有一道同语义的兜底清零）。
                reportPendingSince = None,
                reportReminderCount = 0))), true)
            case _ => (s, false)
        }.flatMap { (s, claimed) =>
          if !claimed then IO.pure(None)
          else
            s.nodes.get(n.id).traverse_(emitUpdated) *>
              bootRecoveryQueue.update(_ + n.id) *>
              FlowMapEventLog.append(workspace, projectName, n.id, NodeEngine.BootRecoveryEventType,
                s"claimed (resume): $claimNote") *>
              logger.info(s"[boot-recovery] node '${n.name}' (${n.id}) claimed for rehydrate: $claimNote")
                .as(Some(Right(ctx)))
        }
      // ── 分类（§3.3 判定表；loop 节点裁定③双会话续接）──
      if n.loop.exists(_.enabled) then
        n.sessionRef match
          case None =>
            failClaim(s"crash recovery: session transcript lost (loop node has no sessionRef persisted — predates crash recovery; " +
              s"loopRound=${n.loopRound}, loopPhase=${n.loopPhase.getOrElse("-")} persisted for audit)")
          case Some(workerSid) =>
            resources.sessionStore.loadMessagesForSession(workerSid).attempt.flatMap {
              case Right(workerMsgs) if workerMsgs.nonEmpty =>
                // verify transcript 可缺（每轮输入自足）：缺 → 空 transcript 水合（等价新会话）。
                val verifySid = n.sessionRefVerify
                verifySid.traverse(sid => resources.sessionStore.loadMessagesForSession(sid).attempt.map {
                  case Right(msgs) => msgs
                  case Left(_)     => Nil // 既有 BackoffSupervisor 同款容错：坏档不阻断恢复
                }).map(_.getOrElse(Nil)).flatMap { verifyMsgs =>
                  val round = math.max(n.loopRound, 1)
                  val phase = n.loopPhase.getOrElse(NodeEngine.LoopPhaseWorker)
                  resumeClaim(
                    NodeEngine.ResumeContext(
                      sessionId = workerSid,
                      recoveredMessages = workerMsgs,
                      resumePrompt = NodeEngine.loopWorkerResumePrompt(n, round, phase, workerMsgs.size),
                      verifySessionId = verifySid,
                      verifyMessages = verifyMsgs,
                      loopResumeRound = round,
                      loopResumePhase = Some(phase)),
                    s"loop dual-session resume (worker=$workerSid msgs=${workerMsgs.size}, verify=${verifySid.getOrElse("-")} msgs=${verifyMsgs.size}, round=$round, phase=$phase)")
                }
              case Right(_) =>
                failClaim(s"crash recovery: session transcript lost/corrupt (loop worker sessionRef=$workerSid empty or missing; " +
                  s"loopRound=${n.loopRound}, loopPhase=${n.loopPhase.getOrElse("-")} persisted for audit)")
              case Left(e) =>
                failClaim(s"crash recovery: session transcript unreadable (loop worker sessionRef=$workerSid: ${Option(e.getMessage).getOrElse(e.toString)})")
            }
      else
        n.sessionRef match
          case None =>
            failClaim("crash recovery: session transcript lost (no sessionRef persisted — node predates crash recovery)")
          case Some(sid) =>
            resources.sessionStore.loadMessagesForSession(sid).attempt.flatMap {
              case Right(msgs) if msgs.nonEmpty =>
                resumeClaim(
                  NodeEngine.ResumeContext(
                    sessionId = sid,
                    recoveredMessages = msgs,
                    resumePrompt = NodeEngine.nodeResumePrompt(projectName, n, msgs.size, n.bgWait)),
                  s"resume (sessionRef=$sid msgs=${msgs.size}${n.bgWait.fold("")(w => s", bgWait cleared: ${w.take(120)}")})")
              case Right(_) =>
                failClaim(s"crash recovery: session transcript lost/corrupt (sessionRef=$sid)")
              case Left(e) =>
                failClaim(s"crash recovery: session transcript unreadable (sessionRef=$sid: ${Option(e.getMessage).getOrElse(e.toString)})")
            }

  /** 慢段单节点：出队 + startNode(resume)。出队即归还 settle 回扫资格——本 fiber
    * 紧接 startNode（CAS 翻转与任何并发新鲜启动互斥裁决，微秒窗口败者安静）；失败
    * （agent 缺失等）则节点留在正常 pending 池由资格回扫新鲜兜底（R4 降级）。 */
  def bootRecoveryStart(nodeId: String, ctx: NodeEngine.ResumeContext): IO[Unit] =
    bootRecoveryQueue.update(_ - nodeId) *>
      logger.info(s"[boot-recovery] node $nodeId rehydrating (session=${ctx.sessionId})") *>
      startNode(nodeId, Some(ctx))

  /** 本引擎是否拥有该会话（P2 定位键修正的实例侧入口，**只读** store 扫描）。
    * watcher 用它代替 `rt.engine.rootSessionId == rec.rootSessionId` 判定「哪个
    * runtime 是这个会话的家」。 */
  def ownsSession(sessionId: String): IO[Boolean] =
    store.snapshot.map(snap => NodeEngine.findNodeForSession(snap, sessionId).isDefined).handleErrorWith(_ => IO.pure(false))

  /** P2：**挂起**节点会话——「只停 actor，不改节点状态」。
    *
    * 这就是 R-1=B 要求的「挂起不终态化」原语：今天的 `bridgeCancelled` 一步兼两职
    * （停 actor + 节点终态化），本方法把前者单独拆出来。实现方式是向观察桥发一条
    * 带 [[NodeEngine.SuspendReasonPrefix]] 哨兵的 `AgentEvent.Cancelled`——桥照旧
    * 完成 `resultDeferred`，引擎 fiber 认出哨兵后走**挂起腿**（停 actor + 清理运行表，
    * **不写 status/result/ttl、不通知、不摘除**），节点保持 `Running`。
    *
    * 调用方（`TaskStuckWatcher` 的 L3 恢复腿）随后按「有界等待 actor 终止确认」
    * 轮询 [[nebflow.agent.SharedResources]] 的 registry，确认会话已摘除后再调
    * [[hardResumeNode]] 做 CAS + resume。
    *
    * 与 `cancelNode`（终态化）的关系：两条路**互斥且语义不同**——`cancelled` 保持
    * 真终态（不可重激活），挂起只是本代次的中断点。 */
  def suspendNode(sessionId: String, reason: String): IO[Boolean] =
    store.snapshot.map(snap => NodeEngine.findNodeForSession(snap, sessionId)).flatMap {
      case None =>
        logger.warn(s"[stuck-recovery] suspend skipped — no node owns session $sessionId").as(false)
      case Some(n) =>
        resources.agentRegistry.get.map(_.get(sessionId).flatMap(_.supervisorRef)).flatMap {
          case Some(sup) =>
            (sup ! AgentEvent.Cancelled(sessionId, s"${NodeEngine.SuspendReasonPrefix}): $reason")).as(true)
              .handleErrorWith(e =>
                logger.warn(s"[stuck-recovery] suspend signal for $sessionId failed: ${e.getMessage}").as(false))
          case None =>
            logger.warn(
              s"[stuck-recovery] suspend skipped for node '${n.name}' (${n.id}) — no supervisor bridge registered " +
                "(session already gone or never bound)").as(false)
        }
    }

  private[project] def suspendLog(sessionId: String, nodeId: String): Unit =
    logger.info(
      s"[stuck-recovery] session $sessionId suspended (actor stopped, node $nodeId left Running — NOT terminalized; " +
        "the L3 recovery leg will CAS it back to Pending and resume from the transcript breakpoint)")

  /** P2：恢复锚探测（设计 §3.1 的 A3 + §3.2 的产物判据）。**零 LLM、零副作用**——
    * 只读目录 + 三条 git 只读查询；探测失败如实降级（不伪造可用）。
    *
    * 口径见 [[RecoveryAnchor]]（含与 §3.2 逐字公式的差异说明）。 */
  def probeRecoveryAnchors(sessionId: String): IO[NodeEngine.RecoveryAnchor] =
    store.snapshot.flatMap { snap =>
      NodeEngine.findNodeForSession(snap, sessionId) match
        case None =>
          IO.pure(NodeEngine.RecoveryAnchor(false, "", false, "node not found in this project's store"))
        case Some(n) =>
          val dir: String =
            try PathUtil.resolveNodeProjectRoot(workspace, n.worktree).toString
            catch case _: Throwable => workspace
          val f = os.Path(dir)
          val worktreeOk = os.exists(f) && os.isDir(f) && os.exists(f / ".git")
          val gitOut = (args: List[String]) =>
            try
              val r = os.proc(args).call(cwd = f, check = false, stderr = os.Pipe, mergeErrIntoOut = true)
              Some((r.exitCode, r.out.text().trim))
            catch case _: Throwable => None
          val porcelain = gitOut(List("git", "status", "--porcelain"))
          // 「本代次新提交数」= 本节点 startedAt 之后的提交（NodeDef.startedAt 是
          // Option[Long]；缺失则退化为 0 ⇒ 该维不计，只靠 P2 工作区判据）。
          val since = gitOut(List("git", "rev-list", "--count", "HEAD",
            "--since=" + (n.startedAt.getOrElse(0L) / 1000).toString))
          val newCommits = since.flatMap(_._2.toIntOption).getOrElse(0)
          val dirty = porcelain.exists(_._2.nonEmpty)
          val hasOutput = dirty || newCommits > 0
          val evidence =
            s"git status --porcelain = ${if dirty then "non-empty" else "clean"}, " +
              s"commits since node start = $newCommits"
          IO.pure(NodeEngine.RecoveryAnchor(worktreeOk, dir, hasOutput, evidence))
    }.handleErrorWith(e =>
      logger
        .warn(s"[stuck-recovery] anchor probe for $sessionId failed: ${Option(e.getMessage).getOrElse(e.toString)}")
        .as(NodeEngine.RecoveryAnchor(false, "", false, "anchor probe failed (see log)")))

  /** P2：恢复腿未生效时的终局写点（R-1=B 形态）。
    *
    * 与 R5 的 [[settleFailedHardResume]] 的差异：R5 那条腿的前提是「节点处于中间态
    * `Cancelled`」（先终态化再救援），而 R-1=B 之后恢复腿全程不进 `Cancelled` ——
    * 节点此时是 **`Running`**。语义完全一致（诚实失败：引擎接管失败 = failed，可经
    * `NodeEdit` 重激活），故复用同一条 `failNode` 全链，只是状态守卫不同。
    *
    * 先 [[DispatchNotify.releaseTerminalNotify]] 归还可能的回流占位（幂等：无占位
    * 零写）——保证「恰好一次回流」在两种形态下都成立（挂起腿本不写占位，R5 旧形态
    * 会写，两者都在此归还）。 */
  def failStuckRecovery(sessionId: String, err: String): IO[Unit] =
    store.snapshot.flatMap { snap =>
      NodeEngine.findNodeForSession(snap, sessionId) match
        case None =>
          logger.error(
            s"[stuck-recovery] recovery failed but no node owns session $sessionId — nothing to re-judge " +
              "(manual intervention required)")
        case Some(n) if NodeLifecycle.Terminal.contains(n.status) =>
          logger.info(
            s"[stuck-recovery] recovery failed for session $sessionId but node '${n.name}' (${n.id}) is already " +
              s"${n.status} — no fallback action needed (idempotent)")
        case Some(n) =>
          dispatchNotify.releaseTerminalNotify(n.id) *>
            FlowMapEventLog.append(workspace, projectName, n.id, "hard-recovery",
              s"stuck recovery FAILED (session=$sessionId) — node re-judged as failed: ${err.take(200)}") *>
            failNode(n.id, err)
    }

  /** Hard-recovery P5-L3（2026-09-07 设计 §2.6/§9；**2026-09-11 stuck 自动恢复批 P2 改造**）：
    * 运行时活挂节点的真重启——从磁盘 transcript 断点 rehydrate 续跑，复用
    * boot-recovery 的 ResumeContext / startNode(resume) 全链（设计 §3.1「同一恢复底座」）。
    *
    * **P2 改造（R-1=B：恢复动作放在终态之前）**：
    *   - 前置动作 = L2 transport abort（止血）+ [[suspendNode]]（**挂起**，不是终态化）
    *     ⇒ 节点到达这里时状态是 **`Running`**（本代次从未进过 `Cancelled`）；
    *   - CAS 前置条件随之**收敛为 `Running` 单一值**（旧值 `Cancelled | Running` 是
    *     「先终态化再救援」形态的遗留：留住它等于给「已取消」的节点一条复活路径，
    *     与「`cancelled` 是不可重激活的真终态」冲突 —— 收敛后该语义由类型面保证）；
    *   - `resumePrompt` 按**零产出 / 有产出**分支（§3.2）：零产出告知「上次尝试零产出、
    *     已中断」，有产出带「未落盘的副作用不可信」+ 已产出清单。**重放封顶**（R-2 的
    *     40 条）在 P3 落地，本段先构造分支文本。
    *
    * 竞态：CAS 失败（并发终态化 / 已被重排 / 已被资格回扫新鲜启动）安静返回 None；
    * transcript 缺失 → None（由调用方走一次上报，诚实失败原则）。返回重启的 nodeId。
    *
    * **`onResumed` 回调（硬约束② 修复，2026-09-11）**：CAS **接受瞬间**触发（在
    * `emitUpdated` / `FlowMapEventLog` / [[startNode]] **之前**），语义 = 「恢复已
    * 生效」。返回之后才写副作用是错的：`startNode` 同步等到被恢复会话的**整段终态**
    * （`runWithAgent` 的 `IO.race`）——恢复后的会话可能运行数十分钟，写点落在返回值
    * 之后 ⇒ 这段时间里「冷却窗基点 / 互斥点 2 指纹基线」不置位（独立复核实测：恢复
    * 成功后 25s 账本仍 `lastRecoveryAt=0`，冷却窗与互斥点 2 在运行期形同不存在）。
    * 回调**带默认值**以保源兼容（既有调用点零改动）；回调失败只留痕、**不中断恢复腿**；
    * CAS 被拒时不触发（负控：拒绝 ⇒ 零写入）。*/
  def hardResumeNode(
    sessionId: String,
    anchor: Option[NodeEngine.RecoveryAnchor] = None,
    onResumed: IO[Unit] = IO.unit
  ): IO[Option[String]] =
    // 互斥点 1(a)（P3，设计 §3.4）：**恢复路径禁止唤醒冻结会话**。
    // 冻结态的自动出口是「用户输入唤醒」那条路——它会调 `resetCrossTurn` 清零跨轮
    // 指纹；恢复链若走到那里，就等于把 365 天 LoopGuard 冻结**绕过**。本批不改冻结面
    // （`AgentActor`/`LoopGuard` 是越界面），故把该纪律落成**显式断言**：会话仍处于
    // 冻结（`status==Frozen` / `frozenReason` 有值）⇒ 恢复**拒绝执行**。恢复的真实
    // 入口是 `startNode(resume)` → `NodeRunner` 起的**全新会话**（全新 actor，从不进
    // frozen behavior），这正是「不绕过」的结构性理由。
    resources.agentRegistry.get.map(_.get(sessionId)).flatMap { live =>
      if NodeEngine.frozenSessionBlocksResume(live) then
        logger
          .error(
            s"[hard-recovery] resume REFUSED for session $sessionId — the session is frozen " +
              s"(status=${live.map(_.status).getOrElse(AgentStatus.Idle)}, " +
              s"reason=${live.flatMap(_.frozenReason).getOrElse("-")}): waking a frozen session would " +
              "bypass the LoopGuard freeze via the user-input resetCrossTurn path (design §3.4 mutex 1a). " +
              "Node left as-is; manual reactivation only.")
          .as(None)
      else
    store.snapshot.flatMap { snap =>
      NodeEngine.findNodeForSession(snap, sessionId) match
        case None =>
          // R5（取消静默死锁修复批）：此前静默 `IO.pure(None)`——L3 6/6 零留痕的一个
          // 候选出口。留痕不改行为（仍返回 None）。
          logger.warn(
            s"[hard-recovery] no node owns session $sessionId — L3 resume skipped (session never bound to a node / node already archived)"
          ).as(None)
        case Some(n) =>
          resources.sessionStore.loadMessagesForSession(sessionId).attempt.flatMap {
            case Right(msgs) if msgs.nonEmpty =>
              // R-2：transcript 重放封顶（默认 40 条）——直击「重发全量 ~250k 上下文」
              // 的 token 放大面。截断事实进 resume prompt（否则模型会以为上下文完整）。
              val (replay, capped) = NodeEngine.capReplayMessages(msgs, nebflow.shared.Defaults.StuckRecoveryReplayMaxMsgs)
              val ctx = NodeEngine.ResumeContext(
                sessionId = sessionId,
                recoveredMessages = replay,
                resumePrompt = NodeEngine.nodeResumePrompt(projectName, n, replay.size, n.bgWait) +
                  NodeEngine.stuckResumeNote(anchor) +
                  (if capped then NodeEngine.replayCapNote(msgs.size, replay.size) else "")
              )
              store.mutateWithResult { s =>
                s.nodes.get(n.id) match
                  // P2（R-1=B）：CAS 前置条件**收敛为 `Running` 单一值**——挂起腿
                  // 不终态化，节点到达此处必为 Running。保留 `Cancelled` 等于给
                  // 「真终态」留一条复活后门，与「cancelled 不可重激活」的用户可
                  // 预期语义冲突（设计 §3.6 选项 B 的立论：消除冲突而不是绕过它）。
                  case Some(f)
                      if f.status == NodeLifecycle.Running =>
                    (s.copy(nodes = s.nodes.updated(n.id, f.copy(
                      status = NodeLifecycle.Pending,
                      completedAt = None,
                      ttlExpireAt = None,
                      bgWait = None,
                      // 未申报计时清零（noderpt 批 A 段）：L3 resume 是新会话，旧一轮的
                      // 计时起点/拍数不继承（挂起出口清表不累计的同一纪律）。
                      reportPendingSince = None,
                      reportReminderCount = 0,
                      // R5 方案 4：L3 中间态的回流占位（cancelNode(notify=false) 写入）
                      // 在此归还——节点已复活，本代次的真实终态尚未发生，占位若留
                      // 会把将来的 completion 回流持久去重吞掉（新一代静默死锁）。
                      // Running → Pending 时清无可清（幂等零副作用）。
                      notifySentAt = None))), true)
                  case _ => (s, false)
              }.flatMap { (s, ok) =>
                if !ok then
                  // R5：此前静默 `IO.pure(None)`——L3 6/6 零留痕的另一个候选出口。
                  // 留痕不改行为（节点保持原状，仍返回 None）。
                  logger.error(
                    s"[hard-recovery] resume CAS rejected for node '${n.name}' (${n.id}) — status is no longer " +
                      s"Running (concurrent terminal / restart / sweep); node left as-is, manual re-trigger needed"
                  ).as(None)
                else
                  // 硬约束② 修复（2026-09-11）：**「恢复已接受」回调在 CAS 接受瞬间
                  // 触发**，位置在 `startNode` **之前**——`startNode` 会同步阻塞到被恢复
                  // 会话终态，回调若放在它的返回值之后，「冷却窗基点 / 互斥点 2 基线」
                  // 就会在恢复后会话的整段运行期缺位。回调失败只 warn（不影响恢复腿）。
                  onResumed.handleErrorWith(e =>
                    logger.warn(
                      s"[hard-recovery] onResumed callback failed for session $sessionId: ${e.getMessage}"
                    )) *>
                    s.nodes.get(n.id).traverse_(emitUpdated) *>
                    FlowMapEventLog.append(workspace, projectName, n.id, "hard-recovery",
                      s"resumed from stuck (session=$sessionId msgs=${msgs.size})") *>
                    logger.info(
                      s"[hard-recovery] node '${n.name}' (${n.id}) resumed from transcript breakpoint (session=$sessionId)"
                    ) *>
                    startNode(n.id, Some(ctx)).as(Some(n.id))
              }
            case _ =>
              logger.warn(
                s"[hard-recovery] no readable transcript for session $sessionId — node left terminal, manual re-trigger needed"
              ).as(None)
          }
    }
    }

  /** R5 **方案 4**（取消静默死锁修复批 2026-09-10，作者裁定「L3 resume 失败 → 改判
    * failed」）：L3 硬恢复的 resume 未生效 ⇒ 节点由 Cancelled **改判 failed**
    * （走既有 [[failNode]] 全链：result=原因 / nodeUpdated / 失败回流 / D5 零结算
    * 停等留痕 / failed 侧 barrier 检查）。这是 [[hardResumeNode]] 返回 None 的引擎侧
    * 唯一收口，也是**失败腿唯一的终局写点**。
    *
    * 为什么是 failed 而不是「留在 cancelled + 按 R4 摘除」（方案 3）：语义上
    * 「引擎接管失败」是失败而非取消；能力上 failed 可经 NodeEdit 重激活（cancelled
    * 不可），把「上游修好 → 重跑 → 停等下游自动续跑」这条 D5 首选恢复路径还给用户。
    *
    * 三条同步面（设计 §5 影响面裁定）：
    *   ① **不摘除 / 不打「待承接」标**：failed = D5 零结算停等（下游保持 in 完整、
    *      barrier 继续等上游 reactivate）——与失败的 R4「自动摘除扩展到 failed」提案
    *      在同一裁定里被**永久拒绝**的理由一致（摘除会掐死 reactivate 恢复路径）。
    *      `cancelNode` / [[detachCancelledUpstream]] 的永久拒绝注释与 failed 侧零摘除
    *      纪律**不回退**：本方法不调用二者。
    *   ② **回流恰好一次且语义为 failed**：L3 路径的中间态 Cancelled 不发通知
    *      （`cancelNode(notify = false)` 占位）——这里先
    *      [[DispatchNotify.releaseTerminalNotify]] 归还占位，再经 [[failNode]] →
    *      `deliverFailed` → `notifyTerminal(Failed)` 发**唯一**一条通知。⇒ 双向坏形态
    *      都被结构性排除：不会「同节点 cancelled + failed 双份回流」（cancelled 那条
    *      从未发出），也不会「零回流」（failed 这条经既有去重链正常发出；即便预算
    *      耗尽，[[DispatchNotify]] 也会 markSent 止重扫）。
    *   ③ **审计可 join、不改写历史**：同一 nodeId 上按 `ts` 顺序可对账的链条 =
    *      `FlowMapEventLog` 的 `cancelled`（中间态，含 source 与原因）→ `FlowMapEventLog`
    *      的 `hard-recovery … L3 resume FAILED … re-judged as failed`（本方法追加）→
    *      `FlowMapEventLog` 的 `dispatch-notify`（`triggered: failed → dispatcher`，回流面）
    *      + WS `nodeUpdated(Failed)`（对外可见终态帧，非事件日志行）。**已写事件不修订**：
    *      `FlowMapEventLog.append` 只有追加面无 update/delete，bridge 侧历史 `cancelled`
    *      行**永不改写**，读者按 ts 顺序即得「被取消 → 恢复失败 → 改判失败」的完整链。
    *
    * `chainArchivable` 结论（[[FlowMapStore.chainArchivable]]，见其 failed 判据）：
    * **无需改动**——failed 成员要求 `notifySentAt.isDefined`，改判后的节点与任何 failed 节点
    * 的归档资格完全一致：回流发出 → `markSent`；failed 预算耗尽 → 同样 `markSent` 止重扫。
    * 唯一「暂时不满足」的形态 = 失败通知被失败窗口抑制（`Suppressed`/`CooldownOn` 有意
    * **不** markSent）——此时该节点（连同其拓扑分量：归档判据是**链级** [[FlowMapStore.chainArchivable]]）
    * 留在主图，等 `FailedCooldownMs`（30min，阈值 5 次/10min）冷却结束后的 30s 补投扫描
    * 必然补投并 `markSent` ⇒ **不会永留主图**（仅当进程在冷却窗口内永久停机才滞留，那是既有
    * failed 链语义，非本路径引入）。「引擎接管失败且通知从未发出」同样不是本路径的新形态。
    *
    * 幂等：状态守卫（非 Cancelled → 零动作）+ failed 回流/事件/告警各自去重。
    *
    * **查无节点的出口（U3/B 修复，2026-09-11 作者拍板）**：旧口径只 ERROR、**零动作**
    * ⇒ 既不摘除也不登记「待承接」，下游 barrier 永久停等且无任何可见账（09-11 全天
    * 15 例 L3 全数落此出口）。现改为 [[lateDetachUnlocatableSession]] 收口——见该方法
    * 的「哪条路径摘除、哪条不摘除」逐条说明。**「已能定位节点」分支（本方法下面
    * `Some(n)` 两支）行为逐字不变**：改判 failed 全链 + D5 零结算停等、不摘除。 */
  def settleFailedHardResume(sessionId: String): IO[Unit] =
    store.snapshot.flatMap { snap =>
      NodeEngine.findNodeForSession(snap, sessionId) match
        case None =>
          lateDetachUnlocatableSession(sessionId)
        case Some(n) if n.status != NodeLifecycle.Cancelled =>
          logger.info(
            s"[hard-recovery] L3 resume failed for session $sessionId but node '${n.name}' (${n.id}) is ${n.status} — no fallback action needed"
          )
        case Some(n) =>
          // 失败原因 = sessionId + 「L3 hard-recovery resume failed」语义 + 恢复指引
          // （节点 result 是分发器/前端读到的权威文本，必须自解释）。
          val err =
            s"L3 hard-recovery resume failed (session=$sessionId) — the engine could not revive this node from " +
              "the on-disk transcript breakpoint (no readable transcript / resume CAS rejected); " +
              "node re-judged as failed by the L3 hard-recovery chain. Downstream keeps waiting (D5 zero-settlement): " +
              "reactivate the upstream (NodeEdit) or rewire the graph to recover."
          val keptTargets = n.out.map(_.to).filterNot(_ == OutEdge.NebulaTarget)
          dispatchNotify.releaseTerminalNotify(n.id) *>
            FlowMapEventLog.append(workspace, projectName, n.id, "hard-recovery",
              s"L3 resume FAILED (session=$sessionId) — node re-judged as failed (was cancelled by the L3 bridge); " +
                "out kept (no detach), successors keep waiting (D5 zero-settlement)" +
                (if keptTargets.nonEmpty then s"; downstream still in=${keptTargets.mkString(",")}" else "")) *>
            failNode(n.id, err)
    }

  /** U3/B 兜底腿（2026-09-11 作者拍板）：L3 resume 失败且**活动区查无**该会话归属节点
    * 时的收口——把「找不到节点归属」从静默死路改成有账的迟到摘除。
    *
    * **哪条路径摘除、哪条不摘除（逐条）**：
    *   - ① **能定位节点**（[[settleFailedHardResume]] 的 `Some(n)` 支；P2① 修好定位键后
    *     L3 场景恒走此路）→ 改判 **failed**（[[failNode]] 全链）⇒ failed 侧 D5 零结算
    *     停等：**不摘除、不打 pendingSuccession**，下游 in 镜像原样保留，等上游
    *     NodeEdit 重激活后自动续跑。这是 R4/R5 裁定的**永久拒绝**项（「failed 也自动
    *     摘」会掐死 reactivate 恢复路径），本批不回退、不越界。
    *   - ② **查无节点但它在归档区**（本方法 `Some(archived)` 支）→ 节点以 **cancelled**
    *     终态离场（不是 failed），而 cancelled 侧的 R4 语义**就是摘除**（[[cancelNode]]
    *     头注 ③）。L3 路径因 `deferDetach = true` 把摘除推迟到 resume 结果；resume
    *     失败且节点已不可定位 ⇒ 没有任何腿会再补这一步 ⇒ **在此补**：反向扫活动区全部
    *     节点，凡 `in` 仍引用该 id 的 → prune `in` + 追加 `pendingSuccession`（「待承接」
    *     标）+ 补发 `nodeUpdated` + 事件流留痕，并把 L3 中间态的回流占位归还（节点已
    *     离场，占位留着只会是永不清除的陈旧标记）。
    *     **为什么反向扫**：节点已不在活动区，前向 `out` 遍历不可达（这正是
    *     [[detachCancelledUpstream]] 的 `targets.isEmpty` 零操作形态）；而「谁还引用我」
    *     在活动区恒可算。摘除方向（引用方 in）与 R4 摘除逐字同源，故语义一致。
    *   - ③ **连归档区也查无**（会话从未绑定到任何节点 / 记录已注销）→ ERROR **+ 事件流
    *     留痕**（`hard-recovery`，nodeId 空串）：不能再静默——这正是 09-11 的事故形态
    *     （35 行日志里 15 例只有 ERROR、事件流零行，事后无法 join 出「哪次 L3 失败了」）。
    *     此支**不摘除**（没有任何 id 可摘），但已可见、可检索、可 join。
    *
    * 幂等：反向 prune 后该 id 不再出现在任何 `in` 中 ⇒ 重复调用零写、零帧；事件流只在
    * 真正到达本方法时追加一行（可重放审计，与 [[FlowMapEventLog]] 的追加-only 纪律一致）。 */
  private def lateDetachUnlocatableSession(sessionId: String): IO[Unit] =
    store.archiveSnapshot.flatMap { arch =>
      arch.nodes.values.find(n =>
        n.sessionRef.contains(sessionId) || n.sessionRefVerify.contains(sessionId)
      ) match
        case None =>
          logger.error(
            s"[hard-recovery] L3 resume failed and no node owns session $sessionId — it is in neither the active " +
              "region nor this project's archive; no re-judge and no detach is possible (manual intervention required)"
          ) *> FlowMapEventLog.append(workspace, projectName, "", "hard-recovery",
            s"L3 resume FAILED (session=$sessionId) — no node owns this session in project '$projectName' " +
              "(active + archive both empty for it): nothing to re-judge, nothing to detach — manual intervention required")
        case Some(archived) =>
          reversePruneReferences(archived.id).flatMap { pruned =>
            dispatchNotify.releaseTerminalNotify(archived.id) *>
              FlowMapEventLog.append(workspace, projectName, archived.id, "hard-recovery",
                s"L3 resume FAILED (session=$sessionId) — node '${archived.name}' (${archived.id}) is no longer in the " +
                  "active region (archived), so it cannot be re-judged as failed; cancelled-side R4 detach applied " +
                  "late instead: " +
                  (if pruned.nonEmpty then
                     s"pruned the in-mirror of ${pruned.size} active referrer(s) ${pruned.mkString(",")} + marked " +
                       "pendingSuccession (their barrier is unblocked for a handover node)"
                   else
                     "no active node references it any more (idempotent zero-write)"))
          }
    }

  /** 反向引用 prune **单点**（本批新增；服务两处：B 的迟到摘除 [[lateDetachUnlocatableSession]]
    * 与 E 的不一致拓扑 [[detachCancelledUpstream]] `targets.isEmpty` 出口）。
    *
    * 语义 = 活动区内凡 `n.in.contains(nodeId)` 的节点 → 从其 `in` 摘除该 id + 追加
    * `pendingSuccession`（「待承接」）+ 补发 `nodeUpdated`（可见性口径与
    * [[detachCancelledUpstream]] 逐字一致：标记只落盘不推帧，前端要等下一次全量快照才见）。
    *
    * **为什么必须有反向方向**：`detachCancelledUpstream` 旧口径用前向 `out` 遍历目标，
    * 在两种形态下 `targets.isEmpty` ⇒ 静默零操作（U5/E 的问题）：
    *   ① out 已改接 Nebula（只剩 Nebula 边）而下游 `in` 镜像仍引用本节点（不一致拓扑）；
    *   ② 节点已不在活动区（归档/移除），`s.nodes.get(nodeId)` 直接 None。
    * 而「谁还引用我」在活动区恒可算——引用方向是这一族的唯一可靠方向。
    *
    * 幂等：prune 后该 id 不再出现在任何 `in` ⇒ 重复调用零写、零帧（满足 U5 的「不破坏
    * 重入幂等」）。返回被 prune 的下游 id 集（升序，审计/事件文案用）。 */
  private def reversePruneReferences(nodeId: String): IO[List[String]] =
    store.mutateWithResult { s =>
      val referrers = s.nodes.values.filter(_.in.contains(nodeId)).map(_.id).toList.sorted
      if referrers.isEmpty then (s, Nil)
      else
        val pruned = referrers.foldLeft(s.nodes) { (acc, tid) =>
          acc.get(tid) match
            case Some(tn) => acc.updated(tid, tn.copy(
              in = tn.in.filterNot(_ == nodeId),
              pendingSuccession = (tn.pendingSuccession :+ nodeId).distinct))
            case None => acc
        }
        (s.copy(nodes = pruned), referrers)
    }.flatMap { case (_, pruned) =>
      pruned.foldLeft(IO.unit) { (acc, tid) =>
        acc >> store.getNode(tid).flatMap {
          case Some(n) => emitUpdated(n)
          case None    => IO.unit
        }
      }.as(pruned)
    }

  /** WS nodeRemoved（TTL 移除/归档后通知前端移除卡片）。payload = 节点最终态
    * （NodeList 同构——归档区兜底查得，ttlLeftSec=0），不再发空对象。 */
  def emitRemoved(nodeId: String): IO[Unit] =
    store.findNode(nodeId).flatMap {
      case Some(n) => emitWithChain("nodeRemoved", nodeId, NodePayload.buildNodeJson(n, System.currentTimeMillis()))
      case None    => emitWithChain("nodeRemoved", nodeId, Json.obj("id" -> nodeId.asJson))
    }

  // ── NodeMessage（20260905 机制批，作者裁定六条语义）──────────────────
  //
  // 向已分发节点注入补充消息，按节点状态三路由：
  //   running（裁定②）     → 复用既有 immediate 注入机制（ImmediateInput →
  //                          pendingImmediateInputs → turn 边界 drainHead /
  //                          idle 即开新 turn；CompactionQueueStore 压缩窗口
  //                          保全）——注入文本带 [NODE-MESSAGE] 可识别前缀
  //                          （来源 = 分发器 NodeMessage + 节点名 + 时间戳），
  //                          与用户任务文本、节点结果投递明确区分。竞态兜底：
  //                          状态检查与入队间会话终结（nodeSessions/registry
  //                          查无映射）→ 回退任务记录追加并标注「注入未达」。
  //   wiring/pending
  //   （裁定③，非终态无
  //    活动会话）          → 补充内容持久追加进节点 task（== 分发器补充
  //                          （NodeMessage <时间戳>） == 分节）——节点启动时 buildInput 沿 task
  //                          读到。事务内 fresh 状态守卫：并发启动窗口（状态已
  //                          变 running）→ 重走一次 running 路由（单次重试）；
  //                          并发终态化 → NODE_TERMINAL_NO_MESSAGE。
  //   终态（裁定④）        → 拒绝 NODE_TERMINAL_NO_MESSAGE（completed/failed/
  //                          cancelled/blocked；blocked 亦拒——应新建节点而非
  //                          倒改）。
  // 留痕（裁定⑤）：每条消息（含注入成功/未达/追加）一律追加 FlowMapEventLog
  // （type=node-message，append-only 持久可追溯）；Flow Map 默认载荷零膨胀
  // （不加标记不加键——载荷精简裁定「能不加就不加」）。
  def sendNodeMessage(nodeId: String, message: String): IO[Either[String, String]] =
    val text = message.trim
    if text.isEmpty then
      IO.pure(Left(s"'message' must be non-empty (trim) — nothing to inject (NODE_MESSAGE_EMPTY)"))
    else
      store.findNode(nodeId).flatMap {
        case None =>
          IO.pure(Left(s"Node '$nodeId' not found in project '$projectName' (active or archived) — " +
            s"check NodeList for the current topology (NODE_NOT_FOUND)"))
        case Some(n) if NodeLifecycle.Terminal.contains(n.status) =>
          IO.pure(Left(s"Node '${n.name}' is terminal (status=${n.status}) — messages are refused: " +
            "a finished node is never retro-edited; create a new node instead (NODE_TERMINAL_NO_MESSAGE)"))
        case Some(n) if n.status == NodeLifecycle.Running =>
          injectRunning(n, text)
        case Some(n) =>
          appendToTaskRoute(n, text, retried = false)
      }

  /** 裁定② running 路由：活会话 → ImmediateInput（source="system"，text 带
    * [NODE-MESSAGE] 前缀头）。
    *
    * 增量#5 修复（2026-09-07，slideblocks ×3 投递失败）：会话解析不再单依赖
    * nodeSessions 内存缓存——**实时解析优先**：D1 持久化的 node.sessionRef 与
    * Running 翻转同事务落库（重激活/重挂载后永为新会话的权威记录），缓存与
    * sessionRef 不一致时以 sessionRef 为准并**自愈缓存**；两者逐一试探
    * agentRegistry（任一命中即投递），全 miss 才走「注入未达」兜底。消除
    * 「blocked→NodeEdit 重激活后注入持续解析到已终结句柄」的窗口（旧句柄
    * 只可能存在于缓存侧；sessionRef 每次启动原子刷新）。注入为 fire-and-forget
    * tell（与 Mail/revalidate 先例同语义）——投递本身不可回执，事件日志恒记录
    * 注入尝试（留痕不丢）。 */
  private def injectRunning(node: NodeDef, text: String): IO[Either[String, String]] =
    nodeSessions.get.map(_.get(node.id)).flatMap { cached =>
      // 候选序：sessionRef（持久权威，实时解析）→ cached（内存快路径）。去重。
      val candidates = (node.sessionRef.toList ++ cached.toList).distinct
      def tryDeliver(sids: List[String]): IO[Either[String, String]] =
        sids match
          case sid :: rest =>
            resources.agentRegistry.get.flatMap { reg =>
              reg.get(sid) match
                case Some(record) =>
                  val head = NodeEngine.nodeMessageHeader(node.name)
                  // 自愈：命中者非缓存值（重激活/重挂载后缓存滞后）→ 刷新缓存，
                  // 后续投递恢复快路径。
                  val heal = if cached.contains(sid) then IO.unit
                    else nodeSessions.update(_ + (node.id -> sid)) *>
                      logger.warn(
                        s"NodeMessage: node '${node.name}' (${node.id}) session cache self-healed -> $sid " +
                          s"(cached=${cached.getOrElse("-")}, sessionRef=${node.sessionRef.getOrElse("-")})"
                      )
                  heal *>
                    (record.ref ! AgentCommand.ImmediateInput(
                      text = s"$head\n\n$text",
                      source = Some("system"),
                      fromUser = false // ② 服务端注入（Node 消息），不是真人输入
                    )).void *>
                    FlowMapEventLog.append(workspace, projectName, node.id, NodeEngine.NodeMessageEventType,
                      s"injected -> live session $sid: $text") *>
                    logger.info(s"NodeMessage -> node '${node.name}' (${node.id}) injected at turn boundary (session $sid, ${text.length} chars)").as(
                      Right(s"Message delivered to running node '${node.name}' — it will be injected at the next turn boundary ([NODE-MESSAGE] header). " +
                        "Trace: flow-map-events.jsonl (node-message/injected)."))
                case None => tryDeliver(rest)
            }
          case Nil =>
            // 全部候选查无会话（会话在检查与入队间已终结）→ 裁定②竞态兜底。
            appendUndelivered(node, text, candidates.mkString("/"))
      tryDeliver(candidates)
    }

  /** 裁定②竞态兜底：注入不可达 → 记录追加「注入未达」（纯留痕写——只对节点
    * 存在性守卫，不挑剔状态；会话已终结时状态可能已翻终态，留痕必须不丢）。 */
  private def appendUndelivered(node: NodeDef, text: String, sid: String): IO[Either[String, String]] =
    store.mutate { st =>
      st.nodes.get(node.id) match
        case Some(fresh) =>
          st.copy(nodes = st.nodes.updated(node.id, fresh.copy(task = Some(
            fresh.task.getOrElse("") + s"\n\n${NodeEngine.nodeMessageSection(undelivered = true)}\n$text"))))
        case None => st
    }.flatMap { s =>
      s.nodes.get(node.id).traverse_(emitUpdated) *>
        FlowMapEventLog.append(workspace, projectName, node.id, NodeEngine.NodeMessageEventType,
          s"not-delivered (session $sid already gone) -> recorded on task: $text") *>
        logger.warn(s"NodeMessage -> node '${node.name}' (${node.id}) session '$sid' gone before delivery — recorded on task as undelivered").as(
          Right(s"Node '${node.name}' session ended before the message could be injected — the message was recorded on the node's task " +
            "as「注入未达」(trace preserved, nothing lost). If the node reruns (NodeEdit reactivation) it will read it; " +
            "otherwise create a new node to carry the instruction."))
    }

  /** 裁定③ wiring/pending 路由：持久追加 task（节点启动时随 buildInput
    * 读到）。单事务 fresh 状态守卫：仍 ∈ {wiring, pending} → 追加写成；
    * 并发启动（running）→ 单次重试走 running 路由；并发终态化 → 裁定④拒绝。 */
  private def appendToTaskRoute(node: NodeDef, text: String, retried: Boolean): IO[Either[String, String]] =
    store.mutate { st =>
      st.nodes.get(node.id) match
        case Some(fresh) if fresh.status == NodeLifecycle.Wiring || fresh.status == NodeLifecycle.Pending =>
          st.copy(nodes = st.nodes.updated(node.id, fresh.copy(task = Some(
            fresh.task.getOrElse("") + s"\n\n${NodeEngine.nodeMessageSection(undelivered = false)}\n$text"))))
        case _ => st // 状态已变 → 本事务不写（下方复核分流）
    }.flatMap { s =>
      s.nodes.get(node.id) match
        case Some(fresh) if fresh.status == NodeLifecycle.Wiring || fresh.status == NodeLifecycle.Pending =>
          // mutate 原子性：返回快照即本事务后状态——状态仍 ∈ 追加集 ⟺ 本事务写成
          emitUpdated(fresh) *>
            FlowMapEventLog.append(workspace, projectName, node.id, NodeEngine.NodeMessageEventType,
              s"appended to task (status=${fresh.status}): $text").as(
              Right(s"Message appended to node '${fresh.name}' task (status=${fresh.status}) as「分发器补充（NodeMessage）」— " +
                "the node will read it as part of its task when it starts. Trace: flow-map-events.jsonl (node-message/appended)."))
        case Some(fresh) if fresh.status == NodeLifecycle.Running && !retried =>
          // 并发启动窗口：pending 在检查与追加间被投递启动 → 消息应走注入面
          logger.info(s"NodeMessage -> node '${node.name}' started concurrently during append — rerouting to live injection")
          injectRunning(fresh, text)
        case Some(fresh) if fresh.status == NodeLifecycle.Running =>
          appendUndelivered(fresh, text, "rerun-race")
        case Some(fresh) =>
          IO.pure(Left(s"Node '${fresh.name}' became terminal (status=${fresh.status}) while the message was being appended — " +
            "refused: create a new node instead (NODE_TERMINAL_NO_MESSAGE)"))
        case None =>
          IO.pure(Left(s"Node '${node.id}' vanished before the message was appended (NODE_NOT_FOUND)"))
    }

  /** WS 事件链富化单点（链级抽象 P0 + U1 多链归属批）：全部节点事件 payload 经此
    * 统一补 chainId / chainIds 条件键——判据单点 FlowMapStore.chainAttrsOf（`_1` =
    * 主链 id，合并集分量成员数 ≥2 才带，孤立单节点链不带；`_2` = 多链归属集 =
    * 主链 id 首项 + 全量成员链，**仅 merge 节点**且可达成员链数 ≥2 才带，普通节点恒
    * 不带 = 单值 chainId 语义不变；与快照 buildNodeListPayload 同口径）。查无链
    * （节点已出双区/单节点链）→ payload 原样透传。WS 帧外壳（ProjectActor.emitNodeEvent）
    * 零改动——富化只发生在载荷体。 */
  private def emitWithChain(eventType: String, nodeId: String, payload: Json): IO[Unit] =
    store.chainAttrsOf(nodeId).flatMap { case (cid, cids) =>
      val enriched = cid.toList.map(c => "chainId" -> c.asJson) ++
        cids.toList.map(ids => "chainIds" -> ids.asJson)
      emitEvent(eventType, nodeId,
        if enriched.isEmpty then payload else payload.deepMerge(Json.obj(enriched*)))
    }

  /** WS nodeCreated（NodeEdit 创建后）。payload 与 NodeList 同构。 */
  def emitCreated(node: NodeDef): IO[Unit] =
    emitWithChain("nodeCreated", node.id, NodePayload.buildNodeJson(node, System.currentTimeMillis()))

  /** WS nodeUpdated（NodeEdit 编辑/wiring 变更后）。payload 与 NodeList 同构，
    * 调用方须传 store 最终态（wiring 变更走 NodeTools.emitWiringUpdates）。 */
  def emitUpdated(node: NodeDef): IO[Unit] =
    emitWithChain("nodeUpdated", node.id, NodePayload.buildNodeJson(node, System.currentTimeMillis()))

  /** 显式投递（改接投递 §2.3：已完成节点结果 → 指定目标）。P1（spec §2.2 #4/#6）：
    * 人工改接 / D1 补投 / 重激活补投链统一经本函数，目标解析加门控判定——沿
    * node.out 中指向该目标的边：on ∌ pass → 跳过投递（failed-only 边不接收完成结果，
    * 停等可见性由 mount-stalled 承载）；无边（修复路径的悬空/孤儿 barrier）→ 按
    * {pass} 兜底投递（修复语义优先）。Nebula 分支：mode=result 通报 + V8 记账，
    * mode=signal 只记账。V8: 记账避免周期扫描对同一结果再补投。缺口3：夹具信封
    * 排除——名字 ∧ 载荷双确认 → WARN + 不投（结果滞留节点不删）。 */
  def deliverOutTo(node: NodeDef, target: String, resultText: String): IO[Unit] =
    val edge = node.out.find(_.to == target)
    if edge.exists(e => !e.on.contains(OutEdge.Pass)) then
      logger.info(s"[gating] redelivery '${node.name}' -> '$target' skipped: edge on=[${edge.get.on.mkString(",")}] excludes pass")
    else
      target match
        case "Nebula" =>
          if NodeEngine.isFixtureEnvelope(node) then
            logger.warn(
              s"[fixture-guard] excluded fixture envelope from manual redelivery: node '${node.name}' (${node.id}) status=${node.status} — name matches fixture family and task carries fixture marker")
          else if edge.exists(_.mode == OutEdge.Signal) then markNebulaDelivered(node.id)
          else deliverToNebula(s"[Node '${node.name}' completed]\n$resultText", node.name, "completed", Some(node.id))
        case t => settleTo(node, t)

  /** deps 满足判定（deps 设计 §1.3）：声明式状态查询（幂等、零记账），非 deliveredTo
    * 式事件累计。findNode 归档兜底——TTL 归档不影响「完成」事实（归档上游可触发，
    * 与 D1「归档 completed 上游是唯一投递入口」先例一致）。deps 为空 → 恒满足。 */
  def depsSatisfied(node: NodeDef): IO[Boolean] =
    node.deps.traverse(store.findNode)
      .map(_.forall(_.exists(_.status == NodeLifecycle.Completed)))

  /** 启动节点（§2.1 创建即运行：入口节点由 NodeEdit 调；下游由投递 barrier 归零调）。
    * resume（crash-recovery 批 2026-09-07 D3）：boot sweep 认领的崩溃残留节点续跑——
    * Some 时跳过 buildInput 直接用 resume prompt（水合消息经 spawn 链 initialMessages
    * 注入），CAS 翻转/deps 闸门/barrier 复核/三表登记全套照走——恢复不绕过任何启动
    * 纪律（resume 节点崩溃前已过同一闸门，deliveredTo/deps 随 flow-map 持久，重走恒真）。 */
  def startNode(nodeId: String, resume: Option[NodeEngine.ResumeContext] = None): IO[Unit] =
    store.getNode(nodeId).flatMap {
      case None => IO.unit
      case Some(node) =>
        node.status match
          // blocked 同样幂等跳过（§1.1：重跑同样 blocked；唯一出口 = NodeEdit 重激活）
          case NodeLifecycle.Running | NodeLifecycle.Completed | NodeLifecycle.Failed | NodeLifecycle.Cancelled | NodeLifecycle.Blocked =>
            IO.unit // 已终态/运行中，幂等跳过
          case _ =>
            // deps 闸门（deps 设计 §1.3，单权威）：全部 deps 上游 completed 才放行。
            // 单闸门自动保护全部启动入口（deliverOut/deliverOutTo/D1 补投递/重激活链/
            // 入口启动/settleDeps）——任何绕过 deps 的启动都被拦在最后一关。
            // failed/cancelled/blocked ∉ completed → 不触发，下游保持 pending/wiring 可见。
            depsSatisfied(node).flatMap {
              case false => IO.unit
              case true =>
                // in barrier 归零检查（deps 设计 §1.3「in 未归零都会静默返回」）：新调用方
                // settleDeps 直调 startNode 不再经 deliverOut 的 barrier 归零前置——闸门内
                // 补上这层，混合同持 in+deps 的下游只在两种等待都满足时才启动。既有全部
                // 调用方（deliverOut/deliverOutTo/deliverFailed/D1 链/入口启动）本就在
                // barrier 归零后才调（入口 in=Nil 平凡归零），检查对它们恒真、零行为变化。
                // 本检查沿快照；并发接线窗口的事务性复核在 spawnAndRun 的翻转 mutate 内
                //（fix a 启动判定完整性，20260903）。
                // R4（取消静默死锁修复批）：带「待承接」标记的 barrier 不放行——引擎自动
                // 摘除 cancelled 上游后 in 已 prune，若不设此闸，barrier 会以「少一轨」的
                // 输入正常启动、静默产出缺轨结论（设计 §6-R4 选择方案 4 的核心理由）。
                // 分发器承接动作落地（NodeEdit 任意实际变更）即清空标记，此处自然放行。
                if node.in.exists(up => !node.deliveredTo.contains(up)) || node.pendingSuccession.nonEmpty then IO.unit
                else
                  // 防御：wiring 空节点（无 task 无 in 无 deps）不空跑（防浪费 token）。
                  // 裁定①后降级为纵深防御：零连接主责在 NodeEdit 校验五/六，此处只兜
                  // 历史遗留数据（校验生效前落盘的零连接旧节点）与校验层外瞬态。
                  if node.status == NodeLifecycle.Wiring && node.task.isEmpty && node.in.isEmpty && node.deps.isEmpty then IO.unit
                  else {
                    // 链快照单点（链级抽象 P2 §9.2 项 4/5/7 + 性能纪律）：spawn 前取
                    // 一次，同时喂首条消息链头（buildInput）与会话身份
                    // （ToolContext.flowChainId）——同一快照两处同源，链头文本与会话
                    // 身份恒等（不重复全量重算分量）。resume 路径跳过 buildInput（用
                    // resume prompt）但身份照旧注入：快照重取（spawn 时刻语义），非
                    // 崩溃前残值。
                    chainContextOf(node.id).flatMap { chain =>
                      // resume：Some → 首轮输入 = resume prompt（任务全文经 NodeList(detail)
                      // 指引自读，BackoffSupervisor continue-prompt 直系演化）；None → buildInput。
                      val inputIO = resume.fold(buildInput(node, chain))(ctx => IO.pure(ctx.resumePrompt))
                      inputIO.flatMap { input =>
                        // 翻转竞发败方终结（trigger-chain-fix CAS 守卫）：spawnAndRun 内
                        // 翻转 LostRace 败方抛 StartRaceLost——在此单点吞掉，对全部调用
                        // 方（forkStart/runDetached/直接调用）呈安静败方语义；其他异常
                        // 原样上抛（forkStart/runDetached 留痕）。
                        // LoopNode 路由（LoopNode 批 2026-09-06）：node.loop 启用 → 走
                        // spawnAndRunLoop（worker/verify 双会话迭代）；否则标准路径。
                        val runIO =
                          if node.loop.exists(_.enabled) then spawnAndRunLoop(node, input, resume, chain)
                          else spawnAndRun(node, input, resume, chain)
                        runIO.handleErrorWith {
                          case _: NodeEngine.StartRaceLost => IO.unit
                        }
                      }
                    }
                  }
            }
        }

  /** 触发链 fork 化（根因报告 §6.1，trigger-chain-fix 批）：startNode 同步等到被
    * 启动节点整个会话终态（runWithAgent 内 IO.race(resultDeferred.get, …)），触发
    * 方在自身 fiber 上被下游会话质押——settleDeps 的 traverse_ 遍历因此把「同上游
    * N 依赖者」压成深度优先串行链（案例 A：队尾依赖者 pending 数小时，外观 pending
    * 永动），deliverOut/deliverFailed/deliverOutTo 则把上游完成 fiber 质押给下游
    * 全链（链深嵌套 SOE 风险面同源）。fork 后上游完成即同时唤醒全部依赖者；启动
    * 判定、幂等闸门、CAS 翻转守卫全在 startNode/spawnAndRun 内原样把关（同节点
    * 竞发由翻转守卫裁决，败方安静退出）。错误观测沿用 NodeTools.runDetached 形态
    * （handleErrorWith 留痕，含触发点上下文）。 */
  private def forkStart(what: String)(io: IO[Unit]): IO[Unit] =
    io
      .handleErrorWith(e =>
        logger.error(s"[$projectName] detached trigger '$what' failed: ${Option(e.getMessage).getOrElse(e.toString)}")
      )
      .start
      .void

  /** 节点任务板头部块（TaskBoard 批 2 §3a）：三段式（①项目目标行 ②自己名下工单
    * 详情块 ③全板速览）——renderer.renderNodeInject 单点装配（上限/降级纪律在
    * renderer）。工单归属按 assignee=自身 node.id（§1d 权限矩阵的身份同源）；
    * ⚠node-done join 用 Flow Map 真实终态映射（store.snapshot 现读，nodeTerminalMap
    * 单点过滤）。无板 → ""（调用方不注空段）。 */
  private def taskBoardNodeBlock(node: NodeDef): IO[String] =
    board match
      case None => IO.pure("")
      case Some(b) =>
        store.snapshot.flatMap { snap =>
          val terminal = TaskBoardStore.nodeTerminalMap(snap.nodes.values)
          IO.blocking {
            val entries = b.entriesSync()
            val mine = entries.filter(_.assignee.contains(node.id))
            TaskBoardRenderer.renderNodeInject(projectGoal, mine, entries, terminal)
          }
        }

  /** 链快照单点（链级抽象 P2 · §9.2 性能纪律硬约束）：`FlowMapStore.topologicalChains`
    * 每次调用全量重算 `combinedNodes` + 弱连通分量（O(N+E)）——**只允许在节点 spawn
    * 时调用一次**，禁止进事件流/结果落盘/索引对账等热路径（逐节点调用放大为
    * O(N·E)，§9.2 :571）。
    * 本方法 = `chainIdOf` 判据单点的手工展开（同一次分量重算里同时取得 chainId /
    * title / memberCount 三值——若调 chainIdOf 再算一次分量即双重全量重算），口径
    * 与 chainIdOf 逐字同源：合并集（活动∪归档）上取含本节点的分量，成员数 ≥2 才
    * 带值（孤立单节点链 = 无链，与载荷 chainId 条件键恒同）。 */
  private[project] def chainContextOf(nodeId: String): IO[Option[NodeEngine.NodeChainContext]] =
    store.combinedNodes.map { combined =>
      FlowMapStore.topologicalChains(combined.values)
        .find(_.memberIds.contains(nodeId))
        .filter(_.memberIds.size >= 2)
        .map { ci =>
          NodeEngine.NodeChainContext(
            chainId = ci.id,
            title = FlowMapStore.chainTitle(ci.memberIds.flatMap(combined.get), ci.id),
            memberCount = ci.memberIds.size
          )
        }
    }

  /** 构造节点输入：链上下文块（链头 + 文件名尾溯源提示段，仅本节点有链时注入）+ 自身
    * task 上下文 + 各上游 result（=== Node <name> === 头，§2.7）+ blocked 声明协议
    * 脚注（设计 §1.5 原文，单点注入覆盖所有节点——节点 agent 是通用全局 agent，
    * system prompt 不含约定，必须随输入注入）。
    * 收敛裁定（作者 2026-09-07）：项目记忆=分发器配置知识——分发器建节点时把关键
    * 口径写进节点 task，节点侧不再注入记忆全文。节点上下文=task+上游结果+AGENTS.md；
    * 节点每 spawn 省一份记忆全文 token（多节点并行批次收益可观）；AGENTS.md 每 turn
    * 注入面不受影响。全局注入面（ContextRefresher）不含项目记忆——瘦身边界不变。
    * TaskBoard 批 2（§3a）：头部追加任务板块（在任务文本之前；无板/三段皆空 →
    * 不注空段）——「自身工单 id」是节点显式上报（决策 d）的使能器。
    * 链级抽象 P2（§9.2 项 7/8；提示段形态 = 2026-09-11 R-3 裁定后的文件名尾溯源）：
    * 链头 + 溯源提示段置于最前（首屏可见，agent 先
    * 看到链归属再读任务）；`chain` 参数由调用方（startNode）传入 spawn 时刻快照——
    * 无链（None）→ 整块不注入（不注空行/零占位）。 */
  private[project] def buildInput(node: NodeDef, chain: Option[NodeEngine.NodeChainContext]): IO[String] =
    val ownTask = node.task.getOrElse("")
    // 链上下文块（有链才有）：链头一行 + 紧随的可复制文件名尾溯源提示段（文本尽量短）
    // ——两行同一块（块内不空行，「提示段紧随链头」字面口径），块间仍以空行分隔。
    val chainBlock: List[String] =
      chain.toList.map(c => NodeEngine.chainHeaderLine(c) + "\n" + NodeEngine.DocProvenanceBlock)
    taskBoardNodeBlock(node).flatMap { boardBlock =>
      node.in.traverse { upId =>
        store.findNode(upId).map {
          case Some(up) =>
            // P1（spec §2.2 #3）：signal 边不投载荷（deps 同款，下游输入 = 自身 task）。
            // 判定沿上游→本节点的全部边：存在 result 边 → 注入（result 意图优先）；
            // 全 signal → 抑制（含 failed 上游 result=错误文本不作输入投递的 signal
            // 场景）；无边（修复/悬空路径）→ 注入（现状兜底）。
            val toHere = up.out.filter(_.to == node.id)
            val suppressed = toHere.nonEmpty && toHere.forall(_.mode == OutEdge.Signal)
            if suppressed then None
            else up.result.map(res => s"=== Node ${up.name} ===\n$res")
          case None => None
        }
      }.map { upstream =>
        (chainBlock ++ List(boardBlock, ownTask).filter(_.nonEmpty) ++ upstream).mkString("\n\n") + "\n\n" + NodeEngine.ProtocolFootnote
      }
    }

  private def spawnAndRun(node: NodeDef, inputText: String, resume: Option[NodeEngine.ResumeContext] = None,
      chain: Option[NodeEngine.NodeChainContext] = None): IO[Unit] =
    val nodeId = node.id
    for
      agentOpt <- EntityLoader.loadAgent(node.agent)
      _ <- agentOpt match
        case None =>
          failNode(nodeId, s"agent '${node.agent}' not found in global library")
        case Some(entry) =>
          // 阶段 2b Plugins（§B.4 第 4 步，spawn 前执行）：① 解析 node.plugins
          // （untrusted/不存在/装载非法 → failNode，错误消息列明原因——分配失败是
          // 节点级失败，不静默降级）；② skill 全文读出 + ${SKILL_DIR} 替换
          // （SkillService.loadSkill 单点复用）组装 <injected-plugins> 块；
          // ③ MCP server 启动 + 引用记账（PluginMcpManager，启动失败 → failNode）。
          // 三步全部发生在状态翻转（status=Running）之前——失败路径零 running 残留。
          // §E.3 preset 消费（协议符合度批接通，2b 遗留）：同样翻转前 failNode。
          // resume（crash-recovery 批 D2/D3）：插件/preset/装配链逐行复用，仅两处
          // 差异——sessionId 复用 sessionRef 旧 id（transcript 单文件续写 + F2 队列
          // 重放白捡，BackoffSupervisor respawn 同款先例）+ initialMessages 水合。
          nodePresetDef(node, entry).flatMap {
            case Left(err) => failNode(nodeId, err)
            case Right(baseDef) =>
              prepareNodePlugins(node).flatMap {
                case Left(err) => failNode(nodeId, err)
                case Right(prepared) =>
                  val sessionId = resume.fold(s"node-${java.util.UUID.randomUUID().toString.take(8)}")(_.sessionId)
                  val inputWithPlugins =
                    if prepared.injectedBlock.isEmpty then inputText
                    else inputText + "\n\n" + prepared.injectedBlock
                  resources.pluginMcp.acquire(sessionId, prepared.mcpPlugins).flatMap {
                    case Left(err) => failNode(nodeId, err)
                    case Right(grant) =>
                      // 回收兜底（§B.4 第 5 步）：completed/failed/cancelled/blocked 全
                      // 终态汇合点=runWithAgent 完成；异常中止路径由 guarantee 补位。
                      // release 幂等（PluginMcpManager 内 no-op 语义），双保险不重复卸载。
                      runWithAgent(node, baseDef, inputWithPlugins, sessionId, prepared, grant, resume, chain)
                        .guarantee(resources.pluginMcp.release(sessionId))
                  }
              }
          }
    yield ()

  /** LoopNode spawn（LoopNode 批 2026-09-06）：与 spawnAndRun 同骨——加载 worker/
    * verify 两 agent、准备插件、acquire 各会话 MCP grant、翻转 Running、spawn 双会话、
    * 驱动 loop。终态（PASS/failed/cancelled/blocked/达 K）由 runLoopNode 落终态化，
    * 会话/MCP/running 清理在 guarantee 内（裁定 B 双销毁不残留）。 */
  private def spawnAndRunLoop(node: NodeDef, inputText: String, resume: Option[NodeEngine.ResumeContext] = None,
      chain: Option[NodeEngine.NodeChainContext] = None): IO[Unit] =
    val nodeId = node.id
    val loopCfg = node.loop.get
    // crash-recovery 批裁定③：resume=Some 时双会话复用 sessionRef（worker）/
    // sessionRefVerify（verify）旧 id + 各自 transcript 水合，runLoopNode 从崩溃时
    // loopRound/loopPhase 断点续跑（worker→verify 迭代跨 kill -9 续接）。
    val workerSessionId = resume.fold(s"node-${java.util.UUID.randomUUID().toString.take(8)}")(_.sessionId)
    // verify 会话：恢复优先复用旧 id（D2）；无旧 ref（崩溃于 flip 前/历史数据）→ 新 id。
    val verifySessionId = resume.flatMap(_.verifySessionId).getOrElse(s"node-${java.util.UUID.randomUUID().toString.take(8)}")
    // projectRoot 解析（与 runWithAgent 同源单点：PathUtil.resolveNodeProjectRoot）
    val projectRoot =
      node.worktree match
        case Some(wt) if PathUtil.normalizeWorktree(wt).isLeft =>
          val pr = PathUtil.resolveNodeProjectRoot(workspace, node.worktree)
          logger.warnSync(
            s"Node '${node.name}' (${node.id}) has corrupt worktree value '$wt' — projectRoot fell back to workspace ($pr)")
          pr
        case _ => PathUtil.resolveNodeProjectRoot(workspace, node.worktree)
    for
      workerAgentOpt <- EntityLoader.loadAgent(node.agent)
      verifyAgentOpt <- EntityLoader.loadAgent(loopCfg.verify)
      _ <- (workerAgentOpt, verifyAgentOpt) match
        case (None, _) => failNode(nodeId, s"worker agent '${node.agent}' not found in global library (loop node)")
        case (_, None) => failNode(nodeId, s"verify agent '${loopCfg.verify}' not found in global library (loop node)")
        case (Some(wEntry), Some(vEntry)) =>
          // worker 侧 preset 消费（§E.3 同款）；verify 侧无单独 preset（LoopConfig 精简，
          // 与 node.preset 归 worker 的 §2.1 口径一致）。
          nodePresetDef(node, wEntry).flatMap {
            case Left(err) => failNode(nodeId, err)
            case Right(workerBase) =>
              val verifyBase = vEntry.toAgentDef
              // worker/verify 均注入 node.plugins（§2.1 verify=通用 agent+plugins，验证域
              // 分配共享同域能力包）；各 session 独立 acquire MCP grant（引用记账分离）。
              prepareNodePlugins(node).flatMap {
                case Left(err) => failNode(nodeId, err)
                case Right(prepared) =>
                  for
                    wGrantE <- resources.pluginMcp.acquire(workerSessionId, prepared.mcpPlugins)
                    vGrantE <- resources.pluginMcp.acquire(verifySessionId, prepared.mcpPlugins)
                    _ <- (wGrantE, vGrantE) match
                      case (Left(err), _) => failNode(nodeId, err)
                      case (_, Left(err)) => failNode(nodeId, err)
                      case (Right(wGrant), Right(vGrant)) =>
                        for
                          cancelSig <- Deferred[IO, Unit]
                          _ <- flipToRunning(node, cancelSig, workerSessionId, nodeId, node.name, Some(verifySessionId))
                          worker <- spawnLoopSession(workerBase, prepared, wGrant, workerSessionId, node.name, projectRoot,
                            initialMessages = resume.fold(List.empty[Message])(_.recoveredMessages),
                            // TaskBoard 批 2（§1d）：loop worker/verify 会话同属该
                            // loop 节点——flowNodeId 身份与普通节点同源（权限矩阵
                            // 同面：仅自己名下任务 status+note）。
                            flowNodeId = Some(nodeId),
                            // D6 批 F1（G9 路径 a）：节点名随路注入（AskUser 归因）。
                            flowNodeName = Some(node.name),
                            // 链级抽象 P2（§9.2 项 5）：worker/verify 同属该 loop
                            // 节点 → 同一条链的同一快照（startNode 单点算出）。
                            flowChainId = chain.map(_.chainId))
                          verify <- spawnLoopSession(verifyBase, prepared, vGrant, verifySessionId, s"${node.name}-verify", projectRoot,
                            initialMessages = resume.fold(List.empty[Message])(_.verifyMessages),
                            flowNodeId = Some(nodeId),
                            flowNodeName = Some(node.name),
                            flowChainId = chain.map(_.chainId))
                          _ <- runLoopNode(node, worker, verify, inputText, cancelSig, resume)
                            .guarantee(
                              destroyLoopSessions(nodeId, worker, verify) *>
                                resources.pluginMcp.release(workerSessionId) *>
                                resources.pluginMcp.release(verifySessionId) *>
                                running.update(_ - nodeId) *>
                                nodeSessions.update(_ - nodeId)
                            )
                        yield ()
                  yield ()
              }
          }
    yield ()

  /** §E.3 preset 消费接通（协议符合度批补齐，2b 遗留）：node.preset →
    * PresetResolver 单点解析（Delegate/SubTask #291 先例同款）——预设链写入
    * AgentDef.model/preset/modelOverride，modelOverride 经 ContextRefresher
    * 每 turn 热重载保活。解析失败（不存在/空链，错误含可用预设清单）= 节点级
    * 失败，状态翻转前 failNode（与插件准备同纪律：失败路径零 running 残留）。
    * node.preset 缺省 → 原 AgentDef 原样透传（旧行为零变化）。 */
  private def nodePresetDef(node: NodeDef, entry: nebflow.core.entity.AgentEntry): IO[Either[String, nebflow.agent.AgentDef]] =
    IO.blocking {
      PresetResolver.applyPreset(PresetStore(), entry.toAgentDef, node.preset).left.map { err =>
        s"Node '${node.name}' preset '${node.preset.getOrElse("")}' unresolved: $err " +
          "(§E.3 node.preset consumption — presets live in model-presets.json)"
      }
    }

  /** node.plugins → 可分配能力（§B.4 第 4 步 ①②，feature flag §G.2 开关）：
    * flag off / 无分配 → 空 preparation（旧行为零变化）；解析失败 → Left
    * （failNode，错误含审批指引）。 */
  private def prepareNodePlugins(node: NodeDef): IO[Either[String, NodeEngine.PluginPreparation]] =
    PluginsConfig.enabled.flatMap {
      case false => IO.pure(Right(NodeEngine.PluginPreparation.empty))
      case true =>
        if node.plugins.isEmpty then IO.pure(Right(NodeEngine.PluginPreparation.empty))
        else
          node.plugins.traverse(PluginRegistry.resolve).flatMap { resolved =>
            val errors = resolved.collect { case Left(e) => e }
            if errors.nonEmpty then IO.pure(Left(errors.mkString(" | ")))
            else
              val defs = resolved.collect { case Right(d) => d }
              defs.traverse(injectedPluginBlock).map { blocks =>
                val inner = blocks.collect { case Right(b) if b.nonEmpty => b }
                Right(NodeEngine.PluginPreparation(
                  injectedBlock =
                    if inner.isEmpty then ""
                    else s"<injected-plugins>\n${inner.mkString("\n\n")}\n</injected-plugins>",
                  mcpPlugins = defs.filter(_.mcpServers.nonEmpty),
                  builtinTools = defs.flatMap(_.toolsExtension).distinct
                ))
              }
          }
    }

  /** 逐 plugin 逐 skill 读 SKILL.md 全文（frontmatter 去除 + ${SKILL_DIR} 替换，
    * SkillService.loadSkill 单点复用——修复「模型自读拿不到替换」缺口，§B.4 ②）。
    * 文件不可读 → Left（节点级失败，不静默降级）。
    *
    * 数据根渲染（home 硬编码 → 运行时动态化批 2026-09-11）：注入块里的
    * `{{data_root}}` 在此渲染为实例数据根（PathUtil.substituteDataRoot，与
    * AgentCore.buildSystemPrompt / DispatcherContextCatalog.render 同一实现）——
    * 隔离实例的节点看到的是**本实例**的 home 路径，而非固定 `~/.nebflow`。 */
  private def injectedPluginBlock(defn: PluginRegistry.PluginDef): IO[Either[String, String]] =
    if defn.skills.isEmpty then IO.pure(Right(""))
    else
      defn.skills.traverse { sk =>
        SkillService.loadSkill(sk.path).flatMap {
          case Some(content) =>
            IO.pure[Either[String, String]](Right(
              s"""<plugin name="${defn.name}" skill="${sk.name}">
                 |${PathUtil.substituteDataRoot(content.content)}
                 |</plugin>""".stripMargin))
          case None =>
            IO.pure[Either[String, String]](Left(
              s"Plugin '${defn.name}' skill '${sk.id}' file unreadable: ${sk.path} — allocation refused (no silent degradation)"))
        }
      }.map { blocks =>
        blocks.find(_.isLeft) match
          case Some(Left(err)) => Left(err)
          case _ => Right(blocks.collect { case Right(b) => b }.mkString("\n\n"))
      }

  /** 信任门运行时重验（§B.5 信任联动，ProjectActor.TtlTick 30s 驱动）：停用
    * digest 失效的运行中 plugin MCP + 对持有会话发系统提醒。flag off → no-op。
    * 可见性批（2026-09-10 P1 静默缩容）：重验即插件重扫完成点——同处聚合输出一次
    * 装载健康摘要（拒载/未批准/digest 漂移清单；干净场景零输出、同状态去重）。 */
  def revalidatePluginTrust(): IO[Unit] =
    PluginsConfig.enabled.flatMap {
      case false => IO.unit
      case true =>
        resources.pluginMcp.revalidate(
          PluginRegistry.scan(),
          (sessionId, text) =>
            resources.agentRegistry.get.flatMap { reg =>
              reg.get(sessionId).map(_.ref) match
                case Some(ref) =>
                  (ref ! AgentCommand.ImmediateInput(text, source = Some("system"), fromUser = false)).void
                case None => IO.unit // 会话已终结——提醒无投递面，server 已停即足够
            }
        ).void *>
          PluginRegistry.logHealthSummary("rescan")
            .handleErrorWith(e => logger.warn(s"plugin health summary failed: ${e.getMessage}"))
    }

  private def runWithAgent(
    node: NodeDef,
    baseDef: nebflow.agent.AgentDef, // §E.3 preset 消费：已过 PresetResolver 的 AgentDef（协议符合度批）
    inputText: String,
    sessionId: String,
    prepared: NodeEngine.PluginPreparation,
    grant: PluginMcpManager.Grant,
    /** crash-recovery 批 D3：Some = 崩溃恢复续跑（initialMessages=水合 transcript，
      * inputText=resume prompt）；None = 新鲜启动（行为与本批前逐字节一致）。 */
    resume: Option[NodeEngine.ResumeContext] = None,
    /** 链级抽象 P2（§9.2 项 4）：节点所属链快照（spawnAndRun 由 startNode 单点算好
      * 传入——与首条消息链头同源）。None = 无链（孤立单节点分量/不在双区）。 */
    chain: Option[NodeEngine.NodeChainContext] = None
  ): IO[Unit] =
    val nodeId = node.id
    // sessionId 由 spawnAndRun 生成传入（阶段 2b：plugin MCP acquire 引用记账
    // 需在 spawn 前拿到同一 id——终态回收 release(sessionId) 靠它对账）。
    // projectRoot 解析（20260903 worktree 参数修复）：归一化 + worktrees/ 权威
    // 位置优先、顶层存量 fallback 的双查单点在 PathUtil.resolveNodeProjectRoot
    // （与 NodeEdit 校验同源，参照系唯一）。顶层命中与旧公式
    // `(os.Path(workspace) / ".nebflow" / wt).toString` 逐字节一致（回归红线）；
    // 两处均不存在 → 旧公式路径；损坏存储值 → workspace 兜底（不再 InvalidSegment
    // 炸 spawn）并 warnSync 留痕（QC P2：fail-safe 必须可排查）。
    val projectRoot =
      node.worktree match
        case Some(wt) if PathUtil.normalizeWorktree(wt).isLeft =>
          val pr = PathUtil.resolveNodeProjectRoot(workspace, node.worktree)
          logger.warnSync(
            s"Node '${node.name}' (${node.id}) has corrupt worktree value '$wt' — projectRoot fell back to workspace ($pr)")
          pr
        case _ => PathUtil.resolveNodeProjectRoot(workspace, node.worktree)
    val nodeName = node.name
    // 清理硬化（僵尸收敛批 2026-09-06，根因报告漏洞①）：runSig 桥接 guarantee 与
    // for 内才创建的 cancelSig——finalizer 在任意退出路径（正常/崩溃/异常/cancel）
    // 读到 cancelSig 执行 cleanupRunTables 三表对称移除（防泄漏「假活 canary」）。
    // cancelSig 未创建（for 前崩溃）→ None → 无登记可清，no-op。
    val runSig = Ref.unsafe[IO, Option[Deferred[IO, Unit]]](None)
    (for
      // 在飞登记先行（清场 c-① liveness 误杀防护 20260903）：running 表先于
      // status=Running 翻转——NodeList liveness / abandon / NodeCancel 收殓判定
      // 以「running 表含此节点」为活会话信号，若先翻转后登记，spawn 窗口内的
      // 节点会被误判死会话（false-dead → 可被收殓 = 误杀）。
      // 条件注册（trigger-chain-fix fork 化硬化）：触发链并行化后，同节点多触发
      // 方（deliverOut/settleDeps/settleSweep）并发到达本点——盲目覆写会顶掉先到
      // 者的 cancelSig（NodeCancel 信号永久丢失）。只在空位时注册；翻转赢家在
      // 翻转成功后重装自己的 sig（FlipDone 分支），最终条目必属活会话 fiber。
      // liveness 判定只看键存在，不受值选择影响。
      cancelSig <- Deferred[IO, Unit]
      _ <- runSig.set(Some(cancelSig))
      _ <- running.modify { m =>
        if m.contains(nodeId) then (m, ())
        else (m + (nodeId -> cancelSig), ())
      }
      // NodeMessage 注入链登记（20260905 机制批）：与 running 表同点置位——
      // sendNodeMessage 经本映射定位会话 actor；清理见下方对称移除。
      _ <- nodeSessions.update(_ + (nodeId -> sessionId))
      // 状态 → running + startedAt（WS nodeUpdated，NodeList 同构 payload）。
      // 竞态修复（与终态化同族）：running 迁移落在事务内现读的 fresh 节点上——
      // getNode 快照经 EntityLoader 加载 agent 期间可能已落后，陈旧 copy 写回会
      // 覆盖该窗口内的接线变更；None = 节点已消失 → 中止 spawn（拒写复活垃圾行）。
      // barrier 事务性复核（fix a 启动判定完整性 20260903）：startNode 入口的
      // in ⊆ deliveredTo 检查沿 getNode 快照，快照与翻转之间并发接线（edit 追加
      // in）可增长 in——沿陈旧快照放行 = 以不完整 barrier 提前启动（未等齐）。
      // 复核与翻转并入同一事务：fresh barrier 未齐 → 拒翻转不 spawn（status 保持
      // 原态，等真正等齐时的投递/结算方重试 startNode）。
      // CAS 翻转守卫（trigger-chain-fix §6.1 前置硬化）：仅 Pending/Wiring 可翻
      // 转。fork 化后多触发方并发通过①②③闸门（都沿陈旧 Pending 快照），无守卫
      // 的第二个 mutate 会无视已是 Running 的事实照常覆写 → 双 spawn（第二个覆写
      // running 表与 startedAt，双会话双计费、cancel 信号错投）。守卫 +
      // mutateWithResult 事务内三态判定：Done=本 fiber 唯一翻转（继续 spawn）；
      // LostRace=败方（他者已翻转到 Running——fork 并行化的正常形态，安静回滚，
      // 不事件不抛错：上游每次完成都产生同节点竞发，事件化=刷屏）；Aborted=真异
      // 常（消失/barrier 未齐/终态竞合）→ start-aborted 事件落账（§6.3 异常信号
      // 源，不再只靠异常上抛）+ 错误上抛由调用方留痕。
      now <- IO(System.currentTimeMillis())
      (flipped, flipOutcome) <- store.mutateWithResult { s =>
        s.nodes.get(nodeId) match
          case Some(fresh)
              if (fresh.status == NodeLifecycle.Pending || fresh.status == NodeLifecycle.Wiring)
                && !fresh.in.exists(up => !fresh.deliveredTo.contains(up)) =>
            // sessionRef 与 startedAt 同事务落库（crash-recovery 批 D1）：崩溃后 boot
            // sweep 据此定位磁盘 transcript；终态不清除（审计价值）。
            // 未申报计时同事务清零（noderpt 批 A 段 2026-09-11）：翻转 = 新一轮/新会话
            // （首次启动 / reactivate 重激活 / boot resume / 挂起恢复），旧一轮的计时
            // 起点与拍数一律不继承（否则新会话一交棒就按旧起点直接跳到高拍）。
            // **销毁窗口同点撤销**（noderpt 批 B 段）：翻转 = 节点重新 Running ⇒ 旧的
            // `destroyAt` 窗口作废（节点在窗口内被重激活/重跑时，绝不按旧计划销毁在跑的
            // 进程）；禁 spawn 表项由翻转后的 `BgTaskRegistry.reopenSession` 同步解除。
            (s.copy(nodes = s.nodes.updated(nodeId, fresh.copy(
              status = NodeLifecycle.Running,
              startedAt = Some(now),
              sessionRef = Some(sessionId),
              reportPendingSince = None,
              reportReminderCount = 0,
              destroyAt = None))), NodeEngine.FlipOutcome.Done)
          case Some(fresh) if fresh.status == NodeLifecycle.Running =>
            (s, NodeEngine.FlipOutcome.LostRace)
          case Some(fresh) if fresh.in.exists(up => !fresh.deliveredTo.contains(up)) =>
            (s, NodeEngine.FlipOutcome.Aborted("in-barrier not settled (concurrent rewiring)"))
          case Some(fresh) =>
            (s, NodeEngine.FlipOutcome.Aborted(s"state changed to '${fresh.status}' before flip (concurrent finalize)"))
          case None =>
            (s, NodeEngine.FlipOutcome.Aborted("node vanished"))
      }
      _ <- flipOutcome match
        case NodeEngine.FlipOutcome.Done =>
          // 赢家重装自己的 cancelSig：条件注册期间条目可能仍是竞发者的占位——
          // 最终条目必须属于本活会话 fiber（NodeCancel 信号才能到达本会话）。
          running.update(m => m + (nodeId -> cancelSig)) *>
            // 销毁窗口撤销的第二步（noderpt 批 B 段）：把**上一轮**的会话解出禁 spawn 表
            // （旧 sessionRef 可能正是窗口内被登记的那个；新 sessionId 是新 id，不受影响）。
            // 不清 ⇒ 重激活/重跑后旧 id 永久禁 spawn（虽无新调用方，仍是错误状态）。
            node.sessionRef.traverse_(BgTaskRegistry.reopenSession) *>
            flipped.nodes.get(nodeId).traverse_(runningDef =>
              emitWithChain("nodeUpdated", nodeId, NodePayload.buildNodeJson(runningDef, System.currentTimeMillis())))
        case NodeEngine.FlipOutcome.LostRace =>
          // CAS 败方：按 sig 身份只回滚自己的登记（不得误删赢家的条目），抛
          // StartRaceLost 终止本 fiber 的 for 推导（否则败方继续 spawn = 双会话，
          // M1 demo 实证 6 路并发 6 会话）。异常由 startNode 的 handleErrorWith
          // 精准吞掉——对全部调用方呈安静败方语义。nodeSessions 对称移除
          //（NodeMessage 注入链，防泄漏僵尸映射）。
          running.modify {
            case m if m.get(nodeId).exists(_.eq(cancelSig)) => (m - nodeId, ())
            case m => (m, ())
          } *> nodeSessions.update(_ - nodeId) *>
            IO.raiseError(NodeEngine.StartRaceLost(nodeId))
        case NodeEngine.FlipOutcome.Aborted(reason) =>
          // 真异常中止：按身份回滚 + start-aborted 事件（原语义「回滚在飞登记并
          // 中止」保留，异常信号从「仅错误上抛」扩展为「事件 + 上抛」双通道）。
          // nodeSessions 对称移除（NodeMessage 注入链，防泄漏僵尸映射）。
          running.modify {
            case m if m.get(nodeId).exists(_.eq(cancelSig)) => (m - nodeId, ())
            case m => (m, ())
          } *> nodeSessions.update(_ - nodeId) *>
            FlowMapEventLog.append(workspace, projectName, nodeId, "start-aborted",
              s"start aborted: $reason") *>
            IO.raiseError(new RuntimeException(
              s"Node '$nodeName' ($nodeId) start aborted — $reason"))
      resultDeferred <- Deferred[IO, Either[FailOutcome, List[Message]]]
      // 阶段 2b Plugins（§B.4 第 4 步 ③）：plugin MCP 前缀来源 + 内建工具授予
      // 进该会话 allowedSet（buildAllowedToolSet 扩展消费；仅运行时 AgentDef，
      // 不落 agent.json）。bridge actor 已由投递可靠性批次迁移至 spawnAgentActor
      // 之后（升级 ctx.watch 会话死亡兜底）——旧内联位置删除，本行仅保留 2b 的
      // agentDef 扩展语义。
      agentDef = baseDef.copy(
        pluginMcpServers = grant.serverIds,
        pluginTools = prepared.builtinTools
      )
      ref <- NodeRunner.spawnAgentActor(
        system,
        NodeRunner.SpawnParams(
          agentDef = agentDef,
          resources = resources,
          sessionId = sessionId,
          sessionName = nodeName,
          depth = 1,
          parentRef = None, // 节点无 Mail 身份（§硬约束）
          // #28 可观测接线：节点事件必须经路由包装注入 rootSessionId（前端
          // sessionBgAgents 归桶键）+ nodeSessionId（popup/历史路由）——否则
          // 子 agent 事件无法在 subagent 面板归到根会话（与 Delegate/SubTask
          // 同一可观测性标准）。project：agentStart 帧注入项目名（面板项目徽标）。
          wsSend = NodeRunner.routeSubagentWsSend(wsSendFn, rootSessionId, sessionId, Some(projectName)),
          projectRoot = Some(projectRoot),
          safetyMode = "confirm-edits",
          rootSessionId = rootSessionId,
          isFlowNode = true, // leaf 剥离（与 flow 节点一致：无 Node 工具/展示类）
          // TaskBoard 批 2（§1d）：节点引擎侧身份（flowNodeId=NodeDef.id）+ 项目
          // 上下文——AgentCore 透传 ToolContext 后 TaskBoard 权限矩阵据此判定
          // （仅自己名下任务、仅 status+note），project 缺省解析随之生效。
          flowNodeId = Some(nodeId),
          projectName = Some(projectName),
          // D6 批 F1（G9 路径 a）：节点人类可读名随 spawn 注入——AskUser payload
          // nodeName 字段来源（badge「project · nodeName」+ node-ask 留痕事件）。
          flowNodeName = Some(nodeName),
          // 链级抽象 P2（§9.2 项 4）：链身份随 spawn 注入（spawn 时刻快照，
          // startNode 经 chainContextOf 单点取值）——AgentCore 透传
          // ToolContext.flowChainId，节点产出据此在过程文档**文件名尾段**写链归属
          // `__<chainId>`（正文零元数据头；2026-09-11 作者裁定 R-3）。
          // None = 无链（孤立单节点分量/不在双区）——口径与载荷 chainId 恒同。
          flowChainId = chain.map(_.chainId),
          // 阶段 2a 沙箱（§A.6）：dev/修复节点 root=<workspace>/.nebflow/<wt>、
          // merge 节点 root=workspace——物理隔离，最小权限。
          sandboxEnabled = true,
          // 项目会话信号（沙箱拆围栏批 S1/R8 解耦）：本 spawn 点 = 项目节点会话
          // ⇒ AGENTS.md 注入判据置位（与沙箱总闸解耦，避免拆围栏时连带关掉项目
          // 契约注入）。语义见 SessionContext.projectSession。
          projectSession = true,
          // [2026-09-05 21:05 作者裁定——worktree 节点继承项目沙箱]：沙箱根=
          // 项目工作区根（不收窄到 worktree 自身）——主仓 .git/worktrees/<name>/
          // 元数据在工作区内，git commit / worktree remove 直写不再 EPERM。
          // 独立信号传入（sessionRoot 显式 sandboxRoot 优先于 projectRoot），
          // 不按路径形态硬猜 workspace 布局；cwd/projectRoot 工具语义不动。
          sandboxRoot = Some(workspace),
          // crash-recovery 批 D3：恢复续跑时水合磁盘 transcript（NodeRunner.
          // initialMessages 通道，BackoffSupervisor respawn 同款）；spawn 时
          // RecoverPersistedQueues 自动重放该会话崩溃前排队中的 F2 注入。
          initialMessages = resume.fold(List.empty[Message])(_.recoveredMessages)
        )
      )
      // Bridge actor：捕获 Completed/Failed/Cancelled → Deferred（同步 complete，
      // 防 Behaviors.stopped 竞态——同 FlowDagExecutor 教训）。
      // 缺口1（2026-09-04 会话死亡假尸修复，EphemeralAgentRunner 先例同款）：
      // bridge ctx.watch(agentRef)——会话无终态事件死亡（processing.onError 静默
      // 回 idle 后被 Stop、裸崩溃退出 loop、外部 fiber cancel）时 Terminated 在此
      // 兜底完成 deferred，engine fiber 不再永久挂在 race 上、节点不再滞留 running
      // 成假尸。Terminated-without-event 只可能是异常死亡：全部合法取消路径
      // （NodeCancel→cancelSig、AgentControl/TaskStuckWatcher giveUp→
      // AgentEvent.Cancelled）都先于死亡到达 bridge——故按 **failed** 语义终态化
      // （failNode 既有链：WARN + deliverFailed；下游 barrier/deps 按既有 failed
      // 语义零改动，不伪造结果投递）。WARN 含 sessionId+nodeId+失败原因。
      //
      // ═══ 节点完成闸（bgtask-completion-gate 批，作者 2026-09-05 18:29 裁定）═══
      // 问题：节点把编译/CI 等长命令跑后台后结束轮次，engine 按「turn 结束=完成」
      // 终态化节点并把结果传下游——后台任务实际没干完，完成通知注入死会话丢失。
      // 方案（桥单咽喉 hold）：Completed 分支先查 BgTaskRegistry.waitingFor
      // (sessionId)（该节点名下等待型后台任务，即「完成时会向本会话注入通知」的
      // 任务；persistent 服务型已被 registry 过滤）：
      //   非空 → hold：不 complete、桥保持存活、FlowMapEventLog 留痕（bg-wait，
      //   先例 held/blocked 语义族但不复用——held=人工 release 放行，bg 等待是
      //   自动续行）+ 武装兜底计时器（每等待期重臂）。后台任务完成回调经
      //   AgentCommand.ExternalEvent(source="background-task") 注入仍活着的 agent
      //   → 新轮次（AgentActor idle 唤醒轮对本桥 supervisorRef 发 Completed——
      //   粘性完成目标）→ 轮次终 → 又一次 Completed → 此处复检。
      //   清空 → drainFailures 检查终局记账（超时/停滞杀的 failed 注明，拒绝静默
      //   completed）→ complete resultDeferred（result=最后一轮文本，agent 已消化
      //   全部后台通知）。
      // 三态：无后台任务 → 立即放行（现状零变化）；等待型 → 拦截至全完；
      // persistent 服务型 → 不等待。兜底面（后台任务不能卡死节点）：
      //   ①等待期超 bgWaitCapMs → failed 注明（TaskStuckWatcher 对等待期 Idle
      //     节点不判卡死——Idle 是合法状态，故需自身兜底防通知链断裂悬挂）；
      //   ②bg 任务被超时/停滞看护杀 → registry 记账 → failed+注明。
      // bg 等待先于 hold/blocked 分流发生（本桥在 completeNode 之前）——hold 节点
      // 也是先等后台再 held，语义正确。
      bridgeRef <- system.spawn(
        Behaviors.setup[AgentEvent] { bctx =>
          bctx.watch(ref) *>
            IO {
              // hold 期状态：留痕单发标记 + 兜底计时器句柄（每等待期重臂）。
              val holdEmitted = Ref.unsafe[IO, Boolean](false)
              val waitCapFiber = Ref.unsafe[IO, Option[Fiber[IO, Throwable, Unit]]](None)
              def disarmCap: IO[Unit] =
                waitCapFiber.get.flatMap(_.traverse_(_.cancel).void)
              def armCap(waiting: List[BgTaskRegistry.ActiveTask]): IO[Unit] =
                disarmCap *>
                  {
                    // 兜底计时器：等待期内无任何后台完成复检达 bgWaitCapMs →
                    // failed 注明。措辞注意：completeNode 以 message.contains
                    // ("cancelled") 分流 cancelNode，本文案不得含该词。
                    val capBody: IO[Unit] =
                      IO.sleep(bgWaitCapMs.millis) *>
                        FlowMapEventLog.append(
                          workspace, projectName, nodeId, "bg-wait-timeout",
                          s"background wait cap (${bgWaitCapMs / 1000}s) hit with ${waiting.size} task(s) pending — finalizing failed") *>
                        waitCapFiber.set(None) *>
                        // ⑤ 残留字段收口（noderpt 批 B 段实测缺陷：`n-0931699e` status=completed
                        // 而 `bgWait` 非空）：本出口此前只 complete deferred、**不写
                        // `setNodeBgWait(Nil)`** ⇒ 首个 hold 期置位的 bgWait 随节点进终态/归档
                        // 永久残留（与 `setNodeBgWait` 头注「flow-map.json 不残留过期 bgWait」
                        // 的契约相悖，也让前端把终态节点误标「等待后台任务」）。此处补写——
                        // fresh 守卫要求 status==Running，此刻节点仍 Running（终态化在后），
                        // 故写点有效；幂等（值未变不写不 event）。
                        setNodeBgWait(nodeId, Nil) *>
                        resultDeferred
                          .complete(Left(FailOutcome(
                            s"background task wait cap exceeded (${bgWaitCapMs / 1000}s): still waiting for " +
                              waiting.map(t => s"'${t.description}' (${t.jobId})").mkString(", ") +
                              " — node finalized as failed by the background-completion gate; " +
                              "the background job(s) keep running and their completion notification may arrive at a finalized session"
                          ))).attempt.void
                    capBody.start.flatMap(f => waitCapFiber.set(Some(f)))
                  }
              // 终局记账 → failed 注明文案（含 agent 消化失败通知后的最终输出，
              // 截断防串膨胀；杀因原文净化同上）。
              def bgFailureMessage(
                failures: List[BgTaskRegistry.FailedBgTask],
                msgs: List[Message]
              ): String =
                val causes = failures
                  .map { f =>
                    s"'${f.description}' (${f.jobId}): ${f.cause.replace("cancelled", "auto-stopped")}"
                  }
                  .mkString("\n  - ")
                val tail = extractLastAssistantText(msgs)
                val tailNote =
                  if tail.trim.nonEmpty then
                    s"\n\nNode agent final output (after consuming the failure notification):\n${tail.take(2000)}"
                  else ""
                s"background task(s) in the node wait set were killed by the background guard (idle/stall watchdog):\n  - $causes$tailNote"
              lazy val bridge: Behavior[AgentEvent] = new Behavior[AgentEvent]:
                def receive(ctx: ActorContext[AgentEvent], event: AgentEvent): IO[Behavior[AgentEvent]] =
                  event match
                    case AgentEvent.Completed(_, messages) =>
                      // 完成门（noderpt 批 A 段 2026-09-11 作者裁定）——同一闸门两条腿：
                      //   腿 1「后台任务存活」（`bgGateHoldEnabled`，默认 false = 封存）：
                      //     关 ⇒ **不查** BgTaskRegistry.waitingFor，直接用空等待集走放行
                      //     分支（hold/armCap 代码逐字保留，开 flag 即恢复逐字旧行为）；
                      //   腿 2「未申报 node_report」（`reportGateHoldEnabled`，默认 true）：
                      //     放行前非消费读申报槽——空 ⇒ **不终态化**（节点保持 Running、
                      //     会话存活）并起表（只置不重）；非空 ⇒ 清表后放行，终态点 drain
                      //     照常分流（blocked/pass/fail 三链零改动）。
                      val waitingIO: IO[List[BgTaskRegistry.ActiveTask]] =
                        if bgGateHoldEnabled then BgTaskRegistry.waitingFor(sessionId)
                        else IO.pure(Nil)
                      waitingIO.flatMap { waiting =>
                        if waiting.nonEmpty then
                          holdEmitted.get.flatMap { already =>
                            val emitOnce =
                              IO.whenA(!already)(
                                FlowMapEventLog.append(
                                  workspace, projectName, nodeId, "bg-wait",
                                  s"completion held: ${waiting.size} background task(s) pending: " +
                                    waiting.map(t => s"'${t.description}'").mkString(", ")) *>
                                  logger.info(
                                    s"Node '$nodeName' ($nodeId) turn completed with ${waiting.size} background " +
                                      "task(s) still running — holding completion until they finish") *>
                                  // bg-wait 标注写点（僵尸收敛批）：首个 hold 期置位
                                  // bgWait——NodePayload 条件字段随之带，前端可标
                                  // 「等待后台任务」（与真僵尸区分，作者首要缺口）。
                                  setNodeBgWait(nodeId, waiting)) *>
                                holdEmitted.set(true)
                            emitOnce *> armCap(waiting).as(bridge)
                          }
                        else
                          BgTaskRegistry.drainFailures(sessionId).flatMap { failures =>
                            if failures.isEmpty then
                              // 放行分支（腿 1 封存后 Completed 直达此处）。放行前的
                              // 腿 2 判定 = 未申报闸。
                              def releaseNow: IO[Behavior[AgentEvent]] =
                                holdEmitted.get.flatMap { held =>
                                  disarmCap *> holdEmitted.set(false) *>
                                    IO.whenA(held)(
                                      FlowMapEventLog.append(
                                        workspace, projectName, nodeId, "bg-released",
                                        "all background task(s) finished — completion released") *>
                                        setNodeBgWait(nodeId, Nil)) *>
                                    resultDeferred.complete(Right(messages)).attempt.void.as(Behaviors.stopped)
                                }
                              // 文本锚定 BLOCKED 豁免（noderpt 批 B 段 · 作者代裁 ⑧-4 = (a)）：
                              // 节点 `Completed` ∧ 申报槽空 ∧ **输出文本可被 `BlockedReader`
                              // 判为 blocked** ⇒ **不走 hold、不进提醒阶梯**，照既有行为终态化
                              // 为 blocked（`completeNode` 的文本锚定分流逐字不变）。
                              // 理由（代裁逐字）：文本说 BLOCKED 本身就是明确的求助申报——
                              // hold 住等于丢掉唯一求助通道（忘申报会被提醒救回，求助被 hold
                              // 则无人知晓，风险不对称）；豁免范围**严格限定**为「能被
                              // `BlockedReader` 判定为 blocked 的文本」，不是「任意非空文本」，
                              // 其余静默结束一律仍走 hold + 提醒阶梯。
                              // 文本来源 = **与终态点完完全全同一份**：`messages` 是同一个
                              // bridge 事件载荷，`extractLastAssistantText(messages)` 与终态
                              // 分支 `completeNode(nodeId, text = extractLastAssistantText(messages))`
                              // 同函数同输入 ⇒ 判定输入零漂移（不引入第二个文本来源）。
                              // 失败方向（代裁③）：判定抛错/不可判 ⇒ 保守**回落 hold**（= 今日
                              // leg 2 行为），不因豁免判定故障放宽闸门。
                              val anchoredBlocked: Boolean =
                                try BlockedReader.parse(extractLastAssistantText(messages)).isDefined
                                catch case _: Throwable => false
                              NodeReportRegistry.peek(sessionId).flatMap {
                                case Some(_) =>
                                  // 已申报 ⇒ 清表（申报 ⇒ 清表，唯一清表条件）后放行；
                                  // 消费语义仍唯一保留在终态点 drain（peek 不消费，
                                  // 否则申报信息在 1810 处已被吃掉、只能走文本降级面）。
                                  clearReportPending(nodeId, Some(sessionId)) *> releaseNow
                                case None if reportGateHoldEnabled && !anchoredBlocked =>
                                  // 未申报且**无 BLOCKED 文本锚定** ⇒ hold：**不终态化、不投递、
                                  // 不杀会话**（节点保持 Running 等人工处置），起表后桥继续存活
                                  // ——下一轮 Completed（提醒轮 / NodeMessage 重入 / 后台通知
                                  // 唤醒）在此复检。
                                  // 只置不重：提醒轮自身的 Completed 不改起点（代裁 4）。
                                  markReportPendingIfAbsent(nodeId, sessionId).as(bridge)
                                case None =>
                                  // 两条出口：① 腿 2 关（封存形态）——未申报照常放行 = 本批
                                  // 之前的文本锚定降级面行为（行为零变化）；② 腿 2 开但**文本
                                  // 锚定 BLOCKED 命中**（代裁 ⑧-4 豁免）——放行后由终态点的
                                  // 既有 `BlockedReader.parse` 分流成 blocked（求助上报链
                                  // blocked → 分发器 → 人 完整保留）。
                                  IO.whenA(reportGateHoldEnabled && anchoredBlocked)(
                                    logger.info(
                                      s"Node '$nodeName' ($nodeId) finished without a node_report but its final " +
                                        "text anchors BLOCKED — leg-2 hold waived (⑧-4 exemption); finalizing via the " +
                                        "existing blocked chain")) *>
                                    releaseNow
                              }
                            else
                              disarmCap *> holdEmitted.set(false) *>
                                setNodeBgWait(nodeId, Nil) *>
                                resultDeferred
                                  .complete(Left(FailOutcome(bgFailureMessage(failures, messages))))
                                  .attempt.void.as(Behaviors.stopped)
                          }
                      }
                    case AgentEvent.Failed(_, err) =>
                      setNodeBgWait(nodeId, Nil) *>
                        resultDeferred
                          .complete(Left(FailOutcome(Option(err.message).getOrElse("unknown error"))))
                          .void
                          .as(Behaviors.stopped)
                    case AgentEvent.Cancelled(_, reason) =>
                      setNodeBgWait(nodeId, Nil) *>
                        resultDeferred.complete(Left(FailOutcome(s"cancelled: $reason"))).void.as(Behaviors.stopped)

                override def onSignal(ctx: ActorContext[AgentEvent], signal: SystemSignal): IO[Behavior[AgentEvent]] =
                  signal match
                    case SystemSignal.Terminated(_) =>
                      logger.warn(
                        s"Node '$nodeName' ($nodeId) session '$sessionId' terminated WITHOUT a terminal event " +
                          "(crashed or stopped unexpectedly) — finalizing node as failed")
                      setNodeBgWait(nodeId, Nil) *>
                        resultDeferred
                          .complete(Left(FailOutcome(
                            s"node session '$sessionId' terminated without a terminal event (session crashed or was stopped unexpectedly)")))
                          .void
                          .handleErrorWith(_ => IO.unit) // 与正常终态事件竞争时败方静默
                        .as(Behaviors.stopped)
              bridge
            }
        },
        s"nodebridge-${sessionId.take(8)}"
      )
      // 卡死处置接线（与 ProjectActor 分发器注册同款）：supervisorRef=bridgeRef
      // ——bridge 的 Cancelled 分支（resultDeferred.complete(Left(cancelled))）
      // 成为节点的取消通道，AgentControl cancel / 面板 cancelAgent /
      // TaskStuckWatcher giveUp 共用。cancelled 走 cancelNode（status=cancelled
      // + TTL + 停 agent + running 清理）= NodeCancel 同语义，不会像 raw Stop
      // 那样把 engine fiber 永远挂在 resultDeferred 上。
      // startedAt/lastActivityMs=now：list 的 up/idle 列 + watcher 从出生可见。
      _ <- resources.agentRegistry.update(
        _ + (
          sessionId -> AgentRecord(
            sessionId,
            ref,
            AgentKind.Flow,
            rootSessionId,
            startedAt = System.currentTimeMillis(),
            lastActivityMs = System.currentTimeMillis(),
            supervisorRef = Some(bridgeRef),
            // 恢复路径项目徽标（activeAgents 快照 → activeAgentEntryJson）。
            project = Some(projectName),
            displayName = Some(nodeName)
          )
        )
      )
      _ <- (ref ! AgentCommand.UserInput(text = inputText, replyTo = Some(bridgeRef))).void
      // 等待完成——不设超时（硬约束）；唯一竞争事件 = NodeCancel 信号。
      outcome <- IO.race(resultDeferred.get, cancelSig.get)
      // P2（R-1=B）：挂起腿识别。桥送来的 Cancelled 若带 [[NodeEngine.SuspendReasonPrefix]]
      // 哨兵 ⇒ 本次中断是**挂起**（stuck 自动恢复的恢复腿），不是终态化：只停 actor，
      // 节点保持 Running，由 watcher 侧 CAS + resume 接手。
      suspended <- IO.pure(outcome match
        case Left(Left(fo)) => NodeEngine.isSuspendOutcome(fo.message)
        case _              => false)
      _ <- outcome match
        case Right(_) =>
          logger.info(s"Node '$nodeName' cancelled — stopping agent")
          (ref ! AgentCommand.Stop("Node cancelled")).void
        case Left(Left(fo)) =>
          // 孤儿后台任务收殓（D1 主钩子，原语义）——**noderpt 批 B 段改为「登记窗口」
          // 之前，此处只保留挂起腿的即时收殓**：
          //   挂起腿（`isSuspendOutcome`）= 中途换会话（stuck 自动恢复的恢复腿，非终态）
          //   ——保留进程无意义且与 resume 后的新会话重复 ⇒ **不登记窗口、即时收殓不变**
          //   （任务要求 ③.5 逐字），其 `bg-harvest` 带 cause 留痕（R-5）逐字保留。
          //   其它桥终态出口（failed/cancelled/zombie/bg-wait-cap）不再即时收割——按作者
          //   裁定「一律存活 30 分钟再销毁」移到终态写点之后的 `scheduleDestroy` 登记，
          //   到点由 `sweepDestroyWindows` 执行同一个 `reclaimSession`（收殓动作集合逐字
          //   相同，只改时点）。NodeCancel 路径（Right）由 `Stop→killSessionShellProcesses`
          //   覆盖（即时，见下方登记段的排除说明）。
          if suspended then
            (BgTaskRegistry.reclaimSession(Some(sessionId), wsSendFn, rootSessionId) *>
              FlowMapEventLog.append(workspace, projectName, nodeId, "bg-harvest",
                // R-5 / 类⑦（stuck 自动恢复批 P2 附加小项，本板 #98 同源）：**补 cause**。
                // 此前该行是「无原因的终态化留痕」——读者只能看到「被回收」而不知
                // 「因何终态」，正是 #98「重启后 bg 会话回收把在飞节点判 cancelled
                // （无 result/无通知）→ 下游 barrier 静默死锁」取证时的第一个盲区。
                // cause 词表 = 与本次终态化同一个判据（挂起 / 取消 / 归还能力失败），
                // 由桥消息文本单点派生（不改 AgentEvent 形态，与 CancelSource 同纪律）。
                s"node finalized (${NodeEngine.finalizeCause(fo.message)}) — reclaimed bg session '$sessionId'; " +
                  s"cause: ${fo.message.take(160)}"))
              .handleErrorWith(e =>
                logger.warn(s"Node '$nodeName' ($nodeId) bg-harvest reclaim failed: ${e.getMessage}"))
              .void.start *> IO.unit
          else IO.unit
        case Left(Right(_)) => IO.unit
      eventResult = outcome match
        case Left(r) => r
        case Right(_) => Left(FailOutcome("cancelled by NodeCancel"))
      _ <- resources.agentRegistry.update(_ - sessionId)
      _ <- system.stop(ref).handleErrorWith(_ => IO.unit)
      _ <- system.stop(bridgeRef).handleErrorWith(_ => IO.unit)
      _ <- running.update(_ - nodeId)
      // NodeMessage 注入链对称移除（与 running 表同点清理——此后 sendNodeMessage
      // 查无映射即走「注入未达」兜底，不再向死会话投递）。
      _ <- nodeSessions.update(_ - nodeId)
      // P2（R-1=B）：**挂起腿不终态化**。会话已被 Stop（上方 system.stop(ref)，
      // 与既有取消路径同款），registry/running/nodeSessions 三表已清（同上），
      // 但节点**不写 status/result/ttl、不通知、不摘除、不跑 barrier 检查**——
      // 它保持 `Running`，由 watcher 侧「有界等待 + hardResumeNode CAS + resume」
      // 接手。这正是把「清场」与「终态化」解耦的那一刀（设计 §3.6 选项 B）。
      _ <- if suspended then IO(suspendLog(sessionId, nodeId)) else eventResult match
        case Right(messages) =>
          val text = extractLastAssistantText(messages)
          // blocked 结构化信号批（20260909 spec §5.2 #5①②；同日作者裁定泛化
          // NodeReport 统一三语义）：会话完成时点 drain 登记表——工具申报（协议
          // 事实）优先于文本锚定（降级面，行为零变化）。时序上必在 bg 闸之后
          // （resultDeferred 完成即等待集已放行）；drain take-and-remove 单次消费，
          // cancelled/failed 路径不消费（残留由 guarantee 内 cleanupRunTables 的
          // remove 对称清理）。
          NodeReportRegistry.drain(sessionId).flatMap { declared =>
            completeNode(nodeId, text, declared)
          }
        case Left(fo) =>
          if fo.message.contains("cancelled") then
            // R2：原因文本从桥消息还原（此前只做 `contains("cancelled")` 布尔嗅探后
            // 丢弃）。R7：触发源按 [[CancelSource]] 两态分类（引擎看门狗 vs 人/Agent
            // 主动取消），由 reason 文本特征推导——**不改 AgentEvent 消息形态**。
            val reason = CancelSource.reasonFromBridgeMessage(fo.message)
            // R4 × R5 交互裁定（本批唯一延迟摘除点）：L3 硬恢复路径（bridge Cancelled
            // → 5s → resume）**延后**摘除——立即摘除会让 resume 成功后拓扑永久缺轨
            // （下游 in 已被 prune、永远拿不到该节点结果）；resume 成功则拓扑完整保留，
            // 失败则由 R5 方案 4 的 [[settleFailedHardResume]] 改判 failed（**不摘除**，
            // D5 零结算停等——摘除 failed 早在 R4 裁定里被永久拒绝，见 cancelNode 头注
            // 与 [[detachCancelledUpstream]]）。
            // 其余取消路径（L3 之外的 watcher giveUp / AgentControl / 面板 NodeCancel /
            // 父会话级联）一律立即摘除。
            val deferDetach = reason.contains("(L3 hard-recovery:")
            // R5 方案 4（2026-09-10 裁定）：同一条 L3 路径同样**推迟回流**——中间态
            // Cancelled 不是终局（5s 后 resume 定生死），故与 deferDetach 同参数
            // 联动（notify=false = 占位推迟，见 cancelNode 头注）。
            cancelNode(nodeId, reason, CancelSource.classify(reason), detach = !deferDetach, notify = !deferDetach)
          else failNode(nodeId, fo.message)
      // ③ 终态对称收割（noderpt 批 B 段 2026-09-11 作者裁定：一律存活 30 分钟再销毁）：
      // 桥终态**四出口**（failed / cancelled / zombie(猝死) / completed）统一到这一个
      // **登记点**——终态写点之后只登记窗口（`destroyAt` 字段 + 禁 spawn 表 +
      // `node-destroy-scheduled` 事件），不杀进程；到点由 `sweepDestroyWindows` 收殓。
      // blocked 出口在 `blockedNode` 内同款登记（口径一致）。
      // **两条腿不登记**（各附理由）：
      //   · 挂起腿（`suspended`）：中途换会话、非终态，进程已在上方即时收殓（③.5）；
      //   · NodeCancel 腿（`outcome = Right(_)` = cancelSig 抢先）：人为/看门狗主动取消，
      //     上方 `AgentCommand.Stop` → `killSessionShellProcesses` **即时收殓**（既有语义），
      //     「窗口内进程仍在」对该腿不成立——登记会留下与事实不符的痕迹，故不登记
      //     （其痕迹由既有 `cancelled` 事件承担）。
      _ <- outcome match
        case Right(_) => IO.unit
        case _ if suspended => IO.unit
        case _ =>
          scheduleDestroy(nodeId, List(sessionId), eventResult match
            case Right(_) => "completed"
            case Left(fo) => NodeEngine.finalizeCause(fo.message))
    yield ()).guarantee {
      // 任意退出路径（正常/崩溃/异常/cancel）三表对称移除——与既有清理段
      // （agentRegistry/running/nodeSessions :947-953）同点幂等（先到先清）。
      runSig.get.flatMap {
        case Some(cancelSig) => cleanupRunTables(nodeId, sessionId, cancelSig)
        case None => IO.unit // cancelSig 未创建 = 未登记任何表 → 无可清
      }
    }

  // ── LoopNode 执行（LoopNode 批 2026-09-06，主设计 §2.2 状态机 + §2.4 异常路径）──
  //
  // LoopNode 语义（作者 13 条红线，主设计 §2）：
  //  - worker/verify 双会话**贯穿节点存续期**（裁定 B 2026-09-03 00:59）：首轮 spawn、
  //    跨轮同会话注入续跑、终态（PASS 投递 /failed/cancelled/blocked）双会话一并销毁。
  //  - worker 会话生产 → verify 会话校验：PASS → completeNode（投递 worker 最终产出
  //    原文，§2.2 裁定建议）。FAIL → 打回 worker（输入恒定，同会话注入返工模板二）
  //    重跑；达 maxRounds(K) 仍未 PASS → 终态（failNode，§2.5 轮级兜底）。
  //  - 打回输入恒定：返工输入只含【LoopNode 返工 · 第 N 轮】+ 新意见 + 协议脚注，
  //    不重复注入产出全文/历史（持久会话模型收益，§2.2 模板二——「重跑≠改需求」）。
  //  - LoopGuard（turn 级，会话内既有防线）与 maxRounds（轮级）两轴独立：worker/verify
  //    会话内部 LoopGuard R-text 精确重复 → 会话 L1 终止 → 以执行层 failed 形态上浮 →
  //    整 Loop failed；轮级由 runLoopNode 的 maxRounds 兜底（本方法）。
  //  - blocked 分流：worker/verify 产出命中 BlockedReader 锚定 → Loop 级 blockedNode
  //    （§2.7：FAIL=产出质量问题内部迭代消化；BLOCKED=任务/拓扑问题传播停止+重入）。
  //
  // 关键：本方法只编排**已 spawn 的** worker/verify 双会话（spawnAndRunLoop 负责
  // 翻转+spawn+插件/MCP）；终态会话销毁在 spawnAndRunLoop 的 guarantee 内。

  /** Loop 会话（worker/verify 各一）：持久 agent 会话 + 常驻桥。跨轮同 ActorRef 复用
    * （裁定 B——上下文贯穿），每轮经 round Ref 等待本轮 Completed。桥常驻存活
    * （每轮 Completes 本轮 Deferred 后回 idle 等下一轮），终态由 spawnAndRunLoop 停毁。 */
  private final case class LoopSession(
    sessionId: String,
    agentRef: ActorRef[AgentCommand],
    bridgeRef: ActorRef[AgentEvent],
    round: Ref[IO, Deferred[IO, Either[String, List[Message]]]]
  )

  /** Loop 会话常驻桥行为（与 runWithAgent bridge 同构，但**每轮完成不停止**——只
    * complete 本轮 Deferred、保持存活等下一轮注入）。失败/取消/会话死亡 → complete
    * Left（本轮即终，spawnAndRunLoop guarantee 统一销毁）。不持会话内状态（状态全在
    * 共享 round Ref），故每轮简单递归构造新 Behavior 即可（无 lazy val 循环引用）。 */
  private def loopBridge(
    round: Ref[IO, Deferred[IO, Either[String, List[Message]]]],
    sessionId: String,
    sessionName: String
  ): Behavior[AgentEvent] =
    new Behavior[AgentEvent]:
      def receive(ctx: ActorContext[AgentEvent], event: AgentEvent): IO[Behavior[AgentEvent]] =
        event match
          case AgentEvent.Completed(_, messages) =>
            round.get.flatMap(_.complete(Right(messages)).attempt.void).as(loopBridge(round, sessionId, sessionName))
          case AgentEvent.Failed(_, err) =>
            round.get.flatMap(_.complete(Left(Option(err.message).getOrElse("unknown error"))).attempt.void).as(loopBridge(round, sessionId, sessionName))
          case AgentEvent.Cancelled(_, reason) =>
            round.get.flatMap(_.complete(Left(s"cancelled: $reason")).attempt.void).as(loopBridge(round, sessionId, sessionName))
      override def onSignal(ctx: ActorContext[AgentEvent], signal: SystemSignal): IO[Behavior[AgentEvent]] =
        signal match
          case SystemSignal.Terminated(_) =>
            logger.warn(s"Loop session '$sessionId' ($sessionName) terminated WITHOUT a terminal event — finalize round as failed")
            round.get.flatMap(_.complete(Left(s"loop session '${sessionId}' terminated without a terminal event")).attempt.void).as(loopBridge(round, sessionId, sessionName))

  /** spawn 单个 Loop 会话（agent + 常驻桥 + registry 注册）。MCP grant 由调用方
    * （spawnAndRunLoop）分别对 worker/verify acquire 后传入（各自独立引用记账）。
    * baseDef=已过 PresetResolver 的 AgentDef；prepared=已解析的插件分配。 */
  private def spawnLoopSession(
    baseDef: AgentDef,
    prepared: NodeEngine.PluginPreparation,
    grant: PluginMcpManager.Grant,
    sessionId: String,
    sessionName: String,
    projectRoot: String,
    initialMessages: List[Message] = Nil,
    /** TaskBoard 批 2（§1d）：loop 会话引擎侧节点身份（所属 NodeDef.id——worker/
      * verify 同属该 loop 节点，TaskBoard 权限矩阵与普通节点同面）。 */
    flowNodeId: Option[String] = None,
    /** D6 批 F1（G9 路径 a）：loop 节点人类可读名（worker/verify 同名——
      * 提问归因到节点而非会话分身），AskUser payload nodeName 字段来源。 */
    flowNodeName: Option[String] = None,
    /** 链级抽象 P2（§9.2 项 5）：loop 会话链身份（worker/verify 同属该 loop
      * 节点 → 同一 chainId 快照）。详见 SessionContext.flowChainId。 */
    flowChainId: Option[String] = None
  ): IO[LoopSession] =
    for
      initD <- Deferred[IO, Either[String, List[Message]]]
      round = Ref.unsafe[IO, Deferred[IO, Either[String, List[Message]]]](initD)
      agentDef = baseDef.copy(pluginMcpServers = grant.serverIds, pluginTools = prepared.builtinTools)
      ref <- NodeRunner.spawnAgentActor(
        system,
        NodeRunner.SpawnParams(
          agentDef = agentDef,
          resources = resources,
          sessionId = sessionId,
          sessionName = sessionName,
          depth = 1,
          parentRef = None,
          // 与节点同款：project 注入 agentStart 帧（LoopNode worker/verify 会话
          // 亦属 Project 域，面板行同标准标注项目名）。
          wsSend = NodeRunner.routeSubagentWsSend(wsSendFn, rootSessionId, sessionId, Some(projectName)),
          projectRoot = Some(projectRoot),
          safetyMode = "confirm-edits",
          rootSessionId = rootSessionId,
          isFlowNode = true,
          // TaskBoard 批 2（§1d）：loop 会话同置节点身份 + 项目上下文（普通节点
          // runWithAgent 同款——worker/verify 更新自己工单与普通节点同权限面）。
          flowNodeId = flowNodeId,
          projectName = Some(projectName),
          flowNodeName = flowNodeName,
          // 链级抽象 P2（§9.2 项 5）：loop worker/verify 会话链身份与 loop 节点同源
          // （同一 spawn 时刻快照）——工具面/文件名尾溯源归属口径与普通节点恒同。
          flowChainId = flowChainId,
          sandboxEnabled = true,
          sandboxRoot = Some(workspace),
          // 项目会话信号（沙箱拆围栏批 S1/R8 解耦）：loop worker/verify 属项目
          // 节点会话 ⇒ AGENTS.md 注入判据置位（与普通节点 runWithAgent 同款）。
          projectSession = true,
          initialMessages = initialMessages
        )
      )
      bridgeRef <- system.spawn(
        Behaviors.setup[AgentEvent] { bctx =>
          bctx.watch(ref) *> IO(loopBridge(round, sessionId, sessionName))
        },
        s"loopbridge-${sessionId.take(8)}"
      )
      _ <- resources.agentRegistry.update(
        _ + (sessionId -> AgentRecord(
          sessionId, ref, AgentKind.Flow, rootSessionId,
          startedAt = System.currentTimeMillis(),
          lastActivityMs = System.currentTimeMillis(),
          supervisorRef = Some(bridgeRef),
          project = Some(projectName), // 恢复路径项目徽标（与节点/分发器同标准）
          displayName = Some(sessionName)
        ))
      )
    yield LoopSession(sessionId, ref, bridgeRef, round)

  /** 翻转 + 在飞登记（LoopNode 批复用；与 runWithAgent 内联翻转同语义——CAS 守卫
    * Done/LostRace/Aborted 三态、running/nodeSessions 条件登记、start-aborted 事件。
    * 独立实现避免改动既有 runWithAgent（并发分支保护，同文件不同 hunk 收敛）。
    * crash-recovery 批 D1/裁定③：翻转事务落 sessionRef（worker 主会话）+
    * sessionRefVerify（verify 会话，仅 loop 有值）——loop 崩溃残留据此双会话续接。 */
  private def flipToRunning(
    node: NodeDef,
    cancelSig: Deferred[IO, Unit],
    sessionId: String,
    nodeId: String,
    nodeName: String,
    verifySessionId: Option[String] = None
  ): IO[Unit] =
    for
      _ <- running.modify { m => if m.contains(nodeId) then (m, ()) else (m + (nodeId -> cancelSig), ()) }
      _ <- nodeSessions.update(_ + (nodeId -> sessionId))
      now <- IO(System.currentTimeMillis())
      (flipped, flipOutcome) <- store.mutateWithResult { s =>
        s.nodes.get(nodeId) match
          case Some(fresh)
              if (fresh.status == NodeLifecycle.Pending || fresh.status == NodeLifecycle.Wiring)
                && !fresh.in.exists(up => !fresh.deliveredTo.contains(up)) =>
            (s.copy(nodes = s.nodes.updated(nodeId, fresh.copy(
              status = NodeLifecycle.Running,
              startedAt = Some(now),
              sessionRef = Some(sessionId),
              sessionRefVerify = verifySessionId,
              // 销毁窗口撤销（noderpt 批 B 段，与 runWithAgent 翻转同点同语义）：
              // Loop 节点重激活/重跑 ⇒ 旧窗口作废（绝不按旧计划销毁在跑的进程）。
              destroyAt = None))), NodeEngine.FlipOutcome.Done)
          case Some(fresh) if fresh.status == NodeLifecycle.Running =>
            (s, NodeEngine.FlipOutcome.LostRace)
          case Some(fresh) if fresh.in.exists(up => !fresh.deliveredTo.contains(up)) =>
            (s, NodeEngine.FlipOutcome.Aborted("in-barrier not settled (concurrent rewiring)"))
          case Some(fresh) =>
            (s, NodeEngine.FlipOutcome.Aborted(s"state changed to '${fresh.status}' before flip (concurrent finalize)"))
          case None =>
            (s, NodeEngine.FlipOutcome.Aborted("node vanished"))
      }
      _ <- flipOutcome match
        case NodeEngine.FlipOutcome.Done =>
          running.update(m => m + (nodeId -> cancelSig)) *>
            // 销毁窗口撤销第二步（noderpt 批 B 段）：上一轮双会话解出禁 spawn 表。
            (node.sessionRef.toList ++ node.sessionRefVerify.toList).traverse_(BgTaskRegistry.reopenSession) *>
            flipped.nodes.get(nodeId).traverse_(n =>
              emitWithChain("nodeUpdated", nodeId, NodePayload.buildNodeJson(n, System.currentTimeMillis())))
        case NodeEngine.FlipOutcome.LostRace =>
          running.modify { case m if m.get(nodeId).exists(_.eq(cancelSig)) => (m - nodeId, ()); case m => (m, ()) } *>
            nodeSessions.update(_ - nodeId) *> IO.raiseError(NodeEngine.StartRaceLost(nodeId))
        case NodeEngine.FlipOutcome.Aborted(reason) =>
          running.modify { case m if m.get(nodeId).exists(_.eq(cancelSig)) => (m - nodeId, ()); case m => (m, ()) } *>
            nodeSessions.update(_ - nodeId) *>
            FlowMapEventLog.append(workspace, projectName, nodeId, "start-aborted",
              s"start aborted: $reason") *>
            IO.raiseError(new RuntimeException(s"Node '$nodeName' ($nodeId) start aborted — $reason"))
    yield ()

  /** 终态双会话销毁（裁定 B）：停 agent + 停桥 + 注销 registry。MCP release 与
    * running/nodeSessions 清理在 spawnAndRunLoop 的 guarantee 内一并做（此处只管会话）。
    *
    * noderpt 批 B 段（2026-09-11 作者裁定 ④）**同批修**两处既有缺口：
    *   ① **申报槽清理**：`NodeReportRegistry.remove(worker) + remove(verify)` —— 此前双会话
    *      终态不 remove ⇒ Loop 的 `node_report` 申报残留（设计稿 §2 缺口清单第 6 条）。
    *   ② **双会话的进程/任务收殓**：此前本函数只停 actor，**从不**调用
    *      `BgTaskRegistry.reclaimSession` ⇒ worker/verify 名下的后台进程、在册任务与输出
    *      留存区永不收殓（③ 补的对称化只覆盖普通节点）。
    *      **时点按作者裁定「Loop 终态同样走 30 分钟窗口口径（与③一致）」**——收殓动作集合
    *      与③逐字相同（同一个 `reclaimSession`），但由窗口到期执行：
    *      · 登记 = 本函数的 `scheduleDestroy(nodeId, List(worker, verify), …)`（窗口内禁
    *        这两个 sessionId 的新 spawn，与普通节点同表同判据）；
    *      · 到点 = `sweepDestroyWindows` → `destroyNodeSessions` → `destroyTargetSessions`
    *        取 `sessionRef`（= worker）**与** `sessionRefVerify`（= verify）**两个**会话逐个
    *        `reclaimSession`（杀进程树 + 注销 registry + 逐条 `finalizeTask` + 释放
    *        `ShellSession.sessions` 条目 + WS `backgroundTaskUpdate(status="cancelled")` 帧）。
    *      ⇒ 本函数**不**即时 reclaim：就地收殓会让窗口口径对 Loop 失效（与本段硬口径冲突），
    *      故把「双会话收殓」落在窗口执行体上，并由 `LoopNodeSpec` ⑦（双会话 + 申报槽）与
    *      `NodeBgReclaimSpec` R1/R7（窗口/到点/幂等）钉死。
    *      停 actor / 停桥 / 注销 registry 保持不变（会话实体拆除不随窗口变——窗口保的是
    *      进程与任务（可取证面），不是已死的 agent actor）。 */
  private def destroyLoopSessions(nodeId: String, worker: LoopSession, verify: LoopSession): IO[Unit] =
    scheduleDestroy(nodeId, List(worker.sessionId, verify.sessionId), "loop-terminal") *>
      NodeReportRegistry.remove(worker.sessionId) *>
      NodeReportRegistry.remove(verify.sessionId) *>
      resources.agentRegistry.update(_ - worker.sessionId - verify.sessionId) *>
      system.stop(worker.agentRef).handleErrorWith(_ => IO.unit) *>
      system.stop(worker.bridgeRef).handleErrorWith(_ => IO.unit) *>
      system.stop(verify.agentRef).handleErrorWith(_ => IO.unit) *>
      system.stop(verify.bridgeRef).handleErrorWith(_ => IO.unit).void

  /** runLoopNode 主编排（§2.2 状态机）：worker/verify 双会话迭代。spawnAndRunLoop
    * 已翻转+spawn+装好两会话后调用；终态（PASS/failed/cancelled/blocked/达 K）落
    * 终态化后即返回，会话清理由调用方 guarantee 兜底。
    * crash-recovery 批裁定③：resume=Some 时从崩溃时持久断点（loopRound/loopPhase）
    * 续跑——phase=worker → 本轮 worker 注入换 resume prompt（transcript 已水合，
    * 从最后持久轮边界续作），完成后照常进 verify；phase=verify → 以 worker transcript
    * 末条 assistant 文本重建本轮 verify 输入（标注崩溃续接）注入续验。loopRound 语义
    * 保持连续（续跑轮号 = 崩溃时轮号，PASS 记同一轮，FAIL 打回 +1）。 */
  private def runLoopNode(
    node: NodeDef,
    worker: LoopSession,
    verify: LoopSession,
    workerFirstInput: String,
    cancelSig: Deferred[IO, Unit],
    resume: Option[NodeEngine.ResumeContext] = None
  ): IO[Unit] =
    val nodeId = node.id
    val loopCfg = node.loop.get
    val maxRounds = loopCfg.maxRounds

    /** 单会话单轮注入：置新 Deferred → 发 UserInput → race(本轮产出, node cancelSig)。
      * cancelSig 赢 → Left(cancelled)（整 Loop cancelled），否则返回本轮产物。 */
    def step(ses: LoopSession, input: String): IO[Either[String, List[Message]]] =
      for
        d <- Deferred[IO, Either[String, List[Message]]]
        _ <- ses.round.set(d)
        _ <- (ses.agentRef ! AgentCommand.UserInput(text = input, replyTo = Some(ses.bridgeRef))).void
        r <- IO.race(d.get, cancelSig.get).map {
          case Left(res) => res
          case Right(_)  => Left("cancelled by NodeCancel")
        }
      yield r

    /** 轮次/阶段状态落库 + WS nodeUpdated（NodePayload 同构载荷，NodeList/前端可见）。 */
    def goto(nodeId: String, phase: String, round: Int): IO[Unit] =
      store.mutate { st =>
        st.nodes.get(nodeId) match
          case Some(f) if f.loop.isDefined =>
            st.copy(nodes = st.nodes.updated(nodeId, f.copy(loopPhase = Some(phase), loopRound = round)))
          case _ => st
      }.flatMap(s => s.nodes.get(nodeId).traverse_(emitUpdated))

    /** 最近一次 FAIL verdict 摘要落库 + WS（loopLastVerdict，前端打回原因可见）。 */
    def setVerdict(nodeId: String, summary: String): IO[Unit] =
      store.mutate { st =>
        st.nodes.get(nodeId) match
          case Some(f) if f.loop.isDefined =>
            st.copy(nodes = st.nodes.updated(nodeId, f.copy(loopLastVerdict = Some(summary))))
          case _ => st
      }.flatMap(s => s.nodes.get(nodeId).traverse_(emitUpdated))

    /** 执行层失败/取消分流（§2.4）：cancelled → cancelNode（整 Loop cancelled）；
      * 其余（LLM 错误/agent 消失/LoopGuard L1 终止该 turn）→ 整 Loop failed（failNode）。
      * R2/R7（取消静默死锁修复批）：Loop 会话的取消同样带原因 + 触发源——err 文本已
      * 含 `cancelled` 特征，原样作为 reason 落盘（[[CancelSource]] 两态分类同桥口径）。 */
    def failOrCancel(nodeId: String, err: String): IO[Unit] =
      if err.contains("cancelled") then
        cancelNode(nodeId, err, CancelSource.classify(err))
      else failNode(nodeId, err)

    /** verify 首轮输入构建（模板三全文：原始任务 + 上游段 + 待验证产出 + 验证清单 +
      * 验证协议脚注）；轮 N≥2 用模板三短段（持久上下文已持有），见 loopVerifyInput。 */
    def verifyRound1Input(workerText: String): IO[String] =
      node.in.traverse { upId =>
        store.findNode(upId).map {
          case Some(up) => up.result.map(res => s"=== Node ${up.name} ===\n$res")
          case None     => None
        }
      }.map { upstream =>
        NodeEngine.loopVerifyInput(1, node.task.getOrElse(""), upstream.flatten.mkString("\n"), workerText, loopCfg.verifyTask)
      }

    /** verify 产出裁决（PASS 投递 worker 产出 / FAIL 打回 +1 / BLOCKED → Loop 级
      * blocked）——loopRound 正常轮与 verify 相位崩溃续跑（resumeVerifyRound）共用。
      * blocked 结构化信号批（20260909 spec §5.2 #8；同日作者裁定泛化 NodeReport
      * 统一三语义）：verify 会话完成时点先 drain 登记表——工具申报（协议事实）
      * 按类别分流到既有链：pass 与 VERDICT: PASS 同链（投递 worker 产出）、fail
      * 与 VERDICT: FAIL 同链（detail=打回意见 / suggestion=通过标准，打回 worker）、
      * blocked → Loop 级 blocked；未申报走文本锚定降级面（行为零变化）。 */
    def handleVerify(roundNum: Int, wText: String, vMsgs: List[Message]): IO[Unit] =
      val vText = extractLastAssistantText(vMsgs)
      val R = nebflow.core.tools.NodeReportToolDef
      NodeReportRegistry.drain(verify.sessionId).flatMap {
        case Some(fb) if R.isPass(fb.category) =>
          completeNode(nodeId, wText) // verify 工具申报 pass → 投递 worker 产出（VERDICT: PASS 同链）
        case Some(fb) if R.isFail(fb.category) =>
          // verify 工具申报 fail → 打回 worker（VERDICT: FAIL 同链）：
          // detail = 打回意见（空则占位），suggestion = 通过标准。
          val issues = List(if fb.detail.trim.isEmpty then VerdictReader.PlaceholderIssues else fb.detail.trim)
          val f = VerdictReader.Verdict.Fail(issues, fb.suggestion)
          setVerdict(nodeId, VerdictReader.renderFailSummary(f)) *>
            loopRound(roundNum + 1, Some(f))
        case Some(fb) => blockedNode(nodeId, fb, finalText = Some(vText)) // verify 工具申报 blocked → Loop 级 blocked
        case None =>
          BlockedReader.parse(vText) match
            case Some(fb) => blockedNode(nodeId, fb) // verify 申告任务无法验证 → Loop 级 blocked
            case None =>
              VerdictReader.parse(vText) match
                case VerdictReader.Verdict.Pass =>
                  // PASS：投递 worker 最终产出原文（§2.2 裁定建议 a）
                  completeNode(nodeId, wText)
                case f: VerdictReader.Verdict.Fail =>
                  // FAIL：打回 worker（同会话注入意见），轮 +1，至 K
                  setVerdict(nodeId, VerdictReader.renderFailSummary(f)) *>
                    loopRound(roundNum + 1, Some(f))
      }

    /** roundNum 轮的 verify 输入构建（首轮模板三全文，N≥2 短段）。 */
    def verifyInputFor(roundNum: Int, wText: String): IO[String] =
      if roundNum <= 1 then verifyRound1Input(wText)
      else IO.pure(NodeEngine.loopVerifyInput(roundNum, "", "", wText, ""))

    def loopRound(roundNum: Int, lastFail: Option[VerdictReader.Verdict.Fail], overrideWorkerInput: Option[String] = None): IO[Unit] =
      if roundNum > maxRounds then
        // 达 K 轮仍未 PASS → 轮级兜底终态（§2.5 划界：maxRounds 是轮帽，非内容检测）
        val suffix = lastFail.map(f => s" — last verdict: ${VerdictReader.renderFailSummary(f)}").getOrElse("")
        failNode(nodeId, s"loop reached maxRounds=${maxRounds} without passing verification$suffix")
      else
        // overrideWorkerInput（crash-recovery 批）：worker 相位崩溃续跑时本轮 worker
        // 输入 = resume prompt（上下文已在水合 transcript 内）；递归轮恒 None。
        val workerInput = overrideWorkerInput.getOrElse {
          if roundNum == 1 then workerFirstInput
          else
            val f = lastFail.getOrElse(VerdictReader.Verdict.Fail(Nil, ""))
            NodeEngine.loopReworkInput(roundNum, f.issues, f.requirements)
        }
        for
          _ <- goto(nodeId, NodeEngine.LoopPhaseWorker, roundNum)
          wOut <- step(worker, workerInput)
          _ <- wOut match
            case Left(err) => failOrCancel(nodeId, s"worker round $roundNum: $err")
            case Right(wMsgs) =>
              val wText = extractLastAssistantText(wMsgs)
              // blocked 结构化信号批（20260909 spec §5.2 #8；同日作者裁定泛化
              // NodeReport 统一三语义）：worker 会话完成时点先 drain 登记表
              // （工具申报按类别分流：blocked → Loop 级 blocked；fail → 既有
              // failed 链；pass 与无申报同链照常进 verify 裁决。spawnLoopSession
              // 会话已带 flowNodeId——工具天然可达）；未申报走文本锚定降级面
              // （行为零变化）。
              val R = nebflow.core.tools.NodeReportToolDef
              NodeReportRegistry.drain(worker.sessionId).flatMap {
                case Some(fb) if R.isFail(fb.category) =>
                  failNode(nodeId, R.renderFail(fb)) // worker 工具申报 fail → 既有 failed 链
                case Some(fb) if R.isBlockedSemantics(fb.category) =>
                  blockedNode(nodeId, fb, finalText = Some(wText)) // worker 工具申报 blocked → Loop 级 blocked
                case _ =>
                  // 无申报（None）或 pass 申报：本轮产出照常进 verify 裁决
                  // （pass = worker 正式声明本轮完成，与无申报同链零新链）。
                  for
                    vIn <- verifyInputFor(roundNum, wText)
                    _ <- goto(nodeId, NodeEngine.LoopPhaseVerify, roundNum)
                    vOut <- step(verify, vIn)
                    _ <- vOut match
                      case Left(err) => failOrCancel(nodeId, s"verify round $roundNum: $err")
                      case Right(vMsgs) => handleVerify(roundNum, wText, vMsgs)
                  yield ()
              }
        yield ()

    /** verify 相位崩溃续跑（裁定③）：以 worker transcript 末条 assistant 文本重建本轮
      * verify 输入（附崩溃续接标注——旧输入可能未持久/已消费，重注入是操作侧消息），
      * 注入水合后的 verify 会话续验；verdict 走 handleVerify 共用裁决（loopRound 语义
      * 连续：PASS 记崩溃轮，FAIL 打回 +1）。 */
    def resumeVerifyRound(r: NodeEngine.ResumeContext): IO[Unit] =
      val roundNum = math.max(r.loopResumeRound, 1)
      val wText = extractLastAssistantText(r.recoveredMessages)
      for
        vBase <- verifyInputFor(roundNum, wText)
        _ <- goto(nodeId, NodeEngine.LoopPhaseVerify, roundNum)
        vOut <- step(verify, NodeEngine.loopVerifyResumeAnnotation(roundNum) + "\n\n" + vBase)
        _ <- vOut match
          case Left(err) => failOrCancel(nodeId, s"verify round $roundNum (resumed): $err")
          case Right(vMsgs) => handleVerify(roundNum, wText, vMsgs)
      yield ()

    resume match
      case None => loopRound(1, None)
      case Some(r) =>
        val roundNum = math.max(r.loopResumeRound, 1)
        r.loopResumePhase match
          case Some(NodeEngine.LoopPhaseVerify) => resumeVerifyRound(r)
          case _ =>
            // worker 相位（或轮前崩溃 loopRound=0/phase=None）：本轮 worker 输入 =
            // resume prompt，完成后照常进 verify 轮。
            loopRound(roundNum, None, Some(r.resumePrompt))


  // ── 终态化（§2.7）─────────────────────────────────────────
  //
  // 竞态根因修复（barrier 投递 bug 主因）：终态字段（status/result/completedAt/
  // ttlExpireAt）一律落在 mutate 事务内现读的 fresh 节点上，绝不在启动时捕获的
  // 陈旧快照上 copy 写回。运行期间 NodeTools.setOut 的接线改写（out/in 反向一致）
  // 与 deliverOut 的 deliveredTo 增量都在 fresh 上原样保留；投递沿 fresh.out。
  // 实证：n-95271231 两上游运行中被改接线 → 完成时陈旧副本回写 out=null →
  // deliverOut 沿空 out 悬空 → 下游永久 wiring；n-ab4a884f 同理回写覆盖成 Nebula。
  // 节点已不在活动区（被移除）→ 拒写拒投（陈旧写回会把它复活成垃圾行）。

  /** blocked 结构化信号批（20260909 spec §5.3；同日作者裁定泛化 NodeReport
    * 统一三语义）：declared = 节点会话经 node_report 工具申报的结构化反馈
    * （NodeEngine 终态分流处 drain 登记表所得）；默认 None 向后兼容全部既有
    * 调用点。分流顺序【结构化信号优先，按申报类别三分流，全部锚定既有链零新链】：
    * pass → 既有 completed 语义链（回落 declared=None 原路径：BlockedReader
    * 降级面 → CompletionGate 闸门 → completedNode——闸门是完成链的产物完整性
    * owner，pass 申报不绕闸、零放宽）；fail → 既有 failed 语义链（failNode：
    * deliverFailed / merge 兜底 / retry 挂点原样接管）；blocked（细分六类/泛值）
    * → 既有 blockedNode/FeedbackRouter 链。 */
  private def completeNode(nodeId: String, resultText: String,
      declared: Option[BlockedFeedback] = None): IO[Unit] =
    declared match
      // pass 申报：节点正式声明完成 → 与无申报同链走既有完成路径（降级面+闸门原样）
      case Some(fb) if nebflow.core.tools.NodeReportToolDef.isPass(fb.category) =>
        completeNode(nodeId, resultText)
      // fail 申报：节点正式声明失败 → 既有 failed 链（result=渲染串，观测面单点格式）
      case Some(fb) if nebflow.core.tools.NodeReportToolDef.isFail(fb.category) =>
        failNode(nodeId, nebflow.core.tools.NodeReportToolDef.renderFail(fb))
      // blocked 申报（细分六类/泛值，协议事实优先）：工具申报即节点对任务可完成性
      // 的正式判断，blocked 可重激活无损，completed 伪终态不可逆（spec §6 语义裁定）。
      case Some(fb) => blockedNode(nodeId, fb, finalText = Some(resultText))
      case None =>
        // blocked 分流（设计 §1.3/§2.1）：最终输出以 BLOCKED 锚定 → blockedNode；
        // 非 BLOCKED 开头 → completeNode 原路径（产物完整性闸门 + completedNode）。
        BlockedReader.parse(resultText) match
          case Some(feedback) => blockedNode(nodeId, feedback)
          case None =>
            // 产物完整性闸门（audit 20260905 机制建议）：completed 出口三合法态
            // 校验（a 已提交+b commit-ready 申报+c 零改动；脏且未申报 → Reject）。
            // Reject → blockedNode 转 blocked（复用 BLOCKED 反馈协议：不走 out 投递、
            // 结果不丢弃，重入协议处置）——堵「节点自报 completed 但产物滞留」静默丢失。
            // fail-open 见 CompletionGate.check。
            store.getNode(nodeId).flatMap {
              case Some(fresh) if fresh.status == NodeLifecycle.Running =>
                CompletionGate.check(workspace, fresh.worktree, resultText, gateRunner).flatMap {
                  case CompletionGate.Pass(reason) =>
                    logger.debug(s"Node '${fresh.name}' completion gate pass: $reason")
                    completedNode(nodeId, resultText)
                  case CompletionGate.Reject(reason, diag) =>
                    logger.warn(s"Node '${fresh.name}' completion gate reject: $reason")
                    // U6/F 修复（2026-09-11）：闸门 Reject 转 blocked 时**必须带上原结论文本**
                    // ——旧口径只传 feedback ⇒ `blockedNode` 把 result 写成「闸门反馈」单段，
                    // 节点辛苦跑出来的结论文本（今日实测 24 分钟复核结论）被**整段替换且
                    // 不可恢复**（磁盘上再无副本）。修法 = 复用 `blockedNode` 既有的
                    // `finalText` 并列落盘能力（与 `node_report` 工具申报 BLOCKED 分支
                    // @:1970 同一机制、同一格式：`render(feedback) + "\n\n" + finalText`）：
                    // 闸门反馈在**前**（保住 `BlockedReader` 的裸 BLOCKED 锚定与重入 prompt
                    // 语义，零回归），原结论文本以空行分隔并列在**后**（一条 result 字段里
                    // 两段可各自取用，无 schema 变更、无前端改动、无新字段）。
                    // 取回原文的具体命令（U6/F 判据，<ws> = 项目工作区，<id> = 节点 id）：
                    //   python3 -c "import json;r=json.load(open('<ws>/.nebflow/flow-map.json'))\
                    //     ['nodes']['<id>']['result'];print(r.split('[original-conclusion]',1)[1])"
                    // → 打印闸门 Reject 前该节点会话产出的结论文本全文（未被闸门文本污染）。
                    blockedNode(nodeId, CompletionGate.feedback(diag),
                      finalText = Some(CompletionGate.withOriginalText(resultText)))
                }
              case _ => completedNode(nodeId, resultText)
            }

  /** completed 原路径：落库 completed + TTL → emitEvent nodeCompleted → deliverOut → settleDeps。 */
  private def completedNode(nodeId: String, resultText: String): IO[Unit] =
    for
      now <- IO(System.currentTimeMillis())
      s <- store.mutate { st =>
        st.nodes.get(nodeId) match
          case Some(fresh) =>
            st.copy(nodes = st.nodes.updated(nodeId, withoutReportPending(fresh.copy(
              status = NodeLifecycle.Completed,
              result = Some(resultText),
              completedAt = Some(now),
              ttlExpireAt = Some(now + NodeEngine.TtlDisplayMs)))))
          case None => st
      }
      _ <- s.nodes.get(nodeId) match
        case Some(completed) =>
          emitWithChain("nodeCompleted", nodeId, NodePayload.buildNodeJson(completed, now)) *>
            logger.info(s"Node '${completed.name}' completed (result ${resultText.length} chars)") *>
            deliverOut(completed, resultText) *>
            // deps 反向结算（deps 设计 §1.3）：与 deliverOut 同一完成 fiber 顺序推进
            //（现状 deliverOut 同款语义）。blocked 分流在 completeNode 入口已与
            // completed 分叉，deps 结算只挂 completed 分支尾部——blocked ∉ completed
            // 不触发；failed（failNode）/cancelled（cancelNode）不挂 settleDeps，
            // 下游保持 pending 可见（裁定③差异语义，NodeDepsSpec T5 锁定）。
            settleDeps(completed) *>
            // dispatch-notify（2026-09-05 批）：终态落库+投递+结算完成后，回流通知
            // 分发器（仅 notifyDispatcher 显式开启的节点；内部 best-effort 不上抛）。
            dispatchNotify.notifyTerminal(completed, NotifyReason.Completion)
        case None =>
          logger.warn(s"Node '$nodeId' vanished before completion — result not persisted")
    yield ()

  /** deps 完成信号 → 触发依赖者（deps 设计 §1.3）：反向扫描活动区，对每个
    * deps 含本次完成节点、且自身非 running/终态的依赖者调 startNode——闸门在
    * startNode 内（deps 未全满足 / in 未归零都会静默返回）。满足判定是声明式
    * 状态查询（幂等、零记账）：同一上游既 in 又 deps 时，in 路径（deliverOut
    * barrier 归零）与 deps 路径（settleDeps）汇合同一个 startNode 入口，第二次
    * 调用被幂等跳过，冗余无害不重复 spawn。归档上游可触发——TTL 归档不影响
    * 「完成」事实（本函数由 completeNode 完成 fiber 调用时上游必在活动区；
    * 归档变体由 NodeEdit D1-deps 补触发路径覆盖，findNode 兜底）。 */
  private def settleDeps(completed: NodeDef): IO[Unit] =
    store.snapshot.flatMap { s =>
      s.nodes.values
        .filter(d => d.deps.contains(completed.id)
          && d.status != NodeLifecycle.Running
          && !NodeLifecycle.Terminal.contains(d.status))
        .toList
        // fork 化（§6.1）：traverse_ 遍历体的 startNode 各自 fork——同上游 N 依赖
        // 者同时获得会话（案例 A 串行链根除），遍历 fiber 不被任何一个下游会话质押。
        .traverse_(d => forkStart(s"settle-deps -> ${d.name}(${d.id})")(startNode(d.id)))
    }

  /** 依赖者触发饥饿记账（§6.3）：nodeId → 连续「资格满足却未获会话」的回扫轮数。
    * 节点启动/终态/消失即移除（下一轮重新起算）。 */
  private val starveRounds: Ref[IO, Map[String, Int]] =
    Ref.unsafe[IO, Map[String, Int]](Map.empty)

  /** mount-stalled 单发记账（mount-enforce 批）：当前停滞期已发过事件的节点 id 集。
    * 每轮 sweep 全量替换为当轮停滞集——节点恢复（被补触发/合法等待）即自动出集，
    * 再次停滞 = 新停滞期再发一次。 */
  private val stallNotified: Ref[IO, Set[String]] =
    Ref.unsafe[IO, Set[String]](Set.empty)

  /** R3 即时 barrier 告警单发记账（取消静默死锁修复批）：已由**终态写点同步**
    * （[[checkBarriersNow]]）发过 `barrier-blocked` 的下游 id 集。与 [[stallNotified]]
    * 分工：本集管「即时告警已发」，周期回扫发射集要排除本集成员（同一停滞不得发
    * 两条）；每轮 sweep 按「此刻是否仍被终态上游闸住」剪枝——恢复（承接/改接/启动）
    * 即出集，未来再次停滞可再告警一次。 */
  private val barrierAlerted: Ref[IO, Set[String]] =
    Ref.unsafe[IO, Set[String]](Set.empty)

  /** 资格回扫（根因报告 §6.2，TtlTick 30s 驱动，ProjectActor 挂点）：对活动区
    * pending/wiring 节点做声明式启动资格重估，一次性关死「有资格但没人叫」的悬
    * 挂族（孤儿 barrier、D1 缺口、投递丢失、触发消费错位——案例 B 收口C 96min
    * 滞留的根因通道）。两步：
    *   1. 孤儿 barrier 自愈：in 中「completed+有 result+deliveredTo 未记」的上游
    *      逐个 deliverOutTo（自带 deliveredTo 去重幂等；语义 = NodeTools fix-b
    *      补投从「仅 edit 时」提升为周期性；barrier 随之归零者由其内部启动）；
    *   2. barrier/deps 均满足者 fork startNode（幂等；fork 化后不阻塞 tick）。
    * 资格口径与 startNode 闸门同源（非终态 + deps 全 completed + in 全归零 + 非
    * 零接线防御）——合格即应启动；同一节点连续 ≥StarvedRounds 轮合格却仍
    * pending/wiring（= fork 启动未生效，健康系统不应发生）→ trigger-starved 事
    * 件单发（附 nodeId+资格明细，防每 tick 刷屏）。
    * 留痕纪律：本轮实际补投/启动了哪些节点——INFO 一行 + FlowMapEventLog 每动作
    * 节点一条 settle-sweep 事件，禁止静默自愈。 */
  def settleRunnableSweep(): IO[Unit] =
    for
      // crash-recovery 批：已认领待 rehydrate 的节点排除（bootRecoveryQueue 见字段注
      // 释）——资格回扫会以无 resume 的新鲜路径 fork startNode，与慢段续跑竞速会
      // 顶掉 transcript 续接语义（CAS 败者安静但恢复降级为全新重跑）。
      recovering <- bootRecoveryQueue.get
      s0 <- store.snapshot
      candidates = s0.nodes.values.filter(n =>
        (n.status == NodeLifecycle.Pending || n.status == NodeLifecycle.Wiring) && !recovering.contains(n.id)).toList
      // 第 1 步：孤儿 barrier 自愈（deliverOutTo 自带 deliveredTo 去重，重复扫描幂等）
      healed <- candidates.traverse { n =>
        n.in.traverse { upId =>
          if n.deliveredTo.contains(upId) then IO.pure(None)
          else
            store.findNode(upId).flatMap {
              case Some(up) if up.status == NodeLifecycle.Completed && up.result.exists(_.trim.nonEmpty) =>
                deliverOutTo(up, n.id, up.result.get)
                  .as(Some(n.id -> s"orphan barrier healed: redelivered completed upstream '${up.name}' (${up.id})"))
              case _ => IO.pure(None)
            }
        }.map(_.flatten)
      }.map(_.flatten)
      _ <- healed.traverse_((id, desc) => FlowMapEventLog.append(workspace, projectName, id, "settle-sweep", desc))
      // 第 2 步：重读后资格判定（补投可能已归零部分 barrier）+ fork 启动
      s1 <- store.snapshot
      actives = s1.nodes.values.filter(n =>
        n.status == NodeLifecycle.Pending || n.status == NodeLifecycle.Wiring).toList
      qualified <- actives.filterA { n =>
        val emptyWiring = n.status == NodeLifecycle.Wiring && n.task.isEmpty && n.in.isEmpty && n.deps.isEmpty
        // R4：带「待承接」标记的 barrier 不放行（摘除后 in 已 prune，若不设此闸，
        // barrier 会以缺轨输入正常启动并静默产出缺轨结论）。分发器承接后（NodeEdit
        // 实际变更）标记清空，此处自然放行。
        val barrierOk = !n.in.exists(up => !n.deliveredTo.contains(up)) && n.pendingSuccession.isEmpty
        if emptyWiring || !barrierOk then IO.pure(false)
        else depsSatisfied(n)
      }
      _ <- qualified.traverse_(n => forkStart(s"settle-sweep -> ${n.name}(${n.id})")(startNode(n.id)))
      _ <- qualified.traverse_(n => FlowMapEventLog.append(workspace, projectName, n.id, "settle-sweep",
        s"qualified (deps+barrier settled, status=${n.status}) — startNode forked by settle sweep"))
      // 饥饿记账：合格 → 计数 +1；已启动/终态/消失（不在本轮合格集）→ 移除（重新
      // 起算）；计数恰达阈值 → trigger-starved 单发（继续增长不再重复发）。
      starved <- starveRounds.modify { m =>
        val next = qualified.map(n => n.id -> (m.getOrElse(n.id, 0) + 1)).toMap
        (next, next.collect { case (id, c) if c == NodeEngine.StarvedRounds => id }.toSet)
      }
      _ <- qualified.filter(n => starved.contains(n.id)).traverse_ { n =>
        FlowMapEventLog.append(workspace, projectName, n.id, "trigger-starved",
          s"qualified but no session after ${NodeEngine.StarvedRounds} consecutive sweep rounds " +
            s"(status=${n.status}, deps=[${n.deps.mkString(",")}] all completed, " +
            s"in=[${n.in.mkString(",")}] all delivered, non-terminal) — startNode attempts not taking effect; " +
            "check detached trigger logs")
      }
      // 第 3 步：挂载停滞兜底闸（mount-enforce 批 20260905，作者裁定「节点一旦挂载
      // 必须一定生效——不存在挂着但永不启动的合法形态」）：可触发点（入口=创建时 /
      // barrier=全部上游终态时）后 60s 仍 pending/wiring → mount-stalled 事件留痕
      // （含节点 id+等待原因）。补触发不另起机制——第 1/2 步既有骨架即接管（孤儿
      // barrier 自愈投递 + 资格回扫 fork 启动）；上游 failed/cancelled 卡 barrier 的
      // 形态补触发不可达，事件即其可见性载体（分发器巡检处置）。合法等待不误伤：
      // running/wiring 上游 = 可触发点未到，不判停滞（mountStallReason 单点口径）。
      // 判定与资格回扫同源快照（actives，fork 启动前）——本 tick 被补触发的节点同样
      // 先落停滞事件：事件记录的是回扫起点的停滞事实，repair 与留痕同 tick 完成，
      // 互不吞没（若用 fork 后快照，fork 翻转会赢得竞速吞掉事件）。
      nowMs <- IO(System.currentTimeMillis())
      stallChecks <- actives.traverse(n => mountStallReason(n, nowMs).map(reason => n.id -> reason))
      stalledNow = stallChecks.collect { case (id, Some(reason)) => id -> reason }
      prevStall <- stallNotified.get
      // R3 去重（取消静默死锁修复批）：本轮发射集排除「终态写点已即时告警」的节点
      // ——同一停滞期不得发两条（即时 barrier-blocked + 周期 mount-stalled）。
      prevAlerted <- barrierAlerted.get
      stallToEmit = stalledNow.filter { case (id, _) => !prevStall.contains(id) && !prevAlerted.contains(id) }
      _ <- stallNotified.set(stalledNow.map(_._1).toSet)
      // 即时告警记账剪枝：仅保留「此刻仍被终态上游闸住」的下游（恢复即出集）。
      heldNow = actives.collect { case n if barrierHeldReason(s1.nodes, n).isDefined => n.id }.toSet
      _ <- barrierAlerted.set(prevAlerted.intersect(heldNow))
      _ <- stallToEmit.traverse_ { case (id, reason) =>
        FlowMapEventLog.append(workspace, projectName, id, "mount-stalled", reason) *>
          logger.warn(s"[$projectName] node $id mount-stalled: $reason")
      }
      actions = healed.map(_._2) ++ qualified.map(n => s"start ${n.name}(${n.id})")
      _ <- if actions.nonEmpty then
        logger.info(s"[$projectName] settle sweep: ${actions.mkString("; ")}")
      else IO.unit
    yield ()

  /** 挂载停滞判定（mount-enforce 批 §3）：Some(reason)=已过可触发点 60s 仍未触发。
    * 可触发点口径：入口节点（无 in 无 deps）自 createdAt 起；barrier 节点自全部上游
    * （in ∪ deps）到达终态起（四终态 completed/blocked/failed/cancelled 写点均落
    * completedAt——已核实）。barrier 感知（合法等待不判停滞）：
    *   - 任一上游 running/wiring = 被真实上游正确闸住（作者明示合法形态）；
    *   - 上游引用悬空（校验生效前遗留数据）= 保守不判。
    * 全部上游终态后仍 pending/wiring 超 60s = 停滞；reason 携带各上游终态明细 +
    * barrier 残缺清单，供事件流直接定位等待原因。 */
  private def mountStallReason(n: NodeDef, now: Long): IO[Option[String]] =
    // R4：pendingSuccession（「待承接」槽位）并入上游集——被摘除的 cancelled 上游
    // 的 completedAt（= 取消时刻）因此参与可触发点 t0，使「承接等待」与其它停滞
    // 同源计时（60s 档），并让 barrier 残缺清单能点名它。
    (n.in ++ n.deps ++ n.pendingSuccession).distinct.traverse(upId => store.findNode(upId)).flatMap { ups =>
      if ups.exists(_.isEmpty) then IO.pure(None)
      else
        val us = ups.flatten
        val allTerminal = us.forall(u => NodeLifecycle.Terminal.contains(u.status))
        if !allTerminal then IO.pure(None)
        else
          // 入口节点（无上游）可触发点 = createdAt；barrier 节点 = 最晚上游终态时刻
          val t0 = if us.isEmpty then n.createdAt
                   else us.flatMap(_.completedAt).maxOption.getOrElse(n.createdAt)
          val stalledSec = (now - t0) / 1000L
          if stalledSec <= NodeEngine.MountStalledMs / 1000L then IO.pure(None)
          else
            val upDesc = if us.isEmpty then "entry node (no upstreams; triggerable since creation)"
                         else us.map(u => s"'${u.name}'(${u.id}):${u.status}").mkString(", ")
            val barrierDesc = n.in.filterNot(n.deliveredTo.contains) match
              case Nil => "in-barrier cleared"
              case missing => s"in-barrier undelivered=[${missing.mkString(",")}]"
            val successionDesc =
              if n.pendingSuccession.nonEmpty then
                s", awaiting handover (R4 pendingSuccession=[${n.pendingSuccession.mkString(",")}] — cancelled upstream detached; barrier held, dispatcher must hand over 承接 / rewire 改接 / abandon)"
              else ""
            IO.pure(Some(
              s"mount stalled: ${stalledSec}s past triggerable point, still status=${n.status}, " +
                s"$barrierDesc$successionDesc, no running/wiring upstream (upstreams: $upDesc) — settle sweep " +
                "takeover attempted; if still stuck a terminal (failed/cancelled) upstream is blocking " +
                "the barrier — dispatcher intervention required"))
    }

  /** blocked 终态化（设计 §2.1 四条动作序列，与 completeNode 同构）：
    * ① 事务内现读 fresh（R2 纪律）→ status=Blocked / result=渲染串 / blockedFeedback /
    *    blockCount+1 / completedAt=now / ttlExpireAt=None（永不过期=待办语义 §1.4）；
    *    节点已消失/状态已变（NodeEdit 重激活竞态）→ 拒写。
    * ② emitEvent nodeUpdated（复用现有 WS 类型 + NodePayload 同构载荷，不加新事件类型）。
    * ③ 不结算下游（out=节点不做 barrier 占位投递——传播停止是特性；下游 pending
    *    由重入分发器 NodeList 可见并处置）。
    * ④ 调 FeedbackRouter（重入 / 升级 / 频率保护决策）。
    *
    * finalText（blocked 结构化信号批 20260909 spec §5.2 #6）：工具申报通道传入
    * 节点最终全文——result 落库 = 渲染串头部 + 全文拼接；内存 Ref/投递链持有
    * 全文，落盘时 FlowMapStore persist 拆分（results/<nodeId>.md 全文 + JSON
    * ≤500 字符摘要，头部恰为渲染串）观测面信息不丢。文本锚定路径不传
    * （申报即全文，渲染串落库现状维持）。 */
  private def blockedNode(nodeId: String, feedback: BlockedFeedback,
      finalText: Option[String] = None): IO[Unit] =
    for
      now <- IO(System.currentTimeMillis())
      s <- store.mutate { st =>
        st.nodes.get(nodeId) match
          case Some(fresh) if fresh.status == NodeLifecycle.Running =>
            st.copy(nodes = st.nodes.updated(nodeId, withoutReportPending(fresh.copy(
              status = NodeLifecycle.Blocked,
              result = Some(BlockedReader.render(feedback) + finalText.fold("")("\n\n" + _)),
              blockedFeedback = Some(feedback),
              blockCount = fresh.blockCount + 1,
              completedAt = Some(now),
              ttlExpireAt = None))))
          case _ => st // 节点已消失 / 状态已变 → 拒写（R2 竞态纪律）
      }
      _ <- s.nodes.get(nodeId) match
        case Some(bn) if bn.status == NodeLifecycle.Blocked =>
          val summary = s"round ${bn.blockCount}: [${feedback.category}] ${feedback.detail.take(160)}"
          emitWithChain("nodeUpdated", nodeId, NodePayload.buildNodeJson(bn, now)) *>
            logger.warn(s"Node '${bn.name}' blocked — $summary") *>
            FlowMapEventLog.append(workspace, projectName, nodeId, "blocked", summary) *>
            // ③ blocked 出口的销毁窗口登记（noderpt 批 B 段：与桥终态四出口**口径一致**
            // ——终态时刻只登记、到点由 `sweepDestroyWindows` 收殓；blocked 节点在窗口内
            // 仍可被 reactivate，届时 `withdrawDestroyWindow` / 翻转点清零撤销窗口）。
            scheduleDestroy(bn.id, destroyTargetSessions(bn), "blocked") *>
            feedbackRouter.route(bn, feedback)
        case Some(other) =>
          logger.info(s"Node '$nodeId' state changed to '${other.status}' before blocked finalize — refused (fresh-read discipline)")
        case None =>
          logger.warn(s"Node '$nodeId' vanished before blocked finalize — feedback not persisted")
    yield ()

  private def failNode(nodeId: String, err: String): IO[Unit] =
    for
      now <- IO(System.currentTimeMillis())
      s <- store.mutate { st =>
        st.nodes.get(nodeId) match
          case Some(fresh) =>
            st.copy(nodes = st.nodes.updated(nodeId, withoutReportPending(fresh.copy(
              status = NodeLifecycle.Failed,
              result = Some(err),
              completedAt = Some(now),
              // 2026-09-07 作者裁定：failed 无 TTL 强制清——死亡现场保留待上层裁决。
              ttlExpireAt = None))))
          case None => st
      }
      _ <- s.nodes.get(nodeId) match
        case Some(failed) =>
          emitWithChain("nodeUpdated", nodeId, NodePayload.buildNodeJson(failed, now)) *>
            logger.warn(s"Node '${failed.name}' failed: ${err.take(200)}") *>
            // 失败投递（§2.7 + D5 零结算）：out=Nebula → failed 消息；out=节点 →
            // 下游停等零结算（merge 例外转 blocked），停等等待者经尾部通知告知分发器。
            deliverFailed(failed, err) *>
            // R3（取消静默死锁修复批）：failed 侧**唯一**新增行为 = 终态写点同步的
            // barrier 即时告警（作者硬约束：failed 一栏只多 R3 的 barrier 检查，无摘除/
            // 无结算改动——deliverFailed 的 D5 零结算语义逐字不变）。
            checkBarriersNow(failed.id, cause = "failed")
        case None =>
          logger.warn(s"Node '$nodeId' vanished before failure finalize — error not persisted")
    yield ()

  /** cancelled 终态化（**取消静默死锁修复批 2026-09-10 重写**——此前只写 status/
    * completedAt/ttlExpireAt，四条出口全截断：无 result / 无事件 / 不结算 / 不通知）：
    *
    *   ① **R2 原因落盘**：`result = Some("cancelled[source=…]: reason=…")`。此前
    *      reason 只作为内存字符串（桥 `FailOutcome`）存在、在桥后被 `contains("cancelled")`
    *      布尔嗅探，随后被丢弃——**取消原因在全系统零落盘**（设计 §1.2 差异点）。
    *   ② **R2 审计留痕**：`FlowMapEventLog` 新增 `cancelled` 事件（含 source + reason
    *      + 摘除目标）。此前唯一留痕是 `bg-harvest` 那行**无原因**。
    *   ③ **R4 自动摘除 + 「待承接」标记**（`detach = true` 默认）：把本节点 out 改接
    *      Nebula（= 今天人工 `NodeEdit(out=[Nebula])` 的自动化；D5 对 cancelled 的既有
    *      指引就是「从 barrier 摘除」——**不是**零结果结算）+ prune 下游 in 镜像 +
    *      给下游打 `pendingSuccession` 标（barrier 不被以缺轨输入自动触发成缺轨结论）。
    *      `detach = false` = **L3 硬恢复路径专用**（bridge Cancelled → 5s → resume）：
    *      该路径**永不摘除**——resume 成功后拓扑必须完整（否则下游 in 已被 prune、
    *      永远拿不到该节点结果）；resume 失败则 R5 方案 4 把节点改判 `failed`
    *      （[[settleFailedHardResume]]，**也不摘除**：R4 方案 3「failed 也自动摘」已
    *      被永久拒绝，见 [[detachCancelledUpstream]] 头注）。故 L3 路径的 out 边与
    *      下游 in 镜像自始至终原样保留，barrier 靠 D5 零结算停等而非摘除化解。
    *   ④ **R3 即时 barrier 告警**（终态写点同步，0 延迟；周期回扫降为兜底，见
    *      [[checkBarriersNow]] 的去重口径）。
    *   ⑤ **R1 回流分发器**：`notifyTerminal(_, Cancelled)`（不查 notifyDispatcher flag，
    *      与 failed 完全对称；账务与通知文本见 DispatchNotify）。
    *
    * 幂等：detach 幂等（out 已无节点目标即零写）、notify 由 notifySentAt 去重、
    * barrier 告警由即时/回扫共享单发记账去重——重复调用不产生重复副作用。
    * 三口复用（NodeCancel 桥 / dead-session reap / TaskStuckWatcher 取消）：
    * `reason`/`source` 由调用方按 [[CancelSource]] 口径给出（R7 两态）。
    *
    * **`notify = false`（R5 方案 4，2026-09-10 裁定）**：与 `detach = false` 同一
    * 调用点（L3 硬恢复路径）——该路径的 Cancelled 只是**中间态**（bridge Cancelled
    * → 5s → resume），终局由 resume 结果决定，故推迟**回流**而非「不发」：
    *   - 这里不发 Cancelled 通知，改以 [[DispatchNotify.holdTerminalNotify]] 占位
    *     （抑制 30s 补投扫描在 5s 窗口内把中间态当终态回流——见该方法注释）；
    *   - resume 成功 → 节点复活，占位由 `hardResumeNode` 的 CAS 归还，其真实终态
    *     照常回流；
    *   - resume 失败 → [[settleFailedHardResume]] 归还占位并改判 failed（failed
    *     语义回流一次）。
    *   ⇒ 对外可见面恰好一次、语义与终局一致（既不双份 cancelled+failed，也不零回流）。
    * 该参数**只**影响回流时点，不动 ①②③④ 任何行为，也不动 failed 侧（D5 零结算
    * 与 `deliverFailed` 本体逐字不变）。 */
  private def cancelNode(nodeId: String, reason: String, source: CancelSource, detach: Boolean = true, notify: Boolean = true): IO[Unit] =
    val rendered = s"cancelled[source=${CancelSource.code(source)}]: reason=$reason"
    for
      now <- IO(System.currentTimeMillis())
      detached <- if detach then detachCancelledUpstream(nodeId) else IO.pure(Nil)
      s <- store.mutate { st =>
        st.nodes.get(nodeId) match
          case Some(fresh) =>
            st.copy(nodes = st.nodes.updated(nodeId, withoutReportPending(fresh.copy(
              status = NodeLifecycle.Cancelled,
              result = Some(rendered), // R2：取消原因落盘（此前 cancelled result 恒空）
              completedAt = Some(now),
              // 2026-09-07 作者裁定：cancelled 无 TTL 强制清——保留主图待上层处置。
              ttlExpireAt = None))))
          case None => st
      }
      _ <- s.nodes.get(nodeId) match
        case Some(cancelled) =>
          emitWithChain("nodeUpdated", nodeId, NodePayload.buildNodeJson(cancelled, now)) *>
            logger.info(s"Node '${cancelled.name}' cancelled [source=${CancelSource.code(source)}]: ${reason.take(200)}") *>
            FlowMapEventLog.append(workspace, projectName, nodeId, "cancelled",
              s"node cancelled [source=${CancelSource.code(source)}]: ${reason.take(220)}" +
                (if detached.nonEmpty then s" — out detached to Nebula; successors awaiting handover: ${detached.mkString(",")}" else "")) *>
            // P2 G11（spec §3.4）：cancelled 级联清理该会话 pending asks——来源死亡
            // 即关闭（hub 移槽 + askUserClosed 广播），卡片不再僵尸常挂。
            cleanupPendingAsks(cancelled.sessionRef)
        case None =>
          logger.warn(s"Node '$nodeId' vanished before cancel finalize — skipped")
      _ <- checkBarriersNow(nodeId, cause = "cancelled") // R3 即时告警
      _ <-
        // R1 回流（notify=true）；L3 路径（notify=false）改以占位推迟——见方法头注
        // 「notify = false」段（中间态不是终局，终局腿负责真实回流）。
        if notify then s.nodes.get(nodeId).traverse_(n => dispatchNotify.notifyTerminal(n, NotifyReason.Cancelled))
        else dispatchNotify.holdTerminalNotify(nodeId)
    yield ()

  /** R4 自动摘除（取消静默死锁修复批）：被取消节点的 out 改接 Nebula + 受影响下游的
    * in 镜像 prune + 下游登记 `pendingSuccession`（「待承接」）。
    *
    * 语义与 `NodeTools.setOut` 的 removed 侧 prune 同源（`in.filterNot(_ == fromId)`，
    * 设计 §5-Q1 实证的人工原语），但写点在引擎内部（工具层的 NodeEdit 是分发器动作，
    * 不经此路径）。**不产生任何结算/投递**——摘除 = 该边不存在，既非零结算也非占位
    * 投递，因此与 D5 的 failed 零结算纪律不冲突（R4 硬约束：failed 侧零改动）。
    *
    * 幂等：out 已无节点目标（重复调用 / 本就悬空）→ 返回 Nil 且零写。
    * 返回被 prune 的下游 id 集（供审计事件与 R1 通知文本使用）。
    *
    * **U5/E 修复（2026-09-11）**：目标集从「纯前向 out 遍历」扩为
    * `前向目标 ∪ 反向引用方`（[[reversePruneReferences]] 的判据，本方法内联同一事务
    * 计算）。原因：`out` 已被改接 Nebula 而下游 `in` 镜像仍引用本节点的**不一致拓扑**
    * 下，前向遍历恒空 ⇒ 旧口径 `targets.isEmpty` 静默零操作，下游 barrier 无人解救
    * （也不会有任何留痕）。反向引用集在活动区恒可算，恰好覆盖该形态。
    * **零行为漂移保证**：镜像边一致（常态）时反向集 ⊆ 前向集，`distinct` 后结果集与
    * 元素顺序均与旧口径**逐字相同**——本改动只在不一致拓扑下新增 prune。
    *
    * 可见性（R4 验收项「自动摘除 + 标记可见」）：prune 生效时对每个受影响下游补发
    * `nodeUpdated`（payload 走 NodePayload 条件字段 `pendingSuccession`，前端卡片/
    * 分发器 NodeList 同一序列化点）——与 NodeTools.emitWiringUpdates 同款：标记若只
    * 落盘不推帧，前端要等下一次全量快照才见。幂等（无 prune → 零帧）。
    *
    * `case None`（节点不在活动区）保持旧口径 `(s, Nil)`：调用方 [[cancelNode]] 的
    * `store.mutate` 同样查无该节点 ⇒ 整个取消是 no-op 且已有 `Node nodeId vanished
    * before cancel finalize` 响亮留痕（非静默）；离线节点的迟到摘除由 L3 失败腿的
    * [[lateDetachUnlocatableSession]]（含归档区解析）承担，不在本方法范围内。 */
  private def detachCancelledUpstream(nodeId: String): IO[List[String]] =
    store.mutateWithResult { s =>
      s.nodes.get(nodeId) match
        case Some(from) =>
          val forward = from.out.map(_.to).filterNot(_ == OutEdge.NebulaTarget).distinct
            .flatMap(OutEdge.resolveTargetId(s.nodes, _)).distinct
          // U5/E：反向引用方（谁还在 in 里引用我）——不一致拓扑下前向遍历恒空，反向恒可算。
          val reverseOnly =
            s.nodes.values.filter(n => n.id != nodeId && n.in.contains(nodeId)).map(_.id).toList.sorted
          val targets = (forward ++ reverseOnly).distinct
          if targets.isEmpty then (s, Nil)
          else
            val pruned = targets.foldLeft(s.nodes) { (acc, tid) =>
              acc.get(tid) match
                case Some(tn) => acc.updated(tid, tn.copy(
                  in = tn.in.filterNot(_ == nodeId),
                  pendingSuccession = (tn.pendingSuccession :+ nodeId).distinct))
                case None => acc
            }
            (s.copy(nodes = pruned.updated(nodeId, from.copy(out = List(OutEdge.nebula)))), targets)
        case None => (s, Nil)
    }.flatMap { case (_, pruned) =>
      pruned.foldLeft(IO.unit) { (acc, tid) =>
        acc >> store.getNode(tid).flatMap {
          case Some(n) => emitUpdated(n)
          case None    => IO.unit
        }
      }.as(pruned)
    }

  /** R3 终态写点**即时** barrier 检查（取消静默死锁修复批，作者裁定 R3 方案 3）：
    * 终态写点已经知道「谁终态了 + 谁是它的 barrier」，信息完整——把「周期发现」变成
    * 「同步可知」。对每个 in/deps/pendingSuccession 引用 `terminalId` 的 pending/wiring
    * 下游，若该 barrier 已被终态上游永久闸死（[[barrierHeldReason]]）→ 立即写
    * `barrier-blocked` 事件 + WARN（**无 60s 阈值**，理论延迟 ≈ 0）。
    *
    * 与周期回扫（`settleRunnableSweep` → `mountStalledMs` 60s 档 → `mount-stalled`）
    * 的关系：**兜底而非重复**。单发记账由 [[barrierAlerted]] 承担——周期回扫把
    * barrierAlerted 成员排除出发射集，且每轮按「此刻是否仍被闸住」剪枝（恢复即出集）。
    *
    * 反例护栏（对齐 `mountStallReason` 的合法等待豁免，见 [[barrierHeldReason]]）：
    * 仍有 running/wiring 上游 → 不告警；上游引用悬空 → 不告警。 */
  private def checkBarriersNow(terminalId: String, cause: String): IO[Unit] =
    store.snapshot.flatMap { snap =>
      snap.nodes.values.toList
        .filter(n =>
          (n.status == NodeLifecycle.Pending || n.status == NodeLifecycle.Wiring) &&
            (n.in.contains(terminalId) || n.deps.contains(terminalId) || n.pendingSuccession.contains(terminalId)))
        .traverse_ { n =>
          barrierHeldReason(snap.nodes, n) match
            case None => IO.unit
            case Some(reason) =>
              barrierAlerted.modify(s => if s.contains(n.id) then (s, false) else (s + n.id, true)).flatMap {
                case false => IO.unit // 本停滞期已告警（即时或回扫）→ 单发
                case true =>
                  val summary = s"barrier blocked by terminal upstream (cause=$cause): $reason"
                  FlowMapEventLog.append(workspace, projectName, n.id, "barrier-blocked", summary) *>
                    logger.warn(s"[$projectName] node ${n.id} barrier-blocked (cause=$cause): $reason")
              }
        }
    }

  /** barrier 被终态上游**永久闸死**的判定单点（R3 即时告警与周期回扫共享口径，
    * **不含时间阈值**——即时路径要求 0 延迟，60s 阈值只属于周期兜底）。
    * 返回 Some(reason) = 已闸死（barrier 的闸门不会再自行打开）。
    *
    * 判定：
    *   - blocker = ① in 引用且**未投递**且上游已是「非 completed 的终态」
    *     （failed/cancelled/blocked——永不投递）；② deps 引用且上游非 completed；
    *     ③ `pendingSuccession` 成员（R4 摘除后登记的「待承接」槽位）。
    *   - 其余上游必须全部到位（in 已投递 / deps 已 completed）——**仍有 running/
    *     wiring 上游 = 合法等待，不判**（mountStallReason 同款豁免）。
    *   - 任一 in/deps 引用在图上查不到（悬空遗留数据）→ 保守不判。
    *   - `completed 但未投递`的上游**不算 blocker**（那是投递丢失，settle 回扫的自愈
    *     对象，不是终态闸死）——避免重复告警噪音。 */
  private def barrierHeldReason(nodes: Map[String, NodeDef], n: NodeDef): Option[String] =
    val refs = (n.in ++ n.deps).distinct
    val resolved = refs.flatMap(id => nodes.get(id).map(id -> _))
    if resolved.size != refs.size then None
    else
      def upstreamOf(id: String): Option[NodeDef] = nodes.get(id)
      def permanentlyTerminal(id: String): Boolean =
        upstreamOf(id).exists(u => u.status != NodeLifecycle.Completed && NodeLifecycle.Terminal.contains(u.status))
      val inBlockers = refs.filter(id => n.in.contains(id) && !n.deliveredTo.contains(id) && permanentlyTerminal(id))
      val depsBlockers = refs.filter(id => n.deps.contains(id) && upstreamOf(id).exists(_.status != NodeLifecycle.Completed))
      val blockers = (inBlockers ++ depsBlockers ++ n.pendingSuccession).distinct
      if blockers.isEmpty then None
      else
        val othersOk = refs.forall { id =>
          blockers.contains(id) ||
            ((!n.in.contains(id) || n.deliveredTo.contains(id)) &&
              (!n.deps.contains(id) || upstreamOf(id).exists(_.status == NodeLifecycle.Completed)))
        }
        if !othersOk then None
        else
          val blockerDesc = blockers.map { id =>
            upstreamOf(id).map(u => s"'${u.name}'(${id}):${u.status}").getOrElse(s"$id:gone")
          }.mkString(", ")
          if n.pendingSuccession.nonEmpty then
            Some(s"in-barrier awaiting handover — pendingSuccession=[${n.pendingSuccession.mkString(",")}] " +
              s"(cancelled upstream detached by R4; the slot is explicitly NOT settled and the barrier will not " +
              s"auto-trigger until the dispatcher hands over) — upstreams: $blockerDesc")
          else
            Some(s"in-barrier permanently wedged by terminal upstream (no settlement for failed/cancelled) — " +
              s"upstreams: $blockerDesc — dispatcher must hand over (承接) / rewire (改接) / abandon")

  /** P2 G11（spec §3.4）：节点 cancelled 级联清理其会话的 pending asks——hub 新增
    * CleanupForSession(sessionId)：移除该会话 pending 槽 + 向 root 广播
    * askUserClosed{requestId}（前端关卡摘卡归批 F2，本批验收到引擎广播为止）。
    * 三口级联：cancelNode（NodeCancel/bridge cancelled 分流）+ NodeEdit abandon +
    * dead-session reap（reapStaleRunning 内部走 cancelNode，自动覆盖）。
    * sessionRef=None（从未启动过，无会话即无问）或 hub 未挂（测试/早期 boot）→
    * 静默跳过。fire-and-forget（tell 语义）：清理失败不阻断 cancelled 终态化。 */
  def cleanupPendingAsks(sessionId: Option[String]): IO[Unit] =
    sessionId.traverse_ { sid =>
      resources.interactionHubRef.get.flatMap {
        case Some(hub) => (hub ! InteractionHubCommand.CleanupForSession(sid)).void
        case None => IO.unit
      }
    }

  // ── 投递（§2.7）─────────────────────────────────────────

  /** 节点目标结算（deliverOut / deliverFailed signal 边 / deliverOutTo 共用骨架）：
    * target.deliveredTo += node.id（dedup 原子记账）→ barrier 归零且非 running 则
    * fork 启动下游。载荷注入与否由 buildInput 按上游→下游边 mode 判定——本函数只管
    * 记账与启动（「signal 只记账归零 barrier」与「result 投载荷」在投递侧同形，
    * 差异全在输入装配侧，deps 同款拆分）。
    * 目标解析（20260909 in 丢失事故修复面③）：边 to 串有节点 id / 节点名两形态
    * （LLM 按名接线的自然写法原样落库；存量归档数据同），记账/启动统一落到解析后
    * 的 id——名字形态边不再是死边（原实现 findNode 按原始串查，名字边投递静默丢失）。 */
  private def settleTo(node: NodeDef, targetId: String): IO[Unit] =
    store.snapshot.map(s => OutEdge.resolveTargetId(s.nodes, targetId)).flatMap {
      case None =>
        logger.warn(s"Node '${node.name}' out target '$targetId' not found — result retained")
      case Some(tid) =>
        store.findNode(tid).flatMap {
          case None =>
            logger.warn(s"Node '${node.name}' out target '$targetId' not found — result retained")
          case Some(_) =>
            // 原子：target.deliveredTo += node.id（dedup）+ 更新
            store.mutate { s =>
              val t = s.nodes.get(tid)
              t match
                case Some(tn) if !tn.deliveredTo.contains(node.id) =>
                  s.copy(nodes = s.nodes.updated(tid, tn.copy(deliveredTo = tn.deliveredTo :+ node.id)))
                case _ => s
            }.flatMap { s =>
              val tn = s.nodes.get(tid)
              // R4：barrier 归零判定并入「待承接」标记（缺轨输入不得自动触发下游）。
              val allArrived = tn.exists(tn2 =>
                tn2.in.forall(upId => tn2.deliveredTo.contains(upId)) && tn2.pendingSuccession.isEmpty)
              if allArrived && tn.exists(_.status != NodeLifecycle.Running) then
                forkStart(s"deliver-out -> $tid")(startNode(tid))
              else IO.unit
            }
        }
    }

  /** 完成投递（P1 语义门控，spec §2.2 #2）：沿 on ∋ pass 过滤出边后投递。fan-out：
    * Nebula 通报与多个节点目标并存（旧单值拓扑 = 单边，行为等价）。mode=result →
    * 投载荷（经 buildInput 注入）+ Nebula 通报；mode=signal → 只记账归零 barrier，
    * Nebula 只记账不通报（重投扫描按同门公式判定，不会重投）。dedup 用 deliveredTo
    * （防改接重投）；canonical 合并同 (to,mode) 多边 on 集——一次终态至多投一次。 */
  private def deliverOut(node: NodeDef, resultText: String): IO[Unit] =
    val passEdges = OutEdge.canonical(node.out).filter(_.on.contains(OutEdge.Pass))
    nebulaDelivery(node, resultText, passEdges.partition(_.to == OutEdge.NebulaTarget)._1) *>
      passEdges.filterNot(_.to == OutEdge.NebulaTarget).traverse_(e => settleTo(node, e.to))

  private def nebulaDelivery(node: NodeDef, resultText: String, nebulaEdges: List[OutEdge]): IO[Unit] =
    if nebulaEdges.isEmpty then IO.unit // 悬空/无 pass 边：结果保留在 result（持久化）
    else if nebulaEdges.exists(_.mode == OutEdge.Result) then
      deliverToNebula(s"[Node '${node.name}' completed]\n$resultText", node.name, "completed", Some(node.id))
    else markNebulaDelivered(node.id)

  /** 失败投递（P1 语义门控，spec §2.2 #3；D5 零结算为底座）：
    * ① 运行期 merge 兜底（纵深，护栏只读复用不重构 §2.6-2）：全部 out 边的节点目标
    *    中 MergeNodePolicy.haltsOnFailure 命中 → mergeBlocked（**与门控无关**——覆盖
    *    校验生效前落盘的旧拓扑 pass-only 边；创建期 NODE_MERGE_PASS_ONLY 已硬拒新
    *    failed 入边）。先于 signal 结算执行（merge blocked 优先于下游触发，D5 原序）。
    * ② Nebula 上报（on ∋ failed）：mode=result → 通报（旧 "Nebula" 双通报形态零漂移）；
    *    mode=signal → 只记账。
    * ③ on-failed signal 节点目标：settleTo 记账归零 barrier + 启动（buildInput 按边
    *    mode 抑制载荷——failed 节点 result=错误文本不作输入投递）。
    * ④ 其余（on-failed result 节点目标 = D5 零结算语义对齐，及无 failed 边覆盖的
    *    pass-only 旧拓扑目标）：停等——不结算不投递，恢复零步（上游 reactivate 修复
    *    重跑完成后经 deliverOut 正常投递）。
    * 尾部 dispatch-notify 挂点不变（failed 版，不查 flag）。 */
  private def deliverFailed(node: NodeDef, err: String): IO[Unit] =
    val edges = OutEdge.canonical(node.out)
    val failedEdges = edges.filter(_.on.contains(OutEdge.Failed))
    val nodeTargets = edges.map(_.to).filterNot(_ == OutEdge.NebulaTarget).distinct
    val signalTargets = failedEdges
      .filter(e => e.to != OutEdge.NebulaTarget && e.mode == OutEdge.Signal).map(_.to).distinct
    val mergeFallback: IO[Unit] = nodeTargets.traverse_(t =>
      store.findNode(t).flatMap {
        case Some(target) if MergeNodePolicy.haltsOnFailure(target) =>
          mergeBlockedByUpstreamFailure(target, node, err)
        case _ => IO.unit
      })
    val nebulaIO = failedEdges.filter(_.to == OutEdge.NebulaTarget) match
      case Nil => IO.unit
      case nes if nes.exists(_.mode == OutEdge.Result) =>
        deliverToNebula(s"[Node '${node.name}' failed]\n$err", node.name, "failed", Some(node.id))
      case _ => markNebulaDelivered(node.id)
    val signalIO = signalTargets.traverse_(t => settleTo(node, t))
    val waitLog: IO[Unit] =
      val waiting = nodeTargets.diff(signalTargets) // 非 signal 覆盖的目标 = D5 停等（merge 兜底者已转 blocked）
      if waiting.nonEmpty then
        logger.info(s"Node '${node.name}' failed — downstream(s) ${waiting.mkString(", ")} keep waiting (D5 zero-settlement; on-failed signal edges opt targets out; reactivate upstream to auto-resume)")
      else IO.unit
    for
      _ <- mergeFallback
      _ <- nebulaIO
      _ <- signalIO
      _ <- waitLog
      // P2 retry 单点触发（spec §2.3，wf3 §8-2）：retry 命中（gen < max）→ 自动回跳
      // 重激活链**替代**本轮 dispatch-notify failed 通知（自愈处置中不扰分发器——
      // 可见性由 nodeUpdated 事件 + FlowMapEventLog "retry" 留痕承载，cap 耗尽的
      // RetryCap 升级才是人工注意力挂点）；未配置 → 既有 failed 通知原样（D5 尾部
      // 挂点零改动）；cap 耗尽 → failed 通知 + RetryCap 升级双发（分发器拓扑通知
      // 不丢，Nebula 另获预算耗尽升级）。同函数不重写 D5 语义——merge 兜底/Nebula
      // 门控通报/signal 结算/停等留痕全部原样先行。
      _ <- retryOrNotify(node, err)
    yield ()

  /** P2 retry 触发判定（spec §2.3）：三态分流见 deliverFailed 尾注。 */
  private def retryOrNotify(node: NodeDef, err: String): IO[Unit] =
    node.retry match
      case None => dispatchNotify.notifyTerminal(node, NotifyReason.Failed)
      case Some(policy) if node.gen < policy.max => retryReactivate(node, policy, err)
      case Some(_) =>
        dispatchNotify.notifyTerminal(node, NotifyReason.Failed) *>
          feedbackRouter.routeRetryCap(node, err)

  /** 自动回跳重激活链（spec §2.3：既有重激活协议的自动化——「不是新机制，是分发器
    * 今天人工处置的自动化」）：
    * ① 本节点（failed）重激活：gen+1（代次载体，blockCount 分立口径不变）、
    *    deliveredTo 清、result/时间戳/投递记账复位、轮次历史复位（与 NodeEdit
    *    failed 重激活同字段族）；R2 纪律——事务内现读 fresh 仍 failed 才写。
    * ② retry.upstream 重激活（仅终态上游——wiring/pending/running 的上游已有在途
    *    重跑，不干预不双跑）。
    * ③ 本节点其余 in 上游（终态有结果、非 blocked）重投（NodeEdit 重激活补投同款，
    *    deliverOutTo dedup 幂等）——否则清账后 barrier 永不归零。
    * ④ fork：上游补投+启动（入口上游直接启动）——上游重跑完成经 pass 边重投本节点
    *    （settleTo 记账归零 barrier → 启动），deps 邻居场景经 settleDeps 触达 →
    *    本节点新代次启动。
    * 已终态的其他消费者不被重跑意外重触发：deliverOut 幂等记账 + startNode 终态
    * 幂等跳过——与今天人工重激活语义完全一致（spec §2.3 红线）。 */
  private def retryReactivate(failed: NodeDef, policy: RetryPolicy, err: String): IO[Unit] =
    for
      now <- IO(System.currentTimeMillis())
      s <- store.mutate { st =>
        st.nodes.get(failed.id) match
          case Some(fresh) if fresh.status == NodeLifecycle.Failed =>
            val nextStatus =
              if fresh.task.exists(_.trim.nonEmpty) && fresh.in.isEmpty then NodeLifecycle.Pending
              else NodeLifecycle.Wiring
            st.copy(nodes = st.nodes.updated(failed.id, fresh.copy(
              status = nextStatus,
              result = None,
              deliveredTo = Nil,
              gen = fresh.gen + 1,
              nebulaDeliveredAt = None,
              startedAt = None,
              completedAt = None,
              ttlExpireAt = None,
              blockCount = 0, // failed 重激活轮次历史复位（NodeEdit 同款口径）
              blockedFeedback = None,
              notifySentAt = None)))
          case _ => st // 状态已变（并发 abandon/人工重激活）→ 拒写不回跳
      }
      _ <- s.nodes.get(failed.id) match
        case Some(b) if b.gen == failed.gen + 1 &&
          (b.status == NodeLifecycle.Wiring || b.status == NodeLifecycle.Pending) =>
          val retryNote =
            s"failed (gen ${b.gen}/${policy.max}): ${err.take(140)} — auto-retry: reactivating self + rerunning upstream '${policy.upstream}'"
          emitWithChain("nodeUpdated", b.id, NodePayload.buildNodeJson(b, now)) *>
            FlowMapEventLog.append(workspace, projectName, b.id, "retry", retryNote) *>
            logger.warn(s"Node '${b.name}' (${b.id}) $retryNote") *>
            store.getNode(policy.upstream).flatMap {
              case Some(up) if NodeLifecycle.Terminal.contains(up.status) =>
                reactivateForRetry(up, now)
              case _ => IO.unit
            } *>
            b.in.filterNot(_ == policy.upstream).traverse_ { upId =>
              store.findNode(upId).flatMap {
                case Some(up) if up.result.isDefined && up.status != NodeLifecycle.Blocked && NodeLifecycle.Terminal.contains(up.status) =>
                  deliverOutTo(up, b.id, up.result.get)
                case _ => IO.unit
              }
            } *>
            forkStart(s"retry-rerun -> ${policy.upstream}")(
              store.getNode(policy.upstream).flatMap {
                case None => IO.unit
                case Some(up) => redeliverInAndStart(up)
              })
        case _ =>
          logger.info(s"Node '${failed.name}' retry skipped — state changed before retry finalize (fresh-read discipline)")
    yield ()

  /** retry 上游重激活（既有重激活协议字段族，NodeEdit failed 重激活同构——不触碰
    * task/description/in/out/deps/loop/retry）：result 清（旧产物不作投递，重跑产出
    * 全新投递）、deliveredTo/nebulaDeliveredAt 清（重跑完成后重新投递+记账）、时间戳
    * 复位；failed 上游轮次历史复位，completed 上游轮次字段原样。R2 竞态守卫：fresh
    * 仍处重激活前看到的终态才写（并发人工重激活/abandon → 拒写）。 */
  private def reactivateForRetry(up: NodeDef, now: Long): IO[Unit] =
    store.mutate { st =>
      st.nodes.get(up.id) match
        case Some(fresh) if fresh.status == up.status && NodeLifecycle.Terminal.contains(fresh.status) =>
          val fromFailed = fresh.status == NodeLifecycle.Failed
          val nextStatus =
            if fresh.task.exists(_.trim.nonEmpty) && fresh.in.isEmpty then NodeLifecycle.Pending
            else NodeLifecycle.Wiring
          st.copy(nodes = st.nodes.updated(up.id, fresh.copy(
            status = nextStatus,
            result = None,
            deliveredTo = Nil,
            nebulaDeliveredAt = None,
            startedAt = None,
            completedAt = None,
            ttlExpireAt = None,
            blockCount = if fromFailed then 0 else fresh.blockCount,
            blockedFeedback = if fromFailed then None else fresh.blockedFeedback,
            notifySentAt = if fromFailed then None else fresh.notifySentAt)))
        case _ => st
    }.flatMap { s2 =>
      s2.nodes.get(up.id) match
        case Some(u2) if u2.status == NodeLifecycle.Wiring || u2.status == NodeLifecycle.Pending =>
          emitWithChain("nodeUpdated", u2.id, NodePayload.buildNodeJson(u2, now))
        case _ => IO.unit
    }

  /** 重激活补投递 + 启动（NodeTools 重激活链同款骨架，引擎内单点）：全部 in 上游
    * 「终态有结果、非 blocked」重投（deliverOutTo dedup 幂等）→ barrier 归零启动；
    * 入口节点（无 in、Pending）直接启动。 */
  private def redeliverInAndStart(n: NodeDef): IO[Unit] =
    n.in.traverse_ { upId =>
      store.findNode(upId).flatMap {
        case Some(up) if up.result.isDefined && up.status != NodeLifecycle.Blocked && NodeLifecycle.Terminal.contains(up.status) =>
          deliverOutTo(up, n.id, up.result.get)
        case _ => IO.unit
      }
    } *> store.getNode(n.id).flatMap {
      case Some(n2) if n2.in.nonEmpty && n2.in.forall(n2.deliveredTo.contains) => startNode(n2.id)
      case Some(n2) if n2.in.isEmpty && n2.status == NodeLifecycle.Pending => startNode(n2.id)
      case _ => IO.unit
    }

  /** 合并节点因上游失败转 blocked（merge-node 批 §触发语义②；与 blockedNode 同构
    * 但**不经 FeedbackRouter**）：① 事务内现读 fresh（R2 纪律，haltsOnFailure 只认
    * wiring/pending）→ status=Blocked / result=渲染串 / blockedFeedback=合成反馈 /
    * completedAt=now / ttlExpireAt=None（待办语义）。blockCount 不增（非节点自报
    * 轮次，重入主责在失败上游侧，MergeNodePolicy.upstreamFailureFeedback）。
    * ② emitEvent nodeUpdated + FlowMapEventLog "merge-blocked" + deliverToNebula
    * 通报（无 nodeId=fire-and-forget，对齐 escalate 通道：状态通报不是结果投递）。
    * 语义收益：不触发合并（占位结算被旁路）+ 不永久 pending 悬挂（blocked 可见
    * 终态，分发器 NodeList 可巡检）+ 已完成上游的结算保留（修复上游 → 重激活合并
    * 节点 → 既有重激活重投链自动补齐 barrier）。
    * public（mount-enforce 批）：第二挂接点 = NodeTools 创建期 D1 保留投递——
    * 合并节点创建时 in 引用已 failed 的上游（创建顺序翻转后合法形态），failed
    * 错误文本不作输入投递，同语义转 blocked 不悬挂。 */
  def mergeBlockedByUpstreamFailure(target: NodeDef, failed: NodeDef, err: String): IO[Unit] =
    val feedback = MergeNodePolicy.upstreamFailureFeedback(failed, err)
    for
      now <- IO(System.currentTimeMillis())
      s <- store.mutate { st =>
        st.nodes.get(target.id) match
          case Some(fresh) if MergeNodePolicy.haltsOnFailure(fresh) =>
            st.copy(nodes = st.nodes.updated(target.id, withoutReportPending(fresh.copy(
              status = NodeLifecycle.Blocked,
              result = Some(MergeNodePolicy.renderBlocked(feedback)),
              blockedFeedback = Some(feedback),
              completedAt = Some(now),
              ttlExpireAt = None))))
          case _ => st // 状态已变（并发终态化）→ 拒写（R2 竞态纪律）
      }
      _ <- s.nodes.get(target.id) match
        case Some(bn) if bn.status == NodeLifecycle.Blocked =>
          val summary = s"merge node blocked: upstream '${failed.name}' (${failed.id}) failed — ${err.take(140)}"
          emitWithChain("nodeUpdated", target.id, NodePayload.buildNodeJson(bn, now)) *>
            logger.warn(s"Node '${bn.name}' $summary") *>
            FlowMapEventLog.append(workspace, projectName, target.id, "merge-blocked", summary) *>
            deliverToNebula(
              s"[Node '${bn.name}' blocked — 上游 '${failed.name}' failed，合并未执行]\n${err.take(800)}",
              bn.name, NodeLifecycle.Blocked)
        case _ => IO.unit
    yield ()

  /** out=Nebula：ImmediateInput 投 Nebula 根会话（source="node"，复用 flow 气泡语义）。
    * 气泡 header 契约（前端 chat.js injectedSourceLabel node 分支）：
    *   source = "node" → 显示段 NODE
    *   eventType = status（"completed" | "failed"）→ 显示段 COMPLETED / FAILED
    *   sender = "<projectName>/<nodeName>" → 显示段 <项目名> · <节点名>
    * 整条 header = NODE · <项目名> · <节点名> · <状态>。
    *
    * V8 (2026-09-03)：带 nodeId 的调用（deliverOut/deliverFailed/deliverOutTo/重投
    * 扫描）在 offer 后写 nebulaDeliveredAt 记账（at-least-once：offer 与记账之间
    * 崩溃 → 重投扫描会再投一次，宁重复不丢失）。nodeId=None（FeedbackRouter
    * escalate 复用通道）保持 fire-and-forget——blocked 反馈本体已持久化在节点上，
    * 升级消息不进重投扫描（避免对已处置的 blocked 再升级）。根 ref 缺失时不丢弃：
    * 不记账 → 周期重投扫描在根会话可用后补投。 */
  private[project] def deliverToNebula(text: String, nodeName: String, status: String, nodeId: Option[String] = None): IO[Unit] =
    resources.agentRegistry.get.map(_.get(rootSessionId).map(_.ref)).flatMap {
      case Some(ref) =>
        // 缺口4：同 (identity, status) 60s 窗口去重——抑制重复 offer（首投已入
        // 根会话队列），仍刷新账本（账本与通知解耦：本表只压秒级抖动，V8
        // at-least-once 语义不变）。不同 status 天然独立窗口（running→completed
        // 互不挡）。
        dedupeNebulaDelivery(nodeId.getOrElse(nodeName), status).flatMap {
          case true =>
            logger.warn(
              s"[dedup] suppressed duplicate Nebula delivery (identity=${nodeId.getOrElse(nodeName)}, status=$status, window=${NodeEngine.NebulaDedupWindowMs}ms, rootSession=$rootSessionId)") *>
              nodeId.traverse_(id => markNebulaDelivered(id)).void
          case false =>
            (ref ! AgentCommand.ImmediateInput(
              text,
              source = Some("node"),
              eventType = Some(status),
              sender = Some(s"$projectName/$nodeName"),
              fromUser = false // ② 服务端注入（节点状态），不是真人输入
            )) *> nodeId.traverse_(id => markNebulaDelivered(id)).void
        }
      case None =>
        logger.warn(s"Root session '$rootSessionId' not found — node result parked for redelivery scan (nodeName=$nodeName)")
        IO.unit
    }

  /** 缺口4：去重判定+登记（固定窗：首投时间戳起算 60s，不滑动；过期条目顺路
    * 淘汰=时间窗淘汰）。true = 窗口内重复（应抑制 offer）。 */
  private def dedupeNebulaDelivery(identity: String, status: String): IO[Boolean] =
    IO(System.currentTimeMillis()).flatMap { now =>
      recentNebulaDeliveries.modify { m =>
        val live = m.view.filter { case (_, ts) => now - ts < NodeEngine.NebulaDedupWindowMs }.toMap
        live.get((identity, status)) match
          case Some(_) => (live, true)
          case None    => (live.updated((identity, status), now), false)
      }
    }

  /** V8: 写 nebulaDeliveredAt 记账（活动区优先，归档区兜底——TTL 归档的
    * 未投递节点同样要记账，否则扫描每次重启都重投）。 */
  private def markNebulaDelivered(nodeId: String): IO[Unit] =
    store.getNode(nodeId).flatMap {
      case Some(_) =>
        store.mutate { s =>
          s.nodes.get(nodeId) match
            case Some(n) => s.copy(nodes = s.nodes.updated(nodeId, n.copy(nebulaDeliveredAt = Some(System.currentTimeMillis()))))
            case None    => s
        }.void
      case None =>
        store.mutateArchive { a =>
          a.nodes.get(nodeId) match
            case Some(n) => a.copy(nodes = a.nodes.updated(nodeId, n.copy(nebulaDeliveredAt = Some(System.currentTimeMillis()))))
            case None    => a
        }.void
    }

  /**
   * V8 (2026-09-03): 重启重投扫描——扫活动区+归档区全部「终态（completed/failed）
   * + out=Nebula + 有 result + 未记账」的节点，补投并记账。挂载时立即跑一次
   * （ProjectRuntimeRegistry.mount），此后挂载在 TtlTick 周期（GatewayMain 启动的
   * projectTtlScanner，30s）——启动期根会话 actor 通常尚未 spawn（懒加载），周期
   * 扫描保证根会话可用后的 30s 内补投。根 ref 缺失时静默跳过（结果滞留 map，
   * 不消费不记账，下个 tick 再试）。返回本次补投的节点数（>0 时 actor 记 info）。
   */
  def redeliverUnconsumedNebulaResults(): IO[Int] =
    resources.agentRegistry.get.flatMap { registry =>
      if !registry.contains(rootSessionId) then IO.pure(0)
      else
        for
          active <- store.snapshot
          arch <- store.archiveSnapshot
          now <- IO(System.currentTimeMillis())
          unpaid = (active.nodes.values ++ arch.nodes.values)
            .filter(n =>
              (n.status == NodeLifecycle.Completed || n.status == NodeLifecycle.Failed) &&
                // P1（spec §2.2 #5）：按边判定——存在指向 Nebula 且 on 含「status 对应门」
                // 的边（completed→pass / failed→failed）。旧 "Nebula" 双读为 {pass,failed}
                // 双通报边 → 两态恒命中（旧拓扑零漂移）；显式门控按声明收窄。
                n.out.exists(e => e.to == OutEdge.NebulaTarget &&
                  e.on.contains(if n.status == NodeLifecycle.Completed then OutEdge.Pass else OutEdge.Failed)) &&
                n.result.exists(_.trim.nonEmpty) &&
                n.nebulaDeliveredAt.isEmpty
            )
          // 缺口3（2026-09-04 夹具信封排除）：名字命中夹具家族 ∧ 任务载荷带夹具
          // 自证标记 → 排除投递 + WARN（09-03 实证 cancel-test-* 夹具家族轰炸根
          // 会话）。排除后照常记账（nebulaDeliveredAt 置位=通知通道对该信封关闭）
          // ——否则 30s 周期扫描每轮重复 WARN。结果不删不改，滞留节点可查。
          fixtures = unpaid.filter(NodeEngine.isFixtureEnvelope).toList
          _ <- fixtures.traverse_(n =>
            logger.warn(
              s"[fixture-guard] excluded fixture envelope from redelivery scan: node '${n.name}' (${n.id}) status=${n.status} — name matches fixture family and task carries fixture marker")
              *> markNebulaDelivered(n.id))
          pending = unpaid.filterNot(NodeEngine.isFixtureEnvelope).toList
          // 缺口2（2026-09-04 补投新鲜度门控）：completedAt 距今 ≤24h（与节点显示
          // TTL 同口径）= 新鲜欠账 → 逐条投（既有形态零改动）；>24h = 历史欠账 →
          // 同批合并单条汇总通知（结果照投不丢：每节点 id+状态+摘要入汇总并逐节点
          // 记账）。completedAt 缺失 = 无法判旧 → 按新鲜逐条（宁投勿丢）。首次实时
          // 投递（completeNode/deliverOut）不走此路径，零改动。
          // MUTATION-2 已恢复：新鲜度门控（缺口2）——completedAt 距今 ≤24h 逐条、>24h 合并
          (fresh, stale) = pending.partition(n => n.completedAt.forall(c => now - c <= NodeEngine.StaleRedeliveryMs))
          _ <- fresh.traverse_(n =>
            deliverToNebula(s"[Node '${n.name}' ${n.status}]\n${n.result.get}", n.name, n.status, Some(n.id)))
          _ <- if stale.nonEmpty then deliverStaleSummary(stale) else IO.unit
          _ <- if pending.nonEmpty then
            logger.info(s"Node redelivery scan: re-delivered ${pending.size} unconsumed out=Nebula result(s) to root '$rootSessionId' (fresh=${fresh.size} stale-merged=${stale.size} fixture-excluded=${fixtures.size})")
          else IO.unit
        yield pending.size
    }

  /** 缺口2：历史欠账（completedAt >24h）同批合并单条汇总通知（形态 (a) 取舍：
    * 纯后端立即生效，不依赖前端改动——形态 (b) 载荷打标记需后续前端批才可见）。
    * 一条文本含 N 条 nodeId+状态+结果摘要（160 字/条）。eventType：混含 failed →
    * "failed"（强提醒），全 completed → "completed"。**绕过缺口4 去重直投**——
    * 汇总文本每批唯一，若走 deliverToNebula 会与 60s 前一批汇总同 key 相撞被
    * 误抑制（节点已被记账=通知丢失）。offer 后逐节点记账（at-least-once：offer
    * 与记账间崩溃 → 下轮扫描重汇总，宁重复不丢失）。 */
  private def deliverStaleSummary(stale: List[NodeDef]): IO[Unit] =
    val eventType = if stale.exists(_.status == NodeLifecycle.Failed) then NodeLifecycle.Failed else NodeLifecycle.Completed
    val lines = stale.zipWithIndex
      .map((n, i) => s"${i + 1}. [${n.status}] ${n.name} (${n.id}): ${n.result.get.trim.take(NodeEngine.StaleSummaryPerNodeChars)}")
      .mkString("\n")
    val text = s"[Node 历史欠账汇总补投 ×${stale.size}（>${NodeEngine.StaleRedeliveryMs / 3600000}h 未消费，项目 $projectName）]\n$lines"
    resources.agentRegistry.get.map(_.get(rootSessionId).map(_.ref)).flatMap {
      case Some(ref) =>
        (ref ! AgentCommand.ImmediateInput(
          text,
          source = Some("node"),
          eventType = Some(eventType),
          sender = Some(s"$projectName/stale-redelivery-summary"),
          fromUser = false // ② 服务端注入（重投汇总），不是真人输入
        )) *> stale.traverse_(n => markNebulaDelivered(n.id))
      case None => IO.unit // 根不可达 → 不记账不丢账，下轮扫描重汇总
    }

  /** 分发器会话最终输出投递（2026-09-05 作者裁定「任务分发器的结果没有任何人看见，
    * 把这个接线给 nebula」）：分发器 turn 终态时由 ProjectActor 观察桥调用——取本
    * turn 最终 assistant 文本（extractLastAssistantText），注入 Nebula 根会话。
    * 空文本（无 assistant 文本或纯空白）不投（防御性判空，debug 级留痕）。
    *
    * 格式（对照节点投递家族）：text 头部标注行 [Dispatcher '<项目名>' · task:
    * <触发任务摘要>] + 换行 + 最终输出全文，对照 "[Node '<name>' completed]"
    * 头部行家族；蓝气泡 source="dispatcher"（DispatcherSourceMarker）走前端
    * injectedSourceLabel 通用分支 → "Dispatcher · <项目名> · Completed"，
    * 与 NODE/MAIL 同族蓝气泡标注体系，零前端改动。
    *
    * 与节点投递（deliverToNebula）的关系——独立新投递种类，刻意绕过节点投递的
    * 全部账本机制（绕过去重直投有 deliverStaleSummary 先例）：
    *  - 不进 V8 nebulaDeliveredAt 账本/重投扫描：分发器输出非节点结果、无持久化
    *    载体可补投，单次会话单次投递，at-least-once 无对象；
    *  - 不进 60s 去重窗口：同项目短窗内多次触发任务是各自独立的合法投递（每次
    *    触发一个新 turn），按 (identity,status) 去重会吞掉合法的第二次；
    *  - 不走夹具信封排除：分发器输出不是节点结果，夹具家族语义不适用。
    * 忙时排队自动继承：ImmediateInput 在根会话忙 turn 时入 pendingImmediateInputs
    * （AgentActor processing 态排队、turn 边界串行 drain），不打断不丢。
    * 根会话 ref 缺失 → WARN 丢弃（无账本无重投，Flow Map 拓扑仍是事实来源）；
    * 占位/跳过类极简输出照常投递（无特殊抑制——分发器一条短行也是有效反馈）。 */
  def deliverDispatcherOutputToNebula(messages: List[Message], taskSummary: Option[String]): IO[Unit] =
    extractLastAssistantText(messages) match
      case "" =>
        logger.debug(s"Dispatcher turn produced no text output (project=$projectName) — nothing to deliver")
      case finalText =>
        resources.agentRegistry.get.map(_.get(rootSessionId).map(_.ref)).flatMap {
          case Some(ref) =>
            val header = taskSummary.map(_.trim).filter(_.nonEmpty) match
              case Some(s) => s"[Dispatcher '$projectName' · task: $s]"
              case None    => s"[Dispatcher '$projectName']"
            (ref ! AgentCommand.ImmediateInput(
              s"$header\n$finalText",
              source = Some(NodeEngine.DispatcherSourceMarker),
              eventType = Some(NodeLifecycle.Completed),
              sender = Some(projectName),
              fromUser = false // ② 服务端注入（分发器最终输出），不是真人输入
            )).void
          case None =>
            logger.warn(
              s"Root session '$rootSessionId' not found — dispatcher final output not deliverable (project=$projectName, ${finalText.length} chars dropped; no ledger/no redelivery by design)")
        }

  private def extractLastAssistantText(messages: List[Message]): String =
    messages.reverse
      .collectFirst {
        case m if m.role == nebflow.shared.MessageRole.Assistant => m.textContent
      }
      .filter(_.trim.nonEmpty)
      .getOrElse("")

  private case class FailOutcome(message: String)

object NodeEngine:
  /** completed 节点显示 TTL（24h——2026-09-02 作者裁定；测试档可缩短——ProjectActor
    * 注入）。2026-09-07 裁定收紧：仅 completed 带 TTL 到期自动归档；failed/cancelled
    * （与 blocked 同）ttlExpireAt=None 不过期——死亡现场保留主图待上层裁决。 */
  val TtlDisplayMs: Long = 24 * 60 * 60 * 1000L

  /** startNode 翻转事务的结局（trigger-chain-fix §6.1 CAS 守卫）：Done=本 fiber
    * 完成翻转（唯一赢家，继续 spawn）；LostRace=败方（他者已翻转到 Running——
    * fork 并行化的正常形态，安静回滚不事件）；Aborted=真异常（节点消失/barrier
    * 未齐/终态竞合）→ start-aborted 事件 + 错误上抛。判定在 mutateWithResult
    * 事务内产出（翻转后状态恒 Running，事后补查无法区分赢家与败方）。 */
  enum FlipOutcome:
    case Done, LostRace
    case Aborted(reason: String)

  /** trigger-starved 触发阈值（§6.3）：同一节点连续 ≥N 轮资格回扫均满足资格却仍
    * 非终态无会话（fork 启动未生效）才落事件——每饥饿期单发（计数恰等于阈值时），
    * 避免每 tick 刷屏。 */
  val StarvedRounds: Int = 2

  /** mount-stalled 停滞阈值（mount-enforce 批 20260905，作者裁定「节点一旦挂载必须
    * 一定生效」）：可触发点后 60s 仍未触发（仍 pending/wiring 且无 running/wiring
    * 上游）→ mount-stalled 事件留痕（含节点 id+等待原因）+ settleSweep 既有资格回扫
    * 接管补触发。双保险的时间维度信号（轮次维度由 trigger-starved 承担）。 */
  val MountStalledMs: Long = 60_000L

  /** 死会话自动收敛的 spawn 窗口宽限（僵尸收敛批 2026-09-06）：节点 status 翻
    * Running 后，agent registry 登记发生在 spawn 之后（runWithAgent :816）——Flipped
    * 瞬到 registry 登记之间存在微小窗口。自动对账只在「已过该宽限仍未登记 agent」
    * 时才判定死会话，避免把刚启动（fiber 活着、agent 即将登记）的节点误判收殓。
    * 宽限取 60s（agent spawn + plugin MCP acquire 量级远小于此）。 */
  val StaleSpawnGraceMs: Long = 60_000L

  /** startNode 翻转竞发的败方信号（trigger-chain-fix）：startNode 单点吞掉——
    * 败方安静退出，赢家持有节点生命周期（会话、cancelSig、终态分发）。 */
  final case class StartRaceLost(nodeId: String)
      extends RuntimeException(s"start race lost ($nodeId) — winner owns the session")

  // ── 投递可靠性批次常量（2026-09-04 四缺口）──────────────────

  /** 缺口2：补投/重投新鲜度阈值（24h，取 TtlDisplayMs 同口径——超过一个显示
    * 周期未被消费的欠账即「历史欠账」，合并汇总降噪；结果照投不丢）。 */
  val StaleRedeliveryMs: Long = TtlDisplayMs

  /** 缺口2：汇总通知每节点结果摘要截断长度。 */
  val StaleSummaryPerNodeChars: Int = 160

  /** 缺口3：测试夹具信封家族（宁窄勿宽——09-03 实证宿主真实数据唯一可确证
    * 夹具家族：cancel-test 取消语义验证节点，见 phd-notebook 归档 cancel-test、
    * cancel-test-3..11；精确锚定全名，真实节点名巧合含 test 不命中）。 */
  val FixtureFamilyRegex = "^(cancel-test|cancel-test-\\d+)$".r

  /** 缺口3：夹具载荷自证标记（09-03 实证夹具任务正文均带此前缀）。双条件
    * 缺一不可：名字 ∧ 载荷双确认才排除——真实节点名巧合命中家族但载荷是
    * 真实工作 → 照常投递。 */
  val FixtureTaskMarker = "取消验证节点"

  /** 缺口3：夹具信封判定（纯函数便于单测）。 */
  def isFixtureEnvelope(node: NodeDef): Boolean =
    FixtureFamilyRegex.matches(node.name) && node.task.exists(_.contains(FixtureTaskMarker))

  /** 缺口4：同 (identity, status) 重复通知去重窗口（60s——秒级抖动口径：挂载
    * 扫描与周期扫描竞态、ProjectCreate 合并回报三连投实证 09-03）。进程内
    * 内存窗，与 V8 nebulaDeliveredAt 持久账本正交（账本管跨重启 at-least-once）。 */
  val NebulaDedupWindowMs: Long = 60_000L

  /** 分发器最终输出投递的 source 标记（2026-09-05 接线）：ImmediateInput source
    * 值。前端 injectedSourceLabel 通用分支对未知 source 首字母大写 → 蓝气泡
    * "Dispatcher · <项目名> · Completed"（与 NODE/MAIL 同族，零前端改动）。 */
  val DispatcherSourceMarker = "dispatcher"

  /** 分发器投递任务摘要截断长度（触发任务首行、单行空白折叠，超出截断加省略号）。 */
  val DispatcherTaskSummaryChars: Int = 100

  /** 阶段 2b Plugins：spawn 前解析完成的分配物（§B.4 第 4 步）。
    * empty = flag 关 / 节点无分配 / 无可注入内容——旧行为零变化。 */
  final case class PluginPreparation(
    /** <injected-plugins> 全文块（逐 plugin 逐 skill，frontmatter 已去除 +
      * ${SKILL_DIR} 已替换）；"" = 无注入。 */
    injectedBlock: String,
    /** 含 MCP server 的 plugin（acquire 输入；serverId = plugin_<p>_<s>）。 */
    mcpPlugins: List[PluginRegistry.PluginDef],
    /** org.nebflow/tools 授予的 builtin 工具名（白名单已在装载层校验）。 */
    builtinTools: List[String]
  )
  object PluginPreparation:
    val empty: PluginPreparation = PluginPreparation("", Nil, Nil)

  // ── NodeMessage（20260905 机制批，作者裁定六条语义）──────────────────

  /** 裁定②：running 注入文本的可识别前缀——与用户任务文本、节点结果投递明确
    * 区分（分发器 NodeMessage + 节点名 + 时间戳来源标注）。 */
  val NodeMessagePrefix: String = "[NODE-MESSAGE]"

  /** 裁定⑤：消息留痕事件类型（FlowMapEventLog append-only JSONL）。 */
  val NodeMessageEventType: String = "node-message"

  /** running 注入文本头（前缀 + 来源标注单点）。 */
  def nodeMessageHeader(nodeName: String): String = {
    val ts = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    s"$NodeMessagePrefix 来源：任务分发器 NodeMessage · 节点 $nodeName · $ts"
  }

  /** 裁定③：任务追加分节头（NodeEdit release note 先例同款形态；裁定原文
    * 「== 分发器补充（NodeMessage <时间戳>） ==」）。undelivered=true 时追加
    * 「注入未达」标注（裁定②竞态兜底——留痕不丢）。 */
  def nodeMessageSection(undelivered: Boolean): String = {
    val ts = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    val base = s"== 分发器补充（NodeMessage $ts） =="
    if undelivered then base + "\n（注入未达：会话在消息投递前已终结，仅记录不注入——留痕不丢）" else base
  }

  /** 节点 id 前缀（sessionId = "node-<uuid>"）。 */
  val SessionPrefix = "node-"

  // ── 未申报提醒阶梯常量（noderpt 批 A 段 2026-09-11 作者裁定）──────────────────

  /** 提醒轮注入文本的**专用前缀头**（文案首行；agent 与取证双面可识别——与
    * `[NODE-MESSAGE]`（分发器 NodeMessage 通道）区分：本头 = 引擎未申报兜底）。 */
  val NodeReportReminderPrefix: String = "[NODE-REPORT-REMINDER]"

  /** 未申报留痕事件类型（FlowMapEventLog）：阶梯期每拍一条 + quiescent 档周期一条。 */
  val NodeReportMissingEventType: String = "node-report-missing"

  /** 死会话 bg-wait 豁免留痕事件类型（代裁 6：把「不算死」的节点标进事件供人监督）。 */
  val DeadSessionBgWaitEventType: String = "dead-session-bg-wait"

  /** 提醒轮注入的 ExternalEvent source 标签（agent 侧 `<system-reminder>` 头内标识；
    * 不进可见气泡白名单 = 不给前端造「用户气泡」，提醒面靠事件 + transcript）。 */
  val NodeReportReminderSource: String = "node-report-reminder"

  /** 事件 `stage` 取值：阶梯期（有注入） / quiescent（只留痕不注入）。 */
  val NodeReportStageActive: String = "active"
  val NodeReportStageQuiescent: String = "quiescent"

  // ── 释放唤醒常量（noderpt 批 F2，2026-09-11 复核 D2 修复）──────────────────────
  //
  // 与提醒阶梯同族但**不同语义**：提醒 = 「你没申报」（注入提醒轮 + 事件）；
  // 释放唤醒 = 「你申报了，但桥没观测到那一轮」（唤醒桥复检放行 + 事件，不提醒）。
  // 前缀头/ source / 事件类型三者单点区分，取证侧不必猜是哪条腿。

  /** 释放唤醒注入文本的专用前缀头（首行；与 `[NODE-REPORT-REMINDER]` 区分）。 */
  val NodeReportReleaseWakePrefix: String = "[NODE-REPORT-RELEASE-WAKE]"

  /** 释放唤醒注入的 ExternalEvent source 标签。 */
  val NodeReportReleaseWakeSource: String = "node-report-release-wake"

  /** 释放唤醒留痕事件类型（FlowMapEventLog）：每次唤醒一条（单发记账见 `releaseWakeSent`）。 */
  val NodeReportReleaseWakeEventType: String = "node-report-release-wake"

  /** 释放唤醒注入文案（并非「你没申报」的提醒——申报**已到**，缺的只是桥的观测）：
    * 要求 agent 用本轮回复给出节点最终结果文本（重述即可，无需再做工作），该轮回复经桥
    * 复检后作为节点结果投递。**不含判罚/威胁措辞**（与提醒文案同纪律：本族永不判 failed）。 */
  def reportReleaseWakeText(nodeName: String): String =
    s"""$NodeReportReleaseWakePrefix 节点 $nodeName 的 node_report 申报已收到，但完成观察桥未观测到产生该申报的那一轮
       |（NodeMessage / 即时注入轮的收尾不产生完成事件，引擎据此补一次唤醒）。
       |请直接用本轮回复给出该节点的**最终结果文本**——已无补充时重述上一轮的最终结论即可；
       |无需再做任何工具调用或额外工作，本轮回复将作为节点结果放行并投递下游。
       |本次唤醒不判罚失败、不终止会话、不杀进程。""".stripMargin

  /** 释放唤醒留痕 summary（`k=v` 单空格分隔，与 [[reportMissingSummary]] 同构，可被
    * [[parseReportMissingSummary]] 解析）：pendingSince（未申报起点）· delivered（注入是否达）。 */
  def reportReleaseWakeSummary(sessionId: String, pendingSince: Long, delivered: Boolean): String =
    s"node_report declaration present but the completion bridge never observed the declaring turn — " +
      s"release wake injected: stage=release-wake session=$sessionId pendingSince=$pendingSince delivered=$delivered"

  // ── 终态延迟销毁窗口常量（noderpt 批 B 段 2026-09-11 作者裁定：一律存活 30 分钟再销毁）──

  /** 终态销毁登记事件（写点 = 终态时刻的 `scheduleDestroy`；窗口开启的唯一痕迹——
    * failed/cancelled 与 completed/blocked 口径一致，挂起腿不写）。 */
  val DestroyScheduledEventType: String = "node-destroy-scheduled"

  /** 终态销毁完成事件（写点 = `sweepDestroyWindows` 到点回收；summary 含 destroyAt /
    * 会话清单 / 被收殓任务数，事后可与 `node-destroy-scheduled` 对齐「窗口是否走完」）。 */
  val DestroyedEventType: String = "node-destroyed"

  /** 窗口撤销事件（写点 = `sweepDestroyWindows` 发现「带 destroyAt 的节点已非终态」=
    * 窗口内被 reactivate/重跑 ⇒ 撤销窗口、解除禁 spawn；正常路径在翻转点即已清零，
    * 本事件只覆盖异常残留）。 */
  val DestroyWithdrawnEventType: String = "node-destroy-withdrawn"

  /** 档位人话标签（阈值 ms → "10min"/"1h"/"10s"）——提醒文案与事件共用单点。 */
  def reportRungLabel(ms: Long): String =
    if ms > 0 && ms % 3600000L == 0 then s"${ms / 3600000L}h"
    else if ms > 0 && ms % 60000L == 0 then s"${ms / 60000L}min"
    else s"${ms / 1000L}s"

  /** 提醒轮注入文案（作者裁定逐字要素：「你已交棒但未调用 node_report；请立即申报
    * pass/fail/blocked」+ 当前档位）。**不含任何判罚/威胁措辞**——本阶梯永不判 failed
    * （相对设计稿 §4.2 的核心改判），文案不得暗示「否则会失败」。
    * 实际投递文本 = 本文案**原文**（首行前缀头 `[NODE-REPORT-REMINDER]`），经
    * `AgentCommand.ExternalEvent(source = NodeReportReminderSource, eventType = "reminder")`
    * 进节点会话唤醒轮——不叠 `[NODE-MESSAGE]` 头（那条是分发器 NodeMessage 通道专用，
    * 前缀头单点区分来源；注入通道差异见 [[injectReminderTurn]]）。 */
  def reportReminderText(nodeName: String, rung: Int, maxRungs: Int, rungMs: Long, elapsedMs: Long): String =
    s"""$NodeReportReminderPrefix 你已交棒但未调用 node_report；请立即申报 pass/fail/blocked。
       |（引擎未申报兜底 · 第 $rung/$maxRungs 拍 · 档位 ${reportRungLabel(rungMs)} · 已等待 ${elapsedMs / 1000}s · 节点 $nodeName）
       |你的 turn 已结束但引擎没有收到终态申报，节点因此保持 Running、结果未投递下游。请调用 node_report 申报 pass / fail / blocked（附 detail），随后照常输出收尾报告。
       |本提醒不判罚失败、不终止会话、不杀进程——只是提醒；你不申报则该节点一直保持 Running 等人工处置。""".stripMargin

  /** `node-report-missing` 的结构化 summary（`k=v` 单空格分隔，含 `=` 的 token 由
    * [[parseReportMissingSummary]] 单点解析——`FlowMapEventLog.append` 的顶层只有
    * ts/type/project/nodeId/summary 五字段，扩展字段一律进 summary，与
    * `FlowMapEventLog.chainArchivedSummary` 先例同构）。字段清单：
    *   stage（active|quiescent）· rung（第几拍，形如 `3/8`）· rungMs（该拍阈值）
    *   · elapsedMs（已等待）· pendingSince（= reportPendingSince）· reminderCount
    *   · ladderExhausted（末拍/之后为 true）· delivered（仅阶梯期有：提醒轮是否投递达）。 */
  def reportMissingSummary(
    stage: String,
    rung: Int,
    maxRungs: Int,
    rungMs: Long,
    elapsedMs: Long,
    pendingSince: Long,
    reminderCount: Int,
    ladderExhausted: Boolean,
    delivered: Option[Boolean]
  ): String =
    val base = List(
      s"stage=$stage",
      s"rung=$rung/$maxRungs",
      s"rungMs=$rungMs",
      s"elapsedMs=$elapsedMs",
      s"pendingSince=$pendingSince",
      s"reminderCount=$reminderCount",
      s"ladderExhausted=$ladderExhausted"
    ) ++ delivered.map(d => s"delivered=$d").toList
    s"node finished without a node_report declaration — ${base.mkString(" ")}"

  /** summary 解析（结构化字段单点，消费者免手工切分；非 `k=v` token 忽略）。 */
  def parseReportMissingSummary(summary: String): Map[String, String] =
    summary.split("\\s+").iterator
      .filter(t => t.indexOf('=') > 0)
      .map { t =>
        val i = t.indexOf('=')
        t.substring(0, i) -> t.substring(i + 1)
      }
      .toMap


  // ── P2（stuck 自动恢复批）：runtime 定位键修正（硬依赖 2，正交实验已证**正**）──
  //
  // 事故形态：`rts.find(rt => rt.engine.rootSessionId == rec.rootSessionId)` 在
  // **多项目挂载**下必然歧义——`ProjectActor.mountAll` 把**同一个顶层 rootSessionId**
  // 交给全部项目（`GatewayMain` 单个 `rootSid` → `mountAll` → `mount` →
  // `NodeEngine(rootSessionId, …)`），`ProjectRuntimeRegistry.all` 是 Map 无序
  // （`runtimes.get.map(_.values.toList)`）⇒ 16 个同 id 候选中任取其一。
  //
  // 实证（宿主日志，2026-09-11 06:58 启动 16 项目同一 rootSessionId
  // `5cc7590a-…`）：`no node owns session`（**引擎内**节点查无，NodeEngine:436）12 次，
  // 而 watcher 层「no project runtime for …」（`rts.find` 整体落空）**0 次** ⇒
  // `find` 总是命中、命中的却总是**错的引擎**（15/16 概率），节点在别的项目 store 里。
  //
  // ⇒ 项目定位键 = **会话在哪个 store 里**，不是 rootSessionId。`sessionRef`
  // （node-/dispatcher- 会话 id）在任一 store 内唯一，故按 sessionRef 扫 store 即
  // 精确唯一解。

  /** 按 `sessionRef` / `sessionRefVerify` 在 store 快照里定位节点（P2 定位键唯一入口）。
    * 全部消费点（watcher 的 resume / L3 复查 / 失败兜底）共用本函数——**禁止**再用
    * `rootSessionId` 反查项目（见上方硬依赖 2 说明）。 */
  def findNodeForSession(snap: FlowMapState, sessionId: String): Option[NodeDef] =
    snap.nodes.values.find(n =>
      n.sessionRef.contains(sessionId) || n.sessionRefVerify.contains(sessionId))

  // ── P3：LoopGuard 互斥（§3.4 互斥点 1）与 R-2 的三个纯函数 ────────────────

  /** 互斥点 1(a) 的判据（P3，设计 §3.4；纯函数，可独立单测）：会话仍处于冻结
    * （`status==Frozen` 或 `frozenReason` 有值）⇒ 恢复必须**拒绝执行**——唤醒冻结
    * 会话意味着走「用户输入」那条会 `resetCrossTurn` 的出口，等于绕过 LoopGuard 的
    * 365 天冻结。`None`（会话已不在 registry）⇒ 不阻止（旧会话已被清场，resume 起
    * 的是全新会话，天然不进 frozen behavior）。 */
  def frozenSessionBlocksResume(live: Option[AgentRecord]): Boolean =
    live.exists(r => r.status == AgentStatus.Frozen || r.frozenReason.isDefined)

  /** 互斥点 1(b) 的判据（P3，设计 §3.4；纯函数，可独立单测）：**会话级原语**
    * （`LlmInterface.transportAbortFor` / `BgTaskRegistry.reclaimSession`，即 L2 腿）
    * 只在会话 `status == Processing` 时允许执行。
    *
    * 为什么必须**显式**（今日本就隐含——watcher 只扫 Processing）：会话级原语会波及
    * 同根会话的其它状态（例如被 LoopGuard 冻结的会话仍持有同根身份），一旦将来放宽
    * 扫描面（例如把 Frozen 纳入候选），这条约束就会**静默**失效。显式化 ⇒ 未来放宽
    * 扫描面时本闸门仍然发声（与 §2.3 三条误报原则同款纪律）。
    *
    * 守卫定义在引擎侧（本函数）、**调用点在 `TaskStuckWatcher`**——与「不改
    * `BgTaskRegistry`」的文件面纪律一致（`reclaimSession` 本体零改动）。 */
  def sessionLevelPrimitiveAllowed(rec: AgentRecord): Boolean =
    rec.status == AgentStatus.Processing

  /** R-2：transcript 重放封顶（默认 [[nebflow.shared.Defaults.StuckRecoveryReplayMaxMsgs]]
    * = 40 条）。保留**最近** `max` 条（越近越相关），并丢掉截断产生的**悬空
    * tool_result 头**（其配对的 assistant `tool_use` 已被截掉——provider 侧会因
    * 「tool_result 无对应 tool_use」拒绝请求，故必须一起丢掉）。
    *
    * 纯函数（零 IO、零 LLM）：边界可独立单测（≤max / 超限 / 全悬空 / 空输入）。
    * 返回 `(封顶后的消息, 是否发生了截断)`；第二个值驱动 [[replayCapNote]]。 */
  def capReplayMessages(msgs: List[Message], max: Int): (List[Message], Boolean) =
    val n = math.max(1, max)
    if msgs.size <= n then (msgs, false)
    else
      val tail = msgs.takeRight(n)
      def orphanedToolResult(m: Message): Boolean = m.content match
        case Right(blocks) =>
          blocks.nonEmpty && blocks.forall(_.isInstanceOf[nebflow.shared.ContentBlock.ToolResult])
        case Left(_) => false
      val trimmed = tail.dropWhile(orphanedToolResult)
      (if trimmed.isEmpty then tail else trimmed, true)

  /** 截断事实的 resume prompt 声明（不可省略：不告知 ⇒ 模型会假定上下文完整）。
    * 与 §3.3「超限则只带最近 40 条 + 摘要」同口径（摘要由模型自行按需读取工作区）。 */
  def replayCapNote(total: Int, kept: Int): String =
    s"\n（transcript 重放封顶：磁盘上有 $total 条消息，本次只重放最近 $kept 条——" +
      "更早的上下文已省略；若需要早期细节，请自行读取工作区文件 / 项目文档。）"

  /** P2「挂起不终态化」哨兵（R-1=B）——见 [[NodeEngine.suspendNode]] 与
    * `runWithAgent` 的挂起分支。走既有 `AgentEvent.Cancelled` 载体（**不改跨模块消息
    * 形态**，与 `CancelSource` 从 reason 文本前缀推导同款纪律），由引擎侧识别前缀后
    * 走「只停 actor、不终态化」的挂起腿。 */
  val SuspendReasonPrefix = "(node-suspend"

  def isSuspendOutcome(msg: String): Boolean = msg.contains(SuspendReasonPrefix)

  /** R-5 / 类⑦：`bg-harvest` 事件的 **cause** 词表（从同一份桥消息文本单点派生——
    * 不改 `AgentEvent` 形态，与 `CancelSource` 同纪律）。
    *
    * 该事件此前**无原因**：读者只能看到「node finalized (cancelled) — reclaimed bg
    * session」，看不出是引擎看门狗取消、bg 等待上限、会话猝死还是本批新增的挂起腿
    * （本板 #98 的两条诉求「为什么被 cancelled / 是否通知过」的第一个盲区即此）。 */
  def finalizeCause(msg: String): String =
    if isSuspendOutcome(msg) then "suspended-for-recovery"
    else if msg.contains("cancelled") then "cancelled"
    else if msg.contains("wait cap") then "bg-wait-cap"
    else if msg.contains("terminated without a terminal event") then "session-died"
    else "failed"

  // ── P2：恢复锚探测结果（设计 §3.1）──────────────────────────────────────

  /** 恢复锚探测结果（§3.1 的 A1/A3 + §3.2 的零产出/有产出判据）。
    *
    * A1（transcript）在主锚位：由调用方（watcher）经 `SessionStore.loadMessagesForSession`
    * 探测（引擎侧同一读点，二者同源）——不可用 ⇒ **不恢复、直接一次上报**。
    * A3（worktree/git）是产物面旁证：目录缺失 ⇒ 该锚不可用（#159 形态），
    * 降级为「A1 可用则续跑」。 */
  final case class RecoveryAnchor(
    /** A3 可用 = worktree 目录存在 ∧ 是 git 工作树（`.git` 条目存在）。 */
    worktreeAvailable: Boolean,
    /** 探测用的工作目录（worktree 优先、回落工作区）——同时是 hasOutput 探针的 cwd。 */
    probeDir: String,
    /** §3.2 的 `P1 ∨ P2`：有产出 ⇒ 断点续跑；零产出 ⇒ 重启 turn。 */
    hasOutput: Boolean,
    /** 可读的产物证据文本（进 resume prompt 与上报文本；**不回流判据**）。 */
    outputEvidence: String
  )

  /** §3.2 的 resume 分支告知（零产出 vs 有产出），拼在既有 `nodeResumePrompt` 之后。
    *
    * ⚠ **与设计 §3.2 口径的差异（单列，未证项）**：§3.2 的 P1 逐字为
    * `git rev-list --count <baseline>..HEAD`，但 `NodeDef` **不持久化任何 baseline
    * sha**（本仓无该字段）⇒ 无 `baseline` 可用。本实现取可得的只读等价物：
    * `git rev-list --count HEAD --since=<本节点 startedAt>`（「本代次新提交数」）
    * ∨ `git status --porcelain` 非空（未提交产出）。语义一致（都是「本代次是否
    * 改变了工作产物」），但**不是逐字公式**——须由验收/后续批知悉。 */
  def stuckResumeNote(anchor: Option[RecoveryAnchor]): String =
    anchor match
      case Some(a) if !a.hasOutput =>
        "\n（hard-recovery：该节点会话此前卡死，已被引擎分级接管中断并从磁盘断点恢复续跑。" +
          s"**上次尝试零产出**（${a.outputEvidence}；worktree " +
          s"${if a.worktreeAvailable then s"可用：${a.probeDir}" else "不可用（目录缺失/非 git 工作树）"}）" +
          "——请从头完成本节点任务，不要假定任何工作已完成。）"
      case Some(a) =>
        "\n（hard-recovery：该节点会话此前卡死，已被引擎分级接管中断并从磁盘断点恢复续跑。" +
          s"**已有产出**（${a.outputEvidence}）——请先核验上一轮未落盘的副作用（git 状态、关键文件），" +
          "已完成的工作无需重复，从中断点继续。）"
      case None =>
        "\n（hard-recovery：该节点会话此前卡死，已被引擎分级接管中断并从磁盘断点恢复续跑。）"

  /** 节点级 blocked 重入上限（设计 §3.1/§7.2）：blockCount 1/2 → 重入调整；
    * count=3（> 2）→ 升级 Nebula 不再重入。 */
  val MaxBlockRoundsPerNode: Int = 2

  /** 节点终态语义申报协议脚注（buildInput 末尾单点注入）。TaskBoard 批 2（§3c）：
    * 末尾增一行上报指引——与终态语义协议同点注入、同生命周期；措辞自带条件
    * （「若…给了」），无板会话注入该行无副作用。
    * blocked 结构化信号批（20260909 spec §5.2 #7）：第一优先 = 调用申报工具；
    * 同日作者裁定泛化（NodeReport 统一三语义）：工具更名 node_report，blocked
    * 细分六类之外 pass/fail 语义同走结构化申报；三语义文本锚定（首行裸 BLOCKED
    * / VERDICT: PASS/FAIL）降级为「工具不可用时」备用通道，措辞强调首行裸形态
    * 要求（6 例实证：markdown 标题/加粗/前置导语均锚定失败）。 */
  val ProtocolFootnote: String =
    """── 节点协议 ──
      |若你判定任务无法完成（上游依赖未就绪/任务定义不完整/能力不匹配/缺外部条件），
      |不要硬造结果：第一优先调用 node_report 工具申报（参数 category/detail/
      |suggestion；受阻申报 category ∈ upstream-incomplete | task-underspecified |
      |agent-mismatch | external-dependency | needs-split | other | blocked），随后照常输出
      |收尾报告。任务/轮次「已完成/已失败」的正式判定同样走 node_report：category
      |传 pass 或 fail（detail 写明依据）。工具不可用时才用文本备用通道：把最终输出的第一行写为裸 BLOCKED
      |（首行恰为 BLOCKED 四个字母——不加 # / ** / 导语等任何前缀），随后给出 JSON：
      |{"category":"…","detail":"…","suggestion":"…"}。
      |可完成时正常输出结果，勿申报 blocked。
      |若上方 <task-board> 给了你工单编号，完成或受阻时用 TaskBoard 工具更新其状态（close=完成，blocked=受阻）。""".stripMargin

  // ── 链级抽象 P2：链上下文透传（20260910_process-doc-chain-attribution-spec §9.2）──

  /** 节点所属链的 spawn 时刻快照（§9.2 项 4/5/7）：chainId = `chain-<分量最早
    * createdAt 节点 id>`，title = 链名三级推导单点（FlowMapStore.chainTitle），
    * memberCount = 分量成员数。三字段同源同刻——首条消息链头与会话身份
    * （ToolContext.flowChainId）由同一快照喂，两处口径恒同。 */
  final case class NodeChainContext(chainId: String, title: String, memberCount: Int)

  /** 首条消息链头（§9.2 项 7 原文字面）：`[chain: <title> (<chainId>) · N 节点]`
    * ——节点执行会话内唯一能拿到「我属于哪条链」的文本面（节点无 NodeList，
    * registry.scala:70 口径）。单行、无换行尾随。 */
  def chainHeaderLine(c: NodeChainContext): String =
    s"[chain: ${c.title} (${c.chainId}) · ${c.memberCount} 节点]"

  /** 过程文档命名·溯源提示段（R-3，2026-09-11 作者裁定；替代原「过程文档元数据头
    * 模板」——本批 §9.2 项 8 注入面）——**不再教写正文元数据头**：旧模板体（可复制
    * YAML front matter + 键白名单）正是正文元数据头的诱导源，整体作废。
    * 权威条文（与 `CONVENTIONS.md` 逐字同源）：① 正文零元数据头（`chain`/`chains`/
    * `chain-source`/`chain-role`/`produced-by`/`produced-at`/`doc-class`/`root` 一律
    * 不写进正文）；② 溯源只进文件名尾段——阶段文档
    * `<YYYYMMDD>_<HHMMSS>_<topic>__<chainId>.md`、多链 `__<主链Id>+<次链Id>.md`；
    * ③ 无归属不带尾段（不得编造），活文档（无日期前缀）同规则（有归属才带尾段）；
    * ④ 与既有 `-<n>` 同秒消歧并存（唯一性消歧 ≠ 已禁的版本副本 `-vN`）；⑤ 存量冻结
    * （零改名、零搬移、零回改）。
    * 段体 = 可复制的四种文件名形态 + 三条纪律，**零 `---` 行、零键白名单**（旧模板的
    * 诱导源就在这两处）；链 id 值取自紧随其上的链头行（`chainHeaderLine`）。
    * 旧「元数据头上限 = ≤8 键 / ≤10 行」（2026-09-10 作者裁定）随正文头停写退为**存量
    * 读取侧历史口径**（`index-backfill.py` 的 legacy 元数据头读取路径仍按该口径容错）。
    * 只在有链时注入（紧随链头）——无链会话无归属对象，注入只会诱导编造 chain id
    * （裁定理由同原交付报告）。 */
  val DocProvenanceBlock: String =
    """[过程文档命名·溯源（正文零元数据头；链归属只进文件名尾段 `__<chainId>`；有归属时复制改值：]
      |# 正文零元数据头：chain/chains/chain-source/chain-role/produced-by/produced-at/doc-class/root 一律不写进正文
      |20260911_082530_<topic>__<chainId>.md          # 阶段文档 = <YYYYMMDD>_<HHMMSS>_<topic>__<chainId>
      |<topic>__<chainId>.md                          # 活文档（无日期前缀）同规则：有归属才带尾段
      |20260911_082530_<topic>.md                     # 无归属不带尾段（不得编造）
      |20260911_082530_<topic>__<主链Id>+<次链Id>.md  # 多链：__<主链Id>+<次链Id>
      |# chainId 取自上方链头行；时间取与内容同刻的秒级本地时间；同秒重名加 -<n> 消歧；存量文档零改名/零搬移/零回改""".stripMargin

  // ── LoopNode（LoopNode 批 2026-09-06，主设计 §2.2/§2.3）──────────────────

  /** loop 运行态阶段值（NodeDef.loopPhase；loopRound 配套）。 */
  val LoopPhaseWorker: String = "worker"
  val LoopPhaseVerify: String = "verify"

  // ── boot-time 崩溃恢复（crash-recovery 批 2026-09-07）──────────────────

  /** 恢复认领事件类型（FlowMapEventLog append-only JSONL，「禁止静默自愈」纪律——
    * 每个认领/处置动作一条，summary 含三分类与 transcript 指针）。 */
  val BootRecoveryEventType: String = "boot-recovery"

  /** 崩溃恢复续跑上下文（D3）：spawnAndRun/runWithAgent/spawnAndRunLoop 全链的
    * resume 增量——Some 时跳过 buildInput、复用旧会话 id（D2：transcript 单文件
    * 续写 + F2 队列重放白捡）+ initialMessages 水合，其余装配逐行复用（R8：禁手写
    * 独立 spawn 链）。普通节点仅主会话三字段；loop 节点（裁定③）另带 verify 双会话
    * 与崩溃时 loopRound/loopPhase 断点。 */
  final case class ResumeContext(
    /** 复用的旧会话 id（普通节点唯一会话；loop = worker 主会话）。 */
    sessionId: String,
    /** 主会话水合消息（磁盘 transcript，最后持久 tool round 边界——§1.1 诚实语义：
      * 工具执行中/LLM 调用中/2s debounce 窗内崩溃的轮次回退重放）。 */
    recoveredMessages: List[Message],
    /** 首轮输入 = resume prompt（BackoffSupervisor continue-prompt 直系演化）。 */
    resumePrompt: String,
    /** loop verify 会话 id（仅 loop 节点；None = 普通/无旧 ref 新起）。 */
    verifySessionId: Option[String] = None,
    /** verify 会话水合消息（loop 节点）。 */
    verifyMessages: List[Message] = Nil,
    /** loop 崩溃时持久轮次断点（≥1；0/无值按 1 起）。 */
    loopResumeRound: Int = 0,
    /** loop 崩溃时相位（worker/verify；无值按 worker）。 */
    loopResumePhase: Option[String] = None
  )

  /** 普通节点 resume prompt（§3.3 模板；磁盘上 (a)/(b) 不可区分——副作用核验告知
    * 恒带（裁定②诚实语义：mid-turn 重放=从上一持久轮重跑，已完成的工作无需重复，
    * 未确认的副作用先核验）。bgWaitNote = 认领时清空的等待集快照（G4 死亡告知）。 */
  def nodeResumePrompt(projectName: String, node: NodeDef, recoveredCount: Int, bgWaitNote: Option[String]): String =
    val bgSection = bgWaitNote match
      case Some(w) => s"\n崩溃前等待中的后台任务已死亡（$w）——等待集已随进程消失，其结果文件若在盘上请自行核验，按需重启该后台工作。\n"
      case None => ""
    s"""[system] 进程在你上一轮工作期间崩溃并已重启。你的会话历史已从磁盘恢复
       |（共恢复 ${recoveredCount} 条消息，断点 = 最后一个已持久化的工具轮边界）。请继续完成节点任务「${node.name}」。
       |注意：上一轮工具调用可能未完成即中断——请先核验关键副作用（git 状态、关键文件）再继续，已完成的工作无需重复。
       |$bgSection
       |任务全文（原始要求）：NodeList(detail="${node.id}", project="$projectName") 可读；本 prompt 只负责续跑告知。""".stripMargin

  /** loop worker 相位 resume prompt（裁定③：worker→verify 迭代跨崩溃续接——本轮
    * 产出从最后持久轮边界续作，verify 照常裁决本轮）。 */
  def loopWorkerResumePrompt(node: NodeDef, round: Int, phase: String, recoveredCount: Int): String =
    s"""[system] 进程在 LoopNode 第 $round 轮（$phase 阶段）执行期间崩溃并已重启。你的会话历史已从磁盘恢复
       |（共恢复 ${recoveredCount} 条消息，断点 = 最后一个已持久化的工具轮边界）。请继续完成本轮产出——
       |从断点继续工作，上一轮工具调用可能未完成即中断，请先核验关键副作用（git 状态、关键文件）再继续，已完成的工作无需重复；
       |完成后给出本轮最终产出（后续由验证会话裁决）。
       |原始任务全文：NodeList(detail="${node.id}") 可读；本 prompt 只负责续跑告知。""".stripMargin

  /** loop verify 相位续跑标注（verify 输入由 worker transcript 末条 assistant 文本
    * 重建——旧输入可能未持久/已消费，重注入是操作侧消息）。 */
  def loopVerifyResumeAnnotation(round: Int): String =
    s"[system] 进程在 LoopNode 第 $round 轮（verify 阶段）执行期间崩溃并已重启，verify 会话历史已恢复；" +
      s"下方为重建的本轮验证输入（worker 本轮产出），请按验证协议对本轮给出 verdict。"

  // ── LoopNode 模板（续）────────────────────────────────────────

  /** verify 文法脚注（主设计 §2.3 原文单点注入，模板三末尾）——与 ProtocolFootnote
    * 同机制，覆盖所有 verify 会话，防「verify 意图 FAIL 但忘写锚定行」被降级放行。 */
  val VerifyVerdictFootnote: String =
    """── 验证协议 ──
      |你的最终输出第一行必须是且只能是：VERDICT: PASS 或 VERDICT: FAIL（大写，冒号后半角）。
      |判 FAIL 时随后给出 JSON：{"issues":["问题1","问题2"],"requirements":"通过标准"}
      |issues 必须具体到修改点；可 PASS 时第一行写 VERDICT: PASS，不要附加其他内容。""".stripMargin

  /** verify 清单默认模板（loopSpec.verifyTask 为空时兜底注入）。 */
  val VerifyDefaultTask: String =
    "按原始任务与验收基准逐条核对 worker 产出，判断是否达到通过标准；" +
      "指出必须修改的具体问题（issues 逐条列出），达到标准则判 PASS。"

  /** worker 返工模板二（第 N≥2 轮同会话注入；产出全文/历史不重复注入——持久会话
    * 上下文已持有，主设计 §2.2 模板二）。 */
  def loopReworkInput(round: Int, issues: List[String], requirements: String): String =
    val reqLine =
      if requirements.trim.nonEmpty then s"== 通过标准 ==\n$requirements" else ""
    val iss = if issues.nonEmpty then issues.map(i => s"- $i").mkString("\n") else "- （verify 未给出具体意见）"
    s"""【LoopNode 返工 · 第 $round 轮】
       |== 验证意见 ==
       |$iss
       |$reqLine
       |
       |${ProtocolFootnote}""".stripMargin

  /** verify 输入模板三（第 N 轮；首轮全文、第 N≥2 轮新段——持久会话已持有原始任务/
    * 上游段/验证清单/历轮产出，主设计 §2.2 模板三 + 裁定 B 注记）。 */
  def loopVerifyInput(
    round: Int,
    nodeTask: String,
    upstreamSection: String,
    workerOutput: String,
    verifyTask: String
  ): String =
    if round <= 1 then
      val up = if upstreamSection.nonEmpty then s"\n$upstreamSection" else ""
      s"""【LoopNode 验证 · 第 $round 轮】
         |== 原始任务（验收基准） ==
         |$nodeTask$up
         |== 待验证产出（worker 第 $round 轮） ==
         |$workerOutput
         |== 验证清单 ==
         |${if verifyTask.trim.nonEmpty then verifyTask else VerifyDefaultTask}
         |
         |${VerifyVerdictFootnote}""".stripMargin
    else
      s"""【LoopNode 验证 · 第 $round 轮】
         |== 待验证产出（worker 第 $round 轮） ==
         |$workerOutput""".stripMargin

/** blocked 声明解析器（设计 §1.3 文法）。独立 object 便于单测。
  *
  * - 锚定：trim 后以 BLOCKED 开头（后跟 `:` 或换行/空白/串尾）——只查开头，正文含词不误判。
  * - JSON 体：首个 `{` 到末个 `}` 之间尝试 circe 解析；category 不在枚举 → other。
  * - JSON 缺失/畸形 → BlockedFeedback("other", 其余全文截断, "")。
  * - 非 BLOCKED 开头 → None（调用方走 completed 原路径无损降级）。 */
object BlockedReader:
  val Categories: Set[String] =
    Set("upstream-incomplete", "task-underspecified", "agent-mismatch", "external-dependency", "needs-split", "other")

  private val Marker = "BLOCKED"
  private val DetailCap = 500
  // 观测面上下文经济学批（20260907 裁定②）：JSON body 分支的 detail/suggestion
  // 此前原样入库（实测最大 detail 1,511ch，审计锚点 9）——与 fallback 分支
  // DetailCap 同域单点常量收口；300/150 配平载荷侧「仅 blocked 态携带」后的预算
  // （NodePayload.buildNodeJson 单点序列化同步门控）。
  private val JsonDetailCap = 300
  private val JsonSuggestionCap = 150

  /** 解析节点最终输出：命中 blocked → Some(BlockedFeedback)；否则 None。 */
  def parse(resultText: String): Option[BlockedFeedback] =
    val trimmed = resultText.trim
    if !anchored(trimmed) then None
    else
      jsonBody(trimmed) match
        case Some(body) =>
          io.circe.parser.parse(body) match
            case Right(json) =>
              val cursor = json.hcursor
              val category = cursor.get[String]("category").toOption.filter(Categories.contains).getOrElse("other")
              val detail = capped(cursor.get[String]("detail").toOption.getOrElse(""), JsonDetailCap)
              val suggestion = capped(cursor.get[String]("suggestion").toOption.getOrElse(""), JsonSuggestionCap)
              Some(BlockedFeedback(category, detail, suggestion))
            case Left(_) => Some(fallback(trimmed))
        case None => Some(fallback(trimmed))

  /** 落库渲染串（设计 §1.3：result 字段人类可读形态，NodeList 摘要与详情窗共用）。 */
  def render(f: BlockedFeedback): String =
    s"[blocked:${f.category}] ${f.detail} — 建议: ${f.suggestion}"

  /** 锚定判定：BLOCKED 开头且后跟 `:` / 空白 / 串尾（"BLOCKEDxx" 不算）。 */
  private def anchored(trimmed: String): Boolean =
    trimmed.startsWith(Marker) && {
      val rest = trimmed.substring(Marker.length)
      rest.isEmpty || rest.head == ':' || rest.head.isWhitespace
    }

  /** 首个 `{` 到末个 `}` 之间（可嵌于说明文字之后）。 */
  private def jsonBody(trimmed: String): Option[String] =
    val i = trimmed.indexOf('{')
    val j = trimmed.lastIndexOf('}')
    if i >= 0 && j > i then Some(trimmed.substring(i, j + 1)) else None

  /** 截断 helper（尾部省略号标记；fallback 与 JSON 分支共用，封顶值单点常量）。 */
  private def capped(s: String, cap: Int): String =
    if s.length > cap then s.take(cap) + "…" else s

  /** JSON 缺失/畸形降级：去掉 BLOCKED 标记行后的其余全文截断为 detail。 */
  private def fallback(trimmed: String): BlockedFeedback =
    val rest = trimmed.replaceFirst("^BLOCKED\\s*:?\\s*", "").trim
    BlockedFeedback("other", capped(rest, DetailCap), "")
