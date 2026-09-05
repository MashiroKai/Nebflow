package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite
import nebflow.actor.{ActorSystem, Behaviors}
import nebflow.agent.*
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.FileChangeTracker
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}

import scala.concurrent.duration.*

/**
 * 缺口1（2026-09-04 投递可靠性批次）：会话死亡 → 节点终态化联动。
 *
 * 假尸根因（09-04 晨立卡实证②：09-03 夜 02:19 两个 node 会话静默死亡，节点滞留
 * running）：NodeEngine.runWithAgent 的 bridge 只等 AgentEvent.{Completed,Failed,
 * Cancelled} 三终态事件——会话无终态事件死亡（processing.onError 静默回 idle 后被
 * Stop、裸崩溃退出 loop、外部 fiber cancel——AgentControl 无记录、主日志零留痕的
 * 形态）时 deferred 永不完成，engine fiber 永久挂在 IO.race 上 → 节点滞留 running。
 *
 * 修复：bridge ctx.watch(agentRef)（EphemeralAgentRunner 先例同款）——Terminated
 * 兜底完成 deferred（failed 语义）→ failNode 既有链终态化 + WARN
 * （sessionId+nodeId+原因）+ deliverFailed。取消路径（NodeCancel→cancelSig、
 * AgentControl/TaskStuckWatcher giveUp→AgentEvent.Cancelled）不经此路径，语义不变。
 *
 * 用例：
 *  - D1 GREEN 会话静默死亡（fiber cancel，无终态事件）→ 节点 failed + 结果含
 *    sessionId+原因 + failed 投递达根会话 + registry 清理
 *  - D2 对照：NodeCancel 正常取消路径仍走 cancelled（语义零改动）
 *  - 变异红基线：去掉 bridge watch/onSignal（Terminated 无人处理）→ D1 超时红
 */
