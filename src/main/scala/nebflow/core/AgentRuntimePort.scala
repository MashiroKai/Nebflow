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

  def wakeDelegateByMail(
    wsSend: Option[Json => IO[Unit]],
    delegateId: String,
    instruction: String
  ): IO[Either[String, String]] = IO.pure(Left("Delegate wake unavailable"))

  def externalThinkingConfig: IO[ThinkingConfig] = IO.pure(ThinkingConfig())

  def executionCommitPort: ExecutionCommitPort = ExecutionCommitPort.unsupported
  def delegateRecoveryMode: Option[String] = None
  def isProtectedExecution(sessionId: String): Boolean = false
  def executionProtection(sessionId: String): IO[ExecutionProtection] = executionCommitPort.protection(sessionId)

  def cancelProtectedExecution(sessionId: String, reason: String): IO[Either[String, String]] =
    IO.pure(Left("Protected runner unavailable; cancellation not committed"))

  /** Bind an exact observed commit, install a new attempt and continue the real Agent. */
  def resumeProtectedExecution(request: ProtectedRecoveryRequest): IO[Either[String, ProtectedRecoveryResult]] =
    IO.pure(Left("protected-recovery-unsupported"))

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

  /**
   * 镜像 `SharedResources.msgTokens`（消息身份牌注册表，msgtokens 批 2026-10-31）：
   * 铸牌 / 投递前消解 / 牌 reminder 反查 / 启动对账的会话级 JSONL 存储。
   * 默认 = 全局单例（匿名窄视图实现零改动；生产装配 = SharedResources 字段，同实例）。
   */
  def msgTokens: nebflow.core.msgtokens.MsgTokenRegistry = nebflow.core.msgtokens.MsgTokenRegistry.global

  /**
   * 镜像 `SharedResources.doneRecordStore`（认知二期产品批：打卡 done 历史，周报数据源）。
   * 默认实例（SharedResources 覆盖为同一 dataRoot 路径；匿名窄视图实现零改动）。
   */
  def doneRecordStore: nebflow.core.scheduler.DoneRecordStore =
    nebflow.core.scheduler.DoneRecordStore(nebflow.shared.PathUtil.dataRoot)

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
   * `resources` 形参(agent 侧实现以 `resources = this` 代入——core 内联构造点
   * MailTool 以 resources 为 receiver 调用,receiver == 原 resources 实参,零行为差。
   * 历史构造点 EphemeralAgentRunner（R4 卫生批 2026-10-29 删）与
   * 团队树的 resumeInterruptedAgent（同批随崩溃恢复假链路删除）已不在)。
   * 默认值逐字镜像。
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
    compactThresholdRatio: Option[Double] = None,
    sessionDevice: Option[String] = None,
    sessionDeviceWorkspace: Option[String] = None
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

  def beginRun(
    task: SubAgentTask,
    runId: String,
    rootSessionId: String,
    executor: String
  ): IO[Either[String, SubAgentTask]] =
    IO.pure(Left("run-lifecycle-unsupported"))

  def findAllTasks(): IO[List[SubAgentTask]] = IO.pure(Nil)

  def updateRun(
    parentSessionId: String,
    taskId: String,
    runId: String,
    executionStatus: Option[String] = None,
    activityAt: Option[Long] = None,
    activityKind: Option[String] = None,
    activityLabel: Option[String] = None,
    diagnostic: Option[String] = None
  ): IO[Either[String, SubAgentTask]] = IO.pure(Left("run-lifecycle-unsupported"))

  def completeRun(
    parentSessionId: String,
    taskId: String,
    runId: String,
    executionStatus: String,
    reason: Option[String] = None,
    resultRef: Option[String] = None,
    incomplete: Boolean = false,
    diagnostic: Option[String] = None,
    notificationId: Option[String] = None,
    notifyParent: Boolean = true
  ): IO[Either[String, SubAgentTask]] = IO.pure(Left("run-lifecycle-unsupported"))

  def acknowledgeNotification(
    parentSessionId: String,
    taskId: String,
    runId: String,
    notificationId: String
  ): IO[Either[String, SubAgentTask]] = IO.pure(Left("run-lifecycle-unsupported"))

  /**
   * 陈旧待投递旗清除（押闸释放专用）：仅当行已终态（failed / cancelled）且 `notificationPending=true`
   * 时清旗；非终态或非该 run 一律拒绝（Left），不碰行。依据：终态行的挂起通知等不到投递
   * （看门狗强终已按零通知令没收），清旗是收拾残留，不是伪造投递。
   */
  def clearStalePendingNotification(
    parentSessionId: String,
    taskId: String,
    runId: String
  ): IO[Either[String, SubAgentTask]] = IO.pure(Left("run-lifecycle-unsupported"))

  def recordTask(task: SubAgentTask): IO[Unit]

  def updateStatus(
    parentSessionId: String,
    taskId: String,
    status: String,
    retryCount: Option[Int] = None,
    lastError: Option[String] = None,
    completedAt: Option[Long] = None
  ): IO[Unit]

  /** Explicit continuation admits a new execution without replacing an undelivered result. */
  def resumeObserved(task: SubAgentTask): IO[Unit] =
    val closePrevious =
      if task.runId.nonEmpty &&
        !task.executionStatus.exists(Set("finished", "failed", "cancelled", "interrupted").contains)
      then
        completeRun(
          task.parentSessionId,
          task.taskId,
          task.runId.get,
          "interrupted",
          reason = Some("Superseded by an explicit continuation")
        ).flatMap(_.fold(reason => IO.raiseError(new IllegalStateException(reason)), _ => IO.unit))
      else IO.unit
    val preflight = findByTaskId(task.taskId).flatMap {
      case Some(current) if current.runId != task.runId =>
        IO.raiseError(new IllegalStateException("stale-run"))
      case Some(current) if current.notificationPending.contains(true) =>
        IO.raiseError(new IllegalStateException("notification-pending"))
      case _ => IO.unit
    }
    preflight *> closePrevious *> IO
      .blocking {
        nebflow.core.delegate.DelegateRegistry.find(task.taskId) match
          case Some(record) if record.roundId.exists(id => !task.runId.contains(id)) => record.roundId.get
          case Some(record) =>
            nebflow.core.delegate.DelegateRoundLedger
              .openNewRound(record)
              .fold(reason => throw new IllegalStateException(reason), _.roundId)
          case None => java.util.UUID.randomUUID().toString
      }
      .flatMap { runId =>
        beginRun(task, runId, task.rootSessionId.getOrElse(task.parentSessionId), task.executor.getOrElse("nebflow"))
          .flatMap(_.fold(reason => IO.raiseError(new IllegalStateException(reason)), _ => IO.unit))
      }

  end resumeObserved

  /** Administrative transitions use an observed row and never bypass execution fencing. */
  def transitionObserved(
    task: SubAgentTask,
    status: String,
    lastError: Option[String] = None
  ): IO[Unit] = task.runId match
    case None =>
      updateStatus(
        task.parentSessionId,
        task.taskId,
        status,
        lastError = lastError,
        completedAt = Option.when(Set("completed", "failed", "cancelled").contains(status))(System.currentTimeMillis())
      )
    case Some(run) =>
      val result = if Set("completed", "failed", "cancelled", "pending-recovery").contains(status) then
        val terminal = status match
          case "completed" => "finished"
          case "pending-recovery" => "interrupted"
          case other => other
        completeRun(
          task.parentSessionId,
          task.taskId,
          run,
          terminal,
          reason = lastError,
          incomplete = terminal == "interrupted"
        )
      else updateRun(task.parentSessionId, task.taskId, run, Some(status), diagnostic = lastError)
      result.flatMap(_.fold(reason => IO.raiseError(new IllegalStateException(reason)), _ => IO.unit))

  def findRunningTasks: IO[List[SubAgentTask]]
  def findByTaskId(taskId: String): IO[Option[SubAgentTask]]

  /**
   * boot 复活记账（监督链自动恢复批 2026-10-22；窄口补位 = 错误冻结批 2026-11-01）：
   * 行翻 running + 复活预算 +1 + 复活时刻 + 清旧错。`DelegateRevival` 的
   * `Deps.resources` 收窄为 [[AgentRuntimePort]] 后（Mail/面板唤醒腿需要 core 侧可
   * 构造），本成员必须上端口——语义与 `agent.SubAgentTaskStore.markRevived` 逐字一致
   * （后者原地 satisfies 本抽象）。默认实现 = 退化为 updateStatus（端口外实现零破坏，
   * 但预算记账缺席；生产实现恒为 store 本体）。
   */
  def markRevived(
    parentSessionId: String,
    taskId: String,
    revivalCount: Int,
    revivedAt: Long
  ): IO[Unit] =
    updateStatus(parentSessionId, taskId, "running", retryCount = Some(revivalCount))
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
 *
 * 🔴 2026-10-09 作者裁定:wall-clock 预算已整体拆除——`resume` 现为结构性 no-op,
 * 本面仅为调用点兼容保留(拆除后 delegate 治理 = TaskStuckWatcher 无进展判据,见
 * DelegateBudget.scala 墓碑壳文档)。
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

