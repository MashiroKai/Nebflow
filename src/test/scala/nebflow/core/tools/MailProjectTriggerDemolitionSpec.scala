package nebflow.core.tools

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.circe.{Json, JsonObject}
import munit.FunSuite
import nebflow.actor.{ActorRef, ActorSystem, Behavior, Behaviors}
import nebflow.actor.AgentDef
import nebflow.agent.{AgentLibrary, SharedResources, SubAgentTaskStore}
import nebflow.core.FileChangeTracker
import nebflow.shared.PathUtil
import nebflow.core.project.{
  FlowMapStore,
  NodeEngine,
  ProjectActor,
  ProjectDef,
  ProjectRuntime,
  ProjectRuntimeRegistry,
  TaskLedgerStore
}
import nebflow.core.task.FileTaskStore
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.llm.ModelCandidate
import nebflow.shared.ThinkingConfig
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import java.nio.file.Files
import scala.concurrent.duration.*

/**
 * Project-trigger 拆除批（2026-10-18 · 作者令「完全拆除」Mail→dispatcher 触发路径，
 * 落地 2026-10-03 作者令「使用 Delegate 直接触发 kernel，Mail 降级为用于通信」）
 * —— Mail 项目触发面的**拆除钉**。
 *
 * 拆除后 Mail 对项目地址（`project:<name>` 形态 + 裸挂载项目名，后者含 Root 裸名腿
 * 与 short-name 瀑布腿）**对一切角色**显式拒绝（`MAIL_PROJECT_TRIGGER_UNSUPPORTED`）：
 * 零 `TriggerDispatcher` 命令、零台账自动建任务、零终态复活。`task=` 参数随腿退役出
 * schema，`call()` 的墓碑读取对一切腿显式拒绝（`MAIL_TASK_PARAM_UNSUPPORTED`）。
 *
 * 各钉（机械可判，每条都断「拒绝 + 零副作用」两侧）：
 *  1. `project:<mounted>`（Root 发送方）⇒ 拒绝词点名 Delegate + 录制 actor 零命令
 *     + 台账零新条目（自动建任务面拆除）；
 *  2. 裸挂载项目名（Root 发送方，layeredRoute 裸名腿）⇒ 同款拒绝、零命令、零新条目；
 *  3. 裸挂载项目名经 short-name 瀑布（delegate 会话与无团队语境发送方——旧 D-4
 *     无角色闸泄漏面）⇒ 同款拒绝、零命令；
 *  4. `task=` 墓碑：任何非空值（含指向**终态**任务的真实 id）⇒ 显式拒绝，且该终态
 *     条目状态**保持不变**（零复活）、零命令；
 *  5. images + `project:` ⇒ 腿拒绝（先于 vision 闸，零投递）；
 *  6. 台账原语面守卫（承接原 MailAutoCreateTaskChainSpec 的两个 guard）：`createSync`
 *     渲染横幅（`Task` 工具显示面）与 `createSyncReturningId` 纯 id 面不变——拆的是
 *     Mail 触发链，不是台账原语。
 *
 * Isolation: `PathUtil.dataRoot` points at a temp dir for the whole file（台账与其
 * 历史绝不触真实 `~/.nebflow`），the project registry (a global) is cleared around
 * every case. No real dispatcher session is spawned: the project runtime is mounted
 * manually with a recording actor（原 MailAutoCreateTaskChainSpec 夹具形态）.
 */