class NodeSessionDeathFinalizeSpec extends FunSuite:

  private val originalRoot = PathUtil.dataRoot

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    nebflow.core.LlmLogWriter.setEnabled(true)

  nebflow.core.LlmLogWriter.setEnabled(false)

  /** 挂死 LLM：turn 永不完成（模拟会话运行中悬挂——死亡发生在进程存活期间）。 */
  private def hangingLlm = new nebflow.shared.LlmHandle[IO]:
    def send(req: nebflow.shared.LlmRequest): IO[nebflow.shared.LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected"))
    def sendStream(req: nebflow.shared.LlmRequest, onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None) =
      fs2.Stream.eval(IO.never)

  private def withFixture(name: String)(body: (FlowMapStore, NodeEngine, SharedResources, ActorSystem, Ref[IO, List[AgentCommand]], String) => Unit): Unit =
    val tmp = os.temp.dir(prefix = s"nodedeath-$name")
    PathUtil.setDataRoot(tmp / "data")
    // EntityLoader.agentsDir = PathUtil.dataRoot/agents —— agent 库种在 dataRoot 下
    os.makeDir.all(tmp / "data" / "agents" / "test-agent")
    os.write.over(tmp / "data" / "agents" / "test-agent" / "agent.json",
      """{"name":"test-agent","description":"session-death spec agent","tools":[],"category":"standalone"}""")
    os.write.over(tmp / "data" / "agents" / "test-agent" / "system.md", "# test-agent\n")
    val system = ActorSystem(s"nodedeath-$name")
    try
      val io = for
        workspace <- IO(os.makeDir.all(tmp / "ws"))
        store <- FlowMapStore.open("ddproj", workspace.toString)
        dispatcher <- cats.effect.std.Dispatcher.parallel[IO].allocated.map(_._1)
        rateLimiter <- RateLimiter.create()
        tracker <- FileChangeTracker.create(os.pwd.toString)
        fileLocks <- FileLockManager.create
        thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
        modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
        voiceMuted <- Ref.of[IO, Boolean](false)
        resources = SharedResources(
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
          subAgentTaskStore = new SubAgentTaskStore(tmp / "subagent-tasks"),
          voiceMutedRef = voiceMuted
        )
        recorded <- Ref.of[IO, List[AgentCommand]](Nil)
        rootSid = "dd-root-sid"
        rootRef <- system.spawn(recorderBehavior(recorded), "dd-root-recorder")
        engine = new NodeEngine(
          store,
          system,
          resources,
          _ => IO.unit,
          workspace.toString,
          rootSid,
          "ddproj",
          FeedbackRouter.ModeAuto,
          (_, _, _) => IO.unit
        )
      yield (store, engine, resources, recorded, rootSid, rootRef)
      val (store, engine, resources, recorded, rootSid, rootRef) = io.unsafeRunSync()
      // 根会话注册（failed 投递的断言终点）
      resources.agentRegistry
        .update(_ + (rootSid -> AgentRecord(rootSid, rootRef, AgentKind.Root, rootSid)))
        .unsafeRunSync()
      body(store, engine, resources, system, recorded, rootSid)
    finally
      PathUtil.setDataRoot(originalRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)

  private def recorderBehavior(recorded: Ref[IO, List[AgentCommand]]): nebflow.actor.Behavior[AgentCommand] =
    lazy val b: nebflow.actor.Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand](msg => recorded.update(_ :+ msg).as(b))
    b

  private def seedRunningCandidate(store: FlowMapStore, id: String, name: String, task: String): IO[Unit] =
    store.mutate(s => s.copy(nodes = s.nodes + (id -> NodeDef(
      id = id, name = name, agent = "test-agent", task = Some(task),
      out = Some("Nebula"), status = NodeLifecycle.Wiring, createdAt = System.currentTimeMillis())))).void

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 50.millis)(cond: IO[Boolean]): IO[Unit] =
    def go(deadline: Long): IO[Unit] = cond.flatMap {
      case true => IO.unit
      case _ if System.currentTimeMillis() >= deadline =>
        IO.raiseError(new AssertionError("waitUntil: condition not met in time"))
      case false => IO.sleep(every) >> go(deadline)
    }
    go(System.currentTimeMillis() + timeout.toMillis)

  private def nodeSession(resources: SharedResources): IO[Option[AgentRecord]] =
    resources.agentRegistry.get.map(_.values.find(r => r.kind == AgentKind.Flow && r.sessionId.startsWith("node-")))

  test("D1 GREEN: silent session death (no terminal event) finalizes node as failed with WARN trail") {
    withFixture("d1") { (store, engine, resources, system, recorded, rootSid) =>
      val io = for
        _ <- seedRunningCandidate(store, "n-dead", "node-doomed", "long running task that will never finish")
        _ <- engine.startNode("n-dead").start
        // 等节点翻 running（spawn 完成、race 挂起——假尸窗口形成）
        _ <- waitUntil(15.seconds)(store.getNode("n-dead").map(_.exists(_.status == NodeLifecycle.Running)))
        // 等会话在 agentRegistry 登记（Running 翻转先于 registry 登记——竞态防）
        _ <- waitUntil(15.seconds)(nodeSession(resources).map(_.isDefined))
        rec0 <- nodeSession(resources)
        _ <- IO.println(s"[spec] stopping node session: ${rec0.map(_.sessionId)}")
        // 静默死亡：外部 cancel 会话 fiber（无任何终态事件——AgentControl 无记录、
        // 零日志留痕的 02:19 形态；ActorSystem.stop = fiber cancel → loop guarantee
        // → death watch Terminated → bridge 兜底）
        _ <- rec0.traverse_(rec => system.stop(rec.ref))
        // 修复生效点：Terminated → failNode（节点不滞留 running）
        _ <- waitUntil(15.seconds)(store.getNode("n-dead").map(_.exists(_.status == NodeLifecycle.Failed)))
        node <- store.getNode("n-dead")
        msgs <- recorded.get
        reg <- nodeSession(resources)
      yield (node, msgs, reg)
      val (node, msgs, reg) = io.unsafeRunSync()
      val n = node.getOrElse(fail("node must exist"))
      assertEquals(clue(n.status), NodeLifecycle.Failed, "dead session must NOT leave node running")
      val result = n.result.getOrElse("")
      assert(clue(result).contains("terminated without a terminal event"), s"failure reason must be recorded: $result")
      assert(clue(result).contains("node-"), s"sessionId must ride the failure reason: $result")
      assert(clue(reg).isEmpty, "agent registry entry must be cleaned up")
      val imms = msgs.collect { case m: AgentCommand.ImmediateInput => m }
      assert(clue(imms.nonEmpty), "failed delivery must reach the root session (deliverFailed)")
      assert(clue(imms.head.text).contains("[Node 'node-doomed' failed]"), s"failed envelope: ${imms.head.text}")
      assert(clue(imms.head.eventType).contains("failed"), "failed status rides the bubble header")
    }
  }

  test("D2 control: NodeCancel cancel path still finalizes as cancelled (semantics unchanged)") {
    withFixture("d2") { (store, engine, resources, system, recorded, rootSid) =>
      val io = for
        _ <- seedRunningCandidate(store, "n-cancel", "node-cancellable", "long running task")
        _ <- engine.startNode("n-cancel").start
        _ <- waitUntil(15.seconds)(store.getNode("n-cancel").map(_.exists(_.status == NodeLifecycle.Running)))
        _ <- engine.cancelNodeById("n-cancel")
        _ <- waitUntil(15.seconds)(store.getNode("n-cancel").map(_.exists(_.status == NodeLifecycle.Cancelled)))
        node <- store.getNode("n-cancel")
      yield node
      val node = io.unsafeRunSync()
      assertEquals(clue(node.map(_.status)), Some(NodeLifecycle.Cancelled), "cancel semantics untouched by gap-1 fix")
    }
  }

end NodeSessionDeathFinalizeSpec
