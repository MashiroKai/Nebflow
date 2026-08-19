package nebflow.core.tools

import cats.effect.unsafe.implicits.global
import io.circe.JsonObject
import io.circe.syntax.*
import munit.FunSuite
import nebflow.core.task.{FileTaskStore, TaskArchive, TaskCreateInput, TaskStatus, TaskUpdateInput}
import nebflow.core.PathUtil

import java.nio.file.{Files => JFiles}

/**
 * R6: TaskQuery tool against the REAL FileTaskStore + archive index
 * (no store mocks). Temp dataRoot per test.
 */
class TaskQueryToolSpec extends FunSuite:

  private var tempRoot: os.Path = null
  private var savedRoot: os.Path = null

  override def beforeEach(context: munit.BeforeEach): Unit =
    savedRoot = PathUtil.dataRoot
    tempRoot = os.Path(JFiles.createTempDirectory("nb-taskquery").toString)
    PathUtil.setDataRoot(tempRoot)

  override def afterEach(context: munit.AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    if os.exists(tempRoot) then os.remove.all(tempRoot)

  private def ctx(sessionId: String): ToolContext =
    ToolContext(projectRoot = tempRoot.toString, taskStore = Some(nebflow.core.task.FileTaskStore), sessionId = Some(sessionId))

  private def obj(fields: (String, io.circe.Json)*): JsonObject = JsonObject.fromIterable(fields.map((k, v) => k -> v))

  private def run(input: JsonObject, sessionId: String = "s1"): String =
    TaskQueryTool.call(input, ctx(sessionId)).unsafeRunSync().fold(e => fail(e.message), identity)

  private def seedSession(sid: String, subject: String, status: TaskStatus, desc: String = "d"): String =
    val id = nebflow.core.task.FileTaskStore.create(sid, TaskCreateInput(subject, desc)).unsafeRunSync()
    status match
      case TaskStatus.Pending => // already pending at create
      case TaskStatus.Completed =>
        // v2 C15: completed is reached via complete(), not agent TaskUpdate
        nebflow.core.task.FileTaskStore.complete(sid, id, by = "agent").unsafeRunSync()
      case other =>
        nebflow.core.task.FileTaskStore.update(sid, id, TaskUpdateInput(status = Some(other))).unsafeRunSync()
    id

  test("scope=session returns current session tasks incl. completed, keyword matches description") {
    seedSession("s1", "fix registry bug", TaskStatus.Completed, "refactor the tool registry deeply")
    seedSession("s1", "write docs", TaskStatus.Pending)
    val out = run(obj("scope" -> "session".asJson))
    assert(out.contains("fix registry bug"))
    assert(out.contains("write docs"))
    val kw = run(obj("scope" -> "session".asJson, "keyword" -> "tool registry deeply".asJson))
    assert(kw.contains("fix registry bug"))
    assert(!kw.contains("write docs"))
  }

  test("scope=recent with since=7d returns only in-window tasks via the index, newest first") {
    seedSession("sa", "recent done", TaskStatus.Completed)
    seedSession("sb", "recent pending", TaskStatus.Pending)
    // out-of-window: completedAt 2026-01-01 via hand-written file
    val oldDir = tempRoot / "tasks" / "sc"
    os.makeDir.all(oldDir)
    os.write(oldDir / "1.json",
      """{"id":"1","subject":"ancient task","description":"x","status":"completed","blocks":[],"blockedBy":[],"createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z","completedAt":"2026-01-01T00:00:00Z"}""")
    // build the index over all three sessions
    TaskArchive.rebuildIndex(_ => TaskArchive.Unclassified).unsafeRunSync()

    val out = run(obj("scope" -> "recent".asJson, "since" -> "7d".asJson))
    assert(out.contains("recent done"))
    assert(out.contains("recent pending")) // createdAt is in-window for pending too
    assert(!out.contains("ancient task"))
  }

  test("scope=recent status=completed filters and orders by completedAt desc") {
    seedSession("sa", "first done", TaskStatus.Completed)
    Thread.sleep(10)
    seedSession("sb", "second done", TaskStatus.Completed)
    TaskArchive.rebuildIndex(_ => TaskArchive.Unclassified).unsafeRunSync()
    val out = run(obj("scope" -> "recent".asJson, "status" -> "completed".asJson))
    val i1 = out.indexOf("first done")
    val i2 = out.indexOf("second done")
    assert(i1 >= 0 && i2 >= 0 && i2 < i1, s"newest must come first:\n$out")
  }

  test("scope=project matches folder name and keeps orphan sessions queryable") {
    seedSession("s-fold", "project task", TaskStatus.Completed)
    seedSession("s-orph", "orphan task", TaskStatus.Completed)
    TaskArchive.rebuildIndex {
      case "s-fold" => TaskArchive.SessionJoin(Some("f1"), Some("nebflow"), Some("named session"))
      case _        => TaskArchive.Unclassified
    }.unsafeRunSync()
    val out = run(obj("scope" -> "project".asJson, "project" -> "nebflow".asJson))
    assert(out.contains("project task"))
    assert(!out.contains("orphan task"))
  }

  test("missing index degrades to session scope without error") {
    seedSession("s1", "only session task", TaskStatus.Completed)
    // no rebuildIndex call — index absent
    val out = run(obj("scope" -> "recent".asJson, "since" -> "7d".asJson))
    assert(out.contains("[archive index unavailable"))
    assert(out.contains("only session task"))
  }

  test("limit defaults to 30 and truncates") {
    (1 to 40).foreach { i => seedSession("s1", s"task $i", TaskStatus.Pending) }
    val out = run(obj("scope" -> "session".asJson))
    assert(out.contains("30 shown"))
    assert(!out.contains("task 40"))
    val out50 = run(obj("scope" -> "session".asJson, "limit" -> 40.asJson))
    assert(out50.contains("40 shown"))
  }

  test("scope=session status=in_progress filter WORKS with the underscore wire name") {
    // regression (qa toolopt-task-20260816): toString.toLowerCase turned
    // InProgress into "inprogress" so the schema-promised value never matched
    seedSession("s1", "wip task", TaskStatus.InProgress)
    seedSession("s1", "done task", TaskStatus.Completed)
    val out = run(obj("scope" -> "session".asJson, "status" -> "in_progress".asJson))
    assert(out.contains("wip task"))
    assert(out.contains("[in_progress] wip task"), s"rendered status must keep the underscore:\n$out")
    assert(!out.contains("done task"))
  }

  test("scope=recent status=in_progress filter works against the index") {
    seedSession("sa", "indexed wip", TaskStatus.InProgress)
    seedSession("sb", "indexed done", TaskStatus.Completed)
    TaskArchive.rebuildIndex(_ => TaskArchive.Unclassified).unsafeRunSync()
    val entries = TaskArchive.loadIndex().unsafeRunSync()
    // the index itself must carry the wire name with underscore
    assertEquals(
      entries.find(_.subject == "indexed wip").map(_.status),
      Some("in_progress")
    )
    val out = run(obj("scope" -> "recent".asJson, "status" -> "in_progress".asJson))
    assert(out.contains("indexed wip"))
    assert(!out.contains("indexed done"))
  }

  test("parseSince handles today / yesterday / Nd / ISO date") {
    val zone = java.time.ZoneId.systemDefault()
    assert(TaskQueryTool.parseSince("today").isDefined)
    assert(TaskQueryTool.parseSince("yesterday").isDefined)
    assertEquals(
      TaskQueryTool.parseSince("2026-08-15"),
      Some(java.time.LocalDate.parse("2026-08-15").atStartOfDay(zone).toInstant.toEpochMilli)
    )
    val seven = TaskQueryTool.parseSince("7d").get
    val expected = System.currentTimeMillis() - 7L * 86400000L
    assert(math.abs(seven - expected) < 60000, "7d should be ~7 days back")
    assertEquals(TaskQueryTool.parseSince("garbage"), None)
  }

end TaskQueryToolSpec
