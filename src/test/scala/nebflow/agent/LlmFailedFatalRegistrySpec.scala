package nebflow.agent

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.core.FileChangeTracker
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.schedule.FreezeScheduleConfig
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/** F1 (2026-08-29, loop-detected report §4.1/§6): the LlmFailed FATAL path
  * (LoopDetectedError — non-retryable, non-freezable) never wrote the
  * agentRegistry back — the record stayed status=Processing (the crashed
  * round's loop-counter touch was the last write), so TaskStuckWatcher
  * flagged a zombie every 30s until a human restarted the actor (08-28:
  * qa-frontend 609s / Backend 605s warning chains).
  *
  * Acceptance (binary, report §6-F1.1): after a fatal LlmFailed the registry
  * record's status is NOT Processing.
  */
class LlmFailedFatalRegistrySpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 90.seconds

  /** LLM that always dies with LoopDetectedError — the report's fatal shape. */
  private class LoopLlm(counter: cats.effect.Ref[IO, Int]) extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected in this test"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(counter.update(_ + 1)) >>
        Stream.eval(IO.raiseError(
          new LoopDetectedError("loop-detected: same tool call repeated 61 rounds")
        )).drain

  private def mkResources(
    system: ActorSystem,
    tmp: os.Path,
    llm: LlmHandle[IO]
  ): IO[SharedResources] =
    for
      dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- IO.ref(ThinkingConfig())
      modelOverrides <- IO.ref(Map.empty[String, ModelCandidate])
      voiceMuted <- IO.ref(false)
      schedRef <- IO.ref(FreezeScheduleConfig(enabled = false))
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
      historyArchiver = HistoryArchiver.fileSystem(tmp / "archives"),
      fileLockManager = fileLocks,
      sessionModelOverrides = modelOverrides,
      providerRegistry = null,
      healthMonitor = ProviderHealthMonitor(null),
      actorSystem = system,
      subAgentTaskStore = new SubAgentTaskStore(tmp / "subagent-tasks"),
      voiceMutedRef = voiceMuted,
      freezeScheduleRef = schedRef,
      freezeSkipUntilRef = IO.ref(None).unsafeRunSync()
    )

  private def rootDef: AgentDef =
    AgentDef(name = "Nebula", description = "test", tools = List("Read"), systemPrompt = "", category = "")

  private def waitUntil(deadlineMs: Long)(cond: IO[Boolean]): IO[Unit] =
    cond.flatMap {
      case true => IO.unit
      case false =>
        if System.currentTimeMillis() > deadlineMs then
          IO.raiseError(new RuntimeException("waitUntil timeout"))
        else IO.sleep(100.millis) *> waitUntil(deadlineMs)(cond)
    }

  private def hasEvent(events: cats.effect.Ref[IO, List[Json]], tpe: String): IO[Boolean] =
    events.get.map(_.exists(_.hcursor.downField("type").as[String].contains(tpe)))

  test("F1: fatal LlmFailed writes the registry back — status leaves Processing") {
    val system = ActorSystem("llmfail-f1")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      val program = for
        counter <- IO.ref(0)
        events <- IO.ref(List.empty[Json])
        resources <- mkResources(system, tmp, LoopLlm(counter))
        sid = "llmfail-f1-agent"
        ref <- system.spawn(
          AgentActor(
            agentDef = rootDef,
            resources = resources,
            wsSend = json => events.update(_ :+ json),
            depth = 0,
            sessionId = Some(sid),
            sessionName = Some("f1")
          ),
          sid
        )
        // Simulate the crashed turn's last registry write: mid-turn the
        // loop-counter touch leaves status=Processing (report §4.1 note).
        _ <- resources.agentRegistry.update(
          _ + (sid -> AgentRecord(sid, ref, AgentKind.Root, sid, None)
            .copy(status = AgentStatus.Processing))
        )
        _ <- ref ! AgentCommand.UserInput("trigger fatal loop-detected turn")
        _ <- waitUntil(System.currentTimeMillis() + 10_000L)(
          resources.agentRegistry.get.map(_.get(sid).exists(_.status != AgentStatus.Processing))
        )
        regStatus <- resources.agentRegistry.get.map(_.get(sid).map(_.status))
        _ = assert(
          regStatus.contains(AgentStatus.Idle),
          s"registry must leave Processing on the fatal path, got $regStatus"
        )
        errSeen <- hasEvent(events, "error")
        _ = assert(errSeen, "fatal path must emit the WS error event")
      yield ()
      program.unsafeRunSync()
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
  }
end LlmFailedFatalRegistrySpec
