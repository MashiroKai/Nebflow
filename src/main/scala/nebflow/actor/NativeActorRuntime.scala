package nebflow.actor

import cats.effect.std.Dispatcher
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.typesafe.config.ConfigFactory
import nebflow.shared.NebflowLogger
import org.apache.pekko.actor.typed.scaladsl.Behaviors as TypedBehaviors
import org.apache.pekko.actor.typed.{ActorRef as TypedActorRef, ActorSystem as TypedActorSystem, Behavior as TypedBehavior, *}

import java.util.UUID

import scala.concurrent.Promise
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/** A native child owned by the managed runtime, not the local actor registry. */
final case class NativeActorHandle[Msg](
  ref: TypedActorRef[Msg],
  terminated: IO[Unit],
  stop: IO[Unit]
)

/** One native system owns both timers and explicitly acquired native children. */
final class NativeActorRuntime private (
  private val system: TypedActorSystem[NativeActorRuntime.Command],
  private val gate: NativeActorRuntime.Gate
) extends ActorTimers:
  import NativeActorRuntime.*

  def scheduleOnce(delay: FiniteDuration, delivery: IO[Unit]): IO[Unit] =
    IO(gate.submitTimer(system, Schedule(delay.max(Duration.Zero), delivery)))

  def spawnNative[Msg](
    behavior: Dispatcher[IO] => TypedBehavior[Msg],
    name: String
  ): Resource[IO, NativeActorHandle[Msg]] =
    Resource.makeFull[IO, NativeActorHandle[Msg]] { poll =>
      IO(new Request[Msg](s"$name-${UUID.randomUUID()}", behavior)).flatMap { request =>
        // Cancel only this observation, never the child's termination promise.
        val terminated = IO.fromFutureCancelable(IO((request.terminated.future, IO.unit)))
        val stop = IO(gate.stop(system, request.id)) *> terminated
        IO(gate.spawn(system, request)) *>
          poll(IO.fromFutureCancelable(IO((request.ready.future, IO.unit))))
            .onCancel(stop)
            .onError { case _ => stop }
            .map(ref => NativeActorHandle(ref, terminated, stop))
      }
    }(_.stop)

