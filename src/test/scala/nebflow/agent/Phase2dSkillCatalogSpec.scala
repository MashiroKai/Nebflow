package nebflow.agent

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fs2.Stream
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.actor.{sessionId, status}
import nebflow.agent.PromptSections.PromptContext
import nebflow.core.entity.EntityLoader
import nebflow.core.project.{FlowMapStore, NodeLifecycle, ProjectDef, ProjectRuntime, ProjectRuntimeRegistry}
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, NodeEditTool, ToolContext}
import nebflow.core.RateLimiter
import nebflow.llm.ModelCandidate
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk, ThinkingConfig}

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.*

/**
 * 阶段 2d D.1-12：skill 目录注入停注 spec（设计 §D.1 #12 + §G.4）。
 *
 * - 新模型 node 会话（执行 agent，skills:["*"] + 磁盘上存在 skill）首条
 *   消息的 system prompt 不含 skill 目录段（order 800 停注）。
 * - legacy agent 会话（test-agent，同样声明）保留目录注入（双轨期，阶段 3 删）。
 * - ContextRefresher.skillCatalogEnabledFor 开关逐角色断言（Nebula 保留，
 *   skill-creator alwaysVisible 语义不变）。
 * - wire 层 D.2 判据：执行 agent 节点收到的工具定义中 Read/Pop description 自含
 *   用法指南——「删条件段后未见段 agent 不退化」在请求层证明。
 *
 * P1-3 归因修正（2026-10-03）：节点执行名随 builtin-merge 批收敛为
 * [[nebflow.core.entity.BuiltinAgents.ExecutorName]]（`general` 不再是内置名）——
 * 原夹具/断言按旧名造身份 ⇒ 1/3 假红；本批把判据源对齐生产执行名（停注语义
 * 逐字不变，只换名），**不是**放宽断言。
 *
 * 基建复用 NodePluginChainSpec（真实 NodeEdit → NodeEngine → 首条 LlmRequest
 * 捕获），RecordingLlm 瞬回。
 */
