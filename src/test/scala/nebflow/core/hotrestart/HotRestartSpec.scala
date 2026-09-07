package nebflow.core.hotrestart

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.agent.{AgentLibrary, SharedResources}
import nebflow.core.PathUtil
import nebflow.core.project.{FlowMapStore, NodeDef, NodeLifecycle, ProjectDef, ProjectRuntime, ProjectRuntimeRegistry}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{LlmInterface, ModelCandidate, ThinkingConfig}
import nebflow.shared.LlmHandle
import nebflow.core.tools.BgTaskRegistry

import scala.concurrent.duration.*

/**
 * 热重启机制定向验收（hot-restart 批 2026-09-07，设计 §5 十二条的进程内定向测试
 * 形态——真实进程 e2e 归下游验证节点；本 spec 全部用注入 fake spawn，不 spawn 真实
 * 进程、不触碰端口、不触碰 8080 宿主）。
 *
 * 覆盖（编号对齐设计 §5 / §3）：
 *  - 握手文件卫生（验收 12）：intent 原子写读回写、markFailed、successor.json、
 *    归档移动、>10min 陈旧归档不阻塞 / 新鲜 intent 不归档。
 *  - [s1] 进场校验（R6）：home/port/host/freshness 四拒绝。
 *  - [s4] 端口让渡等待（探测注入）：pid 死 + 无活监听 → 放行（TW-only 快速）；
 *    pid 活 → 超时弃进；pid 死 + 活监听 → ForeignOccupantSignal（外来抢占回落
 *    ensureSingleInstance 语义）。
 *  - quiesceReport 五域（F1-F5）：F1 注册表 running 节点 / F2 subtask 文件 /
 *    F4 在飞 LLM / F5 等待型 bg 任务逐域 busy + 全空 idle。
 *  - busy 拒绝（验收 2）：RejectIfBusy → Left 带五域明细 ∧ 无 intent 写入 ∧
 *    draining 未置位。
 *  - WaitIdle 排队（验收 3）：busy → 等待期转空闲 → 自动进入重启成功（且等待期
 *    draining 不置位）；超时仍 busy → 放弃 + 释放占位 + 无 intent。
 *  - 主路径成功链（验收 1 进程内形态）：C1 过 → C2 过（测试写 readyToBind 模拟
 *    后继 Zone A 回执）→ gatewayShutdown Deferred 完成（[8] 优雅让渡触发）∧
 *    draining 保持置位（旧实例即将退出）∧ destroy 未被调用。
 *  - C1 spawn 失败中止（验收 4）：fake spawn 即死 → intent phase=failed ∧
 *    draining 清零（新工作可准入）∧ Deferred 未完成（旧实例继续服务）。
 *  - C2 后继卡死中止（验收 5）：fake spawn 活但永不 readyToBind → 超时 TERM
 *    （destroy 调用）∧ draining 清零 ∧ failed 记录。
 *  - 冷却窗（R7）+ 在途幂等合并 + 总开关（设计 §6 回滚）。
 *  - spawn 命令构造（两形态，§4）：jar 形态命令形 / bundled 形态 exe / 不可解析
 *    形态大声报错。
 */
class HotRestartSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-hot-restart"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "sessions")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    HotRestart.resetForTest.unsafeRunSync()(using cats.effect.unsafe.implicits.global)
    ProjectRuntimeRegistry.clear.unsafeRunSync()(using cats.effect.unsafe.implicits.global)
    os.remove.all(tempRoot / "restart")
    os.remove.all(tempRoot / "subagent-tasks")
    os.remove.all(tempRoot / "ws-f1")

  override def afterEach(context: munit.AfterEach): Unit =
    HotRestart.resetForTest.unsafeRunSync()(using cats.effect.unsafe.implicits.global)
    ProjectRuntimeRegistry.clear.unsafeRunSync()(using cats.effect.unsafe.implicits.global)
    LlmInterface.cancelAllInflight().unsafeRunSync()(using cats.effect.unsafe.implicits.global)
    // F2 域测试的 subtask 文件是持久面——不清理会泄漏进后续测试的 quiesce 判定
    os.remove.all(tempRoot / "subagent-tasks")

  // ── 基建 ──────────────────────────────────────────────────────────────

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
    cooldownMs = 5000
  )

  private def waitUntil(desc: String, timeoutMs: Long = 8000)(cond: IO[Boolean]): IO[Unit] =
    def loop: IO[Unit] = cond.flatMap(if _ then IO.unit else IO.sleep(40.millis) *> loop)
    loop.timeout(timeoutMs.millis)
      .handleErrorWith(_ => IO.raiseError(new AssertionError(s"timeout waiting for: $desc")))

  /** Fake spawn 注入：进程存亡/destroy 记账全在测试手中（不 spawn 真实进程）。 */
  private class FakeProcess(aliveAfterGrace: Boolean, destroyed: Ref[IO, Int])
      extends HotRestart.SpawnedProcess:
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

  private def intentFile: os.Path = SuccessorGate.intentPath(tempRoot)

  private def readIntentPhase: IO[Option[String]] =
    SuccessorGate.readIntent(intentFile).map(_.map(_.phase))

  // ── 握手文件 IO 与卫生（验收 12）────────────────────────────────────

  test("intent round-trip: write → read → markPhase → markFailed (atomic)") {
    val intent = HotRestartIntent(1, 100, "0.0.0.0", 8080, tempRoot.toString, "jar", "cmd", "web-ui", "spawned", 2)
    for
      _ <- SuccessorGate.writeIntent(intentFile, intent)
      read1 <- SuccessorGate.readIntent(intentFile)
      _ = assertEquals(read1, Some(intent))
      _ <- SuccessorGate.markPhase(intentFile, "readyToBind")
      read2 <- SuccessorGate.readIntent(intentFile)
      _ = assertEquals(read2.map(_.phase), Some("readyToBind"))
      _ <- SuccessorGate.markFailed(intentFile, "boom")
      read3 <- SuccessorGate.readIntent(intentFile)
      _ = assertEquals(read3.map(_.phase), Some("failed"))
      _ = assertEquals(read3.flatMap(_.failure), Some("boom"))
      // 原子写：目录无 .tmp. 残留
      residue <- IO(os.list(tempRoot / "restart").filter(_.last.contains(".tmp.")).toList)
      _ = assertEquals(residue, List.empty[os.Path])
    yield ()
  }

  test("missing intent reads as None; archiveIntent moves intent to last-restart.json") {
    for
      before <- SuccessorGate.readIntent(intentFile)
      _ = assertEquals(before, None)
      intent = HotRestartIntent(1, 100, "0.0.0.0", 8080, tempRoot.toString, "jar", "cmd", "web-ui", "spawned", 2)
      _ <- SuccessorGate.writeIntent(intentFile, intent)
      _ <- SuccessorGate.archiveIntent(tempRoot)
      after <- SuccessorGate.readIntent(intentFile)
      _ = assertEquals(after, None, "intent archived away")
      archived <- SuccessorGate.readIntent(SuccessorGate.lastRestartPath(tempRoot))
      _ = assertEquals(archived, Some(intent), "content preserved in last-restart.json")
    yield ()
  }

  test("stale (>10min) intent archived at boot; fresh intent untouched (验收 12)") {
    val intent = HotRestartIntent(1, 100, "0.0.0.0", 8080, tempRoot.toString, "jar", "cmd", "web-ui", "spawned", 2)
    for
      _ <- SuccessorGate.writeIntent(intentFile, intent)
      now <- IO(System.currentTimeMillis())
      _ <- SuccessorGate.archiveStaleIntent(tempRoot, now) // mtime = 现在 → 新鲜不归档
      fresh <- SuccessorGate.readIntent(intentFile)
      _ = assertEquals(fresh.isDefined, true, "fresh intent must NOT be archived")
      // mtime 拨回 11 分钟前 → 陈旧 → WARN + 归档（不阻塞不丢弃）
      _ <- IO {
        val f = new java.io.File(intentFile.toString)
        f.setLastModified(now - 11 * 60 * 1000)
        ()
      }
      _ <- SuccessorGate.archiveStaleIntent(tempRoot, now)
      staleGone <- SuccessorGate.readIntent(intentFile)
      _ = assertEquals(staleGone, None, "stale intent archived away")
      archived <- SuccessorGate.readIntent(SuccessorGate.lastRestartPath(tempRoot))
      _ = assertEquals(archived.isDefined, true, "stale content preserved for forensics")
    yield ()
  }

  test("writeSuccessor writes {generation, newPid, version, ts}") {
    for
      _ <- SuccessorGate.writeSuccessor(tempRoot, 42, 4242)
      raw <- IO(os.read(SuccessorGate.successorPath(tempRoot)))
      decoded <- IO.fromOption(io.circe.parser.decode[SuccessorRecord](raw).toOption)(
        new AssertionError("successor.json undecodable"))
      _ = assertEquals(decoded.generation, 42L)
      _ = assertEquals(decoded.newPid, 4242L)
      _ = assertEquals(decoded.version, nebflow.Version.string)
    yield ()
  }

  // ── [s1] 进场校验（R6）────────────────────────────────────────────

  private def mkCtx(port: Int = 8080, home: String = tempRoot.toString, ts: Long = System.currentTimeMillis()) =
    SuccessorContext(oldPid = 999, host = "0.0.0.0", port = port, home = home, generation = 1, ts = ts, intentPath = intentFile)

  test("validate: ok / home mismatch / port mismatch / host mismatch / stale (R6)") {
    val now = System.currentTimeMillis()
    assertEquals(SuccessorGate.validate(mkCtx(), tempRoot, "0.0.0.0", 8080, now), Right(()))
    assert(SuccessorGate.validate(mkCtx(home = "/other"), tempRoot, "0.0.0.0", 8080, now).isLeft, "home mismatch refuses")
    assert(SuccessorGate.validate(mkCtx(port = 9999), tempRoot, "0.0.0.0", 8080, now).isLeft, "port mismatch refuses")
    assert(SuccessorGate.validate(mkCtx(), tempRoot, "127.0.0.1", 8080, now).isLeft, "host mismatch refuses")
    assert(
      SuccessorGate.validate(mkCtx(ts = now - 61_000), tempRoot, "0.0.0.0", 8080, now).isLeft,
      "stale intent (>60s) refuses")
  }

  // ── [s4] 端口让渡等待（探测注入）──────────────────────────────────

  test("awaitHandover: pid dead + no live listener → proceed") {
    SuccessorGate.awaitHandover(
      oldPid = 100, port = 8080,
      pidAlive = _ => IO.pure(false),
      liveListener = _ => IO.pure(false)
    ).map(result => assertEquals(result, Right(())))
  }

  test("awaitHandover: TIME_WAIT-only (connect refused) → proceeds without drain wait") {
    // TW 语义：connect refused = 无活监听——不等排空直接放行（Ember bind + 65s
    // fail-open 归下游）。断言窗口 <2s（若误用 strict-bind 排空会 ≥65s 超时）。
    val start = System.currentTimeMillis()
    for
      result <- SuccessorGate.awaitHandover(
        oldPid = 100, port = 8080,
        pidAlive = _ => IO.pure(false),
        liveListener = _ => IO.pure(false)
      )
      elapsed = System.currentTimeMillis() - start
      _ = assertEquals(result, Right(()))
      _ = assert(elapsed < 2000, s"TW-only must proceed without drain wait, took ${elapsed}ms")
    yield ()
  }

  test("awaitHandover: old pid still alive past deadline → abandon (not foreign)") {
    SuccessorGate.awaitHandover(
      oldPid = 100, port = 8080,
      pidAlive = _ => IO.pure(true),
      liveListener = _ => IO.pure(false),
      pidDeadlineMs = 300,
      pollMs = 40.millis
    ).map { result =>
      assert(result.isLeft, "alive old pid past deadline must abandon")
      assert(
        result.left.toOption.get != SuccessorGate.ForeignOccupantSignal,
        "abandon must not be confused with foreign preemption")
    }
  }

  test("awaitHandover: pid dead but port still served → ForeignOccupantSignal") {
    SuccessorGate.awaitHandover(
      oldPid = 100, port = 8080,
      pidAlive = _ => IO.pure(false),
      liveListener = _ => IO.pure(true)
    ).map(result => assertEquals(result, Left(SuccessorGate.ForeignOccupantSignal)))
  }

  // ── quiesceReport 五域（F1/F2/F4/F5）─────────────────────────────

  test("quiesceReport: all-empty resources → idle") {
    for
      shutdownD <- Deferred[IO, Unit]
      res <- mkResources(shutdownD)
      hr = new HotRestart(res, 8080, "0.0.0.0", _ => IO.unit, tinyTiming)
      q <- hr.quiesceReport
      _ = assert(q.isIdle, s"expected idle, got: ${q.detail}")
    yield ()
  }

  test("quiesceReport F1: registered project with Running node → busy with node name") {
    val ws = tempRoot / "ws-f1"
    for
      _ <- IO(os.makeDir.all(ws))
      shutdownD <- Deferred[IO, Unit]
      res <- mkResources(shutdownD)
      store <- FlowMapStore.open("hot-restart-f1", ws.toString)
      _ <- store.mutate { s =>
        s.copy(nodes = s.nodes.updated(
          "n-run",
          NodeDef(id = "n-run", name = "runner", agent = "x", createdAt = System.currentTimeMillis())
            .copy(status = NodeLifecycle.Running)))
      }
      rt = ProjectRuntime(
        ProjectDef(name = "hot-restart-f1", workspace = ws.toString, agentFile = (ws / "AGENTS.md").toString, createdAt = System.currentTimeMillis()),
        store, null, null, null, None)
      _ <- ProjectRuntimeRegistry.register(rt)
      hr = new HotRestart(res, 8080, "0.0.0.0", _ => IO.unit, tinyTiming)
      q <- hr.quiesceReport
      _ = assert(!q.isIdle, s"F1 running node must be busy: ${q.detail}")
      _ = assert(q.runningNodes.exists(_.contains("hot-restart-f1/runner")), q.runningNodes.toString)
    yield ()
  }

  test("quiesceReport F2: running subtask file → busy") {
    val tasksDir = tempRoot / "subagent-tasks"
    val taskJson =
      """[{"taskId":"task-1","parentSessionId":"parent-1","agentName":"a","prompt":"p","description":"d",
         "status":"running","retryCount":0,"spawnedAt":1,"completedAt":null,"lastError":null,"source":"delegate"}]"""
    for
      shutdownD <- Deferred[IO, Unit]
      res <- mkResources(shutdownD)
      _ <- IO(os.makeDir.all(tasksDir))
      _ <- IO(os.write.over(tasksDir / "parent-1.json", taskJson.replaceAll("\\n\\s*", "")))
      hr = new HotRestart(res, 8080, "0.0.0.0", _ => IO.unit, tinyTiming)
      q <- hr.quiesceReport
      _ = assert(!q.isIdle, s"F2 running subtask must be busy: ${q.detail}")
      _ = assert(q.subtasks.exists(_.contains("task-1")), q.subtasks.toString)
    yield ()
  }

  test("quiesceReport F4+F5: in-flight LLM and waiting bg task → busy (persistent excluded, cleanup restores idle)") {
    for
      shutdownD <- Deferred[IO, Unit]
      res <- mkResources(shutdownD)
      hr = new HotRestart(res, 8080, "0.0.0.0", _ => IO.unit, tinyTiming)
      q0 <- hr.quiesceReport
      _ = assert(q0.isIdle)
      // F4: 在飞 LLM
      (key, _) <- LlmInterface.registerInflight(None)
      qF4 <- hr.quiesceReport
      _ = assert(!qF4.isIdle, s"LLM in-flight must be busy: ${qF4.detail}")
      _ = assert(qF4.inflightLlm >= 1)
      _ <- LlmInterface.cancelAllInflight()
      // F5: 等待型 bg 任务（persistent=false 纳入；persistent=true 不纳入）
      _ <- BgTaskRegistry.register("job-f5", "sess-1", "compile", "local")
      qF5 <- hr.quiesceReport
      _ = assert(!qF5.isIdle, s"waiting bg task must be busy: ${qF5.detail}")
      _ <- BgTaskRegistry.register("job-f5-p", "sess-1", "server", "local", persistent = true)
      qBoth <- hr.quiesceReport
      _ = assertEquals(qBoth.waitingBgTasks.count(_.startsWith("job-f5-p")), 0, "persistent task must NOT count")
      _ <- BgTaskRegistry.unregister("job-f5")
      _ <- BgTaskRegistry.unregister("job-f5-p")
      qIdle <- hr.quiesceReport
      _ = assert(qIdle.isIdle, s"cleanup must restore idle: ${qIdle.detail}")
    yield ()
  }

  // ── busy 拒绝 / WaitIdle / 成功链 / C1 / C2（fake spawn，验收 1-5）──

  test("RejectIfBusy: busy → Left with domain detail, NO intent written, admission stays open (验收 2)") {
    for
      shutdownD <- Deferred[IO, Unit]
      res <- mkResources(shutdownD)
      (broadcast, _) <- mkBroadcastCapture
      hr = new HotRestart(res, 8080, "0.0.0.0", broadcast, tinyTiming)
      (key, _) <- LlmInterface.registerInflight(None)
      result <- hr.requestRestart("web-ui", RestartMode.RejectIfBusy)
      _ <- LlmInterface.cancelAllInflight()
      _ = assert(result.isLeft)
      _ = assert(result.left.toOption.get.contains("busy"), result.left.toOption.get)
      _ = assert(result.left.toOption.get.contains("LLM"), "detail must name the busy domain")
      intentExists <- IO(os.exists(intentFile))
      _ = assertEquals(intentExists, false, "busy reject must not write intent")
      draining <- HotRestart.isDraining
      _ = assertEquals(draining, false, "busy reject must not close admission")
      claimOpen <- HotRestart.inProgressRef.get
      _ = assertEquals(claimOpen, false, "busy reject must release the in-progress claim")
    yield ()
  }

  test("success chain: idle → C1 pass → C2 pass (readyToBind) → gatewayShutdown completed, draining stays on (验收 1 进程内形态)") {
    for
      shutdownD <- Deferred[IO, Unit]
      res <- mkResources(shutdownD)
      (broadcast, _) <- mkBroadcastCapture
      (spawnFn, destroyed) <- mkFakeSpawn(aliveAfterGrace = true)
      hr = new HotRestart(res, 8080, "0.0.0.0", broadcast, tinyTiming, spawnFn)
      accepted <- hr.requestRestart("web-ui", RestartMode.RejectIfBusy)
      _ = assertEquals(accepted, Right(()))
      // 模拟后继 Zone A 完成回执：intent 出现后回写 readyToBind
      _ <- waitUntil("intent written") { readIntentPhase.map(_.isDefined) }
      _ <- SuccessorGate.markPhase(intentFile, "readyToBind")
      // [8]：Deferred 完成 = 优雅让渡触发
      _ <- shutdownD.get.timeout(15.seconds)
      phase <- readIntentPhase
      // 此刻 phase = readyToBind（测试模拟的后继回执留盘；真实链路由后继 [s7]
      // 归档 last-restart.json）——关键断言：成功路径绝不改写 failed
      _ = assert(phase != Some("failed"), s"success path never marks failed, got: $phase")
      _ = assert(phase.isDefined, "intent must survive the success path until archived")
      destroying <- destroyed.get
      _ = assertEquals(destroying, 0, "success path must not destroy the successor")
      draining <- HotRestart.isDraining
      _ = assertEquals(draining, true, "success keeps draining on (old instance is exiting)")
    yield ()
  }

  test("C1: successor dies within grace → abort, admission reopens, no shutdown (验收 4)") {
    for
      shutdownD <- Deferred[IO, Unit]
      res <- mkResources(shutdownD)
      (broadcast, _) <- mkBroadcastCapture
      (spawnFn, destroyed) <- mkFakeSpawn(aliveAfterGrace = false)
      hr = new HotRestart(res, 8080, "0.0.0.0", broadcast, tinyTiming, spawnFn)
      accepted <- hr.requestRestart("web-ui", RestartMode.RejectIfBusy)
      _ = assertEquals(accepted, Right(()))
      _ <- waitUntil("intent marked failed") { readIntentPhase.map(_ == Some("failed")) }
      draining <- HotRestart.isDraining
      _ = assertEquals(draining, false, "abort must clear draining (new work admitted)")
      claimReleased <- HotRestart.inProgressRef.get
      _ = assertEquals(claimReleased, false, "abort must release the in-progress claim")
      intent <- SuccessorGate.readIntent(intentFile)
      _ = assert(intent.flatMap(_.failure).exists(_.contains("C1")), intent.flatMap(_.failure).toString)
      // 旧实例继续服务：Deferred 未完成
      shutdownUncompleted <- shutdownD.get.timeout(200.millis).attempt.map(_.isLeft)
      _ = assert(shutdownUncompleted, "C1 abort must NOT trigger graceful shutdown")
      untouched <- destroyed.get
      _ = assertEquals(untouched, 0, "C1 abort must not destroy (spawn failed / already dead)")
    yield ()
  }

  test("C2: successor alive but never readyToBind → TERM own successor + abort (验收 5)") {
    for
      shutdownD <- Deferred[IO, Unit]
      res <- mkResources(shutdownD)
      (broadcast, _) <- mkBroadcastCapture
      (spawnFn, destroyed) <- mkFakeSpawn(aliveAfterGrace = true)
      hr = new HotRestart(res, 8080, "0.0.0.0", broadcast, tinyTiming.copy(c2DeadlineMs = 400), spawnFn)
      accepted <- hr.requestRestart("web-ui", RestartMode.RejectIfBusy)
      _ = assertEquals(accepted, Right(()))
      _ <- waitUntil("intent marked failed") { readIntentPhase.map(_ == Some("failed")) }
      destroying <- destroyed.get
      _ = assert(destroying >= 1, "C2 timeout must TERM the own successor")
      draining <- HotRestart.isDraining
      _ = assertEquals(draining, false, "C2 abort must clear draining")
      intent <- SuccessorGate.readIntent(intentFile)
      _ = assert(intent.flatMap(_.failure).exists(_.contains("C2")), intent.flatMap(_.failure).toString)
      shutdownUncompleted <- shutdownD.get.timeout(200.millis).attempt.map(_.isLeft)
      _ = assert(shutdownUncompleted, "C2 abort must NOT trigger graceful shutdown")
    yield ()
  }

  test("WaitIdle: busy → queued (admission stays open) → becomes idle → restart proceeds (验收 3 case 1)") {
    for
      shutdownD <- Deferred[IO, Unit]
      res <- mkResources(shutdownD)
      (broadcast, _) <- mkBroadcastCapture
      (spawnFn, _) <- mkFakeSpawn(aliveAfterGrace = true)
      hr = new HotRestart(res, 8080, "0.0.0.0", broadcast, tinyTiming, spawnFn)
      (key, _) <- LlmInterface.registerInflight(None)
      accepted <- hr.requestRestart("web-ui", RestartMode.WaitIdle(timeoutMs = 5000))
      _ = assertEquals(accepted, Right(()), "WaitIdle accepts while busy")
      // 等待期 draining 不置位（工作照常准入，设计 §3.4）
      drainingWhileWaiting <- HotRestart.isDraining
      _ = assertEquals(drainingWhileWaiting, false, "WaitIdle waiting must NOT close admission")
      // LLM 完成 → 转空闲 → 编排推进
      _ <- LlmInterface.cancelAllInflight()
      _ <- waitUntil("intent written after idle") { readIntentPhase.map(_.isDefined) }
      _ <- SuccessorGate.markPhase(intentFile, "readyToBind")
      _ <- shutdownD.get.timeout(15.seconds)
    yield ()
  }

  test("WaitIdle: timeout while still busy → abandon + release claim, no intent (验收 3 case 2)") {
    for
      shutdownD <- Deferred[IO, Unit]
      res <- mkResources(shutdownD)
      (broadcast, _) <- mkBroadcastCapture
      (spawnFn, _) <- mkFakeSpawn(aliveAfterGrace = true)
      hr = new HotRestart(res, 8080, "0.0.0.0", broadcast, tinyTiming, spawnFn)
      (key, _) <- LlmInterface.registerInflight(None)
      accepted <- hr.requestRestart("web-ui", RestartMode.WaitIdle(timeoutMs = 300))
      _ = assertEquals(accepted, Right(()))
      _ <- waitUntil("WaitIdle released the claim") { HotRestart.inProgressRef.get.map(!_) }
      intentExists <- IO(os.exists(intentFile))
      _ = assertEquals(intentExists, false, "timeout abandon must not write intent")
      _ <- LlmInterface.cancelAllInflight()
      shutdownUncompleted <- shutdownD.get.timeout(200.millis).attempt.map(_.isLeft)
      _ = assert(shutdownUncompleted, "timeout must NOT trigger shutdown")
    yield ()
  }

  test("cooldown window: restart completed recently → refused (R7)") {
    for
      shutdownD <- Deferred[IO, Unit]
      res <- mkResources(shutdownD)
      hr = new HotRestart(res, 8080, "0.0.0.0", _ => IO.unit, tinyTiming)
      _ <- HotRestart.noteRestartCompleted() // [s7] 等价：冷却锚点刚置位
      result <- hr.requestRestart("web-ui", RestartMode.RejectIfBusy)
      _ = assert(result.isLeft)
      _ = assert(result.left.toOption.get.toLowerCase.contains("cooldown"), result.left.toOption.get)
      intentExists <- IO(os.exists(intentFile))
      _ = assertEquals(intentExists, false, "cooldown refuse must not write intent")
      claimReleased <- HotRestart.inProgressRef.get
      _ = assertEquals(claimReleased, false, "cooldown refuse must release the claim")
    yield ()
  }

  test("in-progress merge: second request while orchestration running merges idempotently (R7)") {
    for
      shutdownD <- Deferred[IO, Unit]
      res <- mkResources(shutdownD)
      (broadcast, _) <- mkBroadcastCapture
      (spawnFn, _) <- mkFakeSpawn(aliveAfterGrace = true)
      hr = new HotRestart(res, 8080, "0.0.0.0", broadcast, tinyTiming, spawnFn)
      r1 <- hr.requestRestart("web-ui", RestartMode.RejectIfBusy)
      _ = assertEquals(r1, Right(()))
      r2 <- hr.requestRestart("other-ui", RestartMode.RejectIfBusy)
      _ = assertEquals(r2, Right(()), "in-flight request merges (accepted, no second orchestration)")
      _ <- waitUntil("intent written") { readIntentPhase.map(_.isDefined) }
      _ <- SuccessorGate.markPhase(intentFile, "readyToBind")
      _ <- shutdownD.get.timeout(15.seconds)
    yield ()
  }

  test("disabled switch: nebflow.hotRestart.enabled=false → refused at the entrance (回滚总开关)") {
    val oldVal = sys.props.get("nebflow.hotRestart.enabled")
    sys.props.put("nebflow.hotRestart.enabled", "false")
    val run: IO[Unit] =
      for
        shutdownD <- Deferred[IO, Unit]
        res <- mkResources(shutdownD)
        hr = new HotRestart(res, 8080, "0.0.0.0", _ => IO.unit, tinyTiming)
        result <- hr.requestRestart("web-ui", RestartMode.RejectIfBusy)
        _ = assert(result.isLeft)
        _ = assert(result.left.toOption.get.contains("disabled"), result.left.toOption.get)
      yield ()
    run.guarantee(IO {
      oldVal match
        case Some(v) => sys.props.put("nebflow.hotRestart.enabled", v)
        case None    => sys.props.remove("nebflow.hotRestart.enabled")
    })
  }

  // ── spawn 命令构造（两形态，§4）──────────────────────────────────

  test("buildCommand jar form: java --add-opens -jar ... start --succeed --no-browser") {
    val cmd = HotRestart.buildCommand("jar", "/fake/java", Some("/fake/nebflow-assembly-1.0.jar"), "/fake/intent.json")
    cmd match
      case Right(parts) =>
        assertEquals(parts.take(4), List("/fake/java", "--add-opens", "java.base/java.lang=ALL-UNNAMED", "-jar"))
        assert(parts.contains("start"), parts.toString)
        val i = parts.indexOf("--succeed")
        assertEquals(parts(i + 1), "/fake/intent.json")
        assertEquals(parts.last, "--no-browser")
      case Left(err) => fail(s"jar form must build: $err")
  }

  test("buildCommand bundled form: bundle executable with --succeed passthrough") {
    val appJar = "/Applications/Nebflow.app/Contents/app/nebflow.jar"
    val cmd = HotRestart.buildCommand("bundled", "/fake/java", Some(appJar), "/fake/intent.json")
    cmd match
      case Right(parts) =>
        assertEquals(parts.head, "/Applications/Nebflow.app/Contents/MacOS/Nebflow")
        assertEquals(parts(1), "--succeed")
        assertEquals(parts(2), "/fake/intent.json")
      case Left(err) => fail(s"bundled form must build from .app/Contents jar: $err")
  }

  test("buildCommand: unknown form / missing jar → loud Left (fail-safe, 不硬闯)") {
    assert(HotRestart.buildCommand("unknown", "/fake/java", None, "/i").isLeft)
    assert(HotRestart.buildCommand("jar", "/fake/java", None, "/i").isLeft)
  }

  private def mkBroadcastCapture: IO[(io.circe.Json => IO[Unit], Ref[IO, List[String]])] =
    Ref.of[IO, List[String]](Nil).map { frames =>
      val send: io.circe.Json => IO[Unit] = j =>
        frames.update(_ :+ j.hcursor.downField("phase").as[String].getOrElse("?")).void
      (send, frames)
    }

end HotRestartSpec
