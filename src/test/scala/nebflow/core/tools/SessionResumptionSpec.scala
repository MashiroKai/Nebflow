package nebflow.core.tools

import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, AgentCommand, AgentKind, AgentRecord, Behavior, Behaviors}
import nebflow.actor.AgentDef
import nebflow.agent.{AgentLibrary, SharedResources, SubAgentTaskStore}
import nebflow.shared.SubAgentTask
import nebflow.core.FileChangeTracker
import nebflow.core.delegate.{DelegateRegistry, DelegateNotify, SessionResumption}
import nebflow.core.project.{DelegateFlowHosts, FlowMapState, NodeDef}
import nebflow.core.task.FileTaskStore
import nebflow.core.{LlmLogWriter, RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.PathUtil
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk, ThinkingConfig}

import java.nio.file.Files
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Continuation invariants: stable identity and display name, intact saved context,
 * manual recovery gates, scope ownership, media delivery and partial-start cleanup.
 */
class SessionResumptionSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  private val tempRoot: os.Path = os.Path(Files.createTempDirectory("nb-session-resumption"))
  private val originalRoot: os.Path = PathUtil.dataRoot

  override def beforeAll(): Unit =
    PathUtil.setDataRoot(tempRoot)
    LlmLogWriter.setEnabled(false)

  override def afterAll(): Unit =
    LlmLogWriter.setEnabled(true)
    PathUtil.setDataRoot(originalRoot)
    // Teardown races the tail of async writers (session-store dirty flushes,
    // flow-map host writes) that can re-create entries between removal passes,
    // surfacing as DirectoryNotEmptyException. Retry with backoff; if the tree
    // still cannot be fully removed, say so and move on — a leftover temp tree
    // must not fail an otherwise green spec run.
    val maxAttempts = 5
    var cleanupError: Option[Throwable] = None
    for attempt <- 1 to maxAttempts do
      try os.remove.all(tempRoot)
      catch
        case e: java.io.IOException =>
          cleanupError = Some(e)
          if attempt < maxAttempts then Thread.sleep(200L * attempt)
    cleanupError.foreach(e =>
      System.err.println(s"[afterAll] temp data root cleanup incomplete after $maxAttempts attempts: $e")
    )
  end afterAll

  override def beforeEach(context: munit.BeforeEach): Unit =
    // 作用域宿主是进程内全局 Ref——逐用例清册防跨用例串图。
    DelegateFlowHosts.clear.unsafeRunSync()

  override def afterEach(context: munit.AfterEach): Unit =
    DelegateFlowHosts.clear.unsafeRunSync()

  // ── fixtures ────────────────────────────────────────────────────────────

  /**
   * hang=true ⇒ the turn never ends (pins the live reading); false ⇒ the turn ends normally (pins the
   * transcript write). `gate` holds every LLM call until it is completed, which keeps the resumed
   * session in flight (live in the registry) for as long as a spec needs.
   */
  private class StubLlm(hang: Boolean, gate: Option[Deferred[IO, Unit]] = None) extends LlmHandle[IO]:
    val requests = new java.util.concurrent.ConcurrentLinkedQueue[LlmRequest]()

    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected"))

    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(IO { requests.add(req); () }).drain ++
        (if hang then Stream.never[IO]
         else
           gate.fold(Stream.empty.covaryAll[IO, StreamChunk])(g => Stream.eval(g.get).drain) ++
             Stream(StreamChunk.TextDelta("RESUMED_TURN_DONE"), StreamChunk.Done(None, None)))

  end StubLlm

  private def mkResources(
    system: ActorSystem,
    hang: Boolean,
    gate: Option[Deferred[IO, Unit]] = None
  ): IO[SharedResources] =
    for
      dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
    yield SharedResources(
      llm = new StubLlm(hang, gate),
      dispatcher = dispatcher,
      sessionStore = SessionStore(tempRoot / "sessions", tempRoot / "tasks"),
      projectRoot = os.pwd,
      thinkingConfigRef = thinkingRef,
      rateLimiter = rateLimiter,
      fileChangeTracker = tracker,
      contextWindow = 100_000,
      agentLibrary = new AgentLibrary(tempRoot / "agents"),
      taskStore = FileTaskStore,
      historyArchiver = null,
      fileLockManager = fileLocks,
      sessionModelOverrides = modelOverrides,
      providerRegistry = null,
      healthMonitor = ProviderHealthMonitor(null),
      actorSystem = system,
      subAgentTaskStore = new SubAgentTaskStore(tempRoot / "subagent-tasks"),
      voiceMutedRef = voiceMuted
    )

  private def mkRecording(events: Ref[IO, List[AgentCommand]]): Behavior[AgentCommand] =
    def loop: Behavior[AgentCommand] =
      Behaviors.receiveMessage(cmd => events.update(_ :+ cmd).as(loop))
    loop

  private def workspaceOf(name: String): os.Path =
    val p = tempRoot / s"ws-$name"
    os.makeDir.all(p)
    p

  private def rootCtx(
    resources: SharedResources,
    system: ActorSystem,
    sid: String,
    rootSid: Option[String] = None,
    agent: String = "Nebula"
  ) =
    ToolContext(
      projectRoot = os.pwd.toString,
      sessionId = Some(sid),
      agentDef = Some(AgentDef(name = agent, description = "spec fixture")),
      actorSystem = Some(system),
      sharedResources = Some(resources),
      rootSessionId = rootSid
    )

  private def delegateCtx(resources: SharedResources, system: ActorSystem, delegateId: String) =
    ToolContext(
      projectRoot = os.pwd.toString,
      sessionId = Some(delegateId),
      agentDef = Some(AgentDef(name = "nebflow", description = "spec fixture")),
      actorSystem = Some(system),
      sharedResources = Some(resources)
    )

  private def mailToDelegate(
    resources: SharedResources,
    system: ActorSystem,
    parentSid: String,
    targetId: String,
    message: String
  ) =
    MailTool.call(
      JsonObject("address" -> s"delegate:$targetId".asJson, "message" -> message.asJson),
      rootCtx(resources, system, parentSid)
    )

  private def seedRecord(
    id: String,
    parentSid: String,
    ws: os.Path,
    title: String,
    tombstone: Boolean = false,
    archived: Boolean = false,
    restartInterrupted: Boolean = false,
    projectWs: Option[String] = None
  ): IO[Unit] =
    IO.blocking(
      DelegateRegistry.put(
        DelegateRegistry.Record(
          id = id,
          executor = nebflow.core.executor.ExecutorRegistry.DefaultId,
          cwd = ws.toString,
          project = None,
          taskId = None,
          parentSessionId = parentSid,
          title = title,
          createdAt = System.currentTimeMillis(),
          cancelRequestedAt = if tombstone then Some(System.currentTimeMillis()) else None,
          cancelRequestedBy = if tombstone then Some("the user panel") else None,
          archivedAt = if archived then Some(System.currentTimeMillis()) else None,
          restartInterruptedAt = if restartInterrupted then Some(System.currentTimeMillis()) else None,
          projectWorkspace = projectWs
        )
      )
    )

  private def seedTranscript(resources: SharedResources, id: String, turns: List[String]): IO[Unit] =
    resources.sessionStore.durableSwapMessages(
      id,
      turns.zipWithIndex.map { case (t, i) =>
        nebflow.shared.Message(
          if i % 2 == 0 then nebflow.shared.MessageRole.User else nebflow.shared.MessageRole.Assistant,
          Left(t)
        )
      }
    )

  /** 作用域图预置（活动区 N 个 pending 节点——「旧链」的机械替身）。 */
  private def seedScope(ws: String, delegateId: String, nodeIds: List[String]): IO[os.Path] =
    IO.blocking {
      val dir = nebflow.core.project.ScopeHandover.scopeDir(ws, delegateId)
      os.makeDir.all(dir)
      val state = FlowMapState(
        project = "stand",
        updatedAt = 0L,
        nodes = nodeIds
          .map(nid =>
            nid -> NodeDef(id = nid, name = s"node-$nid", agent = "nebflow", createdAt = 0L, status = "pending")
          )
          .toMap
      )
      os.write.over(dir / "flow-map.json", state.asJson.noSpaces)
      dir
    }

  private def scopeNodes(ws: String, delegateId: String): List[String] =
    val dir = nebflow.core.project.ScopeHandover.scopeDir(ws, delegateId)
    val p = dir / "flow-map.json"
    if !os.exists(p) then Nil
    else
      SessionResumption.openNodeCount(dir) // sanity: 非终态计数可读
      io.circe.parser
        .parse(os.read(p))
        .toOption
        .flatMap(_.hcursor.get[Map[String, io.circe.Json]]("nodes").toOption)
        .map(_.keys.toList.sorted)
        .getOrElse(Nil)

  private def transcriptText(resources: SharedResources, id: String): IO[String] =
    resources.sessionStore.loadMessagesForSession(id).map(_.map(_.textContent).mkString("\n"))

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 50.millis)(cond: IO[Boolean]): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError(s"waitUntil: condition not met within $timeout"))
          else IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  // ══════════════════════════════════════════════════════════════════════
  // A. 显示名去叠加
  // ══════════════════════════════════════════════════════════════════════

  test("A1 剥前缀单点：continuation 三形态 + 叠加 N 代 + 幂等 + 剥空回落") {
    val base = "大版本批（作者令 2026-11-03 00:16 逐字）：重启之后你就派发一个大版本"
    // ① 旧派生腿的前缀形态（叠加源）
    assertEquals(SessionResumption.displayTitle(s"continuation: $base"), base)
    // ② 叠加四代（作者实证形态）
    val stacked = List.fill(4)("continuation:").foldRight(base)((p, acc) => s"$p $acc")
    assertEquals(SessionResumption.displayTitle(stacked), base)
    assert(!SessionResumption.hasContinuationMark(SessionResumption.displayTitle(stacked)))
    // ③ 方括号 brief 形态
    val bracket =
      "[continuation of delegate neo-abc1234 — the instance finished; this supplement is your new primary instruction] " + base
    assertEquals(SessionResumption.displayTitle(bracket), base)
    // ④ 无方括号形态
    assertEquals(
      SessionResumption.displayTitle("continuation of delegate delegate-nebflow-bb1a8bbb " + base),
      base
    )
    // ⑤ 幂等
    val once = SessionResumption.stripContinuationPrefix(stacked)
    assertEquals(SessionResumption.stripContinuationPrefix(once), once)
    // ⑥ 干净名逐字不动
    assertEquals(SessionResumption.displayTitle(base), base)
    // ⑦ 剥空 ⇒ 回落（绝不给空名）
    assertEquals(SessionResumption.displayTitle("continuation:", "fallback-title"), "fallback-title")
  }

  test("repeated Mail to a cancelled identity never derives a replacement or moves its scope") {
    val system = ActorSystem("cancelled-mail-repeat")
    val id = "delegate-nebflow-cancel-repeat"
    val ws = workspaceOf("cancel-repeat")
    val exercise = for
      resources <- mkResources(system, hang = false)
      _ <- seedRecord(id, "parent", ws, "original title", tombstone = true)
      _ <- seedTranscript(resources, id, List("saved original task", "saved partial result"))
      _ <- seedScope(ws.toString, id, List("n1", "n2"))
      before <- IO.blocking(DelegateRegistry.list().map(_.id).toSet)
      outcomes <- List("first", "second", "third").traverse(mailToDelegate(resources, system, "parent", id, _))
      after <- IO.blocking(DelegateRegistry.list().map(_.id).toSet)
      live <- resources.agentRegistry.get
    yield
      assert(outcomes.forall(_.left.toOption.exists(_.message.contains("DELEGATE_CANCELLED"))))
      assertEquals(after, before)
      assert(!live.contains(id))
      assertEquals(scopeNodes(ws.toString, id), List("n1", "n2"))
      assert(!SessionResumption.hasOwnerMigration(id, None))
    exercise.guarantee(system.stopAll)
  }

  // ══════════════════════════════════════════════════════════════════════
  // B. 原位恢复优先
  // ══════════════════════════════════════════════════════════════════════

  test("concurrent continuation shares one startup lease and never multiplies the original actor") {
    val system = ActorSystem("concurrent-continuation")
    val id = "delegate-nebflow-concurrent-mail"
    val ws = workspaceOf("concurrent-mail")
    val exercise = for
      resources <- mkResources(system, hang = true)
      _ <- seedRecord(id, "parent", ws, "original persistent owner")
      _ <- seedTranscript(resources, id, List("saved original task", "saved partial result"))
      outcomes <- (1 to 12).toList.parTraverse(i => mailToDelegate(resources, system, "parent", id, s"supplement $i"))
      _ <- waitUntil(10.seconds)(IO(!resources.llm.asInstanceOf[StubLlm].requests.isEmpty))
      _ = assert(outcomes.exists(_.isRight))
      _ = assertEquals(resources.llm.asInstanceOf[StubLlm].requests.size(), 1)
      _ = assertEquals(
        nebflow.core.delegate.ChainStore
          .readSync(nebflow.core.delegate.ChainStore.keyFor(None, id))
          .toList
          .flatMap(_.entries)
          .count(_.kind == "delegate-resume-in-place"),
        1
      )
      _ = assertEquals(DelegateRegistry.list().count(_.id == id), 1)
      live <- resources.agentRegistry.get
      _ = assert(live.contains(id))
    yield ()
    exercise.guarantee(system.stopAll)
  }

  test("same-identity continuation delivers reference media together with the verified history and handoff") {
    val system = ActorSystem("continuation-media")
    val id = "delegate-nebflow-continuation-media"
    val ws = workspaceOf("media")
    val image = nebflow.shared.ContentBlock.Image("fixture-image", "image/png")
    val exercise = for
      resources <- mkResources(system, hang = true)
      _ <- seedRecord(id, "parent", ws, "media continuation")
      _ <- seedTranscript(resources, id, List("saved original task", "saved partial result"))
      record = DelegateRegistry.find(id).get
      result <- SessionResumption.resumeInPlace(
        SessionResumption.Deps(resources, system, None),
        record,
        "Continue with this reference",
        Some(List(nebflow.shared.ContentBlock.Text("Continue with this reference"), image))
      )
      _ = assert(result.isRight, result.toString)
      _ <- waitUntil(10.seconds)(IO(!resources.llm.asInstanceOf[StubLlm].requests.isEmpty))
      request = resources.llm.asInstanceOf[StubLlm].requests.iterator().asScala.toList.head
      _ = assert(request.messages.exists(_.textContent.contains("saved original task")))
      _ = assert(request.messages.exists(_.textContent.contains("Continue with this reference")))
      _ = assert(request.messages.exists(_.content.toOption.exists(_.contains(image))))
    yield ()
    exercise.guarantee(system.stopAll)
  }

  test("partial continuation failure stops its child and retains the supplement for manual recovery") {
    val system = ActorSystem("partial-continuation")
    val id = "delegate-nebflow-partial-continuation"
    val ws = workspaceOf("partial-continuation")
    val exercise = for
      children <- Ref.of[IO, List[nebflow.actor.ActorPath]](Nil)
      failing = new ActorSystem:
        def localDevice = system.localDevice
        def spawn[M](
          behavior: Behavior[M],
          name: String,
          ttl: Option[FiniteDuration] = None
        ): IO[nebflow.actor.ActorRef[M]] =
          if name.endsWith("-adapter") then
            IO.raiseError(new IllegalStateException("simulated continuation adapter failure"))
          else system.spawn(behavior, name, ttl).flatTap(ref => children.update(_ :+ ref.path))
        def resolve[M](path: String) = system.resolve[M](path)
        def stop(ref: nebflow.actor.ActorRef[?]) = system.stop(ref)
        def stopAll = system.stopAll
        def isAlive(path: nebflow.actor.ActorPath) = system.isAlive(path)
        def watch(watcher: nebflow.actor.ActorPath, target: nebflow.actor.ActorPath) = system.watch(watcher, target)
        def unwatch(watcher: nebflow.actor.ActorPath, target: nebflow.actor.ActorPath) = system.unwatch(watcher, target)
        def deadLetterCount = system.deadLetterCount
        def replayWatches = system.replayWatches
      resources <- mkResources(failing, hang = true)
      _ <- seedRecord(id, "parent", ws, "original continuation")
      // 过时测试修复（2026-11-15）：run 桥后真实派发的 delegate 都有任务行，
      // DelegateWakeLeg 的执行窗重建按行取数（无行 = 无法唤醒的诚实拒绝）。
      // 夹具补播种无 runId 的 legacy 行（recordTask 契约禁 runId 行直写），
      // 使「冻结 → 面板唤醒」可达性与生产行形态一致。
      _ <- resources.subAgentTaskStore.recordTask(SubAgentTask(
        taskId = id,
        parentSessionId = "parent",
        agentName = "nebflow",
        prompt = "original continuation",
        description = "original continuation",
        status = "running",
        retryCount = 0,
        spawnedAt = System.currentTimeMillis(),
        completedAt = None,
        lastError = None,
        source = "delegate"
      ))
      _ <- seedTranscript(resources, id, List("saved original task", "saved partial result"))
      result <- mailToDelegate(resources, failing, "parent", id, "continue with the wiring")
      _ = assert(result.toOption.exists(_.contains("MANUAL_RECOVERY_REQUIRED")), result.toString)
      paths <- children.get
      _ = assertEquals(paths.size, 1)
      alive <- paths.traverse(system.isAlive)
      _ = assert(alive.forall(!_))
      _ = assert(resources.llm.asInstanceOf[StubLlm].requests.isEmpty)
      _ = assert(DelegateRegistry.isFrozen(id))
      pending <- nebflow.core.mail.SessionMailbox.scanPending(id)
      _ = assertEquals(pending.map(_.event.payload), List("continue with the wiring"))
      row <- resources.subAgentTaskStore.findByTaskId(id)
      // 过时测试修复（2026-11-15）：续跑失败腿随 run 桥接/工作台退役分叉
      // （SessionResumption.resumeAdmitted 的 failed 路径）——failed 腿只在
      // `admitted` 已入账任务行时写台账：有 runId ⇒ completeRun("failed",
      // incomplete=true)，无 runId ⇒ transitionObserved("frozen")。本夹具
      // （registry 记录 + 保存转录）已补播 legacy 任务行（无 runId）⇒ 失败腿
      // 必落 transitionObserved("frozen")；行断言按双分支合同容忍 frozen/failed
      // （failed 支 = run 腿任务的合同面，本夹具不可达但属同一合同），缺位由
      // 下方 wake.isRight 腿兜住。冻结本体（registry parkFrozen + 补件保留 +
      // 子停）与行解耦，上方 isFrozen/scanPending 断言已钉。
      _ = assert(
        row.forall(r => r.status == "frozen" || r.status == "failed"),
        s"若存在任务行，续跑启动失败后应为 frozen（无 runId 腿）或 failed（run 腿），实测：${row.map(_.status)}"
      )
      recovered = resources.copy(actorSystem = system)
      woke <- nebflow.agent.DelegateWakeLeg.wake(recovered, None, id, "Resume from saved context", "panel-wake")
      _ = assert(woke.isRight, woke.toString)
      _ <- waitUntil(10.seconds)(IO(!resources.llm.asInstanceOf[StubLlm].requests.isEmpty))
      request = resources.llm.asInstanceOf[StubLlm].requests.iterator().asScala.toList.head
      _ = assert(request.messages.exists(_.textContent.contains("saved original task")))
      _ = assert(request.messages.exists(_.textContent.contains("continue with the wiring")))
    yield ()
    exercise.guarantee(system.stopAll)
  }

  test("B1 原位续：Mail 到终态 delegate ⇒ 同一 sessionId 活着、转录追加（不新建、不重写）、回执说破") {
    val system = ActorSystem("session-resumption-b1")
    val parentSid = "b1-parent"
    val id = "delegate-nebflow-b1inpl01"
    val ws = workspaceOf("b1")
    for
      resources <- mkResources(system, hang = false)
      parentEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- system.spawn(mkRecording(parentEvents), "b1-parent-rec")
      _ <- resources.agentRegistry.update(
        _ + (parentSid -> AgentRecord(parentSid, parentRef, AgentKind.Root, parentSid, None))
      )
      _ <- seedRecord(id, parentSid, ws, "原位续批")
      _ <- seedTranscript(resources, id, List("original brief", "partial work"))
      idsBefore <- IO.blocking(DelegateRegistry.list().map(_.id).toSet)
      res <- mailToDelegate(resources, system, parentSid, id, "supplement: also clean the worktree")
      // 同一 id 必须有活体（原位续的证据）
      live <- waitUntil(30.seconds)(resources.agentRegistry.get.map(_.contains(id))) *>
        resources.agentRegistry.get.map(_.get(id))
      // 转录追加：nudge + 补充信息 + 本轮回复都落在**同一个** session 转录里
      _ <- waitUntil(60.seconds)(
        transcriptText(resources, id).map(t => t.contains("also clean the worktree"))
      )
      text <- transcriptText(resources, id)
      idsAfter <- IO.blocking(DelegateRegistry.list().map(_.id).toSet)
      rec <- IO.blocking(DelegateRegistry.find(id))
      _ <- system.stopAll
    yield
      val receipt = res match
        case Right(r) => r
        case Left(e) => fail(s"in-place resume must proceed, got: ${e.message}")
      assert(receipt.contains("RESUMED IT IN PLACE"), receipt)
      assert(receipt.contains("same session id"), receipt)
      assert(live.exists(_.kind == AgentKind.Delegate), "the SAME session id is live again as a Delegate")
      // 转录追加：旧两条仍在 + 新输入在 + 本轮回复在（绝不是「新会话只带一条新输入」）
      assert(text.contains("original brief"), s"prior history must survive, got: $text")
      assert(text.contains("supplement: also clean the worktree"), s"supplement must land, got: $text")
      assert(text.contains("RESUMED_TURN_DONE"), s"the resumed turn must run to completion, got: $text")
      assert(SessionResumption.stripContinuationPrefix("") == "" && text.split("\n").length >= 3)
      // 零新会话：注册表 id 集不变
      assertEquals(
        idsAfter.diff(idsBefore),
        Set.empty[String],
        s"no derived session may appear: ${idsAfter.diff(idsBefore)}"
      )
      // 原位续不落血缘（同一身份）
      assertEquals(rec.flatMap(_.resumedFrom), None)
    end for
  }

  test("B2 原位续保图：作用域图同 id 重挂 ⇒ 原节点全部仍可见（NodeList 面可读）、引擎归属不变") {
    val system = ActorSystem("session-resumption-b2")
    val parentSid = "b2-parent"
    val id = "delegate-nebflow-b2inpl01"
    val ws = workspaceOf("b2")
    for
      resources <- mkResources(system, hang = true) // 回合永挂 ⇒ 活体读数稳定
      parentEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- system.spawn(mkRecording(parentEvents), "b2-parent-rec")
      _ <- resources.agentRegistry.update(
        _ + (parentSid -> AgentRecord(parentSid, parentRef, AgentKind.Root, parentSid, None))
      )
      _ <- seedRecord(id, parentSid, ws, "原位续保图批")
      _ <- seedTranscript(resources, id, List("original brief"))
      _ <- seedScope(ws.toString, id, List("n1", "n2", "n3"))
      res <- mailToDelegate(resources, system, parentSid, id, "continue the batch")
      _ = assert(res.isRight, s"resume must proceed: ${res.swap.map(_.message).getOrElse("")}")
      host <- DelegateFlowHosts.get(id)
      // 会话工具面读法（NodeList/NodeEdit 的解析单点）
      runtime <- NodeTools.resolveRuntime(None, delegateCtx(resources, system, id))
      rt = host.getOrElse(fail("the resumed delegate must have its scope host remounted"))
      snapshot <- rt.store.snapshot
      _ <- system.stopAll
    yield
      assertEquals(rt.delegateId, id, "scope host bound to the SAME delegate id (ownership unchanged)")
      assertEquals(rt.engine.rootSessionId, id, "engine self-root unchanged ⇒ node events route to this session")
      assertEquals(
        snapshot.nodes.keys.toList.sorted,
        List("n1", "n2", "n3"),
        "all original nodes still in the same map"
      )
      runtime match
        case Right(face) =>
          assertEquals(face.project.name, rt.engine.projectName)
        case Left(err) => fail(s"the delegate scope face must resolve after in-place resume: $err")
    end for
  }

  test("B3 原位续事件可达：节点通知注入命中同一会话并被消费（转录可见）") {
    val system = ActorSystem("session-resumption-b3")
    val parentSid = "b3-parent"
    val id = "delegate-nebflow-b3inpl01"
    val ws = workspaceOf("b3")
    for
      gate <- Deferred[IO, Unit]
      resources <- mkResources(system, hang = false, gate = Some(gate))
      parentEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- system.spawn(mkRecording(parentEvents), "b3-parent-rec")
      _ <- resources.agentRegistry.update(
        _ + (parentSid -> AgentRecord(parentSid, parentRef, AgentKind.Root, parentSid, None))
      )
      _ <- seedRecord(id, parentSid, ws, "原位续事件批")
      _ <- seedTranscript(resources, id, List("original brief"))
      _ <- seedScope(ws.toString, id, List("n1"))
      res <- mailToDelegate(resources, system, parentSid, id, "resume the batch")
      _ = assert(res.isRight, s"resume must proceed: ${res.swap.map(_.message).getOrElse("")}")
      // The resumed turn is held at the gate, so the session is live in the registry. A plain-text turn
      // end is the completion delivery and releases the session, hence the event has to arrive while
      // the turn is in flight.
      _ <- waitUntil(60.seconds)(resources.agentRegistry.get.map(_.contains(id)))
      // Node completion event (DelegateNotify = the injection channel of NodeEngine).
      reached <- DelegateNotify.inject(resources.agentRegistry, id, "NODE n1 completed: result ready")
      _ = assert(reached, "the node notification MUST reach the resumed delegate session (registry has a live actor)")
      _ <- gate.complete(())
      // Consumption: the injected line and the turn text land in the same session transcript.
      _ <- waitUntil(60.seconds)(transcriptText(resources, id).map(_.contains("NODE n1 completed")))
      text <- transcriptText(resources, id)
      _ <- system.stopAll
    yield assert(
      text.contains("NODE n1 completed"),
      s"the node event must be consumed by the resumed session, got: $text"
    )
    end for
  }

  test("restart-interrupted Mail is retained without clearing the gate or starting an actor") {
    val system = ActorSystem("restart-held-mail")
    val id = "delegate-nebflow-restart-held"
    val ws = workspaceOf("restart-held")
    val exercise = for
      resources <- mkResources(system, hang = true)
      _ <- seedRecord(id, "parent", ws, "interrupted", restartInterrupted = true)
      _ <- seedTranscript(resources, id, List("original task", "partial result"))
      result <- mailToDelegate(resources, system, "parent", id, "supplement retained for manual recovery")
      pending <- nebflow.core.mail.SessionMailbox.scanPending(id)
      live <- resources.agentRegistry.get
      record <- IO.blocking(DelegateRegistry.find(id))
    yield
      assert(result.toOption.exists(_.contains("MANUAL_RECOVERY_REQUIRED")))
      assertEquals(pending.map(_.event.payload), List("supplement retained for manual recovery"))
      assert(!live.contains(id))
      assert(record.exists(_.restartInterruptedAt.nonEmpty))
    exercise.guarantee(system.stopAll)
  }

  test("missing history and unresolved tool results refuse Mail without a replacement or execution claim") {
    val system = ActorSystem("unverified-continuation")
    val id = "delegate-nebflow-unverified-mail"
    val ws = workspaceOf("unverified-mail")
    val exercise = for
      resources <- mkResources(system, hang = true)
      _ <- seedRecord(id, "parent", ws, "unverified original task")
      before <- IO.blocking(DelegateRegistry.list().map(_.id).toSet)
      missing <- mailToDelegate(resources, system, "parent", id, "do not replace missing history")
      unknown = List(
        nebflow.shared.Message(nebflow.shared.MessageRole.User, Left("original task")),
        nebflow.shared.Message(
          nebflow.shared.MessageRole.Assistant,
          Right(List(nebflow.shared.ContentBlock.ToolUse("write-outcome-unknown", "Bash", JsonObject.empty)))
        )
      )
      _ <- resources.sessionStore.durableSwapMessages(id, unknown)
      unresolved <- mailToDelegate(resources, system, "parent", id, "do not replay the write")
      after <- IO.blocking(DelegateRegistry.list().map(_.id).toSet)
      saved <- resources.sessionStore.loadMessagesForSession(id)
      live <- resources.agentRegistry.get
    yield
      assert(missing.left.toOption.exists(_.message.contains("RECOVERY_UNVERIFIED")))
      assert(unresolved.left.toOption.exists(_.message.contains("RECOVERY_UNVERIFIED")))
      assertEquals(after, before)
      assertEquals(saved, unknown)
      assert(!live.contains(id))
    exercise.guarantee(system.stopAll)
  }

  test("B6 delegate 会话（含派生实例）可 Mail 根会话——派生批的中间读数/阻断报告不再无门") {
    val system = ActorSystem("session-resumption-b6")
    val rootSid = "b6-root-sid"
    val derivedId = "delegate-nebflow-b6deriv01"
    for
      resources <- mkResources(system, hang = true)
      rootEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      rootRef <- system.spawn(mkRecording(rootEvents), "b6-root-rec")
      _ <- resources.agentRegistry.update(
        _ + (rootSid -> AgentRecord(rootSid, rootRef, AgentKind.Root, rootSid, None))
      )
      res <- MailTool.call(
        JsonObject("address" -> "Nebula".asJson, "message" -> "intermediate reading: 3/16 nodes done".asJson),
        rootCtx(resources, system, derivedId, rootSid = Some(rootSid), agent = "nebflow")
      )
      events <- rootEvents.get
      _ <- system.stopAll
    yield
      val receipt = res match
        case Right(r) => r
        case Left(e) => fail(s"a delegate MUST be able to mail the root session (face defect ⑧), got: ${e.message}")
      assert(receipt.contains("Message sent to Nebula"), receipt)
      val injected = events.collect { case i: AgentCommand.ImmediateInput => i }
      assertEquals(injected.size, 1, s"exactly one root injection expected, got: $events")
      assert(injected.head.text.contains("intermediate reading"), injected.head.text)
    end for
  }

  // ══════════════════════════════════════════════════════════════════════
  // ③ 孤悬终点巡检（不得存在「节点挂在终态会话名下且无接管记录」）
  // ══════════════════════════════════════════════════════════════════════

  test("B7 孤悬巡检：取消态会话名下仍有非终态节点且无 ownerMigration ⇒ 疑点可判；移交后消失") {
    val system = ActorSystem("session-resumption-b7")
    val parentSid = "b7-parent"
    val orphanId = "delegate-nebflow-b7orph01"
    val healedId = "delegate-nebflow-b7heal01"
    val ws = workspaceOf("b7")
    for
      resources <- mkResources(system, hang = true)
      // 疑点：取消墓碑 + 名下 2 个非终态节点 + 零接管记录
      _ <- seedRecord(orphanId, parentSid, ws, "孤悬疑点批", tombstone = true)
      _ <- seedScope(ws.toString, orphanId, List("x1", "x2"))
      // 正控：已移交（ownerMigration 在案）——同名态但不算疑点
      _ <- seedRecord(healedId, parentSid, ws, "已移交批", tombstone = true)
      _ <- seedScope(ws.toString, healedId, List("y1"))
      _ <- IO.blocking(
        nebflow.core.delegate.ChainStore.appendSync(
          healedId,
          "owner-migration",
          io.circe.Json.obj("from" -> healedId.asJson, "to" -> "delegate-nebflow-b7next01".asJson)
        )
      )
      suspects <- SessionResumption.orphanScopeSuspects()
      _ <- system.stopAll
    yield
      val ids = suspects.map(_.delegateId)
      assert(ids.contains(orphanId), s"the orphaned scope must be flagged, got: $suspects")
      assert(!ids.contains(healedId), s"a handed-over scope must NOT be flagged, got: $suspects")
      assertEquals(suspects.find(_.delegateId == orphanId).map(_.openNodes), Some(2))
    end for
  }

end SessionResumptionSpec
