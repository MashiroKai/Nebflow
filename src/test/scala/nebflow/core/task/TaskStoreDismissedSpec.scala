package nebflow.core.task

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.core.PathUtil

class TaskStoreDismissedSpec extends CatsEffectSuite:
  private val tempRoot: os.Path = os.pwd / "target" / "test-task-dismiss"
  PathUtil.setDataRoot(tempRoot)

  private val store: TaskStore = FileTaskStore

  private def reset(): IO[Unit] =
    IO.delay { if os.exists(tempRoot) then os.remove.all(tempRoot) } *>
      IO.delay { os.makeDir.all(tempRoot) }

  /** Create a task, move it to Completed, then Dismiss it (the real user flow).
    * v2 C15: agent TaskUpdate can no longer reach completed — seed via the
    * complete() path (by="agent", the legacy agent-flow completion). */
  private def mkDismissedTask(sessionId: String, subject: String = "test"): IO[String] =
    for
      tid <- store.create(sessionId, TaskCreateInput(subject = subject, description = "desc"))
      _ <- store.complete(sessionId, tid, by = "agent")
      _ <- store.dismiss(sessionId, tid)
    yield tid

  test("update on Dismissed task with status=completed is a no-op (returns existing, stays Dismissed)"):
    for
      _ <- reset()
      sid = "s1"
      tid <- mkDismissedTask(sid)
      result <- store.update(sid, tid, TaskUpdateInput(status = Some(TaskStatus.Completed)))
    yield
      assert(result.isDefined, "should return Some(existing)")
      assertEquals(result.get.status, TaskStatus.Dismissed, "status must stay Dismissed")

  test("update on Dismissed task with status=in_progress is a no-op"):
    for
      _ <- reset()
      sid = "s2"
      tid <- mkDismissedTask(sid)
      result <- store.update(sid, tid, TaskUpdateInput(status = Some(TaskStatus.InProgress)))
    yield assertEquals(result.get.status, TaskStatus.Dismissed)

  test("update on Dismissed task with non-status fields (subject/blocks) is a no-op"):
    for
      _ <- reset()
      sid = "s3"
      tid <- mkDismissedTask(sid, "original")
      result <- store.update(sid, tid, TaskUpdateInput(subject = Some("changed"), addBlocks = Some(List("x"))))
      reloaded <- store.get(sid, tid)
    yield
      assertEquals(result.get.subject, "original", "subject unchanged in return value")
      assertEquals(result.get.blocks, Nil, "blocks unchanged in return value")
      assertEquals(reloaded.get.subject, "original", "subject unchanged on disk")
      assertEquals(reloaded.get.blocks, Nil, "blocks unchanged on disk")

  test("normal status transitions are not affected"):
    for
      _ <- reset()
      sid = "s4"
      tid <- store.create(sid, TaskCreateInput(subject = "t", description = "d"))
      _ <- store.update(sid, tid, TaskUpdateInput(status = Some(TaskStatus.InProgress)))
      _ <- store.complete(sid, tid, by = "agent")
      completed <- store.get(sid, tid)
      _ <- store.dismiss(sid, tid)
      dismissed <- store.get(sid, tid)
    yield
      assertEquals(completed.get.status, TaskStatus.Completed)
      assertEquals(dismissed.get.status, TaskStatus.Dismissed)

  test("invalid transition on non-Dismissed task still raises"):
    for
      _ <- reset()
      sid = "s5"
      tid <- store.create(sid, TaskCreateInput(subject = "t", description = "d"))
      _ <- store.complete(sid, tid, by = "agent")
      // Completed -> InProgress is invalid
      result <- store
        .update(sid, tid, TaskUpdateInput(status = Some(TaskStatus.InProgress)))
        .attempt
    yield assert(result.isLeft, "invalid transition should still error for non-Dismissed tasks")
end TaskStoreDismissedSpec
