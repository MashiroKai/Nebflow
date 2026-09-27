/* 严格DAG第⑥步第三批A裁定(dwfq-5c7a31ea-1):本文件承载 core←agent 依赖倒置的三件套——
 * (1) AgentRuntimePort:agent.SharedResources 的窄视图(M1,先例 DeviceIdentityView/SessionStorePort):
 *     成员面 = core 全部消费点实读实调的签名镜像(含 R-A 的 agentRegistry,经继承
 *     AgentRegistryPort;含 R-C 的三工厂镜像 agentActorBehavior/spawnAgentActor/
 *     spawnSupervisedAdapter);签名零 agent 符号(全为 core/actor/shared 与既有 core 端口)。
 *     agent 的 SharedResources 原地 extends(方法体 = core 原内联逻辑逐字迁移,零行为差),
 *     既有构造/传参点经子类型继续编译。
 * (2) SubAgentTaskStorePort:agent.SubAgentTaskStore 的窄口(M1,四成员签名镜像;模型
 *     SubAgentTask 已下沉 shared);agent 实现类原地 extends。
 * (3) AgentLibraryView:agent.AgentLibrary 的窄视图(M3,消费面仅 get);agent 实现类原地 extends。
 * 另含三个 M2 注册器(先例 LlmRuntimePort/FriendRosterPort):DelegateBudgetPort /
 * SendConfirmPort / AskUserAnswerPort——core 侧 object 静态调用面经注册器倒置,agent 侧
 * 实现原地,注册点 = SharedResources 构造行(生产 boot 与测试装配共用,见各注册器注释)。
 * core 自此不再 import 或 FQN 引用 nebflow.agent(scripts/check-scala-layers.mjs 门禁锚定)。 */
package nebflow.core

import cats.effect.{IO, Ref}
import io.circe.{Json, JsonObject}
import nebflow.actor.*
import nebflow.core.node.NodeRunner
import nebflow.core.plugin.PluginMcpManager
import nebflow.core.scheduler.{ScheduledTaskService, ScheduledTaskStore}
import nebflow.core.tools.ToolContext
import nebflow.shared.{FileHistory, ReadTracker, *}

import java.util.concurrent.atomic.AtomicReference

// ── M1:SharedResources 窄视图 ───────────────────────────────────────────────

// 严格DAG第⑥步第三批A裁定(dwfq-5c7a31ea-1,R-A/R-C):窄视图倒置,成员=core 消费点
// 实读实调面,签名镜像现实现(类型窄化仅限裁定明示),行为保持。
/**
 * `agent.SharedResources` 的窄视图(严格DAG第⑥步第三批A)。core 侧(flow/entity/node/
 * processor/project/tools 家族)经本视图消费:会话存储(core.SessionStore)、统一注册表
 * (actor.AgentRecord 的 Ref,继承自 AgentRegistryPort)、actor 系统、上下文窗口、有效
 * 档位、sub-agent 任务库、插件 MCP、计划任务两件、neblink/dropbox/friend 三服务(窄化为
 * 既有 core 端口)、健康监视(窄化 ProviderHealthPort)、交互 hub 引用(命令 ADT 已下沉
 * actor)、以及三个 agent 构造工厂(R-C 镜像)。agent 的 `SharedResources` 原地 extends 本
 * 视图;既有构造/传参/字段点经子类型继续编译,接线零改动。
 */
