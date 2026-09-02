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
 *    分发器会话（单次会话 agent，fresh session：Flow Map 快照 + 任务文本注入；
 *    无回报——拓扑/状态已落 Flow Map）。ReenterDispatcher（blocked 重入）同规。
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
  actorRef: Option[ActorRef[ProjectActor.ProjectCommand]] = None
)

/** 全局注册表：project name → runtime。 */
object ProjectRuntimeRegistry:
  private val runtimes = Ref.unsafe[IO, Map[String, ProjectRuntime]](Map.empty)

  def register(rt: ProjectRuntime): IO[Unit] = runtimes.update(_ + (rt.project.name -> rt))
  def unregister(name: String): IO[Unit] = runtimes.update(_ - name)
  def get(name: String): IO[Option[ProjectRuntime]] = runtimes.get.map(_.get(name))
  def all: IO[List[ProjectRuntime]] = runtimes.get.map(_.values.toList)
  def clear: IO[Unit] = runtimes.set(Map.empty)

  /** 启动自动挂载（0b 契约「阶段 1 补自动挂载」，#37 重启窗口收尾）：
    * 磁盘已有项目 → 幂等挂载。rootSessionId = 顶层 Nebula 主会话 id
    * （启动期无会话上下文，调用方直接传顶层根——A 修复后的 thread 语义）。
    * 与 ProjectCreate 幂等挂载（运行时主动路径，241ef6c5）互补：
    * 本函数管重启免人工，ProjectCreate 管运行时挂载/重挂。返回新挂载数。
    * wsSend：启动期传入 wsHub.broadcast——否则 engine 的 wsSendFn 是 no-op，
    * 节点/分发器事件永远到不了前端（#28 可观测缺口根因之一）。 */
  def mountAll(
    projects: List[ProjectDef],
    rootSessionId: String,
    system: ActorSystem,
    resources: SharedResources,
    wsSend: Option[Json => IO[Unit]] = None
  ): IO[Int] =
    projects.foldLeft(IO.pure(0)) { (acc, pd) =>
      acc.flatMap { n =>
        get(pd.name).flatMap {
          case Some(_) => IO.pure(n) // 已挂载跳过（启动时序无并发，防御性判断）
          case None    => mount(pd, system, resources, wsSend, rootSessionId).as(n + 1)
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
    ttlCheckIntervalSec: Int = 30
  ): IO[ProjectRuntime] =
    get(project.name).flatMap {
      case Some(rt) => IO.pure(rt)
      case None =>
        for
          store <- FlowMapStore.open(project.name, project.workspace)
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
              emitNodeEvent(project.name, wsSend)
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
                ttlCheckIntervalSec = ttlCheckIntervalSec
              )
            ),
            s"project-${project.name.take(20)}"
          )
          rt = ProjectRuntime(project, store, engine, system, resources, Some(actorRef))
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
    case TriggerDispatcher(taskText: String, rootSessionId: String)
    /** blocked 反馈重入（设计 §2.2）：FeedbackRouter 裁决通过 → spawn 全新分发器会话
      * 注入重入 prompt。无 rootSessionId 参数——重入是系统发起，用挂载时的 root。 */
    case ReenterDispatcher(nodeId: String, feedback: BlockedFeedback, blockCount: Int)
    case CancelNode(nodeId: String)
    case TtlTick
    case Shutdown

  /** 分发器全局 agent 名（定义文件交付 Nebula/entity-creator 注册）。 */
  val DispatcherAgentName = "project-dispatcher"

  /** 分发器会话 id 前缀（sessionId = "dispatcher-<uuid8>"）。AgentControl/
    * 面板的 cancel 白名单按此识别新 Project 系统会话（与 NodeEngine.SessionPrefix
    * 同款跨层契约；前端 utils.isBgAgentId 硬编码同值）。 */
  val DispatcherSessionPrefix = "dispatcher-"

  /** 活跃分发器会话登记（单例化裁定 2026-09-02）。spawn 时置位、观察桥终态时
    * 清除（与 agentRegistry 同点清理）。pendingInjected = 已注入本会话但 turn
    * 尚未终结的任务数：注入任务以 UserInput(replyTo=观察桥) 进入，每个任务
    * 恰好产出一个 Completed 终态事件——桥见 Completed 时 pendingInjected>0 →
    * 延迟拆除（-1，后续注入 turn 还要跑）；=0 → 正常拆除（最后一 turn 已终态）。 */
  case class ActiveDispatcher(
    sessionId: String,
    agentRef: ActorRef[AgentCommand],
    bridgeRef: ActorRef[AgentEvent],
    pendingInjected: Int = 0
  )

  case class ProjectConfig(
    project: ProjectDef,
    engine: NodeEngine,
    system: ActorSystem,
    resources: SharedResources,
    rootSessionId: String,
    ttlDisplayMs: Long = NodeEngine.TtlDisplayMs,
    ttlCheckIntervalSec: Int = 30
  )

  /** 全局 TTL 扫描：周期给所有已挂载 ProjectActor 发 TtlTick（GatewayMain 启动）。
    * TTL 只管终态节点 24h 显示消失；Node 运行本身不设超时（硬约束）。
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
            case ProjectCommand.TriggerDispatcher(taskText, rootSessionId) =>
              dispatchTask(cfg, active, behavior, taskText, rootSessionId)
            case ProjectCommand.ReenterDispatcher(nodeId, feedback, blockCount) =>
              dispatchReentry(cfg, active, behavior, nodeId, feedback, blockCount, cfg.rootSessionId)
            case ProjectCommand.CancelNode(nodeId) =>
              cfg.engine.cancelNodeById(nodeId).as(behavior)
            case ProjectCommand.TtlTick =>
              cfg.engine.store.sweepExpired(System.currentTimeMillis()).flatMap { removed =>
                removed.traverse_(id => cfg.engine.emitRemoved(id))
              }.as(behavior)
            case ProjectCommand.Shutdown =>
              IO.pure(Behaviors.stopped)
          }
        behavior
      }
    }

  /** 新任务形态 prompt（spawnDispatcher 双形态之一，现状文案保留）。 */
  private def newTaskPrompt(project: ProjectDef, snapshot: FlowMapState, taskText: String): String =
    s"""你是项目「${project.name}」的任务分发器。当前 Flow Map 快照（NodeList 数据源）：
       |```json
       |${snapshot.asJson.noSpaces}
       |```
       |
       |任务：$taskText
       |
       |先 NodeList 读现状，再按需用 NodeEdit 建节点/接线/改接。所有 Node 工具调用必须带 project=${project.name} 参数。无需回报——拓扑与状态已落 Flow Map。""".stripMargin

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
    * 含节点名/id/blockCount/反馈三字段/Flow Map 快照注入/四动作选择/abandon 说明/「无需回报」）。 */
  private def reentryPrompt(project: ProjectDef, snapshot: FlowMapState, node: NodeDef, feedback: BlockedFeedback, blockCount: Int): String =
    s"""你是项目「${project.name}」的任务分发器——本轮是【节点反馈重入调整】，不是新任务。
       |
       |节点 ${node.name}（${node.id}）报告 blocked（第 $blockCount 轮）：
       |  原因分类：${feedback.category}
       |  说明：${feedback.detail}
       |  对拓扑的建议：${feedback.suggestion}
       |
       |当前 Flow Map 快照（NodeList 数据源）：
       |```json
       |${snapshot.asJson.noSpaces}
       |```
       |
       |${reentryActions(project)}""".stripMargin

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
        case AgentEvent.Completed(_, _) =>
          // 原子裁决：pendingInjected>0 → 计数-1 保活（注入 turn 未跑完）；
          // =0 → 该 Completed 即最后一 turn 终态 → 拆除。modify 全序保证与
          // 注入占位不可交错（占位成功 → 本次必见 >0）。
          active.modify {
            case Some(a) if a.sessionId == sessionId && a.pendingInjected > 0 =>
              (Some(a.copy(pendingInjected = a.pendingInjected - 1)), false)
            case _ => (None, true)
          }.flatMap {
            case true  => teardown
            case false => IO.pure(dispatcherBridge(cfg, active, ref, rootSessionId, sessionId))
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
    rootSessionId: String
  ): IO[Behavior[ProjectCommand]] =
    // 单例化裁定：先经 modify 原子占位（占位与桥终态清理在全序 Ref 操作上不可
    // 交错——占位成功则桥必见 pendingInjected>0 而延迟拆除）——有活跃会话 →
    // 任务注入现有会话（turn 边界生效：处理中排 pendingUserInputs，idle 直接
    // 开新 turn）；无（含占位瞬间会话刚终结）→ spawn 新实例。
    active.modify {
      case Some(a) => (Some(a.copy(pendingInjected = a.pendingInjected + 1)), Some(a))
      case None    => (None, None)
    }.flatMap {
      case Some(a) =>
        (a.agentRef ! AgentCommand.UserInput(
          text = taskInjectionText(taskText),
          replyTo = Some(a.bridgeRef),
          source = Some("task")
        )).void *>
          logger
            .info(
              s"Project '${cfg.project.name}' task injected into active dispatcher ${a.sessionId} (pending=${a.pendingInjected})"
            )
            .as(same)
      case None =>
        cfg.engine.store.snapshot.flatMap { snapshot =>
          spawnDispatcher(cfg, active, same, newTaskPrompt(cfg.project, snapshot, taskText), rootSessionId, "")
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
          case Some(a) => (Some(a.copy(pendingInjected = a.pendingInjected + 1)), Some(a))
          case None    => (None, None)
        }.flatMap {
          case Some(a) =>
            (a.agentRef ! AgentCommand.UserInput(
              text = reentryInjectionText(cfg.project, node, feedback, blockCount),
              replyTo = Some(a.bridgeRef),
              source = Some("task")
            )).void *>
              logger
                .info(
                  s"Project '${cfg.project.name}' reentry (round $blockCount: ${node.name}) injected into active dispatcher ${a.sessionId}"
                )
                .as(same)
          case None =>
            cfg.engine.store.snapshot.flatMap { snapshot =>
              spawnDispatcher(cfg, active, same, reentryPrompt(cfg.project, snapshot, node, feedback, blockCount), rootSessionId,
                s" (reentry round $blockCount: ${node.name})")
            }
        }
    }

  private def spawnDispatcher(
    cfg: ProjectConfig,
    active: Ref[IO, Option[ActiveDispatcher]],
    same: Behavior[ProjectCommand],
    prompt: String,
    rootSessionId: String,
    tag: String
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
              wsSend = NodeRunner.routeSubagentWsSend(cfg.engine.wsSendFn, rootSessionId, sessionId),
              projectRoot = Some(project.workspace),
              safetyMode = "confirm-edits",
              rootSessionId = rootSessionId,
              isFlowNode = true
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
                supervisorRef = Some(bridgeRef)
              )
            )
          )
          // 单例化登记（顺序铁律）：先占位 activeRef 再发首条 prompt——占位在本
          // actor handler IO 内完成，此后到达的 TriggerDispatcher /
          // ReenterDispatcher 一律注入本会话，无 spawn 竞态窗口。
          _ <- active.set(Some(ActiveDispatcher(sessionId, ref, bridgeRef)))
          _ <- (ref ! AgentCommand.UserInput(text = prompt, replyTo = Some(bridgeRef))).void
          _ <- logger.info(s"Project '${project.name}' dispatcher session spawned: $sessionId$tag")
        yield same
    }
