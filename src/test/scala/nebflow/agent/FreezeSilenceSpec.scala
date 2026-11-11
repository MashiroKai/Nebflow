package nebflow.agent

import cats.effect.{IO, Ref}
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import fs2.Stream
import munit.CatsEffectSuite
import nebflow.actor.{
  ActorSystem,
  AgentCommand,
  AgentError,
  AgentErrorType,
  AgentEvent,
  AgentKind,
  AgentRecord,
  Behavior,
  Behaviors
}
import nebflow.core.FileChangeTracker
import nebflow.core.compact.HistoryArchiver
import nebflow.core.delegate.{DelegateFreeze, DelegateJudge, DelegateRegistry, FreezeNotice}
import nebflow.core.mail.SessionMailbox
import nebflow.core.project.DispatchNotify
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.*

import scala.concurrent.duration.*

/**
 * 冻结通知策略批（2026-11-05）判红锚 —— 作者令两连逐字：
 *
 *   10:23「然后frozen这种可恢复的，不需要通知，」
 *   10:36「不论是节点还是delegate，由于provider 故障，都不需要向上通知，冻结等待恢复就
 *         行。然后在面板显示，这个是需要用户去处理的，我们引擎要做的就行检测恢复了能
 *         接着工作。所以不需要向上汇报。因为provider 故障上级也基本死了，汇报给上级没用。」
 *
 * 钉死的断言（任务书验收 + 对齐清单「反向验红」）：
 *   ① provider 全败冻结 ⇒ **零向上通知**（父会话邮箱 0 条、父 ref 0 条），
 *      面板判定 = 「待你处理 + 可自动恢复」，唤醒入口仍在（kind=manual ⇒ 行上给按钮）；
 *   ② 同一波 6 条 provider 冻结 ⇒ 通知 **0** 条，静默统计读出 6；
 *   ③ 需人处理的确定性缺陷 ⇒ **一条**聚合告警（同一波 3 条仍是 1 条，重复拍不重发）；
 *   ④ provider 未服务 ⇒ 不抢跑；服务恢复 ⇒ 自动续跑走 **UserInput**（带幂等键，
 *      **不是** RestartAgent，不花 supervisor 重启预算），通知仍为 0；退避为
 *      **指数 + 封顶**（三个连续重试时刻给读数），升级判据是**本轮冻结超过 30 分钟
 *      时限**（不是尝试次数用尽）；健康表只许**延迟**续跑、不许无限期否决
 *      （fallback 那条旧缺陷的正解）；
 *   ⑤ flow-notify 闸：基础设施故障文本 ⇒ 不向上汇报（纯函数判据），确定性缺陷
 *      （AssertionError / NPE / 校验和 / 越权 / 业务失败）仍照常通知。
 *   ⑥ **判据表**（`ProviderFault.Table`）：鉴权订阅 403 / 配额余额 / 上游 5xx 含 550 /
 *      连接失败超时 / 格式层拒绝含 `Input too long` 逐族可恢复；代码缺陷族照常通知。
 */
class FreezeSilenceSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val fakeLlm = new LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("fake llm not expected here"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.raiseError[IO](new RuntimeException("fake llm not expected here"))

  private val originalRoot = PathUtil.dataRoot
  private var base: os.Path = os.pwd / "target" / "test-freeze-silence"

  private val props = List(
    "nebflow.delegate.freeze.enabled",
    "nebflow.delegate.freeze.silence",
    "nebflow.delegate.freeze.autoResume",
    "nebflow.delegate.freeze.autoResumeAfterMs",
    "nebflow.delegate.freeze.autoResumeMax",
    "nebflow.delegate.freeze.autoResumeBackoffMs",
    "nebflow.delegate.freeze.autoResumeBackoffMaxMs",
    "nebflow.delegate.freeze.autoResumeForceAfterMs",
    "nebflow.delegate.freeze.escalateAfterMs",
    "nebflow.delegate.freeze.digestWindowMs",
    "nebflow.delegate.freeze.alertReArmMs"
  )

  override def beforeAll(): Unit =
    base = os.temp.dir(prefix = "nb-freeze-silence-")
    PathUtil.setDataRoot(base / "data")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    try os.remove.all(base)
    catch case _: Throwable => ()

  override def beforeEach(context: munit.BeforeEach): Unit =
    DelegateJudge.DelegateJudgePort.clear()
    props.foreach(p => sys.props.remove(p))
    FreezeNotice.resetLedger()
    FreezeAlertSweep.resetForTest()
    // registry 是 `<dataRoot>/delegates` 下的持久目录：不清理的话上一用例的冻结记录
    // 会漏进下一用例的全量统计（digest.total 7 ≠ 6）。
    try os.remove.all(PathUtil.dataRoot / "delegates")
    catch case _: Throwable => ()

  override def afterEach(context: munit.AfterEach): Unit =
    props.foreach(p => sys.props.remove(p))
    FreezeNotice.resetLedger()
    FreezeAlertSweep.resetForTest()

  private def mkRecordingChild(record: Ref[IO, List[AgentCommand]]): Behavior[AgentCommand] =
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
      healthMonitor = new ProviderHealthMonitor(null),
      actorSystem = system,
      subAgentTaskStore = new SubAgentTaskStore(tmp / "subagent-tasks"),
      voiceMutedRef = voiceMuted
    )

  private def mkTask(parentSid: String, childSid: String): SubAgentTask =
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
      source = "delegate"
    )

  private def mkRecord(id: String, parentSid: String): DelegateRegistry.Record =
    DelegateRegistry.Record(
      id = id,
      executor = "nebflow",
      cwd = os.pwd.toString,
      project = None,
      taskId = None,
      parentSessionId = parentSid,
      title = s"freeze silence spec record $id",
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

  private def mailboxEvents(parentSid: String): IO[List[AgentCommand.ExternalEvent]] =
    for
      pending <- SessionMailbox.scanPending(parentSid)
      taken <- SessionMailbox.scanTaken(parentSid)
    yield (pending ++ taken).map(_.event)

  /** 冻结一条 delegate（经监督者的 Failed 腿，与生产同一条路）。 */
  private def freezeDelegate(
    resources: SharedResources,
    system: ActorSystem,
    parentSid: String,
    childSid: String,
    errorType: AgentErrorType,
    message: String,
    childCmds: Ref[IO, List[AgentCommand]],
    parentRef: Ref[IO, List[AgentCommand]]
  ): IO[Unit] =
    for
      parentActor <- system.spawn(mkRecordingChild(parentRef), s"parent-${childSid}")
      child <- system.spawn(mkRecordingChild(childCmds), s"child-${childSid}")
      sup <- system.spawn(
        BackoffSupervisor(
          childRef = child,
          childSpawnFn = (_, _) => system.spawn(mkRecordingChild(childCmds), s"child2-${childSid}"),
          childName = childSid,
          parentRef = Some(parentActor),
          description = s"$childSid work",
          agentName = "Worker",
          subagentId = childSid,
          parentSessionId = parentSid,
          resources = resources,
          initialPrompt = "go",
          source = "delegate",
          minBackoff = 50.millis,
          maxBackoff = 100.millis
        ),
        s"sup-$childSid"
      )
      _ <- IO(DelegateRegistry.put(mkRecord(childSid, parentSid)))
      // 冻结夹具必须带 child 的**已存转录**（纯文本、无未决 tool_use）：恢复完整性闸
      // （RECOVERY_UNVERIFIED）只放行「存档可验证」的唤醒——真实世界里冻住的 delegate
      // 一定跑过真轮次、有转录；旧夹具缺这一腿，唤醒腿按设计正确拒醒（这正是两条
      // 移交红的根因：sweep 出手了、wake 腿拒了）。空转录拒醒是产品语义，不是缺陷。
      _ <- resources.sessionStore.getOrCreateSession(childSid, s"$childSid work", agentName = Some("Worker"))
      _ <- resources.sessionStore.saveMessagesForSession(
        childSid,
        List(
          Message(MessageRole.User, Left("go")),
          Message(MessageRole.Assistant, Left("ack — partial work recorded before the freeze"))
        )
      )
      _ <- resources.subAgentTaskStore.recordTask(mkTask(parentSid, childSid))
      _ <- resources.agentRegistry.update(
        _ + (childSid -> AgentRecord(childSid, child, AgentKind.Delegate, parentSid, Some(parentActor)))
      )
      _ <- sup ! AgentEvent.Failed(childSid, AgentError(childSid, "Worker", 1, errorType, message))
      _ <- waitUntil(20.seconds)(
        resources.subAgentTaskStore
          .loadTasks(parentSid)
          .map(_.find(_.taskId == childSid).exists(_.status == DelegateFreeze.TaskStatus))
      )
      _ <- IO.sleep(200.millis)
    yield ()

  // ── ① provider 全败 ⇒ 零向上通知 ─────────────────────────────────────────

  test(
    "provider failure freeze: ZERO upward notification (parent mailbox + parent ref), panel says needs-you-but-auto-recoverable, wake entry stays"
  ) {
    val system = ActorSystem(s"fsz-1-${scala.util.Random.nextInt(100000)}")
    val tmp = os.temp.dir(prefix = "nb-fsz1-")
    val parentSid = "fsz-parent-1"
    val childSid = "delegate-fsz-1"
    for
      resources <- mkResources(system, tmp)
      _ <- resources.sessionStore.getOrCreateSession(parentSid, "freeze silence parent", agentName = Some("Nebula"))
      childCmds <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- Ref.of[IO, List[AgentCommand]](Nil)
      _ <- freezeDelegate(
        resources,
        system,
        parentSid,
        childSid,
        AgentErrorType.LlmFailed,
        "All providers failed (2 attempts; families: auth): [auth] kimi/k3: 403 no subscription; deepseek: 403 forbidden",
        childCmds,
        parentRef
      )
      events <- mailboxEvents(parentSid)
      pushed <- parentRef.get
      rec <- IO(DelegateRegistry.find(childSid).get)
      reg <- resources.agentRegistry.get
      cmds <- childCmds.get
      digest = FreezeNotice.digestOf(DelegateRegistry.list(), System.currentTimeMillis())
      _ <- system.stopAll
    yield
      // ① provider 故障 = 基础设施故障 ⇒ **零向上通知**（邮箱与 ref 两面都为 0）。
      assertEquals(events.map(e => e.eventType -> e.source), Nil, s"no upward message at all, got $events")
      assertEquals(
        pushed.collect { case e: AgentCommand.ExternalEvent => e.eventType },
        Nil,
        s"the legacy ref push chain must stay silent too, got $pushed"
      )
      // ② 冻结事实照常落盘（面板/唤醒地址锚）。
      assertEquals(rec.frozenReason, Some(DelegateFreeze.wireValue(DelegateFreeze.Reason.LlmFailure)))
      assert(rec.frozenSince.isDefined)
      assertEquals(reg.get(childSid).map(_.status), Some(nebflow.actor.AgentStatus.Frozen))
      // ③ 面板态：待你处理 + 可自动恢复（唤醒入口靠 kind=manual 的行按钮在场）。
      val fd = FreezeNotice.displayOf("delegate-frozen:llm-failure")
      assert(fd.needsHuman, "the row must read as 'needs you'")
      assert(fd.autoRecoverable, "provider faults are auto-recoverable by the engine")
      assert(fd.labelZh.contains("待你处理"), s"label says awaiting-you: ${fd.labelZh}")
      assert(!fd.labelZh.contains("引擎不会自动续跑"), "the old 'engine never resumes' claim must be gone")
      // ④ 静默统计读出这条冻结。
      assertEquals(digest.total, 1)
      assertEquals(digest.infraCount, 1)
      // ⑤ 冻结不注入重试、不重启（引擎不靠"反复真调用"恢复）。
      assert(
        !cmds.exists { case _: AgentCommand.UserInput => true; case _ => false },
        "freezing must not inject a retry nudge by itself"
      )
    end for
  }

  // ── ② 同一波 6 条 ⇒ 通知 0，统计 6 ───────────────────────────────────────

  test("one wave of 6 provider-failure freezes: notification count = 0 and the quiet digest reads 6") {
    val system = ActorSystem(s"fsz-2-${scala.util.Random.nextInt(100000)}")
    val tmp = os.temp.dir(prefix = "nb-fsz2-")
    val parentSid = "fsz-parent-2"
    val children = (1 to 6).map(i => s"delegate-fsz-wave-$i").toList
    for
      resources <- mkResources(system, tmp)
      _ <- resources.sessionStore.getOrCreateSession(parentSid, "freeze silence parent", agentName = Some("Nebula"))
      childCmds <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- Ref.of[IO, List[AgentCommand]](Nil)
      _ <- children.traverse_(cid =>
        freezeDelegate(
          resources,
          system,
          parentSid,
          cid,
          AgentErrorType.LlmFailed,
          "provider anthropic/claude-x: 529 overloaded — all candidates exhausted",
          childCmds,
          parentRef
        )
      )
      // 告警拍也跑一遍：provider 故障族不该产出任何告警。
      _ <- FreezeAlertSweep.alertTick(resources)
      events <- mailboxEvents(parentSid)
      digest = FreezeNotice.digestOf(DelegateRegistry.list(), System.currentTimeMillis())
      _ <- system.stopAll
    yield
      assertEquals(events.map(_.eventType), Nil, s"6 recoverable freezes ⇒ 0 messages, got ${events.map(_.eventType)}")
      assertEquals(digest.total, 6, "the panel digest counts the whole wave")
      assertEquals(digest.infraCount, 6)
      assertEquals(digest.silentCount, 6)
      assertEquals(FreezeNotice.planAlerts(digest), Nil, "no alert is planned for a recoverable wave")
    end for
  }

  // ── ③ 需人处理 ⇒ 一条聚合告警 ────────────────────────────────────────────

  test(
    "deterministic-defect freezes produce exactly ONE aggregated alert for the whole wave (and no repeat on the next tick)"
  ) {
    val system = ActorSystem(s"fsz-3-${scala.util.Random.nextInt(100000)}")
    val tmp = os.temp.dir(prefix = "nb-fsz3-")
    val parentSid = "fsz-parent-3"
    val children = List("delegate-fsz-def-1", "delegate-fsz-def-2", "delegate-fsz-def-3")
    for
      resources <- mkResources(system, tmp)
      _ <- resources.sessionStore.getOrCreateSession(parentSid, "freeze silence parent", agentName = Some("Nebula"))
      childCmds <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- Ref.of[IO, List[AgentCommand]](Nil)
      _ <- children.traverse_(cid =>
        freezeDelegate(
          resources,
          system,
          parentSid,
          cid,
          AgentErrorType.ToolFailed,
          "tool pipeline crashed: NullPointerException in render step",
          childCmds,
          parentRef
        )
      )
      // 冻结腿本身一条都不推（全部交给同波聚合）。
      beforeAlert <- mailboxEvents(parentSid)
      _ <- FreezeAlertSweep.alertTick(resources)
      afterFirst <- mailboxEvents(parentSid)
      _ <- FreezeAlertSweep.alertTick(resources)
      afterSecond <- mailboxEvents(parentSid)
      _ <- system.stopAll
    yield
      assertEquals(
        beforeAlert.map(_.eventType),
        Nil,
        s"the freeze leg must not push per-freeze receipts, got ${beforeAlert.map(_.eventType)}"
      )
      val alerts = afterFirst.filter(_.eventType == "freeze-alert")
      assertEquals(alerts.size, 1, s"exactly ONE aggregated alert for 3 freezes, got ${afterFirst.map(_.eventType)}")
      val a = alerts.head
      assert(
        a.metadata("count").exists(j => j.asNumber.flatMap(_.toInt).contains(3)),
        s"the alert carries the wave size: ${a.metadata}"
      )
      assert(a.payload.contains("delegate:delegate-fsz-def-1"), s"wake addresses listed: ${a.payload.take(300)}")
      assert(a.payload.contains("aggregated alert"), "the text tells the reader it is ONE aggregated alert")
      assertEquals(afterSecond.count(_.eventType == "freeze-alert"), 1, "the next tick must not repeat it")
    end for
  }

  // ── ④ 自动续跑：探测 → 续跑（不花预算）→ 上限 → 一条告警 ────────────────

  test(
    "auto-resume: waits while no provider serves, then resumes the SAME session via UserInput (never RestartAgent) with zero notification"
  ) {
    val system = ActorSystem(s"fsz-4-${scala.util.Random.nextInt(100000)}")
    val tmp = os.temp.dir(prefix = "nb-fsz4-")
    val parentSid = "fsz-parent-4"
    val childSid = "delegate-fsz-auto-1"
    val configChildSid = "delegate-fsz-auto-2"
    sys.props("nebflow.delegate.freeze.autoResumeAfterMs") = "0"
    for
      resources <- mkResources(system, tmp)
      _ <- resources.sessionStore.getOrCreateSession(parentSid, "freeze silence parent", agentName = Some("Nebula"))
      childCmds <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- Ref.of[IO, List[AgentCommand]](Nil)
      _ <- freezeDelegate(
        resources,
        system,
        parentSid,
        childSid,
        AgentErrorType.LlmFailed,
        // 载体详情必须是**自动续跑预算内**的临时族（upstream-5xx）：预算闸
        // （`automaticRecoveryAllowed`，bounded-recovery 批）只放行临时族，
        // 配置失效族（403 无订阅）要人处理、永不自动续跑——该半边契约由下方
        // config 控制行 + `ProviderRecoveryBudgetSpec` 钉死。本用例钉的是
        // **机制**（不抢跑 → 恢复 → 同会话 UserInput 续跑），载体换族语义不变。
        "provider kimi/k3: 529 upstream overloaded",
        childCmds,
        parentRef
      )
      // 控制行：配置失效族（403 无订阅）同环境冻结——供应商恢复后它**必须**仍冻着
      //（改订阅要人，自动续跑一个必然再失败的会话只会烧唤醒次数）。
      configCmds <- Ref.of[IO, List[AgentCommand]](Nil)
      _ <- freezeDelegate(
        resources,
        system,
        parentSid,
        configChildSid,
        AgentErrorType.LlmFailed,
        "provider kimi/k3: 403 no subscription",
        configCmds,
        parentRef
      )
      // 供应商仍不可用 ⇒ 不抢跑。
      _ <- resources.healthMonitor.markDown("kimi", "k3", "529 upstream overloaded")
      _ <- FreezeAlertSweep.autoResumeTick(resources, None)
      cmdsWhileDown <- childCmds.get
      // 唤醒键要在唤醒前取（唤醒后冻结槽被 onWoken 清空 ⇒ frozenSince 归 None）。
      frozenSinceBefore <- IO(DelegateRegistry.find(childSid).flatMap(_.frozenSince).getOrElse(0L))
      // 供应商恢复 ⇒ 自动续跑同一会话。
      _ <- resources.healthMonitor.markUp("kimi", "k3")
      _ <- FreezeAlertSweep.autoResumeTick(resources, None)
      cmdsAfterUp <- childCmds.get
      configStillFrozen <- IO(DelegateRegistry.find(configChildSid).flatMap(_.frozenSince).isDefined)
      events <- mailboxEvents(parentSid)
      _ <- system.stopAll
    yield
      assert(
        !cmdsWhileDown.exists { case _: AgentCommand.UserInput => true; case _ => false },
        s"no auto-resume while the provider is still down, got $cmdsWhileDown"
      )
      val wakes = cmdsAfterUp.collect { case u: AgentCommand.UserInput => u }
      assertEquals(wakes.size, 1, s"exactly one auto-resume wake, got ${cmdsAfterUp.map(_.getClass.getSimpleName)}")
      // 预算闸的另一面（bounded-recovery 批契约在集成面的钉子）：配置失效族控制行
      // 在供应商已恢复的一拍里零唤醒、仍冻着——自动续跑只属临时族。
      assert(
        configStillFrozen,
        "the config-family control freeze must stay frozen — configuration failures need manual recovery"
      )
      val w = wakes.head
      assert(w.text.contains("[frozen-wake]"), s"the wake carries the frozen-wake banner: ${w.text.take(200)}")
      assertEquals(
        w.clientMessageId,
        Some(s"frozen-wake:$childSid:$frozenSinceBefore"),
        "idempotent wake key"
      )
      // 苏醒载荷的 source 标签：本用例要钉的是「这是一次 delegate 苏醒（不是 RestartAgent）」，
      // 不是某个字面量。标签单一真源在代码侧——旧腿 = `DelegateWakeLeg.LiveSource`
      // ("delegate-wake")，resume-transparent 批（2026-11-07）后统一走
      // `nebflow.core.ResumeHandoff.Source` ("resume")。两值都接受，避免标签换代把
      // 这条语义用例变成假红。
      assert(
        Set("delegate-wake", "resume").contains(w.source.getOrElse("")),
        s"the wake must carry a delegate-wake/resume source label, got ${w.source}"
      )
      // 不花 supervisor 重启预算：没有任何 RestartAgent / respawn 指令。
      assert(
        !cmdsAfterUp.exists { case _: AgentCommand.RestartAgent => true; case _ => false },
        s"a provider fault must never spend the restart budget, got ${cmdsAfterUp.map(_.getClass.getSimpleName)}"
      )
      // 续跑成功/失败都不发通知。
      assertEquals(events.map(_.eventType), Nil, s"auto-resume is silent, got ${events.map(_.eventType)}")
    end for
  }

  test("auto-resume budget: capped attempts with growing gaps, and only an exhausted budget escalates to ONE alert") {
    // 纯函数判据：间隔递增（after=0/backoff=60s ⇒ 0 / +60s / +120s …）。
    sys.props("nebflow.delegate.freeze.autoResumeAfterMs") = "0"
    sys.props("nebflow.delegate.freeze.autoResumeBackoffMs") = "60000"
    sys.props("nebflow.delegate.freeze.autoResumeMax") = "3"
    val t0 = 1_000_000L
    assert(FreezeNotice.autoResumeDue(t0, t0, attempts = 0), "the first attempt is due right away")
    assert(!FreezeNotice.autoResumeDue(t0, t0 + 30_000L, attempts = 1), "attempt #2 waits one backoff gap")
    assert(FreezeNotice.autoResumeDue(t0, t0 + 60_000L, attempts = 1))
    assert(!FreezeNotice.autoResumeDue(t0, t0 + 60_000L, attempts = 2), "attempt #3 waits two gaps")
    assert(FreezeNotice.autoResumeDue(t0, t0 + 180_000L, attempts = 2))

    val system = ActorSystem(s"fsz-5-${scala.util.Random.nextInt(100000)}")
    val tmp = os.temp.dir(prefix = "nb-fsz5-")
    val parentSid = "fsz-parent-5"
    val childSid = "delegate-fsz-budget-1"
    sys.props("nebflow.delegate.freeze.autoResumeMax") = "2"
    for
      resources <- mkResources(system, tmp)
      _ <- resources.sessionStore.getOrCreateSession(parentSid, "freeze silence parent", agentName = Some("Nebula"))
      childCmds <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- Ref.of[IO, List[AgentCommand]](Nil)
      _ <- freezeDelegate(
        resources,
        system,
        parentSid,
        childSid,
        AgentErrorType.LlmFailed,
        "deepseek: 余额耗尽 (insufficient balance)",
        childCmds,
        parentRef
      )
      // 让续跑腿不必真跑：直接记账（同一份进程内台账）。
      since <- FreezeNotice
        .itemsOf(DelegateRegistry.list(), System.currentTimeMillis(), 0L)
        .headOption
        .map(_.frozenSince)
        .liftTo[IO](new AssertionError("the frozen record must be visible to the digest"))
      _ <- IO(FreezeNotice.recordAutoResume(childSid, since, System.currentTimeMillis()))
      _ <- IO(FreezeNotice.recordAutoResume(childSid, since, System.currentTimeMillis()))
      attempts = FreezeNotice.autoResumesOf(childSid, since)
      exhausted = FreezeNotice.autoResumesExhausted(childSid, since)
      digest = FreezeNotice.digestOf(DelegateRegistry.list(), System.currentTimeMillis())
      // provider 故障降噪批 2026-11-05：次数用尽**不再是升级判据**（次数只作面板读数），
      // 升级改由「本轮冻结超过 30 分钟」判定 ⇒ 此处必须**没有**告警计划。
      planned = FreezeNotice.planAlerts(digest)
      _ <- FreezeAlertSweep.alertTick(resources)
      events <- mailboxEvents(parentSid)
      until = System.currentTimeMillis()
      escalateNow = FreezeNotice.escalateDue(childSid, since, until)
      escalateLater = FreezeNotice.escalateDue(childSid, since, since + Defaults.DelegateFreezeEscalateAfterMs + 1)
      _ <- system.stopAll
    yield
      assertEquals(attempts, 2, "the ledger records the attempts")
      assert(exhausted, "the attempt counter reaches the panel-readout cap")
      assertEquals(planned, Nil, "a spent count no longer escalates — the horizon does")
      assertEquals(events.map(_.eventType), Nil, s"no message inside the horizon, got ${events.map(_.eventType)}")
      assert(!escalateNow, "fresh freeze ⇒ inside the 30-minute horizon")
      assert(escalateLater, "past the horizon ⇒ escalation is due")
    end for
  }

  test("auto-resume backoff: exponential with a CAP, and the retry schedule is never a fixed short loop") {
    // 任务书验收③：给实际参数 + 三个连续重试的时间点（证明指数退避不是死循环）。
    sys.props("nebflow.delegate.freeze.autoResumeAfterMs") = "0"
    sys.props("nebflow.delegate.freeze.autoResumeBackoffMs") = "60000"
    sys.props("nebflow.delegate.freeze.autoResumeBackoffMaxMs") = "300000"
    val t0 = 1_000_000L
    assert(FreezeNotice.autoResumeDue(t0, t0, attempts = 0), "the first attempt is due right away")
    assert(!FreezeNotice.autoResumeDue(t0, t0 + 30_000L, attempts = 1), "attempt #2 waits one backoff gap")
    assert(FreezeNotice.autoResumeDue(t0, t0 + 60_000L, attempts = 1))
    assert(!FreezeNotice.autoResumeDue(t0, t0 + 60_000L, attempts = 2), "attempt #3 waits two gaps")
    assert(FreezeNotice.autoResumeDue(t0, t0 + 120_000L, attempts = 2))
    // 封顶：第 4 次起间隔不再翻倍（120s ⇒ 300s 封顶），恒按 300s。
    assert(FreezeNotice.autoResumeDue(t0, t0 + 240_000L, attempts = 3))
    assert(!FreezeNotice.autoResumeDue(t0, t0 + 240_000L, attempts = 4), "capped at 300s, not 480s")
    assert(FreezeNotice.autoResumeDue(t0, t0 + 300_000L, attempts = 4))
    assert(!FreezeNotice.autoResumeDue(t0, t0 + 299_999L, attempts = 12), "the cap holds for every later try")
    assert(FreezeNotice.autoResumeDue(t0, t0 + 300_000L, attempts = 12))

    // 三个连续重试时刻（实际参数 after=60s / base=60s / cap=300s，冻结时刻 t0）：
    //   #1 t0+60s · #2 t0+120s · #3 t0+180s · #4 t0+300s · #5 t0+360s（此后每 300s）
    sys.props("nebflow.delegate.freeze.autoResumeAfterMs") = "60000"
    // 退避曲线读数：第 n 次尝试的最早时刻（从冻结时刻起算）。
    def firstDueAt(attempts: Int): Long =
      (0 to 1200).map(_.toLong * 1000L).find(ms => FreezeNotice.autoResumeDue(t0, t0 + ms, attempts)).getOrElse(-1L)
    // 实际参数 after=60s / base=60s / cap=300s 下的三个连续重试时刻（任务书验收③）：
    //   #1 t0+60s · #2 t0+120s · #3 t0+180s —— 指数段；#4 t0+300s · #5 起封顶 t0+360s。
    val timeline: Vector[Long] = (0 until 5).map(firstDueAt).toVector
    assertEquals(
      timeline.map(_ / 1000L),
      Vector(60L, 120L, 180L, 300L, 360L),
      s"exponential until the 300s cap, then flat: ${timeline.map(_ / 1000L)}"
    )
    assert(
      timeline.forall(_ >= 60_000L),
      s"no fixed 5s-style hammering: every gap is >= 60s, got ${timeline.map(_ / 1000L)}s"
    )
    // 指数证据：首两次重试相隔 60s，之后增长量翻倍（+60s → +120s → 封顶 +300s）。
    val steps = (1 until timeline.size).map(i => timeline(i) - timeline(i - 1)).toVector
    assertEquals(steps.map(_ / 1000L), Vector(60L, 60L, 120L, 60L), s"exponential growth of the gaps: $steps")
    assert(firstDueAt(4) == firstDueAt(5), "past the cap every attempt is at the same schedule")
  }

  test(
    "escalation horizon (重写, fallback 废除批): a provider freeze stays silent inside AND past the 30-minute horizon — provider digest leg retired (zero upward notify)"
  ) {
    val system = ActorSystem(s"fsz-6-${scala.util.Random.nextInt(100000)}")
    val tmp = os.temp.dir(prefix = "nb-fsz6-")
    val parentSid = "fsz-parent-6"
    val childSid = "delegate-fsz-horizon-1"
    sys.props("nebflow.delegate.freeze.autoResumeAfterMs") = "0"
    sys.props("nebflow.delegate.freeze.autoResumeMax") = "2"
    for
      resources <- mkResources(system, tmp)
      _ <- resources.sessionStore.getOrCreateSession(parentSid, "freeze silence parent", agentName = Some("Nebula"))
      childCmds <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- Ref.of[IO, List[AgentCommand]](Nil)
      _ <- freezeDelegate(
        resources,
        system,
        parentSid,
        childSid,
        AgentErrorType.LlmFailed,
        "cmdcode/deepseek-flash: 550 — upstream returned an empty body",
        childCmds,
        parentRef
      )
      since <- FreezeNotice
        .itemsOf(DelegateRegistry.list(), System.currentTimeMillis(), 0L)
        .headOption
        .map(_.frozenSince)
        .liftTo[IO](new AssertionError("the frozen record must be visible to the digest"))
      // 次数早已超过 autoResumeMax：时限内仍然不许升级。
      _ <- IO((1 to 6).foreach(_ => FreezeNotice.recordAutoResume(childSid, since, System.currentTimeMillis())))
      inside = FreezeNotice.planAlerts(FreezeNotice.digestOf(DelegateRegistry.list(), System.currentTimeMillis()))
      _ <- FreezeAlertSweep.alertTick(resources)
      eventsInside <- mailboxEvents(parentSid)
      // 时限到（把时限压到 1ms，等价于「本轮冻结已超 30 分钟」）。
      _ <- IO(sys.props("nebflow.delegate.freeze.escalateAfterMs") = "1")
      _ <- IO(Thread.sleep(5))
      outside = FreezeNotice.planAlerts(FreezeNotice.digestOf(DelegateRegistry.list(), System.currentTimeMillis()))
      _ <- FreezeAlertSweep.alertTick(resources)
      eventsOutside <- mailboxEvents(parentSid)
      _ <- system.stopAll
    yield
      assertEquals(inside, Nil, "inside the horizon a provider freeze is never escalated, no matter how many resumes")
      assertEquals(
        eventsInside.map(_.eventType),
        Nil,
        s"inside the horizon: zero messages, got ${eventsInside.map(_.eventType)}"
      )
      // fallback 废除批（作者令 2026-11-06 20:36「只在子agent面板里显示……不再向上通知」）：
      // 「超 30 分钟升级为一条摘要」本身就是一条向上通知 ⇒ provider 腿整体退役——时限
      // 内/外都零告警，呈现面只有子 agent 面板。原断言「时限外恰 1 条 digest」随批退役。
      assertEquals(
        outside,
        Nil,
        "past the horizon TOO: zero alerts (the 30-minute provider digest was itself an upward notify — retired)"
      )
      val alerts = eventsOutside.filter(_.eventType == "freeze-alert")
      assertEquals(
        alerts.size,
        0,
        s"zero digest alerts past the horizon (provider family), got ${eventsOutside.map(_.eventType)}"
      )
    end for
  }

  test("provider-fault table: every recoverable family is silent, every deterministic defect still notifies") {
    import nebflow.core.delegate.ProviderFault
    // 任务书验收①：可恢复族逐类（作者报障那一屏的四类都在里面，另加 5xx/连接/频率）。
    val recoverable = List(
      "kimi/k3: HTTP 403 no subscription for this account",
      "flysin: insufficient balance — top up to continue",
      "cmdcode/deepseek-flash: statusCode: 550, response:",
      "zhipu/glm: 429 {\"error\":{\"code\":\"1308\"}} 已达到 5 小时的使用上限",
      "anthropic: 529 overloaded",
      "ConnectException: Connection refused",
      "java.net.SocketTimeoutException: connect timed out",
      "cmdcode/deepseek-flash: 400 invalid request — Input too long",
      "request rejected: maximum context length exceeded (context_length_exceeded)"
    )
    recoverable.foreach { d =>
      assert(ProviderFault.isRecoverable(d), s"recoverable family missed: $d")
      assert(FreezeNotice.infraFault(d), s"infraFault must agree (single ruler): $d")
      assertEquals(
        DelegateFreeze.classify("Unknown", d),
        DelegateFreeze.Reason.LlmFailure,
        s"a provider fault must land on the silent llm-failure code, not engine-fault: $d"
      )
      assert(FreezeNotice.silent(DelegateFreeze.wireValue(DelegateFreeze.Reason.LlmFailure)))
    }
    // 真失败：确定性缺陷（代码 bug / 越权 / 数据损坏 / 业务失败）⇒ 照常通知。
    val real = List(
      "AssertionError: expected 1 but got 2",
      "java.lang.NullPointerException: Cannot invoke cwd() because rec is null",
      "compile error: value foo is not a member of bar",
      "tool pipeline crashed: checksum mismatch on artifact",
      "越权：not allowed to write outside the workspace",
      "显式业务失败：explicit business failure — the report was rejected by the gate"
    )
    real.foreach { d =>
      assert(ProviderFault.isRealFailure(d), s"deterministic defect missed: $d")
      assert(!FreezeNotice.infraFault(d), s"a real defect must never be silenced: $d")
      assert(
        !FreezeNotice.silent(DelegateFreeze.wireValue(DelegateFreeze.classify("Unknown", d))),
        s"a real defect must escalate: $d"
      )
    }
    // 判定优先级：同一段正文里既有 provider 字样又有代码缺陷字样 ⇒ 按真失败处置。
    assert(!FreezeNotice.infraFault("provider 503 but the tool pipeline threw a NullPointerException"))
    // 未命中任何族的正文不许静音（宁可照常通知）。
    assert(!FreezeNotice.infraFault("no such file or directory: build.sbt"))
  }

  test("fallback chain: a Down model elsewhere never blocks the resume (there IS a usable model in the queue)") {
    // 旧缺陷（delegate/delegate-fallback-chain-fix）：「fallback 队列里明明有可用模型，
    // 却报所有模型不可用并冻结 agent」。真实盘面 = 健康表里**只有被判过死的候选**
    // （探针只探 Down 的），健康的候选往往没有条目；旧判据「表里一条 Down 都没有」
    // 因此永假 ⇒ 冻结批永远等不到自动续跑。本批的正解 = 健康表只许延迟，不许否决。
    val system = ActorSystem(s"fsz-7-${scala.util.Random.nextInt(100000)}")
    val tmp = os.temp.dir(prefix = "nb-fsz7-")
    val parentSid = "fsz-parent-7"
    val childSid = "delegate-fsz-chain-1"
    sys.props("nebflow.delegate.freeze.autoResumeAfterMs") = "0"
    for
      resources <- mkResources(system, tmp)
      _ <- resources.sessionStore.getOrCreateSession(parentSid, "freeze silence parent", agentName = Some("Nebula"))
      childCmds <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- Ref.of[IO, List[AgentCommand]](Nil)
      _ <- freezeDelegate(
        resources,
        system,
        parentSid,
        childSid,
        AgentErrorType.LlmFailed,
        // 载体详情用临时族（upstream-5xx）：本用例钉的是「健康表只延迟、不否决」的
        // fallback 语义，而自动续跑预算闸只放行临时族（配置失效族在
        // auto-resume 用例的控制行 + ProviderRecoveryBudgetSpec 单独钉）——
        // 载体换族后本用例才走到它要验的续跑腿。
        "kimi/k3: 529 upstream overloaded (all candidates exhausted)",
        childCmds,
        parentRef
      )
      // 真实盘面：只有 kimi 被判过死；cmdcode 从未被探过（健康表里根本没有它的条目）。
      _ <- resources.healthMonitor.markDown("kimi", "k3", "529 upstream overloaded")
      serving <- FreezeAlertSweep.providerServing(resources)
      // 判据函数本身（纯函数，缺省容忍窗 10 分钟）：窗内延迟、窗外放行。
      deferredInsideWindow = !FreezeAlertSweep.shouldAttemptResume(serving = false, episodeAgeMs = 60_000L)
      allowedPastWindow = FreezeAlertSweep.shouldAttemptResume(serving = false, episodeAgeMs = 11L * 60_000L)
      allowedWhenServing = FreezeAlertSweep.shouldAttemptResume(serving = true, episodeAgeMs = 0L)
      // 健康表说「没恢复」，但容忍窗过期 ⇒ 必须照常尝试续跑（不许被一个 Down 压死）。
      _ <- IO(sys.props("nebflow.delegate.freeze.autoResumeForceAfterMs") = "0")
      _ <- FreezeAlertSweep.autoResumeTick(resources, None)
      cmds <- childCmds.get
      events <- mailboxEvents(parentSid)
      // 唤醒落地判据：冻结槽被 `onWoken` 清空（live 腿注入 UserInput / 不 live 腿同 id 复活，
      // 两条腿都清槽）。old code 下这里会仍然是冻结态（续跑根本不发生）。
      stillFrozen <- IO(DelegateRegistry.find(childSid).flatMap(_.frozenSince).isDefined)
      _ <- system.stopAll
    yield
      assert(!serving, "one Down entry and no other probe result ⇒ the health table says 'not serving'")
      assert(deferredInsideWindow, "inside the tolerance window the health table still delays the resume")
      assert(allowedPastWindow, "past the window a stale Down entry never blocks the resume")
      assert(allowedWhenServing, "a healthy provider resumes immediately")
      assert(
        !stillFrozen,
        "the frozen delegate must be woken by the sweep even though another model is Down (frozen slot cleared)"
      )
      assert(
        !cmds.exists { case _: AgentCommand.RestartAgent => true; case _ => false },
        "resuming never spends the supervisor restart budget"
      )
      assertEquals(events.map(_.eventType), Nil, s"resuming stays silent, got ${events.map(_.eventType)}")
    end for
  }

  test("real failures still notify: a deterministic defect freezes AND reaches the author as one alert") {
    val system = ActorSystem(s"fsz-8-${scala.util.Random.nextInt(100000)}")
    val tmp = os.temp.dir(prefix = "nb-fsz8-")
    val parentSid = "fsz-parent-8"
    val childSid = "delegate-fsz-real-1"
    for
      resources <- mkResources(system, tmp)
      _ <- resources.sessionStore.getOrCreateSession(parentSid, "freeze silence parent", agentName = Some("Nebula"))
      childCmds <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- Ref.of[IO, List[AgentCommand]](Nil)
      _ <- freezeDelegate(
        resources,
        system,
        parentSid,
        childSid,
        AgentErrorType.Unknown,
        "AssertionError: expected 1 but got 2 (the judge layer rejected the branch)",
        childCmds,
        parentRef
      )
      reason = DelegateRegistry.find(childSid).flatMap(_.frozenReason).getOrElse("")
      _ <- FreezeAlertSweep.alertTick(resources)
      events <- mailboxEvents(parentSid)
      _ <- system.stopAll
    yield
      // 补详批（第二波）：engine-fault 线值携带诊断后缀
      // `engine-fault[trigger=…; errorType=…; <正文>]`——裸码仍是 engine-fault
      // （判等读 `[` 前），后缀逐字保住原始 errorType 与正文。断言比旧等值更强：
      // 裸码等值 + 后缀在场 + 原始 errorType 逐字 + parse 读回 EngineFault。
      assert(reason.startsWith("delegate-frozen:engine-fault["), s"engine-fault wire + diagnostic suffix, got: $reason")
      assert(reason.contains("errorType=Unknown"), s"the raw errorType survives verbatim, got: $reason")
      assert(reason.contains("AssertionError"), s"the defect text rides the audit suffix, got: $reason")
      assertEquals(
        DelegateFreeze.parse(reason),
        Some(DelegateFreeze.Reason.EngineFault),
        "a deterministic defect must NOT be filed under the provider-fault code (suffix is audit-only)"
      )
      val alerts = events.filter(_.eventType == "freeze-alert")
      assertEquals(alerts.size, 1, s"a real failure still notifies (one alert), got ${events.map(_.eventType)}")
      assert(
        alerts.head.payload.contains("deterministic defect"),
        s"the alert says it is a defect, not a provider fault: ${alerts.head.payload.take(300)}"
      )
    end for
  }

  // ── ⑤ flow-notify 闸（纯函数判据）────────────────────────────────────────

  test("flow-notify: an infrastructure-fault node failure is never reported upward, a deterministic one still is") {
    def node(result: String): nebflow.core.project.NodeDef =
      nebflow.core.project.NodeDef(
        id = "n1",
        name = "worker",
        agent = "Nebula",
        status = nebflow.core.project.NodeLifecycle.Failed,
        result = Some(result),
        createdAt = System.currentTimeMillis()
      )
    assert(
      DispatchNotify
        .suppressFailedNotifyForInfraFault(node("provider cmdcode/deepseek: 503 all candidates exhausted"), true),
      "provider failures are infrastructure faults ⇒ no upward report"
    )
    assert(
      DispatchNotify.suppressFailedNotifyForInfraFault(node("insufficient_quota: monthly budget exhausted"), true),
      "billing faults are infrastructure faults ⇒ no upward report"
    )
    assert(
      !DispatchNotify.suppressFailedNotifyForInfraFault(node("AssertionError: expected 1 but got 2"), true),
      "a deterministic defect still reaches the dispatcher"
    )
    assert(
      !DispatchNotify.suppressFailedNotifyForInfraFault(node("provider 503"), false),
      "the kill switch restores the old behaviour"
    )
    // 与 delegate 冻结面共用同一把尺。
    assert(FreezeNotice.infraFault("provider anthropic: 529 overloaded"))
    assert(!FreezeNotice.infraFault("no such file or directory: build.sbt"))
  }

end FreezeSilenceSpec
