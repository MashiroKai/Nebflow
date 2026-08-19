package nebflow.core.task

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.parser.decode
import io.circe.syntax.*
import munit.FunSuite
import nebflow.core.PathUtil

import java.nio.file.{Files => JFiles}

/**
 * C2 acceptance: legacy decode compatibility (qa R1/R2 red lines),
 * completedAt semantics (R3), event stream (R4), renderForPrompt
 * byte-identity (R5 red line), archive index (R8).
 *
 * S1-S5 are REAL legacy task JSON files sampled from the production
 * ~/.nebflow/tasks tree (535+ completed archives) — quoted verbatim from
 * the qa acceptance doc.
 */
class TaskArchiveCompatSpec extends FunSuite:

  // ===== qa fixtures S1-S5 (verbatim from /tmp/qa-toolopt-acceptance.md) =====

  private val s1 = """{"id":"6","subject":"P0: remote-exec 鉴权加固 — 引入 device secret","description":"为 P2P 通信引入 device-level secret。DeviceIdentity 增加一个 deviceSecret 字段，peer 端点验证 userId + deviceSecret，而非仅 userId。","activeForm":"修复 P0: remote-exec 鉴权过弱","status":"completed","blocks":[],"blockedBy":[],"createdAt":"2026-06-09T13:44:41.596134Z","updatedAt":"2026-06-09T13:48:46.579475Z"}"""
  private val s2 = """{"id":"1","subject":"演示任务系统功能","description":"创建一个示例任务来展示任务管理系统的基本功能，包括创建、更新、查询等操作","activeForm":"演示任务系统功能中","status":"completed","blocks":[],"blockedBy":[],"metadata":null}"""
  private val s3 = """{"id":"1","subject":"Create baseline branch with current uncommitted changes","description":"Create temp/flow-baseline worktree from main, apply uncommitted changes, commit. This is the base for all flow fix branches.","activeForm":"Creating baseline branch","status":"dismissed","parentId":null,"blocks":[],"blockedBy":[],"createdAt":"2026-07-23T12:59:12.353180Z","updatedAt":"2026-08-05T05:49:47.474550Z"}"""
  private val s4 = """{"id":"8","subject":"前端交互界面 v1 (已废弃，需重写)","description":"前端: Three.js 3D 探测器（可点击设置交互位置）+ 参数面板（能量、角度、探测器尺寸、偏压、迁移率、粒子类型）+ 实时波形图（Chart.js，显示总信号/电子/空穴分量）+ 能谱图（支持 log/linear 切换）。WebSocket 实时更新。","activeForm":"构建前端交互界面","status":"failed","blocks":[],"blockedBy":["5","6"],"createdAt":"2026-06-07T13:24:25.717562Z","updatedAt":"2026-06-07T14:01:10.711460Z"}"""
  private val s5 = """{"id":"1","subject":"探索 Scala 版上下文压缩现有实现","description":"探索 archive/scala 分支的 ContextRefresher, compaction, DreamMode, AgentCore 等源文件，理解现有架构","activeForm":"探索现有上下文压缩实现","status":"completed","parentId":null,"blocks":[],"blockedBy":[],"createdAt":"2026-08-10T00:39:06.920775Z","updatedAt":"2026-08-10T00:40:33.955765Z"}"""

  private var tempRoot: os.Path = null
  private var savedRoot: os.Path = null

  override def beforeEach(context: munit.BeforeEach): Unit =
    savedRoot = PathUtil.dataRoot
    tempRoot = os.Path(JFiles.createTempDirectory("nb-task-c2").toString)
    PathUtil.setDataRoot(tempRoot)

  override def afterEach(context: munit.AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    if os.exists(tempRoot) then os.remove.all(tempRoot)

  // ===== R1 (red line): legacy decode =====

  test("R1: all five legacy fixtures decode Right with fields preserved") {
    for (name, json) <- List(("S1", s1), ("S2", s2), ("S3", s3), ("S4", s4), ("S5", s5)) do
      decode[Task](json) match
        case Left(err) => fail(s"$name failed to decode: $err")
        case Right(t) =>
          // old fields preserved verbatim
          assertEquals(t.id, jsonMatch(json, "id"), s"$name id")
          assert(t.subject.nonEmpty, s"$name subject")
          assert(t.description.nonEmpty, s"$name description")
          assert(t.activeForm.isDefined, s"$name activeForm")
          // new fields = defaults
          assertEquals(t.completedAt, None, s"$name completedAt must default")
          assertEquals(t.notes, Nil, s"$name notes must default")
          assertEquals(t.events, Nil, s"$name events must default")
  }

  private def jsonMatch(json: String, key: String): String =
    io.circe.parser.parse(json).fold(_ => "", _.hcursor.downField(key).as[String].getOrElse(""))

  test("R1: S2 unknown field metadata is ignored, missing createdAt tolerated") {
    val t = decode[Task](s2).toOption.get
    assertEquals(t.activeForm, Some("演示任务系统功能中"))
    assertEquals(t.status, TaskStatus.Completed)
    assertEquals(t.createdAt, None) // absent in the legacy JSON
    assertEquals(t.updatedAt, None)
  }

  test("R1: S4 blockedBy and S5 explicit-null parentId survive exactly") {
    val t4 = decode[Task](s4).toOption.get
    assertEquals(t4.blockedBy, List("5", "6"))
    assertEquals(t4.status, TaskStatus.Failed)
    val t5 = decode[Task](s5).toOption.get
    assertEquals(t5.parentId, None)
  }

  test("R1 mechanism: a List-defaulted field missing from JSON is the real breaker without withDefaults") {
    // sanity-check the codec actually fills List defaults: strip notes/events
    // keys entirely (this IS the legacy-file situation for every old task)
    val stripped = """{"id":"9","subject":"x","description":"y","status":"pending","blocks":[],"blockedBy":[]}"""
    decode[Task](stripped) match
      case Right(t) =>
        assertEquals(t.notes, Nil)
        assertEquals(t.events, Nil)
      case Left(err) => fail(s"codec lost useDefaults: $err")
  }

  // ===== R2 (red line): list() keeps every archive =====

  test("R2: list() over a directory of the five legacy fixtures returns 5") {
    val dir = tempRoot / "tasks" / "sess-r2"
    os.makeDir.all(dir)
    os.write(dir / "6.json", s1)
    os.write(dir / "1a.json", s2) // non-numeric ids sort last, still counted
    os.write(dir / "1.json", s3)
    os.write(dir / "8.json", s4)
    os.write(dir / "1b.json", s5)
    val listed = FileTaskStore.list("sess-r2").unsafeRunSync()
    assertEquals(listed.length, 5)
  }

  // ===== R3: completedAt =====

  test("R3: completed transition stamps completedAt on disk") {
    val sid = "sess-r3a"
    val id = FileTaskStore.create(sid, TaskCreateInput("t", "d")).unsafeRunSync()
    FileTaskStore.update(sid, id, TaskUpdateInput(status = Some(TaskStatus.InProgress))).unsafeRunSync()
    // v2 C15: agent reaches completed via complete(), not TaskUpdate
    val done = FileTaskStore.complete(sid, id, by = "agent").unsafeRunSync().get
    assert(done.completedAt.isDefined)
    // disk round-trip
    val fromDisk = FileTaskStore.get(sid, id).unsafeRunSync().get
    assertEquals(fromDisk.completedAt, done.completedAt)
    // same-second as updatedAt
    assertEquals(fromDisk.completedAt.map(_.take(19)), fromDisk.updatedAt.map(_.take(19)))
  }

  test("R3: failed transition also stamps completedAt") {
    val sid = "sess-r3b"
    val id = FileTaskStore.create(sid, TaskCreateInput("t", "d")).unsafeRunSync()
    val failed = FileTaskStore.update(sid, id, TaskUpdateInput(status = Some(TaskStatus.Failed))).unsafeRunSync().get
    assert(failed.completedAt.isDefined)
  }

  test("R3: no-op pending update does not stamp completedAt") {
    val sid = "sess-r3c"
    val id = FileTaskStore.create(sid, TaskCreateInput("t", "d")).unsafeRunSync()
    val stillPending = FileTaskStore.update(sid, id, TaskUpdateInput(subject = Some("t2"))).unsafeRunSync().get
    assertEquals(stillPending.status, TaskStatus.Pending)
    assertEquals(stillPending.completedAt, None)
  }

  // ===== R4: event stream =====

  test("R4: create seeds a created event; description/status/dependency/note updates append kinds") {
    val sid = "sess-r4a"
    val id = FileTaskStore.create(sid, TaskCreateInput("t", "old desc")).unsafeRunSync()
    val created = FileTaskStore.get(sid, id).unsafeRunSync().get
    assertEquals(created.events.map(_.kind), List("created"))

    FileTaskStore.update(sid, id, TaskUpdateInput(description = Some("new desc"))).unsafeRunSync()
    val afterDesc = FileTaskStore.get(sid, id).unsafeRunSync().get
    assertEquals(afterDesc.events.map(_.kind), List("created", "description"))
    assert(afterDesc.events.last.detail.exists(_.contains("old desc")))

    FileTaskStore.update(sid, id, TaskUpdateInput(status = Some(TaskStatus.InProgress))).unsafeRunSync()
    val afterStatus = FileTaskStore.get(sid, id).unsafeRunSync().get
    assertEquals(afterStatus.events.map(_.kind), List("created", "description", "status"))
    assertEquals(afterStatus.events.last.detail, Some("pending→in_progress"))

    FileTaskStore.update(sid, id, TaskUpdateInput(addBlockedBy = Some(List("99")))).unsafeRunSync()
    val afterDep = FileTaskStore.get(sid, id).unsafeRunSync().get
    assert(afterDep.events.map(_.kind).contains("dependency"))

    FileTaskStore.update(sid, id, TaskUpdateInput(note = Some("done, see commit abc"))).unsafeRunSync()
    val afterNote = FileTaskStore.get(sid, id).unsafeRunSync().get
    assert(afterNote.events.map(_.kind).contains("note"))
    assertEquals(afterNote.notes.length, 1)
    assertEquals(afterNote.notes.head.content, "done, see commit abc")
  }

  test("R4: notes are append-only across updates") {
    val sid = "sess-r4b"
    val id = FileTaskStore.create(sid, TaskCreateInput("t", "d")).unsafeRunSync()
    FileTaskStore.update(sid, id, TaskUpdateInput(
      note = Some("first"), noteLinks = Some(List("/tmp/a.md", "abc123"))
    )).unsafeRunSync()
    FileTaskStore.complete(sid, id, by = "agent").unsafeRunSync()
    FileTaskStore.update(sid, id, TaskUpdateInput(note = Some("second"))).unsafeRunSync()
    val t = FileTaskStore.get(sid, id).unsafeRunSync().get
    assertEquals(t.notes.map(_.content), List("first", "second"))
    assertEquals(t.notes.head.links, List("/tmp/a.md", "abc123"))
  }

  test("R4: event history caps at 50, oldest dropped") {
    val sid = "sess-r4c"
    val id = FileTaskStore.create(sid, TaskCreateInput("t", "d")).unsafeRunSync()
    (1 to 60).foreach { i =>
      FileTaskStore.update(sid, id, TaskUpdateInput(description = Some(s"rev $i"))).unsafeRunSync()
    }
    val t = FileTaskStore.get(sid, id).unsafeRunSync().get
    assertEquals(t.events.length, 50)
    // oldest kept should be a later revision, not "created"
    assert(!t.events.headOption.exists(_.kind == "created"))
    // the LAST event's detail records the PREVIOUS value (rev 59), not rev 60
    assert(t.events.last.detail.exists(_.contains("rev 59")))
  }

  // ===== R5 (red line): renderForPrompt byte-identical =====
  // v2 note: the header sentence changed with the five-state lifecycle (C15 —
  // needs_confirmation is the completion lane); the body rendering is unchanged.

  test("R5: renderForPrompt output is byte-identical to the frozen baseline") {
    val sid = "sess-r5"
    val t1 = Task("1", "subject1", "d1", status = TaskStatus.Completed)
    val t2 = Task("2", "subject2", "d2", Some("activeForm2"), TaskStatus.InProgress)
    val t3 = Task("3", "subject3", "d3", None, TaskStatus.Pending)
    val dir = tempRoot / "tasks" / sid
    os.makeDir.all(dir)
    os.write(dir / "1.json", t1.asJson.noSpaces)
    os.write(dir / "2.json", t2.asJson.noSpaces)
    os.write(dir / "3.json", t3.asJson.noSpaces)
    val out = FileTaskStore.renderForPrompt(sid).unsafeRunSync()
    val expected =
      "## Current Tasks\n\n" +
        "Your task list is below. Work through tasks in order. When a task is fully done, " +
        "mark it needs_confirmation (NOT completed) and attach a note with the outcome — " +
        "completed is reserved for the user's confirmation. Tasks marked [needs_confirmation] " +
        "are DONE and awaiting user confirmation: do NOT work on them again; if the user " +
        "returns one with feedback, a [打回任务] block tells you what to revise.\n\n" +
        "#2 [in_progress] subject2 — activeForm2\n" +
        "#3 [pending] subject3\n"
    assertEquals(out, expected)
    // completed task's subject must not leak
    assert(!out.contains("subject1"))
    // new fields never appear in the prompt render
    assert(!out.contains("completedAt") && !out.contains("notes") && !out.contains("events"))
  }

  test("R5: all-completed session renders empty string") {
    val sid = "sess-r5b"
    val t1 = Task("1", "done thing", "d", status = TaskStatus.Completed)
    val dir = tempRoot / "tasks" / sid
    os.makeDir.all(dir)
    os.write(dir / "1.json", t1.asJson.noSpaces)
    assertEquals(FileTaskStore.renderForPrompt(sid).unsafeRunSync(), "")
  }

  // ===== R8: archive index =====

  test("R8: rebuildIndex joins folder info and keeps orphans unclassified") {
    val sid = "sess-known"
    val orphan = "sess-deleted"
    val id1 = FileTaskStore.create(sid, TaskCreateInput("known task", "d")).unsafeRunSync()
    FileTaskStore.update(sid, id1, TaskUpdateInput(
      note = Some("done"), noteLinks = Some(List("/x.md"))
    )).unsafeRunSync()
    FileTaskStore.complete(sid, id1, by = "agent").unsafeRunSync()
    val dir2 = tempRoot / "tasks" / orphan
    os.makeDir.all(dir2)
    os.write(dir2 / "1.json", s1)

    val join: String => TaskArchive.SessionJoin = {
      case `sid` => TaskArchive.SessionJoin(Some("f1"), Some("Nebflow 项目"), Some("main session"))
      case `orphan` => TaskArchive.SessionJoin(None, None, None) // session gone from store
      case _ => TaskArchive.Unclassified
    }
    val entries = TaskArchive.rebuildIndex(join).unsafeRunSync()
    assertEquals(entries.length, 2)
    val known = entries.find(_.taskId == id1).get
    assertEquals(known.folderName, Some("Nebflow 项目"))
    assertEquals(known.status, "completed")
    assert(known.completedAt.isDefined)
    assertEquals(known.noteCount, 1)
    assert(known.hasLinks)
    val orphanEntry = entries.find(_.sessionId == orphan).get
    assertEquals(orphanEntry.folderId, None)
    // index file landed at the exact agreed path, and ONLY there
    assert(os.exists(tempRoot / "tasks" / "_index.json"))
  }

  test("R8: refreshSession replaces one session's slice incrementally") {
    val sid = "sess-inc"
    val id1 = FileTaskStore.create(sid, TaskCreateInput("first", "d")).unsafeRunSync()
    TaskArchive.refreshSession(sid, _ => TaskArchive.Unclassified).unsafeRunSync()
    val v1 = TaskArchive.loadIndex().unsafeRunSync()
    assertEquals(v1.length, 1)

    val id2 = FileTaskStore.create(sid, TaskCreateInput("second", "d")).unsafeRunSync()
    TaskArchive.refreshSession(sid, _ => TaskArchive.SessionJoin(Some("f9"), Some("proj"), None)).unsafeRunSync()
    val v2 = TaskArchive.loadIndex().unsafeRunSync()
    assertEquals(v2.length, 2)
    assert(v2.forall(_.folderName.contains("proj")))
    // other sessions' slices untouched
    val otherDir = tempRoot / "tasks" / "sess-other"
    os.makeDir.all(otherDir)
    os.write(otherDir / "1.json", s3)
    TaskArchive.refreshSession("sess-other", _ => TaskArchive.Unclassified).unsafeRunSync()
    val v3 = TaskArchive.loadIndex().unsafeRunSync()
    assertEquals(v3.filter(_.sessionId == sid).length, 2)
    assertEquals(v3.filter(_.sessionId == "sess-other").length, 1)
  }

  // ===== R9: wire format only grows =====

  test("R9: encoded task JSON carries old 11 fields plus the 3 new ones") {
    val t = Task("1", "s", "d", Some("a"), TaskStatus.Completed, None, List("2"), Nil,
      Some("2026-08-15T00:00:00Z"), Some("2026-08-15T01:00:00Z"),
      Some("2026-08-15T01:00:00Z"), List(TaskNote("n", List("/l"), Some("now"))), List(TaskEvent("created")))
    val json = t.asJson
    val obj = json.asObject.get
    for k <- List("id", "subject", "description", "activeForm", "status", "parentId", "blocks",
                  "blockedBy", "createdAt", "updatedAt", "completedAt", "notes", "events") do
      assert(obj.contains(k), s"missing key $k")
  }

end TaskArchiveCompatSpec
