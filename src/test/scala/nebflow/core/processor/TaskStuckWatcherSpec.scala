package nebflow.core.processor

import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, ActorSystem, Behavior, Behaviors}
import nebflow.agent.{AgentCommand, AgentEvent, AgentKind, AgentLibrary, AgentRecord, AgentStatus, SharedResources}
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
 *   - #22 (2026-08-19)：Team agent Processing 超时 → 广播 taskStuck(action=
 *     attention)，只读不 Stop（Team 是长驻用户可见会话，AgentControl §4）
 *   - 防误杀铁律：Idle 态永不判；lastActivityMs 在阈值内不判；
 *     刚注册（lastActivityMs==0）不判
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

  /** 记录收到的所有 AgentEvent 的测试 actor（充当观察桥 supervisor）。 */
  private def mkRecordingEvt(record: Ref[IO, List[AgentEvent]]): Behavior[AgentEvent] =
    def loop: Behavior[AgentEvent] =
      Behaviors.receiveMessage[AgentEvent](evt => record.update(_ :+ evt).as(loop))
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


  test("#22: Team agent Processing 超时 → 只读广播 taskStuck(action=attention)，绝不发 Stop") {
    val system = ActorSystem("test-team-stuck")
    for
      _ <- IO(system)
      tmp <- IO(os.temp.dir())
      resources <- mkResources(system, tmp)
      teamReceived <- Ref.of[IO, List[AgentCommand]](Nil)
      teamRef <- system.spawn(mkRecordingActor(teamReceived), "team-agent")
      parentRef <- system.spawn(mkRecordingActor(Ref.unsafe(Nil)), "team-parent")
      wsHub = new WsHub()
      receivedWs <- Ref.of[IO, List[io.circe.Json]](Nil)
      _ <- wsHub.register(json => receivedWs.update(_ :+ json))
      now = System.currentTimeMillis()
      threshold = 10 * 60 * 1000L
      // Mail 激活的 team agent：kind=Team、有 parentRef（曾使 parentRef=Some 分支
      // 误发 Stop 的风险形态）——必须走 Team 只读分支
      stuckTeam = AgentRecord(
        sessionId = "team-nebflow-project-Frontend",
        ref = teamRef,
        kind = AgentKind.Team,
        rootSessionId = "root-1",
        parentRef = Some(parentRef),
        startedAt = now - 2 * 60 * 60 * 1000L,
        status = AgentStatus.Processing,
        lastActivityMs = now - threshold - 1000
      )
      _ <- resources.agentRegistry.set(Map("team-nebflow-project-Frontend" -> stuckTeam))
      _ <- TaskStuckWatcher.scan(resources, wsHub, threshold)
      _ <- IO.sleep(200.millis)
      wsEvents <- receivedWs.get
      teamCmds <- teamReceived.get
    yield
      // 广播：kind=Team + action=attention（用户可见，交给用户/Nebula 决策）
      assert(wsEvents.size == 1, s"expected one taskStuck broadcast, got $wsEvents")
      val ev = wsEvents.head
      assertEquals(ev.hcursor.get[String]("type").toOption, Some("taskStuck"))
      assertEquals(ev.hcursor.get[String]("kind").toOption, Some("Team"))
      assertEquals(ev.hcursor.get[String]("action").toOption, Some("attention"))
      assertEquals(ev.hcursor.get[String]("sessionId").toOption, Some("team-nebflow-project-Frontend"))
      assert(ev.hcursor.get[Long]("idleSecs").toOption.exists(_ > 0), s"idleSecs: $ev")
      // 只读铁律：长驻 team agent 绝不自动 Stop（AgentControl §4 Team=只读）
      assert(teamCmds.isEmpty, s"Team agent must NOT receive any command, got $teamCmds")
  }

  test("子 agent Processing 超时 → 广播 taskStuck(action=restart) + 发 Stop（AgentControl spec §3.5）") {
    val system = ActorSystem("test")
    for
      _ <- IO(system)
      tmp <- IO(os.temp.dir())
      resources <- mkResources(system, tmp)
      parentRef <- system.spawn(mkRecordingActor(Ref.unsafe(Nil)), "parent")
      _ <- system.spawn(mkRecordingActor(Ref.unsafe(Nil)), "child")
      childReceived <- Ref.of[IO, List[AgentCommand]](Nil)
      childRef <- system.spawn(mkRecordingActor(childReceived), "child-1")
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
      stopMsgs <- childReceived.get
    yield
      // §3.5：子 agent 自动重启也广播——前端可见「卡死，正在自动重启」，
      // Nebula 事后 AgentControl(list) 能看到 retryCount
      assert(wsEvents.size == 1, s"expected exactly one taskStuck broadcast for sub-agent, got $wsEvents")
      val ev = wsEvents.head
      assertEquals(ev.hcursor.get[String]("type").toOption.getOrElse(""), "taskStuck")
      assertEquals(ev.hcursor.get[String]("sessionId").toOption.getOrElse(""), "delegate-stuck")
      assertEquals(ev.hcursor.get[String]("kind").toOption.getOrElse(""), "Delegate")
      assertEquals(ev.hcursor.get[String]("action").toOption.getOrElse(""), "restart")
      assert(ev.hcursor.get[Long]("idleSecs").toOption.exists(_ > 0), s"idleSecs must be positive: $ev")
      // 恢复动作不变：仍然发 Stop（→ BackoffSupervisor death-watch 重启）
      assert(stopMsgs.count(_.isInstanceOf[AgentCommand.Stop]) == 1, s"Stop must still be sent, got $stopMsgs")
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

  test("#22: Team 加入扫描集合但只读——广播 taskStuck，绝不 Stop") {
    val system = ActorSystem("test")
    for
      _ <- IO(system)
      tmp <- IO(os.temp.dir())
      resources <- mkResources(system, tmp)
      childRef <- system.spawn(mkRecordingActor(Ref.unsafe(Nil)), "team-child")
      received <- Ref.of[IO, List[AgentCommand]](Nil)
      wsHub = new WsHub()
      receivedWs <- Ref.of[IO, List[io.circe.Json]](Nil)
      _ <- wsHub.register(json => receivedWs.update(_ :+ json))
      now = System.currentTimeMillis()
      _ <- resources.agentRegistry.set(
        Map(
          "team-long" -> AgentRecord(
            sessionId = "team-long",
            ref = childRef,
            kind = AgentKind.Team, // Team 现在在扫描集合（#22），但只读
            rootSessionId = "root-1",
            parentRef = Some(childRef),
            startedAt = now - 60 * 60 * 1000L,
            status = AgentStatus.Processing,
            lastActivityMs = now - 50 * 60 * 1000L
          )
        )
      )
      _ <- TaskStuckWatcher.scan(resources, wsHub, 10 * 60 * 1000L)
      _ <- IO.sleep(200.millis)
      msgs <- received.get
      wsEvents <- receivedWs.get
    yield
      // 只读铁律：Team 永不 Stop
      assert(msgs.isEmpty, s"Team agent must never be stopped, got: $msgs")
      // 但要被看见：长驻 Team 卡死不再是盲区（#22 12:28-13:45 两小时零可见性）
      assert(wsEvents.nonEmpty, s"Team stuck must broadcast taskStuck, got: $wsEvents")
      assert(wsEvents.head.hcursor.get[String]("kind").toOption.contains("Team"), s"kind=Team: ${wsEvents.head}")
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

  test("run 循环跨 sleep 边界递归 ≥2 轮不栈溢出（回归：`*> loop` 构建期无限递归）") {
    val system = ActorSystem("test")
    for
      tmp <- IO(os.temp.dir())
      resources <- mkResources(system, tmp)
      parentRef <- system.spawn(mkRecordingActor(Ref.unsafe(Nil)), "parent-loop")
      received <- Ref.of[IO, List[AgentCommand]](Nil)
      childRef <- system.spawn(mkRecordingActor(received), "loop-child")
      now = System.currentTimeMillis()
      threshold = 1000L
      _ <- resources.agentRegistry.set(
        Map(
          "loop-stuck" -> AgentRecord(
            sessionId = "loop-stuck",
            ref = childRef,
            kind = AgentKind.Delegate,
            rootSessionId = "root-1",
            parentRef = Some(parentRef),
            startedAt = now - 60_000L,
            status = AgentStatus.Processing,
            lastActivityMs = now - threshold - 1000
          )
        )
      )
      // 30ms interval：250ms ≈ 8 轮；每轮扫到 stuck 发 1 个 Stop。
      // timeout 双保险：若 run() 构建期无限递归（旧 *> 写法）→ 立即 StackOverflowError
      // → 测试红；若 sleep 链挂死 → timeout 兜底。
      fiber <- TaskStuckWatcher
        .run(resources, new WsHub(), 30.millis, threshold)
        .timeout(3.seconds)
        .start
      _ <- IO.sleep(250.millis)
      _ <- fiber.cancel
      msgs <- received.get
    yield assert(msgs.size >= 2, s"expected >=2 Stop across loop rounds, got ${msgs.size}")
  }


  test("gate-wedge P1-1: 第二次扫描对无视 Stop 的卡死子 agent 硬取消在飞 LLM 请求") {
    // 事故链：suspended 在 LLM fiber 上的 agent 永不消费 mailbox 的 Stop——
    // 6.5h 每 30s 重发全部无效。第二次扫描必须升级：经 inflight 注册表按
    // session cancel 在飞请求（StuckAbort），turn 走 llm-fail，Stop 终于被消费。
    val system = ActorSystem("test-escalation")
    for
      _ <- IO(system)
      tmp <- IO(os.temp.dir())
      resources <- mkResources(system, tmp)
      childReceived <- Ref.of[IO, List[AgentCommand]](Nil)
      childRef <- system.spawn(mkRecordingActor(childReceived), "child-esc")
      parentRef <- system.spawn(mkRecordingActor(Ref.unsafe(Nil)), "parent-esc")
      wsHub = new WsHub()
      now = System.currentTimeMillis()
      threshold = 10 * 60 * 1000L
      stuckChild = AgentRecord(
        sessionId = "session-escalate",
        ref = childRef,
        kind = AgentKind.Delegate,
        rootSessionId = "root-1",
        parentRef = Some(parentRef),
        startedAt = now - 2 * 60 * 60 * 1000L,
        status = AgentStatus.Processing,
        lastActivityMs = now - threshold - 1000
      )
      _ <- resources.agentRegistry.set(Map("session-escalate" -> stuckChild))
      // 该 session 有一个在飞（排队中即可）LLM 请求
      (_, halt) <- nebflow.llm.LlmInterface.registerInflight(Some("session-escalate"))
      stopCounts <- Ref.of[IO, Map[String, Int]](Map.empty)
      _ <- TaskStuckWatcher.scan(resources, wsHub, threshold, stopCounts) // Stop #1
      abortedAfterFirst <- halt.tryGet
      _ <- TaskStuckWatcher.scan(resources, wsHub, threshold, stopCounts) // Stop #2 → escalate
      aborted <- halt.get.timeoutTo(1.second, IO.pure(Left(new RuntimeException("not aborted"))))
      cmds <- childReceived.get
    yield
      assertEquals(abortedAfterFirst, None, "first scan must NOT hard-cancel yet (Stop gets a chance)")
      aborted match
        case Left(e: nebflow.llm.StuckAbort) => assert(e.sessionId == "session-escalate")
        case other => fail(s"expected Left(StuckAbort), got $other")
      assert(cmds.count(_.isInstanceOf[AgentCommand.Stop]) == 2, s"both scans must still send Stop, got $cmds")
  }

  // ---- Block 0 (supervision trio): team stuck notice carries turn-level
  // semantics per user ruling 2026-08-27 ("turn 级中止 ≠ actor 级 Stop") —
  // the ACTOR is never auto-stopped, a looping TURN may be terminated, and
  // the Manager/Nebula can cancel/restart via AgentControl. The old wording
  // ("read-only notice … user/Nebula can restart") contradicted both the
  // let-it-crash intent and the (now granted) AgentControl capabilities.

  test("Block 0: team stuck notice wording — turn-level semantics, not read-only") {
    val system = ActorSystem("test-team-stuck-wording")
    val lbLogger =
      org.slf4j.LoggerFactory.getLogger("nebflow.core.processor.stuck")
        .asInstanceOf[ch.qos.logback.classic.Logger]
    val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
    appender.start()
    lbLogger.addAppender(appender)
    try
      val program = for
        tmp <- IO(os.temp.dir())
        resources <- mkResources(system, tmp)
        teamRef <- system.spawn(mkRecordingActor(Ref.unsafe(Nil)), "team-agent-w")
        wsHub = new WsHub()
        receivedWs <- Ref.of[IO, List[io.circe.Json]](Nil)
        _ <- wsHub.register(json => receivedWs.update(_ :+ json))
        now = IO(System.currentTimeMillis())
        _ <- now.flatMap { n =>
          resources.agentRegistry.set(
            Map("team-w-1" -> AgentRecord(
              sessionId = "team-w-1",
              ref = teamRef,
              kind = AgentKind.Team,
              rootSessionId = "root-1",
              startedAt = n - 2 * 60 * 60 * 1000L,
              status = AgentStatus.Processing,
              lastActivityMs = n - 11 * 60 * 1000L
            ))
          )
        }
        _ <- TaskStuckWatcher.scan(resources, wsHub, 10 * 60 * 1000L)
        _ <- IO.sleep(200.millis)
        wsEvents <- receivedWs.get
      yield wsEvents

      val wsEvents = program.unsafeRunSync()
      import scala.jdk.CollectionConverters.*
      val warnings = appender.list.asScala.toList
        .filter(_.getLevel == ch.qos.logback.classic.Level.WARN)
        .map(_.getFormattedMessage)
        .filter(_.contains("team-w-1"))
      assertEquals(wsEvents.size, 1, s"exactly one taskStuck broadcast, got $wsEvents")
      assert(warnings.nonEmpty, "no WARN notice captured for the stuck team agent")
      val msg = warnings.head
      assert(msg.contains("actor is never auto-stopped"), s"wording must scope no-stop to the ACTOR: $msg")
      assert(msg.contains("loop guard"), s"wording must mention turn-level loop termination: $msg")
      assert(msg.contains("cancel/restart"), s"wording must state the AgentControl remedy: $msg")
      assert(!msg.contains("read-only notice"), s"stale read-only wording must be gone: $msg")
    finally
      lbLogger.detachAppender(appender)
      system.stopAll.attempt.void.unsafeRunSync()
  }

  // ── Project flow 会话（node-/dispatcher-）卡死恢复（let-it-crash）──────────

  /** kind=Flow + supervisorRef=观察桥 + parentRef=None 的卡死分发器/节点形态。 */
  private def stuckProjectFlow(
      system: ActorSystem,
      sid: String,
      bridgeRef: nebflow.actor.ActorRef[AgentEvent],
      threshold: Long
  ): IO[AgentRecord] =
    for
      ref <- system.spawn(mkRecordingActor(Ref.unsafe(Nil)), s"$sid-agent")
      now = System.currentTimeMillis()
    yield AgentRecord(
      sessionId = sid,
      ref = ref,
      kind = AgentKind.Flow,
      rootSessionId = "root-1",
      parentRef = None,
      startedAt = now - 20 * 60 * 1000L,
      status = AgentStatus.Processing,
      lastActivityMs = now - threshold - 1000,
      supervisorRef = Some(bridgeRef)
    )

  test("Project dispatcher 会话卡死 → 广播 restart + 第 1 次即硬取消在飞 LLM，绝不发 raw Stop") {
    val system = ActorSystem("test-dispatcher-stuck")
    for
      _ <- IO(system)
      tmp <- IO(os.temp.dir())
      resources <- mkResources(system, tmp)
      agentReceived <- Ref.of[IO, List[AgentCommand]](Nil)
      agentRef <- system.spawn(mkRecordingActor(agentReceived), "dispatcher-agent")
      bridgeReceived <- Ref.of[IO, List[AgentEvent]](Nil)
      bridgeRef <- system.spawn(mkRecordingEvt(bridgeReceived), "dispatcher-bridge")
      wsHub = new WsHub()
      receivedWs <- Ref.of[IO, List[io.circe.Json]](Nil)
      _ <- wsHub.register(json => receivedWs.update(_ :+ json))
      now = System.currentTimeMillis()
      threshold = 10 * 60 * 1000L
      stuck = AgentRecord(
        sessionId = "dispatcher-ab12cd34",
        ref = agentRef,
        kind = AgentKind.Flow,
        rootSessionId = "root-1",
        parentRef = None,
        startedAt = now - 20 * 60 * 1000L,
        status = AgentStatus.Processing,
        lastActivityMs = now - threshold - 1000,
        supervisorRef = Some(bridgeRef)
      )
      _ <- resources.agentRegistry.set(Map("dispatcher-ab12cd34" -> stuck))
      (_, halt) <- nebflow.llm.LlmInterface.registerInflight(Some("dispatcher-ab12cd34"))
      stopCounts <- cats.effect.Ref.of[IO, Map[String, Int]](Map.empty)
      _ <- TaskStuckWatcher.scan(resources, wsHub, threshold, stopCounts)
      aborted <- halt.get.timeoutTo(1.second, IO.pure(Left(new RuntimeException("not aborted"))))
      _ <- IO.sleep(200.millis)
      wsEvents <- receivedWs.get
      agentCmds <- agentReceived.get
      bridgeEvts <- bridgeReceived.get
    yield
      // 广播：action=halt（L1 软恢复——真实动作是 halt 在飞 LLM，尚未 restart；P7 诚实帧）
      assert(wsEvents.size == 1, s"expected one taskStuck broadcast, got $wsEvents")
      val ev = wsEvents.head
      assertEquals(ev.hcursor.get[String]("type").toOption, Some("taskStuck"))
      assertEquals(ev.hcursor.get[String]("kind").toOption, Some("Flow"))
      assertEquals(ev.hcursor.get[String]("action").toOption, Some("halt"))
      assertEquals(ev.hcursor.get[String]("sessionId").toOption, Some("dispatcher-ab12cd34"))
      // 第 1 次扫描即硬取消（flow 会话无 supervisor 重启预算可消耗）
      aborted match
        case Left(e: nebflow.llm.StuckAbort) => assert(e.sessionId == "dispatcher-ab12cd34")
        case other => fail(s"expected Left(StuckAbort), got $other")
      // 铁律：不发 raw Stop——单次会话 Stop 杀 actor 而不发终态事件（桥收不到）
      assert(agentCmds.isEmpty, s"flow session must NOT receive raw Stop, got $agentCmds")
      // 未到 giveUp 阈值：桥不收 Cancelled
      assert(bridgeEvts.isEmpty, s"bridge must not receive events before giveUp, got $bridgeEvts")
  }

  test("Project flow 会话连续分级接管 → L3 桥 Cancelled 释放；resume 失败计数保留 → L4 failed 可达") {
    val system = ActorSystem("test-flow-giveup")
    for
      _ <- IO(system)
      tmp <- IO(os.temp.dir())
      resources <- mkResources(system, tmp)
      agentReceived <- Ref.of[IO, List[AgentCommand]](Nil)
      agentRef <- system.spawn(mkRecordingActor(agentReceived), "node-agent-gu")
      bridgeReceived <- Ref.of[IO, List[AgentEvent]](Nil)
      bridgeRef <- system.spawn(mkRecordingEvt(bridgeReceived), "node-bridge-gu")
      wsHub = new WsHub()
      threshold = 10 * 60 * 1000L
      stuck <- stuckProjectFlow(system, "node-aaaa1111", bridgeRef, threshold)
      _ <- resources.agentRegistry.set(Map("node-aaaa1111" -> stuck))
      stopCounts <- cats.effect.Ref.of[IO, Map[String, Int]](Map.empty)
      _ <- TaskStuckWatcher.scan(resources, wsHub, threshold, stopCounts) // 1 → L1 halt
      _ <- TaskStuckWatcher.scan(resources, wsHub, threshold, stopCounts) // 2 → L2 hard-abort
      _ <- TaskStuckWatcher.scan(resources, wsHub, threshold, stopCounts) // 3 → L3 restart（桥 Cancelled 释放）
      _ <- TaskStuckWatcher.scan(resources, wsHub, threshold, stopCounts) // 4 → L4 failed（resume 失败计数保留）
      _ <- IO.sleep(200.millis)
      bridgeEvts <- bridgeReceived.get
      agentCmds <- agentReceived.get
      counts <- stopCounts.get
    yield
      val cancelled = bridgeEvts.collect { case c: AgentEvent.Cancelled => c }
      assertEquals(cancelled.size, 1, s"bridge must receive exactly one Cancelled (L3 release), got: $bridgeEvts")
      assertEquals(cancelled.head.sessionId, "node-aaaa1111")
      assert(cancelled.head.reason.contains("stuck"), s"reason must carry stuck context: ${cancelled.head.reason}")
      // 全程零 raw Stop（即便 giveUp 也走桥 Cancelled）
      assert(agentCmds.isEmpty, s"flow session must never receive raw Stop, got $agentCmds")
      // L3 resume 失败（无 project runtime）→ 计数保留 → L4 "failed" 拍可达
      // （响亮失败，不因清零而静默掩盖迟钝恢复）。
      assert(counts.contains("node-aaaa1111"), s"stopCounts must persist after failed resume so L4 failed is reachable, got: $counts")
  }

  test("旧 flow 系统 dag- 会话（无 supervisorRef）维持 notice-only——不硬取消、不发 Cancelled") {
    val system = ActorSystem("test-dag-notice")
    for
      _ <- IO(system)
      tmp <- IO(os.temp.dir())
      resources <- mkResources(system, tmp)
      agentReceived <- Ref.of[IO, List[AgentCommand]](Nil)
      agentRef <- system.spawn(mkRecordingActor(agentReceived), "dag-agent")
      wsHub = new WsHub()
      receivedWs <- Ref.of[IO, List[io.circe.Json]](Nil)
      _ <- wsHub.register(json => receivedWs.update(_ :+ json))
      now = System.currentTimeMillis()
      threshold = 10 * 60 * 1000L
      stuckDag = AgentRecord(
        sessionId = "dag-myflow-step1",
        ref = agentRef,
        kind = AgentKind.Flow,
        rootSessionId = "root-1",
        parentRef = None,
        startedAt = now - 20 * 60 * 1000L,
        status = AgentStatus.Processing,
        lastActivityMs = now - threshold - 1000
        // supervisorRef = None（FlowDagExecutor 注册现状）
      )
      _ <- resources.agentRegistry.set(Map("dag-myflow-step1" -> stuckDag))
      (_, halt) <- nebflow.llm.LlmInterface.registerInflight(Some("dag-myflow-step1"))
      stopCounts <- cats.effect.Ref.of[IO, Map[String, Int]](Map.empty)
      _ <- TaskStuckWatcher.scan(resources, wsHub, threshold, stopCounts)
      _ <- IO.sleep(200.millis)
      notAborted <- halt.tryGet
      wsEvents <- receivedWs.get
      agentCmds <- agentReceived.get
    yield
      // 无取消通道 → 不硬取消（dag 会话的取消走 cancelFlow / RunningFlowRegistry）
      assertEquals(notAborted, None, s"dag session must not be hard-cancelled, got $notAborted")
      // 仅 notice（根 agent 分支广播）
      assert(wsEvents.size == 1, s"expected one taskStuck notice, got $wsEvents")
      assertEquals(wsEvents.head.hcursor.get[String]("action").toOption, Some("attention"))
      assert(agentCmds.isEmpty, s"dag session must not receive commands, got $agentCmds")
  }

end TaskStuckWatcherSpec
