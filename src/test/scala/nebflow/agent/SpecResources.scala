/* Phase 3 去重(行为保持重构,2026-09-25)。 */
package nebflow.agent

import cats.effect.{IO, Ref}
import nebflow.actor.ActorSystem
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.llm.ModelCandidate
import nebflow.shared.{LlmHandle, ThinkingConfig}

/**
 * SharedResources 测试工厂——原 43 份逐字节相同的
 * `private def mkResources(system: ActorSystem, tmp: os.Path, llm: LlmHandle[IO])`
 * (core/plugin/PluginDispatchFaceSpec + core/project 下 42 份)的唯一公共实现。
 * 函数体与被替换的 43 份逐字相同,仅挪位;字段契约:
 *
 *  - `tmp` 只进两处:`SessionStore(tmp / "sessions", tmp / "tasks")` 与
 *    `AgentLibrary(tmp / "agents")`;
 *  - `projectRoot` / `FileChangeTracker` 恒取 `os.pwd`(与 tmp 无关——迁移前如此,
 *    保持);
 *  - `llm` 原样透传;
 *  - `historyArchiver` / `providerRegistry` / `healthMonitor` 恒
 *    `null`(这些 spec 不触达;null 只落 test 域,沿既有口径);
 *  - `actorSystem` 恒 `null` —— 🔴 2026-10-24 CI 分诊注：此折衷已开始漏水——
 *    scan 面的看护腿（TaskStuckWatcher → MergeWindowCare.care，steward 批
 *    2026-10-23）读 actorSystem，null 会在 IO 构造期 NPE（JVM 内全局
 *    MergeQueue 有其它套件残留条目时必炸，NodeBgCompletionGateSpec G7 实测）。
 *    需要跑 scan 的套件请 `res.copy(actorSystem = system)` 局部接真（G7 先例），
 *    不要在此全局改（18+ 套件共享本夹具，一次性换面风险未评估）;
 *  - `dispatcher` 用 `Dispatcher.parallel.allocated` 取泄漏形态 `.map(_._1)`
 *    (与原 43 份相同的既有语义:不释放);
 *  - 其余字段取本函数内新建的默认 Ref / 常量(contextWindow = 100_000)。
 *
 * 同签名但函数体有漂移的 mkResources 变体(如 (system, tmp, llm, sessionStore)
 * 8 份、(system, tmp) 20 份等)不属本簇,留原地。
 */
object SpecResources:

  def mkResources(system: ActorSystem, tmp: os.Path, llm: LlmHandle[IO]): IO[SharedResources] =
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
      actorSystem = null,
      voiceMutedRef = voiceMuted
    )
end SpecResources
