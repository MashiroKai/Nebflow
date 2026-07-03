package nebflow.actor

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import munit.CatsEffectSuite

import scala.concurrent.duration.*

class ActorSpec extends CatsEffectSuite:

  // ============================================================
  // Basic lifecycle: spawn → send message → receive → stop
  // ============================================================

  test("actor receives and processes a message") {
    val system = ActorSystem("test-device")
    for
      received <- Ref.of[IO, Option[String]](None)
      ref <- system.spawn(
        Behaviors.receiveMessage[String] { msg =>
          received.set(Some(msg)).as(Behaviors.stopped)
        },
        "test-actor"
      )
      _ <- ref ! "hello"
      _ <- IO.sleep(100.millis)
      result <- received.get
      _ <- system.stopAll
    yield assertEquals(result, Some("hello"))
  }

  // ============================================================
  // State machine: behavior chaining carries state
  // ============================================================

  test("actor state machine transitions through behaviors") {
    val system = ActorSystem("test-device")
    sealed trait Cmd
    case class Add(n: Int) extends Cmd
    case class Get(replyTo: ActorRef[Int]) extends Cmd

    def counter(value: Int): Behavior[Cmd] =
      Behaviors.receiveMessage {
        case Add(n) => IO.pure(counter(value + n))
        case Get(r) => (r ! value) *> IO.pure(counter(value))
      }

    for
      ref <- system.spawn(counter(0), "counter")
      _ <- ref ! Add(5)
      _ <- ref ! Add(3)
      result <- ref ? (Get(_), 5.seconds)
      _ <- system.stopAll
    yield assertEquals(result, 8)
  }

  // ============================================================
  // forkTurn + cancelCurrentTurn
  // ============================================================

  test("forkTurn runs IO and cancelCurrentTurn stops it") {
    val system = ActorSystem("test-device")
    sealed trait Cmd
    case object Start extends Cmd
    case object Interrupt extends Cmd
    case class Done(value: String) extends Cmd

    def behavior(completed: Ref[IO, Boolean]): Behavior[Cmd] =
      Behaviors.setup { ctx =>
        IO.pure(Behaviors.receiveMessage[Cmd] {
          case Start =>
            ctx
              .forkTurn(
                IO.sleep(10.seconds) *> completed.set(true) *> (ctx.self ! Done("finished"))
              )
              .as(
                Behaviors.receiveMessage[Cmd] {
                  case Done(_) => IO.pure(Behaviors.stopped)
                  case Interrupt => IO.pure(Behaviors.stopped)
                  case Start => IO.pure(Behaviors.stopped)
                }
              )
          case Interrupt =>
            ctx.cancelCurrentTurn().as(Behaviors.stopped)
          case Done(_) => IO.pure(Behaviors.stopped)
        })
      }

    for
      completed <- Ref.of[IO, Boolean](false)
      ref <- system.spawn(behavior(completed), "fork-test")
      _ <- ref ! Start
      _ <- IO.sleep(50.millis)
      _ <- ref ! Interrupt
      _ <- IO.sleep(100.millis)
      wasCompleted <- completed.get
      _ <- system.stopAll
    yield assert(!wasCompleted, "IO work should have been cancelled before completing")
  }

  // ============================================================
  // Ask pattern: send message with reply-to, get response
  // ============================================================

  test("ask pattern returns reply") {
    val system = ActorSystem("test-device")
    sealed trait Cmd
    case class Ping(replyTo: ActorRef[String]) extends Cmd

    val pong = Behaviors.receiveMessage[Cmd] { case Ping(r) =>
      (r ! "pong") *> IO.pure(Behaviors.stopped)
    }

    for
      ref <- system.spawn(pong, "ping-pong")
      result <- ref ? (Ping(_), 5.seconds)
      _ <- system.stopAll
    yield assertEquals(result, "pong")
  }

  // ============================================================
  // Error handling: actor survives message processing errors
  // ============================================================

  test("actor resumes after error in message processing") {
    val system = ActorSystem("test-device")
    for
      count <- Ref.of[IO, Int](0)
      ref <- system.spawn(
        Behaviors.receiveMessage[String] { msg =>
          if msg == "boom" then IO.raiseError(new RuntimeException("deliberate"))
          else count.update(_ + 1).as(Behaviors.stopped)
        },
        "error-test"
      )
      _ <- ref ! "boom"
      _ <- IO.sleep(50.millis)
      _ <- ref ! "ok"
      _ <- IO.sleep(50.millis)
      finalCount <- count.get
      _ <- system.stopAll
    yield assertEquals(finalCount, 1)
    end for
  }

  // ============================================================
  // Regression: ref ! msg returns IO[Unit] — wrapping in IO(...) silently discards it
  // ============================================================

  test("ref ! msg delivers when used directly as IO[Unit] (not wrapped in IO(...))") {
    val system = ActorSystem("test-device")
    def listener(received: Ref[IO, List[String]]): Behavior[String] =
      Behaviors.receiveMessage[String] { msg =>
        received.update(_ :+ msg).as(listener(received))
      }
    for
      received <- Ref.of[IO, List[String]](Nil)
      ref <- system.spawn(listener(received), "delivery-test")
      // This mirrors the production routeToAgent pattern: a function
      // ActorRef[Msg] => IO[Unit] that must return ref ! msg directly.
      // Writing IO(ref ! msg) instead would trigger Scala value-discarding,
      // producing IO(()) — a no-op that never delivers the message.
      routeToActor: (String => IO[Unit]) = msg => ref ! msg
      _ <- routeToActor("first")
      _ <- routeToActor("second")
      _ <- IO.sleep(100.millis)
      messages <- received.get
      _ <- system.stopAll
    yield assertEquals(messages, List("first", "second"))
  }

end ActorSpec
