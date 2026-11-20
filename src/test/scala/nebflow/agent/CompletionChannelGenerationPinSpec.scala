package nebflow.agent

import cats.effect.{IO, Ref}
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import io.circe.JsonObject
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, Behavior, Behaviors}
import nebflow.actor.{AgentCommand, AgentEvent}
import nebflow.core.FileChangeTracker
import nebflow.core.compact.HistoryArchiver
import nebflow.core.mail.{CompletionDebtStore, SessionMailbox}
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.{
  FallbackAttempt,
  LlmHandle,
  LlmRequest,
  LlmResponse,
  Message,
  MessageRole,
  PathUtil,
  StreamChunk,
  ThinkingConfig,
  SubAgentTask
}

import scala.concurrent.duration.*

/**
 * #2141/#2178 独立定向终验测试钉（final-gate 验证轮新增；零产品改动）。
 *
 * 被验候选 fbfd51b43 的两个**无专测覆盖面**钉红：
 *
 *   1. **跨代第二份必达**（beed5aa 生产根因的最小生产接线复现）：第一轮终态已
 *      入父箱并消费（delivered 墓碑 `taskId#gen1`）后，Mail 续轮新完成铸新代
 *      （债 settled ⇒ record 换新代）→ 监督者重建（SessionResumption boot 重放
 *      腿）→ 第二份终态 **必须** 以 `taskId#gen2` 身份入父箱——裸 id 身份会被
 *      gen1 墓碑假去重吞掉（改前行为），候选身份带代 ⇒ 必达。
 *   2. **boot 对账代际前缀对账面**（MailboxBootSweep.synthesizeOne 的 genBearing
 *      三腿）：真实终态在盘/在消费/已消费的任一代形态都必须判「账信相符」，
 *      绝不重合成已交付终态。
 *
 * 夹具形状与 [[BackoffSupervisorSupervisionCheckSpec]] 同款（真 SessionMailbox /
 * 真 CompletionDebtStore / 真 scheduleOnce；隔离 temp data root；阶梯关闭保持
 * 单一被测面）。
 */
class CompletionChannelGenerationPinSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-completion-channel-pin"
  private val originalRoot = PathUtil.dataRoot

  private def resetRoot(): Unit =
    if os.exists(tempRoot) then
      try os.remove.all(tempRoot)
      catch case _: Throwable => ()
    os.makeDir.all(tempRoot)
    PathUtil.setDataRoot(tempRoot)

  resetRoot()

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  private val fakeLlm = new LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("fake llm not expected here"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): fs2.Stream[IO, StreamChunk] =
      fs2.Stream.raiseError[IO](new RuntimeException("fake llm not expected here"))

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
      prompt = "generation pin test",
      description = "generation pin test task",
      status = "running",
      retryCount = 0,
      spawnedAt = System.currentTimeMillis(),
      completedAt = None,
      lastError = None,
      source = "delegate"
    )

  /** delegate 轨「complete × 已申报」前置（生产消费判账面依赖 registry 记录）。 */
  private def seedDeclaredReport(childSid: String, parentSid: String): IO[Unit] =
    IO {
      nebflow.core.delegate.DelegateRegistry.put(
        nebflow.core.delegate.DelegateRegistry.Record(
          id = childSid,
          executor = "nebflow",
          cwd = os.pwd.toString,
          project = None,
          taskId = Some("2141"),
          parentSessionId = parentSid,
          title = s"generation pin $childSid",
          createdAt = System.currentTimeMillis()
        )
      )
      nebflow.core.delegate.DelegateRegistry.updateReport(childSid, "finish", "declared", "") match
        case Left(e) => throw new RuntimeException(s"seed declared report failed: $e")
        case Right(_) => ()
    }

  private def waitUntil(timeout: FiniteDuration, every: Long = 20L)(cond: IO[Boolean]): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError("waitUntil: condition not met in time"))
          else IO.sleep(every.millis) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  private def pendingMail(parentSid: String): IO[List[SessionMailbox.Entry]] =
    SessionMailbox
      .scanPending(parentSid)
      .map(_.filter(_.kind == "delegate-terminal"))
      .handleErrorWith(_ => IO.pure(Nil))

  private def mkProbeBehavior(record: Ref[IO, List[AgentCommand]]): Behavior[AgentCommand] =
    def loop: Behavior[AgentCommand] =
      Behaviors.receiveMessage: cmd =>
        record.update(_ :+ cmd).as(loop)
    loop

  private def spawnSupervisor(
    system: ActorSystem,
    resources: SharedResources,
    childRef: nebflow.actor.ActorRef[AgentCommand],
    childSid: String,
    parentSid: String
  ) =
    system.spawn(
      BackoffSupervisor(
        childRef = childRef,
        childSpawnFn = (_, _) => IO.raiseError(new RuntimeException("respawn must not happen in this pin")),
        childName = childSid,
        parentRef = None,
        description = "generation pin delegate",
        agentName = "Worker",
        subagentId = childSid,
        parentSessionId = parentSid,
        resources = resources,
        initialPrompt = "cross-generation pin",
        source = "delegate",
        completionCapMs = 60_000L,
        bgIdleCapMs = 0L,
        checkBackoffMs = Nil // 阶梯关闭：本钉只测代际身份面，不测检查腿
      ),
      s"supervisor-pin-$childSid-${System.nanoTime()}"
    )

  // ══════════════════════════════════════════════════════════════════════
  // PIN 1：跨代第二份必达（生产根因形态：gen1 已消费墓碑不得吞 gen2 续轮终态）
  // ══════════════════════════════════════════════════════════════════════

  test("PIN1: Mail-continuation round 2 MUST reach the parent box after round 1 was consumed (gen-bearing identity)") {
    val system = ActorSystem("pin-cross-gen")
    val tmp = os.temp.dir()
    val childSid = "delegate-pin-crossgen"
    val msgs1 = List(Message(MessageRole.Assistant, Left("round one final report")))
    val msgs2 = List(Message(MessageRole.Assistant, Left("round two continuation report")))
    for
      resources <- mkResources(system, tmp)
      parent <- resources.sessionStore.createSession("Nebula-root", agentName = Some("Nebula"))
      parentSid = parent.id
      _ <- resources.subAgentTaskStore.recordTask(mkTask(parentSid, childSid))
      _ <- seedDeclaredReport(childSid, parentSid)
      probe <- Ref.of[IO, List[AgentCommand]](Nil)
      childRef <- system.spawn(mkProbeBehavior(probe), "pin-child-probe")
      // 监督者先在场（setup 时债未落 ⇒ 不触发 boot 重放腿），再走真实执行轮链路。
      supervisor1 <- spawnSupervisor(system, resources, childRef, childSid, parentSid)
      // ── 第一轮：record 铸 gen1（AgentFinishTurn 同点）→ Completed → 干净放行 ──
      _ <- CompletionDebtStore.record(childSid, parentSid, msgs1)
      gen1 <- CompletionDebtStore.pending(childSid).map(_.toOption.flatten.map(_.generation).getOrElse(""))
      _ <- supervisor1 ! AgentEvent.Completed(childSid, msgs1)
      _ <- waitUntil(15.seconds)(pendingMail(parentSid).map(_.nonEmpty))
      round1 <- pendingMail(parentSid)
      _ = assertEquals(round1.size, 1, "exactly one terminal entry for round 1")
      _ = assertEquals(
        clue(round1.head.identity),
        s"$childSid#$gen1",
        "terminal identity carries the durable generation"
      )
      _ = assert(
        clue(round1.head.event.payload).contains("round one final report"),
        "round-1 payload is the real report"
      )
      // ── 消费腿（AgentIdle 注入同点）：take → unlink 落墓碑 → 可靠 ACK 清债 ──
      _ <- SessionMailbox.take(parentSid, round1)
      _ <- SessionMailbox.unlinkTaken(parentSid, round1.map(_.identity))
      acked <- CompletionDebtStore.acknowledge(childSid, parentSid, msgs1, gen1)
      _ = assert(acked, "reliable ACK settles the delivered round-1 debt")
      afterConsume <- pendingMail(parentSid)
      _ = assertEquals(afterConsume.size, 0, "consumed entry leaves the pending box")
      // ── Mail 续轮：settled 债重开 = 新代（record 换新代）→ 监督者重建重放腿 ──
      _ <- CompletionDebtStore.record(childSid, parentSid, msgs2)
      gen2 <- CompletionDebtStore.pending(childSid).map(_.toOption.flatten.map(_.generation).getOrElse(""))
      _ = assert(gen2.nonEmpty && gen2 != gen1, "the continuation round mints a NEW generation (settled debt re-opens)")
      supervisor2 <- spawnSupervisor(system, resources, childRef, childSid, parentSid) // setup 沿债重放 Completed(msgs2)
      _ <- waitUntil(15.seconds)(pendingMail(parentSid).map(_.nonEmpty))
      round2 <- pendingMail(parentSid)
      _ <- system.stopAll
      tombstone <- SessionMailbox.deliveredContains(parentSid, Set(s"$childSid#$gen1"))
    yield
      assertEquals(clue(round2.size), 1, "round-2 terminal is delivered — NOT swallowed by the round-1 tombstone")
      assertEquals(clue(round2.head.identity), s"$childSid#$gen2", "round-2 identity carries the NEW generation")
      assert(
        clue(round2.head.event.payload).contains("round two continuation report"),
        "round-2 payload is the continuation report"
      )
      assertEquals(
        clue(round2.head.event.metadata("completionGeneration").flatMap(_.asString)),
        Some(gen2),
        "event metadata carries the same generation"
      )
      assert(clue(tombstone).nonEmpty, "round-1 tombstone still records the consumed generation")
      assert(!clue(tombstone).contains(s"$childSid#$gen2"), "the unconsumed round-2 generation must NOT be tombstoned")
    end for
  }

  // ══════════════════════════════════════════════════════════════════════
  // PIN 2：boot 对账 gen 前缀三腿（pending / taken / delivered 墓碑任一代形态
  // 都判「账信相符」——绝不重合成已交付终态）
  // ══════════════════════════════════════════════════════════════════════

  private def sweepFixture(name: String): IO[(SubAgentTaskStore, SessionStore, String, String)] =
    for
      _ <- IO {
        val dir = tempRoot / name
        if os.exists(dir) then os.remove.all(dir)
        os.makeDir.all(dir)
        PathUtil.setDataRoot(dir)
      }
      taskStore = SubAgentTaskStore(tempRoot / name / "subagent-tasks")
      sessionStore = SessionStore(tempRoot / name / "sessions", tempRoot / name / "tasks")
      _ <- sessionStore.getOrCreateSession(s"pin-parent-$name", "p")
      child = s"delegate-pin-sweep-$name"
      _ <- taskStore.saveTasks(
        s"pin-parent-$name",
        List(
          mkTask(s"pin-parent-$name", child).copy(status = "completed", completedAt = Some(System.currentTimeMillis()))
        )
      )
      _ <- MailboxBootSweep.run(taskStore, sessionStore) // 播种 boot 纪元（空窗）
    yield (taskStore, sessionStore, s"pin-parent-$name", child)

  private def seedEntry(parentSid: String, identity: String, takeIt: Boolean, unlinkIt: Boolean): IO[Unit] =
    for
      _ <- SessionMailbox.mailOnce(
        parentSid,
        AgentCommand.ExternalEvent(
          source = "delegate",
          eventType = "completed",
          payload = "consumed in a previous lifetime",
          metadata = JsonObject.empty,
          correlationId = Some(parentSid)
        ),
        kind = "delegate-terminal",
        identity = identity,
        coalesce = false,
        producer = "generation-pin"
      )
      entries <- SessionMailbox.scanPending(parentSid)
      _ <- IO.whenA(takeIt)(SessionMailbox.take(parentSid, entries.filter(_.identity == identity)).void)
      _ <- IO.whenA(unlinkIt)(SessionMailbox.unlinkTaken(parentSid, List(identity)))
    yield ()

  test("PIN2a: a generation-bearing consumed tombstone is NOT re-synthesized by the boot sweep") {
    for
      (taskStore, sessionStore, parentSid, child) <- sweepFixture("tombstone")
      _ <- seedEntry(parentSid, s"$child#gen-abc", takeIt = true, unlinkIt = true)
      report <- MailboxBootSweep.run(taskStore, sessionStore)
      pend <- SessionMailbox.scanPending(parentSid)
    yield
      assertEquals(
        clue(report.synthesized),
        Nil,
        "gen-bearing tombstone = accounted; no re-synthesis of a consumed terminal"
      )
      assertEquals(clue(pend), Nil)
  }

  test("PIN2b: a generation-bearing PENDING entry is accounted (no synthesis)") {
    for
      (taskStore, sessionStore, parentSid, child) <- sweepFixture("pending")
      _ <- seedEntry(parentSid, s"$child#gen-def", takeIt = false, unlinkIt = false)
      report <- MailboxBootSweep.run(taskStore, sessionStore)
      pend <- SessionMailbox.scanPending(parentSid)
    yield
      assertEquals(clue(report.synthesized), Nil, "gen-bearing entry on disk = accounted")
      assertEquals(clue(pend.map(_.identity)), List(s"$child#gen-def"), "the live entry is untouched")
  }

  test("PIN2c: a generation-bearing TAKEN (consumption in flight) entry is accounted (no synthesis)") {
    for
      (taskStore, sessionStore, parentSid, child) <- sweepFixture("taken")
      _ <- seedEntry(parentSid, s"$child#gen-ghi", takeIt = true, unlinkIt = false)
      report <- MailboxBootSweep.run(taskStore, sessionStore)
      taken <- SessionMailbox.scanTaken(parentSid)
    yield
      assertEquals(clue(report.synthesized), Nil, "gen-bearing taken entry = accounted (consumer still holds it)")
      assertEquals(clue(taken.map(_.identity)), List(s"$child#gen-ghi"), "the in-flight entry is untouched")
  }
end CompletionChannelGenerationPinSpec
