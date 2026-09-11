package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, Behaviors}
import nebflow.agent.*
import nebflow.core.PathUtil
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{BgTaskRegistry, FileLockManager, NodeEditTool, ShellSession, ToolContext}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * 孤儿后台任务收割批 · failed/cancelled 收殓缺口（D1 主钩子）+ noderpt 批 B 段
 * 终态延迟销毁窗口（2026-09-11 作者裁定「一律存活 30 分钟再销毁」）。
 *
 * 机制本体（事故 A 2b1e6310 孤儿 ~3h）：NodeEngine 桥的 failed/cancelled/zombie/
 * bg-wait-cap 终态出口原先只在 runWithAgent 清理段裸 system.stop(ref)——不杀后台
 * 进程、不注销 BgTaskRegistry、不销毁 ShellSession（收殓链整条缺失）。
 *
 * **noderpt 批 B 段改口径**：收殓动作集合不变（同一个 `BgTaskRegistry.reclaimSession`
 * = 杀进程树 + 注销 registry + 逐条 `finalizeTask` + 释放 `ShellSession.sessions` 条目
 * + WS `backgroundTaskUpdate(status="cancelled")` 帧），**时点从「终态瞬间」改为
 * 「窗口到期」**：终态时刻只登记 `destroyAt`（+ `node-destroy-scheduled` 事件 + 禁
 * spawn 表项），窗口内进程/任务照跑、可取证，到点由 `sweepDestroyWindows` 收殓并写
 * `node-destroyed`。挂起腿保持即时收割（本 spec R3 家族口径不变）。
 *
 * 用例：
 *  - R1 window:GREEN cancelled-via-bridge → 终态登记窗口（destroyAt + 事件 + 禁 spawn
 *    表）、**任务仍在册**（窗口内照跑）；到点扫描 ⇒ 收殓 + WS cancelled 帧 + 字段清零，
 *    且**重复扫描幂等**（二次 no-op）
 *  - R2 window:GREEN wait-cap failed → 同上 + `bgWait` 字段清零（⑤ 残留缺陷回归断言）
 *  - R3 GREEN reclaimSession 直接收殓（单测）：真实进程树被杀 + registry 清空 +
 *    WS cancelled 帧（收殓原语本身零改动）
 *  - R4 窗口内禁 spawn（新会话 / 新后台任务各一次被拒读数）
 *  - R5 窗口撤销：带 destroyAt 的节点已非终态 ⇒ 扫描腿撤销窗口 + 解禁 spawn
 *  - R6 FlowMapEventLog ts 求值时点（构造期 → 执行期修复的回归断言）
 */
class NodeBgReclaimSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-node-bg-reclaim"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "test-agent")
  os.write.over(
    tempRoot / "agents" / "test-agent" / "agent.json",
    """{"name":"test-agent","description":"bg reclaim regression agent","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "test-agent" / "system.md", "# test-agent\n")
  os.makeDir.all(tempRoot / "agents" / "general")
  os.write.over(tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"general executor","tools":[],"category":"standalone"}""")
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    // 全局单例残留兜底：全量清空（防御——每个用例已各自 guarantee 清理）
    BgTaskRegistry.unregisterSession(None).attempt.void.unsafeRunSync()

  /** 后台任务模拟 stub LLM：首轮请求时在 BgTaskRegistry 登记一个等待型任务。
    * 登记 jobId/sessionId 记入 Ref 供 spec 断言与清理。 */
  private class BgStubLlm:
    val jobIds: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)
    val nodeSessions: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)
    val turnCount: Ref[IO, Int] = Ref.unsafe[IO, Int](0)
    @volatile var res: SharedResources = null
    def handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(
          req: LlmRequest,
          onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
      ): Stream[IO, StreamChunk] =
        Stream.eval {
          for
            turn <- turnCount.updateAndGet(_ + 1)
            _ <-
              if turn == 1 then
                Option(res) match
                  case None => IO.raiseError(new RuntimeException("spec res not injected"))
                  case Some(r) =>
                    r.agentRegistry.get.flatMap { reg =>
                      reg.values.find(rec => rec.kind == AgentKind.Flow && rec.sessionId.startsWith("node-")) match
                        case Some(rec) =>
                          val jobId = s"bg-reclaim-${java.util.UUID.randomUUID().toString.take(8)}"
                          BgTaskRegistry.register(jobId, rec.sessionId, "spec bg task", "local", "nebula-root") *>
                            jobIds.update(_ :+ jobId) *> nodeSessions.update(_ :+ rec.sessionId)
                        case None => IO.raiseError(new RuntimeException("node session record not found at first LLM request"))
                    }
              else IO.unit
          yield turn
        }.flatMap { turn =>
          val text = req.messages.map(_.textContent).mkString("\n")
          val reply = if turn == 1 then text.linesIterator.nextOption().getOrElse("").take(200) else "bg-noted"
          Stream(StreamChunk.TextDelta(reply), StreamChunk.Done(None, None))
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
      actorSystem = null,
      voiceMutedRef = voiceMuted
    )

  private def mountProject(
    name: String,
    ws: os.Path,
    system: ActorSystem,
    res: SharedResources,
    wsFrames: Ref[IO, List[Json]],
    bgWaitCapMs: Long = 3_600_000L,
    // noderpt 批 B 段：终态延迟销毁窗口压到 0 ⇒ `destroyAt <= now`，扫描腿可即时执行
    // （生产默认 30min 由 Defaults 现读；spec 走构造入参避开全局 prop）。
    destroyWindowMs: Long = 0L,
    withBgHold: Boolean = true
  ): IO[ProjectRuntime] =
    for
      store <- FlowMapStore.open(name, ws.toString)
      engine = new NodeEngine(
        store,
        system,
        res,
        wsSendFn = (j: Json) => wsFrames.update(_ :+ j),
        workspace = ws.toString,
        rootSessionId = "nebula-root",
        projectName = name,
        emitEvent = (_, _, _) => IO.unit,
        bgWaitCapMs = bgWaitCapMs,
        // noderpt 批 A 段：本 spec 主题 = bg 等待家族（腿 1）⇒ 显式置回旧行为；
        // 腿 2（生产默认开）不在本 spec 主题内，显式关（默认开行为由
        // NodeReportReminderSpec 覆盖）。
        bgGateCompletionHold = Some(withBgHold),
        reportGateHold = Some(false),
        // noderpt 批 B 段：销毁窗口接缝（压 0 = 窗口当期到点）。
        destroyWindowMs = Some(destroyWindowMs)
      )
      pd = ProjectDef(name = name, workspace = ws.toString, agentFile = (ws / "AGENTS.md").toString, createdAt = System.currentTimeMillis())
      rt = ProjectRuntime(pd, store, engine, system, res, None)
      _ <- ProjectRuntimeRegistry.register(rt)
    yield rt

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 50.millis)(
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

  private def nodeInput(project: String, nodename: String, extra: (String, Json)*): Json =
    Json.obj(("project" -> Json.fromString(project)) :: ("nodename" -> Json.fromString(nodename)) :: extra.toList*)

  private def createNode(project: String, ws: os.Path, name: String, task: String, res: SharedResources, system: ActorSystem): IO[Unit] =
    val ctx = ToolContext(
      projectRoot = ws.toString,
      sessionId = Some("spec-sid"),
      rootSessionId = Some("nebula-root"),
      sharedResources = Some(res),
      actorSystem = Some(system)
    )
    NodeEditTool
      .call(nodeInput(project, name,
        "description" -> Json.fromString("bg reclaim spec node"),
        "task" -> Json.fromString(task),
        "out" -> Json.fromString("Nebula")).asObject.get, ctx)
      .map(_.left.map(_.message))
      .flatMap {
        case Left(err) => IO.raiseError(new AssertionError(s"NodeEdit failed: $err"))
        case Right(_)  => IO.unit
      }

  private def byName(rt: ProjectRuntime, name: String): IO[NodeDef] =
    rt.store.snapshot.map(_.nodes.values.find(_.name == name)).map {
      case Some(n) => n
      case None    => fail(s"node '$name' must exist")
    }

  /** 等节点 agent 回 Idle（turn 1 结束，bg 任务已登记、桥进入 hold），返回 nodeSessionId。 */
  private def waitNodeIdle(res: SharedResources): IO[String] =
    def go(deadline: Long): IO[String] =
      res.agentRegistry.get.flatMap { reg =>
        reg.values.find(r => r.kind == AgentKind.Flow && r.sessionId.startsWith("node-")) match
          case Some(rec) if rec.status == nebflow.agent.AgentStatus.Idle =>
            IO.pure(rec.sessionId)
          case _ =>
            if System.currentTimeMillis() >= deadline then
              IO.raiseError(new AssertionError("node agent never went Idle"))
            else IO.sleep(50.millis) >> go(deadline)
      }
    go(System.currentTimeMillis() + 15.seconds.toMillis)

  /** BgTaskRegistry 中该会话名下是否仍有无 persistent 任务（= reclaim 未跑完）。 */
  private def sessionTasksEmpty(sessionId: String): IO[Boolean] =
    BgTaskRegistry.waitingFor(sessionId).map(_.isEmpty)

  private def readEvents(ws: os.Path): IO[List[String]] =
    IO.blocking {
      val f = ws / ".nebflow" / "flow-map-events.jsonl"
      if os.exists(f) then os.read(f).linesIterator.toList else Nil
    }

  private def hasCancelledFrame(frames: List[Json], sessionId: String): Boolean =
    frames.exists { j =>
      j.hcursor.get[String]("type").contains("backgroundTaskUpdate") &&
        j.hcursor.get[String]("status").contains("cancelled") &&
        j.hcursor.get[String]("rootSessionId").contains("nebula-root")
    }

  /** 收殓帧计数（幂等断言用：窗口内必须 0 条、到点后恰 1 条/job、二次扫描不增）。 */
  private def cancelledFrameCount(frames: List[Json]): Int =
    frames.count(j => j.hcursor.get[String]("type").contains("backgroundTaskUpdate"))

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  // ── R1 cancelled-via-bridge → 销毁窗口登记 → 到点收殓 ────────────────

  test("R1 window: cancelled via bridge registers the destroy window (task still alive), then the sweep reclaims it") {
    val ws = tempRoot / "ws-r1"
    os.makeDir.all(ws)
    val system = ActorSystem(s"bg-r1-${scala.util.Random.nextInt(100000)}")
    val llm = BgStubLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      _ <- IO(llm.res = res)
      wsFrames <- Ref.of[IO, List[Json]](Nil)
      rt <- mountProject("bg-r1", ws, system, res, wsFrames)
      _ <- createNode("bg-r1", ws, "cancel-a", "result-CANCEL", res = res, system = system)
      nodeSid <- waitNodeIdle(res)
      _ <- waitUntil(10.seconds)(sessionTasksEmpty(nodeSid).map(!_)) // 任务已登记（entry 前置）
      reg <- res.agentRegistry.get
      supRef = reg(nodeSid).supervisorRef
      // 模拟 TaskStuckWatcher giveUp / AgentControl cancel（supervisorRef Cancelled 同链）
      _ <- supRef.traverse_(sup => (sup ! AgentEvent.Cancelled(nodeSid, "stuck — cancelled by spec")).void)
      _ <- waitUntil(20.seconds)(
        byName(rt, "cancel-a").map(n => NodeLifecycle.Terminal.contains(n.status)))
      done <- byName(rt, "cancel-a")
      // ① 窗口刚开启的读数：任务**仍在册**（窗口内进程/任务照跑 = 作者裁定 (b) 的
      //    可取证窗口），且禁 spawn 表已登记该会话。
      tasksAliveInWindow <- sessionTasksEmpty(nodeSid).map(!_)
      finalizedAt <- BgTaskRegistry.finalizedAt(nodeSid)
      framesInWindow <- wsFrames.get
      eventsInWindow <- readEvents(ws)
      // ② 到点扫描（生产 = TtlTick 30s 节拍；spec 压 destroyWindowMs=0 即时到点）
      _ <- rt.engine.sweepDestroyWindows()
      _ <- waitUntil(10.seconds)(sessionTasksEmpty(nodeSid))
      tasksEmptyAfter <- sessionTasksEmpty(nodeSid)
      afterSweep <- byName(rt, "cancel-a")
      framesAfterSweep <- wsFrames.get
      eventsAfterSweep <- readEvents(ws)
      // ③ 幂等：二次扫描零副作用（无新帧、无第二条 node-destroyed）
      _ <- rt.engine.sweepDestroyWindows()
      framesAfterSecond <- wsFrames.get
      eventsAfterSecond <- readEvents(ws)
      _ <- BgTaskRegistry.unregisterSession(Some(nodeSid)).attempt.void // 防御清理
      _ <- BgTaskRegistry.reopenSession(nodeSid).attempt.void
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(done.status, NodeLifecycle.Cancelled, "bridge Cancelled must finalize node as cancelled")
      // 窗口登记（③ 只登记不杀进程）
      assert(done.destroyAt.isDefined, "terminal write must register a destroy window (destroyAt)")
      assert(done.destroyAt.exists(_ <= System.currentTimeMillis()),
        s"compressed window must already be due: ${done.destroyAt}")
      assert(finalizedAt.isDefined, "the session must be in the spawn-ban ledger while the window is open")
      assert(eventsInWindow.exists(l => l.contains("\"node-destroy-scheduled\"")),
        s"node-destroy-scheduled audit expected: ${eventsInWindow.mkString("|").take(500)}")
      assert(eventsInWindow.exists(l => l.contains("node-destroy-scheduled") && l.contains("cause: cancelled")),
        s"the scheduled event must carry the terminal cause: ${eventsInWindow.mkString("|").take(500)}")
      // 窗口内进程/任务照跑（= 可取证；未被即时收割）
      assert(tasksAliveInWindow, "inside the window the bg task MUST still be registered (read-only evidence window)")
      assertEquals(cancelledFrameCount(framesInWindow), 0,
        s"no reclaim frame may be sent inside the window: ${framesInWindow.map(_.noSpaces.take(80)).mkString("|")}")
      // 到点收殓
      assert(tasksEmptyAfter, "the sweep must reclaim the bg task when the window expires")
      assert(afterSweep.destroyAt.isEmpty, "the sweeper must clear destroyAt after reclaiming")
      assert(hasCancelledFrame(framesAfterSweep, nodeSid),
        s"WS cancelled backgroundTaskUpdate frame expected after the sweep: ${framesAfterSweep.map(_.noSpaces.take(120)).mkString("|")}")
      assert(eventsAfterSweep.exists(_.contains("\"node-destroyed\"")),
        s"node-destroyed audit expected: ${eventsAfterSweep.mkString("|").take(500)}")
      // 幂等
      assertEquals(cancelledFrameCount(framesAfterSecond), cancelledFrameCount(framesAfterSweep),
        "a second sweep must be a no-op (idempotent destruction)")
      assertEquals(eventsAfterSecond.count(_.contains("\"node-destroyed\"")), 1,
        "exactly one node-destroyed event may exist")
  }

  // ── R2 wait-cap failed → 窗口登记 + bgWait 清零（⑤）+ 到点收殓 ──────────

  test("R2 window: wait-cap failure registers the window, clears bgWait (⑤) and reclaims at expiry") {
    val ws = tempRoot / "ws-r2"
    os.makeDir.all(ws)
    val system = ActorSystem(s"bg-r2-${scala.util.Random.nextInt(100000)}")
    val llm = BgStubLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      _ <- IO(llm.res = res)
      wsFrames <- Ref.of[IO, List[Json]](Nil)
      rt <- mountProject("bg-r2", ws, system, res, wsFrames, bgWaitCapMs = 1200L)
      _ <- createNode("bg-r2", ws, "cap-a", "result-CAP", res = res, system = system)
      nodeSid <- waitNodeIdle(res)
      _ <- waitUntil(10.seconds)(sessionTasksEmpty(nodeSid).map(!_))
      // 首个 hold 期已置 bgWait（窗口收口前的既有行为）
      _ <- waitUntil(10.seconds)(byName(rt, "cap-a").map(_.bgWait.isDefined))
      _ <- waitUntil(20.seconds)(
        byName(rt, "cap-a").map(n => NodeLifecycle.Terminal.contains(n.status)))
      done <- byName(rt, "cap-a")
      _ <- rt.engine.sweepDestroyWindows()
      _ <- waitUntil(10.seconds)(sessionTasksEmpty(nodeSid)) // 收殓跑完
      tasksEmpty <- sessionTasksEmpty(nodeSid)
      afterSweep <- byName(rt, "cap-a")
      events <- readEvents(ws)
      frames <- wsFrames.get
      _ <- BgTaskRegistry.unregisterSession(Some(nodeSid)).attempt.void // 防御清理
      _ <- BgTaskRegistry.reopenSession(nodeSid).attempt.void
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(done.status, NodeLifecycle.Failed, "wait cap must fail the node (never hang)")
      assert(done.result.exists(_.contains("wait cap exceeded")), s"cap annotation expected: ${done.result}")
      // ⑤ 残留字段回归断言（实测 n-0931699e：status=completed 而 bgWait 非空）
      assertEquals(afterSweep.bgWait, None,
        "the bg-wait-cap exit must clear bgWait (regression: field used to linger forever)")
      assertEquals(afterSweep.destroyAt, None, "the sweep cleared the window registration")
      assert(done.destroyAt.isDefined, "the failed terminal registered a destroy window")
      assert(tasksEmpty, "pending bg task must be reclaimed once the destroy window expires")
      assert(events.exists(_.contains("\"node-destroy-scheduled\"")),
        s"node-destroy-scheduled audit expected: ${events.mkString("|").take(400)}")
      assert(events.exists(_.contains("\"node-destroyed\"")),
        s"node-destroyed audit expected: ${events.mkString("|").take(400)}")
      assert(hasCancelledFrame(frames, nodeSid), s"WS cancelled backgroundTaskUpdate frame expected, got: ${frames.map(_.noSpaces.take(120)).mkString("|")}")
  }

  // ── R3 reclaimSession 直接收殓（组成：registry 清空 + WS 帧）──
  // 进程树杀断已由 ShellKillOnRestartSpec E-1/E-2/E-5 覆盖（本包 JobHealth 为
  // private[tools] 不可直接 assert pid）——R3 验证 reclaimSession 的协调组成。

  test("R3 GREEN: reclaimSession clears registry + sends WS cancelled frame") {
    (for
      wsFrames <- Ref.of[IO, List[Json]](Nil)
      shell <- ShellSession.forSession("r3-session")
      _ <- shell.executeBackground("sleep 300", jobIdOverride = Some("r3-bg"), healthCheckIntervalSec = 1)
      _ <- BgTaskRegistry.register("r3-bg", "r3-session", "r3-bg-desc", "local")
      _ <- waitUntil(5.seconds)(BgTaskRegistry.waitingFor("r3-session").map(_.nonEmpty))
      _ <- BgTaskRegistry.reclaimSession(Some("r3-session"), (j: Json) => wsFrames.update(_ :+ j), "nebula-root")
      stillWaiting <- BgTaskRegistry.waitingFor("r3-session")
      frames <- wsFrames.get
      _ <- shell.cancelBackgroundJob("r3-bg").attempt.void // 防御清理
    yield
      assertEquals(stillWaiting, Nil, "bg task must be cleared from registry after reclaimSession")
      assert(hasCancelledFrame(frames, "r3-session"), s"WS cancelled frame expected, got: ${frames.map(_.noSpaces.take(120)).mkString("|")}")
    ).guarantee(
      ShellSession.killSessionProcesses(Some("r3-session")).attempt.void *>
        ShellSession.destroySession("r3-session").attempt.void *>
        BgTaskRegistry.unregisterSession(Some("r3-session")).attempt.void
    )
  }

  // ── R4 窗口内禁 spawn（新会话 / 新后台任务各一次被拒读数）──────────────

  test("R4 window: spawning inside the destroy window is rejected (new session + new background task)") {
    val sid = s"finalized-${scala.util.Random.nextInt(100000)}"
    val freshSid = s"$sid-fresh"
    for
      shell <- ShellSession.forSession(sid) // 窗口开启前会话可正常创建
      _ <- BgTaskRegistry.markSessionFinalized(sid, System.currentTimeMillis() + 60_000L)
      _ <- BgTaskRegistry.markSessionFinalized(freshSid, System.currentTimeMillis() + 60_000L)
      _ <- ShellSession.forSession(sid) // 复用已有实体：不触发创建 ⇒ 放行（读/操作面不受限）
      newSessionErr <- ShellSession.forSession(freshSid).attempt // 新会话 ⇒ 拒
      bgErr <- shell
        .executeBackground("echo spec-deny-probe", jobIdOverride = Some("deny-probe-1"))
        .attempt // 新后台任务 ⇒ 拒（进程未 spawn）
      waiting <- BgTaskRegistry.waitingFor(sid)
      _ <- BgTaskRegistry.reopenSession(sid)
      _ <- BgTaskRegistry.reopenSession(freshSid)
      _ <- shell.cancelBackgroundJob("deny-probe-1").attempt.void
    yield
      assert(newSessionErr.isLeft, "creating a NEW session for a finalized sessionId must be rejected")
      assert(newSessionErr.left.exists(_.getMessage.contains("read-only evidence window")),
        s"the rejection must be self-describing: ${newSessionErr.left.map(_.getMessage)}")
      assert(bgErr.isLeft, "starting a NEW background task in the window must be rejected")
      assert(bgErr.left.exists(_.getMessage.contains("new background tasks and new sessions are rejected")),
        s"bg rejection message expected: ${bgErr.left.map(_.getMessage)}")
      assertEquals(waiting, Nil, "the rejected spawn must not register anything")
  }

  // ── R5 窗口撤销（节点已非终态 ⇒ 扫描腿撤销窗口 + 解禁 spawn）──────────────

  test("R5 window: a node that is no longer terminal has its window withdrawn by the sweep") {
    val ws = tempRoot / "ws-r5"
    os.makeDir.all(ws)
    val system = ActorSystem(s"bg-r5-${scala.util.Random.nextInt(100000)}")
    val llm = BgStubLlm()
    val sid = s"withdraw-${scala.util.Random.nextInt(100000)}"
    for
      res <- mkResources(system, tempRoot, llm.handle)
      wsFrames <- Ref.of[IO, List[Json]](Nil)
      rt <- mountProject("bg-r5", ws, system, res, wsFrames, destroyWindowMs = 0L)
      // 直种不一致态：Running 节点却带着过期的 destroyAt（重激活后残留的极端形态）
      _ <- rt.store.mutate { s =>
        s.copy(nodes = s.nodes + ("n-r5" -> NodeDef(
          id = "n-r5", name = "withdraw-probe", agent = "general",
          status = NodeLifecycle.Running, task = Some("probe"),
          createdAt = System.currentTimeMillis(),
          sessionRef = Some(sid),
          destroyAt = Some(System.currentTimeMillis() - 1000L))))
      }
      _ <- BgTaskRegistry.markSessionFinalized(sid, System.currentTimeMillis() - 1000L)
      _ <- rt.engine.sweepDestroyWindows()
      after <- byName(rt, "withdraw-probe")
      finalized <- BgTaskRegistry.finalizedAt(sid)
      events <- readEvents(ws)
      _ <- BgTaskRegistry.reopenSession(sid).attempt.void
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(after.destroyAt, None, "a non-terminal node must have its stale window cleared")
      assertEquals(finalized, None, "the spawn ban must be lifted when the window is withdrawn")
      assert(events.exists(_.contains("\"node-destroy-withdrawn\"")),
        s"node-destroy-withdrawn audit expected: ${events.mkString("|").take(400)}")
  }

  // ── R6 FlowMapEventLog ts 求值时点（构造期 → 执行期）──────────────────────

  test("R6: FlowMapEventLog stamps ts at execution time, not at IO construction time (⑤)") {
    val ws = tempRoot / "ws-r6"
    os.makeDir.all(ws)
    for
      // 构造事件 IO（不执行）→ 睡 300ms → 执行：旧实现会把 ts 写成构造时刻（早 300ms）
      built <- IO.pure(FlowMapEventLog.append(ws.toString, "r6", "n-r6", "spec-ts-probe", "ts probe"))
      _ <- IO.sleep(300.millis)
      execStart <- IO(System.currentTimeMillis())
      _ <- built
      raw <- IO.blocking(os.read(ws / ".nebflow" / FlowMapEventLog.FileName))
    yield
      val ts = io.circe.parser
        .parse(raw.linesIterator.toList.last)
        .toOption
        .flatMap(_.hcursor.get[Long]("ts").toOption)
        .getOrElse(fail(s"ts field must be present: $raw"))
      assert(ts >= execStart,
        s"ts must be stamped at execution time (>= $execStart), got $ts (delta=${ts - execStart}ms) — construction-time stamping would be ~300ms earlier")
  }

  // ── R7 真实进程：窗口内进程仍在 → 到点被杀 + 会话条目释放 ────────────────

  test("R7 window: a REAL background process survives the window, then the sweep kills it and releases the session entry") {
    val ws = tempRoot / "ws-r7"
    os.makeDir.all(ws)
    val system = ActorSystem(s"bg-r7-${scala.util.Random.nextInt(100000)}")
    val llm = BgStubLlm()
    val sid = s"pid-probe-${scala.util.Random.nextInt(100000)}"
    val pidFile = tempRoot / s"r7-$sid.pid"
    for
      res <- mkResources(system, tempRoot, llm.handle)
      wsFrames <- Ref.of[IO, List[Json]](Nil)
      rt <- mountProject("bg-r7", ws, system, res, wsFrames, destroyWindowMs = 0L)
      shell <- ShellSession.forSession(sid)
      // 注意：tempRoot 路径含空格（worktree 目录名）⇒ pid 文件路径必须加引号。
      _ <- shell.executeBackground(s"""echo $$$$ > "$pidFile"; sleep 120""", jobIdOverride = Some("pid-probe"))
      _ <- BgTaskRegistry.register("pid-probe", sid, "real pid probe", "local")
      _ <- waitUntil(15.seconds)(IO.blocking(os.exists(pidFile)))
      pid <- IO.blocking(os.read(pidFile).trim.toLong)
      // 种一个「终态 + 窗口已到点」的节点（等价于终态时刻登记、此刻到点）
      _ <- rt.store.mutate { s =>
        s.copy(nodes = s.nodes + ("n-r7" -> NodeDef(
          id = "n-r7", name = "pid-probe-node", agent = "general",
          status = NodeLifecycle.Completed, task = Some("probe"),
          createdAt = System.currentTimeMillis(),
          sessionRef = Some(sid),
          destroyAt = Some(System.currentTimeMillis() - 1L))))
      }
      aliveInWindow <- IO(java.lang.ProcessHandle.of(pid).map(_.isAlive).orElse(false))
      tasksInWindow <- BgTaskRegistry.waitingFor(sid)
      framesInWindow <- wsFrames.get
      // 到点
      _ <- rt.engine.sweepDestroyWindows()
      _ <- waitUntil(15.seconds)(
        IO(java.lang.ProcessHandle.of(pid).map(!_.isAlive).orElse(true))).attempt
      dead <- IO(java.lang.ProcessHandle.of(pid).map(!_.isAlive).orElse(true))
      tasksAfter <- BgTaskRegistry.waitingFor(sid)
      framesAfter <- wsFrames.get
      // 会话条目释放证据：窗口内会话实体仍在（下方 alive/tasks 读数为证），销毁后
      // `forSession` 只能走**创建**分支 ⇒ 命中禁 spawn 守卫（表项保留 = 死会话不可复活）。
      postForSession <- ShellSession.forSession(sid).attempt
      after <- byName(rt, "pid-probe-node")
      _ <- BgTaskRegistry.reopenSession(sid).attempt.void
      _ <- ShellSession.destroySession(sid).attempt.void
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(aliveInWindow, s"inside the window the real background process (pid $pid) must still be alive")
      assert(tasksInWindow.nonEmpty, "inside the window the task stays registered (evidence window)")
      assertEquals(cancelledFrameCount(framesInWindow), 0, "no reclaim frame inside the window")
      assert(dead, s"the sweep must kill the real process tree (pid $pid is still alive)")
      assertEquals(tasksAfter, Nil, "the sweep must unregister the task")
      assertEquals(cancelledFrameCount(framesAfter), 1, "exactly one cancelled frame per reclaimed job")
      assert(postForSession.isLeft,
        s"the ShellSession entry must be released (a fresh forSession hits the finalized-session guard): $postForSession")
      assertEquals(after.destroyAt, None, "destroyAt cleared after the sweep")
  }

end NodeBgReclaimSpec
