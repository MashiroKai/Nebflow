package nebflow.core.tools

import cats.effect.{IO, Ref}
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, AgentCommand, ActorSystem, AgentRecord, Behavior, Behaviors}
import nebflow.agent.{AgentLibrary, CompactionQueueStore, SharedResources}
import nebflow.core.{FileChangeTracker, RateLimiter, SessionStore}
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk, ThinkingConfig}

import scala.concurrent.duration.*

/**
 * bash overhaul T2 红相（作者令 2026-11-04「组织优化 bash，用业界成熟做法完整
 * 实现，一步到位」）：**完成通知不得因「没有 agent 上下文」而静默消失**。
 *
 * 事实源：`docs/20261104_110500_our-bash-tool-defects.md` 缺陷 D5 ——
 * `BashTool.scala:1117-1118`（`ctx.agentActorRef` 为空 ⇒ `makeNotifyCallback`
 * 返回 `None`，回调链整条静默缺席）+ `BgNotifyDelivery.scala:166-167`
 * （`targetRef match { case None => IO.unit }`）⇒ REST 直调 / 无 actor 上下文的
 * 会话把后台任务完成事件**彻底丢掉**，连账本都没有。
 *
 * 修法口径（调研件 §0-2/§4-5 + 本仓 §5.1）：`targetRef == None` 但
 * `targetSessionId` 在场的投递**必须**走 `Persist` 腿——落「待补投」标记到
 * 目标会话自己的持久注入队（`BgInboxPort` → `CompactionQueueStore`），会话
 * 复活/新回合时由既有补投腿重放；「静默无动作」这个形态必须消失。
 *
 * 本文件第一批只有**行为腿**（能编译、跑起来即红）；T2 的编译腿（
 * `BgTaskRegistry.markAwaited` / `BashTool.makeNotifyCallback` 的可测性）在
 * 第二批追加——那两条要求生产侧新增喂 awaited 标记/面向上开可见性，属预期编译红。
 *
 * harness 与 `BgNotifyDeliverySpec` 同款（SharedResources 构造即安装
 * `BgInboxPort` 真实写点 = `BgInboxBackstop.face`），断言走**真实落盘面**
 * （`CompactionQueueStore.load`），不造假替身。
 */
class BashOverhaulNotifySpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-bash-overhaul-notify"
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

  private def payload = "[Background task completed] \"spec job\":\nT2-OUTPUT-TAIL"

  private def deliver(
    system: Option[ActorSystem],
    registry: Option[Ref[IO, Map[String, AgentRecord]]],
    targetRef: Option[ActorRef[AgentCommand]],
    targetSessionId: Option[String],
    jobId: String
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
      payload = payload,
      metadata = JsonObject("description" -> io.circe.Json.fromString("spec job"))
    )

  // ── T2 行为腿：无 agent 上下文时事件必须进持久补投面，不得静默蒸发 ────────

  test(
    "T2: targetRef=None + targetSessionId=Some ⇒ a persistent pending-redelivery marker is written (old code: silent IO.unit ⇒ RED)"
  ) {
    val system = ActorSystem(s"bash-overhaul-notify-${scala.util.Random.nextInt(100000)}")
    for
      resources <- mkResources(system)
      // 目标会话真实存在（持久注入队的写入门槛）——但不传 actorRef（REST/无上下文档）。
      meta <- resources.sessionStore.createSession("bg-noref-t2")
      // 重启窗口形态：激活面未装配 ⇒ 唯一正确路由 = Persist（落目标会话注入队）。
      _ = BgSessionActivator.clear()
      _ <- deliver(
        system = Some(system),
        registry = Some(resources.agentRegistry),
        targetRef = None,
        targetSessionId = Some(meta.id),
        jobId = "job-t2"
      )
      persisted <- CompactionQueueStore.load(meta.id)
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      val events = persisted.map(_.events).getOrElse(Nil)
      assertEquals(
        events.length,
        1,
        "a completion event with no agent ref must NOT vanish — it must persist a pending-redelivery marker to the target session's own inbox"
      )
      assertEquals(
        events.head.correlationId,
        Some("bg-redelivery-job-t2"),
        "idempotence key = bg-redelivery-<jobId>"
      )
      assertEquals(
        events.head.metadata("bgTaskId").flatMap(_.asString),
        Some("job-t2"),
        "the marker names the job id"
      )
      assert(
        events.head.payload.contains("T2-OUTPUT-TAIL"),
        s"the completion payload rides the marker: ${events.head.payload.take(120)}"
      )
  }

  test("T2: targetRef=None + targetSessionId=None ⇒ still no crash (nothing to address, must stay a no-op)") {
    deliver(system = None, registry = None, targetRef = None, targetSessionId = None, jobId = "job-t2b").attempt.map { r =>
      assert(r.isRight, s"a completion event with no addressing at all must not throw: $r")
    }
  }

  // ── T2 编译红腿（第二批）────────────────────────────────────────────────
  //
  // 这三条钉的是**生产侧可测性/新喂料面**，旧代码缺符号 ⇒ 本文件编译失败即预期红：
  //   (a) `BgTaskRegistry.markAwaited(jobId)` —— 不存在（`awaited` 标记的喂入面；
  //       dsh 的 `awaited` 去重语义：已被等待者收走的结算不再重复推送）；
  //   (b) `BashTool.makeNotifyCallback` 现为 `private`（不可从 spec 触达）——
  //       修法须放宽到 `private[tools]` 并让 `agentActorRef = None` 时**仍返回非
  //       None** 的回调（D5：无 agent 上下文也要落账本 + 输出留存）；
  //   (c) 「先落账再投递」顺序：投递腿抛错/被拦时账本行必须已经在盘上。

  private def mkRecordingRef(
    system: ActorSystem,
    name: String,
    record: Ref[IO, List[AgentCommand]]
  ): IO[ActorRef[AgentCommand]] =
    def loop: Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand](cmd => record.update(_ :+ cmd).as(loop))
    system.spawn(loop, name)

  test("T2(a) — markAwaited: an awaited completion skips the agent push but still lands the ledger row") {
    val system = ActorSystem(s"bash-overhaul-awaited-${scala.util.Random.nextInt(100000)}")
    val jobId = s"t2-awaited-${scala.util.Random.nextInt(100000)}"
    val sid = "sid-t2-awaited"
    for
      received <- Ref.of[IO, List[AgentCommand]](Nil)
      target <- mkRecordingRef(system, "t2-awaited-target", received)
      // 等待型任务行先注册，再由「等待者」把它标成 awaited（Bash wait mode 收走结果）。
      _ <- BgTaskRegistry.register(jobId, sid, "awaited task", "local", sid, persistent = false)
      _ <- BgTaskOutputStore.open(jobId, None) // 生产面：起后台任务即建缓冲
      _ <- BgTaskRegistry.markAwaited(jobId)
      ctx = ToolContext(projectRoot = "/tmp", sessionId = Some(sid), agentActorRef = Some(target))
      cbOpt = BashTool.makeNotifyCallback("sleep 1 && echo awaited-done", None, ctx, jobId)
      cb <- IO(cbOpt.getOrElse(fail("makeNotifyCallback must not vanish")))
      _ <- cb(Right(ProcessResult("awaited-done\n", "", 0, "/tmp"))).attempt
      _ <- IO.sleep(200.millis)
      got <- received.get
      ledger <- BgTaskLedger.pending(sid)
      output <- BgTaskOutputStore.read(jobId, 0L)
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      val pushes = got.collect {
        case e: AgentCommand.ExternalEvent if e.metadata("bgTaskId").flatMap(_.asString).contains(jobId) => e
      }
      assertEquals(pushes, Nil, "an awaited completion must NOT be pushed to the agent a second time")
      assert(
        ledger.exists(_.jobId == jobId),
        s"the ledger row must still be written for an awaited completion (ledger=${ledger.map(_.jobId)})"
      )
      assert(output.isDefined, "output retention must still be finalized for an awaited completion")
  }

  test("T2(b) — makeNotifyCallback with agentActorRef=None returns a real callback (D5: no silent absence)") {
    val jobId = s"t2-noref-${scala.util.Random.nextInt(100000)}"
    val sid = "sid-t2-noref"
    val ctx = ToolContext(projectRoot = "/tmp", sessionId = Some(sid), agentActorRef = None)
    // 输出留存面先建缓冲（生产 = BashTool 起后台任务时 open）。
    (for
      _ <- BgTaskOutputStore.open(jobId, None)
      cbRaw <- IO(BashTool.makeNotifyCallback("echo noref-done", None, ctx, jobId))
      cb <- IO(cbRaw.getOrElse(fail("no-agentRef context must STILL produce a completion callback (D5)")))
      _ <- cb(Right(ProcessResult("noref-done\n", "", 0, "/tmp"))).attempt
      ledger <- BgTaskLedger.pending(sid)
      output <- BgTaskOutputStore.read(jobId, 0L)
    yield
      assert(ledger.exists(_.jobId == jobId), s"ledger row must exist without an agent ref (ledger=${ledger.map(_.jobId)})")
      assert(output.isDefined, "output retention must exist without an agent ref")
      assertEquals(output.map(_.status), Some("completed"), "the retained output must be finalized as completed")
    )
  }

  test("T2(c) — ledger-first ordering: the delivery leg already sees the ledger row (投递失败也留账)") {
    val system = ActorSystem(s"bash-overhaul-order-${scala.util.Random.nextInt(100000)}")
    val jobId = s"t2-order-${scala.util.Random.nextInt(100000)}"
    val sid = "sid-t2-order"
    for
      resources <- mkResources(system)
      _ <- resources.sessionStore.createSession(sid)
      seenLedgerFirst <- Ref.of[IO, Boolean](false)
      _ = BgInboxPort.install(new BgInboxPort.Face:
        def append(sessionId: String, event: AgentCommand.ExternalEvent): IO[Unit] =
          BgTaskLedger
            .pending(sid)
            .flatMap(es => seenLedgerFirst.set(es.exists(_.jobId == jobId)))
            .as(()))
      ctx = ToolContext(projectRoot = "/tmp", sessionId = Some(sid), agentActorRef = None)
      cb <- IO(
        BashTool
          .makeNotifyCallback("echo order-done", None, ctx, jobId)
          .getOrElse(fail("no-agentRef context must still produce a completion callback (D5)"))
      )
      _ <- cb(Right(ProcessResult("order-done\n", "", 0, "/tmp"))).attempt
      ledgerFirst <- seenLedgerFirst.get
      ledger <- BgTaskLedger.pending(sid)
      // 还原生产注入面（SharedResources 构造时装的是这一份）。
      _ = BgInboxPort.install(nebflow.agent.BgInboxBackstop.face(resources))
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assert(ledgerFirst, "the ledger row must ALREADY exist when the delivery leg runs (先落账再投递)")
      assert(ledger.exists(_.jobId == jobId), "the ledger row survives whatever the delivery leg does")
  }

end BashOverhaulNotifySpec
