package nebflow.core.project

import cats.effect.{IO, Ref}
import fs2.Stream
import io.circe.Json
import io.circe.parser.parse as jsonParse
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{SharedResources, SpecResources}
import nebflow.core.tools.{NodeEditTool, ToolContext}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk}

import scala.concurrent.duration.*

/**
 * blocked 终态退役回归（no-blocked-semantics 第一阶段）：blocked 不再是可达终态。
 *
 * 覆盖：
 * - 文本锚定面退役：节点最终输出以 BLOCKED 锚定 + JSON 体 → 走 completed 原链
 *   （旧 BlockedReader 降级面已删，BLOCKED 文本不再产生 blocked 终态）；
 * - 生命周期集合哨兵：Blocked ∉ Terminal / All / ChainCancelScope（token 仅为
 *   legacy 存量行迁移保留）；
 * - legacy 存量行迁移：store 直种 legacy blocked 行 → NodeEdit 重激活仍可用
 *   （blockCount 保留、反馈留存、上游补投）、abandon 仍可用、D1 排除仍成立
 *   （反馈串永不可投）。
 */
class NodeBlockedRetiredSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 240.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-node-blocked-retired"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)

  for agent <- List("test-agent", "general") do
    os.makeDir.all(tempRoot / "agents" / agent)
    os.write.over(
      tempRoot / "agents" / agent / "agent.json",
      s"""{"name":"$agent","description":"blocked retirement regression agent","tools":[],"category":"standalone"}"""
    )
    os.write.over(tempRoot / "agents" / agent / "system.md", s"# $agent\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  /** 旧文法形态（退役前 BlockedReader 锚定 + JSON 体）——退役后必须不再产生 blocked。 */
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

  private def mkCtx(res: SharedResources, system: ActorSystem, ws: String): ToolContext =
    ToolContext(
      projectRoot = ws,
      sessionId = Some("retired-sid"),
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

  /** 引擎挂载（无 ProjectActor）+ WS 事件捕获；申报 hold 腿显式关（本面不测它）。 */
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

  /** The report hold leg is a dormant knob without a production read point; the global property switches it off for isolation. */
  private val ReportHoldProp = "nebflow.noderpt.completionHold"

  override def beforeEach(context: munit.BeforeEach): Unit =
    ProjectRuntimeRegistry.clear
    System.setProperty(ReportHoldProp, "false")

  override def afterEach(context: munit.AfterEach): Unit =
    ProjectRuntimeRegistry.clear
    System.clearProperty(ReportHoldProp)

  // ── 生命周期集合哨兵：blocked 不在任何活集合里（token 仅 legacy 迁移用）──

  test("lifecycle sentinels: blocked is not terminal, not a legal status, not in the chain-cancel scope") {
    assert(!NodeLifecycle.Terminal.contains(NodeLifecycle.Blocked), "blocked must NOT be a terminal state")
    assert(!NodeLifecycle.All.contains(NodeLifecycle.Blocked), "blocked must NOT be a legal lifecycle value")
    assert(
      !NodeLifecycle.ChainCancelScope.contains(NodeLifecycle.Blocked),
      "chain cancel must not sweep legacy blocked rows (manual migration only)"
    )
    // token 本身为 legacy 存量行保留（ProjectTypes 注释契约）
    assertEquals(NodeLifecycle.Blocked, "blocked")
  }

  // ── 文本锚定面退役：BLOCKED 锚定输出 → completed 原链（不再 blocked）──

  test(
    "text-anchor retired: BLOCKED-anchored final output no longer terminalizes as blocked — the node completes via the normal chain"
  ) {
    val ws = tempRoot / "ws-anchor"
    os.makeDir.all(ws)
    val system = ActorSystem(s"blk-anchor-${scala.util.Random.nextInt(100000)}")
    val llm = FuncLlm(_ => IO.pure(blockedText("upstream-incomplete", "上游 X 未完成", "需上游先完成")))
    for
      res <- SpecResources.mkResources(system, tempRoot, llm.handle)
      (rt, events) <- mountEngineOnly("blk-anchor", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- nodeEdit(
        nodeInput(
          "blk-anchor",
          "anchor-node",
          "description" -> Json.fromString("test node purpose"),
          "task" -> Json.fromString("will-emit-blocked-text"),
          "out" -> Json.fromString("Nebula")
        ),
        ctx
      )
      _ <- waitStatus(rt, "anchor-node", Set(NodeLifecycle.Completed, NodeLifecycle.Failed))
      aId <- idOf(rt, "anchor-node")
      a <- nodeById(rt, aId)
      evs <- events.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(
        a.status == NodeLifecycle.Completed,
        s"BLOCKED text must NOT produce a blocked terminal (got: ${a.status}, result=${a.result.take(80)})"
      )
      assertEquals(a.blockCount, 0, "no blocked round counting may happen")
      assertEquals(a.blockedFeedback, None, "no structured feedback may be stored")
      assert(
        !evs.exists((t, _, p) =>
          t == "nodeUpdated" && p.hcursor.get[String]("status").toOption.contains(NodeLifecycle.Blocked)
        ),
        "no nodeUpdated may ever carry status=blocked"
      )
    end for
  }

  // ── legacy 存量行迁移①：重激活仍可用（blockCount 保留 + 反馈留存 + 补投）──

  test(
    "legacy stock: editing a stored blocked row reactivates it (upstream re-delivered, blockCount kept, feedback kept), reruns to completion"
  ) {
    val ws = tempRoot / "ws-legacy-reactivate"
    os.makeDir.all(ws)
    val system = ActorSystem(s"blk-legacy-${scala.util.Random.nextInt(100000)}")
      // 桩以**重跑输入携带的新任务书**（migrated-task）为判别锚：重激活后的 rerun
      // prompt 带编辑后 task，首轮上游（late-result-C）不含它 ⇒ 应答可区分两腿。
      val llm = FuncLlm(text => if text.contains("migrated-task") then IO.pure("legacy-ok-final") else IO.pure("ok"))
    for
      res <- SpecResources.mkResources(system, tempRoot, llm.handle)
      (rt, events) <- mountEngineOnly("blk-legacy-react", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // 完成的上游 C（重激活时接为 in）
      _ <- nodeEdit(
        nodeInput(
          "blk-legacy-react",
          "late-up",
          "description" -> Json.fromString("test node purpose"),
          "task" -> Json.fromString("late-result-C"),
          "out" -> Json.fromString("Nebula")
        ),
        ctx
      )
      _ <- waitStatus(rt, "late-up", Set(NodeLifecycle.Completed))
      cId <- idOf(rt, "late-up")
      // store 直种 legacy blocked 行（旧引擎写点已删——存量形态只能这样造）
      _ <- rt.store.mutate { s =>
        s.copy(nodes =
          s.nodes ++ Map(
            "n-legacy-b" -> NodeDef(
              id = "n-legacy-b",
              name = "legacy-b",
              agent = "test-agent",
              task = Some("legacy-task"),
              in = List(cId),
              out = List(OutEdge.root),
              status = NodeLifecycle.Blocked,
              result = Some("[blocked:other] 反馈串"),
              blockedFeedback = Some(NodeFeedback("other", "反馈串", "")),
              blockCount = 2,
              createdAt = System.currentTimeMillis() - 3_600_000
            )
          )
        )
      }.void
      // 编辑 legacy blocked 行：task 实际变更 → 重激活（migration path）
      _ <- nodeEdit(nodeInput("blk-legacy-react", "legacy-b", "task" -> Json.fromString("migrated-task")), ctx)
      _ <- waitStatus(rt, "legacy-b", Set(NodeLifecycle.Completed))
      b <- nodeById(rt, "n-legacy-b")
      secondRunInput <- llm.inputs.get.map(
        _.find(t => t.contains("migrated-task") && t.contains("=== Node late-up ==="))
      )
      audit <- readAuditTypes(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(b.status, NodeLifecycle.Completed, "legacy blocked row must migrate via reactivation")
      assertEquals(b.blockCount, 2, "blockCount preserved on legacy reactivation (轮次历史保留)")
      assert(b.blockedFeedback.isDefined, "legacy feedback kept for history")
      assertEquals(b.result, Some("legacy-ok-final"), "result replaced by new run output")
      assert(b.deliveredTo.contains(cId), "completed upstream re-delivered after deliveredTo cleared")
      assert(
        secondRunInput.isDefined,
        "second run input must carry re-delivered upstream result"
      )
      assert(b.completedAt.isDefined && b.ttlExpireAt.isDefined, "completed rerun re-arms display TTL")
      assert(
        audit.exists((t, id) => t == "reactivated" && id == "n-legacy-b"),
        s"reactivated audit line, got: $audit"
      )
    end for
  }

  // ── legacy 存量行迁移②：abandon 仍可用 → cancelled ──

  test("legacy stock: abandon on a stored blocked row → cancelled + audit") {
    val ws = tempRoot / "ws-legacy-abandon"
    os.makeDir.all(ws)
    val system = ActorSystem(s"blk-labandon-${scala.util.Random.nextInt(100000)}")
    val llm = FuncLlm(_ => IO.pure("ok"))
    for
      res <- SpecResources.mkResources(system, tempRoot, llm.handle)
      (rt, events) <- mountEngineOnly("blk-legacy-abandon", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- rt.store.mutate { s =>
        s.copy(nodes =
          s.nodes ++ Map(
            "n-legacy-a" -> NodeDef(
              id = "n-legacy-a",
              name = "legacy-a",
              agent = "test-agent",
              task = Some("legacy task"),
              status = NodeLifecycle.Blocked,
              result = Some("[blocked:other] 无法提出调整"),
              blockCount = 1,
              createdAt = System.currentTimeMillis() - 3_600_000
            )
          )
        )
      }.void
      r <- nodeEdit(nodeInput("blk-legacy-abandon", "legacy-a", "abandon" -> Json.fromBoolean(true)), ctx)
      _ <- IO.sleep(200.millis)
      a <- nodeById(rt, "n-legacy-a")
      evs <- events.get
      audit <- readAuditTypes(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isRight, s"abandon on legacy blocked row must succeed, got: $r")
      assertEquals(a.status, NodeLifecycle.Cancelled, "abandoned legacy row becomes cancelled")
      assert(a.ttlExpireAt.isEmpty, "abandoned row retained on map — no display TTL")
      assert(
        evs.exists((t, id, p) =>
          t == "nodeUpdated" && id == "n-legacy-a" && p.hcursor.get[String]("status").toOption
            .contains(NodeLifecycle.Cancelled)
        ),
        "abandon must emit nodeUpdated (cancelled)"
      )
      assert(audit.exists((t, id) => t == "abandoned" && id == "n-legacy-a"), s"abandoned audit line, got: $audit")
    end for
  }

  // ── legacy 存量行迁移③：D1 排除仍成立（反馈串永不可投）──

  test(
    "legacy stock: D1 delivery guards exclude a stored blocked upstream — feedback string never delivered, downstream stays waiting"
  ) {
    val ws = tempRoot / "ws-legacy-d1"
    os.makeDir.all(ws)
    val system = ActorSystem(s"blk-ld1-${scala.util.Random.nextInt(100000)}")
    val llm = FuncLlm(_ => IO.pure("ok"))
    for
      res <- SpecResources.mkResources(system, tempRoot, llm.handle)
      (rt, events) <- mountEngineOnly("blk-legacy-d1", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- rt.store.mutate { s =>
        s.copy(nodes =
          s.nodes ++ Map(
            "n-legacy-up" -> NodeDef(
              id = "n-legacy-up",
              name = "legacy-up",
              agent = "test-agent",
              task = Some("legacy upstream"),
              out = List(OutEdge.root),
              status = NodeLifecycle.Blocked,
              result = Some("[blocked:other] 反馈串"),
              blockCount = 1,
              createdAt = System.currentTimeMillis() - 3_600_000
            )
          )
        )
      }.void
      // create 路径：B 接 in=[legacy blocked 上游] → 不得投递/启动
      _ <- nodeEdit(
        nodeInput(
          "blk-legacy-d1",
          "consumer-b",
          "description" -> Json.fromString("test node purpose"),
          "task" -> Json.fromString("consume-b"),
          "in" -> Json.fromString("n-legacy-up"),
          "out" -> Json.fromString("Nebula")
        ),
        ctx
      )
      _ <- IO.sleep(800.millis) // 给「假如误投递」留窗口
      inputs <- llm.inputs.get
      b <- rt.store.snapshot.map(_.nodes.values.find(_.name == "consumer-b")).map(_.getOrElse(fail("B must exist")))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(b.status, NodeLifecycle.Wiring, "create-path D1 must not deliver/start from legacy blocked upstream")
      assertEquals(b.deliveredTo, Nil, "no deliveredTo entry from legacy blocked upstream")
      assert(
        !inputs.exists(t => t.contains("=== Node legacy-up ===")),
        s"legacy feedback string must never be delivered, inputs=${inputs.map(_.take(100))}"
      )
    end for
  }

end NodeBlockedRetiredSpec
