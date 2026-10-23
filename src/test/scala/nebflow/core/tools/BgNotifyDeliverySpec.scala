package nebflow.core.tools

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.JsonObject
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, ActorSystem, AgentCommand, AgentKind, AgentRecord, Behavior, Behaviors}
import nebflow.agent.{AgentLibrary, CompactionQueueStore, SharedResources}
import nebflow.core.{FileChangeTracker, RateLimiter, SessionStore}
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.BgNotifyDelivery.Route
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk, ThinkingConfig}

import scala.concurrent.duration.*

/**
 * D1 后台任务通知投递可靠性判读（steward 批 2026-10-23）：
 *
 *  - ① 路由裁决纯函数四态（Direct / Reroot / Persist / Undeliverable）；
 *  - ② 验收「目标会话已死 → 结果仍到达活会话」：死 target + 活 root ⇒ 改道投
 *    root（metadata 标注改道事实）；
 *  - ③ 验收「重启窗口 → 复活后补投」：死 target + root 不在注册表（重启窗口形态）
 *    ⇒ 事件持久化进 root 会话注入队文件（correlationId 幂等）——该文件正是复活轮
 *    `RecoverPersistedQueues`（AgentIdle）重放的唯一入口，写入即补投契约达成；
 *    重复投递零重复（幂等）；会话已删 ⇒ 跳过（不产生垃圾文件）；
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

  private def fakeLlm: LlmHandle[IO] = new LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("not expected"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): fs2.Stream[IO, StreamChunk] =
      fs2.Stream.raiseError[IO](new RuntimeException("not expected"))

  private def mkRecordingRef(
    system: ActorSystem,
    name: String,
    record: Ref[IO, List[AgentCommand]]
  ): IO[ActorRef[AgentCommand]] =
    def loop: Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand](cmd => record.update(_ :+ cmd).as(loop))
    system.spawn(loop, name)

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

  /** 造一个「已死」的会话 ref：真 spawn 再 stop（actor 注册表退出 = isAlive false）。 */
  private def deadRef(system: ActorSystem, name: String): IO[ActorRef[AgentCommand]] =
    def loop: Behavior[AgentCommand] = Behaviors.receiveMessage[AgentCommand](_ => IO.pure(loop))
    system.spawn(loop, name).flatMap(ref => system.stop(ref).as(ref))

  private def deliver(
    system: Option[ActorSystem],
    registry: Option[Ref[IO, Map[String, AgentRecord]]],
    targetRef: Option[ActorRef[AgentCommand]],
    jobId: String,
    rootSessionId: Option[String] = Some("root-1"),
    targetSessionId: Option[String] = Some("dead-1")
  ): IO[Unit] =
    BgNotifyDelivery.deliverAgentEvent(
      targetRef = targetRef,
      targetSessionId = targetSessionId,
      rootSessionId = rootSessionId,
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

  test("① route: alive=Direct; dead+live-root=Reroot; dead+dead-root=Persist; dead self-root=Persist; no-root=Undeliverable") {
    assertEquals(BgNotifyDelivery.route(targetAlive = true, Some("s1"), Some("root"), rootAlive = true), Route.Direct)
    assertEquals(BgNotifyDelivery.route(targetAlive = false, Some("s1"), Some("root"), rootAlive = true), Route.Reroot("root"))
    assertEquals(BgNotifyDelivery.route(targetAlive = false, Some("s1"), Some("root"), rootAlive = false), Route.Persist("root"))
    // root==target（根会话自己的任务）：没有「改道投自己」的语义 ⇒ 直接持久补投
    assertEquals(BgNotifyDelivery.route(targetAlive = false, Some("root"), Some("root"), rootAlive = true), Route.Persist("root"))
    assertEquals(BgNotifyDelivery.route(targetAlive = false, Some("s1"), None, rootAlive = false), Route.Undeliverable)
  }

  // ── ② 验收：目标会话已死 → 结果仍到达活会话 ──────────────────────────

  test("② dead target + live root ⇒ the completion notification lands on the LIVE root session (rerouted)") {
    val system = ActorSystem(s"bg-notify-2-${scala.util.Random.nextInt(100000)}")
    for
      resources <- mkResources(system)
      target <- deadRef(system, "dead-target")
      received <- Ref.of[IO, List[AgentCommand]](Nil)
      root <- mkRecordingRef(system, "root-sink", received)
      _ <- resources.agentRegistry.update(_ + ("root-1" -> AgentRecord("root-1", root, AgentKind.Root, "root-1")))
      _ <- deliver(Some(system), Some(resources.agentRegistry), Some(target), jobId = "job-2")
      _ <- IO.sleep(100.millis)
      got <- received.get
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      val events = got.collect { case e: AgentCommand.ExternalEvent => e }
      assertEquals(events.size, 1, "the live root session received exactly one completion event")
      assertEquals(events.head.eventType, "completed")
      assertEquals(events.head.payload.contains("spec-output"), true, "the task output rides the rerouted event")
      assertEquals(
        events.head.metadata("bgNotifyRerouted").map(_.asBoolean).getOrElse(None),
        Some(true),
        "the event is marked as rerouted for consumers"
      )
      assertEquals(events.head.metadata("bgTaskId").flatMap(_.asString), Some("job-2"))
  }

  // ── ③ 验收：重启窗口 → 复活后补投 ────────────────────────────────────

  test("③ dead target + root not live (restart window) ⇒ pending-redelivery marker persisted; replay intake sees it; idempotent; deleted session skipped") {
    val system = ActorSystem(s"bg-notify-3-${scala.util.Random.nextInt(100000)}")
    for
      resources <- mkResources(system)
      target <- deadRef(system, "dead-target-3")
      // 重启窗口形态：root 会话文件存在（sessionStore 有行）但无活 actor 注册行。
      meta <- resources.sessionStore.createSession("root-probe")
      _ <- deliver(Some(system), Some(resources.agentRegistry), Some(target), jobId = "job-3", rootSessionId = Some(meta.id))
      persisted <- CompactionQueueStore.load(meta.id)
      // 幂等：同 job 二次交付零重复。
      _ <- deliver(Some(system), Some(resources.agentRegistry), Some(target), jobId = "job-3", rootSessionId = Some(meta.id))
      persisted2 <- CompactionQueueStore.load(meta.id)
      // 会话已删：不产生无人读的垃圾文件。
      _ <- deliver(Some(system), Some(resources.agentRegistry), Some(target), jobId = "job-4", rootSessionId = Some("deleted-root"))
      deleted <- CompactionQueueStore.load("deleted-root")
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      val events = persisted.map(_.events).getOrElse(Nil)
      assertEquals(events.length, 1, "exactly one pending-redelivery event persisted")
      assertEquals(events.head.correlationId, Some("bg-redelivery-job-3"), "idempotence key = bg-redelivery-<jobId>")
      assertEquals(events.head.eventType, "completed", "the persisted marker carries the completion event")
      assertEquals(events.head.payload.contains("spec-output"), true, "the output reference rides the marker")
      assertEquals(
        events.head.metadata("bgNotifyDeferred").map(_.asBoolean).getOrElse(None),
        Some(true),
        "the marker is marked as deferred (revival replay face)"
      )
      assertEquals(persisted2.map(_.events).getOrElse(Nil).length, 1, "second delivery is a no-op (correlationId idempotent)")
      assertEquals(deleted, None, "a deleted root session is skipped (no garbage file)")
  }

  // ── ④ 无 actorSystem（harness）⇒ 旧行为直投 ──────────────────────────

  test("④ no actorSystem (spec harness) ⇒ direct delivery to the target ref, legacy semantics, no reroute marks") {
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
      assertEquals(events.head.metadata("bgNotifyRerouted"), None, "no reroute marks on the legacy path")
      assertEquals(events.head.metadata("bgNotifyDeferred"), None, "no deferred marks on the legacy path")
  }

end BgNotifyDeliverySpec
