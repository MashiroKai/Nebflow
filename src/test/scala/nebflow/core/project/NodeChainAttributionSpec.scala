package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentLibrary, SharedResources}
import nebflow.agent.flowChainId // AgentState extension accessor（§9.2 项 2 第三处）
import nebflow.core.PathUtil
import nebflow.core.node.NodeRunner
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, Tool, ToolContext, ToolError, ToolRegistry}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{ContentBlock, LlmHandle, LlmRequest, LlmResponse, StreamChunk, ToolCall}

import scala.concurrent.duration.*

/**
 * 引擎透传：`flowChainId` + 首条消息链头 + 元数据头模板注入
 * （20260910_process-doc-chain-attribution-spec §9.2 项 1-8）spec。
 *
 * 覆盖：
 *  - ①链快照单点：`NodeEngine.chainContextOf` = chainIdOf 判据同口径（合并集分量
 *    成员数 ≥2 才带值；孤立单节点分量 = 无链；title 走 chainTitle 三级推导单点）
 *  - ②首条消息链头（§9.2 项 7）：有链 → `[chain: <title> (<chainId>) · N 节点]`
 *    单行注入；无链 → 整块不注入（不注空行、零占位）
 *  - ③元数据头模板（§9.2 项 8/§3）：front matter 键数 ≤8（**键数口径**：行数 =
 *    键数 + 2 个 `---` 包围行 ⇒ ≤10 行，8 键单链 9 行 / 多链 10 行合法——2026-09-10
 *    作者裁定，`CONVENTIONS.md:7`/`:78` 权威；spec §3.1 的「≤8 行」已被取代）、
 *    键 ⊆ 八键白名单、零路径值；模板紧随链头
 *  - ④全链透传（§9.2 项 1-5，真实引擎路径）：节点 spawn → SessionContext →
 *    AgentCore → `ToolContext.flowChainId`——探针工具在真实节点会话内读到的链值
 *    与首条消息链头逐字同源（同一快照）；上游完成结算下游
 *  - ⑤分发器口径（§9.2 项 6）：`NodeRunner.SpawnParams`/`AgentState` 默认
 *    `flowChainId = None`（分发器不属任何链——ProjectActor spawn 点显式置 None，
 *    静默默认值的显式化）
 *
 * 变异验红（实施记录）：`buildInput` 链块摘除 → T1/T1b/T3 红；`AgentCore` 透传行
 * 摘除 → T3 红（探针读不到链值）；`chainContextOf` 的 `size >= 2` 过滤摘除 →
 * T1b 红（孤立单节点分量被当链注入）。
 */
class NodeChainAttributionSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 240.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-node-chain-attribution"
  private val originalRoot = PathUtil.dataRoot

  /** 探针节点的 agent 名（非 converged——`tools:["*"]` 才授能自定义探针工具）。 */
  private val ProbeAgent = "chainprobe"

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / ProbeAgent)
  os.write.over(
    tempRoot / "agents" / ProbeAgent / "agent.json",
    s"""{"name":"$ProbeAgent","description":"chain passthrough regression agent","tools":["*"],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / ProbeAgent / "system.md", s"# $ProbeAgent\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  private val t0 = 1750000000000L
  private val UpDone = "up-done"
  private val DownDone = "down-done"

  private val ExpectedChainId = "chain-n-ca"
  private val ExpectedHeader = s"[chain: 链标题A ($ExpectedChainId) · 2 节点]"

  /** 八键白名单（`CONVENTIONS.md:7` / spec §3.2）。 */
  private val KeyWhitelist =
    Set("chain", "chains", "chain-source", "chain-role", "produced-by", "produced-at", "doc-class", "root")

  /** 元数据头合规判据（**键数口径**，2026-09-10 作者裁定 / `CONVENTIONS.md:7` 与
    * `:78`）：取首个 `---` 行到配对 `---` 行的 front matter 段 → 键数 ≤8；行数为
    * 派生量 = 键数 + 2 ⇒ ≤10 行（8 键单链 9 行 / 多链 10 行合法）；键 ⊆ 白名单；
    * 零路径值。返回违规清单（Nil = 合规）。 */
  private def headViolations(text: String): List[String] =
    val lines = text.linesIterator.toList
    val start = lines.indexWhere(_.trim == "---")
    val end = if start >= 0 then lines.indexWhere(_.trim == "---", start + 1) else -1
    if start < 0 || end <= start then List("no front matter block found")
    else
      val fm = lines.slice(start, end + 1)
      val keys = fm.slice(1, fm.size - 1).map(_.takeWhile(ch => ch != ':' && ch != ' ').trim).filter(_.nonEmpty)
      val out = List.newBuilder[String]
      if keys.size > 8 then out += s"key count > 8: ${keys.size} ($keys)"
      if !keys.forall(KeyWhitelist.contains) then out += s"keys outside whitelist: ${keys.filterNot(KeyWhitelist.contains)}"
      if fm.size != keys.size + 2 then out += s"line count must equal keys + 2, got ${fm.size} lines / ${keys.size} keys"
      if fm.size > 10 then out += s"line count > 10: ${fm.size}"
      if fm.exists(_.contains("/")) then out += s"path value present: $fm"
      out.result()

  // ── 探针工具：把 ToolContext 的链身份落到 Ref（真实会话内断言面）──

  private object ChainProbe:
    val name = "chain_probe_p2"
    val ack = "[OK] chain-probe recorded"
    val captured: Ref[IO, List[Option[String]]] = Ref.unsafe[IO, List[Option[String]]](Nil)
    val probe: Tool = new Tool:
      def name: String = ChainProbe.name
      def description: String = "test-only probe: records ToolContext.flowChainId for chain passthrough assertions"
      def inputSchema: JsonObject = JsonObject.empty
      def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
        captured.update(_ :+ ctx.flowChainId).as(Right(ack))
      def summarize(input: JsonObject): String = s"$name()"
      def summarizeResult(input: JsonObject, result: String): String = s"$name → probe"

  /** 脚本 LLM：上游节点（输入含 up-task）直接收尾；下游节点（含 down-task）首 turn
    * 发探针工具调用，见到 ack 后收尾（两会话共用同一 handle，按输入文本分流）。 */
  private class ProbeLlm:
    val inputs: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)
    private def sawAck(req: LlmRequest): Boolean =
      req.messages.exists { m =>
        m.content match
          case Right(blocks) =>
            blocks.exists {
              case ContentBlock.ToolResult(_, content, _) => content.contains(ChainProbe.ack)
              case _                                      => false
            }
          case Left(t) => t.contains(ChainProbe.ack)
      }
    val handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(
          req: LlmRequest,
          onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
      ): Stream[IO, StreamChunk] =
        val text = req.messages.map(_.textContent).mkString("\n")
        Stream.eval(inputs.update(_ :+ text)).flatMap { _ =>
          if sawAck(req) then Stream(StreamChunk.TextDelta(DownDone), StreamChunk.Done(None, None))
          else if text.contains("down-task") then
            Stream(
              StreamChunk.ToolCallChunk(ToolCall("probe-1", ChainProbe.name, JsonObject.empty)),
              StreamChunk.Done(Some("tool_use"), None)
            )
          else Stream(StreamChunk.TextDelta(UpDone), StreamChunk.Done(None, None))
        }

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

  private def mountEngine(name: String, ws: os.Path, system: ActorSystem, res: SharedResources): IO[ProjectRuntime] =
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
        emitEvent = (_, _, _) => IO.unit
      )
      pd = ProjectDef(name = name, workspace = ws.toString, agentFile = (ws / "AGENTS.md").toString,
        createdAt = System.currentTimeMillis())
      rt = ProjectRuntime(pd, store, engine, system, res, None)
      _ <- ProjectRuntimeRegistry.register(rt)
    yield rt

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 50.millis)(cond: IO[Boolean]): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError("waitUntil: condition not met in time"))
          else IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  private def waitNodeStatus(rt: ProjectRuntime, id: String, statuses: Set[String]): IO[Unit] =
    waitUntil(60.seconds)(rt.store.getNode(id).map(_.exists(n => statuses.contains(n.status))))

  /** 两节点链夹具（直种 store：确定性 createdAt → 链 id/title 可逐字断言）：
    * n-ca（早，description=链标题A，out→n-cb）— n-cb（in=[n-ca]）。 */
  private def seedChain(rt: ProjectRuntime): IO[Unit] =
    rt.store
      .mutate(s =>
        s.copy(nodes = s.nodes ++ Map(
          "n-ca" -> NodeDef(id = "n-ca", name = "chain-a", agent = ProbeAgent, description = Some("链标题A"),
            task = Some("up-task"), status = NodeLifecycle.Pending, out = List(OutEdge("n-cb")), createdAt = t0),
          "n-cb" -> NodeDef(id = "n-cb", name = "chain-b", agent = ProbeAgent, description = Some("链标题B"),
            task = Some("down-task"), status = NodeLifecycle.Pending, in = List("n-ca"), createdAt = t0 + 1000)
        ))
      )
      .void

  private def seedIsolated(rt: ProjectRuntime, id: String): IO[Unit] =
    rt.store
      .mutate(s =>
        s.copy(nodes = s.nodes.updated(
          id,
          NodeDef(id = id, name = s"iso-$id", agent = ProbeAgent, description = Some("孤立节点"),
            task = Some("solo-task"), status = NodeLifecycle.Pending, createdAt = t0)
        ))
      )
      .void

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit =
    ProjectRuntimeRegistry.clear
    ChainProbe.captured.set(Nil).unsafeRunSync()

  // ── ① 链快照单点 + ② 有链链头 + ③ 元数据头模板 ──────────────────────

  test("T1 有链：chainContextOf 快照（chainId/title/成员数）+ buildInput 链头 + 元数据头模板（键数口径/白名单键/零路径）") {
    val ws = tempRoot / "ws-chain-header"
    os.makeDir.all(ws)
    val system = ActorSystem(s"chain-hdr-${scala.util.Random.nextInt(100000)}")
    val llm = new ProbeLlm
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountEngine("chain-header", ws, system, res)
      _ <- seedChain(rt)
      a <- rt.store.getNode("n-ca").map(_.getOrElse(fail("n-ca must exist")))
      chain <- rt.engine.chainContextOf("n-ca")
      input <- rt.engine.buildInput(a, chain)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // ① 快照：chainId = chain-<分量最早 createdAt 成员>，title = chainTitle 单点，成员数 2
      val c = chain.getOrElse(fail("n-ca belongs to a 2-member chain — snapshot must be defined"))
      assertEquals(c.chainId, ExpectedChainId, "chain id = chain-<分量最早 createdAt 节点 id>")
      assertEquals(c.title, "链标题A", "title = chainTitle 三级推导单点（首个非空 description）")
      assertEquals(c.memberCount, 2, "memberCount = 分量成员数")
      // ② 链头：单行、逐字形态（§9.2 项 7）
      assert(input.contains(ExpectedHeader), s"chain header line injected verbatim, got:\n${input.take(700)}")
      assertEquals(input.linesIterator.filter(_.startsWith("[chain:")).toList, List(ExpectedHeader),
        "exactly one chain header line")
      // ③ 元数据头模板：紧随链头 + 键数 ≤8（行数 = 键数 + 2 ≤ 10）+ 键 ⊆ 白名单 + 零路径
      val lines = input.linesIterator.toList
      val hIdx = lines.indexOf(ExpectedHeader)
      assert(hIdx >= 0 && lines.lift(hIdx + 1).exists(_.startsWith("[过程文档元数据头")),
        s"metadata template must follow the chain header, got: ${lines.slice(math.max(hIdx, 0), hIdx + 3)}")
      assertEquals(headViolations(input), Nil, "injected metadata head template must be compliant")
      val fm = lines.slice(lines.indexWhere(_.trim == "---"),
        lines.indexWhere(_.trim == "---", lines.indexWhere(_.trim == "---") + 1) + 1)
      val keys = fm.slice(1, fm.size - 1).map(_.takeWhile(ch => ch != ':' && ch != ' ').trim).filter(_.nonEmpty)
      assertEquals(keys, List("chain", "chain-source", "chain-role", "produced-by", "produced-at", "doc-class", "root"),
        "template carries the house single-chain form (7 keys, 9 lines — legal under the key-count cap)")
      // 结构不变式：链块在自身 task 之前（首屏可见），协议脚注仍在末尾
      assert(input.indexOf(ExpectedHeader) < input.indexOf("up-task"), "chain block precedes the task text")
      assert(input.endsWith(NodeEngine.ProtocolFootnote), "protocol footnote stays last (unchanged)")
  }

  // ── ③bis 上限口径：键数而非行数（2026-09-10 作者裁定）──────────────

  test("T2 元数据头上限 = 键数口径：8 键单链 9 行 / 多链 10 行合法，超键/越白名单/带路径为违规") {
    val single =
      """---
        |chain: chain-n-01fcb885
        |chain-source: engine
        |chain-role: head
        |produced-by: node:n-3f2a9b1c
        |produced-at: 2026-09-10T11:28:14+08:00
        |doc-class: stage
        |root: home
        |---""".stripMargin
    val multi = single.replace("root: home", "chains: [chain-n-01fcb885, chain-n-3f2a9b1c]\nroot: ws")
    assertEquals(headViolations(single), Nil, "8 键单链 9 行 = 合法（键数口径，非「≤8 行」）")
    assertEquals(headViolations(multi), Nil, "8 键多链 10 行 = 合法（键数口径上限）")
    assertEquals(single.linesIterator.size - 1, 8, "single-chain sample: 7 keys + 2 delimiters")
    assertEquals(multi.linesIterator.size - 1, 9, "multi-chain sample: 8 keys + 2 delimiters")
    // 反例闸：越白名单键 / 带路径值 / 行数 ≠ 键数 + 2
    assert(headViolations(single.replace("chain-role: head", "chain-role: head\nfoo: 1")).nonEmpty,
      "非白名单键（同时越 8 键）必须违规")
    assert(headViolations(single.replace("root: home", "root: ~/.nebflow/docs")).nonEmpty, "路径值必须违规")
    assert(headViolations(single.replace("---\n", "")).nonEmpty, "无 front matter 段 → 违规（解析器定位失败）")
  }

  // ── ② 无链：整块不注入 + 孤立单节点分量不算链 ──────────────────────

  test("T1b 无链：孤立单节点分量 → 快照 None；buildInput 无链头/无模板/无空行残留") {
    val ws = tempRoot / "ws-chain-absent"
    os.makeDir.all(ws)
    val system = ActorSystem(s"chain-absent-${scala.util.Random.nextInt(100000)}")
    val llm = new ProbeLlm
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountEngine("chain-absent", ws, system, res)
      _ <- seedIsolated(rt, "n-iso")
      iso <- rt.store.getNode("n-iso").map(_.getOrElse(fail("n-iso must exist")))
      chain <- rt.engine.chainContextOf("n-iso")
      input <- rt.engine.buildInput(iso, chain)
      missing <- rt.engine.chainContextOf("n-nope")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(chain, None, "孤立单节点分量（成员数 1）不算链——与 payload chainId 条件键同口径")
      assertEquals(missing, None, "不在双区的节点 → None（不出 panic）")
      assert(!input.contains("[chain:"), s"no chain → no chain header line, got:\n${input.take(400)}")
      assert(!input.contains("[过程文档元数据头"), "no chain → no metadata template (no attribution subject)")
      assert(!input.contains("\n\n\n"), "no chain → zero blank-line artifacts (整行不注入)")
      assert(input.contains("solo-task") && input.endsWith(NodeEngine.ProtocolFootnote),
        "task + protocol footnote unchanged")
  }

  // ── ④ 全链透传：节点 spawn → ToolContext.flowChainId（真实引擎路径）────

  test("T3 透传链：下游会话内 ToolContext.flowChainId = 链头同一快照（探针工具实证），上游完成结算下游") {
    val ws = tempRoot / "ws-chain-passthrough"
    os.makeDir.all(ws)
    val system = ActorSystem(s"chain-pass-${scala.util.Random.nextInt(100000)}")
    val llm = new ProbeLlm
    val body = for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountEngine("chain-pass", ws, system, res)
      _ <- seedChain(rt)
      _ <- rt.engine.startNode("n-ca")
      _ <- waitNodeStatus(rt, "n-ca", NodeLifecycle.Terminal)
      _ <- waitNodeStatus(rt, "n-cb", NodeLifecycle.Terminal)
      cap <- ChainProbe.captured.get
      ins <- llm.inputs.get
      ca <- rt.store.getNode("n-ca").map(_.getOrElse(fail("n-ca must exist")))
      cb <- rt.store.getNode("n-cb").map(_.getOrElse(fail("n-cb must exist")))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // 透传链实证：探针在真实节点会话（NodeEngine spawn → NodeRunner.SpawnParams →
      // AgentActor → AgentState → AgentCore → ToolContext）内读到的链 id
      assertEquals(cap, List(Some(ExpectedChainId)),
        s"probe must see the spawn-time chain id via ToolContext.flowChainId, got $cap")
      // 链头与 ToolContext 值同源（同一快照）：首条消息链头含同一 chainId
      val downInput = ins.find(_.contains("down-task")).getOrElse(fail(s"downstream input missing, got ${ins.map(_.take(80))}"))
      assert(downInput.contains(ExpectedHeader), s"downstream first message must carry the chain header, got:\n${downInput.take(700)}")
      val upInput = ins.find(_.contains("up-task")).getOrElse(fail("upstream input missing"))
      assert(upInput.contains(ExpectedHeader), "upstream node sees the same chain header (both members of one chain)")
      // 编排未变：上游完成 → 下游结算启动 → 终态
      assertEquals(ca.status, NodeLifecycle.Completed, "upstream completed")
      assertEquals(cb.status, NodeLifecycle.Completed, "downstream settled by upstream completion")
      assertEquals(cb.result, Some(DownDone), "downstream result = its closing text (probe round consumed)")
    IO(ToolRegistry.registerTool(ChainProbe.probe)) *>
      body.guarantee(IO(ToolRegistry.unregisterTool(ChainProbe.name)))
  }

  // ── ⑤ 分发器口径 + 默认值（§9.2 项 6）────────────────────────────────

  test("T4 分发器口径：SpawnParams/AgentState 默认 flowChainId = None（ProjectActor 显式置 None 的静默面）") {
    val system = ActorSystem(s"disp-gate-${scala.util.Random.nextInt(100000)}")
    val llm = new ProbeLlm
    for
      res <- mkResources(system, tempRoot, llm.handle)
      // 分发器 spawn 形态（ProjectActor :600-628 同参：isDispatcher=true + projectName + 显式 None）
      params = NodeRunner.SpawnParams(
        agentDef = nebflow.agent.AgentDef(name = ProbeAgent, description = "dispatcher-gate", tools = Nil, category = "standalone"),
        resources = res,
        sessionId = "dispatcher-gate-sid",
        sessionName = "dispatcher/gate",
        depth = 1,
        parentRef = None,
        wsSend = (_: Json) => IO.unit,
        isDispatcher = true,
        projectName = Some("gate"),
        flowChainId = None
      )
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(params.flowChainId, None, "dispatcher belongs to no chain（口径显式 None，不依赖调用方记忆）")
      assertEquals(params.flowNodeId, None, "分发器无 NodeDef.id（对照：节点 spawn 置 Some）")
      assertEquals(nebflow.agent.AgentState(flowChainId = Some("chain-x")).flowChainId, Some("chain-x"),
        "SessionContext 字段/参数/accessor 三处接通（AgentState 透传面 + extension accessor）")
      assertEquals(nebflow.agent.AgentState().flowChainId, None, "非项目会话默认 None")
      assertEquals(nebflow.agent.AgentState(flowChainId = Some("chain-x")).session.flowChainId, Some("chain-x"),
        "SessionContext 字段本体承载（扩展 accessor 与字段同源）")
  }

end NodeChainAttributionSpec
