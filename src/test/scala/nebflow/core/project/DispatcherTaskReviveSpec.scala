package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentLibrary, SharedResources}
import nebflow.shared.PathUtil // W1 shim: main had nebflow.core.PathUtil; PR moved it to shared
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.core.{RateLimiter, SessionStore} // W1 shim: main had them in nebflow.gateway; PR moved the family to core
import nebflow.llm.ModelCandidate // W1 shim: ThinkingConfig split out — PR moved it to shared
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}
import nebflow.shared.ThinkingConfig // W1 shim: main had nebflow.llm.ThinkingConfig; PR moved it to shared

import scala.concurrent.duration.*

/**
 * Mail task-continuation batch (2026-09-28) - the ACTOR face of the revive: the
 * "new session guarantee". A Mail revive trigger (`TriggerDispatcher(revive = true)`)
 * must NEVER deliver into a leftover session: if the task's slot still holds a live
 * dispatcher (the residue the 30 s task-terminal sweep window can leave behind), that
 * residue is EVICTED (existing teardown semantics: registry deregister + `Stop` + bridge
 * stop + unconsumed-item trace) inside the same atomic slot judgment, and a FRESH bound
 * session takes over — the receipt face's "dispatcher re-mounted" is only true if the old
 * registration verifiably died. The evict leaves its own audit event
 * (`dispatcher-revive-evict`, distinguishable from the sweep's `dispatcher-task-terminal`).
 *
 * Pinned here:
 *  1. **residue eviction**: with the task-terminal anchor OFF (deterministic residue —
 *     the idle window is huge, nothing else can tear the slot down), completing the task
 *     leaves the session in place; a `revive = true` trigger removes exactly THAT session
 *     and spawns a DIFFERENT live one, and the audit event names the evicted session and
 *     `reason=revived-by-mail`;
 *  2. **no residue ⇒ plain fresh spawn**: the same trigger with an empty slot spawns
 *     normally and writes NO revive-evict event (no eviction noise on the clean path).
 *
 * Harness = `DispatcherTaskSlotSpec` verbatim (real ActorSystem + real registry +
 * RecordingLlm that ends the turn on the first text delta; the task ledger is driven
 * through its real write face).
 */
class DispatcherTaskReviveSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-dispatcher-task-revive"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "project-dispatcher")
  os.write.over(
    tempRoot / "agents" / "project-dispatcher" / "agent.json",
    """{"name":"project-dispatcher","description":"task revive test","tools":[],"category":"standalone"}"""
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
    res: SharedResources,
    anchor: Boolean
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
      // a huge idle window: nothing but the revive leg may remove a slot (the residue
      // must be deterministic, never sweep-dependent)
      dispatcherIdleWindowMs = Some(3600_000L),
      dispatcherLifecycleAnchorTaskTerminal = Some(anchor),
      dispatcherMaxConcurrentSessions = None
    )

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  private def withScanner[A](body: IO[A]): IO[A] =
    ProjectActor.ttlScanner(1.second).background.use(_ => body)

  private def newTask(title: String): String =
    TaskLedgerStore.open()
      .createSyncReturningId(title = title, actor = TaskLedgerHistory.Actors.Dispatcher)
      .getOrElse(sys.error("create must succeed"))

  private type ActorRefT = nebflow.actor.ActorRef[ProjectActor.ProjectCommand]

  private def trigger(ref: ActorRefT, text: String, tid: String, revive: Boolean): IO[Unit] =
    (ref ! ProjectActor.ProjectCommand.TriggerDispatcher(text, "nebula-root", ProjectActor.SourceTask, None, Some(tid), revive)).void

  // ── (1) residue eviction: the revive trigger never reuses a leftover session ──

  test("(1) revive evicts the residue slot: old session destroyed (audit event reason=revived-by-mail), a DIFFERENT live session takes over") {
    val ws = tempRoot / "ws-revive-evict"
    os.makeDir.all(ws)
    val system = ActorSystem(s"revive-evict-${scala.util.Random.nextInt(100000)}")
    withScanner {
      for
        llm <- IO.pure(new RecordingLlm)
        resources <- mkResources(system, tempRoot, llm.handle)
        rt <- mount("revive-evict-proj", ws, system, resources, anchor = false)
        actorRef = rt.actorRef.getOrElse(sys.error("ProjectActor must be spawned by mount"))
        tid = newTask("revive evict case")
        _ <- trigger(actorRef, "round 1 brief", tid, revive = false)
        _ <- waitUntil(20.seconds)(llm.streamsDone.get.map(_ >= 1))
        sid1 <- dispatcherEntries(resources).map(_.headOption)
        // the task goes terminal; with the anchor OFF the slot survives (deterministic
        // residue — exactly what the 30 s sweep window can leave in production)
        _ <- IO(TaskLedgerStore.open().completeSync(tid))
        _ <- IO.sleep(2.5.seconds) // let 2 sweep ticks pass: nothing else may remove the slot
        residue <- dispatcherEntries(resources)
        // the Mail revive trigger arrives
        _ <- trigger(actorRef, "continuation brief (revive)", tid, revive = true)
        _ <- waitUntil(20.seconds)(llm.streamsDone.get.map(_ >= 2))
        after <- dispatcherEntries(resources)
        events <- eventLines(ws)
        _ <- system.stopAll.handleErrorWith(_ => IO.unit)
      yield
        val oldSid = sid1.getOrElse(sys.error("the first session must have spawned"))
        assertEquals(residue, List(oldSid),
          s"with the anchor off, the completed task's session must still be in place (the residue this test evicts), got: $residue")
        assertEquals(after.size, 1, s"exactly one live session after the revive, got: $after")
        assert(after.head != oldSid,
          s"the revive trigger must NEVER reuse the residue session, got: revive=${after.head} residue=$oldSid")
        val evictEvents = events.filter(l => l.contains("\"type\":\"dispatcher-revive-evict\"") && l.contains(s"task=$tid"))
        assert(evictEvents.nonEmpty,
          s"a dispatcher-revive-evict event naming the task must exist, got:\n${events.filter(_.contains("dispatcher")).mkString("\n")}")
        assert(evictEvents.exists(_.contains(oldSid)) && evictEvents.exists(_.contains("reason=revived-by-mail")),
          s"the evict event must name the destroyed session and the cause, got: $evictEvents")
    }
  }

  // ── (2) no residue ⇒ plain fresh spawn, zero evict noise ──

  test("(2) revive trigger with an empty slot spawns normally: one fresh bound session, no dispatcher-revive-evict event") {
    val ws = tempRoot / "ws-revive-clean"
    os.makeDir.all(ws)
    val system = ActorSystem(s"revive-clean-${scala.util.Random.nextInt(100000)}")
    withScanner {
      for
        llm <- IO.pure(new RecordingLlm)
        resources <- mkResources(system, tempRoot, llm.handle)
        rt <- mount("revive-clean-proj", ws, system, resources, anchor = true)
        actorRef = rt.actorRef.getOrElse(sys.error("ProjectActor must be spawned by mount"))
        tid = newTask("revive clean case")
        // the real revive sequence: the Mail tool has ALREADY flipped the entry back to
        // open (reviveSync) by the time the trigger reaches the actor, and no session
        // was ever mounted for it ⇒ the slot is empty
        _ <- IO(TaskLedgerStore.open().completeSync(tid))
        _ <- IO(TaskLedgerStore.open().reviveSync(tid))
        _ <- trigger(actorRef, "continuation brief (clean revive)", tid, revive = true)
        _ <- waitUntil(20.seconds)(llm.streamsDone.get.map(_ >= 1))
        sessions <- dispatcherEntries(resources)
        events <- eventLines(ws)
        _ <- system.stopAll.handleErrorWith(_ => IO.unit)
      yield
        assertEquals(sessions.size, 1, s"exactly one fresh bound session, got: $sessions")
        assert(!events.exists(_.contains("\"type\":\"dispatcher-revive-evict\"")),
          "the clean path must write no revive-evict event")
    }
  }

end DispatcherTaskReviveSpec