trait AgentRuntimePort extends AgentRegistryPort:

  /** 镜像 `SharedResources.sessionStore`(core.SessionStore,a315825 已下沉 core)。 */
  def sessionStore: SessionStore

  /** 镜像 `SharedResources.actorSystem`(actor.ActorSystem,底层包)。 */
  def actorSystem: ActorSystem

  /** 镜像 `SharedResources.contextWindow`。 */
  def contextWindow: Int

  /** 镜像 `SharedResources.effectiveSafetyMode`(唯一入口,permshield S1)。 */
  def effectiveSafetyMode: IO[SafetyMode]

  /** 镜像 `SharedResources.subAgentTaskStore`(窄化为下方 SubAgentTaskStorePort)。 */
  def subAgentTaskStore: SubAgentTaskStorePort

  /** 镜像 `SharedResources.pluginMcp`(core.plugin.PluginMcpManager)。 */
  def pluginMcp: PluginMcpManager

  /** 镜像 `SharedResources.scheduledTaskStore`(core.scheduler)。 */
  def scheduledTaskStore: ScheduledTaskStore

  /** 镜像 `SharedResources.scheduledTaskService`(core.scheduler)。 */
  def scheduledTaskService: Option[ScheduledTaskService]

  /** 镜像 `SharedResources.neblinkService`(窄化批二 NeblinkServicePort)。 */
  def neblinkService: Option[NeblinkServicePort]

  /** 镜像 `SharedResources.dropboxService`(窄化批二 DropboxServicePort[?])。 */
  def dropboxService: Option[DropboxServicePort[?]]

  /** 镜像 `SharedResources.friendService`(窄化批二 FriendServicePort)。 */
  def friendService: Option[FriendServicePort]

  /** 镜像 `SharedResources.healthMonitor`(窄化批一 ProviderHealthPort,R-D 扩面)。 */
  def healthMonitor: ProviderHealthPort

  /** 镜像 `SharedResources.interactionHubRef`(命令 ADT 已按 B1 下沉 actor)。 */
  def interactionHubRef: Ref[IO, Option[ActorRef[InteractionHubCommand]]]

  /**
   * 共享 spawn(R-C 镜像):readTracker/fileHistory 创建 + AgentActor spawn。
   * 方法体 = core.node.NodeRunner.spawnAgentActor 原体逐字迁 SharedResources
   * (resources 以 this 代入——委托式保证 receiver == p.resources,零行为差);
   * NodeRunner 侧改为一行委托。
   */
  def spawnAgentActor(system: ActorSystem, p: NodeRunner.SpawnParams): IO[ActorRef[AgentCommand]]

  /**
   * 共享 BackoffSupervisor adapter spawn(R-C 镜像):delegate/subtask 的崩溃自动重启
   * + AgentEvent.Cancelled 处理。方法体 = core.node.NodeRunner.spawnSupervisedAdapter
   * 原体逐字迁 SharedResources(resources 以 this 代入);NodeRunner 侧改为一行委托。
   */
  def spawnSupervisedAdapter(
    system: ActorSystem,
    params: NodeRunner.SpawnParams,
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
  ): IO[ActorRef[AgentEvent]]

  /**
   * `agent.AgentActor.apply` 的工厂镜像(R-C):参数表与现 apply 逐字一致,唯去掉
   * `resources` 形参(agent 侧实现以 `resources = this` 代入——core 三个内联构造点
   * EphemeralAgentRunner/FlowTreeActor/MailTool 均以 resources 为 receiver 调用,
   * receiver == 原 resources 实参,零行为差)。默认值逐字镜像。
   */
  def agentActorBehavior(
    agentDef: AgentDef,
    wsSend: io.circe.Json => IO[Unit],
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]] = None,
    sessionId: Option[String] = None,
    sessionName: Option[String] = None,
    initialMessages: List[Message] = Nil,
    readTracker: Option[ReadTracker] = None,
    fileHistory: Option[FileHistory] = None,
    contextWindow: Int = Defaults.ContextWindow,
    projectRoot: Option[String] = None,
    rulesMd: Option[String] = None,
    agentsMd: Option[String] = None,
    folderId: Option[String] = None,
    safetyMode: String = "confirm-edits",
    gitBranch: Option[String] = None,
    expectsMail: Boolean = false,
    rootSessionId: String = "",
    isSubTaskWorker: Boolean = false,
    freezeExempt: Boolean = false,
    isFlowNode: Boolean = false,
    userFacingNode: Boolean = false,
    flowNodeId: Option[String] = None,
    isDispatcher: Boolean = false,
    flowNodeRole: Option[String] = None,
    projectName: Option[String] = None,
    flowNodeName: Option[String] = None,
    flowChainId: Option[String] = None,
    sandboxEnabled: Boolean = false,
    sandboxRoot: Option[String] = None,
    sessionCwd: Option[String] = None,
    projectSession: Boolean = false,
    compactThresholdRatio: Option[Double] = None
  ): Behavior[AgentCommand]
  end agentActorBehavior