object NativeActorRuntime:
  private sealed trait Command
  private case class Schedule(delay: FiniteDuration, delivery: IO[Unit]) extends Command
  private case class Deliver(delivery: IO[Unit]) extends Command
  private case class Spawn[Msg](request: Request[Msg]) extends Command
  private case class Ready(id: String) extends Command
  private case class Stop(id: String) extends Command
  private case class ChildTerminated(id: String) extends Command

  private final class Request[Msg](val id: String, val behavior: Dispatcher[IO] => TypedBehavior[Msg]):
    val ready = Promise[TypedActorRef[Msg]]()
    val terminated = Promise[Unit]()

    def fail(error: Throwable): Unit =
      ready.tryFailure(error)
      ()

    def finish(): Unit =
      fail(new IllegalStateException(s"Native actor $id terminated before readiness"))
      terminated.trySuccess(())
      ()

  /** Admission and shutdown share a lock so no request is lost behind termination. */
  private final class Gate:
    private var closed = false
    private var requests = Map.empty[String, Request[?]]

    def spawn[Msg](system: TypedActorSystem[Command], request: Request[Msg]): Unit = synchronized {
      if closed then throw new IllegalStateException("Native actor runtime is closed")
      requests = requests.updated(request.id, request)
      system ! Spawn(request)
    }

    def submitTimer(system: TypedActorSystem[Command], command: Schedule): Unit = synchronized {
      if closed then throw new IllegalStateException("Actor timers are closed")
      system ! command
    }

    def stop(system: TypedActorSystem[Command], id: String): Unit = synchronized {
      if !closed then system ! Stop(id)
    }

    def finished(id: String): Unit = synchronized {
      requests.get(id).foreach(_.finish())
      requests -= id
    }

    def close(): Unit = synchronized { closed = true }

    def systemTerminated(): Unit = synchronized {
      closed = true
      requests.values.foreach(_.finish())
      requests = Map.empty
    }

  private final case class Child(ref: TypedActorRef[?], request: Request[?], acknowledge: () => Unit, stopping: Boolean)

  private def guardian(dispatcher: Dispatcher[IO], gate: Gate): TypedBehavior[Command] =
    TypedBehaviors.setup { context =>
      val guardianRef = context.self
      var children = Map.empty[String, Child]

      def spawn[Msg](request: Request[Msg]): Unit =
        try
          val ref = context.spawn(
            TypedBehaviors.intercept[Msg, Msg](() => new BehaviorInterceptor[Msg, Msg](classOf[Object].asInstanceOf[Class[Msg]]) {
              override def aroundStart(ctx: TypedActorContext[Msg], target: BehaviorInterceptor.PreStartTarget[Msg]): TypedBehavior[Msg] =
                val started = target.start(ctx)
                if TypedBehavior.isAlive(started) then guardianRef ! Ready(request.id)
                started

              override def aroundReceive(ctx: TypedActorContext[Msg], message: Msg, target: BehaviorInterceptor.ReceiveTarget[Msg]): TypedBehavior[Msg] =
                target(ctx, message)
            })(TypedBehaviors.setup(_ => request.behavior(dispatcher))),
            request.id
          )
          context.watchWith(ref, ChildTerminated(request.id))
          children = children.updated(request.id, Child(ref, request, () => { request.ready.trySuccess(ref); () }, false))
        catch
          case NonFatal(error) =>
            request.fail(error)
            gate.finished(request.id)

      TypedBehaviors.withTimers { timers =>
        TypedBehaviors.receiveMessage {
          case Schedule(delay, delivery) =>
            // Each registration has its own key, including repeated deliveries.
            timers.startSingleTimer(new Object, Deliver(delivery), delay)
            TypedBehaviors.same
          case Deliver(delivery) =>
            dispatcher.unsafeRunAndForget(
              delivery.handleErrorWith(error =>
                NebflowLogger.forName("nebflow.actor.timers").warn(s"Timer delivery failed: ${error.getMessage}").void
              )
            )
            TypedBehaviors.same
          case Spawn(request) =>
            spawn(request)
            TypedBehaviors.same
          case Ready(id) =>
            children.get(id).filterNot(_.stopping).foreach(_.acknowledge())
            TypedBehaviors.same
          case Stop(id) =>
            children.get(id).foreach { child =>
              children = children.updated(id, child.copy(stopping = true))
              child.request.fail(new IllegalStateException(s"Native actor $id stopped before readiness"))
              context.stop(child.ref)
            }
            TypedBehaviors.same
          case ChildTerminated(id) =>
            children -= id
            gate.finished(id)
            TypedBehaviors.same
        }
      }
    }

  /** Native termination precedes Dispatcher release, which cancels outstanding deliveries. */
  def resource: Resource[IO, NativeActorRuntime] =
    Dispatcher.parallel[IO](await = false).flatMap { dispatcher =>
      Resource.make {
        IO {
          val gate = new Gate
          val config = ConfigFactory.parseString("""
            pekko.actor.provider = local
            pekko.coordinated-shutdown.run-by-jvm-shutdown-hook = off
            pekko.log-dead-letters-during-shutdown = off
          """).withFallback(ConfigFactory.load())
          val system = TypedActorSystem(guardian(dispatcher, gate), s"stand-native-${UUID.randomUUID()}", config)
          system.whenTerminated.onComplete(_ => gate.systemTerminated())(scala.concurrent.ExecutionContext.parasitic)
          new NativeActorRuntime(system, gate)
        }
      } { runtime =>
        IO(runtime.gate.close()) *> IO(runtime.system.terminate()) *>
          IO.fromFuture(IO(runtime.system.whenTerminated)).void *> IO(runtime.gate.systemTerminated())
      }
    }
