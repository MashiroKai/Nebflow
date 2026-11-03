package nebflow.core.delegate

import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, AgentCommand, AgentKind, AgentRecord, AgentStatus, Behavior, Behaviors}
import nebflow.agent.{AgentLibrary, SharedResources}
import nebflow.core.{FileChangeTracker, RateLimiter, SessionStore}
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk, ThinkingConfig}

import scala.concurrent.duration.*

/**
 * 待命看护**应答消费 + 退避推进 + 次数封顶**批（2026-11-03 · 作者报障逐字
 * 「然后 standby 这个是不是有问题，怎么报了 standby 还一直通知让 standby 呢」）。
 *
 * 实证病灶（delegate-nebflow-1e759bae 单会话）：[delegate-standby] 提醒 **49 条、
 * 全部写着 `Reminder #1`**，间隔 20-50s——旧 `arm()` 每次停靠都「取消旧 fiber +
 * 从第 1 档重开序列」。看护提醒唤醒会话 → 会话重申 standby → 回合收尾又命中
 * Standby 腿 → arm() 把序列从头再来 ⇒ 退避序永远停在首档（10s），短档/长档/
 * 封顶全部作废：**应答不被消费、退避不推进**。
 *
 * 本 spec 用毫秒级档序（prop 覆写）跑真 caretaker + 真 actor ref，断言：
 *   ① 纯函数决策表 [[DelegateStandbyCare.armDecision]]（判红锚）；
 *   ② 「应答后不再重复提醒」：每次收到提醒即重申 standby（重停靠 + 再 arm）⇒
 *      提醒**按 100→200→300→400ms 的档序递进**，绝不在首档原地复读
 *      （改前红：每次重停靠都从第 1 档重开 ⇒ 间隔恒 ≈100ms，且条数远超封顶）；
 *   ③ 「提醒次数 ≤ 封顶」：总条数 = delays.size + longMax = 4，穷尽后**静默**
 *      （继续重申也不再提醒——本期待命期回纯事件驱动等待）。
 */
class DelegateStandbyCareAnswerSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-standby-answer"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    os.remove.all(tempRoot / "delegates")
    DelegateStandbyCare.clearAllForSpec()

  override def afterEach(context: munit.AfterEach): Unit =
    DelegateStandbyCare.clearAllForSpec()
    Seq(
      "nebflow.delegate.standby.nudgeDelaysMs",
      "nebflow.delegate.standby.nudgeLongMs",
      "nebflow.delegate.standby.nudgeLongMax"
    ).foreach(sys.props.remove)

  private def fakeLlm: LlmHandle[IO] = new LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("not expected"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.raiseError[IO](new RuntimeException("not expected"))

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
      voiceMutedRef = voiceMuted
    )

  /** 记账型子会话 ref：把注入的 standby 提醒按到达时刻落表。 */
  private def nudgeRecorder(
    system: ActorSystem,
    name: String,
    received: Ref[IO, List[(Long, String)]]
  ): IO[nebflow.actor.ActorRef[AgentCommand]] =
    def loop: Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand] {
        case ui: AgentCommand.UserInput if ui.source.contains("standby-care") =>
          IO.realTime.map(_.toMillis).flatMap(at => received.update(_ :+ (at, ui.text))).as(loop)
        case _ => IO.pure(loop)
      }
    system.spawn(loop, name)

  private def supervisorRef(system: ActorSystem): IO[nebflow.actor.ActorRef[nebflow.actor.AgentEvent]] =
    def loop: Behavior[nebflow.actor.AgentEvent] = Behaviors.receiveMessage[nebflow.actor.AgentEvent](_ => IO.pure(loop))
    system.spawn(loop, "standby-supervisor")

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 10.millis)(cond: IO[Boolean]): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError(s"waitUntil: condition not met within $timeout"))
          else IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  private def seedRecord(sid: String): Unit =
    DelegateRegistry.put(
      DelegateRegistry.Record(
        id = sid,
        executor = "nebflow",
        cwd = tempRoot.toString,
        project = None,
        taskId = None,
        parentSessionId = "spec-root",
        title = "standby backoff spec",
        createdAt = 1L
      )
    )

  // ── ① 决策表（纯函数，判红锚）────────────────────────────────────────

  test("① armDecision: 新期待命 StartFresh；同期待命重申 = 应答消费（不重启）；fiber 已死 ⇒ 续档；穷尽 ⇒ 静默") {
    import DelegateStandbyCare.{ArmDecision, CareState, armDecision}
    assertEquals(armDecision(None, fiberLive = false, total = 6), ArmDecision.StartFresh)
    assertEquals(
      armDecision(Some(CareState(attempt = 2)), fiberLive = true, total = 6),
      ArmDecision.AnswerConsumed,
      "🔴 同期待命期内重申 standby = 应答：fiber 在跑 ⇒ 零动作（退避由在跑 caretaker 推进）"
    )
    assertEquals(
      armDecision(Some(CareState(attempt = 2)), fiberLive = false, total = 6),
      ArmDecision.ResumeAt(3),
      "fiber 已自死 ⇒ 从下一档续跑（绝不回第一档）"
    )
    assertEquals(
      armDecision(Some(CareState(attempt = 6, exhausted = true)), fiberLive = false, total = 6),
      ArmDecision.StaySilent,
      "🔴 序列穷尽 ⇒ 本期待命期静默（提醒次数封顶的机械防线）"
    )
    assertEquals(
      armDecision(Some(CareState(attempt = 6)), fiberLive = false, total = 6),
      ArmDecision.StaySilent,
      "已发满 total 档 ⇒ 同样静默（防御）"
    )
    assertEquals(DelegateStandbyCare.sequenceTotal, 6, "缺省档 = 10s/30s/60s + 10min×3 = 6 条封顶")
  }

  // ── ②③ 真 caretaker：应答消费 + 退避递进 + 封顶 ──────────────────────

  test("② 重申 standby 视为应答 ⇒ 提醒按档序递进而非首档复读；③ 条数封顶后静默（改前红：恒 Reminder #1 且远超封顶）") {
    sys.props("nebflow.delegate.standby.nudgeDelaysMs") = "100,200,300"
    sys.props("nebflow.delegate.standby.nudgeLongMs") = "400"
    sys.props("nebflow.delegate.standby.nudgeLongMax") = "1"
    val sid = "delegate-nebflow-standby-answer"
    val system = ActorSystem(s"standby-answer-${scala.util.Random.nextInt(100000)}")
    val program = for
      resources <- mkResources(system, os.temp.dir())
      received <- Ref.of[IO, List[(Long, String)]](Nil)
      child <- nudgeRecorder(system, "standby-child", received)
      supervisor <- supervisorRef(system)
      _ <- IO(seedRecord(sid))
      _ <- resources.agentRegistry.update(
        _ + (sid -> AgentRecord(sid, child, AgentKind.Delegate, "spec-root", None, status = AgentStatus.Waiting))
      )
      _ <- IO(DelegateRegistry.parkStandby(sid)) // 停靠（Declared 腿的判据：standbySince 在场）
      _ <- DelegateStandbyCare.arm(sid, child, supervisor, resources, DelegateStandbyCare.Declared)
      // 「会话收到提醒 → 重申 standby → 回合收尾再次 arm」的循环：跑 2.4s
      // （缺省 4 档累计 1000ms，留足余量观察穷尽后的静默）。
      _ <- {
        def answersLoop(deadline: Long, seen: Int): IO[Unit] =
          IO.realTime.map(_.toMillis).flatMap { now =>
            if now >= deadline then IO.unit
            else
              received.get.flatMap { rows =>
                if rows.size > seen then
                  // 重申 standby：重停靠 + 再 arm（BackoffSupervisor Standby 腿的同形调用）
                  IO(DelegateRegistry.parkStandby(sid)) *>
                    DelegateStandbyCare.arm(sid, child, supervisor, resources, DelegateStandbyCare.Declared) *>
                    IO.sleep(10.millis) *> answersLoop(deadline, rows.size)
                else IO.sleep(10.millis) *> answersLoop(deadline, seen)
              }
          }
        IO.realTime.map(_.toMillis).flatMap(start => answersLoop(start + 2400L, 0))
      }
      rows <- received.get
      state = DelegateStandbyCare.careStateOf(sid)
      live = DelegateStandbyCare.caretakerLive(sid)
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield (rows, state, live)
    program.map { case (rows, state, live) =>
      val gaps = rows.map(_._1).sliding(2).collect { case List(a, b) => b - a }.toList
      assertEquals(rows.size, 4, s"🔴 提醒次数 = 封顶 4（delays 3 档 + longMax 1）——实际条数 ${rows.size}，gaps=$gaps")
      assert(gaps.nonEmpty && gaps.forall(_ >= 90L), s"🔴 应答后不再重复提醒：间隔须 ≥ 首档，实际 gaps=$gaps")
      assert(gaps.size == 3, s"4 条提醒应有 3 个间隔（依次对应第 2/3/4 档），实际 gaps=$gaps")
      assert(gaps(0) >= 180L, s"🔴 答后第 2 档 ≥200ms（退避推进，非首档复读），实际 $gaps")
      assert(gaps(1) >= 280L, s"第 3 档 ≥300ms，实际 $gaps")
      assert(gaps(2) >= 380L, s"第 4 档 = 长档 400ms，实际 $gaps")
      assert(gaps.forall(_ >= 180L), s"🔴 任一间隔不得回落到首档 100ms（旧病灶：恒 Reminder #1），实际 $gaps")
      assertEquals(state.map(_.attempt), Some(4), "记账面：已发 4 档")
      assertEquals(state.map(_.exhausted), Some(true), "记账面：序列穷尽")
      assert(state.exists(_.answers >= 3), s"应答面：至少 3 次重申被当应答消费（实际 ${state.map(_.answers)}）")
      assertEquals(live, false, "穷尽后 caretaker 退出 ⇒ 本期待命期静默（重申不再武装新序列）")
    }
  }

  test("③ 序列穷尽后再重申 standby：零新提醒（本期待命期静默，只保留终态判定）") {
    sys.props("nebflow.delegate.standby.nudgeDelaysMs") = "60"
    sys.props("nebflow.delegate.standby.nudgeLongMs") = "80"
    sys.props("nebflow.delegate.standby.nudgeLongMax") = "0"
    val sid = "delegate-nebflow-standby-silent"
    val system = ActorSystem(s"standby-silent-${scala.util.Random.nextInt(100000)}")
    for
      resources <- mkResources(system, os.temp.dir())
      received <- Ref.of[IO, List[(Long, String)]](Nil)
      child <- nudgeRecorder(system, "standby-child-silent", received)
      supervisor <- supervisorRef(system)
      _ <- IO(seedRecord(sid))
      _ <- resources.agentRegistry.update(
        _ + (sid -> AgentRecord(sid, child, AgentKind.Delegate, "spec-root", None, status = AgentStatus.Waiting))
      )
      _ <- IO(DelegateRegistry.parkStandby(sid))
      _ <- DelegateStandbyCare.arm(sid, child, supervisor, resources, DelegateStandbyCare.Declared)
      _ <- waitUntil(5.seconds)(received.get.map(_.nonEmpty))
      firstCount <- received.get.map(_.size)
      // 穷尽后连续重申 5 次（每次重停靠 + arm）
      _ <- (1 to 5).toList.traverse_ { _ =>
        IO(DelegateRegistry.parkStandby(sid)) *>
          DelegateStandbyCare.arm(sid, child, supervisor, resources, DelegateStandbyCare.Declared) *>
          IO.sleep(120.millis)
      }
      after <- received.get.map(_.size)
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(firstCount, 1, "短档 1 档 + longMax 0 ⇒ 首轮恰一条提醒")
      assertEquals(after, firstCount, "🔴 穷尽后重申 standby 不再产生任何提醒（静默封顶）")
  }

  // ── ④ 换腿不漏（引擎会话治理专项 · 缺陷三同族漏网，2026-11-03）──────────
  //
  // 912335af7 的应答消费修好了「同一待命期内的重申」，但记账 [[DelegateStandbyCare.CareState]]
  // 只按 delegateId 存、**不分提醒型**（Declared / IdleNudge）。矩阵两条腿是串行换乘的：
  // 「回合结束未申报」先吃 IdleNudge 腿的整条退避序（穷尽 ⇒ exhausted=true），之后模型
  // 补报 standby 换成 Declared 腿——arm() 读到**上一条腿的** exhausted ⇒ StaySilent ⇒
  // 申报后的待命期零提醒（看护被静默关闭，且与「应答即消费」的封顶语义混淆）。
  // 修法：记账带 kind；换腿 = 新待命期 ⇒ StartFresh（作废旧腿的 attempt/exhausted）。
  test("④ 换提醒型 = 新待命期：IdleNudge 序列穷尽后申报 standby，Declared 腿必须重新武装（改前红：沿用旧腿 exhausted ⇒ 零提醒）") {
    sys.props("nebflow.delegate.standby.nudgeDelaysMs") = "60"
    sys.props("nebflow.delegate.standby.nudgeLongMs") = "80"
    sys.props("nebflow.delegate.standby.nudgeLongMax") = "0"
    val sid = "delegate-nebflow-standby-kindflip"
    val system = ActorSystem(s"standby-kindflip-${scala.util.Random.nextInt(100000)}")
    for
      resources <- mkResources(system, os.temp.dir())
      received <- Ref.of[IO, List[(Long, String)]](Nil)
      child <- nudgeRecorder(system, "standby-child-kindflip", received)
      supervisor <- supervisorRef(system)
      _ <- IO(seedRecord(sid))
      _ <- resources.agentRegistry.update(
        _ + (sid -> AgentRecord(sid, child, AgentKind.Delegate, "spec-root", None, status = AgentStatus.Waiting))
      )
      // 第一腿：回合结束未申报 ⇒ IdleNudge 看护（1 档穷尽）
      _ <- DelegateStandbyCare.arm(sid, child, supervisor, resources, DelegateStandbyCare.IdleNudge)
      _ <- waitUntil(5.seconds)(received.get.map(_.nonEmpty))
      idleState <- IO(DelegateStandbyCare.careStateOf(sid))
      _ = assert(idleState.exists(_.exhausted), s"fixture sanity: the idle leg must be exhausted, state=$idleState")
      before <- received.get.map(_.size)
      // 换腿：模型补报 standby ⇒ Declared 看护（新待命期）
      _ <- IO(DelegateRegistry.parkStandby(sid))
      _ <- DelegateStandbyCare.arm(sid, child, supervisor, resources, DelegateStandbyCare.Declared)
      _ <- waitUntil(3.seconds)(received.get.map(_.size > before))
      after <- received.get.map(_.size)
      state = DelegateStandbyCare.careStateOf(sid)
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assert(
        after > before,
        s"🔴 换腿必须重新武装（改前红：旧腿 exhausted 沿用 ⇒ 申报 standby 后零提醒），before=$before after=$after state=$state"
      )
      assertEquals(state.map(_.kind), Some(DelegateStandbyCare.KindDeclared), "记账面：当前腿 = declared")
  }

end DelegateStandbyCareAnswerSpec
