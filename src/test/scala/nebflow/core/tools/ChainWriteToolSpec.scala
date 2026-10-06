package nebflow.core.tools

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.SharedResources
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.core.tools.FileLockManager
import nebflow.llm.ModelCandidate
import nebflow.shared.{Defaults, LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk, ThinkingConfig}
import nebflow.shared.given

import scala.concurrent.duration.*

/**
 * N1 刀2 · ChainWrite 整链登记验收（v3 §5 S4「链齐才跑」+ A4 作用域闸 + A5 双寻址
 * ＋错误码族 v2 §6.I 承继）：
 *
 *  - 纯函数校验族逐码（CHAIN_EMPTY / TOO_MANY / DUPLICATE_ID / ID_INVALID /
 *    NAME_INVALID / NAME_CONFLICT / NAME_ID_COLLISION / CYCLE / TASK_REQUIRED）——
 *    全部拒绝零副作用（all-or-nothing 第一站）。
 *  - 唯一域现读（R2 name=活动区 / R4 name≠两区 id / id=两区全集）＋ deps 规范化
 *    （R6：批内 id|批内 name 皆收、链外仅 id、歧义 CHAIN_ADDR_AMBIGUOUS、未知
 *    CHAIN_DEPS_UNKNOWN）。
 *  - 整链登记往返 E2E：ACK 携链号 → 全员 Pending 落 scope store（**零 spawn**）
 *    → 入口节点放行（runDetached fork）跑至 completed → 下游经 settleDeps 完成
 *    → chain-registered 审计事件在基工作区事件流 → ChainLedger 链号出生。
 *  - 双寻址四态（id 命中 / name 命中 / 歧义列候选 / 未找到）。
 *  - A4 作用域闸：非 delegate fail-closed（CHAINWRITE_SCOPE_FORBIDDEN）；`project`
 *    参数显式拒；宿主查无（DELEGATE_SCOPE_UNAVAILABLE）。
 *  - A6 必红（零上游建即放行摘除——delegate 侧）：delegate ctx NodeEdit 建入口
 *    节点 = **Pending 且无会话且回执无 started running**；per-project 分发器 ctx
 *    对照组照旧建即跑。
 */
class ChainWriteToolSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-chain-write-tool"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "general")
  os.write.over(
    tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"general executor","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")
  os.makeDir.all(tempRoot / "sessions")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    (nebflow.core.project.ProjectRuntimeRegistry.clear *> nebflow.core.project.DelegateFlowHosts.clear).unsafeRunSync()

  override def afterEach(context: munit.AfterEach): Unit =
    (nebflow.core.project.ProjectRuntimeRegistry.clear *> nebflow.core.project.DelegateFlowHosts.clear).unsafeRunSync()

  // ── 基建（DelegateFlowHostsSpec 同款骨架）────────────────────────────────

  private def mkResources(system: ActorSystem, llm: LlmHandle[IO]): IO[SharedResources] =
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
      sessionStore = SessionStore(tempRoot / "sessions", tempRoot / "tasks"),
      projectRoot = os.pwd,
      thinkingConfigRef = thinkingRef,
      rateLimiter = rateLimiter,
      fileChangeTracker = tracker,
      contextWindow = 100_000,
      agentLibrary = new nebflow.agent.AgentLibrary(tempRoot / "agents"),
      taskStore = nebflow.core.task.FileTaskStore,
      historyArchiver = null,
      fileLockManager = fileLocks,
      sessionModelOverrides = modelOverrides,
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = null,
      voiceMutedRef = voiceMuted
    )

  /** 捕获型 LLM：按「最后一条用户消息」路由剧本回复。 */
  private class ScriptLlm(respond: String => String):
    val fullReqs: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)

    def handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(
          req: LlmRequest,
          onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]]
      ): Stream[IO, StreamChunk] =
        val text = req.messages.map(_.textContent).mkString("\n---\n")
        Stream
          .eval(fullReqs.update(_ :+ text))
          .flatMap(_ => Stream(StreamChunk.TextDelta(respond(text)), StreamChunk.Done(None, None)))

  end ScriptLlm

  /** per-project 载体（A6 对照组用——分发器面挂载形态）。 */
  private def mountCarrier(name: String, ws: os.Path, system: ActorSystem, res: SharedResources): IO[nebflow.core.project.ProjectRuntime] =
    for
      store <- nebflow.core.project.FlowMapStore.open(name, ws.toString)
      engine = new nebflow.core.project.NodeEngine(
        store,
        system,
        res,
        wsSendFn = (_: io.circe.Json) => IO.unit,
        workspace = ws.toString,
        rootSessionId = "nebula-root",
        projectName = name,
        emitEvent = (_, _, _) => IO.unit,
        reportGateHold = Some(false)
      )
      pd = nebflow.core.project.ProjectDef(
        name = name,
        workspace = ws.toString,
        agentFile = (ws / "AGENTS.md").toString,
        createdAt = System.currentTimeMillis()
      )
      rt = nebflow.core.project.ProjectRuntime(pd, store, engine, system, res, None)
      _ <- nebflow.core.project.ProjectRuntimeRegistry.register(rt)
    yield rt

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

  /** delegate 宿主挂载（spec 缺省参数定版：确定性腿显式关）。 */
  private def mount(
      ws: os.Path,
      delegateId: String,
      system: ActorSystem,
      res: SharedResources
  ): IO[Option[nebflow.core.project.DelegateFlowRuntime]] =
    nebflow.core.project.DelegateFlowHosts.mount(
      delegateId = delegateId,
      project = None,
      baseWorkspace = ws.toString,
      engineCwd = ws.toString,
      system = system,
      resources = res,
      wsSend = None,
      reportGateHold = Some(false),
      rootNotifyQuietMs = Some(0),
      destroyWindowMs = Some(0L)
    )

  private def readEvents(ws: os.Path): IO[List[String]] =
    IO.blocking {
      val f = ws / ".nebflow" / "flow-map-events.jsonl"
      if os.exists(f) then os.read(f).linesIterator.toList else Nil
    }

  private def delegateCtx(delegateId: String, ws: os.Path): ToolContext =
    ToolContext(projectRoot = ws.toString, sessionId = Some(delegateId))

  private def nodesJson(items: (String, Option[String], String, List[String])*) =
    JsonObject(
      "nodes" -> io.circe.Json.arr(
        items.map { case (id, name, task, deps) =>
          val obj = List.newBuilder[(String, io.circe.Json)]
          obj += "id" -> id.asJson
          name.foreach(n => obj += "name" -> n.asJson)
          obj += "task" -> task.asJson
          if deps.nonEmpty then obj += "deps" -> deps.map(_.asJson).asJson
          io.circe.Json.obj(obj.result()*)
        }*
      )
    )

  private def namedChainJson(name: String, items: (String, Option[String], String, List[String])*) =
    JsonObject.fromIterable(nodesJson(items*).toMap + ("chainName" -> name.asJson))

  // ── 纯函数校验族（逐码；all-or-nothing 第一站）──────────────────────────

  test("parseNodes/validateStructure: the error-code family rejects with zero side effects") {
    // 缺 nodes 键（parseNodes 形态层）
    assertEquals(ChainWriteTool.parseNodes(JsonObject.empty).isLeft, true)
    // CHAIN_TASK_REQUIRED（task 缺失）
    val noTask = ChainWriteTool.parseNodes(nodesJson(("a", None, "", Nil)))
    assert(noTask.left.exists(_.message.contains(ChainWriteTool.Codes.TaskRequired)), noTask.toString)
    // CHAIN_EMPTY
    val empty = ChainWriteTool.validateStructure(Nil)
    assert(empty.left.exists(_.message.contains(ChainWriteTool.Codes.Empty)), empty.toString)
    // CHAIN_TOO_MANY_STEPS（cap = ChainWriteTool.MaxNodes）
    val many = (1 to ChainWriteTool.MaxNodes + 1).map(i => ChainWriteTool.ChainNode(s"n$i", None, "t", Nil, None)).toList
    val tooMany = ChainWriteTool.validateStructure(many)
    assert(tooMany.left.exists(_.message.contains(ChainWriteTool.Codes.TooMany)), tooMany.toString)
    // CHAIN_DUPLICATE_ID（批内）
    val dup = ChainWriteTool.validateStructure(List(
      ChainWriteTool.ChainNode("a", None, "t", Nil, None),
      ChainWriteTool.ChainNode("a", None, "t2", Nil, None)
    ))
    assert(dup.left.exists(_.message.contains(ChainWriteTool.Codes.DuplicateId)), dup.toString)
    // CHAIN_ID_INVALID（路径逃逸/空白/首字符非字母数字）
    Seq("../escape", "a b", ".hidden", "x/y").foreach { bad =>
      val inv = ChainWriteTool.validateStructure(List(ChainWriteTool.ChainNode(bad, None, "t", Nil, None)))
      assert(inv.left.exists(_.message.contains(ChainWriteTool.Codes.IdInvalid)), s"$bad: $inv")
    }
    // CHAIN_NAME_CONFLICT（批内重名）
    val dupName = ChainWriteTool.validateStructure(List(
      ChainWriteTool.ChainNode("a", Some("Same"), "t", Nil, None),
      ChainWriteTool.ChainNode("b", Some("Same"), "t", Nil, None)
    ))
    assert(dupName.left.exists(_.message.contains(ChainWriteTool.Codes.NameConflict)), dupName.toString)
    // CHAIN_NAME_ID_COLLISION（批内 name == 批内 id——R4 两空间不相交）
    val collision = ChainWriteTool.validateStructure(List(
      ChainWriteTool.ChainNode("a", None, "t", Nil, None),
      ChainWriteTool.ChainNode("b", Some("a"), "t", Nil, None)
    ))
    assert(collision.left.exists(_.message.contains(ChainWriteTool.Codes.NameIdCollision)), collision.toString)
    // CHAIN_CYCLE（Kahn 判环）
    val cyclic = ChainWriteTool.validateStructure(List(
      ChainWriteTool.ChainNode("a", None, "t", List("b"), None),
      ChainWriteTool.ChainNode("b", None, "t", List("a"), None)
    ))
    assert(cyclic.left.exists(_.message.contains(ChainWriteTool.Codes.Cycle)), cyclic.toString)
    // 合法批零拒绝（ diamond 无环 + name 域干净）
    val ok = ChainWriteTool.validateStructure(List(
      ChainWriteTool.ChainNode("a", Some("Alpha"), "t", Nil, None),
      ChainWriteTool.ChainNode("b", None, "t", List("a"), None),
      ChainWriteTool.ChainNode("c", None, "t", List("a"), None),
      ChainWriteTool.ChainNode("d", None, "t", List("b", "c"), None)
    ))
    assertEquals(ok, Right(()))
  }

  test("Codes: the error-code family is single-point and collision-free") {
    val all = List(
      ChainWriteTool.Codes.Empty,
      ChainWriteTool.Codes.TooMany,
      ChainWriteTool.Codes.DuplicateId,
      ChainWriteTool.Codes.IdInvalid,
      ChainWriteTool.Codes.NameInvalid,
      ChainWriteTool.Codes.NameConflict,
      ChainWriteTool.Codes.NameIdCollision,
      ChainWriteTool.Codes.AddrAmbiguous,
      ChainWriteTool.Codes.NodeNotFound,
      ChainWriteTool.Codes.DepsUnknown,
      ChainWriteTool.Codes.Cycle,
      ChainWriteTool.Codes.TaskRequired,
      ChainWriteTool.Codes.NotDelegate,
      ChainWriteTool.Codes.ScopeUnavailable
    )
    assertEquals(all.distinct.size, all.size, "error codes must be distinct (single-point family)")
  }

  // ── A4 作用域闸（fail-closed）────────────────────────────────────────────

  test("scope gate: non-delegate ctx is fail-closed; project param is refused; unknown host is explicit") {
    val ws = os.temp.dir(prefix = "nb-cw-gate-", deleteOnExit = false)
    // 非 delegate 会话 → CHAINWRITE_SCOPE_FORBIDDEN（挂载面之外的调用一概拒）
    val nonDelegate = ChainWriteTool.call(nodesJson(("a", None, "t", Nil)), ToolContext(projectRoot = ws.toString))
    assert(nonDelegate.unsafeRunSync().left.exists(_.message.contains(ChainWriteTool.Codes.NotDelegate)), nonDelegate.toString)
    // delegate 会话带 project 参数 → 显式拒（作用域归属不可由客户端参数伪造）
    val withProject = ChainWriteTool.call(
      JsonObject.fromIterable(nodesJson(("a", None, "t", Nil)).toMap + ("project" -> "some-else".asJson)),
      delegateCtx("delegate-general-aaaaaaaa", ws)
    )
    assert(withProject.unsafeRunSync().isLeft, "project param must be refused")
    // delegate 会话 + 宿主查无 → DELEGATE_SCOPE_UNAVAILABLE
    val noHost = ChainWriteTool.call(nodesJson(("a", None, "t", Nil)), delegateCtx("delegate-general-bbbbbbbb", ws))
    assert(noHost.unsafeRunSync().left.exists(_.message.contains(ChainWriteTool.Codes.ScopeUnavailable)), noHost.toString)
  }

  // ── 整链登记往返 E2E（登记 → 全 pending → 放行 → settleDeps → 台账出生）──

  test("register-release round trip: ack carries the chain id, everything lands pending, the entry runs, the downstream settles, the ledger is born") {
    val ws = os.temp.dir(prefix = "nb-cw-e2e-", deleteOnExit = false)
    val system = ActorSystem(s"cw-e2e-${scala.util.Random.nextInt(1000000)}")
    val delegateId = "delegate-general-c1d2e3f4"
    val llm = ScriptLlm(_ => "CHAIN-NODE-DONE")
    for
      res <- mkResources(system, llm.handle)
      mo <- mount(ws, delegateId, system, res)
      h = mo.getOrElse(fail("host must mount"))
      ack <- ChainWriteTool.call(
        namedChainJson("cw-e2e-chain", ("survey", None, "survey the ground", Nil), ("build", None, "build on the survey", List("survey"))),
        delegateCtx(delegateId, ws)
      ).map(_.getOrElse(fail("register must succeed")))
      // ACK 携链号（非阻塞——登记即返）
      _ = assert(ack.contains("cw-e2e-chain"), ack)
      _ = assert(ack.contains("released"), ack)
      // 全员 Pending 落 scope store（登记时刻零 spawn：llm 零请求）
      s0 <- h.store.snapshot
      reqs0 <- llm.fullReqs.get
      _ <- waitUntil(30.seconds)(llm.fullReqs.get.map(_.nonEmpty)) // 入口放行（后台 fork）
      entry <- h.store.getNode("survey").map(_.getOrElse(fail("entry must exist")))
      _ <- waitUntil(45.seconds)(h.store.getNode("survey").map(n => n.exists(x => nebflow.core.project.NodeLifecycle.Terminal.contains(x.status))))
      done <- h.store.getNode("survey").map(_.getOrElse(fail("entry must exist")))
      _ <- waitUntil(45.seconds)(h.store.getNode("build").map(n => n.exists(x => nebflow.core.project.NodeLifecycle.Terminal.contains(x.status))))
      down <- h.store.getNode("build").map(_.getOrElse(fail("downstream must exist")))
      events <- readEvents(ws)
      ledger <- h.store.chainLedgerStore.snapshot
      _ <- nebflow.core.project.DelegateFlowHosts.unmount(delegateId)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(s0.nodes("survey").status, nebflow.core.project.NodeLifecycle.Pending, "entry lands PENDING (whole-chain gate: 链齐才跑)")
      assertEquals(s0.nodes("build").status, nebflow.core.project.NodeLifecycle.Pending, "downstream lands PENDING")
      assertEquals(s0.nodes("survey").chainId, Some("cw-e2e-chain"), "declared chain id is the membership")
      assertEquals(s0.nodes("build").chainId, Some("cw-e2e-chain"))
      assertEquals(reqs0, Nil, "registration must not spawn anything (release is a detached fork AFTER the ack state)")
      assertEquals(done.status, nebflow.core.project.NodeLifecycle.Completed, "entry node runs to completion")
      assertEquals(done.result, Some("CHAIN-NODE-DONE"))
      assertEquals(down.status, nebflow.core.project.NodeLifecycle.Completed, "downstream settles via deps (settleDeps)")
      assert(down.result.contains("CHAIN-NODE-DONE"), "upstream result block rides into the downstream input")
      assert(events.exists(e => e.contains("chain-registered") && e.contains("cw-e2e-chain")), "audit line in the base-workspace event stream")
      assert(ledger.entries.contains("cw-e2e-chain"), "ChainLedger entry born (reconcile single point)")
      assertEquals(ledger.entries("cw-e2e-chain").members.toList.sorted, List("build", "survey"))
    end for
  }

  // ── 唯一域现读（R2/R4/id 两区）＋ deps 规范化（R6）───────────────────────

  test("unique domains on the live scope: R2 name active-zone, R4 name≠id both zones, id both zones, R6 deps normalization") {
    val ws = os.temp.dir(prefix = "nb-cw-uniq-", deleteOnExit = false)
    val system = ActorSystem(s"cw-uniq-${scala.util.Random.nextInt(1000000)}")
    val delegateId = "delegate-general-d2e3f4a5"
    for
      res <- mkResources(system, ScriptLlm(_ => "UNUSED").handle)
      mo <- mount(ws, delegateId, system, res)
      h = mo.getOrElse(fail("host must mount"))
      // 预置活动区节点（id=n-seed, name=seed）——归档唯一域的可达面由 id 活动区命中
      // 与 R4（活动区 id）覆盖（归档写面无公开 API，归档 id 冲突判据同一集合函数）。
      _ <- h.store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        "n-seed" -> nebflow.core.project.NodeDef(
          id = "n-seed",
          name = "seed",
          agent = "general",
          status = nebflow.core.project.NodeLifecycle.Completed,
          createdAt = System.currentTimeMillis()
        )
      )))
      rt = h.projectRuntime
      // id 两区冲突（活动区命中即拒）
      idDup <- ChainWriteTool.registerChain(rt, List(ChainWriteTool.ChainNode("n-seed", None, "t", Nil, None)), None)
      // R2：name 撞活动区既有名
      nameDup <- ChainWriteTool.registerChain(rt, List(ChainWriteTool.ChainNode("fresh", Some("seed"), "t", Nil, None)), None)
      // R4：name == 既有 id
      nameId <- ChainWriteTool.registerChain(rt, List(ChainWriteTool.ChainNode("fresh", Some("n-seed"), "t", Nil, None)), None)
      // R6：批内 name 引用规范化（b 用 a 的 name "Alpha" 引用）
      ok <- ChainWriteTool.registerChain(
        rt,
        List(
          ChainWriteTool.ChainNode("aa", Some("Alpha"), "t", Nil, None),
          ChainWriteTool.ChainNode("bb", None, "t", List("Alpha"), None)
        ),
        None
      )
      norm <- h.store.getNode("bb").map(_.getOrElse(fail("bb must land")))
      // R6：链外未知 id → CHAIN_DEPS_UNKNOWN
      unknown <- ChainWriteTool.registerChain(rt, List(ChainWriteTool.ChainNode("cc", None, "t", List("ghost-x"), None)), None)
      // R6：链外歧义名（活动区现无重名——造两个同 name 的既有节点后按名引用）
      _ <- h.store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        "dup-1" -> nebflow.core.project.NodeDef(id = "dup-1", name = "twin", agent = "general", status = nebflow.core.project.NodeLifecycle.Completed, createdAt = System.currentTimeMillis()),
        "dup-2" -> nebflow.core.project.NodeDef(id = "dup-2", name = "twin", agent = "general", status = nebflow.core.project.NodeLifecycle.Completed, createdAt = System.currentTimeMillis())
      )))
      ambiguous <- ChainWriteTool.registerChain(rt, List(ChainWriteTool.ChainNode("dd", None, "t", List("twin"), None)), None)
      // chainName 在飞唯一域（活跃声明链号撞车）
      _ <- ChainWriteTool.registerChain(rt, List(ChainWriteTool.ChainNode("ee", None, "t", Nil, None)), Some("taken-chain"))
      chainDup <- ChainWriteTool.registerChain(rt, List(ChainWriteTool.ChainNode("ff", None, "t", Nil, None)), Some("taken-chain"))
      _ <- nebflow.core.project.DelegateFlowHosts.unmount(delegateId)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(idDup.left.exists(_.message.contains(ChainWriteTool.Codes.DuplicateId)), idDup.toString)
      assert(nameDup.left.exists(_.message.contains(ChainWriteTool.Codes.NameConflict)), nameDup.toString)
      assert(nameId.left.exists(_.message.contains(ChainWriteTool.Codes.NameIdCollision)), nameId.toString)
      assert(ok.isRight, ok.toString)
      assertEquals(norm.deps, List("aa"), "in-chain name reference normalized to the id form (R6)")
      assert(unknown.left.exists(_.message.contains(ChainWriteTool.Codes.DepsUnknown)), unknown.toString)
      assert(ambiguous.left.exists(_.message.contains(ChainWriteTool.Codes.AddrAmbiguous)), ambiguous.toString)
      assert(chainDup.left.exists(_.message.contains(ChainWriteTool.Codes.NameConflict)), chainDup.toString)
    end for
  }

  // ── 双寻址四态（NodeTools.resolveNodeRef 纯单点）─────────────────────────

  test("dual addressing: id hit wins, unique name resolves, ambiguity lists candidates, miss is explicit") {
    import nebflow.core.project.{NodeDef, NodeLifecycle}
    val nodes = Map(
      "id-1" -> NodeDef(id = "id-1", name = "alpha", agent = "general", status = NodeLifecycle.Completed, createdAt = 0L),
      "id-2" -> NodeDef(id = "id-2", name = "twin", agent = "general", status = NodeLifecycle.Completed, createdAt = 0L),
      "id-3" -> NodeDef(id = "id-3", name = "twin", agent = "general", status = NodeLifecycle.Completed, createdAt = 0L)
    )
    // id 命中即止（即使 name 也存在同名字面）
    assertEquals(NodeTools.resolveNodeRef(nodes, "id-1"), Right("id-1"))
    // name 唯一命中 → 归 id
    assertEquals(NodeTools.resolveNodeRef(nodes, "alpha"), Right("id-1"))
    // 歧义 → CHAIN_ADDR_AMBIGUOUS + 列候选
    NodeTools.resolveNodeRef(nodes, "twin") match
      case Left(err) =>
        assert(err.contains(ChainWriteTool.Codes.AddrAmbiguous), err)
        assert(err.contains("id-2") && err.contains("id-3"), s"candidates listed: $err")
      case other => fail(s"ambiguous name must be an explicit error, got $other")
    // 零命中 → CHAIN_NODE_NOT_FOUND
    assertEquals(NodeTools.resolveNodeRef(nodes, "ghost").isLeft, true)
    assert(NodeTools.resolveNodeRef(nodes, "ghost").left.exists(_.contains(ChainWriteTool.Codes.NodeNotFound)))
  }

  // ── A6 必红：零上游建即放行摘除（delegate 侧）＋ per-project 对照组 ────────

  test("A6: a delegate-ctx NodeEdit entry create lands PENDING with no session and no started-running receipt; the per-project dispatcher face still auto-starts") {
    val ws = os.temp.dir(prefix = "nb-cw-a6-", deleteOnExit = false)
    val wsPj = os.temp.dir(prefix = "nb-cw-a6-pj-", deleteOnExit = false)
    val system = ActorSystem(s"cw-a6-${scala.util.Random.nextInt(1000000)}")
    val delegateId = "delegate-general-e3f4a5b6"
    val llm = ScriptLlm(_ => "A6-NODE-DONE")
    for
      res <- mkResources(system, llm.handle)
      mo <- mount(ws, delegateId, system, res)
      _ = mo.getOrElse(fail("host must mount"))
      _ <- mountCarrier("a6-carrier", wsPj, system, res)
      // delegate ctx：建入口节点（task、无 in）→ 必红判据 = Pending ∧ 无会话 ∧ 回执无 started running
      delRes <- NodeEditTool.call(
        JsonObject(
          "nodename" -> "del-entry".asJson,
          "task" -> "delegate side entry task".asJson,
          "description" -> "delegate entry".asJson,
          "plugins" -> io.circe.Json.arr()
        ),
        delegateCtx(delegateId, ws)
      ).map(_.getOrElse(fail("delegate create must succeed")))
      _ <- IO.sleep(700.millis) // 若误自动启动，fork 在此窗口内必然发出 LLM 请求
      delReqs <- llm.fullReqs.get
      h0 <- nebflow.core.project.DelegateFlowHosts.get(delegateId)
      h = h0.getOrElse(fail("host still mounted"))
      // NodeEdit 建位 id = n-<8hex>（nodename 是 name 不是 id）——按名取
      delNode <- h.store.snapshot.map(_.nodes.values.find(_.name == "del-entry")).map(_.getOrElse(fail("delegate node must exist")))
      // per-project 对照组（分发器面照旧建即跑）
      pjRes <- NodeEditTool.call(
        JsonObject(
          "nodename" -> "pj-entry".asJson,
          "task" -> "per-project entry task".asJson,
          "description" -> "per-project entry".asJson,
          "plugins" -> io.circe.Json.arr()
        ),
        ToolContext(projectRoot = wsPj.toString, projectName = Some("a6-carrier"), sessionId = Some("dispatcher-1"))
      ).map(_.getOrElse(fail("per-project create must succeed")))
      _ <- waitUntil(45.seconds)(llm.fullReqs.get.map(_.nonEmpty)) // 对照组：入口真的开跑（LLM 请求到账）
      rtPj <- nebflow.core.project.ProjectRuntimeRegistry.get("a6-carrier").map(_.getOrElse(fail("carrier registered")))
      _ <- waitUntil(45.seconds)(
        rtPj.store.snapshot.map(_.nodes.values.find(_.name == "pj-entry")).map(n => n.exists(x => nebflow.core.project.NodeLifecycle.Terminal.contains(x.status)))
      )
      pjNode <- rtPj.store.snapshot.map(_.nodes.values.find(_.name == "pj-entry")).map(_.getOrElse(fail("per-project node must exist")))
      _ <- nebflow.core.project.DelegateFlowHosts.unmount(delegateId)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(!delRes.contains("started running"), s"delegate receipt must NOT claim a start: $delRes")
      assertEquals(delNode.status, nebflow.core.project.NodeLifecycle.Pending, "delegate entry lands PENDING (A6: 建位不自动 startNode)")
      assertEquals(delReqs, Nil, "no session spawned on delegate create (release goes through the registration gate / settle sweep)")
      assert(pjRes.contains("started running"), s"per-project face keeps the legacy entry auto-start receipt: $pjRes")
      assertEquals(pjNode.status, nebflow.core.project.NodeLifecycle.Completed, "per-project对照 entry runs to completion")
    end for
  }

end ChainWriteToolSpec
