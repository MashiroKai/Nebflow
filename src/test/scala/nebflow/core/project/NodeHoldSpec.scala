package nebflow.core.project

import cats.effect.{IO, Ref}
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, Behaviors}
import nebflow.agent.{AgentCommand, AgentKind, AgentLibrary, AgentRecord, SharedResources}
import nebflow.core.PathUtil
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, NodeCancelTool, NodeEditTool, ToolContext}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * Node「暂停/人在回路」MVP（方案 B「节点 hold」）十用例（20260903 设计 §5.3）：
 *
 * - T1 hold 节点完成 → held（status=held / result 全文落库 / ttlExpireAt=None /
 *   无投递：下游 deliveredTo 空 + 未启动 / deps 依赖者未触发 / Nebula 收到 held 全文通知）
 * - T2 release → 投递链（held→completed / 下游启动且输入含上游结果段 /
 *   多 in barrier 时仍等齐其他上游）
 * - T3 release+note（下游输入含「用户补充（放行时注入）」追加段 / 单事务原子）
 * - T4 BLOCKED 优先（hold 节点输出 BLOCKED 锚定 → blocked 非 held / blockCount+1 /
 *   FeedbackRouter 路由被调——escalate-only 档经 deliverToNebula 可观测）
 * - T5 重启恢复（held 后重建 store open 重载 → held 原样 / release 照常成功）
 * - T6 校验负向五连（hold+out=Nebula 拒 / completed 设 hold 拒 / release 非 held 拒 /
 *   release+task 同传拒 / note 单独拒）
 * - T7 dup-dispatch（held 同 agent+task 重派拒「疑似重复派发」）
 * - T8 abandon held（→cancelled+TTL+审计；NodeCancel 对 held no-op）
 * - T9 held 改接后 release（NodeEdit out→新目标 → release → 结果投新目标，
 *   改接一等语义兼容）
 * - T10 held 链归档排除（sweepExpired 不动 held 节点 / Terminal 判定不含 held）
 *
 * 变异验红记录（见实施报告）：去掉 completeNode hold 分支（恢复直落 completed 原路径）
 * → T1/T4 相关用例红（复现「完成即投递」旧行为），恢复后绿。
 */
class NodeHoldSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-node-hold"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "test-agent")
  os.write.over(
    tempRoot / "agents" / "test-agent" / "agent.json",
    """{"name":"test-agent","description":"hold regression agent","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "test-agent" / "system.md", "# test-agent\n")
  // 2026-09-05 agent 退役：新建节点执行统一 general——fixture 侧补 general agent
  os.makeDir.all(tempRoot / "agents" / "general")
  os.write.over(tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"general executor","tools":[],"category":"standalone"}""")
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  /** 捕获输入 LLM：记录每次请求的 user 文本；应答 = 输入首行（入口节点即 task
    * 首行 → 断言「结果全文」有区分度）。task 含 blocked-please → BLOCKED 锚定应答
    * （T4 走 blocked 分流）。 */
  private class CaptureLlm(delayOf: String => FiniteDuration = _ => 0.millis):
    val inputs: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)
    def handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(
          req: LlmRequest,
          onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
      ): Stream[IO, StreamChunk] =
        val text = req.messages.map(_.textContent).mkString("\n")
        val reply =
          if text.contains("blocked-please") then
            "BLOCKED\n{\"category\":\"external-dependency\",\"detail\":\"vendor API down\",\"suggestion\":\"retry later\"}"
          else text.linesIterator.nextOption().getOrElse("").take(200)
        Stream
          .eval(inputs.update(_ :+ text) >> IO.sleep(delayOf(text)))
          .flatMap(_ => Stream(StreamChunk.TextDelta(reply), StreamChunk.Done(None, None)))

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
      sessionId = Some("hold-sid"),
      rootSessionId = Some("nebula-root"),
      sharedResources = Some(res),
      actorSystem = Some(system)
    )

  private def nodeEdit(input: Json, ctx: ToolContext): IO[Either[String, String]] =
    NodeEditTool.call(input.asObject.get, ctx).map(_.left.map(_.message))

  private def nodeCancel(project: String, nodeId: String, ctx: ToolContext): IO[Either[String, String]] =
    NodeCancelTool
      .call(Json.obj("project" -> Json.fromString(project), "node-id" -> Json.fromString(nodeId)).asObject.get, ctx)
      .map(_.left.map(_.message))

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

  /** 根会话记录器：deliverToNebula 的投递终点（held 通知 / completed 通知 /
    * escalate-only 升级）全部记账，供断言。 */
  private def registerRecorder(res: SharedResources, system: ActorSystem, sid: String): IO[Ref[IO, List[AgentCommand]]] =
    for
      recorded <- Ref.of[IO, List[AgentCommand]](Nil)
      ref <- system.spawn(recorderBehavior(recorded), s"rec-${scala.util.Random.nextInt(100000)}")
      _ <- res.agentRegistry.update(_ + (sid -> AgentRecord(sid, ref, AgentKind.Root, sid)))
    yield recorded

  private def recorderBehavior(recorded: Ref[IO, List[AgentCommand]]): nebflow.actor.Behavior[AgentCommand] =
    lazy val b: nebflow.actor.Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand](msg => recorded.update(_ :+ msg).as(b))
    b

  private def recordedImmediate(recorded: Ref[IO, List[AgentCommand]]): IO[List[AgentCommand.ImmediateInput]] =
    recorded.get.map(_.collect { case m: AgentCommand.ImmediateInput => m })

  private def mountProject(
    name: String,
    ws: os.Path,
    system: ActorSystem,
    res: SharedResources,
    feedbackMode: String = FeedbackRouter.ModeAuto
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
        feedbackMode = feedbackMode,
        emitEvent = (_, _, _) => IO.unit
      )
      pd = ProjectDef(name = name, workspace = ws.toString, agentFile = (ws / "AGENTS.md").toString, createdAt = System.currentTimeMillis())
      rt = ProjectRuntime(pd, store, engine, system, res, None)
      _ <- ProjectRuntimeRegistry.register(rt)
    yield rt

  /** 按名取节点（活动区；找不到 fail 该测试）。 */
  private def byName(rt: ProjectRuntime, name: String): IO[NodeDef] =
    rt.store.snapshot.map(_.nodes.values.find(_.name == name)).map {
      case Some(n) => n
      case None    => fail(s"node '$name' must exist")
    }

  private def idOf(rt: ProjectRuntime, name: String): IO[String] =
    byName(rt, name).map(_.id)

  private def nodeById(rt: ProjectRuntime, id: String): IO[Option[NodeDef]] =
    rt.store.snapshot.map(_.nodes.get(id))

  private def waitStatus(rt: ProjectRuntime, name: String, statuses: Set[String]): IO[Unit] =
    waitUntil(15.seconds) {
      rt.store.snapshot.map(_.nodes.values.find(_.name == name)).flatMap {
        case Some(n) => IO.pure(statuses.contains(n.status))
        case None    => IO.pure(false)
      }
    }

  /** wiring out-only 节点直种（NodeEdit 创建必带输入侧，种子等价——NodeBarrierDeliverySpec 同款）。 */
  private def seedWiring(rt: ProjectRuntime, id: String, name: String, task: Option[String] = None,
    deps: List[String] = Nil): IO[Unit] =
    rt.store.mutate(s => s.copy(nodes = s.nodes ++ Map(
      id -> NodeDef(id = id, name = name, agent = "test-agent", task = task, deps = deps,
        status = NodeLifecycle.Wiring, out = Some("Nebula"), createdAt = System.currentTimeMillis())))).void

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  // ── T1 hold 节点完成 → held ─────────────────────────────

  test("T1: hold node completes → held (result stored in full, never expires, NO delivery, Nebula notified in full)") {
    val ws = tempRoot / "ws-hold-t1"
    os.makeDir.all(ws)
    val system = ActorSystem(s"hold-t1-${scala.util.Random.nextInt(100000)}")
    val llm = CaptureLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      recorded <- registerRecorder(res, system, "nebula-root")
      rt <- mountProject("hold-t1", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- seedWiring(rt, "n-b", "node-b")
      bId <- idOf(rt, "node-b")
      _ <- nodeEdit(nodeInput("hold-t1", "hold-a", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("hold-result-ALPHA"), "out" -> Json.fromString(bId),
        "hold" -> Json.fromBoolean(true)), ctx)
      _ <- waitStatus(rt, "hold-a", Set(NodeLifecycle.Held))
      aId <- idOf(rt, "hold-a")
      // deps 依赖者（A 完成信号）
      _ <- seedWiring(rt, "n-d", "dep-d", deps = List(aId))
      _ <- IO.sleep(300.millis) // held 分流后若有（错误）投递/结算，给窗口显形
      a <- byName(rt, "hold-a")
      b <- nodeById(rt, bId).map(_.getOrElse(fail("B must exist")))
      d <- byName(rt, "dep-d")
      imms <- recordedImmediate(recorded)
      inputs <- llm.inputs.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // 状态与落库
      assertEquals(a.status, NodeLifecycle.Held, "hold node must finalize as held")
      assertEquals(a.hold, true)
      assertEquals(a.result, Some("hold-result-ALPHA"), "result must be persisted in FULL")
      assertEquals(a.ttlExpireAt, None, "held never expires (blocked-style todo semantics)")
      assert(a.completedAt.isDefined, "completedAt kept at held moment")
      // 无投递：下游未收到、未启动；deps 依赖者未触发
      assertEquals(b.deliveredTo, Nil, s"held node must NOT deliver downstream, got ${b.deliveredTo}")
      assertEquals(b.status, NodeLifecycle.Wiring, "downstream must stay wiring")
      assertEquals(d.status, NodeLifecycle.Wiring, "deps waiter must NOT be triggered by held")
      assert(!inputs.exists(_.contains("=== Node hold-a ===")),
        "downstream must never be spawned (no input carrying the held result)")
      // Nebula 收到 held 全文通知（决策②：结果全文，对齐 out=Nebula 先例）
      assertEquals(imms.size, 1, s"exactly one Nebula notification expected, got ${imms.map(_.text.take(80))}")
      assertEquals(imms.head.eventType, Some("held"), "held rides the bubble header")
      assert(imms.head.text.contains("held, awaiting release"), s"notification must announce held, got: ${imms.head.text.take(120)}")
      assert(imms.head.text.contains("hold-result-ALPHA"), "notification must carry the FULL result")
      // NodePayload 条件序列化：hold 节点带键，无 hold 节点不带（NodeEventPushSpec 兼容）
      val heldJson = NodePayload.buildNodeJson(a, System.currentTimeMillis())
      assertEquals(heldJson.asObject.get("hold").flatMap(_.asBoolean), Some(true), "held node payload carries hold=true")
      val plainJson = NodePayload.buildNodeJson(b, System.currentTimeMillis())
      assert(plainJson.asObject.get("hold").isEmpty, "non-hold node payload must NOT carry the hold key")
  }

  // ── T2 release → 投递链 ─────────────────────────────────

  test("T2: release → held to completed, delivery chain runs, multi-in barrier still waits for the OTHER upstream") {
    val ws = tempRoot / "ws-hold-t2"
    os.makeDir.all(ws)
    val system = ActorSystem(s"hold-t2-${scala.util.Random.nextInt(100000)}")
    // C 轻微延迟，保证 A held 时 C 仍在跑（barrier 部分等待窗口）
    val llm = CaptureLlm(text => if text.contains("normal-result-CHARLIE") then 400.millis else 0.millis)
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("hold-t2", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- seedWiring(rt, "n-merge", "merge-b")
      mergeId <- idOf(rt, "merge-b")
      _ <- nodeEdit(nodeInput("hold-t2", "hold-a", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("hold-result-BRAVO"), "out" -> Json.fromString(mergeId),
        "hold" -> Json.fromBoolean(true)), ctx)
      _ <- nodeEdit(nodeInput("hold-t2", "normal-c", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("normal-result-CHARLIE"), "out" -> Json.fromString(mergeId)), ctx)
      _ <- waitStatus(rt, "hold-a", Set(NodeLifecycle.Held))
      aId <- idOf(rt, "hold-a")
      cId <- idOf(rt, "normal-c")
      _ <- waitStatus(rt, "normal-c", Set(NodeLifecycle.Completed))
      _ <- IO.sleep(200.millis)
      partial <- nodeById(rt, mergeId).map(_.getOrElse(fail("merge must exist")))
      // release
      rel <- nodeEdit(nodeInput("hold-t2", "hold-a", "release" -> Json.fromBoolean(true)), ctx)
      _ <- waitStatus(rt, "hold-a", Set(NodeLifecycle.Completed))
      _ <- waitStatus(rt, "merge-b", Set(NodeLifecycle.Completed))
      a <- byName(rt, "hold-a")
      merged <- nodeById(rt, mergeId).map(_.getOrElse(fail("merge must exist")))
      mergedInput <- llm.inputs.get.map(_.find(t => t.contains("=== Node hold-a ===") && t.contains("=== Node normal-c ===")))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(rel.isRight, s"release must succeed, got $rel")
      assertEquals(a.status, NodeLifecycle.Completed, "release: held → completed")
      assert(a.ttlExpireAt.isDefined, "display TTL restarts from release")
      // 放行前：C 已投、A 未投、barrier 未归零（merge 等 A）
      assert(partial.deliveredTo.contains(cId), "completed C delivers regardless of held A")
      assert(!partial.deliveredTo.contains(aId), "held A must not deliver before release")
      assertEquals(partial.status, NodeLifecycle.Wiring, "barrier not zero while A held")
      // 放行后：投递链全跑
      assert(merged.deliveredTo.toSet.contains(aId) && merged.deliveredTo.toSet.contains(cId),
        s"merge must receive BOTH after release, got ${merged.deliveredTo}")
      assert(mergedInput.isDefined, "merge input must carry BOTH upstream results")
      assert(mergedInput.exists(_.contains("hold-result-BRAVO")), "merge input must carry A's full held result")
  }

  // ── T3 release + note ───────────────────────────────────

  test("T3: release+note → user note injected into downstream task (single transaction), downstream input carries it") {
    val ws = tempRoot / "ws-hold-t3"
    os.makeDir.all(ws)
    val system = ActorSystem(s"hold-t3-${scala.util.Random.nextInt(100000)}")
    val llm = CaptureLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("hold-t3", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- seedWiring(rt, "n-consumer", "consumer-b", task = Some("base-task-DELTA"))
      bId <- idOf(rt, "consumer-b")
      _ <- nodeEdit(nodeInput("hold-t3", "hold-a", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("hold-result-ECHO"), "out" -> Json.fromString(bId),
        "hold" -> Json.fromBoolean(true)), ctx)
      _ <- waitStatus(rt, "hold-a", Set(NodeLifecycle.Held))
      _ <- nodeEdit(nodeInput("hold-t3", "hold-a", "release" -> Json.fromBoolean(true),
        "note" -> Json.fromString("补充：请使用 v2 接口重试")), ctx)
      _ <- waitStatus(rt, "hold-a", Set(NodeLifecycle.Completed))
      _ <- waitStatus(rt, "consumer-b", Set(NodeLifecycle.Completed))
      a <- byName(rt, "hold-a")
      b <- byName(rt, "consumer-b")
      bInput <- llm.inputs.get.map(_.find(_.contains("=== Node hold-a ===")))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(a.status, NodeLifecycle.Completed, "release completed the held node")
      // note 原子注入下游 task（同事务）
      assert(b.task.exists(_.contains("base-task-DELTA")), "original task preserved")
      assert(b.task.exists(_.contains(NodeEngine.ReleaseNoteMarker)), s"note marker injected, got task=${b.task}")
      assert(b.task.exists(_.contains("补充：请使用 v2 接口重试")), "note text injected")
      // 下游输入（buildInput 沿 task）携带追加段
      assert(bInput.exists(_.contains(NodeEngine.ReleaseNoteMarker)), "downstream input carries the injected note")
      assert(bInput.exists(_.contains("补充：请使用 v2 接口重试")), "downstream input carries the note text")
      assert(bInput.exists(_.contains("hold-result-ECHO")), "downstream input carries upstream result too")
  }

  // ── T4 BLOCKED 优先于 hold ──────────────────────────────

  test("T4: BLOCKED anchor wins over hold → blocked (not held), blockCount+1, FeedbackRouter invoked") {
    val ws = tempRoot / "ws-hold-t4"
    os.makeDir.all(ws)
    val system = ActorSystem(s"hold-t4-${scala.util.Random.nextInt(100000)}")
    val llm = CaptureLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      recorded <- registerRecorder(res, system, "nebula-root")
      // escalate-only 档：路由裁决 → 升级 deliverToNebula（recorder 可观测 = 路由被调证据）
      rt <- mountProject("hold-t4", ws, system, res, feedbackMode = FeedbackRouter.ModeEscalateOnly)
      ctx = mkCtx(res, system, ws.toString)
      _ <- seedWiring(rt, "n-b4", "node-b4")
      bId <- idOf(rt, "node-b4")
      _ <- nodeEdit(nodeInput("hold-t4", "hold-blocked", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("blocked-please"), "out" -> Json.fromString(bId),
        "hold" -> Json.fromBoolean(true)), ctx)
      _ <- waitStatus(rt, "hold-blocked", Set(NodeLifecycle.Blocked))
      _ <- waitUntil(5.seconds)(recordedImmediate(recorded).map(_.nonEmpty))
      a <- byName(rt, "hold-blocked")
      b <- nodeById(rt, bId).map(_.getOrElse(fail("B must exist")))
      imms <- recordedImmediate(recorded)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // blocked 优先：BLOCKED 锚定不被 held 吞掉
      assertEquals(a.status, NodeLifecycle.Blocked, "BLOCKED anchor must win over hold")
      assert(a.status != NodeLifecycle.Held, "must NOT be held")
      assertEquals(a.blockCount, 1, "blockCount incremented by blockedNode only")
      assert(a.blockedFeedback.isDefined, "structured feedback persisted")
      assertEquals(a.blockedFeedback.map(_.category), Some("external-dependency"))
      assert(a.result.exists(_.contains("vendor API down")), "rendered feedback in result")
      // 传播停止：下游同样不收（blocked 语义）
      assertEquals(b.deliveredTo, Nil, "blocked also stops propagation")
      // FeedbackRouter 路由被调（escalate-only → deliverToNebula eventType=blocked）
      assert(imms.nonEmpty, "escalation proves the FeedbackRouter route was invoked")
      assert(imms.exists(_.eventType.contains("blocked")), s"escalation bubble, got ${imms.map(_.eventType)}")
      assert(!imms.exists(_.eventType.contains("held")), "no held notification for a blocked outcome")
  }

  // ── T5 重启恢复 ─────────────────────────────────────────

  test("T5: restart recovery — store reopen reloads held verbatim; release still works") {
    val ws = tempRoot / "ws-hold-t5"
    os.makeDir.all(ws)
    val system = ActorSystem(s"hold-t5-${scala.util.Random.nextInt(100000)}")
    val llm = CaptureLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("hold-t5", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- seedWiring(rt, "n-b5", "node-b5")
      bId <- idOf(rt, "node-b5")
      _ <- nodeEdit(nodeInput("hold-t5", "hold-a", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("hold-result-FOXTROT"), "out" -> Json.fromString(bId),
        "hold" -> Json.fromBoolean(true)), ctx)
      _ <- waitStatus(rt, "hold-a", Set(NodeLifecycle.Held))
      aId <- idOf(rt, "hold-a")
      heldBefore <- rt.store.getNode(aId)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
      // ── 模拟重启：重建 store（open 重载 flow-map.json）+ 新引擎 ──
      system2 = ActorSystem(s"hold-t5b-${scala.util.Random.nextInt(100000)}")
      res2 <- mkResources(system2, tempRoot, llm.handle)
      store2 <- FlowMapStore.open("hold-t5", ws.toString)
      engine2 = new NodeEngine(
        store2, system2, res2, wsSendFn = (_: Json) => IO.unit, workspace = ws.toString,
        rootSessionId = "nebula-root", projectName = "hold-t5",
        feedbackMode = FeedbackRouter.ModeAuto, emitEvent = (_, _, _) => IO.unit)
      reloaded <- store2.getNode(aId)
      // 重启后 release 照常成功（engine 单点，直调——NodeEdit 亦汇入同一路径）
      rel <- engine2.releaseNode(aId, None)
      _ <- waitUntil(15.seconds)(store2.getNode(aId).map(_.exists(_.status == NodeLifecycle.Completed)))
      _ <- waitUntil(15.seconds)(store2.getNode(bId).map(_.exists(_.status == NodeLifecycle.Completed)))
      bAfter <- store2.getNode(bId)
      bInput <- llm.inputs.get.map(_.find(t => t.contains("=== Node hold-a ===")))
      _ <- system2.stopAll.handleErrorWith(_ => IO.unit)
    yield
      val before = heldBefore.getOrElse(fail("A must exist pre-restart"))
      assertEquals(before.status, NodeLifecycle.Held, "held pre-restart")
      val after = reloaded.getOrElse(fail("A must survive restart"))
      assertEquals(after.status, NodeLifecycle.Held, "held status restored verbatim from flow-map.json")
      assertEquals(after.result, Some("hold-result-FOXTROT"), "held result restored in full")
      assertEquals(after.hold, true, "hold flag restored")
      assertEquals(after.ttlExpireAt, None, "held still never-expiring after reload")
      assert(rel.isRight, s"release after restart must succeed, got $rel")
      assert(bAfter.exists(_.deliveredTo.contains(aId)), s"downstream received the result post-restart-release, got ${bAfter.map(_.deliveredTo)}")
      assert(bInput.isDefined, "downstream ran with the upstream result after restart-release")
  }

  // ── T6 校验负向五连 ─────────────────────────────────────

  test("T6: validation negatives — hold+out=Nebula / completed-hold / release-non-held / release+task / note-alone") {
    val ws = tempRoot / "ws-hold-t6"
    os.makeDir.all(ws)
    val system = ActorSystem(s"hold-t6-${scala.util.Random.nextInt(100000)}")
    val llm = CaptureLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("hold-t6", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // N1: hold=true + out=Nebula（create）→ 拒
      n1 <- nodeEdit(nodeInput("hold-t6", "n1-nebula", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("n1"), "out" -> Json.fromString("Nebula"),
        "hold" -> Json.fromBoolean(true)), ctx)
      // 正常入口节点跑完 → completed
      _ <- nodeEdit(nodeInput("hold-t6", "n2-done", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("n2-task"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- waitStatus(rt, "n2-done", Set(NodeLifecycle.Completed))
      // N2: completed 上设置 hold → 拒
      n2 <- nodeEdit(nodeInput("hold-t6", "n2-done", "hold" -> Json.fromBoolean(true)), ctx)
      // N3: release 非 held（completed）→ 拒
      n3 <- nodeEdit(nodeInput("hold-t6", "n2-done", "release" -> Json.fromBoolean(true)), ctx)
      // N4: release + task 同传 → 拒（整调用拒绝，决策④）
      n4 <- nodeEdit(nodeInput("hold-t6", "n2-done", "release" -> Json.fromBoolean(true),
        "task" -> Json.fromString("rewire-attempt")), ctx)
      // N5: note 单独出现（无 release）→ 拒
      n5 <- nodeEdit(nodeInput("hold-t6", "n2-done", "note" -> Json.fromString("orphan-note")), ctx)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
      n2node <- byName(rt, "n2-done")
    yield
      assert(n1.isLeft && n1.left.exists(_.contains("nothing to hold")),
        s"N1 hold+out=Nebula must refuse with actionable text, got $n1")
      assert(n2.isLeft && n2.left.exists(_.contains("before completion")),
        s"N2 hold on completed must refuse, got $n2")
      assert(n3.isLeft && n3.left.exists(_.contains("not held")),
        s"N3 release non-held must refuse, got $n3")
      assert(n4.isLeft && n4.left.exists(_.contains("standalone action")),
        s"N4 release+task must refuse (no half-release-half-edit), got $n4")
      assert(n5.isLeft && n5.left.exists(_.contains("only valid together with release")),
        s"N5 note alone must refuse, got $n5")
      // 负向调用零副作用：n2-done 仍 completed 且未带 hold
      assertEquals(n2node.status, NodeLifecycle.Completed, "refused edits leave the node untouched")
      assertEquals(n2node.hold, false, "refused hold edit must not flip the flag")
  }

  // ── T7 dup-dispatch ─────────────────────────────────────

  test("T7: held node same agent+task re-dispatch refused (suspected duplicate dispatch)") {
    val ws = tempRoot / "ws-hold-t7"
    os.makeDir.all(ws)
    val system = ActorSystem(s"hold-t7-${scala.util.Random.nextInt(100000)}")
    val llm = CaptureLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("hold-t7", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- seedWiring(rt, "n-b7", "node-b7") // hold 要求节点 out（规则①：out=Nebula 无 hold 意义）
      bId <- idOf(rt, "node-b7")
      _ <- nodeEdit(nodeInput("hold-t7", "dup-a", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("dup-dispatch-TASK"), "out" -> Json.fromString(bId),
        "hold" -> Json.fromBoolean(true)), ctx)
      _ <- waitStatus(rt, "dup-a", Set(NodeLifecycle.Held))
      dup <- nodeEdit(nodeInput("hold-t7", "dup-b", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("dup-dispatch-TASK"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(dup.isLeft && dup.left.exists(_.contains("疑似重复派发")),
        s"held task must still count as dispatched, got $dup")
  }

  // ── T8 abandon held + NodeCancel no-op ──────────────────

  test("T8: abandon held → cancelled+TTL (clean-up exit); NodeCancel on held is a no-op") {
    val ws = tempRoot / "ws-hold-t8"
    os.makeDir.all(ws)
    val system = ActorSystem(s"hold-t8-${scala.util.Random.nextInt(100000)}")
    val llm = CaptureLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("hold-t8", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- seedWiring(rt, "n-b8", "node-b8")
      bId8 <- idOf(rt, "node-b8")
      _ <- nodeEdit(nodeInput("hold-t8", "hold-a", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("hold-result-HOTEL"), "out" -> Json.fromString(bId8),
        "hold" -> Json.fromBoolean(true)), ctx)
      _ <- waitStatus(rt, "hold-a", Set(NodeLifecycle.Held))
      aId <- idOf(rt, "hold-a")
      // NodeCancel 对 held：既有 no-op 分支天然覆盖（零改动，确认）
      cancel <- nodeCancel("hold-t8", aId, ctx)
      stillHeld <- rt.store.getNode(aId)
      // abandon held → cancelled + TTL + 审计
      ab <- nodeEdit(nodeInput("hold-t8", "hold-a", "abandon" -> Json.fromBoolean(true)), ctx)
      _ <- waitStatus(rt, "hold-a", Set(NodeLifecycle.Cancelled))
      a <- rt.store.getNode(aId)
      events <- IO.blocking(os.read(os.Path(ws.toString, PathUtil.dataRoot) / ".nebflow" / FlowMapEventLog.FileName))
        .map(_.linesIterator.toList)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(cancel.isRight && cancel.exists(_.contains("not running (status=held)")),
        s"NodeCancel on held must hit the no-op branch, got $cancel")
      assertEquals(stillHeld.map(_.status), Some(NodeLifecycle.Held), "cancel no-op leaves held intact")
      assert(ab.isRight, s"abandon held must succeed, got $ab")
      val node = a.getOrElse(fail("A must exist"))
      assertEquals(node.status, NodeLifecycle.Cancelled, "abandon: held → cancelled")
      assert(node.ttlExpireAt.isDefined, "cancelled gets display TTL")
      assert(events.exists(l => l.contains("\"type\":\"abandoned\"") && l.contains(aId)),
        s"audit event logged, got $events")
  }

  // ── T9 held 改接后 release ──────────────────────────────

  test("T9: rewire held node to a NEW target, then release → result delivered to the new target (D1-compatible)") {
    val ws = tempRoot / "ws-hold-t9"
    os.makeDir.all(ws)
    val system = ActorSystem(s"hold-t9-${scala.util.Random.nextInt(100000)}")
    val llm = CaptureLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("hold-t9", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- seedWiring(rt, "n-old", "old-b")
      _ <- seedWiring(rt, "n-new", "new-c")
      oldId <- idOf(rt, "old-b")
      newId <- idOf(rt, "new-c")
      _ <- nodeEdit(nodeInput("hold-t9", "hold-a", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("hold-result-GOLF"), "out" -> Json.fromString(oldId),
        "hold" -> Json.fromBoolean(true)), ctx)
      _ <- waitStatus(rt, "hold-a", Set(NodeLifecycle.Held))
      aId <- idOf(rt, "hold-a")
      // held 期改接：out → 新目标（改接一等语义）
      rew <- nodeEdit(nodeInput("hold-t9", "hold-a", "out" -> Json.fromString(newId)), ctx)
      _ <- IO.sleep(200.millis) // 若改接误触发立即投递（不该），给窗口显形
      mid <- nodeById(rt, newId).map(_.getOrElse(fail("new-c must exist")))
      // release → 结果投新目标
      rel <- nodeEdit(nodeInput("hold-t9", "hold-a", "release" -> Json.fromBoolean(true)), ctx)
      _ <- waitStatus(rt, "hold-a", Set(NodeLifecycle.Completed))
      _ <- waitStatus(rt, "new-c", Set(NodeLifecycle.Completed))
      a <- byName(rt, "hold-a")
      newC <- nodeById(rt, newId).map(_.getOrElse(fail("new-c must exist")))
      oldB <- nodeById(rt, oldId).map(_.getOrElse(fail("old-b must exist")))
      newInput <- llm.inputs.get.map(_.find(t => t.contains("=== Node hold-a ===")))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(rew.isRight, s"rewire on held must succeed, got $rew")
      assertEquals(a.out, Some(newId), "held node rewired to new target")
      // 改接本身不投递（held 仍扣住）
      assertEquals(mid.deliveredTo, Nil, "rewiring alone must NOT deliver")
      assert(rel.isRight, s"release after rewire must succeed, got $rel")
      assertEquals(newC.deliveredTo, List(aId), s"NEW target received the result, got ${newC.deliveredTo}")
      assertEquals(newC.status, NodeLifecycle.Completed, "new target ran with the result")
      assert(newInput.exists(_.contains("hold-result-GOLF")), "new target input carries the held result")
      // 旧目标全程未收未启动
      assertEquals(oldB.deliveredTo, Nil, "old target must NOT receive after rewire")
      assertEquals(oldB.status, NodeLifecycle.Wiring, "old target stays wiring")
  }

  // ── T10 held 链归档排除 ─────────────────────────────────

  test("T10: held chains excluded from archival — sweepExpired leaves held; Terminal set does not contain held") {
    val ws = tempRoot / "ws-hold-t10"
    os.makeDir.all(ws)
    val system = ActorSystem(s"hold-t10-${scala.util.Random.nextInt(100000)}")
    val llm = CaptureLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("hold-t10", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- seedWiring(rt, "n-b10", "node-b10")
      bId10 <- idOf(rt, "node-b10")
      _ <- nodeEdit(nodeInput("hold-t10", "hold-a", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("hold-result-INDIA"), "out" -> Json.fromString(bId10),
        "hold" -> Json.fromBoolean(true)), ctx)
      _ <- waitStatus(rt, "hold-a", Set(NodeLifecycle.Held))
      aId <- idOf(rt, "hold-a")
      // 已过期 completed 节点（TTL 1h 前到期）——sweep 应移走它
      now <- IO(System.currentTimeMillis())
      _ <- rt.store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        "n-old10" -> NodeDef(id = "n-old10", name = "old-c10", agent = "test-agent",
          status = NodeLifecycle.Completed, result = Some("old done"),
          createdAt = now - 26 * 3600_000L, completedAt = Some(now - 25 * 3600_000L),
          ttlExpireAt = Some(now - 3600_000L)))))
      swept <- rt.store.sweepExpired(now + 1000L)
      heldAfter <- rt.store.getNode(aId)
      oldAfter <- rt.store.getNode("n-old10")
      arch <- rt.store.archiveSnapshot
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // 终态判定：held ∉ Terminal（整链归档判定天然排除的机制根）
      assert(!NodeLifecycle.Terminal.contains(NodeLifecycle.Held),
        "held must NOT be in Terminal — held chains never count as fully terminal")
      // sweepExpired：过期 completed 移走，held 原地不动（双保险：非 Terminal 且 ttlExpireAt=None）
      assertEquals(swept, List("n-old10"), s"only the expired completed node is swept, got $swept")
      val held = heldAfter.getOrElse(fail("held node must survive the sweep"))
      assertEquals(held.status, NodeLifecycle.Held, "held stays in the active map")
      assertEquals(held.result, Some("hold-result-INDIA"), "held result intact")
      assert(oldAfter.isEmpty, "expired completed node removed from active area")
      assert(arch.nodes.contains("n-old10"), "expired completed node archived (result retained)")
  }

end NodeHoldSpec
