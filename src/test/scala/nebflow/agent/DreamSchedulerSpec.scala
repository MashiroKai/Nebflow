package nebflow.agent

import org.apache.pekko.actor.testkit.typed.scaladsl.BehaviorTestKit
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.*

/**
 * Unit tests for the DreamScheduler state machine.
 *
 * Tests verify the core invariant: entries arriving while a Dream cycle is
 * in progress are buffered and not lost. The scheduler transitions between
 * idle → dreaming → idle states correctly.
 *
 * Uses [[BehaviorTestKit]] from pekko-actor-testkit-typed, which runs the
 * actor behavior synchronously without a real ActorSystem — fast and
 * deterministic.
 */
class DreamSchedulerSpec extends munit.FunSuite:

  // ============================================================
  // Test fixtures
  // ============================================================

  /** Records all hooks invocations for assertion. */
  private case class TriggerCall(
    entries: List[DreamCommand.ProcessEntry],
    isFullCycle: Boolean
  )

  /** Fake hooks implementation that records all calls. */
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

  /** Short durations for fast tests. */
  private val Debounce = 50.millis
  private val FullCycle = 999.hours // effectively never fires in tests
  private val Timeout = 999.hours   // effectively never fires in tests

  private def mkEntry(content: String): DreamCommand.ProcessEntry =
    DreamCommand.ProcessEntry("user", content, None, "test", None)

  /** Create a scheduler with the given hooks. */
  private def mkScheduler(hooks: DreamScheduler.Hooks): Behavior[DreamCommand] =
    DreamScheduler(hooks, Debounce, FullCycle, Timeout)

  // ============================================================
  // Tests: idle state
  // ============================================================

  test("idle: single entry triggers after debounce") {
    val hooks = FakeHooks()
    val testKit = BehaviorTestKit(mkScheduler(hooks))

    // Send entry — should not trigger immediately
    testKit.run(mkEntry("e1"))
    assertEquals(hooks.triggerCalls.size, 0, "should not trigger immediately")

    // Wait for debounce
    Thread.sleep(Debounce.toMillis + 30)
    testKit.run(DreamCommand.FlushEntries)

    assertEquals(hooks.triggerCalls.size, 1, "should trigger after debounce")
    assertEquals(hooks.triggerCalls.head.entries.map(_.content), List("e1"))
    assertEquals(hooks.triggerCalls.head.isFullCycle, false)
  }

  test("idle: multiple entries batched before debounce") {
    val hooks = FakeHooks()
    val testKit = BehaviorTestKit(mkScheduler(hooks))

    testKit.run(mkEntry("e1"))
    testKit.run(mkEntry("e2"))
    testKit.run(mkEntry("e3"))
    assertEquals(hooks.triggerCalls.size, 0)

    Thread.sleep(Debounce.toMillis + 30)
    testKit.run(DreamCommand.FlushEntries)

    assertEquals(hooks.triggerCalls.size, 1)
    assertEquals(hooks.triggerCalls.head.entries.map(_.content), List("e1", "e2", "e3"))
  }

  test("idle: debounce only starts on first entry, not subsequent") {
    val hooks = FakeHooks()
    val testKit = BehaviorTestKit(mkScheduler(hooks))

    testKit.run(mkEntry("e1"))
    Thread.sleep(Debounce.toMillis / 2)
    testKit.run(mkEntry("e2"))  // should NOT restart debounce

    // Total sleep < debounce → no trigger yet
    Thread.sleep(Debounce.toMillis / 2)
    // Now debounce should have fired
    testKit.run(DreamCommand.FlushEntries)

    assertEquals(hooks.triggerCalls.size, 1)
    assertEquals(hooks.triggerCalls.head.entries.map(_.content), List("e1", "e2"))
  }

  test("idle: FlushEntries with empty buffer is a no-op") {
    val hooks = FakeHooks()
    val testKit = BehaviorTestKit(mkScheduler(hooks))

    testKit.run(DreamCommand.FlushEntries)
    assertEquals(hooks.triggerCalls.size, 0)
  }

  test("idle: stale DreamComplete is ignored") {
    val hooks = FakeHooks()
    val testKit = BehaviorTestKit(mkScheduler(hooks))

    testKit.run(DreamCommand.DreamComplete)
    testKit.run(DreamCommand.DreamTimeout)
    assertEquals(hooks.triggerCalls.size, 0)
    assertEquals(hooks.stopCalls, 0)
  }

  // ============================================================
  // Tests: dreaming state — THE CORE FIX
  // ============================================================

  test("dreaming: entry arriving during Dream is buffered, not lost") {
    val hooks = FakeHooks()
    val testKit = BehaviorTestKit(mkScheduler(hooks))

    // Phase 1: trigger Dream with e1
    testKit.run(mkEntry("e1"))
    Thread.sleep(Debounce.toMillis + 30)
    testKit.run(DreamCommand.FlushEntries)
    assertEquals(hooks.triggerCalls.size, 1, "first Dream should trigger")
    assertEquals(hooks.triggerCalls.last.entries.map(_.content), List("e1"))

    // Phase 2: while Dream is running, new entry arrives
    testKit.run(mkEntry("e2"))
    // Should NOT trigger a new Dream
    assertEquals(hooks.triggerCalls.size, 1, "should not trigger while dreaming")

    // Phase 3: Dream completes → e2 should be in the next trigger
    testKit.run(DreamCommand.DreamComplete)
    assertEquals(hooks.triggerCalls.size, 2, "second Dream should trigger after completion")
    assertEquals(hooks.triggerCalls.last.entries.map(_.content), List("e2"), "e2 should be buffered and processed")
  }

  test("dreaming: multiple entries during Dream are all buffered") {
    val hooks = FakeHooks()
    val testKit = BehaviorTestKit(mkScheduler(hooks))

    // Trigger Dream
    testKit.run(mkEntry("initial"))
    Thread.sleep(Debounce.toMillis + 30)
    testKit.run(DreamCommand.FlushEntries)
    assertEquals(hooks.triggerCalls.size, 1)

    // Multiple entries during Dream
    testKit.run(mkEntry("a"))
    testKit.run(mkEntry("b"))
    testKit.run(mkEntry("c"))

    // Dream completes → all buffered entries should trigger
    testKit.run(DreamCommand.DreamComplete)
    assertEquals(hooks.triggerCalls.size, 2)
    assertEquals(hooks.triggerCalls.last.entries.map(_.content), List("a", "b", "c"))
  }

  test("dreaming: DreamComplete with empty buffer returns to idle") {
    val hooks = FakeHooks()
    val testKit = BehaviorTestKit(mkScheduler(hooks))

    // Trigger Dream
    testKit.run(mkEntry("solo"))
    Thread.sleep(Debounce.toMillis + 30)
    testKit.run(DreamCommand.FlushEntries)
    assertEquals(hooks.triggerCalls.size, 1)

    // No entries during Dream → DreamComplete → back to idle
    testKit.run(DreamCommand.DreamComplete)
    assertEquals(hooks.triggerCalls.size, 1, "should not trigger again with no buffered entries")
    assertEquals(hooks.stopCalls, 1, "should stop Dream agent")

    // Now in idle — new entry should trigger normally
    testKit.run(mkEntry("next"))
    Thread.sleep(Debounce.toMillis + 30)
    testKit.run(DreamCommand.FlushEntries)
    assertEquals(hooks.triggerCalls.size, 2)
    assertEquals(hooks.triggerCalls.last.entries.map(_.content), List("next"))
  }

  test("dreaming: DreamTimeout behaves same as DreamComplete") {
    val hooks = FakeHooks()
    val testKit = BehaviorTestKit(mkScheduler(hooks))

    // Trigger Dream
    testKit.run(mkEntry("e1"))
    Thread.sleep(Debounce.toMillis + 30)
    testKit.run(DreamCommand.FlushEntries)
    assertEquals(hooks.triggerCalls.size, 1)

    // Entry during Dream
    testKit.run(mkEntry("e2"))

    // Timeout fires (Dream agent crashed/hung)
    testKit.run(DreamCommand.DreamTimeout)
    assertEquals(hooks.triggerCalls.size, 2, "timeout should process buffered entries")
    assertEquals(hooks.triggerCalls.last.entries.map(_.content), List("e2"))
  }

  test("dreaming: FlushEntries (stale debounce timer) is ignored") {
    val hooks = FakeHooks()
    val testKit = BehaviorTestKit(mkScheduler(hooks))

    // Trigger Dream
    testKit.run(mkEntry("e1"))
    Thread.sleep(Debounce.toMillis + 30)
    testKit.run(DreamCommand.FlushEntries)
    assertEquals(hooks.triggerCalls.size, 1)

    // Stale FlushEntries arrives — should be ignored
    testKit.run(mkEntry("e2"))
    testKit.run(DreamCommand.FlushEntries)
    assertEquals(hooks.triggerCalls.size, 1, "stale FlushEntries should be ignored in dreaming state")

    // Clean up
    testKit.run(DreamCommand.DreamComplete)
  }

  test("dreaming: chained Dream cycles (e1 → complete → e2 → complete → idle)") {
    val hooks = FakeHooks()
    val testKit = BehaviorTestKit(mkScheduler(hooks))

    // Cycle 1
    testKit.run(mkEntry("e1"))
    Thread.sleep(Debounce.toMillis + 30)
    testKit.run(DreamCommand.FlushEntries)
    assertEquals(hooks.triggerCalls.size, 1)

    // e2 arrives during Dream 1
    testKit.run(mkEntry("e2"))
    testKit.run(DreamCommand.DreamComplete)
    assertEquals(hooks.triggerCalls.size, 2)
    assertEquals(hooks.triggerCalls.last.entries.map(_.content), List("e2"))

    // e3 arrives during Dream 2
    testKit.run(mkEntry("e3"))
    testKit.run(DreamCommand.DreamComplete)
    assertEquals(hooks.triggerCalls.size, 3)
    assertEquals(hooks.triggerCalls.last.entries.map(_.content), List("e3"))

    // No more entries → DreamComplete → idle
    testKit.run(DreamCommand.DreamComplete)
    assertEquals(hooks.triggerCalls.size, 3, "should not trigger with empty buffer")
  }

  // ============================================================
  // Tests: hooks interactions
  // ============================================================

  test("stopDreamAgent called once per Dream completion") {
    val hooks = FakeHooks()
    val testKit = BehaviorTestKit(mkScheduler(hooks))

    testKit.run(mkEntry("e1"))
    Thread.sleep(Debounce.toMillis + 30)
    testKit.run(DreamCommand.FlushEntries)

    testKit.run(mkEntry("e2"))
    testKit.run(DreamCommand.DreamComplete)
    // After DreamComplete, old agent is stopped, new Dream is triggered
    assertEquals(hooks.stopCalls, 1)

    testKit.run(DreamCommand.DreamComplete)
    // Second completion, no more entries
    assertEquals(hooks.stopCalls, 2)
  }

  test("touchLastDreamTime called when Dream starts") {
    val hooks = FakeHooks()
    val testKit = BehaviorTestKit(mkScheduler(hooks))

    testKit.run(mkEntry("e1"))
    Thread.sleep(Debounce.toMillis + 30)
    testKit.run(DreamCommand.FlushEntries)

    assertEquals(hooks.touchCalls, 1, "should touch lastDreamTime on trigger")
  }

  // ============================================================
  // Tests: trigger failure handling
  // ============================================================

  test("idle: trigger failure returns to idle, accepts new entries") {
    val hooks = FakeHooks(triggerResult = false)
    val testKit = BehaviorTestKit(mkScheduler(hooks))

    testKit.run(mkEntry("e1"))
    Thread.sleep(Debounce.toMillis + 30)
    testKit.run(DreamCommand.FlushEntries)

    // Trigger failed — should stay in idle (not dreaming)
    assertEquals(hooks.triggerCalls.size, 1)

    // New entry should trigger again
    testKit.run(mkEntry("e2"))
    Thread.sleep(Debounce.toMillis + 30)
    testKit.run(DreamCommand.FlushEntries)

    assertEquals(hooks.triggerCalls.size, 2)
    assertEquals(hooks.triggerCalls.last.entries.map(_.content), List("e2"))
  }

  // ============================================================
  // Tests: FullCycleTick
  // ============================================================

  test("idle: FullCycleTick triggers full cycle Dream") {
    val hooks = FakeHooks()
    val testKit = BehaviorTestKit(mkScheduler(hooks))

    // Manually inject FullCycleTick (timer would take 999 hours)
    testKit.run(DreamCommand.FullCycleTick)

    assertEquals(hooks.triggerCalls.size, 1)
    assertEquals(hooks.triggerCalls.head.isFullCycle, true)
  }

  test("idle: FullCycleTick with buffered entries includes them") {
    val hooks = FakeHooks()
    val testKit = BehaviorTestKit(mkScheduler(hooks))

    testKit.run(mkEntry("pending"))
    testKit.run(DreamCommand.FullCycleTick)

    assertEquals(hooks.triggerCalls.size, 1)
    assertEquals(hooks.triggerCalls.head.isFullCycle, true)
    assertEquals(hooks.triggerCalls.head.entries.map(_.content), List("pending"))
  }

  test("dreaming: FullCycleTick is deferred until DreamComplete") {
    val hooks = FakeHooks()
    val testKit = BehaviorTestKit(mkScheduler(hooks))

    // Enter dreaming
    testKit.run(mkEntry("e1"))
    Thread.sleep(Debounce.toMillis + 30)
    testKit.run(DreamCommand.FlushEntries)
    assertEquals(hooks.triggerCalls.size, 1)

    // FullCycleTick during dreaming
    testKit.run(DreamCommand.FullCycleTick)
    assertEquals(hooks.triggerCalls.size, 1, "should not trigger during dreaming")

    // Dream completes → deferred full cycle fires
    testKit.run(DreamCommand.DreamComplete)
    assertEquals(hooks.triggerCalls.size, 2)
    assertEquals(hooks.triggerCalls.last.isFullCycle, true, "deferred full cycle should fire")
  }

  // ============================================================
  // Tests: Shutdown
  // ============================================================

  test("Shutdown in idle stops the scheduler") {
    val hooks = FakeHooks()
    val testKit = BehaviorTestKit(mkScheduler(hooks))

    testKit.run(DreamCommand.Shutdown)
    // Actor should be stopped — BehaviorTestKit tracks this
    assert(testKit.isAlive == false, "scheduler should stop on Shutdown")
  }

  test("Shutdown in dreaming stops the scheduler") {
    val hooks = FakeHooks()
    val testKit = BehaviorTestKit(mkScheduler(hooks))

    testKit.run(mkEntry("e1"))
    Thread.sleep(Debounce.toMillis + 30)
    testKit.run(DreamCommand.FlushEntries)

    testKit.run(DreamCommand.Shutdown)
    assert(testKit.isAlive == false, "scheduler should stop on Shutdown while dreaming")
  }

  // ============================================================
  // Test: the bug scenario — concurrent writes don't lose entries
  // ============================================================

  test("BUG REPRO: entries are NOT lost when WriteMemory fires during Dream processing") {
    /**
     * Before the fix:
     *   - idle receives e1, debounce, FlushEntries → triggerDream([e1]) → idle(Nil)
     *   - While Dream processes e1, e2 arrives → idle receives e2, debounce
     *   - FlushEntries → triggerDream([e2]) → kills Dream processing e1!
     *   - e1's unprocessed entries are LOST.
     *
     * After the fix:
     *   - idle receives e1, debounce, FlushEntries → triggerDream([e1]) → dreaming(Nil)
     *   - While Dream processes e1, e2 arrives → dreaming buffers e2
     *   - DreamComplete → dreaming stops old agent → triggerDream([e2]) → dreaming(Nil)
     *   - e2 is safely processed. No data loss.
     */
    val hooks = FakeHooks()
    val testKit = BehaviorTestKit(mkScheduler(hooks))

    // e1 arrives
    testKit.run(mkEntry("important-fact-1"))

    // Debounce fires → Dream 1 starts processing e1
    Thread.sleep(Debounce.toMillis + 30)
    testKit.run(DreamCommand.FlushEntries)
    assertEquals(hooks.triggerCalls.size, 1)
    assertEquals(hooks.triggerCalls(0).entries.map(_.content), List("important-fact-1"))

    // While Dream 1 is busy, more entries arrive
    testKit.run(mkEntry("important-fact-2"))
    testKit.run(mkEntry("important-fact-3"))

    // These should NOT trigger a new Dream (old code would kill Dream 1 here)
    assertEquals(hooks.triggerCalls.size, 1, "Dream 1 must not be interrupted")

    // Dream 1 finishes
    testKit.run(DreamCommand.DreamComplete)

    // Dream 2 should have fact-2 and fact-3
    assertEquals(hooks.triggerCalls.size, 2)
    assertEquals(
      hooks.triggerCalls(1).entries.map(_.content),
      List("important-fact-2", "important-fact-3"),
      "buffered entries must all be present in Dream 2"
    )

    // Dream 2 finishes, no more entries → back to idle
    testKit.run(DreamCommand.DreamComplete)
    assertEquals(hooks.triggerCalls.size, 2, "no extra triggers")
  }

end DreamSchedulerSpec
