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
 * 待命看护**矩阵收口批**（2026-11-03 18:14 作者裁定逐字：「我们有四个状态 idle、
 * stand by、merge、finish。只有在 idle 状态下，进入等待（complete），才会有 system
 * reminder 通知。它都已经报了 stand by 了，还通知什么？所以这里的四个状态 idle
 * complete 通知，stand by/merge complete 不通知 finish complete 回报给主 agent。」）
 *
 * 本 spec 覆盖三条被本裁定重写的看护判据（毫秒级档序 prop 覆写跑真 caretaker）：
 *
 *   ① 纯函数决策表 [[DelegateStandbyCare.armDecision]]：新期待命 StartFresh /
 *      同期待命重申 = 应答消费 / fiber 已死续档 / 穷尽静默，**且换型
 *      （Declared ↔ IdleNudge）不得把退避序重置回第 1 档**（18:14 裁定的退避判据
 *      ——旧「换型 ⇒ StartFresh」会把分钟级连戳重新点起来）；
 *   ② 未申报腿（idle×complete，唯一还在提醒的腿）按档序递进、次数封顶后静默；
 *   ③ **申报待命腿零提醒**（stand by×complete ⇒ silent queue）：arm(kind=Declared)
 *      后无 caretaker、无记账、零注入；
 *   ④ **应答下限** [[DelegateStandbyCare.effectiveDelay]]：答复过（answers>0）或第
 *      2 档起的间隔 ≥ floor（缺省 10min），绝无「答了无变化还 1 分钟连戳」。
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
      "nebflow.delegate.standby.nudgeLongMax",
      "nebflow.delegate.standby.answerFloorMs"
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
    def loop: Behavior[nebflow.actor.AgentEvent] =
      Behaviors.receiveMessage[nebflow.actor.AgentEvent](_ => IO.pure(loop))
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

  test("① armDecision: 新期待命 StartFresh；同期待命重申 = 应答消费；fiber 已死 ⇒ 续档；穷尽 ⇒ 静默；换型不回首档") {
    import DelegateStandbyCare.{ArmDecision, CareState, armDecision}
    assertEquals(armDecision(None, fiberLive = false, total = 6), ArmDecision.StartFresh)
    assertEquals(
      armDecision(Some(CareState(attempt = 2)), fiberLive = true, total = 6),
      ArmDecision.AnswerConsumed,
      "🔴 同期待命期内重申 = 应答：fiber 在跑 ⇒ 零动作（退避由在跑 caretaker 推进）"
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
    // 18:14 裁定的退避判据：换型（IdleNudge ↔ Declared）不再重置回首档。
    assertEquals(
      armDecision(
        Some(CareState(attempt = 2, kind = DelegateStandbyCare.KindIdleNudge)),
        fiberLive = false,
        total = 6,
        kind = DelegateStandbyCare.KindDeclared
      ),
      ArmDecision.ResumeAt(3),
      "🔴 换型 ≠ 新待命期起点：单调续档（改前红：换型 ⇒ StartFresh ⇒ 退避序回第 1 档 ⇒ 分钟级连戳）"
    )
    assertEquals(
      armDecision(
        Some(CareState(attempt = 3, kind = DelegateStandbyCare.KindDeclared)),
        fiberLive = false,
        total = 6,
        kind = DelegateStandbyCare.KindIdleNudge
      ),
      ArmDecision.ResumeAt(4),
      "反向换型同样单调续档"
    )
    assertEquals(DelegateStandbyCare.sequenceTotal, 6, "缺省档 = 10s/30s/60s + 10min×3 = 6 条封顶")
  }

  // ── ②③ 真 caretaker（唯一在提醒的腿 = 未申报 idle）────────────────────

  test("② 未申报腿重申（arm 同型再调用）= 应答消费 ⇒ 提醒按档序递进、③ 条数封顶后静默") {
    sys.props("nebflow.delegate.standby.nudgeDelaysMs") = "100,200,300"
    sys.props("nebflow.delegate.standby.nudgeLongMs") = "400"
    sys.props("nebflow.delegate.standby.nudgeLongMax") = "1"
    sys.props("nebflow.delegate.standby.answerFloorMs") = "0"
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
      _ <- DelegateStandbyCare.arm(sid, child, supervisor, resources, DelegateStandbyCare.IdleNudge)
      // 「会话收到提醒 → 回合收尾仍未申报 → 再次 arm」的循环：跑 2.4s
      // （缺省 4 档累计 1000ms，留足余量观察穷尽后的静默）。
      _ <-
        def answersLoop(deadline: Long, seen: Int): IO[Unit] =
          IO.realTime.map(_.toMillis).flatMap { now =>
            if now >= deadline then IO.unit
            else
              received.get.flatMap { rows =>
                if rows.size > seen then
                  DelegateStandbyCare.arm(sid, child, supervisor, resources, DelegateStandbyCare.IdleNudge) *>
                    IO.sleep(10.millis) *> answersLoop(deadline, rows.size)
                else IO.sleep(10.millis) *> answersLoop(deadline, seen)
              }
          }
        IO.realTime.map(_.toMillis).flatMap(start => answersLoop(start + 2400L, 0))
      rows <- received.get
      state = DelegateStandbyCare.careStateOf(sid)
      live = DelegateStandbyCare.caretakerLive(sid)
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield (rows, state, live)
    program.map { case (rows, state, live) =>
      val gaps = rows.map(_._1).sliding(2).collect { case List(a, b) => b - a }.toList
      println(s"[standby-answer-spec] rows=${rows.size} gaps=$gaps state=$state live=$live")
      assertEquals(rows.size, 4, s"🔴 提醒次数 = 封顶 4（delays 3 档 + longMax 1）——实际条数 ${rows.size}，gaps=$gaps")
      assert(gaps.size == 3, s"4 条提醒应有 3 个间隔（依次对应第 2/3/4 档），实际 gaps=$gaps")
      assert(gaps(0) >= 180L, s"🔴 答后第 2 档 ≥200ms（退避推进，非首档复读），实际 $gaps")
      assert(gaps(1) >= 280L, s"第 3 档 ≥300ms，实际 $gaps")
      assert(gaps(2) >= 380L, s"第 4 档 = 长档 400ms，实际 $gaps")
      assertEquals(state.map(_.attempt), Some(4), "记账面：已发 4 档")
      assertEquals(state.map(_.exhausted), Some(true), "记账面：序列穷尽")
      assert(state.exists(_.answers >= 3), s"应答面：至少 3 次重申被当应答消费（实际 ${state.map(_.answers)}）")
      assertEquals(live, false, "穷尽后 caretaker 退出 ⇒ 本期待命期静默（重申不再武装新序列）")
    }
  }

  test("③ 序列穷尽后再重申：零新提醒（本期待命期静默，只保留终态判定）") {
    sys.props("nebflow.delegate.standby.nudgeDelaysMs") = "60"
    sys.props("nebflow.delegate.standby.nudgeLongMs") = "80"
    sys.props("nebflow.delegate.standby.nudgeLongMax") = "0"
    sys.props("nebflow.delegate.standby.answerFloorMs") = "0"
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
      _ <- DelegateStandbyCare.arm(sid, child, supervisor, resources, DelegateStandbyCare.IdleNudge)
      _ <- waitUntil(5.seconds)(received.get.map(_.nonEmpty))
      firstCount <- received.get.map(_.size)
      // 穷尽后连续重申 5 次
      _ <- (1 to 5).toList.traverse_ { _ =>
        DelegateStandbyCare.arm(sid, child, supervisor, resources, DelegateStandbyCare.IdleNudge) *>
          IO.sleep(120.millis)
      }
      after <- received.get.map(_.size)
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(firstCount, 1, "短档 1 档 + longMax 0 ⇒ 首轮恰一条提醒")
      assertEquals(after, firstCount, "🔴 穷尽后重申不再产生任何提醒（静默封顶）")
    end for
  }

  // ── ④ 申报待命腿：零提醒（18:14 裁定 stand by×complete 不通知）────────

  test("④ 申报 standby ⇒ 零 caretaker / 零记账 / 零注入（stand by×complete = silent queue）") {
    sys.props("nebflow.delegate.standby.nudgeDelaysMs") = "60"
    sys.props("nebflow.delegate.standby.nudgeLongMs") = "80"
    sys.props("nebflow.delegate.standby.nudgeLongMax") = "3"
    sys.props("nebflow.delegate.standby.answerFloorMs") = "0"
    val sid = "delegate-nebflow-standby-declared-silent"
    val system = ActorSystem(s"standby-declared-${scala.util.Random.nextInt(100000)}")
    val program = for
      resources <- mkResources(system, os.temp.dir())
      received <- Ref.of[IO, List[(Long, String)]](Nil)
      child <- nudgeRecorder(system, "standby-child-declared", received)
      supervisor <- supervisorRef(system)
      _ <- IO(seedRecord(sid))
      _ <- resources.agentRegistry.update(
        _ + (sid -> AgentRecord(sid, child, AgentKind.Delegate, "spec-root", None, status = AgentStatus.Waiting))
      )
      _ <- IO(DelegateRegistry.parkStandby(sid)) // 已申报待命（停靠）
      _ <- DelegateStandbyCare.arm(sid, child, supervisor, resources, DelegateStandbyCare.Declared)
      liveAfter <- IO(DelegateStandbyCare.caretakerLive(sid))
      stateAfter <- IO(DelegateStandbyCare.careStateOf(sid))
      _ <- IO.sleep(500.millis) // 远超 60ms 首档：若还有任何提醒腿，这里必炸
      rows <- received.get
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield (liveAfter, stateAfter, rows)
    program.map { case (live, state, rows) =>
      assertEquals(
        rows,
        Nil,
        s"🔴 申报待命腿必须零注入（改前红：Declared 腿照常催 —— 作者 18:14「都已经报了 stand by 了，还通知什么」），实际=${rows.map(_._2.take(80))}"
      )
      assertEquals(live, false, "申报待命腿不得武装 caretaker")
      assertEquals(state, None, "申报待命腿不得写看护记账（careStateOf=None）")
    }
  }

  // ── ⑤ 应答下限（治「答了无变化还分钟级连戳」）────────────────────────

  test("⑤ 实测间隔：delays=50ms / floor=600ms ⇒ 相邻提醒间隔 ≥ floor（不出现分钟级连戳形态）") {
    sys.props("nebflow.delegate.standby.nudgeDelaysMs") = "50"
    sys.props("nebflow.delegate.standby.nudgeLongMs") = "50"
    sys.props("nebflow.delegate.standby.nudgeLongMax") = "2"
    sys.props("nebflow.delegate.standby.answerFloorMs") = "600"
    val sid = "delegate-nebflow-standby-floor"
    val system = ActorSystem(s"standby-floor-${scala.util.Random.nextInt(100000)}")
    val program = for
      resources <- mkResources(system, os.temp.dir())
      received <- Ref.of[IO, List[(Long, String)]](Nil)
      child <- nudgeRecorder(system, "standby-child-floor", received)
      supervisor <- supervisorRef(system)
      _ <- IO(seedRecord(sid))
      _ <- resources.agentRegistry.update(
        _ + (sid -> AgentRecord(sid, child, AgentKind.Delegate, "spec-root", None, status = AgentStatus.Waiting))
      )
      _ <- DelegateStandbyCare.arm(sid, child, supervisor, resources, DelegateStandbyCare.IdleNudge)
      _ <-
        def answersLoop(deadline: Long, seen: Int): IO[Unit] =
          IO.realTime.map(_.toMillis).flatMap { now =>
            if now >= deadline then IO.unit
            else
              received.get.flatMap { rows =>
                if rows.size > seen then
                  DelegateStandbyCare.arm(sid, child, supervisor, resources, DelegateStandbyCare.IdleNudge) *>
                    IO.sleep(10.millis) *> answersLoop(deadline, rows.size)
                else IO.sleep(10.millis) *> answersLoop(deadline, seen)
              }
          }
        IO.realTime.map(_.toMillis).flatMap(start => answersLoop(start + 2500L, 0))
      rows <- received.get
      state = DelegateStandbyCare.careStateOf(sid)
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield (rows, state)
    program.map { case (rows, state) =>
      val gaps = rows.map(_._1).sliding(2).collect { case List(a, b) => b - a }.toList
      println(s"[standby-floor-spec] rows=${rows.size} gaps=$gaps state=$state")
      assertEquals(rows.size, 3, s"delays=1 档 + longMax=2 ⇒ 3 条封顶，实际 ${rows.size}，gaps=$gaps")
      assert(gaps.nonEmpty, s"至少一条间隔，gaps=$gaps")
      assert(
        gaps.forall(_ >= 550L),
        s"🔴 应答后/第 2 档起的间隔必须 ≥ floor 600ms（改前红：无 floor ⇒ 复读 ~50ms，正是作者报障的分钟级连戳形态），实际 gaps=$gaps"
      )
      assertEquals(state.map(_.attempt), Some(3), "记账面：已发 3 档")
    }
  }

end DelegateStandbyCareAnswerSpec
