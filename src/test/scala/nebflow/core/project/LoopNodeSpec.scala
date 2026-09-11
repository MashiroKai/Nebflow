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
import nebflow.core.tools.{BgTaskRegistry, FileLockManager, NodeEditTool, ToolContext}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * LoopNode 语义测试（LoopNode 批 2026-09-06，主设计 20260902_flowmap-engine-evolution-design.md §2/§4.2）。
 *
 * 覆盖核心语义①-⑤：
 *   ① worker PASS → completed（投递 worker 产出原文）
 *   ② verify FAIL → 打回 worker → 重跑 → 再 PASS（含输入恒定 + 双会话存续证据）
 *   ③ 达 maxRounds(K) 仍未 PASS → 终态 failed（轮级兜底）
 *   ④ 输入恒定：重跑输入 = 返工模板（意见/标准 + 协议脚注），不重注入产出全文
 *   ⑤ 终态双销毁（registry 无 node-* 滞留，worker+verify 两会话均销毁）
 * 附：⑥ payload 序列化（loop 节点带 loop 配置 + 运行态条件字段；非 loop 节点零变化）
 *
 * 基建同 NodeBarrierDeliverySpec（CaptureLlm/mountProject/waitStatus/nodeEdit）。
 * verify 判定文法经 VerdictReader：响应以「VERDICT: PASS/FAIL」锚定；无锚定 → Pass 直通
 * （降级语义）。故 LoopLlm 按输入内容区分 worker（产出，无锚定 → 普通文本）与 verify
 * （判定，锚定 VERDICT）响应——判定键 = 每请求的**最后一条消息**（本轮新注入；持久会话
 * 下历史消息在列表前部，不干扰「本轮注入」判定）。
 *
 * 注：turn 级 LoopGuard（会话内精确重复防线）是既有机制，已由 LoopGuardSpec /
 * LoopGuardWiringSpec 独立覆盖；本 spec 只负责 LoopNode 的**轮级 maxRounds** 轴（③）。
 */
class LoopNodeSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-loop-node"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "general")
  os.write.over(tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"general executor","tools":[],"category":"standalone"}""")
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  /** 按输入内容返回不同 LLM 响应的捕获桩（worker/verify 双会话共用一个 llm handle）。
    * respond 以「本轮注入的 last 消息文本」为键：worker 注入 = 任务/返工模板（不含
    * 【LoopNode 验证】标记），verify 注入 = 验证模板（含【LoopNode 验证】）。 */
  private class LoopLlm(
    respond: String => String,
    delayOf: String => FiniteDuration = _ => 0.millis,
    // noderpt 批 B 段 ④：每轮副作用钩子（本轮注入文本 → IO）——用于在某一轮注入
    // `node_report` 残留申报等探针（default no-op ⇒ 既有用例零影响）。
    onTurn: String => IO[Unit] = _ => IO.unit
  ):
    val inputs: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)    // 全请求文本
    val lastTurns: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil) // 每请求最后一条消息
    def handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(
          req: LlmRequest,
          onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
      ): Stream[IO, StreamChunk] =
        val text = req.messages.map(_.textContent).mkString("\n")
        val last = req.messages.lastOption.map(_.textContent).getOrElse("")
        Stream
          .eval(inputs.update(_ :+ text) >> lastTurns.update(_ :+ last) >> IO.sleep(delayOf(last)) >> onTurn(last))
          .flatMap(_ => Stream(StreamChunk.TextDelta(respond(last)), StreamChunk.Done(None, None)))

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
      sessionId = Some("loop-sid"),
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
    res: SharedResources,
    // noderpt 批 B 段：WS 帧捕获（默认丢弃 = 既有用例零影响）。
    wsFrames: Option[Ref[IO, List[Json]]] = None,
    // noderpt 批 B 段：销毁窗口压 0 ⇒ 到点即时可扫（生产默认 30min 走 Defaults 现读）。
    destroyWindowMs: Long = 0L
  ): IO[ProjectRuntime] =
    val send: Json => IO[Unit] = wsFrames match
      case Some(ref) => (j: Json) => ref.update(_ :+ j)
      case None      => (_: Json) => IO.unit
    for
      store <- FlowMapStore.open(name, ws.toString)
      engine = new NodeEngine(
        store,
        system,
        res,
        wsSendFn = send,
        workspace = ws.toString,
        rootSessionId = "nebula-root",
        projectName = name,
        emitEvent = (_, _, _) => IO.unit,
        // noderpt 批 A 段：本 fixture 主题非 node_report 语义 ⇒ 显式关腿 2（生产默认开；
        // 腿 2 默认开行为由 NodeReportReminderSpec 覆盖）。Loop 节点本就不在腿 2 判定面。
        reportGateHold = Some(false),
        destroyWindowMs = Some(destroyWindowMs)
      )
      pd = ProjectDef(name = name, workspace = ws.toString, agentFile = (ws / "AGENTS.md").toString, createdAt = System.currentTimeMillis())
      rt = ProjectRuntime(pd, store, engine, system, res, None)
      _ <- ProjectRuntimeRegistry.register(rt)
    yield rt

  private def idOf(rt: ProjectRuntime, name: String): IO[String] =
    rt.store.snapshot.map(_.nodes.values.find(_.name == name)).map {
      case Some(n) => n.id
      case None    => fail(s"node '$name' must exist")
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

  private def readEvents(ws: os.Path): IO[List[String]] =
    IO.blocking {
      val f = ws / ".nebflow" / "flow-map-events.jsonl"
      if os.exists(f) then os.read(f).linesIterator.toList else Nil
    }

  /** 捕获的 lastTurns 中属于「worker 注入」（不含验证标记）的 turn 集合。 */
  private def workerTurns(l: LoopLlm): IO[List[String]] =
    l.lastTurns.get.map(_.filterNot(_.contains("【LoopNode 验证")))

  /** 捕获的 lastTurns 中属于「verify 注入」（含验证标记）的 turn 集合。 */
  private def verifyTurns(l: LoopLlm): IO[List[String]] =
    l.lastTurns.get.map(_.filter(_.contains("【LoopNode 验证")))

  /** 终态双销毁反向断言：loop 会话（node-*）须从 agentRegistry 清空。 */
  private def waitSessionClean(res: SharedResources): IO[Unit] =
    waitUntil(10.seconds)(res.agentRegistry.get.map(_.keys.forall(!_.startsWith("node-"))))

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  /** 创建 loop 入口节点（task + 无 in + out=Nebula + loop 配置）；auto-start 由 createNode 触发。 */
  private def createLoop(
    project: String, name: String, task: String, maxRounds: Int, ctx: ToolContext
  ): IO[Either[String, String]] =
    nodeEdit(nodeInput(project, name,
      "description" -> Json.fromString("loop node purpose"),
      "task" -> Json.fromString(task),
      "loop" -> Json.fromBoolean(true),
      "maxRounds" -> Json.fromInt(maxRounds),
      "verify" -> Json.fromString("general"),
      "out" -> Json.fromString("Nebula")), ctx)

  // ── ① worker PASS → completed ──────────────────────────

  test("① worker PASS: single round — verify PASS → completed (worker output delivered)") {
    val ws = tempRoot / "ws-pass"
    os.makeDir.all(ws)
    val system = ActorSystem(s"loop-pass-${scala.util.Random.nextInt(100000)}")
    // verify 一律 PASS；worker 产 "ok"
    val llm = LoopLlm(t => if t.contains("【LoopNode 验证") then "VERDICT: PASS" else "ok")
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("loop-pass", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- createLoop("loop-pass", "loop-a", "produce-X", 5, ctx)
      _ <- waitStatus(rt, "loop-a", Set(NodeLifecycle.Completed))
      aId <- idOf(rt, "loop-a")
      n <- nodeById(rt, aId).map(_.getOrElse(fail("loop-a must exist")))
      _ <- waitSessionClean(res)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(n.status, NodeLifecycle.Completed, "loop node must complete on verify PASS")
      assertEquals(n.result, Some("ok"), "result must be the worker's final output (PASS delivers worker text)")
      assert(n.loop.isDefined, s"loop config must persist on the node, got ${n.loop}")
      // 运行态字段按最后一次 goto 落库（verify 轮、round=1）；payload 序列化带 loop 配置对象
      val payload = NodePayload.buildNodeJson(n, System.currentTimeMillis())
      assert(payload.hcursor.downField("loop").focus.exists(_.isObject),
        s"buildNodeJson must carry a loop config object, got ${payload.hcursor.downField("loop").focus}")
      assert(payload.hcursor.downField("loopRound").focus.exists(j => j.asNumber.exists(_.toInt.contains(1))),
        "loopRound must be serialized (=1 after a single round)")
  }

  // ── ② verify FAIL → 打回 worker → 重跑 → 再 PASS ────────

  test("② FAIL→rework→PASS: round 1 FAIL, worker re-runs on constant rework input, round 2 PASS") {
    val ws = tempRoot / "ws-failpass"
    os.makeDir.all(ws)
    val system = ActorSystem(s"loop-fp-${scala.util.Random.nextInt(100000)}")
    val WorkerAnswer = "DISTINCTIVE_WORKER_ANSWER"
    // verify 第 1 轮 FAIL、第 2 轮 PASS；worker 任何轮产出 WorkerAnswer
    val llm = LoopLlm(t =>
      if t.contains("【LoopNode 验证") then
        if t.contains("第 2 轮") then "VERDICT: PASS"
        else "VERDICT: FAIL\n{\"issues\":[\"need more detail\"],\"requirements\":\"be precise\"}"
      else WorkerAnswer)
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("loop-failpass", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- createLoop("loop-failpass", "loop-b", "produce-Y", 5, ctx)
      _ <- waitStatus(rt, "loop-b", Set(NodeLifecycle.Completed))
      bId <- idOf(rt, "loop-b")
      n <- nodeById(rt, bId).map(_.getOrElse(fail("loop-b must exist")))
      wTurns <- workerTurns(llm)
      vTurns <- verifyTurns(llm)
      wFull <- llm.inputs.get.map(_.filter(_.contains("【LoopNode 返工 · 第 2 轮】")))
      _ <- waitSessionClean(res)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(n.status, NodeLifecycle.Completed, "loop must complete after FAIL→rework→PASS")
      // ② 双会话存续 + 轮数：worker/verify 各恰 2 轮
      assertEquals(wTurns.size, 2, s"worker must run exactly 2 turns, got ${wTurns.size}")
      assertEquals(vTurns.size, 2, s"verify must run exactly 2 turns, got ${vTurns.size}")
      // ④ 输入恒定：重跑输入 = 返工模板（含第 2 轮标记 + 意见），且**不重注入产出全文**
      val reworkTurn = wTurns.find(_.contains("【LoopNode 返工 · 第 2 轮】"))
      assert(reworkTurn.isDefined, s"worker must receive a rework template for round 2, got $wTurns")
      assert(!reworkTurn.get.contains(WorkerAnswer),
        s"rework input must NOT re-inject the full round-1 output (输入恒定), got: ${reworkTurn.get.take(200)}")
      // ⑤ 双会话存续证据：round-2 worker 全请求内含 round-1 产出（同会话历史贯穿）
      assert(wFull.exists(_.contains(WorkerAnswer)),
        s"persistent worker session must carry round-1 output into round-2 context, got ${wFull.headOption.map(_.take(300))}")
      // verify 第 2 轮输入 = 短模板（持久上下文，不重复原始任务段）
      val v2 = vTurns.find(_.contains("第 2 轮"))
      assert(v2.exists(t => t.contains("== 待验证产出") && !t.contains("== 原始任务（验收基准） ==")),
        s"verify round-2 input must be the short template (persistent context), got: ${v2.map(_.take(200))}")
  }

  // ── ③ 达 maxRounds(K) 仍未 PASS → failed ────────────────

  test("③ maxRounds reached: K=3, verify always FAIL → loop terminalizes as failed (round-level cap)") {
    val ws = tempRoot / "ws-kcap"
    os.makeDir.all(ws)
    val system = ActorSystem(s"loop-k-${scala.util.Random.nextInt(100000)}")
    val K = 3
    val llm = LoopLlm(t =>
      if t.contains("【LoopNode 验证") then "VERDICT: FAIL\n{\"issues\":[\"never passes\"],\"requirements\":\"n/a\"}"
      else "ok")
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("loop-kcap", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- createLoop("loop-kcap", "loop-c", "produce-Z", K, ctx)
      _ <- waitStatus(rt, "loop-c", Set(NodeLifecycle.Failed))
      cId <- idOf(rt, "loop-c")
      n <- nodeById(rt, cId).map(_.getOrElse(fail("loop-c must exist")))
      wTurns <- workerTurns(llm)
      vTurns <- verifyTurns(llm)
      _ <- waitSessionClean(res)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(n.status, NodeLifecycle.Failed, "loop must fail on reaching maxRounds without PASS")
      assert(n.result.exists(_.contains(s"loop reached maxRounds=$K")),
        s"failure result must state the round cap, got ${n.result}")
      // 轮级计数：K 轮 worker + K 轮 verify（第 K 轮 FAIL 后不再进入第 K+1 轮 worker）
      assertEquals(wTurns.size, K, s"worker must run exactly K turns, got ${wTurns.size}")
      assertEquals(vTurns.size, K, s"verify must run exactly K turns, got ${vTurns.size}")
  }

  // ── ⑤ 终态双销毁（worker+verify 两会话）────

  test("⑤ dual-session destruction: failed loop (K cap) leaves no node-* sessions in agentRegistry") {
    val ws = tempRoot / "ws-destroy"
    os.makeDir.all(ws)
    val system = ActorSystem(s"loop-destroy-${scala.util.Random.nextInt(100000)}")
    val K = 2
    val llm = LoopLlm(t =>
      if t.contains("【LoopNode 验证") then "VERDICT: FAIL\n{\"issues\":[\"never passes\"],\"requirements\":\"n/a\"}"
      else "ok")
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("loop-destroy", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- createLoop("loop-destroy", "l-fail", "produce-F", K, ctx)
      _ <- waitStatus(rt, "l-fail", Set(NodeLifecycle.Failed))
      _ <- waitSessionClean(res)
      registry <- res.agentRegistry.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(registry.keys.filter(_.startsWith("node-")), Set.empty[String],
        s"no node-* (worker/verify) session may linger after failed loop terminal, got keys=${registry.keys.filter(_.startsWith("node-"))}")
  }

  // ── ⑦ 终态销毁窗口（noderpt 批 B 段 ④：Loop 双会话同批修）────

  test("⑦ destroy window: loop terminal registers BOTH sessions, clears the declaration slots and the sweep reclaims both") {
    val ws = tempRoot / "ws-loop-window"
    os.makeDir.all(ws)
    val system = ActorSystem(s"loop-win-${scala.util.Random.nextInt(100000)}")
    val K = 2
    @volatile var resRef: SharedResources = null
    // 在**每个 verify 轮**给 worker 会话留一条 node_report 残留申报：worker 的 drain
    // 只发生在其自身轮次结束点（本 Loop 末轮 worker 之后直接进 verify ⇒ 该申报留到终态）
    // ⇒ 只有 `destroyLoopSessions` 的 `NodeReportRegistry.remove` 能清（④ 直接证据）。
    val llm = new LoopLlm(
      t =>
        if t.contains("【LoopNode 验证") then
          "VERDICT: FAIL\n{\"issues\":[\"never passes\"],\"requirements\":\"n/a\"}"
        else "ok",
      onTurn = last =>
        IO(Option(resRef)).flatMap {
          case None => IO.unit
          case Some(r) =>
            if !last.contains("【LoopNode 验证") then IO.unit
            else
              r.agentRegistry.get.flatMap { reg =>
                reg.values.find(_.displayName.exists(_ == "l-win")).map(_.sessionId) match
                  case Some(sid) =>
                    // category="pass"（而非 blocked 类）：worker 的 drain 对它走「pass 与无
                    // 申报同链」，不改变 Loop 裁决路径（本用例主题是窗口收殓，不是分流）。
                    NodeReportRegistry.register(sid, BlockedFeedback("pass", "residue before loop terminal", ""))
                  case None => IO.unit
              }
        }
    )
    for
      res <- mkResources(system, tempRoot, llm.handle)
      _ <- IO { resRef = res }
      frames <- Ref.of[IO, List[Json]](Nil)
      rt <- mountProject("loop-window", ws, system, res, wsFrames = Some(frames))
      ctx = mkCtx(res, system, ws.toString)
      _ <- createLoop("loop-window", "l-win", "produce-W", K, ctx)
      _ <- waitStatus(rt, "l-win", Set(NodeLifecycle.Failed))
      _ <- waitSessionClean(res)
      id <- idOf(rt, "l-win")
      n <- nodeById(rt, id).map(_.getOrElse(fail("l-win must exist")))
      wSid = n.sessionRef.getOrElse(fail(s"worker sessionRef must be persisted: $n"))
      vSid = n.sessionRefVerify.getOrElse(fail(s"verify sessionRef must be persisted: $n"))
      residueW <- NodeReportRegistry.peek(wSid)
      residueV <- NodeReportRegistry.peek(vSid)
      banW <- BgTaskRegistry.finalizedAt(wSid)
      banV <- BgTaskRegistry.finalizedAt(vSid)
      eventsScheduled <- readEvents(ws)
      // 窗口内：双会话名下各有在跑任务（模拟 loop 两个会话的后台进程仍在）
      _ <- BgTaskRegistry.register("loop-bg-w", wSid, "worker bg", "local")
      _ <- BgTaskRegistry.register("loop-bg-v", vSid, "verify bg", "local")
      inWindowW <- BgTaskRegistry.waitingFor(wSid)
      inWindowV <- BgTaskRegistry.waitingFor(vSid)
      framesInWindow <- frames.get
      // 到点收殓（生产 = TtlTick 30s；spec 直驱）
      _ <- rt.engine.sweepDestroyWindows()
      afterW <- BgTaskRegistry.waitingFor(wSid)
      afterV <- BgTaskRegistry.waitingFor(vSid)
      after <- nodeById(rt, id)
      framesAfter <- frames.get
      eventsAfter <- readEvents(ws)
      _ <- BgTaskRegistry.unregisterSession(Some(wSid)).attempt.void
      _ <- BgTaskRegistry.unregisterSession(Some(vSid)).attempt.void
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // ④ 申报槽清理（此前 Loop 双会话终态不 remove）
      assertEquals(residueW, None, "the worker declaration slot must be cleared by destroyLoopSessions (④)")
      assertEquals(residueV, None, "the verify declaration slot must be cleared (④)")
      // ③/④ 窗口登记（双会话都在禁 spawn 表 + destroyAt 落库）
      assert(n.destroyAt.isDefined, "the loop terminal must register a destroy window")
      assert(banW.isDefined, "the worker session must be in the spawn-ban ledger")
      assert(banV.isDefined, "the verify session must be in the spawn-ban ledger")
      assert(eventsScheduled.exists(_.contains("\"node-destroy-scheduled\"")),
        s"node-destroy-scheduled expected for the loop terminal: ${eventsScheduled.mkString("|").take(400)}")
      // 窗口内两会话任务照跑（未被即时收割）
      assert(inWindowW.nonEmpty && inWindowV.nonEmpty, "inside the window both loop sessions keep their bg tasks")
      assertEquals(framesInWindow.count(j => j.hcursor.get[String]("type").contains("backgroundTaskUpdate")), 0,
        "no reclaim frame inside the window")
      // 到点：两个会话都被收殓
      assertEquals(afterW, Nil, "the sweep must reclaim the worker session's tasks")
      assertEquals(afterV, Nil, "the sweep must reclaim the verify session's tasks")
      assertEquals(after.flatMap(_.destroyAt), None, "destroyAt must be cleared after the sweep")
      val bgFrames = framesAfter.filter(j => j.hcursor.get[String]("type").contains("backgroundTaskUpdate"))
      assert(bgFrames.exists(j => j.hcursor.get[String]("sessionId").contains(wSid)),
        s"cancelled frame for the worker session expected: ${bgFrames.map(_.noSpaces.take(100)).mkString("|")}")
      assert(bgFrames.exists(j => j.hcursor.get[String]("sessionId").contains(vSid)),
        s"cancelled frame for the verify session expected: ${bgFrames.map(_.noSpaces.take(100)).mkString("|")}")
      assert(eventsAfter.exists(_.contains("\"node-destroyed\"")),
        s"node-destroyed audit expected: ${eventsAfter.mkString("|").take(400)}")
  }

  // ── ⑥ payload 序列化对照：非 loop 节点零变化 ─────────────

  test("⑥ payload: non-loop node carries no loop fields (field-set zero drifts)") {
    val ws = tempRoot / "ws-pl"
    os.makeDir.all(ws)
    val system = ActorSystem(s"loop-pl-${scala.util.Random.nextInt(100000)}")
    val llm = LoopLlm(_ => "ok")
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("loop-pl", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- nodeEdit(nodeInput("loop-pl", "plain", "description" -> Json.fromString("plain node"),
        "task" -> Json.fromString("plain-task"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- waitStatus(rt, "plain", Set(NodeLifecycle.Completed))
      pId <- idOf(rt, "plain")
      n <- nodeById(rt, pId).map(_.getOrElse(fail("plain must exist")))
      payload = NodePayload.buildNodeJson(n, System.currentTimeMillis())
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(payload.hcursor.downField("loop").focus.isEmpty, "non-loop node payload must not carry a loop field")
      assert(payload.hcursor.downField("loopRound").focus.isEmpty, "non-loop node payload must not carry loopRound")
      assert(payload.hcursor.downField("loopPhase").focus.isEmpty, "non-loop node payload must not carry loopPhase")
  }

end LoopNodeSpec
