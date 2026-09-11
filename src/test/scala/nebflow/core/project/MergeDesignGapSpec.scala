package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.{Json, JsonObject}
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
 * 合并节点设计缺口修复批（2026-09-11 作者 13:1x 拍板）**引擎面**单测。
 *
 * 覆盖：
 *  - **P2① 定位键**：`NodeEngine.ownsSession` —— 全部挂载项目共享同一
 *    `rootSessionId`（生产形态）时，归属判定必须只对**持有该会话**的 store 返回 true
 *    （旧判据 `rt.engine.rootSessionId == rec.rootSessionId` 在此形态下恒真 ⇒ 歧义）。
 *  - **P2② 兜底腿**：`settleFailedHardResume` 的「活动区查无」出口——归档区可定位 ⇒
 *    反向 prune `in` + `pendingSuccession` + `nodeUpdated` + 事件流；两区皆无 ⇒
 *    ERROR + 事件流留痕（零动作但不静默）。**「能定位节点」分支逐字不变**（D5 不摘除）
 *    ——由 CancelDeadlockFixSpec 的 R5 用例继续守。
 *  - **U5/E 不一致拓扑**：out 已改接 Nebula（无节点目标）而下游 `in` 仍引用本节点时，
 *    取消节点（`reapStaleRunning` → `cancelNode(detach=true)`）必须 prune 反向引用，
 *    不得静默零操作（该 helper 的重入零写幂等由 P2②-a 的重放断言承担）。
 *  - **U2/P1 硬闸**：`NODE_MERGE_IN_CAP`（merge in ≤4，create/edit 双路径）与
 *    `NODE_MERGE_FIRED_NO_IN`（running/blocked/completed 禁追加 in；wiring/pending 放行；
 *    同参回显不误拒）。
 */
class MergeDesignGapSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-merge-gap"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "general")
  os.write.over(tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"merge-gap spec agent","tools":[],"category":"standalone"}""")
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  // ── 装配（与 CancelDeadlockFixSpec 同款，零 LLM 调用）─────────────

  private class StubLlm:
    def handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(req: LlmRequest, onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None): Stream[IO, StreamChunk] =
        Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

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
      llm = new StubLlm().handle,
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

  /** 挂载一个 runtime。`rootSessionId` 显式可注 —— 复现生产形态（**同一 rootSessionId
    * 挂载多个项目**）的唯一途径。 */
  private def mountProject(
    name: String,
    ws: os.Path,
    system: ActorSystem,
    res: SharedResources,
    rootSessionId: String = "nebula-root",
    frames: Ref[IO, List[Json]] = Ref.unsafe[IO, List[Json]](Nil),
    notified: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)
  ): IO[ProjectRuntime] =
    for
      store <- FlowMapStore.open(name, ws.toString)
      engine = new NodeEngine(
        store, system, res,
        wsSendFn = (j: Json) => frames.update(_ :+ j),
        workspace = ws.toString,
        rootSessionId = rootSessionId,
        projectName = name,
        emitEvent = (typ: String, nodeId: String, payload: Json) =>
          frames.update(_ :+ payload.deepMerge(Json.obj(
            "type" -> Json.fromString(typ), "nodeId" -> Json.fromString(nodeId)))),
        // 通知触发器接缝（与 CancelDeadlockFixSpec 同款）：不 spawn 分发会话，只记账
        notifyTriggerOverride = Some((text: String) => notified.update(_ :+ text))
      )
      pd = ProjectDef(name = name, workspace = ws.toString, agentFile = (ws / "AGENTS.md").toString,
        createdAt = System.currentTimeMillis())
      rt = ProjectRuntime(pd, store, engine, system, res, None)
      _ <- ProjectRuntimeRegistry.register(rt)
    yield rt

  private def seed(store: FlowMapStore, n: NodeDef): IO[Unit] =
    store.mutate(s => s.copy(nodes = s.nodes + (n.id -> n))).void

  private def seedArchive(store: FlowMapStore, n: NodeDef): IO[Unit] =
    store.mutateArchive(a => a.copy(nodes = a.nodes + (n.id -> n))).void

  private def node(rt: ProjectRuntime, id: String): IO[NodeDef] =
    rt.store.getNode(id).map(_.getOrElse(fail(s"node '$id' must exist")))

  private def readAudit(ws: os.Path): IO[List[(String, String, String)]] =
    IO.blocking(os.read(ws / ".nebflow" / FlowMapEventLog.FileName))
      .map(_.linesIterator.toList.filter(_.trim.nonEmpty))
      .map(_.flatMap(l => jsonParse(l).toOption.map(j => (
        j.hcursor.get[String]("type").getOrElse(""),
        j.hcursor.get[String]("nodeId").getOrElse(""),
        j.hcursor.get[String]("summary").getOrElse("")))))
      .handleError(_ => Nil)

  private def mkCtx(res: SharedResources, system: ActorSystem, ws: String): ToolContext =
    ToolContext(projectRoot = ws, sessionId = Some("mgap-sid"), rootSessionId = Some("nebula-root"),
      sharedResources = Some(res), actorSystem = Some(system))

  private def nodeEdit(input: Json, ctx: ToolContext): IO[Either[String, String]] =
    NodeEditTool.call(input.asObject.get, ctx).map(_.left.map(_.message))

  private def nodeInput(project: String, nodename: String, extra: (String, Json)*): Json =
    Json.obj(("project" -> Json.fromString(project)) :: ("nodename" -> Json.fromString(nodename)) :: extra.toList*)

  private def nid(suffix: String): String = s"n-$suffix"

  private def upstream(id: String, status: String): NodeDef =
    NodeDef(id = id, name = s"up-$id", agent = "general", status = status,
      sessionRef = None, createdAt = System.currentTimeMillis() - 100_000L)

  // ── P2①：会话归属判定（生产形态 = 多项目共享同一 rootSessionId）────

  test("P2①: ownsSession is per-store — two projects mounted under the SAME rootSessionId claim only their own session (old rootSessionId key is ambiguous by construction)") {
    val system = ActorSystem(s"mgap-own-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot)
      wsA = tempRoot / "ws-own-a"; _ <- IO(os.makeDir.all(wsA))
      wsB = tempRoot / "ws-own-b"; _ <- IO(os.makeDir.all(wsB))
      // 生产形态复现：启动挂载给**所有**项目同一个 rootSessionId（GatewayMain.startupMount
      // → mountAll(projects, rootSid, …)）——这是旧判据歧义的构造性前提。
      rtA <- mountProject("mgap-a", wsA, system, res, rootSessionId = "shared-root-sid")
      rtB <- mountProject("mgap-b", wsB, system, res, rootSessionId = "shared-root-sid")
      // 会话 node-sess-a 只属于 A 的节点；B 只有别的会话
      _ <- seed(rtA.store, NodeDef(id = nid("a"), name = "A", agent = "general",
        status = NodeLifecycle.Running, sessionRef = Some("node-sess-a"), createdAt = 1L))
      _ <- seed(rtB.store, NodeDef(id = nid("b"), name = "B", agent = "general",
        status = NodeLifecycle.Running, sessionRef = Some("node-sess-b"), createdAt = 1L))
      // 旧判据：两者恒真（不可区分）——这正是「命错引擎」的结构性根因
      sameKey <- IO.pure(rtA.engine.rootSessionId == rtB.engine.rootSessionId)
      // 新判据：按 store 归属
      aOwnsA <- rtA.engine.ownsSession("node-sess-a")
      aOwnsB <- rtA.engine.ownsSession("node-sess-b")
      bOwnsA <- rtB.engine.ownsSession("node-sess-a")
      bOwnsB <- rtB.engine.ownsSession("node-sess-b")
      ghost <- rtA.engine.ownsSession("node-sess-ghost")
      // sessionRefVerify（loop 节点 verify 会话）同判据
      _ <- seed(rtA.store, NodeDef(id = nid("lv"), name = "LV", agent = "general",
        status = NodeLifecycle.Running, sessionRefVerify = Some("node-sess-verify"), createdAt = 2L))
      verifyOwned <- rtA.engine.ownsSession("node-sess-verify")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(sameKey, "the production shape (all mounts share one rootSessionId) must be reproduced by the fixture")
      assert(aOwnsA, "A must own its own session")
      assert(!aOwnsB, "A must NOT own B's session — the old rootSessionId key would have said yes")
      assert(!bOwnsA, "B must NOT own A's session")
      assert(bOwnsB, "B must own its own session")
      assert(!ghost, "an unbound session must not be claimed by anyone")
      assert(verifyOwned, "sessionRefVerify must count as ownership (loop verify session)")
  }

  // ── P2②：查无节点出口的兜底腿 ────────────────────────────────────

  test("P2②-a: settleFailedHardResume with the node out of the active region (archived) performs the LATE cancelled-side detach — reverse prune of in + pendingSuccession + nodeUpdated + hard-recovery audit") {
    val ws = tempRoot / "ws-late"; os.makeDir.all(ws)
    val system = ActorSystem(s"mgap-late-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    val frames = Ref.unsafe[IO, List[Json]](Nil)
    for
      res <- mkResources(system, tempRoot)
      rt <- mountProject("mgap-late", ws, system, res, frames = frames)
      // 节点已出活动区（归档），但下游仍在活动区且 in 仍引用它（不一致拓扑的极端形态：
      // 归档判据按分量，引用边单侧存在时两者可异区）
      _ <- seedArchive(rt.store, NodeDef(id = nid("x"), name = "X", agent = "general",
        status = NodeLifecycle.Cancelled, sessionRef = Some("node-sess-x"),
        result = Some("cancelled[source=engine]: reason=stuck (L3 hard-recovery: …)"),
        out = List(OutEdge.nebula), notifySentAt = Some(now - 1_000L),
        createdAt = now - 900_000L, completedAt = Some(now - 800_000L)))
      _ <- seed(rt.store, NodeDef(id = nid("d"), name = "D", agent = "general",
        status = NodeLifecycle.Pending, in = List(nid("x")), createdAt = now - 700_000L))
      // 无归属会话（同一调用会先走 lateDetach；此会话名与归档节点 sessionRef 不同 ⇒ 走 ERROR 腿）
      _ <- rt.engine.settleFailedHardResume("node-sess-nobody")
      d0 <- node(rt, nid("d"))
      audit0 <- readAudit(ws)
      // 真正的兜底腿：会话可解析到归档节点
      _ <- rt.engine.settleFailedHardResume("node-sess-x")
      d <- node(rt, nid("d"))
      arch <- rt.store.archiveSnapshot
      audit <- readAudit(ws)
      f <- frames.get
      // 幂等重放：零写零帧
      _ <- rt.engine.settleFailedHardResume("node-sess-x")
      d2 <- node(rt, nid("d"))
      f2 <- frames.get
      audit2 <- readAudit(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // ① ERROR 腿（两区皆无）：回退出「有账」——事件流一条 hard-recovery，零动作
      val orphan = audit0.filter(r => r._1 == "hard-recovery" && r._2 == "")
      assertEquals(orphan.size, 1, s"the unlocatable leg must leave exactly one audit line, got ${audit0}")
      assert(orphan.head._3.contains("no node owns this session"), s"orphan audit text self-explanatory, got ${orphan.head._3}")
      assertEquals(d0.in, List(nid("x")), "the orphan leg must not touch any node (nothing to reference)")
      // ② 归档可定位 ⇒ 迟到摘除：prune + 待承接 + 帧 + 事件
      assertEquals(d.in, Nil, s"U3/B: the archived node's active referrer must have its in-mirror pruned, got ${d.in}")
      assertEquals(d.pendingSuccession, List(nid("x")),
        s"U3/B: the referrer must be marked pendingSuccession (待承接), got ${d.pendingSuccession}")
      assert(arch.nodes.get(nid("x")).exists(_.notifySentAt.isEmpty),
        "the L3 notify hold must be released for the archived node (no stale dedup marker left behind)")
      assert(f.exists(j => j.hcursor.get[String]("nodeId").toOption.contains(nid("d"))),
        s"U3/B: the pruned referrer must get a nodeUpdated frame, got ${f.map(_.noSpaces.take(80))}")
      val late = audit.filter(r => r._1 == "hard-recovery" && r._2 == nid("x"))
      assertEquals(late.size, 1, s"exactly one late-detach audit line expected, got ${audit.filter(_._1 == "hard-recovery")}")
      assert(late.head._3.contains("cancelled-side R4 detach applied late"), s"audit text must state the late detach: ${late.head._3}")
      assert(late.head._3.contains(nid("d")), s"audit text must name the pruned referrer: ${late.head._3}")
      // ③ 幂等：重放零写零帧（U5 的「不破坏重入幂等」）
      assertEquals(d2.in, Nil, "replay must be a no-op (idempotent)")
      assertEquals(d2.pendingSuccession, List(nid("x")), "replay must not duplicate the pendingSuccession marker")
      assertEquals(f2.size, f.size, "replay must not emit extra frames")
      assertEquals(audit2.size, audit.size + 1, "replay appends only its own audit line (audit is append-only by design)")
  }

  test("P2②-b: settleFailedHardResume's LOCATED node path is unchanged — re-judged failed, zero detach, zero pendingSuccession (D5 red line)") {
    val ws = tempRoot / "ws-located"; os.makeDir.all(ws)
    val system = ActorSystem(s"mgap-loc-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot)
      rt <- mountProject("mgap-located", ws, system, res)
      _ <- seed(rt.store, NodeDef(id = nid("u"), name = "U", agent = "general",
        status = NodeLifecycle.Cancelled, sessionRef = Some("node-sess-loc"),
        result = Some("cancelled[source=engine]: reason=stuck (L3 hard-recovery: …)"),
        out = List(OutEdge(nid("d"))), createdAt = now - 900_000L, completedAt = Some(now - 800_000L)))
      _ <- seed(rt.store, NodeDef(id = nid("d"), name = "D", agent = "general",
        status = NodeLifecycle.Pending, in = List(nid("u")), createdAt = now - 700_000L))
      _ <- rt.engine.settleFailedHardResume("node-sess-loc")
      u <- node(rt, nid("u"))
      d <- node(rt, nid("d"))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(u.status, NodeLifecycle.Failed, "located node must be re-judged as failed (R5 方案 4)")
      assertEquals(u.out, List(OutEdge(nid("d"))), "R4 red line: failed side must NOT detach")
      assertEquals(d.in, List(nid("u")), "failed side must keep the downstream in mirror (D5 stop-wait)")
      assertEquals(d.pendingSuccession, Nil, "R4 red line: failed side must NOT write 待承接")
  }

  // ── U5/E：不一致拓扑下取消 → 反向 prune（旧口径静默零操作）────────

  test("U5/E: cancelling a node whose out holds no node target (rewired to Nebula) STILL prunes an active downstream that mirrors it in `in` — reverse scan, idempotent") {
    val ws = tempRoot / "ws-u5"; os.makeDir.all(ws)
    val system = ActorSystem(s"mgap-u5-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    val frames = Ref.unsafe[IO, List[Json]](Nil)
    for
      res <- mkResources(system, tempRoot)
      rt <- mountProject("mgap-u5", ws, system, res, frames = frames)
      // 不一致拓扑：out 已改接 Nebula（只剩 Nebula 边），下游 in 仍镜像引用本节点
      _ <- seed(rt.store, NodeDef(id = nid("c"), name = "C", agent = "general",
        status = NodeLifecycle.Running, out = List(OutEdge.nebula),
        startedAt = Some(now - 60_000L), createdAt = now - 60_000L))
      _ <- seed(rt.store, NodeDef(id = nid("m"), name = "M", agent = "general", merge = true,
        status = NodeLifecycle.Pending, in = List(nid("c")), out = List(OutEdge.nebula),
        createdAt = now - 50_000L))
      r1 <- rt.engine.reapStaleRunning(nid("c"))
      c <- node(rt, nid("c"))
      m <- node(rt, nid("m"))
      f1 <- frames.get
      // 稳态复读：prune 后无悬空引用（重入零写的判据由同一 helper 的 P2②-a 重放用例承担）
      m2 <- node(rt, nid("m"))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r1.isRight, s"the seeded dead running node must be reaped: $r1")
      assertEquals(c.status, NodeLifecycle.Cancelled, "reap finalizes the node as cancelled")
      // U5/E 核心断言：旧口径 targets.isEmpty ⇒ 静默零操作；现在必须 prune
      assertEquals(m.in, Nil, s"U5/E red: the downstream in-mirror must be pruned even when out has no node target, got ${m.in}")
      assertEquals(m.pendingSuccession, List(nid("c")),
        s"U5/E red: the downstream must be marked 待承接, got ${m.pendingSuccession}")
      assert(f1.exists(j => j.hcursor.get[String]("nodeId").toOption.contains(nid("m"))),
        "the pruned downstream must get a nodeUpdated frame (visibility contract)")
      assertEquals(m2.in, Nil, "stable state after the prune (no dangling reference left behind)")
      assertEquals(m2.pendingSuccession, List(nid("c")), "the marker is written exactly once (distinct)")
  }

  // ── U2/P1：merge 硬闸 ───────────────────────────────────────────

  test("C-⑥: NODE_MERGE_IN_CAP — create with 5 upstreams refused, 4 accepted; edit beyond 4 refused, =4 accepted") {
    val ws = tempRoot / "ws-cap"; os.makeDir.all(ws)
    val system = ActorSystem(s"mgap-cap-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot)
      rt <- mountProject("mgap-cap", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- (1 to 6).toList.traverse_(i => seed(rt.store, upstream(nid(s"u$i"), NodeLifecycle.Running)))
      ids = (1 to 6).toList.map(i => nid(s"u$i"))
      // create：5 → 拒（边界 =4 合法 / =5 拒）
      c5 <- nodeEdit(nodeInput("mgap-cap", "m5", "task" -> Json.fromString("merge"),
        "description" -> Json.fromString("cap probe 5 upstreams"),
        "out" -> Json.fromString("Nebula"), "merge" -> Json.fromBoolean(true),
        "in" -> Json.fromString(ids.take(5).mkString(","))), ctx)
      // create：4 → 合法
      c4 <- nodeEdit(nodeInput("mgap-cap", "m4", "task" -> Json.fromString("merge"),
        "description" -> Json.fromString("cap probe 4 upstreams"),
        "out" -> Json.fromString("Nebula"), "merge" -> Json.fromBoolean(true),
        "in" -> Json.fromString(ids.take(4).mkString(","))), ctx)
      // edit：3 → 4 合法（未触发 wiring 期追加是正常回流）
      _ <- seed(rt.store, NodeDef(id = nid("e3"), name = "e3", agent = "general", merge = true,
        status = NodeLifecycle.Wiring, in = List(ids(0), ids(1), ids(2)),
        out = List(OutEdge.nebula), createdAt = now - 1_000L))
      e3ok <- nodeEdit(nodeInput("mgap-cap", "e3", "in" -> Json.fromString(ids(3))), ctx)
      e3 <- node(rt, nid("e3"))
      // edit：4 → 5 拒
      e4bad <- nodeEdit(nodeInput("mgap-cap", "e3", "in" -> Json.fromString(ids(4))), ctx)
      e3after <- node(rt, nid("e3"))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(c5.isLeft && c5.left.exists(_.contains("NODE_MERGE_IN_CAP")),
        s"C-⑥: a 5-upstream merge create must be refused with NODE_MERGE_IN_CAP, got $c5")
      assert(c5.left.exists(_.contains("at most 4")), s"C-⑥: the message must state the cap, got $c5")
      assert(c4.isRight, s"C-⑥: a 4-upstream merge create is the legal boundary and must pass, got $c4")
      assert(e3ok.isRight, s"C-⑥: appending the 4th upstream (un-triggered wiring merge) must pass, got $e3ok")
      assertEquals(e3.in.sorted, List(ids(0), ids(1), ids(2), ids(3)).sorted, "the 4th upstream must be persisted")
      assert(e4bad.isLeft && e4bad.left.exists(_.contains("NODE_MERGE_IN_CAP")),
        s"C-⑥: appending a 5th upstream must be refused with NODE_MERGE_IN_CAP, got $e4bad")
      assertEquals(e3after.in.sorted, List(ids(0), ids(1), ids(2), ids(3)).sorted,
        "a refused edit must leave the ledger untouched (zero side effects)")
  }

  test("C-⑧: NODE_MERGE_FIRED_NO_IN — running/blocked/completed merge refuses new in; wiring/pending accepts; re-sending existing in is not 'adding'") {
    val ws = tempRoot / "ws-fired"; os.makeDir.all(ws)
    val system = ActorSystem(s"mgap-fired-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    def mkNode(id: String, status: String, in: List[String], merge: Boolean = true): NodeDef =
      NodeDef(id = id, name = id, agent = "general", merge = merge, status = status,
        in = in, out = List(OutEdge.nebula), createdAt = now - 1_000L)
    for
      res <- mkResources(system, tempRoot)
      rt <- mountProject("mgap-fired", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- (1 to 6).toList.traverse_(i => seed(rt.store, upstream(nid(s"u$i"), NodeLifecycle.Running)))
      ids = (1 to 6).toList.map(i => nid(s"u$i"))
      _ <- seed(rt.store, mkNode(nid("run"), NodeLifecycle.Running, List(ids(0))))
      _ <- seed(rt.store, mkNode(nid("blk"), NodeLifecycle.Blocked, List(ids(0))))
      _ <- seed(rt.store, mkNode(nid("cmp"), NodeLifecycle.Completed, List(ids(0))))
      _ <- seed(rt.store, mkNode(nid("wir"), NodeLifecycle.Wiring, List(ids(0))))
      _ <- seed(rt.store, mkNode(nid("pen"), NodeLifecycle.Pending, List(ids(0))))
      // 非 merge 节点不受此闸（本批唯一的引擎新增校验面 = merge）
      _ <- seed(rt.store, mkNode(nid("plain"), NodeLifecycle.Running, List(ids(0)), merge = false))
      r1 <- nodeEdit(nodeInput("mgap-fired", nid("run"), "in" -> Json.fromString(ids(1))), ctx)
      r2 <- nodeEdit(nodeInput("mgap-fired", nid("blk"), "in" -> Json.fromString(ids(1))), ctx)
      r3 <- nodeEdit(nodeInput("mgap-fired", nid("cmp"), "in" -> Json.fromString(ids(1))), ctx)
      r4 <- nodeEdit(nodeInput("mgap-fired", nid("wir"), "in" -> Json.fromString(ids(1))), ctx)
      r5 <- nodeEdit(nodeInput("mgap-fired", nid("pen"), "in" -> Json.fromString(ids(1))), ctx)
      r6 <- nodeEdit(nodeInput("mgap-fired", nid("plain"), "in" -> Json.fromString(ids(1))), ctx)
      // 同参回显（分发器幂等重发既有 in）不算「加 in」
      r7 <- nodeEdit(nodeInput("mgap-fired", nid("blk"), "in" -> Json.fromString(ids(0))), ctx)
      wir <- node(rt, nid("wir"))
      pen <- node(rt, nid("pen"))
      plain <- node(rt, nid("plain"))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      for (r, st) <- List((r1, "running"), (r2, "blocked"), (r3, "completed")) do
        assert(r.isLeft && r.left.exists(_.contains("NODE_MERGE_FIRED_NO_IN")),
          s"C-⑧: a $st merge must refuse new in with NODE_MERGE_FIRED_NO_IN, got $r")
      assert(r4.isRight, s"C-⑧: wiring (un-triggered) merge must still accept appended in, got $r4")
      assert(r5.isRight, s"C-⑧: pending (un-triggered) merge must still accept appended in, got $r5")
      assert(r6.isRight, s"C-⑧: the gate is merge-only — a plain running node still accepts in, got $r6")
      assert(r7.isRight, s"C-⑧: echoing the existing in (no new upstream) must NOT be refused, got $r7")
      assertEquals(wir.in.sorted, List(ids(0), ids(1)).sorted, "wiring merge gets the appended upstream")
      assertEquals(pen.in.sorted, List(ids(0), ids(1)).sorted, "pending merge gets the appended upstream")
      assertEquals(plain.in.sorted, List(ids(0), ids(1)).sorted, "plain node keeps append-only in semantics")
  }

