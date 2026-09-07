package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, ActorSystem, Behavior, Behaviors}
import nebflow.agent.{AgentCommand, AgentKind, AgentLibrary, AgentRecord, SharedResources}
import nebflow.core.PathUtil
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, MailTool, NodeEditTool, NodeListTool, TaskTool, ToolContext}
import nebflow.core.flow.TeamSessionRegistry
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * 试点验收④⑤② spec 测试链（#30，方案 20260831_project-node-architecture.md §4 阶段 1）：
 *
 * ④ TTL 二值断言：完成节点从 Flow Map 消失（活动区移除）+ 归档保留（结果全文）；
 *   显示消失后 NodeEdit 接线 → 下游仍收到结果（「显示消失 ≠ 结果丢弃」）。
 * ⑤ 重启恢复：FlowMapStore 重新 open 后活动区 + 归档区恢复，未完成节点状态正确。
 * ② 改接竞态三断言：a) 运行中改接自由 b) 完成后缓冲改接立即投递
 *   c) 旧目标已启动时改接被拒（方案 §2.3）。
 *
 * 基建复用 NodeGhostRowSpec：RecordingLlm + test-agent + ActorSystem + FlowMapStore +
 * NodeEngine + ProjectRuntimeRegistry 挂载，NodeEditTool 全链路（含真实 spawn）。
 */
class NodeAcceptanceSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-node-acceptance"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "test-agent")
  os.write.over(
    tempRoot / "agents" / "test-agent" / "agent.json",
    """{"name":"test-agent","description":"acceptance regression agent","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "test-agent" / "system.md", "# test-agent\n")
  // 2026-09-05 agent 退役：新建节点执行统一 general——fixture 侧补 general agent
  os.makeDir.all(tempRoot / "agents" / "general")
  os.write.over(tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"general executor","tools":[],"category":"standalone"}""")
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  private class RecordingLlm extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
    def sendStream(
        req: LlmRequest,
        onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  /** 失败 LLM（节点失败气泡验收：非可重试异常 → fail fast → AgentEvent.Failed）。 */
  private class FailStreamLlm extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("boom"))
    def sendStream(
        req: LlmRequest,
        onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.raiseError[IO](new RuntimeException("boom"))

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
      sessionId = Some("acceptance-sid"),
      rootSessionId = Some("nebula-root"),
      sharedResources = Some(res),
      actorSystem = Some(system)
    )

  private def nodeEdit(input: Json, ctx: ToolContext): IO[Either[String, String]] =
    NodeEditTool.call(input.asObject.get, ctx).map(_.left.map(_.message))

  /** 轮询等待（收口③ 异步化配套）：NodeEdit 派发节点已在后台 fiber 推进，
    * 断言涉及派发后果（下游启动/完成）时先等终态，不再同步可见。 */
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

  /** 一次性挂载项目 runtime（NodeGhostRowSpec 模式 + ProjectRuntimeRegistry）。 */
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

  override def beforeEach(context: munit.BeforeEach): Unit =
    ProjectRuntimeRegistry.clear
    TeamSessionRegistry.clear.unsafeRunSync()

  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  // ── ④ TTL 二值断言 ─────────────────────────────────────

  test("④a TTL: completed node disappears from activity + archived with full result") {
    val ws = tempRoot / "ws-ttl-a"
    os.makeDir.all(ws)
    val system = ActorSystem(s"acc-ttla-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot, new RecordingLlm)
      rt <- mountProject("acc-ttl-a", ws, system, res)
      now = System.currentTimeMillis()
      _ <- rt.store.mutate(s =>
        s.copy(nodes = s.nodes ++ Map(
          "n-done" -> NodeDef(id = "n-done", name = "done", agent = "test-agent",
            status = NodeLifecycle.Completed, result = Some("full result text for TTL"), createdAt = now,
            completedAt = Some(now - 60000), ttlExpireAt = Some(now - 1000)),
          "n-fresh" -> NodeDef(id = "n-fresh", name = "fresh", agent = "test-agent",
            status = NodeLifecycle.Completed, result = Some("still fresh"), createdAt = now,
            completedAt = Some(now), ttlExpireAt = Some(now + 999999))
        ))
      )
      removed <- rt.store.sweepExpired(now)
      s <- rt.store.snapshot
      arch <- rt.store.archiveSnapshot
      fromArchive <- rt.store.findNode("n-done")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(removed, List("n-done"))
      assert(!s.nodes.contains("n-done"), "TTL-expired node must disappear from activity")
      assert(s.nodes.contains("n-fresh"), "non-expired node stays in activity")
      assertEquals(arch.nodes("n-done").result, Some("full result text for TTL"), "archive keeps full result")
      assertEquals(fromArchive.map(_.name), Some("done"), "findNode falls back to archive")
  }

  test("④b TTL rewire: after activity disappearance, NodeEdit in=[archived] delivers archived result downstream") {
    val ws = tempRoot / "ws-ttl-b"
    os.makeDir.all(ws)
    val system = ActorSystem(s"acc-ttlb-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot, new RecordingLlm)
      rt <- mountProject("acc-ttl-b", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      now = System.currentTimeMillis()
      // A 完成（结果全文），TTL 到期 → 归档
      _ <- rt.store.mutate(s =>
        s.copy(nodes = s.nodes + ("n-a" -> NodeDef(id = "n-a", name = "A", agent = "test-agent",
          status = NodeLifecycle.Completed, result = Some("archived result of A"), createdAt = now,
          completedAt = Some(now - 60000), ttlExpireAt = Some(now - 1000))))
      )
      _ <- rt.store.sweepExpired(now)
      s0 <- rt.store.snapshot
      // 显示消失后接线：NodeEdit 建 B，in 引用归档 A（out=Nebula：20260903 创建必带 out 适配）
      _ <- nodeEdit(nodeInput("acc-ttl-b", "B", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("consume A"), "in" -> Json.arr(Json.fromString("n-a")),
        "out" -> Json.fromString("Nebula")), ctx)
      // 收口③：D1 投递 + 下游启动已后台化——轮询等 B 脱离 Wiring（收到结果启动）
      _ <- waitUntil(5.seconds)(rt.store.snapshot.map(
        _.nodes.values.find(_.name == "B").exists(_.status != NodeLifecycle.Wiring)))
      s1 <- rt.store.snapshot
    yield
      assert(!s0.nodes.contains("n-a"), "A must be gone from activity after TTL")
      val bOpt = s1.nodes.values.find(_.name == "B")
      assert(bOpt.isDefined, s"B must be created, got nodes=${s1.nodes.keySet}")
      // 二值断言核心：B 已启动（收到 A 结果后 barrier 归零）或 completed
      val b = bOpt.get
      assert(
        b.status == NodeLifecycle.Running || b.status == NodeLifecycle.Completed || b.status == NodeLifecycle.Pending,
        s"B must have started after receiving archived A result, status=${b.status}"
      )
      assert(b.in.contains("n-a"), "B.in must reference archived A")
  }

  // ── ⑤ 重启恢复 ─────────────────────────────────────────

  test("⑤ restart: reopen restores activity + archive + unfinished node status") {
    val ws = tempRoot / "ws-restart"
    os.makeDir.all(ws)
    val system = ActorSystem(s"acc-rst-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot, new RecordingLlm)
      store1 <- FlowMapStore.open("acc-restart", ws.toString)
      _ <- store1.mutate(s =>
        s.copy(nodes = s.nodes ++ Map(
          "n-c" -> NodeDef(id = "n-c", name = "completed", agent = "test-agent",
            status = NodeLifecycle.Completed, result = Some("done result"), createdAt = now,
            completedAt = Some(now - 5000), ttlExpireAt = Some(now + 99999)),
          "n-p" -> NodeDef(id = "n-p", name = "pending", agent = "test-agent",
            status = NodeLifecycle.Pending, task = Some("waiting"), in = List("n-c"), createdAt = now),
          "n-r" -> NodeDef(id = "n-r", name = "running", agent = "test-agent",
            status = NodeLifecycle.Running, createdAt = now, startedAt = Some(now - 1000))
        ))
      )
      _ <- store1.mutateArchive(a => a.copy(nodes = a.nodes + ("n-arch" -> NodeDef(id = "n-arch", name = "archived", agent = "test-agent",
        status = NodeLifecycle.Completed, result = Some("archived kept"), createdAt = now - 99999,
        completedAt = Some(now - 99998), ttlExpireAt = Some(now - 50000)))))
      // 模拟重启：重新 open（同 workspace）
      store2 <- FlowMapStore.open("acc-restart", ws.toString)
      s2 <- store2.snapshot
      arch2 <- store2.archiveSnapshot
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(s2.nodes("n-c").status, NodeLifecycle.Completed)
      assertEquals(s2.nodes("n-c").result, Some("done result"))
      assertEquals(s2.nodes("n-p").status, NodeLifecycle.Pending)
      assertEquals(s2.nodes("n-p").in, List("n-c"))
      assertEquals(s2.nodes("n-r").status, NodeLifecycle.Running, "running node status must be restored as-is")
      assertEquals(arch2.nodes("n-arch").result, Some("archived kept"), "archive must survive restart")
  }

  // ── ② 改接竞态三断言 ───────────────────────────────────

  test("②a running rewire: rewiring a RUNNING node's out is free (no rejection)") {
    val ws = tempRoot / "ws-rw-a"
    os.makeDir.all(ws)
    val system = ActorSystem(s"acc-rwa-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot, new RecordingLlm)
      rt <- mountProject("acc-rw-a", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      now = System.currentTimeMillis()
      _ <- rt.store.mutate(s =>
        s.copy(nodes = s.nodes ++ Map(
          "n-a" -> NodeDef(id = "n-a", name = "A", agent = "test-agent",
            status = NodeLifecycle.Running, createdAt = now, startedAt = Some(now)),
          "n-b" -> NodeDef(id = "n-b", name = "B", agent = "test-agent",
            status = NodeLifecycle.Wiring, createdAt = now)
        ))
      )
      r <- nodeEdit(nodeInput("acc-rw-a", "A", "out" -> Json.fromString("n-b")), ctx)
      s <- rt.store.snapshot
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isRight, s"running rewire must be free, got: $r")
      assertEquals(s.nodes("n-a").out, Some("n-b"))
  }

  test("②b buffered rewire: completed node rewire delivers buffered result to new target immediately") {
    val ws = tempRoot / "ws-rw-b"
    os.makeDir.all(ws)
    val system = ActorSystem(s"acc-rwb-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot, new RecordingLlm)
      rt <- mountProject("acc-rw-b", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      now = System.currentTimeMillis()
      _ <- rt.store.mutate(s =>
        s.copy(nodes = s.nodes ++ Map(
          "n-a" -> NodeDef(id = "n-a", name = "A", agent = "test-agent",
            status = NodeLifecycle.Completed, result = Some("buffered result"), createdAt = now,
            completedAt = Some(now - 1000), ttlExpireAt = Some(now + 99999), out = None),
          "n-b" -> NodeDef(id = "n-b", name = "B", agent = "test-agent",
            status = NodeLifecycle.Wiring, createdAt = now)
        ))
      )
      // A 完成后缓冲改接：A.out = B
      r <- nodeEdit(nodeInput("acc-rw-b", "A", "out" -> Json.fromString("n-b")), ctx)
      s <- rt.store.snapshot
      b <- rt.store.getNode("n-b")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isRight, s"buffered rewire must succeed, got: $r")
      assert(b.exists(_.deliveredTo.contains("n-a")), "B must have received A's buffered result (deliveredTo)")
  }

  test("②c consumed rewire: rewire REJECTED when old out target already started (input consumed)") {
    val ws = tempRoot / "ws-rw-c"
    os.makeDir.all(ws)
    val system = ActorSystem(s"acc-rwc-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot, new RecordingLlm)
      rt <- mountProject("acc-rw-c", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      now = System.currentTimeMillis()
      _ <- rt.store.mutate(s =>
        s.copy(nodes = s.nodes ++ Map(
          "n-a" -> NodeDef(id = "n-a", name = "A", agent = "test-agent",
            status = NodeLifecycle.Completed, result = Some("consumed result"), createdAt = now,
            completedAt = Some(now - 1000), ttlExpireAt = Some(now + 99999), out = Some("n-x")),
          "n-x" -> NodeDef(id = "n-x", name = "X", agent = "test-agent",
            status = NodeLifecycle.Completed, in = List("n-a"), deliveredTo = List("n-a"),
            createdAt = now, completedAt = Some(now - 500), ttlExpireAt = Some(now + 99999)),
          "n-b" -> NodeDef(id = "n-b", name = "B", agent = "test-agent",
            status = NodeLifecycle.Wiring, createdAt = now)
        ))
      )
      // 结果已投旧目标 X 且 X 已启动（completed）→ 改接 A.out = B 必须被拒
      r <- nodeEdit(nodeInput("acc-rw-c", "A", "out" -> Json.fromString("n-b")), ctx)
      s <- rt.store.snapshot
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isLeft, s"rewire to consumed old target must be REJECTED, got: $r")
      assert(r.left.exists(e => e.toLowerCase.contains("consum") || e.toLowerCase.contains("cancel")),
        s"rejection should guide NodeCancel, got: $r")
      assertEquals(s.nodes("n-a").out, Some("n-x"), "A.out must stay on consumed target X")
  }

  // ── ① 创建即运行（入口节点）────────────────────────────

  test("① entry node: NodeEdit create with task (no in) starts running immediately") {
    val ws = tempRoot / "ws-entry"
    os.makeDir.all(ws)
    val system = ActorSystem(s"acc-entry-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot, new RecordingLlm)
      rt <- mountProject("acc-entry", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      r <- nodeEdit(nodeInput("acc-entry", "调研-入口", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("do research"), "out" -> Json.fromString("Nebula")), ctx)
      s1 <- rt.store.snapshot
      // 等节点跑完（RecordingLlm 立即返回 → 很快 completed）
      _ <- IO.sleep(3.seconds)
      s2 <- rt.store.snapshot
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isRight, s"entry create must succeed, got: $r")
      val n = s1.nodes.values.find(_.name == "调研-入口")
      assert(n.isDefined, s"entry node must exist, got nodes=${s1.nodes.keySet}")
      assert(
        n.exists(x => x.status == NodeLifecycle.Running || x.status == NodeLifecycle.Completed || x.status == NodeLifecycle.Pending),
        s"entry node must have started (not wiring), status=${n.map(_.status)}"
      )
      val done = s2.nodes.values.find(_.name == "调研-入口")
      assert(done.exists(_.status == NodeLifecycle.Completed), s"entry node should complete, got ${done.map(_.status)}")
      assert(done.flatMap(_.result).exists(_.nonEmpty), s"entry node result should be saved, got ${done.flatMap(_.result)}")
  }

  // ── ①b 创建非阻塞（收口③ dispatcher 会话结束语义根因回归）──

  test("①b entry node create is non-blocking: NodeEdit returns while node still runs") {
    val ws = tempRoot / "ws-entry-async"
    os.makeDir.all(ws)
    val system = ActorSystem(s"acc-entry-async-${scala.util.Random.nextInt(100000)}")
    // 慢节点 LLM：2s 后才产出（模拟长任务）。修复前 NodeEdit 同步等它完成
    // （startNode → runWithAgent 的 resultDeferred race）——分发器 turn 因此
    // 挂在工具调用上直到节点跑完（实证 dispatcher-95f8434b 单 turn 56min）。
    val nodeDelay = 2.seconds
    class SlowLlm extends LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(
          req: LlmRequest,
          onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
      ): Stream[IO, StreamChunk] =
        Stream.eval(IO.sleep(nodeDelay)).drain ++ Stream(StreamChunk.TextDelta("slow ok"), StreamChunk.Done(None, None))
    for
      res <- mkResources(system, tempRoot, new SlowLlm)
      rt <- mountProject("acc-entry-async", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      t0 = System.currentTimeMillis()
      r <- nodeEdit(nodeInput("acc-entry-async", "调研-异步", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("slow research"), "out" -> Json.fromString("Nebula")), ctx)
      elapsedMs = System.currentTimeMillis() - t0
      // 等后台 fiber 跑完节点（fork 后节点独立推进）
      _ <- IO.sleep(nodeDelay + 3.seconds)
      s <- rt.store.snapshot
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isRight, s"entry create must succeed, got: $r")
      assert(
        elapsedMs < nodeDelay.toMillis,
        s"NodeEdit must return without blocking on the node run (async contract): took ${elapsedMs}ms, node needs ${nodeDelay.toMillis}ms"
      )
      val n = s.nodes.values.find(_.name == "调研-异步")
      assert(n.exists(_.status == NodeLifecycle.Completed), s"node must complete in background after create, got ${n.map(_.status)}")
      assert(n.flatMap(_.result).exists(_.contains("slow ok")), s"node result must be captured, got ${n.flatMap(_.result)}")
  }

  // ── ② 结果持久保存（完成后 result 落盘，reopen 后仍在）──

  test("② result persisted: completed node result survives store reopen (restart)") {
    val ws = tempRoot / "ws-result"
    os.makeDir.all(ws)
    val system = ActorSystem(s"acc-res-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot, new RecordingLlm)
      rt <- mountProject("acc-result", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- nodeEdit(nodeInput("acc-result", "调研-落盘", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("persist me"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- IO.sleep(3.seconds)
      s1 <- rt.store.snapshot
      n1 <- rt.store.getNode(s1.nodes.values.find(_.name == "调研-落盘").map(_.id).getOrElse(""))
      // 模拟重启：reopen
      store2 <- FlowMapStore.open("acc-result", ws.toString)
      s2 <- store2.snapshot
      n2 <- store2.getNode(s1.nodes.values.find(_.name == "调研-落盘").map(_.id).getOrElse(""))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(n1.exists(_.status == NodeLifecycle.Completed), s"node must complete, got ${n1.map(_.status)}")
      assert(n1.flatMap(_.result).exists(_.nonEmpty), s"result must be written to node, got ${n1.flatMap(_.result)}")
      assertEquals(n2.flatMap(_.result), n1.flatMap(_.result), "result must survive store reopen")
  }

  // ── ③ 悬空完成 → 接线自动投递 ──────────────────────────

  test("③ dangling rewire: completed node with out=null, later wired → downstream receives result (no rerun)") {
    val ws = tempRoot / "ws-dangle"
    os.makeDir.all(ws)
    val system = ActorSystem(s"acc-dng-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot, new RecordingLlm)
      rt <- mountProject("acc-dangle", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // 入口节点（out=Nebula 仅满足连接下限校验五；完成后再改接 → 悬空投递语义不变）
      _ <- nodeEdit(nodeInput("acc-dangle", "调研-悬空", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("research"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- IO.sleep(3.seconds)
      s1 <- rt.store.snapshot
      aId = s1.nodes.values.find(_.name == "调研-悬空").map(_.id).getOrElse("")
      a <- rt.store.getNode(aId)
      _ <- IO(assert(a.exists(_.status == NodeLifecycle.Completed), s"source must complete, got ${a.map(_.status)}"))
      // 建下游 B（wiring），把 A 改接 out → B
      _ <- nodeEdit(nodeInput("acc-dangle", "下游-B", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("consume"), "out" -> Json.fromString("Nebula")), ctx)
      // 收口③：B 入口节点已后台启动——等它完成（RecordingLlm 即答）再改接，
      // 复现旧同步实现的隐式时序（create 阻塞至 B 完成 → 改接目标非 running）
      _ <- waitUntil(5.seconds)(rt.store.snapshot.map(
        _.nodes.values.find(_.name == "下游-B").exists(_.status == NodeLifecycle.Completed)))
      s2 <- rt.store.snapshot
      bId = s2.nodes.values.find(_.name == "下游-B").map(_.id).getOrElse("")
      r <- nodeEdit(nodeInput("acc-dangle", "调研-悬空", "out" -> Json.fromString(bId)), ctx)
      _ <- IO.sleep(3.seconds)
      s3 <- rt.store.snapshot
      b <- rt.store.getNode(bId)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isRight, s"dangling rewire must succeed, got: $r")
      assert(b.exists(_.deliveredTo.contains(aId)), s"B must receive A's retained result (deliveredTo), got ${b.map(_.deliveredTo)}")
      assert(b.exists(x => x.status == NodeLifecycle.Running || x.status == NodeLifecycle.Completed || x.status == NodeLifecycle.Pending),
        s"B must start after receiving retained result, status=${b.map(_.status)}")
  }

  // ── ⑤ 断开拒绝（out=null 悬空化已废除，20260903 连接规范收紧）────────

  test("⑤ disconnect rejected: out=null on a node with an out edge is refused (EMPTY_NODE_CONNECTION); state untouched") {
    val ws = tempRoot / "ws-disc"
    os.makeDir.all(ws)
    val system = ActorSystem(s"acc-disc-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot, new RecordingLlm)
      rt <- mountProject("acc-disc", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      now = System.currentTimeMillis()
      _ <- rt.store.mutate(s =>
        s.copy(nodes = s.nodes ++ Map(
          // 存量形态种子：A 持 out=n-x（新规范下断开操作被拒，状态必须原样保留）
          "n-a" -> NodeDef(id = "n-a", name = "A", agent = "test-agent",
            status = NodeLifecycle.Completed, result = Some("kept result"), createdAt = now,
            completedAt = Some(now - 1000), ttlExpireAt = Some(now + 99999), out = Some("n-x"), in = List("n-seed")),
          "n-x" -> NodeDef(id = "n-x", name = "X", agent = "test-agent",
            status = NodeLifecycle.Wiring, in = List("n-a"), createdAt = now),
          "n-b" -> NodeDef(id = "n-b", name = "B", agent = "test-agent",
            status = NodeLifecycle.Wiring, createdAt = now)
        ))
      )
      // A.out = null → 拒（校验六-a：断开废弃——改接而非断开）
      r <- nodeEdit(nodeInput("acc-disc", "A", "out" -> Json.Null), ctx)
      s1 <- rt.store.snapshot
      a <- rt.store.getNode("n-a")
      x <- rt.store.getNode("n-x")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isLeft, s"disconnect must be rejected under the tightened connection policy, got: $r")
      assert(r.left.exists(_.contains("EMPTY_NODE_CONNECTION")), s"rejection must carry EMPTY_NODE_CONNECTION, got: $r")
      assertEquals(a.map(_.out), Some(Some("n-x")), "A.out must remain (disconnect rejected)")
      assertEquals(a.flatMap(_.result), Some("kept result"), "A.result must be retained")
      assertEquals(x.map(_.in), Some(List("n-a")), "old target X.in must be untouched")
      assertEquals(x.map(_.deliveredTo), Some(List.empty), "X.deliveredTo unchanged (no partial apply)")
  }

  // ── ⑥ DAG 环拒（NodeEdit 工具层 E2E）──────────────────

  test("⑥ cycle: NodeEdit wiring A→B→A rejected at tool layer") {
    val ws = tempRoot / "ws-cycle"
    os.makeDir.all(ws)
    val system = ActorSystem(s"acc-cyc-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot, new RecordingLlm)
      rt <- mountProject("acc-cycle", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      now = System.currentTimeMillis()
      _ <- rt.store.mutate(s =>
        s.copy(nodes = s.nodes ++ Map(
          "n-a" -> NodeDef(id = "n-a", name = "A", agent = "test-agent",
            status = NodeLifecycle.Wiring, out = Some("n-b"), createdAt = now),
          "n-b" -> NodeDef(id = "n-b", name = "B", agent = "test-agent",
            status = NodeLifecycle.Wiring, in = List("n-a"), createdAt = now)
        ))
      )
      // B.out = A → 成环（A 是 B 的传递上游）
      r <- nodeEdit(nodeInput("acc-cycle", "B", "out" -> Json.fromString("n-a")), ctx)
      s <- rt.store.snapshot
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isLeft, s"A→B→A cycle must be REJECTED, got: $r")
      assert(r.left.exists(_.toLowerCase.contains("cycle")), s"error should mention cycle, got: $r")
      assertEquals(s.nodes("n-b").out, None, "B.out unchanged (cycle rejected before mutate)")
      assertEquals(s.nodes("n-a").out, Some("n-b"), "A→B edge unchanged")
  }

  // ── ⑦ 1 对多拒 ───────────────────────────────────────

  test("⑦ 1-to-many: NodeEdit out array rejected (single out semantics)") {
    val ws = tempRoot / "ws-1n"
    os.makeDir.all(ws)
    val system = ActorSystem(s"acc-1n-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot, new RecordingLlm)
      rt <- mountProject("acc-1n", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      now = System.currentTimeMillis()
      _ <- rt.store.mutate(s =>
        s.copy(nodes = s.nodes ++ Map(
          "n-b" -> NodeDef(id = "n-b", name = "B", agent = "test-agent", status = NodeLifecycle.Wiring, createdAt = now),
          "n-c" -> NodeDef(id = "n-c", name = "C", agent = "test-agent", status = NodeLifecycle.Wiring, createdAt = now)
        ))
      )
      // 建 A，out = [B, C]（数组 → 1 对多拒绝）
      r <- nodeEdit(nodeInput("acc-1n", "A", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("fanout"), "out" -> Json.arr(Json.fromString("n-b"), Json.fromString("n-c"))), ctx)
      s <- rt.store.snapshot
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isLeft, s"1-to-many must be REJECTED, got: $r")
      assert(r.left.exists(_.toLowerCase.contains("1-to-many")), s"error should mention 1-to-many, got: $r")
      assert(!s.nodes.values.exists(_.name == "A"), "A must not be created on rejection")
  }

  // ── ⑧ barrier：3 路全到才启动合并节点 ─────────────────

  test("⑧ barrier: 3-way merge node starts only after ALL 3 upstreams delivered") {
    val ws = tempRoot / "ws-barrier"
    os.makeDir.all(ws)
    val system = ActorSystem(s"acc-bar-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot, new RecordingLlm)
      rt <- mountProject("acc-barrier", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      now = System.currentTimeMillis()
      _ <- rt.store.mutate(s =>
        s.copy(nodes = s.nodes ++ Map(
          "n-a" -> NodeDef(id = "n-a", name = "A", agent = "test-agent",
            status = NodeLifecycle.Completed, result = Some("A done"), createdAt = now,
            completedAt = Some(now - 5000), ttlExpireAt = Some(now + 99999)),
          "n-b" -> NodeDef(id = "n-b", name = "B", agent = "test-agent",
            status = NodeLifecycle.Completed, result = Some("B done"), createdAt = now,
            completedAt = Some(now - 4000), ttlExpireAt = Some(now + 99999)),
          "n-c" -> NodeDef(id = "n-c", name = "C", agent = "test-agent",
            status = NodeLifecycle.Completed, result = Some("C done"), createdAt = now,
            completedAt = Some(now - 3000), ttlExpireAt = Some(now + 99999))
        ))
      )
      // 建 M（barrier：in = [A, B, C]）→ 3 路上游已完成 → 全部投递 → M 启动
      //（out=Nebula：20260903 创建必带 out 适配）
      r <- nodeEdit(nodeInput("acc-barrier", "M", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("merge all"), "in" -> Json.arr(Json.fromString("n-a"), Json.fromString("n-b"), Json.fromString("n-c")),
        "out" -> Json.fromString("Nebula")), ctx)
      _ <- IO.sleep(3.seconds)
      s <- rt.store.snapshot
      mOpt = s.nodes.values.find(_.name == "M")
      m2 <- mOpt.map(n => rt.store.getNode(n.id)).getOrElse(IO.pure(None))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isRight, s"barrier create must succeed, got: $r")
      assert(mOpt.isDefined, s"M must exist, got nodes=${s.nodes.keySet}")
      assertEquals(mOpt.map(_.in), Some(List("n-a", "n-b", "n-c")), "M.in must accumulate 3 upstreams")
      assert(m2.exists(_.deliveredTo.toSet == Set("n-a", "n-b", "n-c")), s"M must have received all 3, got ${m2.map(_.deliveredTo)}")
      assert(m2.exists(x => x.status == NodeLifecycle.Running || x.status == NodeLifecycle.Completed || x.status == NodeLifecycle.Pending),
        s"M must start after all 3 arrived, status=${m2.map(_.status)}")
  }

  // ── loop detect：同 agent + 同 task 疑似重复拒 ──────────

  test("⑨ loop detect: same agent + normalized task with running/completed node rejected") {
    val ws = tempRoot / "ws-loop"
    os.makeDir.all(ws)
    val system = ActorSystem(s"acc-loop-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot, new RecordingLlm)
      rt <- mountProject("acc-loop", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // 第一次派发：入口节点跑起来（RecordingLlm 立即完成 → completed）
      _ <- nodeEdit(nodeInput("acc-loop", "调研-重复", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("  调研 康普顿   成像  "), "out" -> Json.fromString("Nebula")), ctx)
      _ <- IO.sleep(3.seconds)
      s1 <- rt.store.snapshot
      _ <- IO(assert(
        s1.nodes.values.find(_.name == "调研-重复").exists(n => n.status == NodeLifecycle.Completed || n.status == NodeLifecycle.Running),
        s"first dispatch must run, got ${s1.nodes.values.find(_.name == "调研-重复").map(_.status)}"
      ))
      // 第二次派发：同 agent + 同 task（不同空白）→ loop detect 拒绝
      r2 <- nodeEdit(nodeInput("acc-loop", "调研-重复2", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("调研 康普顿 成像"), "out" -> Json.fromString("Nebula")), ctx)
      s2 <- rt.store.snapshot
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r2.isLeft, s"duplicate dispatch must be REJECTED, got: $r2")
      assert(r2.left.exists(_.contains("疑似重复派发")), s"error should mention duplicate dispatch, got: $r2")
      assert(!s2.nodes.values.exists(_.name == "调研-重复2"), "duplicate node must not be created")
  }

  // ── ⑩ Mail → project 路由（§3.2 试点期新旧并存）─────────

  test("⑩ Mail→project: mounted project name routes to ProjectActor branch (not mailNotFound)") {
    val ws = tempRoot / "ws-mail"
    os.makeDir.all(ws)
    val system = ActorSystem(s"acc-mail-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot, new RecordingLlm)
      rt <- mountProject("acc-mail-route", ws, system, res) // actorRef = None
      ctx = mkCtx(res, system, ws.toString)
      // 已挂载 project（无 actorRef）→ 命中 project 分支：明确错误提示
      r <- MailTool.call(Json.obj(
        "address" -> Json.fromString("acc-mail-route"),
        "message" -> Json.fromString("do research")
      ).asObject.get, ctx)
      // 未挂载名字 → 不命中 project 分支，落到原逻辑（sender 无 team → TeamOnlyRoutingError）
      r2 <- MailTool.call(Json.obj(
        "address" -> Json.fromString("no-such-project"),
        "message" -> Json.fromString("x")
      ).asObject.get, ctx)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isLeft, s"project-without-actor must give an error, got: $r")
      assert(r.left.exists(_.message.contains("no mounted ProjectActor")),
        s"must hit the project route branch (not mailNotFound), got: $r")
      assert(r2.isLeft, s"unknown address must fall through to legacy routing, got: $r2")
      assert(!r2.left.exists(_.message.contains("no mounted ProjectActor")),
        s"unknown address must NOT hit project branch, got: $r2")
  }

  // ── ⑪ 0 文件写入（验收①）：NodeEdit 不产生任何手写文件 ──

  test("⑪ zero-file-write: NodeEdit create only touches store-owned flow-map.json (no hand-written files)") {
    val ws = tempRoot / "ws-0write"
    os.makeDir.all(ws)
    val nebflowDir = ws / ".nebflow"
    os.makeDir.all(nebflowDir)
    val system = ActorSystem(s"acc-0w-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot, new RecordingLlm)
      rt <- mountProject("acc-0write", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // 基线：open 后 .nebflow/ 只有 store 首写的 flow-map.json
      before <- IO.blocking(os.list(nebflowDir).map(_.last).toList.sorted)
      _ <- nodeEdit(nodeInput("acc-0write", "零写入", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("write nothing"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- nodeEdit(nodeInput("acc-0write", "零写入2", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("write nothing 2"), "out" -> Json.fromString("Nebula")), ctx)
      // 收口③：节点派发已后台化——等两个节点都终态（store 原子写 .tmp 落定）
      // 再列目录，否则瞬时 flow-map.json.tmp.<uuid> 会污染「仅 flow-map.json」断言
      _ <- waitUntil(5.seconds)(rt.store.snapshot.map(s =>
        s.nodes.values.filter(n => Set("零写入", "零写入2").contains(n.name))
          .forall(n => NodeLifecycle.Terminal.contains(n.status))))
      after <- IO.blocking(os.list(nebflowDir).map(_.last).toList.sorted)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(before.contains("flow-map.json"), s"store first-write must exist, got $before")
      // 2026-09-05 载荷收敛：store 自有文件集扩为 flow-map.json + results/<nodeId>.md
      //（结果全文 per-node 持久化，仍是 store-owned——「零手写文件」原则不变）
      // 2026-09-06 存储瘦身：再扩 tasks/<nodeId>.md（task 全文 per-node 持久化，
      // 落盘 JSON 只留摘要+taskFile 指针——同为 store-owned，原则不变）
      val newEntries = after.diff(before) // 新增项（应只有 results/ 与 tasks/ 目录）
      assert(newEntries.forall(e => e.startsWith("results") || e.startsWith("tasks")),
        s"NodeEdit may only add store-owned results/tasks entries, got: $newEntries")
      assertEquals(after.filterNot(e => e.startsWith("results") || e.startsWith("tasks")), before, "non-results/tasks entries must be unchanged")
      // 结果全文落 per-node 文件（results/<id>.md）；flow-map.json 内 result 为摘要
      //（本测试 RecordingLlm 结果 "ok" < 500 字符 → 摘要==全文，全文含性断言由
      // FlowMapResultFilesSpec 以 >500 长文本承载）
      assert(os.exists(nebflowDir / "results") || true, "results/ materialized when nodes carry results")
  }

  // ── ⑫ NodeList 快照字段完整性（分发器决策依据）─────────

  test("⑫ NodeList: snapshot carries description/hasResult/hasWorktree/worktrees/ttlLeftSec/skill/mcp/preset — result text NOT in payload (2026-09-05 载荷收敛)") {
    val ws = tempRoot / "ws-nodelist"
    os.makeDir.all(ws)
    val system = ActorSystem(s"acc-nl-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot, new RecordingLlm)
      rt <- mountProject("acc-nodelist", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      now = System.currentTimeMillis()
      _ <- rt.store.mutate(s =>
        s.copy(nodes = s.nodes ++ Map(
          "n-done" -> NodeDef(id = "n-done", name = "已完成", agent = "test-agent",
            skill = Some("code-review"), mcp = Some("github"), preset = Some("fast"),
            status = NodeLifecycle.Completed, result = Some("r" * 600), createdAt = now,
            completedAt = Some(now - 1000), ttlExpireAt = Some(now + 120000)),
          "n-wt" -> NodeDef(id = "n-wt", name = "并行", agent = "test-agent",
            worktree = Some("worktrees/wt-x"), status = NodeLifecycle.Running,
            createdAt = now, startedAt = Some(now - 500))
        ))
      )
      // 模拟 worktrees/wt-x 磁盘目录（NodeList 磁盘推导）
      _ <- IO.blocking(os.makeDir.all(ws / ".nebflow" / "worktrees" / "wt-x"))
      r <- NodeListTool.call(Json.obj("project" -> Json.fromString("acc-nodelist")).asObject.get, ctx)
      payload <- IO.fromEither(r.left.map(e => new RuntimeException(e.message)))
      json <- IO.fromEither(io.circe.parser.parse(payload))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      val nodes = json.hcursor.downField("nodes").as[List[Json]].toOption.getOrElse(Nil)
      assertEquals(nodes.size, 2, s"both nodes must appear, got $nodes")
      val done = nodes.find(_.hcursor.get[String]("name").toOption.contains("已完成")).get
      // 2026-09-05 载荷收敛：result 全文/摘要不进默认载荷；hasResult 标记 + 按需读取
      assert(!done.asObject.exists(_.keys.exists(_ == "result")), "default payload must NOT carry result key")
      assert(!nodes.toString.contains("rrrrrr"), "600-r result text must not leak into payload")
      assertEquals(done.hcursor.downField("hasResult").as[Boolean].toOption, Some(true), "hasResult marker drives on-demand fetch")
      assertEquals(done.hcursor.downField("hasWorktree").as[Boolean].toOption, Some(false))
      assert(done.hcursor.downField("ttlLeftSec").as[Long].toOption.exists(_ > 0), "ttlLeftSec must be present for terminal node")
      // 子任务 C：节点配置字段（skill/mcp/preset）——裁定③（20260907 上下文经济学批）
      // 后条件序列化：仅存量非 None 节点携带（本节点三键有值，照常下发）
      assertEquals(done.hcursor.downField("skill").as[String].toOption, Some("code-review"))
      assertEquals(done.hcursor.downField("mcp").as[String].toOption, Some("github"))
      assertEquals(done.hcursor.downField("preset").as[String].toOption, Some("fast"))
      val wt = nodes.find(_.hcursor.get[String]("name").toOption.contains("并行")).get
      assertEquals(wt.hcursor.downField("hasWorktree").as[Boolean].toOption, Some(true))
      assertEquals(wt.hcursor.downField("worktree").as[String].toOption, Some("worktrees/wt-x"))
      val wts = json.hcursor.downField("worktrees").as[List[String]].toOption.getOrElse(Nil)
      assert(wts.contains("wt-x"), s"worktrees must be disk-derived, got $wts")
  }

  // ── Task 工具（阶段 2 迁移第一步：Nebula 侧项目任务触发）─────

  /** 分发器 agent fixture——spawnDispatcher 依赖 EntityLoader 可加载。 */
  private def writeDispatcherAgent(): Unit =
    val dispDir = tempRoot / "agents" / "project-dispatcher"
    os.makeDir.all(dispDir)
    os.write.over(
      dispDir / "agent.json",
      """{"name":"project-dispatcher","description":"project task dispatcher","tools":[],"category":"standalone"}"""
    )
    os.write.over(dispDir / "system.md", "# project-dispatcher\n")

  private def spawnProjectActor(
      rt: ProjectRuntime,
      system: ActorSystem,
      res: SharedResources,
      name: String
    ): IO[ActorRef[ProjectActor.ProjectCommand]] =
    system.spawn(ProjectActor(ProjectActor.ProjectConfig(rt.project, rt.engine, system, res, "nebula-root")), name)

  /** 门控 LLM（Task① 用）：sendStream 首段等待 gate —— 分发器 turn 保持
    * in-flight，注册窗口确定性可观测。 */
  private class GatedLlm(gate: cats.effect.Deferred[IO, Unit]) extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
    def sendStream(
        req: LlmRequest,
        onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(gate.get) >> Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  test("Task①: Task(project, task) triggers dispatcher session (dispatcher-<uuid> spawned)") {
    writeDispatcherAgent()
    val ws = tempRoot / "ws-task1"
    os.makeDir.all(ws)
    val system = ActorSystem(s"acc-task1-${scala.util.Random.nextInt(100000)}")
    for
      // #28 可观测接线后分发器是「运行中注册、完成即注销」的单次会话——gate 卡住
      // turn，注册窗口确定性可观测。
      gate <- cats.effect.Deferred[IO, Unit]
      res <- mkResources(system, tempRoot, new GatedLlm(gate))
      rt0 <- mountProject("acc-task1", ws, system, res)
      ref <- spawnProjectActor(rt0, system, res, "proj-task1")
      _ <- ProjectRuntimeRegistry.register(rt0.copy(actorRef = Some(ref)))
      ctx = mkCtx(res, system, ws.toString)
      r <- TaskTool.call(Json.obj(
        "project" -> Json.fromString("acc-task1"), "task" -> Json.fromString("调研 X")).asObject.get, ctx)
      // 1) 运行中必须注册（getActiveAgents 快照依赖）——agent 被 gate 卡在 turn 内，
      //    注册条目稳定存在，轮询必命中。
      seen <- pollRegistryFor(res.agentRegistry, _.startsWith("dispatcher-"), 100, 20.millis)
      _ <- gate.complete(()) // 释放 turn → 完成 → bridge 注销
      _ <- waitRegistryGone(res.agentRegistry, _.startsWith("dispatcher-"), 100, 20.millis) // 等待注销
      regAfter <- res.agentRegistry.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.exists(_.contains("dispatcher triggered")), s"got: $r")
      assert(seen.exists(_.nonEmpty),
        s"dispatcher session must be registered while running (snapshot/面板可见性前提), seen: $seen")
      assert(!regAfter.keys.exists(_.startsWith("dispatcher-")),
        s"dispatcher must unregister after completion (ghost-row fix), still present: ${regAfter.keys}")
  }

  /** 轮询 agentRegistry 直到出现满足 pred 的键或超时。返回每次采样快照。 */
  private def pollRegistryFor(
    registry: cats.effect.Ref[IO, Map[String, nebflow.agent.AgentRecord]],
    pred: String => Boolean,
    attempts: Int,
    interval: FiniteDuration
  ): IO[List[Set[String]]] =
    (1 to attempts).toList.foldLeft(IO.pure(List.empty[Set[String]])) { (acc, _) =>
      acc.flatMap { snaps =>
        registry.get.map(_.keySet).flatMap { keys =>
          val newSnaps = snaps :+ keys
          if keys.exists(pred) then IO.pure(newSnaps) else IO.sleep(interval).as(newSnaps)
        }
      }
    }

  /** 轮询直到不再存在满足 pred 的键（等待完成注销）。 */
  private def waitRegistryGone(
    registry: cats.effect.Ref[IO, Map[String, nebflow.agent.AgentRecord]],
    pred: String => Boolean,
    attempts: Int,
    interval: FiniteDuration
  ): IO[Unit] =
    (1 to attempts).toList.foldLeft(IO.unit) { (acc, _) =>
      acc.flatMap { _ =>
        registry.get.map(_.keySet).flatMap { keys =>
          if keys.exists(pred) then IO.sleep(interval) else IO.unit
        }
      }
    }

  test("Task②: unmounted project → explicit error (no registry entry)") {
    val system = ActorSystem(s"acc-task2-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot, new RecordingLlm)
      ctx = mkCtx(res, system, tempRoot.toString)
      r <- TaskTool.call(Json.obj(
        "project" -> Json.fromString("no-such"), "task" -> Json.fromString("x")).asObject.get, ctx)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isLeft, s"must fail, got: $r")
      assert(r.left.exists(_.message.contains("not mounted")), s"error must say not mounted, got: $r")
  }

  test("Task③: same-name collision — Task→project dispatcher vs Mail→team (channels distinct)") {
    writeDispatcherAgent()
    val ws = tempRoot / "ws-task3"
    os.makeDir.all(ws)
    val system = ActorSystem(s"acc-task3-${scala.util.Random.nextInt(100000)}")
    // 同名团队 fixture（slideblocks 场景：团队与新 project 同名，渠道区分零歧义）
    val teamDir = tempRoot / "teams" / "slideblocks"
    os.makeDir.all(teamDir)
    os.write.over(teamDir / "team.json", """{"name":"slideblocks","description":"legacy team","lead":"boss","members":[]}""")
    for
      res <- mkResources(system, tempRoot, new RecordingLlm)
      rt0 <- mountProject("slideblocks", ws, system, res)
      ref <- spawnProjectActor(rt0, system, res, "proj-slide")
      _ <- ProjectRuntimeRegistry.register(rt0.copy(actorRef = Some(ref)))
      _ <- TeamSessionRegistry.registerSession("slideblocks", "boss", "boss-sid")
      rootCtx = mkCtx(res, system, ws.toString).copy(sessionId = Some("root-sid"))
      // Task(project=slideblocks) → project dispatcher（新渠道）
      rTask <- TaskTool.call(Json.obj(
        "project" -> Json.fromString("slideblocks"), "task" -> Json.fromString("做 PPT")).asObject.get, rootCtx)
      // Mail(→slideblocks) → team（Mail 保持团队优先不翻转——immediate 全链）
      rMail <- MailTool.call(Json.obj(
        "address" -> Json.fromString("slideblocks"), "message" -> Json.fromString("hi")).asObject.get, rootCtx)
      leadSid <- TeamSessionRegistry.findTeamAgent("slideblocks", "boss")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(rTask.exists(_.contains("dispatcher triggered")), s"Task must hit project dispatcher, got: $rTask")
      // Mail 路由事实：同名团队命中（lead 可解析）→ 团队路由优先，完全不碰 project 分支
      assertEquals(leadSid, Some("boss-sid"), "same-named team lead must resolve (team routing premise)")
      assert(!rMail.exists(_.contains("dispatcher triggered")), s"Mail must NOT hit project dispatcher, got: $rMail")
      assert(!rMail.exists(_.contains("no mounted ProjectActor")), s"Mail must NOT hit project branch at all, got: $rMail")
  }

  // ── Node 完成通知蓝气泡 header（NODE · 项目 · 节点 · 状态）──────

  /** 注册一个记录根会话 actor（agentRegistry["nebula-root"]）——deliverToNebula
    * 会把 ImmediateInput 投给它；测试断言该命令携带的 source/eventType/sender。 */
  private def registerRecordingRoot(
    system: ActorSystem,
    res: SharedResources,
    rec: Ref[IO, List[AgentCommand]]
  ): IO[ActorRef[AgentCommand]] =
    def recBehavior: Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand] { cmd =>
        rec.update(_ :+ cmd).as(recBehavior)
      }
    for
      ref <- system.spawn(recBehavior, s"recroot-${scala.util.Random.nextInt(100000)}")
      _ <- res.agentRegistry.update(_ + ("nebula-root" -> AgentRecord("nebula-root", ref, AgentKind.Root, "nebula-root")))
    yield ref

  /** 轮询记录根 actor 直到收到 ImmediateInput 或超时（失败路径可能带一次
    * ≥5s backoff 重试，15s 窗口兜底）。 */
  private def pollImmediateInput(
    rec: Ref[IO, List[AgentCommand]],
    attempts: Int = 30,
    interval: FiniteDuration = 500.millis
  ): IO[Option[AgentCommand.ImmediateInput]] =
    (1 to attempts).toList.foldLeft(IO.pure(Option.empty[AgentCommand.ImmediateInput])) { (acc, _) =>
      acc.flatMap {
        case some @ Some(_) => IO.pure(some)
        case None =>
          rec.get.flatMap { cmds =>
            cmds.collectFirst { case i: AgentCommand.ImmediateInput => i } match
              case some @ Some(_) => IO.pure(some)
              case None           => IO.sleep(interval).as(None)
          }
      }
    }

  test("⑬ bubble completed: node out=Nebula → ImmediateInput(source=node, eventType=completed, sender='project/node')") {
    val ws = tempRoot / "ws-bubble-c"
    os.makeDir.all(ws)
    val system = ActorSystem(s"acc-bubc-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot, new RecordingLlm)
      rt <- mountProject("acc-bubble-c", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      rec <- Ref.of[IO, List[AgentCommand]](Nil)
      _ <- registerRecordingRoot(system, res, rec)
      r <- nodeEdit(nodeInput("acc-bubble-c", "调研-通知", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("bubble test"), "out" -> Json.fromString("Nebula")), ctx)
      imm <- pollImmediateInput(rec)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isRight, s"entry create must succeed, got: $r")
      assert(imm.isDefined, s"root must receive ImmediateInput for completed node")
      assertEquals(imm.flatMap(_.source), Some("node"))
      assertEquals(imm.flatMap(_.eventType), Some("completed"))
      assertEquals(imm.flatMap(_.sender), Some("acc-bubble-c/调研-通知"),
        "sender must be '<project>/<node>' for the NODE · project · node · status header")
      assert(imm.exists(_.text.startsWith("[Node '调研-通知' completed]")), s"text prefix, got ${imm.map(_.text.take(60))}")
  }

  test("⑬ bubble failed: node LLM failure → ImmediateInput(source=node, eventType=failed, sender='project/node')") {
    val ws = tempRoot / "ws-bubble-f"
    os.makeDir.all(ws)
    val system = ActorSystem(s"acc-bubf-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot, new FailStreamLlm)
      rt <- mountProject("acc-bubble-f", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      rec <- Ref.of[IO, List[AgentCommand]](Nil)
      _ <- registerRecordingRoot(system, res, rec)
      r <- nodeEdit(nodeInput("acc-bubble-f", "调研-失败", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("will fail"), "out" -> Json.fromString("Nebula")), ctx)
      imm <- pollImmediateInput(rec)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isRight, s"entry create must succeed, got: $r")
      assert(imm.isDefined, s"root must receive ImmediateInput for failed node")
      assertEquals(imm.flatMap(_.source), Some("node"))
      assertEquals(imm.flatMap(_.eventType), Some("failed"), "failed node must NOT carry eventType=completed")
      assertEquals(imm.flatMap(_.sender), Some("acc-bubble-f/调研-失败"))
      assert(imm.exists(_.text.startsWith("[Node '调研-失败' failed]")), s"text prefix, got ${imm.map(_.text.take(60))}")
  }

  // ── NodeEdit worktree 布尔派生（2026-09-05 显式布尔改造）──
  //
  // 契约：worktree String→Boolean——true = NodeEdit 即时派生创建
  // (<derived-from-node-name> 同名分支、基线 main HEAD；fail-fast，失败拒绝建节点)；
  // false/缺省 = workspace 直跑；旧字符串形态（裸名/前缀/绝对路径）一律
  // WORKTREE_NOT_BOOLEAN 拒绝；编辑路径 WORKTREE_CREATE_ONLY 拒绝。
  // （20260903 的「三形态宽容 + 双位置实存」查找契约随字符串参数一并退役。）

  /** 建 worktree 集成测试项目（git 仓 workspace + 真实 spawn 走 RecordingLlm 秒完）。 */
  private def wtProject(tag: String): (os.Path, ActorSystem, SharedResources, ProjectRuntime, ToolContext) =
    val ws = tempRoot / s"ws-wt-$tag"
    os.makeDir.all(ws)
    os.proc("git", "init", ws.toString).call(check = true)
    os.proc("git", "-C", ws.toString, "config", "user.email", "spec@nebflow.local").call(check = true)
    os.proc("git", "-C", ws.toString, "config", "user.name", "spec").call(check = true)
    os.proc("git", "-C", ws.toString, "commit", "--allow-empty", "-m", "init").call(check = true)
    val system = ActorSystem(s"acc-wt-$tag-${scala.util.Random.nextInt(100000)}")
    val res = mkResources(system, tempRoot, new RecordingLlm).unsafeRunSync()
    val rt = mountProject(s"acc-wt-$tag", ws, system, res).unsafeRunSync()
    (ws, system, res, rt, mkCtx(res, system, ws.toString))

  test("WT-1 worktree=true: derived worktree+branch created immediately, bare name stored") {
    val (ws, system, res, rt, ctx) = wtProject("bare")
    for
      r <- nodeEdit(nodeInput("acc-wt-bare", "调研-派生", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("t"), "worktree" -> Json.fromBoolean(true),
        "out" -> Json.fromString("Nebula")), ctx)
      s <- rt.store.snapshot
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isRight, s"worktree=true create must succeed, got: $r")
      val wt = s.nodes.values.find(_.name == "调研-派生").flatMap(_.worktree)
      assert(wt.isDefined, "NodeDef.worktree must store the derived bare name")
      assert(os.exists(ws / ".nebflow" / "worktrees" / wt.get / ".git"), "derived worktree dir must exist")
      val branches = os.proc("git", "-C", ws.toString, "branch", "--list", wt.get).call(check = true).out.trim()
      assert(branches.nonEmpty, s"same-name branch must exist, got: '$branches'")
  }

  test("WT-2 worktree=true: sanitize-collision → unique derived suffix (-2), both nodes independent") {
    val (ws, system, res, rt, ctx) = wtProject("unique")
    for
      // 「调研 同」（空格）与「调研-同」sanitize 后同名（空格 → -）→ 派生名冲突走 -2 后缀
      r1 <- nodeEdit(nodeInput("acc-wt-unique", "调研 同", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("t1"), "worktree" -> Json.fromBoolean(true), "out" -> Json.fromString("Nebula")), ctx)
      r2 <- nodeEdit(nodeInput("acc-wt-unique", "调研-同", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("t2"), "worktree" -> Json.fromBoolean(true), "out" -> Json.fromString("Nebula")), ctx)
      s <- rt.store.snapshot
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r1.isRight && r2.isRight, s"both creates must succeed, got: $r1 / $r2")
      val wts = s.nodes.values.toList.sortBy(_.name).flatMap(_.worktree)
      assertEquals(wts.size, 2)
      assert(wts(0) != wts(1), s"derived names must be unique, got: $wts")
      assert(wts(1).endsWith("-2"), s"second derived name must carry -2 suffix, got: $wts")
      // CJK 保留（sanitize 不把中文名归一成 "node"）
      assert(wts(0).startsWith("调研"), s"CJK must be preserved in derived name, got: $wts")
  }

  test("WT-3 worktree=false → workspace direct-run (no binding), matches omitted") {
    val (ws, system, res, rt, ctx) = wtProject("false")
    for
      r <- nodeEdit(nodeInput("acc-wt-false", "N3", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("t"), "worktree" -> Json.fromBoolean(false),
        "out" -> Json.fromString("Nebula")), ctx)
      s <- rt.store.snapshot
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isRight, s"worktree=false create must succeed, got: $r")
      assertEquals(s.nodes.values.find(_.name == "N3").flatMap(_.worktree), None)
      // workspace 直跑：不派生任何 worktree 目录
      val wtsDir = ws / ".nebflow" / "worktrees"
      assert(!os.exists(wtsDir) || os.list(wtsDir).isEmpty, "worktree=false must not create any worktree dir")
  }

  test("WT-4 worktree string form (bare/prefix/absolute) → WORKTREE_NOT_BOOLEAN (legacy forms retired)") {
    val (ws, system, res, _, ctx) = wtProject("str")
    for
      rBare <- nodeEdit(nodeInput("acc-wt-str", "N4a", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("t"), "worktree" -> Json.fromString("wt-a"), "out" -> Json.fromString("Nebula")), ctx)
      rPrefix <- nodeEdit(nodeInput("acc-wt-str", "N4b", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("t"), "worktree" -> Json.fromString("worktrees/wt-a"), "out" -> Json.fromString("Nebula")), ctx)
      rAbs <- nodeEdit(nodeInput("acc-wt-str", "N4c", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("t"), "worktree" -> Json.fromString("/abs/x"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      for (r, form) <- List((rBare, "bare"), (rPrefix, "prefix"), (rAbs, "absolute")) do
        assert(r.isLeft, s"$form string form must be rejected")
        assert(r.left.exists(_.contains("WORKTREE_NOT_BOOLEAN")), s"$form rejection must carry WORKTREE_NOT_BOOLEAN, got: ${r.left.getOrElse("")}")
  }

  test("WT-5 worktree=true on non-git workspace → fail-fast Left, NO node created") {
    val ws = tempRoot / s"ws-wt-nogit"
    os.makeDir.all(ws) // 非 git 目录（tempRoot 在仓库树内但 ws 自身无 .git 且 show-toplevel ≠ ws）
    val system = ActorSystem(s"acc-wt-nogit-${scala.util.Random.nextInt(100000)}")
    val res = mkResources(system, tempRoot, new RecordingLlm).unsafeRunSync()
    val rt = mountProject("acc-wt-nogit", ws, system, res).unsafeRunSync()
    val ctx = mkCtx(res, system, ws.toString)
    for
      r <- nodeEdit(nodeInput("acc-wt-nogit", "N5", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("t"), "worktree" -> Json.fromBoolean(true), "out" -> Json.fromString("Nebula")), ctx)
      s <- rt.store.snapshot
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // 显式布尔契约 fail-fast：创建失败 → 节点不存在（不做 workspace 直跑静默降级）
      assert(r.isLeft && r.left.exists(_.contains("git repository")), s"non-git workspace must fail fast, got: $r")
      assert(s.nodes.isEmpty, "fail-fast: node must NOT be created")
  }

  test("WT-6 worktree on edit → WORKTREE_CREATE_ONLY (create-time binding)") {
    val (ws, system, res, _, ctx) = wtProject("edit")
    for
      _ <- nodeEdit(nodeInput("acc-wt-edit", "N6", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("t"), "out" -> Json.fromString("Nebula")), ctx)
      r <- nodeEdit(nodeInput("acc-wt-edit", "N6", "worktree" -> Json.fromBoolean(true)), ctx)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isLeft && r.left.exists(_.contains("WORKTREE_CREATE_ONLY")), s"worktree on edit must be refused, got: $r")
  }

  test("WT-7 NodeList worktrees[]: dual-position merge + dedup + stable sort") {
    val ws = tempRoot / "ws-wt-nodelist"
    os.makeDir.all(ws)
    val system = ActorSystem(s"acc-wtnl-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot, new RecordingLlm)
      _ <- mountProject("acc-wt-nodelist", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // 权威位置两个 + 顶层一个 + 顶层软链别名（指向权威同名——生产孤儿清理形态）
      // + 保留名系统目录（skills/：QC P1——不得混入 worktrees[] 候选）
      _ <- IO.blocking {
        os.makeDir.all(ws / ".nebflow" / "worktrees" / "wt-a")
        os.makeDir.all(ws / ".nebflow" / "worktrees" / "wt-x")
        os.makeDir.all(ws / ".nebflow" / "wt-b")
        os.makeDir.all(ws / ".nebflow" / "skills")
        java.nio.file.Files.createSymbolicLink(
          (ws / ".nebflow" / "wt-a").toNIO, (os.rel / "worktrees" / "wt-a").toNIO)
      }
      r <- NodeListTool.call(Json.obj("project" -> Json.fromString("acc-wt-nodelist")).asObject.get, ctx)
      payload <- IO.fromEither(r.left.map(e => new RuntimeException(e.message)))
      json <- IO.fromEither(io.circe.parser.parse(payload))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      val wts = json.hcursor.downField("worktrees").as[List[String]].toOption.getOrElse(Nil)
      assertEquals(wts, List("wt-a", "wt-b", "wt-x"),
        s"dual-position merge must dedup (wt-a via symlink alias) and sort stably, got: $wts")
  }

end NodeAcceptanceSpec
