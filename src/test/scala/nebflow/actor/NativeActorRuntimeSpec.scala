package nebflow.actor

import cats.effect.{Deferred, IO}
import cats.syntax.all.*
import munit.CatsEffectSuite
import org.apache.pekko.actor.typed.PostStop
import org.apache.pekko.actor.typed.scaladsl.Behaviors as TypedBehaviors

import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.duration.*

class NativeActorRuntimeSpec extends CatsEffectSuite:
  test("synchronous actor systems reject native acquisition without evaluating behavior") {
    val system = ActorSystem("legacy")
    system.spawnNative[Unit](_ => throw new AssertionError("must not evaluate"), "unsupported").use(_ => IO.unit).attempt.map {
      case Left(_: UnsupportedOperationException) => ()
      case other => fail(s"Expected unsupported native acquisition, got $other")
    }
  }

  test("native acquisition waits for setup and fresh identities allow repeated logical names") {
    ActorSystem.resource("native-ready").use { system =>
      for
        ready <- Deferred[IO, Unit]
        _ <- system.spawnNative[Unit](dispatcher => TypedBehaviors.setup { _ =>
          dispatcher.unsafeRunAndForget(ready.complete(()).void)
          TypedBehaviors.ignore
        }, "same-name").use { first =>
          system.spawnNative[Unit](_ => TypedBehaviors.ignore, "same-name").use { second =>
            ready.get.timeout(5.seconds) *> IO {
              assertNotEquals(first.ref.path, second.ref.path)
              assert(first.ref.path.name.startsWith("same-name-"))
            }
          }
        }
      yield ()
    }
  }

  test("explicit stop waits for PostStop and is idempotent") {
    NativeActorRuntime.resource.use { runtime =>
      for
        stopped <- Deferred[IO, Unit]
        _ <- runtime.spawnNative[Unit](dispatcher => TypedBehaviors.receiveMessage[Unit](_ => TypedBehaviors.same)
          .receiveSignal { case (_, PostStop) =>
            dispatcher.unsafeRunAndForget(stopped.complete(()).void)
            TypedBehaviors.same
          }, "explicit-stop").use { handle =>
          handle.stop.timeout(5.seconds) *> handle.terminated.timeout(5.seconds) *>
            stopped.get.timeout(5.seconds) *> handle.stop.timeout(5.seconds)
        }
      yield ()
    }
  }

  test("protocol stop and unexpected failure both complete actual termination") {
    NativeActorRuntime.resource.use { runtime =>
      val protocol = runtime.spawnNative[Unit](_ => TypedBehaviors.receiveMessage(_ => TypedBehaviors.stopped), "protocol")
      val failure = runtime.spawnNative[Unit](_ => TypedBehaviors.receiveMessage(_ => throw new IllegalStateException("probe")), "failure")
      List(protocol, failure).traverse_(_.use { handle =>
        IO(handle.ref ! ()) *> handle.terminated.timeout(5.seconds) *> handle.stop.timeout(5.seconds)
      })
    }
  }

  test("factory, deferred setup, stopped initial behavior, and invalid names fail acquisition") {
    NativeActorRuntime.resource.use { runtime =>
      val factory = runtime.spawnNative[Unit](_ => throw new IllegalStateException("factory probe"), "factory")
      val setup = runtime.spawnNative[Unit](_ => TypedBehaviors.setup(_ => throw new IllegalStateException("setup probe")), "setup")
      val stopped = runtime.spawnNative[Unit](_ => TypedBehaviors.stopped, "stopped")
      val invalid = runtime.spawnNative[Unit](_ => TypedBehaviors.ignore, "invalid/name")
      List(factory, setup, stopped, invalid).traverse_ { acquisition =>
        acquisition.use(_ => IO.unit).attempt.timeout(5.seconds).map(result => assert(result.isLeft))
      } *> runtime.spawnNative[Unit](_ => TypedBehaviors.ignore, "still-alive").use(_ => IO.unit)
    }
  }

  test("cancellation during setup waits for the acquired child to terminate") {
    NativeActorRuntime.resource.use { runtime =>
      for
        entered <- Deferred[IO, Unit]
        stopped <- Deferred[IO, Unit]
        latch <- IO(new CountDownLatch(1))
        _ <- (for
          fiber <- runtime.spawnNative[Unit](dispatcher => TypedBehaviors.setup { _ =>
            dispatcher.unsafeRunAndForget(entered.complete(()).void)
            if !latch.await(5, TimeUnit.SECONDS) then throw new IllegalStateException("setup barrier expired")
            TypedBehaviors.receiveMessage[Unit](_ => TypedBehaviors.same).receiveSignal { case (_, PostStop) =>
              dispatcher.unsafeRunAndForget(stopped.complete(()).void)
              TypedBehaviors.same
            }
          }, "cancel-setup").use(_ => IO.never).start
          _ <- entered.get.timeout(5.seconds)
          cancelling <- fiber.cancel.start
          _ <- IO(latch.countDown())
          _ <- cancelling.joinWithNever.timeout(5.seconds)
          _ <- stopped.get.timeout(5.seconds)
        yield ()).guarantee(IO(latch.countDown()))
      yield ()
    }
  }

  test("runtime release stops children, completes their handles, and rejects new work") {
    for
      allocated <- NativeActorRuntime.resource.allocated
      (runtime, release) = allocated
      _ <- (for
        child <- runtime.spawnNative[Unit](_ => TypedBehaviors.ignore, "runtime-release").allocated
        (handle, releaseChild) = child
        _ <- release.timeout(5.seconds)
        _ <- handle.terminated.timeout(5.seconds)
        _ <- releaseChild.timeout(5.seconds)
        spawn <- runtime.spawnNative[Unit](_ => TypedBehaviors.ignore, "closed").use(_ => IO.unit).attempt
        timer <- runtime.scheduleOnce(Duration.Zero, IO.unit).attempt
      yield
        assert(spawn.isLeft)
        assert(timer.isLeft)
      ).guarantee(release)
    yield ()
  }

  test("managed release stops local actors while native children are still usable") {
    for
      localStopped <- Deferred[IO, Unit]
      localReady <- Deferred[IO, Unit]
      nativeReplied <- Deferred[IO, Unit]
      _ <- ActorSystem.resource("ordered-release").use { system =>
        system.spawnNative[Unit](dispatcher => TypedBehaviors.receiveMessage { _ =>
          dispatcher.unsafeRunAndForget(nativeReplied.complete(()).void)
          TypedBehaviors.same
        }, "reply-on-stop").allocated.flatMap { case (native, _) =>
          val local = new Behavior[Unit]:
            def receive(ctx: ActorContext[Unit], msg: Unit): IO[Behavior[Unit]] = localReady.complete(()).as(this)
            override def onStop(ctx: ActorContext[Unit]): IO[Unit] =
              IO(native.ref ! ()) *> nativeReplied.get.timeout(5.seconds) *> localStopped.complete(()).void
          system.spawn(local, "local").flatMap(ref => ref ! ()) *> localReady.get.timeout(5.seconds)
        }
      }.timeout(10.seconds)
      _ <- localStopped.get.timeout(5.seconds)
    yield ()
  }
