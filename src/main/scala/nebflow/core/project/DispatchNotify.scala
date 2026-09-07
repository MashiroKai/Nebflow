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
 * 2026-09-07 批（失败事件投递分发器路由，设计 20260907_node-failure-event-
 * dispatcher-routing.md，作者 09:24 三裁定冻结）：接线 Failed reason——节点 failed
 * 终态化后分发器被**及时**触发（不依赖 out 接线形态、不依赖 Nebula 人肉巡检）。
 * failed 通知**不查 notifyDispatcher flag**：flag 语义保持「completion 回流」不变；
 * failed 是异常低频事件，项目的拓扑主人对项目内任何失败都应知情（「无人订阅的
 * 失败」正是 2026-09-07 今晨宿主误杀事故的教训——节点悬空/僵尸收敛后分发器与
 * Nebula 双不知情）。防循环口径：failed 通知 → 分发器处置 → 新节点/重激活节点再
 * failed → 再通知，这不是循环而是**重试回路**（每次通知对应一次真实的新失败），
 * 回路护栏由窗口熔断承担（见下），不需要 opt-in。
 *
 * == 单一通知入口（可扩展，监督化批共用） ==
 * 全部通知走唯一入口 [[notifyTerminal(reason, node)]]，带原因码 NotifyReason
 * （completion / failed / blocked / …）。completion（2026-09-05 批）与 failed
 * （2026-09-07 批）已接线；blocked 仍预留零动作（接口层锁死「组合不双触发」：
 * blocked 重入由 FeedbackRouter 独占——同一节点终态唯一，failed 走 deliverFailed →
 * notifyTerminal(Failed)，blocked 走 blockedNode → FeedbackRouter.route，双通道在
 * 接口层结构性互斥）。
 *
 * == 不占 out 边（设计约束①） ==
 * out 边限 1 条且已被合并节点纪律占用；回流是独立信号通道：
 *  - 信号面 = NodeDef.notifyDispatcher 布尔标志（completion 专用；NodeEdit 按需
 *    开启，默认关）+ ProjectActor.TriggerDispatcher 消息（与 Task 工具/Mail(→project)
 *    同链路）；failed 通知无 per-node 开关（见类头）。
 *  - 结果全文不进通知文本——走既有投递面：全文已持久化于
 *    `<workspace>/.nebflow/results/<nodeId>.md`，通知文本只带节点名/终态/原因码 +
 *    NodeList(detail) 读取指引，分发器按单次会话语义自读。分发器会话忙时注入排队
 *    由 ActiveDispatcher.pendingInjected（turn 边界消费）既有机制承载——与
 *    deliverDispatcherOutputToNebula 先例同款「绕过账本」。
 *
 * == 防循环（设计约束③；两 reason 各自护栏、分账互不挤占） ==
 *  - 同节点去重：notifySentAt 持久标记（NodeDef 字段，flow-map.json 落盘，重启不
 *    重触发）+ 进程内 inFlight 占位关掉「直触发 vs 周期补投扫描」竞态窗口。占位在
 *    尝试结束后**释放**（attempt.guarantee）：持久去重由 notifySentAt 承担（guard
 *    链已查空标记），inFlight 只管并发在飞窗口——failed 节点被 NodeEdit 重激活
 *    （notifySentAt 随之清零，2026-09-07 批 failed 重激活放开）后再次真失败时，
 *    占位不得永久挡住第二次通知（重试回路非循环口径，设计 §2.2）。
 *  - 投递时机 = tell-then-mark（V8 nebulaDeliveredAt 同款 at-least-once：崩溃在
 *    tell 与 mark 之间 → 重启后补投扫描重触发，宁重复不丢失）。
 *  - 预算分账：completion 预算（budgetUsed/budgetMax）与 failed 预算
 *    （failedBudgetUsed/failedBudgetMax，默认同为 5/回合）**独立计数**——正常回流
 *    用量不挤占失败通知，反之亦然。回合边界 = 每轮 redeliver 扫描（TtlTick 30s）。
 *    耗尽 → 节点保持终态（completed/failed 不翻转）+ 落 notifySentAt 止重扫 +
 *    single-flight 升级 Nebula（notice 语义非 blocked——前端不得标 BLOCKED）。
 *    预算为进程内计数（FeedbackRouter §3.2 先例：限流器是成本保护不是安全机制，
 *    重启重置可接受——持久去重由 notifySentAt 承担，重开后已通知节点不会重触发）。
 *  - failed 版项目级窗口熔断（FeedbackRouter 同款形态，参数同值 10min/5 次/30min
 *    cooldown）：10min 滚动窗口内第 5 次 failed 通知 → 合并单条升级 Nebula + 进入
 *    30min cooldown；冷却期内后续 failed Suppress（**不 markSent** → 留在 redeliver
 *    候选集 → 冷却结束下轮扫描自然补投，延迟触发而非永久丢失）。窗口判定在预算
 *    之前（Suppress/CooldownOn 不耗预算不标记）；窗口/cooldown 是进程内状态，重启
 *    重置可接受（同预算口径）。
 *  - 收敛保证：分发器因通知新建的节点默认不继承 notifyDispatcher（NodeEdit 缺省
 *    关，显式传参才开）——completion 回流链深 ≤1 不变；failed 链深无上限但受窗口
 *    熔断兜底。
 *
 * == 触发时机与补投（设计约束④） ==
 * completion：completedNode 在终态落库+投递+deps 结算完成后调用本入口（1 行挂接）。
 * failed：NodeEngine.deliverFailed 尾部挂接（2026-09-07 批单点收口——failNode 与
 * autoFailDeadRunning 两口必经 deliverFailed；通知在 out 结算**之后**发出，分发器
 * spawn/注入时读到的 Flow Map 是结算后状态，拓扑判断不失真）。宿主重启后未投递
 * 通知（notifySentAt 为空）由 ProjectActor.TtlTick(30s) 挂 [[redeliver]] 周期补投
 * （参照 redeliverUnconsumedNebulaResults 形态）。
 */
