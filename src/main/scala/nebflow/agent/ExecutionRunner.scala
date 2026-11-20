package nebflow.agent

import cats.effect.{Deferred, Fiber, IO, Ref, Resource}
import cats.effect.kernel.Outcome
import cats.effect.std.Semaphore
import cats.syntax.all.*

import scala.concurrent.duration.FiniteDuration

/** Volatile ownership of the work of one attempt, not a durable execution authority. */
final class ExecutionRunner private (
  val attempt: ExecutionRunner.Attempt,
  state: Ref[IO, ExecutionRunner.State],
  gate: Semaphore[IO]
):
  import ExecutionRunner.*

  /** Registration is atomic with revocation; accepted work is owned before it is started. */
  def start(operationId: String)(work: IO[Unit]): IO[Either[Rejection, Receipt]] =
    gate.permit.use { _ =>
      IO.uncancelable { poll =>
        for
          fiberReady <- Deferred[IO, Fiber[IO, Throwable, Unit]]
          done <- Deferred[IO, Outcome[IO, Throwable, Unit]]
          entry = Entry(fiberReady, done)
          accepted <- state.modify { current =>
            if current.closed then (current, Left(Rejection.Closed))
            else if operationId.isEmpty then (current, Left(Rejection.InvalidOperation))
            else if current.active.contains(operationId) then (current, Left(Rejection.DuplicateOperation))
            else (current.copy(active = current.active.updated(operationId, entry)), Right(()))
          }
          result <- accepted match
            case Left(reason) => IO.pure(Left(reason))
            case Right(_) =>
              poll(work).start.flatMap { fiber =>
                val observe = fiber.join.flatMap { outcome =>
                  state.update(s => s.copy(active = s.active - operationId)) *> done.complete(outcome).void
                }
                observe.start *> fiberReady.complete(fiber).as(Right(Receipt(attempt, operationId, done)))
              }
        yield result
      }
    }

  /** Revoke admission and request cancellation without waiting for work finalizers. */
  def close: IO[Unit] = gate.permit.use { _ =>
    IO.uncancelable { _ =>
      state
        .modify { current =>
          if current.closed then (current, List.empty[Entry])
          else (current.copy(closed = true), current.active.values.toList)
        }
        .flatMap(_.traverse_(entry => entry.fiber.get.flatMap(_.cancel).start.void))
    }
  }

  def activeOperations: IO[Set[String]] = state.get.map(_.active.keySet)

  /** A timeout is only an observation; it never removes ownership or cancels cleanup. */
  def awaitStopped(timeout: FiniteDuration): IO[Boolean] =
    awaitStoppedUnbounded.timeoutTo(timeout, IO.pure(false))

  private[agent] def awaitStoppedUnbounded: IO[Boolean] =
    state.get.flatMap { current =>
      if !current.closed then IO.pure(false)
      else current.active.values.toList.traverse_(_.done.get).as(true)
    }

end ExecutionRunner

object ExecutionRunner:
  final case class Attempt(sessionId: String, executionId: String, attemptId: String, incarnationId: String)

  enum Rejection:
    case Closed, InvalidOperation, DuplicateOperation

  final case class Receipt private[agent] (
    attempt: Attempt,
    operationId: String,
    private val completion: Deferred[IO, Outcome[IO, Throwable, Unit]]
  ):
    def await: IO[Outcome[IO, Throwable, Unit]] = completion.get

  private final case class Entry(
    fiber: Deferred[IO, Fiber[IO, Throwable, Unit]],
    done: Deferred[IO, Outcome[IO, Throwable, Unit]]
  )
  private final case class State(closed: Boolean, active: Map[String, Entry])

  def resource(attempt: Attempt): Resource[IO, ExecutionRunner] =
    Resource.make(
      (Ref.of[IO, State](State(closed = false, Map.empty)), Semaphore[IO](1)).mapN(new ExecutionRunner(attempt, _, _))
    )(_.close)
end ExecutionRunner
