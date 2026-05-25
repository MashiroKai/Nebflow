package nebflow.agent

import cats.effect.{IO, Ref}
import io.circe.syntax.*
import io.circe.Json
import nebflow.core.NebflowLogger
import org.apache.pekko.actor.typed.ActorRef

/**
 * Central routing registry for inter-agent communication.
 *
 * Maintains a mapping from session ID to (AgentMailbox, ActorRef).
 * Agents register when they start (ensureRootAgent) and unregister on stop.
 *
 * All operations are O(1) via Ref[IO, Map].
 */
class PostOffice private (
  registry: Ref[IO, Map[String, (AgentMailbox, ActorRef[AgentCommand])]],
  private val nameIndex: Ref[IO, Map[String, String]] // agentName -> sessionId (latest)
):

  private val logger = NebflowLogger.forName("nebflow.postoffice")

  /** Register a session's agent. Idempotent — re-registration updates the ref. */
  def register(mailbox: AgentMailbox, ref: ActorRef[AgentCommand]): IO[Unit] =
    registry.update(_ + (mailbox.sessionId -> (mailbox, ref))) *>
      nameIndex.update(_ + (mailbox.agentName -> mailbox.sessionId)) *>
      IO(logger.info(s"Registered ${mailbox.display}"))

  /** Unregister a session. No-op if not registered. */
  def unregister(sessionId: String): IO[Unit] =
    registry.modify { m =>
      m.get(sessionId) match
        case Some((mailbox, _)) =>
          (m - sessionId, Some(mailbox))
        case None =>
          (m, None)
    }.flatMap {
      case Some(mailbox) =>
        nameIndex.update(_ - mailbox.agentName) *>
          IO(logger.info(s"Unregistered ${mailbox.display}"))
      case None => IO.unit
    }

  /** Look up by session ID. Returns the mailbox + actor ref. */
  def findBySessionId(sessionId: String): IO[Option[(AgentMailbox, ActorRef[AgentCommand])]] =
    registry.get.map(_.get(sessionId))

  /** Look up by canonical address string. Returns the mailbox + actor ref. */
  def findByAddress(address: String): IO[Option[(AgentMailbox, ActorRef[AgentCommand])]] =
    AgentMailbox.parse(address) match
      case Some(mailbox) => findBySessionId(mailbox.sessionId)
      case None => IO.pure(None)

  /** Look up by agent name (returns the latest registered session for that agent). */
  def findByAgentName(agentName: String): IO[Option[(AgentMailbox, ActorRef[AgentCommand])]] =
    nameIndex.get.map(_.get(agentName)).flatMap {
      case Some(sid) => findBySessionId(sid)
      case None => IO.pure(None)
    }

  /** List all registered mailboxes (for tool discovery). */
  def listAll: IO[List[AgentMailbox]] =
    registry.get.map(_.values.map(_._1).toList)

  /** Deliver a message to a target session. Returns Left(error) if target not found. */
  def deliver(
    targetSessionId: String,
    message: AgentCommand
  ): IO[Either[String, Unit]] =
    registry.get.map(_.get(targetSessionId)).map {
      case Some((mailbox, ref)) =>
        ref ! message
        Right(())
      case None =>
        Left(s"No agent registered for session ${targetSessionId.take(8)}")
    }

end PostOffice

object PostOffice:

  def create(): IO[PostOffice] =
    for
      reg <- Ref[IO].of[Map[String, (AgentMailbox, ActorRef[AgentCommand])]](Map.empty)
      idx <- Ref[IO].of[Map[String, String]](Map.empty)
    yield new PostOffice(reg, idx)

  /** Stub instance for testing or when PostOffice is not initialized. */
  val stub: PostOffice =
    val reg = Ref.unsafe[IO, Map[String, (AgentMailbox, ActorRef[AgentCommand])]](Map.empty)
    val idx = Ref.unsafe[IO, Map[String, String]](Map.empty)
    new PostOffice(reg, idx)

end PostOffice
