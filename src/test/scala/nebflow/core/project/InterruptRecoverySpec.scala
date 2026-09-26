package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, Behaviors}
import nebflow.actor.{AgentCommand, AgentKind, AgentRecord}
import nebflow.agent.{AgentLibrary, SharedResources}
import nebflow.core.tools.{FileLockManager, NodeEditTool, ToolContext}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{Defaults, LlmHandle, LlmRequest, LlmResponse, Message, MessageRole, PathUtil, StreamChunk}
import nebflow.shared.given

import scala.concurrent.duration.*

/**
 * 中断恢复语义验收（批 R1/R2，2026-09-13；设计正本
 * `.nebflow/Spec/20260908_interrupt-recovery-semantics.md` §2.2/§2.3/§2.4/§2.7/§2.8）。
 *
 * 覆盖（编号对齐 spec §4 R1/R2 用例组）：
 *  - **R1a 优雅关机翻态与守卫**：Running → interrupted（WS nodeUpdated + `interrupted`
 *    审计留痕、bgWait 清空、sessionRef/deliveredTo 保留、result/TTL 不写）；非 Running
 *    节点零触碰；draining 置位时 `failNode`（经 bootRecoveryClaim (c) 类）与
 *    `autoFailDeadRunning`（经 settleStaleRunningNodes）**双守卫拒绝**（零 failed 写、
 *    零 deliverFailed 通知、零 dead-session-reaped）；对照组（draining 未置位）证明
 *    守卫承重。
 *  - **R1b 资格扩展认领续跑**：interrupted + transcript → boot sweep 认领 → 同 session
 *    断点水合续跑 → completed（与 kill -9 的 Running 残留同链，R8 零新机制）；
 *    class (c)（transcript 丢失）仍走真失败链（failed 通知合法）。
 *  - **R1c 降级模式零收敛**：interrupted 停留原态——watchdog ≥2 拍 / settle 回扫 /
 *    补投扫描 / 销毁窗口扫描 **结构性零触碰**（零收敛零通知零事件）。
 *  - **R1d 回滚开关逐字节回退**：`nebflow.shutdownInterrupt.enabled=false` ⇒ 零翻态、
 *    draining 恒 false（守卫结构性失效）、资格集不含 interrupted、优雅关机后的失败
 *    路径与本批前一致（failed + 通知）。
 *  - **R1e 超时降级路径**：可注入 timeout —— 部分翻态后 deadline 触发，残余 Running
 *    由既有 sweep 兜底；`timeoutMs=0` 全量确定性降级。
 *  - **R2 NodeMessage / reactivate**：interrupted 走 undelivered 路径（记录在 task
 *    「注入未达」+ 事件留痕，**绝不按终态拒收**）+ 活会话注入面；NodeEdit 对
 *    interrupted = fresh 重跑（新会话，非续跑）且可跑到 completed。
 */
class InterruptRecoverySpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-interrupt-recovery"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "general")

  os.write.over(
    tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"general executor","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")
  os.makeDir.all(tempRoot / "agents" / "test-agent")

  os.write.over(
    tempRoot / "agents" / "test-agent" / "agent.json",
    """{"name":"test-agent","description":"interrupt spec agent","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "test-agent" / "system.md", "# test-agent\n")
  os.makeDir.all(tempRoot / "sessions")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  // 每个用例前后复位三处全局单点（draining 标志 + 回滚开关 + 项目注册表）——测试隔离。
  // 注册表必须**真正执行** clear（bare `ProjectRuntimeRegistry.clear` 只构造 IO 而丢弃，
  // 注册会跨用例残留——本 spec 的钩子断言遍历全部已挂载项目，泄漏会污染报告）。
  private def resetGlobals(): Unit =
    ShutdownState.resetForTest()
    sys.props.remove("nebflow.shutdownInterrupt.enabled")
    sys.props.remove("nebflow.crashRecovery.enabled")
    ProjectRuntimeRegistry.clear.unsafeRunSync()(using cats.effect.unsafe.implicits.global)

  override def beforeEach(context: munit.BeforeEach): Unit = resetGlobals()
  override def afterEach(context: munit.AfterEach): Unit = resetGlobals()

  // ── 基建（BootCrashRecoverySpec 同款骨架）─────────────────────────────

  private val global = cats.effect.unsafe.implicits.global

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

  private class ScriptLlm(respond: String => String):
    val fullReqs: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)
    val lastTurns: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)

    def handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(
        req: LlmRequest,
        onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]]
      ): Stream[IO, StreamChunk] =
        val text = req.messages.map(_.textContent).mkString("\n---\n")
        val last = req.messages.lastOption.map(_.textContent).getOrElse("")
        Stream
          .eval(fullReqs.update(_ :+ text) >> lastTurns.update(_ :+ last))
          .flatMap(_ => Stream(StreamChunk.TextDelta(respond(last)), StreamChunk.Done(None, None)))

  end ScriptLlm

  private def registerRecorder(
    res: SharedResources,
    system: ActorSystem,
    sid: String
  ): IO[Ref[IO, List[AgentCommand]]] =
    for
      recorded <- Ref.of[IO, List[AgentCommand]](Nil)
      ref <- system.spawn(recorderBehavior(recorded), s"rec-${scala.util.Random.nextInt(1000000)}")
      _ <- res.agentRegistry.update(_ + (sid -> AgentRecord(sid, ref, AgentKind.Root, sid)))
    yield recorded

  private def recorderBehavior(recorded: Ref[IO, List[AgentCommand]]): nebflow.actor.Behavior[AgentCommand] =
    lazy val b: nebflow.actor.Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand](msg => recorded.update(_ :+ msg).as(b))
    b

  private def mountProject(
    name: String,
    ws: os.Path,
    system: ActorSystem,
    res: SharedResources,
    wsFrames: Option[Ref[IO, List[io.circe.Json]]] = None
  ): IO[ProjectRuntime] =
    for
      store <- FlowMapStore.open(name, ws.toString)
      engine = new NodeEngine(
        store,
        system,
        res,
        wsSendFn = (j: io.circe.Json) => wsFrames.fold(IO.unit)(_.update(_ :+ j)),
        workspace = ws.toString,
        rootSessionId = "nebula-root",
        projectName = name,
        // nodeUpdated 走 emitEvent 通道（emitWithChain → emitEvent），
        // 与生产 wsSend 同帧形状：{type, nodeId, node}
        emitEvent = (t, id, payload) =>
          wsFrames.fold(IO.unit)(
            _.update(_ :+ io.circe.Json.obj("type" -> t.asJson, "nodeId" -> id.asJson, "node" -> payload))
          ),
        reportGateHold = Some(false),
        // notifybatch 返工（2026-09-18 · F-2 对齐）：root 通道打包窗**显式关窗**——
        // 本 fixture 主题 = 中断恢复的 failed 通报（R1a/R1b），其断言要求
        // `[Node '<n>' failed]` **即时**到达 root（窗语义下最多晚 `rootNotifyQuietMs`）。
        // 窗本体由 `RootNotifyBatchSpec` 专项覆盖；🔴 原断言一字未改。
        rootNotifyQuietMs = Some(0)
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

  private def byName(rt: ProjectRuntime, name: String): IO[NodeDef] =
    rt.store.snapshot.map(_.nodes.values.find(_.name == name)).map {
      case Some(n) => n
      case None => fail(s"node '$name' must exist")
    }

  private def readEvents(ws: os.Path): IO[List[String]] =
    IO.blocking {
      val f = ws / ".nebflow" / "flow-map-events.jsonl"
      if os.exists(f) then os.read(f).linesIterator.toList else Nil
    }

  private def seedTranscript(sid: String, msgs: List[Message]): Unit =
    os.write.over(tempRoot / "sessions" / s"$sid.json", msgs.asJson.noSpaces)

  private def msg(role: MessageRole, text: String): Message = Message(role = role, content = Left(text))

  /** 播种节点（默认 Running = 优雅关机的现场形态；status 可指定以便播种 interrupted）。 */
  private def seedNode(
    rt: ProjectRuntime,
    id: String,
    name: String,
    task: String,
    status: String = NodeLifecycle.Running,
    out: List[OutEdge] = List(OutEdge.root),
    sessionRef: Option[String] = None,
    bgWait: Option[String] = None,
    deliveredTo: List[String] = Nil,
    in: List[String] = Nil
  ): IO[Unit] =
    rt.store.mutate { s =>
      s.copy(nodes =
        s.nodes.updated(
          id,
          NodeDef(
            id = id,
            name = name,
            agent = "general",
            task = Some(task),
            out = out,
            in = in,
            status = status,
            startedAt = Some(System.currentTimeMillis() - 3_600_000),
            createdAt = System.currentTimeMillis() - 3_600_000,
            sessionRef = sessionRef,
            bgWait = bgWait,
            deliveredTo = deliveredTo
          )
        )
      )
    }.void

  private def triggerRecorder: IO[(Ref[IO, List[String]], Option[String => IO[Unit]])] =
    Ref.of[IO, List[String]](Nil).map(r => (r, Some((t: String) => r.update(_ :+ t))))

  // ══ R1a 优雅关机翻态 ════════════════════════════════════════════════

  test(
    "R1a: graceful shutdown flips every Running node to interrupted (bgWait cleared, sessionRef/deliveredTo kept, result untouched) and leaves other statuses alone"
  ) {
    val ws = tempRoot / "ws-r1a"
    os.makeDir.all(ws)
    val system = ActorSystem(s"ir-r1a-${scala.util.Random.nextInt(1000000)}")
    val llm = ScriptLlm(_ => "X")
    for
      res <- mkResources(system, llm.handle)
      wsFrames <- Ref.of[IO, List[io.circe.Json]](Nil)
      rt <- mountProject("ir-r1a", ws, system, res, Some(wsFrames))
      _ <- seedNode(
        rt,
        "n-r1a-1",
        "run-a",
        "task a",
        sessionRef = Some("node-r1a1"),
        bgWait = Some("1 background task(s): 'compile'"),
        deliveredTo = List("n-up")
      )
      _ <- seedNode(rt, "n-r1a-2", "run-b", "task b", sessionRef = Some("node-r1a2"))
      // 非 Running 节点：零触碰对照组
      _ <- seedNode(rt, "n-r1a-3", "pend-c", "task c", status = NodeLifecycle.Pending)
      _ <- seedNode(rt, "n-r1a-4", "done-d", "task d", status = NodeLifecycle.Completed)
      // 生产路径同款前置：钩子第一腿先置 draining，再跑翻态腿
      _draining = ShutdownState.beginDraining()
      rep <- GracefulInterruptHook.interruptRunningNodes(5_000L)
      a <- byName(rt, "run-a")
      b <- byName(rt, "run-b")
      c <- byName(rt, "pend-c")
      d <- byName(rt, "done-d")
      events <- readEvents(ws)
      frames <- wsFrames.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(rep.flipped.sorted, List("n-r1a-1", "n-r1a-2"), "every Running node flips")
      assertEquals(rep.timedOut, false)
      assertEquals(rep.remaining, Nil)
      assertEquals(a.status, NodeLifecycle.Interrupted)
      assertEquals(b.status, NodeLifecycle.Interrupted)
      assert(
        !NodeLifecycle.Terminal.contains(NodeLifecycle.Interrupted),
        "interrupted must NOT be a terminal status (no auto-archive, no TTL write point)"
      )
      assertEquals(a.bgWait, None, "bgWait must be cleared (wait set evaporated with the process — G4)")
      assertEquals(a.sessionRef, Some("node-r1a1"), "sessionRef preserved (recovery anchor)")
      assertEquals(a.deliveredTo, List("n-up"), "deliveredTo preserved (in-barrier already-received delivery)")
      assertEquals(a.result, None, "no failure fact written")
      assertEquals(a.completedAt, None, "no completion timestamp written")
      assertEquals(a.ttlExpireAt, None, "no TTL write point → never auto-archived")
      assertEquals(c.status, NodeLifecycle.Pending, "non-Running statuses untouched")
      assertEquals(d.status, NodeLifecycle.Completed, "terminal statuses untouched")
      assert(
        events.exists(e => e.contains("\"interrupted\"")),
        s"interrupted audit trail expected, got: ${events.mkString("|").take(400)}"
      )
      assert(
        frames.exists(f => f.hcursor.get[String]("type").toOption.contains("nodeUpdated")),
        s"nodeUpdated WS frame expected, got: ${frames.map(_.noSpaces.take(80)).mkString("|").take(300)}"
      )
      assert(
        frames.exists(f =>
          f.hcursor.downField("node").get[String]("status").toOption.contains(NodeLifecycle.Interrupted)
        ),
        "WS payload must carry status=interrupted (NodePayload passthrough)"
      )
    end for
  }

  test(
    "R1a-guard: while draining, both failure writers refuse (failNode via class-c claim on Running AND on interrupted, autoFailDeadRunning via watchdog) and never deliverFailed"
  ) {
    val ws = tempRoot / "ws-r1a-g"
    os.makeDir.all(ws)
    val system = ActorSystem(s"ir-r1ag-${scala.util.Random.nextInt(1000000)}")
    val llm = ScriptLlm(_ => "X")
    for
      res <- mkResources(system, llm.handle)
      recorded <- registerRecorder(res, system, "nebula-root")
      rt <- mountProject("ir-r1a-g", ws, system, res)
      // failNode 面（Running 形态）：(c) 类候选（无 sessionRef）
      _ <- seedNode(rt, "n-g1", "dead-a", "dead task")
      // failNode 面（interrupted 形态）：钩子已翻态的节点收到 abort 诱发的失败链
      _ <- seedNode(rt, "n-g3", "flipped-c", "flipped task", sessionRef = Some("node-r1agnofile"))
      _draining = ShutdownState.beginDraining()
      _ <- GracefulInterruptHook.interruptRunningNodes(5_000L)
      snap <- rt.store.snapshot
      claim1 <- rt.engine.bootRecoveryClaim(snap.nodes("n-g1"))
      claim3 <- rt.engine.bootRecoveryClaim(snap.nodes("n-g3"))
      // autoFailDeadRunning 面：钩子 deadline 降级后的残余 Running（R1e 形态）——
      // draining 期间 watchdog 必须同样拒绝死会话收敛。
      _ <- seedNode(rt, "n-g2", "zombie-b", "zombie task")
      _ <- rt.engine.settleStaleRunningNodes()
      _ <- rt.engine.settleStaleRunningNodes() // ≥2 拍
      g1 <- byName(rt, "dead-a")
      g2 <- byName(rt, "zombie-b")
      g3 <- byName(rt, "flipped-c")
      events <- readEvents(ws)
      imms <- recorded.get.map(_.collect { case m: AgentCommand.ImmediateInput => m })
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(
        claim1,
        None,
        "class-c claim must NOT report a disposition it could not apply (no false 'failed' record)"
      )
      assertEquals(claim3, None)
      assertEquals(
        g1.status,
        NodeLifecycle.Interrupted,
        "failNode refuses to write failed while draining (already-flipped form)"
      )
      assertEquals(
        g3.status,
        NodeLifecycle.Interrupted,
        "failNode refuses to write failed while draining (interrupted form)"
      )
      assertEquals(
        g2.status,
        NodeLifecycle.Running,
        "watchdog refuses the dead-session failed convergence while draining"
      )
      assertEquals(g1.result, None, "no failure result written while draining")
      assertEquals(g3.result, None)
      assert(
        events.exists(e => e.contains("\"interrupted\"") && e.contains("failed write suppressed while draining")),
        s"failNode guard must leave an audit note, got: ${events.filter(_.contains("suppressed")).mkString("|").take(400)}"
      )
      assert(
        events.exists(e =>
          e.contains("\"interrupted\"") && e.contains("dead-session failed write suppressed while draining")
        ),
        s"autoFailDeadRunning guard must leave an audit note, got: ${events.filter(_.contains("suppressed")).mkString("|").take(400)}"
      )
      assert(!events.exists(_.contains("dead-session-reaped")), "no dead-session convergence while draining")
      assert(!events.exists(_.contains("failed (class c)")), "no boot-recovery failure disposition while draining")
      assert(
        imms.forall(!_.text.contains("failed")),
        s"deliverFailed must NOT fire while draining, got: ${imms.map(_.text.take(80)).mkString("|")}"
      )
    end for
  }

  test(
    "R1a-guard-control: with draining NOT set the same paths DO converge to failed (proves the guard is load-bearing)"
  ) {
    val ws = tempRoot / "ws-r1a-c"
    os.makeDir.all(ws)
    val system = ActorSystem(s"ir-r1ac-${scala.util.Random.nextInt(1000000)}")
    val llm = ScriptLlm(_ => "X")
    for
      res <- mkResources(system, llm.handle)
      recorded <- registerRecorder(res, system, "nebula-root")
      rt <- mountProject("ir-r1a-c", ws, system, res)
      _ <- seedNode(rt, "n-c1", "dead-a", "dead task")
      snap <- rt.store.snapshot
      _ <- rt.engine.bootRecoveryClaim(snap.nodes("n-c1"))
      _ <- seedNode(rt, "n-c2", "zombie-b", "zombie task")
      _ <- rt.engine.settleStaleRunningNodes()
      a <- byName(rt, "dead-a")
      b <- byName(rt, "zombie-b")
      events <- readEvents(ws)
      imms <- recorded.get.map(_.collect { case m: AgentCommand.ImmediateInput => m })
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(
        a.status,
        NodeLifecycle.Failed,
        "without draining the class-c path fails the node (pre-batch behaviour)"
      )
      assertEquals(b.status, NodeLifecycle.Failed, "without draining the watchdog converges the residue")
      assert(events.exists(_.contains("dead-session-reaped")))
      assert(
        events.exists(_.contains("failed (class c)")),
        "boot-recovery failure disposition present without draining"
      )
      assert(imms.exists(_.text.contains("failed")), "failed notification fires without draining")
    end for
  }

  // ══ R1b 资格扩展认领续跑 ════════════════════════════════════════════

  test(
    "R1b: interrupted node is claimed by the boot sweep and resumes from the SAME session checkpoint (not a fresh rerun from the task)"
  ) {
    val ws = tempRoot / "ws-r1b"
    os.makeDir.all(ws)
    val system = ActorSystem(s"ir-r1b-${scala.util.Random.nextInt(1000000)}")
    val sid = "node-r1bb1234"
    val llm = ScriptLlm(_ => "RESUMED-AFTER-INTERRUPT")
    for
      res <- mkResources(system, llm.handle)
      _ <- registerRecorder(res, system, "nebula-root")
      rt <- mountProject("ir-r1b", ws, system, res)
      _ = seedTranscript(
        sid,
        List(msg(MessageRole.User, "原始任务：产出报告 R"), msg(MessageRole.Assistant, "INTERRUPTED-HALFWAY-CONTEXT"))
      )
      _ <- seedNode(rt, "n-r1b", "mid-a", "产出报告 R", status = NodeLifecycle.Interrupted, sessionRef = Some(sid))
      (trig, trigger) <- triggerRecorder
      actions <- ProjectCrashRecovery.recoverProject(rt, trigger = trigger)
      _ <- waitUntil(25.seconds)(byName(rt, "mid-a").map(n => NodeLifecycle.Terminal.contains(n.status)))
      n <- byName(rt, "mid-a")
      reqs <- llm.fullReqs.get
      turns <- llm.lastTurns.get
      events <- readEvents(ws)
      trigTexts <- trig.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(actions, 1, "one recovery action (rehydrate claim) for the interrupted node")
      assertEquals(n.status, NodeLifecycle.Completed)
      assertEquals(n.result, Some("RESUMED-AFTER-INTERRUPT"))
      assertEquals(n.sessionRef, Some(sid), "same session id reused (checkpoint resume, not a new run)")
      assert(
        reqs.exists(_.contains("INTERRUPTED-HALFWAY-CONTEXT")),
        "the LLM request must carry the hydrated transcript (progress not lost)"
      )
      assert(
        turns.exists(_.contains("crashed during your last turn and restarted")),
        s"resume prompt expected, got: ${turns.headOption.map(_.take(300))}"
      )
      assert(
        events.exists(e => e.contains("boot-recovery") && e.contains("n-r1b") && e.contains("claimed")),
        s"claim audit event expected, got: ${events.mkString("|").take(400)}"
      )
      assertEquals(trigTexts.size, 1, "exactly one project-level summary notification")
    end for
  }

  test(
    "R1b-class-c: interrupted node with a lost transcript fails through the normal chain (failed notification is legitimate)"
  ) {
    val ws = tempRoot / "ws-r1b-c"
    os.makeDir.all(ws)
    val system = ActorSystem(s"ir-r1bc-${scala.util.Random.nextInt(1000000)}")
    val llm = ScriptLlm(_ => "SHOULD-NOT-RUN")
    for
      res <- mkResources(system, llm.handle)
      recorded <- registerRecorder(res, system, "nebula-root")
      rt <- mountProject("ir-r1b-c", ws, system, res)
      _ <- seedNode(
        rt,
        "n-r1bc",
        "lost-a",
        "lost task",
        status = NodeLifecycle.Interrupted,
        sessionRef = Some("node-r1bcnofile")
      )
      (trig, trigger) <- triggerRecorder
      actions <- ProjectCrashRecovery.recoverProject(rt, trigger = trigger)
      n <- byName(rt, "lost-a")
      events <- readEvents(ws)
      imms <- recorded.get.map(_.collect { case m: AgentCommand.ImmediateInput => m })
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(actions, 1, "a class-c disposition counts as an action")
      assertEquals(n.status, NodeLifecycle.Failed)
      assert(
        n.result.exists(_.contains("crash recovery: session transcript lost/corrupt")),
        s"class-c reason expected, got ${n.result}"
      )
      assert(
        imms.exists(_.text.contains("[Node 'lost-a' failed]")),
        "a genuinely unrecoverable node still notifies the dispatcher (spec §2.4 class c)"
      )
      assert(events.exists(e => e.contains("boot-recovery") && e.contains("failed (class c)")))
    end for
  }

  // ══ R1c 降级模式诚实（crashRecovery=false）══════════════════════════

  test(
    "R1c: degraded mode (crashRecovery disabled) — interrupted node stays put across ≥2 watchdog ticks and every other sweep (zero convergence, zero notify, zero events)"
  ) {
    val ws = tempRoot / "ws-r1c"
    os.makeDir.all(ws)
    val system = ActorSystem(s"ir-r1c-${scala.util.Random.nextInt(1000000)}")
    val llm = ScriptLlm(_ => "X")
    sys.props("nebflow.crashRecovery.enabled") = "false"
    for
      res <- mkResources(system, llm.handle)
      recorded <- registerRecorder(res, system, "nebula-root")
      rt <- mountProject("ir-r1c", ws, system, res)
      _ <- seedNode(
        rt,
        "n-r1c",
        "degraded-a",
        "degraded task",
        status = NodeLifecycle.Interrupted,
        sessionRef = Some("node-r1cx")
      )
      // GatewayMain 在 crashRecovery=false 时不挂 sweep ⇒ 这里不调用 recoverProject，
      // 只跑全部既有周期腿（watchdog 两拍 + settle 回扫 + 补投扫描 + 销毁窗口扫描）
      _ <- rt.engine.settleStaleRunningNodes()
      _ <- rt.engine.settleStaleRunningNodes()
      _ <- rt.engine.settleRunnableSweep()
      _ <- rt.engine.redeliverUnconsumedRootResults()
      _ <- rt.engine.sweepDestroyWindows()
      n <- byName(rt, "degraded-a")
      events <- readEvents(ws)
      imms <- recorded.get.map(_.collect { case m: AgentCommand.ImmediateInput => m })
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(Defaults.CrashRecoveryEnabled, false, "fixture precondition: degraded mode flag")
      assertEquals(n.status, NodeLifecycle.Interrupted, "interrupted stays put — no watchdog/settle convergence")
      assertEquals(n.result, None, "no failure text")
      assertEquals(n.destroyAt, None, "no destroy window (non-terminal → scheduleDestroy refuses)")
      assertEquals(n.bgWait, None)
      assert(events.isEmpty, s"zero events expected in degraded mode, got: ${events.mkString("|").take(300)}")
      assert(imms.isEmpty, "zero notifications")
    end for
  }

  // ══ R1d 回滚开关逐字节回退 ═══════════════════════════════════════════

  test(
    "R1d: rollback switch off — no flip, no draining (guards inert), eligibility set excludes interrupted, failure path unchanged"
  ) {
    val ws = tempRoot / "ws-r1d"
    os.makeDir.all(ws)
    val system = ActorSystem(s"ir-r1d-${scala.util.Random.nextInt(1000000)}")
    val llm = ScriptLlm(_ => "X")
    sys.props("nebflow.shutdownInterrupt.enabled") = "false"
    for
      res <- mkResources(system, llm.handle)
      _ <- registerRecorder(res, system, "nebula-root")
      rt <- mountProject("ir-r1d", ws, system, res)
      _ <- seedNode(rt, "n-r1d", "rollback-a", "rollback task")
      // 残留 interrupted（回滚前跑过一批的历史残留）：资格集必须不含它
      _ <- seedNode(rt, "n-r1d-i", "left-b", "left task", status = NodeLifecycle.Interrupted)
      // 钩子执行体（生产等价路径）：开关 false ⇒ 只跑 abort 腿
      _ = GracefulInterruptHook.run(0L)
      drainingAfter = ShutdownState.draining
      a <- byName(rt, "rollback-a")
      events <- readEvents(ws)
      (trig, trigger) <- triggerRecorder
      // 资格集回退：sweep 只认 Running（本批前语义）——Running 残留照常被认领处置，
      // interrupted 残留留在原地（人工处置）
      actions <- ProjectCrashRecovery.recoverProject(rt, trigger = trigger)
      a2 <- byName(rt, "rollback-a")
      left <- byName(rt, "left-b")
      actions2 <- ProjectCrashRecovery.recoverProject(rt, trigger = trigger)
      events2 <- readEvents(ws)
      trigTexts <- trig.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(Defaults.ShutdownInterruptEnabled, false, "fixture precondition: rollback switch")
      assertEquals(a.status, NodeLifecycle.Running, "no flip at all (pre-batch behaviour)")
      assertEquals(drainingAfter, false, "draining never set → both guards structurally inert")
      assert(
        !events.exists(_.contains("\"interrupted\"")),
        s"zero interrupted events, got: ${events.mkString("|").take(300)}"
      )
      assertEquals(actions, 1, "Running residue is still claimed (pre-batch eligibility)")
      assertEquals(
        a2.status,
        NodeLifecycle.Failed,
        "failure path unchanged (guard inert — same shape as R1a-guard-control)"
      )
      assert(
        a2.result.exists(_.contains("crash recovery: session transcript lost")),
        s"class-c reason unchanged, got ${a2.result}"
      )
      assertEquals(
        left.status,
        NodeLifecycle.Interrupted,
        "eligibility set excludes interrupted under rollback (residue left for manual NodeEdit)"
      )
      assertEquals(actions2, 0, "second pass is a no-op (idempotent boot)")
      assert(trigTexts.size == 1 && trigTexts.head.contains("failed"), "the ordinary boot summary is unchanged")
      assert(
        events2.forall(e => !(e.contains("\"interrupted\"") && e.contains("left-b"))),
        "no event ever touches the interrupted residue"
      )
    end for
  }

  // ══ R1e 超时降级路径（可注入超时）════════════════════════════════════

  test(
    "R1e: injectable timeout — deadline gives up on the remaining nodes, which fall back to the kill -9 style boot sweep"
  ) {
    val ws = tempRoot / "ws-r1e"
    os.makeDir.all(ws)
    val system = ActorSystem(s"ir-r1e-${scala.util.Random.nextInt(1000000)}")
    val sidA = "node-r1ea011"
    val llm = ScriptLlm(_ => "TIMEOUT-RESUME-DONE")
    for
      // 注入时钟：每次读前进 1s；timeoutMs=1500 ⇒ 第 1 个节点在预算内、第 2 个已超时
      tick <- Ref.of[IO, Long](0L)
      clock = () => tick.getAndUpdate(_ + 1L).unsafeRunSync()(using global) * 1_000L
      res <- mkResources(system, llm.handle)
      _ <- registerRecorder(res, system, "nebula-root")
      rt <- mountProject("ir-r1e", ws, system, res)
      _ = seedTranscript(
        sidA,
        List(msg(MessageRole.User, "tick task a"), msg(MessageRole.Assistant, "TICK-HALF-CONTEXT"))
      )
      // A 有 transcript（可续跑）；B 的 transcript 缺失（走 class-c 真失败）
      _ <- seedNode(rt, "n-r1e-1", "tick-a", "tick task a", sessionRef = Some(sidA))
      _ <- seedNode(rt, "n-r1e-2", "tick-b", "tick task b", sessionRef = Some("node-r1e0missing"))
      rep <- GracefulInterruptHook.interruptRunningNodes(1_500L, clock)
      a <- byName(rt, "tick-a")
      b <- byName(rt, "tick-b")
      events <- readEvents(ws)
      // 残余 Running 仍被既有 sweep 认领（kill -9 同款兜底）
      (trig, trigger) <- triggerRecorder
      actions <- ProjectCrashRecovery.recoverProject(rt, trigger = trigger)
      _ <- waitUntil(25.seconds)(byName(rt, "tick-a").map(n => NodeLifecycle.Terminal.contains(n.status)))
      a2 <- byName(rt, "tick-a")
      b2 <- byName(rt, "tick-b")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(rep.flipped, List("n-r1e-1"), s"exactly one node flipped before the deadline, report=$rep")
      assertEquals(rep.remaining, List("n-r1e-2"), s"the remaining node is left Running, report=$rep")
      assertEquals(rep.timedOut, true)
      assertEquals(a.status, NodeLifecycle.Interrupted)
      assertEquals(b.status, NodeLifecycle.Running, "residual Running is the safe degraded state")
      assert(events.exists(e => e.contains("\"interrupted\"")), "the flipped node still leaves its audit trail")
      assertEquals(
        actions,
        2,
        "BOTH the interrupted node and the residual Running node are eligible for the ordinary sweep"
      )
      assertEquals(a2.status, NodeLifecycle.Completed, "interrupted node resumes from its checkpoint")
      assertEquals(a2.result, Some("TIMEOUT-RESUME-DONE"))
      assertEquals(
        b2.status,
        NodeLifecycle.Failed,
        "residual Running node is disposed by the ordinary sweep (class c here)"
      )
      assert(
        b2.result.exists(_.contains("crash recovery: session transcript lost/corrupt")),
        s"class-c reason expected, got ${b2.result}"
      )
    end for
  }

  test(
    "R1e-zero: timeout 0 degrades deterministically (zero flips → the kill -9 shape, still recovered by the boot sweep)"
  ) {
    val ws = tempRoot / "ws-r1e0"
    os.makeDir.all(ws)
    val system = ActorSystem(s"ir-r1e0-${scala.util.Random.nextInt(1000000)}")
    val sid = "node-r1e0a"
    val llm = ScriptLlm(_ => "ZERO-TIMEOUT-RESUMED")
    for
      res <- mkResources(system, llm.handle)
      _ <- registerRecorder(res, system, "nebula-root")
      rt <- mountProject("ir-r1e0", ws, system, res)
      _ = seedTranscript(sid, List(msg(MessageRole.User, "task zero"), msg(MessageRole.Assistant, "ZERO-HALF")))
      _ <- seedNode(rt, "n-r1e0", "zero-a", "task zero", sessionRef = Some(sid))
      rep <- GracefulInterruptHook.interruptRunningNodes(0L)
      n <- byName(rt, "zero-a")
      events <- readEvents(ws)
      (trig, trigger) <- triggerRecorder
      actions <- ProjectCrashRecovery.recoverProject(rt, trigger = trigger)
      _ <- waitUntil(25.seconds)(byName(rt, "zero-a").map(x => NodeLifecycle.Terminal.contains(x.status)))
      n2 <- byName(rt, "zero-a")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(rep.flipped, Nil, "zero budget → no flips")
      assertEquals(rep.remaining, List("n-r1e0"))
      assertEquals(rep.timedOut, true)
      assertEquals(n.status, NodeLifecycle.Running, "residual stays Running = the kill -9 shape")
      assert(events.isEmpty, "zero flips → zero audit events")
      assertEquals(actions, 1, "the residual Running node is still claimed by the ordinary sweep")
      assertEquals(n2.status, NodeLifecycle.Completed)
      assertEquals(n2.result, Some("ZERO-TIMEOUT-RESUMED"))
    end for
  }

  // ══ R2 NodeMessage 对 interrupted 走 undelivered 路径 ═══════════════

  test(
    "R2: NodeMessage to an interrupted node goes the undelivered path (recorded on task — never refused as terminal)"
  ) {
    val ws = tempRoot / "ws-r2"
    os.makeDir.all(ws)
    val system = ActorSystem(s"ir-r2-${scala.util.Random.nextInt(1000000)}")
    val llm = ScriptLlm(_ => "X")
    for
      res <- mkResources(system, llm.handle)
      _ <- registerRecorder(res, system, "nebula-root")
      rt <- mountProject("ir-r2", ws, system, res)
      _ <- seedNode(
        rt,
        "n-r2",
        "sleep-a",
        "sleep task",
        status = NodeLifecycle.Interrupted,
        sessionRef = Some("node-r2gone")
      )
      r <- rt.engine.sendNodeMessage("n-r2", "补充指示：改用方案 B")
      n <- byName(rt, "sleep-a")
      events <- readEvents(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isRight, s"interrupted must not be refused: $r")
      assert(
        n.task.exists(_.contains("补充指示：改用方案 B")),
        s"message must be preserved on the node task (readable via NodeList(detail)), got ${n.task}"
      )
      assert(n.task.exists(_.contains("注入未达")), "undelivered marker expected on the task record")
      assert(
        events.exists(e => e.contains("node-message") && e.contains("not-delivered")),
        s"undelivered audit event expected, got: ${events.mkString("|").take(400)}"
      )
      assert(!r.exists(_.contains("became terminal")), "the old terminal-refusal text must be gone")
    end for
  }

  test(
    "R2-live: NodeMessage to an interrupted node with a live session is still injected (no message loss in the hook window)"
  ) {
    val ws = tempRoot / "ws-r2l"
    os.makeDir.all(ws)
    val system = ActorSystem(s"ir-r2l-${scala.util.Random.nextInt(1000000)}")
    val sid = "node-r2live"
    val llm = ScriptLlm(_ => "X")
    for
      res <- mkResources(system, llm.handle)
      recorded <- registerRecorder(res, system, sid)
      rt <- mountProject("ir-r2l", ws, system, res)
      _ <- seedNode(rt, "n-r2l", "live-a", "live task", status = NodeLifecycle.Interrupted, sessionRef = Some(sid))
      r <- rt.engine.sendNodeMessage("n-r2l", "活会话注入指示")
      // 注入是 fire-and-forget tell：等记录 actor 收齐再断言（避免 tell 竞态假红）
      _ <- waitUntil(5.seconds)(recorded.get.map(_.exists {
        case m: AgentCommand.ImmediateInput => m.text.contains("活会话注入指示")
        case _ => false
      }))
      imms <- recorded.get.map(_.collect { case m: AgentCommand.ImmediateInput => m })
      events <- readEvents(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isRight, s"live-session injection must succeed: $r")
      assert(imms.exists(_.text.contains("活会话注入指示")), "ImmediateInput must carry the message")
      assert(imms.forall(_.text.contains("[NODE-MESSAGE]")), "injection carries the standard header")
      assert(events.exists(e => e.contains("node-message") && e.contains("injected")), "injection audit trail expected")
    end for
  }

  // ══ R2 reactivate（降级模式下的人工处置出口）════════════════════════

  test(
    "R2-reactivate: NodeEdit on an interrupted node reactivates it (fresh rerun, NEW session) and it runs to completion"
  ) {
    val ws = tempRoot / "ws-r2r"
    os.makeDir.all(ws)
    val system = ActorSystem(s"ir-r2r-${scala.util.Random.nextInt(1000000)}")
    val llm = ScriptLlm(_ => "RERUN-AFTER-REACTIVATE")
    for
      res <- mkResources(system, llm.handle)
      _ <- registerRecorder(res, system, "nebula-root")
      rt <- mountProject("ir-r2r", ws, system, res)
      _ <- seedNode(
        rt,
        "n-r2r",
        "manual-a",
        "manual task",
        status = NodeLifecycle.Interrupted,
        sessionRef = Some("node-r2rold")
      )
      _ <-
        val ctx = ToolContext(
          projectRoot = ws.toString,
          sessionId = Some("spec-sid"),
          rootSessionId = Some("nebula-root"),
          sharedResources = Some(res),
          actorSystem = Some(system)
        )
        NodeEditTool
          .call(
            io.circe.Json
              .obj(
                "project" -> io.circe.Json.fromString("ir-r2r"),
                "nodename" -> io.circe.Json.fromString("manual-a"),
                "task" -> io.circe.Json.fromString("manual task v2"),
                "out" -> io.circe.Json.fromString("Nebula")
              )
              .asObject
              .get,
            ctx
          )
          .void
      _ <- waitUntil(25.seconds)(byName(rt, "manual-a").map(n => NodeLifecycle.Terminal.contains(n.status)))
      n <- byName(rt, "manual-a")
      events <- readEvents(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(n.status, NodeLifecycle.Completed, "reactivated interrupted node must run to completion")
      assertEquals(n.result, Some("RERUN-AFTER-REACTIVATE"))
      assert(
        n.sessionRef.exists(_ != "node-r2rold"),
        s"fresh rerun = a NEW session (not a resume of the interrupted checkpoint), got ${n.sessionRef}"
      )
      assert(
        events.exists(e => e.contains("reactivated") && e.contains("interrupted node edited")),
        s"reactivation audit event must label the interrupted source, got: ${events.filter(_.contains("reactivated")).mkString("|").take(400)}"
      )
      assert(
        events.exists(e => e.contains("preStatus=interrupted")),
        s"structured preStatus=interrupted tail expected, got: ${events.filter(_.contains("reactivated")).mkString("|").take(400)}"
      )
    end for
  }

end InterruptRecoverySpec
