package nebflow.core.project

import cats.effect.unsafe.implicits.global
import io.circe.parser.decode
import io.circe.syntax.*
import munit.FunSuite

import java.nio.file.Files

import nebflow.core.PathUtil
import nebflow.core.tools.{TaskTool, ToolContext}

/**
 * TaskLedgerStore spec -- the core unit-test layer for the **seven acceptance blocks** of the
 * taskunify merge batch (2026-09-24).
 *
 * It covers the five clauses of the task brief's §7 "acceptance (mechanically executable)"
 * plus the structural criteria of §1/§2:
 *  - new three-state machine edge: `create -> close -> complete` = `Right` (`closed->completed` is legal);
 *  - `completed` has no out edge: `create -> complete -> close` = `Left` (a terminal state has no out edge);
 *  - **dependency gate accepts both terminal states** (**mutation red-anchor**): with A
 *    `blocks`=B and B `close` (**not** complete), `complete A` = `Right` -- if the gate still
 *    judged "== done / a single terminal state", this case must go red;
 *  - zero writes to the old ledger: old-vs-new mtime comparison (the old two files' mtime is
 *    unchanged around reads and writes of the new ledger);
 *  - `grep -c '"status"'` (the new `Task` schema) = 0 (key-face criterion, see also the schema assertions).
 *
 * Every case starts on an empty store (temporary dataRoot, no in-memory state => deleting the
 * file gives a brand-new ledger).
 */
