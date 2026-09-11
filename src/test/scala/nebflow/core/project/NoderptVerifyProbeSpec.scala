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
 * ★ 入库时的改动 = **P4 的前提断言**（P1/P2/P3 逐字未动；原始归档件在
 *   `.nebflow/evidence/20260911_noderpt-verify/NoderptVerifyProbeSpec.scala`，21807 B，
 *   sha256 前 8 `e374c401` 可比对）。两次改写：
 *   - 批 F1（`6308201b`）：把原始前提（`assert(swept.nonEmpty)` + `assert(!activeIds
 *     .contains("n-p4"))` = D1 复现前提）反转成「窗口未收殓不得出库」+ 补「收殓后恢复
 *     归档资格」；
 *   - 批 F1'（修复第 2 轮 2026-09-12，本节点）：F1 的反转被判为回归（`NodeDepsSpec.T4`
 *     / `NodeEdgeRepairSpec` 6 例红），撤销 `chainArchivable` 前置拒收 ⇒ 前提**还原为
 *     出库成立**（= 原始前提），改钉「归档后窗口到点仍收殓」不变式（归档区成员同扫、
 *     归档副本字段清除、事件落盘、不复活进活动区）。
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
  //
  // 批 F1'（修复第 2 轮，2026-09-12）口径 = **归档语义与销毁窗口正交**：
  //   ① `chainArchivable` **不看** `destroyAt`（撤销 F1 的前置拒收）⇒ 带未到期窗口的
  //      链**照常出库**（既有归档判据 `NodeDepsSpec.T4` / `NodeEdgeRepairSpec` 恢复）；
  //   ② 销毁扫描腿**双区读面**（活动区 ∪ 归档区）⇒ 被搬进归档区的成员到点仍被收殓
  //      （进程杀 / 任务注销 / 归档副本 `destroyAt` 清零 / `node-destroyed` 事件）；
  //   ③ 归档副本清字段但**不复活**进活动区（no-revive 纪律）。
  // 本用例按生产 TtlTick 同拍顺序（销毁扫描 → 链级归档）驱动，三段各钉一条。
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
      at = System.currentTimeMillis() + 1500L
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
      archivedWhileOpen <- rt.store.archiveSnapshot.map(_.nodes.keySet.toList)
      // 宿主重启的自愈读面（内存禁 spawn 表清空后一拍扫描必须从**归档副本**重建）
      _ <- BgTaskRegistry.reopenSession(sid)
      _ <- rt.engine.sweepDestroyWindows()
      banRestored <- BgTaskRegistry.finalizedAt(sid)
      tasksInWindow <- BgTaskRegistry.waitingFor(sid)
      aliveInWindow <- IO(java.lang.ProcessHandle.of(pid).map(_.isAlive).orElse(false))
      _ <- IO.sleep(1800.millis) // 越过 destroyAt
      _ <- rt.engine.sweepDestroyWindows() // 后续每一拍
      _ <- IO.sleep(300.millis)
      _ <- rt.engine.sweepDestroyWindows()
      aliveAfter <- IO(java.lang.ProcessHandle.of(pid).map(_.isAlive).orElse(false))
      tasksAfter <- BgTaskRegistry.waitingFor(sid)
      activeAfter <- rt.store.snapshot.map(_.nodes.keySet.toList)
      arch <- rt.store.archiveSnapshot
      events <- readEvents(ws)
      _ <- BgTaskRegistry.reopenSession(sid).attempt.void
      _ <- ShellSession.destroySession(sid).attempt.void
      _ <- BgTaskRegistry.unregisterSession(Some(sid)).attempt.void
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(beforeSweep.isRight, s"the first (pre-expiry) sweep must not blow up: $beforeSweep")
      // ① 窗口未到期**不阻塞**出库（＝ 原始探针的 D1 复现前提；归档语义与窗口正交）
      assert(sweptWhileOpen.exists(_.nodeIds.contains("n-p4")),
        s"F1': an open destroy window must NOT block the chain sweep (archive eligibility is orthogonal): $sweptWhileOpen")
      assert(!activeIds.contains("n-p4"),
        s"F1': the node must have left the active map once its chain was archived: $activeIds")
      assert(archivedWhileOpen.contains("n-p4"),
        s"F1': the node must be in the archive (with its open window) : $archivedWhileOpen")
      // ② 归档区读面：禁 spawn 表自愈（重启后一拍内重建，源 = 归档副本）+ 窗口内任务/进程照跑
      assertEquals(banRestored, Some(at),
        s"F1': the spawn-ban self-heal must rebuild from the ARCHIVED copy (in-memory table was cleared): $banRestored")
      assert(tasksInWindow.nonEmpty, "inside the window the bg task stays registered (read-only evidence window)")
      assert(aliveInWindow, s"inside the window the real background process (pid $pid) must still be alive")
      // ③ 到点收殓：进程被杀 + 任务注销 + 恰 1 条 node-destroyed + 归档副本清字段 + 不复活
      val diag = s"pid=$pid aliveAfterExpiry=$aliveAfter tasksStillRegistered=${tasksAfter.size} " +
        s"destroyEvents=${events.count(_.contains("\"node-destroyed\""))} archivedDestroyAt=${arch.nodes.get("n-p4").flatMap(_.destroyAt)} " +
        s"activeAfter=$activeAfter"
      assert(!aliveAfter, s"an archived member's destroy window must still be honoured at expiry (its processes may not leak); got $diag")
      assertEquals(tasksAfter, Nil, s"its bg tasks must be reclaimed at expiry; got $diag")
      assertEquals(events.count(_.contains("\"node-destroyed\"")), 1,
        s"exactly one node-destroyed event expected (idempotent sweep); got $diag")
      assert(arch.nodes.get("n-p4").exists(_.destroyAt.isEmpty),
        s"F1': the ARCHIVED copy must carry a cleared window after the reclaim: ${arch.nodes.get("n-p4").map(_.destroyAt)}")
      assert(!activeAfter.contains("n-p4"),
        s"F1': clearing the window on the archived copy must NOT revive the node into the active map: $activeAfter")
  }

end NoderptVerifyProbeSpec
