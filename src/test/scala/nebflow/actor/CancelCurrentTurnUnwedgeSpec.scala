package nebflow.actor

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * Hard-recovery P3 (2026-09-07 取证 §1.4): cancelCurrentTurn must be
 * fire-and-forget. The pre-fix implementation ran `traverse_(_.cancel)` —
 * CE3 `Fiber.cancel` WAITS for cancellation to complete, and for a fiber
 * parked on an uncancellable wait (production: the JDK HttpClient body read
 * over a half-open TCP connection, 22 minutes) that wait never returns. The
 * Interrupt mailbox handler calling it therefore wedged the whole actor:
 * after the interrupt, later user messages never even queued.
 *
 * Counterfactual: an IO.uncancelable(IO.never) fiber reproduces the parked
 * uncancellable read exactly. cancelCurrentTurn must return within a tight
 * bound, and the tracking map must be cleared (the forked cancel keeps
 * running in the background until the transport abort unwedges it).
 */
class CancelCurrentTurnUnwedgeSpec extends CatsEffectSuite:

  test("cancelCurrentTurn returns promptly despite an uncancellable fiber (P3)") {
    val system = ActorSystem("cancel-unwedge-test")
    for
      ref <- system.spawn(Behaviors.receiveMessage[Command](_ => IO.pure(Behaviors.stopped)), "noop")
      fibers <- Ref.of[IO, Map[String, cats.effect.Fiber[IO, Throwable, Unit]]](Map.empty)
      children <- Ref.of[IO, List[ActorRef[?]]](Nil)
      ctx = LocalActorContext(
        self = ref,
        system = system,
        log = nebflow.core.NebflowLogger.forName("nebflow.actor"),
        activeTurnFibers = fibers,
        childrenRef = children
      )
      // The wedge: an uncancellable never-completing turn fiber (parked read).
      _ <- ctx.forkTurn(IO.uncancelable(_ => IO.never): IO[Unit])
      _ <- IO.sleep(100.millis)
      // The pre-fix code hung HERE forever. Bound tightly: 2s is >10x the
      // expected sub-100ms return; a regression to the blocking cancel
      // trips the timeout and fails the test instead of hanging the suite.
      returned <- ctx.cancelCurrentTurn().timeout(2.seconds).attempt
      cleared <- fibers.get
      _ <- system.stopAll
    yield
      assert(returned.isRight, s"cancelCurrentTurn must return promptly, got $returned")
      assertEquals(cleared.size, 0, "tracking map must be cleared immediately")
  }

  private sealed trait Command
  private case object Noop extends Command
end CancelCurrentTurnUnwedgeSpec
