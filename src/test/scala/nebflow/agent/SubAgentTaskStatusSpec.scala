package nebflow.agent

import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, ActorRef, Behavior, Behaviors}
import nebflow.core.FileChangeTracker
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * Regression tests for the sub-agent task status bug: BackoffSupervisor used
 * to hardcode "" as parentSessionId in its updateStatus calls, so status
 * updates landed in a bogus `.json` file and the real parent session's task
 * stayed "running" forever.
 *
 * These tests drive the REAL supervisor behavior (spawned in a real
 * ActorSystem) against a REAL SubAgentTaskStore and assert the task file
 * under the correct parent session transitions out of "running".
 */
class SubAgentTaskStatusSpec extends CatsEffectSuite:

  // Never called on the code paths under test — the supervisor only touches
  // subAgentTaskStore / agentRegistry / wsSend.
  private val fakeLlm = new LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("fake llm not expected here"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.raiseError[IO](new RuntimeException("fake llm not expected here"))

  // A child that ignores every command and stays alive (crash-free).
  private lazy val ignoreChild: Behavior[AgentCommand] =
    Behaviors.receiveMessage[AgentCommand](_ => IO.pure(ignoreChild))

  private def mkResources(system: ActorSystem, tmp: os.Path): IO[SharedResources] =
    for
      dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
    yield SharedResources(
      llm = fakeLlm,
      dispatcher = dispatcher,
      sessionStore = SessionStore(os.temp.dir(), os.temp.dir()),
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
      // Not touched on supervisor paths (same pattern as HealthMonitorSpec).
      providerRegistry = null,
      healthMonitor = ProviderHealthMonitor(null),
      actorSystem = system,
      subAgentTaskStore = new SubAgentTaskStore(tmp / "subagent-tasks"),
      voiceMutedRef = voiceMuted
    )

  private def mkTask(parentSid: String, childSid: String): SubAgentTask =
    SubAgentTask(
      taskId = childSid,
      parentSessionId = parentSid,
      agentName = "Worker",
      prompt = "do the thing",
      description = "test task",
      status = "running",
      retryCount = 0,
      spawnedAt = System.currentTimeMillis(),
      completedAt = None,
      lastError = None,
      source = "delegate"
    )

  /** Poll until cond holds, or fail after timeout. */
  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 50.millis)(
    cond: IO[Boolean]
  ): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true  => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError("waitUntil: condition not met in time"))
          else IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  test("Completed event updates the task under the parent session, not a bogus file") {
    val system = ActorSystem("subagent-status-test")
    val tmp = os.temp.dir()
    val parentSid = "parent-session-1"
    val childSid = "delegate-child-1"
    for
      resources <- mkResources(system, tmp)
      _ <- resources.subAgentTaskStore.recordTask(mkTask(parentSid, childSid))
      child <- system.spawn(ignoreChild, "fake-child-1")
      supervisor <- system.spawn(
        BackoffSupervisor(
          childRef = child,
          childSpawnFn = _ => IO.pure(child),
          childName = childSid,
          parentRef = None,
          description = "test task",
          agentName = "Worker",
          subagentId = childSid,
          parentSessionId = parentSid,
          resources = resources,
          initialPrompt = "do the thing",
          source = "delegate"
        ),
        "supervisor-1"
      )
      _ <- supervisor ! AgentEvent.Completed(childSid, Nil)
      _ <- waitUntil(3.seconds)(
        resources.subAgentTaskStore
          .loadTasks(parentSid)
          .map(_.exists(t => t.taskId == childSid && t.status == "completed"))
      )
      tasks <- resources.subAgentTaskStore.loadTasks(parentSid)
      _ <- system.stopAll
    yield
      val task = tasks.find(_.taskId == childSid).get
      assertEquals(task.status, "completed")
      assert(task.completedAt.isDefined, "completedAt must be set")
      // No stray ".json" litter from a blank parentSessionId
      assert(!os.exists(tmp / "subagent-tasks" / ".json"))
    end for
  }

  test("Failed event marks the task failed under the parent session") {
    val system = ActorSystem("subagent-status-test")
    val tmp = os.temp.dir()
    val parentSid = "parent-session-2"
    val childSid = "delegate-child-2"
    for
      resources <- mkResources(system, tmp)
      _ <- resources.subAgentTaskStore.recordTask(mkTask(parentSid, childSid))
      child <- system.spawn(ignoreChild, "fake-child-2")
      supervisor <- system.spawn(
        BackoffSupervisor(
          childRef = child,
          childSpawnFn = _ => IO.pure(child),
          childName = childSid,
          parentRef = None,
          description = "test task",
          agentName = "Worker",
          subagentId = childSid,
          parentSessionId = parentSid,
          resources = resources,
          initialPrompt = "do the thing",
          source = "delegate"
        ),
        "supervisor-2"
      )
      _ <- supervisor ! AgentEvent.Failed(
        childSid,
        AgentError(
          agentId = childSid,
          agentName = "Worker",
          depth = 1,
          errorType = AgentErrorType.LlmFailed,
          message = "boom: provider down",
          cause = None
        )
      )
      _ <- waitUntil(3.seconds)(
        resources.subAgentTaskStore
          .loadTasks(parentSid)
          .map(_.exists(t => t.taskId == childSid && t.status == "failed"))
      )
      tasks <- resources.subAgentTaskStore.loadTasks(parentSid)
      _ <- system.stopAll
    yield
      val task = tasks.find(_.taskId == childSid).get
      assertEquals(task.status, "failed")
      assert(task.lastError.exists(_.contains("boom")), s"lastError should carry the failure, got: ${task.lastError}")
      assert(task.completedAt.isDefined, "completedAt must be set on failure too")
      assert(!os.exists(tmp / "subagent-tasks" / ".json"))
    end for
  }

  test("blank parentSessionId is refused at the store level — no .json litter") {
    val tmp = os.temp.dir()
    val store = new SubAgentTaskStore(tmp / "subagent-tasks")
    val task = mkTask("", "delegate-child-3")
    for
      recorded <- store.recordTask(task).attempt
      loaded <- store.loadTasks("").attempt
      updated <- store
        .updateStatus("", "delegate-child-3", "completed", completedAt = Some(1L))
        .attempt
    yield
      // All three refuse with IllegalArgumentException (recordTask first
      // touches the file in loadTasks — the guard fires there).
      recorded.isLeft
      loaded.isLeft
      updated.isLeft
      assert(recorded.left.exists(_.isInstanceOf[IllegalArgumentException]))
      assert(loaded.left.exists(_.isInstanceOf[IllegalArgumentException]))
      assert(updated.left.exists(_.isInstanceOf[IllegalArgumentException]))
      assert(!os.exists(tmp / "subagent-tasks" / ".json"))
    end for
  }

  test("blank-but-whitespace parentSessionId is also refused") {
    val tmp = os.temp.dir()
    val store = new SubAgentTaskStore(tmp / "subagent-tasks")
    val task = mkTask("   ", "delegate-child-4")
    for recorded <- store.recordTask(task).attempt
    yield
      assert(recorded.left.exists(_.isInstanceOf[IllegalArgumentException]))
      assert(!os.exists(tmp / "subagent-tasks" / ".json"))
    end for
  }

  test("findRunningTasks returns empty when no running tasks exist") {
    val tmp = os.temp.dir()
    val store = new SubAgentTaskStore(tmp / "subagent-tasks")
    val parentSid = "parent-session-5"
    for
      // Empty directory
      empty <- store.findRunningTasks
      // A session file whose only task is completed
      _ <- store.recordTask(mkTask(parentSid, "delegate-child-5"))
      _ <- store.updateStatus(
        parentSid,
        "delegate-child-5",
        "completed",
        completedAt = Some(System.currentTimeMillis())
      )
      after <- store.findRunningTasks
    yield
      assertEquals(empty, Nil)
      assertEquals(after, Nil)
    end for
  }

end SubAgentTaskStatusSpec
