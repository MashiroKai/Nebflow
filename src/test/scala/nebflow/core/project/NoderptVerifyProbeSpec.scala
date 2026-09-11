package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, Behaviors}
import nebflow.agent.{AgentCommand, AgentKind, AgentLibrary, AgentRecord, AgentStatus, SharedResources}
import nebflow.core.PathUtil
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{BgTaskOutputStore, BgTaskRegistry, FileLockManager, NodeEditTool, ShellSession, ToolContext}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * 独立复核探针（noderpt-verify 节点产出，**批 F 修复后归档入库**）——每个用例钉一条
 * 复核发现的复现判据，修复前 P2/P3/P4 为红、修复后应全绿：
 *  - P1：判据②（30 分钟窗口口径）**第四分量** —— `BgTaskOutputStore` 终态翻转
 *    （窗口内 running 且可读 → 到点翻 cancelled）。交付 spec R7 只断言了进程/registry/
 *    WS 帧三个分量。
 *  - P2（→ 复核 **D3**，批 F3 修）：`NodeEdit abandon`（NodeTools.scala#abandonNode）
 *    也是**不经 run fiber 的终态写点**，是否与 A 段补的 6 个写点同口径清 `reportPendingSince`。
 *  - P3（→ 复核 **D2**，批 F2 修）：负例审计「`NodeMessage` 重入是否重置计时（应『不重置』）」
 *    的**下游后果** —— 重入轮不唤醒观察桥（⑧-1 同根因）时，申报只被阶梯 `peek` 见到，
 *    节点是否被终态化 / 是否仍有提醒兜底。
 *  - P4（→ 复核 **D1**，批 F1 修）：销毁窗口被链级归档吞掉（生产盘上实测：n-future 到点前
 *    12s 被归档、永不收殓）。
 *
 * ★ 入库时的**唯一改动 = P4 的前提断言反转**（P1/P2/P3 逐字未动）：原 P4 的
 *   `assert(swept.nonEmpty)` + `assert(!activeIds.contains("n-p4"))` 是**复现 D1 的前提
 *   条件**（「链确实被归档出库」）；F1 把该前提本身定为缺陷 ⇒ 两条断言按修复后契约反转
 *   （窗口未收殓不得出库），并补第三段「收殓清 `destroyAt` 后必须恢复归档资格」。
 *   改动仅限这两条断言与新增末段；原始归档件在
 *   `.nebflow/evidence/20260911_noderpt-verify/NoderptVerifyProbeSpec.scala`
 *   （21807 B，sha256 前 8 `e374c401`）可比对。
 */
class NoderptVerifyProbeSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-noderpt-verify-probe"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "general")
  os.write.over(tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"general executor","tools":[],"category":"standalone"}""")
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    BgTaskRegistry.unregisterSession(None).attempt.void.unsafeRunSync()

  override def afterEach(context: munit.AfterEach): Unit =
    ProjectRuntimeRegistry.clear
    System.clearProperty("nebflow.noderpt.remind.ladderMs")
    System.clearProperty("nebflow.noderpt.remind.maxRungs")
    System.clearProperty("nebflow.noderpt.remind.quiescentIntervalMs")

  private def setLadder(ladderMs: String, maxRungs: Int, quiescentMs: String = "600000"): Unit =
    System.setProperty("nebflow.noderpt.remind.ladderMs", ladderMs)
    System.setProperty("nebflow.noderpt.remind.maxRungs", maxRungs.toString)
    System.setProperty("nebflow.noderpt.remind.quiescentIntervalMs", quiescentMs)

  /** 桩 LLM：turn 1 回任务首行；`declareOnTurn` 指定轮次登记一条 node_report 申报。 */
  private class StubLlm(declareOnTurn: Int = 0, category: String = "pass"):
    val turnCount: Ref[IO, Int] = Ref.unsafe[IO, Int](0)
    @volatile var res: SharedResources = null
    val declaredSids: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)
    def handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(req: LlmRequest, onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None): Stream[IO, StreamChunk] =
        Stream.eval {
          for
            turn <- turnCount.updateAndGet(_ + 1)
            _ <- IO(Option(res)).flatMap {
              case None => IO.unit
              case Some(r) =>
                r.agentRegistry.get.flatMap { reg =>
                  reg.values.find(rec => rec.kind == AgentKind.Flow && rec.sessionId.startsWith("node-")) match
                    case None => IO.unit
                    case Some(rec) =>
                      declaredSids.update(s => (s :+ rec.sessionId).distinct) *>
                        (if turn == declareOnTurn then
                           NodeReportRegistry.register(rec.sessionId, BlockedFeedback(category, s"probe declare ($category)", ""))
                         else IO.unit)
                }
            }
          yield turn
        }.flatMap { turn =>
          val text = req.messages.map(_.textContent).mkString("\n")
          val reply = if turn == 1 then text.linesIterator.nextOption().getOrElse("").take(200) else "probe-turn-ack"
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
    wsFrames: Ref[IO, List[Json]] = Ref.unsafe[IO, List[Json]](Nil),
    reportGateHold: Boolean = true,
    destroyWindowMs: Long = 0L
  ): IO[ProjectRuntime] =
    for
      store <- FlowMapStore.open(name, ws.toString)
      engine = new NodeEngine(
        store, system, res,
        wsSendFn = (j: Json) => wsFrames.update(_ :+ j),
        workspace = ws.toString,
        rootSessionId = "nebula-root",
        projectName = name,
        emitEvent = (_, _, _) => IO.unit,
        bgGateCompletionHold = Some(false),
        reportGateHold = Some(reportGateHold),
        destroyWindowMs = Some(destroyWindowMs)
      )
      pd = ProjectDef(name = name, workspace = ws.toString, agentFile = (ws / "AGENTS.md").toString, createdAt = System.currentTimeMillis())
      rt = ProjectRuntime(pd, store, engine, system, res, None)
      _ <- ProjectRuntimeRegistry.register(rt)
    yield rt

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 50.millis)(cond: IO[Boolean]): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then IO.raiseError(new AssertionError("waitUntil: condition not met in time"))
          else IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  private def nodeInput(project: String, nodename: String, extra: (String, Json)*): Json =
    Json.obj(("project" -> Json.fromString(project)) :: ("nodename" -> Json.fromString(nodename)) :: extra.toList*)

  private def createNode(project: String, ws: os.Path, name: String, task: String, res: SharedResources, system: ActorSystem): IO[Unit] =
    val ctx = ToolContext(projectRoot = ws.toString, sessionId = Some("spec-sid"),
      rootSessionId = Some("nebula-root"), sharedResources = Some(res), actorSystem = Some(system))
    NodeEditTool.call(nodeInput(project, name,
      "description" -> Json.fromString("noderpt verify probe node"),
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

  private def waitIdle(res: SharedResources, timeout: FiniteDuration = 20.seconds): IO[String] =
    def go(deadline: Long): IO[String] =
      res.agentRegistry.get.flatMap { reg =>
        reg.values.find(r => r.kind == AgentKind.Flow && r.sessionId.startsWith("node-")) match
          case Some(rec) if rec.status == AgentStatus.Idle => IO.pure(rec.sessionId)
          case _ =>
            if System.currentTimeMillis() >= deadline then IO.raiseError(new AssertionError("node agent never went Idle"))
            else IO.sleep(50.millis) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  private def readEvents(ws: os.Path): IO[List[String]] =
    IO.blocking {
      val f = ws / ".nebflow" / "flow-map-events.jsonl"
      if os.exists(f) then os.read(f).linesIterator.toList else Nil
    }

  private def reminderSummaryCount(events: List[String]): Int =
    events.count(_.contains("\"node-report-missing\""))

  private def deniedFrames(frames: List[Json]): Int =
    frames.count(j => j.hcursor.get[String]("type").contains("backgroundTaskUpdate"))

  // ── P1：判据② 第四分量 —— BgTaskOutputStore 在窗口内 running、到点 cancelled ──

  test("P1: the bg output store stays 'running'/readable inside the destroy window and flips to 'cancelled' at expiry") {
    val ws = tempRoot / "ws-p1"
    os.makeDir.all(ws)
    val system = ActorSystem(s"probe-p1-${scala.util.Random.nextInt(100000)}")
    val llm = StubLlm()
    val sid = s"probe-p1-sid-${scala.util.Random.nextInt(100000)}"
    val jobId = "probe-p1-job"
    val pidFile = tempRoot / s"p1-$sid.pid"
    for
      res <- mkResources(system, tempRoot, llm.handle)
      frames <- Ref.of[IO, List[Json]](Nil)
      rt <- mountProject("probe-p1", ws, system, res, frames, destroyWindowMs = 0L)
      shell <- ShellSession.forSession(sid)
      _ <- shell.executeBackground(s"""echo $$$$ > "$pidFile"; sleep 120""", jobIdOverride = Some(jobId))
      buffer <- BgTaskOutputStore.open(jobId)
      _ <- IO(buffer.append("probe-line-inside-window"))
      _ <- BgTaskRegistry.register(jobId, sid, "probe p1 bg task", "local")
      _ <- waitUntil(15.seconds)(IO.blocking(os.exists(pidFile)))
      pid <- IO.blocking(os.read(pidFile).trim.toLong)
      _ <- rt.store.mutate { s =>
        s.copy(nodes = s.nodes + ("n-p1" -> NodeDef(
          id = "n-p1", name = "probe-p1-node", agent = "general",
          status = NodeLifecycle.Completed, task = Some("probe"),
          createdAt = System.currentTimeMillis(),
          sessionRef = Some(sid),
          destroyAt = Some(System.currentTimeMillis() - 1L))))
      }
      // 窗口内读数：进程活 + 任务在册 + 输出留存区 running 且内容可读
      aliveInWindow <- IO(java.lang.ProcessHandle.of(pid).map(_.isAlive).orElse(false))
      tasksInWindow <- BgTaskRegistry.waitingFor(sid)
      outInWindow <- BgTaskOutputStore.read(jobId, 0L)
      _ <- rt.engine.sweepDestroyWindows()
      _ <- waitUntil(15.seconds)(IO(java.lang.ProcessHandle.of(pid).map(!_.isAlive).orElse(true))).attempt
      dead <- IO(java.lang.ProcessHandle.of(pid).map(!_.isAlive).orElse(true))
      tasksAfter <- BgTaskRegistry.waitingFor(sid)
      outAfter <- BgTaskOutputStore.read(jobId, 0L)
      framesAfter <- frames.get
      after <- byName(rt, "probe-p1-node")
      _ <- ShellSession.destroySession(sid).attempt.void
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(aliveInWindow, s"inside the window the real process (pid $pid) must stay alive")
      assert(tasksInWindow.nonEmpty, "inside the window the bg task stays registered")
      assertEquals(outInWindow.map(_.status), Some("running"),
        "inside the window the output store must still report 'running' (readable evidence)")
      assert(outInWindow.exists(_.output.contains("probe-line-inside-window")),
        s"inside the window the buffered output must be readable: $outInWindow")
      assert(dead, s"at expiry the sweep must kill the process tree (pid $pid still alive)")
      assertEquals(tasksAfter, Nil, "at expiry the task must be unregistered")
      assertEquals(outAfter.map(_.status), Some("cancelled"),
        s"at expiry BgTaskOutputStore must flip the task to a terminal state: $outAfter")
      assertEquals(deniedFrames(framesAfter), 1, "exactly one cancelled frame per reclaimed job")
      assertEquals(after.destroyAt, None, "destroyAt must be cleared after the reclaim")
  }

  // ── P2：abandon 终态写点是否清计时（⑧-3 覆盖穷尽性）────────────────────

  test("P2: NodeEdit abandon (a fiber-less terminal write) clears the pending clock — coverage exhaustiveness") {
    val ws = tempRoot / "ws-p2"
    os.makeDir.all(ws)
    val system = ActorSystem(s"probe-p2-${scala.util.Random.nextInt(100000)}")
    val llm = StubLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("probe-p2", ws, system, res, destroyWindowMs = 0L)
      // 与 NodeReportReminderSpec R8 同构的种子：Running + 无活 fiber（死会话），带计时
      _ <- rt.store.mutate { s =>
        s.copy(nodes = s.nodes + ("n-p2" -> NodeDef(
          id = "n-p2", name = "abandon-probe", agent = "general",
          status = NodeLifecycle.Running, task = Some("probe"),
          createdAt = System.currentTimeMillis(),
          sessionRef = Some("probe-p2-dead-sid"),
          reportPendingSince = Some(System.currentTimeMillis() - 70000L),
          reportReminderCount = 2)))
      }
      r <- NodeEditTool.call(nodeInput("probe-p2", "abandon-probe",
        "abandon" -> Json.fromBoolean(true)).asObject.get,
        ToolContext(projectRoot = ws.toString, sessionId = Some("spec-sid"),
          rootSessionId = Some("nebula-root"), sharedResources = Some(res), actorSystem = Some(system)))
      node <- byName(rt, "abandon-probe")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isRight, s"abandon must succeed on a dead-session running node: $r")
      assertEquals(node.status, NodeLifecycle.Cancelled, "abandon → cancelled")
      assertEquals(node.reportPendingSince, None,
        s"abandon is a terminal write point — it must clear the pending clock like the 6 covered points (actual=${node.reportPendingSince})")
      assertEquals(node.reportReminderCount, 0,
        s"abandon must reset the rung counter (actual=${node.reportReminderCount})")
  }

  // ── P3：NodeMessage 重入 + 申报 ⇒ 是否被终态化 / 是否仍有提醒兜底 ──────────

  test("P3: a node_report declared on a NodeMessage-driven turn (no bridge Completed) still gets finalized or reminded") {
    // 阶梯压到 700ms —— 关键：扫描腿只在**有档到点**时才走 peek 分支
    // （`if !ladderStage && !quiescentStage then IO.unit`），故必须让第 1 档到点，
    // 才能复现「peek 见申报 ⇒ 清表」这条路。本探针**手动逐拍驱动**扫描腿
    // （spec 无 ProjectActor 节拍），故不会出现「早于申报注入的抢跑提醒」。
    setLadder("700,3000", 2, "600000")
    val ws = tempRoot / "ws-p3"
    os.makeDir.all(ws)
    val system = ActorSystem(s"probe-p3-${scala.util.Random.nextInt(100000)}")
    val llm = StubLlm(declareOnTurn = 2)
    for
      res <- mkResources(system, tempRoot, llm.handle)
      _ <- IO(llm.res = res)
      rt <- mountProject("probe-p3", ws, system, res, reportGateHold = true, destroyWindowMs = 60000L)
      _ <- createNode("probe-p3", ws, "reentry-a", "result-REENTRY", res, system)
      sid <- waitIdle(res)
      _ <- waitUntil(20.seconds)(byName(rt, "reentry-a").map(_.reportPendingSince.isDefined))
      held <- byName(rt, "reentry-a")
      // 分发器 NodeMessage 重入（ImmediateInput，replyTo=None —— ⑧-1 同根因）
      sent <- rt.engine.sendNodeMessage(held.id, "please continue and report")
      _ <- waitUntil(20.seconds)(NodeReportRegistry.peek(sid).map(_.isDefined)).attempt
      declared <- NodeReportRegistry.peek(sid)
      _ <- waitUntil(20.seconds)( // 等重入轮跑完（该轮不产生 bridge Completed）
        res.agentRegistry.get.map(_.get(sid).exists(_.status == AgentStatus.Idle)))
      _ <- IO.sleep(900.millis) // 越过第 1 档阈值（700ms）
      _ <- rt.engine.remindUnreportedNodes() // 第 1 拍：peek 见申报 ⇒ 清表（本批逻辑）
      afterScan <- byName(rt, "reentry-a")
      _ <- IO.sleep(300.millis)
      _ <- rt.engine.remindUnreportedNodes() // 第 2 拍：清表后不再入选
      finalState <- byName(rt, "reentry-a")
      _ <- IO.sleep(300.millis)
      _ <- rt.engine.remindUnreportedNodes()
      settled <- byName(rt, "reentry-a")
      events <- readEvents(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(sent.isRight, true, s"NodeMessage to a held Running node must be accepted: $sent")
      assert(declared.isDefined, "the re-entered turn must have registered the node_report declaration")
      // 诊断读数（无论红绿都要落进失败消息）：
      val diag = s"status=${settled.status} pendingSince=${settled.reportPendingSince} " +
        s"rungs=${settled.reportReminderCount} reminderEvents=${reminderSummaryCount(events)} " +
        s"clockAfterFirstScan=${afterScan.reportPendingSince.isDefined} jobDone=${afterScan.destroyAt.isDefined}"
      assert(NodeLifecycle.Terminal.contains(settled.status) || settled.reportPendingSince.isDefined,
        s"a declaration that the bridge cannot observe must NOT silently disarm the safety net — " +
          s"the node must either be finalized or still be covered by the ladder; got $diag")
  }

  // ── P4：销毁窗口被链级归档吞掉（生产盘上实测：n-future 到点前 12s 被归档、永不收殓）──

  test("P4: a terminal node whose chain is archived while its destroy window is still open IS still reclaimed at expiry") {
    val ws = tempRoot / "ws-p4"
    os.makeDir.all(ws)
    val system = ActorSystem(s"probe-p4-${scala.util.Random.nextInt(100000)}")
    val llm = StubLlm()
    val sid = s"probe-p4-sid-${scala.util.Random.nextInt(100000)}"
    val jobId = "probe-p4-job"
    val pidFile = tempRoot / s"p4-$sid.pid"
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("probe-p4", ws, system, res, destroyWindowMs = 1800000L)
      shell <- ShellSession.forSession(sid)
      _ <- shell.executeBackground(s"""echo $$$$ > "$pidFile"; sleep 120""", jobIdOverride = Some(jobId))
      _ <- BgTaskRegistry.register(jobId, sid, "probe p4 bg task", "local")
      _ <- waitUntil(15.seconds)(IO.blocking(os.exists(pidFile)))
      pid <- IO.blocking(os.read(pidFile).trim.toLong)
      at = System.currentTimeMillis() + 1200L
      // 终态 + 窗口**未到点**（等价于 `scheduleDestroy` 刚登记的时刻）
      _ <- rt.store.mutate { s =>
        s.copy(nodes = s.nodes + ("n-p4" -> NodeDef(
          id = "n-p4", name = "probe-p4-node", agent = "general",
          status = NodeLifecycle.Completed, task = Some("probe"),
          createdAt = System.currentTimeMillis(),
          sessionRef = Some(sid), destroyAt = Some(at))))
      }
      _ <- BgTaskRegistry.markSessionFinalized(sid, at)
      // 生产 TtlTick 的**同一拍**：销毁扫描在前（未到点 ⇒ 零动作），链级归档紧随其后
      beforeSweep <- rt.engine.sweepDestroyWindows().attempt
      sweptWhileOpen <- rt.store.sweepCompletedChainsDetailed(System.currentTimeMillis())
      activeIds <- rt.store.snapshot.map(_.nodes.keySet.toList)
      _ <- IO.sleep(1600.millis) // 越过 destroyAt
      _ <- rt.engine.sweepDestroyWindows() // 后续每一拍
      _ <- IO.sleep(300.millis)
      _ <- rt.engine.sweepDestroyWindows()
      aliveAfter <- IO(java.lang.ProcessHandle.of(pid).map(_.isAlive).orElse(false))
      tasksAfter <- BgTaskRegistry.waitingFor(sid)
      sweptAfterReclaim <- rt.store.sweepCompletedChainsDetailed(System.currentTimeMillis())
      arch <- rt.store.archiveSnapshot
      events <- readEvents(ws)
      _ <- BgTaskRegistry.reopenSession(sid).attempt.void
      _ <- ShellSession.destroySession(sid).attempt.void
      _ <- BgTaskRegistry.unregisterSession(Some(sid)).attempt.void
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(beforeSweep.isRight, s"the first (pre-expiry) sweep must not blow up: $beforeSweep")
      // ★ 前提反转（批 F1）：窗口**未收殓** ⇒ 链不得出库、节点留活动区。原始探针此处为
      //   `assert(swept.nonEmpty)` + `assert(!activeIds.contains("n-p4"))`（= D1 复现前提）；
      //   F1 把该前提本身定为缺陷 ⇒ 按修复后契约反转。
      assert(sweptWhileOpen.isEmpty,
        s"F1: a chain that still has an open destroy window must NOT be archived: $sweptWhileOpen")
      assert(activeIds.contains("n-p4"),
        s"F1: the node must stay in the active map while its window is open (only the active map is swept): $activeIds")
      val diag = s"pid=$pid aliveAfterExpiry=$aliveAfter tasksStillRegistered=${tasksAfter.size} " +
        s"destroyEvents=${events.count(_.contains("\"node-destroyed\""))} archivedDestroyAt=${arch.nodes.get("n-p4").flatMap(_.destroyAt)}"
      assert(!aliveAfter, s"a node with an open destroy window must still be reclaimed at expiry (its processes may not leak); got $diag")
      assertEquals(tasksAfter, Nil, s"its bg tasks must be reclaimed at expiry; got $diag")
      assertEquals(events.count(_.contains("\"node-destroyed\"")), 1,
        s"exactly one node-destroyed event expected (idempotent sweep); got $diag")
      // ★ 新增末段（批 F1 契约的另一半）：收殓清 `destroyAt` ⇒ 归档资格恢复（不永久阻塞出库）
      assert(sweptAfterReclaim.exists(_.nodeIds.contains("n-p4")),
        s"F1: once the window is reclaimed the chain must become archivable again: $sweptAfterReclaim")
      assert(arch.nodes.get("n-p4").exists(_.destroyAt.isEmpty),
        s"F1: the archived copy must carry a cleared window: ${arch.nodes.get("n-p4").map(_.destroyAt)}")
  }

end NoderptVerifyProbeSpec
