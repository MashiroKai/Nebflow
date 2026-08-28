package nebflow.agent

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.core.FileChangeTracker
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, LlmMeta, StreamChunk}

import scala.concurrent.duration.*

/**
 * #308 spec §6 A3：usageUpdate 事件必须携带该轮实际使用的模型。
 *
 *  - 序列化：主会话与 subagent 两个分支都在 Some(model) 时输出 "model" 键；
 *    None 时省略（老前端零感知，payload 字节稳定）
 *  - 接线（B1b）：真 AgentActor 完成一轮 LLM 调用后，usageUpdate 事件的
 *    model 字段 = 该轮 meta.model（fallback 后的实际模型）——前端 popup
 *    轮级 live 刷新的数据源
 */
class UsageUpdateModelSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  // ── A3 序列化契约 ─────────────────────────────────────────

  test("usageUpdate serializes model on the main-session branch") {
    val j = AgentStreamEvent
      .UsageUpdate(inputTokens = 1, contextWindow = 1000, compactThreshold = 0.8, model = Some("zhipu/GLM-5.3"))
      .toJson("test-agent", false, Some("sid-1"))
    assertEquals(j.hcursor.get[String]("type").toOption, Some("usageUpdate"))
    assertEquals(j.hcursor.get[String]("sessionId").toOption, Some("sid-1"))
    assertEquals(j.hcursor.get[String]("model").toOption, Some("zhipu/GLM-5.3"))
  }

  test("usageUpdate serializes model on the subagent branch (nodeSessionId present)") {
    val j = AgentStreamEvent
      .UsageUpdate(inputTokens = 1, contextWindow = 1000, compactThreshold = 0.8, model = Some("zhipu/GLM-5.3"))
      .toJson("agent-x", true, Some("node-s"))
    assertEquals(j.hcursor.get[String]("type").toOption, Some("usageUpdate"))
    assertEquals(j.hcursor.get[String]("nodeSessionId").toOption, Some("node-s"))
    assertEquals(j.hcursor.get[String]("model").toOption, Some("zhipu/GLM-5.3"))
  }

  test("usageUpdate omits the model key when None (backward compatible)") {
    val j = AgentStreamEvent
      .UsageUpdate(inputTokens = 100, contextWindow = 1000, compactThreshold = 0.8)
      .toJson("test-agent", false, Some("sid-1"))
    assertEquals(j.hcursor.get[String]("model").toOption, None)
    // 既有字段不受影响
    assertEquals(j.hcursor.get[Int]("inputTokens").toOption, Some(100))
    assertEquals(j.hcursor.get[Int]("contextWindow").toOption, Some(1000))
  }

  // ── B1b 接线：真 AgentActor 一轮后事件携带实际模型 ─────────

  private class MetaLlm(model: String) extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected in this test"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream(
        StreamChunk.TextDelta("ok"),
        // meta.model = fallback 后实际命中的 candidate —— lastModel 的唯一来源。
        // lastModel 由 (providerId, model) 组合为 "providerId/model"，meta 里
        // model 只写裸模型名。
        StreamChunk.Done(None, None, meta = Some(LlmMeta(
          sessionId = req.sessionId,
          agentId = "Tester",
          providerId = "107",
          model = "deepseek-v4-pro",
          durationMs = 5L
        )))
      )

  test("actor wiring: usageUpdate event carries the round's actual model from Done meta") {
    val system = ActorSystem("usage-model-e2e")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    val wsEvents: cats.effect.Ref[IO, List[Json]] = cats.effect.Ref.unsafe(Nil)
    try
      val sid = "usage-model-parent"
      def waitForUsageModel(deadline: Long): IO[Unit] =
        wsEvents.get.flatMap { evs =>
          val hit = evs.exists(e =>
            e.hcursor.get[String]("type").contains("usageUpdate") &&
            e.hcursor.get[String]("model").contains("107/deepseek-v4-pro")
          )
          if hit then IO.unit
          else if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError(s"usageUpdate with model not observed, events: $evs"))
          else IO.sleep(100.millis) >> waitForUsageModel(deadline)
        }
      val program = for
        dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
        rateLimiter <- RateLimiter.create()
        tracker <- FileChangeTracker.create(os.pwd.toString)
        fileLocks <- FileLockManager.create
        thinkingRef <- IO.ref(ThinkingConfig())
        modelOverrides <- IO.ref(Map.empty[String, ModelCandidate])
        voiceMuted <- IO.ref(false)
        resources = SharedResources(
          llm = MetaLlm("107/deepseek-v4-pro"),
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
        agentDef = AgentDef(name = "Tester", description = "usage probe", tools = Nil, systemPrompt = "")
        ref <- system.spawn(
          AgentActor(
            agentDef = agentDef,
            resources = resources,
            wsSend = json => wsEvents.update(_ :+ json),
            depth = 0,
            sessionId = Some(sid),
            sessionName = Some("usage-model")
          ),
          sid
        )
        _ <- ref ! AgentCommand.UserInput("hello", None, Some("usage-model-1"))
        // 轮完成 → usageUpdate 已发出；断言其 model = 实际 meta.model
        _ <- waitForUsageModel(System.currentTimeMillis() + 10_000)
        evs <- wsEvents.get
      yield
        val usage = evs.filter(_.hcursor.get[String]("type").contains("usageUpdate"))
        assert(usage.nonEmpty, "at least one usageUpdate per LLM round")
        usage.foreach { u =>
          assertEquals(
            u.hcursor.get[String]("model").toOption,
            Some("107/deepseek-v4-pro"),
            s"every usageUpdate must carry the actual model: $u"
          )
        }
        // done 事件的 model 契约不变（#308 验收 A5）
        val done = evs.filter(_.hcursor.get[String]("type").contains("done"))
        assert(done.exists(_.hcursor.get[String]("model").contains("107/deepseek-v4-pro")),
          s"done event model unchanged path: $done")
      program.unsafeRunSync()
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
  }

end UsageUpdateModelSpec
