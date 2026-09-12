package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.actor.*
import nebflow.agent.*
import nebflow.core.NebflowLogger
import nebflow.core.entity.EntityLoader
import nebflow.core.node.NodeRunner

import scala.concurrent.duration.*

/**
 * ProjectActor —— 每项目一个的常驻 actor（#28 阶段 0，方案 §1.1）。
 *
 * 职责：
 * 1. TriggerDispatcher → 分发器单例化（2026-09-02 作者裁定）：项目已有活跃
 *    分发器会话 → 任务文本注入现有会话（turn 边界串行消费）；无 → spawn 新
 *    分发器会话（单次会话 agent，fresh session：任务文本注入；无回报——拓扑/
 *    状态已落 Flow Map）。ReenterDispatcher（blocked 重入）同规。
 *    观测面上下文经济学批（20260907 裁定①方向 B）：spawn/重入 prompt 不再嵌
 *    Flow Map 水合快照（旧实现首条消息直灌 snapshot.asJson，nebflow 规模
 *    ≈1,055KB → spawn 即超压缩阈值）——拓扑由分发器首轮 NodeList 按需拉取
 *    （元数据 only + ToolResultGuard persist 兜底）。
 * 2. TTL 定时器：终态节点 24h 显示消失 → 移归档 + WS nodeRemoved（TTL 只管
 *    显示，非运行超时；Node 运行本身不设超时）
 * 3. CancelNode：转发到 NodeEngine（复用 cancel 信号）
 *
 * 无 Mail 身份（自身不是 agent）；Mail(→project) 触发路由留阶段 1。
 */

/** Project 运行时（工具/REST 层直接操作 store/engine 的入口）。 */
case class ProjectRuntime(
  project: ProjectDef,
  store: FlowMapStore,
  engine: NodeEngine,
  system: ActorSystem,
  resources: SharedResources,
  actorRef: Option[ActorRef[ProjectActor.ProjectCommand]] = None,
  /** 项目任务板（TaskBoard 批 2，规格 §1f）：mount 时 TaskBoardStore.open 同位
    * 挂载（fail-soft——异常仅 WARN 置 None，不拖垮项目挂载）。工具层经
    * NodeTools.resolveProject → rt.board 取用；None = 该项目无板（工具报
    * TBOARD_PARAM 引导），注入面整块省略。 */
  board: Option[TaskBoardStore] = None
)

