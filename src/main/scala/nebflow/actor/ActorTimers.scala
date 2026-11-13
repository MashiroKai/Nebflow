package nebflow.actor

import cats.effect.{IO, Resource}
import cats.effect.std.Dispatcher
import com.typesafe.config.ConfigFactory
import nebflow.shared.NebflowLogger
import org.apache.pekko.actor.typed.{ActorSystem as TypedActorSystem, Behavior as TypedBehavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors as TypedBehaviors

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.*

/** Runtime-owned one-shot timers; delivery runs outside the Pekko mailbox. */
trait ActorTimers:
  def scheduleOnce(delay: FiniteDuration, delivery: IO[Unit]): IO[Unit]

object ActorTimers:
  private sealed trait Command
  private case class Schedule(delay: FiniteDuration, delivery: IO[Unit]) extends Command
  private case class Deliver(delivery: IO[Unit]) extends Command

  private def behavior(dispatcher: Dispatcher[IO]): TypedBehavior[Command] =
    TypedBehaviors.withTimers { timers =>
      TypedBehaviors.receiveMessage {
        case Schedule(delay, delivery) =>
          // A fresh key preserves independent registrations, even for the same recipient.
          timers.startSingleTimer(new Object, Deliver(delivery), delay)
          TypedBehaviors.same
        case Deliver(delivery) =>
          dispatcher.unsafeRunAndForget(
            delivery.handleErrorWith(error =>
              NebflowLogger.forName("nebflow.actor.timers").warn(s"Timer delivery failed: ${error.getMessage}").void
            )
          )
          TypedBehaviors.same
      }
    }

  /** Local-only runtime. Release stops pending timers, then cancels delivery IO. */
  def resource: Resource[IO, ActorTimers] =
    Dispatcher.parallel[IO](await = false).flatMap { dispatcher =>
      Resource.make {
        IO {
          val closed = new AtomicBoolean(false)
          val config = ConfigFactory.parseString("""
            pekko.actor.provider = local
            pekko.coordinated-shutdown.run-by-jvm-shutdown-hook = off
            pekko.log-dead-letters-during-shutdown = off
          """).withFallback(ConfigFactory.load())
          val system = TypedActorSystem(behavior(dispatcher), s"stand-timers-${UUID.randomUUID()}", config)
          (system, closed)
        }
      } { case (system, closed) =>
        IO(closed.set(true)) *> IO(system.terminate()) *>
          IO.fromFuture(IO(system.whenTerminated)).void
      }.map { case (system, closed) =>
        new ActorTimers:
          def scheduleOnce(delay: FiniteDuration, delivery: IO[Unit]): IO[Unit] = IO {
            if closed.get() then throw new IllegalStateException("Actor timers are closed")
            system ! Schedule(if delay <= Duration.Zero then Duration.Zero else delay, delivery)
          }
      }
    }
