package nebflow.agent

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fs2.Stream
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.PromptSections.PromptContext
import nebflow.core.PathUtil
import nebflow.core.entity.EntityLoader
import nebflow.core.project.{FlowMapStore, NodeLifecycle, ProjectDef, ProjectRuntime, ProjectRuntimeRegistry}
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, NodeEditTool, ToolContext}
import nebflow.gateway.RateLimiter
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.*

/**
 * 阶段 2d D.1-12：skill 目录注入停注 spec（设计 §D.1 #12 + §G.4）。
 *
 * - 新模型 node 会话（general 模版，skills:["*"] + 磁盘上存在 skill）首条
 *   消息的 system prompt 不含 skill 目录段（order 800 停注）。
 * - legacy agent 会话（test-agent，同样声明）保留目录注入（双轨期，阶段 3 删）。
 * - ContextRefresher.skillCatalogEnabledFor 开关逐角色断言（Nebula 保留，
 *   skill-creator alwaysVisible 语义不变）。
 * - wire 层 D.2 判据：general 节点收到的工具定义中 Read/Pop description 自含
 *   用法指南——「删条件段后未见段 agent 不退化」在请求层证明。
 *
 * 基建复用 NodePluginChainSpec（真实 NodeEdit → NodeEngine → 首条 LlmRequest
 * 捕获），RecordingLlm 瞬回。
 */
class Phase2dSkillCatalogSpec extends CatsEffectSuite:

  override val munitIOTimeout: FiniteDuration = 180.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-2d-skill-catalog"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)

  // general 模版：声明 skills:["*"]——停注改造的对象（node 会话模版）
  os.makeDir.all(tempRoot / "agents" / "general")
  os.write.over(
    tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"2d node template","skills":["*"]}"""
  )
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n\n## 无团队上下文\n")

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
      sessionStore = nebflow.gateway.SessionStore(tmp / "sessions", tmp / "tasks"),
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
        store, system, res,
        wsSendFn = (_: io.circe.Json) => IO.unit,
        workspace = ws.toString,
        rootSessionId = "nebula-root",
        projectName = name,
        emitEvent = (_, _, _) => IO.unit
      )
      pd = ProjectDef(name = name, workspace = ws.toString, agentFile = (ws / "AGENTS.md").toString, createdAt = System.currentTimeMillis())
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
        created <- nodeEdit(nodeInput(s"p2d-$tag", s"n-$tag",
          // 2026-09-05 agent 退役：节点执行统一 general，无 agent 参数可传。
          "description" -> io.circe.Json.fromString("catalog probe"),
          "task" -> io.circe.Json.fromString("catalog probe"),
          "out" -> io.circe.Json.fromString("Nebula")), ctx)
        _ = assert(created.isRight, s"NodeEdit must succeed: $created")
        _ <- waitUntil(60.seconds)(rt.store.snapshot.map(
          _.nodes.values.exists(n => n.name == s"n-$tag" && n.status == NodeLifecycle.Completed)))
        _ <- system.stopAll.handleErrorWith(_ => IO.unit)
        reqOpt = capture.values.headOption
      yield reqOpt
    (capture, program)

  // ── D.1-12：开关与注入断言 ─────────────────────────

  test("D.1-12: skillCatalogEnabledFor 逐角色——Nebula 保留，general/dispatcher 停注，legacy 保留"):
    assert(ContextRefresher.skillCatalogEnabledFor("Nebula"), "Nebula keeps the catalog (skill-creator alwaysVisible)")
    assert(!ContextRefresher.skillCatalogEnabledFor("general"), "node 会话模版停注")
    assert(!ContextRefresher.skillCatalogEnabledFor("project-dispatcher"), "dispatcher 停注")
    assert(ContextRefresher.skillCatalogEnabledFor("Explorer"), "legacy agent 保留至阶段 3")
    assert(ContextRefresher.skillCatalogEnabledFor("Coder"), "legacy agent 保留至阶段 3")

  test("D.1-12: node 会话（general 模版）首条消息不含 per-agent skill 目录（order 800 停注）"):
    // 注意：共享前缀层（system-prefix-for-all）已随阶段 2 批 A 退役——其
    // JAR 内静态「## Skills」段一并消失，断言语义不受影响（本测试只钉
    // order 800 per-agent 目录停注，设计 §D.1 #12）。
    // per-agent 目录的特征：目录头句「Skills live at」+ 条目行「- <skill>:」。
    val (_, program) = runNodeAndCapture(s"p2d-gen-${scala.util.Random.nextInt(100000)}", "general")
    val reqOpt = program.unsafeRunSync()
    val req = reqOpt.getOrElse(fail("no LlmRequest captured for general node"))
    val stable = req.systemStable.getOrElse(fail("systemStable missing"))
    assert(!stable.contains("Skills live at"), s"general node session must NOT carry the per-agent skill catalog (order 800 停注), got:\n${stable.take(1200)}")
    assert(!stable.contains("catalog-probe"), "fixture skill must not leak into node session prompt")

  // 2026-09-05 agent 退役：原「legacy agent 会话保留 per-agent skill 目录（双轨期
  // 对照）」wire 级测试随 NodeEdit agent 参数一并退役——节点执行统一 general，
  // 无法再经 NodeEdit 以 legacy agent spawn 会话。legacy 角色的目录注入开关语义
  // 仍由上方 skillCatalogEnabledFor 逐角色断言覆盖（保留至阶段 3 的裁定不变）。

  test("D.2 wire 层判据: general 节点收到的工具定义自含用法指南（删段不退化）"):
    val (_, program) = runNodeAndCapture(s"p2d-wire-${scala.util.Random.nextInt(100000)}", "general")
    val reqOpt = program.unsafeRunSync()
    val req = reqOpt.getOrElse(fail("no LlmRequest captured"))
    val tools = req.tools.getOrElse(fail("tools missing")).map(td => td.name -> td.description).toMap
    val read = tools.getOrElse("Read", fail("general node must receive Read"))
    assert(read.contains("Live results") && read.contains("Never re-read"),
      "Read description carries the order-410 live semantics at the wire level")
    val pop = tools.getOrElse("Pop", fail("general node must receive Pop"))
    assert(pop.contains("professional tool") && pop.contains("never hand-draw"),
      "Pop description carries the order-415 reporting workflow at the wire level")
    // 2026-09-06 节点面摘除 AskUser：general 节点 wire 层不得再收到该工具
    // （order-400 指南仍随工具 description 对 Nebula/显式声明身份生效，
    // 由 AskUserQuestionToolSpec/机制 spec 覆盖，此处只钉 general 交付面）
    assert(!tools.contains("AskUserQuestion"),
      "AskUserQuestion no longer on the general node default face (2026-09-06 交互出口统一)")

end Phase2dSkillCatalogSpec
