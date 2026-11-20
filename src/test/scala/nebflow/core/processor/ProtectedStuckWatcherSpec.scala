package nebflow.core.processor

import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, AgentCommand, AgentKind, AgentRecord, AgentStatus, Behavior, Behaviors}
import nebflow.agent.{AgentLibrary, ExecutionCommitStore, SharedResources}
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.core.{ExecutionCommitPort, FileChangeTracker, RateLimiter, SessionStore}
import nebflow.gateway.WsHub
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.*

import scala.concurrent.duration.*

/** A protected stuck run is left to explicit recovery; the watchdog never replays it. */
class ProtectedStuckWatcherSpec extends CatsEffectSuite:

  private val watchdogLogTmp = os.temp.dir(prefix = "protected-stuck-watchdog")
  override def beforeAll(): Unit = WatchdogEventLog.setLogDirForTest(watchdogLogTmp.toNIO)
  override def afterAll(): Unit = WatchdogEventLog.resetLogDirForTest()

  private def recordingLoop(received: Ref[IO, List[AgentCommand]]): Behavior[AgentCommand] =
    def loop: Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand](cmd => received.update(_ :+ cmd).as(loop))
    loop

  test("watchdog hard cancel records fresh session disposition and never automatically replays") {
    val system = ActorSystem("protected-stuck-watcher")
    val llm = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] =
        IO.raiseError(new IllegalStateException("protected watchdog must not call the model"))
      def sendStream(req: LlmRequest, onAttempt: Option[FallbackAttempt => IO[Unit]] = None): Stream[IO, StreamChunk] =
        Stream.raiseError[IO](new IllegalStateException("protected watchdog must not call the model"))
    for
      root <- IO.blocking(os.temp.dir(prefix = "protected-stuck-"))
      _ <- (IO.blocking(os.makeDir.all(root / "sessions")) *>
        ExecutionCommitStore.resource((root / "sessions").toNIO).use { store =>
          val session = "protected-stuck"
          val admission = ExecutionAdmission(
            session,
            "run",
            "commit",
            "request",
            "attempt",
            "incarnation",
            "owner",
            "business",
            "audit",
            Json.fromString("initial"),
            "input"
          )
          for
            bound <- ExecutionCommitPort.admitHandle(store, admission)
            handle = bound.toOption.get
            _ <- store.transition(
              ExecutionTransition(
                session,
                "run",
                "start",
                "start",
                handle.ref,
                "attempt",
                "incarnation",
                ExecutionEvent.AttemptStarted
              )
            )
            received <- Ref.of[IO, List[AgentCommand]](Nil)
            actor <- system.spawn(recordingLoop(received), "protected-stuck-actor")
            dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
            limiter <- RateLimiter.create()
            tracker <- FileChangeTracker.create(root.toString)
            locks <- FileLockManager.create
            thinking <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
            overrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
            muted <- Ref.of[IO, Boolean](false)
            resources = SharedResources(
              llm = llm,
              dispatcher = dispatcher,
              sessionStore = SessionStore(root / "sessions", root / "tasks"),
              projectRoot = root,
              thinkingConfigRef = thinking,
              rateLimiter = limiter,
              fileChangeTracker = tracker,
              contextWindow = 100_000,
              agentLibrary = new AgentLibrary(root / "agents"),
              taskStore = FileTaskStore,
              historyArchiver = null,
              fileLockManager = locks,
              sessionModelOverrides = overrides,
              providerRegistry = null,
              healthMonitor = ProviderHealthMonitor(null),
              actorSystem = system,
              voiceMutedRef = muted,
              executionCommitPort = store
            )
            now = System.currentTimeMillis()
            threshold = 10 * 60 * 1000L
            record = AgentRecord(
              sessionId = session,
              ref = actor,
              kind = AgentKind.Delegate,
              rootSessionId = "root",
              status = AgentStatus.Processing,
              startedAt = now - 2 * threshold,
              lastActivityMs = now - threshold - 1000
            )
            _ <- resources.agentRegistry.set(Map(session -> record))
            _ <- TaskStuckWatcher.scan(resources, new WsHub(), threshold, cwdProbe = _ => IO.pure(Some(true)))
            commands <- received.get
            chain <- store.observeChain(session, "run")
            _ <- system.stopAll
          yield
            assert(commands.isEmpty, s"the protected watchdog must not issue automatic commands: $commands")
            chain match
              case ExecutionChainObservation.Verified(state) =>
                assertEquals(state.phase, "active", "the authority keeps the existing attempt")
              case other => fail(s"unexpected chain: $other")
          end for
        }).guarantee(system.stopAll *> IO.blocking(os.remove.all(root)))
    yield ()
    end for
  }
end ProtectedStuckWatcherSpec
