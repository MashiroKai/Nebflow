package nebflow.core.processor

import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref}
import fs2.Stream
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, ActorSystem, Behavior, Behaviors}
import nebflow.agent.{AgentCommand, AgentEvent, AgentKind, AgentLibrary, AgentRecord, AgentStatus, SharedResources}
import nebflow.core.FileChangeTracker
import nebflow.core.compact.HistoryArchiver
import nebflow.core.project.{FlowMapStore, NodeDef, NodeEngine, NodeLifecycle, OutEdge, ProjectDef, ProjectRuntime, ProjectRuntimeRegistry}
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore, WsHub}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{ContentBlock, Defaults, FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, Message, MessageRole, StreamChunk}

import scala.concurrent.duration.*

/**
 * stuck 自动恢复批 **P3** 验收 spec（2026-09-11，作者裁定 R-2 / §3.3 / §3.4 / R-6）。
 *
 * 钉住 P3 的五件结构性改动：
 *   1. **R-2 常量**（`Defaults.StuckRecovery*`）数值与命名逐字 = 设计 §3.3 建议值；
 *   2. **§3.4 判定序**（互斥点 1 / 互斥点 2 / 冷却窗 / 全链预算）= 纯函数可独立断言；
 *   3. **恢复预算消费点** = 尝试时（不是成功后）——「禁无条件重试」；
 *   4. **互斥点 1(a)(b)**：冻结会话拒绝 resume（不得经 resetCrossTurn 绕过 365 天
 *      冻结）；会话级原语只在 `Processing` 时执行；
 *   5. **R-2 transcript 重放封顶 40 条** + 截断告知（token 放大面的结构性封顶）。
 *
 * 全部隔离：临时目录 + 独立 ActorSystem + `WatchdogEventLog` 重定向（不写真实
 * `~/.nebflow`）；零 LLM、零网络、零进程。
 */
