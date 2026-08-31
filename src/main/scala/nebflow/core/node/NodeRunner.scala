package nebflow.core.node

import cats.effect.IO
import io.circe.{Json, JsonObject}
import nebflow.actor.*
import nebflow.agent.*
import nebflow.core.tools.{FileHistory, ReadTracker}
import nebflow.shared.Message

/**
 * NodeRunner 共享执行内核（统一分析方案 A，阶段 0 —— #28）。
 *
 * Delegate / SubTask / flow 节点三处重复的 spawn 逻辑收敛于此：
 * 1. **spawn** —— readTracker/fileHistory 创建 + AgentActor 构造（三处统一）
 * 2. **registry** —— AgentRegistry 注册（AgentRecord 统一构造）
 * 3. **结果捕获 adapter** —— BackoffSupervisor 构造（delegate/subtask 共用）
 *
 * 行为零变化纪律（R9）：所有参数逐一透传、默认值与各调用方现值一致；
 * 结果形态差异（ExternalEvent vs ImmediateInput vs bridge-deferred）属调用方
 * 契约，保留各处。本文件只消除重复、不改变语义——改动后全 spec 回归必须全绿
 * 才进 0b（新模型 Node 工具集）。
 *
 * 使用约定：
 * - `spawnAgentActor`：初始 spawn（带 readTracker/fileHistory）；restart 重建
 *   传 `withTracking = false`（与旧 BackoffSupervisor childSpawnFn 一致——
 *   现状 restart 重建的 AgentActor 不携带 readTracker/fileHistory）。
 * - `spawnSupervisedAdapter`：BackoffSupervisor 包裹（delegate/subtask 的
 *   崩溃自动重启 + AgentEvent.Cancelled 处理）；flow 节点的 bridge-deferred
 *   捕获在 FlowDagExecutor 内（结构差异大，属 flow 专属监督语义，不外抽）。
 */
object NodeRunner:

  /** AgentActor spawn 参数（覆盖 Delegate/SubTask/flow 三处差异面）。 */
  final case class SpawnParams(
    agentDef: AgentDef,
    resources: SharedResources,
    sessionId: String,
    sessionName: String,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    wsSend: Json => IO[Unit],
    /** None = 不设置（flow 节点现状：沿用 agent 自身 projectRoot）。 */
    projectRoot: Option[String] = None,
    safetyMode: String = "confirm-edits",
    /** 已解析的根 session（调用方算好传入：delegate/subtask 用
      * `if root.nonEmpty then root else parentSessionId.getOrElse(id)`；
      * flow 用 `if root.nonEmpty then root else sessionId`）。 */
    rootSessionId: String = "",
    initialMessages: List[Message] = Nil,
    isSubTaskWorker: Boolean = false,
    isFlowNode: Boolean = false,
    expectsMail: Boolean = false,
    userFacingNode: Boolean = false,
    /** actor 名字（默认 = sessionId；flow 节点用 "dagnode-<nodeId>-<sid>" 前缀）。 */
    actorName: String = "",
    /** restart 重建传 false（旧 childSpawnFn 的 AgentActor 不带
      * readTracker/fileHistory，保持行为零变化）。 */
    withTracking: Boolean = true
  )

  /** 共享 spawn：readTracker/fileHistory 创建 + AgentActor spawn。 */
  def spawnAgentActor(system: ActorSystem, p: SpawnParams): IO[ActorRef[AgentCommand]] =
    val actorName = if p.actorName.nonEmpty then p.actorName else p.sessionId
    for
      readTracker <- ReadTracker.create
      fileHistory <- FileHistory.create()
      ref <- system.spawn(
        AgentActor(
          agentDef = p.agentDef,
          resources = p.resources,
          wsSend = p.wsSend,
          depth = p.depth,
          parentRef = p.parentRef,
          sessionId = Some(p.sessionId),
          sessionName = Some(p.sessionName),
          initialMessages = p.initialMessages,
          readTracker = if p.withTracking then Some(readTracker) else None,
          fileHistory = if p.withTracking then Some(fileHistory) else None,
          contextWindow = p.resources.contextWindow,
          projectRoot = p.projectRoot,
          safetyMode = p.safetyMode,
          rootSessionId = p.rootSessionId,
          isSubTaskWorker = p.isSubTaskWorker,
          isFlowNode = p.isFlowNode,
          expectsMail = p.expectsMail,
          userFacingNode = p.userFacingNode
        ),
        actorName
      )
    yield ref

  /** 共享 registry 注册（AgentRecord 统一构造；默认值 = AgentRecord 默认）。 */
  def registerAgent(
    resources: SharedResources,
    id: String,
    ref: ActorRef[AgentCommand],
    kind: AgentKind,
    rootSessionId: String,
    parentRef: Option[ActorRef[AgentCommand]] = None,
    startedAt: Long = 0L,
    lastActivityMs: Long = 0L,
    supervisorRef: Option[ActorRef[AgentEvent]] = None,
    parentSessionId: String = ""
  ): IO[Unit] =
    resources.agentRegistry.update(
      _ + (
        id -> AgentRecord(
          sessionId = id,
          ref = ref,
          kind = kind,
          rootSessionId = rootSessionId,
          parentRef = parentRef,
          startedAt = startedAt,
          lastActivityMs = lastActivityMs,
          supervisorRef = supervisorRef,
          parentSessionId = parentSessionId
        )
      )
    )

  /** 共享 BackoffSupervisor adapter spawn（delegate/subtask 共用）。
    * childSpawnFn 用 spawnAgentActor(withTracking=false) 重建——与旧
    * childSpawnFn 的 AgentActor 构造逐一对应（recoveredMessages 为空时
    * 行为与直接传 recovered 等价，两者 Nil 同值）。 */
  def spawnSupervisedAdapter(
    system: ActorSystem,
    params: SpawnParams,
    childRef: ActorRef[AgentCommand],
    childName: String,
    description: String,
    agentName: String,
    subagentId: String,
    parentSessionId: String,
    initialPrompt: String,
    source: String,
    extraMetadata: JsonObject = JsonObject.empty,
    wsSend: Option[Json => IO[Unit]] = None
  ): IO[ActorRef[AgentEvent]] =
    system.spawn(
      BackoffSupervisor(
        childRef = childRef,
        childSpawnFn = (sys: ActorSystem, recoveredMessages: List[Message]) =>
          spawnAgentActor(
            sys,
            params.copy(
              initialMessages = recoveredMessages,
              withTracking = false
            )
          ),
        childName = childName,
        parentRef = params.parentRef,
        description = description,
        agentName = agentName,
        subagentId = subagentId,
        parentSessionId = parentSessionId,
        resources = params.resources,
        initialPrompt = initialPrompt,
        source = source,
        extraMetadata = extraMetadata,
        wsSend = wsSend
      ),
      s"$subagentId-adapter"
    )
