package nebflow.core.tools

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import munit.FunSuite
import nebflow.actor.{ActorSystem, Behaviors}
import nebflow.agent.*
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.flow.{MailQueueStore, TeamSessionRegistry}
import nebflow.core.task.FileTaskStore
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, Message, MessageRole, StreamChunk}
import nebflow.core.FileChangeTracker

import scala.concurrent.duration.*

/**
  * Issue #22: queue 模式 Mail 对冷 agent 的激活链。
  *
  * 场景矩阵（覆盖「冷」的三种真实形态）：
  *  - S1 重启后冷（session 持久化、注册表空、无 actor）→ queueToSession 必须
  *    冷激活并 drain（与 immediate 同等激活保证）
  *  - S2 陈旧死 ref（actorMap 缓存了死 actor——deathwatch 失联/未挂的清理缺口）
  *    → 必须检测死亡并重生，而不是把 MailQueued 送进无人消费的队列静默蒸发
  *  - S3 激活失败诚实化（agent def / session 缺失）→ 工具结果必须如实报错，
  *    不能返回「已排队将被处理」的成功谎言（永不 drain）
  *  - S4 重复 MailQueued（respawn drain 触发器 + 投递方各发一次同一 item）
  *    → 同一 item 不得双份注入为两个 turn
  */
