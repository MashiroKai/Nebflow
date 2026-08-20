package nebflow.agent

import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref}
import nebflow.actor.{ActorRef, ActorSystem}
import nebflow.bridge.BridgeManager
import nebflow.core.compact.HistoryArchiver
import nebflow.core.daemon.DaemonService
import nebflow.core.hooks.{HookEngine, HooksConfig}
import nebflow.core.scheduler.{ScheduledTaskService, ScheduledTaskStore}
import nebflow.core.task.TaskStore
import nebflow.core.telemetry.TelemetryReporter
import nebflow.core.tools.FileLockManager
import nebflow.core.workspace.KnowledgeStore
import nebflow.core.{FileChangeTracker, PathUtil, UsageRecordStore}
import nebflow.dropbox.DropboxService
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.*
import nebflow.neblink.{NeblinkService, FriendService}
import nebflow.shared.*

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
  telemetry: Option[TelemetryReporter] = None,
  neblinkService: Option[NeblinkService] = None,
  /** A2A 好友与消息服务（spec §11 客户端）。由 GatewayMain 在 NeblinkClient
    * 初始化后创建，注入 REST 路由 + relay tunnel + WS 事件回调。 */
  friendService: Option[FriendService] = None,
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
   * P2 全局权限策略: one PermissionPolicy per Nebula root session, keyed by
   * rootSessionId. Every agent in a root session's tree reads this bucket at
   * decision time (dynamic inheritance — never a per-agent snapshot).
   * Seeded by ensureRootAgent from session meta; written by SetSafetyMode.
   */
  permissionPolicies: Ref[IO, Map[String, PermissionPolicy]] = Ref.unsafe[IO, Map[String, PermissionPolicy]](Map.empty),
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
  /** 工具结果 TTL 清理（#341）：request-only 清理配置。GatewayMain 启动时从
    * nebflow.json toolResultTtl 节 fail-safe 加载覆写；默认关（disabled）。
    * 带默认值 → 既有测试的 SharedResources 构造零改动。 */
  toolResultTtl: nebflow.core.compact.ToolResultTtlConfig = nebflow.core.compact.ToolResultTtlConfig()
)
