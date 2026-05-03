package nebflow.agent

import cats.effect.IO
import cats.effect.std.{Dispatcher, Semaphore}
import io.circe.Json
import nebflow.core.task.TaskStore
import nebflow.core.{FileChangeTracker, PermissionState, ReminderState}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.shared.*

/**
 * Shared resources available to all actors in the hierarchy.
 * Created once in GatewayMain and passed down through actor constructors.
 *
 * Resource lifecycle semantics:
 * - Global singletons: llm, dispatcher, sessionStore, projectRoot, rateLimiter,
 *   fileChangeTracker, contextWindow, skillDiscovery, agentLibrary, askSemaphore, taskStore
 * - Global mutable state (shared across all sessions): thinkingModeRef, permState, reminderStateRef
 * - Per-connection (set by SessionActor on creation): wsSend
 */
case class SharedResources(
  llm: LlmHandle[IO], // global singleton
  dispatcher: Dispatcher[IO], // global singleton
  sessionStore: SessionStore, // global singleton — persistence layer only
  projectRoot: os.Path, // global singleton
  // Per-connection callback — set by SessionActor on creation
  wsSend: Json => IO[Unit],
  // Global mutable state (shared across all sessions)
  thinkingModeRef: cats.effect.Ref[IO, Option[io.circe.Json]],
  permState: PermissionState,
  rateLimiter: RateLimiter,
  fileChangeTracker: FileChangeTracker,
  reminderStateRef: cats.effect.Ref[IO, ReminderState],
  contextWindow: Int,
  skillDiscovery: Option[nebflow.skill.SkillDiscovery],
  agentLibrary: AgentLibrary,
  askSemaphore: Semaphore[IO],
  taskStore: TaskStore
)
