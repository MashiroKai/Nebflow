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
 * 孤儿后台任务收割批 · failed/cancelled 收殓缺口（D1 主钩子）定向测试。
 *
 * 机制本体（事故 A 2b1e6310 孤儿 ~3h）：NodeEngine 桥的 failed/cancelled/zombie/
 * bg-wait-cap 终态出口只在 runWithAgent 清理段裸 system.stop(ref)——不杀后台进程、
 * 不注销 BgTaskRegistry、不销毁 ShellSession（收殓链整条缺失）。NodeCancel 路径
 * （outcome=Right）已由 Stop→killSessionShellProcesses 覆盖；Left 终态无此链。
 *
 * 修复：在 runWithAgent 清理段的 outcome=Left(Left(_))（桥 failed/cancelled/zombie/
 * bg-wait-cap）分支补 BgTaskRegistry.reclaimSession（杀进程树 + 注销 registry +
 * WS cancelled 帧），fork best-effort 不阻塞终态化。
 *
 * 用例：
 *  - R1 GREEN cancelled-via-bridge（watcher giveUp / AgentControl cancel 同链）：
 *    桥 Cancelled → 节点 cancelled 且名下后台任务被收殓（registry 清空 + WS
 *    backgroundTaskUpdate(status=cancelled) 帧）
 *  - R2 GREEN wait-cap failed：等待总上限兜底 → 节点 failed 且任务被收殓 +
 *    bg-harvest 审计事件
 *  - R3 GREEN reclaimSession 直接收殓（单测）：真实进程树被杀 + registry 清空 +
 *    WS cancelled 帧
 *  - R4 变异验红基线：摘除 NodeEngine 的 reclaim 分支 → R1/R2 红（任务仍滞留
 *    registry、无 WS 帧）——恢复后验绿
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
    bgWaitCapMs: Long = 3_600_000L
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
        bgWaitCapMs = bgWaitCapMs
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

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  // ── R1 cancelled-via-bridge → 收殓 ──────────────────────────────

  test("R1 GREEN: cancelled via bridge reclaims the node's waiting bg task (registry cleared + WS cancelled frame)") {
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
      // 收殓 fork best-effort——等 registry 清空（证明 reclaimSession 跑到 unregisterSession）
      _ <- waitUntil(10.seconds)(sessionTasksEmpty(nodeSid))
      tasksEmpty <- sessionTasksEmpty(nodeSid)
      frames <- wsFrames.get
      _ <- BgTaskRegistry.unregisterSession(Some(nodeSid)).attempt.void // 防御清理
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(done.status, NodeLifecycle.Cancelled, "bridge Cancelled must finalize node as cancelled")
      assert(tasksEmpty, "bg task must be reclaimed (registry cleared) after cancelled terminal")
      assert(hasCancelledFrame(frames, nodeSid), s"WS cancelled backgroundTaskUpdate frame expected, got: ${frames.map(_.noSpaces.take(120)).mkString("|")}")
  }

  // ── R2 wait-cap failed → 收殓 ──────────────────────────────────

  test("R2 GREEN: wait-cap failure reclaims the pending bg task — audit bg-harvest recorded") {
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
      _ <- waitUntil(20.seconds)(
        byName(rt, "cap-a").map(n => NodeLifecycle.Terminal.contains(n.status)))
      done <- byName(rt, "cap-a")
      _ <- waitUntil(10.seconds)(sessionTasksEmpty(nodeSid)) // 收殓跑完
      tasksEmpty <- sessionTasksEmpty(nodeSid)
      events <- readEvents(ws)
      frames <- wsFrames.get
      _ <- BgTaskRegistry.unregisterSession(Some(nodeSid)).attempt.void // 防御清理
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(done.status, NodeLifecycle.Failed, "wait cap must fail the node (never hang)")
      assert(done.result.exists(_.contains("wait cap exceeded")), s"cap annotation expected: ${done.result}")
      assert(tasksEmpty, "pending bg task must be reclaimed after failed terminal")
      assert(events.exists(_.contains("\"bg-harvest\"")), s"bg-harvest audit expected, got: ${events.mkString("|").take(400)}")
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

end NodeBgReclaimSpec
