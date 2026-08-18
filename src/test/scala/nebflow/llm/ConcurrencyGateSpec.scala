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
 *  - cancel while queued / during RPM wait removes the ghost waiter and
 *    restores the permit (Stop/abort must not leak slots)
 *  - restored permit dispatches the next waiter immediately (not next release)
 *  - tryAcquire is RPM-aware (persistence decision must not skip queueing)
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

  test("queued acquire waits indefinitely — no QueueTimeout (user #296 追加)") {
    // gate.acquire now waits indefinitely (no queue timeout). Provider failure
    // is caught by LLM request timeout (first token 90s / inactivity 60s),
    // not the gate. Verify: a queued acquire stays queued until release.
    val g = gate(max = 1, timeout = 100.millis) // timeout is now ignored
    for
      p1 <- g.acquire
      f2 <- g.acquire.start // queues behind p1
      // f2 should still be waiting after 200ms (timeout was 100ms but no longer fires)
      stillWaiting <- IO.defer(f2.join.timeoutTo(200.millis, IO.pure(None)))
      _ <- IO(assert(stillWaiting == None, "queued acquire must NOT time out — waits indefinitely"))
      _ <- p1.release // now f2 can proceed
      p2 <- f2.joinWithNever
      _ <- p2.release
    yield ()
  }

  test("RPM window throttles beyond limit and recovers after window slides") {
    val g = gate(max = 5, rpm = Some(1), window = 200.millis)
    for
      p1 <- g.acquire
      _ <- p1.release
      // Second acquire within the 200ms window must wait ~200ms. The exact
      // sleep (one wakeup until the oldest entry slides out) converges to the
      // window boundary — allow ~5ms slack for the millis-truncated window
      // math and scheduler jitter (busy-poll previously overshot by design).
      start <- IO.monotonic
      p2 <- g.acquire
      elapsed <- IO.monotonic.map(_ - start)
      _ <- p2.release
      _ <- IO(assert(elapsed >= 195.millis, s"RPM throttled acquire should wait ~ window, got $elapsed"))
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

  test("cancelled queued acquires do not leak permits (cleanup still works without timeout)") {
    // Previously tested via repeated queue timeouts. Now that timeout is removed,
    // we test the same cleanup path (no ghost waiters / permit leaks) via cancellation.
    val g = gate(max = 1, timeout = 50.millis) // timeout ignored
    for
      p1 <- g.acquire
      // Five waiters queued, all cancelled while waiting.
      fibers <- (1 to 5).toList.traverse(_ => g.acquire.start)
      _ <- IO.sleep(20.millis) // let all fibers enqueue
      _ <- fibers.traverse_(f => f.cancel)
      _ <- IO.sleep(50.millis) // let cancel cleanup settle
      _ <- p1.release
      // Capacity must be intact: an immediate acquire succeeds (no ghost
      // waiters holding the slot, no leaked decrements).
      p2 <- g.acquire.timeoutTo(200.millis, IO.raiseError(new RuntimeException("gate stuck after cancellations")))
      _ <- p2.release
    yield ()
  }

  test("cancel while queued removes the ghost waiter and restores the permit") {
    val g = gate(max = 1)
    for
      p1 <- g.acquire // holds the only permit
      f2 <- g.acquire.start // queues behind p1
      _ <- IO.sleep(50.millis) // let f2 actually enqueue
      _ <- f2.cancel // Stop/abort while queued
      _ <- IO.sleep(50.millis) // let the cancel cleanup settle
      // Without the cancel path f2's ghost Deferred would stay head of the
      // queue and swallow p1's release — f3 must get the permit instead.
      f3 <- g.acquire.start
      _ <- IO.sleep(50.millis) // let f3 enqueue behind the (removed) ghost
      _ <- p1.release
      p3 <- f3.joinWithNever.timeoutTo(300.millis, IO.raiseError(new RuntimeException("gate stuck after cancel")))
      _ <- p3.release
      // Clean state: no ghost waiter consumed the release dispatch.
      ok <- g.tryAcquire
      _ <- IO(assert(ok, "gate must return to a clean state after the cancelled waiter"))
    yield ()
  }

  test("cancel during RPM wait restores the held slot and dispatches the next waiter") {
    val g = gate(max = 1, rpm = Some(1), window = 200.millis, timeout = 10.seconds)
    for
      p1 <- g.acquire // permit + records the only RPM window slot
      _ <- p1.release
      f2 <- g.acquire.start // grabs the permit, then RPM-waits ~200ms
      _ <- IO.sleep(50.millis) // f2 now holds the slot inside waitForWindow
      f3 <- g.acquire.start // queued behind f2's slot
      _ <- f2.cancel // abort during the RPM wait -> slot must be restored
      // The restored slot must reach f3 NOW (dispatch inside cleanup), not on
      // the next release. f3 completes once the RPM window slides (~200ms).
      p3 <- f3.joinWithNever.timeoutTo(2.seconds, IO.raiseError(new RuntimeException("slot not dispatched after cancel")))
      _ <- p3.release
    yield ()
  }

  test("tryAcquire accounts for the RPM window, not just free permits") {
    val g = gate(max = 2, rpm = Some(1), window = 200.millis)
    for
      p1 <- g.acquire // records the only RPM slot (window full)
      before <- g.tryAcquire // a permit is free, but the window is full
      _ <- IO(assert(!before, "tryAcquire must be false while the RPM window is full"))
      _ <- IO.sleep(250.millis) // window slides past p1's timestamp
      after <- g.tryAcquire
      _ <- IO(assert(after, "tryAcquire must be true once the window has capacity"))
      _ <- p1.release
    yield ()
  }
