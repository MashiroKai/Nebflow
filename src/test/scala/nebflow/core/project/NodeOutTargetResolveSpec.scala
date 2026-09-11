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

/** out 边目标「标识符二元性」修复回归（20260909 in 落盘丢失事故，五个实证节点
  * n-fdfb01e1 / n-7f645f0c / n-9ba4fb19 / n-fb2c7a1e / n-b8cce0f8）：
  *
  * 事故链（宿主事件日志 + 会话转录 + 归档边形态三方实证）：分发器标准组图工作流
  * 「create 下游带 in=<上游id> → 随后 NodeEdit(上游, out=<下游名>) 接线」——
  * 旧 setOut.rewire 按原始串 diff：旧边 to=下游 id、新边 to=下游名 → 误判为改接他点，
  * removed 侧把下游 in 抹掉；added 侧名字查 id 键 Map MISS 漏记 → in 蒸发 →
  * settle sweep 对空 in 空真放行 → 提前启动 / merge 空真收集（barrier 失效）。
  *
  * 修复面：OutEdge.resolveTargetId 解析单点（id/名字 → 节点 id），校验/in 镜像/投递
  * 三处统一按解析后 id 记账：
  *  - ① 事故重放：out 按名改接到既有 in 上游 → in 必须保留 + sweep 不得提前启动
  *    （上游未 completed 时下游绝不进入 running——端到端 barrier 断言）；
  *  - ② added 侧按名接线：目标 in 正确建立（旧实现静默漏记 = 半接线）；
  *  - ③ 投递侧：名字形态边可投递（旧实现 findNode 按原始串查 = 死边）；
  *  - ④ create 的 out 目标接受名字（镜像+环检按解析 id）；悬空目标显式拒绝
  *    （旧实现对编辑路径静默放行）；
  *  - ⑤ create 回执一致性：带 in 创建的节点立即快照读 in 非空（写后读哨兵）。
  *
  * 变异验红锚：revert setOut.rewire 至原始串 diff → ①② 红（①的 sweep 提前启动、
  * ② 的 in 缺失）；revert settleTo 解析 → ①③ 红（下游永久 wiring 直至超时）。
  */
class NodeOutTargetResolveSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-node-out-target-resolve"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "general")
  os.write.over(tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"general executor","tools":[],"category":"standalone"}""")
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  // ── 纯函数单测：OutEdge.resolveTargetId 解析单点 ─────────

  test("resolveTargetId: id hit > name hit > miss (dangling)") {
    val nodes = Map(
      "n-aaa" -> NodeDef(id = "n-aaa", name = "上游修复支", agent = "general", createdAt = 1L),
      "n-bbb" -> NodeDef(id = "n-bbb", name = "合并批", agent = "general", createdAt = 2L))
    assertEquals(OutEdge.resolveTargetId(nodes, "n-aaa"), Some("n-aaa"), "id form must hit directly")
    assertEquals(OutEdge.resolveTargetId(nodes, "合并批"), Some("n-bbb"), "name form must resolve to id")
    assertEquals(OutEdge.resolveTargetId(nodes, "n-zzz"), None, "unknown id must miss")
    assertEquals(OutEdge.resolveTargetId(nodes, "不存在"), None, "unknown name must miss")
  }

  // ── 夹具（与 NodeEdgeRepairSpec 同构）────────────────────

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
      sessionId = Some("otr-sid"),
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
        emitEvent = (_, _, _) => IO.unit,
        // noderpt 批 A 段：本 fixture 主题非 node_report 语义 ⇒ 显式关腿 2（生产默认开；
        // 腿 2 默认开行为由 NodeReportReminderSpec 覆盖）。
        reportGateHold = Some(false)
      )
      pd = ProjectDef(name = name, workspace = ws.toString, agentFile = (ws / "AGENTS.md").toString, createdAt = System.currentTimeMillis())
      rt = ProjectRuntime(pd, store, engine, system, res, None)
      _ <- ProjectRuntimeRegistry.register(rt)
    yield rt

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

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  // ── ① 事故重放：create 带 in → out 按名改接 → in 保留 + 不提前启动 ──

  test("① incident replay: out-edit rewiring to downstream BY NAME preserves its in edge; settle sweep must NOT start while upstream is running (end-to-end barrier)") {
    val ws = tempRoot / "ws-incident"
    os.makeDir.all(ws)
    val system = ActorSystem(s"otr-inc-${scala.util.Random.nextInt(100000)}")
    val llm = CaptureLlm(text => if text.contains("slow-upstream") then 2500.millis else 0.millis)
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("otr-incident", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // 上游：入口节点，跑 2.5s（给 sweep 留出「上游仍 running」的窗口）
      created <- nodeEdit(nodeInput("otr-incident", "slow-upstream", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("slow-upstream"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- waitStatus(rt, "slow-upstream", Set(NodeLifecycle.Running))
      upId <- idOf(rt, "slow-upstream")
      // 下游：task + in=[上游 id]（事故节点形态：带 task 的 wiring barrier 节点）
      _ <- nodeEdit(nodeInput("otr-incident", "下游合并批", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("collect"), "in" -> Json.fromString(upId), "out" -> Json.fromString("Nebula")), ctx)
      downId <- idOf(rt, "下游合并批")
      afterCreate <- nodeById(rt, downId)
      // 分发器标准第二拍：out 按下游**名字**接线（事故触发动作）
      wired <- nodeEdit(nodeInput("otr-incident", "slow-upstream", "out" -> Json.fromString("下游合并批")), ctx)
      afterEdit <- nodeById(rt, downId)
      // 上游仍 running 时显式驱动 settle sweep（生产由 TtlTick 30s 驱动）
      _ <- rt.engine.settleRunnableSweep()
      afterSweep <- nodeById(rt, downId)
      up <- nodeById(rt, upId).map(_.getOrElse(fail("upstream must exist")))
      // 上游完成 → 名字形态边投递 → barrier 结算 → 下游以完整输入启动
      _ <- waitStatus(rt, "slow-upstream", Set(NodeLifecycle.Completed))
      _ <- waitStatus(rt, "下游合并批", Set(NodeLifecycle.Completed))
      done <- nodeById(rt, downId).map(_.getOrElse(fail("downstream must exist")))
      allInputs <- llm.inputs.get
      collectInput = allInputs.find(t => t.contains("=== Node slow-upstream ==="))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(created.isRight, s"create must succeed, got: $created")
      assertEquals(afterCreate.map(_.in), Some(List(upId)), "⑤ create receipt: in must be persisted with the node")
      assert(wired.isRight, s"by-name out wiring must succeed, got: $wired")
      assertEquals(afterEdit.map(_.in), Some(List(upId)),
        "① THE incident: out-edit by NAME must NOT wipe the downstream in edge")
      assert(up.out.exists(_.to == "下游合并批"), s"upstream out must hold the name-form edge, got ${up.out}")
      assertEquals(afterSweep.map(_.status), Some(NodeLifecycle.Wiring),
        "① end-to-end barrier: upstream still running → sweep must NOT start the downstream (pre-fix: vacuous empty-in start)")
      assertEquals(done.status, NodeLifecycle.Completed, "downstream must complete after upstream delivery")
      assertEquals(done.deliveredTo, List(upId), "delivery must credit the barrier via the resolved name edge")
      assert(collectInput.isDefined, s"downstream input must carry the upstream result header, got inputs=${allInputs.map(_.take(150))}")
  }

  // ── ② added 侧：out 按名接线 → 目标 in 正确建立 ──────────

  test("② out-edit wiring BY NAME establishes the target's in edge (added-side mirror was silently skipped pre-fix)") {
    val ws = tempRoot / "ws-added"
    os.makeDir.all(ws)
    val system = ActorSystem(s"otr-add-${scala.util.Random.nextInt(100000)}")
    val llm = CaptureLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("otr-added", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // W store 直种（wiring 空节点；out-only wiring 不可经 NodeEdit 创建）
      _ <- rt.store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        "n-w" -> NodeDef(id = "n-w", name = "收集批", agent = "general",
          status = NodeLifecycle.Wiring, out = List(OutEdge.nebula), createdAt = System.currentTimeMillis()))))
      _ <- nodeEdit(nodeInput("otr-added", "done-a", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("done-result-A"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- waitStatus(rt, "done-a", Set(NodeLifecycle.Completed))
      aId <- idOf(rt, "done-a")
      // 按**名字**接线（不是 id）
      wired <- nodeEdit(nodeInput("otr-added", "done-a", "out" -> Json.fromString("收集批")), ctx)
      _ <- waitStatus(rt, "收集批", Set(NodeLifecycle.Completed))
      w <- nodeById(rt, "n-w").map(_.getOrElse(fail("W must exist")))
      allInputs <- llm.inputs.get
      wInput = allInputs.find(_.contains("=== Node done-a ==="))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(wired.isRight, s"by-name wiring must succeed, got: $wired")
      assert(w.in.contains(aId), s"② added-side mirror must establish W.in by resolved id, got ${w.in}")
      assert(w.deliveredTo.contains(aId), s"delivery must reach W via the name-form edge, got ${w.deliveredTo}")
      assert(wInput.isDefined, s"W input must carry A's result header, got inputs=${allInputs.map(_.take(150))}")
  }

  // ── ④ create 的 out 目标接受名字 + 悬空目标显式拒绝 ──────

  test("④ create with out=<existing node NAME> mirrors the target's in edge; dangling out target is rejected with an actionable error") {
    val ws = tempRoot / "ws-create-name"
    os.makeDir.all(ws)
    val system = ActorSystem(s"otr-cn-${scala.util.Random.nextInt(100000)}")
    val llm = CaptureLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("otr-create-name", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // W' 入口节点先建（out=Nebula）
      _ <- nodeEdit(nodeInput("otr-create-name", "前置节点", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("prep"), "out" -> Json.fromString("Nebula")), ctx)
      prepId <- idOf(rt, "前置节点")
      // 新节点 out 按名字指向前置节点（旧实现对名字形态误拒 "Referenced node not found"）
      created <- nodeEdit(nodeInput("otr-create-name", "后续节点", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("follow-up"), "out" -> Json.fromString("前置节点")), ctx)
      followId <- idOf(rt, "后续节点")
      prep <- nodeById(rt, prepId).map(_.getOrElse(fail("prep must exist")))
      // 悬空目标：显式拒绝（不再静默放行进拓扑）
      dangling <- nodeEdit(nodeInput("otr-create-name", "后续节点", "out" -> Json.fromString("不存在的节点xyz")), ctx)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(created.isRight, s"create with by-name out must succeed, got: $created")
      assert(prep.in.contains(followId), s"④ create-time by-name out must mirror target's in edge, got ${prep.in}")
      assert(dangling.isLeft && dangling.left.exists(_.contains("not found")),
        s"④ dangling out target must be rejected, got: $dangling")
  }

end NodeOutTargetResolveSpec
