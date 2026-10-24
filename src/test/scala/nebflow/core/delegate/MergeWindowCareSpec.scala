package nebflow.core.delegate

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.JsonObject
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, ActorSystem, AgentCommand, AgentEvent, AgentKind, AgentRecord, Behavior, Behaviors}
import nebflow.agent.{AgentLibrary, SharedResources}
import nebflow.core.{FileChangeTracker, RateLimiter, SessionStore}
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.BgInboxPort
import nebflow.core.tools.FileLockManager
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk, ThinkingConfig}

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.*

/**
 * 合并窗台账事件化看护判读（steward 批 D3-⑤，2026-10-23）——DelegateBudget
 * wall-clock 预算退役后的另一半活性替代：
 *
 *  - ① 合并轮在飞 ∧ 监督者司机已死 ⇒ 轮驱动：条目按「被拒不堵塞」语义留队待
 *    重试（pendingRetry），窗释放轮转下一条 + **升级投活 root**（D3-① 落点：
 *    明确死亡事件、无人认领 ⇒ 投最终责任方）；幂等（第二拍零重复动作）；
 *  - ② 提醒未发 ∧ delegate 会话不在注册表（孤儿条目）⇒ 出队收口；
 *  - ③ 活司机 ⇒ 零动作（正常等待终态回调）。
 *
 * 判据 = 台账状态 × actor 活性信号，**零时间判据**（红线）。
 */
class MergeWindowCareSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-merge-window-care"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    os.remove.all(tempRoot / "sessions")
    os.remove.all(tempRoot / "chains")
    MergeQueue.reset()

  override def afterEach(context: munit.AfterEach): Unit =
    MergeQueue.reset()
    MergeQueue.installLivenessProbe(_ => IO.pure(true))

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

  private class Sink(records: ListBuffer[String]) extends Behavior[AgentCommand]:

    def receive(ctx: nebflow.actor.ActorContext[AgentCommand], msg: AgentCommand): IO[Behavior[AgentCommand]] =
      msg match
        case AgentCommand.UserInput(text, _, _, _, _, _, _, _, _, _, _, _, _) => IO { records += text; this }
        case _ => IO.pure(this)

  private def mkRecordingRef(
    system: ActorSystem,
    name: String,
    record: Ref[IO, List[AgentCommand]]
  ): IO[ActorRef[AgentCommand]] =
    def loop: Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand](cmd => record.update(_ :+ cmd).as(loop))
    system.spawn(loop, name)

  /** 条目 + (child, supervisor) refs。 */
  private case class Rig(entry: MergeQueue.Entry, child: ActorRef[AgentCommand], sup: ActorRef[AgentEvent])

  private def mkEntry(
    system: ActorSystem,
    delegateId: String,
    projectKey: String,
    readyAt: Long,
    reminderSent: Boolean
  ): IO[Rig] =
    for
      child <- system.spawn(new Sink(ListBuffer.empty[String]), s"child-$delegateId")
      sup <- system.spawn(
        new Behavior[AgentEvent]:
          def receive(ctx: nebflow.actor.ActorContext[AgentEvent], msg: AgentEvent): IO[Behavior[AgentEvent]] =
            IO.pure(this)
        ,
        s"sup-$delegateId"
      )
    yield Rig(
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
        readyAt = readyAt,
        childRef = child,
        supervisorRef = sup,
        reminderSentAt = if reminderSent then Some(System.currentTimeMillis()) else None
      ),
      child,
      sup
    )

  // ── ① 司机死 → 留队待重试 + 升级投活 root ────────────────────────────

  test(
    "⑤ dead round driver: entry kept pending retry, window released, escalation lands on the LIVE root; idempotent"
  ) {
    val system = ActorSystem(s"mwc-1-${scala.util.Random.nextInt(100000)}")
    val records = ListBuffer.empty[String]
    for
      resources <- mkResources(system)
      received <- Ref.of[IO, List[AgentCommand]](Nil)
      root <- mkRecordingRef(system, "root-sink", received)
      _ <- resources.agentRegistry.update(
        _ + ("root-1" -> AgentRecord("root-1", root, AgentKind.Root, "root-1"))
      )
      rig <- mkEntry(system, "d-m1", "p-mwc", readyAt = 1_000L, reminderSent = true)
      _ <- IO { MergeQueue.enqueue(rig.entry) } // 合并轮在飞（窗持）
      _ <- IO.sleep(60.millis)
      _ <- system.stop(rig.sup) // 司机死（死亡事件，非超时）
      findings <- MergeWindowCare.care(resources)
      kept = MergeQueue.find("d-m1")
      _ <- IO.sleep(80.millis)
      got <- received.get
      findings2 <- MergeWindowCare.care(resources)
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(findings.map(_.action), List(MergeWindowCare.ActionRoundDriverDead), "the dead driver is caught")
      assertEquals(kept.map(_.pendingRetry), Some(true), "the entry is kept pending retry (refused-head semantics)")
      assertEquals(
        MergeQueue.isInFlight("p-mwc"),
        false,
        "the single-flight window is released (queue not wedged on the corpse)"
      )
      val events = got.collect { case e: AgentCommand.ExternalEvent => e }
      assertEquals(events.size, 1, "exactly one escalation event reached the live root")
      assertEquals(events.head.source, "merge-window-care")
      assertEquals(events.head.metadata("delegateId").flatMap(_.asString), Some("d-m1"))
      assertEquals(findings2, Nil, "second sweep is a no-op (pendingRetry idempotence)")
    end for
  }

  // ── ② 孤儿 pre-injection 条目 → 出队收口 ─────────────────────────────

  test("⑤ orphan pre-injection entry (no live session) is dequeued") {
    val system = ActorSystem(s"mwc-2-${scala.util.Random.nextInt(100000)}")
    for
      resources <- mkResources(system)
      rig <- mkEntry(system, "d-o1", "p-mwc2", readyAt = 1_000L, reminderSent = false)
      // 注入目标已死 ⇒ enqueue 走 deferred 腿（留队不投）；delegate 无注册表行 = 孤儿。
      _ = MergeQueue.installLivenessProbe(ref => IO.pure(ref != rig.child))
      _ <- IO { MergeQueue.enqueue(rig.entry) }
      _ <- IO.sleep(60.millis)
      _ <- system.stop(rig.sup) // 司机死 ⇒ 台账判读进孤儿出队腿（提醒未发 ∧ 无会话行）
      before = MergeQueue.find("d-o1")
      findings <- MergeWindowCare.care(resources)
      after = MergeQueue.find("d-o1")
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assert(before.isDefined, "sanity: the orphan entry was queued")
      assertEquals(
        findings.map(_.action),
        List(MergeWindowCare.ActionOrphanDequeue),
        "the orphan is dequeued by the care sweep"
      )
      assertEquals(after, None, "the orphan entry is gone after the sweep")
    end for
  }

  // ── ③ 活司机 → 零动作 ────────────────────────────────────────────────

  test("⑤ live driver ⇒ zero action (a healthy waiting merge round is never touched)") {
    val system = ActorSystem(s"mwc-3-${scala.util.Random.nextInt(100000)}")
    for
      resources <- mkResources(system)
      rig <- mkEntry(system, "d-live", "p-mwc3", readyAt = 1_000L, reminderSent = true)
      _ <- resources.agentRegistry.update(
        _ + ("d-live" -> AgentRecord("d-live", rig.child, AgentKind.Delegate, "root-1"))
      )
      _ <- IO { MergeQueue.enqueue(rig.entry) }
      _ <- IO.sleep(60.millis)
      findings <- MergeWindowCare.care(resources)
      kept = MergeQueue.find("d-live")
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(findings, Nil, "a live merge round is left alone")
      assert(kept.isDefined, "the entry stays queued")
      assertEquals(kept.map(_.pendingRetry), Some(false))
    end for
  }

end MergeWindowCareSpec
