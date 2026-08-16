package nebflow.gateway

import cats.effect.std.{Dispatcher, Semaphore}
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, Behaviors}
import nebflow.agent.{AgentActor, AgentCommand, AgentDef, AgentLibrary, SharedResources, SubAgentTaskStore}
import nebflow.core.FileChangeTracker
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk, UiMessage}
import org.http4s.circe.CirceEntityCodec.*

import scala.concurrent.duration.*

/**
  * TurnEndpoint tests (P0 benchmark headless — /tmp/headless-design.md §5).
  * Drives the REAL AgentActor in a real ActorSystem with a scripted LlmHandle
  * (SavePhaseZeroToolTurnSpec harness pattern), with the agent's wsSend wired
  * through a REAL SessionRecorder into a REAL WsHub — the same recording
  * chain production uses (agent wsSend = recording layer over wsHub.broadcast),
  * so the completion signal and the UiMessage flush ordering are exercised
  * end to end, not mocked.
  *
  * The WS "userMessage" ≡ "immediateInput" equivalence (same dispatch body)
  * is covered by the E2E smoke script on a live gateway, not here —
  * handleMessage is an instance method of the heavyweight WebSocketRoutes
  * class (G1: not constructible in isolation).
  */
class TurnEndpointSpec extends CatsEffectSuite:

  private class RecordingLlm(scripts: List[Stream[IO, StreamChunk]]) extends LlmHandle[IO]:
    val requests = Ref.unsafe[IO, List[LlmRequest]](Nil)
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected in this test"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream
        .eval(requests.modify { list => (req :: list, list.size) })
        .flatMap(idx =>
          scripts.lift(idx).getOrElse(Stream.raiseError[IO](
            new RuntimeException(s"unexpected LLM call #$idx"))))

  private def mkResources(
    system: ActorSystem, tmp: os.Path, llm: LlmHandle[IO], sessionStore: SessionStore
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

  /** Spawn a real root agent whose wsSend is the production chain:
    * SessionRecorder (UiMessage flush) over wsHub.broadcast. */
  private def spawnAgent(
    system: ActorSystem,
    resources: SharedResources,
    sessionStore: SessionStore,
    wsHub: WsHub,
    sessionId: String,
    llm: LlmHandle[IO]
  ): IO[nebflow.actor.ActorRef[AgentCommand]] =
    val recorder = SessionRecorder(sessionId, sessionStore, j => wsHub.broadcast(j))
    val recordingWsSend: io.circe.Json => IO[Unit] = recorder.apply
    system.spawn(
      AgentActor(
        AgentDef(name = "Bench", description = "test agent", tools = Nil, systemPrompt = ""),
        resources,
        recordingWsSend,
        depth = 0,
        parentRef = None,
        sessionId = Some(sessionId),
        sessionName = Some("turn-spec")
      ),
      s"turn-spec-$sessionId"
    )

  /** The dispatch callback production wires in: persist the user bubble,
    * then ImmediateInput the agent (mirror of wsRoutes.dispatchUserText). */
  private def dispatch(
    agent: nebflow.actor.ActorRef[AgentCommand],
    sessionStore: SessionStore
  ): (String, String) => IO[Unit] = (sid, content) =>
    sessionStore.appendUiMessages(
      sid, List(UiMessage.User(content, Nil, timestamp = System.currentTimeMillis()))
    ) *> (agent ! AgentCommand.ImmediateInput(content))

  private def bodyJson(resp: org.http4s.Response[IO]): Json =
    resp.as[Json].unsafeRunSync()

  private def withEnv[T](name: String)(test: (os.Path, ActorSystem) => T): T =
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    val system = ActorSystem(name)
    try test(tmp, system)
    finally
      PathUtil.setDataRoot(prevRoot)
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)

  test("happy path: turn completes with finalMessage from the recording chain") {
    withEnv("turn-happy") { (tmp, system) =>
      val llm = new RecordingLlm(List(
        Stream(StreamChunk.TextDelta("the answer is 42"), StreamChunk.Done(None, None))
      ))
      val sessionStore = new SessionStore(tmp / "sessions", tmp / "tasks")
      val wsHub = new WsHub()
      val sid = s"turn-happy-${System.currentTimeMillis()}"
      val program = for
        resources <- mkResources(system, tmp, llm, sessionStore)
        agent <- spawnAgent(system, resources, sessionStore, wsHub, sid, llm)
        resp <- TurnEndpoint.runTurn(
          wsHub, sessionStore, dispatch(agent, sessionStore), sid, "what is the answer?", 30)
      yield resp

      val resp = program.unsafeRunSync()
      assertEquals(resp.status, org.http4s.Status.Ok)
      val body = bodyJson(resp)
      assertEquals(body.hcursor.downField("status").as[String].toOption, Some("completed"))
      assertEquals(body.hcursor.downField("finalMessage").as[String].toOption, Some("the answer is 42"))
      // usage captured from the depth-0 done event (fields present as a JSON object)
      assert(body.hcursor.downField("usage").focus.exists(_.isObject), s"usage missing: $body")
      // toolCalls empty for a text-only turn
      assertEquals(body.hcursor.downField("toolCalls").as[List[Json]].toOption, Some(Nil))
      // error null
      assertEquals(body.hcursor.downField("error").as[Option[String]].toOption, Some(None))
    }
  }

  test("error path: LLM failure -> 200 with status=error and message") {
    withEnv("turn-error") { (tmp, system) =>
      val llm = new RecordingLlm(List(
        Stream.raiseError[IO](new RuntimeException("provider exploded"))
      ))
      val sessionStore = new SessionStore(tmp / "sessions", tmp / "tasks")
      val wsHub = new WsHub()
      val sid = s"turn-error-${System.currentTimeMillis()}"
      val program = for
        resources <- mkResources(system, tmp, llm, sessionStore)
        agent <- spawnAgent(system, resources, sessionStore, wsHub, sid, llm)
        resp <- TurnEndpoint.runTurn(
          wsHub, sessionStore, dispatch(agent, sessionStore), sid, "break loudly", 30)
      yield resp

      val resp = program.unsafeRunSync()
      // a failed turn still TERMINATED the turn — HTTP 200 with status=error
      assertEquals(resp.status, org.http4s.Status.Ok)
      val body = bodyJson(resp)
      assertEquals(body.hcursor.downField("status").as[String].toOption, Some("error"))
      assert(
        body.hcursor.downField("error").as[String].exists(_.nonEmpty),
        s"error message must be captured: $body"
      )
    }
  }

  test("timeout: hung LLM -> 504 with status=timeout; listener cleaned up") {
    withEnv("turn-timeout") { (tmp, system) =>
      val llm = new RecordingLlm(List(Stream.never[IO]))
      val sessionStore = new SessionStore(tmp / "sessions", tmp / "tasks")
      val wsHub = new WsHub()
      val sid = s"turn-timeout-${System.currentTimeMillis()}"
      val program = for
        resources <- mkResources(system, tmp, llm, sessionStore)
        agent <- spawnAgent(system, resources, sessionStore, wsHub, sid, llm)
        resp <- TurnEndpoint.runTurn(
          wsHub, sessionStore, dispatch(agent, sessionStore), sid, "hang forever", 1)
        // after the 504 the listener must be unregistered: broadcasting
        // busy=false for this session must NOT reach a stale Deferred
        // (observable only via idempotence — unregister is fire-and-mutex;
        // here we just assert the hub still functions)
        _ <- wsHub.broadcast(Json.obj(
          "type" -> "sessionBusy".asJson, "sessionId" -> sid.asJson, "busy" -> false.asJson))
      yield resp

      val started = System.currentTimeMillis()
      val resp = program.unsafeRunSync()
      assert(System.currentTimeMillis() - started < 15000, "timeout must fire at ~1s")
      assertEquals(resp.status, org.http4s.Status.GatewayTimeout)
      val body = bodyJson(resp)
      assertEquals(body.hcursor.downField("status").as[String].toOption, Some("timeout"))
    }
  }

  test("gate: second concurrent turn on the same session -> 409; release reopens") {
    val sid = s"gate-spec-${System.currentTimeMillis()}"
    val ok = org.http4s.Response[IO](org.http4s.Status.Ok)
    val program = for
      // first holder: hangs until cancelled (like a real in-flight turn)
      firstFiber <- TurnEndpoint.gated(sid)(IO.never[org.http4s.Response[IO]]).start
      _ <- IO.sleep(100.millis) // let the gate acquire
      second <- TurnEndpoint.gated(sid)(IO.pure(ok))
      _ <- firstFiber.cancel // guarantee releases the gate
      _ <- IO.sleep(100.millis)
      third <- TurnEndpoint.gated(sid)(IO.pure(ok)) // gate is free again
    yield (second.status, third.status)

    val (secondStatus, thirdStatus) = program.unsafeRunSync()
    assertEquals(secondStatus, org.http4s.Status.Conflict)
    assertEquals(thirdStatus, org.http4s.Status.Ok)
  }

end TurnEndpointSpec
