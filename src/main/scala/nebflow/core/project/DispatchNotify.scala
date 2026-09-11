package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import nebflow.core.NebflowLogger

import scala.concurrent.duration.*

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
 * == cancelled 回流（取消静默死锁修复批 2026-09-10，作者裁定 R1 方案 2） ==
 * `NotifyReason.Cancelled` 与 failed **完全对称**：不查 notifyDispatcher flag、持久
 * 去重同款（notifySentAt + inFlight 占位）、tell-then-mark、补投扫描同款候选、预算
 * 耗尽「终态不翻转 + markSent + single-flight notice 升级」。
 * **账务独立**：`cancelledBudgetUsed/cancelledBudgetMax` 与
 * `cancelledGuard`（10min/5 次/30min cooldown）**各自一份**，不与 failed 合账
 * （理由与风暴面评估见 [[cancelledAttempt]] 头注）。
 * 通知文本必须与 failed 区分（cancelled 不可重激活，指引 = 承接/改接/abandon，
 * 见 [[cancelledNotifyTaskText]]）。挂接点 = `NodeEngine.cancelNode` 尾部
 * （终态写点单点，覆盖桥取消 / NodeCancel / dead-session reap 三口）。
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
 * spawn/注入时读到的 Flow Map 是结算后状态，拓扑判断不失真）。D5 起（20260908
 * wf1cde §3）failed 零结算：普通下游停等 pending/wiring、merge 下游转 blocked，
 * 通知文本尾部附停等等待者清单（E-③，[[waitingSuccessors]]）。宿主重启后未投递
 * 通知（notifySentAt 为空）由 ProjectActor.TtlTick(30s) 挂 [[redeliver]] 周期补投
 * （参照 redeliverUnconsumedNebulaResults 形态）。
 *
 * == 打包窗口与预算并账（Q4，2026-09-11 任务分发器收件规则批） ==
 * 守卫链（flag/状态/持久去重/占位/窗口熔断）全部保持**逐件**裁决不变；变化只在
 * 「投递 + 预算计数」这一步：通过守卫的件先进入按 reason 分账的打包缓冲
 * （[[NotifyBatch]]），**首件到达起算 5s 滚动窗口**（[[windowMs]]，默认
 * [[DefaultWindowMs]]，不随新件延长 ⇒ 单件延迟上界 5s），窗口结束时把本窗口的件
 * **合并为一次注入**（[[flushBatch]]），并按**一次注入**计一个预算单位。
 * 由此：一轮 TtlTick 内的密集扇出（N 件终态）只花 1 个预算单位、只注入 1 条分发器
 * 任务（**#62「第 6 件起静默失联」结构性消除**）；预算是成本保护（限流器口径），
 * 窗口只改「怎么算一次」，不改任何护栏阈值。窗口是进程内状态：崩溃即丢，但件未
 * `markSent` ⇒ 仍在 [[redeliver]] 候选集，重启后补投（at-least-once 不破）。
 * `windowMs <= 0` ⇒ 同步逐条触发（窗口引入前的行为，测试接缝）。
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
  failedWindowThreshold: Int = DispatchNotify.FailedWindowThreshold,
  /** cancelled 链级通知预算（默认 5/回合；**与 failed 独立分账**——R1 账务裁定，
    * 理由见类头「cancelled 回流」节）。 */
  cancelledBudgetMax: Int = DispatchNotify.DefaultBudget,
  /** cancelled 通知滚动窗口/冷却时长/阈值（与 failed **各自独立**：引擎批量取消
    * 的风暴不得触发 failed 的 cooldown 而把真正的高优先级失败通知一并静音）。 */
  cancelledWindowMs: Long = DispatchNotify.FailedWindowMs,
  cancelledCooldownMs: Long = DispatchNotify.FailedCooldownMs,
  cancelledWindowThreshold: Int = DispatchNotify.FailedWindowThreshold,
  /** 打包窗口（Q4，2026-09-11 任务分发器收件规则批）：同一 reason 的件在窗口内
    * 到达即**合并为一次注入**，并按「一次注入」计一个预算单位（[[flushBatch]]）。
    * 默认 [[DispatchNotify.DefaultWindowMs]]（5s，写死）；**≤0 关闭打包**（同步逐条
    * 触发 = 窗口引入前的行为，测试接缝）。窗口是**滚动**的：首件到达起算，不随
    * 新件延长 ⇒ 单件延迟上界 = 窗口长度。分账口径不变（completion / failed /
    * cancelled 各一份缓冲 + 各自预算）。 */
  windowMs: Long = DispatchNotify.DefaultWindowMs
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

  /** cancelled 进程内通知预算计数（R1：**与 failed 独立分账**；回合边界重置）。 */
  private val cancelledBudgetUsed: Ref[IO, Int] = Ref.unsafe[IO, Int](0)

  /** cancelled 通知项目级窗口状态（与 failedGuard **各自独立**——R1 账务裁定）。 */
  private case class CancelledGuardState(cancelledAt: List[Long] = Nil, cooldownUntil: Long = 0L)
  private val cancelledGuard: Ref[IO, CancelledGuardState] = Ref.unsafe[IO, CancelledGuardState](CancelledGuardState())

  /** cancelled 预算耗尽 single-flight（与 completion/failed 三方分账）。 */
  private val cancelledBudgetEscalated: Ref[IO, Boolean] = Ref.unsafe[IO, Boolean](false)

  /** 打包缓冲（Q4）：按 reason 分账（completion / failed / cancelled 各一份），
    * 每份 = 本窗口已入队但尚未注入的 (节点, 通知文本)。内存态：崩溃即丢，节点
    * 未 markSent ⇒ 仍在 [[redeliver]] 候选集，重启后补投（at-least-once 不破）。 */
  private case class NotifyBatch(entries: List[(NodeDef, String)] = Nil)
  private val batches: Ref[IO, Map[String, NotifyBatch]] = Ref.unsafe[IO, Map[String, NotifyBatch]](Map.empty)

  /** 缓冲分账键 = reason 码（与预算/窗口分账同维度）。 */
  private def batchKey(reason: NotifyReason): String = NotifyReason.code(reason)

  /** 预算计数单点（按 reason 分账）。 */
  private def budgetUsedFor(reason: NotifyReason): Ref[IO, Int] = reason match
    case NotifyReason.Failed    => failedBudgetUsed
    case NotifyReason.Cancelled => cancelledBudgetUsed
    case _                      => budgetUsed

  /** 预算上限单点（按 reason 分账）。 */
  private def budgetMaxFor(reason: NotifyReason): Int = reason match
    case NotifyReason.Failed    => failedBudgetMax
    case NotifyReason.Cancelled => cancelledBudgetMax
    case _                      => budgetMax

  /** 预算耗尽升级单点（按 reason 分派到既有三个 escalate*，节点保持终态语义各自不变）。 */
  private def escalateBudgetExhaustedFor(node: NodeDef, reason: NotifyReason): IO[Unit] = reason match
    case NotifyReason.Failed    => escalateFailedBudgetExhausted(node)
    case NotifyReason.Cancelled => escalateCancelledBudgetExhausted(node)
    case _                      => escalateBudgetExhausted(node)

  /** 合并通知文本：单件逐字不变（零回归）；多件 = 一次注入的合并件。 */
  private def mergedText(texts: List[String]): String =
    texts match
      case Nil       => ""
      case List(one) => one
      case many =>
        // 与桥侧合并摘要（ProjectActor.batchSummaryLine）同形：本批 N 件触发 + 各件清单。
        s"[dispatch-notify] 本批 ${many.size} 件触发（同打包窗口合并为一次注入）：\n\n" + many.mkString("\n\n---\n\n")

  /** Q4 入队（打包窗口唯一入口，替代原来的立即 trigger）。
    *  - `windowMs <= 0`：同步立即 flush（逐条等价旧行为）。
    *  - 否则：入缓冲 + 首件到达起算滚动窗口；窗口结束时 [[flushBatch]] 合并注入。
    * 同节点重复入队（redeliver 撞窗口等）在缓冲层去重——同一件不得重复注入。 */
  private def enqueueNotify(reason: NotifyReason, node: NodeDef, text: String): IO[Unit] =
    val key = batchKey(reason)
    if windowMs <= 0 then
      batches
        .update(m => m.updated(key, NotifyBatch(m.getOrElse(key, NotifyBatch()).entries :+ (node, text))))
        .flatMap(_ => flushBatch(reason))
    else
      batches
        .modify { m =>
          val b = m.getOrElse(key, NotifyBatch())
          if b.entries.exists(_._1.id == node.id) then (m, false)
          else (m.updated(key, b.copy(entries = b.entries :+ (node, text))), b.entries.isEmpty)
        }
        .flatMap { first =>
          if first then (IO.sleep(windowMs.millis) *> flushBatch(reason)).start.void else IO.unit
        }

  /** Q4 打包投递（窗口结束的唯一出口）：本窗口的件**合并为一次** `trigger`，并按
    * **一次注入**计一个预算单位（Q4「打包后按一次注入计数」）——一轮扫描内的密集
    * 扇出不再从第 6 件起被预算静默丢弃（#62）。
    *
    * 预算耗尽：逐件走 [[escalateBudgetExhaustedFor]]（节点保持终态 + markSent 退出
    * 候选集 + single-flight 监督通知）——账务口径与窗口引入前一致。
    * 投递成功后逐件 `markSent`（tell-then-mark，per-node 持久去重语义不变）。 */
  private def flushBatch(reason: NotifyReason): IO[Unit] =
    val key = batchKey(reason)
    batches.modify(m => (m - key, m.getOrElse(key, NotifyBatch()).entries)).flatMap {
      case Nil => IO.unit
      case entries =>
        budgetUsedFor(reason).modify(n => if n >= budgetMaxFor(reason) then (n, false) else (n + 1, true)).flatMap {
          case true =>
            val nodes = entries.map(_._1)
            trigger(mergedText(entries.map(_._2)))
              .handleErrorWith(e =>
                logger.warn(s"dispatch-notify trigger failed (reason=${key}, batch=${entries.size}): ${e.getMessage}").void) *>
              nodes.traverse_(node =>
                markSent(node.id) *>
                  FlowMapEventLog.append(workspace, projectName, node.id, "dispatch-notify",
                    s"triggered: $key → dispatcher (node '${node.name}', batch=${entries.size}, budget ${budgetMaxFor(reason)})")) *>
              logger.info(
                s"Project '$projectName' dispatch-notify batch flushed: reason=$key batch=${entries.size} nodes=${nodes.map(_.name).mkString(",")}")
          case false =>
            entries.traverse_((node, _) => escalateBudgetExhaustedFor(node, reason))
        }
    }

  /** cancelled 窗口裁决（与 [[FailedWindowVerdict]] 同构，独立实例）。 */
  private enum CancelledWindowVerdict:
    case Proceed
    case Suppress
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
      case NotifyReason.Cancelled =>
        // R1（取消静默死锁修复批）：与 failed 完全对称——不查 flag。取消同样是
        // 「无人订阅的异常终态」：节点 out 被自动摘除、下游 barrier 带「待承接」
        // 标停等，拓扑主人不知情就无人承接（本批三例 27.8–38.8 min 静默死锁的
        // 直接成因）。持久去重同款按 store 现读判定。
        if node.status != NodeLifecycle.Cancelled then IO.pure(false)
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
              case NotifyReason.Failed    => failedAttempt(node)
              case NotifyReason.Cancelled => cancelledAttempt(node)
              case _                      => completionAttempt(node)
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

  /** completion 投递尝试（2026-09-05 批原路径；Q4 起改为**打包入队**——投递与预算
    * 计数在窗口结束时由 [[flushBatch]] 单点完成：切了窗口后行为逐字等价，未切窗口
    * 时同窗口的多件合并为一次注入）。 */
  private def completionAttempt(node: NodeDef): IO[Unit] =
    enqueueNotify(NotifyReason.Completion, node, notifyTaskText(node, NotifyReason.Completion))

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
        // 触发前现读等待者清单（wf1cde E-③）：停等下游随通知告知分发器
        // （补投扫描路径同此口，清单一致）。Q4：文本在入队时成型，投递与预算
        // 计数在窗口结束时由 [[flushBatch]] 单点完成。
        waitingSuccessors(node.id).flatMap(waiters =>
          enqueueNotify(NotifyReason.Failed, node, failedNotifyTaskText(node, waiters)))
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
    * flag 条件，设计 §3）」+「cancelled 候选 = cancelled ∧ 未标记 ∧ **有结果**
    * （R1；R2 后 cancelled 必有 result，故本条实际恒真——保留非空守卫与新语义
    * 一致）」逐条过单一入口（幂等：入口内占位+标记二次把关）。
    * 返回候选总数（>0 时调用方记 info）。重启后本扫描覆盖「终态已落库但通知未
    * 触发/未标记」的全部欠账；窗口 Suppress 的 failed/cancelled 节点保持未标记留在
    * 候选集，冷却结束后由本扫描自然补投。
    *
    * == 回合边界重置预算（2026-09-06 作者拍板；2026-09-07 批起两 reason 分账各自重置）==
    * 每轮扫描开启新一轮预算：先把 budgetUsed / failedBudgetUsed / cancelledBudgetUsed
    * 清零——预算不再按进程生命周期全局单调计数，而是按「分发器回合/通知识别链」计数、
    * 回合结束（本处=每轮 TtlTick 扫描边界）后重置。深度 ≤1 纵深防御的本意保留；既已落
    * notifySentAt 的节点不受重置影响（已出候选集）。 */
  def redeliver(): IO[Int] =
    budgetUsed.set(0) *> failedBudgetUsed.set(0) *> cancelledBudgetUsed.set(0) *> store.snapshot.flatMap { s =>
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
      val cancellations = s.nodes.values
        .filter(n =>
          n.status == NodeLifecycle.Cancelled &&
            n.notifySentAt.isEmpty &&
            n.result.exists(_.trim.nonEmpty))
        .toList
      (completions.traverse_(n => notifyTerminal(n, NotifyReason.Completion)) *>
        failures.traverse_(n => notifyTerminal(n, NotifyReason.Failed)) *>
        cancellations.traverse_(n => notifyTerminal(n, NotifyReason.Cancelled)))
        .as(completions.size + failures.size + cancellations.size)
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

  /** cancelled 投递尝试（取消静默死锁修复批，**R1 方案 2**；与 [[failedAttempt]]
    * 同构、**账务独立**）：窗口裁决在前（Suppress / CooldownOn 不耗预算不标记——
    * 冷却结束 redeliver 补投不丢失）→ 预算 → tell-then-mark；预算耗尽 → cancelled
    * 版升级（节点保持 cancelled + markSent 止重扫 + single-flight notice）。
    *
    * **账务裁定（R1 拍板项）：cancelled 与 failed 独立分账**（独立预算计数 +
    * 独立滚动窗口/cooldown）。理由：
    *   ① 与既有纪律同源——类头「防循环（两 reason 各自护栏、分账互不挤占）」已为
    *      completion/failed 立此先例：一类通知的用量不得挤占另一类；
    *   ② 合账会让「引擎批量取消风暴」（宿主重启后的 sweep / 一轮内多次 L3）打满
    *      共享窗口 → 触发 cooldown → 把真正高优先级的 **failed** 通知一并静音；
    *      而 cancelled 与 failed 的处置链完全不同（承接 vs 重激活），静音一方的
    *      代价不可用另一方抵扣；
    *   ③ 成本保护同档（两 reason 默认 5/回合），独立分账不放大总通知量上限。
    * **风暴面评估（引擎批量取消）**：一轮 TtlTick（30s）内多次取消 → cancelled
    * 窗口 10min/5 次触顶 → 合并单条升级 Nebula + 30min cooldown；cooldown 期内
    * 后续 cancelled **不 markSent**，留在 redeliver 候选集，冷却结束自动补投
    * （延迟而非丢失）。风暴期间的分发器可见性由 **R3 的即时 barrier 告警事件**
    * （`barrier-blocked`，终态写点 0 延迟、不走该预算/窗口）兜住——这是「通知限流」
    * 与「事故可见性」解耦的关键：预算压的是**触发分发器新会话**的成本，不是留痕。 */
  private def cancelledAttempt(node: NodeDef): IO[Unit] =
    cancelledGuard.modify { g =>
      val now = System.currentTimeMillis()
      val recent = g.cancelledAt.filter(t => now - t <= cancelledWindowMs) :+ now
      if now < g.cooldownUntil then
        (g.copy(cancelledAt = recent), CancelledWindowVerdict.Suppress)
      else if recent.size >= cancelledWindowThreshold then
        (g.copy(cancelledAt = recent, cooldownUntil = now + cancelledCooldownMs), CancelledWindowVerdict.CooldownOn)
      else
        (g.copy(cancelledAt = recent), CancelledWindowVerdict.Proceed)
    }.flatMap {
      case CancelledWindowVerdict.Proceed =>
        // Q4：同一 reason 的件在窗口内合并为一次注入（预算按一次注入计）。
        waitingSuccessors(node.id).flatMap(waiters =>
          enqueueNotify(NotifyReason.Cancelled, node, cancelledNotifyTaskText(node, waiters)))
      case CancelledWindowVerdict.Suppress =>
        logger.warn(s"Project '$projectName' node '${node.name}' (${node.id}) cancelled during cancel-notify cooldown — suppressed (unmarked; redelivered after cooldown)")
      case CancelledWindowVerdict.CooldownOn =>
        val text =
          s"[dispatch-notify] 项目「$projectName」${cancelledWindowMs / 60000} 分钟内节点取消通知达 $cancelledWindowThreshold 次——进入 ${cancelledCooldownMs / 60000} 分钟冷却：" +
            s"期间 cancelled 通知暂停触发分发器（未通知节点保持未标记，冷却结束自动补投），冷却结束自动恢复。\n" +
            s"最新取消节点「${node.name}」(${node.id})：${node.result.map(_.take(300)).getOrElse("(无取消原因)")}"
        FlowMapEventLog.append(workspace, projectName, node.id, "dispatch-notify",
          s"cancel window threshold ($cancelledWindowThreshold in ${cancelledWindowMs / 60000}min) → cooldown ${cancelledCooldownMs / 60000}min on, dispatcher notify paused, supervisor notice (merged, latest node '${node.name}')") *>
          escalate(text, node.name) *>
          logger.warn(s"Project '$projectName' cancel-notify window threshold reached (${cancelledWindowThreshold}/${cancelledWindowMs / 60000}min) — cooldown ${cancelledCooldownMs / 60000}min on, merged supervisor notice sent (latest node '${node.name}' ${node.id})")
    }

  /** cancelled 预算耗尽（与 [[escalateFailedBudgetExhausted]] 同构、single-flight
    * 独立分账）：节点**保持 cancelled**（终态不翻转——R1 反例对拍②）+ markSent 退出
    * redeliver 候选集 + 首次耗尽发一条 notice 监督通知（notice 语义非 blocked）+
    * 审计。 */
  private def escalateCancelledBudgetExhausted(node: NodeDef): IO[Unit] =
    markSent(node.id) *>
      cancelledBudgetEscalated.modify(b => if b then (b, false) else (true, true)).flatMap {
        case false =>
          logger.info(
            s"Project '$projectName' dispatch-notify cancelled-budget exhausted ($cancelledBudgetMax) for node '${node.name}' (${node.id}) — node kept cancelled; supervisor notice already sent (single-flight)")
        case true =>
          val text = s"[dispatch-notify] cancelled 通知预算耗尽（$cancelledBudgetMax 次/回合）——项目「$projectName」节点「${node.name}」(${node.id}) 已被取消但未自动通知分发器，请经 NodeList(detail=\"${node.id}\") 复核（cancelled 不可重激活：承接 / 改接 / abandon）。"
          FlowMapEventLog.append(workspace, projectName, node.id, "dispatch-notify",
            s"cancelled budget exhausted ($cancelledBudgetMax) → node '${node.name}' kept cancelled, notifySentAt set, supervisor notice (single-flight)") *>
            escalate(text, node.name) *>
            logger.warn(
              s"Project '$projectName' dispatch-notify cancelled-budget exhausted ($cancelledBudgetMax) — node '${node.name}' (${node.id}) kept cancelled, supervisor notice sent")
      }

  /** **R5 方案 4（2026-09-10 裁定）L3 硬恢复中间态「延后回流」占位**：把
    * `notifySentAt` 写成占位，**不发送任何通知**。语义扩展（唯一一处）：该字段
    * 由「本代次已回流」加读为「本代次回流已处置」——`markSent` 与
    * [[holdTerminalNotify]] 都满足之，[[releaseTerminalNotify]] 归零。
    *
    * 为什么必须有这个占位（不是可选项）：L3 硬恢复的中间态是「节点瞬时
    * Cancelled（bridge Cancelled → 5s → resume）」，它**不是终局**——终局由
    * resume 结果决定（成功 = 节点复活续跑；失败 = 改判 failed）。若中间态照常
    * 回流，则 ① resume 成功的节点带 notifySentAt 复活 ⇒ 其真实终态（completed）
    * 的 completion 回流被持久去重吞掉 → 分发器**永远**收不到该节点完成（新一代
    * 的静默死锁，与 cancelled 不可重激活叠加后更隐蔽）；② resume 失败腿会先发
    * cancelled 再发 failed ⇒ 双份回流。
    *
    * 占位同时关掉另一条竞态：`redeliver`（TtlTick 30s 周期）的 cancelled 候选 =
    * `cancelled ∧ notifySentAt.isEmpty ∧ result.nonEmpty`——5s 窗口内任一扫描轮
    * 撞上中间态就会补投一条 cancelled 通知（概率 ~5/30，非确定性）。占位把该
    * 节点移出候选集，使「恰好一次、且语义正确」成为结构性保证而非时序侥幸。
    *
    * 生命周期：`cancelNode(notify = false)`（仅 L3 路径）写入；两条终局腿负责
    * 归零——resume 成功（[[NodeEngine.hardResumeNode]] CAS 复活）与改判 failed
    * （[[NodeEngine.settleFailedHardResume]] 先归还再走 failNode 全链）。
    *
    * **时序守卫（2026-09-11）**：只在节点仍处 Cancelled 中间态时写占位（见方法体）。 */
  def holdTerminalNotify(nodeId: String): IO[Unit] =
    store.findNode(nodeId).flatMap {
      // **时序守卫**（2026-09-11 实测补）：占位只在节点**仍处 L3 中间态**（Cancelled）时写入。
      // 占位写点在取消尾链的**末尾**（emit → 审计事件 → cleanupPendingAsks → checkBarriersNow
      // → 占位），若终局腿（改判 failed 的 failNode / 成功复活的 CAS）先落，这一笔占位会反向
      // 覆盖终局的回流去重标记：最险的形态是「failed 通知被冷却窗口抑制（未 markSent）」——
      // 占位会让 30s 补投扫描把该节点当已处置 ⇒ **静默丢通知**。终局腿最早在取消后 5s 才可能
      // 开跑（TaskStuckWatcher L3 的 sleep 5s），窗口实际不可达；本守卫把「依赖时序余量」改成
      // **结构性不可能**（幂等：非中间态零写）。
      case Some(n) if n.status == NodeLifecycle.Cancelled => markSent(nodeId)
      case Some(n) =>
        logger.info(
          s"Project '$projectName' node '${n.name}' (${n.id}) is ${n.status} — L3 intermediate notify hold skipped " +
            "(the terminal leg already landed; holding now would clobber the terminal dedup marker)")
      case None => IO.unit
    }

  /** 终局腿归还占位（见 [[holdTerminalNotify]]）：清活动区 + 归档区双区标记，
    * 使节点在下一次真实终态时能正常回流（幂等：两区皆无标记 = 零写）。 */
  def releaseTerminalNotify(nodeId: String): IO[Unit] =
    store.mutate { s =>
      s.nodes.get(nodeId) match
        case Some(n) if n.notifySentAt.isDefined => s.copy(nodes = s.nodes.updated(nodeId, n.copy(notifySentAt = None)))
        case _                                   => s
    } *>
      store.mutateArchive { a =>
        a.nodes.get(nodeId) match
          case Some(n) if n.notifySentAt.isDefined => a.copy(nodes = a.nodes.updated(nodeId, n.copy(notifySentAt = None)))
          case _                                   => a
      }.void

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

  /** D5 停等等待者清单（20260908 wf1cde §3.3 + E-③）：因本节点 failed 而停等的下游。
    * 判定（status ∈ {pending, wiring} 的停等态）：in-barrier 等待 = in 引用本节点 ∧
    * 未收到其投递（deliveredTo 无此键——与 startNode barrier 闸门同构）；deps 等待 =
    * deps 引用本节点（depsSatisfied 只认 completed，failed 永不满足）。合并节点例外
    * 下游已转 blocked（可见终态 + merge-blocked 独立通报），不在停等集合，不入清单。
    *
    * cancelled 侧扩展（R1/R4 取消静默死锁修复批）：被取消节点在**引擎自动摘除**后
    * 已不在下游的 `in` 里（镜像 prune），改以 `pendingSuccession`（「待承接」标）
    * 留痕——故本判据并入该键，否则 cancelled 通知会误报「无下游等待者」。对 failed
    * 节点本键恒空 → failed 清单逐字不变（D5 行为零变化）。 */
  private def waitingSuccessors(failedNodeId: String): IO[List[String]] =
    store.snapshot.map { s =>
      s.nodes.values
        .filter { n =>
          val waiting = n.status == NodeLifecycle.Wiring || n.status == NodeLifecycle.Pending
          val inWait = n.in.contains(failedNodeId) && !n.deliveredTo.contains(failedNodeId)
          val depsWait = n.deps.contains(failedNodeId)
          val successionWait = n.pendingSuccession.contains(failedNodeId)
          waiting && (inWait || depsWait || successionWait)
        }
        .toList
        .map(n => s"${n.name}(${n.id})")
    }

  /** cancelled 版通知任务文本（取消静默死锁修复批 **R1 方案 2**）。
    *
    * **与 failed 文本必须区分**（作者裁定明令）：cancelled **不可重激活**
    * （`NodeEdit` 的重激活闸只放行 `Blocked | Failed`，`NodeTools` 描述
    * `completed/cancelled not reactivatable (create successor instead)`），因此
    * **严禁照抄 failed 的四步 NodeEdit 重激活指引**——照抄会让分发器去「编辑
    * cancelled 节点触发 reactivate」，而该动作对 cancelled 只走普通编辑路径、
    * 不会复活节点，等于把分发器引向无效动作。本文本给出的三条出路 = 承接 /
    * 改接 / abandon（与设计 §6-R1「处置指引 = 承接（新建承接节点）/ 改接（改该节点
    * out → Nebula，触发下游 in 镜像 prune）/ abandon」逐条对应）。 */
  private def cancelledNotifyTaskText(node: NodeDef, waiters: List[String]): String =
    val reasonSummary = node.result.map(_.take(500)).getOrElse("(无取消原因)")
    val waiterLine = waiters match
      case Nil   => "下游等待者：无下游等待者。"
      case names => s"下游等待者（cancelled 不投递不结算，且其 barrier 已被「待承接」标记闸住；承接/改接/放弃后自动续跑）：${names.mkString(", ")}。"
    s"""[dispatch-notify] 节点 '${node.name}' (${node.id}) 已被**取消**（终态 cancelled，reason=cancelled，project=$projectName）。
       |取消原因：$reasonSummary
       |结果全文：NodeList(detail="${node.id}", project=$projectName)。
       |处置指引（cancelled 是终态，但**与 failed 不同：不可重激活** —— NodeEdit 的重激活闸只放行 blocked/failed，编辑 cancelled 节点只会走普通编辑路径、不会复活它；因此 failed 的「NodeEdit 编辑触发 reactivate 重跑」那套指引对本节点**无效，请勿照用**）：
       |1. 承接（首选）：NodeEdit 新建承接节点（建议命名 <原名>-retry 或语义新名），接原拓扑位置（in 同源、out 同目标）；再把该承接节点 append 进受影响下游的 in（NodeEdit 的 in 参数只增）——对该下游的任意实际变更会清掉它的「待承接」标记，其 barrier 才放行。
       |2. 改接（该轨确实不再需要）：**普通取消路径引擎已自动处理**——取消终态写点即把本节点 out 摘除并改接 Nebula（下游 in 镜像随之 prune，下游登记「待承接」标）。若下游 in 里仍见本节点，NodeEdit 把本节点 out 改为 Nebula 即可（幂等）。
       |   **L3 硬恢复路径语义不同（U4，2026-09-11 与代码对齐）**：该路径取消时**不摘除**（resume 成功后拓扑必须完整，提前 prune 会让下游永远拿不到结果）；resume 失败则节点被**改判 failed**（不是留在 cancelled）——failed 侧按 D5 零结算停等，**同样不摘除**，下游 in 保持完整、等上游 NodeEdit 重激活重跑后自动续跑；此时若该轨确实不再需要，请用于该节点 out→Nebula 的 NodeEdit 触发 prune（幂等）。兜底腿：仅当引擎已无法定位该会话归属节点（节点已离活动区/归档）时，引擎才代做 cancelled 侧的**迟到摘除**（反向 prune 活动区引用 + 登记「待承接」+ 事件流留痕）；两者都查无则记 ERROR + 事件流（人工介入）。
       |3. 放弃该轨：NodeEdit abandon=true 标记放弃；下游的「待承接」标记仍需按 1/2 处置，否则其 barrier 永久停等。
       |4. 需人工/外部条件 → 在你的最终输出中写明上报内容（自动投递 Nebula）。
       |$waiterLine
       |前置检查：本节点可能在 L3 硬恢复中已被引擎复活（status 已非 cancelled）**或被改判 failed**——先 NodeList(detail="${node.id}") 读现状；若已 running/pending 则本轮无需动作（若已是 failed，按 failed 版通知的处置指引处理：首选 NodeEdit 重激活重跑）。
       |无需回报——拓扑与状态已落 Flow Map。""".stripMargin

  /** failed 版通知任务文本（2026-09-07 批设计 §2.3 + 作者 09:24 裁定③——failed 可
    * 重激活重跑：reactivate 为首选处置，换名新建降为换基线/重派备选）：err 摘要 +
    * 四动作清单 + 操作知识（分发器是单次会话无记忆，每次都要被告知）。
    * D5 追加（20260908 wf1cde E-③）：尾部附停等等待者告知行——有则列节点名/id
    * （处置上游前先知影响面），无则明示「无下游等待者」（处置不再靠 NodeList 巡检猜）。 */
  private def failedNotifyTaskText(node: NodeDef, waiters: List[String]): String =
    val errSummary = node.result.map(_.take(500)).getOrElse("(无错误文本)")
    val waiterLine = waiters match
      case Nil   => "下游等待者：无下游等待者。"
      case names => s"下游等待者（failed 零结算停等中；上游修复重跑完成后自动续跑）：${names.mkString(", ")}。"
    s"""[dispatch-notify] 节点 '${node.name}' (${node.id}) failed（reason=failed，project=$projectName）。
       |错误摘要：$errSummary
       |结果全文：NodeList(detail="${node.id}", project=$projectName)。
       |处置指引（failed 为终态：可经 NodeEdit 重激活复活重跑；不可直接发消息——NodeMessage 对终态节点拒收）：
       |1. 瞬时/基础设施类失败（LLM 超时、agent 基础设施故障等，同参数值得重试）→ NodeEdit 编辑该节点任意实际变更（task/description/in/out/deps 任一实际改动）触发 reactivate 重跑——原节点复活（status 回 wiring/pending），in/out 拓扑保持，轮次计数清零；
       |2. 需换基线/重派（任务定义或执行形态需实质调整）→ NodeEdit 新建承接节点（建议命名 <原名>-retry 或语义新名），接原拓扑位置（in 同源、out 同目标）；原 failed 节点留作审计，勿删改；
       |3. 任务无意义/无法修复 → NodeEdit abandon=true 标记放弃；
       |4. 需人工/外部条件 → 在你的最终输出中写明上报内容（自动投递 Nebula）。
       |$waiterLine
       |无需回报——拓扑与状态已落 Flow Map。""".stripMargin

object DispatchNotify:
  /** 链级通知预算默认值（设计约束③建议值；completion/failed 同值独立分账）。 */
  val DefaultBudget: Int = 5

  /** 打包窗口默认值（Q4 裁定，2026-09-11 任务分发器收件规则批）：写死 5s。
    * 语义 = 同 reason 的件在该窗口内到达即合并为**一次注入**，并按「一次注入」
    * 计一个预算单位（密集扇出不再从第 6 件起被预算静默丢弃，#62）。 */
  val DefaultWindowMs: Long = 5000L

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
            (ref ! ProjectActor.ProjectCommand.TriggerDispatcher(text, rootSessionId, ProjectActor.SourceDispatch)).void
          case None =>
            NebflowLogger.forName("nebflow.project.dispatch-notify")
              .warn(s"Project '$projectName' has no actorRef — dispatch-notify skipped")
      case None =>
        NebflowLogger.forName("nebflow.project.dispatch-notify")
          .warn(s"Project '$projectName' not mounted — dispatch-notify skipped")
    }

  /** NodeEngine 装配口（挂接最小化：engine 侧仅此一个 val）。
    *
    * `triggerOverride`（取消静默死锁修复批，测试接缝）：Some 时取代
    * [[defaultTrigger]]——spec 用它捕获**通知文本原文**做断言（`defaultTrigger`
    * 在未挂 actorRef 的项目里只 WARN，文本无从观察）。生产调用点不传（默认 None
    * = 真实触发链，零行为变化）。
    *
    * `windowMs`（Q4，2026-09-11）：打包窗口，默认 [[DefaultWindowMs]] = 5s。
    * **测试接缝蕴含窗口关闭**：`triggerOverride` 存在 ⇒ `windowMs = 0`（同步逐条
    * 触发）——注入 stub 的 spec 不模拟时序，5s 墙钟等待会把确定性判据变成 flaky
    * 等待；生产恒 `None` ⇒ 恒 5s 窗口。要显式验证窗口行为时直接构造
    * [[DispatchNotify]] 并传 `windowMs`。 */
  def forEngine(
    store: FlowMapStore,
    workspace: String,
    projectName: String,
    rootSessionId: String,
    escalate: (String, String) => IO[Unit],
    emitUpdated: NodeDef => IO[Unit],
    budgetMax: Int = DefaultBudget,
    triggerOverride: Option[String => IO[Unit]] = None,
    windowMs: Long = DefaultWindowMs
  ): DispatchNotify =
    new DispatchNotify(store, workspace, projectName, escalate, emitUpdated,
      triggerOverride.getOrElse(defaultTrigger(projectName, rootSessionId)), budgetMax,
      windowMs = if triggerOverride.isDefined then 0L else windowMs)

/** 通知原因码（单一入口 dispatchNotify(reason, node) 的 reason 维度）。
  * completion（2026-09-05 批）与 failed（2026-09-07 批）已接线；blocked 预留接口
  * （blocked 由 FeedbackRouter 重入独占——组合不双触发），监督化批按需扩展。 */
enum NotifyReason:
  case Completion
  case Failed
  case Blocked
  /** cancelled 终态回流（取消静默死锁修复批 2026-09-10，作者裁定 **R1 方案 2**）：
    * 与 failed 对称——不查 notifyDispatcher flag（取消同样是「无人订阅的异常
    * 终态」，拓扑主人必须知情，否则 barrier 静默死锁无人可处置）。
    * **通知文本必须与 failed 区分**（cancelled 不可重激活，处置指引 = 承接/改接/
    * abandon，严禁照抄 failed 的 NodeEdit 重激活四步）。 */
  case Cancelled

object NotifyReason:
  /** 原因码字符串（事件留痕/通知文本/审计共用）。 */
  def code(r: NotifyReason): String =
    r match
      case NotifyReason.Completion => "completion"
      case NotifyReason.Failed     => "failed"
      case NotifyReason.Blocked    => "blocked"
      case NotifyReason.Cancelled  => "cancelled"
