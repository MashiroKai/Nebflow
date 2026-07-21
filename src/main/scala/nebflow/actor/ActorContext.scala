package nebflow.actor

import cats.effect.{Fiber, IO, Ref}
import cats.syntax.all.*
import nebflow.core.NebflowLogger

import scala.concurrent.duration.FiniteDuration

/**
 * Capabilities available to an actor during message processing.
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

  /**
   * Fork an IO computation as a tracked turn fiber.
   *
   * Multiple forkTurn calls can be active simultaneously — each is tracked
   * independently by a unique key. This prevents side-effect forkTurns
   * (e.g. wsSend, deferred.complete) from overwriting the main turn's fiber
   * in the tracking slot.
   *
   * The fiber is tracked so that [[cancelCurrentTurn]] can cancel it.
   * When the actor stops (returns [[Behaviors.stopped]] or system.stop),
   * any in-flight turn fiber is automatically cancelled.
   *
   * Errors in the IO are caught and logged — the fiber never fails silently.
   * If the IO needs to notify the actor of failure (e.g. LlmFailed), it should
   * include its own handleErrorWith that sends a message to [[self]].
   *
   * The Actor returns immediately — it never blocks on this IO.
   * The IO should send a message back to [[self]] when done.
   */
  def forkTurn(io: IO[Unit]): IO[Unit]

  /** Cancel all currently running turn fibers (if any). */
  def cancelCurrentTurn(): IO[Unit]

  /** Spawn a child actor with lifecycle bound to this actor. */
  def spawn[ChildMsg](behavior: Behavior[ChildMsg], name: String): IO[ActorRef[ChildMsg]]

  /**
   * Register interest in another actor's lifecycle. When the watched actor
   * terminates, this actor receives [[SystemSignal.Terminated]] via the system
   * signal channel.
   */
  def watch(ref: ActorRef[?]): IO[Unit]

  /** Unregister interest in another actor's lifecycle. */
  def unwatch(ref: ActorRef[?]): IO[Unit]

end ActorContext

/** Implementation of ActorContext for local actors. */
final class LocalActorContext[Msg](
  val self: ActorRef[Msg],
  val system: ActorSystem,
  val log: NebflowLogger,
  private val activeTurnFibers: Ref[IO, Map[String, Fiber[IO, Throwable, Unit]]],
  private val childrenRef: Ref[IO, List[ActorRef[?]]]
) extends ActorContext[Msg]:

  def forkTurn(io: IO[Unit]): IO[Unit] =
    for
      key <- IO(java.util.UUID.randomUUID().toString)
      safeIo = io
        .handleErrorWith(err => log.error(s"forkTurn IO failed: ${err.getMessage}").void)
        .guarantee(activeTurnFibers.update(_ - key))
      fiber <- safeIo.start
      _ <- activeTurnFibers.update(_ + (key -> fiber))
    yield ()

  def cancelCurrentTurn(): IO[Unit] =
    activeTurnFibers.get.flatMap { fibers =>
      fibers.values.toList.traverse_(_.cancel) *> activeTurnFibers.set(Map.empty)
    }

  def spawn[ChildMsg](behavior: Behavior[ChildMsg], name: String): IO[ActorRef[ChildMsg]] =
    system.spawn(behavior, name).flatTap(ref => childrenRef.update(_ :+ ref))

  def watch(ref: ActorRef[?]): IO[Unit] =
    system.watch(self.path, ref.path)

  def unwatch(ref: ActorRef[?]): IO[Unit] =
    system.unwatch(self.path, ref.path)
end LocalActorContext
