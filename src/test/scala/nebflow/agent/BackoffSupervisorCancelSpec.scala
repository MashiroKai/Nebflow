package nebflow.agent

import cats.effect.{IO, Ref}
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import fs2.Stream
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, Behavior, Behaviors}
import nebflow.core.FileChangeTracker
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * AgentControl spec §3.1 / §6 B1：AgentEvent.Cancelled 的 supervisor 语义。
 *
 *  - Cancelled → notifyParentAndStop("cancelled")：父 ExternalEvent（source 保
 *    持 delegate → barrier 释放）+ taskStore cancelled（lastError 留痕）+
 *    registry 移除 + child Stop + supervisor 自停
 *  - Cancelled + Completed 竞态：mailbox 串行，首个终态胜出——父只收到一次
 *    通知且为 cancelled，task 不被后续 Completed 覆写成 completed
 */
class BackoffSupervisorCancelSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 30.seconds

  private val fakeLlm = new LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("fake llm not expected here"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.raiseError[IO](new RuntimeException("fake llm not expected here"))

  /** 记录所有命令、收到 Stop 即停止的 child probe（BackoffSupervisor 会 Stop child）。 */
  private def mkStopOnStopProbe(record: Ref[IO, List[AgentCommand]]): Behavior[AgentCommand] =
    def loop: Behavior[AgentCommand] =
      Behaviors.receiveMessage:
        case stop: AgentCommand.Stop =>
          record.update(_ :+ stop).as(Behaviors.stopped)
        case other =>
          record.update(_ :+ other).as(loop)
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
      prompt = "cancel me",
      description = "cancel test task",
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
        case true  => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError("waitUntil: condition not met in time"))
          else IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  test("B1: Cancelled → parent ExternalEvent(delegate/cancelled) + task cancelled + registry removal + child Stop") {
    val system = ActorSystem("bsup-cancel-1")
    val tmp = os.temp.dir()
    val parentSid = "cancel-parent-1"
    val childSid = "delegate-cancel-1"
    for
      resources <- mkResources(system, tmp)
      _ <- resources.subAgentTaskStore.recordTask(mkTask(parentSid, childSid))
      parentEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- system.spawn(mkRecordingCmd(parentEvents), "parent-rec-1")
      childEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      childRef <- system.spawn(mkStopOnStopProbe(childEvents), "cancel-child-1")
      _ <- resources.agentRegistry.update(
        _ + (childSid -> AgentRecord(childSid, childRef, AgentKind.Delegate, parentSid, Some(parentRef)))
      )
      supervisor <- system.spawn(
        BackoffSupervisor(
          childRef = childRef,
          childSpawnFn = (_, _) => IO.raiseError(new RuntimeException("respawn must not happen on cancel")),
          childName = childSid,
          parentRef = Some(parentRef),
          description = "cancel test task",
          agentName = "Worker",
          subagentId = childSid,
          parentSessionId = parentSid,
          resources = resources,
          initialPrompt = "cancel me",
          source = "delegate"
        ),
        "supervisor-c1"
      )
      _ <- supervisor ! AgentEvent.Cancelled(childSid, "user lost patience")
      _ <- waitUntil(3.seconds)(
        resources.subAgentTaskStore
          .loadTasks(parentSid)
          .map(_.exists(t => t.taskId == childSid && t.status == "cancelled"))
      )
      _ <- waitUntil(3.seconds)(
        resources.agentRegistry.get.map(!_.contains(childSid))
      )
      _ <- waitUntil(3.seconds)(parentEvents.get.map(_.nonEmpty))
      _ <- waitUntil(3.seconds)(childEvents.get.map(_.exists(_.isInstanceOf[AgentCommand.Stop])))
      events <- parentEvents.get
      childCmds <- childEvents.get
      tasks <- resources.subAgentTaskStore.loadTasks(parentSid)
      _ <- system.stopAll
    yield
      val ext = events.collect { case e: AgentCommand.ExternalEvent => e }
      assertEquals(ext.size, 1, s"parent must receive exactly one ExternalEvent, got: $events")
      assertEquals(ext.head.source, "delegate", "source must stay delegate so the wait barrier is released")
      assertEquals(ext.head.eventType, "cancelled")
      assert(ext.head.payload.contains("cancelled by Nebula via AgentControl"), s"payload: ${ext.head.payload}")
      assert(ext.head.payload.contains("user lost patience"), s"reason must be carried: ${ext.head.payload}")
      assertEquals(ext.head.metadata("cancelled").flatMap(_.asBoolean), Some(true))
      assertEquals(ext.head.metadata("failureType").flatMap(_.asString), Some("cancelled"))
      assertEquals(ext.head.correlationId, Some(childSid))
      // child 收到 Stop（终态清理路径）
      assert(childCmds.exists(_.isInstanceOf[AgentCommand.Stop]), s"child must be stopped, got: $childCmds")
      // task 文件：cancelled + lastError 留痕 + completedAt
      val task = tasks.find(_.taskId == childSid).get
      assertEquals(task.status, "cancelled")
      assert(task.lastError.exists(_.contains("cancelled by Nebula")), s"lastError: ${task.lastError}")
      assert(task.completedAt.isDefined, "completedAt must be set on cancel")
    end for
  }

  test("B1: Cancelled then Completed race — first terminal wins, single notification, no status overwrite") {
    val system = ActorSystem("bsup-cancel-2")
    val tmp = os.temp.dir()
    val parentSid = "cancel-parent-2"
    val childSid = "delegate-cancel-2"
    for
      resources <- mkResources(system, tmp)
      _ <- resources.subAgentTaskStore.recordTask(mkTask(parentSid, childSid))
      parentEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- system.spawn(mkRecordingCmd(parentEvents), "parent-rec-2")
      childEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      childRef <- system.spawn(mkStopOnStopProbe(childEvents), "cancel-child-2")
      supervisor <- system.spawn(
        BackoffSupervisor(
          childRef = childRef,
          childSpawnFn = (_, _) => IO.raiseError(new RuntimeException("respawn must not happen on cancel")),
          childName = childSid,
          parentRef = Some(parentRef),
          description = "cancel test task",
          agentName = "Worker",
          subagentId = childSid,
          parentSessionId = parentSid,
          resources = resources,
          initialPrompt = "cancel me",
          source = "delegate"
        ),
        "supervisor-c2"
      )
      // 竞态注入：Cancelled 先到（胜出、supervisor 自停），Completed 后到（死信丢弃）
      _ <- supervisor ! AgentEvent.Cancelled(childSid, "")
      _ <- supervisor ! AgentEvent.Completed(childSid, Nil)
      _ <- waitUntil(3.seconds)(
        resources.subAgentTaskStore
          .loadTasks(parentSid)
          .map(_.exists(t => t.taskId == childSid && t.status == "cancelled"))
      )
      _ <- IO.sleep(500.millis) // 给迟到的 Completed 足够时间制造破坏（若有）
      events <- parentEvents.get
      tasks <- resources.subAgentTaskStore.loadTasks(parentSid)
      _ <- system.stopAll
    yield
      val ext = events.collect { case e: AgentCommand.ExternalEvent => e }
      assertEquals(ext.size, 1, s"exactly one terminal notification expected, got: $events")
      assertEquals(ext.head.eventType, "cancelled", "first terminal (cancelled) must win")
      val task = tasks.find(_.taskId == childSid).get
      assertEquals(task.status, "cancelled", "late Completed must NOT overwrite the cancelled status")
    end for
  }

end BackoffSupervisorCancelSpec
