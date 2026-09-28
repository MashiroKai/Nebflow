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
 *   - Reminder refactor (2026-08-20 user ruling): schedule/tasks are root-only
 *     (Nebula); sessions/environment reminder types removed; devices takes
 *     delta lines; time suppressible via injectTime.
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

  test("collectAllIO returns time + schedule on user turns (root agent)"):
    val later = System.currentTimeMillis() + 3_600_000L
    for
      _ <- reset()
      _ <- taskStore.addTask(ScheduledTask.create("s1", "整理记忆", later))
      reminders <- SystemReminders.collectAllIO(true, taskStore, Some("s1"), isRootAgent = true)
    yield
      val cats = reminders.map(_.category)
      assert(cats.contains("time"), s"expected time category, got $cats")
      assert(!cats.contains("time-context"), s"time-context must be removed, got $cats")
      assert(cats.contains("schedule"), s"expected schedule category, got $cats")
      val sched = reminders.find(_.category == "schedule").get
      assert(sched.content.contains("Pending schedules (1)"), sched.content)
      assert(sched.content.contains("整理记忆"), sched.content)

  test("collectAllIO schedule is root-only — non-Nebula agents get none (2026-08-20 ruling)"):
    val later = System.currentTimeMillis() + 3_600_000L
    for
      _ <- reset()
      _ <- taskStore.addTask(ScheduledTask.create("s1", "整理记忆", later))
      reminders <- SystemReminders.collectAllIO(true, taskStore, Some("s1"), isRootAgent = false)
    yield
      assert(!reminders.exists(_.category == "schedule"), "Manager/team agents must not get schedule reminders")

  test("collectAllIO returns Nil on non-user turns"):
    for
      _ <- reset()
      reminders <- SystemReminders.collectAllIO(false, taskStore, Some("s1"), isRootAgent = true)
    yield assertEquals(reminders.size, 0)

  test("collectAllIO omits schedule when no pending tasks for this session"):
    for
      _ <- reset()
      _ <- taskStore.addTask(ScheduledTask.create("other-session", "不属于本会话", System.currentTimeMillis() + 3_600_000L))
      reminders <- SystemReminders.collectAllIO(true, taskStore, Some("s1"), isRootAgent = true)
    yield
      assert(reminders.exists(_.category == "time"))
      assert(!reminders.exists(_.category == "time-context"), "time-context must be removed")
      assert(!reminders.exists(_.category == "schedule"), "schedule must be empty for session without tasks")

  test("time-context removed entirely (no time-context reminder ever)"):
    for
      _ <- reset()
      reminders <- SystemReminders.collectAllIO(true, taskStore, None, isRootAgent = true)
    yield
      val cats = reminders.map(_.category)
      assert(!cats.contains("time-context"), s"time-context must not be injected, got $cats")
  // ------------------------------------------------------------------
  // Cache v2 (2026-08-11) → reminder refactor (2026-08-20 user ruling):
  //   - sessions / environment reminder types REMOVED
  //   - tasks: team sessions only (task redesign 2026-08-30) — gate lives
  //     upstream in AgentCore (teamOfSession), collectAllIO includes the
  //     reminder whenever text is non-empty; schedule: Nebula only
  //   - devices: caller supplies delta lines
  //   - time: suppressible via injectTime (system-event turns, ≥1h gap)
  // ------------------------------------------------------------------

  test("collectAllIO injects devices/tasks/language for root agents; sessions & environment are gone"):
    for
      _ <- reset()
      reminders <- SystemReminders.collectAllIO(
        true,
        taskStore,
        Some("s1"),
        isRootAgent = true,
        deviceDelta = "+ Desktop-PC\n- Old-Phone",
        taskListText = "## Current Tasks (1 active)\n#1 [pending] Fix the cache bug",
        language = Some("Chinese")
      )
    yield
      val cats = reminders.map(_.category)
      assert(cats.contains("devices"), s"expected devices, got $cats")
      assert(cats.contains("tasks"), s"expected tasks, got $cats")
      assert(cats.contains("language"), s"expected language, got $cats")
      assert(!cats.contains("sessions"), s"sessions reminder type must be REMOVED (2026-08-20 ruling), got $cats")
      assert(!cats.contains("environment"), s"environment reminder type must be REMOVED (2026-08-20 ruling), got $cats")
      val tasks = reminders.find(_.category == "tasks").get
      assert(tasks.content.contains("Fix the cache bug"), tasks.content)
      val dev = reminders.find(_.category == "devices").get
      assert(dev.content.contains("+ Desktop-PC"), dev.content)
      assert(dev.content.contains("- Old-Phone"), dev.content)

  test("collectAllIO injectTime=false suppresses the time reminder (system-event ≥1h gate)"):
    for
      _ <- reset()
      reminders <- SystemReminders.collectAllIO(true, taskStore, Some("s1"), isRootAgent = true, injectTime = false)
    yield
      assert(!reminders.exists(_.category == "time"), "system-event turns within 1h must not re-inject time")

  test("collectAllIO omits reminders when values are empty (time stays)"):
    for
      _ <- reset()
      reminders <- SystemReminders.collectAllIO(true, taskStore, Some("s1"), isRootAgent = true)
    yield
      val cats = reminders.map(_.category)
      assert(!cats.contains("devices"), cats)
      assert(!cats.contains("environment"), cats)
      assert(!cats.contains("tasks"), cats)
      assert(!cats.contains("language"), cats)
      assert(cats.contains("time"), "time reminder must remain (持久化语义不受影响)")

  test("collectAllIO tasks ride along whenever text is non-empty (gate lives upstream in AgentCore)"):
    for
      _ <- reset()
      reminders <- SystemReminders.collectAllIO(
        true,
        taskStore,
        Some("s1"),
        isRootAgent = false,
        taskListText = "## Current Tasks (1 active)\n#1 [pending] Team-scoped",
        deviceDelta = "+ Desktop-PC",
        language = Some("Chinese")
      )
    yield
      val cats = reminders.map(_.category)
      // Task redesign (2026-08-30): AgentCore only renders taskListText for
      // team-registered sessions — collectAllIO itself no longer gates on
      // isRootAgent, so a non-root caller WITH text gets the reminder.
      assert(cats.contains("tasks"), s"task reminder must follow the supplied text, got $cats")
      assert(cats.contains("devices"), s"devices stays for non-root agents, got $cats")
      assert(cats.contains("language"), s"language stays for non-root agents, got $cats")
      assert(cats.contains("time"), s"time stays for non-root agents, got $cats")

  test("collectAllIO suppresses all reminders on non-user turns"):
    for
      _ <- reset()
      reminders <- SystemReminders.collectAllIO(
        false,
        taskStore,
        Some("s1"),
        isRootAgent = true,
        deviceDelta = "+ Desktop-PC",
        taskListText = "## Current Tasks (1 active)\n#1 [pending] x"
      )
    yield assertEquals(reminders.size, 0)

end ScheduleV2Spec
