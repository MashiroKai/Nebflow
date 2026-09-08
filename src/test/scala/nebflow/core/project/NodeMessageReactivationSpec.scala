package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, Behaviors}
import nebflow.agent.{AgentCommand, AgentKind, AgentLibrary, AgentRecord, SharedResources}
import nebflow.core.PathUtil
import nebflow.core.tools.{FileLockManager, ToolContext}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}
import nebflow.shared.given

import scala.concurrent.duration.*

/**
 * 增量#5 反事实（2026-09-07，slideblocks ×3 投递失败取证）：blocked→NodeEdit
 * 重激活后，NodeMessage 对存活节点的注入持续解析到已终结会话句柄（「注入未达」
 * 兜底）而真实会话健在。
 *
 * 缺陷面 = injectRunning 的会话解析：修复前单依赖 nodeSessions 内存缓存（重激活/
 * 重挂载窗口下可能持有旧句柄或缺失）；修复后**实时解析优先**——D1 持久化的
 * node.sessionRef（与 Running 翻转同事务落库，重激活后永为新会话权威）逐一试探
 * agentRegistry，命中即投递并自愈缓存。
 *
 * 本 spec 以「重激活后的稳态」直接构造（Running + sessionRef=新会话 + registry 有
 * 活 actor），变异点只在 nodeSessions 侧（缺失/陈旧）——重激活链是生产触发器，
 * 路由解析是被钉死的缺陷面。预期：修复前红（not-delivered），修复后绿。
 */
class NodeMessageReactivationSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-nodemsg-reactivation"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "general")
  os.write.over(
    tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"general executor","tools":[],"category":"standalone"}""")
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")
  os.makeDir.all(tempRoot / "sessions")

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)
  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  private def mkResources(system: ActorSystem): IO[SharedResources] =
    for
      dispatcher <- cats.effect.std.Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- nebflow.core.FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
    yield SharedResources(
      llm = null,
      dispatcher = dispatcher,
      sessionStore = SessionStore(tempRoot / "sessions", tempRoot / "tasks"),
      projectRoot = os.pwd,
      thinkingConfigRef = thinkingRef,
      rateLimiter = rateLimiter,
      fileChangeTracker = tracker,
      contextWindow = 100_000,
      agentLibrary = new AgentLibrary(tempRoot / "agents"),
      taskStore = nebflow.core.task.FileTaskStore,
      historyArchiver = null,
      fileLockManager = fileLocks,
      sessionModelOverrides = modelOverrides,
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = null,
      voiceMutedRef = voiceMuted
    )

  private def mountProject(name: String, ws: os.Path, system: ActorSystem, res: SharedResources): IO[ProjectRuntime] =
    for
      store <- FlowMapStore.open(name, ws.toString)
      engine = new NodeEngine(
        store,
        system,
        res,
        wsSendFn = (_: io.circe.Json) => IO.unit,
        workspace = ws.toString,
        rootSessionId = "nebula-root",
        projectName = name,
        emitEvent = (_, _, _) => IO.unit
      )
      pd = ProjectDef(name = name, workspace = ws.toString, agentFile = (ws / "AGENTS.md").toString, createdAt = System.currentTimeMillis())
      rt = ProjectRuntime(pd, store, engine, system, res, None)
      _ <- ProjectRuntimeRegistry.register(rt)
    yield rt

  /** registry 里登记一个活会话 recorder（节点真实会话的替身）。 */
  private def registerLiveSession(res: SharedResources, system: ActorSystem, sid: String): IO[Ref[IO, List[AgentCommand]]] =
    def mkBehavior(recorded: Ref[IO, List[AgentCommand]]): nebflow.actor.Behavior[AgentCommand] =
      lazy val behavior: nebflow.actor.Behavior[AgentCommand] =
        Behaviors.receiveMessage[AgentCommand](m => recorded.update(_ :+ m).as(behavior))
      behavior
    for
      recorded <- Ref.of[IO, List[AgentCommand]](Nil)
      ref <- system.spawn(mkBehavior(recorded), sid)
      _ <- res.agentRegistry.update(_ + (sid -> AgentRecord(sid, ref, AgentKind.Flow, "nebula-root")))
    yield recorded

  private def seedRunningNode(rt: ProjectRuntime, sessionRef: String): IO[Unit] =
    rt.store.mutate { s =>
      s.copy(nodes = s.nodes.updated("n-r1", NodeDef(
        id = "n-r1", name = "react-a", agent = "general",
        task = Some("原任务（已被 NodeEdit 重激活改写）"), out = List(OutEdge.nebula),
        status = NodeLifecycle.Running,
        startedAt = Some(System.currentTimeMillis()),
        createdAt = System.currentTimeMillis(),
        sessionRef = Some(sessionRef),
        deliveredTo = Nil)))
    }.void

  test("re-activated node: missing session cache resolves via persisted sessionRef (realtime resolution)") {
    val ws = tempRoot / "ws-r1"
    os.makeDir.all(ws)
    val system = ActorSystem(s"nmr-1-${scala.util.Random.nextInt(1000000)}")
    val liveSid = "node-live11aa"
    for
      res <- mkResources(system)
      _ <- registerLiveSession(res, system, "nebula-root")
      live <- registerLiveSession(res, system, liveSid)
      rt <- mountProject("nmr-1", ws, system, res)
      _ <- seedRunningNode(rt, sessionRef = liveSid)
      // 缺陷形态①：nodeSessions 无映射（重挂载/登记窗口丢失）——修复前直接
      // not-delivered；修复后经 sessionRef 实时解析命中活会话。
      pre <- rt.engine.nodeSessions.get.map(_.get("n-r1"))
      result <- rt.engine.sendNodeMessage("n-r1", "增量指令：改用方案 B")
      _ <- IO.sleep(150.millis)
      delivered <- live.get.map(_.collect { case m: AgentCommand.ImmediateInput => m })
      healed <- rt.engine.nodeSessions.get.map(_.get("n-r1"))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(pre, None, "precondition: no cached handle")
      assert(result.isRight, s"delivery must succeed, got $result")
      assert(delivered.exists(_.text.contains("增量指令：改用方案 B")),
        "the LIVE session must receive the injection ([NODE-MESSAGE] header + text)")
      assert(delivered.forall(_.source.contains("system")), "injection carries source=system")
      assertEquals(healed, Some(liveSid), "session cache must self-heal to the live session")
  }

  test("re-activated node: STALE cached handle (dead old session) yields to sessionRef and self-heals") {
    val ws = tempRoot / "ws-r2"
    os.makeDir.all(ws)
    val system = ActorSystem(s"nmr-2-${scala.util.Random.nextInt(1000000)}")
    val deadSid = "node-dead22bb"
    val liveSid = "node-live22cc"
    for
      res <- mkResources(system)
      _ <- registerLiveSession(res, system, "nebula-root")
      live <- registerLiveSession(res, system, liveSid)
      rt <- mountProject("nmr-2", ws, system, res)
      _ <- seedRunningNode(rt, sessionRef = liveSid)
      // 缺陷形态②（slideblocks 现场）：缓存持有已终结旧句柄（dead 会话不在
      // registry），sessionRef 是重激活后的新会话。修复前：registry.get(deadSid)
      // = None → not-delivered ×N（对活节点反复失败）。
      _ <- rt.engine.nodeSessions.update(_ + ("n-r1" -> deadSid))
      result <- rt.engine.sendNodeMessage("n-r1", "重试投递：现场取证样本")
      _ <- IO.sleep(150.millis)
      delivered <- live.get.map(_.collect { case m: AgentCommand.ImmediateInput => m })
      healed <- rt.engine.nodeSessions.get.map(_.get("n-r1"))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(result.isRight, s"delivery must succeed despite the stale cached handle, got $result")
      assert(delivered.exists(_.text.contains("重试投递：现场取证样本")),
        "injection must reach the NEW live session, not bounce off the dead handle")
      assertEquals(healed, Some(liveSid), "stale cache entry must be replaced by the live session id")
  }

  test("真终结会话（两个候选都查无）仍走「注入未达」兜底——不误投、留痕不丢") {
    val ws = tempRoot / "ws-r3"
    os.makeDir.all(ws)
    val system = ActorSystem(s"nmr-3-${scala.util.Random.nextInt(1000000)}")
    val goneSid = "node-gone33dd"
    for
      res <- mkResources(system)
      _ <- registerLiveSession(res, system, "nebula-root")
      rt <- mountProject("nmr-3", ws, system, res)
      _ <- seedRunningNode(rt, sessionRef = goneSid)
      _ <- rt.engine.nodeSessions.update(_ + ("n-r1" -> "node-also-gone-ee"))
      result <- rt.engine.sendNodeMessage("n-r1", "不应送达的消息")
      node <- rt.store.snapshot.map(_.nodes("n-r1"))
      events <- IO.blocking {
        val f = ws / ".nebflow" / "flow-map-events.jsonl"
        if os.exists(f) then os.read(f).linesIterator.toList else Nil
      }
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(result.isRight, "undelivered fallback is a Right (honest trace, nothing lost)")
      assert(result.toOption.exists(_.contains("注入未达")), s"must report the undelivered fallback, got $result")
      assert(node.task.exists(_.contains("不应送达的消息")), "message must be recorded on the node task")
      assert(events.exists(e => e.contains("not-delivered")), "audit event must be preserved")
  }
end NodeMessageReactivationSpec
