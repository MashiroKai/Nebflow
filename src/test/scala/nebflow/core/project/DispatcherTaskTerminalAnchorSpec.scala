package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentLibrary, SharedResources}
import nebflow.shared.PathUtil
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.llm.ModelCandidate
import nebflow.shared.ThinkingConfig // W1 shim: main had nebflow.llm.ThinkingConfig; PR moved it to shared
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
 *     silent over-spawn); the refusal summary must carry **all four elements** (project name ∧
 *     current live count ∧ cap ∧ way out) on the event line alone;
 *  ⑤ **5.5 the PASSING side**: 0 active ∧ cap = 1 ⇒ the first spawn is **allowed** (the
 *     session is really registered, its first turn really ran, and the live count reads 1);
 *  ⑥ the **default-value leg**: with no override (`maxConcurrent = None` ⇒ read-through to
 *     `Defaults.DispatcherMaxConcurrentSessions`) the passing side still holds, and the default
 *     is **pinned to 0** (the "one task one dispatcher" guard — mailmodel batch 2026-09-25
 *     ruling (a) moved the default 1 → 0: with per-task dispatcher slots a project-wide cap
 *     would refuse the second task's dispatcher; 0 = gate off; without this assertion a
 *     silent change of the default would never turn any test red);
 *  ⑦ the **`≤0` zero-cost bypass** (behaviour face of "gate off"): `maxConcurrent = Some(0)` ⇒
 *     **zero** `dispatcher-concurrency-refused` events and the session **really spawns**.
 *
 * **Counting-point causality (pinned, author's wording)**: the gate is checked **before** the
 * spawn and this spawn is **not counted in its own judgment** (`spawnFresh`'s first statement
 * is `underConcurrencyCap` — `ProjectActor.scala` dispatchTask leg and the reentry leg hit the
 * same single point). **If someone later moves the gate to after the spawn, `count < cap`
 * becomes semantically `cap-1`, and this spec's (5)/(6) turn red.**
 *
 * **Proxy criterion declaration (hard declaration)**: the WARN face is not directly observable
 * from this spec (the logger is a process-global side channel), so case (4) asserts the four
 * elements on the **event line alone** — the summary now carries `way=` (the way out), which
 * makes the event face self-sufficient. That the WARN carries the same text is guaranteed by
 * construction (both read the single constant `FlowMapEventLog.DispatcherConcurrencyCapWayOut`);
 * the spec's "zero events / zero WARN" style judgments for the bypass case are **proxy
 * criteria** (declared as such): zero `dispatcher-concurrency-refused` events + a really
 * spawned session stand in for "no WARN fired".
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
  //
  // Counting-point causality (pinned, author's wording): the gate is checked BEFORE the spawn
  // and this spawn is NOT counted in its own judgment (spawnFresh's first statement is
  // underConcurrencyCap; the reentry leg hits the same single point). If someone later moves
  // the gate to after the spawn, `count < cap` becomes semantically `cap-1`, and this spec's
  // (5)/(5b)/(6) turn red.

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
          // W1 shim: main wrote `nebflow.actor.AgentCommand` here; the merge moved it to actor.
          nebflow.actor.Behaviors.receiveMessage[nebflow.actor.AgentCommand](_ =>
            IO.pure(nebflow.actor.Behaviors.stopped[nebflow.actor.AgentCommand])),
          s"ghost-${scala.util.Random.nextInt(100000)}")
        _ <- resources.agentRegistry.update(_ + (
          s"${ProjectActor.DispatcherSessionPrefix}ghost01" -> nebflow.actor.AgentRecord(
            s"${ProjectActor.DispatcherSessionPrefix}ghost01",
            ghostRef,
            nebflow.actor.AgentKind.Flow,
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
        // 🔴 four elements on the event line ALONE (visible + locatable, not silent):
        // ① project name ② current live count ③ cap ④ way out. Splitting the four elements
        // across "the summary + the WARN" and calling that done is forbidden — the event face
        // is self-sufficient because the summary now carries `way=`.
        assert(line.contains("project=anchor-cap-proj"), s"four-element check ①: the event must carry the project name, got:\n$line")
        assert(line.contains("active=1") && line.contains("cap=1"), s"four-element checks ②③: live count and cap, got:\n$line")
        assert(line.contains("way="), s"four-element check ④: the event must carry the way out, got:\n$line")
        assert(line.contains("maxConcurrentSessions"), s"the way out must name the actionable knob, got:\n$line")
        assertEquals(turns.size, 0, "the refusal must happen before spawn — zero LLM turns (no session started despite the silent over-spawn being forbidden)")
    }
  }

  // ── (5) the PASSING side of the cap: 0 active ∧ cap=1 ⇒ the first spawn is allowed ──

  test("(5) concurrency cap boundary — the PASSING side: 0 active ∧ cap=1 ⇒ the first spawn is allowed (exactly one; the session is really registered and really ran its first turn; the live count reads 1)") {
    val ws = tempRoot / "ws-cap-pass"
    os.makeDir.all(ws)
    val system = ActorSystem(s"anchor-cappass-${scala.util.Random.nextInt(100000)}")
    withScanner {
      for
        llm <- IO.pure(new RecordingLlm)
        resources <- mkResources(system, tempRoot, llm.handle)
        // cap = 1 explicitly, and the registry is EMPTY (0 active) ⇒ strictly below the cap
        rt <- mount("anchor-cappass-proj", ws, system, resources, Some(3600_000L), Some(true), Some(1))
        actorRef = rt.actorRef.getOrElse(sys.error("ProjectActor must be spawned by mount"))
        tid = newTask("task-E")
        _ <- (actorRef ! ProjectActor.ProjectCommand.TriggerDispatcher("task-E", "nebula-root", ProjectActor.SourceTask, None, Some(tid))).void
        _ <- waitUntil(20.seconds)(dispatcherEntries(resources).map(_.nonEmpty))
        sid1 <- dispatcherEntries(resources).map(_.head)
        // the spawn really ran: at least one LLM turn happened inside the new session
        _ <- waitUntil(20.seconds)(llm.streamsDone.get.map(_ >= 1))
        // the count basis reads 1 after the spawn (the registry is the single counting point)
        activeAfter <- resources.agentRegistry.get.map(
          _.values.count(r => r.kind == nebflow.actor.AgentKind.Flow && r.project.contains("anchor-cappass-proj")))
        events <- eventLines(ws)
        sidAfter <- dispatcherEntries(resources)
        streamsDone <- llm.streamsDone.get
        _ <- system.stopAll.handleErrorWith(_ => IO.unit)
      yield
        // the explicit live-count reading (criterion ② of the passing side): the registry is
        // the single counting point ⇒ after an allowed first spawn it must read exactly 1
        assertEquals(activeAfter, 1, "after an allowed first spawn the project's live dispatcher count must read exactly 1")
        // the spawned session is really registered under the dispatcher prefix (exactly one)
        assertEquals(sidAfter.filter(_ == sid1), List(sid1),
          "the spawned session must be really registered under the dispatcher prefix")
        assert(streamsDone >= 1, "the allowed spawn must have really run its first LLM turn")
        // the passing side must NOT leave a refusal trace (refusal traces belong to the refusing side only)
        assertEquals(events.count(_.contains("\"type\":\"dispatcher-concurrency-refused\"")), 0,
          s"0 active ∧ cap=1 ⇒ allowed ⇒ zero refusal events, got:\n${events.filter(_.contains("dispatcher-concurrency-refused")).mkString("\n")}")
    }
  }

  // ── (5b) the default-value leg: no override ⇒ read-through default, pinned to 0 ──

  test("(5b) default-value leg: maxConcurrent = None (no helper override) ⇒ the read-through default applies to the passing side, and the default itself is pinned to 0 (the one-task-one-dispatcher guard)") {
    // the pin (mailmodel batch 2026-09-25, ruling (a)): the default moved 1 → 0 — with the
    // per-task slot model landed, the old project-wide singleton cap would refuse the second
    // task's dispatcher, which contradicts "one task gets its own dispatcher session". 0 =
    // gate off = unlimited; the prop override stays the explicit-cap escape hatch. Without
    // this assertion, silently moving the default again would never turn any test red.
    assertEquals(nebflow.shared.Defaults.DispatcherMaxConcurrentSessions, 0,
      "Defaults.DispatcherMaxConcurrentSessions must stay 0 (one task one dispatcher — the per-task slot model made the project-wide cap a wrong-task refusal; changing this default is a behaviour-visible change that must be declared)")
    val ws = tempRoot / "ws-cap-default"
    os.makeDir.all(ws)
    val system = ActorSystem(s"anchor-capdef-${scala.util.Random.nextInt(100000)}")
    withScanner {
      for
        llm <- IO.pure(new RecordingLlm)
        resources <- mkResources(system, tempRoot, llm.handle)
        // maxConcurrent = None ⇒ underConcurrencyCap reads through Defaults (== 0, asserted above)
        rt <- mount("anchor-capdef-proj", ws, system, resources, Some(3600_000L), Some(true), None)
        actorRef = rt.actorRef.getOrElse(sys.error("ProjectActor must be spawned by mount"))
        tid = newTask("task-F")
        _ <- (actorRef ! ProjectActor.ProjectCommand.TriggerDispatcher("task-F", "nebula-root", ProjectActor.SourceTask, None, Some(tid))).void
        _ <- waitUntil(20.seconds)(dispatcherEntries(resources).map(_.nonEmpty))
        sid1 <- dispatcherEntries(resources).map(_.head)
        _ <- waitUntil(20.seconds)(llm.streamsDone.get.map(_ >= 1))
        sidAfter <- dispatcherEntries(resources)
        events <- eventLines(ws)
        _ <- system.stopAll.handleErrorWith(_ => IO.unit)
      yield
        // gate off ⇒ the first spawn is allowed; exactly one session lives afterwards
        assertEquals(sidAfter, List(sid1),
          "under the read-through default (0 = gate off) the first spawn is allowed and exactly one session lives")
        assertEquals(events.count(_.contains("\"type\":\"dispatcher-concurrency-refused\"")), 0,
          "under the read-through default (0 = gate off) there must be zero refusal events")
    }
  }

  // ── (6) the ≤0 zero-cost bypass: gate off ⇒ zero refusals AND the session really spawns ──

  test("(6) gate-off bypass: maxConcurrent = Some(0) (≤0 = gate disabled) ⇒ zero dispatcher-concurrency-refused events AND the session really spawns (the only behaviour-face evidence of the off state)") {
    val ws = tempRoot / "ws-cap-off"
    os.makeDir.all(ws)
    val system = ActorSystem(s"anchor-capoff-${scala.util.Random.nextInt(100000)}")
    withScanner {
      for
        llm <- IO.pure(new RecordingLlm)
        resources <- mkResources(system, tempRoot, llm.handle)
        // cap = Some(0) ⇒ underConcurrencyCap short-circuits IO.pure(true) WITHOUT touching
        // the activeDispatcherCount face (the source-face zero-cost bypass; asserted below)
        rt <- mount("anchor-capoff-proj", ws, system, resources, Some(3600_000L), Some(true), Some(0))
        actorRef = rt.actorRef.getOrElse(sys.error("ProjectActor must be spawned by mount"))
        tid = newTask("task-G")
        _ <- (actorRef ! ProjectActor.ProjectCommand.TriggerDispatcher("task-G", "nebula-root", ProjectActor.SourceTask, None, Some(tid))).void
        _ <- waitUntil(20.seconds)(dispatcherEntries(resources).map(_.nonEmpty))
        sid1 <- dispatcherEntries(resources).map(_.head)
        _ <- waitUntil(20.seconds)(llm.streamsDone.get.map(_ >= 1))
        sidAfter <- dispatcherEntries(resources)
        streamsDone <- llm.streamsDone.get
        events <- eventLines(ws)
        _ <- system.stopAll.handleErrorWith(_ => IO.unit)
      yield
        // the gate-off semantics: the spawn goes through even though a count gate would
        // otherwise be in place — the session is really registered and really ran a turn
        assertEquals(sidAfter, List(sid1),
          "cap=0 (gate off) ⇒ the spawn must go through: the session is really registered")
        assert(streamsDone >= 1, "cap=0 (gate off) ⇒ the spawned session really ran its first turn")
        // 🔴 PROXY criterion (declared as a proxy): the WARN face is not observable from this
        // spec, so "zero refusal events AND a really spawned session" stands in for "no
        // refusal happened at all". 0 here means "did not happen", which is the EXPECTED
        // value — 0 is not a defect (and event-family counts are judged by exact `type`
        // matching, never by whole-line substring).
        assertEquals(events.count(_.contains("\"type\":\"dispatcher-concurrency-refused\"")), 0,
          s"cap=0 (gate off) ⇒ zero refusal events (expected 0 = did not happen, not a defect), got:\n${events.filter(_.contains("dispatcher-concurrency-refused")).mkString("\n")}")
    }
  }

  // ── (7) source face: the ≤0 branch is a zero-cost bypass that does not touch the count ──

  test("(7) source-face criterion of the zero-cost bypass: the cap<=0 branch returns without reading activeDispatcherCount") {
    val src = os.read(os.pwd / "src" / "main" / "scala" / "nebflow" / "core" / "project" / "ProjectActor.scala")
    val body = src.slice(src.indexOf("private def underConcurrencyCap"), src.indexOf("private def refuseSpawnOnCap"))
    assert(body.contains("if cap <= 0 then IO.pure(true)"),
      "the cap<=0 leg must short-circuit with IO.pure(true) — a zero-cost bypass that never reads the live count")
    // the cap<=0 leg is the LAST statement of the body: everything AFTER the bypass leg is the
    // gated leg, and it must not be reachable from inside the bypass (a split-based check:
    // the segment before the bypass must contain no counting face at all)
    val beforeBypass = body.take(body.indexOf("if cap <= 0 then IO.pure(true)"))
    assert(!beforeBypass.contains("activeDispatcherCount(cfg)"),
      "the zero-cost bypass must not evaluate the counting face before short-circuiting")
    assert(body.contains("activeDispatcherCount(cfg).map(_ < cap)"),
      "the gated leg compares the registry count against the cap (the single counting point)")
  }
