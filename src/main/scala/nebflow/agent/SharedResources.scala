package nebflow.agent

import cats.effect.std.Dispatcher
import cats.effect.{Deferred, IO, Ref}
import io.circe.{Json, JsonObject}
import nebflow.actor.*
import nebflow.bridge.BridgeManager
import nebflow.core.compact.HistoryArchiver
import nebflow.core.daemon.DaemonService
import nebflow.core.hooks.{HookEngine, HooksConfig}
import nebflow.core.node.NodeRunner
import nebflow.core.scheduler.{ScheduledTaskService, ScheduledTaskStore}
import nebflow.core.task.TaskStore
import nebflow.core.tools.*
import nebflow.core.workspace.KnowledgeStore
import nebflow.core.{RateLimiter, SessionStore, *}
import nebflow.dropbox.DropboxService
import nebflow.llm.*
import nebflow.neblink.{AttachUploadRegistry, FriendService, NeblinkService}
import nebflow.shared.{FileHistory, ReadTracker, *}

// 严格DAG第⑥步第三批A裁定(dwfq-5c7a31ea-1,R-A/R-C):本类原地混入 core 窄视图 AgentRuntimePort
// (AgentRegistryPort 经其继承;签名镜像,行为保持)。extends 子句形状与改前一致。
/**
 * Shared resources available to all actors in the hierarchy.
 * Created once in GatewayMain and passed down through actor constructors.
 *
 * All fields are global singletons — no per-connection state.
 */
