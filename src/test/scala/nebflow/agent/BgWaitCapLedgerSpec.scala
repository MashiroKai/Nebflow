package nebflow.agent

import cats.effect.{IO, Ref}
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import fs2.Stream
import io.circe.JsonObject
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, Behavior, Behaviors}
import nebflow.actor.{AgentCommand, AgentEvent, AgentKind, AgentRecord}
import nebflow.core.FileChangeTracker
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{BgTaskLedger, BgTaskOutputStore, BgTaskRegistry, FileLockManager}
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, Message, MessageRole, PathUtil, StreamChunk, ThinkingConfig, SubAgentTask}

import scala.concurrent.duration.*

/**
 * 修复批 2026-10-24 fix 3「两种死法统一收口」spec：bg-wait cap 兜底触发时，
 * 未结任务的「完成事件+输出尾段」写入持久账本供续会话拾取（旧行为 = 直接丢）。
 *
 * A-4（DelegateBgWaitHoldSpec）钉的是诚实注记交付；本 spec 钉的是**账本面**：
 * cap 触发 ⇒ 每个仍未结任务一条 orphan 条目（含 BgTaskOutputStore 尾段快照）；
 * 此后真实完成事件经 recordCompletion 原位升级（kind 不降级、payload 落账）。
 */
class BgWaitCapLedgerSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val fakeLlm = new LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("fake llm not expected here"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.raiseError[IO](new RuntimeException("fake llm not expected here"))

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
      prompt = "cap ledger test",
      description = "cap ledger test task",
      status = "running",
      retryCount = 0,
      spawnedAt = System.currentTimeMillis(),
      completedAt = None,
      lastError = None,
      source = "delegate"
    )

  private def assistantMsg(text: String): Message =
    Message(MessageRole.Assistant, Left(text))

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 50.millis)(
    cond: IO[Boolean]
  ): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError("waitUntil: condition not met in time"))
          else IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  test("C1 cap 触发 ⇒ 未结任务孤儿入账（含输出尾段快照）+ 诚实注记照旧交付"):
    val prevRoot = PathUtil.dataRoot
    val tmpRoot = os.temp.dir()
    PathUtil.setDataRoot(tmpRoot / "data")
    val system = ActorSystem("bgwait-ledger-1")
    val tmp = os.temp.dir()
    val parentSid = "capledger-parent-1"
    val childSid = "delegate-capledger-1"
    val jobId = "job-capledger-1"
    for
      resources <- mkResources(system, tmp)
      _ <- resources.subAgentTaskStore.recordTask(mkTask(parentSid, childSid))
      _ <- BgTaskRegistry.register(jobId, childSid, "Full test suite (sbt -batch test)", kind = "local")
      // 输出缓冲预置：cap 孤儿登记的尾段快照面（「输出尾段」入账的可断言载体）
      buffer <- BgTaskOutputStore.open(jobId)
      _ <- IO(buffer.append("stuck compile output line A"))
      _ <- IO(buffer.append("stuck compile output line B"))
      parentEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- system.spawn(mkRecordingCmd(parentEvents), "parent-capledger-1")
      childEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      childRef <- system.spawn(Behaviors.stopped[AgentCommand], "capledger-child-1")
      supervisor <- system.spawn(
        BackoffSupervisor(
          childRef = childRef,
          childSpawnFn = (_, _) => IO.raiseError(new RuntimeException("respawn must not happen in this spec")),
          childName = childSid,
          parentRef = Some(parentRef),
          description = "cap ledger test task",
          agentName = "Worker",
          subagentId = childSid,
          parentSessionId = parentSid,
          resources = resources,
          initialPrompt = "task with a never-finishing bg job",
          source = "delegate",
          bgWaitCapMs = 300.millis.toMillis
        ),
        "supervisor-capledger-1"
      )
      _ <- supervisor ! AgentEvent.Completed(childSid, List(assistantMsg("turn done; bg job still running")))
      // cap 300ms 后强制终判：诚实注记（回归面，A-4 语义零漂移）
      _ <- waitUntil(15.seconds)(
        parentEvents.get.map(events =>
          events.collect { case e: AgentCommand.ExternalEvent =>
            e
          }.exists(e => e.eventType == "completed" && e.payload.contains("[delegate-bg-wait]"))
        )
      )
      // 账本面：孤儿条目 = 未结任务的完成事件+输出尾段供续会话拾取（fix 3 本体）
      _ <- waitUntil(10.seconds)(
        BgTaskLedger.pending(childSid).map(_.exists(e => e.jobId == jobId && e.kind == "orphan"))
      )
      pendingNow <- BgTaskLedger.pending(childSid)
      // 此后真实完成事件到达：recordCompletion 原位升级（不降级、payload 落账）
      _ <- BgTaskLedger.recordCompletion(childSid, jobId, "Full test suite (sbt -batch test)", "completed",
        "[Background task completed] \"Full test suite (sbt -batch test)\":\nall green")
      upgraded <- BgTaskLedger.pending(childSid)
      _ <- BgTaskRegistry.unregisterSession(Some(childSid))
      _ <- system.stopAll
    yield
      val orphan = pendingNow.find(_.jobId == jobId).get
      assertEquals(orphan.kind, "orphan", "cap hit with a pending task ⇒ orphan entry in the ledger")
      assertEquals(orphan.description, "Full test suite (sbt -batch test)")
      assert(
        orphan.tailSnapshot.exists(_.contains("stuck compile output line B")),
        s"output tail snapshot carried: ${orphan.tailSnapshot}"
      )
      val after = upgraded.find(_.jobId == jobId).get
      assertEquals(after.kind, "completion", "late real completion upgrades the orphan in place")
      assert(after.payload.contains("all green"), "completion payload (event + tail) now in the ledger")
      assert(!after.consumed, "upgraded entry is pickable by a revived session")
      ()
    end for
    .unsafeRunSync()
    PathUtil.setDataRoot(prevRoot)
    os.remove.all(tmpRoot)
    os.remove.all(tmp)
  end test
end BgWaitCapLedgerSpec
