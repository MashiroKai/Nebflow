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
      out = List(OutEdge.nebula),
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
          (_, _, _) => IO.unit,
          // noderpt 批 A 段：本 fixture 主题 = 未消费结果重投 ⇒ 显式关腿 2（生产默认开）。
          reportGateHold = Some(false)
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

  /** 有界轮询等待投递消息记账（ad0a50ad awaitMsgs 模式）：deliverToNebula 是
    * `ref ! ImmediateInput` fire-and-forget——扫描返回时消息可能仍在 recorder
    * 邮箱里未处理，立即直读 recorded 有竞态（R4 CI 偶发 NoSuchElementException
    * head of empty list @:200；R1@:147 / R5@:218 同根因偶发 size 断言红）。
    * 等预期投递记录到达后再断言，不改断言语义。 */
  private def awaitImms(
      recorded: Ref[IO, List[AgentCommand]],
      min: Int,
      timeoutMs: Long = 10_000L
  ): IO[List[AgentCommand.ImmediateInput]] =
    def snapshot: IO[List[AgentCommand.ImmediateInput]] =
      recorded.get.map(_.collect { case m: AgentCommand.ImmediateInput => m })
    def go(deadline: Long): IO[List[AgentCommand.ImmediateInput]] =
      snapshot.flatMap { ms =>
        if ms.size >= min || System.currentTimeMillis() >= deadline then IO.pure(ms)
        else IO.sleep(50.millis) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeoutMs)

  test("R1 GREEN: crash-window result (completed, unmarked) is redelivered to Nebula and marked") {
    withFixture("r1") { (store, engine, resources, system, recorded, rootSid, rootRef) =>
      val io = for
        _ <- registerRoot(resources, rootSid, rootRef)
        _ <- store.mutate(s => s.copy(nodes = s.nodes + ("n-done" -> completedNode("n-done", NodeLifecycle.Completed, "V8_CRASH_WINDOW_RESULT"))))
        n1 <- engine.redeliverUnconsumedNebulaResults()
        msgs <- awaitImms(recorded, min = 1)
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
        countAfterFirst <- awaitImms(recorded, min = 1).map(_.size)
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
        msgs <- awaitImms(recorded, min = 1)
      yield (n, msgs)
      val (n, msgs) = io.unsafeRunSync()
      assertEquals(clue(n), 1)
      val imms = msgs.collect { case m: AgentCommand.ImmediateInput => m }
      assert(clue(imms.head.text).contains("r4 failure detail"))
      assert(clue(imms.head.eventType).contains("failed"), "failed status rides the bubble header")
    }
  }

  test("R5 manual edit-redelivery (deliverOutTo): unmarked node delivers and marks (first wiring passes)") {
    withFixture("r5") { (store, engine, resources, system, recorded, rootSid, rootRef) =>
      val io = for
        _ <- registerRoot(resources, rootSid, rootRef)
        node = completedNode("n-manual", NodeLifecycle.Completed, "r5 manual")
        _ <- store.mutate(s => s.copy(nodes = s.nodes + ("n-manual" -> node)))
        // 人工接线首次投递（nebulaDeliveredAt 空 = 首次接线）⇒ M1 守卫放行。
        _ <- engine.deliverOutTo(node, "Nebula", "r5 manual")
        msgs <- awaitImms(recorded, min = 1)
        fresh <- store.getNode("n-manual")
      yield (msgs, fresh)
      val (msgs, fresh) = io.unsafeRunSync()
      val imms = msgs.collect { case m: AgentCommand.ImmediateInput => m }
      assertEquals(clue(imms.size), 1, "first-wiring manual redelivery must deliver")
      assert(clue(fresh.flatMap(_.nebulaDeliveredAt)).isDefined, "ledger written after delivery")
    }
  }

  test("R5b M1 guard: manual redelivery of an ALREADY-MARKED node is suppressed (persistent anchor)") {
    withFixture("r5b") { (store, engine, resources, system, recorded, rootSid, rootRef) =>
      val io = for
        _ <- registerRoot(resources, rootSid, rootRef)
        node = completedNode("n-marked", NodeLifecycle.Completed, "r5b marked")
        // 已记账（= 结果早已投达 root）：再走一遍人工改接不得重复投
        //（旧口径「已记账也再投一次」＝本批 N4/M1 要堵的重复面）。
        _ <- store.mutate(s => s.copy(nodes = s.nodes + ("n-marked" ->
          node.copy(nebulaDeliveredAt = Some(System.currentTimeMillis() - 3600_000L)))))
        marked <- store.getNode("n-marked").map(_.get)
        _ <- engine.deliverOutTo(marked, "Nebula", "r5b marked")
        _ <- IO.sleep(300.millis)
        msgs <- recorded.get
      yield msgs
      val msgs = io.unsafeRunSync()
      assert(msgs.isEmpty, s"a marked Nebula edge must not be re-delivered (persistent anchor), got $msgs")
    }
  }

  test("R6 N3: the redelivery scan skips mode=signal EXIT-MARKER edges (never promotes them to a root notify)") {
    withFixture("r6") { (store, engine, resources, system, recorded, rootSid, rootRef) =>
      val io = for
        _ <- registerRoot(resources, rootSid, rootRef)
        // bare "Nebula" 落边形态：{pass}/signal 出口标记 —— 崩溃/重启窗口内扫描不得补投
        exitMarker = completedNode("n-exit", NodeLifecycle.Completed, "R6_EXIT_MARKER_RESULT")
          .copy(out = List(OutEdge(OutEdge.NebulaTarget, Set(OutEdge.Pass), OutEdge.Signal)))
        // 对照：显式门集 {pass,failed}/result 通知声明仍须被扫描补投
        notifyEdge = completedNode("n-notify", NodeLifecycle.Completed, "R6_NOTIFY_RESULT")
        _ <- store.mutate(s => s.copy(nodes = s.nodes + ("n-exit" -> exitMarker) + ("n-notify" -> notifyEdge)))
        n <- engine.redeliverUnconsumedNebulaResults()
        msgs <- awaitImms(recorded, min = 1)
      yield (n, msgs)
      val (n, msgs) = io.unsafeRunSync()
      val imms = msgs.collect { case m: AgentCommand.ImmediateInput => m }
      assertEquals(clue(n), 1, "only the explicit-gate (mode=result) node is a redelivery candidate")
      assertEquals(clue(imms.size), 1)
      assert(clue(imms.head.text).contains("R6_NOTIFY_RESULT"), "explicit-gate notify still redelivers")
      assert(!imms.exists(_.text.contains("R6_EXIT_MARKER_RESULT")), "signal exit marker must never be redelivered to root")
    }
  }

  test("R7 source-status gate selection: failed source needs a failed edge; cancelled source never delivers") {
    withFixture("r7") { (store, engine, resources, system, recorded, rootSid, rootRef) =>
      val io = for
        _ <- registerRoot(resources, rootSid, rootRef)
        now <- IO(System.currentTimeMillis())
        // (a) failed 源 + 只有 pass 腿 ⇒ 跳过（旧口径方向相反的缺口）
        failedPass = NodeDef(id = "n-fp", name = "fp", agent = "worker",
          out = List(OutEdge("n-down", Set(OutEdge.Pass))), status = NodeLifecycle.Failed,
          result = Some("FAILED_ERR_TEXT"), createdAt = now - 60_000L, completedAt = Some(now))
        // (b) failed 源 + failed 腿 ⇒ 投递（按源 status 选门）
        failedFail = NodeDef(id = "n-ff", name = "ff", agent = "worker",
          out = List(OutEdge("n-down2", Set(OutEdge.Failed), OutEdge.Signal)), status = NodeLifecycle.Failed,
          result = Some("FAILED_EDGE_TEXT"), createdAt = now - 60_000L, completedAt = Some(now))
        // (c) cancelled 源 ⇒ 整体不投（仅 info）
        cancelled = NodeDef(id = "n-cx", name = "cx", agent = "worker",
          out = List(OutEdge("n-down3", Set(OutEdge.Pass))), status = NodeLifecycle.Cancelled,
          result = Some("CANCELLED_TEXT"), createdAt = now - 60_000L, completedAt = Some(now))
        // 目标节点（wiring）——投递 = deliveredTo 记账 + startNode 尝试
        down = NodeDef(id = "n-down", name = "down", agent = "worker", in = List("n-fp"), createdAt = now)
        down2 = NodeDef(id = "n-down2", name = "down2", agent = "worker", in = List("n-ff"), createdAt = now)
        down3 = NodeDef(id = "n-down3", name = "down3", agent = "worker", in = List("n-cx"), createdAt = now)
        _ <- store.mutate(s => s.copy(nodes = s.nodes ++ Map(
          "n-fp" -> failedPass, "n-ff" -> failedFail, "n-cx" -> cancelled,
          "n-down" -> down, "n-down2" -> down2, "n-down3" -> down3)))
        _ <- engine.deliverOutTo(failedPass, "n-down", "FAILED_ERR_TEXT")
        _ <- engine.deliverOutTo(failedFail, "n-down2", "FAILED_EDGE_TEXT")
        _ <- engine.deliverOutTo(cancelled, "n-down3", "CANCELLED_TEXT")
        d1 <- store.getNode("n-down")
        d2 <- store.getNode("n-down2")
        d3 <- store.getNode("n-down3")
      yield (d1, d2, d3)
      val (d1, d2, d3) = io.unsafeRunSync()
      assertEquals(clue(d1.map(_.deliveredTo)), Some(List.empty[String]),
        "failed source on a pass-only edge must NOT deliver (gate selected by source status)")
      assertEquals(clue(d2.map(_.deliveredTo)), Some(List("n-ff")),
        "failed source on a failed edge must deliver")
      assertEquals(clue(d3.map(_.deliveredTo)), Some(List.empty[String]),
        "cancelled source never delivers (no input semantics)")
    }
  }

end NebulaDeliveryRedeliverySpec