class Phase2dSkillCatalogSpec extends CatsEffectSuite:

  override val munitIOTimeout: FiniteDuration = 180.seconds

  /** 节点实际执行的 agent 名（builtin-merge 批 2026-10-03 收敛单点）。
    * P1-3 归因修正（2026-10-03）：旧夹具/断言按已退役名 `general` 造身份——该名已
    * 非内置名（BuiltinAgents.Names），判据源对齐生产执行名。 */
  private val executorName: String = nebflow.core.entity.BuiltinAgents.ExecutorName

  private val tempRoot: os.Path = os.pwd / "target" / "test-2d-skill-catalog"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)

  // 执行 agent（节点会话模版）：磁盘目录对该名是**死信**（builtin-def 批 2026-10-03
  // 起代码定义唯一权威）——夹具保留以证明「磁盘声明 skills:["*"] 不生效」这一更强
  // 断言（代码定义 skills = Nil）。目录段结构上无从产生。
  os.makeDir.all(tempRoot / "agents" / executorName)

  os.write.over(
    tempRoot / "agents" / executorName / "agent.json",
    s"""{"name":"$executorName","description":"2d node template","skills":["*"]}"""
  )
  os.write.over(tempRoot / "agents" / executorName / "system.md", s"# $executorName\n\n## 无团队上下文\n")

  // legacy agent：同样声明 skills:["*"]——双轨期对照（必须保留注入）
  os.makeDir.all(tempRoot / "agents" / "test-agent")

  os.write.over(
    tempRoot / "agents" / "test-agent" / "agent.json",
    """{"name":"test-agent","description":"legacy contrast agent","skills":["*"]}"""
  )
  os.write.over(tempRoot / "agents" / "test-agent" / "system.md", "# test-agent\n")

  // skill 库 fixture（modelInvocable：有 name + description 即可）
  os.makeDir.all(tempRoot / "skills" / "catalog-probe")

  os.write.over(
    tempRoot / "skills" / "catalog-probe" / "SKILL.md",
    """---
      |name: catalog-probe
      |description: 2d skill catalog probe fixture
      |---
      |# body""".stripMargin
  )

  os.write.over(tempRoot / "nebflow.json", "{}")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  // ── 基建（NodePluginChainSpec 同款）─────────────────────────

  private class RecordingLlm(capture: TrieMap[String, LlmRequest]) extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))

    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(IO(capture.update(req.sessionId, req))).drain ++
        Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  private def mkResources(system: ActorSystem, tmp: os.Path, llm: LlmHandle[IO]): IO[SharedResources] =
    for
      dispatcher <- cats.effect.std.Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- nebflow.core.FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- cats.effect.Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- cats.effect.Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- cats.effect.Ref.of[IO, Boolean](false)
    yield SharedResources(
      llm = llm,
      dispatcher = dispatcher,
      sessionStore = nebflow.core.SessionStore(tmp / "sessions", tmp / "tasks"),
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

  private def mountProject(name: String, ws: os.Path, system: ActorSystem, res: SharedResources): IO[ProjectRuntime] =
    for
      store <- FlowMapStore.open(name, ws.toString)
      engine = new nebflow.core.project.NodeEngine(
        store,
        system,
        res,
        wsSendFn = (_: io.circe.Json) => IO.unit,
        workspace = ws.toString,
        rootSessionId = "nebula-root",
        projectName = name,
        emitEvent = (_, _, _) => IO.unit,
        // noderpt 批 A 段（2026-09-11）：**完成门腿 2** 生产默认 **开**
        // （`Defaults.NodeReportCompletionHold=true`；判定点 `NodeStarter.scala` 的
        // `case None if reportGateHoldEnabled && !anchoredBlocked`——2026-09-25 H 步重钉：
        // 随启动簇 runWithAgent 自 `NodeEngine.scala` 迁至 NodeStarter，行为保持重构）
        // ——节点交棒（桥收到 `AgentEvent.Completed`）而**未**调 `node_report` 时
        // **不**终态化**，节点保持 Running（`NodeStarter.scala` 桥 hold 分支
        // `markReportPendingIfAbsent(...).as(bridge)` 逐字）。本 spec 的主题是
        // 「skill 目录停注 / wire 层工具描述」，`waitUntil(status == Completed)`
        // （`:183-184`，载体 `:158/:154`）只是取首条 `LlmRequest` 的**前置**——腿 2
        // 开着 ⇒ 该条件永不满足 ⇒ 60s 到点必假红。
        // 故**显式关腿 2（仅测试面注入、零生产改动）**：14 个兄弟 spec 同款写法
        // （`MountedProjectsWiringSpec.scala:108`、`CompletionGateSpec.scala:182` …）；
        // 腿 2「默认开」的行为本体验由 `NodeReportReminderSpec` 覆盖。
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

  private def mkCtx(res: SharedResources, system: ActorSystem, ws: String): ToolContext =
    ToolContext(
      projectRoot = ws,
      sessionId = Some("2d-sid"),
      rootSessionId = Some("nebula-root"),
      sharedResources = Some(res),
      actorSystem = Some(system)
    )

  private def nodeEdit(input: io.circe.Json, ctx: ToolContext): IO[Either[String, String]] =
    NodeEditTool.call(input.asObject.get, ctx).map(_.left.map(_.message))

  private def nodeInput(project: String, nodename: String, extra: (String, io.circe.Json)*): io.circe.Json =
    io.circe.Json.obj(
      ("project" -> io.circe.Json.fromString(project)) ::
        ("nodename" -> io.circe.Json.fromString(nodename)) ::
        ("plugins" -> io.circe.Json.arr()) ::
        extra.toList*
    )

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

  private def runNodeAndCapture(
    systemName: String,
    agentName: String
  ): (TrieMap[String, LlmRequest], IO[Option[LlmRequest]]) =
    val capture = TrieMap[String, LlmRequest]()
    val program =
      for
        system <- IO(ActorSystem(systemName))
        res <- mkResources(system, tempRoot, new RecordingLlm(capture))
        tag = s"${agentName}-${scala.util.Random.nextInt(100000)}"
        ws = tempRoot / s"ws-$tag"
        _ <- IO(os.makeDir.all(ws))
        rt <- mountProject(s"p2d-$tag", ws, system, res)
        ctx = mkCtx(res, system, ws.toString)
        created <- nodeEdit(
          nodeInput(
            s"p2d-$tag",
            s"n-$tag",
            // 2026-09-05 agent 退役：节点执行统一 general，无 agent 参数可传。
            "description" -> io.circe.Json.fromString("catalog probe"),
            "task" -> io.circe.Json.fromString("catalog probe"),
            "out" -> io.circe.Json.fromString("Nebula")
          ),
          ctx
        )
        _ = assert(created.isRight, s"NodeEdit must succeed: $created")
        _ <- waitUntil(60.seconds)(
          rt.store.snapshot.map(_.nodes.values.exists(n => n.name == s"n-$tag" && n.status == NodeLifecycle.Completed))
        )
        _ <- system.stopAll.handleErrorWith(_ => IO.unit)
        reqOpt = capture.values.headOption
      yield reqOpt
    (capture, program)
  end runNodeAndCapture

  // ── D.1-12：开关与注入断言 ─────────────────────────

  test("D.1-12: skillCatalogEnabledFor 逐角色——Nebula 保留，执行 agent/dispatcher 停注，legacy 保留"):
    // P1-3 归因修正（2026-10-03）：builtin-merge 批把节点执行名从 `general` 收敛为
    // BuiltinAgents.ExecutorName（= nebflow）⇒ 旧断言用手搓 "general" 名判「node 会话
    // 模版停注」已指不到执行面（该名不在 ConvergedAgentNames ⇒ 函数返回 true ⇒ 假红）。
    // 判据源对齐生产执行名 = 修根因不是放宽断言：停注语义（收敛名非 root ⇒ 停注）
    // 逐字不变，只把名字换成执行名。
    assert(ContextRefresher.skillCatalogEnabledFor("Nebula"), "Nebula keeps the catalog (skill-creator alwaysVisible)")
    assert(!ContextRefresher.skillCatalogEnabledFor(executorName), "执行 agent（node 会话模版）停注")
    assert(!ContextRefresher.skillCatalogEnabledFor("project-dispatcher"), "dispatcher 停注")
    assert(!ContextRefresher.skillCatalogEnabledFor("subagent"), "只读侦察子面同样停注（builtin-merge 批收敛名）")
    assert(ContextRefresher.skillCatalogEnabledFor("Explorer"), "legacy agent 保留至阶段 3")
    assert(ContextRefresher.skillCatalogEnabledFor("Coder"), "legacy agent 保留至阶段 3")

  test("D.1-12: node 会话（执行 agent 模版）首条消息不含 per-agent skill 目录（order 800 停注）"):
    // 注意：共享前缀层（system-prefix-for-all）已随阶段 2 批 A 退役——其
    // JAR 内静态「## Skills」段一并消失，断言语义不受影响（本测试只钉
    // order 800 per-agent 目录停注，设计 §D.1 #12）。
    // per-agent 目录的特征：目录头句「Skills live at」+ 条目行「- <skill>:」。
    // P1-3 归因修正（2026-10-03）：节点执行名现为 BuiltinAgents.ExecutorName
    // （代码定义、skills = Nil）——本用例的判据面因此比磁盘夹具更强：目录段
    // 结构上无从产生。
    val (_, program) = runNodeAndCapture(s"p2d-gen-${scala.util.Random.nextInt(100000)}", executorName)
    val reqOpt = program.unsafeRunSync()
    val req = reqOpt.getOrElse(fail("no LlmRequest captured for the executor node"))
    val stable = req.systemStable.getOrElse(fail("systemStable missing"))
    assert(
      !stable.contains("Skills live at"),
      s"executor node session must NOT carry the per-agent skill catalog (order 800 停注), got:\n${stable.take(1200)}"
    )
    assert(!stable.contains("catalog-probe"), "fixture skill must not leak into node session prompt")

  // 2026-09-05 agent 退役：原「legacy agent 会话保留 per-agent skill 目录（双轨期
  // 对照）」wire 级测试随 NodeEdit agent 参数一并退役——节点执行统一执行 agent
  // （builtin-merge 批 2026-10-03 收敛为 BuiltinAgents.ExecutorName），
  // 无法再经 NodeEdit 以 legacy agent spawn 会话。legacy 角色的目录注入开关语义
  // 仍由上方 skillCatalogEnabledFor 逐角色断言覆盖（保留至阶段 3 的裁定不变）。

  test("D.2 wire 层判据: 执行 agent 节点收到的工具定义自含用法指南（删段不退化）"):
    val (_, program) = runNodeAndCapture(s"p2d-wire-${scala.util.Random.nextInt(100000)}", executorName)
    val reqOpt = program.unsafeRunSync()
    val req = reqOpt.getOrElse(fail("no LlmRequest captured"))
    val tools = req.tools.getOrElse(fail("tools missing")).map(td => td.name -> td.description).toMap
    val read = tools.getOrElse("Read", fail("executor node must receive Read"))
    assert(
      read.contains("Live results") && read.contains("Never re-read"),
      "Read description carries the order-410 live semantics at the wire level"
    )
    // 2026-09-10 作者裁定翻转本断言：Pop 收归 Nebula 专属——执行 agent 节点在
    // wire 层（LLM 工具面）收不到 Pop 的 schema（定义层摘除节点固定面 +
    // RootExclusiveTools 剥离；执行面另有 PopTool 身份闸兜底）
    assert(
      !tools.contains("Pop"),
      s"executor node must NOT receive Pop (2026-09-10 作者裁定：Pop 收归 Nebula 专属), got keys: ${tools.keys.toList.sorted}"
    )
    // 2026-09-08 作者修订恢复 AskUser（D6 批D1）：节点 wire 层重新
    // 收到该工具（2026-09-06 摘除断言反向）；order-400 指南仍随工具 description
    // 自包含生效（删段不退化判据恢复钉死）
    val ask =
      tools.getOrElse("AskUserQuestion", fail("executor node must receive AskUserQuestion (2026-09-08 restored)"))
    assert(
      ask.contains("When NOT to use"),
      "AskUserQuestion description carries the order-400 guidance at the wire level"
    )
    assert(
      ask.contains("node-ask trace event") && ask.contains("project · node"),
      "AskUserQuestion description carries the supervision note (D6 批D1: node-ask 留痕 + 来源标注)"
    )

end Phase2dSkillCatalogSpec
