package nebflow.core.project

import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.actor.*
import nebflow.agent.*
import nebflow.core.PathUtil
import nebflow.core.entity.EntityLoader
import nebflow.core.node.NodeRunner
import nebflow.core.plugin.{PluginMcpManager, PluginRegistry, PluginsConfig}
import nebflow.core.skill.SkillService
import nebflow.core.tools.BgTaskRegistry
import nebflow.shared.{Message, NebflowLogger}

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
  private[project] val system: ActorSystem,
  private[project] val resources: SharedResources,
  val wsSendFn: Json => IO[Unit],
  private[project] val workspace: String,
  val rootSessionId: String,
  /** 项目名：Node 完成通知蓝气泡 header 第二段（NODE · <项目名> · <节点名> · <状态>）。 */
  val projectName: String,
  /** blocked 反馈档位（设计 §7.1）：auto（默认）| escalate-only。挂载时读 project.json。 */
  val feedbackMode: String = FeedbackRouter.ModeAuto,
  /** WS 事件推送（type → payload 载体）。 */
  private[project] val emitEvent: (String, String, Json) => IO[Unit],
  /**
   * 产物完整性闸门的 git 执行器（audit 20260905）：默认真实 git 只读命令；
   * spec 注 stub。闸门本体与 kill-switch 见 CompletionGate。
   */
  private[project] val gateRunner: CompletionGate.GitRunner = CompletionGate.defaultRunner,
  /**
   * 节点完成闸等待总上限兜底（bgtask-completion-gate 批）：单个 bg 等待期超过
   * 此值 → 节点 failed+注明（防通知链断裂永久悬挂）。默认 Defaults.BgGateWaitTimeoutMs
   * （system prop nebflow.bgtask.gate.timeoutMs 可调）；spec 注小值验红。
   */
  private[project] val bgWaitCapMs: Long = nebflow.shared.Defaults.BgGateWaitTimeoutMs,
  /**
   * 项目任务板 store（TaskBoard 批 2 §3a）：节点 buildInput 头部板块数据源；
   * None = 该项目无板（节点注入整块省略）。mount 时传入。
   */
  val board: Option[TaskBoardStore] = None,
  /** 项目目标行来源（TaskBoard 批 2 §3b：ProjectDef.description；None 整行省略）。 */
  val projectGoal: Option[String] = None,
  /**
   * dispatch-notify 触发通道覆盖（取消静默死锁修复批 R1，测试接缝）：Some 时取代
   * 真实触发链（ProjectRuntimeRegistry → ProjectActor.TriggerDispatcher）——spec
   * 用它捕获**通知文本原文**断言「cancelled 专属处置指引」。生产调用点零传参
   * （默认 None = 真实链，零行为变化）。
   */
  notifyTriggerOverride: Option[String => IO[Unit]] = None,
  /**
   * 完成门腿 1 开关（noderpt 批 A 段 2026-09-11）：后台任务存活是否拦节点终态化。
   * None = 现读 `Defaults.BgGateCompletionHold`（生产默认 **false = 封存**：不查
   * `BgTaskRegistry.waitingFor`，hold 代码与 armCap 逐字保留、打开即恢复旧行为）；
   * Some = spec 显式注入（避开全局 prop 的跨 suite 污染，测试矩阵双态各一条）。
   */
  bgGateCompletionHold: Option[Boolean] = None,
  /**
   * 完成门腿 2 开关（noderpt 批 A 段）：未申报 `node_report` 是否拦节点终态化。
   * None = 现读 `Defaults.NodeReportCompletionHold`（生产默认 **true**：未申报的
   * `Completed` 不终态化、节点保持 Running，由未申报提醒阶梯兜底）；Some = spec 注入。
   */
  reportGateHold: Option[Boolean] = None,
  /**
   * 终态延迟销毁窗口（noderpt 批 B 段 2026-09-11 作者裁定「一律存活 30 分钟再销毁」）：
   * None = 现读 `Defaults.NodeDestroyWindowMs`（生产默认 30min）；Some = spec 注入
   * （压到 0/秒级，避开全局 prop 的跨 suite 污染——`bgGateCompletionHold` 同款接缝）。
   */
  destroyWindowMs: Option[Long] = None,
  /**
   * 静默/去抖窗口生效值（M4，b64 批 2026-09-13）：project.json 的 `notify.quietMs`
   * （键名为对外冻结命名），由挂载面经 `NotifyPolicy.parseQuietMs` 校验后注入
   * （缺键 = 缺省 5s；超限 ⇒ 挂载面 fail-fast，**不截断**）。None = 现读缺省值
   * （`NotifyPolicy.NotifyQuietMsDefaultMs`），spec 注入用于确定性断言。
   */
  notifyQuietMs: Option[Long] = None,
  /**
   * mount-stalled **告警升级**间隔覆盖（engine-defects 批 #85，2026-09-15；接缝形态
   * 与 `destroyWindowMs` / `notifyQuietMs` 同款）：None = 现读
   * [[nebflow.shared.Defaults.StallReNotifyMs]]（生产默认 10min）；Some = spec 显式
   * 注入毫秒级窗口做确定性断言（**避开全局 prop 的跨 suite 污染**——本工程测试 JVM 下
   * `sys.props.update` 与 `System.setProperty` **写入后同进程读回均为空**（实测
   * `Obtained: None` / `Obtained: null`）⇒ 用 prop 做 spec 注入口会**静默失效**，
   * 断言只会看到默认值；故走构造器接缝）。
   */
  stallReNotifyMs: Option[Long] = None,
  /**
   * root 通道通知打包窗生效值（notifybatch 批 2026-09-18）：None = 现读
   * [[nebflow.shared.Defaults.RootNotifyQuietMs]]（生产默认 5s）；Some = spec 显式注入
   * （`notifyQuietMs` / `destroyWindowMs` / `stallReNotifyMs` 同款接缝——本工程测试 JVM 下
   * `sys.props`/`System.setProperty` **写入后同进程读回为空** ⇒ 用 prop 做 spec 注入口会
   * 静默失效；`Some(0)` = 关窗，逐条等价旧行为）。
   */
  private[project] val rootNotifyQuietMs: Option[Long] = None,
  /**
   * root 通道打包条数上限生效值（同上接缝形态）：None = 现读
   * [[nebflow.shared.Defaults.RootNotifyBatchMax]]（生产默认 10）。
   */
  private[project] val rootNotifyBatchMax: Option[Int] = None
) extends NodeCanceller
    with NodeRecovery
    with NodeDelivery
    with NodeGating
    with NodeLoopRunner
    with NodeCompletion
    with NodeStarter:
  private[project] val logger = NebflowLogger.forName("nebflow.node.engine")

  /** 完成门腿 1 生效值（每次判定现读；无在线翻转路由 ⇒ 生产侧改 prop 需重启宿主）。 */
  private[project] def bgGateHoldEnabled: Boolean =
    bgGateCompletionHold.getOrElse(nebflow.shared.Defaults.BgGateCompletionHold)

  /** 完成门腿 2 生效值（同上，现读）。 */
  private[project] def reportGateHoldEnabled: Boolean =
    reportGateHold.getOrElse(nebflow.shared.Defaults.NodeReportCompletionHold)

  /** 终态延迟销毁窗口生效值（现读；spec 走构造入参注入）。 */
  private[project] def destroyWindowDurationMs: Long =
    destroyWindowMs.getOrElse(nebflow.shared.Defaults.NodeDestroyWindowMs)

  /**
   * blocked 反馈路由器（§2.2/§2.3）：档位决策 + 项目级频率保护 + 重入/升级执行。
   * escalate 通道 = 本引擎的 [[enqueueRootNotify]]（eventType="blocked"，前端 label 自动 BLOCKED）；
   * escalateFailed 通道 = 同型 [[enqueueRootNotify]]（eventType="failed"）——P2 RetryCap
   * 升级专用（spec §2.3 G12：failed 终态真实显示，不冒充 BLOCKED）。
   * ⚠ notifybatch 批（2026-09-18）：两条通道改走**打包入口**（决策②「异常类一并合并、
   * 不单列」）——`nodeId=None`（fire-and-forget，不记账）语义逐字未变，只是投递节拍
   * 由 root 通道打包窗统一（单件场景文本逐字不变）。
   */
  private[project] val feedbackRouter: FeedbackRouter = new FeedbackRouter(
    projectName = projectName,
    workspace = workspace,
    feedbackMode = feedbackMode,
    escalate = (text, nodeName) => enqueueRootNotify(text, nodeName, NodeLifecycle.Blocked),
    escalateFailed = Some((text, nodeName) => enqueueRootNotify(text, nodeName, NodeLifecycle.Failed))
  )

  /**
   * dispatch-notify 通道（2026-09-05 批接线 completion；2026-09-07 批接线 failed；
   * 取消静默死锁修复批 2026-09-10 接线 cancelled/R1）：
   * 节点终态结果回流分发器的单一通知入口（completion 查 notifyDispatcher flag；
   * failed/cancelled 不查——异常低频事件拓扑主人全知情；防循环+预算独立分账+
   * 窗口熔断+持久去重见 DispatchNotify）。挂接点四处：completedNode 尾部 +
   * deliverFailed 尾部直触发 + **cancelNode 尾部（R1）** + ProjectActor.TtlTick 周期补投。
   */
  private[project] val dispatchNotify: DispatchNotify = DispatchNotify.forEngine(
    store,
    workspace,
    projectName,
    rootSessionId,
    // notice 语义（非 blocked）：预算耗尽时节点保持 completed，前端不可标 BLOCKED。
    // notifybatch 批（2026-09-18）：改走 root 打包入口（异常/监督通报同窗打包，决策②）。
    escalate = (text, nodeName) => enqueueRootNotify(text, nodeName, DispatchNotify.NoticeEventType),
    emitUpdated = emitUpdated,
    triggerOverride = notifyTriggerOverride,
    // M4（b64 批）：`notify.quietMs` → 打包/静默窗口（缺省 5s，上界 60s 由挂载面校验）。
    windowMs = notifyQuietMs.getOrElse(NotifyPolicy.NotifyQuietMsDefaultMs)
  )

  /** 运行中节点 → cancel 信号（NodeCancel 用）。 */
  private[project] val running: Ref[IO, Map[String, Deferred[IO, Unit]]] =
    Ref.unsafe[IO, Map[String, Deferred[IO, Unit]]](Map.empty)

  /**
   * NodeMessage 注入链（20260905 机制批，作者裁定②）：running 节点 nodeId →
   * sessionId 活映射。runWithAgent 在飞登记时与 running 表同步置位、清理段
   * 同步移除——sendNodeMessage 据此定位节点会话 actor 发 ImmediateInput
   * （turn 边界 drain 既有基建：pendingImmediateInputs + TurnBoundaryDrains
   * .drainHead + CompactionQueueStore 压缩窗口保全，全部复用零重造）。
   * 无映射 = 会话已终结（裁定②竞态兜底入口：回退记录追加「注入未达」）。
   */
  private[project] val nodeSessions: Ref[IO, Map[String, String]] =
    Ref.unsafe[IO, Map[String, String]](Map.empty)

  /**
   * boot 恢复排队集（crash-recovery 批 2026-09-07）：快段已认领（翻 Pending）但慢段
   * 尚未 startNode(resume) 的节点 id——settleRunnableSweep 对其跳过（资格回扫的
   * 新鲜启动会与续跑竞速，CAS 裁决虽不双 spawn 但恢复降级为无 transcript 的全新重
   * 跑）。进程内 boot 生命周期状态：慢段逐节点认领前移除；慢段异常（节点级
   * handleErrorWith）后该节点回归正常 pending 池，settle 回扫以新鲜路径兜底启动
   * （R4 降级语义——恢复优先、收殓兜底）。
   */
  private[project] val bootRecoveryQueue: Ref[IO, Set[String]] =
    Ref.unsafe[IO, Set[String]](Set.empty)

  /**
   * 缺口4（2026-09-04 重复投递去重）：同 (identity, status) 60s 窗口内重复
   * Nebula 通知抑制——进程内时间窗 map（固定窗，顺路淘汰过期条目）。与 V8
   * nebulaDeliveredAt 账本**正交**：账本管跨重启 at-least-once（持久化，管
   * 「结果是否到达过 Nebula」），本表管秒级抖动（内存，管「同一通知短窗内
   * 重复轰炸」——09-03 ProjectCreate 合并回报三连投实证）。窗口内重复被抑制
   * 时仍 markNebulaDelivered，账本一致性不破坏。identity = nodeId（无 nodeId
   * 的 fire-and-forget 通道用 nodeName——escalate 各节点独立窗口）。
   */
  private[project] val recentRootDeliveries: Ref[IO, Map[(String, String), Long]] =
    Ref.unsafe[IO, Map[(String, String), Long]](Map.empty)

  /**
   * **取代面跨调用判据（R2/R3，chaincancel 批 2026-09-17）**：本进程内「由级联/链级
   * 取消腿取消」的节点集。
   *
   * 为什么必须有它（而不是只靠 `suppressTargets` 形参）：级联腿对**有在飞 fiber** 的
   * running 成员只发取消信号，其 `cancelled` 终态由**既有的桥/收殓腿**异步落盘，届时
   * 走的是默认参数（`suppressTargets = ∅`）——没有本集，那条腿会给**已取消**的下游补打
   * `pendingSuccession` 噪标（正是 `detachCancelledUpstream` 头注禁止的「打标 + 紧接
   * 取消」自相矛盾态，判据 M3）。`detachCancelledUpstream` 对本集成员**不打标**。
   *
   * 生命周期/上界：进程内、只增不缩（无持久化、无需持久化——它只服务于同一进程内
   * 秒级的异步终态腿）。FIFO 上界 [[NodeEngine.CascadeSuppressCap]]（超限丢最旧）：
   * 正常情况下成员在写入后数秒内就完成摘除，上界只为防长跑进程无界增长；被逐出者
   * 最坏后果 = 已取消下游被补一条噪标（零功能影响，非安全面）。
   *
   * 零回归：默认空集 ⇒ `cancelNode` / `reapStaleRunning` / `NodeCancel` 工具 / 面板
   * 会话键等既有腿**逐字零改动**（Z4）。
   */
  private[project] val cascadeCancelledIds: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)

  /**
   * 死会话 running 周期对账（僵尸收敛批 2026-09-06；ProjectActor.TtlTick 30s 驱动
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
   * [[NodeEngine.DeadSessionBgWaitEventType]] 事件（进程内每节点一条，防 30s 节拍刷屏）。
   */
  def settleStaleRunningNodes(): IO[Unit] =
    store.snapshot.flatMap { s =>
      s.nodes.values
        .filter(_.status == NodeLifecycle.Running)
        .toList
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
            autoFailDeadRunning(
              z.id,
              "node session dead (no live session, no in-flight background task) — auto-converged to failed by dead-session watchdog"
            )
          }
        }
    }

  /**
   * quiescent 档写事件节流记账（nodeId → 上次写事件的 ts）。内存态：重启后每个节点
   * 至多多写一条 quiescent 事件（幂等冗余，不丢监督面）；阶梯拍数的幂等不依赖本表
   * （那是 `reportReminderCount` 的持久 CAS）。
   */
  private val quiescentNotified: Ref[IO, Map[String, Long]] =
    Ref.unsafe[IO, Map[String, Long]](Map.empty)

  /** 死会话 bg-wait 豁免留痕单发记账（nodeId 集合，进程内一次）。 */
  private val bgWaitExemptNotified: Ref[IO, Set[String]] = Ref.unsafe[IO, Set[String]](Set.empty)

  /**
   * 释放唤醒单发记账（nodeId → 已唤醒的 `reportPendingSince` 值，进程内）：同一**未申报
   * 期**只注入一次唤醒轮——防 TtlTick 30s 拍连发 LLM turn。新一轮翻转/新会话重排起表
   * （新起点值）自动重新允许；进程重启后表空 ⇒ 至多再唤醒一次（幂等冗余，不丢监督面）。
   */
  private[project] val releaseWakeSent: Ref[IO, Map[String, Long]] = Ref.unsafe[IO, Map[String, Long]](Map.empty)

  /**
   * J1 形态的**单发**事件留痕（`worktree-missing`；取证件 §6.1 建议 4）：节点声明的
   * worktree 裸名在权威位置与顶层存量**两处均不存在** ⇒ 写一行事件 + 一行 WARN。
   * 同名只写一次（避免每轮扫描刷屏）；**零行为变更**（不终态化、不改状态）。
   */
  private[project] val worktreeMissingNoted = Ref.unsafe[IO, Set[String]](Set.empty)

  /**
   * 代裁 6「在事件里标出该态节点供人监督」：`settleStaleRunningNodes` 命中
   * 「死会话 + 仍在途后台任务」豁免时单发一条留痕（判据本身原样保留——bg 等待不算死）。
   */
  private def markDeadSessionBgWaitExemption(
    node: NodeDef,
    sessionId: String,
    waiting: List[BgTaskRegistry.ActiveTask]
  ): IO[Unit] =
    bgWaitExemptNotified.modify(s => if s.contains(node.id) then (s, false) else (s + node.id, true)).flatMap {
      case false => IO.unit
      case true =>
        FlowMapEventLog.append(
          workspace,
          projectName,
          node.id,
          NodeEngine.DeadSessionBgWaitEventType,
          s"dead-session node kept Running (bg-wait exemption): ${waiting.size} background task(s) still " +
            s"in flight (${waiting.map(t => s"'${t.description}'").mkString(", ")}) — not reaped, needs human supervision"
        ) *>
          logger.warn(
            s"Node '${node.name}' (${node.id}) session '$sessionId' looks dead but still owns ${waiting.size} " +
              "in-flight background task(s) — kept Running for human supervision (bg-wait exemption, no auto-convergence)"
          )
    }

  /**
   * quiescent 档（第 maxRungs 拍之后）：**不注入**，每 `quiescentIntervalMs` 写一条
   * `node-report-missing`（stage=quiescent，reminderCount 不递增）。节点保持 Running。
   */
  private[project] def fireQuiescentEvent(
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
            FlowMapEventLog.append(
              workspace,
              projectName,
              node.id,
              NodeEngine.NodeReportMissingEventType,
              NodeEngine.reportMissingSummary(
                stage = NodeEngine.NodeReportStageQuiescent,
                rung = maxRungs,
                maxRungs = maxRungs,
                rungMs = lastRungMs,
                elapsedMs = elapsed,
                pendingSince = since,
                reminderCount = node.reportReminderCount,
                ladderExhausted = true,
                delivered = None
              )
            ) *>
            logger.warn(
              s"Node '${node.name}' (${node.id}) still has no node_report declaration after the reminder ladder " +
                s"(${maxRungs} rungs, waited ${elapsed / 1000}s) — quiescent stage: no further reminders injected, " +
                "periodic audit event only; node stays Running awaiting human action (never failed, never killed)"
            )
      }
    }
  end fireQuiescentEvent

  private[project] def suspendLog(sessionId: String, nodeId: String): Unit =
    logger.info(
      s"[stuck-recovery] session $sessionId suspended (actor stopped, node $nodeId left Running — NOT terminalized; " +
        "the L3 recovery leg will CAS it back to Pending and resume from the transcript breakpoint)"
    )

  /**
   * 判词闸**回退告警**单发记账（下游 id → 上次留痕的持有者 key 序列）——
   * 同一 (下游, 持有者+判词) 组合只留一条，防每启动路径刷屏。
   */
  private[project] val verdictGapLogged: Ref[IO, Map[String, List[String]]] =
    Ref.unsafe[IO, Map[String, List[String]]](Map.empty)

  /**
   * 同键多项目告警单发记账（mark = 他项目名清单；进程内、每挂载一份——只防 30 s
   * 节拍刷屏，不承担持久幂等）。
   */
  private[project] val sameKeyWarned: Ref[IO, Set[String]] = Ref.unsafe[IO, Set[String]](Set.empty)

  /**
   * 互斥闸停等事件单发记账（nodeId → 上次留痕的持有者 id 序列）：同一持有者集合只
   * 留一条 `merge-queue` 事件 + 一行 INFO（持有者变化时再留——FIFO 次序取证面）。
   */
  private[project] val mutexHoldLogged: Ref[IO, Map[String, List[String]]] =
    Ref.unsafe[IO, Map[String, List[String]]](Map.empty)

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

  /**
   * Loop 会话（worker/verify 各一）：持久 agent 会话 + 常驻桥。跨轮同 ActorRef 复用
   * （裁定 B——上下文贯穿），每轮经 round Ref 等待本轮 Completed。桥常驻存活
   * （每轮 Completes 本轮 Deferred 后回 idle 等下一轮），终态由 spawnAndRunLoop 停毁。
   */
  private[project] final case class LoopSession(
    sessionId: String,
    agentRef: ActorRef[AgentCommand],
    bridgeRef: ActorRef[AgentEvent],
    round: Ref[IO, Deferred[IO, Either[String, List[Message]]]]
  )

  /**
   * **待接线队列扫描腿**（B5 缺口③ · 作者 2026-09-17 M-3 裁定「待接线队列（到点自动
   * 接）」，选项①）：挂既有 30s `TtlTick`（与 `settleRunnableSweep` /
   * `sweepLoopBudgets` 同族——零新调度器、零新 fiber）。
   *
   * 判据（幂等 · 零副作用面）：节点 `pendingOut` 非空 ∧ 该控制边的目标**已离开
   * running**（终态 / wiring / pending / 已归档不可寻址）⇒ 把该边**接线**
   * （`pendingOut` → `out`，canonical 合并）+ `wiring-applied` 事件 + WS 同构更新。
   * 目标仍 running ⇒ 原地保留（下一轮再判，本腿零写）。
   *
   * 红线①（nrloop 一期 2026-09-12）逐字不变：控制边不进 in 镜像、不进 barrier、不进
   * 环检 ⇒ 「接线」= 纯 `out` 声明面追加：**零补投递、零启动**（控制边零投递语义）。
   * 写路径在 `store.mutate` 内**现读 fresh** 并按 `pendingOut` 存在性收窄（并发改接/
   * 删边/已接线 ⇒ 幂等跳过，不复活陈旧声明）。
   */
  def applyDeferredWiring(): IO[Unit] =
    store.snapshot.flatMap { s =>
      s.nodes.values.toList.filter(_.pendingOut.nonEmpty).traverse_ { from =>
        val due = from.pendingOut.filter { e =>
          OutEdge.resolveTargetId(s.nodes, e.to).flatMap(s.nodes.get).forall(_.status != NodeLifecycle.Running)
        }
        if due.isEmpty then IO.unit
        else
          store.mutate { st =>
            st.nodes.get(from.id) match
              case Some(fresh) =>
                val wired = fresh.pendingOut.filter(due.contains)
                if wired.isEmpty then st
                else
                  st.copy(nodes =
                    st.nodes.updated(
                      from.id,
                      fresh.copy(
                        out = OutEdge.canonical(fresh.out ++ wired),
                        pendingOut = fresh.pendingOut.filterNot(wired.contains)
                      )
                    )
                  )
              case None => st
          } *>
            store.getNode(from.id).flatMap {
              case Some(fresh) if !fresh.pendingOut.exists(due.contains) =>
                FlowMapEventLog.append(
                  workspace,
                  projectName,
                  from.id,
                  FlowMapEventLog.WiringAppliedType,
                  s"deferred control edge(s) auto-wired: ${due.map(e => s"${e.to}:${e.mode}").mkString(",")} — " +
                    "target(s) left running (pendingOut → out; control edge = zero delivery, zero start)"
                ) *>
                  emitUpdated(fresh) *>
                  logger.info(
                    s"Node '${fresh.name}' (${fresh.id}) deferred control edge(s) auto-wired: ${due.map(_.to).mkString(",")}"
                  )
              case _ => IO.unit
            }
        end if
      }
    }

  /**
   * 依赖者触发饥饿记账（§6.3）：nodeId → 连续「资格满足却未获会话」的回扫轮数。
   * 节点启动/终态/消失即移除（下一轮重新起算）。
   */
  private[project] val starveRounds: Ref[IO, Map[String, Int]] =
    Ref.unsafe[IO, Map[String, Int]](Map.empty)

  /**
   * mount-stalled 告警**升级**记账（mount-enforce 批 + engine-defects 批 #85，2026-09-15）：
   * nodeId → (上次发射时刻 ms, 本停滞期已发射条数)。
   *
   * 前身 =「单发 Set」——一个停滞期**全生命周期只发一条** `mount-stalled`，其后永久
   * 静默。实际形态（真身 `.nebflow/flow-map-events.jsonl:5553`，2026-09-15 夹具）：
   * 上游被 R4 摘除并写入 `pendingSuccession` ⇒ barrier 被**永久** hold（唯一出口 =
   * 分发器 `NodeEdit` 人工介入，`NodeTools.scala:2373-2390`），节点 `wiring` 不动、
   * 却仍以「开态 rank 更小者」身份占着合并队列临界区 ⇒ **整条合并队列静默死锁**
   * （真身 `:5581`：两个 `wiring` 持有者挡死 `n-25e6daf7`）。单发档位在此形态下
   * 等于零告警。
   *
   * 本批改为**有界重复告警**：首次仍是 60s 档（逐字保留今日行为，`NodeMountEnforceSpec`
   * M5/M6/M7 零变化）；此后只要该节点**仍在当轮停滞集内**，每过
   * [[NodeEngine.StallReNotifyMs]] 再发一条，summary 带升级标 `escalation=#N`。
   * 节点出集（恢复/承接/启动）即自动出表 ⇒ 下次停滞 = 新停滞期、重新从 #1 起算。
   *
   * 🔴 本批**不改** R4「待承接槽位不自动结算」的裁定：自动放行会让下游按**缺轨输入**
   * 启动并产出缺轨结论（R4 头注逐字）；故只做告警可见性，不做语义放行。
   */
  private[project] val stallNotified: Ref[IO, Map[String, (Long, Int)]] =
    Ref.unsafe[IO, Map[String, (Long, Int)]](Map.empty)

  /**
   * 生效的告警升级间隔（构造器接缝优先；生产 = 现读 prop 默认 10min）。
   * `private[project]` 供 spec 断言接缝真的接进来了（其余字段同款可读面）。
   */
  private[project] val stallReNotifyWindowMs: Long =
    stallReNotifyMs.getOrElse(nebflow.shared.Defaults.StallReNotifyMs)

  /**
   * R3 即时 barrier 告警单发记账（取消静默死锁修复批）：已由**终态写点同步**
   * （[[checkBarriersNow]]）发过 `barrier-blocked` 的下游 id 集。与 [[stallNotified]]
   * 分工：本集管「即时告警已发」，周期回扫发射集要排除本集成员（同一停滞不得发
   * 两条）；每轮 sweep 按「此刻是否仍被终态上游闸住」剪枝——恢复（承接/改接/启动）
   * 即出集，未来再次停滞可再告警一次。
   */
  private[project] val barrierAlerted: Ref[IO, Set[String]] =
    Ref.unsafe[IO, Set[String]](Set.empty)

  /**
   * 失败投递（P1 语义门控，spec §2.2 #3；D5 零结算为底座）：
   * ① 运行期 merge 兜底（纵深，护栏只读复用不重构 §2.6-2）：全部 out 边的节点目标
   *    中 MergeNodePolicy.haltsOnFailure 命中 → mergeBlocked（**与门控无关**——覆盖
   *    校验生效前落盘的旧拓扑 pass-only 边；创建期 NODE_MERGE_PASS_ONLY 已硬拒新
   *    failed 入边）。先于 signal 结算执行（merge blocked 优先于下游触发，D5 原序）。
   *    **`:loop` 控制边不在 nodeTargets 内**（nrloop 一期：verdict 选通面与 failed
   *    结算面正交，见下方 nodeTargets 计算处）。
   * ② Nebula 上报（on ∋ failed）：mode=result → 通报（旧 "Nebula" 双通报形态零漂移）；
   *    mode=signal → 只记账。
   * ③ on-failed signal 节点目标：settleTo 记账归零 barrier + 启动（buildInput 按边
   *    mode 抑制载荷——failed 节点 result=错误文本不作输入投递）。
   * ④ 其余（on-failed result 节点目标 = D5 零结算语义对齐，及无 failed 边覆盖的
   *    pass-only 旧拓扑目标）：停等——不结算不投递，恢复零步（上游 reactivate 修复
   *    重跑完成后经 deliverOut 正常投递）。
   * 尾部 dispatch-notify 挂点不变（failed 版，不查 flag）。
   */
  private[project] def deliverFailed(node: NodeDef, err: String): IO[Unit] =
    val edges = OutEdge.canonical(node.out)
    val failedEdges = edges.filter(_.on.contains(OutEdge.Failed))
    // **`:loop` 控制边排除（nrloop 一期 2026-09-12，B3 末条 / 设计 §3.5）**：`:loop`
    // 边是 verdict 选通的控制边（`on={fail}`，与 `failed` 门正交），不进任何 failed
    // 结算面——否则 vera 的 loop 目标会被误当「失败下游」做 merge 兜底/停等留痕
    // （无 failed 门覆盖 ⇒ 恒落 waiting 分支，每轮刷一条误导日志）。
    val nodeTargets = edges.filterNot(OutEdge.isLoopEdge).map(_.to).filterNot(_ == OutEdge.RootTarget).distinct
    val signalTargets = failedEdges
      .filter(e => e.to != OutEdge.RootTarget && e.mode == OutEdge.Signal)
      .map(_.to)
      .distinct
    val mergeFallback: IO[Unit] = nodeTargets.traverse_(t =>
      store.findNode(t).flatMap {
        case Some(target) if MergeNodePolicy.haltsOnFailure(target) =>
          mergeBlockedByUpstreamFailure(target, node, err)
        case _ => IO.unit
      }
    )
    val rootIO = failedEdges.filter(_.to == OutEdge.RootTarget) match
      case Nil => IO.unit
      case nes if nes.exists(_.mode == OutEdge.Result) =>
        // notifybatch 批（2026-09-18，M-2）：失败腿同走打包入口（决策②异常类一并合并）。
        enqueueRootNotify(s"[Node '${node.name}' failed]\n$err", node.name, "failed", Some(node.id))
      case _ => markRootDelivered(node.id)
    val signalIO = signalTargets.traverse_(t => settleTo(node, t))
    val waitLog: IO[Unit] =
      val waiting = nodeTargets.diff(signalTargets) // 非 signal 覆盖的目标 = D5 停等（merge 兜底者已转 blocked）
      if waiting.nonEmpty then
        logger.info(
          s"Node '${node.name}' failed — downstream(s) ${waiting.mkString(", ")} keep waiting (D5 zero-settlement; on-failed signal edges opt targets out; reactivate upstream to auto-resume)"
        )
      else IO.unit
    for
      _ <- mergeFallback
      _ <- rootIO
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

  end deliverFailed

  /**
   * P2 retry 触发判定（spec §2.3）：三态分流见 deliverFailed 尾注。
   *
   * **R4（cancelsem 批 1 · 2026-09-17）：用户主动取消的上游不得被重新武装** —— 第四态
   * 「抑制」：`retry.upstream` 是**用户**（人/Agent）主动取消的节点
   * （[[CancelSource.isUserCancelled]]）⇒ 本次自动重试**整腿不触发**（既不重激活本节点、
   * 也不重跑上游），本节点留 failed 并走既有 failed 回流告知分发器；抑制事实落
   * `retry` 审计事件 + WARN。判据单点 = 上游自身 `result` 的 source 前缀
   * （[[CancelSource.fromResult]]，禁二次派生）。
   *
   * 为什么是「整腿不触发」而非「只跳过上游重激活」：后者会让本节点在**缺轨**（上游已
   * 被取消终态写点摘除出 in 镜像）状态下被重跑——白烧一轮后再次 failed，正是用户报的
   * 「还在重试」形态；而用户取消表达的意图就是「这条轨停下」。
   * **引擎发起**的取消（L3 硬恢复 / 看门狗 giveUp / 死会话收殓）**语义逐条不变**
   * （照旧重激活上游，与 L3 恢复链、RetryCap 预算链零交互）；source **不可判定**
   * （R2 落地前的旧数据 / abandon 不写 result）⇒ 保持现状不抑制（语义选择项，
   * 见批报告待拍板栏，禁自裁）。
   *
   * **判据单点（cancelsem 批 2，2026-09-17）**：第四态的判定条件抽到
   * [[DispatchNotify.userCancelSuppression]]（同一函数、同一常量
   * [[CancelSource.isUserCancelled]]）——本处与 failed 回流文本
   * （`DispatchNotify.suppressedFailedNotifyTaskText`）共用同一判据，禁二次派生；
   * 本处语义（整腿不触发 + `retry` 审计 + WARN + failed 回流）逐字未改。
   */
  private def retryOrNotify(node: NodeDef, err: String): IO[Unit] =
    node.retry match
      case None => dispatchNotify.notifyTerminal(node, NotifyReason.Failed)
      case Some(policy) if node.gen < policy.max =>
        // 判据单点（cancelsem 批 2，2026-09-17）：第四态「抑制」的判定条件抽到
        // DispatchNotify.userCancelSuppression（与 failed 回流文本变体**共用同一
        // 函数、同一常量 CancelSource.isUserCancelled**，禁二次派生第二套判定）。
        // 本处行为逐字未改（整腿不触发 + retry 审计 + WARN + failed 回流）。
        dispatchNotify.userCancelSuppression(node).flatMap {
          case Some(up) =>
            val note =
              s"auto-retry suppressed: retry.upstream '${up.name}' (${up.id}) was cancelled by the user " +
                s"(source=user) — a user cancel is never re-armed; node stays failed (no self-reactivation, gen stays ${node.gen}/${policy.max})"
            FlowMapEventLog.append(workspace, projectName, node.id, "retry", note) *>
              logger.warn(s"Node '${node.name}' (${node.id}) $note") *>
              dispatchNotify.notifyTerminal(node, NotifyReason.Failed)
          case _ => retryReactivate(node, policy, err)
        }
      case Some(_) =>
        dispatchNotify.notifyTerminal(node, NotifyReason.Failed) *>
          feedbackRouter.routeRetryCap(node, err)

  /**
   * 自动回跳重激活链（spec §2.3：既有重激活协议的自动化——「不是新机制，是分发器
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
   * 幂等跳过——与今天人工重激活语义完全一致（spec §2.3 红线）。
   */
  private def retryReactivate(failed: NodeDef, policy: RetryPolicy, err: String): IO[Unit] =
    for
      now <- IO(System.currentTimeMillis())
      s <- store.mutate { st =>
        st.nodes.get(failed.id) match
          case Some(fresh) if fresh.status == NodeLifecycle.Failed =>
            val nextStatus =
              if fresh.task.exists(_.trim.nonEmpty) && fresh.in.isEmpty then NodeLifecycle.Pending
              else NodeLifecycle.Wiring
            st.copy(nodes =
              st.nodes.updated(
                failed.id,
                fresh.copy(
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
                  notifySentAt = None
                )
              )
            )
          case _ => st // 状态已变（并发 abandon/人工重激活）→ 拒写不回跳
      }
      _ <- s.nodes.get(failed.id) match
        case Some(b)
            if b.gen == failed.gen + 1 &&
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
                case Some(up)
                    if up.result.isDefined && up.status != NodeLifecycle.Blocked && NodeLifecycle.Terminal.contains(
                      up.status
                    ) =>
                  deliverOutTo(up, b.id, up.result.get)
                case _ => IO.unit
              }
            } *>
            forkStart(s"retry-rerun -> ${policy.upstream}")(store.getNode(policy.upstream).flatMap {
              case None => IO.unit
              case Some(up) => redeliverInAndStart(up)
            })
        case _ =>
          logger.info(
            s"Node '${failed.name}' retry skipped — state changed before retry finalize (fresh-read discipline)"
          )
    yield ()

  /**
   * retry 上游重激活（既有重激活协议字段族，NodeEdit failed 重激活同构——不触碰
   * task/description/in/out/deps/loop/retry）：result 清（旧产物不作投递，重跑产出
   * 全新投递）、deliveredTo/nebulaDeliveredAt 清（重跑完成后重新投递+记账）、时间戳
   * 复位；failed 上游轮次历史复位，completed 上游轮次字段原样。R2 竞态守卫：fresh
   * 仍处重激活前看到的终态才写（并发人工重激活/abandon → 拒写）。
   *
   * **R4 输入域（cancelsem 批 1）**：本方法的唯一生产调用方 = [[retryReactivate]] 的
   * retry.upstream 腿，而「**用户主动取消**」的上游已在 [[retryOrNotify]] 被整腿抑制
   * ⇒ 本方法的输入域**不含** user-cancel 节点（判据单点 [[CancelSource.isUserCancelled]]）；
   * 引擎发起取消（Cancelled ∧ source=engine/不可判定）与 completed/failed/blocked 照旧
   * 入域——「某轨是否可以重新武装」的裁决点只有一个（retryOrNotify），本方法只做写。
   */
  private def reactivateForRetry(up: NodeDef, now: Long): IO[Unit] =
    store
      .mutate { st =>
        st.nodes.get(up.id) match
          case Some(fresh) if fresh.status == up.status && NodeLifecycle.Terminal.contains(fresh.status) =>
            val fromFailed = fresh.status == NodeLifecycle.Failed
            val nextStatus =
              if fresh.task.exists(_.trim.nonEmpty) && fresh.in.isEmpty then NodeLifecycle.Pending
              else NodeLifecycle.Wiring
            st.copy(nodes =
              st.nodes.updated(
                up.id,
                fresh.copy(
                  status = nextStatus,
                  result = None,
                  deliveredTo = Nil,
                  nebulaDeliveredAt = None,
                  startedAt = None,
                  completedAt = None,
                  ttlExpireAt = None,
                  blockCount = if fromFailed then 0 else fresh.blockCount,
                  blockedFeedback = if fromFailed then None else fresh.blockedFeedback,
                  notifySentAt = if fromFailed then None else fresh.notifySentAt
                )
              )
            )
          case _ => st
      }
      .flatMap { s2 =>
        s2.nodes.get(up.id) match
          case Some(u2) if u2.status == NodeLifecycle.Wiring || u2.status == NodeLifecycle.Pending =>
            emitWithChain("nodeUpdated", u2.id, NodePayload.buildNodeJson(u2, now))
          case _ => IO.unit
      }

  /**
   * 重激活补投递 + 启动（NodeTools 重激活链同款骨架，引擎内单点）：全部 in 上游
   * 「终态有结果、非 blocked」重投（deliverOutTo dedup 幂等）→ barrier 归零启动；
   * 入口节点（无 in、Pending）直接启动。
   */
  private def redeliverInAndStart(n: NodeDef): IO[Unit] =
    n.in.traverse_ { upId =>
      store.findNode(upId).flatMap {
        case Some(up)
            if up.result.isDefined && up.status != NodeLifecycle.Blocked && NodeLifecycle.Terminal.contains(
              up.status
            ) =>
          deliverOutTo(up, n.id, up.result.get)
        case _ => IO.unit
      }
    } *> store.getNode(n.id).flatMap {
      case Some(n2) if n2.in.nonEmpty && n2.in.forall(n2.deliveredTo.contains) => startNode(n2.id)
      case Some(n2) if n2.in.isEmpty && n2.status == NodeLifecycle.Pending => startNode(n2.id)
      case _ => IO.unit
    }

  /**
   * 合并节点因上游失败转 blocked（merge-node 批 §触发语义②；与 blockedNode 同构
   * 但**不经 FeedbackRouter**）：① 事务内现读 fresh（R2 纪律，haltsOnFailure 只认
   * wiring/pending）→ status=Blocked / result=渲染串 / blockedFeedback=合成反馈 /
   * completedAt=now / ttlExpireAt=None（待办语义）。blockCount 不增（非节点自报
   * 轮次，重入主责在失败上游侧，MergeNodePolicy.upstreamFailureFeedback）。
   * ② emitEvent nodeUpdated + FlowMapEventLog "merge-blocked" + deliverToRoot
   * 通报（无 nodeId=fire-and-forget，对齐 escalate 通道：状态通报不是结果投递）。
   * 语义收益：不触发合并（占位结算被旁路）+ 不永久 pending 悬挂（blocked 可见
   * 终态，分发器 NodeList 可巡检）+ 已完成上游的结算保留（修复上游 → 重激活合并
   * 节点 → 既有重激活重投链自动补齐 barrier）。
   * public（mount-enforce 批）：第二挂接点 = NodeTools 创建期 D1 保留投递——
   * 合并节点创建时 in 引用已 failed 的上游（创建顺序翻转后合法形态），failed
   * 错误文本不作输入投递，同语义转 blocked 不悬挂。
   */
  def mergeBlockedByUpstreamFailure(target: NodeDef, failed: NodeDef, err: String): IO[Unit] =
    val feedback = MergeNodePolicy.upstreamFailureFeedback(failed, err)
    for
      now <- IO(System.currentTimeMillis())
      s <- store.mutate { st =>
        st.nodes.get(target.id) match
          case Some(fresh) if MergeNodePolicy.haltsOnFailure(fresh) =>
            st.copy(nodes =
              st.nodes.updated(
                target.id,
                withoutReportPending(
                  fresh.copy(
                    status = NodeLifecycle.Blocked,
                    result = Some(MergeNodePolicy.renderBlocked(feedback)),
                    blockedFeedback = Some(feedback),
                    completedAt = Some(now),
                    ttlExpireAt = None
                  )
                )
              )
            )
          case _ => st // 状态已变（并发终态化）→ 拒写（R2 竞态纪律）
      }
      _ <- s.nodes.get(target.id) match
        case Some(bn) if bn.status == NodeLifecycle.Blocked =>
          val summary = s"merge node blocked: upstream '${failed.name}' (${failed.id}) failed — ${err.take(140)}"
          emitWithChain("nodeUpdated", target.id, NodePayload.buildNodeJson(bn, now)) *>
            logger.warn(s"Node '${bn.name}' $summary") *>
            FlowMapEventLog.append(workspace, projectName, target.id, "merge-blocked", summary) *>
            // notifybatch 批（2026-09-18）：走 root 打包入口（blocked 通报同窗打包，
            // 决策②「异常类一并合并、不单列」）；`nodeId=None` fire-and-forget 口径不变。
            enqueueRootNotify(
              s"[Node '${bn.name}' blocked — 上游 '${failed.name}' failed，合并未执行]\n${err.take(800)}",
              bn.name,
              NodeLifecycle.Blocked
            )
        case _ => IO.unit
    yield ()

    end for

  end mergeBlockedByUpstreamFailure

  /**
   * root 打包条目（`nodeId=None` 的 escalate/notice 腿以 nodeName 作身份，
   * 与 [[dedupeNebulaDelivery]] 的 identity 口径同源）。
   */
  private[project] case class RootNotifyEntry(text: String, nodeName: String, status: String, nodeId: Option[String]):
    def identity: String = nodeId.getOrElse(nodeName)

  /**
   * 缓冲状态：`entries` = 本窗（+溢出留队）尚未注入的件，FIFO；`windowArmed` = 本窗
   * 计时是否已在走（防同窗第二件重复起算 ⇒ 保持「首件起算、不随新件延长」）。
   */
  private[project] case class RootNotifyBatchState(
    entries: Vector[RootNotifyEntry] = Vector.empty,
    windowArmed: Boolean = false
  )

  private[project] val rootNotifyBatchState: Ref[IO, RootNotifyBatchState] =
    Ref.unsafe[IO, RootNotifyBatchState](RootNotifyBatchState())

  /**
   * 链级摘要**降级登记**（作者 2026-09-16 裁定「**全部降级列表态**」；本批前语义 = 投根）。
   *
   * == 语义（本批起）==
   * **零投主对话**：链完成横幅**不再注入 root 会话**——不再 `ref ! AgentCommand.ImmediateInput(...)`，
   * 故 ① 不进 LLM 上下文（`AgentActor` 的 `immediateMessages` 腿不接本链件）
   * ② 不出即时气泡（`emitInjectedUserEvent` 链腿不再被调用，`.ui.json` 零
   * `source="chain"` 行）。本方法退化为**一次日志登记**（可观测性 + R-10 口径区分：
   * 「本回合出库链数 / 降级登记数 / 投递数 0」）。
   *
   * **聚合信息不删除**（降级 ≠ 删除）：链级事实的承载面 = **链级列表/明细面**
   * ——归档 sweep 照跑（`FlowMapStore.sweepCompletedChainsDetailed`）、批文件 + 归档区
   * 照写、`chain-archived` 审计事件照记；UI 侧由 **Flow Map 归档面板**
   * （`GET /projects/<n>/flow-map/archive` 批次聚合 → 链条目 → 成员行 → 详情窗按需
   * 取结果全文）**在用户主动查看时**呈现（含 §「读取指引」等价提示）。
   * ⇒ root 侧不再有横幅，但「链已归档、可去查明细」在列表态**可达**，非静默丢信息。
   *
   * == 记账（R-9）==
   * 调用方（`ProjectActor.deliverChainSummaries`）在本方法**恒成功后**照记
   * `markChainSummarySent` ⇒ 批文件 `summarySentAt` 置位（防每拍重算 / 重复降级）。
   * **不再依赖 root 会话 ref 存在性**（本批前：ref 缺失 ⇒ false ⇒ 不记账 ⇒ 下拍补投）；
   * 降级后 root 面不存在 ⇒ 该依赖与其 redelivery 语义一并退出（**语义变化显式申报**，
   * 见批报告 R-10 节）。
   *
   * == 保留的历史形态（不再触发）==
   * 本批前的气泡 header 契约（`source="chain"` + `eventType` 三元 + `sender="<project>/<chainId>"`）
   * 与「不过 60s 同 (identity,status) 去重」纪律**一并失效**——`source="chain"` 词表项
   * 仍在（`InjectionAttribution.BackendNamedSources` + 前端表），仅服务**存量历史行**
   * 的渲染（宿主 sessions 面**现取**：单文件 `5cc7590a-…ui.json` 内 49 处 `source="chain"`，
   * 全部**无 `header` 键** ⇒ 逐行走前端回落渲染；条数随宿主 session 轮转漂移，禁当恒值）。
   */
  private[project] def deliverChainSummary(text: String, chainId: String, eventType: String): IO[Unit] =
    logger.info(
      s"Project '$projectName' chain summary DOWNGRADED to list state (no root injection): chain=$chainId event=$eventType chars=${text.length}"
    )

  /**
   * 缺口4：去重判定+登记（固定窗：首投时间戳起算 60s，不滑动；过期条目顺路
   * 淘汰=时间窗淘汰）。true = 窗口内重复（应抑制 offer）。
   */
  private[project] def dedupeRootDelivery(identity: String, status: String): IO[Boolean] =
    IO(System.currentTimeMillis()).flatMap { now =>
      recentRootDeliveries.modify { m =>
        val live = m.view.filter { case (_, ts) => now - ts < NodeEngine.RootDedupWindowMs }.toMap
        live.get((identity, status)) match
          case Some(_) => (live, true)
          case None => (live.updated((identity, status), now), false)
      }
    }

  /**
   * V8: 写 nebulaDeliveredAt 记账（活动区优先，归档区兜底——TTL 归档的
   * 未投递节点同样要记账，否则扫描每次重启都重投）。
   */
  private[project] def markRootDelivered(nodeId: String): IO[Unit] =
    store.getNode(nodeId).flatMap {
      case Some(_) =>
        store.mutate { s =>
          s.nodes.get(nodeId) match
            case Some(n) =>
              s.copy(nodes = s.nodes.updated(nodeId, n.copy(nebulaDeliveredAt = Some(System.currentTimeMillis()))))
            case None => s
        }.void
      case None =>
        store.mutateArchive { a =>
          a.nodes.get(nodeId) match
            case Some(n) =>
              a.copy(nodes = a.nodes.updated(nodeId, n.copy(nebulaDeliveredAt = Some(System.currentTimeMillis()))))
            case None => a
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
  def redeliverUnconsumedRootResults(): IO[Int] =
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
                // N3（2026-09-12 批）：**再按 mode 收窄**——本扫描的补投调用不经
                // nebulaDelivery/deliverFailed 的 mode 分支（直调 deliverToNebula）⇒
                // 只看门集会把 mode=signal 的**出口标记**边当成通知声明补投（bare
                // "Nebula" 出口标记节点若在 markNebulaDelivered 之前崩溃/重启，30s 扫描
                // 就把它投到 root，绕过「默认不升根」）。补 e.mode == Result 这一合取项。
                n.out.exists(e =>
                  e.to == OutEdge.RootTarget &&
                    e.mode == OutEdge.Result &&
                    e.on.contains(if n.status == NodeLifecycle.Completed then OutEdge.Pass else OutEdge.Failed)
                ) &&
                // R5 补投感知（spec §5 行 14「策略 ≠ root 的节点不得被补投扫描复活投递」）：
                // **completed 腿**再按通知策略裁决（显式 silent/dispatcher ⇒ 不入候选）。
                // failed 腿**不查策略**（R14 不豁免：显式 `(failed)Nebula` 的失败通报根
                // 语义与今天逐字相同）；legacy 节点的 completed 判据退化为同一个边的
                // 存在性判据 ⇒ 存量补投行为字节级零漂移。
                (n.status != NodeLifecycle.Completed || NotifyPolicy.completedRootVisible(n)) &&
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
              s"[fixture-guard] excluded fixture envelope from redelivery scan: node '${n.name}' (${n.id}) status=${n.status} — name matches fixture family and task carries fixture marker"
            )
              *> markRootDelivered(n.id)
          )
          pending = unpaid.filterNot(NodeEngine.isFixtureEnvelope).toList
          // 缺口2（2026-09-04 补投新鲜度门控）：completedAt 距今 ≤24h（与节点显示
          // TTL 同口径）= 新鲜欠账 → 逐条投（既有形态零改动）；>24h = 历史欠账 →
          // 同批合并单条汇总通知（结果照投不丢：每节点 id+状态+摘要入汇总并逐节点
          // 记账）。completedAt 缺失 = 无法判旧 → 按新鲜逐条（宁投勿丢）。首次实时
          // 投递（completeNode/deliverOut）不走此路径，零改动。
          // MUTATION-2 已恢复：新鲜度门控（缺口2）——completedAt 距今 ≤24h 逐条、>24h 合并
          (fresh, stale) = pending.partition(n => n.completedAt.forall(c => now - c <= NodeEngine.StaleRedeliveryMs))
          _ <- fresh.traverse_(n =>
            // notifybatch 批（2026-09-18，M-4）：fresh 腿改走**同一打包入口**（与实时腿
            // 同窗同 digest 形态 ⇒ 补投扫描撞窗时合并而非逐件补投）；stale 腿
            // [[deliverStaleSummary]]（>24h 合并摘要，唯一现存合并点）**零行为改动**。
            enqueueRootNotify(s"[Node '${n.name}' ${n.status}]\n${n.result.get}", n.name, n.status, Some(n.id))
          )
          _ <- if stale.nonEmpty then deliverStaleSummary(stale) else IO.unit
          _ <-
            if pending.nonEmpty then
              logger.info(
                s"Node redelivery scan: re-delivered ${pending.size} unconsumed out=Nebula result(s) to root '$rootSessionId' (fresh=${fresh.size} stale-merged=${stale.size} fixture-excluded=${fixtures.size})"
              )
            else IO.unit
        yield pending.size
    }

  /**
   * 缺口2：历史欠账（completedAt >24h）同批合并单条汇总通知（形态 (a) 取舍：
   * 纯后端立即生效，不依赖前端改动——形态 (b) 载荷打标记需后续前端批才可见）。
   * 一条文本含 N 条 nodeId+状态+结果摘要（160 字/条）。eventType：混含 failed →
   * "failed"（强提醒），全 completed → "completed"。**绕过缺口4 去重直投**——
   * 汇总文本每批唯一，若走 deliverToRoot 会与 60s 前一批汇总同 key 相撞被
   * 误抑制（节点已被记账=通知丢失）。offer 后逐节点记账（at-least-once：offer
   * 与记账间崩溃 → 下轮扫描重汇总，宁重复不丢失）。
   */
  private def deliverStaleSummary(stale: List[NodeDef]): IO[Unit] =
    val eventType =
      if stale.exists(_.status == NodeLifecycle.Failed) then NodeLifecycle.Failed else NodeLifecycle.Completed
    val lines = stale.zipWithIndex
      .map((n, i) =>
        s"${i + 1}. [${n.status}] ${n.name} (${n.id}): ${n.result.get.trim.take(NodeEngine.StaleSummaryPerNodeChars)}"
      )
      .mkString("\n")
    val text =
      s"[Node 历史欠账汇总补投 ×${stale.size}（>${NodeEngine.StaleRedeliveryMs / 3600000}h 未消费，项目 $projectName）]\n$lines"
    resources.agentRegistry.get.map(_.get(rootSessionId).map(_.ref)).flatMap {
      case Some(ref) =>
        (ref ! AgentCommand.ImmediateInput(
          text,
          source = Some("node"),
          eventType = Some(eventType),
          sender = Some(s"$projectName/stale-redelivery-summary"),
          fromUser = false // ② 服务端注入（重投汇总），不是真人输入
        )) *> stale.traverse_(n => markRootDelivered(n.id))
      case None => IO.unit // 根不可达 → 不记账不丢账，下轮扫描重汇总
    }
  end deliverStaleSummary

  // `deliverDispatcherOutputToNebula` 已删净（R2「一个 Mail 统一」批 2026-09-12，
  // R7-b 桥收敛 + D-4 二次裁定）：分发器 turn 终态**不再**无条件把最终 assistant
  // 文本注入 root——root 注入面 100% 由显式载体驱动（分发器显式
  // `Mail(address="Nebula", type=RESULT, chainId=<本批链 id>, ...)`）。
  // 该函数全仓零第三方调用者（唯一调用点 = ProjectActor.dispatcherBridge，同批移除），
  // 按 B5-c 精神删净而不留死代码。观测口径随之而定：source=="dispatcher" 族
  // **生产者归零**（`project-dispatcher/system.md` 的「自动投递」措辞同批改写，
  // 否则分发器以为会自动投递 ⇒ root 面静默收不到）。

  private[project] def extractLastAssistantText(messages: List[Message]): String =
    messages.reverse
      .collectFirst {
        case m if m.role == nebflow.shared.MessageRole.Assistant => m.textContent
      }
      .filter(_.trim.nonEmpty)
      .getOrElse("")

  private[project] case class FailOutcome(message: String)

