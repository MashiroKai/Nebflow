package nebflow.core.project

import cats.effect.IO
import munit.CatsEffectSuite
import nebflow.core.PathUtil

import scala.concurrent.duration.*

/**
 * ProjectStore 单测（#28 阶段 0，§1.1）——项目定义 + 工作区脚手架：
 * - create：projects/<name>/project.json + 工作区根 AGENTS.md（agent 指令模板）+ .nebflow/（.gitignore）
 * - 重复 create 拒绝（防覆盖）
 * - load 往返
 */
class ProjectStoreSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 30.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-project-store"
  private val ws: os.Path = tempRoot / "workspace"

  // 每个 test 前重置 dataRoot + 清空工作区（setDataRoot 是全局态，类级设一次）
  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(ws)

  private val template =
    """# demo — AGENTS.md
项目级 agent 指令。
"""

  test("create writes project.json + workspace .nebflow scaffolding") {
    for
      created <- ProjectStore.create("demo", ws.toString, Some("demo project"), template)
      pd <- ProjectStore.load("demo")
    yield
      assert(created.isRight)
      val right = created.toOption.get
      assertEquals(right.name, "demo")
      assertEquals(right.workspace, ws.toString)
      assert(os.exists(ProjectStore.projectJsonPath("demo")))
      assert(os.exists(ws / "AGENTS.md"), "AGENTS.md template must be at workspace root")
      assert(!os.exists(ws / ".nebflow" / ".gitignore"), ".gitignore must NOT be inside .nebflow/ (R6 position fix)")
      assert(os.exists(ws / ".gitignore"), ".gitignore must be at workspace root (R6)")
      assertEquals(os.read(ws / ".gitignore"), ".nebflow/\n")
      assertEquals(pd.map(_.name), Some("demo"))
  }

  test("duplicate create rejected (no overwrite)") {
    for
      _ <- ProjectStore.create("dup", ws.toString, None, template)
      second <- ProjectStore.create("dup", ws.toString, None, template)
    yield assert(second.isLeft)
  }

  test("invalid name (path traversal) rejected") {
    ProjectStore.create("../evil", ws.toString, None, template).map { r =>
      assert(r.isLeft)
    }
  }

  test("load: absent project → None") {
    ProjectStore.load("no-such-project").map(r => assertEquals(r, None))
  }

  test("list returns created projects") {
    for
      _ <- ProjectStore.create("listme", (ws / "listme").toString, None, template)
      all <- ProjectStore.list()
    yield assert(all.exists(_.name == "listme"))
  }

  test("R6: existing workspace .gitignore keeps user content and appends .nebflow/") {
    val ws2 = tempRoot / "ws-existing-gi"
    os.makeDir.all(ws2)
    os.write.over(ws2 / ".gitignore", "# user rules\nnode_modules/\n") // 无尾换行
    for created <- ProjectStore.create("gi-append", ws2.toString, None, template)
    yield
      assert(created.isRight)
      val content = os.read(ws2 / ".gitignore")
      assert(content.startsWith("# user rules\nnode_modules/\n"), "user content must be preserved")
      assert(content.contains(".nebflow/"), "must append .nebflow/")
      assertEquals(os.read(ws2 / "AGENTS.md"), template)
  }

  test("R6: existing workspace .gitignore already containing .nebflow/ is not duplicated") {
    val ws3 = tempRoot / "ws-has-gi"
    os.makeDir.all(ws3)
    os.write.over(ws3 / ".gitignore", "node_modules/\n.nebflow/\n")
    for created <- ProjectStore.create("gi-existing", ws3.toString, None, template)
    yield
      assert(created.isRight)
      val content = os.read(ws3 / ".gitignore")
      assertEquals(content, "node_modules/\n.nebflow/\n", "must not duplicate the .nebflow/ entry")
  }

  test("R6: fresh workspace gets root .gitignore with .nebflow/ entry") {
    val ws4 = tempRoot / "ws-ignore-check"
    os.makeDir.all(ws4)
    for created <- ProjectStore.create("gi-ignore-check", ws4.toString, None, template)
    yield
      assert(created.isRight)
      val gi = ws4 / ".gitignore"
      assert(os.exists(gi))
      assertEquals(os.read(gi), ".nebflow/\n")
  }

  // S2 2026-09-17 12:09 裁定单 ③-8：「缺件即补、既有永不覆盖」——既有件是**不可覆写**的
  // （变异面：若把 ensureScaffold 改成无条件覆写，本条必红）。
  test("③-8: pre-existing AGENTS.md is never overwritten (additive-only)") {
    val ws5 = tempRoot / "ws-existing-agents"
    os.makeDir.all(ws5)
    val userMd = "# 用户自定 AGENTS.md\n别动我\n"
    os.write.over(ws5 / "AGENTS.md", userMd)
    for created <- ProjectStore.create("agents-keep", ws5.toString, None, template)
    yield
      assert(created.isRight)
      assertEquals(os.read(ws5 / "AGENTS.md"), userMd, "既有 AGENTS.md 必须逐字节不变")
  }

  test("③-8: pre-existing .nebflow/ directory content is never touched by create") {
    val ws6 = tempRoot / "ws-existing-nebflow"
    os.makeDir.all(ws6 / ".nebflow")
    os.write.over(ws6 / ".nebflow" / "user-note.md", "keep\n")
    for created <- ProjectStore.create("nebflow-keep", ws6.toString, None, template)
    yield
      assert(created.isRight)
      assertEquals(os.read(ws6 / ".nebflow" / "user-note.md"), "keep\n", "既有 .nebflow/ 内容必须不动")
      assert(!os.exists(ws6 / ".nebflow" / "flow-map.json"), "创建面禁预写 flow-map.json（由挂载首写）")
  }

  // S2 2026-09-17 12:09 裁定单 ③-8/③-9：脚手架单点 + 逐件报告 + 占用扫描的全量读源。
  test("③-8/③-9: ensureScaffold is per-item additive — only the missing piece is backfilled") {
    val ws7 = tempRoot / "ws-ensure-additive"
    os.makeDir.all(ws7)
    for
      first <- ProjectStore.ensureScaffold(ws7, template)
      _ <- IO(os.remove(ws7 / "AGENTS.md"))
      second <- ProjectStore.ensureScaffold(ws7, template)
    yield
      assertEquals(
        first.items.map(i => i.item -> i.created),
        List(".nebflow/" -> true, "AGENTS.md" -> true, ".gitignore" -> true),
        "首次补缺：三件全 created"
      )
      assert(first.render.contains("3 created, 0 skipped"), first.render)
      assertEquals(
        second.items.map(i => i.item -> i.created),
        List(".nebflow/" -> false, "AGENTS.md" -> true, ".gitignore" -> false),
        "二次：只补缺的 AGENTS.md，其余 skipped"
      )
      assertEquals(os.read(ws7 / "AGENTS.md"), template)
      assertEquals(second.render.contains("AGENTS.md created"), true, second.render)
      assert(second.render.contains("1 created, 2 skipped"), second.render)
      assert(!os.exists(ws7 / ".nebflow" / "flow-map.json"), "创建面禁预写 flow-map.json（由挂载首写）")
    end for
  }

  test("③-9: .gitignore append / already-present is reported honestly (no duplicate line, user bytes kept)") {
    val ws8 = tempRoot / "ws-ensure-gitignore"
    os.makeDir.all(ws8)
    os.write.over(ws8 / ".gitignore", "node_modules/\n")
    for
      appended <- ProjectStore.ensureScaffold(ws8, template)
      afterFirst <- IO(os.read(ws8 / ".gitignore"))
      again <- ProjectStore.ensureScaffold(ws8, template)
      afterSecond <- IO(os.read(ws8 / ".gitignore"))
    yield
      val giFirst = appended.items.find(_.item == ".gitignore").getOrElse(fail("item missing"))
      assert(giFirst.created, "既有 .gitignore 缺行 ⇒ 追加 = created")
      assert(giFirst.detail.contains("appended"), giFirst.detail)
      assertEquals(afterFirst, "node_modules/\n.nebflow/\n", "用户内容零改动 + 恰一行 .nebflow/")
      val giSecond = again.items.find(_.item == ".gitignore").getOrElse(fail("item missing"))
      assert(!giSecond.created, "已含 .nebflow/ 行 ⇒ skipped")
      assert(giSecond.detail.contains("already lists"), giSecond.detail)
      assertEquals(afterSecond, afterFirst, "字节不变、不重复追加")
  }

  test("③-8: createWithScaffold reports per-item, keeps create's Left semantics (no exists-and-write)") {
    val ws9 = tempRoot / "ws-create-with-scaffold"
    os.makeDir.all(ws9)
    for
      first <- ProjectStore.createWithScaffold("cw-one", ws9.toString, None, template)
      second <- ProjectStore.createWithScaffold("cw-one", ws9.toString, None, template)
    yield
      assert(first.isRight)
      val (pd, report) = first.toOption.get
      assertEquals(pd.name, "cw-one")
      assertEquals(pd.workspace, ws9.toString)
      assertEquals(report.createdCount, 3)
      assert(report.render.contains("AGENTS.md created"), report.render)
      assert(second.isLeft, "定义已存在仍返 Left —— 防覆盖语义不变（禁改成「已存在也写」）")
      assert(second.swap.toOption.get.contains("already exists"), second.swap.toOption.get)
  }

  test("④-4: listAll() carries archived definitions (occupancy scan source) while list() still filters") {
    val wsA = tempRoot / "ws-listall-a"
    val wsB = tempRoot / "ws-listall-b"
    List(wsA, wsB).foreach(os.makeDir.all)
    for
      _ <- ProjectStore.create("la-one", wsA.toString, None, template)
      _ <- ProjectStore.create("la-two", wsB.toString, None, template)
      _ <- ProjectStore.archive("la-two")
      all <- ProjectStore.listAll()
      visible <- ProjectStore.list()
    yield
      assert(all.exists(_.name == "la-two"), "占用扫描必须看见归档定义（保守默认：宁误拒不误建）")
      assert(all.find(_.name == "la-two").exists(_.archived.contains(true)))
      assert(visible.exists(_.name == "la-one"), "list() 语义不变：非归档仍在列")
      assert(!visible.exists(_.name == "la-two"), "list() 语义不变：归档不在列")
  }

end ProjectStoreSpec
