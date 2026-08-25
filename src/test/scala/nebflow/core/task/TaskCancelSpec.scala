package nebflow.core.task

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.parser.decode
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.PathUtil

/** task-cancel #35 backend contract (spec: 任务取消机制 — 前端 taskList.js
  * 契约#1-#4):
  * - §1 cancelled is a terminal state reachable from the three active sources
  *   (pending / in_progress / needs_confirmation); no out-edges
  * - §2 cancel() (user path, WS cancelTask): human todos rejected, terminal
  *   sources rejected, idempotent re-cancel, cancelReason persisted
  * - §3 agent path (TaskUpdate status=cancelled) requires a reason note
  * - §4 archive index carries cancelReason (taskArchive.js 契约#4)
  */
class TaskCancelSpec extends CatsEffectSuite:
  private var tempRoot: os.Path = null
  private var savedRoot: os.Path = null

  // beforeEach/afterEach (not class-init redirect): a class-init
  // PathUtil.setDataRoot without restore pollutes later suites in the same
  // JVM — e.g. FlowExecuteToolSpec resolves the live agent library from
  // PathUtil.dataRoot; a stale temp root (no agents dir) makes it report
  // "agent 'Nebula' not found" instead of reaching its spawn gate.
  override def beforeEach(context: munit.BeforeEach): Unit =
    savedRoot = PathUtil.dataRoot
    tempRoot = os.Path(java.nio.file.Files.createTempDirectory("nb-task-cancel").toString)
    PathUtil.setDataRoot(tempRoot)

  override def afterEach(context: munit.AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    if os.exists(tempRoot) then os.remove.all(tempRoot)

  private val store: TaskStore = FileTaskStore

  private def reset(): IO[Unit] =
    IO.delay { if os.exists(tempRoot) then os.remove.all(tempRoot) } *>
      IO.delay { os.makeDir.all(tempRoot) }

  private def mkTask(sessionId: String, kind: Option[String] = None): IO[String] =
    store.create(sessionId, TaskCreateInput(subject = "s", description = "d", taskKind = kind))

  private def mkInProgress(sessionId: String): IO[String] =
    for
      tid <- mkTask(sessionId)
      _ <- store.update(sessionId, tid, TaskUpdateInput(status = Some(TaskStatus.InProgress)))
    yield tid

  private def mkNeedsConfirmation(sessionId: String): IO[String] =
    for
      tid <- mkTask(sessionId)
      _ <- store.update(
        sessionId,
        tid,
        TaskUpdateInput(status = Some(TaskStatus.NeedsConfirmation), note = Some("done"))
      )
    yield tid

  // ── §1 state machine: three active sources → cancelled ───────────────

  test("pending -> cancelled is legal (user cancel path)"):
    for
      _ <- reset()
      sid = "cancel-pending"
      tid <- mkTask(sid)
      r <- store.cancel(sid, tid, "user changed mind")
      t <- store.get(sid, tid)
    yield
      assertEquals(r.map(_.status), Some(TaskStatus.Cancelled))
      assertEquals(t.map(_.status), Some(TaskStatus.Cancelled))

  test("in_progress -> cancelled is legal (user cancel path)"):
    for
      _ <- reset()
      sid = "cancel-inprog"
      tid <- mkInProgress(sid)
      r <- store.cancel(sid, tid, "user changed mind")
    yield assertEquals(r.map(_.status), Some(TaskStatus.Cancelled))

  test("needs_confirmation -> cancelled is legal (user cancel path)"):
    for
      _ <- reset()
      sid = "cancel-needsc"
      tid <- mkNeedsConfirmation(sid)
      r <- store.cancel(sid, tid, "user changed mind")
    yield assertEquals(r.map(_.status), Some(TaskStatus.Cancelled))

  test("cancelled is terminal: no out-edges (agent update rejected)"):
    for
      _ <- reset()
      sid = "cancel-terminal"
      tid <- mkTask(sid)
      _ <- store.cancel(sid, tid, "stop")
      r1 <- store.update(sid, tid, TaskUpdateInput(status = Some(TaskStatus.InProgress))).attempt
      r2 <- store.update(sid, tid, TaskUpdateInput(status = Some(TaskStatus.Completed), note = Some("x"))).attempt
      r3 <- store.complete(sid, tid, "user").attempt
      r4 <- store.dismiss(sid, tid).attempt
      t <- store.get(sid, tid)
    yield
      assert(r1.isLeft, "cancelled -> in_progress must be rejected")
      assert(r2.isLeft, "cancelled -> completed must be rejected")
      assert(r3.isLeft, "complete() on cancelled must be rejected")
      assert(r4.isLeft, "dismiss on cancelled must be rejected")
      assertEquals(t.map(_.status), Some(TaskStatus.Cancelled), "status unchanged after rejected transitions")

  // ── §2 cancel() guards & persistence ─────────────────────────────────

  test("terminal sources cannot be cancelled: completed/failed/dismissed rejected"):
    for
      _ <- reset()
      sid = "cancel-terminal-src"
      c <- mkTask(sid)
      f <- mkTask(sid)
      d <- mkTask(sid)
      _ <- store.complete(sid, c, "user")
      _ <- store.update(sid, f, TaskUpdateInput(status = Some(TaskStatus.Failed)))
      _ <- store.complete(sid, d, "user") *> store.dismiss(sid, d)
      rc <- store.cancel(sid, c, "x").attempt
      rf <- store.cancel(sid, f, "x").attempt
      rd <- store.cancel(sid, d, "x").attempt
    yield
      assert(rc.isLeft, "completed -> cancelled must be rejected")
      assert(rf.isLeft, "failed -> cancelled must be rejected")
      assert(rd.isLeft, "dismissed -> cancelled must be rejected")

  test("human todo cannot be cancelled (frontend isCancellable is agent-only)"):
    for
      _ <- reset()
      sid = "cancel-human"
      hid <- mkTask(sid, Some("human"))
      r <- store.cancel(sid, hid, "x").attempt
      t <- store.get(sid, hid)
    yield
      assert(r.isLeft, "human todo cancel must be rejected")
      assertEquals(t.map(_.status), Some(TaskStatus.Pending))

  test("re-cancel on already-cancelled is an idempotent no-op"):
    for
      _ <- reset()
      sid = "cancel-idempotent"
      tid <- mkTask(sid)
      first <- store.cancel(sid, tid, "first reason")
      second <- store.cancel(sid, tid, "second reason")
      t <- store.get(sid, tid)
    yield
      assertEquals(second.map(_.status), Some(TaskStatus.Cancelled))
      assertEquals(t.flatMap(_.cancelReason), Some("first reason"), "original cancelReason preserved")

  test("cancel persists cancelReason + completedAt + kind=cancel note + status event"):
    for
      _ <- reset()
      sid = "cancel-persist"
      tid <- mkInProgress(sid)
      _ <- store.cancel(sid, tid, "   user changed mind  ")
      t <- store.get(sid, tid)
      reloaded <- store.get(sid, tid)
    yield
      val task = t.get
      assertEquals(task.status, TaskStatus.Cancelled)
      assertEquals(task.cancelReason, Some("user changed mind"), "reason trimmed")
      assert(task.completedAt.isDefined, "terminal entry stamps completedAt")
      assert(task.notes.exists(n => n.kind.contains("cancel") && n.content.contains("user changed mind")),
        "cancel reason recorded as kind=cancel note (archive 契约#4)")
      assert(task.events.exists(_.detail.contains("in_progress→cancelled")), "status event recorded")
      assertEquals(reloaded.flatMap(_.cancelReason), Some("user changed mind"), "persisted to disk (reload)")

  test("blank cancel reason falls back to a default"):
    for
      _ <- reset()
      sid = "cancel-blank"
      tid <- mkTask(sid)
      _ <- store.cancel(sid, tid, "   ")
      t <- store.get(sid, tid)
    yield assertEquals(t.flatMap(_.cancelReason), Some("cancelled by user"))

  test("cancel unknown task returns None"):
    for
      _ <- reset()
      sid = "cancel-missing"
      r <- store.cancel(sid, "999", "x")
    yield assertEquals(r, None)

  // ── §3 agent path (TaskUpdate status=cancelled) requires reason note ─

  test("agent TaskUpdate to cancelled without note is rejected (anti-abuse)"):
    for
      _ <- reset()
      sid = "cancel-agent-note"
      tid <- mkInProgress(sid)
      r <- store.update(sid, tid, TaskUpdateInput(status = Some(TaskStatus.Cancelled))).attempt
      t <- store.get(sid, tid)
    yield
      assert(r.isLeft, "cancelled without note must be rejected")
      assertEquals(t.map(_.status), Some(TaskStatus.InProgress), "task unchanged after rejection")

  test("agent TaskUpdate to cancelled with note succeeds (reason lives in notes)"):
    for
      _ <- reset()
      sid = "cancel-agent-ok"
      tid <- mkInProgress(sid)
      r <- store.update(
        sid,
        tid,
        TaskUpdateInput(status = Some(TaskStatus.Cancelled), note = Some("scope dropped by planner"))
      )
      t <- store.get(sid, tid)
    yield
      assertEquals(r.map(_.status), Some(TaskStatus.Cancelled))
      val task = t.get
      assert(task.notes.exists(_.content.contains("scope dropped")), "reason note recorded")
      assertEquals(task.cancelReason, None, "agent path does not set cancelReason (archive falls back to notes)")
      assert(task.completedAt.isDefined, "agent-path cancel also stamps terminal time")

  // ── §4 archive index carries cancelReason (taskArchive.js 契约#4) ────

  test("archive index entry carries cancelReason for cancelled tasks"):
    for
      _ <- reset()
      sid = "cancel-archive"
      tid <- mkTask(sid)
      _ <- store.cancel(sid, tid, "reprioritized")
      _ <- TaskArchive.refreshSession(sid, _ => TaskArchive.Unclassified)
      entries <- TaskArchive.loadIndex()
    yield
      val e = entries.find(e => e.sessionId == sid && e.taskId == tid)
      assert(e.isDefined, "cancelled task must be in the archive index")
      assertEquals(e.map(_.status), Some("cancelled"))
      assertEquals(e.flatMap(_.cancelReason), Some("reprioritized"))

  test("cancelled tasks are excluded from listVisible (panel convergence)"):
    for
      _ <- reset()
      sid = "cancel-listvisible"
      a <- mkTask(sid)
      b <- mkTask(sid)
      _ <- store.cancel(sid, a, "stop")
      visible <- store.listVisible(sid)
      all <- store.list(sid)
    yield
      assert(!visible.exists(_.id == a), "cancelled must not surface in the panel list")
      assert(visible.exists(_.id == b), "active tasks unaffected")
      assertEquals(all.map(_.id).toSet.size, 2, "cancelled task file still on disk (archive keeps it)")

  // ── codec compatibility ─────────────────────────────────────────────

  test("legacy task JSON (pre cancelReason/note-kind) decodes with defaults"):
    val legacy =
      """{"id":"1","subject":"old","description":"legacy file","status":"pending"}"""
    decode[Task](legacy) match
      case Right(t) =>
        assertEquals(t.cancelReason, None)
        assert(t.notes.forall(_.kind.isEmpty))
      case Left(err) => fail(s"legacy decode failed: $err")

  test("cancelled task JSON round-trips cancelReason"):
    val t = Task(id = "1", subject = "s", description = "d", status = TaskStatus.Cancelled,
      cancelReason = Some("user changed mind"))
    decode[Task](t.asJson.noSpaces) match
      case Right(back) =>
        assertEquals(back.status, TaskStatus.Cancelled)
        assertEquals(back.cancelReason, Some("user changed mind"))
      case Left(err) => fail(s"round-trip failed: $err")

end TaskCancelSpec
