package nebflow.core.executor

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.AgentLibrary
import nebflow.core.mail.SessionMailbox
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

/** Durable external completion delivery, including parents without a live actor. */
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

  test("external receipts are durable and idempotent even when the parent has no live actor") {
    withTempDataRoot {
      for
        resources <- fakeResources
        parentSid = "backstop-parent"
        sid = "external-test-abc12345"
        // A persisted parent does not require a live actor.
        _ <- resources.sessionStore.getOrCreateSession(parentSid, "backstop parent")
        _ <- ExternalSessionRunner.notifyParent(
          spec(parentSid, resources),
          sid,
          ExternalSessionRunner.Outcome.Completed,
          "the final text",
          None
        )
        queued1 <- SessionMailbox.scanPending(parentSid)
        // The same stable notification is not inserted twice.
        _ <- ExternalSessionRunner.notifyParent(
          spec(parentSid, resources),
          sid,
          ExternalSessionRunner.Outcome.Completed,
          "the final text",
          None
        )
        queued2 <- SessionMailbox.scanPending(parentSid)
        // An unavailable parent retains a claimable result.
        _ <- ExternalSessionRunner.notifyParent(
          spec("gone-parent", resources),
          sid,
          ExternalSessionRunner.Outcome.Completed,
          "orphan text",
          None
        )
        queuedGone <- SessionMailbox.scanPending("gone-parent")
        _ <- resources.actorSystem.stopAll.attempt.void
      yield
        val events1 = queued1.map(_.event)
        assertEquals(events1.size, 1, s"exactly one queued terminal, got: ${events1.map(_.eventType)}")
        val e = events1.head
        assertEquals(e.eventType, "completed")
        assertEquals(e.correlationId, Some(sid))
        assert(e.payload.contains("the final text"), s"payload must survive verbatim, got: ${e.payload.take(120)}")
        assertEquals(queued2.size, 1, "re-entry must stay idempotent (single copy)")
        assertEquals(queuedGone.size, 1, "a missing parent must not silently lose an accepted result")
    }
  }

end ExternalTerminalBackstopSpec
