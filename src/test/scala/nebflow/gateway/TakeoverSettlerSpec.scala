package nebflow.gateway

import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.Queue
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.core.browser.BrowserScreencast
import scala.concurrent.duration.*

class TakeoverSettlerSpec extends CatsEffectSuite:
  import TakeoverSettler.*
  private case class Counts(release: Int = 0, lease: Int = 0, record: Int = 0, normal: Int = 0)
  private case class Fixture(s: Settler[IO], stream: BrowserScreencast, counts: Ref[IO, Counts], queue: Queue[IO, Json], sending: Deferred[IO, Unit]):
    def active: Active = ("id", "session", stream, () => ())

  private def fixture(failNormal: Boolean = false, full: Boolean = false): IO[Fixture] = for
    counts <- Ref.of[IO, Counts](Counts())
    queue <- Queue.bounded[IO, Json](1)
    sending <- Deferred[IO, Unit]
    _ <- if full then queue.offer(Json.Null) else IO.unit
    stream <- IO(new BrowserScreencast((_, _) => ()))
    s = new Settler[IO](
      _ => counts.update(c => c.copy(release = c.release + 1)),
      _ => counts.update(c => c.copy(lease = c.lease + 1)),
      _ => counts.update(c => c.copy(record = c.record + 1)),
      (_, _) => counts.update(c => c.copy(normal = c.normal + 1, lease = c.lease + 1)) *> (if failNormal then IO.raiseError(new RuntimeException("normal failure")) else IO.unit),
      j => sending.complete(()).void *> queue.offer(j), 5.seconds, (_, _) => IO.unit)
  yield Fixture(s, stream, counts, queue, sending)

  test("failure before registration settles immediately") {
    TestControl.executeEmbed(for
      f <- fixture()
      _ <- IO(f.s.markFailed(f.stream))
      _ <- f.s.register(f.active)
      c <- f.counts.get
      frame <- f.queue.tryTake
      _ = assertEquals(c, Counts(1, 1, 1, 0))
      _ = assert(frame.exists(_.hcursor.get[String]("error").toOption.contains("画面流故障，接管已停止")))
    yield ())
  }

  test("tick settles failure and delivers frame to a waiting consumer") {
    TestControl.executeEmbed(for
      f <- fixture(full = true)
      _ <- f.s.register(f.active)
      _ <- IO(f.s.markFailed(f.stream))
      fiber <- f.s.tick.start
      _ <- f.sending.get.timeoutTo(1.second, IO.unit)
      _ <- IO.cede
      _ <- f.queue.take
      frame <- f.queue.take.timeoutTo(1.second, IO.pure(Json.Null))
      _ <- fiber.joinWithNever
      c <- f.counts.get
      _ = assertEquals(c, Counts(1, 1, 1, 0))
      _ = assertEquals(frame.hcursor.get[String]("error").toOption, Some("画面流故障，接管已停止"))
    yield ())
  }

  test("normal finish owns settlement despite subsequent failures") {
    TestControl.executeEmbed(for
      f <- fixture()
      _ <- f.s.register(f.active)
      result <- f.s.finish
      _ <- IO(f.s.markFailed(f.stream))
      _ <- List.fill(3)(f.s.tick).sequence_
      empty <- f.s.finish
      c <- f.counts.get
      _ = assertEquals(result, Outcome.SettledNormally)
      _ = assertEquals(empty, Outcome.NothingToSettle)
      _ = assertEquals(c, Counts(1, 1, 0, 1))
    yield ())
  }

  test("finalizer handles failure without sending and handles normal close") {
    TestControl.executeEmbed(for
      f <- fixture()
      _ <- f.s.register(f.active) *> IO(f.s.markFailed(f.stream)) *> f.s.finalizer
      c <- f.counts.get
      frame <- f.queue.tryTake
      n <- fixture()
      _ <- n.s.register(n.active) *> n.s.finalizer
      nc <- n.counts.get
      _ = assertEquals(c, Counts(1, 1, 1, 0))
      _ = assertEquals(frame, None)
      _ = assertEquals(nc, Counts(1, 1, 0, 1))
    yield ())
  }

  test("normal error propagates but resources are released") {
    TestControl.executeEmbed(for
      f <- fixture(failNormal = true)
      _ <- f.s.register(f.active)
      result <- f.s.finish.attempt
      c <- f.counts.get
      _ = assert(result.isLeft)
      _ = assertEquals(c.release, 1)
    yield ())
  }

  test("full outbound queue times out after settling") {
    TestControl.executeEmbed(for
      f <- fixture(full = true)
      _ <- f.s.register(f.active) *> IO(f.s.markFailed(f.stream))
      result <- f.s.finish
      c <- f.counts.get
      _ = assertEquals(result, Outcome.SettledAsFailure)
      _ = assertEquals(c, Counts(1, 1, 1, 0))
    yield ())
  }

  test("cancellation during resource release cannot interrupt settlement") {
    TestControl.executeEmbed(for
      entered <- Deferred[IO, Unit]
      resume <- Deferred[IO, Unit]
      count <- Ref.of[IO, Counts](Counts())
      stream <- IO(new BrowserScreencast((_, _) => ()))
      s = new Settler[IO](_ => entered.complete(()) *> resume.get *> count.update(c => c.copy(release = c.release + 1)),
        _ => count.update(c => c.copy(lease = c.lease + 1)),
        _ => count.update(c => c.copy(record = c.record + 1)), (_, _) => IO.unit, _ => IO.unit, 5.seconds, (_, _) => IO.unit)
      _ <- s.register(("id", "session", stream, () => ())) *> IO(s.markFailed(stream))
      fiber <- s.finish.start
      _ <- entered.get
      cancel <- fiber.cancel.start
      _ <- s.finalizer
      _ <- resume.complete(())
      _ <- cancel.joinWithNever
      n <- count.get
      _ = assertEquals(n, Counts(1, 1, 1, 0))
    yield ())
  }

  test("frame pump settles failure at the next 200ms tick") {
    TestControl.executeEmbed(for
      f <- fixture()
      _ <- f.s.register(f.active)
      ready <- Deferred[IO, Unit]
      pump <- (fs2.Stream.eval(ready.complete(())) >> framePump(f.s, 200.millis)(_ => IO.pure(None: Option[String]))).compile.drain.start
      _ <- ready.get
      _ <- IO.cede
      _ <- IO(f.s.markFailed(f.stream))
      frame <- f.queue.take.timeoutTo(201.millis, IO.pure(Json.Null))
      c <- f.counts.get
      _ <- pump.cancel
      _ = assertEquals(c, Counts(1, 1, 1, 0))
      _ = assertEquals(frame.hcursor.get[String]("error").toOption, Some("画面流故障，接管已停止"))
    yield ())
  }

  test("simultaneous tick and finish settle a failed stream only once") {
    TestControl.executeEmbed(for
      f <- fixture()
      gate <- Deferred[IO, Unit]
      tickReady <- Deferred[IO, Unit]
      finishReady <- Deferred[IO, Unit]
      _ <- f.s.register(f.active) *> IO(f.s.markFailed(f.stream))
      tick <- (tickReady.complete(()) *> gate.get *> f.s.tick).start
      finish <- (finishReady.complete(()) *> gate.get *> f.s.finish).start
      _ <- tickReady.get *> finishReady.get *> gate.complete(())
      _ <- tick.joinWithNever
      outcome <- finish.joinWithNever
      c <- f.counts.get
      frame <- f.queue.tryTake
      extra <- f.queue.tryTake
      _ = assert(Set(Outcome.NothingToSettle, Outcome.SettledAsFailure).contains(outcome))
      _ = assertEquals(c, Counts(1, 1, 1, 0))
      _ = assert(frame.nonEmpty)
      _ = assertEquals(extra, None)
    yield ())
  }
