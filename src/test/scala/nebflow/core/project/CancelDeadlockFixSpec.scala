package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.parser.parse as jsonParse
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentLibrary, SharedResources}
import nebflow.core.PathUtil
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * 取消静默死锁修复批（2026-09-10）**R1–R5 单测与引擎集成**。
 *
 * 覆盖（每条注明对应裁定项）：
 *  - R1-A1 cancelled 通知触发 + 文本专属指引（与 failed 四步区分）+ 不查 flag +
 *    二次调用 no-op（持久去重）
 *  - R1-A2 预算耗尽：节点**保持 cancelled** + markSent 止重扫 + single-flight notice；
 *    **账务独立**（cancelled 耗尽不吃 failed 预算，反之亦然）
 *  - R1-A3 窗口熔断：Suppress **不 markSent** → 冷却结束 redeliver 补投；窗口与
 *    failed **各自独立**（cancelled 风暴不静音 failed）
 *  - R1-A4 redeliver 候选集含 cancelled（重启/周期补投面）
 *  - R2/R4/R7 reap 路径（source=engine）：result 落盘 + out→Nebula + 下游 in prune +
 *    pendingSuccession + WS 帧可见 + cancelled/barrier-blocked 审计
 *  - R4 闸门：带「待承接」标的下游不被 settle sweep 以缺轨输入启动
 *  - R3 去重：即时 barrier-blocked 与周期 mount-stalled 单发（同 barrier 一条），
 *    且未被即时告警的停滞仍由回扫照常发（不是全面静音）
 *  - R3 failed 侧：settleStaleRunningNodes 收敛 failed → barrier-blocked；且
 *    **零摘除**（下游 in 不动、无 pendingSuccession）——R4 硬约束的代码面证据
 *  - R5：settleFailedHardResume 失败腿（摘除 + 即时告警 + R1 回流 + hard-recovery
 *    留痕）与两条无动作腿（非 cancelled 节点 / 无归属会话）
 *
 * ⚠ 隔离实例（真 gateway）实测 Δ 读数不在本文件——见批报告证据目录。
 */
class CancelDeadlockFixSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-cancel-deadlock"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "general")
  os.write.over(tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"cancel-deadlock spec agent","tools":[],"category":"standalone"}""")
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  // ── 装配 ──────────────────────────────────────────────────────────

  private class StubLlm:
    def handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(req: LlmRequest, onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None): Stream[IO, StreamChunk] =
        Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

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

  /** 挂载真实 NodeEngine（triggerOverride = R1 测试接缝，捕获通知文本原文；
    * emitEvent 接成帧捕获——生产由 ProjectActor 把 emitEvent 接到项目 WS）。 */
  private def mountProject(
    name: String,
    ws: os.Path,
    system: ActorSystem,
    res: SharedResources,
    triggered: Ref[IO, List[String]],
    wsFrames: Ref[IO, List[Json]] = Ref.unsafe[IO, List[Json]](Nil)
  ): IO[ProjectRuntime] =
    for
      store <- FlowMapStore.open(name, ws.toString)
      engine = new NodeEngine(
        store, system, res,
        wsSendFn = (j: Json) => wsFrames.update(_ :+ j),
        workspace = ws.toString,
        rootSessionId = "nebula-root",
        projectName = name,
        emitEvent = (typ: String, nodeId: String, payload: Json) =>
          wsFrames.update(_ :+ payload.deepMerge(Json.obj(
            "type" -> Json.fromString(typ), "nodeId" -> Json.fromString(nodeId)))),
        notifyTriggerOverride = Some((text: String) => triggered.update(_ :+ text))
      )
      pd = ProjectDef(name = name, workspace = ws.toString, agentFile = (ws / "AGENTS.md").toString,
        createdAt = System.currentTimeMillis())
      rt = ProjectRuntime(pd, store, engine, system, res, None)
      _ <- ProjectRuntimeRegistry.register(rt)
    yield rt

  private def seed(store: FlowMapStore, n: NodeDef): IO[Unit] =
    store.mutate(s => s.copy(nodes = s.nodes + (n.id -> n))).void

  private def node(rt: ProjectRuntime, id: String): IO[NodeDef] =
    rt.store.getNode(id).map(_.getOrElse(fail(s"node '$id' must exist")))

  private def readAudit(ws: os.Path): IO[List[(String, String, String)]] =
    IO.blocking(os.read(ws / ".nebflow" / FlowMapEventLog.FileName))
      .map(_.linesIterator.toList.filter(_.trim.nonEmpty))
      .map(_.flatMap(l => jsonParse(l).toOption.map(j => (
        j.hcursor.get[String]("type").getOrElse(""),
        j.hcursor.get[String]("nodeId").getOrElse(""),
        j.hcursor.get[String]("summary").getOrElse("")))))
      .handleError(_ => Nil)

  // ── R1 单测装配：直接驱动 DispatchNotify（不经引擎） ──────────────

  private def mkNotify(
    name: String,
    cancelledBudgetMax: Int = DispatchNotify.DefaultBudget,
    cancelledWindowMs: Long = DispatchNotify.FailedWindowMs,
    cancelledCooldownMs: Long = DispatchNotify.FailedCooldownMs,
    cancelledWindowThreshold: Int = DispatchNotify.FailedWindowThreshold,
    failedBudgetMax: Int = DispatchNotify.DefaultBudget,
    failedWindowMs: Long = DispatchNotify.FailedWindowMs,
    failedCooldownMs: Long = DispatchNotify.FailedCooldownMs,
    failedWindowThreshold: Int = DispatchNotify.FailedWindowThreshold
  ): IO[(FlowMapStore, DispatchNotify, Ref[IO, List[String]], Ref[IO, List[String]], os.Path)] =
    val ws = tempRoot / s"unit-$name-${scala.util.Random.nextInt(100000)}"
    os.makeDir.all(ws)
    for
      store <- FlowMapStore.open(s"cancel-$name", ws.toString)
      triggered <- Ref.of[IO, List[String]](Nil)
      escalated <- Ref.of[IO, List[String]](Nil)
      dn = new DispatchNotify(
        store, ws.toString, s"cancel-$name",
        escalate = (text, _) => escalated.update(_ :+ text),
        emitUpdated = (_: NodeDef) => IO.unit,
        trigger = text => triggered.update(_ :+ text),
        failedBudgetMax = failedBudgetMax,
        failedWindowMs = failedWindowMs,
        failedCooldownMs = failedCooldownMs,
        failedWindowThreshold = failedWindowThreshold,
        cancelledBudgetMax = cancelledBudgetMax,
        cancelledWindowMs = cancelledWindowMs,
        cancelledCooldownMs = cancelledCooldownMs,
        cancelledWindowThreshold = cancelledWindowThreshold
      )
    yield (store, dn, triggered, escalated, ws)

  private def cNode(id: String, name: String, notify: Boolean = false): NodeDef =
    NodeDef(id = id, name = name, agent = "general", status = NodeLifecycle.Cancelled,
      result = Some(s"cancelled[source=user]: reason=$name cancelled by user"),
      notifyDispatcher = notify, createdAt = System.currentTimeMillis())

  private def fNode(id: String, name: String): NodeDef =
    NodeDef(id = id, name = name, agent = "general", status = NodeLifecycle.Failed,
      result = Some("boom"), createdAt = System.currentTimeMillis())

  // ── R1-A1：触发 + 文本 + 去重 ────────────────────────────────────

  test("R1-A1: cancelled terminal triggers the dispatcher — text carries cancelled-only guidance (承接/改接/abandon), NOT the failed四步 reactivate recipe; flag not consulted; second call no-op") {
    for
      (store, dn, triggered, _, _) <- mkNotify("trigger")
      // notifyDispatcher = false：与 failed 对称**不查 flag**——cancelled 是无人订阅的异常终态
      _ <- seed(store, cNode("n-c1", "cancel-one", notify = false))
      _ <- seed(store, fNode("n-f1", "fail-one"))
      c <- store.getNode("n-c1").map(_.get)
      f <- store.getNode("n-f1").map(_.get)
      _ <- dn.notifyTerminal(c, NotifyReason.Cancelled)
      _ <- dn.notifyTerminal(c, NotifyReason.Cancelled) // 二次必须 no-op（持久去重）
      _ <- dn.notifyTerminal(f, NotifyReason.Failed) // 对照：failed 文本
      cTexts <- triggered.get
      cAfter <- store.getNode("n-c1").map(_.get)
      // 反例①：状态不匹配（completed 节点 + Cancelled reason）→ 零动作
      _ <- seed(store, NodeDef(id = "n-done", name = "done-one", agent = "general",
        status = NodeLifecycle.Completed, result = Some("R"), notifyDispatcher = false,
        createdAt = System.currentTimeMillis()))
      d <- store.getNode("n-done").map(_.get)
      _ <- dn.notifyTerminal(d, NotifyReason.Cancelled)
      afterMismatch <- triggered.get
    yield
      assertEquals(cTexts.size, 2, s"exactly one cancelled + one failed trigger expected, got ${cTexts.size}")
      assertEquals(afterMismatch.size, 2, "status mismatch (completed + Cancelled) must be a no-op")
      val cText = cTexts.find(_.contains("cancel-one")).getOrElse(fail("cancelled notify text must exist"))
      val fText = cTexts.find(_.contains("fail-one")).getOrElse(fail("failed notify text must exist"))
      // 与 failed 区分（作者明令）：cancelled 不可重激活
      assert(cText.contains("已被**取消**") && cText.contains("reason=cancelled"), s"cancelled text header: $cText")
      assert(cText.contains("不可重激活"), "cancelled text must state non-reactivatable")
      assert(cText.contains("承接") && cText.contains("改接") && cText.contains("abandon"),
        "cancelled text must offer 承接/改接/abandon")
      // 严禁照抄 failed 四步：cancelled 文本不得携带 failed 的动作措辞（「原节点复活」/
      // 「NodeEdit 编辑该节点任意实际变更…触发 reactivate 重跑」），必须显式写「请勿照用」
      assert(!cText.contains("原节点复活"), "cancelled text must not offer the failed revive outcome")
      assert(!cText.contains("NodeEdit 编辑该节点任意实际变更"),
        "cancelled text must not copy the failed step-1 recipe")
      assert(cText.contains("请勿照用"), "cancelled text must explicitly forbid reusing the failed recipe")
      // failed 侧逐字保留（本批 failed 文本零变化）
      assert(fText.contains("NodeEdit 编辑该节点任意实际变更") && fText.contains("原节点复活"),
        "failed text must keep its reactivate recipe (zero drift)")
      assert(!fText.contains("不可重激活"), "failed text must not gain the cancelled-only wording")
      assertEquals(cAfter.notifySentAt.isDefined, true, "notifySentAt persisted after first cancelled trigger")
  }

  // ── R1-A2：预算耗尽 + 账务独立 ───────────────────────────────────

  test("R1-A2: cancelled budget exhaustion keeps the node cancelled + single-flight notice; accounting is INDEPENDENT of the failed budget") {
    for
      (store, dn, triggered, escalated, _) <- mkNotify("budget", cancelledBudgetMax = 1, failedBudgetMax = 2)
      _ <- seed(store, cNode("n-cb1", "cb1"))
      _ <- seed(store, cNode("n-cb2", "cb2"))
      _ <- seed(store, fNode("n-fb1", "fb1"))
      n1 <- store.getNode("n-cb1").map(_.get)
      n2 <- store.getNode("n-cb2").map(_.get)
      f1 <- store.getNode("n-fb1").map(_.get)
      _ <- dn.notifyTerminal(n1, NotifyReason.Cancelled) // 预算 1 用尽
      _ <- dn.notifyTerminal(n2, NotifyReason.Cancelled) // 耗尽 → 保持 cancelled + notice
      _ <- dn.notifyTerminal(f1, NotifyReason.Failed)    // failed 预算独立 → 照常触发
      n2After <- store.getNode("n-cb2").map(_.get)
      f1After <- store.getNode("n-fb1").map(_.get)
      fired <- triggered.get
      esc <- escalated.get
    yield
      assertEquals(n2After.status, NodeLifecycle.Cancelled, "budget exhaustion must NOT flip the node's terminal state")
      assertEquals(n2After.notifySentAt.isDefined, true, "exhausted node must be marked (leaves the redeliver candidate set)")
      assertEquals(f1After.notifySentAt.isDefined, true, "failed node notified independently")
      assert(fired.exists(_.contains("cb1")), "first cancelled within budget must trigger")
      assert(fired.exists(_.contains("fb1")) && fired.exists(_.contains("reason=failed")),
        s"failed must still trigger with its own budget untouched, got: $fired")
      assertEquals(fired.count(_.contains("cb2")), 0, "over-budget cancelled must not trigger a dispatcher session")
      assertEquals(esc.size, 1, s"exactly one single-flight notice expected, got: $esc")
      assert(esc.head.contains("cancelled 通知预算耗尽") && esc.head.contains("承接 / 改接 / abandon"),
        s"cancelled budget notice wording: ${esc.head}")
  }

  // ── R1-A3：窗口熔断 + Suppress 不标记 → 冷却后补投 ───────────────

  test("R1-A3: cancel-window threshold → cooldown (merged notice, unmarked); in-cooldown cancels suppressed WITHOUT marking → redeliver after cooldown delivers the backlog; failed window unaffected") {
    val winMs = 400L
    for
      (store, dn, triggered, escalated, _) <- mkNotify("window",
        cancelledWindowThreshold = 2, cancelledWindowMs = winMs, cancelledCooldownMs = winMs,
        failedWindowThreshold = 2, failedWindowMs = winMs, failedCooldownMs = winMs)
      _ <- seed(store, cNode("n-w1", "w1"))
      _ <- seed(store, cNode("n-w2", "w2"))
      _ <- seed(store, cNode("n-w3", "w3"))
      _ <- seed(store, fNode("n-wf", "wf"))
      n1 <- store.getNode("n-w1").map(_.get)
      n2 <- store.getNode("n-w2").map(_.get)
      n3 <- store.getNode("n-w3").map(_.get)
      f <- store.getNode("n-wf").map(_.get)
      _ <- dn.notifyTerminal(n1, NotifyReason.Cancelled) // 窗口内第 1 次 → Proceed
      _ <- dn.notifyTerminal(n2, NotifyReason.Cancelled) // 第 2 次触阈 → CooldownOn（不标记）
      _ <- dn.notifyTerminal(n3, NotifyReason.Cancelled) // 冷却期内 → Suppress（不标记）
      w2Mid <- store.getNode("n-w2").map(_.get)
      w3Mid <- store.getNode("n-w3").map(_.get)
      escMid <- escalated.get
      firedMid <- triggered.get
      // cancelled 的 cooldown 不得静音 failed（窗口独立）
      _ <- dn.notifyTerminal(f, NotifyReason.Failed)
      fMid <- store.getNode("n-wf").map(_.get)
      firedAfterFailed <- triggered.get
      // 冷却 + 窗口同时过期 → 两轮 redeliver 把欠账全部补投
      _ <- IO.sleep((winMs + 150).millis)
      count1 <- dn.redeliver()
      _ <- IO.sleep((winMs + 150).millis)
      count2 <- dn.redeliver()
      w2After <- store.getNode("n-w2").map(_.get)
      w3After <- store.getNode("n-w3").map(_.get)
      firedFinal <- triggered.get
    yield
      assertEquals(w2Mid.notifySentAt, None, "CooldownOn must NOT mark the node (it stays in the redeliver backlog)")
      assertEquals(w3Mid.notifySentAt, None, "Suppress must NOT mark the node")
      assertEquals(escMid.size, 1, s"exactly one merged cooldown notice for the cancel window, got $escMid")
      assert(escMid.head.contains("节点取消通知达 2 次") || escMid.head.contains("取消通知"),
        s"cooldown notice must be the cancel-window one: ${escMid.head}")
      assert(firedAfterFailed.exists(_.contains("wf")) && fMid.notifySentAt.isDefined,
        "failed notification must proceed while the cancel window is in cooldown (independent windows)")
      assert(count1 >= 2, s"first redeliver must see the two unmarked cancelled nodes, got $count1")
      assert(count2 >= 1, s"second redeliver must see the remaining backlog, got $count2")
      assertEquals(w2After.notifySentAt.isDefined, true, "backlog node w2 must be delivered after cooldown")
      assertEquals(w3After.notifySentAt.isDefined, true, "backlog node w3 must be delivered after cooldown")
      assert(firedFinal.exists(_.contains("w2")) && firedFinal.exists(_.contains("w3")),
        s"both backlogged cancel notifications must be delivered (delayed, not lost), got: $firedFinal")
  }

  // ── R1-A4：redeliver 候选集含 cancelled ─────────────────────────

  test("R1-A4: redeliver candidate set includes cancelled ∧ unmarked ∧ result-nonEmpty (boot/periodic backfill)") {
    for
      (store, dn, triggered, _, _) <- mkNotify("redeliver")
      _ <- seed(store, cNode("n-r1", "r1"))                      // 候选
      _ <- seed(store, cNode("n-r2", "r2").copy(result = None))  // 无 result → 不入候选
      _ <- seed(store, cNode("n-r3", "r3").copy(notifySentAt = Some(System.currentTimeMillis()))) // 已标记 → 不入
      _ <- seed(store, NodeDef(id = "n-r4", name = "r4", agent = "general",
        status = NodeLifecycle.Cancelled, result = Some("x"), notifyDispatcher = false,
        createdAt = System.currentTimeMillis())) // flag=false 仍入候选（不查 flag）
      cnt <- dn.redeliver()
      fired <- triggered.get
    yield
      assertEquals(cnt, 2, s"candidates = r1 + r4 (flag ignored for cancelled), got $cnt")
      assertEquals(fired.size, 2, s"both candidates must be notified: $fired")
      assert(!fired.exists(_.contains("r2")), "cancelled without result must not be notified")
      assert(!fired.exists(_.contains("r3")), "already-marked cancelled must not be re-notified")
  }

  // ── R2/R4/R7：reap 路径（engine 发起）集成 ──────────────────────

  test("R2/R4/R7-integration: dead-session reap (source=engine) writes result + detaches out→Nebula + prunes downstream in + marks 待承接 + emits nodeUpdated + audits cancelled/barrier-blocked; downstream stays held") {
    val ws = tempRoot / "ws-reap"
    os.makeDir.all(ws)
    val system = ActorSystem(s"cd-reap-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot, new StubLlm().handle)
      triggered <- Ref.of[IO, List[String]](Nil)
      frames <- Ref.of[IO, List[Json]](Nil)
      rt <- mountProject("cd-reap", ws, system, res, triggered, frames)
      _ <- seed(rt.store, NodeDef(id = "n-a", name = "A", agent = "general", task = Some("work-A"),
        status = NodeLifecycle.Running, out = List(OutEdge("n-b")),
        startedAt = Some(now - 3_600_000L), createdAt = now - 3_600_000L))
      _ <- seed(rt.store, NodeDef(id = "n-b", name = "B", agent = "general", task = Some("work-B"),
        status = NodeLifecycle.Pending, in = List("n-a"), createdAt = now - 600_000L))
      reap <- rt.engine.reapStaleRunning("n-a")
      a <- node(rt, "n-a")
      b <- node(rt, "n-b")
      audit <- readAudit(ws)
      texts <- triggered.get
      // R4 闸门：回扫多轮不得以「缺轨输入」启动 B
      _ <- rt.engine.settleRunnableSweep()
      _ <- rt.engine.settleRunnableSweep()
      bAfterSweeps <- node(rt, "n-b")
      wsFrames <- frames.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(reap.isRight, s"reap must succeed: $reap")
      assertEquals(a.status, NodeLifecycle.Cancelled, "reap must finalize as cancelled")
      val r = a.result.getOrElse(fail("R2: cancelled result must be persisted"))
      assert(r.startsWith("cancelled[source=engine]: reason="), s"R2/R7 result format, got: $r")
      assert(r.contains("dead-session reap"), s"R2 reason text must carry the read-cause, got: $r")
      assertEquals(a.out, List(OutEdge.nebula), "R4: cancelled node out must be detached to Nebula")
      assert(!b.in.contains("n-a"), s"R4: downstream in mirror must be pruned, got ${b.in}")
      assertEquals(b.pendingSuccession, List("n-a"), "R4: downstream must register the 待承接 marker")
      assertEquals(bAfterSweeps.status, NodeLifecycle.Pending,
        "R4 gate: a pendingSuccession-marked barrier must NOT be auto-started by the sweep (no missing-track conclusion)")
      // 审计
      val cancelledEv = audit.filter { case (t, id, _) => t == "cancelled" && id == "n-a" }
      assertEquals(cancelledEv.size, 1, s"exactly one cancelled audit line, got ${audit.map(_._1)}")
      assert(cancelledEv.head._3.contains("source=engine"), s"R2/R7 audit must carry the source: ${cancelledEv.head._3}")
      assert(cancelledEv.head._3.contains("successors awaiting handover: n-b"),
        s"R4 audit summary must name the pruned successors: ${cancelledEv.head._3}")
      val barrierEv = audit.filter { case (t, id, _) => t == "barrier-blocked" && id == "n-b" }
      assertEquals(barrierEv.size, 1, s"R3 immediate barrier alert expected for n-b, got ${audit.map((t, id, _) => (t, id))}")
      assert(barrierEv.head._3.contains("pendingSuccession") && barrierEv.head._3.contains("cause=cancelled"),
        s"R3 alert must explain the held slot + cause: ${barrierEv.head._3}")
      // R1 回流（cancelled 专属文本，captured via the engine-stamped trigger seam）
      assertEquals(texts.size, 1, s"exactly one dispatcher notification for the cancelled node, got ${texts.size}")
      assert(texts.head.contains("已被**取消**") && texts.head.contains("不可重激活"),
        s"engine-side trigger must carry the cancelled-only guidance: ${texts.head.take(200)}")
      assert(texts.head.contains("下游等待者") && texts.head.contains("B(n-b)"),
        "cancelled notification must list the held successor (cancelled side reads pendingSuccession)")
      // R4 可见性：下游被 prune 时补发 nodeUpdated，payload 带 pendingSuccession
      val bFrame = wsFrames.find(j => j.hcursor.get[String]("type").contains("nodeUpdated")
        && j.hcursor.get[String]("nodeId").contains("n-b"))
      assert(bFrame.isDefined, s"R4 visibility: nodeUpdated frame for the pruned successor expected, got ${wsFrames.size} frames")
      assert(bFrame.get.hcursor.get[List[String]]("pendingSuccession").toOption.contains(List("n-a")),
        s"R4 visibility: frame payload must carry pendingSuccession, got ${bFrame.get.noSpaces.take(300)}")
  }

  // ── R5：L3 resume 失败腿 ────────────────────────────────────────

  test("R5: settleFailedHardResume — cancelled node w/o detach gets detach + immediate barrier alert + R1 notify + hard-recovery FAILED audit; repeated call performs no new action") {
    val ws = tempRoot / "ws-l3fail"
    os.makeDir.all(ws)
    val system = ActorSystem(s"cd-l3-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot, new StubLlm().handle)
      triggered <- Ref.of[IO, List[String]](Nil)
      rt <- mountProject("cd-l3fail", ws, system, res, triggered)
      // 被取消的上游（L3 路径**未摘除**——out 仍指下游）+ 带 pendingSuccession 的下游
      _ <- seed(rt.store, NodeDef(id = "n-u", name = "U", agent = "general",
        status = NodeLifecycle.Cancelled, result = Some("cancelled[source=engine]: reason=stuck 25m (L3 hard-recovery: released by TaskStuckWatcher)"),
        sessionRef = Some("node-sess-l3"), out = List(OutEdge("n-d")),
        createdAt = now - 1_800_000L, completedAt = Some(now - 1_800_000L)))
      _ <- seed(rt.store, NodeDef(id = "n-d", name = "D", agent = "general",
        status = NodeLifecycle.Pending, in = List("n-u"), createdAt = now - 1_700_000L))
      _ <- rt.engine.settleFailedHardResume("node-sess-l3")
      u <- node(rt, "n-u")
      d <- node(rt, "n-d")
      audit1 <- readAudit(ws)
      fired1 <- triggered.get
      _ <- rt.engine.settleFailedHardResume("node-sess-l3") // 幂等重放
      fired2 <- triggered.get
      d2 <- node(rt, "n-d")
      audit2 <- readAudit(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(u.out, List(OutEdge.nebula), "R5 failure leg must perform the deferred R4 detach")
      assert(!d.in.contains("n-u") && d.pendingSuccession == List("n-u"),
        s"R5 failure leg must mark the successor: in=${d.in} pendingSuccession=${d.pendingSuccession}")
      val hr = audit1.filter { case (t, id, _) => t == "hard-recovery" && id == "n-u" }
      assertEquals(hr.size, 1, s"exactly one hard-recovery FAILED line expected, got ${audit1.map((t, id, _) => (t, id))}")
      assert(hr.head._3.contains("L3 resume FAILED"), s"R5 failure wording: ${hr.head._3}")
      val bEv = audit1.filter { case (t, id, _) => t == "barrier-blocked" && id == "n-d" }
      assertEquals(bEv.size, 1, s"R5 failure leg must raise the immediate barrier alert, got ${audit1.map((t, id, _) => (t, id))}")
      assert(bEv.head._3.contains("cause=L3-resume-failed"), s"alert must carry the L3 cause: ${bEv.head._3}")
      assertEquals(fired1.size, 1, s"R5 failure leg must notify the dispatcher (R1), got ${fired1.size}")
      // 幂等：动作零新增（重复调用不重复触发/不重复告警；审计行本身按调用追加，见本用例断言）
      assertEquals(fired2.size, 1, "repeated call must not re-notify (notifySentAt dedup)")
      assertEquals(d2.pendingSuccession, List("n-u"), "marker must not duplicate")
      val bEv2 = audit2.filter { case (t, id, _) => t == "barrier-blocked" && id == "n-d" }
      assertEquals(bEv2.size, 1, "repeated call must not re-alert the same barrier")
      val hr2 = audit2.filter { case (t, id, _) => t == "hard-recovery" && id == "n-u" }
      assertEquals(hr2.size, 2, "audit line is appended per call (actions are idempotent; the log is append-only by design)")
  }

  test("R5b: settleFailedHardResume no-op legs — node not cancelled, and unknown session (both silent, zero side effects)") {
    val ws = tempRoot / "ws-l3noop"
    os.makeDir.all(ws)
    val system = ActorSystem(s"cd-l3noop-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot, new StubLlm().handle)
      triggered <- Ref.of[IO, List[String]](Nil)
      rt <- mountProject("cd-l3noop", ws, system, res, triggered)
      // resume 已复活（running）→ 不得摘除/不得告警
      _ <- seed(rt.store, NodeDef(id = "n-live", name = "LIVE", agent = "general",
        status = NodeLifecycle.Running, sessionRef = Some("node-sess-live"),
        out = List(OutEdge("n-down")), createdAt = now - 60_000L))
      _ <- seed(rt.store, NodeDef(id = "n-down", name = "DOWN", agent = "general",
        status = NodeLifecycle.Pending, in = List("n-live"), createdAt = now - 50_000L))
      _ <- rt.engine.settleFailedHardResume("node-sess-live")
      live <- node(rt, "n-live")
      down <- node(rt, "n-down")
      audit <- readAudit(ws)
      // 未知会话（无节点归属）→ 响亮 ERROR 留痕，零动作
      _ <- rt.engine.settleFailedHardResume("node-sess-ghost")
      fired <- triggered.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(live.out, List(OutEdge("n-down")), "non-cancelled node must not be detached")
      assertEquals(down.pendingSuccession, Nil, "no marker for a live upstream")
      assertEquals(audit.filter(_._1 == "hard-recovery"), Nil, "no hard-recovery failure line for the live leg")
      assertEquals(audit.filter(_._1 == "barrier-blocked"), Nil, "no barrier alert while the upstream is alive")
      assertEquals(fired, Nil, "no dispatcher notification in either no-op leg")
  }

  // ── R3 去重 + failed 侧零摘除 ───────────────────────────────────

  test("R3-dedup: a barrier alerted immediately is NOT re-emitted by the periodic sweep (single alert per stall episode); an un-alerted stall still emits mount-stalled (not blanket-silenced)") {
    val ws = tempRoot / "ws-dedup"
    os.makeDir.all(ws)
    val system = ActorSystem(s"cd-dedup-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot, new StubLlm().handle)
      triggered <- Ref.of[IO, List[String]](Nil)
      rt <- mountProject("cd-dedup", ws, system, res, triggered)
      // B：被取消上游（L3 未摘除）→ settleFailedHardResume 走即时告警（checkBarriersNow）
      _ <- seed(rt.store, NodeDef(id = "n-cu", name = "CU", agent = "general",
        status = NodeLifecycle.Cancelled, result = Some("cancelled[source=engine]: reason=x"),
        sessionRef = Some("node-sess-cu"), out = List(OutEdge("n-b")),
        createdAt = now - 900_000L, completedAt = Some(now - 800_000L)))
      _ <- seed(rt.store, NodeDef(id = "n-b", name = "B", agent = "general",
        status = NodeLifecycle.Pending, in = List("n-cu"), createdAt = now - 700_000L))
      // C：failed 上游（deps），**不经**终态写点（直接播种）→ 只有周期回扫能发现
      _ <- seed(rt.store, NodeDef(id = "n-fu", name = "FU", agent = "general",
        status = NodeLifecycle.Failed, result = Some("boom"),
        createdAt = now - 900_000L, completedAt = Some(now - 800_000L)))
      _ <- seed(rt.store, NodeDef(id = "n-c", name = "C", agent = "general",
        status = NodeLifecycle.Pending, deps = List("n-fu"), createdAt = now - 700_000L))
      _ <- rt.engine.settleFailedHardResume("node-sess-cu") // 即时告警 B
      _ <- rt.engine.settleRunnableSweep()
      _ <- rt.engine.settleRunnableSweep()
      audit <- readAudit(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      val bImmediate = audit.count((t, id, _) => t == "barrier-blocked" && id == "n-b")
      val bSweep = audit.count((t, id, _) => t == "mount-stalled" && id == "n-b")
      assertEquals(bImmediate, 1, s"B must have exactly one immediate alert, got $bImmediate")
      assertEquals(bSweep, 0, s"B must NOT be re-emitted by the periodic sweep (single alert per stall), got $bSweep")
      val cSweep = audit.count((t, id, _) => t == "mount-stalled" && id == "n-c")
      assertEquals(cSweep, 1, s"C (no immediate alert was raised) must still be reported by the sweep, got $cSweep")
  }

  test("R3-failed-side + R4-red-line: dead-session convergence to failed raises the immediate barrier alert but performs ZERO detach / ZERO succession marking") {
    val ws = tempRoot / "ws-failed"
    os.makeDir.all(ws)
    val system = ActorSystem(s"cd-failed-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot, new StubLlm().handle)
      triggered <- Ref.of[IO, List[String]](Nil)
      rt <- mountProject("cd-failed", ws, system, res, triggered)
      _ <- seed(rt.store, NodeDef(id = "n-z", name = "Z", agent = "general", task = Some("work-Z"),
        status = NodeLifecycle.Running, out = List(OutEdge("n-w")),
        startedAt = Some(now - 3_600_000L), createdAt = now - 3_600_000L))
      _ <- seed(rt.store, NodeDef(id = "n-w", name = "W", agent = "general", task = Some("work-W"),
        status = NodeLifecycle.Pending, in = List("n-z"), createdAt = now - 600_000L))
      _ <- rt.engine.settleStaleRunningNodes()
      z <- node(rt, "n-z")
      w <- node(rt, "n-w")
      audit <- readAudit(ws)
      fired <- triggered.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(z.status, NodeLifecycle.Failed, "dead session must converge to failed")
      assertEquals(z.out, List(OutEdge("n-w")), "R4 red line: failed side must NOT detach the out edge")
      assert(w.in.contains("n-z"), s"R4 red line: failed side must NOT prune the downstream in mirror, got ${w.in}")
      assertEquals(w.pendingSuccession, Nil, "R4 red line: failed side must NOT write the 待承接 marker")
      val bEv = audit.filter { case (t, id, _) => t == "barrier-blocked" && id == "n-w" }
      assertEquals(bEv.size, 1, s"R3: the failed-side terminal write point must raise the immediate alert, got ${audit.map((t, id, _) => (t, id))}")
      assert(bEv.head._3.contains("cause=failed"), s"alert must carry cause=failed: ${bEv.head._3}")
      // failed 回流仍按既有 failed 文本（本批 failed 通知面零变化）且绝不混入 cancelled 文本
      assertEquals(fired.size, 1, s"failed notify must still fire exactly once (zero regression), got ${fired.size}")
      assert(fired.head.contains("reason=failed") && fired.head.contains("触发 reactivate 重跑"),
        s"failed notification must keep the failed text: ${fired.head.take(200)}")
      assert(!fired.exists(_.contains("已被**取消**")), "cancelled wording must never appear on the failed path")
  }

  // ── R4：NodeEdit 实际变更解除闸门 ────────────────────────────────

  test("R4-unlock: NodeEdit with an actual change clears the 待承接 marker (barrier becomes triggerable again); a no-op edit does not") {
    val ws = tempRoot / "ws-unlock"
    os.makeDir.all(ws)
    val system = ActorSystem(s"cd-unlock-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot, new StubLlm().handle)
      triggered <- Ref.of[IO, List[String]](Nil)
      rt <- mountProject("cd-unlock", ws, system, res, triggered)
      _ <- seed(rt.store, NodeDef(id = "n-up", name = "UP", agent = "general",
        status = NodeLifecycle.Cancelled, result = Some("cancelled[source=engine]: reason=x"),
        createdAt = now - 900_000L, completedAt = Some(now - 800_000L)))
      _ <- seed(rt.store, NodeDef(id = "n-succ", name = "SUCC", agent = "general", task = Some("t-succ"),
        status = NodeLifecycle.Pending, in = Nil, out = List(OutEdge.nebula),
        pendingSuccession = List("n-up"), createdAt = now - 700_000L))
      ctx = nebflow.core.tools.ToolContext(
        projectRoot = ws.toString, sessionId = Some("spec-sid"), rootSessionId = Some("nebula-root"),
        sharedResources = Some(res), actorSystem = Some(system))
      // ① 纯 no-op 编辑（只传 project+nodename）→ 标记保留
      _ <- nebflow.core.tools.NodeEditTool.call(
        Json.obj("project" -> Json.fromString("cd-unlock"), "nodename" -> Json.fromString("SUCC")).asObject.get, ctx)
        .map(_.left.map(_.message)).flatMap {
          case Left(e) => IO.raiseError(new AssertionError(s"no-op NodeEdit failed: $e"))
          case Right(_) => IO.unit
        }
      kept <- node(rt, "n-succ")
      // ② 实际变更（task 改写）→ 标记清除
      _ <- nebflow.core.tools.NodeEditTool.call(
        Json.obj("project" -> Json.fromString("cd-unlock"), "nodename" -> Json.fromString("SUCC"),
          "task" -> Json.fromString("t-succ-v2")).asObject.get, ctx)
        .map(_.left.map(_.message)).flatMap {
          case Left(e) => IO.raiseError(new AssertionError(s"actual-change NodeEdit failed: $e"))
          case Right(_) => IO.unit
        }
      cleared <- node(rt, "n-succ")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(kept.pendingSuccession, List("n-up"), "no-op edit must not unlock the barrier")
      assertEquals(cleared.pendingSuccession, Nil, "actual change must clear the 待承接 marker (handover landed)")
  }

end CancelDeadlockFixSpec
