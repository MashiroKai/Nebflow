package nebflow.agent

import nebflow.core.PathUtil

import cats.effect.std.{Dispatcher, Semaphore}
import cats.effect.{IO, Ref}
import nebflow.bridge.BridgeManager
import nebflow.core.FileChangeTracker
import nebflow.core.compact.HistoryArchiver
import nebflow.core.hooks.{HookEngine, HooksConfig}
import nebflow.core.scheduler.{ScheduledTaskCommand, ScheduledTaskStore}
import nebflow.core.task.TaskStore
import nebflow.core.telemetry.TelemetryReporter
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderRegistry, ThinkingConfig}
import nebflow.mesh.MeshService
import nebflow.shared.*
import org.apache.pekko.actor.typed.ActorRef

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
  hookEngine: HookEngine = HookEngine.noop,
  bridgeManager: Option[BridgeManager] = None,
  scheduledTaskStore: ScheduledTaskStore = new ScheduledTaskStore(PathUtil.dataRoot / "scheduled-tasks"),
  telemetry: Option[TelemetryReporter] = None,
  meshService: Option[MeshService] = None,
  dreamSchedulerRef: Option[ActorRef[DreamCommand]] = None,
  scheduledTaskActorRef: Option[ActorRef[ScheduledTaskCommand]] = None
)
