package nebflow.agent

import cats.effect.IO
import cats.effect.std.{Dispatcher, Semaphore}
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.TaskStore
import nebflow.core.{FileChangeTracker, PermissionState, ReminderState}
import nebflow.gateway.{RateLimiter, SessionStore}
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
  thinkingModeRef: cats.effect.Ref[IO, Option[io.circe.Json]],
  permState: PermissionState,
  rateLimiter: RateLimiter,
  fileChangeTracker: FileChangeTracker,
  reminderStateRef: cats.effect.Ref[IO, ReminderState],
  contextWindow: Int,
  skillDiscovery: Option[nebflow.skill.SkillDiscovery],
  agentLibrary: AgentLibrary,
  askSemaphore: Semaphore[IO],
  taskStore: TaskStore,
  historyArchiver: HistoryArchiver
)
