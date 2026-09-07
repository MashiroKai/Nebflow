package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import io.circe.parser.parse as jsonParse
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentLibrary, SharedResources}
import nebflow.core.PathUtil
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, NodeEditTool, ToolContext}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * deps 依赖连接回归（deps 设计 §1 / 主文档 20260902_flowmap-engine-evolution-design.md §4.1）：
 *
 * - T1 deps 触发且不投递 result（下游输入 = 自身 task 自足，无 === Node A === 段）
 * - T2 in+deps 混合 barrier（in 段注入、deps 段绝不注入；两路等待汇合同一 startNode 闸门）
 * - T3 混合图环检测（store 层传递链 + NodeEdit e2e 双层）
 * - T4 D1-deps 补触发（活动区 + 归档变体——「归档上游可触发」裁定回归锚）
 * - T5 failed 不触发 deps 下游（对照 in 边 collect——锁定 §1.3 行为差异）
 * - T6 创建连接校验（校验五，20260903 收紧：(task ∨ in) ∧ out——创建必带 out）
 * - T7 编辑路径连接校验（校验六零连接下限不变 + 校验六-a 断开拒绝，20260903 收紧）
 * - T8 abandon 接受域扩展（裁定①待实施语义 §1.6）
 */
class NodeDepsSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-node-deps"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "test-agent")
  os.write.over(
    tempRoot / "agents" / "test-agent" / "agent.json",
    """{"name":"test-agent","description":"deps regression agent","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "test-agent" / "system.md", "# test-agent\n")
  // 2026-09-05 agent 退役：新建节点执行统一 general——fixture 侧补 general agent
  os.makeDir.all(tempRoot / "agents" / "general")
  os.write.over(tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"general executor","tools":[],"category":"standalone"}""")
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  /** 按输入文本分派回复与延迟的捕获 LLM：inputs 记录每次请求 user 文本。
    * gateOf：Some 时先等该 Deferred 再产出回复——定长 delayOf 窗口在满载下
    * 有竞态（T1「B 在 A 完成前保持等待」需观测 A 仍在跑时 B 的 Pending 态，
    * 一旦 A 瞬回即完成，B 闸门即放行 → partial 读到 Running 假红）；改由
    * Deferred 将「A 何时完成」变成测试显式可控的同步点。 */
  private class DispatchLlm(
      replyOf: String => String = _ => "ok",
      delayOf: String => FiniteDuration = _ => 0.millis,
      gateOf: String => Option[cats.effect.Deferred[IO, Unit]] = _ => None
  ):
    val inputs: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)
    def handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(
          req: LlmRequest,
          onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
      ): Stream[IO, StreamChunk] =
        val text = req.messages.map(_.textContent).mkString("\n")
        Stream
          .eval(inputs.update(_ :+ text) >> IO.sleep(delayOf(text)))
          .flatMap { _ =>
            val wait: Stream[IO, Nothing] = gateOf(text) match
              case Some(g) => Stream.eval(g.get).drain
              case None    => Stream.empty
            wait ++ Stream(StreamChunk.TextDelta(replyOf(text)), StreamChunk.Done(None, None))
          }

  /** 对指定文本抛错的失败 LLM（stream error → AgentEvent.Failed → failNode）。
    * delayBeforeFail：抛错前先等一段——保留 Running 窗口供测试建立依赖拓扑。 */
  private class FailOnLlm(
      failWhen: String => Boolean,
      replyOf: String => String = _ => "ok",
      delayBeforeFail: FiniteDuration = 0.millis):
    val inputs: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)
    def handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(
          req: LlmRequest,
          onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
      ): Stream[IO, StreamChunk] =
        val text = req.messages.map(_.textContent).mkString("\n")
        if failWhen(text) then
          Stream.eval(inputs.update(_ :+ text) >> IO.sleep(delayBeforeFail)) >>
            Stream.raiseError[IO](new RuntimeException("boom-exploded"))
        else
          Stream
            .eval(inputs.update(_ :+ text))
            .flatMap(_ => Stream(StreamChunk.TextDelta(replyOf(text)), StreamChunk.Done(None, None)))

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
      sessionId = Some("deps-sid"),
      rootSessionId = Some("nebula-root"),
      sharedResources = Some(res),
      actorSystem = Some(system)
    )

  private def nodeEdit(input: Json, ctx: ToolContext): IO[Either[String, String]] =
    NodeEditTool.call(input.asObject.get, ctx).map(_.left.map(_.message))

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
        wsSendFn = (_: Json) => IO.unit,
        workspace = ws.toString,
        rootSessionId = "nebula-root",
        projectName = name,
        emitEvent = (_, _, _) => IO.unit
      )
      pd = ProjectDef(name = name, workspace = ws.toString, agentFile = (ws / "AGENTS.md").toString, createdAt = System.currentTimeMillis())
      rt = ProjectRuntime(pd, store, engine, system, res, None)
      _ <- ProjectRuntimeRegistry.register(rt)
    yield rt

  /** 按名取节点 id（活动区；找不到 fail 该测试）。 */
  private def idOf(rt: ProjectRuntime, name: String): IO[String] =
    rt.store.snapshot.map(_.nodes.values.find(_.name == name)).map {
      case Some(n) => n.id
      case None    => fail(s"node '$name' must exist")
    }

  private def nodeById(rt: ProjectRuntime, id: String): IO[Option[NodeDef]] =
    rt.store.snapshot.map(_.nodes.get(id))

  private def waitStatus(rt: ProjectRuntime, name: String, statuses: Set[String]): IO[Unit] =
    waitUntil(15.seconds) {
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

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  // ── T1 deps 触发且不投递 result ─────────────────────────

  test("T1 deps trigger: B starts after A completes; B input carries NO upstream result (task self-sufficient)") {
    val ws = tempRoot / "ws-t1"
    os.makeDir.all(ws)
    val system = ActorSystem(s"deps-t1-${scala.util.Random.nextInt(100000)}")
    val aGate = IO.deferred[Unit].unsafeRunSync()
    val llm = DispatchLlm(
      replyOf = t => if t.contains("produce-A") then "RESULT-OF-A" else "ok",
      gateOf = t => if t.contains("produce-A") then Some(aGate) else None
    )
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("deps-t1", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // A 入口运行中（out=Nebula 仅满足连接下限；deps 单侧持有，A 不知道被 B 依赖）
      _ <- nodeEdit(nodeInput("deps-t1", "src-a", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("produce-A"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- waitStatus(rt, "src-a", Set(NodeLifecycle.Running))
      // B 声明 deps=[A]（有 task 无 in → Pending；入口启动被 startNode 内 deps 闸门拦下）
      aId <- idOf(rt, "src-a")
      _ <- nodeEdit(nodeInput("deps-t1", "after-a", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("run-after-a"), "deps" -> Json.fromString(aId),
        "out" -> Json.fromString("Nebula")), ctx)
      partial <- rt.store.snapshot.map(_.nodes.values.find(_.name == "after-a"))
      // A 完成 → settleDeps → B 启动并完成——放行 A 门（此前 A 回复被 gateOf 挂起，
      // 保证上面的 partial 观测落在「A 仍 Running」的窗口内，B 闸门拦下证据确定性）
      _ <- aGate.complete(()).attempt.void
      _ <- waitStatus(rt, "src-a", Set(NodeLifecycle.Completed))
      _ <- waitStatus(rt, "after-a", Set(NodeLifecycle.Completed))
      bId <- idOf(rt, "after-a")
      b <- nodeById(rt, bId).map(_.getOrElse(fail("B must exist")))
      bInput <- llm.inputs.get.map(_.find(_.contains("run-after-a")))
      a <- nodeById(rt, aId).map(_.getOrElse(fail("A must exist")))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // B 在 A 完成前保持等待（闸门拦下证据）——A 的 LLM 回复被 aGate 挂在
      // 首轮（produce-A 命中），partial 读取期间 A 必然仍 Running（确定性）。
      assert(partial.exists(n => n.status == NodeLifecycle.Pending || n.status == NodeLifecycle.Wiring),
        s"B must wait while A runs, got ${partial.map(_.status)}")
      assert(b.deps.contains(aId), s"B.deps must reference A, got ${b.deps}")
      assertEquals(b.status, NodeLifecycle.Completed, "B must start after A completes (deps completion signal)")
      // 核心断言：B 输入不含 A 的 result 段（deps 不投递——下游输入 = 自身 task 自足）
      assert(bInput.isDefined, "B must have been started (input captured)")
      assert(!bInput.exists(_.contains("=== Node src-a ===")),
        s"B input must NOT carry A's result section, got: ${bInput.map(_.take(300))}")
      assert(!bInput.exists(_.contains("RESULT-OF-A")),
        s"B input must NOT contain A's result text, got: ${bInput.map(_.take(300))}")
      // A.result 不因被依赖而变化（deps 不消费上游输出）
      assertEquals(a.result, Some("RESULT-OF-A"), "A.result must stay intact")
  }

  // ── T2 in+deps 混合 barrier ─────────────────────────────

  test("T2 mixed in+deps: C waits for BOTH (in delivered + deps completed); input carries in result, never deps result") {
    val ws = tempRoot / "ws-t2"
    os.makeDir.all(ws)
    val system = ActorSystem(s"deps-t2-${scala.util.Random.nextInt(100000)}")
    val llm = DispatchLlm(
      replyOf = t =>
        if t.contains("src-a-fast") then "RESULT-OF-A"
        else if t.contains("dep-b-slow") then "RESULT-OF-B"
        else "ok",
      delayOf = t => if t.contains("dep-b-slow") then 2000.millis else 0.millis
    )
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("deps-t2", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // B：deps 上游（entry，慢——制造 running 窗口；out=Nebula，deps 单侧持有不回写）
      _ <- nodeEdit(nodeInput("deps-t2", "dep-b", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("dep-b-slow"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- waitStatus(rt, "dep-b", Set(NodeLifecycle.Running))
      // A：in 上游（entry，快——先于 C 创建完成）
      _ <- nodeEdit(nodeInput("deps-t2", "src-a", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("src-a-fast"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- waitStatus(rt, "src-a", Set(NodeLifecycle.Completed))
      aId <- idOf(rt, "src-a")
      bId <- idOf(rt, "dep-b")
      // C：in=[A] + deps=[B]（A 已完成 → D1 in-delivery 归零 barrier → startNode 被 deps 闸门拦下）
      _ <- nodeEdit(nodeInput("deps-t2", "merge-c", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("merge-both"), "in" -> Json.fromString(aId),
        "deps" -> Json.fromString(bId), "out" -> Json.fromString("Nebula")), ctx)
      cId <- idOf(rt, "merge-c")
      // B 完成前：C 的 in barrier 已归零（deliveredTo 含 A）但 deps 未满足 → 保持等待
      _ <- waitUntil(3.seconds)(nodeById(rt, cId).map(_.exists(_.deliveredTo.contains(aId))))
      gated <- nodeById(rt, cId).map(_.getOrElse(fail("C must exist")))
      // B 完成 → settleDeps → startNode(C) → 闸门全开 → C 完成且输入含 in 段、绝无 deps 段
      _ <- waitStatus(rt, "dep-b", Set(NodeLifecycle.Completed))
      _ <- waitStatus(rt, "merge-c", Set(NodeLifecycle.Completed))
      c <- nodeById(rt, cId).map(_.getOrElse(fail("C must exist")))
      cInput <- llm.inputs.get.map(_.find(_.contains("merge-both")))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(gated.status, NodeLifecycle.Wiring,
        s"C must stay waiting while deps-upstream B runs (in barrier already zero), got ${gated.status}")
      assert(gated.deliveredTo.contains(aId), s"in result must be delivered before deps satisfied, got ${gated.deliveredTo}")
      assertEquals(c.status, NodeLifecycle.Completed, "C must start once BOTH waits are satisfied")
      assert(cInput.exists(_.contains("=== Node src-a ===")), s"C input must carry IN upstream result section, got ${cInput.map(_.take(300))}")
      assert(cInput.exists(_.contains("RESULT-OF-A")), "C input must carry IN upstream result text")
      // deps 上游的 result 绝不注入（B 是 deps 不是 in）
      assert(!cInput.exists(_.contains("=== Node dep-b ===")), "C input must NOT carry deps upstream section")
      assert(!cInput.exists(_.contains("RESULT-OF-B")), "C input must NOT contain deps upstream result text")
  }

  // ── T3 混合图环检测（store 层 + NodeEdit e2e）────────────

  test("T3 mixed-graph cycle: store-level transitive out+deps chain detected; NodeEdit deps edge rejected with cycle error, store unchanged") {
    val ws = tempRoot / "ws-t3"
    os.makeDir.all(ws)
    val system = ActorSystem(s"deps-t3-${scala.util.Random.nextInt(100000)}")
    val llm = DispatchLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("deps-t3", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      now = System.currentTimeMillis()
      // store 层混合传递链：A.out=B（流 A→B），C.deps=[B]（流 B→C）。
      // 追加 C→A 边（in 或 deps 同向）= A→B→C→A 环 → wouldCreateCycle(C, A) 必须 true
      _ <- rt.store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        "n-a" -> NodeDef(id = "n-a", name = "A", agent = "test-agent", out = Some("n-b"), createdAt = now),
        "n-b" -> NodeDef(id = "n-b", name = "B", agent = "test-agent", createdAt = now),
        "n-c" -> NodeDef(id = "n-c", name = "C", agent = "test-agent", deps = List("n-b"), createdAt = now)
      )))
      cycleStore <- rt.store.wouldCreateCycle("n-c", "n-a")
      noCycle <- rt.store.wouldCreateCycle("n-a", "n-c") // 反向链不成环
      // e2e：node-b(wiring) ← node-a(out→node-b，流 A→B)。再给 A 声明 deps=[B] = B→A(deps) → 环。
      // node-b store 直种（20260903 创建必带 out 新规范下 out-only wiring 节点不可经 NodeEdit 创建）
      _ <- rt.store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        "n-e2e-b" -> NodeDef(id = "n-e2e-b", name = "node-b", agent = "test-agent",
          status = NodeLifecycle.Wiring, out = Some("Nebula"), createdAt = System.currentTimeMillis()))))
      bE2e <- idOf(rt, "node-b")
      _ <- nodeEdit(nodeInput("deps-t3", "node-a", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("work"), "out" -> Json.fromString(bE2e)), ctx)
      // running 节点编辑被冻结（校验三）——等 A 终态后再测 deps 环检
      _ <- waitStatus(rt, "node-a", Set(NodeLifecycle.Completed))
      aE2e <- idOf(rt, "node-a")
      r <- nodeEdit(nodeInput("deps-t3", "node-a", "deps" -> Json.fromString(bE2e)), ctx)
      aAfter <- nodeById(rt, aE2e).map(_.getOrElse(fail("node-a must exist")))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // store 层：混合传递链成环判定
      assert(cycleStore, "mixed out+deps transitive chain must be detected as cycle (wouldCreateCycle(C, A))")
      assert(!noCycle, "reverse direction must not be a cycle")
      // e2e：NodeEdit 返回 cycle 错误，store 无新边
      assert(r.isLeft, s"deps cycle must be rejected by NodeEdit, got: $r")
      assert(r.left.exists(_.contains("Cycle")), s"error must mention cycle, got: $r")
      assertEquals(aAfter.deps, Nil, "store must be unchanged after rejected deps edge (no new edge)")
  }

  // ── T4 D1-deps 补触发（活动区 + 归档变体）────────────────

  test("T4 D1-deps: wiring deps on ALREADY-completed upstream starts immediately — active area and archived variant (findNode fallback)") {
    val ws1 = tempRoot / "ws-t4-active"
    os.makeDir.all(ws1)
    val ws2 = tempRoot / "ws-t4-archived"
    os.makeDir.all(ws2)
    val system = ActorSystem(s"deps-t4-${scala.util.Random.nextInt(100000)}")
    val llm = DispatchLlm(replyOf = t => if t.contains("done-a") then "RESULT-OF-A" else "ok")
    for
      res <- mkResources(system, tempRoot, llm.handle)
      // ── 变体 1：活动区接线（上游 completed 仍在活动区）──
      rt1 <- mountProject("deps-t4-active", ws1, system, res)
      ctx1 = mkCtx(res, system, ws1.toString)
      _ <- nodeEdit(nodeInput("deps-t4-active", "done-a", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("done-a"), "out" -> Json.fromString("Nebula")), ctx1)
      _ <- waitStatus(rt1, "done-a", Set(NodeLifecycle.Completed))
      aId1 <- idOf(rt1, "done-a")
      // 上游已 completed 后接 deps 边 → D1-deps 立即触发
      _ <- nodeEdit(nodeInput("deps-t4-active", "late-b", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("after-a-work"), "deps" -> Json.fromString(aId1),
        "out" -> Json.fromString("Nebula")), ctx1)
      _ <- waitStatus(rt1, "late-b", Set(NodeLifecycle.Completed))
      bInput <- llm.inputs.get.map(_.find(_.contains("after-a-work")))
      // ── 变体 2：归档后接线（completed 上游已 TTL 归档——findNode 归档兜底）──
      rt2 <- mountProject("deps-t4-archived", ws2, system, res)
      ctx2 = mkCtx(res, system, ws2.toString)
      _ <- nodeEdit(nodeInput("deps-t4-archived", "done-a", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("done-a"), "out" -> Json.fromString("Nebula")), ctx2)
      _ <- waitStatus(rt2, "done-a", Set(NodeLifecycle.Completed))
      aId2 <- idOf(rt2, "done-a")
      _ <- rt2.store.mutate(s =>
        s.nodes.get(aId2).map(n => s.copy(nodes = s.nodes.updated(aId2, n.copy(ttlExpireAt = Some(System.currentTimeMillis() - 1000)))))
          .getOrElse(s)).void
      swept <- rt2.store.sweepCompletedChains(System.currentTimeMillis())
      archived <- rt2.store.archiveSnapshot
      // 上游已归档后接 deps 边 → findNode 归档兜底 → D1-deps 立即触发（「归档上游可触发」裁定）
      _ <- nodeEdit(nodeInput("deps-t4-archived", "late-c", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("after-archived-work"), "deps" -> Json.fromString(aId2),
        "out" -> Json.fromString("Nebula")), ctx2)
      _ <- waitStatus(rt2, "late-c", Set(NodeLifecycle.Completed))
      cInput <- llm.inputs.get.map(_.find(_.contains("after-archived-work")))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // 变体 1 断言：B 立即启动并完成；输入无上游 result 段
      assert(bInput.isDefined, "variant-1: B must be triggered by D1-deps immediately")
      assert(!bInput.exists(_.contains("=== Node done-a ===")), "variant-1: B input must NOT carry A result section")
      assert(!bInput.exists(_.contains("RESULT-OF-A")), "variant-1: B input must NOT contain A result text")
      // 变体 2 断言：归档完成、C 立即启动、输入无上游 result
      assertEquals(swept, List(aId2), "variant-2: done-a must be swept to archive")
      assert(archived.nodes.contains(aId2), "variant-2: done-a must live in archive area")
      assert(cInput.isDefined, "variant-2: C must be triggered by D1-deps from ARCHIVED upstream")
      assert(!cInput.exists(_.contains("RESULT-OF-A")), "variant-2: C input must NOT contain archived upstream result")
  }

  // ── T5 failed 不触发 deps 下游（对照 in 边 collect）────────

  test("T5 failed upstream: deps waiter stays pending and visible; in-edge waiter is collected and starts (behavior difference locked)") {
    val ws = tempRoot / "ws-t5"
    os.makeDir.all(ws)
    val system = ActorSystem(s"deps-t5-${scala.util.Random.nextInt(100000)}")
    // 失败延迟 800ms：保留 Running 窗口（即时失败会让 waitStatus(Running) 永不命中）
    val llm = FailOnLlm(
      failWhen = _.contains("boom-a"),
      replyOf = t => if t.contains("in-waiter") then "in-side-ok" else "ok",
      delayBeforeFail = 800.millis)
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("deps-t5", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // 对照组：C2（wiring, out=Nebula）← A2（entry, boom-a2, out→C2）——in 边。
      // C2 store 直种（20260903 创建必带 out 新规范下 out-only wiring 节点不可经 NodeEdit 创建）
      _ <- rt.store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        "n-in-waiter" -> NodeDef(id = "n-in-waiter", name = "in-waiter", agent = "test-agent",
          status = NodeLifecycle.Wiring, out = Some("Nebula"), createdAt = System.currentTimeMillis()))))
      c2Id <- idOf(rt, "in-waiter")
      _ <- nodeEdit(nodeInput("deps-t5", "src-a2", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("boom-a2"), "out" -> Json.fromString(c2Id)), ctx)
      // 实验组：A（entry, boom-a）+ B deps=[A]
      _ <- nodeEdit(nodeInput("deps-t5", "src-a", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("boom-a"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- waitStatus(rt, "src-a", Set(NodeLifecycle.Running))
      aId <- idOf(rt, "src-a")
      _ <- nodeEdit(nodeInput("deps-t5", "dep-waiter", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("dep-waiter-work"), "deps" -> Json.fromString(aId),
        "out" -> Json.fromString("Nebula")), ctx)
      // 双双失败
      _ <- waitStatus(rt, "src-a", Set(NodeLifecycle.Failed))
      _ <- waitStatus(rt, "src-a2", Set(NodeLifecycle.Failed))
      _ <- IO.sleep(800.millis) // 给「假如 deps 被误触发」留窗口
      bId <- idOf(rt, "dep-waiter")
      b <- nodeById(rt, bId).map(_.getOrElse(fail("B must exist")))
      c2 <- nodeById(rt, c2Id).map(_.getOrElse(fail("C2 must exist")))
      bInputs <- llm.inputs.get.map(_.filter(_.contains("dep-waiter-work")))
      // C2 无 task（wiring）→ 输入 = 上游错误投递段 + 脚注；按 in 投递头定位其输入
      c2Input <- llm.inputs.get.map(_.find(_.contains("=== Node src-a2 ===")))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // deps 边：failed ∉ completed → B 保持 pending/wiring 可见（分发器可自行处置）
      assert(b.status == NodeLifecycle.Pending || b.status == NodeLifecycle.Wiring,
        s"deps waiter must stay pending after upstream failed, got ${b.status}")
      assert(bInputs.isEmpty, s"deps waiter must NEVER be started by failed upstream, got ${bInputs.map(_.take(120))}")
      // 对照 in 边：failed 走 deliverFailed collect 结算 → C2 被触发（「带错继续」）
      assertEquals(c2.status, NodeLifecycle.Completed, "in-edge waiter must be collected and start (collect semantics)")
      assert(c2Input.isDefined, "in-edge waiter must have been started")
      assert(c2Input.exists(_.contains("boom-exploded")), "in-edge collect carries failed upstream error string as result")
  }

  // ── T6 创建连接校验（校验五，20260903 收紧：(task ∨ in) ∧ out）──────

  test("T6 creation connection policy: no-out rejected (task-only / deps-only), out-only rejected (no input side); task+out and in+out pass") {
    val ws = tempRoot / "ws-t6"
    os.makeDir.all(ws)
    val system = ActorSystem(s"deps-t6-${scala.util.Random.nextInt(100000)}")
    val llm = DispatchLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("deps-t6", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // task-only（无 out）→ 拒：悬空新节点废弃（校验五 out 强制）
      r1 <- nodeEdit(nodeInput("deps-t6", "lonely", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("no connection at all")), ctx)
      s1 <- rt.store.snapshot
      // 对照：仅 out=Nebula（无 task/in）→ 拒：out-only 中继废弃（in 侧下限 (task ∨ in)）
      r2 <- nodeEdit(nodeInput("deps-t6", "nebula-only", "description" -> Json.fromString("test node purpose"),
        "out" -> Json.fromString("Nebula")), ctx)
      // 对照：仅 deps（无 out）→ 拒：deps 不计入连接（须带 out）
      _ <- nodeEdit(nodeInput("deps-t6", "up", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("up-work"), "out" -> Json.fromString("Nebula")), ctx)
      upId <- idOf(rt, "up")
      r3 <- nodeEdit(nodeInput("deps-t6", "deps-only", "description" -> Json.fromString("test node purpose"),
        "deps" -> Json.fromString(upId)), ctx)
      // 合法域：task+out（入口）与 in+out（wiring）→ 过
      r4 <- nodeEdit(nodeInput("deps-t6", "entry-ok", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("entry-work"), "out" -> Json.fromString("Nebula")), ctx)
      r5 <- nodeEdit(nodeInput("deps-t6", "wire-ok", "description" -> Json.fromString("test node purpose"),
        "in" -> Json.fromString(upId), "out" -> Json.fromString("Nebula")), ctx)
      s2 <- rt.store.snapshot
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r1.isLeft, s"task-only create (no out) must be rejected, got: $r1")
      assert(r1.left.exists(_.contains("must declare 'out'")), s"message must state the out rule, got: $r1")
      assert(r1.left.exists(_.contains("EMPTY_NODE_CONNECTION")), s"r1 must carry EMPTY_NODE_CONNECTION code, got: $r1")
      assert(s1.nodes.values.find(_.name == "lonely").isEmpty, "store must have no new node after rejection")
      assert(r2.isLeft, s"out-only create (no task/in) must be rejected under the tightened policy, got: $r2")
      assert(r2.left.exists(_.contains("must declare an input side")), s"r2 message must state the input-side rule, got: $r2")
      assert(r3.isLeft, s"deps-only create (no out) must be rejected, got: $r3")
      assert(r3.left.exists(_.contains("must declare 'out'")), s"r3 message must state the out rule, got: $r3")
      assert(r4.isRight, s"task+out entry create must pass, got: $r4")
      assert(r5.isRight, s"in+out wiring create must pass, got: $r5")
      assert(s2.nodes.values.exists(n => n.name == "wire-ok" && n.in.contains(upId)), "wire-ok node must carry in")
  }

  // ── T7 编辑路径连接校验（校验六零连接下限 + 校验六-a 断开拒绝，20260903 收紧）──

  test("T7 edit connection policy: disconnect-to-no-out rejected; clearing deps with connections left passes; zero-connection floor unchanged") {
    val ws = tempRoot / "ws-t7"
    os.makeDir.all(ws)
    val system = ActorSystem(s"deps-t7-${scala.util.Random.nextInt(100000)}")
    // slow-up 运行 8s：7b/7c 的 deps 目标保持未完成 → 节点确定停留在 wiring（闸门拦下），
    // 排除「D1-deps 先启动节点 → 撞上 running 冻结而非零连接拒绝」的竞态
    val llm = DispatchLlm(delayOf = t => if t.contains("slow-up") then 8000.millis else 0.millis)
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("deps-t7", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // 慢上游（7b/7c 的 deps 目标，测试窗口内保持 running）
      _ <- nodeEdit(nodeInput("deps-t7", "slow-up", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("slow-up"), "out" -> Json.fromString("Nebula")), ctx)
      slowId <- idOf(rt, "slow-up")
      upId <- nodeEdit(nodeInput("deps-t7", "up", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("up-work"), "out" -> Json.fromString("Nebula")), ctx) *> idOf(rt, "up")
      // 7a：持 out 的 wiring 节点断开 out → 拒（校验六-a：断开一律拒绝）。setup 补
      // in=[slow-up] 适配创建新规范（原 out-only 创建已非法），r7a 断言一字未动
      _ <- nodeEdit(nodeInput("deps-t7", "only-out", "description" -> Json.fromString("test node purpose"),
        "in" -> Json.fromString(slowId), "out" -> Json.fromString("Nebula")), ctx)
      r7a <- nodeEdit(nodeInput("deps-t7", "only-out", "out" -> Json.Null), ctx)
      // 7b：仅持 deps 的 wiring 节点清空 deps → 拒（校验六零连接下限不变）。存量式
      // 形态——新规范下 deps-only 不可经 NodeEdit 创建，改 store 直种保持断言原样
      _ <- rt.store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        "n-onlydeps" -> NodeDef(id = "n-onlydeps", name = "only-deps", agent = "test-agent",
          deps = List(slowId), status = NodeLifecycle.Wiring, createdAt = System.currentTimeMillis()))))
      r7b <- nodeEdit(nodeInput("deps-t7", "only-deps", "deps" -> Json.arr()), ctx)
      // 7c 合法对照：多连接并持，清空 deps 仍剩 in+out → 成功（setup 补 in 适配创建新规范）
      _ <- nodeEdit(nodeInput("deps-t7", "out-and-deps", "description" -> Json.fromString("test node purpose"),
        "in" -> Json.fromString(slowId), "out" -> Json.fromString("Nebula"), "deps" -> Json.fromString(slowId)), ctx)
      r7c <- nodeEdit(nodeInput("deps-t7", "out-and-deps", "deps" -> Json.Null), ctx)
      odAfter <- idOf(rt, "out-and-deps").flatMap(nodeById(rt, _).map(_.getOrElse(fail("must exist"))))
      // 7d 对照（新规范翻转）：in + out 并持，断开 out → 拒（校验六-a 取代旧「剩 in 即过」）
      _ <- nodeEdit(nodeInput("deps-t7", "in-and-out", "description" -> Json.fromString("test node purpose"),
        "in" -> Json.fromString(upId), "out" -> Json.fromString("Nebula")), ctx)
      ioId <- idOf(rt, "in-and-out")
      r7d <- nodeEdit(nodeInput("deps-t7", "in-and-out", "out" -> Json.Null), ctx)
      ioAfter <- nodeById(rt, ioId).map(_.getOrElse(fail("must exist")))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r7a.isLeft, s"disconnecting out must be rejected, got: $r7a")
      assert(r7a.left.exists(_.contains("EMPTY_NODE_CONNECTION")), s"7a must carry EMPTY_NODE_CONNECTION code, got: $r7a")
      assert(r7b.isLeft, s"clearing the only deps list must be rejected, got: $r7b")
      assert(r7b.left.exists(_.contains("EMPTY_NODE_CONNECTION")), s"7b must carry EMPTY_NODE_CONNECTION code, got: $r7b")
      assert(r7c.isRight, s"clearing deps while in+out remain must pass, got: $r7c")
      assertEquals(odAfter.deps, Nil, "7c: deps must be cleared (replace-on-provide)")
      assertEquals(odAfter.out, Some("Nebula"), "7c: out must remain")
      assert(r7d.isLeft, s"disconnecting out must be rejected under the tightened policy, got: $r7d")
      assert(r7d.left.exists(_.contains("EMPTY_NODE_CONNECTION")), s"7d must carry EMPTY_NODE_CONNECTION code, got: $r7d")
      assertEquals(ioAfter.out, Some("Nebula"), "7d: out must remain (disconnect rejected)")
      assert(ioAfter.in.contains(upId), "7d: in must remain untouched")
  }

  // ── T8 abandon 接受域扩展（§1.6 裁定①）──────────────────

  test("T8 abandon domain extension: wiring abandonable (cancelled+TTL+audit); running refused; upstream completion after abandon skips idempotently") {
    val ws = tempRoot / "ws-t8"
    os.makeDir.all(ws)
    val system = ActorSystem(s"deps-t8-${scala.util.Random.nextInt(100000)}")
    val llm = DispatchLlm(delayOf = t => if t.contains("slow-a") then 2500.millis else 0.millis)
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("deps-t8", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // slow-a 入口运行中（制造 running 窗口）
      _ <- nodeEdit(nodeInput("deps-t8", "slow-a", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("slow-a"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- waitStatus(rt, "slow-a", Set(NodeLifecycle.Running))
      slowId <- idOf(rt, "slow-a")
      // wiring 节点 abandon → cancelled + display TTL + 审计。
      // w-wire store 直种（20260903 创建必带 out 新规范下 out-only wiring 节点不可经 NodeEdit 创建）
      _ <- rt.store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        "n-w-wire" -> NodeDef(id = "n-w-wire", name = "w-wire", agent = "test-agent",
          status = NodeLifecycle.Wiring, out = Some("Nebula"), createdAt = System.currentTimeMillis()))))
      wId <- idOf(rt, "w-wire")
      rWire <- nodeEdit(nodeInput("deps-t8", "w-wire", "abandon" -> Json.fromBoolean(true)), ctx)
      _ <- IO.sleep(200.millis)
      wAfter <- nodeById(rt, wId).map(_.getOrElse(fail("w-wire must exist")))
      audit1 <- readAuditTypes(ws)
      // running abandon 仍拒绝（走 NodeCancel）
      rRun <- nodeEdit(nodeInput("deps-t8", "slow-a", "abandon" -> Json.fromBoolean(true)), ctx)
      // 被退役的 deps 等待者：W2 deps=[slow-a] wiring → abandon → slow-a 完成后不触发
      _ <- nodeEdit(nodeInput("deps-t8", "w2-dep", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("never-should-run"), "deps" -> Json.fromString(slowId),
        "out" -> Json.fromString("Nebula")), ctx)
      w2Id <- idOf(rt, "w2-dep")
      rW2 <- nodeEdit(nodeInput("deps-t8", "w2-dep", "abandon" -> Json.fromBoolean(true)), ctx)
      _ <- waitStatus(rt, "slow-a", Set(NodeLifecycle.Completed))
      _ <- IO.sleep(500.millis) // 给「假如被误触发」留窗口
      w2After <- nodeById(rt, w2Id).map(_.getOrElse(fail("w2 must exist")))
      w2Inputs <- llm.inputs.get.map(_.filter(_.contains("never-should-run")))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // wiring abandon：接受（「悬空活节点」处置出口）
      assert(rWire.isRight, s"wiring node must be abandonable, got: $rWire")
      assertEquals(wAfter.status, NodeLifecycle.Cancelled, "abandoned wiring node becomes cancelled")
      assert(wAfter.ttlExpireAt.isDefined, "abandoned wiring node gets display TTL")
      assert(audit1.exists((t, id) => t == "abandoned" && id == wId), s"abandoned audit event must be logged, got: $audit1")
      // running 仍拒绝
      assert(rRun.isLeft, s"running node abandon must be refused, got: $rRun")
      assert(rRun.left.exists(m => m.contains("running") && m.contains("NodeCancel")), s"refusal must point to NodeCancel, got: $rRun")
      // 退役的 deps 等待者：上游其后完成 → startNode 幂等跳过（cancelled ∈ Terminal）
      assert(rW2.isRight, s"deps-waiting wiring node must be abandonable, got: $rW2")
      assertEquals(w2After.status, NodeLifecycle.Cancelled, "retired waiter stays cancelled after upstream completes")
      assert(w2Inputs.isEmpty, s"retired waiter must never run, got ${w2Inputs.map(_.take(120))}")
  }

end NodeDepsSpec
