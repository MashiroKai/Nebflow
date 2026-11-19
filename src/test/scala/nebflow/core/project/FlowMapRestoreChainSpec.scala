package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import io.circe.parser.parse as jsonParse
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentLibrary, SharedResources}
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, NodeEditTool, NodeTools, ToolContext}
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk, ThinkingConfig}

import scala.concurrent.duration.*

/**
 * Flow Map 链级抽象 **P2 拉回（restoreChain）** 测试（spec
 * 20260910_flowmap-chain-abstraction-spec §5）：
 *
 * - R① 整批拉回（§5.3-③）：`restoreFromArchive` 单点入参 → **归档批整体**回活动区
 *   （恒整批 §5.3-⑧）+ 批文件收敛 + 批次索引清除 + 双区不重叠 + 幂等
 * - R② 旗标缺省 = 现状（负控，§5.3-②）：不传 restoreChain → 归档节点原样留归档区；
 *   解析次序沿用 findNode（活动区命中即不拉回、两区皆无即忽略）
 * - R③ 旗标正控 e2e 创建形态（§5.3-④）：NodeEdit(create, restoreChain=true, in=归档
 *   节点) → 先拉回再创建；结果前导 `[chain-restored]` 块；新节点与拉回成员同链
 * - R④ 旗标正负控 e2e 编辑形态（§5.1 点 2）：不传旗标 ⇒ 归档编辑拒绝（现状）；
 *   传旗标 ⇒ 拉回后按活动区语义编辑（拒绝闸让位）
 * - R⑤ 重挂窗口（§5.3-④ 时序）：拉回后 sweep 让路（不立即再归档）；窗口过后照常
 *   再归档（自愈，非「永久豁免」）
 * - R⑥ 审计（§5.3）：`chain-restored` 事件——顶层 chainId + 结构化 summary，
 *   消费者 DocIndexConsumer 可解析（回翻前置；此前只有接口点无写入点）
 * - R⑦ open 双区对账（§5.3-③ 崩溃窗口）：活动区优先、归档副本剔除
 */
class FlowMapRestoreChainSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-restore-chain"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "general")

  os.write.over(
    tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"general executor","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  private val t0 = 1750000000000L

  private def def0(
    id: String,
    createdAt: Long,
    status: String = NodeLifecycle.Completed,
    in: List[String] = Nil,
    out: List[OutEdge] = Nil,
    deps: List[String] = Nil
  ): NodeDef =
    NodeDef(
      id = id,
      name = s"name-$id",
      agent = "general",
      status = status,
      in = in,
      out = out,
      deps = deps,
      createdAt = createdAt
    )

  /**
   * 链 a → b 直种归档区（走 mutateArchive ⇒ persistArchiveDiff 的孤儿重聚簇会把两成员
   * 注册成同一拓扑批 `chain-n-a` 并落批文件——与生产归档同路径，非测试旁路）。
   */
  private def seedArchivedChain(store: FlowMapStore): IO[Unit] =
    store.mutateArchive { a0 =>
      a0.copy(nodes =
        a0.nodes ++ Map(
          "n-a" -> def0("n-a", t0, out = List(OutEdge("n-b"))),
          "n-b" -> def0("n-b", t0 + 1000, in = List("n-a"))
        )
      )
    }.void

  private def batchFileOf(ws: os.Path, batchId: String): os.Path =
    ws / ".nebflow" / FlowMapStore.ArchiveDirName / s"$batchId.json"

  private def batchFileNodeIds(ws: os.Path, batchId: String): Either[String, Set[String]] =
    val f = batchFileOf(ws, batchId)
    if !os.exists(f) then Left("missing")
    else
      jsonParse(os.read(f)).flatMap(_.as[FlowMapArchiveBatch]) match
        case Right(b) => Right(b.nodes.keySet)
        case Left(e) => Left(e.getMessage)

  /** 只读 store 的 ProjectRuntime 桩（R②/R⑥ 用：不触 engine 的纯 store 单点）。 */
  private def mkRuntimeStub(store: FlowMapStore, ws: os.Path): ProjectRuntime =
    ProjectRuntime(
      ProjectDef(name = store.project, workspace = ws.toString, agentFile = "", createdAt = t0),
      store,
      null,
      null,
      null,
      None
    )

  // ── R① 整批拉回 ─────────────────────────────────────────

  test("R① 整批拉回: 单点入参 → 归档批整体回活动区（恒整批）+ 批文件收敛 + 批次索引清除 + 幂等") {
    val ws = os.temp.dir(prefix = "nb-restore-r1-", deleteOnExit = false)
    for
      store <- FlowMapStore.open("restore-r1", ws.toString)
      _ <- seedArchivedChain(store)
      btBefore <- store.archiveBatches
      fileBefore <- IO.blocking(os.exists(batchFileOf(ws, "chain-n-a")))
      // 只传链尾成员 → 仍应整批（含链头 n-a）拉回
      restored <- store.restoreFromArchive(List("n-b"))
      s <- store.snapshot
      arch <- store.archiveSnapshot
      btAfter <- store.archiveBatches
      fileAfter <- IO.blocking(os.exists(batchFileOf(ws, "chain-n-a")))
      // 幂等：重复拉回 → 空集零动作
      again <- store.restoreFromArchive(List("n-b"))
    yield
      assertEquals(btBefore.keySet, Set("chain-n-a"), "seeded archive batch registered under the topological chain id")
      assert(fileBefore, "seeded batch file must exist on disk")
      assertEquals(restored.map(_.id).sorted, List("n-a", "n-b"), "a single target pulls the WHOLE batch (恒整批 §5.3-⑧)")
      assertEquals(s.nodes.keySet, Set("n-a", "n-b"), "both members back on the active map")
      assertEquals(arch.nodes.keySet, Set.empty[String], "archive zone emptied (no double-zone residue)")
      assertEquals(btAfter.keySet, Set.empty[String], "batch index cleared")
      assert(!fileAfter, "batch file removed once all its members left the archive")
      assertEquals(again, Nil, "restore is idempotent — a second call is a no-op")
    end for
  }

  // ── R② 旗标缺省（负控）───────────────────────────────────

  test("R② 旗标缺省: 不传 restoreChain → 零动作；解析次序 = 活动区命中不拉回 / 两区皆无忽略") {
    val ws = os.temp.dir(prefix = "nb-restore-r2-", deleteOnExit = false)
    for
      store <- FlowMapStore.open("restore-r2", ws.toString)
      _ <- seedArchivedChain(store)
      stubRt = mkRuntimeStub(store, ws)
      // ① 旗标 false（缺省形态）：即便引用命中归档区，也零动作
      noFlag <- NodeTools.maybeRestoreChains(
        stubRt,
        flag = false,
        inJson = Some(Json.fromString("n-a")),
        depsJson = None,
        nodename = "name-n-a"
      )
      s0 <- store.snapshot
      arch0 <- store.archiveSnapshot
      // 活动区节点（按名解析命中活动区 ⇒ 无需拉回）
      _ <- store.mutate(s => s.copy(nodes = s.nodes + ("n-live" -> def0("n-live", t0 + 5000, NodeLifecycle.Wiring))))
      liveRef <- NodeTools.maybeRestoreChains(
        stubRt,
        flag = true,
        inJson = Some(Json.fromString("name-n-live")),
        depsJson = None,
        nodename = "fresh-node"
      )
      // 两区皆无 ⇒ 忽略（交给既有「引用不存在」校验报错）
      ghostRef <- NodeTools.maybeRestoreChains(
        stubRt,
        flag = true,
        inJson = Some(Json.fromString("no-such-node")),
        depsJson = None,
        nodename = "fresh-node"
      )
      s1 <- store.snapshot
      arch1 <- store.archiveSnapshot
    yield
      assertEquals(noFlag, Nil, "flag off ⇒ zero restore (default = today's pure-reference semantics)")
      assertEquals(s0.nodes.keySet, Set.empty[String], "flag off: nothing moved into the active zone")
      assertEquals(arch0.nodes.keySet, Set("n-a", "n-b"), "flag off: archive untouched")
      assertEquals(liveRef, Nil, "an active-zone hit needs no restore (resolution order = findNode)")
      assertEquals(ghostRef, Nil, "a reference in neither zone is ignored (existing not-found validation owns it)")
      assertEquals(s1.nodes.keySet, Set("n-live"), "no restore happened for the last two probes")
      assertEquals(arch1.nodes.keySet, Set("n-a", "n-b"), "archive untouched throughout")
    end for
  }

  // ── R⑤ 重挂窗口 ─────────────────────────────────────────

  test("R⑤ 重挂窗口: 拉回后 sweep 让路（不立即再归档）；窗口过后照常再归档（自愈）") {
    val ws = os.temp.dir(prefix = "nb-restore-r5-", deleteOnExit = false)
    for
      store <- FlowMapStore.open("restore-r5", ws.toString)
      _ <- seedArchivedChain(store)
      restored <- store.restoreChainsFromArchiveDetailed(List("n-a"))
      nowMs = System.currentTimeMillis()
      inWindow <- store.sweepCompletedChains(nowMs)
      s1 <- store.snapshot
      arch1 <- store.archiveSnapshot
      afterWindow <- store.sweepCompletedChains(nowMs + FlowMapStore.RestoreReattachWindowMs + 1000)
      s2 <- store.snapshot
      arch2 <- store.archiveSnapshot
      bt2 <- store.archiveBatches
    yield
      assertEquals(restored.map(_.chainId), List("chain-n-a"), "restore returns one chain entry (batch id = chain id)")
      assertEquals(restored.head.nodeIds.sorted, List("n-a", "n-b"), "entry carries the whole member set")
      assertEquals(inWindow, Nil, "sweep must yield to a freshly restored chain (30s TtlTick racing the re-attach)")
      assertEquals(s1.nodes.keySet, Set("n-a", "n-b"), "restored members stay on the active map inside the window")
      assertEquals(arch1.nodes.keySet, Set.empty[String], "no immediate re-archive inside the window")
      assertEquals(
        afterWindow.sorted,
        List("n-a", "n-b"),
        "after the window the chain is swept again normally (self-healing, not exempt forever)"
      )
      assertEquals(s2.nodes.keySet, Set.empty[String], "active zone emptied by the post-window sweep")
      assertEquals(arch2.nodes.keySet, Set("n-a", "n-b"), "members back in the archive zone")
      assertEquals(bt2.keySet, Set("chain-n-a"), "re-archived under the same topological chain id (id stability)")
    end for
  }

  // ── R⑦ open 双区对账 ────────────────────────────────────

  test("R⑦ open 双区对账: 拉回崩溃窗口（双区同在）→ 活动区优先、归档副本剔除、批次收敛") {
    val ws = os.temp.dir(prefix = "nb-restore-r7-", deleteOnExit = false)
    for
      store1 <- FlowMapStore.open("restore-r7", ws.toString)
      _ <- seedArchivedChain(store1)
      // 模拟「活动区先写、归档区后删」之间崩溃：活动区也持有 n-a（双区同在）
      _ <- store1.mutate(s => s.copy(nodes = s.nodes + ("n-a" -> def0("n-a", t0, out = List(OutEdge("n-b"))))))
      // 重开（= 崩溃后重启）
      store2 <- FlowMapStore.open("restore-r7", ws.toString)
      s <- store2.snapshot
      arch <- store2.archiveSnapshot
      bt <- store2.archiveBatches
      fileIds <- IO.blocking(batchFileNodeIds(ws, "chain-n-a"))
    yield
      assertEquals(s.nodes.keySet, Set("n-a"), "active-zone copy wins (safety side of the restore move order)")
      assertEquals(arch.nodes.keySet, Set("n-b"), "only the archive copy of the crashed member is dropped")
      assertEquals(bt.keySet, Set("chain-n-a"), "the surviving member keeps its batch registration")
      assertEquals(fileIds, Right(Set("n-b")), "batch file rewritten with the surviving member only")
    end for
  }

  // ── R⑥ 审计事件 ─────────────────────────────────────────

  test("R⑥ 审计: chain-restored 事件（顶层 chainId + k=v summary），DocIndexConsumer 可解析") {
    val ws = os.temp.dir(prefix = "nb-restore-r6-", deleteOnExit = false)
    val eventsFile = ws / ".nebflow" / FlowMapEventLog.FileName
    for
      store <- FlowMapStore.open("restore-r6", ws.toString)
      _ <- seedArchivedChain(store)
      restored <- store.restoreChainsFromArchiveDetailed(List("n-a"))
      _ <- NodeTools.logChainRestored(mkRuntimeStub(store, ws), restored)
      lines <- IO.blocking(os.read.lines(eventsFile).toList.filter(_.trim.nonEmpty))
      parsed = lines.flatMap(DocIndexConsumer.parseEventLine)
      // 负控：未拉回（空集）⇒ 零写入
      _ <- NodeTools.logChainRestored(mkRuntimeStub(store, ws), Nil)
      lines2 <- IO.blocking(os.read.lines(eventsFile).toList.filter(_.trim.nonEmpty))
    yield
      assertEquals(parsed.size, 1, s"exactly one audit line, got ${lines.mkString(" | ")}")
      val ev = parsed.head
      assertEquals(
        ev.typ,
        FlowMapEventLog.ChainRestoredType,
        "event type = chain-restored (the previously unwired interface point is now live)"
      )
      assertEquals(ev.chainId, Some("chain-n-a"), "top-level chainId written (no summary re-parsing needed)")
      assertEquals(ev.project, "restore-r6")
      assertEquals(
        ev.nodeId,
        "n-a",
        "nodeId = the chain anchor (earliest createdAt member) — symmetric with chain-archived"
      )
      assertEquals(ev.fields.get("members"), Some("2"), "structured summary carries members")
      assert(ev.fields.get("restoredAt").exists(_.toLongOption.isDefined), s"summary: ${ev.summary}")
      assertEquals(
        ev.atMs,
        restored.head.restoredAt,
        "atMs resolves from restoredAt (same key semantics as archivedAt)"
      )
      assertEquals(lines2.size, 1, "empty restore ⇒ zero audit writes")
    end for
  }

  // ── R③ 旗标正控 e2e（创建形态）───────────────────────────

  test("R③ 旗标正控 e2e: NodeEdit(create, in=归档节点, restoreChain=true) → 先拉回再创建、同链") {
    val ws = os.temp.dir(prefix = "nb-restore-r3-", deleteOnExit = false)
    os.makeDir.all(ws)
    val system = ActorSystem(s"restore-r3-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(ws)
      rt <- mountProject("restore-r3", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- seedArchivedChain(rt.store)
      // 负控：同一创建 + 不传旗标 → 归档上游仍可接线（D1 通道）但**不拉回**
      negCreate <- NodeEditTool
        .call(
          nodeInput(
            "restore-r3",
            "plain-follow",
            "description" -> Json.fromString("plain follow-up"),
            "task" -> Json.fromString("plain follow-up task"),
            "in" -> Json.fromString("n-a")
          ).asObject.get,
          ctx
        )
        .map(_.left.map(_.message))
      archNeg <- rt.store.archiveSnapshot
      activeNeg <- rt.store.snapshot
      // 正控：传旗标 → 先拉回整链再创建
      posCreate <- NodeEditTool
        .call(
          nodeInput(
            "restore-r3",
            "restored-follow",
            "description" -> Json.fromString("follow-up on restored chain"),
            "task" -> Json.fromString("follow-up on the restored chain"),
            "in" -> Json.fromString("n-a"),
            "restoreChain" -> Json.fromBoolean(true)
          ).asObject.get,
          ctx
        )
        .map(_.left.map(_.message))
      s <- rt.store.snapshot
      arch <- rt.store.archiveSnapshot
      combined <- rt.store.combinedNodes
      chains = FlowMapStore.topologicalChains(combined.values)
      followId = s.nodes.values.find(_.name == "restored-follow").map(_.id).getOrElse("")
      follow = s.nodes.get(followId)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(negCreate.isRight, s"plain reference onto an archived upstream is legal without the flag, got $negCreate")
      assert(!negCreate.exists(_.contains("[chain-restored]")), s"no restore without the flag, got $negCreate")
      assertEquals(
        archNeg.nodes.keySet,
        Set("n-a", "n-b"),
        "negative control: flag off keeps the chain archived (pure reference = D1 delivery only)"
      )
      assert(
        activeNeg.nodes.values.exists(_.name == "plain-follow"),
        "negative control node created in the active zone"
      )
      assert(posCreate.isRight, s"flagged create must succeed, got $posCreate")
      assert(
        posCreate.exists(_.contains("[chain-restored] chain-n-a back on the active map (2 node(s))")),
        s"tool result must lead with the restore notice, got $posCreate"
      )
      assertEquals(
        arch.nodes.keySet,
        Set.empty[String],
        "flagged create emptied the archive (whole chain restored first)"
      )
      assert(follow.exists(_.in.contains("n-a")), s"new node wired to the restored upstream, got ${follow.map(_.in)}")
      assertEquals(
        chains.map(_.id),
        List("chain-n-a"),
        s"restored members + the follow-up node = one derived chain: ${chains.map(c => c.id -> c.memberIds)}"
      )
      assert(chains.head.memberIds.contains(followId), "the follow-up node belongs to the restored chain")
    end for
  }

  // ── R④ 旗标正负控 e2e（编辑形态）─────────────────────────

  test("R④ 旗标正负控 e2e: 归档节点编辑——缺省拒绝（只放行 out 改接）；传旗标则拒绝闸让位") {
    val ws = os.temp.dir(prefix = "nb-restore-r4-", deleteOnExit = false)
    os.makeDir.all(ws)
    val system = ActorSystem(s"restore-r4-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(ws)
      rt <- mountProject("restore-r4", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- seedArchivedChain(rt.store)
      // (a) 负控：归档节点 + description 编辑、无旗标 → 现状拒绝
      neg <- NodeEditTool
        .call(
          nodeInput("restore-r4", "name-n-a", "description" -> Json.fromString("rewritten purpose")).asObject.get,
          ctx
        )
        .map(_.left.map(_.message))
      archAfterNeg <- rt.store.archiveSnapshot
      // (b) 正控：同一输入 + restoreChain=true → 先拉回（整链回活动区）再走原编辑流
      pos <- NodeEditTool
        .call(
          nodeInput(
            "restore-r4",
            "name-n-a",
            "description" -> Json.fromString("rewritten purpose"),
            "restoreChain" -> Json.fromBoolean(true)
          ).asObject.get,
          ctx
        )
        .map(_.left.map(_.message))
      s <- rt.store.snapshot
      arch <- rt.store.archiveSnapshot
      desc <- rt.store.snapshot.map(_.nodes.get("n-a").flatMap(_.description))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(neg.isLeft, s"archived node + description edit must be refused WITHOUT the flag, got $neg")
      assert(neg.left.exists(_.contains("is archived")), s"refusal must name the archived state, got $neg")
      assertEquals(archAfterNeg.nodes.keySet, Set("n-a", "n-b"), "(a) negative control: archive untouched")
      assert(pos.isRight, s"with restoreChain=true the edit must proceed, got $pos")
      assert(
        pos.exists(_.contains("[chain-restored] chain-n-a")),
        s"tool result must lead with the restore notice, got $pos"
      )
      assert(!pos.exists(_.contains("is archived")), s"the archived-edit gate must be lifted, got $pos")
      assertEquals(s.nodes.keySet, Set("n-a", "n-b"), "(b) whole chain back on the active map")
      assertEquals(arch.nodes.keySet, Set.empty[String], "(b) archive emptied")
      assertEquals(desc, Some("rewritten purpose"), "(b) the edit itself landed on the restored node")
    end for
  }

  // ── harness ──────────────────────────────────────────────

  private def mkResources(tmp: os.Path): IO[SharedResources] =
    for
      dispatcher <- cats.effect.std.Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- nebflow.core.FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, nebflow.llm.ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
    yield SharedResources(
      llm = new LlmHandle[IO]:
        def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
        def sendStream(
          req: LlmRequest,
          onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
        ): Stream[IO, StreamChunk] = Stream.empty
      ,
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
      sessionId = Some("restore-sid"),
      rootSessionId = Some("nebula-root"),
      sharedResources = Some(res),
      actorSystem = Some(system)
    )

  private def nodeInput(project: String, nodename: String, extra: (String, Json)*): Json =
    Json.obj(
      ("project" -> Json
        .fromString(project)) :: ("nodename" -> Json.fromString(nodename)) :: ("plugins" -> Json.arr()) :: extra.toList*
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

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear
end FlowMapRestoreChainSpec
