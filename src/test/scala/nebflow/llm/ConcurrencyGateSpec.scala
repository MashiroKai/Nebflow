package nebflow.llm

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * ConcurrencyGate unit tests (P0 API 并发管理, design §4.1-4.2, §7).
 *
 * Coverage:
 *  - concurrency bound: N in flight max; extra callers queue FIFO
 *  - FIFO ordering under saturation
 *  - queue timeout → QueueTimeout (Transient, not a hard failure)
 *  - RPM sliding window (short window via test constructor)
 *  - unlimited concurrency (maxConcurrency = 0) still honors RPM
 *  - release re-dispatches a waiting permit
 */
class ConcurrencyGateSpec extends CatsEffectSuite:

  private def gate(
    max: Int,
    rpm: Option[Int] = None,
    timeout: FiniteDuration = 1.second,
    window: FiniteDuration = 1.second
  ): ConcurrencyGate =
    new ConcurrencyGate("test-provider", max, rpm, timeout, window)

  test("limits concurrent acquisitions to maxConcurrency") {
    val g = gate(max = 1)
    for
      p1 <- g.acquire
      // Second acquire must queue (no immediate permit) — start it concurrently
      // and verify it does NOT complete until p1 is released.
      secondFiber <- g.acquire.start
      secondDone <- IO.defer(secondFiber.join.timeoutTo(150.millis, IO.pure(None)))
      _ <- IO {
        // 150ms timeout fired = still queued, not granted
        assertEquals(secondDone, None)
      }
      _ <- p1.release
      second <- secondFiber.joinWithNever
      _ <- second.release
    yield ()
  }

  test("FIFO order: permits granted in arrival order") {
    val g = gate(max = 1)
    for
      p0 <- g.acquire
      f1 <- g.acquire.start
      f2 <- g.acquire.start
      f3 <- g.acquire.start
      _ <- p0.release
      p1 <- f1.joinWithNever
      // Only p1 should be granted after p0's release — p2/p3 still queued.
      p2StillQueued <- f2.join.timeoutTo(150.millis, IO.pure(None))
      _ <- IO(assertEquals(p2StillQueued, None))
      _ <- p1.release
      p2 <- f2.joinWithNever
      _ <- p2.release
      p3 <- f3.joinWithNever
      _ <- p3.release
    yield ()
  }

  test("times out while queued with QueueTimeout (Transient)") {
    val g = gate(max = 1, timeout = 100.millis)
    for
      p1 <- g.acquire
      secondResult <- g.acquire.attempt // queues, then times out
      _ <- IO {
        assert(secondResult.isLeft, "second acquire should time out")
        assert(secondResult.swap.toOption.get.isInstanceOf[QueueTimeout], "error type must be QueueTimeout")
      }
      _ <- p1.release
      // After timeout, the gate must not hand the ghost waiter a permit:
      // a fresh acquire succeeds immediately.
      p2 <- g.acquire.timeoutTo(150.millis, IO.raiseError(new RuntimeException("gate stuck")))
      _ <- p2.release
    yield ()
  }

  test("RPM window throttles beyond limit and recovers after window slides") {
    val g = gate(max = 5, rpm = Some(1), window = 200.millis)
    for
      p1 <- g.acquire
      _ <- p1.release
      // Second acquire within the 200ms window must wait ~200ms.
      start <- IO.monotonic
      p2 <- g.acquire
      elapsed <- IO.monotonic.map(_ - start)
      _ <- p2.release
      _ <- IO(assert(elapsed >= 200.millis, s"RPM throttled acquire should wait >= window, got $elapsed"))
    yield ()
  }

  test("unlimited concurrency (0) skips the queue but honors RPM") {
    val g = gate(max = 0, rpm = Some(2), window = 500.millis)
    for
      p1 <- g.acquire
      p2 <- g.acquire // both immediate (unlimited concurrency)
      // Third must wait for the RPM window.
      thirdFiber <- g.acquire.start
      thirdStillWaiting <- IO.defer(thirdFiber.join.timeoutTo(150.millis, IO.pure(None)))
      _ <- IO(assertEquals(thirdStillWaiting, None, "3rd within window should be RPM-throttled"))
      _ <- p1.release
      _ <- p2.release
      p3 <- thirdFiber.joinWithNever // after window slides
      _ <- p3.release
    yield ()
  }

  test("release re-dispatches a queued waiter") {
    val g = gate(max = 1)
    for
      p1 <- g.acquire
      f2 <- g.acquire.start
      _ <- IO.sleep(50.millis)
      _ <- p1.release
      p2 <- f2.joinWithNever
      _ <- p2.release
    yield ()
  }

  test("repeated queue timeouts do not leak permits or ghost waiters") {
    val g = gate(max = 1, timeout = 50.millis)
    for
      p1 <- g.acquire
      // Five waiters all time out while p1 holds the only permit.
      _ <- (1 to 5).toList.traverse_(_ => g.acquire.attempt.map(_.left.map(_.getClass)))
      _ <- IO.sleep(20.millis) // let all onError cleanup settle
      _ <- p1.release
      // Capacity must be intact: an immediate acquire succeeds (no ghost
      // waiters holding the slot, no leaked decrements).
      p2 <- g.acquire.timeoutTo(200.millis, IO.raiseError(new RuntimeException("gate stuck after timeouts")))
      _ <- p2.release
    yield ()
  }
