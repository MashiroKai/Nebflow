package nebflow.core.processor

import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, ActorSystem, Behavior, Behaviors}
import nebflow.agent.{AgentCommand, AgentKind, AgentLibrary, AgentRecord, AgentStatus, SharedResources}
import nebflow.core.FileChangeTracker
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore, WsHub}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * P0 阶段 3（2026-08-18，设计 §4.4）：TaskStuckWatcher 卡死识别扫描器单测。
 *
 * 直接驱动单轮 scan 断言判定与恢复动作：
 *   - 子 agent（有 parentRef）Processing 超时 → 发 Stop（→ BackoffSupervisor
 *     death-watch 重启链路由现有 supervisor 测试覆盖，这里只验证识别+触发）
 *   - 根 agent（无 parentRef）Processing 超时 → 广播 taskStuck WS 事件，不 Stop
 *   - 防误杀铁律：Idle 态永不判；lastActivityMs 在阈值内不判；
 *     刚注册（lastActivityMs==0）不判；非 taskKinds（Team/Root）不扫
 */
class TaskStuckWatcherSpec extends CatsEffectSuite:

  private val fakeLlm = new LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("fake llm not expected here"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.raiseError[IO](new RuntimeException("fake llm not expected here"))

  /** 记录收到的所有 AgentCommand 的测试 actor。 */
  private def mkRecordingActor(record: Ref[IO, List[AgentCommand]]): Behavior[AgentCommand] =
    def loop: Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand](cmd => record.update(_ :+ cmd).as(loop))
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

  test("子 agent Processing 超时 → 发 Stop，不广播") {
    val system = ActorSystem("test")
    for
      _ <- IO(system)
      tmp <- IO(os.temp.dir())
      resources <- mkResources(system, tmp)
      parentRef <- system.spawn(mkRecordingActor(Ref.unsafe(Nil)), "parent")
      _ <- system.spawn(mkRecordingActor(Ref.unsafe(Nil)), "child")
      childRef <- system.spawn(mkRecordingActor(Ref.unsafe(Nil)), "child-1")
      wsHub = new WsHub()
      receivedWs <- Ref.of[IO, List[io.circe.Json]](Nil)
      _ <- wsHub.register(json => receivedWs.update(_ :+ json))
      now = System.currentTimeMillis()
      threshold = 10 * 60 * 1000L
      stuckRecord = AgentRecord(
        sessionId = "delegate-stuck",
        ref = childRef,
        kind = AgentKind.Delegate,
        rootSessionId = "root-1",
        parentRef = Some(parentRef),
        startedAt = now - 20 * 60 * 1000L,
        status = AgentStatus.Processing,
        lastActivityMs = now - threshold - 1000
      )
      _ <- resources.agentRegistry.set(Map("delegate-stuck" -> stuckRecord))
      _ <- TaskStuckWatcher.scan(resources, wsHub, threshold)
      _ <- IO.sleep(200.millis) // 等 tell 到达
      wsEvents <- receivedWs.get
    yield
      // 子 agent 恢复动作是发 Stop（由「Stop 实际送达」测试断言），不应广播 taskStuck
      assert(wsEvents.isEmpty, s"expected no broadcast for sub-agent, got $wsEvents")
  }

  test("根 agent Processing 超时 → 广播 taskStuck，不 Stop") {
    val system = ActorSystem("test")
    for
      tmp <- IO(os.temp.dir())
      resources <- mkResources(system, tmp)
      rootRef <- system.spawn(mkRecordingActor(Ref.unsafe(Nil)), "root-rec")
      wsHub = new WsHub()
      receivedWs <- Ref.of[IO, List[io.circe.Json]](Nil)
      _ <- wsHub.register(json => receivedWs.update(_ :+ json))
      now = System.currentTimeMillis()
      threshold = 10 * 60 * 1000L
      stuckRecord = AgentRecord(
        sessionId = "root-stuck",
        ref = rootRef,
        kind = AgentKind.Root,
        rootSessionId = "root-stuck",
        parentRef = None,
        startedAt = now - 20 * 60 * 1000L,
        status = AgentStatus.Processing,
        lastActivityMs = now - threshold - 1000
      )
      _ <- resources.agentRegistry.set(Map("root-stuck" -> stuckRecord))
      _ <- TaskStuckWatcher.scan(resources, wsHub, threshold)
      _ <- IO.sleep(200.millis)
      wsEvents <- receivedWs.get
    yield
      assert(wsEvents.size == 1, s"expected exactly one taskStuck broadcast, got $wsEvents")
      val ev = wsEvents.head
      assertEquals(ev.hcursor.get[String]("type").toOption.getOrElse(""), "taskStuck")
      assertEquals(ev.hcursor.get[String]("sessionId").toOption.getOrElse(""), "root-stuck")
  }

  test("Idle 态永不判卡死（run_in_background 防误杀铁律）") {
    val system = ActorSystem("test")
    for
      tmp <- IO(os.temp.dir())
      resources <- mkResources(system, tmp)
      childRef <- system.spawn(mkRecordingActor(Ref.unsafe(Nil)), "idle-child")
      received <- Ref.of[IO, List[AgentCommand]](Nil)
      _ <- resources.agentRegistry.set(
        Map(
          "idle-delegate" -> AgentRecord(
            sessionId = "idle-delegate",
            ref = childRef,
            kind = AgentKind.Delegate,
            rootSessionId = "root-1",
            parentRef = Some(childRef),
            startedAt = System.currentTimeMillis() - 60 * 60 * 1000L,
            status = AgentStatus.Idle, // 回 idle 等待后台命令
            lastActivityMs = System.currentTimeMillis() - 30 * 60 * 1000L // 远超阈值
          )
        )
      )
      _ <- TaskStuckWatcher.scan(resources, new WsHub(), 10 * 60 * 1000L)
      _ <- IO.sleep(200.millis)
      msgs <- received.get
    yield assert(msgs.isEmpty, s"idle agent must never be stopped, got $msgs")
  }

  test("lastActivityMs 在阈值内（有活动）不判卡死") {
    val system = ActorSystem("test")
    for
      tmp <- IO(os.temp.dir())
      resources <- mkResources(system, tmp)
      childRef <- system.spawn(mkRecordingActor(Ref.unsafe(Nil)), "active-child")
      received <- Ref.of[IO, List[AgentCommand]](Nil)
      now = System.currentTimeMillis()
      _ <- resources.agentRegistry.set(
        Map(
          "active-delegate" -> AgentRecord(
            sessionId = "active-delegate",
            ref = childRef,
            kind = AgentKind.Delegate,
            rootSessionId = "root-1",
            parentRef = Some(childRef),
            startedAt = now - 30 * 60 * 1000L,
            status = AgentStatus.Processing,
            lastActivityMs = now - 1000 // 1s 前还在活动
          )
        )
      )
      _ <- TaskStuckWatcher.scan(resources, new WsHub(), 10 * 60 * 1000L)
      _ <- IO.sleep(200.millis)
      msgs <- received.get
    yield assert(msgs.isEmpty, s"recently-active agent must not be stopped, got $msgs")
  }

  test("lastActivityMs==0（刚注册未 touch）不判卡死") {
    val system = ActorSystem("test")
    for
      tmp <- IO(os.temp.dir())
      resources <- mkResources(system, tmp)
      childRef <- system.spawn(mkRecordingActor(Ref.unsafe(Nil)), "fresh-child")
      received <- Ref.of[IO, List[AgentCommand]](Nil)
      _ <- resources.agentRegistry.set(
        Map(
          "fresh-delegate" -> AgentRecord(
            sessionId = "fresh-delegate",
            ref = childRef,
            kind = AgentKind.Delegate,
            rootSessionId = "root-1",
            parentRef = Some(childRef),
            startedAt = 0L,
            status = AgentStatus.Processing,
            lastActivityMs = 0L // 默认值：还没 touch 过
          )
        )
      )
      _ <- TaskStuckWatcher.scan(resources, new WsHub(), 10 * 60 * 1000L)
      _ <- IO.sleep(200.millis)
      msgs <- received.get
    yield assert(msgs.isEmpty, s"freshly-registered agent must not be stopped, got $msgs")
  }

  test("非扫描集合（Team）不扫描") {
    val system = ActorSystem("test")
    for
      tmp <- IO(os.temp.dir())
      resources <- mkResources(system, tmp)
      childRef <- system.spawn(mkRecordingActor(Ref.unsafe(Nil)), "team-child")
      received <- Ref.of[IO, List[AgentCommand]](Nil)
      now = System.currentTimeMillis()
      _ <- resources.agentRegistry.set(
        Map(
          "team-long" -> AgentRecord(
            sessionId = "team-long",
            ref = childRef,
            kind = AgentKind.Team, // Team 长期驻留，registry 常年在
            rootSessionId = "root-1",
            parentRef = Some(childRef),
            startedAt = now - 60 * 60 * 1000L,
            status = AgentStatus.Processing,
            lastActivityMs = now - 50 * 60 * 1000L
          )
        )
      )
      _ <- TaskStuckWatcher.scan(resources, new WsHub(), 10 * 60 * 1000L)
      _ <- IO.sleep(200.millis)
      msgs <- received.get
    yield assert(msgs.isEmpty, s"Team agent must not be scanned, got $msgs")
  }

  test("空 registry 扫描不抛错") {
    val system = ActorSystem("test")
    for
      tmp <- IO(os.temp.dir())
      resources <- mkResources(system, tmp)
      _ <- TaskStuckWatcher.scan(resources, new WsHub(), 10 * 60 * 1000L)
    yield assert(true)
  }

  test("子 agent Stop 消息实际送达（recording actor 收到）") {
    val system = ActorSystem("test")
    for
      tmp <- IO(os.temp.dir())
      resources <- mkResources(system, tmp)
      parentRef <- system.spawn(mkRecordingActor(Ref.unsafe(Nil)), "parent-rec")
      received <- Ref.of[IO, List[AgentCommand]](Nil)
      childRef <- system.spawn(mkRecordingActor(received), "stuck-child")
      now = System.currentTimeMillis()
      threshold = 10 * 60 * 1000L
      _ <- resources.agentRegistry.set(
        Map(
          "delegate-stuck" -> AgentRecord(
            sessionId = "delegate-stuck",
            ref = childRef,
            kind = AgentKind.Delegate,
            rootSessionId = "root-1",
            parentRef = Some(parentRef),
            startedAt = now - 20 * 60 * 1000L,
            status = AgentStatus.Processing,
            lastActivityMs = now - threshold - 1000
          )
        )
      )
      _ <- TaskStuckWatcher.scan(resources, new WsHub(), threshold)
      _ <- IO.sleep(300.millis)
      msgs <- received.get
    yield
      assert(msgs.size == 1, s"expected exactly one Stop, got $msgs")
      assert(msgs.head.isInstanceOf[AgentCommand.Stop], s"expected Stop, got ${msgs.head.getClass.getSimpleName}")
  }

end TaskStuckWatcherSpec
