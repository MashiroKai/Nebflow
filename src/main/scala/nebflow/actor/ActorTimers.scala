package nebflow.actor

import cats.effect.{IO, Resource}

import scala.concurrent.duration.FiniteDuration

/** Runtime-owned one-shot timers; delivery runs outside the Pekko mailbox. */
trait ActorTimers:
  def scheduleOnce(delay: FiniteDuration, delivery: IO[Unit]): IO[Unit]

object ActorTimers:
  /** Compatibility facade over the shared native runtime implementation. */
  def resource: Resource[IO, ActorTimers] = NativeActorRuntime.resource.map(runtime => runtime: ActorTimers)
