package nebflow.core.processor

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, ActorSystem, Behavior, Behaviors}
import nebflow.actor.{AgentCommand, AgentEvent, AgentKind, AgentRecord, AgentStatus}
import nebflow.agent.{AgentLibrary, SharedResources}
import nebflow.core.FileChangeTracker
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{BgTaskRegistry, FileLockManager}
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.gateway.WsHub
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, StreamChunk, ThinkingConfig}

import scala.concurrent.duration.*

/**
 * 子代理等待三缺口批（2026-10-25）缺口③ 验收 spec：**tool-phase 挂死的受控
 * 击杀通道**（TaskStuckWatcher 子代理阶梯 L2）。
 *
 * 990bd848 实证：三次 Stop 无效 + 工具相位判死（inflight=0，无 LLM transport
 * 可 abort）⇒ 证据 #5 容器盲区护栏把进程 kill **无条件**扣杀——挂死会话除
 * supervisor Cancelled 外再无出路，孤儿子会话继续烧 token 27min+。
 * 本批把「无条件」改成「**三连 Stop 无效 + 等待面已判死**才放行」：
 *   - 放行条件 = `n == 0`（工具相位）∧ `cls.recoverable`（类① true-stuck：
 *     零正信号、确实停摆）∧ `attempts >= StopAttempts + 1`（transport abort
 *     恰在此拍武装——三次 Stop 已发）∧ §3.4 互斥点 1(b)（status==Processing）。
 *   - 动作 = 复用收殓支 `BgTaskRegistry.reclaimSession`（按自有 PGID 击杀 +
 *     结算名下 bg 任务 + WS 终态帧）。
 * 三条读数：正控（三连后开杀）/ 负控（未满三连不杀）/ 负控（类④ provider-hang
 * ——LLM 层自管，不杀）。
 */
