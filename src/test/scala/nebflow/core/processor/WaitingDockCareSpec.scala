package nebflow.core.processor

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, ActorSystem, AgentCommand, AgentEvent, AgentKind, AgentRecord, AgentStatus, Behavior, Behaviors}
import nebflow.agent.{AgentLibrary, SharedResources}
import nebflow.core.FileChangeTracker
import nebflow.core.compact.HistoryArchiver
import nebflow.core.delegate.MergeQueue
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.gateway.WsHub
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk, SubAgentTask, ThinkingConfig}

import scala.concurrent.duration.*

/**
 * D3-④ Waiting 停靠态活性看护判读（steward 批 2026-10-23）：
 *
 *  - ① 验收「Waiting 被看护」：registry 行 Waiting（等合并轮停靠）而 actor 已死
 *    = 明确死亡事件 ⇒ 本拍看护：合并队列前进（条目按被拒语义留队待重试 + 窗释放
 *    ——后续条目即刻可入）+ 任务台账翻 failed + taskStuck WS 广播 + 升级投活 root；
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

  private def entry(
    system: ActorSystem,
    delegateId: String,
    projectKey: String,
    child: ActorRef[AgentCommand],
    sup: ActorRef[AgentEvent]
  ): MergeQueue.Entry =
    MergeQueue.Entry(
      delegateId = delegateId,
      branch = s"delegate/$delegateId",
      baseWs = tempRoot.toString,
      baseBranch = "main",
      worktree = false,
      projectKey = projectKey,
      taskId = Some("31"),
      parentSessionId = "root-1",
      description = s"task for $delegateId",
      readyAt = 1_000L,
      childRef = child,
      supervisorRef = sup
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

  test("① docked Waiting session whose actor died: queue advances (kept pending retry), task row failed, escalation reaches the live root") {
    val system = ActorSystem(s"wdc-1-${scala.util.Random.nextInt(100000)}")
    val key = s"p-wdc-1-${scala.util.Random.nextInt(100000)}"
    for
      resources <- mkResources(system)
      rootReceived <- Ref.of[IO, List[AgentCommand]](Nil)
      root <- mkRecordingRef(system, "root-sink", rootReceived)
      _ <- resources.agentRegistry.update(_ + ("root-1" -> AgentRecord("root-1", root, AgentKind.Root, "root-1")))
      // 停靠会话：先活（提醒已发、窗持）→ 死（dock 期崩溃且监督者同亡的形态）。
      received <- Ref.of[IO, List[AgentCommand]](Nil)
      dockedRef <- mkRecordingRef(system, "docked-child", received)
      sup <- mkSup(system, "docked-sup")
      e1 = entry(system, "d-w1", key, dockedRef, sup)
      _ <- IO { MergeQueue.enqueue(e1) } // 注入 + 窗持（子活）
      _ <- IO.sleep(80.millis)
      _ <- system.stop(dockedRef)
      _ <- resources.agentRegistry.update(
        _ + ("d-w1" -> AgentRecord("d-w1", dockedRef, AgentKind.Delegate, "root-1", parentSessionId = "root-1", status = AgentStatus.Waiting))
      )
      _ <- recordTask(resources, "d-w1")
      ws <- Ref.of[IO, List[Json]](Nil)
      wsHub = new WsHub()
      _ <- wsHub.registerListener(json => ws.update(_ :+ json))
      _ <- TaskStuckWatcher.scan(resources, wsHub, thresholdMs = 10 * 60 * 1000L)
      kept = MergeQueue.find("d-w1")
      task <- resources.subAgentTaskStore.findByTaskId("d-w1")
      // 窗已释放：后续条目即刻可入（不再陪站）。
      nextReceived <- Ref.of[IO, List[AgentCommand]](Nil)
      nextChild <- mkRecordingRef(system, "next-child", nextReceived)
      nextSup <- mkSup(system, "next-sup")
      _ <- IO { MergeQueue.enqueue(entry(system, "d-w2", key, nextChild, nextSup)) }
      _ <- IO.sleep(80.millis)
      nextGot <- nextReceived.get
      rootGot <- rootReceived.get
      wsGot <- ws.get
      // 终态收尾：d-w2 出队（否则其死司机条目漏进下一拍的 care 扫描）。
      _ <- IO { MergeQueue.onTerminal("d-w2") }
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(kept.map(_.pendingRetry), Some(true), "the dead dock's queue entry is kept pending retry (skipped, not dropped)")
      assertEquals(task.map(_.status), Some("failed"), "the task ledger row is flipped to failed (panel-visible terminal)")
      assert(task.flatMap(_.lastError).exists(_.contains("waiting dock orphaned")), "the ledger carries the death reason")
      val escalations = rootGot.collect { case e: AgentCommand.ExternalEvent => e }
      assertEquals(escalations.size, 1, "exactly one escalation event reached the live root (①)")
      assertEquals(escalations.head.source, "waiting-dock-care")
      assert(wsGot.exists(_.hcursor.get[String]("action").toOption.contains("waiting-dock-dead")), "the death is WS-broadcast for humans")
      assertEquals(nextGot.size, 1, "the window was released: the next entry got its merge turn immediately")
  }

  // ── ② 负控：活停靠零动作 ─────────────────────────────────────────────

  test("② LIVE docked Waiting session: zero action — no Stop, no ledger flip, queue untouched (stale lastActivityMs is NOT a criterion)") {
    val system = ActorSystem(s"wdc-2-${scala.util.Random.nextInt(100000)}")
    val key = s"p-wdc-2-${scala.util.Random.nextInt(100000)}"
    for
      resources <- mkResources(system)
      rootReceived <- Ref.of[IO, List[AgentCommand]](Nil)
      root <- mkRecordingRef(system, "root-sink-2", rootReceived)
      _ <- resources.agentRegistry.update(_ + ("root-1" -> AgentRecord("root-1", root, AgentKind.Root, "root-1")))
      dockReceived <- Ref.of[IO, List[AgentCommand]](Nil)
      dockedRef <- mkRecordingRef(system, "docked-child-2", dockReceived) // 活停靠
      sup <- mkSup(system, "docked-sup-2")
      _ <- IO { MergeQueue.enqueue(entry(system, "d-v1", key, dockedRef, sup)) }
      _ <- IO.sleep(80.millis)
      // 初始合并轮提醒是既有队列语义（活子即投）——记基线后清空，看护扫描必须零新增。
      baseline <- dockReceived.get
      _ <- IO(assert(baseline.size == 1, "sanity: the merge-turn reminder was injected while the dock was alive"))
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
      ws <- Ref.of[IO, List[Json]](Nil)
      wsHub = new WsHub()
      _ <- wsHub.registerListener(json => ws.update(_ :+ json))
      _ <- TaskStuckWatcher.scan(resources, wsHub, thresholdMs = 10 * 60 * 1000L)
      dockGot <- dockReceived.get
      task <- resources.subAgentTaskStore.findByTaskId("d-v1")
      kept = MergeQueue.find("d-v1")
      rootGot <- rootReceived.get
      wsGot <- ws.get
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(dockGot, Nil, "no Stop / no command is sent to a live docked session")
      assertEquals(task.map(_.status), Some("ready-to-merge"), "the task ledger row is untouched")
      assertEquals(kept.map(_.pendingRetry), Some(false), "the queue entry is untouched")
      assertEquals(rootGot, Nil, "no escalation for a live dock")
      assert(!wsGot.exists(_.hcursor.get[String]("action").toOption.contains("waiting-dock-dead")), "no death broadcast")
  }

end WaitingDockCareSpec
