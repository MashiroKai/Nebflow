package nebflow.agent

import cats.effect.{IO, Ref}
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import nebflow.actor.*
import nebflow.core.{InjectionChannel, RateLimiter, SessionStore}
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.{NebflowLogger, ThinkingConfig}
import munit.FunSuite
import scala.concurrent.duration.FiniteDuration

class InboxMirrorDeliveryRiskSpec extends FunSuite:
  test("actor emission mirrors after collapsed delivery and failed broadcast") {
    val tmp = os.temp.dir(prefix = "mirror-delivery-risk")
    val system = ActorSystem("mirror-delivery-risk")
    val program = Dispatcher.parallel[IO].use { dispatcher =>
      for
        ref <- system.spawn(Behaviors.receiveMessage[AgentCommand](_ => IO.pure(Behaviors.stopped)), "sink")
        fibers <- Ref.of[IO, Map[String, cats.effect.Fiber[IO, Throwable, Unit]]](Map.empty)
        children <- Ref.of[IO, List[ActorRef[?]]](Nil)
        local = LocalActorContext(ref, system, NebflowLogger.forName("test"), fibers, children)
        // Execute the exact forked production effect synchronously to provide a deterministic barrier.
        ctx = new ActorContext[AgentCommand]:
          def self = local.self
          def system = local.system
          def log = local.log
          def forkTurn(io: IO[Unit]) = io
          def cancelCurrentTurn() = local.cancelCurrentTurn()
          def spawn[A](b: Behavior[A], n: String) = local.spawn(b, n)
          def watch(r: ActorRef[?]) = local.watch(r)
          def unwatch(r: ActorRef[?]) = local.unwatch(r)
          def scheduleOnce(d: FiniteDuration, m: AgentCommand) = local.scheduleOnce(d, m)
        rate <- RateLimiter.create()
        tracker <- nebflow.core.FileChangeTracker.create(os.pwd.toString)
        locks <- FileLockManager.create
        thinking <- IO.ref(ThinkingConfig())
        overrides <- IO.ref(Map.empty[String, ModelCandidate])
        muted <- IO.ref(false)
        store = SessionStore(tmp / "sessions", tmp / "tasks")
        resources = SharedResources(
          llm = null, dispatcher = dispatcher, sessionStore = store, projectRoot = os.pwd,
          thinkingConfigRef = thinking, rateLimiter = rate, fileChangeTracker = tracker,
          contextWindow = 100000, agentLibrary = new AgentLibrary(tmp / "agents"),
          taskStore = FileTaskStore, historyArchiver = HistoryArchiver.fileSystem(tmp / "archives"),
          fileLockManager = locks, sessionModelOverrides = overrides, providerRegistry = null,
          healthMonitor = ProviderHealthMonitor(null), actorSystem = system,
          subAgentTaskStore = new SubAgentTaskStore(tmp / "subagent"), voiceMutedRef = muted
        )
        _ <- resources.agentRegistry.set(Map("delegate-A" -> AgentRecord(
          sessionId = "delegate-A", ref = ref, kind = AgentKind.Root,
          rootSessionId = "root-1", status = AgentStatus.Idle)))
        _ <- IO(InjectionChannel.resetForTest())
        _ <- AgentActor.emitInjectedUserEvent(resources, _ => IO.unit, Some("root-1"), "dedup probe", "mail", dedup = true)(using ctx)
        _ <- AgentActor.emitInjectedUserEvent(resources, _ => IO.unit, Some("root-1"), "dedup probe", "mail", dedup = true)(using ctx)
        root <- store.getUiMessages("root-1", 0, 0)
        mirror <- store.getUiMessages("delegate-A", 0, 0)
        _ <- IO { assertEquals(root._1.size, 1); assertEquals(mirror._1.size, 2) }
        _ <- AgentActor.emitInjectedUserEvent(resources, _ => IO.raiseError(new RuntimeException("send failed")), Some("root-1"), "failure probe", "mail")(using ctx)
        root2 <- store.getUiMessages("root-1", 0, 0)
        mirror2 <- store.getUiMessages("delegate-A", 0, 0)
        _ <- IO { assertEquals(root2._1.size, 2); assertEquals(mirror2._1.size, 3) }
      yield ()
    }.guarantee(system.stopAll)
    try program.unsafeRunSync()
    finally os.remove.all(tmp)
  }
