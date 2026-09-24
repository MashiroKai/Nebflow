package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentLibrary, SharedResources}
import nebflow.core.PathUtil
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * Dispatcher session lifetime **anchored to the bound task's terminal state** acceptance
 * (taskunify merge batch 2026-09-24 · landing point 5 · ruling d①).
 *
 * **Behaviour under test (implplan §5, criteria 5.1–5.5)**:
 *  ① **5.2 task-terminal anchor**: the task reaches `completed` ⇒ the session is torn down
 *     and the event face gains one **`dispatcher-task-terminal`** entry (**distinguishable**
 *     from `dispatcher-idle-expired`);
 *  ② **5.1 the fallback switch works**: `Some(false)` (equivalent to `≤0`/`off`) ⇒ back to
 *     the idle-window behaviour with **zero** `dispatcher-task-terminal` events (criterion =
 *     after the fallback `grep -c` on refusal/anchor events = 0);
 *  ③ **5.3 hard guardrail**: with `pendingInjected > 0` ⇒ **no teardown** (guardrail holds);
 *  ④ **5.5 concurrency cap**: once the live dispatcher count reaches the cap, a new spawn is
 *     **explicitly refused + alerted** (a `dispatcher-concurrency-refused` event; **not** a
 *     silent over-spawn).
 *
 * **Speed-up equivalence (hard declaration)**: this spec uses `ttlCheckIntervalSec = 1` to
 * compress the `TtlTick` beat from the production 30 s to 1 s. **Only the beat constant is
 * compressed**; the code path under test is line-for-line unchanged (the same
 * `ProjectActor.sweepDispatchers` dispatch leg, the same `sweepTaskTerminalDispatchers`
 * criterion, the same `expireTaskTerminalDispatcher` action set and the same audit write
 * point). The criterion's form ("task terminal ⇒ session gone + event present") is
 * independent of the absolute duration's dimension ⇒ equivalent judgment is valid.
 * **What is NOT equivalent** = the full chain in which a real task terminal state is reached
 * through a real `Task` tool call (this spec reaches the terminal state directly through the
 * `TaskLedgerStore` write face — that is the same write face; the tool layer is merely its
 * caller).
 */
class DispatcherTaskTerminalAnchorSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-dispatcher-task-terminal"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "project-dispatcher")
  os.write.over(
    tempRoot / "agents" / "project-dispatcher" / "agent.json",
    """{"name":"project-dispatcher","description":"task-terminal anchor test","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "project-dispatcher" / "system.md", "# project-dispatcher\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  /** Recording LLM: a text delta ends the turn immediately (zero tool calls).
    *
    * `holdFromSecondTurn` = an optional gate used only by the guardrail test: from the **second**
    * turn on, the stream parks until the gate is released, which keeps the turn (and therefore
    * `pendingInjected > 0`) deterministically in flight instead of racing the fast stub. */
  private class RecordingLlm(
      holdFromSecondTurn: Option[cats.effect.Deferred[IO, Unit]] = None
  ):
    val inputs: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)
    val streamsDone: Ref[IO, Int] = Ref.unsafe[IO, Int](0)
    val handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(
          req: LlmRequest,
          onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
      ): Stream[IO, StreamChunk] =
        val text = req.messages.map(_.textContent).mkString("\n")
        Stream.eval(inputs.update(_ :+ text)).flatMap { _ =>
          Stream
            .eval(inputs.get.flatMap { seen =>
              if seen.size >= 2 then holdFromSecondTurn.map(_.get).getOrElse(IO.unit) else IO.unit
            })
            .drain ++
            Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None)) ++
            Stream.eval(streamsDone.update(_ + 1)).drain
        }

  private def mkResources(system: ActorSystem, tmp: os.Path, llm: LlmHandle[IO]): IO[SharedResources] =
    for
      dispatcher <- cats.effect.std.Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- nebflow.core.FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
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
      historyArchiver = null,
      fileLockManager = fileLocks,
      sessionModelOverrides = modelOverrides,
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = system,
      voiceMutedRef = voiceMuted
    )

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 25.millis)(
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

  private def dispatcherEntries(resources: SharedResources): IO[List[String]] =
    resources.agentRegistry.get.map(_.keys.toList.filter(_.startsWith(ProjectActor.DispatcherSessionPrefix)))

  private def eventLines(ws: os.Path): IO[List[String]] =
    val f = ws / ".nebflow" / FlowMapEventLog.FileName
    IO.blocking(if os.exists(f) then os.read.lines(f).toList else Nil)

  private def mount(
    name: String,
    ws: os.Path,
    system: ActorSystem,
    res: SharedResources,
    idleWindowMs: Option[Long],
    anchorTaskTerminal: Option[Boolean],
    maxConcurrent: Option[Int] = None
  ): IO[ProjectRuntime] =
    val pd = ProjectDef(
      name = name,
      workspace = ws.toString,
      agentFile = (ws / "AGENTS.md").toString,
      createdAt = System.currentTimeMillis()
    )
    ProjectRuntimeRegistry.mount(
      pd,
      system,
      res,
      Some((_: Json) => IO.unit),
      rootSessionId = "nebula-root",
      ttlCheckIntervalSec = 1,
      dispatcherIdleWindowMs = idleWindowMs,
      dispatcherLifecycleAnchorTaskTerminal = anchorTaskTerminal,
      dispatcherMaxConcurrentSessions = maxConcurrent
    )

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  private def withScanner[A](body: IO[A]): IO[A] =
    ProjectActor.ttlScanner(1.second).background.use(_ => body)

  /** Create one task through the unified ledger's real write face and return the bare task id
    * (the create call returns its human-readable confirmation, not the id). */
  private def newTask(title: String): String =
    val msg = nebflow.core.project.TaskLedgerStore.open()
      .createSync(title = title, actor = nebflow.core.project.TaskLedgerHistory.Actors.Dispatcher)
      .getOrElse(sys.error("create must succeed"))
    "#(\\d+)".r.findFirstMatchIn(msg).map(_.group(1)).getOrElse(sys.error(s"cannot parse task id from: $msg"))

  // ── ① task-terminal anchor: task completed ⇒ session torn down + distinguishable event ──

  test("(1) task reaches completed ⇒ session torn down + dispatcher-task-terminal event (distinguishable from idle-expired)") {
    val ws = tempRoot / "ws-anchor"
    os.makeDir.all(ws)
    val system = ActorSystem(s"anchor-${scala.util.Random.nextInt(100000)}")
    withScanner {
      for
        llm <- IO.pure(new RecordingLlm)
        resources <- mkResources(system, tempRoot, llm.handle)
        // task-terminal anchor = on; idle window given a **huge** value ⇒ taking the wrong leg
        // (idle window) would make this test red for sure
        rt <- mount("anchor-proj", ws, system, resources, Some(3600_000L), Some(true))
        actorRef = rt.actorRef.getOrElse(sys.error("ProjectActor must be spawned by mount"))
        // engine auto-create path: create through the same write face and hand it to the
        // dispatcher (equivalent to Mail auto-create)
        tid = newTask("task-A")
        _ <- (actorRef ! ProjectActor.ProjectCommand.TriggerDispatcher("task-A", "nebula-root", ProjectActor.SourceTask, None, Some(tid))).void
        _ <- waitUntil(20.seconds)(dispatcherEntries(resources).map(_.nonEmpty))
        sid1 <- dispatcherEntries(resources).map(_.head)
        _ <- waitUntil(20.seconds)(llm.streamsDone.get.map(_ >= 1))
        _ <- IO.sleep(500.millis)
        // the task is still open ⇒ the session **must** stay alive (proof that the criterion is
        // really "task terminal", not "turn terminal")
        beforeTerminal <- dispatcherEntries(resources)
        // drive the task to its terminal state
        _ <- IO(nebflow.core.project.TaskLedgerStore.open().completeSync(tid))
        _ <- waitUntil(20.seconds)(dispatcherEntries(resources).map(_.isEmpty))
        _ <- waitUntil(10.seconds)(
          eventLines(ws).map(_.exists(l => l.contains("\"type\":\"dispatcher-task-terminal\"") && l.contains(sid1)))
        )
        events <- eventLines(ws)
        _ <- system.stopAll.handleErrorWith(_ => IO.unit)
      yield
        assertEquals(beforeTerminal, List(sid1), "the session must stay alive while the task is open (the criterion is task terminal, not turn terminal)")
        val anchorEvents = events.filter(_.contains("\"type\":\"dispatcher-task-terminal\""))
        assert(anchorEvents.nonEmpty, s"a dispatcher-task-terminal event must be left, got:\n${events.mkString("\n")}")
        assert(anchorEvents.exists(l => l.contains(sid1) && l.contains(s"task=$tid")),
          s"the event must carry the session id and the task id (mechanically greppable), got:\n${anchorEvents.mkString("\n")}")
        // **distinguishable** from the idle-window event (this is exactly criterion 5.2's
        // "different event type" face)
        assert(!events.exists(_.contains("\"type\":\"dispatcher-idle-expired\"")),
          "a task-terminal teardown must NOT write dispatcher-idle-expired (the two causes must be distinguishable)")
    }
  }

  // ── ② fallback switch: anchor off ⇒ idle window, zero anchor events ──

  test("(2) fallback switch (anchor=Some(false) ⇒ equivalent to ≤0/off) ⇒ back to the idle window; a task terminal state does NOT tear the session down, zero anchor events") {
    val ws = tempRoot / "ws-rollback"
    os.makeDir.all(ws)
    val system = ActorSystem(s"anchor-rollback-${scala.util.Random.nextInt(100000)}")
    withScanner {
      for
        llm <- IO.pure(new RecordingLlm)
        resources <- mkResources(system, tempRoot, llm.handle)
        // anchor off + huge idle window ⇒ the session takes neither the task-terminal leg nor
        // reaches the idle window ⇒ it must stay alive
        rt <- mount("anchor-rollback-proj", ws, system, resources, Some(3600_000L), Some(false))
        actorRef = rt.actorRef.getOrElse(sys.error("ProjectActor must be spawned by mount"))
        tid = newTask("task-B")
        _ <- (actorRef ! ProjectActor.ProjectCommand.TriggerDispatcher("task-B", "nebula-root", ProjectActor.SourceTask, None, Some(tid))).void
        _ <- waitUntil(20.seconds)(dispatcherEntries(resources).map(_.nonEmpty))
        sid1 <- dispatcherEntries(resources).map(_.head)
        _ <- waitUntil(20.seconds)(llm.streamsDone.get.map(_ >= 1))
        _ <- IO.sleep(500.millis)
        _ <- IO(nebflow.core.project.TaskLedgerStore.open().completeSync(tid))
        // run enough ticks: in the fallback state a task terminal state must NOT trigger teardown
        _ <- IO.sleep(3.5.seconds)
        alive <- dispatcherEntries(resources)
        events <- eventLines(ws)
        _ <- system.stopAll.handleErrorWith(_ => IO.unit)
      yield
        assertEquals(alive, List(sid1), "in the fallback state (anchor off) a task terminal state must not tear the session down (the old idle-window behaviour)")
        assertEquals(events.count(_.contains("\"type\":\"dispatcher-task-terminal\"")), 0,
          s"criterion 5.1: after the fallback the anchor-event count must be 0, got:\n${events.filter(_.contains("dispatcher-task-terminal")).mkString("\n")}")
    }
  }

  // ── ③ hard guardrail: pendingInjected > 0 ⇒ no teardown ──

  test("(3) hard guardrail: injected items still in flight (pendingInjected > 0) ⇒ no teardown even at a task terminal state (prevents unconsumed items being taken away)") {
    val ws = tempRoot / "ws-guard"
    os.makeDir.all(ws)
    val system = ActorSystem(s"anchor-guard-${scala.util.Random.nextInt(100000)}")
    withScanner {
      for
        gate <- cats.effect.Deferred[IO, Unit]
        llm <- IO.pure(new RecordingLlm(holdFromSecondTurn = Some(gate)))
        resources <- mkResources(system, tempRoot, llm.handle)
        rt <- mount("anchor-guard-proj", ws, system, resources, Some(3600_000L), Some(true))
        actorRef = rt.actorRef.getOrElse(sys.error("ProjectActor must be spawned by mount"))
        tid = newTask("task-C")
        _ <- (actorRef ! ProjectActor.ProjectCommand.TriggerDispatcher("task-C", "nebula-root", ProjectActor.SourceTask, None, Some(tid))).void
        _ <- waitUntil(20.seconds)(dispatcherEntries(resources).map(_.nonEmpty))
        sid1 <- dispatcherEntries(resources).map(_.head)
        _ <- waitUntil(20.seconds)(llm.streamsDone.get.map(_ >= 1))
        _ <- IO.sleep(500.millis)
        // inject one more item: its turn **parks on the gate** (the stub's second turn) so
        // `pendingInjected > 0` holds deterministically; then push the task to its terminal state
        // and let several sweep ticks run — the guardrail must block the teardown
        // (an unconsumed injected item is in flight).
        _ <- (actorRef ! ProjectActor.ProjectCommand.TriggerDispatcher("task-C-extra", "nebula-root", ProjectActor.SourceTask, None, Some(tid))).void
        _ <- waitUntil(10.seconds)(llm.inputs.get.map(_.size >= 2))
        _ <- IO(nebflow.core.project.TaskLedgerStore.open().completeSync(tid))
        _ <- IO.sleep(2.5.seconds) // several tick beats; the guardrail must hold throughout
        guarded <- dispatcherEntries(resources)
        _ <- gate.complete(()).attempt
        _ <- system.stopAll.handleErrorWith(_ => IO.unit)
      yield
        assertEquals(guarded, List(sid1),
          "criterion 5.3: with pendingInjected > 0 the sweep must not tear down (the guardrail holds — unconsumed injected items must not be taken away with the session)")
    }
  }

  // ── ④ concurrency cap: at the cap ⇒ explicit refusal + alert ──

  test("(4) concurrency cap: active >= cap ⇒ a new spawn is explicitly refused + dispatcher-concurrency-refused event (no silent over-spawn)") {
    val ws = tempRoot / "ws-cap"
    os.makeDir.all(ws)
    val system = ActorSystem(s"anchor-cap-${scala.util.Random.nextInt(100000)}")
    withScanner {
      for
        llm <- IO.pure(new RecordingLlm)
        resources <- mkResources(system, tempRoot, llm.handle)
        // cap = 1 (the production default = keeps the existing singleton semantics)
        rt <- mount("anchor-cap-proj", ws, system, resources, Some(3600_000L), Some(true), Some(1))
        actorRef = rt.actorRef.getOrElse(sys.error("ProjectActor must be spawned by mount"))
        // deterministic occupancy: pre-seed one live dispatcher registry row for this project
        // (= the slot that already consumes the concurrency budget). This changes no production
        // code path — it is exactly `underConcurrencyCap`'s counting basis (the registry single point).
        ghostRef <- system.spawn(
          nebflow.actor.Behaviors.receiveMessage[nebflow.agent.AgentCommand](_ =>
            IO.pure(nebflow.actor.Behaviors.stopped[nebflow.agent.AgentCommand])),
          s"ghost-${scala.util.Random.nextInt(100000)}")
        _ <- resources.agentRegistry.update(_ + (
          s"${ProjectActor.DispatcherSessionPrefix}ghost01" -> nebflow.agent.AgentRecord(
            s"${ProjectActor.DispatcherSessionPrefix}ghost01",
            ghostRef,
            nebflow.agent.AgentKind.Flow,
            "nebula-root",
            project = Some("anchor-cap-proj")
          )))
        tid = newTask("task-D")
        // activeRef is empty (no live session) ⇒ this dispatch takes the **spawn leg** ⇒ hits the cap
        _ <- (actorRef ! ProjectActor.ProjectCommand.TriggerDispatcher("task-D", "nebula-root", ProjectActor.SourceTask, None, Some(tid))).void
        _ <- waitUntil(10.seconds)(dispatcherEntries(resources).map(_.size == 1))
        _ <- waitUntil(10.seconds)(
          eventLines(ws).map(_.exists(_.contains("\"type\":\"dispatcher-concurrency-refused\""))))
        events <- eventLines(ws)
        // zero spawn: this turn makes **no** LLM request at all (the refusal happens before spawn)
        turns <- llm.inputs.get
        _ <- system.stopAll.handleErrorWith(_ => IO.unit)
      yield
        val refused = events.filter(_.contains("\"type\":\"dispatcher-concurrency-refused\""))
        assert(refused.nonEmpty,
          s"criterion 5.5: reaching the concurrency cap must leave a dispatcher-concurrency-refused event (no silent over-spawn), got:\n${events.mkString("\n")}")
        val line = refused.last
        assert(line.contains("cap=1"), s"the event must carry the cap value, got:\n$line")
        assert(line.contains("active=1"), s"the event must carry the current live count, got:\n$line")
        assertEquals(turns.size, 0, "the refusal must happen before spawn — zero LLM turns (no session started despite the silent over-spawn being forbidden)")
    }
  }
