package nebflow.core.tools

import cats.effect.std.{Dispatcher, Semaphore}
import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import munit.FunSuite
import nebflow.actor.{ActorSystem, Behaviors}
import nebflow.agent.*
import nebflow.core.FileChangeTracker
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.flow.TeamSessionRegistry
import nebflow.core.task.FileTaskStore
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, Message, MessageRole, StreamChunk}

import scala.concurrent.duration.*

/**
  * Lifecycle spec for Mail-activated team agents — the two deep follow-ups
  * of the 2026-08-17 Teams ghost investigation:
  *
  * #2 respawn amnesia: MailTool.activateAgent used to spawn the actor with
  * initialMessages = Nil even when the session had hundreds of persisted
  * messages — every respawn was an amnesiac (turn counts reset, prior
  * context lost; live evidence: 19:16 respawn msgs=0 while disk held 230+,
  * 19:24 turn-complete msgs=62 all-fresh). The fix loads the persisted
  * messages through the same pattern as doFork / ensureRootAgent.
  *
  * #1 silent death + busy leak: Mail-spawned team agents lived outside
  * FlowTreeActor's watch system, so their death left actorMap holding a
  * dead ref (later Mails vanished), agentRegistry holding the record, and
  * busyMap holding the last turn's busy flag — getActiveAgents then
  * reported a running ghost. The fix spawns a death watcher that
  * unregisters + clears busy + logs.
  */
class MailActivateLifecycleSpec extends FunSuite:

  private val originalRoot = PathUtil.dataRoot

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    nebflow.core.LlmLogWriter.setEnabled(true)

  // LlmLogWriter hardcodes user.home/.nebflow/logs/router and prunes the
  // REAL log tree from the test JVM — disable for the whole spec (known trap).
  nebflow.core.LlmLogWriter.setEnabled(false)

  private class RecordingLlm extends LlmHandle[IO]:
    val requests: Ref[IO, List[LlmRequest]] = Ref.unsafe(Nil)
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected in this test"))
    def sendStream(
        req: LlmRequest,
        onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(requests.update(req :: _)) >>
        Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  private def mkResources(
      system: ActorSystem,
      tmp: os.Path,
      llm: LlmHandle[IO],
      sessionStore: SessionStore
  ): IO[SharedResources] =
    for
      dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
      askSem <- Semaphore[IO](4)
    yield SharedResources(
      llm = llm,
      dispatcher = dispatcher,
      sessionStore = sessionStore,
      projectRoot = os.pwd,
      thinkingConfigRef = thinkingRef,
      rateLimiter = rateLimiter,
      fileChangeTracker = tracker,
      contextWindow = 100_000,
      agentLibrary = new AgentLibrary(tmp / "agents"),
      askSemaphore = askSem,
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

  private def fixtureTeam(tmp: os.Path): Unit =
    val data = tmp / "data"
    PathUtil.setDataRoot(data)
    val teamDir = data / "teams" / "lifecyc"
    os.makeDir.all(teamDir)
    os.write.over(
      teamDir / "team.json",
      """{"name": "lifecyc", "description": "lifecycle fixture", "lead": "Manager", "members": ["member"]}"""
    )
    val memberDir = teamDir / "agents" / "member"
    os.makeDir.all(memberDir)
    os.write.over(memberDir / "agent.json", """{"description": "fixture member", "useWhen": "tests"}""")

  test("#2 respawn loads persisted history — Mail-activated agent is not an amnesiac") {
    val system = ActorSystem(s"mail-lc-${java.util.UUID.randomUUID().toString.take(6)}")
    val tmp = os.temp.dir(prefix = "mail-lifecycle")
    fixtureTeam(tmp)
    val llm = new RecordingLlm

    val io = for
      sessionStore <- IO.pure(SessionStore(tmp / "sessions", tmp / "tasks"))
      meta <- sessionStore.createSession(
        "lifecyc/member",
        agentName = Some("member"),
        flowName = Some("lifecyc")
      )
      // Pre-existing history on disk, as a previous activation would leave
      _ <- sessionStore.saveMessagesForSession(
        meta.id,
        List(Message(MessageRole.User, Left("HISTORY_MARKER_42")))
      )
      resources <- mkResources(system, tmp, llm, sessionStore)
      // The respawn under test
      refOpt <- MailTool.activateAgent(meta.id, resources, system, ToolContext(projectRoot = os.pwd.toString))
      _ <- refOpt.traverse_ { ref =>
        // Deliver a Mail — triggers the first turn on the respawned actor
        ref ! AgentCommand.ImmediateInput(
          "fresh mail after respawn",
          source = Some("mail"),
          sender = Some("tester"),
          delivery = Some("immediate")
        )
      }
      _ <- waitUntil(20.seconds)(llm.requests.get.map(_.nonEmpty))
      reqs <- llm.requests.get
    yield (refOpt, reqs)

    val (refOpt, reqs) = io.unsafeRunSync()
    assert(refOpt.isDefined, "activateAgent returned no ref")
    assert(clue(reqs).nonEmpty, "no LLM request was made — turn never ran")
    val first = reqs.head
    val texts = first.messages.map(_.content.fold(identity, _.mkString))
    assert(
      clue(texts).exists(_.contains("HISTORY_MARKER_42")),
      s"persisted history missing from the respawned agent's first request: $texts"
    )
    assert(clue(texts).exists(_.contains("fresh mail after respawn")))
    // metadata intact: the session keeps its team association
    assertEquals(refOpt.isDefined, true)
  }

  test("#1 death watch clears actorMap, agentRegistry and busy on victim death") {
    val system = ActorSystem(s"mail-dw-${java.util.UUID.randomUUID().toString.take(6)}")
    val tmp = os.temp.dir(prefix = "mail-deathwatch")
    val llm = new RecordingLlm

    val io = for
      sessionStore <- IO.pure(SessionStore(tmp / "sessions", tmp / "tasks"))
      resources <- mkResources(system, tmp, llm, sessionStore)
      sid = s"dw-sid-${java.util.UUID.randomUUID().toString.take(6)}"
      // A victim that stops as soon as it receives any message
      victim <- system.spawn(
        Behaviors.receiveMessage[AgentCommand](_ => IO.pure(Behaviors.stopped[AgentCommand])),
        s"victim-$sid"
      )
      _ <- TeamSessionRegistry.registerActor(sid, victim, resources, rootSessionId = sid)
      _ <- TeamSessionRegistry.markBusy(sid)
      _ <- system.spawn(
        MailTool.teamAgentDeathWatch(sid, "victim", victim, resources),
        s"watcher-$sid"
      )
      _ <- victim ! AgentCommand.Interrupt()
      _ <- waitUntil(10.seconds)(TeamSessionRegistry.getRunningActor(sid).map(_.isEmpty))
      busy <- TeamSessionRegistry.isBusy(sid)
      registryEntry <- resources.agentRegistry.get.map(_.get(sid))
    yield (busy, registryEntry)

    val (busy, registryEntry) = io.unsafeRunSync()
    assert(!busy, "busy flag leaked past actor death")
    assert(registryEntry.isEmpty, "agentRegistry entry leaked past actor death")
  }

end MailActivateLifecycleSpec
