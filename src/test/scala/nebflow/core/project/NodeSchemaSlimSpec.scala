package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentLibrary, SharedResources}
import nebflow.core.PathUtil
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, NodeEditTool, NodeListTool, NodeTools, ToolContext}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*
import scala.util.Random

/**
 * 节点模型字段改造 spec（2026-09-05 插件架构对齐批）——NodeEdit 新 schema 契约 +
 * Flow Map 载荷收敛 E2E（进程内 wire 级，NodeAcceptanceSpec/NodePluginChainSpec
 * stub-LLM harness 先例）。
 *
 * Schema 层：
 * - description 创建必写（非空 trim、≤200），编辑可 update
 * - agent/skill/mcp 退役 → 拒绝（NODE_AGENT_RETIRED，指向 plugins）
 * - worktree String→Boolean（WORKTREE_NOT_BOOLEAN）；true = 即时派生创建
 *   （fail-fast，失败拒绝建节点）；编辑路径拒绝（WORKTREE_CREATE_ONLY）
 * - plugins 信任门既有逻辑保持（approved 才可分配——2b 断言零弱化由 NodePluginChainSpec 承载）
 * E2E：
 * - 新 schema 建节点（description+task+out=Nebula+plugins[approved]+preset+worktree=true）
 *   → 执行至 completed → 默认载荷只见元数据（无 result）→ NodeList(detail) 取回全文一致
 *   → 落盘拆分（JSON 摘要 + results/<id>.md 全文）→ worktree 派生创建成功
 */
class NodeSchemaSlimSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-schema-slim"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "general")
  os.write.over(tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"general executor","tools":[],"category":"standalone"}""")
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")
  os.write.over(tempRoot / "nebflow.json", "{}")

  // skills-only plugin fixture（真实目录 + 审批走 PluginRegistry 单点，NodePluginChainSpec 同款）
  private val slimPluginDir = tempRoot / "plugins" / "slim-e2e"
  os.makeDir.all(slimPluginDir / "skills" / "howto")
  os.write.over(slimPluginDir / "plugin.json",
    """{"$schema":"https://agent-plugins.org/schema/1.0.0","name":"slim-e2e","version":"1.0.0","description":"slim payload e2e fixture"}""")
  os.write.over(slimPluginDir / "skills" / "howto" / "SKILL.md",
    """---
      |name: howto
      |description: slim e2e skill
      |---
      |## SlimE2E Marker
      |Body.""".stripMargin)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  // >500 chars —— E2E 断言「JSON 只带摘要（500+…）不带全文尾部标记」需要全文长于摘要上限
  private val ResultText = ("E2E FULL RESULT BODY。result-line-eight。" * 25) + "marker-slim-e2e-end"

  private class RecordingLlm extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
    def sendStream(
        req: LlmRequest,
        onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream(StreamChunk.TextDelta(ResultText), StreamChunk.Done(None, None))

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
      sessionId = Some("slim-sid"),
      rootSessionId = Some("nebula-root"),
      sharedResources = Some(res),
      actorSystem = Some(system)
    )

  private def mountProject(name: String, ws: os.Path, system: ActorSystem, res: SharedResources): IO[ProjectRuntime] =
    for
      store <- FlowMapStore.open(name, ws.toString)
      engine = new NodeEngine(
        store, system, res,
        wsSendFn = (_: Json) => IO.unit,
        workspace = ws.toString,
        rootSessionId = "nebula-root",
        projectName = name,
        emitEvent = (_, _, _) => IO.unit
      )
      pd = ProjectDef(name = name, workspace = ws.toString, agentFile = (ws / "AGENTS.md").toString, createdAt = System.currentTimeMillis())
      rt = ProjectRuntime(pd, store, engine, system, res, None)
      _ <- ProjectRuntimeRegistry.register(rt)
    yield rt

  private def nodeEdit(input: Json, ctx: ToolContext): IO[Either[String, String]] =
    NodeEditTool.call(input.asObject.get, ctx).map(_.left.map(_.message))

  private def nodeInput(project: String, nodename: String, extra: (String, Json)*): Json =
    Json.obj(("project" -> Json.fromString(project)) :: ("nodename" -> Json.fromString(nodename)) :: extra.toList*)

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 50.millis)(cond: IO[Boolean]): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError("waitUntil: condition not met in time"))
          else IO.sleep(every) *> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  /** git 仓 workspace fixture：init + 身份 + 初始空提交（worktree add 需至少一提交）。 */
  private def gitWorkspace(name: String): os.Path =
    val ws = tempRoot / s"ws-git-$name-${Random.nextInt(100000)}"
    os.makeDir.all(ws)
    os.proc("git", "init", ws.toString).call(check = true)
    os.proc("git", "-C", ws.toString, "config", "user.email", "spec@nebflow.local").call(check = true)
    os.proc("git", "-C", ws.toString, "config", "user.name", "spec").call(check = true)
    os.proc("git", "-C", ws.toString, "commit", "--allow-empty", "-m", "init").call(check = true)
    ws

  private def plainWorkspace(name: String): os.Path =
    val ws = tempRoot / s"ws-plain-$name-${Random.nextInt(100000)}"
    os.makeDir.all(ws)
    ws

  // ── 1. description 契约 ─────────────────────────────────

  test("A① create without description → NODE_DESCRIPTION_REQUIRED; empty → same; >200 → NODE_DESCRIPTION_TOO_LONG") {
    val ws = plainWorkspace("desc")
    val system = ActorSystem(s"slim-desc-${Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot, new RecordingLlm)
      rt <- mountProject("slim-desc", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      missing <- nodeEdit(nodeInput("slim-desc", "n-missing", "task" -> Json.fromString("t"), "out" -> Json.fromString("Nebula")), ctx)
      blank <- nodeEdit(nodeInput("slim-desc", "n-blank", "description" -> Json.fromString("   "), "task" -> Json.fromString("t"), "out" -> Json.fromString("Nebula")), ctx)
      tooLong <- nodeEdit(nodeInput("slim-desc", "n-long", "description" -> Json.fromString("x" * 201), "task" -> Json.fromString("t"), "out" -> Json.fromString("Nebula")), ctx)
      ok <- nodeEdit(nodeInput("slim-desc", "n-ok", "description" -> Json.fromString("  within limit  "), "task" -> Json.fromString("t"), "out" -> Json.fromString("Nebula")), ctx)
      stored <- rt.store.getNode(ok.toOption.map(_ => "n-ok").getOrElse(""))
      snap <- rt.store.snapshot
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(missing.isLeft && missing.left.exists(_.contains("NODE_DESCRIPTION_REQUIRED")), s"missing description must be rejected, got: $missing")
      assert(blank.isLeft && blank.left.exists(_.contains("NODE_DESCRIPTION_REQUIRED")), s"blank description must be rejected, got: $blank")
      assert(tooLong.isLeft && tooLong.left.exists(_.contains("NODE_DESCRIPTION_TOO_LONG")), s">200 must be rejected, got: $tooLong")
      assert(ok.isRight, s"valid description must pass, got: $ok")
      // 创建时 trim 归一
      assertEquals(snap.nodes.values.find(_.name == "n-ok").flatMap(_.description), Some("within limit"))
  }

  test("A② edit updates description") {
    val ws = plainWorkspace("desc-edit")
    val system = ActorSystem(s"slim-de-${Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot, new RecordingLlm)
      rt <- mountProject("slim-de", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- nodeEdit(nodeInput("slim-de", "n-e", "description" -> Json.fromString("before"), "task" -> Json.fromString("t"), "out" -> Json.fromString("Nebula")), ctx)
      upd <- nodeEdit(nodeInput("slim-de", "n-e", "description" -> Json.fromString("after")), ctx)
      snap <- rt.store.snapshot
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(upd.isRight, s"description edit must succeed, got: $upd")
      assertEquals(snap.nodes.values.find(_.name == "n-e").flatMap(_.description), Some("after"))
  }

  // ── 2. agent/skill/mcp 退役 ─────────────────────────────

  test("B① passing agent/skill/mcp → rejected (NODE_AGENT_RETIRED, points at plugins)") {
    val ws = plainWorkspace("retired")
    val system = ActorSystem(s"slim-ret-${Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot, new RecordingLlm)
      rt <- mountProject("slim-ret", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      withAgent <- nodeEdit(nodeInput("slim-ret", "n-a", "agent" -> Json.fromString("test-agent"), "description" -> Json.fromString("d"), "task" -> Json.fromString("t"), "out" -> Json.fromString("Nebula")), ctx)
      withSkill <- nodeEdit(nodeInput("slim-ret", "n-s", "skill" -> Json.fromString("some-skill"), "description" -> Json.fromString("d"), "task" -> Json.fromString("t"), "out" -> Json.fromString("Nebula")), ctx)
      withMcp <- nodeEdit(nodeInput("slim-ret", "n-m", "mcp" -> Json.fromString("some-mcp"), "description" -> Json.fromString("d"), "task" -> Json.fromString("t"), "out" -> Json.fromString("Nebula")), ctx)
      snap <- rt.store.snapshot
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      for (r, label) <- List((withAgent, "agent"), (withSkill, "skill"), (withMcp, "mcp")) do
        assert(r.isLeft, s"$label param must be rejected")
        assert(r.left.exists(_.contains("NODE_AGENT_RETIRED")), s"$label rejection must carry NODE_AGENT_RETIRED, got: ${r.left.getOrElse("")}")
        assert(r.left.exists(_.contains("plugins")), s"$label rejection must point at plugins")
      assert(snap.nodes.isEmpty, "no node must be created from retired-param calls")
  }

  // ── 3. worktree 布尔派生（创建时机裁决 a：NodeEdit 即时创建 fail-fast）──

  test("C① worktree non-boolean (legacy string form) → WORKTREE_NOT_BOOLEAN") {
    val ws = gitWorkspace("wtnb")
    val system = ActorSystem(s"slim-wtnb-${Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot, new RecordingLlm)
      rt <- mountProject("slim-wtnb", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      r <- nodeEdit(nodeInput("slim-wtnb", "n-str", "worktree" -> Json.fromString("pre-made"), "description" -> Json.fromString("d"), "task" -> Json.fromString("t"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isLeft && r.left.exists(_.contains("WORKTREE_NOT_BOOLEAN")), s"string worktree must be rejected, got: $r")
  }

  test("C② worktree=true on non-git workspace → fail-fast rejection, NO node created") {
    val ws = plainWorkspace("nogit")
    val system = ActorSystem(s"slim-nogit-${Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot, new RecordingLlm)
      rt <- mountProject("slim-nogit", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      r <- nodeEdit(nodeInput("slim-nogit", "n-ng", "worktree" -> Json.fromBoolean(true), "description" -> Json.fromString("d"), "task" -> Json.fromString("t"), "out" -> Json.fromString("Nebula")), ctx)
      snap <- rt.store.snapshot
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isLeft && r.left.exists(_.contains("git repository")), s"non-git workspace must reject worktree=true, got: $r")
      assert(snap.nodes.isEmpty, "fail-fast: node must NOT be created when worktree creation fails")
  }

  test("C③ worktree=true on git workspace → derived worktree+branch created immediately, node bound to it") {
    val ws = gitWorkspace("wtyes")
    val system = ActorSystem(s"slim-wtyes-${Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot, new RecordingLlm)
      rt <- mountProject("slim-wtyes", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      r <- nodeEdit(nodeInput("slim-wtyes", "调研-派生一", "worktree" -> Json.fromBoolean(true), "description" -> Json.fromString("d"), "task" -> Json.fromString("t"), "out" -> Json.fromString("Nebula")), ctx)
      snap <- rt.store.snapshot
      // worktrees[] 名单自动纳入（NodeList 载荷 worktree 标记链路）
      payload <- NodeTools.buildNodeListPayload(rt)
      wts = payload.hcursor.downField("worktrees").as[List[String]].toOption.getOrElse(Nil)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isRight, s"worktree=true create must succeed, got: $r")
      val n = snap.nodes.values.find(_.name == "调研-派生一")
      assert(n.isDefined, "node must exist")
      val bare = n.get.worktree
      assert(bare.isDefined, s"node must carry worktree binding, node=$n")
      val derived = bare.get
      assert(os.exists(ws / ".nebflow" / "worktrees" / derived / ".git"), s"worktree dir must exist: $derived")
      val branches = os.proc("git", "-C", ws.toString, "branch", "--list", derived).call(check = true).out.trim()
      assert(branches.nonEmpty, s"same-name branch must exist for worktree '$derived', got: '$branches'")
      assert(wts.contains(derived), s"worktrees[] must include the derived worktree, got: $wts")
  }

  test("C④ worktree on edit → WORKTREE_CREATE_ONLY (create-time binding)") {
    val ws = plainWorkspace("wtedit")
    val system = ActorSystem(s"slim-wte-${Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot, new RecordingLlm)
      rt <- mountProject("slim-wte", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- nodeEdit(nodeInput("slim-wte", "n-w", "description" -> Json.fromString("d"), "task" -> Json.fromString("t"), "out" -> Json.fromString("Nebula")), ctx)
      r <- nodeEdit(nodeInput("slim-wte", "n-w", "worktree" -> Json.fromBoolean(true)), ctx)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isLeft && r.left.exists(_.contains("WORKTREE_CREATE_ONLY")), s"worktree on edit must be refused, got: $r")
  }

  // ── 4. E2E：新 schema 建节点 → 执行至 completed → 载荷收敛 + 按需读取 ──

  test("E2E: plugins[approved]+preset+description+worktree=true → completed; payload metadata-only; detail channel returns full result") {
    val ws = gitWorkspace("e2e")
    val system = ActorSystem(s"slim-e2e-${Random.nextInt(100000)}")
    for
      _ <- nebflow.core.plugin.PluginRegistry.approve("slim-e2e").flatMap {
        case Right(_) => IO.unit
        case Left(e)  => IO.raiseError(new RuntimeException(s"fixture approve failed: $e"))
      }
      res <- mkResources(system, tempRoot, new RecordingLlm)
      rt <- mountProject("slim-e2e", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      created <- nodeEdit(nodeInput("slim-e2e", "E2E-主节点",
        "description" -> Json.fromString("端到端载荷收敛验证节点"),
        "task" -> Json.fromString("produce the result"),
        "out" -> Json.fromString("Nebula"),
        "plugins" -> Json.arr(Json.fromString("slim-e2e")),
        "preset" -> Json.fromString("qa"),
        "worktree" -> Json.fromBoolean(true)), ctx)
      _ <- waitUntil(60.seconds)(rt.store.snapshot.map(
        _.nodes.values.exists(n => n.name == "E2E-主节点" && n.status == NodeLifecycle.Completed)))
      snap <- rt.store.snapshot
      payload <- NodeTools.buildNodeListPayload(rt)
      detailRaw <- NodeListTool.call(io.circe.JsonObject.fromIterable(List(
        "project" -> Json.fromString("slim-e2e"),
        "detail" -> Json.fromString(snap.nodes.values.find(_.name == "E2E-主节点").map(_.id).getOrElse("")))), ctx)
      nodeId = snap.nodes.values.find(_.name == "E2E-主节点").map(_.id).getOrElse("")
      diskJson <- IO.blocking(os.read(ws / ".nebflow" / "flow-map.json"))
      fileFull <- IO.blocking(os.read(ws / ".nebflow" / "results" / s"$nodeId.md"))
      memResult = snap.nodes.values.find(_.name == "E2E-主节点").flatMap(_.result)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(created.isRight, s"E2E create must succeed, got: $created")
      val n = snap.nodes.values.find(_.name == "E2E-主节点").getOrElse(fail("node missing"))
      // 执行统一 general + 配置面
      assertEquals(n.agent, "general", "new node agent must be pinned to general")
      assertEquals(n.plugins, List("slim-e2e"), "approved plugin must be allocated")
      assertEquals(n.preset, Some("qa"))
      assert(n.worktree.isDefined, "worktree=true must bind a worktree")
      assert(os.exists(ws / ".nebflow" / "worktrees" / n.worktree.get / ".git"), "derived worktree must exist")
      // 默认载荷：元数据 only —— 无 result 键（全文与摘要都不进）
      val nodes = payload.hcursor.downField("nodes").as[List[Json]].toOption.getOrElse(Nil)
      val mine = nodes.find(_.hcursor.get[String]("id").toOption.contains(n.id)).getOrElse(fail("node missing from payload"))
      assert(!mine.asObject.exists(_.keys.exists(_ == "result")), "default payload must NOT carry result")
      assertEquals(mine.hcursor.get[String]("description").toOption, Some("端到端载荷收敛验证节点"))
      assertEquals(mine.hcursor.get[Boolean]("hasResult").toOption, Some(true))
      // detail 通道：全文一致（同源 = 内存水合全文 = 落盘文件）
      val detail = detailRaw match
        case Right(raw) => io.circe.parser.parse(raw).getOrElse(fail("detail not json"))
        case Left(e)    => fail(s"detail failed: $e")
      assertEquals(detail.hcursor.get[String]("result").toOption, memResult, "detail channel result must equal in-memory full text")
      assertEquals(memResult, Some(ResultText), "in-memory result = stub LLM full output")
      // 落盘拆分：JSON 摘要、文件全文
      assert(!diskJson.contains(ResultText), "flow-map.json must NOT contain the full result text")
      assertEquals(fileFull, ResultText)
  }

end NodeSchemaSlimSpec
