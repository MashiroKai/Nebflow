package nebflow.core.project

import cats.effect.{IO, Ref}
import fs2.Stream
import io.circe.Json
import io.circe.parser.parse as jsonParse
import io.circe.syntax.*
import io.circe.JsonObject
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentLibrary, SharedResources}
import nebflow.core.PathUtil
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, NodeEditTool, ToolContext}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{ContentBlock, LlmHandle, LlmRequest, LlmResponse, StreamChunk, ToolCall}

import scala.concurrent.duration.*

/**
 * blocked 结构化信号（node_report 工具）集成回归（20260909 spec §9.2/§9.3，
 * 真实引擎路径驱动，NodeBlockedReentrySpec 同款基建；同日作者裁定泛化 NodeReport
 * 统一三语义——blocked 重放全保留 + pass/fail 主动申报集成例）：
 * - 工具申报 → 会话完成时点 drain → blockedNode 终态化（结构化信号优先）：
 *   status/blockedFeedback/blockCount=1/ttlExpireAt=None + result=渲染串头部+全文
 *   拼接 + 不结算下游 + nodeUpdated/blocked 审计（FeedbackRouter 消费面不变）
 * - pass 申报 → 既有 completed 链（blockedFeedback 零落库、result 原文、下游结算）
 * - fail 申报 → 既有 failed 链（result=渲染串、零 deliverOut 结算）
 * - 误判回归：正文恰含 BLOCKED 字样、未调工具 → completed 不变（结构性免疫，
 *   spec §6 第一行）
 * - 收尾报告后申报残留：cancelled 路径不消费（cleanupRunTables remove 对称清理，
 *   幂等不残留）
 */
class NodeBlockedToolSignalSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 240.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-node-blocked-tool-signal"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  for agent <- List("test-agent", "project-dispatcher", "general") do
    os.makeDir.all(tempRoot / "agents" / agent)
    os.write.over(
      tempRoot / "agents" / agent / "agent.json",
      s"""{"name":"$agent","description":"blocked tool signal regression agent","tools":[],"category":"standalone"}"""
    )
    os.write.over(tempRoot / "agents" / agent / "system.md", s"# $agent\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  /** 按输入内容响应的 LLM：inputs 记录全部 user 文本（断言 turn 形态）。 */
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

  /** 工具申报状态机 LLM：首 turn 发 node_report 工具调用（category 参数化——
    * blocked/pass/fail 三语义共用驱动），见到工具结果后输出无锚定收尾文本——
    * 证明终态由工具通道驱动而非文本形态。
    * 探测走 ContentBlock.ToolResult（textContent 只含 Text 块——tool_result 在
    * role=user 消息的块列表里，文本面不可见）。 */
  private class ToolCallLlm(category: String, detail: String, suggestion: String, closing: String):
    val inputs: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)
    private def ackText: String =
      if nebflow.core.tools.NodeReportToolDef.isBlockedSemantics(category)
      then "[OK] blocked declaration recorded"
      else s"[OK] node report ($category) recorded"
    private def sawDeclarationAck(req: LlmRequest): Boolean =
      req.messages.exists { m =>
        m.content match
          case Right(blocks) =>
            blocks.exists {
              case ContentBlock.ToolResult(_, content, _) =>
                content.contains(ackText)
              case _ => false
            }
          case Left(t) => t.contains(ackText)
      }
    def handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(
          req: LlmRequest,
          onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
      ): Stream[IO, StreamChunk] =
        val text = req.messages.map(_.textContent).mkString("\n")
        if sawDeclarationAck(req) then
          Stream.eval(inputs.update(_ :+ text)) >>
            Stream(StreamChunk.TextDelta(closing), StreamChunk.Done(None, None))
        else
          Stream.eval(inputs.update(_ :+ text)) >>
            Stream(
              StreamChunk.ToolCallChunk(ToolCall(
                "rb-1", "node_report",
                JsonObject(
                  "category" -> category.asJson,
                  "detail" -> detail.asJson,
                  "suggestion" -> suggestion.asJson))),
              StreamChunk.Done(Some("tool_use"), None))

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
      sessionId = Some("tool-signal-sid"),
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
        emitEvent = (t, id, payload) => events.update((t, id, payload) :: _),
        // noderpt 批 A 段：本 fixture 主题 = node_report(blocked) 工具信号 ⇒ 显式关腿 2
        // （生产默认开；腿 2 默认开行为由 NodeReportReminderSpec 覆盖）。本 spec 的
        // 未申报降级面用例（文本锚定）依赖「未申报照常放行」的旧口径。
        reportGateHold = Some(false)
      )
      pd = ProjectDef(name = name, workspace = ws.toString, agentFile = (ws / "AGENTS.md").toString, createdAt = System.currentTimeMillis())
      rt = ProjectRuntime(pd, store, engine, system, res, None)
      _ <- ProjectRuntimeRegistry.register(rt)
    yield (rt, events)

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

  // ── 工具申报 → blocked 终态（spec §9.2 核心重放面：6 例申报形态被工具通道归一）──

  test("tool declaration: node_report call drives blocked terminal (feedback/count/ttl/result head+full text), no text anchor needed, downstream NOT settled") {
    val ws = tempRoot / "ws-tool-blocked"
    os.makeDir.all(ws)
    val system = ActorSystem(s"bts-tool-${scala.util.Random.nextInt(100000)}")
    val closing = "合作方接口不可用，已按协议申报受阻。本节点收尾。"
    val llm = new ToolCallLlm(
      category = "external-dependency",
      detail = "等待外部 API 恢复",
      suggestion = "API 恢复后重派",
      closing = closing)
    for
      res <- mkResources(system, tempRoot, llm.handle)
      (rt, events) <- mountEngineOnly("bts-tool", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // 下游 wiring（blocked 零结算断言面；store 直种——20260903 out 规范下
      // wiring 节点不可经 NodeEdit 创建）
      _ <- rt.store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        "n-down-d" -> NodeDef(id = "n-down-d", name = "down-d", agent = "test-agent",
          status = NodeLifecycle.Wiring, out = List(OutEdge.nebula), createdAt = System.currentTimeMillis()))))
      _ <- nodeEdit(nodeInput("bts-tool", "tool-blocked-a", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("will-block-tool-A"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- waitStatus(rt, "tool-blocked-a", Set(NodeLifecycle.Blocked))
      aId <- idOf(rt, "tool-blocked-a")
      a <- nodeById(rt, aId)
      evs <- events.get
      audit <- readAuditTypes(ws)
      _ <- IO.sleep(300.millis)
      dAfter <- rt.store.snapshot.map(_.nodes.values.find(_.name == "down-d")).map(_.getOrElse(fail("D must exist")))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // 终态形态（blockedNode 四动作不变，spec §9.2）
      assertEquals(a.status, NodeLifecycle.Blocked, "tool declaration must terminalize blocked")
      assertEquals(a.blockedFeedback, Some(BlockedFeedback("external-dependency", "等待外部 API 恢复", "API 恢复后重派")))
      assertEquals(a.blockCount, 1)
      assertEquals(a.ttlExpireAt, None, "blocked never expires (待办语义)")
      // result = 渲染串头部 + 收尾全文拼接（spec §5.2 #6）
      assert(a.result.exists(_.startsWith("[blocked:external-dependency]")), s"result head must be render string, got: ${a.result}")
      assert(a.result.exists(_.contains(closing)), s"result must carry the full closing report, got: ${a.result}")
      // 收尾文本不含裸 BLOCKED 锚定 → 文本降级面未参与，blocked 纯由工具驱动
      assert(!closing.trim.startsWith("BLOCKED"), "closing text must NOT carry the text anchor (tool-driven proof)")
      // 不结算下游
      assertEquals(dAfter.status, NodeLifecycle.Wiring, "downstream must NOT be settled by blocked")
      assertEquals(dAfter.deliveredTo, Nil)
      // WS + 审计（既有消费面不变）
      assert(evs.exists((t, id, p) => t == "nodeUpdated" && id == aId && p.hcursor.get[String]("status").toOption.contains(NodeLifecycle.Blocked)),
        "blocked must emit nodeUpdated")
      assert(!evs.exists((t, id, _) => t == "nodeCompleted" && id == aId), "blocked must NOT emit nodeCompleted")
      assert(audit.exists((t, id) => t == "blocked" && id == aId), s"blocked audit line must exist, got: $audit")
  }

  // ── 误判回归（spec §9.3 第一行）：正文含 BLOCKED 字样、未调工具 → completed ──

  test("misdetection regression: final text mentioning BLOCKED mid-report WITHOUT tool call completes normally") {
    val ws = tempRoot / "ws-misdetect"
    os.makeDir.all(ws)
    val system = ActorSystem(s"bts-mis-${scala.util.Random.nextInt(100000)}")
    val tricky = "上游节点曾申报 **BLOCKED**（现已恢复），我方据其产出完成交付。"
    val llm = FuncLlm(text => if text.contains("will-finish-with-word") then IO.pure(tricky) else IO.pure("ok"))
    for
      res <- mkResources(system, tempRoot, llm.handle)
      (rt, events) <- mountEngineOnly("bts-mis", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- nodeEdit(nodeInput("bts-mis", "finisher", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("will-finish-with-word"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- waitStatus(rt, "finisher", Set(NodeLifecycle.Completed))
      fId <- idOf(rt, "finisher")
      f <- nodeById(rt, fId)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(f.status, NodeLifecycle.Completed, "BLOCKED-word prose must never flip the terminal state")
      assertEquals(f.blockedFeedback, None, "no blockedFeedback without a declaration")
      assertEquals(f.blockCount, 0)
      assertEquals(f.result, Some(tricky), "completed result is the raw output (no render-string rewrite)")
  }

  // ── 申报残留对称清理：cancelled 路径不消费、清理幂等（cleanupRunTables 钩子）──

  test("leftover sweep: cancelled session's declaration is removed by cleanup (no cross-session leak)") {
    val ws = tempRoot / "ws-sweep"
    os.makeDir.all(ws)
    // 直接驱动 registry 语义 + NodeEngine 清理钩子等价面：register → remove → drain=None
    val sid = "node-sweepsid"
    for
      _ <- NodeReportRegistry.register(sid, BlockedFeedback("other", "残留申报", ""))
      _ <- NodeReportRegistry.remove(sid)
      drained <- NodeReportRegistry.drain(sid)
      _ <- NodeReportRegistry.remove(sid) // 幂等二次
      again <- NodeReportRegistry.drain(sid)
    yield
      assertEquals(drained, None, "removed declaration must not be consumable")
      assertEquals(again, None, "remove is idempotent")
  }

  // ── NodeReport 泛化批扩面：pass/fail 主动申报集成重放（作者裁定三语义统一迁移）──

  test("pass declaration: node_report(pass) drives the existing completed chain — no blockedFeedback, downstream settled") {
    val ws = tempRoot / "ws-tool-pass"
    os.makeDir.all(ws)
    val system = ActorSystem(s"bts-pass-${scala.util.Random.nextInt(100000)}")
    val closing = "验收条件逐条核对通过，正式声明完成。本节点收尾。"
    val llm = new ToolCallLlm(
      category = "pass",
      detail = "验收条件逐条核对通过",
      suggestion = "",
      closing = closing)
    for
      res <- mkResources(system, tempRoot, llm.handle)
      (rt, events) <- mountEngineOnly("bts-pass", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // 下游 wiring（pass 走 completed 链 → out 结算断言面；store 直种同上）
      _ <- rt.store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        "n-down-p" -> NodeDef(id = "n-down-p", name = "down-p", agent = "test-agent",
          status = NodeLifecycle.Wiring, out = List(OutEdge.nebula), createdAt = System.currentTimeMillis()))))
      _ <- nodeEdit(nodeInput("bts-pass", "tool-pass-a", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("will-pass-tool-A"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- waitStatus(rt, "tool-pass-a", Set(NodeLifecycle.Completed))
      pId <- idOf(rt, "tool-pass-a")
      p <- nodeById(rt, pId)
      evs <- events.get
      audit <- readAuditTypes(ws)
      _ <- IO.sleep(300.millis)
      dAfter <- rt.store.snapshot.map(_.nodes.values.find(_.name == "down-p")).map(_.getOrElse(fail("D must exist")))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // 既有 completed 链（pass 申报与无申报同链：CompletionGate → completedNode）
      assertEquals(p.status, NodeLifecycle.Completed, "pass declaration must follow the existing completed chain")
      assertEquals(p.blockedFeedback, None, "pass must NOT land a blockedFeedback")
      assertEquals(p.blockCount, 0)
      assertEquals(p.result, Some(closing), "completed result is the raw output (no render-string rewrite)")
      // down-p 与完成节点无 in/deps 边：零杂散结算（有边投递由 FlowDag 既有 spec 覆盖）
      assertEquals(dAfter.status, NodeLifecycle.Wiring, "no edge → no settlement (unchanged)")
      assertEquals(dAfter.deliveredTo, Nil, "no edge → no delivery (unchanged)")
      // 完成链证据：nodeCompleted 事件 + 零 blocked 痕迹
      assert(evs.exists((t, id, _) => t == "nodeCompleted" && id == pId), "pass must emit nodeCompleted (existing chain)")
      assert(!audit.exists((t, id) => t == "blocked" && id == pId), s"no blocked audit line, got: $audit")
  }

  test("fail declaration: node_report(fail) drives the existing failed chain — result carries the render string, no blocked flip") {
    val ws = tempRoot / "ws-tool-fail"
    os.makeDir.all(ws)
    val system = ActorSystem(s"bts-fail-${scala.util.Random.nextInt(100000)}")
    val closing = "产物编译红，无法在本会话内修复，正式声明失败。本节点收尾。"
    val llm = new ToolCallLlm(
      category = "fail",
      detail = "产物编译红",
      suggestion = "回滚上游依赖后重派",
      closing = closing)
    for
      res <- mkResources(system, tempRoot, llm.handle)
      (rt, events) <- mountEngineOnly("bts-fail", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- rt.store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        "n-down-f" -> NodeDef(id = "n-down-f", name = "down-f", agent = "test-agent",
          status = NodeLifecycle.Wiring, out = List(OutEdge.nebula), createdAt = System.currentTimeMillis()))))
      _ <- nodeEdit(nodeInput("bts-fail", "tool-fail-a", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("will-fail-tool-A"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- waitStatus(rt, "tool-fail-a", Set(NodeLifecycle.Failed))
      fId <- idOf(rt, "tool-fail-a")
      f <- nodeById(rt, fId)
      evs <- events.get
      audit <- readAuditTypes(ws)
      _ <- IO.sleep(300.millis)
      dAfter <- rt.store.snapshot.map(_.nodes.values.find(_.name == "down-f")).map(_.getOrElse(fail("D must exist")))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // 既有 failed 链（failNode：deliverFailed / retry 挂点原样接管）
      assertEquals(f.status, NodeLifecycle.Failed, "fail declaration must follow the existing failed chain")
      assertEquals(f.blockedFeedback, None, "fail must NOT land a blockedFeedback (fail ≠ blocked)")
      assertEquals(f.ttlExpireAt, None, "failed never expires (死亡现场保留，2026-09-07 作者裁定)")
      assert(f.result.exists(_.startsWith("[node-report:fail] 产物编译红")), s"result head must be the fail render string, got: ${f.result}")
      assert(f.result.exists(_.contains("建议: 回滚上游依赖后重派")), s"result carries the suggestion tail, got: ${f.result}")
      // 零结算（failed 停等语义：下游不被 completed 结算）
      assertEquals(dAfter.deliveredTo, Nil, "failed must NOT deliver to downstream (D6 零结算)")
      assert(!evs.exists((t, id, _) => t == "nodeCompleted" && id == fId), "failed must NOT emit nodeCompleted")
      assert(!audit.exists((t, id) => t == "blocked" && id == fId), s"no blocked audit line, got: $audit")
  }

end NodeBlockedToolSignalSpec
