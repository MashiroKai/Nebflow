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

  // ── R7-b（2026-09-12 R2「一个 Mail 统一」批）：桥收敛 ⇒ 零自动投递 ──────
  //
  // 旧行为（本文件 2026-09-05 起验证的 D1–D4）已被**取代**：`ProjectActor.
  // dispatcherBridge` 不再每 turn 无条件把最终 assistant 文本投给 root，
  // `NodeEngine.deliverDispatcherOutputToNebula` / `DispatcherSourceMarker` /
  // `DispatcherTaskSummaryChars` 同批删净（D-4）。root 注入面 100% 由显式载体
  // 驱动（分发器自己 `Mail(address="Nebula", …)`）。本 spec 断言**逆命题**：
  // 分发器 turn 终态后，root 会话收到的注入恒为 0——这正是 R7-b 的验收判据，
  // 也是「分发器以为会自动投递」这类静默失败的最佳哨兵。

  test("R7-b-1: dispatcher turn 终态 → 根会话零自动投递（旧 D1 逆命题；含长输出与长任务）") {
    val project = "delivery-r7b1"
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
      longTask = "做一个 login 功能：\n" + ("拆解前后端节点并接线。详细需求描述。" * 6)
      _ <- (actorRef ! ProjectActor.ProjectCommand.TriggerDispatcher(longTask, "nebula-root")).void
      _ <- waitUntil(30.seconds)(llm.inputs.get.map(_.nonEmpty))
      _ <- g1.complete(()).void
      // 给误投留充分窗口（旧实现此处必有 1 条 ImmediateInput 到达）
      _ <- IO.sleep(2.seconds)
      msgs <- imms(recorded)
      _ <- waitUntil(30.seconds)(resources.agentRegistry.get.map(_.keys.forall(!_.startsWith(ProjectActor.DispatcherSessionPrefix))))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(msgs, Nil, s"R7-b：桥收敛后不得有任何自动投递，got ${msgs.map(_.text.take(120))}")
  }

  test("R7-b-2: 分发器输出含占位/极简文本时同样零自动投递；桥的拆除职责保留") {
    val project = "delivery-r7b2"
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
      _ <- IO.sleep(2.seconds)
      msgs <- imms(recorded)
      // 桥的非投递职责仍在：Completed 后 activeRef 清空 → 会话注销（拆除裁决未受影响）
      gone <- waitUntil(30.seconds)(resources.agentRegistry.get.map(_.keys.forall(!_.startsWith(ProjectActor.DispatcherSessionPrefix)))).attempt
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(msgs, Nil, "极简输出同样零自动投递（旧 D4 逆命题）")
      assert(gone.isRight, "R7-b 只摘投递，不摘拆除：Completed 后分发器会话仍应注销")
  }

end DispatcherOutputDeliverySpec
