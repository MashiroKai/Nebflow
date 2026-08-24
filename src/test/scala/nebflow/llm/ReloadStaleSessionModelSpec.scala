package nebflow.llm

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import munit.CatsEffectSuite
import nebflow.core.PathUtil
import nebflow.shared.*

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * #33: "Unknown provider: anthropic after hot-reloading LLM config".
 *
 * A session-model override is a ModelCandidate SNAPSHOT built from the config
 * at setSessionModel time. After a config hot-reload removes that provider,
 * the stale override used to raise "Unknown provider" at the adapter/gate on
 * the next request (reloadConfig only cleared adapter/gate caches). The fix:
 *  - reloadConfig drops overrides whose provider no longer exists (and returns
 *    the dropped session ids so callers can clear the persisted session meta);
 *  - the request path filters candidates whose provider is unknown (graceful
 *    skip — the chain continues with the remaining candidates).
 */
class ReloadStaleSessionModelSpec extends CatsEffectSuite:

  override val munitIOTimeout = 60.seconds

  private def providerJson(port: Int): String =
    s"""{"baseUrl":"http://127.0.0.1:$port","apiKey":"k","protocol":"anthropic",
        "models":[{"id":"m1","maxTokens":1024,"contextWindow":8192,"vision":false}]}"""

  private def writeConfig(root: os.Path, providers: Map[String, Int]): Unit =
    val body = providers.map { case (id, port) => s""""$id":${providerJson(port)}""" }.mkString(",")
    os.write.over(root / "nebflow.json", s"""{"llm":{"providers":{$body}}}""")

  /** Redirect the global dataRoot to a temp dir for the duration of `f`
    * (reloadConfig reads the config from `PathUtil.dataRoot`). Restored in
    * guarantee — sequential munit suites make capture-at-start safe. */
  private def withTempDataRoot[A](f: os.Path => IO[A]): IO[A] =
    val original = PathUtil.dataRoot
    val tmp = os.temp.dir(prefix = "nb-stale-session-")
    PathUtil.setDataRoot(tmp)
    f(tmp).guarantee(IO(PathUtil.setDataRoot(original)) *> IO(os.remove.all(tmp)))

  /** Fast Anthropic-SSE mock (multi-threaded — a serial executor would mask
    * concurrency behavior under test). */
  private def startMock(port: Int, hits: AtomicInteger): IO[HttpServer] =
    IO.blocking {
      val server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0)
      server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool())
      server.createContext(
        "/v1/messages",
        (exchange: HttpExchange) =>
          val _ = exchange.getRequestBody.readAllBytes()
          hits.incrementAndGet()
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

  private def request(sessionId: String): LlmRequest =
    LlmRequest(
      messages = List(Message(MessageRole.User, Left("hi"))),
      sessionId = sessionId,
      agentId = "stale-spec-agent",
      agentModel = Some(AgentModelConfig(preferred = Some("b/m1")))
    )

  // ── reload-time invalidation ──────────────────────────────────

  test("reloadConfig drops session overrides whose provider was removed (#33)") {
    withTempDataRoot { root =>
      for
        _ <- IO(writeConfig(root, Map("a" -> 19101, "b" -> 19102)))
        configRef <- Ref.of[IO, NebflowServiceConfig](Config.loadServiceConfig())
        overrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
        result <- LlmInterface.createLlm(overrides, None, Some(configRef)).flatMap { case (_, registry, _, release) =>
          for
            candA <- registry.getCandidateForRef("a/m1")
            candB <- registry.getCandidateForRef("b/m1")
            _ <- IO(assert(candA.isDefined && candB.isDefined, "candidates a/m1 + b/m1 must resolve in initial config"))
            _ <- overrides.set(Map("s1" -> candA.get, "s2" -> candB.get))
            // Hot-reload: provider "a" is removed from the config on disk.
            _ <- IO(writeConfig(root, Map("b" -> 19102)))
            staleIds <- registry.reloadConfig(Some(overrides))
            remaining <- overrides.get
            _ <- release
          yield (staleIds, remaining)
        }
        (staleIds, remaining) = result
      yield
        assertEquals(staleIds, List("s1"), "only the session whose provider was removed must be dropped")
        assertEquals(remaining.keySet, Set("s2"), "overrides for still-configured providers must survive")
    }
  }

  // ── request path (end-to-end) ────────────────────────────────

  test("E2E: stale session override after reload gracefully skips to the new chain (#33)") {
    val hitsB = new AtomicInteger(0)
    withTempDataRoot { root =>
      for
        serverB <- startMock(19112, hitsB)
        _ <- IO(writeConfig(root, Map("a" -> 19111, "b" -> 19112)))
        configRef <- Ref.of[IO, NebflowServiceConfig](Config.loadServiceConfig())
        overrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
        attempts <- Ref.of[IO, List[FallbackAttempt]](Nil)
        result <- LlmInterface.createLlm(overrides, None, Some(configRef)).flatMap { case (handle, registry, _, release) =>
          for
            candA <- registry.getCandidateForRef("a/m1")
            _ <- IO(assert(candA.isDefined, "a/m1 must resolve in initial config"))
            _ <- overrides.set(Map("s1" -> candA.get))
            // Hot-reload removes provider "a" → the override for s1 is dropped.
            _ <- IO(writeConfig(root, Map("b" -> 19112)))
            staleIds <- registry.reloadConfig(Some(overrides))
            t0 <- IO.monotonic
            chunks <- handle
              .sendStream(request("s1"), onAttempt = Some(a => attempts.update(_ :+ a)))
              .compile
              .toList
            elapsed <- IO.monotonic.map(_ - t0)
            remaining <- overrides.get
            _ <- release
          yield (staleIds, chunks, elapsed, remaining)
        }
        (staleIds, chunks, elapsed, remaining) = result
        _ <- IO.blocking(serverB.stop(0))
        allAttempts <- attempts.get
      yield
        assertEquals(staleIds, List("s1"), "reload must drop the stale override for s1")
        assertEquals(remaining.keySet, Set.empty)
        assert(chunks.exists { case _: StreamChunk.Done => true; case _ => false }, "turn must complete end-to-end")
        assertEquals(hitsB.get, 1, "the new chain's provider must serve exactly one request")
        assert(
          allAttempts.forall(_.providerId != "a"),
          s"the stale provider must never be attempted: $allAttempts"
        )
        assert(
          elapsed < 5.seconds,
          s"request must not burn retries on the stale provider (elapsed ${elapsed.toMillis}ms)"
        )
    }
  }

  test("request-time filter: stale override is skipped even without a reload (#33)") {
    val hitsB = new AtomicInteger(0)
    withTempDataRoot { root =>
      for
        serverB <- startMock(19122, hitsB)
        _ <- IO(writeConfig(root, Map("b" -> 19122)))
        configRef <- Ref.of[IO, NebflowServiceConfig](Config.loadServiceConfig())
        // Stale override for a provider that does NOT exist in the config at
        // all — simulates a race / a pre-fix leftover (reload never ran).
        staleCandidate = ModelCandidate(
          providerId = "a",
          provider = ProviderConfig(
            baseUrl = "http://127.0.0.1:19121",
            apiKey = "k",
            protocol = LlmProtocol.Anthropic,
            models = List(ModelConfig("m1", maxTokens = 1024, contextWindow = 8192, vision = Some(false)))
          ),
          model = "m1",
          maxTokens = 1024,
          contextWindow = 8192,
          vision = false
        )
        overrides <- Ref.of[IO, Map[String, ModelCandidate]](Map("s1" -> staleCandidate))
        attempts <- Ref.of[IO, List[FallbackAttempt]](Nil)
        result <- LlmInterface.createLlm(overrides, None, Some(configRef)).flatMap { case (handle, _, _, release) =>
          for
            t0 <- IO.monotonic
            chunks <- handle
              .sendStream(request("s1"), onAttempt = Some(a => attempts.update(_ :+ a)))
              .compile
              .toList
            elapsed <- IO.monotonic.map(_ - t0)
            _ <- release
          yield (chunks, elapsed)
        }
        (chunks, elapsed) = result
        _ <- IO.blocking(serverB.stop(0))
        allAttempts <- attempts.get
      yield
        assert(chunks.exists { case _: StreamChunk.Done => true; case _ => false }, "turn must complete end-to-end")
        assertEquals(hitsB.get, 1, "the valid provider must serve the request")
        assert(
          allAttempts.forall(_.providerId != "a"),
          s"the unknown provider must be skipped before any attempt: $allAttempts"
        )
        assert(
          elapsed < 3.seconds,
          s"no retry/backoff on the stale provider allowed (elapsed ${elapsed.toMillis}ms)"
        )
    }
  }

end ReloadStaleSessionModelSpec
