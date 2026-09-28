package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.parser.parse as jsonParse
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.SharedResources
import nebflow.core.PathUtil
import nebflow.llm.{ModelCandidate, ThinkingConfig}

import scala.concurrent.duration.*

/**
 * Project 归档回归（迁移方案 v2 §6.1「Project 面板归档按钮」后端语义）：
 * - 归档 = project.json 原位插键（archived/archivedAt），既有字段逐字节稳定
 * - 零删除零移动：workspace 文件与定义目录全部保留（只有这一个文件被重写）
 * - 列表出口过滤：ProjectStore.list 源头滤掉归档项 → 面板 API 与 startupMount 同源
 * - startupMount 跳过归档项：重启后不自动挂载（GatewayMain:429 list → mountAll 链）
 * - 幂等：重复归档不重写文件（raw 文本逐字节相同、archivedAt 不变）
 * - 无自动归档路径：归档仅由显式 API 触发；本 spec 只锁持久化/过滤/挂载语义
 */
class ProjectArchiveSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-project-archive"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit =
    ProjectRuntimeRegistry.clear
    PathUtil.setDataRoot(originalRoot)

  private val template = "# archive-spec — AGENTS.md\n"

  private def mkWs(tag: String): os.Path =
    val ws = tempRoot / s"ws-$tag-${scala.util.Random.nextInt(100000)}"
    os.makeDir.all(ws)
    ws

  /** 挂载测试用 SharedResources（对齐 ProjectStartupMountSpec 的 null-容忍构造）。 */
  private def testResources(projectRoot: os.Path): SharedResources =
    new SharedResources(
      llm = null,
      dispatcher = null,
      sessionStore = null,
      projectRoot = projectRoot,
      thinkingConfigRef = Ref.unsafe[IO, ThinkingConfig](ThinkingConfig()),
      rateLimiter = null,
      fileChangeTracker = null,
      contextWindow = 100_000,
      agentLibrary = null,
      taskStore = null,
      historyArchiver = null,
      fileLockManager = null,
      sessionModelOverrides = Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty),
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = null,
      voiceMutedRef = Ref.unsafe[IO, Boolean](false)
    )

  private def rawJson(name: String): Json =
    jsonParse(os.read(ProjectStore.projectJsonPath(name))).toOption.get

  test("archive writes marker into project.json; pre-existing fields unchanged; load decodes it") {
    val ws = mkWs("marker")
    val wsFiles = mkWs("marker-ws")
    for
      _ <- ProjectStore.create("arch-marker", ws.toString, Some("before desc"), template)
      before = rawJson("arch-marker")
      at <- ProjectStore.archive("arch-marker")
      after = rawJson("arch-marker")
      pd <- ProjectStore.load("arch-marker")
    yield
      assert(at.isRight, "archive must succeed")
      // 新键语义
      assertEquals(after.hcursor.downField("archived").as[Boolean], Right(true), "archived must be true")
      assertEquals(
        after.hcursor.downField("archivedAt").as[Long],
        Right(at.toOption.get),
        "archivedAt must match returned timestamp"
      )
      // 既有字段逐值稳定（name/description/workspace/agentFile/createdAt 不动）
      val keep = List("name", "description", "workspace", "agentFile", "createdAt")
      keep.foreach { k =>
        assertEquals(after.hcursor.downField(k).focus, before.hcursor.downField(k).focus, s"field '$k' must be untouched")
      }
      // 键集 = 旧键集 + 恰好两个新键（无 null 填充、无重排产物）
      val beforeKeys = before.asObject.map(_.keys.toSet).get
      val afterKeys = after.asObject.map(_.keys.toSet).get
      assertEquals(afterKeys, beforeKeys + "archived" + "archivedAt", "exactly the two archive keys may be added")
      // 解码侧：load 可读回归档态（Option 语义）
      assertEquals(pd.map(_.archived), Some(Some(true)), "load must decode archived=true")
      assert(pd.flatMap(_.archivedAt).isDefined, "load must decode archivedAt")
      assert(wsFiles.toString.nonEmpty) // 占位防 unused 警告
  }

  test("archive is zero-delete zero-move: workspace files and definition dir all kept, content byte-identical") {
    val ws = mkWs("files")
    for
      _ <- ProjectStore.create("arch-files", ws.toString, None, template)
      _ <- IO.blocking {
        // 预置工作区内容：模板 + .nebflow/flow-map.json 脚手架 + 用户文件
        os.write.over(ws / "AGENTS.md", "# user customized\n")
        os.write.over(ws / ".nebflow" / "flow-map.json", """{"v":1,"project":"arch-files","updatedAt":0,"nodes":{}}""")
        os.makeDir.all(ws / "src")
        os.write.over(ws / "src" / "main.txt", "user data")
      }
      // 快照：全部文件相对路径 → 内容（零移动/零删除/零内容变更的证据基线）
      beforeSnapshot <- IO.blocking {
        os.walk(ws).filter(os.isFile).map(p => p.relativeTo(ws).toString -> os.read(p)).toMap
      }
      _ <- ProjectStore.archive("arch-files")
      afterSnapshot <- IO.blocking {
        os.walk(ws).filter(os.isFile).map(p => p.relativeTo(ws).toString -> os.read(p)).toMap
      }
    yield
      assertEquals(afterSnapshot, beforeSnapshot, "workspace must be byte-identical after archive")
      // 定义目录与 project.json 仍在（打标记而非删除）
      assert(os.exists(ProjectStore.projectDir("arch-files")), "project definition dir must survive")
      assert(os.exists(ProjectStore.projectJsonPath("arch-files")), "project.json must survive")
  }

  test("list() filters archived projects (panel API + startupMount single source)") {
    val wsA = mkWs("lista")
    val wsB = mkWs("listb")
    for
      _ <- ProjectStore.create("arch-keep", wsA.toString, None, template)
      _ <- ProjectStore.create("arch-gone", wsB.toString, None, template)
      before <- ProjectStore.list()
      _ <- ProjectStore.archive("arch-gone")
      after <- ProjectStore.list()
    yield
      assert(before.exists(_.name == "arch-gone"), "pre-archive list must contain the project")
      assert(!after.exists(_.name == "arch-gone"), "archived project must vanish from list")
      assert(after.exists(_.name == "arch-keep"), "active project must stay listed")
  }

  test("startupMount skips archived project (list→mountAll chain; runtime absent, active one mounted)") {
    val wsA = mkWs("mounta")
    val wsB = mkWs("mountb")
    val tag = scala.util.Random.nextInt(100000)
    val system = ActorSystem(s"arch-$tag")
    val keep = s"arch-mk-$tag"
    val gone = s"arch-mg-$tag"
    for
      _ <- ProjectStore.create(keep, wsA.toString, None, template)
      _ <- ProjectStore.create(gone, wsB.toString, None, template)
      _ <- ProjectStore.archive(gone)
      projects <- ProjectStore.list() // GatewayMain:429 同源——归档项必须已不在列
      mounted <- ProjectRuntimeRegistry.mountAll(projects, "nebula-root", system, testResources(wsA))
      rtKeep <- ProjectRuntimeRegistry.get(keep)
      rtGone <- ProjectRuntimeRegistry.get(gone)
      _ <- ProjectRuntimeRegistry.clear
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // tempRoot 跨用例共享（前面用例的活跃项目仍在根下）→ 挂载数封闭断言：
      // list 返回几个挂几个（list 已滤归档项），且归档项目绝不在挂载输入里
      assert(!projects.exists(_.name == gone), "archived project must be absent from the startupMount input (ProjectStore.list)")
      assertEquals(mounted, projects.length, "every project list() returns must mount; archived ones never reach mountAll")
      assert(rtKeep.isDefined, "active project must be mounted")
      assertEquals(rtGone, None, "archived project must NOT be mounted after restart")
  }

  test("archive is idempotent: second call does not rewrite the file, archivedAt unchanged") {
    val ws = mkWs("idem")
    for
      _ <- ProjectStore.create("arch-idem", ws.toString, None, template)
      first <- ProjectStore.archive("arch-idem")
      rawAfterFirst <- IO.blocking(os.read(ProjectStore.projectJsonPath("arch-idem")))
      second <- ProjectStore.archive("arch-idem")
      rawAfterSecond <- IO.blocking(os.read(ProjectStore.projectJsonPath("arch-idem")))
    yield
      assert(first.isRight && second.isRight, "both archive calls must succeed")
      assertEquals(first, second, "idempotent re-archive must return the same archivedAt")
      assertEquals(rawAfterSecond, rawAfterFirst, "file must not be rewritten on re-archive (byte-identical)")
  }

  test("archive nonexistent project → Left; invalid name → Left") {
    for
      missing <- ProjectStore.archive("arch-no-such-project")
      invalid <- ProjectStore.archive("../evil")
    yield
      assert(missing.isLeft, "missing project must be rejected")
      assert(invalid.isLeft, "path-traversal name must be rejected")
  }

end ProjectArchiveSpec
