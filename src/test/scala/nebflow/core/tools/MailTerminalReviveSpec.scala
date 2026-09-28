package nebflow.core.tools

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import munit.FunSuite
import nebflow.actor.{ActorRef, ActorSystem, Behavior, Behaviors}
import nebflow.agent.{AgentDef, AgentLibrary, SharedResources, SubAgentTaskStore}
import nebflow.core.FileChangeTracker
import nebflow.core.PathUtil
import nebflow.core.project.{FlowMapStore, NodeEngine, ProjectActor, ProjectDef, ProjectRuntime, ProjectRuntimeRegistry, TaskLedgerData, TaskLedgerHistory, TaskLedgerStore}
import nebflow.core.task.FileTaskStore
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import java.nio.file.Files
import java.time.Instant
import scala.concurrent.duration.*

/**
 * Mail task-continuation batch (2026-09-28 · the author's 10:49 order) - the Mail-tool
 * face of the terminal-task revive. The OLD gate refused `Mail(task=N)` for a terminal
 * task (`TASK_STATUS` terminal-refusal); the NEW gate REVIVES it: the entry flips back to
 * `open` (`TaskLedgerStore.reviveSync` — one `kind=revive` history event + the author's
 * verbatim note line), a FRESH dispatcher session is mounted (`TriggerDispatcher(revive =
 * true)` — the actor evicts any residue slot, never reuses the old session), and the
 * receipt header reports `Task 上下文已继承（前态=<prior>，已重启）· dispatcher re-mounted`.
 *
 * Pinned here (the five case spec of the task book, each mechanically decidable):
 *  1. **completed revival**: receipt header verbatim (前态=completed), status flips to
 *     `open`, the verbatim note line exists on the note timeline, and the trigger carries
 *     `revive = true`;
 *  2. **closed revival**: the same primitive from the other terminal state (前态=closed);
 *  3. **the non-continuable face stays an error**: a never-created number AND a pruned
 *     (30-day terminal retention, real lazy-prune path) number both return the UNCHANGED
 *     `TASK_NOT_FOUND` text, and nothing is delivered;
 *  4. **open path zero change**: byte-identical receipt header, no revive flip, no revive
 *     note, `revive = false`;
 *  5. **double-continuation idempotency**: the second `Mail(task=N)` finds the task open ⇒
 *     ordinary path (no second flip, no second revive note, no revive flag).
 *
 * Harness = `MailAutoCreateTaskChainSpec` verbatim (temp `dataRoot` — the ledger and its
 * history never touch the real `~/.nebflow`; the project registry is cleared around every
 * case; a recording ProjectCommand actor captures the trigger so the `revive` flag and the
 * slot-binding taskId are asserted, no real dispatcher session is spawned).
 */
