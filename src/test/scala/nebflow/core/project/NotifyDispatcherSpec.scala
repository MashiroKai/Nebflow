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
 * dispatch-notify（节点终态结果回流分发器）验收（2026-09-05 批 completion；
 * 2026-09-07 批扩展 failed 接线——设计 20260907_node-failure-event-dispatcher-routing.md）：
 *
 * - ① 显式开启节点完成 → 分发器会话被触发，通知文本含节点名/终态/原因码；
 *   结果全文可达（results/<id>.md 持久化 + NodeList detail 指引）
 * - ② 未开启节点零触发；out=Nebula 投递零回归
 * - ③ 防循环收敛（completion）：同节点去重（notifySentAt 持久标记）；链级预算耗尽 →
 *   节点**保持 completed + 落 notifySentAt 止重扫 + 单条监督通知（notice 语义非
 *   blocked）**；预算按回合边界重置，single-flight 仅首次一条
 * - ④ 单一入口组合不双触发：blocked 仍接口预留零动作（FeedbackRouter 独占）；
 *   failed 已接线（状态门控、不查 flag）——状态不匹配（blocked 节点 + Failed
 *   reason）零动作
 * - ⑤ 重启补投（completion）：未标记欠账由 redeliver 扫描补投；已标记不重触发
 * - ⑥ NodeEdit notifyDispatcher 开关机制：create 开启 / edit 撤销 / 域校验 /
 *   载荷条件字段 / 缺省关
 * - ⑦-⑫ failed 用例组（2026-09-07 批）：同节点去重 / 预算分账与耗尽 /
 *   窗口熔断（Suppress 不标记、冷却结束补投不丢失）/ redeliver 欠账 /
 *   重激活后再失败必须再通知（inFlight 释放 + notifySentAt 清零配套）
 * - ⑬-⑮ failed 集成（真实引擎 zombie 收敛路径）：out=Nebula 双收（Nebula
 *   eventType=failed + 分发器同收）/ out=None 无 out 依赖 / out=节点下游 D5 零结算
 *   停等 + 分发器触发（附等待者清单，20260908 wf1cde E-③）
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

  /** 单测装配：真实 FlowMapStore（temp 盘）+ stub 触发/升级捕获（failed 窗口参数
    * 可注入 tiny 值验证状态机，同 FeedbackRouterSpec 先例）。 */
  private def mkUnit(
    name: String,
    budgetMax: Int = DispatchNotify.DefaultBudget,
    failedBudgetMax: Int = DispatchNotify.DefaultBudget,
    failedWindowMs: Long = DispatchNotify.FailedWindowMs,
    failedCooldownMs: Long = DispatchNotify.FailedCooldownMs,
    failedWindowThreshold: Int = DispatchNotify.FailedWindowThreshold
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
        budgetMax = budgetMax,
        failedBudgetMax = failedBudgetMax,
        failedWindowMs = failedWindowMs,
        failedCooldownMs = failedCooldownMs,
        failedWindowThreshold = failedWindowThreshold
      )
    yield (store, dn, triggered, escalated, ws)

  /** 种一个死会话 running 节点（僵尸收敛路径——settleStaleRunningNodes 驱动自动
    * failed，2026-09-07 批 failed 通知的集成触发源；NodeDeadSessionAutoReapSpec 同款）。 */
  private def seedZombie(rt: ProjectRuntime, id: String, nodeName: String, task: String,
      out: List[OutEdge], in: List[String] = Nil): IO[Unit] =
    rt.store.mutate { s =>
      s.copy(nodes = s.nodes + (id -> NodeDef(
        id = id, name = nodeName, agent = "general", task = Some(task), out = out, in = in,
        status = NodeLifecycle.Running,
        startedAt = Some(System.currentTimeMillis() - 3_600_000),
        createdAt = System.currentTimeMillis() - 3_600_000)))
    }.void

  /** 种一个节点（可指定终态/标志/结果）。 */
  private def seed(store: FlowMapStore, id: String, name: String, status: String,
      notify: Boolean, result: Option[String], out: List[OutEdge] = List(OutEdge.nebula)): IO[Unit] =
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

  // ── ④：单一入口组合不双触发（blocked 仍预留零动作；failed 已接线、状态门控）──

  test("④ single-entry reason gate: blocked stays reserved no-op; failed wired (status-gated, flag-independent, no double trigger with FeedbackRouter)") {
    for
      (store, dn, triggered, escalated, _) <- mkUnit("reasongate")
      _ <- seed(store, "n-rg", "gate-node", NodeLifecycle.Blocked, notify = true, result = Some("[blocked:other] x"))
      _ <- seed(store, "n-rgf", "gate-failed", NodeLifecycle.Failed, notify = false, result = Some("boom"))
      blockedNode <- store.getNode("n-rg").map(_.get)
      failedNode <- store.getNode("n-rgf").map(_.get)
      _ <- dn.notifyTerminal(blockedNode, NotifyReason.Blocked)  // 仍预留：FeedbackRouter 独占 blocked 重入
      _ <- dn.notifyTerminal(blockedNode, NotifyReason.Failed)   // 状态不匹配（Blocked ≠ Failed）→ 零动作
      _ <- dn.notifyTerminal(failedNode, NotifyReason.Failed)    // failed 接线：不查 flag 也触发
      calls <- triggered.get
      esc <- escalated.get
      afterB <- store.getNode("n-rg").map(_.get)
      afterF <- store.getNode("n-rgf").map(_.get)
    yield
      assertEquals(calls.size, 1, "only the failed node triggers (blocked reason no-op; status mismatch no-op)")
      assert(calls.head.contains("gate-failed") && calls.head.contains("reason=failed"), s"failed trigger text, got: ${calls.headOption}")
      assertEquals(esc.size, 0, "no escalation from the reason gate")
      assertEquals(afterB.notifySentAt, None, "no marker written for blocked node")
      assertEquals(afterF.notifySentAt.isDefined, true, "marker set for failed node")
  }

  // ── ⑤：重启补投（未标记欠账补投；已标记不重触发）────────────────

  test("⑤ redeliver: unmarked completion gets re-triggered; marked (persisted) never re-triggers across restart") {
    for
      (store, dn, triggered, _, _) <- mkUnit("redeliver")
      _ <- seed(store, "n-r1", "redeliver-one", NodeLifecycle.Completed, notify = true, result = Some("R"))
      _ <- seed(store, "n-r2", "redeliver-two", NodeLifecycle.Completed, notify = true, result = Some("R"))
      // 模拟「已通知并标记」的节点（重启前已投）
      _ <- seed(store, "n-r3", "redeliver-marked", NodeLifecycle.Completed, notify = true, result = Some("R"),
        out = List(OutEdge.nebula)) // 显式双通报边（与缺省同形，可读性）
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
          status = NodeLifecycle.Wiring, out = List(OutEdge.nebula), createdAt = System.currentTimeMillis()))))
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

  // ── ⑦-⑫：failed 接线单测（2026-09-07 批，设计 §11.2-11.5/11.8）────────

  test("⑦ failed dedup: same node notifies exactly once (in-flight claim + persisted marker; flag-independent)") {
    for
      (store, dn, triggered, _, _) <- mkUnit("failed-dedup")
      _ <- seed(store, "n-fdup", "fdup-node", NodeLifecycle.Failed, notify = false, result = Some("err-x"))
      seeded <- store.getNode("n-fdup").map(_.getOrElse(fail("seed must land")))
      _ <- dn.notifyTerminal(seeded, NotifyReason.Failed)
      _ <- dn.notifyTerminal(seeded, NotifyReason.Failed) // 第二次必须被去重
      calls <- triggered.get
      after <- store.getNode("n-fdup").map(_.getOrElse(fail("node must exist")))
    yield
      assertEquals(calls.size, 1, "second notify for same node must be deduped")
      assert(calls.head.contains("fdup-node") && calls.head.contains("reason=failed"), s"failed trigger text, got: ${calls.headOption}")
      assertEquals(after.notifySentAt.isDefined, true, "marker persisted after first trigger")
  }

  test("⑧ failed ledger split: completion budget exhaustion does NOT consume failed budget (independent Refs)") {
    for
      (store, dn, triggered, escalated, _) <- mkUnit("failed-split", budgetMax = 1)
      _ <- seed(store, "n-c1", "c-one", NodeLifecycle.Completed, notify = true, result = Some("R1"))
      _ <- seed(store, "n-c2", "c-two", NodeLifecycle.Completed, notify = true, result = Some("R2"))
      _ <- seed(store, "n-f1", "f-one", NodeLifecycle.Failed, notify = false, result = Some("E1"))
      c1 <- store.getNode("n-c1").map(_.get)
      c2 <- store.getNode("n-c2").map(_.get)
      f1 <- store.getNode("n-f1").map(_.get)
      _ <- dn.notifyTerminal(c1, NotifyReason.Completion)  // completion 预算 1/1 → 触发
      _ <- dn.notifyTerminal(c2, NotifyReason.Completion)  // completion 预算耗尽 → 升级
      _ <- dn.notifyTerminal(f1, NotifyReason.Failed)      // failed 预算独立分账 → 照常触发
      calls <- triggered.get
      esc <- escalated.get
    yield
      assertEquals(calls.count(_.contains("reason=completion")), 1, "completion: 1 trigger (budget=1)")
      assertEquals(calls.count(_.contains("reason=failed")), 1, "failed budget must be independent of completion exhaustion")
      assertEquals(esc.size, 1, "one completion-budget notice only")
      assert(esc.head.contains("预算耗尽"), s"notice must carry budget reason, got: ${esc.headOption}")
  }

  test("⑨ failed budget exhaustion: 3rd failed (budget=2) not triggered, kept failed, single notice (notice semantics), no rescan") {
    for
      (store, dn, triggered, escalated, ws) <- mkUnit("failed-budget", failedBudgetMax = 2)
      _ <- seed(store, "n-fb1", "fb-one", NodeLifecycle.Failed, notify = false, result = Some("E1"))
      _ <- seed(store, "n-fb2", "fb-two", NodeLifecycle.Failed, notify = false, result = Some("E2"))
      _ <- seed(store, "n-fb3", "fb-three", NodeLifecycle.Failed, notify = false, result = Some("E3"))
      b1 <- store.getNode("n-fb1").map(_.get)
      b2 <- store.getNode("n-fb2").map(_.get)
      b3 <- store.getNode("n-fb3").map(_.get)
      _ <- dn.notifyTerminal(b1, NotifyReason.Failed)
      _ <- dn.notifyTerminal(b2, NotifyReason.Failed)
      _ <- dn.notifyTerminal(b3, NotifyReason.Failed) // 预算 2 已耗尽 → 保持 failed + 单条 notice
      calls <- triggered.get
      esc <- escalated.get
      b3After <- store.getNode("n-fb3").map(_.getOrElse(fail("fb3 must exist")))
      audit <- readAuditTypes(ws)
      _ <- dn.redeliver() // 止重扫：已标记 → 不再重投/重报
      callsAfterScan <- triggered.get
    yield
      assertEquals(calls.size, 2, "failed budget=2 → exactly 2 triggers")
      assertEquals(b3After.status, NodeLifecycle.Failed, "budget-exhausted node must STAY failed (terminal not flipped)")
      assertEquals(b3After.notifySentAt.isDefined, true, "notifySentAt set → exits redeliver candidate set")
      assertEquals(esc.size, 1, "exactly one supervisor notice (single-flight)")
      assert(esc.head.contains("failed 通知预算耗尽"), s"notice must carry failed-budget reason, got: ${esc.head}")
      assert(!esc.head.contains("blocked"), "notice must NOT use blocked semantics")
      assert(audit.exists((t, id) => t == "dispatch-notify" && id == "n-fb3"), s"failed budget-exhausted audit for fb3, got: $audit")
      assertEquals(callsAfterScan.size, 2, "no rescan: budget-exhausted node not re-notified")
  }

  test("⑩ failed window circuit breaker: threshold → merged escalation + cooldown; suppressed/cooldown-on unmarked; cooldown end → redeliver delivers (not lost)") {
    for
      (store, dn, triggered, escalated, _) <- mkUnit("failed-window",
        failedWindowThreshold = 3, failedWindowMs = 250, failedCooldownMs = 300)
      _ <- seed(store, "n-w1", "w-one", NodeLifecycle.Failed, notify = false, result = Some("E1"))
      _ <- seed(store, "n-w2", "w-two", NodeLifecycle.Failed, notify = false, result = Some("E2"))
      _ <- seed(store, "n-w3", "w-three", NodeLifecycle.Failed, notify = false, result = Some("E3"))
      _ <- seed(store, "n-w4", "w-four", NodeLifecycle.Failed, notify = false, result = Some("E4"))
      w1 <- store.getNode("n-w1").map(_.get)
      w2 <- store.getNode("n-w2").map(_.get)
      w3 <- store.getNode("n-w3").map(_.get)
      w4 <- store.getNode("n-w4").map(_.get)
      _ <- dn.notifyTerminal(w1, NotifyReason.Failed)  // 窗口 1 → 触发
      _ <- dn.notifyTerminal(w2, NotifyReason.Failed)  // 窗口 2 → 触发
      _ <- dn.notifyTerminal(w3, NotifyReason.Failed)  // 窗口 3 ≥ 阈值 → CooldownOn：合并升级 + 冷却
      _ <- dn.notifyTerminal(w4, NotifyReason.Failed)  // 冷却期内 → Suppress：不标记不触发
      calls1 <- triggered.get
      esc1 <- escalated.get
      w3a <- store.getNode("n-w3").map(_.get)
      w4a <- store.getNode("n-w4").map(_.get)
      _ <- IO.sleep(600.millis) // 窗口（250ms）+ 冷却（300ms）双过期
      scan <- dn.redeliver()    // 回合边界：failed 预算重置；w3/w4 未标记 → 补投
      calls2 <- triggered.get
      w3b <- store.getNode("n-w3").map(_.get)
      w4b <- store.getNode("n-w4").map(_.get)
    yield
      assertEquals(calls1.size, 2, "w1/w2 triggered; w3 cooldown-on (escalated), w4 suppressed")
      assertEquals(esc1.size, 1, "merged single escalation on threshold")
      assert(esc1.head.contains("冷却"), s"escalation must carry cooldown wording, got: ${esc1.head}")
      assert(esc1.head.contains("w-three") && esc1.head.contains("n-w3"), "escalation carries latest failure node")
      assertEquals(w3a.notifySentAt, None, "cooldown-on node unmarked → stays in candidate set")
      assertEquals(w4a.notifySentAt, None, "suppressed node unmarked (not lost, deferred)")
      assertEquals(scan, 2, "redeliver sees w3+w4 after cooldown")
      assertEquals(calls2.size, 4, "w3/w4 delivered after cooldown ends (delayed, not lost)")
      assert(w3b.notifySentAt.isDefined && w4b.notifySentAt.isDefined, "marked after post-cooldown delivery")
  }

  test("⑪ failed redeliver backlog: unmarked failed re-triggered by scan; marked never re-triggered (across restart)") {
    for
      (store, dn, triggered, _, _) <- mkUnit("failed-redeliver")
      _ <- seed(store, "n-fr1", "fr-one", NodeLifecycle.Failed, notify = false, result = Some("E"))
      _ <- seed(store, "n-fr2", "fr-two", NodeLifecycle.Failed, notify = false, result = Some("E"))
      // 模拟「已通知并标记」的节点（重启前已投）
      _ <- store.mutate(s => s.copy(nodes = s.nodes.updated("n-fr2",
        s.nodes("n-fr2").copy(notifySentAt = Some(System.currentTimeMillis()))))).void
      scan1 <- dn.redeliver()
      count1 <- triggered.get.map(_.size)
      _ <- dn.redeliver()
      count2 <- triggered.get.map(_.size)
      // 重启模拟：全新实例（进程态清零），持久标记仍在 → 不重触发
      triggered2 <- Ref.of[IO, List[String]](Nil)
      dn2 = new DispatchNotify(store, tempRoot.toString, store.project,
        escalate = (_, _) => IO.unit, emitUpdated = (_: NodeDef) => IO.unit,
        trigger = t => triggered2.update(_ :+ t), budgetMax = DispatchNotify.DefaultBudget)
      _ <- dn2.redeliver()
      count3 <- triggered2.get.map(_.size)
      fr1 <- store.getNode("n-fr1").map(_.get)
      fr2 <- store.getNode("n-fr2").map(_.get)
    yield
      assertEquals(scan1, 1, "scan sees exactly 1 unmarked failed candidate (fr1)")
      assertEquals(count1, 1, "exactly one trigger from scan (fr1)")
      assertEquals(count2, 1, "second scan: nothing new")
      assertEquals(count3, 0, "fresh instance (restart): persisted markers prevent re-trigger")
      assertEquals(fr1.notifySentAt.isDefined, true, "fr1 marked after delivery")
      assertEquals(fr2.notifySentAt.isDefined, true, "fr2 (pre-marked) untouched")
  }

  test("⑫ failed re-notify after reactivation: in-flight released + notifySentAt cleared → second real failure notifies again (retry loop, not cycle)") {
    for
      (store, dn, triggered, _, _) <- mkUnit("failed-renotify")
      _ <- seed(store, "n-rn", "rn-node", NodeLifecycle.Failed, notify = false, result = Some("err-1"))
      n1 <- store.getNode("n-rn").map(_.get)
      _ <- dn.notifyTerminal(n1, NotifyReason.Failed) // 触发 #1 + markSent（attempt 结束释放 inFlight）
      // 模拟 NodeEdit failed 重激活复位（NodeTools 事务同款字段）：notifySentAt 清零
      _ <- store.mutate(s => s.copy(nodes = s.nodes.updated("n-rn",
        s.nodes("n-rn").copy(notifySentAt = None)))).void
      n2 <- store.getNode("n-rn").map(_.get)
      _ <- dn.notifyTerminal(n2, NotifyReason.Failed) // 重跑后的第二次真失败 → 必须再通知
      calls <- triggered.get
    yield
      assertEquals(calls.size, 2, "reactivated node's second real failure must notify again — inFlight must not block forever")
      assert(calls.forall(_.contains("reason=failed")), "both notifies carry failed reason")
  }

  // ── ⑬-⑮：failed 集成（真实引擎 zombie 收敛 → deliverFailed 尾部挂接）────

  test("⑬ failed (out=Nebula) double delivery: Nebula gets eventType=failed AND dispatcher triggered; notify text/audit/marker asserts") {
    val ws = tempRoot / "ws-fail-nebula"
    os.makeDir.all(ws)
    val system = ActorSystem(s"ntf-fn-${scala.util.Random.nextInt(100000)}")
    val llm = FuncLlm(text => if text.contains("任务分发器") then IO.pure("ok") else IO.pure("x"))
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountReal("ntf-fn", ws, system, res)
      nebula <- registerNebulaCapture(res, system)
      _ <- seedZombie(rt, "n-fn1", "fail-nebula", "dead task", List(OutEdge.nebula))
      _ <- rt.engine.settleStaleRunningNodes()
      // out=Nebula 投递（eventType=failed）零回归
      _ <- waitUntil(20.seconds)(nebula.get.map(_.exists((t, ev) =>
        t.contains("[Node 'fail-nebula' failed]") && ev.contains("failed"))))
      // 分发器触发（spawn prompt 捕获）
      _ <- waitUntil(20.seconds)(llm.inputs.get.map(_.exists(p => p.contains("[dispatch-notify]") && p.contains("fail-nebula"))))
      prompts <- llm.inputs.get
      nodeId <- idOf(rt, "fail-nebula")
      node <- rt.store.snapshot.map(_.nodes(nodeId))
      audit <- readAuditTypes(ws)
      deliveries <- nebula.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      val prompt = prompts.find(p => p.contains("[dispatch-notify]") && p.contains("fail-nebula"))
        .getOrElse(fail("failed notify prompt must be captured"))
      assert(prompt.contains(s"($nodeId)"), s"prompt must carry node id, got: ${prompt.take(200)}")
      assert(prompt.contains("reason=failed"), "prompt must carry failed reason code")
      assert(prompt.contains("错误摘要"), "prompt must carry err summary section")
      assert(prompt.contains("no live session"), "prompt must carry the actual error text")
      assert(prompt.contains("NodeList(detail=") && prompt.contains(nodeId), "prompt must carry result read hint (NodeList detail)")
      assert(prompt.contains("reactivate"), "prompt must teach reactivate rerun (ruling ③: failed IS reactivatable)")
      assert(prompt.contains("承接节点"), "prompt must teach rename-new fallback action")
      assert(prompt.contains("abandon=true"), "prompt must teach abandon action")
      assert(prompt.contains("Nebula"), "prompt must teach escalate-to-Nebula action")
      assert(prompt.contains("无下游等待者"), "prompt must carry waiter line (E-③: none for out=Nebula leaf)")
      assertEquals(node.notifySentAt.isDefined, true, "notifySentAt marker must be set after failed trigger")
      assert(audit.exists((t, id) => t == "dispatch-notify" && id == nodeId), s"dispatch-notify failed audit must exist, got: $audit")
      assert(deliveries.exists((t, ev) => t.contains("[Node 'fail-nebula' failed]") && ev.contains("failed")),
        "out=Nebula failed delivery must be unchanged (Nebula + dispatcher double delivery)")
  }

  test("⑭ failed (out=None): dispatcher still triggered (no out-wiring dependency); no Nebula delivery for the node") {
    val ws = tempRoot / "ws-fail-dangling"
    os.makeDir.all(ws)
    val system = ActorSystem(s"ntf-fd-${scala.util.Random.nextInt(100000)}")
    val llm = FuncLlm(text => if text.contains("任务分发器") then IO.pure("ok") else IO.pure("x"))
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountReal("ntf-fd", ws, system, res)
      nebula <- registerNebulaCapture(res, system)
      _ <- seedZombie(rt, "n-fd1", "fail-dangling", "dead task", Nil)
      _ <- rt.engine.settleStaleRunningNodes()
      _ <- waitUntil(20.seconds)(llm.inputs.get.map(_.exists(p => p.contains("[dispatch-notify]") && p.contains("fail-dangling"))))
      nodeId <- idOf(rt, "fail-dangling")
      node <- rt.store.snapshot.map(_.nodes(nodeId))
      deliveries <- nebula.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(node.status, NodeLifecycle.Failed, "zombie converged to failed")
      assertEquals(node.notifySentAt.isDefined, true, "dispatcher notified despite dangling out (no out dependency)")
      // out=None：无节点结果投递（「[Node '…' failed]」形态 / eventType=failed 均不得
      // 出现）。分发器最终输出投递的 header 会带任务摘要（含节点名）——那是 dispatcher
      // 输出通道（eventType=completed），不是节点结果投递，不在此断言范围。
      assert(!deliveries.exists((t, ev) => t.contains("[Node 'fail-dangling'") || ev.contains("failed")),
        s"out=None: no node-result delivery for this node, got: $deliveries")
  }

  test("⑮ failed (out=node): downstream stays waiting (D5 zero-settlement) AND dispatcher triggered with waiter list") {
    val ws = tempRoot / "ws-fail-target"
    os.makeDir.all(ws)
    val system = ActorSystem(s"ntf-ft-${scala.util.Random.nextInt(100000)}")
    val llm = FuncLlm(text => if text.contains("任务分发器") then IO.pure("ok") else IO.pure("dn-done"))
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountReal("ntf-ft", ws, system, res)
      _ <- seedZombie(rt, "n-ft-up", "fail-up", "dead upstream task", List(OutEdge("n-ft-dn")))
      _ <- rt.store.mutate { s =>
        s.copy(nodes = s.nodes + ("n-ft-dn" -> NodeDef(
          id = "n-ft-dn", name = "down-node", agent = "general",
          task = Some("downstream work"), out = List(OutEdge.nebula), in = List("n-ft-up"),
          status = NodeLifecycle.Wiring, createdAt = System.currentTimeMillis() - 3_600_000))) }.void
      _ <- rt.engine.settleStaleRunningNodes()
      // D5 零结算：下游停等不启动；分发器通知照发（settle-then-notify 顺序不变）
      _ <- waitUntil(20.seconds)(llm.inputs.get.map(_.exists(p => p.contains("[dispatch-notify]") && p.contains("fail-up"))))
      _ <- IO.sleep(500.millis) // 给「假如 collect 仍在异步启动下游」留观察窗口
      upId <- idOf(rt, "fail-up")
      up <- rt.store.snapshot.map(_.nodes(upId))
      dn <- idOf(rt, "down-node").flatMap(id => rt.store.snapshot.map(_.nodes(id)))
      prompt <- llm.inputs.get.map(_.find(p => p.contains("[dispatch-notify]") && p.contains("fail-up")))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(up.status, NodeLifecycle.Failed, "upstream failed")
      assertEquals(up.notifySentAt.isDefined, true, "dispatcher notified for failed upstream (settle-then-notify order)")
      // D5 翻转（原 collect 断言）：下游停等零结算——不启动、无 result、无占位键
      assertEquals(dn.status, NodeLifecycle.Wiring, "downstream stays wiring (D5 zero-settlement — collect placeholder abolished)")
      assertEquals(dn.result, None, "downstream never started (no session run)")
      assertEquals(dn.deliveredTo, Nil, "failed upstream must not write deliveredTo key (wf1cde §3.2 hole source)")
      // E-③：通知文本附停等等待者清单（此处恰为 down-node）
      assert(prompt.exists(p => p.contains("下游等待者") && p.contains("down-node")),
        "failed notify must carry waiter list naming the waiting successor (E-③)")
  }

end NotifyDispatcherSpec
