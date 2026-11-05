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
import nebflow.core.delegate.{DelegateFreeze, DelegateJudge, DelegateRegistry, FreezeNotice, ProviderFault}
import nebflow.core.mail.SessionMailbox
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.*

import scala.concurrent.duration.*

/**
 * provider 冻结告警合并窗批（2026-11-05 19:08 作者裁定逐字：「**30分钟才提醒，而且是
 * 有一个合并窗口，把同类型的错误合并后一起汇报，不要分开汇报**」）。
 *
 * 钉死的断言（任务书验收逐条对应）：
 *   ① 阈值是**时间口径**：本轮冻结 ≥ 升级时限（缺省 30 分钟）才提醒；29:59 静默。
 *   ② 归并键 = **错误类型族**（`ProviderFault.familyWire`）：同类型窗内多次冻结合并成
 *      一条；不同类型各一条；窗内新增同类**不另发**（旧键含 id 集 ⇒ 每冻一条发一条）。
 *   ③ 时长实证：< 时限 ⇒ **零通知**；> 时限 ⇒ **一条**。
 *   ④ 滚动 = **tumbling**（锚点 = 上次发出）：同类每窗最多一条，不刷屏、也不永远推迟。
 *   ⑤ `deterministic-defect`（真失败）**不被合并窗吞掉**：即时、单独、按父会话一批一条。
 *   ⑥ 汇报可读：类型 / 影响的会话数 / 时间跨度（首次—末次）/ 已自动恢复 / 仍待人工。
 */
class FreezeAlertWindowSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  private val fakeLlm = new LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("fake llm not expected here"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.raiseError[IO](new RuntimeException("fake llm not expected here"))

  private val originalRoot = PathUtil.dataRoot
  private var base: os.Path = os.pwd / "target" / "test-freeze-alert-window"

  private val props = List(
    "nebflow.delegate.freeze.enabled",
    "nebflow.delegate.freeze.silence",
    "nebflow.delegate.freeze.autoResume",
    "nebflow.delegate.freeze.autoResumeAfterMs",
    "nebflow.delegate.freeze.autoResumeMax",
    "nebflow.delegate.freeze.escalateAfterMs",
    "nebflow.delegate.freeze.digestMergeWindowMs",
    "nebflow.delegate.freeze.digestWindowMs",
    "nebflow.delegate.freeze.alertReArmMs"
  )

  override def beforeAll(): Unit =
    base = os.temp.dir(prefix = "nb-freeze-window-")
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
    try os.remove.all(PathUtil.dataRoot / "delegates")
    catch case _: Throwable => ()

  override def afterEach(context: munit.AfterEach): Unit =
    props.foreach(p => sys.props.remove(p))
    FreezeNotice.resetLedger()
    FreezeAlertSweep.resetForTest()

  // ── harness ────────────────────────────────────────────────────────────────

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
      title = s"freeze alert window spec record $id",
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

  private def alertsOf(parents: List[String]): IO[List[AgentCommand.ExternalEvent]] =
    parents.distinct.traverse(mailboxEvents).map(_.flatten.filter(_.eventType == "freeze-alert"))

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
      parentActor <- system.spawn(mkRecordingChild(parentRef), s"p-$childSid")
      child <- system.spawn(mkRecordingChild(childCmds), s"c-$childSid")
      sup <- system.spawn(
        BackoffSupervisor(
          childRef = child,
          childSpawnFn = (_, _) => system.spawn(mkRecordingChild(childCmds), s"c2-$childSid"),
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
      _ <- IO.sleep(100.millis)
    yield ()

  /** 合成一条 digest 项（纯函数用例；不经磁盘）。 */
  private def mkItem(
    id: String,
    detail: String,
    since: Long,
    now: Long,
    parent: String = "parent-A",
    reason: String = "delegate-frozen:llm-failure",
    tier: FreezeNotice.Tier = FreezeNotice.Tier.Silent
  ): FreezeNotice.Item =
    FreezeNotice.Item(
      delegateId = id,
      parentSessionId = parent,
      title = "t",
      reason = reason,
      detail = detail,
      tier = tier,
      infra = tier == FreezeNotice.Tier.Silent,
      frozenSince = since,
      ageMs = math.max(0L, now - since),
      workspace = "",
      autoResumes = 0,
      autoResumesExhausted = false
    )

  private def stamp(tag: String, msg: String): Unit =
    println(s"[timeline] ${java.time.LocalTime.now()} $tag — $msg")

  // ── ① 归并键：错误类型族（单一真源 = ProviderFault.Table）──────────────────

  test("merge key = one error-type family (reason-code family), never provider+model, never per-delegate") {
    val now = 10_000_000L
    def fam(detail: String, tier: FreezeNotice.Tier = FreezeNotice.Tier.Silent, reason: String = "delegate-frozen:llm-failure") =
      FreezeNotice.familyOfItem(mkItem("x", detail, now, now, reason = reason, tier = tier))
    // provider 可恢复族逐类 → 族线值（判据读 ProviderFault.Table，不另写分类）。
    assertEquals(fam("kimi/k3: HTTP 403 no subscription"), "auth-subscription")
    assertEquals(fam("flysin: insufficient balance — top up"), "quota-balance")
    assertEquals(fam("cmdcode/deepseek-flash: statusCode: 550, response: (empty)"), "upstream-5xx")
    assertEquals(fam("java.net.SocketTimeoutException: connect timed out"), "connect-timeout")
    assertEquals(fam("cmdcode: 400 invalid request — Input too long"), "format-rejected")
    assertEquals(fam("zhipu/glm: 429 rate limit exceeded"), "rate-limited")
    // 真失败 → 确定性缺陷族（不进合并窗）。
    assertEquals(
      fam("tool pipeline crashed: NullPointerException in render step", FreezeNotice.Tier.Escalate, "delegate-frozen:tool-failure"),
      "deterministic-defect"
    )
    // 未命中判据表：原因码兜底（额度族 / 未知形状桶），不并进任何已知族。
    assertEquals(fam("connection to the moon refused? nonsense payload"), "provider-unclassified")
    assertEquals(fam("nonsense payload", reason = "delegate-frozen:cancelled-by-quota"), "quota-balance")
    // 同一 provider + model、两个不同族 ⇒ 两个族（证明不是按 provider+model 归并）。
    assertNotEquals(fam("kimi/k3: 403 no subscription"), fam("kimi/k3: insufficient balance"))
  }

  // ── ② 阈值是时间口径（缺省 30 分钟），不是续跑次数 ─────────────────────────

  test("threshold reads the 30-minute episode age (default), not the auto-resume count") {
    val horizon = Defaults.DelegateFreezeEscalateAfterMs
    assertEquals(horizon, 30L * 60 * 1000, "the default horizon is 30 minutes")
    val now = 10_000_000L
    def planned(items: List[FreezeNotice.Item]) =
      FreezeNotice.planAlerts(FreezeNotice.Digest(now, 0L, items)).map(a => a.family -> a.items.size)

    val young = mkItem("a1", "kimi/k3: 403 no subscription", now - horizon + 1_000L, now)
    assertEquals(planned(List(young)), Nil, "29:59 old ⇒ still silent")

    val due = mkItem("a2", "kimi/k3: 403 no subscription", now - horizon, now)
    assertEquals(planned(List(due)), List("auth-subscription" -> 1), "exactly at the horizon ⇒ one digest")

    // 同类型里一条到时限、一条还没到 ⇒ 仍是**一条**（同一个事件，全部并入）。
    val younger = mkItem("a3", "kimi/k3: 403 no subscription", now - 10_000L, now)
    assertEquals(
      planned(List(due, younger)),
      List("auth-subscription" -> 2),
      "same type joins the SAME digest even if its own age is inside the horizon"
    )
    // 时限内的同类冻结**一条都不发**（全静默）。
    assertEquals(planned(List(young, younger)), Nil, "every freeze inside the horizon ⇒ zero messages")
  }

  // ── ③ 合并窗实证：同类型合并一条，异类型各一条 ───────────────────────────

  test(
    "empirical: 3 same-type freezes (same window) ⇒ exactly ONE message; a 4th from ANOTHER parent joins it; a second type ⇒ a SECOND message"
  ) {
    val system = ActorSystem(s"faw-3-${scala.util.Random.nextInt(100000)}")
    val tmp = os.temp.dir(prefix = "nb-faw3-")
    val parentA = "faw-parent-a"
    val parentB = "faw-parent-b"
    sys.props("nebflow.delegate.freeze.escalateAfterMs") = "300" // 时限压到 300ms（真实 tick，非假时钟）
    for
      resources <- mkResources(system, tmp)
      _ <- resources.sessionStore.getOrCreateSession(parentA, "window parent a", agentName = Some("Nebula"))
      _ <- resources.sessionStore.getOrCreateSession(parentB, "window parent b", agentName = Some("Nebula"))
      childCmds <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- Ref.of[IO, List[AgentCommand]](Nil)
      t0 = System.currentTimeMillis()
      _ <- List("w1", "w2", "w3").traverse_(n =>
        freezeDelegate(
          resources,
          system,
          parentA,
          s"delegate-faw-$n",
          AgentErrorType.LlmFailed,
          "kimi/k3: 403 no subscription",
          childCmds,
          parentRef
        )
      )
      _ <- IO.sleep(350.millis) // 三条都越过 300ms 时限
      _ <- FreezeAlertSweep.alertTick(resources)
      afterFirst <- alertsOf(List(parentA, parentB))
      _ = stamp(
        "tick-1",
        s"3 条 auth-subscription 冻结（同一窗口）⇒ ${afterFirst.size} 条汇报：" +
          afterFirst.map(_.metadata("family").flatMap(_.asString).getOrElse("?")).mkString(",")
      )
      _ <- FreezeAlertSweep.alertTick(resources)
      afterSecond <- alertsOf(List(parentA, parentB))
      // 第 4 条：**另一个父会话**、同一类型、仍在同一窗口内。
      _ <- freezeDelegate(
        resources,
        system,
        parentB,
        "delegate-faw-w4",
        AgentErrorType.LlmFailed,
        "kimi/k3: 403 no subscription",
        childCmds,
        parentRef
      )
      _ <- FreezeAlertSweep.alertTick(resources)
      afterFourth <- alertsOf(List(parentA, parentB))
      windowAfterFourth = FreezeNotice.windowOf("auth-subscription")
      _ = stamp("tick-2", s"第 4 条同类（另一父会话）并入后 ⇒ ${afterFourth.size} 条汇报（不许变成 4 条）")
      // 第二种类型：配额族（另一个族）。
      _ <- freezeDelegate(
        resources,
        system,
        parentA,
        "delegate-faw-q1",
        AgentErrorType.LlmFailed,
        "flysin: insufficient balance — top up to continue",
        childCmds,
        parentRef
      )
      _ <- IO.sleep(350.millis) // 让这条同类新冻结也越过 300ms 时限
      _ <- FreezeAlertSweep.alertTick(resources)
      afterSecondType <- alertsOf(List(parentA, parentB))
      digest = FreezeNotice.fullDigest(DelegateRegistry.list(), System.currentTimeMillis())
      _ <- system.stopAll
    yield
      assertEquals(
        afterFirst.map(_.metadata("family").flatMap(_.asString)),
        List(Some("auth-subscription")),
        s"3 same-type freezes ⇒ exactly ONE report, got ${afterFirst.map(_.payload.take(80))}"
      )
      assertEquals(
        afterFirst.head.metadata("count").flatMap(_.asNumber).flatMap(_.toInt),
        Some(3),
        "the single report carries all 3 freezes"
      )
      assertEquals(afterSecond.size, 1, "the next 30s tick must not repeat the same digest")
      assertEquals(
        afterFourth.size,
        1,
        s"a 4th same-type freeze (other parent) joins the SAME report — the id set must not create a new message, got ${afterFourth.size}"
      )
      assertEquals(
        windowAfterFourth.map(_.affected),
        Some(4),
        "the merge ledger now reads 4 affected sessions for that type (next window's digest carries it)"
      )
      assertEquals(
        afterFourth.head.metadata("affectedSessions").flatMap(_.asNumber).flatMap(_.toInt),
        Some(3),
        "the already-sent report is never retro-edited (its id set was 3 at send time)"
      )
      assertEquals(
        afterSecondType.map(_.metadata("family").flatMap(_.asString)).sorted,
        List(Some("auth-subscription"), Some("quota-balance")).sorted,
        s"a second type ⇒ a SECOND report (different types are never merged), got ${afterSecondType.map(_.metadata("family"))}"
      )
      assertEquals(afterSecondType.size, 2, "two types ⇒ two reports, no more")
      assert(digest.total >= 5, s"all freezes are on the panel: ${digest.total}")
      stamp(
        "final",
        s"同类合并读数：afterFirst=${afterFirst.size}, afterSecondTick=${afterSecond.size}, " +
          s"after4th=${afterFourth.size}, afterSecondType=${afterSecondType.size}（时间线自 t0 起 " +
          s"${System.currentTimeMillis() - t0}ms）"
      )
    end for
  }

  // ── ④ 时长实证：< 时限零通知；> 时限一条 ────────────────────────────────

  test("empirical: a freeze younger than 30 minutes sends ZERO messages; the same freeze past 30 minutes sends exactly ONE") {
    val system = ActorSystem(s"faw-4-${scala.util.Random.nextInt(100000)}")
    val tmp = os.temp.dir(prefix = "nb-faw4-")
    val parentSid = "faw-parent-h"
    val childSid = "delegate-faw-horizon"
    for
      resources <- mkResources(system, tmp)
      _ <- resources.sessionStore.getOrCreateSession(parentSid, "window parent h", agentName = Some("Nebula"))
      childCmds <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- Ref.of[IO, List[AgentCommand]](Nil)
      t0 = System.currentTimeMillis()
      _ <- freezeDelegate(
        resources,
        system,
        parentSid,
        childSid,
        AgentErrorType.LlmFailed,
        "cmdcode/deepseek-flash: statusCode: 550, response: (empty body)",
        childCmds,
        parentRef
      )
      // 缺省时限（30 分钟）下连打三拍：零通知。
      _ <- FreezeAlertSweep.alertTick(resources)
      _ <- IO.sleep(50.millis)
      _ <- FreezeAlertSweep.alertTick(resources)
      _ <- IO.sleep(50.millis)
      _ <- FreezeAlertSweep.alertTick(resources)
      insideAlerts <- alertsOf(List(parentSid))
      insideAge = FreezeNotice.fullDigest(DelegateRegistry.list(), System.currentTimeMillis()).items.map(_.ageMs)
      _ = stamp("inside-horizon", s"三拍后倒数：${insideAlerts.size} 条汇报（冻龄 ${insideAge.mkString(",")} ms，时限 ${Defaults.DelegateFreezeEscalateAfterMs} ms）")
      // 把这条冻结的起点倒推到 31 分钟前（真实缺省参数，不改判据）⇒ 只剩「超过时限」一个变量。
      _ <- IO {
        val rec = DelegateRegistry.find(childSid).get
        DelegateRegistry.put(rec.copy(frozenSince = Some(System.currentTimeMillis() - 31L * 60 * 1000)))
        FreezeNotice.resetLedger() // 轮次台账按新的起点重算（等价于该轮已持续 31 分钟）
      }
      _ <- FreezeAlertSweep.alertTick(resources)
      pastAlerts <- alertsOf(List(parentSid))
      _ = stamp("past-horizon", s"冻龄倒推 31 分钟后 ⇒ ${pastAlerts.size} 条汇报")
      _ <- FreezeAlertSweep.alertTick(resources)
      pastAgain <- alertsOf(List(parentSid))
      _ <- system.stopAll
    yield
      assertEquals(insideAlerts, Nil, s"inside the 30-minute horizon: ZERO messages, got ${insideAlerts.map(_.eventType)}")
      assertEquals(pastAlerts.size, 1, s"past the horizon: exactly ONE message, got ${pastAlerts.size}")
      assertEquals(pastAlerts.head.metadata("family").flatMap(_.asString), Some("upstream-5xx"))
      assert(
        pastAlerts.head.payload.contains("30"),
        s"the digest states the horizon: ${pastAlerts.head.payload.take(200)}"
      )
      assertEquals(pastAgain.size, 1, "the next tick does not repeat it (tumbling window)")
    end for
  }

  // ── ⑤ 滚动：tumbling，同类型每窗最多一条 ────────────────────────────────

  test("rolling: the same type speaks at most once per merge window, and speaks again only after the window elapses (tumbling)") {
    val system = ActorSystem(s"faw-5-${scala.util.Random.nextInt(100000)}")
    val tmp = os.temp.dir(prefix = "nb-faw5-")
    val parentSid = "faw-parent-r"
    sys.props("nebflow.delegate.freeze.escalateAfterMs") = "1"
    sys.props("nebflow.delegate.freeze.digestMergeWindowMs") = "1500"
    for
      resources <- mkResources(system, tmp)
      _ <- resources.sessionStore.getOrCreateSession(parentSid, "window parent r", agentName = Some("Nebula"))
      childCmds <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- Ref.of[IO, List[AgentCommand]](Nil)
      _ <- freezeDelegate(
        resources,
        system,
        parentSid,
        "delegate-faw-roll",
        AgentErrorType.LlmFailed,
        "anthropic: 529 overloaded",
        childCmds,
        parentRef
      )
      _ <- IO.sleep(20.millis)
      // 窗内连打 4 拍 ⇒ 只有第一条。
      _ <- (1 to 4).toList.traverse_(_ => FreezeAlertSweep.alertTick(resources) *> IO.sleep(20.millis))
      within <- alertsOf(List(parentSid))
      _ = stamp("within-window", s"窗内 4 拍 ⇒ ${within.size} 条（窗宽 1500ms）")
      // 窗口过去 ⇒ 同类再一条（滚动，不是永不发）。
      _ <- IO.sleep(1600.millis)
      _ <- FreezeAlertSweep.alertTick(resources)
      afterWindow <- alertsOf(List(parentSid))
      _ = stamp("after-window", s"滚过一窗 ⇒ 累计 ${afterWindow.size} 条")
      _ <- system.stopAll
    yield
      assertEquals(within.size, 1, s"4 ticks inside one window ⇒ exactly ONE report, got ${within.size}")
      assertEquals(afterWindow.size, 2, s"after the window elapses the same type reports once more, got ${afterWindow.size}")
      assertEquals(
        afterWindow.map(_.metadata("family").flatMap(_.asString)).distinct,
        List(Some("upstream-5xx")),
        "the rolling report stays one per type"
      )
    end for
  }

  // ── ⑥ 真失败不被吞 ──────────────────────────────────────────────────────

  test("deterministic-defect still notifies immediately and separately — it is never merged away by the provider window") {
    val system = ActorSystem(s"faw-6-${scala.util.Random.nextInt(100000)}")
    val tmp = os.temp.dir(prefix = "nb-faw6-")
    val parentSid = "faw-parent-d"
    for
      resources <- mkResources(system, tmp)
      _ <- resources.sessionStore.getOrCreateSession(parentSid, "window parent d", agentName = Some("Nebula"))
      childCmds <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- Ref.of[IO, List[AgentCommand]](Nil)
      // 缺省 30 分钟时限下：provider 冻结静默，确定性缺陷**立刻**通知。
      _ <- freezeDelegate(
        resources,
        system,
        parentSid,
        "delegate-faw-def-provider",
        AgentErrorType.LlmFailed,
        "kimi/k3: 403 no subscription",
        childCmds,
        parentRef
      )
      _ <- freezeDelegate(
        resources,
        system,
        parentSid,
        "delegate-faw-def-bug",
        AgentErrorType.ToolFailed,
        "tool pipeline crashed: NullPointerException in render step",
        childCmds,
        parentRef
      )
      _ <- FreezeAlertSweep.alertTick(resources)
      firstPass <- alertsOf(List(parentSid))
      _ = stamp(
        "defect-immediate",
        s"时限内：provider 冻结静默，确定性缺陷 ⇒ ${firstPass.size} 条（family=${firstPass.map(_.metadata("family").flatMap(_.asString)).mkString(",")}）"
      )
      // 再让 provider 那一族也过时限 ⇒ 额外一条类型摘要，缺陷不重发、也不被吞掉。
      _ <- IO(sys.props("nebflow.delegate.freeze.escalateAfterMs") = "1")
      _ <- IO.sleep(10.millis)
      _ <- FreezeAlertSweep.alertTick(resources)
      secondPass <- alertsOf(List(parentSid))
      _ = stamp("both-legs", s"两腿齐发 ⇒ 累计 ${secondPass.size} 条")
      _ <- system.stopAll
    yield
      assertEquals(
        firstPass.map(_.metadata("family").flatMap(_.asString)),
        List(Some("deterministic-defect")),
        s"inside the horizon only the defect speaks (immediately), got ${firstPass.map(_.metadata("family"))}"
      )
      assert(
        firstPass.head.payload.contains("deterministic defect"),
        s"the defect alert says it is a defect: ${firstPass.head.payload.take(200)}"
      )
      assertEquals(
        secondPass.map(_.metadata("family").flatMap(_.asString)).sorted,
        List(Some("auth-subscription"), Some("deterministic-defect")).sorted,
        s"the provider family digest comes later as its OWN message; the defect is not swallowed, got ${secondPass.map(_.metadata("family"))}"
      )
      assertEquals(secondPass.size, 2, "two families ⇒ two messages, the defect one was not re-sent")
    end for
  }

  // ── ⑦ 汇报内容：类型 / 会话数 / 跨度 / 已恢复 / 仍待人工 ─────────────────

  test("the digest reads type + affected sessions + span + auto-recovered + still-needs-you (sample below)") {
    val system = ActorSystem(s"faw-7-${scala.util.Random.nextInt(100000)}")
    val tmp = os.temp.dir(prefix = "nb-faw7-")
    val parentSid = "faw-parent-s"
    sys.props("nebflow.delegate.freeze.escalateAfterMs") = "1200"
    for
      resources <- mkResources(system, tmp)
      _ <- resources.sessionStore.getOrCreateSession(parentSid, "window parent s", agentName = Some("Nebula"))
      childCmds <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- Ref.of[IO, List[AgentCommand]](Nil)
      t0 = System.currentTimeMillis()
      _ <- freezeDelegate(
        resources,
        system,
        parentSid,
        "delegate-faw-span-a",
        AgentErrorType.LlmFailed,
        "cmdcode/deepseek-flash: statusCode: 550, response: (empty body)",
        childCmds,
        parentRef
      )
      _ <- IO.sleep(150.millis)
      _ <- freezeDelegate(
        resources,
        system,
        parentSid,
        "delegate-faw-span-b",
        AgentErrorType.LlmFailed,
        "anthropic: 529 overloaded",
        childCmds,
        parentRef
      )
      // 第一拍：并入台账（尚未过时限 ⇒ 零通知）。
      _ <- FreezeAlertSweep.alertTick(resources)
      tracked <- alertsOf(List(parentSid))
      _ = stamp("tracked", s"两条 upstream-5xx 进台账，${tracked.size} 条汇报（时限 1200ms 未到）")
      // 其中一条被引擎自动续跑救回来了（清冻结槽 = 恢复的观测事实）。
      unfrozen <- IO(DelegateRegistry.clearFrozen("delegate-faw-span-b"))
      stillFrozenB <- IO(DelegateRegistry.isFrozen("delegate-faw-span-b"))
      _ = stamp("recovered-b", s"clearFrozen=$unfrozen isFrozen=$stillFrozenB")
      _ <- IO.sleep(1200.millis)
      bStillFrozen <- IO(DelegateRegistry.isFrozen("delegate-faw-span-b"))
      _ = stamp("pre-emit", s"isFrozen(b)=$bStillFrozen")
      winPre = FreezeNotice.windowOf("upstream-5xx")
      itemsPre = FreezeNotice.fullDigest(DelegateRegistry.list(), System.currentTimeMillis()).items.map(_.delegateId)
      _ = stamp("pre-emit-window", s"window=$winPre items=$itemsPre")
      _ <- FreezeAlertSweep.alertTick(resources)
      out <- alertsOf(List(parentSid))
      _ <- system.stopAll
    yield
      assertEquals(tracked, Nil, "inside the horizon nothing is reported")
      assertEquals(unfrozen.isRight, true, s"the recovery fixture must land: $unfrozen")
      assertEquals(stillFrozenB, false, "the recovered delegate is no longer frozen")
      assertEquals(out.size, 1, s"one type ⇒ one digest, got ${out.size}")
      val a = out.head
      assertEquals(a.metadata("family").flatMap(_.asString), Some("upstream-5xx"))
      assertEquals(a.metadata("affectedSessions").flatMap(_.asNumber).flatMap(_.toInt), Some(2), "2 sessions affected")
      assertEquals(a.metadata("stillFrozen").flatMap(_.asNumber).flatMap(_.toInt), Some(1), "1 still frozen")
      assertEquals(a.metadata("autoRecovered").flatMap(_.asNumber).flatMap(_.toInt), Some(1), "1 auto-recovered")
      assert(a.metadata("spanMs").flatMap(_.asNumber).flatMap(_.toLong).exists(_ >= 100L), s"span is read: ${a.metadata}")
      assertEquals(a.metadata("needsHuman").flatMap(_.asBoolean), Some(true))
      List("类型", "影响的会话数", "时间跨度", "已自动恢复", "仍待人工").foreach { k =>
        assert(a.payload.contains(k), s"the digest must read '$k': ${a.payload.take(400)}")
      }
      println("──── 示例摘要文本（原样，即作者收到的唯一一条）────")
      println(a.payload)
      println("──── 示例摘要文本结束 ────")
    end for
  }

  // ── ⑧ 示例摘要（**生产缺省参数**原样渲染：30 分钟时限 / 30 分钟合并窗）──────────

  test("sample digest with production defaults (30-minute horizon, 30-minute merge window)") {
    val now = 1_800_000_000_000L
    val min = 60_000L
    val a = mkItem(
      "delegate-7f3a",
      "cmdcode/deepseek-flash: statusCode: 550, response: (empty body)",
      now - 32 * min,
      now
    )
    val b = mkItem("delegate-b91c", "anthropic/claude: 529 overloaded — all candidates exhausted", now - 7 * min, now)
    val old = now - 32 * min
    FreezeNotice.resetLedger()
    FreezeNotice.trackWave(List(a, b), now - 5 * min)
    FreezeNotice.trackWave(List(a), now) // b 在本窗内被自动续跑救回来了
    val alerts = FreezeNotice.planAlerts(FreezeNotice.Digest(now, 0L, List(a)))
    assertEquals(alerts.size, 1, "one type ⇒ one digest")
    assertEquals(alerts.head.family, "upstream-5xx")
    assertEquals(alerts.head.rearmMs, 30L * min, "the merge window is the 30-minute default")
    val text = FreezeNotice.alertText(alerts.head)
    assert(text.contains("30 分钟合并窗内只发这一条"), s"the text states the merge window: ${text.take(400)}")
    assert(text.contains("仍待人工：1"), s"still-needs-you is read: ${text.take(400)}")
    assert(text.contains("已自动恢复：1"), s"auto-recovered is read: ${text.take(400)}")
    assert(text.contains("时间跨度"), "the span is read")
    assert(text.contains("超过 30 分钟时限才汇报"), "the horizon prints as the real 30-minute default")
    println("──── 示例摘要文本（生产缺省参数）────")
    println(text)
    println("──── 示例摘要文本结束 ────")
    // 分桶不串味：a/b 是同一族的两次冻结，但旧账没被 reset 掉时 firstFrozenAt 仍应取最早。
    assert(FreezeNotice.windowOf("upstream-5xx").exists(_.firstFrozenAt <= old + 1), "the window keeps the earliest freeze")
  }

end FreezeAlertWindowSpec
