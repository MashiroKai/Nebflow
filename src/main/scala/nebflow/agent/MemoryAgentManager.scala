package nebflow.agent

import cats.effect.std.Dispatcher
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import nebflow.agent.MemoryAgentProtocol.*
import nebflow.core.NebflowLogger
import nebflow.gateway.SessionStore
import nebflow.shared.LlmHandle
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}

import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration.*

/**
 * Routes memory writes to ephemeral Memory Agent actors.
 * Manages the Dream timer for User scope.
 *
 * All scopes use per-mail temporary actors — no long-lived actors.
 */
class MemoryAgentManager(
  actorSystem: ActorSystem[?],
  llm: LlmHandle[IO],
  dispatcher: Dispatcher[IO],
  sessionStore: SessionStore
):

  private val logger = NebflowLogger.forName("nebflow.memory-manager")
  private val counter = new AtomicLong()
  @volatile private var lastDreamTime: Long = 0L

  // Spawn a Dream scheduler actor on construction
  locally {
    actorSystem.systemActorOf(dreamScheduler(), "memory-dream-scheduler")
  }

  // ============================================================
  // Public API
  // ============================================================

  /** Send a memory write mail — spawns a temporary agent to process it. */
  def sendMail(scope: MemoryAgentScope, mail: MemoryWriteMail): IO[Unit] =
    IO.blocking {
      val id = counter.incrementAndGet()
      val actorName = s"memory-${scope.key}-$id"
      val ref = actorSystem.systemActorOf(
        MemoryAgentActor(scope, llm, dispatcher),
        actorName
      )
      ref ! mail
    }.void

  /** Resolve the Folder scope for any folder ID by walking up to the top-level folder. */
  def resolveFolderScope(folderId: String): IO[Option[MemoryAgentScope.Folder]] =
    IO.blocking {
      walkToTopLevel(folderId)
    }

  /** No-op — all actors are ephemeral. */
  def shutdownAll(): IO[Unit] =
    IO(logger.infoSync("Memory Agent Manager: shutdown (ephemeral actors self-terminate)"))

  // ============================================================
  // Dream scheduler — ticks every 30 min, triggers User Dream
  // ============================================================

  private sealed trait DreamTick
  private case object Tick extends DreamTick

  private def dreamScheduler(): Behavior[DreamTick] =
    Behaviors.withTimers { timers =>
      timers.startTimerAtFixedRate(Tick, 30.minutes)
      Behaviors.receiveMessage {
        case Tick =>
          val now = System.currentTimeMillis()
          if now - lastDreamTime >= 24.hours.toMillis then
            lastDreamTime = now
            try
              val digest = dispatcher.unsafeRunSync(MemoryAgentActor.collectRecentUserInputs)
              if digest.nonEmpty then
                val id = counter.incrementAndGet()
                actorSystem.systemActorOf(
                  MemoryAgentActor.dream(llm, dispatcher, digest),
                  s"memory-user-dream-$id"
                )
                logger.infoSync("Memory Agent Manager: spawned User Dream agent")
              else
                logger.infoSync("Memory Agent Manager: Dream skipped — no recent user inputs")
            catch
              case e: Exception =>
                logger.warnSync(s"Memory Agent Manager: Dream failed: ${e.getMessage}")
          Behaviors.same
      }
    }

  // ============================================================
  // Folder tree traversal
  // ============================================================

  private def walkToTopLevel(folderId: String): Option[MemoryAgentScope.Folder] =
    @annotation.tailrec
    def walk(id: String): Option[String] =
      sessionStore.getFolderParentId(id) match
        case None           => None
        case Some(None)     => Some(id)
        case Some(Some(pid)) => walk(pid)
    walk(folderId).map(MemoryAgentScope.Folder.apply)

end MemoryAgentManager
