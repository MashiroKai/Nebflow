package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, Behaviors}
import nebflow.agent.{AgentKind, AgentLibrary, AgentRecord, AgentStatus, SharedResources}
import nebflow.core.PathUtil
import nebflow.core.processor.TaskStuckWatcher
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{AgentControlTool, FileLockManager}
import nebflow.gateway.{RateLimiter, SessionStore, WsHub}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * Project 会话取消 → Sub-Agents 面板实时终态帧验收（取消实时刷新修复）。
 *
 * 缺口：node-/dispatcher- 会话被取消注销（AgentControl cancel / 面板 cancelAgent /
 * TaskStuckWatcher giveUp / 观察桥 Failed+Cancelled）时后端零 WS 出口——前端面板行
 * 由 agentStart 创建、只被 agentDone/会话级 done 清理，取消后 Processing 幽灵行
 * 滞留到浏览器刷新。修复在三处补发 agentDone 同构帧：
 *   - dispatcher-*：观察桥拆除点（ProjectActor.dispatcherBridge Failed|Cancelled
 *     分支）——全部取消/失败路径的唯一汇合点；
 *   - node-* 经 AgentControl：doCancel 的 notifyWs 出口（工具路径 ctx.wsSend /
 *     面板路径连接 wsSend）；
 *   - node-* 经 watcher giveUp：TaskStuckWatcher Flow 分支 wsHub 补发。
 *
 * 本 spec 锁定三条链路的帧契约：agentDone 同构（agentId=nodeSessionId=会话 id，
 * rootSessionId/sessionId 归桶路由键由 routeSubagentWsSend 注入）——前端零改动
 * 消费（ws.js 转换会话级 done → 立即删行）。
 */
class ProjectSessionCancelPanelFrameSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-cancel-panel-frame"
  private val originalRoot = PathUtil.dataRoot

  // 类级：隔离 dataRoot + 预置 project-dispatcher 定义（ProjectActor 走
  // EntityLoader = dataRoot/agents/project-dispatcher）。
  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "project-dispatcher")
  os.write.over(
    tempRoot / "agents" / "project-dispatcher" / "agent.json",
    """{"name":"project-dispatcher","description":"cancel frame test dispatcher","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "project-dispatcher" / "system.md", "# project-dispatcher\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  /** 挂死 LLM：流永不产出（取消必须在 turn 存活窗口内发生）。 */
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

  private def mount(name: String, ws: os.Path, system: ActorSystem, res: SharedResources, wsSend: Json => IO[Unit]): IO[ProjectRuntime] =
    val pd = ProjectDef(
      name = name,
      workspace = ws.toString,
      agentFile = (ws / "AGENTS.md").toString,
      createdAt = System.currentTimeMillis()
    )
    ProjectRuntimeRegistry.mount(pd, system, res, Some(wsSend), rootSessionId = "nebula-root")

  private def dispatcherEntries(resources: SharedResources): IO[List[String]] =
    resources.agentRegistry.get.map(_.keys.toList.filter(_.startsWith(ProjectActor.DispatcherSessionPrefix)))

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  test("dispatcher-* 取消：AgentControl doCancel → 观察桥拆除点补发 agentDone 面板帧 + registry 清理") {
    val ws = tempRoot / "ws-disp-cancel"
    os.makeDir.all(ws)
    val system = ActorSystem(s"disp-cancel-${scala.util.Random.nextInt(100000)}")
    val wsEvents = Ref.unsafe[IO, List[Json]](Nil)
    for
      resources <- mkResources(system, tempRoot, new HungLlm)
      rt <- mount("disp-cancel", ws, system, resources, j => wsEvents.update(j :: _))
      actorRef = rt.actorRef.getOrElse(sys.error("ProjectActor must be spawned by mount"))
      _ <- (actorRef ! ProjectActor.ProjectCommand.TriggerDispatcher("会被取消的任务", "nebula-root")).void
      _ <- waitUntil(20.seconds)(dispatcherEntries(resources).map(_.nonEmpty))
      // AgentControl cancel（工具路径同链路——supervisorRef=观察桥）
      recs <- resources.agentRegistry.get
      sid = recs.keys.toList.find(_.startsWith(ProjectActor.DispatcherSessionPrefix)).getOrElse(sys.error("dispatcher session missing"))
      rec = recs(sid)
      outcome <- AgentControlTool.doCancel(resources, rec, "spec cancel")
      // 桥 Cancelled → 补发面板帧 + 清 registry + 停 agent
      _ <- waitUntil(20.seconds)(dispatcherEntries(resources).map(_.isEmpty))
      _ <- waitUntil(20.seconds)(wsEvents.get.map { events =>
        events.exists(e =>
          e.hcursor.get[String]("type").toOption.contains("agentDone") &&
            e.hcursor.get[String]("agentId").toOption.contains(sid) &&
            e.hcursor.get[String]("nodeSessionId").toOption.contains(sid) &&
            e.hcursor.get[String]("rootSessionId").toOption.contains("nebula-root")
        )
      })
      frames <- wsEvents.get
      remaining <- dispatcherEntries(resources)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(outcome.isRight, s"doCancel must succeed, got $outcome")
      assertEquals(remaining, Nil, "no lingering dispatcher session after cancel")
      val done = frames.filter(_.hcursor.get[String]("type").toOption.contains("agentDone"))
      assert(done.nonEmpty, s"bridge teardown must emit the panel done frame, got: $frames")
      assert(
        done.exists(e => e.hcursor.get[String]("nodeSessionId").toOption.contains(sid)),
        s"done frame must carry nodeSessionId=$sid (popup/row key), got: $done"
      )
      assert(
        done.forall(e => e.hcursor.get[String]("type").toOption.contains("agentDone")),
        "no stray frame types from teardown"
      )
  }

  /** 哑 actor 行为：收到任何消息原样驻留（收 Cancelled 但不复刻桥的清理语义）。 */
  private def dumbBehavior[Msg]: nebflow.actor.Behavior[Msg] =
    Behaviors.receive[Msg]((_, _) => IO.pure(dumbBehavior[Msg]))

  test("node-* 取消：doCancel notifyWs 补发帧契约（agentId=nodeSessionId=会话 id，rootSessionId 归桶键注入）") {
    val system = ActorSystem(s"node-cancel-${scala.util.Random.nextInt(100000)}")
    val captured = Ref.unsafe[IO, List[Json]](Nil)
    for
      resources <- mkResources(system, tempRoot, new HungLlm)
      // 哑 actor：cmdRef 填 AgentRecord.ref（Some(sup) 路径不触达）；evtRef 作
      // 哑监督者只收 Cancelled（node 桥的引擎侧语义不属本 spec——本轨禁改
      // NodeEngine，帧补发在 doCancel 出口，与桥解耦）
      cmdRef <- system.spawn(dumbBehavior[nebflow.agent.AgentCommand], s"dumb-cmd-${scala.util.Random.nextInt(100000)}")
      evtRef <- system.spawn(dumbBehavior[nebflow.agent.AgentEvent], s"dumb-sup-${scala.util.Random.nextInt(100000)}")
      sid = "node-abc12345"
      _ <- resources.agentRegistry.update(
        _ + (sid -> AgentRecord(
          sessionId = sid,
          ref = cmdRef,
          kind = AgentKind.Flow,
          rootSessionId = "nebula-root",
          startedAt = System.currentTimeMillis(),
          lastActivityMs = System.currentTimeMillis(),
          supervisorRef = Some(evtRef)
        ))
      )
      recs <- resources.agentRegistry.get
      outcome <- AgentControlTool.doCancel(
        resources,
        recs(sid),
        "spec cancel",
        notifyWs = Some(j => captured.update(j :: _))
      )
      frames <- captured.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // 注：registry 清理属桥/engine fiber 侧语义（哑监督者不复刻），本 spec
      // 只锁帧契约。
      assert(outcome.isRight, s"doCancel must succeed, got $outcome")
      assertEquals(frames.length, 1, s"exactly one panel frame expected, got: $frames")
      val f = frames.head
      assertEquals(f.hcursor.get[String]("type").toOption, Some("agentDone"))
      assertEquals(f.hcursor.get[String]("agentId").toOption, Some(sid))
      assertEquals(f.hcursor.get[String]("nodeSessionId").toOption, Some(sid), "row/popup key")
      assertEquals(f.hcursor.get[String]("rootSessionId").toOption, Some("nebula-root"), "bucket key")
      assertEquals(f.hcursor.get[String]("sessionId").toOption, Some("nebula-root"), "routing key")
  }

  test("node-* watcher giveUp：TaskStuckWatcher 升级取消 → wsHub 补发 agentDone 面板帧") {
    val system = ActorSystem(s"node-giveup-${scala.util.Random.nextInt(100000)}")
    val wsHub = new WsHub()
    val broadcasts = Ref.unsafe[IO, List[Json]](Nil)
    for
      resources <- mkResources(system, tempRoot, new HungLlm)
      _ <- wsHub.register(j => broadcasts.update(j :: _))
      cmdRef <- system.spawn(dumbBehavior[nebflow.agent.AgentCommand], s"dumb-cmd-${scala.util.Random.nextInt(100000)}")
      evtRef <- system.spawn(dumbBehavior[nebflow.agent.AgentEvent], s"dumb-sup-${scala.util.Random.nextInt(100000)}")
      sid = "node-feedbeef"
      // 卡死形态：Processing + lastActivityMs 远超阈值（scan threshold=300ms）
      _ <- resources.agentRegistry.update(
        _ + (sid -> AgentRecord(
          sessionId = sid,
          ref = cmdRef,
          kind = AgentKind.Flow,
          rootSessionId = "nebula-root",
          startedAt = System.currentTimeMillis() - 60_000L,
          lastActivityMs = System.currentTimeMillis() - 60_000L,
          status = AgentStatus.Processing,
          supervisorRef = Some(evtRef)
        ))
      )
      // 4 轮扫描（attempt ≥ StopAttempts+2=4 → giveUp：桥 Cancelled + 面板帧）
      stopCounts <- cats.effect.Ref.of[IO, Map[String, Int]](Map.empty)
      _ <- TaskStuckWatcher.scan(resources, wsHub, 300L, stopCounts)
      _ <- TaskStuckWatcher.scan(resources, wsHub, 300L, stopCounts)
      _ <- TaskStuckWatcher.scan(resources, wsHub, 300L, stopCounts)
      _ <- TaskStuckWatcher.scan(resources, wsHub, 300L, stopCounts)
      _ <- waitUntil(10.seconds)(broadcasts.get.map { events =>
        events.exists(e =>
          e.hcursor.get[String]("type").toOption.contains("agentDone") &&
            e.hcursor.get[String]("nodeSessionId").toOption.contains(sid)
        )
      })
      frames <- broadcasts.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(
        frames.exists(_.hcursor.get[String]("action").toOption.contains("restart")),
        s"taskStuck(action=restart) must be broadcast, got: $frames"
      )
      val done = frames.filter(_.hcursor.get[String]("type").toOption.contains("agentDone"))
      assert(done.nonEmpty, s"giveUp must emit the panel done frame, got: $frames")
      val f = done.find(_.hcursor.get[String]("nodeSessionId").toOption.contains(sid)).getOrElse(sys.error("frame missing"))
      assertEquals(f.hcursor.get[String]("agentId").toOption, Some(sid))
      assertEquals(f.hcursor.get[String]("rootSessionId").toOption, Some("nebula-root"))
  }

end ProjectSessionCancelPanelFrameSpec
