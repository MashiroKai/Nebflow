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

  /** One 8x8 RGB PNG (164 bytes) — small enough to inline, real enough that
    * the serializer treats it as an opaque base64 payload. */
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

  /** Capture the FULL outbound request body (read before responding — the JDK
    * HttpServer requires the request body to be consumed), then answer with a
    * well-formed Anthropic SSE stream so the real chain completes. */
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

  /** No vision knob exists post-(甲) — the fixture is a plain configured model.
    * That absence is the point: nothing in the config face can suppress images
    * any more, so the only way to make the wire assertion red is to re-insert a
    * strip step in the code path (mutation 2). */
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

  /** Parse the captured Anthropic outbound body and count image blocks whose
    * source is base64 (AnthropicAdapter.scala:53-61 serialization shape). */
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
    val result = try program.unsafeRunSync()
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
    val result = try program.unsafeRunSync()
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
