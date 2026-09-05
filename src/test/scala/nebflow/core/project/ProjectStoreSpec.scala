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
    yield
      assert(second.isLeft)
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
    yield
      assert(all.exists(_.name == "listme"))
  }

  test("R6: existing workspace .gitignore keeps user content and appends .nebflow/") {
    val ws2 = tempRoot / "ws-existing-gi"
    os.makeDir.all(ws2)
    os.write.over(ws2 / ".gitignore", "# user rules\nnode_modules/\n") // 无尾换行
    for
      created <- ProjectStore.create("gi-append", ws2.toString, None, template)
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
    for
      created <- ProjectStore.create("gi-existing", ws3.toString, None, template)
    yield
      assert(created.isRight)
      val content = os.read(ws3 / ".gitignore")
      assertEquals(content, "node_modules/\n.nebflow/\n", "must not duplicate the .nebflow/ entry")
  }

  test("R6: fresh workspace gets root .gitignore with .nebflow/ entry") {
    val ws4 = tempRoot / "ws-ignore-check"
    os.makeDir.all(ws4)
    for
      created <- ProjectStore.create("gi-ignore-check", ws4.toString, None, template)
    yield
      assert(created.isRight)
      val gi = ws4 / ".gitignore"
      assert(os.exists(gi))
      assertEquals(os.read(gi), ".nebflow/\n")
  }

end ProjectStoreSpec
