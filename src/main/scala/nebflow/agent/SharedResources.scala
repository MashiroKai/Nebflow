package nebflow.agent

import cats.effect.IO
import cats.effect.std.{Dispatcher, Semaphore}
import io.circe.Json
import nebflow.core.{FileChangeTracker, PermissionState, ReminderState}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.shared.*

/** Shared resources available to all actors in the hierarchy.
  * Created once in GatewayMain and passed down through actor constructors.
  */
case class SharedResources(
  llm: LlmHandle[IO],
  dispatcher: Dispatcher[IO],
  sessionStore: SessionStore,
  projectRoot: os.Path,
  // Per-connection callback — set by SessionActor on creation
  wsSend: Json => IO[Unit],
  // Global state refs (shared across all sessions)
  thinkingModeRef: cats.effect.Ref[IO, Option[io.circe.Json]],
  permState: PermissionState,
  rateLimiter: RateLimiter,
  fileChangeTracker: FileChangeTracker,
  reminderStateRef: cats.effect.Ref[IO, ReminderState],
  contextWindow: Int,
  skillDiscovery: Option[nebflow.skill.SkillDiscovery],
  agentLibrary: AgentLibrary,
  askSemaphore: Semaphore[IO]
)
