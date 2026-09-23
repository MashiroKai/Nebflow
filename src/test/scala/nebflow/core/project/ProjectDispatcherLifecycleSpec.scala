package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentLibrary, SharedResources}
import nebflow.core.PathUtil
import nebflow.core.processor.TaskStuckWatcher
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore, WsHub}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * dispatcher 会话生命周期验收（dispatcher 会话卡死修复）。
 *
 * 上游 2bf3395f 给分发器补了完成观察桥（Completed/Failed/Cancelled → 清 registry
 * + 停 agent），正常路径的「Processing idle 挂起」已收口；本 spec 锁定两条端到端契约：
 *
 * 1. 正常结束：TriggerDispatcher → 分发器 turn 完成 → 观察桥清理 → AgentControl
 *    视角（registry）无滞留 Processing dispatcher。同时验证 #28 的 wsSend 路由
 *    包装接线（agentStart 经 engine wsSendFn 可观测，nodeSessionId=dispatcher-*）。
 * 2. 人为卡死可处置：LLM 流挂死 → TaskStuckWatcher 按新恢复分支（kind=Flow +
 *    supervisorRef=观察桥）硬取消升级 → giveUp 经桥 Cancelled → registry 清理。
 *    修复前分发器（parentRef=None）落入根 agent 分支只 notice，幽灵行永滞留。
 *
 * ⚠ 口径登记（有意行为变化，非回归）：生产默认已由「4 拍 giveUp」升级为**分级链**
 * L1 halt → L2 hard-abort → L3 suspend/resume → L4 failed（`Defaults.HardRecoveryEnabled`
 * 默认 true；姊妹 spec `TaskStuckWatcherSpec` 的分级链用例按新口径断言）。本用例锁定的是
 * **回滚分支**（`!hard`，仍是现行产品代码：每轮 hard-cancel，`StopAttempts+2` 轮后经桥
 * Cancelled 终态收殓 ⇒ registry 清理）——通过 `TaskStuckWatcher.scan(hardRecovery = Some(false))`
 * 注入缝显式进入该分支，使其断言保持确定性。分级链本身就「不发桥信号 ⇒ 不自动释放」
 * （dispatcher 会话无 owning node，L3 释放腿空转）是**新口径的显式语义**，不由本用例覆盖。
 */
class ProjectDispatcherLifecycleSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-dispatcher-lifecycle"
  private val originalRoot = PathUtil.dataRoot

  // 类级：隔离 dataRoot + 预置 project-dispatcher 定义（ProjectActor 走
  // EntityLoader = dataRoot/agents/project-dispatcher）。
  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "project-dispatcher")

  os.write.over(
    tempRoot / "agents" / "project-dispatcher" / "agent.json",
    """{"name":"project-dispatcher","description":"lifecycle test dispatcher","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "project-dispatcher" / "system.md", "# project-dispatcher\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  /** 正常完成 LLM：一个文本 delta 即收尾。 */
  private class RecordingLlm extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))

    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  /** 挂死 LLM：流永不产出（人为卡死场景——分发起 turn 悬在流上，零 chunk）。 */
  private class HungLlm extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))

    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(IO.never)

  private def mkResources(system: ActorSystem, tmp: os.Path, llm: LlmHandle[IO]): IO[SharedResources] =
    for
      dispatcher <- cats.effect.std.Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- nebflow.core.FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
    yield SharedResources(
      llm = llm,
      dispatcher = dispatcher,
      sessionStore = SessionStore(tmp / "sessions", tmp / "tasks"),
      projectRoot = os.pwd,
      thinkingConfigRef = thinkingRef,
      rateLimiter = rateLimiter,
      fileChangeTracker = tracker,
      contextWindow = 100_000,
      agentLibrary = new AgentLibrary(tmp / "agents"),
      taskStore = FileTaskStore,
      historyArchiver = null,
      fileLockManager = fileLocks,
      sessionModelOverrides = modelOverrides,
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = system,
      voiceMutedRef = voiceMuted
    )

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 50.millis)(
    cond: IO[Boolean]
  ): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError("waitUntil: condition not met in time"))
          else IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  private def dispatcherEntries(resources: SharedResources): IO[List[String]] =
    resources.agentRegistry.get.map(_.keys.toList.filter(_.startsWith(ProjectActor.DispatcherSessionPrefix)))

  private def mount(
    name: String,
    ws: os.Path,
    system: ActorSystem,
    res: SharedResources,
    wsSend: Json => IO[Unit],
    idleWindowMs: Option[Long] = None,
    ttlCheckIntervalSec: Int = 30
  ): IO[ProjectRuntime] =
    val pd = ProjectDef(
      name = name,
      workspace = ws.toString,
      agentFile = (ws / "AGENTS.md").toString,
      createdAt = System.currentTimeMillis()
    )
    ProjectRuntimeRegistry.mount(
      pd,
      system,
      res,
      Some(wsSend),
      rootSessionId = "nebula-root",
      ttlCheckIntervalSec = ttlCheckIntervalSec,
      dispatcherIdleWindowMs = idleWindowMs
    )
  end mount

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  test("正常结束：turn 完成 → 会话进入空闲保活（不再即拆）；空闲窗到期 → 观察桥/扫描腿清 registry") {
    // 令 3 契约变更（2026-09-12）：改前判据 =「正常结束后 registry 无滞留
    // dispatcher」；保活落地后 turn 终态不再即拆 ⇒ 判据改为「窗口内保活 +
    // 窗口到期后无滞留」。窗口压到 1.5 s、节拍 1 s（等效实验：只压两个数值
    // 常量，被验代码路径逐行未变；详见 DispatcherIdleWindowSpec 头部声明）。
    val ws = tempRoot / "ws-normal"
    os.makeDir.all(ws)
    val system = ActorSystem(s"disp-normal-${scala.util.Random.nextInt(100000)}")
    val wsEvents = Ref.unsafe[IO, List[Json]](Nil)
    ProjectActor.ttlScanner(1.second).background.use { _ =>
      for
        resources <- mkResources(system, tempRoot, new RecordingLlm)
        rt <- mount("disp-normal", ws, system, resources, j => wsEvents.update(j :: _), Some(1500L), 1)
        actorRef = rt.actorRef.getOrElse(sys.error("ProjectActor must be spawned by mount"))
        _ <- (actorRef ! ProjectActor.ProjectCommand.TriggerDispatcher("建一个调研节点", "nebula-root")).void
        // #28 接线在位：agentStart 经路由包装到达 engine wsSend（nodeSessionId=dispatcher-*）
        _ <- waitUntil(20.seconds)(
          wsEvents.get.map(
            _.filter(_.hcursor.get[String]("type").toOption.contains("agentStart"))
              .exists(
                _.hcursor
                  .get[String]("nodeSessionId")
                  .toOption
                  .exists(_.startsWith(ProjectActor.DispatcherSessionPrefix))
              )
          )
        )
        // 令 3 新契约：turn 终态后会话**保活**（≥1 拍仍在）
        _ <- waitUntil(20.seconds)(dispatcherEntries(resources).map(_.nonEmpty))
        kept <- dispatcherEntries(resources)
        // 空闲窗到期（1.5 s 窗 + ≤1 s 节拍）→ 拆除，registry 无滞留
        _ <- waitUntil(60.seconds)(dispatcherEntries(resources).map(_.isEmpty))
        entries <- dispatcherEntries(resources)
        starts <- wsEvents.get.map(_.filter(_.hcursor.get[String]("type").toOption.contains("agentStart")))
        _ <- system.stopAll.handleErrorWith(_ => IO.unit)
      yield
        assertEquals(kept.size, 1, s"turn 终态后必须保活（同 id 存活），got $kept")
        assertEquals(entries, Nil, "idle window expired ⇒ no lingering dispatcher session")
        assert(starts.nonEmpty, "dispatcher agentStart must be observable via the routed wsSend")
    }
  }

  test("人为卡死：LLM 流挂死 → TaskStuckWatcher 升级 → 桥 Cancelled → registry 清理") {
    val ws = tempRoot / "ws-hang"
    os.makeDir.all(ws)
    val system = ActorSystem(s"disp-hang-${scala.util.Random.nextInt(100000)}")
    val wsHub = new WsHub()
    val stuckBroadcasts = Ref.unsafe[IO, List[Json]](Nil)
    for
      _ <- wsHub.register(j => stuckBroadcasts.update(j :: _))
      resources <- mkResources(system, tempRoot, new HungLlm)
      rt <- mount("disp-hang", ws, system, resources, _ => IO.unit)
      actorRef = rt.actorRef.getOrElse(sys.error("ProjectActor must be spawned by mount"))
      _ <- (actorRef ! ProjectActor.ProjectCommand.TriggerDispatcher("会卡死的任务", "nebula-root")).void
      // 分发器出现（注册先于 UserInput——挂死在流上也必已注册）
      _ <- waitUntil(20.seconds)(dispatcherEntries(resources).map(_.nonEmpty))
      // giveUp 形态（回滚分支，4 轮扫描：attempt ≥ StopAttempts+2 → giveUp）：threshold
      // 300ms，先静置 600ms。🔴 显式 `hardRecovery = Some(false)` 进回滚分支——生产默认
      // 走分级链 L1→L4（有意行为变化，见类头「口径登记」），而本用例锁的是 giveUp 契约
      // （桥 Cancelled ⇒ registry 清理）；不注入则该断点恒不可达（L3 释放腿对无 owning
      // node 的会话空转、L4 只广播 failed）。
      _ <- IO.sleep(600.millis)
      stopCounts <- cats.effect.Ref.of[IO, Map[String, Int]](Map.empty)
      _ <- TaskStuckWatcher.scan(resources, wsHub, 300L, stopCounts, hardRecovery = Some(false))
      _ <- TaskStuckWatcher.scan(resources, wsHub, 300L, stopCounts, hardRecovery = Some(false))
      _ <- TaskStuckWatcher.scan(resources, wsHub, 300L, stopCounts, hardRecovery = Some(false))
      _ <- TaskStuckWatcher.scan(resources, wsHub, 300L, stopCounts, hardRecovery = Some(false))
      // giveUp → 桥 Cancelled → 清 registry + 停 agent
      _ <- waitUntil(20.seconds)(dispatcherEntries(resources).map(_.isEmpty))
      entries <- dispatcherEntries(resources)
      broadcasts <- stuckBroadcasts.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(entries, Nil, "stuck dispatcher must be cleaned up via the bridge Cancelled channel")
      assert(
        broadcasts.exists(_.hcursor.get[String]("action").toOption.contains("restart")),
        s"taskStuck(action=restart) must be broadcast, got: $broadcasts"
      )
    end for
  }

end ProjectDispatcherLifecycleSpec
