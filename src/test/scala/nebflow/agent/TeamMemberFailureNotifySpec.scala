package nebflow.agent

import cats.effect.std.{Dispatcher, Semaphore}
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, ActorRef, Behavior, Behaviors}
import nebflow.core.FileChangeTracker
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * P0-3 (dual-DOWN diagnosis 2026-08-15): a Mail team member's turn is driven
 * by UserInput(replyTo=None), so when its LLM call failed permanently the
 * AgentEvent.Failed notification was a no-op — the failure evaporated and the
 * team lead kept waiting for a [RESULT] that would never come (53-minute
 * mutual-wait deadlock). The LlmFailed permanent branch must now notify
 * parentRef with an ExternalEvent("failed") carrying the same metadata
 * contract as BackoffSupervisor (failedSessionId / retryable / failureType),
 * so the lead's re-delegate system-reminder fires.
 *
 * Drives the REAL AgentActor in a real ActorSystem — only the LlmHandle is
 * faked (permanent auth error).
 */
class TeamMemberFailureNotifySpec extends CatsEffectSuite:

  // 401-style message → classifyError = Permanent → no llm-fail-retry
  private class FakeLlm extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected in this test"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.raiseError[IO](new RuntimeException("401 unauthorized: invalid api key"))

  private def mkResources(system: ActorSystem, tmp: os.Path, llm: LlmHandle[IO]): IO[SharedResources] =
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
      sessionStore = SessionStore(tmp / "sessions", tmp / "tasks"),
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

  test("permanent LlmFailure of a replyTo=None member turn notifies parentRef with failed ExternalEvent") {
    val system = ActorSystem("team-member-fail-test")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    val dataRoot = tmp / "data"
    IO.delay(PathUtil.setDataRoot(dataRoot)).bracket { _ =>
      // Probe: the "team lead" — collects every AgentCommand it is sent.
      def probe(ref: Ref[IO, List[AgentCommand]]): Behavior[AgentCommand] =
        Behaviors.receiveMessage[AgentCommand](m => ref.update(_ :+ m).as(probe(ref)))
      for
        received <- IO.ref(List.empty[AgentCommand])
        resources <- mkResources(system, tmp, new FakeLlm)
        memberSid = "team-member-sess-1"
        memberDef = AgentDef(
          name = "Backend",
          description = "test member",
          tools = List("Read"),
          systemPrompt = ""
        )
        probeRef <- system.spawn(probe(received), "lead-probe")
        member <- system.spawn(
            AgentActor(
              agentDef = memberDef,
              resources = resources,
              wsSend = _ => IO.unit,
              depth = 1,
              parentRef = Some(probeRef),
              sessionId = Some(memberSid),
              sessionName = Some("Backend"),
              expectsMail = true
            ),
            "member-backend"
          )
        // Mail member turns arrive exactly like this: UserInput with replyTo=None.
        // (ActorRef.! returns IO[Unit] — flatMap it directly, wrapping it in
        // IO.delay would discard the tell entirely.)
        _ <- member ! AgentCommand.UserInput("finish the W2 report")
        _ <- waitUntil(10.seconds)(
          received.get.map(_.exists {
            case AgentCommand.ExternalEvent(_, eventType, _, _, _) => eventType == "failed"
            case _ => false
          })
        )
        events <- received.get
        _ <- system.stopAll.attempt.void
        failed = events.collect { case e: AgentCommand.ExternalEvent => e }
          .filter(_.eventType == "failed")
      yield
        assertEquals(failed.size, 1, s"exactly one failed event, got: ${events.map(_.getClass.getSimpleName)}")
        val ev = failed.head
        assertEquals(ev.source, "team")
        assert(ev.payload.contains("Backend"), s"payload must name the member: ${ev.payload}")
        val meta = ev.metadata
        assertEquals(meta("failedSessionId").flatMap(_.asString), Some(memberSid))
        assertEquals(meta("retryable").flatMap(_.asBoolean), Some(true))
        assertEquals(meta("failureType").flatMap(_.asString), Some("LlmFailed"))
        assertEquals(meta("agentName").flatMap(_.asString), Some("Backend"))
        assertEquals(ev.correlationId, Some(memberSid))
    } { _ =>
      IO.delay(PathUtil.setDataRoot(prevRoot)) *>
        system.stopAll.attempt.void *>
        IO.delay(if os.exists(tmp) then os.remove.all(tmp)).attempt.void
    }
  }
end TeamMemberFailureNotifySpec
