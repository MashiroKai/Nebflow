package nebflow.llm

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import io.circe.{Json, parser}
import munit.CatsEffectSuite
import nebflow.shared.*

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*

/**
 * visionfix (甲) — O7 main criterion C: WIRE CAPTURE.
 *
 * The only assertion that proves "the image really left on the wire": a real
 * `com.sun.net.httpserver.HttpServer` mock bound to 127.0.0.1:<dedicated port>
 * receives the ACTUAL outbound request body produced by the real chain
 * (createLlm → sendStream → adapter serialization). Asserting on the captured
 * bytes, not on an internal flag, is what makes a reintroduced strip step fail
 * this spec (see mutation 2 in the batch report).
 *
 * Why a real HTTP mock and not the in-process `CapturingBackend` seam
 * (OpenAiAdapterSearchSpec:29): that seam hosts the ADAPTER and is driven by
 * `adapter.sendMessage`, which bypasses `interface.scala`'s send gate — it can
 * never prove whether stripping happened. `LlmInterface.createLlm` builds its
 * own `HttpClientFs2Backend` (interface.scala:384-393) with no backend
 * parameter, and `SendMessageParams.attemptBackend` has no spec injection point,
 * so the mock server is the only seam that covers gate + wire at once. Precedent:
 * FallbackSeamSpec / FormatErrorNoEvictSpec use the same `HttpServer` shape.
 *
 * Isolation: this spec never reads or writes the real HOME. All model state is
 * supplied inline via `NebflowServiceConfig`; no dataRoot is touched.
 *
 * Red premise: the assertion binds the OUTBOUND BYTES, so any reintroduction of
 * a pre-send strip step makes it red — that is exactly how the red run is
 * obtained for this batch (mutation 2: re-insert a strip step at the send point;
 * captured body then has ZERO image blocks ⇒ `nImg == 1` RED, restored code ⇒
 * GREEN). The configuration cannot arm a strip gate any more, because the
 * per-model vision bit was retired with (甲): after the change there is no
 * config value that suppresses images — that IS the fix.
 *
 * Ports: 18571/18572 are free (verified in evidence 02_resource_jdk.txt).
 */
