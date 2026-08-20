package nebflow.llm

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import munit.CatsEffectSuite

import scala.jdk.CollectionConverters.*
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
 *  - gate-wedge P0-1: bounded queue wait — QueueTimeout on saturation, with
 *    permit accounting fully restored afterwards (no wedge residue)
 *  - gate-wedge P0-2: cancellation storms (wedge repro) leave permits at full
 *    capacity, zero tracer-fiber residue, and future-stamped RPM entries
 *    (wall clock stepped back) cannot park a holder
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

  test("P0-1: saturated gate raises QueueTimeout after queueTimeout — and accounting is intact afterwards") {
    // gate-wedge: #296's infinite wait is reverted. A queued acquire must fail
    // with QueueTimeout (Transient → fallback next provider) once the timeout
    // elapses, and the gate must be fully usable afterwards (the timed-out
    // waiter's slot accounting is cleaned up on the error path).
    val g = gate(max = 1, timeout = 150.millis)
    for
      p1 <- g.acquire
      outcome <- g.acquire.attempt
      _ <- p1.release
      _ <- IO(
        outcome match
          case Left(e: QueueTimeout) =>
            assert(e.providerId == "test-provider", s"wrong provider: ${e.getMessage}")
          case other => fail(s"expected QueueTimeout, got $other")
      )
      // After timeout + release the gate must accept fresh acquires instantly.
      p2 <- g.acquire.timeoutTo(500.millis, IO.raiseError(new RuntimeException("gate wedged after QueueTimeout")))
      _ <- p2.release
      ok <- g.tryAcquire
      _ <- IO(assert(ok, "gate must return to full capacity after a queue timeout"))
    yield ()
  }

  test("P0-1: queue timeout during RPM wait releases the held permit (no holder stranded)") {
    // The timeout bounds queue wait AND rpm-wait combined. A granted request
    // stuck in a full RPM window times out and its permit is restored by the
    // timeout cleanup. Window (300ms) chosen so the SECOND acquire can prove
    // it re-got the permit and rides the rpm-wait to completion (~300ms mark)
    // — with a leaked permit it would sit queued until its own timeout.
    val g = gate(max = 1, rpm = Some(1), window = 300.millis, timeout = 200.millis)
    for
      p1 <- g.acquire // permit + records the only RPM slot at t≈0
      _ <- p1.release
      // f2 grabs the permit (rpm-wait ~300ms) — the 200ms timeout fires first.
      outcome <- g.acquire.attempt
      _ <- IO(
        outcome match
          case Left(_: QueueTimeout) => ()
          case other => fail(s"expected QueueTimeout, got $other")
      )
      // Permit restored by the timeout cleanup: the next acquire is granted
      // again and completes as soon as the window slides (~100ms later),
      // well inside its own 200ms timeout.
      p3 <- g.acquire.timeoutTo(2.seconds, IO.raiseError(new RuntimeException("permit not restored after rpm-wait timeout")))
      _ <- p3.release
    yield ()
  }

  test("P0-2 wedge repro: three cancelled queued acquires leave permits at full capacity, zero tracer residue") {
    // The 8.5h incident: granted-but-cancelled waiters leaked permits until
    // permits=0 forever, and their ghost tracers printed "granted(rpm-wait)"
    // every 5s for hours. Fix invariants: after a cancellation storm the gate
    // must hand out ALL permits immediately and no tracer fiber may survive.
    val g = gate(max = 1, timeout = 10.seconds)
    for
      p1 <- g.acquire
      fibers <- (1 to 3).toList.traverse(_ => g.acquire.start)
      _ <- IO.sleep(50.millis) // all three enqueued and tracing
      _ <- fibers.traverse_(f => f.cancel) // Stop/abort storm while queued
      _ <- IO.sleep(100.millis) // let cleanups settle
      tracersAfterCancel <- g.tracersRef.get
      _ <- p1.release
      // Capacity intact: an immediate acquire succeeds with no ghost waiters.
      p2 <- g.acquire.timeoutTo(500.millis, IO.raiseError(new RuntimeException("gate wedged after cancel storm")))
      _ <- p2.release
      tracersFinal <- g.tracersRef.get
      ok <- g.tryAcquire
    yield
      assertEquals(tracersAfterCancel, 0, "cancelled waiters must cancel their tracer fibers (E8 ghost-log leak)")
      assertEquals(tracersFinal, 0)
      assert(ok, "gate must return to full capacity after the cancellation storm")
  }

  test("P0-2: wall clock steps backwards — future-stamped RPM entries do not wedge the holder") {
    // Incident hypothesis 2: after a host sleep + NTP correction the wall
    // clock jumps back, making already-recorded window timestamps "future"
    // relative to now — a naive `now - t < windowMs` filter keeps them
    // forever (negative age passes!) and the holder sleeps (future - now) =
    // hours. The hardened filter drops future-stamped entries and caps each
    // sleep at one window, so the second acquire completes fast instead of
    // parking for 10 minutes (the injected step-back magnitude).
    for
      clockRef <- IO.ref(Option.empty[Long])
      // Stateful clock: first read returns real time, later reads whatever
      // the test injects (the step-back).
      clock = IO.realTime.map(_.toMillis).flatMap { t =>
        clockRef.get.flatMap {
          case Some(fixed) => IO.pure(fixed)
          case None        => clockRef.set(Some(t)).as(t)
        }
      }
      g = new ConcurrencyGate(
        "clock-test",
        maxConcurrency = 1,
        rpm = Some(1),
        queueTimeout = 10.seconds,
        rpmWindow = 1.second,
        clockMs = clock,
        tracerPeriod = 5.seconds
      )
      p1 <- g.acquire // stamps the only RPM slot with real (pre-step) time
      _ <- p1.release
      _ <- IO.realTime.map(_.toMillis).flatMap(real => clockRef.set(Some(real - 10 * 60 * 1000L)))
      start <- IO.monotonic
      p2 <- g.acquire.timeoutTo(
        2.seconds,
        IO.raiseError(new RuntimeException("future-stamped entries wedged the holder"))
      )
      elapsed <- IO.monotonic.map(_ - start)
      _ <- p2.release
    yield assert(elapsed < 2.seconds, s"acquire must drop future-stamped entries and complete fast, took $elapsed")
  }

  test("#22 tracer wiring: fast grant undelayed; queued waiter completes on release despite active tracer") {
    // acquire 内嵌的等待留痕 tracer（5s 周期）不能改语义：快授权不受拖延、
    // 排队者在 release 后正常获得许可。tracer fiber 的 start/cancel 生命周期
    // 由 d.get 驱动，绝不 race/timeout 许可等待本身。
    val g = gate(max = 1)
    for
      t0 <- IO.monotonic
      p1 <- g.acquire
      elapsed <- IO.monotonic.map(_ - t0)
      _ <- IO(assert(elapsed < 1.second, s"fast grant must not be delayed by the tracer, took $elapsed"))
      f2 <- g.acquire.start
      _ <- IO.sleep(50.millis)
      _ <- p1.release
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

  test("P2: rpm-wait tracer line carries window=N and oldestAge (gate-wedge acceptance)") {
    // The incident forensics were blinded by a tracer that printed only
    // permits/waiters/thisWaiter — one window metric would have separated
    // hypothesis 1 (leak) from hypothesis 2 (rpm pinned) in seconds. The
    // tracer line must now contain window=N and oldestAge=Xs.
    val logbackLogger = org.slf4j.LoggerFactory.getLogger("nebflow.llm.gate")
      .asInstanceOf[ch.qos.logback.classic.Logger]
    val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]()
    appender.start()
    logbackLogger.addAppender(appender)
    val g = new ConcurrencyGate(
      "trace-test",
      maxConcurrency = 1,
      rpm = Some(1),
      queueTimeout = 10.seconds,
      rpmWindow = 3.seconds,
      clockMs = IO.realTime.map(_.toMillis),
      tracerPeriod = 150.millis
    )
    for
      p1 <- g.acquire // stamps the only RPM slot
      _ <- p1.release
      f2 <- g.acquire.start // granted the permit, rpm-waits ~3s, tracer fires meanwhile
      _ <- IO.sleep(500.millis) // at least 2 tracer periods elapse
      _ <- f2.cancel // end the wait cleanly (timeout is 10s)
      _ <- IO.sleep(100.millis)
      lines = appender.list.asScala.map(_.getFormattedMessage).toList
      _ <- IO(logbackLogger.detachAppender(appender))
      waitingLines = lines.filter(_.contains("acquire waiting"))
    yield
      assert(waitingLines.nonEmpty, s"expected tracer lines while rpm-waiting, got: $lines")
      val line = waitingLines.head
      assert(line.contains("window=1"), s"tracer must carry window length: $line")
      assert(line.contains("oldestAge="), s"tracer must carry oldest entry age: $line")
      assert(line.contains("thisWaiter=granted(rpm-wait)"), s"rpm-waiting holder must be labeled: $line")
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