class StuckRecoveryBudgetSpec extends CatsEffectSuite:

  private val watchdogLogTmp = os.temp.dir(prefix = "stuck-budget-events")
  override def beforeAll(): Unit = WatchdogEventLog.setLogDirForTest(watchdogLogTmp.toNIO)
  override def afterAll(): Unit =
    WatchdogEventLog.resetLogDirForTest()
    sys.props.remove("nebflow.stuck.suspendWaitMs")

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

  private def mountProject(name: String, ws: os.Path, system: ActorSystem, res: SharedResources,
                           rootSid: String): IO[ProjectRuntime] =
    for
      store <- FlowMapStore.open(name, ws.toString)
      engine = new NodeEngine(
        store, system, res,
        wsSendFn = (_: Json) => IO.unit,
        workspace = ws.toString,
        rootSessionId = rootSid,
        projectName = name,
        emitEvent = (_: String, _: String, _: Json) => IO.unit,
        notifyTriggerOverride = Some((_: String) => IO.unit)
      )
      pd = ProjectDef(name = name, workspace = ws.toString, agentFile = (ws / "AGENTS.md").toString,
        createdAt = System.currentTimeMillis())
      rt = ProjectRuntime(pd, store, engine, system, res, None)
      _ <- ProjectRuntimeRegistry.register(rt)
    yield rt

  private def flowRecord(sid: String, ref: ActorRef[AgentCommand], rootSid: String,
                         bridge: Option[ActorRef[AgentEvent]],
                         frozenReason: Option[String] = None,
                         status: AgentStatus = AgentStatus.Processing,
                         strikes: Int = 0,
                         fp: String = ""): AgentRecord =
    AgentRecord(sessionId = sid, ref = ref, kind = AgentKind.Flow, rootSessionId = rootSid,
      startedAt = System.currentTimeMillis() - 30 * 60 * 1000L, status = status,
      lastActivityMs = System.currentTimeMillis() - threshold - 1000L,
      supervisorRef = bridge, currentToolStartedAt = 0L,
      frozenReason = frozenReason, loopStrikeCount = strikes, lastLoopFp = fp)

  // ══ R-2：五个常量（命名 + 数值逐字 = 设计 §3.3 建议值）════════════════════

  test("R-2 常量: StuckRecoveryMaxPerGen=1 / MaxPerChain=2 / BackoffMs=[30s,120s,600s] / CooldownMs=20min / ReplayMaxMsgs=40") {
    assertEquals(Defaults.StuckRecoveryMaxPerGen, 1, "单代次恢复预算 = 1（§3.3 建议值）")
    assertEquals(Defaults.StuckRecoveryMaxPerChain, 2, "全链恢复预算 = 2（§3.3 建议值）")
    assertEquals(Defaults.StuckRecoveryBackoffMs, List(30_000L, 120_000L, 600_000L), "退避曲线 30s→120s→600s")
    assertEquals(Defaults.StuckRecoveryCooldownMs, 1_200_000L, "恢复后冷却 = 20min = 2×StuckThresholdMs")
    assertEquals(Defaults.StuckRecoveryReplayMaxMsgs, 40, "transcript 重放封顶 = 40 条")
    assertEquals(Defaults.StuckRecoveryLoopDetectMs, 60_000L, "互斥点 2 检测窗 = 60s = 扫描周期×2")
  }

  test("R-2 退避查询口径: 端点夹取（≤0 与超界都取端点，不增长、不越界）") {
    assertEquals(Defaults.stuckRecoveryBackoffMs(1), 30_000L)
    assertEquals(Defaults.stuckRecoveryBackoffMs(2), 120_000L)
    assertEquals(Defaults.stuckRecoveryBackoffMs(3), 600_000L)
    assertEquals(Defaults.stuckRecoveryBackoffMs(4), 600_000L, "超界取末项 = 上限，不再增长")
    assertEquals(Defaults.stuckRecoveryBackoffMs(0), 30_000L, "≤0 取首项")
    assertEquals(Defaults.stuckRecoveryBackoffMs(-5), 30_000L)
  }

  // ══ §3.4 判定序（纯函数逐条）══════════════════════════════════════════════

  private def rec(sid: String = "s", frozen: Option[String] = None,
                  status: AgentStatus = AgentStatus.Processing,
                  strikes: Int = 0, fp: String = ""): AgentRecord =
    AgentRecord(sessionId = sid, ref = null, kind = AgentKind.Flow, rootSessionId = "r",
      status = status, frozenReason = frozen, loopStrikeCount = strikes, lastLoopFp = fp)

  test("§3.4 判定序 1: LoopGuard 冻结记录 ⇒ 互斥点 1（零恢复动作 + 一次上报）") {
    val g = TaskStuckWatcher.recoveryGate(
      rec(frozen = Some(TaskStuckWatcher.LoopFreezeReasonWire)), TaskStuckWatcher.RecoveryLedger(), 0L)
    assertEquals(g.map(_.kind), Some(TaskStuckWatcher.GateLoopFrozen), "365 天冻结不得被自动恢复绕过")
    assertEquals(g.map(_.report), Some(true), "第 2 步：只广播 + 一次上报")
    // status=Frozen（未带 reason）同样命中——两条判据任一成立即可
    val g2 = TaskStuckWatcher.recoveryGate(rec(status = AgentStatus.Frozen), TaskStuckWatcher.RecoveryLedger(), 0L)
    assertEquals(g2.map(_.kind), Some(TaskStuckWatcher.GateLoopFrozen))
  }

  test("§3.4 判定序 2: 互斥点 2（恢复后 60s 内指纹再命中）⇒ chainHalted，零动作 + 一次上报") {
    val now = 1_000_000L
    val led = TaskStuckWatcher.RecoveryLedger(
      chainAttempts = 1, lastRecoveryAt = now - 10_000L, strikeBaseline = 3, fpBaseline = "fpA")
    // 恢复后 10s，跨轮命中计数由 3 → 4 ⇒ 「反复卡」识别
    assert(TaskStuckWatcher.loopDetectedAfterRecovery(rec(strikes = 4, fp = "fpA"), led, now),
      "计数上升 = 恢复后 LoopGuard 再次命中")
    // 同样条件但计数未上升、指纹未变 ⇒ 不误报
    assert(!TaskStuckWatcher.loopDetectedAfterRecovery(rec(strikes = 3, fp = "fpA"), led, now),
      "计数与指纹均未变 ⇒ 不触发互斥点 2（防误报）")
    // 出了 60s 窗 ⇒ 不再归因于「恢复后立刻」
    assert(!TaskStuckWatcher.loopDetectedAfterRecovery(rec(strikes = 9, fp = "fpZ"), led, now + 61_000L),
      "超出检测窗 ⇒ 不触发")
    // 命中后闸门 = recovery-loop-detected（report = true）
    val g = TaskStuckWatcher.recoveryGate(rec(strikes = 4), led.copy(chainHalted = true), now)
    assertEquals(g.map(_.kind), Some(TaskStuckWatcher.GateRecoveryLoopDetected))
    assertEquals(g.map(_.report), Some(true))
  }

  test("§3.4 判定序 3: 冷却窗内 ⇒ 零动作、**不**上报（防自激、防误报）") {
    val now = 2_000_000L
    val led = TaskStuckWatcher.RecoveryLedger(chainAttempts = 1, lastRecoveryAt = now - 60_000L)
    val g = TaskStuckWatcher.recoveryGate(rec(), led, now)
    assertEquals(g.map(_.kind), Some(TaskStuckWatcher.GateCooldown))
    assertEquals(g.map(_.report), Some(false), "冷却窗只留痕，不上报（会话可能自己缓过来）")
    // 冷却到期（20min 后）⇒ 放行
    assertEquals(TaskStuckWatcher.recoveryGate(rec(), led, now + Defaults.StuckRecoveryCooldownMs),
      None, "冷却窗到期 ⇒ 判定序放行")
  }

  test("§3.4 判定序 4: 全链预算耗尽 ⇒ 零动作 + 一次上报") {
    val led = TaskStuckWatcher.RecoveryLedger(chainAttempts = Defaults.StuckRecoveryMaxPerChain)
    val g = TaskStuckWatcher.recoveryGate(rec(), led, 0L)
    assertEquals(g.map(_.kind), Some(TaskStuckWatcher.GateBudgetExhausted))
    assertEquals(g.map(_.report), Some(true))
    // 预算未耗尽（chain=1 < 2）⇒ 放行
    assertEquals(TaskStuckWatcher.recoveryGate(rec(), TaskStuckWatcher.RecoveryLedger(chainAttempts = 1), 0L), None)
  }

  test("§3.4 判定序 0（负控）: 默认空账本 + 无冻结 ⇒ 全部放行（既有路径逐字不变）") {
    assertEquals(TaskStuckWatcher.recoveryGate(rec(), TaskStuckWatcher.RecoveryLedger(), 0L), None,
      "空账本 ⇒ 闸门不发声（首轮 / 既有测试行为逐字等价）")
  }

  // ══ 互斥点 1(a)(b)：纯判据 ═════════════════════════════════════════════════

  test("互斥点 1(a): 冻结会话拒绝 resume；非冻结 / 已不在 registry ⇒ 放行") {
    assert(NodeEngine.frozenSessionBlocksResume(Some(rec(frozen = Some("loop")))),
      "带 LoopGuard 冻结记录 ⇒ 拒绝（不得经 resetCrossTurn 绕过 365 天冻结）")
    assert(NodeEngine.frozenSessionBlocksResume(Some(rec(status = AgentStatus.Frozen))),
      "status=Frozen ⇒ 拒绝")
    assert(NodeEngine.frozenSessionBlocksResume(Some(rec(frozen = Some("llm-transient")))),
      "任意冻结记录都拒绝（本批不区分冻结族——统一走人工恢复出口）")
    assert(!NodeEngine.frozenSessionBlocksResume(Some(rec())), "Processing 无冻结 ⇒ 放行")
    assert(!NodeEngine.frozenSessionBlocksResume(None), "会话已不在 registry ⇒ 不阻止（起的是全新会话）")
  }

  test("互斥点 1(b): 会话级原语（transport abort / reclaim）只在 status==Processing 时允许") {
    assert(NodeEngine.sessionLevelPrimitiveAllowed(rec(status = AgentStatus.Processing)))
    assert(!NodeEngine.sessionLevelPrimitiveAllowed(rec(status = AgentStatus.Frozen)),
      "冻结态 ⇒ 会话级原语不得执行")
    assert(!NodeEngine.sessionLevelPrimitiveAllowed(rec(status = AgentStatus.Idle)))
    assert(!NodeEngine.sessionLevelPrimitiveAllowed(rec(status = AgentStatus.WaitingForUser)))
  }

  // ══ R-2：transcript 重放封顶 ═══════════════════════════════════════════════

  private def userMsg(i: Int): Message = Message(role = MessageRole.User, content = Left(s"m$i"))
  private def toolResultMsg(i: Int): Message =
    Message(role = MessageRole.User,
      content = Right(List(ContentBlock.ToolResult(s"t$i", s"out$i"))))

  test("R-2 重放封顶: ≤max 原样返回（不截断、不声明）；>max 取最近 max 条并标记截断") {
    val small = List.tabulate(10)(userMsg)
    assertEquals(NodeEngine.capReplayMessages(small, 40), (small, false), "≤max ⇒ 原样 + 未截断")
    val big = List.tabulate(100)(userMsg)
    val (capped, truncated) = NodeEngine.capReplayMessages(big, 40)
    assertEquals(capped.size, 40)
    assert(truncated)
    assertEquals(capped.head.content, Left("m60"), "保留**最近**的（越近越相关）")
    assertEquals(capped.last.content, Left("m99"))
  }

  test("R-2 重放封顶: 截断产生的**悬空 tool_result 头**必须一起丢掉（provider 侧会拒）") {
    // 尾部 40 条以 tool_result 开头 ⇒ 其配对的 assistant tool_use 已被截掉
    val msgs = List.tabulate(50)(userMsg) ++ List(toolResultMsg(1), toolResultMsg(2), userMsg(3))
    val (capped, truncated) = NodeEngine.capReplayMessages(msgs, 3)
    assert(truncated)
    assertEquals(capped.size, 1, "两条悬空 tool_result 头被丢")
    assertEquals(capped.head.content, Left("m3"), "剩下的第一条是正常消息（悬空头之后）")
  }

  test("R-2 重放封顶: 全悬空（极端）⇒ 退化保留原尾（宁多勿缺，不返回空上下文）") {
    val msgs = List(userMsg(0)) ++ List(toolResultMsg(1), toolResultMsg(2))
    val (capped, truncated) = NodeEngine.capReplayMessages(msgs, 2)
    assert(truncated)
    assertEquals(capped.size, 2, "全悬空不返回空列表")
  }

  test("R-2 截断告知: replayCapNote 含总数与保留数（不告知 ⇒ 模型会假定上下文完整）") {
    val note = NodeEngine.replayCapNote(250, 40)
    assert(note.contains("250") && note.contains("40"), s"必须声明截断事实，得：$note")
  }

  // ══ 端到端：判定序闸门确实让扫描面**零动作**（互斥点 1）══════════════════════

  test("端到端 负控: 冻结会话被扫描命中 ⇒ 零恢复动作（不发桥 Cancelled、不拉挂起腿）") {
    val system = ActorSystem("budget-gate")
    val sid = "node-gated"
    for
      _ <- IO(system)
      tmp <- IO(os.temp.dir())
      res <- mkResources(system, tmp)
      _ <- ProjectRuntimeRegistry.clear
      ws = tmp / "ws-gated"; _ <- IO(os.makeDir.all(ws))
      rt <- mountProject("gated", ws, system, res, "root-gated")
      _ <- rt.store.mutate(s => s.copy(nodes = s.nodes + ("n-gated" -> NodeDef(
        id = "n-gated", name = "n-gated", agent = "general", status = NodeLifecycle.Running,
        sessionRef = Some(sid), startedAt = Some(System.currentTimeMillis() - 60_000L),
        out = List(OutEdge.nebula), createdAt = System.currentTimeMillis() - 60_000L)))).void
      bridgeReceived <- Ref.of[IO, List[AgentEvent]](Nil)
      bridgeRef <- system.spawn(mkRecordingEvt(bridgeReceived), "gated-bridge")
      agentRef <- system.spawn(mkRecordingActor(Ref.unsafe(Nil)), "gated-agent")
      // 判定序第 2 步命中：会话带 LoopGuard 冻结记录
      _ <- res.agentRegistry.set(Map(sid -> flowRecord(sid, agentRef, "root-gated", Some(bridgeRef),
        frozenReason = Some(TaskStuckWatcher.LoopFreezeReasonWire))))
      stopCounts <- Ref.of[IO, Map[String, Int]](Map.empty)
      pendingL3 <- Ref.of[IO, List[TaskStuckWatcher.PendingL3]](Nil)
      ledger <- Ref.of[IO, Map[String, TaskStuckWatcher.RecoveryLedger]](Map.empty)
      _ <- TaskStuckWatcher.scan(res, new WsHub(), threshold, stopCounts, pendingL3, ledger = ledger)
      _ <- TaskStuckWatcher.scan(res, new WsHub(), threshold, stopCounts, pendingL3, ledger = ledger)
      _ <- TaskStuckWatcher.scan(res, new WsHub(), threshold, stopCounts, pendingL3, ledger = ledger)
      _ <- IO.sleep(500.millis)
      bridgeEvts <- bridgeReceived.get
      nodeAfter <- rt.store.getNode("n-gated")
      counts <- stopCounts.get
      led <- ledger.get
      _ <- IO(system.stopAll.attempt.void.unsafeRunSync())
      _ <- ProjectRuntimeRegistry.clear
    yield
      assert(bridgeEvts.isEmpty,
        s"互斥点 1 ⇒ 零**恢复**动作（挂起腿不启动、不发桥信号），得 $bridgeEvts")
      // 「零恢复动作」≠「无事发生」：设计 §3.4 第 2 步是「只广播 + 一次上报」——
      // 本批的「一次上报」= R-1=B 形态的 failNode（诚实失败，可经 NodeEdit 重激活）。
      assertEquals(nodeAfter.map(_.status), Some(NodeLifecycle.Failed),
        "互斥点 1 的宽松档：零恢复动作 + 恰好一次上报（failNode）")
      assertEquals(counts.getOrElse(sid, 0), 0, "零动作 ⇒ stopCounts 都不该增长（不进入升级链）")
      assert(led.get(sid).exists(_.reportedAt != 0L), "宽松档必须留下「一次上报」的账本痕迹")
  }

  test("端到端 正控: 冷却窗内的扫描 ⇒ 零动作且**不**上报（账本 reportedAt 保持 0）") {
    val system = ActorSystem("budget-cooldown")
    val sid = "node-cool"
    for
      _ <- IO(system)
      tmp <- IO(os.temp.dir())
      res <- mkResources(system, tmp)
      _ <- ProjectRuntimeRegistry.clear
      ws = tmp / "ws-cool"; _ <- IO(os.makeDir.all(ws))
      rt <- mountProject("cool", ws, system, res, "root-cool")
      _ <- rt.store.mutate(s => s.copy(nodes = s.nodes + ("n-cool" -> NodeDef(
        id = "n-cool", name = "n-cool", agent = "general", status = NodeLifecycle.Running,
        sessionRef = Some(sid), startedAt = Some(System.currentTimeMillis() - 60_000L),
        out = List(OutEdge.nebula), createdAt = System.currentTimeMillis() - 60_000L)))).void
      bridgeReceived <- Ref.of[IO, List[AgentEvent]](Nil)
      bridgeRef <- system.spawn(mkRecordingEvt(bridgeReceived), "cool-bridge")
      agentRef <- system.spawn(mkRecordingActor(Ref.unsafe(Nil)), "cool-agent")
      _ <- res.agentRegistry.set(Map(sid -> flowRecord(sid, agentRef, "root-cool", Some(bridgeRef))))
      stopCounts <- Ref.of[IO, Map[String, Int]](Map.empty)
      pendingL3 <- Ref.of[IO, List[TaskStuckWatcher.PendingL3]](Nil)
      // 预置：本会话 1 分钟前刚**成功恢复**过 ⇒ 落在 20min 冷却窗内
      ledger <- Ref.of[IO, Map[String, TaskStuckWatcher.RecoveryLedger]](Map(
        sid -> TaskStuckWatcher.RecoveryLedger(chainAttempts = 1, lastRecoveryAt = System.currentTimeMillis() - 60_000L)))
      _ <- TaskStuckWatcher.scan(res, new WsHub(), threshold, stopCounts, pendingL3, ledger = ledger)
      _ <- TaskStuckWatcher.scan(res, new WsHub(), threshold, stopCounts, pendingL3, ledger = ledger)
      _ <- IO.sleep(400.millis)
      bridgeEvts <- bridgeReceived.get
      nodeAfter <- rt.store.getNode("n-cool")
      counts <- stopCounts.get
      led <- ledger.get
      _ <- IO(system.stopAll.attempt.void.unsafeRunSync())
      _ <- ProjectRuntimeRegistry.clear
    yield
      assert(bridgeEvts.isEmpty, s"冷却窗 ⇒ 零动作（不发桥信号），得 $bridgeEvts")
      assertEquals(nodeAfter.map(_.status), Some(NodeLifecycle.Running), "冷却窗不得终态化节点")
      assertEquals(counts.getOrElse(sid, 0), 0, "冷却窗零动作 ⇒ stopCounts 不增长")
      assertEquals(led.get(sid).map(_.reportedAt), Some(0L), "冷却闸门**不**上报（防误报）")
      assertEquals(led.get(sid).map(_.chainAttempts), Some(1), "冷却窗不消耗预算")
  }

  test("预算消费: L3 开火 ⇒ 账本 gen/chain 各 +1（**尝试时**消费，不是成功后——否则失败路径无限重试）") {
    val system = ActorSystem("budget-consume")
    val sid = "node-consume"
    sys.props("nebflow.stuck.suspendWaitMs") = "300" // 缩短有界等待，避免 15s 空转
    for
      _ <- IO(system)
      tmp <- IO(os.temp.dir())
      res <- mkResources(system, tmp)
      _ <- ProjectRuntimeRegistry.clear
      ws = tmp / "ws-consume"; _ <- IO(os.makeDir.all(ws))
      rt <- mountProject("consume", ws, system, res, "root-consume")
      _ <- rt.store.mutate(s => s.copy(nodes = s.nodes + ("n-consume" -> NodeDef(
        id = "n-consume", name = "n-consume", agent = "general", status = NodeLifecycle.Running,
        sessionRef = Some(sid), startedAt = Some(System.currentTimeMillis() - 60_000L),
        out = List(OutEdge.nebula), createdAt = System.currentTimeMillis() - 60_000L)))).void
      _ <- res.sessionStore.saveMessagesForSession(sid, List(
        Message(role = MessageRole.User, content = Left("fixture transcript"))))
      bridgeReceived <- Ref.of[IO, List[AgentEvent]](Nil)
      bridgeRef <- system.spawn(mkRecordingEvt(bridgeReceived), "consume-bridge")
      agentRef <- system.spawn(mkRecordingActor(Ref.unsafe(Nil)), "consume-agent")
      _ <- res.agentRegistry.set(Map(sid -> flowRecord(sid, agentRef, "root-consume", Some(bridgeRef))))
      stopCounts <- Ref.of[IO, Map[String, Int]](Map.empty)
      pendingL3 <- Ref.of[IO, List[TaskStuckWatcher.PendingL3]](Nil)
      ledger <- Ref.of[IO, Map[String, TaskStuckWatcher.RecoveryLedger]](Map.empty)
      _ <- TaskStuckWatcher.scan(res, new WsHub(), threshold, stopCounts, pendingL3, ledger = ledger) // L1
      _ <- TaskStuckWatcher.scan(res, new WsHub(), threshold, stopCounts, pendingL3, ledger = ledger) // L2
      _ <- TaskStuckWatcher.scan(res, new WsHub(), threshold, stopCounts, pendingL3, ledger = ledger) // L3
      _ <- IO.sleep(700.millis)
      led <- ledger.get
      _ <- IO(system.stopAll.attempt.void.unsafeRunSync())
      _ <- ProjectRuntimeRegistry.clear
    yield
      assertEquals(led.get(sid).map(_.genAttempts), Some(1), "本代次预算在**尝试**时消费（默认 1 次）")
      assertEquals(led.get(sid).map(_.chainAttempts), Some(1), "全链预算同步消费（默认上限 2）")
  }

  test("预算闸门 端到端: 全链预算已耗尽 ⇒ 下一次扫描零动作 + 恰好一次上报（节点 failed，不再重试）") {
    val system = ActorSystem("budget-exhausted")
    val sid = "node-exhausted"
    for
      _ <- IO(system)
      tmp <- IO(os.temp.dir())
      res <- mkResources(system, tmp)
      _ <- ProjectRuntimeRegistry.clear
      ws = tmp / "ws-exh"; _ <- IO(os.makeDir.all(ws))
      rt <- mountProject("exh", ws, system, res, "root-exh")
      _ <- rt.store.mutate(s => s.copy(nodes = s.nodes + ("n-exh" -> NodeDef(
        id = "n-exh", name = "n-exh", agent = "general", status = NodeLifecycle.Running,
        sessionRef = Some(sid), startedAt = Some(System.currentTimeMillis() - 60_000L),
        out = List(OutEdge.nebula), createdAt = System.currentTimeMillis() - 60_000L)))).void
      _ <- res.sessionStore.saveMessagesForSession(sid, List(
        Message(role = MessageRole.User, content = Left("fixture transcript"))))
      bridgeReceived <- Ref.of[IO, List[AgentEvent]](Nil)
      bridgeRef <- system.spawn(mkRecordingEvt(bridgeReceived), "exh-bridge")
      agentRef <- system.spawn(mkRecordingActor(Ref.unsafe(Nil)), "exh-agent")
      _ <- res.agentRegistry.set(Map(sid -> flowRecord(sid, agentRef, "root-exh", Some(bridgeRef))))
      stopCounts <- Ref.of[IO, Map[String, Int]](Map.empty)
      pendingL3 <- Ref.of[IO, List[TaskStuckWatcher.PendingL3]](Nil)
      // 预置：全链预算已耗尽（2/2）
      ledger <- Ref.of[IO, Map[String, TaskStuckWatcher.RecoveryLedger]](Map(
        sid -> TaskStuckWatcher.RecoveryLedger(chainAttempts = Defaults.StuckRecoveryMaxPerChain)))
      _ <- TaskStuckWatcher.scan(res, new WsHub(), threshold, stopCounts, pendingL3, ledger = ledger)
      _ <- TaskStuckWatcher.scan(res, new WsHub(), threshold, stopCounts, pendingL3, ledger = ledger)
      _ <- IO.sleep(400.millis)
      bridgeEvts <- bridgeReceived.get
      nodeAfter <- rt.store.getNode("n-exh")
      counts <- stopCounts.get
      led <- ledger.get
      _ <- IO(system.stopAll.attempt.void.unsafeRunSync())
      _ <- ProjectRuntimeRegistry.clear
    yield
      assert(bridgeEvts.isEmpty, s"预算耗尽 ⇒ 零恢复动作（不发桥信号），得 $bridgeEvts")
      assertEquals(nodeAfter.map(_.status), Some(NodeLifecycle.Failed), "预算耗尽 ⇒ 一次上报（failNode）")
      assertEquals(counts.getOrElse(sid, 0), 0, "预算耗尽 ⇒ 不再进入升级链")
      assert(led.get(sid).exists(_.reportedAt != 0L), "上报恰好一次（账本 reportedAt 结构性保证）")
      assertEquals(led.get(sid).map(_.chainAttempts), Some(2), "零动作 ⇒ 不再消耗预算")
  }

  test("互斥点 1(a) 端到端: 冻结会话 + A1 可用 ⇒ hardResumeNode 拒绝执行（节点保持 Running）") {    val system = ActorSystem("budget-freeze")
    val sid = "node-frozen"
    for
      _ <- IO(system)
      tmp <- IO(os.temp.dir())
      res <- mkResources(system, tmp)
      _ <- ProjectRuntimeRegistry.clear
      ws = tmp / "ws-frozen"; _ <- IO(os.makeDir.all(ws))
      rt <- mountProject("frozen", ws, system, res, "root-frozen")
      _ <- rt.store.mutate(s => s.copy(nodes = s.nodes + ("n-frozen" -> NodeDef(
        id = "n-frozen", name = "n-frozen", agent = "general", status = NodeLifecycle.Running,
        sessionRef = Some(sid), startedAt = Some(System.currentTimeMillis() - 60_000L),
        out = List(OutEdge.nebula), createdAt = System.currentTimeMillis() - 60_000L)))).void
      _ <- res.sessionStore.saveMessagesForSession(sid, List(
        Message(role = MessageRole.User, content = Left("fixture transcript"))))
      agentRef <- system.spawn(mkRecordingActor(Ref.unsafe(Nil)), "frozen-agent")
      _ <- res.agentRegistry.set(Map(sid -> flowRecord(sid, agentRef, "root-frozen", None,
        frozenReason = Some(TaskStuckWatcher.LoopFreezeReasonWire))))
      anchor <- rt.engine.probeRecoveryAnchors(sid)
      res0 <- rt.engine.hardResumeNode(sid, Some(anchor))
      nodeAfter <- rt.store.getNode("n-frozen")
      _ <- IO(system.stopAll.attempt.void.unsafeRunSync())
      _ <- ProjectRuntimeRegistry.clear
    yield
      assertEquals(res0, None, "冻结会话 ⇒ 恢复拒绝执行（不得经 resetCrossTurn 出口绕过 365 天冻结）")
      assertEquals(nodeAfter.map(_.status), Some(NodeLifecycle.Running),
        "拒绝 ⇒ 节点原样保留（人工再激活）")
  }

  // ══ 验收负控（§4.2 表）：②④⑤ 的事件面读数 ═══════════════════════════════════

  /** 读本 spec 自己的看门狗事件面（隔离目录），按 type + sessionId 过滤。 */
  private def readEvents(eventType: String, sessionId: String): IO[List[io.circe.Json]] =
    IO {
      val dir = WatchdogEventLog.logDirForTest
      if !os.exists(os.Path(dir)) then List.empty[io.circe.Json]
      else
        os.list(os.Path(dir)).filter(_.last.endsWith("_events.jsonl")).toList.flatMap { f =>
          os.read.lines(f).toList.flatMap(l =>
            io.circe.parser.parse(l).toOption.filter { j =>
              j.hcursor.get[String]("type").toOption.contains(eventType) &&
                j.hcursor.get[String]("sessionId").toOption.contains(sessionId)
            })
        }
    }

  test("负控②: 恢复后 60s 内 LoopGuard 指纹再命中 ⇒ 零恢复动作 + 写 recovery-loop-detected + 一次上报") {
    val system = ActorSystem("budget-loopdet")
    val sid = "node-loopdet"
    for
      _ <- IO(system)
      tmp <- IO(os.temp.dir())
      res <- mkResources(system, tmp)
      _ <- ProjectRuntimeRegistry.clear
      ws = tmp / "ws-loopdet"; _ <- IO(os.makeDir.all(ws))
      rt <- mountProject("loopdet", ws, system, res, "root-loopdet")
      _ <- rt.store.mutate(s => s.copy(nodes = s.nodes + ("n-loopdet" -> NodeDef(
        id = "n-loopdet", name = "n-loopdet", agent = "general", status = NodeLifecycle.Running,
        sessionRef = Some(sid), startedAt = Some(System.currentTimeMillis() - 60_000L),
        out = List(OutEdge.nebula), createdAt = System.currentTimeMillis() - 60_000L)))).void
      bridgeReceived <- Ref.of[IO, List[AgentEvent]](Nil)
      bridgeRef <- system.spawn(mkRecordingEvt(bridgeReceived), "loopdet-bridge")
      agentRef <- system.spawn(mkRecordingActor(Ref.unsafe(Nil)), "loopdet-agent")
      // 恢复后 10s，跨轮命中计数由 3 → 4（= LoopGuard 在恢复后又命中了一次）
      _ <- res.agentRegistry.set(Map(sid -> flowRecord(sid, agentRef, "root-loopdet", Some(bridgeRef),
        strikes = 4, fp = "fpRecovered")))
      stopCounts <- Ref.of[IO, Map[String, Int]](Map.empty)
      pendingL3 <- Ref.of[IO, List[TaskStuckWatcher.PendingL3]](Nil)
      ledger <- Ref.of[IO, Map[String, TaskStuckWatcher.RecoveryLedger]](Map(
        sid -> TaskStuckWatcher.RecoveryLedger(chainAttempts = 1,
          lastRecoveryAt = System.currentTimeMillis() - 10_000L, strikeBaseline = 3, fpBaseline = "fpRecovered")))
      _ <- TaskStuckWatcher.scan(res, new WsHub(), threshold, stopCounts, pendingL3, ledger = ledger)
      _ <- TaskStuckWatcher.scan(res, new WsHub(), threshold, stopCounts, pendingL3, ledger = ledger)
      _ <- IO.sleep(400.millis)
      evts <- readEvents(TaskStuckWatcher.RecoveryLoopDetectedType, sid)
      bridgeEvts <- bridgeReceived.get
      nodeAfter <- rt.store.getNode("n-loopdet")
      counts <- stopCounts.get
      led <- ledger.get
      _ <- IO(system.stopAll.attempt.void.unsafeRunSync())
      _ <- ProjectRuntimeRegistry.clear
    yield
      assertEquals(evts.size, 1, s"必须写 **恰好一条** recovery-loop-detected 事件，得 ${evts.size}")
      assertEquals(evts.head.hcursor.get[Int]("strikeBaseline").toOption, Some(3))
      assertEquals(evts.head.hcursor.get[Int]("strikeNow").toOption, Some(4))
      assert(led.get(sid).exists(_.chainHalted), "恢复链必须被停（chainHalted）")
      assert(bridgeEvts.isEmpty, s"零恢复动作（不发挂起/桥信号），得 $bridgeEvts")
      assertEquals(counts.getOrElse(sid, 0), 0, "零动作 ⇒ 不进入升级链")
      assertEquals(nodeAfter.map(_.status), Some(NodeLifecycle.Failed), "互斥点 2 宽松档：一次上报（failNode）")
  }

  test("负控④: 首次恢复尝试后未满退避窗 ⇒ 零第二次恢复（退避曲线可判据，不是死常量）") {
    val now = 5_000_000L
    // 第 1 档 = 30s：尝试后 5s ⇒ 退避窗内
    val led = TaskStuckWatcher.RecoveryLedger(chainAttempts = 1, lastAttemptAt = now - 5_000L)
    val g = TaskStuckWatcher.recoveryGate(rec(), led, now)
    assertEquals(g.map(_.kind), Some(TaskStuckWatcher.GateBackoff))
    assertEquals(g.map(_.report), Some(false), "退避窗只留痕，不上报")
    // 退避窗过后（30s）⇒ 放行（此时预算仍在，chain=1 < 2）
    assertEquals(TaskStuckWatcher.recoveryGate(rec(), led, now + Defaults.stuckRecoveryBackoffMs(2)), None,
      "退避窗到期 ⇒ 判定序放行")
    // chain 已 2/2 ⇒ **预算闸门优先**（判定序把预算排在退避之前：预算耗尽是终局，
    // 退避只是「还没到时间」，两者同时成立时终局语义胜出）。
    val led2 = TaskStuckWatcher.RecoveryLedger(chainAttempts = 2, lastAttemptAt = now - 100_000L)
    assertEquals(TaskStuckWatcher.recoveryGate(rec(), led2, now).map(_.kind), Some(TaskStuckWatcher.GateBudgetExhausted),
      "预算耗尽 + 退避窗内 ⇒ 预算闸门（终局语义）优先于退避")
    // 预算未耗尽（chain=1）但退避未满 ⇒ 退避闸门
    val led3 = TaskStuckWatcher.RecoveryLedger(chainAttempts = 1, lastAttemptAt = now - 100_000L)
    assertEquals(TaskStuckWatcher.recoveryGate(rec(), led3, now).map(_.kind), Some(TaskStuckWatcher.GateBackoff),
      "chain=1（下次是第 2 档 = 120s）+ 才过 100s ⇒ 退避闸门")
    // 退避档位查询本身（纯）
    assertEquals(Defaults.stuckRecoveryBackoffMs(1), 30_000L)
    assertEquals(Defaults.stuckRecoveryBackoffMs(2), 120_000L)
    assertEquals(Defaults.stuckRecoveryBackoffMs(3), 600_000L)
  }

  test("负控⑤: 已上报过的节点再命中 ⇒ 零第二次上报（reportedAt 结构性保证恰好一次）") {
    val system = ActorSystem("budget-once")
    val sid = "node-once"
    for
      _ <- IO(system)
      tmp <- IO(os.temp.dir())
      res <- mkResources(system, tmp)
      _ <- ProjectRuntimeRegistry.clear
      ws = tmp / "ws-once"; _ <- IO(os.makeDir.all(ws))
      rt <- mountProject("once", ws, system, res, "root-once")
      _ <- rt.store.mutate(s => s.copy(nodes = s.nodes + ("n-once" -> NodeDef(
        id = "n-once", name = "n-once", agent = "general", status = NodeLifecycle.Running,
        sessionRef = Some(sid), startedAt = Some(System.currentTimeMillis() - 60_000L),
        out = List(OutEdge.nebula), createdAt = System.currentTimeMillis() - 60_000L)))).void
      bridgeReceived <- Ref.of[IO, List[AgentEvent]](Nil)
      bridgeRef <- system.spawn(mkRecordingEvt(bridgeReceived), "once-bridge")
      agentRef <- system.spawn(mkRecordingActor(Ref.unsafe(Nil)), "once-agent")
      _ <- res.agentRegistry.set(Map(sid -> flowRecord(sid, agentRef, "root-once", Some(bridgeRef))))
      stopCounts <- Ref.of[IO, Map[String, Int]](Map.empty)
      pendingL3 <- Ref.of[IO, List[TaskStuckWatcher.PendingL3]](Nil)
      ledger <- Ref.of[IO, Map[String, TaskStuckWatcher.RecoveryLedger]](Map(
        sid -> TaskStuckWatcher.RecoveryLedger(chainAttempts = Defaults.StuckRecoveryMaxPerChain)))
      // 三轮扫描：闸门每轮都命中，但上报只准发生**一次**
      _ <- TaskStuckWatcher.scan(res, new WsHub(), threshold, stopCounts, pendingL3, ledger = ledger)
      _ <- TaskStuckWatcher.scan(res, new WsHub(), threshold, stopCounts, pendingL3, ledger = ledger)
      _ <- TaskStuckWatcher.scan(res, new WsHub(), threshold, stopCounts, pendingL3, ledger = ledger)
      _ <- IO.sleep(400.millis)
      exhausted <- readEvents(TaskStuckWatcher.RecoveryExhaustedType, sid)
      gated <- readEvents(TaskStuckWatcher.RecoveryGatedType, sid)
      counts <- stopCounts.get
      _ <- IO(system.stopAll.attempt.void.unsafeRunSync())
      _ <- ProjectRuntimeRegistry.clear
    yield
      assertEquals(exhausted.size, 1, s"三轮命中 ⇒ **恰好一次**上报事件，得 ${exhausted.size}")
      assertEquals(gated.size, 3, "闸门每轮都留痕（可审计「本拍为什么什么都没做」）")
      assertEquals(counts.getOrElse(sid, 0), 0, "预算闸门零动作 ⇒ stopCounts 不增长")
  }

end StuckRecoveryBudgetSpec
