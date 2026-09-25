package nebflow.core.project

import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.actor.*
import nebflow.agent.*
import nebflow.core.entity.EntityLoader
import nebflow.core.node.NodeRunner
import nebflow.core.plugin.{PluginMcpManager, PluginRegistry, PluginsConfig}
import nebflow.core.skill.SkillService
import nebflow.core.tools.BgTaskRegistry
import nebflow.core.{NebflowLogger, PathUtil}
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
  bgWaitCapMs: Long = nebflow.shared.Defaults.BgGateWaitTimeoutMs,
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
    with NodeCompletion:
  private[project] val logger = NebflowLogger.forName("nebflow.node.engine")

  /** 完成门腿 1 生效值（每次判定现读；无在线翻转路由 ⇒ 生产侧改 prop 需重启宿主）。 */
  private def bgGateHoldEnabled: Boolean =
    bgGateCompletionHold.getOrElse(nebflow.shared.Defaults.BgGateCompletionHold)

  /** 完成门腿 2 生效值（同上，现读）。 */
  private def reportGateHoldEnabled: Boolean =
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
  private[project] val recentNebulaDeliveries: Ref[IO, Map[(String, String), Long]] =
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

  /**
   * 启动节点（§2.1 创建即运行：入口节点由 NodeEdit 调；下游由投递 barrier 归零调）。
   * resume（crash-recovery 批 2026-09-07 D3）：boot sweep 认领的崩溃残留节点续跑——
   * Some 时跳过 buildInput 直接用 resume prompt（水合消息经 spawn 链 initialMessages
   * 注入），CAS 翻转/deps 闸门/barrier 复核/三表登记全套照走——恢复不绕过任何启动
   * 纪律（resume 节点崩溃前已过同一闸门，deliveredTo/deps 随 flow-map 持久，重走恒真）。
   */
  /**
   * @param resume      崩溃续跑上下文（Some ⇒ 首轮输入 = resume prompt，跳过 buildInput）
   * @param loopRework  **回边返工段**（nrloop 二期 2026-09-14；缺省 None = 全部既有调用
   *                    点零行为变化）：追加在 `buildInput` 全文之后，把 verifier 的打回
   *                    意见送进目标的重跑首轮。只在非 resume 分支生效（resume 路径的输入
   *                    由 resume prompt 独占），且只由 [[reloopTo]] 传入。
   */
  def startNode(
    nodeId: String,
    resume: Option[NodeEngine.ResumeContext] = None,
    loopRework: Option[String] = None
  ): IO[Unit] =
    store.getNode(nodeId).flatMap {
      case None => IO.unit
      case Some(node) =>
        node.status match
          // blocked 同样幂等跳过（§1.1：重跑同样 blocked；唯一出口 = NodeEdit 重激活）
          case NodeLifecycle.Running | NodeLifecycle.Completed | NodeLifecycle.Failed | NodeLifecycle.Cancelled |
              NodeLifecycle.Blocked =>
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
                // （fix a 启动判定完整性，20260903）。
                // R4（取消静默死锁修复批）：带「待承接」标记的 barrier 不放行——引擎自动
                // 摘除 cancelled 上游后 in 已 prune，若不设此闸，barrier 会以「少一轨」的
                // 输入正常启动、静默产出缺轨结论（设计 §6-R4 选择方案 4 的核心理由）。
                // 分发器承接动作落地（NodeEdit 任意实际变更）即清空标记，此处自然放行。
                if node.in.exists(up => !node.deliveredTo.contains(up)) || node.pendingSuccession.nonEmpty then IO.unit
                else
                  // 防御：wiring 空节点（无 task 无 in 无 deps）不空跑（防浪费 token）。
                  // 裁定①后降级为纵深防御**：零连接主责在创建侧输入侧下限
                  // （`NodeTools.createNode` 校验五：task ∨ in；2026-09-12 裁定后 out 可空置，
                  // 零连接闸已在改接侧删除），此处只兜
                  // 历史遗留数据（校验生效前落盘的零连接旧节点）与校验层外瞬态。
                  if node.status == NodeLifecycle.Wiring && node.task.isEmpty && node.in.isEmpty && node.deps.isEmpty
                  then IO.unit
                  else
                    // 链快照单点（链级抽象 P2 §9.2 项 4/5/7 + 性能纪律）：spawn 前取
                    // 一次，同时喂首条消息链头（buildInput）与会话身份
                    // （ToolContext.flowChainId）——同一快照两处同源，链头文本与会话
                    // 身份恒等（不重复全量重算分量）。resume 路径跳过 buildInput（用
                    // resume prompt）但身份照旧注入：快照重取（spawn 时刻语义），非
                    // 崩溃前残值。
                    val pastVerdictGate: IO[Unit] = chainContextOf(node.id).flatMap { chain =>
                      // resume：Some → 首轮输入 = resume prompt（任务全文经 NodeList(detail)
                      // 指引自读，BackoffSupervisor continue-prompt 直系演化）；None → buildInput。
                      // loopRework（回边返工段）：追加在全文之后——设计 §3.7 选项 (i)
                      // 「每轮 cold start 全文重注 + 返工意见」，语义确定优先。
                      val inputIO = resume.fold(
                        buildInput(node, chain).map(b => loopRework.fold(b)(r => s"$b\n\n$r"))
                      )(ctx => IO.pure(ctx.resumePrompt))
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
                        runIO.handleErrorWith { case _: NodeEngine.StartRaceLost =>
                          IO.unit
                        }
                      }
                    }
                    // verdict 闸收口（merge-verdict-gate 批 2026-09-12）——**单权威落点**：
                    // 全部启动入口（barrier 结算 settleTo / settleDeps 反结算 / 重激活补投
                    // redeliverInAndStart / D1 补投 / settleRunnableSweep 资格回扫 / crash-recovery
                    // 续跑 / 直接调用）都汇到本函数，闸在此处即对全部入口生效（与上方 deps/barrier
                    // 「单闸门自动保护全部启动入口」同款纪律）。命中 ⇒ 零动作返回：节点保持
                    // pending/wiring 可见、零副作用（见 mergeVerdictHoldersOf 注释）。
                    // mergefifo-engine 批 2026-09-13：**互斥闸**逐字接在同一收口点之后（先 verdict
                    // 后互斥，与设计件 §7.2 状态机同序；verdict 闸判据/留痕零改动）。
                    // engine-defects 批 #238（2026-09-15）：**闸面泛化**——判据不再按 merge 分叉
                    // ⇒ 非 merge 收口位（O-1 缝：`bpm-verify`(fail) → `bpm-report`(merge=False)）
                    // 同样被本收口点挡住（「不通过」结论不得作为正向交付补投下游）。旧形态
                    // （越闸启动）此后**结构性不可能**：若仍发生，[[logVerdictGateBreach]] 会
                    // 单发一行回退告警（不变式告警，与闸共用 staleVerdictUps 单点）。
                    mergeVerdictHoldersOf(node).flatMap { holders =>
                      if holders.nonEmpty then logVerdictGateHold("startNode", node, holders)
                      else
                        logVerdictGateBreach("startNode", node) *>
                          mergeMutexHoldersOf(node).flatMap { queued =>
                            if queued.nonEmpty then logMutexHold("startNode", node, queued)
                            else pastVerdictGate
                          }
                    }
            }
    }

  /**
   * 触发链 fork 化（根因报告 §6.1，trigger-chain-fix 批）：startNode 同步等到被
   * 启动节点整个会话终态（runWithAgent 内 IO.race(resultDeferred.get, …)），触发
   * 方在自身 fiber 上被下游会话质押——settleDeps 的 traverse_ 遍历因此把「同上游
   * N 依赖者」压成深度优先串行链（案例 A：队尾依赖者 pending 数小时，外观 pending
   * 永动），deliverOut/deliverFailed/deliverOutTo 则把上游完成 fiber 质押给下游
   * 全链（链深嵌套 SOE 风险面同源）。fork 后上游完成即同时唤醒全部依赖者；启动
   * 判定、幂等闸门、CAS 翻转守卫全在 startNode/spawnAndRun 内原样把关（同节点
   * 竞发由翻转守卫裁决，败方安静退出）。错误观测沿用 NodeTools.runDetached 形态
   * （handleErrorWith 留痕，含触发点上下文）。
   */
  private[project] def forkStart(what: String)(io: IO[Unit]): IO[Unit] =
    io
      .handleErrorWith(e =>
        logger.error(s"[$projectName] detached trigger '$what' failed: ${Option(e.getMessage).getOrElse(e.toString)}")
      )
      .start
      .void

  /**
   * 节点任务板头部块（TaskBoard 批 2 §3a）：三段式（①项目目标行 ②自己名下工单
   * 详情块 ③全板速览）——renderer.renderNodeInject 单点装配（上限/降级纪律在
   * renderer）。工单归属按 assignee=自身 node.id（§1d 权限矩阵的身份同源）；
   * ⚠node-done join 用 Flow Map 真实终态映射（store.snapshot 现读，nodeTerminalMap
   * 单点过滤）。无板 → ""（调用方不注空段）。
   */
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

  /**
   * 链快照单点（链级抽象 P2 · §9.2 性能纪律硬约束）：`FlowMapStore.topologicalChains`
   * 每次调用全量重算 `combinedNodes` + 弱连通分量（O(N+E)）——**只允许在节点 spawn
   * 时调用一次**，禁止进事件流/结果落盘/索引对账等热路径（逐节点调用放大为
   * O(N·E)，§9.2 :571）。
   * 本方法 = `chainIdOf` 判据单点的手工展开（同一次分量重算里同时取得 chainId /
   * title / memberCount 三值——若调 chainIdOf 再算一次分量即双重全量重算），口径
   * 与 chainIdOf 逐字同源（**chainmodel 批一 ① 起 = `chainIdIn`**：声明恒带、未声明者
   * 合并集派生分量成员数 ≥2 才带值，孤立单节点链 = 无链，与载荷 chainId 条件键恒同）。
   */
  private[project] def chainContextOf(nodeId: String): IO[Option[NodeEngine.NodeChainContext]] =
    store.combinedNodes.map { combined =>
      val chains = FlowMapStore.topologicalChains(combined.values)
      FlowMapStore
        .chainIdIn(combined, chains, nodeId)
        .map { cid =>
          val members = chains.find(_.id == cid).map(_.memberIds).getOrElse(Nil)
          NodeEngine.NodeChainContext(
            chainId = cid,
            title = FlowMapStore.chainTitle(members.flatMap(combined.get), cid),
            memberCount = members.size
          )
        }
    }

  /**
   * 构造节点输入：链上下文块（链头 + 文件名尾溯源提示段，仅本节点有链时注入）+ 自身
   * task 上下文 + 各上游 result（=== Node <name> === 头，§2.7）+ 终态申报协议脚注
   * （[[protocolFootnoteFor]]——**节点会话完整协议的单一权威面**，F8 收口
   * 2026-09-15：角色值域、blocked JSON 文法、verifier verdict 分支、未申报语义的
   * 唯一引擎承载文本；引擎编译、随任务输入组装面单点注入（本方法一个代码位覆盖
   * 全部节点 spawn 输入，含 loop 节点首轮）、零盘面依赖 ⇒ 抗 seed/运行面漂移。
   * 系统提示词面两处相关文本形态各异：agent 种子条件句（
   * `seed/agents/general/system.md:7-10`）自带完整规则文本（双角色值域/六类
   * blocked/ILLEGAL/verdict≠状态/8 拍提醒阶梯/未申报不终态化），且**无**指向本
   * 脚注的指针——这是 promptfix 批作者并存终态（「规则各留一份」，该条件句 =
   * 唯一 seed 面防线，已终态冻结），非缺陷；always-on belt 行（
   * `PromptSections.NodeSessionAlwaysOnSection`，段序 360、段长门 ≤400 B）=
   * belt+指针，指向工具 description 与本脚注。故「单一权威」的准确口径 =
   * **引擎贡献面**（引擎编译、随任务输入注入的提示词文本）唯一完整纪律，而
   * 非全局唯一——旧注释「system prompt 不含约定」的前提已被该结构取代（F8 判违：
   * 两处设计前提互相否证，本段即消解后的真实理由）。loop 侧同款：
   * `loopReworkInput` 复注同一 `ProtocolFootnote`（同会话返工轮再提醒，非第二份
   * 协议文本）。
   * 收敛裁定（作者 2026-09-07）：项目记忆=分发器配置知识——分发器建节点时把关键
   * 口径写进节点 task，节点侧不再注入记忆全文。节点上下文=task+上游结果+AGENTS.md；
   * 节点每 spawn 省一份记忆全文 token（多节点并行批次收益可观）；AGENTS.md 每 turn
   * 注入面不受影响。全局注入面（ContextRefresher）不含项目记忆——瘦身边界不变。
   * TaskBoard 批 2（§3a）：头部追加任务板块（在任务文本之前；无板/三段皆空 →
   * 不注空段）——「自身工单 id」是节点显式上报（决策 d）的使能器。
   * 链级抽象 P2（§9.2 项 7/8；提示段形态 = 2026-09-11 R-3 裁定后的文件名尾溯源）：
   * 链头 + 溯源提示段置于最前（首屏可见，agent 先
   * 看到链归属再读任务）；`chain` 参数由调用方（startNode）传入 spawn 时刻快照——
   * 无链（None）→ 整块不注入（不注空行/零占位）。
   */
  private[project] def buildInput(node: NodeDef, chain: Option[NodeEngine.NodeChainContext]): IO[String] =
    val ownTask = node.task.getOrElse("")
    // 链上下文块（有链才有）：链头一行 + 紧随的可复制文件名尾溯源提示段（文本尽量短）
    // ——两行同一块（块内不空行，「提示段紧随链头」字面口径），块间仍以空行分隔。
    val chainBlock: List[String] =
      chain.toList.map(c => NodeEngine.chainHeaderLine(c) + "\n" + NodeEngine.DocProvenanceBlock)
    taskBoardNodeBlock(node).flatMap { boardBlock =>
      node.in
        .traverse { upId =>
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
        }
        .map { upstream =>
          (chainBlock ++ List(boardBlock, ownTask).filter(_.nonEmpty) ++ upstream)
            .mkString("\n\n") + "\n\n" + NodeEngine.protocolFootnoteFor(Some(node.role))
        }
    }

  end buildInput

  private def spawnAndRun(
    node: NodeDef,
    inputText: String,
    resume: Option[NodeEngine.ResumeContext] = None,
    chain: Option[NodeEngine.NodeChainContext] = None
  ): IO[Unit] =
    val nodeId = node.id
    for
      agentOpt <- EntityLoader.loadAgent(node.agent)
      _ <- agentOpt match
        case None =>
          failNode(nodeId, s"agent '${node.agent}' not found in global library")
        case Some(entry) =>
          // 阶段 2b Plugins（§B.4 第 4 步，spawn 前执行）：① 解析 node.plugins
          // （不存在/被封禁/装载非法 → failNode，错误消息列明原因——分配失败是
          // 节点级失败，不静默降级；内容变更**不**拒启动，见 prepareNodePlugins）；
          // ② skill 全文读出 + ${SKILL_DIR} 替换
          // （SkillService.loadSkill 单点复用）组装 <injected-plugins> 块；
          // ③ MCP server 启动 + 引用记账（PluginMcpManager，启动失败 → failNode）。
          // 三步全部发生在状态翻转（status=Running）之前——失败路径零 running 残留。
          // panelscheme 批（2026-09-21）：§E.3 node.preset 静态消费**废止**——
          // 节点无自有方案，worker 模型 = 分发器当前方案（entry.toAgentDef 经
          // SchemePolicy 对 general 动态继承 project-dispatcher，装载期现读）。
          // resume（crash-recovery 批 D2/D3）：插件/装配链逐行复用，仅两处
          // 差异——sessionId 复用 sessionRef 旧 id（transcript 单文件续写 + F2 队列
          // 重放白捡，BackoffSupervisor respawn 同款先例）+ initialMessages 水合。
          val baseDef = entry.toAgentDef
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
    yield ()

    end for

  end spawnAndRun

  /**
   * LoopNode spawn（LoopNode 批 2026-09-06）：与 spawnAndRun 同骨——加载 worker/
   * verify 两 agent、准备插件、acquire 各会话 MCP grant、翻转 Running、spawn 双会话、
   * 驱动 loop。终态（PASS/failed/cancelled/blocked/达 K）由 runLoopNode 落终态化，
   * 会话/MCP/running 清理在 guarantee 内（裁定 B 双销毁不残留）。
   */
  private def spawnAndRunLoop(
    node: NodeDef,
    inputText: String,
    resume: Option[NodeEngine.ResumeContext] = None,
    chain: Option[NodeEngine.NodeChainContext] = None
  ): IO[Unit] =
    val nodeId = node.id
    val loopCfg = node.loop.get
    // crash-recovery 批裁定③：resume=Some 时双会话复用 sessionRef（worker）/
    // sessionRefVerify（verify）旧 id + 各自 transcript 水合，runLoopNode 从崩溃时
    // loopRound/loopPhase 断点续跑（worker→verify 迭代跨 kill -9 续接）。
    val workerSessionId = resume.fold(s"node-${java.util.UUID.randomUUID().toString.take(8)}")(_.sessionId)
    // verify 会话：恢复优先复用旧 id（D2）；无旧 ref（崩溃于 flip 前/历史数据）→ 新 id。
    val verifySessionId =
      resume.flatMap(_.verifySessionId).getOrElse(s"node-${java.util.UUID.randomUUID().toString.take(8)}")
    // projectRoot 解析（与 runWithAgent 同源单点：PathUtil.resolveNodeProjectRoot）
    val projectRoot =
      node.worktree match
        case Some(wt) if PathUtil.normalizeWorktree(wt).isLeft =>
          val pr = PathUtil.resolveNodeProjectRoot(workspace, node.worktree)
          logger.warnSync(
            s"Node '${node.name}' (${node.id}) has corrupt worktree value '$wt' — projectRoot fell back to workspace ($pr)"
          )
          pr
        case _ => PathUtil.resolveNodeProjectRoot(workspace, node.worktree)
    for
      workerAgentOpt <- EntityLoader.loadAgent(node.agent)
      verifyAgentOpt <- EntityLoader.loadAgent(loopCfg.verify)
      _ <- (workerAgentOpt, verifyAgentOpt) match
        case (None, _) => failNode(nodeId, s"worker agent '${node.agent}' not found in global library (loop node)")
        case (_, None) => failNode(nodeId, s"verify agent '${loopCfg.verify}' not found in global library (loop node)")
        case (Some(wEntry), Some(vEntry)) =>
          // panelscheme 批（2026-09-21）：worker/verify 均经 SchemePolicy 名称策略
          // ——worker（general）动态继承 project-dispatcher 当前方案（节点无自有
          // 设置）；verify agent 同一策略（§E.3 node.preset 静态消费已废止）。
          val workerBase = wEntry.toAgentDef
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
                      worker <- spawnLoopSession(
                        workerBase,
                        prepared,
                        wGrant,
                        workerSessionId,
                        node.name,
                        projectRoot,
                        initialMessages = resume.fold(List.empty[Message])(_.recoveredMessages),
                        // TaskBoard 批 2（§1d）：loop worker/verify 会话同属该
                        // loop 节点——flowNodeId 身份与普通节点同源（权限矩阵
                        // 同面：仅自己名下任务 status+note）。
                        flowNodeId = Some(nodeId),
                        // nrloop 一期（§3.2 透传表）：loop 双会话角色 = 该 loop
                        // 节点自身 role（与普通节点同源口径，worker/verify 同值）。
                        flowNodeRole = Some(node.role),
                        // D6 批 F1（G9 路径 a）：节点名随路注入（AskUser 归因）。
                        flowNodeName = Some(node.name),
                        // 链级抽象 P2（§9.2 项 5）：worker/verify 同属该 loop
                        // 节点 → 同一条链的同一快照（startNode 单点算出）。
                        flowChainId = chain.map(_.chainId)
                      )
                      verify <- spawnLoopSession(
                        verifyBase,
                        prepared,
                        vGrant,
                        verifySessionId,
                        s"${node.name}-verify",
                        projectRoot,
                        initialMessages = resume.fold(List.empty[Message])(_.verifyMessages),
                        flowNodeId = Some(nodeId),
                        flowNodeRole = Some(node.role),
                        flowNodeName = Some(node.name),
                        flowChainId = chain.map(_.chainId)
                      )
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
    yield ()
    end for
  end spawnAndRunLoop

  /**
   * panelscheme 批（2026-09-21）：§E.3 nodePresetDef（node.preset →
   * PresetResolver 静态消费）**整体移除**——节点侧静态覆盖废止，引擎解析不再
   * 读节点存储方案（NodeDef.preset 字段保留：存量数据零删除，仅显示/审计）。
   * 节点 worker/verify 模型 = 分发器当前方案，经 AgentEntry.toAgentDef 内
   * SchemePolicy 名称策略（general 动态继承 project-dispatcher）单点生效。
   */

  /**
   * node.plugins → 可分配能力（§B.4 第 4 步 ①②，feature flag §G.2 开关）：
   * flag off / 无分配 → 空 preparation（旧行为零变化）；解析失败 → Left
   * （failNode，错误含可行动指引）。
   *
   * **闸 B / C / E 的口径（2026-09-13 无审批批更新）**：
   * 本函数是 spawn / crash-recovery resume / loop 双会话的**装载门**，它判的**只能是
   * 内容面可用性**（`PluginRegistry.resolve` = 「存在 ∧ 装载合法 ∧ **未被封禁**」）。
   * 「在位即信任」后这里恒 `Right`——除非包不存在（`PLUGIN_NOT_FOUND`）或被封禁
   * （`PLUGIN_BLOCKED`）。**内容变更不再拒启动**（digest 漂移降级为非拦截可见性）。
   * **派发许可面（`PluginDispatchPolicy`）不在此判定** —— 按设计 S2/S3：派发许可的
   * 判定点是 NodeEdit 落库时刻，一次性；落库之后对该节点的开关变更无效。作者的关闭
   * 动作走派发面（`plugins.dispatch`），内容面不动 ⇒ 本函数对已派发节点**零影响**。
   * 要收回已派发的包，用封禁（`POST /api/plugins/:name/revoke`，会停用运行中 MCP）。
   * 闸 A（新派发）在 `NodeTools.dispatchFaceCheck`；闸 D 在 `PluginMcpManager.revalidate`。
   */
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
                Right(
                  NodeEngine.PluginPreparation(
                    injectedBlock =
                      if inner.isEmpty then ""
                      else s"<injected-plugins>\n${inner.mkString("\n\n")}\n</injected-plugins>",
                    mcpPlugins = defs.filter(_.mcpServers.nonEmpty),
                    builtinTools = defs.flatMap(_.toolsExtension).distinct
                  )
                )
              }
            end if
          }
    }

  /**
   * 逐 plugin 逐 skill 读 SKILL.md 全文（frontmatter 去除 + ${SKILL_DIR} 替换，
   * SkillService.loadSkill 单点复用——修复「模型自读拿不到替换」缺口，§B.4 ②）。
   * 文件不可读 → Left（节点级失败，不静默降级）。
   *
   * 数据根渲染（home 硬编码 → 运行时动态化批 2026-09-11）：注入块里的
   * `{{data_root}}` 在此渲染为实例数据根（PathUtil.substituteDataRoot，与
   * AgentCore.buildSystemPrompt / DispatcherContextCatalog.render 同一实现）——
   * 隔离实例的节点看到的是**本实例**的 home 路径，而非固定 `~/.nebflow`。
   */
  private def injectedPluginBlock(defn: PluginRegistry.PluginDef): IO[Either[String, String]] =
    if defn.skills.isEmpty then IO.pure(Right(""))
    else
      defn.skills
        .traverse { sk =>
          SkillService.loadSkill(sk.path).flatMap {
            case Some(content) =>
              IO.pure[Either[String, String]](Right(s"""<plugin name="${defn.name}" skill="${sk.name}">\n${PathUtil
                  .substituteDataRoot(content.content)}\n</plugin>"""))
            case None =>
              IO.pure[Either[String, String]](
                Left(
                  s"Plugin '${defn.name}' skill '${sk.id}' file unreadable: ${sk.path} — allocation refused (no silent degradation)"
                )
              )
          }
        }
        .map { blocks =>
          blocks.find(_.isLeft) match
            case Some(Left(err)) => Left(err)
            case _ => Right(blocks.collect { case Right(b) => b }.mkString("\n\n"))
        }

  /**
   * 内容面运行时重验（§B.5 信任联动，ProjectActor.TtlTick 30s 驱动）：停用**被封禁**
   * （deny-list）或已从注册表移除的运行中 plugin MCP + 对持有会话发系统提醒。
   * flag off → no-op（总闸 `plugins.enabled=false` 的既有停飞行为不回退）。
   * 内容变更（digest 漂移）**不停飞**（2026-09-13 无审批批，见
   * [[PluginMcpManager.revalidate]]）。可见性批（2026-09-10 P1 静默缩容）：重验即插件
   * 重扫完成点——同处聚合输出一次装载健康摘要（拒载/封禁清单 + 内容变更清单；干净场景
   * 零输出、同状态去重）。
   */
  def revalidatePluginTrust(): IO[Unit] =
    PluginsConfig.enabled.flatMap {
      case false => IO.unit
      case true =>
        resources.pluginMcp
          .revalidate(
            PluginRegistry.scan(),
            (sessionId, text) =>
              resources.agentRegistry.get.flatMap { reg =>
                reg.get(sessionId).map(_.ref) match
                  case Some(ref) =>
                    (ref ! AgentCommand.ImmediateInput(text, source = Some("system"), fromUser = false)).void
                  case None => IO.unit // 会话已终结——提醒无投递面，server 已停即足够
              }
          )
          .void *>
          PluginRegistry
            .logHealthSummary("rescan")
            .handleErrorWith(e => logger.warn(s"plugin health summary failed: ${e.getMessage}"))
    }

  private def runWithAgent(
    node: NodeDef,
    baseDef: nebflow.agent.AgentDef, // panelscheme 批：经 SchemePolicy 的 worker def（继承分发器当前方案）
    inputText: String,
    sessionId: String,
    prepared: NodeEngine.PluginPreparation,
    grant: PluginMcpManager.Grant,
    /**
     * crash-recovery 批 D3：Some = 崩溃恢复续跑（initialMessages=水合 transcript，
     * inputText=resume prompt）；None = 新鲜启动（行为与本批前逐字节一致）。
     */
    resume: Option[NodeEngine.ResumeContext] = None,
    /**
     * 链级抽象 P2（§9.2 项 4）：节点所属链快照（spawnAndRun 由 startNode 单点算好
     * 传入——与首条消息链头同源）。None = 无链（孤立单节点分量/不在双区）。
     */
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
            s"Node '${node.name}' (${node.id}) has corrupt worktree value '$wt' — projectRoot fell back to workspace ($pr)"
          )
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
            (
              s.copy(nodes =
                s.nodes.updated(
                  nodeId,
                  fresh.copy(
                    status = NodeLifecycle.Running,
                    startedAt = Some(now),
                    sessionRef = Some(sessionId),
                    reportPendingSince = None,
                    reportReminderCount = 0,
                    destroyAt = None
                  )
                )
              ),
              NodeEngine.FlipOutcome.Done
            )
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
            flipped.nodes
              .get(nodeId)
              .traverse_(runningDef =>
                emitWithChain("nodeUpdated", nodeId, NodePayload.buildNodeJson(runningDef, System.currentTimeMillis()))
              )
        case NodeEngine.FlipOutcome.LostRace =>
          // CAS 败方：按 sig 身份只回滚自己的登记（不得误删赢家的条目），抛
          // StartRaceLost 终止本 fiber 的 for 推导（否则败方继续 spawn = 双会话，
          // M1 demo 实证 6 路并发 6 会话）。异常由 startNode 的 handleErrorWith
          // 精准吞掉——对全部调用方呈安静败方语义。nodeSessions 对称移除
          // （NodeMessage 注入链，防泄漏僵尸映射）。
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
            FlowMapEventLog.append(workspace, projectName, nodeId, "start-aborted", s"start aborted: $reason") *>
            IO.raiseError(new RuntimeException(s"Node '$nodeName' ($nodeId) start aborted — $reason"))
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
          // nrloop 一期（设计 §3.2 透传表，B1 第一段）：节点角色随 spawn 注入 →
          // AgentCore 透传 ToolContext.flowNodeRole——node_report 值域分化
          // （verifier ⇒ pass/fail；task ⇒ finish）与 ProtocolFootnote 角色分支的
          // 引擎侧判据来源。task 也显式带值（判据侧 normalize 宽容，但显式 = 可审计）。
          flowNodeRole = Some(node.role),
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
          // B5 缺口②（作者 2026-09-17 M-1 裁定「会话启动即 `cd` 座椅」，选项①）：
          // 会话初始 cwd = 座椅（= 本节点的 projectRoot；worktree 节点 ⇒
          // `<ws>/.nebflow/worktrees/<name>`，由上方 resolveNodeProjectRoot 单点解析）。
          // 非 worktree 节点该值与沙箱根同源 ⇒ 行为逐字节不变。座椅目录缺失时
          // 不在此兜底——交 ShellSession.resolveCwdOrFail 显式失败（fail-closed）。
          sessionCwd = Some(projectRoot),
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
                disarmCap *> {
                  // 兜底计时器：等待期内无任何后台完成复检达 bgWaitCapMs →
                  // failed 注明。措辞注意：completeNode 以 message.contains
                  // ("cancelled") 分流 cancelNode，本文案不得含该词。
                  val capBody: IO[Unit] =
                    IO.sleep(bgWaitCapMs.millis) *>
                      FlowMapEventLog.append(
                        workspace,
                        projectName,
                        nodeId,
                        "bg-wait-timeout",
                        s"background wait cap (${bgWaitCapMs / 1000}s) hit with ${waiting.size} task(s) pending — finalizing failed"
                      ) *>
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
                        .complete(
                          Left(
                            FailOutcome(
                              s"background task wait cap exceeded (${bgWaitCapMs / 1000}s): still waiting for " +
                                waiting.map(t => s"'${t.description}' (${t.jobId})").mkString(", ") +
                                " — node finalized as failed by the background-completion gate; " +
                                "the background job(s) keep running and their completion notification may arrive at a finalized session"
                            )
                          )
                        )
                        .attempt
                        .void
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
              end bgFailureMessage
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
                                  workspace,
                                  projectName,
                                  nodeId,
                                  "bg-wait",
                                  s"completion held: ${waiting.size} background task(s) pending: " +
                                    waiting.map(t => s"'${t.description}'").mkString(", ")
                                ) *>
                                  logger.info(
                                    s"Node '$nodeName' ($nodeId) turn completed with ${waiting.size} background " +
                                      "task(s) still running — holding completion until they finish"
                                  ) *>
                                  // bg-wait 标注写点（僵尸收敛批）：首个 hold 期置位
                                  // bgWait——NodePayload 条件字段随之带，前端可标
                                  // 「等待后台任务」（与真僵尸区分，作者首要缺口）。
                                  setNodeBgWait(nodeId, waiting)
                              ) *>
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
                                        workspace,
                                        projectName,
                                        nodeId,
                                        "bg-released",
                                        "all background task(s) finished — completion released"
                                      ) *>
                                        setNodeBgWait(nodeId, Nil)
                                    ) *>
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
                                        "existing blocked chain"
                                    )
                                  ) *>
                                    releaseNow
                              }
                            else
                              disarmCap *> holdEmitted.set(false) *>
                                setNodeBgWait(nodeId, Nil) *>
                                resultDeferred
                                  .complete(Left(FailOutcome(bgFailureMessage(failures, messages))))
                                  .attempt
                                  .void
                                  .as(Behaviors.stopped)
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
                          "(crashed or stopped unexpectedly) — finalizing node as failed"
                      )
                      setNodeBgWait(nodeId, Nil) *>
                        resultDeferred
                          .complete(
                            Left(
                              FailOutcome(
                                s"node session '$sessionId' terminated without a terminal event (session crashed or was stopped unexpectedly)"
                              )
                            )
                          )
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
        case _ => false)
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
              FlowMapEventLog.append(
                workspace,
                projectName,
                nodeId,
                "bg-harvest",
                // R-5 / 类⑦（stuck 自动恢复批 P2 附加小项，本板 #98 同源）：**补 cause**。
                // 此前该行是「无原因的终态化留痕」——读者只能看到「被回收」而不知
                // 「因何终态」，正是 #98「重启后 bg 会话回收把在飞节点判 cancelled
                // （无 result/无通知）→ 下游 barrier 静默死锁」取证时的第一个盲区。
                // cause 词表 = 与本次终态化同一个判据（挂起 / 取消 / 归还能力失败），
                // 由桥消息文本单点派生（不改 AgentEvent 形态，与 CancelSource 同纪律）。
                s"node finalized (${NodeEngine.finalizeCause(fo.message)}) — reclaimed bg session '$sessionId'; " +
                  s"cause: ${fo.message.take(160)}"
              ))
              .handleErrorWith(e =>
                logger.warn(s"Node '$nodeName' ($nodeId) bg-harvest reclaim failed: ${e.getMessage}")
              )
              .void
              .start *> IO.unit
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
      _ <-
        if suspended then IO(suspendLog(sessionId, nodeId))
        else
          eventResult match
            case Right(messages) =>
              val text = extractLastAssistantText(messages)
              // blocked 结构化信号批（20260909 spec §5.2 #5①②；同日作者裁定泛化
              // NodeReport 统一三语义）：会话完成时点 drain 登记表——工具申报（协议
              // 事实）优先于文本锚定（降级面，行为零变化）。时序上必在 bg 闸之后
              // （resultDeferred 完成即等待集已放行）；drain take-and-remove 单次消费，
              // cancelled/failed 路径不消费（残留由 guarantee 内 cleanupRunTables 的
              // remove 对称清理）。
              // engine-defects 批 #239①（2026-09-15）：drain 取走即移除，而终态写有多条
              // **拒写**路径 ⇒ 旧口径「拒写 = 申报永久丢失、零痕迹」。修法 = 消费点持
              // 终态写的**落地判词**（landed），未落地者把申报全文补偿写回审计流
              // （见 [[compensateUnconsumedReport]]）；take-and-remove 单次消费语义不动。
              // #239② ⑤-(2)：改经 consumeReport 包装 ⇒ 终态写**抛异常**时同一补偿点照常触发
              // （`landed=false` 方向），异常原样重抛（控制流零变化）。
              NodeReportRegistry.drain(sessionId).flatMap { declared =>
                consumeReport(nodeId, sessionId, declared)(completeNodeR(nodeId, text, declared)).void
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
                // 🔴 **硬不变量（判据 M8 / 作者三答 4）**：L3 硬恢复中间态
                // （`deferDetach = true`）⇒ 级联**强制 false**——见
                // [[NodeEngine.l3CascadeAllowed]]。本腿请求的级联旗标**由该函数显式驱动**
                // （不是字面量，故「去掉这条绑定」= 可失败变异）：本腿唯一终态写路径是本
                // 受守卫的单节点写入口（不经 `cancelNodes` 链级/级联腿），
                // 「请求级联 ∧ L3 中间态」= 越权 ⇒ 守卫立即抛出：
                // resume 复活时拓扑必须完整，提前连带取消下游 = 恢复路径被掐死。
                // 注意**两条腿共用本调用点**：L3（deferDetach=true）与 L3 之外的
                // watcher giveUp / AgentControl / 面板 NodeCancel / 父会话级联
                // （deferDetach=false ⇒ 该腿允许级联 ⇒ 不抛、走既有单节点写路径）。
                val cascadeRequested = NodeEngine.l3CascadeAllowed(deferDetach)
                cancelNodeGuarded(
                  nodeId,
                  reason,
                  CancelSource.classify(reason),
                  detach = !deferDetach,
                  notify = !deferDetach,
                  cascadeRequested = cascadeRequested,
                  l3Intermediate = deferDetach
                )
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
          scheduleDestroy(
            nodeId,
            List(sessionId),
            eventResult match
              case Right(_) => "completed"
              case Left(fo) => NodeEngine.finalizeCause(fo.message)
          )
    yield ()).guarantee {
      // 任意退出路径（正常/崩溃/异常/cancel）三表对称移除——与既有清理段
      // （agentRegistry/running/nodeSessions :947-953）同点幂等（先到先清）。
      runSig.get.flatMap {
        case Some(cancelSig) => cleanupRunTables(nodeId, sessionId, cancelSig)
        case None => IO.unit // cancelSig 未创建 = 未登记任何表 → 无可清
      }
    }
  end runWithAgent

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
    val nodeTargets = edges.filterNot(OutEdge.isLoopEdge).map(_.to).filterNot(_ == OutEdge.NebulaTarget).distinct
    val signalTargets = failedEdges
      .filter(e => e.to != OutEdge.NebulaTarget && e.mode == OutEdge.Signal)
      .map(_.to)
      .distinct
    val mergeFallback: IO[Unit] = nodeTargets.traverse_(t =>
      store.findNode(t).flatMap {
        case Some(target) if MergeNodePolicy.haltsOnFailure(target) =>
          mergeBlockedByUpstreamFailure(target, node, err)
        case _ => IO.unit
      }
    )
    val nebulaIO = failedEdges.filter(_.to == OutEdge.NebulaTarget) match
      case Nil => IO.unit
      case nes if nes.exists(_.mode == OutEdge.Result) =>
        // notifybatch 批（2026-09-18，M-2）：失败腿同走打包入口（决策②异常类一并合并）。
        enqueueRootNotify(s"[Node '${node.name}' failed]\n$err", node.name, "failed", Some(node.id))
      case _ => markNebulaDelivered(node.id)
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
   * ② emitEvent nodeUpdated + FlowMapEventLog "merge-blocked" + deliverToNebula
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
  private[project] def dedupeNebulaDelivery(identity: String, status: String): IO[Boolean] =
    IO(System.currentTimeMillis()).flatMap { now =>
      recentNebulaDeliveries.modify { m =>
        val live = m.view.filter { case (_, ts) => now - ts < NodeEngine.NebulaDedupWindowMs }.toMap
        live.get((identity, status)) match
          case Some(_) => (live, true)
          case None => (live.updated((identity, status), now), false)
      }
    }

  /**
   * V8: 写 nebulaDeliveredAt 记账（活动区优先，归档区兜底——TTL 归档的
   * 未投递节点同样要记账，否则扫描每次重启都重投）。
   */
  private[project] def markNebulaDelivered(nodeId: String): IO[Unit] =
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
                // N3（2026-09-12 批）：**再按 mode 收窄**——本扫描的补投调用不经
                // nebulaDelivery/deliverFailed 的 mode 分支（直调 deliverToNebula）⇒
                // 只看门集会把 mode=signal 的**出口标记**边当成通知声明补投（bare
                // "Nebula" 出口标记节点若在 markNebulaDelivered 之前崩溃/重启，30s 扫描
                // 就把它投到 root，绕过「默认不升根」）。补 e.mode == Result 这一合取项。
                n.out.exists(e =>
                  e.to == OutEdge.NebulaTarget &&
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
              *> markNebulaDelivered(n.id)
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
   * 汇总文本每批唯一，若走 deliverToNebula 会与 60s 前一批汇总同 key 相撞被
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
        )) *> stale.traverse_(n => markNebulaDelivered(n.id))
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

  private case class FailOutcome(message: String)

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

  val NebulaDedupWindowMs: Long = NodeEngineContract.NebulaDedupWindowMs

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
