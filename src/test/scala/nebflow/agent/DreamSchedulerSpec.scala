package nebflow.agent

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.*

/**
 * Unit tests for the DreamScheduler state machine (IO-based).
 *
 * Tests verify the core invariant: entries arriving while a Dream cycle is
 * in progress are buffered and not lost. The scheduler transitions between
 * idle → dreaming → idle states correctly.
 *
 * The scheduler runs as a background IO fiber. Tests interact via
 * submitEntry/signalComplete/signalTimeout and use Thread.sleep for timing.
 */
class DreamSchedulerSpec extends munit.FunSuite:

  // ============================================================
  // Test fixtures
  // ============================================================

  private case class TriggerCall(
    entries: List[DreamCommand.ProcessEntry],
    isFullCycle: Boolean
  )

  private class FakeHooks(val triggerResult: Boolean = true) extends DreamScheduler.Hooks:
    val triggerCalls = ListBuffer.empty[TriggerCall]
    var stopCalls = 0
    var touchCalls = 0

    def trigger(entries: List[DreamCommand.ProcessEntry], isFullCycle: Boolean): Boolean =
      triggerCalls += TriggerCall(entries, isFullCycle)
      triggerResult

    def stopDreamAgent(): Unit =
      stopCalls += 1

    def touchLastDreamTime(): Unit =
      touchCalls += 1
  end FakeHooks

  private val Debounce = 50.millis
  private val FullCycle = 999.hours
  private val Timeout = 999.hours

  private def mkEntry(content: String): DreamCommand.ProcessEntry =
    DreamCommand.ProcessEntry("user", content, None, "test", None)

  /** Create and start a scheduler with the given hooks and config. */
  private def mkScheduler(
    hooks: DreamScheduler.Hooks,
    debounce: FiniteDuration = Debounce,
    fullCycle: FiniteDuration = FullCycle,
    timeout: FiniteDuration = Timeout
  ): DreamScheduler =
    val s = new DreamScheduler(hooks, debounce, fullCycle, timeout)
    dispatcher.unsafeRunAndForget(s.start)
    s

  private val dispatcher = Dispatcher.parallel[IO].allocated.unsafeRunSync()._1
  private given Dispatcher[IO] = dispatcher

  // ============================================================
  // Tests: idle state
  // ============================================================

  test("idle: single entry triggers after debounce") {
    val hooks = FakeHooks()
    val scheduler = mkScheduler(hooks)

    scheduler.submitEntry(mkEntry("e1")).unsafeRunSync()
    assertEquals(hooks.triggerCalls.size, 0, "should not trigger immediately")

    Thread.sleep(Debounce.toMillis + 60)
    assertEquals(hooks.triggerCalls.size, 1, "should trigger after debounce")
    assertEquals(hooks.triggerCalls.head.entries.map(_.content), List("e1"))
    assertEquals(hooks.triggerCalls.head.isFullCycle, false)

    scheduler.shutdown.unsafeRunSync()
  }

  test("idle: multiple entries batched before debounce") {
    val hooks = FakeHooks()
    val scheduler = mkScheduler(hooks)

    scheduler.submitEntry(mkEntry("e1")).unsafeRunSync()
    scheduler.submitEntry(mkEntry("e2")).unsafeRunSync()
    scheduler.submitEntry(mkEntry("e3")).unsafeRunSync()
    assertEquals(hooks.triggerCalls.size, 0)

    Thread.sleep(Debounce.toMillis + 60)

    assertEquals(hooks.triggerCalls.size, 1)
    assertEquals(hooks.triggerCalls.head.entries.map(_.content), List("e1", "e2", "e3"))

    scheduler.shutdown.unsafeRunSync()
  }

  test("idle: debounce only starts on first entry, not subsequent") {
    val hooks = FakeHooks()
    val scheduler = mkScheduler(hooks)

    scheduler.submitEntry(mkEntry("e1")).unsafeRunSync()
    Thread.sleep(Debounce.toMillis / 2)
    scheduler.submitEntry(mkEntry("e2")).unsafeRunSync() // should NOT restart debounce

    // Total sleep since e1 ≈ debounce → should have fired
    Thread.sleep(Debounce.toMillis / 2 + 60)

    assertEquals(hooks.triggerCalls.size, 1)
    assertEquals(hooks.triggerCalls.head.entries.map(_.content), List("e1", "e2"))

    scheduler.shutdown.unsafeRunSync()
  }

  test("idle: stale DreamComplete is ignored") {
    val hooks = FakeHooks()
    val scheduler = mkScheduler(hooks)

    scheduler.signalComplete.unsafeRunSync()
    scheduler.signalTimeout.unsafeRunSync()
    Thread.sleep(50)
    assertEquals(hooks.triggerCalls.size, 0)
    assertEquals(hooks.stopCalls, 0)

    scheduler.shutdown.unsafeRunSync()
  }

  // ============================================================
  // Tests: dreaming state — THE CORE FIX
  // ============================================================

  test("dreaming: entry arriving during Dream is buffered, not lost") {
    val hooks = FakeHooks()
    val scheduler = mkScheduler(hooks)

    // Phase 1: trigger Dream with e1
    scheduler.submitEntry(mkEntry("e1")).unsafeRunSync()
    Thread.sleep(Debounce.toMillis + 60)
    assertEquals(hooks.triggerCalls.size, 1, "first Dream should trigger")
    assertEquals(hooks.triggerCalls.last.entries.map(_.content), List("e1"))

    // Phase 2: while Dream is running, new entry arrives
    scheduler.submitEntry(mkEntry("e2")).unsafeRunSync()
    Thread.sleep(50)
    assertEquals(hooks.triggerCalls.size, 1, "should not trigger while dreaming")

    // Phase 3: Dream completes → e2 should be in the next trigger
    scheduler.signalComplete.unsafeRunSync()
    Thread.sleep(50)
    assertEquals(hooks.triggerCalls.size, 2, "second Dream should trigger after completion")
    assertEquals(hooks.triggerCalls.last.entries.map(_.content), List("e2"), "e2 should be buffered and processed")

    scheduler.shutdown.unsafeRunSync()
  }

  test("dreaming: multiple entries during Dream are all buffered") {
    val hooks = FakeHooks()
    val scheduler = mkScheduler(hooks)

    scheduler.submitEntry(mkEntry("initial")).unsafeRunSync()
    Thread.sleep(Debounce.toMillis + 60)
    assertEquals(hooks.triggerCalls.size, 1)

    scheduler.submitEntry(mkEntry("a")).unsafeRunSync()
    scheduler.submitEntry(mkEntry("b")).unsafeRunSync()
    scheduler.submitEntry(mkEntry("c")).unsafeRunSync()
    Thread.sleep(50)

    scheduler.signalComplete.unsafeRunSync()
    Thread.sleep(50)
    assertEquals(hooks.triggerCalls.size, 2)
    assertEquals(hooks.triggerCalls.last.entries.map(_.content), List("a", "b", "c"))

    scheduler.shutdown.unsafeRunSync()
  }

  test("dreaming: DreamComplete with empty buffer returns to idle") {
    val hooks = FakeHooks()
    val scheduler = mkScheduler(hooks)

    scheduler.submitEntry(mkEntry("solo")).unsafeRunSync()
    Thread.sleep(Debounce.toMillis + 60)
    assertEquals(hooks.triggerCalls.size, 1)

    scheduler.signalComplete.unsafeRunSync()
    Thread.sleep(50)
    assertEquals(hooks.triggerCalls.size, 1, "should not trigger again with no buffered entries")
    assertEquals(hooks.stopCalls, 1, "should stop Dream agent")

    // Now in idle — new entry should trigger normally
    scheduler.submitEntry(mkEntry("next")).unsafeRunSync()
    Thread.sleep(Debounce.toMillis + 60)
    assertEquals(hooks.triggerCalls.size, 2)
    assertEquals(hooks.triggerCalls.last.entries.map(_.content), List("next"))

    scheduler.shutdown.unsafeRunSync()
  }

  test("dreaming: DreamTimeout behaves same as DreamComplete") {
    val hooks = FakeHooks()
    val scheduler = mkScheduler(hooks)

    scheduler.submitEntry(mkEntry("e1")).unsafeRunSync()
    Thread.sleep(Debounce.toMillis + 60)
    assertEquals(hooks.triggerCalls.size, 1)

    scheduler.submitEntry(mkEntry("e2")).unsafeRunSync()
    Thread.sleep(50)

    scheduler.signalTimeout.unsafeRunSync()
    Thread.sleep(50)
    assertEquals(hooks.triggerCalls.size, 2, "timeout should process buffered entries")
    assertEquals(hooks.triggerCalls.last.entries.map(_.content), List("e2"))

    scheduler.shutdown.unsafeRunSync()
  }

  test("dreaming: chained Dream cycles (e1 → complete → e2 → complete → idle)") {
    val hooks = FakeHooks()
    val scheduler = mkScheduler(hooks)

    // Cycle 1
    scheduler.submitEntry(mkEntry("e1")).unsafeRunSync()
    Thread.sleep(Debounce.toMillis + 60)
    assertEquals(hooks.triggerCalls.size, 1)

    // e2 arrives during Dream 1
    scheduler.submitEntry(mkEntry("e2")).unsafeRunSync()
    scheduler.signalComplete.unsafeRunSync()
    Thread.sleep(50)
    assertEquals(hooks.triggerCalls.size, 2)
    assertEquals(hooks.triggerCalls.last.entries.map(_.content), List("e2"))

    // e3 arrives during Dream 2
    scheduler.submitEntry(mkEntry("e3")).unsafeRunSync()
    scheduler.signalComplete.unsafeRunSync()
    Thread.sleep(50)
    assertEquals(hooks.triggerCalls.size, 3)
    assertEquals(hooks.triggerCalls.last.entries.map(_.content), List("e3"))

    // No more entries → DreamComplete → idle
    scheduler.signalComplete.unsafeRunSync()
    Thread.sleep(50)
    assertEquals(hooks.triggerCalls.size, 3, "should not trigger with empty buffer")

    scheduler.shutdown.unsafeRunSync()
  }

  // ============================================================
  // Tests: hooks interactions
  // ============================================================

  test("stopDreamAgent called once per Dream completion") {
    val hooks = FakeHooks()
    val scheduler = mkScheduler(hooks)

    scheduler.submitEntry(mkEntry("e1")).unsafeRunSync()
    Thread.sleep(Debounce.toMillis + 60)

    scheduler.submitEntry(mkEntry("e2")).unsafeRunSync()
    scheduler.signalComplete.unsafeRunSync()
    Thread.sleep(50)
    assertEquals(hooks.stopCalls, 1)

    scheduler.signalComplete.unsafeRunSync()
    Thread.sleep(50)
    assertEquals(hooks.stopCalls, 2)

    scheduler.shutdown.unsafeRunSync()
  }

  test("touchLastDreamTime called when Dream starts") {
    val hooks = FakeHooks()
    val scheduler = mkScheduler(hooks)

    scheduler.submitEntry(mkEntry("e1")).unsafeRunSync()
    Thread.sleep(Debounce.toMillis + 60)

    assertEquals(hooks.touchCalls, 1, "should touch lastDreamTime on trigger")

    scheduler.shutdown.unsafeRunSync()
  }

  // ============================================================
  // Tests: trigger failure handling
  // ============================================================

  test("idle: trigger failure returns to idle, accepts new entries") {
    val hooks = FakeHooks(triggerResult = false)
    val scheduler = mkScheduler(hooks)

    scheduler.submitEntry(mkEntry("e1")).unsafeRunSync()
    Thread.sleep(Debounce.toMillis + 60)
    assertEquals(hooks.triggerCalls.size, 1)

    // New entry should trigger again
    scheduler.submitEntry(mkEntry("e2")).unsafeRunSync()
    Thread.sleep(Debounce.toMillis + 60)
    assertEquals(hooks.triggerCalls.size, 2)
    assertEquals(hooks.triggerCalls.last.entries.map(_.content), List("e2"))

    scheduler.shutdown.unsafeRunSync()
  }

  // ============================================================
  // Tests: FullCycleTick
  // ============================================================

  test("idle: FullCycleTick triggers full cycle Dream") {
    val hooks = FakeHooks()
    val scheduler = mkScheduler(hooks, fullCycle = 50.millis)

    // Full cycle fires after 50ms (empty buffer)
    Thread.sleep(120)

    assertEquals(hooks.triggerCalls.size, 1)
    assertEquals(hooks.triggerCalls.head.isFullCycle, true)

    scheduler.shutdown.unsafeRunSync()
  }

  test("idle: FullCycleTick with buffered entries includes them") {
    val hooks = FakeHooks()
    // Debounce longer than full cycle so full cycle wins the race
    val scheduler = mkScheduler(hooks, debounce = 200.millis, fullCycle = 50.millis)

    scheduler.submitEntry(mkEntry("pending")).unsafeRunSync()
    Thread.sleep(120)

    assertEquals(hooks.triggerCalls.size, 1)
    assertEquals(hooks.triggerCalls.head.isFullCycle, true)
    assertEquals(hooks.triggerCalls.head.entries.map(_.content), List("pending"))

    scheduler.shutdown.unsafeRunSync()
  }

  test("dreaming: FullCycleTick is deferred until DreamComplete") {
    val hooks = FakeHooks()
    val scheduler = mkScheduler(hooks, debounce = 50.millis, fullCycle = 50.millis)

    // Enter dreaming
    scheduler.submitEntry(mkEntry("e1")).unsafeRunSync()
    Thread.sleep(120) // debounce (50ms) fires → Dream starts → full cycle (50ms) fires during dreaming
    assertEquals(hooks.triggerCalls.size, 1)

    // Dream completes → deferred full cycle fires
    scheduler.signalComplete.unsafeRunSync()
    Thread.sleep(50)
    assertEquals(hooks.triggerCalls.size, 2)
    assertEquals(hooks.triggerCalls.last.isFullCycle, true, "deferred full cycle should fire")

    scheduler.shutdown.unsafeRunSync()
  }

  // ============================================================
  // Tests: Shutdown
  // ============================================================

  test("Shutdown in idle stops the scheduler") {
    val hooks = FakeHooks()
    val scheduler = mkScheduler(hooks)

    scheduler.shutdown.unsafeRunSync()
    Thread.sleep(50)

    // Scheduler should be stopped — submitting entries has no effect
    scheduler.submitEntry(mkEntry("late")).unsafeRunSync()
    Thread.sleep(Debounce.toMillis + 60)
    assertEquals(hooks.triggerCalls.size, 0, "should not trigger after shutdown")
  }

  // ============================================================
  // Test: the bug scenario — concurrent writes don't lose entries
  // ============================================================

  test("BUG REPRO: entries are NOT lost when WriteMemory fires during Dream processing") {
    val hooks = FakeHooks()
    val scheduler = mkScheduler(hooks)

    // e1 arrives → debounce → Dream 1 starts
    scheduler.submitEntry(mkEntry("important-fact-1")).unsafeRunSync()
    Thread.sleep(Debounce.toMillis + 60)
    assertEquals(hooks.triggerCalls.size, 1)
    assertEquals(hooks.triggerCalls(0).entries.map(_.content), List("important-fact-1"))

    // While Dream 1 is busy, more entries arrive
    scheduler.submitEntry(mkEntry("important-fact-2")).unsafeRunSync()
    scheduler.submitEntry(mkEntry("important-fact-3")).unsafeRunSync()
    Thread.sleep(50)

    // These should NOT trigger a new Dream (old code would kill Dream 1 here)
    assertEquals(hooks.triggerCalls.size, 1, "Dream 1 must not be interrupted")

    // Dream 1 finishes
    scheduler.signalComplete.unsafeRunSync()
    Thread.sleep(50)

    // Dream 2 should have fact-2 and fact-3
    assertEquals(hooks.triggerCalls.size, 2)
    assertEquals(
      hooks.triggerCalls(1).entries.map(_.content),
      List("important-fact-2", "important-fact-3"),
      "buffered entries must all be present in Dream 2"
    )

    // Dream 2 finishes, no more entries → back to idle
    scheduler.signalComplete.unsafeRunSync()
    Thread.sleep(50)
    assertEquals(hooks.triggerCalls.size, 2, "no extra triggers")

    scheduler.shutdown.unsafeRunSync()
  }

end DreamSchedulerSpec