case class SharedResources(
  llm: LlmHandle[IO],
  dispatcher: Dispatcher[IO],
  sessionStore: SessionStore,
  projectRoot: os.Path,
  thinkingConfigRef: Ref[IO, ThinkingConfig],
  rateLimiter: RateLimiter,
  fileChangeTracker: FileChangeTracker,
  contextWindow: Int,
  agentLibrary: AgentLibrary,
  taskStore: TaskStore,
  historyArchiver: HistoryArchiver,
  fileLockManager: FileLockManager,
  sessionModelOverrides: cats.effect.Ref[IO, Map[String, ModelCandidate]],
  providerRegistry: ProviderRegistry,
  healthMonitor: ProviderHealthMonitor,
  actorSystem: ActorSystem,
  hookEngine: HookEngine = HookEngine.noop,
  bridgeManager: Option[BridgeManager] = None,
  scheduledTaskStore: ScheduledTaskStore = new ScheduledTaskStore(PathUtil.dataRoot / "scheduled-tasks"),
  neblinkService: Option[NeblinkService] = None,
  /**
   * A2A 好友与消息服务（spec §11 客户端）。由 GatewayMain 在 NeblinkClient
   * 初始化后创建，注入 REST 路由 + relay tunnel + WS 事件回调。
   */
  friendService: Option[FriendService] = None,
  /**
   * attachcl 批（2026-09-16）：在飞附件上传的**取消位**登记表（网页腿「整件一次
   * 请求 → 网关分块」的上传可取消；见 [[nebflow.neblink.AttachUploadRegistry]]）。
   * 缺省 = 本实例独立空表（`Ref.unsafe`，同本类既有先例）⇒ 既有构造点零改动。
   */
  attachUploads: AttachUploadRegistry = AttachUploadRegistry.unsafe,
  dropboxService: Option[DropboxService] = None,
  scheduledTaskService: Option[ScheduledTaskService] = None,
  daemonService: Option[DaemonService] = None,
  knowledgeStore: KnowledgeStore = new KnowledgeStore(PathUtil.dataRoot / "workspace-items"),
  subAgentTaskStore: SubAgentTaskStore = new SubAgentTaskStore(PathUtil.dataRoot / "subagent-tasks"),
  usageRecordStore: UsageRecordStore = new UsageRecordStore(PathUtil.dataRoot / "usage-records"),
  voiceMutedRef: Ref[IO, Boolean],
  lastWsActivity: Ref[IO, Long] = Ref.unsafe[IO, Long](System.currentTimeMillis()),
  runtimeModels: Ref[IO, Map[String, String]] = Ref.unsafe[IO, Map[String, String]](Map.empty),
  /**
   * P1 统一注册表: the single registry for ALL running agents
   * (Root/Team/Flow/Delegate/Ephemeral). Replaces `subAgentRegistry`; during P1
   * `rootAgents` and `TeamSessionRegistry.actorMap` remain as dual-written
   * caches (P3 removes them). Interaction answers route through this map only —
   * a lookup miss never spawns a ghost agent.
   */
  agentRegistry: Ref[IO, Map[String, AgentRecord]] = Ref.unsafe[IO, Map[String, AgentRecord]](Map.empty),
  /**
   * P2 InteractionHub: spawned once by GatewayMain at startup. Agents send
   * InteractionRequest here for permission/AskUser; gateway forwards frontend
   * answers as InteractionAnswered. Option so SharedResources can be built
   * before the hub actor exists (tests, early boot).
   */
  interactionHubRef: Ref[IO, Option[ActorRef[InteractionHubCommand]]] =
    Ref.unsafe[IO, Option[ActorRef[InteractionHubCommand]]](None),
  /**
   * 冻结调度（freeze-schedule，#337 黑名单语义）：全局冻结时间表（配置段=冻结
   * 时间/非工作时间，D5 一期全局粒度）。GatewayMain 启动时从 nebflow.json
   * workSchedule 节（JSON 键名保留，语义=冻结时段）fail-safe 加载覆写；
   * setWorkSchedule WS 命令热更。带默认值 → 既有测试的 SharedResources 构造零改动。
   */
  freezeScheduleRef: Ref[IO, nebflow.core.schedule.FreezeScheduleConfig] =
    Ref.unsafe[IO, nebflow.core.schedule.FreezeScheduleConfig](nebflow.core.schedule.FreezeScheduleConfig()),
  /**
   * 用户消息全局跳过当前冻结窗口（2026-08-25 裁定）：skipUntil epoch millis——
   * 该时刻之前 evalWithSkip 视为不冻结（本次冻结整体作废），到期自动过期
   * （下一冻结段照常冻结）。运行时态，不持久化（跳过非永久）；handleUserText
   * 在用户消息到达时 set + FreezeScheduler.scan 即时唤醒所有 Frozen agent。
   * 带默认值 → 既有测试的 SharedResources 构造零改动。
   */
  freezeSkipUntilRef: Ref[IO, Option[Long]] = Ref.unsafe[IO, Option[Long]](None),
  /**
   * 工具结果 TTL 清理（#341）：request-only 清理配置。GatewayMain 启动时从
   * nebflow.json toolResultTtl 节 fail-safe 加载覆写；默认关（disabled）。
   * #341 WS 尾巴：Ref 化热更（镜像 freezeScheduleRef）——setToolResultTtl
   * 写盘成功后 set，下个 LLM 请求即生效，无需重启。
   * 带默认值 → 既有测试的 SharedResources 构造零改动。
   */
  toolResultTtlRef: Ref[IO, nebflow.core.compact.ToolResultTtlConfig] =
    Ref.unsafe[IO, nebflow.core.compact.ToolResultTtlConfig](nebflow.core.compact.ToolResultTtlConfig()),
  /**
   * Bash 卡死防护阈值（#391）：GatewayMain 从 nebflow.json 顶层键 fail-safe
   * 读取（bashAutoBackgroundMs/bashBackgroundHardTimeoutMs/bashStuckWindowSec），
   * 经 AgentCore 注入 ToolContext → BashTool。带默认值 → 既有测试构造零改动。
   */
  bashResilience: nebflow.shared.BashResilienceConfig = nebflow.shared.BashResilienceConfig(),
  /**
   * 执行环境 provider 配置（design §4.2；GatewayMain 启动经 SandboxConfig.load +
   * SandboxRuntime.init 装配）：absent → 缺省 provider=host / enabled=true。经
   * AgentCore 派生 ToolContext.sandbox（路径语义 + 会话根推导）。默认实例 =
   * provider=host（宿主直跑），存量测试构造零改动。
   * [沙箱拆围栏批 S1, 2026-09-10] 本配置不再参与 AGENTS.md 注入判据（该判据改用
   * SessionContext.projectSession，见 ContextRefresher.agentsMdEnabledFor）。
   * [沙箱拆围栏批 S3, 2026-09-10] JVM 写闸已退役（S2）、宿主 Bash 包裹已退场
   * （S3）：本配置残留承重 = provider 装配 + 会话根/路径语义 + §4.5 回退点。
   */
  sandboxConfig: nebflow.core.sandbox.SandboxConfig = nebflow.core.sandbox.SandboxConfig(),
  /**
   * 阶段 2b Plugins（§B.5）：plugin 级 MCP 生命周期管理器（引用计数 + 信任运行时
   * 联动）。独立于全局 mcpManager（不复用 enable/disable 面，§B.5）；默认实例
   * 同步构造（Ref.unsafe 先例）——存量测试构造零改动，节点无分配时不触碰。
   */
  pluginMcp: nebflow.core.plugin.PluginMcpManager = nebflow.core.plugin.PluginMcpManager.unsafe(),
  /**
   * 热重启优雅关停触发闸（hot-restart 批设计 §3.3）：HotRestart 编排器在三检查点
   * 全过后 complete 它，GatewayMain 的 waitForQuit 经 IO.race 收敛 → use 块完成 →
   * Ember 优雅 stop → .guarantee 链 → JVM 自然退出——替代 System.exit(0) 非优雅
   * 退（RestApiRoutes neblink 更新路径的既有缺陷）。R4 零回归：Ctrl+C / quit /
   * `nebflow stop` 三既有退出路径零改变（race 对它们无感——任一先完成即收敛，
   * Deferred 无人 complete 时 race 行为与裸 waitForQuit 等价）。
   * Deferred.unsafe 同步构造（HealthMonitor.signalRef 先例）→ 既有测试构造零改动。
   */
  gatewayShutdown: Deferred[IO, Unit] = Deferred.unsafe[IO, Unit],
  /**
   * 热重启编排器（hot-restart 批）：GatewayMain 装配后注入；None = 未装配（测试 /
   * 极早期 boot / 总开关关闭）。WS restart 命令经它触发 requestRestart；进度经
   * restartStatus 帧广播（wsHub）。
   */
  hotRestart: Option[nebflow.core.hotrestart.HotRestart] = None,
  /**
   * 统一更新编排器（hotupdate 批 1，设计 §4）：GatewayMain 装配后注入；None = 未装配
   * （测试 / 极早期 boot）。设置页一键更新经它受理（`WebSocketRoutes` 的 `doUpdate`
   * 分支）；其重启相位委托上面的 [[hotRestart]]；进度经统一进度帧广播（wsHub）。
   */
  updateOrchestrator: Option[nebflow.core.hotupdate.UpdateOrchestrator] = None,
  /**
   * **会话级压缩阈值比例覆盖**（ctxthresh 批，2026-09-15 方案 A）：键 = **root
   * sessionId**，值 = 比例 `r`（`15% < r ≤ 90%`，见
   * [[nebflow.agent.CompactThresholdOverride]]）。与 `sessionModelOverrides`
   * （上方 :40）同形同列——同一「会话级覆盖」形态的第二个实例。
   *
   * 与 `sessionModelOverrides` 的**语义差异**（设计 §9-O4，作者卡答采纳）：
   * 本 Ref **不参与启动清零**（`SessionStore.clearAllSessionModels`）——清零的
   * 理由是 `ModelCandidate` 是**旧配置快照**，热重载后可能「Unknown provider」
   * （`llm/registry.scala:186-195`）；比例是**纯标量**，无陈旧风险 ⇒ 跨重启保留
   * （盘上权威 = `SessionMeta` 新增的阈值比例键，见 `shared/SessionMeta.scala`）。
   *
   * 带默认值（Ref.unsafe 先例）⇒ 存量测试构造零改动。🔴 非 root spawn 路径
   * **禁**读本 Ref（口径③；静态判据见
   * `.nebflow/tools/20260915_ctxthresh_leak-check.sh`）。
   */
  sessionCompactThreshold: Ref[IO, Map[String, Double]] = Ref.unsafe[IO, Map[String, Double]](Map.empty)
) extends AgentRuntimePort,
      SubAgentTaskPort:

  /**
   * **唯一解析入口**（permshield S1 / 2026-09-13 作者重裁「候选 B」）：有效档位
   * **恒等于**全局持久档位（`nebflow.json` 的 `safety.defaultMode`，热读、零缓存）。
   *
   * 2026-09-12 的「会话内临时覆盖」（仅内存 `permissionPolicies[rootSid]`）层已整体
   * 停用并删除 ⇒ 本方法**不再接受会话参数**：没有任何按会话分叉的档位来源，因此
   * "某个会话有自己的档位"在类型上就不可能存在（漏用/漏注入从运行时缺陷降为编译期
   * 不可能）。所有消费点（工具判定 / 卡帧档位 / 会话列表出口 / 子代理与流程继承）
   * 一律经此读，不得各自读配置文件。
   */
  def effectiveSafetyMode: IO[nebflow.core.SafetyMode] =
    nebflow.core.GlobalSafety.defaultMode

  /**
   * 会话列表出口的**权威 overlay**：把 `List[SessionMeta]` 序列化成
   * `safetyMode` 恒存在的 JSON，取值 = **有效档位** = 全局持久档位
   * （与 `effectiveSafetyMode` 同源）。
   *
   * ⚠ 只用于**线上出口**（WS `agentSessionList` / fork `sessionList` /
   * `SessionService.sendSessionList` / REST `GET /sessions`）。**不得**用于
   * `SessionStore.saveIndex` 的落盘序列化——盘上字节零改动。
   */
  def overlaySessionList(sessions: List[nebflow.shared.SessionMeta]): IO[io.circe.Json] =
    effectiveSafetyMode.flatMap { global =>
      val base = nebflow.shared.SessionMeta.withEffectiveSafetyModes(sessions, nebflow.core.SafetyMode.toString(global))
      // taskbadge batch 2026-09-27: task-attribution enrichment (wire-only).
      // Attribution source = AgentRegistry (AgentRecord.taskId: snapshot taken at
      // spawn from the Project-domain registration site, cleared at the same point
      // as the dispatcher slot table -- slot cleared => registry cleared => both
      // keys absent = empty state); title source = the unified ledger (deduped,
      // read once -- at most one ledger-file read per task id across N sessions).
      // 🔴 An attribution-query failure must **never take down the session-list
      // endpoint**: the whole enrichment degrades to the un-enriched base (keys
      // absent = empty state), matching the "the list endpoint never fails because
      // an attribution query failed" contract.
      agentRegistry.get.flatMap { reg =>
        val attribution =
          sessions.flatMap(m => reg.get(m.id).flatMap(_.taskId).map(tid => m.id -> tid)).toMap
        val ids = attribution.values.toList.distinct
        val titlesIo: IO[Map[String, String]] =
          if ids.isEmpty then IO.pure(Map.empty[String, String])
          else IO.blocking(ids.map(id =>
            id -> nebflow.core.project.TaskLedgerStore.titleOfSync(Some(id)).getOrElse("")).toMap)
        titlesIo
          .handleErrorWith(_ => IO.pure(Map.empty[String, String]))
          .map(titles => nebflow.shared.SessionMeta.withTaskAttribution(base, attribution, titles))
      }
    }

  // ── Phase 5 D 步窄能力面(core ← agent 倒置,见 core/capabilities.scala)──────
  // 方法体 = core(hotrestart/HotRestart.quiesceReport)原内联逻辑逐字迁移,零行为差;
  // 签名零 agent 符号(AgentRecord/AgentStatus/SubAgentTask 不外泄)。

  /** hot-restart 五域判定 F3 的读数(原 HotRestart 内联过滤)。 */
  def processingSessionIds: IO[List[String]] =
    agentRegistry.get.map(_.values.filter(_.status == AgentStatus.Processing).map(r => r.sessionId).toList)

  /** hot-restart 五域判定 F2 的读数(原 HotRestart 直读;映射为 core 瘦视图)。 */
  def findRunningTasks: IO[List[RunningSubAgentTask]] =
    subAgentTaskStore.findRunningTasks.map(_.map(t => RunningSubAgentTask(t.taskId, t.parentSessionId, t.source)))

  // ── 严格DAG第⑥步第三批A裁定(dwfq-5c7a31ea-1,R-C):spawn 工厂三镜像。方法体 = core.node
  //    .NodeRunner 的 spawnAgentActor/spawnSupervisedAdapter 原体逐字迁此(resources 以
  //    this 代入——NodeRunner 委托式保证 receiver == p.resources,零行为差);
  //    agentActorBehavior = AgentActor.apply 全参镜像去 resources(this 代入)。──────────

  /** 共享 spawn:readTracker/fileHistory 创建 + AgentActor spawn(NodeRunner 原体)。 */
  def spawnAgentActor(system: ActorSystem, p: NodeRunner.SpawnParams): IO[ActorRef[AgentCommand]] =
    val actorName = if p.actorName.nonEmpty then p.actorName else p.sessionId
    for
      readTracker <- ReadTracker.create
      fileHistory <- FileHistory.create()
      ref <- system.spawn(
        AgentActor(
          agentDef = p.agentDef,
          resources = this,
          wsSend = p.wsSend,
          depth = p.depth,
          parentRef = p.parentRef,
          sessionId = Some(p.sessionId),
          sessionName = Some(p.sessionName),
          initialMessages = p.initialMessages,
          readTracker = if p.withTracking then Some(readTracker) else None,
          fileHistory = if p.withTracking then Some(fileHistory) else None,
          contextWindow = contextWindow,
          projectRoot = p.projectRoot,
          safetyMode = p.safetyMode,
          rootSessionId = p.rootSessionId,
          isSubTaskWorker = p.isSubTaskWorker,
          isFlowNode = p.isFlowNode,
          expectsMail = p.expectsMail,
          userFacingNode = p.userFacingNode,
          flowNodeId = p.flowNodeId,
          isDispatcher = p.isDispatcher,
          flowNodeRole = p.flowNodeRole,
          projectName = p.projectName,
          taskId = p.taskId,
          flowNodeName = p.flowNodeName,
          flowChainId = p.flowChainId,
          sandboxEnabled = p.sandboxEnabled,
          sandboxRoot = p.sandboxRoot,
          sessionCwd = p.sessionCwd,
          projectSession = p.projectSession
        ),
        actorName
      )
    yield ref

    end for

  end spawnAgentActor

  /** 共享 BackoffSupervisor adapter spawn(NodeRunner 原体;delegate/subtask 共用)。 */
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
        resources = this,
        initialPrompt = initialPrompt,
        source = source,
        extraMetadata = extraMetadata,
        wsSend = wsSend
      ),
      s"$subagentId-adapter"
    )
  end spawnSupervisedAdapter

  /** `AgentActor.apply` 的工厂镜像(R-C):全参转发,resources 以 this 代入。 */
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
  ): Behavior[AgentCommand] =
    AgentActor(
      agentDef = agentDef,
      resources = this,
      wsSend = wsSend,
      depth = depth,
      parentRef = parentRef,
      sessionId = sessionId,
      sessionName = sessionName,
      initialMessages = initialMessages,
      readTracker = readTracker,
      fileHistory = fileHistory,
      contextWindow = contextWindow,
      projectRoot = projectRoot,
      rulesMd = rulesMd,
      agentsMd = agentsMd,
      folderId = folderId,
      safetyMode = safetyMode,
      gitBranch = gitBranch,
      expectsMail = expectsMail,
      rootSessionId = rootSessionId,
      isSubTaskWorker = isSubTaskWorker,
      freezeExempt = freezeExempt,
      isFlowNode = isFlowNode,
      userFacingNode = userFacingNode,
      flowNodeId = flowNodeId,
      isDispatcher = isDispatcher,
      flowNodeRole = flowNodeRole,
      projectName = projectName,
      flowNodeName = flowNodeName,
      flowChainId = flowChainId,
      sandboxEnabled = sandboxEnabled,
      sandboxRoot = sandboxRoot,
      sessionCwd = sessionCwd,
      projectSession = projectSession,
      compactThresholdRatio = compactThresholdRatio
    )
  end agentActorBehavior

  // ── 严格DAG第⑥步第三批A裁定(dwfq-5c7a31ea-1,M2):三个静态面注册器的安装行(构造即
  //    注册,幂等;生产 boot 与测试装配均经本构造——GatewayMain / SpecResources;各注册器
  //    未注册兜底语义见 core/AgentRuntimePort.scala)。─────────────────────────────
  DelegateBudgetPort.install(
    new DelegateBudgetPort.Face:
      def resume(sessionId: String): IO[Unit] = DelegateBudget.resume(sessionId)
  )

  SendConfirmPort.install(
    new SendConfirmPort.Face:
      def locally[A](ctx: ToolContext, recipientLabel: String)(io: IO[A]): IO[A] =
        SendConfirm.locally(SendConfirm.targetFor(ctx, recipientLabel))(io)
  )

  AskUserAnswerPort.install(
    new AskUserAnswerPort.Face:
      def ref(
        target: ActorRef[AgentCommand],
        items: List[AskItem],
        requestId: String,
        ctx: ToolContext
      ): ActorRef[List[String]] =
        AskUserAnswerBridge.ref(target, items, requestId, ctx)
  )
end SharedResources
