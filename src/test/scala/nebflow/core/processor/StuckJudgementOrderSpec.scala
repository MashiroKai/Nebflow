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

  // ══ wd-fix 批（2026-09-12 作者裁定「只做方向 A」）════════════════════════════
  // 三条目标行为：① 正信号新鲜者不得判死（去掉 toolPhaseMs ≤ 有效阈值 前置）；
  // ② 正信号窗与授权解耦（窗随授权联动、单调不减、未声明零变化）；
  // ③ 文案如实（按实际命中分支输出原因）。
  // 语料 = 生产事件面 `~/.nebflow/logs/watchdog/2026-09-12_events.jsonl`（541 条 /
  // `stuck-detected` 378 条），逐条重放见 [[WatchdogCriteriaReplaySpec]]。

  /** 生产样本形态（本批三条目标行为的输入面）：命令自带 `timeout` 声明、单工具调用
    * 持续、工具执行期 agent 侧零事件（轴① 同步停滞）。
    * `declaredMs` = 命令声明时长（= `AgentRecord.currentToolDeadlineMs`，0 = 未声明）。 */
  private def productionShape(sid: String, ref: ActorRef[AgentCommand], now: Long,
                              declaredMs: Long, toolPhaseMs: Long,
                              progressAgeMs: Option[Long]): AgentRecord =
    AgentRecord(
      sessionId = sid,
      ref = ref,
      kind = AgentKind.Flow,
      rootSessionId = "root-wdfix",
      startedAt = now - 60 * 60 * 1000L,
      status = AgentStatus.Processing,
      lastActivityMs = now - toolPhaseMs,
      currentToolName = Some("Bash"),
      currentToolStartedAt = now - toolPhaseMs,
      currentToolDeadlineMs = declaredMs,
      lastProgressSignalAt = progressAgeMs.fold(0L)(age => now - age)
    )

  test("wd① 正信号新鲜 + 工具相位**已超授权** ⇒ 类②（去掉前置：超授权不再否决正信号）") {
    val system = ActorSystem("wdfix-1")
    for
      _ <- IO(system)
      ref <- system.spawn(mkRecordingActor(Ref.unsafe(Nil)), "wdfix-1-ref")
      now = System.currentTimeMillis()
      // 生产样本 node-eb8a9c70 形态：toolPhaseMs 604810ms、正信号 3s 前、未登记声明
      // （有效阈值 600s）⇒ 改造前必判 true-stuck（事件面实读为 class=true-stuck）。
      rec = productionShape("node-wdfix1", ref, now, declaredMs = 0L,
        toolPhaseMs = 604_810L, progressAgeMs = Some(3_000L))
      a = TaskStuckWatcher.assessDetailed(rec, now).getOrElse(fail("判据必须命中（前提）"))
      cls = TaskStuckWatcher.classify(rec, a, now, inflight = 0)
      // 对照臂：声明了 `timeout=600s`（有效阈值 660s）而工具相位 700s —— 同样必须类②。
      rec2 = productionShape("node-wdfix1b", ref, now, declaredMs = 600_000L,
        toolPhaseMs = 700_000L, progressAgeMs = Some(5_000L))
      a2 = TaskStuckWatcher.assessDetailed(rec2, now).getOrElse(fail("判据必须命中（前提）"))
      cls2 = TaskStuckWatcher.classify(rec2, a2, now, inflight = 0)
      _ <- IO(system.stopAll.attempt.void.unsafeRunSync())
    yield
      assertEquals(a.branch, TaskStuckWatcher.BranchMerged, "轴①+轴② 双命中 ⇒ merged（生产样本形态）")
      assertEquals(cls.cls, TaskStuckWatcher.ClassFalsePositive,
        s"正信号新鲜（3s）⇒ 类②，得 ${cls.cls}: ${cls.note}")
      assert(!cls.recoverable, "类② 不进恢复链")
      assert(!cls.destructiveAllowed, "类② 不许破坏档")
      assertEquals(cls2.cls, TaskStuckWatcher.ClassFalsePositive,
        s"声明 600s / 工具相位 700s 超授权但正信号新鲜 ⇒ 类②，得 ${cls2.cls}: ${cls2.note}")
      // ③ 文案如实：超授权事实必须写清，且**不得**再出现「没有正信号」与新鲜正信号同句。
      assert(cls.note.contains("over its authorised window"),
        s"超授权分支必须写明超授权事实，得 ${cls.note}")
      assert(cls.note.contains("tool phase 604s") && cls.note.contains("authorised 600s"),
        s"机器可读读数（工具相位 / 授权）必须保留，得 ${cls.note}")
      assert(cls.note.contains("progress signal 3s ago"),
        s"机器可读读数（正信号新鲜度）必须保留，得 ${cls.note}")
      assert(!cls.note.contains("no progress signal") && !cls.note.contains("no fresh progress signal"),
        s"有新鲜正信号时不得写「无正信号」（③ 自相矛盾面），得 ${cls.note}")
      assert(!cls.note.contains("still inside its authorised window"),
        s"超授权时不得再写「仍在授权窗内」，得 ${cls.note}")
      assertEquals(cls2.note.contains("over its authorised window"), true,
        s"声明 600s 的对照臂同样按超授权分支输出，得 ${cls2.note}")
  }

  test("wd② 声明 3600s + 安静 74s ⇒ 类②（窗随授权联动 = max(60s, 声明/10) = 360s）") {
    val system = ActorSystem("wdfix-2")
    for
      _ <- IO(system)
      ref <- system.spawn(mkRecordingActor(Ref.unsafe(Nil)), "wdfix-2-ref")
      now = System.currentTimeMillis()
      // 生产样本 node-8920254c 形态：命令自带 timeout=3600s（有效阈值 3660s），
      // 工具相位 676s、正信号 74s 前 ⇒ 改造前因「正信号 >60s 未刷新」判 true-stuck。
      rec = productionShape("node-wdfix2", ref, now, declaredMs = 3_600_000L,
        toolPhaseMs = 676_006L, progressAgeMs = Some(74_000L))
      a = TaskStuckWatcher.assessDetailed(rec, now).getOrElse(fail("判据必须命中（前提）"))
      cls = TaskStuckWatcher.classify(rec, a, now, inflight = 0)
      // 同形态但安静段超出联动窗（360s）⇒ 必须**仍判**类①（真卡死不因联动被放走）。
      recStale = productionShape("node-wdfix2b", ref, now, declaredMs = 3_600_000L,
        toolPhaseMs = 1_000_000L, progressAgeMs = Some(400_000L))
      aStale = TaskStuckWatcher.assessDetailed(recStale, now).getOrElse(fail("判据必须命中（前提）"))
      clsStale = TaskStuckWatcher.classify(recStale, aStale, now, inflight = 0)
      _ <- IO(system.stopAll.attempt.void.unsafeRunSync())
    yield
      assertEquals(TaskStuckWatcher.effectiveProgressWindowMs(3_600_000L), 360_000L,
        "声明 3600s ⇒ 有效窗 360s（max(60s, 3600s/10)）")
      assert(TaskStuckWatcher.hasProgressSignal(rec, now,
        TaskStuckWatcher.effectiveProgressWindowMs(rec.currentToolDeadlineMs)),
        "74s 的安静段落在 360s 联动窗内 ⇒ 正信号新鲜")
      assert(!TaskStuckWatcher.hasProgressSignal(rec, now,
        nebflow.shared.Defaults.StuckProgressSignalWindowMs),
        "对照：基础 60s 窗下同一读数被判为无正信号（= 改造前判死根因）")
      assertEquals(cls.cls, TaskStuckWatcher.ClassFalsePositive,
        s"窗联动后不再判死，得 ${cls.cls}: ${cls.note}")
      assert(cls.note.contains("window 360s"),
        s"note 必须给出实际生效的窗（可复核联动），得 ${cls.note}")
      assertEquals(clsStale.cls, TaskStuckWatcher.ClassTrueStuck,
        s"安静段超出联动窗 ⇒ 仍判类①（不得放走真卡死），得 ${clsStale.cls}: ${clsStale.note}")
      assert(clsStale.recoverable, "类① 必须仍可进恢复链")
  }

  test("wd③ 无正信号 + 工具相位超授权 ⇒ 仍类①（真判不减少；文案写清超授权事实）") {
    val system = ActorSystem("wdfix-3")
    for
      _ <- IO(system)
      ref <- system.spawn(mkRecordingActor(Ref.unsafe(Nil)), "wdfix-3-ref")
      now = System.currentTimeMillis()
      rec = productionShape("node-wdfix3", ref, now, declaredMs = 0L,
        toolPhaseMs = 604_810L, progressAgeMs = None) // 从未观测到正信号
      a = TaskStuckWatcher.assessDetailed(rec, now).getOrElse(fail("判据必须命中（前提）"))
      cls = TaskStuckWatcher.classify(rec, a, now, inflight = 0)
      recStale = productionShape("node-wdfix3b", ref, now, declaredMs = 0L,
        toolPhaseMs = 604_810L, progressAgeMs = Some(75_000L)) // 正信号已过期（>60s 基础窗）
      aStale = TaskStuckWatcher.assessDetailed(recStale, now).getOrElse(fail("判据必须命中（前提）"))
      clsStale = TaskStuckWatcher.classify(recStale, aStale, now, inflight = 0)
      _ <- IO(system.stopAll.attempt.void.unsafeRunSync())
    yield
      assertEquals(cls.cls, TaskStuckWatcher.ClassTrueStuck, s"无正信号 ⇒ 类①，得 ${cls.note}")
      assert(cls.recoverable, "类① 允许进恢复链（挂起 + 从 transcript 续跑）")
      assert(!cls.destructiveAllowed, "类① 非破坏档（禁 L2 进程 kill / L3 终态化）")
      assert(cls.note.contains("no progress signal"), s"从未观测 ⇒ 文案如实写无正信号，得 ${cls.note}")
      assert(cls.note.contains("over its authorised window"),
        s"超授权事实必须写明，得 ${cls.note}")
      assertEquals(clsStale.cls, TaskStuckWatcher.ClassTrueStuck,
        s"正信号过期（75s > 60s 基础窗、未声明授权）⇒ 仍类①，得 ${clsStale.note}")
      assert(clsStale.note.contains("no fresh progress signal") && clsStale.note.contains("75s ago"),
        s"文案必须写「不新鲜」并给出实际读数（不写「没有正信号」），得 ${clsStale.note}")
  }

  test("wd② 窗联动单调性: 声明越长窗不得越小；未声明 ⇒ 基础窗逐字不变（零行为变化）") {
    val base = nebflow.shared.Defaults.StuckProgressSignalWindowMs
    val declared = List(0L, 1L, 60_000L, 600_000L, 900_000L, 3_600_000L, 72_000_000L)
    val windows = declared.map(d => TaskStuckWatcher.effectiveProgressWindowMs(d))
    assertEquals(TaskStuckWatcher.effectiveProgressWindowMs(0L), base, "未声明 ⇒ 基础窗 60s")
    assertEquals(TaskStuckWatcher.effectiveProgressWindowMs(-1L), base, "非正数视作未声明")
    assertEquals(TaskStuckWatcher.effectiveProgressWindowMs(600_000L), base,
      "声明 600s（600000/10 = 60000 = 基础窗）⇒ 基础窗")
    assertEquals(TaskStuckWatcher.effectiveProgressWindowMs(3_600_000L), 360_000L)
    assert(windows.sliding(2).forall { case List(a, b) => b >= a; case _ => true },
      s"窗必须随声明时长单调不减，得 $windows")
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

  // ── wd④ 开火链（**必须放在本 spec 最后**：本 suite 共用同一份事件文件，而既有的
  //    「正控① 扫描面」用例断言该文件里**没有** stuck-fire —— 本用例刻意开火，故
  //    定义次序必须晚于它。这不改任何既有断言，只避免本用例的事件污染其读数面。）──

  test("wd④ L1→L3 分级未被破坏: 无正信号的类① 会话仍逐拍升到 L3（并登记待复查）") {
    val system = ActorSystem("wdfix-4")
    for
      _ <- IO(system)
      tmp <- IO(os.temp.dir())
      resources <- mkResources(system, tmp)
      agentRef <- system.spawn(mkRecordingActor(Ref.unsafe(Nil)), "wdfix-4-agent")
      bridgeReceived <- Ref.of[IO, List[AgentEvent]](Nil)
      bridgeRef <- system.spawn(mkRecordingEvt(bridgeReceived), "wdfix-4-bridge")
      wsHub = new WsHub()
      now = System.currentTimeMillis()
      // 真卡死形态：声明 3600s 授权 + 从未有正信号（安静段远超联动窗）⇒ 类①
      rec = productionShape("node-wdfix4", agentRef, now, declaredMs = 3_600_000L,
        toolPhaseMs = 1_500_000L, progressAgeMs = None).copy(supervisorRef = Some(bridgeRef))
      _ <- resources.agentRegistry.set(Map(rec.sessionId -> rec))
      stopCounts <- Ref.of[IO, Map[String, Int]](Map.empty)
      pendingL3 <- Ref.of[IO, List[TaskStuckWatcher.PendingL3]](Nil)
      _ <- TaskStuckWatcher.scan(resources, wsHub, threshold, stopCounts, pendingL3) // L1
      _ <- TaskStuckWatcher.scan(resources, wsHub, threshold, stopCounts, pendingL3) // L2
      _ <- TaskStuckWatcher.scan(resources, wsHub, threshold, stopCounts, pendingL3) // L3
      _ <- IO.sleep(400.millis)
      fires <- eventsOfType(WatchdogEventLog.StuckFireType)
      detected <- eventsOfType(TaskStuckWatcher.StuckDetectedType)
      counts <- stopCounts.get
      pend <- pendingL3.get
      _ <- IO(system.stopAll.attempt.void.unsafeRunSync())
    yield
      val levels = fires.filter(_.hcursor.get[String]("sessionId").toOption.contains("node-wdfix4"))
        .flatMap(_.hcursor.get[String]("level").toOption)
      assert(levels.contains("L1") && levels.contains("L2") && levels.contains("L3"),
        s"L1→L3 分级必须逐拍发生（真判不减少），得 $levels")
      assertEquals(counts.getOrElse("node-wdfix4", 0), 3, "扫描三拍 ⇒ 升级阶梯计数 3")
      assertEquals(pend.map(_.sessionId), List("node-wdfix4"), "L3 必须登记待复查（复查面未被本批改动）")
      val mine = detected.filter(_.hcursor.get[String]("sessionId").toOption.contains("node-wdfix4"))
      assert(mine.nonEmpty && mine.forall(_.hcursor.get[String]("class").toOption
        .contains(TaskStuckWatcher.ClassTrueStuck)),
        s"每一拍都必须判类①（无正信号），得 ${mine.map(_.hcursor.get[String]("class").toOption)}")
      // 三拍各留一条（L3 拍在既有实现里写两次 `stuck-detected`——`classifyNote` 在
      // Flow 分支与 L3 分支各执行一次；生产语料同形的重复行即此因，本批未动它）。
      assertEquals(mine.flatMap(_.hcursor.get[Long]("toolPhaseMs").toOption).distinct.size, 3,
        "三拍必须各自留痕（读数三点互异）")
  }

  /** 薄包装：把 `AgentCore.projectLoopCounters` 提到测试可读处（避免重复 import 长链）。 */
  private object AgentCoreProjection:
    def of(c: nebflow.core.processor.LoopGuard.Counters): (Int, String) =
      nebflow.agent.AgentCore.projectLoopCounters(c)

end StuckJudgementOrderSpec
