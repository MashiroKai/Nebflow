package nebflow.core.processor

import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref}
import fs2.Stream
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, ActorSystem, Behavior, Behaviors}
import nebflow.agent.{AgentCommand, AgentEvent, AgentKind, AgentLibrary, AgentRecord, AgentStatus, SharedResources}
import nebflow.core.FileChangeTracker
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore, WsHub}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * stuck 自动恢复批 **P1** 验收 spec（2026-09-11，作者裁定 R-3）。
 *
 * 钉住判据序（设计 §2.1）的两条改造：
 *   - **正信号门（正控①）**：>600s 单工具调用 + 命令自带大额 `timeout` + 持续推进
 *     ⇒ 归**类② 假阳性**、**本拍零动作**（只写 `stuck-detected` 事件行）。对照改造前
 *     该形态必被轴① 判死（今日 11/11 样本即此形态）——**11/11 转 suspect 是预期
 *     行为、不是回归**。
 *   - **类④ 分流（负控③）**：`inflightFor(sid) > 0` ⇒ 类④ provider hang ⇒
 *     **不执行 L2 进程 kill / L3**（`destructiveAllowed=false` ⇒ 进程 kill 结构性
 *     收回；`recoverable=false` ⇒ 节点级 L3 腿跳过）。
 *   - **类① 非破坏档**：真卡死只允许 L1 halt + 恢复腿，`destructiveAllowed=false`。
 *
 * 另有 [[nebflow.agent.AgentCore.markToolProgress]] / `projectLoopCounters` 两个写入
 * 语义单点的纯函数钉子（正信号窗口边界、跨轮指纹投影确定性）。
 *
 * 与 `WatchdogSelfMonitorSpec`/`TaskStuckWatcherSpec` 的分工：后两者钉**既有**开火链
 * 与 R8 审计面（本批保持其断言零漂移，仅 R8-③ 的类④ 口径按新判据序更新并注明），
 * 本 spec 钉**新增**的判据序行为。两者共用同一 [[WatchdogEventLog]] 测试缝约定。
 */
