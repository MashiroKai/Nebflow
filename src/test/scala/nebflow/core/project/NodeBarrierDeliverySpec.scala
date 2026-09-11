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
import nebflow.core.tools.{FileLockManager, NodeEditTool, ToolContext}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * barrier 投递 bug 根因修复回归（真实轮次，NodeAcceptanceSpec 同款基建）：
 *
 * - 主因修复：终态化不再沿启动时捕获的陈旧 NodeDef 写回——completeNode/failNode/
 *   cancelNode 在 mutate 事务内现读 fresh 节点，投递沿 fresh.out（NodeEngine）。
 *   ② 串联 A→B：A 完成后 B 自动收到结果并启动；
 *   ③ 并行 barrier A、B→C：两上游完成后 C 才启动且输入含两份结果；
 *   ⑤ 运行中接线竞态（核心）：A running 时建 C 接 in=[A] → A 完成 → C 收到结果
 *     并启动（修复前：陈旧 out 覆盖 + 悬空投递，C 永久 wiring）。
 * - 次因 A 修复：editNode in 追加补 D1 等价投递 + barrier 结算复查（NodeTools）。
 *   ④ 改接补投递（create 路径已有 D1 + edit 路径本次补齐）；
 *   ⑥ edit 路径 in 追加已完成上游 → 立即投递（部分 barrier 场景）。
 * - 附带修复：加载净化存量 out="null" 字面串（FlowMapStore）。
 */
class NodeBarrierDeliverySpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-node-barrier-delivery"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "test-agent")
  os.write.over(
    tempRoot / "agents" / "test-agent" / "agent.json",
    """{"name":"test-agent","description":"barrier regression agent","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "test-agent" / "system.md", "# test-agent\n")
  // 2026-09-05 agent 退役：新建节点执行统一 general——fixture 侧补 general agent
  os.makeDir.all(tempRoot / "agents" / "general")
  os.write.over(tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"general executor","tools":[],"category":"standalone"}""")
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  /** 捕获输入 LLM：记录每次请求的 user 文本（断言下游节点输入含上游结果）。
    * 逐请求延迟由 delayOf（按输入内容）决定——构造指定节点的 running 窗口。 */
  private class CaptureLlm(delayOf: String => FiniteDuration = _ => 0.millis):
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
          .flatMap(_ => Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None)))

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
      sessionId = Some("barrier-sid"),
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
        emitEvent = (_, _, _) => IO.unit,
        // noderpt 批 A 段：本 fixture 主题非 node_report 语义 ⇒ 显式关腿 2（生产默认开；
        // 腿 2 默认开行为由 NodeReportReminderSpec 覆盖）。
        reportGateHold = Some(false)
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

  /** 等待某名节点到达给定状态集合。 */
  private def waitStatus(rt: ProjectRuntime, name: String, statuses: Set[String]): IO[Unit] =
    waitUntil(15.seconds) {
      rt.store.snapshot.map(_.nodes.values.find(_.name == name)).flatMap {
        case Some(n) => IO.pure(statuses.contains(n.status))
        case None    => IO.pure(false)
      }
    }

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  // ── ② 串联 A→B ─────────────────────────────────────────

  test("② chain: A completes → B receives A's result and starts (fresh.out delivery)") {
    val ws = tempRoot / "ws-chain"
    os.makeDir.all(ws)
    val system = ActorSystem(s"bar-chain-${scala.util.Random.nextInt(100000)}")
    val llm = CaptureLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("bar-chain", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // 先建 B（wiring，无 task），再建入口 A 且 out → B。
      // B store 直种（20260903 创建必带 out 新规范下 out-only wiring 节点不可经 NodeEdit 创建）
      _ <- rt.store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        "n-b" -> NodeDef(id = "n-b", name = "node-b", agent = "test-agent",
          status = NodeLifecycle.Wiring, out = List(OutEdge.nebula), createdAt = System.currentTimeMillis()))))
      bId <- idOf(rt, "node-b")
      _ <- nodeEdit(nodeInput("bar-chain", "node-a", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("produce-X"), "out" -> Json.fromString(bId)), ctx)
      // A 完成（后台）→ deliverOut 沿 fresh.out → B barrier 归零启动 → B 完成
      _ <- waitStatus(rt, "node-a", Set(NodeLifecycle.Completed))
      _ <- waitStatus(rt, "node-b", Set(NodeLifecycle.Completed))
      aId <- idOf(rt, "node-a")
      b <- nodeById(rt, bId)
      bInput <- llm.inputs.get.map(_.find(_.contains(s"=== Node node-a ===")))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(b.exists(_.in.contains(aId)), s"B.in must reference A, got ${b.map(_.in)}")
      assert(b.exists(_.deliveredTo.contains(aId)), s"B must have received A's delivery, got ${b.map(_.deliveredTo)}")
      assertEquals(b.map(_.status), Some(NodeLifecycle.Completed), "B must have run to completion")
      // A 的 result = LLM 应答 "ok"；B 输入 = own task + "=== Node node-a ===\n<result>"
      assert(bInput.exists(_.contains("=== Node node-a ===")),
        s"B's input must carry A's result header, got: ${bInput.map(_.take(300))}")
  }

  // ── ③ 并行 barrier A、B→C ──────────────────────────────

  test("③ barrier: C starts only after BOTH A and B complete, input carries both results") {
    val ws = tempRoot / "ws-parallel"
    os.makeDir.all(ws)
    val system = ActorSystem(s"bar-par-${scala.util.Random.nextInt(100000)}")
    val llm = CaptureLlm(_ => 300.millis) // 轻微延迟制造并行窗口
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("bar-parallel", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // merge-c store 直种（20260903 创建必带 out 新规范下 out-only wiring 节点不可经 NodeEdit 创建）
      _ <- rt.store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        "n-merge-c" -> NodeDef(id = "n-merge-c", name = "merge-c", agent = "test-agent",
          status = NodeLifecycle.Wiring, out = List(OutEdge.nebula), createdAt = System.currentTimeMillis()))))
      cId <- idOf(rt, "merge-c")
      _ <- nodeEdit(nodeInput("bar-parallel", "src-a", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("result-of-A"), "out" -> Json.fromString(cId)), ctx)
      _ <- nodeEdit(nodeInput("bar-parallel", "src-b", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("result-of-B"), "out" -> Json.fromString(cId)), ctx)
      _ <- waitStatus(rt, "src-a", Set(NodeLifecycle.Completed))
      _ <- waitStatus(rt, "src-b", Set(NodeLifecycle.Completed))
      _ <- waitStatus(rt, "merge-c", Set(NodeLifecycle.Completed))
      aId <- idOf(rt, "src-a")
      bId <- idOf(rt, "src-b")
      c <- nodeById(rt, cId)
      cInput <- llm.inputs.get.map(_.find(t => t.contains("=== Node src-a ===") && t.contains("=== Node src-b ===")))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(c.isDefined, "C must exist")
      assertEquals(c.get.in.toSet, Set(aId, bId), s"C.in must hold both upstreams, got ${c.get.in}")
      assertEquals(c.get.deliveredTo.toSet, Set(aId, bId), s"C must have received BOTH results, got ${c.get.deliveredTo}")
      assertEquals(c.get.status, NodeLifecycle.Completed, "C must start after barrier and complete")
      assert(cInput.isDefined,
        s"C's input must contain BOTH upstream results (=== headers), got inputs=${llm.inputs.get.unsafeRunSync().map(_.take(150))}")
  }

  // ── ④ 改接补投递（create 路径 D1 + edit 路径补齐）───────

  test("④a create-path: completed dangling A + new B with in=[A] → auto delivered and started (D1)") {
    val ws = tempRoot / "ws-d1-create"
    os.makeDir.all(ws)
    val system = ActorSystem(s"bar-d1c-${scala.util.Random.nextInt(100000)}")
    val llm = CaptureLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("bar-d1-create", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // A 入口（悬空完成：out=Nebula 仅满足连接下限，Nebula 通知在测试环境无根会话，无断言影响）
      _ <- nodeEdit(nodeInput("bar-d1-create", "done-a", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("dangling-result-A"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- waitStatus(rt, "done-a", Set(NodeLifecycle.Completed))
      aId <- idOf(rt, "done-a")
      // 新建 B 接 in=[A]（JSON 数组字符串形态，顺带回归次因 B）→ D1 自动投递 + 启动
      _ <- nodeEdit(nodeInput("bar-d1-create", "consumer-b", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("consume"), "in" -> Json.fromString(s"""["$aId"]"""),
        "out" -> Json.fromString("Nebula")), ctx)
      _ <- waitStatus(rt, "consumer-b", Set(NodeLifecycle.Completed))
      bId <- idOf(rt, "consumer-b")
      b <- nodeById(rt, bId).map(_.getOrElse(fail("B must exist")))
      bInput <- llm.inputs.get.map(_.find(_.contains("=== Node done-a ===")))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(b.in, List(aId), s"B.in must reference A (parsed from JSON-array-string), got ${b.in}")
      assert(b.deliveredTo.contains(aId), s"B must have received A's result, got ${b.deliveredTo}")
      assertEquals(b.status, NodeLifecycle.Completed, "B must start after delivery")
      assert(bInput.isDefined, s"B's input must contain A's result header, got inputs=${llm.inputs.get.unsafeRunSync().map(_.take(120))}")
  }

  test("④b edit-path: completed dangling A + EXISTING wiring W appends in=[A] → auto delivered and started (补齐)") {
    val ws = tempRoot / "ws-d1-edit"
    os.makeDir.all(ws)
    val system = ActorSystem(s"bar-d1e-${scala.util.Random.nextInt(100000)}")
    val llm = CaptureLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("bar-d1-edit", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // A 悬空完成（out=Nebula 仅满足连接下限，校验五——测试环境无 Nebula 根会话无副作用）
      _ <- nodeEdit(nodeInput("bar-d1-edit", "done-a", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("dangling-result-A"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- waitStatus(rt, "done-a", Set(NodeLifecycle.Completed))
      aId0 <- idOf(rt, "done-a")
      // 已存在 wiring 节点 W（无 task，store 直种——20260903 创建必带 out 新规范下
      // out-only wiring 节点不可经 NodeEdit 创建；本用例主体是 edit-append 路径，种子等价）
      _ <- rt.store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        "n-wiring-w" -> NodeDef(id = "n-wiring-w", name = "wiring-w", agent = "test-agent",
          status = NodeLifecycle.Wiring, out = List(OutEdge.nebula), createdAt = System.currentTimeMillis()))))
      // edit 路径追加 in=[A]（修复次因 A）→ 立即投递 + barrier 结算启动
      _ <- nodeEdit(nodeInput("bar-d1-edit", "wiring-w", "in" -> Json.fromString(aId0)), ctx)
      _ <- waitStatus(rt, "wiring-w", Set(NodeLifecycle.Completed))
      aId <- idOf(rt, "done-a")
      wId <- idOf(rt, "wiring-w")
      w <- nodeById(rt, wId).map(_.getOrElse(fail("W must exist")))
      wInput <- llm.inputs.get.map(_.find(_.contains("=== Node done-a ===")))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(w.in.contains(aId), s"W.in must reference A after append, got ${w.in}")
      assert(w.deliveredTo.contains(aId), s"W must have received A's result via edit-append, got ${w.deliveredTo}")
      assertEquals(w.status, NodeLifecycle.Completed, "W must start after appended delivery")
      assert(wInput.isDefined, s"W's input must contain A's result header, got inputs=${llm.inputs.get.unsafeRunSync().map(_.take(120))}")
  }

  // ── ⑤ 运行中接线竞态（本次核心）────────────────────────

  test("⑤ race: wire C.in=[A] while A is RUNNING → A completes → C receives result and starts (陈旧 out 不再覆盖)") {
    val ws = tempRoot / "ws-race"
    os.makeDir.all(ws)
    val system = ActorSystem(s"bar-race-${scala.util.Random.nextInt(100000)}")
    val llm = CaptureLlm(_ => 1500.millis) // A 运行窗口
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("bar-race", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // A 入口运行中（out 仅满足连接下限；启动时捕获的快照 out 将被运行中接线改写为 C）。
      // 2026-09-12 批 A1：字面用**显式门集** `(pass,failed)Nebula`（= 通知汇报边
      // `OutEdge.nebula`）；bare `"Nebula"` 今日已收敛为纯出口标记（`{pass}/signal`）。
      _ <- nodeEdit(nodeInput("bar-race", "race-a", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("race-result-A"), "out" -> Json.fromString("(pass,failed)Nebula")), ctx)
      _ <- waitStatus(rt, "race-a", Set(NodeLifecycle.Running))
      // A 运行中建 C 并接线 in=[A]（NodeTools.setOut 改写运行中节点的 out）。
      // C store 直种（20260903 创建必带 out 新规范下 out-only wiring 节点不可经
      // NodeEdit 创建；本用例主体是运行中接线竞态，种子等价）
      _ <- rt.store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        "n-race-c" -> NodeDef(id = "n-race-c", name = "race-c", agent = "test-agent",
          status = NodeLifecycle.Wiring, out = List(OutEdge.nebula), createdAt = System.currentTimeMillis()))))
      cId <- idOf(rt, "race-c")
      aId0 <- idOf(rt, "race-a")
      _ <- nodeEdit(nodeInput("bar-race", "race-c", "in" -> Json.fromString(aId0)), ctx)
      aId <- idOf(rt, "race-a")
      // A 完成 → 终态化须沿 fresh.out（=C）投递，而非启动快照的 out=None
      _ <- waitStatus(rt, "race-a", Set(NodeLifecycle.Completed))
      _ <- waitStatus(rt, "race-c", Set(NodeLifecycle.Completed))
      aAfter <- nodeById(rt, aId).map(_.getOrElse(fail("A must exist")))
      c <- nodeById(rt, cId).map(_.getOrElse(fail("C must exist")))
      cInput <- llm.inputs.get.map(_.find(_.contains("=== Node race-a ===")))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // 核心断言 1：A 完成后 out 仍含 C（修复前被启动快照的 out=None 覆盖回写）。
      // P1 追加语义：in 声明给上游 out 追加指向本节点的边，不再切断既有 Nebula 汇报边
      assertEquals(aAfter.out, List(OutEdge.nebula, OutEdge(cId)), "A.out must keep the mid-run rewiring (no stale snapshot overwrite)")
      // 核心断言 2：C 收到 A 的结果并启动（修复前 deliveredTo 空 + 永久 wiring）
      assert(c.deliveredTo.contains(aId), s"C must have received A's result, got ${c.deliveredTo}")
      assertEquals(c.status, NodeLifecycle.Completed, s"C must start and complete, got ${c.status}")
      // C 输入 = own task + "=== Node race-a ===\n<A result>"（结果头即投递证据）
      assert(cInput.exists(_.contains("=== Node race-a ===")), s"C's input must carry A's result header, got: ${cInput.map(_.take(300))}")
  }

  // ── ⑥ edit 路径 in 追加已完成上游 → 立即投递（部分 barrier）──

  test("⑥ edit-append into partial barrier: appended completed upstream delivered immediately, starts when running upstream finishes") {
    val ws = tempRoot / "ws-append"
    os.makeDir.all(ws)
    val system = ActorSystem(s"bar-app-${scala.util.Random.nextInt(100000)}")
    // R（含 running-result-R）保持运行 2.5s；A（含 done-result-A）0.2s 即完成——
    // 保证 edit 追加 A 时 R 仍在运行（部分 barrier）
    val llm = CaptureLlm(text =>
      if text.contains("running-result-R") then 2500.millis
      else if text.contains("done-result-A") then 200.millis
      else 0.millis)
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("bar-append", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // W（wiring）+ R 入口 out→W（R 运行中 → W 的 barrier 挂起等 R）。
      // W store 直种（20260903 创建必带 out 新规范下 out-only wiring 节点不可经
      // NodeEdit 创建；本用例主体是分批 barrier 投递，种子等价）
      _ <- rt.store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        "n-w" -> NodeDef(id = "n-w", name = "w-w", agent = "test-agent",
          status = NodeLifecycle.Wiring, out = List(OutEdge.nebula), createdAt = System.currentTimeMillis()))))
      wId <- idOf(rt, "w-w")
      _ <- nodeEdit(nodeInput("bar-append", "run-r", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("running-result-R"), "out" -> Json.fromString(wId)), ctx)
      // A 悬空完成（out=Nebula 仅满足连接下限，校验五——追加目标）
      _ <- nodeEdit(nodeInput("bar-append", "done-a", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("done-result-A"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- waitStatus(rt, "done-a", Set(NodeLifecycle.Completed))
      aId <- idOf(rt, "done-a")
      rId <- idOf(rt, "run-r")
      // edit 追加 in=[A] → A 立即投递（barrier 未齐——R 仍在运行——W 不启动）
      _ <- nodeEdit(nodeInput("bar-append", "w-w", "in" -> Json.fromString(aId)), ctx)
      _ <- waitUntil(10.seconds)(nodeById(rt, wId).map(_.exists(_.deliveredTo.contains(aId))))
      partial <- nodeById(rt, wId).map(_.getOrElse(fail("W must exist")))
      // R 完成 → barrier 归零 → W 启动且输入含两份结果
      _ <- waitStatus(rt, "run-r", Set(NodeLifecycle.Completed))
      _ <- waitStatus(rt, "w-w", Set(NodeLifecycle.Completed))
      w <- nodeById(rt, wId).map(_.getOrElse(fail("W must exist")))
      wInput <- llm.inputs.get.map(_.find(t => t.contains("=== Node run-r ===") && t.contains("=== Node done-a ===")))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // 追加后、R 完成前：A 已投递（立即投递证据）、W 尚未启动（barrier 仍等待）
      assert(partial.deliveredTo.contains(aId), s"A's result must be delivered right after edit-append, got ${partial.deliveredTo}")
      assert(partial.status == NodeLifecycle.Wiring, s"W must still be waiting for R at append time, got ${partial.status}")
      assertEquals(w.deliveredTo.toSet, Set(rId, aId), s"W must receive both, got ${w.deliveredTo}")
      assertEquals(w.status, NodeLifecycle.Completed, "W must start only after barrier completes")
      assert(wInput.isDefined, s"W's input must contain BOTH results, got inputs=${llm.inputs.get.unsafeRunSync().map(_.take(150))}")
  }

  // ── 终态显示 TTL：一次性计算 + 24h 量级（2026-09-02 作者裁定）──

  test("ttl display: terminalized node ttlExpireAt = completedAt + TtlDisplayMs (24h, one-shot)") {
    val ws = tempRoot / "ws-ttl"
    os.makeDir.all(ws)
    val system = ActorSystem(s"bar-ttl-${scala.util.Random.nextInt(100000)}")
    val llm = CaptureLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("bar-ttl", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- nodeEdit(nodeInput("bar-ttl", "node-ttl", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("ttl-check"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- waitStatus(rt, "node-ttl", Set(NodeLifecycle.Completed))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
      n <- rt.store.snapshot.map(_.nodes.values.find(_.name == "node-ttl"))
    yield
      // 常量量级：显示保留 1 天（防误改回 5min 量级的回归哨兵）
      assertEquals(NodeEngine.TtlDisplayMs, 24 * 60 * 60 * 1000L, "TtlDisplayMs must be 24h")
      val node = n.getOrElse(fail("node-ttl must exist in active area right after completion"))
      assertEquals(node.status, NodeLifecycle.Completed, "node must have run to completion")
      val completedAt = node.completedAt.getOrElse(fail("completedAt must be set on completed node"))
      val ttlExpireAt = node.ttlExpireAt.getOrElse(fail("ttlExpireAt must be set on terminalized node"))
      // 一次性计算语义：ttlExpireAt - completedAt 恒等于常量（终态转换时算一次，此后不再重算）
      assertEquals(ttlExpireAt - completedAt, NodeEngine.TtlDisplayMs,
        s"ttlExpireAt must be exactly completedAt + ${NodeEngine.TtlDisplayMs}ms (24h)")
  }

  // ── 附带：加载净化存量 out="null" 字面串 ────────────────

  test("sanitize: legacy out=\"null\" string in flow-map.json loads as None and persists back as real null") {
    val ws = tempRoot / "ws-sanitize"
    val neb = ws / ".nebflow"
    os.makeDir.all(neb)
    val legacy =
      """{"v":1,"project":"bar-sanitize","updatedAt":1,"nodes":{
        |"n-a":{"id":"n-a","name":"a","agent":"test-agent","createdAt":1,"out":"null","status":"completed"},
        |"n-b":{"id":"n-b","name":"b","agent":"test-agent","createdAt":1,"out":"n-a","status":"wiring"}
        |}}""".stripMargin
    os.write.over(neb / "flow-map.json", legacy)
    for
      store <- FlowMapStore.open("bar-sanitize", ws.toString)
      a <- store.getNode("n-a")
      b <- store.getNode("n-b")
      // open 首写已把净化后的状态落盘 → 磁盘上应为真 JSON null
      raw <- IO.blocking(os.read(neb / "flow-map.json"))
      parsed <- IO.fromEither(io.circe.parser.parse(raw))
      aOutOnDisk = parsed.hcursor.downField("nodes").downField("n-a").downField("out").focus
    yield
      assertEquals(a.map(_.out), Some(Nil), "legacy string \"null\" must load as Nil (dangling)")
      assertEquals(b.map(_.out), Some(List(OutEdge("n-a"))), "legitimate out edge must be preserved")
      assertEquals(aOutOnDisk, Some(Json.Null), "persisted state must carry real JSON null after sanitize")
  }

end NodeBarrierDeliverySpec
