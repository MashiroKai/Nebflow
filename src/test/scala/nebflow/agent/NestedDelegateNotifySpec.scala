package nebflow.agent

import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, ActorRef}
import nebflow.core.FileChangeTracker
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * #25 (direction A): nested delegation completion dead-letter.
 *
 * Topology under test (post-#28 real shape):
 *
 *   root Nebula ──Delegate(agent="Worker")──▶ delegate-Worker clone
 *                                                │ turn 1: SubTask toolcall
 *                                                │ turn 2: "launched, ending turn" → barrier=1
 *                                                ▼
 *                                          subtask-Worker grandchild (SLOW final turn)
 *
 * Bug (pre-fix): the clone's turn-2 finish fired AgentEvent.Completed while
 * outstandingSubagentResults=1 → BackoffSupervisor.notifyParentAndStop killed
 * the clone → the grandchild's result dead-lettered and the root never saw the
 * final synthesis.
 *
 * Fix: finishTurnCont parks the completion debt (execution.owedCompletion)
 * while sub-agents are in flight; the batch-completion injection turn ends
 * with the barrier at 0 and pays the debt — the supervisor then receives the
 * FINAL synthesized text and the root session wakes with it.
 */
class NestedDelegateNotifySpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val GrandchildMarker = "GRANDCHILD-FINAL-MARKER-9f2c"
  private val CloneFinalMarker = "CLONE-FINAL-SYNTHESIS-7a1e"

  /**
   * Session-routed mock LLM:
   *  - root: #1 Delegate toolcall / #2 relay text / #3+ external-event wake turns
   *  - delegate-* (clone): #1 SubTask toolcall / #2 "ending turn" text /
   *    #3 final synthesis (asserted to carry the grandchild marker)
   *  - subtask-* (grandchild): #1 slow (3s) final report
   */
  private class NestedLlm(
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
          else if req.sessionId.startsWith("delegate-") then cloneTurn(n)
          else if req.sessionId.startsWith("subtask-") then grandchildTurn(n)
          else Stream(StreamChunk.TextDelta("unexpected session"), StreamChunk.Done(None, None))
        }

    private def rootTurn(n: Int): Stream[IO, StreamChunk] =
      n match
        case 1 =>
          Stream(
            StreamChunk.ToolCallChunk(nebflow.shared.ToolCall(
              id = "tc-delegate-root",
              name = "Delegate",
              input = JsonObject(
                "prompt" -> "coordinate the nested job".asJson,
                "description" -> "nested-e2e clone".asJson,
                "agent" -> "Worker".asJson
              )
            )),
            StreamChunk.Done(None, None)
          )
        case _ =>
          Stream(StreamChunk.TextDelta(s"root turn $n"), StreamChunk.Done(None, None))

    private def cloneTurn(n: Int): Stream[IO, StreamChunk] =
      n match
        case 1 =>
          Stream(
            StreamChunk.ToolCallChunk(nebflow.shared.ToolCall(
              id = "tc-subtask-clone",
              name = "SubTask",
              input = JsonObject(
                "prompt" -> "do the leaf work and report the marker".asJson,
                "description" -> "nested-e2e grandchild".asJson
              )
            )),
            StreamChunk.Done(None, None)
          )
        case 2 =>
          // Ends the turn with the grandchild in flight — the #25 kill shot
          // pre-fix: this turn's finish MUST NOT fire AgentEvent.Completed.
          Stream(
            StreamChunk.TextDelta("launched the subtask, ending my turn now"),
            StreamChunk.Done(None, None)
          )
        case _ =>
          // Final synthesis turn: woken by the grandchild's result. The request
          // must already contain the grandchild marker (asserted separately).
          Stream(
            StreamChunk.TextDelta(s"$CloneFinalMarker: incorporated the subtask report"),
            StreamChunk.Done(None, None)
          )

    private def grandchildTurn(n: Int): Stream[IO, StreamChunk] =
      // Slow leaf: keeps the clone's barrier at 1 long enough for the
      // mid-flight assertions (task must still be running) to be sampled.
      Stream.sleep[IO](3.seconds).drain ++
        Stream(
          StreamChunk.TextDelta(s"leaf report: $GrandchildMarker"),
          StreamChunk.Done(None, None)
        )
  end NestedLlm

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

  /** Root def: name must be Nebula (Delegate is Nebula-exclusive). */
  private val nebulaDef: AgentDef =
    AgentDef(
      name = "Nebula",
      description = "root scheduler under test",
      tools = List("Read", "Delegate"),
      systemPrompt = ""
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

  private def sidByPrefix(registry: Map[String, AgentRecord], prefix: String): Option[String] =
    registry.keys.find(_.startsWith(prefix))

  /**
   * loadCurrentDef reloads defs from disk every turn — seed explicit agent.json
   * files pinning the tool sets the chain needs (empty dirs fall back to
   * Seeds.Nebula whose toolset lacks Delegate → toolcalls silently filtered).
   */
  private def seedAgents(tmp: os.Path): Unit =
    val nebulaDir = tmp / "agents" / "Nebula"
    os.makeDir.all(nebulaDir)
    os.write.over(nebulaDir / "agent.json",
      """{"name":"Nebula","displayName":"Nebula","description":"e2e root","tools":["Read","Delegate"]}"""
    )
    // The clone runs the Worker def: SubTask in tools → it can spawn the
    // grandchild (SubTask is NOT Nebula-exclusive; the clone is not Nebula).
    val workerDir = tmp / "agents" / "Worker"
    os.makeDir.all(workerDir)
    os.write.over(workerDir / "agent.json",
      """{"name":"Worker","displayName":"Worker","description":"e2e nested target","tools":["Read","SubTask"]}"""
    )

  test("#25: grandchild result reaches the ROOT session — no dead-letter, debt paid with final text") {
    val system = ActorSystem("nested-delegate-e2e")
    val tmp = os.temp.dir()
    seedAgents(tmp)
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      val rootSid = "e2e-nested-root"
      val program = for
        counters <- IO.ref(Map.empty[String, Int])
        requests <- IO.ref(List.empty[LlmRequest])
        resources <- mkResources(system, tmp, NestedLlm(rootSid, counters, requests))
        rootRef <- system.spawn(
          AgentActor(
            agentDef = nebulaDef,
            resources = resources,
            wsSend = _ => IO.unit,
            depth = 0,
            sessionId = Some(rootSid),
            sessionName = Some("e2e-nested")
          ),
          rootSid
        )
        _ <- resources.agentRegistry.update(
          _ + (rootSid -> AgentRecord(rootSid, rootRef, AgentKind.Root, rootSid, None))
        )
        _ <- rootRef ! AgentCommand.UserInput("run the nested delegation", None, Some("e2e-nested-1"))
        // Both levels spawned
        _ <- waitUntil(10.seconds)(
          resources.agentRegistry.get.map(m => sidByPrefix(m, "delegate-").isDefined && sidByPrefix(m, "subtask-").isDefined)
        )
        registry1 <- resources.agentRegistry.get
        cloneSid = sidByPrefix(registry1, "delegate-").get
        grandchildSid = sidByPrefix(registry1, "subtask-").get
        // ── Mid-flight: the clone has ended its spawning turn (turn 2 done),
        // the grandchild is still working (3s leaf). The clone MUST still be
        // alive/running — pre-fix it was Completed+killed here.
        _ <- waitUntil(10.seconds)(
          counters.get.map(m => m.getOrElse(cloneSid, 0) >= 2 && m.getOrElse(grandchildSid, 0) >= 1)
        )
        _ <- IO.sleep(500.millis) // settle: turn-2 finish fully processed
        taskMid <- resources.subAgentTaskStore.findByTaskId(cloneSid)
        _ = assertEquals(taskMid.map(_.status), Some("running"),
          s"the clone must NOT be completed while its grandchild is in flight (pre-fix dead-letter): $taskMid")
        registryMid <- resources.agentRegistry.get
        _ = assert(registryMid.contains(cloneSid),
          "the clone must still be registered while the grandchild is in flight")
        // ── The grandchild's result is consumed by the clone (not dead-lettered):
        // clone turn 3 request carries the grandchild marker.
        _ <- waitUntil(15.seconds)(
          requests.get.map(reqs =>
            reqs.exists(r => r.sessionId == cloneSid && r.messages.exists(_.textContent.contains(GrandchildMarker)))
          )
        )
        // ── Debt paid with the FINAL text: the root wakes with the clone's
        // synthesis (pre-fix: only the turn-2 relay text ever arrived).
        _ <- waitUntil(15.seconds)(
          requests.get.map(reqs =>
            reqs.exists(r => r.sessionId == rootSid && r.messages.exists(_.textContent.contains(CloneFinalMarker)))
          )
        )
        // ── Terminal hygiene: clone task completed, both actors unregistered.
        _ <- waitUntil(10.seconds)(
          resources.subAgentTaskStore.findByTaskId(cloneSid).map(_.exists(_.status == "completed"))
        )
        _ <- waitUntil(10.seconds)(
          resources.agentRegistry.get.map(m => !m.contains(cloneSid) && !m.contains(grandchildSid))
        )
        // Root persisted session saw the final payload too (LLM-visible, not
        // just a UI bubble — the #25 companion defect).
        _ <- waitUntil(10.seconds)(
          resources.sessionStore.loadMessagesForSession(rootSid).map(
            _.exists(_.textContent.contains(CloneFinalMarker))
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

end NestedDelegateNotifySpec
