package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, Behaviors}
import nebflow.agent.{AgentCommand, AgentKind, AgentRecord, SharedResources}
import nebflow.core.PathUtil
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, NodeEditTool, ToolContext}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * b64 批验收（作者 2026-09-13 裁定 R1–R17 + M1–M5）：
 * 「节点 out 语义与通知路由重设计」实施批的核心语义单测/集成测。
 *
 * 设计源 = `~/.nebflow/docs/Nebflow/20260910_node-out-semantics-notification-routing-design.md`
 * （推荐值真源）；本 spec 逐项对应实施报告「17 项逐条落地表」的判据列。
 *
 * 覆盖：
 *  - ① 三值语义 + legacy 解析（R1/R2/R3/R5；含存量两条腿 channel-additive 零漂移）
 *  - ② 值域校验可行动错误（NODE_NOTIFY_INVALID）+ 未声明 ⇒ 缺键 + 载荷条件键 `notify`
 *  - ③ R5 抑制实测（补投扫描腿：不投根 + 记账；**主路径腿**：真实引擎 nebulaDelivery）
 *  - ④ M1 signal 边不受策略影响（只记账不通报，策略不得升根）
 *  - ⑤ R14 failed 不豁免（silent 节点 failed 仍回流分发器）
 *  - ⑥ M2 链摘要 ≥2 成员（正控发 / 负控单成员链不发且不下发 chainId）
 *  - ⑦ R8 账本（恰一次：投递后置 summarySentAt ⇒ 下轮零候选）+ 存量批零补发
 *  - ⑧ R11 护栏（>3 条独立 → 溢出合并为一条计数摘要）
 *  - ⑨ R17①②③（窗口合并 = 一次注入/一次预算；档位重新定档；耗尽非静默 + 逐节点持久审计）
 *  - ⑩ M3 只警告不拦 + M4 `notify.quietMs`（缺键缺省 / 越界可行动报错 / 挂载面 fail-fast）
 */
class NodeNotifyPolicySpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 240.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-notify-policy"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  for agent <- List("test-agent", "project-dispatcher", "general") do
    os.makeDir.all(tempRoot / "agents" / agent)
    os.write.over(
      tempRoot / "agents" / agent / "agent.json",
      s"""{"name":"$agent","description":"b64 notify-policy agent","tools":[],"category":"standalone"}"""
    )
    os.write.over(tempRoot / "agents" / agent / "system.md", s"# $agent\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit  = ProjectRuntimeRegistry.clear

  // ── harness ────────────────────────────────────────────────────────────

  private class FuncLlm(respond: String => IO[String]):
    def handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(
          req: LlmRequest,
          onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
      ): Stream[IO, StreamChunk] =
        val text = req.messages.map(_.textContent).mkString("\n")
        Stream.eval(respond(text)).flatMap(reply => Stream(StreamChunk.TextDelta(reply), StreamChunk.Done(None, None)))

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
      agentLibrary = new nebflow.agent.AgentLibrary(tmp / "agents"),
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
      sessionId = Some("b64-sid"),
      rootSessionId = Some("nebula-root"),
      sharedResources = Some(res),
      actorSystem = Some(system)
    )

  private def nodeEdit(input: Json, ctx: ToolContext): IO[Either[String, String]] =
    NodeEditTool.call(input.asObject.get, ctx).map(_.left.map(_.message))

  private def nodeInput(project: String, nodename: String, extra: (String, Json)*): Json =
    Json.obj(("project" -> Json.fromString(project)) :: ("nodename" -> Json.fromString(nodename)) :: extra.toList*)

  private def waitUntil(timeout: FiniteDuration, every: Long = 50)(cond: IO[Boolean]): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError("waitUntil: condition not met in time"))
          else IO.sleep(every.millis) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  private def mkEngine(store: FlowMapStore, system: ActorSystem, res: SharedResources,
                       ws: os.Path, id: String): NodeEngine =
    new NodeEngine(store, system, res, (_: Json) => IO.unit, ws.toString, "nebula-root", id,
      emitEvent = (_, _, _) => IO.unit, reportGateHold = Some(false))

  /** 完整挂载（NodeEdit 的 ProjectRuntimeRegistry 前置；与 NotifyDispatcherSpec.mountReal 同款：
    * `reportGateHold=false` 仅测试面）。 */
  private def mountReal(name: String, ws: os.Path, system: ActorSystem, res: SharedResources): IO[ProjectRuntime] =
    val pd = ProjectDef(name = name, workspace = ws.toString, agentFile = (ws / "AGENTS.md").toString,
      createdAt = System.currentTimeMillis())
    for
      store <- FlowMapStore.open(name, ws.toString)
      board <- IO(TaskBoardStore.open(name, ws.toString)).map(Some(_): Option[TaskBoardStore])
        .handleErrorWith(_ => IO.pure(None))
      engine = new NodeEngine(
        store, system, res,
        wsSendFn = (_: Json) => IO.unit,
        workspace = ws.toString,
        rootSessionId = "nebula-root",
        projectName = name,
        feedbackMode = pd.feedbackMode.getOrElse(FeedbackRouter.ModeAuto),
        emitEvent = (_, _, _) => IO.unit,
        board = board,
        projectGoal = pd.description,
        reportGateHold = Some(false)
      )
      ref <- system.spawn(
        ProjectActor(ProjectActor.ProjectConfig(pd, engine, system, res, "nebula-root", board = board)),
        s"project-${name.take(20)}"
      )
      rt = ProjectRuntime(pd, store, engine, system, res, Some(ref), board)
      _ <- ProjectRuntimeRegistry.register(rt)
    yield rt

  /** 根会话捕获 actor（ImmediateInput 三面：text / source / eventType）。 */
  private def registerNebulaCapture(
      res: SharedResources, system: ActorSystem
  ): IO[Ref[IO, List[(String, Option[String], Option[String])]]] =
    Ref.of[IO, List[(String, Option[String], Option[String])]](Nil).flatMap { captured =>
      lazy val captureBehavior: nebflow.actor.Behavior[AgentCommand] = Behaviors.receive[AgentCommand] { (_, msg) =>
        msg match
          case im: AgentCommand.ImmediateInput => captured.update(_ :+ ((im.text, im.source, im.eventType))).as(captureBehavior)
          case _                               => IO.pure(captureBehavior)
      }
      system.spawn(captureBehavior, s"b64-nebula-${scala.util.Random.nextInt(100000)}").flatMap { ref =>
        val now = System.currentTimeMillis()
        res.agentRegistry
          .update(_ + ("nebula-root" -> AgentRecord(
            sessionId = "nebula-root", ref = ref, kind = AgentKind.Root, rootSessionId = "nebula-root",
            startedAt = now, lastActivityMs = now)))
          .as(captured)
      }
    }

  private def nid(id: String, name: String, policy: Option[String], out: List[OutEdge],
                  status: String = NodeLifecycle.Completed, flag: Boolean = false,
                  result: Option[String] = Some("done"), delivered: Option[Long] = None): NodeDef =
    NodeDef(id = id, name = name, agent = "general", status = status, out = out, createdAt = 1L,
      notifyPolicy = policy, notifyDispatcher = flag, result = result, nebulaDeliveredAt = delivered)

  private val nebulaResult = OutEdge(OutEdge.NebulaTarget, Set(OutEdge.Pass), OutEdge.Result)
  private val nebulaSignal = OutEdge(OutEdge.NebulaTarget, Set(OutEdge.Pass), OutEdge.Signal)
  private val toDown = OutEdge("n-b", Set(OutEdge.Pass), OutEdge.Result)

  // ── ① 三值 + legacy 解析（R1/R2/R3/R5）────────────────────────────────

  test("① legacy 解析：out 含 :result pass Nebula 边 ⇒ 投根；否则 flag ⇒ dispatcher；否则 silent（两腿 additive）") {
    // 行 1：out 含 Nebula（:result pass）⇒ 根可见
    assert(NotifyPolicy.completedRootVisible(nid("n1", "root-legacy", None, List(nebulaResult))),
      "legacy 行 1：out 含 :result pass Nebula 边 ⇒ root（今天确实投根）")
    // 行 2：out 无 Nebula ∧ flag ⇒ dispatcher（不是 root）
    val flagOnly = nid("n2", "flag-legacy", None, List(toDown), flag = true)
    assert(!NotifyPolicy.completedRootVisible(flagOnly), "legacy 行 2：不投根")
    assert(NotifyPolicy.completionNotifiesDispatcher(flagOnly), "legacy 行 2：回分发器 ✓")
    // 行 3：两者皆无 ⇒ silent
    val silentLegacy = nid("n3", "silent-legacy", None, List(toDown))
    assert(!NotifyPolicy.completedRootVisible(silentLegacy) && !NotifyPolicy.completionNotifiesDispatcher(silentLegacy),
      "legacy 行 3：两处皆不投（仅落 Flow Map）")
    // 并存形态（out 含 Nebula ∧ flag=true）：单值表无格，两条腿各自沿用今天行为（channel-additive）
    val both = nid("n4", "both-legacy", None, List(nebulaResult), flag = true)
    assert(NotifyPolicy.completedRootVisible(both), "并存形态：根腿照旧（今天确实投根）")
    assert(NotifyPolicy.completionNotifiesDispatcher(both), "并存形态：分发器腿照旧——单值表会让这 4 个存量节点丢腿")
    // bare Nebula = 出口标记（:signal）⇒ 不是投根声明（与 redelivery 扫描 N3 收窄同源）
    assert(!NotifyPolicy.completedRootVisible(nid("n5", "marker", None, List(nebulaSignal))),
      "bare Nebula（:signal 出口标记）不构成投根声明")
  }

  test("① 显式声明覆盖 legacy：root/dispatcher/silent 各自裁决两条腿") {
    assert(NotifyPolicy.completedRootVisible(nid("a", "a", Some(NotifyPolicy.Root), List(nebulaResult))), "root ⇒ 放行边")
    assert(!NotifyPolicy.completedRootVisible(nid("b", "b", Some(NotifyPolicy.Dispatcher), List(nebulaResult))), "dispatcher ⇒ 抑制边（R5）")
    assert(!NotifyPolicy.completedRootVisible(nid("c", "c", Some(NotifyPolicy.Silent), List(nebulaResult))), "silent ⇒ 抑制边（R5）")
    assert(NotifyPolicy.completionNotifiesDispatcher(nid("d", "d", Some(NotifyPolicy.Dispatcher), Nil)), "dispatcher ⇒ 回流 ✓")
    assert(!NotifyPolicy.completionNotifiesDispatcher(nid("e", "e", Some(NotifyPolicy.Root), Nil)), "root ⇒ 不回流分发器")
    assert(!NotifyPolicy.completionNotifiesDispatcher(nid("f", "f", Some(NotifyPolicy.Silent), Nil)), "silent ⇒ 不回流")
    // 显式 silent 覆盖 legacy flag=true（写侧权威单点）
    assert(!NotifyPolicy.completionNotifiesDispatcher(nid("g", "g", Some(NotifyPolicy.Silent), Nil, flag = true)))
    // R14 恒定声明：failed 不豁免
    assertEquals(NotifyPolicy.failedAlwaysDispatched, true, "R14：failed 恒回分发器（无豁免口）")
  }

  test("② 值域校验：非法值给可行动报错（合法值域 + 实收值 + NODE_NOTIFY_INVALID）") {
    assertEquals(NotifyPolicy.validate("  root "), Right("root"), "两端空白容错")
    val bad = NotifyPolicy.validate("loud")
    assert(bad.isLeft)
    val msg = bad.left.getOrElse("")
    assert(msg.contains("loud"), s"必须含实收值：$msg")
    assert(msg.contains("silent") && msg.contains("dispatcher") && msg.contains("root"), s"必须含合法值域：$msg")
    assert(msg.contains(NotifyPolicy.InvalidCode), s"必须含错误码：$msg")
  }

  // ── ⑩ M4 quietMs ──────────────────────────────────────────────────────

  test("⑩ M4 quietMs 解析：缺键 = 缺省 5s；越界 = 可行动报错且不截断") {
    assertEquals(NotifyPolicy.parseQuietMs(None), Right(NotifyPolicy.NotifyQuietMsDefaultMs))
    assertEquals(NotifyPolicy.NotifyQuietMsDefaultMs, 5000L, "R9：默认 5s")
    assertEquals(NotifyPolicy.NotifyQuietMsMaxMs, 60000L, "R9：上界 60s")
    assertEquals(NotifyPolicy.QuietMsKey, "notify.quietMs", "对外冻结键名（M4）")
    assertEquals(NotifyPolicy.parseQuietMs(Some(60000L)), Right(60000L), "上界含端点")
    for bad <- List(0L, -1L, 60001L, 999999L) do
      val e = NotifyPolicy.parseQuietMs(Some(bad))
      assert(e.isLeft, s"$bad 必须拒")
      val msg = e.left.getOrElse("")
      assert(msg.contains(NotifyPolicy.QuietMsKey), s"必须指名键：$msg")
      assert(msg.contains("60000") && msg.contains("0"), s"必须给允许区间：$msg")
      assert(msg.contains(bad.toString), s"必须给当前值：$msg")
      assert(msg.contains("no silent clamping"), s"必须显式声明不截断：$msg")
  }

  test("⑩ M4 挂载面：越界 quietMs fail-fast（不截断）；合法值挂载成功") {
    val ws = tempRoot / "ws-m4"
    os.makeDir.all(ws)
    val system = ActorSystem(s"b64-m4-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot, new FuncLlm(_ => IO.pure("ok")).handle)
      badPd = ProjectDef(name = "b64-m4-bad", workspace = ws.toString, agentFile = (ws / "AGENTS.md").toString,
        createdAt = now, notifyConfig = Some(NotifyConfig(quietMs = Some(60001L))))
      bad <- ProjectRuntimeRegistry.mount(badPd, system, res, None, "nebula-root").attempt
      okPd = ProjectDef(name = "b64-m4-ok", workspace = ws.toString, agentFile = (ws / "AGENTS.md").toString,
        createdAt = now, notifyConfig = Some(NotifyConfig(quietMs = Some(20000L))))
      ok <- ProjectRuntimeRegistry.mount(okPd, system, res, None, "nebula-root").attempt
      _ <- ProjectRuntimeRegistry.unregister("b64-m4-ok")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(bad.isLeft, s"越界 quietMs 必须 fail-fast 挂载失败（禁静默截断）：$bad")
      val msg = bad.left.toOption.map(_.getMessage).getOrElse("")
      assert(msg.contains(NotifyPolicy.QuietMsKey), s"报错必须指名键：$msg")
      assert(msg.contains("60000") && msg.contains("60001"), s"报错必须给区间与当前值：$msg")
      assert(ok.isRight, s"合法值挂载成功：$ok")
  }

  // ── ③ R5 实测：抑制 + 记账 / 主路径 ───────────────────────────────────

  test("③ R5 补投扫描腿：显式 dispatcher/silent 的 Nebula :result 边不投根 + 记账（不被补投复活）") {
    val ws = tempRoot / "ws-suppress"
    os.makeDir.all(ws)
    val system = ActorSystem(s"b64-suppress-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot, new FuncLlm(_ => IO.pure("ok")).handle)
      captured <- registerNebulaCapture(res, system)
      store <- FlowMapStore.open("b64-suppress", ws.toString)
      engine = mkEngine(store, system, res, ws, "b64-suppress")
      _ <- store.mutate(s => s.copy(nodes = Map(
        "n-disp" -> nid("n-disp", "disp", Some(NotifyPolicy.Dispatcher), List(nebulaResult)),
        "n-silent" -> nid("n-silent", "silent", Some(NotifyPolicy.Silent), List(nebulaResult)),
        "n-legacy" -> nid("n-legacy", "legacy", None, List(nebulaResult))
      )))
      _ <- engine.redeliverUnconsumedNebulaResults()
      after1 <- store.snapshot
      capturedTexts1 <- captured.get
      _ <- engine.redeliverUnconsumedNebulaResults() // 第二轮（记账后不应再投）
      capturedTexts2 <- captured.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(capturedTexts1.size, 1,
        s"恰 1 条投根：只有 legacy 节点（dispatcher/silent 被抑制）——实收 ${capturedTexts1.map(_._1.take(40))}")
      assert(capturedTexts1.head._1.contains("legacy"), "被投的是 legacy 对照节点（存量零漂移）")
      assertEquals(capturedTexts2.size, 1, "记账后第二轮零新增（R5 抑制不被补投扫描复活）")
      // 扫描腿的抑制 = **结构性排除**（候选判据内裁决，根本不上投）：被抑制节点既不被投、
      // 也不被扫描「记账」——二者都靠同一策略判据，故「不复活」恒成立（判据恒同源）。
      // 扫描腿记账的不变量：投递成功的节点必有账（legacy）。主路径（nebulaDelivery）的
      // 「抑制 + 同时记账」由本 spec ③b 覆盖（spec §5 表尾推论 2）。
      assertEquals(after1.nodes("n-disp").nebulaDeliveredAt, None, "suppressed 节点不在候选（扫描不投也不标记）")
      assertEquals(after1.nodes("n-silent").nebulaDeliveredAt, None, "silent 同上")
      assert(after1.nodes("n-legacy").nebulaDeliveredAt.isDefined, "legacy 投递后照旧记账")
  }

  test("③b R5 主路径（真实引擎 nebulaDelivery）：silent/出口标记完成不投根（且记账）；root 同形节点照旧投根") {
    val ws = tempRoot / "ws-suppress-real"
    os.makeDir.all(ws)
    val system = ActorSystem(s"b64-suppress-real-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot, new FuncLlm(_ => IO.pure("ok-final")).handle)
      captured <- registerNebulaCapture(res, system)
      rt <- mountReal("b64-suppress-real", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      silent <- nodeEdit(nodeInput("b64-suppress-real", "r5-silent",
        "description" -> Json.fromString("silent node with nebula edge"),
        "task" -> Json.fromString("silent task text"),
        "out" -> Json.fromString("(pass)Nebula"),
        "notify" -> Json.fromString("silent")), ctx)
      _ <- IO(assert(silent.isRight, s"silent 节点创建成功（R5 不阻断接线）：$silent"))
      root <- nodeEdit(nodeInput("b64-suppress-real", "r5-root",
        "description" -> Json.fromString("root node with nebula edge"),
        "task" -> Json.fromString("root task text"),
        "out" -> Json.fromString("(pass)Nebula"),
        "notify" -> Json.fromString("root")), ctx)
      _ <- IO(assert(root.isRight, s"root 节点创建成功：$root"))
      // ④ M1 主路径：bare `Nebula`（出口标记 :signal）+ 策略 root ⇒ 仍不投根（原判据保留）、只记账
      marker <- nodeEdit(nodeInput("b64-suppress-real", "r5-marker",
        "description" -> Json.fromString("bare nebula exit marker"),
        "task" -> Json.fromString("marker task text"),
        "out" -> Json.fromString("Nebula"),
        "notify" -> Json.fromString("root")), ctx)
      _ <- IO(assert(marker.isRight, s"bare Nebula 节点创建成功：$marker"))
      names = List("r5-silent", "r5-root", "r5-marker")
      _ <- waitUntil(60.seconds)(rt.store.snapshot.map(s =>
        names.forall(n => s.nodes.values.exists(x => x.name == n && x.status == NodeLifecycle.Completed))))
      _ <- waitUntil(30.seconds)(captured.get.map(_.exists(_._1.contains("r5-root"))))
      _ <- IO.sleep(400.millis) // 给被抑制腿的（不该存在的）投递留出观察窗
      texts <- captured.get
      nodesAfter <- rt.store.snapshot
      byName = nodesAfter.nodes.values.map(n => n.name -> n).toMap
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      val rootTexts = texts.filter(_._1.contains("r5-root"))
      assertEquals(rootTexts.size, 1, s"root 策略节点完成照旧投根（1 条）：${texts.map(_._1.take(50))}")
      assertEquals(rootTexts.head._2, Some("node"), "source=node（节点级通道）")
      assertEquals(rootTexts.head._3, Some(NodeLifecycle.Completed), "eventType=completed")
      assert(!texts.exists(_._1.contains("r5-silent")),
        s"silent 策略节点完成**不投根**（边保留为声明、运行时抑制）：${texts.map(_._1.take(50))}")
      assert(!texts.exists(_._1.contains("r5-marker")),
        s"M1：bare Nebula（出口标记）完成**不投根**——策略=root 也不得使之升根：${texts.map(_._1.take(50))}")
      // spec §5 表尾推论 2：抑制必须同时记账（否则 30s 补投扫描会复活投递）
      assert(byName("r5-silent").nebulaDeliveredAt.isDefined, "被抑制的 silent 节点记账（防补投复活）")
      assert(byName("r5-marker").nebulaDeliveredAt.isDefined, "出口标记节点记账（只记账不通报，M1 现网口径）")
      assert(byName("r5-root").nebulaDeliveredAt.isDefined, "root 节点投递后照旧记账")
  }

  test("④ M1：`:signal` 出口标记恒「只记账不通报」，策略（含 root）不得使之升根") {
    val ws = tempRoot / "ws-signal"
    os.makeDir.all(ws)
    val system = ActorSystem(s"b64-signal-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot, new FuncLlm(_ => IO.pure("ok")).handle)
      captured <- registerNebulaCapture(res, system)
      store <- FlowMapStore.open("b64-signal", ws.toString)
      engine = mkEngine(store, system, res, ws, "b64-signal")
      _ <- store.mutate(s => s.copy(nodes = Map(
        "n-signal-root" -> nid("n-signal-root", "signal-root", Some(NotifyPolicy.Root), List(nebulaSignal))
      )))
      pending <- engine.redeliverUnconsumedNebulaResults()
      texts <- captured.get
      after <- store.snapshot
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(texts.size, 0, "signal 边不通报：策略=root 也不得升根（M1「先不定义，沿用现网口径」）")
      assertEquals(pending, 0, "signal 边不在补投候选集（mode == Result 前置判据原样保留）")
      assertEquals(after.nodes("n-signal-root").nebulaDeliveredAt, None, "扫描腿不认 signal 边（不投也不标记）")
  }

  // ── ⑤ R14 failed 不豁免 ───────────────────────────────────────────────

  test("⑤ R14：silent 节点 failed 恒回分发器（不查策略）；silent 只豁免 completed") {
    val ws = tempRoot / "ws-failed"
    os.makeDir.all(ws)
    for
      store <- FlowMapStore.open("b64-failed", ws.toString)
      triggered <- Ref.of[IO, List[String]](Nil)
      dn = new DispatchNotify(
        store, ws.toString, "b64-failed",
        escalate = (_, _) => IO.unit, emitUpdated = (_: NodeDef) => IO.unit,
        trigger = (t: String) => triggered.update(_ :+ t),
        windowMs = 0L)
      _ <- store.mutate(s => s.copy(nodes = Map(
        "n-silent-failed" -> nid("n-silent-failed", "silent-failed", Some(NotifyPolicy.Silent), Nil,
          status = NodeLifecycle.Failed, result = Some("boom")),
        "n-silent-done" -> nid("n-silent-done", "silent-done", Some(NotifyPolicy.Silent), Nil)
      )))
      failedNode <- store.getNode("n-silent-failed").map(_.getOrElse(fail("failed node must exist")))
      doneNode <- store.getNode("n-silent-done").map(_.getOrElse(fail("done node must exist")))
      _ <- dn.notifyTerminal(failedNode, NotifyReason.Failed)
      _ <- dn.notifyTerminal(doneNode, NotifyReason.Completion)
      seen <- triggered.get
    yield
      assertEquals(seen.size, 1, s"恰 failed 一条（silent 只豁免 completed）：${seen.map(_.take(30))}")
      assert(seen.head.contains("silent-failed"), "被通知的是 failed 节点")
  }

  // ── ⑥⑦⑧ 链摘要：M2 / 账本 / R11 ───────────────────────────────────────

  test("⑥⑦⑧ 链摘要：M2 ≥2 成员 / R8 记账恰一次 / R11 溢出合并") {
    val ws = tempRoot / "ws-chain"
    os.makeDir.all(ws)
    for
      store <- FlowMapStore.open("b64-chain", ws.toString)
      now = System.currentTimeMillis()
      // 一条 2 成员链（n-chain-a → n-chain-b）+ 一条孤立单成员链（n-lone）
      _ <- store.mutate { s =>
        val a = nid("n-chain-a", "chain-a", Some(NotifyPolicy.Dispatcher),
          List(OutEdge("n-chain-b", Set(OutEdge.Pass), OutEdge.Result)), result = Some("A result line"))
          .copy(completedAt = Some(now - 60000), description = Some("first"))
        val b = nid("n-chain-b", "chain-b", Some(NotifyPolicy.Root), List(nebulaResult), result = Some("B result line"))
          .copy(completedAt = Some(now), description = Some("second"))
        val lone = nid("n-lone", "lone", Some(NotifyPolicy.Dispatcher), Nil, result = Some("solo"))
          .copy(completedAt = Some(now))
        s.copy(nodes = Map(a.id -> a, b.id -> b, lone.id -> lone))
      }
      swept <- store.sweepCompletedChainsDetailed(now)
      _ = assertEquals(swept.size, 2, s"两条链都出库（一条 2 成员 + 一条单成员）：${swept.map(c => c.chainId -> c.members)}")
      batch <- store.chainSummaryBatch(FlowMapStore.ChainSummaryMaxPerRound)
      (head, tail) = batch
      _ <- IO {
        assertEquals(head.size, 1, "M2 负控：单成员链不发摘要；正控：≥2 成员链发一条")
        assertEquals(tail.size, 0)
        val c = head.head
        assertEquals(c.members, 2, "摘要成员数")
        assertEquals(c.chainId, "chain-n-chain-a", "chainId 来自分量锚（createdAt 最早成员）")
        assert(c.text.contains("chain-a") && c.text.contains("chain-b"), "摘要含成员清单（R15）")
        assert(c.text.contains("[Chain "), s"R15 头行结构：${c.text.take(80)}")
        assert(c.text.contains("起止：") && c.text.contains("读取指引："), "R15 起止 + 读取指引")
        assertEquals(c.eventType, NodeLifecycle.Completed)
        assert(c.text.length <= FlowMapStore.ChainSummaryMaxChars, "R15 长度上限")
      }
      // M2 负控：单成员链**不在**候选里（chainId 不下发）
      _ <- IO(assert(!head.exists(_.text.contains("lone")), "孤立单成员链不得出现在链摘要里"))
      // 恰一次：记账后第二轮零候选
      _ <- store.markChainSummarySent(head.head.chainId, now)
      again <- store.chainSummaryBatch(FlowMapStore.ChainSummaryMaxPerRound)
      _ <- IO(assertEquals(again, (Nil, Nil), "R8：置 summarySentAt 后不再成为候选（恰一次）"))
      metas <- store.archiveBatches
      _ <- IO {
        assert(metas(head.head.chainId).summarySentAt.isDefined, "账本落位")
        assert(metas(head.head.chainId).summaryLedgerOn, "账本启用")
      }
      // R11：5 条 2 成员链 ⇒ 3 条独立 + 2 条溢出
      ws2 = tempRoot / "ws-chain-r11"
      _ <- IO(os.makeDir.all(ws2))
      moreStore <- FlowMapStore.open("b64-chain-r11", ws2.toString)
      _ <- moreStore.mutate { s =>
        val pairs = (1 to 5).flatMap { i =>
          val a = nid(s"n-p$i-a", s"p$i-a", Some(NotifyPolicy.Dispatcher),
            List(OutEdge(s"n-p$i-b", Set(OutEdge.Pass), OutEdge.Result))).copy(completedAt = Some(now - i * 1000))
          val b = nid(s"n-p$i-b", s"p$i-b", Some(NotifyPolicy.Dispatcher), Nil)
            .copy(completedAt = Some(now - i * 1000 + 1))
          List(a.id -> a, b.id -> b)
        }.toMap
        s.copy(nodes = pairs)
      }
      sw2 <- moreStore.sweepCompletedChainsDetailed(now)
      batch2 <- moreStore.chainSummaryBatch(FlowMapStore.ChainSummaryMaxPerRound)
      _ <- IO {
        assertEquals(sw2.size, 5, "五条链出库")
        assertEquals(batch2._1.size, 3, "R11：独立摘要上限 3")
        assertEquals(batch2._2.size, 2, "R11：溢出 2 条留给合并条")
        val overflow = FlowMapStore.renderChainSummaryOverflow(batch2._2)
        assert(overflow.contains("本回合另有 2 条链完成"), s"溢出合并为一条计数摘要：${overflow.take(80)}")
      }
    yield ()
  }

  test("⑦ 存量批零补发：账本启用前归档的批文件（无 summaryLedgerOn 键）不成为候选") {
    val ws = tempRoot / "ws-legacy-batch"
    os.makeDir.all(ws)
    for
      _ <- FlowMapStore.open("b64-legacy-batch", ws.toString)
      _ <- IO(os.makeDir.all(ws / ".nebflow" / "flow-map-archive"))
      // 手放一个「账本启用前」的批文件（形态 = 存量：无 summarySentAt/summaryLedgerOn 键）
      _ <- IO {
        val nodes = Json.obj(
          "n-old-a" -> Json.obj("id" -> Json.fromString("n-old-a"), "name" -> Json.fromString("old-a"),
            "agent" -> Json.fromString("general"), "status" -> Json.fromString("completed"),
            "createdAt" -> Json.fromLong(1L), "result" -> Json.fromString("x")),
          "n-old-b" -> Json.obj("id" -> Json.fromString("n-old-b"), "name" -> Json.fromString("old-b"),
            "agent" -> Json.fromString("general"), "status" -> Json.fromString("completed"),
            "createdAt" -> Json.fromLong(2L), "result" -> Json.fromString("y")))
        os.write.over(ws / ".nebflow" / "flow-map-archive" / "chain-n-old-a.json",
          Json.obj("project" -> Json.fromString("b64-legacy-batch"), "batch" -> Json.fromString("chain-n-old-a"),
            "archivedAt" -> Json.fromLong(1L), "nodes" -> nodes).noSpaces)
      }
      reopened <- FlowMapStore.open("b64-legacy-batch", ws.toString)
      candidates <- reopened.chainSummaryBatch(FlowMapStore.ChainSummaryMaxPerRound)
      metas <- reopened.archiveBatches
    yield
      assertEquals(candidates, (Nil, Nil), "存量批不补发（防首轮把整库历史链灌进根会话）")
      assert(metas.contains("chain-n-old-a"), "批索引照旧入册")
      assert(!metas("chain-n-old-a").summaryLedgerOn, "无账本键 ⇒ 账本关闭")
      assertEquals(metas("chain-n-old-a").summarySentAt, None)
  }

  // ── ⑨ R17 三件 ────────────────────────────────────────────────────────

  test("⑨ R17①②：窗口内合并单条（N 件 = 一次注入 = 一个预算单位）+ 档位重新定档公式") {
    val ws = tempRoot / "ws-r17"
    os.makeDir.all(ws)
    for
      store <- FlowMapStore.open("b64-r17", ws.toString)
      triggered <- Ref.of[IO, List[String]](Nil)
      dn = new DispatchNotify(
        store, ws.toString, "b64-r17",
        escalate = (_, _) => IO.unit, emitUpdated = (_: NodeDef) => IO.unit,
        trigger = (t: String) => triggered.update(_ :+ t),
        windowMs = 300L)
      nodes = (1 to 6).map(i =>
        s"n-r17-$i" -> nid(s"n-r17-$i", s"r17-$i", Some(NotifyPolicy.Dispatcher), Nil, result = Some(s"r$i"))).toMap
      _ <- store.mutate(s => s.copy(nodes = nodes))
      snap <- store.snapshot
      _ <- snap.nodes.values.toList.traverse_(n => dn.notifyTerminal(n, NotifyReason.Completion))
      _ <- IO.sleep(900.millis)
      seen <- triggered.get
      _ <- IO {
        assertEquals(seen.size, 1, s"同窗 6 件合并为一次注入（R17①）：${seen.size}")
        assert(seen.head.contains("r17-1") && seen.head.contains("r17-6"), "合并件含全部成员")
        assertEquals(DispatchNotify.completionTier(5, 5000L, 30000L), 7, "默认参数档位 = max(5, ceil(30/5)+1) = 7")
        assertEquals(DispatchNotify.completionTier(5, 0L, 30000L), 5, "窗口关闭 ⇒ 基准档（同步逐条语义）")
        assertEquals(DispatchNotify.completionTier(5, 60_000L, 30_000L), 5, "窗口 ≥ 跨度 ⇒ 单窗口 + 1 = 2 < 5 ⇒ 基准档")
        assertEquals(DispatchNotify.completionTier(9, 5000L, 30_000L), 9, "基准档更高时取基准")
      }
    yield ()
  }

  test("⑨ R17③：预算耗尽语义非静默（markSent + single-flight notice），且逐节点留持久审计") {
    val ws = tempRoot / "ws-r17c"
    os.makeDir.all(ws)
    for
      store <- FlowMapStore.open("b64-r17c", ws.toString)
      triggered <- Ref.of[IO, List[String]](Nil)
      notices <- Ref.of[IO, List[String]](Nil)
      dn = new DispatchNotify(
        store, ws.toString, "b64-r17c",
        escalate = (t, _) => notices.update(_ :+ t), emitUpdated = (_: NodeDef) => IO.unit,
        trigger = (t: String) => triggered.update(_ :+ t),
        budgetMax = 1, windowMs = 0L) // 窗口关 + 档 1：第 2 件起即耗尽
      nodes = (1 to 3).map(i =>
        s"n-ex-$i" -> nid(s"n-ex-$i", s"ex-$i", Some(NotifyPolicy.Dispatcher), Nil, result = Some(s"e$i"))).toMap
      _ <- store.mutate(s => s.copy(nodes = nodes))
      snap <- store.snapshot
      _ <- snap.nodes.values.toList.traverse_(n => dn.notifyTerminal(n, NotifyReason.Completion))
      seen <- triggered.get
      esc <- notices.get
      after <- store.snapshot
      audit <- IO.blocking(os.read(ws / ".nebflow" / FlowMapEventLog.FileName))
        .map(_.linesIterator.filter(_.contains("budget exhausted")).toList)
        .handleError(_ => Nil)
      excluded <- store.findNode("n-ex-1").map(_.exists(_.notifySentAt.isDefined))
    yield
      assertEquals(seen.size, 1, "档位 1 ⇒ 只有第 1 件注入")
      assertEquals(esc.size, 1, "single-flight：恰好一条监督通知（非静默）")
      assert(esc.head.contains("预算耗尽"), s"监督通知文案含耗尽语义：${esc.head.take(60)}")
      assert(after.nodes.values.forall(_.notifySentAt.isDefined), "全部耗尽件 markSent（退出重扫候选）")
      assert(after.nodes.values.forall(_.status == NodeLifecycle.Completed), "节点保持 completed（不翻转）")
      assert(excluded, "注入成功件同样记账（tell-then-mark）")
      assertEquals(audit.size, 2, s"逐节点持久审计（每件一条，不止首件）：${audit.map(_.take(80))}")
      assert(audit.exists(_.contains("n-ex-2")) && audit.exists(_.contains("n-ex-3")),
        "耗尽件逐个留痕（可得「是谁」而非只有「有人被静默」）")
      assert(audit.forall(_.contains("kept completed")), "审计语义 = 保持 completed + notifySentAt set")
  }

  // ── ⑩ NodeEdit 面：创建默认 / 编辑 / 警告 / legacy 兼容 ────────────────

  test("②⑩ NodeEdit：未声明 ⇒ 缺键（B-3；载荷无 notify 键）/ 非法值拒 / 编辑设撤 / M3 警告 / legacy flag 仍生效") {
    val ws = tempRoot / "ws-nodedit"
    os.makeDir.all(ws)
    val system = ActorSystem(s"b64-nodedit-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot, new FuncLlm(_ => IO.pure("ok")).handle)
      rt <- mountReal("b64-nodedit", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // 预置一个上游（wiring——非终态：策略/flag 仅 wiring/pending/running 可设）（b64）
      _ <- rt.store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        "n-up" -> nid("n-up", "up", Some(NotifyPolicy.Dispatcher), Nil, status = NodeLifecycle.Wiring))))
      plain <- nodeEdit(nodeInput("b64-nodedit", "plain",
        "description" -> Json.fromString("purpose line"), "in" -> Json.fromString("n-up")), ctx)
      _ <- IO(assert(plain.isRight, s"创建应成功：$plain"))
      snap1 <- rt.store.snapshot
      plainId = snap1.nodes.values.find(_.name == "plain").map(_.id).getOrElse(fail("plain node must exist"))
      plainNode = snap1.nodes(plainId)
      payload = NodePayload.buildNodeJson(plainNode, System.currentTimeMillis())
      _ <- IO {
        // 🔴 期望值调整申报（B-3 语义变更的必然结果，**非放水**）：本处两条断言按
        // 2026-09-14 裁定 B-3 调整**期望值**（断言条件不变——仍是「创建后 notifyPolicy
        // 的确切形态 / 载荷键的确切存在性」）。b64 批 R2 口径为「未传 notify ⇒ 显式落
        // dispatcher（写盘非缺键）」⇒ 期望 `Some(Dispatcher)` / 载荷 `Some("dispatcher")`；
        // 该口径使**默认值覆盖显式门集**（`createNode` 恒落 `Some("dispatcher")` ⇒
        // 用户显式写的 `(pass,failed)Nebula` 被静默抑制 = 语义回归，①E/⑧D 两例红）。
        // B-3 定案「缺键才是『未声明』」⇒ 期望改为 `None` / 载荷**无** `notify` 键。
        // 依据 = 本任务书裁定三项之 B-3 + triage A-5 B-3 行（「NodeNotifyPolicySpec:588
        // 一例由『创建显式落 dispatcher』调整为『未显式声明 ⇒ 缺键』」）。
        // 显式声明的裁决力不受影响：编辑设撤（下）/ M3 警告 / flag legacy 路径三条**原样全绿**。
        assertEquals(plainNode.notifyPolicy, None, "B-3：创建未传 notify ⇒ 缺键（默认值不再代填 Some；b64 批原期望 Some(Dispatcher)，按语义变更调整）")
        assertEquals(payload.hcursor.get[String]("notify").toOption, None, "B-3：缺键 ⇒ 载荷无 notify 键（b64 批原期望 Some(\"dispatcher\")，同批调整）")
      }
      // 非法值拒（NODE_NOTIFY_INVALID）
      bad <- nodeEdit(nodeInput("b64-nodedit", "plain", "notify" -> Json.fromString("loud")), ctx)
      _ <- IO {
        assert(bad.isLeft, "非法值必须拒")
        assert(bad.left.getOrElse("").contains("NODE_NOTIFY_INVALID"), s"错误码：${bad.left.getOrElse("")}")
      }
      // 编辑设 root → silent → null（清除回落 legacy）
      _ <- nodeEdit(nodeInput("b64-nodedit", "plain", "notify" -> Json.fromString("root")), ctx)
      setRoot <- rt.store.snapshot.map(_.nodes(plainId))
      _ <- nodeEdit(nodeInput("b64-nodedit", "plain", "notify" -> Json.fromString("silent")), ctx)
      setSilent <- rt.store.snapshot.map(_.nodes(plainId))
      _ <- nodeEdit(nodeInput("b64-nodedit", "plain", "notify" -> Json.Null), ctx)
      cleared <- rt.store.snapshot.map(_.nodes(plainId))
      _ <- IO {
        assertEquals(setRoot.notifyPolicy, Some("root"))
        assertEquals(setSilent.notifyPolicy, Some("silent"))
        assertEquals(cleared.notifyPolicy, None, "null = 显式清除（回落 legacy 解析）")
      }
      // M3：silent ∧ 链末端 → 只警告不拦（创建成功 + 尾部 ⚠ 行）
      warnRes <- nodeEdit(nodeInput("b64-nodedit", "chain-end-silent",
        "description" -> Json.fromString("chain end silent"),
        "in" -> Json.fromString("n-up"), "notify" -> Json.fromString("silent"),
        "out" -> Json.fromString("(pass)Nebula")), ctx)
      _ <- IO {
        assert(warnRes.isRight, s"M3 只警告不拦（不得拒）：$warnRes")
        val txt = warnRes.getOrElse("")
        assert(txt.contains("⚠"), s"M3 警告行存在：$txt")
        assert(txt.toLowerCase.contains("chain-end"), s"M3 判据说明（链末端）：$txt")
        assert(txt.contains("does NOT exempt failures"), "R14 提示：silent 不豁免 failed")
      }
      // root ∧ 无根出口 → 警告（WARNING 族不阻断）
      rootWarn <- nodeEdit(nodeInput("b64-nodedit", "root-nogate",
        "description" -> Json.fromString("root without outlet"),
        "in" -> Json.fromString("n-up"), "notify" -> Json.fromString("root")), ctx)
      _ <- IO {
        assert(rootWarn.isRight, "不阻断")
        assert(rootWarn.getOrElse("").contains("no root outlet"), s"spec §4.2 root ∧ 无 Nebula 边 WARNING：$rootWarn")
      }
      // legacy flag：未声明 notify 的存量节点仍可写 flag（零漂移）；已声明 notify 的节点写 flag 被忽略（WARN）
      _ <- rt.store.mutate(s => s.copy(nodes = s.nodes.updated("n-up", s.nodes("n-up").copy(notifyPolicy = None))))
      _ <- nodeEdit(nodeInput("b64-nodedit", "up", "notifyDispatcher" -> Json.fromBoolean(true)), ctx)
      upAfter <- rt.store.snapshot.map(_.nodes("n-up"))
      _ <- nodeEdit(nodeInput("b64-nodedit", "plain", "notifyDispatcher" -> Json.fromBoolean(true)), ctx)
      plainAfter <- rt.store.snapshot.map(_.nodes(plainId))
      _ <- IO {
        assertEquals(upAfter.notifyDispatcher, true, "legacy 路径：缺 notify 时 flag 仍可写（存量零漂移）")
        assertEquals(plainAfter.notifyPolicy, None, "已显式清除 notify 的节点：flag 写入不复活 notify（notify 权威面在 provide 时）")
        assertEquals(plainAfter.notifyDispatcher, true, "缺 notify 时 flag 照写")
      }
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield ()
  }
