package nebflow.core.project

import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.Queue
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentLibrary, SharedResources}
import nebflow.core.PathUtil
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, NodeEditTool, ToolContext}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * 分发器单例化验收（2026-09-02 作者裁定：任务分发器实例每项目永远只有一个）。
 *
 * 覆盖（ProjectActor 单 actor 串行 + activeRef modify 原子占位 + 观察桥延迟拆除）：
 * 1. 连发注入同一会话：活跃会话存在时新任务注入现有会话（turn 边界串行消费，
 *    全程唯一 dispatcher-* 会话）；观察桥在注入 turn 跑完前延迟拆除。
 * 2. 并发到达不双 spawn：三条任务几乎同时触发 → 只 spawn 一个会话，三条全进
 *    同一会话按 turn 串行消费。
 * 3. 终态后 spawn 新实例：会话终态清理后第一个新任务 spawn 全新 sessionId。
 * 4. 重入优先投递（§2.2 修订）：blocked 重入请求在有活跃会话时注入现有会话，
 *    不并行 spawn 重入会话。
 */
class ProjectDispatcherSingletonSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 240.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-dispatcher-singleton"
  private val originalRoot = PathUtil.dataRoot

  // 类级：隔离 dataRoot + 预置 project-dispatcher / test-agent 定义
  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  for agent <- List("test-agent", "project-dispatcher", "general") do
    os.makeDir.all(tempRoot / "agents" / agent)
    os.write.over(
      tempRoot / "agents" / agent / "agent.json",
      s"""{"name":"$agent","description":"singleton test agent","tools":[],"category":"standalone"}"""
    )
    os.write.over(tempRoot / "agents" / agent / "system.md", s"# $agent\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  private def blockedText(category: String, detail: String, suggestion: String): String =
    s"""BLOCKED: 无法继续
       |{"category":"$category","detail":"$detail","suggestion":"$suggestion"}""".stripMargin

  /** 门控 LLM：分发器 turn（非 will-block-S 输入）等 gate 后回 "ok"；节点 turn
    * （will-block-S 任务）立即回 BLOCKED 文本（不占 gate）。inputs 记录全部分发器输入。 */
  private class GatedLlm(gates: Queue[IO, Deferred[IO, Unit]]):
    val inputs: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)
    def offerGate: IO[Deferred[IO, Unit]] = Deferred[IO, Unit].flatTap(gates.offer)
    val handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(
          req: LlmRequest,
          onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
      ): Stream[IO, StreamChunk] =
        val text = req.messages.map(_.textContent).mkString("\n")
        if text.contains("will-block-S") then
          Stream(
            StreamChunk.TextDelta(blockedText("task-underspecified", "任务缺少交付物定义", "补充验收标准")),
            StreamChunk.Done(None, None)
          )
        else
          Stream
            .eval(inputs.update(_ :+ text))
            .flatMap(_ => Stream.eval(gates.take.flatMap(_.get)))
            .flatMap(_ => Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None)))

  private def mkGatedLlm: IO[GatedLlm] =
    Queue.unbounded[IO, Deferred[IO, Unit]].map(new GatedLlm(_))

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
      actorSystem = system,
      voiceMutedRef = voiceMuted
    )

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

  private def dispatcherEntries(resources: SharedResources): IO[List[String]] =
    resources.agentRegistry.get.map(_.keys.toList.filter(_.startsWith(ProjectActor.DispatcherSessionPrefix)))

  private def mount(name: String, ws: os.Path, system: ActorSystem, res: SharedResources): IO[ProjectRuntime] =
    val pd = ProjectDef(
      name = name,
      workspace = ws.toString,
      agentFile = (ws / "AGENTS.md").toString,
      createdAt = System.currentTimeMillis()
    )
    ProjectRuntimeRegistry.mount(pd, system, res, None, rootSessionId = "nebula-root")

  private def mkCtx(res: SharedResources, system: ActorSystem, ws: String): ToolContext =
    ToolContext(
      projectRoot = ws,
      sessionId = Some("singleton-sid"),
      rootSessionId = Some("nebula-root"),
      sharedResources = Some(res),
      actorSystem = Some(system)
    )

  private def nodeEdit(input: Json, ctx: ToolContext): IO[Either[String, String]] =
    NodeEditTool.call(input.asObject.get, ctx).map(_.left.map(_.message))

  private def nodeInput(project: String, nodename: String, extra: (String, Json)*): Json =
    Json.obj(("project" -> Json.fromString(project)) :: ("nodename" -> Json.fromString(nodename)) :: extra.toList*)

  private def waitStatus(rt: ProjectRuntime, name: String, statuses: Set[String]): IO[Unit] =
    waitUntil(20.seconds) {
      rt.store.snapshot.map(_.nodes.values.find(_.name == name)).flatMap {
        case Some(n) => IO.pure(statuses.contains(n.status))
        case None    => IO.pure(false)
      }
    }

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  // ── 1. 连发注入同一会话 + 观察桥延迟拆除 ──────────────────────────

  test("连发注入：活跃会话收到新任务 → 注入排队（无第二 spawn），末 turn 终态后才拆除") {
    val ws = tempRoot / "ws-inject"
    os.makeDir.all(ws)
    val system = ActorSystem(s"singleton-inject-${scala.util.Random.nextInt(100000)}")
    for
      llm <- mkGatedLlm
      g1 <- llm.offerGate // turn 1（spawn prompt）就绪
      resources <- mkResources(system, tempRoot, llm.handle)
      rt <- mount("singleton-inject", ws, system, resources)
      actorRef = rt.actorRef.getOrElse(sys.error("ProjectActor must be spawned by mount"))
      _ <- (actorRef ! ProjectActor.ProjectCommand.TriggerDispatcher("任务甲", "nebula-root")).void
      _ <- waitUntil(20.seconds)(dispatcherEntries(resources).map(_.nonEmpty)) // turn 1 gated 在飞
      _ <- (actorRef ! ProjectActor.ProjectCommand.TriggerDispatcher("任务乙", "nebula-root")).void
      _ <- IO.sleep(1.second) // 让 ProjectActor 处理完乙（注入占位先于 g1 释放——串行化保证）
      before <- dispatcherEntries(resources)
      _ <- g1.complete(()).void // turn 1 完成 → 桥必须延迟拆除（乙 turn 未跑）
      g2 <- llm.offerGate
      _ <- waitUntil(20.seconds)(llm.inputs.get.map(_.size >= 2)) // turn 2 消费注入（记录先于 gate）
      during <- dispatcherEntries(resources)
      _ <- g2.complete(()).void // 末 turn 终态 → 拆除
      _ <- waitUntil(20.seconds)(dispatcherEntries(resources).map(_.isEmpty))
      after <- dispatcherEntries(resources)
      ins <- llm.inputs.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(before.size, 1, "注入期间只允许一个活跃会话")
      assertEquals(during.size, 1, s"turn 1 终态后桥必须延迟拆除（注入 turn 未跑完），got $during")
      assertEquals(after, Nil, "最后一 turn 终态后清 registry")
      assertEquals(ins.size, 2, "全程恰好 2 个分发器 turn——不得出现第二 spawn")
      assert(ins(1).contains("新任务到达"), s"第二个 turn 必须是注入形态（含「新任务到达」标注），got: ${ins(1).take(120)}")
      assert(ins(1).contains("任务乙"), "注入文本必须携带任务内容")
      assert(!ins(0).contains("新任务到达"), "首个 turn 是 spawn prompt，非注入形态")
  }

  // ── 2. 并发到达只 spawn 一个 ─────────────────────────────────────

  test("并发到达：三条任务几乎同时触发 → 只 spawn 一个会话，三条按 turn 串行消费") {
    val ws = tempRoot / "ws-rapid"
    os.makeDir.all(ws)
    val system = ActorSystem(s"singleton-rapid-${scala.util.Random.nextInt(100000)}")
    for
      llm <- mkGatedLlm
      g1 <- llm.offerGate
      g2 <- llm.offerGate
      g3 <- llm.offerGate
      resources <- mkResources(system, tempRoot, llm.handle)
      rt <- mount("singleton-rapid", ws, system, resources)
      actorRef = rt.actorRef.getOrElse(sys.error("ProjectActor must be spawned by mount"))
      // 三连发：check-then-spawn 在 ProjectActor 单 actor 内串行——首条 spawn，
      // 后两条原子占位注入，无双 spawn 竞态
      _ <- (actorRef ! ProjectActor.ProjectCommand.TriggerDispatcher("任务甲", "nebula-root")).void
      _ <- (actorRef ! ProjectActor.ProjectCommand.TriggerDispatcher("任务乙", "nebula-root")).void
      _ <- (actorRef ! ProjectActor.ProjectCommand.TriggerDispatcher("任务丙", "nebula-root")).void
      _ <- waitUntil(20.seconds)(llm.inputs.get.map(_.size >= 1))
      _ <- IO.sleep(1.second) // ProjectActor 串行处理乙/丙注入
      one <- dispatcherEntries(resources)
      _ <- g1.complete(()).void
      _ <- waitUntil(20.seconds)(llm.inputs.get.map(_.size >= 2))
      _ <- g2.complete(()).void
      _ <- waitUntil(20.seconds)(llm.inputs.get.map(_.size >= 3))
      two <- dispatcherEntries(resources)
      _ <- g3.complete(()).void
      _ <- waitUntil(20.seconds)(dispatcherEntries(resources).map(_.isEmpty))
      ins <- llm.inputs.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(one.size, 1, s"三连发只允许一个会话，got $one")
      assertEquals(two.size, 1, s"串行消费期间桥必须保持会话存活，got $two")
      assertEquals(ins.size, 3, "三条任务恰好三个 turn（同一会话串行消费）")
      assert(ins(0).contains("任务甲") && !ins(0).contains("新任务到达"), "turn 1 = spawn prompt 形态")
      assert(ins(1).contains("新任务到达") && ins(1).contains("任务乙"), "turn 2 = 注入排队消费（乙）")
      assert(ins(2).contains("新任务到达") && ins(2).contains("任务丙"), "turn 3 = 注入排队消费（丙）")
  }

  // ── 3. 终态后 spawn 新实例 ───────────────────────────────────────

  test("终态后 spawn：会话终态清理后，新任务 spawn 全新分发器实例（新 sessionId，非注入形态）") {
    val ws = tempRoot / "ws-respawn"
    os.makeDir.all(ws)
    val system = ActorSystem(s"singleton-respawn-${scala.util.Random.nextInt(100000)}")
    for
      llm <- mkGatedLlm
      g1 <- llm.offerGate
      resources <- mkResources(system, tempRoot, llm.handle)
      rt <- mount("singleton-respawn", ws, system, resources)
      actorRef = rt.actorRef.getOrElse(sys.error("ProjectActor must be spawned by mount"))
      _ <- (actorRef ! ProjectActor.ProjectCommand.TriggerDispatcher("任务甲", "nebula-root")).void
      _ <- waitUntil(20.seconds)(dispatcherEntries(resources).map(_.nonEmpty))
      sid1 <- dispatcherEntries(resources).map(_.head)
      _ <- g1.complete(()).void // pendingInjected=0 → 立即拆除
      _ <- waitUntil(20.seconds)(dispatcherEntries(resources).map(_.isEmpty))
      g2 <- llm.offerGate
      _ <- (actorRef ! ProjectActor.ProjectCommand.TriggerDispatcher("任务乙", "nebula-root")).void
      _ <- waitUntil(20.seconds)(dispatcherEntries(resources).map(_.nonEmpty))
      sids <- dispatcherEntries(resources)
      _ <- g2.complete(()).void
      _ <- waitUntil(20.seconds)(dispatcherEntries(resources).map(_.isEmpty))
      ins <- llm.inputs.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(sids.size, 1, "终态后新任务恰好 spawn 一个新会话")
      assert(!sids.contains(sid1), s"新实例必须是新 sessionId（旧=$sid1 新=${sids.mkString})")
      assertEquals(ins.size, 2)
      assert(!ins(1).contains("新任务到达"), "终态后的新任务走 spawn 路径（fresh prompt，非注入形态）")
      assert(ins(1).contains("任务乙"))
  }

  // ── 4. 重入优先投递活跃会话（§2.2 修订）──────────────────────────

  test("重入优先投递：blocked 重入在有活跃分发器时注入现有会话，不并行 spawn 重入会话") {
    val ws = tempRoot / "ws-reentry"
    os.makeDir.all(ws)
    val system = ActorSystem(s"singleton-reentry-${scala.util.Random.nextInt(100000)}")
    for
      llm <- mkGatedLlm
      g1 <- llm.offerGate
      resources <- mkResources(system, tempRoot, llm.handle)
      rt <- mount("singleton-reentry", ws, system, resources)
      ctx = mkCtx(resources, system, ws.toString)
      actorRef = rt.actorRef.getOrElse(sys.error("ProjectActor must be spawned by mount"))
      _ <- (actorRef ! ProjectActor.ProjectCommand.TriggerDispatcher("任务甲", "nebula-root")).void
      _ <- waitUntil(20.seconds)(dispatcherEntries(resources).map(_.nonEmpty)) // turn 1 gated 在飞
      // blocked 节点 → FeedbackRouter(auto) → ReenterDispatcher → 活跃会话 → 注入排队
      _ <- nodeEdit(nodeInput("singleton-reentry", "blk-node", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("will-block-S"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- waitStatus(rt, "blk-node", Set(NodeLifecycle.Blocked))
      _ <- IO.sleep(1.second) // ProjectActor 处理完 ReenterDispatcher 注入
      _ <- g1.complete(()).void // turn 1 完成 → 延迟拆除 → 边界消费重入注入
      g2 <- llm.offerGate
      _ <- waitUntil(20.seconds)(llm.inputs.get.map(_.size >= 2))
      during <- dispatcherEntries(resources)
      _ <- g2.complete(()).void
      _ <- waitUntil(20.seconds)(dispatcherEntries(resources).map(_.isEmpty))
      ins <- llm.inputs.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(during.size, 1, "重入必须注入现有会话——不得并行 spawn 第二个会话")
      assertEquals(ins.size, 2, "全程恰好 2 个分发器 turn")
      assert(ins(1).contains("节点反馈重入调整"), s"第二个 turn 必须是重入注入形态，got: ${ins(1).take(120)}")
      assert(ins(1).contains("blk-node"), "重入注入必须携带 blocked 节点名")
  }

end ProjectDispatcherSingletonSpec
