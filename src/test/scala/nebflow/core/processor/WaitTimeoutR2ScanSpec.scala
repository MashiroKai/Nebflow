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
 * R2（wait-timeout-fix，2026-09-03 作者裁定）：TaskStuckWatcher 对
 * WaitingForUser（人在环等待：AskUser pending / 权限卡挂起）的扫描契约。
 *
 * 审计 20260903 根因：AskUser 挂起期间 registry 状态残留 Processing 且
 * lastActivityMs 不再刷新 → 每 30s 扫描必然命中 → 当日 116 条 taskStuck 误报
 * （root=噪声刷屏；Delegate/Ephemeral/SubTask=Stop→硬取消破坏性链，问句永久失效）。
 *
 * 时间模拟手法（沿用 TaskStuckWatcherSpec 先例，零真实等待）：
 * 直接注入 lastActivityMs 回拨（now - threshold - 1000）+ 以参数化 threshold
 * 驱动单轮 scan。「等了 11min」= 回拨时间戳，不是真的等。
 *
 * 分工说明：本 spec 钉「扫描层契约」——WaitingForUser 记录永不进卡死判定；
 * 生产行为链（AskUser 派发标注 → 回答解除 → 重新覆盖）由
 * WaitTimeoutAskUserWiringSpec 以真实 AgentActor 端到端钉住。验红变异 =
 * 还原等待态标注（AgentActor.AskUser / AgentCore.askUserPermission 的
 * WaitingForUser touch）→ status 残留 Processing → 本 spec 对照用例同形开火。
 */
