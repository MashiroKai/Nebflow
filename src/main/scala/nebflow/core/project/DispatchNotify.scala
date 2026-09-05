package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import nebflow.core.NebflowLogger

/**
 * dispatch-notify —— 节点终态结果回流任务分发器的单一通知通道（2026-09-05 批）。
 *
 * 目标语义（作者 2026-09-05 20:53 触发任务）：显式开启 notifyDispatcher 标志的节点
 * 到达终态后，其结果触发 project-dispatcher 新会话——复用既有触发链
 * （TriggerDispatcher → spawn/注入分发器单次会话），使分发器能基于节点结果继续
 * 规划（扩拓扑 / 建合并节点收口 / 收口 / 无动作判定）。
 *
 * == 单一通知入口（可扩展，监督化批共用） ==
 * 全部通知走唯一入口 [[notifyTerminal(reason, node)]]，带原因码 NotifyReason
 * （completion / failed / blocked / …）。本批只接线 completion；failed/blocked
 * 只留接口不接线（notifyTerminal 对未接线 reason 零动作——接口层锁死「组合不双
 * 触发」：blocked 重入由 FeedbackRouter 独占，监督化批未来直接复用本入口）。
 *
 * == 不占 out 边（设计约束①） ==
 * out 边限 1 条且已被合并节点纪律占用；回流是独立信号通道：
 *  - 信号面 = NodeDef.notifyDispatcher 布尔标志（NodeEdit 按需开启，默认关）+
 *    ProjectActor.TriggerDispatcher 消息（与 Task 工具/Mail(→project) 同链路）；
 *  - 结果全文不进通知文本——走既有投递面：全文已持久化于
 *    `<workspace>/.nebflow/results/<nodeId>.md`，通知文本只带
 *    节点名/终态/原因码 + NodeList(detail) 读取指引，分发器按单次会话语义自读。
 *    分发器会话忙时注入排队由 ActiveDispatcher.pendingInjected（turn 边界消费）
 *    既有机制承载——与 deliverDispatcherOutputToNebula 先例同款「绕过账本」。
 *
 * == 防循环（设计约束③） ==
 *  - 同节点去重：notifySentAt 持久标记（NodeDef 字段，flow-map.json 落盘，
 *    重启不重触发）；进程内 inFlight Ref 原子占位关掉「直触发 vs 周期补投扫描」
 *    竞态窗口。
 *  - 投递时机 = tell-then-mark（V8 nebulaDeliveredAt 同款 at-least-once：崩溃在
 *    tell 与 mark 之间 → 重启后补投扫描重触发，宁重复不丢失）。
 *  - 链级通知预算：默认 5 次/进程（DefaultBudget；构造可注入）。耗尽 → 该节点
 *    转 blocked（待办语义，ttlExpireAt=None）+ 升级 Nebula——对齐监督树
 *    「处理不了就升级」；不走 FeedbackRouter（重入恰恰是预算要挡的东西，
 *    与 mergeBlockedByUpstreamFailure「不经 FeedbackRouter」同构）。
 *    预算为进程内计数（FeedbackRouter §3.2 先例：限流器是成本保护不是安全机制，
 *    重启重置可接受——持久去重由 notifySentAt 承担，重开后已通知节点不会重触发）。
 *  - 收敛保证：分发器因通知新建的节点默认不继承 notifyDispatcher（NodeEdit
 *    缺省关，显式传参才开）——通知链天然深度 ≤1，预算是纵深防御兜底。
 *
 * == 触发时机与补投（设计约束④） ==
 * completedNode 在终态落库+投递+deps 结算完成后调用本入口（1 行挂接）；宿主
 * 重启后未投递通知（notifySentAt 为空）由 ProjectActor.TtlTick(30s) 挂
 * [[redeliver]] 周期补投（参照 redeliverUnconsumedNebulaResults 形态）。
 */
