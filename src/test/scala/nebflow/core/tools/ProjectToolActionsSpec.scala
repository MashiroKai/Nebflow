package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.project.ProjectStore
import nebflow.shared.PathUtil

import scala.concurrent.duration.*

/**
 * Project 工具 list / show / update 面（台账 #913 ②④，2026-10-26）——
 * 经 `ProjectTool.call` 全口径（不经私有函数）：设备归属标注的 agent 查询面 +
 * description 单字段写穿面的报错族与幂等态。
 */
class ProjectToolActionsSpec extends FunSuite:

  private val tempRoot: os.Path = os.pwd / "target" / "test-project-tool-actions"
  private val ws: os.Path = tempRoot / "workspace"

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(ws)

  private val template = ""
  private val ctx = ToolContext(projectRoot = tempRoot.toString)

  private def call(input: (String, io.circe.Json)*): Either[ToolError, String] =
    ProjectTool
      .call(io.circe.JsonObject.fromIterable(input), ctx)
      .unsafeRunSync()

  private def seed(name: String, description: Option[String]): Unit =
    ProjectStore.create(name, ws.toString, description, template).unsafeRunSync()

  // ── list ─────────────────────────────────────────────────────────

  test("list: renders device tag + workspace + description per project (local = device=local)"):
    seed("tool-list-a", Some("描述甲"))
    val r = call("action" -> io.circe.Json.fromString("list"))
    val out = r.toOption.getOrElse(fail(s"expected Right, got $r"))
    assert(out.contains("- tool-list-a [device=local,"), out)
    assert(out.contains("mounted") || out.contains("not mounted"), out)
    assert(out.contains(ws.toString), out)
    assert(out.contains("描述甲"), out)

  test("list: empty store renders the explicit placeholder (never a bare empty string)"):
    os.remove.all(tempRoot / "projects")
    val r = call("action" -> io.circe.Json.fromString("list"))
    val out = r.toOption.getOrElse(fail(s"expected Right, got $r"))
    assert(out.contains("No mounted projects"), out)

  test("list: remote-origin project shows the device display name (roster miss = raw id, honest)"):
    ProjectStore
      .createWithScaffold(
        "tool-list-remote",
        "/remote/ws",
        None,
        template,
        origin = Some(nebflow.core.project.ProjectOrigin("peer-uuid-4321", "/remote/ws"))
      )
      .unsafeRunSync()
    val out = call("action" -> io.circe.Json.fromString("list")).toOption.get
    assert(out.contains("device=peer-uuid-4321"), "no roster in harness → raw id fallback: " + out)

  // ── show ─────────────────────────────────────────────────────────

  test("show: full definition — device (display + storage), origin, brainRoot, mounted state"):
    ProjectStore
      .createWithScaffold(
        "tool-show-one",
        "/remote/ws2",
        Some("远端项目"),
        template,
        origin = Some(nebflow.core.project.ProjectOrigin("peer-uuid-9999", "/remote/ws2"))
      )
      .unsafeRunSync()
    val out = call(
      "action" -> io.circe.Json.fromString("show"),
      "name" -> io.circe.Json.fromString("tool-show-one")
    ).toOption.get
    assert(out.startsWith("Project 'tool-show-one'"), out)
    assert(out.contains("description: 远端项目"), out)
    assert(out.contains("device: peer-uuid-9999 (peer-uuid-9999)"), "roster miss shows raw id: " + out)
    assert(out.contains("origin: {device: peer-uuid-9999, path: /remote/ws2}"), out)
    assert(out.contains("brainRoot:"), out)
    assert(out.contains("mounted: "), out)

  test("show: unknown project → Left in the archive-family error style; missing name → explicit error"):
    val missing = call("action" -> io.circe.Json.fromString("show"), "name" -> io.circe.Json.fromString("ghost"))
    assert(missing.isLeft)
    assert(missing.swap.toOption.get.message.contains("not found"), missing.toString)
    val noName = call("action" -> io.circe.Json.fromString("show"))
    assert(noName.isLeft)
    assert(noName.swap.toOption.get.message.contains("show requires 'name'"), noName.toString)

  // ── update ───────────────────────────────────────────────────────

  test("update: writes through to project.json (only description) and reports the idempotent state"):
    seed("tool-upd", Some("旧句"))
    val r1 = call(
      "action" -> io.circe.Json.fromString("update"),
      "name" -> io.circe.Json.fromString("tool-upd"),
      "description" -> io.circe.Json.fromString("新句")
    )
    assert(r1.toOption.get.contains("updated"), r1.toString)
    val loaded = ProjectStore.load("tool-upd").unsafeRunSync()
    assertEquals(loaded.flatMap(_.description), Some("新句"))
    val r2 = call(
      "action" -> io.circe.Json.fromString("update"),
      "name" -> io.circe.Json.fromString("tool-upd"),
      "description" -> io.circe.Json.fromString("新句")
    )
    assert(r2.toOption.get.contains("unchanged"), "idempotent no-op is explicit: " + r2.toString)

  test("update: missing name / missing description → explicit errors, nothing written"):
    val noName = call("action" -> io.circe.Json.fromString("update"))
    assert(noName.isLeft && noName.swap.toOption.get.message.contains("update requires 'name'"), noName.toString)
    val noDesc = call(
      "action" -> io.circe.Json.fromString("update"),
      "name" -> io.circe.Json.fromString("tool-upd")
    )
    assert(noDesc.isLeft && noDesc.swap.toOption.get.message.contains("update requires 'description'"), noDesc.toString)

  test("update: unknown action still lists the full action menu"):
    val r = call("action" -> io.circe.Json.fromString("frobnicate"))
    assert(r.isLeft)
    assert(r.swap.toOption.get.message.contains("create (default), list, show, update, unmount"), r.toString)

end ProjectToolActionsSpec
