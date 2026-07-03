package nebflow.actor

import cats.effect.std.Queue
import cats.effect.{Fiber, IO, Ref}
import cats.syntax.all.*
import nebflow.core.NebflowLogger

import scala.concurrent.duration.FiniteDuration

/**
 * The actor system — registry + message routing.
 *
 * spawn creates local actors (Queue + Fiber + Behavior loop).
 * resolve looks up actors by path (local registry lookup).
 * stop cancels an actor's fiber, releasing all resources.
 */
trait ActorSystem:
  def localDevice: String
  def spawn[Msg](behavior: Behavior[Msg], name: String, ttl: Option[FiniteDuration] = None): IO[ActorRef[Msg]]
  def resolve[Msg](path: String): IO[ActorRef[Msg]]
  def stop(ref: ActorRef[?]): IO[Unit]
  def stopAll: IO[Unit]

object ActorSystem:

  /** Create a local actor system. */
  def apply(deviceName: String): ActorSystem =
    new LocalActorSystem(deviceName)

  /** Create an actor system from a given device name, as a Resource. */
  def resource(deviceName: String): cats.effect.Resource[IO, ActorSystem] =
    cats.effect.Resource.eval(IO(apply(deviceName)))

end ActorSystem

/** Local-only actor system implementation. */
final class LocalActorSystem(val localDevice: String) extends ActorSystem:
  private val log = NebflowLogger.forName("nebflow.actor")

  private case class RegistryEntry(
    ref: ActorRef[?],
    fiber: Fiber[IO, Throwable, Unit]
  )

  private val registry: Ref[IO, Map[String, RegistryEntry]] = Ref.unsafe(Map.empty)

  def spawn[Msg](
    behavior: Behavior[Msg],
    name: String,
    ttl: Option[FiniteDuration] = None
  ): IO[ActorRef[Msg]] =
    val path = ActorPath(localDevice, name)
    val ctxLog = NebflowLogger.forName(s"nebflow.actor.$name")
    for
      queue <- Queue.unbounded[IO, Msg]
      turnRef <- Ref.of[IO, Option[Fiber[IO, Throwable, Unit]]](None)
      childrenRef <- Ref.of[IO, List[ActorRef[?]]](Nil)
      fiberPromise <- cats.effect.Deferred[IO, Fiber[IO, Throwable, Unit]]
      ref = new LocalActorRef[Msg](path, queue, fiberPromise)
      ctx = new LocalActorContext[Msg](ref, this, ctxLog, turnRef, childrenRef)
      initBehavior <- behavior.onStart(ctx)
      behaviorRef <- Ref.of[IO, Behavior[Msg]](initBehavior)
      fiber <- actorLoop(path, ctx, queue, behaviorRef, ttl, childrenRef).start
      _ <- fiberPromise.complete(fiber)
      _ <- registry.update(_.updated(path.toString, RegistryEntry(ref, fiber)))
    yield ref

  end spawn

  def resolve[Msg](pathStr: String): IO[ActorRef[Msg]] =
    ActorPath.parse(pathStr) match
      case Right(path) =>
        if path.isLocal(localDevice) then
          registry.get.map(_.get(path.toString) match
            case Some(entry) => entry.ref.asInstanceOf[ActorRef[Msg]]
            case None => throw new NoSuchElementException(s"Actor not found: $pathStr"))
        else
          IO.raiseError(
            new NotImplementedError(s"Remote actor resolution not yet implemented: $pathStr")
          )
      case Left(err) =>
        IO.raiseError(new IllegalArgumentException(err))

  def stop(ref: ActorRef[?]): IO[Unit] =
    ref match
      case r: LocalActorRef[?] =>
        r.stop *> registry.update(_ - r.path.toString)
      case _ => IO.unit

  /** Stop all actors — for shutdown. */
  def stopAll: IO[Unit] =
    registry.get.flatMap { entries =>
      entries.values.toList.traverse_ { entry =>
        entry.fiber.cancel
      } *> registry.set(Map.empty)
    }

  // ============================================================
  // Actor message loop
  // ============================================================

  private def actorLoop[Msg](
    path: ActorPath,
    ctx: LocalActorContext[Msg],
    queue: Queue[IO, Msg],
    behaviorRef: Ref[IO, Behavior[Msg]],
    ttl: Option[FiniteDuration],
    childrenRef: Ref[IO, List[ActorRef[?]]]
  ): IO[Unit] =
    val take: IO[Option[Msg]] = ttl match
      case Some(duration) =>
        IO.race(queue.take, IO.sleep(duration)).map {
          case Left(msg) => Some(msg)
          case Right(_) => None
        }
      case None =>
        queue.take.map(Some(_))

    def loop: IO[Unit] =
      take.flatMap {
        case None =>
          // TTL expired
          ctx.log.info(s"Actor $path TTL expired, stopping")
          cleanupChildren(childrenRef)
        case Some(msg) =>
          (for
            current <- behaviorRef.get
            next <- current
              .receive(ctx, msg)
              .handleErrorWith { err =>
                ctx.log.error(s"Actor $path error in message processing: ${err.getMessage}")
                IO.pure(current) // supervision: resume with same behavior
              }
            _ <- behaviorRef.set(next)
          yield next).flatMap {
            case b if b.isStopped =>
              ctx.log.info(s"Actor $path stopped")
              // Cancel any in-flight turn + stop children
              ctx.cancelCurrentTurn() *> cleanupChildren(childrenRef)
            case _ =>
              loop
          }
      }

    loop
  end actorLoop

  private def cleanupChildren(childrenRef: Ref[IO, List[ActorRef[?]]]): IO[Unit] =
    childrenRef.get.flatMap { children =>
      children.traverse_ {
        case r: LocalActorRef[?] => r.stop
        case _ => IO.unit
      } *> childrenRef.set(Nil)
    }

end LocalActorSystem