// Canvas ask mode（2026-10-28 实施批）：主会话转交/回问注册器（M2 先例同款）。
/**
 * ask 会话 ↔ 主会话的单向信使（机制稿 §7）注册器：core 消费面 =
 * `SendMessageToMainChatTool`（ask 工具腿）与 `WsAskHandlers`（dock 转交腿，
 * 经注册器同一面——两条腿同限频、同呈现契约，禁出现第二条投递原语）。
 * Face 实现 = agent 侧 `ensureAgent(主会话) ! ExternalEvent(source="ask")`
 * （injected 气泡 + 来源行「来自文件问答 · <文件名>」的引擎呈现链），
 * 注册点 = WebSocketRoutes 构造行（ensureAgent 私有，唯有该处可装）。
 * 未注册兜底 = fail-closed Left（对齐 SendConfirmPort「禁静默直发」）。
 */
object AskHandoffPort:

  /** 一次转交/回问投递请求（载荷形状 = 机制稿 §7 消息契约的引擎侧投影）。 */
  final case class Request(
    askSessionId: String,
    file: String,
    fileName: String,
    question: String,
    /** 来源腿："ask-tool"（分身工具）/ "ask-dock"（输入条转交钮）/ "main-reply"（主会话回问）。 */
    leg: String,
    /** 检索引用（§7 context_refs，形状引擎不解释、随注入帧透传）。 */
    contextRefs: List[io.circe.Json] = Nil,
    /**
     * ask-scope 批（2026-11-18）· 结构化转交请求四件（作者令「复杂任务转交主会话：
     * 做什么、涉及哪些路径、为什么需要主会话、期望产出」）。机器可读面 = 本四字段
     * （注入帧 metadata.handoff + askHandoffAck.handoff 同一单源）；人读面 =
     * `question`（缺省由四件渲染，见 handoffSummary）。
     */
    task: String = "",
    paths: List[String] = Nil,
    reason: String = "",
    expected: String = "",
    /**
     * gitviz 分支管理问答批（2026-11-19 第二轮）· 结构化交办请求的 **git 件**。
     *
     * 作者令「做事就转发给主会话，比如不仅可以上面多选进行合并，也可以跟这个 agent
     * 说，它转交」+「两个入口收敛到同一个出口」。因此面板的「多选 + 合并」钮与问答
     * agent 的对话交办**产出同一份 request**，区别只在 `source`（`panel` / `ask`）。
     *
     * 分支清单 / 目标线 / 意图 / 理由 是固定四件（人读 `task`/`paths`/`reason`/
     * `expected` 仍照旧携带压扁版，机器读本字段）。`None` = 非 gitviz 交办（既有
     * 文件 ask 行为逐字不变）。
     */
    git: Option[GitRequest] = None
  )

  /**
   * gitviz 交办请求的机器可读件（分支清单 · 目标线 · 意图 · 理由 + 逐项备注 + 入口来源）。
   *
   * 🔴 它是**请求**，不是执行凭据：本结构不授任何写能力，投递面仍是主会话（Nebula），
   * 由主会话按落支规则派发执行（作者「写操作唯一执行者 = 主会话」）。
   */
  final case class GitRequest(
    /** 项目名（交办对象的工作区上下文，供主会话定位仓库）。 */
    project: String,
    /** 分支清单（精确分支名）。 */
    branches: List[String],
    /** 目标线（合并进哪一支）。 */
    target: String,
    /** 意图：merge / branch / delete / push / rebase / tag。 */
    intent: String,
    /** 逐项备注（面板多选写下的备注，随请求带走）。 */
    notes: List[String] = Nil,
    /** 入口来源："panel"（多选+合并钮）/ "ask"（对话里说的）。 */
    source: String = "panel"
  )

  /**
   * 结构化转交请求的人读单行（`question` 缺省值单点）：四件齐全时按
   * 「任务 + 涉及路径数 + 期望产出」压缩，缺件按现有信息降级，全空 = ""。
   */
  def handoffSummary(task: String, paths: List[String], reason: String, expected: String): String =
    val head = Option(task).map(_.trim).filter(_.nonEmpty).getOrElse("")
    val pathNote =
      if paths.isEmpty then ""
      else s"（涉及 ${paths.length} 个路径：${paths.take(3).mkString(", ")}${if paths.length > 3 then ", …" else ""}）"
    val tail = Option(expected).map(_.trim).filter(_.nonEmpty).map(e => s" 期望产出：$e").getOrElse("")
    (head + pathNote + tail).trim

  /** Face：投递进主会话（injected 呈现 + 限频已在工具侧收敛）。 */
  trait Face:
    def deliver(req: Request): IO[Either[nebflow.core.tools.ToolError, String]]
  end Face

  private val face = new AtomicReference[Option[Face]](None)

  def install(f: Face): Unit = face.set(Some(f))

  def clear(): Unit = face.set(None)

  /** 未注册 = fail-closed（转交不可用是显式错误，不是静默丢弃）。 */
  def deliver(req: Request): IO[Either[nebflow.core.tools.ToolError, String]] =
    face.get match
      case Some(f) => f.deliver(req)
      case None =>
        IO.pure(Left(nebflow.core.tools.ToolError("主会话转交通道未就绪（AskHandoffPort 未注册）——请稍后再试")))
end AskHandoffPort

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
