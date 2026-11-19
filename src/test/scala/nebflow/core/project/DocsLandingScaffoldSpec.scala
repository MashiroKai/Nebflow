package nebflow.core.project

import munit.CatsEffectSuite
import nebflow.shared.PathUtil

import scala.concurrent.duration.*

/**
 * 文档落位脚手架验收（作者令 2026-11-03 20:02：「docs 放各 projects 下的 .stand
 * （放 project.json 的地方）目录下的 docs，不放各项目目录下」）。
 *
 * 三条断言（任务书验收面「脚手架」）：
 *   ① 新建项目（走**生产模板** `ProjectCreateService.AgentMdTemplateFull`）⇒
 *      `projects/<name>/.stand/docs/` 自动出现（且不需要手工建）；
 *   ② 该工作区 AGENTS.md **含新落位说明**（路径 + 「不是 workspace docs」+ 校验命令
 *      三要素齐），且模板里**不再**出现旧口径的说法；
 *   ③ 幂等重挂补缺：文档库已存在 ⇒ 报告 skipped，不重建、不覆盖（存量项目零回滚）。
 */
class DocsLandingScaffoldSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 30.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-docs-landing"
  private val originalRoot: os.Path = PathUtil.dataRoot

  override def beforeAll(): Unit =
    os.remove.all(tempRoot)
    os.makeDir.all(tempRoot)
    PathUtil.setDataRoot(tempRoot)

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  test("新建项目 ⇒ 注册目录 .stand/docs/ 自动出现，AGENTS.md 带落位说明（生产模板整链）"):
    val name = "landing-new"
    val ws = tempRoot / "ws" / name
    os.makeDir.all(ws)
    val template = ProjectCreateService.AgentMdTemplateFull
    val (pd, report) = ProjectStore
      .createWithScaffold(name, ws.toString, Some("落位验收"), template)
      .map(_.toOption.getOrElse(fail("create should succeed")))
      .unsafeRunSync()

    // ① 文档库（注册目录侧，与 project.json 同住）
    val ledger = ProjectStore.ledgerDocsDir(name)
    assertEquals(ledger, tempRoot / "projects" / name / ".stand" / "docs")
    assert(os.isDir(ledger), s"新建项目必须自动建文档库：$ledger")
    assert(!os.exists(ws / "docs"), "创建面禁在 workspace 里建 docs/（落位是注册目录）")
    assert(
      report.items.exists(i => i.item == ".stand/docs/" && i.created),
      s"逐件报告须含文档库这件：${report.render}"
    )
    assertEquals(pd.workspace, ws.toString)

    // ② AGENTS.md 落位说明三要素
    val agents = os.read(ws / "AGENTS.md")
    assert(agents.contains("## Document landing (docs discipline)"), agents)
    assert(agents.contains("<data_root>/projects/<this project>/.stand/docs/"), agents)
    assert(agents.contains("NOT the knowledge vault"), agents)
    assert(agents.contains("--project <this project>"), agents)
    assert(agents.contains("[[wikilink]]"), "Obsidian 格式要求须在模板里")
    // 反向：旧口径（workspace 内的 docs 是知识库）不得再出现
    assert(!agents.contains("workspace's `docs/` subtree"), agents)

  test("幂等重挂补缺 ⇒ 文档库已存在报 skipped（不重建、不覆盖既有内容）"):
    val name = "landing-idem"
    val ws = tempRoot / "ws" / name
    os.makeDir.all(ws)
    val ledger = ProjectStore.ledgerDocsDir(name)
    os.makeDir.all(ledger)
    os.write.over(ledger / "existing.md", "# 既有文档\n")
    val (_, report) = ProjectStore
      .createWithScaffold(name, ws.toString, None, ProjectCreateService.AgentMdTemplateFull)
      .map(_.toOption.getOrElse(fail("create should succeed")))
      .unsafeRunSync()
    val item = report.items.find(_.item == ".stand/docs/").getOrElse(fail(s"missing item: ${report.render}"))
    assert(!item.created, item.detail)
    assertEquals(os.read(ledger / "existing.md"), "# 既有文档\n", "既有文档零触碰")

  test("设备项目（远端 origin）⇒ 本地零 workspace 脚手架，但文档库仍落引擎侧注册目录"):
    val remoteWs = "/remote-dev/x/repo"
    val (_, report) = ProjectStore
      .createWithScaffold(
        "landing-remote",
        remoteWs,
        None,
        ProjectCreateService.AgentMdTemplateFull,
        origin = Some(ProjectOrigin("dev-peer-1", remoteWs))
      )
      .map(_.toOption.getOrElse(fail("create should succeed")))
      .unsafeRunSync()
    assertEquals(report.items.map(_.item), List(".stand/docs/"))
    assert(os.isDir(ProjectStore.ledgerDocsDir("landing-remote")), "设备项目的文档库仍在大脑侧（引擎注册目录）")

end DocsLandingScaffoldSpec
