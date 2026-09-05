package nebflow.actor

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import scala.concurrent.duration.*

/** Isolation probe (2026-08-30): does a setup-time `ctx.self ! msg` get
  * processed by the message loop? AgentActor relies on this for
  * RecoverPersistedQueues (F2) — this spec proves the mechanism itself. */
class SelfSendSetupSpec extends FunSuite:

  private case object Kick

  private def kickBehavior(seen: cats.effect.Ref[IO, Int]): Behavior[Any] =
    Behaviors.receiveMessage[Any] {
      case Kick => seen.update(_ + 1) *> IO.pure(kickBehavior(seen))
      case _    => IO.pure(kickBehavior(seen))
    }

  test("setup-time self-send is processed by the loop") {
    val system = ActorSystem("self-send-setup-probe")
    try
      val program = for
        seen <- IO.ref(0)
        ref <- system.spawn(
          Behaviors.setup[Any] { ctx =>
            // MUST sequence: a bare `ctx.self ! Kick` statement is built-not-run
            // (2026-08-30 probe — setup-time self-sends were silently dropped).
            (ctx.self ! Kick) *> IO.pure(kickBehavior(seen))
          },
          "kick-probe"
        )
        _ <- IO.sleep(300.millis)
        n <- seen.get
      yield n
      val n = program.unsafeRunSync()
      assertEquals(n, 1, "setup-time self-send must be processed exactly once")
    finally system.stopAll.attempt.void.unsafeRunSync()
  }

  test("post-start self-send is processed (control)") {
    val system = ActorSystem("self-send-post-probe")
    try
      val program = for
        seen <- IO.ref(0)
        ref <- system.spawn(kickBehavior(seen), "kick-post-probe")
        _ <- ref ! Kick
        _ <- IO.sleep(300.millis)
        n <- seen.get
      yield n
      val n = program.unsafeRunSync()
      assertEquals(n, 1, "post-start self-send must be processed")
    finally system.stopAll.attempt.void.unsafeRunSync()
  }

end SelfSendSetupSpec