final class DispatchNotify(
  store: FlowMapStore,
  workspace: String,
  projectName: String,
  /** 预算耗尽/窗口熔断升级通道（NodeEngine 注入 deliverToNebula(_, _, "notice")
    * ——notice 语义非 blocked：节点保持终态，前端不可标 BLOCKED）。 */
  escalate: (String, String) => IO[Unit],
  /** 预留（2026-09-06 起不再在预算耗尽路径调用——节点不再转 blocked，无需 WS 刷新）。 */
  emitUpdated: NodeDef => IO[Unit],
  /** 触发通道（默认经 ProjectRuntimeRegistry → ProjectActor.TriggerDispatcher；
    * 测试注入 stub 捕获通知文本）。 */
  trigger: String => IO[Unit],
  /** completion 链级通知预算（默认 5；每回合边界重置，见 [[redeliver]] 头注）。 */
  budgetMax: Int = DispatchNotify.DefaultBudget,
  /** failed 链级通知预算（默认 5/回合，与 completion 分账——设计 §3）。 */
  failedBudgetMax: Int = DispatchNotify.DefaultBudget,
  /** failed 通知滚动窗口/冷却时长/阈值（默认抄 FeedbackRouter：10min/30min/5；
    * 测试注入 tiny 值验证状态机，同 FeedbackRouter 先例）。 */
  failedWindowMs: Long = DispatchNotify.FailedWindowMs,
  failedCooldownMs: Long = DispatchNotify.FailedCooldownMs,
  failedWindowThreshold: Int = DispatchNotify.FailedWindowThreshold
):
  private val logger = NebflowLogger.forName("nebflow.project.dispatch-notify")

  /** completion 进程内通知预算计数（当前回合已触发次数；回合边界重置）。 */
  private val budgetUsed: Ref[IO, Int] = Ref.unsafe[IO, Int](0)

  /** failed 进程内通知预算计数（与 completion 分账；回合边界重置）。 */
  private val failedBudgetUsed: Ref[IO, Int] = Ref.unsafe[IO, Int](0)

  /** 进程内在飞占位（同节点并发直触发/补投竞态关闭；尝试结束后释放——持久去重由
    * notifySentAt 承担，占位只管并发在飞窗口，见类头注）。 */
  private val inFlight: Ref[IO, Set[String]] = Ref.unsafe[IO, Set[String]](Set.empty)

  /** completion 预算耗尽监督通知 single-flight 守卫（进程内仅首次耗尽向 Nebula 发
    * 一条，后续耗尽只留痕不重报——避免每 30s 重扫反复轰炸）。 */
  private val budgetEscalated: Ref[IO, Boolean] = Ref.unsafe[IO, Boolean](false)

  /** failed 预算耗尽 single-flight（与 completion 分账——两路耗尽各报一次）。 */
  private val failedBudgetEscalated: Ref[IO, Boolean] = Ref.unsafe[IO, Boolean](false)

  /** failed 通知项目级窗口状态（FeedbackRouter.GuardState 同款形态：滚动窗口时间戳
    * + cooldown 截止）。进程内状态，重启重置可接受（限流器是成本保护不是安全机制）。 */
  private case class FailedGuardState(failedAt: List[Long] = Nil, cooldownUntil: Long = 0L)
  private val failedGuard: Ref[IO, FailedGuardState] = Ref.unsafe[IO, FailedGuardState](FailedGuardState())

  /** failed 窗口裁决（FeedbackRouter 三态同构）。 */
  private enum FailedWindowVerdict:
    /** 窗口内未达阈值 → 走预算 + 触发。 */
    case Proceed
    /** cooldown 期内 → 不触发不标记（冷却结束 redeliver 补投，不丢失）。 */
    case Suppress
    /** 本次触达阈值 → 合并单条升级 + 置 cooldown（不标记，冷却结束补投）。 */
    case CooldownOn

  /** 单一通知入口（可扩展）：终态节点 → 分发器通知。
    *
    * guard 链按 reason 分流（2026-09-07 批起 failed 接线；blocked 仍接口层预留零
    * 动作——组合不双触发由接口层锁定，blocked 重入由 FeedbackRouter 独占）。全路径
    * best-effort：内部错误只 WARN 不上抛（通知失败不拖垮终态链）。 */
  def notifyTerminal(node: NodeDef, reason: NotifyReason): IO[Unit] =
    val guarded: IO[Boolean] = reason match
      case NotifyReason.Completion =>
        // flag 语义保持 completion 回流专用（设计 §2.2）；持久去重按 store 现读
        // 判定（见 [[markerEmpty]]——传入快照可能是标记前的）。
        if !(node.notifyDispatcher && node.status == NodeLifecycle.Completed) then IO.pure(false)
        else markerEmpty(node.id)
      case NotifyReason.Failed =>
        // 不查 flag（设计 §2.2 四理由：failed 是异常低频事件，拓扑主人全知情）；
        // 持久去重同样按 store 现读判定。
        if node.status != NodeLifecycle.Failed then IO.pure(false)
        else markerEmpty(node.id)
      case NotifyReason.Blocked =>
        logger.debug(
          s"dispatch-notify: reason '${NotifyReason.code(reason)}' reserved (FeedbackRouter owns blocked reentry) — node '${node.name}' (${node.id}) no-op")
          .as(false)
    guarded.flatMap {
      case false => IO.unit
      case true =>
        // 原子占位：同节点第二路（补投扫描 vs 直触发）在此关掉
        inFlight.modify(s => if s.contains(node.id) then (s, false) else (s + node.id, true)).flatMap {
          case false => IO.unit
          case true =>
            val attempt: IO[Unit] = reason match
              case NotifyReason.Failed => failedAttempt(node)
              case _                   => completionAttempt(node)
            // 尝试结束即释放占位：持久去重由 notifySentAt 承担（guard 已查空标记），
            // 占位只关并发在飞窗口。failed 节点经 NodeEdit 重激活（notifySentAt 清零）
            // 后再次真失败时，占位不得永久挡住第二次通知（重试回路非循环，设计 §2.2）。
            attempt
              .guarantee(inFlight.update(_ - node.id))
              .handleErrorWith(e =>
                logger.warn(s"dispatch-notify attempt failed for node '${node.name}' (${node.id}): ${e.getMessage}").void)
        }
    }

  /** 持久去重判定：notifySentAt 按 store 现读（活动/归档双区，与 [[markSent]] 对称）。
    * 调用方传入的 NodeDef 可能是标记落库**前**的快照——inFlight 在尝试结束后释放
    * （见类头注），去重权威必须是 store 里的 notifySentAt，不能依赖传入快照或进程
    * 内占位。节点已消失（双区皆无）→ false（无从标记，不触发）。 */
  private def markerEmpty(nodeId: String): IO[Boolean] =
    store.findNode(nodeId).map {
      case Some(fresh) => fresh.notifySentAt.isEmpty
      case None        => false
    }

  /** completion 投递尝试（2026-09-05 批原路径，行为零变化）：预算 → tell-then-mark；
    * 耗尽 → escalateBudgetExhausted。 */
  private def completionAttempt(node: NodeDef): IO[Unit] =
    budgetUsed.modify(n => if n >= budgetMax then (n, false) else (n + 1, true)).flatMap {
      case true =>
        // tell-then-mark（at-least-once）：触发 → 持久标记 → 审计
        trigger(notifyTaskText(node, NotifyReason.Completion))
          .handleErrorWith(e => logger.warn(s"dispatch-notify trigger failed for node '${node.name}' (${node.id}): ${e.getMessage}").void) *>
          markSent(node.id) *>
          FlowMapEventLog.append(workspace, projectName, node.id, "dispatch-notify",
            s"triggered: completion → dispatcher (node '${node.name}', budget $budgetMax)") *>
          logger.info(s"Project '$projectName' node '${node.name}' (${node.id}) completed — dispatcher notified (reason=completion)")
      case false =>
        escalateBudgetExhausted(node)
    }

  /** failed 投递尝试（2026-09-07 批，设计 §3 护栏链）：窗口裁决在前（Suppress/
    * CooldownOn 不耗预算不标记——冷却结束 redeliver 补投不丢失）→ 预算 →
    * tell-then-mark；预算耗尽 → failed 版升级（节点保持 failed + markSent 止重扫 +
    * single-flight notice）。 */
  private def failedAttempt(node: NodeDef): IO[Unit] =
    failedGuard.modify { g =>
      val now = System.currentTimeMillis()
      val recent = g.failedAt.filter(t => now - t <= failedWindowMs) :+ now
      if now < g.cooldownUntil then
        (g.copy(failedAt = recent), FailedWindowVerdict.Suppress)
      else if recent.size >= failedWindowThreshold then
        (g.copy(failedAt = recent, cooldownUntil = now + failedCooldownMs), FailedWindowVerdict.CooldownOn)
      else
        (g.copy(failedAt = recent), FailedWindowVerdict.Proceed)
    }.flatMap {
      case FailedWindowVerdict.Proceed =>
        failedBudgetUsed.modify(n => if n >= failedBudgetMax then (n, false) else (n + 1, true)).flatMap {
          case true =>
            trigger(failedNotifyTaskText(node))
              .handleErrorWith(e => logger.warn(s"dispatch-notify trigger failed for node '${node.name}' (${node.id}): ${e.getMessage}").void) *>
              markSent(node.id) *>
              FlowMapEventLog.append(workspace, projectName, node.id, "dispatch-notify",
                s"triggered: failed → dispatcher (node '${node.name}', failed budget $failedBudgetMax)") *>
              logger.info(s"Project '$projectName' node '${node.name}' (${node.id}) failed — dispatcher notified (reason=failed)")
          case false =>
            escalateFailedBudgetExhausted(node)
        }
      case FailedWindowVerdict.Suppress =>
        // 冷却期内：不触发、不 markSent——节点留在 redeliver 候选集，冷却结束后下轮
        // 扫描自然补投（延迟触发而非永久丢失，设计 §3）。
        logger.warn(s"Project '$projectName' node '${node.name}' (${node.id}) failed during failure-notify cooldown — suppressed (unmarked; redelivered after cooldown)")
      case FailedWindowVerdict.CooldownOn =>
        // 窗口阈值触达：合并单条升级（含最新失败信息，FeedbackRouter §3.2 同语义）+
        // 置 cooldown；本次不触发分发器、不 markSent（冷却结束补投）。
        val text =
          s"[dispatch-notify] 项目「$projectName」${failedWindowMs / 60000} 分钟内节点失败通知达 $failedWindowThreshold 次——进入 ${failedCooldownMs / 60000} 分钟冷却：" +
            s"期间 failed 通知暂停触发分发器（未通知节点保持未标记，冷却结束自动补投），冷却结束自动恢复。\n" +
            s"最新失败节点「${node.name}」(${node.id})：${node.result.map(_.take(300)).getOrElse("(无错误文本)")}"
        FlowMapEventLog.append(workspace, projectName, node.id, "dispatch-notify",
          s"failure window threshold ($failedWindowThreshold in ${failedWindowMs / 60000}min) → cooldown ${failedCooldownMs / 60000}min on, dispatcher notify paused, supervisor notice (merged, latest node '${node.name}')") *>
          escalate(text, node.name) *>
          logger.warn(s"Project '$projectName' failure-notify window threshold reached (${failedWindowThreshold}/${failedWindowMs / 60000}min) — cooldown ${failedCooldownMs / 60000}min on, merged supervisor notice sent (latest node '${node.name}' ${node.id})")
    }

  /** 补投扫描（TtlTick 30s 周期挂点）：扫「completion 候选 = notifyDispatcher ∧
    * completed ∧ 未标记 ∧ 有结果」+「failed 候选 = failed ∧ 未标记 ∧ 有结果（无
    * flag 条件，设计 §3）」逐条过单一入口（幂等：入口内占位+标记二次把关）。
    * 返回候选总数（>0 时调用方记 info）。重启后本扫描覆盖「终态已落库但通知未
    * 触发/未标记」的全部欠账；窗口 Suppress 的 failed 节点保持未标记留在候选集，
    * 冷却结束后由本扫描自然补投。
    *
    * == 回合边界重置预算（2026-09-06 作者拍板；2026-09-07 批起两 reason 分账各自重置）==
    * 每轮扫描开启新一轮预算：先把 budgetUsed / failedBudgetUsed 清零——预算不再按
    * 进程生命周期全局单调计数，而是按「分发器回合/通知识别链」计数、回合结束
    * （本处=每轮 TtlTick 扫描边界）后重置。深度 ≤1 纵深防御的本意保留；既已落
    * notifySentAt 的节点不受重置影响（已出候选集）。 */
  def redeliver(): IO[Int] =
    budgetUsed.set(0) *> failedBudgetUsed.set(0) *> store.snapshot.flatMap { s =>
      val completions = s.nodes.values
        .filter(n =>
          n.notifyDispatcher &&
            n.status == NodeLifecycle.Completed &&
            n.notifySentAt.isEmpty &&
            n.result.exists(_.trim.nonEmpty))
        .toList
      val failures = s.nodes.values
        .filter(n =>
          n.status == NodeLifecycle.Failed &&
            n.notifySentAt.isEmpty &&
            n.result.exists(_.trim.nonEmpty))
        .toList
      (completions.traverse_(n => notifyTerminal(n, NotifyReason.Completion)) *>
        failures.traverse_(n => notifyTerminal(n, NotifyReason.Failed))).as(completions.size + failures.size)
    }

  /** completion 预算耗尽（2026-09-06 作者拍板语义修正）：**不再翻转节点状态**——completedNode
    * 已先 deliverOut+settleDeps，下游已按完成推进，此时把节点转 blocked 会污染
    * 完成事实 + 切断分发器回流（本批缺陷根因）。改为三动作：
    *   1. 落 notifySentAt（复用 [[markSent]]）→ 节点退出 [[redeliver]] 候选集
    *      （notifyDispatcher∧Completed∧notifySentAt.isEmpty∧result.nonEmpty），
    *      否则保持 completed 会被 TtlTick 每 30s 反复重扫 → 反复 escalate；
    *   2. 进程内 single-flight：仅首次预算耗尽向 Nebula 发一条非阻塞监督通知
    *      （notice 语义，非 blocked——eventType=blocked 会让前端标 BLOCKED，
    *      作者看到「完成节点被标阻塞」）；后续耗尽只留痕不重报；
    *   3. FlowMapEventLog 审计一条（budget exhausted + kept completed）。
    * 全部 best-effort（通知失败不拖垮完成链）。 */
  private def escalateBudgetExhausted(node: NodeDef): IO[Unit] =
    markSent(node.id) *>
      budgetEscalated.modify(b => if b then (b, false) else (true, true)).flatMap {
        case false =>
          logger.info(
            s"Project '$projectName' dispatch-notify budget exhausted ($budgetMax) for node '${node.name}' (${node.id}) — node kept completed; supervisor notice already sent (single-flight)")
        case true =>
          val text = s"[dispatch-notify] 预算耗尽（${budgetMax} 次/回合）——项目「$projectName」节点「${node.name}」(${node.id}) 已完成但未自动通知分发器，请经 NodeList(detail=\"${node.id}\") 复核。"
          FlowMapEventLog.append(workspace, projectName, node.id, "dispatch-notify",
            s"budget exhausted ($budgetMax) → node '${node.name}' kept completed, notifySentAt set, supervisor notice (single-flight)") *>
            escalate(text, node.name) *>
            logger.warn(
              s"Project '$projectName' dispatch-notify budget exhausted ($budgetMax) — node '${node.name}' (${node.id}) kept completed, supervisor notice sent")
      }

  /** failed 预算耗尽（2026-09-07 批，与 completion 版 [[escalateBudgetExhausted]]
    * 同构、single-flight 分账）：节点**保持 failed**（终态不翻转）+ markSent 退出
    * redeliver 候选集 + 首次耗尽发一条 notice 监督通知（notice 语义非 blocked）+
    * 审计。 */
  private def escalateFailedBudgetExhausted(node: NodeDef): IO[Unit] =
    markSent(node.id) *>
      failedBudgetEscalated.modify(b => if b then (b, false) else (true, true)).flatMap {
        case false =>
          logger.info(
            s"Project '$projectName' dispatch-notify failed-budget exhausted ($failedBudgetMax) for node '${node.name}' (${node.id}) — node kept failed; supervisor notice already sent (single-flight)")
        case true =>
          val text = s"[dispatch-notify] failed 通知预算耗尽（${failedBudgetMax} 次/回合）——项目「$projectName」节点「${node.name}」(${node.id}) failed 但未自动通知分发器，请经 NodeList(detail=\"${node.id}\") 复核。"
          FlowMapEventLog.append(workspace, projectName, node.id, "dispatch-notify",
            s"failed budget exhausted ($failedBudgetMax) → node '${node.name}' kept failed, notifySentAt set, supervisor notice (single-flight)") *>
            escalate(text, node.name) *>
            logger.warn(
              s"Project '$projectName' dispatch-notify failed-budget exhausted ($failedBudgetMax) — node '${node.name}' (${node.id}) kept failed, supervisor notice sent")
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

  /** 通知任务文本（completion 版，TriggerDispatcher 任务全文）：节点名/终态/原因码 +
    * 结果全文读取指引（全文不进通知——走 results/<nodeId>.md 既有投递面）。 */
  private def notifyTaskText(node: NodeDef, reason: NotifyReason): String =
    s"""[dispatch-notify] 节点 '${node.name}' (${node.id}) 到达终态：${node.status}（reason=${NotifyReason.code(reason)}，project=$projectName）。
       |结果全文已持久化：NodeList(detail="${node.id}", project=$projectName) 或 REST results 端点按需读取。
       |请先 NodeList 读现状，再基于该结果决定后续：扩拓扑 / 建合并节点收口 / 收口 / 判定无需动作。
       |无需回报——拓扑与状态已落 Flow Map。""".stripMargin

  /** failed 版通知任务文本（2026-09-07 批设计 §2.3 + 作者 09:24 裁定③——failed 可
    * 重激活重跑：reactivate 为首选处置，换名新建降为换基线/重派备选）：err 摘要 +
    * 四动作清单 + 操作知识（分发器是单次会话无记忆，每次都要被告知）。 */
  private def failedNotifyTaskText(node: NodeDef): String =
    val errSummary = node.result.map(_.take(500)).getOrElse("(无错误文本)")
    s"""[dispatch-notify] 节点 '${node.name}' (${node.id}) failed（reason=failed，project=$projectName）。
       |错误摘要：$errSummary
       |结果全文：NodeList(detail="${node.id}", project=$projectName)。
       |处置指引（failed 为终态：可经 NodeEdit 重激活复活重跑；不可直接发消息——NodeMessage 对终态节点拒收）：
       |1. 瞬时/基础设施类失败（LLM 超时、agent 基础设施故障等，同参数值得重试）→ NodeEdit 编辑该节点任意实际变更（task/description/in/out/deps 任一实际改动）触发 reactivate 重跑——原节点复活（status 回 wiring/pending），in/out 拓扑保持，轮次计数清零；
       |2. 需换基线/重派（任务定义或执行形态需实质调整）→ NodeEdit 新建承接节点（建议命名 <原名>-retry 或语义新名），接原拓扑位置（in 同源、out 同目标）；原 failed 节点留作审计，勿删改；
       |3. 任务无意义/无法修复 → NodeEdit abandon=true 标记放弃；
       |4. 需人工/外部条件 → 在你的最终输出中写明上报内容（自动投递 Nebula）。
       |无需回报——拓扑与状态已落 Flow Map。""".stripMargin

object DispatchNotify:
  /** 链级通知预算默认值（设计约束③建议值；completion/failed 同值独立分账）。 */
  val DefaultBudget: Int = 5

  /** failed 通知滚动窗口/冷却/阈值默认值（2026-09-07 批）：直接抄 FeedbackRouter
    * 同名参数（10min/30min/5）——项目内护栏心智一致（设计 §10.2 建议、作者 09:24
    * 裁定②确认）。 */
  val FailedWindowMs: Long = FeedbackRouter.WindowMs
  val FailedCooldownMs: Long = FeedbackRouter.CooldownMs
  val FailedWindowThreshold: Int = FeedbackRouter.WindowThreshold

  /** 预算耗尽/窗口熔断监督通知的 eventType（notice 语义，非 blocked——eventType=blocked
    * 会让前端标 BLOCKED，作者看到「终态节点被标阻塞」）。NodeEngine 注入 escalate
    * 闭包按本常量送 deliverToNebula。 */
  val NoticeEventType: String = "notice"

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
  * completion（2026-09-05 批）与 failed（2026-09-07 批）已接线；blocked 预留接口
  * （blocked 由 FeedbackRouter 重入独占——组合不双触发），监督化批按需扩展。 */
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