class TaskStuckWatcherControlledKillSpec extends CatsEffectSuite:

  private val watchdogLogTmp = os.temp.dir(prefix = "controlled-kill-events")
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

  private def mkResources(system: ActorSystem, tmp: os.Path): IO[SharedResources] =
    for
      dispatcher <- cats.effect.std.Dispatcher.parallel[IO].allocated.map(_._1)
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

  /**
   * 缺口③ 的目标形态（990bd848 断面）：delegate（有 parentRef）工具相位挂死
   * ——agent 侧事件流停滞 + 同一工具调用超 600s 未返回，零正信号 ⇒ 类①
   * true-stuck（recoverable=true），inflight=0（无 LLM transport 可 abort）。
   */
  private def toolPhaseDelegateRecord(
    sid: String,
    ref: ActorRef[AgentCommand],
    now: Long
  ): AgentRecord =
    AgentRecord(
      sessionId = sid,
      ref = ref,
      kind = AgentKind.Delegate,
      rootSessionId = "root-kill",
      startedAt = now - 30 * 60 * 1000L,
      status = AgentStatus.Processing,
      lastActivityMs = now - threshold - 1000L, // 轴① 命中
      currentToolName = Some("Subagent"),
      currentToolStartedAt = now - threshold - 1000L, // 轴② 命中（无声明授权 ⇒ 600s 档）
      currentToolDeadlineMs = 0L,
      lastProgressSignalAt = 0L, // 从未观测到正信号 ⇒ 类①
      parentRef = Some(ref) // 有父 ⇒ 子代理阶梯（delegate 家族）
    )

  private val jobId = "kill-chan-job"

  /** 名下登记一个等待型 bg 任务——reclaim 开杀的可观测面（unregister + cancelled 帧）。 */
  private def registerProbeTask(sid: String, root: String): IO[Unit] =
    BgTaskRegistry.register(
      jobId = jobId,
      sessionId = sid,
      description = "probe task for controlled-kill",
      kind = "local",
      rootSessionId = root
    )

  test("正控: 三次 Stop 无效（attempts=3）+ 工具相位判死 + 零 LLM transport ⇒ 受控击杀通道触发"):
    val system = ActorSystem("kill-pos")
    val sid = "delegate-killme"
    for
      tmp <- IO(os.temp.dir())
      resources <- mkResources(system, tmp)
      agentReceived <- Ref.of[IO, List[AgentCommand]](Nil)
      agentRef <- system.spawn(mkRecordingActor(agentReceived), "kill-pos-agent")
      wsHub = new WsHub()
      receivedWs <- Ref.of[IO, List[io.circe.Json]](Nil)
      _ <- wsHub.registerListener(json => receivedWs.update(_ :+ json))
      now = System.currentTimeMillis()
      rec = toolPhaseDelegateRecord(sid, agentRef, now).copy(parentRef = Some(agentRef))
      _ <- resources.agentRegistry.set(Map(sid -> rec))
      _ <- registerProbeTask(sid, "root-kill")
      stopCounts <- Ref.of[IO, Map[String, Int]](Map(sid -> 2)) // 前两拍已发 Stop——本拍 attempts=3
      pendingL3 <- Ref.of[IO, List[TaskStuckWatcher.PendingL3]](Nil)
      _ <- TaskStuckWatcher.scan(resources, wsHub, threshold, stopCounts, pendingL3)
      _ <- IO.sleep(150.millis)
      probe <- BgTaskRegistry.find(jobId)
      ws <- receivedWs.get
      cmds <- agentReceived.get
      counts <- stopCounts.get
      _ <- IO(system.stopAll.attempt.void.unsafeRunSync())
    yield
      assertEquals(probe, None, "the controlled kill must reclaim the session (probe task unregistered)")
      assert(
        ws.exists(j =>
          j.hcursor.get[String]("type").toOption.contains("backgroundTaskUpdate") &&
            j.hcursor.get[String]("status").toOption.contains("cancelled") &&
            j.hcursor.get[String]("sessionId").toOption.contains(sid)
        ),
        s"reclaim must broadcast the task-cancelled frame for $sid, got ${ws.map(_.hcursor.get[String]("type").toOption)}"
      )
      assert(
        cmds.exists { case AgentCommand.Stop(_) => true; case _ => false },
        s"the ladder must still send the round's Stop, got $cmds"
      )
      assertEquals(counts.getOrElse(sid, 0), 3, "the round must be counted on the stop ladder")

    end for

  test("负控①: 未满三连（attempts=2，transport abort 未武装拍）⇒ 护栏照旧扣杀，不杀"):
    val system = ActorSystem("kill-neg1")
    val sid = "delegate-notyet"
    for
      tmp <- IO(os.temp.dir())
      resources <- mkResources(system, tmp)
      agentReceived <- Ref.of[IO, List[AgentCommand]](Nil)
      agentRef <- system.spawn(mkRecordingActor(agentReceived), "kill-neg1-agent")
      wsHub = new WsHub()
      now = System.currentTimeMillis()
      rec = toolPhaseDelegateRecord(sid, agentRef, now).copy(parentRef = Some(agentRef))
      _ <- resources.agentRegistry.set(Map(sid -> rec))
      _ <- registerProbeTask(sid, "root-kill")
      stopCounts <- Ref.of[IO, Map[String, Int]](Map(sid -> 1)) // 本拍 attempts=2——未满三连
      pendingL3 <- Ref.of[IO, List[TaskStuckWatcher.PendingL3]](Nil)
      _ <- TaskStuckWatcher.scan(resources, wsHub, threshold, stopCounts, pendingL3)
      _ <- IO.sleep(150.millis)
      probe <- BgTaskRegistry.find(jobId)
      _ <- IO(system.stopAll.attempt.void.unsafeRunSync())
    yield
      assert(probe.isDefined, "before three ineffective Stops the guard must still withhold the kill")
      assertEquals(probe.map(_.sessionId), Some(sid), "the probe task must survive untouched")

    end for

  test("负控②: 类④ provider-hang（在飞 LLM 存在，LLM 层自管）⇒ 不进受控击杀通道"):
    val system = ActorSystem("kill-neg2")
    val sid = "delegate-providerhang"
    for
      tmp <- IO(os.temp.dir())
      resources <- mkResources(system, tmp)
      agentReceived <- Ref.of[IO, List[AgentCommand]](Nil)
      agentRef <- system.spawn(mkRecordingActor(agentReceived), "kill-neg2-agent")
      wsHub = new WsHub()
      now = System.currentTimeMillis()
      // 在飞 LLM 请求 ⇒ classify 读数 inflight>0 ⇒ 类④（recoverable=false——LLM 层自管）
      ((_, handle)) <- nebflow.llm.LlmInterface.registerInflight(Some(sid))
      rec = toolPhaseDelegateRecord(sid, agentRef, now).copy(parentRef = Some(agentRef))
      _ <- resources.agentRegistry.set(Map(sid -> rec))
      _ <- registerProbeTask(sid, "root-kill")
      stopCounts <- Ref.of[IO, Map[String, Int]](Map(sid -> 2))
      pendingL3 <- Ref.of[IO, List[TaskStuckWatcher.PendingL3]](Nil)
      _ <- TaskStuckWatcher.scan(resources, wsHub, threshold, stopCounts, pendingL3)
      _ <- IO.sleep(150.millis)
      probe <- BgTaskRegistry.find(jobId)
      _ <- nebflow.llm.LlmInterface.cancelAllInflight() // 清场（全局 Ref）
      _ <- IO(system.stopAll.attempt.void.unsafeRunSync())
    yield assert(probe.isDefined, "class 4 (provider-hang) owns the case — the controlled kill must NOT fire")

    end for

  test("负控③: 类② 假阳性（正信号新鲜——工具仍在推进）⇒ 本拍零动作，不杀"):
    val system = ActorSystem("kill-neg3")
    val sid = "delegate-advancing"
    for
      tmp <- IO(os.temp.dir())
      resources <- mkResources(system, tmp)
      agentReceived <- Ref.of[IO, List[AgentCommand]](Nil)
      agentRef <- system.spawn(mkRecordingActor(agentReceived), "kill-neg3-agent")
      wsHub = new WsHub()
      now = System.currentTimeMillis()
      rec = toolPhaseDelegateRecord(sid, agentRef, now)
        .copy(parentRef = Some(agentRef), lastProgressSignalAt = now - 3_000L) // 正信号 3s 前 ⇒ 类②
      _ <- resources.agentRegistry.set(Map(sid -> rec))
      _ <- registerProbeTask(sid, "root-kill")
      stopCounts <- Ref.of[IO, Map[String, Int]](Map.empty)
      pendingL3 <- Ref.of[IO, List[TaskStuckWatcher.PendingL3]](Nil)
      _ <- TaskStuckWatcher.scan(resources, wsHub, threshold, stopCounts, pendingL3)
      _ <- IO.sleep(150.millis)
      probe <- BgTaskRegistry.find(jobId)
      counts <- stopCounts.get
      _ <- IO(system.stopAll.attempt.void.unsafeRunSync())
    yield
      assert(probe.isDefined, "a still-advancing tool (class 2) must never be killed")
      assertEquals(counts.getOrElse(sid, 0), 0, "class 2 takes zero action and must not consume ladder attempts")
    end for

end TaskStuckWatcherControlledKillSpec