class MailTerminalReviveSpec extends FunSuite:

  private val tempRoot: os.Path = os.Path(Files.createTempDirectory("nb-mail-revive"))
  private val originalRoot: os.Path = PathUtil.dataRoot

  override def beforeAll(): Unit =
    PathUtil.setDataRoot(tempRoot)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    os.remove.all(tempRoot)

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  // ── fixtures (MailAutoCreateTaskChainSpec verbatim) ──

  private def qIn(fields: (String, String)*): JsonObject =
    JsonObject.fromIterable(fields.map((k, v) => k -> Json.fromString(v)))

  private def ctx(system: ActorSystem): ToolContext =
    ToolContext(
      projectRoot = os.pwd.toString,
      sessionId = Some("sid-mail-revive-nebula"),
      isDispatcher = false,
      projectName = None,
      agentDef = Some(AgentDef(name = "Nebula", description = "spec fixture")),
      actorSystem = Some(system)
    )

  /** Records every ProjectCommand the Mail project leg sends (the `revive` flag and the
    * slot-binding taskId both travel on TriggerDispatcher — asserted, never assumed). */
  private def mkRecordingActor(
      record: Ref[IO, List[ProjectActor.ProjectCommand]]
  ): Behavior[ProjectActor.ProjectCommand] =
    def loop: Behavior[ProjectActor.ProjectCommand] =
      Behaviors.receiveMessage[ProjectActor.ProjectCommand](cmd => record.update(_ :+ cmd).as(loop))
    loop

  private class StubLlm extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected"))
    def sendStream(
        req: LlmRequest,
        onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  private def mkResources(system: ActorSystem): IO[SharedResources] =
    for
      dispatcher <- cats.effect.std.Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
    yield SharedResources(
      llm = new StubLlm,
      dispatcher = dispatcher,
      sessionStore = SessionStore(tempRoot / "sessions", tempRoot / "tasks"),
      projectRoot = os.pwd,
      thinkingConfigRef = thinkingRef,
      rateLimiter = rateLimiter,
      fileChangeTracker = tracker,
      contextWindow = 100_000,
      agentLibrary = new AgentLibrary(tempRoot / "agents"),
      taskStore = FileTaskStore,
      historyArchiver = null,
      fileLockManager = fileLocks,
      sessionModelOverrides = modelOverrides,
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = system,
      subAgentTaskStore = new SubAgentTaskStore(tempRoot / "subagent-tasks"),
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

  private def mountWithRecordingActor(
      name: String,
      ws: os.Path,
      system: ActorSystem,
      res: SharedResources,
      record: Ref[IO, List[ProjectActor.ProjectCommand]]
  ): IO[ActorRef[ProjectActor.ProjectCommand]] =
    for
      ref <- system.spawn(mkRecordingActor(record), s"$name-recorder")
      store <- FlowMapStore.open(name, ws.toString)
      engine = new NodeEngine(
        store, system, res,
        wsSendFn = (_: Json) => IO.unit,
        workspace = ws.toString,
        rootSessionId = "nebula-root",
        projectName = name,
        emitEvent = (_: String, _: String, _: Json) => IO.unit,
        notifyTriggerOverride = Some((_: String) => IO.unit),
        reportGateHold = Some(false)
      )
      pd = ProjectDef(name = name, workspace = ws.toString, agentFile = (ws / "AGENTS.md").toString,
        createdAt = System.currentTimeMillis())
      rt = ProjectRuntime(pd, store, engine, system, res, Some(ref))
      _ <- ProjectRuntimeRegistry.register(rt)
    yield ref

  /** Create one task through the real ledger write face and return the bare id. */
  private def newTask(title: String): String =
    TaskLedgerStore.open()
      .createSyncReturningId(title = title, actor = TaskLedgerHistory.Actors.Dispatcher)
      .getOrElse(sys.error("create must succeed"))

  /** The revive-class note lines on the task's note timeline (the flip annotation only;
    * Mail-body notes are `from=nebula` and never carry the verbatim string). */
  private def reviveNoteCount(tid: String): Int =
    TaskLedgerHistory.open()
      .readFor(tid, Int.MaxValue, TaskLedgerHistory.isNoteEvent).events
      .count(_.text.exists(_.contains(TaskLedgerStore.ReviveNoteText)))

  /** (receipt, trigger info) after one `Mail(address="project:…", task=…)`. */
  private def mailWithTask(
      name: String,
      ws: os.Path,
      system: ActorSystem,
      task: Option[String],
      expectedTriggers: Int
  ): IO[(Either[ToolError, String], List[(String, Boolean)])] =
    for
      record <- Ref.of[IO, List[ProjectActor.ProjectCommand]](Nil)
      resources <- mkResources(system)
      _ <- mountWithRecordingActor(name, ws, system, resources, record)
      res <- task match
        case Some(tid) => MailTool.call(qIn("address" -> s"project:$name", "message" -> "continuation mail", "task" -> tid), ctx(system))
        case None      => MailTool.call(qIn("address" -> s"project:$name", "message" -> "continuation mail"), ctx(system))
      _ <- waitUntil(10.seconds)(record.get.map(_.size >= expectedTriggers))
      cmds <- record.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      val triggers = cmds.collect {
        case ProjectActor.ProjectCommand.TriggerDispatcher(_, _, _, _, tid, revive) => (tid.getOrElse(""), revive)
      }
      (res, triggers)

  // ── (1) completed 续行 ──

  test("(1) completed revival: receipt `…Task 上下文已继承（前态=completed，已重启）· dispatcher re-mounted` verbatim, status flips open, verbatim note line, trigger carries revive=true"):
    val ws = tempRoot / "ws-revive-completed"
    os.makeDir.all(ws)
    val system = ActorSystem(s"mail-revive-c-${java.util.UUID.randomUUID().toString.take(6)}")
    try
      val tid = newTask("revive completed case")
      assert(TaskLedgerStore.open().completeSync(tid).isRight)
      val (res, triggers) = mailWithTask("revive-proj-completed", ws, system, Some(tid), 1).unsafeRunSync()

      val receipt = res match
        case Right(r) => r
        case Left(e)  => fail(s"the completed task must be REVIVED, not refused, got error: ${e.message}")
      assertEquals(receipt,
        s"[task #$tid] Task 上下文已继承（前态=completed，已重启）· dispatcher re-mounted",
        "the revive receipt header must be the author's verbatim string with 前态=completed")

      val entry = TaskLedgerStore.open().findSync(tid).getOrElse(fail("the revived entry must still exist"))
      assertEquals(entry.status, TaskLedgerStore.Status.Open, "the revival flips the terminal state back to open")
      assertEquals(reviveNoteCount(tid), 1,
        "exactly one note-timeline line with the author's verbatim body must exist after one revival")
      assertEquals(triggers, List((tid, true)),
        s"the trigger must carry the bare taskId and revive=true, got: $triggers")
    finally
      system.stopAll.handleErrorWith(_ => IO.unit).unsafeRunSync()
      ProjectRuntimeRegistry.clear

  // ── (2) closed 续行 ──

  test("(2) closed revival: the same primitive from the other terminal state (前态=closed)"):

    val ws = tempRoot / "ws-revive-closed"
    os.makeDir.all(ws)
    val system = ActorSystem(s"mail-revive-x-${java.util.UUID.randomUUID().toString.take(6)}")
    try
      val tid = newTask("revive closed case")
      assert(TaskLedgerStore.open().closeSync(tid).isRight)
      val (res, triggers) = mailWithTask("revive-proj-closed", ws, system, Some(tid), 1).unsafeRunSync()

      val receipt = res match
        case Right(r) => r
        case Left(e)  => fail(s"the closed task must be REVIVED (re-open), not refused, got error: ${e.message}")
      assertEquals(receipt,
        s"[task #$tid] Task 上下文已继承（前态=closed，已重启）· dispatcher re-mounted",
        "the revive receipt header must name the actual prior state (closed)")
      assertEquals(TaskLedgerStore.open().findSync(tid).map(_.status), Some(TaskLedgerStore.Status.Open))
      assertEquals(reviveNoteCount(tid), 1)
      assertEquals(triggers, List((tid, true)))
    finally
      system.stopAll.handleErrorWith(_ => IO.unit).unsafeRunSync()
      ProjectRuntimeRegistry.clear

  // ── (3) 不可续面零变化 ──

  test("(3) non-continuable face unchanged: a never-created number returns the byte-identical TASK_NOT_FOUND text and delivers nothing"):
    val ws = tempRoot / "ws-revive-missing"
    os.makeDir.all(ws)
    val system = ActorSystem(s"mail-revive-m-${java.util.UUID.randomUUID().toString.take(6)}")
    try
      val (res, triggers) = mailWithTask("revive-proj-missing", ws, system, Some("424242"), 0).unsafeRunSync()

      val err = res match
        case Left(e)  => e.message
        case Right(r) => fail(s"a never-created task number must stay an explicit error, got receipt: $r")
      assert(err.contains("(TASK_NOT_FOUND)"), s"the error code face is unchanged, got: $err")
      assert(err.contains("it was never created (or it was pruned after reaching a terminal state)"),
        s"the readable-reason face is byte-unchanged, got: $err")
      assertEquals(triggers, Nil, "nothing may be delivered for a non-existent task")
    finally
      system.stopAll.handleErrorWith(_ => IO.unit).unsafeRunSync()
      ProjectRuntimeRegistry.clear

  test("(3b) pruned face unchanged: a 30-day-expired terminal entry is lazily pruned by the next create, then Mail(task=it) = TASK_NOT_FOUND (real prune path)"):
    val ws = tempRoot / "ws-revive-pruned"
    os.makeDir.all(ws)
    val system = ActorSystem(s"mail-revive-p-${java.util.UUID.randomUUID().toString.take(6)}")
    try
      val ledger = TaskLedgerStore.open()
      val tid = newTask("prune-me terminal task")
      assert(ledger.completeSync(tid).isRight)
      // Backdate the terminal timestamp 31 days (test-side rewrite of the ledger file —
      // the same on-disk shape the store reads), then let the NEXT create run the real
      // lazy prune.
      val f = tempRoot / TaskLedgerStore.FileName
      val backdatedAt = Instant.now().minusSeconds(31L * 24 * 3600).toString
      val data = io.circe.parser.decode[TaskLedgerData](os.read(f)).getOrElse(fail("ledger file must decode"))
      val backdated = data.copy(tasks = data.tasks.map(t =>
        if t.id == tid then t.copy(completedAt = Some(backdatedAt)) else t))
      os.write.over(f, backdated.asJson.noSpaces)
      assert(ledger.createSyncReturningId("prune trigger").isRight)
      assertEquals(ledger.findSync(tid), None, "the 31-day terminal entry must be gone after the lazy prune")

      val (res, triggers) = mailWithTask("revive-proj-pruned", ws, system, Some(tid), 0).unsafeRunSync()
      val err = res match
        case Left(e)  => e.message
        case Right(r) => fail(s"a pruned task number must stay TASK_NOT_FOUND (the object is gone), got: $r")
      assert(err.contains("(TASK_NOT_FOUND)"), s"got: $err")
      assertEquals(triggers, Nil, "nothing may be delivered for a pruned task")
    finally
      system.stopAll.handleErrorWith(_ => IO.unit).unsafeRunSync()
      ProjectRuntimeRegistry.clear

  // ── (4) 非终态路径零变化 ──

  test("(4) open path zero change: byte-identical triggered receipt, no revive flip, no revive note, revive=false"):
    val ws = tempRoot / "ws-revive-open"
    os.makeDir.all(ws)
    val system = ActorSystem(s"mail-revive-o-${java.util.UUID.randomUUID().toString.take(6)}")
    try
      val tid = newTask("plain open task")
      val (res, triggers) = mailWithTask("revive-proj-open", ws, system, Some(tid), 1).unsafeRunSync()

      val receipt = res match
        case Right(r) => r
        case Left(e)  => fail(s"an open task continues exactly as before, got error: ${e.message}")
      assertEquals(receipt,
        s"[task #$tid] Project 'revive-proj-open' dispatcher triggered",
        "the ordinary receipt header must be byte-identical to the historical string")
      assertEquals(TaskLedgerStore.open().findSync(tid).map(_.status), Some(TaskLedgerStore.Status.Open),
        "an open task is NOT re-flipped by its own continuation Mail")
      assertEquals(reviveNoteCount(tid), 0, "no revive note on the ordinary path")
      assertEquals(triggers, List((tid, false)), "the ordinary trigger carries revive=false")
    finally
      system.stopAll.handleErrorWith(_ => IO.unit).unsafeRunSync()
      ProjectRuntimeRegistry.clear

  // ── (5) 连续两次续行幂等 ──

  test("(5) double continuation is idempotent: the second Mail finds the task open ⇒ ordinary path, no second flip / no second revive note"):
    val ws = tempRoot / "ws-revive-twice"
    os.makeDir.all(ws)
    val system = ActorSystem(s"mail-revive-t-${java.util.UUID.randomUUID().toString.take(6)}")
    try
      val tid = newTask("revive twice case")
      assert(TaskLedgerStore.open().completeSync(tid).isRight)

      // first Mail: the revival
      val (res1, triggers1) = mailWithTask("revive-proj-twice", ws, system, Some(tid), 1).unsafeRunSync()
      assert(res1.exists(_.contains("Task 上下文已继承（前态=completed，已重启）· dispatcher re-mounted")),
        s"the first Mail must revive, got: $res1")
      assertEquals(triggers1, List((tid, true)))
      assertEquals(reviveNoteCount(tid), 1)

      // second Mail: the task is open again ⇒ the ordinary path, no second revival
      val (res2, triggers2) = mailWithTask("revive-proj-twice", ws, system, Some(tid), 1).unsafeRunSync()
      val receipt2 = res2 match
        case Right(r) => r
        case Left(e)  => fail(s"the second continuation must NOT error (idempotent ordinary path), got: ${e.message}")
      assertEquals(receipt2,
        s"[task #$tid] Project 'revive-proj-twice' dispatcher triggered",
        "the second Mail takes the ordinary receipt face, byte-identical")
      assertEquals(TaskLedgerStore.open().findSync(tid).map(_.status), Some(TaskLedgerStore.Status.Open),
        "no second flip (open → open is not a revival)")
      assertEquals(reviveNoteCount(tid), 1, "no second revive note may be written")
      assertEquals(triggers2, List((tid, false)), "the second trigger carries revive=false")
    finally
      system.stopAll.handleErrorWith(_ => IO.unit).unsafeRunSync()
      ProjectRuntimeRegistry.clear

end MailTerminalReviveSpec
