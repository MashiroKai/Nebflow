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
import nebflow.core.delegate.{DelegateDeliveryReceipts, DelegateJudge, DelegateRegistry, MergeQueue}
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, Message, MessageRole, PathUtil, StreamChunk, SubAgentTask, ThinkingConfig}

import scala.concurrent.duration.*

/**
 * 合并回执通知链修复批 2026-10-31（作者 08:38 裁定：「delegate 不是应该合并完成了
 * 才通知你吗——怎么还没合并就通知你了，然后合并完了也没通知你」）回归：
 *
 *  - R1 全生命周期模拟（真仓真并）：工作轮 judge ready-to-merge ⇒ 停靠（父**零
 *    通知**——「合并前的中间态不吵」）⇒ 合并轮真落地（机械验收通过）⇒ dispatcher
 *    **恰收到一次** completed 终态回执，且该回执携带判词行 + 保真工作结果全文
 *    （单条消息一次到齐——「合并落地 ⇒ 回执必达」）；
 *  - R2 合并轮夭折形态：merge-window-timeout cancel ⇒ 恰一次 cancelled 终态 +
 *    随投保真工作结果（工作成果绝不随夭折丢失——停靠不投父后本段是唯一回传面）。
 *
 * 断点锚（修复前红态）：10-29「停靠即投」让停靠件与合并回执共用
 * correlationId=delegateId，父 intake 的 DeliveredReceiptLedger 恰一次判重把合并
 * 回执滤除（external-event-duplicate-suppressed）——本 spec 的 R1 在修复前红在
 * 「停靠后父已收到 1 件（work result），合并后仍是那 1 件、payload 无判词行」。
 */
class MergeReceiptNotifyChainSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-merge-receipt-notify-chain"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  nebflow.ForceRemove.removeAll(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    MergeQueue.reset()
    nebflow.ForceRemove.removeAll(tempRoot / "delegates")
    nebflow.ForceRemove.removeAll(tempRoot / "merge-channel")

  override def afterEach(context: munit.AfterEach): Unit =
    MergeQueue.reset()
    DelegateJudge.DelegateJudgePort.clear()

  private val fakeLlm = new LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("fake llm not expected here"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.raiseError[IO](new RuntimeException("fake llm not expected here"))

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
      sessionStore = SessionStore(tmp / "sessions", tmp / "tasks"),
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
      prompt = "merge receipt chain test",
      description = "merge receipt chain test task",
      status = "running",
      retryCount = 0,
      spawnedAt = System.currentTimeMillis(),
      completedAt = None,
      lastError = None,
      source = "delegate"
    )

  private def assistantMsg(text: String): Message =
    Message(MessageRole.Assistant, Left(text))

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

  private def parentEventsNow(events: List[AgentCommand]): List[AgentCommand.ExternalEvent] =
    events.collect { case e: AgentCommand.ExternalEvent => e }

  /**
   * 真仓夹具：main 一个基提交 + `delegate/<sid>` 一个领先提交（模拟工作轮交付），
   * checkout 回 main（合并轮由夹具代 delegate 执行 `git merge --no-ff`）。
   */
  private def mkRepo(tmp: os.Path, sid: String): IO[os.Path] = IO.blocking {
    val repo = tmp / s"repo-$sid"
    os.makeDir.all(repo)
    def git(args: String*): Unit =
      val res = os.proc("git", "-C", repo.toString, args).call(check = false, mergeErrIntoOut = true)
      assertEquals(res.exitCode, 0, s"git ${args.mkString(" ")} failed: ${res.out.trim()}")
    git("init", "-b", "main")
    git("config", "user.email", "spec@example.com")
    git("config", "user.name", "merge-receipt-spec")
    os.write(repo / "base.txt", "base")
    git("add", ".")
    git("commit", "-m", "base")
    git("checkout", "-b", s"delegate/$sid")
    os.write(repo / "work.txt", "the deliverable")
    git("add", ".")
    git("commit", "-m", "work")
    git("checkout", "main")
    repo
  }

  /** 脚本判定机：工作轮判 ready-to-merge；合并轮（mergeRound 旗）判 completed。 */
  private def installScriptedJudge(): Unit =
    DelegateJudge.DelegateJudgePort.install(
      new DelegateJudge.DelegateJudgePort.Face:
        def decideWorktree(task: String): IO[Option[Boolean]] = IO.pure(None)
        override def judgeTerminal(input: DelegateJudge.TerminalInput): IO[Option[DelegateJudge.Verdict]] =
          IO.pure(
            Some(
              if input.mergeRound then DelegateJudge.Verdict.Completed
              else DelegateJudge.Verdict.ReadyToMerge
            )
          )
    )

  test("R1 全生命周期：工作轮停靠父零通知；合并落地 ⇒ dispatcher 恰收到一次 completed 回执（携带判词行 + 工作结果全文）") {
    val system = ActorSystem("merge-receipt-r1")
    val tmp = os.temp.dir()
    val parentSid = "mr-parent-1"
    val childSid = "delegate-mr-1"
    val workText = "WORK RESULT FULL TEXT: the feature is implemented and verified."
    val mergeText = "merge round finished"
    for
      repo <- mkRepo(tmp, childSid)
      resources <- mkResources(system, tmp)
      _ <- resources.subAgentTaskStore.recordTask(mkTask(parentSid, childSid))
      _ <- IO {
        DelegateRegistry.put(
          DelegateRegistry.Record(
            id = childSid,
            executor = "nebflow",
            cwd = repo.toString,
            project = None,
            taskId = None,
            parentSessionId = parentSid,
            title = "merge receipt chain test task",
            createdAt = System.currentTimeMillis(),
            branch = Some(s"delegate/$childSid"),
            worktree = None,
            baseBranch = Some("main"),
            projectWorkspace = Some(repo.toString)
          )
        )
      }
      _ <- IO(installScriptedJudge())
      parentEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- system.spawn(mkRecordingCmd(parentEvents), "mr-parent-rec-1")
      childEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      childRef <- system.spawn(mkStopOnStopProbe(childEvents), "mr-child-1")
      _ <- resources.agentRegistry.update(
        _ + (childSid -> AgentRecord(childSid, childRef, AgentKind.Delegate, parentSid, Some(parentRef)))
      )
      supervisor <- system.spawn(
        BackoffSupervisor(
          childRef = childRef,
          childSpawnFn = (_, _) => IO.raiseError(new RuntimeException("respawn must not happen in this spec")),
          childName = childSid,
          parentRef = Some(parentRef),
          description = "merge receipt chain test task",
          agentName = "Worker",
          subagentId = childSid,
          parentSessionId = parentSid,
          resources = resources,
          initialPrompt = "do the work and merge",
          source = "delegate"
        ),
        "mr-supervisor-1"
      )
      // ── 工作轮终态：judge ready-to-merge ⇒ 停靠（应零父通知）──
      _ <- supervisor ! AgentEvent.Completed(childSid, List(assistantMsg(workText)))
      _ <- waitUntil(10.seconds)(
        resources.subAgentTaskStore
          .loadTasks(parentSid)
          .map(_.exists(t => t.taskId == childSid && t.status == MergeQueueMemberReady))
      )
      _ <- waitUntil(10.seconds)(IO(MergeQueue.isMergeRound(childSid)))
      parkedEvents <- parentEvents.get
      parkedWorkReport <- IO(DelegateRegistry.find(childSid).flatMap(_.workReport))
      // ── 合并轮：夹具代 delegate 执行真合并（机械验收的双亲+祖先判据）──
      _ <- IO.blocking {
        val res = os
          .proc("git", "-C", repo.toString, "merge", "--no-ff", s"delegate/$childSid", "-m", s"merge $childSid")
          .call(check = false, mergeErrIntoOut = true)
        assertEquals(res.exitCode, 0, s"the merge-round fixture merge must succeed: ${res.out.trim()}")
      }
      _ <- supervisor ! AgentEvent.Completed(childSid, List(assistantMsg(mergeText)))
      _ <- waitUntil(15.seconds)(
        resources.subAgentTaskStore
          .loadTasks(parentSid)
          .map(_.exists(t => t.taskId == childSid && t.status == "completed"))
      )
      _ <- waitUntil(15.seconds)(parentEvents.get.map(_.nonEmpty))
      _ <- waitUntil(15.seconds)(IO(MergeQueue.find(childSid).exists(m => !m.open)))
      events <- parentEvents.get
      tasks <- resources.subAgentTaskStore.loadTasks(parentSid)
      receipt <- IO(DelegateDeliveryReceipts.find(childSid))
      _ <- system.stopAll
    yield
      // 停靠面：父零通知（「合并前的中间态不吵」——修复前红态：此时父已收到 work result）
      assertEquals(parentEventsNow(parkedEvents), Nil, s"park phase must NOT notify the parent, got: $parkedEvents")
      assertEquals(parkedWorkReport, Some(workText), "the work result must be preserved into the registry slot at park time")
      // 合并落地面：恰一次 completed 终态回执 = 判词行 + 工作结果全文一次到齐
      val ext = parentEventsNow(events)
      assertEquals(ext.size, 1, s"exactly ONE merge receipt must reach the dispatcher, got: $events")
      assertEquals(ext.head.eventType, "completed")
      assertEquals(ext.head.correlationId, Some(childSid))
      assert(ext.head.payload.contains("[delegate-judge] verdict: completed"), s"verdict line missing: ${ext.head.payload}")
      assert(ext.head.payload.contains(mergeText), s"merge-round final text missing: ${ext.head.payload}")
      assert(ext.head.payload.contains(workText), s"the preserved work result must ride the single receipt: ${ext.head.payload}")
      // 交付回执落盘（补投机器面）
      assert(receipt.isDefined, "the terminal delivery receipt must be recorded")
      assertEquals(receipt.map(_.eventType), Some("completed"))
      // 队列关账 + 任务行终态
      assertEquals(tasks.find(_.taskId == childSid).map(_.status), Some("completed"))
    end for
  }

  test("R2 合并轮夭折：merge-window-timeout cancel ⇒ 恰一次 cancelled 终态 + 随投保真工作结果（工作成果不随夭折丢失）") {
    val system = ActorSystem("merge-receipt-r2")
    val tmp = os.temp.dir()
    val parentSid = "mr-parent-2"
    val childSid = "delegate-mr-2"
    val workText = "WORK RESULT FULL TEXT: implementation done, awaiting merge."
    for
      repo <- mkRepo(tmp, childSid)
      resources <- mkResources(system, tmp)
      _ <- resources.subAgentTaskStore.recordTask(mkTask(parentSid, childSid))
      _ <- IO {
        DelegateRegistry.put(
          DelegateRegistry.Record(
            id = childSid,
            executor = "nebflow",
            cwd = repo.toString,
            project = None,
            taskId = None,
            parentSessionId = parentSid,
            title = "merge receipt chain test task",
            createdAt = System.currentTimeMillis(),
            branch = Some(s"delegate/$childSid"),
            worktree = None,
            baseBranch = Some("main"),
            projectWorkspace = Some(repo.toString)
          )
        )
      }
      _ <- IO(installScriptedJudge())
      parentEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- system.spawn(mkRecordingCmd(parentEvents), "mr-parent-rec-2")
      childEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      childRef <- system.spawn(mkStopOnStopProbe(childEvents), "mr-child-2")
      _ <- resources.agentRegistry.update(
        _ + (childSid -> AgentRecord(childSid, childRef, AgentKind.Delegate, parentSid, Some(parentRef)))
      )
      supervisor <- system.spawn(
        BackoffSupervisor(
          childRef = childRef,
          childSpawnFn = (_, _) => IO.raiseError(new RuntimeException("respawn must not happen in this spec")),
          childName = childSid,
          parentRef = Some(parentRef),
          description = "merge receipt chain test task",
          agentName = "Worker",
          subagentId = childSid,
          parentSessionId = parentSid,
          resources = resources,
          initialPrompt = "do the work and merge",
          source = "delegate"
        ),
        "mr-supervisor-2"
      )
      _ <- supervisor ! AgentEvent.Completed(childSid, List(assistantMsg(workText)))
      _ <- waitUntil(10.seconds)(
        resources.subAgentTaskStore
          .loadTasks(parentSid)
          .map(_.exists(t => t.taskId == childSid && t.status == MergeQueueMemberReady))
      )
      _ <- waitUntil(10.seconds)(IO(MergeQueue.isMergeRound(childSid)))
      parkedEvents <- parentEvents.get
      // 合并轮夭折：合并窗超时取消（会话停靠期的既有取消链）
      _ <- supervisor ! AgentEvent.Cancelled(childSid, "merge-window-timeout")
      _ <- waitUntil(15.seconds)(
        resources.subAgentTaskStore
          .loadTasks(parentSid)
          .map(_.exists(t => t.taskId == childSid && t.status == "cancelled"))
      )
      _ <- waitUntil(15.seconds)(parentEvents.get.map(_.nonEmpty))
      events <- parentEvents.get
      _ <- system.stopAll
    yield
      assertEquals(parentEventsNow(parkedEvents), Nil, s"park phase must NOT notify the parent, got: $parkedEvents")
      val ext = parentEventsNow(events)
      assertEquals(ext.size, 1, s"exactly ONE terminal notification for the aborted merge round, got: $events")
      assertEquals(ext.head.eventType, "cancelled")
      assert(ext.head.payload.contains("merge-window-timeout"), s"cancel reason must ride: ${ext.head.payload}")
      assert(ext.head.payload.contains(workText), s"the preserved work result must ride the aborted-round terminal: ${ext.head.payload}")
    end for
  }

  /** DelegateTerminal.ReadyToMergeStatus 的同串钉（跨包公共值）。 */
  private def MergeQueueMemberReady: String = "ready-to-merge"

end MergeReceiptNotifyChainSpec
