package nebflow.agent

import cats.effect.{IO, Ref}
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import io.circe.JsonObject
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, Behavior, Behaviors}
import nebflow.actor.{AgentCommand, AgentEvent, AgentKind, AgentRecord}
import nebflow.core.FileChangeTracker
import nebflow.core.compact.HistoryArchiver
import nebflow.core.mail.CompletionDebtStore
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{BgTaskRegistry, FileLockManager, ShellSession}
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.{Defaults, FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, Message, MessageRole, PathUtil, StreamChunk, ThinkingConfig, SubAgentTask}

import scala.concurrent.duration.*

/**
 * #2141 · 执行轮完成/监督通知验收（作者裁定 2026-11-12，两道补充逐字为契约）：
 *
 *   1. 「complete 只要是在后台没有智能体在运行的情况下，都可以回报。（然后后台有
 *      智能体的情况下，要定期注入检查，比如10。30 60 等这样的时间 退避通知）」
 *      ——单位更正：「是分钟 不是秒」（10/30/60 **分钟**，末档封顶 60min）。
 *   2. 终态 identity 接成 session + 持久稳定 generation（#2178 生产根因：裸
 *      subagentId 身份命中父箱已消费墓碑 ⇒ Mail 续轮第二份终态被吞）。
 *
 * 本 spec 的动态腿全部走**毫秒级注入阶梯**（构造器缝 checkBackoffMs）+ 真实
 * scheduleOnce 到点消息 = 虚拟推进时间轴，不真实等待；分钟级默认由 P0 纯判据
 * 锚死。生产语义：真实进展（等待集缩小）重置阶梯；同代重试同 identity 去重；
 * 取消停止该 generation 检查且不复活；重建（同代债重放）不重合成已交付终态。
 */
class BackoffSupervisorSupervisionCheckSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-supervision-check"
  private val originalRoot = PathUtil.dataRoot

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
    ): fs2.Stream[IO, StreamChunk] =
      fs2.Stream.raiseError[IO](new RuntimeException("fake llm not expected here"))

  /** 记录到达命令 + 墙钟时刻的探针（检查注入的时间轴观测面）。 */
  private def mkTimedProbe(record: Ref[IO, List[(Long, AgentCommand)]]): Behavior[AgentCommand] =
    def loop: Behavior[AgentCommand] =
      Behaviors.receiveMessage: cmd =>
        record.update(_ :+ (System.currentTimeMillis(), cmd)).as(loop)
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
      prompt = "supervision check test",
      description = "supervision check test task",
      status = "running",
      retryCount = 0,
      spawnedAt = System.currentTimeMillis(),
      completedAt = None,
      lastError = None,
      source = "delegate"
    )

  private def waitUntil(timeout: FiniteDuration, every: Long = 20L)(cond: IO[Boolean]): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true  => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then IO.raiseError(new AssertionError("waitUntil: condition not met in time"))
          else IO.sleep(every.millis) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  /** 真进程在册等待任务（无输出型，R5 夹具同款）。 */
  private def registerNeverEndingTask(sessionId: String, jobId: String): IO[Unit] =
    for
      shell <- ShellSession.forSession(sessionId)
      _ <- shell.executeBackground("sleep 3600", jobIdOverride = Some(jobId))
      _ <- BgTaskRegistry.register(jobId, sessionId, s"never-ending $jobId", kind = "local")
    yield ()

  private def cleanupJobs(sessionId: String, jobIds: List[String]): IO[Unit] =
    jobIds
      .traverse { j =>
        ShellSession.peekSession(sessionId).flatMap(_.fold(IO.unit)(s => s.cancelBackgroundJob(j).attempt.void)) *>
          BgTaskRegistry.unregister(j).attempt.void
      }
      .void *>
      ShellSession.destroySession(sessionId).attempt.void *>
      BgTaskRegistry.unregisterSession(Some(sessionId)).attempt.void

  private def terminalMail(parentSid: String): IO[List[nebflow.core.mail.SessionMailbox.Entry]] =
    nebflow.core.mail.SessionMailbox
      .scanPending(parentSid)
      .map(_.filter(_.kind == "delegate-terminal"))
      .handleErrorWith(_ => IO.pure(Nil))

  /** delegate 轨「complete × 已申报」腿前置（R5 夹具同款；未申报 = 零父投递矩阵腿）。 */
  private def seedDeclaredReport(childSid: String, parentSid: String): IO[Unit] =
    IO {
      nebflow.core.delegate.DelegateRegistry.put(
        nebflow.core.delegate.DelegateRegistry.Record(
          id = childSid,
          executor = "nebflow",
          cwd = os.pwd.toString,
          project = None,
          taskId = Some("2141"),
          parentSessionId = parentSid,
          title = s"supervision spec $childSid",
          createdAt = System.currentTimeMillis()
        )
      )
      nebflow.core.delegate.DelegateRegistry.updateReport(childSid, "finish", "turn done; job pending", "") match
        case Left(e) => throw new RuntimeException(s"seed declared report failed: $e")
        case Right(_) => ()
    }

  private def assistantMsg(text: String): Message = Message(MessageRole.Assistant, Left(text))

  private def checkTimes(records: List[(Long, AgentCommand)]): List[Long] =
    records.collect { case (t, e: AgentCommand.ExternalEvent) if e.source == "supervision-check" => t }

  // ══════════════════════════════════════════════════════════════════════
  // 纯判据（零等待）：分钟级默认 + 阶梯/封顶/关闭 + 唤醒回流腿
  // ══════════════════════════════════════════════════════════════════════

  test("P0 defaults are MINUTES (10/30/60min, 60min cap) — seconds defaults are forbidden") {
    assertEquals(Defaults.SupervisionCheckBackoffMs, List(600000L, 1800000L, 3600000L))
    // 相邻间隔语义：第 N 次检查后的间隔 = 第 min(N, 末档) 档（末档封顶重复）
    assertEquals(BackoffSupervisor.supervisionCheckIntervalMs(List(600000L, 1800000L, 3600000L), 0L), Some(600000L))
    assertEquals(BackoffSupervisor.supervisionCheckIntervalMs(List(600000L, 1800000L, 3600000L), 1L), Some(1800000L))
    assertEquals(BackoffSupervisor.supervisionCheckIntervalMs(List(600000L, 1800000L, 3600000L), 2L), Some(3600000L))
    assertEquals(BackoffSupervisor.supervisionCheckIntervalMs(List(600000L, 1800000L, 3600000L), 3L), Some(3600000L), "ladder caps at the last rung (60min)")
    assertEquals(BackoffSupervisor.supervisionCheckIntervalMs(List(600000L, 1800000L, 3600000L), 99L), Some(3600000L))
    assertEquals(BackoffSupervisor.supervisionCheckIntervalMs(Nil, 0L), None, "empty ladder = check leg disabled")
    assertEquals(BackoffSupervisor.supervisionCheckIntervalMs(List(0L, -1L), 0L), None, "non-positive rungs = check leg disabled")
  }

  test("P1 supervision-check wakes the held delegate session back to the supervisor (Completed回流)") {
    assert(
      AgentIdle.wakeContinuationApplies(isFlowNode = false, sessionId = Some("delegate-x"), eventSource = "supervision-check", isDelegateSession = true),
      "supervision-check must re-arm the supervisorRef replyTo or the supervisor is stranded in hold"
    )
    assert(AgentIdle.wakeContinuationApplies(isFlowNode = false, sessionId = Some("delegate-x"), eventSource = "background-task", isDelegateSession = true))
    assert(!AgentIdle.wakeContinuationApplies(isFlowNode = false, sessionId = Some("delegate-x"), eventSource = "supervision-check", isDelegateSession = false))
    assert(!AgentIdle.wakeContinuationApplies(isFlowNode = false, sessionId = Some("delegate-x"), eventSource = "mail", isDelegateSession = true))
  }

  // ══════════════════════════════════════════════════════════════════════
  // D1：子在途抑制最终结算 + 退避阶梯真实注入（300/500/700ms 虚拟时间轴）
  // ══════════════════════════════════════════════════════════════════════

  test("D1: child in-flight suppresses settlement; backoff checks inject at ladder intervals") {
    val system = ActorSystem("supchk-d1")
    val tmp = os.temp.dir()
    val childSid = "delegate-supchk-d1"
    val jobA = "job-supchk-d1"
    val rungs = List(300L, 500L, 700L)
    for
      resources <- mkResources(system, tmp)
      parent <- resources.sessionStore.createSession("Nebula-root", agentName = Some("Nebula"))
      parentSid = parent.id
      _ <- resources.subAgentTaskStore.recordTask(mkTask(parentSid, childSid))
      _ <- seedDeclaredReport(childSid, parentSid)
      _ <- registerNeverEndingTask(childSid, jobA)
      childRec <- Ref.of[IO, List[(Long, AgentCommand)]](Nil)
      childRef <- system.spawn(mkTimedProbe(childRec), "supchk-child-d1")
      supervisor <- system.spawn(
        BackoffSupervisor(
          childRef = childRef,
          childSpawnFn = (_, _) => IO.raiseError(new RuntimeException("respawn must not happen in this spec")),
          childName = childSid,
          parentRef = None,
          description = "supervision d1",
          agentName = "Worker",
          subagentId = childSid,
          parentSessionId = parentSid,
          resources = resources,
          initialPrompt = "hold with a pending job",
          source = "delegate",
          completionCapMs = 60_000L, // 墙钟腿绝不参与
          bgIdleCapMs = 0L, // 空闲腿关闭：检查腿不被遮蔽
          checkBackoffMs = rungs // 构造器缝：毫秒级虚拟时间轴
        ),
        "supervisor-supchk-d1"
      )
      t0 <- IO(System.currentTimeMillis())
      _ <- supervisor ! AgentEvent.Completed(childSid, List(assistantMsg("turn done; job pending")))
      _ <- IO.sleep(1800.millis) // 覆盖 300/800/1500 三个到点；第 4 档在 2200（窗外）
      records <- childRec.get
      mails <- terminalMail(parentSid)
      checks = checkTimes(records)
      gaps = checks match
        case h :: t => (h :: t).sliding(2).map(p => p(1) - p(0)).toList
        case Nil    => Nil
      firstDelay = checks.headOption.map(_ - t0).getOrElse(0L)
      tasks <- resources.subAgentTaskStore.loadTasks(parentSid)
      _ <- cleanupJobs(childSid, List(jobA))
      _ <- system.stopAll
    yield
      assertEquals(checks.length, 3, s"exactly the three ladder checks fired (t=$checks)")
      assert(math.abs(firstDelay - 300L) <= 160L, s"first check ~300ms after hold, got $firstDelay")
      assert(gaps.length == 2 && math.abs(gaps(0) - 500L) <= 160L && math.abs(gaps(1) - 700L) <= 160L, s"ladder gaps must be 500/700, got $gaps")
      assertEquals(mails.size, 0, "final settlement is suppressed while the child has in-flight background work")
      assertEquals(tasks.find(_.taskId == childSid).map(_.status), Some("running"), "the task row stays running — the parent keeps its supervision obligation")
      val injected = records.collect { case (_, e: AgentCommand.ExternalEvent) => e }
      assert(injected.forall(_.metadata("targetSessionId").flatMap(_.asString).contains(childSid)), "every check is traceable to its target session")
  }

  // ══════════════════════════════════════════════════════════════════════
  // D2：真实进展（等待集缩小）重置阶梯回首档
  // ══════════════════════════════════════════════════════════════════════

  test("D2: real progress (wait-set shrink) resets the ladder to the first rung") {
    val system = ActorSystem("supchk-d2")
    val tmp = os.temp.dir()
    val childSid = "delegate-supchk-d2"
    val jobA = "job-supchk-d2a"
    val jobB = "job-supchk-d2b"
    val rungs = List(300L, 500L, 800L)
    for
      resources <- mkResources(system, tmp)
      parent <- resources.sessionStore.createSession("Nebula-root", agentName = Some("Nebula"))
      parentSid = parent.id
      _ <- resources.subAgentTaskStore.recordTask(mkTask(parentSid, childSid))
      _ <- seedDeclaredReport(childSid, parentSid)
      _ <- registerNeverEndingTask(childSid, jobA)
      _ <- registerNeverEndingTask(childSid, jobB)
      childRec <- Ref.of[IO, List[(Long, AgentCommand)]](Nil)
      childRef <- system.spawn(mkTimedProbe(childRec), "supchk-child-d2")
      supervisor <- system.spawn(
        BackoffSupervisor(
          childRef = childRef,
          childSpawnFn = (_, _) => IO.raiseError(new RuntimeException("respawn must not happen in this spec")),
          childName = childSid,
          parentRef = None,
          description = "supervision d2",
          agentName = "Worker",
          subagentId = childSid,
          parentSessionId = parentSid,
          resources = resources,
          initialPrompt = "hold with two pending jobs",
          source = "delegate",
          completionCapMs = 60_000L,
          bgIdleCapMs = 0L,
          checkBackoffMs = rungs
        ),
        "supervisor-supchk-d2"
      )
      _ <- supervisor ! AgentEvent.Completed(childSid, List(assistantMsg("holding two")))
      _ <- IO.sleep(380.millis) // 第一档检查（~300）已注入
      tWake <- IO(System.currentTimeMillis())
      _ <- BgTaskRegistry.unregister(jobA) // 真实进展：等待集 2 → 1
      _ <- supervisor ! AgentEvent.Completed(childSid, List(assistantMsg("one finished, one pending")))
      _ <- IO.sleep(1500.millis) // 覆盖重置后的 ~300 档与再下一档 ~500
      records <- childRec.get
      checks = checkTimes(records)
      gapAfterWake = checks.lift(1).map(_ - tWake).getOrElse(-1L)
      gap3 = checks match
        case a :: b :: c :: _ => c - b
        case _                => -1L
      _ <- cleanupJobs(childSid, List(jobA, jobB))
      _ <- system.stopAll
    yield
      assert(checks.length >= 3, s"three checks expected (t=$checks)")
      assert(math.abs(gapAfterWake - 300L) <= 170L, s"after real progress the NEXT check returns to rung 1 (~300ms), got $gapAfterWake (ladder would be 500)")
      assert(math.abs(gap3 - 500L) <= 190L, s"the ladder then backs off again (~500ms), got $gap3")
  }

  // ══════════════════════════════════════════════════════════════════════
  // D3：显式取消停止该 generation 的检查（不复活）+ 取消终态交付
  // ══════════════════════════════════════════════════════════════════════

  test("D3: explicit cancel stops the supervision checks of this generation and delivers the cancelled terminal") {
    val system = ActorSystem("supchk-d3")
    val tmp = os.temp.dir()
    val childSid = "delegate-supchk-d3"
    val jobA = "job-supchk-d3"
    val rungs = List(300L, 500L, 900L)
    for
      resources <- mkResources(system, tmp)
      parent <- resources.sessionStore.createSession("Nebula-root", agentName = Some("Nebula"))
      parentSid = parent.id
      _ <- resources.subAgentTaskStore.recordTask(mkTask(parentSid, childSid))
      _ <- seedDeclaredReport(childSid, parentSid)
      _ <- registerNeverEndingTask(childSid, jobA)
      childRec <- Ref.of[IO, List[(Long, AgentCommand)]](Nil)
      childRef <- system.spawn(mkTimedProbe(childRec), "supchk-child-d3")
      supervisor <- system.spawn(
        BackoffSupervisor(
          childRef = childRef,
          childSpawnFn = (_, _) => IO.raiseError(new RuntimeException("respawn must not happen in this spec")),
          childName = childSid,
          parentRef = None,
          description = "supervision d3",
          agentName = "Worker",
          subagentId = childSid,
          parentSessionId = parentSid,
          resources = resources,
          initialPrompt = "cancel during hold",
          source = "delegate",
          completionCapMs = 60_000L,
          bgIdleCapMs = 0L,
          checkBackoffMs = rungs
        ),
        "supervisor-supchk-d3"
      )
      _ <- supervisor ! AgentEvent.Completed(childSid, List(assistantMsg("holding")))
      _ <- IO.sleep(420.millis) // 第一档检查（~300）已注入
      tCancel = System.currentTimeMillis()
      _ <- supervisor ! AgentEvent.Cancelled(childSid, "explicit author cancel")
      _ <- waitUntil(10.seconds)(terminalMail(parentSid).map(_.exists(_.event.eventType == "cancelled")))
      _ <- IO.sleep(1200.millis) // 800/1300 两档若未停将在此注入
      records <- childRec.get
      checks = checkTimes(records)
      mails <- terminalMail(parentSid)
      debt <- CompletionDebtStore.pending(childSid)
      _ <- cleanupJobs(childSid, List(jobA))
      _ <- system.stopAll
    yield
      assertEquals(checks.count(_ < tCancel - 60L), 1, "exactly the pre-cancel ladder check fired")
      assert(checks.forall(_ <= tCancel + 60L), s"no check may fire after cancel (t=$checks, cancel=$tCancel) — the cancelled generation must not revive")
      assertEquals(mails.count(_.event.eventType == "cancelled"), 1, "the cancelled terminal is delivered exactly once")
      assertEquals(debt.toOption.flatten.map(_.phase), Some("cancelled"), "cancellation writes the durable debt tombstone")
  }

  // ══════════════════════════════════════════════════════════════════════
  // D4：末子后真正最终汇总只有效结算一次；重建同代稳定（不重合成已交付终态）
  // ══════════════════════════════════════════════════════════════════════

  test("D4: last-child release settles once; a rebuilt supervisor replays the SAME generation without duplicating") {
    val system = ActorSystem("supchk-d4")
    val tmp = os.temp.dir()
    val childSid = "delegate-supchk-d4"
    val jobA = "job-supchk-d4"
    val rungs = List(200L, 400L)
    val msgs = List(assistantMsg("final synthesized report"))
    for
      resources <- mkResources(system, tmp)
      parent <- resources.sessionStore.createSession("Nebula-root", agentName = Some("Nebula"))
      parentSid = parent.id
      _ <- resources.subAgentTaskStore.recordTask(mkTask(parentSid, childSid))
      _ <- seedDeclaredReport(childSid, parentSid)
      _ <- CompletionDebtStore.record(childSid, parentSid, msgs) // 代际在 record 铸造（AgentFinishTurn 同点）
      seededGen <- CompletionDebtStore.pending(childSid).map(_.toOption.flatten.map(_.generation).getOrElse(""))
      _ <- registerNeverEndingTask(childSid, jobA)
      childRec <- Ref.of[IO, List[(Long, AgentCommand)]](Nil)
      childRef <- system.spawn(mkTimedProbe(childRec), "supchk-child-d4")
      supervisor <- system.spawn(
        BackoffSupervisor(
          childRef = childRef,
          childSpawnFn = (_, _) => IO.raiseError(new RuntimeException("respawn must not happen in this spec")),
          childName = childSid,
          parentRef = None,
          description = "supervision d4",
          agentName = "Worker",
          subagentId = childSid,
          parentSessionId = parentSid,
          resources = resources,
          initialPrompt = "release when the job finishes",
          source = "delegate",
          completionCapMs = 60_000L,
          bgIdleCapMs = 30_000L, // 唤醒腿必须先赢（R5 回归同款）
          checkBackoffMs = rungs
        ),
        "supervisor-supchk-d4"
      )
      _ <- supervisor ! AgentEvent.Completed(childSid, msgs)
      _ <- IO.sleep(120.millis) // 200ms 检查档未到即释放
      _ <- BgTaskRegistry.unregister(jobA) // 末子完成
      _ <- supervisor ! AgentEvent.Completed(childSid, msgs) // 真正最终汇总（干净放行）
      _ <- waitUntil(15.seconds)(terminalMail(parentSid).map(_.nonEmpty))
      mailsAfterRelease <- terminalMail(parentSid)
      // 重建：新监督者实例沿**同代**债重放（apply 启动腿）——同 identity ⇒ 不重合成
      rebuild <- system.spawn(
        BackoffSupervisor(
          childRef = childRef,
          childSpawnFn = (_, _) => IO.raiseError(new RuntimeException("respawn must not happen in this spec")),
          childName = childSid,
          parentRef = None,
          description = "supervision d4",
          agentName = "Worker",
          subagentId = childSid,
          parentSessionId = parentSid,
          resources = resources,
          initialPrompt = "release when the job finishes",
          source = "delegate",
          completionCapMs = 60_000L,
          bgIdleCapMs = 30_000L,
          checkBackoffMs = rungs
        ),
        "supervisor-supchk-d4-rebuild"
      )
      // 重放窗口：managed-timer 批（5900fee71）后重建重放的结算腿是异步的——
      // 固定 600ms 点读会抢在结算落账前读盘（D4 首轮即此形态）。改轮询至
      // 「债已 settled」再断言（确定性窗口，不弱化任何断言本体）。
      _ <- waitUntil(15.seconds)(
        CompletionDebtStore.pending(childSid).map(_.toOption.flatten.exists(_.phase == "settled"))
      )
      mailsAfterRebuild <- terminalMail(parentSid)
      debt <- CompletionDebtStore.pending(childSid)
      records <- childRec.get
      _ <- cleanupJobs(childSid, List(jobA))
      _ <- system.stopAll
    yield
      assert(seededGen.nonEmpty, "the durable generation was minted at record time")
      assertEquals(mailsAfterRelease.size, 1, "the final synthesis settles exactly once")
      assert(mailsAfterRelease.head.event.payload.contains("final synthesized report"), "the delivered payload is the real report, not an intermediate summary")
      assert(mailsAfterRelease.head.identity.startsWith(s"$childSid#"), s"terminal identity carries the generation: ${mailsAfterRelease.head.identity}")
      assertEquals(mailsAfterRebuild.size, 1, "the rebuilt supervisor replays the SAME generation — no second copy, no re-synthesis")
      assertEquals(debt.toOption.flatten.map(_.phase), Some("settled"), "the replay confirms and settles the debt via the acceptance path")
      assertEquals(checkTimes(records).length, 0, "a clean release injects no supervision checks")
      assert(rebuild != null)
  }
end BackoffSupervisorSupervisionCheckSpec
