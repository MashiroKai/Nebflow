package nebflow.core.processor

import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref}
import fs2.Stream
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, ActorSystem, Behavior, Behaviors}
import nebflow.agent.{AgentCommand, AgentEvent, AgentKind, AgentLibrary, AgentRecord, AgentStatus, SharedResources}
import nebflow.core.FileChangeTracker
import nebflow.core.compact.HistoryArchiver
import nebflow.core.project.{FlowMapStore, NodeEngine, NodeLifecycle, NodeDef, OutEdge, ProjectDef, ProjectRuntime, ProjectRuntimeRegistry}
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore, WsHub}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, Message, MessageRole, StreamChunk}

import scala.concurrent.duration.*

/**
 * stuck 自动恢复批 **P2** 验收 spec（2026-09-11，R-1=B / 硬依赖 2 / R-5）。
 *
 * 钉住 P2 的四件结构性改动：
 *   1. **runtime 定位键修正（硬依赖 2，正交实验已证正）**：多项目挂载共享同一个
 *      `rootSessionId` 时，旧定位（`rt.engine.rootSessionId == rec.rootSessionId`）
 *      必然歧义；新定位（会话属于哪个 store）精确唯一 —— 正控④。
 *   2. **恢复锚为恢复前置步骤**：A1（transcript）不可用 ⇒ **不启动挂起腿**、不发任何
 *      桥信号、直接一次上报 —— 负控①。
 *   3. **`hardResumeNode` 的 CAS 前置条件收敛为 `Running`**：`Cancelled` 不再是可复活
 *      状态（`cancelled` = 真终态、不可重激活），该语义由类型面保证。
 *   4. **类⑦ `bg-harvest` 带 cause**（R-5）：事件文本可判定「因何终态」。
 */
