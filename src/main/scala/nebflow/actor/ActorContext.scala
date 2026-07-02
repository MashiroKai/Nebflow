package nebflow.actor

import cats.effect.{Fiber, IO, Ref}
import nebflow.core.NebflowLogger

import scala.concurrent.duration.FiniteDuration

/** Capabilities available to an actor during message processing.
  *
  * The actor uses this to:
  *   - reference itself (for replyTo)
  *   - fork IO work (non-blocking, tracked for cancellation)
  *   - cancel the current turn's IO work (for Interrupt)
  *   - spawn child actors (lifecycle bound to parent)
  *   - access the system (for resolve, etc.)
  */
trait ActorContext[Msg]:
  /** This actor's own reference — pass as replyTo in messages. */
  def self: ActorRef[Msg]

  /** The actor system — for resolve, global operations. */
  def system: ActorSystem

  /** Logger scoped to this actor. */
  def log: NebflowLogger

  /** Fork an IO computation as a tracked turn fiber.
    *
    * The fiber is tracked so that [[cancelCurrentTurn]] can cancel it.
    * When the actor stops (returns [[Behaviors.stopped]] or system.stop),
    * any in-flight turn fiber is automatically cancelled.
    *
    * The Actor returns immediately — it never blocks on this IO.
    * The IO should send a message back to [[self]] when done.
    */
  def forkTurn(io: IO[Unit]): IO[Unit]

  /** Cancel the currently running turn fiber (if any). */
  def cancelCurrentTurn(): IO[Unit]

  /** Spawn a child actor with lifecycle bound to this actor. */
  def spawn[ChildMsg](behavior: Behavior[ChildMsg], name: String): IO[ActorRef[ChildMsg]]

/** Implementation of ActorContext for local actors. */
final class LocalActorContext[Msg](
    val self: ActorRef[Msg],
    val system: ActorSystem,
    val log: NebflowLogger,
    private val currentTurnRef: Ref[IO, Option[Fiber[IO, Throwable, Unit]]],
    private val childrenRef: Ref[IO, List[ActorRef[?]]]
) extends ActorContext[Msg]:

  def forkTurn(io: IO[Unit]): IO[Unit] =
    io.guarantee(currentTurnRef.set(None)).start.flatMap { fiber =>
      currentTurnRef.set(Some(fiber))
    }

  def cancelCurrentTurn(): IO[Unit] =
    currentTurnRef.get.flatMap {
      case Some(fiber) => fiber.cancel *> currentTurnRef.set(None)
      case None        => IO.unit
    }

  def spawn[ChildMsg](behavior: Behavior[ChildMsg], name: String): IO[ActorRef[ChildMsg]] =
    system.spawn(behavior, name).flatTap(ref => childrenRef.update(_ :+ ref))
end LocalActorContext
