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
 * mailmodel batch (2026-09-25, plan §2.1) - the **per-task dispatcher slot model**
 * acceptance (one task one dispatcher).
 *
 * The old model kept ONE active dispatcher per project (`Option[ActiveDispatcher]`):
 * a second task's trigger text was injected into the FIRST task's live session, and the
 * project-wide `DispatcherMaxConcurrentSessions = 1` cap refused the second spawn. The
 * new model keys active dispatchers by task id (`Map[String, ActiveDispatcher]`), the
 * default cap is 0 (gate off - ruling (a)), and the task-terminal anchor tears down the
 * FINISHED task's slot only.
 *
 * Pinned here (each mechanically decidable, each red on the old singleton model):
 *  1. **two tasks ⇒ two live dispatcher sessions**: task A and task B each get their own
 *     bound session under the default (read-through cap = 0 = gate off); neither trigger
 *     is injected into the other's session and no refusal event is written;
 *  2. **terminal tears down its OWN slot**: completing task A removes exactly A's session
 *     (event carries task=<A>) while B's session stays alive (the old singleton model had
 *     no notion of "the other task's session" to preserve);
 *  3. **empty slot ⇒ rebuild**: after A's slot was torn down, a re-trigger of the SAME
 *     task id spawns a FRESH session bound to that task (slot-key lookup miss ⇒ spawn),
 *     never resurrects the old session id.
 *
 * Harness = `DispatcherTaskTerminalAnchorSpec` verbatim (real ActorSystem + real registry
 * + RecordingLlm that ends the turn on the first text delta; `ttlCheckIntervalSec = 1`
 * compresses only the sweep beat; the task ledger is driven through its real write face).
 * Terminal-state teardown is judged through the task-terminal anchor (`Some(true)`), which
 * is the deterministic single-slot removal path (idle-window expiry would race the
 * assertions and tests a different lifecycle face).
 */
class DispatcherTaskSlotSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-dispatcher-task-slot"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "project-dispatcher")
  os.write.over(
    tempRoot / "agents" / "project-dispatcher" / "agent.json",
    """{"name":"project-dispatcher","description":"task slot test","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "project-dispatcher" / "system.md", "# project-dispatcher\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  /** Recording LLM: a text delta ends the turn immediately (zero tool calls). */
  private class RecordingLlm extends LlmHandle[IO]:
    val streamsDone: Ref[IO, Int] = Ref.unsafe[IO, Int](0)
    val handle: LlmHandle[IO] = this
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
    def sendStream(
        req: LlmRequest,
        onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None)) ++
        Stream.eval(streamsDone.update(_ + 1)).drain

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
    res: SharedResources
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
      // a huge idle window: teardown must come ONLY from the task-terminal anchor
      dispatcherIdleWindowMs = Some(3600_000L),
      dispatcherLifecycleAnchorTaskTerminal = Some(true),
      // None = read through Defaults.DispatcherMaxConcurrentSessions (== 0 = gate off)
      dispatcherMaxConcurrentSessions = None
    )

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  private def withScanner[A](body: IO[A]): IO[A] =
    ProjectActor.ttlScanner(1.second).background.use(_ => body)

  /** Create one task through the unified ledger's real write face and return the bare id.
    * (r2 P0 fix, 2026-09-25: reads the PURE id via `createSyncReturningId` -- the old
    * regex scrape of the rendered `[OK] ...` banner was the same smell the fix removes.) */
  private def newTask(title: String): String =
    TaskLedgerStore.open()
      .createSyncReturningId(title = title, actor = TaskLedgerHistory.Actors.Dispatcher)
      .getOrElse(sys.error("create must succeed"))

  private type ActorRefT = nebflow.actor.ActorRef[ProjectActor.ProjectCommand]

  private def trigger(ref: ActorRefT, text: String, tid: String): IO[Unit] =
    (ref ! ProjectActor.ProjectCommand.TriggerDispatcher(text, "nebula-root", ProjectActor.SourceTask, None, Some(tid))).void

  // ── (1) two tasks ⇒ two live dispatcher sessions ──

  test("(1) one task one dispatcher: two tasks triggered back-to-back get two DISTINCT live sessions (default cap reads through as 0 = gate off), zero refusal events") {
    val ws = tempRoot / "ws-two-tasks"
    os.makeDir.all(ws)
    val system = ActorSystem(s"slot-two-${scala.util.Random.nextInt(100000)}")
    withScanner {
      for
        llm <- IO.pure(new RecordingLlm)
        resources <- mkResources(system, tempRoot, llm.handle)
        rt <- mount("slot-two-proj", ws, system, resources)
        actorRef = rt.actorRef.getOrElse(sys.error("ProjectActor must be spawned by mount"))
        tidA = newTask("slot task A")
        tidB = newTask("slot task B")
        _ <- trigger(actorRef, "task A brief", tidA)
        _ <- waitUntil(20.seconds)(llm.streamsDone.get.map(_ >= 1))
        // the SECOND task arrives while the first session is alive - the old singleton
        // model would inject this text into A's session instead of spawning B's own
        _ <- trigger(actorRef, "task B brief", tidB)
        _ <- waitUntil(20.seconds)(llm.streamsDone.get.map(_ >= 2))
        sids <- dispatcherEntries(resources)
        events <- eventLines(ws)
        _ <- system.stopAll.handleErrorWith(_ => IO.unit)
      yield
        assertEquals(sids.distinct.size, 2,
          s"two tasks must own two distinct live dispatcher sessions, got: $sids")
        assertEquals(sids.size, 2, s"no third session may exist, got: $sids")
        assertEquals(events.count(_.contains("\"type\":\"dispatcher-concurrency-refused\"")), 0,
          "the default (gate off) must not refuse the second task's dispatcher")
    }
  }

  // ── (2) terminal tears down its OWN slot only ──

  test("(2) task A reaches completed ⇒ exactly A's session is torn down (event carries task=A) while B's session stays alive") {
    val ws = tempRoot / "ws-slot-terminal"
    os.makeDir.all(ws)
    val system = ActorSystem(s"slot-term-${scala.util.Random.nextInt(100000)}")
    withScanner {
      for
        llm <- IO.pure(new RecordingLlm)
        resources <- mkResources(system, tempRoot, llm.handle)
        rt <- mount("slot-term-proj", ws, system, resources)
        actorRef = rt.actorRef.getOrElse(sys.error("ProjectActor must be spawned by mount"))
        tidA = newTask("slot terminal A")
        tidB = newTask("slot terminal B")
        _ <- trigger(actorRef, "task A brief", tidA)
        _ <- waitUntil(20.seconds)(llm.streamsDone.get.map(_ >= 1))
        _ <- trigger(actorRef, "task B brief", tidB)
        _ <- waitUntil(20.seconds)(llm.streamsDone.get.map(_ >= 2))
        both <- dispatcherEntries(resources)
        // drive ONLY task A to its terminal state
        _ <- IO(TaskLedgerStore.open().completeSync(tidA))
        _ <- waitUntil(20.seconds)(dispatcherEntries(resources).map(_.size == 1))
        sidsAfter <- dispatcherEntries(resources)
        events <- eventLines(ws)
        _ <- system.stopAll.handleErrorWith(_ => IO.unit)
      yield
        // the surviving session is exactly the one that is NOT the session A's terminal
        // event names (the event line carries both the session id and the task id)
        val terminalA = events.filter(l => l.contains("\"type\":\"dispatcher-task-terminal\"") && l.contains(s"task=$tidA"))
        assert(terminalA.nonEmpty,
          s"a dispatcher-task-terminal event for task A must exist, got:\n${events.filter(_.contains("dispatcher-task-terminal")).mkString("\n")}")
        val sidA = terminalA.flatMap(l => both.find(s => l.contains(s))).headOption
        assert(sidA.isDefined, s"A's terminal event must name one of the two live sessions, got: $terminalA")
        assertEquals(sidsAfter.size, 1, s"exactly B's session must survive, got: $sidsAfter")
        assertEquals(sidsAfter.toSet, both.filter(_ != sidA.get).toSet,
          s"completing task A must tear down exactly A's slot; B's session must survive, got: $sidsAfter vs before $both")
        assert(!events.exists(l => l.contains("\"type\":\"dispatcher-task-terminal\"") && l.contains(s"task=$tidB")),
          "task B must NOT be terminalized by A's completion")
    }
  }

  // ── (3) empty slot ⇒ rebuild on re-trigger ──

  test("(3) empty-slot rebuild: after A's slot was torn down, a re-trigger of the SAME task id spawns a FRESH session (new id), never resurrects the old one") {
    val ws = tempRoot / "ws-slot-rebuild"
    os.makeDir.all(ws)
    val system = ActorSystem(s"slot-reb-${scala.util.Random.nextInt(100000)}")
    withScanner {
      for
        llm <- IO.pure(new RecordingLlm)
        resources <- mkResources(system, tempRoot, llm.handle)
        rt <- mount("slot-reb-proj", ws, system, resources)
        actorRef = rt.actorRef.getOrElse(sys.error("ProjectActor must be spawned by mount"))
        tidA = newTask("slot rebuild A")
        _ <- trigger(actorRef, "task A brief round 1", tidA)
        _ <- waitUntil(20.seconds)(dispatcherEntries(resources).map(_.nonEmpty))
        sid1 <- dispatcherEntries(resources).map(_.head)
        _ <- waitUntil(20.seconds)(llm.streamsDone.get.map(_ >= 1))
        // empty the slot through the task-terminal anchor (deterministic teardown;
        // the ledger entry itself is NOT what the slot map consults - the slot is gone)
        _ <- IO(TaskLedgerStore.open().completeSync(tidA))
        _ <- waitUntil(20.seconds)(dispatcherEntries(resources).map(_.isEmpty))
        // re-trigger the SAME task id: the slot-key lookup misses ⇒ spawn a fresh bound session
        _ <- trigger(actorRef, "task A brief round 2", tidA)
        _ <- waitUntil(20.seconds)(llm.streamsDone.get.map(_ >= 2))
        sids2 <- dispatcherEntries(resources)
        _ <- system.stopAll.handleErrorWith(_ => IO.unit)
      yield
        assertEquals(sids2.size, 1, s"exactly one live session after the rebuild, got: $sids2")
        assert(sids2.head != sid1,
          s"the rebuild must spawn a NEW session id (slot-key miss ⇒ spawn), got: rebuild=${sids2.head} first=$sid1")
    }
  }

end DispatcherTaskSlotSpec
