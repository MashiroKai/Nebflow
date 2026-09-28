package nebflow.core.processor

import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, ActorSystem, Behavior, Behaviors}
import nebflow.agent.*
import nebflow.core.FileChangeTracker
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.project.{FeedbackRouter, FlowMapEventLog, FlowMapStore, NodeDef, NodeEngine, NodeLifecycle,
  OutEdge, ProjectDef, ProjectRuntime, ProjectRuntimeRegistry}
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore, WsHub}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, Message, MessageRole, StreamChunk}

import scala.concurrent.duration.*

/**
 * **硬约束② 观察夹具的共享基础设施**（`stuckrec-fix` 节点自建，2026-09-11）。
 *
 * 真引擎（`NodeEngine` + 真桥 `runWithAgent`）+ 挂死 LLM（`sendStream = IO.never`）驱动
 * 生产路径；读数全部取自**行为面**（不读实现内部）：
 *   - 账本 `TaskStuckWatcher.RecoveryLedger`（消费方 = `recoveryGate` / `loopDetectedAfterRecovery` 的输入）；
 *   - 会话存活面 `agentRegistry`（`resumedAlive`）；
 *   - 事件面 `FlowMapEventLog`（CAS 接受留痕 `resumed from stuck`）。
 *
 * 本 trait **不引用任何新增 API**（只用 `hardResumeNode` 的两参形与 `TaskStuckWatcher.scan`）——
 * 目的：同一批用例可在**改前**（基线 `9c052707`）与**改后**两个构建上逐字复跑，给出改前/改后
 * 对照读数。带回调三参形的用例单列在 [[StuckRecoveryResumeCallbackSpec]]（只在改后构建可编译）。
 */
