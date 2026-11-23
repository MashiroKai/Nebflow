package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, AgentCommand, AgentKind, AgentRecord, Behaviors}
import nebflow.agent.SharedResources
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.core.delegate.DelegateRegistry
import nebflow.core.tools.FileLockManager
import nebflow.llm.ModelCandidate
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, Message, MessageRole, PathUtil, StreamChunk, ThinkingConfig}
import nebflow.shared.given

import scala.concurrent.duration.*

/**
 * dispatcher-hang 修复批验收（2026-11-18）——三组：
 *
 *  - **G1 completed → 唤醒**：隔离链节点真实跑完（rehydrate E2E，与
 *    DelegateNotifyRedirectSpec R1 同骨架）⇒ `(pass)Nebula` 根边 ImmediateInput 投
 *    dispatcher 本人恰一次（主唤醒链在位）。
 *  - **G2 failed / cancelled → 唤醒**：failed 走 deliverFailed 根通报（result 边），
 *    cancelled 走 DispatchNotify（flag-free）→ DelegateNotify.inject —— 两种终态都
 *    必须**真实投达** dispatcher（ImmediateInput 落 recorder）。
 *  - **G3 长期无事件 → 兜底**：链面有节点 ∧ dispatcher registry Idle ∧ 锚点静默 ≥
 *    cap ⇒ dispatcherWakeWatchdog 注入 fallback 唤醒（有界：3 次后 outcome=exhausted
 *    **可读结论**、不再注入）；链面有进展（锚点前进）⇒ 预算重置；per-project 面
 *    （ProjectRoot）结构性零触碰。
 */
class DispatcherWakeWatchdogSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-dispatcher-wake-watchdog"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "general")

  os.write.over(
    tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"general executor","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")
  os.makeDir.all(tempRoot / "sessions")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    (ProjectRuntimeRegistry.clear *> DelegateFlowHosts.clear).unsafeRunSync()

  override def afterEach(context: munit.AfterEach): Unit =
    (ProjectRuntimeRegistry.clear *> DelegateFlowHosts.clear).unsafeRunSync()

  // ── 基建（DelegateNotifyRedirectSpec 同款骨架）────────────────────────────

  private def mkResources(system: ActorSystem, llm: LlmHandle[IO]): IO[SharedResources] =
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
      sessionStore = SessionStore(tempRoot / "sessions", tempRoot / "tasks"),
      projectRoot = os.pwd,
      thinkingConfigRef = thinkingRef,
      rateLimiter = rateLimiter,
      fileChangeTracker = tracker,
      contextWindow = 100_000,
      agentLibrary = new nebflow.agent.AgentLibrary(tempRoot / "agents"),
      taskStore = nebflow.core.task.FileTaskStore,
      historyArchiver = null,
      fileLockManager = fileLocks,
      sessionModelOverrides = modelOverrides,
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = null,
      voiceMutedRef = voiceMuted
    )

  private class ScriptLlm(respond: String => String):
    def handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(
        req: LlmRequest,
        onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]]
      ): Stream[IO, StreamChunk] =
        val text = req.messages.map(_.textContent).mkString("\n---\n")
        Stream(StreamChunk.TextDelta(respond(text)), StreamChunk.Done(None, None))
  end ScriptLlm

  private def registerRecorder(
    res: SharedResources,
    system: ActorSystem,
    sid: String,
    kind: AgentKind
  ): IO[Ref[IO, List[AgentCommand]]] =
    for
      recorded <- Ref.of[IO, List[AgentCommand]](Nil)
      ref <- system.spawn(recorderBehavior(recorded), s"rec-$sid-${scala.util.Random.nextInt(1000000)}")
      _ <- res.agentRegistry.update(_ + (sid -> AgentRecord(sid, ref, kind, sid)))
    yield recorded

  private def recorderBehavior(recorded: Ref[IO, List[AgentCommand]]): nebflow.actor.Behavior[AgentCommand] =
    lazy val b: nebflow.actor.Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand](msg => recorded.update(_ :+ msg).as(b))
    b

  private def seedDelegateRecord(id: String, ws: os.Path): IO[Unit] =
    IO.blocking(
      DelegateRegistry.put(
        DelegateRegistry.Record(
          id = id,
          executor = nebflow.core.executor.ExecutorRegistry.DefaultId,
          cwd = ws.toString,
          project = None,
          taskId = None,
          parentSessionId = "nebula-root",
          title = "watchdog-probe",
          createdAt = System.currentTimeMillis()
        )
      )
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

  private def readEvents(ws: os.Path): IO[List[String]] =
    IO.blocking {
      val f = ws / ".nebflow" / "flow-map-events.jsonl"
      if os.exists(f) then os.read(f).linesIterator.toList else Nil
    }

  private def mount(
    ws: os.Path,
    delegateId: String,
    system: ActorSystem,
    res: SharedResources
  ): IO[DelegateFlowRuntime] =
    DelegateFlowHosts
      .mount(
        delegateId = delegateId,
        project = None,
        baseWorkspace = ws.toString,
        engineCwd = ws.toString,
        system = system,
        resources = res,
        wsSend = None,
        reportGateHold = Some(false),
        rootNotifyQuietMs = Some(0),
        destroyWindowMs = Some(0L)
      )
      .map(_.getOrElse(fail("host must mount")))

  private def mountCarrier(name: String, ws: os.Path, system: ActorSystem, res: SharedResources): IO[ProjectRuntime] =
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
        emitEvent = (_, _, _) => IO.unit,
        reportGateHold = Some(false)
      )
      pd = ProjectDef(
        name = name,
        workspace = ws.toString,
        agentFile = (ws / "AGENTS.md").toString,
        createdAt = System.currentTimeMillis()
      )
      rt = ProjectRuntime(pd, store, engine, system, res, None)
      _ <- ProjectRuntimeRegistry.register(rt)
    yield rt

  private def seedTranscript(sid: String, msgs: List[Message]): Unit =
    os.write.over(tempRoot / "sessions" / s"$sid.json", msgs.asJson.noSpaces)

  private def msg(role: MessageRole, text: String): Message = Message(role = role, content = Left(text))

  /** 直接落一个指定形态的节点（G2/G3 用——不跑真实 agent，控制状态与时间戳）。 */
  private def seedNode(
    h: DelegateFlowRuntime,
    nodeId: String,
    status: String,
    out: List[OutEdge],
    ageMs: Long,
    extra: NodeDef => NodeDef = identity
  ): IO[Unit] =
    val now = System.currentTimeMillis()
    h.store.mutate { s =>
      s.copy(nodes =
        s.nodes.updated(
          nodeId,
          extra(
            NodeDef(
              id = nodeId,
              name = s"node-$nodeId",
              agent = "general",
              task = Some("watchdog probe task"),
              taskId = Some(s"task-$nodeId"),
              out = out,
              status = status,
              createdAt = now - ageMs,
              startedAt = Some(now - ageMs),
              completedAt = if NodeLifecycle.Terminal.contains(status) then Some(now - ageMs) else None,
              deliveredTo = Nil
            )
          )
        )
      )
    }.void

  // ── G1：completed → 唤醒（E2E 主链）───────────────────────────────────────

  test("G1: end-node completion wakes the dispatcher (creator delegate) exactly once via the (pass)Nebula root edge") {
    val ws = os.temp.dir(prefix = "nb-dww-g1-", deleteOnExit = false)
    val system = ActorSystem(s"dww-g1-${scala.util.Random.nextInt(1000000)}")
    val delegateId = "delegate-general-dwwg1aa"
    val sid = "node-dwwg1n1"
    val llm = ScriptLlm(_ => "G1-CHAIN-DONE")
    for
      res <- mkResources(system, llm.handle)
      recorded <- registerRecorder(res, system, delegateId, AgentKind.Delegate)
      _ <- seedDelegateRecord(delegateId, ws)
      _ <- mountCarrier("dww-carrier-g1", ws, system, res)
      _ <- mount(ws, delegateId, system, res)
      h0 <- DelegateFlowHosts.get(delegateId).map(_.getOrElse(fail("host")))
      _ <- seedNode(h0, "n-g1", NodeLifecycle.Running, List(OutEdge.root), 3_600_000L, _.copy(sessionRef = Some(sid)))
      _ <- DelegateFlowHosts.unmount(delegateId) // 模拟进程死亡（recover 认领重挂，与 R1 骨架一致）
      _ <- IO(seedTranscript(sid, List(msg(MessageRole.User, "原始任务"), msg(MessageRole.Assistant, "WIP-HALF"))))
      _ <- ProjectCrashRecovery.recoverDelegateScopes(reportGateHold = Some(false), rootNotifyQuietMs = Some(0))
      h <- DelegateFlowHosts.get(delegateId).map(_.getOrElse(fail("host after recover")))
      _ <- waitUntil(30.seconds)(
        h.store.snapshot.map(_.nodes.get("n-g1").exists(n => NodeLifecycle.Terminal.contains(n.status)))
      )
      _ <- waitUntil(10.seconds)(
        recorded.get.map(_.exists {
          case m: AgentCommand.ImmediateInput => m.text.contains("[Node 'node-n-g1' completed]")
          case _                              => false
        })
      )
      imms <- recorded.get.map(_.collect { case m: AgentCommand.ImmediateInput => m })
      n <- h.store.snapshot.map(_.nodes("n-g1"))
      _ <- DelegateFlowHosts.unmount(delegateId)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(n.status, NodeLifecycle.Completed, "the chain node reaches the terminal state")
      assertEquals(
        imms.count(_.text.contains("[Node 'node-n-g1' completed]")),
        1,
        "G1: completion wakes the dispatcher exactly once through the real root-notify chain"
      )
    end for
  }

  // ── G2：failed / cancelled → 唤醒 ─────────────────────────────────────────

  test("G2: failed and cancelled terminal states both reach the dispatcher (root failure notice + DispatchNotify leg)") {
    val ws = os.temp.dir(prefix = "nb-dww-g2-", deleteOnExit = false)
    val system = ActorSystem(s"dww-g2-${scala.util.Random.nextInt(1000000)}")
    val delegateId = "delegate-general-dwwg2bb"
    val llm = ScriptLlm(_ => "UNUSED")
    for
      res <- mkResources(system, llm.handle)
      recorded <- registerRecorder(res, system, delegateId, AgentKind.Delegate)
      _ <- seedDelegateRecord(delegateId, ws)
      _ <- mount(ws, delegateId, system, res)
      h <- DelegateFlowHosts.get(delegateId).map(_.getOrElse(fail("host")))
      // failed 腿：终态写点（failNodeR 同包可见）→ deliverFailed → retryOrNotify →
      // DispatchNotify（flag-free）5s 批窗 → DelegateNotify.inject。((pass)Nebula result
      // 边对 failed 不投根——failedRoot 只认 on∋failed 边；本腿真实生产路径即 DispatchNotify）。
      _ <- seedNode(h, "n-g2f", NodeLifecycle.Running, List(OutEdge.root), 60_000L)
      failedWritten <- h.engine.failNodeR("n-g2f", "G2-BOOM: simulated infrastructure failure")
      _ <- waitUntil(30.seconds)(
        recorded.get.map(_.exists {
          case m: AgentCommand.ImmediateInput => m.text.contains("failed") && m.text.contains("node-n-g2f")
          case _                              => false
        })
      )
      // cancelled 腿：cancelNodeById → cancelled 终态 + notifyTerminal(Cancelled)
      // （flag-free）→ DispatchNotify 5s 批窗 → DelegateNotify.inject。
      _ <- seedNode(h, "n-g2c", NodeLifecycle.Running, List(OutEdge.root), 60_000L)
      _ <- h.engine.cancelNodeById("n-g2c")
      _ <- waitUntil(30.seconds)(
        recorded.get.map(_.exists {
          case m: AgentCommand.ImmediateInput => m.text.contains("cancelled") && m.text.contains("node-n-g2c")
          case _                              => false
        })
      )
      imms <- recorded.get.map(_.collect { case m: AgentCommand.ImmediateInput => m })
      nf <- h.store.snapshot.map(_.nodes("n-g2f"))
      nc <- h.store.snapshot.map(_.nodes("n-g2c"))
      _ <- DelegateFlowHosts.unmount(delegateId)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(failedWritten, "failNodeR persisted the failed terminal state")
      assertEquals(nf.status, NodeLifecycle.Failed)
      assertEquals(nc.status, NodeLifecycle.Cancelled)
      assert(
        imms.exists(m => m.text.contains("failed") && m.text.contains("node-n-g2f") && m.text.contains("G2-BOOM")),
        "G2-failed: the failure notice reaches the dispatcher (DispatchNotify flag-free leg, error text included)"
      )
      assert(
        imms.exists(m => m.text.contains("cancelled") && m.text.contains("node-n-g2c")),
        "G2-cancelled: the cancellation notice reaches the dispatcher (DispatchNotify flag-free leg)"
      )
    end for
  }

  // ── G3：长期无事件 → 看门狗兜底（有界 + 可读结论 + 预算重置 + 项目面零触碰）──

  test("G3: long no-advance triggers the bounded fallback wake, then a readable exhausted conclusion; progress resets the budget; per-project face untouched") {
    val ws = os.temp.dir(prefix = "nb-dww-g3-", deleteOnExit = false)
    val system = ActorSystem(s"dww-g3-${scala.util.Random.nextInt(1000000)}")
    val delegateId = "delegate-general-dwwg3cc"
    val llm = ScriptLlm(_ => "UNUSED")
    val capKey = "nebflow.dispatcher.wakeWatchdogMs"
    val capOrig = sys.props.getOrElse(capKey, null)
    sys.props.put(capKey, "1") // 静默阈值 1ms：驱动即判（判据本体 = 阈值比较，值大小不改变逻辑）
    for
      res <- mkResources(system, llm.handle)
      recorded <- registerRecorder(res, system, delegateId, AgentKind.Delegate)
      _ <- seedDelegateRecord(delegateId, ws)
      carrier <- mountCarrier("dww-carrier-g3", ws, system, res)
      _ <- mount(ws, delegateId, system, res)
      h <- DelegateFlowHosts.get(delegateId).map(_.getOrElse(fail("host")))
      // 事故形态复刻：completed 1h 前、已投递记账（nebulaDeliveredAt 置位）——30s 补投
      // 扫描因持久锚**不再重投**，dispatcher Idle ⇒ 无人推进 ⇒ 只能靠看门狗。
      _ <- seedNode(
        h,
        "n-g3",
        NodeLifecycle.Completed,
        List(OutEdge.root),
        3_600_000L,
        _.copy(result = Some("G3 delivered-but-unconsumed"), nebulaDeliveredAt = Some(System.currentTimeMillis() - 3_600_000L))
      )
      _ <- h.engine.dispatcherWakeWatchdog()
      w1 <- recorded.get.map(_.count {
        case m: AgentCommand.ImmediateInput => m.text.contains("dispatcher-wake-watchdog wake #1/")
        case _                              => false
      })
      _ <- h.engine.dispatcherWakeWatchdog()
      _ <- h.engine.dispatcherWakeWatchdog()
      w3 <- recorded.get.map(_.count {
        case m: AgentCommand.ImmediateInput => m.text.contains("dispatcher-wake-watchdog wake #3/")
        case _                              => false
      })
      // 预算耗尽：第 4 拍不再注入，落 outcome=exhausted 可读结论。
      _ <- h.engine.dispatcherWakeWatchdog()
      exhaustedSeen <- waitUntil(5.seconds)(readEvents(ws).map(_.exists(l =>
        l.contains(NodeEngine.DispatcherWakeWatchdogEventType) && l.contains("outcome=exhausted")
      ))).attempt.map(_.isRight)
      wAny <- recorded.get.map(_.count {
        case m: AgentCommand.ImmediateInput => m.text.contains("dispatcher-wake-watchdog wake #")
        case _                              => false
      })
      _ <- h.engine.dispatcherWakeWatchdog() // concluded ⇒ 第 5 拍零动作
      wStill <- recorded.get.map(_.count {
        case m: AgentCommand.ImmediateInput => m.text.contains("dispatcher-wake-watchdog wake #")
        case _                              => false
      })
      wakeEvents <- readEvents(ws).map(_.count(l =>
        l.contains(NodeEngine.DispatcherWakeWatchdogEventType) && l.contains("outcome=wake")
      ))
      // 预算重置：链面有进展（锚点前进）⇒ 新静默期，重新给预算。
      _ <- seedNode(
        h,
        "n-g3",
        NodeLifecycle.Completed,
        List(OutEdge.root),
        0L,
        _.copy(result = Some("G3 delivered-but-unconsumed"), nebulaDeliveredAt = Some(System.currentTimeMillis()))
      )
      _ <- IO.sleep(20.millis) // 令锚点静默跨过 1ms 阈值（判据 = 阈值比较，不依赖真实分钟级等待）
      _ <- h.engine.dispatcherWakeWatchdog()
      wReset <- recorded.get.map(_.count {
        case m: AgentCommand.ImmediateInput => m.text.contains("dispatcher-wake-watchdog wake #1/")
        case _                              => false
      })
      // per-project 面（ProjectRoot）结构性零触碰。
      _ <- carrier.engine.dispatcherWakeWatchdog()
      carrierEvents <- readEvents(ws).map(_.count(l =>
        l.contains(NodeEngine.DispatcherWakeWatchdogEventType) && l.contains("root=nebula-root")
      ))
      _ <- DelegateFlowHosts.unmount(delegateId)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
      _ <- IO {
        if capOrig == null then sys.props.remove(capKey) else sys.props.put(capKey, capOrig)
      }
    yield
      assertEquals(w1, 1, "G3: first quiet tick injects fallback wake #1 (dispatcher Idle + no advance)")
      assertEquals(w3, 1, "wake #3 lands within the bounded budget")
      assertEquals(wAny, 3, "exactly 3 wakes injected in the quiet episode (bounded, no infinite nagging)")
      assertEquals(wStill, 3, "after exhaustion no further injection happens (concluded)")
      assert(exhaustedSeen, "a readable outcome=exhausted conclusion event is persisted")
      assertEquals(wakeEvents, 3, "each wake leaves a greppable dispatcher-wake-watchdog audit line")
      assertEquals(wReset, 2, "chain progress (anchor advance) resets the wake budget for a new episode")
      assertEquals(carrierEvents, 0, "per-project engine (ProjectRoot face) is structurally untouched by the watchdog")
    end for
  }

end DispatcherWakeWatchdogSpec
