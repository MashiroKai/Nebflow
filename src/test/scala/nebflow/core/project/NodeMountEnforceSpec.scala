package nebflow.core.project

import cats.effect.{IO, Ref}
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
 * 挂载即生效——空挂封堵 + 挂载停滞兜底闸（mount-enforce 批 20260905，作者裁定：
 * 「节点不允许空挂载——in/out 必须有效填写，节点一旦挂载必须一定生效（立即运行、
 * 或被真实上游正确闸住），不存在『挂着但永不启动』的合法形态」；背景 = 实证
 * n-371cf932（merge=true、in=[]）空挂 pending 10+ 分钟永不生效）。
 *
 * - M① 零上游 merge 创建拒绝：merge=true 必须 in ≥1（NODE_MERGE_REQUIRES_UPSTREAM，
 *   报错携带语义说明——合并节点靠上游 completed 投递清 barrier，零上游=永远等不到）。
 * - M② in 引用存在性钉死（既有 ensureNodeExists 校验，此前无测试钉死）。
 * - M③ 入口节点创建即 running 钉死（既有行为，锚点②显式断言 Running 态可见）。
 * - M④ 合法新顺序：上游先建、合并节点 in=<id> 后建——merge 落库 + payload 条件
 *   字段（"merge": true 仅 merge 节点带；loop 前瞻字段不产出）+ barrier 触发照常。
 * - M⑤ 停滞闸：入口节点可触发点（createdAt）后 60s 仍未触发 → mount-stalled 事件
 *   落 FlowMapEventLog + settleSweep 回扫补触发（节点跑到 completed）。
 * - M⑥ 不误伤：等待 running 上游的 pending 超 60s 不发事件（barrier 感知合法等待），
 *   上游完成后正常投递启动。
 * - M⑦ 停滞留痕（barrier 永不可清形态）：上游已 failed 的 pending 下游 → mount-stalled
 *   事件（等待原因含 failed 上游明细）+ 每停滞期单发（重复回扫不刷屏）。
 */
class NodeMountEnforceSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-mount-enforce"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  // general：统一执行 agent（createNode 固定校验并落库 "general"）
  os.makeDir.all(tempRoot / "agents" / "general")
  os.write.over(tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"general executor","tools":[],"category":"standalone"}""")
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  /** 按输入文本分派延迟的捕获 LLM。 */
  private class EchoLlm(delayOf: String => FiniteDuration = _ => 0.millis):
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
      sessionId = Some("me-sid"),
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

  private def idOf(rt: ProjectRuntime, name: String): IO[String] =
    rt.store.snapshot.map(_.nodes.values.find(_.name == name)).map {
      case Some(n) => n.id
      case None    => fail(s"node '$name' must exist")
    }

  private def waitStatus(rt: ProjectRuntime, name: String, statuses: Set[String], timeout: FiniteDuration = 20.seconds): IO[Unit] =
    waitUntil(timeout) {
      rt.store.snapshot.map(_.nodes.values.find(_.name == name)).flatMap {
        case Some(n) => IO.pure(statuses.contains(n.status))
        case None    => IO.pure(false)
      }
    }

  private def readAudit(ws: os.Path): IO[List[(String, String, String)]] =
    IO.blocking(os.read(ws / ".nebflow" / FlowMapEventLog.FileName))
      .map(_.linesIterator.toList.filter(_.trim.nonEmpty))
      .map(lines => lines.flatMap(l => jsonParse(l).toOption.map(j => (
        j.hcursor.get[String]("type").getOrElse(""),
        j.hcursor.get[String]("nodeId").getOrElse(""),
        j.hcursor.get[String]("summary").getOrElse("")))))
      .handleError(_ => Nil)

  /** 直种活动区节点（绕过 NodeEdit——停滞闸测试的「历史遗留形态」播种）。 */
  private def seedNode(rt: ProjectRuntime, n: NodeDef): IO[Unit] =
    rt.store.mutate(s => s.copy(nodes = s.nodes + (n.id -> n))).void

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  // ── M① 零上游 merge 创建拒绝（NODE_MERGE_REQUIRES_UPSTREAM）────────

  test("M1: merge=true with zero upstreams is rejected at create — semantic error, node NOT created (n-371cf932 form)") {
    val ws = tempRoot / "ws-m1"
    os.makeDir.all(ws)
    val system = ActorSystem(s"me-m1-${scala.util.Random.nextInt(100000)}")
    val llm = EchoLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("me-m1", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // 事故形态复刻：merge=true、in 缺省（=[] 等价）、task+out 在、零 in
      r1 <- nodeEdit(nodeInput("me-m1", "m0", "description" -> Json.fromString("merge empty mount"),
        "task" -> Json.fromString("landing"), "out" -> Json.fromString("Nebula"),
        "merge" -> Json.fromBoolean(true)), ctx)
      // 显式空数组 in=[] 同判（parseIn 归一化为 Nil）
      r2 <- nodeEdit(nodeInput("me-m1", "m0b", "description" -> Json.fromString("merge empty mount arr"),
        "task" -> Json.fromString("landing"), "out" -> Json.fromString("Nebula"),
        "in" -> Json.arr(), "merge" -> Json.fromBoolean(true)), ctx)
      snap <- rt.store.snapshot
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r1.isLeft, s"zero-upstream merge create must be rejected, got $r1")
      assert(r1.left.exists(_.contains("NODE_MERGE_REQUIRES_UPSTREAM")),
        s"error must carry NODE_MERGE_REQUIRES_UPSTREAM, got: $r1")
      assert(r1.left.exists(m => m.contains("in-barrier") || m.contains("in") ),
        s"error must explain the semantics (fires via in-barrier of upstream delivery), got: $r1")
      assert(r1.left.exists(m => m.contains("never fire") || m.contains("pending forever")),
        s"error must state the empty-mount consequence, got: $r1")
      assert(r2.isLeft && r2.left.exists(_.contains("NODE_MERGE_REQUIRES_UPSTREAM")),
        s"explicit in=[] must be rejected identically, got: $r2")
      assert(!snap.nodes.values.exists(n => n.name == "m0" || n.name == "m0b"),
        "rejected creates must leave NO node in the store (0 spawn)")
  }

  // ── M1b 第 6 例形态实证钉住（20260909 blocked-signal spec §0 附带发现/§1 例6）──
  // n-e8b82fd5（合并-Smoke修复批）：merge=true ∧ task ∧ in=[]，经 EMPTY_NODE_
  // CONNECTION「task or in 二选一」通道以 entry 形态直跑 → completed 伪终态在案。
  // 护栏本体 = merge 校验②（019cc53b mount-enforce 批 20260905 落地，spec 侦查
  // 误判「代码层无校验」已收敛）——本测试固化该事故形态防回退（BS-3 独立可拆，
  // 生产代码零改动）。

  test("M1b: n-e8b82fd5 incident form (merge=true + task + explicit in=[]) is rejected at create, node NOT persisted") {
    val ws = tempRoot / "ws-m1b"
    os.makeDir.all(ws)
    val system = ActorSystem(s"me-m1b-${scala.util.Random.nextInt(100000)}")
    val llm = EchoLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("me-m1b", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // 事故形态逐字复刻：merge=true、显式空数组 in=[]、task+out 在（entry 直跑形态）
      r <- nodeEdit(nodeInput("me-m1b", "merge-smoke-fix", "description" -> Json.fromString("n-e8b82fd5 form pin"),
        "task" -> Json.fromString("landing smoke fixes"), "out" -> Json.fromString("Nebula"),
        "in" -> Json.arr(), "merge" -> Json.fromBoolean(true)), ctx)
      snap <- rt.store.snapshot
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isLeft, s"n-e8b82fd5 form (merge=true, task, in=[]) must be rejected, got: $r")
      assert(r.left.exists(_.contains("NODE_MERGE_REQUIRES_UPSTREAM")),
        s"rejection must carry NODE_MERGE_REQUIRES_UPSTREAM, got: $r")
      assert(!snap.nodes.values.exists(n => n.name == "merge-smoke-fix"),
        "rejected incident form must leave NO node in the store (0 spawn)")
  }

  // ── M② in 引用存在性钉死 ───────────────────────────────

  test("M2: create with in referencing a nonexistent node is rejected (existence pin)") {
    val ws = tempRoot / "ws-m2"
    os.makeDir.all(ws)
    val system = ActorSystem(s"me-m2-${scala.util.Random.nextInt(100000)}")
    val llm = EchoLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("me-m2", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      r <- nodeEdit(nodeInput("me-m2", "barrier", "description" -> Json.fromString("dangling in"),
        "in" -> Json.fromString("n-nonexistent"), "out" -> Json.fromString("Nebula")), ctx)
      snap <- rt.store.snapshot
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isLeft, s"dangling in must be rejected, got $r")
      assert(r.left.exists(_.contains("Referenced node 'n-nonexistent' not found")),
        s"error must name the missing reference, got: $r")
      assert(!snap.nodes.values.exists(_.name == "barrier"), "rejected create must not persist the node")
  }

  // ── M③ 入口节点创建即 running（锚点②钉死）──────────────────

  test("M3: entry node (task + out, no in) is RUNNING right after create (startedAt set), then completes") {
    val ws = tempRoot / "ws-m3"
    os.makeDir.all(ws)
    val system = ActorSystem(s"me-m3-${scala.util.Random.nextInt(100000)}")
    val llm = EchoLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("me-m3", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- nodeEdit(nodeInput("me-m3", "entry", "description" -> Json.fromString("entry start pin"),
        "task" -> Json.fromString("entry-runs-at-once"), "out" -> Json.fromString("Nebula")), ctx)
      // 创建即 running（不等待任何上游；startNode 后台 fork 但立即翻转状态）。
      // Running 是瞬态：0 延迟 EchoLlm 下节点可在一次 50ms 轮询间隙内跑到 Completed，
      // 原「等 Running」会偶发超时（节点实际已 completed）。治本=观测条件改为接受
      // Running|Completed——入口节点语义（不等待上游立即启动）由 final 断言
      // startedAt + Completed 覆盖，Running 只是手段，不作为可失败观察点。
      _ <- waitStatus(rt, "entry", Set(NodeLifecycle.Running, NodeLifecycle.Completed))
      _ <- waitStatus(rt, "entry", Set(NodeLifecycle.Completed))
      n <- idOf(rt, "entry").flatMap(id => rt.store.getNode(id)).map(_.getOrElse(fail("entry must exist")))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(n.startedAt.isDefined, "entry node must carry startedAt (mount took effect immediately)")
      assertEquals(n.status, NodeLifecycle.Completed, "entry node must run to completion")
  }

  // ── M④ 合法新顺序 + payload merge 条件字段 ──────────────────

  test("M4: legal order (upstream first, merge with in=<id>) — merge persists, payload carries merge:true only for merge nodes, no loop key, barrier triggers") {
    val ws = tempRoot / "ws-m4"
    os.makeDir.all(ws)
    val system = ActorSystem(s"me-m4-${scala.util.Random.nextInt(100000)}")
    // 上游慢 1.2s：合并节点创建时上游仍 running（barrier 等待语义可见）
    val llm = EchoLlm(delayOf = t => if t.contains("slow-up") then 1200.millis else 0.millis)
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("me-m4", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- nodeEdit(nodeInput("me-m4", "slow-up", "description" -> Json.fromString("m4 upstream"),
        "task" -> Json.fromString("slow-up-work"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- waitStatus(rt, "slow-up", Set(NodeLifecycle.Running))
      upId <- idOf(rt, "slow-up")
      // 新合法顺序：上游已存在 → 合并节点 in=<upId> 创建
      r <- nodeEdit(nodeInput("me-m4", "merge-m4", "description" -> Json.fromString("m4 landing sink"),
        "task" -> Json.fromString("landing"), "in" -> Json.fromString(upId),
        "out" -> Json.fromString("Nebula"), "merge" -> Json.fromBoolean(true)), ctx)
      _ <- waitStatus(rt, "slow-up", Set(NodeLifecycle.Completed))
      _ <- waitStatus(rt, "merge-m4", Set(NodeLifecycle.Completed))
      merge <- idOf(rt, "merge-m4").flatMap(id => rt.store.getNode(id)).map(_.getOrElse(fail("merge must exist")))
      plain <- idOf(rt, "slow-up").flatMap(id => rt.store.getNode(id)).map(_.getOrElse(fail("up must exist")))
      now = System.currentTimeMillis()
      mergeJson = NodePayload.buildNodeJson(merge, now)
      plainJson = NodePayload.buildNodeJson(plain, now)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isRight, s"merge create with existing upstream must succeed, got $r")
      assertEquals(merge.merge, true, "merge flag must persist")
      assertEquals(mergeJson.hcursor.get[Boolean]("merge").toOption, Some(true),
        "payload must carry merge:true for merge nodes (REST/WS/NodeList single serialization point)")
      assert(!plainJson.asObject.exists(_.keys.exists(_ == "merge")),
        "non-merge node payload must NOT carry the merge key (conditional serialization, zero drift)")
      assert(!mergeJson.asObject.exists(_.keys.exists(_ == "loop")) && !plainJson.asObject.exists(_.keys.exists(_ == "loop")),
        "loop forward-defense field must be absent while the engine has no loop node concept")
      assertEquals(merge.status, NodeLifecycle.Completed,
        "merge node must trigger after upstream completion (in-barrier semantics intact under the new order)")
  }

  // ── M⑤ 停滞闸：入口停滞 → mount-stalled 事件 + 回扫补触发 ────

  test("M5: entry node 60s past its triggerable point (createdAt) and still pending → mount-stalled event + settle sweep takeover starts it") {
    val ws = tempRoot / "ws-m5"
    os.makeDir.all(ws)
    val system = ActorSystem(s"me-m5-${scala.util.Random.nextInt(100000)}")
    val llm = EchoLlm()
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("me-m5", ws, system, res)
      // 历史遗留形态播种：入口 pending、createdAt 回拨 61s（= fork 启动从未生效）
      _ <- seedNode(rt, NodeDef(
        id = "n-stalled-entry", name = "stalled-entry", agent = "general",
        task = Some("stalled-entry-work"), status = NodeLifecycle.Pending,
        createdAt = now - 61_000L))
      _ <- rt.engine.settleRunnableSweep()
      // 补触发：回扫 fork startNode → 节点跑完
      _ <- waitStatus(rt, "stalled-entry", Set(NodeLifecycle.Completed))
      audit <- readAudit(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      val events = audit.filter((t, id, _) => t == "mount-stalled" && id == "n-stalled-entry")
      assert(events.nonEmpty,
        s"mount-stalled event must be logged for the stalled entry node, got: ${audit.map((t, id, _) => (t, id))}")
      val (_, _, summary) = events.head
      assert(summary.contains("triggerable"), s"summary must state the triggerable-point staleness, got: $summary")
      assert(summary.contains("entry node"), s"summary must identify the entry (no upstreams) form, got: $summary")
  }

  // ── M⑥ 不误伤：等待 running 上游的 pending 超 60s 不判停滞 ────

  test("M6: pending waiting on a RUNNING upstream for >60s must NOT be flagged mount-stalled (barrier-aware legal wait); normal delivery resumes after upstream completes") {
    val ws = tempRoot / "ws-m6"
    os.makeDir.all(ws)
    val system = ActorSystem(s"me-m6-${scala.util.Random.nextInt(100000)}")
    // 上游慢 2.5s：下游播种（createdAt 回拨 10min）后回扫时上游仍 running
    val llm = EchoLlm(delayOf = t => if t.contains("slow-up-m6") then 2500.millis else 0.millis)
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("me-m6", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- nodeEdit(nodeInput("me-m6", "slow-up", "description" -> Json.fromString("m6 upstream"),
        "task" -> Json.fromString("slow-up-m6"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- waitStatus(rt, "slow-up", Set(NodeLifecycle.Running))
      upId <- idOf(rt, "slow-up")
      // 下游播种：pending、极老（>60s）、in=[running 上游]（含 out 改接，模拟 in 声明接线）
      _ <- seedNode(rt, NodeDef(
        id = "n-waiter", name = "waiter", agent = "general",
        in = List(upId), status = NodeLifecycle.Wiring,
        createdAt = now - 600_000L))
      _ <- rt.store.mutate(s => s.copy(nodes = s.nodes.updated(upId, s.nodes(upId).copy(out = List(OutEdge("n-waiter"))))))
      _ <- rt.engine.settleRunnableSweep()
      postSweep <- rt.store.getNode("n-waiter").map(_.getOrElse(fail("waiter must exist")))
      auditAfterSweep <- readAudit(ws)
      // 上游完成 → 投递 → 正常启动（合法等待结束后链路照常）
      _ <- waitStatus(rt, "slow-up", Set(NodeLifecycle.Completed))
      _ <- waitStatus(rt, "waiter", Set(NodeLifecycle.Completed))
      auditFinal <- readAudit(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(postSweep.status, NodeLifecycle.Wiring,
        "waiter must stay un-started right after the sweep (running upstream = legal wait, not stalled)")
      assert(!auditAfterSweep.exists((t, id, _) => t == "mount-stalled" && id == "n-waiter"),
        s"waiting on a running upstream must NOT emit mount-stalled, got: ${auditAfterSweep.map((t, id, _) => (t, id))}")
      assert(!auditFinal.exists((t, id, _) => t == "mount-stalled" && id == "n-waiter"),
        "no mount-stalled may appear for the legal waiter even after completion")
      assertEquals(postSweep.createdAt, now - 600_000L, "precondition: waiter is far past 60s")
  }

  // ── M⑦ 停滞留痕（barrier 永不可清形态）+ 每停滞期单发 ────────

  test("M7: pending downstream whose upstream already FAILED (barrier unclearable) → mount-stalled naming the failed upstream; single emission per stall episode") {
    val ws = tempRoot / "ws-m7"
    os.makeDir.all(ws)
    val system = ActorSystem(s"me-m7-${scala.util.Random.nextInt(100000)}")
    val llm = EchoLlm()
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("me-m7", ws, system, res)
      // 播种：failed 上游（终态、completedAt 老旧）+ pending 下游（in=[failed 上游]）
      _ <- seedNode(rt, NodeDef(
        id = "n-failed-up", name = "failed-up", agent = "general",
        status = NodeLifecycle.Failed, result = Some("boom"),
        createdAt = now - 300_000L, completedAt = Some(now - 290_000L)))
      _ <- seedNode(rt, NodeDef(
        id = "n-stuck", name = "stuck", agent = "general",
        in = List("n-failed-up"), status = NodeLifecycle.Pending,
        createdAt = now - 280_000L))
      _ <- rt.engine.settleRunnableSweep()
      _ <- IO.sleep(100.millis)
      _ <- rt.engine.settleRunnableSweep() // 第二轮回扫：单发纪律（不刷屏）
      stuck <- rt.store.getNode("n-stuck").map(_.getOrElse(fail("stuck must exist")))
      audit <- readAudit(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      val events = audit.filter((t, id, _) => t == "mount-stalled" && id == "n-stuck")
      assertEquals(events.size, 1,
        s"mount-stalled must fire exactly once per stall episode (two sweeps), got ${events.size}: ${audit.map((t, id, _) => (t, id))}")
      val (_, _, summary) = events.head
      assert(summary.contains("failed"), s"summary must name the upstream terminal state, got: $summary")
      assert(summary.contains("failed-up"), s"summary must identify the blocking upstream, got: $summary")
      assertEquals(stuck.status, NodeLifecycle.Pending,
        "stuck node must NOT have been started (failed upstream barrier cannot clear; event is the visibility carrier)")
      assert(stuck.startedAt.isEmpty, "stuck node must have no session")
  }

end NodeMountEnforceSpec
