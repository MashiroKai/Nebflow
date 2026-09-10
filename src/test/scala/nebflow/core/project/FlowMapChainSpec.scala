package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentLibrary, SharedResources}
import nebflow.core.PathUtil
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, NodeTools, ToolContext}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * Flow Map 链级抽象 P0 语义地基测试（spec 20260910_flowmap-chain-abstraction-spec §2/
 * §4/§6）：
 *
 * - topologicalChains 判定算法（派生单点）：
 *   T① 弱连通分量——in∪out∪deps 无向并集（D1 deps 算链边）+ 名字形态 out 边解析（D9 正路）
 *   T② 跨活动/归档区——combinedNodes 合并集并链（D8）+ chainIdOf 判据（≥2 带/孤立不带）
 *   T③ 悬空名 out 边——跳过不连，两端各自成链（D9 负路）
 *   T④ 菱形多入口——一链两入口一终点（D5/D2/D7）
 *   T⑤ 孤立节点——单成员链（D6）
 *   T⑥ chainId 稳定性——链尾追加成员 id 不变；接线并链 id 归最早者
 *   T⑦ 终点判定——Nebula-only / 悬空 / out=Nil 都算终点；有效节点目标不算（D7）
 *   T⑧ 谱系边表 via 标注（D3）+ deps-only 下游非入口（D2）+ retry 回跳边不进拓扑（D4）
 * - sweep 拓扑口径（C4）：
 *   T⑨ 分量内全终态整链归档（batchId=chain-<最早>）；blocked 拖住连通链
 *   T⑩ 跨区续做分量——归档上游 + 活跃下游完成 → 归档进同链批（批 id 合并不孤儿化）
 * - 载荷契约（spec §6.2）：
 *   T⑪ 节点级 chainId 条件键 + chains 顶层旁挂（title 三级推导后端下发，前端零派生）
 */
class FlowMapChainSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-flowmap-chain"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "general")
  os.write.over(tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"general executor","tools":[],"category":"standalone"}""")
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
    NodeDef(id = id, name = s"name-$id", agent = "general", status = status,
      in = in, out = out, deps = deps, createdAt = createdAt)

  private def chainIds(nodes: Iterable[NodeDef]): List[String] =
    FlowMapStore.topologicalChains(nodes).map(_.id)

  private def chainOf(nodes: Iterable[NodeDef], id: String): ChainInfo =
    FlowMapStore.topologicalChains(nodes).find(_.id == id)
      .getOrElse(fail(s"chain $id not found in ${FlowMapStore.topologicalChains(nodes).map(_.id)}"))

  // ── T① 弱连通分量：in/out/deps 全并 + 名字形态 out 边 ──────

  test("T① 弱连通分量: in+out+deps 无向并集成一条链（D1），名字形态 out 边解析命中才连（D9 正路）") {
    // a --out--> b --out=Nebula--> ×；c --deps--> b（deps 单侧持有）；d --out("name-b")--> b（名字形态）
    val a = def0("a", t0, out = List(OutEdge("b")))
    val b = def0("b", t0 + 1000, in = List("a"), out = List(OutEdge.nebula))
    val c = def0("c", t0 + 2000, deps = List("b"))
    val d = def0("d", t0 + 3000, out = List(OutEdge("name-b"))) // "name-b" = b 的 name
    val chains = FlowMapStore.topologicalChains(List(a, b, c, d))
    assertEquals(chains.map(_.id), List("chain-a"), "all four nodes = one weakly connected component")
    val chain = chainOf(List(a, b, c, d), "chain-a")
    assertEquals(chain.memberIds, List("a", "b", "c", d.id))
    assertEquals(chain.entries, List("a", "d"), "a and d have in=Nil ∧ deps=Nil (D2 — out 边不影响入口判定)")
    assertEquals(chain.ends.toSet, Set("b", "c"), "b (Nebula-only out) and c (out=Nil) are ends (D7)")
    // 谱系边表：deps 弱关联以 via 标注保留（D3）；名字 out 边解析到 id
    assert(chain.edges.contains(ChainEdge("a", "b", "out")), s"edges=${chain.edges}")
    assert(chain.edges.contains(ChainEdge("a", "b", "in")), "in mirror edge kept with own via")
    assert(chain.edges.contains(ChainEdge("b", "c", "deps")), "deps edge annotated via=deps")
    assert(chain.edges.contains(ChainEdge(d.id, "b", "out")), "name-form out edge resolved to node id")
  }

  // ── T② 跨活动/归档区（D8）+ chainIdOf 判据 ──────────────

  test("T② 跨区并链: combinedNodes 合并集上归档上游与活跃下游同链（D8）；chainIdOf ≥2 才带") {
    val ws = os.temp.dir(prefix = "nb-chain-t2-", deleteOnExit = false)
    for
      store <- FlowMapStore.open("chain-t2", ws.toString)
      // 直种归档区：上游 A（completed，早 createdAt）
      archived = def0("n-arch-a", t0).copy(result = Some("archived upstream"))
      _ <- store.mutateArchive(a0 => a0.copy(nodes = a0.nodes + ("n-arch-a" -> archived)))
      // 活动区：下游 B 接线归档 A（D1 补投递同款形态）
      _ <- store.mutate(s => s.copy(nodes = s.nodes + ("n-b" -> def0("n-b", t0 + 5000, NodeLifecycle.Wiring, in = List("n-arch-a")))))
      combined <- store.combinedNodes
      chains = FlowMapStore.topologicalChains(combined.values)
      cidB <- store.chainIdOf("n-b")
      cidA <- store.chainIdOf("n-arch-a")
      // 孤立活动节点：单成员链 → chainIdOf = None（payload 零膨胀判据）
      _ <- store.mutate(s => s.copy(nodes = s.nodes + ("n-solo" -> def0("n-solo", t0 + 6000))))
      cidSolo <- store.chainIdOf("n-solo")
    yield
      assertEquals(combined.keySet, Set("n-arch-a", "n-b"),
        "combined fetched before n-solo was added — merge-set derivation domain")
      assertEquals(chains.find(_.id == "chain-n-arch-a").map(_.memberIds), Some(List("n-arch-a", "n-b")),
        "archived upstream and active downstream = one chain (cross-region merge)")
      assertEquals(cidB, Some("chain-n-arch-a"), "active member of multi-member chain carries chainId")
      assertEquals(cidA, Some("chain-n-arch-a"), "archived member carries the same chain id")
      assertEquals(cidSolo, None, "isolated single-node chain → no chainId (payload 零膨胀)")
  }

  // ── T③ 悬空名 out 边（D9 负路）─────────────────────────

  test("T③ 悬空名 out 边: 不连边不产生链归属——两端各自成链（D9）") {
    val x = def0("x", t0, out = List(OutEdge("ghost-name")))
    val y = def0("y", t0 + 1000)
    assertEquals(chainIds(List(x, y)).sorted, List("chain-x", "chain-y"),
      "dangling name edge must NOT link x to y")
    // 名字形态但目标已删除（同名节点不在集内）= 同款悬空
    val x2 = def0("x2", t0, out = List(OutEdge("name-y")))
    val y2 = def0("y2", t0 + 1000) // name-y2，不叫 name-y
    assertEquals(chainIds(List(x2, y2)).sorted, List("chain-x2", "chain-y2"),
      "name-form edge with no name match stays dangling")
  }

  // ── T④ 菱形多入口（D5）────────────────────────────────

  test("T④ 菱形多入口: 一条链、两个入口、一个终点（D5 弱连通分量归属唯一）") {
    val e1 = def0("e1", t0, out = List(OutEdge("m")))
    val e2 = def0("e2", t0 + 1000, out = List(OutEdge("m")))
    val m = def0("m", t0 + 2000, in = List("e1", "e2"))
    val chains = FlowMapStore.topologicalChains(List(e1, e2, m))
    assertEquals(chains.map(_.id), List("chain-e1"), "diamond = ONE chain")
    val chain = chains.head
    assertEquals(chain.memberIds, List("e1", "e2", "m"))
    assertEquals(chain.entries, List("e1", "e2"), "both fan-out sources are entries (D2)")
    assertEquals(chain.ends, List("m"), "merge node is the sole end (D7)")
  }

  // ── T⑤ 孤立节点（D6）─────────────────────────────────

  test("T⑤ 孤立节点: 自成单成员链，entries/ends=自身（D6）") {
    val z = def0("z", t0)
    val chains = FlowMapStore.topologicalChains(List(z))
    assertEquals(chains.map(_.id), List("chain-z"))
    assertEquals(chains.head.memberIds, List("z"))
    assertEquals(chains.head.entries, List("z"))
    assertEquals(chains.head.ends, List("z"), "out=Nil → the node is its own end")
    assertEquals(chains.head.edges, Nil)
  }

  // ── T⑥ chainId 稳定性 ────────────────────────────────

  test("T⑥ chainId 稳定性: 链尾追加成员 id 不变；两独立链接线并链后 id 归最早者") {
    val a = def0("a", t0, out = List(OutEdge("b")))
    val b = def0("b", t0 + 1000, in = List("a"))
    assertEquals(chainIds(List(a, b)), List("chain-a"))
    // 链尾追加 c：最早节点仍 a → id 稳定（续做场景）
    val c = def0("c", t0 + 2000, in = List("b"), deps = List("a"))
    assertEquals(chainIds(List(a, b, c)), List("chain-a"), "tail append keeps chain id")
    // 两条独立链（a→b 与 z→w）接线 b.out=z 后并链：id = 最早 createdAt 者的 a
    val z = def0("z", t0 + 3000, out = List(OutEdge("w")))
    val w = def0("w", t0 + 4000, in = List("z"))
    assertEquals(chainIds(List(a, b, z, w)).sorted, List("chain-a", "chain-z"), "before wiring: two chains")
    val bLinked = b.copy(out = List(OutEdge("z")))
    assertEquals(chainIds(List(a, bLinked, z, w)), List("chain-a"), "after wiring: merged into earliest-anchored id")
  }

  // ── T⑦ 终点判定（D7）─────────────────────────────────

  test("T⑦ 终点判定: Nebula-only / 悬空 / out=Nil 皆终点；有节点目标非终点（D7）") {
    val n1 = def0("n1", t0, out = List(OutEdge.nebula))                      // Nebula-only → end
    val n2 = def0("n2", t0 + 1000, out = List(OutEdge.nebula, OutEdge("m"))) // 有节点目标 → not end
    val m = def0("m", t0 + 2000, in = List("n2"))                            // out=Nil → end
    val n3 = def0("n3", t0 + 3000).copy(notifyDispatcher = true)             // dispatcher-only（不占边）→ end
    val n4 = def0("n4", t0 + 4000, out = List(OutEdge("ghost")))             // 悬空 → end
    val all = List(n1, n2, m, n3, n4)
    val chains = FlowMapStore.topologicalChains(all)
    assertEquals(chains.size, 4, s"n2+m connected; n1/n3/n4 isolated — got ${chains.map(_.id)}")
    assertEquals(chainOf(all, "chain-n2").ends, List("m"), "n2 has live node target → not end; m is end")
    assertEquals(chainOf(all, "chain-n1").ends, List("n1"), "Nebula-only out is an end")
    assertEquals(chainOf(all, "chain-n3").ends, List("n3"), "notifyDispatcher occupies no edge → end")
    assertEquals(chainOf(all, "chain-n4").ends, List("n4"), "dangling out target is an end")
  }

  // ── T⑧ via 边表 + D2 deps 非入口 + D4 retry 不进拓扑 ────

  test("T⑧ 谱系边表: via 标注（D3）；deps-only 下游非入口（D2）；retry 回跳边不进链拓扑（D4）") {
    val a = def0("a", t0, out = List(OutEdge("b")))
    val b = def0("b", t0 + 1000, in = List("a"), deps = List("a"))
    val chain = chainOf(List(a, b), "chain-a")
    // D2：b in=Nil 但 deps=[a] 非空 → 不是入口（与创建期 ins.isEmpty 独占启动对齐）
    assertEquals(chain.entries, List("a"), "deps-only downstream is NOT an entry (D2 双空)")
    // D3：同一双端 in 镜像边与 out 边各自保留，deps 弱关联单独标注
    assert(chain.edges.contains(ChainEdge("a", "b", "in")), s"edges=${chain.edges}")
    assert(chain.edges.contains(ChainEdge("a", "b", "out")), s"edges=${chain.edges}")
    assert(chain.edges.contains(ChainEdge("a", "b", "deps")), s"edges=${chain.edges}")
    // D4：retry 回跳是策略不是结构边——r 配 retry 指向 a，但不进任何邻接表 → 孤立
    val r = def0("r", t0 + 2000).copy(retry = Some(RetryPolicy(upstream = "a", max = 3)))
    val rChain = chainOf(List(a, b, r), "chain-r")
    assertEquals(rChain.memberIds, List("r"), "retry back-jump must NOT put r into a's chain")
    assert(!rChain.edges.exists(e => e.from == "r" || e.to == "r"), "no edge touches the retry node")
  }

  // ── T⑨ sweep 拓扑口径（C4）────────────────────────────

  test("T⑨ sweep 拓扑口径: 连通链全终态整链归档（batchId=chain-<最早>）；blocked 拖住连通链") {
    val ws = os.temp.dir(prefix = "nb-chain-t9-", deleteOnExit = false)
    for
      store <- FlowMapStore.open("chain-t9", ws.toString)
      _ <- store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        // 链1：a→b 全终态（completed×2）→ 整链归档
        "s9-a" -> def0("s9-a", t0, out = List(OutEdge("s9-b"))).copy(completedAt = Some(t0 + 100)),
        "s9-b" -> def0("s9-b", t0 + 1000, in = List("s9-a")).copy(completedAt = Some(t0 + 1100)),
        // 链2：c→d 含 blocked → 整链保留（活跃链兄弟语义）
        "s9-c" -> def0("s9-c", t0 + 2000, out = List(OutEdge("s9-d"))).copy(completedAt = Some(t0 + 2100)),
        "s9-d" -> def0("s9-d", t0 + 3000, in = List("s9-c"), status = NodeLifecycle.Blocked)
      )))
      removed <- store.sweepCompletedChains(t0 + 10000).map(_.sorted)
      s <- store.snapshot
      arch <- store.archiveSnapshot
      bt <- store.archiveBatches
    yield
      assertEquals(removed, List("s9-a", "s9-b"), "fully-terminal chain swept as a whole")
      assertEquals(bt.keySet, Set("chain-s9-a"), "batch id = chain-<earliest member in component>")
      assertEquals(bt("chain-s9-a").nodeIds, Set("s9-a", "s9-b"))
      assertEquals(s.nodes.keySet, Set("s9-c", "s9-d"), "blocked member holds its whole chain")
      assertEquals(arch.nodes.keySet, Set("s9-a", "s9-b"))
  }

  // ── T⑩ 跨区续做分量归档（批 id 合并不孤儿化）──────────────

  test("T⑩ 跨区续做分量: 归档上游 + 活跃下游完成 → 归档进同链批（id 合并，旧批成员不孤儿化）") {
    val ws = os.temp.dir(prefix = "nb-chain-t10-", deleteOnExit = false)
    val archDir = ws / ".nebflow" / "flow-map-archive"
    for
      store <- FlowMapStore.open("chain-t10", ws.toString)
      // 第 1 步：孤立上游 A 完成 → 单成员链归档（batch chain-t10a）
      _ <- store.mutate(s => s.copy(nodes = s.nodes +
        ("t10-a" -> def0("t10-a", t0).copy(result = Some("upstream"), completedAt = Some(t0 + 100)))))
      removed1 <- store.sweepCompletedChains(t0 + 1000)
      bt1 <- store.archiveBatches
      // 第 2 步：下游 B 接线归档 A（续做形态）→ 完成后 sweep
      _ <- store.mutate(s => s.copy(nodes = s.nodes +
        ("t10-b" -> def0("t10-b", t0 + 5000, in = List("t10-a")).copy(result = Some("downstream"), completedAt = Some(t0 + 5100)))))
      removed2 <- store.sweepCompletedChains(t0 + 10000)
      bt2 <- store.archiveBatches
      s <- store.snapshot
      arch <- store.archiveSnapshot
      batchJson <- IO.blocking(os.read(archDir / "chain-t10-a.json"))
      parsed <- IO.fromEither(parse(batchJson))
      fileNodes = parsed.hcursor.downField("nodes").keys.getOrElse(Iterable.empty).toSet
    yield
      assertEquals(removed1, List("t10-a"))
      assertEquals(bt1.keySet, Set("chain-t10-a"))
      assertEquals(removed2, List("t10-b"), "only the active member moves")
      assertEquals(bt2.keySet, Set("chain-t10-a"), "merged component re-archives under the same chain id")
      assertEquals(bt2("chain-t10-a").nodeIds, Set("t10-a", "t10-b"),
        "batch meta merges member sets — old members not orphaned")
      assertEquals(fileNodes, Set("t10-a", "t10-b"), "batch file rewritten to the full chain")
      assertEquals(s.nodes.keySet, Set.empty)
      assertEquals(arch.nodes.keySet, Set("t10-a", "t10-b"))
  }

  // ── T⑪ 载荷契约：chainId 条件键 + chains 顶层旁挂 ─────────

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

  test("T⑪ 载荷契约: chainId 条件键（≥2 带/孤立不带）+ chains 旁挂（title 三级推导，前端零派生）") {
    val ws = tempRoot / "ws-t11"
    os.makeDir.all(ws)
    val system = ActorSystem(s"chain-t11-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot, new RecordingLlm)
      store <- FlowMapStore.open("chain-t11", ws.toString)
      engine = new NodeEngine(
        store, system, res,
        wsSendFn = (_: Json) => IO.unit,
        workspace = ws.toString,
        rootSessionId = "nebula-root",
        projectName = "chain-t11",
        emitEvent = (_, _, _) => IO.unit
      )
      pd = ProjectDef(name = "chain-t11", workspace = ws.toString, agentFile = (ws / "AGENTS.md").toString, createdAt = System.currentTimeMillis())
      rt = ProjectRuntime(pd, store, engine, system, res, None)
      // 链1（title 层①：description）；链2（title 层②：首节点 task 预览）；
      // 链3（title 层③：chain id 兜底）；z 孤立（无 chainId）
      _ <- store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        "p-a" -> def0("p-a", t0, out = List(OutEdge("p-b"))).copy(description = Some("修复登录回归"), completedAt = Some(t0 + 1)),
        "p-b" -> def0("p-b", t0 + 1000, in = List("p-a")),
        "p-c" -> def0("p-c", t0 + 2000, out = List(OutEdge("p-d"))).copy(task = Some("第一行任务说明\n第二行不该出现")),
        "p-d" -> def0("p-d", t0 + 3000, in = List("p-c")),
        "p-e" -> def0("p-e", t0 + 4000, out = List(OutEdge("p-f"))),
        "p-f" -> def0("p-f", t0 + 5000, in = List("p-e")),
        "p-z" -> def0("p-z", t0 + 6000)
      )))
      payload <- NodeTools.buildNodeListPayload(rt)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      val nodes = payload.hcursor.downField("nodes").as[List[Json]].toOption.getOrElse(Nil)
      def nodeJson(id: String): Json = nodes.find(_.hcursor.get[String]("id").toOption.contains(id)).getOrElse(fail(s"$id missing"))
      // 节点级 chainId 条件键：多成员链成员带、孤立节点不带
      assertEquals(nodeJson("p-a").hcursor.get[String]("chainId").toOption, Some("chain-p-a"))
      assertEquals(nodeJson("p-f").hcursor.get[String]("chainId").toOption, Some("chain-p-e"))
      assert(!nodeJson("p-z").asObject.exists(_.contains("chainId")), "isolated node must NOT carry chainId")
      // chains 顶层旁挂：三条多成员链（按 id 排序）；孤立链不入旁挂
      val chains = payload.hcursor.downField("chains").as[List[Json]].toOption.getOrElse(Nil)
      assertEquals(chains.map(_.hcursor.get[String]("id").toOption.getOrElse("")).sorted,
        List("chain-p-a", "chain-p-c", "chain-p-e"))
      // title 三级推导（后端下发，前端零派生）
      def titleOf(cid: String): String =
        chains.find(_.hcursor.get[String]("id").toOption.contains(cid))
          .flatMap(_.hcursor.get[String]("title").toOption).getOrElse(fail(s"chain $cid missing"))
      assertEquals(titleOf("chain-p-a"), "修复登录回归", "level 1: first description on the chain")
      assertEquals(titleOf("chain-p-c"), "第一行任务说明", "level 2: first node's task first line")
      assertEquals(titleOf("chain-p-e"), "chain-p-e", "level 3: chain id fallback")
      // 条目形状：entries/ends/memberIds
      val chainA = chains.find(_.hcursor.get[String]("id").toOption.contains("chain-p-a")).getOrElse(fail("chain-p-a missing"))
      assertEquals(chainA.hcursor.downField("entries").as[List[String]].toOption, Some(List("p-a")))
      assertEquals(chainA.hcursor.downField("ends").as[List[String]].toOption, Some(List("p-b")))
      assertEquals(chainA.hcursor.downField("memberIds").as[List[String]].toOption, Some(List("p-a", "p-b")))
  }

end FlowMapChainSpec
