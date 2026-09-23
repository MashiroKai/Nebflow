package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.circe.Json
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

/**
 * 分发器会话空闲保活窗验收（**令 3 分发器生命周期**，2026-09-12 作者 14:14 原话
 * 「30 mins 无新任务才销毁为新会话，保证连续任务派发的连贯性。目前是每次都是新的
 * 实例」；设计件 `20260912_142338_dispatcher-lifecycle-design__chain-n-718da6b5.md`
 * §2/§3）。
 *
 * **等效性依据（加速验证，硬性声明）**：本 spec 用 `mount(dispatcherIdleWindowMs =
 * Some(2000))` + `ttlCheckIntervalSec = 1` 把窗口压到 2 s、节拍压到 1 s。被压的
 * **只有两个数值常量**，被验的**代码路径逐行未变**：同一 `dispatcherBridge` 的
 * `Completed` 裁决分支、同一 `sweepIdleDispatchers` 扫描腿、同一 `ProjectActor.ttlScanner`
 * 驱动形态（生产 = GatewayMain 的 30 s `TtlTick` 全项目广播，本 spec 直接
 * `ProjectActor.ttlScanner(1.second)` 起在同一周期驱动函数上）、同一 `teardown`
 * 动作集、同一条 `dispatcher-idle-expired` 审计写入点。时间量纲关系（窗口 > 节拍、
 * 到期检出落在 [窗口, 窗口+节拍]）在压缩尺度上保持 ⇒ 判据 #3 可等效判定。
 * **不可等效的部分**：真实 30 min / 30 s 的绝对时长本身（本 spec 不证其绝对值为
 * 30 min——该值由 `Defaults.DispatcherIdleWindowMs` 常量与 `GatewayMain:614` 的
 * 30 s 节拍分别读盘核验）。
 *
 * 覆盖判据（对应任务书 ④）：
 * ① 派发一次 ⇒ 会话存活（保活，不再于 turn 终态即拆）；
 * ② 窗口内再派发 ⇒ **复用同一会话**（id 不变 **+ 上下文连贯性可证**：第二轮 LLM
 *    请求里出现第一轮任务全文）；
 * ③ 无新任务达阈值 ⇒ 销毁（registry 清 + `dispatcher-idle-expired` 事件）；
 *    阈值内（节拍已跑过但未到窗）⇒ 不触发销毁；
 * ④ 销毁后下一次派发 ⇒ 新 sessionId（新实例）；
 * ⑤ 零回归面：`TTL 回退开关`（窗口 ≤ 0）⇒ 逐字回到 turn 级即拆；`Failed/Cancelled`
 *    即时拆除分支不受影响（由 `ProjectDispatcherLifecycleSpec` 覆盖）。
 */
class DispatcherIdleWindowSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-dispatcher-idle-window"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "project-dispatcher")

  os.write.over(
    tempRoot / "agents" / "project-dispatcher" / "agent.json",
    """{"name":"project-dispatcher","description":"idle-window test dispatcher","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "project-dispatcher" / "system.md", "# project-dispatcher\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  /**
   * 记录型 LLM：每次分发器 turn 记录「请求全文」（用于上下文连贯性断言）+ 计数
   * 已结束的 stream（用于等待 turn 终态）。文本 delta 即收尾（零工具调用）。
   */
  private class RecordingLlm:
    val inputs: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)
    val streamsDone: Ref[IO, Int] = Ref.unsafe[IO, Int](0)

    val handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(
        req: LlmRequest,
        onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
      ): Stream[IO, StreamChunk] =
        val text = req.messages.map(_.textContent).mkString("\n")
        Stream.eval(inputs.update(_ :+ text)).flatMap { _ =>
          Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None)) ++
            Stream.eval(streamsDone.update(_ + 1)).drain
        }

  end RecordingLlm

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

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 25.millis)(
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

  private def eventLines(ws: os.Path): IO[List[String]] =
    val f = ws / ".nebflow" / FlowMapEventLog.FileName
    IO.blocking(if os.exists(f) then os.read.lines(f).toList else Nil)

  private def mount(
    name: String,
    ws: os.Path,
    system: ActorSystem,
    res: SharedResources,
    idleWindowMs: Option[Long]
  ): IO[ProjectRuntime] =
    val pd = ProjectDef(
      name = name,
      workspace = ws.toString,
      agentFile = (ws / "AGENTS.md").toString,
      createdAt = System.currentTimeMillis()
    )
    ProjectRuntimeRegistry.mount(
      pd,
      system,
      res,
      Some((_: Json) => IO.unit),
      rootSessionId = "nebula-root",
      // 节拍压到 1 s（生产 = GatewayMain 的 30 s 广播，同一 ttlScanner 函数）
      ttlCheckIntervalSec = 1,
      dispatcherIdleWindowMs = idleWindowMs
    )
  end mount

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  /** 起真实扫描节拍（= `GatewayMain` boot 链起的同一个 `ProjectActor.ttlScanner`）。 */
  private def withScanner[A](body: IO[A]): IO[A] =
    ProjectActor.ttlScanner(1.second).background.use(_ => body)

  // ── ① / ③-阈值内 / ② ────────────────────────────────────────────

  test("① 派发一次 ⇒ 会话存活（turn 终态不再即拆）；② 窗口内再派发 ⇒ 复用同一 id + 上下文连贯") {
    val ws = tempRoot / "ws-keepalive"
    os.makeDir.all(ws)
    val system = ActorSystem(s"idle-keepalive-${scala.util.Random.nextInt(100000)}")
    withScanner {
      for
        llm <- IO.pure(new RecordingLlm)
        resources <- mkResources(system, tempRoot, llm.handle)
        // 窗口 2 s，节拍 1 s ⇒ 有效窗 [2 s, 3 s]
        rt <- mount("idle-keepalive", ws, system, resources, Some(2000L))
        actorRef = rt.actorRef.getOrElse(sys.error("ProjectActor must be spawned by mount"))
        _ <- (actorRef ! ProjectActor.ProjectCommand.TriggerDispatcher("任务甲-交付物X", "nebula-root")).void
        _ <- waitUntil(20.seconds)(dispatcherEntries(resources).map(_.nonEmpty))
        sid1 <- dispatcherEntries(resources).map(_.head)
        _ <- waitUntil(20.seconds)(llm.streamsDone.get.map(_ >= 1))
        _ <- IO.sleep(500.millis) // 桥处理 Completed 的窗口
        // ① turn 终态后会话必须**存活**（改前：此处已 teardown ⇒ entries 空）
        alive1 <- dispatcherEntries(resources)
        // ③-阈值内：已跑过 1 整拍以上但未到 2 s 窗口 ⇒ 仍不销毁
        _ <- IO.sleep(1200.millis)
        alive2 <- dispatcherEntries(resources)
        // ② 窗口内再派发 ⇒ 复用同一会话（id 不变）
        _ <- (actorRef ! ProjectActor.ProjectCommand.TriggerDispatcher("任务乙-交付物Y", "nebula-root")).void
        _ <- waitUntil(20.seconds)(llm.inputs.get.map(_.size >= 2))
        alive3 <- dispatcherEntries(resources)
        ins <- llm.inputs.get
        _ <- waitUntil(20.seconds)(llm.streamsDone.get.map(_ >= 2))
        _ <- IO.sleep(400.millis)
        // 仍存活（窗口内第二次派发同样重置/续用；总量断言落在 ④ 的对照 test 里）
        alive4 <- dispatcherEntries(resources)
        _ <- system.stopAll.handleErrorWith(_ => IO.unit)
      yield
        assertEquals(alive1, List(sid1), "① turn 终态后会话必须保活（同 id），不得即拆")
        assertEquals(alive2, List(sid1), "③-阈值内（已过 1 拍 + 1.2s < 2s 窗）不得触发销毁")
        assertEquals(alive3, List(sid1), "② 窗口内再派发必须复用同一会话（id 不变）")
        assert(alive4.contains(sid1), s"② 第二轮消费完成后仍应保活，got $alive4")
        assertEquals(ins.size, 2, "恰好两个分发器 turn（第二轮为注入形态，非 spawn）")
        assert(
          ins(1).contains("任务甲-交付物X"),
          "上下文连贯性：第二轮请求必须带上第一轮任务的全文（同会话历史复用）——got: " + ins(1).take(200)
        )
        assert(ins(1).contains("任务乙-交付物Y"), "第二轮请求必须携带本轮注入文本")
        assert(ins(1).contains("New task arrived"), "第二轮必须是注入形态（既有标注行），非 spawn prompt")
    }
  }

  // ── ③-到期销毁 + ④-新实例 ───────────────────────────────────────

  test("③ 无新任务达阈值 ⇒ 销毁（registry 清 + dispatcher-idle-expired 事件）；④ 下次派发 ⇒ 新 sessionId") {
    val ws = tempRoot / "ws-expire"
    os.makeDir.all(ws)
    val system = ActorSystem(s"idle-expire-${scala.util.Random.nextInt(100000)}")
    withScanner {
      for
        llm <- IO.pure(new RecordingLlm)
        resources <- mkResources(system, tempRoot, llm.handle)
        rt <- mount("idle-expire", ws, system, resources, Some(2000L))
        actorRef = rt.actorRef.getOrElse(sys.error("ProjectActor must be spawned by mount"))
        _ <- (actorRef ! ProjectActor.ProjectCommand.TriggerDispatcher("任务甲", "nebula-root")).void
        _ <- waitUntil(20.seconds)(dispatcherEntries(resources).map(_.nonEmpty))
        sid1 <- dispatcherEntries(resources).map(_.head)
        _ <- waitUntil(20.seconds)(llm.streamsDone.get.map(_ >= 1))
        _ <- IO.sleep(500.millis)
        kept <- dispatcherEntries(resources)
        // 等空闲窗到期（2 s 窗 + ≤1 s 节拍 ⇒ 5 s 内必定检出）
        _ <- waitUntil(20.seconds)(dispatcherEntries(resources).map(_.isEmpty))
        // 审计事件与拆除同点但异步追加（IO.blocking）——单独等它落盘，避免竞态
        _ <- waitUntil(10.seconds)(
          eventLines(ws).map(_.exists(l => l.contains("\"type\":\"dispatcher-idle-expired\"") && l.contains(sid1)))
        )
        events <- eventLines(ws)
        // ④ 到期销毁后的新派发 = 新实例（新 id）
        _ <- (actorRef ! ProjectActor.ProjectCommand.TriggerDispatcher("任务乙", "nebula-root")).void
        _ <- waitUntil(20.seconds)(dispatcherEntries(resources).map(_.nonEmpty))
        sid2 <- dispatcherEntries(resources).map(_.head)
        _ <- waitUntil(20.seconds)(llm.inputs.get.map(_.size >= 2))
        ins <- llm.inputs.get
        _ <- system.stopAll.handleErrorWith(_ => IO.unit)
      yield
        assertEquals(kept, List(sid1), "到期前会话必须仍存活")
        assert(sid2 != sid1, s"到期销毁后的新派发必须是新实例（旧=$sid1 新=$sid2）")
        assert(
          events.exists(l => l.contains("\"type\":\"dispatcher-idle-expired\"") && l.contains(sid1)),
          s"必须留一条携带旧会话 id 的 dispatcher-idle-expired 审计事件，got:\n${events.mkString("\n")}"
        )
        // ④ 的新会话是 spawn 形态（fresh prompt），不复用旧上下文
        assertEquals(ins.size, 2, "两轮：旧会话一轮 + 新会话一轮")
        assert(!ins(1).contains("New task arrived"), "新实例走 spawn 路径（fresh prompt），非注入形态")
        assert(!ins(1).contains("任务甲"), "新实例不得携带旧会话上下文（确为新建）")
    }
  }

  // ── ⑤ 回退开关：窗口 ≤ 0 = 逐字回到 turn 级即拆 ───────────────────

  test("⑤ 回退开关：idleWindowMs ≤ 0 ⇒ 逐字回到 turn 级即拆（改前行为零回归）") {
    val ws = tempRoot / "ws-rollback"
    os.makeDir.all(ws)
    val system = ActorSystem(s"idle-rollback-${scala.util.Random.nextInt(100000)}")
    withScanner {
      for
        llm <- IO.pure(new RecordingLlm)
        resources <- mkResources(system, tempRoot, llm.handle)
        rt <- mount("idle-rollback", ws, system, resources, Some(0L))
        actorRef = rt.actorRef.getOrElse(sys.error("ProjectActor must be spawned by mount"))
        _ <- (actorRef ! ProjectActor.ProjectCommand.TriggerDispatcher("任务甲", "nebula-root")).void
        // turn 终态后**立即**拆（不经空闲窗）——改前语义；保活开启时此处必为红
        _ <- waitUntil(20.seconds)(llm.streamsDone.get.map(_ >= 1))
        _ <- waitUntil(20.seconds)(dispatcherEntries(resources).map(_.isEmpty))
        // 下一次派发 ⇒ 新实例（新 id，spawn 形态）
        _ <- (actorRef ! ProjectActor.ProjectCommand.TriggerDispatcher("任务乙", "nebula-root")).void
        _ <- waitUntil(20.seconds)(llm.streamsDone.get.map(_ >= 2))
        _ <- waitUntil(20.seconds)(dispatcherEntries(resources).map(_.isEmpty))
        ins <- llm.inputs.get
        events <- eventLines(ws)
        _ <- system.stopAll.handleErrorWith(_ => IO.unit)
      yield
        assertEquals(ins.size, 2, "回退档：两次派发 = 两个独立 turn（无任何注入复用）")
        assert(!ins(1).contains("New task arrived"), "回退档第二次必须是 spawn 形态（新实例）")
        assert(!ins(1).contains("任务甲"), "回退档新实例不得携带上一实例的上下文")
        assert(
          !events.exists(_.contains("dispatcher-idle-expired")),
          "关闭保活时不得写空闲到期事件（拆的是 turn 终态，不是空闲窗）"
        )
    }
  }

end DispatcherIdleWindowSpec
