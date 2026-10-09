package nebflow.core.project

import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.shared.PathUtil

import java.nio.file.Files

/**
 * project.origin 落地（v2.1 阶段 1 §A.5，零迁移纪律）承重断言：
 *   - ProjectOrigin codec 往返 + self 字面判据；
 *   - **零迁移**：存量 project.json（无 origin 键）照常解码 origin=None；带 origin
 *     的盘面往返无损；
 *   - createWithScaffold 远端腿：origin 绑对端 ⇒ **本地零脚手架**（空报告 = 远端腿
 *     已在对端完成的锚）+ agentFile = origin.path/AGENTS.md；本机腿 = 现状脚手架。
 */
class ProjectOriginSpec extends CatsEffectSuite:

  private var savedRoot: os.Path = null
  private var tmp: os.Path = null

  override def beforeEach(context: munit.BeforeEach): Unit =
    savedRoot = PathUtil.dataRoot
    tmp = os.Path(Files.createTempDirectory("nb-origin"))
    PathUtil.setDataRoot(tmp)

  override def afterEach(context: munit.AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(tmp)

  test("isSelfLiteral: self / SELF / blank = self; anything else = a device binding") {
    assert(ProjectOrigin.isSelfLiteral("self"))
    assert(ProjectOrigin.isSelfLiteral("SELF"))
    assert(ProjectOrigin.isSelfLiteral(""))
    assert(ProjectOrigin.isSelfLiteral("   "))
    assert(!ProjectOrigin.isSelfLiteral("dev-remote-9"))
  }

  test("codec: origin round-trips; absent key decodes to None (zero migration)") {
    import ProjectDef.given

    val local = ProjectDef(name = "p1", workspace = "/ws/p1", agentFile = "/ws/p1/AGENTS.md", createdAt = 1L)
    val decodedLocal = io.circe.parser.decode[ProjectDef](local.asJson.noSpaces)
    assertEquals(decodedLocal.map(_.origin), Right(None))

    val remote = local.copy(
      origin = Some(ProjectOrigin("dev-remote-9", "/remote/ws"))
    )
    val encoded = remote.asJson
    assert(encoded.hcursor.downField("origin").focus.isDefined)
    val decodedRemote = io.circe.parser.decode[ProjectDef](encoded.noSpaces)
    assertEquals(decodedRemote.map(_.origin), Right(Some(ProjectOrigin("dev-remote-9", "/remote/ws"))))

    // 存量形态（手写旧盘面，无 origin 键）= None，零迁移。
    val legacyJson = """{"name":"old","workspace":"/ws/old","agentFile":"/ws/old/AGENTS.md","createdAt":1}"""
    assertEquals(io.circe.parser.decode[ProjectDef](legacyJson).map(_.origin), Right(None))
  }

  test("createWithScaffold remote leg: zero local scaffold, agentFile = origin.path/AGENTS.md, definition written") {
    val ws = "/remote-machines/host2/repo" // 对端盘路径——本机**不得**创建（路径错觉防护）
    val result = ProjectStore.createWithScaffold(
      "cross-dev",
      ws,
      None,
      "# AGENTS template",
      origin = Some(ProjectOrigin("dev-remote-9", ws))
    )
    val (pd, report) = result.map(_.toOption.get).unsafeRunSync()
    assertEquals(pd.origin, Some(ProjectOrigin("dev-remote-9", ws)))
    assertEquals(pd.agentFile, s"$ws/AGENTS.md")
    // 本地零脚手架（脚手架由远端腿在对端完成——本地落笔只有定义）。
    assertEquals(report.items, Nil)
    assertEquals(os.exists(os.Path(ws)), false)
    // 定义就绪（createWithScaffold 的唯一本机落笔 = project.json）。
    assertEquals(os.exists(ProjectStore.projectJsonPath("cross-dev")), true)
  }

  test("createWithScaffold local leg (no origin): scaffold present, byte-identical status quo") {
    val ws = tmp / "ws" / "localproj"
    os.makeDir.all(ws)
    val result = ProjectStore.createWithScaffold("local-proj", ws.toString, None, "# AGENTS template", origin = None)
    val (pd, report) = result.map(_.toOption.get).unsafeRunSync()
    assertEquals(pd.origin, None)
    assertEquals(pd.agentFile, (ws / "AGENTS.md").toString)
    assert(report.items.nonEmpty) // 本机腿照常补脚手架
  }

end ProjectOriginSpec
