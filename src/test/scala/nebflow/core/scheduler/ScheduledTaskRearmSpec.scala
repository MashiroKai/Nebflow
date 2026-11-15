package nebflow.core.scheduler

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import munit.FunSuite

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * 数据安全收尾（2026-11-14）：rearm 的稳定身份、来源可证明的旧记录迁移、来源未知
 * 的明确拒绝、跨会话可恢复迁移，以及**真实底层写失败**（目录只读 ⇒ AtomicJson 的
 * 临时写入失败）。每个用例都走生产 `ScheduledTaskStore` / `AskTodoBridge`，不是帧桩。
 *
 * 来源判据（写进 spec，不靠猜测）：
 *  - kind=task 且带 name 只可能来自 ScheduleTool（UI 的定时任务创建不带 name）；
 *  - `ask-`/`perm-` 前缀只由 AskTodoBridge 分配；`weekly-report-` 只由周报生成器分配；
 *  - kind=todo 且带 name 的旧行无法区分工具 / 人建（待办 UI 会发 name）⇒ 拒绝。
 */
class ScheduledTaskRearmSpec extends FunSuite:

  private val future = System.currentTimeMillis() + 3_600_000L
  private val surface = ScheduledTask.nextTodoSurfaceAt(System.currentTimeMillis(), java.time.ZoneId.systemDefault())

  private def tempStore(): (os.Path, ScheduledTaskStore) =
    val root = os.Path(java.nio.file.Files.createTempDirectory("data-rearm-test"))
    (root, new ScheduledTaskStore(root))

  private def toolTask(id: String, session: String, content: String, name: String): ScheduledTask =
    ScheduledTask.create(session, content, future, name = Some(name)).copy(id = id, source = Some("tool"))

  test("FDC-SCN-014 rearm keeps the stored id and a repeated call converges to one record") {
    val (root, ledger) = tempStore()
    try
      val first = toolTask("stable-id", "s1", "v1", "routine")
      val result = (for
        _ <- ledger.addTask(first)
        r1 <- ledger.upsertTaskByName(toolTask("request-1", "s1", "v2", "routine"))
        r2 <- ledger.upsertTaskByName(toolTask("request-2", "s1", "v3", "routine"))
        rows <- ledger.loadTasks("s1")
      yield (r1, r2, rows)).unsafeRunSync()
      assertEquals(result._1.saved.id, "stable-id", "rearm must not mint a new identity")
      assertEquals(result._2.saved.id, "stable-id", "a repeated call keeps the same id")
      assertEquals(result._3.map(_.id), List("stable-id"), "no duplicate row is stacked")
      assertEquals(result._3.head.content, "v3")
      assertEquals(result._3.head.createdAt, first.createdAt, "the record's creation time is preserved")
    finally os.remove.all(root)
  }

  test("FDC-SCN-015 a failing low-level write leaves the stored record unchanged and a retry keeps its id") {
    val (root, ledger) = tempStore()
    try
      val first = toolTask("stable-id", "s1", "v1", "routine")
      ledger.addTask(first).unsafeRunSync()
      val file = root / "s1.json"
      val before = os.read(file)
      val original = Files.getPosixFilePermissions(root.toNIO)
      val readOnly = PosixFilePermissions.fromString("r-xr-xr-x")
      Files.setPosixFilePermissions(root.toNIO, readOnly)
      val blocked =
        try
          os.write.over(root / "probe.tmp", "probe")
          false
        catch case _: Exception => true
      try
        assume(blocked, "directory permissions do not block writes (running as root?) — injection unavailable")
        val failed = ledger.upsertTaskByName(toolTask("request", "s1", "v2", "routine")).attempt.unsafeRunSync()
        assert(failed.isLeft, s"the write failure must be reported, got $failed")
        assertEquals(os.read(file), before, "a failed atomic replace must leave the old file byte-for-byte intact")
        assertEquals(decodeRows(file).map(_.content), List("v1"))
      finally Files.setPosixFilePermissions(root.toNIO, original)
      val retry = ledger.upsertTaskByName(toolTask("request", "s1", "v2", "routine")).unsafeRunSync()
      assertEquals(retry.saved.id, "stable-id", "the retry keeps the same identity")
      assertEquals(ledger.loadTasks("s1").unsafeRunSync().map(_.content), List("v2"))
    finally os.remove.all(root)
  }

  test("FDC-SCN-016 a partial cross-session move converges on the next rearm without losing the record") {
    val (root, ledger) = tempStore()
    try
      val old = toolTask("stable-id", "old-session", "before", "routine")
      val result = (for
        _ <- ledger.addTask(old)
        moved <- ledger.upsertTaskByName(toolTask("request", "new-session", "after", "routine"))
        oldRows <- ledger.loadTasks("old-session")
        newRows <- ledger.loadTasks("new-session")
        // Reproduce the recoverable crash window: destination written, source removal not yet done.
        _ <- ledger.saveTasks("old-session", List(old))
        recovered <- ledger.upsertTaskByName(toolTask("request", "new-session", "after-2", "routine"))
        oldAfter <- ledger.loadTasks("old-session")
        newAfter <- ledger.loadTasks("new-session")
      yield (moved, oldRows, newRows, recovered, oldAfter, newAfter)).unsafeRunSync()
      assertEquals(result._1.saved.id, "stable-id", "the move keeps the record's id")
      assertEquals(result._1.saved.sessionId, "new-session")
      assertEquals(result._2, Nil)
      assertEquals(result._3.map(_.id), List("stable-id"))
      assertEquals(result._4.saved.id, "stable-id", "recovery keeps the same identity")
      assertEquals(result._5, Nil, "the duplicate in the old session is dropped on the next rearm")
      assertEquals(result._6.map(t => (t.id, t.content)), List(("stable-id", "after-2")), "exactly one record survives")
    finally os.remove.all(root)
  }

  test("FDC-SCN-017 a legacy named scheduled task is adopted as tool-owned without changing its id") {
    val (root, ledger) = tempStore()
    try
      val legacy = ScheduledTask.create("s1", "old", future, name = Some("routine"))
      assertEquals(legacy.source, None, "fixture: pre-source record")
      val result = (for
        _ <- ledger.addTask(legacy)
        rearm <- ledger.upsertTaskByName(toolTask("request", "s1", "new", "routine"), ScheduledTaskStore.toolOwnsLegacyNamedTask)
        rows <- ledger.loadTasks("s1")
      yield (rearm, rows)).unsafeRunSync()
      assertEquals(result._1.saved.id, legacy.id, "the legacy record is migrated, not replaced")
      assertEquals(result._2.map(_.id), List(legacy.id))
      assertEquals(result._2.head.source, Some("tool"), "the proven owner is recorded on the row")
    finally os.remove.all(root)
  }

  test("FDC-SCN-018 a legacy named todo is not attributable: kept, and the rearm writes nothing") {
    val (root, ledger) = tempStore()
    try
      val legacy = ScheduledTask.create("s1", "old", surface, name = Some("same"), kind = Some(ScheduledTask.KindTodo))
      ledger.addTask(legacy).unsafeRunSync()
      val file = root / "s1.json"
      val before = os.read(file)
      val candidate =
        ScheduledTask.create("s1", "new", surface, name = Some("same"), kind = Some(ScheduledTask.KindTodo))
          .copy(id = "request", source = Some("tool"))
      val failed = ledger.upsertTaskByName(candidate, ScheduledTaskStore.toolOwnsLegacyNamedTask).attempt.unsafeRunSync()
      assert(failed.isLeft, "an unprovable legacy owner must be refused, not guessed")
      assert(failed.left.toOption.exists(_.getMessage.contains(legacy.id)), s"refusal names the kept record: $failed")
      assertEquals(os.read(file), before, "refusal happens before any write")
      assertEquals(decodeRows(file).map(_.id), List(legacy.id))
    finally os.remove.all(root)
  }

  test("FDC-SCN-019 engine mirrors are adopted by reserved prefix and retired without touching other rows") {
    val (root, ledger) = tempStore()
    try
      val legacy = ScheduledTask.create("root", "old ask", surface, name = Some("ask-r1"), kind = Some(ScheduledTask.KindTodo))
      val user = ScheduledTask.create("root", "user note", surface, name = Some("ask-r1"), kind = Some(ScheduledTask.KindTodo))
        .copy(source = Some("user"))
      val frames = scala.collection.mutable.ListBuffer.empty[Json]
      val bridge = new AskTodoBridge(ledger, None, j => IO(frames += j))
      val result = (for
        _ <- ledger.addTask(legacy)
        _ <- ledger.addTask(user)
        _ <- bridge.attach("root", "r1", "Q?", "agent")
        _ <- bridge.attach("root", "r1", "Q?", "agent")
        afterAttach <- ledger.loadTasks("root")
        _ <- bridge.retire("r1")
        afterRetire <- ledger.loadTasks("root")
      yield (afterAttach, afterRetire)).unsafeRunSync()
      val engineRows = result._1.filter(_.source.contains("engine"))
      assertEquals(engineRows.map(_.id), List(legacy.id), "the legacy mirror is migrated in place, once")
      assert(result._1.exists(_.id == user.id), "an explicit other-source row with the same name is kept")
      assertEquals(result._2.map(_.id), List(user.id), "retire removes only engine-owned rows")
      val createdIds = frames.filter(_.hcursor.downField("type").as[String].toOption.contains("scheduledTaskCreated"))
        .flatMap(_.hcursor.downField("task").downField("id").as[String].toOption).toList
      assertEquals(createdIds.distinct, List(legacy.id), "created frames carry the stable id")
      assert(frames.exists(f => f.hcursor.downField("type").as[String].toOption.contains("scheduledTaskDeleted") &&
        f.hcursor.downField("id").as[String].toOption.contains(legacy.id)), "retire broadcasts the removed id")
    finally os.remove.all(root)
  }

  test("FDC-SCN-025 cancel and completion cover a duplicate copy left by a partial move") {
    val (root, ledger) = tempStore()
    try
      val old = toolTask("stable-id", "old-session", "v1", "routine").copy(triggerAt = 1L)
      val copy = old.copy(sessionId = "new-session")
      val result = (for
        _ <- ledger.addTask(old)
        _ <- ledger.addTask(copy)
        dueBefore <- ledger.getAllDueTasks
        pendingBefore <- ledger.getAllPendingTasks
        // A cancel issued against either session must remove the id everywhere, or the
        // survivor keeps firing after a partial cross-session move.
        _ <- ledger.deleteTask("old-session", "stable-id")
        afterCancelOld <- ledger.loadTasks("old-session")
        afterCancelNew <- ledger.loadTasks("new-session")
        _ <- ledger.addTask(old)
        _ <- ledger.addTask(copy)
        completed <- ledger.updateTask("old-session", "stable-id")(_.copy(completedAt = Some(1L)))
        completedOld <- ledger.loadTasks("old-session")
        completedNew <- ledger.loadTasks("new-session")
        visible <- ledger.getAllTodos
        _ <- ledger.markTriggered("old-session", "stable-id")
        firedOld <- ledger.loadTasks("old-session")
        firedNew <- ledger.loadTasks("new-session")
      yield (dueBefore, pendingBefore, afterCancelOld, afterCancelNew, completed, completedOld, completedNew, visible, firedOld, firedNew)).unsafeRunSync()
      assertEquals(result._1.map(_.id), List("stable-id"), "the due read collapses a duplicate to one row")
      assertEquals(result._2.map(_.id), List("stable-id"), "the pending read collapses a duplicate to one row")
      assertEquals(result._3, Nil)
      assertEquals(result._4, Nil, "cancel removes the duplicate copy too")
      assert(result._5)
      assertEquals((result._6 ++ result._7).map(_.completedAt), List(Some(1L)), "completion converges copies to one current row")
      assertEquals(result._8, Nil, "no copy keeps surfacing")
      assertEquals((result._9 ++ result._10).map(_.triggered), List(true), "a fired mark covers the surviving authority")
    finally os.remove.all(root)
  }

  // Restore the exact source bytes after target-first rearm, as if source cleanup failed.
  private def partialMove(root: os.Path, ledger: ScheduledTaskStore, old: ScheduledTask,
                          requested: ScheduledTask): ScheduledTask =
    ledger.addTask(old).unsafeRunSync()
    val before = os.read(root / s"${old.sessionId}.json")
    val saved = ledger.upsertTaskByName(requested).unsafeRunSync().saved
    os.write.over(root / s"${old.sessionId}.json", before)
    saved

  test("FDC-SCN-025 a future rearm wins before due filtering on every read face after restart") {
    val (root, ledger) = tempStore()
    try
      val old = toolTask("stable-id", "old-session", "stale due payload", "routine").copy(triggerAt = 1L)
      val saved = partialMove(root, ledger, old, toolTask("request", "new-session", "future payload", "routine"))
      val restarted = new ScheduledTaskStore(root)
      assertEquals(restarted.getAllDueTasks.unsafeRunSync(), Nil, "the stale due row must not fire and delete the future row")
      assertEquals(restarted.getAllPendingTasks.unsafeRunSync(), List(saved))
      assertEquals(restarted.findTaskById(old.id).unsafeRunSync(), Some(saved))
      assertEquals(restarted.loadTasks("old-session").unsafeRunSync(), Nil)
      assertEquals(restarted.loadTasks("new-session").unsafeRunSync(), List(saved))
      assertEquals(restarted.getPendingCount("old-session").unsafeRunSync(), 0)
      assertEquals(restarted.getPendingCount("new-session").unsafeRunSync(), 1)
      assertEquals(saved.id, old.id)
      assertEquals(saved.createdAt, old.createdAt)
      assertEquals(saved.source, old.source)
      assert(saved.revision > old.revision, "authority is persisted independently of trigger time")
      restarted.deleteTask("old-session", old.id).unsafeRunSync()
      assertEquals(decodeRows(root / "old-session.json"), Nil)
      assertEquals(decodeRows(root / "new-session.json"), Nil)
    finally os.remove.all(root)
  }

  test("FDC-SCN-025 an earlier rearm is authoritative, not the greatest trigger time") {
    val (root, ledger) = tempStore()
    try
      val old = toolTask("stable-id", "z-old", "future stale payload", "routine")
      val saved = partialMove(root, ledger, old, toolTask("request", "a-new", "due current payload", "routine").copy(triggerAt = 1L))
      val restarted = new ScheduledTaskStore(root)
      assertEquals(restarted.getAllDueTasks.unsafeRunSync(), List(saved))
      assertEquals(restarted.getAllPendingTasks.unsafeRunSync(), List(saved))
      assertEquals(restarted.findTaskById(old.id).unsafeRunSync(), Some(saved))
    finally os.remove.all(root)
  }

  test("FDC-SCN-025 todo filters and updates use the current payload, not stale copy state") {
    val (root, ledger) = tempStore()
    try
      val old = toolTask("stable-id", "old-session", "stale todo", "routine")
        .copy(kind = Some(ScheduledTask.KindTodo), triggerAt = 1L, enabled = false, missCount = 4)
      val current = partialMove(root, ledger, old, old.copy(id = "request", sessionId = "new-session",
        content = "current todo", triggerAt = future, enabled = true, missCount = 10, completedAt = Some(2L)))
      val restarted = new ScheduledTaskStore(root)
      assertEquals(restarted.getAllTodos.unsafeRunSync(), Nil)
      assertEquals(restarted.getAllDueTodos.unsafeRunSync(), Nil)
      assertEquals(restarted.getAllPendingTasks.unsafeRunSync(), Nil)
      assertEquals(restarted.getAllTodosIncludingCompleted.unsafeRunSync(), List(current))
      assertEquals(restarted.toggleTask("old-session", old.id).unsafeRunSync(), false)
      val toggled = restarted.findTaskById(old.id).unsafeRunSync().get
      assertEquals(toggled.content, current.content)
      assertEquals(toggled.enabled, false, "toggle must apply to current enabled=true exactly once")
      restarted.toggleTask("old-session", old.id).unsafeRunSync()
      var calls = 0
      restarted.updateTask("old-session", old.id) { task =>
        calls += 1
        task.copy(completedAt = None, missCount = task.missCount + 1)
      }.unsafeRunSync()
      assertEquals(calls, 1, "an id-scoped update transforms only the authoritative payload")
      val updated = restarted.findTaskById(old.id).unsafeRunSync().get
      assertEquals(updated.missCount, 11)
      assertEquals(updated.content, current.content)
      assertEquals(restarted.getAllTodos.unsafeRunSync(), List(updated))
      assertEquals(restarted.getAllDueTodos.unsafeRunSync(), Nil)
      restarted.rescheduleTask("old-session", old.id, 1L).unsafeRunSync()
      assertEquals(restarted.getAllDueTodos.unsafeRunSync().map(_.content), List(current.content))
      restarted.markTriggered("old-session", old.id).unsafeRunSync()
      assertEquals(restarted.getAllPendingTasks.unsafeRunSync(), Nil)
      assertEquals(restarted.getAllTodosIncludingCompleted.unsafeRunSync(), Nil)
    finally os.remove.all(root)
  }

  test("FDC-SCN-025 stale enabled todo never bypasses completion or trigger filters") {
    val (root, ledger) = tempStore()
    try
      val old = toolTask("stable-id", "old-session", "stale due todo", "routine")
        .copy(kind = Some(ScheduledTask.KindTodo), triggerAt = 1L)
      val current = partialMove(root, ledger, old, old.copy(id = "request", sessionId = "new-session",
        content = "completed current todo", triggerAt = future, completedAt = Some(2L)))
      val restarted = new ScheduledTaskStore(root)
      assertEquals(restarted.getAllTodos.unsafeRunSync(), Nil)
      assertEquals(restarted.getAllDueTodos.unsafeRunSync(), Nil)
      assertEquals(restarted.getAllTodosIncludingCompleted.unsafeRunSync(), List(current))
      assertEquals(restarted.getPendingCount("old-session").unsafeRunSync(), 0)
      restarted.markTriggered("old-session", old.id).unsafeRunSync()
      assertEquals(restarted.getAllTodosIncludingCompleted.unsafeRunSync(), Nil)
      assertEquals(restarted.findTaskById(old.id).unsafeRunSync().map(_.content), Some(current.content))
    finally os.remove.all(root)
  }

  test("FDC-SCN-025 conflicting unversioned copies never guess authority and remain cancellable") {
    val (root, ledger) = tempStore()
    try
      val old = toolTask("stable-id", "old-session", "due old", "routine").copy(triggerAt = 1L)
      ledger.addTask(old).unsafeRunSync()
      ledger.addTask(old.copy(sessionId = "new-session", content = "future new", triggerAt = future)).unsafeRunSync()
      assertEquals(ledger.getAllDueTasks.unsafeRunSync(), Nil)
      assertEquals(ledger.getAllPendingTasks.unsafeRunSync(), Nil)
      assertEquals(ledger.findTaskById(old.id).unsafeRunSync(), None)
      assertEquals(ledger.loadTasks("old-session").unsafeRunSync(), Nil)
      val before = os.read(root / "old-session.json")
      assert(ledger.updateTask("old-session", old.id)(_.copy(enabled = false)).attempt.unsafeRunSync().isLeft)
      assertEquals(os.read(root / "old-session.json"), before)
      ledger.deleteTask("new-session", old.id).unsafeRunSync()
      assertEquals(decodeRows(root / "old-session.json"), Nil)
      assertEquals(decodeRows(root / "new-session.json"), Nil)
    finally os.remove.all(root)
  }

  test("FDC-SCN-025 legacy revision defaults and ambiguous copies recover through explicit rearm") {
    val (root, ledger) = tempStore()
    try
      val legacy = """{"id":"stable-id","sessionId":"old-session","content":"old","triggerAt":1,"createdAt":2,"name":"routine","source":"tool"}"""
      val decoded = io.circe.parser.decode[ScheduledTask](legacy).toOption.get
      assertEquals(decoded.revision, 0L)
      ledger.addTask(decoded).unsafeRunSync()
      ledger.addTask(decoded.copy(sessionId = "new-session", content = "other", triggerAt = future)).unsafeRunSync()
      val recovered = ledger.upsertTaskByName(toolTask("request", "new-session", "chosen", "routine")).unsafeRunSync()
      assertEquals(recovered.saved.id, decoded.id)
      assertEquals(recovered.saved.createdAt, decoded.createdAt)
      assertEquals(recovered.saved.revision, 1L)
      assertEquals(ledger.getAllPendingTasks.unsafeRunSync(), List(recovered.saved))
      assertEquals(decodeRows(root / "old-session.json"), Nil)
    finally os.remove.all(root)
  }

  private def decodeRows(file: os.Path): List[ScheduledTask] =
    io.circe.parser.decode[List[ScheduledTask]](os.read(file)).getOrElse(fail(s"unreadable: $file"))
end ScheduledTaskRearmSpec