final class DispatchNotify(
  store: FlowMapStore,
  workspace: String,
  projectName: String,
  /** 预算耗尽升级通道（NodeEngine 注入 deliverToNebula(_, _, "blocked")，
    * 与 FeedbackRouter.escalate 同款）。 */
  escalate: (String, String) => IO[Unit],
  /** blocked 转换后 WS nodeUpdated（NodeEngine 注入 emitUpdated，前端卡片刷新）。 */
  emitUpdated: NodeDef => IO[Unit],
  /** 触发通道（默认经 ProjectRuntimeRegistry → ProjectActor.TriggerDispatcher；
    * 测试注入 stub 捕获通知文本）。 */
  trigger: String => IO[Unit],
  /** 链级通知预算（默认 5；测试注入小值验证耗尽路径）。 */
  budgetMax: Int = DispatchNotify.DefaultBudget
):
  private val logger = NebflowLogger.forName("nebflow.project.dispatch-notify")

  /** 进程内通知预算计数（已触发次数）。 */
  private val budgetUsed: Ref[IO, Int] = Ref.unsafe[IO, Int](0)

  /** 进程内在飞占位（同节点并发直触发/补投竞态关闭；一次性，通知语义=每节点至多一次）。 */
  private val inFlight: Ref[IO, Set[String]] = Ref.unsafe[IO, Set[String]](Set.empty)

  /** 单一通知入口（可扩展）：终态节点 → 分发器通知。
    *
    * 未接线 reason（failed/blocked/…）零动作（debug 留痕）——单入口接口层保证
    * 未来监督化批接入时与既有触发器（FeedbackRouter 重入）组合不双触发。
    * 全路径 best-effort：内部错误只 WARN 不上抛（通知失败不拖垮完成链）。 */
  def notifyTerminal(node: NodeDef, reason: NotifyReason): IO[Unit] =
    val guarded: IO[Boolean] =
      if reason != NotifyReason.Completion then
        logger.debug(
          s"dispatch-notify: reason '${NotifyReason.code(reason)}' reserved (not wired this batch) — node '${node.name}' (${node.id}) no-op")
          .as(false)
      else if !node.notifyDispatcher then IO.pure(false)
      else if node.status != NodeLifecycle.Completed then IO.pure(false)
      else IO.pure(true)
    guarded.flatMap {
      case false => IO.unit
      case true =>
        // 原子占位：同节点第二路（补投扫描 vs 直触发）在此关掉
        inFlight.modify(s => if s.contains(node.id) then (s, false) else (s + node.id, true)).flatMap {
          case false => IO.unit
          case true =>
            budgetUsed.modify(n => if n >= budgetMax then (n, false) else (n + 1, true)).flatMap {
              case true =>
                // tell-then-mark（at-least-once）：触发 → 持久标记 → 审计
                trigger(notifyTaskText(node, reason))
                  .handleErrorWith(e => logger.warn(s"dispatch-notify trigger failed for node '${node.name}' (${node.id}): ${e.getMessage}").void) *>
                  markSent(node.id) *>
                  FlowMapEventLog.append(workspace, projectName, node.id, "dispatch-notify",
                    s"triggered: ${NotifyReason.code(reason)} → dispatcher (node '${node.name}', budget $budgetMax)") *>
                  logger.info(s"Project '$projectName' node '${node.name}' (${node.id}) completed — dispatcher notified (reason=${NotifyReason.code(reason)})")
              case false =>
                escalateBudgetExhausted(node, reason)
            }
        }
    }

  /** 补投扫描（TtlTick 30s 周期挂点）：扫活动区「notifyDispatcher ∧ completed ∧
    * 未标记 ∧ 有结果」节点逐条过单一入口（幂等：入口内占位+标记二次把关）。
    * 返回候选数（>0 时调用方记 info）。重启后本扫描覆盖「终态已落库但通知未
    * 触发/未标记」的全部欠账。 */
  def redeliver(): IO[Int] =
    store.snapshot.flatMap { s =>
      val pending = s.nodes.values
        .filter(n =>
          n.notifyDispatcher &&
            n.status == NodeLifecycle.Completed &&
            n.notifySentAt.isEmpty &&
            n.result.exists(_.trim.nonEmpty))
        .toList
      pending.traverse_(n => notifyTerminal(n, NotifyReason.Completion)).as(pending.size)
    }

  /** 预算耗尽：节点转 blocked（待办语义）+ 升级 Nebula。
    * 事务内现读 fresh（R2 纪律）：仍 completed 且开启标志才转换；blockCount 不增
    * （非节点自报轮次，与 mergeBlockedByUpstreamFailure 同构）；不经 FeedbackRouter
    * （预算耗尽时重入恰是被挡对象，直投升级通道）。 */
  private def escalateBudgetExhausted(node: NodeDef, reason: NotifyReason): IO[Unit] =
    val feedback = BlockedFeedback(
      category = "other",
      detail = s"dispatch-notify 通知预算耗尽（${budgetMax} 次/进程）——自动回流停止，升级人工处置",
      suggestion = "人工处置该节点结果，或确认链路收敛后重新规划（新节点默认不继承 notifyDispatcher）")
    store.mutate { st =>
      st.nodes.get(node.id) match
        case Some(fresh) if fresh.status == NodeLifecycle.Completed && fresh.notifyDispatcher =>
          st.copy(nodes = st.nodes.updated(node.id, fresh.copy(
            status = NodeLifecycle.Blocked,
            result = Some(BlockedReader.render(feedback)),
            blockedFeedback = Some(feedback),
            completedAt = fresh.completedAt, // 工作完成时刻保留
            ttlExpireAt = None)))            // 待办语义（永不显示过期）
        case _ => st // 状态已变（并发 abandon/重激活）→ 拒写（R2 竞态纪律）
    }.flatMap { s =>
      s.nodes.get(node.id) match
        case Some(bn) if bn.status == NodeLifecycle.Blocked =>
          val text = s"[Node '${bn.name}' blocked]\n项目「$projectName」节点「${bn.name}」(${bn.id}) 的 dispatch-notify 通知预算耗尽" +
            s"（${budgetMax} 次/进程）——已转 blocked 并停止自动回流，等待处置。\n" +
            s"节点结果全文仍可经 NodeList(detail=\"${bn.id}\") 读取。"
          emitUpdated(bn) *>
            FlowMapEventLog.append(workspace, projectName, node.id, "dispatch-notify",
              s"budget exhausted ($budgetMax) → node '${node.name}' converted blocked + escalated to Nebula") *>
            escalate(text, bn.name) *>
            logger.warn(s"Project '$projectName' dispatch-notify budget exhausted ($budgetMax) — node '${bn.name}' (${bn.id}) → blocked + escalated")
        case _ =>
          logger.warn(s"Project '$projectName' node '${node.name}' changed state before budget-escalation — skipped (fresh-read discipline)").void
    }

  /** notifySentAt 持久标记（活动区优先，归档区兜底——与 markNebulaDelivered 同款双区）。 */
  private def markSent(nodeId: String): IO[Unit] =
    store.getNode(nodeId).flatMap {
      case Some(_) =>
        store.mutate { s =>
          s.nodes.get(nodeId) match
            case Some(n) => s.copy(nodes = s.nodes.updated(nodeId, n.copy(notifySentAt = Some(System.currentTimeMillis()))))
            case None    => s
        }.void
      case None =>
        store.mutateArchive { a =>
          a.nodes.get(nodeId) match
            case Some(n) => a.copy(nodes = a.nodes.updated(nodeId, n.copy(notifySentAt = Some(System.currentTimeMillis()))))
            case None    => a
        }.void
    }

  /** 通知任务文本（TriggerDispatcher 任务全文）：节点名/终态/原因码 + 结果全文
    * 读取指引（全文不进通知——走 results/<nodeId>.md 既有投递面）。 */
  private def notifyTaskText(node: NodeDef, reason: NotifyReason): String =
    s"""[dispatch-notify] 节点 '${node.name}' (${node.id}) 到达终态：${node.status}（reason=${NotifyReason.code(reason)}，project=$projectName）。
       |结果全文已持久化：NodeList(detail="${node.id}", project=$projectName) 或 REST results 端点按需读取。
       |请先 NodeList 读现状，再基于该结果决定后续：扩拓扑 / 建合并节点收口 / 收口 / 判定无需动作。
       |无需回报——拓扑与状态已落 Flow Map。""".stripMargin

