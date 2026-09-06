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
 * 缺口3（2026-09-04 投递可靠性批次）：测试夹具信封排除。
 *
 * 09-03 实证（宿主 phd-notebook 归档真实数据）：cancel-test、cancel-test-3..11
 * 取消语义验证节点（任务正文带「（取消验证节点）」自证标记）流入宿主真实
 * flow-map，宿主重启后 V8 重投扫描逐条轰炸根会话。
 *
 * 规则（宁窄勿宽，双条件缺一不可）：
 *  - 信封名精确命中夹具家族 ^cancel-test(-\d+)?$ **且** 任务载荷带夹具自证
 *    标记「取消验证节点」→ 排除投递 + WARN + 记账（通知通道对该信封关闭，
 *    防 30s 周期扫描重复 WARN；结果不删不改滞留节点可查）。
 *  - 名字命中但载荷无标记（真实工作）→ 照常投递（防过宽）。
 *  - 名字不命中家族（如 my-test-node 巧合含 test）→ 照常投递（宁窄勿宽）。
 *  - 人工改接重投通道（deliverOutTo→Nebula）同样过闸。
 *
 * 用例：
 *  - X1 GREEN 夹具信封（名+载荷双确认）排除 + 记账（扫描不重 Warn）
 *  - X2 对照①：名字命中家族但载荷真实 → 照常投递
 *  - X3 对照②：名字巧合含 test 但不命中精确家族 → 照常投递
 *  - X4 人工改接通道同样排除夹具信封
 *  - 变异红基线：去掉排除 → X1/X4 夹具信封投递复现红
 */
