package nebflow.core.project

import cats.effect.{IO, Ref}
import fs2.Stream
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, Behaviors}
import nebflow.agent.{AgentCommand, AgentKind, AgentLibrary, AgentRecord, SharedResources}
import nebflow.core.PathUtil
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, NodeEditTool, NodeMessageTool, ToolContext}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * failed 重激活放开验收（2026-09-07 批，作者 09:24 裁定③——推翻设计 §10.5
 * 「本批不放开」建议，Scope B 与失败通知接线同批交付）：
 *
 * - FR1 核心链路：failed 节点 + 实际编辑变更（task）→ reactivate → 重跑至完成。
 *   failed 版语义点（作者裁定①②）：blockCount→0 / blockedFeedback 清除 /
 *   notifySentAt 清除（重跑非语义阻塞轮次；下次真失败分发器再获通知——与
 *   NotifyDispatcherSpec ⑫「重激活后再通知」配套）。
 * - FR2 无实际变更 → 不重激活（failed 保持终态）。
 * - FR3 终态边界不回退：completed/cancelled 不可重激活（actualChange 也不复活）。
 * - FR4 NodeMessage 对 failed 终态仍拒收（NODE_TERMINAL_NO_MESSAGE 不回退）。
 * - FR5 out-absent quirk 锁定（actualChange quirk 修，2026-09-07）：编辑未传 out
 *   且其余字段原值重发 → parseOut(None)=None 不得与 out=Some 误判不等 → no-op
 *   不重激活（修前恒判 actualChange=true → 意外重激活）。
 * - FR6 反向锁定：显式传 out 且值变更 → 仍算 actualChange → 重激活（防修过头）。
 *
 * 失败驱动=死会话僵尸收敛（settleStaleRunningNodes → autoFailDeadRunning →
 * deliverFailed，NodeDeadSessionAutoReapSpec 同款）——确定性，不依赖 LLM 报错路径。
 * 注：failed 通知接线后，僵尸收敛会触发分发器会话 spawn（目标语义本身）——
 * FuncLlm 对分发器 prompt 回 "ok" 承接，不干扰断言。
 */
class NodeFailedReactivateSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 240.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-failed-reactivate"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  for agent <- List("test-agent", "project-dispatcher", "general") do
    os.makeDir.all(tempRoot / "agents" / agent)
    os.write.over(
      tempRoot / "agents" / agent / "agent.json",
      s"""{"name":"$agent","description":"failed-reactivate spec agent","tools":[],"category":"standalone"}"""
    )
    os.write.over(tempRoot / "agents" / agent / "system.md", s"# $agent\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  /** 按输入内容响应的 LLM：分发器 prompt → "ok"；指定任务 → 指定结果。 */
  private class FuncLlm(respond: String => IO[String]):
    val inputs: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)
    def handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(
          req: LlmRequest,
          onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
      ): Stream[IO, StreamChunk] =
        val text = req.messages.map(_.textContent).mkString("\n")
        Stream
          .eval(inputs.update(_ :+ text))
          .flatMap(_ => Stream.eval(respond(text)))
          .flatMap(reply => Stream(StreamChunk.TextDelta(reply), StreamChunk.Done(None, None)))

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

  private def mkCtx(res: SharedResources, system: ActorSystem, ws: String): ToolContext =
    ToolContext(
      projectRoot = ws,
      sessionId = Some("fr-spec-sid"),
      rootSessionId = Some("nebula-root"),
      sharedResources = Some(res),
      actorSystem = Some(system)
    )

  private def nodeEdit(input: Json, ctx: ToolContext): IO[Either[String, String]] =
    NodeEditTool.call(input.asObject.get, ctx).map(_.left.map(_.message))

  private def nodeMessage(input: Json, ctx: ToolContext): IO[Either[String, String]] =
    NodeMessageTool.call(input.asObject.get, ctx).map(_.left.map(_.message))

  private def waitUntil(timeout: FiniteDuration, every: Long = 50)(cond: IO[Boolean]): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError("waitUntil: condition not met in time"))
          else IO.sleep(every.millis) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  private def nodeInput(project: String, nodename: String, extra: (String, Json)*): Json =
    Json.obj(("project" -> Json.fromString(project)) :: ("nodename" -> Json.fromString(nodename)) :: extra.toList*)

  private def mountReal(name: String, ws: os.Path, system: ActorSystem, res: SharedResources): IO[ProjectRuntime] =
    val pd = ProjectDef(name = name, workspace = ws.toString, agentFile = (ws / "AGENTS.md").toString, createdAt = System.currentTimeMillis())
    ProjectRuntimeRegistry.mount(pd, system, res, None, "nebula-root")

  private def byName(rt: ProjectRuntime, name: String): IO[NodeDef] =
    rt.store.snapshot.map(_.nodes.values.find(_.name == name)).map {
      case Some(n) => n
      case None    => fail(s"node '$name' must exist")
    }

  private def waitStatus(rt: ProjectRuntime, name: String, statuses: Set[String]): IO[Unit] =
    waitUntil(30.seconds) {
      rt.store.snapshot.map(_.nodes.values.find(_.name == name)).flatMap {
        case Some(n) => IO.pure(statuses.contains(n.status))
        case None    => IO.pure(false)
      }
    }

  private def readAudit(ws: os.Path): IO[List[(String, String)]] =
    IO.blocking(os.read(ws / ".nebflow" / FlowMapEventLog.FileName))
      .map(_.linesIterator.toList.filter(_.trim.nonEmpty))
      .map(lines => lines.flatMap(l => io.circe.parser.parse(l).toOption.map(j =>
        (j.hcursor.get[String]("type").getOrElse(""), j.hcursor.get[String]("nodeId").getOrElse("")))))
      .handleError(_ => Nil)

  /** 种一个死会话 running 节点（settleStaleRunningNodes 驱动自动 failed）。 */
  private def seedZombie(rt: ProjectRuntime, id: String, nodeName: String, task: String, out: List[OutEdge],
                         description: Option[String] = None): IO[Unit] =
    rt.store.mutate { s =>
      s.copy(nodes = s.nodes + (id -> NodeDef(
        id = id, name = nodeName, agent = "general", task = Some(task), out = out,
        description = description,
        status = NodeLifecycle.Running,
        startedAt = Some(System.currentTimeMillis() - 3_600_000),
        createdAt = System.currentTimeMillis() - 3_600_000)))
    }.void

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  // ── FR1：failed + 实际变更 → reactivate → 重跑完成；轮次历史复位 ──────

  test("FR1: edit failed node (task change) → reactivated, round history reset, reruns to completion") {
    val ws = tempRoot / "ws-fr1"
    os.makeDir.all(ws)
    val system = ActorSystem(s"fr1-${scala.util.Random.nextInt(100000)}")
    val llm = FuncLlm(text =>
      if text.contains("任务分发器") then IO.pure("ok")
      else if text.contains("revived-task") then IO.pure("recovered-result")
      else IO.pure("unexpected-run"))
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountReal("fr1", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- seedZombie(rt, "n-fr1", "fr-node", "original-task", List(OutEdge.nebula))
      _ <- rt.engine.settleStaleRunningNodes()
      _ <- waitStatus(rt, "fr-node", Set(NodeLifecycle.Failed))
      // failed 通知接线：marker 必须已落（重激活清零的前置事实）
      _ <- waitUntil(20.seconds)(byName(rt, "fr-node").map(_.notifySentAt.isDefined))
      before <- byName(rt, "fr-node")
      // 实际变更（task 修订）→ reactivate
      editRes <- nodeEdit(nodeInput("fr1", "fr-node",
        "task" -> Json.fromString("revived-task"),
        "description" -> Json.fromString("failed reactivate spec node")), ctx)
      _ <- waitStatus(rt, "fr-node", Set(NodeLifecycle.Completed))
      after <- byName(rt, "fr-node")
      audit <- readAudit(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(editRes.exists(_.contains("reactivated from failed")), s"edit result must report failed reactivation, got: $editRes")
      assertEquals(before.status, NodeLifecycle.Failed, "precondition: node was failed")
      assertEquals(after.status, NodeLifecycle.Completed, "reactivated node reruns to completion")
      assertEquals(after.result, Some("recovered-result"), "rerun uses the REVISED task (new session output)")
      assertEquals(after.blockCount, 0, "failed reactivation resets blockCount (rerun is not a semantic block round)")
      assertEquals(after.blockedFeedback, None, "stale blockedFeedback cleared with the reset")
      assertEquals(after.notifySentAt, None, "notifySentAt cleared — next real failure re-notifies dispatcher (retry loop)")
      assert(audit.exists((t, id) => t == "reactivated" && id == "n-fr1"),
        s"reactivated audit event must exist, got: $audit")
  }

  // ── FR2：无实际变更 → 不重激活 ─────────────────────────────────

  test("FR2: edit failed node with NO actual change → stays failed (no reactivation)") {
    val ws = tempRoot / "ws-fr2"
    os.makeDir.all(ws)
    val system = ActorSystem(s"fr2-${scala.util.Random.nextInt(100000)}")
    val llm = FuncLlm(text => IO.pure("ok"))
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountReal("fr2", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- seedZombie(rt, "n-fr2", "fr2-node", "same-task", List(OutEdge.nebula))
      _ <- rt.engine.settleStaleRunningNodes()
      _ <- waitStatus(rt, "fr2-node", Set(NodeLifecycle.Failed))
      _ <- waitUntil(20.seconds)(byName(rt, "fr2-node").map(_.notifySentAt.isDefined))
      // 同 task 原文重发 + 同 out 显式回传 → 全维度无差异 → actualChange=false → 不
      // 重激活。（out **未传**的形态见 FR5——actualChange quirk 修后未传 out 不再
      // 误判变更；本测试保留显式回传同值 out，双维度锁定 no-op 语义。）
      // 2026-09-12 批 A1：种子 out = `OutEdge.nebula`（{pass,failed}/result），故回传
      // 必须写**同值的显式门集**；bare `"Nebula"` 今日 = {pass}/signal 出口标记，
      // 回传它反而是真实变更（这正是 A1 的两处语义分叉）。
      editRes <- nodeEdit(nodeInput("fr2", "fr2-node",
        "task" -> Json.fromString("same-task"),
        "out" -> Json.fromString("(pass,failed)Nebula")), ctx)
      _ <- IO.sleep(300.millis) // 无重激活即无异步启动——给竞态留确定性窗口后复查
      after <- byName(rt, "fr2-node")
      audit <- readAudit(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(after.status, NodeLifecycle.Failed, "no actual change → stays failed (terminal)")
      assert(!editRes.exists(_.contains("reactivated")), s"no reactivation reported, got: $editRes")
      assert(!audit.exists((t, id) => t == "reactivated" && id == "n-fr2"), "no reactivated audit event")
  }

  // ── FR3：completed/cancelled 不可重激活（终态边界不回退）────────────

  test("FR3: completed/cancelled nodes are NOT reactivatable (task edit changes nothing terminal)") {
    val ws = tempRoot / "ws-fr3"
    os.makeDir.all(ws)
    val system = ActorSystem(s"fr3-${scala.util.Random.nextInt(100000)}")
    val llm = FuncLlm(text => IO.pure("ok"))
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountReal("fr3", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      now = System.currentTimeMillis()
      _ <- rt.store.mutate { s =>
        s.copy(nodes = s.nodes ++ Map(
          "n-fr3c" -> NodeDef(id = "n-fr3c", name = "fr3-completed", agent = "general",
            task = Some("t1"), out = List(OutEdge.nebula), status = NodeLifecycle.Completed,
            result = Some("done"), completedAt = Some(now), createdAt = now),
          "n-fr3x" -> NodeDef(id = "n-fr3x", name = "fr3-cancelled", agent = "general",
            task = Some("t1"), out = List(OutEdge.nebula), status = NodeLifecycle.Cancelled,
            completedAt = Some(now), createdAt = now))) }.void
      editC <- nodeEdit(nodeInput("fr3", "fr3-completed", "task" -> Json.fromString("t2")), ctx)
      editX <- nodeEdit(nodeInput("fr3", "fr3-cancelled", "task" -> Json.fromString("t2")), ctx)
      c <- byName(rt, "fr3-completed")
      x <- byName(rt, "fr3-cancelled")
      audit <- readAudit(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(c.status, NodeLifecycle.Completed, "completed stays completed (no accidental revive)")
      assertEquals(x.status, NodeLifecycle.Cancelled, "cancelled stays cancelled")
      assert(!editC.exists(_.contains("reactivated")) && !editX.exists(_.contains("reactivated")),
        s"no reactivation reported for completed/cancelled, got: $editC / $editX")
      assert(!audit.exists((t, _) => t == "reactivated"), "no reactivated audit events")
  }

  // ── FR4：NodeMessage 对 failed 终态仍拒收 ────────────────────────

  test("FR4: NodeMessage to failed node still REFUSED (NODE_TERMINAL_NO_MESSAGE, terminal refusal semantics intact)") {
    val ws = tempRoot / "ws-fr4"
    os.makeDir.all(ws)
    val system = ActorSystem(s"fr4-${scala.util.Random.nextInt(100000)}")
    val llm = FuncLlm(text => IO.pure("ok"))
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountReal("fr4", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- seedZombie(rt, "n-fr4", "fr4-node", "dead task", List(OutEdge.nebula))
      _ <- rt.engine.settleStaleRunningNodes()
      _ <- waitStatus(rt, "fr4-node", Set(NodeLifecycle.Failed))
      _ <- waitUntil(20.seconds)(byName(rt, "fr4-node").map(_.notifySentAt.isDefined))
      refused <- nodeMessage(Json.obj(
        "project" -> Json.fromString("fr4"),
        "nodeId" -> Json.fromString("n-fr4"),
        "message" -> Json.fromString("extra guidance")), ctx)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(refused.isLeft, "NodeMessage to failed node must be REFUSED")
      assert(refused.left.exists(_.contains("NODE_TERMINAL_NO_MESSAGE")),
        s"refusal must carry NODE_TERMINAL_NO_MESSAGE, got: $refused")
  }

  // ── FR5：未传 out 的终态编辑 → no-op（out-absent quirk 锁定）─────────

  test("FR5: edit failed node WITHOUT out (identical task/description) → stays failed (out-absent is not a change)") {
    val ws = tempRoot / "ws-fr5"
    os.makeDir.all(ws)
    val system = ActorSystem(s"fr5-${scala.util.Random.nextInt(100000)}")
    val llm = FuncLlm(text => IO.pure("ok"))
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountReal("fr5", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- seedZombie(rt, "n-fr5", "fr5-node", "fr5-task", List(OutEdge.nebula), description = Some("fr5-desc"))
      _ <- rt.engine.settleStaleRunningNodes()
      _ <- waitStatus(rt, "fr5-node", Set(NodeLifecycle.Failed))
      _ <- waitUntil(20.seconds)(byName(rt, "fr5-node").map(_.notifySentAt.isDefined))
      // out 不传 + task/description 原值重发 → 全维度无差异 → actualChange=false。
      // quirk 修前：parseOut(None)=None 与 out=Some("Nebula") 恒不等 → 恒判变更
      // → 意外重激活（违背「No-op if nothing actually changed」）。
      editRes <- nodeEdit(nodeInput("fr5", "fr5-node",
        "task" -> Json.fromString("fr5-task"),
        "description" -> Json.fromString("fr5-desc")), ctx)
      _ <- IO.sleep(300.millis) // 无重激活即无异步启动——给竞态留确定性窗口后复查
      after <- byName(rt, "fr5-node")
      audit <- readAudit(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(after.status, NodeLifecycle.Failed, "out-absent edit with no value change → stays failed (no-op)")
      assert(!editRes.exists(_.contains("reactivated")), s"no reactivation reported, got: $editRes")
      assert(!audit.exists((t, id) => t == "reactivated" && id == "n-fr5"), "no reactivated audit event")
  }

  // ── FR6：显式传 out 且值变更 → 仍算变更 → 重激活（反向锁定）──────────

  test("FR6: edit failed node changing ONLY out (explicit, new value) → reactivates and reruns") {
    val ws = tempRoot / "ws-fr6"
    os.makeDir.all(ws)
    val system = ActorSystem(s"fr6-${scala.util.Random.nextInt(100000)}")
    val llm = FuncLlm(text =>
      if text.contains("任务分发器") then IO.pure("ok")
      else if text.contains("fr6-task") then IO.pure("fr6-redone")
      else IO.pure("ok"))
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountReal("fr6", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      now = System.currentTimeMillis()
      // 下游 sink（wiring，带 task）：out 改指它 → 重跑完成 barrier 归零后被 start，
      // FuncLlm 兜底 "ok" 承接 → 链路确定性收口（out=Nebula 终投）。
      _ <- rt.store.mutate { s =>
        s.copy(nodes = s.nodes + ("n-fr6sink" -> NodeDef(id = "n-fr6sink", name = "fr6-sink",
          agent = "general", task = Some("fr6 sink task"), out = List(OutEdge.nebula),
          status = NodeLifecycle.Wiring, createdAt = now))) }.void
      _ <- seedZombie(rt, "n-fr6", "fr6-node", "fr6-task", List(OutEdge.nebula))
      _ <- rt.engine.settleStaleRunningNodes()
      _ <- waitStatus(rt, "fr6-node", Set(NodeLifecycle.Failed))
      _ <- waitUntil(20.seconds)(byName(rt, "fr6-node").map(_.notifySentAt.isDefined))
      // 仅改 out（显式传 + 值变更 Nebula → n-fr6sink）→ actualChange=true → 重激活
      editRes <- nodeEdit(nodeInput("fr6", "fr6-node", "out" -> Json.fromString("n-fr6sink")), ctx)
      _ <- waitStatus(rt, "fr6-node", Set(NodeLifecycle.Completed))
      after <- byName(rt, "fr6-node")
      _ <- waitStatus(rt, "fr6-sink", Set(NodeLifecycle.Completed))
      audit <- readAudit(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(editRes.exists(_.contains("reactivated from failed")), s"out-only change must reactivate, got: $editRes")
      assertEquals(after.status, NodeLifecycle.Completed, "reactivated node reruns to completion")
      assertEquals(after.out, List(OutEdge("n-fr6sink")), "out rewired to the new target")
      assertEquals(after.result, Some("fr6-redone"), "rerun keeps the original task (out-only edit)")
      assert(audit.exists((t, id) => t == "reactivated" && id == "n-fr6"), "reactivated audit event must exist")
  }

end NodeFailedReactivateSpec
