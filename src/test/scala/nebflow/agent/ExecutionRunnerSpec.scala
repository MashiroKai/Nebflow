package nebflow.agent

import cats.effect.{Deferred, IO}
import cats.effect.kernel.Outcome
import cats.syntax.all.*

import scala.concurrent.duration.*

class ExecutionRunnerSpec extends munit.CatsEffectSuite:
  private val attempt = ExecutionRunner.Attempt("session", "run", "attempt", "incarnation")

  test("registered work completes with its attempt and operation receipt") {
    ExecutionRunner.resource(attempt).use { runner =>
      for
        entered <- Deferred[IO, Unit]
        release <- Deferred[IO, Unit]
        accepted <- runner.start("model")(entered.complete(()).void *> release.get)
        receipt = accepted.toOption.get
        _ <- entered.get
        active <- runner.activeOperations
        _ = assertEquals(active, Set("model"))
        _ = assertEquals(receipt.attempt, attempt)
        _ = assertEquals(receipt.operationId, "model")
        _ <- release.complete(())
        result <- receipt.await
        _ = assert(result match
          case Outcome.Succeeded(_) => true
          case _ => false)
        _ <- runner.close
        stopped <- runner.awaitStopped(1.second)
        _ = assert(stopped)
      yield ()
    }
  }

  test("close requests cancellation without waiting for a held finalizer") {
    ExecutionRunner.resource(attempt).use { runner =>
      for
        entered <- Deferred[IO, Unit]
        finalizing <- Deferred[IO, Unit]
        release <- Deferred[IO, Unit]
        accepted <- runner.start("tool")(
          (entered.complete(()).void *> IO.never[Unit]).guarantee(finalizing.complete(()).void *> release.get)
        )
        receipt = accepted.toOption.get
        _ <- entered.get
        _ <- runner.close.timeout(1.second)
        _ <- finalizing.get.timeout(1.second)
        refused <- runner.start("other")(IO.unit)
        _ = assertEquals(refused.left.toOption, Some(ExecutionRunner.Rejection.Closed))
        stopped <- runner.awaitStopped(50.millis)
        _ = assert(!stopped)
        active <- runner.activeOperations
        _ = assertEquals(active, Set("tool"))
        _ <- runner.close.timeout(1.second)
        _ <- release.complete(())
        outcome <- receipt.await.timeout(1.second)
        _ = assert(outcome match
          case Outcome.Canceled() => true
          case _ => false)
        confirmed <- runner.awaitStopped(1.second)
        _ = assert(confirmed)
      yield ()
    }
  }

  test("duplicate live identity is rejected and late completion remains observable") {
    ExecutionRunner.resource(attempt).use { runner =>
      for
        entered <- Deferred[IO, Unit]
        release <- Deferred[IO, Unit]
        first <- runner.start("op")(entered.complete(()).void *> release.get)
        receipt = first.toOption.get
        _ <- entered.get
        duplicate <- runner.start("op")(IO.raiseError(new RuntimeException("must not run")))
        _ = assertEquals(duplicate.left.toOption, Some(ExecutionRunner.Rejection.DuplicateOperation))
        openStopped <- runner.awaitStopped(50.millis)
        _ = assert(!openStopped)
        _ <- release.complete(())
        _ <- receipt.await.timeout(1.second)
        again <- runner.start("op")(IO.unit)
        _ = assert(again.isRight)
        _ <- again.toOption.get.await.timeout(1.second)
        _ <- runner.close
        _ <- runner.close
        stopped <- runner.awaitStopped(1.second)
        _ = assert(stopped)
      yield ()
    }
  }
end ExecutionRunnerSpec
