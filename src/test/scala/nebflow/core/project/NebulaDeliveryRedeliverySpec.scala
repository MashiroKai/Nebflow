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
 * V8 (2026-09-03, 结果投递链丢失向量修复): out=Nebula 投递记账 + 重启重投扫描。
 *
 * 丢失形态（审计 V8）：deliverOut 的 Nebula 分支不写 deliveredTo（无记账），
 * deliverToNebula 是 `ref ! ImmediateInput` 即忘；根 ref 不存在仅 warn 丢弃；
 * completeNode 落盘与 Nebula 消费之间崩溃 → 结果滞留 map 无人再投（无启动
 * 重投/对账扫描）。
 *
 * 修复：NodeDef.nebulaDeliveredAt 记账（与 in barrier 的 deliveredTo 判定完全
 * 分离，barrier 语义零改动）+ NodeEngine.redeliverUnconsumedNebulaResults
 * （挂载即扫 + TtlTick 周期扫，覆盖活动区+归档区）+ 根 ref 缺失时不丢弃
 * （不记账滞留，根可用后补投）。
 *
 * 用例：
 *  - R1 GREEN 崩溃窗口补投：completed + out=Nebula + 未记账 → 扫描补投 + 记账
 *  - R2 幂等：再扫不重投（记账去重）
 *  - R3 根 ref 缺失：扫描静默跳过（滞留不丢弃，不误标记）
 *  - R4 failed 节点同样补投
 *  - R5 改接手动投递（deliverOutTo）投递 + 刷账（人工意图通道保持）
 *  - 红基线见报告 §V8（变异 = deliverToNebula 去记账 + 扫描桩返回 0 → R1 红）
 */
