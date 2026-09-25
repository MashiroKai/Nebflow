package nebflow.core.tools

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.circe.{Json, JsonObject}
import munit.FunSuite
import nebflow.actor.{ActorRef, ActorSystem, Behavior, Behaviors}
import nebflow.agent.{AgentDef, AgentLibrary, SharedResources, SubAgentTaskStore}
import nebflow.core.FileChangeTracker
import nebflow.core.PathUtil
import nebflow.core.project.{FlowMapStore, NodeEngine, ProjectActor, ProjectDef, ProjectRuntime, ProjectRuntimeRegistry, TaskLedgerStore}
import nebflow.core.task.FileTaskStore
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import java.nio.file.Files
import scala.concurrent.duration.*

/**
 * mailmodel r2 P0 fix (2026-09-25) - the pure-id chain pin for the Mail auto-create leg.
 *
 * Reproduction of the all-day TASK_NOT_FOUND (2026-09-25): the pre-fix auto-create consumed
 * `TaskLedgerStore.createSync`'s RENDERED result banner ("[OK] Task created #3 [open
 * @dispatcher] ... Reach `completed` ...") as the task id, so the whole banner string
 * traveled into the dispatcher slot key, the SpawnParams / SessionContext /
 * ToolContext.taskId attach fingerprint, the note append and the receipt header -- and
 * every session attached from such a receipt failed `TaskInfo` with TASK_NOT_FOUND (the
 * ledger lookup matches `entry.id`, never a banner).
 *
 * The fix: `createSyncReturningId` returns `entry.id` alone; the Mail auto-create branch
 * consumes it. Pinned here as ONE chained case (each step feeds the next, per the fix
 * order):
 *   1. the receipt matches `[task #<pure digits>]` and carries NO banner fragment (no
 *      "[OK]" / no "Reach");
 *   2. the id from that receipt hits the real ledger (`findSync`, entry open) AND is the
 *      exact taskId the TriggerDispatcher carried -- the value the slot table keys on and
 *      the session attach fingerprint derives from (captured by a recording
 *      ProjectCommand actor);
 *   3. `TaskInfo` reads THAT id (its zero-parameter attribution resolves from
 *      `ToolContext.taskId`; this exact step is what returned TASK_NOT_FOUND pre-fix).
 *
 * Red side (mutation): revert the MailTool auto-create branch to consume the rendered
 * banner => 1 goes red (the receipt contains "[OK]"), 2 findSync misses, 3 TASK_NOT_FOUND.
 *
 * Isolation: `PathUtil.dataRoot` points at a temp dir for the whole file (the ledger and
 * its history must never touch the real `~/.nebflow`), and the project registry (a global)
 * is cleared around every case. No real dispatcher session is spawned: the project runtime
 * is mounted manually with a recording actor, so the LLM/engine faces stay untouched.
 */