class MailProjectTriggerDemolitionSpec extends FunSuite:

  private val tempRoot: os.Path = os.Path(Files.createTempDirectory("nb-mail-proj-demolition"))
  private val originalRoot: os.Path = PathUtil.dataRoot

  override def beforeAll(): Unit =
    PathUtil.setDataRoot(tempRoot)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    os.remove.all(tempRoot)

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  // ── fixtures（MailAutoCreateTaskChainSpec 夹具形态逐字承袭）──

  private def qIn(fields: (String, String)*): JsonObject =
    JsonObject.fromIterable(fields.map((k, v) => k -> Json.fromString(v)))

  /** Root（Nebula）发送方：引擎侧身份判据 = agentDef.name。 */
  private def ctx(system: ActorSystem): ToolContext =
    ToolContext(
      projectRoot = os.pwd.toString,
      sessionId = Some("sid-mail-demo-nebula"),
      isDispatcher = false,
      projectName = None,
      agentDef = Some(AgentDef(name = "Nebula", description = "spec fixture")),
      actorSystem = Some(system)
    )

  /** Delegate 会话发送方（引擎侧前缀判据；无团队语境）。 */
  private def delegateCtx(system: ActorSystem): ToolContext =
    ToolContext(
      projectRoot = os.pwd.toString,
      sessionId = Some("delegate-demoab12"),
      isDispatcher = false,
      projectName = None,
      actorSystem = Some(system)
    )

  /** 普通发送方（无团队语境——旧 D-4 泄漏路径的发送方形态）。 */
  private def teamlessCtx(system: ActorSystem): ToolContext =
    ToolContext(
      projectRoot = os.pwd.toString,
      sessionId = Some("sid-mail-demo-plain"),
      isDispatcher = false,
      projectName = None,
      actorSystem = Some(system)
    )

  /** Records every ProjectCommand the tool face sends（拆除后必须恒为空）。 */
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

  /**
   * Mount the project runtime MANUALLY with a recording actorRef (no real dispatcher
   * spawn — this spec pins the demolition of the Mail-tool trigger face).
   */
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
        store,
        system,
        res,
        wsSendFn = (_: Json) => IO.unit,
        workspace = ws.toString,
        rootSessionId = "nebula-root",
        projectName = name,
        emitEvent = (_: String, _: String, _: Json) => IO.unit,
        notifyTriggerOverride = Some((_: String) => IO.unit),
        reportGateHold = Some(false)
      )
      pd = ProjectDef(
        name = name,
        workspace = ws.toString,
        agentFile = (ws / "AGENTS.md").toString,
        createdAt = System.currentTimeMillis()
      )
      rt = ProjectRuntime(pd, store, engine, system, res, Some(ref))
      _ <- ProjectRuntimeRegistry.register(rt)
    yield ref

  private def ledgerEntryCount(): Int = TaskLedgerStore.open().entriesSync().size

  /** 拆除断言的共同收口：拒绝词 + 零命令 + 零新条目。 */
  private def assertDemolished(
    res: Either[ToolError, String],
    record: Ref[IO, List[ProjectActor.ProjectCommand]],
    entriesBefore: Int
  ): Unit =
    res match
      case Left(err) =>
        assert(
          err.message.contains(MailTool.ErrProjectTriggerUnsupported),
          s"must carry the demolition error code: ${err.message}"
        )
        assert(err.message.contains("Delegate"), s"must point at Delegate (the dispatch entry): ${err.message}")
        assert(err.message.contains("Nothing was sent"), s"fail-closed declaration required: ${err.message}")
      case Right(v) => fail(s"a project address must be refused, got success: $v")
    val cmds = record.get.unsafeRunSync()
    assertEquals(cmds, Nil, s"no ProjectCommand may reach the project actor, got: $cmds")
    assertEquals(ledgerEntryCount(), entriesBefore, "the ledger must gain no entry (auto-create demolished)")

  // ── 1. `project:<mounted>`（Root）──

  test("project:<mounted> (root) => demolition refusal; no trigger command, no auto-created task"):
    val ws = tempRoot / "ws-demo-form"
    os.makeDir.all(ws)
    val system = ActorSystem(s"mail-demo-form-${java.util.UUID.randomUUID().toString.take(6)}")
    try
      val program =
        for
          record <- Ref.of[IO, List[ProjectActor.ProjectCommand]](Nil)
          resources <- mkResources(system)
          _ <- mountWithRecordingActor("demo-form", ws, system, resources, record)
          _ <- IO.blocking(TaskLedgerStore.open().createSyncReturningId(title = "pre-existing entry"))
          entriesBefore = ledgerEntryCount()
          res <- MailTool.call(
            qIn("address" -> "project:demo-form", "message" -> "should not trigger anything"),
            ctx(system)
          )
          _ <- IO.sleep(100.millis) // the old leg was fire-and-forget: give a phantom trigger no hiding place
          _ <- system.stopAll.handleErrorWith(_ => IO.unit)
        yield (res, record, entriesBefore)

      val (res, record, entriesBefore) = program.unsafeRunSync()
      assertDemolished(res, record, entriesBefore)
    finally
      system.stopAll.handleErrorWith(_ => IO.unit).unsafeRunSync()
      ProjectRuntimeRegistry.clear
    end try

  // ── 2. 裸挂载项目名（Root 裸名腿）──

  test("bare mounted project name (root) => the same demolition refusal, no trigger, no task"):
    val ws = tempRoot / "ws-demo-bare"
    os.makeDir.all(ws)
    val system = ActorSystem(s"mail-demo-bare-${java.util.UUID.randomUUID().toString.take(6)}")
    try
      val program =
        for
          record <- Ref.of[IO, List[ProjectActor.ProjectCommand]](Nil)
          resources <- mkResources(system)
          _ <- mountWithRecordingActor("demo-bare", ws, system, resources, record)
          entriesBefore = ledgerEntryCount()
          res <- MailTool.call(
            qIn("address" -> "demo-bare", "message" -> "bare name must not trigger either"),
            ctx(system)
          )
          _ <- IO.sleep(100.millis)
          _ <- system.stopAll.handleErrorWith(_ => IO.unit)
        yield (res, record, entriesBefore)

      val (res, record, entriesBefore) = program.unsafeRunSync()
      assertDemolished(res, record, entriesBefore)
    finally
      system.stopAll.handleErrorWith(_ => IO.unit).unsafeRunSync()
      ProjectRuntimeRegistry.clear
    end try

  // ── 3. 裸名 short-name 瀑布腿（delegate 会话 / 无团队语境——旧 D-4 泄漏面）──

  test("bare mounted project name via the short-name waterfall (delegate session and teamless sender) => refused, D-4 closed"):
    val ws = tempRoot / "ws-demo-d4"
    os.makeDir.all(ws)
    val system = ActorSystem(s"mail-demo-d4-${java.util.UUID.randomUUID().toString.take(6)}")
    try
      val program =
        for
          record <- Ref.of[IO, List[ProjectActor.ProjectCommand]](Nil)
          resources <- mkResources(system)
          _ <- mountWithRecordingActor("demo-d4", ws, system, resources, record)
          entriesBefore = ledgerEntryCount()
          resDelegate <- MailTool.call(
            qIn("address" -> "demo-d4", "message" -> "delegate must not trigger"),
            delegateCtx(system)
          )
          resPlain <- MailTool.call(
            qIn("address" -> "demo-d4", "message" -> "teamless sender must not trigger"),
            teamlessCtx(system)
          )
          _ <- IO.sleep(100.millis)
          _ <- system.stopAll.handleErrorWith(_ => IO.unit)
        yield (resDelegate, resPlain, record, entriesBefore)

      val (resDelegate, resPlain, record, entriesBefore) = program.unsafeRunSync()
      assertDemolished(resDelegate, record, entriesBefore)
      assertDemolished(resPlain, record, entriesBefore)
    finally
      system.stopAll.handleErrorWith(_ => IO.unit).unsafeRunSync()
      ProjectRuntimeRegistry.clear
    end try

  // ── 4. `task=` 墓碑（终态条目零复活）──

  test("task= tombstone: a stale task id is refused on every leg and a TERMINAL task stays terminal (no revive)"):
    val ws = tempRoot / "ws-demo-task"
    os.makeDir.all(ws)
    val system = ActorSystem(s"mail-demo-task-${java.util.UUID.randomUUID().toString.take(6)}")
    try
      val tid = TaskLedgerStore
        .open()
        .createSyncReturningId(title = "terminal entry that must stay terminal")
        .getOrElse(sys.error("create must succeed"))
      assert(TaskLedgerStore.open().completeSync(tid).isRight)

      val program =
        for
          record <- Ref.of[IO, List[ProjectActor.ProjectCommand]](Nil)
          resources <- mkResources(system)
          _ <- mountWithRecordingActor("demo-task", ws, system, resources, record)
          resProject <- MailTool.call(
            qIn("address" -> "project:demo-task", "message" -> "continuation attempt", "task" -> tid),
            ctx(system)
          )
          resNode <- MailTool.call(
            qIn("address" -> "node:n-1", "message" -> "stale param on another leg", "task" -> tid),
            ctx(system)
          )
          _ <- IO.sleep(100.millis)
          _ <- system.stopAll.handleErrorWith(_ => IO.unit)
        yield (resProject, resNode, record)

      val (resProject, resNode, record) = program.unsafeRunSync()
      for (label, res) <- List("project:" -> resProject, "node:" -> resNode) do
        res match
          case Left(err) =>
            assert(
              err.message.contains(MailTool.ErrTaskParamUnsupported),
              s"$label: must carry the task-tombstone code: ${err.message}"
            )
            assert(err.message.contains("Delegate"), s"$label: must point at Delegate: ${err.message}")
          case Right(v) => fail(s"$label: a stale task= must be refused, got success: $v")
      val cmds = record.get.unsafeRunSync()
      assertEquals(cmds, Nil, s"no ProjectCommand may be sent, got: $cmds")
      val entry = TaskLedgerStore
        .open()
        .findSync(tid)
        .getOrElse(fail("the terminal entry must still exist"))
      assertEquals(entry.status, TaskLedgerStore.Status.Completed, "the terminal task must NOT be revived")
    finally
      system.stopAll.handleErrorWith(_ => IO.unit).unsafeRunSync()
      ProjectRuntimeRegistry.clear
    end try

  // ── 5. images + project:（腿拒绝先于 vision 闸）──

  test("images + project:<mounted> => the demolition refusal (no silent vision drop, no delivery)"):
    val ws = tempRoot / "ws-demo-img"
    os.makeDir.all(ws)
    val img = tempRoot / "img.png"
    os.write.over(img, "png-bytes")
    val system = ActorSystem(s"mail-demo-img-${java.util.UUID.randomUUID().toString.take(6)}")
    try
      val program =
        for
          record <- Ref.of[IO, List[ProjectActor.ProjectCommand]](Nil)
          resources <- mkResources(system)
          _ <- mountWithRecordingActor("demo-img", ws, system, resources, record)
          res <- MailTool.call(
            JsonObject(
              "address" -> Json.fromString("project:demo-img"),
              "message" -> Json.fromString("with an image"),
              "images" -> Json.arr(Json.fromString(img.toString))
            ),
            ctx(system)
          )
          _ <- IO.sleep(100.millis)
          _ <- system.stopAll.handleErrorWith(_ => IO.unit)
        yield (res, record)

      val (res, record) = program.unsafeRunSync()
      assertDemolished(res, record, ledgerEntryCount())
    finally
      system.stopAll.handleErrorWith(_ => IO.unit).unsafeRunSync()
      ProjectRuntimeRegistry.clear
    end try

  // ── 6. 台账原语面守卫（承接原 MailAutoCreateTaskChainSpec 的两个 guard）──

  test(
    "guard: createSync's rendered banner face is unchanged (the Task tool display string, byte-stable head, shape and tail)"
  ):
    val banner =
      TaskLedgerStore.open().createSync(title = "banner guard").toOption.getOrElse(fail("create must succeed"))
    assert(banner.startsWith("[OK] Task created #"), s"banner head unchanged, got: ${banner.take(80)}")
    assert(
      """\[OK\] Task created #\d+ \[open @dispatcher\] banner guard""".r.findFirstMatchIn(banner).isDefined,
      s"banner shape unchanged, got: ${banner.take(120)}"
    )
    assert(
      banner.contains("Reach `completed` with action=complete; `closed` (voided) with action=close."),
      s"banner tail unchanged, got: $banner"
    )

  test("guard: createSyncReturningId returns the bare numeric id alone (no banner, no decoration)"):
    val id = TaskLedgerStore
      .open()
      .createSyncReturningId(title = "pure id face")
      .toOption
      .getOrElse(fail("create must succeed"))
    assert(id.matches("\\d+"), s"the id face must be pure digits, got: '$id'")

end MailProjectTriggerDemolitionSpec
