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
 * 缺口4（2026-09-04 投递可靠性批次）：同 (nodeId, status) 短窗口（60s）重复
 * Nebula 通知去重——进程内时间窗 map（固定窗+顺路淘汰）。
 *
 * 背景实证（09-03 ProjectCreate 合并回报三连投）：同一 (nodeId,status) 通知
 * 短窗内重复入根会话。与 V8 nebulaDeliveredAt 账本**正交**：账本管跨重启
 * at-least-once（持久、管「结果是否到达过」），本表管秒级抖动（内存、管
 * 「同一通知短窗重复轰炸」）。窗口内重复被抑制时仍记账（账本一致性不破坏）。
 *
 * 用例：
 *  - P1 GREEN 同 (nodeId,completed) 60s 内三连投 → 仅 1 条入根会话 + 账本标记
 *  - P2 不同 status 独立窗口：(id,completed) 后 (id,failed) → 两条都投
 *  - P3 窗口过期：61s 前投过的 (id,status) → 再次投递不被抑制
 *  - 变异红基线：去掉去重 → P1 三连投复现红（1→3 条）
 */
class NebulaDeliveryDedupSpec extends FunSuite:

  private val originalRoot = PathUtil.dataRoot
  private val Hour = 3600_000L

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    nebflow.core.LlmLogWriter.setEnabled(true)

  nebflow.core.LlmLogWriter.setEnabled(false)

  private def node(id: String, name: String, status: String, result: String): NodeDef =
    NodeDef(
      id = id, name = name, agent = "worker", out = Some("Nebula"), status = status,
      result = Some(result), createdAt = System.currentTimeMillis() - 2 * Hour,
      completedAt = Some(System.currentTimeMillis() - Hour)
    )

  private def withFixture(name: String)(body: (FlowMapStore, NodeEngine, SharedResources, Ref[IO, List[AgentCommand]], String) => Unit): Unit =
    val tmp = os.temp.dir(prefix = s"dedup-$name")
    PathUtil.setDataRoot(tmp / "data")
    val system = ActorSystem(s"dedup-$name")
    try
      val io = for
        workspace <- IO(os.makeDir.all(tmp / "ws"))
        store <- FlowMapStore.open("dedupproj", workspace.toString)
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
          llm = llm, dispatcher = dispatcher,
          sessionStore = SessionStore(tmp / "sessions", tmp / "tasks"),
          projectRoot = os.pwd, thinkingConfigRef = thinkingRef, rateLimiter = rateLimiter,
          fileChangeTracker = tracker, contextWindow = 100_000,
          agentLibrary = new AgentLibrary(tmp / "agents"), taskStore = FileTaskStore,
          historyArchiver = HistoryArchiver.fileSystem(tmp / "archives"), fileLockManager = fileLocks,
          sessionModelOverrides = modelOverrides, providerRegistry = null,
          healthMonitor = ProviderHealthMonitor(null), actorSystem = system,
          subAgentTaskStore = new SubAgentTaskStore(tmp / "subagent-tasks"),
          voiceMutedRef = voiceMuted
        )
        recorded <- Ref.of[IO, List[AgentCommand]](Nil)
        rootSid = s"dedup-root-$name"
        rootRef <- system.spawn(recorderBehavior(recorded), s"dedup-rec-$name")
        engine = new NodeEngine(store, system, resources, _ => IO.unit, workspace.toString,
          rootSid, "dedupproj", FeedbackRouter.ModeAuto, (_, _, _) => IO.unit)
      yield (store, engine, resources, recorded, rootSid, rootRef)
      val (store, engine, resources, recorded, rootSid, rootRef) = io.unsafeRunSync()
      resources.agentRegistry
        .update(_ + (rootSid -> AgentRecord(rootSid, rootRef, AgentKind.Root, rootSid)))
        .unsafeRunSync()
      body(store, engine, resources, recorded, rootSid)
    finally
      PathUtil.setDataRoot(originalRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)

  private def recorderBehavior(recorded: Ref[IO, List[AgentCommand]]): nebflow.actor.Behavior[AgentCommand] =
    lazy val b: nebflow.actor.Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand](msg => recorded.update(_ :+ msg).as(b))
    b

  private def imms(recorded: Ref[IO, List[AgentCommand]]): IO[List[AgentCommand.ImmediateInput]] =
    recorded.get.map(_.collect { case m: AgentCommand.ImmediateInput => m })

  /** 有界轮询：等 recorder 收到预期条数（并发负载下 offer→actor 处理异步，直读有竞态）。 */
  private def awaitMsgs(recorded: Ref[IO, List[AgentCommand]], min: Int): IO[List[AgentCommand.ImmediateInput]] =
    def go(deadline: Long): IO[List[AgentCommand.ImmediateInput]] =
      imms(recorded).flatMap { msgs =>
        if msgs.size >= min || System.currentTimeMillis() >= deadline then IO.pure(msgs)
        else IO.sleep(50.millis) >> go(deadline)
      }
    go(System.currentTimeMillis() + 10_000L)

  test("P1 GREEN: three same-(nodeId,status) deliveries within window → exactly one reaches root") {
    withFixture("p1") { (store, engine, resources, recorded, rootSid) =>
      val n = node("n-triple", "triple-node", NodeLifecycle.Completed, "TRIPLE_RESULT")
      val io = for
        _ <- store.mutate(s => s.copy(nodes = s.nodes + ("n-triple" -> n)))
        _ <- engine.deliverOutTo(n, "Nebula", n.result.get)
        _ <- engine.deliverOutTo(n, "Nebula", n.result.get)
        _ <- engine.deliverOutTo(n, "Nebula", n.result.get)
        msgs <- imms(recorded)
        ledger <- store.getNode("n-triple").map(_.flatMap(_.nebulaDeliveredAt))
      yield (msgs, ledger)
      val (msgs, ledger) = io.unsafeRunSync()
      assertEquals(clue(msgs.size), 1, "60s-window duplicates must be suppressed (09-03 三连投复现点)")
      assert(clue(msgs.head.text).contains("TRIPLE_RESULT"))
      assert(clue(ledger).isDefined, "ledger must still be marked on suppressed duplicates (V8 consistency)")
    }
  }

  test("P2: different status → independent windows (completed then failed both delivered)") {
    withFixture("p2") { (store, engine, resources, recorded, rootSid) =>
      val n = node("n-dual", "dual-node", NodeLifecycle.Completed, "DUAL_COMPLETED_RESULT")
      val io = for
        _ <- store.mutate(s => s.copy(nodes = s.nodes + ("n-dual" -> n)))
        _ <- engine.deliverOutTo(n, "Nebula", n.result.get)
        // 同节点状态翻转为 failed（结果更新）——(id,failed) 是另一窗口
        failed = n.copy(status = NodeLifecycle.Failed, result = Some("DUAL_FAILED_RESULT"))
        _ <- store.mutate(s => s.copy(nodes = s.nodes + ("n-dual" -> failed)))
        _ <- engine.redeliverUnconsumedNebulaResults()
        msgs <- imms(recorded)
      yield msgs
      val msgs = io.unsafeRunSync()
      assertEquals(clue(msgs.size), 2, "(id,completed) and (id,failed) are independent windows")
      assert(clue(msgs.map(_.text)).exists(_.contains("DUAL_COMPLETED_RESULT")))
      assert(clue(msgs.map(_.text)).exists(_.contains("DUAL_FAILED_RESULT")))
    }
  }

  test("P3: window expiry — a delivery 61s old does not suppress the next one") {
    withFixture("p3") { (store, engine, resources, recorded, rootSid) =>
      val n = node("n-expired", "expired-node", NodeLifecycle.Completed, "EXPIRY_RESULT")
      val io = for
        _ <- store.mutate(s => s.copy(nodes = s.nodes + ("n-expired" -> n)))
        // 预置窗口外旧投递记录（61s 前——超过 60s 窗口，顺路验证时间窗淘汰）
        now <- IO(System.currentTimeMillis())
        _ <- engine.recentNebulaDeliveries.update(
          _ + (("n-expired", "completed") -> (now - NodeEngine.NebulaDedupWindowMs - 1000L)))
        _ <- engine.deliverOutTo(n, "Nebula", n.result.get)
        msgs <- awaitMsgs(recorded, min = 1)
      yield msgs
      val msgs = io.unsafeRunSync()
      assertEquals(clue(msgs.size), 1, "expired window entry must not suppress delivery")
      assert(clue(msgs.head.text).contains("EXPIRY_RESULT"))
    }
  }

end NebulaDeliveryDedupSpec
