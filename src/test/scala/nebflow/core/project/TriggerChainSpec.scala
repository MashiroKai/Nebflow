package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.syntax.all.*
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
 * 触发链修复验收（trigger-chain-fix 批，根因报告 §五 锚点 + §6.1/§6.2/§6.4 落地回归）：
 *
 * - T-A 案例 A fork 并行化：1 慢上游 + 3 deps 依赖者——三者 startedAt 全部早于任一
 *   依赖者完成（串行基线=逐一等于前序完成时刻，d3.startedAt 必然晚于 d1.completedAt）；
 *   官方锚点 startedAt 差 <5s 一并断言。
 * - T-B 案例 B 孤儿 barrier 自愈：U + C1(in=U，先建) + C2(in=U，后建夺走 U.out)——
 *   U 完成后 C2 即时获投递（回归红线），C1 孤儿悬挂（现状=永不）；settleRunnableSweep
 *   后 C1.deliveredTo 含 U 且 startedAt 非空（≤35s 锚点的引擎级直接调用形态），
 *   deliveredTo 去重幂等（重复回扫不重复记账），settle-sweep 事件留痕。
 * - T-C CAS 翻转守卫：in+deps 混合触发同一节点（deliverOut 与 settleDeps 竞发）——
 *   恰好一次会话（LLM 输入计数=1），败方安静退出不双 spawn。
 * - T-D mount 僵尸对账：持久化 running 且无在飞 fiber 的节点，挂载即收殓
 *   （cancelled 无 TTL + reaped 审计；2026-09-07 裁定：cancelled 不静默消失）。
 */
class TriggerChainSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-trigger-chain"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "test-agent")
  os.write.over(
    tempRoot / "agents" / "test-agent" / "agent.json",
    """{"name":"test-agent","description":"trigger chain regression agent","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "test-agent" / "system.md", "# test-agent\n")
  // 2026-09-05 agent 退役：新建节点执行统一 general——fixture 侧补 general agent
  os.makeDir.all(tempRoot / "agents" / "general")
  os.write.over(tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"general executor","tools":[],"category":"standalone"}""")
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  /** 按输入文本分派回复与延迟的捕获 LLM：inputs 记录每次请求 user 文本。 */
  private class DispatchLlm(
      replyOf: String => String = _ => "ok",
      delayOf: String => FiniteDuration = _ => 0.millis
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
      sessionId = Some("tc-sid"),
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
    waitUntil(20.seconds) {
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

  // ── T-A 案例 A：触发链 fork 并行化 ─────────────────────────

  test("T-A fork parallelism: 3 deps dependents all START before the first one FINISHES (serial chain = each starts at previous completion)") {
    val ws = tempRoot / "ws-ta"
    os.makeDir.all(ws)
    val system = ActorSystem(s"tc-ta-${scala.util.Random.nextInt(100000)}")
    // 上游慢 1.2s（建立 running 窗口），依赖者各慢 600ms——串行基线下 d3.startedAt
    // = d2.completedAt ≥ d1.completedAt + 600ms；fork 后三者 startedAt 几乎同时。
    val llm = DispatchLlm(
      replyOf = t => if t.contains("slow-up-ta") then "RESULT-OF-U" else "ok",
      delayOf = t => if t.contains("slow-up-ta") then 1200.millis else 600.millis
    )
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("tc-ta", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- nodeEdit(nodeInput("tc-ta", "slow-up", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("slow-up-ta"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- waitStatus(rt, "slow-up", Set(NodeLifecycle.Running))
      upId <- idOf(rt, "slow-up")
      // 三个 deps 依赖者（闸门拦下，pending 等上游完成信号）
      _ <- List("d1", "d2", "d3").traverse_(dn =>
        nodeEdit(nodeInput("tc-ta", dn, "description" -> Json.fromString("test node purpose"),
          "task" -> Json.fromString(s"dep-work-$dn"), "deps" -> Json.fromString(upId),
          "out" -> Json.fromString("Nebula")), ctx))
      _ <- List("d1", "d2", "d3").traverse_(dn => waitStatus(rt, dn, Set(NodeLifecycle.Pending, NodeLifecycle.Wiring)))
      _ <- waitStatus(rt, "slow-up", Set(NodeLifecycle.Completed))
      _ <- List("d1", "d2", "d3").traverse_(dn => waitStatus(rt, dn, Set(NodeLifecycle.Completed)))
      snaps <- rt.store.snapshot.map(_.nodes)
      d1 = snaps.values.find(_.name == "d1").getOrElse(fail("d1 must exist"))
      d2 = snaps.values.find(_.name == "d2").getOrElse(fail("d2 must exist"))
      d3 = snaps.values.find(_.name == "d3").getOrElse(fail("d3 must exist"))
      starts = List(d1, d2, d3).flatMap(_.startedAt)
      spread = if starts.isEmpty then Long.MaxValue else starts.max - starts.min
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // 官方锚点（报告 §五）：三依赖者 startedAt 差 <5s
      assert(starts.size == 3, s"all three dependents must have startedAt, got $starts")
      assert(spread < 5000L, s"startedAt spread must be <5s (anchor), got ${spread}ms")
      // 机制判据（串行/并行的确定性分界）：任一依赖者完成之前，三者已全部启动。
      // 串行基线（fork 化前）d3.startedAt ≥ d2.startedAt + 600ms > d1.completedAt，
      // 本断言必红；fork 后 d3.startedAt ≈ d1.startedAt << d1.completedAt。
      val lastStart = starts.max
      val firstFinish = List(d1, d2, d3).flatMap(_.completedAt).min
      assert(lastStart < firstFinish,
        s"all dependents must START before the first one FINISHES (parallel fan-out); lastStart=$lastStart firstFinish=$firstFinish")
  }

  // ── T-B 案例 B：孤儿 barrier 自愈（settleSweep）──────────────

  test("T-B orphan barrier heal: C1 (first in declarer, out stolen by C2) gets redelivery+start from settleRunnableSweep; deliveredTo dedup idempotent; settle-sweep event logged") {
    val ws = tempRoot / "ws-tb"
    os.makeDir.all(ws)
    val system = ActorSystem(s"tc-tb-${scala.util.Random.nextInt(100000)}")
    val llm = DispatchLlm(replyOf = t => if t.contains("up-tb") then "RESULT-OF-U" else "ok",
      delayOf = t => if t.contains("up-tb") then 800.millis else 0.millis)
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("tc-tb", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- nodeEdit(nodeInput("tc-tb", "up", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("up-tb"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- waitStatus(rt, "up", Set(NodeLifecycle.Running))
      upId <- idOf(rt, "up")
      // C1 先建（拿走 U.out），C2 后建（夺走 U.out）——报告 §2.2 案例 B 拓扑
      _ <- nodeEdit(nodeInput("tc-tb", "c1", "description" -> Json.fromString("test node purpose"),
        "in" -> Json.fromString(upId), "out" -> Json.fromString("Nebula")), ctx)
      _ <- nodeEdit(nodeInput("tc-tb", "c2", "description" -> Json.fromString("test node purpose"),
        "in" -> Json.fromString(upId), "out" -> Json.fromString("Nebula")), ctx)
      c1Id <- idOf(rt, "c1")
      c2Id <- idOf(rt, "c2")
      // U 完成：deliverOut 只到 out 持有者 C2（回归红线：C2 即时获投递并启动）；
      // C1 孤儿悬挂（现状=永不）
      _ <- waitStatus(rt, "up", Set(NodeLifecycle.Completed))
      _ <- waitStatus(rt, "c2", Set(NodeLifecycle.Completed))
      pre <- rt.store.snapshot.map(_.nodes)
      c1Pre = pre.get(c1Id).getOrElse(fail("c1 must exist"))
      c2Pre = pre.get(c2Id).getOrElse(fail("c2 must exist"))
      // 回扫自愈（引擎级直接调用 = TtlTick 30s 挂点的等价形态，≤35s 锚点收紧为即时）
      _ <- rt.engine.settleRunnableSweep()
      _ <- waitStatus(rt, "c1", Set(NodeLifecycle.Completed))
      _ <- rt.engine.settleRunnableSweep() // 第二轮回扫：去重幂等回归面
      post <- rt.store.snapshot.map(_.nodes)
      c1 = post.get(c1Id).getOrElse(fail("c1 must exist"))
      c2 = post.get(c2Id).getOrElse(fail("c2 must exist"))
      audit <- readAuditTypes(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // 现状回归面：out 持有者 C2 即时获投递（deliverOut 语义不变）
      assert(c2Pre.deliveredTo.contains(upId), "C2 (out holder) must receive U's delivery at completion (red line: unchanged)")
      assertEquals(c2Pre.status, NodeLifecycle.Completed, "C2 must complete without sweep (event-driven path intact)")
      // 孤儿 C1：U 完成时未获投递（缺陷现场），回扫后自愈
      assert(!c1Pre.deliveredTo.contains(upId),
        "precondition: C1 must be orphaned after U completes (out stolen by C2)")
      assert(c1.deliveredTo.contains(upId), s"C1.deliveredTo must contain U after sweep, got ${c1.deliveredTo}")
      assert(c1.deliveredTo.count(_ == upId) == 1,
        s"deliveredTo dedup must stay idempotent across repeated sweeps, got ${c1.deliveredTo}")
      assert(c1.startedAt.isDefined, "C1.startedAt must be non-empty after sweep (anchor)")
      assertEquals(c1.status, NodeLifecycle.Completed, "C1 must complete after orphan barrier heal")
      assert(c1Pre.startedAt.isEmpty, "C1 must NOT have started before the sweep (starvation reproduced)")
      // 留痕纪律：settle-sweep 事件落账（禁止静默自愈）
      assert(audit.exists((t, id) => t == "settle-sweep" && id == c1Id),
        s"settle-sweep audit event must be logged for C1, got: $audit")
  }

  // ── T-C CAS 翻转守卫：同节点竞发单 spawn ─────────────────────

  test("T-C CAS flip guard: in+deps mixed triggers race startNode on the same node — exactly one session spawns (loser exits quietly)") {
    val ws = tempRoot / "ws-tc"
    os.makeDir.all(ws)
    val system = ActorSystem(s"tc-tc-${scala.util.Random.nextInt(100000)}")
    val llm = DispatchLlm(replyOf = t => if t.contains("up-tc") then "RESULT-OF-U" else "ok",
      delayOf = t => if t.contains("up-tc") then 800.millis else 0.millis)
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("tc-tc", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- nodeEdit(nodeInput("tc-tc", "up", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("up-tc"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- waitStatus(rt, "up", Set(NodeLifecycle.Running))
      upId <- idOf(rt, "up")
      // M 既 in 又 deps 同一上游：U 完成 → deliverOut 与 settleDeps 双路 fork 竞发
      // startNode(M)——翻转 CAS 守卫裁决唯一赢家
      _ <- nodeEdit(nodeInput("tc-tc", "mixed", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("mixed-trigger"), "in" -> Json.fromString(upId),
        "deps" -> Json.fromString(upId), "out" -> Json.fromString("Nebula")), ctx)
      _ <- waitStatus(rt, "up", Set(NodeLifecycle.Completed))
      _ <- waitStatus(rt, "mixed", Set(NodeLifecycle.Completed))
      spawns <- llm.inputs.get.map(_.count(_.contains("mixed-trigger")))
      mixedId <- idOf(rt, "mixed")
      mixed <- nodeById(rt, mixedId).map(_.getOrElse(fail("mixed must exist")))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(spawns, 1,
        s"exactly one session must spawn under concurrent triggers (CAS flip guard), got $spawns")
      assertEquals(mixed.status, NodeLifecycle.Completed, "M must complete normally (winner owns lifecycle)")
      assert(mixed.startedAt.isDefined, "winner must carry startedAt")
  }

  // ── T-D mount 僵尸 running 对账 ─────────────────────────────

  test("T-D mount zombie reconciliation: persisted running node with no live fiber is reaped (cancelled, no TTL + reaped audit — 2026-09-07 ruling) at mount") {
    val ws = tempRoot / "ws-td"
    os.makeDir.all((ws / ".nebflow"))
    val system = ActorSystem(s"tc-td-${scala.util.Random.nextInt(100000)}")
    val llm = DispatchLlm()
    val now = System.currentTimeMillis()
    // 预置持久化 flow-map.json：一个 status=running 的僵尸节点（重启窗口遗产，
    // 本进程无在飞 fiber）
    val zombie = NodeDef(id = "n-zombie", name = "zombie", agent = "test-agent",
      status = NodeLifecycle.Running, createdAt = now, startedAt = Some(now))
    val stateJson = Json.obj(
      "v" -> Json.fromInt(1),
      "project" -> "tc-td".asJson,
      "updatedAt" -> now.asJson,
      "nodes" -> Json.obj("n-zombie" -> zombie.asJson))
    os.write.over(ws / ".nebflow" / "flow-map.json", stateJson.noSpaces)
    for
      res <- mkResources(system, tempRoot, llm.handle)
      pd = ProjectDef(name = "tc-td", workspace = ws.toString, agentFile = (ws / "AGENTS.md").toString, createdAt = now)
      // ProjectRuntimeRegistry.mount（含僵尸对账）——非 mountProject 直挂路径
      rt <- ProjectRuntimeRegistry.mount(pd, system, res, None, "nebula-root")
      _ <- waitUntil(10.seconds) {
        rt.store.getNode("n-zombie").map(_.exists(_.status == NodeLifecycle.Cancelled))
      }
      z <- rt.store.getNode("n-zombie").map(_.getOrElse(fail("zombie must exist")))
      audit <- readAuditTypes(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(z.status, NodeLifecycle.Cancelled, "zombie running node must be reaped to cancelled at mount")
      // 2026-09-07 作者裁定（df846488）：failed/cancelled 无 TTL 强制清——死亡现场
      // 保留主图待上层裁决，不静默消失。cancelNode 链 ttlExpireAt 恒 None。
      assert(z.ttlExpireAt.isEmpty,
        "reaped zombie must NOT carry display TTL (2026-09-07 ruling: cancelled retained on map, no forced cleanup)")
      assert(audit.exists((t, id) => t == "reaped" && id == "n-zombie"),
        s"reaped audit event must be logged, got: $audit")
  }

end TriggerChainSpec
