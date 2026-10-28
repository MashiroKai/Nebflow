package nebflow.core.processor

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{
  ActorRef,
  ActorSystem,
  AgentCommand,
  AgentEvent,
  AgentKind,
  AgentRecord,
  AgentStatus,
  Behavior,
  Behaviors
}
import nebflow.agent.{AgentLibrary, SharedResources}
import nebflow.core.FileChangeTracker
import nebflow.core.compact.HistoryArchiver
import nebflow.core.delegate.MergeQueue
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.gateway.WsHub
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.{
  FallbackAttempt,
  LlmHandle,
  LlmRequest,
  LlmResponse,
  PathUtil,
  StreamChunk,
  SubAgentTask,
  ThinkingConfig
}

import scala.concurrent.duration.*

/**
 * D3-④ Waiting 停靠态活性看护判读（steward 批 2026-10-23；台账 #896 交通引导员制
 * 2026-10-26 改关账语义）：
 *
 *  - ① 验收「Waiting 被看护」：registry 行 Waiting（等合并轮停靠）而 actor 已死
 *    = 明确死亡事件 ⇒ 本拍看护：合并队列成员关账 **owner-gone**（账实核对先行——
 *    期间被并则关 merged；无留队待重试语义，处置归 dispatcher）+ 任务台账翻 failed
 *    + taskStuck WS 广播 + 升级投活 root；窗随即放行后继（单持有人不陪葬）；
 *  - ② 负控：**活**停靠零动作（等待屏障语义保持——不发 Stop、不翻台账、不动队列）；
 *  - 判据 = actor 活性信号，**零时间判据**（活停靠即使 lastActivityMs 很旧也不判）。
 */
class WaitingDockCareSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val watchdogLogTmp: os.Path = os.temp.dir(prefix = "waiting-dock-care-events")

  private val tempRoot: os.Path = os.pwd / "target" / "test-waiting-dock-care"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  override def beforeAll(): Unit = WatchdogEventLog.setLogDirForTest(watchdogLogTmp.toNIO)

  override def afterAll(): Unit =
    WatchdogEventLog.resetLogDirForTest()
    PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    MergeQueue.reset()

  override def afterEach(context: munit.AfterEach): Unit =
    MergeQueue.reset()
    MergeQueue.installLivenessProbe(_ => IO.pure(true))
    MergeQueue.OwnerGonePort.clear()

  private def fakeLlm: LlmHandle[IO] = new LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("not expected"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): fs2.Stream[IO, StreamChunk] =
      fs2.Stream.raiseError[IO](new RuntimeException("not expected"))

  private def mkResources(system: ActorSystem): IO[SharedResources] =
    for
      dispatcher <- cats.effect.std.Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
      tmp <- IO(os.temp.dir())
    yield SharedResources(
      llm = fakeLlm,
      dispatcher = dispatcher,
      sessionStore = SessionStore(tempRoot / "sessions", tempRoot / "tasks"),
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

  private def mkRecordingRef(
    system: ActorSystem,
    name: String,
    record: Ref[IO, List[AgentCommand]]
  ): IO[ActorRef[AgentCommand]] =
    def loop: Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand](cmd => record.update(_ :+ cmd).as(loop))
    system.spawn(loop, name)

  private def mkSup(system: ActorSystem, name: String): IO[ActorRef[AgentEvent]] =
    def loop: Behavior[AgentEvent] = Behaviors.receiveMessage[AgentEvent](_ => IO.pure(loop))
    system.spawn(loop, name)

  /** 真 git 基座（main@init——净空闸/账实核对的真 git 面；分支未在仓 ⇒ 不命中账实核对）。 */
  private def mkBaseRepo(dir: os.Path): os.Path =
    os.remove.all(dir)
    os.makeDir.all(dir)
    os.proc("git", "init", "-b", "main", dir.toString).call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    def g(args: String*): os.CommandResult =
      os.proc(Seq("git", "-C", dir.toString) ++ args).call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    g("config", "user.email", "spec@nebflow.local")
    g("config", "user.name", "spec")
    g("commit", "--allow-empty", "-m", "init")
    dir

  private def member(
    system: ActorSystem,
    delegateId: String,
    projectKey: String,
    child: ActorRef[AgentCommand],
    sup: ActorRef[AgentEvent],
    baseWs: os.Path
  ): MergeQueue.Member =
    MergeQueue.Member(
      delegateId = delegateId,
      branch = s"delegate/$delegateId",
      baseWs = baseWs.toString,
      baseBranch = "main",
      worktree = false,
      projectKey = projectKey,
      taskId = Some("31"),
      parentSessionId = "root-1",
      description = s"task for $delegateId",
      readyAt = 1_000L,
      channelKind = "internal",
      internal = Some(MergeQueue.InternalLeg(child, sup))
    )

  private def recordTask(resources: SharedResources, taskId: String): IO[Unit] =
    resources.subAgentTaskStore.recordTask(
      SubAgentTask(
        taskId = taskId,
        parentSessionId = "root-1",
        agentName = "nebflow",
        prompt = "brief",
        description = s"task for $taskId",
        status = "ready-to-merge",
        retryCount = 0,
        spawnedAt = 1L,
        completedAt = None,
        lastError = None,
        source = "delegate"
      )
    )

  // ── ① 验收：Waiting 死停靠被看护 ─────────────────────────────────────

  test(
    "① docked Waiting session whose actor died: membership closed owner-gone, task row failed, escalation reaches the live root, window released"
  ) {
    val system = ActorSystem(s"wdc-1-${scala.util.Random.nextInt(100000)}")
    val key = s"p-wdc-1-${scala.util.Random.nextInt(100000)}"
    val baseRepo = mkBaseRepo(tempRoot / s"base-$key")
    for
      resources <- mkResources(system)
      rootReceived <- Ref.of[IO, List[AgentCommand]](Nil)
      root <- mkRecordingRef(system, "root-sink", rootReceived)
      _ <- resources.agentRegistry.update(_ + ("root-1" -> AgentRecord("root-1", root, AgentKind.Root, "root-1")))
      // 停靠会话：先活（放行通知已发、窗持）→ 死（dock 期崩溃且监督者同亡的形态）。
      received <- Ref.of[IO, List[AgentCommand]](Nil)
      dockedRef <- mkRecordingRef(system, "docked-child", received)
      sup <- mkSup(system, "docked-sup")
      m1 = member(system, "d-w1", key, dockedRef, sup, baseRepo)
      _ <- IO { MergeQueue.enqueue(m1) } // 放行 = 通知（子活）→ 窗持
      _ <- IO.sleep(80.millis)
      _ <- received.get.map(l =>
        assert(l.size == 1, "sanity: the merge-turn brief was injected while the dock was alive")
      )
      _ <- system.stop(dockedRef)
      _ <- resources.agentRegistry.update(
        _ + ("d-w1" -> AgentRecord(
          "d-w1",
          dockedRef,
          AgentKind.Delegate,
          "root-1",
          parentSessionId = "root-1",
          status = AgentStatus.Waiting
        ))
      )
      _ <- recordTask(resources, "d-w1")
      escalations <- Ref.of[IO, List[(String, String)]](Nil)
      _ = MergeQueue.OwnerGonePort.install((m, reason) => escalations.update(_ :+ (m.delegateId -> reason)))
      ws <- Ref.of[IO, List[Json]](Nil)
      wsHub = new WsHub()
      _ <- wsHub.registerListener(json => ws.update(_ :+ json))
      _ <- TaskStuckWatcher.scan(resources, wsHub, thresholdMs = 10 * 60 * 1000L)
      kept = MergeQueue.find("d-w1")
      task <- resources.subAgentTaskStore.findByTaskId("d-w1")
      escGot <- escalations.get
      // 窗已放：后续成员即刻放行（单持有人不陪葬——不再有陪站/留队待重试）。
      nextReceived <- Ref.of[IO, List[AgentCommand]](Nil)
      nextChild <- mkRecordingRef(system, "next-child", nextReceived)
      nextSup <- mkSup(system, "next-sup")
      _ <- IO { MergeQueue.enqueue(member(system, "d-w2", key, nextChild, nextSup, baseRepo)) }
      _ <- IO.sleep(80.millis)
      nextGot <- nextReceived.get
      rootGot <- rootReceived.get
      wsGot <- ws.get
      // 终态收尾：d-w2 以被拒回合关账（避免其 owner-gone 升级混入计数）。
      _ = MergeQueue.onTerminal("d-w2", Some(MergeQueue.RoundOutcome.Refused("spec teardown")))
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(
        kept.map(_.state),
        Some(MergeQueue.State.OwnerGone),
        "the dead dock's membership is closed owner-gone (needs dispatcher handling — never kept pending, never dropped)"
      )
      assert(
        kept.flatMap(_.closeReason).exists(_.contains("waiting dock orphaned")),
        "the ledger carries the death reason"
      )
      assertEquals(
        task.map(_.status),
        Some("failed"),
        "the task ledger row is flipped to failed (panel-visible terminal)"
      )
      assert(
        task.flatMap(_.lastError).exists(_.contains("waiting dock orphaned")),
        "the ledger carries the death reason"
      )
      assertEquals(escGot.map(_._1), List("d-w1"), "exactly one queue-side owner-gone escalation")
      val escalationsRoot = rootGot.collect { case e: AgentCommand.ExternalEvent => e }
      assertEquals(escalationsRoot.size, 1, "exactly one escalation event reached the live root (①)")
      assertEquals(escalationsRoot.head.source, "waiting-dock-care")
      assert(
        wsGot.exists(_.hcursor.get[String]("action").toOption.contains("waiting-dock-dead")),
        "the death is WS-broadcast for humans"
      )
      assertEquals(nextGot.size, 1, "the window was released: the next member got its merge turn immediately")
    end for
  }

  // ── ② 负控：活停靠零动作 ─────────────────────────────────────────────

  test(
    "② LIVE docked Waiting session: zero action — no Stop, no ledger flip, queue untouched (stale lastActivityMs is NOT a criterion)"
  ) {
    val system = ActorSystem(s"wdc-2-${scala.util.Random.nextInt(100000)}")
    val key = s"p-wdc-2-${scala.util.Random.nextInt(100000)}"
    val baseRepo = mkBaseRepo(tempRoot / s"base-$key")
    for
      resources <- mkResources(system)
      rootReceived <- Ref.of[IO, List[AgentCommand]](Nil)
      root <- mkRecordingRef(system, "root-sink-2", rootReceived)
      _ <- resources.agentRegistry.update(_ + ("root-1" -> AgentRecord("root-1", root, AgentKind.Root, "root-1")))
      dockReceived <- Ref.of[IO, List[AgentCommand]](Nil)
      dockedRef <- mkRecordingRef(system, "docked-child-2", dockReceived) // 活停靠
      sup <- mkSup(system, "docked-sup-2")
      _ <- IO { MergeQueue.enqueue(member(system, "d-v1", key, dockedRef, sup, baseRepo)) }
      _ <- IO.sleep(80.millis)
      // 放行 = 通知是既有队列语义（活子即投）——记基线后清空，看护扫描必须零新增。
      baseline <- dockReceived.get
      _ <- IO(assert(baseline.size == 1, "sanity: the merge-turn brief was injected while the dock was alive"))
      _ <- dockReceived.set(Nil)
      _ <- resources.agentRegistry.update(
        _ + ("d-v1" -> AgentRecord(
          "d-v1",
          dockedRef,
          AgentKind.Delegate,
          "root-1",
          parentSessionId = "root-1",
          status = AgentStatus.Waiting,
          // 故意放一个很旧的 lastActivityMs：时间不得成为判据（红线）。
          lastActivityMs = 1L
        ))
      )
      _ <- recordTask(resources, "d-v1")
      escalations <- Ref.of[IO, List[(String, String)]](Nil)
      _ = MergeQueue.OwnerGonePort.install((m, reason) => escalations.update(_ :+ (m.delegateId -> reason)))
      ws <- Ref.of[IO, List[Json]](Nil)
      wsHub = new WsHub()
      _ <- wsHub.registerListener(json => ws.update(_ :+ json))
      _ <- TaskStuckWatcher.scan(resources, wsHub, thresholdMs = 10 * 60 * 1000L)
      dockGot <- dockReceived.get
      task <- resources.subAgentTaskStore.findByTaskId("d-v1")
      kept = MergeQueue.find("d-v1")
      escGot <- escalations.get
      rootGot <- rootReceived.get
      wsGot <- ws.get
      _ = MergeQueue.onTerminal("d-v1", Some(MergeQueue.RoundOutcome.Refused("spec teardown")))
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(dockGot, Nil, "no Stop / no command is sent to a live docked session")
      assertEquals(task.map(_.status), Some("ready-to-merge"), "the task ledger row is untouched")
      assertEquals(
        kept.map(_.state),
        Some(MergeQueue.State.TurnGranted),
        "the queue membership is untouched (still the single window holder)"
      )
      assertEquals(escGot, Nil, "no owner-gone escalation for a live dock")
      assertEquals(rootGot, Nil, "no escalation for a live dock")
      assert(
        !wsGot.exists(_.hcursor.get[String]("action").toOption.contains("waiting-dock-dead")),
        "no death broadcast"
      )
    end for
  }

end WaitingDockCareSpec
