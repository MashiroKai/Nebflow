package nebflow.core.processor

import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.parser.parse as jsonParse
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, ActorSystem, Behavior, Behaviors}
import nebflow.actor.{AgentCommand, AgentKind, AgentRecord, AgentStatus}
import nebflow.agent.{AgentLibrary, SharedResources}
import nebflow.core.FileChangeTracker
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.gateway.WsHub
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, StreamChunk, ThinkingConfig}

import scala.concurrent.duration.*

/**
 * **node-stuck dispatcher notification**（node-stuck-notify 批，2026-09-11）场景回归。
 *
 * 场景与判据的对应（验收映射）：
 *   - S1 超窗挂等 → dispatcher 注入 + flow-map 审计事件（走 `TaskStuckWatcher.scan`
 *     端到端，证明接线）
 *   - S2 productive long task（工具相位超阈值但**有新鲜正信号**）→ 零通报、零事件
 *   - S3 dispatcher 缺席（空串 / 注册表查无）→ root 兜底（`viaRoot=true`）
 *   - S4 可配置阈值（同一形态：宽阈值不报 / 窄阈值报）
 *   - S5（增补）无进展 / 工具相位超时两条 cause 分流
 */
class NodeStuckNotifySpec extends CatsEffectSuite:

  private val fakeLlm = new LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("fake llm not expected here"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.raiseError[IO](new RuntimeException("fake llm not expected here"))

  private def mkRecordingActor(record: Ref[IO, List[AgentCommand]]): Behavior[AgentCommand] =
    def loop: Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand](cmd => record.update(_ :+ cmd).as(loop))
    loop

  private def mkResources(system: ActorSystem, tmp: os.Path): IO[SharedResources] =
    for
      dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
    yield SharedResources(
      llm = fakeLlm,
      dispatcher = dispatcher,
      sessionStore = SessionStore(os.temp.dir(), os.temp.dir()),
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
      voiceMutedRef = voiceMuted
    )

  /** flow-map 事件行读取（真实写盘：`<workspace>/.nebflow/flow-map-events.jsonl`）。 */
  private def eventLines(ws: os.Path): IO[List[io.circe.Json]] =
    val f = ws / ".nebflow" / nebflow.core.project.FlowMapEventLog.FileName
    if !os.exists(f) then IO.pure(Nil)
    else
      IO(
        os.read(f)
          .linesIterator
          .filter(_.trim.nonEmpty)
          .flatMap(l => jsonParse(l).toOption.toList)
          .toList
      )

  private def notifiedTexts(cmds: List[AgentCommand]): List[String] =
    cmds.collect { case i: AgentCommand.ImmediateInput => i.text }

  private def rootRow(sessionId: String, ref: ActorRef[AgentCommand]): AgentRecord =
    AgentRecord(sessionId = sessionId, ref = ref, kind = AgentKind.Root, rootSessionId = "root-1")

  /** 一条 WaitingForUser 节点记录（dispatcher = `parent`）。 */
  private def waitingRecord(
    nodeRef: ActorRef[AgentCommand],
    now: Long,
    idleMs: Long,
    parent: String
  ): AgentRecord =
    AgentRecord(
      sessionId = "node-sess-1",
      ref = nodeRef,
      kind = AgentKind.Flow,
      rootSessionId = "root-1",
      startedAt = now - 3600_000L,
      status = AgentStatus.WaitingForUser,
      lastActivityMs = now - idleMs,
      parentSessionId = parent,
      currentToolName = Some("AskUserQuestion"),
      currentToolStartedAt = now - idleMs,
      currentToolCallId = Some("call-1")
    )

  test("S1: WaitingForUser over threshold → dispatcher injection + flow-map event (end-to-end through scan)") {
    val system = ActorSystem("node-stuck-s1")
    for
      _ <- IO(system)
      tmp <- IO(os.temp.dir())
      ws = tmp / "ws-s1"
      resources <- mkResources(system, tmp)
      dispatcherInbox <- Ref.of[IO, List[AgentCommand]](Nil)
      dispatcherRef <- system.spawn(mkRecordingActor(dispatcherInbox), "dispatcher-1")
      nodeInbox <- Ref.of[IO, List[AgentCommand]](Nil)
      nodeActorRef <- system.spawn(mkRecordingActor(nodeInbox), "node-1")
      rootInbox <- Ref.of[IO, List[AgentCommand]](Nil)
      rootRef <- system.spawn(mkRecordingActor(rootInbox), "root-1")
      hub = new WsHub()
      now = System.currentTimeMillis()
      rec = waitingRecord(nodeActorRef, now, 20 * 60 * 1000L, parent = "dispatcher-1")
      registry = Map(
        rec.sessionId -> rec,
        "dispatcher-1" -> rootRow("dispatcher-1", dispatcherRef),
        "root-1" -> rootRow("root-1", rootRef)
      )
      _ <- resources.agentRegistry.set(registry)
      // 端到端接线：走 watcher 的一整轮扫描（阈值注入 60s < 已挂等 20min）。
      _ <- TaskStuckWatcher.scan(
        resources,
        hub,
        600_000L,
        notifyWaitingMs = 60_000L,
        nodeProbe = _ => IO.pure(Some(NodeStuckNotify.NodeRef(ws.toString, "demo", "n-1", Some("chain-7"))))
      )
      _ <- IO.sleep(300.millis)
      dispatcherCmds <- dispatcherInbox.get
      nodeCmds <- nodeInbox.get
      rootCmds <- rootInbox.get
      events <- eventLines(ws)
    yield
      val texts = notifiedTexts(dispatcherCmds)
      assertEquals(texts.size, 1, s"expected exactly one injected notice to the dispatcher, got $dispatcherCmds")
      val t = texts.head
      // 四段产品契约：state / duration / activity / actionable options
      assert(t.contains("[node-stuck]"), t)
      assert(t.contains("cause=waiting-for-user"), t)
      assert(t.contains("node=n-1"), t)
      // 「哪个会话」必须在通报正文里可读（不只落在审计行）
      assert(t.contains("session=node-sess-1"), t)
      assert(t.contains("state: WaitingForUser"), t)
      assert(t.contains("duration: 20m0s"), t)
      assert(t.contains("activity:"), t)
      assert(t.contains("options:"), t)
      assert(t.contains("dispatcher-1"), t)
      // 通报只发 dispatcher：节点自身与 root 都不收
      assertEquals(nodeCmds.size, 0, s"node must not be injected, got $nodeCmds")
      assertEquals(rootCmds.size, 0, s"root must not be injected when a live dispatcher exists, got $rootCmds")
      // 审计事件：真实 flow-map-events.jsonl 一行，机器可读记账字段齐备
      assertEquals(events.size, 1, s"expected exactly one flow-map event, got $events")
      val ev = events.head
      assertEquals(ev.hcursor.get[String]("type").toOption, Some("node-stuck-notified"))
      assertEquals(ev.hcursor.get[String]("nodeId").toOption, Some("n-1"))
      assertEquals(ev.hcursor.get[String]("project").toOption, Some("demo"))
      assertEquals(ev.hcursor.get[String]("chainId").toOption, Some("chain-7"))
      val summary = ev.hcursor.get[String]("summary").toOption.getOrElse("")
      assert(summary.contains("cause=waiting-for-user"), summary)
      assert(summary.contains("duration=1200s"), summary)
      assert(summary.contains("target=dispatcher-1:dispatcher"), summary)
      assert(summary.contains("delivered=true"), summary)
    end for
  }

  test("S2: productive long task (tool over phase threshold BUT fresh progress signal) → zero alert, zero event") {
    val system = ActorSystem("node-stuck-s2")
    for
      _ <- IO(system)
      tmp <- IO(os.temp.dir())
      ws = tmp / "ws-s2"
      resources <- mkResources(system, tmp)
      dispatcherInbox <- Ref.of[IO, List[AgentCommand]](Nil)
      dispatcherRef <- system.spawn(mkRecordingActor(dispatcherInbox), "dispatcher-1")
      nodeInbox <- Ref.of[IO, List[AgentCommand]](Nil)
      nodeActorRef <- system.spawn(mkRecordingActor(nodeInbox), "node-1")
      now = System.currentTimeMillis()
      // 30min 工具相位（远超 10min 阈值）但 5s 前刚打过进展信号 = 还在推进的长任务。
      busy = AgentRecord(
        sessionId = "node-sess-1",
        ref = nodeActorRef,
        kind = AgentKind.Flow,
        rootSessionId = "root-1",
        startedAt = now - 3600_000L,
        status = AgentStatus.Processing,
        lastActivityMs = now - 30 * 60 * 1000L,
        lastProgressSignalAt = now - 5000L,
        currentToolName = Some("Bash"),
        currentToolStartedAt = now - 30 * 60 * 1000L,
        parentSessionId = "dispatcher-1"
      )
      registry = Map(busy.sessionId -> busy, "dispatcher-1" -> rootRow("dispatcher-1", dispatcherRef))
      _ <- resources.agentRegistry.set(registry)
      // 判据面先自证：这条记录**确实**命中 assessDetailed（工具相位超时），只是被判为类② 假阳性。
      assessment = TaskStuckWatcher.assessDetailed(busy, now, 600_000L)
      cls = assessment.map(a => TaskStuckWatcher.classify(busy, a, now, 0))
      outcomes <- NodeStuckNotify.sweep(
        resources,
        registry,
        now,
        nodeProbe = _ => IO.pure(Some(NodeStuckNotify.NodeRef(ws.toString, "demo", "n-1", None)))
      )
      cmds <- dispatcherInbox.get
      events <- eventLines(ws)
    yield
      assert(assessment.isDefined, "precondition: the long tool phase does hit the stuck assessment")
      assertEquals(cls.map(_.cls), Some(TaskStuckWatcher.ClassFalsePositive))
      assertEquals(outcomes.size, 0, s"no notification expected for a productive long task, got $outcomes")
      assertEquals(cmds.size, 0, s"dispatcher must not be notified, got $cmds")
      assertEquals(events.size, 0, s"no flow-map event expected, got $events")
    end for
  }

  test("S3: dispatcher absent (empty / dead row) → root fallback") {
    val system = ActorSystem("node-stuck-s3")
    for
      _ <- IO(system)
      tmp <- IO(os.temp.dir())
      ws = tmp / "ws-s3"
      resources <- mkResources(system, tmp)
      rootInbox <- Ref.of[IO, List[AgentCommand]](Nil)
      rootRef <- system.spawn(mkRecordingActor(rootInbox), "root-1")
      nodeInboxA <- Ref.of[IO, List[AgentCommand]](Nil)
      nodeRefA <- system.spawn(mkRecordingActor(nodeInboxA), "node-a")
      nodeInboxB <- Ref.of[IO, List[AgentCommand]](Nil)
      nodeRefB <- system.spawn(mkRecordingActor(nodeInboxB), "node-b")
      now = System.currentTimeMillis()
      // A：dispatcher 字段为空（注册点从未写 dispatcher）
      noDisp = waitingRecord(nodeRefA, now, 20 * 60 * 1000L, parent = "").copy(sessionId = "node-sess-a")
      // B：dispatcher 字段指向一个**已不在注册表**的会话（dispatcher 死亡/重启）
      deadDisp = waitingRecord(nodeRefB, now, 20 * 60 * 1000L, parent = "ghost-1").copy(sessionId = "node-sess-b")
      rootRec = rootRow("root-1", rootRef)
      registry = Map(noDisp.sessionId -> noDisp, deadDisp.sessionId -> deadDisp, "root-1" -> rootRec)
      _ <- resources.agentRegistry.set(registry)
      outcomes <- NodeStuckNotify.sweep(
        resources,
        registry,
        now,
        nodeProbe = sid => IO.pure(Some(NodeStuckNotify.NodeRef(ws.toString, "demo", s"n-$sid", None)))
      )
      rootCmds <- rootInbox.get
      events <- eventLines(ws)
    yield
      assertEquals(outcomes.map(_.sessionId).sorted, List("node-sess-a", "node-sess-b"))
      assert(outcomes.forall(o => o.target.exists(_.sessionId == "root-1")), s"both must fall back to root: $outcomes")
      assert(outcomes.forall(o => o.target.exists(_.viaRoot)), s"viaRoot must be true: $outcomes")
      assert(outcomes.forall(_.delivered), s"root delivery must succeed: $outcomes")
      val texts = notifiedTexts(rootCmds)
      assertEquals(texts.size, 2, s"root must receive one notice per node, got $rootCmds")
      assert(texts.forall(_.contains("root fallback")), texts.mkString("\n"))
      assertEquals(events.size, 2, s"one event per node, got $events")
      assert(
        events.forall(_.hcursor.get[String]("summary").toOption.exists(_.contains("target=root-1:root-fallback"))),
        events.mkString("\n")
      )
    end for
  }

  test("S4: configurable threshold works (wide → silent, narrow → notified)") {
    val system = ActorSystem("node-stuck-s4")
    for
      _ <- IO(system)
      tmp <- IO(os.temp.dir())
      ws = tmp / "ws-s4"
      resources <- mkResources(system, tmp)
      dispatcherInbox <- Ref.of[IO, List[AgentCommand]](Nil)
      dispatcherRef <- system.spawn(mkRecordingActor(dispatcherInbox), "dispatcher-1")
      nodeInbox <- Ref.of[IO, List[AgentCommand]](Nil)
      nodeActorRef <- system.spawn(mkRecordingActor(nodeInbox), "node-1")
      now = System.currentTimeMillis()
      rec = waitingRecord(nodeActorRef, now, 20 * 60 * 1000L, parent = "dispatcher-1")
      registry = Map(rec.sessionId -> rec, "dispatcher-1" -> rootRow("dispatcher-1", dispatcherRef))
      _ <- resources.agentRegistry.set(registry)
      probe = (_: String) => IO.pure(Some(NodeStuckNotify.NodeRef(ws.toString, "demo", "n-1", None)))
      // 宽阈值（1h > 已挂等 20min）⇒ 不通报
      wide <- NodeStuckNotify.sweep(resources, registry, now, waitingThresholdMs = 3600_000L, nodeProbe = probe)
      cmdsAfterWide <- dispatcherInbox.get
      eventsAfterWide <- eventLines(ws)
      // 窄阈值（60s < 已挂等 20min）⇒ 通报
      narrow <- NodeStuckNotify.sweep(resources, registry, now, waitingThresholdMs = 60_000L, nodeProbe = probe)
      cmdsAfterNarrow <- dispatcherInbox.get
      eventsAfterNarrow <- eventLines(ws)
    yield
      assertEquals(wide.size, 0, s"wide threshold must stay silent, got $wide")
      assertEquals(cmdsAfterWide.size, 0, "wide threshold must not inject")
      assertEquals(eventsAfterWide.size, 0, "wide threshold must not write an event")
      assertEquals(narrow.size, 1, s"narrow threshold must notify, got $narrow")
      assertEquals(cmdsAfterNarrow.size, 1, "narrow threshold must inject exactly one notice")
      assertEquals(eventsAfterNarrow.size, 1, "narrow threshold must write exactly one event")
    end for
  }

  test("S5: no-progress / tool-timeout cause split (Processing axis)") {
    val system = ActorSystem("node-stuck-s5")
    for
      _ <- IO(system)
      tmp <- IO(os.temp.dir())
      ws = tmp / "ws-s5"
      resources <- mkResources(system, tmp)
      dispatcherInbox <- Ref.of[IO, List[AgentCommand]](Nil)
      dispatcherRef <- system.spawn(mkRecordingActor(dispatcherInbox), "dispatcher-1")
      toolInbox <- Ref.of[IO, List[AgentCommand]](Nil)
      toolRef <- system.spawn(mkRecordingActor(toolInbox), "node-tool")
      idleInbox <- Ref.of[IO, List[AgentCommand]](Nil)
      idleRef <- system.spawn(mkRecordingActor(idleInbox), "node-idle")
      now = System.currentTimeMillis()
      // 工具相位超时：agent 侧事件 60s 前（未超 10min）但同一工具已跑 30min、零正信号
      toolCase = AgentRecord(
        sessionId = "node-sess-tool",
        ref = toolRef,
        kind = AgentKind.Flow,
        rootSessionId = "root-1",
        startedAt = now - 3600_000L,
        status = AgentStatus.Processing,
        lastActivityMs = now - 60_000L,
        lastProgressSignalAt = 0L,
        currentToolName = Some("Bash"),
        currentToolStartedAt = now - 30 * 60 * 1000L,
        parentSessionId = "dispatcher-1"
      )
      // 无进展：无工具相位、agent 侧事件停摆 30min
      idleCase = AgentRecord(
        sessionId = "node-sess-idle",
        ref = idleRef,
        kind = AgentKind.Flow,
        rootSessionId = "root-1",
        startedAt = now - 3600_000L,
        status = AgentStatus.Processing,
        lastActivityMs = now - 30 * 60 * 1000L,
        lastProgressSignalAt = 0L,
        parentSessionId = "dispatcher-1"
      )
      registry =
        Map(
          toolCase.sessionId -> toolCase,
          idleCase.sessionId -> idleCase,
          "dispatcher-1" -> rootRow("dispatcher-1", dispatcherRef)
        )
      _ <- resources.agentRegistry.set(registry)
      outcomes <- NodeStuckNotify.sweep(
        resources,
        registry,
        now,
        noProgressThresholdMs = 600_000L,
        nodeProbe = sid => IO.pure(Some(NodeStuckNotify.NodeRef(ws.toString, "demo", s"n-$sid", None)))
      )
      events <- eventLines(ws)
      cmds <- dispatcherInbox.get
    yield
      assertEquals(
        outcomes.map(o => (o.sessionId, o.cause)).toSet,
        Set(("node-sess-tool", NodeStuckNotify.CauseToolTimeout), ("node-sess-idle", NodeStuckNotify.CauseNoProgress)),
        s"cause split wrong: $outcomes"
      )
      assert(outcomes.forall(_.delivered), s"both must be delivered to the dispatcher: $outcomes")
      assertEquals(cmds.size, 2, "dispatcher gets one notice per stuck node")
      assertEquals(events.size, 2, "one audit event per notification (accounting)")
      assert(
        events.map(summaryCause).forall(c => c == "no-progress" || c == "tool-timeout"),
        events.mkString("\n")
      )
    end for
  }

  test("S6: healthy session (recent activity; short park) → zero alert, zero event (no false positive)") {
    val system = ActorSystem("node-stuck-s6")
    for
      _ <- IO(system)
      tmp <- IO(os.temp.dir())
      ws = tmp / "ws-s6"
      resources <- mkResources(system, tmp)
      dispatcherInbox <- Ref.of[IO, List[AgentCommand]](Nil)
      dispatcherRef <- system.spawn(mkRecordingActor(dispatcherInbox), "dispatcher-1")
      healthyInbox <- Ref.of[IO, List[AgentCommand]](Nil)
      healthyRef <- system.spawn(mkRecordingActor(healthyInbox), "node-healthy")
      parkedInbox <- Ref.of[IO, List[AgentCommand]](Nil)
      parkedRef <- system.spawn(mkRecordingActor(parkedInbox), "node-parked")
      now = System.currentTimeMillis()
      // 正常推进中的会话：3s 前刚有 agent 侧事件，无工具相位。
      healthy = AgentRecord(
        sessionId = "node-sess-healthy",
        ref = healthyRef,
        kind = AgentKind.Flow,
        rootSessionId = "root-1",
        startedAt = now - 3600_000L,
        status = AgentStatus.Processing,
        lastActivityMs = now - 3000L,
        lastProgressSignalAt = now - 3000L,
        parentSessionId = "dispatcher-1"
      )
      // 合法 human-in-the-loop：挂在人工回答上仅 30s（远低于挂等阈值）。
      parked = waitingRecord(parkedRef, now, 30 * 1000L, parent = "dispatcher-1").copy(sessionId = "node-sess-parked")
      registry = Map(
        healthy.sessionId -> healthy,
        parked.sessionId -> parked,
        "dispatcher-1" -> rootRow("dispatcher-1", dispatcherRef)
      )
      _ <- resources.agentRegistry.set(registry)
      outcomes <- NodeStuckNotify.sweep(
        resources,
        registry,
        now,
        nodeProbe = sid => IO.pure(Some(NodeStuckNotify.NodeRef(ws.toString, "demo", s"n-$sid", None)))
      )
      cmds <- dispatcherInbox.get
      events <- eventLines(ws)
    yield
      assertEquals(outcomes.size, 0, s"healthy/parked sessions must not be flagged, got $outcomes")
      assertEquals(cmds.size, 0, s"dispatcher must get no notice for healthy sessions, got $cmds")
      assertEquals(events.size, 0, s"no flow-map event for healthy sessions, got $events")
    end for
  }

  private def summaryCause(ev: io.circe.Json): String =
    val summary = ev.hcursor.get[String]("summary").toOption.getOrElse("")
    summary.split("\\s+").collectFirst { case s if s.startsWith("cause=") => s.stripPrefix("cause=") }.getOrElse("")

end NodeStuckNotifySpec
