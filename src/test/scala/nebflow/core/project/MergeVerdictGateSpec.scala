package nebflow.core.project

import cats.effect.{IO, Ref}
import fs2.Stream
import io.circe.Json
import io.circe.parser.parse as jsonParse
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentLibrary, SharedResources}
import nebflow.core.PathUtil
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, ToolContext}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * merge 触发的 **verdict 闸**（merge-verdict-gate 批 2026-09-12，作者裁定：把「先验后合」
 * 从节点自觉变成机制保证）回归族。
 *
 * 判据：**merge 节点的 `in` 上游中含 `role=verifier` 的节点时，该 verifier 的当下
 * `lastVerdict` 必须为 `pass`**，否则本 merge 不可启动、原地保持 pending（读当下值，
 * 非一次性历史闩；verifier 重跑出 `pass` 后自动放行）。零副作用——不改上游
 * status/lastVerdict、不写 blockedFeedback、不改 deliveredTo 记账。
 *
 * 出处实证（本批场景蓝本）：`perm-global-merge`(n-9e9c385d) —— merge=true、
 * in=[n-5c5d5801, n-6f86d028]（后者 role=verifier 且已 `node_report(fail)`），
 * deliveredTo 两轨齐（barrier 已清），仍被 `settleRunnableSweep` 拉起并启动。
 *
 * 用例面（覆盖要求 1/2/3 逐条）：
 *  - V1 case (a)：verifier 判 fail ⇒ merge **不得**被 settle-sweep 拉起（含 sweep 第 1 步
 *    孤儿 barrier 自愈已发生的证据链：barrier 记账齐 + 节点纹丝不动）+ mount-stalled 文案点名闸因；
 *  - V2 case (b) path a：verifier 判 pass ⇒ barrier 结算（deliverOutTo → settleTo）正常触发；
 *  - V3 case (b) path b：verifier 判 pass ⇒ 资格回扫正常拉起（V1 的对照组）；
 *  - V4 读当下值：held 之后把 verifier 翻成 pass ⇒ 下一轮回扫放行（无一次性闩）；
 *  - V5 case (c)：**上游无 verifier 的 merge 行为逐字不变**（task 上游正常触发）；
 *  - V6 case (c)：闸是 **merge-only**——非 merge 下游遇 fail verifier 照旧启动；
 *  - V7 case (d)：verifier **未申报** lastVerdict ⇒ 保守不放行；
 *  - V8 单权威：绕开 settleTo/sweep 的直投路径（settleDeps / redeliverInAndStart 走
 *    `startNode`）同样被挡（barrier 记账人为置齐后直接调 startNode）。
 */
class MergeVerdictGateSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-merge-verdict-gate"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  // general：统一执行 agent（createNode 固定校验并落库 "general"）
  os.makeDir.all(tempRoot / "agents" / "general")
  os.write.over(tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"merge verdict gate spec agent","tools":[],"category":"standalone"}""")
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  /** 立即应答的捕获 LLM（节点会话跑满真实栈：spawn → 首轮 → completed）。 */
  private class EchoLlm:
    val inputs: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)
    def handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(
          req: LlmRequest,
          onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
      ): Stream[IO, StreamChunk] =
        Stream.eval(inputs.update(_ :+ req.messages.map(_.textContent).mkString("\n"))) >>
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

  private def mountProject(
    name: String,
    ws: os.Path,
    system: ActorSystem,
    res: SharedResources
  ): IO[ProjectRuntime] =
    for
      store <- FlowMapStore.open(name, ws.toString)
      engine = new NodeEngine(
        store, system, res,
        wsSendFn = (_: Json) => IO.unit,
        workspace = ws.toString,
        rootSessionId = "nebula-root",
        projectName = name,
        emitEvent = (_, _, _) => IO.unit,
        // noderpt 批 A 段：本 fixture 主题非 node_report 语义 ⇒ 显式关腿 2（对齐
        // NodeMountEnforceSpec / MergeDesignGapSpec 同款装配）。
        reportGateHold = Some(false)
      )
      pd = ProjectDef(name = name, workspace = ws.toString, agentFile = (ws / "AGENTS.md").toString,
        createdAt = System.currentTimeMillis())
      rt = ProjectRuntime(pd, store, engine, system, res, None)
      _ <- ProjectRuntimeRegistry.register(rt)
    yield rt

  /** 直种活动区节点（绕过 NodeEdit——把状态/字段摆到判据上）。 */
  private def seed(rt: ProjectRuntime, nodes: NodeDef*): IO[Unit] =
    rt.store.mutate(s => s.copy(nodes = s.nodes ++ nodes.map(n => n.id -> n).toMap)).void

  private def node(rt: ProjectRuntime, id: String): IO[NodeDef] =
    rt.store.getNode(id).map(_.getOrElse(fail(s"node '$id' must exist")))

  private def readAudit(ws: os.Path): IO[List[(String, String, String)]] =
    IO.blocking(os.read(ws / ".nebflow" / FlowMapEventLog.FileName))
      .map(_.linesIterator.toList.filter(_.trim.nonEmpty))
      .map(lines => lines.flatMap(l => jsonParse(l).toOption.map(j => (
        j.hcursor.get[String]("type").getOrElse(""),
        j.hcursor.get[String]("nodeId").getOrElse(""),
        j.hcursor.get[String]("summary").getOrElse("")))))
      .handleError(_ => Nil)

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 50.millis)(cond: IO[Boolean]): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError("waitUntil: condition not met in time"))
          else IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  private def waitStatus(rt: ProjectRuntime, id: String, statuses: Set[String],
      timeout: FiniteDuration = 30.seconds): IO[Unit] =
    waitUntil(timeout) {
      rt.store.getNode(id).map(_.exists(n => statuses.contains(n.status)))
    }

  /** 「保持未启动」的负向断言窗口：给 fork 出的启动腿足够时间跑完（生产同款亚秒级）。 */
  private def settleWindow: IO[Unit] = IO.sleep(600.millis)

  // ── 节点构造（判据两侧的形态蓝本）─────────────────────────────

  /** verifier 上游（role=verifier，verdict 由 lastVerdict 携带；completed = verdict 面终态）。 */
  private def verifierNode(id: String, name: String, verdict: Option[String],
      out: List[OutEdge], now: Long): NodeDef =
    NodeDef(id = id, name = name, agent = "general", task = Some(s"$name verify task"),
      status = NodeLifecycle.Completed, role = NodeRoles.Verifier, lastVerdict = verdict,
      result = Some(s"verdict report for $name"), out = out,
      createdAt = now - 400_000L, completedAt = Some(now - 200_000L))

  /** merge 节点（merge=true；in = 上游清单；pending = 未触发态）。 */
  private def mergeNode(id: String, name: String, in: List[String], now: Long): NodeDef =
    NodeDef(id = id, name = name, agent = "general", merge = true, task = Some(s"$name landing task"),
      status = NodeLifecycle.Pending, in = in, out = List(OutEdge.nebula),
      createdAt = now - 100_000L)

  /** task 上游（无 verifier 的对照组）。 */
  private def taskUpstream(id: String, name: String, out: List[OutEdge], now: Long): NodeDef =
    NodeDef(id = id, name = name, agent = "general", task = Some(s"$name task"),
      status = NodeLifecycle.Completed, result = Some(s"$name artifact"),
      out = out, createdAt = now - 400_000L, completedAt = Some(now - 200_000L))

  // ── V1 case (a)：verifier fail ⇒ settle sweep 不得拉起 merge（出处场景）──

  test("V1 (case a, incident form n-9e9c385d): the sweep heals the orphan barrier but must NOT start a merge whose in-upstream verifier judged fail — node stays pending, zero side effects, mount-stalled names the gate") {
    val ws = tempRoot / "ws-v1"; os.makeDir.all(ws)
    val system = ActorSystem(s"mvg-v1-${scala.util.Random.nextInt(100000)}")
    val llm = new EchoLlm
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("mvg-v1", ws, system, res)
      _ <- seed(rt,
        verifierNode("n-ver", "ver", Some("fail"), List(OutEdge("n-merge")), now),
        mergeNode("n-merge", "merge-v1", List("n-ver"), now))
      _ <- rt.engine.settleRunnableSweep()
      _ <- settleWindow
      m1 <- node(rt, "n-merge")
      v1 <- node(rt, "n-ver")
      audit1 <- readAudit(ws)
      // 幂等重放：连续两轮回扫仍不得放行（闸非一次性、非竞速）
      _ <- rt.engine.settleRunnableSweep() *> IO.sleep(200.millis) *> rt.engine.settleRunnableSweep()
      _ <- settleWindow
      m2 <- node(rt, "n-merge")
      v2 <- node(rt, "n-ver")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // 前提证据链：sweep 第 1 步孤儿 barrier 自愈**确实跑了**（deliveredTo 记全）
      assert(m1.deliveredTo.contains("n-ver"),
        s"precondition: the sweep must have healed the orphan barrier (deliveredTo should hold n-ver), got ${m1.deliveredTo}")
      assert(audit1.exists((t, id, _) => t == "settle-sweep" && id == "n-merge"),
        s"precondition: the sweep must have processed n-merge (settle-sweep audit), got ${audit1.map((t, id, _) => (t, id))}")
      // 判据本体：barrier 已清而 merge 不启动
      assertEquals(m1.status, NodeLifecycle.Pending,
        "merge with a fail-verdict upstream must stay pending (verdict gate)")
      assert(m1.startedAt.isEmpty, "no session may be spawned for a gated merge")
      assertEquals(m1.result, None, "the gate must not fabricate any result")
      assertEquals(m1.blockedFeedback, None, "the gate must NOT fake a blocked state")
      // 上游零改写
      assertEquals(v1.status, NodeLifecycle.Completed, "upstream verifier status must not be rewritten")
      assertEquals(v1.lastVerdict, Some("fail"), "upstream lastVerdict must not be rewritten")
      assert(!audit1.exists((t, id, _) => id == "n-merge" && (t == "blocked" || t == "merge-blocked")),
        s"no blocked/merge-blocked event may be emitted for a verdict-gated merge, got ${audit1.filter((_, id, _) => id == "n-merge")}")
      // 幂等：多轮回扫仍持有
      assertEquals(m2.status, NodeLifecycle.Pending, "repeated sweeps must keep holding the merge (idempotent)")
      assertEquals(v2.lastVerdict, Some("fail"), "repeated sweeps must not touch the upstream verdict")
      // 停等可见性：既有 mount-stalled 事件（60s 档、单发）点名 verdict 闸
      val stalls = audit1.filter((t, id, _) => t == "mount-stalled" && id == "n-merge")
      assertEquals(stalls.size, 1, s"exactly one mount-stalled line expected for the held merge, got $stalls")
      val stall = stalls.head._3
      assert(stall.contains("verdict gate held"), s"the stall reason must name the verdict gate, got: $stall")
      assert(stall.contains("n-ver") && stall.contains("lastVerdict=fail"),
        s"the stall reason must name the holding verifier + its verdict, got: $stall")
  }

  // ── V2 case (b) path a：barrier 结算（deliverOutTo → settleTo）────────────────

  test("V2 (case b, path a: barrier settle): a verifier whose current lastVerdict is pass lets the merge trigger through deliverOutTo's barrier settlement") {
    val ws = tempRoot / "ws-v2"; os.makeDir.all(ws)
    val system = ActorSystem(s"mvg-v2-${scala.util.Random.nextInt(100000)}")
    val llm = new EchoLlm
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("mvg-v2", ws, system, res)
      _ <- seed(rt,
        verifierNode("n-ver", "ver", Some("pass"), List(OutEdge("n-merge")), now),
        mergeNode("n-merge", "merge-v2", List("n-ver"), now))
      v <- node(rt, "n-ver")
      _ <- rt.engine.deliverOutTo(v, "n-merge", "verdict report for ver")
      _ <- waitStatus(rt, "n-merge", Set(NodeLifecycle.Completed))
      m <- node(rt, "n-merge")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(m.status, NodeLifecycle.Completed,
        "path a: a pass-verdict upstream must let the merge start via the barrier settle")
      assert(m.startedAt.isDefined, "the merge must have really run (startedAt set)")
      assert(m.result.exists(_.nonEmpty), "the merge must carry its own result")
  }

  // ── V3 case (b) path b：资格回扫（V1 的 pass 对照组）──────────────────────

  test("V3 (case b, path b: settle sweep): with lastVerdict=pass the same sweep that held the merge in V1 starts it (control)") {
    val ws = tempRoot / "ws-v3"; os.makeDir.all(ws)
    val system = ActorSystem(s"mvg-v3-${scala.util.Random.nextInt(100000)}")
    val llm = new EchoLlm
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("mvg-v3", ws, system, res)
      _ <- seed(rt,
        verifierNode("n-ver", "ver", Some("pass"), List(OutEdge("n-merge")), now),
        mergeNode("n-merge", "merge-v3", List("n-ver"), now))
      _ <- rt.engine.settleRunnableSweep()
      _ <- waitStatus(rt, "n-merge", Set(NodeLifecycle.Completed))
      m <- node(rt, "n-merge")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(m.status, NodeLifecycle.Completed,
        "path b: a pass-verdict upstream must let the sweep start the merge")
      assert(m.startedAt.isDefined, "the merge must have really run via the sweep")
  }

  // ── V4 读当下值：翻成 pass 即放行（非一次性历史闩）─────────────────────

  test("V4: the gate re-reads lastVerdict on every judgement — flipping the verifier to pass unblocks the held merge on the next sweep (no one-shot latch, no manual reset)") {
    val ws = tempRoot / "ws-v4"; os.makeDir.all(ws)
    val system = ActorSystem(s"mvg-v4-${scala.util.Random.nextInt(100000)}")
    val llm = new EchoLlm
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("mvg-v4", ws, system, res)
      _ <- seed(rt,
        verifierNode("n-ver", "ver", Some("fail"), List(OutEdge("n-merge")), now),
        mergeNode("n-merge", "merge-v4", List("n-ver"), now))
      _ <- rt.engine.settleRunnableSweep()
      _ <- settleWindow
      held <- node(rt, "n-merge")
      // verifier 重跑出 pass（等价于其第二轮的 node_report(pass)）——本 spec 只改判据面
      _ <- rt.store.mutate(s => s.copy(nodes = s.nodes.updated("n-ver",
        s.nodes("n-ver").copy(lastVerdict = Some("pass")))))
      _ <- rt.engine.settleRunnableSweep()
      _ <- waitStatus(rt, "n-merge", Set(NodeLifecycle.Completed))
      m <- node(rt, "n-merge")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(held.status, NodeLifecycle.Pending, "precondition: held while the verdict is fail")
      assertEquals(m.status, NodeLifecycle.Completed,
        "after the verifier re-runs to pass the gate must release the merge (current value, not history)")
  }

  // ── V5 case (c)：上游无 verifier 的 merge 行为逐字不变 ───────────────────

  test("V5 (case c control): a merge whose upstreams are all task nodes is untouched by the gate — it starts as before (existing spec NodeMergeSpec family)") {
    val ws = tempRoot / "ws-v5"; os.makeDir.all(ws)
    val system = ActorSystem(s"mvg-v5-${scala.util.Random.nextInt(100000)}")
    val llm = new EchoLlm
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("mvg-v5", ws, system, res)
      _ <- seed(rt,
        taskUpstream("n-a", "up-a", List(OutEdge("n-merge")), now),
        taskUpstream("n-b", "up-b", List(OutEdge("n-merge")), now),
        mergeNode("n-merge", "merge-v5", List("n-a", "n-b"), now))
      _ <- rt.engine.settleRunnableSweep()
      _ <- waitStatus(rt, "n-merge", Set(NodeLifecycle.Completed))
      m <- node(rt, "n-merge")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(m.status, NodeLifecycle.Completed,
        "a verifier-free merge must keep its旧 behavior (gate is inert without a verifier upstream)")
      assertEquals(m.deliveredTo.sorted, List("n-a", "n-b"), "barrier accounting unchanged")
  }

  // ── V6 case (c)：闸是 merge-only（非 merge 下游不受影响）─────────────────

  test("V6 (case c, merge-only): a NON-merge downstream of a fail-verdict verifier is not gated — it still starts (only merge nodes carry the gate)") {
    val ws = tempRoot / "ws-v6"; os.makeDir.all(ws)
    val system = ActorSystem(s"mvg-v6-${scala.util.Random.nextInt(100000)}")
    val llm = new EchoLlm
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("mvg-v6", ws, system, res)
      _ <- seed(rt,
        verifierNode("n-ver", "ver", Some("fail"), List(OutEdge("n-plain")), now),
        NodeDef(id = "n-plain", name = "plain", agent = "general", task = Some("plain task"),
          status = NodeLifecycle.Pending, in = List("n-ver"), out = List(OutEdge.nebula),
          createdAt = now - 100_000L))
      v <- node(rt, "n-ver")
      _ <- rt.engine.deliverOutTo(v, "n-plain", "verdict report for ver")
      _ <- waitStatus(rt, "n-plain", Set(NodeLifecycle.Completed))
      p <- node(rt, "n-plain")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(p.status, NodeLifecycle.Completed,
        "non-merge node behavior must be unchanged — the gate is merge-only")
  }

  // ── V7 case (d)：verifier 未申报 ⇒ 保守不放行 ────────────────────────────

  test("V7 (case d): a verifier that never declared lastVerdict (None) ⇒ conservative hold — the merge must not start") {
    val ws = tempRoot / "ws-v7"; os.makeDir.all(ws)
    val system = ActorSystem(s"mvg-v7-${scala.util.Random.nextInt(100000)}")
    val llm = new EchoLlm
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("mvg-v7", ws, system, res)
      _ <- seed(rt,
        verifierNode("n-ver", "ver", None, List(OutEdge("n-merge")), now),
        mergeNode("n-merge", "merge-v7", List("n-ver"), now))
      _ <- rt.engine.settleRunnableSweep()
      _ <- settleWindow
      m <- node(rt, "n-merge")
      v <- node(rt, "n-ver")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(m.deliveredTo.contains("n-ver"), "precondition: the barrier was healed by the sweep")
      assertEquals(m.status, NodeLifecycle.Pending,
        "an undeclared verdict (None) must hold the merge (conservative)")
      assert(m.startedAt.isEmpty, "no session may be spawned")
      assertEquals(v.lastVerdict, None, "the verifier's empty verdict must stay empty")
  }

  // ── V8 单权威：绕开两条结算腿的直投路径同样被挡 ──────────────────────────

  test("V8 (single authority): with the barrier accounting already complete, a DIRECT startNode call (the settleDeps / redeliverInAndStart / D1 route) is still held by the gate") {
    val ws = tempRoot / "ws-v8"; os.makeDir.all(ws)
    val system = ActorSystem(s"mvg-v8-${scala.util.Random.nextInt(100000)}")
    val llm = new EchoLlm
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("mvg-v8", ws, system, res)
      _ <- seed(rt,
        verifierNode("n-ver", "ver", Some("fail"), List(OutEdge("n-merge")), now),
        mergeNode("n-merge", "merge-v8", List("n-ver"), now))
      // barrier 记账人为置齐（等价于「投递早已发生」的存量形态），只留判据面
      _ <- rt.store.mutate(s => s.copy(nodes = s.nodes.updated("n-merge",
        s.nodes("n-merge").copy(deliveredTo = List("n-ver")))))
      _ <- rt.engine.startNode("n-merge")
      _ <- settleWindow
      m <- node(rt, "n-merge")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(m.status, NodeLifecycle.Pending,
        "startNode is the single authority for every start route (settleDeps / re-activation / D1) — it must hold")
      assert(m.startedAt.isEmpty, "no session may be spawned through the direct route")
  }

end MergeVerdictGateSpec