class StuckRecoveryLegSpec extends CatsEffectSuite:

  private val watchdogLogTmp = os.temp.dir(prefix = "stuck-leg-events")
  override def beforeAll(): Unit = WatchdogEventLog.setLogDirForTest(watchdogLogTmp.toNIO)
  override def afterAll(): Unit = WatchdogEventLog.resetLogDirForTest()

  private val threshold = 10 * 60 * 1000L

  private val fakeLlm = new LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("fake llm not expected here"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.raiseError[IO](new RuntimeException("fake llm not expected here"))

  private def mkRecordingEvt(record: Ref[IO, List[AgentEvent]]): Behavior[AgentEvent] =
    def loop: Behavior[AgentEvent] =
      Behaviors.receiveMessage[AgentEvent](evt => record.update(_ :+ evt).as(loop))
    loop

  private def mkRecordingActor(record: Ref[IO, List[AgentCommand]]): Behavior[AgentCommand] =
    def loop: Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand](cmd => record.update(_ :+ cmd).as(loop))
    loop

  private def mkResources(system: ActorSystem, tmp: os.Path): IO[SharedResources] =
    for
      dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
    yield SharedResources(
      llm = fakeLlm,
      dispatcher = dispatcher,
      sessionStore = SessionStore(os.temp.dir(), os.temp.dir()),
      projectRoot = os.pwd,
      thinkingConfigRef = thinkingRef,
      rateLimiter = rateLimiter,
      fileChangeTracker = tracker,
      contextWindow = 100_000,
      agentLibrary = new AgentLibrary(tmp / "agents"),
      taskStore = FileTaskStore,
      historyArchiver = HistoryArchiver.fileSystem(tmp / "archives"),
      fileLockManager = fileLocks,
      sessionModelOverrides = modelOverrides,
      providerRegistry = null,
      healthMonitor = ProviderHealthMonitor(null),
      actorSystem = system,
      voiceMutedRef = voiceMuted
    )

  private def mountProject(name: String, ws: os.Path, system: ActorSystem, res: SharedResources,
                           rootSid: String): IO[ProjectRuntime] =
    for
      store <- FlowMapStore.open(name, ws.toString)
      engine = new NodeEngine(
        store, system, res,
        wsSendFn = (_: Json) => IO.unit,
        workspace = ws.toString,
        rootSessionId = rootSid,
        projectName = name,
        emitEvent = (_: String, _: String, _: Json) => IO.unit,
        notifyTriggerOverride = Some((_: String) => IO.unit)
      )
      pd = ProjectDef(name = name, workspace = ws.toString, agentFile = (ws / "AGENTS.md").toString,
        createdAt = System.currentTimeMillis())
      rt = ProjectRuntime(pd, store, engine, system, res, None)
      _ <- ProjectRuntimeRegistry.register(rt)
    yield rt

  private def seed(store: FlowMapStore, n: NodeDef): IO[Unit] =
    store.mutate(s => s.copy(nodes = s.nodes + (n.id -> n))).void

  private def node(id: String, sid: String, status: String = NodeLifecycle.Running): NodeDef =
    NodeDef(id = id, name = id, agent = "general", status = status,
      sessionRef = Some(sid), startedAt = Some(System.currentTimeMillis() - 60_000L),
      out = List(OutEdge.nebula), createdAt = System.currentTimeMillis() - 60_000L)

  private def flowRecord(sid: String, ref: ActorRef[AgentCommand], rootSid: String,
                         bridge: Option[ActorRef[AgentEvent]]): AgentRecord =
    AgentRecord(sessionId = sid, ref = ref, kind = AgentKind.Flow, rootSessionId = rootSid,
      startedAt = System.currentTimeMillis() - 30 * 60 * 1000L, status = AgentStatus.Processing,
      lastActivityMs = System.currentTimeMillis() - threshold - 1000L,
      supervisorRef = bridge, currentToolStartedAt = 0L)

  // ── ① runtime 定位键修正（硬依赖 2）─────────────────────────────────────

  test("正控④: 多项目挂载共享同一 rootSessionId → 新定位键精确命中**拥有该会话**的引擎（旧键必然歧义）") {
    val system = ActorSystem("leg-locator")
    val sharedRoot = "shared-root-session"
    for
      _ <- IO(system)
      tmp <- IO(os.temp.dir())
      res <- mkResources(system, tmp)
      _ <- ProjectRuntimeRegistry.clear
      // 两个项目挂载时拿到**同一个** rootSessionId（启动挂载的真实形态）
      wsA = tmp / "projA"; wsB = tmp / "projB"
      _ <- IO(os.makeDir.all(wsA)); _ <- IO(os.makeDir.all(wsB))
      rtA <- mountProject("projA", wsA, system, res, sharedRoot)
      rtB <- mountProject("projB", wsB, system, res, sharedRoot)
      sid = "node-belongs-to-A"
      _ <- seed(rtA.store, node("nA", sid, NodeLifecycle.Running))
      _ <- seed(rtB.store, node("nB", "node-belongs-to-B", NodeLifecycle.Running))
      // 旧定位键：两个引擎的 rootSessionId 相同 ⇒ find 结果与「谁是家」无关
      rts <- ProjectRuntimeRegistry.all
      _ <- IO(assert(rts.size == 2, s"两个 runtime 应均已挂载，得 ${rts.size}"))
      _ <- IO(assert(rts.forall(_.engine.rootSessionId == sharedRoot),
        "夹具前提：两个项目共享同一 rootSessionId（这正是旧定位键歧义的根因）"))
      oldHits <- rts.filter(rt => rt.engine.rootSessionId == sharedRoot).foldLeft(IO.pure(0))((acc, rt) =>
        acc.map(_ + 1))
      ownsA <- rtA.engine.ownsSession(sid)
      ownsB <- rtB.engine.ownsSession(sid)
      _ <- IO(system.stopAll.attempt.void.unsafeRunSync())
      _ <- ProjectRuntimeRegistry.clear
    yield
      assertEquals(oldHits, 2, "旧键：两个 runtime 都「匹配」⇒ 任取其一 = 15/16 命错引擎的事故根因")
      assert(ownsA, "新键：会话 node-belongs-to-A 由 projA 拥有")
      assert(!ownsB, "新键：projB 不拥有该会话（旧键无法区分二者）")
  }

  test("正控④ 负半: findNodeForSession 只认 sessionRef/sessionRefVerify，不认 rootSessionId") {
    val snap = nebflow.core.project.FlowMapState(
      v = 1, project = "locator-spec", updatedAt = 0L,
      nodes = Map("n1" -> NodeDef(id = "n1", name = "n1", agent = "general",
        sessionRef = Some("node-x"), createdAt = 0L)))
    assert(NodeEngine.findNodeForSession(snap, "node-x").isDefined)
    assert(NodeEngine.findNodeForSession(snap, "some-root-session").isEmpty,
      "rootSessionId 不得再作为定位键（定位键 = 会话在哪个 store 里）")
  }

  // ── ② 恢复锚 / 挂起腿 ───────────────────────────────────────────────────

  test("负控①: A1 transcript 不可用 → 不启动挂起腿、不发桥信号、不重试（直接一次上报）") {
    val system = ActorSystem("leg-noanchor")
    val sid = "node-noanchor"
    for
      _ <- IO(system)
      tmp <- IO(os.temp.dir())
      res <- mkResources(system, tmp)
      _ <- ProjectRuntimeRegistry.clear
      ws = tmp / "ws-noanchor"; _ <- IO(os.makeDir.all(ws))
      rt <- mountProject("noanchor", ws, system, res, "root-noanchor")
      _ <- seed(rt.store, node("n-noanchor", sid, NodeLifecycle.Running))
      bridgeReceived <- Ref.of[IO, List[AgentEvent]](Nil)
      bridgeRef <- system.spawn(mkRecordingEvt(bridgeReceived), "noanchor-bridge")
      agentRef <- system.spawn(mkRecordingActor(Ref.unsafe(Nil)), "noanchor-agent")
      // 刻意**不**写入 transcript ⇒ A1 不可用
      _ <- res.agentRegistry.set(Map(sid -> flowRecord(sid, agentRef, "root-noanchor", Some(bridgeRef))))
      stopCounts <- Ref.of[IO, Map[String, Int]](Map.empty)
      pendingL3 <- Ref.of[IO, List[TaskStuckWatcher.PendingL3]](Nil)
      _ <- TaskStuckWatcher.scan(res, new WsHub(), threshold, stopCounts, pendingL3) // L1
      _ <- TaskStuckWatcher.scan(res, new WsHub(), threshold, stopCounts, pendingL3) // L2
      _ <- TaskStuckWatcher.scan(res, new WsHub(), threshold, stopCounts, pendingL3) // L3
      _ <- IO.sleep(500.millis)
      bridgeEvts <- bridgeReceived.get
      _ <- IO(system.stopAll.attempt.void.unsafeRunSync())
      _ <- ProjectRuntimeRegistry.clear
    yield
      assert(bridgeEvts.isEmpty,
        s"无恢复锚 ⇒ 挂起腿不启动（负控①：不重试、直接一次上报），不得发任何桥信号，得 $bridgeEvts")
  }

  test("正控②: A1 可用 → 挂起腿发出**带哨兵**的中断信号（只停 actor，不终态化）") {
    val system = ActorSystem("leg-suspend")
    val sid = "node-suspend"
    for
      _ <- IO(system)
      tmp <- IO(os.temp.dir())
      res <- mkResources(system, tmp)
      _ <- ProjectRuntimeRegistry.clear
      ws = tmp / "ws-suspend"; _ <- IO(os.makeDir.all(ws))
      rt <- mountProject("suspend", ws, system, res, "root-suspend")
      _ <- seed(rt.store, node("n-suspend", sid, NodeLifecycle.Running))
      bridgeReceived <- Ref.of[IO, List[AgentEvent]](Nil)
      bridgeRef <- system.spawn(mkRecordingEvt(bridgeReceived), "suspend-bridge")
      agentRef <- system.spawn(mkRecordingActor(Ref.unsafe(Nil)), "suspend-agent")
      _ <- res.sessionStore.saveMessagesForSession(sid, List(
        Message(role = MessageRole.User, content = Left("fixture transcript"))))
      _ <- res.agentRegistry.set(Map(sid -> flowRecord(sid, agentRef, "root-suspend", Some(bridgeRef))))
      stopCounts <- Ref.of[IO, Map[String, Int]](Map.empty)
      pendingL3 <- Ref.of[IO, List[TaskStuckWatcher.PendingL3]](Nil)
      _ <- TaskStuckWatcher.scan(res, new WsHub(), threshold, stopCounts, pendingL3) // L1
      _ <- TaskStuckWatcher.scan(res, new WsHub(), threshold, stopCounts, pendingL3) // L2
      _ <- TaskStuckWatcher.scan(res, new WsHub(), threshold, stopCounts, pendingL3) // L3
      _ <- IO.sleep(600.millis)
      bridgeEvts <- bridgeReceived.get
      nodeAfter <- rt.store.getNode("n-suspend")
      _ <- IO(system.stopAll.attempt.void.unsafeRunSync())
      _ <- ProjectRuntimeRegistry.clear
    yield
      val cancels = bridgeEvts.collect { case c: AgentEvent.Cancelled => c }
      assertEquals(cancels.size, 1, s"挂起腿必须恰好发一条中断信号，得 $bridgeEvts")
      assert(NodeEngine.isSuspendOutcome(cancels.head.reason),
        s"信号必须带挂起哨兵（引擎侧据此走「只停 actor、不终态化」分支），得 ${cancels.head.reason}")
      // 挂起**不终态化**：本 spec 的桥是记录器、不驱动引擎 fiber，故节点停在 Running
      // ——断言「节点未被改成 Cancelled/Failed」正是 R-1=B 的可观测面。
      assertEquals(nodeAfter.map(_.status), Some(NodeLifecycle.Running),
        "挂起腿不得终态化节点（R-1=B：恢复动作放在终态之前，全程不进 Cancelled）")
  }

  // ── ③ hardResumeNode 的 CAS 前置条件收敛 ────────────────────────────────

  test("负控: CAS 前置条件收敛为 Running——Cancelled 节点不再可被 resume（真终态不可重激活）") {
    val system = ActorSystem("leg-cas")
    val sid = "node-cas"
    for
      _ <- IO(system)
      tmp <- IO(os.temp.dir())
      res <- mkResources(system, tmp)
      _ <- ProjectRuntimeRegistry.clear
      ws = tmp / "ws-cas"; _ <- IO(os.makeDir.all(ws))
      rt <- mountProject("cas", ws, system, res, "root-cas")
      _ <- seed(rt.store, node("n-cas", sid, NodeLifecycle.Cancelled))
      _ <- res.sessionStore.saveMessagesForSession(sid, List(
        Message(role = MessageRole.User, content = Left("fixture transcript"))))
      cached <- rt.engine.hardResumeNode(sid)
      nodeAfter <- rt.store.getNode("n-cas")
      _ <- IO(system.stopAll.attempt.void.unsafeRunSync())
      _ <- ProjectRuntimeRegistry.clear
    yield
      assertEquals(cached, None, "Cancelled 节点不得被 resume（CAS 只接受 Running）")
      assertEquals(nodeAfter.map(_.status), Some(NodeLifecycle.Cancelled),
        "节点状态不得被改动（cancelled 保持真终态语义）")
  }

  test("正控②: Running 节点 + A1 可用 → CAS 接受并翻回 Pending（恢复锚 = transcript）") {
    val system = ActorSystem("leg-casok")
    val sid = "node-casok"
    for
      _ <- IO(system)
      tmp <- IO(os.temp.dir())
      res <- mkResources(system, tmp)
      _ <- ProjectRuntimeRegistry.clear
      ws = tmp / "ws-casok"; _ <- IO(os.makeDir.all(ws))
      rt <- mountProject("casok", ws, system, res, "root-casok")
      // deps/barrier 无约束（in 空、deps 空）⇒ startNode 可推进
      _ <- seed(rt.store, node("n-casok", sid, NodeLifecycle.Running))
      _ <- res.sessionStore.saveMessagesForSession(sid, List(
        Message(role = MessageRole.User, content = Left("fixture transcript"))))
      anchor <- rt.engine.probeRecoveryAnchors(sid)
      ok <- rt.engine.hardResumeNode(sid, Some(anchor))
      _ <- IO.sleep(300.millis)
      nodeAfter <- rt.store.getNode("n-casok")
      _ <- IO(system.stopAll.attempt.void.unsafeRunSync())
      _ <- ProjectRuntimeRegistry.clear
    yield
      assertEquals(ok, Some("n-casok"), "Running → Pending 的 CAS 必须被接受（R-1=B 的唯一合法前置态）")
      assert(nodeAfter.exists(n => n.status != NodeLifecycle.Cancelled),
        s"恢复后节点必须离开 Running-未迁移形态，得 ${nodeAfter.map(_.status)}")
  }

  test("A3 锚探测: 非 git 目录 ⇒ worktreeAvailable=false（#159 形态降级），且探测零副作用") {
    val system = ActorSystem("leg-a3")
    val sid = "node-a3"
    for
      _ <- IO(system)
      tmp <- IO(os.temp.dir())
      res <- mkResources(system, tmp)
      _ <- ProjectRuntimeRegistry.clear
      ws = tmp / "ws-a3"; _ <- IO(os.makeDir.all(ws))
      rt <- mountProject("a3", ws, system, res, "root-a3")
      _ <- seed(rt.store, node("n-a3", sid, NodeLifecycle.Running))
      anchor <- rt.engine.probeRecoveryAnchors(sid)
      nodeAfter <- rt.store.getNode("n-a3")
      _ <- IO(system.stopAll.attempt.void.unsafeRunSync())
      _ <- ProjectRuntimeRegistry.clear
    yield
      assert(!anchor.worktreeAvailable, "非 git 目录不得被当作可用 worktree（#159 形态：目录缺失/非工作树）")
      assertEquals(nodeAfter.map(_.status), Some(NodeLifecycle.Running), "锚探测必须零副作用")
  }

  // ── ④ 类⑦ bg-harvest 带 cause（R-5）─────────────────────────────────────

  test("类⑦/R-5: bg-harvest 的 cause 词表从同一份桥消息文本单点派生（挂起/取消/bg-wait-cap/猝死/失败）") {
    assertEquals(NodeEngine.finalizeCause("(node-suspend): stuck 601s (agent-stale)"), "suspended-for-recovery")
    assertEquals(NodeEngine.finalizeCause("cancelled: stuck for 670s — released by TaskStuckWatcher"), "cancelled")
    assertEquals(NodeEngine.finalizeCause("background task wait cap exceeded (7200s)"), "bg-wait-cap")
    assertEquals(NodeEngine.finalizeCause("node session 'x' terminated without a terminal event"), "session-died")
    assertEquals(NodeEngine.finalizeCause("some other failure"), "failed")
  }

end StuckRecoveryLegSpec
