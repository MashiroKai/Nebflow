package nebflow.llm.decision

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import munit.CatsEffectSuite
import nebflow.shared.PathUtil

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * P1 (Face A) acceptance: the decision-provider face.
 *
 * Covers the four verification faces the design card names for P1 (parsing /
 * timeout / error classification / credential-round-trip discipline) plus the
 * two P0 adjudications that fix the SHAPE:
 *
 *   - #1: the face is an independent provider, not `ProviderAdapter`;
 *   - #2: the canonical internal choice form is a MAP (no array->map convert);
 *   - #7: P(yes) is read from a choice answer's `probabilities["yes"]`, and a
 *     noul answer carries a scalar with NO confidence field.
 *
 * The wire fixtures are transcriptions of the REAL captured HTTP-200 samples
 * (`~/.nebflow/projects/jev-test2/.nebflow/run/selfcheck-request.json` and
 * `selfcheck-response.json`), so a shape regression fails here rather than in
 * a live call. No credential is read: the mock server ignores the header.
 */
class DecisionProviderSpec extends CatsEffectSuite:

  override val munitIOTimeout = 120.seconds

  // ── wire fixtures (verbatim from the captured 200 samples) ──────────────

  private val capturedResponse =
    """{
      |  "model": "jev-1.13.0",
      |  "answers": {
      |    "q1": { "type": "noul", "noul": 0.98 },
      |    "q2": { "type": "choice", "choice": "frustrated", "confidence": 0.98,
      |            "probabilities": { "angry": 0.01, "frustrated": 0.99, "calm": 0 } },
      |    "q3": { "type": "score", "score": 2, "confidence": 1,
      |            "legend": { "0": "can wait", "1": "this week", "2": "today" },
      |            "probabilities": { "0": 0, "1": 0, "2": 1 } }
      |  },
      |  "usage": { "input_tokens": 394, "output_tokens": 77 }
      |}""".stripMargin

  private def canonicalRequest: DecisionRequest =
    DecisionRequest(
      state = "Help! My payouts have been failing for 3 days and I need this fixed today.",
      questions = List(
        DecisionQuestion.Noul("q1", "Does this convey urgency?"),
        DecisionQuestion.Choice(
          "q2",
          "What is the customer's tone?",
          Map("calm" -> None, "frustrated" -> Some("annoyed but civil"), "angry" -> Some("hostile or cursing"))
        ),
        DecisionQuestion.Score("q3", "How urgent is this ticket?", List("can wait", "this week", "today"))
      )
    )

  // ── #2 canonical form: the request body is a MAP, and null survives ─────

  test("request body renders choice criteria as a map with a null-valued entry (#2)") {
    val body = JevWireCodec.requestBody(canonicalRequest, "jev-latest")
    val criteria = body.hcursor.downField("questions").downField("q2").downField("criteria").focus
    assert(criteria.exists(_.isObject), s"choice criteria must be a JSON object, got: $criteria")
    // The null description is a legal JeV input (the captured sample answers
    // 200 on exactly this shape) and must NOT be dropped or stringified.
    assert(criteria.exists(_.hcursor.downField("calm").focus.exists(_.isNull)), "null description must be preserved")
    assert(
      criteria.exists(_.hcursor.downField("angry").as[String].toOption.contains("hostile or cursing")),
      "description must be carried verbatim"
    )
  }

  test("score criteria renders as an ordered array and noul carries no criteria") {
    val body = JevWireCodec.requestBody(canonicalRequest, "jev-latest")
    val scoreCriteria = body.hcursor.downField("questions").downField("q3").downField("criteria").as[List[String]]
    assertEquals(scoreCriteria, Right(List("can wait", "this week", "today")))
    val noul = body.hcursor.downField("questions").downField("q1")
    assertEquals(noul.downField("type").as[String], Right("noul"))
    assert(noul.downField("criteria").focus.isEmpty, "noul must not carry criteria")
  }

  test("request body carries exactly state/model/questions at top level") {
    val body = JevWireCodec.requestBody(canonicalRequest, "jev-latest")
    val keys = body.asObject.map(_.keys.toSet).getOrElse(Set.empty)
    assertEquals(keys, Set("state", "model", "questions"))
  }

  // ── response parsing against the captured 200 body ──────────────────────

  test("captured response parses into canonical answers with usage") {
    val parsed = JevWireCodec.parseResponse(capturedResponse)
    assert(parsed.isRight, s"expected a clean parse, got: $parsed")
    val resp = parsed.toOption.get
    assertEquals(resp.model, Some("jev-1.13.0"))
    assertEquals(resp.usage.map(u => (u.inputTokens, u.outputTokens)), Some((394L, 77L)))
    assertEquals(resp.answers("q2"), DecisionAnswer.Choice("frustrated", Some(0.98), Map("angry" -> 0.01, "frustrated" -> 0.99, "calm" -> 0.0)))
    assertEquals(resp.answers("q1"), DecisionAnswer.Noul(0.98))
    resp.answers("q3") match
      case s: DecisionAnswer.Score =>
        assertEquals(s.score, 2)
        assertEquals(s.legend.get("2"), Some("today"))
      case other => fail(s"q3 must parse as Score, got: $other")
  }

  // ── #7 field reads: the two question types read DIFFERENT fields ────────

  test("P(yes) of a choice question reads probabilities[yes]; of a noul, the scalar (#7)") {
    val resp = JevWireCodec.parseResponse(capturedResponse).toOption.get
    // The classic mistake the adjudication registered: reading a noul through
    // the choice accessor. The types make that inexpressible, and the
    // accessors return None across the boundary instead of a wrong number.
    assertEquals(DecisionAnswer.pYes(resp.answers("q1")), None, "a noul answer has no probabilities map")
    assertEquals(DecisionAnswer.noulYes(resp.answers("q1")), Some(0.98))
    assertEquals(DecisionAnswer.noulYes(resp.answers("q2")), None, "a choice answer has no noul scalar")
    assertEquals(DecisionAnswer.YesThreshold, 0.5, "threshold is the officially given 0.5")
  }

  test("an integral-float score position is accepted rather than discarded") {
    val body = """{"answers":{"q":{"type":"score","score":2.0,"probabilities":{"0":0,"1":0,"2":1}}}}"""
    val parsed = JevWireCodec.parseResponse(body)
    assert(parsed.isRight, s"2.0 must read as position 2, got: $parsed")
    parsed.toOption.get.answers("q") match
      case s: DecisionAnswer.Score => assertEquals(s.score, 2)
      case other => fail(s"expected Score, got: $other")
  }

  // ── malformed inputs are loud, never silent empties ─────────────────────

  test("malformed envelopes and answers fail loudly with a specific reason") {
    assert(JevWireCodec.parseResponse("not json").isLeft, "non-JSON must fail")
    assert(JevWireCodec.parseResponse("""{"model":"m"}""").isLeft, "missing answers must fail")
    val noLabel = """{"answers":{"q":{"type":"choice","probabilities":{"a":1}}}}"""
    JevWireCodec.parseResponse(noLabel) match
      case Left(e) => assert(e.message.contains("choice"), s"reason must name the gap: ${e.message}")
      case Right(_) => fail("a choice answer without a label must not parse")
    val noulNoScalar = """{"answers":{"q":{"type":"noul","confidence":0.9}}}"""
    assert(JevWireCodec.parseResponse(noulNoScalar).isLeft, "a noul answer without its scalar must fail")
  }

  // ── error classification (the standalone-search buckets) ────────────────

  test("HTTP status classification mirrors the standalone-search buckets") {
    assert(DecisionHttpTransport.classifyStatus(401, "").isInstanceOf[DecisionError.Auth])
    assert(DecisionHttpTransport.classifyStatus(403, "").isInstanceOf[DecisionError.Auth])
    assert(DecisionHttpTransport.classifyStatus(429, "quota").isInstanceOf[DecisionError.Quota])
    assert(DecisionHttpTransport.classifyStatus(500, "boom").isInstanceOf[DecisionError.Server])
    assert(DecisionHttpTransport.classifyStatus(418, "teapot").isInstanceOf[DecisionError.Transport])
    assert(DecisionHttpTransport.classifyException("connect timed out").isInstanceOf[DecisionError.Timeout])
    assert(DecisionHttpTransport.classifyException("connection reset").isInstanceOf[DecisionError.Transport])
  }

  // ── config decode: partial blocks must not break the service config ─────

  test("a partial jev block decodes through the production loader (seed intermediate state must not fail)") {
    // Goes through `Config.loadServiceConfig`, not a bare circe decode: the
    // loader's `ensureLlmDefaults` is what keeps a seed-state file (no `llm`
    // block at all) from throwing "Missing required field '.llm'" and sending
    // GatewayMain down crash-recovery. A bare decode would fail for reasons
    // that have nothing to do with the jev block.
    val original = PathUtil.dataRoot
    val tmp = os.temp.dir(prefix = "nb-jev-partial-")
    PathUtil.setDataRoot(tmp)
    try
      os.write(tmp / "nebflow.json", """{"jev":{"enabled":true}}""")
      val cfg = nebflow.shared.Config.loadServiceConfig()
      assertEquals(cfg.jev.map(_.isEnabled), Some(true))
      assertEquals(cfg.jev.flatMap(_.provider), None)
      assertEquals(cfg.llm.providers, Map.empty, "a partial jev block must not disturb the llm section")
    finally PathUtil.setDataRoot(original)
  }

  test("an absent jev block decodes to None and the face resolves to off (zero migration)") {
    val original = PathUtil.dataRoot
    val tmp = os.temp.dir(prefix = "nb-jev-absent-")
    PathUtil.setDataRoot(tmp)
    try
      os.write(tmp / "nebflow.json", """{"llm":{"providers":{}}}""")
      val cfg = nebflow.shared.Config.loadServiceConfig()
      assertEquals(cfg.jev, None)
      assertEquals(JevSettings.resolve(cfg.jev, None), None, "no block => face off")
      // A block present but disabled is equally off (the safe default for a
      // face that sends task text off-machine).
      val disabled = nebflow.shared.JevConfig(enabled = Some(false))
      assertEquals(JevSettings.resolve(Some(disabled), None), None)
      // An empty block is off too: `enabled` defaults to false.
      assertEquals(JevSettings.resolve(Some(nebflow.shared.JevConfig()), None), None)
    finally PathUtil.setDataRoot(original)
  }

  test("an unknown provider id is refused rather than silently defaulted") {
    val cfg = nebflow.shared.JevConfig(enabled = Some(true), provider = Some("nope"))
    assertEquals(JevSettings.resolve(Some(cfg), None), None)
  }

  test("settings apply the documented defaults and the hot-read timeout guard") {
    val cfg = nebflow.shared.JevConfig(enabled = Some(true))
    val s = JevSettings.resolve(Some(cfg), None).get
    assertEquals(s.provider, DecisionProvider.TypesafeJev)
    assertEquals(s.endpoint, DecisionProvider.DefaultTypesafeEndpoint)
    assertEquals(s.timeoutMs, DecisionProvider.DefaultTimeoutMs)
    // A non-positive timeout is not a valid deadline: fall back to the default
    // rather than building an instantly-expiring call.
    val bad = JevSettings.resolve(Some(cfg.copy(timeoutMs = Some(0L))), None).get
    assertEquals(bad.timeoutMs, DecisionProvider.DefaultTimeoutMs)
  }

  test("the LAYA provider defaults to its loopback endpoint") {
    val cfg = nebflow.shared.JevConfig(enabled = Some(true), provider = Some(DecisionProvider.Laya))
    val s = JevSettings.resolve(Some(cfg), None).get
    assertEquals(s.endpoint, DecisionProvider.DefaultLayaEndpoint)
  }

  // ── credential discipline ──────────────────────────────────────────────

  test("keyRef is a pointer: an absent secret yields no token and never a value in diagnostics") {
    val original = PathUtil.dataRoot
    val tmp = os.temp.dir(prefix = "nb-jev-secret-")
    PathUtil.setDataRoot(tmp)
    try
      // Point at a name that does not exist: resolution must succeed with no
      // token (the call would fail auth), not throw and not invent a value.
      val cfg = nebflow.shared.JevConfig(enabled = Some(true), keyRef = Some("no-such-secret"))
      val s = JevSettings.resolve(Some(cfg), None).get
      assertEquals(s.token, None)
      // The diagnostic projection must not carry the token at all.
      val view = DecisionProviders.resolvedView(s.copy(token = Some("LEAK-CANARY")))
      assert(!view.values.exists(_.contains("LEAK-CANARY")), s"resolvedView must never carry key material: $view")
      assertEquals(view.get("keyRefResolved"), Some("true"))
    finally PathUtil.setDataRoot(original)
  }

  test("a present secret resolves to its value and the empty case resolves to none") {
    val original = PathUtil.dataRoot
    val tmp = os.temp.dir(prefix = "nb-jev-secret-ok-")
    PathUtil.setDataRoot(tmp)
    try
      os.write(tmp / "secrets" / "good-key", "PRESENT-VALUE", createFolders = true)
      os.write(tmp / "secrets" / "empty-key", "   \n", createFolders = true)
      assertEquals(JevSettings.readSecretToken(Some("good-key")), Some("PRESENT-VALUE"))
      assertEquals(JevSettings.readSecretToken(Some("empty-key")), None)
      assertEquals(JevSettings.readSecretToken(Some("")), None)
      assertEquals(JevSettings.readSecretToken(None), None)
    finally PathUtil.setDataRoot(original)
  }

  // ── LAYA adaptation (the three documented differences) ─────────────────

  test("LAYA adaptation downgrades a null label, keeps the key set, and warns above capacity") {
    val q = DecisionQuestion.Choice("q", "i", Map("calm" -> None, "angry" -> Some("hostile")))
    val adapted = LayaRequestAdapter.adaptQuestion(q).asInstanceOf[DecisionQuestion.Choice]
    assertEquals(adapted.criteria, Map("calm" -> Some(""), "angry" -> Some("hostile")))
    assertEquals(LayaRequestAdapter.hasNoDuplicateLabels(q), true, "a Map cannot hold duplicate labels")

    val many = DecisionQuestion.Choice("wide", "i", (1 to 25).map(i => s"opt$i" -> Some("d")).toMap)
    val wide = LayaRequestAdapter.adaptQuestion(many).asInstanceOf[DecisionQuestion.Choice]
    assertEquals(wide.criteria.size, 25, "adaptation must not shrink the option set")
  }

  test("min_confidence is only added when configured") {
    val noFloor = LayaRequestAdapter.requestBody(canonicalRequest, "laya", None)
    assert(noFloor.hcursor.downField("min_confidence").focus.isEmpty, "absent floor must not be written")
    val withFloor = LayaRequestAdapter.requestBody(canonicalRequest, "laya", Some(0.3))
    assertEquals(withFloor.hcursor.downField("min_confidence").as[Double], Right(0.3))
  }

  // ── A3: the targeted write touches only its own key family ─────────────

  test("jev section write updates named keys, preserves the rest, and refuses unknown keys") {
    val original = PathUtil.dataRoot
    val tmp = os.temp.dir(prefix = "nb-jev-write-")
    PathUtil.setDataRoot(tmp)
    try
      val path = tmp / "nebflow.json"
      os.write(
        path,
        """{"llm":{"providers":{"p":{"baseUrl":"u","apiKey":"SECRET-KEY","protocol":"openai"}}},"plugins":{"enabled":true}}"""
      )
      import io.circe.syntax.*
      val written = nebflow.service.ConfigService
        .setJevSection(Map("enabled" -> true.asJson, "provider" -> "typesafe-jev".asJson))
        .unsafeRunSync()
      assertEquals(written, Right(()))
      val after = io.circe.parser.parse(os.read(path)).toOption.get
      assertEquals(after.hcursor.downField("jev").downField("enabled").as[Boolean], Right(true))
      assertEquals(after.hcursor.downField("jev").downField("provider").as[String], Right("typesafe-jev"))
      // Untouched neighbours survive byte-for-byte, including the credential
      // the targeted write must never disturb or echo.
      assertEquals(after.hcursor.downField("llm").downField("providers").downField("p").downField("apiKey").as[String], Right("SECRET-KEY"))
      assertEquals(after.hcursor.downField("plugins").downField("enabled").as[Boolean], Right(true))

      // A second write updates one key and leaves the earlier one in place.
      val second = nebflow.service.ConfigService
        .setJevSection(Map("model" -> "jev-1.13.0".asJson))
        .unsafeRunSync()
      assertEquals(second, Right(()))
      val after2 = io.circe.parser.parse(os.read(path)).toOption.get
      assertEquals(after2.hcursor.downField("jev").downField("enabled").as[Boolean], Right(true))
      assertEquals(after2.hcursor.downField("jev").downField("model").as[String], Right("jev-1.13.0"))

      // An unknown key is refused outright — no partial write.
      val refused = nebflow.service.ConfigService
        .setJevSection(Map("apiKey" -> "SOME-VALUE".asJson))
        .unsafeRunSync()
      assert(refused.isLeft, "a value-carrying credential key must not be writable on this face")
      val after3 = io.circe.parser.parse(os.read(path)).toOption.get
      assert(after3.hcursor.downField("jev").downField("apiKey").focus.isEmpty, "the refused write must not have landed")
    finally PathUtil.setDataRoot(original)
  }

  // ── end-to-end over a mock endpoint (both providers, one transport) ─────

  private def withMockServer[A](handler: HttpExchange => Unit)(f: Int => IO[A]): IO[A] =
    IO.blocking {
      val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
      server.createContext("/", (e: HttpExchange) => handler(e))
      server.start()
      server
    }.flatMap { server =>
      val port = server.getAddress.getPort
      f(port).guarantee(IO.blocking(server.stop(0)))
    }

  private def respond(exchange: HttpExchange, code: Int, body: String): Unit =
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(code, bytes.length.toLong)
    val os = exchange.getResponseBody
    os.write(bytes)
    os.close()

  test("TypesafeJevProvider performs one batched call and parses the answers") {
    val seen = new AtomicInteger(0)
    val bodies = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    withMockServer { ex =>
      seen.incrementAndGet()
      bodies.add(new String(ex.getRequestBody.readAllBytes(), StandardCharsets.UTF_8))
      respond(ex, 200, capturedResponse)
    } { port =>
      val provider = new TypesafeJevProvider(
        endpoint = s"http://127.0.0.1:$port/v1/systemone",
        model = "jev-latest",
        token = "test-token",
        timeoutMs = 5000L,
        health = None
      )
      provider.predict(canonicalRequest).map { out =>
        assert(out.isRight, s"mock 200 must yield Right, got: $out")
        assertEquals(out.toOption.get.answers.size, 3)
        val body = bodies.poll()
        // The whole question set travels in ONE call (one batched round trip).
        assert(body.contains("\"q1\"") && body.contains("\"q3\""), s"all questions must ride one call: $body")
      }
    }.unsafeToFuture()
  }

  test("a non-2xx status classifies and does not parse") {
    withMockServer(ex => respond(ex, 429, """{"error":"quota"}""")) { port =>
      val provider = new TypesafeJevProvider(s"http://127.0.0.1:$port/x", "m", "t", 5000L, None)
      provider.predict(canonicalRequest).map { out =>
        assert(out.isLeft, "429 must not parse as a successful decision")
        assert(out.swap.toOption.get.isInstanceOf[DecisionError.Quota], s"expected Quota, got: $out")
      }
    }.unsafeToFuture()
  }

  test("a timeout is classified as a timeout, not as a transport failure") {
    withMockServer { ex =>
      // Sleep past the provider deadline, then answer — the client must give
      // up first and report a timeout.
      Thread.sleep(2500)
      respond(ex, 200, capturedResponse)
    } { port =>
      val provider = new TypesafeJevProvider(s"http://127.0.0.1:$port/x", "m", "t", 300L, None)
      provider.predict(canonicalRequest).map { out =>
        assert(out.isLeft, "an expired deadline must not yield an answer")
        assert(out.swap.toOption.get.isInstanceOf[DecisionError.Timeout], s"expected Timeout, got: $out")
      }
    }.unsafeToFuture()
  }

  test("the health channel records success and failure independently of model health") {
    val monitor = new DecisionHealthMonitor
    val seen = new AtomicInteger(0)
    withMockServer { ex =>
      respond(ex, if seen.getAndIncrement() == 0 then 200 else 500, capturedResponse)
    } { port =>
      val provider = new TypesafeJevProvider(s"http://127.0.0.1:$port/x", "m", "t", 5000L, Some(monitor))
      for
        first <- provider.predict(canonicalRequest)
        up <- monitor.getDecisionHealth
        _ = assert(first.isRight, "first call succeeds")
        _ = assertEquals(up, DecisionHealth.Up)
        second <- provider.predict(canonicalRequest)
        down <- monitor.getDecisionHealth
        _ = assert(second.isLeft, "second call fails")
        _ = assert(down.isInstanceOf[DecisionHealth.Down], s"expected Down, got: $down")
      yield ()
    }.unsafeToFuture()
  }

  test("LayaProvider posts an adapted body and reads 503 Retry-After as a capacity signal") {
    val bodies = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    withMockServer { ex =>
      bodies.add(new String(ex.getRequestBody.readAllBytes(), StandardCharsets.UTF_8))
      ex.getResponseHeaders.add("Retry-After", "7")
      respond(ex, 503, """{"detail":"busy"}""")
    } { port =>
      val provider = LayaProvider(
        JevSettings(DecisionProvider.Laya, s"http://127.0.0.1:$port/predict", "laya", 5000L, Some("t"), None, None),
        minConfidence = Some(0.25)
      )
      provider.predict(canonicalRequest).map { out =>
        val body = bodies.poll()
        assert(body.contains("min_confidence"), s"the configured floor must travel: $body")
        assert(body.contains("\"calm\":\"\""), s"the null label must be downgraded to an empty description: $body")
        out match
          case Left(e) =>
            assert(e.message.contains("503"), s"the status must be named: ${e.message}")
            assert(e.message.contains("Retry-After: 7"), s"the retry hint must survive: ${e.message}")
          case Right(_) => fail("a 503 must not parse as a decision")
      }
    }.unsafeToFuture()
  }
