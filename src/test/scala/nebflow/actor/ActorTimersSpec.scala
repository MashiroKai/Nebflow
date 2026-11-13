package nebflow.actor

import cats.effect.{Deferred, IO, Ref}
import munit.CatsEffectSuite

import scala.concurrent.duration.*

class ActorTimersSpec extends CatsEffectSuite:
  test("a timer does not deliver before its deadline") {
    ActorTimers.resource.use { timers =>
      for
        deliveredAt <- Deferred[IO, FiniteDuration]
        startedAt <- IO.monotonic
        _ <- timers.scheduleOnce(120.millis, IO.monotonic.flatMap(deliveredAt.complete).void)
        end <- deliveredAt.get.timeout(5.seconds)
      yield assert(end - startedAt >= 120.millis)
    }
  }

  test("independent timers deliver once, including nonpositive delays") {
    ActorTimers.resource.use { timers =>
      for
        count <- Ref.of[IO, Int](0)
        done <- Deferred[IO, Unit]
        delivery = count.updateAndGet(_ + 1).flatMap(n => if n == 3 then done.complete(()).void else IO.unit)
        _ <- timers.scheduleOnce(80.millis, delivery)
        _ <- timers.scheduleOnce(Duration.Zero, delivery)
        _ <- timers.scheduleOnce((-1).millis, delivery)
        _ <- done.get.timeout(5.seconds)
        _ <- IO.sleep(150.millis)
        n <- count.get
      yield assertEquals(n, 3)
    }
  }

  test("delivery failure does not stop subsequent timers") {
    ActorTimers.resource.use { timers =>
      for
        failed <- Deferred[IO, Unit]
        done <- Deferred[IO, Unit]
        _ <- timers.scheduleOnce(Duration.Zero, failed.complete(()).void *> IO.raiseError(new Exception("probe")))
        _ <- failed.get.timeout(5.seconds)
        _ <- timers.scheduleOnce(Duration.Zero, done.complete(()).void)
        _ <- done.get.timeout(5.seconds)
      yield ()
    }
  }

  test("resource release cancels pending timers and running deliveries and rejects new schedules") {
    for
      late <- Deferred[IO, Unit]
      entered <- Deferred[IO, Unit]
      cancelled <- Deferred[IO, Unit]
      timers <- ActorTimers.resource.use { timers =>
        timers.scheduleOnce(500.millis, late.complete(()).void) *>
          timers.scheduleOnce(Duration.Zero, (entered.complete(()).void *> IO.never).onCancel(cancelled.complete(()).void)) *>
          entered.get.timeout(5.seconds).as(timers)
      }
      _ <- cancelled.get.timeout(5.seconds)
      _ <- IO.sleep(600.millis)
      delivered <- late.tryGet
      rejected <- timers.scheduleOnce(Duration.Zero, IO.unit).attempt
    yield
      assertEquals(delivered, None)
      assert(rejected.isLeft)
  }

  test("managed actor timers survive turn cancellation and suppress delivery after actor stop") {
    ActorSystem.resource("pekko-timer-probe").use { system =>
      for
        context <- Deferred[IO, ActorContext[Unit]]
        inbox <- cats.effect.std.Queue.unbounded[IO, Unit]
        ref <- system.spawn(
          Behaviors.setup[Unit] { ctx =>
            context.complete(ctx).as(Behaviors.receiveMessage(_ => inbox.offer(()).as(Behaviors.ignore[Unit])))
          },
          "probe"
        )
        ctx <- context.get
        _ <- ctx.scheduleOnce(100.millis, ())
        _ <- ctx.cancelCurrentTurn()
        _ <- inbox.take.timeout(5.seconds)
        _ <- system.stop(ref)
        _ <- ctx.scheduleOnce(Duration.Zero, ())
        _ <- IO.sleep(150.millis)
        extra <- inbox.tryTake
        deadLetters <- system.deadLetterCount
      yield
        assertEquals(extra, None)
        assertEquals(deadLetters, 0L)
    }
  }
