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
 * 2. TTL 定时器：终态节点 5min 显示消失 → 移归档 + WS nodeRemoved（TTL 只管
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
    case CancelNode(nodeId: String)
    case TtlTick
    case Shutdown

  /** 分发器全局 agent 名（定义文件交付 Nebula/entity-creator 注册）。 */
  val DispatcherAgentName = "project-dispatcher"

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
    * TTL 只管终态节点 5min 显示消失；Node 运行本身不设超时（硬约束）。 */
  def ttlScanner(intervalSec: Int): IO[Unit] =
    def loop: IO[Unit] =
      IO.sleep(intervalSec.seconds) *>
        ProjectRuntimeRegistry.all.flatMap { rts =>
          rts.traverse_(rt => rt.actorRef.fold(IO.unit)(ref => (ref ! ProjectCommand.TtlTick).void))
        } *> loop
    loop

  def apply(cfg: ProjectConfig): Behavior[ProjectCommand] =
    // 无内部状态（状态在 store/engine），保持同一 behavior 即可（InteractionHub 模式）。
    lazy val behavior: Behavior[ProjectCommand] =
      Behaviors.receiveMessage {
        case ProjectCommand.TriggerDispatcher(taskText, rootSessionId) =>
          spawnDispatcher(cfg, behavior, taskText, rootSessionId)
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

  private def spawnDispatcher(cfg: ProjectConfig, same: Behavior[ProjectCommand], taskText: String, rootSessionId: String): IO[Behavior[ProjectCommand]] =
    val project = cfg.project
    EntityLoader.loadAgent(DispatcherAgentName).flatMap {
      case None =>
        logger.warn(s"Dispatcher agent '$DispatcherAgentName' not found — cannot trigger project '${project.name}'")
        IO.pure(same)
      case Some(entry) =>
        val sessionId = s"dispatcher-${java.util.UUID.randomUUID().toString.take(8)}"
        for
          snapshot <- cfg.engine.store.snapshot
          prompt =
            s"""你是项目「${project.name}」的任务分发器。当前 Flow Map 快照（NodeList 数据源）：
               |```json
               |${snapshot.asJson.noSpaces}
               |```
               |
               |任务：$taskText
               |
               |先 NodeList 读现状，再按需用 NodeEdit 建节点/接线/改接。所有 Node 工具调用必须带 project=${project.name} 参数。无需回报——拓扑与状态已落 Flow Map。""".stripMargin
          ref <- NodeRunner.spawnAgentActor(
            cfg.system,
            NodeRunner.SpawnParams(
              agentDef = entry.toAgentDef,
              resources = cfg.resources,
              sessionId = sessionId,
              sessionName = s"dispatcher/${project.name}",
              depth = 1,
              parentRef = None,
              wsSend = cfg.engine.wsSendFn,
              projectRoot = Some(project.workspace),
              safetyMode = "confirm-edits",
              rootSessionId = rootSessionId,
              isFlowNode = true
            )
          )
          _ <- cfg.resources.agentRegistry.update(_ + (sessionId -> AgentRecord(sessionId, ref, AgentKind.Flow, rootSessionId)))
          _ <- (ref ! AgentCommand.UserInput(text = prompt, replyTo = None)).void
          _ <- logger.info(s"Project '${project.name}' dispatcher session spawned: $sessionId")
        yield same
    }
