package nebflow.agent

import cats.effect.{IO, Ref}
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, Behavior, Behaviors}
import nebflow.actor.{AgentCommand, AgentEvent, AgentKind, AgentRecord}
import nebflow.core.FileChangeTracker
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, ToolContext}
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk, SubAgentTask, ThinkingConfig}

import scala.concurrent.duration.*

/**
 * R1 三修收口批（2026-10-29 · 报告二 §1.3 硬验收「三路径」之**进程内 respawn 腿**）：
 * 取消墓碑（DelegateRegistry.cancelRequestedAt）在场的 delegate **永不 respawn**。
 *
 * 红绿锚：
 *  - 红（修前）：`BackoffSupervisor.respawn` 不读墓碑——doCancel 降级兜底只 Stop
 *    child，慢半拍的监督者把 Terminated 照常走进 respawn：已 cancelled 的行被翻回
 *    restarting、新内核 spawn = 取消被进程内复活腿无声推翻（DelegateRevival 七闸
 *    管不到本腿；报告二实证僵尸的进程内同构形态）。
 *  - 绿（修后）：respawn 头部墓碑闸（crashed / empty-completion 两腿共用单点）——
 *    在场 ⇒ 不写 restarting、零 spawn、直接落 cancelled 终态（R1）；无墓碑的崩溃
 *    续跑照旧（R2 对照——闸只在墓碑在场时触发）；AgentControl restart 同闸显式
 *    拒绝、回执如实（R3）。
 */
class BackoffSupervisorTombstoneSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 30.seconds

  private val originalRoot = PathUtil.dataRoot

  override def afterEach(context: munit.AfterEach): Unit = PathUtil.setDataRoot(originalRoot)

  private val fakeLlm = new LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("fake llm not expected here"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): fs2.Stream[IO, StreamChunk] =
      fs2.Stream.raiseError[IO](new RuntimeException("fake llm not expected here"))

  /** 收到 Stop 即停的 child probe（供崩溃腿注入：Stop = 模拟 crash → Terminated）。 */
  private def mkStopOnStopProbe: Behavior[AgentCommand] =
    def loop: Behavior[AgentCommand] =
      Behaviors.receiveMessage:
        case _: AgentCommand.Stop => IO.pure(Behaviors.stopped)
        case _                    => IO.pure(loop)
    loop

  /** 恒哑的 AgentEvent 收件箱（doRestart 前置的 supervisorRef 占位——本测不经 actor 链）。 */
  private def mkIdleEventProbe: Behavior[AgentEvent] =
    def loop: Behavior[AgentEvent] = Behaviors.receiveMessage(_ => IO.pure(loop))
    loop

  private def mkRecordingCmd(record: Ref[IO, List[AgentCommand]]): Behavior[AgentCommand] =
    def loop: Behavior[AgentCommand] =
      Behaviors.receiveMessage(cmd => record.update(_ :+ cmd).as(loop))
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
      subAgentTaskStore = new SubAgentTaskStore(tmp / "subagent-tasks"),
      voiceMutedRef = voiceMuted
    )

  private def mkTask(parentSid: String, childSid: String): SubAgentTask =
    SubAgentTask(
      taskId = childSid,
      parentSessionId = parentSid,
      agentName = "Worker",
      prompt = "tombstone test",
      description = "tombstone test task",
      status = "running",
      retryCount = 0,
      spawnedAt = System.currentTimeMillis(),
      completedAt = None,
      lastError = None,
      source = "delegate"
    )

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

  test("R1: cancel tombstone on record → respawn REFUSED (zero spawn, row settles cancelled, parent notified)") {
    val system = ActorSystem("bsup-tomb-1")
    val tmp = os.temp.dir(prefix = "bsup-tomb-1")
    PathUtil.setDataRoot(tmp / "data")
    val parentSid = "tomb-parent-1"
    val childSid = "delegate-tomb-1"
    val io =
      for
        resources <- mkResources(system, tmp)
        _ <- resources.subAgentTaskStore.recordTask(mkTask(parentSid, childSid))
        // 墓碑先行（doCancel 同序：意图落盘先于任何复活窗口）。
        _ <- IO {
          nebflow.core.delegate.DelegateRegistry.put(
            nebflow.core.delegate.DelegateRegistry.Record(
              id = childSid,
              executor = "nebflow",
              cwd = tmp.toString,
              project = None,
              taskId = None,
              parentSessionId = parentSid,
              title = "tombstone spec",
              createdAt = System.currentTimeMillis() - 60_000L
            )
          )
          nebflow.core.delegate.DelegateRegistry.markCancelRequested(childSid, "the user panel")
        }
        parentEvents <- Ref.of[IO, List[AgentCommand]](Nil)
        parentRef <- system.spawn(mkRecordingCmd(parentEvents), "tomb-parent-rec-1")
        spawnCalls <- Ref.of[IO, Int](0)
        childRef <- system.spawn(mkStopOnStopProbe, "tomb-child-1")
        supervisor <- system.spawn(
          BackoffSupervisor(
            childRef = childRef,
            childSpawnFn = (_, _) =>
              spawnCalls.update(_ + 1) *> system.spawn(mkStopOnStopProbe, s"tomb-child-respawn-1"),
            childName = childSid,
            parentRef = Some(parentRef),
            description = "tombstone test task",
            agentName = "Worker",
            subagentId = childSid,
            parentSessionId = parentSid,
            resources = resources,
            initialPrompt = "tombstone test",
            source = "delegate",
            minBackoff = 100.millis,
            maxBackoff = 200.millis
          ),
          "supervisor-tomb-1"
        )
        // 崩溃注入：child 死 → Terminated →（修前）respawn 把 cancelled 行翻回
        // restarting；（修后）墓碑闸拒绝 → 直接落 cancelled 终态。
        _ <- childRef ! AgentCommand.Stop("test-crash")
        _ <- waitUntil(5.seconds)(
          resources.subAgentTaskStore
            .loadTasks(parentSid)
            .map(_.exists(t => t.taskId == childSid && t.status == "cancelled"))
        )
        // 闸若缺席，100ms backoff 后 spawn 必已发生——给足破坏窗口再断言零 spawn。
        _ <- IO.sleep(700.millis)
        spawns <- spawnCalls.get
        events <- parentEvents.get
        tasks <- resources.subAgentTaskStore.loadTasks(parentSid)
        _ <- system.stopAll
      yield
        assertEquals(spawns, 0, "a tombstoned delegate must never be respawned in-process")
        val task = tasks.find(_.taskId == childSid).get
        assertEquals(task.status, "cancelled", "row must settle cancelled, never back to restarting")
        val ext = events.collect { case e: AgentCommand.ExternalEvent => e }
        assert(ext.nonEmpty, "parent must be notified of the refused-respawn cancel settle")
        assert(
          ext.exists(_.eventType == "cancelled"),
          s"terminal event must be cancelled: ${ext.map(_.eventType)}"
        )
        assert(
          ext.exists(_.payload.contains("cancel tombstone")),
          s"payload must name the tombstone refusal: ${ext.map(_.payload)}"
        )
    try io.unsafeRunSync()
    finally
      PathUtil.setDataRoot(originalRoot)
      os.remove.all(tmp)
  }

  test("R2 (control): no tombstone → crash respawn proceeds unchanged (gate stays silent)") {
    val system = ActorSystem("bsup-tomb-2")
    val tmp = os.temp.dir(prefix = "bsup-tomb-2")
    PathUtil.setDataRoot(tmp / "data")
    val parentSid = "tomb-parent-2"
    val childSid = "delegate-tomb-2"
    val io =
      for
        resources <- mkResources(system, tmp)
        _ <- resources.subAgentTaskStore.recordTask(mkTask(parentSid, childSid))
        // 有 registry 记录、无墓碑——闸必须保持沉默（不过度拒绝崩溃续跑）。
        _ <- IO {
          nebflow.core.delegate.DelegateRegistry.put(
            nebflow.core.delegate.DelegateRegistry.Record(
              id = childSid,
              executor = "nebflow",
              cwd = tmp.toString,
              project = None,
              taskId = None,
              parentSessionId = parentSid,
              title = "no tombstone",
              createdAt = System.currentTimeMillis() - 60_000L
            )
          )
        }
        parentEvents <- Ref.of[IO, List[AgentCommand]](Nil)
        parentRef <- system.spawn(mkRecordingCmd(parentEvents), "tomb-parent-rec-2")
        spawnCalls <- Ref.of[IO, Int](0)
        childRef <- system.spawn(mkStopOnStopProbe, "tomb-child-2")
        supervisor <- system.spawn(
          BackoffSupervisor(
            childRef = childRef,
            childSpawnFn = (_, _) =>
              spawnCalls.update(_ + 1) *> system.spawn(mkStopOnStopProbe, s"tomb-child-respawn-2"),
            childName = childSid,
            parentRef = Some(parentRef),
            description = "tombstone test task",
            agentName = "Worker",
            subagentId = childSid,
            parentSessionId = parentSid,
            resources = resources,
            initialPrompt = "tombstone test",
            source = "delegate",
            minBackoff = 100.millis,
            maxBackoff = 200.millis
          ),
          "supervisor-tomb-2"
        )
        _ <- childRef ! AgentCommand.Stop("test-crash")
        _ <- waitUntil(5.seconds)(spawnCalls.get.map(_ >= 1))
        _ <- waitUntil(5.seconds)(
          resources.subAgentTaskStore
            .loadTasks(parentSid)
            .map(_.exists(t => t.taskId == childSid && t.status == "restarting"))
        )
        _ <- IO.sleep(300.millis)
        spawns <- spawnCalls.get
        events <- parentEvents.get
        tasks <- resources.subAgentTaskStore.loadTasks(parentSid)
        _ <- system.stopAll
      yield
        assert(spawns >= 1, "without a tombstone the crash respawn must proceed (existing semantics)")
        val task = tasks.find(_.taskId == childSid).get
        assertEquals(task.status, "restarting", "respawn-in-flight row shape unchanged")
        assert(
          events.collect { case e: AgentCommand.ExternalEvent => e }.isEmpty,
          "respawn path must NOT emit a terminal parent notification"
        )
    try io.unsafeRunSync()
    finally
      PathUtil.setDataRoot(originalRoot)
      os.remove.all(tmp)
  }

  test("R3: AgentControl restart on a tombstoned session → explicit refusal, row untouched") {
    val system = ActorSystem("bsup-tomb-3")
    val tmp = os.temp.dir(prefix = "bsup-tomb-3")
    PathUtil.setDataRoot(tmp / "data")
    val parentSid = "tomb-parent-3"
    val childSid = "delegate-tomb-3"
    val io =
      for
        resources <- mkResources(system, tmp)
        _ <- resources.subAgentTaskStore.recordTask(mkTask(parentSid, childSid))
        _ <- IO {
          nebflow.core.delegate.DelegateRegistry.put(
            nebflow.core.delegate.DelegateRegistry.Record(
              id = childSid,
              executor = "nebflow",
              cwd = tmp.toString,
              project = None,
              taskId = None,
              parentSessionId = parentSid,
              title = "tombstone restart spec",
              createdAt = System.currentTimeMillis() - 60_000L
            )
          )
          nebflow.core.delegate.DelegateRegistry.markCancelRequested(childSid, "the user panel")
        }
        childRef <- system.spawn(mkStopOnStopProbe, "tomb-child-3")
        // supervisorRef 仅需非空（doRestart 前置：None = 内部不一致拒绝，先于墓碑闸）。
        supRef <- system.spawn(mkIdleEventProbe, "supervisor-tomb-3")
        rec = AgentRecord(
          sessionId = childSid,
          ref = childRef,
          kind = AgentKind.Delegate,
          rootSessionId = parentSid,
          supervisorRef = Some(supRef),
          parentSessionId = parentSid
        )
        ctx = ToolContext(projectRoot = os.pwd.toString, sessionId = Some(parentSid), sharedResources = Some(resources))
        verdict <- nebflow.core.tools.AgentControlTool.doRestart(resources, ctx, rec, "spec probe")
        tasks <- resources.subAgentTaskStore.loadTasks(parentSid)
        _ <- system.stopAll
      yield
        assert(verdict.isLeft, "restart on a tombstoned session must be refused")
        val err = verdict.swap.getOrElse(nebflow.core.tools.ToolError("<none>"))
        assert(err.message.contains("cancel tombstone"), s"refusal must name the tombstone: ${err.message}")
        val task = tasks.find(_.taskId == childSid).get
        assertEquals(task.status, "running", "refusal must not flip the row to restarting")
    try io.unsafeRunSync()
    finally
      PathUtil.setDataRoot(originalRoot)
      os.remove.all(tmp)
  }

end BackoffSupervisorTombstoneSpec
