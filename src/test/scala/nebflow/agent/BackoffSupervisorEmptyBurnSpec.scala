package nebflow.agent

import cats.effect.{IO, Ref}
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import fs2.Stream
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, Behavior, Behaviors}
import nebflow.actor.{AgentCommand, AgentEvent, AgentKind, AgentRecord}
import nebflow.core.FileChangeTracker
import nebflow.core.mail.SessionMailbox
import nebflow.core.compact.HistoryArchiver
import nebflow.core.delegate.{ChainStore, DelegateJudge}
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.*

import scala.concurrent.duration.*

/**
 * 四修①（2026-10-07 修复令「空轮≠完成」）判读——worker 会话 turn-ended-empty
 * 且全会话零工具调用（尤其 stopReason=max_tokens 空烧形态）不再等于完成：
 *
 *  - 判据纯函数：`sessionToolCalls`（全会话 tool_use 计数）＋
 *    `isEmptyBurnCompletion`（零工具 ∧（终文空 ∨ max_tokens 空烧 stub））；
 *  - 两级处置（作者荐形态）：有重启预算 → 既有 respawn 续推（"restarting"
 *    库态＋subagentRetry＋continue nudge 注入）；预算耗尽 → **状态矩阵批
 *    （2026-11-01 · 作者令「complete × 未申报 ⇒ 提醒，不终判」）**：delegate 轨
 *    挂进未申报提醒腿（Outcome.NudgeIdle——零终态报父、零判词行、任务行回
 *    running，DelegateStandbyCare.IdleNudge 承担 system-reminder 退避提醒，
 *    [[DelegateTerminalPrecheckSpec]] 钉 core 侧），subtask 轨 failed 终态
 *    （failureType=empty-burn）。
 *
 * 现场回归：鹦鹉骑自行车 delegate——thinking-only 空烧，supervisor 把
 * "(no text output)" 当完成报父，假 completed。本件钉死：空烧 Completed 一律
 * 不直接报完成。
 */
class BackoffSupervisorEmptyBurnSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val fakeLlm = new LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("fake llm not expected here"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.raiseError[IO](new RuntimeException("fake llm not expected here"))

  private val tempRoot: os.Path = os.pwd / "target" / "test-bsup-emptyburn"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    os.remove.all(tempRoot / "chains")
    DelegateJudge.DelegateJudgePort.clear()

  /** 记录命令的 child probe：respawn 链会向新 child 注入 UserInput。 */
  private def mkRecordingChild(record: Ref[IO, List[AgentCommand]]): Behavior[AgentCommand] =
    def loop: Behavior[AgentCommand] = Behaviors.receiveMessage(cmd => record.update(_ :+ cmd).as(loop))
    loop

  private def mkParentRecorder(record: Ref[IO, List[AgentCommand]]): Behavior[AgentCommand] =
    def loop: Behavior[AgentCommand] = Behaviors.receiveMessage(cmd => record.update(_ :+ cmd).as(loop))
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

  private def mkTask(parentSid: String, childSid: String, source: String): SubAgentTask =
    SubAgentTask(
      taskId = childSid,
      parentSessionId = parentSid,
      agentName = "Worker",
      prompt = "画一个「鹦鹉骑自行车」的循环动画",
      description = "parrot bicycle loop",
      status = "running",
      retryCount = 0,
      spawnedAt = System.currentTimeMillis(),
      completedAt = None,
      lastError = None,
      source = source
    )

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

  // ── 空烧消息面构造 ─────────────────────────────────────────────────────

  /** thinking-only 空轮（turn-ended-empty 形态）：终文空、零工具。 */
  private def thinkingOnlyMessages: List[Message] = List(
    Message(MessageRole.User, Left("draw it")),
    Message(MessageRole.Assistant, Right(List(ContentBlock.Thinking("…" * 500, None))))
  )

  /** max_tokens 空烧 stub 形态：终文 = 截断 stub、零工具（现场同款）。 */
  private def stubMessages: List[Message] = List(
    Message(MessageRole.User, Left("draw it")),
    Message(MessageRole.Assistant, Left(Defaults.MaxTokensStubText))
  )

  // ── 判据纯函数 ─────────────────────────────────────────────────────────

  test("sessionToolCalls counts tool_use blocks across the whole session") {
    val msgs = List(
      Message(MessageRole.User, Left("go")),
      Message(MessageRole.Assistant, Right(List(ContentBlock.ToolUse("t1", "Bash", io.circe.JsonObject.empty)))),
      Message(MessageRole.User, Right(List(ContentBlock.ToolResult("t1", "ok")))),
      Message(
        MessageRole.Assistant,
        Right(List(ContentBlock.Text("partial"), ContentBlock.ToolUse("t2", "Read", io.circe.JsonObject.empty)))
      )
    )
    assertEquals(BackoffSupervisor.sessionToolCalls(msgs), 2)
    assertEquals(BackoffSupervisor.sessionToolCalls(thinkingOnlyMessages), 0)
    assertEquals(BackoffSupervisor.sessionToolCalls(Nil), 0)
  }

  test("isEmptyBurnCompletion: zero tools + (no text | stub) = true; real text or any tool = false") {
    assert(BackoffSupervisor.isEmptyBurnCompletion(Nil), "silent empty completion is an empty burn")
    assert(
      BackoffSupervisor.isEmptyBurnCompletion(thinkingOnlyMessages),
      "thinking-only turn-ended-empty is an empty burn"
    )
    assert(
      BackoffSupervisor.isEmptyBurnCompletion(stubMessages),
      "the max_tokens stub is NOT real output (the incident form)"
    )
    assert(
      !BackoffSupervisor.isEmptyBurnCompletion(List(Message(MessageRole.Assistant, Left("did the work")))),
      "real text output is the judge's input, not an empty burn"
    )
    assert(
      !BackoffSupervisor.isEmptyBurnCompletion(
        thinkingOnlyMessages :+ Message(
          MessageRole.Assistant,
          Right(List(ContentBlock.ToolUse("t9", "Bash", io.circe.JsonObject.empty)))
        )
      ),
      "any tool call disqualifies the empty-burn verdict"
    )
  }

  // ── 行为面：delegate 轨两级处置 ─────────────────────────────────────────

  test(
    "delegate track: empty Completed restarts (nudge injected, restarting status); budget exhausted ⇒ matrix complete×idle (NudgeIdle): reminder armed, NO terminal, NO parent delivery, task row honest running — never a fake completion"
  ) {
    val system = ActorSystem(s"bsup-eb-1-${scala.util.Random.nextInt(100000)}")
    val tmp = os.temp.dir(prefix = "nb-bsup-eb-")
    val parentSid = "eb-parent-1"
    val childSid = "delegate-eb-1"
    val stubBrief = "画一个「鹦鹉骑自行车」的循环动画"
    // 状态矩阵批：待命提醒关（确定性——caretaker 的 10s 首催与本轮断言无关；
    // IdleNudge 注入形态由 DelegateStandbyCareSpec 钉）。
    val prevNudge = sys.props.put("nebflow.delegate.standby.nudgeEnabled", "false")
    for
      resources <- mkResources(system, tmp)
      // P2 邮箱化：父会话登记在场（MailboxProducer.deliver 父在场守卫）。
      _ <- resources.sessionStore.getOrCreateSession(parentSid, "empty-burn test parent")
      _ <- resources.subAgentTaskStore.recordTask(mkTask(parentSid, childSid, "delegate"))
      // 预存会话消息 ⇒ respawn 走 recovered 分支（nudge 注入可见）
      _ <- resources.sessionStore.saveMessagesForSession(childSid, thinkingOnlyMessages)
      parentEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- system.spawn(mkParentRecorder(parentEvents), "eb-parent-rec-1")
      childCmds <- Ref.of[IO, List[AgentCommand]](Nil)
      spawned <- Ref.of[IO, Int](0)
      firstChild <- system.spawn(mkRecordingChild(childCmds), "eb-child-0")
      _ <- resources.agentRegistry.update(
        _ + (childSid -> AgentRecord(childSid, firstChild, AgentKind.Delegate, parentSid, Some(parentRef)))
      )
      supervisor <- system.spawn(
        BackoffSupervisor(
          childRef = firstChild,
          childSpawnFn = (_, _) =>
            spawned.update(_ + 1) *> system
              .spawn(mkRecordingChild(childCmds), s"eb-child-${scala.util.Random.nextInt(100000)}"),
          childName = childSid,
          parentRef = Some(parentRef),
          description = "parrot bicycle loop",
          agentName = "Worker",
          subagentId = childSid,
          parentSessionId = parentSid,
          resources = resources,
          initialPrompt = stubBrief,
          source = "delegate",
          minBackoff = 50.millis,
          maxBackoff = 100.millis
        ),
        "eb-supervisor-1"
      )
      // 空烧 ×3：第 1/2 次吃掉重启预算，第 3 次预算耗尽 → 矩阵未申报腿（提醒不终判）
      _ <- supervisor ! AgentEvent.Completed(childSid, thinkingOnlyMessages)
      _ <- waitUntil(10.seconds)(spawned.get.map(_ == 1))
      _ <- supervisor ! AgentEvent.Completed(childSid, stubMessages)
      _ <- waitUntil(10.seconds)(spawned.get.map(_ == 2))
      _ <- supervisor ! AgentEvent.Completed(childSid, stubMessages)
      _ <- IO.sleep(500.millis) // 观察停靠沉降（无终态可等——纯断言 reminder 形态）
      mailed <- SessionMailbox.scanPending(parentSid)
      refEvents <- parentEvents.get
      cmds <- childCmds.get
      tasks <- resources.subAgentTaskStore.loadTasks(parentSid)
      chainLines = ChainStore.listSync().flatMap(_.entries).filter(_.kind == "terminal-verdict")
      _ <- system.stopAll
      _ <- IO {
        prevNudge.fold(sys.props.remove("nebflow.delegate.standby.nudgeEnabled"))(v =>
          sys.props.put("nebflow.delegate.standby.nudgeEnabled", v)
        )
        ()
      }
    yield
      // 两次续推：新 child 各收到一次「空烧 nudge」（非旧 completed 报文）
      val nudges = cmds
        .collect { case u: AgentCommand.UserInput => u.text }
        .filter(_.contains("NO output and NO tool calls"))
      assertEquals(nudges.size, 2, s"each empty-burn restart must inject the nudge, got: $nudges")
      // 状态矩阵锚演进（作者令 2026-11-01「不给 completed 假终态」）：预算耗尽 ⇒
      // 未申报腿——零终态投递（原预检 completed+note 报父形态退役），提醒面接管。
      val ext = mailed.collect { case e if e.event.eventType == "completed" => e }
      assertEquals(ext.size, 0, s"an observed empty burn must NOT reach the parent as any terminal, got: ${mailed.map(e => e.event.eventType)}")
      assertEquals(chainLines.size, 0, s"no terminal-verdict line may be written, got: $chainLines")
      // 红线（双发=事故）：ref 消息流零 ExternalEvent。
      assertEquals(
        refEvents.collect { case e: AgentCommand.ExternalEvent => e }.size,
        0,
        s"delegate terminal MUST NOT ride the legacy push chain, got: $refEvents"
      )
      // 停靠面：任务行诚实回 running（respawn 的 restarting 不滞留——行诚实：
      // 活着、待提醒、未完工）。
      val task = tasks.find(_.taskId == childSid).get
      assertEquals(task.retryCount, 2, "two empty-burn restarts must be counted")
      assertEquals(task.status, "running", "the reminded delegate is parked awaiting its declaration, NOT completed")
    end for
  }

  test("subtask track: empty burn with exhausted budget ⇒ honest failed terminal (failureType=empty-burn)") {
    val system = ActorSystem(s"bsup-eb-2-${scala.util.Random.nextInt(100000)}")
    val tmp = os.temp.dir(prefix = "nb-bsup-eb2-")
    val parentSid = "eb-parent-2"
    val childSid = "subtask-eb-2"
    for
      resources <- mkResources(system, tmp)
      _ <- resources.subAgentTaskStore.recordTask(mkTask(parentSid, childSid, "subtask"))
      parentEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- system.spawn(mkParentRecorder(parentEvents), "eb-parent-rec-2")
      childCmds <- Ref.of[IO, List[AgentCommand]](Nil)
      spawned <- Ref.of[IO, Int](0)
      firstChild <- system.spawn(mkRecordingChild(childCmds), "eb2-child-0")
      supervisor <- system.spawn(
        BackoffSupervisor(
          childRef = firstChild,
          childSpawnFn = (_, _) =>
            spawned.update(_ + 1) *> system
              .spawn(mkRecordingChild(childCmds), s"eb2-child-${scala.util.Random.nextInt(100000)}"),
          childName = childSid,
          parentRef = Some(parentRef),
          description = "subtask empty burn",
          agentName = "Worker",
          subagentId = childSid,
          parentSessionId = parentSid,
          resources = resources,
          initialPrompt = "do it",
          source = "subtask",
          minBackoff = 50.millis,
          maxBackoff = 100.millis
        ),
        "eb-supervisor-2"
      )
      _ <- supervisor ! AgentEvent.Completed(childSid, thinkingOnlyMessages)
      _ <- waitUntil(10.seconds)(spawned.get.map(_ == 1))
      _ <- supervisor ! AgentEvent.Completed(childSid, thinkingOnlyMessages)
      _ <- waitUntil(10.seconds)(spawned.get.map(_ == 2))
      _ <- supervisor ! AgentEvent.Completed(childSid, thinkingOnlyMessages)
      _ <- waitUntil(10.seconds)(
        parentEvents.get.map(_.exists {
          case e: AgentCommand.ExternalEvent => e.eventType == "failed"
          case _ => false
        })
      )
      _ <- IO.sleep(300.millis)
      events <- parentEvents.get
      tasks <- resources.subAgentTaskStore.loadTasks(parentSid)
      _ <- system.stopAll
    yield
      val ext = events.collect { case e: AgentCommand.ExternalEvent => e }
      assertEquals(ext.size, 1, s"exactly one terminal notification, got: $events")
      assertEquals(ext.head.eventType, "failed", "an unresolvable empty burn is NOT reported as completed")
      assertEquals(ext.head.metadata("failureType").flatMap(_.asString), Some("empty-burn"))
      assert(ext.head.payload.contains("empty burn"), s"the payload names the burn: ${ext.head.payload}")
      val task = tasks.find(_.taskId == childSid).get
      assertEquals(task.status, "failed")
      assertEquals(task.retryCount, 2)
    end for
  }

end BackoffSupervisorEmptyBurnSpec
