package nebflow.agent

import cats.effect.{IO, Ref}
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.{ActorPath, ActorRef, ActorSystem, AgentCommand, AgentDef, AgentEvent, AgentKind, AgentRecord}
import nebflow.core.FileChangeTracker
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.core.mail.{CompletionDebtStore, SessionMailbox}
import nebflow.llm.ModelCandidate
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, Message, MessageRole, PathUtil, StreamChunk, SubAgentTask, ThinkingConfig}

import scala.concurrent.duration.*

/**
 * End-to-end legs for the two ways a managed run's completion signal can be lost,
 * with **both sides real**: a real `AgentActor` turn writes the run-bound completion
 * debt before sending ([[AgentFinishTurn.emitCompletion]]) and the real boot
 * reconciliation ([[SubAgentStartupRecovery.recoverPendingInternal]]) recovers from
 * it. Only the transport in between is the failure under test.
 *
 *  - E2E-1 `Completed` send failure: the supervisor transport raises on delivery. The
 *    signal never leaves the child; the debt does. Boot reconciliation must terminalize
 *    the row and deliver exactly one parent notice.
 *  - E2E-2 supervisor never processes it: the message is accepted into a mailbox whose
 *    consumer never runs (dead actor / process gone). Nothing handles the completion;
 *    the debt is the only surviving evidence and boot reconciliation must deliver.
 *
 * Red semantics: make reconciliation debt-blind (drop the `case (None, _)` debt branch of
 * `recoverActiveEnvelopes`) or stop writing the debt in `emitCompletion` ⇒ the row stays
 * `running` and the parent mailbox stays empty in both legs.
 */
