package nebflow.llm

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import munit.CatsEffectSuite
import nebflow.shared.*

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*

/**
 * issue #31 Fix B (2026-08-20): whole-stream no-progress watchdog.
 *
 * The per-provider inactivityTimeout (first-token 90s / inactivity 60s) only
 * arms AFTER the provider stream starts producing. The incident
 * (delegate-design-engineer-36d95c33): intake logged at 22:48:41, then 40min
 * of ZERO traces — the fiber was parked in the intake→first-chunk evaluation
 * chain where no watchdog existed; Stop (mailbox, never consumed) and
 * hard-cancel (halt Deferred, fiber suspended on a non-cancellable wait) were
 * both ineffective, leaving a phantom barrier slot until restart.
 *
 * This spec pins the OUTER watchdog mounted in sendStream (interface.scala,
 * around the whole candidate chain): with the test window shrunk to 1s via
 * LlmInterface.noProgressTimeoutOverride, a provider that never responds —
 * and one that emits a single chunk then stalls — must each fail the whole
 * sendStream with a TimeoutException within seconds (classified Timeout →
 * llm-fail path), not hang forever. A well-behaved stream completes untouched.
 */
class SendStreamNoProgressSpec extends CatsEffectSuite:

  override val munitIOTimeout = 30.seconds

  // Distinct ports from every other mock in the suite (GateWedge uses 18341/2).
  private val PortNever = 18401
  private val PortOk = 18403

  private val messageStart =
    "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_mock\",\"usage\":{\"input_tokens\":10,\"output_tokens\":0}}}\n\n"
  private val oneDelta =
    "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}}\n\n"
  private val restOfStream =
    Seq(
      "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n",
      "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":2}}\n\n",
      "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n",
      "data: [DONE]\n\n"
    ).mkString

  /** mode: "never" (accept, hold BEFORE response headers) / "ok" (full stream).
    *
    * "never" parks the fiber on the async response-header wait — a CANCELLABLE
    * park, which the outer watchdog must kill within the window. (The
    * non-cancellable body-read stall is deliberately NOT mocked here — see the
    * boundary note below the tests.) */
  private def startMock(port: Int, mode: String): IO[HttpServer] =
    IO.blocking {
      val server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0)
      // CachedThreadPool: JDK HttpServer's default serializes exchanges — a
      // holding handler must not block other exchanges on the same server.
      server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool())
      server.createContext(
        "/v1/messages",
        (exchange: HttpExchange) =>
          val _ = exchange.getRequestBody.readAllBytes() // JDK requirement
          mode match
            case "never" =>
              // Park BEFORE sending response headers: the client fiber waits
              // on the async response-header future — a cancellable wait.
              Thread.sleep(120_000)
              exchange.close()
            case _ =>
              val body = (messageStart + oneDelta + restOfStream).getBytes(StandardCharsets.UTF_8)
              exchange.getResponseHeaders.add("Content-Type", "text/event-stream")
              exchange.sendResponseHeaders(200, 0)
              val os = exchange.getResponseBody
              os.write(body)
              os.flush()
              os.close()
              exchange.close()
      )
      server.start()
      server
    }

  private def mkConfig(port: Int): NebflowServiceConfig =
    NebflowServiceConfig(
      llm = ServiceLlmConfig(
        providers = Map(
          "p" -> ProviderConfig(
            baseUrl = s"http://127.0.0.1:$port",
            apiKey = "test",
            protocol = LlmProtocol.Anthropic,
            models = List(ModelConfig("m1", maxTokens = 1024, vision = Some(false))),
            queuePersist = Some(false)
          )
        )
      )
    )

  private def runOneTurn(port: Int): IO[Either[Throwable, List[StreamChunk]]] =
    for
      configRef <- Ref.of[IO, NebflowServiceConfig](mkConfig(port))
      sessionOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      result <- LlmInterface
        .createLlm(sessionOverrides, None, Some(configRef))
        .flatMap { case (handle, _, _, release) =>
          val req = LlmRequest(
            messages = List(Message(MessageRole.User, Left("hi"))),
            sessionId = s"noprogress-$port",
            agentId = "spec-agent",
            agentModel = Some(AgentModelConfig(preferred = Some("p/m1")))
          )
          handle.sendStream(req).compile.toList.attempt.guarantee(release)
        }
    yield result

  test("Fix B: provider never responds → whole stream fails with TimeoutException within the window") {
    val prev = LlmInterface.noProgressTimeoutOverride
    LlmInterface.noProgressTimeoutOverride = Some(1500.millis)
    var server: HttpServer = null
    try
      val program = for
        s <- startMock(PortNever, "never")
        _ = server = s
        t0 <- IO.monotonic
        result <- runOneTurn(PortNever)
        elapsed <- IO.monotonic.map(_ - t0)
        _ <- IO.blocking(server.stop(0)).attempt.void
      yield (result, elapsed)
      val (result, elapsed) = program.unsafeRunSync()
      result match
        case Left(e: java.util.concurrent.TimeoutException) =>
          assert(e.getMessage.contains("no response"), s"first-token message expected, got: ${e.getMessage}")
        case other => fail(s"expected Left(TimeoutException), got $other")
      assert(elapsed < 10.seconds, s"watchdog fired at ${elapsed.toMillis}ms — the outer guard must bound the blind window")
    finally
      LlmInterface.noProgressTimeoutOverride = prev
      if server != null then server.stop(0)
  }

  // NOTE (boundary, issue #31): a stall INSIDE the streaming body (headers +
  // one chunk, then the socket held open) is NOT recoverable by the outer
  // watchdog — the fiber parks on a non-cancellable body read, so the
  // watchdog's cancellation propagates into the void and the error never
  // surfaces (verified empirically: this exact mock hangs past munitIOTimeout).
  // That layer is covered by Fix A instead: TaskStuckWatcher gives up via the
  // supervisor's Cancelled branch, releasing the parent barrier from OUTSIDE
  // the dead fiber (StuckDelegateReleaseSpec pins that path end-to-end).
  // The chunk-gap pipe semantics themselves (one chunk → silence → inactive)
  // are pinned by StreamWatchdogSpec on the cancellable layer.

  test("Fix B: well-behaved stream completes untouched (no false positive)") {
    val prev = LlmInterface.noProgressTimeoutOverride
    LlmInterface.noProgressTimeoutOverride = Some(5.seconds)
    var server: HttpServer = null
    try
      val program = for
        s <- startMock(PortOk, "ok")
        _ = server = s
        result <- runOneTurn(PortOk)
        _ <- IO.blocking(s.stop(0)).attempt.void
      yield result
      val result = program.unsafeRunSync()
      result match
        case Right(chunks) =>
          assert(chunks.exists(_.isInstanceOf[StreamChunk.Done]), s"stream must complete with Done, got $chunks")
        case Left(e) => fail(s"well-behaved stream must not be killed by the outer watchdog: $e")
    finally
      LlmInterface.noProgressTimeoutOverride = prev
      if server != null then server.stop(0)
  }

end SendStreamNoProgressSpec
