package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.SharedResources
import nebflow.core.PathUtil
import nebflow.llm.{ModelCandidate, ThinkingConfig}

import scala.concurrent.duration.*

/**
 * #37 启动自动挂载回归（重启窗口收尾项）：
 *
 * ProjectRuntimeRegistry.mountAll——磁盘已有项目 → 幂等挂载（rootSessionId=顶层
 * Nebula 主会话），重复调用跳过已挂载。与 ProjectCreate 幂等挂载（运行时主动）
 * 互补：本函数管重启免人工。
 */
class ProjectStartupMountSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-startup-mount"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit =
    ProjectRuntimeRegistry.clear
    PathUtil.setDataRoot(originalRoot)

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

  test("mountAll mounts disk projects with the top-level rootSessionId; repeat call skips (idempotent)") {
    val ws1 = tempRoot / "ws1"
    val ws2 = tempRoot / "ws2"
    os.makeDir.all(ws1)
    os.makeDir.all(ws2)
    val system = ActorSystem(s"sm-${scala.util.Random.nextInt(100000)}")
    val res = testResources(ws1)
    for
      _ <- ProjectStore.create("p-start-1", ws1.toString, None, "# p1")
      _ <- ProjectStore.create("p-start-2", ws2.toString, None, "# p2")
      projects <- ProjectStore.list()
      mounted1 <- ProjectRuntimeRegistry.mountAll(projects, "nebula-root", system, res)
      rt1 <- ProjectRuntimeRegistry.get("p-start-1")
      rt2 <- ProjectRuntimeRegistry.get("p-start-2")
      mounted2 <- ProjectRuntimeRegistry.mountAll(projects, "nebula-root", system, res)
      _ <- ProjectRuntimeRegistry.clear
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(mounted1, 2, "both disk projects must mount on startup")
      assert(rt1.isDefined && rt2.isDefined, "both projects registered")
      assertEquals(rt1.get.engine.rootSessionId, "nebula-root", "engine must anchor to top-level root")
      assertEquals(rt2.get.engine.rootSessionId, "nebula-root")
      assertEquals(mounted2, 0, "already-mounted projects must be skipped (idempotent)")
  }

  test("mountAll fail-soft: unmountable project workspace is skipped with warn; other projects still mount") {
    // #46 回归：单项目 mount 失败不再拖垮整批启动。构造一个 workspace 不可创建的
    // 项目（祖先路径存在但为普通文件，os.makeDir.all 必抛）+ 一个正常项目——
    // mountAll 应记 WARN 跳过坏项目、照常挂载好项目、gateway 启动不崩。
    val wsGood = tempRoot / "ws-good"
    os.makeDir.all(wsGood)
    // 自包含：tempRoot 为类级共享，先清掉上一个用例留下的项目目录，避免 list() 混入
    os.remove.all(tempRoot / "projects")
    os.makeDir.all(tempRoot / "projects")
    // 阻塞文件：<tempRoot>/bad-blocker 是普通文件 → 其下 workspace 的 .nebflow 不可创建
    val badBlocker = tempRoot / "bad-blocker"
    os.write(badBlocker, "not a directory")
    val badWorkspace = (badBlocker / "sub").toString
    // 坏项目 project.json 直接落盘（模拟磁盘存量项目 workspace 已不可挂载）
    val badDir = tempRoot / "projects" / "p-fs-bad"
    os.makeDir.all(badDir)
    os.write(
      badDir / "project.json",
      s"""{"name":"p-fs-bad","description":null,"workspace":"$badWorkspace","agentFile":"$badWorkspace/AGENTS.md","feedbackMode":null,"createdAt":${System.currentTimeMillis()}}"""
    )
    val system = ActorSystem(s"sm-${scala.util.Random.nextInt(100000)}")
    val res = testResources(wsGood)
    for
      _ <- ProjectStore.create("p-fs-good", wsGood.toString, None, "# good")
      projects <- ProjectStore.list()
      mounted <- ProjectRuntimeRegistry.mountAll(projects, "nebula-root", system, res)
      rtGood <- ProjectRuntimeRegistry.get("p-fs-good")
      rtBad <- ProjectRuntimeRegistry.get("p-fs-bad")
      _ <- ProjectRuntimeRegistry.clear
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(mounted, 1, "only the good project must mount; unmountable one skipped")
      assert(rtGood.isDefined, "good project registered")
      assert(rtBad.isEmpty, "unmountable project not registered (fail-soft skip)")
  }

end ProjectStartupMountSpec
