package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.JsonObject
import io.circe.syntax.*
import munit.FunSuite
import nebflow.core.PathUtil
import nebflow.core.task.{FileTaskStore, TaskStatus}

import java.nio.file.{Files => JFiles}

/**
 * Team Manager task tools (#D, spec 20260825_team-manager-task-tool-spec.md
 * §2.2/§2.3/§2.5/§3): non-team context hard rejection, create→update→list
 * smoke flow, WS teamTaskListUpdate event shape, blockedBy-at-create,
 * read-only List hard constraint, Nebula read-only via `team` param.
 */
class TeamTaskToolsSpec extends FunSuite:

  private var tempRoot: os.Path = null
  private var savedRoot: os.Path = null

  override def beforeEach(context: munit.BeforeEach): Unit =
    savedRoot = PathUtil.dataRoot
    tempRoot = os.Path(JFiles.createTempDirectory("nb-teamtask-tools").toString)
    PathUtil.setDataRoot(tempRoot)

  override def afterEach(context: munit.AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    if os.exists(tempRoot) then os.remove.all(tempRoot)

  private var lastSent: Option[io.circe.Json] = None
  private val captureSend: io.circe.Json => IO[Unit] = j => IO { lastSent = Some(j) }

  private def teamCtx(teamName: String = "alpha"): ToolContext =
    lastSent = None
    ToolContext(
      projectRoot = tempRoot.toString,
      taskStore = Some(FileTaskStore),
      teamName = Some(teamName),
      wsSend = Some(captureSend)
    )

  /** Nebula-like context: no teamName — reads must pass `team` explicitly. */
  private def noTeamCtx(): ToolContext =
    lastSent = None
    ToolContext(projectRoot = tempRoot.toString, taskStore = Some(FileTaskStore), wsSend = Some(captureSend))

  private def obj(fields: (String, io.circe.Json)*): JsonObject = JsonObject.fromIterable(fields.map((k, v) => k -> v))

  private def call(tool: Tool, input: JsonObject, ctx: ToolContext): Either[ToolError, String] =
    tool.call(input, ctx).unsafeRunSync()

  // ── §3 non-team context hard rejection ──────────────────────────────

  test("TeamTaskCreate/Update/List hard-reject when ToolContext.teamName is absent"):
    val errCtx = ToolContext(projectRoot = tempRoot.toString, taskStore = Some(FileTaskStore))
    val create = call(TeamTaskCreateTool, obj("subject" -> "s".asJson, "description" -> "d".asJson), errCtx)
    val update = call(TeamTaskUpdateTool, obj("taskId" -> "1".asJson, "status" -> "in_progress".asJson), errCtx)
    val list = call(TeamTaskListTool, obj(), errCtx)
    assert(create.isLeft && create.swap.toOption.get.message.contains("only available to team agents"))
    assert(update.isLeft && update.swap.toOption.get.message.contains("only available to team agents"))
    assert(list.isLeft, "List without teamName and without team param must error")

  // ── smoke flow: create (with blockedBy) → update → list + WS event ──

  test("smoke: create with blockedBy → in_progress → completed+note → list reflects, WS event carries {team, tasks}"):
    val c1 = call(TeamTaskCreateTool, obj("subject" -> "fix registry bug".asJson, "description" -> "d1".asJson), teamCtx())
    val id1 = c1.fold(e => fail(e.message), _.split("ID: ")(1).stripSuffix(")").trim)
    assertEquals(c1.toOption.get, s"Task created: fix registry bug (ID: $id1)")
    // WS event after create: team + tasks array
    val ev1 = lastSent.get.hcursor
    assertEquals(ev1.downField("type").as[String].toOption, Some("teamTaskListUpdate"))
    assertEquals(ev1.downField("team").as[String].toOption, Some("alpha"))
    assertEquals(ev1.downField("tasks").as[List[io.circe.Json]].toOption.map(_.size), Some(1))

    // second task blockedBy the first
    val c2 = call(TeamTaskCreateTool, obj(
      "subject" -> "write docs".asJson, "description" -> "d2".asJson,
      "blockedBy" -> List(id1).asJson
    ), teamCtx())
    val id2 = c2.fold(e => fail(e.message), _.split("ID: ")(1).stripSuffix(")").trim)
    val t2 = FileTaskStore.get("team:alpha", id2).unsafeRunSync().get
    assertEquals(t2.blockedBy, List(id1), "blockedBy declared at create lands on the task")

    // start + complete with note
    val up1 = call(TeamTaskUpdateTool, obj("taskId" -> id1.asJson, "status" -> "in_progress".asJson), teamCtx())
    assertEquals(up1.toOption.get, s"fix registry bug → in_progress")
    val up2 = call(TeamTaskUpdateTool, obj(
      "taskId" -> id1.asJson, "status" -> "completed".asJson,
      "note" -> "merged abc1234".asJson, "noteLinks" -> List("/tmp/report.md").asJson
    ), teamCtx())
    assertEquals(up2.toOption.get, s"fix registry bug → completed")
    val done = FileTaskStore.get("team:alpha", id1).unsafeRunSync().get
    assertEquals(done.status, TaskStatus.Completed)
    assertEquals(done.completedAt.isDefined, true)
    assertEquals(done.notes.lastOption.map(_.content), Some("merged abc1234"))
    assertEquals(done.notes.lastOption.flatMap(_.links.headOption), Some("/tmp/report.md"))

    // list reflects states + deps
    val listOut = call(TeamTaskListTool, obj(), teamCtx()).toOption.get
    assert(listOut.contains("Team tasks for 'alpha' (2)"))
    assert(listOut.contains("#1 [completed] fix registry bug"))
    assert(listOut.contains("#2 [pending] write docs"))
    assert(listOut.contains("dep #1"), "blockedBy shown in list output")

    // status filter
    val pendingOnly = call(TeamTaskListTool, obj("status" -> "pending".asJson), teamCtx()).toOption.get
    assert(pendingOnly.contains("#2 [pending]"))
    assert(!pendingOnly.contains("#1 [completed]"))

  // ── status parsing strictness + transition rejection ────────────────

  test("unknown status string is a ToolError, not a silent no-op"):
    val c1 = call(TeamTaskCreateTool, obj("subject" -> "s".asJson, "description" -> "d".asJson), teamCtx())
    val id = c1.fold(e => fail(e.message), _.split("ID: ")(1).stripSuffix(")").trim)
    val bad = call(TeamTaskUpdateTool, obj("taskId" -> id.asJson, "status" -> "needs_confirmation".asJson), teamCtx())
    assert(bad.isLeft && bad.swap.toOption.get.message.contains("Invalid status"), "needs_confirmation not in team matrix")
    val stillPending = FileTaskStore.get("team:alpha", id).unsafeRunSync().get
    assertEquals(stillPending.status, TaskStatus.Pending)

  test("completed → in_progress rejected with IllegalStateException surfaced as ToolError"):
    val c1 = call(TeamTaskCreateTool, obj("subject" -> "s".asJson, "description" -> "d".asJson), teamCtx())
    val id = c1.fold(e => fail(e.message), _.split("ID: ")(1).stripSuffix(")").trim)
    call(TeamTaskUpdateTool, obj("taskId" -> id.asJson, "status" -> "completed".asJson), teamCtx())
    val bad = call(TeamTaskUpdateTool, obj("taskId" -> id.asJson, "status" -> "in_progress".asJson), teamCtx())
    assert(bad.isLeft && bad.swap.toOption.get.message.contains("Invalid status transition"))

  test("update on missing task → ToolError not found"):
    val r = call(TeamTaskUpdateTool, obj("taskId" -> "999".asJson, "status" -> "in_progress".asJson), teamCtx())
    assert(r.isLeft && r.swap.toOption.get.message.contains("not found"))

  test("create blockedBy cycle surfaces as ToolError via the create tool"):
    val c1 = call(TeamTaskCreateTool, obj("subject" -> "a".asJson, "description" -> "d".asJson), teamCtx())
    val id1 = c1.fold(e => fail(e.message), _.split("ID: ")(1).stripSuffix(")").trim)
    val c2 = call(TeamTaskCreateTool, obj(
      "subject" -> "b".asJson, "description" -> "d".asJson,
      "blockedBy" -> List(id1).asJson
    ), teamCtx())
    val id2 = c2.fold(e => fail(e.message), _.split("ID: ")(1).stripSuffix(")").trim)
    // now make id1 blockedBy id2 → cycle; must be a ToolError and not corrupt id1
    val cyc = call(TeamTaskUpdateTool, obj("taskId" -> id1.asJson, "addBlockedBy" -> List(id2).asJson), teamCtx())
    assert(cyc.isLeft && cyc.swap.toOption.get.message.contains("cycle"))
    val t1 = FileTaskStore.get("team:alpha", id1).unsafeRunSync().get
    assertEquals(t1.blockedBy, Nil, "rejected cycle must not persist")

  // ── List read-only hard constraint ──────────────────────────────────

  test("TeamTaskList inputSchema exposes ONLY read params (team/status) — hard read-only constraint"):
    val props = TeamTaskListTool.inputSchema("properties").flatMap(_.asObject).get.keys.toSet
    assertEquals(props, Set("team", "status"))

  test("Nebula-like context (no teamName) can read any team via the team param"):
    val c = call(TeamTaskCreateTool, obj("subject" -> "s".asJson, "description" -> "d".asJson), teamCtx("beta"))
    c.fold(e => fail(e.message), _ => ())
    val out = call(TeamTaskListTool, obj("team" -> "beta".asJson), noTeamCtx()).toOption.get
    assert(out.contains("Team tasks for 'beta' (1)"))

  // ── team isolation through the tools ────────────────────────────────

  test("tools operate only on the caller's team directory (team A cannot see team B)"):
    call(TeamTaskCreateTool, obj("subject" -> "a-task".asJson, "description" -> "d".asJson), teamCtx("aaa"))
    call(TeamTaskCreateTool, obj("subject" -> "b-task".asJson, "description" -> "d".asJson), teamCtx("bbb"))
    val out = call(TeamTaskListTool, obj(), teamCtx("aaa")).toOption.get
    assert(out.contains("a-task"))
    assert(!out.contains("b-task"))

end TeamTaskToolsSpec
