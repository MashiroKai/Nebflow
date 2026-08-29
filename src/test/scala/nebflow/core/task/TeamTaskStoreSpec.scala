package nebflow.core.task

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.parser.decode
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.PathUtil

/**
 * Team Manager task store (#D, spec 20260825_team-manager-task-tool-spec.md
 * §2.3/§2.4/§4.2):
 * - four-state team matrix: all 16 cells (legal/illegal transitions)
 * - block semantics: cycle rejection, dependency fields, delete-reference cleanup
 * - directory isolation: session vs team, team A vs team B, independent hwm
 * - backward compat: legacy session JSON (no scope field) decodes to "session",
 *   session five-state behavior untouched
 * - scope/teamId metadata stamped on team-domain create
 */
class TeamTaskStoreSpec extends CatsEffectSuite:
  private var tempRoot: os.Path = null
  private var savedRoot: os.Path = null

  // beforeEach/afterEach (NOT class-init redirect): class-init setDataRoot
  // without restore pollutes later suites in the same JVM (e.g. FlowExecute
  // tool validation resolves the live agent library from PathUtil.dataRoot —
  // a stale temp root with no agents dir breaks it).
  override def beforeEach(context: munit.BeforeEach): Unit =
    savedRoot = PathUtil.dataRoot
    tempRoot = os.Path(java.nio.file.Files.createTempDirectory("nb-teamtask-store").toString)
    PathUtil.setDataRoot(tempRoot)

  override def afterEach(context: munit.AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    if os.exists(tempRoot) then os.remove.all(tempRoot)

  private val store: TaskStore = FileTaskStore

  /** beforeEach already provides a fresh tempRoot; reset() wipes it again for
    * tests that want a blank slate mid-test (kept for readability). */
  private def reset(): IO[Unit] =
    IO.delay { if os.exists(tempRoot) then os.remove.all(tempRoot) } *>
      IO.delay { os.makeDir.all(tempRoot) }

  private def teamKey(name: String): String = TaskStore.teamScopeKey(name)

  private def mkTeamTask(teamName: String, subject: String = "s"): IO[String] =
    store.create(teamKey(teamName), TaskCreateInput(subject = subject, description = "d"))

  private def updateTo(scopeKey: String, id: String, status: TaskStatus): IO[Option[Task]] =
    store.update(scopeKey, id, TaskUpdateInput(status = Some(status)))

  // ── §2.3 four-state matrix: all 16 cells ────────────────────────────

  test("pending → {pending, in_progress, completed, failed} all legal"):
    for
      _ <- reset()
      key = teamKey("mtx-pending-ok")
      id1 <- mkTeamTask("mtx-pending-ok")
      r1 <- updateTo(key, id1, TaskStatus.Pending) // no-op
      id2 <- mkTeamTask("mtx-pending-ok")
      r2 <- updateTo(key, id2, TaskStatus.InProgress)
      id3 <- mkTeamTask("mtx-pending-ok")
      r3 <- updateTo(key, id3, TaskStatus.Completed)
      id4 <- mkTeamTask("mtx-pending-ok")
      r4 <- updateTo(key, id4, TaskStatus.Failed)
    yield
      assert(r1.map(_.status).contains(TaskStatus.Pending))
      assert(r2.map(_.status).contains(TaskStatus.InProgress))
      assert(r3.map(_.status).contains(TaskStatus.Completed))
      assert(r4.map(_.status).contains(TaskStatus.Failed))

  test("in_progress → {in_progress no-op, completed, failed} legal; → pending rejected"):
    for
      _ <- reset()
      key = teamKey("mtx-inprog")
      id <- mkTeamTask("mtx-inprog")
      _ <- updateTo(key, id, TaskStatus.InProgress)
      noop <- updateTo(key, id, TaskStatus.InProgress)
      done <- updateTo(key, id, TaskStatus.Completed)
      bad <- store.update(key, id, TaskUpdateInput(status = Some(TaskStatus.Pending))).attempt
    yield
      assert(noop.map(_.status).contains(TaskStatus.InProgress))
      assert(done.map(_.status).contains(TaskStatus.Completed))
      assert(bad.isLeft, "in_progress → pending must be rejected")

  test("completed is terminal: only no-op; → pending/in_progress/failed rejected"):
    for
      _ <- reset()
      key = teamKey("mtx-completed")
      id <- mkTeamTask("mtx-completed")
      _ <- updateTo(key, id, TaskStatus.Completed)
      noop <- updateTo(key, id, TaskStatus.Completed)
      r1 <- updateTo(key, id, TaskStatus.Pending).attempt
      r2 <- updateTo(key, id, TaskStatus.InProgress).attempt
      r3 <- updateTo(key, id, TaskStatus.Failed).attempt
      t <- store.get(key, id)
    yield
      assert(noop.map(_.status).contains(TaskStatus.Completed))
      assert(r1.isLeft && r2.isLeft && r3.isLeft, "completed must not transition to any non-completed state")
      assertEquals(t.map(_.status), Some(TaskStatus.Completed), "status unchanged after rejected transitions")

  test("failed is terminal: only no-op; → pending/in_progress/completed rejected"):
    for
      _ <- reset()
      key = teamKey("mtx-failed")
      id <- mkTeamTask("mtx-failed")
      _ <- updateTo(key, id, TaskStatus.Failed)
      noop <- updateTo(key, id, TaskStatus.Failed)
      r1 <- updateTo(key, id, TaskStatus.Pending).attempt
      r2 <- updateTo(key, id, TaskStatus.InProgress).attempt
      r3 <- updateTo(key, id, TaskStatus.Completed).attempt
    yield
      assert(noop.map(_.status).contains(TaskStatus.Failed))
      assert(r1.isLeft && r2.isLeft && r3.isLeft, "failed must not transition to any non-failed state")

  test("legacy status wire names decode leniently (任务工具重做: needs_confirmation→completed, dismissed/cancelled→failed)"):
    for
      _ <- reset()
      key = teamKey("legacy-decode")
      dir = tempRoot / "tasks" / "teams" / "legacy-decode"
      _ = os.makeDir.all(dir)
      _ = os.write(dir / "1.json", """{"id":"1","subject":"nc","description":"d","status":"needs_confirmation","events":[]}""")
      _ = os.write(dir / "2.json", """{"id":"2","subject":"d","description":"d","status":"dismissed","events":[]}""")
      _ = os.write(dir / "3.json", """{"id":"3","subject":"c","description":"d","status":"cancelled","events":[]}""")
      nc <- store.get(key, "1")
      dm <- store.get(key, "2")
      cx <- store.get(key, "3")
    yield
      assertEquals(nc.map(_.status), Some(TaskStatus.Completed), "needs_confirmation → completed")
      assertEquals(dm.map(_.status), Some(TaskStatus.Failed), "dismissed → failed")
      assertEquals(cx.map(_.status), Some(TaskStatus.Failed), "cancelled → failed")

  test("created team task carries scope=team + teamId"):
    for
      _ <- reset()
      id <- mkTeamTask("meta-team")
      t <- store.get(teamKey("meta-team"), id)
    yield
      assertEquals(t.map(_.scope), Some("team"))
      assertEquals(t.map(_.teamId), Some(Some("meta-team")))

  test("session-domain created task keeps scope=session + teamId=None"):
    for
      _ <- reset()
      id <- store.create("sess-meta", TaskCreateInput(subject = "s", description = "d"))
      t <- store.get("sess-meta", id)
    yield
      assertEquals(t.map(_.scope), Some("session"))
      assertEquals(t.map(_.teamId), Some(None))

  // ── §2.4 block semantics ────────────────────────────────────────────

  test("addBlockedBy cycle is rejected (DFS over same-team set)"):
    for
      _ <- reset()
      key = teamKey("block-cycle")
      id1 <- mkTeamTask("block-cycle", "t1")
      id2 <- mkTeamTask("block-cycle", "t2")
      _ <- store.update(key, id2, TaskUpdateInput(addBlockedBy = Some(List(id1))))
      cyc <- store.update(key, id1, TaskUpdateInput(addBlockedBy = Some(List(id2)))).attempt
      t1 <- store.get(key, id1)
      t2 <- store.get(key, id2)
    yield
      assert(cyc.isLeft, "1 blockedBy 2 + 2 blockedBy 1 must be a cycle")
      assert(cyc.swap.toOption.get.getMessage.contains("cycle"))
      assertEquals(t1.map(_.blockedBy), Some(Nil), "rejected update must not persist")
      assertEquals(t2.map(_.blockedBy), Some(List(id1)), "original dependency intact")

  test("dependency fields populate correctly (blockedBy via addBlockedBy, blocks via addBlocks)"):
    // Store semantics (session domain 原样复用): addBlockedBy writes ONLY the
    // target task's blockedBy; the blocks mirror on the other task is the
    // CALLER's job (addBlocks) — TeamTaskUpdate exposes both directions.
    for
      _ <- reset()
      key = teamKey("block-fields")
      id1 <- mkTeamTask("block-fields", "t1")
      id2 <- mkTeamTask("block-fields", "t2")
      _ <- store.update(key, id2, TaskUpdateInput(addBlockedBy = Some(List(id1))))
      _ <- store.update(key, id1, TaskUpdateInput(addBlocks = Some(List(id2))))
      t1 <- store.get(key, id1)
      t2 <- store.get(key, id2)
      _ <- store.update(key, id2, TaskUpdateInput(removeBlockedBy = Some(List(id1))))
      _ <- store.update(key, id1, TaskUpdateInput(removeBlocks = Some(List(id2))))
      t2b <- store.get(key, id2)
      t1b <- store.get(key, id1)
    yield
      assertEquals(t2.map(_.blockedBy), Some(List(id1)))
      assertEquals(t1.map(_.blocks), Some(List(id2)))
      assertEquals(t2b.map(_.blockedBy), Some(Nil), "removeBlockedBy clears")
      assertEquals(t1b.map(_.blocks), Some(Nil), "removeBlocks clears")

  test("delete cleans references from other team tasks (spec §2.4)"):
    for
      _ <- reset()
      key = teamKey("block-delete")
      id1 <- mkTeamTask("block-delete", "t1")
      id2 <- mkTeamTask("block-delete", "t2")
      _ <- store.update(key, id2, TaskUpdateInput(addBlockedBy = Some(List(id1))))
      deleted <- store.delete(key, id1)
      t2 <- store.get(key, id2)
    yield
      assert(deleted)
      assertEquals(t2.map(_.blockedBy), Some(Nil), "deleted id removed from blockedBy")

  // ── §4.2 directory isolation ────────────────────────────────────────

  test("session tasks and team tasks are isolated (same name ≠ same dir)"):
    for
      _ <- reset()
      sid = "sessiso"
      team = "sessiso" // team named exactly like the session id — must not collide
      st <- store.create(sid, TaskCreateInput(subject = "session task", description = "d"))
      tt <- mkTeamTask(team, "team task")
      sl <- store.list(sid)
      tl <- store.list(teamKey(team))
    yield
      assertEquals(sl.map(_.subject), List("session task"))
      assertEquals(tl.map(_.subject), List("team task"))
      assertEquals(sl.headOption.map(_.scope), Some("session"))
      assertEquals(tl.headOption.map(_.scope), Some("team"))

  test("team A and team B are isolated with independent hwm"):
    for
      _ <- reset()
      a1 <- mkTeamTask("team-a", "a-first")
      b1 <- mkTeamTask("team-b", "b-first")
      b2 <- mkTeamTask("team-b", "b-second")
      la <- store.list(teamKey("team-a"))
      lb <- store.list(teamKey("team-b"))
    yield
      assertEquals(a1, "1", "team A hwm starts at 1")
      assertEquals(b1, "1", "team B hwm starts at 1 independently")
      assertEquals(b2, "2", "team B hwm advances independently")
      assertEquals(la.map(_.id), List("1"))
      assertEquals(lb.map(_.id), List("1", "2"))
      assertEquals(la.map(_.subject), List("a-first"))

  // ── backward compat (§4.1 red line) ─────────────────────────────────

  test("legacy session JSON without scope/teamId decodes to scope=session, four-state intact"):
    for
      _ <- reset()
      sid = "legacy-sess"
      dir = tempRoot / "tasks" / sid
      _ = os.makeDir.all(dir)
      _ = os.write(
        dir / "1.json",
        """{"id":"1","subject":"legacy","description":"d","status":"pending","blocks":[],"blockedBy":[],"createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z","events":[]}"""
      )
      t <- store.get(sid, "1")
      // four-state path: pending → in_progress → completed (agent marks directly)
      _ <- store.update(sid, "1", TaskUpdateInput(status = Some(TaskStatus.InProgress)))
      _ <- store.complete(sid, "1", "agent")
      t2 <- store.get(sid, "1")
    yield
      assertEquals(t.map(_.scope), Some("session"), "absent scope key decodes to 'session'")
      assertEquals(t.map(_.teamId), Some(None))
      assertEquals(t2.map(_.status), Some(TaskStatus.Completed))

  test("unsafe team scope keys are rejected by the store (path traversal guard)"):
    for
      _ <- reset()
      r1 <- store.list("team:../evil").attempt
      r2 <- store.list("team:").attempt
      r3 <- store.list("team:a/b").attempt
    yield
      assert(r1.isLeft, ".. traversal rejected")
      assert(r2.isLeft, "empty team name rejected")
      assert(r3.isLeft, "slash rejected")

end TeamTaskStoreSpec
