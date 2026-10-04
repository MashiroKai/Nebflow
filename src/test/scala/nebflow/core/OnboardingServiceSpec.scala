package nebflow.core

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.shared.FallbackExhaustedError
import nebflow.shared.*

import java.nio.file.Files as JFiles

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
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): fs2.Stream[IO, StreamChunk] =
      fs2.Stream.raiseError[IO](new RuntimeException("not used in probe"))

  private def okResponse(providerId: String): LlmResponse =
    LlmResponse(
      reply = "ok",
      toolCalls = Nil,
      usage = None,
      meta =
        LlmMeta(sessionId = "llm-probe", agentId = "llm-probe", providerId = providerId, model = "m", durationMs = 1)
    )

  /** The answer of a SPECIFIC candidate — what a real chain returns when the
    *  picked model (or a fallback covering for it) answers. */
  private def okResponseFor(providerId: String, model: String): LlmResponse =
    okResponse(providerId).copy(meta = okResponse(providerId).meta.copy(model = model))

  test("probeLlm sends minimal User message request through the global handle") {
    var captured: LlmRequest = null
    val spy = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] =
        captured = req; IO.pure(okResponse("prov-a"))
      def sendStream(
        req: LlmRequest,
        onAttempt: Option[FallbackAttempt => IO[Unit]] = None
      ): fs2.Stream[IO, StreamChunk] =
        fs2.Stream.empty
    val res = OnboardingService.probeLlm(spy).unsafeRunSync()
    assert(res.ok)
    assertEquals(res.provider, Some("prov-a"))
    // contract shape
    assertEquals(captured.sessionId, "llm-probe")
    assertEquals(captured.agentId, "llm-probe")
    assertEquals(captured.messages.size, 1)
    assertEquals(captured.messages.head.role, MessageRole.User)
    assertEquals(captured.messages.head.content, Left("回复 ok"))
    assert(captured.tools.isEmpty)
    // no ref ⇒ no per-request model chain: the global (seed) chain decides
    assertEquals(captured.agentModel, None, "不带 ref 的探针必须保持历史形态（全局链）")
  }

  test("probeLlm(modelRef) puts the picked model on the request (the probe measures the user's pick)") {
    var captured: LlmRequest = null
    val spy = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] =
        captured = req; IO.pure(okResponseFor("pppick", "pick-model"))
      def sendStream(
        req: LlmRequest,
        onAttempt: Option[FallbackAttempt => IO[Unit]] = None
      ): fs2.Stream[IO, StreamChunk] =
        fs2.Stream.empty
    val res = OnboardingService.probeLlm(spy, Some("pppick/pick-model")).unsafeRunSync()
    assert(res.ok)
    assertEquals(
      captured.agentModel,
      Some(AgentModelConfig(preferred = Some("pppick/pick-model"))),
      "选定 ref 必须落在 LlmRequest.agentModel（既有点：interface.scala → getCandidatesForAgent）"
    )
    // a blank / whitespace ref is the same as no ref (never an empty preferred)
    val blank = OnboardingService.probeLlm(spy, Some("   ")).unsafeRunSync()
    assert(blank.ok)
    assertEquals(captured.agentModel, None, "空白 ref 不得变成空 preferred")
  }

  test("probeLlm(modelRef): a fallback covering for a dead pick is NOT a success (no probeOkAt)") {
    // The chain ends with the reserve tier, so a dead preferred falls through to
    // another live provider. That answer must not be recorded as the user's brain
    // working — the round-2 decisive reading (pick-dead ⇒ ok=true) is exactly this.
    val spy = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.pure(okResponseFor("ssseed", "seed-model"))
      def sendStream(
        req: LlmRequest,
        onAttempt: Option[FallbackAttempt => IO[Unit]] = None
      ): fs2.Stream[IO, StreamChunk] =
        fs2.Stream.empty
    val res = OnboardingService.probeLlm(spy, Some("pppick/pick-model")).unsafeRunSync()
    assert(!res.ok, s"选定的模型没答，别人代答 ⇒ 探针必须失败，实际 ok=${res.ok}")
    assert(res.error.exists(_.contains("pppick/pick-model")), s"失败理由须点名选定 ref：${res.error}")
    assertEquals(OnboardingService.readStored().unsafeRunSync().flatMap(_.probeOkAt), None, "代答不得记录 probeOkAt")
    // …and the terminal gate therefore still refuses `done`.
    assertEquals(OnboardingService.setState(OnboardingService.OnboardingState.Done).unsafeRunSync().isLeft, true)
  }

  test("probeLlm(modelRef): the picked model answering IS a success (gate opens for the right model)") {
    val spy = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.pure(okResponseFor("pppick", "pick-model"))
      def sendStream(
        req: LlmRequest,
        onAttempt: Option[FallbackAttempt => IO[Unit]] = None
      ): fs2.Stream[IO, StreamChunk] =
        fs2.Stream.empty
    val res = OnboardingService.probeLlm(spy, Some("pppick/pick-model")).unsafeRunSync()
    assert(res.ok)
    assert(OnboardingService.readStored().unsafeRunSync().flatMap(_.probeOkAt).isDefined)
    assertEquals(OnboardingService.setState(OnboardingService.OnboardingState.Done).unsafeRunSync().isRight, true)
  }

  test("probeLlm(modelRef): a malformed ref fails loudly instead of degrading to another provider") {
    val spy = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.pure(okResponse("prov-a"))
      def sendStream(
        req: LlmRequest,
        onAttempt: Option[FallbackAttempt => IO[Unit]] = None
      ): fs2.Stream[IO, StreamChunk] =
        fs2.Stream.empty
    val res = OnboardingService.probeLlm(spy, Some("no-slash")).unsafeRunSync()
    assert(!res.ok, "无法解析的 ref 不得让链路改判到别的 provider 上")
    assert(res.error.exists(_.toLowerCase.contains("invalid")), s"须给出可操作理由：${res.error}")
    assertEquals(OnboardingService.readStored().unsafeRunSync().flatMap(_.probeOkAt), None)
  }

  /** The config body the probe consults to tell "does not exist" from "did not
    *  answer" — the shape `configService.getConfig` returns. */
  private def probeCfg(providerId: String, modelIds: String*): String =
    io.circe.Json
      .obj(
        "llm" -> io.circe.Json.obj(
          "providers" -> io.circe.Json.obj(
            providerId -> io.circe.Json.obj(
              "baseUrl" -> io.circe.Json.fromString("https://example.invalid/v1/"),
              "models" -> io.circe.Json.arr(modelIds.map(id => io.circe.Json.obj("id" -> io.circe.Json.fromString(id)))*)
            )
          )
        )
      )
      .noSpaces

  test("probeLlm(modelRef): a well-formed ref naming an UNKNOWN model is refused (no false success)") {
    // E2E section G4: an unknown model id resolves to no candidate, the reserve
    // tier covers for it, and the call returns 200 from a live provider — which
    // used to be reported as ok for a model that does not exist. The config body
    // is what lets the probe refuse it WITHOUT even calling the provider.
    var called = false
    val spy = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] =
        called = true; IO.pure(okResponseFor("livelive", "other-model"))
      def sendStream(
        req: LlmRequest,
        onAttempt: Option[FallbackAttempt => IO[Unit]] = None
      ): fs2.Stream[IO, StreamChunk] =
        fs2.Stream.empty
    val res = OnboardingService
      .probeLlm(spy, Some("livelive/ghost-model"), Some(probeCfg("livelive", "real-model")))
      .unsafeRunSync()
    assert(!res.ok, s"配置里没有的 model 不得报成功，实际 ok=${res.ok}")
    assert(res.error.exists(_.contains("ghost-model")), s"理由须点名该 model：${res.error}")
    assert(!called, "不存在的 model 不该发起网络调用（也就没有『别人代答』的机会）")
    assertEquals(OnboardingService.readStored().unsafeRunSync().flatMap(_.probeOkAt), None)
  }

  test("probeLlm(modelRef): a ref naming an UNKNOWN provider is refused too") {
    val spy = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.pure(okResponseFor("livelive", "real-model"))
      def sendStream(
        req: LlmRequest,
        onAttempt: Option[FallbackAttempt => IO[Unit]] = None
      ): fs2.Stream[IO, StreamChunk] =
        fs2.Stream.empty
    val res = OnboardingService
      .probeLlm(spy, Some("ghostprov/m"), Some(probeCfg("livelive", "real-model")))
      .unsafeRunSync()
    assert(!res.ok, "未配置的 provider 不得报成功")
    assert(res.error.exists(_.contains("ghostprov")), s"理由须点名该 provider：${res.error}")
  }

  test("probeLlm(modelRef): a ref the config DOES contain still probes normally (the check is not a blanket refusal)") {
    val spy = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.pure(okResponseFor("livelive", "real-model"))
      def sendStream(
        req: LlmRequest,
        onAttempt: Option[FallbackAttempt => IO[Unit]] = None
      ): fs2.Stream[IO, StreamChunk] =
        fs2.Stream.empty
    val res = OnboardingService
      .probeLlm(spy, Some("livelive/real-model"), Some(probeCfg("livelive", "real-model")))
      .unsafeRunSync()
    assert(res.ok)
    assert(OnboardingService.readStored().unsafeRunSync().flatMap(_.probeOkAt).isDefined)
  }

  test("probeLlm(modelRef) with no config supplied keeps the old shape (unknown ids are not judged)") {
    // Guards the optional parameter: callers that pass no config (legacy / test
    // doubles) must not start failing for a reason they cannot express.
    val spy = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.pure(okResponseFor("someone", "other"))
      def sendStream(
        req: LlmRequest,
        onAttempt: Option[FallbackAttempt => IO[Unit]] = None
      ): fs2.Stream[IO, StreamChunk] =
        fs2.Stream.empty
    // The answering-ref check still applies (it always did); only the
    // existence check is skipped. So a covering answer is still a refusal.
    val res = OnboardingService.probeLlm(spy, Some("pppick/pick-model")).unsafeRunSync()
    assert(!res.ok, "无 config 时『答者校验』仍生效")
  }

  test("probeLlm with NO ref measures the agent's OWN chain when one is on disk") {
    // E2E cross-check finding: with no ref the probe resolved the raw seed chain
    // and visited `ssseed` while the pick from a previous run sat unread in
    // agents/<root>/agent.json — so the gate answered ok over a brain that is not
    // the user's. The own chain is the same chain a follower inherits.
    val dir = tempRoot / "agents" / nebflow.actor.RootAgentIdentity.Name
    os.makeDir.all(dir)
    os.write(
      dir / "agent.json",
      """{"name":"Nebula","model":{"preferred":"pppick/pick-model","fallbacks":["cccover/cover-model"]}}"""
    )
    var captured: LlmRequest = null
    val spy = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] =
        captured = req; IO.pure(okResponseFor("pppick", "pick-model"))
      def sendStream(
        req: LlmRequest,
        onAttempt: Option[FallbackAttempt => IO[Unit]] = None
      ): fs2.Stream[IO, StreamChunk] =
        fs2.Stream.empty
    val res = OnboardingService.probeLlm(spy).unsafeRunSync()
    assert(res.ok)
    assertEquals(
      captured.agentModel,
      Some(AgentModelConfig(preferred = Some("pppick/pick-model"), fallbacks = List("cccover/cover-model"))),
      "无 ref 时必须量磁盘上的 own 链，而不是 seed 链"
    )
  }

  test("probeLlm with NO ref and NO own chain keeps the historical shape (global chain decides)") {
    var captured: LlmRequest = null
    val spy = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] =
        captured = req; IO.pure(okResponse("prov-a"))
      def sendStream(
        req: LlmRequest,
        onAttempt: Option[FallbackAttempt => IO[Unit]] = None
      ): fs2.Stream[IO, StreamChunk] =
        fs2.Stream.empty
    val res = OnboardingService.probeLlm(spy).unsafeRunSync()
    assert(res.ok)
    assertEquals(captured.agentModel, None, "没有任何 own 链时保持历史形态（全局链）")
  }

  test("probeLlm with NO ref: a fallback covering for the own chain's head is NOT a success") {
    // The no-ref arm gets the same treatment as the picked-ref arm: the head of
    // the chain we set out to measure must be the one that answered.
    val dir = tempRoot / "agents" / nebflow.actor.RootAgentIdentity.Name
    os.makeDir.all(dir)
    os.write(dir / "agent.json", """{"name":"Nebula","model":{"preferred":"pppick/pick-model"}}""")
    val spy = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.pure(okResponseFor("ssseed", "seed-model"))
      def sendStream(
        req: LlmRequest,
        onAttempt: Option[FallbackAttempt => IO[Unit]] = None
      ): fs2.Stream[IO, StreamChunk] =
        fs2.Stream.empty
    val res = OnboardingService.probeLlm(spy).unsafeRunSync()
    assert(!res.ok, s"own 链的头没答、别人代答 ⇒ 必须失败，实际 ok=${res.ok}")
    assert(res.error.exists(_.contains("pppick/pick-model")), s"理由须点名该头：${res.error}")
    assertEquals(OnboardingService.readStored().unsafeRunSync().flatMap(_.probeOkAt), None)
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
