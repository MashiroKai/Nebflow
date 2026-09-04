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
 * 缺口2（2026-09-04 投递可靠性批次）：补投新鲜度门控——历史欠账（completedAt
 * 距今 >24h，与节点显示 TTL 同口径）重投时同批合并为单条汇总通知（形态 (a)：
 * 纯后端立即生效，不依赖前端改动——形态 (b) 载荷打标记需后续前端批）。
 *
 * 背景实证（09-04 晨立卡①）：宿主重启后数十条 09-03 白天完成的历史通知逐条
 * 集中轰炸根会话——V8 重投扫描无新鲜度门控无汇总。
 *
 * 红线：结果照投不丢（每节点 id+状态+摘要入汇总并逐节点记账）；首次实时投递
 * 零改动；<24h 欠账仍逐条投（对照断言防误伤）；completedAt 缺失按新鲜（宁投勿丢）。
 *
 * 用例：
 *  - F1 GREEN >24h 双欠账合并单条 + <24h/None 仍逐条 + 全部记账
 *  - F2 混含 failed 的历史批 → 汇总 eventType=failed
 *  - F3 对照：全部 <24h 欠账逐条投（无汇总）
 *  - 变异红基线：去掉新鲜度门控 → F1 逐条轰炸复现（imms 数量 3→4）红
 */