class FixtureEnvelopeGuardSpec extends FunSuite:

  private val originalRoot = PathUtil.dataRoot
  private val Hour = 3600_000L

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    nebflow.core.LlmLogWriter.setEnabled(true)

  nebflow.core.LlmLogWriter.setEnabled(false)

  private def node(id: String, name: String, task: String, result: String): NodeDef =
    NodeDef(
      id = id, name = name, agent = "worker", out = Some("Nebula"),
      status = NodeLifecycle.Completed, task = Some(task), result = Some(result),
      createdAt = System.currentTimeMillis() - 2 * Hour,
      completedAt = Some(System.currentTimeMillis() - Hour)
    )

  private def withFixture(name: String)(body: (FlowMapStore, NodeEngine, SharedResources, Ref[IO, List[AgentCommand]], String) => Unit): Unit =
    val tmp = os.temp.dir(prefix = s"fixguard-$name")
    PathUtil.setDataRoot(tmp / "data")
    val system = ActorSystem(s"fixguard-$name")
    try
      val io = for
        workspace <- IO(os.makeDir.all(tmp / "ws"))
        store <- FlowMapStore.open("fixproj", workspace.toString)
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
        rootSid = s"fix-root-$name"
        rootRef <- system.spawn(recorderBehavior(recorded), s"fix-rec-$name")
        engine = new NodeEngine(store, system, resources, _ => IO.unit, workspace.toString,
          rootSid, "fixproj", FeedbackRouter.ModeAuto, (_, _, _) => IO.unit)
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

  // 09-03 实证夹具命名家族（宿主 phd-notebook 归档逐字盘点）+ 自证标记
  private val FixtureTask = "（取消验证节点）第一步：获取 httpbin 延迟端点返回。"

  test("X1 GREEN: fixture envelope (name family + payload marker) excluded from scan and ledger-marked") {
    withFixture("x1") { (store, engine, resources, recorded, rootSid) =>
      val io = for
        _ <- store.mutate(s => s.copy(nodes = s.nodes ++ Map(
          "n-fx1" -> node("n-fx1", "cancel-test-9", FixtureTask, "httpbin 响应已获取"),
          "n-fx2" -> node("n-fx2", "cancel-test", FixtureTask, "another fixture artifact")
        )))
        n <- engine.redeliverUnconsumedNebulaResults()
        msgs <- imms(recorded)
        all <- store.snapshot.map(_.nodes)
        // 二次扫描：已记账 → 不再进入 pending → 无重复 WARN 来源
        n2 <- engine.redeliverUnconsumedNebulaResults()
        msgs2 <- imms(recorded)
      yield (n, msgs, all, n2, msgs2)
      val (n, msgs, all, n2, msgs2) = io.unsafeRunSync()
      assertEquals(clue(n), 0, "fixture envelopes must not be delivered")
      assertEquals(clue(msgs.size), 0, "no envelope may reach the root session")
      assert(clue(all.values.filter(_.nebulaDeliveredAt.isEmpty)).isEmpty,
        "excluded fixtures must be ledger-marked (channel closed — no repeated scan hits)")
      assertEquals(clue(n2), 0)
      assertEquals(clue(msgs2.size), 0, "second scan stays silent (marking prevented re-flagging)")
    }
  }

  test("X2 control: name hits family but payload is genuine work → delivered (双条件缺一不可)") {
    withFixture("x2") { (store, engine, resources, recorded, rootSid) =>
      val io = for
        _ <- store.mutate(s => s.copy(nodes = s.nodes + (
          "n-genuine" -> node("n-genuine", "cancel-test-12", "真实研究任务：调研 CZT 读出电子学文献", "GENUINE_RESEARCH_RESULT"))))
        n <- engine.redeliverUnconsumedNebulaResults()
        // 有界轮询：等待投递消息记录到达（offer→actor 处理异步，立即直读有竞态）
        msgs <- awaitMsgs(recorded, min = 1)
      yield (n, msgs)
      val (n, msgs) = io.unsafeRunSync()
      assertEquals(clue(n), 1, "genuine payload must not be excluded by name alone")
      assert(clue(msgs.map(_.text)).exists(_.contains("GENUINE_RESEARCH_RESULT")))
    }
  }

  test("X3 control: coincidental 'test' in name but family mismatch → delivered (宁窄勿宽)") {
    withFixture("x3") { (store, engine, resources, recorded, rootSid) =>
      val io = for
        _ <- store.mutate(s => s.copy(nodes = s.nodes ++ Map(
          // 名字含 test 但不命中 ^cancel-test(-\d+)$ 家族——即使载荷带标记也照常投递
          "n-coincide" -> node("n-coincide", "my-test-node", FixtureTask, "COINCIDENTAL_NAME_RESULT"),
          // 家族内数字段后缀变体命中 → 排除（家族边界确认）
          "n-fam-edge" -> node("n-fam-edge", "cancel-test-11", FixtureTask, "FAMILY_EDGE_FIXTURE")
        )))
        n <- engine.redeliverUnconsumedNebulaResults()
        // 有界轮询：等待投递消息记录到达（offer→actor 处理异步，立即直读有竞态）
        msgs <- awaitMsgs(recorded, min = 1)
      yield (n, msgs)
      val (n, msgs) = io.unsafeRunSync()
      assertEquals(clue(n), 1, "only the family-matching fixture is excluded")
      assert(clue(msgs.map(_.text)).exists(_.contains("COINCIDENTAL_NAME_RESULT")),
        "coincidental test-name with fixture-looking payload still delivered (narrow rule)")
      assert(clue(msgs.map(_.text)).forall(!_.contains("FAMILY_EDGE_FIXTURE")),
        "exact family variant (cancel-test-11) stays excluded")
    }
  }

  test("X4: manual redelivery channel (deliverOutTo) also excludes fixture envelopes") {
    withFixture("x4") { (store, engine, resources, recorded, rootSid) =>
      val fixture = node("n-manual-fx", "cancel-test-5", FixtureTask, "MANUAL_FIXTURE_ARTIFACT")
      val genuine = node("n-manual-ok", "real-worker", "真实任务", "MANUAL_GENUINE_RESULT")
      val io = for
        _ <- store.mutate(s => s.copy(nodes = s.nodes ++ Map("n-manual-fx" -> fixture, "n-manual-ok" -> genuine)))
        _ <- engine.deliverOutTo(fixture, "Nebula", fixture.result.get)
        _ <- engine.deliverOutTo(genuine, "Nebula", genuine.result.get)
        msgs <- awaitMsgs(recorded, min = 1)
        fxLedger <- store.getNode("n-manual-fx").map(_.flatMap(_.nebulaDeliveredAt))
        okLedger <- store.getNode("n-manual-ok").map(_.flatMap(_.nebulaDeliveredAt))
      yield (msgs, fxLedger, okLedger)
      val (msgs, fxLedger, okLedger) = io.unsafeRunSync()
      assert(clue(msgs.map(_.text)).forall(!_.contains("MANUAL_FIXTURE_ARTIFACT")), "fixture envelope suppressed on manual channel")
      assert(clue(msgs.map(_.text)).exists(_.contains("MANUAL_GENUINE_RESULT")), "genuine manual redelivery passes")
      assert(clue(fxLedger).isEmpty, "excluded manual envelope must NOT be ledger-marked (node result retained for inspection)")
      assert(clue(okLedger).isDefined, "genuine manual redelivery refreshes the ledger")
    }
  }

end FixtureEnvelopeGuardSpec
