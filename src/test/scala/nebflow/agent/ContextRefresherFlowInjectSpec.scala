package nebflow.agent

import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import fs2.Stream
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.core.FileChangeTracker
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{AgentModelConfig, FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, StreamChunk}

/**
 * Regression for #406 flow-node runtime injections surviving the per-turn
 * disk reload. FlowDagExecutor.executeNode spawns flow agents with
 * `baseDef.copy(flowContract = ...)` — but ContextRefresher.loadCurrentDef
 * reloads the agent def from disk every turn and, pre-fix, only re-applied
 * modelOverride, silently dropping the flowContract for any agent that
 * EXISTS on disk (standalone/team agents reused by dynamic flows — e.g.
 * Explorer as a flow node).
 *
 * The E2E missed this because its isolated home had an EMPTY agents dir:
 * agentLibrary.get → None → caller falls back to the spawn snapshot (which
 * still carries the injection). THIS spec pins the "disk agent exists"
 * branch explicitly.
 *
 * 2026-09-06（工具面裁撤批）口径更新：spec 原名 FlowInject，曾同时锁
 * FlowReport 工具 append 的保活。FlowReport 工具已退役——append 逻辑从
 * applyRuntimeOverrides 移除（决策 A①：磁盘 tools 声明解析照旧，运行时不再
 * 追加任何已退役工具名）。本 spec 现锁定：flowContract + modelOverride 的
 * 热重载保活不变，且 reload 不再发明/保留 FlowReport 名。
 */
class ContextRefresherFlowInjectSpec extends munit.CatsEffectSuite:

  private val diskAgentJson: String =
    """{"name":"Explorer","description":"disk def under test","category":"standalone","tools":["Read","Write"],"systemPrompt":""}"""

  private val injectedDef: AgentDef =
    AgentDef(
      name = "Explorer",
      description = "running def with flow-node injections",
      tools = List("Read", "Write"),
      systemPrompt = "",
      category = "standalone",
      modelOverride = Some(AgentModelConfig(preferred = Some("zhipu/glm-5.3"))),
      flowContract = Some(FlowNodeContract(caseKeys = Set("pass", "fail")))
    )

  private val noopLlm: LlmHandle[IO] = new LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected in this spec"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.empty

  private def mkResources(tmp: os.Path, system: ActorSystem): IO[SharedResources] =
    for
      dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- IO.ref(ThinkingConfig())
      modelOverrides <- IO.ref(Map.empty[String, ModelCandidate])
      voiceMuted <- IO.ref(false)
    yield SharedResources(
      llm = noopLlm,
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

  private def writeDiskAgent(tmp: os.Path): IO[Unit] = IO {
    os.makeDir.all(tmp / "agents" / "Explorer")
    os.write.over(tmp / "agents" / "Explorer" / "agent.json", diskAgentJson)
  }

  test("loadCurrentDef keeps flowContract + modelOverride when disk def exists") {
    for
      system <- IO(ActorSystem("flow-inject-spec"))
      tmp <- IO(os.temp.dir(prefix = "cf-inject-"))
      _ <- writeDiskAgent(tmp)
      resources <- mkResources(tmp, system)
      freshOpt <- ContextRefresher.loadCurrentDef(None, resources, injectedDef)
    yield
      val d = freshOpt.getOrElse(fail("loadCurrentDef returned None — disk def not found"))
      assertEquals(d.flowContract, injectedDef.flowContract, "flowContract dropped by reload")
      assertEquals(d.modelOverride, injectedDef.modelOverride, "modelOverride dropped by reload")
      assertEquals(d.model, injectedDef.modelOverride, "model must be re-applied from modelOverride")
  }

  test("loadCurrentDef does not invent FlowReport for non-flow defs") {
    val plain = injectedDef.copy(tools = List("Read", "Write"), flowContract = None, modelOverride = None)
    for
      system <- IO(ActorSystem("flow-inject-spec-2"))
      tmp <- IO(os.temp.dir(prefix = "cf-inject-"))
      _ <- writeDiskAgent(tmp)
      resources <- mkResources(tmp, system)
      freshOpt <- ContextRefresher.loadCurrentDef(None, resources, plain)
    yield
      val d = freshOpt.getOrElse(fail("loadCurrentDef returned None"))
      assert(!d.tools.contains("FlowReport"), s"FlowReport invented for non-flow def: ${d.tools}")
      assert(d.flowContract.isEmpty, "flowContract invented for non-flow def")
  }

  test("loadCurrentDef no longer re-appends retired FlowReport after reload (2026-09-06)") {
    // 退役前：running.tools 带 FlowReport 时热重载会把名字补回。退役后：
    // append 逻辑移除——名字不得再出现（工具已从注册表摘除，补名只会制造
    // 惰性字符串噪声）。
    val retired = injectedDef.copy(tools = List("Read", "Write", "FlowReport"))
    for
      system <- IO(ActorSystem("flow-inject-spec-3"))
      tmp <- IO(os.temp.dir(prefix = "cf-inject-"))
      _ <- writeDiskAgent(tmp)
      resources <- mkResources(tmp, system)
      freshOpt <- ContextRefresher.loadCurrentDef(None, resources, retired)
    yield
      val d = freshOpt.getOrElse(fail("loadCurrentDef returned None — disk def not found"))
      assert(!d.tools.contains("FlowReport"), s"retired FlowReport re-appended by reload: ${d.tools}")
      assertEquals(d.flowContract, retired.flowContract, "flowContract must still survive the reload")
  }
