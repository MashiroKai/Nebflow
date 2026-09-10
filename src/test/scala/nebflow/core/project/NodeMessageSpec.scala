package nebflow.core.project

import cats.effect.{IO, Ref}
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, Behaviors}
import nebflow.agent.{AgentCommand, AgentKind, AgentLibrary, AgentRecord, SharedResources}
import nebflow.core.PathUtil
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, NodeMessageTool, ToolContext}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, StreamChunk}

import scala.concurrent.duration.*

/**
 * NodeMessage（20260905 机制批，作者裁定六条语义）spec——六条逐条覆盖：
 *
 *  - ①签名：NODE_NOT_FOUND（活动+归档均无）/ NODE_MESSAGE_EMPTY（空白 trim）
 *  - ②running 注入：活会话 → ImmediateInput（[NODE-MESSAGE] 前缀 + 来源标注
 *    （分发器 NodeMessage + 节点名 + 时间戳））；task 不被改写；竞态兜底——
 *    nodeSessions/registry 查无映射 → 任务记录追加「注入未达」（留痕不丢）
 *  - ③未启动追加：wiring / pending → task 追加「== 分发器补充
 *    （NodeMessage <时间戳>） ==」分节（buildInput 启动时沿 task 读到）；
 *    多条消息顺序追加
 *  - ④终态拒绝 ×4 态：completed / failed / cancelled / blocked →
 *    NODE_TERMINAL_NO_MESSAGE，零副作用（task 不动、零事件日志）
 *  - ⑤留痕持久化：每条消息落 FlowMapEventLog（type=node-message，注入/
 *    追加/未达三形态）；store reopen 重载后追加段仍在（持久）
 *  - ⑤载荷不变：NodePayload.buildNodeJson 键集追加前后全等（Flow Map 默认
 *    载荷零膨胀——无 hasMessages 等任何新键）
 *
 * 变异验红（见实施报告）：终态拒绝分支摘除 → S4 红；running 未达兜底摘除
 * （查无会话仍报 injected）→ S2b 红；pending 追加 mutate 摘除（no-op 直返
 * 成功）→ S3/S5 红。恢复后全绿。
 */
class NodeMessageSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-node-message"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "test-agent")
  os.write.over(
    tempRoot / "agents" / "test-agent" / "agent.json",
    """{"name":"test-agent","description":"node-message regression agent","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "test-agent" / "system.md", "# test-agent\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  /** 无 LLM 请求（本 spec 不 spawn 真会话——running 会话用 recorder actor 模拟）。 */
  private object NoLlm extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[nebflow.shared.LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected"))
    def sendStream(
        req: LlmRequest,
        onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.raiseError[IO](new RuntimeException("sendStream not expected"))

  private def mkResources(system: ActorSystem, tmp: os.Path): IO[SharedResources] =
    for
      dispatcher <- cats.effect.std.Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- nebflow.core.FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
    yield SharedResources(
      llm = NoLlm,
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
      sessionId = Some("nmsg-sid"),
      rootSessionId = Some("nebula-root"),
      sharedResources = Some(res),
      actorSystem = Some(system)
    )

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
        feedbackMode = FeedbackRouter.ModeAuto,
        emitEvent = (_, _, _) => IO.unit
      )
      pd = ProjectDef(name = name, workspace = ws.toString, agentFile = (ws / "AGENTS.md").toString, createdAt = System.currentTimeMillis())
      rt = ProjectRuntime(pd, store, engine, system, res, None)
      _ <- ProjectRuntimeRegistry.register(rt)
    yield rt

  private def eventLogLines(ws: os.Path): IO[List[String]] =
    IO.blocking {
      val p = os.Path(ws.toString, PathUtil.dataRoot) / ".nebflow" / FlowMapEventLog.FileName
      if os.exists(p) then os.read(p).linesIterator.toList else Nil
    }

  /** wiring/pending 等非运行节点直种（seedWiring 同款）。 */
  private def seedNode(
      rt: ProjectRuntime,
      id: String,
      name: String,
      status: String,
      task: Option[String] = None,
      result: Option[String] = None
  ): IO[Unit] =
    rt.store.mutate(s => s.copy(nodes = s.nodes ++ Map(
      id -> NodeDef(id = id, name = name, agent = "test-agent", task = task, result = result,
        status = status, out = List(OutEdge.nebula), createdAt = System.currentTimeMillis())))).void

  private def nodeMessage(rt: ProjectRuntime, nodeId: String, message: String): IO[Either[String, String]] =
    rt.engine.sendNodeMessage(nodeId, message)

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  // ── ①签名：NODE_NOT_FOUND / NODE_MESSAGE_EMPTY ──────────

  test("S1-NOT_FOUND: unknown nodeId (active+archive) → NODE_NOT_FOUND; blank message → NODE_MESSAGE_EMPTY; zero audit writes") {
    val ws = tempRoot / "ws-nmsg-s1"
    os.makeDir.all(ws)
    val system = ActorSystem(s"nmsg-s1-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot)
      rt <- mountProject("nmsg-s1", ws, system, res)
      r1 <- nodeMessage(rt, "n-nonexistent", "hello")
      r2 <- nodeMessage(rt, "n-also-missing", "  ")
      lines <- eventLogLines(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r1.isLeft && r1.left.exists(_.contains("NODE_NOT_FOUND")), s"unknown id must be NODE_NOT_FOUND, got $r1")
      // 空白消息（裁定①）独立于存在性——错误码 NODE_MESSAGE_EMPTY
      assert(r2.isLeft && r2.left.exists(_.contains("NODE_MESSAGE_EMPTY")), s"blank message must be NODE_MESSAGE_EMPTY, got $r2")
      assert(lines.isEmpty, s"failed calls must write zero audit lines, got $lines")
  }

  test("S1-EMPTY: blank message (trim) on an existing node → NODE_MESSAGE_EMPTY, node untouched") {
    val ws = tempRoot / "ws-nmsg-s1b"
    os.makeDir.all(ws)
    val system = ActorSystem(s"nmsg-s1b-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot)
      rt <- mountProject("nmsg-s1b", ws, system, res)
      _ <- seedNode(rt, "n-a", "node-a", NodeLifecycle.Pending, task = Some("base"))
      before <- rt.store.getNode("n-a")
      r <- nodeMessage(rt, "n-a", "   \n\t ")
      after <- rt.store.getNode("n-a")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isLeft && r.left.exists(_.contains("NODE_MESSAGE_EMPTY")), s"blank must be refused, got $r")
      assertEquals(before.flatMap(_.task), after.flatMap(_.task), "refused call leaves the node untouched")
  }

  // ── ④终态拒绝 ×4 态 ─────────────────────────────────────

  test("S4-TERMINAL: all four terminal states refuse with NODE_TERMINAL_NO_MESSAGE, zero side effects") {
    val ws = tempRoot / "ws-nmsg-s4"
    os.makeDir.all(ws)
    val system = ActorSystem(s"nmsg-s4-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot)
      rt <- mountProject("nmsg-s4", ws, system, res)
      _ <- seedNode(rt, "n-c", "done-c", NodeLifecycle.Completed, task = Some("t-c"), result = Some("res-c"))
      _ <- seedNode(rt, "n-f", "boom-f", NodeLifecycle.Failed, task = Some("t-f"), result = Some("err"))
      _ <- seedNode(rt, "n-x", "stop-x", NodeLifecycle.Cancelled, task = Some("t-x"))
      _ <- seedNode(rt, "n-b", "halt-b", NodeLifecycle.Blocked, task = Some("t-b"), result = Some("[blocked:other] x"))
      rc <- nodeMessage(rt, "n-c", "late msg")
      rf <- nodeMessage(rt, "n-f", "late msg")
      rx <- nodeMessage(rt, "n-x", "late msg")
      rb <- nodeMessage(rt, "n-b", "late msg")
      nodes <- rt.store.snapshot.map(_.nodes)
      lines <- eventLogLines(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      val results = List(rc, rf, rx, rb)
      results.zipWithIndex.foreach { (r, i) =>
        assert(r.isLeft && r.left.exists(_.contains("NODE_TERMINAL_NO_MESSAGE")),
          s"terminal state #$i must refuse with NODE_TERMINAL_NO_MESSAGE, got $r")
      }
      assert(rc.left.exists(_.contains("completed")) && rb.left.exists(_.contains("blocked")),
        "error text names the concrete status (blocked included per ruling)")
      // 零副作用：task/result/status 全部原样
      assertEquals(nodes("n-c").task, Some("t-c"), "completed node task untouched")
      assertEquals(nodes("n-c").result, Some("res-c"), "completed node result untouched")
      assertEquals(nodes("n-b").status, NodeLifecycle.Blocked, "blocked node stays blocked")
      assertEquals(lines, Nil, "refused calls write zero audit lines")
  }

  // ── ③未启动追加（wiring / pending）+ ⑤留痕/载荷 ──

  test("S3-PENDING: wiring+pending nodes get the message appended to task with the section marker; audit line written") {
    val ws = tempRoot / "ws-nmsg-s3"
    os.makeDir.all(ws)
    val system = ActorSystem(s"nmsg-s3-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot)
      rt <- mountProject("nmsg-s3", ws, system, res)
      _ <- seedNode(rt, "n-w", "wiring-w", NodeLifecycle.Wiring, task = Some("base-W"))
      _ <- seedNode(rt, "n-p", "pending-p", NodeLifecycle.Pending, task = Some("base-P"))
      before <- rt.store.getNode("n-w").map(_.getOrElse(fail("n-w must exist")))
      payloadKeysBefore = NodePayload.buildNodeJson(before, 0L).asObject.get.keys.toSet
      rw <- nodeMessage(rt, "n-w", "补充指示-W：使用 v2 接口")
      rp <- nodeMessage(rt, "n-p", "补充指示-P：先跑 lint")
      w <- rt.store.getNode("n-w").map(_.getOrElse(fail("n-w must exist")))
      p <- rt.store.getNode("n-p").map(_.getOrElse(fail("n-p must exist")))
      payloadKeysAfter = NodePayload.buildNodeJson(w, 0L).asObject.get.keys.toSet
      lines <- eventLogLines(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(rw.isRight, s"wiring append must succeed, got $rw")
      assert(rp.isRight, s"pending append must succeed, got $rp")
      // 分节头 + 原任务保留（裁定③格式）
      assert(w.task.exists(_.startsWith("base-W")), "original task preserved (append, not replace)")
      assert(w.task.exists(_.contains("== 分发器补充（NodeMessage")), s"section marker present, got task=${w.task}")
      assert(w.task.exists(_.contains("补充指示-W：使用 v2 接口")), "message text present")
      assert(p.task.exists(_.contains("== 分发器补充（NodeMessage")) && p.task.exists(_.contains("补充指示-P")),
        s"pending node appended too, got task=${p.task}")
      assert(!w.task.exists(_.contains("注入未达")), "delivered append must NOT carry the undelivered annotation")
      // 裁定⑤载荷不变：默认载荷键集追加前后全等
      assertEquals(payloadKeysAfter, payloadKeysBefore, "Flow Map default payload key set must be unchanged (no hasMessages etc.)")
      // 裁定⑤留痕：node-message 审计行
      assert(lines.count(_.contains("\"type\":\"node-message\"")) == 2, s"two node-message audit lines expected, got $lines")
      assert(lines.exists(l => l.contains("n-w") && l.contains("appended to task")), "wiring append audited")
  }

  test("S3-MULTI: two messages append two sequential sections (order preserved)") {
    val ws = tempRoot / "ws-nmsg-s3m"
    os.makeDir.all(ws)
    val system = ActorSystem(s"nmsg-s3m-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot)
      rt <- mountProject("nmsg-s3m", ws, system, res)
      _ <- seedNode(rt, "n-m", "multi-m", NodeLifecycle.Pending, task = Some("base"))
      _ <- nodeMessage(rt, "n-m", "first-msg-ALPHA")
      _ <- nodeMessage(rt, "n-m", "second-msg-BRAVO")
      m <- rt.store.getNode("n-m").map(_.getOrElse(fail("n-m must exist")))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      val task = m.task.getOrElse(fail("task must be defined"))
      val iA = task.indexOf("first-msg-ALPHA")
      val iB = task.indexOf("second-msg-BRAVO")
      assert(iA >= 0 && iB >= 0, s"both messages present, got $task")
      assert(iA < iB, "sections appended in send order")
      assertEquals(task.split("== 分发器补充（NodeMessage").length - 1, 2, "exactly two section markers")
  }

  // ── ②running：注入 + 竞态兜底 ───────────────────────────

  /** recorder actor 模拟节点活会话（捕获 ImmediateInput），登记 agentRegistry。 */
  private def registerNodeSession(
      rt: ProjectRuntime,
      system: ActorSystem,
      nodeId: String,
      sessionId: String
  ): IO[Ref[IO, List[AgentCommand]]] =
    for
      recorded <- Ref.of[IO, List[AgentCommand]](Nil)
      ref <- system.spawn(recorderBehavior(recorded), s"nmsg-rec-$sessionId")
      _ <- rt.resources.agentRegistry.update(_ + (sessionId -> AgentRecord(sessionId, ref, AgentKind.Flow, "nebula-root")))
      // nodeSessions 是 private[project]——本 spec 同包直种（模拟 runWithAgent 置位）
      _ <- rt.engine.nodeSessions.update(_ + (nodeId -> sessionId))
    yield recorded

  private def recorderBehavior(recorded: Ref[IO, List[AgentCommand]]): nebflow.actor.Behavior[AgentCommand] =
    lazy val b: nebflow.actor.Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand](msg => recorded.update(_ :+ msg).as(b))
    b

  test("S2-RUNNING: live session → ImmediateInput with [NODE-MESSAGE] header (source+node+timestamp); task untouched; audit=injected") {
    val ws = tempRoot / "ws-nmsg-s2"
    os.makeDir.all(ws)
    val system = ActorSystem(s"nmsg-s2-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot)
      rt <- mountProject("nmsg-s2", ws, system, res)
      _ <- seedNode(rt, "n-r", "runner-r", NodeLifecycle.Running, task = Some("original task"))
      recorded <- registerNodeSession(rt, system, "n-r", "node-fakesession")
      r <- nodeMessage(rt, "n-r", "running 补充：加上回归测试")
      n <- rt.store.getNode("n-r").map(_.getOrElse(fail("n-r must exist")))
      imms <- recorded.get.map(_.collect { case m: AgentCommand.ImmediateInput => m })
      lines <- eventLogLines(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isRight, s"running inject must succeed, got $r")
      assertEquals(imms.size, 1, s"exactly one ImmediateInput expected, got ${imms.map(_.text.take(80))}")
      val text = imms.head.text
      assert(text.startsWith("[NODE-MESSAGE]"), s"recognizable prefix required, got: ${text.take(60)}")
      assert(text.contains("NodeMessage") && text.contains("runner-r"), s"source attribution (NodeMessage + node name) present, got: ${text.take(120)}")
      assert(text.contains("running 补充：加上回归测试"), "message body carried")
      assertEquals(imms.head.source, Some("system"), "source marker = system")
      // 裁定②：running 注入不改任务记录（会话已消费 task，注入走会话面）
      assertEquals(n.task, Some("original task"), "running inject must NOT touch the task record")
      // 裁定⑤留痕：injected 形态
      assert(lines.exists(l => l.contains("\"type\":\"node-message\"") && l.contains("injected")), s"injected audit line expected, got $lines")
  }

  test("S2b-UNDELIVERED: running node whose session vanished → fallback task append annotated 注入未达 (trace not lost)") {
    val ws = tempRoot / "ws-nmsg-s2b"
    os.makeDir.all(ws)
    val system = ActorSystem(s"nmsg-s2b-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot)
      rt <- mountProject("nmsg-s2b", ws, system, res)
      // status=running 但 nodeSessions/registry 查无映射 = 会话在检查与入队间终结
      _ <- seedNode(rt, "n-d", "dying-d", NodeLifecycle.Running, task = Some("dying task"))
      r <- nodeMessage(rt, "n-d", "未达消息-GOLF")
      n <- rt.store.getNode("n-d").map(_.getOrElse(fail("n-d must exist")))
      lines <- eventLogLines(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isRight, s"race fallback must not fail the call (trace preserved), got $r")
      assert(r.exists(_.contains("注入未达")), s"result text names the fallback, got $r")
      assert(n.task.exists(_.contains("dying task")), "original task preserved")
      assert(n.task.exists(_.contains("== 分发器补充（NodeMessage")), s"section marker present, got task=${n.task}")
      assert(n.task.exists(t => t.contains("注入未达") && t.contains("未达消息-GOLF")),
        s"undelivered annotation + message text present, got task=${n.task}")
      assert(lines.exists(l => l.contains("\"type\":\"node-message\"") && l.contains("not-delivered")),
        s"not-delivered audit line expected, got $lines")
  }

  // ── ⑤持久化：reopen 重载后追加段仍在 ────────────────────

  test("S5-PERSIST: append survives a store reopen (persisted to flow-map.json, reload verbatim)") {
    val ws = tempRoot / "ws-nmsg-s5"
    os.makeDir.all(ws)
    val system = ActorSystem(s"nmsg-s5-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot)
      rt <- mountProject("nmsg-s5", ws, system, res)
      _ <- seedNode(rt, "n-s", "persist-s", NodeLifecycle.Pending, task = Some("base"))
      _ <- nodeMessage(rt, "n-s", "持久化探针-CHARLIE")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
      // 模拟重启：重开 store
      store2 <- FlowMapStore.open("nmsg-s5", ws.toString)
      reloaded <- store2.getNode("n-s")
    yield
      val n = reloaded.getOrElse(fail("node must survive reopen"))
      assert(n.task.exists(_.contains("== 分发器补充（NodeMessage")) && n.task.exists(_.contains("持久化探针-CHARLIE")),
        s"append persisted across reopen, got task=${n.task}")
  }

  // ── 工具面：NodeMessageTool 封装（错误码透传 + project 参数）──

  test("S6-TOOL: NodeMessageTool wraps the engine — error codes surface verbatim; ctx.projectName fallback works") {
    val ws = tempRoot / "ws-nmsg-s6"
    os.makeDir.all(ws)
    val system = ActorSystem(s"nmsg-s6-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot)
      rt <- mountProject("nmsg-s6", ws, system, res)
      // 活节点（pending）用于合法追加路径；终态节点（completed）用于终态拒绝断言。
      _ <- seedNode(rt, "n-t", "tool-t", NodeLifecycle.Pending, task = Some("t"))
      _ <- seedNode(rt, "n-t-term", "tool-t-term", NodeLifecycle.Completed, task = Some("t"))
      ctx = mkCtx(res, system, ws.toString)
      // project 参数显式传（分发器协议：所有 Node 工具调用带 project）
      eNotFound <- NodeMessageTool.call(
        Json.obj("project" -> "nmsg-s6".asJson, "nodeId" -> "n-missing".asJson, "message" -> "x".asJson).asObject.get, ctx)
        .map(_.left.map(_.message))
      eEmpty <- NodeMessageTool.call(
        Json.obj("project" -> "nmsg-s6".asJson, "nodeId" -> "n-t".asJson, "message" -> "  ".asJson).asObject.get, ctx)
        .map(_.left.map(_.message))
      eTerminal <- NodeMessageTool.call(
        Json.obj("project" -> "nmsg-s6".asJson, "nodeId" -> "n-t-term".asJson, "message" -> "late".asJson).asObject.get, ctx)
        .map(_.left.map(_.message))
      ok <- NodeMessageTool.call(
        Json.obj("project" -> "nmsg-s6".asJson, "nodeId" -> "n-t".asJson, "message" -> "工具面追加".asJson).asObject.get, ctx)
        .map(_.left.map(_.message))
      after <- rt.store.getNode("n-t")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(eNotFound.left.exists(_.contains("NODE_NOT_FOUND")), s"got $eNotFound")
      assert(eEmpty.left.exists(_.contains("NODE_MESSAGE_EMPTY")), s"got $eEmpty")
      assert(eTerminal.left.exists(_.contains("NODE_TERMINAL_NO_MESSAGE")), s"got $eTerminal")
      assert(ok.isRight, s"valid call succeeds, got $ok")
      assert(after.flatMap(_.task).exists(_.contains("工具面追加")), "tool-path append landed")
  }

  // ── ③→buildInput：追加段随任务进入节点输入（读回路径）────

  test("S3-READBACK: buildInput carries the appended section (node start reads it as part of the task)") {
    val ws = tempRoot / "ws-nmsg-s3r"
    os.makeDir.all(ws)
    val system = ActorSystem(s"nmsg-s3r-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot)
      rt <- mountProject("nmsg-s3r", ws, system, res)
      _ <- seedNode(rt, "n-q", "readback-q", NodeLifecycle.Pending, task = Some("base task"))
      _ <- nodeMessage(rt, "n-q", "读回探针-DELTA")
      q <- rt.store.getNode("n-q").map(_.getOrElse(fail("n-q must exist")))
      // 链透传批 P2：buildInput 增 chain 参数（spawn 时刻链快照）——本 spec 节点
      // 无链（无 seed 边）→ None = 无链上下文块（不影响本测断言面）。
      input <- rt.engine.buildInput(q, None)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(input.contains("base task"), "own task present in input")
      assert(input.contains("== 分发器补充（NodeMessage") && input.contains("读回探针-DELTA"),
        "appended section reaches the node input (ruling ③: read as part of the task at start)")
  }

end NodeMessageSpec
