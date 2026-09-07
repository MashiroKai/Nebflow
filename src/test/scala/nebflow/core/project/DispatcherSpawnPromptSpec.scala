package nebflow.core.project

import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.Queue
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentLibrary, SharedResources}
import nebflow.core.PathUtil
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/** 分发器 spawn 首条消息形态 spec（观测面上下文经济学批 20260907 裁定①方向 B）。
  *
  * 审计基准：.nebflow/Spec/20260907_nodelist-flowmap-context-audit.md §4 P0-1——
  * 旧实现 newTaskPrompt/reentryPrompt 把 `store.snapshot`（内存模型 result/task
  * 双全文水合）整体 asJson 直灌首条消息，nebflow 规模（131 节点）≈1,055KB ≈
  * 260K+ tokens > CompactThreshold（256K 固定/0.8×window）→ spawn 即进压缩死亡
  * 螺旋。方向 B：首条消息 = 系统 prompt + 目录 + 项目记忆 + 任务文本，Flow Map
  * 拓扑由分发器首轮 NodeList 按需拉取（元数据 only + ToolResultGuard persist 兜底）。
  *
  * 本 spec 在真实 ProjectActor spawn 链上（门控 LLM 捕获全部分发器输入，
  * ProjectDispatcherSingletonSpec harness 同款）以 nebflow 规模 Flow Map 固化：
  *  1. 首条消息不含 "result": / "task": 水合 JSON 字段、不含 ```json 快照围栏、
  *     不含 "v":1 快照签名（审计验收点①——grep 断言固化）；
  *  2. 首条消息 < 30KB（审计验收点①尺寸线；旧路径同规模 ≈0.9-1MB）；
  *  3. 携带任务文本 + 「先 NodeList」工作流指引；
  *  4. 经济账对照打印：同规模快照 asJson 字节数（= 旧实现嵌入量）vs 实际首条。
  */
class DispatcherSpawnPromptSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 240.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-dispatcher-spawn-prompt"
  private val originalRoot = PathUtil.dataRoot

  // 类级：隔离 dataRoot + 预置 project-dispatcher / general 定义（空 plugin/preset
  // 目录 → pluginCatalogText()=""，首条消息只含任务文本与工作流指引）。
  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  for agent <- List("project-dispatcher", "general") do
    os.makeDir.all(tempRoot / "agents" / agent)
    os.write.over(
      tempRoot / "agents" / agent / "agent.json",
      s"""{"name":"$agent","description":"spawn prompt spec agent","tools":[],"category":"standalone"}"""
    )
    os.write.over(tempRoot / "agents" / agent / "system.md", s"# $agent\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  /** 门控 LLM：记录全部分发器输入文本；等 gate 后回 "ok"（完成即止）。 */
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
        Stream
          .eval(inputs.update(_ :+ text))
          .flatMap(_ => Stream.eval(gates.take.flatMap(_.get)))
          .flatMap(_ => Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None)))

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

  private def mount(name: String, ws: os.Path, system: ActorSystem, res: SharedResources): IO[ProjectRuntime] =
    val pd = ProjectDef(
      name = name,
      workspace = ws.toString,
      agentFile = (ws / "AGENTS.md").toString,
      createdAt = System.currentTimeMillis()
    )
    ProjectRuntimeRegistry.mount(pd, system, res, None, rootSessionId = "nebula-root")

  /** nebflow 规模 Flow Map：131 节点（审计附表活动区形态——96% 终态 + 少量
    * running/wiring），result 全文 ≈3.8KB/节点 + task 全文 ≈3.1KB/节点
    * （审计附表：result 合计 516KB / task 合计 404KB）。内存水合态直接经
    * store.mutate 种入 = 旧实现 spawn 通道序列化的同一形态。 */
  private def seedNebflowScaleMap(rt: ProjectRuntime): IO[Long] =
    val now = System.currentTimeMillis()
    val statuses = List(NodeLifecycle.Completed, NodeLifecycle.Cancelled, NodeLifecycle.Failed, NodeLifecycle.Running, NodeLifecycle.Wiring)
    val defs: Map[String, NodeDef] = (0 until 131).map { i =>
      val st = statuses(i % statuses.size)
      val terminal = NodeLifecycle.Terminal.contains(st)
      s"n-spawn-$i" -> NodeDef(
        id = s"n-spawn-$i",
        name = s"节点-$i",
        agent = "general",
        task = Some(s"任务全文-$i。" + "T" * 3100),
        description = Some(s"nebflow 规模夹具节点 $i"),
        status = st,
        result = if terminal then Some(s"结果全文-$i。" + "R" * 3800) else None,
        createdAt = now + i
      )
    }.toMap
    rt.store.mutate(s => s.copy(nodes = s.nodes ++ defs)).flatMap { s2 =>
      // 旧实现嵌入量基准：同一内存水合态的整体 asJson（= 旧 spawn 直灌字节）
      IO.pure(s2.asJson.noSpaces.getBytes("UTF-8").length.toLong)
    }

  test("spawn 首条消息：nebflow 规模 Flow Map 下不含快照字节（result:/task:/```json/v:1 零命中）且 <30KB") {
    val ws = tempRoot / "ws-spawn-prompt"
    os.makeDir.all(ws)
    val system = ActorSystem(s"spawn-prompt-${scala.util.Random.nextInt(100000)}")
    for
      llm <- mkGatedLlm
      g1 <- llm.offerGate
      resources <- mkResources(system, tempRoot, llm.handle)
      rt <- mount("spawn-prompt", ws, system, resources)
      oldStyleBytes <- seedNebflowScaleMap(rt)
      taskText = "把登录模块的性能问题查清并修掉（spawn-prompt 契约任务）"
      actorRef = rt.actorRef.getOrElse(sys.error("ProjectActor must be spawned by mount"))
      _ <- (actorRef ! ProjectActor.ProjectCommand.TriggerDispatcher(taskText, "nebula-root")).void
      _ <- waitUntil(20.seconds)(llm.inputs.get.map(_.nonEmpty))
      _ <- g1.complete(()).void // 首个（也是唯一）turn 完成 → 拆除
      _ <- waitUntil(20.seconds)(resources.agentRegistry.get.map(!_.keySet.exists(_.startsWith(ProjectActor.DispatcherSessionPrefix))))
      ins <- llm.inputs.get
      snap <- rt.store.snapshot
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(ins.size, 1, "恰好一个分发器 turn（spawn 首条消息）")
      val first = ins.head
      // 审计验收点①：水合 JSON 全文字段零命中（旧实现 96% 字节来源）
      assert(!first.contains("\"result\":"), s"first message must NOT contain hydrated result JSON field, len=${first.length}")
      assert(!first.contains("\"task\":"), s"first message must NOT contain hydrated task JSON field, len=${first.length}")
      // 快照形态零命中：围栏 + FlowMapState 签名键（v:1/project/updatedAt 同现才=快照）
      assert(!first.contains("```json"), "first message must NOT embed a ```json snapshot fence")
      assert(!first.contains("\"v\":1") && !first.contains("\"updatedAt\":"), "first message must NOT carry FlowMapState snapshot signature keys")
      // 尺寸线（审计验收点①：<30KB；旧路径同规模 ≈0.9MB+）
      val bytes = first.getBytes("UTF-8").length
      assert(bytes < 30_000, s"spawn first message must stay under 30KB (audit P0-1), got $bytes bytes")
      // 形态：任务文本 + 先 NodeList 工作流指引 + 项目名
      assert(first.contains(taskText), "first message must carry the task text")
      assert(first.contains("先 NodeList"), "first message must keep the NodeList-first workflow guidance")
      assert(first.contains("spawn-prompt"), "first message must carry the project name")
      // 经济账对照（回执实测）：同规模快照 asJson 字节数（= 旧实现嵌入量）vs 实际首条
      val nodeCount = snap.nodes.size
      val pct = math.round(1000.0 * (1.0 - bytes.toDouble / oldStyleBytes)) / 10.0
      println(s"[ctx-econ 裁定①] nodes=$nodeCount old-style snapshot embed≈$oldStyleBytes bytes → actual first message=$bytes bytes (reduction $pct%)")
      assert(bytes < oldStyleBytes / 10, s"first message must be <10% of old-style snapshot embed ($bytes vs $oldStyleBytes)")
  }

  private def mkGatedLlm: IO[GatedLlm] =
    Queue.unbounded[IO, Deferred[IO, Unit]].map(new GatedLlm(_))

end DispatcherSpawnPromptSpec
