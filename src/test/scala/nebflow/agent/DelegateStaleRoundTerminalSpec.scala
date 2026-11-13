package nebflow.agent

import cats.effect.{IO, Ref}
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import fs2.Stream
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, Behavior, Behaviors}
import nebflow.actor.{AgentCommand, AgentEvent}
import nebflow.core.FileChangeTracker
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.{
  FallbackAttempt,
  LlmHandle,
  LlmRequest,
  LlmResponse,
  Message,
  MessageRole,
  PathUtil,
  StreamChunk,
  SubAgentTask,
  ThinkingConfig
}
import nebflow.shared.given

import scala.concurrent.duration.*

/**
 * Round-model terminal idempotency spec:
 *
 *  - S-1 stale-round leg: a completion carrying an OLD round generation (the registry
 *    already points at a newer roundId) lands ONLY a stale-round audit row in the
 *    round ledger — no terminal delivery, no archive rewrite, no cleanup restart;
 *    the supervisor still stops honestly.
 *  - S-2 terminal mailbox identity = `<id>#<roundId>`: a current-generation
 *    completion delivers under the round-scoped identity (the existing generation
 *    mechanism, now round-aligned).
 */
class DelegateStaleRoundTerminalSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-stale-round"
  private val originalRoot: os.Path = PathUtil.dataRoot

  private def resetRoot(): Unit =
    if os.exists(tempRoot) then
      try os.remove.all(tempRoot)
      catch case _: Throwable => ()
    os.makeDir.all(tempRoot)
    PathUtil.setDataRoot(tempRoot)

  resetRoot()

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit = resetRoot()

  private val fakeLlm = new LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("fake llm not expected here"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.raiseError[IO](new RuntimeException("fake llm not expected here"))

  private def mkRecordingCmd(record: Ref[IO, List[AgentCommand]]): Behavior[AgentCommand] =
    def loop: Behavior[AgentCommand] =
      Behaviors.receiveMessage(cmd => record.update(_ :+ cmd).as(loop))
    loop

  private def mkStopOnStopProbe(record: Ref[IO, List[AgentCommand]]): Behavior[AgentCommand] =
    def loop: Behavior[AgentCommand] =
      Behaviors.receiveMessage:
        case stop: AgentCommand.Stop =>
          record.update(_ :+ stop).as(Behaviors.stopped)
        case other =>
          record.update(_ :+ other).as(loop)
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

  private def mkDelegateRecord(id: String, parentSid: String, roundNo: Int, roundId: String): Unit =
    nebflow.core.delegate.DelegateRegistry.put(
      nebflow.core.delegate.DelegateRegistry.Record(
        id = id,
        executor = "nebflow",
        cwd = os.temp.dir().toString,
        project = None,
        taskId = None,
        parentSessionId = parentSid,
        title = s"stale $id",
        createdAt = System.currentTimeMillis(),
        roundNo = Some(roundNo),
        roundId = Some(roundId)
      )
    )

  /** Debt file written verbatim (bypasses record()'s registry-aligned minting). */
  private def writeDebt(owner: String, recipient: String, generation: String, msgs: List[Message]): Unit =
    val dir = PathUtil.dataRoot / "sessions" / nebflow.core.mail.SessionMailbox.safeId(owner)
    os.makeDir.all(dir)
    val debt =
      nebflow.core.mail.CompletionDebtStore.Debt(owner, recipient, msgs, "deliverable", generation, Some(generation))
    os.write.over(dir / "completion-debt.json", debt.asJson.noSpaces)

  private def assistantMsg(text: String): Message =
    Message(MessageRole.Assistant, Left(text))

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

  private def terminalMail(parentSid: String): IO[List[nebflow.core.mail.SessionMailbox.Entry]] =
    nebflow.core.mail.SessionMailbox
      .scanPending(parentSid)
      .map(_.filter(_.kind == "delegate-terminal"))
      .handleErrorWith(_ => IO.pure(Nil))

  private def spawnSupervisor(
    system: ActorSystem,
    resources: SharedResources,
    parentRef: nebflow.actor.ActorRef[AgentCommand],
    childSid: String,
    parentSid: String,
    childEvents: Ref[IO, List[AgentCommand]]
  ): IO[nebflow.actor.ActorRef[AgentEvent]] =
    for
      childRef <- system.spawn(mkStopOnStopProbe(childEvents), s"child-$childSid")
      supervisor <- system.spawn(
        BackoffSupervisor(
          childRef = childRef,
          childSpawnFn = (_, _) => IO.raiseError(new RuntimeException("respawn must not happen in this spec")),
          childName = childSid,
          parentRef = Some(parentRef),
          description = "stale round test",
          agentName = "Worker",
          subagentId = childSid,
          parentSessionId = parentSid,
          resources = resources,
          initialPrompt = "do work",
          source = "delegate"
        ),
        s"supervisor-$childSid"
      )
    yield supervisor

  test("S-1 a late terminal from an old round only audits (stale-round row); no delivery, no archive, honest stop") {
    val system = ActorSystem("stale-round-1")
    val tmp = os.temp.dir()
    val childSid = "delegate-stale-1"
    for
      resources <- mkResources(system, tmp)
      parent <- resources.sessionStore.createSession("Nebula-root", agentName = Some("Nebula"))
      parentSid = parent.id
      // the registry already points at ROUND 2 (a Mail round was opened); the debt
      // still carries the OLD round-1 generation
      _ = mkDelegateRecord(childSid, parentSid, roundNo = 2, roundId = "gen-round-2")
      _ = writeDebt(childSid, parentSid, "gen-round-1", List(assistantMsg("old round text")))
      parentEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- system.spawn(mkRecordingCmd(parentEvents), "parent-rec-s1")
      childEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      supervisor <- spawnSupervisor(system, resources, parentRef, childSid, parentSid, childEvents)
      replacement <- system.spawn(mkRecordingCmd(parentEvents), "replacement-s1")
      _ <- resources.agentRegistry.update(
        _ + (childSid -> nebflow.actor.AgentRecord(
          childSid,
          replacement,
          nebflow.actor.AgentKind.Delegate,
          parentSid
        ))
      )
      _ <- supervisor ! AgentEvent.Completed(childSid, List(assistantMsg("old round completion — late")))
      _ <- waitUntil(10.seconds)(childEvents.get.map(_.exists(_.isInstanceOf[AgentCommand.Stop])))
      mails <- terminalMail(parentSid)
      staleRows <- IO(
        nebflow.core.delegate.DelegateRoundLedger
          .rowsOf(childSid)
          .filter(_.kind == nebflow.core.delegate.DelegateRoundLedger.KindStaleRound)
      )
      recAfter <- IO(nebflow.core.delegate.DelegateRegistry.find(childSid))
      childCmds <- childEvents.get
      registryAfter <- resources.agentRegistry.get
      _ <- system.stopAll
    yield
      assertEquals(
        registryAfter.get(childSid).map(_.ref),
        Some(replacement),
        "old supervisor preserves replacement registration"
      )
      assertEquals(mails, Nil, "a stale-round terminal is NEVER delivered")
      assertEquals(staleRows.size, 1, "exactly one stale-round audit row lands")
      assertEquals(staleRows.head.roundId, "gen-round-1", "the audit row names the old generation")
      assertEquals(staleRows.head.roundNo, 2, "the audit row is anchored at the CURRENT round number")
      assert(
        staleRows.head.detail.exists(_.contains("gen-round-1")),
        s"detail carries the audit facts: ${staleRows.head.detail}"
      )
      assert(childCmds.exists(_.isInstanceOf[AgentCommand.Stop]), "the supervisor stops the child honestly")
      assertEquals(recAfter.flatMap(_.archivedAt), None, "no archive rewrite from a stale event")
    end for
  }

  test("S-2 a current-generation completion delivers under the <id>#<roundId> terminal identity") {
    val system = ActorSystem("stale-round-2")
    val tmp = os.temp.dir()
    val childSid = "delegate-stale-2"
    for
      resources <- mkResources(system, tmp)
      parent <- resources.sessionStore.createSession("Nebula-root", agentName = Some("Nebula"))
      parentSid = parent.id
      _ = mkDelegateRecord(childSid, parentSid, roundNo = 1, roundId = "gen-cur-1")
      _ = writeDebt(childSid, parentSid, "gen-cur-1", List(assistantMsg("current round text")))
      parentEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- system.spawn(mkRecordingCmd(parentEvents), "parent-rec-s2")
      childEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      supervisor <- spawnSupervisor(system, resources, parentRef, childSid, parentSid, childEvents)
      _ <- supervisor ! AgentEvent.Completed(childSid, List(assistantMsg("current round final report")))
      _ <- waitUntil(10.seconds)(terminalMail(parentSid).map(_.nonEmpty))
      mails <- terminalMail(parentSid)
      rounds <- IO(nebflow.core.delegate.DelegateRoundLedger.currentRounds(childSid))
      _ <- system.stopAll
    yield
      assertEquals(mails.size, 1)
      assertEquals(
        mails.head.identity,
        s"$childSid#gen-cur-1",
        "the terminal mailbox key is <id>#<roundId> (round-aligned generation)"
      )
      assertEquals(mails.head.event.metadata("completionGeneration"), Some(io.circe.Json.fromString("gen-cur-1")))
      assertEquals(
        rounds.find(_.roundNo == 1).flatMap(_.verdict),
        Some("completed"),
        "settlement writes the round verdict into the append-only ledger"
      )
      assert(rounds.find(_.roundNo == 1).exists(_.settledAt.isDefined), "the round row carries settledAt")
    end for
  }

  List(false, true).foreach { legacy =>
    test(s"same-round changed report delivers despite a different generation (legacy=$legacy)") {
      val system = ActorSystem(s"same-round-$legacy")
      val tmp = os.temp.dir()
      val childSid = s"delegate-same-round-$legacy"
      for
        resources <- mkResources(system, tmp)
        parent <- resources.sessionStore.createSession("Nebula-root", agentName = Some("Nebula"))
        _ = mkDelegateRecord(childSid, parent.id, 1, "execution-round")
        _ <- nebflow.core.mail.CompletionDebtStore.record(childSid, parent.id, List(assistantMsg("progress")))
        _ <- nebflow.core.mail.CompletionDebtStore.record(childSid, parent.id, List(assistantMsg("final")))
        debt <- nebflow.core.mail.CompletionDebtStore.pending(childSid)
        _ = assert(debt.toOption.flatten.exists(_.generation != "execution-round"))
        _ = assertEquals(debt.toOption.flatten.flatMap(_.roundId), Some("execution-round"))
        _ = if legacy then
          val file = PathUtil.dataRoot / "sessions" / childSid / "completion-debt.json"
          val json = io.circe.parser.parse(os.read(file)).toOption.get
          os.write.over(file, json.mapObject(_.remove("roundId")).noSpaces)
        parentEvents <- Ref.of[IO, List[AgentCommand]](Nil)
        parentRef <- system.spawn(mkRecordingCmd(parentEvents), "parent")
        childEvents <- Ref.of[IO, List[AgentCommand]](Nil)
        supervisor <- spawnSupervisor(system, resources, parentRef, childSid, parent.id, childEvents)
        _ <- supervisor ! AgentEvent.Completed(childSid, List(assistantMsg("final")))
        _ <- waitUntil(10.seconds)(terminalMail(parent.id).map(_.nonEmpty))
        mails <- terminalMail(parent.id)
        _ <- system.stopAll
      yield
        assertEquals(mails.size, 1)
        assertEquals(mails.head.identity, s"$childSid#${debt.toOption.flatten.get.generation}")
        assertEquals(nebflow.core.delegate.DelegateRoundLedger.rowsOf(childSid).count(_.kind == "stale-round"), 0)
      end for
    }
  }

  test("managed new run does not replay a previous run's pending completion debt") {
    val system = ActorSystem("managed-stale-debt")
    val tmp = os.temp.dir()
    val childSid = "delegate-managed-stale"
    for
      resources <- mkResources(system, tmp)
      parent <- resources.sessionStore.createSession("parent", agentName = Some("Nebula"))
      _ = mkDelegateRecord(childSid, parent.id, 2, "new-run")
      _ = writeDebt(childSid, parent.id, "old-run", List(assistantMsg("old report")))
      _ <- resources.subAgentTaskStore.beginRun(
        SubAgentTask(childSid, parent.id, "Worker", "work", "test", "running", 0, 1L, None, None, "delegate"),
        "new-run",
        parent.id,
        "nebflow"
      )
      parentEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- system.spawn(mkRecordingCmd(parentEvents), "parent")
      childEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      supervisor <- spawnSupervisor(system, resources, parentRef, childSid, parent.id, childEvents)
      _ <- IO.sleep(200.millis)
      before <- resources.subAgentTaskStore.findByTaskId(childSid)
      childCommands <- childEvents.get
      mails <- terminalMail(parent.id)
      _ <- system.stopAll
    yield
      assertEquals(before.flatMap(_.executionStatus), Some("starting"))
      assertEquals(childCommands, Nil)
      assertEquals(mails, Nil)
    end for
  }

  test("identical reports in a new round do not retain the previous round debt") {
    val childSid = "delegate-identical-report"
    val messages = List(assistantMsg("done"))
    for
      _ <- IO(mkDelegateRecord(childSid, "parent", 1, "round-1"))
      _ <- nebflow.core.mail.CompletionDebtStore.record(childSid, "parent", messages)
      _ <- IO(mkDelegateRecord(childSid, "parent", 2, "round-2"))
      _ <- nebflow.core.mail.CompletionDebtStore.record(childSid, "parent", messages)
      debt <- nebflow.core.mail.CompletionDebtStore.pending(childSid)
    yield
      assertEquals(debt.toOption.flatten.flatMap(_.roundId), Some("round-2"))
      assertEquals(debt.toOption.flatten.map(_.generation), Some("round-2"))
  }

  test("multiple reports in the same round never reuse an earlier delivery identity") {
    val childSid = "delegate-multiple-reports"
    for
      _ <- IO(mkDelegateRecord(childSid, "parent", 1, "round-1"))
      debts <- (1 to 3).toList.traverse { n =>
        nebflow.core.mail.CompletionDebtStore.record(childSid, "parent", List(assistantMsg(s"report-$n"))) *>
          nebflow.core.mail.CompletionDebtStore.pending(childSid).map(_.toOption.flatten.get)
      }
    yield
      assertEquals(debts.map(_.generation).distinct.size, 3)
      assert(debts.forall(_.roundId.contains("round-1")))
  }

end DelegateStaleRoundTerminalSpec
