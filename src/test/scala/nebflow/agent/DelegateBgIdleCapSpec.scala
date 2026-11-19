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
import nebflow.core.tools.{BgTaskRegistry, FileLockManager, ShellSession}
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, Message, MessageRole, StreamChunk, ThinkingConfig, SubAgentTask}

import scala.concurrent.duration.*

/**
 * **R5 · bg-turn-end barrier 加 idle 上限放行**（停顿审计 2026-11-04 §4-R5 / §5 排序 3）。
 *
 * 病灶：delegate 回合尾的等待屏障只认**墙钟总额**（`completionCapMs`，默认 2h）。一个
 * 「永不结束、也不再产出」的后台作业把回合吊在 hold（实证 `79c40e17`：14:17:34 起
 * 被 `Find stand-website dir` 吊 1h49m，未到 2h）⇒ 回合不收尾 ⇒ 无 Completed ⇒
 * 合并窗 leg 永不安装 ⇒ 队头不可投递 ⇒ 整条合并队列 2h 零放行（R1 的上游）。
 *
 * 修法：再加一条**空闲**上限（`bgIdleCapMs`，默认 15min）——等待集里每个作业最近
 * 都没有新输出且已超上限 ⇒ 放行回合收尾，带诚实注记交付父会话。
 *
 * 本 spec 的夹具 = 「barrier 后挂着一个永不结束的 bg 回合」：
 *   - **红**：`bgIdleCapMs = 0`（= 本腿关闭，修复前行为）⇒ 父会话零通知；
 *   - **绿**：`bgIdleCapMs` 数百毫秒 ⇒ 到上限放行，注记写明 IDLE cap 与作业名；
 *   - **纯判据**：`bgIdleCapReached` 的全集/任一/关闭三态。
 */
class DelegateBgIdleCapSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 90.seconds

  /**
   * 章节记录：本 spec 的数据根指到临时目录 —— 终态交付走**父会话持久邮箱**
   * （`PathUtil.dataRoot/sessions/<sid>/mailbox`），测试不得往真 `.stand` 里写。
   */
  private val tempRoot: os.Path = os.pwd / "target" / "test-bg-idle-cap"
  private val originalRoot = nebflow.shared.PathUtil.dataRoot

  private def resetRoot(): Unit =
    if os.exists(tempRoot) then
      try os.remove.all(tempRoot)
      catch case _: Throwable => ()
    os.makeDir.all(tempRoot)
    nebflow.shared.PathUtil.setDataRoot(tempRoot)

  resetRoot()

  override def afterAll(): Unit =
    nebflow.shared.PathUtil.setDataRoot(originalRoot)

  private val fakeLlm = new LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("fake llm not expected here"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.raiseError[IO](new RuntimeException("fake llm not expected here"))

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
      prompt = "idle-cap test",
      description = "idle-cap test task",
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

  /** 真进程在册等待任务（`79c40e17` 形态：永不结束、也不再产出——`sleep 3600` 无输出）。 */
  private def registerNeverEndingTask(sessionId: String, jobId: String): IO[Unit] =
    for
      shell <- ShellSession.forSession(sessionId)
      _ <- shell.executeBackground("sleep 3600", jobIdOverride = Some(jobId))
      _ <- BgTaskRegistry.register(jobId, sessionId, "Find stand-website dir", kind = "local")
    yield ()

  private def cleanupWaitingTask(sessionId: String, jobId: String): IO[Unit] =
    ShellSession
      .peekSession(sessionId)
      .flatMap(_.fold(IO.unit)(s => s.cancelBackgroundJob(jobId).attempt.void)) *>
      ShellSession.destroySession(sessionId).attempt.void *>
      BgTaskRegistry.unregisterSession(Some(sessionId)).attempt.void

  private def parentEventsNow(events: List[AgentCommand]): List[AgentCommand.ExternalEvent] =
    events.collect { case e: AgentCommand.ExternalEvent => e }

  /** delegate 轨终态交付面 = 父会话**持久邮箱**（P2 邮箱化：不再 actor 直投）。 */
  private def terminalMail(parentSid: String): IO[List[nebflow.core.mail.SessionMailbox.Entry]] =
    nebflow.core.mail.SessionMailbox
      .scanPending(parentSid)
      .map(_.filter(_.kind == "delegate-terminal"))
      .handleErrorWith(_ => IO.pure(Nil))

  /**
   * 真 delegate 形态的注册表前置：记录在册 **且已申报**（状态槽 finish）。
   *
   * 为什么必须有：终态矩阵的「complete × **未申报**」腿（`Outcome.NudgeIdle`）按作者令
   * **零父投递**（只武装提醒）——本批边界明令不动它。本 spec 要观测的是「屏障是否放行
   * 回合收尾」，所以夹具取矩阵的 `complete × 已申报` 腿（父会话照常收到终态投递），
   * 红/绿两腿**只有** `bgIdleCapMs` 不同。
   */
  private def seedDeclaredReport(childSid: String, parentSid: String): IO[Unit] =
    IO {
      nebflow.core.delegate.DelegateRegistry.put(
        nebflow.core.delegate.DelegateRegistry.Record(
          id = childSid,
          executor = "nebflow",
          cwd = os.pwd.toString,
          project = None,
          taskId = Some("31"),
          parentSessionId = parentSid,
          title = s"idle-cap spec $childSid",
          createdAt = System.currentTimeMillis()
        )
      )
      nebflow.core.delegate.DelegateRegistry.updateReport(childSid, "finish", "turn done; the job never ends", "") match
        case Left(e)  => throw new RuntimeException(s"seed declared report failed: $e")
        case Right(_) => ()
    }

  // ══════════════════════════════════════════════════════════════════════
  // 纯判据（零等待）
  // ══════════════════════════════════════════════════════════════════════

  test("P0 bgIdleCapReached：全集空闲 ⇒ true；有一个还在产出 ⇒ false；启动未过窗 ⇒ false；cap<=0 ⇒ 关闭") {
    val now = 10_000_000L
    val cap = 60_000L
    // 全部作业最近活动都早于 now-cap（含「从未有输出」（None ⇒ 用启动时刻））
    assert(BackoffSupervisor.bgIdleCapReached(List((now - cap - 1L, None)), now, cap))
    assert(BackoffSupervisor.bgIdleCapReached(List((now - 5 * cap, Some(now - cap - 5L)), (now - cap, None)), now, cap))
    // 有一个作业刚刚有输出 ⇒ 还在产出，等它有价值
    assert(!BackoffSupervisor.bgIdleCapReached(List((now - 5 * cap, None), (now - 10L, Some(now - 10L))), now, cap))
    // 刚启动、还没到上限 ⇒ 不放行
    assert(!BackoffSupervisor.bgIdleCapReached(List((now - 100L, None)), now, cap))
    // 关闭本腿
    assert(!BackoffSupervisor.bgIdleCapReached(List((now - 10 * cap, None)), now, 0L))
    // 空集 ⇒ 不放行（无作业时屏障本身不会 hold）
    assert(!BackoffSupervisor.bgIdleCapReached(Nil, now, cap))
  }

  // ══════════════════════════════════════════════════════════════════════
  // 红 → 绿（同一夹具：barrier 后挂一个永不结束的 bg 回合）
  // ══════════════════════════════════════════════════════════════════════

  test("R5-红：idle 腿关闭（bgIdleCapMs=0 = 修复前行为）⇒ 父会话零交付，回合被永久吊住（墙钟总额 2h 才兜底）") {
    val system = ActorSystem("bgidle-red")
    val tmp = os.temp.dir()
    val childSid = "delegate-bgidle-red"
    val jobId = "job-bgidle-red"
    for
      resources <- mkResources(system, tmp)
      parent <- resources.sessionStore.createSession("Nebula-root", agentName = Some("Nebula"))
      parentSid = parent.id
      _ <- resources.subAgentTaskStore.recordTask(mkTask(parentSid, childSid))
      _ <- seedDeclaredReport(childSid, parentSid)
      _ <- registerNeverEndingTask(childSid, jobId)
      parentEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- system.spawn(mkRecordingCmd(parentEvents), "parent-rec-red")
      childEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      childRef <- system.spawn(mkStopOnStopProbe(childEvents), "bgidle-child-red")
      supervisor <- system.spawn(
        BackoffSupervisor(
          childRef = childRef,
          childSpawnFn = (_, _) => IO.raiseError(new RuntimeException("respawn must not happen in this spec")),
          childName = childSid,
          parentRef = Some(parentRef),
          description = "idle-cap red",
          agentName = "Worker",
          subagentId = childSid,
          parentSessionId = parentSid,
          resources = resources,
          initialPrompt = "wait for a never-ending job",
          source = "delegate",
          completionCapMs = 60_000L, // 墙钟总额远长于本测试窗
          bgIdleCapMs = 0L // ← 本腿关闭 = 修复前的唯一兜底
        ),
        "supervisor-bgidle-red"
      )
      started <- IO(System.currentTimeMillis())
      _ <- supervisor ! AgentEvent.Completed(childSid, List(assistantMsg("turn done; the job never ends")))
      _ <- IO.sleep(1500.millis) // 远长于绿腿的 idle 上限，红腿照样不动作
      held <- IO(System.currentTimeMillis() - started)
      mails <- terminalMail(parentSid)
      childCmds <- childEvents.get
      tasks <- resources.subAgentTaskStore.loadTasks(parentSid)
      _ <- cleanupWaitingTask(childSid, jobId)
      _ <- system.stopAll
    yield
      assertEquals(mails.size, 0, s"OLD behavior: nothing is delivered after ${held}ms (the turn is held by the idle job)")
      assert(!childCmds.exists(_.isInstanceOf[AgentCommand.Stop]), "the child is never stopped (session parked forever)")
      assertEquals(tasks.find(_.taskId == childSid).map(_.status), Some("running"), "the task row stays running — the parent waits for a wake that never comes")
  }

  test("R5-绿：idle 上限到点 ⇒ 放行回合收尾，父会话收到带 IDLE cap 诚实注记的终态") {
    val system = ActorSystem("bgidle-green")
    val tmp = os.temp.dir()
    val childSid = "delegate-bgidle-green"
    val jobId = "job-bgidle-green"
    for
      resources <- mkResources(system, tmp)
      parent <- resources.sessionStore.createSession("Nebula-root", agentName = Some("Nebula"))
      parentSid = parent.id
      _ <- resources.subAgentTaskStore.recordTask(mkTask(parentSid, childSid))
      _ <- seedDeclaredReport(childSid, parentSid)
      _ <- registerNeverEndingTask(childSid, jobId)
      parentEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- system.spawn(mkRecordingCmd(parentEvents), "parent-rec-green")
      childEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      childRef <- system.spawn(mkStopOnStopProbe(childEvents), "bgidle-child-green")
      supervisor <- system.spawn(
        BackoffSupervisor(
          childRef = childRef,
          childSpawnFn = (_, _) => IO.raiseError(new RuntimeException("respawn must not happen in this spec")),
          childName = childSid,
          parentRef = Some(parentRef),
          description = "idle-cap green",
          agentName = "Worker",
          subagentId = childSid,
          parentSessionId = parentSid,
          resources = resources,
          initialPrompt = "wait for a never-ending job",
          source = "delegate",
          completionCapMs = 60_000L, // 墙钟腿绝不参与本测试
          bgIdleCapMs = 600L // ← 空闲上限（spec 注入缝）
        ),
        "supervisor-bgidle-green"
      )
      started <- IO(System.currentTimeMillis())
      _ <- supervisor ! AgentEvent.Completed(childSid, List(assistantMsg("turn done; the job never ends")))
      // 交付面 = 父会话**持久邮箱**（delegate 轨 P2 邮箱化：不再 actor 直投）
      _ <- waitUntil(20.seconds)(terminalMail(parentSid).map(_.nonEmpty))
      released <- IO(System.currentTimeMillis() - started)
      _ <- waitUntil(20.seconds)(
        resources.subAgentTaskStore.loadTasks(parentSid).map(_.exists(t => t.taskId == childSid && t.status == "completed"))
      )
      mails <- terminalMail(parentSid)
      childCmds <- childEvents.get
      tasks <- resources.subAgentTaskStore.loadTasks(parentSid)
      _ <- cleanupWaitingTask(childSid, jobId)
      _ <- system.stopAll
    yield
      assertEquals(mails.size, 1, s"exactly one terminal delivery, got: ${mails.map(_.kind)}")
      val payload = mails.head.event.payload
      assertEquals(mails.head.event.eventType, "completed")
      assert(payload.contains("IDLE cap"), s"the honest note must name the idle leg: $payload")
      assert(payload.contains(jobId), s"the note names the pending job: $payload")
      assert(payload.contains("Find stand-website dir"), s"the note names the description: $payload")
      assert(payload.contains("turn done"), "the held turn's deliverable rides the forced delivery")
      assert(released < 20_000L, s"release happened on the idle cap, not the 60s wall clock (took ${released}ms)")
      assert(childCmds.exists(_.isInstanceOf[AgentCommand.Stop]), "the child is stopped at the forced terminal")
      assertEquals(tasks.find(_.taskId == childSid).map(_.status), Some("completed"))
  }

  test("R5-回归：等待集清空 ⇒ 干净放行（无 IDLE/墙钟注记，既有唤醒语义零漂移）") {
    val system = ActorSystem("bgidle-regress")
    val tmp = os.temp.dir()
    val childSid = "delegate-bgidle-regress"
    val jobId = "job-bgidle-regress"
    for
      resources <- mkResources(system, tmp)
      parent <- resources.sessionStore.createSession("Nebula-root", agentName = Some("Nebula"))
      parentSid = parent.id
      _ <- resources.subAgentTaskStore.recordTask(mkTask(parentSid, childSid))
      _ <- seedDeclaredReport(childSid, parentSid)
      _ <- registerNeverEndingTask(childSid, jobId)
      parentEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- system.spawn(mkRecordingCmd(parentEvents), "parent-rec-regress")
      childEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      childRef <- system.spawn(mkStopOnStopProbe(childEvents), "bgidle-child-regress")
      supervisor <- system.spawn(
        BackoffSupervisor(
          childRef = childRef,
          childSpawnFn = (_, _) => IO.raiseError(new RuntimeException("respawn must not happen in this spec")),
          childName = childSid,
          parentRef = Some(parentRef),
          description = "idle-cap regression",
          agentName = "Worker",
          subagentId = childSid,
          parentSessionId = parentSid,
          resources = resources,
          initialPrompt = "wait then finish",
          source = "delegate",
          completionCapMs = 60_000L,
          bgIdleCapMs = 30_000L // 足够长：唤醒腿必须先赢
        ),
        "supervisor-bgidle-regress"
      )
      _ <- supervisor ! AgentEvent.Completed(childSid, List(assistantMsg("held turn")))
      _ <- IO.sleep(300.millis)
      // 作业真完成（注销）+ 唤醒轮的 Completed
      _ <- BgTaskRegistry.unregister(jobId)
      _ <- supervisor ! AgentEvent.Completed(childSid, List(assistantMsg("job finished — final report")))
      _ <- waitUntil(20.seconds)(terminalMail(parentSid).map(_.nonEmpty))
      mails <- terminalMail(parentSid)
      _ <- cleanupWaitingTask(childSid, jobId)
      _ <- system.stopAll
    yield
      assertEquals(mails.size, 1, s"one clean terminal, got ${mails.size}")
      val payload = mails.head.event.payload
      assert(!payload.contains("IDLE cap"), s"a clean wake must not carry the forced-delivery note: $payload")
      assert(!payload.contains("[delegate-background-wait]"), s"nor the wall-clock cap note: $payload")
      assert(payload.contains("final report"), s"the release payload is the real report: $payload")
  }
end DelegateBgIdleCapSpec
