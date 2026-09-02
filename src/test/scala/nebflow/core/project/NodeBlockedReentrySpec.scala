package nebflow.core.project

import cats.effect.{IO, Ref}
import fs2.Stream
import io.circe.Json
import io.circe.parser.parse as jsonParse
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, Behaviors}
import nebflow.agent.{AgentCommand, AgentKind, AgentLibrary, AgentRecord, SharedResources}
import nebflow.core.PathUtil
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, NodeEditTool, ToolContext}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * blocked 反馈重入集成回归（真实引擎路径驱动，NodeBarrierDeliverySpec 同款基建）。
 *
 * 覆盖（20260902 设计 §1/§2/§3 + 拍板验收 ③④⑤ 后端侧）：
 * - blocked 数据形态：status/ttlExpireAt=None/result 渲染串/blockCount/blockedFeedback
 *   + 不结算下游 + WS nodeUpdated（无新事件类型）+ blocked 审计行
 * - 验收③：blocked → ReenterDispatcher → spawnDispatcher 以【重入调整】prompt 形态 spawn
 * - 验收④：第 3 次 blocked（count=3）升级 Nebula 不再重入（deliverToNebula 通道，无新 spawn）
 * - 重激活：编辑 blocked 节点 → wiring/pending + deliveredTo 清空 + blockCount 保留 + D1 重投
 * - abandon：终态节点 → cancelled + TTL + 审计；running 节点拒绝
 * - R1：D1 补投递必须排除 blocked（反馈串不是可投结果）
 */
class NodeBlockedReentrySpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 240.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-node-blocked-reentry"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  for agent <- List("test-agent", "project-dispatcher") do
    os.makeDir.all(tempRoot / "agents" / agent)
    os.write.over(
      tempRoot / "agents" / agent / "agent.json",
      s"""{"name":"$agent","description":"blocked reentry regression agent","tools":[],"category":"standalone"}"""
    )
    os.write.over(tempRoot / "agents" / agent / "system.md", s"# $agent\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  private def blockedText(category: String, detail: String, suggestion: String): String =
    s"""BLOCKED: 无法继续
       |{"category":"$category","detail":"$detail","suggestion":"$suggestion"}""".stripMargin

  /** 按输入内容响应的 LLM：inputs 记录全部 user 文本（断言 prompt 形态/投递）。 */
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
      sessionId = Some("reentry-sid"),
      rootSessionId = Some("nebula-root"),
      sharedResources = Some(res),
      actorSystem = Some(system)
    )

  private def nodeEdit(input: Json, ctx: ToolContext): IO[Either[String, String]] =
    NodeEditTool.call(input.asObject.get, ctx).map(_.left.map(_.message))

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

  /** 引擎挂载（无 ProjectActor：重入走 router 的 warn 降级路径）+ WS 事件捕获。 */
  private def mountEngineOnly(
    name: String,
    ws: os.Path,
    system: ActorSystem,
    res: SharedResources
  ): IO[(ProjectRuntime, Ref[IO, List[(String, String, Json)]])] =
    for
      store <- FlowMapStore.open(name, ws.toString)
      events <- Ref.of[IO, List[(String, String, Json)]](Nil)
      engine = new NodeEngine(
        store,
        system,
        res,
        wsSendFn = (_: Json) => IO.unit,
        workspace = ws.toString,
        rootSessionId = "nebula-root",
        projectName = name,
        emitEvent = (t, id, payload) => events.update((t, id, payload) :: _)
      )
      pd = ProjectDef(name = name, workspace = ws.toString, agentFile = (ws / "AGENTS.md").toString, createdAt = System.currentTimeMillis())
      rt = ProjectRuntime(pd, store, engine, system, res, None)
      _ <- ProjectRuntimeRegistry.register(rt)
    yield (rt, events)

  /** 完整挂载（真实 ProjectActor——重入 spawn 路径需要）。 */
  private def mountReal(name: String, ws: os.Path, system: ActorSystem, res: SharedResources): IO[ProjectRuntime] =
    val pd = ProjectDef(name = name, workspace = ws.toString, agentFile = (ws / "AGENTS.md").toString, createdAt = System.currentTimeMillis())
    ProjectRuntimeRegistry.mount(pd, system, res, None, "nebula-root")

  /** Nebula 根会话捕获 actor（deliverToNebula 升级通道的断言点）。 */
  private def registerNebulaCapture(res: SharedResources, system: ActorSystem): IO[Ref[IO, List[(String, Option[String])]]] =
    Ref.of[IO, List[(String, Option[String])]](Nil).flatMap { captured =>
      // 续存行为：handler 返回自身（本 actor 库无 Behaviors.same——Behavior.scala）
      lazy val captureBehavior: nebflow.actor.Behavior[AgentCommand] = Behaviors.receive[AgentCommand] { (_, msg) =>
        msg match
          case im: AgentCommand.ImmediateInput => captured.update(_ :+ (im.text -> im.eventType)).as(captureBehavior)
          case _                               => IO.pure(captureBehavior)
      }
      system.spawn(captureBehavior, s"nebula-capture-${scala.util.Random.nextInt(100000)}").flatMap { ref =>
        val now = System.currentTimeMillis()
        res.agentRegistry
          .update(_ + ("nebula-root" -> AgentRecord(
            sessionId = "nebula-root", ref = ref, kind = AgentKind.Root, rootSessionId = "nebula-root",
            startedAt = now, lastActivityMs = now)))
          .as(captured)
      }
    }

  private def idOf(rt: ProjectRuntime, name: String): IO[String] =
    rt.store.snapshot.map(_.nodes.values.find(_.name == name)).map {
      case Some(n) => n.id
      case None    => fail(s"node '$name' must exist")
    }

  private def nodeById(rt: ProjectRuntime, id: String): IO[NodeDef] =
    rt.store.snapshot.map(_.nodes.get(id)).map(_.getOrElse(fail(s"node '$id' must exist")))

  private def waitStatus(rt: ProjectRuntime, name: String, statuses: Set[String]): IO[Unit] =
    waitUntil(20.seconds) {
      rt.store.snapshot.map(_.nodes.values.find(_.name == name)).flatMap {
        case Some(n) => IO.pure(statuses.contains(n.status))
        case None    => IO.pure(false)
      }
    }

  private def readAuditTypes(ws: os.Path): IO[List[(String, String)]] =
    IO.blocking(os.read(ws / ".nebflow" / FlowMapEventLog.FileName))
      .map(_.linesIterator.toList.filter(_.trim.nonEmpty))
      .map(lines => lines.flatMap(l => jsonParse(l).toOption.map(j =>
        (j.hcursor.get[String]("type").getOrElse(""), j.hcursor.get[String]("nodeId").getOrElse("")))))
      .handleError(_ => Nil)

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  // ── blocked 数据形态 + 不结算下游 + WS 事件 + 审计（§2.1）──────────

  test("blocked: node terminalized as blocked (ttlExpireAt=None, render result, feedback, count=1), downstream NOT settled, nodeUpdated payload carries blockCount/blockedFeedback") {
    val ws = tempRoot / "ws-shape"
    os.makeDir.all(ws)
    val system = ActorSystem(s"blk-shape-${scala.util.Random.nextInt(100000)}")
    val llm = FuncLlm(text => if text.contains("will-block-A") then IO.pure(blockedText("upstream-incomplete", "上游 X 未完成", "需上游先完成")) else IO.pure("ok"))
    for
      res <- mkResources(system, tempRoot, llm.handle)
      (rt, events) <- mountEngineOnly("blk-shape", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // B（wiring，无 task）← A 入口 blocked
      _ <- nodeEdit(nodeInput("blk-shape", "down-b", "agent" -> Json.fromString("test-agent"),
        "out" -> Json.fromString("Nebula")), ctx)
      _ <- nodeEdit(nodeInput("blk-shape", "blocked-a", "agent" -> Json.fromString("test-agent"),
        "task" -> Json.fromString("will-block-A"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- waitStatus(rt, "blocked-a", Set(NodeLifecycle.Blocked))
      aId <- idOf(rt, "blocked-a")
      a <- nodeById(rt, aId)
      evs <- events.get
      audit <- readAuditTypes(ws)
      _ <- IO.sleep(300.millis) // 给「假如有下游结算」留窗口
      bAfter <- rt.store.snapshot.map(_.nodes.values.find(_.name == "down-b")).map(_.getOrElse(fail("B must exist")))
      inputs <- llm.inputs.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // 数据形态（§1.3/§1.4/§7.6）
      assertEquals(a.status, NodeLifecycle.Blocked)
      assertEquals(a.ttlExpireAt, None, "blocked node must never expire (待办语义)")
      assertEquals(a.blockCount, 1)
      assertEquals(a.blockedFeedback, Some(BlockedFeedback("upstream-incomplete", "上游 X 未完成", "需上游先完成")))
      assert(a.result.exists(_.startsWith("[blocked:upstream-incomplete]")), s"result must be rendered string, got: ${a.result}")
      // 不结算下游（§2.1 动作③）：B 未收到投递、未启动
      assertEquals(bAfter.status, NodeLifecycle.Wiring, "downstream must NOT be settled by blocked")
      assertEquals(bAfter.deliveredTo, Nil, "downstream must not receive blocked feedback string")
      assert(!inputs.exists(_.contains("=== Node ")), "no result delivery may happen for blocked")
      // WS：nodeUpdated（复用现有类型，§2.1 动作②），payload 同构 + 新字段
      val upd = evs.find((t, id, p) => t == "nodeUpdated" && id == aId && p.hcursor.get[String]("status").toOption.contains(NodeLifecycle.Blocked))
      assert(upd.isDefined, s"blocked must emit nodeUpdated, got: ${evs.map((t, id, _) => (t, id))}")
      val payload = upd.get._3
      assertEquals(payload.hcursor.get[Int]("blockCount").toOption, Some(1), "payload must carry blockCount")
      val bf = payload.hcursor.downField("blockedFeedback")
      assertEquals(bf.get[String]("category").toOption, Some("upstream-incomplete"), "payload must carry structured feedback")
      assert(!evs.exists((t, id, _) => t == "nodeCompleted" && id == aId), "blocked must NOT emit nodeCompleted")
      // 审计（§4.4）
      assert(audit.exists((t, id) => t == "blocked" && id == aId), s"blocked audit line must exist, got: $audit")
  }

  // ── 验收③：blocked → ReenterDispatcher → 重入 prompt 形态 spawn ──

  test("reentry: blocked routes ReenterDispatcher → dispatcher spawned with reentry-adjustment prompt (含节点名/id/轮次/三字段/快照/四动作/无需回报)") {
    val ws = tempRoot / "ws-reentry"
    os.makeDir.all(ws)
    val system = ActorSystem(s"blk-reentry-${scala.util.Random.nextInt(100000)}")
    val llm = FuncLlm(text =>
      if text.contains("任务分发器") then IO.pure("ok") // 重入分发器会话：完成即止
      else if text.contains("will-block-R") then IO.pure(blockedText("task-underspecified", "任务缺少交付物定义", "补充验收标准"))
      else IO.pure("ok"))
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountReal("blk-reentry", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- nodeEdit(nodeInput("blk-reentry", "reentry-node", "agent" -> Json.fromString("test-agent"),
        "task" -> Json.fromString("will-block-R"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- waitStatus(rt, "reentry-node", Set(NodeLifecycle.Blocked))
      // 重入分发器会话 spawn → prompt 经 LLM 捕获
      _ <- waitUntil(15.seconds)(llm.inputs.get.map(_.exists(_.contains("节点反馈重入调整"))))
      prompts <- llm.inputs.get
      nodeId <- idOf(rt, "reentry-node")
      node <- nodeById(rt, nodeId)
      audit <- readAuditTypes(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      val prompt = prompts.find(_.contains("节点反馈重入调整")).getOrElse(fail("reentry prompt must be captured"))
      assert(prompt.contains("不是新任务"), "reentry prompt must declare reentry nature")
      assert(prompt.contains("reentry-node") && prompt.contains(nodeId), s"prompt must carry node name+id")
      assert(prompt.contains("第 1 轮"), "prompt must carry blockCount round")
      assert(prompt.contains("原因分类：task-underspecified"), "prompt must carry category")
      assert(prompt.contains("说明：任务缺少交付物定义"), "prompt must carry detail")
      assert(prompt.contains("对拓扑的建议：补充验收标准"), "prompt must carry suggestion")
      assert(prompt.contains("```json") && prompt.contains("Flow Map 快照"), "prompt must embed Flow Map snapshot")
      assert(prompt.contains("abandon=true"), "prompt must carry abandon action hint")
      assert(prompt.contains("无需回报"), "prompt must carry no-report note")
      assert(prompt.contains("project=blk-reentry"), "prompt must carry project param note")
      assert(node.blockCount == 1, "first blocked round")
      assert(audit.exists((t, _) => t == "reentry-triggered"), s"reentry-triggered audit line must exist, got: $audit")
  }

  // ── 验收④：第 3 次 blocked → 升级 Nebula 不再重入 ──────────────

  test("loop-cap: 3rd blocked escalates to Nebula (eventType=blocked), only 2 reentry spawns") {
    val ws = tempRoot / "ws-loopcap"
    os.makeDir.all(ws)
    val system = ActorSystem(s"blk-loopcap-${scala.util.Random.nextInt(100000)}")
    val llm = FuncLlm(text =>
      if text.contains("任务分发器") then IO.pure("ok")
      else if text.contains("round-one") || text.contains("round-two") || text.contains("round-three") then
        IO.pure(blockedText("needs-split", "任务应拆分", "拆为子图"))
      else IO.pure("ok"))
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountReal("blk-loopcap", ws, system, res)
      nebula <- registerNebulaCapture(res, system)
      ctx = mkCtx(res, system, ws.toString)
      // 第 1 轮 blocked（count=1 → 重入）
      _ <- nodeEdit(nodeInput("blk-loopcap", "loop-node", "agent" -> Json.fromString("test-agent"),
        "task" -> Json.fromString("round-one"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- waitStatus(rt, "loop-node", Set(NodeLifecycle.Blocked))
      _ <- waitUntil(15.seconds)(llm.inputs.get.map(_.count(_.contains("节点反馈重入调整")) >= 1))
      // 第 2 轮：重激活（task 实际变更）→ blocked（count=2 → 重入）
      _ <- nodeEdit(nodeInput("blk-loopcap", "loop-node", "task" -> Json.fromString("round-two")), ctx)
      _ <- waitStatus(rt, "loop-node", Set(NodeLifecycle.Blocked))
      _ <- waitUntil(15.seconds)(llm.inputs.get.map(_.count(_.contains("节点反馈重入调整")) >= 2))
      // 第 3 轮：重激活 → blocked（count=3 → 升级，不再重入）
      _ <- nodeEdit(nodeInput("blk-loopcap", "loop-node", "task" -> Json.fromString("round-three")), ctx)
      _ <- waitStatus(rt, "loop-node", Set(NodeLifecycle.Blocked))
      _ <- waitUntil(15.seconds)(nebula.get.map(_.nonEmpty))
      _ <- IO.sleep(600.millis) // 若仍会重入，这里会冒出第 3 条重入 prompt
      prompts <- llm.inputs.get
      escalations <- nebula.get
      node <- rt.store.snapshot.map(_.nodes.values.find(_.name == "loop-node")).map(_.getOrElse(fail("node must exist")))
      audit <- readAuditTypes(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      val reentryCount = prompts.count(_.contains("节点反馈重入调整"))
      assertEquals(reentryCount, 2, "rounds 1&2 reenter; round 3 must NOT reenter")
      assertEquals(escalations.size, 1, "exactly one escalation to Nebula")
      val (text, eventType) = escalations.head
      assertEquals(eventType, Some("blocked"), "escalation eventType must be 'blocked' (前端 label 自动 BLOCKED)")
      assert(text.contains("[Node 'loop-node' blocked]"), s"escalation head, got: $text")
      assert(text.contains("第 3 次 blocked") && text.contains("上限 2"), s"escalation must carry round count + cap, got: $text")
      assert(text.contains("历史轮次反馈") && text.contains("needs-split"), s"escalation must carry feedback history, got: $text")
      assertEquals(node.status, NodeLifecycle.Blocked, "node stays blocked awaiting disposition")
      assertEquals(node.blockCount, 3)
      assertEquals(node.ttlExpireAt, None, "escalated blocked node stays visible (待办语义)")
      assert(audit.exists((t, _) => t == "escalated"), s"escalated audit line must exist, got: $audit")
  }

  // ── NodeEdit 重激活：deliveredTo 清空 + blockCount 保留 + D1 重投 ──

  test("reactivation: edit blocked node (task change + in append) → reactivated, upstream results re-delivered, blockCount preserved, reruns to completion") {
    val ws = tempRoot / "ws-reactivate"
    os.makeDir.all(ws)
    val system = ActorSystem(s"blk-react-${scala.util.Random.nextInt(100000)}")
    val llm = FuncLlm(text =>
      if text.contains("will-block") then IO.pure(blockedText("upstream-incomplete", "缺上游输入", "先等上游完成"))
      else IO.pure("ok-final"))
    for
      res <- mkResources(system, tempRoot, llm.handle)
      (rt, events) <- mountEngineOnly("blk-reactivate", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // A 入口 blocked（out=Nebula 仅满足连接下限校验五；blocked 不结算下游，无投递副作用）
      _ <- nodeEdit(nodeInput("blk-reactivate", "react-node", "agent" -> Json.fromString("test-agent"),
        "task" -> Json.fromString("will-block"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- waitStatus(rt, "react-node", Set(NodeLifecycle.Blocked))
      aId <- idOf(rt, "react-node")
      // C 悬空完成（重激活时接为上游；out=Nebula 仅满足连接下限）
      _ <- nodeEdit(nodeInput("blk-reactivate", "late-up", "agent" -> Json.fromString("test-agent"),
        "task" -> Json.fromString("late-result-C"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- waitStatus(rt, "late-up", Set(NodeLifecycle.Completed))
      cId <- idOf(rt, "late-up")
      // 编辑 blocked 节点：task 实际变更 + in 追加 → 重激活
      _ <- nodeEdit(nodeInput("blk-reactivate", "react-node", "task" -> Json.fromString("retry-with-input"),
        "in" -> Json.fromString(cId)), ctx)
      _ <- waitStatus(rt, "react-node", Set(NodeLifecycle.Completed))
      a <- nodeById(rt, aId)
      secondRunInput <- llm.inputs.get.map(_.find(t => t.contains("retry-with-input") && t.contains("=== Node late-up ===")))
      audit <- readAuditTypes(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(a.status, NodeLifecycle.Completed, "reactivated node must rerun to completion")
      assertEquals(a.blockCount, 1, "blockCount preserved on reactivation (不清零)")
      assert(a.blockedFeedback.isDefined, "last blocked feedback kept for history")
      assertEquals(a.result, Some("ok-final"), "result replaced by new run output (反馈渲染串清除)")
      assert(a.in.contains(cId), "in append must land")
      assert(a.deliveredTo.contains(cId), "completed upstream re-delivered after deliveredTo cleared")
      assert(a.completedAt.isDefined && a.ttlExpireAt.isDefined, "completed rerun re-arms display TTL")
      assert(secondRunInput.isDefined, s"second run input must carry re-delivered upstream result, got: ${llm.inputs.get.unsafeRunSync().map(_.take(120))}")
      assert(secondRunInput.exists(_.contains("── 节点协议 ──")), "protocol footnote must be injected (§1.5)")
      assert(audit.exists((t, id) => t == "reactivated" && id == aId), s"reactivated audit line, got: $audit")
  }

  // ── abandon：终态 → cancelled + TTL + 审计；running 拒绝 ────────

  test("abandon: blocked node → cancelled + display TTL + audit; running node refused") {
    val ws = tempRoot / "ws-abandon"
    os.makeDir.all(ws)
    val system = ActorSystem(s"blk-abandon-${scala.util.Random.nextInt(100000)}")
    val llm = FuncLlm(text =>
      if text.contains("will-block") then IO.pure(blockedText("other", "无法提出调整", "abandon"))
      else if text.contains("slow-node") then IO.sleep(1500.millis).as("slow-ok")
      else IO.pure("ok"))
    for
      res <- mkResources(system, tempRoot, llm.handle)
      (rt, events) <- mountEngineOnly("blk-abandon", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- nodeEdit(nodeInput("blk-abandon", "abandon-node", "agent" -> Json.fromString("test-agent"),
        "task" -> Json.fromString("will-block"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- waitStatus(rt, "abandon-node", Set(NodeLifecycle.Blocked))
      aId <- idOf(rt, "abandon-node")
      // running 节点 → abandon 拒绝
      _ <- nodeEdit(nodeInput("blk-abandon", "slow-node", "agent" -> Json.fromString("test-agent"),
        "task" -> Json.fromString("slow-node"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- waitStatus(rt, "slow-node", Set(NodeLifecycle.Running))
      refused <- nodeEdit(nodeInput("blk-abandon", "slow-node", "abandon" -> Json.fromBoolean(true)), ctx)
      // blocked → abandon 接受
      r <- nodeEdit(nodeInput("blk-abandon", "abandon-node", "abandon" -> Json.fromBoolean(true)), ctx)
      _ <- IO.sleep(200.millis)
      a <- nodeById(rt, aId)
      evs <- events.get
      audit <- readAuditTypes(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(refused.isLeft, s"abandon on running node must be refused, got: $refused")
      assert(refused.left.exists(_.contains("terminal")), s"refusal message must point to terminal-only, got: $refused")
      assert(r.isRight, s"abandon on blocked node must succeed, got: $r")
      assertEquals(a.status, NodeLifecycle.Cancelled, "abandoned node becomes cancelled (§7.7)")
      assert(a.ttlExpireAt.isDefined, "abandoned node walks normal display TTL (§7.6)")
      assert(evs.exists((t, id, p) => t == "nodeUpdated" && id == aId && p.hcursor.get[String]("status").toOption.contains(NodeLifecycle.Cancelled)),
        "abandon must emit nodeUpdated (cancelled)")
      assert(audit.exists((t, id) => t == "abandoned" && id == aId), s"abandoned audit line, got: $audit")
  }

  // ── R1：D1 补投递必须排除 blocked（create + edit 两路径）─────────

  test("R1: D1 delivery guards exclude blocked upstream — feedback string never delivered, downstream stays waiting") {
    val ws = tempRoot / "ws-r1"
    os.makeDir.all(ws)
    val system = ActorSystem(s"blk-r1-${scala.util.Random.nextInt(100000)}")
    val llm = FuncLlm(text => if text.contains("will-block") then IO.pure(blockedText("other", "反馈串", "")) else IO.pure("ok"))
    for
      res <- mkResources(system, tempRoot, llm.handle)
      (rt, events) <- mountEngineOnly("blk-r1", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // A blocked 悬空（out=Nebula 仅满足连接下限校验五；blocked 不结算，投递面无副作用）
      _ <- nodeEdit(nodeInput("blk-r1", "blk-up", "agent" -> Json.fromString("test-agent"),
        "task" -> Json.fromString("will-block"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- waitStatus(rt, "blk-up", Set(NodeLifecycle.Blocked))
      aId <- idOf(rt, "blk-up")
      // create 路径：B 接 in=[A] → 不得投递/启动
      _ <- nodeEdit(nodeInput("blk-r1", "consumer-b", "agent" -> Json.fromString("test-agent"),
        "task" -> Json.fromString("consume-b"), "in" -> Json.fromString(aId),
        "out" -> Json.fromString("Nebula")), ctx)
      // edit 路径：W 追加 in=[A] → 不得投递/启动
      _ <- nodeEdit(nodeInput("blk-r1", "wiring-w", "agent" -> Json.fromString("test-agent"),
        "out" -> Json.fromString("Nebula")), ctx)
      _ <- nodeEdit(nodeInput("blk-r1", "wiring-w", "in" -> Json.fromString(aId)), ctx)
      _ <- IO.sleep(800.millis) // 给「假如误投递」留窗口
      inputs <- llm.inputs.get
      b <- rt.store.snapshot.map(_.nodes.values.find(_.name == "consumer-b")).map(_.getOrElse(fail("B must exist")))
      w <- rt.store.snapshot.map(_.nodes.values.find(_.name == "wiring-w")).map(_.getOrElse(fail("W must exist")))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(b.status, NodeLifecycle.Wiring, "create-path D1 must not deliver/start from blocked upstream")
      assertEquals(b.deliveredTo, Nil, "no deliveredTo entry from blocked upstream (create path)")
      assertEquals(w.status, NodeLifecycle.Wiring, "edit-path D1 must not deliver/start from blocked upstream")
      assertEquals(w.deliveredTo, Nil, "no deliveredTo entry from blocked upstream (edit path)")
      assert(!inputs.exists(t => t.contains("=== Node blk-up ===")), s"blocked feedback string must never be delivered, inputs=${inputs.map(_.take(100))}")
  }

end NodeBlockedReentrySpec
