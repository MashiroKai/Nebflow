package nebflow.core.processor

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.parser.parse as jsonParse
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, ActorSystem, Behavior, Behaviors}
import nebflow.agent.{AgentCommand, AgentEvent, AgentKind, AgentLibrary, AgentRecord, AgentStatus, SharedResources, SubAgentTaskStore}
import nebflow.core.{FileChangeTracker, PathUtil}
import nebflow.core.compact.HistoryArchiver
import nebflow.core.project.{FlowMapStore, NodeDef, NodeEngine, NodeLifecycle, OutEdge, ProjectDef, ProjectRuntime, ProjectRuntimeRegistry}
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore, WsHub}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * R8「看门狗自身监测」批（2026-09-10 机制设计 `20260910_看门狗自身监测-机制设计.md`
 * §2 方向① / §3 方向② / §4 方向③）**单元验红面**。
 *
 *   - ① 每次开火落结构化事件（`WatchdogEventLog`）：三分支身份 / 会话与 kind /
 *     level 与输入一致；**反例四条**（Idle / WaitingForUser / `lastActivityMs==0` /
 *     无候选扫描）一行都不许写。
 *   - ② L3 有效性自检：桥记录 Cancelled 但节点不迁移 → T+N 复查出 1 条
 *     `l3-ineffective`（+ `logger.error`）；正常 resume（节点已 Pending/Running）
 *     → 不告警（守门反例）。
 *   - ③ 影子模式守门：`nebflow.stuck.shadow=true` 时 0 条 `AgentCommand`、0 条
 *     `AgentEvent`、inflight 计数不变、0 条 WS 帧；**同一输入下事件行数与
 *     shadow=false 相等**（防「shadow = 关掉监测」）；对照 shadow=false 仍真实
 *     发动作。
 *
 * 隔离：本 spec 把 `PathUtil.dataRoot` 与看门狗日志目录都指向自己的临时目录——
 * 不得把测试事件写进真实 `~/.nebflow`。
 */
class WatchdogSelfMonitorSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  private val tmp = os.temp.dir(prefix = "watchdog-selfmon")
  private val originalRoot = PathUtil.dataRoot
  private val wdLogDir = tmp / "wdlog"

  PathUtil.setDataRoot(tmp / "data")
  os.makeDir.all(tmp / "data" / "agents" / "general")
  os.write.over(tmp / "data" / "agents" / "general" / "agent.json",
    """{"name":"general","description":"watchdog self-monitor spec agent","tools":[],"category":"standalone"}""")
  os.write.over(tmp / "data" / "agents" / "general" / "system.md", "# general\n")
  WatchdogEventLog.setLogDirForTest(wdLogDir.toNIO)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    WatchdogEventLog.resetLogDirForTest()
    sys.props.remove(ShadowProp)
    sys.props.remove("nebflow.hardRecovery.enabled")

  override def beforeEach(context: munit.BeforeEach): Unit =
    ProjectRuntimeRegistry.clear
    sys.props.remove(ShadowProp)
    sys.props.remove("nebflow.hardRecovery.enabled")

  override def afterEach(context: munit.AfterEach): Unit =
    ProjectRuntimeRegistry.clear
    sys.props.remove(ShadowProp)

  private val ShadowProp = "nebflow.stuck.shadow"
  private val threshold = 10 * 60 * 1000L

  // ── 夹具 ────────────────────────────────────────────────────────────────

  private class StubLlm:
    def handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(req: LlmRequest, onAttempt: Option[FallbackAttempt => IO[Unit]] = None): Stream[IO, StreamChunk] =
        Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  private def mkResources(system: ActorSystem): IO[SharedResources] =
    for
      dispatcher <- cats.effect.std.Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
    yield SharedResources(
      llm = new StubLlm().handle,
      dispatcher = dispatcher,
      sessionStore = SessionStore(tmp / "sessions", tmp / "tasks"),
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
      subAgentTaskStore = new SubAgentTaskStore(tmp / "subagent-tasks"),
      voiceMutedRef = voiceMuted
    )

  private def mkRecordingActor(record: Ref[IO, List[AgentCommand]]): Behavior[AgentCommand] =
    def loop: Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand](cmd => record.update(_ :+ cmd).as(loop))
    loop

  private def mkRecordingEvt(record: Ref[IO, List[AgentEvent]]): Behavior[AgentEvent] =
    def loop: Behavior[AgentEvent] =
      Behaviors.receiveMessage[AgentEvent](evt => record.update(_ :+ evt).as(loop))
    loop

  /** 读走看门狗事件文件（append-only JSONL；按 ts 排序）。 */
  private def events(): IO[List[Json]] =
    IO.blocking {
      if !os.exists(wdLogDir) then Nil
      else
        os.list(wdLogDir).toList.flatMap { f =>
          os.read.lines(f).toList.filter(_.trim.nonEmpty).flatMap(l => jsonParse(l).toOption)
        }
    }.map(_.sortBy(j => j.hcursor.get[Long]("ts").getOrElse(0L)))

  private def fires(sid: String): IO[List[Json]] =
    events().map(_.filter(j =>
      j.hcursor.get[String]("type").toOption.contains(WatchdogEventLog.StuckFireType) &&
        j.hcursor.get[String]("sessionId").toOption.contains(sid)))

  private def l3Alerts(sid: String): IO[List[Json]] =
    events().map(_.filter(j =>
      j.hcursor.get[String]("type").toOption.contains(WatchdogEventLog.L3IneffectiveType) &&
        j.hcursor.get[String]("sessionId").toOption.contains(sid)))

  private def str(j: Json, k: String): Option[String] = j.hcursor.get[String](k).toOption
  private def num(j: Json, k: String): Option[Long] = j.hcursor.get[Long](k).toOption

  /** 卡死记录构造器：两个轴独立可调（`toolStartedAt = 0` = 无在飞工具）。 */
  private def mkRecord(
    sid: String,
    kind: AgentKind,
    ref: ActorRef[AgentCommand],
    rootSid: String,
    lastActivityMs: Long,
    toolStartedAt: Long,
    toolName: Option[String] = None,
    parentRef: Option[ActorRef[AgentCommand]] = None,
    supervisorRef: Option[ActorRef[AgentEvent]] = None,
    status: AgentStatus = AgentStatus.Processing
  ): AgentRecord =
    AgentRecord(sessionId = sid, ref = ref, kind = kind, rootSessionId = rootSid,
      parentRef = parentRef, status = status, lastActivityMs = lastActivityMs,
      currentToolName = toolName, currentToolStartedAt = toolStartedAt,
      supervisorRef = supervisorRef)

  /** 挂载真实 NodeEngine（Root 会话 id 与记录一致——L3 恢复按 rootSessionId 反查 runtime）。 */
  private def mountProject(name: String, ws: os.Path, system: ActorSystem, res: SharedResources, rootSid: String): IO[ProjectRuntime] =
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

  private def seed(store: FlowMapStore, n: NodeDef): IO[Unit] =
    store.mutate(s => s.copy(nodes = s.nodes + (n.id -> n))).void

  /** logback ERROR 捕获器（`logger.error` 告警面的断言；列表内容由调用方消费）。 */
  private def withStuckAppender[A](body: => A): (A, List[String]) =
    val lbLogger = org.slf4j.LoggerFactory.getLogger("nebflow.core.processor.stuck")
      .asInstanceOf[ch.qos.logback.classic.Logger]
    val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
    appender.start()
    lbLogger.addAppender(appender)
    try
      val a = body
      import scala.jdk.CollectionConverters.*
      val errs = appender.list.asScala.toList
        .filter(_.getLevel == ch.qos.logback.classic.Level.ERROR)
        .map(_.getFormattedMessage)
      (a, errs)
    finally lbLogger.detachAppender(appender)

  // ── ① 事件面：三分支 + 反例四条 ─────────────────────────────────────────

  test("R8-①: 三分支各驱动一次 scan → stuck-fire 事件 branch/sessionId/kind/level 与输入一致") {
    val system = ActorSystem("wd-branch")
    for
      res <- mkResources(system)
      now <- IO(System.currentTimeMillis())
      ref0 <- system.spawn(mkRecordingActor(Ref.unsafe(Nil)), "wd-b-ref")
      _ <- res.agentRegistry.set(Map(
        "team-merged" -> mkRecord("team-merged", AgentKind.Team, ref0, "root-1",
          lastActivityMs = now - 20 * 60 * 1000L, toolStartedAt = now - 30 * 60 * 1000L, toolName = Some("Bash")),
        "team-agent-stale" -> mkRecord("team-agent-stale", AgentKind.Team, ref0, "root-1",
          lastActivityMs = now - 20 * 60 * 1000L, toolStartedAt = 0L),
        "team-tool-overdue" -> mkRecord("team-tool-overdue", AgentKind.Team, ref0, "root-1",
          lastActivityMs = now - 1000L, toolStartedAt = now - 30 * 60 * 1000L, toolName = Some("Bash"))
      ))
      _ <- TaskStuckWatcher.scan(res, new WsHub(), threshold)
      merged <- fires("team-merged")
      stale <- fires("team-agent-stale")
      overdue <- fires("team-tool-overdue")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(merged.size, 1, s"merged 分支应恰好一条事件，得 $merged")
      assertEquals(str(merged.head, "branch"), Some(TaskStuckWatcher.BranchMerged))
      assertEquals(str(merged.head, "kind"), Some("Team"))
      assertEquals(str(merged.head, "level"), Some("attention"))
      assertEquals(num(merged.head, "attempt"), Some(0L))
      assert(num(merged.head, "toolPhaseMs").exists(_ > 0), s"toolPhaseMs 应 > 0: ${merged.head}")
      assert(num(merged.head, "agentIdleMs").exists(_ > 0), s"agentIdleMs 应 > 0: ${merged.head}")
      assertEquals(str(merged.head, "toolName"), Some("Bash"))
      assertEquals(merged.head.hcursor.get[Boolean]("hardRecoveryEnabled").toOption, Some(true))

      assertEquals(stale.size, 1, s"agent-stale 分支应恰好一条事件，得 $stale")
      assertEquals(str(stale.head, "branch"), Some(TaskStuckWatcher.BranchAgentStale))
      assertEquals(str(stale.head, "sessionId"), Some("team-agent-stale"))
      assertEquals(str(stale.head, "level"), Some("attention"))

      assertEquals(overdue.size, 1, s"tool-overdue 分支应恰好一条事件，得 $overdue")
      assertEquals(str(overdue.head, "branch"), Some(TaskStuckWatcher.BranchToolOverdue))
      assertEquals(str(overdue.head, "sessionId"), Some("team-tool-overdue"))
      // 反 CPU 红线：事件里不得出现进程活性字段
      assert(!overdue.head.noSpaces.contains("processActivity"), "事件载荷不得含进程活性信号（红线 R6-4）")
      assert(!overdue.head.noSpaces.toLowerCase.contains("cpu"), "事件载荷不得含 CPU 信号（红线 R6-4）")
  }

  test("R8-① 反例四条: Idle / WaitingForUser / lastActivityMs==0 / 无候选扫描 → 0 行事件") {
    val system = ActorSystem("wd-counterexamples")
    for
      res <- mkResources(system)
      now <- IO(System.currentTimeMillis())
      ref0 <- system.spawn(mkRecordingActor(Ref.unsafe(Nil)), "wd-ce-ref")
      rowsBefore <- events()
      // ① Idle 态（run_in_background 合法态）
      _ <- res.agentRegistry.set(Map(
        "wd-idle" -> mkRecord("wd-idle", AgentKind.Team, ref0, "root-1",
          lastActivityMs = now - 30 * 60 * 1000L, toolStartedAt = now - 30 * 60 * 1000L, status = AgentStatus.Idle)))
      _ <- TaskStuckWatcher.scan(res, new WsHub(), threshold)
      idleRows <- fires("wd-idle")
      // ② WaitingForUser（人在环等待：永不判卡死）
      _ <- res.agentRegistry.set(Map(
        "wd-ask" -> mkRecord("wd-ask", AgentKind.Team, ref0, "root-1",
          lastActivityMs = now - 30 * 60 * 1000L, toolStartedAt = now - 30 * 60 * 1000L, status = AgentStatus.WaitingForUser)))
      _ <- TaskStuckWatcher.scan(res, new WsHub(), threshold)
      askRows <- fires("wd-ask")
      // ③ lastActivityMs==0（刚注册未 touch）+ 无在飞工具
      _ <- res.agentRegistry.set(Map(
        "wd-fresh" -> mkRecord("wd-fresh", AgentKind.Team, ref0, "root-1",
          lastActivityMs = 0L, toolStartedAt = 0L)))
      _ <- TaskStuckWatcher.scan(res, new WsHub(), threshold)
      freshRows <- fires("wd-fresh")
      // ④ 无候选扫描（空 registry）——防「每轮一行心跳」把文件写成时间序列噪声
      _ <- res.agentRegistry.set(Map.empty)
      _ <- TaskStuckWatcher.scan(res, new WsHub(), threshold)
      afterEmpty <- events()
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(idleRows, Nil, "Idle 态不得写事件行")
      assertEquals(askRows, Nil, "WaitingForUser 不得写事件行")
      assertEquals(freshRows, Nil, "lastActivityMs==0 不得写事件行")
      // ④：四次扫描**一行都没写**（以本用例开始前的文件行数为基线——看门狗日志目录
      // 是本 spec 共享的，事件数比较必须用「本用例区间」）
      assertEquals(afterEmpty.size, rowsBefore.size,
        s"无 stuck 候选的扫描不得写任何行（防 30s 心跳噪声）：before=${rowsBefore.size} after=${afterEmpty.size}")
  }

  // ── ② L3 有效性自检 ─────────────────────────────────────────────────────

  /** L3 自检夹具：挂载项目 + 一个 sessionRef 绑定的 Cancelled 节点 + Flow 卡死记录。
    * 返回**同一** stopCounts / pendingL3（阶梯 L1→L3 必须跨扫描累积）。 */
  private def driveL3(
    tag: String,
    shadow: Boolean = false
  ): IO[(SharedResources, ProjectRuntime, Ref[IO, List[AgentEvent]], Ref[IO, List[TaskStuckWatcher.PendingL3]], Ref[IO, Map[String, Int]], String, ActorSystem)] =
    val sid = s"node-$tag"
    val rootSid = s"root-$tag"
    val system = ActorSystem(s"wd-$tag")
    for
      res <- mkResources(system)
      ws = tmp / s"ws-$tag"
      _ <- IO(os.makeDir.all(ws))
      rt <- mountProject(s"wd-$tag", ws, system, res, rootSid)
      _ <- seed(rt.store, NodeDef(id = s"n-$tag", name = s"n-$tag", agent = "general",
        status = NodeLifecycle.Cancelled, result = Some("cancelled[source=engine]: reason=stuck (L3 hard-recovery: x)"),
        sessionRef = Some(sid), out = List(OutEdge.nebula),
        createdAt = System.currentTimeMillis() - 60_000L))
      bridgeReceived <- Ref.of[IO, List[AgentEvent]](Nil)
      bridgeRef <- system.spawn(mkRecordingEvt(bridgeReceived), s"bridge-$tag")
      agentRef <- system.spawn(mkRecordingActor(Ref.unsafe(Nil)), s"agent-$tag")
      _ <- IO(sys.props.remove(ShadowProp))
      _ <- IO(if shadow then sys.props.update(ShadowProp, "true") else sys.props.remove(ShadowProp))
      // ── P2（stuck 自动恢复批，2026-09-11 R-1=B）夹具补充：**可读 transcript** ──
      // 恢复腿的锚探测（设计 §3.1 的 A1）是**恢复前置步骤**：transcript 为空 ⇒ 按
      // 负控①「不重试，直接一次上报」，**不发任何桥信号**（挂起腿不启动）。本用例要
      // 观测「L3 开火 ⇒ 桥收到中断信号」，故必须给 A1 一份非空 transcript——这不是
      // 放松断言，而是让夹具满足恢复腿的真实前置条件（空 transcript 的分支由本 spec
      // 新增的「A1 不可用」用例单独覆盖）。
      _ <- res.sessionStore.saveMessagesForSession(sid, List(
        nebflow.shared.Message(role = nebflow.shared.MessageRole.User, content = Left("fixture: stuck session transcript"))))
      _ <- res.agentRegistry.set(Map(sid -> mkRecord(sid, AgentKind.Flow, agentRef, rootSid,
        lastActivityMs = System.currentTimeMillis() - threshold - 1000L, toolStartedAt = 0L,
        supervisorRef = Some(bridgeRef))))
    yield (res, rt, bridgeReceived, Ref.unsafe[IO, List[TaskStuckWatcher.PendingL3]](Nil),
      Ref.unsafe[IO, Map[String, Int]](Map.empty), sid, system)

  test("R8-② 正例: 桥记录 Cancelled 但节点不迁移 → T+N 复查出 1 条 l3-ineffective（+ logger.error）") {
    val system = ActorSystem("wd-l3fail")
    val io = for
      t <- driveL3("l3fail")
      (res, rt, bridgeReceived, pending, stopCounts, sid, _) = t
      wsHub = new WsHub()
      _ <- TaskStuckWatcher.scan(res, wsHub, threshold, stopCounts, pending) // L1
      _ <- TaskStuckWatcher.scan(res, wsHub, threshold, stopCounts, pending) // L2
      _ <- TaskStuckWatcher.scan(res, wsHub, threshold, stopCounts, pending) // L3（登记待复查）
      reg <- pending.get
      nodeBefore <- rt.store.getNode(s"n-l3fail")
      // T+N：延迟注入 0 = 立刻到期（生产为常量 120s，测试唯一注入点）
      _ <- TaskStuckWatcher.scan(res, wsHub, threshold, stopCounts, pending, l3VerifyDelayMs = 0L)
      alerts <- l3Alerts(sid)
      // P2（2026-09-11）：L3 恢复腿的**锚探测 + 挂起**在 forked fiber 里跑（锚探测含
      // 两条只读 git 调用，几十毫秒量级），桥消息（挂起哨兵）要等该 fiber 调度到才
      // 发出 ⇒ 读桥事件前补一个短歇（本 spec 的既有惯例：读异步 tell/WS 帧前
      // `IO.sleep`）。这是**测试夹具**的时序等待，不是把生产行为改成同步——生产侧
      // 「开火 ⇒ 挂起信号」仍由 forked 恢复腿发出，扫描循环不等。
      _ <- IO.sleep(500.millis)
      bridgeEvts <- bridgeReceived.get
      _ <- IO(system.stopAll.attempt.void.unsafeRunSync())
    yield (reg, nodeBefore.map(_.status), alerts, bridgeEvts, sid)
    val ((reg, nodeStatus, alerts, bridgeEvts, sid), errs) = withStuckAppender(io.unsafeRunSync())
    assertEquals(reg.map(_.sessionId), List(sid), s"L3 开火必须登记待复查: $reg")
    assertEquals(nodeStatus, Some(NodeLifecycle.Cancelled), "本用例节点刻意停在 Cancelled（resume 未生效形态）")
    assertEquals(alerts.size, 1, s"T+N 复查应产出恰好一条 l3-ineffective，得 $alerts")
    assertEquals(str(alerts.head, "evidence"), Some("node-still-cancelled"))
    assertEquals(str(alerts.head, "level"), Some("L3"))
    assertEquals(str(alerts.head, "nodeId"), Some("n-l3fail"))
    assertEquals(num(alerts.head, "attempt"), Some(3L))
    // 事件与开火行可 join（同 sessionId + firedAt ≤ 事件 ts）
    assert(num(alerts.head, "firedAt").isDefined && num(alerts.head, "verifiedAt").isDefined,
      s"复查事件必须带 firedAt/verifiedAt 供 join: ${alerts.head}")
    assert(bridgeEvts.exists(_.isInstanceOf[AgentEvent.Cancelled]), "L3 桥 Cancelled 必须已发出（本用例的前提）")
    // 一期告警第二档：logger.error（人肉排障入口）
    assert(errs.exists(_.contains("L3 INEFFECTIVE")), s"必须落 logger.error 告警，实得 ${errs.filter(_.nonEmpty)}")
  }

  test("R8-② 守门反例: 正常 resume（节点已 Pending/Running）→ 不告警；无 runtime → 不误报") {
    val system = ActorSystem("wd-l3ok")
    val io = for
      t <- driveL3("l3ok")
      (res, rt, _, pending, stopCounts, sid, _) = t
      wsHub = new WsHub()
      _ <- TaskStuckWatcher.scan(res, wsHub, threshold, stopCounts, pending)
      _ <- TaskStuckWatcher.scan(res, wsHub, threshold, stopCounts, pending)
      _ <- TaskStuckWatcher.scan(res, wsHub, threshold, stopCounts, pending) // L3
      // 模拟 resume 已生效：节点被 CAS 翻回 Pending（设计 §3.6(iii)：只要求出现过 Pending/Running）
      _ <- rt.store.mutate(s => s.copy(nodes = s.nodes.updated("n-l3ok",
        s.nodes("n-l3ok").copy(status = NodeLifecycle.Pending)))).void
      _ <- TaskStuckWatcher.scan(res, wsHub, threshold, stopCounts, pending, l3VerifyDelayMs = 0L)
      alerts <- l3Alerts(sid)
      // 无 project runtime（跨项目/未挂载）→ 裁决不可得 ⇒ 保守不告警（设计 §3.8 容忍重启丢 pending）
      //
      // ⚠ P2（2026-09-11）夹具订正：原写法 `IO(ProjectRuntimeRegistry.clear)` 只是把
      // 返回的 `IO[Unit]` **包进另一个 IO** 后丢弃——clear 从未执行（既有 latent bug，
      // 旧定位键 `rootSessionId` 把它掩盖了：传一个不存在的 root 也能得到「无 runtime」
      // 的效果）。P2 把定位键改为「会话属于哪个 store」后，本用例必须**真的**清空
      // registry 才能构造出「无 runtime 拥有该会话」——故改为直接执行。
      _ <- ProjectRuntimeRegistry.clear
      _ <- TaskStuckWatcher.scan(res, wsHub, threshold, cats.effect.Ref.unsafe(Map.empty[String, Int]),
        cats.effect.Ref.unsafe(List(TaskStuckWatcher.PendingL3(sid, "root-gone", 0L, TaskStuckWatcher.BranchMerged, 0L, 0L, 3))),
        l3VerifyDelayMs = 0L)
      allAlerts <- l3Alerts(sid)
      _ <- IO(system.stopAll.attempt.void.unsafeRunSync())
    yield (alerts, allAlerts)
    val (alerts, allAlerts) = io.unsafeRunSync()
    assertEquals(alerts, Nil, "resume 生效（节点已 Pending/Running）不得告警")
    assertEquals(allAlerts, Nil, "无 runtime 不得误报（裁决不可得 = 保守不告警）")
  }

  // ── ③ 影子模式守门 ─────────────────────────────────────────────────────

  /** 单臂：3 次扫描（L1/L2/L3）于同一输入；返回（事件行数、桥事件、AgentCommand、inflight 是否被硬取消）。 */
  private def driveShadowArm(tag: String, shadow: Boolean): IO[(Int, List[AgentEvent], List[AgentCommand], Boolean)] =
    val sid = s"node-$tag"
    val rootSid = s"root-$tag"
    val system = ActorSystem(s"wd-shadow-$tag")
    val io = for
      res <- mkResources(system)
      bridgeReceived <- Ref.of[IO, List[AgentEvent]](Nil)
      bridgeRef <- system.spawn(mkRecordingEvt(bridgeReceived), s"sh-bridge-$tag")
      agentReceived <- Ref.of[IO, List[AgentCommand]](Nil)
      agentRef <- system.spawn(mkRecordingActor(agentReceived), s"sh-agent-$tag")
      _ <- IO(if shadow then sys.props.update(ShadowProp, "true") else sys.props.remove(ShadowProp))
      _ <- res.agentRegistry.set(Map(sid -> mkRecord(sid, AgentKind.Flow, agentRef, rootSid,
        lastActivityMs = System.currentTimeMillis() - threshold - 1000L, toolStartedAt = 0L,
        supervisorRef = Some(bridgeRef))))
      (_, halt) <- nebflow.llm.LlmInterface.registerInflight(Some(sid))
      wsHub = new WsHub()
      receivedWs <- Ref.of[IO, List[Json]](Nil)
      _ <- wsHub.register(json => receivedWs.update(_ :+ json))
      stopCounts <- Ref.of[IO, Map[String, Int]](Map.empty)
      _ <- TaskStuckWatcher.scan(res, wsHub, threshold, stopCounts)
      abortedAfterL1 <- halt.tryGet
      _ <- TaskStuckWatcher.scan(res, wsHub, threshold, stopCounts)
      _ <- TaskStuckWatcher.scan(res, wsHub, threshold, stopCounts)
      rows <- fires(sid)
      bridgeEvts <- bridgeReceived.get
      cmds <- agentReceived.get
      ws <- receivedWs.get
      _ <- IO(system.stopAll.attempt.void.unsafeRunSync())
    yield (rows.size, bridgeEvts, cmds, abortedAfterL1.isDefined)
    io.guaranteeCase(_ => IO(sys.props.remove(ShadowProp)))

  test("R8-③ shadow 守门: 0 AgentCommand / 0 AgentEvent / inflight 不变 / 0 WS 帧，且事件行数与 shadow=false 相等") {
    // 对照臂（shadow=false）：仍真实动作
    val control = driveShadowArm("ctl", shadow = false).unsafeRunSync()
    val shadow = driveShadowArm("shd", shadow = true).unsafeRunSync()
    val (cRows, cBridge, cCmds, cAborted) = control
    val (sRows, sBridge, sCmds, sAborted) = shadow
    // 对照：生产链未被误关（L1 真硬取消在飞 LLM）
    //
    // ⚠ 2026-09-11 判据序改造（stuck 自动恢复批 P1，作者裁定 R-3）后的口径更新：
    // 本臂刻意在会话上注册了一条**在飞 LLM 请求**（用作 L1 硬取消的可观测量）⇒ 按
    // 新判据序 [[TaskStuckWatcher.classify]] 的第一档，该会话三拍**全部**归**类④
    // provider hang**（`inflightFor > 0`）。类④ 的硬约束（任务书负控③）=
    // 「不执行 L2 进程 kill / L3」⇒ 本臂**不应**再出现 L3 的桥 Cancelled——这正是
    // 本断言从「L3 必须发桥 Cancelled」改为「类④ 不得发桥 Cancelled」的原因。
    // L2/L3 腿本身的覆盖不受影响：`l3-ineffective` 用例与本 spec 外的
    // `node-l3ok/node-l3fail` 用例都是**无在飞请求**的类① 会话，L3 腿照常开火；
    // 本用例保留的三条 `stuck-fire` 行（L1/L2/L3 三拍）亦证明升级链未被关掉。
    assert(cAborted, "shadow=false 臂：L1 必须真实硬取消在飞 LLM（证明开关边界正确）")
    assert(!cBridge.exists(_.isInstanceOf[AgentEvent.Cancelled]),
      s"shadow=false 臂：类④（inflight>0）不得发桥 Cancelled——节点级 L3 腿对类④ 被结构性跳过，得 $cBridge")
    // 守门：shadow=true 全部破坏性动作被禁
    assertEquals(sBridge, Nil, "shadow=true 不得发任何 AgentEvent（含 bridgeCancelled）")
    assertEquals(sCmds, Nil, "shadow=true 不得发任何 AgentCommand")
    assert(!sAborted, "shadow=true 不得硬取消在飞 LLM（inflight 计数不变）")
    // 防「shadow = 关掉监测」：同一输入下事件行数相等
    assertEquals(sRows, cRows, "shadow=true 与 false 在同一输入下事件行数必须相等（shadow 只关动作、不关监测）")
    assertEquals(cRows, 3, s"三次扫描 = 三条 stuck-fire（L1/L2/L3），得 $cRows")
  }

end WatchdogSelfMonitorSpec
