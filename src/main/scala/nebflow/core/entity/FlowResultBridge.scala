package nebflow.core.entity

import cats.effect.std.Dispatcher
import cats.effect.{Deferred, IO, Resource}
import cats.syntax.all.*
import nebflow.actor.*
import nebflow.shared.Message
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import scala.concurrent.duration.FiniteDuration

/** One execution's terminal reply channel, owned independently of the legacy registry. */
private[entity] object FlowResultBridge:
  final case class Failure(message: String, retryable: Boolean)
  type Result = Either[Failure, List[Message]]

  final case class Handle(replyTo: ActorRef[AgentEvent], result: IO[Result], terminated: IO[Unit])

  private sealed trait Command
  private final case class Event(event: AgentEvent) extends Command
  private case object Published extends Command
  private case object Stop extends Command

  def resource(system: ActorSystem, name: String): Resource[IO, Handle] =
    acquire(system, name, result => IO.pure(result))

  private[entity] def acquire(
    system: ActorSystem,
    name: String,
    beforePublish: Result => IO[Result]
  ): Resource[IO, Handle] =
    for
      result <- Resource.eval(Deferred[IO, Either[Throwable, Result]])
      native <- system.spawnNative[Command](dispatcher => behavior(dispatcher, result, beforePublish), name)
      // Stop is serialized with terminal acceptance; accepted publication finishes first.
      _ <- Resource.make(IO.unit)(_ => IO(native.ref ! Stop) *> native.terminated)
    yield
      val adapter = new ActorRef[AgentEvent]:
        val path = ActorPath(system.localDevice, List("native", native.ref.path.name))
        def !(event: AgentEvent): IO[Unit] = IO(native.ref ! Event(event))
        def ?[Reply](makeMsg: ActorRef[Reply] => AgentEvent, timeout: Option[FiniteDuration]): IO[Reply] =
          IO.raiseError(new UnsupportedOperationException("Flow result bridges accept terminal events, not ask requests"))
      val awaitResult = IO.race(result.get, native.terminated).flatMap {
        case Left(value) => IO.fromEither(value)
        case Right(_) =>
          result.tryGet.flatMap {
            case Some(value) => IO.fromEither(value)
            case None => IO.raiseError(new IllegalStateException("Flow result bridge stopped before publishing a result"))
          }
      }
      Handle(adapter, awaitResult, native.terminated)

  private def behavior(
    dispatcher: Dispatcher[IO],
    result: Deferred[IO, Either[Throwable, Result]],
    beforePublish: Result => IO[Result]
  ): Behavior[Command] =
    Behaviors.setup { context =>
      def receive(publishing: Boolean): Behavior[Command] =
        Behaviors.receiveMessage[Command] {
          case Event(_: AgentEvent.BgCompletionCapTick) => Behaviors.same
          case Event(_) if publishing => Behaviors.same
          case Event(event) =>
            val outcome: Result = event match
              case AgentEvent.Completed(_, messages) => Right(messages)
              case AgentEvent.Failed(_, error) => Left(Failure(error.message, error.retryable.getOrElse(false)))
              case AgentEvent.Cancelled(_, reason) => Left(Failure(s"cancelled: $reason", retryable = false))
              case _: AgentEvent.BgCompletionCapTick => throw new IllegalStateException("Unexpected timer event")
            dispatcher.unsafeRunAndForget(
              beforePublish(outcome).attempt.flatMap(value => result.complete(value).void) *>
                IO(context.self ! Published)
            )
            receive(publishing = true)
          case Published => Behaviors.stopped
          case Stop if publishing => Behaviors.same
          case Stop => Behaviors.stopped
        }
      receive(publishing = false)
    }
