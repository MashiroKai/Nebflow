package nebflow.core.tools

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.JsonObject
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, ActorSystem, AgentCommand, AgentRecord, Behavior, Behaviors}
import nebflow.agent.{AgentLibrary, CompactionQueueStore, SharedResources}
import nebflow.core.{FileChangeTracker, RateLimiter, SessionStore}
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.BgNotifyDelivery.Route
import nebflow.core.tools.BgSessionActivator.ActivationResult
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk, ThinkingConfig}

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.*

/**
 * D1 后台任务通知投递可靠性判读（steward 批 2026-10-23；**作者裁定 09:46**
 * 「目标会话死了就激活发送，不用发给root」——改道投 root 方案已废）：
 *
 *  - ① 路由裁决纯函数四态（Direct / Revive / Persist / Undeliverable）；
 *  - ② 验收「目标会话已死 → **该会话被激活**且通知注入**该会话**」：死 target +
 *    激活面装配 ⇒ 激活面被以目标会话 id 调用、标记持久化进**目标会话**注入队
 *    （correlationId 幂等）、通知进入复活后的该会话（活 target ⇒ 直投零漂移，
 *    激活面零调用）；
 *  - ③ 验收「重启窗口 → 复活后补投**到目标会话**」：激活面未装配（重启窗口形态）
 *    ⇒ 「待补投」标记落**目标会话自己**的注入队文件（复活轮
 *    `RecoverPersistedQueues` 重放补投给它）；同族恢复腿所有者（Deferred）同腿；
 *    会话已删 ⇒ 跳过（不产生垃圾文件）+ UnknownSession 审计收口；
 *  - ④ 无 actorSystem（harness）⇒ 旧行为直投（语义零漂移）。
 */
class BgNotifyDeliverySpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-bg-notify"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    os.remove.all(tempRoot / "sessions")
    os.remove.all(tempRoot / "subagent-tasks")
    BgSessionActivator.clear()

  override def afterEach(context: munit.AfterEach): Unit =
    BgSessionActivator.clear()

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

  /** 造一个「已死」的会话 ref：真 spawn 再 stop（actor 注册表退出 = isAlive false）。 */
  private def deadRef(system: ActorSystem, name: String): IO[ActorRef[AgentCommand]] =
    def loop: Behavior[AgentCommand] = Behaviors.receiveMessage[AgentCommand](_ => IO.pure(loop))
    system.spawn(loop, name).flatMap(ref => system.stop(ref).as(ref))

  private def deliver(
    system: Option[ActorSystem],
    registry: Option[Ref[IO, Map[String, AgentRecord]]],
    targetRef: Option[ActorRef[AgentCommand]],
    jobId: String,
    targetSessionId: Option[String] = Some("dead-1")
  ): IO[Unit] =
    BgNotifyDelivery.deliverAgentEvent(
      targetRef = targetRef,
      targetSessionId = targetSessionId,
      system = system,
      registry = registry,
      projectName = None,
      flowNodeId = None,
      jobId = jobId,
      source = "background-task",
      eventType = "completed",
      payload = "[Background task completed] \"spec job\":\nspec-output",
      metadata = JsonObject("description" -> io.circe.Json.fromString("spec job"))
    )

  // ── ① 路由裁决纯函数 ─────────────────────────────────────────────────

  test("① route: alive=Direct; dead+activator=Revive; dead+no-activator=Persist; unknown session=Undeliverable") {
    assertEquals(BgNotifyDelivery.route(targetAlive = true, activatorAvailable = true), Route.Direct)
    assertEquals(BgNotifyDelivery.route(targetAlive = true, activatorAvailable = false), Route.Direct)
    // 作者裁定：目标死了激活目标自己——不再有 Reroot 腿。
    assertEquals(BgNotifyDelivery.route(targetAlive = false, activatorAvailable = true), Route.Revive)
    assertEquals(BgNotifyDelivery.route(targetAlive = false, activatorAvailable = false), Route.Persist)
    assertEquals(
      BgNotifyDelivery.route(targetAlive = false, activatorAvailable = true, sessionKnown = false),
      Route.Undeliverable
    )
  }

  // ── ② 验收：目标会话已死 → 该会话被激活且通知注入该会话 ──────────────

  test("② dead target ⇒ the TARGET session is activated and the notification lands in THAT session (not root)") {
    val system = ActorSystem(s"bg-notify-2-${scala.util.Random.nextInt(100000)}")
    val activations = ListBuffer.empty[(String, AgentCommand.ExternalEvent)]
    for
      resources <- mkResources(system)
      target <- deadRef(system, "dead-target-2")
      // 目标会话真实存在（SessionStore 有行）——激活面的存在性前提。
      meta <- resources.sessionStore.createSession("bg-target-2")
      revivedReceived <- Ref.of[IO, List[AgentCommand]](Nil)
      revived <- mkRecordingRef(system, "revived-target", revivedReceived)
      // 激活面 fake：生产契约 = 存在守卫 + 标记持久化（correlationId 幂等）+ 恢复
      // spawn（重放交付）——这里以「append 进目标注入队 + 把事件投给复活会话」模拟。
      _ = BgSessionActivator.install((sid, ev) =>
        BgInboxPort.append(sid, ev) *>
          IO { activations += ((sid, ev)) } *>
          (revived ! ev).as(BgSessionActivator.Activated: ActivationResult)
      )
      _ <- deliver(
        Some(system),
        Some(resources.agentRegistry),
        Some(target),
        jobId = "job-2",
        targetSessionId = Some(meta.id)
      )
      _ <- IO.sleep(100.millis)
      got <- revivedReceived.get
      persisted <- CompactionQueueStore.load(meta.id)
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(activations.map(_._1).toList, List(meta.id), "the TARGET session (not root) is what gets activated")
      val events = got.collect { case e: AgentCommand.ExternalEvent => e }
      assertEquals(events.size, 1, "the notification lands in the activated target session")
      assertEquals(events.head.payload.contains("spec-output"), true, "the task output rides the event")
      assertEquals(
        events.head.metadata("bgTaskId").flatMap(_.asString),
        Some("job-2"),
        "the event carries the job id"
      )
      val persistedEvents = persisted.map(_.events).getOrElse(Nil)
      assertEquals(
        persistedEvents.length,
        1,
        "the activation face persisted the marker into the target queue (spawn replay carries it)"
      )
      assertEquals(
        persistedEvents.head.correlationId,
        Some("bg-redelivery-job-2"),
        "idempotence key = bg-redelivery-<jobId>"
      )
    end for
  }

  test("② live target ⇒ direct delivery, zero drift, activator never consulted") {
    val system = ActorSystem(s"bg-notify-2b-${scala.util.Random.nextInt(100000)}")
    val activations = ListBuffer.empty[String]
    for
      resources <- mkResources(system)
      received <- Ref.of[IO, List[AgentCommand]](Nil)
      target <- mkRecordingRef(system, "live-target", received)
      meta <- resources.sessionStore.createSession("bg-target-live")
      _ = BgSessionActivator.install((sid, _) =>
        IO { activations += sid; BgSessionActivator.Activated: ActivationResult }
      )
      _ <- deliver(
        Some(system),
        Some(resources.agentRegistry),
        Some(target),
        jobId = "job-2b",
        targetSessionId = Some(meta.id)
      )
      _ <- IO.sleep(80.millis)
      got <- received.get
      persisted <- CompactionQueueStore.load(meta.id)
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      val events = got.collect { case e: AgentCommand.ExternalEvent => e }
      assertEquals(events.size, 1, "the live target got exactly one direct completion event")
      assertEquals(events.head.metadata("bgNotifyRedelivered"), None, "no redelivery marks on the direct path")
      assertEquals(activations.toList, Nil, "the activator is never consulted for a live target")
      assertEquals(persisted, None, "no pending-redelivery marker for a live target")
    end for
  }

  // ── ③ 验收：重启窗口 → 复活后补投到目标会话 ──────────────────────────

  test(
    "③ restart window (no activator) ⇒ pending-redelivery marker persisted to the TARGET session's own queue; idempotent; deleted session skipped"
  ) {
    val system = ActorSystem(s"bg-notify-3-${scala.util.Random.nextInt(100000)}")
    for
      resources <- mkResources(system)
      target <- deadRef(system, "dead-target-3")
      // 重启窗口形态：目标会话文件存在（sessionStore 有行）但 actor 已死且无激活面。
      meta <- resources.sessionStore.createSession("bg-target-3")
      _ <- deliver(
        Some(system),
        Some(resources.agentRegistry),
        Some(target),
        jobId = "job-3",
        targetSessionId = Some(meta.id)
      )
      persisted <- CompactionQueueStore.load(meta.id)
      // 幂等：同 job 二次交付零重复。
      _ <- deliver(
        Some(system),
        Some(resources.agentRegistry),
        Some(target),
        jobId = "job-3",
        targetSessionId = Some(meta.id)
      )
      persisted2 <- CompactionQueueStore.load(meta.id)
      // 会话已删：不产生无人读的垃圾文件。
      _ <- deliver(
        Some(system),
        Some(resources.agentRegistry),
        Some(target),
        jobId = "job-4",
        targetSessionId = Some("deleted-target")
      )
      deleted <- CompactionQueueStore.load("deleted-target")
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      val events = persisted.map(_.events).getOrElse(Nil)
      assertEquals(events.length, 1, "exactly one pending-redelivery event persisted")
      assertEquals(events.head.correlationId, Some("bg-redelivery-job-3"), "idempotence key = bg-redelivery-<jobId>")
      assertEquals(events.head.eventType, "completed", "the persisted marker carries the completion event")
      assertEquals(events.head.payload.contains("spec-output"), true, "the output reference rides the marker")
      assertEquals(
        events.head.metadata("bgNotifyRedelivered").map(_.asBoolean).getOrElse(None),
        Some(true),
        "the marker is marked as redelivery (target-session revival replay face)"
      )
      assertEquals(
        events.head.metadata("bgNotifyToSession").flatMap(_.asString),
        Some(meta.id),
        "the marker names the TARGET session as its redelivery destination"
      )
      assertEquals(
        persisted2.map(_.events).getOrElse(Nil).length,
        1,
        "second delivery is a no-op (correlationId idempotent)"
      )
      assertEquals(deleted, None, "a deleted target session is skipped (no garbage file)")
    end for
  }

  test(
    "③ Deferred (family revival owned by supervisor legs) ⇒ marker persisted by the delivery face; UnknownSession (deleted) ⇒ nothing persisted"
  ) {
    val system = ActorSystem(s"bg-notify-3b-${scala.util.Random.nextInt(100000)}")
    val attempts = ListBuffer.empty[String]
    for
      resources <- mkResources(system)
      target <- deadRef(system, "dead-target-3b")
      meta <- resources.sessionStore.createSession("bg-target-3b")
      _ = BgSessionActivator.install((sid, _) =>
        IO { attempts += sid }.as(
          if sid == "deleted-target" then BgSessionActivator.UnknownSession: ActivationResult
          else BgSessionActivator.Deferred("revival owned by supervisor respawn"): ActivationResult
        )
      )
      _ <- deliver(
        Some(system),
        Some(resources.agentRegistry),
        Some(target),
        jobId = "job-3b",
        targetSessionId = Some(meta.id)
      )
      persisted <- CompactionQueueStore.load(meta.id)
      _ <- deliver(
        Some(system),
        Some(resources.agentRegistry),
        Some(target),
        jobId = "job-3c",
        targetSessionId = Some("deleted-target")
      )
      deleted <- CompactionQueueStore.load("deleted-target")
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      val events = persisted.map(_.events).getOrElse(Nil)
      assertEquals(events.length, 1, "the Deferred leg persists the marker (delivery face owns the write)")
      assertEquals(
        events.head.correlationId,
        Some("bg-redelivery-job-3b"),
        "the deferred marker keeps the idempotence key"
      )
      assertEquals(
        attempts.toList,
        List(meta.id, "deleted-target"),
        "the activator was consulted for both dead targets"
      )
      assertEquals(deleted, None, "UnknownSession ⇒ nothing persisted anywhere (no garbage file)")
    end for
  }

  // ── ④ 无 actorSystem（harness）⇒ 旧行为直投 ──────────────────────────

  test("④ no actorSystem (spec harness) ⇒ direct delivery to the target ref, legacy semantics, no activation marks") {
    for
      received <- Ref.of[IO, List[AgentCommand]](Nil)
      system = ActorSystem(s"bg-notify-4-${scala.util.Random.nextInt(100000)}")
      target <- mkRecordingRef(system, "harness-target", received)
      _ <- deliver(None, None, Some(target), jobId = "job-4h")
      _ <- IO.sleep(80.millis)
      got <- received.get
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      val events = got.collect { case e: AgentCommand.ExternalEvent => e }
      assertEquals(events.size, 1, "harness (no system) keeps the legacy direct-delivery face")
      assertEquals(events.head.metadata("bgNotifyRedelivered"), None, "no redelivery marks on the legacy path")
  }

end BgNotifyDeliverySpec