class MailAutoCreateTaskChainSpec extends FunSuite:

  private val tempRoot: os.Path = os.Path(Files.createTempDirectory("nb-mailauto-chain"))
  private val originalRoot: os.Path = PathUtil.dataRoot

  override def beforeAll(): Unit =
    PathUtil.setDataRoot(tempRoot)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    os.remove.all(tempRoot)

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  // ── fixtures ──

  private def qIn(fields: (String, String)*): JsonObject =
    JsonObject.fromIterable(fields.map((k, v) => k -> Json.fromString(v)))

  private def ctx(system: ActorSystem): ToolContext =
    ToolContext(
      projectRoot = os.pwd.toString,
      sessionId = Some("sid-mailauto-nebula"),
      isDispatcher = false,
      projectName = None,
      agentDef = Some(AgentDef(name = "Nebula", description = "spec fixture")),
      actorSystem = Some(system)
    )

  /** Records every ProjectCommand the Mail project leg sends. The TriggerDispatcher taskId
    * IS the value the slot table keys on and the session attach fingerprint derives from,
    * so capturing it closes the chain between the receipt and the attach. */
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

  /** Mount the project runtime MANUALLY with a recording actorRef (no real dispatcher
    * spawn -- this spec pins the Mail-tool face of the id chain, not the spawn engine;
    * same manual-mount shape as `WatchdogSelfMonitorSpec.mountProject`). */
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

  // ── the chained case ──

  test("chain: Mail auto-create => (1) pure-numeric [task #N] receipt, no banner => (2) the receipt id findSync-hits AND is the taskId the trigger carried => (3) TaskInfo reads that id (the 2026-09-25 TASK_NOT_FOUND reproduction + fix pin)"):
    val ws = tempRoot / "ws-chain"
    os.makeDir.all(ws)
    val system = ActorSystem(s"mailauto-chain-${java.util.UUID.randomUUID().toString.take(6)}")
    try
      val program =
        for
          record <- Ref.of[IO, List[ProjectActor.ProjectCommand]](Nil)
          resources <- mkResources(system)
          _ <- mountWithRecordingActor("chain-fixture", ws, system, resources, record)
          res <- MailTool.call(
            qIn("address" -> "project:chain-fixture", "message" -> "first mail, no task id -- the engine creates one"),
            ctx(system))
          // the send is fire-and-forget: wait for the recording actor to observe the trigger
          _ <- waitUntil(10.seconds)(record.get.map(_.nonEmpty))
          _ <- system.stopAll.handleErrorWith(_ => IO.unit)
          cmds <- record.get
        yield (res, cmds)

      val (res, recordedCmds) = program.unsafeRunSync()

      // (1) the receipt is the pure-id face
      val receipt = res match
        case Right(r) => r
        case Left(e)  => fail(s"the auto-create call must succeed, got error: ${e.message}")
      val digits = """^\[task #(\d+)\] Project 'chain-fixture' dispatcher triggered$"""
        .r.findFirstMatchIn(receipt)
        .getOrElse(fail(s"receipt must match [task #<pure digits>], got: $receipt"))
        .group(1)
      assert(!receipt.contains("[OK]"), s"the rendered banner must never travel in the receipt: $receipt")
      assert(!receipt.contains("Reach"), s"the banner's second line must never travel in the receipt: $receipt")

      // (2) the receipt id hits the real ledger AND is the taskId the trigger carried
      val entry = TaskLedgerStore.open().findSync(digits)
        .getOrElse(fail(s"findSync($digits) must hit the ledger (the attach id is a real entry id)"))
      assertEquals(entry.id, digits)
      assertEquals(entry.status, TaskLedgerStore.Status.Open)
      val carried = recordedCmds.collect {
        case ProjectActor.ProjectCommand.TriggerDispatcher(_, _, _, _, tid) => tid
      }.flatten
      assertEquals(carried, List(digits),
        s"the TriggerDispatcher must carry the PURE id (slot key / attach fingerprint source), got: $carried")

      // (3) TaskInfo reads THAT id (pre-fix this exact step returned TASK_NOT_FOUND)
      val info = TaskTool.infoSync(TaskLedgerStore.open(), ctx(system).copy(taskId = Some(digits)))
      info match
        case Left(e)  => fail(s"TaskInfo must read the attach id #$digits, got: ${e.message}")
        case Right(text) =>
          assert(text.startsWith(s"#$digits[open]"),
            s"TaskInfo must render the attached entry, got: ${text.take(160)}")

    finally
      system.stopAll.handleErrorWith(_ => IO.unit).unsafeRunSync()
      ProjectRuntimeRegistry.clear

  // ── guards for the two faces the fix must not disturb ──

  test("guard: createSync's rendered banner face is unchanged (the Task tool display string, author-approved -- byte-stable head, shape and tail)"):
    val banner = TaskLedgerStore.open().createSync(title = "banner guard")
      .toOption.getOrElse(fail("create must succeed"))
    assert(banner.startsWith("[OK] Task created #"), s"banner head unchanged, got: ${banner.take(80)}")
    assert("""\[OK\] Task created #\d+ \[open @dispatcher\] banner guard""".r.findFirstMatchIn(banner).isDefined,
      s"banner shape unchanged, got: ${banner.take(120)}")
    assert(banner.contains("Reach `completed` with action=complete; `closed` (voided) with action=close."),
      s"banner tail unchanged, got: $banner")

  test("guard: createSyncReturningId returns the bare numeric id alone (no banner, no decoration)"):
    val id = TaskLedgerStore.open().createSyncReturningId(title = "pure id face")
      .toOption.getOrElse(fail("create must succeed"))
    assert(id.matches("\\d+"), s"the id face must be pure digits, got: '$id'")

end MailAutoCreateTaskChainSpec
