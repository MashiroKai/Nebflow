package nebflow.core

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.scheduler.{ScheduledTask, ScheduledTaskStore}

/**
 * Schedule engine v2 (2026-08-12):
 *   - ScheduledTaskStore.getAllDueTasks returns tasks sorted by triggerAt (FIFO
 *     firing order — simultaneously-due tasks are processed one by one).
 *   - SystemReminders.collectAllIO injects time-context (peak/off-peak + next
 *     idle window) and a summary of the session's pending schedules.
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

  test("collectAllIO returns time + time-context + schedule on user turns"):
    val later = System.currentTimeMillis() + 3_600_000L
    val pattern = UsagePattern(
      hourlyActivity = Vector.fill(24)(0.0),
      dailyActivity = Vector.fill(7)(0.0),
      idleWindows = List(TimeWindow(0, 23, 3)),
      totalRecords = 1000,
      lastUpdated = System.currentTimeMillis()
    )
    for
      _ <- reset()
      _ <- IO.delay(os.write.over(tempRoot / "usage-pattern.json", pattern.asJson.spaces2))
      _ <- taskStore.addTask(ScheduledTask.create("s1", "整理记忆", later))
      reminders <- SystemReminders.collectAllIO(true, taskStore, Some("s1"))
    yield
      val cats = reminders.map(_.category)
      assert(cats.contains("time"), s"expected time category, got $cats")
      assert(cats.contains("time-context"), s"expected time-context category, got $cats")
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
    val pattern = UsagePattern(
      hourlyActivity = Vector.fill(24)(0.0),
      dailyActivity = Vector.fill(7)(0.0),
      idleWindows = Nil,
      totalRecords = 1000,
      lastUpdated = System.currentTimeMillis()
    )
    for
      _ <- reset()
      _ <- IO.delay(os.write.over(tempRoot / "usage-pattern.json", pattern.asJson.spaces2))
      _ <- taskStore.addTask(ScheduledTask.create("other-session", "不属于本会话", System.currentTimeMillis() + 3_600_000L))
      reminders <- SystemReminders.collectAllIO(true, taskStore, Some("s1"))
    yield
      assert(reminders.exists(_.category == "time"))
      assert(reminders.exists(_.category == "time-context"))
      assert(!reminders.exists(_.category == "schedule"), "schedule must be empty for session without tasks")

  test("time-context reports next idle window when idleWindows present"):
    val todayDow = java.time.ZonedDateTime.now().getDayOfWeek.getValue % 7
    val pattern = UsagePattern(
      hourlyActivity = Vector.fill(24)(0.0),
      dailyActivity = Vector.fill(7)(0.0),
      idleWindows = List(TimeWindow(todayDow, 23, 3)),
      totalRecords = 1000,
      lastUpdated = System.currentTimeMillis()
    )
    for
      _ <- reset()
      _ <- IO.delay(os.write.over(tempRoot / "usage-pattern.json", pattern.asJson.spaces2))
      reminders <- SystemReminders.collectAllIO(true, taskStore, None)
    yield
      val ctx = reminders.find(_.category == "time-context").get
      assert(ctx.content.contains("Next idle window"), ctx.content)
      assert(ctx.content.contains("23:00"), ctx.content)

end ScheduleV2Spec
