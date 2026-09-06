package nebflow.core.project

import cats.effect.{IO, Ref}
import fs2.Stream
import io.circe.Json
import io.circe.parser.parse as jsonParse
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
 * dispatch-notify（节点终态结果回流分发器）验收（2026-09-05 批）：
 *
 * - ① 显式开启节点完成 → 分发器会话被触发，通知文本含节点名/终态/原因码；
 *   结果全文可达（results/<id>.md 持久化 + NodeList detail 指引）
 * - ② 未开启节点零触发；out=Nebula 投递零回归
 * - ③ 防循环收敛：同节点去重（notifySentAt 持久标记）；链级预算耗尽 → 节点**保持
 *   completed + 落 notifySentAt 止重扫 + 单条监督通知（notice 语义非 blocked）**；
 *   预算按回合边界重置，single-flight 仅首次一条
 * - ④ 单一入口组合不双触发：未接线 reason（blocked/failed）接口层零动作
 *   （blocked 重入由 FeedbackRouter 独占）
 * - ⑤ 重启补投：未标记欠账由 redeliver 扫描补投；已标记（持久化）不重触发
 * - ⑥ NodeEdit notifyDispatcher 开关机制：create 开启 / edit 撤销 / 域校验 /
 *   载荷条件字段 / 缺省关
 */
class NotifyDispatcherSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 240.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-notify-dispatcher"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  for agent <- List("test-agent", "project-dispatcher", "general") do
    os.makeDir.all(tempRoot / "agents" / agent)
    os.write.over(
      tempRoot / "agents" / agent / "agent.json",
      s"""{"name":"$agent","description":"dispatch-notify regression agent","tools":[],"category":"standalone"}"""
    )
    os.write.over(tempRoot / "agents" / agent / "system.md", s"# $agent\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  /** 按输入内容响应的 LLM：inputs 记录全部 user 文本（分发器 prompt 捕获点）。 */
  private class FuncLlm(respond: String => IO[String]):
    val inputs: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)
    def handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(
          req: LlmRequest,
          onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
      ): Stream[IO, StreamChunk] =
        val text = req.messages.map(_.textContent).mkString("\n")
        Stream
          .eval(inputs.update(_ :+ text))
          .flatMap(_ => Stream.eval(respond(text)))
          .flatMap(reply => Stream(StreamChunk.TextDelta(reply), StreamChunk.Done(None, None)))

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

  private def mkCtx(res: SharedResources, system: ActorSystem, ws: String): ToolContext =
    ToolContext(
      projectRoot = ws,
      sessionId = Some("notify-sid"),
      rootSessionId = Some("nebula-root"),
      sharedResources = Some(res),
      actorSystem = Some(system)
    )

  private def nodeEdit(input: Json, ctx: ToolContext): IO[Either[String, String]] =
    NodeEditTool.call(input.asObject.get, ctx).map(_.left.map(_.message))

  private def waitUntil(timeout: FiniteDuration, every: Long = 50)(cond: IO[Boolean]): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError("waitUntil: condition not met in time"))
          else IO.sleep(every.millis) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  private def nodeInput(project: String, nodename: String, extra: (String, Json)*): Json =
    Json.obj(("project" -> Json.fromString(project)) :: ("nodename" -> Json.fromString(nodename)) :: extra.toList*)

  /** 完整挂载（真实 ProjectActor——notify 直触发 spawn 路径需要）。 */
  private def mountReal(name: String, ws: os.Path, system: ActorSystem, res: SharedResources): IO[ProjectRuntime] =
    val pd = ProjectDef(name = name, workspace = ws.toString, agentFile = (ws / "AGENTS.md").toString, createdAt = System.currentTimeMillis())
    ProjectRuntimeRegistry.mount(pd, system, res, None, "nebula-root")

  /** Nebula 根会话捕获 actor（out=Nebula 投递零回归断言点）。 */
  private def registerNebulaCapture(res: SharedResources, system: ActorSystem): IO[Ref[IO, List[(String, Option[String])]]] =
    Ref.of[IO, List[(String, Option[String])]](Nil).flatMap { captured =>
      lazy val captureBehavior: nebflow.actor.Behavior[AgentCommand] = Behaviors.receive[AgentCommand] { (_, msg) =>
        msg match
          case im: AgentCommand.ImmediateInput => captured.update(_ :+ (im.text -> im.eventType)).as(captureBehavior)
          case _                               => IO.pure(captureBehavior)
      }
      system.spawn(captureBehavior, s"nebula-capture-${scala.util.Random.nextInt(100000)}").flatMap { ref =>
        val now = System.currentTimeMillis()
        res.agentRegistry
          .update(_ + ("nebula-root" -> AgentRecord(
            sessionId = "nebula-root", ref = ref, kind = AgentKind.Root, rootSessionId = "nebula-root",
            startedAt = now, lastActivityMs = now)))
          .as(captured)
      }
    }

  private def idOf(rt: ProjectRuntime, name: String): IO[String] =
    rt.store.snapshot.map(_.nodes.values.find(_.name == name)).map {
      case Some(n) => n.id
      case None    => fail(s"node '$name' must exist")
    }

  private def waitStatus(rt: ProjectRuntime, name: String, statuses: Set[String]): IO[Unit] =
    waitUntil(20.seconds) {
      rt.store.snapshot.map(_.nodes.values.find(_.name == name)).flatMap {
        case Some(n) => IO.pure(statuses.contains(n.status))
        case None    => IO.pure(false)
      }
    }

  private def readAuditTypes(ws: os.Path): IO[List[(String, String)]] =
    IO.blocking(os.read(ws / ".nebflow" / FlowMapEventLog.FileName))
      .map(_.linesIterator.toList.filter(_.trim.nonEmpty))
      .map(lines => lines.flatMap(l => jsonParse(l).toOption.map(j =>
        (j.hcursor.get[String]("type").getOrElse(""), j.hcursor.get[String]("nodeId").getOrElse("")))))
      .handleError(_ => Nil)

  /** 单测装配：真实 FlowMapStore（temp 盘）+ stub 触发/升级捕获。 */
  private def mkUnit(
    name: String,
    budgetMax: Int = DispatchNotify.DefaultBudget
  ): IO[(FlowMapStore, DispatchNotify, Ref[IO, List[String]], Ref[IO, List[String]], os.Path)] =
    val ws = tempRoot / s"unit-$name-${scala.util.Random.nextInt(100000)}"
    os.makeDir.all(ws)
    for
      store <- FlowMapStore.open(s"notify-$name", ws.toString)
      triggered <- Ref.of[IO, List[String]](Nil)
      escalated <- Ref.of[IO, List[String]](Nil)
      dn = new DispatchNotify(
        store, ws.toString, s"notify-$name",
        escalate = (text, _) => escalated.update(_ :+ text),
        emitUpdated = (_: NodeDef) => IO.unit,
        trigger = text => triggered.update(_ :+ text),
        budgetMax = budgetMax
      )
    yield (store, dn, triggered, escalated, ws)

  /** 种一个节点（可指定终态/标志/结果）。 */
  private def seed(store: FlowMapStore, id: String, name: String, status: String,
      notify: Boolean, result: Option[String], out: Option[String] = Some("Nebula")): IO[Unit] =
    store.mutate(s => s.copy(nodes = s.nodes + (id -> NodeDef(
      id = id, name = name, agent = "general", status = status, result = result,
      notifyDispatcher = notify, out = out, createdAt = System.currentTimeMillis())))).void

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  // ── ①：显式开启节点完成 → 分发器触发 + 通知文本含节点名/终态/原因码 + 结果可达 ──

  test("① notify-enabled node completion triggers dispatcher session (name/terminal/reason code in task text; result persisted + read hint)") {
    val ws = tempRoot / "ws-notify-hit"
    os.makeDir.all(ws)
    val system = ActorSystem(s"ntf-hit-${scala.util.Random.nextInt(100000)}")
    val llm = FuncLlm(text => if text.contains("任务分发器") then IO.pure("ok") else IO.pure("ok-final-result"))
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountReal("ntf-hit", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- nodeEdit(nodeInput("ntf-hit", "notify-one", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("notify-one"), "out" -> Json.fromString("Nebula"),
        "notifyDispatcher" -> Json.fromBoolean(true)), ctx)
      _ <- waitStatus(rt, "notify-one", Set(NodeLifecycle.Completed))
      // 分发器 spawn prompt 捕获（通知文本经 newTaskPrompt 注入）
      _ <- waitUntil(20.seconds)(llm.inputs.get.map(_.exists(_.contains("[dispatch-notify]"))))
      prompts <- llm.inputs.get
      nodeId <- idOf(rt, "notify-one")
      node <- rt.store.snapshot.map(_.nodes(nodeId))
      audit <- readAuditTypes(ws)
      resultFile = ws / ".nebflow" / "results" / s"$nodeId.md"
      // 结果文件由 completedNode 的 mutate→persistState→writeResultFiles 同步写；防御性
      // 确定性等待，避免瞬时读到未落盘文件抛错
      _ <- waitUntil(20.seconds)(IO.delay(os.exists(resultFile)))
      resultText <- IO.blocking(os.read(resultFile))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      val prompt = prompts.find(_.contains("[dispatch-notify]")).getOrElse(fail("notify prompt must be captured"))
      assert(prompt.contains("notify-one") && prompt.contains(nodeId), s"prompt must carry node name+id")
      assert(prompt.contains("reason=completion"), s"prompt must carry reason code")
      assert(prompt.contains("completed"), s"prompt must carry terminal state")
      assert(prompt.contains("NodeList(detail="), s"prompt must carry result read hint")
      assert(prompt.contains("ntf-hit"), s"prompt must carry project name")
      assertEquals(node.notifySentAt.isDefined, true, "notifySentAt marker must be set after trigger")
      assertEquals(resultText, "ok-final-result", "full result must be persisted to per-node file")
      assert(audit.exists((t, id) => t == "dispatch-notify" && id == nodeId), s"dispatch-notify audit line must exist, got: $audit")
  }

  // ── ②：未开启节点零触发；out=Nebula 零回归 ─────────────────────

  test("② non-flagged node: no dispatcher notify; out=Nebula delivery unchanged (zero regression)") {
    val ws = tempRoot / "ws-notify-off"
    os.makeDir.all(ws)
    val system = ActorSystem(s"ntf-off-${scala.util.Random.nextInt(100000)}")
    val llm = FuncLlm(text => if text.contains("任务分发器") then IO.pure("ok") else IO.pure("plain-result"))
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountReal("ntf-off", ws, system, res)
      nebula <- registerNebulaCapture(res, system)
      ctx = mkCtx(res, system, ws.toString)
      _ <- nodeEdit(nodeInput("ntf-off", "plain-one", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("plain-one"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- waitStatus(rt, "plain-one", Set(NodeLifecycle.Completed))
      // 确定性同步：等 out=Nebula 完成投递实际到达（等具体事件文本+事件类型，替代固定 sleep
      // 与「恰好一次」之间的时序假设；引擎去重兜底，这里是回归断言而非时序依赖）
      _ <- waitUntil(20.seconds)(nebula.get.map(_.count { case (t, ev) =>
        t.contains("[Node 'plain-one' completed]") && ev.contains("completed")
      } == 1))
      prompts <- llm.inputs.get
      deliveries <- nebula.get
      nodeId <- idOf(rt, "plain-one")
      node <- rt.store.snapshot.map(_.nodes(nodeId))
      audit <- readAuditTypes(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(!prompts.exists(_.contains("[dispatch-notify]")), "non-flagged node must NOT trigger dispatcher notify")
      assert(!node.notifyDispatcher, "flag must default off")
      assertEquals(node.notifySentAt, None, "no marker for non-flagged node")
      assert(!audit.exists((t, _) => t == "dispatch-notify"), "no dispatch-notify audit for non-flagged node")
      // out=Nebula 零回归：完成投递照常到达 Nebula 根会话
      val delivered = deliveries.filter((t, ev) => t.contains("[Node 'plain-one' completed]") && ev.contains("completed"))
      assertEquals(delivered.size, 1, "out=Nebula completion delivery must be unchanged")
  }

  // ── ③：同节点去重 + 预算耗尽 → blocked + 升级 ──────────────────

  test("③ dedup: same node notifies exactly once (in-flight claim + persisted marker)") {
    for
      (store, dn, triggered, _, _) <- mkUnit("dedup")
      _ <- seed(store, "n-dup", "dup-node", NodeLifecycle.Completed, notify = true, result = Some("R"))
      seeded <- store.getNode("n-dup").map(_.getOrElse(fail("seed must land")))
      _ <- dn.notifyTerminal(seeded, NotifyReason.Completion)
      _ <- dn.notifyTerminal(seeded, NotifyReason.Completion) // 第二次必须被去重
      calls <- triggered.get
      after <- store.getNode("n-dup").map(_.getOrElse(fail("node must exist")))
    yield
      assertEquals(calls.size, 1, "second notify for same node must be deduped")
      assert(calls.head.contains("dup-node"), "trigger text must carry node name")
      assertEquals(after.notifySentAt.isDefined, true, "marker persisted after first trigger")
  }

  test("③ budget: exhaustion keeps node Completed + single supervisor notice (notice, no fake blocked) + no rescan") {
    for
      (store, dn, triggered, escalated, ws) <- mkUnit("budget", budgetMax = 2)
      _ <- seed(store, "n-b1", "budget-one", NodeLifecycle.Completed, notify = true, result = Some("R1"))
      _ <- seed(store, "n-b2", "budget-two", NodeLifecycle.Completed, notify = true, result = Some("R2"))
      _ <- seed(store, "n-b3", "budget-three", NodeLifecycle.Completed, notify = true, result = Some("R3"))
      b1 <- store.getNode("n-b1").map(_.get)
      b2 <- store.getNode("n-b2").map(_.get)
      b3 <- store.getNode("n-b3").map(_.get)
      _ <- dn.notifyTerminal(b1, NotifyReason.Completion)
      _ <- dn.notifyTerminal(b2, NotifyReason.Completion)
      _ <- dn.notifyTerminal(b3, NotifyReason.Completion) // 预算 2 已耗尽 → 保持 completed + 单条监督
      calls <- triggered.get
      esc <- escalated.get
      b3After <- store.getNode("n-b3").map(_.getOrElse(fail("b3 must exist")))
      audit <- readAuditTypes(ws)
      // 止重扫：redeliver 不再重投/重报已标记的 b3（notifySentAt 已落）
      _ <- dn.redeliver()
      callsAfterScan <- triggered.get
      b3AfterScan <- store.getNode("n-b3").map(_.get)
    yield
      assertEquals(calls.size, 2, "budget=2 → exactly 2 triggers")
      assertEquals(b3After.status, NodeLifecycle.Completed, "budget-exhausted node must STAY completed (no fake blocked)")
      assertEquals(b3After.blockedFeedback, None, "completed node carries NO blocked feedback")
      assertEquals(b3After.notifySentAt.isDefined, true, "notifySentAt set → exits redeliver candidate set")
      assertEquals(esc.size, 1, "exactly one supervisor notice")
      assert(esc.head.contains("预算耗尽"), s"notice must carry budget reason, got: ${esc.head}")
      assert(!esc.head.contains("[Node 'budget-three' blocked]"), "notice must NOT use blocked semantics (kept completed)")
      assert(audit.exists((t, id) => t == "dispatch-notify" && id == "n-b1"), "trigger audit for b1")
      assert(audit.exists((t, id) => t == "dispatch-notify" && id == "n-b2"), "trigger audit for b2")
      assert(audit.exists((t, id) => t == "dispatch-notify" && id == "n-b3"), "budget-exhausted audit for b3")
      assertEquals(callsAfterScan.size, 2, "no rescan: budget-exhausted node not re-notified")
      assertEquals(b3AfterScan.notifySentAt.isDefined, true, "marker persists after scan")
  }

  test("③ budget round reset: next round inherits fresh budget (not exhausted count)") {
    for
      (store, dn, triggered, _, _) <- mkUnit("budget-round-reset", budgetMax = 1)
      _ <- seed(store, "n-rr1", "rr-one", NodeLifecycle.Completed, notify = true, result = Some("R1"))
      _ <- seed(store, "n-rr2", "rr-two", NodeLifecycle.Completed, notify = true, result = Some("R2"))
      rr1 <- store.getNode("n-rr1").map(_.get)
      rr2 <- store.getNode("n-rr2").map(_.get)
      _ <- dn.notifyTerminal(rr1, NotifyReason.Completion)  // trigger (budget 1/1)
      _ <- dn.notifyTerminal(rr2, NotifyReason.Completion)  // exhausted (budget 1/1)
      count1 <- triggered.get.map(_.size)
      _ <- dn.redeliver()   // 回合边界 → 预算重置
      _ <- seed(store, "n-rr3", "rr-three", NodeLifecycle.Completed, notify = true, result = Some("R3"))
      _ <- seed(store, "n-rr4", "rr-four", NodeLifecycle.Completed, notify = true, result = Some("R4"))
      rr3 <- store.getNode("n-rr3").map(_.get)
      rr4 <- store.getNode("n-rr4").map(_.get)
      _ <- dn.notifyTerminal(rr3, NotifyReason.Completion)  // trigger (fresh budget 0→1)
      _ <- dn.notifyTerminal(rr4, NotifyReason.Completion)  // exhausted (new round)
      count2 <- triggered.get.map(_.size)
    yield
      assertEquals(count1, 1, "round1 → 1 trigger (budget 1 exhausted)")
      assertEquals(count2, 2, "round2 → fresh budget, rr3 triggers (not inheriting exhausted count)")
  }

  test("③ budget single-flight: multiple exhaustions in process → exactly one supervisor notice") {
    for
      (store, dn, triggered, escalated, _) <- mkUnit("budget-single-flight", budgetMax = 1)
      _ <- seed(store, "n-s1", "sf-one", NodeLifecycle.Completed, notify = true, result = Some("R1"))
      _ <- seed(store, "n-s2", "sf-two", NodeLifecycle.Completed, notify = true, result = Some("R2"))
      _ <- seed(store, "n-s3", "sf-three", NodeLifecycle.Completed, notify = true, result = Some("R3"))
      s1 <- store.getNode("n-s1").map(_.get)
      s2 <- store.getNode("n-s2").map(_.get)
      s3 <- store.getNode("n-s3").map(_.get)
      _ <- dn.notifyTerminal(s1, NotifyReason.Completion)  // trigger (budget 1)
      _ <- dn.notifyTerminal(s2, NotifyReason.Completion)  // exhausted → 监督通知 #1
      _ <- dn.notifyTerminal(s3, NotifyReason.Completion)  // exhausted → single-flight 抑制（不重报）
      calls <- triggered.get
      esc <- escalated.get
      s2After <- store.getNode("n-s2").map(_.get)
      s3After <- store.getNode("n-s3").map(_.get)
    yield
      assertEquals(calls.size, 1, "budget=1 → 1 trigger")
      assertEquals(esc.size, 1, "single-flight: only ONE supervisor notice across multiple exhaustions")
      assertEquals(s2After.status, NodeLifecycle.Completed, "s2 stays completed")
      assertEquals(s3After.status, NodeLifecycle.Completed, "s3 stays completed")
      assertEquals(s2After.notifySentAt.isDefined, true, "s2 marked sent")
      assertEquals(s3After.notifySentAt.isDefined, true, "s3 marked sent")
  }

  // ── ④：单一入口组合不双触发（未接线 reason 接口层零动作）─────────

  test("④ single-entry reason gate: blocked/failed reasons are interface-reserved no-ops (no double trigger with FeedbackRouter)") {
    for
      (store, dn, triggered, escalated, _) <- mkUnit("reasongate")
      _ <- seed(store, "n-rg", "gate-node", NodeLifecycle.Blocked, notify = true, result = Some("[blocked:other] x"))
      seeded <- store.getNode("n-rg").map(_.get)
      _ <- dn.notifyTerminal(seeded, NotifyReason.Blocked)   // 预留：FeedbackRouter 独占 blocked 重入
      _ <- dn.notifyTerminal(seeded, NotifyReason.Failed)    // 预留：监督化批
      calls <- triggered.get
      esc <- escalated.get
      after <- store.getNode("n-rg").map(_.get)
    yield
      assertEquals(calls.size, 0, "reserved reasons must not trigger")
      assertEquals(esc.size, 0, "reserved reasons must not escalate")
      assertEquals(after.notifySentAt, None, "no marker written for reserved reasons")
  }

  // ── ⑤：重启补投（未标记欠账补投；已标记不重触发）────────────────

  test("⑤ redeliver: unmarked completion gets re-triggered; marked (persisted) never re-triggers across restart") {
    for
      (store, dn, triggered, _, _) <- mkUnit("redeliver")
      _ <- seed(store, "n-r1", "redeliver-one", NodeLifecycle.Completed, notify = true, result = Some("R"))
      _ <- seed(store, "n-r2", "redeliver-two", NodeLifecycle.Completed, notify = true, result = Some("R"))
      // 模拟「已通知并标记」的节点（重启前已投）
      _ <- seed(store, "n-r3", "redeliver-marked", NodeLifecycle.Completed, notify = true, result = Some("R"),
        out = Some("Nebula"))
      _ <- store.mutate(s => s.copy(nodes = s.nodes.updated("n-r3",
        s.nodes("n-r3").copy(notifySentAt = Some(System.currentTimeMillis()))))).void
      n1 <- store.getNode("n-r1").map(_.get)
      _ <- dn.notifyTerminal(n1, NotifyReason.Completion) // r1 直触发+标记
      count1 <- triggered.get.map(_.size)
      scan1 <- dn.redeliver()                              // r2 未标记 → 补投；r1/r3 已标记 → 跳过
      count2 <- triggered.get.map(_.size)
      _ <- dn.redeliver()                                  // 再扫：全部已标记 → 零触发
      count3 <- triggered.get.map(_.size)
      // 重启模拟：全新实例（进程态清零），持久标记仍在 → 不重触发
      dn2 = new DispatchNotify(store, tempRoot.toString, store.project,
        escalate = (_, _) => IO.unit, emitUpdated = (_: NodeDef) => IO.unit,
        trigger = _ => IO.unit, budgetMax = DispatchNotify.DefaultBudget)
      _ <- dn2.redeliver()
      count4 <- triggered.get.map(_.size)
      r3 <- store.getNode("n-r3").map(_.get)
    yield
      assertEquals(count1, 1, "direct trigger for r1")
      assertEquals(scan1, 1, "scan sees exactly 1 unmarked candidate (r2)")
      assertEquals(count2, 2, "exactly one more trigger from scan (r2)")
      assertEquals(count3, 2, "second scan: nothing new")
      assertEquals(count4, 2, "fresh instance (restart): persisted markers prevent re-trigger")
      assertEquals(r3.notifySentAt.isDefined, true, "marked node untouched")
  }

  // ── ⑥：NodeEdit notifyDispatcher 开关机制 ───────────────────────

  test("⑥ NodeEdit flag: create on / edit withdraw / domain guard (terminal refused, running allowed) / payload field / default off") {
    val ws = tempRoot / "ws-notify-flag"
    os.makeDir.all(ws)
    val system = ActorSystem(s"ntf-flag-${scala.util.Random.nextInt(100000)}")
    val llm = FuncLlm(text =>
      if text.contains("slow-runner") then IO.sleep(1200.millis).as("slow-ok")
      else if text.contains("任务分发器") then IO.pure("ok")
      else IO.pure("ok-final"))
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountReal("ntf-flag", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // create 显式开启
      _ <- nodeEdit(nodeInput("ntf-flag", "flagged", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("flagged"), "out" -> Json.fromString("Nebula"),
        "notifyDispatcher" -> Json.fromBoolean(true)), ctx)
      flaggedId <- idOf(rt, "flagged")
      flagged <- rt.store.snapshot.map(_.nodes(flaggedId))
      // create 缺省关（分发器新建节点默认不继承）
      _ <- nodeEdit(nodeInput("ntf-flag", "unflagged", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("unflagged"), "out" -> Json.fromString("Nebula")), ctx)
      unflaggedId <- idOf(rt, "unflagged")
      unflagged <- rt.store.snapshot.map(_.nodes(unflaggedId))
      // wiring 节点（store 直种）：edit 开 → 撤 → 载荷条件字段
      _ <- rt.store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        "n-wiring-w" -> NodeDef(id = "n-wiring-w", name = "wiring-w", agent = "general",
          status = NodeLifecycle.Wiring, out = Some("Nebula"), createdAt = System.currentTimeMillis()))))
      _ <- nodeEdit(nodeInput("ntf-flag", "wiring-w", "notifyDispatcher" -> Json.fromBoolean(true)), ctx)
      wOn <- rt.store.snapshot.map(_.nodes("n-wiring-w"))
      _ <- nodeEdit(nodeInput("ntf-flag", "wiring-w", "notifyDispatcher" -> Json.fromBoolean(false)), ctx)
      wOff <- rt.store.snapshot.map(_.nodes("n-wiring-w"))
      // running 节点合法（完成时行为开关）
      _ <- nodeEdit(nodeInput("ntf-flag", "slow-runner", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("slow-runner"), "out" -> Json.fromString("Nebula"),
        "notifyDispatcher" -> Json.fromBoolean(true)), ctx)
      _ <- waitStatus(rt, "slow-runner", Set(NodeLifecycle.Running))
      runningEdit <- nodeEdit(nodeInput("ntf-flag", "slow-runner", "notifyDispatcher" -> Json.fromBoolean(false)), ctx)
      // completed 节点拒绝（域守卫）
      _ <- waitStatus(rt, "flagged", Set(NodeLifecycle.Completed))
      completedRefused <- nodeEdit(nodeInput("ntf-flag", "flagged", "notifyDispatcher" -> Json.fromBoolean(true)), ctx)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(flagged.notifyDispatcher, true, "create with flag=true must persist")
      assertEquals(unflagged.notifyDispatcher, false, "default off (dispatcher-created nodes do not inherit)")
      assertEquals(wOn.notifyDispatcher, true, "edit set on wiring node")
      assertEquals(wOff.notifyDispatcher, false, "edit withdraw on wiring node")
      val payload = NodePayload.buildNodeJson(wOn, System.currentTimeMillis())
      assertEquals(payload.hcursor.get[Boolean]("notifyDispatcher").toOption, Some(true), "conditional payload field when on")
      val payloadOff = NodePayload.buildNodeJson(wOff, System.currentTimeMillis())
      assertEquals(payloadOff.hcursor.get[Boolean]("notifyDispatcher").toOption, None, "no field when off (zero drift)")
      assert(runningEdit.isRight, s"flag switch must be allowed on running node, got: $runningEdit")
      assert(completedRefused.isLeft, "flag switch must be refused on completed node")
      assert(completedRefused.left.exists(_.contains("wiring/pending/running")), s"refusal must name the domain, got: $completedRefused")
  }

end NotifyDispatcherSpec
