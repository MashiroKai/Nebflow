package nebflow.core.tools

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.syntax.*
import munit.FunSuite
import nebflow.actor.ActorSystem
import nebflow.agent.*
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.FileChangeTracker
import nebflow.core.flow.{MailDeliveryDedup, MailQueueStore, TeamSessionRegistry}
import nebflow.core.task.FileTaskStore
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * P0 投递层指纹去重 — 投递链 wiring 验证（真实 AgentActor）。
 *
 *  - W1 同指纹第二条不同 item（窗口内）→ 消费但不注入（正好一次注入）
 *  - W2 重启重放：磁盘指纹文件预置（模拟重启前已投递、内存空）→ 重放被抑制
 *  - W3 磁盘指纹已过期 → 合法重发正常注入（不误杀）
 */
class MailDedupWiringSpec extends FunSuite:

  private val originalRoot = PathUtil.dataRoot

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    nebflow.core.LlmLogWriter.setEnabled(true)

  nebflow.core.LlmLogWriter.setEnabled(false)

  private class RecordingLlm(delayMs: Long = 0L) extends LlmHandle[IO]:
    val requests: Ref[IO, List[LlmRequest]] = Ref.unsafe(Nil)
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected"))
    def sendStream(
        req: LlmRequest,
        onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(requests.update(req :: _)) >>
        Stream.eval(IO.sleep(delayMs.millis)) >>
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

  private def fixtureTeam(tmp: os.Path): Unit =
    val data = tmp / "data"
    PathUtil.setDataRoot(data)
    val teamDir = data / "teams" / "dq"
    os.makeDir.all(teamDir)
    os.write.over(
      teamDir / "team.json",
      """{"name": "dq", "description": "dedup fixture", "lead": "boss", "members": ["member"]}"""
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

  private def mkItem(id: String, message: String): MailQueueStore.MailQueueItem =
    MailQueueStore.MailQueueItem(
      id = id, from = "tester", fromSession = "sender-dd",
      message = message, `type` = "INFO",
      timestamp = System.currentTimeMillis(), imagePaths = Nil
    )

  /** Seed the persisted fingerprint file as if a previous JVM delivered `fp`
   * at `ts` — simulates the state at a restart boundary. */
  private def seedDisk(fp: String, ts: Long): Unit =
    val file = PathUtil.dataRoot / "mail-dedup.json"
    os.makeDir.all(file / os.up)
    os.write.over(file, Map(fp -> ts).asJson.noSpaces)

  test("W1 same-fingerprint second item within window is consumed without injection") {
    val system = ActorSystem(s"dd-w1-${java.util.UUID.randomUUID().toString.take(6)}")
    val tmp = os.temp.dir(prefix = "dd-w1")
    fixtureTeam(tmp)
    MailDeliveryDedup.reset() // fresh-JVM isolation
    val llm = new RecordingLlm

    val io = for
      sessionStore <- IO.pure(SessionStore(tmp / "sessions", tmp / "tasks"))
      meta <- sessionStore.createSession("dq/member", agentName = Some("member"), flowName = Some("dq"))
      _ <- TeamSessionRegistry.registerSession("dq", "member", meta.id)
      resources <- mkResources(system, tmp, llm, sessionStore)
      refOpt <- MailTool.activateAgent(meta.id, resources, system, ctxFor(resources, system, "sender-dd"))
      // First item — delivers normally
      item1 = mkItem("mail-q-w1a", "DEDUP_W1_MARKER")
      _ <- MailQueueStore.append(meta.id, item1)
      _ <- refOpt.traverse_(ref => ref ! AgentCommand.MailQueued(item1, "sender-dd"))
      _ <- waitUntil(20.seconds)(llm.requests.get.map(_.nonEmpty))
      _ <- IO.sleep(500.millis) // let the turn complete and the actor return to idle
      // Second item: DISTINCT queue item (new id/timestamp) but IDENTICAL
      // fingerprint (same sender + recipient + content) — the replay shape
      _ <- MailQueueStore.append(meta.id, mkItem("mail-q-w1b", "DEDUP_W1_MARKER"))
      _ <- refOpt.traverse_(ref => ref ! AgentCommand.MailQueued(mkItem("mail-q-w1b", "DEDUP_W1_MARKER"), "sender-dd"))
      _ <- waitUntil(20.seconds)(MailQueueStore.load(meta.id).map(_.isEmpty))
      _ <- IO.sleep(1.second) // let a potential duplicate injection run
      persisted <- sessionStore.loadMessagesForSession(meta.id)
      queueLeft <- MailQueueStore.load(meta.id)
    yield (persisted, queueLeft)

    val (persisted, queueLeft) = io.unsafeRunSync()
    val injected = persisted.count(_.content.fold(identity, _.mkString).contains("DEDUP_W1_MARKER"))
    assertEquals(
      clue(injected), 1,
      s"duplicate fingerprint must be injected exactly once, got $injected (persisted=${persisted.size})"
    )
    assert(clue(queueLeft).isEmpty, "duplicate item must still be consumed from the queue")
  }

  test("W2 restart replay: disk-backed fingerprint suppresses re-delivery") {
    val system = ActorSystem(s"dd-w2-${java.util.UUID.randomUUID().toString.take(6)}")
    val tmp = os.temp.dir(prefix = "dd-w2")
    fixtureTeam(tmp)
    MailDeliveryDedup.reset() // fresh JVM — memory empty
    val llm = new RecordingLlm

    val io = for
      sessionStore <- IO.pure(SessionStore(tmp / "sessions", tmp / "tasks"))
      meta <- sessionStore.createSession("dq/member", agentName = Some("member"), flowName = Some("dq"))
      _ <- TeamSessionRegistry.registerSession("dq", "member", meta.id)
      // The pre-restart delivery is recorded on disk only (JVM restarted)
      _ <- IO(seedDisk(MailDeliveryDedup.fingerprint("tester", meta.id, "REPLAY_MARKER_W2"), System.currentTimeMillis()))
      resources <- mkResources(system, tmp, llm, sessionStore)
      refOpt <- MailTool.activateAgent(meta.id, resources, system, ctxFor(resources, system, "sender-dd"))
      // Restart recovery replays the disk-head item (MailTool:1075 shape)
      replay = mkItem("mail-q-w2", "REPLAY_MARKER_W2")
      _ <- MailQueueStore.append(meta.id, replay)
      _ <- refOpt.traverse_(ref => ref ! AgentCommand.MailQueued(replay, "sender-dd"))
      _ <- waitUntil(20.seconds)(MailQueueStore.load(meta.id).map(_.isEmpty))
      _ <- IO.sleep(1.second) // let a potential (wrong) injection run
      persisted <- sessionStore.loadMessagesForSession(meta.id)
      reqs <- llm.requests.get
    yield (persisted, reqs)

    val (persisted, reqs) = io.unsafeRunSync()
    val injected = persisted.count(_.content.fold(identity, _.mkString).contains("REPLAY_MARKER_W2"))
    assertEquals(
      clue(injected), 0,
      "replayed mail within the dedup window must NOT be injected (disk-backed fingerprint)"
    )
    assert(clue(reqs).isEmpty, "no LLM turn should run for a suppressed replay")
  }

  test("W3 expired disk fingerprint: legitimate re-send delivers normally") {
    val system = ActorSystem(s"dd-w3-${java.util.UUID.randomUUID().toString.take(6)}")
    val tmp = os.temp.dir(prefix = "dd-w3")
    fixtureTeam(tmp)
    MailDeliveryDedup.reset()
    val llm = new RecordingLlm

    val io = for
      sessionStore <- IO.pure(SessionStore(tmp / "sessions", tmp / "tasks"))
      meta <- sessionStore.createSession("dq/member", agentName = Some("member"), flowName = Some("dq"))
      _ <- TeamSessionRegistry.registerSession("dq", "member", meta.id)
      // Identical content was delivered LONG ago — outside the window
      _ <- IO(seedDisk(
        MailDeliveryDedup.fingerprint("tester", meta.id, "RESEND_MARKER_W3"),
        System.currentTimeMillis() - nebflow.shared.Defaults.MailDedupWindowMs - 60_000L
      ))
      resources <- mkResources(system, tmp, llm, sessionStore)
      refOpt <- MailTool.activateAgent(meta.id, resources, system, ctxFor(resources, system, "sender-dd"))
      resend = mkItem("mail-q-w3", "RESEND_MARKER_W3")
      _ <- MailQueueStore.append(meta.id, resend)
      _ <- refOpt.traverse_(ref => ref ! AgentCommand.MailQueued(resend, "sender-dd"))
      _ <- waitUntil(20.seconds)(llm.requests.get.map(_.nonEmpty))
      persisted <- sessionStore.loadMessagesForSession(meta.id)
    yield persisted

    val persisted = io.unsafeRunSync()
    val injected = persisted.count(_.content.fold(identity, _.mkString).contains("RESEND_MARKER_W3"))
    assertEquals(
      clue(injected), 1,
      "identical content outside the window is a legitimate re-send and must deliver"
    )
  }

  test("W4 turn-end drain suppresses same-fingerprint item queued while busy") {
    val system = ActorSystem(s"dd-w4-${java.util.UUID.randomUUID().toString.take(6)}")
    val tmp = os.temp.dir(prefix = "dd-w4")
    fixtureTeam(tmp)
    MailDeliveryDedup.reset()
    // Slow LLM: keeps the first turn in-flight so the second MailQueued lands
    // while busy — exercising the turn-end drain path (site 2), not idle.
    val llm = new RecordingLlm(delayMs = 1500L)

    val io = for
      sessionStore <- IO.pure(SessionStore(tmp / "sessions", tmp / "tasks"))
      meta <- sessionStore.createSession("dq/member", agentName = Some("member"), flowName = Some("dq"))
      _ <- TeamSessionRegistry.registerSession("dq", "member", meta.id)
      resources <- mkResources(system, tmp, llm, sessionStore)
      refOpt <- MailTool.activateAgent(meta.id, resources, system, ctxFor(resources, system, "sender-dd"))
      // First item — delivered while idle (records the fingerprint)
      item1 = mkItem("mail-q-w4a", "TURNEND_MARKER_W4")
      _ <- MailQueueStore.append(meta.id, item1)
      _ <- refOpt.traverse_(ref => ref ! AgentCommand.MailQueued(item1, "sender-dd"))
      _ <- waitUntil(20.seconds)(llm.requests.get.map(_.nonEmpty))
      // Second item: identical fingerprint, queued while the first turn is
      // still busy (slow LLM above guarantees the window)
      _ <- MailQueueStore.append(meta.id, mkItem("mail-q-w4b", "TURNEND_MARKER_W4"))
      _ <- refOpt.traverse_(ref => ref ! AgentCommand.MailQueued(mkItem("mail-q-w4b", "TURNEND_MARKER_W4"), "sender-dd"))
      _ <- IO.sleep(300.millis) // second MailQueued lands while turn 1 is busy
      _ <- waitUntil(20.seconds)(MailQueueStore.load(meta.id).map(_.isEmpty))
      _ <- IO.sleep(1.second) // let a potential duplicate injection run
      persisted <- sessionStore.loadMessagesForSession(meta.id)
      queueLeft <- MailQueueStore.load(meta.id)
    yield (persisted, queueLeft)

    val (persisted, queueLeft) = io.unsafeRunSync()
    val injected = persisted.count(_.content.fold(identity, _.mkString).contains("TURNEND_MARKER_W4"))
    assertEquals(
      clue(injected), 1,
      s"busy-queued duplicate fingerprint must not inject a second turn, got $injected (persisted=${persisted.size})"
    )
    assert(clue(queueLeft).isEmpty, "turn-end duplicate must still be consumed from the queue")
  }

end MailDedupWiringSpec
