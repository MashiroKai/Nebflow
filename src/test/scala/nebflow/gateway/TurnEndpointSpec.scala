package nebflow.gateway

import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, Behaviors}
import nebflow.actor.{AgentCommand, AgentDef}
import nebflow.agent.{AgentActor, AgentLibrary, SharedResources, SubAgentTaskStore}
import nebflow.core.FileChangeTracker
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk, ThinkingConfig, UiMessage}
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
          scripts.lift(idx).getOrElse(Stream.raiseError[IO](new RuntimeException(s"unexpected LLM call #$idx")))
        )

  end RecordingLlm

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

  /**
   * Spawn a real root agent whose wsSend is the production chain:
   * SessionRecorder (UiMessage flush) over wsHub.broadcast.
   */
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

  end spawnAgent

  /**
   * The dispatch callback production wires in: persist the user bubble,
   * then ImmediateInput the agent (mirror of wsRoutes.dispatchUserText).
   */
  private def dispatch(
    agent: nebflow.actor.ActorRef[AgentCommand],
    sessionStore: SessionStore
  ): (String, String) => IO[Unit] = (sid, content) =>
    sessionStore.appendUiMessages(
      sid,
      List(UiMessage.User(content, Nil, timestamp = System.currentTimeMillis()))
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
      val llm = new RecordingLlm(
        List(
          Stream(StreamChunk.TextDelta("the answer is 42"), StreamChunk.Done(None, None))
        )
      )
      val sessionStore = new SessionStore(tmp / "sessions", tmp / "tasks")
      val wsHub = new WsHub()
      val sid = s"turn-happy-${System.currentTimeMillis()}"
      val program = for
        resources <- mkResources(system, tmp, llm, sessionStore)
        agent <- spawnAgent(system, resources, sessionStore, wsHub, sid, llm)
        resp <- TurnEndpoint.runTurn(wsHub, sessionStore, dispatch(agent, sessionStore), sid, "what is the answer?", 30)
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
      // v2 冻结式错误恢复（20260824_frozen-error-recovery-plan §1.6）：transient
      // 错误（"provider exploded" → Unknown → Transient）不再 fatal → 进入
      // ErrorFrozen（退避到期自动续跑）。fatal 路径只剩 Permanent/Fatal——
      // 本测试改用 timeout（classifyError → Timeout → Permanent）验证 fatal 语义。
      val llm = new RecordingLlm(
        List(
          Stream.raiseError[IO](new RuntimeException("request timeout after 30s"))
        )
      )
      val sessionStore = new SessionStore(tmp / "sessions", tmp / "tasks")
      val wsHub = new WsHub()
      val sid = s"turn-error-${System.currentTimeMillis()}"
      val program = for
        resources <- mkResources(system, tmp, llm, sessionStore)
        agent <- spawnAgent(system, resources, sessionStore, wsHub, sid, llm)
        resp <- TurnEndpoint.runTurn(wsHub, sessionStore, dispatch(agent, sessionStore), sid, "break loudly", 30)
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
        resp <- TurnEndpoint.runTurn(wsHub, sessionStore, dispatch(agent, sessionStore), sid, "hang forever", 1)
        // after the 504 the listener must be unregistered: broadcasting
        // busy=false for this session must NOT reach a stale Deferred
        // (observable only via idempotence — unregister is fire-and-mutex;
        // here we just assert the hub still functions)
        _ <- wsHub.broadcast(
          Json.obj("type" -> "sessionBusy".asJson, "sessionId" -> sid.asJson, "busy" -> false.asJson)
        )
      yield resp

      val started = System.currentTimeMillis()
      val resp = program.unsafeRunSync()
      assert(System.currentTimeMillis() - started < 15000, "timeout must fire at ~1s")
      assertEquals(resp.status, org.http4s.Status.GatewayTimeout)
      val body = bodyJson(resp)
      assertEquals(body.hcursor.downField("status").as[String].toOption, Some("timeout"))
    }
  }

  /**
   * 异常路径的注销保证（perf-closeout2 §5.4 第三条登记项）。
   *
   * 判据是**行为**不是字符串：`registerListener`（for 链首个语句）之后任一语句抛
   * 异常时，`wsHub.unregister` 必须**仍然发生**，否则每个失败 turn 永久漏一个监听
   * 者（WsHub.unregister 记的正是该泄漏形态：随后每次 broadcast 都会调用它）。
   *
   * 两段断言缺一不可：dispatch 时刻 listenerCount 必须是 1（证明监听者**确实注册
   * 了** —— 排除"根本没注册所以自然是 0"的空过），失败之后必须是 0（证明注销发生）。
   * 修前：dispatch 抛异常 ⇒ for 链直接中断 ⇒ 注销被跳过 ⇒ 第二段读 1 ⇒ 红。
   */
  test("exceptional path: an exception after registerListener still unregisters the listener") {
    withEnv("turn-exc") { (tmp, _) =>
      val sessionStore = new SessionStore(tmp / "sessions", tmp / "tasks")
      val wsHub = new WsHub()
      val sid = s"turn-exc-${System.currentTimeMillis()}"
      val listenerCountAtDispatch = Ref.unsafe[IO, Int](-1)
      val boom: (String, String) => IO[Unit] = (_, _) =>
        wsHub.listenerCount.flatMap(n => listenerCountAtDispatch.set(n)) *>
          IO.raiseError(new RuntimeException("dispatch exploded"))
      val program = for
        outcome <- TurnEndpoint.runTurn(wsHub, sessionStore, boom, sid, "boom", 30).attempt
        atDispatch <- listenerCountAtDispatch.get
        after <- wsHub.listenerCount
      yield (outcome, atDispatch, after)

      val (outcome, atDispatch, after) = program.unsafeRunSync()
      assert(outcome.isLeft, s"dispatch failure must propagate: $outcome")
      assertEquals(atDispatch, 1, "监听者必须已注册（否则本用例空过，判据不可达）")
      assertEquals(after, 0, "注销必须在异常路径也执行（否则每个失败 turn 漏一个监听者）")
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
