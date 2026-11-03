package nebflow.core.tools

import cats.effect.{IO, Ref}
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

/**
 * 会话续命的名与位修正批（2026-11-03 作者 16:48/16:51/16:53 三段报障）机械断言。
 *
 * A 部分（显示名去叠加）：`continuation:` 前缀逐代叠加到不可辨识（四代会话链实证
 * bb1a8bbb → 556fef54 → 5dcbf6c5 → 836a475e）。判据 = 剥前缀单点幂等 + 四代派生链
 * 逐代显示名稳定、零 "continuation" 字样。
 *
 * B 部分（原位恢复优先，结构性必需）：Mail 到「已终态但历史完好」的 delegate 必须
 * **沿用原 sessionId**（转录追加、作用域图同 id 重挂 ⇒ 链节点归属与事件路由不变）；
 * 仅 cancel 墓碑 / 转录损坏才派生新 id，且派生必须落 `resumedFrom` 并**移交**旧
 * 作用域的图（`ownerMigration` 审计）——绝不出现「节点挂在死会话名下、活实例收不到
 * 事件」的孤悬态（缺陷⑨）。
 *
 * ⑧ 地址面：delegate 会话（含续命派生实例）必须保留 Nebula 腿——否则连向根会话
 * 汇报的通道都没有（作者截图实证）。
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
    os.remove.all(tempRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    // 作用域宿主是进程内全局 Ref——逐用例清册防跨用例串图。
    DelegateFlowHosts.clear.unsafeRunSync()

  override def afterEach(context: munit.AfterEach): Unit =
    DelegateFlowHosts.clear.unsafeRunSync()

  // ── fixtures ────────────────────────────────────────────────────────────

  /** hang=true ⇒ 回合永挂（钉活体读数）；false ⇒ 回合正常收尾（钉转录落盘）。 */
  private class StubLlm(hang: Boolean) extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected"))
    def sendStream(
        req: LlmRequest,
        onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      if hang then Stream.never[IO]
      else Stream(StreamChunk.TextDelta("RESUMED_TURN_DONE"), StreamChunk.Done(None, None))

  private def mkResources(system: ActorSystem, hang: Boolean): IO[SharedResources] =
    for
      dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
    yield SharedResources(
      llm = new StubLlm(hang),
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

  private def rootCtx(resources: SharedResources, system: ActorSystem, sid: String, rootSid: Option[String] = None, agent: String = "Nebula") =
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
    resources.sessionStore.saveMessagesForSession(
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
          .map(nid => nid -> NodeDef(id = nid, name = s"node-$nid", agent = "nebflow", createdAt = 0L, status = "pending"))
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

  test("A2 四代连续派生链：显示名逐代稳定、零 continuation 字样、resumedFrom 血缘成链、节点跨四代存活") {
    val system = ActorSystem("session-resumption-a2")
    val parentSid = "a2-parent"
    val gen1 = "delegate-nebflow-a2gen001"
    val base = "UI 大版本批（作者令 2026-11-03 00:16 逐字）：重启之后你就派发一个大版本"
    val ws = workspaceOf("a2")
    for
      resources <- mkResources(system, hang = false)
      parentEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- system.spawn(mkRecording(parentEvents), "a2-parent-rec")
      _ <- resources.agentRegistry.update(
        _ + (parentSid -> AgentRecord(parentSid, parentRef, AgentKind.Root, parentSid, None))
      )
      _ <- seedRecord(gen1, parentSid, ws, base, tombstone = true) // 逐代强制派生腿
      _ <- seedTranscript(resources, gen1, List("original brief", "work in progress"))
      _ <- seedScope(ws.toString, gen1, List("n1", "n2"))
      // 逐代推进：每代「Mail → 墓碑 → 摘活体」强制走派生腿，共 4 代（≥4 代断言）。
      gen2 <- mailAndTombstone(resources, system, parentSid, gen1, "gen2 supplement")
      gen3 <- mailAndTombstone(resources, system, parentSid, gen2, "gen3 supplement")
      gen4 <- mailAndTombstone(resources, system, parentSid, gen3, "gen4 supplement")
      _ <- waitUntil(60.seconds)(IO.blocking(DelegateRegistry.find(gen4).isDefined))
      recs <- IO.blocking(List(gen2, gen3, gen4).flatMap(id => DelegateRegistry.find(id)))
      tasks <- resources.subAgentTaskStore.loadTasks(parentSid)
      finalNodes = scopeNodes(ws.toString, gen4)
      _ <- system.stopAll
    yield
      // ① 显示名逐代稳定：四代全部等于第一代的 title（零 continuation 字样）
      assertEquals(recs.map(_.title).distinct, List(base), s"titles per generation: ${recs.map(_.title)}")
      assert(recs.forall(r => !SessionResumption.hasContinuationMark(r.title)), "no continuation mark in any generation")
      // ② 血缘成链：gen2←gen1, gen3←gen2, gen4←gen3
      assertEquals(recs.map(_.resumedFrom), List(Some(gen1), Some(gen2), Some(gen3)))
      // ③ 任务行同名（面板行数据源同口径）
      assertEquals(
        List(gen2, gen3, gen4).flatMap(id => tasks.find(_.taskId == id).map(_.description)).distinct,
        List(base)
      )
      // ④ 链节点跨四代存活（每次派生都做了作用域移交）
      assertEquals(finalNodes, List("n1", "n2"))
  }

  /** Mail → 等新派生落地 → 打墓碑 + 摘活体（模拟「已终态且取消」的下一轮）。 */
  private def mailAndTombstone(
    resources: SharedResources,
    system: ActorSystem,
    parentSid: String,
    currentId: String,
    message: String
  ): IO[String] =
    for
      _ <- resources.agentRegistry.update(_ - currentId) // 模拟进程死亡/终态：注册表无活体
      before <- IO.blocking(DelegateRegistry.list().map(_.id).toSet)
      res <- mailToDelegate(resources, system, parentSid, currentId, message)
      _ = assert(res.isRight, s"continuation must proceed: ${res.swap.map(_.message).getOrElse("")}")
      newId <- waitUntil(60.seconds)(
        IO.blocking(DelegateRegistry.list().map(_.id).toSet.diff(before)).map(_.nonEmpty)
      ) *> IO.blocking(DelegateRegistry.list().map(_.id).toSet.diff(before)).map(_.head)
      // 下一代推进前：本代打墓碑（派生腿的唯一合法触发面）+ 摘活体
      _ <- IO.blocking(DelegateRegistry.markCancelRequested(newId, "spec fixture"))
      _ <- waitUntil(60.seconds)(
        resources.sessionStore.loadMessagesForSession(newId).map(_.nonEmpty)
      ).attempt.void
      _ <- IO.sleep(300.millis)
    yield newId

  // ══════════════════════════════════════════════════════════════════════
  // B. 原位恢复优先
  // ══════════════════════════════════════════════════════════════════════

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
        case Left(e)  => fail(s"in-place resume must proceed, got: ${e.message}")
      assert(receipt.contains("RESUMED IT IN PLACE"), receipt)
      assert(receipt.contains("same session id"), receipt)
      assert(live.exists(_.kind == AgentKind.Delegate), "the SAME session id is live again as a Delegate")
      // 转录追加：旧两条仍在 + 新输入在 + 本轮回复在（绝不是「新会话只带一条新输入」）
      assert(text.contains("original brief"), s"prior history must survive, got: $text")
      assert(text.contains("supplement: also clean the worktree"), s"supplement must land, got: $text")
      assert(text.contains("RESUMED_TURN_DONE"), s"the resumed turn must run to completion, got: $text")
      assert(SessionResumption.stripContinuationPrefix("") == "" && text.split("\n").length >= 3)
      // 零新会话：注册表 id 集不变
      assertEquals(idsAfter.diff(idsBefore), Set.empty[String], s"no derived session may appear: ${idsAfter.diff(idsBefore)}")
      // 原位续不落血缘（同一身份）
      assertEquals(rec.flatMap(_.resumedFrom), None)
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
      assertEquals(snapshot.nodes.keys.toList.sorted, List("n1", "n2", "n3"), "all original nodes still in the same map")
      runtime match
        case Right(face) =>
          assertEquals(face.project.name, rt.engine.projectName)
        case Left(err) => fail(s"the delegate scope face must resolve after in-place resume: $err")
  }

  test("B3 原位续事件可达：节点通知注入命中同一会话并被消费（转录可见）") {
    val system = ActorSystem("session-resumption-b3")
    val parentSid = "b3-parent"
    val id = "delegate-nebflow-b3inpl01"
    val ws = workspaceOf("b3")
    for
      resources <- mkResources(system, hang = false)
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
      // 第一轮收尾（矩阵未申报 = NudgeIdle，活体留存）
      _ <- waitUntil(60.seconds)(transcriptText(resources, id).map(_.contains("RESUMED_TURN_DONE")))
      _ <- IO.sleep(500.millis)
      // 节点完成事件（DelegateNotify 单点 = NodeEngine 的注入通道）
      reached <- DelegateNotify.inject(resources.agentRegistry, id, "NODE n1 completed: result ready")
      _ = assert(reached, "the node notification MUST reach the resumed delegate session (registry has a live actor)")
      // 消费面：注入行与回合文本落在同一会话转录
      _ <- waitUntil(60.seconds)(transcriptText(resources, id).map(_.contains("NODE n1 completed")))
      text <- transcriptText(resources, id)
      _ <- system.stopAll
    yield assert(text.contains("NODE n1 completed"), s"the node event must be consumed by the resumed session, got: $text")
  }

  test("B4 cancel 墓碑 ⇒ 派生新 id + resumedFrom + 作用域移交（节点搬到新 scope、旧图清空、ownerMigration 在案）") {
    val system = ActorSystem("session-resumption-b4")
    val parentSid = "b4-parent"
    val oldId = "delegate-nebflow-b4old001"
    val ws = workspaceOf("b4")
    for
      resources <- mkResources(system, hang = false)
      parentEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- system.spawn(mkRecording(parentEvents), "b4-parent-rec")
      _ <- resources.agentRegistry.update(
        _ + (parentSid -> AgentRecord(parentSid, parentRef, AgentKind.Root, parentSid, None))
      )
      _ <- seedRecord(oldId, parentSid, ws, "墓碑派生批", tombstone = true)
      _ <- seedTranscript(resources, oldId, List("original brief", "partial work"))
      _ <- seedScope(ws.toString, oldId, List("n1", "n2"))
      before <- IO.blocking(DelegateRegistry.list().map(_.id).toSet)
      res <- mailToDelegate(resources, system, parentSid, oldId, "supplement after cancel")
      newId <- waitUntil(60.seconds)(
        IO.blocking(DelegateRegistry.list().map(_.id).toSet.diff(before)).map(_.nonEmpty)
      ) *> IO.blocking(DelegateRegistry.list().map(_.id).toSet.diff(before)).map(_.min)
      derived <- IO.blocking(DelegateRegistry.find(newId))
      oldNodes = scopeNodes(ws.toString, oldId)
      newNodes = scopeNodes(ws.toString, newId)
      migration = SessionResumption.hasOwnerMigration(oldId, None)
      _ <- system.stopAll
    yield
      val receipt = res match
        case Right(r) => r
        case Left(e)  => fail(s"tombstoned continuation must derive, got: ${e.message}")
      assert(receipt.contains("could not be resumed in place"), receipt)
      assert(receipt.contains(s"resumedFrom=$oldId"), receipt)
      assert(newId != oldId, "a tombstoned session must NOT be resumed in place")
      // ⑨ 血缘 + 移交审计
      assertEquals(derived.flatMap(_.resumedFrom), Some(oldId), "the derived record carries resumedFrom")
      assert(!SessionResumption.hasContinuationMark(derived.map(_.title).getOrElse("")), "derived title stays clean")
      assertEquals(newNodes, List("n1", "n2"), "the chain moved to the derived session (nodes reachable there)")
      assertEquals(oldNodes, Nil, "the old scope no longer claims the nodes (no double ownership)")
      assert(migration, "an ownerMigration record must exist for the handed-over chain")
  }

  test("B5 重启 TERM 终态不是墓碑 ⇒ 原位续 + 打断旗摘除（不派生）") {
    val system = ActorSystem("session-resumption-b5")
    val parentSid = "b5-parent"
    val id = "delegate-nebflow-b5term01"
    val ws = workspaceOf("b5")
    for
      resources <- mkResources(system, hang = true)
      parentEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- system.spawn(mkRecording(parentEvents), "b5-parent-rec")
      _ <- resources.agentRegistry.update(
        _ + (parentSid -> AgentRecord(parentSid, parentRef, AgentKind.Root, parentSid, None))
      )
      _ <- seedRecord(id, parentSid, ws, "重启打断批", restartInterrupted = true)
      _ <- seedTranscript(resources, id, List("original brief", "work in progress"))
      before <- IO.blocking(DelegateRegistry.list().map(_.id).toSet)
      res <- mailToDelegate(resources, system, parentSid, id, "carry on after the restart")
      live <- waitUntil(30.seconds)(resources.agentRegistry.get.map(_.contains(id))) *>
        resources.agentRegistry.get.map(_.contains(id))
      after <- IO.blocking(DelegateRegistry.list().map(_.id).toSet)
      rec <- IO.blocking(DelegateRegistry.find(id))
      _ <- system.stopAll
    yield
      val receipt = res match
        case Right(r) => r
        case Left(e)  => fail(s"a restart-TERM terminal must resume in place, got: ${e.message}")
      assert(receipt.contains("RESUMED IT IN PLACE"), receipt)
      assert(live, "the same session id must be live again")
      assertEquals(after.diff(before), Set.empty[String], "restart TERM is not a tombstone — no derivation")
      assertEquals(rec.flatMap(_.restartInterruptedAt), None, "the interruption flag is cleared on resume")
  }

  // ══════════════════════════════════════════════════════════════════════
  // ⑧ 地址面：delegate 的 Nebula 腿
  // ══════════════════════════════════════════════════════════════════════

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
        case Left(e)  => fail(s"a delegate MUST be able to mail the root session (face defect ⑧), got: ${e.message}")
      assert(receipt.contains("Message sent to Nebula"), receipt)
      val injected = events.collect { case i: AgentCommand.ImmediateInput => i }
      assertEquals(injected.size, 1, s"exactly one root injection expected, got: $events")
      assert(injected.head.text.contains("intermediate reading"), injected.head.text)
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
  }

end SessionResumptionSpec