class RedeliveryFreshnessGateSpec extends FunSuite:

  private val originalRoot = PathUtil.dataRoot
  private val Hour = 3600_000L

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    nebflow.core.LlmLogWriter.setEnabled(true)

  nebflow.core.LlmLogWriter.setEnabled(false)

  private def node(id: String, name: String, status: String, result: String, completedAt: Option[Long]): NodeDef =
    NodeDef(
      id = id, name = name, agent = "worker", out = Some("Nebula"), status = status,
      result = Some(result), createdAt = System.currentTimeMillis() - 2 * Hour,
      completedAt = completedAt, ttlExpireAt = completedAt.map(_ + NodeEngine.TtlDisplayMs)
    )

  private def withFixture(name: String)(body: (FlowMapStore, NodeEngine, SharedResources, Ref[IO, List[AgentCommand]], String) => Unit): Unit =
    val tmp = os.temp.dir(prefix = s"fresh-$name")
    PathUtil.setDataRoot(tmp / "data")
    val system = ActorSystem(s"fresh-$name")
    try
      val io = for
        workspace <- IO(os.makeDir.all(tmp / "ws"))
        store <- FlowMapStore.open("freshproj", workspace.toString)
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
        rootSid = s"fresh-root-$name"
        rootRef <- system.spawn(recorderBehavior(recorded), s"fresh-rec-$name")
        engine = new NodeEngine(store, system, resources, _ => IO.unit, workspace.toString,
          rootSid, "freshproj", FeedbackRouter.ModeAuto, (_, _, _) => IO.unit)
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

  test("F1 GREEN: >24h debts merged into ONE summary; <24h and no-timestamp debts still individual; all marked") {
    withFixture("f1") { (store, engine, resources, recorded, rootSid) =>
      val now = System.currentTimeMillis()
      val io = for
        _ <- store.mutate(s => s.copy(nodes = s.nodes ++ Map(
          "n-stale-a" -> node("n-stale-a", "stale-alpha", NodeLifecycle.Completed, "STALE_ALPHA_RESULT_1903", Some(now - 25 * Hour)),
          "n-stale-b" -> node("n-stale-b", "stale-beta", NodeLifecycle.Completed, "STALE_BETA_RESULT_1903", Some(now - 25 * Hour)),
          "n-fresh-c" -> node("n-fresh-c", "fresh-gamma", NodeLifecycle.Completed, "FRESH_GAMMA_RESULT", Some(now - Hour)),
          "n-none-d"  -> node("n-none-d", "none-delta", NodeLifecycle.Completed, "NONE_DELTA_RESULT", None)
        )))
        n <- engine.redeliverUnconsumedNebulaResults()
        msgs <- imms(recorded)
        all <- store.snapshot.map(_.nodes)
      yield (n, msgs, all)
      val (n, msgs, all) = io.unsafeRunSync()
      assertEquals(clue(n), 4, "scan counts all four as handled")
      // 新鲜逐条（fresh-gamma + none-delta）+ 历史合并单条 = 3 条通知（修复前逐条 = 4 条轰炸）
      assertEquals(clue(msgs.size), 3, "2 stale merge into 1 summary; fresh ones stay individual")
      val merged = msgs.filter(_.text.contains("历史欠账汇总补投"))
      assertEquals(clue(merged.size), 1, "exactly one merged summary notification")
      val m = merged.head
      assert(clue(m.text).contains("stale-alpha") && clue(m.text).contains("n-stale-a"), "summary carries nodeId+name")
      assert(clue(m.text).contains("stale-beta") && clue(m.text).contains("n-stale-b"), "summary carries second nodeId+name")
      assert(clue(m.text).contains("STALE_ALPHA_RESULT_1903") && clue(m.text).contains("STALE_BETA_RESULT_1903"), "summary carries result digests (照投不丢)")
      assertEquals(clue(m.eventType), Some("completed"), "all-completed batch rides completed header")
      assert(clue(msgs.filterNot(_.text.contains("历史欠账汇总补投")).map(_.text))
        .exists(_.contains("FRESH_GAMMA_RESULT")), "<24h debt still delivered individually")
      assert(clue(msgs.filterNot(_.text.contains("历史欠账汇总补投")).map(_.text))
        .exists(_.contains("NONE_DELTA_RESULT")), "missing completedAt treated as fresh (宁投勿丢)")
      assert(clue(all.values.filter(_.nebulaDeliveredAt.isEmpty)).isEmpty, "every delivered node must be ledger-marked")
    }
  }

  test("F2: mixed failed in stale batch → summary rides failed header") {
    withFixture("f2") { (store, engine, resources, recorded, rootSid) =>
      val now = System.currentTimeMillis()
      val io = for
        _ <- store.mutate(s => s.copy(nodes = s.nodes ++ Map(
          "n-old-f" -> node("n-old-f", "old-fail", NodeLifecycle.Failed, "OLD_FAILURE_DETAIL", Some(now - 30 * Hour)),
          "n-old-c" -> node("n-old-c", "old-done", NodeLifecycle.Completed, "OLD_DONE_RESULT", Some(now - 30 * Hour))
        )))
        _ <- engine.redeliverUnconsumedNebulaResults()
        msgs <- imms(recorded)
      yield msgs
      val msgs = io.unsafeRunSync()
      assertEquals(clue(msgs.size), 1, "one merged summary for the stale batch")
      assertEquals(clue(msgs.head.eventType), Some("failed"), "any failed in batch → failed header (strong attention)")
      assert(clue(msgs.head.text).contains("OLD_FAILURE_DETAIL"))
    }
  }

  test("F3 control: all debts <24h stay individual (no summary, no误伤)") {
    withFixture("f3") { (store, engine, resources, recorded, rootSid) =>
      val now = System.currentTimeMillis()
      val io = for
        _ <- store.mutate(s => s.copy(nodes = s.nodes ++ Map(
          "n-y-a" -> node("n-y-a", "yesterday-alpha", NodeLifecycle.Completed, "YESTERDAY_ALPHA", Some(now - 23 * Hour)),
          "n-y-b" -> node("n-y-b", "yesterday-beta", NodeLifecycle.Completed, "YESTERDAY_BETA", Some(now - 23 * Hour))
        )))
        _ <- engine.redeliverUnconsumedNebulaResults()
        msgs <- imms(recorded)
      yield msgs
      val msgs = io.unsafeRunSync()
      assertEquals(clue(msgs.size), 2, "<24h debts must stay one-by-one")
      assert(clue(msgs.forall(!_.text.contains("历史欠账汇总补投"))), "no summary notification for fresh debts")
    }
  }

end RedeliveryFreshnessGateSpec
