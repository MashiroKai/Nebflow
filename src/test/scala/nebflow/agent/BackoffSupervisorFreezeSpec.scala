package nebflow.agent

import cats.effect.{IO, Ref}
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import fs2.Stream
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, Behavior, Behaviors}
import nebflow.actor.{AgentCommand, AgentEvent, AgentError, AgentErrorType, AgentKind, AgentRecord}
import nebflow.core.FileChangeTracker
import nebflow.core.compact.HistoryArchiver
import nebflow.core.delegate.{DelegateFreeze, DelegateJudge, DelegateRegistry}
import nebflow.core.mail.SessionMailbox
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.*

import scala.concurrent.duration.*

/**
 * 错误冻结批（2026-11-01 · 作者令逐字「所有因错误状态的 delegate 应该上报之后 要能
 * 保留状态 冻结态 方便被唤醒 接着做」）——**监督者冻结腿**判红锚。
 *
 * 钉死的硬断言（任务书验收 ①「错误→冻结（不是 failed）」+「多种冻结原因各一例」）：
 *
 *  - `AgentEvent.Failed` 到达 delegate 轨监督者 ⇒ **不**落 failed 终态：
 *      · registry 记录 status=Frozen + frozenReason=`delegate-frozen:<reason>`；
 *      · 台账行 status=`frozen`（boot 收殓视野之外的状态值）；
 *      · 父会话经**持久邮箱**收到 `eventType="frozen"` 回执（**不是** failed），
 *        正文带唤醒地址 `delegate:<id>` 且逐字声明「这不是失败判词」；
 *      · 零 failed/completed/cancelled 事件（双面：ref 流 + 邮箱）；
 *      · 任务行**不**翻 failed、registry 记录**不**移除（唤醒地址锚 + 合并队列位）。
 *  - 配额类 `AgentEvent.Cancelled` 同走冻结腿，原因码 `cancelled-by-quota`；
 *    显式取消（非配额）逐字保持既有 cancelled 终态（取消意图必须落地）。
 *  - 非 delegate 轨（subtask）逐字保持既有 failed 终态（零行为变化）。
 */
class BackoffSupervisorFreezeSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 90.seconds

  private val fakeLlm = new LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("fake llm not expected here"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.raiseError[IO](new RuntimeException("fake llm not expected here"))

  private val originalRoot = PathUtil.dataRoot
  private var base: os.Path = os.pwd / "target" / "test-bsup-freeze"

  override def beforeAll(): Unit =
    base = os.temp.dir(prefix = "nb-bsup-freeze-")
    PathUtil.setDataRoot(base / "data")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    try os.remove.all(base)
    catch case _: Throwable => ()

  override def beforeEach(context: munit.BeforeEach): Unit =
    DelegateJudge.DelegateJudgePort.clear()
    sys.props.remove("nebflow.delegate.freeze.enabled")

  override def afterEach(context: munit.AfterEach): Unit =
    sys.props.remove("nebflow.delegate.freeze.enabled")

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
      prompt = "wire up the image pipeline",
      description = "wire up the image pipeline",
      status = "running",
      retryCount = 0,
      spawnedAt = System.currentTimeMillis(),
      completedAt = None,
      lastError = None,
      source = source
    )

  private def mkRecord(id: String, parentSid: String): DelegateRegistry.Record =
    DelegateRegistry.Record(
      id = id,
      executor = "nebflow",
      cwd = os.pwd.toString,
      project = None,
      taskId = None,
      parentSessionId = parentSid,
      title = s"freeze spec record $id",
      createdAt = System.currentTimeMillis()
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

  private def err(kind: AgentErrorType, msg: String, childSid: String): AgentError =
    AgentError(childSid, "Worker", 1, kind, msg)

  private def mailboxEvents(parentSid: String): IO[List[AgentCommand.ExternalEvent]] =
    for
      pending <- SessionMailbox.scanPending(parentSid)
      taken <- SessionMailbox.scanTaken(parentSid)
    yield (pending ++ taken).map(_.event)

  // ── 主腿：Failed ⇒ 冻结（不是 failed）───────────────────────────────────

  test(
    "LLM failure on the delegate track FREEZES instead of failing: registry Frozen + reason wire, task row frozen, parent gets a frozen receipt (never failed), record kept"
  ) {
    val system = ActorSystem(s"bsup-fz-1-${scala.util.Random.nextInt(100000)}")
    val tmp = os.temp.dir(prefix = "nb-bsup-fz1-")
    val parentSid = "fz-parent-1"
    val childSid = "delegate-fz-1"
    for
      resources <- mkResources(system, tmp)
      _ <- resources.sessionStore.getOrCreateSession(parentSid, "freeze spec parent", agentName = Some("Nebula"))
      _ <- resources.subAgentTaskStore.recordTask(mkTask(parentSid, childSid, "delegate"))
      _ <- IO(DelegateRegistry.put(mkRecord(childSid, parentSid)))
      parentEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- system.spawn(mkParentRecorder(parentEvents), "fz-parent-rec-1")
      childCmds <- Ref.of[IO, List[AgentCommand]](Nil)
      firstChild <- system.spawn(mkRecordingChild(childCmds), "fz-child-0")
      _ <- resources.agentRegistry.update(
        _ + (childSid -> AgentRecord(childSid, firstChild, AgentKind.Delegate, parentSid, Some(parentRef)))
      )
      supervisor <- system.spawn(
        BackoffSupervisor(
          childRef = firstChild,
          childSpawnFn =
            (_, _) => system.spawn(mkRecordingChild(childCmds), s"fz-child-${scala.util.Random.nextInt(100000)}"),
          childName = childSid,
          parentRef = Some(parentRef),
          description = "wire up the image pipeline",
          agentName = "Worker",
          subagentId = childSid,
          parentSessionId = parentSid,
          resources = resources,
          initialPrompt = "wire it up",
          source = "delegate",
          minBackoff = 50.millis,
          maxBackoff = 100.millis
        ),
        "fz-supervisor-1"
      )
      _ <- supervisor ! AgentEvent.Failed(
        childSid,
        err(
          AgentErrorType.LlmFailed,
          "provider anthropic/claude-x: 529 overloaded - all candidates exhausted",
          childSid
        )
      )
      // 冻结落盘是异步链（park → registry → mailbox → WS）——等台账行落 frozen。
      _ <- waitUntil(20.seconds)(
        resources.subAgentTaskStore
          .loadTasks(parentSid)
          .map(_.find(_.taskId == childSid).exists(_.status == DelegateFreeze.TaskStatus))
      )
      // 冻结通知策略批（2026-11-05 作者令）：provider 故障族**不再投父会话回执**——
      // 这里不再等回执，改为给投递腿一个沉降窗口，随后断言「一条都没有」。
      _ <- IO.sleep(400.millis)
      _ <- IO.sleep(300.millis) // 沉降：确认没有第二件终态悄悄落地
      events <- mailboxEvents(parentSid)
      refEvents <- parentEvents.get
      tasks <- resources.subAgentTaskStore.loadTasks(parentSid)
      record <- IO(DelegateRegistry.find(childSid).get)
      reg <- resources.agentRegistry.get
      cmds <- childCmds.get
      _ <- system.stopAll
    yield
      // ① 判词诚实：零 failed / completed / cancelled（ref 流 + 邮箱两面）。
      assertEquals(
        events.filter(e => Set("failed", "completed", "cancelled").contains(e.eventType)).map(_.eventType),
        Nil,
        s"a frozen delegate must never produce a failure terminal, got: ${events.map(_.eventType)}"
      )
      assertEquals(
        refEvents
          .collect { case e: AgentCommand.ExternalEvent => e }
          .map(_.eventType)
          .filter(t => Set("failed", "completed", "cancelled").contains(t)),
        Nil,
        s"the legacy push chain must not carry a terminal either, got: $refEvents"
      )
      // ② 上报：provider 故障（llm-failure）**零向上通知**（冻结通知策略批 2026-11-05：
      // 基础设施故障不是任务失败，上级多半也在同一波里挨饿）。回执正文仍由
      // `receiptText` 单点提供（升级/回滚路径与审计面用它），故按纯函数断言其判词诚实。
      val receipts = events.filter(e => e.eventType == "frozen" && e.source == "delegate")
      assertEquals(receipts, Nil, s"a provider-fault freeze must NOT mail the parent, got: ${events.map(_.eventType)}")
      val honestText = DelegateFreeze.receiptText(
        childSid,
        "wire up the image pipeline",
        DelegateFreeze.Reason.LlmFailure,
        "529 overloaded",
        os.pwd.toString
      )
      assert(honestText.contains("NOT a failure verdict"), s"honest verdict text, got: ${honestText.take(400)}")
      assert(honestText.contains(s"delegate:$childSid"), "the wake address must be printed")
      // ③ 状态保留：registry 冻结槽 + 台账行 frozen（不进 boot 收殓视野）+ 记录在册。
      assert(record.frozenSince.isDefined, "frozenSince persisted (survives restart)")
      assertEquals(record.frozenReason, Some(DelegateFreeze.wireValue(DelegateFreeze.Reason.LlmFailure)))
      assert(
        record.frozenDetail.exists(_.contains("529")),
        s"the last error context is persisted, got: ${record.frozenDetail}"
      )
      // 触发源诊断（engine-fault 冻结报错补详批 · 第二波）：触发源 + 原始错误类别进
      // 详情行与结构化槽——记录答得出「什么触发了这次冻结」。
      assert(
        record.frozenDetail.exists(_.startsWith("trigger=failed-event; errorType=LlmFailed; ")),
        s"the diagnostic prefix leads the persisted detail, got: ${record.frozenDetail}"
      )
      assertEquals(record.frozenTrigger, Some("failed-event"), "the trigger source lands in the structured slot")
      val row = tasks.find(_.taskId == childSid).get
      assertEquals(row.status, DelegateFreeze.TaskStatus)
      assert(row.lastError.exists(_.contains("llm-failure")), s"the row carries the reason, got: ${row.lastError}")
      // ④ 记录仍在（唤醒地址锚 + 合并队列位），且 registry 状态 = Frozen。
      assert(reg.contains(childSid), "the registry record must survive a freeze (wake address anchor)")
      assertEquals(reg.get(childSid).map(_.status), Some(nebflow.actor.AgentStatus.Frozen))
      assertEquals(
        reg.get(childSid).flatMap(_.frozenReason),
        Some(DelegateFreeze.wireValue(DelegateFreeze.Reason.LlmFailure))
      )
      // ⑤ 没有无限重试：冻结即停——child 未收到新的续跑注入。
      assert(
        !cmds.exists {
          case _: AgentCommand.UserInput => true
          case _ => false
        },
        "freezing must not inject a retry nudge (the wake is the retry, not the freeze)"
      )
    end for
  }

  test(
    "quota-like Cancelled freezes with cancelled-by-quota; an explicit cancel keeps the cancelled terminal verbatim"
  ) {
    val system = ActorSystem(s"bsup-fz-2-${scala.util.Random.nextInt(100000)}")
    val tmp = os.temp.dir(prefix = "nb-bsup-fz2-")
    val parentSid = "fz-parent-2"
    val childQuota = "delegate-fz-quota"
    val childCancel = "delegate-fz-cancel"
    for
      resources <- mkResources(system, tmp)
      _ <- resources.sessionStore.getOrCreateSession(parentSid, "freeze spec parent", agentName = Some("Nebula"))
      _ <- resources.subAgentTaskStore.recordTask(mkTask(parentSid, childQuota, "delegate"))
      _ <- resources.subAgentTaskStore.recordTask(mkTask(parentSid, childCancel, "delegate"))
      _ <- IO(DelegateRegistry.put(mkRecord(childQuota, parentSid)))
      _ <- IO(DelegateRegistry.put(mkRecord(childCancel, parentSid)))
      quotaCmds <- Ref.of[IO, List[AgentCommand]](Nil)
      quotaChild <- system.spawn(mkRecordingChild(quotaCmds), "fz-quota-child")
      quotaParentEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      _ <- system.spawn(mkParentRecorder(quotaParentEvents), "fz-quota-parent")
      quotaSup <- system.spawn(
        BackoffSupervisor(
          childRef = quotaChild,
          childSpawnFn = (_, _) => system.spawn(mkRecordingChild(quotaCmds), "fz-quota-child2"),
          childName = childQuota,
          parentRef = None,
          description = "quota delegate",
          agentName = "Worker",
          subagentId = childQuota,
          parentSessionId = parentSid,
          resources = resources,
          initialPrompt = "go",
          source = "delegate",
          minBackoff = 50.millis,
          maxBackoff = 100.millis
        ),
        "fz-quota-sup"
      )
      cancelCmds <- Ref.of[IO, List[AgentCommand]](Nil)
      cancelChild <- system.spawn(mkRecordingChild(cancelCmds), "fz-cancel-child")
      cancelSup <- system.spawn(
        BackoffSupervisor(
          childRef = cancelChild,
          childSpawnFn = (_, _) => system.spawn(mkRecordingChild(cancelCmds), "fz-cancel-child2"),
          childName = childCancel,
          parentRef = None,
          description = "cancel delegate",
          agentName = "Worker",
          subagentId = childCancel,
          parentSessionId = parentSid,
          resources = resources,
          initialPrompt = "go",
          source = "delegate",
          minBackoff = 50.millis,
          maxBackoff = 100.millis
        ),
        "fz-cancel-sup"
      )
      _ <- quotaSup ! AgentEvent.Cancelled(childQuota, "insufficient_quota: monthly budget exhausted")
      _ <- cancelSup ! AgentEvent.Cancelled(childCancel, "cancelled by Nebula via AgentControl")
      _ <- waitUntil(20.seconds)(
        resources.subAgentTaskStore
          .loadTasks(parentSid)
          .map(ts => ts.find(_.taskId == childQuota).exists(_.status == DelegateFreeze.TaskStatus))
      )
      _ <- waitUntil(20.seconds)(
        resources.subAgentTaskStore
          .loadTasks(parentSid)
          .map(ts => ts.find(_.taskId == childCancel).exists(_.status == "cancelled"))
      )
      quotaRec <- IO(DelegateRegistry.find(childQuota).get)
      cancelRec <- IO(DelegateRegistry.find(childCancel).get)
      events <- mailboxEvents(parentSid)
      _ <- system.stopAll
    yield
      // 配额类取消 ⇒ 冻结（可等额度恢复后唤醒续做）。
      assertEquals(
        quotaRec.frozenReason,
        Some(DelegateFreeze.wireValue(DelegateFreeze.Reason.CancelledByQuota)),
        "a quota-exhausted cancel freezes with its own reason code"
      )
      assert(quotaRec.frozenSince.isDefined)
      // 冻结通知策略批（2026-11-05）：配额耗尽同属 provider 故障族（基础设施故障）⇒
      // 同样**零向上通知**（由面板徽标 + 静默统计 + 引擎自愈续跑接管）。
      assert(
        !events.exists(e =>
          e.eventType == "frozen" && e.metadata("frozenReason").flatMap(_.asString).contains("cancelled-by-quota")
        ),
        s"a quota freeze must stay silent too, got: ${events.map(e => e.eventType -> e.metadata("frozenReason"))}"
      )
      // 显式取消 ⇒ 既有 cancelled 终态（逐字不变：取消意图必须落地）。
      assertEquals(cancelRec.frozenSince, None, "an explicit cancel is never frozen (the intent must land)")
      assert(
        events.exists(e => e.eventType == "cancelled"),
        s"the explicit cancel keeps its cancelled terminal, got: ${events.map(_.eventType)}"
      )
    end for
  }

  test(
    "subtask track: Failed keeps the existing failed terminal (zero behavior change for cheap re-dispatchable workers)"
  ) {
    val system = ActorSystem(s"bsup-fz-3-${scala.util.Random.nextInt(100000)}")
    val tmp = os.temp.dir(prefix = "nb-bsup-fz3-")
    val parentSid = "fz-parent-3"
    val childSid = "subtask-fz-3"
    for
      resources <- mkResources(system, tmp)
      _ <- resources.sessionStore.getOrCreateSession(parentSid, "freeze spec parent", agentName = Some("Nebula"))
      _ <- resources.subAgentTaskStore.recordTask(mkTask(parentSid, childSid, "subtask"))
      parentEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- system.spawn(mkParentRecorder(parentEvents), "fz-parent-rec-3")
      childCmds <- Ref.of[IO, List[AgentCommand]](Nil)
      child <- system.spawn(mkRecordingChild(childCmds), "fz-sub-child")
      sup <- system.spawn(
        BackoffSupervisor(
          childRef = child,
          childSpawnFn = (_, _) => system.spawn(mkRecordingChild(childCmds), "fz-sub-child2"),
          childName = childSid,
          parentRef = Some(parentRef),
          description = "subtask failure",
          agentName = "Worker",
          subagentId = childSid,
          parentSessionId = parentSid,
          resources = resources,
          initialPrompt = "go",
          source = "subtask",
          minBackoff = 50.millis,
          maxBackoff = 100.millis
        ),
        "fz-sub-sup"
      )
      _ <- sup ! AgentEvent.Failed(childSid, err(AgentErrorType.LlmFailed, "provider down", childSid))
      _ <- waitUntil(20.seconds)(
        resources.subAgentTaskStore.loadTasks(parentSid).map(_.find(_.taskId == childSid).exists(_.status == "failed"))
      )
      events <- mailboxEvents(parentSid)
      refEvents <- parentEvents.get
      _ <- system.stopAll
    yield
      // subtask 轨 P2 邮箱化**未迁移**（P3 另批）：failed 终态走既有 ref 直投链，
      // 逐字保持既有形态——冻结批对它零行为变化。
      val pushed = refEvents.collect { case e: AgentCommand.ExternalEvent => e }
      assert(
        pushed.exists(e => e.eventType == "failed" && e.source == "subtask"),
        s"the subtask track keeps its honest failed terminal, got: ${pushed.map(e => e.source -> e.eventType)}"
      )
      assert(
        events.isEmpty,
        s"the subtask terminal never rides the delegate mailbox leg, got: ${events.map(_.eventType)}"
      )
      assertEquals(DelegateRegistry.find(childSid), None, "no freeze record is created for a subtask")
    end for
  }

  test(
    "fallback classification keeps the raw errorType: a Timeout failure freezes as engine-fault with the raw type verbatim in record + wire + row"
  ) {
    val system = ActorSystem(s"bsup-fz-4-${scala.util.Random.nextInt(100000)}")
    val tmp = os.temp.dir(prefix = "nb-bsup-fz4-")
    val parentSid = "fz-parent-4"
    val childSid = "delegate-fz-4"
    for
      resources <- mkResources(system, tmp)
      _ <- resources.sessionStore.getOrCreateSession(parentSid, "freeze spec parent", agentName = Some("Nebula"))
      _ <- resources.subAgentTaskStore.recordTask(mkTask(parentSid, childSid, "delegate"))
      _ <- IO(DelegateRegistry.put(mkRecord(childSid, parentSid)))
      parentEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- system.spawn(mkParentRecorder(parentEvents), "fz-parent-rec-4")
      childCmds <- Ref.of[IO, List[AgentCommand]](Nil)
      firstChild <- system.spawn(mkRecordingChild(childCmds), "fz-child-4")
      _ <- resources.agentRegistry.update(
        _ + (childSid -> AgentRecord(childSid, firstChild, AgentKind.Delegate, parentSid, Some(parentRef)))
      )
      supervisor <- system.spawn(
        BackoffSupervisor(
          childRef = firstChild,
          childSpawnFn =
            (_, _) => system.spawn(mkRecordingChild(childCmds), s"fz-child-4-${scala.util.Random.nextInt(100000)}"),
          childName = childSid,
          parentRef = Some(parentRef),
          description = "wire up the image pipeline",
          agentName = "Worker",
          subagentId = childSid,
          parentSessionId = parentSid,
          resources = resources,
          initialPrompt = "wire it up",
          source = "delegate",
          minBackoff = 50.millis,
          maxBackoff = 100.millis
        ),
        "fz-supervisor-4"
      )
      // Timeout + 判据表不命中的正文 ⇒ classify 落 engine-fault **兜底档**——原始
      // errorType 必须逐字保留（不得被兜底吞掉）。
      _ <- supervisor ! AgentEvent.Failed(
        childSid,
        err(AgentErrorType.Timeout, "turn exceeded the wall clock without progress", childSid)
      )
      _ <- waitUntil(20.seconds)(
        resources.subAgentTaskStore
          .loadTasks(parentSid)
          .map(_.find(_.taskId == childSid).exists(_.status == DelegateFreeze.TaskStatus))
      )
      _ <- IO.sleep(400.millis)
      tasks <- resources.subAgentTaskStore.loadTasks(parentSid)
      record <- IO(DelegateRegistry.find(childSid).get)
      reg <- resources.agentRegistry.get
      _ <- system.stopAll
    yield
      // 兜底档：原因码 = engine-fault；诊断后缀与详情携带**逐字**的原始 errorType。
      assertEquals(
        record.frozenReason.map(_.takeWhile(_ != '[')),
        Some(DelegateFreeze.wireValue(DelegateFreeze.Reason.EngineFault)),
        s"the fallback freezes as engine-fault, got: ${record.frozenReason}"
      )
      assert(
        record.frozenReason.exists(_.contains("errorType=Timeout")),
        s"the raw errorType survives in the wire suffix, got: ${record.frozenReason}"
      )
      assert(
        record.frozenDetail.exists(d => d.startsWith("trigger=failed-event; errorType=Timeout; ")),
        s"the diagnostic prefix leads the detail verbatim, got: ${record.frozenDetail}"
      )
      assertEquals(record.frozenTrigger, Some("failed-event"))
      val row = tasks.find(_.taskId == childSid).get
      assert(
        row.lastError.exists(_.contains("errorType=Timeout")),
        s"the task row carries the raw errorType too, got: ${row.lastError}"
      )
      // 内存态 registry 面仍写裸原因码线值（面板短名不吞诊断正文——诊断走详情/后缀面）。
      assertEquals(
        reg.get(childSid).flatMap(_.frozenReason),
        Some(DelegateFreeze.wireValue(DelegateFreeze.Reason.EngineFault))
      )
    end for
  }

end BackoffSupervisorFreezeSpec
