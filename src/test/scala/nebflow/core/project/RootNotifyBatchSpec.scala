package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import munit.FunSuite
import nebflow.actor.{ActorSystem, Behaviors}
import nebflow.agent.*
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.FileChangeTracker
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}

import scala.concurrent.duration.*

/**
 * root 通道通知打包窗（notifybatch 批 · 实施位 2026-09-18）。
 *
 * 问题（诊断批 `n-45385003` 现读）：同一族节点终态事件在**分发器通道**已有打包窗
 * （`DispatchNotify.NotifyBatch`，5s 滚动窗）而 **root 通道**（`NodeEngine.deliverToNebula`）
 * 逐件 offer、零缓冲 ⇒ 「一条 = 一个 turn」（`batch=1` 178/178）。
 *
 * 修复（作者三决策）：① 生产者侧打包（N 件合成**一条** `ImmediateInput` ⇒ 消费侧
 * `AgentActor.drainHead` **零改动**，2026-09-15 裁定一字未动）；② 分层**只保留一档**
 * ——INTERRUPT/P0 不合并，`failed`/`blocked`/`cancelled` 一并合并、不单列；
 * ③ **不折叠**——无超龄阈值/无折叠摘要行/无 `RootNotifyFoldMs`，只做「打包窗 + 合并注入」。
 *
 * 验收面（诊断 §4.1 A1–A6 / §4.2 R1–R7）：
 *  - A1 窗内 3 件 ⇒ **恰一次**注入且含全部 3 件（并断言窗内**未**逐件注入 = 缓冲真生效）
 *  - A2 单件**零漂移**：开窗单件与关窗单件的文本/header **逐字节相同**（含 `sender` 形态）
 *  - A3 INTERRUPT 不合并：1×P0 + 3×P1 ⇒ 恰 2 次注入，P0 单列**在前**
 *  - A4（**本轮改判为负向断言**，决策③）：超龄件在窗口腿**仍投正文全文**、**不得出现**
 *       折叠摘要行（`历史欠账汇总`）；同时对偶断言「>24h 扫描腿既有 `deliverStaleSummary`
 *       保持现状」（绕过窗口、即时一条汇总 = 零行为改动证据）
 *  - A5 条数上限 + 保序：25 件 + cap=10 ⇒ 3 次注入、每次 ≤10、拼接序 == 到达序；A5b 滚动窗
 *       跨溢出自动续窗（cap=2 × 5 件 ⇒ [2,2,1]）
 *  - A6 不丢不重：digest 的 nodeId 多重集 == 该窗终态集 + 每节点记账 + 再扫零候选
 *  - R1 旧行为基线可复现（关窗 ⇒ 逐条 `batch=1`，修复前形态）· R3 `mergedRatio` 二值
 *       （合并场景 = 1、关窗场景 = 0）· R5 保序反作弊（比较器有判别力）· R7 INTERRUPT 负控
 *
 * 红证（R2 变异验红 / R6 假绿排除）是**过程**：以定点替换把 digest 组装退回逐件 offer，
 * 期望本 spec 的 A1/A3/A5/R3 转红（读数与还原见批报告 + 证据目录）。
 *
 * 接缝：`rootNotifyQuietMs` / `rootNotifyBatchMax`（构造入参，`notifyQuietMs` /
 * `destroyWindowMs` / `stallReNotifyMs` 同款——本工程测试 JVM 下 `sys.props` 写入同进程
 * 读回为空 ⇒ prop 注入口会静默失效，故走构造器）。`Some(0)` = 关窗 = 逐条旧行为。
 */