class NebulaDeliveryRedeliverySpec extends FunSuite:

  private val originalRoot = PathUtil.dataRoot

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    nebflow.core.LlmLogWriter.setEnabled(true)

  nebflow.core.LlmLogWriter.setEnabled(false)

  private def completedNode(id: String, status: String, result: String): NodeDef =
    NodeDef(
      id = id,
      name = s"node-$id",
      agent = "worker",
      out = Some("Nebula"),
      status = status,
      result = Some(result),
      createdAt = System.currentTimeMillis() - 60_000L
    )

  private def withFixture(name: String)(body: (FlowMapStore, NodeEngine, SharedResources, ActorSystem, Ref[IO, List[AgentCommand]], String, nebflow.actor.ActorRef[AgentCommand]) => Unit): Unit =
    val tmp = os.temp.dir(prefix = s"v8-$name")
    PathUtil.setDataRoot(tmp / "data")
    val system = ActorSystem(s"v8-$name")
    try
      val io = for
        workspace <- IO(os.makeDir.all(tmp / "ws"))
        store <- FlowMapStore.open("v8proj", workspace.toString)
        dispatcher <- cats.effect.std.Dispatcher.parallel[IO].allocated.map(_._1)
        rateLimiter <- RateLimiter.create()
        tracker <- FileChangeTracker.create(os.pwd.toString)
        fileLocks <- FileLockManager.create
        thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
        modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
        voiceMuted <- Ref.of[IO, Boolean](false)
        llm = new nebflow.shared.LlmHandle[IO]:
          def send(req: nebflow.shared.LlmRequest): IO[nebflow.shared.LlmResponse] =
            IO.raiseError(new RuntimeException("not expected"))
          def sendStream(req: nebflow.shared.LlmRequest, onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None) =
            fs2.Stream(nebflow.shared.StreamChunk.TextDelta("ok"), nebflow.shared.StreamChunk.Done(None, None))
        resources = SharedResources(
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
          historyArchiver = HistoryArchiver.fileSystem(tmp / "archives"),
          fileLockManager = fileLocks,
          sessionModelOverrides = modelOverrides,
          providerRegistry = null,
          healthMonitor = ProviderHealthMonitor(null),
          actorSystem = system,
          subAgentTaskStore = new SubAgentTaskStore(tmp / "subagent-tasks"),
          voiceMutedRef = voiceMuted
        )
        // 根会话记录器：收 ImmediateInput 并记账（fire-and-forget 投递的真实终点）。
        recorded <- Ref.of[IO, List[AgentCommand]](Nil)
        rootSid = "v8-root-sid"
        rootRef <- system.spawn(recorderBehavior(recorded), "v8-root-recorder")
        engine = new NodeEngine(
          store,
          system,
          resources,
          _ => IO.unit,
          workspace.toString,
          rootSid,
          "v8proj",
          FeedbackRouter.ModeAuto,
          (_, _, _) => IO.unit
        )
      yield (store, engine, resources, recorded, rootSid, rootRef)
      val (store, engine, resources, recorded, rootSid, rootRef) = io.unsafeRunSync()
      body(store, engine, resources, system, recorded, rootSid, rootRef)
    finally
      PathUtil.setDataRoot(originalRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)

  private def registerRoot(resources: SharedResources, rootSid: String, rootRef: nebflow.actor.ActorRef[AgentCommand]): IO[Unit] =
    resources.agentRegistry.update(_ + (rootSid -> AgentRecord(rootSid, rootRef, AgentKind.Root, rootSid)))

  /** 自引用 recorder behavior：收到的 AgentCommand 全部记账。 */
  private def recorderBehavior(recorded: Ref[IO, List[AgentCommand]]): nebflow.actor.Behavior[AgentCommand] =
    lazy val b: nebflow.actor.Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand](msg => recorded.update(_ :+ msg).as(b))
    b

  test("R1 GREEN: crash-window result (completed, unmarked) is redelivered to Nebula and marked") {
    withFixture("r1") { (store, engine, resources, system, recorded, rootSid, rootRef) =>
      val io = for
        _ <- registerRoot(resources, rootSid, rootRef)
        _ <- store.mutate(s => s.copy(nodes = s.nodes + ("n-done" -> completedNode("n-done", NodeLifecycle.Completed, "V8_CRASH_WINDOW_RESULT"))))
        n1 <- engine.redeliverUnconsumedNebulaResults()
        msgs <- recorded.get
        node <- store.getNode("n-done")
      yield (n1, msgs, node)
      val (n1, msgs, node) = io.unsafeRunSync()
      assertEquals(clue(n1), 1, "scan must report one redelivery")
      val imms = msgs.collect { case m: AgentCommand.ImmediateInput => m }
      assertEquals(clue(imms.size), 1, "exactly one ImmediateInput to the root")
      assert(clue(imms.head.text).contains("V8_CRASH_WINDOW_RESULT"), s"result text must be delivered: ${imms.head.text}")
      assert(imms.head.source.contains("node"), "node-source bubble semantics preserved")
      assert(clue(node.flatMap(_.nebulaDeliveredAt)).isDefined, "delivery must be recorded (nebulaDeliveredAt)")
    }
  }

  test("R2 idempotent: second scan does not redeliver (marker dedup)") {
    withFixture("r2") { (store, engine, resources, system, recorded, rootSid, rootRef) =>
      val io = for
        _ <- registerRoot(resources, rootSid, rootRef)
        _ <- store.mutate(s => s.copy(nodes = s.nodes + ("n-a" -> completedNode("n-a", NodeLifecycle.Completed, "r2 result"))))
        _ <- engine.redeliverUnconsumedNebulaResults()
        countAfterFirst <- recorded.get.map(_.size)
        n2 <- engine.redeliverUnconsumedNebulaResults()
        _ <- IO.sleep(200.millis)
        countAfterSecond <- recorded.get.map(_.size)
      yield (countAfterFirst, n2, countAfterSecond)
      val (countAfterFirst, n2, countAfterSecond) = io.unsafeRunSync()
      assertEquals(clue(countAfterFirst), 1)
      assertEquals(clue(n2), 0, "second scan must find nothing undelivered")
      assertEquals(clue(countAfterSecond), 1, "no duplicate delivery")
    }
  }

  test("R3 missing root ref: scan parks silently (no discard, no false marking)") {
    withFixture("r3") { (store, engine, resources, system, recorded, rootSid, rootRef) =>
      val io = for
        // Root NOT registered (lazy spawn — the real early-boot shape).
        _ <- store.mutate(s => s.copy(nodes = s.nodes + ("n-park" -> completedNode("n-park", NodeLifecycle.Completed, "r3 parked"))))
        n <- engine.redeliverUnconsumedNebulaResults()
        msgs <- recorded.get
        node <- store.getNode("n-park")
      yield (n, msgs, node)
      val (n, msgs, node) = io.unsafeRunSync()
      assertEquals(clue(n), 0, "scan skips while the root session is not up")
      assert(clue(msgs).isEmpty, "nothing delivered while parked")
      assert(clue(node.flatMap(_.nebulaDeliveredAt)).isEmpty, "parked result must NOT be marked (would lose it)")
      assertEquals(clue(node.flatMap(_.result)), Some("r3 parked"), "result retained in the map")
    }
  }

  test("R4 failed node results are redelivered too") {
    withFixture("r4") { (store, engine, resources, system, recorded, rootSid, rootRef) =>
      val io = for
        _ <- registerRoot(resources, rootSid, rootRef)
        _ <- store.mutate(s => s.copy(nodes = s.nodes + ("n-fail" -> completedNode("n-fail", NodeLifecycle.Failed, "r4 failure detail"))))
        n <- engine.redeliverUnconsumedNebulaResults()
        msgs <- recorded.get
      yield (n, msgs)
      val (n, msgs) = io.unsafeRunSync()
      assertEquals(clue(n), 1)
      val imms = msgs.collect { case m: AgentCommand.ImmediateInput => m }
      assert(clue(imms.head.text).contains("r4 failure detail"))
      assert(clue(imms.head.eventType).contains("failed"), "failed status rides the bubble header")
    }
  }

  test("R5 manual edit-redelivery (deliverOutTo) delivers and refreshes the ledger") {
    withFixture("r5") { (store, engine, resources, system, recorded, rootSid, rootRef) =>
      val io = for
        _ <- registerRoot(resources, rootSid, rootRef)
        node = completedNode("n-manual", NodeLifecycle.Completed, "r5 manual")
        _ <- store.mutate(s => s.copy(nodes = s.nodes + ("n-manual" -> node.copy(nebulaDeliveredAt = Some(System.currentTimeMillis() - 3600_000L)))))
        // 人工改接投递：已记账也必须再投（用户显式意图——审计确认的既有恢复通道）。
        _ <- engine.deliverOutTo(node, "Nebula", "r5 manual")
        msgs <- recorded.get
        fresh <- store.getNode("n-manual")
      yield (msgs, fresh)
      val (msgs, fresh) = io.unsafeRunSync()
      val imms = msgs.collect { case m: AgentCommand.ImmediateInput => m }
      assertEquals(clue(imms.size), 1, "manual redelivery passes even though a ledger entry existed")
      assert(clue(fresh.flatMap(_.nebulaDeliveredAt)).exists(_ >= System.currentTimeMillis() - 60_000L), "ledger refreshed")
    }
  }

end NebulaDeliveryRedeliverySpec
