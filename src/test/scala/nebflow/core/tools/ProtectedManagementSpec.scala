package nebflow.core.tools

import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.{AgentKind, AgentRecord, ActorSystem}
import nebflow.agent.{AgentLibrary, ExecutionCommitStore, ExecutionRunner, SharedResources}
import nebflow.core.task.FileTaskStore
import nebflow.core.{ExecutionCommitPort, FileChangeTracker, RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.*

import scala.concurrent.duration.*

/** Management entrypoints must route a protected target through the authority. */
class ProtectedManagementSpec extends CatsEffectSuite:

  test("protected cancel commits before stopping and legacy restart refuses without replay") {
    val system = ActorSystem("protected-management")
    val llm = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] =
        IO.raiseError(new IllegalStateException("management must not call the model"))
      def sendStream(req: LlmRequest, onAttempt: Option[FallbackAttempt => IO[Unit]] = None):
          Stream[IO, StreamChunk] =
        Stream.raiseError[IO](new IllegalStateException("management must not call the model"))
    for
      root <- IO.blocking(os.temp.dir(prefix = "protected-management-"))
      _ <- (IO.blocking(os.makeDir.all(root / "sessions")) *>
        ExecutionCommitStore.resource((root / "sessions").toNIO).use { store =>
          val session = "protected-managed"
          val admission = ExecutionAdmission(session, "run", "commit", "request", "attempt", "incarnation",
            "owner", "business", "audit", Json.fromString("initial"), "input")
          for
            bound <- ExecutionCommitPort.admitHandle(store, admission)
            handle = bound.toOption.get
            _ <- store.transition(ExecutionTransition(session, "run", "start", "start", handle.ref,
              "attempt", "incarnation", ExecutionEvent.AttemptStarted))
            (registered, release) <- store.registerRunner(
              ExecutionRunner.Attempt(session, "run", "attempt", "incarnation")).allocated
            runner = registered.toOption.get
            dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
            limiter <- RateLimiter.create()
            tracker <- FileChangeTracker.create(root.toString)
            locks <- FileLockManager.create
            thinking <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
            overrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
            muted <- Ref.of[IO, Boolean](false)
            sessionStore = SessionStore(root / "sessions", root / "tasks")
            _ = sessionStore.installExecutionAuthority(store)
            resources = SharedResources(
              llm = llm, dispatcher = dispatcher, sessionStore = sessionStore,
              projectRoot = root, thinkingConfigRef = thinking, rateLimiter = limiter,
              fileChangeTracker = tracker, contextWindow = 100_000, agentLibrary = new AgentLibrary(root / "agents"),
              taskStore = FileTaskStore, historyArchiver = null, fileLockManager = locks,
              sessionModelOverrides = overrides, providerRegistry = null,
              healthMonitor = ProviderHealthMonitor(null), actorSystem = system, voiceMutedRef = muted,
              executionCommitPort = store, protectedExecutions = Map(session -> handle),
              protectedRunners = Map(session -> runner))
            record = AgentRecord(sessionId = session, ref = null, kind = AgentKind.Delegate,
              rootSessionId = "root", status = nebflow.actor.AgentStatus.Processing)
            cancelled <- AgentControlTool.doCancel(resources, record, "operator")
            afterCancel <- store.observeChain(session, "run")
            restarted <- AgentControlTool.doRestart(resources, ToolContext(projectRoot = root.toString), record,
              "legacy restart")
            deleted <- sessionStore.deleteSession(session).attempt
            stopped <- runner.awaitStopped(2.seconds)
            _ <- release
            _ <- system.stopAll
          yield
            assert(cancelled.toOption.exists(_.contains("Cancellation committed")), s"cancel: $cancelled")
            afterCancel match
              case ExecutionChainObservation.Verified(state) => assertEquals(state.phase, "cancelled")
              case other => fail(s"unexpected chain after cancel: $other")
            assert(restarted.isLeft, "legacy restart must refuse a protected run")
            assert(restarted.left.toOption.exists(_.message.contains("exact-ref recovery")), s"restart: $restarted")
            assert(deleted.isLeft, "physical delete must refuse protected evidence")
            assert(deleted.left.toOption.exists(_.getMessage.contains("protected-or-unknown-execution")), s"delete: $deleted")
            assert(stopped, "cancel must distinguish and report a confirmed owned runner stop")
        }).guarantee(system.stopAll *> IO.blocking(os.remove.all(root)))
    yield ()
  }
