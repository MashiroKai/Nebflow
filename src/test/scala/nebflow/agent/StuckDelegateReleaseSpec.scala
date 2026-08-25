package nebflow.agent

import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, ActorSystem}
import nebflow.core.FileChangeTracker
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.processor.TaskStuckWatcher
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore, WsHub}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * issue #31 (2026-08-20): stuck delegate releases the parent barrier.
 *
 * Topology:
 *
 *   root Nebula ──turn 1──▶ two parallel Delegates: Fast (completes) + Stall
 *                            (LLM stream NEVER produces — the incident shape:
 *                            fiber parked before any watchdog could arm)
 *
 *   Fast completes while Stall is outstanding. Pre-#418 the idle parent HELD
 *   the result (outstanding 2→1, result parked in pendingEvents, NO turn
 *   starts — UI shows COMPLETED but the parent sleeps). #418 (2026-08-26): an
 *   idle parent NEVER holds — the Fast result wakes the root immediately as
 *   its own injection turn; only the stall remains outstanding.
 *
 *   Stall never terminates on its own: Stop (mailbox, never consumed while
 *   suspended on the LLM fiber) and hard-cancel (StuckAbort on a fiber parked
 *   in a non-cancellable wait) were BOTH ineffective in the incident. Fix A:
 *   after escalate, the next scans give up via the supervisor's Cancelled
 *   branch (same path as AgentControl cancel) — the parent's barrier slot is
 *   returned, the stall cancellation reaches the root, and the barrier hits 0.
 *
 * Assertions (report §4.2, updated for #418):
 *   (a) the Fast result wakes the idle root IMMEDIATELY (#418, own turn);
 *       the stall give-up cancellation reaches the ROOT as its own turn
 *   (b) root barrier returns to 0 (registry snapshot, Fix D)
 *   (c) no phantom residue: a later single delegate completes → immediate turn
 */
class StuckDelegateReleaseSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val FastMarker = "FAST-RESULT-MARKER-31a"
  private val Fast2Marker = "FAST2-RESULT-MARKER-31b"
  private val RootFastWakeMarker = "ROOT-FAST-WAKE-418"
  private val RootCancelMarker = "ROOT-CANCEL-TURN-31"
  private val RootFinalMarker = "ROOT-FINAL-TURN-31"
  private val AMarker = "TWIN-A-MARKER-418"
  private val BMarker = "TWIN-B-MARKER-418"

  /** Session-routed mock LLM. delegate-Stall-* NEVER produces a chunk — the
    * turn fiber parks on Stream.never, exactly the incident's parked fiber. */
  private class StuckLlm(
    rootSid: String,
    counters: Ref[IO, Map[String, Int]],
    requests: Ref[IO, List[LlmRequest]]
  ) extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected in this test"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(
        counters.update(m => m.updated(req.sessionId, m.getOrElse(req.sessionId, 0) + 1)) *>
          requests.update(_ :+ req)
      ) >>
        Stream.eval(counters.get.map(_.getOrElse(req.sessionId, 0))).flatMap { n =>
          if req.sessionId == rootSid then rootTurn(req)
          else if req.sessionId.startsWith("delegate-Stall") then Stream.never[IO]
          else if req.sessionId.startsWith("delegate-Fast2") then
            Stream(StreamChunk.TextDelta(s"second report $Fast2Marker"), StreamChunk.Done(None, None))
          else if req.sessionId.startsWith("delegate-Fast") then
            Stream(StreamChunk.TextDelta(s"fast report $FastMarker"), StreamChunk.Done(None, None))
          else Stream(StreamChunk.TextDelta("unexpected session"), StreamChunk.Done(None, None))
        }

    // Turn shape note: a tool-calling turn makes MULTIPLE LLM requests (one
    // per loop round). Round 1 emits the toolcalls, round 2 receives the tool
    // results and ends the turn. Injection turns (batch/single external-event
    // wake) are single-round. Content-driven routing (NOT request-count
    // matching): which path Fast's completion takes (immediate #418 idle wake
    // vs held to the cancellation batch) is a timing race, so the request
    // numbering is path-dependent — route on the request's message content.
    private def rootTurn(req: LlmRequest): Stream[IO, StreamChunk] =
      val texts = req.messages.map(_.textContent).mkString(" ")
      if req.messages.size <= 2 then
        // Turn 1 round 1: spawn the two-worker batch (Fast + Stall).
        Stream(
          StreamChunk.ToolCallChunk(nebflow.shared.ToolCall(
            id = "tc-del-fast",
            name = "Delegate",
            input = JsonObject(
              "prompt" -> "finish quickly and report".asJson,
              "description" -> "fast worker".asJson,
              "agent" -> "Fast".asJson
            )
          )),
          StreamChunk.ToolCallChunk(nebflow.shared.ToolCall(
            id = "tc-del-stall",
            name = "Delegate",
            input = JsonObject(
              "prompt" -> "hang forever on your LLM call".asJson,
              "description" -> "stalled worker".asJson,
              "agent" -> "Stall".asJson
            )
          )),
          StreamChunk.Done(None, None)
        )
      else if texts.contains("now a single one") then
        // Phantom probe: spawn a single follow-up delegate (Fast2).
        Stream(
          StreamChunk.ToolCallChunk(nebflow.shared.ToolCall(
            id = "tc-del-fast2",
            name = "Delegate",
            input = JsonObject(
              "prompt" -> "single follow-up, report immediately".asJson,
              "description" -> "phantom probe".asJson,
              "agent" -> "Fast2".asJson
            )
          )),
          StreamChunk.Done(None, None)
        )
      else if texts.contains(FastMarker) || texts.contains("cancelled") then
        // Injection turn: Fast result (immediate #418 wake) and/or the stall
        // cancellation — reply and end the turn.
        Stream(StreamChunk.TextDelta(s"$RootFastWakeMarker: saw an injection"), StreamChunk.Done(None, None))
      else
        // Round 2 of a tool turn (tool results) — end the turn.
        Stream(StreamChunk.TextDelta("batch launched, standing by"), StreamChunk.Done(None, None))
  end StuckLlm

  private def mkResources(
    system: ActorSystem,
    tmp: os.Path,
    llm: LlmHandle[IO]
  ): IO[SharedResources] =
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

  private val nebulaDef: AgentDef =
    AgentDef(
      name = "Nebula",
      description = "root scheduler under test",
      tools = List("Read", "Delegate"),
      systemPrompt = ""
    )

  /** loadCurrentDef reloads from disk every turn — pin the tool sets. */
  private def seedAgents(tmp: os.Path): Unit =
    val nebulaDir = tmp / "agents" / "Nebula"
    os.makeDir.all(nebulaDir)
    os.write.over(nebulaDir / "agent.json",
      """{"name":"Nebula","displayName":"Nebula","description":"e2e root","tools":["Read","Delegate"]}"""
    )
    for name <- List("Fast", "Stall", "Fast2", "TwinA", "TwinB") do
      val dir = tmp / "agents" / name
      os.makeDir.all(dir)
      os.write.over(dir / "agent.json",
        s"""{"name":"$name","displayName":"$name","description":"e2e worker","tools":["Read"]}"""
      )

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 100.millis)(
    cond: IO[Boolean]
  ): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true  => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError(s"waitUntil: condition not met within $timeout"))
          else IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  test("issue #31: stuck delegate releases the parent barrier — held results inject, root fires, no phantom") {
    val system = ActorSystem("stuck-delegate-release")
    val tmp = os.temp.dir()
    seedAgents(tmp)
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      val rootSid = "e2e-stuck-release-root"
      val program = for
        counters <- IO.ref(Map.empty[String, Int])
        requests <- IO.ref(List.empty[LlmRequest])
        resources <- mkResources(system, tmp, StuckLlm(rootSid, counters, requests))
        rootRef <- system.spawn(
          AgentActor(
            agentDef = nebulaDef,
            resources = resources,
            wsSend = _ => IO.unit,
            depth = 0,
            sessionId = Some(rootSid),
            sessionName = Some("e2e-stuck-release")
          ),
          rootSid
        )
        _ <- resources.agentRegistry.update(
          _ + (rootSid -> AgentRecord(rootSid, rootRef, AgentKind.Root, rootSid, None))
        )
        _ <- rootRef ! AgentCommand.UserInput("run the two-worker batch", None, Some("cid-1"))
        // Both delegates spawned; Fast finishes and is deregistered (HOLD fired).
        // Fast2 does not exist yet (turn 3 has not run), so "no delegate-Fast*"
        // unambiguously means the original Fast has completed and been removed.
        _ <- waitUntil(15.seconds)(
          resources.agentRegistry.get.map { m =>
            m.keys.exists(_.startsWith("delegate-Stall")) &&
            !m.keys.exists(_.startsWith("delegate-Fast"))
          }
        )
        // Grab the stall sid NOW — the supervisor removes it on give-up below.
        registry0 <- resources.agentRegistry.get
        stallSid = registry0.keys.find(_.startsWith("delegate-Stall")).get
        // (a) Fast's result eventually reaches the ROOT — either injected
        // immediately by the #418 idle-wake path, or held to the batch turn
        // when its completion raced ahead of turn 1 ending (processing path).
        // Which path is a timing race — assert both markers AFTER the watcher
        // give-up below (a held Fast only appears with the cancellation). The
        // deterministic idle-wake assertion lives in the twin-delegate test.
        // Fix A: drive the watcher. Scan #1-2 send Stop (ignored — suspended),
        // #3 escalates (cancelInflight misses: fixture LLM is not in the
        // registry — mirrors the incident where hard-cancel was ineffective),
        // #4 gives up via supervisor Cancelled → barrier released → batch turn.
        stopCounts <- Ref.of[IO, Map[String, Int]](Map.empty)
        _ <- TaskStuckWatcher.scan(resources, new WsHub(), 1L, stopCounts)
        _ <- TaskStuckWatcher.scan(resources, new WsHub(), 1L, stopCounts)
        _ <- TaskStuckWatcher.scan(resources, new WsHub(), 1L, stopCounts)
        _ <- TaskStuckWatcher.scan(resources, new WsHub(), 1L, stopCounts)
        // (a) Fast's result reaches the ROOT turn (immediate #418 wake or held
        // batch — path-dependent, both valid).
        _ <- waitUntil(15.seconds)(
          requests.get.map(reqs =>
            reqs.exists(r => r.sessionId == rootSid && r.messages.exists(_.textContent.contains(FastMarker)))
          )
        )
        // The stall give-up cancellation reaches the ROOT as its own turn.
        _ <- waitUntil(15.seconds)(
          requests.get.map(reqs =>
            reqs.exists(r => r.sessionId == rootSid && r.messages.exists(_.textContent.contains("cancelled")))
          )
        )
        // (b) barrier returned to 0 and nothing is held (Fix D registry snapshot).
        _ <- waitUntil(10.seconds)(
          resources.agentRegistry.get.map(m => m.get(rootSid).exists(r => r.outstandingSubagents == 0 && r.pendingEventCount == 0))
        )
        // Stall deregistered + task file says cancelled (supervisor Cancelled branch).
        _ <- waitUntil(10.seconds)(
          resources.agentRegistry.get.map(m => !m.keys.exists(_.startsWith("delegate-Stall")))
        )
        _ <- resources.subAgentTaskStore.findByTaskId(stallSid).map {
          case Some(t) => assertEquals(t.status, "cancelled", s"stall task must be cancelled, got ${t.status}")
          case None    => () // task file already pruned — fine
        }
        // (c) no phantom: a later SINGLE delegate completes → its result
        // reaches the root (immediate injection turn; no held residue from the
        // earlier batch). Counting absolute request numbers would be
        // path-dependent (the held path batches Fast+cancel into one turn, the
        // #418 idle path injects them separately) — the Fast2Marker appearing
        // in a root request is the evidence.
        _ <- rootRef ! AgentCommand.UserInput("now a single one", None, Some("cid-2"))
        _ <- waitUntil(15.seconds)(
          requests.get.map(reqs =>
            reqs.exists(r => r.sessionId == rootSid && r.messages.exists(_.textContent.contains(Fast2Marker)))
          )
        )
      yield ()
      program.unsafeRunSync()
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
  }

  /** #418 regression: BOTH delegates complete normally, but only after turn 1
    * ends (root back to idle). The FIRST completion must wake the idle root
    * immediately — pre-fix the idle HOLD branch parked the parent asleep until
    * user input ("delegate COMPLETED but the parent never turns").
    */
  private class TwinWakeLlm(
    rootSid: String,
    counters: Ref[IO, Map[String, Int]],
    requests: Ref[IO, List[LlmRequest]]
  ) extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected in this test"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(
        counters.update(m => m.updated(req.sessionId, m.getOrElse(req.sessionId, 0) + 1)) *>
          requests.update(_ :+ req)
      ) >>
        Stream.eval(counters.get.map(_.getOrElse(req.sessionId, 0))).flatMap { n =>
          if req.sessionId == rootSid then rootTurn(n)
          else if req.sessionId.startsWith("delegate-TwinA") then
            Stream(StreamChunk.TextDelta(s"report A $AMarker"), StreamChunk.Done(None, None))
          else if req.sessionId.startsWith("delegate-TwinB") then
            Stream(StreamChunk.TextDelta(s"report B $BMarker"), StreamChunk.Done(None, None))
          else Stream(StreamChunk.TextDelta("unexpected session"), StreamChunk.Done(None, None))
        }

    private def rootTurn(n: Int): Stream[IO, StreamChunk] =
      n match
        case 1 =>
          // Two parallel delegates in ONE turn: barrier +2 (spawn counting).
          Stream(
            StreamChunk.ToolCallChunk(nebflow.shared.ToolCall(
              id = "tc-twin-a",
              name = "Delegate",
              input = JsonObject(
                "prompt" -> "first worker, report immediately".asJson,
                "description" -> "twin A".asJson,
                "agent" -> "TwinA".asJson
              )
            )),
            StreamChunk.ToolCallChunk(nebflow.shared.ToolCall(
              id = "tc-twin-b",
              name = "Delegate",
              input = JsonObject(
                "prompt" -> "second worker, report immediately".asJson,
                "description" -> "twin B".asJson,
                "agent" -> "TwinB".asJson
              )
            )),
            StreamChunk.Done(None, None)
          )
        case 2 =>
          // Round 2 of turn 1: both spawns reported as tool results; the root
          // goes idle with the batch in flight (outstanding = 2).
          Stream(StreamChunk.TextDelta("twin batch launched, standing by"), StreamChunk.Done(None, None))
        case 3 =>
          // #418: woken by the FIRST completion (A) while idle — own turn, no
          // user input, no batch wait. The A result is in this turn's messages.
          Stream(StreamChunk.TextDelta("saw A immediately"), StreamChunk.Done(None, None))
        case 4 =>
          // B completes → outstanding 0 → immediate injection turn.
          Stream(StreamChunk.TextDelta("saw B"), StreamChunk.Done(None, None))
        case _ =>
          Stream(StreamChunk.TextDelta("unexpected root request"), StreamChunk.Done(None, None))
  end TwinWakeLlm

  test("#418: idle parent + cross-turn twin delegates — first completion wakes the parent immediately") {
    val system = ActorSystem("delegate-wake")
    val tmp = os.temp.dir()
    seedAgents(tmp)
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      val rootSid = "e2e-delegate-wake-root"
      val program = for
        counters <- IO.ref(Map.empty[String, Int])
        requests <- IO.ref(List.empty[LlmRequest])
        resources <- mkResources(system, tmp, TwinWakeLlm(rootSid, counters, requests))
        rootRef <- system.spawn(
          AgentActor(
            agentDef = nebulaDef,
            resources = resources,
            wsSend = _ => IO.unit,
            depth = 0,
            sessionId = Some(rootSid),
            sessionName = Some("e2e-delegate-wake")
          ),
          rootSid
        )
        _ <- resources.agentRegistry.update(
          _ + (rootSid -> AgentRecord(rootSid, rootRef, AgentKind.Root, rootSid, None))
        )
        _ <- rootRef ! AgentCommand.UserInput("run the twin batch", None, Some("cid-twin"))
        // Turn 1 (requests 1-2) ends and the root is back to idle with the
        // batch in flight. NO further user input below — every later root
        // request is an injection wake. (Registry-key checks are racy here:
        // Twin delegates complete immediately and deregister before the check
        // could see both keys — count root requests instead.)
        _ <- waitUntil(15.seconds)(
          counters.get.map(m => m.getOrElse(rootSid, 0) >= 2)
        )
        // #418: the FIRST completion (A) wakes the idle root with NO user
        // input — the A result arrives as its own injection turn (request #3).
        _ <- waitUntil(15.seconds)(
          requests.get.map(reqs =>
            reqs.exists(r => r.sessionId == rootSid && r.messages.exists(_.textContent.contains(AMarker)))
          )
        )
        // #418 core assertion: the FIRST delegate result must arrive ALONE in
        // its own wake turn (immediate injection) — whichever twin finishes
        // first (A or B is a genuine race). Pre-fix the idle HOLD held the
        // first result until the second completed and injected BOTH together
        // in one batch turn — this XOR is the mutation-red catch.
        firstResultTurn <- requests.get.map(
          _.find(r =>
            r.sessionId == rootSid &&
              (r.messages.exists(_.textContent.contains(AMarker)) || r.messages.exists(_.textContent.contains(BMarker)))
          ).get
        )
        _ = assert(
          firstResultTurn.messages.exists(_.textContent.contains(AMarker)) !=
            firstResultTurn.messages.exists(_.textContent.contains(BMarker)),
          s"#418: the first result must arrive ALONE (immediate injection, not batched with its twin), got: ${firstResultTurn.messages.map(_.textContent).mkString(" | ").take(500)}"
        )
        _ = assert(
          firstResultTurn.messages.exists(_.textContent.contains("report A")) ||
            firstResultTurn.messages.exists(_.textContent.contains("report B")),
          s"the first wake turn must carry a delegate result payload, got: ${firstResultTurn.messages.map(_.textContent).mkString(" | ").take(500)}"
        )
        // B completes → outstanding 0 → its own injection turn (request #4).
        // (The drain that releases B only fires when the barrier hits 0, so a
        // root request carrying BMarker is behavioral proof the barrier fully
        // released. Registry-snapshot counts are NOT updated on the queued /
        // turn-end-drain paths — only on the tools-complete / immediate-inject
        // paths — so a snapshot assertion would be flaky by design.)
        _ <- waitUntil(15.seconds)(
          requests.get.map(reqs =>
            reqs.exists(r => r.sessionId == rootSid && r.messages.exists(_.textContent.contains(BMarker)))
          )
        )
        bTurn <- requests.get.map(
          _.find(r => r.sessionId == rootSid && r.messages.exists(_.textContent.contains(BMarker))).get
        )
        _ = assert(
          bTurn.messages.exists(_.textContent.contains("report B")),
          s"the B turn must carry the B delegate result, got: ${bTurn.messages.map(_.textContent).mkString(" | ").take(500)}"
        )
      yield ()
      program.unsafeRunSync()
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
  }

end StuckDelegateReleaseSpec
