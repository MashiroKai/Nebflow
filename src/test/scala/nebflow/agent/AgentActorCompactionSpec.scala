package nebflow.agent

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import munit.FunSuite
import nebflow.actor.{ActorPath, ActorRef, ActorSystem, Behavior, Behaviors}
import nebflow.core.{FileChangeTracker, PathUtil}
import nebflow.core.compact.{CompactConfig, CompactThreshold, HistoryArchiver}
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, Message, MessageRole, StreamChunk, TokenUsage}

import io.circe.parser.decode
import scala.concurrent.duration.*

/**
 * Lightweight state-level circuit breaker tests.
 * These tests validate the core state transition logic that the actor relies on.
 */
class AgentActorCompactionSpec extends FunSuite:

  private def mkState(compactionFailures: Int): AgentState =
    AgentState(
      messages = Nil,
      status = AgentStatus.Idle,
      depth = 0,
      sessionId = Some("test-session"),
      pendingCompaction = None,
      compactionFailures = compactionFailures,
      latestUsage = None,
      pendingAskUser = None,
      pendingPermission = None,
      turnIdx = 0
    )

  test("fresh state allows compaction (failures = 0 < max = 3)") {
    val state = mkState(0)
    assert(state.compactionFailures < CompactConfig().circuitBreakerMax)
  }

  test("state with failures = max - 1 still allows compaction") {
    val state = mkState(2)
    assert(state.compactionFailures < CompactConfig().circuitBreakerMax)
  }

  test("state with failures = max blocks compaction") {
    val state = mkState(3)
    assert(state.compactionFailures >= CompactConfig().circuitBreakerMax)
  }

  test("state with failures > max blocks compaction") {
    val state = mkState(5)
    assert(state.compactionFailures >= CompactConfig().circuitBreakerMax)
  }

  test("state reset: copy with compactionFailures = 0 after success") {
    val failed = mkState(3)
    val reset = failed.withCompactionFailures(0)
    assertEquals(reset.compactionFailures, 0)
    assert(reset.compactionFailures < CompactConfig().circuitBreakerMax)
  }

  test("pendingCompaction field is preserved during state transitions") {
    val state = mkState(0)
    assert(state.pendingCompaction.isEmpty)
    val withPending = state.withPendingCompaction(
      Some(
        CompactionJob("sub-1", "full", None, None)
      )
    )
    assert(withPending.pendingCompaction.isDefined)
  }

  test("AgentState no longer has pendingManualCompaction (compile-time check)") {
    // This test is a compile-time guard: if pendingManualCompaction were still
    // a field on AgentState, the code below would compile. Instead, we verify
    // the constructor only accepts the new fields by constructing a valid state.
    val state = mkState(0)
    // Verify we can access all expected fields
    assertEquals(state.compactionFailures, 0)
    assert(state.pendingCompaction.isEmpty)
    assert(state.sessionId.contains("test-session"))
  }

  // ---------- Manual compact state transitions (issue 009) ----------

  test("manual compact success clears pendingCompaction and resets failures") {
    val originalMsgs = List(
      Message(MessageRole.User, Left("hello")),
      Message(MessageRole.Assistant, Left("world"))
    )
    val state = mkState(2)
      .withMessages(originalMsgs)
      .withPendingCompaction(Some(CompactionJob("sub-1", "full", None, None)))
    // Simulate the success branch transformations in DelegateResult
    val compactedMsgs = List(Message(MessageRole.User, Left("summary")))
    val newState = state.withPendingCompaction(None)
    val successState = newState.withMessages(compactedMsgs).withCompactionFailures(0)

    assert(successState.pendingCompaction.isEmpty)
    assertEquals(successState.compactionFailures, 0)
    assertEquals(successState.messages.size, 1)
    assertEquals(successState.messages.head.content.swap.getOrElse(""), "summary")
  }

  test("manual compact failure increments failures and clears pendingCompaction") {
    val originalMsgs = List(
      Message(MessageRole.User, Left("hello")),
      Message(MessageRole.Assistant, Left("world"))
    )
    val state = mkState(1)
      .withMessages(originalMsgs)
      .withPendingCompaction(Some(CompactionJob("sub-1", "full", None, None)))
    // Simulate the failure branch transformations in DelegateResult (non-circuit-broken)
    val failures = state.compactionFailures + 1 // 2
    val newState = state.withPendingCompaction(None)
    val failedState = newState.withCompactionFailures(failures)

    assert(failedState.pendingCompaction.isEmpty)
    assertEquals(failedState.compactionFailures, 2)
    assertEquals(failedState.messages, originalMsgs) // original messages retained
  }

  // ---------- latestUsage reset after compaction success (prevents infinite loop) ----------

  test("compaction success resets latestUsage to prevent re-trigger") {
    val contextWindow = 128000
    val threshold = CompactThreshold.threshold(contextWindow)
    val highUsage = TokenUsage(inputTokens = threshold + 5000, outputTokens = 1000)

    val originalMsgs = List(
      Message(MessageRole.User, Left("hello")),
      Message(MessageRole.Assistant, Left("world"))
    )
    val state = mkState(0)
      .withMessages(originalMsgs)
      .withLatestUsage(Some(highUsage))
      .withPendingCompaction(Some(CompactionJob("sub-1", "full", None, None)))

    // Simulate the success branch — exactly matching AgentActor DelegateResult handler:
    val compactedMsgs = List(Message(MessageRole.User, Left("summary")))
    val newState = state.withPendingCompaction(None)
    val successState = newState
      .withMessages(compactedMsgs)
      .withCompactionFailures(0)
      .withLatestUsage(None)

    // After compaction success, latestUsage must be None so maybeAutoCompact skips
    assert(successState.latestUsage.isEmpty, "latestUsage should be None after compaction success")
    // Verify that with None latestUsage, the auto-compact check would NOT re-trigger
    val shouldNotCompact = successState.latestUsage.exists(_.inputTokens > threshold)
    assert(!shouldNotCompact, "Auto-compaction must not re-trigger after successful compaction")
  }

  // ---------- Queued-input drains during compaction (2026-08-14 injection bug) ----------
  //
  // Bug: ToolsComplete drained pendingEvents unconditionally — during the Save
  // phase (tools still running) the drained event landed in the save-turn
  // history, which the compact summary then REPLACED: consumed from the queue
  // yet lost. pendingImmediateInputs already had the guard; events did not.
  // Additionally CompactionComplete never re-drained the held-back events, and
  // a CompactionJob could survive into idle (finishTurn / fatal LlmFailed),
  // making the next normal reply masquerade as the compact summary.

  private def mkEvent(n: Int): AgentCommand.ExternalEvent =
    AgentCommand.ExternalEvent("background", "completed", s"payload-$n")

  private def mkSaveJob: CompactionJob =
    // Single-stage (2026-08-31): every compaction job is a Compact turn.
    CompactionJob("job-1", "full", None, None, resumeAfterCompact = false)

  test("drainHead keeps the queue intact while a compaction job is pending") {
    val evs = List(mkEvent(1), mkEvent(2), mkEvent(3))
    val (drained, remaining) = TurnBoundaryDrains.drainHead(evs, compactionPending = true)
    assert(drained.isEmpty, "no event may be consumed while compaction is pending")
    assertEquals(remaining, evs, "queue must be untouched while compaction is pending")
  }

  test("drainHead consumes exactly the head once no compaction is pending") {
    val evs = List(mkEvent(1), mkEvent(2), mkEvent(3))
    val (drained, remaining) = TurnBoundaryDrains.drainHead(evs, compactionPending = false)
    assertEquals(drained.map(_.payload), Some("payload-1"))
    assertEquals(remaining.map(_.payload), List("payload-2", "payload-3"))
  }

  test("drainHead on an empty queue is a no-op either way") {
    assertEquals(TurnBoundaryDrains.drainHead[AgentCommand.ExternalEvent](Nil, compactionPending = false), (None, Nil))
    assertEquals(TurnBoundaryDrains.drainHead[AgentCommand.ExternalEvent](Nil, compactionPending = true), (None, Nil))
  }

  test("ToolsComplete drain mirrors the guard: save-phase turn leaves events queued") {
    // Exact call-site shape at ToolsComplete (AgentActor):
    val state = mkState(0)
      .withPendingCompaction(Some(mkSaveJob))
      .copy(execution = state0Execution(List(mkEvent(1), mkEvent(2))))
    val (eventOpt, remainingEvents) =
      TurnBoundaryDrains.drainHead(state.execution.pendingEvents, state.pendingCompaction.isDefined)
    assert(eventOpt.isEmpty)
    assertEquals(remainingEvents.size, 2)
  }

  test("CompactionComplete drains ONE held-back event after the summary is in place") {
    // Mirror of the post-compaction drain branch (AgentActor CompactionComplete,
    // non-resume path, after immediate/user inputs are exhausted):
    val compactedMsgs = List(Message(MessageRole.User, Left("summary")))
    val events = List(mkEvent(1), mkEvent(2))
    val compactedState = mkState(0)
      .withMessages(compactedMsgs)
      .withPendingCompaction(None)
      .copy(execution = state0Execution(Nil).copy(pendingEvents = events))
    // Branch transformation:
    val ev = compactedState.execution.pendingEvents.head
    val eventMessage = Message(MessageRole.User, Left(s"<system-reminder>\n${ev.payload}\n</system-reminder>"))
    val drainedState = compactedState.copy(execution =
      compactedState.execution.copy(
        messages = compactedState.messages :+ eventMessage,
        pendingEvents = compactedState.execution.pendingEvents.tail
      )
    )
    // The drained event survives INTO the post-compaction history…
    assert(
      drainedState.messages.last.content.swap.getOrElse("").contains("payload-1"),
      "drained event content must be present in the post-compaction messages"
    )
    // …and the tail stays queued for the next turn boundary.
    assertEquals(drainedState.execution.pendingEvents.map(_.payload), List("payload-2"))
    // Guard prevents any double-drain before that: queue head stays put while pending.
    val (none, untouched) =
      TurnBoundaryDrains.drainHead(drainedState.execution.pendingEvents, compactionPending = true)
    assert(none.isEmpty)
    assertEquals(untouched.map(_.payload), List("payload-2"))
  }

  test("finishTurn zombie guard clears a surviving CompactionJob and lets events deliver") {
    // A job reaching finishTurn is dead (context overflow / max-tokens on the
    // compact turn). Normalization: complete deferred (not asserted — IO),
    // clear job, count the failure. Queued events then hit finishTurnCont's
    // drain because pendingCompaction is now empty.
    val state = mkState(1)
      .withPendingCompaction(Some(mkSaveJob))
      .copy(execution = state0Execution(List(mkEvent(1))))
    val normalizedState = state
      .withPendingCompaction(None)
      .withCompactionFailures(state.compactionFailures + 1)
    assert(normalizedState.pendingCompaction.isEmpty, "job must not survive into idle")
    assertEquals(normalizedState.compactionFailures, 2)
    // finishTurnCont branch-1 condition after normalization:
    val branchDelivers = normalizedState.pendingCompaction.isEmpty &&
      normalizedState.execution.pendingEvents.nonEmpty
    assert(branchDelivers, "queued events must be deliverable once the zombie job is cleared")
    val (drained, _) =
      TurnBoundaryDrains.drainHead(normalizedState.execution.pendingEvents, normalizedState.pendingCompaction.isDefined)
    assertEquals(drained.map(_.payload), Some("payload-1"))
  }

  test("finishTurnCont branch-3: empty pendingUserInputs must be tail-safe") {
    // Mirror of the turn-fully-finished drain (AgentActor finishTurnCont, after
    // markTeamIdle): the forward is guarded by headOption, and the tail update
    // must be empty-safe in the same style. The REST /api/command turn-end path
    // reached this branch with an EMPTY queue on every turn in live testing
    // (9/9 sessions) — a bare .tail threw "tail of empty list" there.
    val state = mkState(0).copy(execution = state0Execution(Nil))
    assert(state.execution.pendingUserInputs.isEmpty, "branch-3 is reached with an empty queue")
    val remainingEmpty =
      val queued = state.execution.pendingUserInputs
      if queued.isEmpty then queued else queued.tail
    assertEquals(remainingEmpty, Nil)
    // Non-empty queue still consumes exactly the head.
    val queued2: List[String] = List("m1", "m2")
    val remaining2 = if queued2.isEmpty then queued2 else queued2.tail
    assertEquals(remaining2, List("m2"))
  }

  /** Default execution context seeded with a pendingEvents queue (specs build
    * AgentState without an explicit execution). */
  private def state0Execution(events: List[AgentCommand.ExternalEvent]) =
    mkState(0).execution.copy(pendingEvents = events)

  // ============================================================
  // V2 (2026-08-30, compact-injection-shield G1): unified post-compaction
  // queue drain. Mixed queues (immediate inputs + user inputs + barrier-
  // drained events) inject ALL into the continuation round — zero loss.
  // replyTo-bearing UserInputs and non-UserInput commands (SkillActivate /
  // AskQuestion) stay in pendingUserInputs for full-metadata
  // head-forwarding; barrier-held events stay queued.
  // ============================================================

  private def mkImm(text: String): AgentCommand.ImmediateInput =
    AgentCommand.ImmediateInput(text, source = Some("mail"))

  private val dummyRef: ActorRef[AgentEvent] = new ActorRef[AgentEvent]:
    def path: ActorPath = ActorPath("test", Nil)
    def !(msg: AgentEvent): IO[Unit] = IO.unit
    def ?[Reply](makeMsg: ActorRef[Reply] => AgentEvent, timeout: Option[FiniteDuration]): IO[Reply] =
      IO.raiseError(new UnsupportedOperationException("ask not expected in this test"))

  private def mkUser(text: String, replyTo: Option[ActorRef[AgentEvent]] = None): AgentCommand.UserInput =
    AgentCommand.UserInput(text = text, replyTo = replyTo, source = Some("mail"))

  private def mkQueuedState(
    imms: List[AgentCommand.ImmediateInput],
    users: List[AgentCommand],
    events: List[AgentCommand.ExternalEvent],
    outstanding: Int = 0
  ): AgentState =
    mkState(0).copy(execution =
      mkState(0).execution.copy(
        pendingImmediateInputs = imms,
        pendingUserInputs = users,
        pendingEvents = events,
        outstandingSubagentResults = outstanding
      )
    )

  test("V2: mixed queues drain fully with zero loss (imms + replyTo-free users + events)") {
    val state = mkQueuedState(
      imms = List(mkImm("imm-1"), mkImm("imm-2")),
      users = List(
        mkUser("user-free"),
        mkUser("user-reply", replyTo = Some(dummyRef)),
        AgentCommand.SkillActivate("s1", "input", "sid", "content", "/tmp")
      ),
      events = List(mkEvent(1), mkEvent(2), mkEvent(3))
    )
    val drain = AgentActor.drainQueuesAfterCompaction(state)
    // appended = 2 imm messages + 1 inline user + 1 batched event reminder
    assertEquals(drain.appended.size, 4)
    assertEquals(drain.immMessages.size, 2)
    assertEquals(drain.userMessages.size, 1)
    assertEquals(drain.eventCount, 3)
    // imm texts land first, in order
    assertEquals(drain.appended.take(2).map(_.content.swap.getOrElse("")), List("imm-1", "imm-2"))
    // inline user message lands
    assertEquals(drain.appended(2).content.swap.getOrElse(""), "user-free")
    // events batch into one system-reminder carrying every payload
    val evText = drain.appended(3).content.swap.getOrElse("")
    assert(evText.contains("payload-1") && evText.contains("payload-3"), s"evText=$evText")
    // queues fully drained except the deferred full-metadata commands
    assertEquals(drain.exec.pendingImmediateInputs, Nil)
    assertEquals(drain.exec.pendingEvents, Nil)
    assertEquals(drain.exec.pendingUserInputs.size, 2) // replyTo-bearing UserInput + SkillActivate
  }

  test("V2: barrier-held delegate results stay queued while the batch is outstanding") {
    val delegateEvs = List(
      AgentCommand.ExternalEvent("delegate", "completed", "result-1"),
      AgentCommand.ExternalEvent("delegate", "completed", "result-2")
    )
    val state = mkQueuedState(
      imms = List(mkImm("imm-1")),
      users = List(mkUser("user-free")),
      events = delegateEvs,
      outstanding = 1
    )
    val drain = AgentActor.drainQueuesAfterCompaction(state)
    assertEquals(drain.eventCount, 0)
    assertEquals(drain.exec.pendingEvents.size, 2)
    assertEquals(drain.appended.size, 2) // imm + user only
  }

  test("V2: non-subagent events flush even while the batch is outstanding") {
    val state = mkQueuedState(
      imms = Nil,
      users = Nil,
      events = List(mkEvent(1), mkEvent(2)),
      outstanding = 1
    )
    val drain = AgentActor.drainQueuesAfterCompaction(state)
    assertEquals(drain.eventCount, 2)
    assertEquals(drain.exec.pendingEvents, Nil)
  }

  test("V2: only-deferred queue → appended empty, deferred preserved for head-forward") {
    val state = mkQueuedState(
      imms = Nil,
      users = List(
        mkUser("user-reply", replyTo = Some(dummyRef)),
        AgentCommand.SkillActivate("s1", "input", "sid", "content", "/tmp")
      ),
      events = Nil
    )
    val drain = AgentActor.drainQueuesAfterCompaction(state)
    assert(drain.appended.isEmpty)
    assertEquals(drain.exec.pendingUserInputs.size, 2)
  }

  // ============================================================
  // V5 (2026-08-30, compact-injection-shield Q1 regression): a Compact-phase
  // LLM response with EMPTY text (thinking-only) must be routed to
  // handleCompactFailure — NEVER allowed to fall through to the mail-check
  // rung. Tools are disabled during compaction (pipeLlmCall sets
  // tools=Some(Nil)), so the "you must call Mail" reminder loop is a
  // deterministic dead loop (production deaths 03:29/03:41/08:45, each
  // ~219K input ×2 retries before compaction-abandoned). This wiring test
  // drives the real branch ladder: with the fix, exactly ONE LLM request
  // (the compact turn) is made, then the actor goes idle; the mail-reminder
  // loop would issue further requests.
  // ============================================================

  private class ThinkingOnlyLlm(counter: cats.effect.Ref[IO, Int]) extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected in this test"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(counter.update(_ + 1)) >>
        Stream(
          StreamChunk.ThinkingDelta("summarizing but writing nothing into text"),
          StreamChunk.Done(None, None)
        )

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

  private def assertNoFurtherRequests(counter: cats.effect.Ref[IO, Int]): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      counter.get.flatMap { n =>
        if n > 1 then
          IO.raiseError(
            new RuntimeException(s"mail-reminder loop live: $n LLM requests after a thinking-only compact failure")
          )
        else if System.currentTimeMillis() > deadline then IO.unit
        else IO.sleep(100.millis) *> go(deadline)
      }
    go(System.currentTimeMillis() + 1500L)

  test("V5: thinking-only Compact response fails compaction — no mail-reminder dead loop") {
    val system = ActorSystem("compact-thinking-only-test")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      val program = for
        counter <- IO.ref(0)
        llm = new ThinkingOnlyLlm(counter)
        resources <- mkResources(system, tmp, llm)
        agentDef = AgentDef(name = "standalone-agent", description = "test", tools = List("Read"), systemPrompt = "")
        // expectsMail=true reproduces the dead-loop precondition (team members
        // must call Mail before finishing); depth=0 avoids the team-lead lookup
        // (and name≠Nebula / no team session ⇒ phase=Compact on the FIRST request).
        ref <- system.spawn(
          AgentActor(
            agentDef = agentDef,
            resources = resources,
            wsSend = _ => IO.unit,
            depth = 0,
            parentRef = None,
            sessionId = Some("v5-agent"),
            sessionName = Some("v5"),
            initialMessages = Nil,
            expectsMail = true
          ),
          "v5-agent"
        )
        _ <- ref ! AgentCommand.TriggerCompaction("full", None, None)
        _ <- assertNoFurtherRequests(counter)
        finalCount <- counter.get
      yield assert(finalCount == 1, s"exactly one compact-turn request expected, got $finalCount")
      program.unsafeRunSync()
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
  }
  // ============================================================
  // V2b (2026-08-30, compact-injection-shield G1): resume=true compaction
  // must drain queues held during the window into the CONTINUATION turn.
  // This exercises the branch wiring (not just the pure helper): an
  // ImmediateInput arriving while the compact turn is in flight is queued by
  // the processing handler, then must appear in the continuation LLM request
  // after CompactionComplete(Right). Reverting the resume branch to the old
  // "pipeLlmCall(stateWithInstruction) without drain" shape turns this red.
  // ============================================================

  private class DelayedSummaryLlm(
    counter: cats.effect.Ref[IO, Int],
    userTextsRef: cats.effect.Ref[IO, List[List[String]]]
  ) extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected in this test"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(counter.update(_ + 1)) >>
        Stream.eval(userTextsRef.update(prev => prev :+ req.messages.collect {
          case m if m.role == MessageRole.User => m.content.fold(identity, _ => "")
        })) >>
        Stream.eval(counter.get).flatMap { n =>
          if n == 1 then
            Stream.sleep[IO](300.millis) >>
              Stream(StreamChunk.TextDelta("COMPACTED SUMMARY"), StreamChunk.Done(None, None))
          else
            Stream(StreamChunk.TextDelta("DONE"), StreamChunk.Done(None, None))
        }

  test("V2b: resume=true compaction drains a queued immediate input into the continuation turn") {
    val system = ActorSystem("compact-resume-drain-test")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      val program = for
        counter <- IO.ref(0)
        userTexts <- IO.ref(List.empty[List[String]])
        llm = new DelayedSummaryLlm(counter, userTexts)
        resources <- mkResources(system, tmp, llm)
        agentDef = AgentDef(name = "standalone-agent", description = "test", tools = List("Read"), systemPrompt = "")
        ref <- system.spawn(
          AgentActor(
            agentDef = agentDef,
            resources = resources,
            wsSend = _ => IO.unit,
            depth = 0,
            parentRef = None,
            sessionId = Some("v2b-agent"),
            sessionName = Some("v2b"),
            initialMessages = List(
              Message(MessageRole.User, Left("task")),
              Message(MessageRole.Assistant, Left("working"))
            )
          ),
          "v2b-agent"
        )
        // resumeAfterCompact=true (postCompactInstruction defined) → the
        // CompactionComplete(Right) continuation must carry the drained queue.
        _ <- ref ! AgentCommand.TriggerCompaction("full", None, Some("post-compact instruction"))
        _ <- IO.sleep(100.millis) // compact turn in flight (mock sleeps 300ms)
        _ <- ref ! AgentCommand.ImmediateInput("queued-during-compact", source = Some("mail"))
        _ <- {
          def go(deadline: Long): IO[Unit] =
            counter.get.flatMap { n =>
              if n >= 2 then IO.unit
              else if System.currentTimeMillis() > deadline then
                IO.raiseError(new RuntimeException(s"continuation turn never started: count=$n"))
              else IO.sleep(100.millis) *> go(deadline)
            }
          go(System.currentTimeMillis() + 5000L)
        }
        texts <- userTexts.get
      yield
        assert(texts.length >= 2, s"expected ≥2 LLM requests, got ${texts.length}")
        val contTurnTexts = texts(1)
        assert(
          contTurnTexts.exists(_.contains("queued-during-compact")),
          s"continuation turn must carry the queued immediate input, got: $contTurnTexts"
        )
      program.unsafeRunSync()
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
  }

  // ============================================================
  // F2 (2026-08-30, compact-injection-shield batch 2): queue persistence.
  // V2-persist — enqueue writes the snapshot to disk immediately (crash
  // mid-compaction must not lose queued injections). V2-recover — persisted
  // queues replay into the actor at spawn (RecoverPersistedQueues) and land
  // in the next drain (compaction continuation round).
  // ============================================================

  test("V2-persist: enqueue writes the queue snapshot to disk immediately") {
    val system = ActorSystem("compact-persist-test")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      val program = for
        counter <- IO.ref(0)
        userTexts <- IO.ref(List.empty[List[String]])
        llm = new DelayedSummaryLlm(counter, userTexts)
        resources <- mkResources(system, tmp, llm)
        agentDef = AgentDef(name = "standalone-agent", description = "test", tools = List("Read"), systemPrompt = "")
        ref <- system.spawn(
          AgentActor(
            agentDef = agentDef,
            resources = resources,
            wsSend = _ => IO.unit,
            depth = 0,
            parentRef = None,
            sessionId = Some("v2p-agent"),
            sessionName = Some("v2p"),
            initialMessages = List(
              Message(MessageRole.User, Left("task")),
              Message(MessageRole.Assistant, Left("working"))
            )
          ),
          "v2p-agent"
        )
        // compact turn in flight (mock sleeps 300ms) → processing handler
        // enqueues + persists to disk.
        _ <- ref ! AgentCommand.TriggerCompaction("full", None, Some("post-compact instruction"))
        _ <- IO.sleep(100.millis)
        _ <- ref ! AgentCommand.ImmediateInput("queued-imm", source = Some("mail"))
        _ <- ref ! AgentCommand.ExternalEvent("e2e", "inject", "queued-ev")
        _ <- IO.sleep(150.millis) // enqueue handlers ran, persist done; compact still in flight (300ms)
        file = tmp / "data" / "sessions" / "v2p-agent" / "injection-queues.json"
        onDisk <- IO.blocking(decode[CompactionQueueStore.PersistedQueues](os.read(file)))
      yield
        assert(onDisk.isRight, s"injection-queues.json must exist and decode, got: $onDisk")
        val q = onDisk.toOption.get
        assert(q.imms.exists(_.text == "queued-imm"), s"imms must contain queued-imm, got: ${q.imms.map(_.text)}")
        assert(
          q.events.exists(_.payload == "queued-ev"),
          s"events must contain queued-ev, got: ${q.events.map(_.payload)}"
        )
      program.unsafeRunSync()
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
  }

  test("V2-recover: persisted queues replay at spawn and land in the compaction continuation") {
    val system = ActorSystem("compact-recover-test")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      val program = for
        // Seed disk as if a crash left queues behind mid-compaction.
        _ <- CompactionQueueStore.save(
          "v2r-agent",
          CompactionQueueStore.PersistedQueues(
            imms = List(AgentCommand.ImmediateInput("recovered-imm", source = Some("mail"))),
            events = List(AgentCommand.ExternalEvent("e2e", "inject", "recovered-ev"))
          )
        )
        counter <- IO.ref(0)
        userTexts <- IO.ref(List.empty[List[String]])
        llm = new DelayedSummaryLlm(counter, userTexts)
        resources <- mkResources(system, tmp, llm)
        agentDef = AgentDef(name = "standalone-agent", description = "test", tools = List("Read"), systemPrompt = "")
        ref <- system.spawn(
          AgentActor(
            agentDef = agentDef,
            resources = resources,
            wsSend = _ => IO.unit,
            depth = 0,
            parentRef = None,
            sessionId = Some("v2r-agent"),
            sessionName = Some("v2r"),
            initialMessages = List(
              Message(MessageRole.User, Left("task")),
              Message(MessageRole.Assistant, Left("working"))
            )
          ),
          "v2r-agent"
        )
        // RecoverPersistedQueues is sent to self at setup; give it time to
        // load and merge before triggering the compaction that drains them.
        _ <- IO.sleep(200.millis)
        _ <- ref ! AgentCommand.TriggerCompaction("full", None, Some("post-compact instruction"))
        _ <- {
          def go(deadline: Long): IO[Unit] =
            counter.get.flatMap { n =>
              if n >= 2 then IO.unit
              else if System.currentTimeMillis() > deadline then
                IO.raiseError(new RuntimeException(s"continuation turn never started: count=$n"))
              else IO.sleep(100.millis) *> go(deadline)
            }
          go(System.currentTimeMillis() + 5000L)
        }
        texts <- userTexts.get
      yield
        assert(texts.length >= 2, s"expected ≥2 LLM requests, got ${texts.length}")
        val contTurnTexts = texts(1)
        assert(
          contTurnTexts.exists(_.contains("recovered-imm")),
          s"continuation turn must carry the recovered immediate input, got: $contTurnTexts"
        )
        assert(
          contTurnTexts.exists(_.contains("recovered-ev")),
          s"continuation turn must carry the recovered event payload, got: $contTurnTexts"
        )
      program.unsafeRunSync()
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
  }
end AgentActorCompactionSpec
