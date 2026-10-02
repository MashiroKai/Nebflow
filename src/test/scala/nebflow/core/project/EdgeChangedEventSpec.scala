package nebflow.core.project

import cats.effect.{IO, Ref}
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{SharedResources, SpecResources}
import nebflow.core.tools.{NodeEditTool, ToolContext}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk}

import scala.concurrent.duration.*
import scala.util.Random

/**
 * **缺陷③「边变更零审计事件」实施位验收 spec**（三件一体 · 案 A，2026-10-01）。
 *
 * 逐条对齐任务书 §2.4 判据（R1–R7 + 变异臂 + 幂等负控）：
 *  - **R1**（`out` 重写 · 改前必红 → 改后必绿）：一次 `NodeEdit(out="B")` ⇒ **恰一行**
 *    `edge-changed`：`owner=<A> kind=out from=- to=B mode=result gate=pass`。
 *  - **R2**（`in` 镜像 · 独立第二行）：同夹具下另存在 `kind=in ∧ owner=<B> ∧ from=- ∧ to=<A>`；
 *    同锚幂等：重复提交同一 `out` ⇒ 零新增行。
 *  - **R3**（`deps` 替换）：`NodeEdit(deps="X")` → `NodeEdit(deps="Y")` ⇒ 一行 `kind=deps from=X to=Y`。
 *  - **R4**（摘边边身份 · 改前必红）：`abandon=true` ⇒ `kind=out ∧ owner=<B> ∧ from=<C>`。
 *  - **R5**（零回归负控）：①`description` 重编辑 ⇒ 计数恒 0；②孤立节点 `NodeCancel` ⇒ 零行。
 *  - **R6**（actor 面 · 本批新增）：经**节点会话**触发重激活 ⇒ summary 不含 `source=human`
 *    且含 `actor=<会话 id>`。
 *  - **R7**（拒绝面 · 本批新增）：`ensureMergePassOnly` 拒 ⇒ 一行含 `reason=refused` ∧
 *    `detail=NODE_MERGE_PASS_ONLY`。
 *  - **变异臂**：把 `edgeChangedSummary` 的 diff 计算改为恒返回 Nil ⇒ R1 / R4 同时转红。
 *
 * 夹具 = 进程内 spec（`ProjectRuntime` 夹具 + 静默 LLM 桩，**完全复用
 * `DeferredWiringSpec` 的 harness 形态**）；零 spawn、零实例、零端口、零 live `:8080`；
 * 读写只在 `target/test-edge-changed/` 下的临时工作区（禁触 `.nebflow/` 生产态）。
 */
class EdgeChangedEventSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-edge-changed"
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
      sessionId = Some("edge-changed-sid"),
      rootSessionId = Some("nebula-root"),
      sharedResources = Some(res),
      actorSystem = Some(system)
    )

  /** project 节点会话上下文（R6：`flowNodeId` 非空 ⇒ `source=node`）。 */
  private def nodeCtx(base: ToolContext): ToolContext =
    base.copy(sessionId = Some("session-node-abc123"), flowNodeId = Some("n-owner"))

  /** 分发器会话上下文（`isDispatcher=true` ⇒ `source=dispatcher`）。 */
  private def dispatcherCtx(base: ToolContext): ToolContext =
    base.copy(sessionId = Some("session-dispatcher-1"), isDispatcher = true)

  private def nodeEdit(input: Json, ctx: ToolContext): IO[Either[String, String]] =
    NodeEditTool.call(input.asObject.get, ctx).map(_.left.map(_.message))

  private def nodeInput(project: String, nodename: String, extra: (String, Json)*): Json =
    Json.obj(
      ("project" -> Json
        .fromString(project)) :: ("nodename" -> Json.fromString(nodename)) :: ("plugins" -> Json.arr()) :: extra.toList*
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
    out: List[OutEdge] = Nil,
    merge: Boolean = false
  ): NodeDef =
    NodeDef(
      id = id,
      name = name,
      agent = "general",
      task = Some(s"$name task"),
      status = status,
      out = out,
      merge = merge,
      createdAt = System.currentTimeMillis()
    )

  private def nodeByName(rt: ProjectRuntime, name: String): IO[NodeDef] =
    rt.store.snapshot.map(_.nodes.values.find(_.name == name).getOrElse(fail(s"node '$name' must exist")))

  /** `type` / `nodeId` / `summary` 三元组（沿 `DeferredWiringSpec.readAudit` 同款读法）。 */
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

  /** 原始行（R1 断言 `"ts":` 存在需要整行文本）。 */
  private def readAuditRaw(ws: os.Path): IO[List[String]] =
    IO.blocking(os.read(ws / ".nebflow" / FlowMapEventLog.FileName))
      .map(_.linesIterator.toList.filter(_.trim.nonEmpty))
      .handleError(_ => Nil)

  private def edgeLines(ws: os.Path): IO[List[String]] =
    readAuditRaw(ws).map(_.filter(_.contains(FlowMapEventLog.EdgeChangedType)))

  private def stop(system: ActorSystem): IO[Unit] = system.stopAll.handleErrorWith(_ => IO.unit)

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  // ── R1 / R2：out 重写 + in 镜像独立第二行 + 幂等 ──────────────────────────

  test("R1/R2: one NodeEdit(out=B) emits exactly one out line and an independent in mirror line") {
    val ws = tempRoot / "ws-r1"
    os.makeDir.all(ws)
    val system = ActorSystem(s"edge-r1-${Random.nextInt(100000)}")
    val llm = new QuietLlm
    for
      res <- SpecResources.mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("edge-r1", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- seed(
        rt,
        mkNode("n-a", "A", NodeLifecycle.Pending),
        mkNode("n-b", "B", NodeLifecycle.Pending)
      )
      r1 <- nodeEdit(
        nodeInput("edge-r1", "A", "description" -> Json.fromString("A node"), "out" -> Json.fromString("B")),
        ctx
      )
      lines1 <- edgeLines(ws)
      // R2 幂等臂：同一 out 再提交一次 ⇒ 零新增行
      r2 <- nodeEdit(
        nodeInput("edge-r1", "A", "description" -> Json.fromString("A node"), "out" -> Json.fromString("B")),
        ctx
      )
      lines2 <- edgeLines(ws)
      raw <- readAuditRaw(ws)
      _ <- stop(system)
    yield
      assert(r1.isRight, s"the out rewrite must be accepted, got: $r1")
      assert(r2.isRight, s"the idempotent re-submit must be accepted, got: $r2")
      val summary1 = lines1.map(l => io.circe.parser.parse(l).toOption.flatMap(_.hcursor.get[String]("summary").toOption).getOrElse(""))
      val outRows = summary1.filter(s => s.contains("owner=n-a") && s.contains("kind=out"))
      assertEquals(outRows.size, 1, s"R1: exactly one out row expected, got: $summary1")
      assert(
        outRows.head.contains("from=-") && outRows.head.contains("to=n-b") && outRows.head.contains("mode=result") &&
          outRows.head.contains("gate=pass"),
        s"R1: out row payload must be from=- to=<B> mode=result gate=pass, got: ${outRows.head}"
      )
      assert(lines1.exists(_.contains("\"ts\":")), s"R1: the new row must carry ts, got: $lines1")
      val inRows = summary1.filter(s => s.contains("owner=n-b") && s.contains("kind=in"))
      assertEquals(inRows.size, 1, s"R2: exactly one independent in mirror row expected, got: $summary1")
      assert(
        inRows.head.contains("from=-") && inRows.head.contains("to=n-a"),
        s"R2: in mirror row must be owner=<B> from=- to=<A>, got: ${inRows.head}"
      )
      assertEquals(lines2.size, lines1.size, "R2 idempotence: re-submitting the same out must add ZERO rows")
      assert(raw.nonEmpty, "the audit file must exist")
  }

  // ── R3：deps 替换 ─────────────────────────────────────────────────────

  test("R3: deps replacement emits kind=deps from=<old> to=<new>") {
    val ws = tempRoot / "ws-r3"
    os.makeDir.all(ws)
    val system = ActorSystem(s"edge-r3-${Random.nextInt(100000)}")
    val llm = new QuietLlm
    for
      res <- SpecResources.mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("edge-r3", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- seed(
        rt,
        mkNode("n-x", "X", NodeLifecycle.Pending),
        mkNode("n-y", "Y", NodeLifecycle.Pending),
        mkNode("n-t", "T", NodeLifecycle.Pending)
      )
      r1 <- nodeEdit(
        nodeInput("edge-r3", "T", "description" -> Json.fromString("T node"), "deps" -> Json.fromString("n-x")),
        ctx
      )
      r2 <- nodeEdit(
        nodeInput("edge-r3", "T", "description" -> Json.fromString("T node"), "deps" -> Json.fromString("n-y")),
        ctx
      )
      summaries <- edgeLines(ws).map(
        _.map(l => io.circe.parser.parse(l).toOption.flatMap(_.hcursor.get[String]("summary").toOption).getOrElse(""))
      )
      _ <- stop(system)
    yield
      assert(r1.isRight && r2.isRight, s"both deps edits must be accepted, got: $r1 / $r2")
      val depsRow = summaries.filter(s => s.contains("owner=n-t") && s.contains("kind=deps"))
      assert(depsRow.nonEmpty, s"R3: a kind=deps row must exist, got: $summaries")
      assert(
        depsRow.exists(s => s.contains("from=n-x") && s.contains("to=n-y")),
        s"R3: the replacement must render from=X to=Y, got: $depsRow"
      )
  }

  // ── R4：摘边边身份（abandon）──────────────────────────────────────────

  test("R4: abandoning B emits kind=out owner=B from=C (edge identity, not a bare count)") {
    val ws = tempRoot / "ws-r4"
    os.makeDir.all(ws)
    val system = ActorSystem(s"edge-r4-${Random.nextInt(100000)}")
    val llm = new QuietLlm
    for
      res <- SpecResources.mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("edge-r4", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- seed(
        rt,
        mkNode("n-a", "A", NodeLifecycle.Pending, out = List(OutEdge("n-b"))),
        mkNode("n-b", "B", NodeLifecycle.Pending, out = List(OutEdge("n-c"))),
        mkNode("n-c", "C", NodeLifecycle.Pending)
      )
      _ <- rt.store.mutate(s =>
        s.copy(nodes =
          s.nodes
            .updated("n-b", s.nodes("n-b").copy(in = List("n-a")))
            .updated("n-c", s.nodes("n-c").copy(in = List("n-b")))
        )
      )
      r <- nodeEdit(
        nodeInput("edge-r4", "B", "description" -> Json.fromString("B node"), "abandon" -> Json.True),
        ctx
      )
      summaries <- edgeLines(ws).map(
        _.map(l => io.circe.parser.parse(l).toOption.flatMap(_.hcursor.get[String]("summary").toOption).getOrElse(""))
      )
      audit <- readAudit(ws)
      _ <- stop(system)
    yield
      assert(r.isRight, s"abandon must be accepted, got: $r")
      val outRow = summaries.filter(s => s.contains("owner=n-b") && s.contains("kind=out"))
      assert(outRow.nonEmpty, s"R4: an out row for B must exist, got: $summaries")
      assert(
        outRow.exists(s => s.contains("from=n-c") && s.contains("reason=abandon")),
        s"R4: the detached edge identity (from=C) must be present, got: $outRow"
      )
      assert(
        audit.exists((t, id, _) => t == "abandoned" && id == "n-b"),
        s"the existing abandoned line must stay verbatim (same type + nodeId), got: $audit"
      )
  }

  // ── R5：零回归负控 ────────────────────────────────────────────────────

  test("R5: description-only edit and an isolated NodeCancel emit zero edge-changed rows") {
    val ws = tempRoot / "ws-r5"
    os.makeDir.all(ws)
    val system = ActorSystem(s"edge-r5-${Random.nextInt(100000)}")
    val llm = new QuietLlm
    for
      res <- SpecResources.mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("edge-r5", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- seed(
        rt,
        mkNode("n-iso", "ISO", NodeLifecycle.Pending),
        mkNode("n-fuel", "FUEL", NodeLifecycle.Pending, out = List(OutEdge.root))
      )
      r1 <- nodeEdit(
        nodeInput("edge-r5", "ISO", "description" -> Json.fromString("same")),
        ctx
      )
      r2 <- nodeEdit(
        nodeInput("edge-r5", "ISO", "description" -> Json.fromString("same")),
        ctx
      )
      lines1 <- edgeLines(ws)
      _ <- rt.engine.cancelNode("n-iso", "R5 negative control", CancelSource.User).handleErrorWith(_ => IO.unit)
      lines2 <- edgeLines(ws)
      _ <- stop(system)
    yield
      assert(r1.isRight && r2.isRight, s"description edits must be accepted, got: $r1 / $r2")
      assertEquals(lines1, Nil, s"R5①: a description-only edit must emit ZERO edge-changed rows, got: $lines1")
      assertEquals(
        lines2,
        Nil,
        s"R5②: cancelling an isolated node (no incident edges) must emit ZERO edge-changed rows, got: $lines2"
      )
  }

  // ── R6：actor 面（节点会话重激活）─────────────────────────────────────

  test("R6: a node-session reactivation records source=node and actor=<sessionId>") {
    val ws = tempRoot / "ws-r6"
    os.makeDir.all(ws)
    val system = ActorSystem(s"edge-r6-${Random.nextInt(100000)}")
    val llm = new QuietLlm
    for
      res <- SpecResources.mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("edge-r6", ws, system, res)
      base = mkCtx(res, system, ws.toString)
      ctx = nodeCtx(base)
      _ <- seed(rt, mkNode("n-rv", "RV", NodeLifecycle.Blocked, out = List(OutEdge.root)))
      r <- nodeEdit(
        nodeInput("edge-r6", "RV", "description" -> Json.fromString("revived"), "task" -> Json.fromString("revived task")),
        ctx
      )
      audit <- readAudit(ws)
      _ <- stop(system)
    yield
      assert(r.isRight, s"the blocked-node edit must be accepted, got: $r")
      val row = audit.find((t, id, _) => t == "reactivated" && id == "n-rv")
      assert(row.isDefined, s"R6: a reactivated row must exist, got: $audit")
      val summary = row.get._3
      assert(!summary.contains("source=human"), s"R6: a node session must NOT fall back to source=human, got: $summary")
      assert(summary.contains("source=node"), s"R6: source=node expected, got: $summary")
      assert(summary.contains("actor=session-node-abc123"), s"R6: actor=<sessionId> expected, got: $summary")
  }

  test("R6b: a dispatcher session records source=dispatcher; a zero-identity context falls back to human") {
    val ws = tempRoot / "ws-r6b"
    os.makeDir.all(ws)
    val system = ActorSystem(s"edge-r6b-${Random.nextInt(100000)}")
    val llm = new QuietLlm
    for
      res <- SpecResources.mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("edge-r6b", ws, system, res)
      base = mkCtx(res, system, ws.toString)
      _ <- seed(
        rt,
        mkNode("n-d1", "D1", NodeLifecycle.Blocked, out = List(OutEdge.root)),
        mkNode("n-d2", "D2", NodeLifecycle.Blocked, out = List(OutEdge.root))
      )
      r1 <- nodeEdit(
        nodeInput("edge-r6b", "D1", "description" -> Json.fromString("d1"), "task" -> Json.fromString("d1 task")),
        dispatcherCtx(base)
      )
      r2 <- nodeEdit(
        nodeInput("edge-r6b", "D2", "description" -> Json.fromString("d2"), "task" -> Json.fromString("d2 task")),
        base.copy(sessionId = None, rootSessionId = None)
      )
      audit <- readAudit(ws)
      _ <- stop(system)
    yield
      assert(r1.isRight && r2.isRight, s"both edits must be accepted, got: $r1 / $r2")
      val d1 = audit.find((t, id, _) => t == "reactivated" && id == "n-d1").map(_._3).getOrElse("")
      val d2 = audit.find((t, id, _) => t == "reactivated" && id == "n-d2").map(_._3).getOrElse("")
      assert(d1.contains("source=dispatcher"), s"R6b: dispatcher context expected, got: $d1")
      assert(d1.contains("actor=session-dispatcher-1"), s"R6b: actor key expected, got: $d1")
      assert(d2.contains("source=human"), s"R6b: zero-identity must fall back to human, got: $d2")
      assert(
        !d2.contains("actor="),
        s"R6b: a missing identity must NOT emit an empty-valued actor key, got: $d2"
      )
  }

  // ── R7：拒绝面 ────────────────────────────────────────────────────────

  test("R7: ensureMergePassOnly refusal emits edge-changed with reason=refused and detail=NODE_MERGE_PASS_ONLY") {
    val ws = tempRoot / "ws-r7"
    os.makeDir.all(ws)
    val system = ActorSystem(s"edge-r7-${Random.nextInt(100000)}")
    val llm = new QuietLlm
    for
      res <- SpecResources.mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("edge-r7", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- seed(
        rt,
        mkNode("n-m", "M", NodeLifecycle.Wiring, merge = true),
        mkNode("n-src", "SRC", NodeLifecycle.Running)
      )
      r0 <- nodeEdit(
        nodeInput("edge-r7", "SRC", "description" -> Json.fromString("src")),
        ctx
      )
      r <- nodeEdit(
        nodeInput("edge-r7", "SRC", "description" -> Json.fromString("src"), "out" -> Json.fromString("(failed)M:signal")),
        ctx
      )
      summaries <- edgeLines(ws).map(
        _.map(l => io.circe.parser.parse(l).toOption.flatMap(_.hcursor.get[String]("summary").toOption).getOrElse(""))
      )
      _ <- stop(system)
    yield
      assert(r0.isRight, s"the source node edit must be accepted, got: $r0")
      assert(r.isLeft, s"R7: the on-failed edge into a merge node must be REJECTED, got: $r")
      val refused = summaries.filter(s => s.contains("reason=refused"))
      assert(refused.nonEmpty, s"R7: a reason=refused row must exist, got: $summaries")
      assert(
        refused.exists(s => s.contains("detail=NODE_MERGE_PASS_ONLY")),
        s"R7: detail must carry the existing error code, got: $refused"
      )
      assert(
        refused.exists(s => s.contains("owner=n-src") && s.contains("from=-") && s.contains("to=-")),
        s"R7: the refusal row must name the owner with from=- to=-, got: $refused"
      )
  }

  // ── 幂等负控：零变更 ⇒ 零行（R5 的纯函数面镜像）──────────────────────

  test("negative control: the pure summary builder stays silent when nothing changed") {
    val v = FlowMapEventLog.outEdgeViews(
      "n-owner",
      List(OutEdge("n-x")),
      List(OutEdge("n-x")),
      FlowMapEventLog.EdgeChangeReason.Machine,
      identity
    )
    assertEquals(v, Nil, "unchanged before/after must yield zero views")
    val m = FlowMapEventLog.inMirrorViewsBatch(
      Map("n-a" -> List("n-u")),
      Map("n-a" -> List("n-u")),
      FlowMapEventLog.EdgeChangeReason.Machine
    )
    assertEquals(m, Nil, "unchanged in mirrors must yield zero views")
  }