/** 全局注册表：project name → runtime。 */
object ProjectRuntimeRegistry:
  private val logger = NebflowLogger.forName("nebflow.project.registry")
  private val runtimes = Ref.unsafe[IO, Map[String, ProjectRuntime]](Map.empty)

  def register(rt: ProjectRuntime): IO[Unit] = runtimes.update(_ + (rt.project.name -> rt))
  def unregister(name: String): IO[Unit] = runtimes.update(_ - name)
  /** 取项目运行时：精确 key 匹配优先（无回归），否则对 project.name 做
    * equalsIgnoreCase 兜底——项目标识天然大小写不敏感（NodeList(Nebflow) 与
    * NodeList(nebflow) 同解析，分发器实名报错根因）。unregister/mount 用
    * canonical 名不受影响。 */
  def get(name: String): IO[Option[ProjectRuntime]] =
    runtimes.get.map { m =>
      m.get(name).orElse(m.values.find(_.project.name.equalsIgnoreCase(name)))
    }
  def all: IO[List[ProjectRuntime]] = runtimes.get.map(_.values.toList)
  def clear: IO[Unit] = runtimes.set(Map.empty)

  /** 启动自动挂载（0b 契约「阶段 1 补自动挂载」，#37 重启窗口收尾）：
    * 磁盘已有项目 → 幂等挂载。rootSessionId = 顶层 Nebula 主会话 id
    * （启动期无会话上下文，调用方直接传顶层根——A 修复后的 thread 语义）。
    * 与 ProjectCreate 幂等挂载（运行时主动路径，241ef6c5）互补：
    * 本函数管重启免人工，ProjectCreate 管运行时挂载/重挂。返回新挂载数。
    * #46 fail-soft：单个项目 mount 失败（workspace 不可创建/不可解析，如跨平台
    * 机器特定绝对路径、权限受限、路径被删——FlowMapStore.open 抛异常）时记 WARN
    * 并跳过该项目（不计数、不中断），其余项目照常挂载，gateway 正常启动——不再
    * 因一个坏项目拖垮整个启动批（此前异常沿 mount→mountAll→startupMount 冒泡到
    * GatewayMain 启动退出）。不改 mount 本身（运行时 ProjectCreate 路径语义保持）。
    * wsSend：启动期传入 wsHub.broadcast——否则 engine 的 wsSendFn 是 no-op，
    * 节点/分发器事件永远到不了前端（#28 可观测缺口根因之一）。
    * skipStaleReap（crash-recovery 批 2026-09-07）：true = 启动挂载且崩溃恢复开启
    * ——跳过挂载期僵尸收殓，崩溃残留 running 节点留给紧随 startupMount 的
    * ProjectCrashRecovery sweep 认领（rehydrate/failed，语义优于 cancelled 收殓）；
    * false（默认，运行时挂载/恢复开关关闭）= 既有收殓行为零变化。 */
  def mountAll(
    projects: List[ProjectDef],
    rootSessionId: String,
    system: ActorSystem,
    resources: SharedResources,
    wsSend: Option[Json => IO[Unit]] = None,
    skipStaleReap: Boolean = false
  ): IO[Int] =
    projects.foldLeft(IO.pure(0)) { (acc, pd) =>
      acc.flatMap { n =>
        get(pd.name).flatMap {
          case Some(_) => IO.pure(n) // 已挂载跳过（启动时序无并发，防御性判断）
          // #46 fail-soft：单项目 mount 失败（FlowMapStore.open 抛异常，如 workspace
          // 不可创建/不可解析）→ 记 WARN 跳过、不计数、不中断，继续下一个项目。
          case None =>
            mount(pd, system, resources, wsSend, rootSessionId, skipStaleReap = skipStaleReap)
              .as(n + 1)
              .handleErrorWith(e => logger.warn(s"Project '${pd.name}' mount skipped: ${e.getMessage}") *> IO.pure(n))
        }
      }
    }

  /** 挂载项目：建 store + engine + spawn actor + 注册 runtime（幂等，已挂载直接返回）。 */
  def mount(
    project: ProjectDef,
    system: ActorSystem,
    resources: SharedResources,
    wsSend: Option[Json => IO[Unit]],
    rootSessionId: String,
    ttlDisplayMs: Long = NodeEngine.TtlDisplayMs,
    ttlCheckIntervalSec: Int = 30,
    skipStaleReap: Boolean = false
  ): IO[ProjectRuntime] =
    get(project.name).flatMap {
      case Some(rt) => IO.pure(rt)
      case None =>
        for
          store <- FlowMapStore.open(project.name, project.workspace)
          // TaskBoard 挂载（批 2 §1f）：与 FlowMapStore.open 同位。open 本体纯
          // 构造无失败路径，防御式兜底保持 mount 语义一致——板建不起来仅 WARN
          // 置 None（fail-soft），不拖垮项目挂载。
          board <- IO(TaskBoardStore.open(project.name, project.workspace))
            .map(Some.apply: TaskBoardStore => Option[TaskBoardStore])
            .handleErrorWith(e => logger
              .warn(s"Project '${project.name}' task board mount failed (board disabled): ${e.getMessage}")
              .as(None))
          engine <- IO.pure(
            new NodeEngine(
              store,
              system,
              resources,
              wsSend.getOrElse((_: Json) => IO.unit),
              project.workspace,
              rootSessionId,
              project.name,
              // blocked 反馈档位（§7.1）：project.json 可选字段 feedbackMode，缺省 auto
              project.feedbackMode.getOrElse(FeedbackRouter.ModeAuto),
              emitNodeEvent(project.name, wsSend),
              // TaskBoard 批 2（§3a）：节点 buildInput 头部板块数据源——板 store
              // + 项目目标行（ProjectDef.description，None 则目标行省略）。
              board = board,
              projectGoal = project.description
            )
          )
          actorRef <- system.spawn(
            ProjectActor(
              ProjectActor.ProjectConfig(
                project = project,
                engine = engine,
                system = system,
                resources = resources,
                rootSessionId = rootSessionId,
                ttlDisplayMs = ttlDisplayMs,
                ttlCheckIntervalSec = ttlCheckIntervalSec,
                board = board
              )
            ),
            s"project-${project.name.take(20)}"
          )
          // 僵尸 running 对账（trigger-chain-fix §6.4）：重启窗口持久化 status=
          // running 且无在飞 fiber（本进程内存 running 表空 = 会话已死）的节点 →
          // reapStaleRunning 收殓（cancelled + reaped 审计；2026-09-07 裁定：无 TTL
          // 强制清，死亡现场保留主图待上层裁决——同族第三变体「重启后僵尸 running
          // 只能人工 NodeEdit abandon 收殓」根除）；有在飞 fiber 的活会话节点 Left
          // 拒绝（误杀防护既有纪律，NodeEngine 硬约束）。
          // crash-recovery 批：skipStaleReap=true（启动挂载且恢复开启）时跳过——
          // 崩溃残留 running 留给紧随其后的 ProjectCrashRecovery sweep 认领（快段
          // 先于 projectTtlScanner 结构性保证，见 GatewayMain boot 链）；sweep 未
          // 认领的残余仍由 watchdog（TtlTick settleStaleRunningNodes→failed）兜底。
          _ <- if skipStaleReap then IO.unit
          else
            store.snapshot.flatMap { s =>
              s.nodes.values.filter(_.status == NodeLifecycle.Running).toList
                .traverse_(n => engine.reapStaleRunning(n.id).void)
            }
          // V8 (2026-09-03): 挂载即扫——项目在运行时挂载（ProjectCreate 路径）且根
          // 会话已活跃时，滞留的 out=Nebula 结果立即补投，不等首个 30s tick。
          // 启动自动挂载路径根 ref 通常缺失 → 静默跳过，由 TtlTick 周期兜底。
          _ <- engine.redeliverUnconsumedNebulaResults().handleErrorWith(_ => IO.unit)
          rt = ProjectRuntime(project, store, engine, system, resources, Some(actorRef), board)
          _ <- register(rt)
        yield rt
    }

  private def emitNodeEvent(project: String, wsSend: Option[Json => IO[Unit]])(eventType: String, nodeId: String, nodeJson: Json): IO[Unit] =
    wsSend.fold(IO.unit) { send =>
      send(
        Json.obj(
          "type" -> eventType.asJson,
          "project" -> project.asJson,
          "nodeId" -> nodeId.asJson,
          "node" -> nodeJson
        )
      )
    }