end AgentRuntimePort

// ── M1:SubAgentTaskStore 窄口 ───────────────────────────────────────────────

// 严格DAG第⑥步第三批A裁定(dwfq-5c7a31ea-1,R-A/M1):窄口倒置,四成员签名镜像现实现
// (模型 SubAgentTask 已按 M4 下沉 shared),removeTask 无 core 消费点不入端口,行为保持。
/**
 * `agent.SubAgentTaskStore` 的窄投影。core 消费面(AgentControlTool/DelegateTool/
 * SubTaskTool)= 记录(recordTask)、状态更新(updateStatus)、在飞清单(findRunningTasks)、
 * 按任务号反查(findByTaskId)。签名与 agent.SubAgentTaskStore 对应成员逐字一致;
 * agent 的 `SubAgentTaskStore` 原地 extends 本端口。
 */
trait SubAgentTaskStorePort:
  def recordTask(task: SubAgentTask): IO[Unit]

  def updateStatus(
    parentSessionId: String,
    taskId: String,
    status: String,
    retryCount: Option[Int] = None,
    lastError: Option[String] = None,
    completedAt: Option[Long] = None
  ): IO[Unit]
  def findRunningTasks: IO[List[SubAgentTask]]
  def findByTaskId(taskId: String): IO[Option[SubAgentTask]]
end SubAgentTaskStorePort

// ── M3:AgentLibrary 窄视图 ─────────────────────────────────────────────────

// 严格DAG第⑥步第三批A裁定(dwfq-5c7a31ea-1,M3):窄视图,成员面=core 消费点实调用
// (唯 get;先例 DeviceIdentityView),只窄不宽,行为保持。
/**
 * `agent.AgentLibrary` 的窄视图:core 消费面(DelegateTool 经 ToolContext.agentLibrary
 * 解析内置 kernel def)只调用 `get(name)`。agent 的 `AgentLibrary` 原地 extends 本视图,
 * 既有构造点经子类型继续编译。
 */
trait AgentLibraryView:
  def get(name: String): IO[Option[AgentDef]]
end AgentLibraryView

// ── M2:注册器(先例 LlmRuntimePort/FriendRosterPort)──────────────────────────

// 严格DAG第⑥步第三批A裁定(dwfq-5c7a31ea-1,M2):注册器倒置,签名镜像现实现,行为保持。
/**
 * `agent.DelegateBudget.resume` 的窄投影(严格DAG第⑥步第三批A):core 消费面 =
 * AskUserQuestionTool.restoreRegistryAfterAnswer 的答复单点恢复信号(R11 第 4 层/
 * U1=C-a + U8=(ii))。agent 的 `DelegateBudget` 实现本 Face(注册点 = SharedResources
 * 构造行,生产 boot 与测试装配共用);pause/register/release 仍由 agent 侧直用,不入本面。
 */
object DelegateBudgetPort:

  /** 注册器持有的答复恢复面(resume)。 */
  trait Face:
    def resume(sessionId: String): IO[Unit]
  end Face

  private val face = new AtomicReference[Option[Face]](None)

  def install(f: Face): Unit = face.set(Some(f))

  def clear(): Unit = face.set(None)

  // 严格DAG第⑥步第三批A裁定(dwfq-5c7a31ea-1,M2):未注册兜底 = IO.unit——与
  // DelegateBudget「未 register 会话的 resume 是无害 no-op」同型空语义。唯一 core
  // 调用点必经 ctx.sharedResources=Some(即 SharedResources 已构造 ⇒ 已注册),该
  // 分支只在「agent 整包未装配」的不可达窗口存在,不产生新的失败面。
  def resume(sessionId: String): IO[Unit] =
    face.get match
      case Some(f) => f.resume(sessionId)
      case None => IO.unit
