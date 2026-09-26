package nebflow.core.project

import cats.effect.{IO, Ref}
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentLibrary, SharedResources, SpecResources}
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, NodeEditTool, ToolContext}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk}

import scala.concurrent.duration.*
import scala.util.Random

/**
 * **B5 缺口③（loop 回边在目标 running 时被拒）+ 对偶缺口回归** —— 作者 2026-09-17
 * M-3 裁定「待接线队列（到点自动接）」**＋ 运行期回边腿同步排除 Running 目标**
 * （两面口径统一，**缺一半即洞**）。
 *
 * 逐条对齐任务书判据：
 *  - **B① 编辑期正向**：目标 running ⇒ **新增控制边**（`:loop`）入 `NodeDef.pendingOut`
 *    待接线队列（**不整单拒**、回执明说）、`out` 里暂无该边、审计行 `wiring-deferred`；
 *    目标离开 running ⇒ `NodeEngine.applyDeferredWiring`（30s `TtlTick` 扫描腿）**自动
 *    接线** + 审计行 `wiring-applied`；红线① = 控制边**零 in 镜像**。
 *  - **B② 边界（不得放宽）**：指向 running 目标的**新增非控制边**照旧拒 +
 *    错误码 `NODE_TARGET_RUNNING_INPUT_FROZEN`。
 *  - **B③ 过宽面收窄（旧守卫「含未变者」的反例）**：**已接线**的控制边在目标 running
 *    期间再编辑 ⇒ 放行且**仍在 `out`**（既不被拒、也**不被摘进队列**）。
 *  - **B④ 对偶两面同判（硬判据 4）**：编辑期（`NodeTools.partitionDeferredWiring` /
 *    `ensureTargetNotRunning`）与运行期（`NodeEngine.loopReworkAdmission`，`reloopTo`
 *    唯一消费点）对 Running 目标判同一件事——**不在此刻动 + 留痕**，且目标状态零翻转；
 *    `reloopTo` 对该判据的**消费**用接线断言把守（仅改一面 ⇒ 本用例必红）。
 *  - **B⑤ 零回归负控**：无待接线项时扫描腿零写零事件（幂等）。
 *
 * 驱动方式：真实 `NodeEditTool` + 真实 `NodeEngine`（`ProjectRuntime` 夹具，静默 LLM
 * 桩——本 spec 全域零 spawn 会话、零实例、零端口）。
 */
class DeferredWiringSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-deferred-wiring"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "general")

  os.write.over(
    tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"general executor","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  /** 静默 LLM 桩（本 spec 只验 0 spawn 的编辑期/判据面）。 */
  private class QuietLlm:
    val inputs: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)

    def handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(
        req: LlmRequest,
        onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
      ): Stream[IO, StreamChunk] =
        val text = req.messages.map(_.textContent).mkString("\n")
        Stream.eval(inputs.update(_ :+ text)) >>
          Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  private def mkCtx(res: SharedResources, system: ActorSystem, ws: String): ToolContext =
    ToolContext(
      projectRoot = ws,
      sessionId = Some("deferred-wiring-sid"),
      rootSessionId = Some("nebula-root"),
      sharedResources = Some(res),
      actorSystem = Some(system)
    )

  private def nodeEdit(input: Json, ctx: ToolContext): IO[Either[String, String]] =
    NodeEditTool.call(input.asObject.get, ctx).map(_.left.map(_.message))

  // 建位期声明闸（nodegate 批 0accce90e「四项④」NODE_PLUGINS_UNDECLARED，判据源
  // NodeEditTool.scala:115）：建位必须**显式声明**能力面——`plugins=[]` = 显式「无需
  // 能力面」（本 spec 全域零插件）。同形先例：NodeEdgeRepairSpec.scala:146 /
  // NodeBlockedToolSignalSpec.scala:174（同批注入的 `("plugins" -> Json.arr())`）。
  private def nodeInput(project: String, nodename: String, extra: (String, Json)*): Json =
    Json.obj(
      ("project" -> Json
        .fromString(project)) :: ("nodename" -> Json.fromString(nodename)) :: ("plugins" -> Json.arr()) :: extra.toList*
    )

  /** verifier 创建/编辑输入（role create-only ⇒ 必须建点声明；fail 路由 = `:loop` 控制边）。 */
  private def verifierInput(project: String, nodename: String, out: String): Json =
    nodeInput(
      project,
      nodename,
      "description" -> Json.fromString(s"verifier $nodename"),
      "task" -> Json.fromString(s"$nodename task"),
      "role" -> Json.fromString(NodeRoles.Verifier),
      "out" -> Json.fromString(out)
    )

  /**
   * verifier **编辑**输入：`role` 是 create-only（建位后带 role 一律 NODE_ROLE_CREATE_ONLY，
   * 真实分发器/节点亦如此），故编辑面只发 out。
   */
  private def verifierEditInput(project: String, nodename: String, out: String): Json =
    nodeInput(
      project,
      nodename,
      "description" -> Json.fromString(s"verifier $nodename"),
      "task" -> Json.fromString(s"$nodename task"),
      "out" -> Json.fromString(out)
    )

  private def mountProject(name: String, ws: os.Path, system: ActorSystem, res: SharedResources): IO[ProjectRuntime] =
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
      pd = ProjectDef(
        name = name,
        workspace = ws.toString,
        agentFile = (ws / "AGENTS.md").toString,
        createdAt = System.currentTimeMillis()
      )
      rt = ProjectRuntime(pd, store, engine, system, res, None)
      _ <- ProjectRuntimeRegistry.register(rt)
    yield rt

  /** 直种节点（绕过 NodeEdit 校验，用于把状态摆到待验判据上）。 */
  private def seed(rt: ProjectRuntime, nodes: NodeDef*): IO[Unit] =
    rt.store.mutate(s => s.copy(nodes = s.nodes ++ nodes.map(n => n.id -> n).toMap)).void

  private def mkNode(
    id: String,
    name: String,
    status: String,
    role: String = NodeRoles.Task,
    out: List[OutEdge] = Nil
  ): NodeDef =
    NodeDef(
      id = id,
      name = name,
      agent = "general",
      task = Some(s"$name task"),
      status = status,
      out = out,
      role = role,
      createdAt = System.currentTimeMillis()
    )

  private def nodeByName(rt: ProjectRuntime, name: String): IO[NodeDef] =
    rt.store.snapshot.map(_.nodes.values.find(_.name == name).getOrElse(fail(s"node '$name' must exist")))

  private def readAudit(ws: os.Path): IO[List[(String, String, String)]] =
    IO.blocking(os.read(ws / ".nebflow" / FlowMapEventLog.FileName))
      .map(_.linesIterator.toList.filter(_.trim.nonEmpty))
      .map(lines =>
        lines.flatMap(l =>
          io.circe.parser
            .parse(l)
            .toOption
            .map(j =>
              (
                j.hcursor.get[String]("type").getOrElse(""),
                j.hcursor.get[String]("nodeId").getOrElse(""),
                j.hcursor.get[String]("summary").getOrElse("")
              )
            )
        )
      )
      .handleError(_ => Nil)

  private def stop(system: ActorSystem): IO[Unit] = system.stopAll.handleErrorWith(_ => IO.unit)

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  private val LoopW = OutEdge("n-w", Set(OutEdge.Fail), OutEdge.Loop)
  private val LoopW2 = OutEdge("n-w2", Set(OutEdge.Fail), OutEdge.Loop)

  test("B① 缺口③ 编辑期：running 目标的新控制边入待接线队列（不整单拒）；目标离开 running 后自动接线 + 落痕") {
    val ws = tempRoot / "ws-defer-a"
    os.makeDir.all(ws)
    val system = ActorSystem(s"b5defer-a-${Random.nextInt(100000)}")
    val llm = new QuietLlm
    for
      res <- SpecResources.mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("defer-a", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- seed(
        rt,
        mkNode("n-x", "x", NodeLifecycle.Wiring),
        mkNode("n-w", "w", NodeLifecycle.Wiring),
        mkNode("n-w2", "w2", NodeLifecycle.Running)
      )
      // verifier 建位（建位即声明 exactly-one fail 路由；n-w 此刻未运行 ⇒ 直接线）
      r0 <- nodeEdit(verifierInput("defer-a", "v", "(pass)n-x, (fail)n-w:loop"), ctx)
      // 目标 n-w2 **running**：把 fail 路由改接到它 = 一条**新的控制边**
      r1 <- nodeEdit(verifierEditInput("defer-a", "v", "(pass)n-x, (fail)n-w2:loop"), ctx)
      v1 <- nodeByName(rt, "v")
      evs1 <- readAudit(ws)
      // 目标离开 running（终态）⇒ 既有 30s 扫描腿的同一入口自动接线
      _ <- rt.store.mutate(s =>
        s.copy(nodes =
          s.nodes.updated("n-w2", s.nodes("n-w2").copy(status = NodeLifecycle.Completed, result = Some("w2 done")))
        )
      )
      _ <- rt.engine.applyDeferredWiring()
      v2 <- nodeByName(rt, "v")
      evs2 <- readAudit(ws)
      w2in <- rt.store.getNode("n-w2").map(_.map(_.in).getOrElse(Nil))
      _ <- stop(system)
    yield
      assert(r0.isRight, s"verifier creation must be accepted, got: $r0")
      assert(r1.isRight, s"a NEW control edge to a running target must be queued, not rejected — got: $r1")
      assert(r1.exists(_.contains("wiring deferred")), s"the receipt must say the edge is queued, got: $r1")
      assert(!OutEdge.canonical(v1.out).contains(LoopW2), s"the queued edge must NOT be wired yet, got: ${v1.out}")
      assert(OutEdge.canonical(v1.pendingOut).contains(LoopW2), s"the queue must hold it, got: ${v1.pendingOut}")
      assert(
        evs1.exists((t, id, _) => t == FlowMapEventLog.WiringDeferredType && id == v1.id),
        s"a ${FlowMapEventLog.WiringDeferredType} audit line is the mechanism's visibility condition, got: $evs1"
      )
      assert(
        OutEdge.canonical(v2.out).contains(LoopW2),
        s"leaving running must auto-wire the queued edge, got: ${v2.out}"
      )
      assert(v2.pendingOut.isEmpty, s"the queue must drain on wiring, got: ${v2.pendingOut}")
      assert(
        evs2.exists((t, id, _) => t == FlowMapEventLog.WiringAppliedType && id == v2.id),
        s"a ${FlowMapEventLog.WiringAppliedType} audit line is expected, got: $evs2"
      )
      assert(!w2in.contains(v2.id), s"red line ①: a control edge must never write the in mirror, got in=${w2in}")
    end for
  }

  test("B② 缺口③ 边界不得放宽：指向 running 目标的**新增非控制边**照旧拒，且带错误码") {
    val ws = tempRoot / "ws-defer-b"
    os.makeDir.all(ws)
    val system = ActorSystem(s"b5defer-b-${Random.nextInt(100000)}")
    val llm = new QuietLlm
    for
      res <- SpecResources.mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("defer-b", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- seed(rt, mkNode("n-run", "n-run", NodeLifecycle.Running))
      // 建位（悬空合法）后**编辑**加真实输入边——状态守卫只在编辑面（`outProvided`）
      r0 <- nodeEdit(
        nodeInput(
          "defer-b",
          "t-plain",
          "description" -> Json.fromString("plain task node"),
          "task" -> Json.fromString("t-plain task")
        ),
        ctx
      )
      r <- nodeEdit(
        nodeInput(
          "defer-b",
          "t-plain",
          "description" -> Json.fromString("plain task node"),
          "out" -> Json.fromString("n-run")
        ),
        ctx
      )
      _ <- stop(system)
    yield
      assert(r0.isRight, s"creating a dangling node must be accepted, got: $r0")
      val msg =
        r.fold(identity, ok => fail(s"a real input edge into a running target must still be rejected, got: $ok"))
      assert(msg.contains("NODE_TARGET_RUNNING_INPUT_FROZEN"), s"the rejection must carry its error code, got: $msg")
      assert(msg.contains("running"), s"the rejection must name the frozen state, got: $msg")
    end for
  }

  test("B③ 缺口③ 过宽面收窄：**已接线**的控制边在目标 running 期间编辑 ⇒ 放行且仍在 out（不摘进队列）") {
    val ws = tempRoot / "ws-defer-c"
    os.makeDir.all(ws)
    val system = ActorSystem(s"b5defer-c-${Random.nextInt(100000)}")
    val llm = new QuietLlm
    for
      res <- SpecResources.mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("defer-c", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- seed(rt, mkNode("n-x", "x", NodeLifecycle.Wiring), mkNode("n-w", "w", NodeLifecycle.Wiring))
      r0 <- nodeEdit(verifierInput("defer-c", "v", "(pass)n-x, (fail)n-w:loop"), ctx)
      v0 <- nodeByName(rt, "v")
      _ <- rt.store.mutate(s =>
        s.copy(nodes = s.nodes.updated("n-w", s.nodes("n-w").copy(status = NodeLifecycle.Running)))
      )
      // 目标 running + 同一条（未变的）控制边再提交 ⇒ 既不拒、也不入队
      r1 <- nodeEdit(verifierEditInput("defer-c", "v", "(pass)n-x, (fail)n-w:loop"), ctx)
      v1 <- nodeByName(rt, "v")
      _ <- stop(system)
    yield
      assert(r0.isRight, s"verifier creation must be accepted, got: $r0")
      assert(OutEdge.canonical(v0.out).contains(LoopW), s"precondition: the loop edge is wired, got: ${v0.out}")
      assert(r1.isRight, s"an already-wired control edge must survive an edit while the target runs, got: $r1")
      assert(
        OutEdge.canonical(v1.out).contains(LoopW),
        s"the wired control edge must stay in out (never unwired by a re-declaration), got: ${v1.out}"
      )
      assert(v1.pendingOut.isEmpty, s"…and must NOT be pushed into the queue, got: ${v1.pendingOut}")
    end for
  }

  test("B④ 对偶两面同判（硬）：编辑期与运行期对 Running 目标同判「不在此刻动＋留痕」；仅改一面 ⇒ 本用例必红") {
    val ws = tempRoot / "ws-defer-d"
    os.makeDir.all(ws)
    val system = ActorSystem(s"b5defer-d-${Random.nextInt(100000)}")
    val llm = new QuietLlm
    for
      res <- SpecResources.mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("defer-d", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- seed(
        rt,
        mkNode("n-x", "x", NodeLifecycle.Wiring),
        mkNode("n-w", "w", NodeLifecycle.Wiring),
        mkNode("n-w2", "w2", NodeLifecycle.Running)
      )
      _ <- nodeEdit(verifierInput("defer-d", "v", "(pass)n-x, (fail)n-w:loop"), ctx)
      r <- nodeEdit(verifierEditInput("defer-d", "v", "(pass)n-x, (fail)n-w2:loop"), ctx)
      v <- nodeByName(rt, "v")
      w2 <- rt.store.getNode("n-w2").map(_.getOrElse(fail("n-w2 must exist")))
      evs <- readAudit(ws)
      _ <- stop(system)
      // 2026-09-25 F 步重钉：reloopTo/verifierFailR/sweepLoopBudgets 等 loop 簇自
      // NodeEngine 迁至 NodeLoopRunner（self-type trait，行为保持重构）——源读数扩为
      // 跨文件聚合（先例 SubAgentInboxMirrorSpec 2.3 增补），锚文本不变、判据语义不变。
      src = os.read(os.pwd / "src/main/scala/nebflow/core/project/NodeEngine.scala") +
        os.read(os.pwd / "src/main/scala/nebflow/core/project/NodeLoopRunner.scala")
    yield
      // ── 面①：编辑期（NodeTools 守卫侧）────────────────────────────────
      assert(r.isRight, s"edit face: a new control edge to a running target is queued, not rejected, got: $r")
      assertEquals(
        w2.status,
        NodeLifecycle.Running,
        "edit face must leave a running target untouched (zero state flip)"
      )
      assert(OutEdge.canonical(v.pendingOut).contains(LoopW2), s"edit face must queue the edge, got: ${v.pendingOut}")
      assert(
        evs.exists((t, _, _) => t == FlowMapEventLog.WiringDeferredType),
        "edit face must leave a trace (the queue's visibility condition)"
      )
      // ── 面②：运行期（NodeLoopRunner 回边腿 reloopTo；2026-09-25 F 步迁出，原
      //    NodeEngine:4324-4328）────────────────────────────────────────
      assert(
        NodeEngine.loopReworkAdmission(NodeLifecycle.Running).isLeft,
        "runtime face: the same judgement — a running target is excluded from the loop re-run reset"
      )
      assert(
        NodeEngine.loopReworkAdmission(NodeLifecycle.Running).left.exists(_.contains("running")),
        "the exclusion reason must name the frozen state"
      )
      assertEquals(
        NodeEngine.loopReworkAdmission(NodeLifecycle.Completed),
        Right(()),
        "completed (just judged) stays re-runnable — the loop's main scenario, unchanged"
      )
      assertEquals(NodeEngine.loopReworkAdmission(NodeLifecycle.Pending), Right(()), "non-terminal stays re-runnable")
      assert(NodeEngine.loopReworkAdmission(NodeLifecycle.Blocked).isLeft, "dispatcher-owned scenes stay excluded")
      // ── 接线断言：reloopTo 是唯一消费点（撤消费 ⇒ 本用例必红）──────────
      assertEquals(
        "NodeEngine\\.loopReworkAdmission\\(".r.findAllIn(src).size,
        1,
        "reloopTo must be the single consumer of the admission predicate"
      )
      val reloopIdx = src.indexOf("private def reloopTo(")
      val callIdx = src.indexOf("NodeEngine.loopReworkAdmission(")
      // 2026-09-25 F 步重钉：原边界锚「**待接线队列扫描腿」属 applyDeferredWiring
      // （留守 NodeEngine，聚合源中位于 reloopTo 之前）——改锚紧随 reloopTo 之后的
      // sweepLoopBudgets 段注，窗口语义不变（判定调用仍须落在 reloopTo 体内）。
      val bodyEnd = src.indexOf("loop 时间帽扫描腿", reloopIdx)
      assert(
        reloopIdx > 0 && callIdx > reloopIdx && bodyEnd > callIdx,
        s"the admission call must live inside the reloopTo body (reloop=$reloopIdx call=$callIdx end=$bodyEnd)"
      )
    end for
  }

  test("B⑤ 零回归负控：无待接线项时扫描腿零写零事件（幂等）") {
    val ws = tempRoot / "ws-defer-e"
    os.makeDir.all(ws)
    val system = ActorSystem(s"b5defer-e-${Random.nextInt(100000)}")
    val llm = new QuietLlm
    for
      res <- SpecResources.mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("defer-e", ws, system, res)
      _ <- seed(rt, mkNode("n-plain", "plain", NodeLifecycle.Running))
      snap0 <- rt.store.snapshot
      _ <- rt.engine.applyDeferredWiring()
      _ <- rt.engine.applyDeferredWiring()
      snap1 <- rt.store.snapshot
      evs <- readAudit(ws)
      _ <- stop(system)
    yield
      assertEquals(snap1.nodes, snap0.nodes, "a node graph without pendingOut must be untouched by the sweep")
      assert(
        !evs.exists((t, _, _) => t == FlowMapEventLog.WiringAppliedType || t == FlowMapEventLog.WiringDeferredType),
        s"the sweep must write no wiring events when there is nothing to wire, got: $evs"
      )
    end for
  }
end DeferredWiringSpec
