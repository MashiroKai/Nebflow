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
 *   Fast completes while Stall is outstanding → HOLD branch (outstanding 2→1,
 *   result parked in pendingEvents, NO turn starts — UI shows COMPLETED).
 *
 *   Stall never terminates on its own: Stop (mailbox, never consumed while
 *   suspended on the LLM fiber) and hard-cancel (StuckAbort on a fiber parked
 *   in a non-cancellable wait) were BOTH ineffective in the incident. Fix A:
 *   after escalate, the next scans give up via the supervisor's Cancelled
 *   branch (same path as AgentControl cancel) — the parent's barrier slot is
 *   returned, ALL held results inject, and the root turn fires.
 *
 * Assertions (report §4.2):
 *   (a) held Fast result + stall cancellation reach the ROOT turn
 *   (b) root barrier returns to 0 (registry snapshot, Fix D)
 *   (c) no phantom residue: a later single delegate completes → immediate turn
 */
class StuckDelegateReleaseSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val FastMarker = "FAST-RESULT-MARKER-31a"
  private val Fast2Marker = "FAST2-RESULT-MARKER-31b"
  private val RootBatchMarker = "ROOT-BATCH-TURN-31"
  private val RootFinalMarker = "ROOT-FINAL-TURN-31"

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
          if req.sessionId == rootSid then rootTurn(n)
          else if req.sessionId.startsWith("delegate-Stall") then Stream.never[IO]
          else if req.sessionId.startsWith("delegate-Fast2") then
            Stream(StreamChunk.TextDelta(s"second report $Fast2Marker"), StreamChunk.Done(None, None))
          else if req.sessionId.startsWith("delegate-Fast") then
            Stream(StreamChunk.TextDelta(s"fast report $FastMarker"), StreamChunk.Done(None, None))
          else Stream(StreamChunk.TextDelta("unexpected session"), StreamChunk.Done(None, None))
        }

    // Turn shape note: a tool-calling turn makes MULTIPLE LLM requests (one
    // per loop round). Round 1 emits the toolcalls, round 2 receives the tool
    // results and ends the turn — so the request counter advances twice per
    // tool turn. Injection turns (batch/single external-event wake) are
    // single-round. The script below numbers REQUESTS, not turns.
    private def rootTurn(n: Int): Stream[IO, StreamChunk] =
      n match
        case 1 =>
          // Two parallel delegates in ONE turn: barrier +2 (spawn counting).
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
        case 2 =>
          // Round 2 of turn 1: both spawns reported as tool results; the turn
          // ends and the root goes idle with the batch in flight.
          Stream(StreamChunk.TextDelta("batch launched, standing by"), StreamChunk.Done(None, None))
        case 3 =>
          // Woken by the batch injection: held Fast result + stall cancellation.
          Stream(StreamChunk.TextDelta(s"$RootBatchMarker: saw the batch"), StreamChunk.Done(None, None))
        case 4 =>
          // User-driven single delegate (phantom probe), round 1.
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
        case 5 =>
          // Round 2 of the phantom-probe turn.
          Stream(StreamChunk.TextDelta("fast2 launched, standing by"), StreamChunk.Done(None, None))
        case _ =>
          Stream(StreamChunk.TextDelta(s"$RootFinalMarker: saw the single result"), StreamChunk.Done(None, None))
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
    for name <- List("Fast", "Stall", "Fast2") do
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
        // (a)-precondition proven: Fast completed but the root has NOT been
        // woken (the HOLD — no batch injection while Stall is stuck). Turn 1
        // makes exactly TWO requests (toolcall round + results round); a third
        // request means an injection turn fired, which must not happen yet.
        _ <- IO.sleep(300.millis)
        rootTurnsEarly <- counters.get.map(_.getOrElse(rootSid, 0))
        _ = assertEquals(rootTurnsEarly, 2, s"root must stay at turn 1 (2 requests) while the batch is held, got $rootTurnsEarly")
        // Fix A: drive the watcher. Scan #1-2 send Stop (ignored — suspended),
        // #3 escalates (cancelInflight misses: fixture LLM is not in the
        // registry — mirrors the incident where hard-cancel was ineffective),
        // #4 gives up via supervisor Cancelled → barrier released → batch turn.
        stopCounts <- Ref.of[IO, Map[String, Int]](Map.empty)
        _ <- TaskStuckWatcher.scan(resources, new WsHub(), 1L, stopCounts)
        _ <- TaskStuckWatcher.scan(resources, new WsHub(), 1L, stopCounts)
        _ <- TaskStuckWatcher.scan(resources, new WsHub(), 1L, stopCounts)
        _ <- TaskStuckWatcher.scan(resources, new WsHub(), 1L, stopCounts)
        // (a) held Fast result + stall cancellation reach the ROOT turn.
        _ <- waitUntil(15.seconds)(
          requests.get.map(reqs =>
            reqs.exists(r => r.sessionId == rootSid && r.messages.exists(_.textContent.contains(FastMarker)))
          )
        )
        requestsAfterBatch <- requests.get
        batchTurn = requestsAfterBatch
          .find(r => r.sessionId == rootSid && r.messages.exists(_.textContent.contains(FastMarker)))
          .get
        _ = assert(
          batchTurn.messages.exists(_.textContent.contains("cancelled")),
          s"the batch injection must carry the stall cancellation payload, got: ${batchTurn.messages.map(_.textContent).mkString(" | ").take(500)}"
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
        // (c) no phantom: a later SINGLE delegate completes → immediate turn.
        _ <- rootRef ! AgentCommand.UserInput("now a single one", None, Some("cid-2"))
        _ <- waitUntil(15.seconds)(
          counters.get.map(m => m.getOrElse(rootSid, 0) >= 4)
        )
        _ <- waitUntil(15.seconds)(
          requests.get.map(reqs =>
            reqs.exists(r => r.sessionId == rootSid && r.messages.exists(_.textContent.contains(Fast2Marker)))
          )
        )
        // Root's final injection turn fired with the single result (request #6+).
        _ <- waitUntil(15.seconds)(
          counters.get.map(m => m.getOrElse(rootSid, 0) >= 6)
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
