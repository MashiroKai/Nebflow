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

  private def mount(name: String, ws: os.Path, system: ActorSystem, res: SharedResources, wsSend: Json => IO[Unit]): IO[ProjectRuntime] =
    val pd = ProjectDef(
      name = name,
      workspace = ws.toString,
      agentFile = (ws / "AGENTS.md").toString,
      createdAt = System.currentTimeMillis()
    )
    ProjectRuntimeRegistry.mount(pd, system, res, Some(wsSend), rootSessionId = "nebula-root")

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  test("正常结束：turn 完成 → 观察桥清 registry（AgentControl 视角无滞留 Processing dispatcher）") {
    val ws = tempRoot / "ws-normal"
    os.makeDir.all(ws)
    val system = ActorSystem(s"disp-normal-${scala.util.Random.nextInt(100000)}")
    val wsEvents = Ref.unsafe[IO, List[Json]](Nil)
    for
      resources <- mkResources(system, tempRoot, new RecordingLlm)
      rt <- mount("disp-normal", ws, system, resources, j => wsEvents.update(j :: _))
      actorRef = rt.actorRef.getOrElse(sys.error("ProjectActor must be spawned by mount"))
      _ <- (actorRef ! ProjectActor.ProjectCommand.TriggerDispatcher("建一个调研节点", "nebula-root")).void
      // #28 接线在位：agentStart 经路由包装到达 engine wsSend（nodeSessionId=dispatcher-*）
      _ <- waitUntil(20.seconds)(wsEvents.get.map(
        _.filter(_.hcursor.get[String]("type").toOption.contains("agentStart"))
          .exists(_.hcursor.get[String]("nodeSessionId").toOption.exists(_.startsWith(ProjectActor.DispatcherSessionPrefix)))
      ))
      // 完成 → 桥清理：registry 无滞留 dispatcher 条目
      _ <- waitUntil(60.seconds)(dispatcherEntries(resources).map(_.isEmpty))
      entries <- dispatcherEntries(resources)
      starts <- wsEvents.get.map(_.filter(_.hcursor.get[String]("type").toOption.contains("agentStart")))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(entries, Nil, "no lingering dispatcher session after normal completion")
      assert(starts.nonEmpty, "dispatcher agentStart must be observable via the routed wsSend")
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
      // 4 轮扫描（attempt ≥ StopAttempts+2 → giveUp）：threshold 300ms，先静置 600ms
      _ <- IO.sleep(600.millis)
      stopCounts <- cats.effect.Ref.of[IO, Map[String, Int]](Map.empty)
      _ <- TaskStuckWatcher.scan(resources, wsHub, 300L, stopCounts)
      _ <- TaskStuckWatcher.scan(resources, wsHub, 300L, stopCounts)
      _ <- TaskStuckWatcher.scan(resources, wsHub, 300L, stopCounts)
      _ <- TaskStuckWatcher.scan(resources, wsHub, 300L, stopCounts)
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
  }

end ProjectDispatcherLifecycleSpec