class VisionWireCaptureSpec extends CatsEffectSuite:

  override val munitIOTimeout = 45.seconds

  private val PortVision = 18571
  private val PortVisionFallback = 18572

  /**
   * One 8x8 RGB PNG (164 bytes) — small enough to inline, real enough that
   * the serializer treats it as an opaque base64 payload.
   */
  private val SrcImageB64: String =
    "iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAIAAABLbSncAAAAa0lEQVR42hWNQREAQQzCKqVSIgUplYIUpCDlbnkSJswMOzBouMFDhg4zyy4sWm7xkqX7g1e+CA4Mgf6KEatXS5ywiKh+cOy9sY47fOTo/cCsn0LmjE1M/YOweWKFCw4JzQ/K9t2pXHFJafkAliFIATL1UtgAAAAASUVORK5CYII="
  private val SrcImageBytes: Int = 164

  private val messageStart =
    "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_mock\",\"usage\":{\"input_tokens\":10,\"output_tokens\":0}}}\n\n"

  private def textDelta(t: String) =
    s"event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"$t\"}}\n\n"

  private val okTail =
    Seq(
      "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n",
      "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":2}}\n\n",
      "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n",
      "data: [DONE]\n\n"
    ).mkString

  /**
   * Capture the FULL outbound request body (read before responding — the JDK
   * HttpServer requires the request body to be consumed), then answer with a
   * well-formed Anthropic SSE stream so the real chain completes.
   */
  private def startCapturingMock(
    port: Int,
    sink: AtomicReference[String],
    hits: java.util.concurrent.atomic.AtomicInteger
  ): IO[HttpServer] =
    IO.blocking {
      val server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0)
      server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool())
      server.createContext(
        "/v1/messages",
        (exchange: HttpExchange) =>
          val body = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
          sink.set(body)
          hits.incrementAndGet()
          val payload = (messageStart + textDelta("OK") + okTail).getBytes(StandardCharsets.UTF_8)
          exchange.getResponseHeaders.add("Content-Type", "text/event-stream")
          exchange.sendResponseHeaders(200, 0)
          val os = exchange.getResponseBody
          os.write(payload)
          os.flush()
          os.close()
          exchange.close()
      )
      server.start()
      server
    }

  /**
   * No vision knob exists post-(甲) — the fixture is a plain configured model.
   * That absence is the point: nothing in the config face can suppress images
   * any more, so the only way to make the wire assertion red is to re-insert a
   * strip step in the code path (mutation 2).
   */
  private def mkConfig(port: Int): NebflowServiceConfig =
    NebflowServiceConfig(
      llm = ServiceLlmConfig(
        providers = Map(
          "v" -> ProviderConfig(
            baseUrl = s"http://127.0.0.1:$port",
            apiKey = "test",
            protocol = LlmProtocol.Anthropic,
            models = List(ModelConfig("m1", contextWindow = 128000))
          )
        )
      )
    )

  /** One user message carrying one Image block. */
  private def imageRequest: LlmRequest =
    LlmRequest(
      messages = List(
        Message(
          MessageRole.User,
          Right(List(ContentBlock.Text("describe this"), ContentBlock.Image(SrcImageB64, "image/png")))
        )
      ),
      sessionId = "vision-wire-spec",
      agentId = "vision-wire-spec-agent",
      agentModel = Some(AgentModelConfig(preferred = Some("v/m1"), fallbacks = Nil))
    )

  /**
   * Parse the captured Anthropic outbound body and count image blocks whose
   * source is base64 (AnthropicAdapter.scala:53-61 serialization shape).
   */
  private def countImageBlocks(body: String): Int =
    val json = parser.parse(body).getOrElse(Json.Null)
    val parts = json.hcursor
      .downField("messages")
      .as[List[Json]]
      .getOrElse(Nil)
      .flatMap(_.hcursor.downField("content").as[List[Json]].getOrElse(Nil))
    parts.count(p =>
      p.hcursor.downField("type").as[String].toOption.contains("image") &&
        p.hcursor.downField("source").downField("type").as[String].toOption.contains("base64")
    )

  private def base64PayloadLength(body: String): Int =
    val json = parser.parse(body).getOrElse(Json.Null)
    json.hcursor
      .downField("messages")
      .as[List[Json]]
      .getOrElse(Nil)
      .flatMap(_.hcursor.downField("content").as[List[Json]].getOrElse(Nil))
      .filter(_.hcursor.downField("type").as[String].toOption.contains("image"))
      .flatMap(_.hcursor.downField("source").downField("data").as[String].toOption)
      .map(_.length)
      .sum

  private def runTurn(config: NebflowServiceConfig): IO[Either[Throwable, List[StreamChunk]]] =
    for
      configRef <- Ref.of[IO, NebflowServiceConfig](config)
      sessionOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      result <- LlmInterface
        .createLlm(sessionOverrides, None, Some(configRef))
        .flatMap { case (handle, _, _, release) =>
          handle.sendStream(imageRequest).compile.toList.attempt.guarantee(release)
        }
    yield result

  test("unconfigured session rejects dispatch and explains the legacy candidate") {
    import scala.jdk.CollectionConverters.*
    val oldRoot = PathUtil.dataRoot
    val root = os.temp.dir(prefix = "unconfigured-model-")
    PathUtil.setDataRoot(root)
    nebflow.core.delegate.DelegateRegistry.put(
      nebflow.core.delegate.DelegateRegistry.Record(
        id = "delegate-unconfigured",
        executor = "nebflow",
        cwd = root.toString,
        project = None,
        taskId = None,
        parentSessionId = "parent",
        title = "unconfigured model",
        createdAt = 0L,
        mode = Some("subagent")
      )
    )
    os.write(
      root / Branding.configFileName,
      """{"llm":{"providers":{"p":{"baseUrl":"http://127.0.0.1:1","apiKey":"fixture","protocol":"openai","models":[{"id":"legacy","contextWindow":8192}]}}}}"""
    )
    val log = org.slf4j.LoggerFactory.getLogger("nebflow.llm").asInstanceOf[ch.qos.logback.classic.Logger]
    val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]()
    appender.start(); log.addAppender(appender)
    val cfg = NebflowServiceConfig(llm =
      ServiceLlmConfig(providers =
        Map(
          "p" -> ProviderConfig(
            baseUrl = "http://127.0.0.1:1",
            apiKey = "fixture",
            protocol = LlmProtocol.OpenAI,
            models = List(ModelConfig("legacy"))
          )
        )
      )
    )
    try
      val outcome = (for
        ref <- Ref.of[IO, NebflowServiceConfig](cfg)
        overrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
        result <- LlmInterface.createLlm(overrides, None, Some(ref)).flatMap { case (handle, registry, _, release) =>
          val req = imageRequest.copy(
            sessionId = "delegate-unconfigured",
            agentModel = None,
            messages = List(Message(MessageRole.User, Left("hi")))
          )
          (for
            displayed <- registry.resolveDisplayedModel("subagent", None)
            candidates <- registry.getCandidates()
            sent <- handle.send(req).attempt
          yield (displayed, candidates, sent)).guarantee(release)
        }
      yield result).unsafeRunSync()
      assertEquals(outcome._1._1.preferred, None)
      assertEquals(outcome._2, Nil)
      assert(outcome._3.isLeft)
      val logs = appender.list.asScala.toList.map(_.getFormattedMessage)
      assert(
        logs.exists(s =>
          s.contains("layer=subagent") && s.contains("old behavior would select p/legacy") && s.contains(
            "dispatch rejected"
          )
        ),
        logs.toString
      )
    finally
      log.detachAppender(appender); appender.stop()
      PathUtil.setDataRoot(oldRoot); os.remove.all(root)
    end try
  }

  test("delegate session model overrides category on the actual request") {
    import scala.jdk.CollectionConverters.*
    val log = org.slf4j.LoggerFactory.getLogger("nebflow.llm").asInstanceOf[ch.qos.logback.classic.Logger]
    val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]()
    appender.start()
    log.addAppender(appender)
    val previousRoot = PathUtil.dataRoot
    val root = os.temp.dir(prefix = "model-wire-")
    PathUtil.setDataRoot(root)
    val seen = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    val program = for
      server <- IO.blocking {
        val s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext(
          "/chat/completions",
          (exchange: HttpExchange) =>
            val body = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
            seen.add(body)
            val payload =
              """{"id":"mock","model":"model-b","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1}}"""
            val bytes = payload.getBytes(StandardCharsets.UTF_8)
            exchange.getResponseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.length)
            val out = exchange.getResponseBody; out.write(bytes); out.close(); exchange.close()
        )
        s.start(); s
      }
      _ <- (for
        cfg <- IO.pure(
          NebflowServiceConfig(llm =
            ServiceLlmConfig(providers =
              Map(
                "p" -> ProviderConfig(
                  baseUrl = s"http://127.0.0.1:${server.getAddress.getPort}",
                  apiKey = "fixture",
                  protocol = LlmProtocol.OpenAI,
                  models = List(ModelConfig("model-a"), ModelConfig("model-b"))
                )
              )
            )
          )
        )
        ref <- Ref.of[IO, NebflowServiceConfig](cfg)
        overrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
        _ <- LlmInterface.createLlm(overrides, None, Some(ref)).flatMap { case (handle, registry, _, release) =>
          val req = imageRequest.copy(
            sessionId = "delegate-fixture",
            messages = List(Message(MessageRole.User, Left("hi"))),
            agentModel = Some(AgentModelConfig(preferred = Some("p/model-a")))
          )
          (for
            selected <- registry.getCandidateForRef("p/model-b")
            _ <- overrides.update(_ + ("delegate-fixture" -> selected.get))
            _ <- handle.send(req)
          yield ()).guarantee(release)
        }
      yield ()).guarantee(IO.blocking(server.stop(0)))
    yield ()
    try
      program.unsafeRunSync()
      val body = seen.peek()
      val wireModel = parser.parse(body).toOption.flatMap(_.hcursor.get[String]("model").toOption)
      assertEquals(wireModel, Some("model-b"), s"wire=$body")
      val logs = appender.list.asScala.toList.map(_.getFormattedMessage)
      val ledger =
        logs.find(_.contains("candidate ledger session=delegate-fixture")).getOrElse(fail(s"missing ledger: $logs"))
      assert(ledger.contains(s"selected=p/${wireModel.get}"), ledger)
      assert(!ledger.contains("p/model-b(not-configured)"), ledger)
      assert(!logs.exists(_.contains("SILENT-EXCLUSION")), logs.toString)
    finally
      log.detachAppender(appender)
      appender.stop()
      PathUtil.setDataRoot(previousRoot)
      os.remove.all(root)
    end try
  }

  test("configured effort allowlist reaches both adapters on stream and non-stream paths") {
    import scala.jdk.CollectionConverters.*
    val seen = new java.util.concurrent.ConcurrentLinkedQueue[Json]()
    val program = for
      server <- IO.blocking {
        val s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
        def handler(anthropic: Boolean)(exchange: HttpExchange): Unit =
          val body =
            parser.parse(new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)).toOption.get
          seen.add(body)
          val stream = body.hcursor.get[Boolean]("stream").getOrElse(false)
          val payload =
            if anthropic && stream then messageStart + textDelta("OK") + okTail
            else if anthropic then
              """{"id":"msg","type":"message","role":"assistant","content":[{"type":"text","text":"ok"}],"model":"k3.1-test","stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":2}}"""
            else if stream then
              "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},\"finish_reason\":null}]}\n\n" +
                "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" + "data: [DONE]\n\n"
            else
              """{"id":"mock","model":"m1","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":2}}"""
          val bytes = payload.getBytes(StandardCharsets.UTF_8)
          exchange.getResponseHeaders.add("Content-Type", if stream then "text/event-stream" else "application/json")
          exchange.sendResponseHeaders(200, bytes.length)
          val out = exchange.getResponseBody; out.write(bytes); out.close(); exchange.close()
        end handler
        s.createContext("/v1/messages", (e: HttpExchange) => handler(true)(e))
        s.createContext("/chat/completions", (e: HttpExchange) => handler(false)(e))
        s.start(); s
      }
      _ <- (List(("anthropic", "k3.1-test", "/v1/messages"), ("openai", "m1", "/chat/completions")))
        .traverse_ { (protocol, model, path) =>
          val base = s"http://127.0.0.1:${server.getAddress.getPort}"
          val raw = Json.obj(
            "baseUrl" -> Json.fromString(base),
            "apiKey" -> Json.fromString("test"),
            "protocol" -> Json.fromString(protocol),
            "models" -> Json.arr(Json.obj("id" -> Json.fromString(model), "contextWindow" -> Json.fromInt(128000))),
            "effortAllowlist" -> Json.obj((base + path) -> Json.obj(model -> Json.arr(Json.fromString("high"))))
          )
          val provider = raw.as[ProviderConfig].fold(throw _, identity)
          val cfg = NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map("v" -> provider)))
          for
            ref <- Ref.of[IO, NebflowServiceConfig](cfg)
            overrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
            _ <- LlmInterface.createLlm(overrides, None, Some(ref)).flatMap { case (handle, _, _, release) =>
              val req = imageRequest.copy(
                messages = List(Message(MessageRole.User, Left("hi"))),
                thinking = Some(ThinkingConfig.toLlmJson(ThinkingConfig(effort = Some(ThinkingEffort.High)))),
                agentModel = Some(AgentModelConfig(preferred = Some(s"v/$model"), fallbacks = Nil))
              )
              (handle.sendStream(req).compile.drain *> handle.send(req).void).guarantee(release)
            }
          yield ()
        }
        .guarantee(IO.blocking(server.stop(0)))
    yield ()
    program.unsafeRunSync()
    assertEquals(seen.size(), 4)
    seen.asScala.foreach(body => assertEquals(body.hcursor.get[String]("reasoning_effort").toOption, Some("high")))
  }

  test("OpenAI-compatible real sendStream and send account synthetic usage once") {
    val program = for
      server <- IO.blocking {
        val s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext(
          "/chat/completions",
          (exchange: HttpExchange) =>
            val body = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
            val streaming = parser.parse(body).toOption.exists(_.hcursor.get[Boolean]("stream").getOrElse(false))
            val payload =
              if streaming then
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},\"finish_reason\":null}]}\n\n" +
                  "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                  "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":2,\"prompt_tokens_details\":{\"cached_tokens\":0}}}\n\n" +
                  "data: [DONE]\n\n"
              else
                "{\"id\":\"synthetic\",\"model\":\"m1\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":2}}"
            val bytes = payload.getBytes(StandardCharsets.UTF_8)
            exchange.getResponseHeaders
              .add("Content-Type", if streaming then "text/event-stream" else "application/json")
            exchange.sendResponseHeaders(200, bytes.length)
            val out = exchange.getResponseBody
            out.write(bytes); out.close(); exchange.close()
        )
        s.start(); s
      }
      result <- (for
        config <- IO.pure(
          NebflowServiceConfig(llm =
            ServiceLlmConfig(providers =
              Map(
                "v" -> ProviderConfig(
                  baseUrl = s"http://127.0.0.1:${server.getAddress.getPort}",
                  apiKey = "synthetic",
                  protocol = LlmProtocol.OpenAI,
                  models = List(ModelConfig("m1"))
                )
              )
            )
          )
        )
        configRef <- Ref.of[IO, NebflowServiceConfig](config)
        overrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
        _ <- IO(CacheAttribution.resetForTest())
        results <- LlmInterface.createLlm(overrides, None, Some(configRef)).flatMap { case (handle, _, _, release) =>
          val streamReq = imageRequest.copy(requestId = Some("synthetic-stream"))
          val normalReq = imageRequest.copy(requestId = Some("synthetic-normal"))
          (for
            chunks <- handle.sendStream(streamReq).compile.toList
            streamed = CacheAttribution.snapshot
            response <- handle.send(normalReq)
            normal = CacheAttribution.snapshot
          yield (chunks, streamed, response, normal)).guarantee(release)
        }
      yield results).guarantee(IO.blocking(server.stop(0)))
    yield result
    val (chunks, streamed, response, normal) = program.unsafeRunSync()
    assert(chunks.collect { case d: StreamChunk.Done => d }.size >= 2)
    assertEquals(streamed.business.requests, 1L)
    assertEquals(streamed.missCounts.get("usage-missing"), None)
    assertEquals(streamed.recentMisses.last.requestId, Some("synthetic-stream"))
    assert(response.usage.isDefined)
    assertEquals(normal.business.requests, 2L)
    assertEquals(normal.recentMisses.last.requestId, Some("synthetic-normal"))
  }

  // ============================================================
  // O7 main criterion C — the model really receives the image block
  // ============================================================

  test("C: outbound wire body carries the image block (no pre-send strip)") {
    val sink = new AtomicReference[String]("")
    val hits = new java.util.concurrent.atomic.AtomicInteger(0)
    var server: HttpServer = null
    val program = for
      s <- startCapturingMock(PortVision, sink, hits)
      _ = server = s
      result <- runTurn(mkConfig(PortVision))
      _ <- IO.blocking(server.stop(0)).attempt.void
    yield result
    val result =
      try program.unsafeRunSync()
      finally if server != null then server.stop(0)

    assert(result.isRight, s"turn should complete against the mock: $result")
    assertEquals(hits.get(), 1, "the mock provider must be contacted exactly once")

    val body = sink.get()
    val nImg = countImageBlocks(body)
    // Pre-change (甲 not applied): the strip gate rewrites the Image block to
    // ContentBlock.Text("...") ⇒ nImg == 0 ⇒ THIS ASSERTION IS RED.
    // Post-change: nImg == 1 ⇒ GREEN.
    assertEquals(nImg, 1, s"outbound body must carry 1 image block, got $nImg; body=$body")

    // Payload fidelity: the base64 is the source image, not a placeholder.
    assertEquals(
      base64PayloadLength(body),
      SrcImageB64.length,
      s"outbound base64 length must equal the source image encoding (${SrcImageB64.length})"
    )
    assert(SrcImageBytes > 0)
  }

  test("C-negative: the placeholder text never reaches the wire") {
    val sink = new AtomicReference[String]("")
    val hits = new java.util.concurrent.atomic.AtomicInteger(0)
    var server: HttpServer = null
    val program = for
      s <- startCapturingMock(PortVisionFallback, sink, hits)
      _ = server = s
      result <- runTurn(mkConfig(PortVisionFallback))
      _ <- IO.blocking(server.stop(0)).attempt.void
    yield result
    val result =
      try program.unsafeRunSync()
      finally if server != null then server.stop(0)

    assert(result.isRight, s"turn should complete against the mock: $result")
    val body = sink.get()
    // The strip path rewrote the block to this exact literal (interface.scala:290).
    // Its presence on the wire = the strip mechanism fired.
    assert(
      !body.contains("image omitted"),
      s"the strip placeholder must never reach the outbound wire; body=$body"
    )
    assertEquals(countImageBlocks(body), 1, s"exactly one image block expected; body=$body")
  }
end VisionWireCaptureSpec
