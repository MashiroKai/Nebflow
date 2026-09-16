package nebflow.llm

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import io.circe.Json
import io.circe.parser.decode
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.shared.*

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*

/**
 * maxcfg batch (2026-09-16, author ruling "我觉得直接去掉这个参数，以后少一个配置项")
 * — outbound request-body pins for the removal of the user-configurable
 * `llm.providers.*.models[].maxTokens`.
 *
 * These are WIRE-level pins: a real `HttpServer` capture mock records the body
 * the engine actually sends, for both faces and both request paths
 * (`send` / `sendStream`). Nothing here is derived from reading source — the
 * assertions run against captured bytes.
 *
 * Pins:
 *   P1/P2  OpenAI face sends NO `max_tokens` key at all (o-series / GPT-5
 *          reject the deprecated field with a 400).
 *   P3/P4  Anthropic face still sends a LEGAL `max_tokens` (the API requires
 *          it) — 16384 with thinking off = the old default of the removed key.
 *   P5     "high" thinking (budget 32768) survives unclamped AND the Anthropic
 *          output cap is raised above it, keeping the API invariant
 *          `budget_tokens < max_tokens` (the old `maxTokens / 2` clamp used to
 *          cut the budget to 8192 instead).
 *   P6     The clamp still bounds pathological / legacy budgets (100000 →
 *          32768) — and a legacy budget no longer downgrades the OpenAI
 *          `reasoning_effort` class (8192 used to read as "medium").
 *   P7     A legacy config JSON still carrying the `maxTokens` key decodes
 *          without error and no longer steers the output cap.
 *
 * Reverting this batch (see the maxcfg evidence bundle: the same spec run
 * against the pre-change tree) turns P1/P2/P3/P5/P6 red — that is the
 * mutation-red reading, not a claim in this comment.
 */