object DispatchNotify:
  /** 链级通知预算默认值（设计约束③建议值）。 */
  val DefaultBudget: Int = 5

  /** 默认触发通道：ProjectRuntimeRegistry → ProjectActor.TriggerDispatcher
    * （与 Task 工具/Mail(→project) 同链路；rootSessionId 用挂载根——系统发起，
    * 与 ReenterDispatcher 同规）。best-effort：未挂载/无 actor 只 WARN。 */
  def defaultTrigger(projectName: String, rootSessionId: String): String => IO[Unit] = text =>
    ProjectRuntimeRegistry.get(projectName).flatMap {
      case Some(rt) =>
        rt.actorRef match
          case Some(ref) =>
            (ref ! ProjectActor.ProjectCommand.TriggerDispatcher(text, rootSessionId)).void
          case None =>
            NebflowLogger.forName("nebflow.project.dispatch-notify")
              .warn(s"Project '$projectName' has no actorRef — dispatch-notify skipped")
      case None =>
        NebflowLogger.forName("nebflow.project.dispatch-notify")
          .warn(s"Project '$projectName' not mounted — dispatch-notify skipped")
    }

  /** NodeEngine 装配口（挂接最小化：engine 侧仅此一个 val）。 */
  def forEngine(
    store: FlowMapStore,
    workspace: String,
    projectName: String,
    rootSessionId: String,
    escalate: (String, String) => IO[Unit],
    emitUpdated: NodeDef => IO[Unit],
    budgetMax: Int = DefaultBudget
  ): DispatchNotify =
    new DispatchNotify(store, workspace, projectName, escalate, emitUpdated,
      defaultTrigger(projectName, rootSessionId), budgetMax)

/** 通知原因码（单一入口 dispatchNotify(reason, node) 的 reason 维度）。
  * 本批只接线 Completion；Failed/Blocked 预留接口（blocked 由 FeedbackRouter
  * 重入独占——组合不双触发），监督化批按需扩展。 */
enum NotifyReason:
  case Completion
  case Failed
  case Blocked

object NotifyReason:
  /** 原因码字符串（事件留痕/通知文本/审计共用）。 */
  def code(r: NotifyReason): String =
    r match
      case NotifyReason.Completion => "completion"
      case NotifyReason.Failed     => "failed"
      case NotifyReason.Blocked    => "blocked"
