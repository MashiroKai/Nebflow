package nebflow.actor

import cats.effect.*
import nebflow.core.NebflowLogger

import scala.concurrent.duration.FiniteDuration

/**
 * Local actor implementation: backed by a Queue + Fiber.
 *
 * Messages are offered to the queue. A fiber processes them one at a time
 * by applying the current Behavior. Cancellation is via fiber.cancel.
 */
final class LocalActorRef[Msg](
  val path: ActorPath,
  queue: cats.effect.std.Queue[IO, Msg],
  fiberPromise: Deferred[IO, Fiber[IO, Throwable, Unit]]
) extends ActorRef[Msg]:

  def !(msg: Msg): IO[Unit] = queue.offer(msg)

  def ?[Reply](makeMsg: ActorRef[Reply] => Msg, timeout: Option[FiniteDuration]): IO[Reply] =
    for
      deferred <- Deferred[IO, Reply]
      tempRef = new ActorRef[Reply]:
        val path = ActorPath("__temp", List(java.util.UUID.randomUUID().toString))
        def !(msg: Reply): IO[Unit] = deferred.complete(msg).void
        def ?[R](m: ActorRef[R] => Reply, t: Option[FiniteDuration]): IO[R] =
          IO.raiseError(new UnsupportedOperationException("Nested ask not supported"))
      _ <- this ! makeMsg(tempRef)
      reply <- timeout match
        case Some(d) => deferred.get.timeout(d)
        case None => deferred.get
    yield reply

  /** Stop this actor — cancel the message loop fiber. */
  def stop: IO[Unit] = fiberPromise.get.flatMap(_.cancel)

end LocalActorRef
