package nebflow.core.project

import cats.effect.{IO, Ref}
import fs2.Stream
import io.circe.Json
import io.circe.parser.parse as jsonParse
import io.circe.syntax.*
import io.circe.JsonObject
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentLibrary, SharedResources, SpecResources}
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, NodeEditTool, ToolContext}
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.llm.ModelCandidate
import nebflow.shared.{
  ContentBlock,
  LlmHandle,
  LlmRequest,
  LlmResponse,
  PathUtil,
  StreamChunk,
  ThinkingConfig,
  ToolCall
}

import scala.concurrent.duration.*

/**
 * node_report 结构化申报集成回归（真实引擎路径驱动；blocked 终态退役后的语义面）：
 * - **blocked 申报在工具入参层即拒**（NODE_REPORT_CATEGORY），登记表零残留——
 *   引擎侧不存在 blocked 终态写点，申报不可能驱动 blocked 终态；
 * - pass 申报 → 既有 completed 链（blockedFeedback 零落库、result 原文、下游结算）
 * - fail 申报 → 既有 completed 链 + fail 边选通（verdict ≠ 节点状态）
 * - finish 申报 → 既有 completed 链
 * - 误判回归：正文恰含 BLOCKED 字样、未调工具 → completed 不变
 * - 收尾报告后申报残留：cancelled 路径不消费（cleanupRunTables remove 对称清理，
 *   幂等不残留）
 */
class NodeBlockedToolSignalSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 240.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-node-blocked-tool-signal"
  private val originalRoot = PathUtil.dataRoot

  // 构造期换根纪律（2026-09-18 判例 6a68914c1 同款残件，S1 组负载敏红族）：**先清树，最后换根**
  // —— 若先 setDataRoot 再 os.remove.all，此刻全局根已指向本树，前序套件收尾期仍在跑的
  // 异步写入（按 PathUtil.dataRoot 落盘）会在删树遍历中途把目录重新建出来 ⇒ 构造期
  // DirectoryNotEmptyException / 组合跑互踩（本 spec 与 NodeBgCompletionGateSpec 是两个残件）。
  os.remove.all(tempRoot)
  PathUtil.setDataRoot(tempRoot)

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

  /**
   * 工具申报状态机 LLM：首 turn 发 node_report 工具调用（category 参数化——
   * blocked/pass/fail 三语义共用驱动），见到工具结果后输出无锚定收尾文本——
   * 证明终态由工具通道驱动而非文本形态。
   * 探测走 ContentBlock.ToolResult（textContent 只含 Text 块——tool_result 在
   * role=user 消息的块列表里，文本面不可见）。
   */
  private class ToolCallLlm(category: String, detail: String, suggestion: String, closing: String):
    val inputs: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)

    private def ackText: String =
      // nrloop 一期（2026-09-12）：confirm 回执按角色/类别分支——blocked 面文本不变；
      // verdict 面（pass/fail）与 finish 面的回执改为 `[OK] verdict recorded (<cat>)`
      // / `[OK] node report (finish) recorded`。回执文本变了 ⇒ 本探测串同步（否则
      // stub 认不出申报已被登记 ⇒ 重发同一工具调用 ⇒ LoopGuard 8 连击冻结会话）。
      if nebflow.core.tools.NodeReportToolDef.isFinish(category)
      then s"[OK] node report ($category) recorded"
      else s"[OK] verdict recorded ($category)"

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
              StreamChunk.ToolCallChunk(
                ToolCall(
                  "rb-1",
                  "node_report",
                  JsonObject(
                    "category" -> category.asJson,
                    "detail" -> detail.asJson,
                    "suggestion" -> suggestion.asJson
                  )
                )
              ),
              StreamChunk.Done(Some("tool_use"), None)
            )

        end if

      end sendStream

  end ToolCallLlm

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
    Json.obj(
      ("project" -> Json
        .fromString(project)) :: ("nodename" -> Json.fromString(nodename)) :: ("plugins" -> Json.arr()) :: extra.toList*
    )

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
      pd = ProjectDef(
        name = name,
        workspace = ws.toString,
        agentFile = (ws / "AGENTS.md").toString,
        createdAt = System.currentTimeMillis()
      )
      rt = ProjectRuntime(pd, store, engine, system, res, None)
      _ <- ProjectRuntimeRegistry.register(rt)
    yield (rt, events)

  private def idOf(rt: ProjectRuntime, name: String): IO[String] =
    rt.store.snapshot.map(_.nodes.values.find(_.name == name)).map {
      case Some(n) => n.id
      case None => fail(s"node '$name' must exist")
    }

  private def nodeById(rt: ProjectRuntime, id: String): IO[NodeDef] =
    rt.store.snapshot.map(_.nodes.get(id)).map(_.getOrElse(fail(s"node '$id' must exist")))

  private def waitStatus(rt: ProjectRuntime, name: String, statuses: Set[String]): IO[Unit] =
    waitUntil(20.seconds) {
      rt.store.snapshot.map(_.nodes.values.find(_.name == name)).flatMap {
        case Some(n) => IO.pure(statuses.contains(n.status))
        case None => IO.pure(false)
      }
    }

  private def readAuditTypes(ws: os.Path): IO[List[(String, String)]] =
    IO.blocking(os.read(ws / ".nebflow" / FlowMapEventLog.FileName))
      .map(_.linesIterator.toList.filter(_.trim.nonEmpty))
      .map(lines =>
        lines.flatMap(l =>
          jsonParse(l).toOption
            .map(j => (j.hcursor.get[String]("type").getOrElse(""), j.hcursor.get[String]("nodeId").getOrElse("")))
        )
      )
      .handleError(_ => Nil)

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  // ── blocked 申报入参层即拒：登记表零残留（引擎无 blocked 终态写点）──

  test(
    "blocked declaration is rejected at the tool layer (NODE_REPORT_CATEGORY) and never reaches the registry"
  ) {
    val ws = tempRoot / "ws-tool-blocked-rejected"
    os.makeDir.all(ws)
    val ctx = ToolContext(
      projectRoot = ws.toString,
      sessionId = Some("node-blk-sid"),
      flowNodeId = Some("n-blk"),
      rootSessionId = Some("nebula-root")
    )
    for
      r <- nebflow.core.tools.NodeReportToolDef.call(
        JsonObject(
          "category" -> Json.fromString("blocked"),
          "detail" -> Json.fromString("上游未完成"),
          "suggestion" -> Json.fromString("等上游")
        ),
        ctx
      )
      drained <- NodeReportRegistry.drain("node-blk-sid")
    yield
      assert(r.isLeft, s"blocked must not be a reportable category, got: $r")
      assert(r.left.exists(_.message.contains("NODE_REPORT_CATEGORY")), s"error must carry the code, got: $r")
      assertEquals(drained, None, "a rejected declaration must never be registered (no blocked write point downstream)")
  }

  // ── 误判回归（spec §9.3 第一行）：正文含 BLOCKED 字样、未调工具 → completed ──

  test("misdetection regression: final text mentioning BLOCKED mid-report WITHOUT tool call completes normally") {
    val ws = tempRoot / "ws-misdetect"
    os.makeDir.all(ws)
    val system = ActorSystem(s"bts-mis-${scala.util.Random.nextInt(100000)}")
    val tricky = "上游节点曾申报 **BLOCKED**（现已恢复），我方据其产出完成交付。"
    val llm = FuncLlm(text => if text.contains("will-finish-with-word") then IO.pure(tricky) else IO.pure("ok"))
    for
      res <- SpecResources.mkResources(system, tempRoot, llm.handle)
      (rt, events) <- mountEngineOnly("bts-mis", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- nodeEdit(
        nodeInput(
          "bts-mis",
          "finisher",
          "description" -> Json.fromString("test node purpose"),
          "task" -> Json.fromString("will-finish-with-word"),
          "out" -> Json.fromString("Nebula")
        ),
        ctx
      )
      _ <- waitStatus(rt, "finisher", Set(NodeLifecycle.Completed))
      fId <- idOf(rt, "finisher")
      f <- nodeById(rt, fId)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(f.status, NodeLifecycle.Completed, "BLOCKED-word prose must never flip the terminal state")
      assertEquals(f.blockedFeedback, None, "no blockedFeedback without a declaration")
      assertEquals(f.blockCount, 0)
      assertEquals(f.result, Some(tricky), "completed result is the raw output (no render-string rewrite)")
    end for
  }

  // ── 申报残留对称清理：cancelled 路径不消费、清理幂等（cleanupRunTables 钩子）──

  test("leftover sweep: cancelled session's declaration is removed by cleanup (no cross-session leak)") {
    val ws = tempRoot / "ws-sweep"
    os.makeDir.all(ws)
    // 直接驱动 registry 语义 + NodeEngine 清理钩子等价面：register → remove → drain=None
    val sid = "node-sweepsid"
    for
      // #239② 面①：register 增加持久化归属（workspace/project/nodeId）——本用例的语义
      // 断言（remove → drain=None）逐字不变，只是登记点现在同步落一份日志（同一 ws）。
      _ <- NodeReportRegistry.register(ws.toString, "spec", "n-sweep", sid, NodeReportFeedback("other", "残留申报", ""))
      _ <- NodeReportRegistry.remove(sid)
      drained <- NodeReportRegistry.drain(sid)
      _ <- NodeReportRegistry.remove(sid) // 幂等二次
      again <- NodeReportRegistry.drain(sid)
    yield
      assertEquals(drained, None, "removed declaration must not be consumable")
      assertEquals(again, None, "remove is idempotent")
  }

  // ── NodeReport 泛化批扩面：pass/fail 主动申报集成重放（作者裁定三语义统一迁移）──

  test(
    "finish declaration (role=task): node_report(finish) drives the existing completed chain — no blockedFeedback, downstream settled"
  ) {
    val ws = tempRoot / "ws-tool-pass"
    os.makeDir.all(ws)
    val system = ActorSystem(s"bts-pass-${scala.util.Random.nextInt(100000)}")
    val closing = "验收条件逐条核对通过，正式声明完成。本节点收尾。"
    val llm = new ToolCallLlm(category = "finish", detail = "验收条件逐条核对通过", suggestion = "", closing = closing)
    for
      res <- SpecResources.mkResources(system, tempRoot, llm.handle)
      (rt, events) <- mountEngineOnly("bts-pass", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // 下游 wiring（completed 链 → out 结算断言面；store 直种同上）
      _ <- rt.store.mutate(s =>
        s.copy(nodes =
          s.nodes ++ Map(
            "n-down-p" -> NodeDef(
              id = "n-down-p",
              name = "down-p",
              agent = "test-agent",
              status = NodeLifecycle.Wiring,
              out = List(OutEdge.root),
              createdAt = System.currentTimeMillis()
            )
          )
        )
      )
      _ <- nodeEdit(
        nodeInput(
          "bts-pass",
          "tool-pass-a",
          "description" -> Json.fromString("test node purpose"),
          "task" -> Json.fromString("will-pass-tool-A"),
          "out" -> Json.fromString("Nebula")
        ),
        ctx
      )
      _ <- waitStatus(rt, "tool-pass-a", Set(NodeLifecycle.Completed))
      pId <- idOf(rt, "tool-pass-a")
      p <- nodeById(rt, pId)
      // 同步点（同上）：nodeCompleted 是**正**断言 ⇒ 有界等它到；零 blocked 审计行是
      // **负**断言 ⇒ settle 必须落在**读之前**（原形态 sleep 在读之后 = 同步点 0）。
      _ <- waitUntil(10.seconds)(
        events.get.map(_.exists((t, id, _) => t == "nodeCompleted" && id == pId))
      )
      evs <- events.get
      _ <- IO.sleep(300.millis)
      audit <- readAuditTypes(ws)
      dAfter <- rt.store.snapshot.map(_.nodes.values.find(_.name == "down-p")).map(_.getOrElse(fail("D must exist")))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // 既有 completed 链（finish 申报与无申报同链：CompletionGate → completedNode）
      assertEquals(p.status, NodeLifecycle.Completed, "finish declaration must follow the existing completed chain")
      assertEquals(p.blockedFeedback, None, "finish must NOT land a blockedFeedback")
      assertEquals(p.blockCount, 0)
      assertEquals(p.result, Some(closing), "completed result is the raw output (no render-string rewrite)")
      assertEquals(p.role, NodeRoles.Task, "role stays the create-time default (task)")
      // down-p 与完成节点无 in/deps 边：零杂散结算（有边投递由 FlowDag 既有 spec 覆盖）
      assertEquals(dAfter.status, NodeLifecycle.Wiring, "no edge → no settlement (unchanged)")
      assertEquals(dAfter.deliveredTo, Nil, "no edge → no delivery (unchanged)")
      // 完成链证据：nodeCompleted 事件 + 零 blocked 痕迹
      assert(
        evs.exists((t, id, _) => t == "nodeCompleted" && id == pId),
        "finish must emit nodeCompleted (existing chain)"
      )
      assert(!audit.exists((t, id) => t == "blocked" && id == pId), s"no blocked audit line, got: $audit")
    end for
  }

  test(
    "verifier verdict=pass: node completes with lastVerdict=pass and ITS pass edge is delivered (verifier pass is not node failure/success of a task)"
  ) {
    val ws = tempRoot / "ws-tool-vpass"
    os.makeDir.all(ws)
    val system = ActorSystem(s"bts-vpass-${scala.util.Random.nextInt(100000)}")
    val closing = "被判定对象合格，正式给出 pass verdict。本节点收尾。"
    val llm = new ToolCallLlm(category = "pass", detail = "验收条件逐条核对通过", suggestion = "", closing = closing)
    for
      res <- SpecResources.mkResources(system, tempRoot, llm.handle)
      (rt, events) <- mountEngineOnly("bts-vpass", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // 回边目标（worker，待二期执行腿驱动重跑）+ 落点（pass 边下游）
      _ <- rt.store.mutate(s =>
        s.copy(nodes =
          s.nodes ++ Map(
            "n-work-vp" -> NodeDef(
              id = "n-work-vp",
              name = "work-vp",
              agent = "test-agent",
              status = NodeLifecycle.Wiring,
              out = List(OutEdge.root),
              createdAt = System.currentTimeMillis()
            ),
            "n-land-vp" -> NodeDef(
              id = "n-land-vp",
              name = "land-vp",
              agent = "test-agent",
              status = NodeLifecycle.Wiring,
              out = List(OutEdge.root),
              createdAt = System.currentTimeMillis()
            )
          )
        )
      )
      _ <- nodeEdit(
        nodeInput(
          "bts-vpass",
          "tool-vpass-a",
          "description" -> Json.fromString("verifier node purpose"),
          "task" -> Json.fromString("will-verdict-pass"),
          "role" -> Json.fromString("verifier"),
          "out" -> Json.fromString("(pass)n-land-vp, (fail)n-work-vp:loop")
        ),
        ctx
      )
      _ <- waitStatus(rt, "tool-vpass-a", Set(NodeLifecycle.Completed))
      vId <- idOf(rt, "tool-vpass-a")
      v <- nodeById(rt, vId)
      // 负断言（零 loop-round 审计行，同文件同族第三处残件）：settle 移到读之前。
      _ <- IO.sleep(300.millis)
      audit <- readAuditTypes(ws)
      land <- rt.store.snapshot.map(_.nodes.get("n-land-vp").getOrElse(fail("landing node must exist")))
      work <- rt.store.snapshot.map(_.nodes.get("n-work-vp").getOrElse(fail("worker node must exist")))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(v.status, NodeLifecycle.Completed, "a pass verdict completes the verifier node normally")
      assertEquals(v.role, NodeRoles.Verifier, "role=verifier persists (create-only)")
      assertEquals(
        v.lastVerdict,
        Some("pass"),
        "lastVerdict must be recorded (deliverOut's verdict awareness reads it)"
      )
      assertEquals(v.result, Some(closing), "raw output kept (no render rewrite for pass)")
      assert(
        land.deliveredTo.contains(vId),
        s"the pass edge must deliver to the landing node, got: ${land.deliveredTo}"
      )
      assertEquals(work.status, NodeLifecycle.Wiring, "a pass verdict must not touch the re-run target")
      assert(!audit.exists((t, id) => t == "loop-round" && id == vId), "pass must not consume a loop round")
    end for
  }

  test(
    "verifier verdict=fail: THIS node still completes; the pass edge is NOT delivered; the fail control edge is recorded (no failNode)"
  ) {
    val ws = tempRoot / "ws-tool-fail"
    os.makeDir.all(ws)
    val system = ActorSystem(s"bts-fail-${scala.util.Random.nextInt(100000)}")
    val closing = "被判定对象不合格，正式给出 fail verdict。本节点收尾。"
    val llm = new ToolCallLlm(category = "fail", detail = "产物编译红", suggestion = "回滚上游依赖后重派", closing = closing)
    for
      res <- SpecResources.mkResources(system, tempRoot, llm.handle)
      (rt, events) <- mountEngineOnly("bts-fail", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- rt.store.mutate(s =>
        s.copy(nodes =
          s.nodes ++ Map(
            "n-work-f" -> NodeDef(
              id = "n-work-f",
              name = "work-f",
              agent = "test-agent",
              status = NodeLifecycle.Wiring,
              out = List(OutEdge.root),
              createdAt = System.currentTimeMillis()
            ),
            "n-land-f" -> NodeDef(
              id = "n-land-f",
              name = "land-f",
              agent = "test-agent",
              status = NodeLifecycle.Wiring,
              out = List(OutEdge.root),
              createdAt = System.currentTimeMillis()
            )
          )
        )
      )
      _ <- nodeEdit(
        nodeInput(
          "bts-fail",
          "tool-fail-a",
          "description" -> Json.fromString("verifier node purpose"),
          "task" -> Json.fromString("will-verdict-fail"),
          "role" -> Json.fromString("verifier"),
          "out" -> Json.fromString("(pass)n-land-f, (fail)n-work-f:loop")
        ),
        ctx
      )
      _ <- waitStatus(rt, "tool-fail-a", Set(NodeLifecycle.Completed))
      fId <- idOf(rt, "tool-fail-a")
      f <- nodeById(rt, fId)
      // 同族第四处残件：`nodeCompleted`（:469 正断言）的读同样零同步点 ⇒ 有界等它到。
      _ <- waitUntil(10.seconds)(
        events.get.map(_.exists((t, id, _) => t == "nodeCompleted" && id == fId))
      )
      evs <- events.get
      // 同步点（S1 组负载敏红修复）：节点终态先落 store，审计行到 flow-map-events.jsonl 更晚
      // ——原形态是「readAuditTypes **之后** IO.sleep(300ms)」，同步点为 0：负载下读到空表
      // （实测红：`a loop-round event must be logged, got: List()`）。改为**有界等它到**：
      // 等 `loop-round/fId` 落盘再读；下面两条断言本体逐字不变。
      _ <- waitUntil(10.seconds)(
        readAuditTypes(ws).map(_.exists((t, id) => t == "loop-round" && id == fId))
      )
      audit <- readAuditTypes(ws)
      _ <- IO.sleep(300.millis)
      land <- rt.store.snapshot.map(_.nodes.get("n-land-f").getOrElse(fail("landing node must exist")))
      work <- rt.store.snapshot.map(_.nodes.get("n-work-f").getOrElse(fail("worker node must exist")))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // 核心语义翻转（设计 §3.1 纪律③）：fail 是 verdict，**不是**节点失败
      assertEquals(
        f.status,
        NodeLifecycle.Completed,
        "a fail VERDICT must NOT fail the verifier node (verdict ≠ node status)"
      )
      assertEquals(f.lastVerdict, Some("fail"), "lastVerdict=fail recorded")
      assertEquals(f.blockedFeedback, None, "fail must NOT land a blockedFeedback (fail ≠ blocked)")
      assertEquals(f.result, Some(closing), "result = the raw closing output (旧 render 串改写已被取代)")
      assert(
        !f.result.exists(_.startsWith("[node-report:fail]")),
        s"the old render-string rewrite must be gone, got: ${f.result}"
      )
      // 选通面：pass 边**不投**；回边只登记（执行腿落二期）
      assertEquals(
        land.deliveredTo,
        Nil,
        "verdict=fail must NOT deliver along the pass edge (verdict-aware deliverOut)"
      )
      assertEquals(
        work.status,
        NodeLifecycle.Wiring,
        "the :loop control edge never settles the target's barrier (execution leg is phase 2)"
      )
      assert(
        work.loopStartedAt.isDefined,
        "the target's loop timer starts on the first in-budget fail (wall-clock cap source)"
      )
      assert(audit.exists((t, id) => t == "loop-round" && id == fId), s"a loop-round event must be logged, got: $audit")
      assert(!audit.exists((t, id) => t == "loop-budget" && id == fId), "budget not exhausted yet — no circuit-break")
      // 既有 completed 链照样走；零 blocked 痕迹
      assert(
        evs.exists((t, id, _) => t == "nodeCompleted" && id == fId),
        "the verifier emits nodeCompleted (completed chain)"
      )
      assert(!audit.exists((t, id) => t == "blocked" && id == fId), s"no blocked audit line, got: $audit")
    end for
  }

end NodeBlockedToolSignalSpec