class ManagedTerminalDeliveryE2ESpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 90.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-managed-terminal-e2e"
  private val originalRoot: os.Path = PathUtil.dataRoot

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  private def resetRoot(): Unit =
    if os.exists(tempRoot) then
      try os.remove.all(tempRoot)
      catch case _: Throwable => ()
    os.makeDir.all(tempRoot / "agents")
    os.write.over(tempRoot / nebflow.shared.Branding.configFileName, "{}")
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tempRoot)

  resetRoot()

  override def beforeEach(context: munit.BeforeEach): Unit = resetRoot()

  /** One assistant turn of plain text — enough to finish the turn with a real answer. */
  private class TextLlm extends LlmHandle[IO]:
    val calls = Ref.unsafe[IO, Int](0)
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(calls.update(_ + 1)) >> Stream(
        StreamChunk.TextDelta("the work is done: E2E-FINAL-TEXT"),
        StreamChunk.Done(None, None)
      )

  private def mkResources(system: ActorSystem, llm: LlmHandle[IO]): IO[SharedResources] =
    for
      dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
      limiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      locks <- FileLockManager.create
      thinking <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      overrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      muted <- Ref.of[IO, Boolean](false)
    yield SharedResources(
      llm = llm,
      dispatcher = dispatcher,
      sessionStore = SessionStore(tempRoot / "sessions", tempRoot / "tasks"),
      projectRoot = os.pwd,
      thinkingConfigRef = thinking,
      rateLimiter = limiter,
      fileChangeTracker = tracker,
      contextWindow = 100_000,
      agentLibrary = new AgentLibrary(tempRoot / "agents"),
      taskStore = FileTaskStore,
      historyArchiver = null,
      fileLockManager = locks,
      sessionModelOverrides = overrides,
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = system,
      voiceMutedRef = muted
    )

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 25.millis)(cond: IO[Boolean]): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError("waitUntil: condition not met in time"))
          else IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  /** Transport that refuses delivery: the completion signal dies at the send. */
  private def failingRef(attempts: Ref[IO, Int]): ActorRef[AgentEvent] =
    new ActorRef[AgentEvent]:
      val path: ActorPath = ActorPath("e2e-failing-supervisor", Nil)
      def !(msg: AgentEvent): IO[Unit] =
        attempts.update(_ + 1) *> IO.raiseError(new RuntimeException("transport down (spec)"))
      def ?[Reply](makeMsg: ActorRef[Reply] => AgentEvent, timeout: Option[FiniteDuration]): IO[Reply] =
        IO.raiseError(new UnsupportedOperationException("failing transport cannot ask"))

  /**
   * Transport that accepts and then never processes: the message lands in a mailbox whose
   * consumer fibre is gone (supervisor killed / process gone). Tell succeeds, nothing handles it.
   */
  private def swallowingRef(accepted: Ref[IO, List[AgentEvent]]): ActorRef[AgentEvent] =
    new ActorRef[AgentEvent]:
      val path: ActorPath = ActorPath("e2e-dead-supervisor", Nil)
      def !(msg: AgentEvent): IO[Unit] = accepted.update(_ :+ msg)
      def ?[Reply](makeMsg: ActorRef[Reply] => AgentEvent, timeout: Option[FiniteDuration]): IO[Reply] =
        IO.raiseError(new UnsupportedOperationException("dead supervisor cannot ask"))

  private def spawnWorker(
    system: ActorSystem,
    resources: SharedResources,
    sid: String,
    parentSid: String,
    target: ActorRef[AgentEvent]
  ): IO[(ActorRef[AgentCommand], SubAgentTask)] =
    for
      agent <- system.spawn(
        AgentActor(
          agentDef = AgentDef(name = "ManagedWorker", description = "e2e managed worker", tools = Nil, systemPrompt = ""),
          resources = resources,
          wsSend = (_: Json) => IO.unit,
          depth = 0,
          parentRef = None,
          sessionId = Some(sid),
          sessionName = Some("ManagedWorker"),
          safetyMode = "auto-all"
        ),
        sid
      )
      task = SubAgentTask(
        taskId = sid,
        parentSessionId = parentSid,
        agentName = "ManagedWorker",
        prompt = "do the work",
        description = "e2e managed run",
        status = "running",
        retryCount = 0,
        spawnedAt = System.currentTimeMillis(),
        completedAt = None,
        lastError = None,
        source = "subtask"
      )
      _ <- resources.subAgentTaskStore.beginRun(task, "run-1", parentSid, "nebflow")
      _ <- resources.agentRegistry.update(
        _.updated(
          sid,
          AgentRecord(
            sessionId = sid,
            ref = agent,
            kind = AgentKind.SubTask,
            rootSessionId = parentSid,
            parentSessionId = parentSid,
            supervisorRef = Some(target)
          )
        )
      )
      row <- resources.subAgentTaskStore.findByTaskId(sid).map(_.getOrElse(fail("row admission must land")))
    yield (agent, row)

  private def mails(parentSid: String): IO[List[SessionMailbox.Entry]] =
    SessionMailbox.scanPending(parentSid).handleErrorWith(_ => IO.pure(Nil))

  // ── E2E-1: the Completed send itself fails ────────────────────────────────

  test("E2E-1 a failed Completed send still terminalizes and delivers from the run-bound debt") {
    val system = ActorSystem("managed-terminal-e2e-sendfail")
    val program = for
      resources <- mkResources(system, new TextLlm)
      parent <- resources.sessionStore.createSession("e2e-sendfail-parent", agentName = Some("Nebula"))
      attempts <- Ref.of[IO, Int](0)
      target = failingRef(attempts)
      (agent, admitted) <- spawnWorker(system, resources, "subtask-e2e-sendfail", parent.id, target)
      _ <- agent ! AgentCommand.UserInput("do the work", Some(target), Some("cmid-sendfail"))
      // the debt is written before the send, so it survives the failed delivery
      _ <- waitUntil(30.seconds)(
        CompletionDebtStore
          .pendingManaged(parent.id, admitted.taskId, "run-1", "subtask")
          .map(_.nonEmpty)
      )
      sendAttempts <- attempts.get
      stranded <- resources.subAgentTaskStore.findByTaskId(admitted.taskId)
      mailboxBefore <- mails(parent.id)
      // process restart: the boot reconciliation sees the debt, not the signal
      _ <- SubAgentStartupRecovery.recoverPendingInternal(
        resources.subAgentTaskStore,
        resources.sessionStore,
        Some(resources)
      )
      after <- resources.subAgentTaskStore.findByTaskId(admitted.taskId)
      mailboxAfter <- mails(parent.id)
      _ <- system.stopAll
    yield
      assertEquals(sendAttempts, 1, "the completion signal was attempted exactly once and failed")
      assertEquals(stranded.flatMap(_.executionStatus), Some("starting"),
        "a failed send must NOT terminalize the row on the child side")
      assertEquals(mailboxBefore.filter(_.identity == s"${admitted.taskId}#run-1"), Nil,
        "nothing was delivered by the failed send")
      assertEquals(after.flatMap(_.executionStatus), Some("finished"),
        "boot reconciliation terminalizes from the debt alone")
      val notices = mailboxAfter.filter(_.identity == s"${admitted.taskId}#run-1")
      assertEquals(notices.size, 1, "exactly one parent notice, minted from the debt")
      assert(notices.head.event.payload.contains("E2E-FINAL-TEXT"),
        s"the recovered notice carries the real final text: ${notices.head.event.payload}")
      assertEquals(notices.head.event.source, "subtask", "the notice keeps the row source")
    program
  }

  // ── E2E-2: the supervisor accepts but never processes it ──────────────────

  test("E2E-2 a completion nobody processes is recovered from the debt at boot") {
    val system = ActorSystem("managed-terminal-e2e-unhandled")
    val program = for
      resources <- mkResources(system, new TextLlm)
      parent <- resources.sessionStore.createSession("e2e-unhandled-parent", agentName = Some("Nebula"))
      accepted <- Ref.of[IO, List[AgentEvent]](Nil)
      target = swallowingRef(accepted)
      (agent, admitted) <- spawnWorker(system, resources, "subtask-e2e-unhandled", parent.id, target)
      _ <- agent ! AgentCommand.UserInput("do the work", Some(target), Some("cmid-unhandled"))
      _ <- waitUntil(30.seconds)(
        CompletionDebtStore
          .pendingManaged(parent.id, admitted.taskId, "run-1", "subtask")
          .map(_.nonEmpty)
      )
      swallowed <- accepted.get
      mailboxBefore <- mails(parent.id)
      stranded <- resources.subAgentTaskStore.findByTaskId(admitted.taskId)
      // boot reconciliation is the only reader of the debt
      _ <- SubAgentStartupRecovery.recoverPendingInternal(
        resources.subAgentTaskStore,
        resources.sessionStore,
        Some(resources)
      )
      after <- resources.subAgentTaskStore.findByTaskId(admitted.taskId)
      mailboxAfter <- mails(parent.id)
      _ <- system.stopAll
    yield
      assertEquals(swallowed.count(_.isInstanceOf[AgentEvent.Completed]), 1,
        "the completion was accepted by a mailbox with no consumer")
      assertEquals(stranded.flatMap(_.executionStatus), Some("starting"),
        "an unprocessed completion leaves the run unresolved on the row")
      assertEquals(mailboxBefore.filter(_.identity == s"${admitted.taskId}#run-1"), Nil,
        "an unprocessed completion delivers nothing")
      assertEquals(after.flatMap(_.executionStatus), Some("finished"),
        "boot reconciliation closes the run from the debt")
      val notices = mailboxAfter.filter(_.identity == s"${admitted.taskId}#run-1")
      assertEquals(notices.size, 1, "exactly one parent notice after recovery")
      assert(notices.head.event.payload.contains("E2E-FINAL-TEXT"),
        s"the recovered notice carries the real final text: ${notices.head.event.payload}")
    program
  }

end ManagedTerminalDeliveryE2ESpec
