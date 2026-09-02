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
 * 1. TriggerDispatcher → spawn 分发器会话（单次会话 agent，fresh session：
 *    Flow Map 快照 + 任务文本注入；无回报——拓扑/状态已落 Flow Map）
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
    // 无内部状态（状态在 store/engine/router），保持同一 behavior 即可（InteractionHub 模式）。
    lazy val behavior: Behavior[ProjectCommand] =
      Behaviors.receiveMessage {
        case ProjectCommand.TriggerDispatcher(taskText, rootSessionId) =>
          dispatchTask(cfg, behavior, taskText, rootSessionId)
        case ProjectCommand.ReenterDispatcher(nodeId, feedback, blockCount) =>
          dispatchReentry(cfg, behavior, nodeId, feedback, blockCount, cfg.rootSessionId)
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
       |先 NodeList 读现状（重点关注：status=blocked 节点、其下游 pending 节点），
       |再从以下动作中选择并执行（NodeEdit / NodeCancel）：
       |1. 任务可修 → NodeEdit 编辑该节点（改 task/agent/in/out）触发重激活（blockCount 自动 +1）；
       |2. 任务应拆分 → 建新节点子图替换，NodeEdit abandon=true 标记旧节点放弃；
       |3. agent 能力不匹配 → 换 agent 重建；
       |4. 需外部条件 / 无法提出与上轮实质不同的调整 → abandon + 不重派（避免无效循环）。
       |所有 Node 工具调用必须带 project=${project.name}。无需回报——拓扑与状态已落 Flow Map。""".stripMargin

  private def dispatchTask(cfg: ProjectConfig, same: Behavior[ProjectCommand], taskText: String, rootSessionId: String): IO[Behavior[ProjectCommand]] =
    cfg.engine.store.snapshot.flatMap { snapshot =>
      spawnDispatcher(cfg, same, newTaskPrompt(cfg.project, snapshot, taskText), rootSessionId, "")
    }

  private def dispatchReentry(
    cfg: ProjectConfig,
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
        cfg.engine.store.snapshot.flatMap { snapshot =>
          spawnDispatcher(cfg, same, reentryPrompt(cfg.project, snapshot, node, feedback, blockCount), rootSessionId,
            s" (reentry round $blockCount: ${node.name})")
        }
    }

  private def spawnDispatcher(
    cfg: ProjectConfig,
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
          bridgeRef <- cfg.system.spawn(
            Behaviors.receive[AgentEvent] { (_, event) =>
              event match
                case AgentEvent.Completed(_, _) | AgentEvent.Failed(_, _) | AgentEvent.Cancelled(_, _) =>
                  (cfg.resources.agentRegistry.update(_ - sessionId) *>
                    (ref ! AgentCommand.Stop("dispatcher turn done")).void *>
                    logger.info(s"Project '${project.name}' dispatcher session $sessionId finished — unregistered"))
                    .as(Behaviors.stopped)
            },
            s"dispatchbridge-${sessionId.take(8)}"
          )
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
          _ <- (ref ! AgentCommand.UserInput(text = prompt, replyTo = Some(bridgeRef))).void
          _ <- logger.info(s"Project '${project.name}' dispatcher session spawned: $sessionId$tag")
        yield same
    }