class StuckJudgementOrderSpec extends CatsEffectSuite:

  private val watchdogLogTmp = os.temp.dir(prefix = "stuck-order-events")
  override def beforeAll(): Unit = WatchdogEventLog.setLogDirForTest(watchdogLogTmp.toNIO)
  override def afterAll(): Unit = WatchdogEventLog.resetLogDirForTest()

  private val threshold = 10 * 60 * 1000L

  private val fakeLlm = new LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("fake llm not expected here"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.raiseError[IO](new RuntimeException("fake llm not expected here"))

  private def mkRecordingActor(record: Ref[IO, List[AgentCommand]]): Behavior[AgentCommand] =
    def loop: Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand](cmd => record.update(_ :+ cmd).as(loop))
    loop

  private def mkRecordingEvt(record: Ref[IO, List[AgentEvent]]): Behavior[AgentEvent] =
    def loop: Behavior[AgentEvent] =
      Behaviors.receiveMessage[AgentEvent](evt => record.update(_ :+ evt).as(loop))
    loop

  private def mkResources(system: ActorSystem, tmp: os.Path): IO[SharedResources] =
    for
      dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
    yield SharedResources(
      llm = fakeLlm,
      dispatcher = dispatcher,
      sessionStore = SessionStore(os.temp.dir(), os.temp.dir()),
      projectRoot = os.pwd,
      thinkingConfigRef = thinkingRef,
      rateLimiter = rateLimiter,
      fileChangeTracker = tracker,
      contextWindow = 100_000,
      agentLibrary = new AgentLibrary(tmp / "agents"),
      taskStore = FileTaskStore,
      historyArchiver = HistoryArchiver.fileSystem(tmp / "archives"),
      fileLockManager = fileLocks,
      sessionModelOverrides = modelOverrides,
      providerRegistry = null,
      healthMonitor = ProviderHealthMonitor(null),
      actorSystem = system,
      voiceMutedRef = voiceMuted
    )

  /** 事件行读取（本 spec 自己的临时目录，按 type 过滤）。 */
  private def eventsOfType(tpe: String): IO[List[io.circe.Json]] =
    IO.blocking {
      val f = watchdogLogTmp / s"${java.time.LocalDate.now()}_events.jsonl"
      if !os.exists(f) then Nil
      else os.read.lines(f).toList.flatMap(l => io.circe.parser.parse(l).toOption)
    }.map(_.filter(_.hcursor.get[String]("type").toOption.contains(tpe)))

  // ── ① 判据序：正信号门 / 类④ 分流 / 类① 非破坏档（纯函数）──────────────

  /** 正控① 的输入形态：>600s 单工具调用 + 命令自带 `timeout=3000000ms` + 持续推进。 */
  private def toolPhaseRecord(sid: String, ref: ActorRef[AgentCommand], now: Long,
                              progressAgeMs: Option[Long], deadlineMs: Long = 3_000_000L): AgentRecord =
    AgentRecord(
      sessionId = sid,
      ref = ref,
      kind = AgentKind.Flow,
      rootSessionId = "root-1",
      startedAt = now - 30 * 60 * 1000L,
      status = AgentStatus.Processing,
      // 轴① 命中：agent 侧事件流已停滞 601s（工具执行期零 agent 侧事件，本次事故形态）
      lastActivityMs = now - threshold - 1000L,
      currentToolName = Some("Bash"),
      currentToolStartedAt = now - threshold - 1000L,
      currentToolDeadlineMs = deadlineMs,
      lastProgressSignalAt = progressAgeMs.fold(0L)(age => now - age)
    )

  test("正控①: >600s 单工具调用 + 大额 timeout + 持续推进 → 类② 假阳性（只阻止判死，本拍零动作）") {
    val system = ActorSystem("order-p1")
    for
      _ <- IO(system)
      tmp <- IO(os.temp.dir())
      ref <- system.spawn(mkRecordingActor(Ref.unsafe(Nil)), "p1-ref")
      now = System.currentTimeMillis()
      rec = toolPhaseRecord("node-p1", ref, now, progressAgeMs = Some(5_000L))
      a = TaskStuckWatcher.assessDetailed(rec, now).getOrElse(fail("判据必须命中（这是正控① 的前提）"))
      cls = TaskStuckWatcher.classify(rec, a, now, inflight = 0)
      _ <- IO(system.stopAll.attempt.void.unsafeRunSync())
    yield
      assertEquals(a.branch, TaskStuckWatcher.BranchAgentStale,
        "轴① 命中（工具执行期 agent 侧零事件）——正是改造前必判死的形态")
      assertEquals(cls.cls, TaskStuckWatcher.ClassFalsePositive, s"正信号在场 ⇒ 类②，得 ${cls.cls}: ${cls.note}")
      assert(!cls.recoverable, "类② 不进恢复链")
      assert(!cls.destructiveAllowed, "类② 不许破坏档")
  }

  test("正控① 对照臂: 同形态但**无**正信号 → 类① 真卡死（非破坏档，允许恢复腿）") {
    val system = ActorSystem("order-p1c")
    for
      _ <- IO(system)
      ref <- system.spawn(mkRecordingActor(Ref.unsafe(Nil)), "p1c-ref")
      now = System.currentTimeMillis()
      rec = toolPhaseRecord("node-p1c", ref, now, progressAgeMs = None)
      a = TaskStuckWatcher.assessDetailed(rec, now).getOrElse(fail("判据必须命中"))
      cls = TaskStuckWatcher.classify(rec, a, now, inflight = 0)
      _ <- IO(system.stopAll.attempt.void.unsafeRunSync())
    yield
      assertEquals(cls.cls, TaskStuckWatcher.ClassTrueStuck, s"无正信号 ⇒ 类①，得 ${cls.cls}: ${cls.note}")
      assert(cls.recoverable, "类① 允许进恢复链（终态化之前挂起并恢复）")
      assert(!cls.destructiveAllowed, "类① 非破坏档：禁 L2 进程 kill / L3 终态化（设计 §2.2 建议动作档）")
  }

  test("正控① 窗口边界: 正信号超过新鲜窗 ⇒ 不算正信号（回类①）") {
    val window = nebflow.shared.Defaults.StuckProgressSignalWindowMs
    val now = System.currentTimeMillis()
    val ref = null.asInstanceOf[ActorRef[AgentCommand]]
    val fresh = AgentRecord("s", ref, AgentKind.Flow, "r", lastProgressSignalAt = now - window + 1)
    val stale = AgentRecord("s", ref, AgentKind.Flow, "r", lastProgressSignalAt = now - window - 1)
    val never = AgentRecord("s", ref, AgentKind.Flow, "r", lastProgressSignalAt = 0L)
    assert(TaskStuckWatcher.hasProgressSignal(fresh, now), "窗内 ⇒ 有正信号")
    assert(!TaskStuckWatcher.hasProgressSignal(stale, now), "窗外 ⇒ 无正信号")
    assert(!TaskStuckWatcher.hasProgressSignal(never, now), "从未观测 ⇒ 无正信号")
  }

  test("负控③: inflightFor(sid) > 0 → 类④ provider hang（不执行 L2 进程 kill / L3）") {
    val system = ActorSystem("order-n3")
    for
      _ <- IO(system)
      ref <- system.spawn(mkRecordingActor(Ref.unsafe(Nil)), "n3-ref")
      now = System.currentTimeMillis()
      rec = AgentRecord(
        sessionId = "node-n3",
        ref = ref,
        kind = AgentKind.Flow,
        rootSessionId = "root-1",
        startedAt = now - 30 * 60 * 1000L,
        status = AgentStatus.Processing,
        lastActivityMs = now - threshold - 1000L // 轴① 命中
      )
      a = TaskStuckWatcher.assessDetailed(rec, now).getOrElse(fail("判据必须命中"))
      cls = TaskStuckWatcher.classify(rec, a, now, inflight = 1)
      _ <- IO(system.stopAll.attempt.void.unsafeRunSync())
    yield
      assertEquals(cls.cls, TaskStuckWatcher.ClassProviderHang, s"inflight>0 ⇒ 类④，得 ${cls.cls}: ${cls.note}")
      assert(!cls.destructiveAllowed, "类④ 不许 L2 进程 kill（负控③）")
      assert(!cls.recoverable, "类④ 不进节点级恢复腿 ⇒ L3 被结构性跳过（负控③）")
  }

  test("判据序优先级: inflight 优先于工具相位（类④ 先命中）") {
    val system = ActorSystem("order-prio")
    for
      _ <- IO(system)
      ref <- system.spawn(mkRecordingActor(Ref.unsafe(Nil)), "prio-ref")
      now = System.currentTimeMillis()
      // 同时具备「工具相位 + 正信号」（本该类②）与「有在飞 LLM」（类④）
      rec = toolPhaseRecord("node-prio", ref, now, progressAgeMs = Some(1_000L))
      a = TaskStuckWatcher.assessDetailed(rec, now).getOrElse(fail("判据必须命中"))
      cls = TaskStuckWatcher.classify(rec, a, now, inflight = 2)
      _ <- IO(system.stopAll.attempt.void.unsafeRunSync())
    yield assertEquals(cls.cls, TaskStuckWatcher.ClassProviderHang,
      "设计 §2.1 判定顺序：inflightFor 是第一档，先于工具相位分支")
  }

  test("LlmInterface.inflightFor 是**只读**：不触发 halt/abort，且 per-session 计数精确") {
    for
      (k1, h1) <- nebflow.llm.LlmInterface.registerInflight(Some("readonly-a"))
      (k2, _) <- nebflow.llm.LlmInterface.registerInflight(Some("readonly-a"))
      (k3, _) <- nebflow.llm.LlmInterface.registerInflight(Some("readonly-b"))
      n1 <- nebflow.llm.LlmInterface.inflightFor("readonly-a")
      n2 <- nebflow.llm.LlmInterface.inflightFor("readonly-b")
      n0 <- nebflow.llm.LlmInterface.inflightFor("readonly-none")
      notAborted <- h1.tryGet
      _ <- nebflow.llm.LlmInterface.cancelAllInflight() // 清场（全局 Ref）
    yield
      assertEquals(n1, 2, "per-session 计数必须精确（不是全局 inflightCount）")
      assertEquals(n2, 1)
      assertEquals(n0, 0)
      assertEquals(notAborted, None, "inflightFor 必须零副作用（不 complete halt Deferred）")
  }

  // ── ② 扫描面：正控① 零动作 / 负控③ 不执行 L3 ───────────────────────────

  test("正控① 扫描面: 类② 本拍零动作——0 WS 帧 / 0 AgentCommand / 0 AgentEvent，只写 stuck-detected 行") {
    val system = ActorSystem("order-scan-p1")
    for
      _ <- IO(system)
      tmp <- IO(os.temp.dir())
      resources <- mkResources(system, tmp)
      agentReceived <- Ref.of[IO, List[AgentCommand]](Nil)
      agentRef <- system.spawn(mkRecordingActor(agentReceived), "p1-agent")
      bridgeReceived <- Ref.of[IO, List[AgentEvent]](Nil)
      bridgeRef <- system.spawn(mkRecordingEvt(bridgeReceived), "p1-bridge")
      wsHub = new WsHub()
      receivedWs <- Ref.of[IO, List[io.circe.Json]](Nil)
      _ <- wsHub.register(json => receivedWs.update(_ :+ json))
      now = System.currentTimeMillis()
      rec = toolPhaseRecord("node-scanp1", agentRef, now, progressAgeMs = Some(3_000L))
        .copy(supervisorRef = Some(bridgeRef))
      _ <- resources.agentRegistry.set(Map(rec.sessionId -> rec))
      stopCounts <- Ref.of[IO, Map[String, Int]](Map.empty)
      pendingL3 <- Ref.of[IO, List[TaskStuckWatcher.PendingL3]](Nil)
      // 三拍都命中判据（工具相位未超声明授权期），三拍都该是类② 零动作
      _ <- TaskStuckWatcher.scan(resources, wsHub, threshold, stopCounts, pendingL3)
      _ <- TaskStuckWatcher.scan(resources, wsHub, threshold, stopCounts, pendingL3)
      _ <- TaskStuckWatcher.scan(resources, wsHub, threshold, stopCounts, pendingL3)
      _ <- IO.sleep(200.millis)
      ws <- receivedWs.get
      cmds <- agentReceived.get
      evts <- bridgeReceived.get
      detected <- eventsOfType(TaskStuckWatcher.StuckDetectedType)
      fires <- eventsOfType(WatchdogEventLog.StuckFireType)
      counts <- stopCounts.get
      pend <- pendingL3.get
      _ <- IO(system.stopAll.attempt.void.unsafeRunSync())
    yield
      assertEquals(ws, Nil, "类② 不得广播 taskStuck（零动作）")
      assertEquals(cmds, Nil, "类② 不得发任何 AgentCommand")
      assertEquals(evts, Nil, "类② 不得发任何 AgentEvent（含桥 Cancelled）")
      assertEquals(fires, Nil, "类② 不产生 stuck-fire（检出面 ≠ 开火面）")
      assertEquals(pend, Nil, "类② 不得登记 L3 待复查")
      assertEquals(counts.getOrElse("node-scanp1", 0), 0, "类② 不得消耗升级阶梯计数")
      val mine = detected.filter(_.hcursor.get[String]("sessionId").toOption.contains("node-scanp1"))
      assertEquals(mine.size, 3, s"三拍各写一条 stuck-detected，得 ${mine.size}")
      assertEquals(mine.head.hcursor.get[String]("class").toOption, Some(TaskStuckWatcher.ClassFalsePositive))
      assertEquals(mine.head.hcursor.get[String]("branch").toOption, Some(TaskStuckWatcher.BranchAgentStale))
      assertEquals(mine.head.hcursor.get[Boolean]("recoverable").toOption, Some(false))
      assertEquals(mine.head.hcursor.get[Boolean]("destructiveAllowed").toOption, Some(false))
  }

  test("负控③ 扫描面: 有在飞 LLM 请求 ⇒ 类④ 三拍均不达 L3（0 桥 Cancelled / 0 L3 待复查登记）") {
    val system = ActorSystem("order-scan-n3")
    for
      _ <- IO(system)
      tmp <- IO(os.temp.dir())
      resources <- mkResources(system, tmp)
      agentReceived <- Ref.of[IO, List[AgentCommand]](Nil)
      agentRef <- system.spawn(mkRecordingActor(agentReceived), "n3-agent")
      bridgeReceived <- Ref.of[IO, List[AgentEvent]](Nil)
      bridgeRef <- system.spawn(mkRecordingEvt(bridgeReceived), "n3-bridge")
      wsHub = new WsHub()
      now = System.currentTimeMillis()
      sid = "node-scann3"
      rec = AgentRecord(
        sessionId = sid, ref = agentRef, kind = AgentKind.Flow, rootSessionId = "root-n3",
        startedAt = now - 30 * 60 * 1000L, status = AgentStatus.Processing,
        lastActivityMs = now - threshold - 1000L, supervisorRef = Some(bridgeRef))
      _ <- resources.agentRegistry.set(Map(sid -> rec))
      (_, _) <- nebflow.llm.LlmInterface.registerInflight(Some(sid))
      stopCounts <- Ref.of[IO, Map[String, Int]](Map.empty)
      pendingL3 <- Ref.of[IO, List[TaskStuckWatcher.PendingL3]](Nil)
      _ <- TaskStuckWatcher.scan(resources, wsHub, threshold, stopCounts, pendingL3)
      _ <- TaskStuckWatcher.scan(resources, wsHub, threshold, stopCounts, pendingL3)
      _ <- TaskStuckWatcher.scan(resources, wsHub, threshold, stopCounts, pendingL3)
      _ <- IO.sleep(200.millis)
      evts <- bridgeReceived.get
      pend <- pendingL3.get
      detected <- eventsOfType(TaskStuckWatcher.StuckDetectedType)
      _ <- nebflow.llm.LlmInterface.cancelAllInflight()
      _ <- IO(system.stopAll.attempt.void.unsafeRunSync())
    yield
      assert(!evts.exists(_.isInstanceOf[AgentEvent.Cancelled]),
        s"类④ 不得走节点级 L3 腿（负控③「不执行 L3」），得 $evts")
      assertEquals(pend, Nil, "类④ 不得登记 L3 待复查（节点级腿未开火）")
      val mine = detected.filter(_.hcursor.get[String]("sessionId").toOption.contains(sid))
      assertEquals(mine.size, 3, s"三拍各留一条 stuck-detected，得 ${mine.size}")
      assert(mine.forall(_.hcursor.get[String]("class").toOption.contains(TaskStuckWatcher.ClassProviderHang)),
        s"三拍均须判为类④，得 ${mine.map(_.hcursor.get[String]("class").toOption)}")
      assert(mine.forall(_.hcursor.get[Boolean]("destructiveAllowed").toOption.contains(false)),
        "类④ 三拍均不得允许破坏档（进程 kill 结构性收回）")
  }

  // ── ③ 写入语义单点：正信号 / 跨轮指纹投影 ───────────────────────────────

  test("AgentCore.markToolProgress: 只推进、不回退（写入语义单点）") {
    val ref = null.asInstanceOf[ActorRef[AgentCommand]]
    val base = AgentRecord("s", ref, AgentKind.Flow, "r", lastProgressSignalAt = 500L)
    val advanced = nebflow.agent.AgentCore.markToolProgress(base, 900L)
    val regressed = nebflow.agent.AgentCore.markToolProgress(base, 100L)
    val untouched = nebflow.agent.AgentCore.markToolProgress(base, 500L)
    assertEquals(advanced.lastProgressSignalAt, 900L)
    assertEquals(regressed.lastProgressSignalAt, 500L, "时间戳不得回退（幂等/单调）")
    assertEquals(untouched.lastProgressSignalAt, 500L)
    assertEquals(base.processActivityMs, 0L, "必须不触碰 processActivityMs（两者语义分家）")
  }

  test("AgentCore.projectLoopCounters: 跨轮指纹投影确定性（次数降序、同数取字典序）") {
    import nebflow.core.processor.LoopGuard.Counters
    val empty = AgentCoreProjection.of(Counters())
    assertEquals(empty, (0, ""), "空 counters ⇒ (0, \"\")")
    val c = Counters(crossTurn = Map("bbb" -> Set("t1", "t2"), "aaa" -> Set("t1"), "ccc" -> Set("t1", "t2")))
    val (n, fp) = AgentCoreProjection.of(c)
    assertEquals(n, 5, "loopStrikeCount = crossTurn 各 fp 失败 turn 数之和")
    assertEquals(fp, "bbb", "同次数（2）取字典序最小")
    // 确定性：同一输入重复投影结果恒定（不受 Map 迭代序影响）
    assertEquals(AgentCoreProjection.of(c), (n, fp))
  }

  /** 薄包装：把 `AgentCore.projectLoopCounters` 提到测试可读处（避免重复 import 长链）。 */
  private object AgentCoreProjection:
    def of(c: nebflow.core.processor.LoopGuard.Counters): (Int, String) =
      nebflow.agent.AgentCore.projectLoopCounters(c)

end StuckJudgementOrderSpec