end NodeEngine

object NodeEngine:
  // 伴生契约已整体迁至 NodeEngineContract.scala(行为保持重构,2026-09-25):
  // val/def 逐成员保留同名同签名委托;嵌套类型(FlipOutcome / StartRaceLost /
  // RetireDetach / PluginPreparation / RecoveryAnchor / NodeChainContext /
  // ResumeContext)以 type 别名 + val 别名成对承接(类型位与伴生对象位一并
  // 再导出);全部 NodeEngine.X 调用点(含测试)零改动。
  val CascadeSuppressCap: Int = NodeEngineContract.CascadeSuppressCap

  def l3CascadeAllowed(deferDetach: Boolean): Boolean = NodeEngineContract.l3CascadeAllowed(deferDetach)

  def prunedReferrersBetween(before: FlowMapState, after: FlowMapState, cancelIds: Set[String]): List[String] =
    NodeEngineContract.prunedReferrersBetween(before, after, cancelIds)

  val TtlDisplayMs: Long = NodeEngineContract.TtlDisplayMs

  type FlipOutcome = NodeEngineContract.FlipOutcome
  val FlipOutcome: NodeEngineContract.FlipOutcome.type = NodeEngineContract.FlipOutcome

  val StarvedRounds: Int = NodeEngineContract.StarvedRounds

  val MountStalledMs: Long = NodeEngineContract.MountStalledMs

  def StallReNotifyMs: Long = NodeEngineContract.StallReNotifyMs

  val StaleSpawnGraceMs: Long = NodeEngineContract.StaleSpawnGraceMs

  type StartRaceLost = NodeEngineContract.StartRaceLost
  val StartRaceLost: NodeEngineContract.StartRaceLost.type = NodeEngineContract.StartRaceLost

  type RetireDetach = NodeEngineContract.RetireDetach
  val RetireDetach: NodeEngineContract.RetireDetach.type = NodeEngineContract.RetireDetach

  val StaleRedeliveryMs: Long = NodeEngineContract.StaleRedeliveryMs

  val StaleSummaryPerNodeChars: Int = NodeEngineContract.StaleSummaryPerNodeChars

  val FixtureFamilyRegex = NodeEngineContract.FixtureFamilyRegex

  val FixtureTaskMarker = NodeEngineContract.FixtureTaskMarker

  def isFixtureEnvelope(node: NodeDef): Boolean = NodeEngineContract.isFixtureEnvelope(node)

  val RootDedupWindowMs: Long = NodeEngineContract.RootDedupWindowMs

  type PluginPreparation = NodeEngineContract.PluginPreparation
  val PluginPreparation: NodeEngineContract.PluginPreparation.type = NodeEngineContract.PluginPreparation

  val NodeMessagePrefix: String = NodeEngineContract.NodeMessagePrefix

  val NodeMessageEventType: String = NodeEngineContract.NodeMessageEventType

  def nodeMessageHeader(nodeName: String): String = NodeEngineContract.nodeMessageHeader(nodeName)

  def nodeMessageSection(undelivered: Boolean): String = NodeEngineContract.nodeMessageSection(undelivered)

  val SessionPrefix = NodeEngineContract.SessionPrefix

  val NodeReportReminderPrefix: String = NodeEngineContract.NodeReportReminderPrefix

  val NodeReportMissingEventType: String = NodeEngineContract.NodeReportMissingEventType

  val DeadSessionBgWaitEventType: String = NodeEngineContract.DeadSessionBgWaitEventType

  val NodeReportReminderSource: String = NodeEngineContract.NodeReportReminderSource

  val NodeReportStageActive: String = NodeEngineContract.NodeReportStageActive

  val NodeReportStageQuiescent: String = NodeEngineContract.NodeReportStageQuiescent

  val NodeReportReleaseWakePrefix: String = NodeEngineContract.NodeReportReleaseWakePrefix

  val NodeReportReleaseWakeSource: String = NodeEngineContract.NodeReportReleaseWakeSource

  val NodeReportReleaseWakeEventType: String = NodeEngineContract.NodeReportReleaseWakeEventType

  def reportReleaseWakeText(nodeName: String): String = NodeEngineContract.reportReleaseWakeText(nodeName)

  def reportReleaseWakeSummary(sessionId: String, pendingSince: Long, delivered: Boolean): String =
    NodeEngineContract.reportReleaseWakeSummary(sessionId, pendingSince, delivered)

  val ReportUnconsumedEventType: String = NodeEngineContract.ReportUnconsumedEventType

  val ReportConsumedEventType: String = NodeEngineContract.ReportConsumedEventType

  val DestroyScheduledEventType: String = NodeEngineContract.DestroyScheduledEventType

  val DestroyedEventType: String = NodeEngineContract.DestroyedEventType

  val DestroyWithdrawnEventType: String = NodeEngineContract.DestroyWithdrawnEventType

  def reportRungLabel(ms: Long): String = NodeEngineContract.reportRungLabel(ms)

  def reportReminderText(nodeName: String, rung: Int, maxRungs: Int, rungMs: Long, elapsedMs: Long): String =
    NodeEngineContract.reportReminderText(nodeName, rung, maxRungs, rungMs, elapsedMs)

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
    NodeEngineContract.reportMissingSummary(
      stage,
      rung,
      maxRungs,
      rungMs,
      elapsedMs,
      pendingSince,
      reminderCount,
      ladderExhausted,
      delivered
    )

  def parseReportMissingSummary(summary: String): Map[String, String] =
    NodeEngineContract.parseReportMissingSummary(summary)

  def findNodeForSession(snap: FlowMapState, sessionId: String): Option[NodeDef] =
    NodeEngineContract.findNodeForSession(snap, sessionId)

  def frozenSessionBlocksResume(live: Option[AgentRecord]): Boolean =
    NodeEngineContract.frozenSessionBlocksResume(live)

  def sessionLevelPrimitiveAllowed(rec: AgentRecord): Boolean =
    NodeEngineContract.sessionLevelPrimitiveAllowed(rec)

  def capReplayMessages(msgs: List[Message], max: Int): (List[Message], Boolean) =
    NodeEngineContract.capReplayMessages(msgs, max)

  def replayCapNote(total: Int, kept: Int): String = NodeEngineContract.replayCapNote(total, kept)

  val SuspendReasonPrefix = NodeEngineContract.SuspendReasonPrefix

  def isSuspendOutcome(msg: String): Boolean = NodeEngineContract.isSuspendOutcome(msg)

  def finalizeCause(msg: String): String = NodeEngineContract.finalizeCause(msg)

  type RecoveryAnchor = NodeEngineContract.RecoveryAnchor
  val RecoveryAnchor: NodeEngineContract.RecoveryAnchor.type = NodeEngineContract.RecoveryAnchor

  def stuckResumeNote(anchor: Option[RecoveryAnchor]): String = NodeEngineContract.stuckResumeNote(anchor)

  private[project] def a3UnavailableNote(dir: String): String = NodeEngineContract.a3UnavailableNote(dir)

  val MaxBlockRoundsPerNode: Int = NodeEngineContract.MaxBlockRoundsPerNode

  val ProtocolFootnote: String = NodeEngineContract.ProtocolFootnote

  val VerifierVerdictFootnote: String = NodeEngineContract.VerifierVerdictFootnote

  def protocolFootnoteFor(role: Option[String]): String = NodeEngineContract.protocolFootnoteFor(role)

  type NodeChainContext = NodeEngineContract.NodeChainContext
  val NodeChainContext: NodeEngineContract.NodeChainContext.type = NodeEngineContract.NodeChainContext

  def chainHeaderLine(c: NodeChainContext): String = NodeEngineContract.chainHeaderLine(c)

  val DocProvenanceBlock: String = NodeEngineContract.DocProvenanceBlock

  val LoopPhaseWorker: String = NodeEngineContract.LoopPhaseWorker

  val LoopPhaseVerify: String = NodeEngineContract.LoopPhaseVerify

  val BootRecoveryEventType: String = NodeEngineContract.BootRecoveryEventType

  val InterruptedEventType: String = NodeEngineContract.InterruptedEventType

  type ResumeContext = NodeEngineContract.ResumeContext
  val ResumeContext: NodeEngineContract.ResumeContext.type = NodeEngineContract.ResumeContext

  def nodeResumePrompt(projectName: String, node: NodeDef, recoveredCount: Int, bgWaitNote: Option[String]): String =
    NodeEngineContract.nodeResumePrompt(projectName, node, recoveredCount, bgWaitNote)

  def loopWorkerResumePrompt(node: NodeDef, round: Int, phase: String, recoveredCount: Int): String =
    NodeEngineContract.loopWorkerResumePrompt(node, round, phase, recoveredCount)

  def loopVerifyResumeAnnotation(round: Int): String = NodeEngineContract.loopVerifyResumeAnnotation(round)

  val VerifyVerdictFootnote: String = NodeEngineContract.VerifyVerdictFootnote

  val VerifyDefaultTask: String = NodeEngineContract.VerifyDefaultTask

  def loopReworkAdmission(status: String): Either[String, Unit] = NodeEngineContract.loopReworkAdmission(status)

  def loopReworkInput(round: Int, issues: List[String], requirements: String): String =
    NodeEngineContract.loopReworkInput(round, issues, requirements)

  def loopVerifyInput(
    round: Int,
    nodeTask: String,
    upstreamSection: String,
    workerOutput: String,
    verifyTask: String
  ): String =
    NodeEngineContract.loopVerifyInput(round, nodeTask, upstreamSection, workerOutput, verifyTask)

end NodeEngine
