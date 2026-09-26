package nebflow.core.project

import cats.effect.{IO, Ref}
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentLibrary, SharedResources}
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, NodeTools}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk}

import scala.concurrent.duration.*

/**
 * **排队位次逐节点唯一批**（queuepos 批 2026-09-15；作者现场报「这个合并节点的排队显示也
 * 有问题，所以节点显示的前面还有几个都一样的。根本不知道节点排队的顺序。点开的详情面板
 * 里也没有。」）引擎/载荷面回归族。
 *
 * 真凶（考古件 `.nebflow/reports/20260915_queuepos-archaeo.md` §A②）：显示面的数字是
 * `mergeQueue.ahead` = **阻塞集合的势**（`holders.length`），**不是**位次——同刻只有一个
 * merge 在 `running`、其余排队者的上游复核都没过 pass（`NodeEngine.mergeQueueHolders` 的
 * verdict 准入过滤把它们从彼此的持有者集合里剔除）时，**全体排队者 `ahead ≡ 1`**
 * （「前面还有 1 个」看起来一模一样）；而队首（没人挡它）在旧键下**完全没有显示**。
 * 载荷里**根本没有本节点位次与队列长度**（`grep queuePos` = 0 命中）。
 *
 * 本批 = **只增键**：新增条件键 `mergeQueuePos = {position, total, queue, arrived,
 * readyAt, createdAt, rank, sameKeyProjects?}`，位次真源 = SEM-2 次序键
 * `rank = (readyAt, createdAt, id)` 升序（[[MergeMutexPolicy.queuePositionOf]] 单点）。
 * 🔴 既有 `mergeQueue`（含 `ahead`）语义**逐字冻结**（[[MergeQueueVisibilitySpec]] ①-⑤
 * 与零漂移断言零改动）——两个量分工：`mergeQueue` = 「谁挡着我」，`mergeQueuePos` = 「我排第几」。
 *
 * 🔴 判据纪律：位次**禁**读文件票层（`.nebflow/locks/main-merge.queue` 是过渡期旧层）、
 * 🔴 **禁**从事件流回放（事件流是审计面）、🔴 **禁**前端/分发器复刻。
 *
 * 用例面：
 *  - ① **作者现场复刻**：1 个 running + 3 个排队（且排队者的 verdict 未过）⇒ 旧面
 *    `ahead` 三者**全同 = 1**（改前红读数）而对读新面 `position` = 2/3/4 **互异**（改后绿）；
 *  - ② **队首/无持有者**（旧面 0/3 零显示那一极）⇒ 新面 1/2/3 全带键；
 *  - ③ `arrived` 标志 + 未到达者（上游未终态 ⇒ rank 首键 MaxValue）位次排尾；
 *  - ④ 条件键纪律零漂移：非 merge / running / 终态 / blocked / 单人（竞争者 <2）**无**键；
 *  - ⑤ `mergeQueue` 既有键字段集与取值**逐字不变**（只增键，无一处放宽）；
 *  - ⑥ 纯派生：NodeDef 持久面**无**该键（不进 flow-map.json / 归档批）；
 *  - ⑦ 同键多项目（O-1）⇒ 两键都带 `sameKeyProjects`（前端降级：不渲染数字）；
 *  - ⑧ **真源一致性**：位次序 ≡ 按载荷 `(readyAt, createdAt, id)` 升序复算的序（机械可判）。
 *
 * 变异臂（自证；🔴 禁跳过）：把 `queuePositionOf` 换成「`mergeQueueHolders` 的势」（即旧
 * `ahead` 口径）⇒ ① 的互异断言必红；把 `QueueMinContenders` 降到 1 ⇒ ④ 的单人无键必红；
 * 去掉 verdict 准入过滤 ⇒ [[MergeQueueVisibilitySpec]] 的边界组必红（既有族，非本族）。
 */
class MergeQueuePositionSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 240.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-merge-queue-position"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "general")

  os.write.over(
    tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"merge queue position spec agent","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  /** 本族**不启动任何节点**（判据是纯状态派生）：LLM 腿只作装配占位，被调用即失败。 */
  private val deadLlm: LlmHandle[IO] = new LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("no node may be started by this spec"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(IO.raiseError(new RuntimeException("no node may be started by this spec")))

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
      llm = deadLlm,
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

  private def mountProject(name: String, ws: os.Path, system: ActorSystem, res: SharedResources): IO[ProjectRuntime] =
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

  private def seed(rt: ProjectRuntime, nodes: NodeDef*): IO[Unit] =
    rt.store.mutate(s => s.copy(nodes = s.nodes ++ nodes.map(n => n.id -> n).toMap)).void

  /** 载荷读数（**真入口** = NodeList / REST flow-map 的共用序列化面）。 */
  private def payload(rt: ProjectRuntime): IO[Json] = NodeTools.buildNodeListPayload(rt)

  private def nodeJson(p: Json, id: String): Json =
    p.hcursor
      .downField("nodes")
      .as[List[Json]]
      .getOrElse(Nil)
      .find(_.hcursor.get[String]("id").contains(id))
      .getOrElse(fail(s"node '$id' missing from payload"))

  private def mqField(p: Json, id: String): Option[Json] =
    nodeJson(p, id).hcursor.downField("mergeQueue").focus

  private def posField(p: Json, id: String): Option[Json] =
    nodeJson(p, id).hcursor.downField("mergeQueuePos").focus

  private def posInt(p: Json, id: String, field: String): Option[Int] =
    posField(p, id).flatMap(_.hcursor.get[Int](field).toOption)

  /** 位次读数对（position, total）——本批主判据的原始读数元组。 */
  private def posPair(p: Json, id: String): Option[(Int, Int)] =
    for
      a <- posInt(p, id, "position")
      b <- posInt(p, id, "total")
    yield (a, b)

  private def posStr(p: Json, id: String, field: String): Option[String] =
    posField(p, id).flatMap(_.hcursor.get[String](field).toOption)

  private def posBool(p: Json, id: String, field: String): Option[Boolean] =
    posField(p, id).flatMap(_.hcursor.get[Boolean](field).toOption)

  private def posLong(p: Json, id: String, field: String): Option[Long] =
    posField(p, id).flatMap(_.hcursor.get[Long](field).toOption)

  private def posKeys(p: Json, id: String): Set[String] =
    posField(p, id).flatMap(_.asObject).map(_.keys.toSet).getOrElse(Set.empty)

  private def aheadOf(p: Json, id: String): Option[Int] =
    mqField(p, id).flatMap(_.hcursor.get[Int]("ahead").toOption)

  private def holderField(p: Json, id: String, field: String): List[String] =
    mqField(p, id)
      .flatMap(_.hcursor.downField("holders").as[List[Json]].toOption)
      .getOrElse(Nil)
      .flatMap(_.hcursor.get[String](field).toOption)

  private def holderJsons(p: Json, id: String): List[Json] =
    mqField(p, id).flatMap(_.hcursor.downField("holders").as[List[Json]].toOption).getOrElse(Nil)

  private def mqKeys(p: Json, id: String): Set[String] =
    mqField(p, id).flatMap(_.asObject).map(_.keys.toSet).getOrElse(Set.empty)

  /**
   * 载荷里「队列成员」的位次读数（position → (id, readyAt, createdAt)），按 position 升序。
   * = 真源一致性复算的输入面（判据只用载荷自身字段，不引入第二真源）。
   */
  private def queueReadings(p: Json): List[(Int, String, Long, Long)] =
    p.hcursor
      .downField("nodes")
      .as[List[Json]]
      .getOrElse(Nil)
      .flatMap { nj =>
        for
          id <- nj.hcursor.get[String]("id").toOption
          pos <- nj.hcursor.downField("mergeQueuePos").get[Int]("position").toOption
          rankAt <- nj.hcursor.downField("mergeQueuePos").get[Long]("readyAt").toOption
          createdAt <- nj.hcursor.downField("mergeQueuePos").get[Long]("createdAt").toOption
        yield (pos, id, rankAt, createdAt)
      }
      .sortBy(_._1)

  /**
   * 取证落盘（**缺省开启**，落 `target/`（gitignored 构建目录））：把**引擎真实载荷**
   * 交给渲染面 fixture 用（`NEBFLOW_QUEUEPOS_DUMP=<abs>` 可改址，缺省
   * `target/queuepos-payload.json`）。🔴 渲染面据此断言真渲染读数，**禁手抄夹具**。
   */
  private def dumpPayload(p: Json): Unit =
    val path = sys.env
      .get("NEBFLOW_QUEUEPOS_DUMP")
      .filter(_.trim.nonEmpty)
      .getOrElse((os.pwd / "target" / "queuepos-payload.json").toString)
    val f = new java.io.File(path)
    Option(f.getParentFile).foreach(_.mkdirs())
    java.nio.file.Files.write(f.toPath, p.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))

  /** merge 节点（入口形态：无 in 无 deps ⇒ 到达时刻 readyAt = createdAt）。 */
  private def mergeNode(id: String, name: String, status: String, createdAt: Long): NodeDef =
    NodeDef(
      id = id,
      name = name,
      agent = "general",
      merge = true,
      task = Some(s"landing $name"),
      status = status,
      out = List(OutEdge.root),
      createdAt = createdAt
    )

  /** 持有者（临界区内运行；`running` 恒优先，与 rank 无关）。 */
  private def runningHolder(id: String, name: String, createdAt: Long): NodeDef =
    NodeDef(
      id = id,
      name = name,
      agent = "general",
      merge = true,
      task = Some(s"landing $name"),
      status = NodeLifecycle.Running,
      out = List(OutEdge.root),
      startedAt = Some(createdAt + 10L),
      createdAt = createdAt
    )

  private def mkGitRepo(dir: os.Path): Unit =
    os.makeDir.all(dir)
    os.proc("git", "init", "-b", "main", dir.toString).call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    os.proc("git", "-C", dir.toString, "config", "user.email", "spec@nebflow.local").call(check = true)
    os.proc("git", "-C", dir.toString, "config", "user.name", "spec").call(check = true)
    os.proc("git", "-C", dir.toString, "commit", "--allow-empty", "-m", "init")
      .call(check = true, stdout = os.Pipe, stderr = os.Pipe)

  private def gitAvailable: Boolean =
    try os.proc("git", "--version").call(check = false, stdout = os.Pipe, stderr = os.Pipe).exitCode == 0
    catch case _: Throwable => false

  // ── ① 作者现场复刻：旧面全同 / 新面互异（同一份载荷对读 = 改前红 + 改后绿）────

  test(
    "① author scenario: with one merge RUNNING and three waiters whose verdict gate is unmet, the OLD key's `ahead` is identical for all three (1/1/1 — the reported symptom) while `mergeQueuePos.position` is unique per node (2/3/4) and matches the SEM-2 rank order"
  ) {
    val ws = tempRoot / "ws-auth"; os.makeDir.all(ws)
    val system = ActorSystem(s"mqpos-auth-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot)
      rt <- mountProject("mqpos-auth", ws, system, res)
      // 现场形态（考古件 §A⑤(e)）：每个排队 sink 都有自己的 verify 上游，且未申报 pass
      // ⇒ 它们互相**不算**持有者（准入过滤），于是三者 holders 塌成同一个 ⇒ ahead 恒 1。
      verifier = NodeDef(
        id = "n-ver",
        name = "verify-r1",
        agent = "general",
        task = Some("verify"),
        status = NodeLifecycle.Completed,
        role = NodeRoles.Verifier,
        lastVerdict = Some(rt.engine.VerdictFail),
        result = Some("fail verdict"),
        out = Nil,
        createdAt = now - 20_000L,
        completedAt = Some(now - 8_500L)
      )
      _ <- seed(
        rt,
        runningHolder("n-hold", "hold-merge", now - 9_000L),
        verifier,
        mergeNode("n-q1", "queue-merge-1", NodeLifecycle.Wiring, now - 8_000L).copy(in = List("n-ver")),
        mergeNode("n-q2", "queue-merge-2", NodeLifecycle.Pending, now - 7_000L).copy(in = List("n-ver")),
        mergeNode("n-q3", "queue-merge-3", NodeLifecycle.Pending, now - 6_000L).copy(in = List("n-ver"))
      )
      p <- payload(rt)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      dumpPayload(p)
      // ── 改前红读数（旧面，同一份载荷）：三个排队者 ahead **全同 = 1** ──
      val aheads = List("n-q1", "n-q2", "n-q3").map(id => id -> aheadOf(p, id))
      assertEquals(
        aheads,
        List("n-q1" -> Some(1), "n-q2" -> Some(1), "n-q3" -> Some(1)),
        s"pre-change reading (RED): every waiter reports the SAME `ahead` — that is the author's symptom: $p"
      )
      assertEquals(
        aheads.flatMap(_._2).distinct.size,
        1,
        "pre-change reading: the old face cannot distinguish the three waiters at all"
      )
      // ── 改后绿读数（新面）：位次互异，且与 rank 序一致 ──
      val positions = List("n-q1", "n-q2", "n-q3").map(id => id -> posPair(p, id))
      assertEquals(
        positions,
        List("n-q1" -> Some((2, 4)), "n-q2" -> Some((3, 4)), "n-q3" -> Some((4, 4))),
        s"post-change reading (GREEN): each waiter carries its own 1-based rank (the running holder holds rank 1): $p"
      )
      assertEquals(
        positions.flatMap(_._2).map(_._1).distinct.size,
        positions.size,
        "post-change reading: the positions MUST be pairwise distinct (位次逐节点唯一)"
      )
      // 队列身份 + 次序键元数据（前端只读不派生）
      assertEquals(
        posStr(p, "n-q1", "queue"),
        Some(MergeMutexPolicy.QueueName),
        "the slot names the queue it belongs to (engine-side token, frontend only translates)"
      )
      assertEquals(
        posField(p, "n-q1").flatMap(_.hcursor.downField("rank").get[String]("primary").toOption),
        Some(MergeMutexPolicy.RankPrimary),
        "the rank basis is published next to the position"
      )
      // 已到达（上游全终态 ⇒ readyAt = max(completedAt)）：arrived = true，且 readyAt 可复算
      assertEquals(posBool(p, "n-q1", "arrived"), Some(true))
      assertEquals(
        posLong(p, "n-q1", "readyAt"),
        Some(now - 8_500L),
        "readyAt = max(upstream completedAt) — the rank primary key itself (auditable)"
      )
      // 在临界区者**不在队列显示面**（它是 rank 1，但不显示「排队中」）
      assertEquals(posField(p, "n-hold"), None, "the node inside the critical section is never 'queued'")
    end for
  }

  // ── ② 队首/无持有者（旧面「零显示」那一极）────────────────────────────

  test(
    "② no-display pole: three waiters with NO running holder and mutually verdict-blocked predecessors carry NO `mergeQueue` at all (0/3 on the old face) while `mergeQueuePos` still gives each one a unique rank (1/2/3 of 3)"
  ) {
    val ws = tempRoot / "ws-head"; os.makeDir.all(ws)
    val system = ActorSystem(s"mqpos-head-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot)
      rt <- mountProject("mqpos-head", ws, system, res)
      verifier = NodeDef(
        id = "n-ver",
        name = "verify-h",
        agent = "general",
        task = Some("verify"),
        status = NodeLifecycle.Completed,
        role = NodeRoles.Verifier,
        lastVerdict = None,
        out = Nil,
        createdAt = now - 20_000L,
        completedAt = Some(now - 8_500L)
      )
      _ <- seed(
        rt,
        verifier,
        mergeNode("n-h1", "head-merge-1", NodeLifecycle.Wiring, now - 8_000L).copy(in = List("n-ver")),
        mergeNode("n-h2", "head-merge-2", NodeLifecycle.Pending, now - 7_000L).copy(in = List("n-ver")),
        mergeNode("n-h3", "head-merge-3", NodeLifecycle.Pending, now - 6_000L).copy(in = List("n-ver"))
      )
      p <- payload(rt)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // 改前红读数（旧面）：三节点**全部无键** ⇒ 面板上「一片空白」（作者现场 6/6 同形）
      val oldFace = List("n-h1", "n-h2", "n-h3").map(id => id -> mqField(p, id))
      assertEquals(
        oldFace.map(_._2),
        List(None, None, None),
        s"pre-change reading (RED): not one waiter carries the old key — nothing is displayed: $p"
      )
      // 改后绿读数（新面）：位次照带，互异
      assertEquals(
        List("n-h1", "n-h2", "n-h3").map(id => id -> posPair(p, id)),
        List("n-h1" -> Some((1, 3)), "n-h2" -> Some((2, 3)), "n-h3" -> Some((3, 3))),
        s"post-change reading (GREEN): the queue head now names its own position too: $p"
      )
    end for
  }

  // ── ③ arrived 标志 + 未到达者排尾 ────────────────────────────────────

  test(
    "③ arrival flag: a waiter whose upstream is not terminal is still ranked (the queue stays fully readable) but is flagged arrived=false and sorts LAST (rank primary = MaxValue)"
  ) {
    val ws = tempRoot / "ws-arr"; os.makeDir.all(ws)
    val system = ActorSystem(s"mqpos-arr-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot)
      rt <- mountProject("mqpos-arr", ws, system, res)
      _ <- seed(
        rt,
        runningHolder("n-hold", "hold-merge", now - 9_000L),
        // 上游仍在跑 ⇒ 未到达（readyAt = MaxValue ⇒ 让位，排在所有已到达者之后）
        NodeDef(
          id = "n-up",
          name = "upstream-worker",
          agent = "coder",
          task = Some("work"),
          status = NodeLifecycle.Running,
          out = List(OutEdge("n-late")),
          createdAt = now - 30_000L
        ),
        mergeNode("n-early", "early-merge", NodeLifecycle.Pending, now - 8_000L),
        mergeNode("n-late", "late-merge", NodeLifecycle.Pending, now - 5_000L).copy(in = List("n-up"))
      )
      p <- payload(rt)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(posPair(p, "n-early"), Some((2, 3)), s"arrived waiter ranks right behind the holder: $p")
      assertEquals(posPair(p, "n-late"), Some((3, 3)), "the not-yet-arrived waiter is ranked last, not dropped")
      assertEquals(posBool(p, "n-early", "arrived"), Some(true))
      assertEquals(
        posBool(p, "n-late", "arrived"),
        Some(false),
        "arrived=false = its upstreams are not terminal yet (the frontend/reader must not read it as 'about to run')"
      )
      assertEquals(
        posLong(p, "n-late", "readyAt"),
        Some(Long.MaxValue),
        "readyAt = MaxValue is the rank primary key of a not-yet-arrived node (让位，不阻断)"
      )
    end for
  }

  // ── ④ 条件键纪律（零漂移：没有队列就没有键）──────────────────────────

  test(
    "④ conditional-key discipline: non-merge nodes, the running holder, terminal merges and blocked merges carry NO mergeQueuePos key; a SOLO merge (fewer than 2 contenders in its whole project) carries none either — while a waiter behind a running holder does"
  ) {
    val ws = tempRoot / "ws-nokey"; os.makeDir.all(ws)
    val loneWs = tempRoot / "ws-lone"; os.makeDir.all(loneWs)
    val system = ActorSystem(s"mqpos-nokey-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot)
      rt <- mountProject("mqpos-nokey", ws, system, res)
      lone <- mountProject("mqpos-lone", loneWs, system, res)
      _ <- seed(
        rt,
        NodeDef(
          id = "n-plain",
          name = "plain",
          agent = "coder",
          task = Some("work"),
          status = NodeLifecycle.Pending,
          out = List(OutEdge.root),
          createdAt = now - 3_000L
        ),
        runningHolder("n-hold", "hold-merge", now - 9_000L),
        mergeNode("n-done", "done-merge", NodeLifecycle.Completed, now - 8_000L).copy(completedAt = Some(now - 1_000L)),
        mergeNode("n-blocked", "blocked-merge", NodeLifecycle.Blocked, now - 7_000L),
        mergeNode("n-solo", "solo-merge", NodeLifecycle.Pending, now - 6_000L)
      )
      _ <- seed(lone, mergeNode("n-only", "only-merge", NodeLifecycle.Pending, now - 5_000L))
      p <- payload(rt)
      pLone <- payload(lone)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      for id <- List("n-plain", "n-hold", "n-done", "n-blocked") do
        assertEquals(
          posField(p, id),
          None,
          s"'$id' must carry NO mergeQueuePos (non-merge / in the critical section / terminal / blocked): ${nodeJson(p, id)}"
        )
      // 单人（该项目唯一 merge 节点）= 竞争者不足 ⇒ 不显示「排队中」（禁假陈述）
      assertEquals(
        posField(pLone, "n-only"),
        None,
        s"a lone merge node is not 'queued' (no competitor at all): ${nodeJson(pLone, "n-only")}"
      )
      // 反向锚（禁「一律不带」式假绿）：一个 running + 一个排队 = 2 个竞争者 ⇒ 该在队里
      assertEquals(
        posPair(p, "n-solo"),
        Some((2, 2)),
        s"a solo waiter behind a running holder IS a queue of 2 (the negative anchor against a blanket suppression): $p"
      )
      assertEquals(posPair(pLone, "n-only"), None)
    end for
  }

  test(
    "④b two-node queue: with two open merges and nothing running, the head carries rank 1 and the later one rank 2 — the queue exists WITHOUT any holder"
  ) {
    val ws = tempRoot / "ws-two"; os.makeDir.all(ws)
    val system = ActorSystem(s"mqpos-two-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot)
      rt <- mountProject("mqpos-two", ws, system, res)
      _ <- seed(
        rt,
        mergeNode("n-a", "merge-a", NodeLifecycle.Wiring, now - 8_000L),
        mergeNode("n-b", "merge-b", NodeLifecycle.Pending, now - 6_000L)
      )
      p <- payload(rt)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(posPair(p, "n-a"), Some((1, 2)), s"the head of the queue is rank 1: $p")
      assertEquals(posPair(p, "n-b"), Some((2, 2)))
      // 旧面此刻对 **两者** 都无键（队首没人挡、次位者前面的开态先到者也算持有者吗？——
      // n-b 的 holders = {n-a} 非空 ⇒ 有键，故这里断言的是「n-a 无键」这一极）
      assertEquals(
        mqField(p, "n-a"),
        None,
        "the head has no holder => the old key cannot represent it at all (why the new key exists)"
      )
      assertEquals(aheadOf(p, "n-b"), Some(1))
    end for
  }

  // ── ⑤ 既有 mergeQueue 键零漂移（只增键）────────────────────────────

  test(
    "⑤ zero drift on the frozen key: `mergeQueue` keeps EXACTLY {ahead,inSection,rank,holders} and its literal values, and now coexists with `mergeQueuePos` on the same node"
  ) {
    val ws = tempRoot / "ws-drift"; os.makeDir.all(ws)
    val system = ActorSystem(s"mqpos-drift-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot)
      rt <- mountProject("mqpos-drift", ws, system, res)
      _ <- seed(
        rt,
        runningHolder("n-holder", "attach-merge", now - 8000L),
        mergeNode("n-wait", "docs-merge", NodeLifecycle.Pending, now - 3000L)
      )
      p <- payload(rt)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(
        mqKeys(p, "n-wait") - "sameKeyProjects",
        Set("ahead", "inSection", "rank", "holders"),
        s"the frozen key's field set must be untouched by this batch: ${mqField(p, "n-wait")}"
      )
      assertEquals(aheadOf(p, "n-wait"), Some(1), "ahead keeps its literal meaning (the size of the blocking set)")
      assertEquals(holderField(p, "n-wait", "id"), List("n-holder"))
      assertEquals(
        holderJsons(p, "n-wait").map(_.asObject.map(_.keys.toSet).getOrElse(Set.empty[String])).toList,
        List(Set("id", "name", "status", "readyAt", "createdAt", "inSection", "notStartedReason")),
        s"holder entries keep their field set: ${holderJsons(p, "n-wait")}"
      )
      // 只增键：同一节点上两键并存，且各自字段集互不污染
      assertEquals(
        posKeys(p, "n-wait") - "sameKeyProjects",
        Set("position", "total", "queue", "arrived", "readyAt", "createdAt", "rank"),
        s"the new key carries exactly the declared fields: ${posField(p, "n-wait")}"
      )
      assertEquals(posPair(p, "n-wait"), Some((2, 2)))
    end for
  }

  // ── ⑥ 纯派生（持久面零漂移）────────────────────────────────────────

  test(
    "⑥ derived only: `mergeQueuePos` is never persisted on NodeDef (it does not enter flow-map.json / the archive batch)"
  ) {
    val ws = tempRoot / "ws-persist"; os.makeDir.all(ws)
    val system = ActorSystem(s"mqpos-persist-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot)
      rt <- mountProject("mqpos-persist", ws, system, res)
      _ <- seed(
        rt,
        runningHolder("n-hold", "hold-merge", now - 8_000L),
        mergeNode("n-a", "merge-a", NodeLifecycle.Pending, now - 6_000L),
        mergeNode("n-b", "merge-b", NodeLifecycle.Pending, now - 5_000L)
      )
      p <- payload(rt)
      stored <- rt.store.getNode("n-a")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(posPair(p, "n-a"), Some((2, 3)), "precondition: the node is in the queue")
      val persisted = stored.getOrElse(fail("n-a must exist")).asJson
      assertEquals(
        persisted.hcursor.downField("mergeQueuePos").focus,
        None,
        s"mergeQueuePos must never be persisted on NodeDef: $persisted"
      )
      assertEquals(persisted.hcursor.downField("mergeQueue").focus, None)
    end for
  }

  // ── ⑦ 同键多项目（O-1）= 位次降级信号 ──────────────────────────────

  test(
    "⑦ same-key multi-project (O-1): the position slot carries sameKeyProjects too, so the frontend degrades to a bare 'queued' with no number"
  ) {
    val repo = tempRoot / "q7-repo"
    val wt = tempRoot / "q7-wt"
    if !gitAvailable then IO.unit
    else
      val system = ActorSystem(s"mqpos-o1-${scala.util.Random.nextInt(100000)}")
      val now = System.currentTimeMillis()
      for
        _ <- IO {
          mkGitRepo(repo)
          os.proc("git", "-C", repo.toString, "worktree", "add", wt.toString, "-b", "q7-branch")
            .call(check = true, stdout = os.Pipe, stderr = os.Pipe)
        }
        res <- mkResources(system, tempRoot)
        rtA <- mountProject("mqpos-o1-a", repo, system, res)
        rtB <- mountProject("mqpos-o1-b", wt, system, res)
        kA <- MergeMutexPolicy.keyOf(repo.toString)
        kB <- MergeMutexPolicy.keyOf(wt.toString)
        _ <- seed(
          rtA,
          runningHolder("a-holder", "merge-a", now - 8_000L),
          mergeNode("a-wait", "merge-a-wait", NodeLifecycle.Pending, now - 7_000L)
        )
        _ <- seed(
          rtB,
          runningHolder("b-holder", "merge-b", now - 8_000L),
          mergeNode("b-wait", "merge-b-wait", NodeLifecycle.Pending, now - 6_000L),
          mergeNode("b-wait2", "merge-b-wait2", NodeLifecycle.Pending, now - 5_000L)
        )
        pA <- payload(rtA)
        pB <- payload(rtB)
        _ <- system.stopAll.handleErrorWith(_ => IO.unit)
      yield
        assertEquals(kB, kA, "fixture sanity: the worktree and the main repo share one git dir key")
        assertEquals(posPair(pB, "b-wait"), Some((2, 3)))
        assertEquals(
          posField(pB, "b-wait").flatMap(_.hcursor.downField("sameKeyProjects").as[List[String]].toOption),
          Some(List("mqpos-o1-a")),
          "the O-1 situation MUST be visible on the position slot (frontend degrades: no number rendered)"
        )
        // 对称面：同键两边**互相**可见（位次在任一项目里都不可信，禁只标一边）
        assertEquals(posPair(pA, "a-wait"), Some((2, 2)))
        assertEquals(
          posField(pA, "a-wait").flatMap(_.hcursor.downField("sameKeyProjects").as[List[String]].toOption),
          Some(List("mqpos-o1-b")),
          "the signal is symmetric: both projects sharing the git dir must degrade"
        )
      end for
    end if

  }

  // ── ⑧ 真源一致性（位次序 ≡ rank 复算序）────────────────────────────

  test(
    "⑧ truth-source consistency: the published positions are pairwise distinct and their order is EXACTLY the rank order recomputed from the payload's own (readyAt, createdAt, id) triples (a running holder holds a rank without a slot — hence the gap)"
  ) {
    val ws = tempRoot / "ws-cons"; os.makeDir.all(ws)
    val system = ActorSystem(s"mqpos-cons-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot)
      rt <- mountProject("mqpos-cons", ws, system, res)
      _ <- seed(
        rt,
        runningHolder("n-hold", "hold-merge", now - 9_000L),
        NodeDef(
          id = "n-ver",
          name = "verify-c",
          agent = "general",
          task = Some("verify"),
          status = NodeLifecycle.Completed,
          role = NodeRoles.Verifier,
          lastVerdict = Some(rt.engine.VerdictFail),
          out = Nil,
          createdAt = now - 20_000L,
          completedAt = Some(now - 4_500L)
        ),
        // createdAt 序与 readyAt 序**故意互异**（verify-spec FIFO 的 SEM-2 面）：
        // c3 到得最晚（readyAt 最大）但 createdAt 最小 ⇒ 位次必须按 (readyAt, createdAt, id)。
        mergeNode("n-c1", "cons-merge-1", NodeLifecycle.Wiring, now - 8_000L).copy(in = List("n-ver")),
        mergeNode("n-c2", "cons-merge-2", NodeLifecycle.Pending, now - 7_000L).copy(in = List("n-ver")),
        mergeNode("n-c3", "cons-merge-3", NodeLifecycle.Pending, now - 9_500L)
      )
      p <- payload(rt)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      val readings = queueReadings(p)
      // 位次**互异**（本批核心断言）——注意位次里的「洞」是有意的：临界区内那个节点占
      // 队列首位（position=2 这里因 c3 抢到 1 而空出 3? 见下）却不显示「排队中」，
      // 故显示面可能看到 (1,3,4)：它是**真实执行序的序数**，不是「显示计数」。
      assertEquals(
        readings.map(_._1).distinct.size,
        readings.size,
        s"the published positions MUST be pairwise distinct: $readings"
      )
      val byRank = readings.sortBy(r => (r._3, r._4, r._2)).map(_._2)
      assertEquals(
        readings.map(_._2),
        byRank,
        s"the published order MUST equal the rank order (readyAt, createdAt, id) recomputed from the payload itself: $readings"
      )
      // 具体读数（真源一致性对照表的可复算面）：c3 无上游 ⇒ 到达最早（readyAt = createdAt
      // = -9500）⇒ 位次 1；临界区内的 hold 占 2（**不显示**）；c1/c2 的 readyAt 都 = 上游
      // verifier 的 completedAt（-4500），平局按 createdAt 分先后 ⇒ 3 / 4。
      assertEquals(
        readings.map(r => (r._1, r._2)),
        List((1, "n-c3"), (3, "n-c1"), (4, "n-c2")),
        s"position ⇄ node mapping (the running holder occupies rank 2 without a queue slot): $p"
      )
      assertEquals(posPair(p, "n-c3"), Some((1, 4)), s"earliest ARRIVAL ranks first even with the newest createdAt: $p")
      assertEquals(posPair(p, "n-c1"), Some((3, 4)))
      assertEquals(posPair(p, "n-c2"), Some((4, 4)))
      assertEquals(
        posPair(p, "n-hold"),
        None,
        "the node inside the critical section holds a rank but is not displayed as queued (hence the gap)"
      )
    end for
  }

end MergeQueuePositionSpec