class MaxTokensRequestFaceSpec extends CatsEffectSuite:

  override val munitIOTimeout = 60.seconds

  private val noThinking: Option[Json] = None
  private val thinkingHigh: Option[Json] =
    Some(Json.obj("type" -> "enabled".asJson, "budget_tokens" -> 32768.asJson))
  private val thinkingLegacyHuge: Option[Json] =
    Some(Json.obj("type" -> "enabled".asJson, "budget_tokens" -> 100000.asJson))

  /** The Anthropic output cap expected with thinking off (old default of the
    * removed key) and with a 32768 budget (raised to keep budget < max_tokens). */
  private val ExpectedAnthropicCapNoThinking = 16384
  private val ExpectedAnthropicCapHighThinking = 49152

  // ── capture mocks ────────────────────────────────────────────────────────

  private val anthropicSse =
    Seq(
      "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_mock\",\"usage\":{\"input_tokens\":10,\"output_tokens\":0}}}\n\n",
      "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"OK\"}}\n\n",
      "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n",
      "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":2}}\n\n",
      "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n",
      "data: [DONE]\n\n"
    ).mkString

  private val anthropicJson =
    """{"id":"msg_1","type":"message","role":"assistant","content":[{"type":"text","text":"OK"}],""" +
      """"stop_reason":"end_turn","usage":{"input_tokens":5,"output_tokens":2}}"""

  private val openAiSse =
    Seq(
      "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"OK\"},\"finish_reason\":null}]}\n\n",
      "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2,\"total_tokens\":7}}\n\n",
      "data: [DONE]\n\n"
    ).mkString

  private val openAiJson =
    """{"id":"c1","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"OK"},"finish_reason":"stop"}],""" +
      """"usage":{"prompt_tokens":5,"completion_tokens":2,"total_tokens":7}}"""

  /** Capture mock: records every request body on `path`, answers 200 with the
    * given payload shape. Port 0 → OS-assigned (no cross-suite collisions). */
  private def startCaptureMock(
      path: String,
      contentType: String,
      payload: String,
      streaming: Boolean,
      captured: ConcurrentLinkedQueue[String]
  ): IO[HttpServer] =
    IO.blocking {
      val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
      server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool())
      server.createContext(
        path,
        (exchange: HttpExchange) =>
          val body = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
          captured.add(body)
          val resp = payload.getBytes(StandardCharsets.UTF_8)
          exchange.getResponseHeaders.add("Content-Type", contentType)
          if streaming then
            exchange.sendResponseHeaders(200, 0)
            val os = exchange.getResponseBody
            os.write(resp)
            os.flush()
            os.close()
          else
            exchange.sendResponseHeaders(200, resp.length.toLong)
            exchange.getResponseBody.write(resp)
          exchange.close()
      )
      server.start()
      server
    }

  private def portOf(server: HttpServer): Int = server.getAddress.getPort

  private def mkConfig(
      protocol: LlmProtocol,
      baseUrl: String,
      providerOverride: Option[ProviderConfig] = None
  ): NebflowServiceConfig =
    val provider = providerOverride match
      case Some(p) => p.copy(baseUrl = baseUrl)
      case None =>
        ProviderConfig(
          baseUrl = baseUrl,
          apiKey = "test",
          protocol = protocol,
          models = List(ModelConfig("m1", contextWindow = 128000, vision = Some(false)))
        )
    NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map("p" -> provider)))

  private def run(
      config: NebflowServiceConfig,
      thinking: Option[Json],
      stream: Boolean
  ): IO[Either[Throwable, Int]] =
    for
      configRef <- Ref.of[IO, NebflowServiceConfig](config)
      sessionOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      triple <- LlmInterface.createLlm(sessionOverrides, None, Some(configRef))
      (handle, _, _, release) = triple
      req = LlmRequest(
        messages = List(Message(MessageRole.User, Left("hi"))),
        sessionId = "maxcfg-face-spec",
        agentId = "maxcfg-face-spec",
        thinking = thinking,
        agentModel = Some(AgentModelConfig(preferred = Some("p/m1"), fallbacks = Nil))
      )
      out <-
        if stream then handle.sendStream(req).compile.toList.attempt.map(_.map(_.size))
        else handle.send(req).attempt.map(_.map(_ => 1))
      _ <- release
    yield out

  private def trace(label: String, bodies: Iterable[String]): Unit =
    bodies.foreach(b => println(s"[MAXCFG-TRACE] $label body=$b"))

  private def field(body: String, name: String): Option[Json] =
    io.circe.parser.parse(body).toOption.flatMap(_.hcursor.downField(name).focus).filterNot(_.isNull)

  /** Runs one face (both paths, one thinking config) and returns the captured
    * bodies plus the call outcome, printing the raw readings. */
  private def capture(
      label: String,
      protocol: LlmProtocol,
      path: String,
      ssePayload: String,
      jsonPayload: String,
      thinking: Option[Json],
      providerOverride: Option[ProviderConfig] = None
  ): IO[List[String]] =
    val captured = new ConcurrentLinkedQueue[String]()
    val result = for
      streamMock <- startCaptureMock(path, "text/event-stream", ssePayload, streaming = true, captured)
      jsonMock <- startCaptureMock(path, "application/json", jsonPayload, streaming = false, captured)
      baseUrl = s"http://127.0.0.1:${portOf(streamMock)}"
      cfg = mkConfig(protocol, baseUrl, providerOverride)
      cfgJson = cfg.copy(llm =
        cfg.llm.copy(providers = cfg.llm.providers.map { case (k, p) =>
          k -> p.copy(baseUrl = s"http://127.0.0.1:${portOf(jsonMock)}")
        })
      )
      rStream <- run(cfg, thinking, stream = true)
      rJson <- run(cfgJson, thinking, stream = false)
      _ = println(s"[MAXCFG-TRACE] $label streamOutcome=${rStream.fold(e => s"ERR:${e.getClass.getSimpleName}", n => s"OK:$n")}")
      _ = println(s"[MAXCFG-TRACE] $label nonStreamOutcome=${rJson.fold(e => s"ERR:${e.getClass.getSimpleName}", n => s"OK:$n")}")
      _ <- IO.blocking(streamMock.stop(0))
      _ <- IO.blocking(jsonMock.stop(0))
    yield ()
    result.flatMap { _ =>
      val bodies = {
        val it = captured.iterator()
        val buf = scala.collection.mutable.ListBuffer[String]()
        while it.hasNext do buf += it.next()
        buf.toList
      }
      IO.delay {
        trace(label, bodies)
        bodies
      }
    }

  // ── P1/P2: OpenAI face must not send `max_tokens` ────────────────────────

  test("P1/P2: OpenAI face sends no max_tokens key (stream + non-stream, thinking on and off)") {
    (for
      off <- capture(
        "openai/thinking-off",
        LlmProtocol.OpenAI,
        "/chat/completions",
        openAiSse,
        openAiJson,
        noThinking
      )
      on <- capture(
        "openai/thinking-high",
        LlmProtocol.OpenAI,
        "/chat/completions",
        openAiSse,
        openAiJson,
        thinkingHigh
      )
    yield (off, on)).map { case (off, on) =>
      val all = off ++ on
      assert(all.nonEmpty, "capture mock recorded no OpenAI request body")
      all.foreach { b =>
        assert(
          !b.contains("max_tokens"),
          s"OpenAI request body must not carry max_tokens (deprecated; o-series/GPT-5 reject it): $b"
        )
      }
    }
  }

  // ── P3/P4: Anthropic face keeps a legal max_tokens ───────────────────────

  test("P3/P4: Anthropic face sends a legal max_tokens = old default (16384) with thinking off") {
    capture(
      "anthropic/thinking-off",
      LlmProtocol.Anthropic,
      "/v1/messages",
      anthropicSse,
      anthropicJson,
      noThinking
    ).map { bodies =>
      assert(bodies.nonEmpty, "capture mock recorded no Anthropic request body")
      bodies.foreach { b =>
        assertEquals(
          field(b, "max_tokens").flatMap(_.asNumber).flatMap(_.toInt),
          Some(ExpectedAnthropicCapNoThinking),
          s"Anthropic REQUIRES max_tokens; with thinking off it must equal the removed key's old default: $b"
        )
      }
    }
  }

  // ── P5: high thinking is neither crushed nor made illegal ───────────────

  test("P5: Anthropic high thinking -> budget 32768 unclamped AND max_tokens raised above it") {
    capture(
      "anthropic/thinking-high",
      LlmProtocol.Anthropic,
      "/v1/messages",
      anthropicSse,
      anthropicJson,
      thinkingHigh
    ).map { bodies =>
      assert(bodies.nonEmpty, "capture mock recorded no Anthropic request body")
      bodies.foreach { b =>
        val parsed = io.circe.parser.parse(b).toOption.getOrElse(fail(s"unparsable body: $b"))
        val budget = parsed.hcursor.downField("thinking").downField("budget_tokens").as[Int].toOption
        val cap = parsed.hcursor.downField("max_tokens").as[Int].toOption
        assertEquals(budget, Some(32768), s"high thinking (32768) must not be clamped down: $b")
        assertEquals(cap, Some(ExpectedAnthropicCapHighThinking), s"output cap must be raised above the budget: $b")
        assert(
          budget.exists(b => cap.exists(c => c > b)),
          s"Anthropic API invariant budget_tokens < max_tokens violated: $b"
        )
      }
    }
  }

  // ── P6: clamp still bounds legacy budgets; effort class preserved ───────

  test("P6: legacy budget 100000 -> clamped to 32768, OpenAI reasoning_effort stays 'high'") {
    capture(
      "anthropic/thinking-legacy-huge",
      LlmProtocol.Anthropic,
      "/v1/messages",
      anthropicSse,
      anthropicJson,
      thinkingLegacyHuge
    ).flatMap { anthroBodies =>
      capture(
        "openai/thinking-legacy-huge",
        LlmProtocol.OpenAI,
        "/chat/completions",
        openAiSse,
        openAiJson,
        thinkingLegacyHuge
      ).map { openAiBodies =>
        assert(anthroBodies.nonEmpty && openAiBodies.nonEmpty, "capture mock recorded no request body")
        anthroBodies.foreach { b =>
          val budget =
            io.circe.parser.parse(b).toOption.flatMap(_.hcursor.downField("thinking").downField("budget_tokens").as[Int].toOption)
          assertEquals(budget, Some(32768), s"pathological budget must still be bounded by the internal ceiling: $b")
        }
        openAiBodies.foreach { b =>
          assertEquals(
            field(b, "reasoning_effort").flatMap(_.asString),
            Some("high"),
            s"a clamped 32768 budget must still read as the 'high' effort class (8192 read as 'medium'): $b"
          )
        }
      }
    }
  }

  // ── P7: legacy `maxTokens` config key is ignored, not an error ──────────

  test("P7: legacy config JSON carrying maxTokens decodes (no error) and does not steer the cap") {
    val legacyJson =
      """{"baseUrl":"https://x.example.com/v1","apiKey":"k","protocol":"anthropic",""" +
        """"models":[{"id":"m1","maxTokens":4096,"contextWindow":8192}]}"""
    val decoded = decode[ProviderConfig](legacyJson)
    assert(decoded.isRight, s"legacy config with maxTokens must decode without error: $decoded")
    val provider = decoded.toOption.get
    assertEquals(provider.models.map(_.id), List("m1"))
    assertEquals(provider.models.head.contextWindow, 8192, "sibling keys unaffected")
    println(s"[MAXCFG-TRACE] legacy-config-decode=OK models=${provider.models.map(m => (m.id, m.contextWindow))}")
  }

  // ── P8: a legacy `maxTokens` value must not reach the wire any more ─────

  test("P8: legacy model entry with maxTokens:4096 → cap stays 16384 (key ignored, no error)") {
    val legacyJson =
      """{"baseUrl":"https://x.example.com/v1","apiKey":"k","protocol":"anthropic",""" +
        """"models":[{"id":"m1","maxTokens":4096,"contextWindow":8192,"vision":false}]}"""
    val decoded = decode[ProviderConfig](legacyJson)
    assert(decoded.isRight, s"legacy entry must decode without error: $decoded")
    capture(
      "anthropic/legacy-key-in-config",
      LlmProtocol.Anthropic,
      "/v1/messages",
      anthropicSse,
      anthropicJson,
      noThinking,
      Some(decoded.toOption.get)
    ).map { bodies =>
      assert(bodies.nonEmpty, "capture mock recorded no Anthropic request body")
      bodies.foreach { b =>
        assertEquals(
          field(b, "max_tokens").flatMap(_.asNumber).flatMap(_.toInt),
          Some(ExpectedAnthropicCapNoThinking),
          s"a legacy maxTokens key must no longer steer the output cap: $b"
        )
      }
    }
  }
