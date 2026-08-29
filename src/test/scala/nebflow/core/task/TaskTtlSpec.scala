package nebflow.core.task

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.parser.decode
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.PathUtil

import java.time.Instant

/** 任务工具重做 (2026-08-30) — TTL persistence rules:
  *   1. completed/failed auto-purge after 6h (judged by completedAt/updatedAt)
  *   2. pending/in_progress auto-purge after 2d (judged by createdAt)
  *   3. Judgement reads the PERSISTED timestamps — a restart sweep over disk
  *      data still cleans (not an in-memory timer)
  *   4. listVisible hides expired rows; purgeAllExpired deletes the files
  */
class TaskTtlSpec extends CatsEffectSuite:

  private val tempRoot: os.Path = os.pwd / "target" / "test-task-ttl"

  override def beforeEach(context: munit.BeforeEach): Unit =
    PathUtil.setDataRoot(tempRoot)
    if os.exists(tempRoot) then os.remove.all(tempRoot)
    os.makeDir.all(tempRoot)

  override def afterEach(context: munit.AfterEach): Unit =
    if os.exists(tempRoot) then os.remove.all(tempRoot)

  private val store: TaskStore = FileTaskStore

  /** Write a task file with explicit timestamps — direct disk write so the
    * sweep sees pre-existing data (restart scenario). */
  private def seedTask(sid: String, id: String, status: String, createdAt: String, completedAt: String): Unit =
    val dir = tempRoot / "tasks" / sid
    os.makeDir.all(dir)
    val json = s"""{"id":"$id","subject":"t$id","description":"d","status":"$status","createdAt":"$createdAt","updatedAt":"$createdAt","completedAt":"$completedAt","events":[]}"""
    os.write(dir / s"$id.json", json)

  private def taskExists(sid: String, id: String): Boolean =
    os.exists(tempRoot / "tasks" / sid / s"$id.json")

  test("TTL-1: completed tasks expire after 6h, not before"):
    val now = Instant.now()
    val fresh = now.minusSeconds(5 * 3600).toString // 5h ago — alive
    val stale = now.minusSeconds(7 * 3600).toString // 7h ago — expired
    val empty = ""
    val freshTask = Task(id = "1", subject = "fresh", description = "d", status = TaskStatus.Completed, completedAt = Some(fresh))
    val staleTask = Task(id = "2", subject = "stale", description = "d", status = TaskStatus.Failed, completedAt = Some(stale))
    assert(!TaskStore.isExpired(freshTask, now), "5h-old completed task is NOT expired")
    assert(TaskStore.isExpired(staleTask, now), "7h-old failed task IS expired")

  test("TTL-2: active tasks expire after 2d, not before"):
    val now = Instant.now()
    val fresh = now.minusSeconds(47 * 3600).toString // 47h — alive
    val stale = now.minusSeconds(49 * 3600).toString // 49h — expired
    val freshTask = Task(id = "1", subject = "f", description = "d", status = TaskStatus.InProgress, createdAt = Some(fresh))
    val staleTask = Task(id = "2", subject = "s", description = "d", status = TaskStatus.Pending, createdAt = Some(stale))
    assert(!TaskStore.isExpired(freshTask, now), "47h-old in_progress task is NOT expired")
    assert(TaskStore.isExpired(staleTask, now), "49h-old pending task IS expired")

  test("TTL-3: purgeAllExpired sweeps disk data after restart (no in-memory state)"):
    val now = Instant.now()
    val recent = now.minusSeconds(3600).toString       // 1h ago — keep
    val oldCompleted = now.minusSeconds(8 * 3600).toString // 8h ago — purge
    val oldActive = now.minusSeconds(3 * 24 * 3600).toString // 3d ago — purge
    seedTask("sess-a", "1", "completed", recent, recent)
    seedTask("sess-a", "2", "failed", oldCompleted, oldCompleted)
    seedTask("sess-b", "1", "in_progress", oldActive, "")
    seedTask("sess-b", "2", "pending", recent, "")
    // team scope too
    val teamDir = tempRoot / "tasks" / "teams" / "demo"
    os.makeDir.all(teamDir)
    os.write(teamDir / "1.json", s"""{"id":"1","subject":"team","description":"d","status":"completed","createdAt":"$oldCompleted","updatedAt":"$oldCompleted","completedAt":"$oldCompleted","events":[]}""")

    val purged = FileTaskStore.purgeAllExpired().unsafeRunSync()
    assertEquals(purged, 3, "one failed + one active + one team task expired")
    assert(taskExists("sess-a", "1"), "recent completed task survives")
    assert(!taskExists("sess-a", "2"), "7h+ completed task purged")
    assert(!taskExists("sess-b", "1"), "3d-old active task purged")
    assert(taskExists("sess-b", "2"), "recent pending task survives")
    assert(!os.exists(tempRoot / "tasks" / "teams" / "demo" / "1.json"), "team completed task purged")

  test("listVisible shows pending/in_progress only — completed/failed never render (progress display)"):
    val now = Instant.now()
    val recent = now.minusSeconds(3600).toString
    val stale = now.minusSeconds(8 * 3600).toString
    seedTask("vis-sid", "1", "in_progress", recent, "")
    seedTask("vis-sid", "2", "failed", stale, stale)
    seedTask("vis-sid", "3", "completed", recent, recent)
    seedTask("vis-sid", "4", "pending", recent, "")
    val visible = store.listVisible("vis-sid").unsafeRunSync()
    assertEquals(visible.map(_.id).sorted, List("1", "4"), "列表只显 pending+in_progress")
    assert(taskExists("vis-sid", "2"), "file still on disk until purge")

  test("purgeExpired in one scope deletes only expired rows there"):
    val now = Instant.now()
    val recent = now.minusSeconds(3600).toString
    val stale = now.minusSeconds(8 * 3600).toString
    seedTask("purge-sid", "1", "completed", recent, recent)
    seedTask("purge-sid", "2", "failed", stale, stale)
    val n = FileTaskStore.purgeExpired("purge-sid").unsafeRunSync()
    assertEquals(n, 1)
    assert(taskExists("purge-sid", "1"))
    assert(!taskExists("purge-sid", "2"))
