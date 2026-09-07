package nebflow.core.project

import cats.effect.{IO, Ref}
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
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
 * FlowMap 引擎缺口修复回归（fix a/b，20260903 三合一节点）：
 *
 * - fix a（多 barrier 提前触发 / 新建边→归档上游崩溃）：
 *   ②-a 归档上游补建 in 边（真实 TTL 归档路径）→ 补投递计入 barrier、
 *      运行中第二上游完成前不启动（不提前触发）；
 *   ②-b 双归档上游 + deps 等待：等齐 in 全部上游 + deps 完成，deps settle 时
 *      一次性以完整输入启动（子集启动 = 提前触发，被 no-subset 断言拦截）。
 * - fix b（已存在边+归档上游不补投递 / 悬空结果节点接线补投递）：
 *   ③ 悬空 completed 节点（归档变体）NodeEdit(out=下游) → 补投递 + barrier 结算；
 *   ③-b 活动区悬空变体回归（既有 completed+newOut 投递分支不回归）；
 *   ④ 已存在边 + 归档上游（n-219106db 实证损伤形态：in 有上游、deliveredTo 恒空）
 *      → NodeEdit(out=下游) 补投递，且 in 不产生重复条目；
 *   ④-c 补投递完整性：in 里未投递的归档上游（不在本次 adds 里）也被补投
 *      （edit-append 扩展——只投 adds 时该用例永久等待 = 变异红）。
 * - 归档编辑域守卫：归档节点只接受 out 改接，task/agent/in/deps/abandon 拒绝。
 *
 * 变异验红方式（真实 revert 执行，证据见节点结果报告）：
 * M-a 将 setOut 恢复为活动区直取版本 → ②-a/②-b/③/④ 红（编辑抛
 *     NoSuchElementException）；
 * M-b 将 edit-append 补投递恢复为仅 adds → ④-c 红（下游永久等待直至超时）。
 * 两者均为「修复前用例红 / 修复后绿」的真实执行，非纯推演。
 */
class NodeEdgeRepairSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-node-edge-repair"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "test-agent")
  os.write.over(
    tempRoot / "agents" / "test-agent" / "agent.json",
    """{"name":"test-agent","description":"edge-repair regression agent","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "test-agent" / "system.md", "# test-agent\n")
  // 2026-09-05 agent 退役：新建节点执行统一 general——fixture 侧补 general agent
  os.makeDir.all(tempRoot / "agents" / "general")
  os.write.over(tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"general executor","tools":[],"category":"standalone"}""")
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  /** 捕获输入 LLM：记录每次请求的 user 文本。 */
  private class CaptureLlm(delayOf: String => FiniteDuration = _ => 0.millis):
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
      sessionId = Some("edge-sid"),
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

  /** 按名取节点 id（活动区）。 */
  private def idOf(rt: ProjectRuntime, name: String): IO[String] =
    rt.store.snapshot.map(_.nodes.values.find(_.name == name)).map {
      case Some(n) => n.id
      case None    => fail(s"node '$name' must exist in active area")
    }

  private def nodeById(rt: ProjectRuntime, id: String): IO[Option[NodeDef]] =
    rt.store.snapshot.map(_.nodes.get(id))

  private def waitStatus(rt: ProjectRuntime, name: String, statuses: Set[String]): IO[Unit] =
    waitUntil(15.seconds) {
      rt.store.snapshot.map(_.nodes.values.find(_.name == name)).flatMap {
        case Some(n) => IO.pure(statuses.contains(n.status))
        case None    => IO.pure(false)
      }
    }

  /** 让指定已完成节点悬空化（out 断开）并走真实链级归档路径移入归档区。
    * 裁定④链级 sweep 口径：整链全终态才归档——夹具把目标节点 createdAt 回拨 10min
    * 自成一批（与同测在飞节点间隔 >120s 批窗口），整批全终态 → 即时归档。 */
  private def archiveDangling(rt: ProjectRuntime, name: String): IO[String] =
    for
      id <- idOf(rt, name)
      _ <- waitStatus(rt, name, Set(NodeLifecycle.Completed))
      _ <- rt.store.mutate { s =>
        s.nodes.get(id) match
          case Some(fresh) =>
            s.copy(nodes = s.nodes.updated(id, fresh.copy(
              out = None, // 悬空（陈旧 out 覆盖时代的历史损伤形态 / LLM 断开写法）
              createdAt = System.currentTimeMillis() - 600000))) // 自成一批（链级 sweep 批次隔离）
          case None => s
      }
      removed <- rt.store.sweepCompletedChains(System.currentTimeMillis())
      _ <- assertIO(IO(removed.contains(id)), true, "node must be swept into archive")
      archived <- rt.store.findNode(id)
      _ <- assertIO(IO(archived.map(_.status)), Some(NodeLifecycle.Completed), "archived copy must keep completed status")
    yield id

  /** 子集启动断言（变异验红锚「不提前触发」）：任何携带上游结果头的已启动输入
    * 必须携带全部预期头——只出现一部分 = 以不完整 barrier 提前启动。 */
  private def assertNoSubsetStart(inputs: List[String], headers: String*): Unit =
    inputs.foreach { in =>
      val hits = headers.count(in.contains)
      if hits > 0 then
        assert(hits == headers.length,
          s"premature start detected — an input carried $hits/${headers.length} upstream headers (subset barrier): ${in.take(220)}")
    }

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  // ── ②-a 多 barrier + 归档上游补建边：等齐才触发 ──────────

  test("②-a append ARCHIVED upstream into partial barrier: delivered immediately, downstream waits for the RUNNING upstream (no premature start)") {
    val ws = tempRoot / "ws-arch-append"
    os.makeDir.all(ws)
    val system = ActorSystem(s"edge-apa-${scala.util.Random.nextInt(100000)}")
    val llm = CaptureLlm(text => if text.contains("slow-r") then 2500.millis else 0.millis)
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("edge-arch-append", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // W（wiring）先建，R 入口 out→W（R 运行 2.5s → W 的 barrier 挂起等 R）
      // W store 直种（20260903 创建必带 out 新规范下 out-only wiring 节点不可经 NodeEdit 创建）
      _ <- rt.store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        "n-w" -> NodeDef(id = "n-w", name = "w-w", agent = "test-agent",
          status = NodeLifecycle.Wiring, out = Some("Nebula"), createdAt = System.currentTimeMillis()))))
      wId <- idOf(rt, "w-w")
      _ <- nodeEdit(nodeInput("edge-arch-append", "run-r", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("slow-r"), "out" -> Json.fromString(wId)), ctx)
      _ <- waitStatus(rt, "run-r", Set(NodeLifecycle.Running))
      // A 完成后悬空化 + 真实 TTL 归档
      _ <- nodeEdit(nodeInput("edge-arch-append", "done-a", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("done-result-A"), "out" -> Json.fromString("Nebula")), ctx)
      aId <- archiveDangling(rt, "done-a")
      rId <- idOf(rt, "run-r")
      // fix a：补建 in 边指向已归档上游——修复前 setOut 崩溃（NoSuchElementException）
      edited <- nodeEdit(nodeInput("edge-arch-append", "w-w", "in" -> Json.fromString(aId)), ctx)
      _ <- waitUntil(10.seconds)(nodeById(rt, wId).map(_.exists(_.deliveredTo.contains(aId))))
      partial <- nodeById(rt, wId).map(_.getOrElse(fail("W must exist")))
      // R 完成 → barrier 归零 → W 启动且输入含两份结果
      _ <- waitStatus(rt, "run-r", Set(NodeLifecycle.Completed))
      _ <- waitStatus(rt, "w-w", Set(NodeLifecycle.Completed))
      w <- nodeById(rt, wId).map(_.getOrElse(fail("W must exist")))
      allInputs <- llm.inputs.get
      wInput = allInputs.find(t => t.contains("=== Node run-r ===") && t.contains("=== Node done-a ==="))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(edited.isRight, s"edit must succeed against archived upstream, got: $edited")
      assert(partial.deliveredTo.contains(aId), s"archived upstream must be delivered right after append, got ${partial.deliveredTo}")
      assertEquals(partial.status, NodeLifecycle.Wiring, s"W must still wait for R at the checkpoint (no premature start), got ${partial.status}")
      assertEquals(w.deliveredTo.toSet, Set(rId, aId), s"W must receive both, got ${w.deliveredTo}")
      assert(wInput.isDefined, s"W input must carry BOTH results, got inputs=${allInputs.map(_.take(150))}")
      assertNoSubsetStart(allInputs, "=== Node run-r ===", "=== Node done-a ===")
  }

  // ── ②-b 双归档上游 + deps 等待：等齐 in + deps 一次性启动 ──

  test("②-b two ARCHIVED upstreams appended under a deps wait: starts ONCE with full input when deps completes (no subset start)") {
    val ws = tempRoot / "ws-arch-deps"
    os.makeDir.all(ws)
    val system = ActorSystem(s"edge-apd-${scala.util.Random.nextInt(100000)}")
    val llm = CaptureLlm(text => if text.contains("slow-x") then 2500.millis else 0.millis)
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("edge-arch-deps", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // W（wiring）+ X（deps 等待对象，运行 2.5s）。W store 直种（20260903 创建必带
      // out 新规范下 out-only wiring 节点不可经 NodeEdit 创建）
      _ <- rt.store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        "n-w" -> NodeDef(id = "n-w", name = "w-w", agent = "test-agent",
          status = NodeLifecycle.Wiring, out = Some("Nebula"), createdAt = System.currentTimeMillis()))))
      wId <- idOf(rt, "w-w")
      _ <- nodeEdit(nodeInput("edge-arch-deps", "slow-x", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("slow-x"), "out" -> Json.fromString("Nebula")), ctx)
      xId <- idOf(rt, "slow-x")
      _ <- waitStatus(rt, "slow-x", Set(NodeLifecycle.Running))
      // W 挂 deps（X 运行中 → deps 未满足，W 保持 wiring）
      _ <- nodeEdit(nodeInput("edge-arch-deps", "w-w", "deps" -> Json.fromString(xId)), ctx)
      // A、B 完成后悬空化 + 归档
      _ <- nodeEdit(nodeInput("edge-arch-deps", "done-a", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("done-result-A"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- nodeEdit(nodeInput("edge-arch-deps", "done-b", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("done-result-B"), "out" -> Json.fromString("Nebula")), ctx)
      aId <- archiveDangling(rt, "done-a")
      bId <- archiveDangling(rt, "done-b")
      // 逐条补建归档 in 边（每次补投递都计入 barrier，deps 未满足期间不得启动）
      _ <- nodeEdit(nodeInput("edge-arch-deps", "w-w", "in" -> Json.fromString(aId)), ctx)
      _ <- waitUntil(10.seconds)(nodeById(rt, wId).map(_.exists(_.deliveredTo.contains(aId))))
      mid <- nodeById(rt, wId).map(_.getOrElse(fail("W must exist")))
      _ <- nodeEdit(nodeInput("edge-arch-deps", "w-w", "in" -> Json.fromString(bId)), ctx)
      _ <- waitUntil(10.seconds)(nodeById(rt, wId).map(_.exists(_.deliveredTo.contains(bId))))
      mid2 <- nodeById(rt, wId).map(_.getOrElse(fail("W must exist")))
      // X 完成 → settleDeps → in 已归零 + deps 满足 → 以完整输入一次性启动
      _ <- waitStatus(rt, "slow-x", Set(NodeLifecycle.Completed))
      _ <- waitStatus(rt, "w-w", Set(NodeLifecycle.Completed))
      w <- nodeById(rt, wId).map(_.getOrElse(fail("W must exist")))
      allInputs <- llm.inputs.get
      wInput = allInputs.find(t => t.contains("=== Node done-a ===") && t.contains("=== Node done-b ==="))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(mid.status, NodeLifecycle.Wiring, "W must not start after first append (deps still waiting)")
      assertEquals(mid2.status, NodeLifecycle.Wiring, "W must not start after second append (deps still waiting)")
      assertEquals(w.status, NodeLifecycle.Completed, "W must complete after deps settle")
      assert(wInput.isDefined, s"W input must carry BOTH archived results, got inputs=${allInputs.map(_.take(150))}")
      assertNoSubsetStart(allInputs, "=== Node done-a ===", "=== Node done-b ===")
  }

  // ── ③ 悬空结果节点（归档变体）接线补投递 ────────────────

  test("③ archived dangling result wired via NodeEdit(out=downstream): re-delivered, barrier settles, downstream starts") {
    val ws = tempRoot / "ws-arch-wire"
    os.makeDir.all(ws)
    val system = ActorSystem(s"edge-aw-${scala.util.Random.nextInt(100000)}")
    val llm = CaptureLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("edge-arch-wire", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // W store 直种（20260903 创建必带 out 新规范下 out-only wiring 节点不可经 NodeEdit 创建）
      _ <- rt.store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        "n-w" -> NodeDef(id = "n-w", name = "w-w", agent = "test-agent",
          status = NodeLifecycle.Wiring, out = Some("Nebula"), createdAt = System.currentTimeMillis()))))
      wId <- idOf(rt, "w-w")
      _ <- nodeEdit(nodeInput("edge-arch-wire", "done-a", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("dangling-result-A"), "out" -> Json.fromString("Nebula")), ctx)
      aId <- archiveDangling(rt, "done-a") // 悬空 + 归档
      // fix b：按名编辑归档节点设 out → 补投递（修复前：按名只在活动区找 → 落
      // createNode 同名重复节点，结果永不补投）
      edited <- nodeEdit(nodeInput("edge-arch-wire", "done-a", "out" -> Json.fromString(wId)), ctx)
      _ <- waitStatus(rt, "w-w", Set(NodeLifecycle.Completed))
      w <- nodeById(rt, wId).map(_.getOrElse(fail("W must exist")))
      archA <- rt.store.findNode(aId)
      allInputs <- llm.inputs.get
      activeNames <- rt.store.snapshot.map(_.nodes.values.filter(_.name == "done-a").toList)
      wInput = allInputs.find(_.contains("=== Node done-a ==="))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(edited.isRight, s"archived out-edit must succeed, got: $edited")
      assert(w.in.contains(aId), s"W.in must reference A, got ${w.in}")
      assert(w.deliveredTo.contains(aId), s"W must have received A's result, got ${w.deliveredTo}")
      assertEquals(w.status, NodeLifecycle.Completed, "W must start after re-delivery and complete")
      assert(wInput.isDefined, s"W input must carry A's result header, got inputs=${allInputs.map(_.take(150))}")
      // 归档副本的 out 单权威补写（setOut 归档感知）
      assertEquals(archA.flatMap(_.out), Some(wId), "archived A.out must be rewritten to W (single-authority kept in archive)")
      // 不产生同名重复节点：活动区不得出现新的 done-a（归档原件保持在归档区）
      assertEquals(activeNames, Nil, "no duplicate active node may be created for an archived name edit")
      assert(archA.map(_.id).contains(aId), "archived original must keep its id")
  }

  test("③-b ACTIVE dangling result wired via NodeEdit(out=downstream): still re-delivered (existing branch regression)") {
    val ws = tempRoot / "ws-active-wire"
    os.makeDir.all(ws)
    val system = ActorSystem(s"edge-acw-${scala.util.Random.nextInt(100000)}")
    val llm = CaptureLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("edge-active-wire", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // W store 直种（20260903 创建必带 out 新规范下 out-only wiring 节点不可经 NodeEdit 创建）
      _ <- rt.store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        "n-w" -> NodeDef(id = "n-w", name = "w-w", agent = "test-agent",
          status = NodeLifecycle.Wiring, out = Some("Nebula"), createdAt = System.currentTimeMillis()))))
      wId <- idOf(rt, "w-w")
      _ <- nodeEdit(nodeInput("edge-active-wire", "done-a", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("dangling-result-A"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- waitStatus(rt, "done-a", Set(NodeLifecycle.Completed))
      aId <- idOf(rt, "done-a")
      edited <- nodeEdit(nodeInput("edge-active-wire", "done-a", "out" -> Json.fromString(wId)), ctx)
      _ <- waitStatus(rt, "w-w", Set(NodeLifecycle.Completed))
      w <- nodeById(rt, wId).map(_.getOrElse(fail("W must exist")))
      allInputs <- llm.inputs.get
      wInput = allInputs.find(_.contains("=== Node done-a ==="))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(edited.isRight, s"active out-edit must succeed, got: $edited")
      assert(w.deliveredTo.contains(aId), s"W must have received A's result, got ${w.deliveredTo}")
      assertEquals(w.status, NodeLifecycle.Completed, "W must start after re-delivery and complete")
      assert(wInput.isDefined, s"W input must carry A's result header, got inputs=${allInputs.map(_.take(150))}")
  }

  // ── ④ 已存在边 + 归档上游：补投递且 barrier 结算正确 ─────

  test("④ pre-existing edge + archived upstream (n-219106db damage shape): NodeEdit(out=) re-delivers, no duplicate in entry") {
    val ws = tempRoot / "ws-preedge"
    os.makeDir.all(ws)
    val system = ActorSystem(s"edge-pe-${scala.util.Random.nextInt(100000)}")
    val llm = CaptureLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("edge-preedge", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // W store 直种（20260903 创建必带 out 新规范下 out-only wiring 节点不可经 NodeEdit 创建）
      _ <- rt.store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        "n-w" -> NodeDef(id = "n-w", name = "w-w", agent = "test-agent",
          status = NodeLifecycle.Wiring, out = Some("Nebula"), createdAt = System.currentTimeMillis()))))
      wId <- idOf(rt, "w-w")
      _ <- nodeEdit(nodeInput("edge-preedge", "done-a", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("dangling-result-A"), "out" -> Json.fromString("Nebula")), ctx)
      aId <- archiveDangling(rt, "done-a")
      // 播种实证损伤形态（陈旧 out 覆盖时代遗留，同 n-219106db）：下游 in 已含
      // 上游、deliveredTo 恒空、上游归档悬空——边存在但投递永不发生
      _ <- rt.store.mutate { s =>
        s.nodes.get(wId) match
          case Some(fresh) => s.copy(nodes = s.nodes.updated(wId, fresh.copy(in = List(aId))))
          case None        => s
      }.void
      before <- nodeById(rt, wId).map(_.getOrElse(fail("W must exist")))
      // NodeEdit(归档 A, out=W) → 补投递（edge already exists 分支）
      edited <- nodeEdit(nodeInput("edge-preedge", "done-a", "out" -> Json.fromString(wId)), ctx)
      _ <- waitStatus(rt, "w-w", Set(NodeLifecycle.Completed))
      w <- nodeById(rt, wId).map(_.getOrElse(fail("W must exist")))
      allInputs <- llm.inputs.get
      wInput = allInputs.find(_.contains("=== Node done-a ==="))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(before.in, List(aId), "seeded damage: in holds A, deliveredTo empty")
      assertEquals(before.deliveredTo, Nil, "seeded damage: nothing ever delivered")
      assert(edited.isRight, s"archived out-edit must succeed, got: $edited")
      assertEquals(w.in, List(aId), s"in must stay single-entry (setOut distinct), got ${w.in}")
      assert(w.deliveredTo.contains(aId), s"W must have received A's result, got ${w.deliveredTo}")
      assertEquals(w.status, NodeLifecycle.Completed, "W must start after re-delivery and complete")
      assert(wInput.isDefined, s"W input must carry A's result header, got inputs=${allInputs.map(_.take(150))}")
  }

  // ── ④-c 补投递完整性：不在 adds 里的未投递归档上游也被补投 ─

  test("④-c append catches up ALL undelivered in-upstreams: pre-existing archived edge delivered by appending ANOTHER upstream") {
    val ws = tempRoot / "ws-catchup"
    os.makeDir.all(ws)
    val system = ActorSystem(s"edge-cu-${scala.util.Random.nextInt(100000)}")
    val llm = CaptureLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("edge-catchup", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // W store 直种（20260903 创建必带 out 新规范下 out-only wiring 节点不可经 NodeEdit 创建）
      _ <- rt.store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        "n-w" -> NodeDef(id = "n-w", name = "w-w", agent = "test-agent",
          status = NodeLifecycle.Wiring, out = Some("Nebula"), createdAt = System.currentTimeMillis()))))
      wId <- idOf(rt, "w-w")
      _ <- nodeEdit(nodeInput("edge-catchup", "done-a", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("done-result-A"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- nodeEdit(nodeInput("edge-catchup", "done-b", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("done-result-B"), "out" -> Json.fromString("Nebula")), ctx)
      aId <- archiveDangling(rt, "done-a")
      bId <- archiveDangling(rt, "done-b")
      // 损伤形态：A 边已存在但从未投递（in 有 A、deliveredTo 空）
      _ <- rt.store.mutate { s =>
        s.nodes.get(wId) match
          case Some(fresh) => s.copy(nodes = s.nodes.updated(wId, fresh.copy(in = List(aId))))
          case None        => s
      }.void
      // 只追加 B——fix b 扩展：补投递扫描全部 in（A 虽不在 adds 里也被补投），
      // 随后 barrier 结算一次性启动。修复前（只投 adds=B）：A 永不投递 → W 永久
      // wiring → 本用例在 waitStatus 超时红（变异 M-b 的红证据承载用例）。
      _ <- nodeEdit(nodeInput("edge-catchup", "w-w", "in" -> Json.fromString(bId)), ctx)
      _ <- waitStatus(rt, "w-w", Set(NodeLifecycle.Completed))
      w <- nodeById(rt, wId).map(_.getOrElse(fail("W must exist")))
      allInputs <- llm.inputs.get
      wInput = allInputs.find(t => t.contains("=== Node done-a ===") && t.contains("=== Node done-b ==="))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(w.deliveredTo.toSet, Set(aId, bId), s"W must receive BOTH (A caught up), got ${w.deliveredTo}")
      assertEquals(w.status, NodeLifecycle.Completed, "W must start only after full barrier")
      assert(wInput.isDefined, s"W input must carry BOTH results, got inputs=${allInputs.map(_.take(150))}")
      assertNoSubsetStart(allInputs, "=== Node done-a ===", "=== Node done-b ===")
  }

  // ── 归档编辑域守卫 ──────────────────────────────────────

  test("archived node edit guard: task/agent/in/deps/abandon rejected; only 'out' rewiring allowed") {
    val ws = tempRoot / "ws-arch-guard"
    os.makeDir.all(ws)
    val system = ActorSystem(s"edge-ag-${scala.util.Random.nextInt(100000)}")
    val llm = CaptureLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("edge-arch-guard", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- nodeEdit(nodeInput("edge-arch-guard", "done-a", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("dangling-result-A"), "out" -> Json.fromString("Nebula")), ctx)
      aId <- archiveDangling(rt, "done-a")
      rTask <- nodeEdit(nodeInput("edge-arch-guard", "done-a", "task" -> Json.fromString("new-task")), ctx)
      rIn <- nodeEdit(nodeInput("edge-arch-guard", "done-a", "in" -> Json.fromString(aId)), ctx)
      rAbandon <- nodeEdit(nodeInput("edge-arch-guard", "done-a", "abandon" -> Json.fromBoolean(true)), ctx)
      rNoOut <- nodeEdit(nodeInput("edge-arch-guard", "done-a"), ctx)
      // 未触碰归档节点：无编辑发生
      archAfter <- rt.store.findNode(aId)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(rTask.isLeft && rTask.left.exists(_.contains("archived")), s"task edit on archived must be rejected, got: $rTask")
      assert(rIn.isLeft && rIn.left.exists(_.contains("archived")), s"in edit on archived must be rejected, got: $rIn")
      assert(rAbandon.isLeft && rAbandon.left.exists(_.contains("archived")), s"abandon on archived must be rejected, got: $rAbandon")
      assert(rNoOut.isLeft && rNoOut.left.exists(_.contains("archived")), s"no-op edit on archived must be rejected, got: $rNoOut")
      assertEquals(archAfter.flatMap(_.out), None, "rejected edits must not touch the archived node")
      assertEquals(archAfter.flatMap(_.result), Some("ok"), "result must be untouched (CaptureLlm echoes 'ok')")
  }

end NodeEdgeRepairSpec
