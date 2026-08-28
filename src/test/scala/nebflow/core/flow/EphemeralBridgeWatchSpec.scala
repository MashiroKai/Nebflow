package nebflow.core.flow

import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, Behavior, Behaviors}
import nebflow.agent.*
import nebflow.core.FileChangeTracker
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * AgentControl spec §3.3 / §6 B3：EphemeralAgentRunner bridge 的 death-watch。
 *
 * 挂起 LLM（Stream.never）的 ephemeral agent 被直接 Stop（AgentControl cancel
 * 降级路径）→ bridge 收 Terminated → deferred.complete(Left("cancelled")) →
 * runner 正常清理并回复调用方——不再永久挂起（fiber 泄漏回归钉死）。
 */
class EphemeralBridgeWatchSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 30.seconds

  /** 永久挂起的 LLM——模拟卡死的 ephemeral agent turn。 */
  private val neverLlm = new LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected in this test"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.never[IO]

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
      llm = neverLlm,
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

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 50.millis)(
    cond: IO[Boolean]
  ): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true  => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError("waitUntil: condition not met in time"))
          else IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  test("B3: Stop on a hung ephemeral agent → runner returns cancelled to the caller within 5s (no fiber leak)") {
    val system = ActorSystem("eph-bridge-watch")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp / "data")
    try
      val program = for
        resources <- mkResources(system, tmp)
        callerEvents <- Ref.of[IO, List[AgentCommand]](Nil)
        callerRef <- system.spawn(mkRecordingCmd(callerEvents), "eph-caller")
        probeDef = AgentDef(name = "Probe", description = "eph probe", tools = List("Read"), systemPrompt = "")
        runner <- system.spawn(EphemeralAgentRunner(resources, None), "eph-runner")
        t0 <- IO(System.currentTimeMillis())
        _ <- runner ! EphemeralAgentRunner.RunAgent(
          agentDef = probeDef,
          taskInput = "hang forever",
          replyTo = callerRef,
          depth = 1,
          projectRoot = os.pwd.toString
        )
        // agent 已 spawn 并注册（LLM 挂起中）
        _ <- waitUntil(5.seconds)(
          resources.agentRegistry.get.map(_.keys.exists(_.startsWith("ephemeral-")))
        )
        registry <- resources.agentRegistry.get
        ephSid = registry.keys.find(_.startsWith("ephemeral-")).get
        ephRef = registry(ephSid).ref
        // AgentControl cancel 降级路径：直接对 agent 发 Stop
        _ <- ephRef ! AgentCommand.Stop("agent-control-cancel-test")
        // bridge death-watch → deferred Left("cancelled") → runner 清理 + 回复调用方
        _ <- waitUntil(5.seconds)(callerEvents.get.map(_.nonEmpty))
        t1 <- IO(System.currentTimeMillis())
        replies <- callerEvents.get
        _ <- waitUntil(3.seconds)(resources.agentRegistry.get.map(!_.contains(ephSid)))
      yield
        assert(t1 - t0 < 5000, s"runner must settle within 5s of the Stop, took ${t1 - t0}ms")
        val immediate = replies.collectFirst { case i: AgentCommand.ImmediateInput => i }
        assert(immediate.isDefined, s"caller must receive the runner's reply, got: $replies")
        assert(
          immediate.get.text.contains("cancelled"),
          s"reply must carry the cancelled outcome, got: ${immediate.get.text}"
        )
      program.unsafeRunSync()
    finally
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
  }

  // ---- Block 0 registration chain (supervision trio §B2) ----

  test("Block 0: RunAgent caller context stamps parentSessionId/rootSessionId in agentRegistry") {
    val system = ActorSystem("eph-regchain")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp / "data")
    try
      val program = for
        resources <- mkResources(system, tmp)
        callerEvents <- Ref.of[IO, List[AgentCommand]](Nil)
        callerRef <- system.spawn(mkRecordingCmd(callerEvents), "eph-caller-rc")
        probeDef = AgentDef(name = "Probe", description = "eph probe", tools = List("Read"), systemPrompt = "")
        runner <- system.spawn(EphemeralAgentRunner(resources, None), "eph-runner-rc")
        _ <- runner ! EphemeralAgentRunner.RunAgent(
          agentDef = probeDef,
          taskInput = "hang forever",
          replyTo = callerRef,
          depth = 1,
          projectRoot = os.pwd.toString,
          callerSessionId = Some("caller-1"),
          callerRootSessionId = Some("root-9")
        )
        // agent spawned + registered (LLM hung on Stream.never)
        _ <- waitUntil(5.seconds)(
          resources.agentRegistry.get.map(_.keys.exists(_.startsWith("ephemeral-")))
        )
        registry <- resources.agentRegistry.get
        ephSid = registry.keys.find(_.startsWith("ephemeral-")).get
        rec = registry(ephSid)
        // cleanup: settle the runner so the temp session is removed
        _ <- rec.ref ! AgentCommand.Stop("regchain-test-done")
        _ <- waitUntil(3.seconds)(resources.agentRegistry.get.map(!_.contains(ephSid)))
      yield rec

      val rec = program.unsafeRunSync()
      assertEquals(rec.kind, AgentKind.Ephemeral)
      assertEquals(
        rec.parentSessionId,
        "caller-1",
        "caller context must stamp parentSessionId (registration chain §B2)"
      )
      assertEquals(
        rec.rootSessionId,
        "root-9",
        "caller context must bucket the ephemeral under the caller's root (legacy self-anchor left it unmanageable)"
      )
    finally
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
  }

end EphemeralBridgeWatchSpec
