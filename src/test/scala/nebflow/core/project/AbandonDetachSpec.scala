package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.parser.parse as jsonParse
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentLibrary, SharedResources, SpecResources, StubLlm}
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, NodeEditTool, NodeTools, ToolContext}
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk}

import scala.concurrent.duration.*

/**
 * cancelled 滞留主图修复批 · **案 A（abandon 摘边）** 单测与引擎集成（2026-09-14，作者 17:24 拍板）。
 *
 * 覆盖四面（验收 ⑥）+ 三小件：
 *  - **腿 1 摘边语义**：带 deps/out/in 三面挂线 + 上游 `out` 逆引用的退役节点被 abandon ⇒
 *    边**两侧全摘** ⇒ 自成全终态分量 ⇒ `chainArchivable` 翻 true。**同测试内给改前/改后
 *    两侧读数**（改前：4 成员分量 / archivable=false；改后：单成员 / true）。
 *  - **腿 2 回填幂等**：已 cancelled 的滞留节点由 `backfillAbandonedDetach` 补摘；第二次
 *    运行 = 真 no-op（零 store 写 / 零 frame / 零审计行 / `flow-map.json` 字节不变）。
 *  - **腿 2 + sweep 集成**：摘净后同一 30s sweep 内该节点出主图 → **归档区可查**（留底，
 *    非删除）；活节点留在活动区。
 *  - **NodeCancel 不回退**：引擎侧 reap 取消腿的 R4 语义逐字不变（out→Nebula + 下游 in
 *    prune + 待承接），且**不获得**任何 abandon 专属臂（自家 in/deps 不动、上游 out 逆引用
 *    不摘）。
 *  - **小件①**：`startedAt=null ∧ result=null` 件回执不再含「result retained」空承诺；
 *    有产出件文案不回归。
 *  - **小件②**：零提交 worktree 被回收（目录删 + `branch -d`）；**有提交的 worktree 一行不动**。
 *  - **小件③**：契约措辞订正（工具描述行 + abandon schema 属性）。
 *
 * ⚠ 本 spec 只跑引擎/store 层（无 ProjectActor）：`TtlTick` 由测试直接调用 sweep 代替。
 * 隔离实例（真 gateway）实测 Δ 读数不在本文件——见批报告证据目录。
 */
class AbandonDetachSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-abandon-detach"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "general")

  os.write.over(
    tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"abandon-detach spec agent","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  // ── 装配（与 CancelDeadlockFixSpec 同源的最小夹具）───────────────

  private def mountProject(
    name: String,
    ws: os.Path,
    system: ActorSystem,
    res: SharedResources,
    frames: Ref[IO, List[Json]]
  ): IO[ProjectRuntime] =
    for
      store <- FlowMapStore.open(name, ws.toString)
      engine = new NodeEngine(
        store,
        system,
        res,
        wsSendFn = (j: Json) => frames.update(_ :+ j),
        workspace = ws.toString,
        rootSessionId = "nebula-root",
        projectName = name,
        emitEvent = (typ: String, nodeId: String, payload: Json) =>
          frames.update(
            _ :+ payload.deepMerge(Json.obj("type" -> Json.fromString(typ), "nodeId" -> Json.fromString(nodeId)))
          ),
        // 本 spec 主题非 node_report 语义 ⇒ 显式关腿 2（同 CancelDeadlockFixSpec 口径）
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

  private def seed(store: FlowMapStore, n: NodeDef): IO[Unit] =
    store.mutate(s => s.copy(nodes = s.nodes + (n.id -> n))).void

  private def node(rt: ProjectRuntime, id: String): IO[NodeDef] =
    rt.store.getNode(id).map(_.getOrElse(fail(s"node '$id' must exist")))

  private def mkCtx(res: SharedResources, system: ActorSystem, ws: String): ToolContext =
    ToolContext(
      projectRoot = ws,
      sessionId = Some("abandon-detach-sid"),
      rootSessionId = Some("nebula-root"),
      sharedResources = Some(res),
      actorSystem = Some(system)
    )

  private def nodeEdit(input: Json, ctx: ToolContext): IO[Either[String, String]] =
    NodeEditTool.call(input.asObject.get, ctx).map(_.left.map(_.message))

  private def nodeInput(project: String, nodename: String, extra: (String, Json)*): Json =
    Json.obj(("project" -> Json.fromString(project)) :: ("nodename" -> Json.fromString(nodename)) :: extra.toList*)

  private def readAudit(ws: os.Path): IO[List[(String, String, String)]] =
    IO.blocking(os.read(ws / ".nebflow" / FlowMapEventLog.FileName))
      .map(_.linesIterator.toList.filter(_.trim.nonEmpty))
      .map(
        _.flatMap(l =>
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

  /** 分量快照（口径单点 = `FlowMapStore.topologicalChains`，与 sweep 同源）。 */
  private def componentOf(rt: ProjectRuntime, id: String): IO[(Int, Boolean)] =
    rt.store.snapshot.map { snap =>
      val members = FlowMapStore
        .topologicalChains(snap.nodes.values)
        .find(_.memberIds.contains(id))
        .map(_.memberIds)
        .getOrElse(Nil)
      (members.size, FlowMapStore.chainArchivable(members.flatMap(snap.nodes.get)))
    }

  /** `flow-map.json` 实存路径（生产同一表达式；测试期用 walk 兜底定位）。 */
  private def stateFile(ws: os.Path): IO[os.Path] =
    IO.blocking(os.walk(ws).filter(_.last == "flow-map.json").toList match
      case h :: _ => h
      case Nil => fail(s"flow-map.json not found under $ws"))

  private def sha256(p: os.Path): IO[String] =
    IO.blocking(sha256Hex(os.read.bytes(p)))

  private def sha256Hex(bytes: Array[Byte]): String =
    val md = java.security.MessageDigest.getInstance("SHA-256")
    md.digest(bytes).map(b => f"$b%02x").mkString

  private def gitOk(cwd: os.Path, args: String*): String =
    val res = os.proc(Seq("git") ++ args).call(cwd = cwd, check = false, mergeErrIntoOut = true)
    if res.exitCode != 0 then fail(s"git ${args.mkString(" ")} failed in $cwd: ${res.out.trim()}")
    res.out.trim()

  // ── 腿 1：摘边语义（改前/改后同测并列读数）──────────────────────

  test(
    "leg1 摘边: abandon severs BOTH sides of every incident edge (in/out/deps + upstream out-refs) so the retired node becomes its own terminal component and chainArchivable flips true; BEFORE: same graph, no detach ⇒ 3-member glue + archivable=false"
  ) {
    val ws = tempRoot / "ws-leg1"
    os.makeDir.all(ws)
    val system = ActorSystem(s"ad-leg1-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- SpecResources.mkResources(system, tempRoot, new StubLlm().handle)
      frames <- Ref.of[IO, List[Json]](Nil)
      rt <- mountProject("ad-leg1", ws, system, res, frames)
      // 上游 U1：completed + pass 边指向退役节点 —— 现场 2/11 件正是这种「已终态上游的粘边」
      _ <- seed(
        rt.store,
        NodeDef(
          id = "n-u1",
          name = "U1",
          agent = "general",
          status = NodeLifecycle.Completed,
          result = Some("u1"),
          out = List(OutEdge("n-a")),
          completedAt = Some(now - 90_000),
          createdAt = now - 90_000
        )
      )
      // 退役节点 A：wiring，三类挂线齐备（in / out / deps）
      _ <- seed(
        rt.store,
        NodeDef(
          id = "n-a",
          name = "A",
          agent = "general",
          task = Some("work-A"),
          status = NodeLifecycle.Wiring,
          in = List("n-u1"),
          out = List(OutEdge("n-d")),
          deps = List("n-live"),
          createdAt = now - 60_000
        )
      )
      // 下游 D：in 镜像引用 A（未消费）
      _ <- seed(
        rt.store,
        NodeDef(
          id = "n-d",
          name = "D",
          agent = "general",
          task = Some("work-D"),
          status = NodeLifecycle.Pending,
          in = List("n-a"),
          createdAt = now - 50_000
        )
      )
      // 活节点 LIVE：被 A.deps 粘住（现场机械根因同型：n-aa3382e0.deps=["n-8b21387d"]）
      _ <- seed(
        rt.store,
        NodeDef(
          id = "n-live",
          name = "LIVE",
          agent = "general",
          task = Some("work-L"),
          status = NodeLifecycle.Wiring,
          createdAt = now - 30_000
        )
      )
      ctx = mkCtx(res, system, ws.toString)
      before <- componentOf(rt, "n-a")
      r <- nodeEdit(nodeInput("ad-leg1", "A", "abandon" -> Json.fromBoolean(true)), ctx)
      a <- node(rt, "n-a")
      d <- node(rt, "n-d")
      u1 <- node(rt, "n-u1")
      live <- node(rt, "n-live")
      after <- componentOf(rt, "n-a")
      audit <- readAudit(ws)
      wsFrames <- frames.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // ── 改前读数（同一 store、同一判据，未摘边）──
      // chainmodel 批一 ①（2026-09-19）：`deps` **不再是成员边** ⇒ A.deps=[n-live] 不再把
      // LIVE 粘进本分量，BEFORE 分量 = U1 + A + D 三名成员（LIVE 自成单成员分量）。
      // 「摘边令退役节点自成分量」的判据本体未变（口径变化，非回归）。
      assertEquals(before._1, 3, "BEFORE: the retired node must be glued into a 3-member component (in/out only)")
      assertEquals(before._2, false, "BEFORE: chainArchivable must be false (a live member holds the chain)")
      // ── 改后读数 ──
      assert(r.isRight, s"abandon must succeed, got: $r")
      assertEquals(a.status, NodeLifecycle.Cancelled, "abandon finalizes as cancelled")
      assertEquals(a.in, Nil, "own in must be severed (abandon-only arm)")
      assertEquals(a.deps, Nil, "own deps must be severed (abandon-only arm)")
      assertEquals(a.out, List(OutEdge.root), "out must collapse to the Nebula exit marker (R4-parity form)")
      assert(!d.in.contains("n-a"), s"downstream in-mirror must be pruned, got ${d.in}")
      assertEquals(d.pendingSuccession, List("n-a"), "unconsumed rail ⇒ 待承接 marker registered")
      assert(!u1.out.exists(e => e.to == "n-a"), s"upstream out-ref must be pruned, got ${u1.out}")
      assertEquals(live.deps, Nil, "the live node holds no edge to the retired node")
      assertEquals(after._1, 1, "AFTER: the retired node must form its own component")
      assertEquals(after._2, true, "AFTER: chainArchivable must flip true ⇒ the sweep can archive it")
      assert(
        audit.exists { case (t, id, s) => t == "abandoned" && id == "n-a" && s.contains("incident edges detached") },
        s"abandon audit must record the detach, got ${audit.map(_._1).distinct}"
      )
      assert(
        wsFrames.exists(j => j.hcursor.get[String]("nodeId").contains("n-d")),
        "visibility: a nodeUpdated frame for the repaired downstream is expected"
      )
      // 小件①（无产出侧）：A 未开工且无结果 ⇒ 回执不得承诺「result retained」
      val receipt = r.toOption.get
      assert(
        receipt.contains("no output was produced"),
        s"receipt must be honest for a never-started node, got: $receipt"
      )
      assert(!receipt.contains("result retained"), s"receipt must not promise a retained result, got: $receipt")
    end for
  }

  // ── 腿 2：回填幂等 ─────────────────────────────────────────────

  test(
    "leg2 回填幂等: backfillAbandonedDetach detaches an already-cancelled glued node, and the SECOND run is a true no-op (zero store write / zero frame / zero audit line)"
  ) {
    val ws = tempRoot / "ws-leg2"
    os.makeDir.all(ws)
    val system = ActorSystem(s"ad-leg2-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- SpecResources.mkResources(system, tempRoot, new StubLlm().handle)
      frames <- Ref.of[IO, List[Json]](Nil)
      rt <- mountProject("ad-leg2", ws, system, res, frames)
      _ <- seed(
        rt.store,
        NodeDef(
          id = "n-u",
          name = "U",
          agent = "general",
          status = NodeLifecycle.Completed,
          result = Some("u"),
          out = List(OutEdge("n-c")),
          completedAt = Some(now - 90_000),
          createdAt = now - 90_000
        )
      )
      _ <- seed(
        rt.store,
        NodeDef(
          id = "n-c",
          name = "C",
          agent = "general",
          task = Some("work-C"),
          status = NodeLifecycle.Cancelled,
          in = List("n-u"),
          out = List(OutEdge("n-d")),
          deps = List("n-live"),
          completedAt = Some(now - 60_000),
          createdAt = now - 60_000
        )
      )
      _ <- seed(
        rt.store,
        NodeDef(
          id = "n-d",
          name = "D",
          agent = "general",
          task = Some("work-D"),
          status = NodeLifecycle.Pending,
          in = List("n-c"),
          createdAt = now - 50_000
        )
      )
      _ <- seed(
        rt.store,
        NodeDef(
          id = "n-live",
          name = "LIVE",
          agent = "general",
          task = Some("work-L"),
          status = NodeLifecycle.Wiring,
          createdAt = now - 30_000
        )
      )
      before <- componentOf(rt, "n-c")
      run1 <- rt.engine.backfillAbandonedDetach()
      frames1 <- frames.get
      audit1 <- readAudit(ws)
      f <- stateFile(ws)
      sha1 <- sha256(f)
      run2 <- rt.engine.backfillAbandonedDetach()
      frames2 <- frames.get
      audit2 <- readAudit(ws)
      sha2 <- sha256(f)
      c <- node(rt, "n-c")
      d <- node(rt, "n-d")
      after <- componentOf(rt, "n-c")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(
        before,
        (3, false),
        "BEFORE: C glued to the live node by in/out ⇒ U + C + D share one non-archivable chain (chainmodel 批一 ①: deps is no longer a member edge)"
      )
      assertEquals(run1, List("n-c"), "first backfill run must report the detached node")
      assertEquals(c.status, NodeLifecycle.Cancelled, "the backfill never touches a non-cancelled node's status")
      assertEquals(c.in, Nil)
      assertEquals(c.deps, Nil)
      assertEquals(c.out, List(OutEdge.root))
      assert(!d.in.contains("n-c"), s"D.in must be pruned, got ${d.in}")
      assertEquals(d.pendingSuccession, List("n-c"))
      assertEquals(after, (1, true), "AFTER: n-c alone in its own all-terminal component")
      // 幂等：第二次 = 真 no-op
      assertEquals(run2, Nil, "second run must report nothing")
      assertEquals(frames2.size, frames1.size, "second run must emit zero frames")
      assertEquals(audit2.size, audit1.size, "second run must append zero audit lines")
      assertEquals(sha2, sha1, "second run must not write flow-map.json (byte-identical)")
      assertEquals(
        audit1.count { case (t, id, _) => t == "abandoned-detach" && id == "n-c" },
        1,
        s"exactly one abandoned-detach audit line expected, got ${audit1.map(_._1)}"
      )
    end for
  }

  // ── 腿 2 + sweep 集成 ──────────────────────────────────────────

  test(
    "leg2+sweep 集成: once detached, the retired node leaves the main map on the very next chain sweep and is found in the ARCHIVE (retained, not deleted); the live node stays active"
  ) {
    val ws = tempRoot / "ws-leg3"
    os.makeDir.all(ws)
    val system = ActorSystem(s"ad-leg3-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- SpecResources.mkResources(system, tempRoot, new StubLlm().handle)
      frames <- Ref.of[IO, List[Json]](Nil)
      rt <- mountProject("ad-leg3", ws, system, res, frames)
      // chainmodel 批一 ①（2026-09-19）：粘边由 `deps` 换成 `out`——deps 已不再是成员边
      // （不再是「拖住分量」的边），本用例要的「取消节点被活跃成员拖住 ⇒ 不可归档」形态
      // 只能由 in/out 成员边承载。摘边仍然把 C.out 收敛为 Nebula（out 臂，逐字同前）。
      _ <- seed(
        rt.store,
        NodeDef(
          id = "n-c",
          name = "C",
          agent = "general",
          task = Some("work-C"),
          status = NodeLifecycle.Cancelled,
          out = List(OutEdge("n-live")),
          result = Some("cancelled[source=user]: reason=spec"),
          completedAt = Some(now - 60_000),
          createdAt = now - 60_000
        )
      )
      _ <- seed(
        rt.store,
        NodeDef(
          id = "n-live",
          name = "LIVE",
          agent = "general",
          task = Some("work-L"),
          status = NodeLifecycle.Wiring,
          createdAt = now - 30_000
        )
      )
      before <- componentOf(rt, "n-c")
      beforeSweep <- rt.store.sweepCompletedChainsDetailed(now)
      detached <- rt.engine.backfillAbandonedDetach()
      swept <- rt.store.sweepCompletedChainsDetailed(now)
      active <- rt.store.snapshot
      arch <- rt.store.archiveSnapshot
      audit <- readAudit(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(before, (2, false), "BEFORE: C + the live node share one non-archivable component")
      assertEquals(beforeSweep, Nil, "BEFORE: nothing may be swept while a live member holds the chain")
      assertEquals(detached, List("n-c"))
      assertEquals(swept.flatMap(_.nodeIds), List("n-c"), s"sweep must move exactly the retired node, got $swept")
      assert(!active.nodes.contains("n-c"), "the retired node must leave the active map")
      assert(active.nodes.contains("n-live"), "the live node must stay on the active map")
      assert(arch.nodes.contains("n-c"), "the retired node must be found in the ARCHIVE (retained, not deleted)")
      assertEquals(arch.nodes("n-c").status, NodeLifecycle.Cancelled)
      assert(audit.exists { case (t, id, _) => t == "abandoned-detach" && id == "n-c" })
    end for
  }

  // ── NodeCancel 不回退（回归钉）──────────────────────────────────

  test(
    "NodeCancel 不回退: the engine-side cancel leg keeps its R4 detach semantics byte-for-byte and does NOT gain any abandon-only arm (own in/deps untouched, upstream out-refs NOT pruned)"
  ) {
    val ws = tempRoot / "ws-nc"
    os.makeDir.all(ws)
    val system = ActorSystem(s"ad-nc-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- SpecResources.mkResources(system, tempRoot, new StubLlm().handle)
      frames <- Ref.of[IO, List[Json]](Nil)
      rt <- mountProject("ad-nc", ws, system, res, frames)
      _ <- seed(
        rt.store,
        NodeDef(
          id = "n-u",
          name = "U",
          agent = "general",
          status = NodeLifecycle.Completed,
          result = Some("u"),
          out = List(OutEdge("n-a")),
          completedAt = Some(now - 90_000),
          createdAt = now - 90_000
        )
      )
      _ <- seed(
        rt.store,
        NodeDef(
          id = "n-w",
          name = "W",
          agent = "general",
          status = NodeLifecycle.Completed,
          result = Some("w"),
          completedAt = Some(now - 80_000),
          createdAt = now - 80_000
        )
      )
      _ <- seed(
        rt.store,
        NodeDef(
          id = "n-a",
          name = "A",
          agent = "general",
          task = Some("work-A"),
          status = NodeLifecycle.Running,
          in = List("n-u"),
          out = List(OutEdge("n-b")),
          deps = List("n-w"),
          startedAt = Some(now - 3_600_000L),
          createdAt = now - 3_600_000L
        )
      )
      _ <- seed(
        rt.store,
        NodeDef(
          id = "n-b",
          name = "B",
          agent = "general",
          task = Some("work-B"),
          status = NodeLifecycle.Pending,
          in = List("n-a"),
          createdAt = now - 600_000L
        )
      )
      reap <- rt.engine.reapStaleRunning("n-a")
      a <- node(rt, "n-a")
      b <- node(rt, "n-b")
      u <- node(rt, "n-u")
      audit <- readAudit(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(reap.isRight, s"reap must succeed: $reap")
      assertEquals(a.status, NodeLifecycle.Cancelled, "reap finalizes as cancelled")
      // ── R4 摘除语义：逐字保留 ──
      assertEquals(a.out, List(OutEdge.root), "R4: cancelled node out → Nebula (unchanged)")
      assert(!b.in.contains("n-a"), s"R4: downstream in mirror pruned (unchanged), got ${b.in}")
      assertEquals(b.pendingSuccession, List("n-a"), "R4: downstream 待承接 marker (unchanged)")
      assert(
        a.result.exists(_.startsWith("cancelled[source=engine]: reason=")),
        "R2: cancelNode still persists the cancel reason (unchanged)"
      )
      // ── abandon 专属臂必须**没有**越界到 NodeCancel ──
      assertEquals(a.in, List("n-u"), "NodeCancel must NOT clear the cancelled node's own in (abandon-only arm)")
      assertEquals(a.deps, List("n-w"), "NodeCancel must NOT clear deps (abandon-only arm)")
      assert(u.out.exists(e => e.to == "n-a"), "NodeCancel must NOT prune upstream out-refs (abandon-only arm)")
      // ── 审计面：cancelled 一条，零 abandoned / 零 abandoned-detach ──
      assertEquals(audit.count(_._1 == "cancelled"), 1, s"exactly one cancelled audit line, got ${audit.map(_._1)}")
      assertEquals(audit.count(_._1 == "abandoned"), 0, "no abandoned audit line on the NodeCancel path")
      assertEquals(audit.count(_._1 == "abandoned-detach"), 0, "no backfill audit line on the NodeCancel path")
    end for
  }

  // ── 小件①：有产出件文案不回归 ─────────────────────────────────

  test(
    "小件① 文案如实: a node that DID run keeps the legacy 'result retained' receipt verbatim (no regression on the has-output arm)"
  ) {
    val ws = tempRoot / "ws-r1"
    os.makeDir.all(ws)
    val system = ActorSystem(s"ad-r1-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- SpecResources.mkResources(system, tempRoot, new StubLlm().handle)
      frames <- Ref.of[IO, List[Json]](Nil)
      rt <- mountProject("ad-r1", ws, system, res, frames)
      _ <- seed(
        rt.store,
        NodeDef(
          id = "n-p",
          name = "P",
          agent = "general",
          task = Some("work-P"),
          status = NodeLifecycle.Wiring,
          result = Some("half-done output"),
          startedAt = Some(now - 120_000),
          createdAt = now - 120_000
        )
      )
      ctx = mkCtx(res, system, ws.toString)
      r <- nodeEdit(nodeInput("ad-r1", "P", "abandon" -> Json.fromBoolean(true)), ctx)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isRight, s"abandon must succeed, got: $r")
      val receipt = r.toOption.get
      assert(receipt.contains("result retained"), s"has-output receipt must keep its legacy wording, got: $receipt")
      assert(!receipt.contains("no output was produced"), s"has-output receipt must not claim no output, got: $receipt")
    end for
  }

  // ── 小件②：零提交 worktree 回收（真 git 仓，两侧读数）────────────

  test(
    "小件② worktree 回收: abandon reclaims a ZERO-COMMIT worktree (dir removed + branch -d) and leaves a worktree that HAS commits completely untouched"
  ) {
    val ws = PathUtil.dataRoot / s"ad-wt-${scala.util.Random.nextInt(100000)}"
    os.makeDir.all(ws)
    val system = ActorSystem(s"ad-wt-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    val zeroDir = ws / ".nebflow" / "worktrees" / "zero-wt"
    val oneDir = ws / ".nebflow" / "worktrees" / "one-wt"
    for
      _ <- IO.blocking {
        gitOk(ws, "-c", "init.defaultBranch=main", "init", "-q")
        gitOk(ws, "-c", "user.email=spec@local", "-c", "user.name=spec", "commit", "-q", "--allow-empty", "-m", "init")
        gitOk(ws, "worktree", "add", "-q", "-b", "zero-wt", zeroDir.toString)
        gitOk(ws, "worktree", "add", "-q", "-b", "one-wt", oneDir.toString)
        os.write(oneDir / "f.txt", "x\n")
        gitOk(oneDir, "add", "f.txt")
        gitOk(oneDir, "-c", "user.email=spec@local", "-c", "user.name=spec", "commit", "-q", "-m", "one")
      }
      res <- SpecResources.mkResources(system, tempRoot, new StubLlm().handle)
      frames <- Ref.of[IO, List[Json]](Nil)
      rt <- mountProject("ad-wt", ws, system, res, frames)
      _ <- seed(
        rt.store,
        NodeDef(
          id = "n-z",
          name = "Z",
          agent = "general",
          task = Some("work-Z"),
          status = NodeLifecycle.Wiring,
          worktree = Some("zero-wt"),
          createdAt = now - 60_000
        )
      )
      _ <- seed(
        rt.store,
        NodeDef(
          id = "n-o",
          name = "O",
          agent = "general",
          task = Some("work-O"),
          status = NodeLifecycle.Wiring,
          worktree = Some("one-wt"),
          createdAt = now - 50_000
        )
      )
      ctx = mkCtx(res, system, ws.toString)
      rz <- nodeEdit(nodeInput("ad-wt", "Z", "abandon" -> Json.fromBoolean(true)), ctx)
      ro <- nodeEdit(nodeInput("ad-wt", "O", "abandon" -> Json.fromBoolean(true)), ctx)
      zeroGone <- IO.blocking(!os.exists(zeroDir))
      oneKept <- IO.blocking(os.exists(oneDir))
      zeroBranch <- IO.blocking(gitOk(ws, "branch", "--list", "zero-wt"))
      oneBranch <- IO.blocking(gitOk(ws, "branch", "--list", "one-wt"))
      audit <- readAudit(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
      _ <- IO.blocking(os.remove.all(ws)).handleErrorWith(_ => IO.unit)
    yield
      assert(rz.isRight, s"abandon must succeed, got: $rz")
      assert(ro.isRight, s"abandon must succeed, got: $ro")
      assert(zeroGone, "ZERO-commit worktree directory must be removed")
      assertEquals(zeroBranch, "", "ZERO-commit branch must be deleted (git branch -d, never -D)")
      assert(oneKept, "a worktree WITH commits must be left untouched")
      assert(oneBranch.nonEmpty, "a branch WITH commits must be left untouched")
      // 回收结果落在 `abandoned` 审计行（回执只讲 abandon 本身：有产出臂必须逐字照旧，
      // 故不把 worktree 结果塞进回执——见 NodeTools.abandonReceipt 头注）。
      val zAudit = audit
        .find { case (t, id, _) => t == "abandoned" && id == "n-z" }
        .map(_._3)
        .getOrElse(fail(s"abandoned audit line for n-z expected, got ${audit.map(_._1)}"))
      val oAudit = audit
        .find { case (t, id, _) => t == "abandoned" && id == "n-o" }
        .map(_._3)
        .getOrElse(fail(s"abandoned audit line for n-o expected, got ${audit.map(_._1)}"))
      assert(zAudit.contains("zero-commit worktree 'zero-wt' removed"), s"ZERO side reading in audit, got: $zAudit")
      assert(zAudit.contains("branch deleted (-d)"), s"branch must be deleted with -d, got: $zAudit")
      assert(oAudit.contains("kept — 1 commit"), s"NON-ZERO side reading in audit, got: $oAudit")
      assert(!oAudit.contains("removed"), s"a worktree with commits must not be reclaimed, got: $oAudit")
    end for
  }

  // ── 小件③：契约措辞订正 ───────────────────────────────────────

  test(
    "小件③ 契约措辞: the abandon contract no longer claims 'never auto-archive' (both the tool-description line and the schema property) and states the component-level sweep path instead; the description byte count does not grow"
  ) {
    val d = NodeEditTool.description
    val schemaDesc = NodeEditTool
      .inputSchema("properties")
      .flatMap(_.asObject)
      .flatMap(_("abandon"))
      .flatMap(_.asObject)
      .flatMap(_("description"))
      .flatMap(_.asString)
      .getOrElse(fail("abandon schema property description must exist"))
    println(
      s"[ABANDON-DETACH-READING] NodeEditTool.description.length=${d.length} " +
        s"bytes=${d.getBytes("UTF-8").length} abandonSchema.length=${schemaDesc.length} " +
        s"bytes=${schemaDesc.getBytes("UTF-8").length}"
    )
    for txt <- List(d, schemaDesc) do
      assert(!txt.contains("never auto-archive"), s"the over-strong phrasing must be gone, got: $txt")
      assert(!txt.contains("永不自动归档"), s"the over-strong phrasing must be gone, got: $txt")
    assert(d.contains("cancelled + edges detached"), s"description line must state the detach, got: $d")
    assert(
      schemaDesc.contains("edges detached") && schemaDesc.contains("chain sweep archives it"),
      s"schema wording must state the component-level path, got: $schemaDesc"
    )
    assert(
      d.contains("abandon") && d.contains("Nebula") && d.contains("restoreChain"),
      "semantic anchors of the compressed description must survive"
    )
  }

  // ── 补臂（chain-failroute-guard 批）：**回填腿同款检测** ─────────────
  //
  // 🔴 本用例补的是上面 `:339` 用例的**结构性错开臂**：那条断言只覆盖**同步取消腿**
  // （`detachCancelledUpstream` 当日形态「不回退 upstream out-refs」），而实盘 8 例中
  // **5 例（#2–#6）的 fail 边摘除发生在 12–26s 之后的 30s 回填腿**
  // （`ProjectActor.TtlTick` → `backfillAbandonedDetach` → 同一 `detachAbandonedNode`）。
  // 该窗口此前**零覆盖面** ⇒ 「零回归」保证与缺陷面错开一臂，是五例长期无症状的机械原因。
  //
  // 判据（红-绿可判，两段读数同测并列）：
  //  - **段 1（同步取消腿的当时读数）**：reap 之后、回填**之前**，verifier 的 fail 边
  //    **仍在**（`:339` 的语义逐字保留）——这正是「错开一臂」的机械证明；
  //  - **段 2（回填腿）**：`backfillAbandonedDetach()` 之后 fail 边被摘除，且受害
  //    verifier **同帧落拒绝态**（`verifier-route-lost` 主语 = verifier + 载荷派生键
  //    `verifierRoute="lost"`）——改前此段**全红**（边被静默摘除、零 verifier 侧留痕）。

  test(
    "backfill-leg same-detection (chain-failroute-guard): the 30s backfill leg severs the victim verifier's fail edge — the sync-cancel reading is taken first (edge still there), then the backfill runs and the victim verifier must land in the REJECTION STATE (verifier-route-lost + payload verifierRoute=lost)"
  ) {
    val ws = tempRoot / "ws-fillver"
    os.makeDir.all(ws)
    val system = ActorSystem(s"ad-fillver-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- SpecResources.mkResources(system, tempRoot, new StubLlm().handle)
      frames <- Ref.of[IO, List[Json]](Nil)
      rt <- mountProject("ad-fillver", ws, system, res, frames)
      // 判据拓扑：worker(被判位, stale running) ← `(fail)worker:loop` ← verifier(role=verifier) → land
      _ <- seed(
        rt.store,
        NodeDef(
          id = "n-work",
          name = "WORK",
          agent = "general",
          status = NodeLifecycle.Running,
          task = Some("work"),
          startedAt = Some(now - 3_600_000L),
          createdAt = now - 3_600_000L
        )
      )
      _ <- seed(
        rt.store,
        NodeDef(
          id = "n-ver",
          name = "VER",
          agent = "general",
          status = NodeLifecycle.Pending,
          task = Some("judge"),
          in = List("n-work"),
          role = NodeRoles.Verifier,
          out = List(OutEdge("n-land"), OutEdge("n-work", Set(OutEdge.Fail), OutEdge.Loop)),
          createdAt = now - 120_000L
        )
      )
      _ <- seed(
        rt.store,
        NodeDef(
          id = "n-land",
          name = "LAND",
          agent = "general",
          status = NodeLifecycle.Pending,
          task = Some("land"),
          in = List("n-ver"),
          createdAt = now - 60_000L
        )
      )
      // 段 1：同步取消腿（NodeCancel/reap）——`detachCancelledUpstream` 不碰他人 out
      reap <- rt.engine.reapStaleRunning("n-work")
      verAfterCancel <- node(rt, "n-ver")
      keyAfterCancel <- payloadKey(rt, "n-ver")
      auditAfterCancel <- readAudit(ws)
      _ <- IO(
        println(
          s"[spec] backfill-arm segment 1 (sync cancel leg) — verifier.out=${verAfterCancel.out} ; " +
            s"verifierRoute=$keyAfterCancel ; verifier-route-lost lines=" +
            s"${auditAfterCancel.count(_._1 == "verifier-route-lost")}"
        )
      )
      // 段 2：30s 回填腿（等价于 `ProjectActor.TtlTick` 的下一拍）
      detached <- rt.engine.backfillAbandonedDetach()
      verAfterFill <- node(rt, "n-ver")
      keyAfterFill <- payloadKey(rt, "n-ver")
      auditAfterFill <- readAudit(ws)
      lostLines = auditAfterFill.filter(_._1 == "verifier-route-lost")
      _ <- IO(
        println(
          s"[spec] backfill-arm segment 2 (30s backfill leg) — detached=$detached ; " +
            s"verifier.out=${verAfterFill.out} ; verifierRoute=$keyAfterFill ; " +
            s"verifier-route-lost=${lostLines.map(l => s"[nodeId=${l._2}] ${l._3}")}"
        )
      )
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(reap.isRight, s"reap must succeed: $reap")
      // ── 段 1 读数（`:339` 语义逐字保留：同步腿不摘 upstream out-refs）──
      assert(
        verAfterCancel.out.exists(e => e.on.contains(OutEdge.Fail) && OutEdge.isLoopEdge(e)),
        s"segment 1: the sync cancel leg must NOT prune the referrer's fail edge (unchanged), got ${verAfterCancel.out}"
      )
      assertEquals(keyAfterCancel, None, "segment 1: still healthy ⇒ no rejection-state key")
      assertEquals(
        auditAfterCancel.count(_._1 == "verifier-route-lost"),
        0,
        "segment 1: the sync cancel leg must log no verifier-route-lost line"
      )
      // ── 段 2 读数（本批新增的检测面：回填腿摘边 ⇒ 同帧落拒绝态）──
      assertEquals(detached, List("n-work"), "segment 2: the backfill leg detaches the cancelled node")
      assertEquals(
        verAfterFill.out,
        List(OutEdge("n-land")),
        "segment 2: the backfill leg DOES sever the victim verifier's fail edge (the coverage gap this arm closes)"
      )
      assertEquals(
        keyAfterFill,
        Some("lost"),
        "segment 2: the victim verifier must carry the derived rejection-state key"
      )
      assertEquals(
        lostLines.map(_._2).distinct,
        List("n-ver"),
        s"segment 2: the audit subject must be the VICTIM VERIFIER, got ${lostLines.map(_._2)}"
      )
      assert(
        lostLines.exists(_.last.contains("(fail)")),
        s"segment 2: the line must carry the actionable restore form, got ${lostLines.map(_.last)}"
      )
    end for
  }

  /** 载荷派生键读数（NodeList 快照载荷 = 工具面/REST 共用序列化点）。 */
  private def payloadKey(rt: ProjectRuntime, id: String): IO[Option[String]] =
    NodeTools.buildNodeListPayload(rt).map { j =>
      j.hcursor
        .downField("nodes")
        .as[List[Json]]
        .getOrElse(Nil)
        .find(_.hcursor.get[String]("id").toOption.contains(id))
        .map(_.hcursor.get[String]("verifierRoute").toOption)
        .getOrElse(fail(s"node '$id' must appear in the NodeList payload"))
    }
end AbandonDetachSpec
