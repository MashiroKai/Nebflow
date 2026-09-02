package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, ActorSystem}
import nebflow.agent.{AgentLibrary, SharedResources}
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

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  private class RecordingLlm extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
    def sendStream(
        req: LlmRequest,
        onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
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
      sessionId = Some("acceptance-sid"),
      rootSessionId = Some("nebula-root"),
      sharedResources = Some(res),
      actorSystem = Some(system)
    )

  private def nodeEdit(input: Json, ctx: ToolContext): IO[Either[String, String]] =
    NodeEditTool.call(input.asObject.get, ctx).map(_.left.map(_.message))

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
        emitEvent = (_, _, _) => IO.unit
      )
      pd = ProjectDef(name = name, workspace = ws.toString, agentFile = (ws / ".nebflow" / "Agent.md").toString, createdAt = System.currentTimeMillis())
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
      // 显示消失后接线：NodeEdit 建 B，in 引用归档 A
      _ <- nodeEdit(nodeInput("acc-ttl-b", "B", "agent" -> Json.fromString("test-agent"),
        "task" -> Json.fromString("consume A"), "in" -> Json.arr(Json.fromString("n-a"))), ctx)
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
      r <- nodeEdit(nodeInput("acc-entry", "调研-入口", "agent" -> Json.fromString("test-agent"),
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

  // ── ② 结果持久保存（完成后 result 落盘，reopen 后仍在）──

  test("② result persisted: completed node result survives store reopen (restart)") {
    val ws = tempRoot / "ws-result"
    os.makeDir.all(ws)
    val system = ActorSystem(s"acc-res-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot, new RecordingLlm)
      rt <- mountProject("acc-result", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- nodeEdit(nodeInput("acc-result", "调研-落盘", "agent" -> Json.fromString("test-agent"),
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
      // 入口节点（无 out = 悬空；task 非空 → 创建即运行）
      _ <- nodeEdit(nodeInput("acc-dangle", "调研-悬空", "agent" -> Json.fromString("test-agent"),
        "task" -> Json.fromString("research")), ctx)
      _ <- IO.sleep(3.seconds)
      s1 <- rt.store.snapshot
      aId = s1.nodes.values.find(_.name == "调研-悬空").map(_.id).getOrElse("")
      a <- rt.store.getNode(aId)
      _ <- IO(assert(a.exists(_.status == NodeLifecycle.Completed), s"source must complete, got ${a.map(_.status)}"))
      // 建下游 B（wiring），把 A 改接 out → B
      _ <- nodeEdit(nodeInput("acc-dangle", "下游-B", "agent" -> Json.fromString("test-agent"),
        "task" -> Json.fromString("consume"), "out" -> Json.fromString("Nebula")), ctx)
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

  // ── ⑤ 断开（out=null 悬空化，结果保留）────────────────

  test("⑤ disconnect: out=null detaches, result retained in activity, rewirable") {
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
          "n-a" -> NodeDef(id = "n-a", name = "A", agent = "test-agent",
            status = NodeLifecycle.Completed, result = Some("kept result"), createdAt = now,
            completedAt = Some(now - 1000), ttlExpireAt = Some(now + 99999), out = Some("n-x")),
          "n-x" -> NodeDef(id = "n-x", name = "X", agent = "test-agent",
            status = NodeLifecycle.Wiring, in = List("n-a"), createdAt = now),
          "n-b" -> NodeDef(id = "n-b", name = "B", agent = "test-agent",
            status = NodeLifecycle.Wiring, createdAt = now)
        ))
      )
      // A.out = null → 断开（X.in 同步移除，结果保留）
      r <- nodeEdit(nodeInput("acc-disc", "A", "out" -> Json.Null), ctx)
      s1 <- rt.store.snapshot
      a <- rt.store.getNode("n-a")
      x <- rt.store.getNode("n-x")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isRight, s"disconnect must succeed, got: $r")
      assertEquals(a.map(_.out), Some(None), "A.out must become null (dangling)")
      assertEquals(a.flatMap(_.result), Some("kept result"), "A.result must be retained")
      assertEquals(x.map(_.in), Some(List.empty), "old target X.in must have A removed")
      assertEquals(x.map(_.deliveredTo), Some(List.empty), "X.deliveredTo must not contain A after detach")
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
      r <- nodeEdit(nodeInput("acc-1n", "A", "agent" -> Json.fromString("test-agent"),
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
      r <- nodeEdit(nodeInput("acc-barrier", "M", "agent" -> Json.fromString("test-agent"),
        "task" -> Json.fromString("merge all"), "in" -> Json.arr(Json.fromString("n-a"), Json.fromString("n-b"), Json.fromString("n-c"))), ctx)
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
      _ <- nodeEdit(nodeInput("acc-loop", "调研-重复", "agent" -> Json.fromString("test-agent"),
        "task" -> Json.fromString("  调研 康普顿   成像  "), "out" -> Json.fromString("Nebula")), ctx)
      _ <- IO.sleep(3.seconds)
      s1 <- rt.store.snapshot
      _ <- IO(assert(
        s1.nodes.values.find(_.name == "调研-重复").exists(n => n.status == NodeLifecycle.Completed || n.status == NodeLifecycle.Running),
        s"first dispatch must run, got ${s1.nodes.values.find(_.name == "调研-重复").map(_.status)}"
      ))
      // 第二次派发：同 agent + 同 task（不同空白）→ loop detect 拒绝
      r2 <- nodeEdit(nodeInput("acc-loop", "调研-重复2", "agent" -> Json.fromString("test-agent"),
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
      _ <- nodeEdit(nodeInput("acc-0write", "零写入", "agent" -> Json.fromString("test-agent"),
        "task" -> Json.fromString("write nothing"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- nodeEdit(nodeInput("acc-0write", "零写入2", "agent" -> Json.fromString("test-agent"),
        "task" -> Json.fromString("write nothing 2"), "out" -> Json.fromString("Nebula")), ctx)
      after <- IO.blocking(os.list(nebflowDir).map(_.last).toList.sorted)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(before.contains("flow-map.json"), s"store first-write must exist, got $before")
      assertEquals(after, before, s"NodeEdit must not add hand-written files, before=$before after=$after")
  }

  // ── ⑫ NodeList 快照字段完整性（分发器决策依据）─────────

  test("⑫ NodeList: snapshot carries status/result summary/hasWorktree/worktrees/ttlLeftSec/skill/mcp/preset") {
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
      val doneRes = done.hcursor.downField("result").as[String].toOption.getOrElse("")
      assert(doneRes.length <= 501 && doneRes.length >= 500, s"result must be truncated to ≤500-char summary + ellipsis, got ${doneRes.length}")
      assertEquals(done.hcursor.downField("hasWorktree").as[Boolean].toOption, Some(false))
      assert(done.hcursor.downField("ttlLeftSec").as[Long].toOption.exists(_ > 0), "ttlLeftSec must be present for terminal node")
      // 子任务 C：节点配置字段（skill/mcp/preset）须随 NodeList 载荷下发（前端 Flow Map 展示依据）
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

  test("Task①: Task(project, task) triggers dispatcher session (dispatcher-<uuid> spawned)") {
    writeDispatcherAgent()
    val ws = tempRoot / "ws-task1"
    os.makeDir.all(ws)
    val system = ActorSystem(s"acc-task1-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot, new RecordingLlm)
      rt0 <- mountProject("acc-task1", ws, system, res)
      ref <- spawnProjectActor(rt0, system, res, "proj-task1")
      _ <- ProjectRuntimeRegistry.register(rt0.copy(actorRef = Some(ref)))
      ctx = mkCtx(res, system, ws.toString)
      r <- TaskTool.call(Json.obj(
        "project" -> Json.fromString("acc-task1"), "task" -> Json.fromString("调研 X")).asObject.get, ctx)
      _ <- IO.sleep(3.seconds) // 等待 dispatcher spawn + agentRegistry 注册
      reg <- res.agentRegistry.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.exists(_.contains("dispatcher triggered")), s"got: $r")
      assert(reg.keys.exists(_.startsWith("dispatcher-")), s"dispatcher session must be spawned, registry=${reg.keys}")
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

end NodeAcceptanceSpec
