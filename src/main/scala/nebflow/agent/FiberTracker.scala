package nebflow.agent

import cats.effect.{Fiber, IO, Ref}
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*

/** Tracks IO fibers forked from an actor. Enables cancellation of all
  * in-flight work on Interrupt/Stop/SessionReset.
  */
class FiberTracker(dispatcher: Dispatcher[IO]):
  private val fibers = Ref.unsafe[IO, List[Fiber[IO, Throwable, Unit]]](Nil)

  /** Fork an IO as a tracked fiber. Replaces dispatcher.unsafeRunAndForget. */
  def fork[A](io: IO[A]): Unit =
    dispatcher.unsafeRunAndForget(
      io.void.start.flatMap(fiber => fibers.update(_ :+ fiber))
    )

  /** Cancel all tracked fibers. Called on Interrupt/Stop. */
  def cancelAll(): Unit =
    dispatcher.unsafeRunAndForget(
      fibers.get.flatMap(_.traverse_(_.cancel)) *> fibers.set(Nil)
    )

  /** Clear completed fibers (allow GC). Called when returning to idle. */
  def clear(): Unit =
    dispatcher.unsafeRunAndForget(fibers.set(Nil))
end FiberTracker
