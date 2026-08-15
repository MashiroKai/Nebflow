package nebflow.core

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.llm.FallbackExhaustedError
import nebflow.shared.*

import java.nio.file.{Files => JFiles}

class OnboardingServiceSpec extends FunSuite:

  private var tempRoot: os.Path = null
  private var savedRoot: os.Path = null

  override def beforeEach(context: munit.BeforeEach): Unit =
    savedRoot = PathUtil.dataRoot
    tempRoot = os.Path(JFiles.createTempDirectory("nb-onboarding").toString)
    PathUtil.setDataRoot(tempRoot)

  override def afterEach(context: munit.AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    if os.exists(tempRoot) then os.remove.all(tempRoot)

  // ===== state machine =====

  test("readState returns None when no marker file exists (fresh install)") {
    assertEquals(OnboardingService.readState().unsafeRunSync(), None)
  }

  test("writeState/readState round-trips all three states") {
    import OnboardingService.OnboardingState
    for st <- List(OnboardingState.Pending, OnboardingState.Done, OnboardingState.Skipped) do
      OnboardingService.writeState(st).unsafeRunSync()
      assertEquals(OnboardingService.readState().unsafeRunSync(), Some(st))
  }

  test("corrupt onboarding.json degrades to Pending, never throws") {
    os.write(OnboardingService.statePath, "{ not json !!!")
    assertEquals(OnboardingService.readState().unsafeRunSync(), Some(OnboardingService.OnboardingState.Pending))
  }

  test("unknown state string degrades to Pending") {
    os.write(OnboardingService.statePath, """{"state":"wat"}""")
    assertEquals(OnboardingService.readState().unsafeRunSync(), Some(OnboardingService.OnboardingState.Pending))
  }

  test("onboarding state resolves via `def` path — dataRoot swap takes effect") {
    // guards the object-frozen-val trap: statePath must track a later setDataRoot
    OnboardingService.writeState(OnboardingService.OnboardingState.Done).unsafeRunSync()
    val other = os.Path(JFiles.createTempDirectory("nb-onboarding2").toString)
    try
      PathUtil.setDataRoot(other)
      assertEquals(OnboardingService.readState().unsafeRunSync(), None)
    finally
      PathUtil.setDataRoot(tempRoot)
      os.remove.all(other)
  }

  // ===== probeOkAt hard gate (qa follow-up, server-side) =====

  test("gate: setState(done) without probeOkAt is HARD-rejected, file stays pending") {
    import OnboardingService.OnboardingState
    OnboardingService.writeState(OnboardingState.Pending).unsafeRunSync()
    OnboardingService.setState(OnboardingState.Done).unsafeRunSync() match
      case Left(reason) =>
        assert(reason.contains("probe"))
      case Right(_) => fail("done without probe must be rejected")
    // state on disk untouched
    assertEquals(OnboardingService.readStored().unsafeRunSync().map(_.state), Some(OnboardingState.Pending))
  }

  test("gate: successful probeLlm records probeOkAt, then done passes") {
    import OnboardingService.OnboardingState
    val ok = OnboardingService.probeLlm(fakeLlm(Right(okResponse("prov-a")))).unsafeRunSync()
    assert(ok.ok)
    val stored = OnboardingService.readStored().unsafeRunSync().get
    assertEquals(stored.state, OnboardingState.Pending) // probe does not change state
    assert(stored.probeOkAt.isDefined)
    // done now goes through
    OnboardingService.setState(OnboardingState.Done).unsafeRunSync() match
      case Right(applied) => assertEquals(applied, OnboardingState.Done)
      case Left(err) => fail(s"done after probe must pass: $err")
    // probeOkAt survives the done write (read-modify-write)
    assertEquals(OnboardingService.readStored().unsafeRunSync().flatMap(_.probeOkAt), stored.probeOkAt)
  }

  test("gate: any setState never erases a recorded probeOkAt") {
    import OnboardingService.OnboardingState
    OnboardingService.probeLlm(fakeLlm(Right(okResponse("p")))).unsafeRunSync()
    val probeOkAt = OnboardingService.readStored().unsafeRunSync().flatMap(_.probeOkAt)
    assert(probeOkAt.isDefined)
    // write pending, then skipped — probeOkAt must persist through each
    OnboardingService.setState(OnboardingState.Pending).unsafeRunSync()
    assertEquals(OnboardingService.readStored().unsafeRunSync().flatMap(_.probeOkAt), probeOkAt)
    OnboardingService.setState(OnboardingState.Skipped).unsafeRunSync()
    assertEquals(OnboardingService.readStored().unsafeRunSync().flatMap(_.probeOkAt), probeOkAt)
    // skipped -> done also allowed once probe is on record
    assertEquals(OnboardingService.setState(OnboardingState.Done).unsafeRunSync().isRight, true)
  }

  test("gate: invalid-state-value JSON degrades to Pending but keeps parseable probeOkAt") {
    os.write(OnboardingService.statePath, """{"state":"garbage","probeOkAt":123456}""")
    val stored = OnboardingService.readStored().unsafeRunSync().get
    assertEquals(stored.state, OnboardingService.OnboardingState.Pending)
    assertEquals(stored.probeOkAt, Some(123456L))
    // and done still passes because the gate record survived
    assertEquals(OnboardingService.setState(OnboardingService.OnboardingState.Done).unsafeRunSync().isRight, true)
  }

  test("gate: failed probeLlm does NOT record probeOkAt") {
    import OnboardingService.OnboardingState
    val bad = OnboardingService.probeLlm(fakeLlm(Left(new RuntimeException("boom")))).unsafeRunSync()
    assert(!bad.ok)
    assertEquals(OnboardingService.readStored().unsafeRunSync().flatMap(_.probeOkAt), None)
    assertEquals(OnboardingService.setState(OnboardingState.Done).unsafeRunSync().isLeft, true)
  }

  // ===== probeLlm contract shape =====

  private def fakeLlm(result: Either[Throwable, LlmResponse]): LlmHandle[IO] = new LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = result.fold(IO.raiseError, IO.pure)
    def sendStream(req: LlmRequest, onAttempt: Option[FallbackAttempt => IO[Unit]] = None): fs2.Stream[IO, StreamChunk] =
      fs2.Stream.raiseError[IO](new RuntimeException("not used in probe"))

  private def okResponse(providerId: String): LlmResponse =
    LlmResponse(
      reply = "ok",
      toolCalls = Nil,
      usage = None,
      meta = LlmMeta(sessionId = "llm-probe", agentId = "llm-probe", providerId = providerId, model = "m", durationMs = 1)
    )

  test("probeLlm sends minimal User message request through the global handle") {
    var captured: LlmRequest = null
    val spy = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] =
        captured = req; IO.pure(okResponse("prov-a"))
      def sendStream(req: LlmRequest, onAttempt: Option[FallbackAttempt => IO[Unit]] = None): fs2.Stream[IO, StreamChunk] =
        fs2.Stream.empty
    val res = OnboardingService.probeLlm(spy).unsafeRunSync()
    assert(res.ok)
    assertEquals(res.provider, Some("prov-a"))
    // contract shape
    assertEquals(captured.sessionId, "llm-probe")
    assertEquals(captured.agentId, "llm-probe")
    assertEquals(captured.maxTokens, Some(8))
    assertEquals(captured.messages.size, 1)
    assertEquals(captured.messages.head.role, MessageRole.User)
    assertEquals(captured.messages.head.content, Left("回复 ok"))
    assert(captured.tools.isEmpty)
  }

  test("probeLlm attributes FallbackExhaustedError per provider") {
    val attempts = List(
      FallbackAttempt(
        providerId = "zhipu",
        model = "glm-5",
        reason = Some(FailoverReason.Auth),
        permanence = Some(ErrorPermanence.Permanent),
        durationMs = 10,
        retriesUsed = 0,
        timestamp = "2026-08-16T00:00:00Z",
        message = Some("401 unauthorized")
      )
    )
    val res = OnboardingService.probeLlm(fakeLlm(Left(new FallbackExhaustedError(attempts)))).unsafeRunSync()
    assert(!res.ok)
    assertEquals(res.provider, Some("zhipu"))
    assert(res.error.exists(_.contains("zhipu")))
    assert(res.error.exists(_.contains("Auth")))
  }

  test("probeLlm surfaces plain exception message") {
    val res = OnboardingService.probeLlm(fakeLlm(Left(new RuntimeException("boom 401")))).unsafeRunSync()
    assert(!res.ok)
    assertEquals(res.error, Some("boom 401"))
  }

end OnboardingServiceSpec