class WaitTimeoutR2ScanSpec extends CatsEffectSuite:

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

  /** 造一条「挂起超阈值」的 registry 记录：status 可参数化，lastActivityMs
    * 回拨 threshold+1000ms（= 零活动已超阈值，模拟等了 11min+ 的形态）。
    * rec.ref 自身是记录 actor（Stop 的观察点——TaskStuckWatcher 把 Stop 发给
    * rec.ref，不是 parentRef），sink 由调用方提供。 */
  private def waitingRecord(
      system: ActorSystem,
      resources: SharedResources,
      sid: String,
      kind: AgentKind,
      status: AgentStatus,
      parentRef: Option[ActorRef[AgentCommand]],
      threshold: Long,
      selfSink: Ref[IO, List[AgentCommand]]
  ): IO[AgentRecord] =
    for
      ref <- system.spawn(mkRecordingActor(selfSink), s"rec-$sid")
      now <- IO(System.currentTimeMillis())
      rec = AgentRecord(
        sessionId = sid,
        ref = ref,
        kind = kind,
        rootSessionId = sid,
        parentRef = parentRef,
        startedAt = now - 60_000,
        status = status,
        lastActivityMs = now - threshold - 1000
      )
      _ <- resources.agentRegistry.update(_ + (sid -> rec))
    yield rec

  // ============================================================
  // 验收 1：WaitingForUser 挂起（缩阈模拟 11min+）→ 零误报
  // ============================================================

  test("R2 验收1: Delegate WaitingForUser 超阈值挂起 → scan 零动作（不 Stop、不广播）——AskUser 误报消灭") {
    val system = ActorSystem("r2-scan-delegate")
    for
      tmp <- IO(os.temp.dir())
      resources <- mkResources(system, tmp)
      selfReceived <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- system.spawn(mkRecordingActor(Ref.unsafe(Nil)), "r2-parent")
      wsHub = new WsHub()
      receivedWs <- Ref.of[IO, List[io.circe.Json]](Nil)
      _ <- wsHub.register(json => receivedWs.update(_ :+ json))
      threshold = 10 * 60 * 1000L
      // 审计 116 条/日 的破坏性形态：Delegate（有 parentRef）挂起超阈值。
      // R2 后 status=WaitingForUser（人在环等待）→ 必须零动作。
      _ <- waitingRecord(system, resources, "r2-waiting-delegate", AgentKind.Delegate,
        AgentStatus.WaitingForUser, Some(parentRef), threshold, selfReceived)
      _ <- TaskStuckWatcher.scan(resources, wsHub, threshold)
      _ <- IO.sleep(200.millis)
      wsEvents <- receivedWs.get
      selfCmds <- selfReceived.get
      registry <- resources.agentRegistry.get
    yield
      assertEquals(wsEvents.size, 0, s"WaitingForUser 绝不触发 taskStuck 广播，got: $wsEvents")
      assertEquals(selfCmds.size, 0, "WaitingForUser 绝不收 Stop（破坏性链禁止）")
      assertEquals(registry("r2-waiting-delegate").status, AgentStatus.WaitingForUser,
        "等待态原样保留（等用户≠卡死，等待不被打扰）")
    end for
  }

  test("R2 验收1 补充: root 会话 WaitingForUser 超阈值 → 无 attention 广播——116条/日噪声形态消灭") {
    val system = ActorSystem("r2-scan-root")
    for
      tmp <- IO(os.temp.dir())
      resources <- mkResources(system, tmp)
      wsHub = new WsHub()
      receivedWs <- Ref.of[IO, List[io.circe.Json]](Nil)
      _ <- wsHub.register(json => receivedWs.update(_ :+ json))
      threshold = 10 * 60 * 1000L
      // 审计日志的四个会话全是 root（无 parentRef）形态：18:30 起每 30s 一条
      // 「stuck in Processing for Ns — not auto-restarting, broadcast taskStuck」。
      _ <- waitingRecord(system, resources, "r2-waiting-root", AgentKind.Root,
        AgentStatus.WaitingForUser, None, threshold, Ref.unsafe(Nil))
      _ <- TaskStuckWatcher.scan(resources, wsHub, threshold)
      _ <- IO.sleep(200.millis)
      wsEvents <- receivedWs.get
    yield
      assertEquals(wsEvents.size, 0, s"root 等待态绝不广播 taskStuck(attention)，got: $wsEvents")
    end for
  }

  // ============================================================
  // 验收 5 前半：真卡死检测不回归——非等待态同形记录仍触发
  // ============================================================

  test("R2 对照（验收5前半）: 同形记录 Processing（真卡死）→ Stop + taskStuck 广播仍触发") {
    val system = ActorSystem("r2-scan-processing")
    for
      tmp <- IO(os.temp.dir())
      resources <- mkResources(system, tmp)
      selfReceived <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- system.spawn(mkRecordingActor(Ref.unsafe(Nil)), "r2-parent-p")
      wsHub = new WsHub()
      receivedWs <- Ref.of[IO, List[io.circe.Json]](Nil)
      _ <- wsHub.register(json => receivedWs.update(_ :+ json))
      threshold = 10 * 60 * 1000L
      // 与验收 1 唯一差异 = status: Processing（真卡死）vs WaitingForUser（等人）。
      // 铁律：排除等人绝不放走真挂死。
      _ <- waitingRecord(system, resources, "r2-stuck-processing", AgentKind.Delegate,
        AgentStatus.Processing, Some(parentRef), threshold, selfReceived)
      _ <- TaskStuckWatcher.scan(resources, wsHub, threshold)
      _ <- IO.sleep(200.millis)
      wsEvents <- receivedWs.get
      selfCmds <- selfReceived.get
    yield
      assert(wsEvents.exists(j => j.hcursor.get[String]("type").toOption.contains("taskStuck")),
        s"Processing 真卡死必须广播 taskStuck，got: $wsEvents")
      assert(selfCmds.exists(_.isInstanceOf[AgentCommand.Stop]),
        s"Processing 真卡死必须收到 Stop（监督重启链），got: $selfCmds")
    end for
  }

  test("R2 对照: Team WaitingForUser → 无广播（#22 只读语义下等待态同样豁免）") {
    val system = ActorSystem("r2-scan-team")
    for
      tmp <- IO(os.temp.dir())
      resources <- mkResources(system, tmp)
      parentReceived <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- system.spawn(mkRecordingActor(parentReceived), "r2-team-parent")
      wsHub = new WsHub()
      receivedWs <- Ref.of[IO, List[io.circe.Json]](Nil)
      _ <- wsHub.register(json => receivedWs.update(_ :+ json))
      threshold = 10 * 60 * 1000L
      _ <- waitingRecord(system, resources, "r2-waiting-team", AgentKind.Team,
        AgentStatus.WaitingForUser, Some(parentRef), threshold, Ref.unsafe(Nil))
      _ <- TaskStuckWatcher.scan(resources, wsHub, threshold)
      _ <- IO.sleep(200.millis)
      wsEvents <- receivedWs.get
      parentCmds <- parentReceived.get
    yield
      assertEquals(wsEvents.size, 0, s"Team 等待态零广播，got: $wsEvents")
      assertEquals(parentCmds.size, 0, "Team 等待态零 Stop")
    end for
  }

end WaitTimeoutR2ScanSpec
