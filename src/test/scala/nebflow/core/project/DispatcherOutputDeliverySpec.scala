package nebflow.core.project

import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.Queue
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, Behaviors}
import nebflow.agent.*
import nebflow.core.PathUtil
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, StreamChunk}

import scala.concurrent.duration.*

/**
 * 分发器会话最终输出自动投递 Nebula（2026-09-05 作者裁定「任务分发器的结果没有
 * 任何人看见，把这个接线给 nebula」）。
 *
 * 链路：分发器 turn 终态（AgentEvent.Completed）→ ProjectActor 观察桥原子 pop
 * pendingTaskTexts 队首（本 turn 触发任务全文）→ NodeEngine.deliverDispatcherOutput-
 * ToNebula 提取最终 assistant 文本（空文本不投）→ ImmediateInput(source="dispatcher",
 * eventType="completed", sender=<projectName>) 注入 Nebula 根会话。标注格式对照节点
 * 投递家族：text 头部 "[Dispatcher '<项目>' · task: <摘要≤100字符单行>]" + 全文。
 *
 * 用例：
 *  - D1 非空文本 → 根会话收到带标注投递（project 名 + 任务摘要单行截断 + 全文）
 *  - D2 空文本（纯空白 turn 输出）→ 不投（防御性判空）
 *  - D3 短窗内两次独立触发 → 两次各自投递（绕过 60s 去重窗口——分发器投递是
 *    新投递种类，同项目短窗多次触发是合法独立投递）+ 注入 turn 也带各自摘要。
 *    忙时排队断言层次：投递命令类型 = AgentCommand.ImmediateInput——产品代码
 *    AgentActor processing 态将其入 pendingImmediateInputs（AgentActor.scala
 *    "immediate-input-queued" 分支）、turn 边界串行 drain，不打断不丢；本 spec
 *    验证投递侧发出的命令契约与多次投递零丢失。
 *  - D4 占位/跳过类极简输出（单行短文本）→ 照常投递（无特殊抑制）
 */
class DispatcherOutputDeliverySpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 240.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-dispatcher-delivery"
  private val originalRoot = PathUtil.dataRoot

  // 类级：隔离 dataRoot + 预置 project-dispatcher / test-agent 定义
  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  for agent <- List("test-agent", "project-dispatcher") do
    os.makeDir.all(tempRoot / "agents" / agent)
    os.write.over(
      tempRoot / "agents" / agent / "agent.json",
      s"""{"name":"$agent","description":"dispatcher delivery test agent","tools":[],"category":"standalone"}"""
    )
    os.write.over(tempRoot / "agents" / agent / "system.md", s"# $agent\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  /** 可编程回复 LLM：按输入关键词决定回复文本；全部 turn 等 gate（精确控制 turn
    * 边界）；inputs 记录全部分发器输入。回复文本成为该 turn 的最终 assistant 文本
    * （无工具调用，单轮直答——分发器占位/直接作答形态）。 */
  private class ScriptedLlm(gates: Queue[IO, Deferred[IO, Unit]]):
    val inputs: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)
    def offerGate: IO[Deferred[IO, Unit]] = Deferred[IO, Unit].flatTap(gates.offer)
    val handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[nebflow.shared.LlmResponse] =
        IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(
          req: LlmRequest,
          onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
      ): Stream[IO, StreamChunk] =
        val text = req.messages.map(_.textContent).mkString("\n")
        val reply =
          if text.contains("EMPTY_TURN") then "   " // 纯空白：最终输出空 → 不投
          else if text.contains("PLACEHOLDER_TURN") then "ok, nothing to split — answered inline"
          else "TOPOLOGY_BUILT: 2 nodes created, wired a→b, out=Nebula"
        Stream
          .eval(inputs.update(_ :+ text))
          .flatMap(_ => Stream.eval(gates.take.flatMap(_.get)))
          .flatMap(_ => Stream(StreamChunk.TextDelta(reply), StreamChunk.Done(None, None)))

  private def mkScriptedLlm: IO[ScriptedLlm] = Queue.unbounded[IO, Deferred[IO, Unit]].map(new ScriptedLlm(_))

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

  /** 根会话 recorder：记录全部 AgentCommand（断言 ImmediateInput 契约与文本）。 */
  private def recorderBehavior(recorded: Ref[IO, List[AgentCommand]]): nebflow.actor.Behavior[AgentCommand] =
    lazy val b: nebflow.actor.Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand](msg => recorded.update(_ :+ msg).as(b))
    b

  private def mountWithRoot(
    project: String,
    ws: os.Path,
    system: ActorSystem,
    res: SharedResources,
    recorded: Ref[IO, List[AgentCommand]]
  ): IO[ProjectRuntime] =
    for
      rootRef <- system.spawn(recorderBehavior(recorded), s"rec-$project")
      _ <- res.agentRegistry.update(_ + ("nebula-root" -> AgentRecord("nebula-root", rootRef, AgentKind.Root, "nebula-root")))
      pd = ProjectDef(
        name = project,
        workspace = ws.toString,
        agentFile = (ws / "AGENTS.md").toString,
        createdAt = System.currentTimeMillis()
      )
      rt <- ProjectRuntimeRegistry.mount(pd, system, res, None, rootSessionId = "nebula-root")
    yield rt

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

  private def imms(recorded: Ref[IO, List[AgentCommand]]): IO[List[AgentCommand.ImmediateInput]] =
    recorded.get.map(_.collect { case m: AgentCommand.ImmediateInput => m })

  /** 有界轮询：等 dispatcher 会话拆干净（桥 teardown 后本轮投递已发生）。 */
  private def waitDelivered(recorded: Ref[IO, List[AgentCommand]], min: Int): IO[List[AgentCommand.ImmediateInput]] =
    def go(deadline: Long): IO[List[AgentCommand.ImmediateInput]] =
      imms(recorded).flatMap { msgs =>
        if msgs.size >= min || System.currentTimeMillis() >= deadline then IO.pure(msgs)
        else IO.sleep(50.millis) >> go(deadline)
      }
    go(System.currentTimeMillis() + 20_000L)

  // ── D1 非空文本 → 带标注投递 ────────────────────────────────────

  test("D1: dispatcher 非空最终输出 → 根会话收到 [Dispatcher '<proj>' · task: <单行摘要>] 标注投递") {
    val project = "delivery-d1"
    val ws = tempRoot / s"ws-$project"
    os.makeDir.all(ws)
    val system = ActorSystem(s"$project-${scala.util.Random.nextInt(100000)}")
    for
      llm <- mkScriptedLlm
      g1 <- llm.offerGate
      recorded <- Ref.of[IO, List[AgentCommand]](Nil)
      resources <- mkResources(system, tempRoot, llm.handle)
      rt <- mountWithRoot(project, ws, system, resources, recorded)
      actorRef = rt.actorRef.getOrElse(sys.error("ProjectActor must be spawned by mount"))
      // 多行长任务（>100 字符 + 换行）→ 摘要必须单行截断
      longTask = "做一个 login 功能：\n" + ("拆解前后端节点并接线。详细需求描述。" * 6)
      _ <- (actorRef ! ProjectActor.ProjectCommand.TriggerDispatcher(longTask, "nebula-root")).void
      _ <- waitUntil(30.seconds)(llm.inputs.get.map(_.nonEmpty)) // turn 1 gated 在飞
      _ <- g1.complete(()).void // turn 1 终态 → 桥投递 + 拆除
      msgs <- waitDelivered(recorded, min = 1)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(msgs.size, 1, s"恰好一条投递，got ${msgs.size}")
      val m = msgs.head
      // 契约：ImmediateInput = Nebula 忙 turn 时 pendingImmediateInputs 排队的消息类型
      // （AgentActor "immediate-input-queued"）——忙时排队语义自动继承
      assert(clue(m.text).startsWith(s"[Dispatcher '$project' · task: "), s"标注行格式: ${m.text.take(160)}")
      assert(clue(m.text).contains("TOPOLOGY_BUILT"), "投递必须携带分发器最终输出全文")
      assert(clue(m.text).contains("做一个 login 功能： 拆解前后端节点并接线。详细需求描述。"),
        "任务摘要必须折叠为单行（换行→空格）")
      val summary = m.text.split('\n').head
      assert(!summary.contains('\n'), "标注行必须单行")
      val expectPrefix = s"[Dispatcher '$project' · task: " // + ≤100 摘要 + "…" + "]"
      assert(clue(summary.length) <= expectPrefix.length + NodeEngine.DispatcherTaskSummaryChars + 2, // 摘要+…+尾]
        s"摘要 ≤100 字符截断+省略号，got len=${summary.length}")
      assert(summary.endsWith("…]"), "截断摘要以省略号收尾（标注行以 ] 闭合）")
      assertEquals(clue(m.source), Some(NodeEngine.DispatcherSourceMarker), "source=dispatcher（蓝气泡 Dispatcher · proj · Completed）")
      assertEquals(clue(m.eventType), Some("completed"), "eventType=completed")
      assertEquals(clue(m.sender), Some(project), "sender=project 名")
  }

  // ── D2 空文本不投 ────────────────────────────────────────────────

  test("D2: 纯空白最终输出 → 不投递（防御性判空）") {
    val project = "delivery-d2"
    val ws = tempRoot / s"ws-$project"
    os.makeDir.all(ws)
    val system = ActorSystem(s"$project-${scala.util.Random.nextInt(100000)}")
    for
      llm <- mkScriptedLlm
      g1 <- llm.offerGate
      recorded <- Ref.of[IO, List[AgentCommand]](Nil)
      resources <- mkResources(system, tempRoot, llm.handle)
      rt <- mountWithRoot(project, ws, system, resources, recorded)
      actorRef = rt.actorRef.getOrElse(sys.error("ProjectActor must be spawned by mount"))
      _ <- (actorRef ! ProjectActor.ProjectCommand.TriggerDispatcher("EMPTY_TURN 空输出任务", "nebula-root")).void
      _ <- waitUntil(30.seconds)(llm.inputs.get.map(_.nonEmpty))
      _ <- g1.complete(()) // turn 终态：空白输出 → 不投
      _ <- IO.sleep(1.second) // 给误投留窗口（若判空失效会有投递到达）
      msgs <- imms(recorded)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(msgs, Nil, s"空文本不得投递，got ${msgs.map(_.text.take(80))}")
  }

  // ── D3 短窗多次触发 → 各自投递不丢（绕过去重 + 注入 turn 带摘要）──

  test("D3: 同项目短窗两次触发（spawn + 注入各一 turn）→ 两次独立投递、各带任务摘要、零丢失") {
    val project = "delivery-d3"
    val ws = tempRoot / s"ws-$project"
    os.makeDir.all(ws)
    val system = ActorSystem(s"$project-${scala.util.Random.nextInt(100000)}")
    for
      llm <- mkScriptedLlm
      g1 <- llm.offerGate
      g2 <- llm.offerGate
      recorded <- Ref.of[IO, List[AgentCommand]](Nil)
      resources <- mkResources(system, tempRoot, llm.handle)
      rt <- mountWithRoot(project, ws, system, resources, recorded)
      actorRef = rt.actorRef.getOrElse(sys.error("ProjectActor must be spawned by mount"))
      _ <- (actorRef ! ProjectActor.ProjectCommand.TriggerDispatcher("任务甲：建拓扑", "nebula-root")).void
      _ <- waitUntil(30.seconds)(llm.inputs.get.map(_.size >= 1))
      _ <- (actorRef ! ProjectActor.ProjectCommand.TriggerDispatcher("任务乙：改接线", "nebula-root")).void // 注入排队
      _ <- IO.sleep(1.second) // 乙占位完成（串行化保证）
      _ <- g1.complete(()) // 甲 turn 终态 → 投递甲摘要
      _ <- waitUntil(30.seconds)(llm.inputs.get.map(_.size >= 2)) // 乙 turn 消费注入
      _ <- g2.complete(()) // 乙 turn 终态 → 投递乙摘要
      msgs <- waitDelivered(recorded, min = 2)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(msgs.size, 2, s"两次触发各一条投递（60s 去重窗口对分发器投递不生效），got ${msgs.size}")
      assert(clue(msgs(0).text).contains("task: 任务甲：建拓扑"), s"首条带甲摘要: ${msgs(0).text.take(120)}")
      assert(clue(msgs(1).text).contains("task: 任务乙：改接线"), s"次条带乙摘要（注入 turn 同样标注）: ${msgs(1).text.take(120)}")
      assert(msgs.forall(_.source == Some(NodeEngine.DispatcherSourceMarker)), "全部走 dispatcher 通道")
  }

  // ── D4 占位/极简输出照常投递 ────────────────────────────────────

  test("D4: 占位类单行极简输出 → 照常投递（无特殊抑制）") {
    val project = "delivery-d4"
    val ws = tempRoot / s"ws-$project"
    os.makeDir.all(ws)
    val system = ActorSystem(s"$project-${scala.util.Random.nextInt(100000)}")
    for
      llm <- mkScriptedLlm
      g1 <- llm.offerGate
      recorded <- Ref.of[IO, List[AgentCommand]](Nil)
      resources <- mkResources(system, tempRoot, llm.handle)
      rt <- mountWithRoot(project, ws, system, resources, recorded)
      actorRef = rt.actorRef.getOrElse(sys.error("ProjectActor must be spawned by mount"))
      _ <- (actorRef ! ProjectActor.ProjectCommand.TriggerDispatcher("PLACEHOLDER_TURN 检查现状", "nebula-root")).void
      _ <- waitUntil(30.seconds)(llm.inputs.get.map(_.nonEmpty))
      _ <- g1.complete(())
      msgs <- waitDelivered(recorded, min = 1)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(msgs.size, 1, "极简输出也必须投递（分发器一条短行也是有效反馈）")
      assert(clue(msgs.head.text).contains("ok, nothing to split — answered inline"))
      assert(clue(msgs.head.text).startsWith(s"[Dispatcher '$project'"), "标注照常")
  }

end DispatcherOutputDeliverySpec
