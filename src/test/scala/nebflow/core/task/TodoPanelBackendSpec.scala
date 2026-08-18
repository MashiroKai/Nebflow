package nebflow.core.task

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.parser.decode
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.PathUtil

/** todo-panel v1.1 backend contract (spec ~/.nebflow/docs/Nebflow/todo-panel-spec.md):
  * - §2.1 taskKind/completedBy with the withDefaults compatibility red line
  * - §2.2 human tasks have no in_progress state (isValidTransitionFor guard)
  * - §7.1 complete(sid, taskId, by) transition semantics for the WS circle
  */
class TodoPanelBackendSpec extends CatsEffectSuite:
  private val tempRoot: os.Path = os.pwd / "target" / "test-todo-panel"
  PathUtil.setDataRoot(tempRoot)

  private val store: TaskStore = FileTaskStore

  private def reset(): IO[Unit] =
    IO.delay { if os.exists(tempRoot) then os.remove.all(tempRoot) } *>
      IO.delay { os.makeDir.all(tempRoot) }

  private def mkTask(sessionId: String, kind: Option[String] = None): IO[String] =
    store.create(sessionId, TaskCreateInput(subject = "s", description = "d", taskKind = kind))

  // ── §2.1 codec compatibility ────────────────────────────────────────

  test("legacy task JSON (pre taskKind/completedBy) decodes with defaults"):
    val legacy =
      """{"id":"1","subject":"old","description":"legacy file","status":"pending"}"""
    decode[Task](legacy) match
      case Right(t) =>
        assertEquals(t.taskKind, "agent", "535+ legacy files must classify as agent (C1)")
        assertEquals(t.completedBy, None)
      case Left(err) => fail(s"legacy decode failed: $err}")

  test("task JSON round-trips taskKind and completedBy"):
    val t = Task(id = "1", subject = "s", description = "d", taskKind = "human",
      completedBy = Some("user"))
    decode[Task](t.asJson.noSpaces) match
      case Right(back) =>
        assertEquals(back.taskKind, "human")
        assertEquals(back.completedBy, Some("user"))
      case Left(err) => fail(s"round-trip failed: $err}")

  test("create normalizes taskKind: human/HUMAN pass through, junk and absent become agent"):
    for
      _ <- reset()
      sid = "codec-sid"
      h <- mkTask(sid, Some("human"))
      hc <- mkTask(sid, Some("HUMAN"))
      j <- mkTask(sid, Some("banana"))
      a <- mkTask(sid, None)
      tasks <- store.list(sid)
    yield
      val byId = tasks.map(t => t.id -> t).toMap
      assertEquals(byId(h).taskKind, "human")
      assertEquals(byId(hc).taskKind, "human", "case-insensitive")
      assertEquals(byId(j).taskKind, "agent", "unknown kinds never break the split")
      assertEquals(byId(a).taskKind, "agent", "absent defaults to agent")

  // ── §2.2 human transition guard ─────────────────────────────────────

  test("human task cannot be pushed to in_progress (agent path rejected)"):
    for
      _ <- reset()
      sid = "human-guard"
      tid <- mkTask(sid, Some("human"))
      r <- store.update(sid, tid, TaskUpdateInput(status = Some(TaskStatus.InProgress))).attempt
      after <- store.get(sid, tid)
    yield
      assert(r.isLeft, "human pending -> in_progress must be rejected")
      assertEquals(after.get.status, TaskStatus.Pending, "status unchanged after rejection")

  test("agent task transitions unaffected by the human guard"):
    for
      _ <- reset()
      sid = "agent-flow"
      tid <- mkTask(sid, Some("agent"))
      _ <- store.update(sid, tid, TaskUpdateInput(status = Some(TaskStatus.InProgress)))
      t <- store.get(sid, tid)
    yield assertEquals(t.get.status, TaskStatus.InProgress)

  // ── §7.1 complete() semantics ───────────────────────────────────────

  test("complete on pending human task records completedBy=user + completedAt + event"):
    for
      _ <- reset()
      sid = "c1"
      tid <- mkTask(sid, Some("human"))
      r <- store.complete(sid, tid, by = "user")
      reloaded <- store.get(sid, tid)
    yield
      assert(r.isDefined)
      assertEquals(reloaded.get.status, TaskStatus.Completed)
      assertEquals(reloaded.get.completedBy, Some("user"))
      assert(reloaded.get.completedAt.isDefined, "completedAt set on terminal entry")
      assert(
        reloaded.get.events.exists(e => e.kind == "status" && e.detail.exists(_.endsWith("→completed"))),
        "status event appended"
      )

  test("complete on in_progress agent task is legal (human acting on agent's behalf)"):
    for
      _ <- reset()
      sid = "c2"
      tid <- mkTask(sid, Some("agent"))
      _ <- store.update(sid, tid, TaskUpdateInput(status = Some(TaskStatus.InProgress)))
      r <- store.complete(sid, tid, by = "user")
    yield assertEquals(r.get.status, TaskStatus.Completed)

  test("complete on already-completed task is an idempotent no-op keeping original completedBy"):
    for
      _ <- reset()
      sid = "c3"
      tid <- mkTask(sid, Some("agent"))
      _ <- store.update(sid, tid, TaskUpdateInput(status = Some(TaskStatus.Completed)))
      r <- store.complete(sid, tid, by = "user").attempt
      after <- store.get(sid, tid)
    yield
      assert(r.isRight, "duplicate complete must not taskError-zombie the panel row")
      assertEquals(after.get.completedBy, None, "original agent completion preserved")

  test("complete on failed or dismissed task raises (terminal re-complete = taskError)"):
    for
      _ <- reset()
      sid = "c4"
      f <- mkTask(sid, None)
      _ <- store.update(sid, f, TaskUpdateInput(status = Some(TaskStatus.Failed)))
      d <- mkTask(sid, None)
      _ <- store.update(sid, d, TaskUpdateInput(status = Some(TaskStatus.Completed)))
      _ <- store.dismiss(sid, d)
      rf <- store.complete(sid, f, by = "user").attempt
      rd <- store.complete(sid, d, by = "user").attempt
    yield
      assert(rf.isLeft, "failed -> completed is illegal")
      assert(rd.isLeft, "dismissed -> completed is illegal")

  test("complete on missing task returns None (not-found error frame)"):
    for
      _ <- reset()
      r <- store.complete("c5", "999", by = "user")
    yield assert(r.isEmpty)

  // ── prompt rendering: agent must not sweep human todos into its loop ──

  test("renderForPrompt marks human pending tasks as waiting-user"):
    for
      _ <- reset()
      sid = "render"
      _ <- mkTask(sid, Some("human"))
      _ <- mkTask(sid, Some("agent"))
      text <- store.renderForPrompt(sid)
    yield
      assert(text.contains("[waiting-user]"), s"human task tagged; got:\n$text")
      assert(text.contains("[pending]"), "agent task still renders as pending")
end TodoPanelBackendSpec
