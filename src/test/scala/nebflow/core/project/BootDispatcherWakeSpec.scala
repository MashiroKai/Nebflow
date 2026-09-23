package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.parser.parse as jsonParse
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentLibrary, SharedResources}
import nebflow.core.PathUtil
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * 宿主启动自动重入验收（boot-wake 批 2026-09-13；方案件 A 档 A1「控制面唤醒腿」）。
 *
 * 设计正本：`~/.nebflow/docs/Nebflow/20260913_075859_session-resume-defect-plan__chain-n-18f8a200.md`
 * 用例编号与验收点一一对应（逐条给判红/负控读数）：
 *  - A0 路径口径：`.nebflow` 目录解析与 store 同源（唤醒读到的就是 store 写的）。
 *  - A1 主路径 + 触发面：唯一入口 = boot 链调用；负控 = 非 boot 腿（扫描腿 ×5 +
 *      真实节点会话启动）零唤醒，boot 入口恰一次（「不重启零触发」）。
 *  - A2 幂等/防重：同 boot 重复调用恰一次；**落盘标记**跨调用路径去重（进程内 Ref 为空
 *      也拦住）；连续 5 次 boot（重启风暴/看门狗拉起形态）线性各一次、绝不累积/循环。
 *  - A3 落盘事实重建：清单由磁盘 flow-map.json + tasks/ + results/ + transcript 存在性派生；
 *      **内存态陈旧时以磁盘为准**（反向对照）+ 纯 `fromJson` 零 store/engine/registry 重建。
 *  - A4 失败降级（显式记录 + 跳过；禁静默成功、禁重试风暴）：项目未挂载 / 目标会话已终态 /
 *      上游缺轨 / 落盘事实不可读 / 触发链失败（一次尝试零重试）。
 *  - A5 可观测 + 可关：日志行 + `dispatcher-wake` 事件（谁/何时/结果）+ 落盘标记清单；
 *      开关关闭 = 零动作零残留（对照读数）；默认开 = 方案默认 + 作者裁定同向。
 *  - A6 假阳负控：零工作项目 / 合法等待（有 running 上游）/ 未到 60 s 档 ⇒ 不唤醒；
 *      终态节点永不入选（R4 陷阱：completed 节点 transcript 仍在盘）。
 *  - A7 上限：单 boot 项目数上限（超出记 cap-exceeded 跳过）。
 */
class BootDispatcherWakeSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  private val root: os.Path = os.pwd / "target" / "test-boot-dispatcher-wake"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(root)
  os.remove.all(root)
  os.makeDir.all(root / "agents" / "general")

  os.write.over(
    root / "agents" / "general" / "agent.json",
    """{"name":"general","description":"general executor","tools":[],"category":"standalone"}"""
  )
  os.write.over(root / "agents" / "general" / "system.md", "# general\n")
  os.makeDir.all(root / "sessions")
  os.makeDir.all(root / "projects")

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  private val nowMs = System.currentTimeMillis()
  private val hourAgo = nowMs - 3_600_000L
  private val tenMinAgo = nowMs - 600_000L

  // ── 基建（BootCrashRecoverySpec 同款骨架）──────────────────────────────

  private def mkResources(system: ActorSystem, llm: LlmHandle[IO]): IO[SharedResources] =
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
      sessionStore = SessionStore(root / "sessions", root / "tasks"),
      projectRoot = os.pwd,
      thinkingConfigRef = thinkingRef,
      rateLimiter = rateLimiter,
      fileChangeTracker = tracker,
      contextWindow = 100_000,
      agentLibrary = new AgentLibrary(root / "agents"),
      taskStore = nebflow.core.task.FileTaskStore,
      historyArchiver = null,
      fileLockManager = fileLocks,
      sessionModelOverrides = modelOverrides,
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = null,
      voiceMutedRef = voiceMuted
    )

  private class ScriptLlm(respond: String => String):
    val requests: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)

    def handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(
        req: LlmRequest,
        onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]]
      ): Stream[IO, StreamChunk] =
        val last = req.messages.lastOption.map(_.textContent).getOrElse("")
        Stream
          .eval(requests.update(_ :+ last))
          .flatMap(_ => Stream(StreamChunk.TextDelta(respond(last)), StreamChunk.Done(None, None)))

  private def pdOf(name: String, ws: os.Path): ProjectDef =
    ProjectDef(name = name, workspace = ws.toString, agentFile = (ws / "AGENTS.md").toString, createdAt = nowMs)

  private def dirOf(pd: ProjectDef): os.Path = BootDispatcherWake.nebflowDir(pd)

  private def mountRuntime(
    name: String,
    ws: os.Path,
    system: ActorSystem,
    res: SharedResources
  ): IO[ProjectRuntime] =
    for
      store <- FlowMapStore.open(name, ws.toString)
      engine = new NodeEngine(
        store,
        system,
        res,
        wsSendFn = (_: Json) => IO.unit,
        workspace = ws.toString,
        rootSessionId = "nebula-root",
        projectName = name,
        emitEvent = (_, _, _) => IO.unit,
        reportGateHold = Some(false)
      )
      pd = pdOf(name, ws)
      rt = ProjectRuntime(pd, store, engine, system, res, None)
      _ <- ProjectRuntimeRegistry.register(rt)
    yield rt

  private def node(
    id: String,
    name: String,
    status: String,
    in: List[String] = Nil,
    deps: List[String] = Nil,
    out: List[OutEdge] = List(OutEdge.nebula),
    task: Option[String] = None,
    result: Option[String] = None,
    sessionRef: Option[String] = None,
    pendingSuccession: List[String] = Nil,
    blockCount: Int = 0,
    blockedFeedback: Option[BlockedFeedback] = None,
    completedAt: Option[Long] = None,
    destroyAt: Option[Long] = None,
    createdAt: Long = hourAgo
  ): NodeDef =
    NodeDef(
      id = id,
      name = name,
      agent = "general",
      task = task,
      in = in,
      deps = deps,
      out = out,
      status = status,
      createdAt = createdAt,
      completedAt = completedAt,
      result = result,
      sessionRef = sessionRef,
      pendingSuccession = pendingSuccession,
      blockCount = blockCount,
      blockedFeedback = blockedFeedback,
      destroyAt = destroyAt,
      deliveredTo = Nil
    )

  /** 落盘 flow-map.json（**直接写盘**——唤醒腿的判据面就是它，不经 store 内存态）。 */
  private def seedFlowMap(pd: ProjectDef, nodes: List[NodeDef]): IO[Unit] =
    IO.blocking {
      os.makeDir.all(dirOf(pd))
      os.write.over(
        dirOf(pd) / BootWakeInventory.FileName,
        FlowMapState(project = pd.name, updatedAt = nowMs, nodes = nodes.map(n => n.id -> n).toMap).asJson.noSpaces
      )
    }

  private def seedFile(pd: ProjectDef, sub: String, name: String, content: String): IO[Unit] =
    IO.blocking(os.write.over(dirOf(pd) / sub / name, content, createFolders = true))

  private def seedTranscript(sid: String): IO[Unit] =
    IO.blocking(os.write.over(root / "sessions" / s"$sid.json", """[{"role":"user","content":"前情"}]"""))

  private def readEvents(pd: ProjectDef): IO[List[String]] =
    IO.blocking {
      val f = dirOf(pd) / FlowMapEventLog.FileName
      if os.exists(f) then os.read(f).linesIterator.toList.filter(_.nonEmpty) else Nil
    }

  private def readMarker(pd: ProjectDef): IO[Option[Json]] =
    IO.blocking {
      val f = BootDispatcherWake.markerPath(pd)
      if os.exists(f) then jsonParse(os.read(f)).toOption else None
    }

  private def diskState(pd: ProjectDef): IO[FlowMapState] =
    IO.blocking(os.read(dirOf(pd) / BootWakeInventory.FileName)).map { raw =>
      jsonParse(raw)
        .flatMap(_.as[FlowMapState])
        .fold(e => throw new AssertionError(s"bad flow-map.json: $e"), identity)
    }

  private def recorder: IO[(Ref[IO, List[String]], Option[String => IO[Unit]])] =
    Ref.of[IO, List[String]](Nil).map(r => (r, Some((t: String) => r.update(_ :+ t))))

  private def wake(
    pd: ProjectDef,
    bootId: String,
    trig: Option[String => IO[Unit]]
  ): IO[BootDispatcherWake.WakeReport] =
    BootDispatcherWake.wakeAll(trigger = trig, bootId = bootId, projects = Some(List(pd)), nowMs = () => nowMs)

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 50.millis)(cond: IO[Boolean]): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError("waitUntil: condition not met in time"))
          else IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  /** 清单条目行判据（条目首行形如 `- [B4] n-block '...' status=...`）。 */
  private def isItemLine(l: String, id: String): Boolean =
    l.trim.startsWith("- [") && l.contains(id)

  private def hasItemLine(text: String, id: String): Boolean =
    text.linesIterator.exists(l => isItemLine(l, id))

  // ══ A0 路径口径 ═══════════════════════════════════════════════════════

  test("A0: wake reads the very dir the store writes (one path idiom, no divergence)") {
    val ws = root / "ws-a0"
    val pd = pdOf("a0-path", ws)
    for
      _ <- IO(os.makeDir.all(ws))
      store <- FlowMapStore.open("a0-path", ws.toString)
      _ <- store.mutate(s => s.copy(nodes = Map("n-a0" -> node("n-a0", "x", NodeLifecycle.Blocked))))
      exists <- IO(os.exists(dirOf(pd) / BootWakeInventory.FileName))
      inv <- BootWakeInventory.fromDisk(dirOf(pd), pd.name, nowMs)
    yield
      assert(
        exists,
        s"store-written flow-map.json must be at ${dirOf(pd) / BootWakeInventory.FileName} (wake reader and store writer share one path expression)"
      )
      assert(inv.isRight, s"wake inventory must read the store-written file, got $inv")
      assertEquals(inv.toOption.map(_.items.map(_.nodeId)), Some(List("n-a0")))
  }

  // ══ A1 主路径：落盘事实清单 → 唤醒 + 事件 + 标记 ═══════════════════════

  test("A1: boot wake fires once with a disk-derived B-format manifest (event + marker + zero node write)") {
    val ws = root / "ws-a1"
    val pd = pdOf("a1-main", ws)
    val nodes = List(
      // B4 待裁决 blocked（带 blockCount + 结构化反馈）
      node(
        "n-block",
        "gate-block",
        NodeLifecycle.Blocked,
        blockCount = 2,
        completedAt = Some(tenMinAgo),
        blockedFeedback = Some(BlockedFeedback("upstream-incomplete", "waiting on X", "hand over")),
        task = Some("守门节点：等待上游交付")
      ),
      // B2 待承接（pendingSuccession 非空；引用的上游已不在图 = 悬空）
      node(
        "n-succ",
        "collect",
        NodeLifecycle.Pending,
        in = List("n-block"),
        pendingSuccession = List("n-gone"),
        task = Some("收口节点")
      ),
      // B3 死 barrier（上游全终态，且含缺轨 failed 上游）
      node("n-bar", "bar", NodeLifecycle.Pending, in = List("n-fail")),
      node("n-fail", "up-fail", NodeLifecycle.Failed, completedAt = Some(tenMinAgo), result = Some("boom")),
      // B1 落单 running（目标会话 transcript 缺失 = 不可续）
      node("n-loose", "loose", NodeLifecycle.Running, sessionRef = Some("node-lost")),
      // 终态节点（R4 陷阱：transcript 仍在盘 —— 永不入选）
      node(
        "n-done",
        "done",
        NodeLifecycle.Completed,
        sessionRef = Some("node-live"),
        completedAt = Some(tenMinAgo),
        result = Some("ALL DONE")
      )
    )
    val system = ActorSystem(s"bdw-a1-${scala.util.Random.nextInt(1000000)}")
    for
      _ <- IO(os.makeDir.all(ws))
      _ <- seedFlowMap(pd, nodes)
      _ <- seedFile(pd, FlowMapStore.TasksDirName, "n-block.md", "守门节点任务书全文\n")
      _ <- seedFile(pd, FlowMapStore.ResultsDirName, "n-block.md", "blocked 现场：上游未交付\n")
      // transcript 存在（completed 节点 n-done 的会话文件仍在盘 —— R4 陷阱夹具）
      _ <- seedTranscript("node-live")
      res <- mkResources(system, new ScriptLlm(_ => "x").handle)
      _ <- mountRuntime("a1-main", ws, system, res)
      before <- diskState(pd)
      (trig, trigger) <- recorder
      report <- wake(pd, "boot-A1", trigger)
      texts <- trig.get
      after <- diskState(pd)
      events <- readEvents(pd)
      marker <- readMarker(pd)
      // B 档清单样例落盘（证据件：清单正文的逐字渲染，供人工/复核读取）
      _ <- IO(os.write.over(root / "manifest-sample.txt", texts.head))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(report.woken, 1, s"exactly one project woken, got ${report.summary}")
      assertEquals(texts.size, 1, "exactly one TriggerDispatcher wake for this boot/project")
      val t = texts.head
      // 零自动行为声明（方案判红：清单缺该声明 = 红）
      assert(t.contains("仅供处置，不产生任何自动行为"), s"manifest must carry the zero-auto-action declaration\n$t")
      // 四档逐条落位
      assert(
        t.contains("B4") && hasItemLine(t, "n-block") && t.contains("blockCount=2"),
        s"B4 blocked entry missing\n$t"
      )
      assert(t.contains("B2") && hasItemLine(t, "n-succ") && t.contains("n-gone"), s"B2 handover entry missing\n$t")
      assert(t.contains("B3") && hasItemLine(t, "n-bar"), s"B3 dead-barrier entry missing\n$t")
      assert(
        t.contains("B1") && hasItemLine(t, "n-loose") && t.contains("transcript-missing"),
        s"B1 loose-running entry missing\n$t"
      )
      // 落盘事实三面（任务书 / 结果件 / 会话现状 / 上游缺轨）
      assert(t.contains("tasks/n-block.md"), s"task-book pointer missing\n$t")
      assert(t.contains("results/n-block.md"), s"result-artifact pointer missing\n$t")
      assert(t.contains("缺轨(禁自动启动)"), s"upstream-gap flag missing (failed upstream)\n$t")
      assert(
        t.contains("sessionState=transcript-missing") && t.contains("resumable=false"),
        s"session-state facts missing\n$t"
      )
      assert(
        t.contains("blocked=1") && t.contains("failed=1") && t.contains("completed=1"),
        s"B0 histogram missing\n$t"
      )
      // R4 陷阱负控：终态节点（transcript 仍在盘）绝不作为条目出现
      assert(
        !t.linesIterator.exists(l => isItemLine(l, "n-done")),
        s"terminal node must never be listed as a reentry target\n$t"
      )
      assert(
        !t.linesIterator.exists(l => isItemLine(l, "n-fail")),
        s"terminal upstream must not itself be a candidate\n$t"
      )
      // 零节点写：磁盘 flow-map.json 逐字段不变（唤醒纯读）
      assertEquals(after, before, "wake must not write any node state (pure wake)")
      // 事件（谁 = project/nodeId、何时 = boot id + at、结果 = woken）
      assertEquals(
        events.count(_.contains(FlowMapEventLog.DispatcherWakeType)),
        1,
        s"exactly one dispatcher-wake event, got $events"
      )
      val ev = events.find(_.contains("dispatcher-wake")).getOrElse("")
      assert(
        ev.contains("result=woken") && ev.contains("boot=boot-A1") && ev.contains("b4=1")
          && ev.contains("b2=1") && ev.contains("b3=1") && ev.contains("b1=1") && ev.contains("nodes=6"),
        s"event summary must carry who/when/result + bucket counts, got: $ev"
      )
      // 落盘标记（幂等锚 + 清单审计面）
      val m = marker.getOrElse(fail("boot-wake.json marker must be persisted"))
      val entries = m.hcursor.downField("entries").as[List[Json]].getOrElse(Nil)
      assertEquals(entries.size, 1, s"one marker entry, got $m")
      assertEquals(entries.head.hcursor.downField("bootId").as[String].toOption, Some("boot-A1"))
      assertEquals(entries.head.hcursor.downField("project").as[String].toOption, Some("a1-main"))
      assertEquals(entries.head.hcursor.downField("result").as[String].toOption, Some("woken"))
      assertEquals(entries.head.hcursor.downField("blocking").as[Boolean].toOption, Some(true))
      val items = entries.head.hcursor.downField("items").as[List[Json]].getOrElse(Nil)
      assert(items.size >= 4, s"marker must carry the manifest items (audit face), got $items")
      assert(
        items.exists(_.hcursor.downField("resumable").as[Boolean].toOption.contains(false)),
        s"terminal target must be recorded as non-resumable, got $items"
      )
    end for
  }

  // ══ A1 触发面：非 boot 腿零触发（不重启零触发负控）═══════════════════

  test("A1b: scan legs + a real node session start produce ZERO wake (trigger surface = boot chain only)") {
    val ws = root / "ws-a1b"
    val pd = pdOf("a1b-surface", ws)
    val system = ActorSystem(s"bdw-a1b-${scala.util.Random.nextInt(1000000)}")
    for
      _ <- IO(os.makeDir.all(ws))
      _ <- seedFlowMap(
        pd,
        List(
          node("n-block", "gate-block", NodeLifecycle.Blocked, blockCount = 1, completedAt = Some(tenMinAgo)),
          node("n-succ", "collect", NodeLifecycle.Pending, in = List("n-block"), pendingSuccession = List("n-gone")),
          node("n-fresh", "fresh", NodeLifecycle.Pending, task = Some("跑一个真实节点会话当负控"))
        )
      )
      res <- mkResources(system, new ScriptLlm(_ => "NODE-DONE").handle)
      rt <- mountRuntime("a1b-surface", ws, system, res)
      (trig, trigger) <- recorder
      // 非 boot 腿 ×5 = TtlTick 30s 节拍驱动的全部扫描腿
      _ <- rt.engine.settleRunnableSweep().handleErrorWith(_ => IO.unit)
      _ <- rt.engine.settleStaleRunningNodes().handleErrorWith(_ => IO.unit)
      _ <- rt.engine.sweepDestroyWindows().handleErrorWith(_ => IO.unit)
      _ <- rt.engine.redeliverUnconsumedNebulaResults().handleErrorWith(_ => IO.unit)
      _ <- rt.engine.dispatchNotify.redeliver().handleErrorWith(_ => IO.unit)
      // 会话层：真实起一个节点会话（AgentActor spawn 路径）
      _ <- rt.engine.startNode("n-fresh").handleErrorWith(_ => IO.unit)
      _ <- waitUntil(20.seconds)(
        rt.store.getNode("n-fresh").map(_.exists(n => NodeLifecycle.Terminal.contains(n.status)))
      )
        .handleErrorWith(_ => IO.unit)
      preEvents <- readEvents(pd)
      preMarker <- readMarker(pd)
      preTexts <- trig.get
      // 触发面 = 仅 boot 入口，恰一次
      report <- wake(pd, "boot-A1b", trigger)
      postEvents <- readEvents(pd)
      postTexts <- trig.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(
        preEvents.count(_.contains(FlowMapEventLog.DispatcherWakeType)),
        0,
        s"no wake event may exist before the boot entry runs (no restart = zero trigger), got $preEvents"
      )
      assertEquals(preMarker, None, "no boot-wake.json marker may exist before the boot entry runs")
      assertEquals(preTexts, Nil, "no wake trigger may fire from scan legs / session start")
      assertEquals(report.woken, 1, s"the boot entry wakes exactly once, got ${report.summary}")
      assertEquals(postTexts.size, 1, "exactly one trigger after the boot entry")
      assertEquals(
        postEvents.count(_.contains(FlowMapEventLog.DispatcherWakeType)),
        1,
        "exactly one wake event after the boot entry (no duplicates from scan legs)"
      )
    end for
  }

  // ══ A2 幂等与防重（含连续重启风暴）════════════════════════════════════

  test("A2: idempotent per (boot, project); disk marker dedups across call paths; 5 consecutive boots stay linear") {
    val ws = root / "ws-a2"
    val pd = pdOf("a2-idem", ws)
    val system = ActorSystem(s"bdw-a2-${scala.util.Random.nextInt(1000000)}")
    for
      _ <- IO(os.makeDir.all(ws))
      _ <- seedFlowMap(pd, List(node("n-block", "gate", NodeLifecycle.Blocked, completedAt = Some(tenMinAgo))))
      res <- mkResources(system, new ScriptLlm(_ => "x").handle)
      _ <- mountRuntime("a2-idem", ws, system, res)
      (trig, trigger) <- recorder
      // 同一 boot 连续三次调用 → 恰一次
      _ <- wake(pd, "boot-A2", trigger)
      _ <- wake(pd, "boot-A2", trigger)
      _ <- wake(pd, "boot-A2", trigger)
      textsAfterRepeat <- trig.get
      evAfterRepeat <- readEvents(pd)
      marker <- readMarker(pd)
      // 落盘标记去重（模拟进程内 Ref 为空的第二调用路径：预置同 bootId 的 blocking 条目）
      _ <- IO(
        os.write.over(
          BootDispatcherWake.markerPath(pd),
          """{"v":1,"project":"a2-idem","lastBootId":"boot-A2-disk","entries":[{"bootId":"boot-A2-disk","project":"a2-idem","at":1,"result":"woken","reason":"","blocking":true,"nodes":1,"items":[]}]}"""
        )
      )
      diskReport <- wake(pd, "boot-A2-disk", trigger)
      textsAfterDisk <- trig.get
      // 连续 5 次 boot（重启风暴 / 看门狗拉起形态）
      _ <- List("boot-A2-r1", "boot-A2-r2", "boot-A2-r3", "boot-A2-r4", "boot-A2-r5")
        .traverse(b => wake(pd, b, trigger))
      textsAfterStorm <- trig.get
      evAfterStorm <- readEvents(pd)
      stormMarker <- readMarker(pd)
      finalState <- diskState(pd)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(textsAfterRepeat.size, 1, "same boot must wake exactly once (in-process fast path)")
      assertEquals(evAfterRepeat.count(_.contains("dispatcher-wake")), 1, "no duplicate wake events within one boot")
      assertEquals(
        marker.flatMap(_.hcursor.downField("entries").as[List[Json]].toOption).map(_.size),
        Some(1),
        "exactly one marker entry per (boot, project)"
      )
      assertEquals(
        diskReport.outcomes.map(o => (o.result, o.reason)),
        List((BootDispatcherWake.ResultSkipped, BootDispatcherWake.ReasonDuplicate)),
        "pre-seeded blocking marker entry must make the call idempotent"
      )
      assertEquals(textsAfterDisk.size, 1, "disk marker dedup must not fire a second wake (Ref empty for that boot id)")
      // 风暴：每 boot 恰一条（线性，绝不累积/循环）
      assertEquals(textsAfterStorm.size, 6, s"each boot wakes exactly once (linear): ${textsAfterStorm.size}")
      assertEquals(
        evAfterStorm.count(_.contains("dispatcher-wake")),
        6,
        s"one event per boot, got ${evAfterStorm.size}"
      )
      val perBoot = evAfterStorm
        .filter(_.contains("dispatcher-wake"))
        .groupBy(l => l.split("boot=").lift(1).map(_.takeWhile(c => c != ' ')).getOrElse("?"))
      assert(perBoot.values.forall(_.size == 1), s"each boot must have exactly one wake event, got $perBoot")
      assert(
        stormMarker
          .flatMap(_.hcursor.downField("entries").as[List[Json]].toOption)
          .exists(_.size <= BootDispatcherWake.MarkerKeepEntries),
        "marker must roll (bounded audit window), not grow unbounded"
      )
      // 幂等零副作用物证：无重入循环（节点集与状态逐字段不变）
      assertEquals(finalState.nodes.keySet, Set("n-block"), "no node may be created/mutated by repeated wakes")
      assertEquals(
        finalState.nodes("n-block").status,
        NodeLifecycle.Blocked,
        "blocked node must never be auto-reactivated"
      )
    end for
  }

  // ══ A3 落盘事实重建（内存态不可用/陈旧 ⇒ 以磁盘为准）══════════════════

  test("A3: manifest is rebuilt from disk (stale memory loses); pure fromJson needs no store/engine") {
    val ws = root / "ws-a3"
    val pd = pdOf("a3-disk", ws)
    val system = ActorSystem(s"bdw-a3-${scala.util.Random.nextInt(1000000)}")
    for
      _ <- IO(os.makeDir.all(ws))
      res <- mkResources(system, new ScriptLlm(_ => "x").handle)
      rt <- mountRuntime("a3-disk", ws, system, res)
      // 内存态：store 里挂一个 completed 节点（= 陈旧真相）
      _ <- rt.store.mutate(s => s.copy(nodes = Map("n-mem" -> node("n-mem", "mem-only", NodeLifecycle.Completed))))
      // 磁盘真相：与内存**不同**（一个 blocked 节点）——直接写盘（模拟另一实例/外部改动）
      _ <- seedFlowMap(
        pd,
        List(node("n-disk", "disk-block", NodeLifecycle.Blocked, blockCount = 3, completedAt = Some(tenMinAgo)))
      )
      (trig, trigger) <- recorder
      report <- wake(pd, "boot-A3", trigger)
      texts <- trig.get
      mem <- rt.store.snapshot
      raw <- IO.blocking(os.read(dirOf(pd) / BootWakeInventory.FileName))
      pure <- IO.fromEither(
        BootWakeInventory
          .fromJson(raw, dirOf(pd), "a3-pure", nowMs)
          .leftMap(e => new AssertionError(s"pure rebuild failed: $e"))
      )
      pureMounted <- ProjectRuntimeRegistry.get("a3-pure").map(_.isDefined)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // 反向对照：内存态仍是被替换前的 completed（陈旧），而唤醒按磁盘 blocked 产出
      assertEquals(mem.nodes.keySet, Set("n-mem"), "fixture sanity: in-memory state is the stale one")
      assertEquals(report.woken, 1, "disk truth must drive the wake even though memory says otherwise")
      val t = texts.head
      assert(t.contains("n-disk") && t.contains("blockCount=3"), s"manifest must come from disk, got\n$t")
      assert(!t.contains("n-mem"), s"manifest must NOT come from stale in-memory state, got\n$t")
      // 纯重建（零 store / 零 engine / 零 registry）
      assert(!pureMounted, "pure rebuild must not require any registered runtime")
      assertEquals(pure.project, "a3-pure")
      assertEquals(pure.inBucket(BootWakeInventory.BucketBlocked).map(_.nodeId), List("n-disk"))
      assertEquals(pure.histogram.get(NodeLifecycle.Blocked), Some(1))
    end for
  }

  // ══ A4 失败降级（显式记录 + 跳过；零重试）════════════════════════════

  test(
    "A4: degradation is explicit — not-mounted / terminal target / upstream gap / unreadable facts / trigger failure (no retry)"
  ) {
    val wsA = root / "ws-a4-unmounted"
    val pdA = pdOf("a4-unmounted", wsA)
    val wsB = root / "ws-a4-degrade"
    val pdB = pdOf("a4-degrade", wsB)
    val wsC = root / "ws-a4-missing"
    val pdC = pdOf("a4-missing", wsC)
    val system = ActorSystem(s"bdw-a4-${scala.util.Random.nextInt(1000000)}")
    for
      _ <- IO(os.makeDir.all(wsA))
      _ <- IO(os.makeDir.all(wsB))
      _ <- IO(os.makeDir.all(wsC))
      // (b) 目标会话已终态（running 但 transcript 缺失）+ (c) 上游缺轨
      _ <- seedFlowMap(
        pdB,
        List(
          node("n-gone", "ghost", NodeLifecycle.Running, sessionRef = Some("node-reclaimed")),
          node("n-bar", "bar", NodeLifecycle.Pending, in = List("n-fail")),
          node("n-fail", "up-fail", NodeLifecycle.Failed, completedAt = Some(tenMinAgo), result = Some("boom"))
        )
      )
      // (a) 项目在册但未挂载
      _ <- seedFlowMap(pdA, List(node("n-block", "gate", NodeLifecycle.Blocked)))
      // (e) 落盘事实不可读：wsC 无 flow-map.json
      res <- mkResources(system, new ScriptLlm(_ => "x").handle)
      _ <- mountRuntime("a4-degrade", wsB, system, res)
      (trig, trigger) <- recorder
      repA <- wake(pdA, "boot-A4a", trigger)
      textsA <- trig.get
      evA <- readEvents(pdA)
      repB <- wake(pdB, "boot-A4b", trigger)
      textsB <- trig.get
      stateB <- diskState(pdB)
      eventsB <- readEvents(pdB)
      sessionsB <- res.agentRegistry.get
      repC <- wake(pdC, "boot-A4c", trigger)
      evC <- readEvents(pdC)
      // (d) 触发链失败 + 零重试
      attempts <- Ref.of[IO, Int](0)
      failTrigger: Option[String => IO[Unit]] =
        Some((_: String) => attempts.update(_ + 1) *> IO.raiseError(new RuntimeException("channel down")))
      repD <- wake(pdB, "boot-A4d", failTrigger)
      attemptsAfter <- attempts.get
      evD <- readEvents(pdB)
      repD2 <- wake(pdB, "boot-A4d", failTrigger)
      attemptsFinal <- attempts.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // (a) 项目未挂载：显式记录 + 跳过、零触发
      assertEquals(
        repA.outcomes.map(o => (o.result, o.reason)),
        List((BootDispatcherWake.ResultSkipped, BootDispatcherWake.ReasonNotMounted))
      )
      assertEquals(textsA, Nil, "not-mounted project must not be woken")
      assert(
        evA.exists(l => l.contains("dispatcher-wake") && l.contains(s"reason=${BootDispatcherWake.ReasonNotMounted}")),
        s"not-mounted must be explicitly recorded in the event stream, got $evA"
      )
      // (b)(c) 可处置项目仍唤醒控制面，但清单把两处降级显式标出
      assertEquals(repB.woken, 1, s"degraded-but-addressable project still wakes the control plane: ${repB.summary}")
      val tB = textsB.head
      assert(
        tB.contains("sessionState=transcript-missing") && tB.contains("已终态/不可续跑"),
        s"terminal target must be explicitly marked as non-resumable\n$tB"
      )
      assert(tB.contains("缺轨(禁自动启动)"), s"upstream gap must be flagged as no-auto-start\n$tB")
      // 跳过 = 真的没动：下游不自动启动、丢会话节点不自动续/判死、零会话 spawn、不碰 crash-recovery
      assertEquals(
        stateB.nodes("n-bar").status,
        NodeLifecycle.Pending,
        "downstream must NOT be auto-started (barrier gap)"
      )
      assertEquals(
        stateB.nodes("n-gone").status,
        NodeLifecycle.Running,
        "lost-session node must NOT be auto-resumed/failed by the wake"
      )
      assert(sessionsB.isEmpty, s"no node session may be spawned by the wake, got ${sessionsB.keys}")
      assert(!eventsB.exists(_.contains("boot-recovery")), "wake must not touch the crash-recovery path")
      // (e) 落盘事实不可读 = 显式记录 + 跳过（不静默当成功）
      assertEquals(
        repC.outcomes.map(o => (o.result, o.reason)),
        List((BootDispatcherWake.ResultSkipped, "flow-map-missing"))
      )
      assert(
        evC.exists(l =>
          l.contains("dispatcher-wake") && l.contains("result=skipped")
            && l.contains("reason=flow-map-missing")
        ),
        s"unreadable facts must be recorded explicitly, got $evC"
      )
      // (d) 触发失败 = 显式失败 + 一次尝试零重试
      assertEquals(
        repD.outcomes.map(o => (o.result, o.reason)),
        List((BootDispatcherWake.ResultFailed, BootDispatcherWake.ReasonNotifyFailed))
      )
      assertEquals(attemptsAfter, 1, "trigger failure = exactly one attempt (no retry storm)")
      assertEquals(repD2.outcomes.head.reason, BootDispatcherWake.ReasonDuplicate)
      assertEquals(attemptsFinal, 1, "a repeated call in the same boot must not retry at all (bounded)")
      assert(
        evD.exists(l =>
          l.contains("dispatcher-wake") && l.contains("result=failed")
            && l.contains("reason=notify-failed")
        ),
        s"trigger failure must be recorded explicitly, got $evD"
      )
    end for
  }

  // ══ A5 可观测 + 可关（对照读数）═══════════════════════════════════════

  test("A5: switch off = zero action / zero residue; default is on (author ruling 'ship A directly')") {
    val ws = root / "ws-a5"
    val pd = pdOf("a5-switch", ws)
    val system = ActorSystem(s"bdw-a5-${scala.util.Random.nextInt(1000000)}")
    for
      _ <- IO(os.makeDir.all(ws))
      _ <- seedFlowMap(pd, List(node("n-block", "gate", NodeLifecycle.Blocked, completedAt = Some(tenMinAgo))))
      res <- mkResources(system, new ScriptLlm(_ => "x").handle)
      _ <- mountRuntime("a5-switch", ws, system, res)
      (trig, trigger) <- recorder
      off <- BootDispatcherWake.wakeAll(
        trigger = trigger,
        bootId = "boot-A5-off",
        enabled = false,
        projects = Some(List(pd))
      )
      textsOff <- trig.get
      eventsOff <- readEvents(pd)
      markerOff <- readMarker(pd)
      on <- wake(pd, "boot-A5-on", trigger)
      textsOn <- trig.get
      eventsOn <- readEvents(pd)
      defaultProp <- IO(nebflow.shared.Defaults.BootDispatcherWakeEnabled)
      hotProp <- IO {
        val before = nebflow.shared.Defaults.BootDispatcherWakeEnabled
        sys.props.update("nebflow.boot.dispatcherWake", "false")
        val during = nebflow.shared.Defaults.BootDispatcherWakeEnabled
        sys.props.remove("nebflow.boot.dispatcherWake")
        (before, during, nebflow.shared.Defaults.BootDispatcherWakeEnabled)
      }
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(off.projects, 0, "switch off = the leg does nothing at all")
      assertEquals(off.outcomes, Nil)
      assertEquals(textsOff, Nil, "switch off: zero wake")
      assertEquals(eventsOff, Nil, "switch off: zero events (status quo has no dispatcher-wake line)")
      assertEquals(markerOff, None, "switch off: zero residue (no marker file)")
      assertEquals(on.woken, 1, "switch on: the same fixture does wake (control reading)")
      assertEquals(textsOn.size, 1)
      assertEquals(eventsOn.count(_.contains("dispatcher-wake")), 1)
      assert(defaultProp, "default must be true (plan §2 A ⑤ recommended default + author ruling)")
      assertEquals(hotProp, (true, false, true), "prop is hot-read (kill-switch precedent)")
    end for
  }

  // ══ A6 假阳负控 ═══════════════════════════════════════════════════════

  test("A6: no false positives — zero-work project, legitimately waiting node, sub-60s stall") {
    val ws = root / "ws-a6"
    val pd = pdOf("a6-falsepositive", ws)
    for
      _ <- IO(os.makeDir.all(ws))
      _ <- seedFlowMap(
        pd,
        List(
          // 全终态成员：不唤醒
          node("n-done", "done", NodeLifecycle.Completed, completedAt = Some(tenMinAgo)),
          node("n-cancel", "cancelled", NodeLifecycle.Cancelled, completedAt = Some(tenMinAgo)),
          // 合法等待：pending 而上游 running（引擎 mountStallReason 同款豁免）
          node("n-wait", "waiter", NodeLifecycle.Pending, in = List("n-run")),
          node("n-run", "runner", NodeLifecycle.Running, sessionRef = Some("node-live")),
          // 未到 60s 档：上游 10s 前刚终态
          node("n-early", "early", NodeLifecycle.Pending, in = List("n-just"), createdAt = nowMs - 300_000L),
          node("n-just", "just", NodeLifecycle.Completed, completedAt = Some(nowMs - 10_000L))
        )
      )
      _ <- seedTranscript("node-live")
      (trig, trigger) <- recorder
      report <- wake(pd, "boot-A6", trigger)
      texts <- trig.get
      events <- readEvents(pd)
      inv <- BootWakeInventory.fromDisk(dirOf(pd), pd.name, nowMs)
    yield
      val ins = inv.getOrElse(fail("inventory must build"))
      assertEquals(
        ins.items.map(i => i.nodeId -> i.buckets),
        Nil,
        "legitimate waits / terminal-only members must not be reported (plan §4 red: 假阳)"
      )
      assertEquals(report.skipped, 1, s"no-work project must be skipped, got ${report.summary}")
      assertEquals(report.outcomes.head.reason, BootDispatcherWake.ReasonNoReentry)
      assertEquals(texts, Nil, "no wake for a project with no reentry need (no phantom cold-start turn)")
      // 跳过也必须留痕（可审计：不再是「缺失的日志行」）
      assert(
        events.exists(l =>
          l.contains("dispatcher-wake") && l.contains("result=skipped")
            && l.contains(s"reason=${BootDispatcherWake.ReasonNoReentry}")
        ),
        s"skip must be auditable in the event stream, got $events"
      )
    end for
  }

  // ══ A7 上限 ═══════════════════════════════════════════════════════════

  test("A7: per-boot project cap is enforced and recorded (no wake storm from an unbounded project list)") {
    val wsShared = root / "ws-a7"
    for
      _ <- IO(os.makeDir.all(wsShared))
      pds = (0 until BootDispatcherWake.MaxProjectsPerBoot + 1).toList.map(i => pdOf(f"a7-p$i%03d", wsShared))
      (trig, trigger) <- recorder
      report <- BootDispatcherWake.wakeAll(
        trigger = trigger,
        bootId = "boot-A7",
        projects = Some(pds),
        nowMs = () => nowMs
      )
      texts <- trig.get
    yield
      assertEquals(report.projects, BootDispatcherWake.MaxProjectsPerBoot + 1)
      assertEquals(
        report.outcomes.count(_.reason == BootDispatcherWake.ReasonCapExceeded),
        1,
        s"exactly one project over the cap must be skipped with cap-exceeded, got ${report.outcomes
            .map(o => o.project -> o.reason)}"
      )
      assertEquals(texts, Nil, "no wake from a project list over the cap without a flow map")
    end for
  }
end BootDispatcherWakeSpec
