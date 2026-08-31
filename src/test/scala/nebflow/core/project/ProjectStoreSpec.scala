package nebflow.core.project

import cats.effect.IO
import munit.CatsEffectSuite
import nebflow.core.PathUtil

import scala.concurrent.duration.*

/**
 * ProjectStore 单测（#28 阶段 0，§1.1）——项目定义 + 工作区脚手架：
 * - create：projects/<name>/project.json + 工作区 .nebflow/（Agent.md + .gitignore）
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
    """# demo — Agent.md
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
      assert(os.exists(ws / ".nebflow" / "Agent.md"))
      assert(os.exists(ws / ".nebflow" / ".gitignore"))
      assertEquals(os.read(ws / ".nebflow" / ".gitignore"), ".nebflow/\n")
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

end ProjectStoreSpec
