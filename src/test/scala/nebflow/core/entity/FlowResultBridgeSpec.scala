package nebflow.core.entity

import cats.effect.{Deferred, IO}
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, AgentError, AgentEvent}

import scala.concurrent.duration.*

class FlowResultBridgeSpec extends CatsEffectSuite:

  test("unrelated ticks preserve terminal handling") {
    ActorSystem.resource("bridge-tick").use { system =>
      FlowResultBridge.resource(system, "result").use { bridge =>
        for
          _ <- bridge.replyTo ! AgentEvent.BgCompletionCapTick("session", 0L, 1L)
          _ <- bridge.replyTo ! AgentEvent.Completed("session")
          result <- bridge.result.timeout(3.seconds)
          _ <- bridge.terminated.timeout(3.seconds)
        yield assertEquals(result, Right(Nil))
      }
    }
  }

  test("failure retryability and cancellation retain their mappings") {
    ActorSystem.resource("bridge-mapping").use { system =>
      List(
        AgentEvent.Failed(
          "session",
          AgentError("session", "worker", 1, nebflow.actor.AgentErrorType.LlmFailed, "failed", retryable = Some(true))
        ) ->
          Left(FlowResultBridge.Failure("failed", retryable = true)),
        AgentEvent.Cancelled("session", "user") ->
          Left(FlowResultBridge.Failure("cancelled: user", retryable = false))
      ).traverse_ { case (event, expected) =>
        FlowResultBridge.resource(system, "result").use { bridge =>
          (bridge.replyTo ! event) *> bridge.result.timeout(3.seconds).map(result => assertEquals(result, expected))
        }
      }
    }
  }

  test("duplicate terminal events cannot replace an accepted result") {
    ActorSystem.resource("bridge-duplicate").use { system =>
      for
        entered <- Deferred[IO, Unit]
        publish <- Deferred[IO, Unit]
        _ <- FlowResultBridge
          .acquire(system, "result", result => entered.complete(()).void *> publish.get.as(result))
          .use { bridge =>
            for
              _ <- bridge.replyTo ! AgentEvent.Completed("session")
              _ <- entered.get.timeout(3.seconds)
              _ <- bridge.replyTo ! AgentEvent.Cancelled("session", "late")
              _ <- publish.complete(())
              result <- bridge.result.timeout(3.seconds)
            yield assertEquals(result, Right(Nil))
          }
      yield ()
    }
  }

  test("release waits for accepted publication and actual termination") {
    ActorSystem.resource("bridge-release").use { system =>
      for
        entered <- Deferred[IO, Unit]
        publish <- Deferred[IO, Unit]
        released <- Deferred[IO, Unit]
        pair <- FlowResultBridge
          .acquire(system, "result", result => entered.complete(()).void *> publish.get.as(result))
          .allocated
        (bridge, release) = pair
        _ <- (for
          _ <- bridge.replyTo ! AgentEvent.Completed("session")
          _ <- entered.get.timeout(3.seconds)
          fiber <- (release *> released.complete(()).void).start
          _ <- IO.cede
          before <- released.tryGet
          _ = assertEquals(before, None)
          _ <- publish.complete(())
          _ <- fiber.joinWithNever.timeout(3.seconds)
          result <- bridge.result.timeout(3.seconds)
          _ <- bridge.terminated.timeout(3.seconds)
        yield assertEquals(result, Right(Nil))).guarantee(publish.complete(()).void *> release)
      yield ()
    }
  }

  test("explicit release terminates an idle bridge and wakes its waiter with failure") {
    ActorSystem.resource("bridge-stop").use { system =>
      FlowResultBridge.resource(system, "result").allocated.flatMap { case (bridge, release) =>
        for
          _ <- release.timeout(3.seconds)
          _ <- bridge.terminated.timeout(3.seconds)
          outcome <- bridge.result.attempt.timeout(3.seconds)
        yield assert(outcome.isLeft)
      }
    }
  }

  test("runtime shutdown wakes an outstanding bridge waiter") {
    ActorSystem.resource("bridge-shutdown").allocated.flatMap { case (system, shutdown) =>
      (for
        pair <- FlowResultBridge.resource(system, "result").allocated
        (bridge, release) = pair
        _ <- (for
          _ <- shutdown.timeout(3.seconds)
          outcome <- bridge.result.attempt.timeout(3.seconds)
        yield assert(outcome.isLeft)).guarantee(release)
      yield ()).guarantee(shutdown)
    }
  }

  test("publication failure reaches the waiter and still terminates") {
    ActorSystem.resource("bridge-publish-failure").use { system =>
      val error = new RuntimeException("publication failed")
      FlowResultBridge.acquire(system, "result", _ => IO.raiseError(error)).use { bridge =>
        for
          _ <- bridge.replyTo ! AgentEvent.Completed("session")
          outcome <- bridge.result.attempt.timeout(3.seconds)
          _ <- bridge.terminated.timeout(3.seconds)
        yield assertEquals(outcome, Left(error))
      }
    }
  }

  test("canceling a result waiter does not wait for bridge termination") {
    ActorSystem.resource("bridge-wait-cancel").use { system =>
      FlowResultBridge.resource(system, "result").use { bridge =>
        for
          waiting <- bridge.result.start
          _ <- IO.cede
          _ <- waiting.cancel.timeout(2.seconds)
        yield ()
      }
    }
  }

  test("synchronous legacy systems reject native acquisition explicitly") {
    FlowResultBridge.resource(ActorSystem("legacy"), "result").use(_ => IO.unit).attempt.map { outcome =>
      assert(outcome.isLeft)
    }
  }
end FlowResultBridgeSpec
