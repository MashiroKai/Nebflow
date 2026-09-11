package nebflow.core.project

import cats.effect.{IO, Deferred, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, Behaviors}
import nebflow.agent.{AgentCommand, AgentKind, AgentLibrary, AgentRecord, SharedResources}
import nebflow.core.PathUtil
import nebflow.core.tools.{FileLockManager, NodeEditTool, ToolContext}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, Message, MessageRole, StreamChunk}
import nebflow.shared.given

import scala.concurrent.duration.*

/**
 * boot-time 崩溃断点恢复验收（crash-recovery 批 2026-09-07，设计 §4 十二条二值
 * 断言的进程内定向测试形态；§4.7 按作者裁定③改写为 loop 双会话续接专项）。
 *
 * 覆盖（编号对齐设计 §4）：
 *  - C1 主路径：running 残留 + transcript → 认领 → rehydrate 续跑（LLM 请求含水合
 *    历史 + resume prompt 告知）→ completed，Nebula 投递恰一次 + nebulaDeliveredAt
 *    记账 + boot-recovery 审计事件 + sessionRef 复用旧 id。
 *  - C2 不误碰：等 barrier 的 wiring 下游不被 sweep 启动（无 boot-recovery 事件），
 *    只经正常投递链（上游 completed → deliverOut → deliveredTo 恰记一次）启动。
 *  - C3 (c) 类：sessionRef 无值 / transcript 文件缺失 → failed("crash recovery:
 *    session transcript lost") + deliverFailed 既有链（D5 零结算：普通下游停等 /
 *    merge 转 blocked）。
 *  - C4 watchdog 竞速关闭：被认领节点无 dead-session-reaped；未被认领的 running
 *    残留仍由 watchdog 收敛 failed（降级兜底不变）。
 *  - C5 幂等重启：恢复完成后再跑 sweep → 零动作零事件零通知（每 boot 恰一条）。
 *  - C6 bgWait：认领清 bgWait + resume prompt 附后台任务死亡告知。
 *  - C7 loop 双会话续接（裁定③改写 §4.7）：worker 相位 / verify 相位跨崩溃续跑，
 *    loopRound 语义正确（FAIL→打回→PASS 迭代连续）；loop (c) 类 failed。
 *  - C8 sessionRef：正常路径新启动节点 flow-map 落 sessionRef 且指向存在文件，
 *    磁盘 JSON 含该键。
 *  - C9 汇总通知：有恢复动作项目 trigger 恰一次（清单 + 无需回报）；零动作零触发。
 *  - C11 并发上限：5 可恢复节点同时 rehydrate ≤ RecoveryConcurrency（默认 3）。
 *  - M1/M2 挂载收殓门控：skipStaleReap=true 崩溃残留保持 Running 交 sweep；
 *    默认 false 维持既有 cancelled 收殓（回滚语义）。
 *
 * §4.10 零回归 = 既有 NotifyDispatcherSpec / settleSweep / 僵尸收敛（NodeDeadSession
 * AutoReapSpec）/ LoopNodeSpec / ProjectStartupMountSpec 全绿（独立运行，非本文件）。
 * §4.12 debounce 语义 = 文档级声明（§1.1 诚实语义写入 resume prompt 与设计文档）。
 */
class BootCrashRecoverySpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-boot-crash-recovery"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "general")
  os.write.over(
    tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"general executor","tools":[],"category":"standalone"}""")
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")
  os.makeDir.all(tempRoot / "agents" / "test-agent")
  os.write.over(
    tempRoot / "agents" / "test-agent" / "agent.json",
    """{"name":"test-agent","description":"recovery spec agent","tools":[],"category":"standalone"}""")
  os.write.over(tempRoot / "agents" / "test-agent" / "system.md", "# test-agent\n")
  os.makeDir.all(tempRoot / "sessions")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  // ── 基建（NodeDeadSessionAutoReapSpec / LoopNodeSpec 同款骨架）──────────

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

  /** 捕获型 LLM：按「最后一条用户消息」路由剧本回复（worker/verify/普通节点）。 */
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

  /** 闩门 LLM（C11 并发上限）：请求到达即计数并阻塞在门上，开门后放行。 */
  private class LatchLlm(gate: Deferred[IO, Unit]):
    val inFlight: Ref[IO, Int] = Ref.unsafe[IO, Int](0)
    val peak: Ref[IO, Int] = Ref.unsafe[IO, Int](0)
    val arrivals: Ref[IO, Int] = Ref.unsafe[IO, Int](0)
    def handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(
          req: LlmRequest,
          onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]]
      ): Stream[IO, StreamChunk] =
        Stream
          .eval(for
            _ <- arrivals.updateAndGet(_ + 1)
            cur <- inFlight.updateAndGet(_ + 1)
            _ <- peak.update(p => math.max(p, cur))
            _ <- gate.get
            _ <- inFlight.updateAndGet(_ - 1)
          yield ())
          .flatMap(_ => Stream(StreamChunk.TextDelta("LATCH-DONE"), StreamChunk.Done(None, None)))

  private def registerRecorder(res: SharedResources, system: ActorSystem, sid: String): IO[Ref[IO, List[AgentCommand]]] =
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
    res: SharedResources
  ): IO[ProjectRuntime] =
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
        // noderpt 批 A 段：本 fixture 主题非 node_report 语义 ⇒ 显式关腿 2（生产默认开；
        // 腿 2 默认开行为由 NodeReportReminderSpec 覆盖）。
        reportGateHold = Some(false)
      )
      pd = ProjectDef(name = name, workspace = ws.toString, agentFile = (ws / "AGENTS.md").toString, createdAt = System.currentTimeMillis())
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
      case None    => fail(s"node '$name' must exist")
    }

  private def readEvents(ws: os.Path): IO[List[String]] =
    IO.blocking {
      val f = ws / ".nebflow" / "flow-map-events.jsonl"
      if os.exists(f) then os.read(f).linesIterator.toList else Nil
    }

  /** 播种磁盘 transcript（模拟崩溃前已逐轮落盘的节点会话历史）。 */
  private def seedTranscript(sid: String, msgs: List[Message]): Unit =
    os.write.over(tempRoot / "sessions" / s"$sid.json", msgs.asJson.noSpaces)

  private def msg(role: MessageRole, text: String): Message = Message(role = role, content = Left(text))

  /** 播种崩溃残留 running 节点（status=Running + sessionRef 持久）。 */
  private def seedRunning(
    rt: ProjectRuntime,
    id: String,
    name: String,
    task: String,
    out: List[OutEdge] = List(OutEdge.nebula),
    sessionRef: Option[String] = None,
    sessionRefVerify: Option[String] = None,
    bgWait: Option[String] = None,
    loop: Option[LoopConfig] = None,
    loopRound: Int = 0,
    loopPhase: Option[String] = None,
    merge: Boolean = false,
    in: List[String] = Nil
  ): IO[Unit] =
    rt.store.mutate { s =>
      s.copy(nodes = s.nodes.updated(id, NodeDef(
        id = id, name = name, agent = "general",
        task = Some(task), out = out, in = in, merge = merge,
        status = NodeLifecycle.Running,
        startedAt = Some(System.currentTimeMillis() - 3_600_000),
        createdAt = System.currentTimeMillis() - 3_600_000,
        sessionRef = sessionRef, sessionRefVerify = sessionRefVerify,
        bgWait = bgWait, loop = loop, loopRound = loopRound, loopPhase = loopPhase,
        deliveredTo = Nil)))
    }.void

  private def triggerRecorder: IO[(Ref[IO, List[String]], Option[String => IO[Unit]])] =
    Ref.of[IO, List[String]](Nil).map(r => (r, Some((t: String) => r.update(_ :+ t))))

  // ── C1 主路径 + C4（无 reaped）+ C5（幂等）+ C9（恰一条通知）──────────

  test("C1: crashed running node with transcript rehydrates from checkpoint, completes, delivers exactly once") {
    val ws = tempRoot / "ws-c1"
    os.makeDir.all(ws)
    val system = ActorSystem(s"bcr-c1-${scala.util.Random.nextInt(1000000)}")
    val sid = "node-c1aa11bb"
    val llm = ScriptLlm(_ => "RECOVERED-DONE")
    for
      res <- mkResources(system, llm.handle)
      recorded <- registerRecorder(res, system, "nebula-root")
      rt <- mountProject("bcr-c1", ws, system, res)
      _ = seedTranscript(sid, List(
        msg(MessageRole.User, "原始任务：实现功能 X"),
        msg(MessageRole.Assistant, "中途已完成一半：WIP-HALF-CONTEXT")))
      _ <- seedRunning(rt, "n-c1", "rec-a", "实现功能 X", sessionRef = Some(sid))
      (trig, trigger) <- triggerRecorder
      actions <- ProjectCrashRecovery.recoverProject(rt, trigger = trigger)
      _ <- waitUntil(20.seconds)(byName(rt, "rec-a").map(n => NodeLifecycle.Terminal.contains(n.status)))
      a <- byName(rt, "rec-a")
      reqs <- llm.fullReqs.get
      turns <- llm.lastTurns.get
      imms <- recorded.get.map(_.collect { case m: AgentCommand.ImmediateInput => m })
      events <- readEvents(ws)
      // C5 幂等：恢复完成后再跑 → 零动作零新事件零新通知
      actions2 <- ProjectCrashRecovery.recoverProject(rt, trigger = trigger)
      events2 <- readEvents(ws)
      trigTexts <- trig.get
      persisted <- res.sessionStore.loadMessagesForSession(sid).map(_.exists(_.textContent.contains("RECOVERED-DONE")))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(actions, 1, "exactly one recovery action (rehydrate claim)")
      assertEquals(a.status, NodeLifecycle.Completed, "rehydrated node must complete")
      assertEquals(a.result, Some("RECOVERED-DONE"))
      assert(reqs.exists(_.contains("WIP-HALF-CONTEXT")),
        "LLM request must carry the hydrated transcript (checkpoint resume)")
      assert(turns.exists(t => t.contains("崩溃并已重启") && t.contains("""NodeList(detail="n-c1"""")),
        s"resume prompt must carry crash notice + task pointer, got: ${turns.take(2).mkString("|").take(300)}")
      assertEquals(a.sessionRef, Some(sid), "sessionRef must be preserved (reused session id)")
      assert(persisted, "recovered session transcript must continue appending to the same file (D2)")
      assertEquals(imms.count(_.text.contains("[Node 'rec-a' completed]")), 1, "exactly one Nebula delivery")
      assert(a.nebulaDeliveredAt.isDefined, "nebulaDeliveredAt ledger must be set")
      assert(events.exists(e => e.contains("boot-recovery") && e.contains("n-c1")),
        s"boot-recovery audit event expected, got: ${events.mkString("|").take(300)}")
      assert(!events.exists(e => e.contains("dead-session-reaped")), "claimed node must NOT be reaped by watchdog (C4)")
      assertEquals(actions2, 0, "second sweep pass must find nothing (idempotent boot)")
      assertEquals(events2.count(e => e.contains("boot-recovery") && e.contains("n-c1")), 1,
        "no duplicate boot-recovery events on re-sweep (C5)")
      assertEquals(trigTexts.size, 1, "exactly one summary notification per boot per project (C9)")
      assert(trigTexts.head.contains("[boot-recovery]") && trigTexts.head.contains("无需回报"),
        s"summary must carry manifest + no-report instruction, got: ${trigTexts.headOption.map(_.take(200))}")
  }

  // ── C2 不误碰：等 barrier 的下游只经正常投递链启动 ─────────────────────

  test("C2: wiring downstream waiting on barrier is NOT sweep-started; settles via normal delivery chain") {
    val ws = tempRoot / "ws-c2"
    os.makeDir.all(ws)
    val system = ActorSystem(s"bcr-c2-${scala.util.Random.nextInt(1000000)}")
    val sid = "node-c2aa22cc"
    // 下游 test-agent 的任务文本独特，用于断言其启动输入来自正常投递链（task 全文）
    // 链透传批 P2 适配：分流键由「上游 task 文本」改为「上游 result 段标记」——
    // buildInput 新增的链头含 chainTitle（三级推导：分量 head 成员的 task 首行
    // 预览），C2 夹具里 head=up-a ⇒ 下游输入会含上游 task 文本「实现功能 X」，
    // 旧键会把下游误判为上游。语义等价且更贴断言意图：收到上游 result 段 = 下游。
    val llm = ScriptLlm(last => if last.contains("=== Node up-a ===") then "DOWNSTREAM-OK" else "UPSTREAM-OK")
    for
      res <- mkResources(system, llm.handle)
      _ <- registerRecorder(res, system, "nebula-root")
      rt <- mountProject("bcr-c2", ws, system, res)
      _ = seedTranscript(sid, List(
        msg(MessageRole.User, "实现功能 X"),
        msg(MessageRole.Assistant, "halfway")))
      _ <- seedRunning(rt, "n-c2up", "up-a", "实现功能 X", out = List(OutEdge("n-c2dn")), sessionRef = Some(sid))
      _ <- rt.store.mutate { s => s.copy(nodes = s.nodes.updated("n-c2dn", NodeDef(
        id = "n-c2dn", name = "down-b", agent = "test-agent", task = Some("下游处理"),
        in = List("n-c2up"), out = List(OutEdge.nebula),
        status = NodeLifecycle.Wiring, createdAt = System.currentTimeMillis() - 3_600_000))) }.void
      (trig, trigger) <- triggerRecorder
      _ <- ProjectCrashRecovery.recoverProject(rt, trigger = trigger)
      // 下游最终经正常链完成：up completed → deliverOut → deliveredTo 记账 → barrier 归零 → 启动
      _ <- waitUntil(25.seconds)(byName(rt, "down-b").map(n => NodeLifecycle.Terminal.contains(n.status)))
      up <- byName(rt, "up-a")
      dn <- byName(rt, "down-b")
      events <- readEvents(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(up.status, NodeLifecycle.Completed)
      assertEquals(dn.status, NodeLifecycle.Completed, "downstream must settle through the normal delivery chain")
      assertEquals(dn.deliveredTo, List("n-c2up"), "deliveredTo recorded exactly once")
      assert(!events.exists(e => e.contains("boot-recovery") && e.contains("n-c2dn")),
        "sweep must not start the barrier-waiting downstream (no boot-recovery event for it)")
      assert(dn.result.contains("DOWNSTREAM-OK"), s"downstream completed with its own task, got ${dn.result}")
  }

  // ── C3 (c) 类：transcript 缺失/无 sessionRef → failed + 既有 failed 投递链 ──

  test("C3: lost/corrupt transcript (class c) fails node via existing deliverFailed chain (D5 zero-settlement: plain downstream waits, merge blocks)") {
    val ws = tempRoot / "ws-c3"
    os.makeDir.all(ws)
    val system = ActorSystem(s"bcr-c3-${scala.util.Random.nextInt(1000000)}")
    val llm = ScriptLlm(_ => "SHOULD-NOT-RUN")
    for
      res <- mkResources(system, llm.handle)
      _ <- registerRecorder(res, system, "nebula-root")
      rt <- mountProject("bcr-c3", ws, system, res)
      // A：字段引入前的旧数据（sessionRef=None）；out=普通下游 D
      _ <- seedRunning(rt, "n-c3a", "old-a", "old task", out = List(OutEdge("n-c3d")))
      // B：有 sessionRef 但 transcript 文件缺失；out=merge 下游 M
      _ <- seedRunning(rt, "n-c3b", "lost-b", "lost task", out = List(OutEdge("n-c3m")), sessionRef = Some("node-c3noFile"))
      _ <- rt.store.mutate { s => s.copy(nodes = s.nodes ++ Map(
        "n-c3d" -> NodeDef(id = "n-c3d", name = "collect-d", agent = "test-agent", task = Some("collect work"),
          in = List("n-c3a"), out = List(OutEdge.nebula), status = NodeLifecycle.Wiring,
          createdAt = System.currentTimeMillis() - 3_600_000),
        "n-c3m" -> NodeDef(id = "n-c3m", name = "merge-m", agent = "general", task = Some("merge work"),
          in = List("n-c3b"), out = List(OutEdge.nebula), merge = true, status = NodeLifecycle.Wiring,
          createdAt = System.currentTimeMillis() - 3_600_000))) }.void
      (trig, trigger) <- triggerRecorder
      actions <- ProjectCrashRecovery.recoverProject(rt, trigger = trigger)
      // D5：普通下游停等不终态化——等 boot-recovery 汇总触发（两 (c) 类处置的凭证）
      _ <- waitUntil(20.seconds)(trig.get.map(_.nonEmpty))
      _ <- IO.sleep(500.millis) // 给「假如 collect 仍在异步启动下游」留观察窗口
      a <- byName(rt, "old-a")
      b <- byName(rt, "lost-b")
      d <- byName(rt, "collect-d")
      m <- byName(rt, "merge-m")
      events <- readEvents(ws)
      reqs <- llm.fullReqs.get
      trigTexts <- trig.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(actions, 2, "two class-c actions (both failed)")
      assertEquals(a.status, NodeLifecycle.Failed)
      assert(a.result.exists(_.contains("crash recovery: session transcript lost")),
        s"(c) failure reason must name crash recovery, got: ${a.result}")
      assertEquals(b.status, NodeLifecycle.Failed)
      assert(b.result.exists(_.contains("crash recovery: session transcript lost/corrupt")))
      // D5 翻转（原 collect 断言）：普通下游停等零结算——不启动、无占位键
      assertEquals(d.status, NodeLifecycle.Wiring,
        "plain downstream stays wiring (D5 zero-settlement — placeholder abolished, dispatcher notified)")
      assertEquals(d.deliveredTo, Nil,
        "failed upstream must not write deliveredTo key into downstream (wf1cde §3.2 hole source)")
      assertEquals(m.status, NodeLifecycle.Blocked, "merge downstream must turn blocked on upstream failure")
      assert(events.count(e => e.contains("boot-recovery") && (e.contains("n-c3a") || e.contains("n-c3b"))) == 2,
        "each class-c disposition must leave a boot-recovery audit event")
      assert(!reqs.exists(_.contains("old task")) && !reqs.exists(_.contains("lost task")),
        "class-c nodes must NOT be spawned (no rehydrate)")
      assertEquals(trigTexts.size, 1)
      assert(trigTexts.head.contains("failed"))
  }

  // ── C4 降级兜底：未被认领的 running 残留仍由 watchdog 收敛 ─────────────

  test("C4: unclaimed running residue still converges via dead-session watchdog (degraded fallback intact)") {
    val ws = tempRoot / "ws-c4"
    os.makeDir.all(ws)
    val system = ActorSystem(s"bcr-c4-${scala.util.Random.nextInt(1000000)}")
    val llm = ScriptLlm(_ => "X")
    for
      res <- mkResources(system, llm.handle)
      _ <- registerRecorder(res, system, "nebula-root")
      rt <- mountProject("bcr-c4", ws, system, res)
      // 不跑 sweep（模拟 sweep 整体失败/关闭）：watchdog 必须仍能收敛（R4 兜底）
      _ <- seedRunning(rt, "n-c4", "residue-a", "residue task")
      _ <- rt.engine.settleStaleRunningNodes()
      n <- byName(rt, "residue-a")
      events <- readEvents(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(n.status, NodeLifecycle.Failed, "unclaimed running residue must converge to failed via watchdog")
      assert(events.exists(_.contains("dead-session-reaped")), "watchdog audit event expected")
  }

  // ── C6 bgWait：认领清空 + 死亡告知 ─────────────────────────────────

  test("C6: bgWait node — claim clears bgWait and resume prompt carries background-task death notice") {
    val ws = tempRoot / "ws-c6"
    os.makeDir.all(ws)
    val system = ActorSystem(s"bcr-c6-${scala.util.Random.nextInt(1000000)}")
    val sid = "node-c6dd44ee"
    val llm = ScriptLlm(_ => "BG-RESUMED")
    for
      res <- mkResources(system, llm.handle)
      _ <- registerRecorder(res, system, "nebula-root")
      rt <- mountProject("bcr-c6", ws, system, res)
      _ = seedTranscript(sid, List(msg(MessageRole.User, "bg task task"), msg(MessageRole.Assistant, "spawned bg")))
      _ <- seedRunning(rt, "n-c6", "bgw-a", "bg task task", sessionRef = Some(sid),
        bgWait = Some("1 background task(s): 'compile'"))
      (trig, trigger) <- triggerRecorder
      _ <- ProjectCrashRecovery.recoverProject(rt, trigger = trigger)
      _ <- waitUntil(20.seconds)(byName(rt, "bgw-a").map(n => NodeLifecycle.Terminal.contains(n.status)))
      n <- byName(rt, "bgw-a")
      turns <- llm.lastTurns.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(n.status, NodeLifecycle.Completed)
      assertEquals(n.bgWait, None, "bgWait must be cleared on claim (wait set evaporated with the process)")
      assert(turns.exists(t => t.contains("后台任务已死亡") && t.contains("compile")),
        s"resume prompt must carry the bg-task death notice with the snapshot, got: ${turns.headOption.map(_.take(300))}")
  }

  // ── C7 loop 双会话续接（裁定③，改写 §4.7）──────────────────────────

  test("C7a: loop node crashed in WORKER phase — dual-session resume continues round, verify passes, loopRound preserved") {
    val ws = tempRoot / "ws-c7a"
    os.makeDir.all(ws)
    val system = ActorSystem(s"bcr-c7a-${scala.util.Random.nextInt(1000000)}")
    val workerSid = "node-c7w0011"
    val verifySid = "node-c7v0022"
    val llm = ScriptLlm { last =>
      if last.contains("【LoopNode 验证") then "VERDICT: PASS" else "W2-FINAL"
    }
    for
      res <- mkResources(system, llm.handle)
      _ <- registerRecorder(res, system, "nebula-root")
      rt <- mountProject("bcr-c7a", ws, system, res)
      _ = seedTranscript(workerSid, List(
        msg(MessageRole.User, "原始任务：产出报告"),
        msg(MessageRole.Assistant, "ROUND1-OUT"),
        msg(MessageRole.User, "【LoopNode 返工 · 第 2 轮】…"),
        msg(MessageRole.Assistant, "WIP-ROUND2-PARTIAL")))
      _ = seedTranscript(verifySid, List(
        msg(MessageRole.User, "【LoopNode 验证 · 第 1 轮】…"),
        msg(MessageRole.Assistant, "VERDICT: PASS")))
      _ <- seedRunning(rt, "n-c7a", "loop-a", "产出报告", sessionRef = Some(workerSid),
        sessionRefVerify = Some(verifySid),
        loop = Some(LoopConfig(maxRounds = 3)), loopRound = 2, loopPhase = Some(NodeEngine.LoopPhaseWorker))
      (trig, trigger) <- triggerRecorder
      _ <- ProjectCrashRecovery.recoverProject(rt, trigger = trigger)
      _ <- waitUntil(25.seconds)(byName(rt, "loop-a").map(n => NodeLifecycle.Terminal.contains(n.status)))
      n <- byName(rt, "loop-a")
      reqs <- llm.fullReqs.get
      turns <- llm.lastTurns.get
      workerPersisted <- res.sessionStore.loadMessagesForSession(workerSid).map(_.exists(_.textContent.contains("W2-FINAL")))
      events <- readEvents(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(n.status, NodeLifecycle.Completed, "resumed loop must reach terminal state")
      assertEquals(n.result, Some("W2-FINAL"), "PASS delivers worker final output")
      assertEquals(n.loopRound, 2, "loopRound semantics preserved across kill -9 (resumed round = crashed round)")
      assertEquals(n.sessionRef, Some(workerSid))
      assertEquals(n.sessionRefVerify, Some(verifySid), "verify session ref preserved")
      assert(reqs.exists(_.contains("WIP-ROUND2-PARTIAL")),
        "worker LLM request must carry hydrated worker transcript")
      assert(turns.exists(t => t.contains("worker 阶段") && t.contains("崩溃并已重启")),
        "worker must receive the loop resume prompt (not fresh/rework template)")
      assert(workerPersisted, "worker session transcript continues in the same file (dual-session continuity)")
      assert(events.exists(e => e.contains("boot-recovery") && e.contains("loop dual-session resume")),
        "claim event must record the loop dual-session resume classification")
  }

  test("C7b: loop node crashed in VERIFY phase — rebuilt verify input drives FAIL→rework→PASS iteration (round increments correctly)") {
    val ws = tempRoot / "ws-c7b"
    os.makeDir.all(ws)
    val system = ActorSystem(s"bcr-c7b-${scala.util.Random.nextInt(1000000)}")
    val workerSid = "node-c7w1133"
    val verifySid = "node-c7v2244"
    // verify 消费两次：第 2 轮 FAIL → 打回 → 第 3 轮 PASS；worker 收返工模板回 W3-FINAL
    //（respond 是纯函数 String=>String——用原子计数器保序，线程安全）
    val verifyReplyCounter = new java.util.concurrent.atomic.AtomicInteger(0)
    val verifyReplies = List(
      "VERDICT: FAIL\n{\"issues\":[\"round-2 output not final\"],\"requirements\":\"must be final\"}",
      "VERDICT: PASS")
    val llm = ScriptLlm { last =>
      if last.contains("【LoopNode 验证") then
        val cur = verifyReplyCounter.incrementAndGet()
        verifyReplies(math.min(cur - 1, verifyReplies.size - 1))
      else if last.contains("【LoopNode 返工") then "W3-FINAL"
      else "W-GEN"
    }
    for
      res <- mkResources(system, llm.handle)
      _ <- registerRecorder(res, system, "nebula-root")
      rt <- mountProject("bcr-c7b", ws, system, res)
      _ = seedTranscript(workerSid, List(
        msg(MessageRole.User, "原始任务：产出报告"),
        msg(MessageRole.Assistant, "ROUND1-OUT"),
        msg(MessageRole.Assistant, "W2-OUTPUT")))
      _ = seedTranscript(verifySid, List(
        msg(MessageRole.User, "【LoopNode 验证 · 第 1 轮】…"),
        msg(MessageRole.Assistant, "VERDICT: PASS")))
      _ <- seedRunning(rt, "n-c7b", "loop-b", "产出报告", sessionRef = Some(workerSid),
        sessionRefVerify = Some(verifySid),
        loop = Some(LoopConfig(maxRounds = 4)), loopRound = 2, loopPhase = Some(NodeEngine.LoopPhaseVerify))
      (trig, trigger) <- triggerRecorder
      _ <- ProjectCrashRecovery.recoverProject(rt, trigger = trigger)
      _ <- waitUntil(25.seconds)(byName(rt, "loop-b").map(n => NodeLifecycle.Terminal.contains(n.status)))
      n <- byName(rt, "loop-b")
      reqs <- llm.fullReqs.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(n.status, NodeLifecycle.Completed, "resumed loop must complete through FAIL→rework→PASS")
      assertEquals(n.result, Some("W3-FINAL"), "final PASS delivers the reworked round-3 output")
      assertEquals(n.loopRound, 3, "iteration continues across the crash: FAIL at round 2 → rework round 3")
      assert(n.loopLastVerdict.exists(_.contains("round-2 output not final")),
        s"last FAIL verdict must be persisted, got: ${n.loopLastVerdict}")
      // 重建的 verify 输入必须带 worker 末轮产出（来自 worker transcript）+ 崩溃续接标注
      assert(reqs.exists(r => r.contains("崩溃并已重启") && r.contains("W2-OUTPUT") && r.contains("verify 阶段")),
        "verify resume must rebuild the round input from the worker transcript with the crash annotation")
      assert(reqs.exists(_.contains("【LoopNode 返工 · 第 3 轮】")),
        "rework round 3 must be injected into the rehydrated worker session")
  }

  test("C7c: loop node without sessionRef fails as class c (loopRound persisted for audit)") {
    val ws = tempRoot / "ws-c7c"
    os.makeDir.all(ws)
    val system = ActorSystem(s"bcr-c7c-${scala.util.Random.nextInt(1000000)}")
    val llm = ScriptLlm(_ => "X")
    for
      res <- mkResources(system, llm.handle)
      _ <- registerRecorder(res, system, "nebula-root")
      rt <- mountProject("bcr-c7c", ws, system, res)
      _ <- seedRunning(rt, "n-c7c", "loop-c", "产出报告",
        loop = Some(LoopConfig(maxRounds = 3)), loopRound = 2, loopPhase = Some(NodeEngine.LoopPhaseWorker))
      (trig, trigger) <- triggerRecorder
      _ <- ProjectCrashRecovery.recoverProject(rt, trigger = trigger)
      _ <- waitUntil(10.seconds)(byName(rt, "loop-c").map(n => NodeLifecycle.Terminal.contains(n.status)))
      n <- byName(rt, "loop-c")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(n.status, NodeLifecycle.Failed)
      assert(n.result.exists(_.contains("crash recovery: session transcript lost")),
        s"loop class-c reason must name the lost transcript, got: ${n.result}")
      assert(n.result.exists(_.contains("loopRound=2")), "loopRound must be persisted in the failure reason for audit")
      assertEquals(n.loopRound, 2)
  }

  // ── C8 sessionRef 正常路径落库 ─────────────────────────────────────

  test("C8: freshly started node persists sessionRef in flow-map pointing at an existing transcript file") {
    val ws = tempRoot / "ws-c8"
    os.makeDir.all(ws)
    val system = ActorSystem(s"bcr-c8-${scala.util.Random.nextInt(1000000)}")
    val llm = ScriptLlm(_ => "FRESH-DONE")
    for
      res <- mkResources(system, llm.handle)
      _ <- registerRecorder(res, system, "nebula-root")
      rt <- mountProject("bcr-c8", ws, system, res)
      _ <- {
        val ctx = ToolContext(
          projectRoot = ws.toString, sessionId = Some("spec-sid"), rootSessionId = Some("nebula-root"),
          sharedResources = Some(res), actorSystem = Some(system))
        NodeEditTool.call(io.circe.Json.obj(
          "project" -> io.circe.Json.fromString("bcr-c8"),
          "nodename" -> io.circe.Json.fromString("fresh-a"),
          "description" -> io.circe.Json.fromString("sessionRef path spec node"),
          "task" -> io.circe.Json.fromString("fresh task"),
          "out" -> io.circe.Json.fromString("Nebula")).asObject.get, ctx).void
      }
      _ <- waitUntil(20.seconds)(byName(rt, "fresh-a").map(n => NodeLifecycle.Terminal.contains(n.status)))
      n <- byName(rt, "fresh-a")
      _ <- waitUntil(15.seconds)(IO.blocking(os.exists(tempRoot / "sessions" / s"${n.sessionRef.get}.json")))
      diskJson <- IO.blocking(os.read(ws / ".nebflow" / "flow-map.json"))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(n.sessionRef.isDefined, "freshly started node must persist sessionRef (D1)")
      assert(diskJson.contains("\"sessionRef\""), "on-disk flow-map.json must carry the sessionRef key")
  }

  // ── C9 零动作零通知（与 C1 的恰一条互补）────────────────────────────

  test("C9: project with no crashed nodes triggers nothing (zero actions, zero notify)") {
    val ws = tempRoot / "ws-c9"
    os.makeDir.all(ws)
    val system = ActorSystem(s"bcr-c9-${scala.util.Random.nextInt(1000000)}")
    val llm = ScriptLlm(_ => "X")
    for
      res <- mkResources(system, llm.handle)
      _ <- registerRecorder(res, system, "nebula-root")
      rt <- mountProject("bcr-c9", ws, system, res)
      _ <- rt.store.mutate { s => s.copy(nodes = s.nodes.updated("n-c9", NodeDef(
        id = "n-c9", name = "pending-a", agent = "general", task = Some("pending task"),
        in = List("n-never"), out = List(OutEdge.nebula),
        status = NodeLifecycle.Wiring, createdAt = System.currentTimeMillis()))) }.void
      (trig, trigger) <- triggerRecorder
      actions <- ProjectCrashRecovery.recoverProject(rt, trigger = trigger)
      events <- readEvents(ws)
      trigTexts <- trig.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(actions, 0, "no running residue → zero actions")
      assert(events.isEmpty, "zero sweep events (no silent self-heal noise)")
      assert(trigTexts.isEmpty, "no recovery actions → no dispatcher trigger")
  }

  // ── C11 并发上限：同时 rehydrate ≤ RecoveryConcurrency ───────────────

  test("C11: five recoverable nodes rehydrate with concurrency capped at 3 (semaphore guard)") {
    val ws = tempRoot / "ws-c11"
    os.makeDir.all(ws)
    val system = ActorSystem(s"bcr-c11-${scala.util.Random.nextInt(1000000)}")
    for
      gate <- Deferred[IO, Unit]
      latch = LatchLlm(gate)
      res <- mkResources(system, latch.handle)
      _ <- registerRecorder(res, system, "nebula-root")
      rt <- mountProject("bcr-c11", ws, system, res)
      _ <- (1 to 5).toList.traverse_ { i =>
        val sid = s"node-c11c0$i"
        IO(seedTranscript(sid, List(msg(MessageRole.User, s"task $i"), msg(MessageRole.Assistant, s"wip $i")))) *>
          seedRunning(rt, s"n-c11-$i", s"par-$i", s"task $i", sessionRef = Some(sid))
      }
      (trig, trigger) <- triggerRecorder
      _ <- ProjectCrashRecovery.recoverProject(rt, trigger = trigger)
      // 3 个槽位占满：第 4/5 个必须还在排队（800ms 观察窗内并发不增）
      _ <- waitUntil(15.seconds)(latch.inFlight.get.map(_ == 3))
      _ <- IO.sleep(800.millis)
      steady <- latch.inFlight.get
      peakBeforeGate <- latch.peak.get
      _ <- gate.complete(())
      _ <- waitUntil(30.seconds)(rt.store.snapshot.map(_.nodes.values.forall(n => NodeLifecycle.Terminal.contains(n.status))))
      all <- rt.store.snapshot.map(_.nodes.values.toList)
      arrivals <- latch.arrivals.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(steady, 3, "concurrent rehydrates must hold at exactly RecoveryConcurrency=3 while latched")
      assertEquals(peakBeforeGate, 3, "peak concurrency must never exceed the cap")
      assert(arrivals >= 5, "all five nodes eventually rehydrate through the semaphore")
      assert(all.nonEmpty && all.forall(n => n.status == NodeLifecycle.Completed),
        s"all recovered nodes must complete, got: ${all.map(n => s"${n.name}:${n.status}").mkString(",")}")
  }

  // ── M1/M2 挂载收殓门控（skipStaleReap）─────────────────────────────

  test("M1: boot mount with skipStaleReap leaves crashed running nodes for the sweep (not cancelled)") {
    val ws = tempRoot / "ws-m1"
    os.makeDir.all(ws)
    val system = ActorSystem(s"bcr-m1-${scala.util.Random.nextInt(1000000)}")
    val llm = ScriptLlm(_ => "M1-DONE")
    for
      res <- mkResources(system, llm.handle)
      _ <- registerRecorder(res, system, "nebula-root")
      // 先播种 flow-map（磁盘），再挂载——模拟重启后磁盘状态
      seedStore <- FlowMapStore.open("bcr-m1", ws.toString)
      _ <- seedStore.mutate { s => s.copy(nodes = s.nodes.updated("n-m1", NodeDef(
        id = "n-m1", name = "crash-a", agent = "general", task = Some("m1 task"),
        out = List(OutEdge.nebula), status = NodeLifecycle.Running,
        startedAt = Some(System.currentTimeMillis() - 3_600_000),
        createdAt = System.currentTimeMillis() - 3_600_000))) }.void
      rt <- ProjectRuntimeRegistry.mount(
        ProjectDef(name = "bcr-m1", workspace = ws.toString, agentFile = (ws / "AGENTS.md").toString,
          createdAt = System.currentTimeMillis()),
        system, res, None, "nebula-root", skipStaleReap = true)
      afterMount <- rt.store.snapshot.map(_.nodes.get("n-m1"))
      (trig, trigger) <- triggerRecorder
      actions <- ProjectCrashRecovery.recoverProject(rt, trigger = trigger)
      _ <- waitUntil(20.seconds)(byName(rt, "crash-a").map(n => NodeLifecycle.Terminal.contains(n.status)))
      n <- byName(rt, "crash-a")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(afterMount.exists(_.status == NodeLifecycle.Running),
        "skipStaleReap mount must NOT cancel crashed running nodes (left for the sweep)")
      assertEquals(actions, 1, "sweep claims the node the mount spared")
      assertEquals(n.status, NodeLifecycle.Failed, "no sessionRef → class c failed (NOT cancelled by mount reap)")
  }

  test("M2: default mount (skipStaleReap=false) keeps the legacy zombie reap (cancelled) — rollback semantics") {
    val ws = tempRoot / "ws-m2"
    os.makeDir.all(ws)
    val system = ActorSystem(s"bcr-m2-${scala.util.Random.nextInt(1000000)}")
    val llm = ScriptLlm(_ => "X")
    for
      res <- mkResources(system, llm.handle)
      _ <- registerRecorder(res, system, "nebula-root")
      seedStore <- FlowMapStore.open("bcr-m2", ws.toString)
      _ <- seedStore.mutate { s => s.copy(nodes = s.nodes.updated("n-m2", NodeDef(
        id = "n-m2", name = "crash-b", agent = "general", task = Some("m2 task"),
        out = List(OutEdge.nebula), status = NodeLifecycle.Running,
        startedAt = Some(System.currentTimeMillis() - 3_600_000),
        createdAt = System.currentTimeMillis() - 3_600_000))) }.void
      rt <- ProjectRuntimeRegistry.mount(
        ProjectDef(name = "bcr-m2", workspace = ws.toString, agentFile = (ws / "AGENTS.md").toString,
          createdAt = System.currentTimeMillis()),
        system, res, None, "nebula-root") // 默认 skipStaleReap=false（运行时挂载/开关关闭形态）
      n <- rt.store.snapshot.map(_.nodes.get("n-m2"))
      events <- readEvents(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(n.exists(_.status == NodeLifecycle.Cancelled),
        "default mount keeps the legacy zombie reap → cancelled (pre-batch behavior)")
      assert(events.exists(_.contains("reaped")), "legacy reap audit event expected")
  }

end BootCrashRecoverySpec
