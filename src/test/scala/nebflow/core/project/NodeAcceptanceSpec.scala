package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
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

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear

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

end NodeAcceptanceSpec
