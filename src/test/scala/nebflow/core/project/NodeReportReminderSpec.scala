package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, Behaviors}
import nebflow.agent.{AgentCommand, AgentKind, AgentLibrary, AgentRecord, SharedResources}
import nebflow.core.PathUtil
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, NodeEditTool, ToolContext}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * 未申报兜底：提醒阶梯（noderpt 批 A 段 2026-09-11 作者裁定）。
 *
 * 两个交付面：
 *  - **完成门腿 2（reportGateHold，默认开）**：节点交棒（桥收到 `AgentEvent.Completed`）
 *    而该刻 `NodeReportRegistry` 申报槽为空 ⇒ **不终态化**——节点保持 Running、会话
 *    存活、结果不投递；申报槽非空 ⇒ 清表后放行（终态点 drain 照常分流三链）。
 *  - **提醒阶梯**：`10min/30min/1h/2h/4h` + 此后每 4h、单节点上限 8 拍（spec 用 prop
 *    压到秒级）；每拍注入 `[NODE-REPORT-REMINDER]` 提醒轮 + 写 `node-report-missing`
 *    事件；第 8 拍后转 **quiescent 档**（不注入、每 quiescentIntervalMs 一条事件、
 *    reminderCount 不递增）。**任何档都不判 failed、不上报失败、不杀进程或会话**
 *    （相对设计稿 §4.2「30min 判 failed」的核心改判）——本 spec 断言节点始终 Running。
 *
 * 计时语义（代裁 4）：只置不重（后续 Completed / 提醒轮自身 Completed / NodeMessage
 * 重入都不改起点）；唯一清表条件 = 该会话任一 node_report 申报。
 *
 * 用例：
 *  - R1 未申报 ⇒ hold：节点保持 Running、无投递、计时置表（持久字段 + 载荷条件键）。
 *  - R2 第 1 拍：注入提醒轮（活会话）+ `node-report-missing`（stage=active rung=N/M）。
 *  - R3 只置不重 + 第 2 拍：起点不变、reminderCount 递增、末拍带 ladderExhausted。
 *  - R4 申报 ⇒ 清表 + 放行：下一个 Completed 终态化 completed（pass 申报走既有链）、
 *    计时字段清零、载荷不再带两键。
 *  - R5 quiescent 档：阶梯耗尽后不再注入、只按间隔写 stage=quiescent 事件、
 *    reminderCount 不递增、节点仍 Running（永不 failed）。
 *  - R6 关闭腿 2（reportGateHold=false）⇒ 未申报照常放行（今天的文本锚定降级面）。
 *
 * 说明：扫描腿的节拍源 = `ProjectActor.TtlTick`（30s，生产）；本 spec 直接调用
 * `NodeEngine.remindUnreportedNodes()` 逐拍驱动（确定性；节拍接线在 ProjectActor
 * 单行调用点，属代码面可见项）。
 */
class NodeReportReminderSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-node-report-reminder"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "test-agent")
  os.write.over(
    tempRoot / "agents" / "test-agent" / "agent.json",
    """{"name":"test-agent","description":"node_report reminder regression agent","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "test-agent" / "system.md", "# test-agent\n")
  os.makeDir.all(tempRoot / "agents" / "general")
  os.write.over(tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"general executor","tools":[],"category":"standalone"}""")
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  /** ladder/quiescent prop 逐例设置（生产口径 = Defaults 现读；spec 用秒级压阶梯）。 */
  private def setLadder(ladderMs: String, maxRungs: Int, quiescentMs: String = "600000"): Unit =
    System.setProperty("nebflow.noderpt.remind.ladderMs", ladderMs)
    System.setProperty("nebflow.noderpt.remind.maxRungs", maxRungs.toString)
    System.setProperty("nebflow.noderpt.remind.quiescentIntervalMs", quiescentMs)

  override def afterEach(context: munit.AfterEach): Unit =
    ProjectRuntimeRegistry.clear
    System.clearProperty("nebflow.noderpt.remind.ladderMs")
    System.clearProperty("nebflow.noderpt.remind.maxRungs")
    System.clearProperty("nebflow.noderpt.remind.quiescentIntervalMs")

  /** 桩 LLM：turn 1 应答任务首行（不申报）；`declareOnTurn` 指定的轮次登记一条申报
    * （模拟 agent 调 node_report）后应答 —— 用于「提醒 → 申报 → 放行」链。
    * `requests` 记录每轮请求的**上下文全文**（提醒是否真进了 agent 上下文 = 注入证据）。
    * `replyOverride`（noderpt 批 B 段 · ⑧-4 豁免用例）：固定每轮应答文本——用来精确
    * 控制节点**最终输出文本**（判定 `BlockedReader` 是否命中）。 */
  private class StubLlm(declareOnTurn: Int = 0, category: String = "pass",
      replyOverride: Option[String] = None):
    val turnCount: Ref[IO, Int] = Ref.unsafe[IO, Int](0)
    val sessions: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)
    val requests: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)
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
            _ <- requests.update(_ :+ req.messages.map(_.textContent).mkString("\n"))
            _ <- IO(Option(res)).flatMap {
              case None => IO.unit
              case Some(r) =>
                r.agentRegistry.get.flatMap { reg =>
                  reg.values.find(rec => rec.kind == AgentKind.Flow && rec.sessionId.startsWith("node-")) match
                    case None => IO.unit
                    case Some(rec) =>
                      sessions.update(_ :+ rec.sessionId) *>
                        (if turn == declareOnTurn then
                           NodeReportRegistry.register(rec.sessionId, BlockedFeedback(category, s"spec declare ($category)", ""))
                         else IO.unit)
                }
            }
          yield turn
        }.flatMap { turn =>
          val text = req.messages.map(_.textContent).mkString("\n")
          val reply = replyOverride.getOrElse(
            if turn == 1 then text.linesIterator.nextOption().getOrElse("").take(200) else "reminder-turn-ack")
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

  private def registerRecorder(res: SharedResources, system: ActorSystem, sid: String): IO[Ref[IO, List[AgentCommand]]] =
    for
      recorded <- Ref.of[IO, List[AgentCommand]](Nil)
      ref <- system.spawn(recorderBehavior(recorded), s"nrr-rec-${scala.util.Random.nextInt(100000)}")
      _ <- res.agentRegistry.update(_ + (sid -> AgentRecord(sid, ref, AgentKind.Root, sid)))
    yield recorded

  private def recorderBehavior(recorded: Ref[IO, List[AgentCommand]]): nebflow.actor.Behavior[AgentCommand] =
    lazy val b: nebflow.actor.Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand](msg => recorded.update(_ :+ msg).as(b))
    b

  private def recordedImmediate(recorded: Ref[IO, List[AgentCommand]]): IO[List[AgentCommand.ImmediateInput]] =
    recorded.get.map(_.collect { case m: AgentCommand.ImmediateInput => m })

  /** 挂载引擎：reportGateHold 默认 **true**（生产默认口径）；bgGateCompletionHold 默认
    * false（① 封存后的生产口径）。 */
  private def mountProject(
    name: String,
    ws: os.Path,
    system: ActorSystem,
    res: SharedResources,
    reportGateHold: Boolean = true,
    bgGateCompletionHold: Boolean = false
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
        reportGateHold = Some(reportGateHold),
        bgGateCompletionHold = Some(bgGateCompletionHold)
      )
      pd = ProjectDef(name = name, workspace = ws.toString, agentFile = (ws / "AGENTS.md").toString,
        createdAt = System.currentTimeMillis())
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
      res: SharedResources, system: ActorSystem): IO[Unit] =
    val ctx = ToolContext(
      projectRoot = ws.toString,
      sessionId = Some("spec-sid"),
      rootSessionId = Some("nebula-root"),
      sharedResources = Some(res),
      actorSystem = Some(system)
    )
    val extras = List(
      "description" -> Json.fromString("node_report reminder spec node"),
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

  private def byName(rt: ProjectRuntime, name: String): IO[NodeDef] =
    rt.store.snapshot.map(_.nodes.values.find(_.name == name)).map {
      case Some(n) => n
      case None    => fail(s"node '$name' must exist")
    }

  /** 等节点 agent 回 Idle（首轮结束、桥已收到 Completed）。 */
  private def waitIdle(res: SharedResources, timeout: FiniteDuration = 20.seconds): IO[String] =
    def go(deadline: Long): IO[String] =
      res.agentRegistry.get.flatMap { reg =>
        reg.values.find(r => r.kind == AgentKind.Flow && r.sessionId.startsWith("node-")) match
          case Some(rec) if rec.status == nebflow.agent.AgentStatus.Idle => IO.pure(rec.sessionId)
          case _ =>
            if System.currentTimeMillis() >= deadline then
              IO.raiseError(new AssertionError("node agent never went Idle"))
            else IO.sleep(50.millis) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  private def readEvents(ws: os.Path): IO[List[String]] =
    IO.blocking {
      val f = ws / ".nebflow" / "flow-map-events.jsonl"
      if os.exists(f) then os.read(f).linesIterator.toList else Nil
    }

  /** 逐拍驱动扫描腿（生产由 ProjectActor.TtlTick 30s 节拍调用）。 */
  private def scan(rt: ProjectRuntime, times: Int = 1): IO[Unit] =
    rt.engine.remindUnreportedNodes().replicateA_(times)

  /** `node-report-missing` 事件的 **summary 串**（真实消费路径 = 解 JSONL 取 `summary`
    * 字段，再交 `parseReportMissingSummary` 单点解析）。 */
  private def reminderEvents(events: List[String]): List[String] =
    events
      .filter(l => l.contains("\"node-report-missing\""))
      .flatMap { l =>
        io.circe.parser
          .parse(l)
          .toOption
          .flatMap(_.hcursor.get[String]("summary").toOption)
          .toList
      }

  // ── R1 未申报 ⇒ hold（不终态化、不投递、保持 Running、计时置表）──────────

  test("R1: unreported hand-off holds the node Running (no delivery) and starts the clock") {
    setLadder("600000", 8) // 阶梯远未到点：本用例只验 hold 与起表
    val ws = tempRoot / "ws-r1"
    os.makeDir.all(ws)
    val system = ActorSystem(s"nrr-r1-${scala.util.Random.nextInt(100000)}")
    val llm = StubLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      _ <- IO(llm.res = res)
      recorded <- registerRecorder(res, system, "nebula-root")
      rt <- mountProject("nrr-r1", ws, system, res)
      _ <- createNode("nrr-r1", ws, "hold-a", "result-HOLD", res, system)
      _ <- waitIdle(res)
      // 等桥完成 Completed 处理（起表 = hold 已生效的可观测证据）
      _ <- waitUntil(20.seconds)(byName(rt, "hold-a").map(_.reportPendingSince.isDefined))
      _ <- IO.sleep(300.millis)
      a <- byName(rt, "hold-a")
      imms <- recordedImmediate(recorded)
      events <- readEvents(ws)
      payload = NodePayload.buildNodeJson(a, System.currentTimeMillis()).noSpaces
      _ <- scan(rt, 3)
      after <- byName(rt, "hold-a")
      eventsAfter <- readEvents(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(a.status, NodeLifecycle.Running, "unreported hand-off must NOT finalize the node")
      assertEquals(imms, Nil, "nothing may be delivered downstream while the node is held")
      assert(a.reportPendingSince.isDefined, "the clock must be started (reportPendingSince)")
      assertEquals(a.reportReminderCount, 0, "no rung fired yet (ladder far away)")
      assert(payload.contains("\"reportPendingSince\""), s"payload must carry the pending key: $payload")
      assert(payload.contains("\"reportReminderCount\""), s"payload must carry the rung counter: $payload")
      assertEquals(reminderEvents(events), Nil, "no reminder event before the first rung")
      assertEquals(after.status, NodeLifecycle.Running, "node stays Running after idle scans")
      assertEquals(reminderEvents(eventsAfter), Nil, "scans below the first rung are no-ops")
  }

  // ── R2 第 1 拍：注入提醒轮（活会话）+ node-report-missing（active）──────

  test("R2: rung 1 injects a [NODE-REPORT-REMINDER] turn into the live session and logs stage=active") {
    // 首档 700ms、次档远在 3s ⇒ 800ms 处必然恰好命中第 1 档（消除睡窗抖动）
    setLadder("700,3000,4000,5000,6000,7000,8000,9000", 8, "600000")
    val ws = tempRoot / "ws-r2"
    os.makeDir.all(ws)
    val system = ActorSystem(s"nrr-r2-${scala.util.Random.nextInt(100000)}")
    val llm = StubLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      _ <- IO(llm.res = res)
      recorded <- registerRecorder(res, system, "nebula-root")
      rt <- mountProject("nrr-r2", ws, system, res)
      _ <- createNode("nrr-r2", ws, "remind-a", "result-REMIND", res, system)
      nodeSid <- waitIdle(res)
      _ <- waitUntil(20.seconds)(byName(rt, "remind-a").map(_.reportPendingSince.isDefined))
      _ <- IO.sleep(800.millis)
      _ <- scan(rt)
      a <- byName(rt, "remind-a")
      events <- readEvents(ws)
      // 提醒注入是异步的（tell 语义）：等提醒轮真的开工（桩 LLM 起第 2 个 turn）
      // 再读请求上下文，否则读到的是注入前的历史。
      _ <- waitUntil(20.seconds)(llm.turnCount.get.map(_ >= 2))
      reqs <- llm.requests.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(a.status, NodeLifecycle.Running, "node stays Running (never failed, never killed)")
      assertEquals(a.reportReminderCount, 1, "exactly one rung fired")
      val miss = reminderEvents(events)
      assertEquals(miss.size, 1, s"exactly one node-report-missing event expected: ${events.mkString("|")}")
      val fields = NodeEngine.parseReportMissingSummary(miss.head)
      assertEquals(fields.get("stage"), Some(NodeEngine.NodeReportStageActive))
      assertEquals(fields.get("rung"), Some("1/8"))
      assertEquals(fields.get("reminderCount"), Some("1"))
      assertEquals(fields.get("ladderExhausted"), Some("false"))
      assert(fields.get("elapsedMs").exists(_.toLong >= 800), s"elapsed must be recorded: $fields")
      assert(fields.get("pendingSince").exists(_.toLong > 0), s"pendingSince must be recorded: $fields")
      assertEquals(fields.get("delivered"), Some("true"),
        "the reminder must be delivered to the live node session")
      assert(reqs.exists(_.contains(NodeEngine.NodeReportReminderPrefix)),
        s"the reminder text ([NODE-REPORT-REMINDER]) must reach the live session's context ($nodeSid)")
  }

  // ── R3 只置不重 + 第 2 拍（末拍 ladderExhausted）─────────────────────

  test("R3: clock is never reset by later Completeds; rung 2 is the last one (ladderExhausted)") {
    setLadder("600,1200", 2, "600000")
    val ws = tempRoot / "ws-r3"
    os.makeDir.all(ws)
    val system = ActorSystem(s"nrr-r3-${scala.util.Random.nextInt(100000)}")
    val llm = StubLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      _ <- IO(llm.res = res)
      recorded <- registerRecorder(res, system, "nebula-root")
      rt <- mountProject("nrr-r3", ws, system, res)
      _ <- createNode("nrr-r3", ws, "tworung-a", "result-TWO", res, system)
      _ <- waitIdle(res)
      _ <- waitUntil(20.seconds)(byName(rt, "tworung-a").map(_.reportPendingSince.isDefined))
      _ <- IO.sleep(700.millis)
      _ <- scan(rt)
      afterRung1 <- byName(rt, "tworung-a")
      // 提醒轮被注入 ⇒ 桩 LLM 起新 turn（turn 2）⇒ 新 Completed 回到桥（未申报）——
      // 起点必须不被重算（只置不重）。
      _ <- waitUntil(20.seconds)(llm.turnCount.get.map(_ >= 2))
      _ <- IO.sleep(900.millis)
      _ <- scan(rt)
      afterRung2 <- byName(rt, "tworung-a")
      events <- readEvents(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(afterRung1.reportReminderCount, 1)
      assertEquals(afterRung2.reportReminderCount, 2, "rung 2 must fire at its threshold")
      assertEquals(afterRung2.reportPendingSince, afterRung1.reportPendingSince,
        "the clock start must never be recomputed (代裁 4 只置不重)")
      assertEquals(afterRung2.status, NodeLifecycle.Running, "still Running — never failed")
      val rungs = reminderEvents(events).map(NodeEngine.parseReportMissingSummary)
      assertEquals(rungs.map(_.get("stage")).distinct, List(Some(NodeEngine.NodeReportStageActive)))
      assertEquals(rungs.map(_.get("rung")), List(Some("1/2"), Some("2/2")))
      assertEquals(rungs.last.get("ladderExhausted"), Some("true"), "last rung carries ladderExhausted=true")
  }

  // ── R4 申报 ⇒ 清表 + 放行 ⇒ 既有链终态化 completed ────────────────────

  test("R4: a node_report declaration clears the clock, releases the gate and finalizes the node") {
    setLadder("500", 8, "600000")
    // declareOnTurn=2：首轮不申报（触发 hold + 起表），提醒轮到达后申报 pass。
    val ws = tempRoot / "ws-r4"
    os.makeDir.all(ws)
    val system = ActorSystem(s"nrr-r4-${scala.util.Random.nextInt(100000)}")
    val llm = StubLlm(declareOnTurn = 2)
    for
      res <- mkResources(system, tempRoot, llm.handle)
      _ <- IO(llm.res = res)
      recorded <- registerRecorder(res, system, "nebula-root")
      rt <- mountProject("nrr-r4", ws, system, res)
      _ <- createNode("nrr-r4", ws, "release-a", "result-RELEASE", res, system)
      _ <- waitIdle(res)
      _ <- waitUntil(20.seconds)(byName(rt, "release-a").map(_.reportPendingSince.isDefined))
      held <- byName(rt, "release-a")
      _ <- IO.sleep(600.millis)
      _ <- scan(rt)
      rung1 <- byName(rt, "release-a")
      // 提醒轮注入 ⇒ agent 起新 turn ⇒ 该轮调 node_report(pass) ⇒ Completed 复检 ⇒ 放行
      _ <- waitUntil(30.seconds)(byName(rt, "release-a").map(n => NodeLifecycle.Terminal.contains(n.status)))
      done <- byName(rt, "release-a")
      imms <- recordedImmediate(recorded)
      payload = NodePayload.buildNodeJson(done, System.currentTimeMillis()).noSpaces
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(held.status, NodeLifecycle.Running, "first hand-off without a declaration holds the node")
      assertEquals(rung1.reportReminderCount, 1, "the reminder rung fired while the node was held")
      assertEquals(done.status, NodeLifecycle.Completed, "the declaration releases the gate into the existing chain")
      assertEquals(done.result, Some("reminder-turn-ack"), "result = the declaring turn's text")
      assert(done.reportPendingSince.isEmpty, "declaration clears the clock (唯一清表条件)")
      assertEquals(done.reportReminderCount, 0, "rung counter cleared with the clock")
      assert(!payload.contains("reportPendingSince"), s"finalized node must not carry the pending key: $payload")
      assert(imms.exists(_.text.contains("[Node 'release-a' completed]")), "delivery must happen after release")
  }

  // ── R5 quiescent 档：阶梯耗尽 ⇒ 不注入、只留痕、仍 Running ──────────────

  test("R5: after the ladder is exhausted the node keeps Running with stage=quiescent events only") {
    setLadder("400", 1, "400") // 单拍阶梯 + 400ms quiescent 间隔（秒级压测）
    val ws = tempRoot / "ws-r5"
    os.makeDir.all(ws)
    val system = ActorSystem(s"nrr-r5-${scala.util.Random.nextInt(100000)}")
    val llm = StubLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      _ <- IO(llm.res = res)
      recorded <- registerRecorder(res, system, "nebula-root")
      rt <- mountProject("nrr-r5", ws, system, res)
      _ <- createNode("nrr-r5", ws, "quiet-a", "result-QUIET", res, system)
      _ <- waitIdle(res)
      _ <- waitUntil(20.seconds)(byName(rt, "quiet-a").map(_.reportPendingSince.isDefined))
      _ <- IO.sleep(500.millis)
      _ <- scan(rt)          // 第 1 拍（= 唯一一拍）
      rung1 <- byName(rt, "quiet-a")
      // 进入 quiescent 档：连续多拍只写事件、不注入
      _ <- IO.sleep(600.millis)
      _ <- scan(rt)
      _ <- IO.sleep(600.millis)
      _ <- scan(rt)
      quiet <- byName(rt, "quiet-a")
      events <- readEvents(ws)
      reqs <- llm.requests.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(rung1.reportReminderCount, 1, "single-rung ladder fires once")
      assertEquals(quiet.status, NodeLifecycle.Running, "quiescent stage never fails/kills the node")
      assertEquals(quiet.reportReminderCount, 1, "quiescent events must NOT increment reminderCount")
      val parsed = reminderEvents(events).map(NodeEngine.parseReportMissingSummary)
      assertEquals(parsed.count(_.get("stage") == Some(NodeEngine.NodeReportStageActive)), 1,
        s"exactly one active-stage reminder: ${events.mkString("|")}")
      assert(parsed.count(_.get("stage") == Some(NodeEngine.NodeReportStageQuiescent)) >= 1,
        s"quiescent events expected after the ladder: ${events.mkString("|")}")
      val injected = reqs.count(_.contains(NodeEngine.NodeReportReminderPrefix))
      assertEquals(injected, 1, "quiescent stage injects nothing (no further LLM turns)")
      assert(!events.exists(_.contains("\"type\":\"failed\"")), "no failed path may exist in this leg")
  }

  // ── R7 计时落盘（跨宿主重启存活）+ R6 腿 2 关闭 ⇒ 未申报照常放行 ────────

  test("R7: the pending clock + rung counter are persisted and decode back (cross-restart timing survival)") {
    // 硬约束（任务书）：计时必须跨宿主重启存活 ⇒ 落点必须是**磁盘**上的节点持久字段
    // （TtlTick 扫描腿重读同一 FlowMapStore），不得是 per-node fiber 内存计时器。
    // 本用例 = 该要求的直接证据：扫描腿驱动一拍后读 **flow-map.json 原文**，断言
    // ①两键落盘且值正确；②用同一 `NodeDef` 解码器（下一次宿主 boot 的读取路径）
    // 往返后值不变（进程重启的唯一信息载体就是这份文件）。
    setLadder("400", 1, "600000")
    val ws = tempRoot / "ws-r7"
    os.makeDir.all(ws)
    val system = ActorSystem(s"nrr-r7-${scala.util.Random.nextInt(100000)}")
    val llm = StubLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      _ <- IO(llm.res = res)
      _ <- registerRecorder(res, system, "nebula-root")
      rt <- mountProject("nrr-r7", ws, system, res)
      _ <- createNode("nrr-r7", ws, "persist-a", "result-PERSIST", res, system)
      _ <- waitIdle(res)
      _ <- waitUntil(20.seconds)(byName(rt, "persist-a").map(_.reportPendingSince.isDefined))
      _ <- IO.sleep(500.millis)
      _ <- scan(rt) // 第 1 拍（= 唯一一拍）⇒ reminderCount 0→1 落库
      node <- byName(rt, "persist-a")
      raw <- IO.blocking(os.read(ws / ".nebflow" / "flow-map.json"))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      val st = io.circe.parser.parse(raw).toOption.getOrElse(fail(s"flow-map.json must parse: $raw"))
      val nodeJson = st.hcursor.downField("nodes").downField(node.id).focus.getOrElse(fail("node must be in flow-map.json"))
      assertEquals(nodeJson.hcursor.get[Long]("reportPendingSince").toOption, node.reportPendingSince,
        s"pending clock must be persisted verbatim: $nodeJson")
      assertEquals(nodeJson.hcursor.get[Int]("reportReminderCount").toOption, Some(1),
        s"rung counter must be persisted: $nodeJson")
      // 下一宿主 boot 的读取路径 = 同一 NodeDef 解码器（NodeDef.given Codec）——往返证明
      val decoded = nodeJson.as[NodeDef].toOption.getOrElse(fail(s"node JSON must decode back: $nodeJson"))
      assertEquals(decoded.reportPendingSince, node.reportPendingSince, "decoded clock must match")
      assertEquals(decoded.reportReminderCount, 1, "decoded rung counter must match")
      assertEquals(decoded.status, NodeLifecycle.Running, "held node stays Running across persistence")
  }

  // ── R6 腿 2 关闭 ⇒ 未申报照常放行（今天的降级面）─────────────────────

  test("R8: a fiber-less terminal write (dead-session reap) clears the clock — no stale pending state") {
    // 隔离实例实跑读数（noderpt-e2e，2026-09-11）：boot 期 `reapStaleRunning` 收殓的
    // Running 节点**不经 run fiber** ⇒ 到不了 `cleanupRunTables` ⇒ 归档里残留
    // reportPendingSince/reportReminderCount。本用例把「终态写点同事务清表」钉死。
    setLadder("600000", 8)
    val ws = tempRoot / "ws-r8"
    os.makeDir.all(ws)
    val system = ActorSystem(s"nrr-r8-${scala.util.Random.nextInt(100000)}")
    val llm = StubLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      _ <- IO(llm.res = res)
      rt <- mountProject("nrr-r8", ws, system, res)
      // store 直种一个 running 且带计时的节点（无活 fiber = boot 期僵尸形态）
      _ <- rt.store.mutate { s =>
        s.copy(nodes = s.nodes + ("n-r8-stale" -> NodeDef(
          id = "n-r8-stale", name = "stale-node", agent = "general",
          status = NodeLifecycle.Running, task = Some("probe"),
          createdAt = System.currentTimeMillis(),
          reportPendingSince = Some(System.currentTimeMillis() - 70000),
          reportReminderCount = 3)))
      }
      _ <- rt.engine.reapStaleRunning("n-r8-stale")
      reaped <- byName(rt, "stale-node")
      events <- readEvents(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(reaped.status, NodeLifecycle.Cancelled, "reap finalizes the stale running node")
      assertEquals(reaped.reportPendingSince, None, "terminal write must clear the pending clock")
      assertEquals(reaped.reportReminderCount, 0, "terminal write must reset the rung counter")
      assert(events.exists(_.contains("\"reaped\"")), s"reap audit line expected: $events")
      assertEquals(reminderEvents(events), Nil, "a cancelled node must never be reminded")
  }

  test("R6: with reportGateHold off an unreported hand-off finalizes as before (text-anchored fallback)") {
    setLadder("400", 8, "600000")
    val ws = tempRoot / "ws-r6"
    os.makeDir.all(ws)
    val system = ActorSystem(s"nrr-r6-${scala.util.Random.nextInt(100000)}")
    val llm = StubLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      _ <- IO(llm.res = res)
      recorded <- registerRecorder(res, system, "nebula-root")
      rt <- mountProject("nrr-r6", ws, system, res, reportGateHold = false)
      _ <- createNode("nrr-r6", ws, "off-a", "result-OFF", res, system)
      _ <- waitUntil(30.seconds)(byName(rt, "off-a").map(n => NodeLifecycle.Terminal.contains(n.status)))
      done <- byName(rt, "off-a")
      // 已终态 ⇒ 扫描腿零动作（与 cleanupRunTables 对称）
      _ <- IO.sleep(600.millis)
      _ <- scan(rt)
      events <- readEvents(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(done.status, NodeLifecycle.Completed, "gate off = pre-batch behavior (completed)")
      assertEquals(done.result, Some("result-OFF"))
      assertEquals(reminderEvents(events), Nil, "no reminder may fire once the node is finalized")
      assert(done.reportPendingSince.isEmpty, "gate off never starts the clock")
  }
  // ── R9/R10 文本锚定 BLOCKED 豁免（noderpt 批 B 段 · 作者代裁 ⑧-4 = (a)）──────
  //
  // 裁定：节点 `Completed` ∧ 申报槽空 ∧ **输出文本可被 `BlockedReader` 判为 blocked**
  // ⇒ **不走 hold、不进提醒阶梯**，照既有行为终态化 blocked（求助上报链
  // blocked → 分发器 → 人 完整保留）。豁免范围**严格限定**为「能被 `BlockedReader`
  // 判定的文本」，不是「任意非空文本」——其余静默结束一律仍走 hold + 阶梯。
  // 两条用例分别钉住这两个方向（R9 豁免命中 / R10 边界：文本里出现 "BLOCKED" 字样
  // 但**不满足锚定**（非行首 + 非 `:`/空白 后继）⇒ 仍 hold）。

  test("R9 (⑧-4): BLOCKED-anchored final text is exempt from the hold — finalized blocked, no reminder, no clock") {
    // 阶梯压到 400ms：若豁免失效（被 hold），随后必出 node-report-missing 事件 ⇒ 断言更强。
    setLadder("400,800,1200", 3, "600000")
    val ws = tempRoot / "ws-r9"
    os.makeDir.all(ws)
    val system = ActorSystem(s"nrr-r9-${scala.util.Random.nextInt(100000)}")
    val llm = StubLlm(replyOverride = Some("BLOCKED: spec exemption probe — 需要人工裁决"))
    for
      res <- mkResources(system, tempRoot, llm.handle)
      _ <- IO(llm.res = res)
      recorded <- registerRecorder(res, system, "nebula-root")
      rt <- mountProject("nrr-r9", ws, system, res)
      _ <- createNode("nrr-r9", ws, "blockedtext-a", "result-BLOCKTEXT", res, system)
      _ <- waitUntil(30.seconds)(byName(rt, "blockedtext-a").map(n => NodeLifecycle.Terminal.contains(n.status)))
      done <- byName(rt, "blockedtext-a")
      // 等过一整档（400ms）+ 多次扫描：若被 hold 则提醒事件必然出现
      _ <- IO.sleep(900.millis)
      _ <- scan(rt, 3)
      after <- byName(rt, "blockedtext-a")
      events <- readEvents(ws)
      imms <- recordedImmediate(recorded)
      payload = NodePayload.buildNodeJson(after, System.currentTimeMillis()).noSpaces
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(done.status, NodeLifecycle.Blocked,
        "a BLOCKED-anchored final text must finalize through the existing blocked chain (⑧-4 豁免)")
      assert(done.result.exists(_.contains("[blocked:")), s"blocked render string expected: ${done.result}")
      assert(done.blockedFeedback.isDefined, "blockedFeedback must be persisted for the dispatcher chain")
      assertEquals(after.status, NodeLifecycle.Blocked, "no later scan may change it")
      assert(done.reportPendingSince.isEmpty, "the exemption must never start the pending clock")
      assertEquals(reminderEvents(events), Nil,
        s"the reminder ladder must NOT fire for an exempted BLOCKED text: ${events.mkString("|").take(400)}")
      assertEquals(after.reportReminderCount, 0, "no rung may be consumed")
      assert(!payload.contains("reportPendingSince"), s"payload must not carry the pending key: $payload")
      assert(!imms.exists(_.text.contains("[Node 'blockedtext-a' completed]")),
        "a blocked node must not deliver a completed result downstream")
  }

  test("R10 (⑧-4 boundary): text merely CONTAINING 'BLOCKED' is NOT exempt — the node is still held + reminded") {
    setLadder("700,3000,4000", 3, "600000")
    val ws = tempRoot / "ws-r10"
    os.makeDir.all(ws)
    val system = ActorSystem(s"nrr-r10-${scala.util.Random.nextInt(100000)}")
    // 非锚定（不在行首 + 非 `:`/空白 后继）⇒ `BlockedReader.parse` 必不命中 ⇒ 必须 hold。
    val llm = StubLlm(replyOverride = Some("nothing is BLOCKED here — silent finish: result-SILENT"))
    for
      res <- mkResources(system, tempRoot, llm.handle)
      _ <- IO(llm.res = res)
      recorded <- registerRecorder(res, system, "nebula-root")
      rt <- mountProject("nrr-r10", ws, system, res)
      _ <- createNode("nrr-r10", ws, "silenttext-a", "result-SILENT", res, system)
      _ <- waitUntil(20.seconds)(byName(rt, "silenttext-a").map(_.reportPendingSince.isDefined))
      held <- byName(rt, "silenttext-a")
      _ <- IO.sleep(800.millis)
      _ <- scan(rt)
      afterRung1 <- byName(rt, "silenttext-a")
      events <- readEvents(ws)
      _ <- waitUntil(20.seconds)(llm.turnCount.get.map(_ >= 2))
      reqs <- llm.requests.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(held.status, NodeLifecycle.Running,
        "non-anchored text must NOT be exempt (⑧-4 exempts only BlockedReader-anchored text)")
      assert(held.reportPendingSince.isDefined, "the clock must start for a silent finish")
      assertEquals(afterRung1.status, NodeLifecycle.Running, "still Running — the ladder never fails the node")
      assertEquals(afterRung1.reportReminderCount, 1, "rung 1 must fire for a held node")
      val miss = reminderEvents(events)
      assertEquals(miss.size, 1, s"exactly one node-report-missing event expected: ${events.mkString("|").take(400)}")
      val fields = NodeEngine.parseReportMissingSummary(miss.head)
      assertEquals(fields.get("stage"), Some(NodeEngine.NodeReportStageActive))
      assertEquals(fields.get("rung"), Some("1/3"))
      assertEquals(fields.get("delivered"), Some("true"))
      assert(reqs.exists(_.contains(NodeEngine.NodeReportReminderPrefix)),
        "the reminder must be delivered into the live session's context")
  }

end NodeReportReminderSpec