trait StuckRecoveryFixture extends CatsEffectSuite:

  protected val threshold = 10 * 60 * 1000L

  private val originalRoot = PathUtil.dataRoot

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  /** 挂死 LLM：turn 永不完成（被恢复的会话长期 Processing ⇒ `startNode` 不返回）。 */
  private def hangingLlm: LlmHandle[IO] = new LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("hanging-llm: send not expected"))
    def sendStream(req: LlmRequest, onAttempt: Option[FallbackAttempt => IO[Unit]] = None): Stream[IO, StreamChunk] =
      Stream.eval(IO.never)

  protected final case class Rig(rt: ProjectRuntime, res: SharedResources, system: ActorSystem, ws: os.Path)

  // ── 工具 ────────────────────────────────────────────────────────────────

  protected def waitSoft(limit: FiniteDuration)(cond: IO[Boolean]): IO[Boolean] =
    val deadline = System.currentTimeMillis() + limit.toMillis
    def go: IO[Boolean] =
      cond.flatMap {
        case true => IO.pure(true)
        case false =>
          if System.currentTimeMillis() >= deadline then IO.pure(false) else IO.sleep(50.millis) >> go
      }
    go

  private def rmBounded(p: os.Path): Unit =
    (1 to 5).foreach { _ =>
      try os.remove.all(p)
      catch case _: Throwable => Thread.sleep(150)
    }

  /** flow-map 事件日志（`FlowMapEventLog.append` 写点）：兼容 workspace / dataRoot 两处候选路径。 */
  protected def readEventLog(ws: os.Path): List[String] =
    def at(base: os.Path): List[String] =
      val f = base / ".nebflow" / FlowMapEventLog.FileName
      if !os.exists(f) then Nil else os.read.lines(f).toList
    (at(ws) ++ at(PathUtil.dataRoot)).distinct

  protected def casLogged(ws: os.Path, nodeId: String): IO[Boolean] =
    IO(readEventLog(ws)).map(_.exists(l => l.contains("resumed from stuck") && l.contains(nodeId)))

  /** 被恢复的会话是否**仍在运行**（registry 里有同 sessionId 的 Flow 会话）。 */
  protected def aliveFlowSession(res: SharedResources, sid: String): IO[Boolean] =
    res.agentRegistry.get.map(_.values.exists(r => r.kind == AgentKind.Flow && r.sessionId == sid))

  protected def mkCommandSink(system: ActorSystem, name: String): IO[ActorRef[AgentCommand]] =
    def loop: Behavior[AgentCommand] = Behaviors.receiveMessage[AgentCommand](_ => IO.pure(loop))
    system.spawn(loop, name)

  /** 替身桥：收到带挂起哨兵的 `Cancelled` ⇒ 做真引擎挂起分支的同一可观测写点（会话摘除），
    * ack 时可附加 `onAck`（负控用它模拟「并发终态化」）。 */
  protected def mkSuspendAckEvt(res: SharedResources, sink: Ref[IO, List[AgentEvent]],
                                onAck: IO[Unit] = IO.unit): Behavior[AgentEvent] =
    def loop: Behavior[AgentEvent] =
      Behaviors.receiveMessage[AgentEvent] { e =>
        sink.update(_ :+ e) *> (e match
          case AgentEvent.Cancelled(sid, reason) if NodeEngine.isSuspendOutcome(reason) =>
            res.agentRegistry.update(_ - sid) *> onAck
          case _ => IO.unit) *> IO.pure(loop)
      }
    loop

  protected def seedNode(store: FlowMapStore, id: String, sid: String, status: String): IO[Unit] =
    store.mutate(s => s.copy(nodes = s.nodes + (id -> NodeDef(
      id = id, name = id, agent = "test-agent", status = status, sessionRef = Some(sid),
      task = Some("stuck-recovery ledger write-point fixture"),
      startedAt = Some(System.currentTimeMillis() - 60_000L),
      out = List(OutEdge.nebula), createdAt = System.currentTimeMillis() - 60_000L)))).void

  protected def seedTranscript(res: SharedResources, sid: String): IO[Unit] =
    res.sessionStore.saveMessagesForSession(sid, List(
      Message(role = MessageRole.User, content = Left(s"fixture transcript for $sid"))))

  /** registry 里一条**停滞形态**的 Flow 会话记录（agent 侧事件停滞轴）。 */
  protected def putStuckRecord(res: SharedResources, sid: String, rootSid: String,
                               ref: ActorRef[AgentCommand],
                               bridge: Option[ActorRef[AgentEvent]]): IO[AgentRecord] =
    val now = System.currentTimeMillis()
    val rec = AgentRecord(sessionId = sid, ref = ref, kind = AgentKind.Flow, rootSessionId = rootSid,
      startedAt = now - 30 * 60 * 1000L, status = AgentStatus.Processing,
      lastActivityMs = now - threshold - 1000L, currentToolStartedAt = 0L, supervisorRef = bridge)
    res.agentRegistry.update(_ + (sid -> rec)).as(rec)

  protected def withFixture(name: String)(body: Rig => IO[Unit]): IO[Unit] =
    IO.blocking(os.temp.dir(prefix = s"ledgerwp-$name")).flatMap { tmp =>
      val dataRoot = tmp / "data"
      for
        _ <- IO {
          PathUtil.setDataRoot(dataRoot)
          os.makeDir.all(dataRoot / "agents" / "test-agent")
          os.write.over(dataRoot / "agents" / "test-agent" / "agent.json",
            """{"name":"test-agent","description":"ledger write-point fixture","tools":[],"category":"standalone"}""")
          os.write.over(dataRoot / "agents" / "test-agent" / "system.md", "# test-agent\n")
        }
        system <- IO(ActorSystem(s"ledgerwp-$name"))
        out <- (for
          dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
          rateLimiter <- RateLimiter.create()
          tracker <- FileChangeTracker.create(os.pwd.toString)
          fileLocks <- FileLockManager.create
          thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
          modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
          voiceMuted <- Ref.of[IO, Boolean](false)
          res = SharedResources(
            llm = hangingLlm,
            dispatcher = dispatcher,
            sessionStore = SessionStore(tmp / "sessions", tmp / "tasks"),
            projectRoot = os.pwd,
            thinkingConfigRef = thinkingRef,
            rateLimiter = rateLimiter,
            fileChangeTracker = tracker,
            contextWindow = 100_000,
            agentLibrary = new AgentLibrary(tmp / "agents"),
            taskStore = FileTaskStore,
            historyArchiver = HistoryArchiver.fileSystem(tmp / "archives"),
            fileLockManager = fileLocks,
            sessionModelOverrides = modelOverrides,
            providerRegistry = null,
            healthMonitor = ProviderHealthMonitor(null),
            actorSystem = system,
            voiceMutedRef = voiceMuted)
          ws = tmp / "ws"
          _ <- IO(os.makeDir.all(ws))
          _ <- ProjectRuntimeRegistry.clear
          store <- FlowMapStore.open(s"ledgerwp-$name", ws.toString)
          engine = new NodeEngine(store, system, res, (_: io.circe.Json) => IO.unit, ws.toString,
            s"root-$name", s"ledgerwp-$name", FeedbackRouter.ModeAuto,
            (_: String, _: String, _: io.circe.Json) => IO.unit,
            notifyTriggerOverride = Some((_: String) => IO.unit))
          pd = ProjectDef(name = s"ledgerwp-$name", workspace = ws.toString,
            agentFile = (ws / "AGENTS.md").toString, createdAt = System.currentTimeMillis())
          rt = ProjectRuntime(pd, store, engine, system, res, None)
          _ <- ProjectRuntimeRegistry.register(rt)
          _ <- body(Rig(rt, res, system, ws))
        yield ()).guarantee(
          ProjectRuntimeRegistry.clear.attempt.void *>
            system.stopAll.attempt.void *>
            IO { PathUtil.setDataRoot(originalRoot); rmBounded(tmp) })
      yield out
    }

  /** L1→L2→L3 阶梯：三轮扫描触发挂起腿 + 恢复腿（与生产扫描循环同形）。 */
  protected def driveL3(fx: Rig, ledger: Ref[IO, Map[String, TaskStuckWatcher.RecoveryLedger]]): IO[Unit] =
    for
      stopCounts <- Ref.of[IO, Map[String, Int]](Map.empty)
      pending <- Ref.of[IO, List[TaskStuckWatcher.PendingL3]](Nil)
      _ <- (1 to 3).toList.traverse_(_ =>
        TaskStuckWatcher.scan(fx.res, new WsHub(), threshold, stopCounts, pending, ledger = ledger))
    yield ()