end DelegateBudgetPort

// 严格DAG第⑥步第三批A裁定(dwfq-5c7a31ea-1,M2):注册器倒置,组合面签名镜像,行为保持。
/**
 * `agent.SendConfirm` 的窄投影(严格DAG第⑥步第三批A):core 消费面 =
 * FriendMessageTool 好友支/群支的 fiber-local 挂靶(locally(targetFor(ctx, label))(io)
 * 的合并镜像——AskTarget 是 agent 私有类型不入 core 签名,组合语义逐字等值)。
 * 注册点 = SharedResources 构造行。
 */
object SendConfirmPort:

  /** 注册器持有的挂靶面(locally+targetFor 合并镜像)。 */
  trait Face:
    def locally[A](ctx: ToolContext, recipientLabel: String)(io: IO[A]): IO[A]
  end Face

  private val face = new AtomicReference[Option[Face]](None)

  def install(f: Face): Unit = face.set(Some(f))

  def clear(): Unit = face.set(None)

  // 严格DAG第⑥步第三批A裁定(dwfq-5c7a31ea-1,M2):未注册兜底 = fail-cloud 显式异常
  // (对齐 FriendRosterPort;确认链**禁静默直发**——静默放行比失败更坏)。FriendMessageTool
  // 调用点生产必经 SharedResources 装配,该分支只在「agent 整包未装配」的不可达窗口存在。
  def locally[A](ctx: ToolContext, recipientLabel: String)(io: IO[A]): IO[A] =
    face.get match
      case Some(f) => f.locally(ctx, recipientLabel)(io)
      case None =>
        throw new IllegalStateException(
          "SendConfirmPort 未注册(SharedResources 构造行应注册 SendConfirm 挂靶面)— confirmation chain unavailable"
        )
end SendConfirmPort

// 严格DAG第⑥步第三批A裁定(dwfq-5c7a31ea-1,M2):注册器倒置,签名镜像现实现,行为保持。
/**
 * `agent.AskUserAnswerBridge.ref` 的窄投影(严格DAG第⑥步第三批A):core 消费面 =
 * AskUserQuestionTool.askUserNonBlocking 的一次性回投桥构造(签名零 agent 符号:
 * ActorRef[AgentCommand]/AskItem/ToolContext 皆 actor/shared/core)。注册点 =
 * SharedResources 构造行。
 */
object AskUserAnswerPort:

  /** 注册器持有的桥构造面(ref)。 */
  trait Face:

    def ref(
      target: ActorRef[AgentCommand],
      items: List[AskItem],
      requestId: String,
      ctx: ToolContext
    ): ActorRef[List[String]]
  end Face

  private val face = new AtomicReference[Option[Face]](None)

  def install(f: Face): Unit = face.set(Some(f))

  def clear(): Unit = face.set(None)

  // 严格DAG第⑥步第三批A裁定(dwfq-5c7a31ea-1,M2):未注册兜底 = fail-cloud 显式异常
  // (对齐 FriendRosterPort;桥不可伪造,禁静默空引用)。调用点必经 ctx.agentActorRef=Some
  // 的 agent 会话(SharedResources 已构造 ⇒ 已注册),该分支只在不可达窗口存在。
  def ref(
    target: ActorRef[AgentCommand],
    items: List[AskItem],
    requestId: String,
    ctx: ToolContext
  ): ActorRef[List[String]] =
    face.get match
      case Some(f) => f.ref(target, items, requestId, ctx)
      case None =>
        throw new IllegalStateException(
          "AskUserAnswerPort 未注册(SharedResources 构造行应注册 AskUserAnswerBridge 面)— answer bridge unavailable"
        )
end AskUserAnswerPort
