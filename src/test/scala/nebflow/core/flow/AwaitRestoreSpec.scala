package nebflow.core.flow

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import munit.FunSuite

import scala.concurrent.duration.*

/**
  * Regression tests for the awaitRestore fast-path fix: every
  * /api/teams/mounted request used to hard-stall ~3s on an empty home
  * (qa W3-m: networkidle dragged to 3.68s, six http/1.1 sockets hogged).
  *
  * Root cause: the completion Deferred's only producer is a FlowTreeActor
  * setup, and on an empty home no FlowTreeActor is EVER created (the single
  * creation path is LoadTool → getOrCreate) — so the wait always ran the
  * full timeout with nothing to wait for.
  *
  * The wait core (awaitRestoreUsing) is tested over LOCAL state so the
  * registry singleton is never polluted; the end-to-end fast path against
  * the real singleton is covered by the isolated-instance smoke (curl
  * /api/teams/mounted returns in milliseconds).
  */
class AwaitRestoreSpec extends FunSuite:

  private def mkState: (cats.effect.Deferred[IO, Unit], Ref[IO, Boolean]) =
    (cats.effect.Deferred.unsafe[IO, Unit], Ref.unsafe[IO, Boolean](false))

  private def elapsed[A](io: IO[A]): (A, FiniteDuration) =
    val t0 = System.nanoTime()
    val a = io.unsafeRunSync()
    (a, (System.nanoTime() - t0).nanos)

  test("fast-path: no restore ever started returns immediately, not via timeout") {
    val (done, started) = mkState
    val (_, dur) = elapsed(
      FlowTreeRegistry.awaitRestoreUsing(done, started, timeoutMs = 3000L)
    )
    assert(dur < 500.millis, s"expected immediate return, took ${dur.toMillis}ms")
  }

  test("fast-path: restore already completed returns immediately") {
    val (done, started) = mkState
    started.set(true).unsafeRunSync()
    done.complete(()).unsafeRunSync()
    val (_, dur) = elapsed(
      FlowTreeRegistry.awaitRestoreUsing(done, started, timeoutMs = 3000L)
    )
    assert(dur < 500.millis, s"expected immediate return, took ${dur.toMillis}ms")
  }

  test("in-flight restore waits for the completion signal") {
    val (done, started) = mkState
    started.set(true).unsafeRunSync()
    // Complete the signal shortly after the wait begins.
    (IO.sleep(120.millis) *> done.complete(())).unsafeRunAndForget()
    val (woke, dur) = elapsed(
      FlowTreeRegistry.awaitRestoreUsing(done, started, timeoutMs = 5000L)
    )
    assert(dur >= 100.millis, "should have actually waited for the signal")
    assert(dur < 2000.millis, s"should wake on the signal, took ${dur.toMillis}ms")
  }

  test("in-flight restore still bounded by the timeout when the signal never comes") {
    val (done, started) = mkState
    started.set(true).unsafeRunSync() // never completed
    val (_, dur) = elapsed(
      FlowTreeRegistry.awaitRestoreUsing(done, started, timeoutMs = 200L)
    )
    assert(dur >= 180.millis, "should have waited the timeout")
    assert(dur < 1500.millis, s"timeout bound grossly exceeded: ${dur.toMillis}ms")
  }

  test("started flag alone (no completion) still waits — semantics preserved") {
    // The wait must key on started, not on completion alone: a just-spawned
    // actor with restore still running must hold requests (bounded).
    val (done, started) = mkState
    started.set(true).unsafeRunSync()
    val (_, dur) = elapsed(
      FlowTreeRegistry.awaitRestoreUsing(done, started, timeoutMs = 150L)
    )
    assert(dur >= 130.millis, "must wait while restore is genuinely in flight")
  }

  // ⑦ (2026-08-24): initFlowTree now marks restore in flight BEFORE spawn.
  // A WS connect (which previously never created a FlowTreeActor at all)
  // must make /api/teams/mounted WAIT for the restore instead of
  // fast-pathing past it with an empty list — the exact regression this
  // test pins: mark-started happens, then the completion signal arrives,
  // and a concurrent awaitRestore wakes on it.
  test("WS-connect restore: markRestoreStarted → concurrent awaitRestore waits and wakes") {
    val (done, started) = mkState
    // Simulate initFlowTree: mark started, then async restore completes.
    started.set(true).unsafeRunSync()
    (IO.sleep(120.millis) *> done.complete(())).unsafeRunAndForget()
    val (_, dur) = elapsed(
      FlowTreeRegistry.awaitRestoreUsing(done, started, timeoutMs = 5000L)
    )
    assert(dur >= 100.millis, "must have waited for the WS-triggered restore")
    assert(dur < 2000.millis, s"should wake on restore completion, took ${dur.toMillis}ms")
  }