class RootNotifyBatchSpec extends FunSuite:

  private val originalRoot = PathUtil.dataRoot

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    nebflow.core.LlmLogWriter.setEnabled(true)

  nebflow.core.LlmLogWriter.setEnabled(false)

  private val Hour = 3600_000L

  // ── 夹具 ───────────────────────────────────────────────────────────────────────

  private def node(
      id: String,
      status: String = NodeLifecycle.Completed,
      result: String = "RESULT",
      completedAt: Option[Long] = None
  ): NodeDef =
    NodeDef(
      id = id,
      name = s"node-$id",
      agent = "worker",
      out = List(OutEdge.nebula),
      status = status,
      result = Some(result),
      createdAt = System.currentTimeMillis() - 60_000L,
      completedAt = completedAt.orElse(Some(System.currentTimeMillis())),
      ttlExpireAt = None
    )

  /** fixture：quietMs = 打包窗长（0 = 关窗）；batchMax = 条数上限。
    * 夹具树不删除（临时目录由 OS 回收）——本 spec 的窗长均为毫秒级或关窗，且末尾一律
    * 显式等待/排空，避免删除与晚到写入者竞态（`NebulaDeliveryRedeliverySpec` 头注同源教训）。 */
  private def withFixture(name: String, quietMs: Long, batchMax: Int)(
      body: (FlowMapStore, NodeEngine, Ref[IO, List[AgentCommand]], String) => Unit
  ): Unit =
    val tmp = os.temp.dir(prefix = s"rootnotify-$name")
    PathUtil.setDataRoot(tmp / "data")
    val system = ActorSystem(s"rootnotify-$name")
    try
      val io = for
        _ <- IO(os.makeDir.all(tmp / "ws"))
        store <- FlowMapStore.open("rootnotifyproj", (tmp / "ws").toString)
        dispatcher <- cats.effect.std.Dispatcher.parallel[IO].allocated.map(_._1)
        rateLimiter <- RateLimiter.create()
        tracker <- FileChangeTracker.create(os.pwd.toString)
        fileLocks <- FileLockManager.create
        thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
        modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
        voiceMuted <- Ref.of[IO, Boolean](false)
        llm = new nebflow.shared.LlmHandle[IO]:
          def send(req: nebflow.shared.LlmRequest): IO[nebflow.shared.LlmResponse] =
            IO.raiseError(new RuntimeException("not expected"))
          def sendStream(
              req: nebflow.shared.LlmRequest,
              onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
          ) =
            fs2.Stream(nebflow.shared.StreamChunk.TextDelta("ok"), nebflow.shared.StreamChunk.Done(None, None))
        resources = SharedResources(
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
          historyArchiver = HistoryArchiver.fileSystem(tmp / "archives"),
          fileLockManager = fileLocks,
          sessionModelOverrides = modelOverrides,
          providerRegistry = null,
          healthMonitor = ProviderHealthMonitor(null),
          actorSystem = system,
          subAgentTaskStore = new SubAgentTaskStore(tmp / "subagent-tasks"),
          voiceMutedRef = voiceMuted
        )
        recorded <- Ref.of[IO, List[AgentCommand]](Nil)
        rootSid = s"rootnotify-root-$name"
        rootRef <- system.spawn(recorderBehavior(recorded), s"rootnotify-rec-$name")
        engine = new NodeEngine(
          store,
          system,
          resources,
          _ => IO.unit,
          (tmp / "ws").toString,
          rootSid,
          "rootnotifyproj",
          FeedbackRouter.ModeAuto,
          (_, _, _) => IO.unit,
          // 分发器通道打包窗在源头关闭（本 spec 主题 = root 通道；避免另一种 5s 窗口
          // fiber 干扰读数），root 通道窗由本 spec 显式注入。
          notifyTriggerOverride = Some(_ => IO.unit),
          reportGateHold = Some(false),
          rootNotifyQuietMs = Some(quietMs),
          rootNotifyBatchMax = Some(batchMax)
        )
        _ <- resources.agentRegistry.update(_ + (rootSid -> AgentRecord(rootSid, rootRef, AgentKind.Root, rootSid)))
      yield (store, engine, recorded, rootSid)
      val (store, engine, recorded, rootSid) = io.unsafeRunSync()
      body(store, engine, recorded, rootSid)
    finally
      PathUtil.setDataRoot(originalRoot)
      system.stopAll.attempt.void.unsafeRunSync()

  private def recorderBehavior(recorded: Ref[IO, List[AgentCommand]]): nebflow.actor.Behavior[AgentCommand] =
    lazy val b: nebflow.actor.Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand](msg => recorded.update(_ :+ msg).as(b))
    b

  private def imms(recorded: Ref[IO, List[AgentCommand]]): IO[List[AgentCommand.ImmediateInput]] =
    recorded.get.map(_.collect { case m: AgentCommand.ImmediateInput => m })

  /** 有界轮询等待注入到达（`ref ! ImmediateInput` 是 fire-and-forget，直读有竞态）。 */
  private def awaitImms(
      recorded: Ref[IO, List[AgentCommand]],
      min: Int,
      timeoutMs: Long = 10_000L
  ): IO[List[AgentCommand.ImmediateInput]] =
    def go(deadline: Long): IO[List[AgentCommand.ImmediateInput]] =
      imms(recorded).flatMap { ms =>
        if ms.size >= min || System.currentTimeMillis() >= deadline then IO.pure(ms)
        else IO.sleep(25.millis) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeoutMs)

  // ── 机械读数（判据可核）────────────────────────────────────────────────────────

  /** 分节行：`── [i/N] [status] <nodeName> (<identity>) ──`。 */
  private val SectionRe = """── \[(\d+)/(\d+)\] \[([a-z]+)\] (\S+) \(([^)]+)\) ──""".r

  /** 一条注入件的批量（无分节 = 单件 = 1）。 */
  private def batchSizeOf(text: String): Int =
    val ns = SectionRe.findAllMatchIn(text).toList.map(_.group(2).toInt)
    if ns.isEmpty then 1 else ns.max

  /** 一条注入件承载的 nodeId 序（分节行顺序）。 */
  private def idsOf(text: String): List[String] =
    SectionRe.findAllMatchIn(text).toList.map(_.group(5))

  /** 集合注入件的 nodeId 序（保序：批内 + 批间）。 */
  private def flatIds(ms: List[AgentCommand.ImmediateInput]): List[String] = ms.flatMap(m => idsOf(m.text))

  /** **注入件身份序（跨两种文本形态）** —— A5b 口径：超限溢出后**尾窗只剩 1 件**时，
    * `NodeEngine.flushRootNotify` 走 legacy 原样路径（`case one :: Nil`，`:5943`）⇒ 文本
    * **无分节行**（A2「单件零漂移」的必然结果）⇒ [[flatIds]] 对该件读不到身份（第四轮
    * 实证：5 件读到 4 件、缺 `n-a5b-5`）。故沿用 [[batchSizeOf]] 的同款约定
    * （**无分节 = 单件**）分流取身份：多件批 = 分节行 `identity`；单件 = `sender`
    * （`"<project>/<nodeName>"`，A2 已逐字钉住的既有字段，引擎 `:5806` 单点构造）。
    * `nameToId` 由夹具 `name = s"node-$id"` 归一；缺映射时回落原名 ⇒ 身份对不上即转红
    * （fail-loud，不静默放行）。判据强度与原 `flatIds` 一致：**全件数 + 严格到达序**。 */
  private def flatIdentities(
      ms: List[AgentCommand.ImmediateInput],
      nameToId: Map[String, String]
  ): List[String] =
    ms.flatMap { m =>
      val sec = idsOf(m.text)
      if sec.nonEmpty then sec
      else
        m.sender.toList.map { s =>
          val name = s.split('/').last
          nameToId.getOrElse(name, name)
        }
    }

  /** 保序判据（单点；R5 反作弊用：逆序必须判假）。 */
  private def orderMatches(actual: List[String], expected: List[String]): Boolean = actual == expected

  /** R3 合并有效性指标（二值可算）：batch≥2 的注入占比。 */
  private def mergedRatio(ms: List[AgentCommand.ImmediateInput]): Double =
    if ms.isEmpty then 0.0 else ms.count(m => batchSizeOf(m.text) >= 2).toDouble / ms.size.toDouble

  /** 把 store 里的节点登记（记账面断言用）。 */
  private def seed(store: FlowMapStore, nodes: List[NodeDef]): IO[Unit] =
    store.mutate(s => s.copy(nodes = s.nodes ++ nodes.map(n => n.id -> n).toMap)).void

  // ── A1 ────────────────────────────────────────────────────────────────────────

  test("A1 GREEN: three nodes inside ONE window → exactly one injection carrying all three (no per-item turn)") {
    withFixture("a1", quietMs = 400L, batchMax = 10) { (store, engine, recorded, _) =>
      val nodes = List(node("n-a1-1", result = "A1_BODY_ONE"), node("n-a1-2", result = "A1_BODY_TWO"), node("n-a1-3", result = "A1_BODY_THREE"))
      val io = for
        _ <- seed(store, nodes)
        _ <- nodes.traverse_(n => engine.deliverOutTo(n, OutEdge.NebulaTarget, s"${n.result.get}"))
        // 窗内读数：**未**逐件注入（证明缓冲真生效，而非「恰好只发了一次」）
        pending <- engine.rootNotifyPendingCount
        before <- imms(recorded)
        ms <- awaitImms(recorded, min = 1)
        _ <- IO.sleep(600.millis) // 窗口已过：不得再冒出第二/第三条
        after <- imms(recorded)
        marked <- store.snapshot.map(_.nodes.filter(n => nodes.exists(_.id == n._1)).values.map(_.nebulaDeliveredAt.isDefined))
      yield (pending, before, ms, after, marked)
      val (pending, before, ms, after, marked) = io.unsafeRunSync()
      println(s"[RootNotifyBatchSpec] A1 DIAG pendingAtWindow=$pending before=$before injections=${after.size} batchSizes=${after.map(m => batchSizeOf(m.text))} texts=${after.map(_.text.take(80))}")
      assertEquals(clue(pending), 3, "all three items must sit in the batch buffer inside the window")
      assertEquals(clue(before.size), 0, "inside the window NOTHING may be injected one-by-one")
      assertEquals(clue(after.size), 1, "exactly ONE injection for the window (this is the whole point)")
      val one = after.head
      assertEquals(clue(one.source), Some("node"), "node-source bubble semantics preserved")
      assertEquals(clue(batchSizeOf(one.text)), 3, "the merged injection must report batch=3")
      List("n-a1-1", "n-a1-2", "n-a1-3").foreach(id => assert(clue(one.text).contains(id), s"digest must carry nodeId $id"))
      assertEquals(clue(idsOf(one.text)), List("n-a1-1", "n-a1-2", "n-a1-3"), "FIFO order inside the batch")
      assertEquals(clue(mergedRatio(after)), 1.0, "R3: mergedRatio == 1 for the burst")
      assert(clue(marked).forall(identity), "every merged node must be ledger-marked after the flush")
      assertEquals(clue(ms.size), 1)
    }
  }

  // ── A2 ────────────────────────────────────────────────────────────────────────

  test("A2 GREEN: single item zero drift — windowed single is BYTE-IDENTICAL to the window-off (legacy) single") {
    def capture(quiet: Long): AgentCommand.ImmediateInput =
      var out: Option[AgentCommand.ImmediateInput] = None
      withFixture(s"a2-$quiet", quietMs = quiet, batchMax = 10) { (store, engine, recorded, _) =>
        val n = node("n-a2", result = "A2_RESULT_BODY")
        val io = for
          _ <- seed(store, List(n))
          _ <- engine.deliverOutTo(n, OutEdge.NebulaTarget, "A2_RESULT_BODY")
          ms <- awaitImms(recorded, min = 1)
          _ <- IO.sleep(400.millis)
          all <- imms(recorded)
        yield
          assertEquals(clue(all.size), 1, s"a lone item must produce exactly one injection (quiet=$quiet)")
          out = Some(all.head)
        io.unsafeRunSync()
      }
      out.get

    val windowed = capture(400L)
    val legacy = capture(0L)
    assertEquals(clue(windowed.text), legacy.text, "single-item TEXT must be byte-identical (zero drift)")
    assertEquals(
      clue(windowed.text),
      "[Node 'node-n-a2' completed]\nA2_RESULT_BODY",
      "single-item text must be TODAY's literal shape"
    )
    assertEquals(clue(windowed.source), legacy.source, "source byte-identical")
    assertEquals(clue(windowed.source), Some("node"))
    assertEquals(clue(windowed.eventType), legacy.eventType, "eventType (header STATE) byte-identical")
    assertEquals(clue(windowed.eventType), Some("completed"))
    assertEquals(clue(windowed.sender), legacy.sender, "sender (header SUBJECT) byte-identical")
    assertEquals(clue(windowed.sender), Some("rootnotifyproj/node-n-a2"))
    assertEquals(clue(batchSizeOf(windowed.text)), 1, "lone item = batch=1 (no batch header)")
  }

  // ── A3 + R7 ───────────────────────────────────────────────────────────────────

  test("A3 GREEN: INTERRUPT (P0) is never merged — 1×P0 + 3×P1 ⇒ exactly 2 injections, P0 alone and FIRST") {
    withFixture("a3", quietMs = 400L, batchMax = 10) { (store, engine, recorded, _) =>
      val nodes = List(node("n-a3-1"), node("n-a3-2"), node("n-a3-3"))
      val io = for
        _ <- seed(store, nodes)
        _ <- engine.enqueueRootNotify("[Node 'p0-alpha' interrupt]\nP0_INTERRUPT_BODY", "p0-alpha", "interrupt", Some("n-p0"))
        _ <- nodes.traverse_(n => engine.deliverOutTo(n, OutEdge.NebulaTarget, "P1 body"))
        ms <- awaitImms(recorded, min = 2)
        _ <- IO.sleep(600.millis)
        after <- imms(recorded)
      yield after
      val after = io.unsafeRunSync()
      println(s"[RootNotifyBatchSpec] A3 DIAG injections=${after.size} eventTypes=${after.map(_.eventType)} batchSizes=${after.map(m => batchSizeOf(m.text))}")
      assertEquals(clue(after.size), 2, "P0 (immediate) + P1 (batched) = exactly two injections")
      val p0 = after.head
      assertEquals(clue(p0.eventType), Some("interrupt"), "P0 rides the interrupt header")
      assert(clue(p0.text).contains("P0_INTERRUPT_BODY"), "P0 body delivered verbatim")
      assertEquals(clue(batchSizeOf(p0.text)), 1, "P0 is a single-column injection (never merged)")
      assert(!clue(p0.text).contains("n-a3-1"), "P0 must not absorb P1 members")
      val batch = after(1)
      assertEquals(clue(batchSizeOf(batch.text)), 3, "P1 members batch together")
      assert(!clue(batch.text).contains("p0-alpha"), "P1 batch must not absorb the INTERRUPT item")
    }
  }

  test("R7 GREEN (negative control): a lone INTERRUPT bypasses the buffer entirely and is never absorbed") {
    withFixture("r7", quietMs = 400L, batchMax = 10) { (store, engine, recorded, _) =>
      val io = for
        _ <- engine.enqueueRootNotify("[Node 'p0-solo' interrupt]\nR7_INTERRUPT", "p0-solo", "interrupt", Some("n-r7"))
        pending <- engine.rootNotifyPendingCount
        ms <- awaitImms(recorded, min = 1)
        _ <- IO.sleep(600.millis)
        after <- imms(recorded)
      yield (pending, ms, after)
      val (pending, ms, after) = io.unsafeRunSync()
      assertEquals(clue(pending), 0, "INTERRUPT must NOT enter the batch buffer")
      assertEquals(clue(after.size), 1, "exactly one injection, no window-driven duplicate")
      assertEquals(clue(batchSizeOf(after.head.text)), 1)
      assertEquals(clue(after.head.eventType), Some("interrupt"))
      assert(after.forall(m => batchSizeOf(m.text) < 2), "no batch≥2 injection may exist on the INTERRUPT path")
      assertEquals(clue(ms.size), 1)
    }
  }

  // ── A4（决策③ 改判为负向断言）──────────────────────────────────────────────────

  test("A4 GREEN (negative, decision ③ no folding): aged items ride the window with FULL text — no summary line") {
    withFixture("a4", quietMs = 400L, batchMax = 10) { (store, engine, recorded, _) =>
      val long1 = "A4_AGED_BODY_ONE_" + ("x" * 400)
      val long2 = "A4_AGED_BODY_TWO_" + ("y" * 400)
      val aged = List(
        node("n-a4-1", result = long1, completedAt = Some(System.currentTimeMillis() - 30 * Hour)),
        node("n-a4-2", result = long2, completedAt = Some(System.currentTimeMillis() - 30 * Hour))
      )
      val io = for
        _ <- seed(store, aged)
        _ <- aged.traverse_(n => engine.enqueueRootNotify(s"[Node '${n.name}' completed]\n${n.result.get}", n.name, "completed", Some(n.id)))
        ms <- awaitImms(recorded, min = 1)
        _ <- IO.sleep(600.millis)
        after <- imms(recorded)
      yield after
      val after = io.unsafeRunSync()
      assertEquals(clue(after.size), 1, "aged items are NOT individually re-injected (window leg)")
      val one = after.head
      assert(!clue(one.text).contains("历史欠账汇总"), "🔴 decision ③: the window leg must NEVER emit a folding/summary line")
      assert(clue(one.text).contains(long1), "aged item #1 keeps its FULL body (merge ≠ summarise ≠ discard)")
      assert(clue(one.text).contains(long2), "aged item #2 keeps its FULL body")
      assertEquals(clue(batchSizeOf(one.text)), 2)

      // 决策③ 的参数面负向断言：**不得存在** RootNotifyFoldMs（整体不做的折叠机制参数）
      val allDefaultsMembers = nebflow.shared.Defaults.getClass.getMethods.map(_.getName).toList
      val foldNames = allDefaultsMembers.filter(_.contains("RootNotify"))
      println(s"[RootNotifyBatchSpec] A4 DIAG Defaults RootNotify* members=$foldNames")
      assert(
        !allDefaultsMembers.exists(_.toLowerCase.contains("rootnotifyfold")),
        s"decision ③: no folding parameter may exist (got ${allDefaultsMembers.filter(_.toLowerCase.contains("rootnotify"))})"
      )
      assert(
        foldNames.contains("RootNotifyQuietMs") && foldNames.contains("RootNotifyBatchMax"),
        s"the two batching parameters must exist (got $foldNames)"
      )
    }
  }

  test("A4b GREEN (counterpart): the >24h stale scan leg is UNCHANGED — still one immediate merged summary") {
    withFixture("a4b", quietMs = 30_000L, batchMax = 10) { (store, engine, recorded, _) =>
      val now = System.currentTimeMillis()
      val stale = List(
        node("n-a4b-1", result = "STALE_ONE", completedAt = Some(now - 25 * Hour)),
        node("n-a4b-2", result = "STALE_TWO", completedAt = Some(now - 25 * Hour))
      )
      val io = for
        _ <- seed(store, stale)
        n <- engine.redeliverUnconsumedNebulaResults()
        // 长窗（30s）+ 零等待：既有 deliverStaleSummary 不经过打包窗 ⇒ 即时一条汇总
        ms <- imms(recorded)
        pending <- engine.rootNotifyPendingCount
      yield (n, ms, pending)
      val (n, ms, pending) = io.unsafeRunSync()
      assertEquals(clue(n), 2, "both stale debts handled")
      assertEquals(clue(ms.size), 1, "stale leg keeps its own single merged summary (zero behaviour change)")
      assert(clue(ms.head.text).contains("历史欠账汇总补投"), "existing deliverStaleSummary rendering verbatim")
      assert(clue(ms.head.text).contains("STALE_ONE") && clue(ms.head.text).contains("STALE_TWO"))
      assertEquals(clue(pending), 0, "the stale leg does not ride the batching window")
    }
  }

  // ── A5 / R5 ───────────────────────────────────────────────────────────────────

  test("A5 GREEN: cap + order — 25 items, cap=10 ⇒ 3 injections (10/10/5), concatenated order == arrival order") {
    withFixture("a5", quietMs = 30_000L, batchMax = 10) { (store, engine, recorded, _) =>
      val ids = (0 until 25).toList.map(i => f"n-a5-$i%02d")
      val nodes = ids.map(id => node(id, result = s"body-$id"))
      val io = for
        _ <- seed(store, nodes)
        _ <- nodes.traverse_(n => engine.enqueueRootNotify(s"[Node '${n.name}' completed]\n${n.result.get}", n.name, "completed", Some(n.id)))
        pending <- engine.rootNotifyPendingCount
        armed <- engine.rootNotifyWindowArmed
        _ <- engine.flushRootNotify()
        _ <- engine.flushRootNotify()
        _ <- engine.flushRootNotify()
        ms <- awaitImms(recorded, min = 3)
        _ <- IO.sleep(300.millis)
        after <- imms(recorded)
        pendingAfter <- engine.rootNotifyPendingCount
        armedAfter <- engine.rootNotifyWindowArmed
      yield (pending, armed, ms, after, pendingAfter, armedAfter)
      val (pending, armed, ms, after, pendingAfter, armedAfter) = io.unsafeRunSync()
      println(s"[RootNotifyBatchSpec] A5 DIAG injections=${after.size} batchSizes=${after.map(m => batchSizeOf(m.text))} pendingBefore=$pending pendingAfter=$pendingAfter")
      assertEquals(clue(pending), 25, "all 25 queued (buffer holds the overflow — decision ③: no folding/downgrade)")
      assertEquals(clue(armed), true, "the rolling window is armed by the first arrival (不随新件延长)")
      assertEquals(clue(after.size), 3, "25 items at cap=10 ⇒ exactly 3 injections")
      assertEquals(clue(after.map(m => batchSizeOf(m.text))), List(10, 10, 5), "each injection ≤ cap, FIFO")
      assertEquals(clue(flatIds(after)), ids, "R5: concatenated nodeId order == arrival order")
      assert(clue(orderMatches(flatIds(after), ids)), "R5 order predicate")
      assertEquals(clue(orderMatches(flatIds(after).reverse, ids)), false, "R5 anti-cheat: the comparator must REJECT reversed order")
      assertEquals(clue(pendingAfter), 0, "buffer fully drained")
      assertEquals(clue(armedAfter), false, "window disarmed once the buffer is empty")

      // R3：合并场景 ratio = 1（全部 batch≥2）
      assertEquals(clue(mergedRatio(after)), 1.0)
      assertEquals(clue(ms.size), 3)
    }
  }

  test("A5b GREEN: rolling window auto-arms across cap overflow — 5 items, cap=2 ⇒ [2,2,1] without explicit flush") {
    withFixture("a5b", quietMs = 250L, batchMax = 2) { (store, engine, recorded, _) =>
      val ids = List("n-a5b-1", "n-a5b-2", "n-a5b-3", "n-a5b-4", "n-a5b-5")
      val nodes = ids.map(id => node(id, result = s"body-$id"))
      val io = for
        _ <- seed(store, nodes)
        _ <- nodes.traverse_(n => engine.deliverOutTo(n, OutEdge.NebulaTarget, s"body-${n.id}"))
        ms <- awaitImms(recorded, min = 3)
        _ <- IO.sleep(600.millis)
        after <- imms(recorded)
        pending <- engine.rootNotifyPendingCount
      yield (ms, after, pending)
      val (ms, after, pending) = io.unsafeRunSync()
      // A5b 身份序判据（第五轮 · 作者 A5b 裁定候选①-(ii)）：末窗溢出到 **1 件** ⇒ 走 legacy
      // 原样路径、文本**无分节行**（A2 单件零漂移）⇒ 身份按 `batchSizeOf` 同款约定分流取
      // （多件批 = 分节行 identity；单件 = `sender`）。判据仍是**全 5 件 + 严格到达序**，
      // 零位置假设（不写死「尾窗必为单件」的窗口切分算术 ⇒ 切分变更不假红/不漏判）。
      val nameToId = ids.map(id => s"node-$id" -> id).toMap
      println(s"[RootNotifyBatchSpec] A5b DIAG injections=${after.size} batchSizes=${after.map(m => batchSizeOf(m.text))} identities=${flatIdentities(after, nameToId)}")
      assertEquals(clue(after.size), 3, "cap=2 × 5 items ⇒ 3 window-driven injections")
      assertEquals(clue(after.map(m => batchSizeOf(m.text))), List(2, 2, 1))
      assertEquals(
        clue(flatIdentities(after, nameToId)),
        ids,
        "order preserved across windows (含末窗 legacy 单件：无分节行 ⇒ 身份走 sender)"
      )
      assertEquals(clue(pending), 0)
      assertEquals(clue(ms.size), 3)
    }
  }

  // ── A6 ────────────────────────────────────────────────────────────────────────

  test("A6 GREEN: no loss / no dup — nodeId multiset equality, every node marked, follow-up scan finds nothing") {
    withFixture("a6", quietMs = 300L, batchMax = 10) { (store, engine, recorded, _) =>
      val mixed = List(
        ("n-a6-1", NodeLifecycle.Completed, "BODY_1"),
        ("n-a6-2", NodeLifecycle.Completed, "BODY_2"),
        ("n-a6-3", NodeLifecycle.Completed, "BODY_3"),
        ("n-a6-4", NodeLifecycle.Failed, "ERR_4"),
        ("n-a6-5", NodeLifecycle.Blocked, "BLOCKED_5"),
        ("n-a6-6", NodeLifecycle.Cancelled, "CANCELLED_6")
      )
      val nodes = mixed.map((id, st, body) => node(id, status = st, result = body))
      val io = for
        _ <- seed(store, nodes)
        _ <- mixed.traverse_((id, st, body) =>
          engine.enqueueRootNotify(s"[Node 'node-$id' $st]\n$body", s"node-$id", st, Some(id)))
        ms <- awaitImms(recorded, min = 1)
        _ <- IO.sleep(600.millis)
        after <- imms(recorded)
        marked <- store.snapshot.map(_.nodes.values.filter(n => mixed.exists(_._1 == n.id)).map(n => n.id -> n.nebulaDeliveredAt.isDefined).toMap)
        rescan <- engine.redeliverUnconsumedNebulaResults()
        _ <- IO.sleep(200.millis)
        afterRescan <- imms(recorded)
        leftover <- engine.rootNotifyPendingCount
      yield (after, marked, rescan, afterRescan, leftover)
      val (after, marked, rescan, afterRescan, leftover) = io.unsafeRunSync()
      assertEquals(clue(after.size), 1, "decision ②: exception classes ride the SAME window (not listed separately)")
      assertEquals(clue(flatIds(after)).sorted, mixed.map(_._1).sorted, "multiset equality (no loss, no dup)")
      assertEquals(clue(flatIds(after)), mixed.map(_._1), "arrival order preserved")
      assertEquals(clue(after.head.eventType), Some(NodeLifecycle.Failed), "mixed batch rides the strongest header (failed)")
      mixed.foreach((id, _, body) => assert(clue(after.head.text).contains(body), s"full body of $id must survive the merge"))
      assertEquals(clue(marked.values.count(identity)), mixed.size, s"every node marked exactly once: $marked")
      assertEquals(clue(rescan), 0, "nothing left unconsumed ⇒ the ledger was written for every merged item")
      assertEquals(clue(afterRescan.size), 1, "the follow-up scan must NOT re-inject (no duplicate)")
      assertEquals(clue(leftover), 0, "buffer empty")
      assertEquals(clue(mergedRatio(after)), 1.0)
    }
  }

  // ── R1 / R3 ───────────────────────────────────────────────────────────────────

  test("R1 GREEN: window OFF reproduces the pre-fix baseline — three individual batch=1 injections") {
    withFixture("r1", quietMs = 0L, batchMax = 10) { (store, engine, recorded, _) =>
      val nodes = List(node("n-r1-1", result = "R1_ONE"), node("n-r1-2", result = "R1_TWO"), node("n-r1-3", result = "R1_THREE"))
      val io = for
        _ <- seed(store, nodes)
        _ <- nodes.traverse_(n => engine.deliverOutTo(n, OutEdge.NebulaTarget, s"${n.result.get}"))
        ms <- awaitImms(recorded, min = 3)
        _ <- IO.sleep(400.millis)
        after <- imms(recorded)
        pending <- engine.rootNotifyPendingCount
      yield (ms, after, pending)
      val (ms, after, pending) = io.unsafeRunSync()
      println(s"[RootNotifyBatchSpec] R1 DIAG injections=${after.size} batchSizes=${after.map(m => batchSizeOf(m.text))}")
      assertEquals(clue(after.size), 3, "window off ⇒ per-item behaviour (the 178/178 batch=1 baseline)")
      assertEquals(clue(after.map(m => batchSizeOf(m.text))), List(1, 1, 1), "every injection is batch=1")
      assertEquals(clue(after.map(_.text)), List(
        "[Node 'node-n-r1-1' completed]\nR1_ONE",
        "[Node 'node-n-r1-2' completed]\nR1_TWO",
        "[Node 'node-n-r1-3' completed]\nR1_THREE"
      ), "per-item text byte-identical to today's shape")
      assertEquals(clue(pending), 0, "nothing buffered when the window is off")
      assertEquals(clue(mergedRatio(after)), 0.0, "R3: mergedRatio == 0 on the pre-fix path (binary metric)")
      assertEquals(clue(ms.size), 3)
    }
  }

  // ── R4 / R5：反作弊（合并 ≠ 丢弃 / 保序有判别力）──────────────────────────────

  test("R4/R5 GREEN: merge is not discard — merged items keep name+id+status+full body, order comparator bites") {
    withFixture("r4", quietMs = 300L, batchMax = 10) { (store, engine, recorded, _) =>
      val ids = List("n-r4-1", "n-r4-2", "n-r4-3")
      val bodies = ids.map(id => s"FULL_BODY_OF_$id" + ("z" * 200))
      val nodes = ids.zip(bodies).map((id, b) => node(id, result = b))
      val io = for
        _ <- seed(store, nodes)
        _ <- ids.zip(bodies).traverse_((id, b) => engine.enqueueRootNotify(s"[Node 'node-$id' completed]\n$b", s"node-$id", "completed", Some(id)))
        ms <- awaitImms(recorded, min = 1)
        _ <- IO.sleep(600.millis)
        after <- imms(recorded)
      yield after
      val after = io.unsafeRunSync()
      assertEquals(clue(after.size), 1)
      val t = after.head.text
      ids.zip(bodies).foreach { (id, b) =>
        assert(clue(t).contains(id), s"nodeId $id present (mechanically checkable identity)")
        assert(clue(t).contains(b), s"full body of $id preserved (merge ≠ summarise ≠ discard)")
      }
      ids.foreach(id => assert(clue(t).contains(s"($id)"), "section header carries the nodeId"))
      assertEquals(clue(idsOf(t)), ids, "section order == arrival order")
      assert(!clue(orderMatches(ids.reverse, ids)), "the order predicate must reject a shuffled/reversed digest")
      assert(t.contains("本批 3 件"), "batch header states the batch size (mechanical read of `batch=N`)")
    }
  }

end RootNotifyBatchSpec
