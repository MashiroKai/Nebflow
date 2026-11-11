package nebflow.core.executor

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.AgentLibrary
import nebflow.agent.CompactionQueueStore
import nebflow.agent.SharedResources
import nebflow.agent.SubAgentTaskStore
import nebflow.core.FileChangeTracker
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.{PathUtil, ThinkingConfig}

import scala.concurrent.duration.*

/**
 * 完成必回报（P5 静默完成治理第二波）· 外部腿终态兜底两钉：
 *
 *  - parentRef=None / 直投失败 ≠ 回执可丢：终态 ExternalEvent 落父会话 F2 注入队
 *    （[[CompactionQueueStore]]，correlationId=sessionId 幂等），父会话下次 spawn
 *    时 RecoverPersistedQueues 重放——「不可投递」升级为「延迟可达」（内部腿
 *    监督链自动恢复批 2026-10-22 同款机制）；
 *  - 父会话真删（盘上无记录，读数故障 fail-open）= 跳过（不产生无人读的垃圾文件）。
 */
class ExternalTerminalBackstopSpec extends CatsEffectSuite:

  override val munitIOTimeout = 60.seconds

  private val fakeResources: IO[SharedResources] =
    for
      dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- IO.ref(ThinkingConfig())
      modelOverrides <- IO.ref(Map.empty[String, ModelCandidate])
      voiceMuted <- IO.ref(false)
      system <- IO(ActorSystem("ext-backstop"))
    yield SharedResources(
      llm = null,
      dispatcher = dispatcher,
      sessionStore = SessionStore(PathUtil.dataRoot / "sessions", PathUtil.dataRoot / "tasks"),
      projectRoot = os.pwd,
      thinkingConfigRef = thinkingRef,
      rateLimiter = rateLimiter,
      fileChangeTracker = tracker,
      contextWindow = 100_000,
      agentLibrary = new AgentLibrary(PathUtil.dataRoot / "agents"),
      taskStore = FileTaskStore,
      historyArchiver = HistoryArchiver.fileSystem(PathUtil.dataRoot / "archives"),
      fileLockManager = fileLocks,
      sessionModelOverrides = modelOverrides,
      providerRegistry = null,
      healthMonitor = ProviderHealthMonitor(null),
      actorSystem = system,
      subAgentTaskStore = new SubAgentTaskStore(PathUtil.dataRoot / "subagent-tasks"),
      voiceMutedRef = voiceMuted
    )

  private def spec(parentSessionId: String, resources: SharedResources): ExternalSessionRunner.SpawnSpec =
    ExternalSessionRunner.SpawnSpec(
      executorId = "test",
      binPath = "/bin/echo",
      task = "do the thing",
      title = "backstop probe",
      workspace = os.pwd.toString,
      project = None,
      taskId = None,
      parentSessionId = parentSessionId,
      rootSessionId = parentSessionId,
      parentRef = None,
      wsSend = None,
      resources = resources
    )

  private def withTempDataRoot[A](f: IO[A]): A =
    val original = PathUtil.dataRoot
    val tmp = os.temp.dir(prefix = "nb-ext-backstop-")
    PathUtil.setDataRoot(tmp)
    try f.guarantee(IO { PathUtil.setDataRoot(original) }).unsafeRunSync()
    finally
      os.remove.all(tmp)

  test("parentRef=None ⇒ completed receipt lands on the parent F2 queue (idempotent); gone parent ⇒ skipped") {
    withTempDataRoot {
      for
        resources <- fakeResources
        parentSid = "backstop-parent"
        sid = "external-test-abc12345"
        // 父会话在场（盘上记录在 = F2 兜底可达）
        _ <- resources.sessionStore.getOrCreateSession(parentSid, "backstop parent")
        _ <- ExternalSessionRunner.notifyParent(spec(parentSid, resources), sid, ExternalSessionRunner.Outcome.Completed, "the final text", None)
        queued1 <- CompactionQueueStore.load(parentSid)
        // 重入幂等（correlationId 同键不重排）
        _ <- ExternalSessionRunner.notifyParent(spec(parentSid, resources), sid, ExternalSessionRunner.Outcome.Completed, "the final text", None)
        queued2 <- CompactionQueueStore.load(parentSid)
        // 父会话已删（盘上无记录）= 跳过
        _ <- ExternalSessionRunner.notifyParent(spec("gone-parent", resources), sid, ExternalSessionRunner.Outcome.Completed, "orphan text", None)
        queuedGone <- CompactionQueueStore.load("gone-parent")
        _ <- resources.actorSystem.stopAll.attempt.void
      yield
        val events1 = queued1.map(_.events).getOrElse(Nil)
        assertEquals(events1.size, 1, s"exactly one queued terminal, got: ${events1.map(_.eventType)}")
        val e = events1.head
        assertEquals(e.eventType, "completed")
        assertEquals(e.correlationId, Some(sid))
        assert(e.payload.contains("the final text"), s"payload must survive verbatim, got: ${e.payload.take(120)}")
        assertEquals(queued2.map(_.events).getOrElse(Nil).size, 1, "re-entry must stay idempotent (single copy)")
        assertEquals(queuedGone, None, "a deleted parent session must not receive junk files")
    }
  }

end ExternalTerminalBackstopSpec
