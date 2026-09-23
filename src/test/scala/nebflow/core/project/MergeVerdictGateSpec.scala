package nebflow.core.project

import cats.effect.{Deferred, IO, Ref}
import fs2.Stream
import io.circe.Json
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
 * verdict 闸（merge-verdict-gate 批 2026-09-12，作者裁定：把「先验后合」从节点自觉变成机制
 * 保证）回归族。**engine-defects 批 #238 泛化笔（2026-09-15，`v238-impl`）**：闸面从
 * 「仅 merge 节点」扩到**全部收口位**——判据不再按节点形态分叉，非 merge sink（09-14 官网链
 * `bpm-report`）与 merge sink 同受该闸；本 spec 的 V6 族按新口径重写（原「闸是 merge-only」
 * 断言属**被本次泛化取代的旧口径**，非弱化：新断言方向为**收紧**——越闸启动由「允准」变
 * 「必须被挡」）。
 *
 * 判据：**节点的 `in ∪ deps` 上游中含 `role=verifier` 的节点时，该 verifier 的当下
 * `lastVerdict` 必须为 `pass`**，否则本节点不可启动、原地保持 pending（读当下值，
 * 非一次性历史闩；verifier 重跑出 `pass` 后自动放行）。零副作用——不改上游
 * status/lastVerdict、不写 blockedFeedback、不改 deliveredTo 记账。
 *
 * 出处实证（本族场景蓝本）：`perm-global-merge`(n-9e9c385d) —— merge=true、
 * in=[n-5c5d5801, n-6f86d028]（后者 role=verifier 且已 `node_report(fail)`），
 * deliveredTo 两轨齐（barrier 已清），仍被 `settleRunnableSweep` 拉起并启动；
 * 非 merge 侧蓝本 = 09-14 `bpm-verify`(fail) → `bpm-report`（merge=False）被补投腿
 * （`settleRunnableSweep` 第 1 步孤儿 barrier 自愈 → startNode）拉起。
 *
 * 用例面（覆盖要求 1/2/3 逐条）：
 *  - V1 case (a)：verifier 判 fail ⇒ merge **不得**被 settle-sweep 拉起（含 sweep 第 1 步
 *    孤儿 barrier 自愈已发生的证据链：barrier 记账齐 + 节点纹丝不动）+ mount-stalled 文案点名闸因；
 *  - V2 case (b) path a：verifier 判 pass ⇒ barrier 结算（deliverOutTo → settleTo）正常触发；
 *  - V3 case (b) path b：verifier 判 pass ⇒ 资格回扫正常拉起（V1 的对照组）；
 *  - V4 读当下值：held 之后把 verifier 翻成 pass ⇒ 下一轮回扫放行（无一次性闩）；
 *  - V5 case (c)：**上游无 verifier 的 merge 行为逐字不变**（task 上游正常触发）；
 *  - V6 #238 泛化：**非 merge** 收口位遇 fail verifier ⇒ 同样被闸挡住（barrier 照旧自愈、
 *    补投照旧记账，但**不启动**）+ mount-stalled 点名 + **不得**发回退告警；
 *  - V6b #238 释放面：同夹具把 verifier 翻成 pass ⇒ 下一轮回扫放行（非 merge 侧不永久卡死）；
 *  - V6c #238 GREEN 面（误报归零）：**pass** verifier 的非 merge 下游照常启动（同一 sweep 腿）；
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

  os.write.over(
    tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"merge verdict gate spec agent","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  /**
   * 立即应答的捕获 LLM（节点会话跑满真实栈：spawn → 首轮 → completed）。
   *
   * `gate`（V9 专用确定性同步，p547b 2026-09-15）：Some 时 `sendStream` 先等闸
   * 放行再产出。用途：重激活触发的 detached 重跑腿（`NodeTools.runDetached`
   * → `startNode`）在 **调 LLM 之前**就把节点翻成 `running`（runWithAgent 的
   * CAS 翻转），测试由此能把重跑**确定性按在 `running`**、在无竞速的固定状态
   * 下读取中间态——修前该读取是 Wiring/Pending/running 三态竞速窗口（flake
   * 根因，见 V9 用例注释）。其余用例不传闸（默认 None），行为逐字不变。
   */
  private class EchoLlm(gate: Option[Deferred[IO, Unit]] = None):
    val inputs: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)

    private val waitGate: IO[Unit] = gate match
      case Some(d) => d.get
      case None => IO.unit

    def handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(
        req: LlmRequest,
        onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
      ): Stream[IO, StreamChunk] =
        Stream.eval(waitGate >> inputs.update(_ :+ req.messages.map(_.textContent).mkString("\n"))) >>
          Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  end EchoLlm

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
        store,
        system,
        res,
        wsSendFn = (_: Json) => IO.unit,
        workspace = ws.toString,
        rootSessionId = "nebula-root",
        projectName = name,
        emitEvent = (_, _, _) => IO.unit,
        // noderpt 批 A 段：本 fixture 主题非 node_report 语义 ⇒ 显式关腿 2（对齐
        // NodeMountEnforceSpec / MergeDesignGapSpec 同款装配）。
        reportGateHold = Some(false)
      )
      pd = ProjectDef(
        name = name,
        workspace = ws.toString,
        agentFile = (ws / "AGENTS.md").toString,
        createdAt = System.currentTimeMillis()
      )
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
      .map(lines =>
        lines.flatMap(l =>
          jsonParse(l).toOption.map(j =>
            (
              j.hcursor.get[String]("type").getOrElse(""),
              j.hcursor.get[String]("nodeId").getOrElse(""),
              j.hcursor.get[String]("summary").getOrElse("")
            )
          )
        )
      )
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

  private def waitStatus(
    rt: ProjectRuntime,
    id: String,
    statuses: Set[String],
    timeout: FiniteDuration = 30.seconds
  ): IO[Unit] =
    waitUntil(timeout) {
      rt.store.getNode(id).map(_.exists(n => statuses.contains(n.status)))
    }

  /** 「保持未启动」的负向断言窗口：给 fork 出的启动腿足够时间跑完（生产同款亚秒级）。 */
  private def settleWindow: IO[Unit] = IO.sleep(600.millis)

  // ── 节点构造（判据两侧的形态蓝本）─────────────────────────────

  /** verifier 上游（role=verifier，verdict 由 lastVerdict 携带；completed = verdict 面终态）。 */
  private def verifierNode(id: String, name: String, verdict: Option[String], out: List[OutEdge], now: Long): NodeDef =
    NodeDef(
      id = id,
      name = name,
      agent = "general",
      task = Some(s"$name verify task"),
      status = NodeLifecycle.Completed,
      role = NodeRoles.Verifier,
      lastVerdict = verdict,
      result = Some(s"verdict report for $name"),
      out = out,
      createdAt = now - 400_000L,
      completedAt = Some(now - 200_000L)
    )

  /** merge 节点（merge=true；in = 上游清单；pending = 未触发态）。 */
  private def mergeNode(id: String, name: String, in: List[String], now: Long): NodeDef =
    NodeDef(
      id = id,
      name = name,
      agent = "general",
      merge = true,
      task = Some(s"$name landing task"),
      status = NodeLifecycle.Pending,
      in = in,
      out = List(OutEdge.nebula),
      createdAt = now - 100_000L
    )

  /** task 上游（无 verifier 的对照组）。 */
  private def taskUpstream(id: String, name: String, out: List[OutEdge], now: Long): NodeDef =
    NodeDef(
      id = id,
      name = name,
      agent = "general",
      task = Some(s"$name task"),
      status = NodeLifecycle.Completed,
      result = Some(s"$name artifact"),
      out = out,
      createdAt = now - 400_000L,
      completedAt = Some(now - 200_000L)
    )

  // ── V1 case (a)：verifier fail ⇒ settle sweep 不得拉起 merge（出处场景）──

  test(
    "V1 (case a, incident form n-9e9c385d): the sweep heals the orphan barrier but must NOT start a merge whose in-upstream verifier judged fail — node stays pending, zero side effects, mount-stalled names the gate"
  ) {
    val ws = tempRoot / "ws-v1"; os.makeDir.all(ws)
    val system = ActorSystem(s"mvg-v1-${scala.util.Random.nextInt(100000)}")
    val llm = new EchoLlm
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("mvg-v1", ws, system, res)
      _ <- seed(
        rt,
        verifierNode("n-ver", "ver", Some("fail"), List(OutEdge("n-merge")), now),
        mergeNode("n-merge", "merge-v1", List("n-ver"), now)
      )
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
      assert(
        m1.deliveredTo.contains("n-ver"),
        s"precondition: the sweep must have healed the orphan barrier (deliveredTo should hold n-ver), got ${m1.deliveredTo}"
      )
      assert(
        audit1.exists((t, id, _) => t == "settle-sweep" && id == "n-merge"),
        s"precondition: the sweep must have processed n-merge (settle-sweep audit), got ${audit1
            .map((t, id, _) => (t, id))}"
      )
      // 判据本体：barrier 已清而 merge 不启动
      assertEquals(
        m1.status,
        NodeLifecycle.Pending,
        "merge with a fail-verdict upstream must stay pending (verdict gate)"
      )
      assert(m1.startedAt.isEmpty, "no session may be spawned for a gated merge")
      assertEquals(m1.result, None, "the gate must not fabricate any result")
      assertEquals(m1.blockedFeedback, None, "the gate must NOT fake a blocked state")
      // 上游零改写
      assertEquals(v1.status, NodeLifecycle.Completed, "upstream verifier status must not be rewritten")
      assertEquals(v1.lastVerdict, Some("fail"), "upstream lastVerdict must not be rewritten")
      assert(
        !audit1.exists((t, id, _) => id == "n-merge" && (t == "blocked" || t == "merge-blocked")),
        s"no blocked/merge-blocked event may be emitted for a verdict-gated merge, got ${audit1
            .filter((_, id, _) => id == "n-merge")}"
      )
      // 幂等：多轮回扫仍持有
      assertEquals(m2.status, NodeLifecycle.Pending, "repeated sweeps must keep holding the merge (idempotent)")
      assertEquals(v2.lastVerdict, Some("fail"), "repeated sweeps must not touch the upstream verdict")
      // 停等可见性：既有 mount-stalled 事件（60s 档、单发）点名 verdict 闸
      val stalls = audit1.filter((t, id, _) => t == "mount-stalled" && id == "n-merge")
      assertEquals(stalls.size, 1, s"exactly one mount-stalled line expected for the held merge, got $stalls")
      val stall = stalls.head._3
      assert(stall.contains("verdict gate held"), s"the stall reason must name the verdict gate, got: $stall")
      assert(
        stall.contains("n-ver") && stall.contains("lastVerdict=fail"),
        s"the stall reason must name the holding verifier + its verdict, got: $stall"
      )
    end for
  }

  // ── V2 case (b) path a：barrier 结算（deliverOutTo → settleTo）────────────────

  test(
    "V2 (case b, path a: barrier settle): a verifier whose current lastVerdict is pass lets the merge trigger through deliverOutTo's barrier settlement"
  ) {
    val ws = tempRoot / "ws-v2"; os.makeDir.all(ws)
    val system = ActorSystem(s"mvg-v2-${scala.util.Random.nextInt(100000)}")
    val llm = new EchoLlm
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("mvg-v2", ws, system, res)
      _ <- seed(
        rt,
        verifierNode("n-ver", "ver", Some("pass"), List(OutEdge("n-merge")), now),
        mergeNode("n-merge", "merge-v2", List("n-ver"), now)
      )
      v <- node(rt, "n-ver")
      _ <- rt.engine.deliverOutTo(v, "n-merge", "verdict report for ver")
      _ <- waitStatus(rt, "n-merge", Set(NodeLifecycle.Completed))
      m <- node(rt, "n-merge")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(
        m.status,
        NodeLifecycle.Completed,
        "path a: a pass-verdict upstream must let the merge start via the barrier settle"
      )
      assert(m.startedAt.isDefined, "the merge must have really run (startedAt set)")
      assert(m.result.exists(_.nonEmpty), "the merge must carry its own result")
    end for
  }

  // ── V3 case (b) path b：资格回扫（V1 的 pass 对照组）──────────────────────

  test(
    "V3 (case b, path b: settle sweep): with lastVerdict=pass the same sweep that held the merge in V1 starts it (control)"
  ) {
    val ws = tempRoot / "ws-v3"; os.makeDir.all(ws)
    val system = ActorSystem(s"mvg-v3-${scala.util.Random.nextInt(100000)}")
    val llm = new EchoLlm
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("mvg-v3", ws, system, res)
      _ <- seed(
        rt,
        verifierNode("n-ver", "ver", Some("pass"), List(OutEdge("n-merge")), now),
        mergeNode("n-merge", "merge-v3", List("n-ver"), now)
      )
      _ <- rt.engine.settleRunnableSweep()
      _ <- waitStatus(rt, "n-merge", Set(NodeLifecycle.Completed))
      m <- node(rt, "n-merge")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(
        m.status,
        NodeLifecycle.Completed,
        "path b: a pass-verdict upstream must let the sweep start the merge"
      )
      assert(m.startedAt.isDefined, "the merge must have really run via the sweep")
    end for
  }

  // ── V4 读当下值：翻成 pass 即放行（非一次性历史闩）─────────────────────

  test(
    "V4: the gate re-reads lastVerdict on every judgement — flipping the verifier to pass unblocks the held merge on the next sweep (no one-shot latch, no manual reset)"
  ) {
    val ws = tempRoot / "ws-v4"; os.makeDir.all(ws)
    val system = ActorSystem(s"mvg-v4-${scala.util.Random.nextInt(100000)}")
    val llm = new EchoLlm
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("mvg-v4", ws, system, res)
      _ <- seed(
        rt,
        verifierNode("n-ver", "ver", Some("fail"), List(OutEdge("n-merge")), now),
        mergeNode("n-merge", "merge-v4", List("n-ver"), now)
      )
      _ <- rt.engine.settleRunnableSweep()
      _ <- settleWindow
      held <- node(rt, "n-merge")
      // verifier 重跑出 pass（等价于其第二轮的 node_report(pass)）——本 spec 只改判据面
      _ <- rt.store.mutate(s =>
        s.copy(nodes = s.nodes.updated("n-ver", s.nodes("n-ver").copy(lastVerdict = Some("pass"))))
      )
      _ <- rt.engine.settleRunnableSweep()
      _ <- waitStatus(rt, "n-merge", Set(NodeLifecycle.Completed))
      m <- node(rt, "n-merge")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(held.status, NodeLifecycle.Pending, "precondition: held while the verdict is fail")
      assertEquals(
        m.status,
        NodeLifecycle.Completed,
        "after the verifier re-runs to pass the gate must release the merge (current value, not history)"
      )
    end for
  }

  // ── V5 case (c)：上游无 verifier 的 merge 行为逐字不变 ───────────────────

  test(
    "V5 (case c control): a merge whose upstreams are all task nodes is untouched by the gate — it starts as before (existing spec NodeMergeSpec family)"
  ) {
    val ws = tempRoot / "ws-v5"; os.makeDir.all(ws)
    val system = ActorSystem(s"mvg-v5-${scala.util.Random.nextInt(100000)}")
    val llm = new EchoLlm
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("mvg-v5", ws, system, res)
      _ <- seed(
        rt,
        taskUpstream("n-a", "up-a", List(OutEdge("n-merge")), now),
        taskUpstream("n-b", "up-b", List(OutEdge("n-merge")), now),
        mergeNode("n-merge", "merge-v5", List("n-a", "n-b"), now)
      )
      _ <- rt.engine.settleRunnableSweep()
      _ <- waitStatus(rt, "n-merge", Set(NodeLifecycle.Completed))
      m <- node(rt, "n-merge")
      audit <- readAudit(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(
        m.status,
        NodeLifecycle.Completed,
        "a verifier-free merge must keep its旧 behavior (gate is inert without a verifier upstream)"
      )
      assertEquals(m.deliveredTo.sorted, List("n-a", "n-b"), "barrier accounting unchanged")
      // engine-defects 批 #238 GREEN 臂：上游无 verifier ⇒ 回退告警**不得**出现（不误报）
      assert(
        !audit.exists((t, _, _) => t == FlowMapEventLog.VerdictGateGapType),
        s"no verifier upstream => no breach event may be emitted, got ${audit
            .filter((t, _, _) => t == FlowMapEventLog.VerdictGateGapType)}"
      )
    end for
  }

  // ── V6 族（#238 泛化）：非 merge 收口位与 merge sink 同受闸 ────────────────
  //
  // 缝本体（09-14 官网链 `bpm-verify`(fail) → `bpm-report`(merge=False)）：闸前置
  // `MergeNodePolicy.isMerge` ⇒ 非 merge 收口位被补投腿（sweep 第 1 步孤儿 barrier 自愈
  // → startNode）拉起，fail 结论被当正向交付前递。泛化笔删除该前置 ⇒ 本族按新口径重写。
  //
  // 补投腿**不改**（侦察 §4 红线：不得下沉 `deliverOutTo` 公共门；且 `deliveredTo` 记账
  // 是 V1/V7 的逐字前提）⇒ 三件事实必须同时成立：①barrier 照旧自愈（记账齐）②节点**不**
  // 启动 ③事件流里有可 grep 的闸因（mount-stalled 文案）。

  /**
   * 非 merge 收口位（无 `merge` 标记、有 task ⇒ 合格即会真 spawn 会话）+ 旧 completedAt
   * 的 verifier 上游 ⇒ 覆盖「可触发点已过 60s」的 mount-stalled 档位（同 V1 夹具手法）。
   */
  private def nonMergeSink(id: String, name: String, in: List[String], now: Long): NodeDef =
    NodeDef(
      id = id,
      name = name,
      agent = "general",
      task = Some(s"$name sink task"),
      status = NodeLifecycle.Pending,
      in = in,
      out = List(OutEdge.nebula),
      createdAt = now - 100_000L
    )

  test(
    "V6 (#238 generalization): a NON-merge landing position whose in-upstream verifier judged fail is HELD by the gate — the orphan-barrier sweep still heals the accounting but must not start it"
  ) {
    val ws = tempRoot / "ws-v6"; os.makeDir.all(ws)
    val system = ActorSystem(s"mvg-v6-${scala.util.Random.nextInt(100000)}")
    val llm = new EchoLlm
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("mvg-v6", ws, system, res)
      _ <- seed(
        rt,
        verifierNode("n-ver", "ver", Some("fail"), List(OutEdge("n-plain")), now),
        nonMergeSink("n-plain", "plain", List("n-ver"), now)
      )
      _ <- rt.engine.settleRunnableSweep()
      _ <- settleWindow
      p1 <- node(rt, "n-plain")
      v1 <- node(rt, "n-ver")
      audit1 <- readAudit(ws)
      // 幂等重放：连续两轮回扫仍不得放行
      _ <- rt.engine.settleRunnableSweep() *> IO.sleep(200.millis) *> rt.engine.settleRunnableSweep()
      _ <- settleWindow
      p2 <- node(rt, "n-plain")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // ① 幂等/记账前置：补投腿照旧跑（barrier 自愈 + settle-sweep 留痕）——闸不改记账契约
      assert(
        p1.deliveredTo.contains("n-ver"),
        s"precondition: the sweep must still heal the orphan barrier (ledger unchanged), got ${p1.deliveredTo}"
      )
      assert(
        audit1.exists((t, id, _) => t == "settle-sweep" && id == "n-plain"),
        s"precondition: the sweep must have processed n-plain, got ${audit1.map((t, id, _) => (t, id))}"
      )
      // ② 判据本体（#238）：barrier 已清而**非 merge** 收口位不启动
      assertEquals(
        p1.status,
        NodeLifecycle.Pending,
        "a NON-merge landing position with a fail-verdict upstream must stay pending (the gate is no longer merge-only)"
      )
      assert(p1.startedAt.isEmpty, "no session may be spawned for a gated non-merge sink")
      assertEquals(p1.result, None, "the gate must not fabricate any result")
      assertEquals(p1.blockedFeedback, None, "the gate must NOT fake a blocked state")
      // ③ 上游零改写
      assertEquals(v1.status, NodeLifecycle.Completed, "upstream verifier status must not be rewritten")
      assertEquals(v1.lastVerdict, Some("fail"), "upstream lastVerdict must not be rewritten")
      // ④ 幂等：多轮回扫仍持有
      assertEquals(p2.status, NodeLifecycle.Pending, "repeated sweeps must keep holding (idempotent)")
      assert(p2.startedAt.isEmpty, "no session may be spawned on any sweep round")
      // ⑤ 停等可见性（非 merge 侧同样点名闸因：分发器不该把它误判为引擎故障）
      val stalls = audit1.filter((t, id, _) => t == "mount-stalled" && id == "n-plain")
      assertEquals(stalls.size, 1, s"exactly one mount-stalled line expected for the held sink, got $stalls")
      val stall = stalls.head._3
      assert(stall.contains("verdict gate held"), s"the stall reason must name the verdict gate, got: $stall")
      assert(
        stall.contains("n-ver") && stall.contains("lastVerdict=fail"),
        s"the stall reason must name the holding verifier + its verdict, got: $stall"
      )
      // ⑥ 回退告警**不得**出现：闸确实持有时走不到告警写点（越闸启动 = 结构性不可能）
      assert(
        !audit1.exists((t, _, _) => t == FlowMapEventLog.VerdictGateGapType),
        s"a held node must NOT emit the regression alarm, got ${audit1
            .filter((t, _, _) => t == FlowMapEventLog.VerdictGateGapType)}"
      )
    end for
  }

  test(
    "V6b (#238 release): flipping the same fixture's verifier to pass releases the held NON-merge sink on the next sweep (no one-shot latch, no permanent stall)"
  ) {
    val ws = tempRoot / "ws-v6b"; os.makeDir.all(ws)
    val system = ActorSystem(s"mvg-v6b-${scala.util.Random.nextInt(100000)}")
    val llm = new EchoLlm
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("mvg-v6b", ws, system, res)
      _ <- seed(
        rt,
        verifierNode("n-ver", "ver", Some("fail"), List(OutEdge("n-plain")), now),
        nonMergeSink("n-plain", "plain", List("n-ver"), now)
      )
      _ <- rt.engine.settleRunnableSweep()
      _ <- settleWindow
      held <- node(rt, "n-plain")
      // verifier 重跑出 pass（等价于其第二轮的 node_report(pass)）——本 spec 只改判据面
      _ <- rt.store.mutate(s =>
        s.copy(nodes = s.nodes.updated("n-ver", s.nodes("n-ver").copy(lastVerdict = Some("pass"))))
      )
      _ <- rt.engine.settleRunnableSweep()
      _ <- waitStatus(rt, "n-plain", Set(NodeLifecycle.Completed))
      p <- node(rt, "n-plain")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(held.status, NodeLifecycle.Pending, "precondition: held while the verdict is fail")
      assertEquals(
        p.status,
        NodeLifecycle.Completed,
        "after the verifier re-runs to pass the gate must release the non-merge sink (current value, not history)"
      )
      assert(p.startedAt.isDefined, "the released sink must have really run")
    end for
  }

  test(
    "V6c (#238 GREEN arm, false-positive zero): with the SAME sweep leg, a pass-verdict upstream lets the non-merge sink start as before"
  ) {
    val ws = tempRoot / "ws-v6c"; os.makeDir.all(ws)
    val system = ActorSystem(s"mvg-v6c-${scala.util.Random.nextInt(100000)}")
    val llm = new EchoLlm
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("mvg-v6c", ws, system, res)
      _ <- seed(
        rt,
        verifierNode("n-ver", "ver", Some("pass"), List(OutEdge("n-plain")), now),
        nonMergeSink("n-plain", "plain", List("n-ver"), now)
      )
      _ <- rt.engine.settleRunnableSweep()
      _ <- waitStatus(rt, "n-plain", Set(NodeLifecycle.Completed))
      p <- node(rt, "n-plain")
      audit <- readAudit(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(
        p.status,
        NodeLifecycle.Completed,
        "GREEN arm: a pass-verdict upstream must let the non-merge sink start (the widened gate must not false-positive)"
      )
      assert(p.startedAt.isDefined, "the sink must have really run via the sweep")
      assert(
        !audit.exists((t, _, _) => t == FlowMapEventLog.VerdictGateGapType),
        s"no breach event may be emitted on the pass arm, got ${audit
            .filter((t, _, _) => t == FlowMapEventLog.VerdictGateGapType)}"
      )
    end for
  }

  // ── V7 case (d)：verifier 未申报 ⇒ 保守不放行 ────────────────────────────

  test(
    "V7 (case d): a verifier that never declared lastVerdict (None) ⇒ conservative hold — the merge must not start"
  ) {
    val ws = tempRoot / "ws-v7"; os.makeDir.all(ws)
    val system = ActorSystem(s"mvg-v7-${scala.util.Random.nextInt(100000)}")
    val llm = new EchoLlm
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("mvg-v7", ws, system, res)
      _ <- seed(
        rt,
        verifierNode("n-ver", "ver", None, List(OutEdge("n-merge")), now),
        mergeNode("n-merge", "merge-v7", List("n-ver"), now)
      )
      _ <- rt.engine.settleRunnableSweep()
      _ <- settleWindow
      m <- node(rt, "n-merge")
      v <- node(rt, "n-ver")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(m.deliveredTo.contains("n-ver"), "precondition: the barrier was healed by the sweep")
      assertEquals(m.status, NodeLifecycle.Pending, "an undeclared verdict (None) must hold the merge (conservative)")
      assert(m.startedAt.isEmpty, "no session may be spawned")
      assertEquals(v.lastVerdict, None, "the verifier's empty verdict must stay empty")
    end for
  }

  // ── V8 单权威：绕开两条结算腿的直投路径同样被挡 ──────────────────────────

  test(
    "V8 (single authority): with the barrier accounting already complete, a DIRECT startNode call (the settleDeps / redeliverInAndStart / D1 route) is still held by the gate"
  ) {
    val ws = tempRoot / "ws-v8"; os.makeDir.all(ws)
    val system = ActorSystem(s"mvg-v8-${scala.util.Random.nextInt(100000)}")
    val llm = new EchoLlm
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("mvg-v8", ws, system, res)
      _ <- seed(
        rt,
        verifierNode("n-ver", "ver", Some("fail"), List(OutEdge("n-merge")), now),
        mergeNode("n-merge", "merge-v8", List("n-ver"), now)
      )
      // barrier 记账人为置齐（等价于「投递早已发生」的存量形态），只留判据面
      _ <- rt.store.mutate(s =>
        s.copy(nodes = s.nodes.updated("n-merge", s.nodes("n-merge").copy(deliveredTo = List("n-ver"))))
      )
      _ <- rt.engine.startNode("n-merge")
      _ <- settleWindow
      m <- node(rt, "n-merge")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(
        m.status,
        NodeLifecycle.Pending,
        "startNode is the single authority for every start route (settleDeps / re-activation / D1) — it must hold"
      )
      assert(m.startedAt.isEmpty, "no session may be spawned through the direct route")
    end for
  }

  // ── V9/V10 #245：重激活必须作废上一轮判词（engine-defects 批 · 2026-09-15）────
  //
  // 缺陷形态（**判据面**）：NodeEdit 重激活 = 「同一身份重跑一轮」——`result` /
  // `deliveredTo` / `nebulaDeliveredAt` / `startedAt` / `completedAt` 全部清零，
  // 唯独 `lastVerdict` 不在其列 ⇒ 重跑中的复核位仍挂着**上一轮**的 `pass`，而本闸
  // **每次判定现读**（V4 的口径）⇒ 下游 merge 被**陈旧结论**放开 ⇒ 按陈旧结论推进。
  //
  // 双向分辨力（同一夹具，两条臂只差「重激活是否清 lastVerdict」）：
  //   · 修复后（GREEN 臂）：重激活 ⇒ `lastVerdict = None` ⇒ 闸保守 hold（`None` 与
  //     `fail` 同被挡，见 V7）⇒ merge 纹丝不动；
  //   · 变异臂（删掉 `lastVerdict = None` 这一行、回退成 `fresh.lastVerdict`）：
  //     `lastVerdict` 仍是 `pass` ⇒ 闸放行 ⇒ merge 启动并 completed ⇒ 本用例必红。
  //
  // 🔧 flake 根因修复（p547b 2026-09-15，#547 队首 ④/⑥ 同源）：本用例修后曾在
  // 轻载环境绿、合跑/本机红（`MergeVerdictGateSpec.scala:593 … got running`）。
  // 机制 = **裸竞速**，与跨 suite 共享态无关（单跑单独跑也红，本批实测 3/3）：
  // 重激活写点（NodeTools.scala 重激活 mutate）之后 `NodeTools.runDetached` 在
  // 后台 fiber 里做「重投递 + barrier 结算 + startNode」，而 runWithAgent 在调
  // LLM **之前**就把节点 CAS 翻成 `running` ⇒ 修前测试在 edit 返回后立刻
  // `node(rt,…)` 读中间态，读到 Wiring/Pending 才绿、读到 running 即 ：593 红
  // ——断言钉在了一个**无同步保证的瞬态窗口**上（负载决定输赢 ⇒ flaky）。
  // 修法 = **确定性同步**（EchoLlm 放行闸，见该类注释）：等 `running`（确定态）
  // → 断言中间态 → 放行 → 等 `completed`（确定态）→ 回扫。断言集**只收紧不
  // 放宽**：原「Wiring‖Pending」瞬态窗口断言改为钉住唯一的确定性中间态
  // `running`，并新增重跑收口面两条（v2 completed + 判词仍空，净 +2 断言）；
  // 机械面（判词作废）与判据面（merge 不启动）逐字不变。

  private def mkCtx(res: SharedResources, system: ActorSystem, ws: os.Path): ToolContext =
    ToolContext(
      projectRoot = ws.toString,
      sessionId = Some("mvg-sid"),
      rootSessionId = Some("nebula-root"),
      sharedResources = Some(res),
      actorSystem = Some(system)
    )

  private def nodeInput(project: String, nodename: String, extra: (String, Json)*): Json =
    Json.obj(("project" -> Json.fromString(project)) :: ("nodename" -> Json.fromString(nodename)) :: extra.toList*)

  private def nodeEdit(input: Json, ctx: ToolContext): IO[Either[String, String]] =
    NodeEditTool.call(input.asObject.get, ctx).map(_.left.map(_.message))

  test(
    "V9 (#245): re-activating a verifier voids its PREVIOUS verdict — the stale 'pass' must not open the gate for the downstream merge on the next sweep"
  ) {
    val ws = tempRoot / "ws-v9"; os.makeDir.all(ws)
    val system = ActorSystem(s"mvg-v9-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      // 放行闸：重激活的 detached 重跑腿会被按在 `running`（EchoLlm 等放行），
      // 中间态读取从竞速窗口变成确定态（flake 根因修复，见上方注释块）。
      gate <- Deferred[IO, Unit]
      llm = new EchoLlm(Some(gate))
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("mvg-v9", ws, system, res)
      _ <- seed(
        rt,
        // verifier 必须声明回边（`NODE_VERIFIER_NEEDS_ROUTE` 在 edit 路径上同样生效）——
        // 被判定对象 n-work 以回边目标身份在场（`:loop` 控制边不进 barrier、不进 in 镜像）
        verifierNode(
          "n-ver",
          "ver",
          Some("pass"),
          List(OutEdge("n-merge"), OutEdge("n-work", Set(OutEdge.Fail), OutEdge.Loop)),
          now
        ),
        taskUpstream("n-work", "work", List(OutEdge.nebula), now),
        mergeNode("n-merge", "merge-v9", List("n-ver"), now)
      )
      // barrier 记账人为置齐（等价于「投递早已发生」的存量形态，同 V8）——只留判据面：
      // 这样 merge 能否启动**只**取决于闸读到的 lastVerdict，与投递腿无关。
      _ <- rt.store.mutate(s =>
        s.copy(nodes = s.nodes.updated("n-merge", s.nodes("n-merge").copy(deliveredTo = List("n-ver"))))
      )
      ctx = mkCtx(res, system, ws)
      // 重激活：Completed 节点须显式 `reactivateCompleted=true` + task 实际变更（既有判据）
      edit <- nodeEdit(
        nodeInput(
          "mvg-v9",
          "ver",
          "task" -> Json.fromString("verifier re-check round 2"),
          "reactivateCompleted" -> Json.fromBoolean(true)
        ),
        ctx
      )
      // 确定性中间态：detached 重跑腿把节点翻到 `running` 后被 LLM 闸按住——
      // 该状态在放行前不再变化，读取无竞速。
      _ <- waitStatus(rt, "n-ver", Set(NodeLifecycle.Running))
      v1 <- node(rt, "n-ver")
      // 放行重跑：EchoLlm 应答 → 会话经真实栈跑完 → completed（新判词只能来自
      // node_report，本夹具不发 ⇒ completed 后判词仍空）。
      _ <- gate.complete(()).void
      _ <- waitStatus(rt, "n-ver", Set(NodeLifecycle.Completed))
      v2 <- node(rt, "n-ver")
      // 重激活后回扫：闸读到的必须是「当下没有判词」，而不是上一轮的 pass
      _ <- rt.engine.settleRunnableSweep()
      _ <- settleWindow
      _ <- rt.engine.settleRunnableSweep()
      _ <- settleWindow
      m <- node(rt, "n-merge")
      audit <- readAudit(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(edit.isRight, s"the reactivating edit must be accepted, got: $edit")
      // ① 机械面：上一轮判词随重激活作废（重跑中的复核位**当下没有判词**）
      assertEquals(
        v1.lastVerdict,
        None,
        s"re-activation is a fresh identity re-run: the previous verdict MUST be voided, got ${v1.lastVerdict}"
      )
      // 原瞬态窗口断言（`Wiring‖Pending`，竞速）改为钉住确定性中间态：重跑已
      // 真的在飞（节点离开终态、新会话已开跑并被闸按住）——同一语义「re-run
      // armed」的无竞速形态。
      assertEquals(
        v1.status,
        NodeLifecycle.Running,
        s"the re-run must be in flight (the detached leg left the terminal state and the fresh session is open), got ${v1.status}"
      )
      assertEquals(v1.result, None, "the previous result is cleared by the same field family (unchanged behaviour)")
      // ①b 重跑收口面（新增确定性钉）：重跑经真实栈跑完、判词仍空
      assertEquals(
        v2.status,
        NodeLifecycle.Completed,
        "the fresh round must complete through the real stack (gated EchoLlm released)"
      )
      assertEquals(
        v2.lastVerdict,
        None,
        "the completed fresh round must NOT inherit the previous verdict (a new verdict can only come from node_report)"
      )
      assert(
        audit.exists((t, id, _) => t == "reactivated" && id == "n-ver"),
        s"re-activation must leave its audit line, got ${audit.map((t, id, _) => (t, id)).distinct}"
      )
      // ② 判据面：陈旧 pass 不得放开闸 ⇒ barrier 已清而 merge 纹丝不动
      assertEquals(
        m.status,
        NodeLifecycle.Pending,
        "a merge whose in-upstream verifier has NO current verdict must stay pending (the stale pass must not advance it)"
      )
      assert(m.startedAt.isEmpty, "no session may be spawned on a stale verdict")
      assertEquals(m.result, None, "the gate must not fabricate any result")
    end for
  }

  test(
    "V10 (#245 zero drift): re-activating a role=task node leaves lastVerdict untouched, and resetForLoop's verdict retention is out of this leg's scope"
  ) {
    val ws = tempRoot / "ws-v10"; os.makeDir.all(ws)
    val system = ActorSystem(s"mvg-v10-${scala.util.Random.nextInt(100000)}")
    val llm = new EchoLlm
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("mvg-v10", ws, system, res)
      // role=task（缺省）承载一个合成判词：本用例只验「非 verifier 的字段集零漂移」
      _ <- seed(
        rt,
        taskUpstream("n-work", "work", List(OutEdge.nebula), now)
          .copy(status = NodeLifecycle.Completed, lastVerdict = Some("fail"))
      )
      ctx = mkCtx(res, system, ws)
      edit <- nodeEdit(
        nodeInput(
          "mvg-v10",
          "work",
          "task" -> Json.fromString("work round 2"),
          "reactivateCompleted" -> Json.fromBoolean(true)
        ),
        ctx
      )
      a <- node(rt, "n-work")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(edit.isRight, s"the reactivating edit must be accepted, got: $edit")
      assertEquals(
        a.lastVerdict,
        Some("fail"),
        "role=task reactivation must keep the field family byte-identical (only the verifier leg voids the verdict)"
      )
    end for
  }

end MergeVerdictGateSpec
