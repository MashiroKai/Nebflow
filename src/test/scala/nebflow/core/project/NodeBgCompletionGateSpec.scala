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
 * 节点完成闸（bgtask-completion-gate 批，作者 2026-09-05 18:29 裁定）：
 * 节点完成判定不仅要求 turn 完成，还要求其名下等待型后台任务全部完成才投递。
 *
 * - G1 三态·无后台：无后台任务的节点 → Completed 立即放行（现状零变化）
 * - G2 三态·等待型拦截：turn 完成时 registry 有等待型任务 → hold（节点保持
 *   Running、不投递）→ 任务完成（unregister + ExternalEvent 通知）→ 唤醒轮
 *   （Completed 经 supervisorRef 回桥）→ 放行投递，result = 最后一轮文本
 * - G3 三态·服务型不等待：persistent=true 的任务不纳入等待集 → 立即完成
 * - G4 超时/停滞杀 → failed 注明：等待集内任务被看护杀（registry 终局记账）
 *   → 节点 Failed（非 Cancelled——杀因原文措辞净化）+ result 含杀因与 agent
 *   消化失败通知后的最终输出
 * - G5 等待总上限兜底：等待期超 bgWaitCapMs → Failed + 注明（后台任务不能
 *   卡死节点）
 * - G6 下游 out 交互：节点（out 指向下游节点）先等后台（bg-wait 留痕）再
 *   finalize completed——bg 等待先于终态投递发生
 * - G7 卡死防护：等待期 agent=Idle，TaskStuckWatcher 不命中（Idle 永不判
 *   卡死铁律）——变异验红：误标 Processing + 预置 giveUp 计数 → 节点被取消（红）
 * - G8 通知轮真卡死的正确行为：桥 Cancelled 通道（TaskStuckWatcher giveUp /
 *   AgentControl cancel 同链）→ 节点 cancelled，不悬挂
 *
 * 变异验红记录（实施报告 §变异）：
 * ①摘除桥 gate（Completed 直接 complete）→ G2 红；②waitingFor 不过滤
 * persistent → G3 红；③放行路径摘除 drainFailures 检查 → G4 红；
 * ④watcher scan 误含 Idle（+预置 giveUp 计数）→ G7 红。每变异后恢复验绿。
 */
class NodeBgCompletionGateSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-node-bg-gate"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "test-agent")
  os.write.over(
    tempRoot / "agents" / "test-agent" / "agent.json",
    """{"name":"test-agent","description":"bg gate regression agent","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "test-agent" / "system.md", "# test-agent\n")
  // 2026-09-05 agent 退役：新建节点执行统一 general——fixture 侧补 general agent
  os.makeDir.all(tempRoot / "agents" / "general")
  os.write.over(tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"general executor","tools":[],"category":"standalone"}""")
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  /** 后台任务模拟 stub LLM：首轮请求时在 BgTaskRegistry 登记一个等待型任务
    * （模拟 agent 起 run_in_background 后台命令——真实登记在 BashTool，spec 用
    * registry 直种等价），应答请求首行；后续轮（通知唤醒轮）应答 "bg-noted"。
    * 登记的 jobId/sessionId 记入 Ref 供 spec 断言与清理。 */
  private class BgStubLlm(persistent: Boolean = false, registerTask: Boolean = true):
    val jobIds: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)
    val nodeSessions: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)
    val turnCount: Ref[IO, Int] = Ref.unsafe[IO, Int](0)
    // 循环依赖解法（handle 需 res，mkResources 需 handle）：spec 在 mkResources
    // 后回填 res；首轮 LLM 请求发生在回填之后，时序安全。
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
                // 首轮：找到本节点会话（kind=Flow + node- 前缀），登记等待型任务
                Option(res) match
                  case None => IO.raiseError(new RuntimeException("spec res not injected"))
                  case Some(r) =>
                    r.agentRegistry.get.flatMap { reg =>
                      reg.values.find(rec => rec.kind == AgentKind.Flow && rec.sessionId.startsWith("node-")) match
                        case Some(rec) =>
                          val jobId = s"bg-gate-spec-${java.util.UUID.randomUUID().toString.take(8)}"
                          BgTaskRegistry.register(jobId, rec.sessionId, "spec bg task", "local", "nebula-root", persistent) *>
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

  /** 根会话记录器：deliverToNebula 投递终点记账。 */
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
    bgWaitCapMs: Long = 3_600_000L,
    // noderpt 批 A 段（2026-09-11）：完成门两条腿的显式注入。
    //  - bgGateCompletionHold = **true**：本 spec 的主题就是腿 1（后台任务存活 hold）
    //    的既有行为（G1–G8），生产默认已封存（false）⇒ 此处置回旧行为作对照；
    //  - reportGateHold = **false**：腿 2（未申报不终态化）不在本 spec 主题内，且其
    //    桩 LLM 不申报 ⇒ 打开会让 G1/G2/G5/G6 全部滞留 Running（腿 2 的默认开行为由
    //    NodeReportReminderSpec + G9 覆盖）。
    bgGateCompletionHold: Boolean = true,
    reportGateHold: Boolean = false
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
        bgGateCompletionHold = Some(bgGateCompletionHold),
        reportGateHold = Some(reportGateHold)
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

  private def nodeInput(project: String, nodename: String, extra: (String, Json)*): Json =
    Json.obj(("project" -> Json.fromString(project)) :: ("nodename" -> Json.fromString(nodename)) :: extra.toList*)

  private def createNode(project: String, ws: os.Path, name: String, task: String,
      extraOut: Option[String] = None, res: SharedResources = null, system: ActorSystem = null): IO[Unit] =
    val ctx = ToolContext(
      projectRoot = ws.toString,
      sessionId = Some("spec-sid"),
      rootSessionId = Some("nebula-root"),
      sharedResources = Some(res),
      actorSystem = Some(system)
    )
    val extras = List(
      "description" -> Json.fromString("bg gate spec node"),
      "task" -> Json.fromString(task),
      // 2026-09-12 批 A1：本 spec 断言的是「投递到根」，接线须写**显式门集**
      // （`{pass,failed}/result`）；bare `"Nebula"` 今日 = 纯出口标记（零投递）。
      "out" -> Json.fromString(extraOut.getOrElse("(pass,failed)Nebula"))
    )
    NodeEditTool
      .call(nodeInput(project, name, extras*).asObject.get, ctx)
      .map(_.left.map(_.message))
      .flatMap {
        case Left(err) => IO.raiseError(new AssertionError(s"NodeEdit failed: $err"))
        case Right(_)  => IO.unit
      }

  private def byName(rt: ProjectRuntime, name: String): IO[NodeDef] =
    rt.store.snapshot.map(_.nodes.values.find(_.name == name)).map {
      case Some(n) => n
      case None    => fail(s"node '$name' must exist")
    }

  /** 等节点 agent 回 Idle（turn 1 结束），返回 (nodeSessionId, agentRef)。 */
  private def waitIdle(res: SharedResources, timeout: FiniteDuration = 15.seconds): IO[(String, nebflow.actor.ActorRef[AgentCommand])] =
    def go(deadline: Long): IO[(String, nebflow.actor.ActorRef[AgentCommand])] =
      res.agentRegistry.get.flatMap { reg =>
        reg.values.find(r => r.kind == AgentKind.Flow && r.sessionId.startsWith("node-")) match
          case Some(rec) if rec.status == nebflow.agent.AgentStatus.Idle =>
            IO.pure((rec.sessionId, rec.ref))
          case _ =>
            if System.currentTimeMillis() >= deadline then
              IO.raiseError(new AssertionError("node agent never went Idle"))
            else IO.sleep(50.millis) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  /** 模拟后台任务完成通知（BashTool makeNotifyCallback 同构 ExternalEvent）。 */
  private def notifyBgCompleted(ref: nebflow.actor.ActorRef[AgentCommand], description: String): IO[Unit] =
    (ref ! AgentCommand.ExternalEvent(
      source = "background-task",
      eventType = "completed",
      payload = s"[Background task completed] \"$description\":\nspec-output",
      metadata = io.circe.JsonObject("description" -> description.asJson)
    )).void

  /** 流程图事件留痕（FlowMapEventLog JSONL）读取。 */
  private def readEvents(ws: os.Path): IO[List[String]] =
    IO.blocking {
      val f = ws / ".nebflow" / "flow-map-events.jsonl"
      if os.exists(f) then os.read(f).linesIterator.toList else Nil
    }

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  // ── G1 三态·无后台：立即完成（现状零变化）────────────────────

  test("G1: node without background tasks completes immediately (zero behavior change)") {
    val ws = tempRoot / "ws-g1"
    os.makeDir.all(ws)
    val system = ActorSystem(s"bg-g1-${scala.util.Random.nextInt(100000)}")
    val llm = BgStubLlm(registerTask = false)
    for
      res <- mkResources(system, tempRoot, llm.handle)
      _ <- IO(llm.res = res)
      recorded <- registerRecorder(res, system, "nebula-root")
      rt <- mountProject("bg-g1", ws, system, res)
      _ <- createNode("bg-g1", ws, "plain-a", "result-PLAIN", res = res, system = system)
      _ <- waitUntil(15.seconds)(byName(rt, "plain-a").map(_.status == NodeLifecycle.Completed))
      a <- byName(rt, "plain-a")
      imms <- recordedImmediate(recorded)
      jobs <- llm.jobIds.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(a.status, NodeLifecycle.Completed, "no-bg node must complete immediately")
      assertEquals(a.result, Some("result-PLAIN"))
      assert(imms.exists(_.text.contains("[Node 'plain-a' completed]")), "delivery must happen immediately")
      assertEquals(jobs, Nil, "no bg task registered in this test")
  }

  // ── G2 三态·等待型拦截：hold → 后台完 → 唤醒轮 → 放行投递 ────

  test("G2: waiting-type bg task holds completion until it finishes, then delivers last-turn text") {
    val ws = tempRoot / "ws-g2"
    os.makeDir.all(ws)
    val system = ActorSystem(s"bg-g2-${scala.util.Random.nextInt(100000)}")
    val llm = BgStubLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      _ <- IO(llm.res = res)
      recorded <- registerRecorder(res, system, "nebula-root")
      rt <- mountProject("bg-g2", ws, system, res)
      _ <- createNode("bg-g2", ws, "wait-a", "result-ALPHA", res = res, system = system)
      // turn 1 结束（agent Idle），bg 任务仍在册 → 节点必须保持 Running（hold）
      (nodeSid, agentRef) <- waitIdle(res)
      jobs <- llm.jobIds.get
      // beta.57 CI G2 治本（20260908）：固定 sleep 猜窗口改有界轮询——等桥真正
      // 完成 turn-1 持留处理（bg-wait 审计事件落盘）才继续，消除「唤醒轮
      // Completed 先于 hold 建立到达」的时序脆弱；「若未 hold 就错误终态化」
      // 的显形能力保留（无 hold → 无 bg-wait 事件 → 此处超时红）。
      _ <- waitUntil(15.seconds)(readEvents(ws).map(_.exists(_.contains("\"bg-wait\""))))
      midRun <- byName(rt, "wait-a")
      midImms <- recordedImmediate(recorded)
      waiting <- BgTaskRegistry.waitingFor(nodeSid)
      // 后台任务完成：unregister（回调同构）+ ExternalEvent 通知 → 唤醒轮
      _ <- jobs.traverse_(BgTaskRegistry.unregister)
      _ <- notifyBgCompleted(agentRef, "spec bg task")
      // 放行窗口 20s→30s：CI 负载头部空间（本地实测释放 ~4ms，绿路径时长不变）
      _ <- waitUntil(30.seconds)(byName(rt, "wait-a").map(n =>
        n.status == NodeLifecycle.Completed || n.status == NodeLifecycle.Failed))
      done <- byName(rt, "wait-a")
      postImms <- recordedImmediate(recorded)
      events <- readEvents(ws)
      _ <- jobs.traverse_(BgTaskRegistry.unregister).attempt.void
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(midRun.status, NodeLifecycle.Running, "node must stay Running while bg task pending (hold)")
      assertEquals(midImms, Nil, "no delivery while holding")
      assert(waiting.nonEmpty, "waitingFor must see the seeded task")
      assertEquals(done.status, NodeLifecycle.Completed, "node must complete after bg task finishes")
      assertEquals(done.result, Some("bg-noted"), "result must be the LAST turn text (agent consumed the notification)")
      assert(postImms.exists(_.text.contains("[Node 'wait-a' completed]")), "delivery must happen only after release")
      assert(events.exists(_.contains("\"bg-wait\"")), s"bg-wait audit event expected, got: ${events.mkString("|").take(400)}")
      assert(events.exists(_.contains("\"bg-released\"")), s"bg-released audit event expected, got: ${events.mkString("|").take(400)}")
  }

  // ── G3 三态·服务型不等待：persistent 过滤 ────────────────────

  test("G3: persistent (service-type) bg task is NOT waited on — node completes immediately") {
    val ws = tempRoot / "ws-g3"
    os.makeDir.all(ws)
    val system = ActorSystem(s"bg-g3-${scala.util.Random.nextInt(100000)}")
    val llm = BgStubLlm(persistent = true)
    for
      res <- mkResources(system, tempRoot, llm.handle)
      _ <- IO(llm.res = res)
      recorded <- registerRecorder(res, system, "nebula-root")
      rt <- mountProject("bg-g3", ws, system, res)
      _ <- createNode("bg-g3", ws, "serv-a", "result-SERV", res = res, system = system)
      // 注意：persistent 立即放行 → engine 终态化即移除 agentRegistry 记录——
      // Idle 窗口不可见（G3 正是「不等」的证据面），等终态即可；nodeSid 从 stub
      // 登记记录取（首轮 LLM 请求时 registry 里必有）。
      _ <- waitUntil(15.seconds)(llm.nodeSessions.get.map(_.nonEmpty))
      nodeSid <- llm.nodeSessions.get.map(_.headOption.getOrElse(fail("stub never saw the node session")))
      _ <- waitUntil(15.seconds)(byName(rt, "serv-a").map(_.status == NodeLifecycle.Completed))
      done <- byName(rt, "serv-a")
      waiting <- BgTaskRegistry.waitingFor(nodeSid)
      // persistent 任务按 rootSessionId（nebula-root）分桶——按 taskId 断言在册
      jobIdsNow <- llm.jobIds.get
      rawStill <- BgTaskRegistry.activeTasksJson.map { j =>
        j.toString.contains("spec bg task") && jobIdsNow.nonEmpty &&
          j.toString.contains("taskId")
      }
      _ <- llm.jobIds.get.flatMap(_.traverse_(BgTaskRegistry.unregister))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(done.status, NodeLifecycle.Completed, "persistent task must not hold the node")
      assertEquals(waiting, Nil, "waitingFor must filter persistent tasks")
      assert(rawStill, "persistent task stays registered for the frontend WS snapshot")
  }

  // ── G4 超时/停滞杀 → failed 注明（拒绝静默 completed）─────────

  test("G4: guard-killed bg task (ledger) finalizes node as FAILED with annotation, never silent completed") {
    val ws = tempRoot / "ws-g4"
    os.makeDir.all(ws)
    val system = ActorSystem(s"bg-g4-${scala.util.Random.nextInt(100000)}")
    val llm = BgStubLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      _ <- IO(llm.res = res)
      recorded <- registerRecorder(res, system, "nebula-root")
      rt <- mountProject("bg-g4", ws, system, res)
      _ <- createNode("bg-g4", ws, "kill-a", "result-KILL", res = res, system = system)
      (nodeSid, agentRef) <- waitIdle(res)
      jobs <- llm.jobIds.get
      _ <- waitUntil(10.seconds)(BgTaskRegistry.waitingFor(nodeSid).map(_.nonEmpty))
      // 模拟看护杀回调（BashTool gateLedger 同构）：unregister + 终局记账。
      // 杀因原文含 "cancelled"（B1 idle 杀文案）——failed 注明必须净化，
      // 且节点终态必须是 Failed 而非 Cancelled（completeNode 路由回归红线）。
      _ <- jobs.traverse_(jid => BgTaskRegistry.unregister(jid) *>
        BgTaskRegistry.markFailed(jid, nodeSid, "nebula-root", "spec bg task",
          "Background command was idle (no output) for 300s and was automatically cancelled."))
      _ <- notifyBgCompleted(agentRef, "spec bg task")
      _ <- waitUntil(20.seconds)(byName(rt, "kill-a").map(n => NodeLifecycle.Terminal.contains(n.status)))
      done <- byName(rt, "kill-a")
      imms <- recordedImmediate(recorded)
      ledgerLeft <- BgTaskRegistry.drainFailures(nodeSid)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(done.status, NodeLifecycle.Failed, "guard-killed wait-set task must fail the node, not complete it")
      assert(done.result.exists(_.contains("killed by the background guard")), s"annotation expected: ${done.result}")
      assert(done.result.exists(_.contains("automatically auto-stopped")), s"cause (scrubbed) expected: ${done.result}")
      assert(!done.result.exists(_.contains("automatically cancelled")), "raw cause wording must be scrubbed (cancelNode routing)")
      assertEquals(done.result.map(_.contains("bg-noted")), Some(true), "agent final output must be preserved in the annotation")
      assert(imms.exists(m => m.text.contains("[Node 'kill-a' failed]")), "failed delivery must happen")
      assertEquals(ledgerLeft, Nil, "failure ledger must be consumed by the bridge (no leak)")
  }

  // ── G5 等待总上限兜底：后台任务不能卡死节点 ──────────────────

  test("G5: wait cap (small injected) finalizes node as FAILED with annotation while task pending") {
    val ws = tempRoot / "ws-g5"
    os.makeDir.all(ws)
    val system = ActorSystem(s"bg-g5-${scala.util.Random.nextInt(100000)}")
    val llm = BgStubLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      _ <- IO(llm.res = res)
      recorded <- registerRecorder(res, system, "nebula-root")
      rt <- mountProject("bg-g5", ws, system, res, bgWaitCapMs = 1200L)
      _ <- createNode("bg-g5", ws, "cap-a", "result-CAP", res = res, system = system)
      _ <- waitIdle(res)
      jobs <- llm.jobIds.get
      // 任务一直不完成 → 1.2s 兜底 → failed
      _ <- waitUntil(20.seconds)(byName(rt, "cap-a").map(n => NodeLifecycle.Terminal.contains(n.status)))
      done <- byName(rt, "cap-a")
      events <- readEvents(ws)
      _ <- jobs.traverse_(BgTaskRegistry.unregister).attempt.void
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(done.status, NodeLifecycle.Failed, "wait cap must fail the node (never hang)")
      assert(done.result.exists(_.contains("wait cap exceeded")), s"cap annotation expected: ${done.result}")
      assert(events.exists(_.contains("\"bg-wait\"")), "bg-wait audit expected")
      assert(events.exists(_.contains("\"bg-wait-timeout\"")), "bg-wait-timeout audit expected")
  }

  // ── G6 下游 out 交互：bg 等待先于终态投递 ──────────────────────

  test("G6: node with a downstream out waits for bg tasks first (bg-wait), then finalizes completed") {
    val ws = tempRoot / "ws-g6"
    os.makeDir.all(ws)
    val system = ActorSystem(s"bg-g6-${scala.util.Random.nextInt(100000)}")
    val llm = BgStubLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      _ <- IO(llm.res = res)
      recorded <- registerRecorder(res, system, "nebula-root")
      rt <- mountProject("bg-g6", ws, system, res)
      // seed 下游 wiring 节点——出边指向下游（区别于 G2 的 out=Nebula）
      _ <- rt.store.mutate(st => st.copy(nodes = st.nodes ++ Map(
        "n-g6-down" -> NodeDef(id = "n-g6-down", name = "bg-down", agent = "test-agent",
          task = None, status = NodeLifecycle.Wiring, out = List(OutEdge.nebula),
          createdAt = System.currentTimeMillis())))).void
      _ <- createNode("bg-g6", ws, "down-a", "result-HOLDBG",
        extraOut = Some("n-g6-down"), res = res, system = system)
      (nodeSid, agentRef) <- waitIdle(res)
      jobs <- llm.jobIds.get
      // 同 G2 治本：hold 建立改 bg-wait 事件有界轮询（替代固定 sleep 猜窗口）
      _ <- waitUntil(15.seconds)(readEvents(ws).map(_.exists(_.contains("\"bg-wait\""))))
      midRun <- byName(rt, "down-a")
      // 后台完成 → 放行 → completeNode → completed（无 hold 分流）
      _ <- jobs.traverse_(BgTaskRegistry.unregister)
      _ <- notifyBgCompleted(agentRef, "spec bg task")
      _ <- waitUntil(30.seconds)(byName(rt, "down-a").map(n => NodeLifecycle.Terminal.contains(n.status)))
      done <- byName(rt, "down-a")
      events <- readEvents(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(midRun.status, NodeLifecycle.Running, "bg wait happens before finalize (node stays running)")
      assertEquals(done.status, NodeLifecycle.Completed, "after bg release the node finalizes as completed")
      assertEquals(done.result, Some("bg-noted"))
      assert(events.exists(_.contains("\"bg-wait\"")), "bg-wait audit expected before completed")
  }

  // ── G7 卡死防护：等待期 Idle 不被 TaskStuckWatcher 命中 ───────

  test("G7: waiting node agent is Idle — TaskStuckWatcher scan must NOT touch it (even with giveUp count pre-seeded)") {
    val ws = tempRoot / "ws-g7"
    os.makeDir.all(ws)
    val system = ActorSystem(s"bg-g7-${scala.util.Random.nextInt(100000)}")
    val llm = BgStubLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      _ <- IO(llm.res = res)
      recorded <- registerRecorder(res, system, "nebula-root")
      rt <- mountProject("bg-g7", ws, system, res)
      _ <- createNode("bg-g7", ws, "stuck-a", "result-STUCK", res = res, system = system)
      (nodeSid, _) <- waitIdle(res)
      jobs <- llm.jobIds.get
      _ <- waitUntil(10.seconds)(BgTaskRegistry.waitingFor(nodeSid).map(_.nonEmpty))
      // 预置 giveUp 计数（≥ StopAttempts+2）：若（变异后）scan 命中 Idle 节点，
      // 立即 giveUp → 桥 Cancelled → 节点 cancelled。正常实现 scan 只看 Processing
      // → 等待期 Idle 永不命中 → 计数无关紧要。
      stopCounts <- cats.effect.Ref.of[IO, Map[String, Int]](Map(nodeSid -> (TaskStuckWatcher.StopAttempts + 2)))
      _ <- TaskStuckWatcher.scan(res, new WsHub(), 0L, stopCounts)
      _ <- IO.sleep(500.millis) // giveUp 若发生，取消链是异步的——留显形窗口
      a <- byName(rt, "stuck-a")
      stillRegistered <- res.agentRegistry.get.map(_.contains(nodeSid))
      _ <- jobs.traverse_(BgTaskRegistry.unregister).attempt.void
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(a.status, NodeLifecycle.Running, "waiting Idle node must never be a stuck candidate")
      assert(stillRegistered, "agent record must survive the scan")
  }

  // ── G8 通知轮真卡死的正确行为：桥 Cancelled 通道取消节点 ──────

  test("G8: bridge Cancelled channel (watcher giveUp / AgentControl cancel) cancels the held node — no hang") {
    val ws = tempRoot / "ws-g8"
    os.makeDir.all(ws)
    val system = ActorSystem(s"bg-g8-${scala.util.Random.nextInt(100000)}")
    val llm = BgStubLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      _ <- IO(llm.res = res)
      recorded <- registerRecorder(res, system, "nebula-root")
      rt <- mountProject("bg-g8", ws, system, res)
      _ <- createNode("bg-g8", ws, "givup-a", "result-GIVEUP", res = res, system = system)
      (nodeSid, agentRef) <- waitIdle(res)
      jobs <- llm.jobIds.get
      _ <- waitUntil(10.seconds)(BgTaskRegistry.waitingFor(nodeSid).map(_.nonEmpty))
      // 模拟 TaskStuckWatcher 对真卡死通知轮的 giveUp（supervisorRef Cancelled 同链）
      reg <- res.agentRegistry.get
      supRef = reg(nodeSid).supervisorRef
      _ <- supRef.traverse_(sup =>
        (sup ! AgentEvent.Cancelled(nodeSid, "stuck — released by TaskStuckWatcher (test proxy)")).void)
      _ <- waitUntil(20.seconds)(byName(rt, "givup-a").map(n => NodeLifecycle.Terminal.contains(n.status)))
      done <- byName(rt, "givup-a")
      _ <- jobs.traverse_(BgTaskRegistry.unregister).attempt.void
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(done.status, NodeLifecycle.Cancelled, "giveUp Cancelled via bridge must cancel the held node (correct behavior, no hang)")
  }

  // ── G9 封存档（noderpt 批 A 段改点①）：flag 关 ⇒ 不 hold（等待型任务存续也放行）──

  test("G9: with the bg completion-hold sealed, a pending wait-set task no longer blocks finalization") {
    val ws = tempRoot / "ws-g9"
    os.makeDir.all(ws)
    val system = ActorSystem(s"bg-g9-${scala.util.Random.nextInt(100000)}")
    val llm = BgStubLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      _ <- IO(llm.res = res)
      recorded <- registerRecorder(res, system, "nebula-root")
      // 本用例 = 封存档（生产默认）：bgGateCompletionHold=false
      rt <- mountProject("bg-g9", ws, system, res, bgGateCompletionHold = false)
      _ <- createNode("bg-g9", ws, "sealed-a", "result-SEALED", res = res, system = system)
      // 注意：封存后节点**首轮即终态化**（不再有 hold 窗口）⇒ 不能用 waitIdle（会话
      // 已被清理）。等待型任务在首轮 LLM 请求时由桩登记 —— 等登记可见即可。
      _ <- waitUntil(15.seconds)(llm.nodeSessions.get.map(_.nonEmpty))
      sids <- llm.nodeSessions.get
      nodeSid = sids.headOption.getOrElse(fail("stub never saw the node session"))
      // 登记在册的等待型任务必须**不**拦节点（封存后的正面收益：bg 存活不再是完成
      // 门的判据）
      waiting <- BgTaskRegistry.waitingFor(nodeSid)
      _ <- waitUntil(20.seconds)(byName(rt, "sealed-a").map(n => NodeLifecycle.Terminal.contains(n.status)))
      done <- byName(rt, "sealed-a")
      imms <- recordedImmediate(recorded)
      events <- readEvents(ws)
      jobs <- llm.jobIds.get
      _ <- jobs.traverse_(BgTaskRegistry.unregister).attempt.void
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(done.status, NodeLifecycle.Completed,
        "sealed gate: a pending wait-set task must not hold the node")
      assertEquals(done.result, Some("result-SEALED"), "first-turn text finalizes immediately")
      assert(imms.exists(_.text.contains("[Node 'sealed-a' completed]")), "delivery is not delayed by the bg task")
      assert(waiting.nonEmpty, "the wait-set task was registered and present (evidence the gate is what changed)")
      assert(!events.exists(_.contains("\"bg-wait\"")), "no bg-wait hold event may be emitted when sealed")
      assert(!events.exists(_.contains("\"bg-released\"")), "no release event either (no hold happened)")
  }
end NodeBgCompletionGateSpec