object ProjectActor:
  private val logger = NebflowLogger.forName("nebflow.project.actor")

  enum ProjectCommand:
    case TriggerDispatcher(taskText: String, rootSessionId: String, source: String = ProjectActor.SourceTask)
    /** blocked 反馈重入（设计 §2.2）：FeedbackRouter 裁决通过 → spawn 全新分发器会话
      * 注入重入 prompt。无 rootSessionId 参数——重入是系统发起，用挂载时的 root。 */
    case ReenterDispatcher(nodeId: String, feedback: BlockedFeedback, blockCount: Int)
    case CancelNode(nodeId: String)
    case TtlTick
    case Shutdown

  /** 注入来源权威定名（Q2-B2，2026-09-11 任务分发器收件规则批）。三分：
    *  - [[SourceTask]] = 项目触发入口（`Mail(address="project:<name>")` / 重入 /
    *    spawn 首条 prompt；**值不改名**——R2 批 D-5 裁定观测面零断代。
    *    本条覆盖此前相关指令：旧注释里的 `Task` 工具已删净退役，
    *    入口唯一 = Mail）；
    *  - [[SourceDispatch]] = 回流通知（DispatchNotify → TriggerDispatcher）；
    *  - `"node"` = 节点消息（NodeEngine.deliverToNebula 侧，本批不动）。
    * 前端 `INJECTED_SOURCE_LABELS` 有对应显式标签（web/js/chat.js）——
    * **后端是唯一定名源**，前端不得靠首字母大写兜底。
    * `TriggerDispatcher.source` 默认 [[SourceTask]] ⇒ 既有调用点（MailTool /
    * ProjectCrashRecovery）零改动即落 task 语义。 */
  val SourceTask: String = "task"
  val SourceDispatch: String = "dispatch"

  /** 分发器全局 agent 名（定义文件交付 Nebula/entity-creator 注册）。 */
  val DispatcherAgentName = "project-dispatcher"

  /** 分发器会话 id 前缀（sessionId = "dispatcher-<uuid8>"）。AgentControl/
    * 面板的 cancel 白名单按此识别新 Project 系统会话（与 NodeEngine.SessionPrefix
    * 同款跨层契约；前端 utils.isBgAgentId 硬编码同值）。 */
  val DispatcherSessionPrefix = "dispatcher-"

  /** 活跃分发器会话登记（单例化裁定 2026-09-02）。spawn 时置位、观察桥终态时
    * 清除（与 agentRegistry 同点清理）。pendingInjected = 已注入本会话但 turn
    * 尚未终结的件数：注入任务以 UserInput(replyTo=观察桥) 进入，**spawn 首条
    * prompt 也计 1 件**。
    * pendingTaskTexts = 未消费件的触发任务全文队列（队首=最早未消费件）；不变量
    * `pendingInjected == pendingTaskTexts.size`，桥 Completed 时以 k 同减两者。
    * （R7-b 后不再 pop 出摘要投递——本队列仅用于**拆除裁决**；投递改由分发器
    * 显式 `Mail(address="Nebula", …)` 承担。）
    *
    * Q3-a（2026-09-11 收件规则批）：mid-turn 直投让**一个 turn 可以消费多件**
    * （tools-complete 边界整队合批，AgentActor.drainUserBatch）——一个 Completed
    * 不再恒等于一件。本 turn 消费几件由 [[ActiveDispatcher.consumedTaskMsgs]]
    * 增量判定（会话历史里带注入来源标签的消息条数）；余件 > 0 → 保活，归零 → 拆除。 */
  case class ActiveDispatcher(
    sessionId: String,
    agentRef: ActorRef[AgentCommand],
    bridgeRef: ActorRef[AgentEvent],
    pendingInjected: Int = 0,
    pendingTaskTexts: List[String] = Nil,
    /** 已计入消费的注入来源消息条数（`source ∈ {task, dispatch}`）——桥跨
      * Completed 事件累计，用于算出本 turn 消费了几件（增量 ≤ 0 时降级为 1）。 */
    consumedTaskMsgs: Int = 0
  )

  /** 桥侧消费计数只认后端定名的注入来源（Q2-B2 三分中的两个分发器入口源）。 */
  private val DispatcherInjectedSources: Set[String] = Set(SourceTask, SourceDispatch)

  // `taskSummaryLine` / `batchSummaryLine` 同批删净（R2「一个 Mail 统一」批
  // 2026-09-12，R7-b）：二者唯一消费点 = 桥侧 turn 级自动投递的标注行，随
  // `NodeEngine.deliverDispatcherOutputToNebula` 一起移除（D-4 删净，不留死代码）。
  // 分发器的批级回传改由显式 `Mail(address="Nebula", type=RESULT, chainId=…, …)` 承载。

  case class ProjectConfig(
    project: ProjectDef,
    engine: NodeEngine,
    system: ActorSystem,
    resources: SharedResources,
    rootSessionId: String,
    ttlDisplayMs: Long = NodeEngine.TtlDisplayMs,
    ttlCheckIntervalSec: Int = 30,
    /** 项目任务板（TaskBoard 批 2 §3a）：分发器 spawn 注入（newTaskPrompt/
      * reentryPrompt）数据源；None = 无板（注入整块省略）。mount 构造时传入。 */
    board: Option[TaskBoardStore] = None
  )

  /** 全局 TTL 扫描：周期给所有已挂载 ProjectActor 发 TtlTick（GatewayMain 启动）。
    * TTL 只管 **completed** 节点 24h 显示消失（2026-09-07 裁定收紧：failed/cancelled
    * 不过期不归档，死亡现场保留待上层裁决）；Node 运行本身不设超时（硬约束）。
    * Bug 1 修复（QA e2e SOE）：`*> loop` 的 by-name 递归在构造期被 eager 求值 →
    * 必须 `*> IO.defer(loop)` 显式延迟到执行期（lazy val 同样会初始化死循环）。 */
  def ttlScanner(interval: FiniteDuration): IO[Unit] =
    def loop: IO[Unit] =
      IO.sleep(interval) *>
        ProjectRuntimeRegistry.all.flatMap { rts =>
          rts.traverse_(rt => rt.actorRef.fold(IO.unit)(ref => (ref ! ProjectCommand.TtlTick).void))
        } *> IO.defer(loop)
    loop

  def apply(cfg: ProjectConfig): Behavior[ProjectCommand] =
    // 单例化裁定（2026-09-02）：每项目同时至多一个活跃分发器会话。会话登记在
    // activeRef（spawn 置位、观察桥终态清除）；TriggerDispatcher /
    // ReenterDispatcher 到达时经 active.modify 原子 check-and-inject——有活跃
    // 会话 → 注入排队（turn 边界消费），无 → spawn。check-then-spawn 在本单
    // actor 内串行化（LocalActorRef 逐条处理，上一条 handler IO 跑完才取下一
    // 条）+ modify 原子占位，双 spawn 竞态关闭。
    Behaviors.setup { _ =>
      Ref.of[IO, Option[ActiveDispatcher]](None).map { active =>
        lazy val behavior: Behavior[ProjectCommand] =
          Behaviors.receiveMessage {
            case ProjectCommand.TriggerDispatcher(taskText, rootSessionId, source) =>
              // 热重启 draining 准入闸（hot-restart 批设计 §3.3 choke 点清单）：
              // draining 期间拒绝新分发器会话/新节点派发（新工作准入关闭）；
              // completion/failed 回流被拒时 notifySentAt 未标记 → 重启后
              // redeliver 扫描补投（延迟触发非丢失）。非 draining 零开销旁路。
              nebflow.core.hotrestart.HotRestart.admissionGate.flatMap {
                case Right(()) => dispatchTask(cfg, active, behavior, taskText, rootSessionId, source)
                case Left(reason) =>
                  logger.warn(s"[hot-restart] dispatcher trigger refused during draining: $reason").as(behavior)
              }
            case ProjectCommand.ReenterDispatcher(nodeId, feedback, blockCount) =>
              nebflow.core.hotrestart.HotRestart.admissionGate.flatMap {
                case Right(()) => dispatchReentry(cfg, active, behavior, nodeId, feedback, blockCount, cfg.rootSessionId)
                case Left(reason) =>
                  logger.warn(s"[hot-restart] dispatcher reentry refused during draining: $reason").as(behavior)
              }
            case ProjectCommand.CancelNode(nodeId) =>
              cfg.engine.cancelNodeById(nodeId).as(behavior)
            case ProjectCommand.TtlTick =>
              // V8 (2026-09-03): Nebula 未消费结果重投扫描——挂载在启动装配点的
              // projectTtlScanner 驱动的周期 tick 上；启动期根会话未 spawn 时扫描
              // 静默跳过（结果滞留 flow-map，下个 tick 再试），根会话可用后 30s 内
              // 补投。best-effort：扫描失败不影响 TTL sweep。
              // 阶段 2b（§B.5 信任运行时联动）：同 tick 挂 plugin 信任重验——
              // digest 失效的运行中 plugin MCP 立即停用 + 持有会话收系统提醒。
              // dispatch-notify 补投（2026-09-05 批）：重启后未触发通知（notifySentAt
              // 为空）30s 内补投——redeliverUnconsumedNebulaResults 同形态周期兜底；
              // best-effort 失败仅 WARN，不影响后续 TTL sweep。
              cfg.engine.dispatchNotify.redeliver()
                .handleErrorWith(e => logger.warn(s"dispatch-notify redelivery scan failed: ${e.getMessage}").void) *>
              cfg.engine.revalidatePluginTrust()
                .handleErrorWith(e => logger.warn(s"plugin trust revalidation failed: ${e.getMessage}")) *>
                cfg.engine.redeliverUnconsumedNebulaResults()
                .handleErrorWith(e => logger.warn(s"Node redelivery scan failed: ${e.getMessage}").as(0)).void *>
                // 资格回扫（trigger-chain-fix §6.2）：pending/wiring 启动资格周期
                // 重估——孤儿 barrier 自愈 + 合格者 fork 启动 + settle-sweep/
                // trigger-starved 留痕。best-effort 同款（失败不影响 TTL sweep）。
                cfg.engine.settleRunnableSweep()
                .handleErrorWith(e => logger.warn(s"settle sweep failed: ${e.getMessage}")) *>
                // 死会话 running 自动收敛（僵尸收敛批 2026-09-06）：仅对「可证明
                // 无活会话且无在途后台任务」的 running 节点收敛 failed（非 cancelled
                // ——cancelled 不投递下游，barrier 永挂）。best-effort 同款
                //（失败不影响 TTL sweep）。
                cfg.engine.settleStaleRunningNodes()
                .handleErrorWith(e => logger.warn(s"dead-session settle failed: ${e.getMessage}")) *>
                // 未申报提醒阶梯（noderpt 批 A 段 2026-09-11 作者裁定）：完成门腿 2
                // （未申报不终态化）的配套兜底——对「已交棒但未 node_report」的 Running
                // 节点按阶梯注入提醒轮 + 写 node-report-missing 事件，阶梯耗尽转 quiescent
                // 档（只留痕不注入）。**永不判 failed、永不杀会话**：本扫描腿零终态化动作。
                // best-effort 同款（失败不影响后续 sweep）。
                cfg.engine.remindUnreportedNodes()
                .handleErrorWith(e => logger.warn(s"node-report reminder sweep failed: ${e.getMessage}")) *>
                // 终态销毁窗口扫描（noderpt 批 B 段 2026-09-11 作者裁定「一律存活 30 分钟
                // 再销毁」）：对 `destroyAt` 到点的终态节点执行对称收殓（`reclaimSession`：
                // 杀进程树 + 注销 registry + 逐条 finalizeTask + 释放 ShellSession 条目 +
                // WS cancelled 帧）并清字段；顺带按持久字段重建禁 spawn 表（重启自愈）。
                // 幂等（字段已清 ⇒ 不再入选）；best-effort 同款（失败不影响后续 sweep）。
                cfg.engine.sweepDestroyWindows()
                .handleErrorWith(e => logger.warn(s"node destroy-window sweep failed: ${e.getMessage}")) *>
                // loop 时间帽扫描（nrloop 一期 2026-09-12，设计 §3.6 时间维）：对
                // 「非终态 ∧ loopStartedAt 已置位 ∧ now-loopStartedAt ≥ maxWallClockMs」
                // 的重跑目标熔断——反查到活 verifier 驱动方 ⇒ 该 verifier 终态化 failed +
                // result 写 reason/计量 + `loop-budget` 事件 + 失败通知（经既有 failNode
                // 链）；反查不到（计时起点成孤儿）⇒ 清起点 + 单发 orphan 事件，绝不终态化
                // 无关节点。闸门前置（maxWallClockMs ≤ 0 = 该维关闭 ⇒ 零成本短路）。
                // 幂等（起点清除 CAS 单发）；best-effort 同款（失败不影响后续 sweep）。
                cfg.engine.sweepLoopBudgets()
                .handleErrorWith(e => logger.warn(s"loop budget sweep failed: ${e.getMessage}")) *>
                // 链级即时归档 sweep（裁定④「TTL 分开」批 2026-09-07）：整链全终态
                // → 整批立即移归档（移除旧 24h 活动区滞留）；链未齐（含 blocked 待办）
                // → 保留。视觉「同帧淡出」由前端派生管线承担，本 sweep 出库+落盘。
                // 归档审计 + 索引联动（P3 归档联动批 2026-09-10，spec §6.2；写路径接线 =
                // 缺口归口补线批 2026-09-10）：
                // ① 出库明细版 sweep（链级事实来自推导单点，调用方零二次派生）——
                //    WS nodeRemoved 逐节点如旧（先行，语义零变化），随后为每条出库链
                //    追加 chain-archived 审计事件（顶层 chainId + 结构化 summary：
                //    `chain=<id> archivedAt=<ms> members=<n>`；nodeId = 分量内 createdAt
                //    最早节点）。事件追加 best-effort——写失败绝不回滚归档、不影响 WS，
                //    漏事件由 ③ 的 catch-up 兜底（幂等重放）。
                // ② 索引翻转（事件驱动、幂等）：「链归档 → 自动翻 INDEX.md」的盘上闭环
                //    ——链块与条目 state 在 active ↔ archived 分区之间迁移。**仅本轮有
                //    出库链时才调用**（swept 空 = 零开销，禁每 30s 空扫）；best-effort
                //    失败仅 WARN，绝不回滚归档、不影响 WS nodeRemoved 语义与后续 tick
                //    （同 ① 审计事件先例）。
                // ③ 对账兜底：同一 30s 周期内按 workspace 节流 10min，窗内先跑一次
                //    apply catch-up（覆盖「事件已写、apply 未落地」的进程死亡窗口，
                //    幂等）再只读对账；索引区无 INDEX.md 即零开销 no-op；只写
                //    `<workspace>/.nebflow/tmp/` 报表，不改任何文档本体。
                //    索引根单点 = DocIndexConsumer.indexRootsFor（home 域 + ws 域）。
                cfg.engine.store.sweepCompletedChainsDetailed(System.currentTimeMillis()).flatMap { swept =>
                  val removals = swept.flatMap(_.nodeIds).traverse_(id => cfg.engine.emitRemoved(id))
                  val audits = swept.traverse_ { c =>
                    FlowMapEventLog
                      .append(
                        cfg.project.workspace,
                        cfg.project.name,
                        c.nodeId,
                        FlowMapEventLog.ChainArchivedType,
                        FlowMapEventLog.chainArchivedSummary(c.chainId, c.archivedAt, c.members),
                        Some(c.chainId)
                      )
                      .handleErrorWith(e =>
                        logger.warn(s"chain-archived audit append failed (${c.chainId}): ${e.getMessage}"))
                  }
                  // ③ 索引翻转（事件驱动、幂等）：出库后把链块/条目 state 在 active ↔
                  //    archived 间迁移。swept 空 → 不调用（零开销，禁 30s 空扫）。
                  val flip =
                    if swept.isEmpty then IO.unit
                    else
                      DocIndexConsumer
                        .applyChainEvents(cfg.project.workspace,
                          DocIndexConsumer.indexRootsFor(cfg.project.workspace))
                        .handleErrorWith(e => logger.warn(s"doc-index flip failed: ${e.getMessage}"))
                        .void
                  val reconcile = DocIndexConsumer
                    .tick(cfg.project.workspace)
                    .handleErrorWith(e =>
                      logger.warn(s"doc-index reconcile tick failed: ${e.getMessage}").as(None))
                    .void
                  removals *> audits *> flip *> reconcile
                }.as(behavior)
            case ProjectCommand.Shutdown =>
              IO.pure(Behaviors.stopped)
          }
        behavior
      }
    }

  /** Plugin Catalog 段（阶段 2b §B.4 第 2 步）：分发器 prompt 组装的注入源。
    * 受信 plugin 目录（untrusted 不出现，§B.3）；flag 关 / 盘上无插件 → ""。可见性批
    * （2026-09-10）：段尾缺席注记随段给出（装载失败/信任未批准/digest 漂移计数），
    * 目录缩容时不再静默——口径见 PluginRegistry.renderCatalog。
    * 对齐 skillCatalog order 800 注入先例——用注入目录段而非新增查询工具
    * （分发器单次会话、目录规模小，不多造工具）。
    * dispatcher-ctx 批（2026-09-05）：目录渲染收口到 DispatcherContextCatalog
    * 双段拼装（插件能力目录 description 单源 + 预设场景目录），本类只留挂接。 */
  private def pluginCatalogText(): IO[String] = nebflow.core.plugin.DispatcherContextCatalog.render()

  /** 项目记忆注入段（project-memory 批 2026-09-05 §3）：本项目 workspace
    * `.nebflow/memory.md` 的渲染块——预算内全文、软警区全文+WARN 脚注、超硬顶
    * 头部+统计（三态渲染单点在 ProjectMemory.injectionBlock）。文件缺失/空 →
    * ""（调用方不注空段）。
    * 注入语义=「派发项目任务 → 该项目记忆自动注入」：只挂 spawn 形态两 prompt
    * （newTaskPrompt/reentryPrompt）；注入活跃会话形态（taskInjectionText/
    * reentryInjectionText）不重复注入——会话 spawn 时已带当次记忆快照，turn
    * 边界间的增量由 NodeList 现读与节点 out 结果承接（最小改动纪律）。
    * 全局注入（ContextRefresher）不含项目记忆——瘦身边界（§3 默认注入只含全局）。 */
  private def projectMemoryText(project: ProjectDef): IO[String] =
    ProjectMemory.injectionBlock(project.workspace, project.name)

  /** 分发器任务板注入段（TaskBoard 批 2 §3a）：renderDispatcher 全板紧凑行
    * （≤1200 字符/20 行，超限整行丢弃+尾注——降级纪律在 renderer 单点）。
    * 无板/空板 → ""（调用方不注空段，项目记忆先例同款）。⚠node-done join 数据
    * 从 Flow Map 快照现读（§2d 真实终态映射，TaskBoardStore.nodeTerminalMap 单点），
    * 不缓存——分发器每次 spawn/reentry 拿当下漂移面。 */
  private def dispatcherBoardText(cfg: ProjectConfig): IO[String] =
    cfg.board match
      case None => IO.pure("")
      case Some(board) =>
        cfg.engine.store.snapshot.flatMap { snap =>
          val terminal = TaskBoardStore.nodeTerminalMap(snap.nodes.values)
          IO.blocking(board.entriesSync())
            .map(entries => TaskBoardRenderer.renderDispatcher(entries, terminal))
            .handleErrorWith(e =>
              logger.warn(s"Project '${cfg.project.name}' task-board injection skipped: ${e.getMessage}").as(""))
        }

  /** 新任务形态 prompt（spawnDispatcher 双形态之一，现状文案保留）。
    * 观测面上下文经济学批（20260907 裁定①方向 B）：不嵌 Flow Map 快照——旧实现
    * `snapshot.asJson.noSpaces` 直灌首条消息（内存模型 result/task 双全文水合，
    * nebflow 规模 ≈1,055KB，spawn 即超 CompactThreshold），改为「先 NodeList 读
    * 现状」按需拉取（与工具通道同源同守卫）。private[project] 供 spawn 首条消息
    * 形态 spec（DispatcherSpawnPromptSpec）固化断言。
    * TaskBoard 批 2（§3a）：taskBoard = <task-board> 注入块（renderer 单点渲染，
    * 上限 1200 字符/20 行）——拼装相对顺序 …→插件目录→项目记忆→任务板→任务文本
    * （任务板在任务文本之前；空串不注空段）。 */
  private[project] def newTaskPrompt(project: ProjectDef, taskText: String, pluginCatalog: String, projectMemory: String, taskBoard: String = ""): String =
    s"""你是项目「${project.name}」的任务分发器。
       |
       |${if pluginCatalog.nonEmpty then pluginCatalog + "\n" else ""}${if projectMemory.nonEmpty then projectMemory + "\n" else ""}${if taskBoard.nonEmpty then taskBoard + "\n" else ""}任务：$taskText
       |
       |先 NodeList 读 Flow Map 现状（拓扑与节点状态按需拉取），再按需用 NodeEdit 建节点/接线/改接。所有 Node 工具调用必须带 project=${project.name} 参数。无需回报——拓扑与状态已落 Flow Map。""".stripMargin

  /** 重入协议四动作块（reentryPrompt 与 reentryInjectionText 共用，单点维护；
    * 设计 §2.2 原文照抄——四动作选择/abandon 说明/「无需回报」）。 */
  private def reentryActions(project: ProjectDef): String =
    s"""先 NodeList 读现状（重点关注：status=blocked 节点、其下游 pending 节点），
       |再从以下动作中选择并执行（NodeEdit / NodeCancel）：
       |1. 任务可修 → NodeEdit 编辑该节点（改 task/agent/in/out）触发重激活（blockCount 自动 +1）；
       |2. 任务应拆分 → 建新节点子图替换，NodeEdit abandon=true 标记旧节点放弃；
       |3. agent 能力不匹配 → 换 agent 重建；
       |4. 需外部条件 / 无法提出与上轮实质不同的调整 → abandon + 不重派（避免无效循环）。
       |所有 Node 工具调用必须带 project=${project.name}。无需回报——拓扑与状态已落 Flow Map。""".stripMargin

  /** 重入调整形态 prompt（spawnDispatcher 双形态之二，设计 §2.2 原文照抄——
    * 含节点名/id/blockCount/反馈三字段/四动作选择/abandon 说明/「无需回报」）。
    * 裁定①（20260907 方向 B）：快照注入移除，同 newTaskPrompt——四动作块自带
    * 「先 NodeList 读现状」。private[project] 供 spec 固化断言。
    * TaskBoard 批 2（§3a）：taskBoard 注入块同 newTaskPrompt——拼装在项目记忆
    * 之后、四动作块之前（任务板在任务文本之前语义的重入对应位）。 */
  private[project] def reentryPrompt(project: ProjectDef, node: NodeDef, feedback: BlockedFeedback, blockCount: Int, pluginCatalog: String, projectMemory: String, taskBoard: String = ""): String =
    s"""你是项目「${project.name}」的任务分发器——本轮是【节点反馈重入调整】，不是新任务。
       |
       |节点 ${node.name}（${node.id}）报告 blocked（第 $blockCount 轮）：
       |  原因分类：${feedback.category}
       |  说明：${feedback.detail}
       |  对拓扑的建议：${feedback.suggestion}
       |
       |${if pluginCatalog.nonEmpty then pluginCatalog + "\n" else ""}${if projectMemory.nonEmpty then projectMemory + "\n" else ""}${if taskBoard.nonEmpty then taskBoard + "\n" else ""}${reentryActions(project)}""".stripMargin

  /** 注入现有会话的新任务文本（单例化裁定：标注「新任务到达」来源；与进行中
    * 工作按 turn 串行——处理中排 pendingUserInputs，turn 边界消费）。 */
  private def taskInjectionText(taskText: String): String =
    s"""── 新任务到达（注入现有分发器会话；与进行中工作按 turn 串行执行）──
       |
       |$taskText""".stripMargin

  /** 注入现有会话的重入调整文本（单例化裁定 × §2.2 修订：重入优先投活跃会话）。 */
  private def reentryInjectionText(project: ProjectDef, node: NodeDef, feedback: BlockedFeedback, blockCount: Int): String =
    s"""── 节点反馈重入调整（注入现有分发器会话；与进行中工作按 turn 串行执行）──
       |
       |节点 ${node.name}（${node.id}）报告 blocked（第 $blockCount 轮）：
       |  原因分类：${feedback.category}
       |  说明：${feedback.detail}
       |  对拓扑的建议：${feedback.suggestion}
       |
       |${reentryActions(project)}""".stripMargin

  /** 分发器观察桥（单例化改造后）：Completed 按 pendingInjected 延迟拆除 /
    * Failed+Cancelled 立即拆除（清 activeRef 登记 + registry + 停 agent）。
    * rootSessionId：面板终态帧的归桶键（Sub-Agents 面板实时刷新修复）。
    */
  private def dispatcherBridge(
    cfg: ProjectConfig,
    active: Ref[IO, Option[ActiveDispatcher]],
    ref: ActorRef[AgentCommand],
    rootSessionId: String,
    sessionId: String
  ): Behavior[AgentEvent] =
    def teardown: IO[Behavior[AgentEvent]] =
      (cfg.resources.agentRegistry.update(_ - sessionId) *>
        (ref ! AgentCommand.Stop("dispatcher turn done")).void *>
        logger.info(s"Project '${cfg.project.name}' dispatcher session $sessionId finished — unregistered"))
        .as(Behaviors.stopped)
    Behaviors.receive[AgentEvent] { (_, event) =>
      event match
        case AgentEvent.Completed(_, messages) =>
          // 原子裁决（Q3-a 扩展，2026-09-11 收件规则批）：本 turn 消费了几件 =
          // 会话历史里带注入来源标签（task/dispatch）的消息**增量**——mid-turn 直投
          // 落地后一个 Completed 不再恒等于一件（AgentActor.drainUserBatch 在
          // tools-complete 边界整队合批：N 件 = 1 次注入 = 1 个 turn = 1 个 Completed）。
          // 增量 ≤0（会话中期历史被压缩等）降级为「一 Completed 一件」（改前行为）：
          // 宁多消费不滞留。pendingInjected 与 pendingTaskTexts 恒等长（spawn 首条
          // prompt 也计 1 件，见 spawnDispatcher 的 active.set），故两者同减 k。
          // 拆除判据：k 件消费后仍有余件（未跑完的注入件）→ 保活；归零 → 拆除。
          // **R7-b 桥收敛（R2「一个 Mail 统一」批 2026-09-12，作者裁定 D-4）**：
          // 本桥**不再**每 turn 无条件把最终 assistant 文本投递给 Nebula root
          // （旧路径 `NodeEngine.deliverDispatcherOutputToNebula` 同批删净）。root
          // 注入面 100% 由显式载体驱动——分发器必须自己
          // `Mail(address="Nebula", type=RESULT, chainId=<本批链 id>, …)`。
          // 本桥职责收敛为：teardown（清 activeRef 登记 + registry + 停 agent）
          // 与 Failed/Cancelled 的面板终态帧；turn 级自动摘要消失。
          // 观测口径：source=="dispatcher" 族自本批起**生产者恒 0**。
          val seenInjectedMsgs =
            messages.count(m => m.source.exists(DispatcherInjectedSources.contains))
          active.modify {
            case Some(a) if a.sessionId == sessionId =>
              val consumed =
                if seenInjectedMsgs > a.consumedTaskMsgs then seenInjectedMsgs - a.consumedTaskMsgs
                else if a.pendingInjected > 0 then 1
                else 0
              val k = math.max(0, math.min(consumed, math.min(a.pendingInjected, a.pendingTaskTexts.size)))
              val remaining = a.pendingInjected - k
              if remaining > 0 then
                (Some(a.copy(
                  pendingInjected = remaining,
                  pendingTaskTexts = a.pendingTaskTexts.drop(k),
                  consumedTaskMsgs = math.max(a.consumedTaskMsgs, seenInjectedMsgs)
                )), false)
              else (None, true)
            case _ => (None, true)
          }.flatMap { teardownNow =>
            if teardownNow then teardown
            else IO.pure(dispatcherBridge(cfg, active, ref, rootSessionId, sessionId))
          }
        case AgentEvent.Failed(_, _) | AgentEvent.Cancelled(_, _) =>
          // 面板实时终态帧（Sub-Agents 面板取消/终止实时刷新修复）：取消与
          // 致命失败此前零 WS 出口（会话级 done 只走正常完成路径）→ 面板行由
          // agentStart 创建后幽灵滞留到浏览器刷新。这里是分发器全部异常终态
          // 路径（AgentControl cancel / 面板 cancelAgent / watcher giveUp /
          // 会话崩溃）的唯一汇合点，补发一次 agentDone 同构帧即全覆盖。
          NodeRunner
            .emitSubagentPanelDone(cfg.engine.wsSendFn, sessionId, rootSessionId)
            .handleErrorWith(_ => IO.unit) *>
            active.update(_.filterNot(_.sessionId == sessionId)) *> teardown
    }

  private def dispatchTask(
    cfg: ProjectConfig,
    active: Ref[IO, Option[ActiveDispatcher]],
    same: Behavior[ProjectCommand],
    taskText: String,
    rootSessionId: String,
    source: String = SourceTask
  ): IO[Behavior[ProjectCommand]] =
    // 单例化裁定：先经 modify 原子占位（占位与桥终态清理在全序 Ref 操作上不可
    // 交错——占位成功则桥必见 pendingInjected>0 而延迟拆除）——有活跃会话 →
    // 任务注入现有会话（turn 边界生效：处理中排 pendingUserInputs，idle 直接
    // 开新 turn）；无（含占位瞬间会话刚终结）→ spawn 新实例。
    // source（Q2-B2）：Task 入口 = task；DispatchNotify 回流通知 = dispatch。
    active.modify {
      case Some(a) =>
        (Some(a.copy(pendingInjected = a.pendingInjected + 1, pendingTaskTexts = a.pendingTaskTexts :+ taskText)), Some(a))
      case None => (None, None)
    }.flatMap {
      case Some(a) =>
        (a.agentRef ! AgentCommand.UserInput(
          text = taskInjectionText(taskText),
          replyTo = Some(a.bridgeRef),
          source = Some(source)
        )).void *>
          logger
            .info(
              s"Project '${cfg.project.name}' task injected into active dispatcher ${a.sessionId} (pending=${a.pendingInjected}, source=$source)"
            )
            .as(same)
      case None =>
        // 裁定①（20260907 方向 B）：无快照获取——spawn prompt 只组任务文本+目录+记忆
        // TaskBoard 批 2（§3a）：spawn 形态追加任务板块（注入活跃会话形态
        // taskInjectionText 不重复注入——会话 spawn 时已带当次板快照，同项目记忆纪律）。
        pluginCatalogText().flatMap { catalog =>
          projectMemoryText(cfg.project).flatMap { memory =>
            dispatcherBoardText(cfg).flatMap { boardText =>
              spawnDispatcher(cfg, active, same, newTaskPrompt(cfg.project, taskText, catalog, memory, boardText), rootSessionId, "", taskText, source)
            }
          }
        }
    }

  private def dispatchReentry(
    cfg: ProjectConfig,
    active: Ref[IO, Option[ActiveDispatcher]],
    same: Behavior[ProjectCommand],
    nodeId: String,
    feedback: BlockedFeedback,
    blockCount: Int,
    rootSessionId: String
  ): IO[Behavior[ProjectCommand]] =
    cfg.engine.store.getNode(nodeId).flatMap {
      case None =>
        logger.warn(s"Project '${cfg.project.name}' reentry skipped — node '$nodeId' not found").as(same)
      case Some(node) =>
        // 单例化裁定 × 反馈协议（§2.2 修订）：重入调整请求同样优先投递活跃
        // 分发器会话（不并行 spawn 重入会话）；会话已不活跃才走 spawn 路径。
        active.modify {
          case Some(a) =>
            (Some(a.copy(pendingInjected = a.pendingInjected + 1, pendingTaskTexts = a.pendingTaskTexts :+ reentryTaskText(node, feedback, blockCount))), Some(a))
          case None => (None, None)
        }.flatMap {
          case Some(a) =>
            (a.agentRef ! AgentCommand.UserInput(
              text = reentryInjectionText(cfg.project, node, feedback, blockCount),
              replyTo = Some(a.bridgeRef),
              // 重入是 task 形态入口（FeedbackRouter 独占 blocked 重入，非 DispatchNotify
              // 回流）——来源标签与 Task 入口同值（Q2-B2 三分之外的既有语义，保持不变）。
              source = Some(SourceTask)
            )).void *>
              logger
                .info(
                  s"Project '${cfg.project.name}' reentry (round $blockCount: ${node.name}) injected into active dispatcher ${a.sessionId}"
                )
                .as(same)
          case None =>
            // 裁定①（20260907 方向 B）：重入 spawn 同样不嵌快照（reentryActions 自带先 NodeList）
            pluginCatalogText().flatMap { catalog =>
              projectMemoryText(cfg.project).flatMap { memory =>
                dispatcherBoardText(cfg).flatMap { boardText =>
                  spawnDispatcher(cfg, active, same, reentryPrompt(cfg.project, node, feedback, blockCount, catalog, memory, boardText), rootSessionId,
                    s" (reentry round $blockCount: ${node.name})", reentryTaskText(node, feedback, blockCount))
                }
              }
            }
        }
    }

  /** 重入任务的触发文本（投递标注摘要来源；一行可读描述而非全文注入 prompt）。 */
  private def reentryTaskText(node: NodeDef, feedback: BlockedFeedback, blockCount: Int): String =
    s"[reentry] 节点 ${node.name} blocked 第 $blockCount 轮（${feedback.category}）"

  private def spawnDispatcher(
    cfg: ProjectConfig,
    active: Ref[IO, Option[ActiveDispatcher]],
    same: Behavior[ProjectCommand],
    prompt: String,
    rootSessionId: String,
    tag: String,
    firstTaskText: String,
    source: String = SourceTask
  ): IO[Behavior[ProjectCommand]] =
    val project = cfg.project
    EntityLoader.loadAgent(DispatcherAgentName).flatMap {
      case None =>
        logger.warn(s"Dispatcher agent '$DispatcherAgentName' not found — cannot trigger project '${project.name}'")
        IO.pure(same)
      case Some(entry) =>
        val sessionId = s"$DispatcherSessionPrefix${java.util.UUID.randomUUID().toString.take(8)}"
        for
          ref <- NodeRunner.spawnAgentActor(
            cfg.system,
            NodeRunner.SpawnParams(
              agentDef = entry.toAgentDef,
              resources = cfg.resources,
              sessionId = sessionId,
              sessionName = s"dispatcher/${project.name}",
              depth = 1,
              parentRef = None,
              // #28 可观测接线：与节点同款路由包装——分发器事件注入
              // rootSessionId/nodeSessionId 后在 subagent 面板可见
              // （Processing 状态 + 工具调用过程，与 Delegate/SubTask 同标准）。
              // project：agentStart 帧注入项目名（面板项目徽标，2026-09-06）。
              wsSend = NodeRunner.routeSubagentWsSend(cfg.engine.wsSendFn, rootSessionId, sessionId, Some(project.name)),
              projectRoot = Some(project.workspace),
              safetyMode = "confirm-edits",
              rootSessionId = rootSessionId,
              isFlowNode = true,
              // TaskBoard 批 2（§1d）：分发器引擎侧身份标记 + 项目上下文——经
              // AgentCore 透传 ToolContext（flowNodeId/isDispatcher/projectName），
              // 是 TaskBoard 权限矩阵（全权列）与 project 缺省解析的身份来源；
              // 工具内不信客户端参数（安全红线）。
              isDispatcher = true,
              projectName = Some(project.name),
              // 链级抽象 P2（20260910 spec §9.2 项 6）：分发器**不属任何链**——链是
              // Flow Map 节点集上的弱连通分量，分发器会话不是节点、无 NodeDef.id。
              // 显式置 None（口径显式化，非依赖默认值）：ProjectActor 是唯一非节点
              // 的项目域 spawn 点，未来若有人在此误传链值，本节注释即口径锚点。
              flowChainId = None,
              // 阶段 2a 沙箱（H-5①）：分发器 root=project workspace——worktree
              // 天然建在 <workspace>/.nebflow/ 内，git worktree add 写主仓 .git
              // 亦在界内。
              sandboxEnabled = true,
              // 项目会话信号（沙箱拆围栏批 S1/R8 解耦）：分发器 = 项目作用域会话
              // ⇒ AGENTS.md 注入判据置位（接收面 = 项目分发器 + 节点会话不变）。
              projectSession = true
            )
          )
          // 单次会话观察桥（#28 可观测收尾）：分发器 turn 完成 → 清 registry +
          // 停 agent。此前分发器无回报（replyTo=None）→ turn 后 agent 长期 idle、
          // registry 条目永久滞留（kind=Flow 会被 getActiveAgents 快照当运行中
          // 幽灵行上报）。桥复用 NodeEngine 同款 bridge 模式。
          // 卡死处置接线（dispatcher 会话卡死修复）：bridge 先于注册 spawn，
          // 以 supervisorRef=bridgeRef 注册——bridge 的 Cancelled 分支成为分发器
          // 的取消通道（AgentControl cancel / 面板 cancelAgent / TaskStuckWatcher
          // giveUp 三方共用同一条 AgentEvent.Cancelled → 清 registry + 停 agent
          // 链路）。分发器单次会话无 BackoffSupervisor，桥就是它的监督终态载体。
          // 单例化裁定（2026-09-02）：Completed 按 pendingInjected 延迟拆除——
          // 注入任务（replyTo=桥）的 turn 尚未终结时保会话存活，计数归零后的
          // 首个 Completed（=最后一 turn 终态）才清 registry+停 agent。Failed/
          // Cancelled 不延迟：致命失败是一次性终态（排队任务随会话丢弃，与
          // failed 语义一致），取消本就是立即终止语义。
          bridgeRef <- cfg.system.spawn(dispatcherBridge(cfg, active, ref, rootSessionId, sessionId), s"dispatchbridge-${sessionId.take(8)}")
          // 注册元数据对齐 DelegateTool 注册约定（AgentControl 注释原文）：
          // startedAt/lastActivityMs 驱动 list 的 up/idle 列与卡死判定
          // （lastActivityMs=now 从出生即可见——修复「turn 在首次 LLM touch 前
          // 挂死则 watcher 永远看不见」的盲区）；supervisorRef 是 cancel 直达通道。
          _ <- cfg.resources.agentRegistry.update(
            _ + (
              sessionId -> AgentRecord(
                sessionId,
                ref,
                AgentKind.Flow,
                rootSessionId,
                startedAt = System.currentTimeMillis(),
                lastActivityMs = System.currentTimeMillis(),
                supervisorRef = Some(bridgeRef),
                // 恢复路径项目徽标：activeAgents 快照 → activeAgentEntryJson
                // 输出 project（分发器行刷新后仍标注项目名）。
                project = Some(cfg.project.name),
                displayName = Some(s"dispatcher/${project.name}")
              )
            )
          )
          // 单例化登记（顺序铁律）：先占位 activeRef 再发首条 prompt——占位在本
          // actor handler IO 内完成，此后到达的 TriggerDispatcher /
          // ReenterDispatcher 一律注入本会话，无 spawn 竞态窗口。
          // pendingTaskTexts 初始化为 [firstTaskText]：首 turn（spawn prompt）
          // 终态时投递标注用（2026-09-05 接线）。
          // Q3-a（2026-09-11）：pendingInjected 同置 1——「已注入本会话但 turn 尚未
          // 终结的件数」首条 prompt 也是一件，**恒等式 pendingInjected ==
          // pendingTaskTexts.size**（桥的消费计数 k 直接同减两者）；且首条 prompt
          // 也打同源标签（source）：桥的消费增量按「历史里带源消息条数」判定，
          // 首条缺标签会让增量恒差 1（批量场景下演变成少消费 → 会话滞留）。
          _ <- active.set(Some(ActiveDispatcher(sessionId, ref, bridgeRef, pendingInjected = 1, pendingTaskTexts = List(firstTaskText))))
          _ <- (ref ! AgentCommand.UserInput(text = prompt, replyTo = Some(bridgeRef), source = Some(source))).void
          _ <- logger.info(s"Project '${project.name}' dispatcher session spawned: $sessionId$tag")
        yield same
    }
