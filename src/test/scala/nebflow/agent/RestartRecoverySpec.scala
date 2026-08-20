package nebflow.agent

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, Behavior, Behaviors}
import nebflow.core.FileChangeTracker
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, Message, MessageRole, StreamChunk}

import scala.concurrent.duration.*

/**
  * Issue #13: AgentActor Mail 队列死锁——turn 卡死后 Mail 只见
  * immediate-input-queued 永不 injected，restartAgent 无法恢复
  * （pipeLlmCall 新调用静默挂起）。
  *
  * 本 spec 在当前基线复刻该场景：挂起 LLM 流（Stream.never，无 Done）的
  * turn + 排队 ImmediateInput + restartAgent。断言三件事：
  *  A. restart 后新 LLM 调用真的发生（不静默挂起）
  *  B. 排队的 immediate 输入在 restart 后被注入（不被 reset 吃掉）
  *  C. Full 级别真正从磁盘重建消息（不再与 Soft 同实现）
  */
class RestartRecoverySpec extends CatsEffectSuite:

  /** 吞掉所有消息的 parent 占位 behavior。 */
  private def ignoreBehavior: Behavior[AgentCommand] =
    Behaviors.receiveMessage[AgentCommand](_ => IO.pure(ignoreBehavior))

  /** 首请求永久挂起，后续请求正常回复——模拟卡死 turn。 */
  private class StuckThenOkLlm(counter: cats.effect.Ref[IO, Int]) extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected in this test"))
    def sendStream(
        req: LlmRequest,
        onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(counter.update(_ + 1)) >> Stream.eval(counter.get).flatMap { n =>
        if n <= stuckCount then Stream.never[IO]
        else Stream(StreamChunk.TextDelta(s"reply #$n"), StreamChunk.Done(None, None))
      }
    def stuckCount: Int = 1

  private def mkResources(system: ActorSystem, tmp: os.Path, llm: LlmHandle[IO]): IO[SharedResources] =
    for
      dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- IO.ref(ThinkingConfig())
      modelOverrides <- IO.ref(Map.empty[String, ModelCandidate])
      voiceMuted <- IO.ref(false)
    yield SharedResources(
      llm = llm,
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

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 50.millis)(
      cond: IO[Boolean]
  ): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true  => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError("waitUntil: condition not met in time"))
          else IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  private def memberDef: AgentDef =
    AgentDef(name = "member-x", description = "fixture", tools = List("Read"), systemPrompt = "")

  override def beforeEach(context: munit.BeforeEach): Unit =
    nebflow.core.LlmLogWriter.setEnabled(false)

  override def afterAll(): Unit =
    nebflow.core.LlmLogWriter.setEnabled(true)
    PathUtil.setDataRoot(os.home / ".nebflow")

  test("#13A restartAgent(Soft) on stuck turn: new LLM call happens, no silent hang") {
    val system = ActorSystem("rr-a")
    val tmp = os.temp.dir(prefix = "rr-a")
    PathUtil.setDataRoot(tmp / "data")

    val program = for
      counter <- IO.ref(0)
      llm = new StuckThenOkLlm(counter)
      resources <- mkResources(system, tmp, llm)
      parentRef <- system.spawn(
        ignoreBehavior,
        "parent-a"
      )
      sid = "rr-stuck-a"
      ref <- system.spawn(
        AgentActor(
          agentDef = memberDef,
          resources = resources,
          wsSend = _ => IO.unit,
          depth = 1,
          parentRef = Some(parentRef),
          sessionId = Some(sid),
          sessionName = Some("rr member"),
          initialMessages = Nil
        ),
        sid
      )
      // Turn 1 — hangs on Stream.never (no Done, no first-token)
      _ <- ref ! AgentCommand.UserInput("STUCK_TURN_TASK", None)
      _ <- IO.sleep(500.millis)
      // Mail arrives mid-stuck-turn → immediate-input-queued (processing)
      _ <- ref ! AgentCommand.ImmediateInput(
        "MAIL_WHILE_STUCK", source = Some("mail"), sender = Some("boss"), delivery = Some("immediate")
      )
      _ <- IO.sleep(200.millis)
      // Supervisor restart — the recovery under test
      _ <- ref ! AgentCommand.RestartAgent(RestartLevel.Soft)
      _ <- waitUntil(15.seconds)(counter.get.map(_ >= 2))
    yield ()

    program.unsafeRunSync()
    // Reaching here means: the restart's pipeLlmCall produced a second LLM
    // request — no silent hang (issue #13's core claim).
  }

  test("#13B queued immediate input survives restartAgent and is injected") {
    val system = ActorSystem("rr-b")
    val tmp = os.temp.dir(prefix = "rr-b")
    PathUtil.setDataRoot(tmp / "data")

    val program = for
      counter <- IO.ref(0)
      llm = new StuckThenOkLlm(counter)
      resources <- mkResources(system, tmp, llm)
      parentRef <- system.spawn(
        ignoreBehavior,
        "parent-b"
      )
      sid = "rr-stuck-b"
      ref <- system.spawn(
        AgentActor(
          agentDef = memberDef,
          resources = resources,
          wsSend = _ => IO.unit,
          depth = 1,
          parentRef = Some(parentRef),
          sessionId = Some(sid),
          sessionName = Some("rr member"),
          initialMessages = Nil
        ),
        sid
      )
      _ <- ref ! AgentCommand.UserInput("STUCK_TURN_TASK_B", None)
      _ <- IO.sleep(500.millis)
      _ <- ref ! AgentCommand.ImmediateInput(
        "MAIL_SURVIVES_RESTART", source = Some("mail"), sender = Some("boss"), delivery = Some("immediate")
      )
      _ <- IO.sleep(200.millis)
      _ <- ref ! AgentCommand.RestartAgent(RestartLevel.Soft)
      // Second LLM call happens, then the queued immediate drains into a turn
      _ <- waitUntil(15.seconds) {
        resources.sessionStore.loadMessagesForSession(sid).map { msgs =>
          msgs.exists(_.content.fold(identity, _.mkString).contains("MAIL_SURVIVES_RESTART"))
        }
      }
    yield ()

    program.unsafeRunSync()
  }

  test("#13C RestartAgent(Full) reloads persisted history (not a Soft alias)") {
    val system = ActorSystem("rr-c")
    val tmp = os.temp.dir(prefix = "rr-c")
    PathUtil.setDataRoot(tmp / "data")

    val program = for
      counter <- IO.ref(0)
      llm = new StuckThenOkLlm(counter)
      resources <- mkResources(system, tmp, llm)
      parentRef <- system.spawn(
        ignoreBehavior,
        "parent-c"
      )
      sid = "rr-stuck-c"
      ref <- system.spawn(
        AgentActor(
          agentDef = memberDef,
          resources = resources,
          wsSend = _ => IO.unit,
          depth = 1,
          parentRef = Some(parentRef),
          sessionId = Some(sid),
          sessionName = Some("rr member"),
          initialMessages = Nil
        ),
        sid
      )
      _ <- ref ! AgentCommand.UserInput("STUCK_TURN_TASK_C", None)
      _ <- IO.sleep(500.millis)
      // External write to the persisted history while the actor holds stale
      // in-memory state — Full must rebuild from disk.
      _ <- resources.sessionStore.saveMessagesForSession(
        sid,
        List(
          Message(MessageRole.User, Left("FULL_RELOAD_MARKER_IN_HISTORY")),
          Message(MessageRole.Assistant, Left("persisted reply"))
        )
      )
      _ <- ref ! AgentCommand.RestartAgent(RestartLevel.Full)
      _ <- waitUntil(15.seconds)(counter.get.map(_ >= 2))
      _ <- IO.sleep(500.millis)
      persisted <- resources.sessionStore.loadMessagesForSession(sid)
    yield persisted

    val persisted = program.unsafeRunSync()
    // The restarted turn ran on the reloaded disk history: the post-restart
    // request context (and resulting persisted session) must contain the
    // disk marker — a Soft alias would only have STUCK_TURN_TASK_C.
    assert(
      clue(persisted).exists(_.content.fold(identity, _.mkString).contains("FULL_RELOAD_MARKER_IN_HISTORY")),
      s"Full restart did not rebuild from persisted history; persisted=${persisted.map(_.content.fold(identity, _.mkString).take(40))}"
    )
  }

end RestartRecoverySpec
