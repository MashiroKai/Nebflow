package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, Behaviors}
import nebflow.agent.{AgentCommand, AgentEvent, AgentKind, AgentLibrary, AgentRecord, SharedResources}
import nebflow.core.PathUtil
import nebflow.core.processor.TaskStuckWatcher
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{BgTaskRegistry, FileLockManager, NodeEditTool, ToolContext}
import nebflow.gateway.{RateLimiter, SessionStore, WsHub}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * 僵尸 running 收敛回归（实施-僵尸running收敛修复 批 2026-09-06）：
 *
 * 引擎对「session 死但 node 留 running」的收殓存在两层漏洞——①runWithAgent 在飞
 * 登记表（running/nodeSessions/agentRegistry）只能由该 fiber 自己在 race 落定后
 * 清理，fiber 崩溃/被 cancel/悬死时三表永久泄漏（假活 canary → 误杀防护被击穿，
 * 所有收殓路径误拒）；②TtlTick 周期没有 running 死会话对账——只有挂载一次性对账 +
 * 分发器人工 abandon，无自动收敛路径。
 *
 * 本批修复（作者 2026-09-06 2 0:1 1 设计约束）：
 *  - 收敛目标取 **failed**（非 cancelled——cancelled 不投递下游，barrier 永挂）。
 *  - 触发条件「可证明无活会话 且 无在途后台任务」：对 status=Running 节点，session
 *    死（nodeSessions 无映射且无在飞 fiber / agentRegistry 无活记录且过 spawn 窗口）
 *    且 BgTaskRegistry.waitingFor 为空才收敛——**不**把「设计内等待后台任务完成」的
 *    节点（按原设计保持 running）误杀成 failed（作者红线）。
 *  - 生效点：ProjectActor.TtlTick 周期驱动 settleStaleRunningNodes + runWithAgent
 *    guarantee 三表对称清理（防泄漏假活 canary）。
 *
 * 用例（独立 spec，避免污染 flaky 的 NodeSessionDeathFinalizeSpec 复验区间）：
 *  🔴 **killruling 批（2026-09-23 作者裁定 #19）改判**：本 spec 主题从「自动收敛 failed」
 *  降档为「**只提醒**（节点留 Running）」——下列用例的期望值已同批更新（原口径留档
 *  在每例注释里）。**降档前的代价已显式登记**：不再有 failed ⇒ 无分发器 failed 通知、
 *  无「reactivate 修复重跑」的失败驱动；可见性改由 `node-session-dead-reminder` 事件 +
 *  barrier 告警腿承载。**顺序义务**：提醒覆盖腿（本 spec Z1 的 reminder 断言）先于
 *  降档落地——撤掉该腿只做裸降档 ⇒ Z1 的 reminder 断言转红（变异复红判据）。
 *  - Z1 僵尸**提醒**：播种 status=Running 且无活会话/fiber → settle → **仍 Running**
 *    + `dead-session-reaped`（文案改）+ `node-session-dead-reminder` 双审计、**零投递**。
 *  - Z2 下游停等可见：上游 A 死会话**保持 Running**（既未结算也未终态）→ 下游 B
 *    停等 pending/wiring 保持可见，处置靠 reminder 事件 + barrier 告警腿。
 *  - Z3 不误杀活会话：真实 spawn + 挂死 LLM → settle → 节点保持 Running（registry
 *    有活记录）。
 *  - Z4 不动等待后台任务：死会话但有在途 bg 任务（waitingFor 非空）→ settle → 节点
 *    保持 Running（作者「等待后台任务」红线）。
 *  - Z5 显示标注（作者首要缺口）：节点持留等待后台任务时 NodePayload 带 bgWait
 *    条件字段，全部清空后不带——前端可辨「设计内等待」vs「真僵尸」。
 */
class NodeDeadSessionAutoReapSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-node-dead-session"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "test-agent")
  os.write.over(
    tempRoot / "agents" / "test-agent" / "agent.json",
    """{"name":"test-agent","description":"dead-session reap regression agent","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "test-agent" / "system.md", "# test-agent\n")
  os.makeDir.all(tempRoot / "agents" / "general")
  os.write.over(tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"general executor","tools":[],"category":"standalone"}""")
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  /** 后台任务模拟 stub LLM（NodeBgCompletionGateSpec 同款）：首轮请求时可在
    * BgTaskRegistry 登记一个等待型任务（模拟 agent run_in_background）；registerTask
    * = false 时不登记（节点立即完成）。 */
  private class BgStubLlm(registerTask: Boolean = true):
    val jobIds: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)
    val nodeSessions: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)
    val turnCount: Ref[IO, Int] = Ref.unsafe[IO, Int](0)
    @volatile var res: SharedResources = null
    def handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(
          req: LlmRequest,
          onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
      ): Stream[IO, StreamChunk] =
        Stream.eval {
          for
            turn <- turnCount.updateAndGet(_ + 1)
            _ <-
              if turn == 1 && registerTask then
                Option(res) match
                  case None => IO.raiseError(new RuntimeException("spec res not injected"))
                  case Some(r) =>
                    r.agentRegistry.get.flatMap { reg =>
                      reg.values.find(rec => rec.kind == AgentKind.Flow && rec.sessionId.startsWith("node-")) match
                        case Some(rec) =>
                          val jobId = s"dead-reap-spec-${java.util.UUID.randomUUID().toString.take(8)}"
                          BgTaskRegistry.register(jobId, rec.sessionId, "spec bg task", "local", "nebula-root") *>
                            jobIds.update(_ :+ jobId) *> nodeSessions.update(_ :+ rec.sessionId)
                        case None => IO.raiseError(new RuntimeException("node session record not found at first LLM request"))
                    }
              else IO.unit
          yield turn
        }.flatMap { turn =>
          val text = req.messages.map(_.textContent).mkString("\n")
          val reply = if turn == 1 then text.linesIterator.nextOption().getOrElse("").take(200) else "bg-noted"
          Stream(StreamChunk.TextDelta(reply), StreamChunk.Done(None, None))
        }

  /** 挂死 LLM：turn 永不完成（模拟活会话窗口内一直运行——registry 有活记录）。 */
  private def hangingLlm = new nebflow.shared.LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
    def sendStream(req: LlmRequest, onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None) =
      fs2.Stream.eval(IO.never)

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
      actorSystem = null,
      voiceMutedRef = voiceMuted
    )

  private def registerRecorder(res: SharedResources, system: ActorSystem, sid: String): IO[Ref[IO, List[AgentCommand]]] =
    for
      recorded <- Ref.of[IO, List[AgentCommand]](Nil)
      ref <- system.spawn(recorderBehavior(recorded), s"rec-${scala.util.Random.nextInt(100000)}")
      _ <- res.agentRegistry.update(_ + (sid -> AgentRecord(sid, ref, AgentKind.Root, sid)))
    yield recorded

  private def recorderBehavior(recorded: Ref[IO, List[AgentCommand]]): nebflow.actor.Behavior[AgentCommand] =
    lazy val b: nebflow.actor.Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand](msg => recorded.update(_ :+ msg).as(b))
    b

  private def recordedImmediate(recorded: Ref[IO, List[AgentCommand]]): IO[List[AgentCommand.ImmediateInput]] =
    recorded.get.map(_.collect { case m: AgentCommand.ImmediateInput => m })

  private def mountProject(
    name: String,
    ws: os.Path,
    system: ActorSystem,
    res: SharedResources,
    bgWaitCapMs: Long = 3_600_000L
  ): IO[ProjectRuntime] =
    for
      store <- FlowMapStore.open(name, ws.toString)
      engine = new NodeEngine(
        store,
        system,
        res,
        wsSendFn = (_: Json) => IO.unit,
        workspace = ws.toString,
        rootSessionId = "nebula-root",
        projectName = name,
        emitEvent = (_, _, _) => IO.unit,
        bgWaitCapMs = bgWaitCapMs,
        // noderpt 批 A 段：本 fixture 主题非 node_report 语义 ⇒ 显式关腿 2（生产默认开；
        // 腿 2 默认开行为由 NodeReportReminderSpec 覆盖）；腿 1 显式置回旧行为作对照
        // （本 spec 的 Z4「等待后台任务不动」断言依赖腿 1 的 hold 语义）。
        bgGateCompletionHold = Some(true),
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

  private def nodeInput(project: String, nodename: String, extra: (String, Json)*): Json =
    Json.obj(("project" -> Json.fromString(project)) :: ("nodename" -> Json.fromString(nodename)) :: ("plugins" -> Json.arr()) :: extra.toList*)

  private def createNode(project: String, ws: os.Path, name: String, task: String,
      res: SharedResources = null, system: ActorSystem = null): IO[Unit] =
    val ctx = ToolContext(
      projectRoot = ws.toString,
      sessionId = Some("spec-sid"),
      rootSessionId = Some("nebula-root"),
      sharedResources = Some(res),
      actorSystem = Some(system)
    )
    val extras = List(
      "description" -> Json.fromString("dead-session reap spec node"),
      "task" -> Json.fromString(task),
      "out" -> Json.fromString("Nebula")
    )
    NodeEditTool
      .call(nodeInput(project, name, extras*).asObject.get, ctx)
      .map(_.left.map(_.message))
      .flatMap {
        case Left(err) => IO.raiseError(new AssertionError(s"NodeEdit failed: $err"))
        case Right(_)  => IO.unit
      }

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  // ── Z1 僵尸**提醒**（killruling 批 #19 降档：不判 failed、节点留 Running + 双事件）──

  test("Z1: dead-session running node stays RUNNING with reminder + audit (killruling #19 downgrade)") {
    val ws = tempRoot / "ws-z1"
    os.makeDir.all(ws)
    val system = ActorSystem(s"drs-z1-${scala.util.Random.nextInt(100000)}")
    val llm = BgStubLlm(registerTask = false)
    for
      res <- mkResources(system, tempRoot, llm.handle)
      _ <- IO(llm.res = res)
      recorded <- registerRecorder(res, system, "nebula-root")
      rt <- mountProject("drs-z1", ws, system, res)
      _ <- rt.store.mutate { s =>
        s.copy(nodes = s.nodes + ("n-z1" -> NodeDef(
          id = "n-z1", name = "zombie-a", agent = "general",
          task = Some("long running task"), out = List(OutEdge.nebula),
          status = NodeLifecycle.Running,
          startedAt = Some(System.currentTimeMillis() - 3_600_000),
          createdAt = System.currentTimeMillis() - 3_600_000))) }.void
      _ <- rt.engine.settleStaleRunningNodes()
      z <- byName(rt, "zombie-a")
      // 确定性同步：改判后**无投递**（不判 failed）——同步点改为等审计事件落盘
      //（等副作用本身；killruling 批 #19：`dead-session-reaped` 文案改 + 新增
      // `node-session-dead-reminder`）。
      _ <- waitUntil(15.seconds)(readEvents(ws).map(_.exists(_.contains("\"node-session-dead-reminder\""))))
      _ <- IO.sleep(300.millis) // 给「假如仍有 failed 投递」留观察窗口（护栏）
      imms <- recordedImmediate(recorded)
      events <- readEvents(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // 🔴 改后断言（原 5 条 :276-284 ⇒ 改后 5 条，断言数不减；killruling 批 #19 降档）
      assertEquals(z.status, NodeLifecycle.Running,
        "dead-session running node must KEEP Running after the ruling — no auto-fail (killruling ruling #19)")
      assert(z.result.isEmpty, s"no failure result may be written any more: ${z.result}")
      assert(z.ttlExpireAt.isEmpty, "no display TTL must be written (nothing auto-converges)")
      assert(!imms.exists(_.text.contains("[Node 'zombie-a' failed]")),
        s"no failed delivery may reach the root session any more, got: ${imms.map(_.text).mkString("|")}")
      assert(events.exists(_.contains("\"dead-session-reaped\"")),
        s"dead-session-reaped audit event expected (wording updated), got: ${events.mkString("|").take(300)}")
      assert(events.exists(_.contains("\"node-session-dead-reminder\"")),
        s"node-session-dead-reminder audit event expected (the coverage leg added before the downgrade), got: ${events.mkString("|").take(300)}")
  }

  // ── Z2 下游停等可见（D5 零结算，原 collect settlement 翻转）────────────

  test("Z2: downstream stays waiting (visible) when the upstream dead-session node is kept Running (killruling #19; no settlement, no delivery)") {
    val ws = tempRoot / "ws-z2"
    os.makeDir.all(ws)
    val system = ActorSystem(s"drs-z2-${scala.util.Random.nextInt(100000)}")
    val llm = BgStubLlm(registerTask = false)
    for
      res <- mkResources(system, tempRoot, llm.handle)
      _ <- IO(llm.res = res)
      recorded <- registerRecorder(res, system, "nebula-root")
      rt <- mountProject("drs-z2", ws, system, res)
      _ <- rt.store.mutate { s =>
        s.copy(nodes = s.nodes ++ Map(
          "n-up" -> NodeDef(id = "n-up", name = "up-a", agent = "general",
            task = Some("long task"), out = List(OutEdge("n-dn")), status = NodeLifecycle.Running,
            startedAt = Some(System.currentTimeMillis() - 3_600_000),
            createdAt = System.currentTimeMillis() - 3_600_000),
          "n-dn" -> NodeDef(id = "n-dn", name = "down-b", agent = "test-agent",
            task = Some("process downstream"), out = List(OutEdge.nebula), in = List("n-up"),
            status = NodeLifecycle.Wiring, createdAt = System.currentTimeMillis() - 3_600_000))) }.void
      _ <- rt.engine.settleStaleRunningNodes()
      // 改判后上游**保持 Running**（不再 failed）⇒ 无 D5 零结算，下游按「有 running
      // 上游」正常停等（比 failed 形态**更强**成立：上游既没结算也没终态）。
      _ <- waitUntil(20.seconds)(byName(rt, "up-a").map(_.status == NodeLifecycle.Running))
      _ <- IO.sleep(500.millis) // 给「假如 collect 仍在异步启动 B」留观察窗口
      up <- byName(rt, "up-a")
      dn <- byName(rt, "down-b")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(up.status, NodeLifecycle.Running,
        "dead-session upstream must be KEPT Running after the ruling (no auto-fail)")
      // 下游停等可见（合法等待，比 failed 形态更强）——处置靠 node-session-dead-reminder
      // 事件 + barrier 告警腿（killruling 批 #19；此前靠 failed 通知附等待者清单）。
      assert(dn.status == NodeLifecycle.Wiring || dn.status == NodeLifecycle.Pending,
        s"downstream must stay waiting and visible, got ${dn.status}")
      assertEquals(dn.deliveredTo, Nil,
        "no settlement may write deliveredTo into downstream (the upstream never terminates)")
      assertEquals(dn.result, None, "downstream never started (no session run)")
  }

  // ── Z3 不误杀活会话 ──────────────────────────────────────────

  test("Z3: LIVE running node (registry has live record) is NOT converged by the watchdog") {
    val ws = tempRoot / "ws-z3"
    os.makeDir.all(ws)
    val system = ActorSystem(s"drs-z3-${scala.util.Random.nextInt(100000)}")
    val llm = hangingLlm
    for
      res <- mkResources(system, tempRoot, llm)
      recorded <- registerRecorder(res, system, "nebula-root")
      rt <- mountProject("drs-z3", ws, system, res)
      _ <- createNode("drs-z3", ws, "live-a", "live task", res = res, system = system)
      _ <- waitUntil(15.seconds)(byName(rt, "live-a").map(_.status == NodeLifecycle.Running))
      // 等会话在 agentRegistry 登记（spawn 窗口后——registry 有活记录 = 活会话）
      _ <- waitUntil(15.seconds)(res.agentRegistry.get.map(_.values.exists(r => r.kind == AgentKind.Flow && r.sessionId.startsWith("node-"))))
      _ <- rt.engine.settleStaleRunningNodes()
      live <- byName(rt, "live-a")
      reg <- res.agentRegistry.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(live.status, NodeLifecycle.Running, "live running must NOT be converged")
      assert(reg.values.exists(r => r.kind == AgentKind.Flow && r.sessionId.startsWith("node-")),
        "live agent record must survive")
      assert(live.result.isEmpty, "live node must keep its result empty (not failed)")
  }

  // ── Z4 不动等待后台任务（作者红线）─────────────────────────────

  test("Z4: dead-session with in-flight bg task is NOT converged (design-intended wait, author red line)") {
    val ws = tempRoot / "ws-z4"
    os.makeDir.all(ws)
    val system = ActorSystem(s"drs-z4-${scala.util.Random.nextInt(100000)}")
    val llm = BgStubLlm(registerTask = false)
    val sid = "node-dead-reap-bgw"
    for
      res <- mkResources(system, tempRoot, llm.handle)
      _ <- IO(llm.res = res)
      recorded <- registerRecorder(res, system, "nebula-root")
      rt <- mountProject("drs-z4", ws, system, res)
      _ <- rt.store.mutate { s =>
        s.copy(nodes = s.nodes + ("n-z4" -> NodeDef(
          id = "n-z4", name = "wait-bg", agent = "general",
          task = Some("long task"), out = List(OutEdge.nebula), status = NodeLifecycle.Running,
          startedAt = Some(System.currentTimeMillis() - 3_600_000),
          createdAt = System.currentTimeMillis() - 3_600_000))) }.void
      // 构造「死会话但有在途等待型后台任务」：nodeSessions 映射 + 在册 bg 任务
      _ <- rt.engine.nodeSessions.update(_ + ("n-z4" -> sid))
      _ <- BgTaskRegistry.register("dead-reap-bg-job", sid, "compile", "local", "nebula-root")
      _ <- (rt.engine.settleStaleRunningNodes() *> byName(rt, "wait-bg"))
        .guarantee(BgTaskRegistry.unregister("dead-reap-bg-job"))
      z4 <- byName(rt, "wait-bg")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(z4.status, NodeLifecycle.Running,
        "design-intended wait for an in-flight bg task must NOT be converged (author red line)")
  }

  // ── Z5 显示标注：bgWait 条件字段在持留时带、清空后不带（作者首要缺口）──

  test("Z5: node payload carries bgWait field while holding for bg tasks, drops it after release") {
    val ws = tempRoot / "ws-z5"
    os.makeDir.all(ws)
    val system = ActorSystem(s"drs-z5-${scala.util.Random.nextInt(100000)}")
    val llm = BgStubLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      _ <- IO(llm.res = res)
      recorded <- registerRecorder(res, system, "nebula-root")
      rt <- mountProject("drs-z5", ws, system, res)
      _ <- createNode("drs-z5", ws, "bgw-a", "result-BGW", res = res, system = system)
      _ <- waitUntil(20.seconds)(res.agentRegistry.get.map(_.values.exists(r => r.kind == AgentKind.Flow && r.sessionId.startsWith("node-") && r.status == nebflow.agent.AgentStatus.Idle)))
      // 等节点进入 hold（bg-wait）：节点保持 Running 且 payload 带 bgWait
      _ <- waitUntil(20.seconds)(byName(rt, "bgw-a").map(n => n.status == NodeLifecycle.Running && n.bgWait.isDefined))
      holding <- byName(rt, "bgw-a")
      jobs <- llm.jobIds.get
      // 后台任务完成：unregister + ExternalEvent → 唤醒轮 → 放行
      _ <- jobs.traverse_(BgTaskRegistry.unregister)
      agentRef <- res.agentRegistry.get.map(_.values.find(r => r.kind == AgentKind.Flow && r.sessionId.startsWith("node-"))
        .map(_.ref).getOrElse(fail("node agent ref not found at release")))
      _ <- (agentRef ! AgentCommand.ExternalEvent(
        source = "background-task", eventType = "completed",
        payload = "[Background task completed] \"spec bg task\":\nspec-output",
        metadata = io.circe.JsonObject("description" -> "spec bg task".asJson))).void
      _ <- waitUntil(20.seconds)(byName(rt, "bgw-a").map(n => NodeLifecycle.Terminal.contains(n.status)))
      done <- byName(rt, "bgw-a")
      _ <- jobs.traverse_(BgTaskRegistry.unregister).attempt.void
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(holding.bgWait.isDefined, s"payload must carry bgWait while holding, got: ${holding.bgWait}")
      assert(holding.status == NodeLifecycle.Running, "holding node must stay Running (completion gate not bypassed)")
      assertEquals(done.bgWait, None, "bgWait must be dropped after release")
      assertEquals(done.status, NodeLifecycle.Completed, "node completes after bg release")
  }

end NodeDeadSessionAutoReapSpec
