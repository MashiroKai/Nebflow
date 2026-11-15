package nebflow.llm

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.shared.*

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8

/** Loopback-only synthetic SSE providers exercise the real winner/fallback policy. */
class MermaidStreamFallbackSpec extends CatsEffectSuite:
  private val markdown = "```mermaid\nflowchart TD\n  A[Start] --> B[End]\n```"

  private def provider(body: String): Resource[IO, (String, Ref[IO, Int])] =
    for
      calls <- Resource.eval(Ref.of[IO, Int](0))
      server <- Resource.make(IO.blocking {
        val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(
          "/",
          (exchange: HttpExchange) =>
            // This handler runs off the cats-effect runtime; retain a simple request counter.
            val _ = exchange.getRequestBody.readAllBytes()
            import cats.effect.unsafe.implicits.global
            calls.update(_ + 1).unsafeRunSync()
            val bytes = body.getBytes(UTF_8)
            exchange.getResponseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.length.toLong)
            exchange.getResponseBody.write(bytes)
            exchange.close()
        )
        server.start()
        server
      })(server => IO.blocking(server.stop(0)))
    yield (s"http://127.0.0.1:${server.getAddress.getPort}", calls)

  private def openAi(parts: List[String], field: String = "content"): String =
    val frames = parts.map(text => Json.obj("choices" -> Json.arr(Json.obj("delta" -> Json.obj(field -> text.asJson)))))
    (frames :+ Json.obj("choices" -> Json.arr(Json.obj("delta" -> Json.obj(), "finish_reason" -> "stop".asJson))))
      .map(json => s"data: ${json.noSpaces}\n\n")
      .mkString + "data: [DONE]\n\n"

  private def anthropic(text: String): String =
    List(
      Json.obj("type" -> "message_start".asJson, "message" -> Json.obj("usage" -> Json.obj())),
      Json.obj(
        "type" -> "content_block_delta".asJson,
        "index" -> 0.asJson,
        "delta" -> Json.obj("type" -> "text_delta".asJson, "text" -> text.asJson)
      ),
      Json.obj(
        "type" -> "message_delta".asJson,
        "delta" -> Json.obj("stop_reason" -> "end_turn".asJson),
        "usage" -> Json.obj()
      ),
      Json.obj("type" -> "message_stop".asJson)
    ).map(json => s"event: ${json.hcursor.downField("type").as[String].getOrElse("")}\ndata: ${json.noSpaces}\n\n")
      .mkString

  private def run(first: String, second: String, protocol: LlmProtocol): IO[List[StreamChunk]] =
    val config = NebflowServiceConfig(llm =
      ServiceLlmConfig(providers =
        Map(
          "first" -> ProviderConfig(
            baseUrl = first,
            apiKey = "synthetic",
            protocol = protocol,
            models = List(ModelConfig("m"))
          ),
          "second" -> ProviderConfig(
            baseUrl = second,
            apiKey = "synthetic",
            protocol = LlmProtocol.OpenAI,
            models = List(ModelConfig("m"))
          )
        )
      )
    )
    for
      ref <- Ref.of[IO, NebflowServiceConfig](config)
      overrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      chunks <- Resource.make(LlmInterface.createLlm(overrides, None, Some(ref)))(_._4).use { case (handle, _, _, _) =>
        handle
          .sendStream(
            LlmRequest(
              messages = List(Message(MessageRole.User, Left("Synthetic diagram"))),
              sessionId = "synthetic-mermaid-fallback",
              agentId = "synthetic-mermaid-fallback",
              agentModel = Some(AgentModelConfig(preferred = Some("first/m"), fallbacks = List("second/m")))
            )
          )
          .compile
          .toList
      }
    yield chunks

    end for

  end run

  test("OpenAI blank text and reasoning do not lock winner and retain no-content fallback") {
    List("content", "reasoning_content").foldLeft(IO.unit) { (previous, field) =>
      previous *> {
        (provider(openAi(List("\n", "  ", "\t"), field)), provider(openAi(List("fallback")))).tupled.use {
          case ((first, _), (second, calls)) =>
            for
              result <- run(first, second, LlmProtocol.OpenAI).attempt
              count <- calls.get
            yield
              // Current policy has one candidate: empty responses exhaust rather than silently succeed.
              assertEquals(count, 0)
              result match
                case Left(error: FallbackExhaustedError) =>
                  assert(error.attempts.nonEmpty)
                  assert(error.attempts.forall(_.message.exists(_.contains("Stream completed with no content"))))
                case other => fail(s"Expected no-content exhaustion, got $other")
        }
      }
    }
  }

  test("OpenAI Mermaid fragments retain every byte through winner selection") {
    (
      provider(openAi(List("\n", "```mermaid", "\n", "flowchart TD\n  A[Start] --> B[End]", "\n```"))),
      provider(openAi(List("unused")))
    ).tupled.use { case ((first, _), (second, calls)) =>
      for
        chunks <- run(first, second, LlmProtocol.OpenAI)
        count <- calls.get
      yield
        assertEquals(chunks.collect { case StreamChunk.TextDelta(delta) => delta }.mkString, "\n" + markdown)
        assertEquals(count, 0)
    }
  }

  test("Anthropic whitespace response keeps its existing winner behavior") {
    (provider(anthropic("\n  ")), provider(openAi(List("unused")))).tupled.use { case ((first, _), (second, calls)) =>
      for
        chunks <- run(first, second, LlmProtocol.Anthropic)
        count <- calls.get
      yield
        assertEquals(chunks.collect { case StreamChunk.TextDelta(delta) => delta }.mkString, "\n  ")
        assertEquals(chunks.collect { case d: StreamChunk.Done => d.meta.map(_.providerId) }.flatten.last, "first")
        assertEquals(count, 0)
    }
  }
end MermaidStreamFallbackSpec
