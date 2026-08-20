package nebflow.llm

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import munit.CatsEffectSuite
import nebflow.shared.*

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*

/**
 * gate-wedge E2E (issue #27, report §5 acceptance #3):
 *
 * Three concurrent agents hammer a provider configured maxConcurrency=1
 * (rpm=20 like the real 107 gateway). Mock A (the gated provider) holds its
 * single permit for ~4s before the first token; the other two requests queue
 * at the gate and MUST each land on the fallback provider B within
 * queueTimeout + ε (2s + slack) — NOT wait for A, NOT retry A with the 60s
 * overload backoff, NOT hang silently (the 8.5h incident).
 *
 * Also asserts the fallback attempt trail records the queue-timeout step and
 * that the turn completes end-to-end (Done chunk) for all three requests.
 */
class GateWedgeE2ESpec extends CatsEffectSuite:

  override val munitIOTimeout = 60.seconds

  private def startMock(name: String, port: Int, delayMs: Long, hits: java.util.concurrent.atomic.AtomicInteger): IO[HttpServer] =
    IO.blocking {
      val server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0)
      // Multi-threaded executor: the JDK HttpServer default handles exchanges
      // SERIALLY on one thread — a sleeping handler would serialize requests at
      // the HTTP layer and mask the gate behavior under test.
      server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool())
      server.createContext(
        "/v1/messages",
        (exchange: HttpExchange) =>
          // Drain the request body (JDK HttpServer requirement).
          val _ = exchange.getRequestBody.readAllBytes()
          hits.incrementAndGet()
          Thread.sleep(delayMs)
          val sse =
            Seq(
              "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_mock\",\"usage\":{\"input_tokens\":10,\"output_tokens\":0}}}\n\n",
              "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n",
              "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}}\n\n",
              "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n",
              "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":2}}\n\n",
              "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n",
              "data: [DONE]\n\n"
            ).mkString.getBytes(StandardCharsets.UTF_8)
          exchange.getResponseHeaders.add("Content-Type", "text/event-stream")
          exchange.sendResponseHeaders(200, 0) // chunked
          val os = exchange.getResponseBody
          os.write(sse)
          os.flush()
          os.close()
          exchange.close()
      )
      server.start()
      server
    }

  test("gate-wedge E2E: saturated maxConcurrency=1 provider → queued turns fall to fallback within queueTimeout+ε") {
    val queueTimeoutMs = 2000L
    val aHoldMs = 4000L
    val hitsA = new java.util.concurrent.atomic.AtomicInteger(0)
    val hitsB = new java.util.concurrent.atomic.AtomicInteger(0)
    for
      serverA <- startMock("a", 18341, aHoldMs, hitsA)
      serverB <- startMock("b", 18342, 0L, hitsB)
      config = NebflowServiceConfig(
        llm = ServiceLlmConfig(
          providers = Map(
            "a" -> ProviderConfig(
              baseUrl = "http://127.0.0.1:18341",
              apiKey = "test",
              protocol = LlmProtocol.Anthropic,
              models = List(ModelConfig("m1", maxTokens = 1024, vision = Some(false))),
              maxConcurrency = Some(1),
              rpm = Some(20),
              queueTimeoutMs = Some(queueTimeoutMs.toInt),
              queuePersist = Some(false)
            ),
            "b" -> ProviderConfig(
              baseUrl = "http://127.0.0.1:18342",
              apiKey = "test",
              protocol = LlmProtocol.Anthropic,
              models = List(ModelConfig("m1", maxTokens = 1024, vision = Some(false))),
              queuePersist = Some(false)
            )
          )
        )
      )
      configRef <- Ref.of[IO, NebflowServiceConfig](config)
      sessionOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      attempts <- Ref.of[IO, List[FallbackAttempt]](Nil)
      work = IO.monotonic.flatMap { t0 =>
        LlmInterface
          .createLlm(sessionOverrides, None, Some(configRef))
          .flatMap { case (handle, _, _, release) =>
            val req = LlmRequest(
              messages = List(Message(MessageRole.User, Left("hi"))),
              sessionId = "gate-wedge-e2e",
              agentId = "e2e-agent",
              agentModel = Some(AgentModelConfig(preferred = Some("a/m1"), fallbacks = List("b/m1")))
            )
            def oneTurn(tag: String): IO[(String, Long, Boolean)] =
              handle
                .sendStream(req, onAttempt = Some(a => attempts.update(_ :+ a)))
                .compile
                .toList
                .map { chunks =>
                  val done = chunks.exists {
                    case _: StreamChunk.Done => true
                    case _                   => false
                  }
                  (tag, done)
                }
                .flatMap { case (tag, done) =>
                  IO.monotonic.map(now => (tag, (now - t0).toMillis, done))
                }
            IO.both(oneTurn("t1"), IO.both(oneTurn("t2"), oneTurn("t3")))
              .map { case (r1, (r2, r3)) => List(r1, r2, r3) }
              .guarantee(release)
          }
      }
      result <- work
      _ <- IO.blocking { serverA.stop(0); serverB.stop(0) }
      allAttempts <- attempts.get
      aHits = hitsA.get
      bHits = hitsB.get
    yield
      // All three turns completed end-to-end (Done chunk present).
      result.foreach { case (tag, _, done) =>
        assert(done, s"turn $tag never completed (no Done chunk)")
      }
      val byTurn = result.map(r => r._1 -> r._2).toMap
      // The permit holder rides A (~4s); the two queued turns must land on B
      // within queueTimeout + ε (fallback hop + fast mock), NOT wait for A
      // and NOT hang.
      val queued = byTurn.filter(_._2 < aHoldMs - 500) // the two fast completions
      assertEquals(
        queued.size,
        2,
        s"expected exactly two fast (fallback) completions, got timings $byTurn; attempts=$allAttempts; aHits=$aHits bHits=$bHits"
      )
      queued.foreach { case (tag, ms) =>
        assert(ms < queueTimeoutMs + 2500, s"turn $tag took ${ms}ms — must fall back within queueTimeout+ε, timings $byTurn")
      }
      // The fallback trail records the queue-timeout step on provider a.
      val queueTimeoutSteps = allAttempts.filter(a => a.providerId == "a" && a.reason.contains(FailoverReason.RateLimit))
      assert(queueTimeoutSteps.nonEmpty, s"expected a RateLimit (queue timeout) attempt on provider a, got $allAttempts")
      // B actually served the queued turns.
      assert(bHits >= 2, s"provider b should serve the two queued turns, b hits=$bHits")
      // A was hit exactly once (holder) — the queued turns never re-fired at A.
      assertEquals(aHits, 1, s"provider a must only see the permit holder's request, a hits=$aHits")
  }

end GateWedgeE2ESpec
