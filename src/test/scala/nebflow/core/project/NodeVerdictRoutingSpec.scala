package nebflow.core.project

import cats.effect.{IO, Ref}
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentLibrary, SharedResources}
import nebflow.core.PathUtil
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, NodeEditTool, NodeTools, ToolContext}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * nrloop 一期（2026-09-12，设计正本 §3.2/§3.3/§3.4/§3.5/§3.6；附 C R3/R4/R5/R6/C5）
 * 的**校验族 + 语法面 + 熔断面**回归：
 *
 *  - B1 `role` create-only：编辑期出现 ⇒ NODE_ROLE_CREATE_ONLY（0 副作用）；
 *    创建期非法值 ⇒ NODE_ROLE_INVALID。
 *  - B4 语法：`OutEdge.Gates += fail` / `Modes += loop` 的解析面（`:loop` 只与 `fail`
 *    成对、错误文案区分 `fail`(verdict) 与 `failed`(node status)）。
 *  - B4 校验族（`NodeTools.verdictRouteGate`，0 spawn）：逐条验红 —— task 节点带
 *    verdict 路由 / verifier 带 failed 门 / `fail` 与 `:loop` 拆散 / verifier 有边
 *    却无 fail 路由 / 回边指 Nebula / 悬空回边 / 自指回边 / 多 fail 目标 / pass 与
 *    fail 同目标 / 回边目标带 retry。
 *  - B4 红线①：`:loop` 控制边**不写 in 镜像**（pass 边照写 —— 对照组）。
 *  - B5 熔断（时间维）：`sweepLoopBudgets` 命中 ⇒ verifier 终态化 failed + result 写
 *    reason + `loop-budget` 事件 + 目标计时起点清除；孤儿计时（无活驱动方）⇒ 只清表。
 *  - B5 判据单点 `LoopBudget.decide` 的矩阵（轮次帽 / 时间帽 / 两维独立 / ≤0 关闭）。
 *  - B8/C5：`reactivateCompleted` 的适用范围闸（非 completed ⇒ 拒；completed ⇒ 重激活）。
 */
class NodeVerdictRoutingSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-node-verdict-routing"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "general")
  os.write.over(tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"general executor","tools":[],"category":"standalone"}""")
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  /** 静默 LLM 桩（本 spec 只验 0 spawn 的校验面 + 纯判据）。 */
  private class QuietLlm:
    val inputs: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)
    def handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(
          req: LlmRequest,
          onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
      ): Stream[IO, StreamChunk] =
        val text = req.messages.map(_.textContent).mkString("\n")
        Stream.eval(inputs.update(_ :+ text)) >>
          Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

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
      sessionId = Some("verdict-routing-sid"),
      rootSessionId = Some("nebula-root"),
      sharedResources = Some(res),
      actorSystem = Some(system)
    )

  private def nodeEdit(input: Json, ctx: ToolContext): IO[Either[String, String]] =
    NodeEditTool.call(input.asObject.get, ctx).map(_.left.map(_.message))

  private def nodeInput(project: String, nodename: String, extra: (String, Json)*): Json =
    Json.obj(("project" -> Json.fromString(project)) :: ("nodename" -> Json.fromString(nodename)) :: extra.toList*)

  /** verifier 创建输入（role create-only ⇒ 必须建点声明）。 */
  private def verifierInput(project: String, nodename: String, out: String): Json =
    nodeInput(project, nodename,
      "description" -> Json.fromString(s"verifier $nodename"),
      "task" -> Json.fromString(s"$nodename task"),
      "role" -> Json.fromString(NodeRoles.Verifier),
      "out" -> Json.fromString(out))

  private def mountProject(name: String, ws: os.Path, system: ActorSystem, res: SharedResources): IO[ProjectRuntime] =
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

  /** 直种节点（绕过 NodeEdit 校验，用于把状态/字段摆到待验判据上）。 */
  private def seed(rt: ProjectRuntime, nodes: NodeDef*): IO[Unit] =
    rt.store.mutate(s => s.copy(nodes = s.nodes ++ nodes.map(n => n.id -> n).toMap)).void

  private def mkNode(id: String, name: String, status: String, role: String = NodeRoles.Task,
      out: List[OutEdge] = Nil, loopRound: Int = 0, loopStartedAt: Option[Long] = None,
      withTask: Boolean = true): NodeDef =
    NodeDef(id = id, name = name, agent = "general", task = if withTask then Some(s"$name task") else None,
      status = status, out = out, role = role, loopRound = loopRound, loopStartedAt = loopStartedAt,
      createdAt = System.currentTimeMillis())

  private def readAudit(ws: os.Path): IO[List[(String, String, String)]] =
    IO.blocking(os.read(ws / ".nebflow" / FlowMapEventLog.FileName))
      .map(_.linesIterator.toList.filter(_.trim.nonEmpty))
      .map(lines => lines.flatMap(l => io.circe.parser.parse(l).toOption.map(j =>
        (j.hcursor.get[String]("type").getOrElse(""),
         j.hcursor.get[String]("nodeId").getOrElse(""),
         j.hcursor.get[String]("summary").getOrElse("")))))
      .handleError(_ => Nil)

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  private val LoopProps = List("nebflow.nrloop.maxRounds", "nebflow.nrloop.maxWallClockMs")

  // ── B4 语法面 ─────────────────────────────────────────────

  test("B4 syntax: '(fail)X:loop' parses to on={fail} + mode=loop; gate/mode errors tell the two gates apart") {
    val ok = NodeTools.parseOut(Some(Json.fromString("(fail)n-w:loop")))
    assertEquals(ok.map(es => es.map(e => (e.to, e.on, e.mode))),
      Right(List(("n-w", Set("fail"), "loop"))), "fail gate + loop mode parse")
    val bad = NodeTools.parseOut(Some(Json.fromString("(nope)n-w")))
    assert(bad.isLeft, "unknown gate must be rejected")
    assert(bad.left.exists(m => m.contains("'failed'") && m.contains("'fail'") && m.contains("VERDICT")),
      s"error text must tell 'failed' (node status) from 'fail' (verdict), got: $bad")
    val badMode = NodeTools.parseOut(Some(Json.fromString("n-w:loopy")))
    assert(badMode.left.exists(m => m.contains("loop") && m.contains("signal") && m.contains("result")),
      s"mode error must list {result, signal, loop}, got: $badMode")
    assertEquals(OutEdge.Gates, Set("pass", "failed", "fail"))
    assertEquals(OutEdge.Modes, Set("result", "signal", "loop"))
    assert(OutEdge.isVerdictGate(OutEdge("n-w", Set(OutEdge.Fail), OutEdge.Loop)), "fail+loop is the verdict control edge")
    assert(!OutEdge.isLoopEdge(OutEdge("n-w")), "a plain pass edge is not a control edge")
  }

  // ── B4 校验族（0 spawn，逐条验红）────────────────────────────

  test("B4 validation family: each verdict-routing criterion rejects with its own code (0 spawn)") {
    val ws = tempRoot / "ws-family"
    os.makeDir.all(ws)
    val system = ActorSystem(s"vfam-${scala.util.Random.nextInt(100000)}")
    val llm = new QuietLlm
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("vfam", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- seed(rt,
        mkNode("n-w1", "w1", NodeLifecycle.Wiring),
        mkNode("n-w2", "w2", NodeLifecycle.Wiring))
      // ① task 节点带 verdict 路由
      r1 <- nodeEdit(nodeInput("vfam", "t-fail", "description" -> Json.fromString("attacker"),
        "task" -> Json.fromString("t1"), "out" -> Json.fromString("(fail)n-w1:loop")), ctx)
      // ② verifier 带 failed 门
      r2 <- nodeEdit(verifierInput("vfam", "v-failed", "(pass)n-w1, (failed)n-w2"), ctx)
      // ③ fail 门缺 :loop
      r3 <- nodeEdit(verifierInput("vfam", "v-failonly", "(pass)n-w2, (fail)n-w1"), ctx)
      // ④ :loop 缺 fail 门
      r4 <- nodeEdit(verifierInput("vfam", "v-looponly", "(pass)n-w1:loop"), ctx)
      // ⑤ 有边却无 fail 路由
      r5 <- nodeEdit(verifierInput("vfam", "v-noroute", "(pass)n-w1"), ctx)
      // ⑥ 回边指 Nebula
      r6 <- nodeEdit(verifierInput("vfam", "v-neb", "(pass)n-w1, (fail)Nebula:loop"), ctx)
      // ⑦ 悬空回边（创建路径上由**更早的 out 存在性闸**拦下——纵深防御：verdict 族
      //   自带的悬空判据（NODE_LOOP_EDGE_ROLE）只在该闸之外可达，见下方直接调用例）
      r7 <- nodeEdit(verifierInput("vfam", "v-miss", "(pass)n-w1, (fail)n-doesnotexist:loop"), ctx)
      // ⑧ 多 fail 目标
      r8 <- nodeEdit(verifierInput("vfam", "v-multi", "(fail)n-w1:loop, (fail)n-w2:loop"), ctx)
      // ⑨ pass 与 fail 同目标
      r9 <- nodeEdit(verifierInput("vfam", "v-collide", "(pass)n-w1, (fail)n-w1:loop"), ctx)
      // ⑩ 自指回边（直种一个 verifier 再改接到自己——**不走 create**：本 spec 全域保持
      //    零 spawn，不给其他并行 suite 制造写 dataRoot 的后台任务）
      _ <- seed(rt, mkNode("n-self", "self", NodeLifecycle.Wiring, role = NodeRoles.Verifier,
        out = List(OutEdge("n-w1", Set(OutEdge.Fail), OutEdge.Loop))))
      r10 <- nodeEdit(nodeInput("vfam", "self", "out" -> Json.fromString("(fail)n-self:loop")), ctx)
      snap <- rt.store.snapshot
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      def code(r: Either[String, String]): String =
        assert(r.isLeft, s"must be rejected, got: $r")
        r.left.toOption.get
      assert(code(r1).contains("NODE_VERDICT_GATE_ON_TASK_NODE"), code(r1))
      assert(code(r2).contains("NODE_VERDICT_GATE_ON_TASK_NODE"), code(r2))
      assert(code(r3).contains("NODE_LOOP_EDGE_ROLE"), code(r3))
      assert(code(r4).contains("NODE_LOOP_EDGE_ROLE"), code(r4))
      assert(code(r5).contains("NODE_VERIFIER_NEEDS_ROUTE"), code(r5))
      assert(code(r6).contains("NODE_LOOP_TARGET_NEBULA"), code(r6))
      // ⑦ 悬空回边在 create 路径上先撞 out 存在性闸（可行动错误照给，码不同）
      assert(code(r7).contains("not found"), code(r7))
      assert(code(r8).contains("NODE_VERIFY_MULTI_FAIL_TARGET"), code(r8))
      assert(code(r9).contains("NODE_VERDICT_ROUTE_COLLISION"), code(r9))
      // ⑩ 自指回边 —— 由 verdict 族（而非环检）拒：`:loop` 不参与图环检（控制边豁免），
      //    故这里必须看到 verdict 族的专用码与可行动文案，而不是 "Cycle detected"。
      assert(code(r10).contains("NODE_LOOP_EDGE_ROLE") && code(r10).contains("itself"), code(r10))
      assert(!code(r10).contains("Cycle detected"), s"the loop edge must stay out of the DAG cycle check, got: ${code(r10)}")
      // 0 spawn：被拒的创建不落库（v-self 是唯一合法建立的 verifier）
      val names = snap.nodes.values.map(_.name).toSet
      val rejected = List("t-fail", "v-failed", "v-failonly", "v-looponly", "v-noroute", "v-neb", "v-miss", "v-multi", "v-collide")
      assert(rejected.forall(n => !names.contains(n)), s"rejected creates must not persist, got: $names")
  }

  test("B4 validation family (direct): the verdict gate itself rejects a dangling fail target (NODE_LOOP_EDGE_ROLE)") {
    val ws = tempRoot / "ws-family3"
    os.makeDir.all(ws)
    val system = ActorSystem(s"vfam3-${scala.util.Random.nextInt(100000)}")
    val llm = new QuietLlm
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("vfam3", ws, system, res)
      _ <- seed(rt, mkNode("n-w1", "w1", NodeLifecycle.Wiring))
      dangling <- NodeTools.verdictRouteGate(rt, "n-new", "v-dangling", NodeRoles.Verifier, false,
        List(OutEdge("n-w1"), OutEdge("n-ghost", Set(OutEdge.Fail), OutEdge.Loop)))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(dangling.exists(e => e.contains("NODE_LOOP_EDGE_ROLE") && e.contains("n-ghost")),
        s"a dangling fail target must be rejected by the gate itself, got: $dangling")
  }

  test("B4 validation family (edit face): retry on a fail-route target is rejected (NODE_RETRY_LOOP_CONFLICT)") {
    val ws = tempRoot / "ws-family2"
    os.makeDir.all(ws)
    val system = ActorSystem(s"vfam2-${scala.util.Random.nextInt(100000)}")
    val llm = new QuietLlm
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("vfam2", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- seed(rt,
        mkNode("n-w1", "w1", NodeLifecycle.Wiring, out = List(OutEdge("n-land"))),
        mkNode("n-land", "land", NodeLifecycle.Wiring))
      // 目标带 retry 策略（直种：retry 本可合法存在）⇒ 回边指它就必须被拒
      _ <- rt.store.mutate { s =>
        val w1 = s.nodes("n-w1")
        s.copy(nodes = s.nodes.updated("n-w1", w1.copy(retry = Some(RetryPolicy("n-land", 2)))))
      }
      conflict <- nodeEdit(verifierInput("vfam2", "v-retrytgt", "(fail)n-w1:loop"), ctx)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(conflict.left.exists(_.contains("NODE_RETRY_LOOP_CONFLICT")),
        s"a fail-route target carrying retry must be rejected, got: $conflict")
  }

  // ── B4 红线①：控制边不写 in 镜像 ─────────────────────────────

  test("B4 red line ①: a ':loop' control edge is never mirrored into the target's in ledger (pass edges still are)") {
    val ws = tempRoot / "ws-mirror"
    os.makeDir.all(ws)
    val system = ActorSystem(s"vmir-${scala.util.Random.nextInt(100000)}")
    val llm = new QuietLlm
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("vmir", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- seed(rt,
        mkNode("n-w1", "w1", NodeLifecycle.Wiring),
        mkNode("n-w2", "w2", NodeLifecycle.Wiring))
      _ <- nodeEdit(verifierInput("vmir", "v1", "(fail)n-w1:loop"), ctx)
      inW1AfterLoop <- rt.store.snapshot.map(_.nodes("n-w1").in)
      // 改接：加一条 pass 边 → 对照组（pass 边照写镜像），回边仍不写
      _ <- nodeEdit(nodeInput("vmir", "v1", "out" -> Json.fromString("(pass)n-w2, (fail)n-w1:loop")), ctx)
      inW1Final <- rt.store.snapshot.map(_.nodes("n-w1").in)
      inW2Final <- rt.store.snapshot.map(_.nodes("n-w2").in)
      vOut <- rt.store.snapshot.map(_.nodes.values.find(_.name == "v1").map(_.out).getOrElse(Nil))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(inW1AfterLoop, Nil,
        "the :loop edge must NOT be mirrored into the target's in (round-1 barrier deadlock guard)")
      assertEquals(inW1Final, Nil, "still not mirrored after the edge set is rewritten")
      assert(inW2Final.nonEmpty, "control group: a plain pass edge IS mirrored into the target's in")
      assert(vOut.exists(e => e.on.contains(OutEdge.Fail) && e.mode == OutEdge.Loop), "the control edge stays in the out ledger")
  }

  // ── B5 熔断（时间维）+ 判据矩阵 ───────────────────────────────

  test("B5 circuit-break (wall clock): sweep terminalizes the verifier failed, logs loop-budget, clears the timer") {
    val ws = tempRoot / "ws-budget"
    os.makeDir.all(ws)
    val system = ActorSystem(s"vbud-${scala.util.Random.nextInt(100000)}")
    val llm = new QuietLlm
    try
      for
        res <- mkResources(system, tempRoot, llm.handle)
        rt <- mountProject("vbud", ws, system, res)
        _ <- IO(System.setProperty("nebflow.nrloop.maxWallClockMs", "1"))
        now = System.currentTimeMillis()
        _ <- seed(rt,
          mkNode("n-tgt", "tgt", NodeLifecycle.Wiring, loopStartedAt = Some(now - 10_000)),
          mkNode("n-ver", "ver", NodeLifecycle.Running, role = NodeRoles.Verifier,
            out = List(OutEdge("n-tgt", Set(OutEdge.Fail), OutEdge.Loop))))
        _ <- rt.engine.sweepLoopBudgets()
        ver <- rt.store.snapshot.map(_.nodes("n-ver"))
        tgt <- rt.store.snapshot.map(_.nodes("n-tgt"))
        audit <- readAudit(ws)
        _ <- system.stopAll.handleErrorWith(_ => IO.unit)
      yield
        assertEquals(ver.status, NodeLifecycle.Failed, "the fail-route driver terminalizes failed on budget exhaustion")
        assert(ver.result.exists(_.contains("loop budget exhausted")), s"result must carry the reason, got: ${ver.result}")
        assert(ver.result.exists(_.contains("rounds=")) && ver.result.exists(_.contains("wallClock")),
          s"result must carry the metering numbers, got: ${ver.result}")
        assertEquals(ver.ttlExpireAt, None, "failed keeps the scene (no TTL)")
        assertEquals(tgt.loopStartedAt, None, "the timer is cleared (idempotent single shot)")
        assertEquals(tgt.status, NodeLifecycle.Wiring, "the TARGET is not terminalized — only the driver is")
        assert(audit.exists((t, id, s) => t == "loop-budget" && id == "n-ver" && s.contains("wallClock")),
          s"a loop-budget event must be logged for the driver, got: $audit")
    finally
      LoopProps.foreach(System.clearProperty)
  }

  test("B5 circuit-break idempotence: a second scan of the same sighting does nothing (timer already cleared)") {
    val ws = tempRoot / "ws-budget2"
    os.makeDir.all(ws)
    val system = ActorSystem(s"vbud2-${scala.util.Random.nextInt(100000)}")
    val llm = new QuietLlm
    try
      for
        res <- mkResources(system, tempRoot, llm.handle)
        rt <- mountProject("vbud2", ws, system, res)
        _ <- IO(System.setProperty("nebflow.nrloop.maxWallClockMs", "1"))
        now = System.currentTimeMillis()
        _ <- seed(rt,
          mkNode("n-tgt2", "tgt2", NodeLifecycle.Wiring, loopStartedAt = Some(now - 10_000)),
          mkNode("n-ver2", "ver2", NodeLifecycle.Running, role = NodeRoles.Verifier,
            out = List(OutEdge("n-tgt2", Set(OutEdge.Fail), OutEdge.Loop))))
        _ <- rt.engine.sweepLoopBudgets()
        first <- readAudit(ws)
        _ <- rt.engine.sweepLoopBudgets()
        second <- readAudit(ws)
        _ <- system.stopAll.handleErrorWith(_ => IO.unit)
      yield
        assertEquals(first.count(_._1 == "loop-budget"), 1, s"exactly one event per sighting, got: $first")
        assertEquals(second.count(_._1 == "loop-budget"), 1, "the second scan is a no-op (timer cleared ⇒ no longer due)")
    finally
      LoopProps.foreach(System.clearProperty)
  }

  test("B5 circuit-break (orphan timer): no live fail-route driver ⇒ timer cleared with an orphan event, nothing terminalized") {
    val ws = tempRoot / "ws-orphan"
    os.makeDir.all(ws)
    val system = ActorSystem(s"vorp-${scala.util.Random.nextInt(100000)}")
    val llm = new QuietLlm
    try
      for
        res <- mkResources(system, tempRoot, llm.handle)
        rt <- mountProject("vorp", ws, system, res)
        _ <- IO(System.setProperty("nebflow.nrloop.maxWallClockMs", "1"))
        now = System.currentTimeMillis()
        _ <- seed(rt, mkNode("n-orph", "orph", NodeLifecycle.Running, loopStartedAt = Some(now - 10_000)))
        _ <- rt.engine.sweepLoopBudgets()
        n <- rt.store.snapshot.map(_.nodes("n-orph"))
        audit <- readAudit(ws)
        _ <- system.stopAll.handleErrorWith(_ => IO.unit)
      yield
        assertEquals(n.status, NodeLifecycle.Running, "an orphan timer must not terminalize an unrelated node")
        assertEquals(n.loopStartedAt, None, "the orphan timer is cleared (no 30s scan hotspot)")
        assert(audit.exists((t, id, s) => t == "loop-budget" && id == "n-orph" && s.contains("orphan")),
          s"an orphan stage event must be logged, got: $audit")
    finally
      LoopProps.foreach(System.clearProperty)
  }

  test("B5 predicate single point LoopBudget.decide: round cap / wall clock / independent dims / <=0 disables a dim") {
    assertEquals(LoopBudget.decide(rounds = 3, maxRounds = 3, startedAt = None, now = 1000L, maxWallMs = 0L),
      Some("rounds=3/3"))
    assertEquals(LoopBudget.decide(rounds = 0, maxRounds = 3, startedAt = Some(0L), now = 1000L, maxWallMs = 500L),
      Some("wallClock=1000ms/500ms"))
    assert(LoopBudget.decide(rounds = 0, maxRounds = 3, startedAt = Some(0L), now = 100L, maxWallMs = 500L).isEmpty,
      "inside budget ⇒ None")
    assert(LoopBudget.decide(rounds = 9, maxRounds = 0, startedAt = Some(0L), now = 1L, maxWallMs = 0L).isEmpty,
      "thresholds <= 0 turn the dimension off (knob semantics)")
    assert(LoopBudget.decide(rounds = 5, maxRounds = 3, startedAt = Some(0L), now = 900L, maxWallMs = 500L)
      .exists(r => r.contains("rounds=5/3") && r.contains("wallClock=900ms/500ms")),
      "both dimensions hit ⇒ both readings reported")
  }

  // ── B1 / B8：role create-only + reactivateCompleted 适用范围 ──

  test("B1: role is create-only (NODE_ROLE_CREATE_ONLY) and an unknown role is rejected (NODE_ROLE_INVALID)") {
    val ws = tempRoot / "ws-role"
    os.makeDir.all(ws)
    val system = ActorSystem(s"vrole-${scala.util.Random.nextInt(100000)}")
    val llm = new QuietLlm
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("vrole", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      badRole <- nodeEdit(nodeInput("vrole", "r-bad", "description" -> Json.fromString("bad role"),
        "task" -> Json.fromString("t"), "role" -> Json.fromString("controller")), ctx)
      _ <- seed(rt,
        mkNode("n-r1", "r1", NodeLifecycle.Wiring, role = NodeRoles.Verifier),
        mkNode("n-r2", "r2", NodeLifecycle.Wiring))
      roleEdit <- nodeEdit(nodeInput("vrole", "r1", "role" -> Json.fromString("task")), ctx)
      roleEdit2 <- nodeEdit(nodeInput("vrole", "r1", "role" -> Json.fromString("verifier")), ctx)
      stored <- rt.store.snapshot.map(_.nodes("n-r1"))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(badRole.left.exists(_.contains("NODE_ROLE_INVALID")), s"unknown role must be rejected, got: $badRole")
      List(roleEdit, roleEdit2).foreach { r =>
        assert(r.left.exists(_.contains("NODE_ROLE_CREATE_ONLY")), s"role must be create-only, got: $r")
      }
      assertEquals(stored.role, NodeRoles.Verifier, "the stored role is untouched by the rejected edits")
  }

  test("B8/C5: reactivateCompleted is the explicit authorization — rejected off a completed node, honoured on one") {
    val ws = tempRoot / "ws-reactivate"
    os.makeDir.all(ws)
    val system = ActorSystem(s"vreact-${scala.util.Random.nextInt(100000)}")
    val llm = new QuietLlm
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("vreact", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- seed(rt,
        mkNode("n-run", "run-node", NodeLifecycle.Running),
        // 无 task ⇒ 重激活后落 wiring（不是 pending）⇒ 不自动启动：本 spec 零 spawn，
        // 断言也免于「重激活后立刻被 LLM 桩跑完又变 completed」的竞态。
        mkNode("n-done", "done-node", NodeLifecycle.Completed, withTask = false))
      createAuth <- nodeEdit(nodeInput("vreact", "brand-new", "description" -> Json.fromString("new node"),
        "task" -> Json.fromString("t"), "reactivateCompleted" -> Json.fromBoolean(true)), ctx)
      runAuth <- nodeEdit(nodeInput("vreact", "run-node", "reactivateCompleted" -> Json.fromBoolean(true),
        "task" -> Json.fromString("run-node task v2")), ctx)
      doneAuth <- nodeEdit(nodeInput("vreact", "done-node", "reactivateCompleted" -> Json.fromBoolean(true),
        "description" -> Json.fromString("done-node v2")), ctx)
      doneAfter <- rt.store.snapshot.map(_.nodes("n-done"))
      audit <- readAudit(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(createAuth.left.exists(_.contains("NODE_COMPLETED_REACTIVATION")),
        s"the flag is edit-only (no node exists on the create path), got: $createAuth")
      assert(runAuth.left.exists(_.contains("NODE_COMPLETED_REACTIVATION")),
        s"the flag only applies to a COMPLETED node, got: $runAuth")
      assert(doneAuth.isRight, s"a completed node CAN be re-run with the explicit authorization, got: $doneAuth")
      assert(doneAuth.exists(_.contains("reactivated from completed")),
        s"the result must report the reactivation, got: $doneAuth")
      assertEquals(doneAfter.status, NodeLifecycle.Wiring,
        "the node left the completed terminal state (no task ⇒ wiring, not pending)")
      assertEquals(doneAfter.result, None, "the old result is discarded (it is about to be recomputed)")
      assert(audit.exists((t, id, s) => t == "reactivated" && id == "n-done" && s.contains("NODE_COMPLETED_REACTIVATION")),
        s"the reactivation must be audited with the authorization name, got: $audit")
  }
