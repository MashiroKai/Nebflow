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
 * Node 连接规范收紧回归（20260903 作者裁定 5 条口径，worktree node-conn-policy）：
 *
 * 口径① 创建必须携带 out（下游 id 或 "Nebula" 出口）——悬空新节点废弃
 * 口径② in 侧 task 即连接——入口节点（task+out，无 in）创建即运行保持；in+task 并存合法
 * 口径③ 纯空挂载（无 task 无 in 无 out）继续拒绝
 * 口径④ 编辑路径 setOut 断开到无 out → 拒绝（改接而非断开）
 * 口径⑤ 存量不回溯——悬空 completed 节点接线补投递机制保留（新规范下该形态只能来自存量）
 *
 * 用例索引：
 * - ① 创建无 out 被拒（task-only / in-only 两变体，文案含自纠指引）
 * - ② 入口节点（task+out，无 in）合法创建且创建即运行（startNode 触发证据）
 * - ③ out="Nebula" 出口创建合法
 * - ④ in+task 并存合法（wiring 等上游 + task 自足）
 * - ⑤ 纯空挂载拒绝（既有语义不回归）
 * - ⑥ 编辑断开拒绝；同节点改接（rewire）合法
 * - ⑦ 存量悬空 completed 接线 → 补投递触发下游（机制不回归）；该节点编辑其他字段合法
 * - ⑧ deps 创建路径同步受「必须带 out」约束（deps 语义本身零改动）
 */
class NodeConnectionPolicySpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-node-conn-policy"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "test-agent")
  os.write.over(
    tempRoot / "agents" / "test-agent" / "agent.json",
    """{"name":"test-agent","description":"connection policy regression agent","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "test-agent" / "system.md", "# test-agent\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  /** 捕获 LLM：inputs 记录每次请求 user 文本；可按文本指定延迟。 */
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
      sessionId = Some("connp-sid"),
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

  // ── ① 创建无 out 被拒（两变体，文案含自纠指引）──────────

  test("① create without out rejected: task-only and in-only variants, message carries guidance + EMPTY_NODE_CONNECTION") {
    val ws = tempRoot / "ws-c1"
    os.makeDir.all(ws)
    val system = ActorSystem(s"connp-c1-${scala.util.Random.nextInt(100000)}")
    val llm = CaptureLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("connp-c1", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // 变体 A：有 task 无 out
      rA <- nodeEdit(nodeInput("connp-c1", "task-no-out", "agent" -> Json.fromString("test-agent"),
        "task" -> Json.fromString("work without exit")), ctx)
      // 变体 B：有 in 无 out（需已存在上游）
      _ <- nodeEdit(nodeInput("connp-c1", "up", "agent" -> Json.fromString("test-agent"),
        "task" -> Json.fromString("up-work"), "out" -> Json.fromString("Nebula")), ctx)
      upId <- idOf(rt, "up")
      rB <- nodeEdit(nodeInput("connp-c1", "in-no-out", "agent" -> Json.fromString("test-agent"),
        "in" -> Json.fromString(upId)), ctx)
      s <- rt.store.snapshot
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(rA.isLeft, s"task-only create (no out) must be rejected, got: $rA")
      assert(rA.left.exists(_.contains("must declare 'out'")), s"variant A message must guide to declare out, got: $rA")
      assert(rA.left.exists(m => m.contains("Nebula") && m.contains("EMPTY_NODE_CONNECTION")),
        s"variant A message must name the Nebula exit + error code, got: $rA")
      assert(rB.isLeft, s"in-only create (no out) must be rejected, got: $rB")
      assert(rB.left.exists(_.contains("must declare 'out'")), s"variant B message must guide to declare out, got: $rB")
      assert(s.nodes.values.find(_.name == "task-no-out").isEmpty, "no node persisted after variant A rejection")
      assert(s.nodes.values.find(_.name == "in-no-out").isEmpty, "no node persisted after variant B rejection")
  }

  // ── ② 入口节点（task+out，无 in）创建即运行 ──────────────

  test("② entry node (task + out, no in) creates legally and starts running immediately (startNode evidence)") {
    val ws = tempRoot / "ws-c2"
    os.makeDir.all(ws)
    val system = ActorSystem(s"connp-c2-${scala.util.Random.nextInt(100000)}")
    val llm = CaptureLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("connp-c2", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      r <- nodeEdit(nodeInput("connp-c2", "entry", "agent" -> Json.fromString("test-agent"),
        "task" -> Json.fromString("entry-runs-at-once"), "out" -> Json.fromString("Nebula")), ctx)
      // startNode 触发证据：agent 收到入口 task 文本（创建即运行）
      _ <- waitUntil(10.seconds)(llm.inputs.get.map(_.exists(_.contains("entry-runs-at-once"))))
      _ <- waitStatus(rt, "entry", Set(NodeLifecycle.Completed))
      n <- idOf(rt, "entry").flatMap(nodeById(rt, _)).map(_.getOrElse(fail("entry must exist")))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isRight, s"entry node (task+out) create must pass, got: $r")
      assertEquals(n.status, NodeLifecycle.Completed, "entry node must have run to completion")
  }

  // ── ③ out="Nebula" 出口创建合法 ─────────────────────────

  test("③ out=Nebula exit creation is legal and the node completes through the Nebula exit path") {
    val ws = tempRoot / "ws-c3"
    os.makeDir.all(ws)
    val system = ActorSystem(s"connp-c3-${scala.util.Random.nextInt(100000)}")
    val llm = CaptureLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("connp-c3", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      r <- nodeEdit(nodeInput("connp-c3", "to-nebula", "agent" -> Json.fromString("test-agent"),
        "task" -> Json.fromString("exit via nebula"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- waitStatus(rt, "to-nebula", Set(NodeLifecycle.Completed))
      n <- idOf(rt, "to-nebula").flatMap(nodeById(rt, _)).map(_.getOrElse(fail("node must exist")))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isRight, s"out=Nebula create must pass, got: $r")
      assertEquals(n.out, Some("Nebula"), "node must carry the Nebula exit edge")
      assertEquals(n.status, NodeLifecycle.Completed, "node must complete (Nebula delivery is a no-op without a live root session)")
  }

  // ── ④ in+task 并存合法（wiring 等上游 + task 自足）───────

  test("④ in + task coexist legally: wiring node with upstream barrier and its own task starts after delivery") {
    val ws = tempRoot / "ws-c4"
    os.makeDir.all(ws)
    val system = ActorSystem(s"connp-c4-${scala.util.Random.nextInt(100000)}")
    val llm = CaptureLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("connp-c4", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // 已完成上游
      _ <- nodeEdit(nodeInput("connp-c4", "up", "agent" -> Json.fromString("test-agent"),
        "task" -> Json.fromString("up-result"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- waitStatus(rt, "up", Set(NodeLifecycle.Completed))
      upId <- idOf(rt, "up")
      // in+task 并存创建：合法（新规范合法域），in 引用已完成上游 → 投递后启动
      r <- nodeEdit(nodeInput("connp-c4", "in-and-task", "agent" -> Json.fromString("test-agent"),
        "task" -> Json.fromString("self-sufficient work"), "in" -> Json.fromString(upId),
        "out" -> Json.fromString("Nebula")), ctx)
      _ <- waitStatus(rt, "in-and-task", Set(NodeLifecycle.Completed))
      n <- idOf(rt, "in-and-task").flatMap(nodeById(rt, _)).map(_.getOrElse(fail("node must exist")))
      inputs <- llm.inputs.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isRight, s"in+task create must pass, got: $r")
      assert(n.in.contains(upId), "node must carry the in edge")
      assert(n.task.exists(_.contains("self-sufficient work")), "node must keep its own task")
      assert(n.status == NodeLifecycle.Completed, "node must start after barrier settles and complete")
      assert(inputs.exists(t => t.contains("self-sufficient work") && t.contains("=== Node up ===")),
        "run input must combine own task with upstream result")
  }

  // ── ⑤ 纯空挂载继续拒绝 ──────────────────────────────────

  test("⑤ pure empty mount (no task/in/out) still rejected (no regression)") {
    val ws = tempRoot / "ws-c5"
    os.makeDir.all(ws)
    val system = ActorSystem(s"connp-c5-${scala.util.Random.nextInt(100000)}")
    val llm = CaptureLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("connp-c5", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      r <- nodeEdit(nodeInput("connp-c5", "empty", "agent" -> Json.fromString("test-agent")), ctx)
      s <- rt.store.snapshot
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isLeft, s"pure empty mount must be rejected, got: $r")
      assert(r.left.exists(_.contains("EMPTY_NODE_CONNECTION")), s"rejection must carry EMPTY_NODE_CONNECTION, got: $r")
      assert(s.nodes.values.find(_.name == "empty").isEmpty, "no node persisted after rejection")
  }

  // ── ⑥ 编辑断开拒绝；同节点 rewire 合法 ───────────────────

  test("⑥ edit: setOut(null) on a node with an out edge rejected; rewiring the same node to another target legal") {
    val ws = tempRoot / "ws-c6"
    os.makeDir.all(ws)
    val system = ActorSystem(s"connp-c6-${scala.util.Random.nextInt(100000)}")
    val llm = CaptureLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("connp-c6", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // 全员 Wiring 种子（确定性：src 未完成 → rewire 不触发投递链，dst 不被启动——
      // 本用例主体是 rewire/断开的接线校验语义，与投递/运行无关）
      _ <- rt.store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        "n-src" -> NodeDef(id = "n-src", name = "src", agent = "test-agent",
          status = NodeLifecycle.Wiring, out = Some("Nebula"), createdAt = System.currentTimeMillis()),
        "n-dst-a" -> NodeDef(id = "n-dst-a", name = "dst-a", agent = "test-agent",
          status = NodeLifecycle.Wiring, out = Some("Nebula"), createdAt = System.currentTimeMillis()),
        "n-dst-b" -> NodeDef(id = "n-dst-b", name = "dst-b", agent = "test-agent",
          status = NodeLifecycle.Wiring, out = Some("Nebula"), createdAt = System.currentTimeMillis()))))
      aId <- idOf(rt, "dst-a")
      bId <- idOf(rt, "dst-b")
      // rewire 到 dst-a：合法
      rw <- nodeEdit(nodeInput("connp-c6", "src", "out" -> Json.fromString(aId)), ctx)
      // 同节点断开：拒绝
      rd <- nodeEdit(nodeInput("connp-c6", "src", "out" -> Json.Null), ctx)
      // 字符串 "null"（LLM 断开常见写法）同拒
      rd2 <- nodeEdit(nodeInput("connp-c6", "src", "out" -> Json.fromString("null")), ctx)
      // rewire 到 dst-b：仍合法（拒绝未污染状态）
      rw2 <- nodeEdit(nodeInput("connp-c6", "src", "out" -> Json.fromString(bId)), ctx)
      src <- idOf(rt, "src").flatMap(nodeById(rt, _)).map(_.getOrElse(fail("src must exist")))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(rw.isRight, s"rewire to dst-a must pass, got: $rw")
      assert(rd.isLeft, s"disconnect (out=null) must be rejected, got: $rd")
      assert(rd.left.exists(_.contains("EMPTY_NODE_CONNECTION")), s"disconnect rejection must carry code, got: $rd")
      assert(rd.left.exists(_.contains("Rewire")), s"disconnect rejection must guide to rewire, got: $rd")
      assert(rd2.isLeft, s"string \"null\" disconnect must be rejected too, got: $rd2")
      assert(rw2.isRight, s"rewire to dst-b must still pass after rejections, got: $rw2")
      assertEquals(src.out, Some(bId), "src.out must end at dst-b (rewires applied, disconnects not)")
  }

  // ── ⑦ 存量悬空 completed：接线补投递不回归 + 其他字段编辑合法 ─

  test("⑦ legacy dangling completed node: NodeEdit(out=downstream) delivers retained result; other-field edits legal") {
    val ws = tempRoot / "ws-c7"
    os.makeDir.all(ws)
    val system = ActorSystem(s"connp-c7-${scala.util.Random.nextInt(100000)}")
    val llm = CaptureLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("connp-c7", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // legacy 节点合法创建（带 out）→ 完成 → store 直种 out=None 模拟存量悬空形态
      //（新规范下该形态只能来自存量地图，口径⑤ 不回溯）
      _ <- nodeEdit(nodeInput("connp-c7", "legacy", "agent" -> Json.fromString("test-agent"),
        "task" -> Json.fromString("legacy-retained-result"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- waitStatus(rt, "legacy", Set(NodeLifecycle.Completed))
      legacyId <- idOf(rt, "legacy")
      _ <- rt.store.mutate(s => s.copy(nodes = s.nodes.updated(legacyId,
        s.nodes(legacyId).copy(out = None))))
      dangling <- nodeById(rt, legacyId).map(_.getOrElse(fail("legacy must exist")))
      // 下游 consumer（入口创建后完成——补投递只要求 deliveredTo 记账 + 状态合法）
      _ <- nodeEdit(nodeInput("connp-c7", "consumer", "agent" -> Json.fromString("test-agent"),
        "task" -> Json.fromString("consume later"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- waitStatus(rt, "consumer", Set(NodeLifecycle.Completed))
      consumerId <- idOf(rt, "consumer")
      // 存量悬空节点接线 → 补投递触发（机制不回归）
      rWire <- nodeEdit(nodeInput("connp-c7", "legacy", "out" -> Json.fromString(consumerId)), ctx)
      _ <- waitUntil(10.seconds)(nodeById(rt, consumerId).map(_.exists(_.deliveredTo.contains(legacyId))))
      consumer <- nodeById(rt, consumerId).map(_.getOrElse(fail("consumer must exist")))
      // 同一存量悬空节点编辑其他字段：合法（不动 out 的编辑不拒绝）
      rTask <- nodeEdit(nodeInput("connp-c7", "legacy", "task" -> Json.fromString("legacy-task-edited")), ctx)
      // 补接线（另一存量化形态）到新下游也可（out 已有 → rewire）
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(dangling.out.isEmpty, "legacy node must be dangling (out=null) before rewiring")
      assert(dangling.result.exists(_.nonEmpty), "legacy node must retain its result while dangling")
      assert(rWire.isRight, s"legacy dangling rewire must pass, got: $rWire")
      assert(consumer.deliveredTo.contains(legacyId), s"consumer must receive legacy's retained result (auto redelivery), got ${consumer.deliveredTo}")
      assert(rTask.isRight, s"editing other fields on a legacy dangling node must be legal, got: $rTask")
  }

  // ── ⑧ deps 创建路径同步受「必须带 out」约束（deps 语义零改动）──

  test("⑧ deps creation path also requires out (deps-only rejected); deps semantics unchanged (task+deps+out legal, list carried)") {
    val ws = tempRoot / "ws-c8"
    os.makeDir.all(ws)
    val system = ActorSystem(s"connp-c8-${scala.util.Random.nextInt(100000)}")
    val llm = CaptureLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("connp-c8", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- nodeEdit(nodeInput("connp-c8", "up", "agent" -> Json.fromString("test-agent"),
        "task" -> Json.fromString("up-work"), "out" -> Json.fromString("Nebula")), ctx)
      upId <- idOf(rt, "up")
      // deps-only（无 out）→ 拒：deps 创建同样受「必须带 out」约束
      rDepsOnly <- nodeEdit(nodeInput("connp-c8", "deps-no-out", "agent" -> Json.fromString("test-agent"),
        "deps" -> Json.fromString(upId)), ctx)
      // task+deps+out → 合法：deps 清单原样携带（语义零改动）
      rOk <- nodeEdit(nodeInput("connp-c8", "deps-ok", "agent" -> Json.fromString("test-agent"),
        "task" -> Json.fromString("deps waiter work"), "deps" -> Json.fromString(upId),
        "out" -> Json.fromString("Nebula")), ctx)
      n <- idOf(rt, "deps-ok").flatMap(nodeById(rt, _)).map(_.getOrElse(fail("deps-ok must exist")))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(rDepsOnly.isLeft, s"deps-only create (no out) must be rejected, got: $rDepsOnly")
      assert(rDepsOnly.left.exists(_.contains("must declare 'out'")), s"deps rejection must state the out rule, got: $rDepsOnly")
      assert(rOk.isRight, s"task+deps+out create must pass, got: $rOk")
      assertEquals(n.deps, List(upId), "deps list must be carried unchanged (deps semantics zero change)")
  }

end NodeConnectionPolicySpec