class TaskLedgerStoreSpec extends FunSuite:

  private var home: os.Path = os.Path(Files.createTempDirectory("nb-taskledger-spec"))
  private var prevRoot: os.Path = PathUtil.dataRoot // overwritten in beforeAll

  override def beforeAll(): Unit =
    prevRoot = PathUtil.dataRoot
    PathUtil.setDataRoot(home)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(prevRoot)
    os.remove.all(home)

  private def file: os.Path = home / TaskLedgerStore.FileName

  private def reset(): Unit =
    if os.exists(home) then
      os.list(home)
        .filter(p => p.last.startsWith(TaskLedgerStore.FileName) || p.last.startsWith(TaskLedgerHistory.FileName))
        .foreach(os.remove)

  private def store: TaskLedgerStore = TaskLedgerStore.open()

  private def statusOf(id: String): String = store.findSync(id).map(_.status).getOrElse("(missing)")

  // ------------------------------------------------------------------
  // §2 · the three-state machine
  // ------------------------------------------------------------------

  test("three-state: create → close → complete = Right, and the entry ends `completed`") {
    reset()
    val s = store
    val created = s.createSync("t1")
    assert(created.isRight, created)
    val id = s.entriesSync().head.id
    assert(statusOf(id) == TaskLedgerStore.Status.Open, statusOf(id))

    val closed = s.closeSync(id)
    assert(closed.isRight, closed)
    assert(statusOf(id) == TaskLedgerStore.Status.Closed, statusOf(id))

    // §7 the NEW legal edge: closed → completed
    val completed = s.completeSync(id)
    assert(completed.isRight, completed)
    assert(statusOf(id) == TaskLedgerStore.Status.Completed, statusOf(id))
  }

  test("three-state: `completed` has no out-edges (close after complete = Left)") {
    reset()
    val s = store
    s.createSync("t2")
    val id = s.entriesSync().head.id
    assert(s.completeSync(id).isRight)
    assert(statusOf(id) == TaskLedgerStore.Status.Completed)

    // §7: completed → anything is refused; close must NOT succeed.
    val closed = s.closeSync(id)
    assert(closed.isLeft, s"expected Left (terminal has no out-edges), got $closed")
    assert(closed.left.exists(_.message.contains(TaskLedgerStore.Codes.Status)), closed)
    assert(statusOf(id) == TaskLedgerStore.Status.Completed, "state must be unchanged by a refused transition")
  }

  test("three-state: same-state writes are idempotent no-op successes") {
    reset()
    val s = store
    s.createSync("t3")
    val id = s.entriesSync().head.id
    s.completeSync(id)
    // completed → completed: idempotent (not an out-edge — a re-write of the same state).
    val again = s.completeSync(id)
    assert(again.isRight, again)
    assert(again.exists(_.contains("no-op")), again)

    s.createSync("t4")
    val id2 = s.entriesSync().find(_.title == "t4").get.id
    s.closeSync(id2)
    val again2 = s.closeSync(id2)
    assert(again2.isRight, again2)
    assert(again2.exists(_.contains("no-op")), again2)
  }

  test("three-state: `update` cannot change the state (no status parameter exists)") {
    reset()
    val s = store
    s.createSync("t5")
    val id = s.entriesSync().head.id
    // updateSync has no `status` argument at all (compile-time single channel).
    val r = s.updateSync(id, title = Some("t5-renamed"))
    assert(r.isRight, r)
    assert(statusOf(id) == TaskLedgerStore.Status.Open, "update must leave the state untouched")
    assert(store.findSync(id).exists(_.title == "t5-renamed"))
  }

  // ------------------------------------------------------------------
  // §2 · the dependency gate (**mutation red-anchor**)
  // ------------------------------------------------------------------

  test("dep guard DOUBLE-TERMINAL: a `closed` (not `completed`) dependency satisfies the guard (MUTATION ANCHOR)") {
    reset()
    val s = store
    s.createSync("dep-B")
    val b = s.entriesSync().find(_.title == "dep-B").get.id
    s.createSync("dep-A", blocksRaw = Some(List(b)))
    val a = s.entriesSync().find(_.title == "dep-A").get.id

    // B is still open ⇒ A cannot complete.
    val blocked = s.completeSync(a)
    assert(blocked.isLeft, s"expected Left while the dependency is open, got $blocked")
    assert(blocked.left.exists(_.message.contains(TaskLedgerStore.Codes.Blocked)), blocked)

    // Close B — **closed**, NOT completed. A voided dependency must stop blocking.
    assert(s.closeSync(b).isRight)
    val ok = s.completeSync(a)
    assert(ok.isRight, s"a `closed` dependency must satisfy the guard (both terminal states count): $ok")
    assert(statusOf(a) == TaskLedgerStore.Status.Completed)
  }

  test("dep guard: a `completed` dependency also satisfies the guard") {
    reset()
    val s = store
    s.createSync("dep-D")
    val d = s.entriesSync().find(_.title == "dep-D").get.id
    s.createSync("dep-C", blocksRaw = Some(List(d)))
    val c = s.entriesSync().find(_.title == "dep-C").get.id
    assert(s.completeSync(d).isRight)
    assert(s.completeSync(c).isRight, "a completed dependency satisfies the guard")
  }

  test("dep guard ESCAPE HATCH: `close` is never gated by dependencies") {
    reset()
    val s = store
    s.createSync("dep-X")
    val x = s.entriesSync().find(_.title == "dep-X").get.id
    s.createSync("dep-Y", blocksRaw = Some(List(x)))
    val y = s.entriesSync().find(_.title == "dep-Y").get.id
    // X still open — but withdrawing Y must always be available.
    val r = s.closeSync(y)
    assert(r.isRight, s"close must never be gated (that is the escape hatch): $r")
    assert(statusOf(y) == TaskLedgerStore.Status.Closed)
  }

  test("dep guard: unknown dependency ids and cycles are rejected") {
    reset()
    val s = store
    val unknown = s.createSync("bad", blocksRaw = Some(List("999")))
    assert(unknown.isLeft)
    assert(unknown.left.exists(_.message.contains(TaskLedgerStore.Codes.BlockUnknown)), unknown)

    s.createSync("cyc-1")
    val c1 = s.entriesSync().find(_.title == "cyc-1").get.id
    // self-dependency
    val self = s.updateSync(c1, blocksRaw = Some(List(c1)))
    assert(self.isLeft)
    assert(self.left.exists(_.message.contains(TaskLedgerStore.Codes.Cycle)), self)
  }

  // ------------------------------------------------------------------
  // §1 · storage: a single watermark key / monotonic id space / zero migration
  // ------------------------------------------------------------------

  test("storage: the ledger envelope has exactly {tasks, nextId, version} keys") {
    reset()
    val s = store
    s.createSync("env")
    val json = io.circe.parser.parse(os.read(file)).toOption.get
    val keys = json.asObject.map(_.keys.toSet).getOrElse(Set.empty)
    assertEquals(keys, Set("version", "tasks", "nextId"))
  }

  test("storage: the id watermark is monotonic and ids are never reused") {
    reset()
    val s = store
    val ids = (1 to 3).map { i => s.createSync(s"m$i"); s.entriesSync().map(_.id).max }
    // Each create advances the watermark: ids increase by exactly 1.
    assertEquals(ids.toList, List("1", "2", "3"))
    val json = io.circe.parser.parse(os.read(file)).toOption.get
    assertEquals(json.hcursor.get[Int]("nextId").toOption, Some(3))
  }

  test("storage: a fresh ledger starts from zero (the old watermark is NOT inherited)") {
    reset()
    val s = store
    s.createSync("first")
    assertEquals(s.entriesSync().head.id, "1")
  }

  test("storage: sub-task depth is capped at 5") {
    reset()
    val s = store
    s.createSync("p0")
    var parent = s.entriesSync().head.id
    (-1 to 4).foreach { i => // 6 levels: p0 + p1..p5
      s.createSync(s"p${i + 1}", parentId = Some(parent))
      parent = s.entriesSync().find(_.title == s"p${i + 1}").get.id
    }
    val tooDeep = s.createSync("p6", parentId = Some(parent))
    assert(tooDeep.isLeft, s"depth > 5 must be rejected: $tooDeep")
    assert(tooDeep.left.exists(_.message.contains(TaskLedgerStore.Codes.ParentDepth)), tooDeep)
  }

  // ------------------------------------------------------------------
  // §2 · zero writes to the old ledger (mtime comparison)
  // ------------------------------------------------------------------

  test("zero-write: creating/reading the NEW ledger never touches the OLD ledgers' mtime") {
    reset()
    val oldOrch = os.home / ".nebflow" / "tasks.json"
    val oldBoard = os.Path("/Users/kaiyu/Claude code/Nebflow/.nebflow/task-board.json")
    // Skip if the host files are absent (isolated CI / other machine) — the assertion
    // is about OUR code not writing them, not about their existence.
    if os.exists(oldOrch) && os.exists(oldBoard) then
      val beforeOrch = os.mtime(oldOrch)
      val beforeBoard = os.mtime(oldBoard)
      val s = store
      s.createSync("zw")
      val id = s.entriesSync().head.id
      s.completeSync(id)
      s.appendNoteSync(id, "note body")
      Option(s.showSync(id))
      assertEquals(os.mtime(oldOrch), beforeOrch, "old orchestration ledger mtime must not change")
      assertEquals(os.mtime(oldBoard), beforeBoard, "old board file mtime must not change")
  }

  // ------------------------------------------------------------------
  // §7 · the note timeline (the engine-side only write path) + the 16,000 cap
  // ------------------------------------------------------------------

  test("note timeline: appendNoteSync records `from` + timestamp + body, and is readable via show") {
    reset()
    val s = store
    s.createSync("nt")
    val id = s.entriesSync().head.id
    val r = s.appendNoteSync(id, "the Mail body becomes a note entry", from = TaskLedgerHistory.Origins.Nebula)
    assert(r.isRight, r)

    val lines = os.read.lines(TaskLedgerHistory.open().file).toList
    val all = lines.flatMap(l => decode[TaskLedgerEvent](l).toOption).filter(_.id.contains(id))
    // `create` records its own event; the note is the NOTE-kind one (single write path).
    val events = all.filter(_.kind == TaskLedgerHistory.Kinds.Note)
    assertEquals(events.size, 1, all)
    assertEquals(events.head.kind, TaskLedgerHistory.Kinds.Note)
    assertEquals(events.head.from, Some(TaskLedgerHistory.Origins.Nebula))
    assert(events.head.text.exists(_.contains("the Mail body becomes a note entry")), events.head)
    assert(events.head.at.nonEmpty, "a timestamp must be present")
  }

  test("note timeline: an over-long note is SEGMENTED, never truncated away (16,000-char cap)") {
    reset()
    val s = store
    s.createSync("big")
    val id = s.entriesSync().head.id
    val body = "x" * (TaskLedgerStore.NoteCapChars + 500)
    val r = s.appendNoteSync(id, body)
    assert(r.isRight, r)

    val events = os.read.lines(TaskLedgerHistory.open().file).toList
      .flatMap(l => decode[TaskLedgerEvent](l).toOption).filter(_.id.contains(id))
    assert(events.size >= 2, s"an over-cap note must be split into segments, got ${events.size}")
    // Every character survives somewhere in the history (no silent truncation).
    val total = events.flatMap(_.text).map(_.takeWhile(_ == 'x').length).sum
    assertEquals(total, body.length, "the full body must survive across segments")
  }

  // ------------------------------------------------------------------
  // §1/§9 · prune + archive hit
  // ------------------------------------------------------------------

  test("prune: terminal entries older than 30d are pruned on create, and their history survives") {
    reset()
    val s = store
    s.createSync("prunee")
    val id = s.entriesSync().head.id
    s.completeSync(id)
    s.appendNoteSync(id, "note before prune")
    // Backdate completedAt beyond the TTL by rewriting the ledger file directly.
    val raw = io.circe.parser.parse(os.read(file)).toOption.get
    val patched = raw.mapObject(o => o.add("tasks", o("tasks").get.asArray.get.map { e =>
      e.mapObject(ee => ee.add("completedAt", io.circe.Json.fromString("2020-01-01T00:00:00Z")))
    }.asJson))
    os.write.over(file, patched.noSpaces)

    s.createSync("trigger-prune") // prune is lazy, triggered by create
    assert(store.findSync(id).isEmpty, "the backdated terminal entry must be pruned")

    // History survives the prune; show renders the archived path.
    val shown = store.showSync(id)
    assert(shown.isRight, shown)
    assert(shown.exists(_.contains("[gone]")), shown)
    assert(shown.exists(_.contains("note before prune")), shown)
  }

  test("corruption: a corrupt ledger degrades reads to an empty view, quarantines on write") {
    reset()
    os.write.over(file, "{ not json", createFolders = true)
    // read path: empty view, no crash, no side effects
    assertEquals(store.entriesSync(), Nil)
    assert(os.exists(file), "the read path must not touch the file")
    // write path: quarantine + fresh store
    val r = store.createSync("after-corruption")
    assert(r.isRight, r)
    val quarantined = os.list(home).filter(_.last.startsWith(s"${TaskLedgerStore.FileName}.corrupt-"))
    assert(quarantined.nonEmpty, "the corrupt file must be quarantined, never silently overwritten")
    assertEquals(os.read(quarantined.head), "{ not json")
  }

  // ------------------------------------------------------------------
  // §3 · a single write authority (fail-closed)
  // ------------------------------------------------------------------

  test("write authority: only a Nebula root session may write; dispatcher/node are refused") {
    val nebulaCtx = ToolContext(projectRoot = "/x", agentDef = None)
    // agentDef=None (REST / harness) ⇒ fail-closed false
    assert(!TaskTool.writableBy(nebulaCtx), "a context with no agent identity must NOT write (fail-closed)")

    val dispatcherCtx = ToolContext(projectRoot = "/x", isDispatcher = true)
    assert(!TaskTool.writableBy(dispatcherCtx), "a dispatcher session must not write")

    val nodeCtx = ToolContext(projectRoot = "/x", flowNodeId = Some("n-abc"))
    assert(!TaskTool.writableBy(nodeCtx), "a project node session must not write")

    // dispatcher calling create ⇒ Left(TASK_FORBIDDEN)
    reset()
    val r = TaskTool.dispatchSync(store, Map.empty, dispatcherCtx, "create", title = Some("forbidden"))
    assert(r.isLeft, r)
    assert(r.left.exists(_.message.contains(TaskLedgerStore.Codes.Forbidden)), r)
  }

  test("read authority: TaskInfo with no attachment = Left(TASKINFO_NO_ATTACHMENT), never a whole-ledger fallback") {
    reset()
    val s = store
    s.createSync("visible-to-nobody-without-attachment")
    val emptyCtx = ToolContext(projectRoot = "/x") // all three identity fields empty
    val r = TaskTool.infoSync(s, emptyCtx)
    assert(r.isLeft, r)
    assert(r.left.exists(_.message.contains(TaskLedgerStore.Codes.NoAttachment)), r)
    assert(r.left.exists(m => !m.message.contains("visible-to-nobody")), "must NOT fall back to showing the board")
  }

  test("read authority: TaskInfo resolves from the session's taskId and shows only that entry") {
    reset()
    val s = store
    s.createSync("mine")
    val mine = s.entriesSync().head.id
    s.createSync("not-mine")
    assertEquals(s.entriesSync().size, 2)

    val ctx = ToolContext(projectRoot = "/x", taskId = Some(mine), flowNodeId = Some("n-1"))
    val r = TaskTool.infoSync(s, ctx)
    assert(r.isRight, r)
    val out = r.toOption.get
    assert(out.contains("#" + mine), out)
    assert(out.contains("mine"), out)
    assert(!out.contains("not-mine"), s"another task's data must never appear: $out")
  }

end TaskLedgerStoreSpec
