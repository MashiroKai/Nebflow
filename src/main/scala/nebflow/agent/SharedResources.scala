package nebflow.agent

import cats.effect.std.{Dispatcher, Semaphore}
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
import nebflow.core.{FileChangeTracker, PathUtil}
import nebflow.dropbox.DropboxService
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.*
import nebflow.neblink.NeblinkService
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
  askSemaphore: Semaphore[IO],
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
  dropboxService: Option[DropboxService] = None,
  scheduledTaskService: Option[ScheduledTaskService] = None,
  daemonService: Option[DaemonService] = None,
  knowledgeStore: KnowledgeStore = new KnowledgeStore(PathUtil.dataRoot / "workspace-items"),
  voiceMutedRef: Ref[IO, Boolean],
  lastWsActivity: Ref[IO, Long] = Ref.unsafe[IO, Long](System.currentTimeMillis()),
  runtimeModels: Ref[IO, Map[String, String]] = Ref.unsafe[IO, Map[String, String]](Map.empty)
)