class ColdQueueActivationSpec extends FunSuite:

  private val originalRoot = PathUtil.dataRoot

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    nebflow.core.LlmLogWriter.setEnabled(true)

  nebflow.core.LlmLogWriter.setEnabled(false)

  private class RecordingLlm extends LlmHandle[IO]:
    val requests: Ref[IO, List[LlmRequest]] = Ref.unsafe(Nil)
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected"))
    def sendStream(
        req: LlmRequest,
        onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(requests.update(req :: _)) >>
        Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  private def mkResources(
      system: ActorSystem,
      tmp: os.Path,
      llm: LlmHandle[IO],
      sessionStore: SessionStore
  ): IO[SharedResources] =
    for
      dispatcher <- cats.effect.std.Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
    yield SharedResources(
      llm = llm,
      dispatcher = dispatcher,
      sessionStore = sessionStore,
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

  /** fixture team "cq" with member agent def on disk; returns dataRoot tmp. */
  private def fixtureTeam(tmp: os.Path): Unit =
    val data = tmp / "data"
    PathUtil.setDataRoot(data)
    val teamDir = data / "teams" / "cq"
    os.makeDir.all(teamDir)
    os.write.over(
      teamDir / "team.json",
      """{"name": "cq", "description": "cold queue fixture", "lead": "boss", "members": ["member"]}"""
    )
    val memberDir = teamDir / "agents" / "member"
    os.makeDir.all(memberDir)
    os.write.over(memberDir / "agent.json", """{"description": "fixture member", "useWhen": "tests"}""")

  private def ctxFor(
      resources: SharedResources,
      system: ActorSystem,
      senderSid: String
  ): ToolContext =
    ToolContext(
      projectRoot = os.pwd.toString,
      sessionId = Some(senderSid),
      sharedResources = Some(resources),
      actorSystem = Some(system)
    )

  test("S1 queue mail to never-activated session cold-activates and drains") {
    val system = ActorSystem(s"cq-s1-${java.util.UUID.randomUUID().toString.take(6)}")
    val tmp = os.temp.dir(prefix = "cq-s1")
    fixtureTeam(tmp)
    val llm = new RecordingLlm

    val io = for
      sessionStore <- IO.pure(SessionStore(tmp / "sessions", tmp / "tasks"))
      meta <- sessionStore.createSession("cq/member", agentName = Some("member"), flowName = Some("cq"))
      _ <- TeamSessionRegistry.registerSession("cq", "member", meta.id)
      resources <- mkResources(system, tmp, llm, sessionStore)
      res <- MailTool.queueToSession(
        meta.id, "member", "COLD_TASK_MARKER_S1", "INFO", Nil,
        ctxFor(resources, system, "sender-s1"), system, "sender-s1"
      )
      _ <- waitUntil(20.seconds)(llm.requests.get.map(_.nonEmpty))
      reqs <- llm.requests.get
      queueLeft <- MailQueueStore.load(meta.id)
    yield (res, reqs, queueLeft)

    val (res, reqs, queueLeft) = io.unsafeRunSync()
    assert(res.isRight, s"queueToSession failed: $res")
    val texts = reqs.flatMap(_.messages.map(_.content.fold(identity, _.mkString)))
    assert(clue(texts).exists(_.contains("COLD_TASK_MARKER_S1")), "cold-activated agent never received the mail content")
    assert(clue(queueLeft).isEmpty, s"queue not drained: $queueLeft")
  }

  test("S2 queue mail to stale dead actor ref reactivates instead of silent void") {
    val system = ActorSystem(s"cq-s2-${java.util.UUID.randomUUID().toString.take(6)}")
    val tmp = os.temp.dir(prefix = "cq-s2")
    fixtureTeam(tmp)
    val llm = new RecordingLlm

    val io = for
      sessionStore <- IO.pure(SessionStore(tmp / "sessions", tmp / "tasks"))
      meta <- sessionStore.createSession("cq/member", agentName = Some("member"), flowName = Some("cq"))
      _ <- TeamSessionRegistry.registerSession("cq", "member", meta.id)
      resources <- mkResources(system, tmp, llm, sessionStore)
      // Stale-ref setup: a victim actor registered in actorMap then stopped
      // WITHOUT a deathwatch — emulates any cleanup-miss path (missed watch,
      // registry bookkeeping bug). actorMap keeps the dead ref.
      victim <- system.spawn(
        Behaviors.receiveMessage[AgentCommand](_ => IO.pure(Behaviors.stopped[AgentCommand])),
        s"victim-${meta.id.take(8)}"
      )
      _ <- TeamSessionRegistry.registerActor(meta.id, victim, resources, rootSessionId = meta.id)
      _ <- system.stop(victim)
      _ <- IO.sleep(200.millis) // let the fiber cancel complete
      // The mail under test
      res <- MailTool.queueToSession(
        meta.id, "member", "COLD_TASK_MARKER_S2", "INFO", Nil,
        ctxFor(resources, system, "sender-s2"), system, "sender-s2"
      )
      _ <- waitUntil(20.seconds)(llm.requests.get.map(_.nonEmpty))
      reqs <- llm.requests.get
      queueLeft <- MailQueueStore.load(meta.id)
    yield (res, reqs, queueLeft)

    val (res, reqs, queueLeft) = io.unsafeRunSync()
    assert(res.isRight, s"queueToSession failed: $res")
    val texts = reqs.flatMap(_.messages.map(_.content.fold(identity, _.mkString)))
    assert(
      clue(texts).exists(_.contains("COLD_TASK_MARKER_S2")),
      "MailQueued was sent to a dead actor and silently vanished — no reactivation"
    )
    assert(clue(queueLeft).isEmpty, s"queue not drained: $queueLeft")
  }

  test("S3 queue mail to session with missing agent def reports activation failure") {
    val system = ActorSystem(s"cq-s3-${java.util.UUID.randomUUID().toString.take(6)}")
    val tmp = os.temp.dir(prefix = "cq-s3")
    PathUtil.setDataRoot(tmp / "data") // NO team fixture — agent def missing
    val llm = new RecordingLlm

    val io = for
      sessionStore <- IO.pure(SessionStore(tmp / "sessions", tmp / "tasks"))
      meta <- sessionStore.createSession("ghost/agent", agentName = Some("ghost"), flowName = Some("ghost"))
      _ <- TeamSessionRegistry.registerSession("ghost", "ghost", meta.id)
      resources <- mkResources(system, tmp, llm, sessionStore)
      res <- MailTool.queueToSession(
        meta.id, "ghost", "GHOST_TASK", "INFO", Nil,
        ctxFor(resources, system, "sender-s3"), system, "sender-s3"
      )
    yield res

    val res = io.unsafeRunSync()
    assert(
      clue(res).isLeft,
      "activation failed (agent def missing) but tool reported success — the mail will never drain"
    )
    res.left.toOption.foreach { e =>
      assert(
        clue(e.message).toLowerCase.contains("activ"),
        s"error should mention activation failure, got: ${e.message}"
      )
    }
  }

  test("S4 duplicate MailQueued for the same item does not double-inject") {
    val system = ActorSystem(s"cq-s4-${java.util.UUID.randomUUID().toString.take(6)}")
    val tmp = os.temp.dir(prefix = "cq-s4")
    fixtureTeam(tmp)
    val llm = new RecordingLlm

    val io = for
      sessionStore <- IO.pure(SessionStore(tmp / "sessions", tmp / "tasks"))
      meta <- sessionStore.createSession("cq/member", agentName = Some("member"), flowName = Some("cq"))
      _ <- TeamSessionRegistry.registerSession("cq", "member", meta.id)
      resources <- mkResources(system, tmp, llm, sessionStore)
      refOpt <- MailTool.activateAgent(meta.id, resources, system, ctxFor(resources, system, "sender-s4"))
      item = MailQueueStore.MailQueueItem(
        id = "mail-q-dup1", from = "tester", fromSession = "sender-s4",
        message = "DUP_TASK_MARKER", `type` = "INFO",
        timestamp = System.currentTimeMillis(), imagePaths = Nil
      )
      _ <- MailQueueStore.append(meta.id, item) // real contract: item on disk before MailQueued
      _ <- refOpt.traverse_(ref => ref ! AgentCommand.MailQueued(item, "sender-s4"))
      _ <- IO.sleep(300.millis) // first drain enters processing
      _ <- refOpt.traverse_(ref => ref ! AgentCommand.MailQueued(item, "sender-s4")) // duplicate
      _ <- waitUntil(20.seconds)(llm.requests.get.map(_.nonEmpty))
      _ <- IO.sleep(1.seconds) // let a potential duplicate turn run
      persisted <- sessionStore.loadMessagesForSession(meta.id)
    yield persisted

    val persisted = io.unsafeRunSync()
    // Count INJECTION events in the persisted session (reminder turns replay
    // history and legitimately contain the marker — they are not injections)
    val injected = persisted.count(
      _.content.fold(identity, _.mkString).contains("DUP_TASK_MARKER")
    )
    assertEquals(
      clue(injected), 1,
      s"same queue item must be injected exactly once, got $injected messages (persisted=${persisted.size})"
    )
  }

end ColdQueueActivationSpec
