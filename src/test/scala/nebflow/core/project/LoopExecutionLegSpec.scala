package nebflow.core.project

import cats.effect.{IO, Ref}
import fs2.Stream
import io.circe.Json
import io.circe.parser.parse as jsonParse
import io.circe.syntax.*
import io.circe.JsonObject
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentLibrary, SharedResources}
import nebflow.core.PathUtil
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, ToolContext}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{ContentBlock, LlmHandle, LlmRequest, LlmResponse, StreamChunk, ToolCall}

import scala.concurrent.duration.*

/**
 * nrloop 二期「节点稳定性快修批」（作者 2026-09-14 11:17 插队令）的回归：三件修复
 * 各自的**旧代码必红**用例（逐条对齐任务书的三件判据）：
 *
 *  ① **reloopTo 执行腿**：verifier 判 fail ⇒ 目标**真实重激活**（状态离开
 *     `completed`、旧结果丢弃）+ 回边**真实投递**（`loop-round` 记
 *     `dispatched=reloopTo`，不再是 `deferred=execution-leg-phase-2`），且**驱动方
 *     复位**（verifier 回 `wiring` 并把目标那一轨从 `deliveredTo` 摘掉）——否则目标
 *     重跑完成后无人复核，回边只跑一轮即静默冻结。
 *  ② **轮预算耗尽显式终态**（两处机制根因）：
 *     (a) 轮次维**在消费后判定** ⇒ `3/3` 是**熔断轮**而非再派发轮（终态 + `loop-budget`
 *         事件 + 无重跑派发）；旧口径用消费前的计数 ⇒ 3/3 仍派发、要等第 4 次判词。
 *     (b) 熔断**不再被「起点缺失」静默吞掉**（旧口径 `clearLoopStartedAt` 返回 false
 *         即整条熔断跳过 —— 轮次维耗尽时若起点已被孤儿扫描清掉，节点不终态化、
 *         不发事件、不通知分发器）。
 *     (c) 时间维的驱动方判据 = **判词边**而非节点生命周期 ⇒ `completed` + `lastVerdict=fail`
 *         的 verifier 仍是驱动方（走熔断），**不再**被误判成孤儿（清表 + 零终态化）。
 *  ③ **settle-sweep 判词感知**：资格回扫的孤儿 barrier 自愈**不补投** `lastVerdict=fail`
 *     的 verifier 的「结果」（它是判词不是产物）⇒ 下游 sink 的 barrier 不结算、不被拉起；
 *     留痕 = `settle-sweep` 事件带 `skipped-by-verdict`（单发）。对照组：普通 task 上游
 *     照旧补投（无 verifier 上游的既有行为逐字不变）。
 *
 * 驱动方式（确定性，零真实 LLM）：`ToolCallLlm` 在 verifier 会话里发 `node_report(fail)`
 * 工具调用后收尾 ⇒ 真实引擎路径 `completeNode(declared=fail)` → `verifierFail`。
 * 目标节点一律**无 task**：`resetForLoop` 判据落 `wiring`、`startNode` 因零接线防御
 * 不 spawn ⇒ 断言面无会话竞态（本 spec 只验**派发/终态/挡投**三件事，不验目标会话本身
 * 的输入装配——那由 `startNode` 的 `loopRework` 参数单测面覆盖）。
 */
class LoopExecutionLegSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 240.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-loop-exec-leg"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "test-agent")
  os.write.over(tempRoot / "agents" / "test-agent" / "agent.json",
    """{"name":"test-agent","description":"loop exec leg agent","tools":[],"category":"standalone"}""")
  os.write.over(tempRoot / "agents" / "test-agent" / "system.md", "# test-agent\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  /** 工具申报状态机 LLM（`NodeBlockedToolSignalSpec` 同款；本 spec 只用 fail 面）：
    * 首 turn 发 `node_report(fail)`，见到回执后输出无锚定收尾文本 ⇒ 终态由**工具通道**
    * 驱动，而不是文本形态（证明 `verifierFail` 是被真实引擎路径调到的）。 */
  private class FailReportLlm(closing: String):
    val inputs: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)
    private val ackText = "[OK] verdict recorded (fail)"
    private def sawAck(req: LlmRequest): Boolean =
      req.messages.exists { m =>
        m.content match
          case Right(blocks) =>
            blocks.exists {
              case ContentBlock.ToolResult(_, content, _) => content.contains(ackText)
              case _                                      => false
            }
          case Left(t) => t.contains(ackText)
      }
    def handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(
          req: LlmRequest,
          onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
      ): Stream[IO, StreamChunk] =
        val text = req.messages.map(_.textContent).mkString("\n")
        if sawAck(req) then
          Stream.eval(inputs.update(_ :+ text)) >>
            Stream(StreamChunk.TextDelta(closing), StreamChunk.Done(None, None))
        else
          Stream.eval(inputs.update(_ :+ text)) >>
            Stream(
              StreamChunk.ToolCallChunk(ToolCall(
                "rb-fail-1", "node_report",
                JsonObject(
                  "category" -> "fail".asJson,
                  "detail" -> "artifact does not compile".asJson,
                  "suggestion" -> "re-run upstream after the dependency rollback".asJson))),
              StreamChunk.Done(Some("tool_use"), None))

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

  /** 引擎挂载（无 ProjectActor：所有扫描腿由本 spec 显式点名调用，零后台竞态）。 */
  private def mountEngineOnly(
    name: String,
    ws: os.Path,
    system: ActorSystem,
    res: SharedResources
  ): IO[ProjectRuntime] =
    for
      store <- FlowMapStore.open(name, ws.toString)
      engine = new NodeEngine(
        store, system, res,
        wsSendFn = (_: Json) => IO.unit,
        workspace = ws.toString,
        rootSessionId = "nebula-root",
        projectName = name,
        emitEvent = (_, _, _) => IO.unit,
        reportGateHold = Some(false)
      )
      pd = ProjectDef(name = name, workspace = ws.toString, agentFile = (ws / "AGENTS.md").toString, createdAt = System.currentTimeMillis())
      rt = ProjectRuntime(pd, store, engine, system, res, None)
      _ <- ProjectRuntimeRegistry.register(rt)
    yield rt

  private def seed(rt: ProjectRuntime, nodes: NodeDef*): IO[Unit] =
    rt.store.mutate(s => s.copy(nodes = s.nodes ++ nodes.map(n => n.id -> n).toMap)).void

  /** 直种节点（绕过 NodeEdit：本 spec 验的是运行期判据，不是创建期校验）。 */
  private def mkNode(
    id: String, name: String, status: String,
    role: String = NodeRoles.Task,
    in: List[String] = Nil,
    out: List[OutEdge] = Nil,
    result: Option[String] = None,
    deliveredTo: List[String] = Nil,
    deps: List[String] = Nil,
    lastVerdict: Option[String] = None,
    loopRound: Int = 0,
    loopStartedAt: Option[Long] = None,
    withTask: Boolean = false
  ): NodeDef =
    NodeDef(
      id = id, name = name, agent = "test-agent",
      task = if withTask then Some(s"$name task") else None,
      status = status, in = in, out = out, deps = deps, result = result,
      deliveredTo = deliveredTo, role = role, lastVerdict = lastVerdict,
      loopRound = loopRound, loopStartedAt = loopStartedAt,
      createdAt = System.currentTimeMillis())

  /** verifier 驱动方（`(fail)n-work:loop`）——被判对象的返工回边持有者。 */
  private def failVerifier(
    id: String, status: String,
    loopRound: Int = 0,
    lastVerdict: Option[String] = None,
    deliveredTo: List[String] = Nil
  ): NodeDef =
    mkNode(id, id, status, role = NodeRoles.Verifier,
      in = List("n-work"), out = List(OutEdge("n-land"), OutEdge("n-work", Set(OutEdge.Fail), OutEdge.Loop)),
      result = Some("verdict closing text"), deliveredTo = deliveredTo,
      lastVerdict = lastVerdict, loopRound = loopRound, withTask = true)

  private def readAudit(ws: os.Path): IO[List[(String, String, String)]] =
    IO.blocking(os.read(ws / ".nebflow" / FlowMapEventLog.FileName))
      .map(_.linesIterator.toList.filter(_.trim.nonEmpty))
      .map(lines => lines.flatMap(l => jsonParse(l).toOption.map(j =>
        (j.hcursor.get[String]("type").getOrElse(""),
         j.hcursor.get[String]("nodeId").getOrElse(""),
         j.hcursor.get[String]("summary").getOrElse("")))))
      .handleError(_ => Nil)

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 50.millis)(cond: IO[Boolean]): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError("waitUntil: condition not met in time"))
          else IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  private def waitTerminal(rt: ProjectRuntime, id: String): IO[Unit] =
    waitUntil(30.seconds)(rt.store.snapshot.map(_.nodes.get(id).exists(n => NodeLifecycle.Terminal.contains(n.status))))

  /** 等待某节点的 `loop-round` 事件落盘（= `verifierFail` 走到「已处置」的唯一同步点）。
    * ⚠ **不能等「节点终态」**：执行腿落地后驱动方被复位出 completed（那正是修复本体），
    * 终态等待会永远超时——旧口径下「fail 恒 completed 且无人复位」才是终态可等的。 */
  private def waitLoopRound(ws: os.Path, id: String): IO[Unit] =
    waitUntil(30.seconds)(readAudit(ws).map(_.exists((t, nid, _) => t == "loop-round" && nid == id)))

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  private val LoopProps = List("nebflow.nrloop.maxRounds", "nebflow.nrloop.maxWallClockMs")

  // ── ① 执行腿：fail ⇒ 真实重激活 + 真实投递 + 驱动方复位 ───────────────

  test("① reloopTo: a fail verdict really dispatches — target re-activated, loop-round records the dispatch, driver reset so the loop can advance") {
    val ws = tempRoot / "ws-exec"
    os.makeDir.all(ws)
    val system = ActorSystem(s"lexec-${scala.util.Random.nextInt(100000)}")
    val llm = new FailReportLlm(closing = "被判定对象不合格，正式给出 fail verdict。本节点收尾。")
    try
      for
        res <- mkResources(system, tempRoot, llm.handle)
        rt <- mountEngineOnly("lexec", ws, system, res)
        // 真实形态：worker 已跑完并把结果投给了 verifier（deliveredTo 有记录）。
        _ <- seed(rt,
          mkNode("n-work", "n-work", NodeLifecycle.Completed, out = List(OutEdge("n-ver")),
            result = Some("worker round-1 output")),
          failVerifier("n-ver", NodeLifecycle.Wiring, loopRound = 0, deliveredTo = List("n-work")),
          mkNode("n-land", "n-land", NodeLifecycle.Wiring))
        _ <- rt.engine.startNode("n-ver")
        _ <- waitLoopRound(ws, "n-ver")
        ver <- rt.store.snapshot.map(_.nodes("n-ver"))
        work <- rt.store.snapshot.map(_.nodes("n-work"))
        land <- rt.store.snapshot.map(_.nodes("n-land"))
        audit <- readAudit(ws)
        _ <- system.stopAll.handleErrorWith(_ => IO.unit)
      yield
        // 判词本体：verdict ≠ 节点状态（fail 恒 completed/已复位，不是 failed）
        assertEquals(ver.lastVerdict, Some("fail"), "the fail verdict is recorded on the driver")
        assert(!audit.exists((t, _, s) => t == "loop-round" && s.contains("deferred=")),
          s"the silent defer MUST be gone, got: $audit")
        assert(audit.exists((t, id, s) =>
          t == "loop-round" && id == "n-ver" && s.contains("dispatched=reloopTo") && s.contains("round=1/3")),
          s"round 1/3 must be DISPATCHED (not deferred), got: $audit")
        // 目标重激活：离开 completed、旧结果丢弃（前端看到 attempt N，而不是拓扑增殖）
        assertEquals(work.status, NodeLifecycle.Wiring, "the judged target leaves the completed terminal state")
        assertEquals(work.result, None, "the old result is discarded (it is about to be recomputed)")
        assert(work.loopStartedAt.isDefined, "the wall-clock anchor survives the re-run (cross-round semantics)")
        assert(audit.exists((t, id, s) => t == "reactivated" && id == "n-work" && s.contains("source=loop")),
          s"the re-run must be audited as an engine loop re-run (source=loop), got: $audit")
        // 驱动方复位 ⇒ 回边下一轮可达（不复位则目标重跑完成后无人复核 = 静默冻结）
        assertEquals(ver.status, NodeLifecycle.Wiring,
          "the fail-route driver is reset so the target's new output can re-trigger it")
        assert(!ver.deliveredTo.contains("n-work"),
          s"the target's delivery is withdrawn from the driver's barrier (waits for the NEW output), got: ${ver.deliveredTo}")
        // 选通面：fail 判词不投 pass 边（既有语义零变化）
        assertEquals(land.deliveredTo, Nil, "verdict=fail must not deliver along the pass edge")
        assertEquals(land.status, NodeLifecycle.Wiring, "the sink must not be pulled up by a fail verdict")
    finally
      LoopProps.foreach(System.clearProperty)
  }

  // ── ②(a) 轮预算：3/3 是熔断轮，不是再派发轮 ─────────────────────────

  test("② round cap: 3/3 is the CIRCUIT-BREAK round (explicit terminal + loop-budget event + no dispatch), not one more re-run") {
    val ws = tempRoot / "ws-rounds"
    os.makeDir.all(ws)
    val system = ActorSystem(s"lround-${scala.util.Random.nextInt(100000)}")
    val llm = new FailReportLlm(closing = "再次判 fail。本节点收尾。")
    try
      for
        res <- mkResources(system, tempRoot, llm.handle)
        rt <- mountEngineOnly("lround", ws, system, res)
        _ <- IO(System.setProperty("nebflow.nrloop.maxRounds", "3"))
        // loopRound=2 ⇒ 本次判词消费的是**第 3 轮** = 预算上限 ⇒ 必须熔断而非再派发。
        _ <- seed(rt,
          mkNode("n-work", "n-work", NodeLifecycle.Completed, out = List(OutEdge("n-ver")),
            result = Some("worker round-2 output")),
          failVerifier("n-ver", NodeLifecycle.Wiring, loopRound = 2, deliveredTo = List("n-work")),
          mkNode("n-land", "n-land", NodeLifecycle.Wiring))
        _ <- rt.engine.startNode("n-ver")
        _ <- waitTerminal(rt, "n-ver")
        ver <- rt.store.snapshot.map(_.nodes("n-ver"))
        work <- rt.store.snapshot.map(_.nodes("n-work"))
        audit <- readAudit(ws)
        _ <- system.stopAll.handleErrorWith(_ => IO.unit)
      yield
        // 显式终态（②）
        assertEquals(ver.status, NodeLifecycle.Failed, "budget exhaustion terminalizes the fail-route driver (visible to the dispatcher)")
        assert(ver.result.exists(_.contains("loop budget exhausted")), s"result must carry the reason, got: ${ver.result}")
        assert(ver.result.exists(_.contains("rounds=3/3")), s"the metering must read the consumed round 3/3, got: ${ver.result}")
        assert(audit.exists((t, id, s) => t == "loop-budget" && id == "n-ver" && s.contains("rounds=3/3")),
          s"a loop-budget event must be logged with the consumed round, got: $audit")
        // 不再派发该轮（旧口径在 3/3 仍派发，须等第 4 次判词才熔断）
        assertEquals(work.status, NodeLifecycle.Completed, "3/3 must NOT dispatch one more re-run")
        assert(!audit.exists((t, id, _) => t == "reactivated" && id == "n-work"),
          s"no re-run may be dispatched on the exhausted round, got: $audit")
    finally
      LoopProps.foreach(System.clearProperty)
  }

  // ── ②(b) 熔断不被「起点缺失」静默吞掉 ───────────────────────────────

  test("② no silent swallow: the round-cap circuit break terminalizes even when the target has NO loop timer to clear") {
    val ws = tempRoot / "ws-notimer"
    os.makeDir.all(ws)
    val system = ActorSystem(s"lnotimer-${scala.util.Random.nextInt(100000)}")
    val llm = new FailReportLlm(closing = "判 fail。本节点收尾。")
    try
      for
        res <- mkResources(system, tempRoot, llm.handle)
        rt <- mountEngineOnly("lnotimer", ws, system, res)
        _ <- IO(System.setProperty("nebflow.nrloop.maxRounds", "3"))
        // 起点缺失（= 已被孤儿扫描清掉的真实形态）⇒ 旧口径 clearLoopStartedAt 返回 false
        // ⇒ 整条熔断被跳过：不终态化、不发 loop-budget、不通知分发器（静默冻结）。
        _ <- seed(rt,
          mkNode("n-work", "n-work", NodeLifecycle.Completed, out = List(OutEdge("n-ver")),
            result = Some("output"), loopStartedAt = None),
          failVerifier("n-ver", NodeLifecycle.Wiring, loopRound = 2, deliveredTo = List("n-work")),
          mkNode("n-land", "n-land", NodeLifecycle.Wiring))
        _ <- rt.engine.startNode("n-ver")
        _ <- waitTerminal(rt, "n-ver")
        ver <- rt.store.snapshot.map(_.nodes("n-ver"))
        audit <- readAudit(ws)
        _ <- system.stopAll.handleErrorWith(_ => IO.unit)
      yield
        assertEquals(ver.status, NodeLifecycle.Failed, "the round cap must terminalize regardless of the timer's presence")
        assert(audit.exists((t, id, s) => t == "loop-budget" && id == "n-ver" && s.contains("rounds=3/3")),
          s"the loop-budget event must be written (the timer is a product of the break, not its gate), got: $audit")
    finally
      LoopProps.foreach(System.clearProperty)
  }

  // ── ②(c) 时间维驱动方判据 = 判词边（终态 fail-verifier 不再被当孤儿）────

  test("② wall-clock driver: a COMPLETED verifier with lastVerdict=fail is still the driver (circuit-breaks); pass/no-verdict stays orphan") {
    val ws = tempRoot / "ws-wall"
    os.makeDir.all(ws)
    val system = ActorSystem(s"lwall-${scala.util.Random.nextInt(100000)}")
    val llm = new FailReportLlm(closing = "unused")
    try
      for
        res <- mkResources(system, tempRoot, llm.handle)
        rt <- mountEngineOnly("lwall", ws, system, res)
        _ <- IO(System.setProperty("nebflow.nrloop.maxWallClockMs", "1"))
        now = System.currentTimeMillis()
        _ <- seed(rt,
          // 本体：驱动方已 completed 且判词 = fail（verdict ≠ 节点状态的常态形态）
          mkNode("n-work", "n-work", NodeLifecycle.Wiring, out = List(OutEdge("n-ver")),
            loopStartedAt = Some(now - 10_000)),
          failVerifier("n-ver", NodeLifecycle.Completed, loopRound = 1, lastVerdict = Some("fail")),
          // 对照组：驱动方 completed 但判词 = pass ⇒ 不算驱动方（孤儿语义逐字保留）
          mkNode("n-work2", "n-work2", NodeLifecycle.Wiring, out = List(OutEdge("n-ver2")),
            loopStartedAt = Some(now - 10_000)),
          mkNode("n-ver2", "n-ver2", NodeLifecycle.Completed, role = NodeRoles.Verifier,
            in = List("n-work2"), out = List(OutEdge("n-land"), OutEdge("n-work2", Set(OutEdge.Fail), OutEdge.Loop)),
            result = Some("passed"), lastVerdict = Some("pass")))
        _ <- rt.engine.sweepLoopBudgets()
        ver <- rt.store.snapshot.map(_.nodes("n-ver"))
        ver2 <- rt.store.snapshot.map(_.nodes("n-ver2"))
        work <- rt.store.snapshot.map(_.nodes("n-work"))
        audit <- readAudit(ws)
        _ <- system.stopAll.handleErrorWith(_ => IO.unit)
      yield
        assertEquals(ver.status, NodeLifecycle.Failed,
          "a completed fail-verifier still drives the re-run ⇒ the expired timer circuit-breaks it (was: orphan ⇒ nothing terminalized)")
        assert(audit.exists((t, id, s) => t == "loop-budget" && id == "n-ver" && s.contains("wallClock")),
          s"the wall-clock break must be logged for the driver, got: $audit")
        assertEquals(work.loopStartedAt, None, "the target's timer is cleared by the break")
        assertEquals(work.status, NodeLifecycle.Wiring, "the TARGET is not terminalized — only the driver is")
        // 对照组：判词 = pass（或无判词）的终态 verifier **不**算驱动方 ⇒ 孤儿分支逐字不变
        assertEquals(ver2.status, NodeLifecycle.Completed, "a pass/no-verdict terminal verifier is NOT a driver (orphan semantics unchanged)")
        assert(audit.exists((t, id, s) => t == "loop-budget" && id == "n-work2" && s.contains("stage=orphan")),
          s"the control target must still take the orphan branch, got: $audit")
    finally
      LoopProps.foreach(System.clearProperty)
  }

  // ── ③ settle-sweep 判词感知（+ 对照组：无 verifier 上游行为不变）────────

  test("③ settle-sweep: a fail-verifier upstream is NOT redelivered (skipped-by-verdict, single shot); a plain task upstream is healed as before") {
    val ws = tempRoot / "ws-sweep"
    os.makeDir.all(ws)
    val system = ActorSystem(s"lsweep-${scala.util.Random.nextInt(100000)}")
    val llm = new FailReportLlm(closing = "unused")
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountEngineOnly("lsweep", ws, system, res)
      _ <- seed(rt,
        // 被判对象仍待返工；收口位（非 merge）的 barrier 只有 verifier 一轨未结算
        mkNode("n-sink", "n-sink", NodeLifecycle.Wiring, in = List("n-ver"), deps = List("n-absent")),
        mkNode("n-ver", "n-ver", NodeLifecycle.Completed, role = NodeRoles.Verifier,
          in = List("n-work"), result = Some("REJECTION: the artifact does not compile"),
          lastVerdict = Some("fail")),
        // 对照组：普通 task 上游（合法产物）⇒ 照旧补投
        mkNode("n-sink2", "n-sink2", NodeLifecycle.Wiring, in = List("n-task"), deps = List("n-absent")),
        mkNode("n-task", "n-task", NodeLifecycle.Completed, result = Some("REAL DELIVERABLE")))
      _ <- rt.engine.settleRunnableSweep()
      sink1 <- rt.store.snapshot.map(_.nodes("n-sink"))
      sink2 <- rt.store.snapshot.map(_.nodes("n-sink2"))
      audit1 <- readAudit(ws)
      _ <- rt.engine.settleRunnableSweep()
      audit2 <- readAudit(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // ③ 判词挡投：verdict=fail 的 verifier 的「结果」不是可前递的产物
      assertEquals(sink1.deliveredTo, Nil, s"a fail-verifier's judgement must NOT be redelivered, got: ${sink1.deliveredTo}")
      assertEquals(sink1.status, NodeLifecycle.Wiring, "the sink stays pending/wiring (the re-run must land first)")
      assert(audit1.exists((t, id, s) => t == "settle-sweep" && id == "n-sink" && s.contains("skipped-by-verdict")),
        s"the skip must be traceable (mechanical marker), got: $audit1")
      // 单发：同一停滞期不得每轮刷屏
      assertEquals(audit2.count { case (t, id, s) => t == "settle-sweep" && id == "n-sink" && s.contains("skipped-by-verdict") }, 1,
        s"the skip event must be single-shot per stagnation window, got: $audit2")
      // 对照组：无 verifier 上游的既有行为逐字不变（孤儿 barrier 照旧自愈）
      assert(sink2.deliveredTo.contains("n-task"),
        s"a plain completed upstream is still redelivered (no-verifier behaviour unchanged), got: ${sink2.deliveredTo}")
      assert(!audit1.exists((t, id, s) => t == "settle-sweep" && id == "n-sink2" && s.contains("skipped-by-verdict")),
        "the control sink must never be reported as verdict-skipped")
  }
