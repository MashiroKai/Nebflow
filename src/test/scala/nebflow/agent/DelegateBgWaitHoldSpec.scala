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
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, Message, MessageRole, PathUtil, StreamChunk, ThinkingConfig, SubAgentTask}

import scala.concurrent.duration.*

/**
 * sleep-poll 禁令批 A 段 spec：delegate 等待屏障（wait barrier）。
 *
 * 根因锚 = delegate-nebflow-747cd36a：交付链要求回归绿 + 「结束回合=会话终结=
 * 后台作业被带走」的历史教训 ⇒ agent 选择 sleep 570 心跳轮询 5h16m。本批拆除
 * 该前提——delegate 结束回合时有未结等待型后台作业 ⇒ **不终结**（hold），完成
 * 通知唤醒复检；等待集清空才终判；作者侧取消照旧立即终态。
 *
 * 判定机在测试环境缺席（DelegateJudgePort 未注册 face ⇒ None ⇒ fail-open
 * unjudged-completed），release 腿的判词行 = unjudged 注记形态——断言只钉
 * eventType/payload 关键词，不钉判词来源。
 */
class DelegateBgWaitHoldSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  /**
   * 断言面迁移（清理批 2026-11-04 · 断言面债一）：delegate 轨终态交付面自
   * P2 邮箱化（`6efd3f08c`）起 = **父会话持久邮箱**
   * （`PathUtil.dataRoot/sessions/<sid>/mailbox/pending/<seq>-<identity>.json`，kind=`delegate-terminal`），
   * 不再是 `parentRef ! ExternalEvent`。本 spec 的 A-1/A-2/A-4 走 `source="delegate"`
   * ⇒ 断言只能读邮箱；A-3 走 `source="subtask"`（旧 push 链保留）⇒ 仍读 actor 事件。
   *
   * 数据根指到 target/ 下的临时目录 —— 邮箱落盘不得写进真 `.stand`。
   */
  private val tempRoot: os.Path = os.pwd / "target" / "test-bg-wait-hold"
  private val originalRoot: os.Path = PathUtil.dataRoot

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
      prompt = "bg-wait test",
      description = "bg-wait test task",
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

  /**
   * 747cd36a 形态的在册等待任务（描述还原现场：sbt 全量回归）。
   * P2 death-watch 批：fixture 升级为**真进程**（真实 ShellSession + 后台
   * sleep）——活性探针（BgTaskLiveness）读内核进程事实，合成行（无会话无
   * 进程）会被判 owner-lost 收割，hold 语义无从钉住；真进程 ⇒ Alive ⇒ hold，
   * 与生产世界同构。
   */
  private def registerWaitingTask(sessionId: String, jobId: String): IO[Unit] =
    for
      shell <- ShellSession.forSession(sessionId)
      _ <- shell.executeBackground("sleep 3600", jobIdOverride = Some(jobId))
      _ <- BgTaskRegistry.register(jobId, sessionId, "Full test suite (sbt -batch test)", kind = "local")
    yield ()

  /** fixture 收尾：杀后台进程 + 毁会话 + 出册（幂等，进程绝不泄漏到测试外）。 */
  private def cleanupWaitingTask(sessionId: String, jobId: String): IO[Unit] =
    ShellSession
      .peekSession(sessionId)
      .flatMap(_.fold(IO.unit)(s => s.cancelBackgroundJob(jobId).attempt.void)) *>
      ShellSession.destroySession(sessionId).attempt.void *>
      BgTaskRegistry.unregisterSession(Some(sessionId)).attempt.void

  test("A-1 747cd36a 复现：结束回合时有未结后台作业 ⇒ hold（不报父、不停 child），清空后唤醒轮 Completed 放行") {
    val system = ActorSystem("bgwait-1")
    val tmp = os.temp.dir()
    val parentSid = "bgwait-parent-1"
    val childSid = "delegate-bgwait-1"
    val jobId = "job-bgwait-1"
    for
      resources <- mkResources(system, tmp)
      // 父会话必须是**真会话**：邮箱生产者按父会话在场判定投递
      // （MailboxProducer：会话已删 ⇒ parent-session-gone 跳过写箱）。
      parent <- resources.sessionStore.createSession("Nebula-root", agentName = Some("Nebula"))
      parentSid = parent.id
      _ <- resources.subAgentTaskStore.recordTask(mkTask(parentSid, childSid))
      _ <- seedDeclaredReport(childSid, parentSid)
      _ <- registerWaitingTask(childSid, jobId)
      parentEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- system.spawn(mkRecordingCmd(parentEvents), "parent-rec-1")
      childEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      childRef <- system.spawn(mkStopOnStopProbe(childEvents), "bgwait-child-1")
      supervisor <- system.spawn(
        BackoffSupervisor(
          childRef = childRef,
          childSpawnFn = (_, _) => IO.raiseError(new RuntimeException("respawn must not happen in this spec")),
          childName = childSid,
          parentRef = Some(parentRef),
          description = "bg-wait test task",
          agentName = "Worker",
          subagentId = childSid,
          parentSessionId = parentSid,
          resources = resources,
          initialPrompt = "run tests and report",
          source = "delegate",
          bgWaitCapMs = 60_000L // 远长于本测试窗口——cap 兜底不触发
        ),
        "supervisor-bgwait-1"
      )
      // 第一轮结束：终稿已写，但 sbt 回归还在后台跑 ⇒ 必须 hold
      _ <- supervisor ! AgentEvent.Completed(childSid, List(assistantMsg("hardening done; full suite still running in background")))
      // hold 窗口：父邮箱**无**终态件、child **不**被 Stop、任务库**不**终态化
      _ <- IO.sleep(600.millis)
      heldParentEvents <- parentEvents.get
      heldMails <- terminalMail(parentSid)
      heldChildCmds <- childEvents.get
      heldTasks <- resources.subAgentTaskStore.loadTasks(parentSid)
      heldWaiting <- BgTaskRegistry.waitingFor(childSid)
      // 唤醒复检：后台作业完成（registry 注销）+ 新一轮 Completed（消化通知后的终稿）
      _ <- BgTaskRegistry.unregister(jobId)
      _ <- supervisor ! AgentEvent.Completed(childSid, List(assistantMsg("full suite green — final report: all 55 tests passed")))
      _ <- waitUntil(10.seconds)(
        resources.subAgentTaskStore
          .loadTasks(parentSid)
          .map(_.exists(t => t.taskId == childSid && t.status == "completed"))
      )
      _ <- waitUntil(10.seconds)(terminalMail(parentSid).map(_.nonEmpty))
      _ <- waitUntil(10.seconds)(childEvents.get.map(_.exists(_.isInstanceOf[AgentCommand.Stop])))
      mails <- terminalMail(parentSid)
      childCmds <- childEvents.get
      tasks <- resources.subAgentTaskStore.loadTasks(parentSid)
      _ <- cleanupWaitingTask(childSid, jobId)
      _ <- system.stopAll
    yield
      // hold 面：零父通知（actor 面与邮箱面双零）、零 child Stop、任务仍 running、等待集仍在册
      assertEquals(heldParentEvents, Nil, s"held phase must NOT push any actor event to the parent, got: $heldParentEvents")
      assertEquals(heldMails, Nil, s"held phase must NOT deliver a terminal mail, got: ${heldMails.map(_.kind)}")
      assert(!heldChildCmds.exists(_.isInstanceOf[AgentCommand.Stop]), "held phase must NOT stop the child")
      assertEquals(heldTasks.find(_.taskId == childSid).map(_.status), Some("running"), "held phase must keep the task running")
      assertEquals(heldWaiting.map(_.jobId), List(jobId), "held phase keeps the wait set")
      // release 面：清空后的 Completed 放行 → 恰一条 delegate-terminal 邮件（父持久邮箱）+ child Stop
      assertEquals(mails.size, 1, s"exactly one terminal mail after release, got: ${mails.map(_.kind)}")
      assertEquals(mails.head.kind, "delegate-terminal", "the delegate terminal rides the parent's persistent mailbox")
      assertEquals(mails.head.identity, childSid, "mail identity = child session id (one entry per terminal)")
      assertEquals(mails.head.event.eventType, "completed")
      assert(
        mails.head.event.payload.contains("55 tests passed"),
        s"release payload must carry the final report: ${mails.head.event.payload}"
      )
      assert(childCmds.exists(_.isInstanceOf[AgentCommand.Stop]), "child must be stopped at release")
      assertEquals(tasks.find(_.taskId == childSid).map(_.status), Some("completed"))
    end for
  }

  test("A-2 回归：无未结后台作业 ⇒ Completed 立即终判（既有行为零漂移）") {
    val system = ActorSystem("bgwait-2")
    val tmp = os.temp.dir()
    val childSid = "delegate-bgwait-2"
    for
      resources <- mkResources(system, tmp)
      parent <- resources.sessionStore.createSession("Nebula-root", agentName = Some("Nebula"))
      parentSid = parent.id
      _ <- resources.subAgentTaskStore.recordTask(mkTask(parentSid, childSid))
      _ <- seedDeclaredReport(childSid, parentSid)
      parentEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- system.spawn(mkRecordingCmd(parentEvents), "parent-rec-2")
      childEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      childRef <- system.spawn(mkStopOnStopProbe(childEvents), "bgwait-child-2")
      supervisor <- system.spawn(
        BackoffSupervisor(
          childRef = childRef,
          childSpawnFn = (_, _) => IO.raiseError(new RuntimeException("respawn must not happen in this spec")),
          childName = childSid,
          parentRef = Some(parentRef),
          description = "bg-wait test task",
          agentName = "Worker",
          subagentId = childSid,
          parentSessionId = parentSid,
          resources = resources,
          initialPrompt = "quick task",
          source = "delegate"
        ),
        "supervisor-bgwait-2"
      )
      _ <- supervisor ! AgentEvent.Completed(childSid, List(assistantMsg("pure read done")))
      _ <- waitUntil(10.seconds)(terminalMail(parentSid).map(_.nonEmpty))
      mails <- terminalMail(parentSid)
      _ <- system.stopAll
    yield
      assertEquals(mails.size, 1, s"exactly one terminal mail, got: ${mails.map(_.kind)}")
      assertEquals(mails.head.kind, "delegate-terminal")
      assertEquals(
        mails.head.event.eventType,
        "completed",
        "no wait set ⇒ immediate completion (unchanged legacy behavior)"
      )
    end for
  }

  test("A-3 subtask 轨不 hold：有未结后台作业也照旧立即终态（轨道面零扩散）") {
    val system = ActorSystem("bgwait-3")
    val tmp = os.temp.dir()
    val parentSid = "bgwait-parent-3"
    val childSid = "subtask-bgwait-3"
    val jobId = "job-bgwait-3"
    for
      resources <- mkResources(system, tmp)
      _ <- resources.subAgentTaskStore.recordTask(mkTask(parentSid, childSid))
      _ <- registerWaitingTask(childSid, jobId)
      parentEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- system.spawn(mkRecordingCmd(parentEvents), "parent-rec-3")
      childEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      childRef <- system.spawn(mkStopOnStopProbe(childEvents), "bgwait-child-3")
      supervisor <- system.spawn(
        BackoffSupervisor(
          childRef = childRef,
          childSpawnFn = (_, _) => IO.raiseError(new RuntimeException("respawn must not happen in this spec")),
          childName = childSid,
          parentRef = Some(parentRef),
          description = "subtask bg test",
          agentName = "Worker",
          subagentId = childSid,
          parentSessionId = parentSid,
          resources = resources,
          initialPrompt = "subtask",
          source = "subtask"
        ),
        "supervisor-bgwait-3"
      )
      _ <- supervisor ! AgentEvent.Completed(childSid, List(assistantMsg("subtask text output")))
      _ <- waitUntil(10.seconds)(parentEvents.get.map(_.nonEmpty))
      events <- parentEvents.get
      _ <- cleanupWaitingTask(childSid, jobId)
      _ <- system.stopAll
    yield
      val ext = parentEventsNow(events)
      assertEquals(ext.size, 1, "subtask track terminates immediately even with a pending wait-set task")
      assertEquals(ext.head.eventType, "completed")
    end for
  }

  test("A-4 cap 兜底：等待期超 bgWaitCapMs 仍非空 ⇒ 带诚实注记强制终判（绝不悬挂成僵尸）") {
    val system = ActorSystem("bgwait-4")
    val tmp = os.temp.dir()
    val childSid = "delegate-bgwait-4"
    val jobId = "job-bgwait-4"
    for
      resources <- mkResources(system, tmp)
      parent <- resources.sessionStore.createSession("Nebula-root", agentName = Some("Nebula"))
      parentSid = parent.id
      _ <- resources.subAgentTaskStore.recordTask(mkTask(parentSid, childSid))
      _ <- seedDeclaredReport(childSid, parentSid)
      _ <- registerWaitingTask(childSid, jobId)
      parentEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- system.spawn(mkRecordingCmd(parentEvents), "parent-rec-4")
      childEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      childRef <- system.spawn(mkStopOnStopProbe(childEvents), "bgwait-child-4")
      supervisor <- system.spawn(
        BackoffSupervisor(
          childRef = childRef,
          childSpawnFn = (_, _) => IO.raiseError(new RuntimeException("respawn must not happen in this spec")),
          childName = childSid,
          parentRef = Some(parentRef),
          description = "bg-wait cap test",
          agentName = "Worker",
          subagentId = childSid,
          parentSessionId = parentSid,
          resources = resources,
          initialPrompt = "task with a never-finishing bg job",
          source = "delegate",
          bgWaitCapMs = 300.millis.toMillis // spec 缝：短 cap 驱动兜底腿
        ),
        "supervisor-bgwait-4"
      )
      _ <- supervisor ! AgentEvent.Completed(childSid, List(assistantMsg("turn done; bg job still running")))
      // cap 300ms 后强制终判：父**持久邮箱**收到 completed + [delegate-bg-wait] 诚实注记
      _ <- waitUntil(15.seconds)(
        terminalMail(parentSid).map(_.exists(e => e.event.eventType == "completed" && e.event.payload.contains("[delegate-bg-wait]")))
      )
      _ <- waitUntil(15.seconds)(
        resources.subAgentTaskStore.loadTasks(parentSid).map(_.exists(t => t.taskId == childSid && t.status == "completed"))
      )
      mails <- terminalMail(parentSid)
      childCmds <- childEvents.get
      tasks <- resources.subAgentTaskStore.loadTasks(parentSid)
      _ <- cleanupWaitingTask(childSid, jobId)
      _ <- system.stopAll
    yield
      assertEquals(mails.size, 1, s"exactly one forced terminal mail, got: ${mails.map(_.kind)}")
      assertEquals(mails.head.kind, "delegate-terminal")
      val payload = mails.head.event.payload
      assertEquals(mails.head.event.eventType, "completed")
      assert(payload.contains("[delegate-bg-wait]"), s"honest note required: $payload")
      assert(payload.contains(jobId), s"note must name the pending job: $payload")
      assert(payload.contains("Full test suite"), s"note must name the description: $payload")
      assert(payload.contains("turn done"), s"held turn's deliverable must ride the forced delivery: $payload")
      assert(childCmds.exists(_.isInstanceOf[AgentCommand.Stop]), "child stopped at forced terminal")
      assertEquals(tasks.find(_.taskId == childSid).map(_.status), Some("completed"), "forced terminal completes the task honestly")
    end for
  }

  private def parentEventsNow(events: List[AgentCommand]): List[AgentCommand.ExternalEvent] =
    events.collect { case e: AgentCommand.ExternalEvent => e }

  /** delegate 轨终态交付面 = 父会话**持久邮箱**（P2 邮箱化：不再 actor 直投）。 */
  private def terminalMail(parentSid: String): IO[List[nebflow.core.mail.SessionMailbox.Entry]] =
    nebflow.core.mail.SessionMailbox
      .scanPending(parentSid)
      .map(_.filter(_.kind == "delegate-terminal"))
      .handleErrorWith(_ => IO.pure(Nil))

  /**
   * 真 delegate 形态的注册表前置：记录在册 **且已申报**（`delegate_report(finish)`）。
   *
   * 为什么必须有：终态矩阵的「complete × **未申报**」腿（`Outcome.NudgeIdle`）按作者令
   * 零父投递（只武装提醒）——本 spec 观测的是等待屏障行为，夹具取矩阵的
   * `complete × 已申报` 腿（父会话照常收到终态投递）。
   */
  private def seedDeclaredReport(childSid: String, parentSid: String): IO[Unit] =
    IO {
      nebflow.core.delegate.DelegateRegistry.put(
        nebflow.core.delegate.DelegateRegistry.Record(
          id = childSid,
          executor = "nebflow",
          cwd = os.pwd.toString,
          project = None,
          taskId = Some("32"),
          parentSessionId = parentSid,
          title = s"bg-wait spec $childSid",
          createdAt = System.currentTimeMillis()
        )
      )
      nebflow.core.delegate.DelegateRegistry.updateReport(childSid, "finish", "wait barrier done", "") match
        case Left(e)  => throw new RuntimeException(s"seed declared report failed: $e")
        case Right(_) => ()
    }
end DelegateBgWaitHoldSpec
