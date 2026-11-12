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
import nebflow.core.tools.{BgTaskLedger, BgTaskOutputStore, BgTaskRegistry, FileLockManager, ShellSession}
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
 * 修复批 2026-10-24 fix 3「两种死法统一收口」spec：bg-wait cap 兜底触发时，
 * 未结任务的「完成事件+输出尾段」写入持久账本供续会话拾取（旧行为 = 直接丢）。
 *
 * A-4（DelegateBackgroundHoldSpec）钉的是诚实注记交付；本 spec 钉的是**账本面**：
 * cap 触发 ⇒ 每个仍未结任务一条 orphan 条目（含 BgTaskOutputStore 尾段快照）；
 * 此后真实完成事件经 recordCompletion 原位升级（kind 不降级、payload 落账）。
 */
class BackgroundCompletionCapLedgerSpec extends CatsEffectSuite:

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

  /** delegate 轨终态交付面 = 父会话**持久邮箱**（P2 邮箱化：不再 actor 直投）。 */
  private def terminalMail(parentSid: String): IO[List[nebflow.core.mail.SessionMailbox.Entry]] =
    nebflow.core.mail.SessionMailbox
      .scanPending(parentSid)
      .map(_.filter(_.kind == "delegate-terminal"))
      .handleErrorWith(_ => IO.pure(Nil))

  /**
   * 真 delegate 形态的注册表前置：记录在册 **且已申报**（状态槽 finish）。
   *
   * 为什么必须有：终态矩阵的「complete × 未申报」腿（`Outcome.NudgeIdle`）按作者令
   * 零父投递（只武装提醒）——本 spec 观测的是 cap 触发的诚实注记交付，夹具取矩阵的
   * `complete × 已申报` 腿。
   */
  private def seedDeclaredReport(childSid: String, parentSid: String): IO[Unit] =
    IO {
      nebflow.core.delegate.DelegateRegistry.put(
        nebflow.core.delegate.DelegateRegistry.Record(
          id = childSid,
          executor = "nebflow",
          cwd = os.pwd.toString,
          project = None,
          taskId = Some("33"),
          parentSessionId = parentSid,
          title = s"cap ledger spec $childSid",
          createdAt = System.currentTimeMillis()
        )
      )
      nebflow.core.delegate.DelegateRegistry.updateReport(childSid, "finish", "cap ledger done", "") match
        case Left(e)  => throw new RuntimeException(s"seed declared report failed: $e")
        case Right(_) => ()
    }

  test("C1 cap 触发 ⇒ 未结任务孤儿入账（含输出尾段快照）+ 诚实注记照旧交付"):
    val prevRoot = PathUtil.dataRoot
    val tmpRoot = os.temp.dir()
    PathUtil.setDataRoot(tmpRoot / "data")
    val system = ActorSystem("bgwait-ledger-1")
    val tmp = os.temp.dir()
    val childSid = "delegate-capledger-1"
    val jobId = "job-capledger-1"
    val program = for
      resources <- mkResources(system, tmp)
      // 父会话必须是**真会话**：邮箱生产者按父会话在场判定投递
      // （MailboxProducer：会话已删 ⇒ parent-session-gone 跳过写箱）。
      parent <- resources.sessionStore.createSession("Nebula-root", agentName = Some("Nebula"))
      parentSid = parent.id
      _ <- resources.subAgentTaskStore.recordTask(mkTask(parentSid, childSid))
      _ <- seedDeclaredReport(childSid, parentSid)
      // P2 death-watch 批：fixture 升级为真进程（真实 ShellSession + 后台 sleep）——
      // 活性探针读内核进程事实，合成行会被判 owner-lost 收割，cap 语义无从钉住。
      shell <- ShellSession.forSession(childSid)
      _ <- shell.executeBackground("sleep 3600", jobIdOverride = Some(jobId))
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
          completionCapMs = 300.millis.toMillis
        ),
        "supervisor-capledger-1"
      )
      _ <- supervisor ! AgentEvent.Completed(childSid, List(assistantMsg("turn done; bg job still running")))
      // cap 300ms 后强制终判：诚实注记（回归面，A-4 语义零漂移）——交付面 = 父**持久邮箱**
      _ <- waitUntil(15.seconds)(
        terminalMail(parentSid).map(
          _.exists(e => e.event.eventType == "completed" && e.event.payload.contains("[delegate-background-wait]"))
        )
      )
      // 账本面：孤儿条目 = 未结任务的完成事件+输出尾段供续会话拾取（fix 3 本体）
      _ <- waitUntil(10.seconds)(
        BgTaskLedger.pending(childSid).map(_.exists(e => e.jobId == jobId && e.kind == "orphan"))
      )
      pendingNow <- BgTaskLedger.pending(childSid)
      // 此后真实完成事件到达：recordCompletion 原位升级（不降级、payload 落账）
      _ <- BgTaskLedger.recordCompletion(
        childSid,
        jobId,
        "Full test suite (sbt -batch test)",
        "completed",
        "[Background task completed] \"Full test suite (sbt -batch test)\":\nall green"
      )
      upgraded <- BgTaskLedger.pending(childSid)
      mails <- terminalMail(parentSid)
      _ <- shell.cancelBackgroundJob(jobId).attempt.void
      _ <- ShellSession.destroySession(childSid).attempt.void
      _ <- BgTaskRegistry.unregisterSession(Some(childSid))
      _ <- system.stopAll
    yield
      // 交付面（本批迁移的断言面）：诚实注记经父**持久邮箱**恰一条
      assertEquals(mails.size, 1, s"exactly one delegate-terminal mail, got: ${mails.map(_.kind)}")
      assertEquals(mails.head.kind, "delegate-terminal")
      assertEquals(mails.head.event.eventType, "completed")
      assert(
        mails.head.event.payload.contains("[delegate-background-wait]"),
        s"honest note rides the mailbox delivery: ${mails.head.event.payload}"
      )
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
    program.unsafeRunSync()
    PathUtil.setDataRoot(prevRoot)
    os.remove.all(tmpRoot)
    os.remove.all(tmp)
end BackgroundCompletionCapLedgerSpec
