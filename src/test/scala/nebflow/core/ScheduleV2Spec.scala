package nebflow.core

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.core.scheduler.{ScheduledTask, ScheduledTaskStore}

/**
 * Schedule engine v2 (2026-08-12):
 *   - ScheduledTaskStore.getAllDueTasks returns tasks sorted by triggerAt (FIFO
 *     firing order — simultaneously-due tasks are processed one by one).
 *   - SystemReminders.collectAllIO injects a summary of the session's pending
 *     schedules. time-context (peak/off-peak) removed 2026-08-19 audit.
 */
class ScheduleV2Spec extends CatsEffectSuite:

  private val tempRoot: os.Path = os.pwd / "target" / "test-schedule-v2"
  PathUtil.setDataRoot(tempRoot)

  private val taskStore: ScheduledTaskStore = new ScheduledTaskStore(tempRoot / "scheduled-tasks")

  private def reset(): IO[Unit] =
    IO.delay { if os.exists(tempRoot) then os.remove.all(tempRoot) } *>
      IO.delay { os.makeDir.all(tempRoot) } *>
      IO.delay { os.makeDir.all(tempRoot / "scheduled-tasks") }

  // ------------------------------------------------------------------
  // FIFO: getAllDueTasks sorted by triggerAt
  // ------------------------------------------------------------------

  test("getAllDueTasks returns due tasks sorted by triggerAt (FIFO firing order)"):
    val now = System.currentTimeMillis()
    for
      _ <- reset()
      // Added in shuffled order — must come back sorted by triggerAt
      _ <- taskStore.addTask(ScheduledTask.create("s1", "later", now - 3_000))
      _ <- taskStore.addTask(ScheduledTask.create("s1", "earliest", now - 30_000))
      _ <- taskStore.addTask(ScheduledTask.create("s1", "middle", now - 15_000))
      due <- taskStore.getAllDueTasks
    yield
      assertEquals(due.size, 3)
      assertEquals(due.map(_.content), List("earliest", "middle", "later"))
      // triggerAt strictly increasing
      assert(due.map(_.triggerAt) == due.map(_.triggerAt).sorted)

  test("getAllDueTasks excludes triggered and disabled tasks"):
    val now = System.currentTimeMillis()
    val triggered = ScheduledTask.create("s2", "already-fired", now - 10_000)
    val disabled = ScheduledTask.create("s2", "paused", now - 10_000)
    for
      _ <- reset()
      _ <- taskStore.addTask(triggered)
      _ <- taskStore.addTask(disabled)
      _ <- taskStore.markTriggered("s2", triggered.id)
      _ <- taskStore.toggleTask("s2", disabled.id)
      due <- taskStore.getAllDueTasks
    yield assertEquals(due.size, 0)

  // ------------------------------------------------------------------
  // P1-2: SystemReminders.collectAllIO
  // ------------------------------------------------------------------

  test("collectAllIO returns time + schedule on user turns"):
    val later = System.currentTimeMillis() + 3_600_000L
    for
      _ <- reset()
      _ <- taskStore.addTask(ScheduledTask.create("s1", "整理记忆", later))
      reminders <- SystemReminders.collectAllIO(true, taskStore, Some("s1"))
    yield
      val cats = reminders.map(_.category)
      assert(cats.contains("time"), s"expected time category, got $cats")
      assert(!cats.contains("time-context"), s"time-context must be removed, got $cats")
      assert(cats.contains("schedule"), s"expected schedule category, got $cats")
      val sched = reminders.find(_.category == "schedule").get
      assert(sched.content.contains("Pending schedules (1)"), sched.content)
      assert(sched.content.contains("整理记忆"), sched.content)

  test("collectAllIO returns Nil on non-user turns"):
    for
      _ <- reset()
      reminders <- SystemReminders.collectAllIO(false, taskStore, Some("s1"))
    yield assertEquals(reminders.size, 0)

  test("collectAllIO omits schedule when no pending tasks for this session"):
    for
      _ <- reset()
      _ <- taskStore.addTask(ScheduledTask.create("other-session", "不属于本会话", System.currentTimeMillis() + 3_600_000L))
      reminders <- SystemReminders.collectAllIO(true, taskStore, Some("s1"))
    yield
      assert(reminders.exists(_.category == "time"))
      assert(!reminders.exists(_.category == "time-context"), "time-context must be removed")
      assert(!reminders.exists(_.category == "schedule"), "schedule must be empty for session without tasks")

  test("time-context removed entirely (no time-context reminder ever)"):
    for
      _ <- reset()
      reminders <- SystemReminders.collectAllIO(true, taskStore, None)
    yield
      val cats = reminders.map(_.category)
      assert(!cats.contains("time-context"), s"time-context must not be injected, got $cats")

  // ------------------------------------------------------------------
  // Cache v2 (2026-08-11): dynamic sections moved out of systemStable
  // are injected as user-turn reminders (devices/sessions/env/tasks/language).
  // ------------------------------------------------------------------

  test("collectAllIO injects cache-v2 dynamic reminders when values are present"):
    for
      _ <- reset()
      reminders <- SystemReminders.collectAllIO(
        true,
        taskStore,
        Some("s1"),
        deviceInfo = "local (MacBook); Desktop-PC",
        sessionsText = "# Active Sessions\n\naddr-1 — Explorer: investigating (running)",
        taskListText = "## Current Tasks\n\n#1 [pending] Fix the cache bug",
        language = Some("Chinese"),
        envInfo = "## Environment\n\n| Chat width | ~1200px |"
      )
    yield
      val cats = reminders.map(_.category)
      assert(cats.contains("devices"), s"expected devices, got $cats")
      assert(cats.contains("sessions"), s"expected sessions, got $cats")
      assert(cats.contains("environment"), s"expected environment, got $cats")
      assert(cats.contains("tasks"), s"expected tasks, got $cats")
      assert(cats.contains("language"), s"expected language, got $cats")
      val tasks = reminders.find(_.category == "tasks").get
      assert(tasks.content.contains("Fix the cache bug"), tasks.content)
      val lang = reminders.find(_.category == "language").get
      assert(lang.content.contains("Chinese"), lang.content)
      val dev = reminders.find(_.category == "devices").get
      assert(dev.content.contains("Desktop-PC"), dev.content)

  test("collectAllIO omits cache-v2 reminders when values are empty (time stays)"):
    for
      _ <- reset()
      reminders <- SystemReminders.collectAllIO(true, taskStore, Some("s1"))
    yield
      val cats = reminders.map(_.category)
      assert(!cats.contains("devices"), cats)
      assert(!cats.contains("sessions"), cats)
      assert(!cats.contains("environment"), cats)
      assert(!cats.contains("tasks"), cats)
      assert(!cats.contains("language"), cats)
      assert(cats.contains("time"), "time reminder must remain (持久化语义不受影响)")

  test("collectAllIO suppresses cache-v2 reminders on non-user turns"):
    for
      _ <- reset()
      reminders <- SystemReminders.collectAllIO(
        false,
        taskStore,
        Some("s1"),
        deviceInfo = "local (MacBook)",
        taskListText = "## Current Tasks\n\n#1 [pending] x"
      )
    yield assertEquals(reminders.size, 0)

  // ------------------------------------------------------------------
  // Reminder audit (2026-08-19): tasks reminder gated to root agents
  // ------------------------------------------------------------------

  test("collectAllIO omits tasks reminder for workers (depth > 0) but keeps other categories"):
    for
      _ <- reset()
      reminders <- SystemReminders.collectAllIO(
        true,
        taskStore,
        Some("s1"),
        taskListText = "## Current Tasks\n\n#1 [pending] Fix the cache bug",
        deviceInfo = "local (MacBook)",
        language = Some("Chinese"),
        depth = 1
      )
    yield
      val cats = reminders.map(_.category)
      assert(!cats.contains("tasks"), s"tasks must be gated for workers, got $cats")
      assert(cats.contains("devices"), s"devices stays for workers, got $cats")
      assert(cats.contains("language"), s"language stays for workers, got $cats")
      assert(cats.contains("time"), s"time stays for workers, got $cats")

  test("collectAllIO keeps tasks reminder for root agents (depth 0)"):
    for
      _ <- reset()
      reminders <- SystemReminders.collectAllIO(
        true,
        taskStore,
        Some("s1"),
        taskListText = "## Current Tasks\n\n#1 [pending] Fix the cache bug",
        depth = 0
      )
    yield
      val tasks = reminders.find(_.category == "tasks")
      assert(tasks.nonEmpty, s"expected tasks reminder, got ${reminders.map(_.category)}")
      assert(tasks.get.content.contains("Fix the cache bug"), tasks.get.content)

end ScheduleV2Spec
