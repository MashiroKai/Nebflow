package nebflow.core.hotupdate

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import munit.CatsEffectSuite

import scala.concurrent.duration.*

import nebflow.agent.{AgentLibrary, SharedResources}
import nebflow.core.hotrestart.{HotRestart, HotRestartIntent, RestartMode, SuccessorGate}
import nebflow.core.project.{FlowMapStore, NodeDef, NodeLifecycle, ProjectDef, ProjectRuntime, ProjectRuntimeRegistry}
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.llm.{LlmInterface, ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, PathUtil}

/**
 * 统一更新编排器定向验收（hotupdate 批 1 · 设计 §4 / §11 批 1；裁定 2/4/7/9/10）。
 *
 * 全部进程内定向形态：**不 spawn 真实进程、不装真包、不真重启、不触碰端口**
 * （真装 + 真重启 round 属批 4 —— 见本批报告 §未做项）。重启相位委托的是**真实**
 * `HotRestart` 编排器，只是 spawn 用注入 fake（HotRestartSpec 既有手法）。
 *
 * 覆盖（对齐批 1 验收判据 2 的 ①-⑤ + 判据 3/4）：
 *  - ① 全相位可达（检查→准备→冻结→更新→重启→恢复→完成）+ 中止分支可达（两条腿：
 *    冻结失败 / 安装失败）。
 *  - ② 幂等：同键二次到达 ⇒ 「已在途 + 当前相位」，且**执行次数不增**。
 *  - ③ 并发门：异键在途 ⇒ 「更新中 + 相位 + 来源」，**不排队**（计数不增、键不切换）。
 *  - ④ 缺确认位 ⇒ 拒绝 + 可行动错误。
 *  - ⑤ 排空超时/忙 ⇒ **中止**（旧实例继续服务、准入闸复位），且**不存在强制档**。
 *  - 判据 3：更新场景默认等待上限 = 300s 独立值。
 *  - 判据 4：统一进度帧逐相位 schema + 文案键在两个 locale 表内可解析（无字面硬编码）；
 *    既有 restartStatus 进度帧形状与语义**未被改**（向后兼容）。
 */
class UpdateOrchestratorSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-hot-update"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "sessions")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  /**
   * 本测试例的开工墙钟锚点（`beforeEach` 置位）——[[awaitOwnIntent]] 的世代闸门用。
   * 每例的首条重启链 writeIntent 必然晚于它（排空观察窗 ≥100ms），故可据此把「上一条
   * 失败例留下的游离链写在同一条 intent 路径上的旧文件」排除在等待判据之外。
   */
  private var testStartedAtMs: Long = 0L

  override def beforeEach(context: munit.BeforeEach): Unit =
    testStartedAtMs = System.currentTimeMillis()
    HotRestart.resetForTest.unsafeRunSync()(using cats.effect.unsafe.implicits.global)
    ProjectRuntimeRegistry.clear.unsafeRunSync()(using cats.effect.unsafe.implicits.global)
    os.remove.all(tempRoot / "restart")
    os.remove.all(tempRoot / "subagent-tasks")

  override def afterEach(context: munit.AfterEach): Unit =
    HotRestart.resetForTest.unsafeRunSync()(using cats.effect.unsafe.implicits.global)
    ProjectRuntimeRegistry.clear.unsafeRunSync()(using cats.effect.unsafe.implicits.global)
    LlmInterface.cancelAllInflight().unsafeRunSync()(using cats.effect.unsafe.implicits.global)
    os.remove.all(tempRoot / "subagent-tasks")

  // ── 基建（与 HotRestartSpec 同款；本 spec 只用得到 quiesce/排空/派生面）──

  private def mkResources(gatewayShutdown: Deferred[IO, Unit]): IO[SharedResources] =
    for
      dispatcher <- cats.effect.std.Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- nebflow.core.FileChangeTracker.create(os.pwd.toString)
      fileLocks <- nebflow.core.tools.FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
    yield SharedResources(
      llm = null.asInstanceOf[LlmHandle[IO]],
      dispatcher = dispatcher,
      sessionStore = SessionStore(tempRoot / "sessions", tempRoot / "tasks"),
      projectRoot = os.pwd,
      thinkingConfigRef = thinkingRef,
      rateLimiter = rateLimiter,
      fileChangeTracker = tracker,
      contextWindow = 100_000,
      agentLibrary = new AgentLibrary(tempRoot / "agents"),
      taskStore = nebflow.core.task.FileTaskStore,
      historyArchiver = null,
      fileLockManager = fileLocks,
      sessionModelOverrides = modelOverrides,
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = null,
      voiceMutedRef = voiceMuted,
      gatewayShutdown = gatewayShutdown
    )

  private def tinyTiming: HotRestart.Timing = HotRestart.Timing(
    c1GraceMs = 50,
    c2DeadlineMs = 3000,
    observeWindowMs = 100,
    drainDeadlineMs = 5000,
    waitIdlePollMs = 50,
    drainPollMs = 20,
    cooldownMs = 5000,
    // 四档健康自检（批 2 G3）的测试级超时：逐档独立、可注入（生产默认见 Timing）
    healthT1SuccessorAliveHoldMs = 50,
    healthT2DoorReceiptMs = 300,
    healthT3PortServingMs = 200,
    healthT4VersionMatchMs = 100,
    healthPollMs = 10
  )

  private def waitUntil(desc: String, timeoutMs: Long = 8000)(cond: IO[Boolean]): IO[Unit] =
    def loop: IO[Unit] = cond.flatMap(if _ then IO.unit else IO.sleep(20.millis) *> loop)
    loop
      .timeout(timeoutMs.millis)
      .handleErrorWith(_ => IO.raiseError(new AssertionError(s"timeout waiting for: $desc")))

  /**
   * 等本测试例自己的链走到中止相位。⚠️ 判据**不能**读 `orch.status`：中止腿的冻结契约是
   * 「释放占位 ⇒ 可重试」——`abort` 先 `releasePlaceholder` 再广播 Aborted 帧，故中止后
   * `status` 恒为 None（同一 spec 的 ①freeze 例**正是断言这个 None**）。中止相位的可观测面
   * = 统一进度帧（判据 4 的帧；本 spec 随后即逐条断言相位序列）。
   */
  private def awaitAbortFrame(frames: Frames): IO[Unit] =
    waitUntil("update reached Aborted (unified progress frame)") {
      frames.progressPhases.get.map(_.contains("aborted"))
    }

  /**
   * 等**本测试例自己的**重启链落盘 intent。判据 = 世代（`generation`，intent 文件链的
   * 关联键）晚于本例开工锚点。⚠️ 单用 `os.exists(intentFile)` 会被上一条失败例的游离链
   * 骗过（intent 路径全例共用）——实测：孤儿链先落盘 ⇒ 本例的 `markPhase` 写到了别人的
   * 文件上、本链的 `pollReadyToBind` 随后被本链自己的 `writeIntent` 覆盖回 "spawned" ⇒
   * C2 超时、交接永不完成。故等待面必须带世代闸门。
   */
  private def awaitOwnIntent(): IO[Unit] =
    waitUntil("hot-restart orchestrator wrote its own intent (generation gate)") {
      SuccessorGate.readIntent(intentFile).map(_.exists(_.generation >= testStartedAtMs))
    }

  /**
   * 后继门口回执（批 2 契约）：真实后继在 Zone A 末回写 readyToBind；编排器的重启相位
   * 现在**等到交接真的让渡（含四档健康自检通过）**才收「已完成」（G0 收口），故测试必须
   * 先把门口回执写上，再等终态——旧契约（受理即 Completed）下这一步在终态之后，属
   * 已被取代的旧时序。
   */
  private def successorAtTheDoor(): IO[Unit] =
    awaitOwnIntent() *> SuccessorGate.markPhase(intentFile, "readyToBind")

  private class FakeProcess(aliveAfterGrace: Boolean, destroyed: Ref[IO, Int]) extends HotRestart.SpawnedProcess:
    def pid: Long = 424242L
    def isAlive: IO[Boolean] = IO.pure(aliveAfterGrace)
    def destroy: IO[Unit] = destroyed.update(_ + 1)

  private def mkFakeSpawn(
    aliveAfterGrace: Boolean
  ): IO[(HotRestartIntent => IO[Either[String, HotRestart.SpawnedProcess]], Ref[IO, Int])] =
    Ref.of[IO, Int](0).map { destroyed =>
      val spawnFn: HotRestartIntent => IO[Either[String, HotRestart.SpawnedProcess]] =
        _ => IO.pure(Right(new FakeProcess(aliveAfterGrace, destroyed)))
      (spawnFn, destroyed)
    }

  private val noopBroadcast: io.circe.Json => IO[Unit] = _ => IO.unit

  /** 帧捕获面：raw 帧 + updateProgress 相位序列 + restartStatus 帧（向后兼容读数）。 */
  private final case class Frames(
    raw: Ref[IO, List[io.circe.Json]],
    progressPhases: Ref[IO, List[String]],
    restartStatus: Ref[IO, List[io.circe.Json]]
  )

  private def mkFrames: IO[Frames] =
    for
      raw <- Ref.of[IO, List[io.circe.Json]](Nil)
      phases <- Ref.of[IO, List[String]](Nil)
      restart <- Ref.of[IO, List[io.circe.Json]](Nil)
    yield Frames(
      raw,
      phases,
      restart
    )

  private def capture(frames: Frames): io.circe.Json => IO[Unit] = j =>
    val hc = j.hcursor
    frames.raw.update(_ :+ j) *>
      (hc.downField("type").as[String].getOrElse("") match
        case "updateProgress" => frames.progressPhases.update(_ :+ hc.get[String]("phase").getOrElse("?"))
        case "restartStatus" => frames.restartStatus.update(_ :+ j)
        case _ => IO.unit)

  private def countingInstall(
    counter: Ref[IO, Int],
    result: Either[String, String] = Right("Update installed, restarting...")
  ): UpdateChannel => IO[Either[String, String]] =
    _ => counter.update(_ + 1).as(result)

  private def mkOrchestrator(
    hr: HotRestart,
    frames: Frames,
    install: UpdateChannel => IO[Either[String, String]],
    latest: Option[String] = Some("2026.9.20"),
    current: String = "2026.9.19"
  ): UpdateOrchestrator =
    new UpdateOrchestrator(
      hotRestart = Some(hr),
      broadcast = capture(frames),
      currentVersion = () => current,
      latestVersion = () => IO.pure(latest),
      install = install
    )

  private def settingsReq(
    key: Option[String] = None,
    mode: RestartMode = RestartMode.WaitIdle(300_000L),
    confirm: Boolean = true,
    source: UpdateSource = UpdateSource.Settings,
    channel: UpdateChannel = UpdateChannel.Stable
  ): UpdateRequest =
    UpdateRequest(source = source, confirm = confirm, channel = channel, mode = mode, idempotencyKey = key)

  private def intentFile: os.Path = SuccessorGate.intentPath(tempRoot)

  // ── ① 全相位可达 + 中止分支可达 ──────────────────────────────────────

  test(
    "① full phase chain: checking→preparing→freezing→updating→restarting→recovering→completed (install called once, restart delegated to the real orchestrator)"
  ) {
    for
      shutdownD <- Deferred[IO, Unit]
      res <- mkResources(shutdownD)
      frames <- mkFrames
      (spawnFn, _) <- mkFakeSpawn(aliveAfterGrace = true)
      hr = new HotRestart(
        res,
        res,
        res.sessionStore,
        res.gatewayShutdown,
        8080,
        "0.0.0.0",
        noopBroadcast,
        tinyTiming,
        spawnFn
      )
      counter <- Ref.of[IO, Int](0)
      orch = mkOrchestrator(hr, frames, countingInstall(counter))
      legacy <- Ref.of[IO, List[String]](Nil)
      admission <- orch.request(settingsReq(), r => legacy.update(_ :+ r.fold(e => s"failed:$e", ok => s"ok:$ok")))
      _ = assertEquals(admission, UpdateAdmission.Accepted(settingsReq().effectiveKey))
      // 重启相位**确实**委托了既有编排器：intent 落盘 = 真实 HotRestart 跑起来了
      _ <- awaitOwnIntent()
      intent <- SuccessorGate.readIntent(intentFile)
      _ = assertEquals(
        intent.map(_.triggerSource),
        Some("update:settings"),
        "restart phase must delegate to the existing orchestrator with the update source"
      )
      _ = assertEquals(intent.map(_.phase), Some("spawned"))
      // 后继门口回执 → 四档健康自检 → 优雅让渡 ⇒ 编排器此时才收「已完成」
      _ <- SuccessorGate.markPhase(intentFile, "readyToBind")
      _ <- waitUntil("update reached Completed") {
        orch.status.map(_.exists(_.phase == UpdatePhase.Completed))
      }
      phases <- frames.progressPhases.get
      count <- counter.get
      // 全部相位按序可达（含终态）
      _ = assertEquals(
        phases,
        List("checking", "preparing", "freezing", "updating", "restarting", "recovering", "completed"),
        s"phase sequence must cover the whole state machine: $phases"
      )
      _ = assertEquals(count, 1, "install action must run exactly once")
      legacyOutcomes <- legacy.get
      _ = assertEquals(legacyOutcomes, List("ok:Update installed, restarting..."), "既有完成帧回执")
      _ <- shutdownD.get.timeout(15.seconds)
    yield ()
  }

  test(
    "① abort branch (freeze): in-flight work past the wait limit → aborted, admission reopened, install never runs"
  ) {
    for
      shutdownD <- Deferred[IO, Unit]
      res <- mkResources(shutdownD)
      frames <- mkFrames
      (spawnFn, _) <- mkFakeSpawn(aliveAfterGrace = true)
      hr = new HotRestart(
        res,
        res,
        res.sessionStore,
        res.gatewayShutdown,
        8080,
        "0.0.0.0",
        noopBroadcast,
        tinyTiming,
        spawnFn
      )
      counter <- Ref.of[IO, Int](0)
      orch = mkOrchestrator(hr, frames, countingInstall(counter))
      (key, _) <- LlmInterface.registerInflight(None) // F4 域在飞 ⇒ 排空无法收敛
      admission <- orch.request(settingsReq(mode = RestartMode.WaitIdle(200L)))
      _ = assert(admission.isInstanceOf[UpdateAdmission.Accepted], admission.toString)
      _ <- awaitAbortFrame(frames)
      phases <- frames.progressPhases.get
      count <- counter.get
      _ = assertEquals(
        phases,
        List("checking", "preparing", "freezing", "aborted"),
        s"abort must happen in the freeze phase: $phases"
      )
      _ = assertEquals(count, 0, "aborted freeze must never run the install action")
      // 裁定 4：中止 ⇒ 旧实例继续服务（准入闸复位、零停机）
      draining <- HotRestart.isDraining
      _ = assertEquals(draining, false, "abort must reopen the admission gate")
      gate <- HotRestart.admissionGate
      _ = assertEquals(gate, Right(()), "work must be admitted again after the abort")
      // 占位已释放 ⇒ 可重试（不是永久卡死）
      status <- orch.status
      _ = assertEquals(status, None, "abort must release the in-progress placeholder (retryable)")
      _ <- LlmInterface.cancelAllInflight()
      intentExists <- IO(os.exists(intentFile))
      _ = assertEquals(intentExists, false, "an aborted freeze must not write any restart intent")
      shutdownUncompleted <- shutdownD.get.timeout(200.millis).attempt.map(_.isLeft)
      _ = assert(shutdownUncompleted, "an aborted update must not trigger a restart")
    yield ()
  }

  test("① abort branch (install): install action fails → aborted, admission reopened, legacy failure frame emitted") {
    for
      shutdownD <- Deferred[IO, Unit]
      res <- mkResources(shutdownD)
      frames <- mkFrames
      (spawnFn, _) <- mkFakeSpawn(aliveAfterGrace = true)
      hr = new HotRestart(
        res,
        res,
        res.sessionStore,
        res.gatewayShutdown,
        8080,
        "0.0.0.0",
        noopBroadcast,
        tinyTiming,
        spawnFn
      )
      counter <- Ref.of[IO, Int](0)
      orch = mkOrchestrator(hr, frames, countingInstall(counter, Left("Install script failed (exit code: 13)")))
      legacy <- Ref.of[IO, List[String]](Nil)
      _ <- orch.request(settingsReq(), r => legacy.update(_ :+ r.fold(e => s"failed:$e", ok => s"ok:$ok")))
      _ <- awaitAbortFrame(frames)
      phases <- frames.progressPhases.get
      _ = assertEquals(phases, List("checking", "preparing", "freezing", "updating", "aborted"), phases)
      count <- counter.get
      _ = assertEquals(count, 1)
      outcomes <- legacy.get
      _ = assertEquals(outcomes, List("failed:Install script failed (exit code: 13)"), "既有完成帧的失败分支必须照旧发出")
      draining <- HotRestart.isDraining
      _ = assertEquals(draining, false, "abort must reopen the admission gate")
      intentExists <- IO(os.exists(intentFile))
      _ = assertEquals(intentExists, false, "a failed install must not start the restart phase")
    yield ()
  }

  // ── ② 幂等双层 ───────────────────────────────────────────────────────

  test(
    "② idempotency: same key arriving again while in flight → AlreadyInFlight + current phase, install count stays 1"
  ) {
    for
      shutdownD <- Deferred[IO, Unit]
      res <- mkResources(shutdownD)
      frames <- mkFrames
      (spawnFn, _) <- mkFakeSpawn(aliveAfterGrace = true)
      hr = new HotRestart(
        res,
        res,
        res.sessionStore,
        res.gatewayShutdown,
        8080,
        "0.0.0.0",
        noopBroadcast,
        tinyTiming,
        spawnFn
      )
      counter <- Ref.of[IO, Int](0)
      gate <- Deferred[IO, Either[String, String]]
      gatedInstall = (_: UpdateChannel) => counter.update(_ + 1) *> gate.get
      orch = mkOrchestrator(hr, frames, gatedInstall)
      req = settingsReq(key = Some("idem-key-1"))
      first <- orch.request(req)
      _ = assertEquals(first, UpdateAdmission.Accepted("idem-key-1"))
      // 安装相位卡住 ⇒ 在途窗口内二次到达
      _ <- waitUntil("install action started") { counter.get.map(_ >= 1) }
      second <- orch.request(req)
      _ = assertEquals(
        second,
        UpdateAdmission.AlreadyInFlight("idem-key-1", UpdatePhase.Updating),
        s"same key must merge into the in-flight update with its current phase: $second"
      )
      _ <- gate.complete(Right("Update installed, restarting..."))
      _ <- successorAtTheDoor()
      _ <- waitUntil("update reached Completed") { orch.status.map(_.exists(_.phase == UpdatePhase.Completed)) }
      count <- counter.get
      _ = assertEquals(count, 1, "a merged duplicate must NOT execute the install action a second time")
      phases <- frames.progressPhases.get
      _ = assertEquals(phases.count(_ == "updating"), 1, s"no second execution may appear: $phases")
      _ <- awaitOwnIntent()
      _ <- SuccessorGate.markPhase(intentFile, "readyToBind")
      _ <- shutdownD.get.timeout(15.seconds)
    yield ()
  }

  // ── ③ 并发门（排他、不排队）──────────────────────────────────────────

  test(
    "③ concurrency gate: different key while in flight → Busy(phase, source), never queued (no second execution, key never switches)"
  ) {
    for
      shutdownD <- Deferred[IO, Unit]
      res <- mkResources(shutdownD)
      frames <- mkFrames
      (spawnFn, _) <- mkFakeSpawn(aliveAfterGrace = true)
      hr = new HotRestart(
        res,
        res,
        res.sessionStore,
        res.gatewayShutdown,
        8080,
        "0.0.0.0",
        noopBroadcast,
        tinyTiming,
        spawnFn
      )
      counter <- Ref.of[IO, Int](0)
      gate <- Deferred[IO, Either[String, String]]
      gatedInstall = (_: UpdateChannel) => counter.update(_ + 1) *> gate.get
      orch = mkOrchestrator(hr, frames, gatedInstall)
      _ <- orch.request(settingsReq(key = Some("run-A")))
      _ <- waitUntil("install action started") { counter.get.map(_ >= 1) }
      second <- orch.request(settingsReq(key = Some("run-B"), source = UpdateSource.DeviceList))
      _ = assertEquals(
        second,
        UpdateAdmission.Busy(UpdatePhase.Updating, UpdateSource.Settings, "run-A"),
        s"an exclusive update answers 'updating + phase + source' and never queues: $second"
      )
      // 不排队 = 第二个请求从不产生执行：占位键仍是 run-A，计数不增
      statusMid <- orch.status
      _ = assertEquals(statusMid.map(_.key), Some("run-A"), "站位必须仍是第一个请求的键")
      _ <- gate.complete(Right("Update installed, restarting..."))
      _ <- successorAtTheDoor()
      _ <- waitUntil("update reached Completed") { orch.status.map(_.exists(_.phase == UpdatePhase.Completed)) }
      count <- counter.get
      _ = assertEquals(count, 1, "the busy request must never be queued and executed")
      phases <- frames.progressPhases.get
      _ = assertEquals(phases.count(_ == "updating"), 1, phases.toString)
      _ <- awaitOwnIntent()
      _ <- SuccessorGate.markPhase(intentFile, "readyToBind")
      _ <- shutdownD.get.timeout(15.seconds)
    yield ()
  }

  // ── ④ 缺确认位 ───────────────────────────────────────────────────────

  test("④ missing confirm bit → refused with an actionable error, nothing claimed, install never runs") {
    for
      shutdownD <- Deferred[IO, Unit]
      res <- mkResources(shutdownD)
      frames <- mkFrames
      (spawnFn, _) <- mkFakeSpawn(aliveAfterGrace = true)
      hr = new HotRestart(
        res,
        res,
        res.sessionStore,
        res.gatewayShutdown,
        8080,
        "0.0.0.0",
        noopBroadcast,
        tinyTiming,
        spawnFn
      )
      counter <- Ref.of[IO, Int](0)
      orch = mkOrchestrator(hr, frames, countingInstall(counter))
      admission <- orch.request(settingsReq(confirm = false))
      _ <- IO {
        admission match
          case UpdateAdmission.Refused(reason, detail) =>
            // 冻结原因字面（裁定 3②）+ 可行动错误文本
            assertEquals(reason, UpdateReason.ConfirmMissing)
            assert(detail.contains("confirm=true"), s"error must be actionable: $detail")
            assert(detail.contains("restarts this instance"), s"error must say what would happen: $detail")
          case other => fail(s"missing confirm bit must be refused, got: $other")
      }
      status <- orch.status
      _ = assertEquals(status, None, "a refused request must not claim the placeholder")
      count <- counter.get
      _ = assertEquals(count, 0, "a refused request must never run the install action")
      framesSeen <- frames.progressPhases.get
      _ = assertEquals(framesSeen, List.empty[String], "a refused request must emit no phase frames")
    yield ()
  }

  // ── ⑤ 排空 = 中止（无强制档）────────────────────────────────────────

  test("⑤ drain never forces: RejectIfBusy on in-flight work → Left, and no node status is flipped (no forced tier)") {
    val ws = tempRoot / "ws-force"
    for
      shutdownD <- Deferred[IO, Unit]
      res <- mkResources(shutdownD)
      _ <- IO(os.makeDir.all(ws))
      store <- FlowMapStore.open("hot-update-force", ws.toString)
      _ <- store.mutate { s =>
        s.copy(nodes =
          s.nodes.updated(
            "n-run",
            NodeDef(id = "n-run", name = "runner", agent = "x", createdAt = System.currentTimeMillis())
              .copy(status = NodeLifecycle.Running)
          )
        )
      }
      rt = ProjectRuntime(
        ProjectDef(
          name = "hot-update-force",
          workspace = ws.toString,
          agentFile = (ws / "AGENTS.md").toString,
          createdAt = System.currentTimeMillis()
        ),
        store,
        null,
        null,
        null,
        None
      )
      _ <- ProjectRuntimeRegistry.register(rt)
      hr = new HotRestart(res, res, res.sessionStore, res.gatewayShutdown, 8080, "0.0.0.0", noopBroadcast, tinyTiming)
      // 忙（F1：一个 Running 节点）⇒ 排队模式也拒绝，且**绝无**强制档可走
      drainResult <- hr.drainForUpdate(RestartMode.RejectIfBusy)
      _ = assert(drainResult.isLeft, s"busy must never be forced: $drainResult")
      _ = assert(
        drainResult match
          case Left(_: HotRestart.DrainFailure.Busy) => true
          case _ => false,
        s"busy must surface as the typed Busy failure (no force tier): $drainResult"
      )
      after <- store.snapshot
      _ = assertEquals(
        after.nodes("n-run").status,
        NodeLifecycle.Running,
        "a refused drain must not flip any node status (no forced tier, no failure write)"
      )
      _ = assertEquals(after.nodes("n-run").bgWait, None, "no side effects on the node")
      draining <- HotRestart.isDraining
      _ = assertEquals(draining, false, "a refused drain must not close admission either")
      // 忙 ⇒ 中止语义：编排器侧同样走 Aborted（而非强制）
      frames <- mkFrames
      counter <- Ref.of[IO, Int](0)
      orch = mkOrchestrator(hr, frames, countingInstall(counter))
      _ <- orch.request(settingsReq(mode = RestartMode.RejectIfBusy))
      _ <- awaitAbortFrame(frames)
      phases <- frames.progressPhases.get
      _ = assertEquals(phases, List("checking", "preparing", "freezing", "aborted"), phases.toString)
      stillRunning <- store.snapshot
      _ = assertEquals(
        stillRunning.nodes("n-run").status,
        NodeLifecycle.Running,
        "the aborted update path must leave running work untouched (graceful-interrupt hook owns flips, not this path)"
      )
    yield ()
    end for
  }

  // ── 判据 3：300s 独立值 ───────────────────────────────────────────────

  test(
    "criterion 3: the update scenario default await-idle is 300s (independent from the 600s UI restart command default)"
  ) {
    for
      _ <- IO(assertEquals(UpdateDefaults.AwaitIdleDefaultMs, 300_000L, "裁定 7：更新场景独立值 300s"))
      _ <- IO(assertEquals(UpdateDefaults.awaitIdleMs, 300_000L))
      _ = assertEquals(
        settingsReq().mode,
        RestartMode.WaitIdle(300_000L),
        "the settings-page request must carry the 300s update default"
      )
      _ = assertEquals(
        UpdateRequest(source = UpdateSource.Settings, confirm = true).mode,
        RestartMode.WaitIdle(300_000L)
      )
    yield ()
  }

  // ── 裁定 3（分发器补充）：冻结字面对账 + 幂等键保留窗口 ───────────────

  test(
    "ruling 3②/③ (frozen literals): orchestrator reason enum and source enum literals match the frozen word tables"
  ) {
    for _ <- IO {
        // 编排层原因字面（本批冻结；改字面 = 契约破坏 ⇒ 本断言即回归闸）
        assertEquals(
          UpdateReason.values.toList.map(_.wire),
          List(
            "confirm-missing",
            "freeze-busy",
            "freeze-wait-limit-exceeded",
            "freeze-drain-deadline-exceeded",
            "install-failed",
            "restart-refused",
            "health-check-failed",
            "orchestrator-unavailable",
            "unexpected-error"
          )
        )
        // source 枚举字面 = 契约词表（设置页｜设备列表｜中继｜官网｜命令行）——同词表，禁两套字面
        assertEquals(UpdateSource.values.toList.map(_.wire), List("settings", "device-list", "relay", "website", "cli"))
        // 通道字面（裁定 9：测试通道仅命令行保留，本批不暴露界面）
        assertEquals(UpdateChannel.values.toList.map(_.wire), List("stable", "beta"))
        // 幂等键保留窗口（契约对齐点 ④：数值本批冻结）
        assertEquals(UpdateDefaults.IdempotencyKeyWindowDefaultMs, 300_000L)
      }
    yield ()
  }

  test(
    "ruling 3④ (idempotency window): after a successful terminal phase the key is held for the frozen window, then expires"
  ) {
    for
      shutdownD <- Deferred[IO, Unit]
      res <- mkResources(shutdownD)
      frames <- mkFrames
      (spawnFn, _) <- mkFakeSpawn(aliveAfterGrace = true)
      hr = new HotRestart(
        res,
        res,
        res.sessionStore,
        res.gatewayShutdown,
        8080,
        "0.0.0.0",
        noopBroadcast,
        tinyTiming,
        spawnFn
      )
      counter <- Ref.of[IO, Int](0)
      // 窗口 100ms（经构造参数注入，不动冻结默认值）
      orch = new UpdateOrchestrator(
        hotRestart = Some(hr),
        broadcast = capture(frames),
        currentVersion = () => "2026.9.19",
        latestVersion = () => IO.pure(Some("2026.9.20")),
        install = countingInstall(counter),
        idempotencyWindowMs = 100L
      )
      req = settingsReq(key = Some("win-key"))
      _ <- orch.request(req)
      _ <- successorAtTheDoor()
      _ <- waitUntil("update reached Completed") { orch.status.map(_.exists(_.phase == UpdatePhase.Completed)) }
      within <- orch.request(req)
      _ = assertEquals(
        within,
        UpdateAdmission.AlreadyInFlight("win-key", UpdatePhase.Completed),
        s"within the window the key must still be held: $within"
      )
      _ <- IO.sleep(200.millis)
      expired <- orch.request(req)
      _ = assert(
        expired.isInstanceOf[UpdateAdmission.Accepted],
        s"after the window the key must expire and be re-accepted: $expired"
      )
      // 计数判据必须等**第二次执行真跑到安装相位**再读（安装动作在 fork 的执行体上）：
      // 请求一受理就读计数是竞态，会把真跑过的第二次安装读成 1（本轮实测命中）。
      _ <- waitUntil("the install action ran a second time") { counter.get.map(_ >= 2) }
      count <- counter.get
      _ = assertEquals(count, 2, "the expired-key request is a genuinely new run (install runs again)")
      // 第二次执行的重启相位被既有编排器**幂等合并**进在途链（R7：实测本例内只出现一条链、
      // 一份 intent——第二次执行没有第二次 "draining on"）⇒ 等待面仍是本例自己的那份 intent。
      _ <- awaitOwnIntent()
      _ <- SuccessorGate.markPhase(intentFile, "readyToBind")
      _ <- shutdownD.get.timeout(15.seconds)
    yield ()
  }

  test("ruling 3② (failure phase carries a reason): the aborted frame carries a frozen reason literal, not free text") {
    for
      shutdownD <- Deferred[IO, Unit]
      res <- mkResources(shutdownD)
      frames <- mkFrames
      (spawnFn, _) <- mkFakeSpawn(aliveAfterGrace = true)
      hr = new HotRestart(
        res,
        res,
        res.sessionStore,
        res.gatewayShutdown,
        8080,
        "0.0.0.0",
        noopBroadcast,
        tinyTiming,
        spawnFn
      )
      counter <- Ref.of[IO, Int](0)
      orch = mkOrchestrator(hr, frames, countingInstall(counter, Left("Install script failed (exit code: 13)")))
      _ <- orch.request(settingsReq())
      _ <- awaitAbortFrame(frames)
      raw <- frames.raw.get
      aborted = raw.filter(_.hcursor.get[String]("phase").contains("aborted"))
      _ = assertEquals(aborted.size, 1)
      _ = assertEquals(
        aborted.head.hcursor.get[String]("reason").toOption,
        Some("install-failed"),
        "the aborted frame must carry the frozen reason literal"
      )
      _ = assertEquals(aborted.head.hcursor.get[String]("messageKey").toOption, Some("update.phase.aborted"))
    yield ()
  }

  // ── 判据 4：进度帧 schema + 文案键 + 既有帧向后兼容 ───────────────────

  test(
    "criterion 4: unified progress frame schema per phase + message keys resolve in both locales (no hardcoded display text)"
  ) {
    for
      shutdownD <- Deferred[IO, Unit]
      res <- mkResources(shutdownD)
      frames <- mkFrames
      (spawnFn, _) <- mkFakeSpawn(aliveAfterGrace = true)
      // 既有编排器也接到同一广播通道 ⇒ 它的 restartStatus 帧（子集保留不删）在同一
      // 捕获面里可读（向后兼容读数；语义/形状未被本批改动）。
      hr = new HotRestart(
        res,
        res,
        res.sessionStore,
        res.gatewayShutdown,
        8080,
        "0.0.0.0",
        capture(frames),
        tinyTiming,
        spawnFn
      )
      counter <- Ref.of[IO, Int](0)
      orch = mkOrchestrator(hr, frames, countingInstall(counter))
      _ <- orch.request(settingsReq(channel = UpdateChannel.Beta))
      _ <- successorAtTheDoor()
      _ <- waitUntil("update reached Completed") { orch.status.map(_.exists(_.phase == UpdatePhase.Completed)) }
      raw <- frames.raw.get
      progress = raw.filter(_.hcursor.get[String]("type").contains("updateProgress"))
      _ = assert(progress.nonEmpty, "the unified progress frame must be broadcast")
      // 逐相位 schema（判据 4 的「帧 schema 逐键」读数）
      _ <- progress.traverse_ { j =>
        val hc = j.hcursor
        IO {
          assertEquals(
            hc.keys.toList.flatten.sorted,
            List(
              "channel",
              "currentVersion",
              "detail",
              "idempotencyKey",
              "latestVersion",
              "messageKey",
              "phase",
              "progress",
              "reason",
              "source",
              "state",
              "type"
            ),
            s"frame keys: ${hc.keys}"
          )
          // 原因字段：非失败相位 = null（契约对齐点 ②：不预设拼写、不用相位字段拼文案）
          assertEquals(hc.downField("reason").focus.flatMap(_.asString), None, "non-failure phases carry reason=null")
          val phase = hc.get[String]("phase").getOrElse("?")
          assertEquals(hc.get[String]("messageKey").toOption, Some(s"update.phase.$phase"))
          assertEquals(hc.get[String]("source").toOption, Some("settings"))
          assertEquals(hc.get[String]("channel").toOption, Some("beta"))
          assertEquals(hc.get[String]("idempotencyKey").toOption, Some("settings:beta:local"))
          assertEquals(hc.get[String]("currentVersion").toOption, Some("2026.9.19"))
          // 最新版本只在**读到版本指针之后**的相位才有值：检查相位此刻还没读（帧仍带该键，
          // 见上 12 键断言）⇒ 该帧必须为 null。逐相位钉死，两侧都不放宽。
          if phase == "checking" then
            assertEquals(
              hc.get[String]("latestVersion").toOption,
              None,
              "the checking frame cannot carry a latest version yet (the pointer is read in the next step)"
            )
          else assertEquals(hc.get[String]("latestVersion").toOption, Some("2026.9.20"))
          // 🔴 帧**不携带展示文案**：只有 messageKey + 诊断 detail（D6）
          assert(
            !hc.keys.toList.flatten.exists(k => k == "text" || k == "label" || k == "message"),
            s"frames must not carry display text: ${hc.keys.toList.flatten}"
          )
        }
      }
      // 文案键解析：每个相位的 messageKey 必须同时存在于 zh-CN 与 en 文案表
      zhRaw <- IO(os.read(os.pwd / "src/main/resources/web/js/locales/zh-CN.js"))
      enRaw <- IO(os.read(os.pwd / "src/main/resources/web/js/locales/en.js"))
      _ <- IO {
        UpdatePhase.values.toList.foreach { p =>
          assert(zhRaw.contains(s"'${p.messageKey}':"), s"zh-CN must define ${p.messageKey}")
          assert(enRaw.contains(s"'${p.messageKey}':"), s"en must define ${p.messageKey}")
        }
        assert(phaseTextSeparated(zhRaw), "对勾/叉号前缀不得并入相位文案")
      }
      // 既有重启进度帧作其子集保留不删、语义未变（向后兼容读数）
      restart <- frames.restartStatus.get
      _ = assert(restart.nonEmpty, "既有 restartStatus 帧仍必须发出（子集保留不删）")
      _ <- restart.traverse_ { j =>
        IO {
          assertEquals(
            j.hcursor.keys.toList.flatten.sorted,
            List("detail", "phase", "type"),
            "restartStatus frame shape unchanged"
          )
          assertEquals(j.hcursor.get[String]("type").toOption, Some("restartStatus"))
        }
      }
      _ <- awaitOwnIntent()
      _ <- SuccessorGate.markPhase(intentFile, "readyToBind")
      _ <- shutdownD.get.timeout(15.seconds)
    yield ()
  }

  test(
    "criterion 4 (negative): an unreachable release pointer is fail-soft — the chain still reaches the install phase"
  ) {
    for
      shutdownD <- Deferred[IO, Unit]
      res <- mkResources(shutdownD)
      frames <- mkFrames
      (spawnFn, _) <- mkFakeSpawn(aliveAfterGrace = true)
      hr = new HotRestart(
        res,
        res,
        res.sessionStore,
        res.gatewayShutdown,
        8080,
        "0.0.0.0",
        noopBroadcast,
        tinyTiming,
        spawnFn
      )
      counter <- Ref.of[IO, Int](0)
      orch = mkOrchestrator(hr, frames, countingInstall(counter), latest = None)
      _ <- orch.request(settingsReq(mode = RestartMode.RejectIfBusy))
      _ <- successorAtTheDoor()
      _ <- waitUntil("update reached Completed") { orch.status.map(_.exists(_.phase == UpdatePhase.Completed)) }
      count <- counter.get
      _ = assertEquals(count, 1, "an unreachable pointer must not block the install (same behaviour as before)")
      raw <- frames.raw.get
      preparing = raw.filter(j => j.hcursor.get[String]("phase").contains("preparing"))
      _ = assert(
        preparing.head.hcursor.get[String]("detail").exists(_.contains("unreachable")),
        preparing.head.hcursor.get[String]("detail").toString
      )
      _ <- awaitOwnIntent()
      _ <- SuccessorGate.markPhase(intentFile, "readyToBind")
      _ <- shutdownD.get.timeout(15.seconds)
    yield ()
  }

  // ── 批 2 G3/G0：健康自检接进重启相位（红/绿两条真链读数）─────────────

  test(
    "batch 2 G0 red: the new version fails the four-tier health self-check ⇒ the update is ABORTED with reason health-check-failed, never 'completed'"
  ) {
    for
      shutdownD <- Deferred[IO, Unit]
      res <- mkResources(shutdownD)
      frames <- mkFrames
      (spawnFn, destroyed) <- mkFakeSpawn(aliveAfterGrace = true)
      // 门口回执带一个「有公告但没人应答」的探针端口 ⇒ 第三档必红（真 connect 探测）
      deadPort <- IO.blocking {
        val s = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val p = s.getLocalPort
        s.close()
        p
      }
      hr = new HotRestart(
        // Phase 5 D 步窄能力接线:res(SharedResources)按 SubAgentTaskPort/
        // AgentRegistryPort 窄类型传入;sessionStore/gatewayShutdown 底层值直传。
        res,
        res,
        res.sessionStore,
        res.gatewayShutdown,
        8080,
        "0.0.0.0",
        noopBroadcast,
        tinyTiming,
        spawnFn,
        connectProbe = Some(p => IO.blocking(nebflow.cli.SingleInstanceGuard.connectProbeAccepted(p)))
      )
      counter <- Ref.of[IO, Int](0)
      orch = mkOrchestrator(hr, frames, countingInstall(counter))
      _ <- orch.request(settingsReq())
      _ <- awaitOwnIntent()
      _ <- SuccessorGate.markPhase(intentFile, "readyToBind", probePort = Some(deadPort))
      // 观测面 = 统一进度帧，**不是** `orch.status`：中止腿的冻结契约是「释放占位 ⇒ 可重试」，
      // `abort` 先 releasePlaceholder 再广播 Aborted 帧 ⇒ 中止后 status 恒为 None（本 spec
      // 的 ①freeze 例正是断言这个 None）。故等帧（`awaitAbortFrame`）——与既有中止例同款。
      _ <- awaitAbortFrame(frames)
      status <- orch.status
      _ = assert(
        status.forall(_.phase != UpdatePhase.Completed),
        "a failed health self-check must never surface as 'completed' (batch 1 verify §11② residual, closed by batch 2 G0)"
      )
      phases <- frames.progressPhases.get
      _ = assert(!phases.contains("completed"), s"no completed phase may appear: $phases")
      _ = assert(phases.contains("aborted"), s"aborted phase must be observable: $phases")
      aborted <- frames.raw.get.map(_.filter(j => j.hcursor.get[String]("phase").contains("aborted")).last)
      _ = assertEquals(
        aborted.hcursor.get[String]("reason").toOption,
        Some("health-check-failed"),
        "the aborted frame must carry the frozen reason literal of the health self-check"
      )
      _ = assert(
        aborted.hcursor.get[String]("detail").exists(_.contains("health self-check failed")),
        aborted.hcursor.get[String]("detail").toString
      )
      destroyedCount <- destroyed.get
      _ = assertEquals(
        destroyedCount,
        1,
        "the failed successor is TERMed through the existing abort path (no second abort machine)"
      )
    yield ()
  }

  test(
    "batch 2 G0 green: a successor that answers its announced probe endpoint passes the gate ⇒ recovering then completed"
  ) {
    for
      shutdownD <- Deferred[IO, Unit]
      res <- mkResources(shutdownD)
      frames <- mkFrames
      (spawnFn, _) <- mkFakeSpawn(aliveAfterGrace = true)
      // 真实 loopback 探针端点：与旧实例的第三/四档探针握手（真 socket、真 HTTP、真载荷）
      probePort <- IO.blocking {
        val srv = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext(
          "/api/health",
          (ex: com.sun.net.httpserver.HttpExchange) =>
            val body = io.circe.Json
              .obj(
                "product" -> io.circe.Json.fromString("nebflow"),
                "status" -> io.circe.Json.fromString("ok"),
                "version" -> io.circe.Json.fromString(nebflow.Version.string)
              )
              .noSpaces
              .getBytes("UTF-8")
            ex.sendResponseHeaders(200, body.length.toLong)
            ex.getResponseBody.write(body)
            ex.close()
        )
        srv.start()
        srv.getAddress.getPort
      }
      // 广播面 = 与编排器同一个 capture：生产里 GatewayMain 把同一个 wsHub.broadcast 同时
      // 交给 HotRestart 与 UpdateOrchestrator，故交接帧（restartStatus/handing-over，含四档
      // 读数）与进度帧落在同一观测面；测试若给 HotRestart 一个 noop，交接帧永远看不到。
      hr = new HotRestart(
        // Phase 5 D 步窄能力接线:res(SharedResources)按 SubAgentTaskPort/
        // AgentRegistryPort 窄类型传入;sessionStore/gatewayShutdown 底层值直传。
        res,
        res,
        res.sessionStore,
        res.gatewayShutdown,
        8080,
        "0.0.0.0",
        capture(frames),
        tinyTiming,
        spawnFn,
        connectProbe = Some(p => IO.blocking(nebflow.cli.SingleInstanceGuard.connectProbeAccepted(p)))
      )
      counter <- Ref.of[IO, Int](0)
      orch = mkOrchestrator(hr, frames, countingInstall(counter))
      _ <- orch.request(settingsReq())
      _ <- awaitOwnIntent()
      _ <- SuccessorGate.markPhase(intentFile, "readyToBind", probePort = Some(probePort))
      _ <- waitUntil("update reached Completed") { orch.status.map(_.exists(_.phase == UpdatePhase.Completed)) }
      phases <- frames.progressPhases.get
      _ = assertEquals(
        phases,
        List("checking", "preparing", "freezing", "updating", "restarting", "recovering", "completed"),
        phases.toString
      )
      restart <- frames.restartStatus.get
      _ = assert(
        restart.exists(j => j.hcursor.get[String]("detail").exists(_.contains("port-serving"))),
        "the handover frame must carry the health tier readings (evidence, not a claim)"
      )
      _ <- shutdownD.get.timeout(15.seconds)
    yield ()
  }

  /** 对勾/叉号前缀与相位文本分离（设计 §7 通用契约）：本批新增的相位文案不得带前缀。 */
  private def phaseTextSeparated(localeRaw: String): Boolean =
    val block = localeRaw.split("'update.phase.").drop(1)
    block.forall(line => !line.take(200).contains("✓") && !line.take(200).contains("✗"))

end UpdateOrchestratorSpec
